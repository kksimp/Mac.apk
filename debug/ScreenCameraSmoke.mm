// debug/ScreenCameraSmoke.mm -- standalone pixel check for runtime/src/screen_camera.mm.
//
// Composes frames from a synthetic screen and a synthetic camera frame (no
// ScreenCaptureKit, no camera, no permissions) and checks the pixels that
// matter: the overlay sits in the chosen corner and shows the camera, the
// screen keeps its colours (a missing colour space once rendered everything
// visibly too dark), the floating shadow darkens the screen next to the
// overlay, a circle leaves its bounding-box corners to the screen, and nothing
// is composed while the screen is switched off.
//
// It #includes the source file so it can feed the screen image directly.
//
// Build and run from the repository root:
//   clang++ -std=c++17 -fobjc-arc -Iruntime/include debug/ScreenCameraSmoke.mm \
//       -o /tmp/screen-camera-smoke -framework Foundation -framework CoreImage \
//       -framework CoreVideo -framework CoreMedia -framework ScreenCaptureKit \
//       -framework CoreGraphics
//   /tmp/screen-camera-smoke
// Exit status 0 and "ScreenCameraSmoke PASS" on success.

#include "../runtime/src/screen_camera.mm"

#include <cstdlib>
#include <string>

namespace {

int gFailures = 0;

void Check(bool ok, const std::string& what) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) gFailures++;
}

struct Rgb { int r, g, b; };

bool Near(Rgb a, Rgb b, int tolerance) {
    return std::abs(a.r - b.r) <= tolerance && std::abs(a.g - b.g) <= tolerance
        && std::abs(a.b - b.b) <= tolerance;
}

std::string Str(Rgb c) {
    return "(" + std::to_string(c.r) + "," + std::to_string(c.g) + "," + std::to_string(c.b) + ")";
}

CGColorSpaceRef Srgb() {
    static CGColorSpaceRef srgb = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    return srgb;
}

CIColor* Color(Rgb c) {
    return [CIColor colorWithRed:c.r / 255.0 green:c.g / 255.0 blue:c.b / 255.0
                      colorSpace:Srgb()];
}

CVPixelBufferRef MakeBuffer(size_t w, size_t h, OSType format, CIImage* content) {
    NSDictionary* attributes = @{(id)kCVPixelBufferIOSurfacePropertiesKey: @{}};
    CVPixelBufferRef buffer = nullptr;
    CVPixelBufferCreate(kCFAllocatorDefault, w, h, format,
                        (__bridge CFDictionaryRef)attributes, &buffer);
    [Context() render:content toCVPixelBuffer:buffer bounds:CGRectMake(0, 0, w, h)
           colorSpace:Srgb()];
    return buffer;
}

/** One pixel of `frame`, in Core Image coordinates (origin bottom-left). */
Rgb PixelAt(CVPixelBufferRef frame, int x, int y) {
    uint8_t px[4] = {0, 0, 0, 0};
    [Context() render:[CIImage imageWithCVPixelBuffer:frame] toBitmap:px rowBytes:4
               bounds:CGRectMake(x, y, 1, 1) format:kCIFormatRGBA8 colorSpace:Srgb()];
    return {px[0], px[1], px[2]};
}

// The synthetic inputs. Screen: 1920x1200 light grey with a blue "window".
// Camera: 640x480 solid warm colour, so any overlay pixel should match it.
const Rgb kScreenGrey = {237, 237, 242};
const Rgb kWindowBlue = {51, 115, 217};
const Rgb kCamera = {200, 140, 90};
const size_t kW = 640, kH = 480;

int RunScenario(const std::string& shape) {
    CIImage* screen = [[CIImage imageWithColor:Color(kScreenGrey)]
        imageByCroppingToRect:CGRectMake(0, 0, 1920, 1200)];
    screen = [[[CIImage imageWithColor:Color(kWindowBlue)]
        imageByCroppingToRect:CGRectMake(200, 300, 1100, 700)] imageByCompositingOverImage:screen];
    CVPixelBufferRef screenBuffer = MakeBuffer(1920, 1200, kCVPixelFormatType_32BGRA, screen);
    CVPixelBufferRef camera = MakeBuffer(kW, kH, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        [[CIImage imageWithColor:Color(kCamera)] imageByCroppingToRect:CGRectMake(0, 0, kW, kH)]);
    OfferScreenFrame(BeginScreenFeed(), (CVPixelBufferRef)CFRetain(screenBuffer));

    Showing().store(false);
    CVPixelBufferRef off = macapk_screencam_compose(camera);
    Check(off == nullptr, shape + ": nothing is composed while the screen is off");
    if (off) CFRelease(off);

    Showing().store(true);
    CVPixelBufferRef out = macapk_screencam_compose(camera);
    Check(out != nullptr, shape + ": a frame is composed while the screen is on");
    if (!out) return 1;
    Check(CVPixelBufferGetWidth(out) == kW && CVPixelBufferGetHeight(out) == kH
              && CVPixelBufferGetPixelFormatType(out) == CVPixelBufferGetPixelFormatType(camera),
          shape + ": same size and pixel format as the camera frame");

    const CGRect o = gLayout.overlay;   // the defaults: bottom-right, 30% of the height
    Check(o.origin.y < kH / 2 && o.origin.x > kW / 2, shape + ": overlay in the bottom-right");
    Check(std::fabs(o.size.height - kH * 0.30) <= 1, shape + ": overlay is 30% of the frame height");

    const Rgb centre = PixelAt(out, (int)CGRectGetMidX(o), (int)CGRectGetMidY(o));
    Check(Near(centre, kCamera, 16), shape + ": overlay shows the camera " + Str(centre));

    // The screen (fitted 1920x1200 -> 640x400, centred) keeps its colours.
    const Rgb blue = PixelAt(out, 150, 250);
    Check(Near(blue, kWindowBlue, 16), shape + ": screen colour kept " + Str(blue));
    const Rgb grey = PixelAt(out, 600, 420);
    Check(Near(grey, kScreenGrey, 16), shape + ": screen background kept " + Str(grey));

    // Floating effect: just left of the overlay the screen is darkened.
    const int besideX = (int)o.origin.x - 4, besideY = (int)CGRectGetMidY(o);
    const Rgb beside = PixelAt(out, besideX, besideY);
    Check(beside.r < kScreenGrey.r - 8, shape + ": shadow beside the overlay " + Str(beside));

    if (shape == "circle") {
        const Rgb corner = PixelAt(out, (int)o.origin.x + 2, (int)CGRectGetMaxY(o) - 3);
        Check(!Near(corner, kCamera, 40), shape + ": bounding-box corner left to the screen "
              + Str(corner));
    }
    CFRelease(out);
    CFRelease(camera);
    CFRelease(screenBuffer);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    // Options are read once per process, so each shape runs in its own process.
    if (argc > 1) {
        RunScenario(argv[1]);
        return gFailures == 0 ? 0 : 1;
    }
    int failed = 0;
    for (const char* shape : {"rounded", "circle", "rect"}) {
        std::string command = std::string("MACAPK_SCREEN_CAMERA=1 MACAPK_SCREEN_CAMERA_SHAPE=")
            + shape + " '" + argv[0] + "' " + shape;
        if (std::system(command.c_str()) != 0) failed++;
    }
    std::printf(failed == 0 ? "ScreenCameraSmoke PASS\n" : "ScreenCameraSmoke FAIL\n");
    return failed == 0 ? 0 : 1;
}
