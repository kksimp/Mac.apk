// screen_camera.mm -- the optional "screen + camera" picture (screen_camera.h).
//
// HOW A FRAME IS MADE. camera_capture.mm hands every frame of the built-in
// camera to macapk_screencam_compose(). That draws, with Core Image, into a
// new buffer of the SAME size and pixel format as the camera frame:
//   1. black, then the newest screen image scaled to fit (letterboxed, never
//      cropped or stretched);
//   2. soft shadows, so the overlay appears to float above the screen;
//   3. the camera image in the chosen corner: the full camera frame for the
//      rounded and sharp rectangles, a circle cut from its centre for circle;
//   4. a faint light hairline around it, visible on dark screens.
// Steps 2 to 4 are left out while macOS's own Presenter Overlay is on (the
// user switches it on from the Video item in the menu bar while sharing):
// macOS then draws the user into the screen image itself, in its Small or
// Large style, and a second copy of the camera would only duplicate it.
// Because size and format match, nothing downstream changes: ImageReader,
// SurfaceTexture and the app see an ordinary camera frame.
//
// THE SCREEN is captured by ScreenCaptureKit at up to 15 frames a second,
// and only while a camera session is running AND the screen is showing (the
// View menu switch), so macOS's screen-recording indicator is shown exactly
// while the screen is being sent. What is captured is the main display, or
// one window or display the user chose in macOS's sharing picker (View >
// Choose What to Share).
// The camera's own frame rate drives the output; each camera frame takes the
// newest screen image.
//
// ARC translation unit (CMakeLists.txt), like user_verification_bridge.mm.
// Core Image coordinates have their origin at the BOTTOM-left; "top" corners
// are therefore near y = height.

#import <CoreImage/CoreImage.h>
#import <Foundation/Foundation.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>

#include "macapk/screen_camera.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>   // getpid, to leave this app's own windows out of the capture

namespace {

// ---- Options (environment, read once) --------------------------------------

enum class Shape { Rounded, Circle, Rect };

struct Options {
    bool on = false;            // the feature is available in this app
    bool startShowing = false;  // the screen shows from launch (else plain camera)
    bool left = false;          // corner: left or right edge
    bool top = false;           // corner: top or bottom edge
    double heightFraction = 0.30;
    Shape shape = Shape::Rounded;
};

const char* ShapeName(Shape s) {
    switch (s) {
        case Shape::Rounded: return "rounded";
        case Shape::Circle:  return "circle";
        case Shape::Rect:    return "rect";
    }
    return "?";
}

Options ReadOptions() {
    Options o;
    const char* on = std::getenv("MACAPK_SCREEN_CAMERA");
    o.on = on && std::strcmp(on, "1") == 0;
    if (!o.on) return o;

    if (const char* start = std::getenv("MACAPK_SCREEN_CAMERA_START")) {
        std::string value(start);
        if (value == "on") o.startShowing = true;
        else if (value != "off")
            std::fprintf(stderr, "[W] screen-camera: MACAPK_SCREEN_CAMERA_START=%s is not on "
                         "or off; using off\n", start);
    }
    if (const char* c = std::getenv("MACAPK_SCREEN_CAMERA_CORNER")) {
        std::string corner(c);
        if (corner == "top-left")          { o.top = true;  o.left = true;  }
        else if (corner == "top-right")    { o.top = true;  o.left = false; }
        else if (corner == "bottom-left")  { o.top = false; o.left = true;  }
        else if (corner != "bottom-right")
            std::fprintf(stderr, "[W] screen-camera: MACAPK_SCREEN_CAMERA_CORNER=%s is not "
                         "top-left, top-right, bottom-left or bottom-right; using bottom-right\n", c);
    }
    if (const char* sz = std::getenv("MACAPK_SCREEN_CAMERA_SIZE")) {
        char* end = nullptr;
        long percent = std::strtol(sz, &end, 10);
        if (end != sz && *end == '\0' && percent >= 10 && percent <= 90) {
            o.heightFraction = percent / 100.0;
        } else {
            std::fprintf(stderr, "[W] screen-camera: MACAPK_SCREEN_CAMERA_SIZE=%s is not a "
                         "percent from 10 to 90; using 30\n", sz);
        }
    }
    if (const char* sh = std::getenv("MACAPK_SCREEN_CAMERA_SHAPE")) {
        std::string shape(sh);
        if (shape == "circle")    o.shape = Shape::Circle;
        else if (shape == "rect") o.shape = Shape::Rect;
        else if (shape != "rounded")
            std::fprintf(stderr, "[W] screen-camera: MACAPK_SCREEN_CAMERA_SHAPE=%s is not "
                         "rounded, circle or rect; using rounded\n", sh);
    }
    std::fprintf(stderr, "[I] screen-camera: available, starting %s (%s-%s, %d%% of the "
                 "frame height, %s; View > Show Screen in Camera switches it, View > "
                 "Choose What to Share picks a window or display)\n",
                 o.startShowing ? "with the screen" : "with the plain camera",
                 o.top ? "top" : "bottom", o.left ? "left" : "right",
                 (int)std::lround(o.heightFraction * 100), ShapeName(o.shape));
    return o;
}

const Options& Opts() {
    static const Options options = ReadOptions();
    return options;
}

// ---- Screen capture ------------------------------------------------------------

std::mutex gScreenLock;                    // guards gScreenLatest
CVPixelBufferRef gScreenLatest = nullptr;  // newest complete screen frame (+1)

// Which feed may publish frames: StopCapture is asynchronous, so a stopped
// stream can still deliver a frame or two afterwards, and that frame must not
// reappear as the first screen of the next showing. Each feed is given the
// epoch it started under; ending a feed moves the epoch on.
uint64_t gLiveFeedEpoch = 0;                // guarded by gScreenLock
// macOS's Presenter Overlay is on for the live feed: its frames already show
// the user, so compose() leaves out its own camera overlay. Reported by the
// stream's delegate; a new or ended feed starts without it.
bool gPresenterOverlay = false;             // guarded by gScreenLock

/** A new feed starts: frames from any earlier one are refused from now. */
uint64_t BeginScreenFeed() {
    std::lock_guard<std::mutex> g(gScreenLock);
    gPresenterOverlay = false;
    return ++gLiveFeedEpoch;
}

/** The feed ended: refuse its late frames and stop showing its last one. */
void EndScreenFeed() {
    CVPixelBufferRef old;
    {
        std::lock_guard<std::mutex> g(gScreenLock);
        ++gLiveFeedEpoch;
        gPresenterOverlay = false;
        old = gScreenLatest;
        gScreenLatest = nullptr;
    }
    if (old) CFRelease(old);
}

/** A feed's frame (+1): kept only if that feed is still the live one. */
void OfferScreenFrame(uint64_t epoch, CVPixelBufferRef next) {
    CVPixelBufferRef old;
    {
        std::lock_guard<std::mutex> g(gScreenLock);
        if (epoch != gLiveFeedEpoch) {
            old = next;
        } else {
            old = gScreenLatest;
            gScreenLatest = next;
        }
    }
    if (old) CFRelease(old);
}

/** A feed's report that Presenter Overlay started or stopped: kept only if
 *  that feed is still the live one. */
void SetPresenterOverlay(uint64_t epoch, bool on) {
    std::lock_guard<std::mutex> g(gScreenLock);
    if (epoch == gLiveFeedEpoch) gPresenterOverlay = on;
}

/** A feed has nothing to show (see the frame statuses in the stream output):
 *  its last image is dropped, so the plain camera is sent until it shows
 *  something again. Ignored from a feed that is no longer the live one. */
void DropScreenFrame(uint64_t epoch) {
    CVPixelBufferRef old = nullptr;
    {
        std::lock_guard<std::mutex> g(gScreenLock);
        if (epoch != gLiveFeedEpoch) return;
        old = gScreenLatest;
        gScreenLatest = nullptr;
    }
    if (old) CFRelease(old);
}

/** The newest screen image (+1, or null), and whether macOS's Presenter
 *  Overlay was on for it. */
CVPixelBufferRef CopyLatestScreen(bool* presenterOverlay) {
    std::lock_guard<std::mutex> g(gScreenLock);
    *presenterOverlay = gPresenterOverlay;
    return gScreenLatest ? (CVPixelBufferRef)CFRetain(gScreenLatest) : nullptr;
}

// macOS ended `stream` on its own (display gone, permission revoked...).
// Defined with the capture control below.
void ForgetEndedStream(SCStream* stream);

}  // namespace

/** Receives ScreenCaptureKit frames and keeps the newest complete one. */
@interface MacapkScreenFeed : NSObject <SCStreamOutput, SCStreamDelegate>
- (instancetype)initWithEpoch:(uint64_t)epoch;
@end

@implementation MacapkScreenFeed {
    uint64_t _epoch;   // see gLiveFeedEpoch
    bool _empty;       // the last status had nothing to show (logged once per change)
}

- (instancetype)initWithEpoch:(uint64_t)epoch {
    if ((self = [super init])) _epoch = epoch;
    return self;
}

- (void)stream:(SCStream*)stream
    didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
                   ofType:(SCStreamOutputType)type {
    if (type != SCStreamOutputTypeScreen) return;
    CFArrayRef info = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, false);
    if (info && CFArrayGetCount(info) > 0) {
        NSDictionary* attachments = (__bridge NSDictionary*)CFArrayGetValueAtIndex(info, 0);
        NSNumber* status = attachments[SCStreamFrameInfoStatus];
        if (status && status.integerValue != SCFrameStatusComplete) {
            // "Blank" and "stopped" mean there is nothing to show -- a chosen
            // window that closed, for one, if macOS keeps the stream alive
            // rather than ending it -- so the last image is NOT kept: holding
            // it would send a frozen picture of something that is gone. Other
            // statuses (idle: the screen did not change; suspended) keep it.
            const NSInteger s = status.integerValue;
            if (s == SCFrameStatusBlank || s == SCFrameStatusStopped) {
                DropScreenFrame(_epoch);
                if (!_empty) {
                    _empty = true;
                    std::fprintf(stderr, "[I] screen-camera: nothing to capture (frame status "
                                 "%ld); sending the plain camera\n", (long)s);
                }
            }
            return;
        }
    }
    _empty = false;
    CVPixelBufferRef frame = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (frame) OfferScreenFrame(_epoch, (CVPixelBufferRef)CFRetain(frame));
}

- (void)stream:(SCStream*)stream didStopWithError:(NSError*)error {
    std::fprintf(stderr, "[W] screen-camera: screen capture stopped: %s; sending the plain "
                 "camera (switching the screen on again retries what was chosen; "
                 "View > Choose What to Share picks something else)\n",
                 error.localizedDescription.UTF8String ?: "(no reason given)");
    ForgetEndedStream(stream);
}

// macOS's Presenter Overlay (Small or Large), switched by the user from the
// Video item in the menu bar while this stream runs.
- (void)outputVideoEffectDidStartForStream:(SCStream*)stream {
    std::fprintf(stderr, "[I] screen-camera: macOS Presenter Overlay on; sending the screen "
                 "with macOS's overlay, without this app's own\n");
    SetPresenterOverlay(_epoch, true);
}

- (void)outputVideoEffectDidStopForStream:(SCStream*)stream {
    std::fprintf(stderr, "[I] screen-camera: macOS Presenter Overlay off; this app's own "
                 "camera overlay is back\n");
    SetPresenterOverlay(_epoch, false);
}

@end

namespace {

// Start and stop run on one serial queue, so a stop that arrives while a start
// is still waiting for macOS cannot interleave with it. `gGeneration` changes
// on every stop; a start whose generation is stale abandons itself.
dispatch_queue_t ControlQueue() {
    static dispatch_queue_t queue =
        dispatch_queue_create("org.macapk.screen-camera.control", DISPATCH_QUEUE_SERIAL);
    return queue;
}
int gSessions = 0;               // running camera sessions that may composite (control queue)
// What to capture: nil for the main display (the default), else the user's
// choice from macOS's sharing picker (control queue).
SCContentFilter* gChosen = nil;
bool gWantShowing = false;       // the menu switch, as last applied (control queue)
uint64_t gGeneration = 0;        // (control queue)
SCStream* gStream = nil;         // (control queue)
MacapkScreenFeed* gFeed = nil;   // the stream's output; held strongly here

/** The whole of `display`, less this app's own windows (nil: no display).
 *  They are left out because the call window shows the camera preview, which
 *  would contain this picture, which contains the preview again, down to a
 *  speck -- for the other side as well. Another display stands in for a
 *  missing `wanted` one only when it was not the user's choice (`chosen`):
 *  sending a display the user did not pick could show the other side
 *  something private. */
SCContentFilter* DisplayFilter(SCShareableContent* content, CGDirectDisplayID wanted, bool chosen) {
    SCDisplay* display = nil;
    for (SCDisplay* d in content.displays) {
        if (d.displayID == wanted) { display = d; break; }
    }
    if (!display && !chosen) display = content.displays.firstObject;
    if (!display) return nil;
    SCRunningApplication* self_app = nil;
    for (SCRunningApplication* app in content.applications) {
        if (app.processID == getpid()) { self_app = app; break; }
    }
    return self_app
        ? [[SCContentFilter alloc] initWithDisplay:display
                             excludingApplications:@[self_app]
                                  exceptingWindows:@[]]
        : [[SCContentFilter alloc] initWithDisplay:display excludingWindows:@[]];
}

void StartCapture(uint64_t generation, SCShareableContent* content) {
    if (generation != gGeneration || gSessions == 0 || !gWantShowing || gStream) return;

    // A chosen window is captured alone, by macOS, wherever it is and
    // whatever floats over it (this app's windows, a picture-in-picture
    // window): only its own pixels are sent. A chosen display is rebuilt
    // here so that this app's windows are still left out of it.
    SCContentFilter* filter = nil;
    std::string what;
    if (gChosen && gChosen.style == SCShareableContentStyleWindow) {
        filter = gChosen;
        // The app's name only: a window's title can carry private text, and
        // this line ends up in logs that users attach to bug reports.
        SCWindow* window = gChosen.includedWindows.firstObject;
        NSString* app = window.owningApplication.applicationName;
        what = std::string("a window of ") + (app.length ? app.UTF8String : "an app");
    } else {
        const bool chosen = gChosen != nil;
        const CGDirectDisplayID wanted = chosen
            ? gChosen.includedDisplays.firstObject.displayID : CGMainDisplayID();
        filter = DisplayFilter(content, wanted, chosen);
        if (!filter) {
            std::fprintf(stderr, chosen
                ? "[W] screen-camera: the chosen display is gone; sending the plain camera "
                  "until something is chosen again\n"
                : "[W] screen-camera: no display to capture; sending the plain camera\n");
            return;
        }
        what = "display " + std::to_string(filter.includedDisplays.firstObject.displayID);
    }
    SCStreamConfiguration* config = [[SCStreamConfiguration alloc] init];
    // Pixel size, capped at 1920 wide: the output is a camera-sized frame
    // (often 640x480 to 1280x720), so more detail than this is never seen.
    CGFloat scale = filter.pointPixelScale > 0 ? filter.pointPixelScale : 1;
    CGFloat w = filter.contentRect.size.width * scale;
    CGFloat h = filter.contentRect.size.height * scale;
    if (w > 1920) { h = h * 1920 / w; w = 1920; }
    config.width = (size_t)w;
    config.height = (size_t)h;
    config.pixelFormat = kCVPixelFormatType_32BGRA;
    config.minimumFrameInterval = CMTimeMake(1, 15);
    config.showsCursor = YES;
    config.queueDepth = 4;

    gFeed = [[MacapkScreenFeed alloc] initWithEpoch:BeginScreenFeed()];
    gStream = [[SCStream alloc] initWithFilter:filter configuration:config delegate:gFeed];
    NSError* error = nil;
    dispatch_queue_t frames =
        dispatch_queue_create("org.macapk.screen-camera.frames", DISPATCH_QUEUE_SERIAL);
    if (![gStream addStreamOutput:gFeed type:SCStreamOutputTypeScreen
               sampleHandlerQueue:frames error:&error]) {
        std::fprintf(stderr, "[W] screen-camera: could not receive screen frames: %s\n",
                     error.localizedDescription.UTF8String ?: "?");
        gStream = nil;
        gFeed = nil;
        EndScreenFeed();
        return;
    }
    SCStream* stream = gStream;
    [stream startCaptureWithCompletionHandler:^(NSError* startError) {
        if (startError) {
            std::fprintf(stderr, "[W] screen-camera: screen capture did not start: %s; "
                         "sending the plain camera\n",
                         startError.localizedDescription.UTF8String ?: "?");
            // Forgotten, so the next camera session or menu switch asks again
            // (after the user allows Screen Recording, say).
            ForgetEndedStream(stream);
        } else {
            std::fprintf(stderr, "[I] screen-camera: capturing %s at %zux%zu\n",
                         what.c_str(), config.width, config.height);
        }
    }];
}

void RequestCapture() {
    const uint64_t generation = gGeneration;
    if (gChosen && gChosen.style != SCShareableContentStyleWindow
                && gChosen.style != SCShareableContentStyleDisplay) {
        // The picker is set to one window or one display; anything else (an
        // application, from the menu bar's sharing item) is not sent. Refused
        // here, before asking for shareable content, which could raise the
        // Screen Recording prompt for nothing.
        std::fprintf(stderr, "[W] screen-camera: the choice is neither a window nor a display; "
                     "sending the plain camera. View > Choose What to Share picks again.\n");
        return;
    }
    // A chosen window needs no list of shareable content (macOS's picker
    // granted it), and so no Screen Recording permission either.
    if (gChosen && gChosen.style == SCShareableContentStyleWindow) {
        dispatch_async(ControlQueue(), ^{ StartCapture(generation, nil); });
        return;
    }
    // Asking for shareable content is what raises the Screen Recording prompt
    // the first time; a refusal arrives here as an error.
    [SCShareableContent getShareableContentExcludingDesktopWindows:NO
                                               onScreenWindowsOnly:YES
                                                 completionHandler:^(SCShareableContent* content,
                                                                     NSError* error) {
        if (!content) {
            std::fprintf(stderr, "[W] screen-camera: the screen cannot be captured (%s); "
                         "sending the plain camera. Allow Screen Recording for this app in "
                         "System Settings > Privacy & Security, then reopen it.\n",
                         error.localizedDescription.UTF8String ?: "no reason given");
            return;
        }
        dispatch_async(ControlQueue(), ^{ StartCapture(generation, content); });
    }];
}

void StopCapture() {
    gGeneration++;
    if (gStream) [gStream stopCaptureWithCompletionHandler:nil];
    gStream = nil;
    gFeed = nil;
    EndScreenFeed();   // a stopped feed must not be shown as live
}

/** Forget a stream macOS ended, so switching the screen on again starts a
 *  new one; until then the plain camera is sent. */
void ForgetEndedStream(SCStream* stream) {
    dispatch_async(ControlQueue(), ^{
        if (gStream != stream) return;   // already replaced or stopped
        gStream = nil;
        gFeed = nil;
        EndScreenFeed();
    });
}

/** Control queue: capture exactly while a session runs and the screen shows. */
void UpdateCapture() {
    const bool want = gSessions > 0 && gWantShowing;
    if (want && !gStream) RequestCapture();   // StartCapture ignores a duplicate
    else if (!want && gStream) StopCapture();
    else if (!want) gGeneration++;            // cancel a request still in flight
}

// The switch compose() reads on every frame; the control queue mirrors it.
std::atomic<bool>& Showing() {
    static std::atomic<bool> showing{Opts().startShowing};
    return showing;
}

/** Control queue: capture `filter` from now on (see gChosen). */
void UseChosenContent(SCContentFilter* filter) {
    gChosen = filter;
    if (gStream) StopCapture();   // restarted with the new choice below
    UpdateCapture();
}

/** This app's own windows, by number: left out of the sharing picker, for the
 *  same reason they are left out of a display (DisplayFilter). */
NSArray<NSNumber*>* OwnWindowNumbers() {
    NSMutableArray<NSNumber*>* numbers = [NSMutableArray array];
    CFArrayRef windows = CGWindowListCopyWindowInfo(kCGWindowListOptionAll, kCGNullWindowID);
    if (!windows) return numbers;
    for (NSDictionary* info in (__bridge NSArray*)windows) {
        if ([info[(__bridge NSString*)kCGWindowOwnerPID] intValue] == getpid())
            [numbers addObject:info[(__bridge NSString*)kCGWindowNumber]];
    }
    CFRelease(windows);
    return numbers;
}

// ---- Compositing ----------------------------------------------------------------

/** Everything that depends only on the frame size, made once per size. */
struct Layout {
    size_t width = 0, height = 0;
    OSType format = 0;
    CGRect canvas = CGRectZero;
    CGRect overlay = CGRectZero;    // where the camera image goes
    CGRect cameraSource = CGRectZero;  // the part of the camera frame shown in it
    CIImage* mask = nil;            // white, opaque where the overlay is
    CIImage* shadow = nil;          // pre-rendered shadows, drawn under the overlay
    CIImage* outline = nil;         // pre-rendered hairline, drawn over its edge
    CVPixelBufferPoolRef pool = nullptr;
};

std::mutex gComposeLock;   // serialises compose(); guards gLayout
Layout gLayout;

CIContext* Context() {
    static CIContext* context = [CIContext contextWithOptions:@{kCIContextCacheIntermediates: @NO}];
    return context;
}

/** White, opaque inside `rect` with corners of `radius` (0: square). */
CIImage* ShapeImage(CGRect rect, CGFloat radius) {
    if (radius <= 0) {
        return [[CIImage imageWithColor:CIColor.whiteColor] imageByCroppingToRect:rect];
    }
    CIFilter* shape = [CIFilter filterWithName:@"CIRoundedRectangleGenerator"];
    [shape setValue:[CIVector vectorWithCGRect:rect] forKey:@"inputExtent"];
    [shape setValue:@(radius) forKey:@"inputRadius"];
    [shape setValue:CIColor.whiteColor forKey:@"inputColor"];
    return shape.outputImage;
}

/** `mask`'s shape filled with grey level `gray` (0 black, 1 white) at `alpha`. */
CIImage* TintedImage(CIImage* mask, CGFloat gray, CGFloat alpha) {
    CIVector* zero = [CIVector vectorWithX:0 Y:0 Z:0 W:0];
    return [mask imageByApplyingFilter:@"CIColorMatrix" withInputParameters:@{
        @"inputRVector": zero, @"inputGVector": zero, @"inputBVector": zero,
        @"inputAVector": [CIVector vectorWithX:0 Y:0 Z:0 W:alpha],
        @"inputBiasVector": [CIVector vectorWithX:gray Y:gray Z:gray W:0],
    }];
}

/** A black shadow of `mask`: blurred by `sigma`, moved down by `drop`. */
CIImage* ShadowImage(CIImage* mask, CGFloat sigma, CGFloat drop, CGFloat alpha) {
    return [[TintedImage(mask, 0, alpha) imageByApplyingGaussianBlurWithSigma:sigma]
        imageByApplyingTransform:CGAffineTransformMakeTranslation(0, -drop)];
}

/** The colour space to render the composite in (+1): the camera frame's own,
 *  from its colour tags, else sRGB. Never nil: with no colour space Core
 *  Image skips colour matching and writes its LINEAR working values, which
 *  come out visibly too dark -- measured 2026-11-13 on a test frame. */
CGColorSpaceRef CopyOutputColorSpace(CVPixelBufferRef camera) {
    if (CGColorSpaceRef tagged = CVImageBufferGetColorSpace(camera)) {
        return CGColorSpaceRetain(tagged);
    }
    CGColorSpaceRef derived = nullptr;
    if (CFDictionaryRef tags = CVBufferCopyAttachments(camera, kCVAttachmentMode_ShouldPropagate)) {
        derived = CVImageBufferCreateColorSpaceFromAttachments(tags);
        CFRelease(tags);
    }
    return derived ? derived : CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
}

/** `image` drawn once into a bitmap covering `rect` (nil if that fails). */
CIImage* Rasterize(CIImage* image, CGRect rect) {
    CGImageRef bitmap = [Context() createCGImage:image fromRect:rect];
    if (!bitmap) return nil;
    CIImage* result = [CIImage imageWithCGImage:bitmap];
    CGImageRelease(bitmap);
    return result;
}

void BuildLayout(Layout& L, size_t width, size_t height, OSType format) {
    const Options& o = Opts();
    if (L.pool) CVPixelBufferPoolRelease(L.pool);
    L = Layout();
    L.width = width;
    L.height = height;
    L.format = format;
    L.canvas = CGRectMake(0, 0, width, height);

    // The overlay's height is the chosen share of the frame height. A
    // rectangle shows the whole camera frame, so its width follows the camera's
    // aspect ratio; the circle is as wide as it is tall.
    const CGFloat margin = std::round(height * 0.03);
    CGFloat h = std::round(height * o.heightFraction);
    CGFloat w = (o.shape == Shape::Circle) ? h : std::round(h * width / (CGFloat)height);
    if (w > width - 2 * margin) {          // a very tall share of a narrow frame
        h = std::round(h * (width - 2 * margin) / w);
        w = width - 2 * margin;
    }
    const CGFloat x = o.left ? margin : width - margin - w;
    const CGFloat y = o.top ? height - margin - h : margin;
    L.overlay = CGRectMake(x, y, w, h);

    if (o.shape == Shape::Circle) {
        const CGFloat side = std::min(width, height);
        L.cameraSource = CGRectMake((width - side) / 2, (height - side) / 2, side, side);
    } else {
        L.cameraSource = L.canvas;
    }

    // The overlay's shape: a rounded rectangle, a circle (a rounded square
    // whose radius is half its side), or the plain rectangle.
    const CGFloat radius = o.shape == Shape::Circle  ? h / 2
                         : o.shape == Shape::Rounded ? std::round(h * 0.12) : 0;
    L.mask = ShapeImage(L.overlay, radius);

    // The floating effect, as macOS and calling apps draw floating video: a
    // wide, soft shadow that lifts the overlay off the screen, a tight one that
    // defines its edge, and a faint light hairline so it still stands out on a
    // dark screen. Sized from the overlay, not the frame, so a small overlay
    // gets a proportionate shadow. Rendered once to bitmaps so frames do not
    // re-blur anything.
    CIImage* ambient = ShadowImage(L.mask, std::max(4.0, h * 0.10), h * 0.04, 0.55);
    CIImage* contact = ShadowImage(L.mask, std::max(1.5, h * 0.02), h * 0.01, 0.35);
    L.shadow = Rasterize([contact imageByCompositingOverImage:ambient], L.canvas);

    const CGFloat hairline = std::max<CGFloat>(1, std::round(height / 480.0));
    CIImage* inner = ShapeImage(CGRectInset(L.overlay, hairline, hairline),
                                std::max<CGFloat>(0, radius - hairline));
    CIImage* ring = [L.mask imageByApplyingFilter:@"CISourceOutCompositing"
                              withInputParameters:@{kCIInputBackgroundImageKey: inner}];
    L.outline = Rasterize(TintedImage(ring, 1.0, 0.35), L.canvas);

    NSDictionary* attributes = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(format),
        (id)kCVPixelBufferWidthKey: @(width),
        (id)kCVPixelBufferHeightKey: @(height),
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        (id)kCVPixelBufferMetalCompatibilityKey: @YES,
    };
    CVPixelBufferPoolCreate(kCFAllocatorDefault, nullptr,
                            (__bridge CFDictionaryRef)attributes, &L.pool);
}

}  // namespace

/** Receives the user's choice from macOS's sharing picker. A choice made
 *  later from the menu bar's screen-sharing item arrives here as well. */
@interface MacapkScreenPickerObserver : NSObject <SCContentSharingPickerObserver>
@end

@implementation MacapkScreenPickerObserver

- (void)contentSharingPicker:(SCContentSharingPicker*)picker
         didUpdateWithFilter:(SCContentFilter*)filter
                   forStream:(SCStream*)stream {
    dispatch_async(ControlQueue(), ^{ UseChosenContent(filter); });
    // Choosing is asking to send it: the screen goes on if it was off.
    macapk_screencam_set_showing(true);
}

- (void)contentSharingPicker:(SCContentSharingPicker*)picker
          didCancelForStream:(SCStream*)stream {
    std::fprintf(stderr, "[I] screen-camera: nothing chosen; what is shown is unchanged\n");
}

- (void)contentSharingPickerStartDidFailWithError:(NSError*)error {
    std::fprintf(stderr, "[W] screen-camera: the sharing picker could not open: %s\n",
                 error.localizedDescription.UTF8String ?: "?");
}

@end

// ---- C surface ------------------------------------------------------------------

bool macapk_screencam_enabled(void) {
    return Opts().on;
}

bool macapk_screencam_showing(void) {
    return Opts().on && Showing().load();
}

void macapk_screencam_set_showing(bool showing) {
    if (!Opts().on) return;
    Showing().store(showing);
    std::fprintf(stderr, "[I] screen-camera: %s\n",
                 showing ? "showing the screen" : "showing the plain camera");
    dispatch_async(ControlQueue(), ^{
        gWantShowing = showing;
        UpdateCapture();
    });
}

void macapk_screencam_choose(void) {
    if (!Opts().on) return;
    static MacapkScreenPickerObserver* observer = nil;   // the picker holds it weakly
    SCContentSharingPicker* picker = SCContentSharingPicker.sharedPicker;
    if (!observer) {
        observer = [[MacapkScreenPickerObserver alloc] init];
        [picker addObserver:observer];
        picker.active = YES;
    }
    // One window or one display. Set on every opening, because this app's
    // windows change while it runs.
    SCContentSharingPickerConfiguration* config = [[SCContentSharingPickerConfiguration alloc] init];
    config.allowedPickerModes =
        SCContentSharingPickerModeSingleWindow | SCContentSharingPickerModeSingleDisplay;
    config.excludedWindowIDs = OwnWindowNumbers();
    picker.defaultConfiguration = config;
    [picker present];
}

void macapk_screencam_session_started(void) {
    if (!Opts().on) return;
    dispatch_async(ControlQueue(), ^{
        gSessions++;
        gWantShowing = Showing().load();
        UpdateCapture();
    });
}

void macapk_screencam_session_stopped(void) {
    if (!Opts().on) return;
    dispatch_async(ControlQueue(), ^{
        if (gSessions > 0) gSessions--;
        UpdateCapture();
    });
}

CVPixelBufferRef macapk_screencam_compose(CVPixelBufferRef camera) {
    if (!camera || !macapk_screencam_showing()) return nullptr;
    bool presenterOverlay = false;
    CVPixelBufferRef screen = CopyLatestScreen(&presenterOverlay);
    if (!screen) return nullptr;   // not captured yet, or refused: plain camera

    CVPixelBufferRef out = nullptr;
    @autoreleasepool {
        std::lock_guard<std::mutex> g(gComposeLock);
        const size_t width = CVPixelBufferGetWidth(camera);
        const size_t height = CVPixelBufferGetHeight(camera);
        const OSType format = CVPixelBufferGetPixelFormatType(camera);
        if (gLayout.width != width || gLayout.height != height || gLayout.format != format) {
            BuildLayout(gLayout, width, height, format);
        }
        const Layout& L = gLayout;

        // 1. Screen, fitted and centred on black.
        CIImage* screenImage = [CIImage imageWithCVPixelBuffer:screen];
        const CGSize screenSize = screenImage.extent.size;
        const CGFloat fit = std::min(width / screenSize.width, height / screenSize.height);
        CGAffineTransform place = CGAffineTransformMakeTranslation(
            (width - screenSize.width * fit) / 2, (height - screenSize.height * fit) / 2);
        place = CGAffineTransformScale(place, fit, fit);
        CIImage* picture = [[screenImage imageByApplyingTransform:place]
            imageByCompositingOverImage:[[CIImage imageWithColor:CIColor.blackColor]
                                            imageByCroppingToRect:L.canvas]];

        // With macOS's Presenter Overlay on, the screen image already shows
        // the user: it is sent as it is.
        if (!presenterOverlay) {
            // 2. Shadow.
            if (L.shadow) picture = [L.shadow imageByCompositingOverImage:picture];

            // 3. Camera, moved from its source rectangle into the overlay, cut to shape.
            CIImage* cameraImage = [[CIImage imageWithCVPixelBuffer:camera]
                imageByCroppingToRect:L.cameraSource];
            const CGFloat s = L.overlay.size.height / L.cameraSource.size.height;
            CGAffineTransform move =
                CGAffineTransformMakeTranslation(L.overlay.origin.x, L.overlay.origin.y);
            move = CGAffineTransformScale(move, s, s);
            move = CGAffineTransformTranslate(move, -L.cameraSource.origin.x, -L.cameraSource.origin.y);
            cameraImage = [cameraImage imageByApplyingTransform:move];
            if (Opts().shape == Shape::Rect) {
                cameraImage = [cameraImage imageByCroppingToRect:L.overlay];
            } else {
                cameraImage = [cameraImage imageByApplyingFilter:@"CIBlendWithMask" withInputParameters:@{
                    kCIInputBackgroundImageKey: [CIImage emptyImage],
                    kCIInputMaskImageKey: L.mask,
                }];
            }
            picture = [cameraImage imageByCompositingOverImage:picture];

            // 4. Hairline.
            if (L.outline) picture = [L.outline imageByCompositingOverImage:picture];
        }

        if (L.pool && CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, L.pool, &out)
                          == kCVReturnSuccess) {
            // Same colour description as the camera frame, so a YCbCr output
            // is encoded the way the camera's own frames are.
            CVBufferPropagateAttachments(camera, out);
            CGColorSpaceRef colorSpace = CopyOutputColorSpace(camera);
            [Context() render:picture toCVPixelBuffer:out bounds:L.canvas colorSpace:colorSpace];
            CGColorSpaceRelease(colorSpace);
            static bool announced = false;
            if (!announced) {
                announced = true;
                std::fprintf(stderr, "[I] screen-camera: first composite %zux%zu\n", width, height);
            }
        }
    }
    CFRelease(screen);
    return out;
}
