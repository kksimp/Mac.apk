// cocoa_bridge.mm -- the runtime's Cocoa surface.
//
// Owns NSApplication, the single primary NSWindow, and an MTKView used
// as the OpenGL/Metal drawable. Mouse events route to a Java callback
// via JNI. Each function here is a small ObjC++ wrapper invoked from
// the JNI shims in native_methods.cpp.

#import "macapk/cocoa_bridge.h"

#import <AppKit/AppKit.h>
#import <MetalKit/MetalKit.h>
#import <QuartzCore/QuartzCore.h>
#import <ImageIO/ImageIO.h>   // CGImageSource -- native webp/heic decode
#import <CoreVideo/CoreVideo.h>   // CVPixelBufferRef -- TextureView video-frame extraction
#import <IOSurface/IOSurfaceRef.h> // the Canvas present's IOSurface ring (CocoaPresentFrameFrom)
#import <objc/runtime.h>

#include <algorithm>           // std::fill -- frame-score scratch reset
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <jni.h>
#include <chrono>              // bounded-nextDrawable timed wait
#include <condition_variable>  // bounded-nextDrawable handoff signal
#include <memory>   // std::shared_ptr -- bounded-nextDrawable control block
#include <mutex>    // std::mutex      -- bounded-nextDrawable handoff lock
#include <string>   // std::string     -- ANGLE_DEFAULT_PLATFORM gate compare
#include <vector>
#include <cmath>    // std::round, std::llround -- see the drawableSize
#include <crt_externs.h>  // _NSGetArgv -- CocoaExitProcess relaunches our own command line
#include <spawn.h>        // posix_spawn -- the detached relaunch helper
#include <unistd.h>       // getpid
                    // fractional-height note (the hcr181 render race)

#ifdef MACAPK_HAS_ANGLE
#include "angle_glue.h"
#endif
#include "sv_layer_policy.h"   // MACAPK_SV_LAYER_AT_CREATE decisions (table-tested)
#include "vsync_thread_policy.h"   // MACAPK_VSYNC_THREAD tick ownership (debug/vsync_thread_gate.sh)
#include "host_keymap.h"   // NSEvent -> Android KeyEvent, MACAPK_CMD_AS_CTRL (debug/keyshortcut_gate.sh)

#include <dlfcn.h>
#include <mach-o/dyld.h>   // _NSGetExecutablePath -- locate the default Dock icon next to the runtime binary for CLI runs
#include <limits.h>        // PATH_MAX
#include <sys/stat.h>      // stat() -- self-capture request poll
#include <unistd.h>        // unlink/rename -- self-capture request consume
#include <cstring>         // std::strcmp / std::memcpy
#include <map>             // window policy phase 3: remembered boxes by activity token
#include "macapk/self_capture.h"
#include "macapk/app_process_group.h"   // AOSP killProcessGroup on app death (macapk_terminate_now)
#include <libproc.h>        // proc_pidpath -- the app-owned fork test in macapk_terminate_now
#include <signal.h>         // SIGKILL
#include <time.h>           // clock_gettime(CLOCK_REALTIME) -- the [quit] timeline
#include "macapk/native_methods.h"   // JavaVsyncFire -- the Java Choreographer vsync

// MACAPK_NO_ACTIVATE=1 (HARNESS ONLY, default off; amongus-key-tap-move lane,
// 2026-10-01): show the window BEHIND every other window, WITHOUT activating the
// app or making the window key (pair it with MACAPK_PAUSE_WHEN_HIDDEN=0 so the
// occluded window keeps its full frame rate). A window in front is a window the
// person at the Mac clicks and types into: measured 2026-10-01, the owner played
// a lane's Among Us window for minutes, which both stole their input and
// contaminated the lane's measurement. The two show sites below otherwise call activateIgnoringOtherApps:YES,
// which pulls keyboard focus off whatever the person at this Mac is typing into
// -- measured 2026-10-01: a lane's background Among Us launch took 72 of the
// owner's keystrokes meant for another window. Harness input never needs focus
// (MACAPK_INJECT_FILE posts into the Activity; MACAPK_DEBUG_HOSTKEY_FILE and the
// host-tap verbs post NSEvents straight to our own view), so a harness run should
// set it. It also makes the host-mouse harness (CocoaPostHostMouse) call the hit
// view's mouseDown:/mouseDragged:/mouseUp: directly instead of posting through
// NSApplication, which would treat the tap as an activating click. Changes
// nothing an app can observe; =0/unset is the shipped path.
static bool MacApkHarnessNoActivate() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_NO_ACTIVATE");
        return e && std::strcmp(e, "1") == 0;
    }();
    return on;
}

// [focus] trace (2026-10-05). Owner: Flappy Bird "doesnt always grab focus,
// touch will inop and the traffic light wil be greyed out. but opening a new
// window and comming back to flappy fixes it". Intermittent and not reproduced
// headlessly, so the run log carries the activation state at the moments that
// decide it: shortly after each window show, every activate / resign, every
// window key change, and a click that lands on a non-key window. Capped so a
// long session that switches apps a lot cannot flood the log.
static void MacApkFocusLog(const char* where) {
    static int s_lines = 0;
    if (++s_lines > 60) return;
    NSWindow* key = [NSApp keyWindow];
    NSRunningApplication* front = [[NSWorkspace sharedWorkspace] frontmostApplication];
    std::fprintf(stderr, "[focus] %s: active=%d keyWindow=%s front=%s (pid %d, self %d)\n",
                 where, [NSApp isActive] ? 1 : 0,
                 key ? "yes" : "none",
                 front.localizedName ? [front.localizedName UTF8String] : "?",
                 (int)front.processIdentifier, (int)getpid());
}

// Check the outcome of an activation request a little after it was made:
// under cooperative activation (macOS 14+) a request can be declined, and the
// window is then front-but-not-key with grey traffic lights.
static void MacApkFocusLogAfterShow(const char* where) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.5 * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{ MacApkFocusLog(where); });
}
#if defined(__aarch64__)
#include <mach/mach.h>     // MACAPK_MAIN_STALL_MS: thread_suspend / thread_get_state
#include <ptrauth.h>       // MACAPK_MAIN_STALL_MS: strip PAC from saved return addresses
#include <thread>          // MACAPK_MAIN_STALL_MS: the watchdog thread
#include <arm_neon.h>      // macapk_premultiply_argb's 16-pixel opaque/clear block test
#endif

// MACAPK_SWAPPY_BRIDGE (2026-09-27): is bionic_shim.cpp's Swappy present bridge
// (__macapk_arm64_swappy_present_tick) installed? This MUST give the same answer
// as the shim's macapk_swappy_bridge_enabled(); the rule is kept in both places so
// the call site below agrees with the shim even when only one binary is rebuilt.
//   MACAPK_SWAPPY_BRIDGE=0  -> off;  any other non-empty value -> on.
//   unset -> ON only under MACAPK_CHOREO_NANOTIME_BASE=0. The bridge's timestamp
//   rewrites exist to reconcile Unity's System.nanoTime() enable sample with the
//   REBASED frame clock the Java Choreographer used to hand out; once frame times
//   are in the System.nanoTime() base (the default) the two agree on their own,
//   and a second writer in a different epoch would corrupt them. The app's own
//   per-vsync handler is the only writer of the counter UnityMain waits on.
//   docs/unity-choreographer-vsync-nanotime-contract_2026-09-27.md.
// debug/geh_unity-swappy-frame-pacing-bridge_gate.sh compiles the block between
// the markers next to the shim's copy and checks the two agree on every input.
// ==== BEGIN macapk-swappy-bridge-rule ====
static bool MacapkSwappyBridgeEnabled() {
    static const bool on = [] {
        const char* b = std::getenv("MACAPK_SWAPPY_BRIDGE");
        if (b && b[0]) return std::strcmp(b, "0") != 0;
        const char* n = std::getenv("MACAPK_CHOREO_NANOTIME_BASE");
        return n != nullptr && std::strcmp(n, "0") == 0;
    }();
    return on;
}
// ==== END macapk-swappy-bridge-rule ====

namespace macapk {

namespace {

NSApplication* g_app           = nil;
std::atomic<bool> g_initialized{false};
int g_phone_w = 360;
int g_phone_h = 640;
// Set true once an explicit launcher/CLI size pinned the canvas (via
// CocoaSetPhoneSize). A subsequent runtime Activity.setRequestedOrientation
// then only TRANSPOSES that pinned size to the requested side instead of
// replacing it with the auto-max canvas, so a user-chosen size is preserved.
static bool g_explicit_size = false;

// v1.0.996: the USER rotation -- Mac.apk's mUserRotation. On a device this is
// the rotation the user last put the display in (WindowManager's
// DisplayRotation.mUserRotation); an activity whose requested orientation is
// non-committal gets THIS rotation back, which is how exiting fullscreen
// video returns a phone to portrait. Seeded ONCE from the boot-resolved
// orientation (main.cpp's CLI/manifest resolution -- the state before any
// runtime setRequestedOrientation can have fired, since both setters run
// before the JVM boots), then updated ONLY by a user act (Cmd+R /
// View > Rotate == the user physically turning the device).
// CocoaApplyRequestedOrientation deliberately does NOT touch it: an app
// forcing landscape does not change which way the user holds the phone.
static bool g_user_landscape    = false;
static bool g_user_orient_seeded = false;

// v1.0.1647: the AOSP large-screen window policy (window_policy.h). With a
// session active the WINDOW and the APP are two boxes, as on a tablet: the
// window is the display the app was given at launch (g_win_w/h, points) and
// g_phone_w/h is the app's own bounds inside it. They are equal at launch --
// the letterbox bars are cut away -- and differ only while a runtime
// setRequestedOrientation has the app letterboxed inside its window (the
// display never rotates: AR16 resolveFixedOrientationConfiguration).
// g_win_w == 0 means "no session": the window follows g_phone exactly as it
// always has. Main thread only.
static int g_win_w = 0, g_win_h = 0;
struct WindowPolicySession {
    bool active = false;
    winpolicy::WindowTraits   traits;
    winpolicy::DeviceSettings settings;
    std::string               pref_path;
    bool                      persist_pref = true;   // false: MACAPK_WINDOW_PREF pinned it
    // The live state (window_policy.h LiveWindow): the display the boxes were
    // decided on, the Cmd+R preference, the app's last requested orientation,
    // the decision behind the current app box, and both boxes. Every event goes
    // through the pure winpolicy transitions and only the RESULT is applied
    // here, so the gate drives the same logic.
    winpolicy::LiveWindow     live;
    // CocoaApplyWindowDecision seeded g_user_landscape: a later fall-back to
    // the legacy rule (CocoaClearWindowPolicy) must un-seed it, or the legacy
    // seed-once in CocoaSetPhoneSize* never runs and a later non-committal
    // request rotates the legacy window to the policy's side.
    bool                      seeded_rotation = false;
};
static WindowPolicySession g_wp;

// Window policy phase 3 (every activity gets its own box; "Phase 3 as built" in
// docs/window-size-policy_2026-09-25.md). The box each covered box owner was
// shown in, keyed by the activity token Java gives its record
// (ActivityHost.windowPolicyOnStarting); consumed by that activity's reveal,
// cleared when a new task starts. Main thread only.
static std::map<int, winpolicy::ActivitySnapshot> g_wp_snapshots;
// What a Java thread needs from the live state without a hop to AppKit
// (Activity.getRequestedOrientation's server answer): the display and the Cmd+R
// preference, published by every main-thread write of g_wp.live
// (publish_live_xthread).
static std::mutex g_wp_xt_mu;
static winpolicy::DisplayBudget g_wp_xt_budget;
static winpolicy::UserWindowPref g_wp_xt_pref = winpolicy::UserWindowPref::kDefault;
static void publish_live_xthread() {
    std::lock_guard<std::mutex> lk(g_wp_xt_mu);
    g_wp_xt_budget = g_wp.live.budget;
    g_wp_xt_pref = g_wp.live.pref;
}

// Phase 2a (fullscreen + user resize; "Phase 2a as built" in
// docs/window-size-policy_2026-09-25.md). The window delegate
// (MacApkWindowDelegate) feeds macOS fullscreen and user resizes through the
// pure winpolicy transitions and applies only their results. Main thread only.
//   g_fs_transition      between will- and did-enter/exit fullscreen: no user
//                        resize, no reshape, no display commit (the drawable
//                        animates through sizes nobody asked for).
//   g_fs_pending_reshape ExitFullScreen asked for a reshape; it runs once the
//                        window is windowed again (a fullscreen window is never
//                        given a frame).
//   g_own_frame_change   >0 while OUR setFrame / setFrameOrigin runs, so
//                        windowDidResize can tell a user / window-manager resize
//                        (tiling, Fill, AX) from one of ours.
//   g_fs_screen          the windowed screen budget, captured BEFORE entering
//                        (inside a fullscreen Space visibleFrame is not it); a
//                        Cmd+R made in fullscreen is applied on it at exit.
//   g_fs_saved_live      the live state before an exit, for didFailToExit.
static bool g_fs_transition = false;
static bool g_fs_pending_reshape = false;
static int  g_own_frame_change = 0;
static winpolicy::DisplayBudget g_fs_screen;
static bool g_fs_screen_ok = false;
static winpolicy::LiveWindow g_fs_saved_live;
// The starting-window colour (CocoaSetStartingWindowColor): the container shows
// it where the app is; letterbox BARS are AOSP's letterbox background instead
// (config_letterboxBackgroundType 0 = solid, config_letterboxBackgroundColor =
// @color/system_neutral1_1000 = #000000: config.xml android16 :6277 / :6289,
// colors.xml :344).
static CGColorRef g_starting_bg = nullptr;

// Autoclick schedule, populated by CocoaRunLoop from
// MACAPK_DEBUG_AUTOCLICK and consumed inside drawInMTKView.
struct AutoclickEntry {
    double target_t;   // seconds since first drawInMTKView
    double x;          // -1 = use view center
    double y;
    bool   fired;      // down dispatched
    bool   up_done;    // up dispatched (hold mode)
    double up_t;       // when to dispatch up (hold mode), seconds elapsed
};
static std::vector<AutoclickEntry> g_autoclicks;

// Headless keyboard-injection test harness (MACAPK_DEBUG_AUTOKEY), the
// keyboard analogue of autoclick. Real key injection from a shell is blocked
// by the macOS accessibility gate, so this drives the SAME dispatchKey JNI
// path the keyDown: handler uses, to verify keys reach the guest activity.
struct AutokeyEntry {
    double target_t;   // seconds since first drawInMTKView
    int    keycode;    // Android KEYCODE_*
    int    unicode;    // literal character (0 = none)
    bool   fired;
};
static std::vector<AutokeyEntry> g_autokeys;

}  // namespace

// v1.0.742: generic diagnostic (same family as MACAPK_LAYER_TREE_TRACE /
// MACAPK_DRAWABLE_TRACE / MACAPK_INPUT_TRACE below) for the window/surface
// pixel-size pipeline -- added while root-causing HCR 1.8.1's "renders in
// the bottom-left corner until resized" bug
// (docs/hcr181-viewport-corner-bug_2026-07-23.md; the actual fix landed in
// runtime/java/android/view/SurfaceView.java). Logs g_phone_w/g_phone_h
// reads and writes, the window reshape path, and the MTKView/ANGLE layer
// creation size, each with a timestamp and which thread, so the real event
// order at cold boot (in particular the "activity" Java thread vs the Cocoa
// main thread, which run concurrently per Boot.java) can be read off the
// log instead of guessed. Not gated on any per-APK check -- applies to any
// app. Zero cost unless MACAPK_TRACE_SURFACE_SIZE is set; kept in the tree
// (like its siblings) for the next time a surface-size/orientation bug
// needs the same visibility.
static bool SurfaceSizeTraceOn() {
    static bool on = std::getenv("MACAPK_TRACE_SURFACE_SIZE") != nullptr;
    return on;
}
#define SFTRACE(fmt, ...) do { \
    if (macapk::SurfaceSizeTraceOn()) { \
        std::fprintf(stderr, "[SFTRACE] t=%.4f thread=%s " fmt "\n", \
                     CACurrentMediaTime(), \
                     [NSThread isMainThread] ? "cocoa-main" : "other", \
                     ##__VA_ARGS__); \
    } \
} while (0)

// Pre-created placeholder window. Created at CocoaInit time so the
// user sees a visible window the moment macapk-runtime boots, regardless
// of whether the APK's MainActivity ever reaches setContentView. If
// the activity DOES call setContentView later, it replaces this
// placeholder's content view via the same NSWindow.
static NSWindow* g_placeholder_window = nil;

// Re-center the placeholder window on its screen's visible frame on the NEXT
// main-queue cycle. The window is aspect-ratio-locked, and AppKit shrinks an
// over-tall content frame (the title bar pushes a visible-height-tall content
// rect's FRAME past the visible frame) to fit -- AFTER our synchronous center,
// keeping the left edge, which drifts the window off-center. Reading the frame
// synchronously would see the pre-shrink size, so we defer one cycle and center
// the ACTUAL settled size. Defined below; forward-declared here for CocoaInit.
static void recenter_placeholder_next_cycle();

// Phase 2a: the user-resize locks for the current class (contentAspectRatio for
// the classes that keep their aspect, a free drag clamped by the delegate for
// the rest) and the window delegate. Defined with the policy block below;
// forward-declared here for CocoaInit.
static void apply_window_locks_main(double box_w, double box_h, double cur_w, double cur_h);
static void install_window_delegate(NSWindow* w);
static NSRect render_frame_in_container(NSSize cs, bool* bars);

// Install the runtime window's menu bar (app menu + a View menu whose Rotate item
// shows its Cmd+R shortcut). Forward-declared here for CocoaInit; defined below.
static void install_menu_bar(NSString* appTitle);

// Install a native "back" chevron in the window's title bar (leading edge, after
// the traffic lights -- like Safari / Finder), routed to the same KEYCODE_BACK ->
// onBackPressed path Esc/Cmd+[ use. Forward-declared here for the placeholder-
// window setup; defined below.
static void InstallBackTitlebarAccessory(NSWindow* w);

// The runtime window's style, named once because the fit math below must know
// exactly what chrome THIS style adds around the content rect.
static const NSWindowStyleMask kMacapkWindowStyle = NSWindowStyleMaskTitled
                                                  | NSWindowStyleMaskClosable
                                                  | NSWindowStyleMaskMiniaturizable
                                                  | NSWindowStyleMaskResizable;

// The largest CONTENT size whose whole window FRAME (content + title bar) fits
// `vis`. Every "fit the canvas to the screen" site sized the CONTENT to the
// visible frame, so the title bar pushed the frame 32pt past it (measured on
// Tahoe: frameRectForContentRect adds dh=32, dw=0 for this style; the leading
// titlebar accessory adds nothing). AppKit then constrained the frame and the
// aspect lock shrank the width to match, so the drawable came out 404x898pt
// while g_phone_w/h -- which Boot turns into DisplayMetrics and Configuration --
// still said 418x930. Every app was told a display ~3.4% larger than the one it
// was drawing into, before any resize. Fitting against this budget makes
// g_phone_w/h equal the real content size (Stage 0 of
// docs/window-resize-and-cross-thread-surface_2026-09-23.md).
// +frameRectForContentRect:styleMask: is a class method doing geometry only, so
// it is safe from the JVM thread CocoaSetPhoneSizeMax runs on.
// Bisect gate, shared with com.macapk.loader.DisplayConfig:
// MACAPK_RESIZE_CONFIG=0 turns the whole v1.0.1637 display model off.
static bool display_model_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_RESIZE_CONFIG");
        return !(e && std::strcmp(e, "0") == 0);
    }();
    return on;
}

static NSSize content_budget_for_visible(NSRect vis) {
    if (!display_model_on()) return vis.size;   // the canvas goes back to the visible frame
    const NSRect probe = NSMakeRect(0, 0, 400, 400);
    const NSRect fr = [NSWindow frameRectForContentRect:probe
                                              styleMask:kMacapkWindowStyle];
    const double dw = MAX(0.0, fr.size.width  - probe.size.width);
    const double dh = MAX(0.0, fr.size.height - probe.size.height);
    return NSMakeSize(MAX(64.0, vis.size.width - dw), MAX(64.0, vis.size.height - dh));
}

// v1.0.129 letterbox container.
//
// The window's contentView is this NSView, NOT the MTKView directly.
// It's a black-bg autoresizing rectangle that fills the window's
// content rect; the MTKView (when CocoaSetWindowContentView is called)
// is added as a CENTERED subview at native phone resolution.
//
// Effect on fullscreen: when the window goes fullscreen, the container
// expands to fill the screen, the MTKView stays at native size centered
// inside it, and the uncovered area paints black (letterbox bars on top
// & bottom for tall phone presets on wide screens, pillarbox bars on
// left & right for wide phone presets on tall screens).
//
// Effect on resize: same as fullscreen -- the MTKView keeps its native
// size and centers within the container. If the user shrinks the window
// below the native size, the MTKView extends past the container's
// bounds but is clipped by the container's masksToBounds.
//
// Pre-v1.0.129 the MTKView was set directly as the window's contentView,
// so going fullscreen STRETCHED the bitmap to fill the screen. This
// container restores the "render at chosen resolution, letterbox the
// rest" behavior the user expects.
static NSView* g_letterbox_container = nil;

// Latest-created MTKView. Used by CocoaPresentBitmap to find a layer
// to attach the Canvas overlay to. v2 has one window and one
// MTKView per app, so this single ref is sufficient. Manually
// retained (no ARC in this TU).
static MTKView* g_last_metal_view = nil;
// Canvas overlay layer. Sibling of the angleLayer (ANGLE owns its
// own sublayer for GL); this layer's contents is set from a Java-
// side Bitmap on each unlockCanvasAndPost. Lazily created on the
// first CocoaPresentBitmap call.
static CALayer* g_canvas_overlay_layer = nil;
// v1.0.91: tracks which MTKView the overlay layer is currently
// attached to. When g_last_metal_view changes (Activity recreate,
// HomeScreen->GameActivity transition, AppCompat sub-decor swap),
// we migrate the overlay over to the new view in CocoaPresentBitmap.
// Without this, the overlay stays as a sublayer of an MTKView that's
// been detached from the window, the canvas bitmap updates go to a
// non-visible layer, and the new MTKView shows its bare clearColor
// (opaque black per MTLClearColorMake at view-creation time). The
// "Frozen Bubble starts white then goes black" symptom is the
// observable form of this bug.
static MTKView* g_overlay_attached_view = nil;
// P3 of docs/canvas-present-cost-at-full-screen_2026-09-25.md: the IOSurface
// present (CocoaPresentFrameFrom) may ELIDE a frame whose pixels equal what the
// layer already shows, and an elided frame dispatches nothing -- so it never
// runs the host selection above. Every input to that selection bumps this
// counter AFTER it changes: g_last_metal_view, g_last_gl_metal_view, the
// actively drawing view, and a window content swap (which is what makes a
// Path-A view windowless). A frame is elided only when the counter still reads
// what it read at the last publish, so a host change is always followed by a
// publish, and the publish's main block migrates the overlay as it always has.
// Nothing else reads it; with the IOSurface path off it is an unread counter.
static std::atomic<uint32_t> g_canvas_host_epoch{0};

// GD Meltdown touch investigation (2026-07-23): WindowManager overlays
// (Dialog/AlertDialog/PopupWindow) composite onto g_last_metal_view via
// CocoaPresentBitmap above -- but that view is deliberately never a GL
// (SurfaceView-backed) MTKView (see CocoaCreateMetalSurfaceViewForGl's
// comment), so a real Android Dialog shown by an SDK on top of a
// cocos2d-x/AndEngine/libGDX game (e.g. AppLovin's non-cancelable Terms/
// Privacy consent AlertDialog, com.applovin.impl.z0) was added to
// WindowOverlayStack, WON every touch dispatch (TouchDispatcher checks
// WindowOverlayStack before the Activity/renderer), yet was composited
// NOWHERE -- an invisible, permanent, un-dismissable modal blocking the
// entire game. This is a SEPARATE compositing target (own layer + own
// "attached view" tracking) specifically for the GL surface, so it never
// interferes with the existing Path-A g_last_metal_view/g_canvas_overlay_layer
// mechanism above. See docs/gl-overlay-dialog-compositing_2026-07-23.md.
static MTKView* g_last_gl_metal_view    = nil;
static CALayer* g_gl_overlay_layer      = nil;
static MTKView* g_gl_overlay_attached_view = nil;

// v1.0.162: Unity rendering bridge. Unity (libunity) drives its own GL
// on its "UnityMain" thread: surfaceCreated -> nativeRecreateGfxState ->
// ANativeWindow_fromSurface -> eglCreateWindowSurface. We hand Unity a
// DEDICATED opaque CAMetalLayer (g_unity_layer), a sublayer ON TOP of
// the framework's angleLayer, so Unity's EGL window surface and the
// framework's own ANGLE present don't fight over one layer's drawables.
// It is created on the MAIN thread inside drawInMTKView BEFORE the
// surfaceCreated callback (which blocks the main thread on a Semaphore
// waiting for UnityMain), so it's ready when UnityMain asks for it via
// CocoaGetUnityRenderLayer. Only created when g_unity_app is set (i.e.
// libunity was loaded) so non-Unity engines are completely unaffected.
// ⚠ 2026-09-27: that premise does not hold for the Unity builds on disk (the
// Semaphore.tryAcquire in updateDisplayInternal is on the surface==null DETACH
// branch only), and keying on the engine is what AOSP never does. Default ON
// (MACAPK_SV_LAYER_AT_CREATE) the layer is made for every app at surface
// creation and g_unity_app is no longer consulted; =0 restores the pre-create.
// The slot keeps its historical name: it is the ONE native-EGL window layer.
static CAMetalLayer* g_unity_layer = nil;
static MTKView*      g_unity_host_view = nil;
static std::atomic<bool> g_unity_app{false};

// ANativeWindow_setBuffersGeometry(window, w, h, format) for the native-EGL
// window: the BUFFER size the guest asked for, 0x0 = the window's own size.
// On a device the buffer is w x h and SurfaceFlinger scales it to the
// SurfaceView's frame (native_window_set_buffers_dimensions; the NDK doc: "the
// buffers will be scaled to match that size by the compositor"). Unreal's
// mobile content scale renders a reduced-size buffer this way. Ignoring the
// request left the drawable at window size, so the engine's w x h viewport
// filled only the bottom-left of it (Rocket League Sideswipe, 2026-10-02).
// Here the layer's bounds become the buffer size in points (so its natural
// drawable, which ANGLE / MoltenVK size the swapchain from, is exactly w x h)
// and a scale transform stretches it over the window rect, as the compositor
// does. Main thread only (both are written there).
static int g_nw_buffer_w = 0, g_nw_buffer_h = 0;
// The window's DEFAULT buffer size (the layer's own rect in pixels) while a
// requested buffer size is applied. ANativeWindow_getWidth/Height answer THIS,
// not the requested size: AOSP's NATIVE_WINDOW_WIDTH/HEIGHT query reaches
// BufferQueueProducer::query, which returns mCore->mDefaultWidth/Height (the
// consumer's size), untouched by native_window_set_buffers_user_dimensions.
// Unreal maps a touch as getX / ANativeWindow_getWidth * renderWidth, so
// answering the requested size put every tap at the far edge.
static std::atomic<int> g_nw_default_w{0}, g_nw_default_h{0};
// Lays `ul` over `rect` (host points) at the requested buffer size and returns
// true; returns false, touching nothing, when no buffer size was requested, so
// each caller keeps its own unscaled placement byte-for-byte.
static bool macapk_place_scaled_native_egl_layer(CAMetalLayer* ul, CGRect rect, CGFloat scale) {
    const int bw = g_nw_buffer_w, bh = g_nw_buffer_h;
    if (!ul || bw <= 0 || bh <= 0 || rect.size.width <= 0 || rect.size.height <= 0) {
        if (ul && !CATransform3DIsIdentity(ul.transform)) ul.transform = CATransform3DIdentity;
        g_nw_default_w.store(0, std::memory_order_relaxed);
        g_nw_default_h.store(0, std::memory_order_relaxed);
        return false;
    }
    if (scale <= 0) scale = 2.0;
    const CGRect b = CGRectMake(0, 0, bw / scale, bh / scale);
    const CATransform3D t = CATransform3DMakeScale(rect.size.width / b.size.width,
                                                   rect.size.height / b.size.height, 1.0);
    if (!CATransform3DEqualToTransform(ul.transform, t)) ul.transform = t;
    if (!CGRectEqualToRect(ul.bounds, b)) ul.bounds = b;
    const CGPoint pos = CGPointMake(rect.origin.x + rect.size.width  * ul.anchorPoint.x,
                                    rect.origin.y + rect.size.height * ul.anchorPoint.y);
    if (!CGPointEqualToPoint(ul.position, pos)) ul.position = pos;
    if (ul.contentsScale != scale) ul.contentsScale = scale;
    const CGSize ds = CGSizeMake(bw, bh);
    if (!CGSizeEqualToSize(ul.drawableSize, ds)) ul.drawableSize = ds;
    g_nw_default_w.store((int)std::round(rect.size.width  * scale), std::memory_order_relaxed);
    g_nw_default_h.store((int)std::round(rect.size.height * scale), std::memory_order_relaxed);
    return true;
}
extern "C" void CocoaSetNativeEglBufferGeometry(int w, int h) {
    if (w < 0 || h < 0) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (g_nw_buffer_w == w && g_nw_buffer_h == h) return;
        g_nw_buffer_w = w;
        g_nw_buffer_h = h;
        CAMetalLayer* ul = g_unity_layer;
        MTKView* host = g_unity_host_view;
        std::fprintf(stderr, "[I] cocoa_v2: native-EGL window buffer %dx%d (%s)\n", w, h,
                     (w > 0 && h > 0) ? "scaled to the window, as SurfaceFlinger does"
                                      : "the window's own size");
        if (!ul || !host) return;
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        CGFloat scale = host.window ? host.window.backingScaleFactor : ul.contentsScale;
        if (!macapk_place_scaled_native_egl_layer(ul, host.bounds, scale)) {
            ul.frame = host.bounds;
            ul.drawableSize = CGSizeMake(std::round(host.bounds.size.width  * scale),
                                         std::round(host.bounds.size.height * scale));
        }
        [CATransaction commit];
    });
}

// Called by the arm64 native loader (arm64_native.cpp) the first time a
// libunity image is resolved, so drawInMTKView knows to allocate the
// Unity render layer. Engine-level signal (Unity), not per-APK.
extern "C" void __macapk_note_unity_loaded() {
    g_unity_app.store(true, std::memory_order_relaxed);
}

// v1.0.485 (item 3b): the video decode core lives in media_video.mm (same
// runtime binary). Declared here for the gated on-screen self-test driver
// (MACAPK_VIDEO_SELFTEST) that proves the decode->CVPixelBuffer->MTLTexture->
// CAMetalLayer present pipeline visually with a synthetic clip.
extern "C" void*   macapk_vd_open(const char* path);
extern "C" void*   macapk_vd_next_frame(void* h, int64_t* outPtsUs);
extern "C" void    macapk_vd_release_frame(void* pixbuf);
extern "C" void    macapk_vd_close(void* h);
extern "C" void*   macapk_vd_present_create(void* mtlDevice);
extern "C" int     macapk_vd_present_to_layer(void* presenter, void* caMetalLayer, void* pixbuf);

// video_toolbox.mm's own self-test driver (MACAPK_VIDEO_TOOLBOX_SELFTEST) --
// see that function's header comment. Declared here only because the call
// site below lives in this file; the implementation (including the NAL
// parsing and the macapk_vtb_open/_decode/_poll/_render_frame calls) is
// entirely in video_toolbox.mm.
extern "C" void macapk_vtb_selftest_tick(const char* mimeC, const char* path);

// Returns the dedicated Unity CAMetalLayer (created on the main thread in
// drawInMTKView) and its pixel dimensions. Called from the shim's
// ANativeWindow_fromSurface on the UnityMain thread -- returns the
// already-created layer pointer (no main-thread dispatch, which would
// deadlock against the surfaceCreated Semaphore wait). NULL if not ready.
extern "C" void* CocoaGetUnityRenderLayer(int* outW, int* outH) {
    CAMetalLayer* l = g_unity_layer;
    if (!l) {
        if (outW) *outW = 0;
        if (outH) *outH = 0;
        return nullptr;
    }
    CGSize sz = l.drawableSize;
    // A requested buffer size (ANativeWindow_setBuffersGeometry) is not the
    // window's size; report the default size, as AOSP's query does.
    const int dw = g_nw_default_w.load(std::memory_order_relaxed);
    const int dh = g_nw_default_h.load(std::memory_order_relaxed);
    if (dw > 0 && dh > 0) sz = CGSizeMake(dw, dh);
    if (outW) *outW = (int)sz.width;
    if (outH) *outH = (int)sz.height;
    return (__bridge void*)l;
}

// v1.0.572: GENERIC native-EGL present support. Unity is not the only
// engine that renders to its own EGL window surface: AGDK GameActivity
// games (Minecraft) and custom native engines (Plague Inc) take the same
// ANativeWindow_fromSurface -> eglCreateWindowSurface path. The dedicated
// CAMetalLayer + hidden framework angleLayer treatment therefore keys off
// that ENGINE BEHAVIOR, not off libunity being loaded:
//  - CocoaEnsureNativeEglLayer: like CocoaGetUnityRenderLayer, but when no
//    dedicated layer exists yet it creates one ON DEMAND when called on
//    the main thread. AGDK's ANativeWindow_fromSurface runs on the UI
//    thread inside surfaceCreated (GameActivity.cpp setSurface), which in
//    our model is the Cocoa main thread inside drawInMTKView's surface
//    dispatch -- so direct creation is safe there. Unity calls from
//    UnityMain while the main thread is blocked in surfaceCreated, which
//    is why the Unity path PRE-creates the layer in drawInMTKView
//    (g_unity_app, set at libunity load) -- that path is unchanged.
//  - CocoaNoteNativeEglSurfaceLive: called by the shim when the guest's
//    eglCreateWindowSurface actually succeeds on the dedicated layer.
//    Only then is the framework's angleLayer hidden (drawInMTKView would
//    otherwise keep presenting an EMPTY angleLayer over the engine's
//    content every vsync -> black; the v1.0.174 Unity lesson). Engines
//    that render THROUGH angleLayer (cocos, AndEngine, Canvas apps) never
//    create a guest EGL window surface, so their angleLayer stays
//    visible. Engine-level gate; no per-APK code.
static std::atomic<bool> g_native_egl_surface_live{false};
static MTKView* g_active_render_view = nil;   // MTKView currently drawing (main thread)
// drawInMTKView nesting depth (main thread only). CocoaHostRenderTick never
// starts a tick from inside one: a renderer that quits from onDrawFrame reaches
// macapk_terminate_now INSIDE drawInMTKView, and a nested draw would re-enter
// the ANGLE begin/end frame pair of the tick that is still on the stack.
static int g_host_draw_depth = 0;
static CALayer* g_active_angle_layer = nil;   // that view's framework angleLayer

// ---- DISPLAY-SLEEP RECOVERY -------------------------------------------------
//
// MEASURED BUG (2026-08-24): a window created while the display is ASLEEP never
// renders, and does NOT recover when the display wakes. Controlled A/B on
// flappy, same binary, same app dir, only the display state differing:
//
//   display AWAKE at launch : AngleInit + drawInMTKView + "first frame" +
//                             AngleBeginFrame call #1, 9 EGL lines, game content
//   display ASLEEP at launch: NO AngleInit, NO drawInMTKView, NO first frame,
//                             1 EGL line. logdigest: "NOT REACHED: first frames".
//                             Waking the display 25s in did NOT start it.
//
// MECHANISM. MTKView drives drawInMTKView from an INTERNAL display-link timer
// (SDK header, MTKView.h: `paused` "Controls whether the draw methods should
// continue at preferredFramesPerSecond ... based on an internal timer"). With
// the panel asleep there is no vsync to drive that timer, so the very first
// tick never arrives. Everything downstream is bootstrapped INSIDE that first
// tick -- ANGLE's EGL context included -- so the guest never gets a surface and
// its main thread simply idles on an empty MessageQueue. Nothing re-drives it on
// wake because the timer was never started, and MTKView does not retry.
//
// This is not a hypothetical. It voided SEVEN diagnostic runs on 2026-08-23,
// including a full `git stash` clean-tree test that "confirmed" a regression
// which did not exist, because that test also ran against a dark display.
// debug/applock.sh now holds `caffeinate -d` so measurements cannot hit it, but
// a USER can: close the lid or let the screen sleep, then launch an app.
//
// FIX. Observe NSWorkspaceScreensDidWakeNotification and kick the view: toggle
// `paused` to restart the internal timer, then call `draw` once to force an
// immediate tick (MTKView.h: draw "Manually ask the view to draw new contents.
// This causes the view to call either the drawInMTKView (delegate) or drawRect
// (subclass) method"). Idempotent, so a redundant firing costs one frame.
//
// ⚠ THE TRAP: NSWorkspace notifications are NOT posted to the default center.
// NSWorkspace.h:34 declares `@property (readonly, strong) NSNotificationCenter
// *notificationCenter` -- an observer registered on [NSNotificationCenter
// defaultCenter] compiles, runs, and NEVER FIRES. Register on
// [[NSWorkspace sharedWorkspace] notificationCenter].
//
// MACAPK_NO_WAKE_KICK=1 disables it (A/B lever; there is no reason to in normal use).
//
// ---- A HIDDEN TASK STAYS HIDDEN (wave 5, 2026-09-26) -----------------------
// MEASURED BUG (AnarchRE, ~/Desktop/emu_ab_tablet_2026-09-25/dev.serwin.AnarchRE
// /mac/run.log L1119-1132): the game's SDL main returned, the task emptied, and
// ActivityHost hid the app (CocoaMoveTaskToBack -> [NSApp hide:]). A screen-
// parameters change then reached this kick, which ordered the render window
// front because it was "NOT visible" -- a hidden app's windows are not visible
// BY DESIGN. Ordering a window front un-hides the app (the very next kick line
// logs the window visible=1), the unhide observer calls
// ActivityHost.macapkTaskToFront, and that relaunched the launcher activity of
// a task that had ended: "task brought back empty -> launching ...".
//
// AOSP: a display change never reorders tasks. ActivityTaskManagerService
// .ensureConfigAndVisibilityAfterUpdate (android14-release :5186-5205) takes
// `mRootWindowContainer.getTopDisplayFocusedRootTask()` and ensures the
// configuration and visibility of what is ALREADY on top; a background task
// only receives the new configuration. The same holds for a window the user
// miniaturized (our model of a task sent to the background by the user), so it
// is left alone too. The display-link kick below still runs for every view:
// restarting a stalled timer is the recovery, and a hidden view must be ready
// to draw the moment the user brings it back.
//
// MACAPK_WAKE_KICK_RESPECT_HIDDEN=0 restores the exact pre-change rule
// (order front every render window that is not visible).
//
// debug/w5cocoa_gate.sh extracts the lines between the two marker lines below
// VERBATIM and compiles them standalone (plain C++17, no AppKit, no JNI), so
// the gate tests THIS code and not a copy of it. Keep the block self-contained.
// >>> W5COCOA-GATE-BEGIN
//
// Every default-ON lever in this file reads ON unless its value is exactly "0".
static bool macapk_gate_value_on(const char* v) {
    return !(v && std::strcmp(v, "0") == 0);
}
// Should the wake-kick order a render window front? Only when it is not
// visible for a reason that is NOT "the task is in the background".
static bool macapk_wake_kick_should_order_front(bool window_visible,
                                                bool window_miniaturized,
                                                bool app_hidden,
                                                bool respect_background) {
    if (window_visible) return false;        // already on screen: nothing to surface
    if (!respect_background) return true;    // gate =0: the pre-change rule
    return !app_hidden && !window_miniaturized;
}
// FLAG_SECURE mirror: the NSWindowSharingType raw value a window should carry,
// or -1 to leave its sharingType untouched (gate =0: the pre-change path, in
// which nothing in the runtime ever set sharingType). NSWindowSharingNone = 0,
// NSWindowSharingReadOnly = 1 (the AppKit default); pinned by a static_assert
// at the use site and by the gate's own SDK probe.
static int macapk_secure_sharing_type(bool secure, bool gate_on) {
    if (!gate_on) return -1;
    return secure ? 0 : 1;
}
// <<< W5COCOA-GATE-END

static bool MacapkWakeKickRespectsBackground() {
    static const bool on =
        macapk_gate_value_on(std::getenv("MACAPK_WAKE_KICK_RESPECT_HIDDEN"));
    return on;
}

// ---- FLAG_SECURE -> NSWindow.sharingType (wave 5, 2026-09-26) ----------------
// AOSP WindowManager.LayoutParams.FLAG_SECURE (android14-release
// WindowManager.java:2499-2506): "treat the content of the window as secure,
// preventing it from appearing in screenshots or from being viewed on
// non-secure displays". WindowState.isSecureLocked (:1949-1953) turns the flag
// into SurfaceControl.SECURE on the window's surface at creation
// (WindowStateAnimator.java:289-291) and on every relayout that changes it
// (WindowManagerService.relayoutWindow :2346-2348, "if ((flagChanges &
// FLAG_SECURE) != 0 ...) setSecure(win.isSecureLocked())"), so a runtime
// addFlags/clearFlags takes effect on the live window.
//
// Mac.apk declared the flag and nothing acted on it, so a 2FA app that asks
// not to be captured (Aegis sets it on every activity by default) could be
// screenshotted and screen-shared. The host-native mirror is rung 1 of the
// decision ladder: NSWindow.sharingType (SDK NSWindow.h:520-522: "specifies
// whether the window content can be read from another process. The default
// sharing type is NSWindowSharingReadOnly ... If you set your window sharing
// type to NSWindowSharingNone, so that the content cannot be captured ...").
//
// SCOPE: every window of this process. Mac.apk composes the whole Android
// window stack into one NSWindow, and SurfaceFlinger refuses a whole-display
// capture while ANY visible layer is secure, so "all of this process's windows
// while the stack carries the flag" is the closest mapping; a window created
// later (a dialog-hosting or sign-in window) is caught when it becomes key.
// Java decides WHEN (the resumed top, and a live setFlags on it) and pushes the
// answer through NativeCocoa.setSecureContent(boolean).
//
// ⚠ HONOUR IS PARTIAL, AND THAT IS APPLE'S, NOT OURS. Apple DTS (developer
// forums thread 792152, Jul 2025): "At this time there are no public APIs for
// preventing screen capture", in reply to a report that a sharingType-None
// window is still captured by ScreenCaptureKit on macOS 15.4+. So this is the
// ONLY public knob and it is not a guarantee against every capture API; do not
// claim the contract is met without measuring each API on the host
// (docs/flag-secure-window-sharing_2026-09-26.md). Our own harness is not
// affected: the runtime's self-capture reads its own frame, like Android's
// privileged system capture.
//
// MACAPK_SECURE_WINDOW_SHARING=0 restores the pre-change path exactly: the
// request is recorded and logged, and no window's sharingType is touched.
static std::atomic<int> g_secure_requested{0};   // last value Java pushed (0/1)

static bool MacapkSecureSharingEnabled() {
    static const bool on =
        macapk_gate_value_on(std::getenv("MACAPK_SECURE_WINDOW_SHARING"));
    return on;
}

// Main thread only: the windows WE set to None, retained by the array (MRC:
// NSMutableArray retains its elements), so a clear reverts exactly those and
// never a window that AppKit or another subsystem made None for its own reasons.
static NSMutableArray* g_secure_windows = nil;

// Main thread. Make one window non-shareable and remember it. Returns whether
// its sharingType changed. A window that is ALREADY None is left untracked: it
// is not ours to revert.
static bool macapk_apply_secure_sharing(NSWindow* w) {
    static_assert(NSWindowSharingNone == 0 && NSWindowSharingReadOnly == 1,
                  "macapk_secure_sharing_type hands out raw NSWindowSharingType values");
    const int t = macapk_secure_sharing_type(true, MacapkSecureSharingEnabled());
    if (t < 0 || !w || w.sharingType == (NSWindowSharingType)t) return false;
    w.sharingType = (NSWindowSharingType)t;
    if (!g_secure_windows) g_secure_windows = [[NSMutableArray alloc] init];
    if (![g_secure_windows containsObject:w]) [g_secure_windows addObject:w];
    return true;
}

// Main thread. The exact inverse: every window we made None goes back to the
// AppKit default (ReadOnly), and the list is released. Returns how many changed.
static unsigned long macapk_clear_secure_sharing() {
    const int t = macapk_secure_sharing_type(false, MacapkSecureSharingEnabled());
    if (t < 0 || !g_secure_windows) return 0;
    unsigned long n = 0;
    for (NSWindow* w in g_secure_windows) {
        if (w.sharingType == NSWindowSharingNone) {
            w.sharingType = (NSWindowSharingType)t;
            n++;
        }
    }
    [g_secure_windows removeAllObjects];
    return n;
}

// Main thread. A window created while the flag is set (placeholder, a
// createWindow fallback) carries it from birth, as AOSP creates the surface
// SECURE (WindowStateAnimator :289-291). A no-op until Java asks.
static void macapk_apply_secure_sharing_on_create(NSWindow* w) {
    if (g_secure_requested.load() != 0) macapk_apply_secure_sharing(w);
}

static void macapk_collect_mtkviews(NSView* root, NSMutableArray* out) {
    if (!root) return;
    if ([root isKindOfClass:[MTKView class]]) [out addObject:root];
    for (NSView* sub in [root subviews]) macapk_collect_mtkviews(sub, out);
}

static void macapk_kick_render_views(const char* why) {
    if (const char* e = std::getenv("MACAPK_NO_WAKE_KICK")) {
        if (e[0] && std::strcmp(e, "0") != 0) return;
    }
    // WALK THE LIVE WINDOW TREE. Do NOT use g_active_render_view here: it is
    // assigned INSIDE drawInMTKView, i.e. by the very callback that has not
    // fired, so in exactly the situation this function exists to repair it is
    // still nil and g_last_metal_view points at an earlier, already-detached
    // view (measured: window=0x0 superview=0x0 while a LATER view held the real
    // AndEngine surface). Kicking that stale pointer is a no-op that looks like
    // a fix in the log. The window tree is the only source that is correct at
    // this moment.
    NSMutableArray* views = [NSMutableArray array];
    NSArray* wins = [NSApp windows];
    unsigned long nwin_with_views = 0;
    const bool respect_bg = MacapkWakeKickRespectsBackground();
    const bool app_hidden = [NSApp isHidden];
    for (NSWindow* w in wins) {
        NSMutableArray* mine = [NSMutableArray array];
        macapk_collect_mtkviews([w contentView], mine);
        if ([mine count] == 0) continue;      // not a render window; leave it alone
        nwin_with_views++;
        [views addObjectsFromArray:mine];
        // Only a window that actually HOSTS a render view is ordered front. An
        // unconditional orderFront over every NSApp window would surface helper
        // windows that are deliberately hidden -- measured: this process has 2
        // windows and only 1 holds an MTKView. And never one whose task is in
        // the background (hidden app / miniaturized window): see "A HIDDEN
        // TASK STAYS HIDDEN" above.
        const bool visible = w.isVisible;
        const bool mini = w.isMiniaturized;
        if (macapk_wake_kick_should_order_front(visible, mini, app_hidden, respect_bg)) {
            std::fprintf(stderr, "[I] cocoa: wake-kick: render window=%p was NOT "
                         "visible -- ordering front\n", (void*)w);
            [w orderFront:nil];
        } else if (!visible) {
            std::fprintf(stderr, "[I] cocoa: wake-kick: render window=%p NOT visible "
                         "because %s -- left in the background (a display change does "
                         "not bring a background task forward; "
                         "MACAPK_WAKE_KICK_RESPECT_HIDDEN=0 orders it front)\n",
                         (void*)w, app_hidden ? "the app is hidden"
                                              : "the window is miniaturized");
        }
    }
    std::fprintf(stderr,
        "[I] cocoa: wake-kick(%s) windows=%lu render-windows=%lu mtkviews=%lu\n",
        why, (unsigned long)[wins count], nwin_with_views,
        (unsigned long)[views count]);
    for (MTKView* v in views) {
        const BOOL was = v.paused;
        std::fprintf(stderr,
            "[I] cocoa: wake-kick: view=%p paused=%d window=%p visible=%d "
            "bounds=%.0fx%.0f drawable=%.0fx%.0f delegate=%p\n",
            (void*)v, (int)was, (void*)v.window,
            v.window ? (int)v.window.isVisible : -1,
            v.bounds.size.width, v.bounds.size.height,
            v.drawableSize.width, v.drawableSize.height, (void*)v.delegate);
        v.paused = YES;
        v.paused = was;      // restart the internal display-link timer
        [v draw];            // force one tick now (MTKView.h: calls the delegate)
    }
}

// webview_bridge.mm: the NSView a WKWebView should be parented onto so its
// backing layer is a sibling of the canvas overlay layer. Same fallback order
// as CocoaPresentBitmap's overlay-host selection (v1.0.962): the Path-A
// MTKView when one exists, else the visibly-ticking GL view, else the last GL
// view created. Main-thread only (all three globals are main-thread state).
extern "C" void* CocoaWebViewHostView(void) {
    MTKView* v = g_last_metal_view;
    if (!v) v = g_active_render_view ? g_active_render_view : g_last_gl_metal_view;
    return (__bridge void*)v;
}

// MACAPK_PAUSE_WHEN_HIDDEN (added 2026-07-24, default ON): real Android's
// compositor (SurfaceFlinger) spends ~0 on a window the user cannot see; a
// minimized or fully-occluded Activity's surface gets no composition work at
// all. Mac.apk's MTKView instead free-runs drawInMTKView (below) at ~74-120Hz
// FOREVER regardless of visibility -- a full eglMakeCurrent+eglSwapBuffers+
// JNI-onDrawFrame cycle for a surface nobody can see. See
// docs/efficiency-gpu-angle-metal_2026-07-24.md finding 2(b).
//
// g_window_hidden mirrors AppKit's own visibility signal for the single
// runtime window (g_placeholder_window), updated ONLY from the main thread by
// the NSWindow notification observers installed in CocoaInit (occlusionState
// change + miniaturize/deminiaturize -- see macapk_update_window_hidden_state
// below for the citation). drawInMTKView reads it (also main thread) once per
// tick to decide whether to run its normal body or return early.
//
// DESIGN: throttle, don't stop. We do NOT touch MTKView.paused /
// preferredFramesPerSecond / enableSetNeedsDisplay, and we do NOT deliver an
// Android onPause to the guest -- this is a HOST-side present throttle only,
// the guest keeps running as if the window were visible. Every engine
// currently in this tree couples its own tick to THIS callback:
//   - GLSurfaceView-driven engines (cocos2d-x / AndEngine / libGDX):
//     macapkDrawFrame() below (called unconditionally on a due tick) directly
//     invokes Renderer.onDrawFrame (see runtime/java/android/opengl/
//     GLSurfaceView.java macapkDrawFrame), so for cocos2d-x / libGDX it IS the
//     game tick (AOSP RENDERMODE_CONTINUOUSLY contract). AndEngine DOES have a
//     separate simulation thread (its own UpdateThread), which hands off to
//     onDrawFrame 1:1 through its EngineLock -- throttling this callback slows
//     that handshake too, it never breaks it.
//   - Native-EGL engines (Unity/libunity, AGDK GameActivity / RenderDragon)
//     block their OWN render thread on the choreoFire/swappyTick pump a few
//     hundred lines below: __macapk_arm64_swappy_present_tick's own comment
//     in bionic_shim.cpp is explicit that without a present-bridge tick
//     "UnityMain blocks forever in the pacing wait". Silencing that pump
//     completely parks the engine thread, not just the present.
// So a hard stop of this callback would freeze guest game logic, which is
// exactly the semantics this change must NOT introduce. Throttling to a low
// rate (default 2 Hz, MACAPK_HIDDEN_DRIVE_HZ) instead keeps every engine's
// frame-pacing state alive through the hidden period at low cost --
// bionic_shim.cpp's swappy_present_tick already has a >200ms-gap "assume one
// 60fps frame elapsed" fallback (its boot-stall safety net), so a throttled
// cadence cannot produce a catch-up burst on resume. Because nothing is ever
// fully stopped, "resume" needs no special-case code: the next tick after
// visibility flips back on just falls through to the unmodified body.
static std::atomic<bool> g_window_hidden{false};
// Wall-clock (CACurrentMediaTime) of the last throttled tick let through
// while hidden. Reset to 0 on every visible->hidden transition so the FIRST
// tick of a NEW hidden spell always fires immediately rather than waiting
// out a stale period left over from an earlier hidden stretch. Touched only
// from the main thread (drawInMTKView and the notification handler below are
// both main-thread; NSWindow notifications are inherently main-thread-only).
static double g_hidden_throttle_last = 0.0;

static bool MacapkPauseWhenHiddenEnabled() {
    static bool on = []{
        const char* e = std::getenv("MACAPK_PAUSE_WHEN_HIDDEN");
        return !(e && std::strcmp(e, "0") == 0);  // default ON; "0" = escape hatch / A-B lever
    }();
    return on;
}
static double MacapkHiddenThrottleHz() {
    static double hz = []{
        const char* e = std::getenv("MACAPK_HIDDEN_DRIVE_HZ");
        double v = e ? std::atof(e) : 2.0;        // mission default: "a low rate like 1-2 fps"
        return (v >= 0.1 && v <= 30.0) ? v : 2.0;
    }();
    return hz;
}

// Re-evaluate AppKit's own visibility signal for g_placeholder_window and
// update g_window_hidden on an actual transition (idempotent -- occlusionState
// can re-post with the same value, and miniaturize/deminiaturize +
// occlusionState can both fire for one user action, so only the FIRST
// observer to see a flip logs/acts). isVisible catches miniaturized AND
// explicitly ordered-out windows; occlusionState additionally catches "on
// screen but fully covered by other windows" -- neither alone covers both
// cases, so visibility requires isVisible AND occlusionState-visible.
//
// Citation (Apple, local SDK header -- developer.apple.com is JS-rendered/
// empty to a fetcher, so read directly per this project's convention):
// AppKit.framework/Headers/NSWindow.h:
//   NSWindowOcclusionStateVisible: "If set, at least part of the window is
//   visible. If not set, the entire window is occluded."
//   NSWindowDidChangeOcclusionStateNotification: "Upon receiving this
//   notification, you can query the NSWindow for its current occlusion
//   state... You can use this notification to increase responsiveness and
//   save power, by halting any expensive calculations that the user can not
//   see."
static void macapk_update_window_hidden_state(const char* reason) {
    if (!g_placeholder_window) return;
    NSWindow* w = g_placeholder_window;
    bool visible = w.isVisible
                && ((w.occlusionState & NSWindowOcclusionStateVisible) != 0);
    bool wasHidden = g_window_hidden.exchange(!visible);
    if (visible && wasHidden) {
        g_hidden_throttle_last = 0.0;   // next hidden spell starts unthrottled
        std::fprintf(stderr,
            "[present] window visible again (%s) -> full-rate present resumed\n",
            reason);
        std::fflush(stderr);
    } else if (!visible && !wasHidden) {
        std::fprintf(stderr,
            "[present] window hidden (%s; isVisible=%d occlusionState=%lu) -> "
            "throttling present to %.1f Hz%s\n",
            reason, (int)w.isVisible, (unsigned long)w.occlusionState,
            MacapkHiddenThrottleHz(),
            MacapkPauseWhenHiddenEnabled() ? ""
                : " (MACAPK_PAUSE_WHEN_HIDDEN=0: tracked only, not applied)");
        std::fflush(stderr);
    }
}

// v1.0.611 BOUNDED-nextDrawable (structural 54% fix; supersedes the v1.0.597
// MACAPK_DRAWABLE_TRACE diagnostic, which still lives inside as the trace path).
//
// THE BUG (see docs/mcpe-54pct-async-recycle-crux_2026-07-11.md + the -07-10 doc):
// on the native-EGL Vulkan path (MCPE = RenderDragon -> bgfx -> ANGLE-Vulkan ->
// MoltenVK) the guest presents to its own CAMetalLayer (g_unity_layer). MoltenVK
// acquires the CAMetalDrawable LAZILY inside vkQueueSubmit at render-pass-encode
// (MVKPresentableSwapchainImage::getCAMetalDrawable -> [layer nextDrawable]) and
// holds RenderDragon's per-frame compute-once-future std::mutex across that entire
// call. The CAMetalLayer pool is Apple-hard-capped at 3 drawables
// (MVKSwapchain.mm: maximumDrawableCount = imgCnt = 3). Under the Ore-UI/V8 spike
// the display-gated pool momentarily reaches 0-free, so nextDrawable parks in
// -[CAMetalLayer nextDrawable] -> _dispatch_semaphore_wait_slow for up to the
// CAMetalLayer default timeout (~1s), retried getImageCount()=3 times by MoltenVK
// (MVKImage.mm: the `for (attemptIdx < attemptCnt)` loop) => ~3s per exhausted
// present. The game thread awaits that future every tick (it is coupled to the
// present via the future), so it is throttled to ~3s/frame and openAsset crawls =>
// the ~40% "stuck at 54%" coin-flip. This is a LATENCY-coupling stall, not a hard
// deadlock: the OS recycle is fully async and alive (WindowServer retires displayed
// drawables and replenishes the pool on its own), it is just slower than the guest's
// ~3s hog window. So the lever is nextDrawable LATENCY, i.e. how long the producer
// holds the game-loop-critical mutex -- NOT any recycle policy knob (which is why
// displaySyncEnabled / present-mode / glFinish were all ~40% no-ops).
//
// THE FIX: cap the time the present thread can spend in -[CAMetalLayer nextDrawable]
// on g_unity_layer to a couple of display intervals. We run the real nextDrawable on
// a dedicated SERIAL queue (so it is never called concurrently on the layer) and wait
// on it from the present thread with a hard timeout (default 33ms = 2 vsync). If a
// drawable is available it comes back within one refresh (~16ms) and NOTHING changes
// (transparent in steady state). If the pool is momentarily empty we return nil to
// MoltenVK after 33ms instead of ~1s: MoltenVK's getCAMetalDrawable then reports
// VK_ERROR_OUT_OF_POOL_MEMORY, the submit/present unwinds, RenderDragon drops the
// frame and RELEASES the compute-once mutex, and the game thread advances. A dropped
// frame during the boot spike is fine; the async OS recycle refills the pool within a
// couple of frames and the next acquire succeeds. The abandoned worker (still blocked
// in the real nextDrawable) releases whatever drawable it eventually gets straight
// back to the pool, so we never leak a pool slot.
//
// WHY allowsNextDrawableTimeout MUST stay YES: if it were NO the real nextDrawable
// would block FOREVER on an empty pool, so our serial worker would wedge permanently
// (and every subsequent acquire would queue behind it). MoltenVK never touches this
// property (MVKSwapchain.mm does not reference it) so it stays at the CAMetalLayer
// default YES; we re-assert YES defensively so the worker always drains (~1s) even
// when we've already returned nil to the present thread. YES is also what lets
// getCAMetalDrawable's retry loop terminate (nil -> OUT_OF_POOL_MEMORY) instead of
// hanging.
//
// GATE / CROSSY SAFETY: the BOUNDED ACQUIRE POLICY applies only on the native-EGL Vulkan
// path (ANGLE_DEFAULT_PLATFORM==vulkan) -- a rendering-path property, not a per-APK branch.
// Crossy Road / Unity render through ANGLE-METAL (no MoltenVK); their process never
// sets ANGLE_DEFAULT_PLATFORM=vulkan, so g_nd_bounded stays false and their
// nextDrawable (which needs the full vsync-pacing block for Swappy) is byte-identical.
// ⚠ STALE, corrected 2026-09-27: Crossy is on ANGLE-VULKAN (bounded=1 in its log);
// see the correction in macapk_eval_drawable_policy.
// ⚠ CORRECTION (v1.0.837): the SWIZZLE ITSELF is installed far more widely than this
// comment used to claim ("the method is never swizzled" on the Metal path). Since
// v1.0.835 it installs whenever layer capture is enabled, which is the DEFAULT -- see
// the install gate below. A `sample` of Crossy Road at v1.0.836 shows
// macapk_bounded_nextDrawable / macapk_nextDrawable_inner in ANGLE-Metal's acquire stack.
// Behaviour is still a pure passthrough there (line ~510), so this was a documentation
// defect rather than a behaviour one -- but do not read the old claim as "Metal apps
// never enter this function". Ref: docs/crossy-blank-blue-regression_2026-07-29.md.
// Even within a Vulkan process the bounded path fires ONLY for self==g_unity_layer;
// any other CAMetalLayer passes straight through. MACAPK_DRAWABLE_TRACE still installs
// the swizzle (with per-acquire logging) for headless diagnosis on any backend, and
// MACAPK_NO_BOUNDED_DRAWABLE / MACAPK_DRAWABLE_TIMEOUT_MS are A/B kill/tune switches.
// v1.0.835 self-capture hooks. Defined next to the rest of the self-capture
// machinery further down (see macapk/self_capture.h for the design); declared
// here because the three call sites -- the nextDrawable swizzle below,
// CocoaDecouplePresentTick, and CocoaPresentBitmap -- all sit above it.
static void MacapkLayerCaptureOnDrawable(CAMetalLayer* layer, id<CAMetalDrawable> d);
static void MacapkSnapshotCanvas(const uint32_t* argb, int w, int h);
// Defined with the IOSurface ring (CocoaPresentFrameFrom's state), far below;
// MacapkSnapshotCanvas calls it under g_snap_mu.
namespace { void MacapkCanvasPoolForeignWriteLocked(); }
static void MacapkSnapshotDc(const uint8_t* rgba, int w, int h);
static void MacapkScoreSnapshotAsync(bool dc);
static bool MacapkLayerCaptureEnabled();

static IMP g_orig_nextDrawable = NULL;
static std::atomic<int> g_nd_enter{0}, g_nd_return{0};
static bool g_nd_bounded = false;               // bounded acquire active (Vulkan path)
static bool g_nd_trace   = false;               // MACAPK_DRAWABLE_TRACE per-acquire log
static uint64_t g_nd_timeout_ns = 33ull * NSEC_PER_MSEC;   // hard cap (default 2 vsync)
static dispatch_queue_t g_nd_queue = nil;       // serial: real nextDrawable never overlaps

// Cross-thread handoff for one bounded acquire. shared_ptr keeps it alive for whichever
// of {present thread, serial worker} outlives the other. The drawable is carried as a
// +1 CFTypeRef so ownership crosses the thread boundary cleanly under ARC.
namespace {
struct NDHandoff {
    std::mutex mu;
    std::condition_variable cv;
    bool       done      = false;    // worker returned from the real nextDrawable
    CFTypeRef  drawable  = nullptr;   // +1 retained when non-null (CFBridgingRetain)
    bool       abandoned = false;    // present thread timed out; worker must give it back
};
}

// v1.0.835: the swizzle now has TWO jobs, so the acquire policy stays in
// macapk_nextDrawable_inner (byte-identical to what it was) and this thin
// wrapper adds the capture tap. Every vended drawable goes through here, on
// every backend and every layer, which is what makes the layer capture source
// universal -- see MacapkLayerCaptureOnDrawable.
static id macapk_nextDrawable_inner(id self, SEL _cmd);

// ---- the GUEST's own drawable acquire is a DEQUEUE: it blocks, it never drops ----
// (MACAPK_GUEST_DEQUEUE_FAITHFUL, default ON; =0 restores the bounded acquire in
// macapk_nextDrawable_inner byte for byte.)
//
// Since MACAPK_VK_WINDOW_SURFACE (2026-09-27) the guest's own MoltenVK submit calls
// -[CAMetalLayer nextDrawable] on g_unity_layer, and it went through the 33 ms bounded
// acquire that was written for the RUNTIME presenter of the retired offscreen decouple.
// A nil from that bound is not "a dropped frame" to MoltenVK, it is a leaked swapchain
// image (MoltenVK 1c96d3d9 MVKImage.mm, the same on upstream main):
//   getCAMetalDrawable() :1590-1613 -- nil after getImageCount() attempts ->
//     setConfigurationResult(VK_ERROR_OUT_OF_POOL_MEMORY), sticky on the image;
//   presentCAMetalDrawable() :1622-1663 -- the scheduled handler still runs
//     addPresentedHandler(nil, ...): beginPresentation() retains the image and bumps
//     _unpresentedImageCount, but `[nil addPresentedHandler:]` never fires, so
//     endPresentation() -> signalAndUntrack(signaler) never runs and the semaphore/fence
//     a later vkAcquireNextImageKHR of that image waits on is never signalled.
// Measured 2026-10-01 (docs/mcpe-guest-drawable-drop-freeze_2026-10-01.md): Minecraft
// stops presenting for good after such a nil while MINECRAFT MAIN keeps ticking -- the
// owner's "freeze after a few minutes" -- and 2 of the owner's 3 hand sessions logged
// the MoltenVK line at the moment play stopped.
//
// What a device does: ANGLE calls vkAcquireNextImageKHR with timeout UINT64_MAX, and
// libvulkan maps that to an UNBOUNDED dequeue (android14-release vulkan/libvulkan/
// swapchain.cpp :1426 `NATIVE_WINDOW_SET_DEQUEUE_TIMEOUT, nsecs_t{-1}` at create,
// :1880-1881 `timeout > max ? -1 : timeout` per acquire), and BufferQueueProducer then
// waits for the consumer with no timeout (libs/gui/BufferQueueProducer.cpp :86
// `mDequeueTimeout(-1)`, :374-381 `if (mDequeueTimeout >= 0) {... TIMED_OUT} else
// { mCore->mDequeueCondition.wait(lock); }`); only an ABANDONED queue fails it
// (:289-291 NO_INIT, which libvulkan turns into VK_ERROR_SURFACE_LOST_KHR). The producer
// blocks until the compositor hands a buffer back and never drops a frame.
//
// Here that is: re-ask CoreAnimation (each real nextDrawable waits up to its own ~1 s
// timeout) until it vends a drawable, and give up only when the app is quitting. The
// guest's render thread then blocks at the dequeue the way a device's does. The main
// thread never takes this path (the runtime presenter of MACAPK_VK_WINDOW_SURFACE=0
// keeps the bounded acquire), and neither does a thread once the quit has begun.
//
// ABANDON IS QUIT-ONLY TODAY (review 2026-10-01). Android's NO_INIT comes from the
// CONSUMER disconnecting, i.e. the SurfaceView's surface being destroyed; the faithful
// analogue here would be surfaceDestroyed / the ANativeWindow release of the owning
// SurfaceView, which is not wired to this dequeue (docs/mcpe-guest-drawable-drop-freeze_
// 2026-10-01.md, risk 3). The native-EGL layer slot is created once and never replaced
// or set to nil (macapk_create_native_egl_layer_on_main returns the existing
// g_unity_layer), so the `self == g_unity_layer` test below is a harmless guard that
// does not fire in practice, not a second abandon path.
//
// The loop is a pure function over its three effects so debug/guestdequeue_gate.sh can
// compile it VERBATIM from between the marker lines, with mocks.
// >>> GUESTDEQUEUE-GATE-BEGIN
// MACAPK_GUEST_DEQUEUE_FAITHFUL is ON unless it is exactly "0".
static inline bool macapk_guest_dequeue_faithful_enabled(const char* e) {
    return !(e && e[0] == '0' && e[1] == '\0');
}
// acquire(): one real nextDrawable (nil only after CoreAnimation's own timeout).
// keep_waiting(): false once the queue is "abandoned" (quit; plus a guard for a layer
// that is no longer the guest's). note(n): n > 1 = the drawable came on the n-th attempt; n < 0 = the
// wait was abandoned after -n attempts.
template <class Acquire, class KeepWaiting, class Note>
static inline auto macapk_dequeue_until_drawable(Acquire acquire, KeepWaiting keep_waiting,
                                                 Note note) -> decltype(acquire()) {
    for (int attempts = 1;; ++attempts) {
        auto d = acquire();
        if (d) {
            if (attempts > 1) note(attempts);
            return d;
        }
        if (!keep_waiting()) {
            note(-attempts);
            return d;
        }
    }
}
// <<< GUESTDEQUEUE-GATE-END
static bool macapk_guest_dequeue_faithful_on() {
    static const bool on = macapk_guest_dequeue_faithful_enabled(
        std::getenv("MACAPK_GUEST_DEQUEUE_FAITHFUL"));
    return on;
}
static bool macapk_quit_under_way();   // defined with g_quit_t0 (macapk_terminate_now)

static id macapk_bounded_nextDrawable(id self, SEL _cmd) {
    id d = macapk_nextDrawable_inner(self, _cmd);
    if (d) MacapkLayerCaptureOnDrawable((CAMetalLayer*)self, (id<CAMetalDrawable>)d);
    return d;
}

// Wall-clock of the guest's last drawable acquisition on ITS OWN layer, i.e. the
// last time the guest actually produced a frame there. 0 = never. Read by
// macapk_reassert_native_egl_layer to tell "the guest presents its own layer"
// from "a guest EGL surface exists but nothing is coming out of it".
static std::atomic<double> g_guest_layer_last_present{0.0};

// ---- where the native-EGL window layer composites (MACAPK_SV_GUEST_BELOW) ----
//
// AOSP composites a SurfaceView's surface BELOW its window unless the app asked
// setZOrderOnTop(true) (android14 core/java/android/view/SurfaceView.java:
// mSubLayer = APPLICATION_MEDIA_SUBLAYER :200, isAboveParent() = mSubLayer >= 0
// :1528-1530, setZOrderedOnTop -> APPLICATION_PANEL_SUBLAYER :687-703), and the
// view punches a transparent hole in its window where it sits (draw()/
// dispatchDraw() :512-533 -> clearSurfaceViewPort :579-597). A PANEL sublayer is
// above its OWN window only; a Dialog or PopupWindow is another window and stays
// on top of it.
//
// g_unity_layer -- the CAMetalLayer ANativeWindow_fromSurface hands a native
// producer (Unity, AGDK, SDL, Plague Inc's GLView) -- used to be pinned at 2.0,
// above the canvas overlay (0.5, the app's View tree) AND the WindowManager
// overlay (1.0, Dialogs). For an engine that owns the whole screen that looked
// harmless; for an app whose UI is Canvas-drawn over its GL surface it hid the UI
// (Plague Inc, docs/surfaceview-z-below-default_2026-09-29.md). The first attempt
// (the retired MACAPK_SURFACEVIEW_Z_BELOW=1 experiment) moved the layer below
// WITHOUT the hole, so the window's own opaque background covered the producer
// (Fruit Ninja black on screen). Now the framework says which SurfaceView owns the
// window (SurfaceView.macapkNativeWindowLayerZ, pulled every tick by
// MacapkSyncGuestLayerZ) and that view punches its hole; the layer goes below
// only while such an owner is known.
//
// The mapping (and the =0 predicate) are pure functions, compiled VERBATIM by
// debug/svguestz_gate.sh from between the two marker lines; keep them self-contained.
// >>> SVGUESTZ-GATE-BEGIN
// MACAPK_SV_GUEST_BELOW is ON unless it is exactly "0" (the Java side's
// !"0".equals(System.getenv(...)) and bionic_shim.cpp's strcmp say the same).
static inline bool macapk_sv_guest_below_enabled(const char* e) {
    return !(e && e[0] == '0' && e[1] == '\0');
}
// answer: 0 = no owning SurfaceView ever known (or MACAPK_SV_GUEST_BELOW=0),
//         1 = below its window, 2 = setZOrderOnTop(true).
// overlayZ: the zPosition of the LIVE canvas overlay (the app's View tree) when it
// shares the guest layer's superlayer -- 0.5 on the GL host, 1.0 when the overlay
// sits on a Path-A MTKView. The slots are derived from it, like the per-surface
// layers' (MacapkApplySurfaceLayerGeometry: overlay.zPosition + (onTop ? 0.1 : -0.25)).
static inline double macapk_guest_layer_z_for(int answer, double overlayZ) {
    switch (answer) {
        case 1:  return overlayZ - 0.3;   // under the canvas overlay, above the framework angleLayer (0)
        case 2:  return overlayZ + 0.1;   // over the canvas overlay, under the WindowManager overlay (1.0) on the GL host
        default: return 2.0;              // the legacy slot above every framework layer
    }
}
// <<< SVGUESTZ-GATE-END
static bool macapk_sv_guest_below_on() {
    static const bool on = macapk_sv_guest_below_enabled(std::getenv("MACAPK_SV_GUEST_BELOW"));
    return on;
}
// The last answer the tick pulled, so a (re)configured layer starts in the right slot.
static std::atomic<int> g_guest_layer_z_answer{0};
/** zPosition for `answer`, ordered against the LIVE canvas overlay when that shares
 *  `host` (the guest layer's superlayer); 0.5 (the GL host's overlay slot) otherwise.
 *  Main thread only (reads CALayer state). EVERY store to the guest layer's
 *  zPosition goes through this or macapk_guest_layer_z_now (debug/svguestz_gate.sh
 *  source check). */
static double macapk_guest_layer_z(int answer, CALayer* host) {
    double overlayZ = 0.5;
    CALayer* ov = g_canvas_overlay_layer;
    if (ov && host && ov.superlayer == host) overlayZ = (double) ov.zPosition;
    return macapk_guest_layer_z_for(answer, overlayZ);
}
static double macapk_guest_layer_z_now(CALayer* host) {
    return macapk_guest_layer_z(g_guest_layer_z_answer.load(std::memory_order_relaxed), host);
}

static id macapk_nextDrawable_inner(id self, SEL _cmd) {
    const bool ours = ((__bridge void*)self == (__bridge void*)g_unity_layer);
    if (ours) g_guest_layer_last_present.store(CACurrentMediaTime(),
                                               std::memory_order_relaxed);
    // Only the guest's dedicated layer on the Vulkan path is bounded; everything
    // else (other layers, or the trace-only install on a non-Vulkan backend) keeps
    // the original blocking semantics so pacing-dependent paths are unchanged.
    if (!ours || !g_nd_bounded) {
        // v1.0.837: this trace used to require `ours`, i.e. self == g_unity_layer -- which
        // is nil on the ANGLE-Metal path, so MACAPK_DRAWABLE_TRACE was structurally DEAD
        // on exactly the apps whose render thread is starving on this call (Crossy Road:
        // UnityGfxDeviceW 98.2% inside -[CAMetalLayer nextDrawable], measured three times
        // across a week and never instrumentable). Trace ANY layer now, keyed by identity,
        // and report the AGGREGATE (count + mean/max blocked time + nil returns) rather
        // than per-acquire lines: at ~120 acquires/sec a per-acquire fprintf is itself a
        // perturbation. The question this answers is open-question 2 of
        // docs/crossy-camtallayer-drawable-starvation_2026-07-23.md -- "is something
        // obtaining a drawable and never presenting it" -- which needs acquires-vs-presents,
        // not a log of individual calls.
        if (g_nd_trace) {
            struct NDStat {
                void*    layer = nullptr;
                uint64_t n = 0, nil_n = 0, wait_ns = 0, worst_ns = 0, report_at = 200;
            };
            static std::mutex   s_mu;
            static NDStat       s_stat[3];      // guest layer, framework angleLayer, spare
            const uint64_t t0 = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
            id d = ((id (*)(id, SEL))g_orig_nextDrawable)(self, _cmd);
            const uint64_t dt = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) - t0;
            bool report = false; NDStat snap; const char* who = "?";
            {
                std::lock_guard<std::mutex> lk(s_mu);
                NDStat* st = nullptr;
                for (auto& s : s_stat) if (s.layer == (void*)self) { st = &s; break; }
                if (!st) for (auto& s : s_stat) if (!s.layer) { s.layer = (void*)self; st = &s; break; }
                if (st) {
                    ++st->n; if (!d) ++st->nil_n;
                    st->wait_ns += dt;
                    if (dt > st->worst_ns) st->worst_ns = dt;
                    if (st->n >= st->report_at) { report = true; snap = *st; st->report_at = st->n + 200; }
                }
            }
            if (report) {
                if (g_unity_layer && snap.layer == (void*)g_unity_layer)            who = "guest(unity-layer)";
                else if (g_active_angle_layer && snap.layer == (void*)g_active_angle_layer) who = "framework(angleLayer)";
                else                                                                who = "guest(other)";
                std::fprintf(stderr,
                    "[drawable] %s %p: acquires=%llu nil=%llu mean_block=%.2fms worst=%.2fms\n",
                    who, snap.layer, (unsigned long long)snap.n, (unsigned long long)snap.nil_n,
                    (double)snap.wait_ns / snap.n / 1e6, (double)snap.worst_ns / 1e6);
                std::fflush(stderr);
            }
            return d;
        }
        return ((id (*)(id, SEL))g_orig_nextDrawable)(self, _cmd);
    }

    // The guest's own producer thread: an unbounded dequeue (see the block comment at
    // macapk_dequeue_until_drawable). Everything below is the bounded acquire, which
    // MACAPK_GUEST_DEQUEUE_FAITHFUL=0, the main thread and TEARDOWN still take: once the
    // quit has begun, MoltenVK's getImageCount()(=3) retries must each cost the old 33 ms
    // bound, not up to 3 x CoreAnimation's 1 s timeout on an empty pool (review 2026-10-01).
    if (macapk_guest_dequeue_faithful_on() && ![NSThread isMainThread]
            && !macapk_quit_under_way()) {
        static std::atomic<bool> s_announced{false};
        if (!s_announced.exchange(true)) {
            std::fprintf(stderr, "[drawable] guest dequeue: faithful (blocks until the "
                                 "compositor returns a drawable, never drops a frame; "
                                 "MACAPK_GUEST_DEQUEUE_FAITHFUL=0 restores the %llums "
                                 "bounded acquire)\n",
                         (unsigned long long)(g_nd_timeout_ns / NSEC_PER_MSEC));
            std::fflush(stderr);
        }
        IMP orig = g_orig_nextDrawable;
        // How often the dequeue actually WAITED past the old bound is the number of
        // frames MACAPK_GUEST_DEQUEUE_FAITHFUL=0 would have handed MoltenVK as nil (each
        // one a potential permanent freeze), so it is counted and reported (first 20,
        // then every 100th). Two clock reads per acquire; no lock.
        const uint64_t dq_t0 = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
        struct DqWaitReport {
            uint64_t t0;
            ~DqWaitReport() {
                const uint64_t dt = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) - t0;
                if (dt <= g_nd_timeout_ns) return;
                static std::atomic<int> s_long{0};
                const int k = ++s_long;
                if (k <= 20 || k % 100 == 0) {
                    std::fprintf(stderr, "[drawable] guest dequeue: waited %.1f ms for a "
                                 "drawable (past the %llu ms bound MACAPK_GUEST_DEQUEUE_FAITHFUL=0 "
                                 "drops at; #%d)\n", (double)dt / 1e6,
                                 (unsigned long long)(g_nd_timeout_ns / NSEC_PER_MSEC), k);
                    std::fflush(stderr);
                }
            }
        } dq_report{dq_t0};
        return macapk_dequeue_until_drawable(
            [&]() -> id {
                // A nil normally comes after CoreAnimation's own ~1 s wait. If CA answers
                // nil AT ONCE (a layer state with nothing to vend), back off 4 ms rather
                // than spin: a dequeue sleeps on its condition, it never busy-waits.
                const uint64_t a0 = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
                id d = ((id (*)(id, SEL))orig)(self, _cmd);
                if (!d && clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) - a0 < 2ull * NSEC_PER_MSEC)
                    usleep(4000);
                return d;
            },
            [&]() -> bool {
                return !macapk_quit_under_way()
                    && (__bridge void*)self == (__bridge void*)g_unity_layer;
            },
            [&](int n) {
                static std::atomic<int> s_waits{0};
                const int k = ++s_waits;
                if (k <= 20 || k % 100 == 0) {
                    std::fprintf(stderr, "[drawable] guest dequeue: %s after %d "
                                 "CoreAnimation timeout(s) (stall #%d)\n",
                                 n > 0 ? "drawable" : "ABANDONED (quit)",
                                 n > 0 ? n - 1 : -n, k);
                    std::fflush(stderr);
                }
            });
    }

    // MoltenVK loops getCAMetalDrawable getImageCount()(=3) times on nil, back-to-back
    // on this same present thread. Short-circuit that synchronous retry burst: after a
    // nil, the next few microsecond-spaced calls return nil instantly so a single
    // exhausted present costs ~one timeout, not three. The window (a few ms) is far
    // shorter than a display interval, so it never suppresses a real next-frame acquire.
    static thread_local uint64_t t_last_nil_ns = 0;
    const uint64_t now_ns = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
    if (t_last_nil_ns && now_ns - t_last_nil_ns < 4ull * NSEC_PER_MSEC) {
        if (g_nd_trace) { std::fprintf(stderr, "[drawable] retry-short-circuit -> nil\n"); std::fflush(stderr); }
        return nil;
    }

    auto h = std::make_shared<NDHandoff>();
    IMP orig = g_orig_nextDrawable;
    // Run the REAL (blocking) nextDrawable off the present thread, serialized so the
    // layer's nextDrawable is never called concurrently. The result crosses back as a
    // +1 CFTypeRef (CFBridgingRetain) so it survives this worker's autorelease pool.
    dispatch_async(g_nd_queue, ^{
        id d = ((id (*)(id, SEL))orig)(self, _cmd);   // may block up to ~1s (timeout=YES)
        CFTypeRef held = d ? CFBridgingRetain(d) : nullptr;   // +1 to carry across threads
        bool giveback = false;
        {
            std::lock_guard<std::mutex> lk(h->mu);
            if (h->abandoned) giveback = true;       // present thread already timed out
            else h->drawable = held;                 // transfer the +1 to it
            h->done = true;
        }
        h->cv.notify_one();
        if (giveback && held) CFRelease(held);       // return the abandoned drawable to the pool
    });

    // Both sides operate under h->mu, so the deposit-vs-timeout decision is atomic: if
    // we time out we set abandoned=true UNDER the lock before releasing it, so the
    // worker (which must take the same lock to deposit) always sees abandoned and gives
    // the drawable back -- no lost pool slot, no leak.
    id out = nil;
    bool via_timeout = false;   // true = we gave up waiting; false = worker finished
    {
        std::unique_lock<std::mutex> lk(h->mu);
        bool got = h->cv.wait_for(lk, std::chrono::nanoseconds((int64_t)g_nd_timeout_ns),
                                  [&]{ return h->done; });
        if (got) {
            if (h->drawable) { out = CFBridgingRelease(h->drawable); h->drawable = nullptr; }
        } else {
            via_timeout = true;
            h->abandoned = true;   // worker will CFRelease its drawable when it finishes
        }
    }
    if (!out) {
        // Remember the moment so MoltenVK's immediate getImageCount() retries on this
        // same thread short-circuit to nil instead of each eating another timeout.
        t_last_nil_ns = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW);
        if (g_nd_trace) {
            std::fprintf(stderr, "[drawable] bounded nextDrawable -> nil (%s)\n",
                         via_timeout ? "timeout" : "CA-nil");
            std::fflush(stderr);
        }
    } else {
        t_last_nil_ns = 0;
    }
    return out;
}

// The acquire POLICY, read from the environment. Split out of the install below
// because the environment can be incomplete when CocoaInit runs: since v1.0.1634 a
// window-first launch decides the ANGLE backend (the GLES 3.1 scan) on the unpack
// worker, AFTER CocoaInit, and main.cpp re-runs this through
// CocoaRefreshDrawablePolicy once it has set ANGLE_DEFAULT_PLATFORM.
static void macapk_eval_drawable_policy() {
    g_nd_trace = std::getenv("MACAPK_DRAWABLE_TRACE") != nullptr;
    // Bounded acquire on the native-EGL Vulkan path only. Gate on the rendering-path
    // property ANGLE_DEFAULT_PLATFORM==vulkan (main.cpp sets it when the app uses GLES
    // 3.1), NEVER on package name. Crossy/Unity (ANGLE-Metal) never sets it -> no
    // swizzle -> byte-identical pacing.
    // ⚠ STALE, corrected 2026-09-27: Crossy DOES run on the Vulkan path -- its
    // installed-stub log (v1.0.1601) shows `swizzle installed (bounded=1 ...)` and
    // `graphicsDeviceName: ANGLE (Apple, Vulkan ...)` -- so the bound applies to it
    // too. Since MACAPK_VK_WINDOW_SURFACE (2026-09-27) the guest's own MoltenVK
    // submit is the caller on that path; it retires with a dequeue-faithful
    // ("block at swap") acquire, see docs/offscreen-decouple-present-retired_2026-09-27.md.
    const char* plat = std::getenv("ANGLE_DEFAULT_PLATFORM");
    const bool vulkan = plat && std::string(plat) == "vulkan";
    g_nd_bounded = vulkan && (std::getenv("MACAPK_NO_BOUNDED_DRAWABLE") == nullptr);
    if (const char* ms = std::getenv("MACAPK_DRAWABLE_TIMEOUT_MS")) {
        int v = std::atoi(ms);
        if (v >= 1 && v <= 1000) g_nd_timeout_ns = (uint64_t)v * NSEC_PER_MSEC;
    }
}

static bool g_nd_swizzled = false;

static void macapk_apply_drawable_policy() {
    // v1.0.835: the swizzle is ALSO the universal capture tap, so it installs
    // whenever self-capture is on (the default) even off the Vulkan path. The
    // acquire POLICY is unchanged: g_nd_bounded stays Vulkan-only, so
    // Crossy/Unity-on-Metal still get the full blocking nextDrawable their Swappy
    // pacing needs. Added steady-state cost per acquire is one relaxed atomic
    // load, plus a throttled stat() only while a capture request is outstanding.
    if (!g_nd_bounded && !g_nd_trace && !MacapkLayerCaptureEnabled()) return;
    if (g_nd_bounded && !g_nd_queue) {
        g_nd_queue = dispatch_queue_create("com.macapk.nextdrawable",
                                           DISPATCH_QUEUE_SERIAL);
        // Defensive: our serial worker must never block forever, so keep the
        // CAMetalLayer's nextDrawable timeout enabled (it is the default; assert it).
        if (g_unity_layer) g_unity_layer.allowsNextDrawableTimeout = YES;
    }
    if (g_nd_swizzled) return;
    Method m = class_getInstanceMethod([CAMetalLayer class], @selector(nextDrawable));
    if (m) {
        g_orig_nextDrawable = method_getImplementation(m);
        method_setImplementation(m, (IMP)macapk_bounded_nextDrawable);
        g_nd_swizzled = true;
        std::fprintf(stderr,
            "[drawable] nextDrawable swizzle installed (bounded=%d timeout=%llums trace=%d)\n",
            (int)g_nd_bounded,
            (unsigned long long)(g_nd_timeout_ns / NSEC_PER_MSEC), (int)g_nd_trace);
        std::fflush(stderr);
    }
}

static void macapk_install_drawable_swizzle() {
    static bool done = false;
    if (done) return;
    done = true;
    macapk_eval_drawable_policy();
    macapk_apply_drawable_policy();
}

// Re-read the policy after main.cpp has decided the ANGLE backend. MAIN THREAD,
// and only before the JVM starts: nothing can be inside a nextDrawable then (the
// placeholder window has no CAMetalLayer; every MTKView and the native-EGL layer
// are created later, from Java or on the guest's behalf), so the plain globals
// the acquire path reads are not being read while this writes them.
void CocoaRefreshDrawablePolicy() {
    const bool was = g_nd_bounded;
    macapk_eval_drawable_policy();
    macapk_apply_drawable_policy();
    if (was != g_nd_bounded)
        std::fprintf(stderr, "[drawable] acquire policy re-read after the backend decision: "
                             "bounded=%d\n", (int)g_nd_bounded);
}

// The ONE native-EGL layer configuration (MACAPK_SV_LAYER_AT_CREATE, 2026-09-27).
// These statements used to live inline in macapk_create_native_egl_layer_on_main;
// they moved here VERBATIM and in the same order, so the on-demand path (=0) runs
// exactly what it ran before, and the layer made at SurfaceView surface creation
// (default ON) gets the same framebufferOnly=NO / opaque / z-order as every other
// native-EGL layer instead of the v1.0.162 Unity copy's framebufferOnly=YES.
// debug/geh_unity-nativeegl-layer-pacing_gate.sh row S3 holds the statement list
// equal to the pre-change inline body. MAIN THREAD ONLY (reads view geometry).
static void macapk_configure_native_egl_layer(CAMetalLayer* ul, MTKView* view,
                                              CGFloat scale) {
    ul.device          = view.device;
    ul.pixelFormat     = MTLPixelFormatBGRA8Unorm;
    // framebufferOnly=NO: a deferred engine like RenderDragon/bgfx resolves its
    // final composite into the drawable and may sample/blit it (and ANGLE's own
    // WindowSurfaceMtl marks its drawable texture non-framebuffer-only for the
    // same reason). A framebufferOnly=YES drawable forbids any non-render-target
    // usage of the texture, so such a resolve/blit silently drops -> a black
    // drawable. Real Android window surfaces are not framebuffer-only. Match that.
    ul.framebufferOnly = NO;
    ul.opaque          = YES;
    // v1.0.598 (MACAPK_NO_DISPLAY_SYNC): on ANGLE-Metal, the vsync wait for a free
    // CAMetalLayer drawable happens INSIDE -[CAMetalLayer nextDrawable], which a GL
    // engine reaches from GL_Clear/render-pass-start -- i.e. WHILE the engine holds
    // its own GL-context mutex. A multi-threaded engine (Minecraft's RenderDragon:
    // an android_main game thread + a separate render thread both serialized on that
    // GL mutex) then starves: the render thread parks ~16ms/frame in nextDrawable
    // holding the mutex, so android_main sits 95% blocked on pthread_mutex_lock and
    // the boot worklist never advances (measured: 6348/6676 samples). On Android the
    // vsync wait is at eglSwapBuffers (a yield point), NOT inside GL_Clear, so the
    // lock is held briefly. displaySyncEnabled=NO frees drawables without waiting for
    // the display refresh, so nextDrawable returns promptly and the GL mutex is held
    // only for the (fast) GL work -- unblocking the starved main thread. This is the
    // opt-in escape hatch for the ANGLE-METAL path (MACAPK_NO_DISPLAY_SYNC); the
    // DEFAULT is the CAMetalLayer default (YES = vsync-paced), which Crossy/Unity-on-
    // Metal needs for its Swappy pacing.
    //
    // v1.0.610: on the ANGLE-VULKAN path (MCPE), setting displaySyncEnabled HERE is a
    // NO-OP: MoltenVK OVERWRITES it at Vulkan-swapchain creation (which happens AFTER
    // this) via `mtlLayer.displaySyncEnabledMVK = (presentMode != IMMEDIATE)` and
    // ANGLE-Vulkan uses FIFO, so it lands on YES regardless. displaySyncEnabled is NOT
    // the lever for the 54% stall (proven ~40% no-op in every placement). The Vulkan-
    // path fix instead BOUNDS how long -[CAMetalLayer nextDrawable] can block on this
    // layer (macapk_bounded_nextDrawable), so an exhausted 3-drawable pool cannot pin
    // the present thread (and the game-loop mutex it holds) for ~3s -- see that function
    // + docs/mcpe-54pct-async-recycle-crux_2026-07-11.md.
    if (std::getenv("MACAPK_NO_DISPLAY_SYNC")) ul.displaySyncEnabled = NO;
    ul.contentsScale   = scale;
    ul.frame           = view.bounds;
    ul.drawableSize    = CGSizeMake(std::round(view.bounds.size.width  * scale),
                                    std::round(view.bounds.size.height * scale));
    ul.actions = @{ @"contents": [NSNull null],
                    @"bounds":   [NSNull null],
                    @"position": [NSNull null] };
    // Below its window when the framework knows the owning SurfaceView, else the
    // legacy slot (see macapk_guest_layer_z_for; MacapkSyncGuestLayerZ follows it).
    ul.zPosition = macapk_guest_layer_z_now(view.layer);
}

// The actual layer-creation body. MAIN THREAD ONLY -- it mutates the CALayer
// tree (addSublayer) and reads view.window.backingScaleFactor, neither of which
// is safe off-main. Callers go through CocoaEnsureNativeEglLayer below, which
// owns the thread policy.
static void* macapk_create_native_egl_layer_on_main(int* outW, int* outH) {
    if (outW) *outW = 0;
    if (outH) *outH = 0;
    if (g_unity_layer) return CocoaGetUnityRenderLayer(outW, outH);
    if (!g_active_render_view) return nullptr;
    MTKView* view = g_active_render_view;
    CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
    CAMetalLayer* ul = [CAMetalLayer layer];
    macapk_configure_native_egl_layer(ul, view, scale);
    [view.layer addSublayer:ul];
    g_unity_layer = ul;          // single dedicated native-EGL layer slot
    macapk_install_drawable_swizzle();   // MACAPK_DRAWABLE_TRACE diagnostic (inert unless set)
    g_unity_host_view = view;
    std::fprintf(stderr,
        "[I] cocoa_v2: native-EGL render layer created on demand %dx%d "
        "(opaque, zPos=%g; angleLayer hides when the guest surface goes live)\n",
        (int)ul.drawableSize.width, (int)ul.drawableSize.height, (double)ul.zPosition);
    return CocoaGetUnityRenderLayer(outW, outH);
}

// ---- MACAPK_SV_LAYER_AT_CREATE (default ON, 2026-09-27) ---------------------
//
// AOSP: SurfaceView.updateSurface builds the layer + BufferQueue
// (createBlastSurfaceControls, copySurface) BEFORE surfaceCreated, and
// ANativeWindow_fromSurface is a pure lookup that works on any thread. So the
// window exists before the app is told about it, whatever engine produces into
// it. We now do the same: drawInMTKView creates the native-EGL layer right
// before a render view's FIRST macapkSurfaceChanged, for every app, hidden and
// UNPUBLISHED (g_unity_layer stays nil, so its ~47 readers keep today's
// meaning: "a guest asked for a native window"), and CocoaEnsureNativeEglLayer
// only PUBLISHES it -- from any thread, with no main-thread wait and no CALayer
// mutation off-main (the unhide is dispatched to main). That retires, default
// ON: the v1.0.162 pre-create keyed on the "libunity" soname (g_unity_app),
// whose premise -- Unity's surfaceCreated blocks main on a Semaphore -- does
// not hold for the Unity builds on disk (the tryAcquire sits on the DETACH
// branch only); the on-main on-demand creation; and the 2 s off-main hand-off.
// MACAPK_SV_LAYER_AT_CREATE=0 restores all three byte for byte. The decisions
// live in sv_layer_policy.h (table-tested); the evidence is in
// docs/unity-nativeegl-layer-precreate-retired_2026-09-27.md.
static bool MacapkSvLayerAtCreateOn() {
    static const bool on =
        sv_layer_at_create_env_on(std::getenv("MACAPK_SV_LAYER_AT_CREATE"));
    return on;
}
// g_sv_pending_layer / g_sv_pending_host are WRITTEN only on the main thread and
// only under g_sv_layer_mu; the publisher (any thread) reads them under it, and
// the per-tick sync (main) reads them lock-free, which is safe because main is
// the only writer. This file is MRC (not ARC): both are explicitly RETAINED, so
// a re-parent (removeFromSuperlayer drops the superlayer's reference) can never
// free the layer, and a host view that leaves the window cannot dangle. The
// publisher never clears them; "published" is g_unity_layer != nil, as it
// always was (g_unity_layer itself stays an unretained alias, as before).
static std::mutex    g_sv_layer_mu;
static CAMetalLayer* g_sv_pending_layer = nil;   // retained, never released
static MTKView*      g_sv_pending_host  = nil;   // retained

// MAIN THREAD, caller holds g_sv_layer_mu.
static void macapk_sv_set_host_locked(MTKView* view) {
    if (g_sv_pending_host == view) return;
    [view retain];
    [g_sv_pending_host release];
    g_sv_pending_host = view;
}

// Put `ul` on `view` with the view's current geometry. MAIN THREAD, caller holds
// g_sv_layer_mu. Only writes a property that differs, inside a no-actions
// transaction, so a steady-state tick costs a few reads.
static bool macapk_sv_layer_place_locked(CAMetalLayer* ul, MTKView* view) {
    CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
    CGRect  fr    = view.bounds;
    CGSize  ds    = CGSizeMake(std::round(fr.size.width  * scale),
                               std::round(fr.size.height * scale));
    const bool reparent = (ul.superlayer != view.layer);
    const bool regeo    = !CGRectEqualToRect(ul.frame, fr)
                          || ul.contentsScale != scale
                          || !CGSizeEqualToSize(ul.drawableSize, ds);
    if (reparent || regeo) {
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        if (reparent) {
            [ul removeFromSuperlayer];
            [view.layer addSublayer:ul];
        }
        ul.frame         = fr;
        ul.contentsScale = scale;
        ul.drawableSize  = ds;
        [CATransaction commit];
    }
    macapk_sv_set_host_locked(view);
    return reparent;
}

// drawInMTKView, immediately before this view's FIRST macapkSurfaceChanged: the
// AOSP point where a SurfaceView's Surface becomes valid. MAIN THREAD.
static void MacapkSvLayerAtSurfaceCreate(MTKView* view) {
    if (!view) return;
    std::lock_guard<std::mutex> lk(g_sv_layer_mu);
    switch (sv_at_create_action(MacapkSvLayerAtCreateOn(), g_unity_layer != nil,
                                g_sv_pending_layer != nil)) {
    case SvAtCreate::kNone:
        return;
    case SvAtCreate::kCreate: {
        CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
        CAMetalLayer* ul = [CAMetalLayer layer];
        macapk_configure_native_egl_layer(ul, view, scale);
        ul.hidden = YES;   // unpublished: must never cover the framework layers
        [view.layer addSublayer:ul];
        g_sv_pending_layer = [ul retain];   // MRC: our own reference (see above)
        macapk_sv_set_host_locked(view);
        std::fprintf(stderr,
            "[I] cocoa_v2: native-EGL window layer created at surface creation %dx%d "
            "(hidden, published by ANativeWindow_fromSurface; "
            "MACAPK_SV_LAYER_AT_CREATE)\n",
            (int)ul.drawableSize.width, (int)ul.drawableSize.height);
        return;
    }
    case SvAtCreate::kPlace:
        if (macapk_sv_layer_place_locked(g_sv_pending_layer, view))
            std::fprintf(stderr,
                "[I] cocoa_v2: native-EGL window layer moved to the new render view "
                "(still unpublished)\n");
        return;
    }
}

// drawInMTKView, every tick: keep an UNPUBLISHED layer's geometry current on its
// own host view (a published one is resized by the existing g_unity_layer code),
// so a later ANativeWindow_fromSurface reads the size it would read on Android.
// ADOPT: if the host view has left its window and `view` is the view drawing
// now, move the layer here BEFORE any publish. That is the view
// macapk_sv_layer_show_on_main would move it to, so an off-main fromSurface
// (which reads drawableSize before the async show) always reads the size the
// layer is then shown at, and show_on_main's re-place stays a no-op.
static void MacapkSvLayerTick(MTKView* view) {
    if (!g_sv_pending_layer || g_unity_layer) return;
    MTKView* host = g_sv_pending_host;   // main is the only writer: lock-free read
    const bool adopt = host != view && (!host || !host.window) &&
                       view == g_active_render_view;
    if (host != view && !adopt) return;
    std::lock_guard<std::mutex> lk(g_sv_layer_mu);
    if (g_unity_layer) return;
    if (macapk_sv_layer_place_locked(g_sv_pending_layer, view) && adopt)
        std::fprintf(stderr,
            "[I] cocoa_v2: native-EGL window layer moved to the new render view "
            "(its host left the window; still unpublished)\n");
}

// MAIN THREAD: make a just-published layer visible. If its host view lost its
// window in the meantime, move it to the view that is drawing now (the view the
// pre-change on-demand path would have attached to).
static void macapk_sv_layer_show_on_main(CAMetalLayer* ul) {
    if (g_unity_layer != ul) return;
    std::lock_guard<std::mutex> lk(g_sv_layer_mu);
    MTKView* host = g_sv_pending_host;
    if ((!host || !host.window) && g_active_render_view) {
        macapk_sv_layer_place_locked(ul, g_active_render_view);
        g_unity_host_view = g_active_render_view;
    }
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    ul.hidden = NO;
    [CATransaction commit];
}

// ANativeWindow_fromSurface's backend, gate ON. ANY THREAD. Returns nullptr only
// when no surface was ever created (the caller then logs and falls back).
static void* macapk_sv_layer_lookup(int* outW, int* outH) {
    // Decide and publish under the lock; touch no CALayer while holding it
    // (the size read below happens after it is released).
    CAMetalLayer* ul = nil;
    SvFromSurface act;
    {
        std::lock_guard<std::mutex> lk(g_sv_layer_mu);
        act = sv_fromsurface_action(true, g_unity_layer != nil,
                                    g_sv_pending_layer != nil);
        if (act == SvFromSurface::kPublishPending) {
            ul = g_sv_pending_layer;
            g_unity_host_view = g_sv_pending_host;
            g_unity_layer     = ul;   // nil -> ul, once, under the lock
        }
    }
    if (act == SvFromSurface::kReturnPublished)
        return CocoaGetUnityRenderLayer(outW, outH);
    if (act != SvFromSurface::kPublishPending) {
        if (outW) *outW = 0;
        if (outH) *outH = 0;
        return nullptr;
    }
    const bool on_main = [NSThread isMainThread];
    if (on_main) {
        macapk_sv_layer_show_on_main(ul);
    } else {
        dispatch_async(dispatch_get_main_queue(), ^{
            macapk_sv_layer_show_on_main(ul);
        });
    }
    std::fprintf(stderr,
        "[I] cocoa_v2: native-EGL window layer published by ANativeWindow_fromSurface "
        "%dx%d (%s thread, no wait; MACAPK_SV_LAYER_AT_CREATE)\n",
        (int)ul.drawableSize.width, (int)ul.drawableSize.height,
        on_main ? "main" : "off-main");
    return CocoaGetUnityRenderLayer(outW, outH);
}

// OFF-MAIN native-EGL layer creation (2026-07-30, the SDL-family blocker).
//
// WHY: SDL runs the app's main() on its own "SDLThread", so
// SDL_CreateWindow -> Android_CreateWindow -> Android_JNI_GetNativeWindow ->
// ANativeWindow_fromSurface all execute OFF the Cocoa main thread. The old
// guard here bailed on any off-main caller, ANativeWindow_fromSurface then
// handed back the magic-less 4 KB dummy window, and the EGL bridge rejected it
// -- measured verbatim as
//   EnsureNativeEglLayer: cannot create off-main (main=0 view=0xa8f670000)
//   ANativeWindow_fromSurface: layer not ready -> dummy
//   eglCreateWindowSurface: no CAMetalLayer (magic=not-our-window) -> EGL_NO_SURFACE
//   SDL_CreateWindow() failed: unable to create an EGL window surface
//   [FATAL] setting video mode failed
// i.e. every SDL app died before its first frame. Note `view` was already
// non-nil: the main-thread requirement was the ONLY thing missing.
//
// Real Android has no such restriction -- the Surface is created by
// SurfaceFlinger before the app is told about it, so ANativeWindow_fromSurface
// is valid from any thread. AGDK/GameActivity merely happens to call it on the
// UI thread, which is why a main-thread-only implementation sufficed until an
// SDL app arrived. This is an engine-behavior fix (any engine that renders from
// its own thread), not a per-APK branch.
//
// HOW, WITHOUT DEADLOCKING: dispatch_async + a BOUNDED wait, never
// dispatch_sync. The Unity bringup deliberately blocks the main thread inside
// surfaceCreated on a Semaphore while UnityMain calls fromSurface, so a
// dispatch_sync from a guest render thread could deadlock outright. With async
// + timeout the worst case degrades to EXACTLY the old behavior (nullptr ->
// dummy -> caller falls back), so nothing that works today can regress. Unity
// never reaches this path anyway: g_unity_app pre-creates the layer in
// drawInMTKView, which short-circuits at the top of this function.
//
// MACAPK_NO_OFFMAIN_EGL_LAYER=1 restores the old off-main bail (A/B lever).
extern "C" void* CocoaEnsureNativeEglLayer(int* outW, int* outH) {
    // MACAPK_SV_LAYER_AT_CREATE (default ON): a pure lookup/publish of the layer
    // made at surface creation. Everything below this block is the pre-change
    // body, reached only under =0 or when no surface was ever created.
    if (MacapkSvLayerAtCreateOn()) {
        if (void* l = macapk_sv_layer_lookup(outW, outH)) return l;
        static std::atomic<bool> warned{false};
        if (!warned.exchange(true))
            std::fprintf(stderr,
                "[W] cocoa_v2: ANativeWindow_fromSurface before any render view "
                "created its surface -> pre-change on-demand path "
                "(MACAPK_SV_LAYER_AT_CREATE fallback)\n");
    }
    if (g_unity_layer) return CocoaGetUnityRenderLayer(outW, outH);
    if (outW) *outW = 0;
    if (outH) *outH = 0;
    if (!g_active_render_view) {
        std::fprintf(stderr,
            "[I] cocoa_v2: EnsureNativeEglLayer: no render view yet "
            "-- caller falls back\n");
        return nullptr;
    }
    if ([NSThread isMainThread])
        return macapk_create_native_egl_layer_on_main(outW, outH);

    static const bool offmain_disabled =
        std::getenv("MACAPK_NO_OFFMAIN_EGL_LAYER") != nullptr;
    if (offmain_disabled) {
        std::fprintf(stderr,
            "[I] cocoa_v2: EnsureNativeEglLayer: off-main creation disabled "
            "(MACAPK_NO_OFFMAIN_EGL_LAYER) -- caller falls back\n");
        return nullptr;
    }

    // Bounded hand-off to the main thread. 2s is far longer than a CALayer
    // insert needs; it only has to outlast one drawInMTKView tick, and it
    // exists solely so a wedged main thread degrades instead of hanging the
    // guest's render thread forever.
    struct Handoff {
        std::mutex mu;
        std::condition_variable cv;
        bool done = false;
    };
    auto h = std::make_shared<Handoff>();
    dispatch_async(dispatch_get_main_queue(), ^{
        int w = 0, hh = 0;
        macapk_create_native_egl_layer_on_main(&w, &hh);
        {
            std::lock_guard<std::mutex> lk(h->mu);
            h->done = true;
        }
        h->cv.notify_all();
    });
    {
        std::unique_lock<std::mutex> lk(h->mu);
        h->cv.wait_for(lk, std::chrono::milliseconds(2000),
                       [&h] { return h->done; });
    }
    if (g_unity_layer) {
        std::fprintf(stderr,
            "[I] cocoa_v2: EnsureNativeEglLayer: created via main-thread "
            "hand-off (caller was off-main, e.g. SDL's own thread)\n");
        return CocoaGetUnityRenderLayer(outW, outH);
    }
    std::fprintf(stderr,
        "[I] cocoa_v2: EnsureNativeEglLayer: main-thread hand-off timed out "
        "-- caller falls back\n");
    return nullptr;
}

// v1.0.602: native-EGL layer VISIBILITY guard + diagnostic (MACAPK_LAYER_REASSERT /
// MACAPK_LAYER_TREE_TRACE). The AGDK on-demand path (CocoaEnsureNativeEglLayer + the
// sub-decor NativeCocoa.setContentView rewire + repeated eglCreateWindowSurface on the
// same layer) can leave the dedicated g_unity_layer with CORRECT content in its
// drawable (proven: FBO0 readback mean 63.8, and ANGLE-Metal presents FBO0 = the
// CAMetalLayer drawable directly, no intermediate blit) yet NOT composited onto the
// on-screen window: the layer's visibility/geometry/parent state is never re-asserted
// on the main thread after ANGLE reconfigures the CALayer from the guest render thread,
// and a content-view rewire can orphan it onto a detached view. Unlike the pre-create
// Unity path (Crossy, proven visible), nothing re-commits it. This main-thread periodic
// guard re-asserts hidden=NO / opacity=1 / frame=host.bounds and re-parents the layer
// onto the CURRENT render view if orphaned, inside an explicit CATransaction (guest-
// thread CALayer writes are otherwise never committed). Generic (any native-EGL engine)
// and INERT once the state is correct, so the pre-create Unity/cocos path is unaffected.
static dispatch_source_t g_layer_guard_timer = nil;
extern "C" void CocoaDumpNativeEglLayerTree(void) {
    CAMetalLayer* ul = g_unity_layer;
    if (!ul) { std::fprintf(stderr, "[layer-tree] g_unity_layer=nil\n"); return; }
    MTKView* hv = g_unity_host_view;
    MTKView* av = g_active_render_view;
    CALayer* sup = ul.superlayer;
    std::fprintf(stderr,
        "[layer-tree] ul=%p frame=(%.0f,%.0f,%.0fx%.0f) hidden=%d opacity=%.2f opaque=%d "
        "zPos=%.1f drawable=%.0fx%.0f scale=%.1f sup=%p\n",
        (void*)ul, ul.frame.origin.x, ul.frame.origin.y, ul.frame.size.width,
        ul.frame.size.height, (int)ul.hidden, (double)ul.opacity, (int)ul.opaque,
        (double)ul.zPosition, ul.drawableSize.width, ul.drawableSize.height,
        (double)ul.contentsScale, (void*)sup);
    int depth = 0;
    for (CALayer* c = ul; c && depth < 12; c = c.superlayer, ++depth) {
        CGColorRef bg = c.backgroundColor;
        std::fprintf(stderr,
            "[layer-tree]   [%d] %s %p hidden=%d op=%.2f masksToBounds=%d bg=%s "
            "frame=(%.0f,%.0f,%.0fx%.0f)\n",
            depth, object_getClassName(c), (void*)c, (int)c.hidden, (double)c.opacity,
            (int)c.masksToBounds, bg ? "set" : "nil",
            c.frame.origin.x, c.frame.origin.y, c.frame.size.width, c.frame.size.height);
    }
    std::fprintf(stderr,
        "[layer-tree] host=%p host.window=%p host==active=%d active=%p active.window=%p "
        "window.visible=%d sup==active.layer=%d\n",
        (void*)hv, (void*)hv.window, (int)(hv == av), (void*)av, (void*)av.window,
        (int)(av.window ? av.window.isVisible : 0), (int)(sup == av.layer));
    std::fflush(stderr);
}
static void macapk_reassert_native_egl_layer(void) {
    CAMetalLayer* ul = g_unity_layer;
    MTKView* av = g_active_render_view;
    if (!ul || !av) return;
    // v1.0.1079, GATED OFF (MACAPK_EGL_LAYER_IDLE_HIDE=1): do not re-assert an
    // IDLE guest layer over content that IS being drawn.
    //
    // This guard exists for AGDK/native-EGL engines where g_unity_layer IS the
    // content and something keeps un-committing it. But it fires on ANY app that
    // ever brought an EGL window surface live, and it forces
    // hidden=NO/opacity=1/zPosition=2 unconditionally -- so an app whose guest
    // surface goes live and then produces nothing gets an OPAQUE EMPTY LAYER
    // pinned on top of the framework canvas that holds its real UI.
    //
    // MEASURED on Plague Inc (com.miniclip.plagueinc), 2026-08-27: ANGLE managed
    // 3 frames total while ViewCanvasDriver presented 3,600+ at
    // `nonzero=5826224/5826224 (100%)`. The canvas self-capture scores RICH
    // (78,144 colours, 90.2% coverage) and shows the real menu; the on-screen
    // window is flat teal (#016984), the guest layer's clear colour. Layer tree:
    // CAMetalLayer(z=0.0,no-pixels) CALayer(z=0.5) CAMetalLayer(z=2.0).
    //
    // So: if the guest has not acquired a drawable on its own layer for a while,
    // HIDE that layer instead of re-asserting it. The instant it presents again
    // the next tick brings it back. Generic -- keyed on whether the guest is
    // producing frames, never on which app it is.
    static const bool kIdleHide = [] {
        const char* e = std::getenv("MACAPK_EGL_LAYER_IDLE_HIDE");
        return e && *e == '1';            // default OFF pending a fleet A/B
    }();
    // Generous: a paused/backgrounded engine must not be judged idle, and the
    // AGDK case this guard was written for presents continuously.
    static const double kIdleSecs = [] {
        const char* e = std::getenv("MACAPK_EGL_LAYER_IDLE_SECS");
        double v = e ? std::atof(e) : 2.0;
        return (v >= 0.2 && v <= 30.0) ? v : 2.0;
    }();
    if (kIdleHide) {
        const double last = g_guest_layer_last_present.load(std::memory_order_relaxed);
        const double age  = CACurrentMediaTime() - last;
        // last==0 means the guest has NEVER presented on this layer.
        if (last == 0.0 || age > kIdleSecs) {
            if (!ul.hidden) {
                // Own begin/commit: this returns BEFORE the transaction the rest
                // of the function opens, so it cannot borrow that one.
                [CATransaction begin];
                [CATransaction setDisableActions:YES];
                ul.hidden = YES;
                [CATransaction commit];
                std::fprintf(stderr,
                    "[layer] guest EGL layer idle %.1fs -- hiding it so the framework "
                    "canvas below is visible (MACAPK_EGL_LAYER_IDLE_HIDE)\n",
                    last == 0.0 ? -1.0 : age);
                std::fflush(stderr);
            }
            return;
        }
    }
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (ul.hidden)          ul.hidden = NO;
    if (ul.opacity != 1.0f) ul.opacity = 1.0f;
    ul.zPosition = macapk_guest_layer_z_now(ul.superlayer);
    // MACAPK_LAYER_VIS_PROBE: a thick magenta border drawn by Core Animation ON TOP
    // of the layer's drawable contents. If the user sees magenta at the menu rect,
    // g_unity_layer IS on-screen + composited (so only the async-presented Metal
    // drawable contents aren't reaching the screen); if not, the layer is
    // clipped/hidden/orphaned somewhere in the ancestor chain.
    static bool probe = std::getenv("MACAPK_LAYER_VIS_PROBE") != nullptr;
    if (probe) {
        ul.borderColor = CGColorCreateGenericRGB(1.0, 0.0, 1.0, 1.0);
        ul.borderWidth = 40.0;
    }
    if (av.window && ul.superlayer != av.layer) {
        // orphaned by a content-view rewire -> re-parent onto the CURRENT view
        [ul removeFromSuperlayer];
        [av.layer addSublayer:ul];
        g_unity_host_view = av;
    }
    if (macapk_place_scaled_native_egl_layer(
            ul, av.bounds, av.window ? av.window.backingScaleFactor : ul.contentsScale)) {
        // placed at the guest's requested buffer size
    } else if (!CGRectEqualToRect(ul.frame, av.bounds)) {
        ul.frame = av.bounds;
        CGFloat scale = av.window ? av.window.backingScaleFactor : ul.contentsScale;
        ul.drawableSize = CGSizeMake(std::round(av.bounds.size.width  * scale),
                                     std::round(av.bounds.size.height * scale));
    }
    [CATransaction commit];
}
extern "C" void CocoaStartNativeEglLayerGuard(void) {
    if (g_layer_guard_timer) return;
    static bool trace    = std::getenv("MACAPK_LAYER_TREE_TRACE") != nullptr;
    // v1.0.602 diagnostic (MACAPK_LAYER_REASSERT / MACAPK_LAYER_TREE_TRACE), OPT-IN.
    // The periodic main-thread re-commit re-asserts g_unity_layer composited/parented.
    // v1.0.610 ground truth (sample of a stuck run): the layer is ALREADY correctly
    // composited during the 54% stall (layer-tree: sup==active.layer, window.visible,
    // all hidden=0) -- so the reassert does NOT fix the stall (the wedge is the present
    // thread pinned in nextDrawable on an exhausted pool, addressed in
    // macapk_bounded_nextDrawable), and it is left OPT-IN as a diagnostic, not default-on.
    static bool reassert = std::getenv("MACAPK_LAYER_REASSERT") != nullptr;
    if (!trace && !reassert) return;   // inert in production unless a diagnostic is set
    dispatch_queue_t mq = dispatch_get_main_queue();
    g_layer_guard_timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, mq);
    dispatch_source_set_timer(g_layer_guard_timer,
        dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)),
        (uint64_t)(0.5 * NSEC_PER_SEC), (uint64_t)(0.1 * NSEC_PER_SEC));
    dispatch_source_set_event_handler(g_layer_guard_timer, ^{
        if (!g_native_egl_surface_live.load()) return;
        if (reassert) macapk_reassert_native_egl_layer();
        if (trace) { static int t = 0; if (t++ % 2 == 0) CocoaDumpNativeEglLayerTree(); }
    });
    dispatch_resume(g_layer_guard_timer);
}

extern "C" void CocoaNoteNativeEglSurfaceLive(void) {
    if (!g_native_egl_surface_live.exchange(true)) {
        std::fprintf(stderr,
            "[I] cocoa_v2: guest EGL window surface live -> hiding "
            "framework angleLayer (native engine presents its own layer)\n");
    }
    // A/B LEVER (MACAPK_KEEP_ANGLE_LAYER=1): keep the framework angleLayer
    // visible even though a guest EGL surface went live.
    //
    // The hide assumes "a guest EGL surface exists" implies "the guest presents
    // the app's content on its own layer". That is true for AGDK/Unity engines
    // and FALSE for an app that merely creates a GLSurfaceView while its real UI
    // is drawn by ViewCanvasDriver through the framework angleLayer -- hiding it
    // then blanks the only layer carrying content. Plague Inc looks exactly like
    // that (ANGLE 3 frames total vs ViewCanvasDriver 3,600+ at 100% coverage),
    // so this lever exists to TEST that reading before changing any default.
    static const bool keep_angle =
        [] { const char* e = std::getenv("MACAPK_KEEP_ANGLE_LAYER");
             return e && *e == '1'; }();
    if (!keep_angle) {
        dispatch_async(dispatch_get_main_queue(), ^{
            CALayer* al = g_active_angle_layer;
            if (al && !al.hidden) al.hidden = YES;
        });
    } else {
        std::fprintf(stderr, "[layer] MACAPK_KEEP_ANGLE_LAYER=1: framework "
                             "angleLayer left VISIBLE\n");
        std::fflush(stderr);
    }
    CocoaStartNativeEglLayerGuard();
}

// ============ v1.0.611 offscreen-decouple present ring + presenter ============
// Guest (native-EGL Vulkan) renders into an ANGLE pbuffer; its eglSwapBuffers hook
// glReadPixels's the finished RGBA frame into one of three ring slots and publishes
// it here. drawInMTKView (main thread) presents the newest slot onto g_unity_layer
// as the SOLE -[CAMetalLayer nextDrawable] caller, at most two drawables in flight,
// holding NO guest lock -- so even a transient nextDrawable block can never wedge
// the guest's game-loop mutex (the 54% deadlock, where the diag_root sample caught
// two threads pinned in getCAMetalDrawable while the loader thread parked forever).
// No-op unless CocoaDecoupleActivate ran (Vulkan path only) -> the ANGLE-Metal
// Crossy/Unity present path is byte-identical.
// See docs/mcpe-54pct-async-recycle-crux_2026-07-11.md.
static std::mutex           g_dc_mtx;
static std::vector<uint8_t> g_dc_slot[3];
static int                  g_dc_w = 0, g_dc_h = 0;
static int                  g_dc_writing = -1;   // slot the guest is filling now
static int                  g_dc_ready   = -1;   // newest fully-written slot
static uint64_t             g_dc_ready_seq = 0, g_dc_consumed_seq = 0;
static std::atomic<bool>    g_dc_active{false};
// ---- AOSP-faithful producer backpressure for the decouple ring ---------------------------
// WHY THIS EXISTS. Android gives a guest its backpressure through the BufferQueue: eglSwapBuffers
// POSTS (queueBuffer, which cannot block) and the only producer wait is buffer exhaustion inside
// dequeueBuffer -- a POSSESSION-based wait on one condition variable, woken ONLY by a buffer
// actually being released by the consumer, performed with the platform's locks dropped
// (frameworks/native libs/gui/BufferQueueProducer.cpp waitForFreeSlotThenRelock;
// BufferQueueConsumer.cpp releaseBuffer). There is no timed delay anywhere in that path, and
// Android has no producer-side "wait K refreshes" mechanism at all (EGL maxSwapInterval is 1).
//
// Our offscreen decouple replaced the guest's window surface with a pbuffer, which -- as the
// comment on present_pacing_enabled() in bionic_shim.cpp says in its own words -- "deleted the
// BufferQueue backpressure a real Android window surface always applies (the guest is throttled to
// the consumer there; that IS the contract)". The ring then became newest-wins: the producer never
// blocked and the consumer dropped. Backpressure was re-imposed by PresentPacer, a WALL-CLOCK
// CREDIT COUNTER granted once per display tick and waited on INSIDE the guest's swap while the
// guest holds its own render mutex. Measured on a live stall: the Rendering Pool worker spends
// 2895 of 3192 samples (90.7%) inside that wait, and MINECRAFT MAIN is blocked on the guest mutex
// held across it.
//
// So this restores the real contract instead of emulating it: the producer waits for a SLOT, and
// the only thing that hands one back is the consumer having actually taken a frame.
// Escape hatch: MACAPK_DC_NO_BACKPRESSURE=1 restores the old newest-wins behaviour for A/B.
static std::condition_variable g_dc_cv;
// AOSP's default minimum is 1 dequeued + 1 acquired (BufferQueueCore.cpp, MAX_ACQUIRED_BUFFERS
// defaults + the dequeue slot), so two frames may be outstanding before the producer must wait.
static constexpr uint64_t kDcMaxOutstanding = 2;
static uint64_t             g_dc_wait_ns = 0;      // diagnostics: total producer wait
static uint64_t             g_dc_waits   = 0;
static uint64_t             g_dc_timeouts = 0;
static bool dc_backpressure_enabled() {
    static const int on = []{ const char* e = std::getenv("MACAPK_DC_NO_BACKPRESSURE");
                              return (e && *e != '0') ? 0 : 1; }();
    return on == 1;
}

extern "C" void CocoaDecoupleActivate(int w, int h) {
    std::lock_guard<std::mutex> lk(g_dc_mtx);
    g_dc_w = w; g_dc_h = h;
    for (auto& s : g_dc_slot) s.assign((size_t)w * h * 4, 0);
    g_dc_writing = g_dc_ready = -1;
    g_dc_ready_seq = g_dc_consumed_seq = 0;
    g_dc_active.store(true);
    std::fprintf(stderr, "[dc-ring] ARMED backpressure=%d budget=%llu (AOSP possession-based; "
                 "MACAPK_DC_NO_BACKPRESSURE=1 restores newest-wins)\n",
                 dc_backpressure_enabled() ? 1 : 0, (unsigned long long)kDcMaxOutstanding);
    std::fflush(stderr);
    std::fprintf(stderr,
        "[decouple] activated %dx%d (guest offscreen; runtime presents "
        "g_unity_layer)\n", w, h);
}
extern "C" int CocoaDecoupleIsActive(void) { return g_dc_active.load() ? 1 : 0; }

// Guest present thread: borrow a writable slot sized w*h*4. Never returns the
// current ready slot, so main can copy the ready frame out while the guest
// glReadPixels's into a different slot. No lock is held across the guest's
// (blocking) glReadPixels -- only the brief borrow/publish bookkeeping.
extern "C" void* CocoaDecoupleBeginFrame(int w, int h) {
    std::unique_lock<std::mutex> lk(g_dc_mtx);
    if (!g_dc_active.load()) return nullptr;
    if (w != g_dc_w || h != g_dc_h) {          // resize drift: re-alloc slots
        g_dc_w = w; g_dc_h = h;
        for (auto& s : g_dc_slot) s.assign((size_t)w * h * 4, 0);
        g_dc_ready = -1;
    }
    // THE BACKPRESSURE. Wait while more than kDcMaxOutstanding frames are un-consumed -- i.e.
    // exactly waitForFreeSlotThenRelock. The predicate is possession (a sequence delta), never a
    // clock, and the ONLY waker is CocoaDecouplePresentTick advancing g_dc_consumed_seq.
    if (dc_backpressure_enabled()) {
        const double t0 = CACurrentMediaTime();
        bool waited = false, timed_out = false;
        while (g_dc_active.load() &&
               (g_dc_ready_seq - g_dc_consumed_seq) >= kDcMaxOutstanding) {
            waited = true;
            // LABELLED DEVIATION FROM AOSP: Android's dequeueBuffer wait is UNBOUNDED by default
            // (it ends when a buffer frees or the queue is abandoned). We cap it so that a consumer
            // that stops -- a hidden window, a lost drawable, a teardown race -- degrades to the
            // old drop behaviour instead of wedging the guest's render thread forever. The cap is
            // counted and logged so a starving consumer is visible rather than silent.
            if (g_dc_cv.wait_for(lk, std::chrono::milliseconds(34)) == std::cv_status::timeout) {
                timed_out = true; ++g_dc_timeouts; break;
            }
        }
        if (!g_dc_active.load()) return nullptr;   // teardown while waiting: S5, tell the guest
        if (waited) {
            ++g_dc_waits;
            g_dc_wait_ns += (uint64_t)((CACurrentMediaTime() - t0) * 1e9);
        }
        (void)timed_out;
        static double win = 0.0; static uint64_t last_waits = 0;
        double now = CACurrentMediaTime();
        if (win == 0.0) win = now;
        if (now - win >= 5.0) {
            std::fprintf(stderr,
                "[dc-ring] budget=%llu waits=%llu (+%llu) timeouts=%llu meanWaitMs=%.2f\n",
                (unsigned long long)kDcMaxOutstanding, (unsigned long long)g_dc_waits,
                (unsigned long long)(g_dc_waits - last_waits),
                (unsigned long long)g_dc_timeouts,
                g_dc_waits ? (double)g_dc_wait_ns / 1e6 / (double)g_dc_waits : 0.0);
            std::fflush(stderr);
            last_waits = g_dc_waits; win = now;
        }
    }
    int pick = 0;
    for (int i = 0; i < 3; ++i) if (i != g_dc_ready) { pick = i; break; }
    g_dc_writing = pick;
    return g_dc_slot[pick].data();
}
// Guest present thread: publish the just-filled slot as newest-ready.
extern "C" void CocoaDecouplePublishFrame(void* slot) {
    std::lock_guard<std::mutex> lk(g_dc_mtx);
    if (g_dc_writing >= 0 && g_dc_slot[g_dc_writing].data() == slot) {
        g_dc_ready = g_dc_writing;
        g_dc_writing = -1;
        ++g_dc_ready_seq;
        // MACAPK_DECOUPLE_PUBRATE: log the GUEST frame-DELIVERY rate (unique frames
        // the guest actually published through the decouple path) once per 5s. This
        // is the true signal for the v618 throttle A/B: the host drawInMTKView "[I]
        // fps" line is display-driven (~60-74Hz) and stays flat whether or not the
        // throttle drops guest frames, so it CANNOT reveal a guest-frame-drop
        // regression. Publish rate can. Zero cost when the env is unset.
        static int pr_on = -1;
        if (pr_on < 0) { const char* e = std::getenv("MACAPK_DECOUPLE_PUBRATE");
                         pr_on = e ? 1 : 0; }
        if (pr_on == 1) {
            static double pr_win = 0.0; static int pr_cnt = 0;
            double now = CACurrentMediaTime();
            if (pr_win == 0.0) pr_win = now;
            ++pr_cnt;
            if (now - pr_win >= 5.0) {
                std::fprintf(stderr, "[decouple] publish rate: %.1f/s\n",
                             pr_cnt / (now - pr_win));
                std::fflush(stderr);
                pr_cnt = 0; pr_win = now;
            }
        }
    }
}

// Minimal Metal presenter: RGBA staging texture -> fullscreen triangle (V-flipped
// for GL bottom-left -> Metal top-left) -> g_unity_layer drawable. Inline shader
// compiled once via newLibraryWithSource (not deprecated on macOS 26).
struct DecouplePresenter {
    id<MTLDevice>              dev = nil;
    id<MTLCommandQueue>        q   = nil;
    id<MTLRenderPipelineState> pso = nil;
    id<MTLTexture>             tex = nil;
    int                        tw = 0, th = 0;
    dispatch_semaphore_t       inflight = nil;
};
static DecouplePresenter g_dcp;

static bool dcp_ensure(id<MTLDevice> dev) {
    if (g_dcp.pso) return true;
    if (!dev) return false;
    g_dcp.dev = dev;
    g_dcp.q = [dev newCommandQueue];
    g_dcp.inflight = dispatch_semaphore_create(2);   // cap OUR frames-in-flight
    NSError* err = nil;
    NSString* src =
      @"#include <metal_stdlib>\n"
       "using namespace metal;\n"
       "struct VOut { float4 pos [[position]]; float2 uv; };\n"
       "vertex VOut macapk_dc_vmain(uint vid [[vertex_id]]) {\n"
       "  float2 p[3] = { float2(-1,-1), float2(3,-1), float2(-1,3) };\n"
       "  VOut o; o.pos = float4(p[vid],0,1);\n"
       "  o.uv = float2(p[vid].x*0.5+0.5, 0.5 + p[vid].y*0.5);\n"  // GL bottom-up readback -> Metal: NO extra V-flip (verified: sky was at bottom otherwise)
       "  return o;\n"
       "}\n"
       "fragment float4 macapk_dc_fmain(VOut in [[stage_in]],\n"
       "    texture2d<float> tex [[texture(0)]]) {\n"
       "  constexpr sampler s(filter::linear);\n"
       "  return tex.sample(s, in.uv);\n"
       "}\n";
    id<MTLLibrary> lib = [dev newLibraryWithSource:src options:nil error:&err];
    if (!lib) {
        std::fprintf(stderr, "[decouple] shader compile failed: %s\n",
                     err ? err.localizedDescription.UTF8String : "?");
        return false;
    }
    MTLRenderPipelineDescriptor* pd = [[MTLRenderPipelineDescriptor alloc] init];
    pd.vertexFunction   = [lib newFunctionWithName:@"macapk_dc_vmain"];
    pd.fragmentFunction = [lib newFunctionWithName:@"macapk_dc_fmain"];
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;  // == drawable
    g_dcp.pso = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
    if (!g_dcp.pso) {
        std::fprintf(stderr, "[decouple] PSO create failed: %s\n",
                     err ? err.localizedDescription.UTF8String : "?");
        return false;
    }
    return true;
}

static void CocoaDecouplePresentTick(MTKView* view) {
    if (!g_dc_active.load() || !g_unity_layer || !view) return;
    // Copy the newest ready frame out under the lock (main is not the bottleneck;
    // the copy keeps the guest's borrow/resize from invalidating the pointer).
    static std::vector<uint8_t> mainbuf;   // main-thread only
    int W = 0, H = 0;
    {
        std::lock_guard<std::mutex> lk(g_dc_mtx);
        if (g_dc_ready < 0 || g_dc_ready_seq == g_dc_consumed_seq) return; // nothing new
        g_dc_consumed_seq = g_dc_ready_seq;
        W = g_dc_w; H = g_dc_h;
        mainbuf.assign(g_dc_slot[g_dc_ready].begin(), g_dc_slot[g_dc_ready].end());
    }
    // releaseBuffer: the frame has been taken, so a slot is genuinely free again. This is the ONLY
    // thing that wakes a waiting producer -- no timer, no display credit (BufferQueueConsumer.cpp
    // releaseBuffer -> mDequeueCondition.notify_all).
    g_dc_cv.notify_all();
    if (W <= 0 || H <= 0 || (int)mainbuf.size() < W * H * 4) return;
    if (!dcp_ensure(view.device)) return;
    // Non-blocking cap: NEVER block the main thread on our own present. If two of
    // our command buffers are already in flight, drop this frame (the layer keeps
    // showing the last one) -- this is what makes our nextDrawable un-wedgeable.
    if (dispatch_semaphore_wait(g_dcp.inflight, DISPATCH_TIME_NOW) != 0) return;
    if (!g_dcp.tex || g_dcp.tw != W || g_dcp.th != H) {
        MTLTextureDescriptor* td =
            [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                                               width:W height:H mipmapped:NO];
        td.usage = MTLTextureUsageShaderRead;
        td.storageMode = MTLStorageModeShared;   // Apple Silicon unified memory
        g_dcp.tex = [g_dcp.dev newTextureWithDescriptor:td];
        g_dcp.tw = W; g_dcp.th = H;
    }
    // Self-capture (v1.0.675): mainbuf IS the frame about to be presented, so
    // this covers native-EGL / Unity-style guests that render outside FBO 0 --
    // the exact case the GL-side dump cannot see. Bottom-up: these bytes came
    // from the guest's glReadPixels (same origin convention the shader's uv
    // comment above documents).
    if (SelfCaptureRequested("dc"))
        SelfCaptureWriteRGBA(mainbuf.data(), W, H, /*bottom_up=*/true, "dc");
    // v1.0.835: retain it too, so a guest that stops publishing (a paused
    // native-EGL menu) can still be captured from the main-thread poll, and
    // score it periodically so the log is self-verifying.
    MacapkSnapshotDc(mainbuf.data(), W, H);
    if (const unsigned every = SelfCaptureScorePeriod()) {
        static unsigned ticks = 0;   // main thread only
        // v1.0.997: score the just-retained snapshot on the background score
        // queue (see MacapkScoreSnapshotAsync) -- the inline two-pass score
        // was a periodic 30-50ms+ stall of THIS (the OS main / fps) thread.
        // The GL-origin flip moved into the async block with it.
        if ((ticks++ % every) == 0) MacapkScoreSnapshotAsync(/*dc=*/true);
    }
    [g_dcp.tex replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0
                   withBytes:mainbuf.data() bytesPerRow:(NSUInteger)W * 4];
    if (!g_unity_layer.device) g_unity_layer.device = g_dcp.dev;
    id<CAMetalDrawable> d = [g_unity_layer nextDrawable];   // ONLY nextDrawable caller
    if (!d) { dispatch_semaphore_signal(g_dcp.inflight); return; }  // skip, never crash
    MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
    rpd.colorAttachments[0].texture     = d.texture;
    rpd.colorAttachments[0].loadAction  = MTLLoadActionDontCare;  // we cover every texel
    rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLCommandBuffer> cb = [g_dcp.q commandBuffer];
    id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rpd];
    [enc setRenderPipelineState:g_dcp.pso];
    [enc setFragmentTexture:g_dcp.tex atIndex:0];
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding];
    [cb presentDrawable:d];
    dispatch_semaphore_t sem = g_dcp.inflight;
    [cb addCompletedHandler:^(id<MTLCommandBuffer>){ dispatch_semaphore_signal(sem); }];
    [cb commit];
}

// v1.0.503: largest (aspectW:aspectH)-aspect rect, centered inside a container
// of size `cs`. Used to aspect-FIT the render MTKView into the letterbox
// container so the content scales with the window with no distortion: in the
// normal (aspect-locked) drag case the fit fills the container exactly;
// full-screen / zoom letterboxes. No upscale cap here -- the render view is
// MEANT to grow to fill the (aspect-locked) window. The initial window-size
// clamp in CocoaInit caps at 1.0 separately (it must not enlarge a small
// preset to fill the screen). See docs/live-aspect-resize-design_2026-06-24.md.
static NSRect MacApkAspectFitRect(NSSize cs, CGFloat aspectW, CGFloat aspectH) {
    if (aspectW <= 0 || aspectH <= 0 || cs.width <= 0 || cs.height <= 0)
        return NSMakeRect(0, 0, cs.width, cs.height);
    CGFloat scale = MIN(cs.width / aspectW, cs.height / aspectH);
    // ROUND to whole points. A fractional fit is the root of the hcr181
    // render race (v1.0.781): fitting 1680x930 into width 1622 gives
    // h = 930 * (1622/1680) = 897.8929 pt, and at backingScale 2 that is
    // 1795.7857 px -- a size no drawable can actually be. CAMetalLayer
    // derives its drawableSize from bounds * contentsScale and TRUNCATES it
    // to 1795, while MTKView ROUNDS its own to 1796. Nothing downstream can
    // reconcile that: we publish the layer's 1795 to the guest, seed the
    // live-resize tracker from it, then compare against the view's 1796, so
    // the "window was resized" branch fires on the next frame of every boot
    // with nothing resized. That fire is asynchronous, and when it lands
    // after the Java view tree publishes the status-bar-inset 3244x1748,
    // cocos2d-x v2 -- which computes one glViewport from whichever size it
    // latched last -- ends up at 1796 (near-blank) instead of 1747 (renders).
    //
    // Rounding downstream does not work; it was tried at all five
    // drawableSize writers and the layer still read 1795, because the value
    // is auto-derived from the fractional BOUNDS rather than written by us.
    // The only fix is to stop producing a fractional rect at all.
    //
    // Cost: the fitted aspect ratio moves by under 0.1% (898/1622 against
    // 930/1680), which is imperceptible and is what AppKit geometry wants
    // anyway. Origins are rounded too so the rect stays pixel-aligned.
    CGFloat w = std::round(aspectW * scale);
    CGFloat h = std::round(aspectH * scale);
    return NSMakeRect(std::round((cs.width - w) * 0.5),
                      std::round((cs.height - h) * 0.5), w, h);
}

// ---------------------------------------------------------------------------
// v1.0.675: permission-free self-capture. See macapk/self_capture.h for why
// this exists (the Screen Recording grant lapses on every Claude Code update)
// and for the request/output protocol.
// This file is already inside `namespace macapk`, so these definitions match
// the declarations in the header without opening it again.
// ---------------------------------------------------------------------------

static void MacapkCaptureReqPath(char* req, size_t reqn) {
    std::snprintf(req, reqn, "/tmp/macapk_capture_req_%d", (int)getpid());
}
static void MacapkCaptureOutPath(char* out, size_t outn, const char* tag) {
    std::snprintf(out, outn, "/tmp/macapk_capture_%d_%s.png", (int)getpid(),
                  tag ? tag : "gl");
}
// Completion marker. See the comment on SelfCaptureRequested for why a reader
// cannot safely decide on its own that every source has written.
static void MacapkCaptureDonePath(char* out, size_t outn) {
    std::snprintf(out, outn, "/tmp/macapk_capture_%d.done", (int)getpid());
}
// Bit per capture source, so each one fires at most once per request window.
static uint32_t MacapkCaptureBit(const char* tag) {
    if (tag && std::strcmp(tag, "canvas") == 0)    return 1u << 1;
    if (tag && std::strcmp(tag, "dc") == 0)        return 1u << 2;
    if (tag && std::strcmp(tag, "layer") == 0)     return 1u << 3;
    if (tag && std::strcmp(tag, "layer2") == 0)    return 1u << 4;
    if (tag && std::strcmp(tag, "composite") == 0) return 1u << 5;
    return 1u << 0;   // "gl"
}

// Set of sources whose PNG rename has COMPLETED in the current window, and the
// set that claimed a slot. The marker is published only when they agree.
static std::atomic<uint32_t> g_cap_claimed{0};
static std::atomic<uint32_t> g_cap_completed{0};
// Capture-window state, at file scope (v1.0.1036) so the main-thread poll can
// schedule the COMPOSITE source near the end of the window -- after the
// per-layer sources have had their chance to refresh the per-layer pixel
// store the composite reads. Written only inside SelfCaptureRequested.
static std::atomic<uint64_t> g_cap_window_end_ms{0};
static std::atomic<bool>     g_cap_window_live{false};

static bool MacapkSelfCaptureEnabled() {
    static const bool enabled = [] {
        const char* e = std::getenv("MACAPK_SELF_CAPTURE");
        return !(e && std::strcmp(e, "0") == 0);
    }();
    return enabled;
}

bool SelfCaptureGlWanted() {
    if (!MacapkSelfCaptureEnabled()) return false;
    if (g_cap_window_live.load(std::memory_order_acquire)) {
        const uint64_t now_ms = (uint64_t)(CACurrentMediaTime() * 1000.0);
        return now_ms < g_cap_window_end_ms.load(std::memory_order_acquire)
            && (g_cap_claimed.load(std::memory_order_acquire) & (1u << 0)) == 0;
    }
    // No window open: a request file waiting for the next poll. One stat() per
    // few withheld ticks, the same throttle SelfCaptureRequested uses.
    static std::atomic<unsigned> tick{0};
    if ((tick.fetch_add(1, std::memory_order_relaxed) % 6) != 0) return false;
    char req[PATH_MAX];
    MacapkCaptureReqPath(req, sizeof(req));
    struct stat st;
    return stat(req, &st) == 0;
}

bool SelfCaptureRequested(const char* tag) {
    if (!MacapkSelfCaptureEnabled()) return false;
    // A request opens a WINDOW rather than being consumed by the first caller:
    // several present paths run per frame and only some carry the content, so
    // every source gets to capture once and the caller picks the richest.
    //
    // WHY A COMPLETION MARKER (v1.0.803). The sources do NOT fire together: a
    // ~100 KB canvas PNG can land a frame or more before the ~2.4 MB gl PNG,
    // because they hang off different present paths. A reader has no way to
    // tell "every source has written" from "the next source has not started
    // yet", and every harness that tried to guess got it wrong -- polling
    // until the total size stops growing settles during that gap, scores the
    // canvas file alone, and reports a rendering app as blank. That is not
    // hypothetical: it is the hcr181 "REGRESS 1 @ 3244x1796" that was read as
    // an arm32rc codegen regression and blocked the LLVM cutover, while a
    // 3244x1747 / 63007-colour gl capture sat on disk from the same run.
    //
    // So the runtime states the fact instead: when the window has expired AND
    // every source that claimed a slot has finished renaming its PNG, write
    // /tmp/macapk_capture_<pid>.done listing those sources. Readers wait for
    // that file (debug/capture_wait.sh) rather than inferring quiescence.
    std::atomic<uint64_t>& window_end_ms = g_cap_window_end_ms;
    std::atomic<bool>&     window_live   = g_cap_window_live;
    const uint64_t now_ms = (uint64_t)(CACurrentMediaTime() * 1000.0);
    if (now_ms >= window_end_ms.load(std::memory_order_acquire)) {
        if (window_live.load(std::memory_order_acquire)) {
            // Defer the marker while a claimed source is still mid-write: the
            // whole point is that its file exists before anyone scores. The
            // next present retries ~16ms later.
            //
            // The grace deadline is the escape hatch: a source can claim a slot
            // and then legitimately decline to write (angle_glue skips an
            // out-of-range viewport, SelfCaptureWriteRGBA rejects bad dims), and
            // without a deadline that claim would suppress the marker forever
            // and push every reader onto its fallback timeout.
            const uint32_t claimed  = g_cap_claimed.load(std::memory_order_acquire);
            const bool     all_done = g_cap_completed.load(std::memory_order_acquire) == claimed;
            if (all_done
                || now_ms >= window_end_ms.load(std::memory_order_acquire) + 600) {
                window_live.store(false, std::memory_order_release);
                // Name the sources whose PNG is actually ON DISK, not the ones
                // that claimed a slot -- on the grace path those differ, and a
                // marker that promises a file the reader cannot open is worse
                // than no marker.
                const uint32_t wrote = g_cap_completed.load(std::memory_order_acquire);
                char done[PATH_MAX];
                MacapkCaptureDonePath(done, sizeof(done));
                char tmp[PATH_MAX];
                std::snprintf(tmp, sizeof(tmp), "%s.part", done);
                if (FILE* f = std::fopen(tmp, "w")) {
                    std::fprintf(f, "%s%s%s%s%s%s\n",
                                 (wrote & (1u << 0)) ? "gl " : "",
                                 (wrote & (1u << 1)) ? "canvas " : "",
                                 (wrote & (1u << 2)) ? "dc " : "",
                                 (wrote & (1u << 3)) ? "layer " : "",
                                 (wrote & (1u << 4)) ? "layer2 " : "",
                                 (wrote & (1u << 5)) ? "composite" : "");
                    std::fclose(f);
                    ::rename(tmp, done);   // atomic: readers never see a partial marker
                }
            }
        }
        // No window open: poll for a request. Throttled to ~every 6th present,
        // so the idle cost is one stat() per few frames.
        static std::atomic<unsigned> tick{0};
        if ((tick.fetch_add(1, std::memory_order_relaxed) % 6) != 0) return false;
        char req[PATH_MAX];
        MacapkCaptureReqPath(req, sizeof(req));
        struct stat st;
        if (stat(req, &st) != 0) return false;
        ::unlink(req);                 // single-shot: consume the request
        // Drop the previous window's marker before opening this one, so a
        // reader cannot mistake a stale .done for this request's completion.
        char done[PATH_MAX];
        MacapkCaptureDonePath(done, sizeof(done));
        ::unlink(done);
        g_cap_claimed.store(0, std::memory_order_relaxed);
        g_cap_completed.store(0, std::memory_order_relaxed);
        window_live.store(true, std::memory_order_release);
        // WINDOW LENGTH: must span at least one WHOLE frame of the slowest
        // present path, because the sources sit at different points INSIDE a
        // frame -- canvas captures mid-frame (CocoaPresentBitmap, reached from
        // the macapkDrawFrame JNI call) while gl captures at end-of-frame
        // (AngleEndFrame). The old 400ms was chosen as "~24 frames at 60fps",
        // which is only true on an idle box: measured 2026-07-27 on this 8 GB
        // host, hcr181 ran as slowly as 60 frames in 50 s, so the window
        // expired BETWEEN the canvas capture and the gl capture of the SAME
        // frame. The result is a window that saw only the ~100 KB canvas file,
        // and a rendering app scored blank off it (the hcr181 false REGRESS).
        //
        // 2500ms covers a frame loop down to ~1 fps. The cost is that the
        // completion marker appears ~2.5s after the request instead of ~0.4s,
        // which is nothing next to the 45-55s a verdict already waits, and
        // readers block on the marker rather than a timeout. Overlap with a
        // following request is not a concern for the same reason.
        static const uint64_t win_ms = [] {
            const char* e = std::getenv("MACAPK_SELF_CAPTURE_WINDOW_MS");
            const long v = e ? std::strtol(e, nullptr, 10) : 0;
            return (uint64_t)(v > 0 ? v : 2500);
        }();
        window_end_ms.store(now_ms + win_ms, std::memory_order_release);
    }
    const uint32_t bit = MacapkCaptureBit(tag);
    return (g_cap_claimed.fetch_or(bit, std::memory_order_acq_rel) & bit) == 0;
}

// Shared tail for all three capture sources. Writes to a temp path and renames,
// so a reader polling for the output never sees a half-written PNG.
static void MacapkWriteCaptureCGImage(CGImageRef img, const char* tag) {
    if (!img) return;
    char out[PATH_MAX];
    MacapkCaptureOutPath(out, sizeof(out), tag);
    char tmp[PATH_MAX];
    std::snprintf(tmp, sizeof(tmp), "%s.part", out);
    NSURL* url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:tmp]];
    CGImageDestinationRef dst = CGImageDestinationCreateWithURL(
        (__bridge CFURLRef)url,
        CFSTR("public.png") /* UTI; avoids deprecated kUTTypePNG + a new framework link */,
        1, nullptr);
    bool ok = false;
    if (dst) {
        CGImageDestinationAddImage(dst, img, nullptr);
        ok = CGImageDestinationFinalize(dst);
        CFRelease(dst);
    }
    if (ok) {
        ::rename(tmp, out);
        // Mark this source complete AFTER the rename, so the .done marker
        // cannot be published while this file is still absent (see the
        // completion-marker comment on SelfCaptureRequested).
        g_cap_completed.fetch_or(MacapkCaptureBit(tag), std::memory_order_acq_rel);
        std::fprintf(stderr, "[self-capture] %s: wrote %zux%zu -> %s\n",
                     tag ? tag : "gl", CGImageGetWidth(img), CGImageGetHeight(img), out);
        std::fflush(stderr);
    } else {
        // A failed encode still has to release its claim, or the window never
        // reaches claimed==completed and the marker is never written.
        g_cap_completed.fetch_or(MacapkCaptureBit(tag), std::memory_order_acq_rel);
        ::unlink(tmp);
    }
}

// Capture source 3: the Java Canvas / ViewCanvasDriver present path. Called
// from CocoaPresentBitmap with the ARGB image it is about to composite. This is
// the ONLY source that sees pure-Java / Compose / Canvas apps -- their pixels
// never touch FBO 0, which is why the GL readback scores them a false BLANK.
void SelfCaptureWriteCGImageForCanvas(void* cgImage, const char* tag) {
    MacapkWriteCaptureCGImage((CGImageRef)cgImage, tag);
}

void SelfCaptureWriteRGBA(const uint8_t* rgba, int width, int height,
                          bool bottom_up, const char* tag) {
    if (!rgba || width <= 0 || height <= 0 || width > 16384 || height > 16384) return;
    const size_t stride = (size_t)width * 4;
    std::vector<uint8_t> rows((size_t)height * stride);
    for (int y = 0; y < height; ++y) {
        const uint8_t* src = rgba + (size_t)(bottom_up ? (height - 1 - y) : y) * stride;
        std::memcpy(&rows[(size_t)y * stride], src, stride);
    }
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    if (!cs) return;
    // kCGImageAlphaNoneSkipLast: treat the 4th byte as padding, NOT alpha. A GL
    // readback often has alpha 0, which would otherwise write a fully
    // transparent PNG and read as an empty frame downstream.
    CGContextRef ctx = CGBitmapContextCreate(
        rows.data(), (size_t)width, (size_t)height, 8, stride, cs,
        kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big);
    CGColorSpaceRelease(cs);
    if (!ctx) return;
    CGImageRef img = CGBitmapContextCreateImage(ctx);
    CGContextRelease(ctx);
    if (!img) return;
    // Score the TOP-DOWN copy we just built, so the 4x4 occupancy grid's row 0
    // really is the top of the frame (the chrome-only note depends on it).
    SelfCaptureScoreFrame(rows.data(), width, height, (int)stride,
                          /*bgra=*/false, tag);
    MacapkWriteCaptureCGImage(img, tag);
    CGImageRelease(img);
}

// ---------------------------------------------------------------------------
// v1.0.835: IN-RUNTIME frame STRUCTURE scoring. See the SelfCaptureScoreFrame
// contract in macapk/self_capture.h for WHY a pixel count is insufficient.
//
// The statistics and thresholds mirror debug/richcheck.swift exactly, so its
// fleet calibration (GD Meltdown 48k RICH, Bouncy chrome-only, the Crossy flat
// #69ceeb TINTED shape) transfers without re-calibration. Two deliberate
// divergences, both because this side sees the frame rather than a window shot:
// alpha is ignored (no rounded corners / drop shadow to exclude, and a GL
// readback's alpha is routinely 0), and there is no title bar -- but Mac.apk's
// OWN status bar still draws into a Canvas frame and scores 200-350 distinct
// colours on an otherwise EMPTY screen, which is exactly why no threshold here
// is a bare colour count.
// ---------------------------------------------------------------------------
namespace {

struct FrameStats {
    uint32_t distinct    = 0;
    uint32_t modal       = 0;   // 0x00RRGGBB
    double   modal_share = 0.0;
    double   coverage    = 0.0;
    double   edges       = 0.0;
    int      cells       = 0;   // occupied cells of the 4x4 grid
    int      top_row     = 0;   // how many of those are in row 0 (chrome band)
};

// Scratch, reused across calls and guarded by g_score_mu. A 2^24-bit set is the
// affordable way to get an EXACT distinct-colour count: a 2^24 COUNT array
// would be 64 MB, and this host has ~8 GB with a JVM and a guest engine already
// in it. The modal colour still comes out exact, via a coarse 5-5-5 histogram
// (128 KB) to find the winning bucket followed by a 512-entry fine pass inside
// it -- a bucket spans exactly 8x8x8 colours.
std::mutex             g_score_mu;
std::vector<uint64_t>  g_score_seen;    // 1 << 24 bits
std::vector<uint32_t>  g_score_hist555; // 1 << 15 buckets

bool MacapkComputeFrameStats(const uint8_t* px, int w, int h, int stride,
                             bool bgra, FrameStats* out) {
    if (!px || !out || w <= 1 || h <= 1 || stride < w * 4) return false;
    const int ri = bgra ? 2 : 0, bi = bgra ? 0 : 2;
    std::lock_guard<std::mutex> lk(g_score_mu);
    if (g_score_seen.size() != (1u << 24) / 64) g_score_seen.assign((1u << 24) / 64, 0);
    else std::fill(g_score_seen.begin(), g_score_seen.end(), 0ull);
    if (g_score_hist555.size() != (1u << 15)) g_score_hist555.assign(1u << 15, 0);
    else std::fill(g_score_hist555.begin(), g_score_hist555.end(), 0u);

    // Pass 1: exact distinct + coarse modal bucket.
    uint32_t distinct = 0;
    const size_t total = (size_t)w * h;
    for (int y = 0; y < h; ++y) {
        const uint8_t* row = px + (size_t)y * stride;
        for (int x = 0; x < w; ++x) {
            const uint8_t* p = row + (size_t)x * 4;
            const uint32_t key = ((uint32_t)p[ri] << 16) | ((uint32_t)p[1] << 8) | p[bi];
            uint64_t& word = g_score_seen[key >> 6];
            const uint64_t bit = 1ull << (key & 63);
            if (!(word & bit)) { word |= bit; ++distinct; }
            g_score_hist555[((key >> 19) & 0x1f) << 10 | ((key >> 11) & 0x1f) << 5
                            | ((key >> 3) & 0x1f)] += 1;
        }
    }
    uint32_t best_bucket = 0, best_n = 0;
    for (uint32_t i = 0; i < (1u << 15); ++i)
        if (g_score_hist555[i] > best_n) { best_n = g_score_hist555[i]; best_bucket = i; }

    // Pass 2: exact modal inside the winning bucket, plus coverage / edge
    // density / 4x4 occupancy. Two passes over the frame is the price of an
    // exact modal colour; richcheck.swift pays the same shape of cost.
    uint32_t fine[512] = {0};
    const uint8_t br = (uint8_t)(((best_bucket >> 10) & 0x1f) << 3);
    const uint8_t bg = (uint8_t)(((best_bucket >> 5)  & 0x1f) << 3);
    const uint8_t bb = (uint8_t)(( best_bucket        & 0x1f) << 3);
    for (int y = 0; y < h; ++y) {
        const uint8_t* row = px + (size_t)y * stride;
        for (int x = 0; x < w; ++x) {
            const uint8_t* p = row + (size_t)x * 4;
            const uint8_t r = p[ri], g = p[1], b = p[bi];
            if ((r >> 3) == (br >> 3) && (g >> 3) == (bg >> 3) && (b >> 3) == (bb >> 3))
                fine[((r & 7) << 6) | ((g & 7) << 3) | (b & 7)] += 1;
        }
    }
    uint32_t fbest = 0, fn = 0;
    for (uint32_t i = 0; i < 512; ++i) if (fine[i] > fn) { fn = fine[i]; fbest = i; }
    const int mR = br | (int)((fbest >> 6) & 7);
    const int mG = bg | (int)((fbest >> 3) & 7);
    const int mB = bb | (int)( fbest       & 7);

    // Tolerance 18/255 per channel: ignores gradient banding and compression
    // noise while still counting real drawn pixels (richcheck.swift's value).
    const int tol = 18;
    size_t covered = 0, edges = 0;
    size_t cell_cov[16] = {0}, cell_tot[16] = {0};
    const int cellW = w / 4 > 0 ? w / 4 : 1, cellH = h / 4 > 0 ? h / 4 : 1;
    auto luma = [&](const uint8_t* p) {
        return ((int)p[ri] * 299 + (int)p[1] * 587 + (int)p[bi] * 114) / 1000;
    };
    for (int y = 0; y < h; ++y) {
        const uint8_t* row  = px + (size_t)y * stride;
        const uint8_t* next = (y + 1 < h) ? row + stride : nullptr;
        const int cy = (y / cellH) < 3 ? (y / cellH) : 3;
        for (int x = 0; x < w; ++x) {
            const uint8_t* p = row + (size_t)x * 4;
            const int cx = (x / cellW) < 3 ? (x / cellW) : 3;
            cell_tot[cy * 4 + cx] += 1;
            if (std::abs((int)p[ri] - mR) > tol || std::abs((int)p[1] - mG) > tol
                || std::abs((int)p[bi] - mB) > tol) {
                ++covered;
                cell_cov[cy * 4 + cx] += 1;
            }
            const int l = luma(p);
            if (x + 1 < w && std::abs(l - luma(p + 4)) > 12) { ++edges; continue; }
            if (next && std::abs(l - luma(next + (size_t)x * 4)) > 12) ++edges;
        }
    }
    out->distinct    = distinct;
    out->modal       = ((uint32_t)mR << 16) | ((uint32_t)mG << 8) | (uint32_t)mB;
    // Share of the EXACT modal colour (fn), not of its 5-5-5 bucket (best_n) --
    // a bucket share would over-report a smooth gradient as a flat fill and turn
    // a real frame into a false TINTED.
    out->modal_share = 100.0 * (double)fn / (double)total;
    out->coverage    = 100.0 * (double)covered / (double)total;
    out->edges       = 100.0 * (double)edges   / (double)total;
    out->cells = 0;
    out->top_row = 0;
    // A cell counts as occupied once >=2% of its OWN pixels are non-modal.
    for (int i = 0; i < 16; ++i) {
        if (cell_tot[i] == 0) continue;
        if (100.0 * (double)cell_cov[i] / (double)cell_tot[i] >= 2.0) {
            ++out->cells;
            if (i < 4) ++out->top_row;
        }
    }
    return true;
}

const char* MacapkFrameVerdict(const FrameStats& s) {
    if (s.distinct <= 8 || s.coverage < 0.5)                    return "BLANK";
    if (s.modal_share >= 90.0 && s.edges < 1.0)                 return "TINTED";
    if (s.distinct >= 5000 && s.coverage >= 15.0
        && s.cells >= 8 && s.edges >= 2.0)                      return "RICH";
    if (s.distinct >= 800 && s.coverage >= 5.0 && s.cells >= 4) return "CONTENT";
    // STRUCTURE-ONLY path to CONTENT (v1.0.835). The rule above needs
    // distinct>=800, and a LOW-PALETTE game legitimately fails it: Crossy Road,
    // a flat-shaded voxel title, presented 711 distinct colours while covering
    // 38.1% of the frame across 8 of 16 cells with 4.52% edge density. Calling
    // that SPARSE is the colour-count trap the whole instrument exists to avoid
    // -- and the owner's rule is explicit that a threshold "must not be a colour
    // count at all". Coverage plus spatial spread separates it from a blank
    // cleanly, which a colour count cannot: F-Droid's 745 distinct colours come
    // with ~0% coverage, and Sgt Puzzles' flat grey fill reaches only 14.6%
    // coverage / 4 of 16 cells. Checked against every control before landing:
    // Bouncy (coverage 0.0%, 0/16) stays BLANK, the TINTED apps are caught by
    // the branch above, and Sgt Puzzles stays SPARSE.
    if (s.coverage >= 25.0 && s.cells >= 8 && s.edges >= 1.0)   return "CONTENT";
    return "SPARSE";
}

}  // namespace

unsigned SelfCaptureScorePeriod() {
    static const unsigned every = [] {
        const char* e = std::getenv("MACAPK_FRAME_SCORE_EVERY");
        if (!e) return 120u;
        const long v = std::strtol(e, nullptr, 10);
        return (v >= 0 && v < 1000000) ? (unsigned)v : 120u;
    }();
    return every;
}

// ---------------------------------------------------------------------------
// v1.0.835: the LAYER capture source -- the universal one. Rationale (and the
// Smash Hit / Crossy Road measurements that forced it) is on the source list in
// macapk/self_capture.h; this is the mechanism.
//
// A guest that drives its own EGL surface never enters AngleEndFrame,
// CocoaPresentBitmap or CocoaDecouplePresentTick, so the other three sources see
// nothing at all. What it CANNOT avoid is -[CAMetalLayer nextDrawable]: every
// backend that reaches the screen on macOS (ANGLE-Metal, ANGLE-Vulkan through
// MoltenVK, libunity's own layer, our framework angleLayer) gets its drawable
// from that one method, which the bounded-acquire swizzle above already
// intercepts. So the capture hangs off the swizzle and is engine-agnostic by
// construction -- no package name, no libunity probe, no backend gate.
//
// TIMING. The swizzle sees the drawable BEFORE the guest renders into it, so we
// cannot read it there. Instead, when a request is outstanding we RETAIN the
// drawable we just vended; by the time the guest asks for the next one, that
// drawable has been rendered and presented, and our retain is what keeps its
// texture contents valid (CA cannot recycle a drawable that is still referenced).
// The blit then runs on the guest's own render thread, off the critical path of
// anything the main thread is doing. Cost is one pool slot (of three) for one
// frame, and only while a request is outstanding -- in steady state the swizzle
// adds one relaxed atomic load per acquire.
//
// framebufferOnly. A drawable from a framebufferOnly=YES layer forbids the blit
// (Metal validation rejects using it as a copy SOURCE), and the flag only
// affects drawables allocated AFTER it changes. So the swizzle clears it on
// first sight of each layer rather than at request time, which would be one
// frame too late. g_unity_layer already carries NO for an unrelated correctness
// reason (a deferred engine resolving into the drawable); the framework's
// angleLayer and any layer a third-party framework made are the ones this
// actually changes. Opt out with MACAPK_SELF_CAPTURE_LAYER=0.
static bool MacapkLayerCaptureEnabled() {
    static const bool on = [] {
        const char* c = std::getenv("MACAPK_SELF_CAPTURE");
        if (c && std::strcmp(c, "0") == 0) return false;
        const char* e = std::getenv("MACAPK_SELF_CAPTURE_LAYER");
        return !(e && std::strcmp(e, "0") == 0);
    }();
    return on;
}

// The drawable retained by the last claimed request. Written by whichever guest
// thread ran the swizzle, read by that thread's next acquire or by the
// main-thread poll -- so both the pointer swap and the "who does the readback"
// decision go through one mutex.
//
// THIS TU IS COMPILED WITHOUT ARC (no -fobjc-arc in OBJCXX_FLAGS), so a plain
// `static id x; x = drawable;` does NOT retain. That is not a style nit: the
// drawable is vended inside the guest render thread's autorelease pool, so the
// unretained store left a dangling pointer and the next `d.texture` was a
// SIGSEGV in objc_msgSend (caught here on the first Frozen Bubble run). Hold it
// as an explicitly +1-retained CFTypeRef, the same convention the
// bounded-nextDrawable NDHandoff above uses for the same reason.
// TWO slots, because a native-EGL app HAS two CAMetalLayers and they carry
// different things: the guest's dedicated presentation layer (g_unity_layer,
// created by CocoaEnsureNativeEglLayer / the Unity pre-create path) and the
// framework's own angleLayer underneath it. A single slot let whichever layer
// vended first win the race, so the capture could not say WHICH surface it had
// read -- and that distinction IS the render-vs-composite bisect this instrument
// exists to answer. So the tag is chosen by identity, not by arrival order:
//   "layer"  = the guest's dedicated native-EGL layer when one exists
//              (otherwise: whatever single layer the app presents through)
//   "layer2" = any other CAMetalLayer, i.e. normally the framework's angleLayer
// More than two layers is not a case this tree produces; extras are ignored
// rather than silently overwriting a slot.
static std::mutex          g_layercap_mu;
static CFTypeRef           g_layercap_pending[2] = {nullptr, nullptr};  // +1 drawables
static void*               g_layercap_slot_layer[2] = {nullptr, nullptr};  // identity only
static id<MTLDevice>       g_layercap_dev  = nil;         // +1 (CFRetain'd)
static id<MTLCommandQueue> g_layercap_q    = nil;         // +1 (new... )
static id<MTLTexture>      g_layercap_dst  = nil;         // +1 (new... )
static std::vector<uint8_t> g_layercap_buf;

// The TAG is derived from identity every time, NOT from the slot index. Slots
// only answer "which pending drawable belongs to which layer"; naming is a
// separate question, and tying it to slot order got it wrong for the common case:
// a pure-framework app has no dedicated layer at all, and a rule of "slot 0 is
// the dedicated one" pushed its ONLY layer into slot 1 and reported it as
// `layer2` with no `layer` anywhere. Deriving the tag from identity also handles
// the guest layer being created after the framework one has already vended.
// v1.0.837: g_unity_layer is the DEDICATED native-EGL/Vulkan layer and is nil on the
// ANGLE-Metal path, so keying only on it tagged EVERY layer "layer" for an ANGLE-Metal
// guest -- including the framework's own angleLayer, which composites nothing but our
// window chrome. Crossy Road has TWO layers vending drawables (the sample shows
// -[MTKView draw] on the main thread AND ANGLE's WindowSurfaceMtl acquire on
// UnityGfxDeviceW), so a `layer` reading there was unattributable: a CONTENT verdict
// could equally have been our status bar. Fall back to excluding the framework's
// angleLayer by identity, which is the thing we actually want to call "not the guest".
// Ref: docs/crossy-blank-blue-regression_2026-07-29.md section 6.
static const char* MacapkLayerTagFor(CAMetalLayer* layer) {
    if (g_unity_layer)
        return ((void*)layer == (void*)g_unity_layer) ? "layer" : "layer2";
    if (g_active_angle_layer && (void*)layer == (void*)g_active_angle_layer)
        return "layer2";                        // the FRAMEWORK's layer, never the guest
    return "layer";                             // guest layer, or a single-layer app
}

// Which pending slot a layer owns, first-come. Call under g_layercap_mu.
// -1 = a third layer, which this tree does not produce; ignore it rather than
// stomping a slot that belongs to someone else.
static int MacapkLayerSlotFor(CAMetalLayer* layer) {
    void* key = (void*)layer;
    for (int i = 0; i < 2; ++i) if (g_layercap_slot_layer[i] == key) return i;
    for (int i = 0; i < 2; ++i)
        if (!g_layercap_slot_layer[i]) { g_layercap_slot_layer[i] = key; return i; }
    return -1;
}

// Give a slot back so a failure never holds the .done marker hostage (the reader
// would otherwise sit out its whole timeout for nothing).
static void MacapkLayerCaptureAbandon(const char* tag) {
    g_cap_completed.fetch_or(MacapkCaptureBit(tag), std::memory_order_acq_rel);
}

// ---------------------------------------------------------------------------
// v1.0.1036: per-CAMetalLayer LAST-PIXELS store, feeding the COMPOSITE source.
//
// The per-layer sources answer "what did THIS surface last present"; none of
// them answers "what does the USER see" -- which layer is on top, at what
// frame, with what opacity. That composite is what an emulator screenshot
// comparison needs, and picking the richest single source has already misled
// (a video app's richest source was the isolated video plane: 291k colours,
// zero UI). So every metal-layer readback ALSO parks its pixels here, keyed by
// layer identity, and MacapkBuildCompositeCapture (main thread) walks the real
// sublayer tree in zPosition order and blends these buffers at each layer's
// actual frame. Entries persist across capture windows deliberately -- an idle
// guest that presented nothing inside THIS window is still on screen with its
// LAST frame, which is exactly what the store holds (same rationale as
// g_snap_canvas above).
struct MacapkLayerPixels {
    void*                layer = nullptr;   // identity only; never dereferenced
    std::vector<uint8_t> rgba;              // top-down RGBA8
    int                  w = 0, h = 0;
    uint64_t             seq = 0;           // recency for slot eviction
};
static std::mutex        g_lpix_mu;
static MacapkLayerPixels g_lpix[4];
static uint64_t          g_lpix_seq = 0;

static void MacapkStoreLayerPixels(void* layerKey, const uint8_t* rgba,
                                   int w, int h) {
    if (!layerKey || !rgba || w <= 0 || h <= 0) return;
    std::lock_guard<std::mutex> lk(g_lpix_mu);
    MacapkLayerPixels* dst = nullptr;
    for (auto& e : g_lpix) if (e.layer == layerKey) { dst = &e; break; }
    if (!dst) for (auto& e : g_lpix) if (!e.layer) { dst = &e; break; }
    if (!dst) {           // evict the stalest entry
        dst = &g_lpix[0];
        for (auto& e : g_lpix) if (e.seq < dst->seq) dst = &e;
    }
    dst->layer = layerKey;
    dst->rgba.assign(rgba, rgba + (size_t)w * h * 4);
    dst->w = w; dst->h = h;
    dst->seq = ++g_lpix_seq;
}

// Blit the drawable's texture into shared storage, read it back, score + write
// the PNG. Takes ownership of the +1 reference. Runs on whatever thread finishes
// the capture -- the guest's render thread or the main thread -- never on a CA
// callback thread.
static void MacapkLayerCaptureFinish(CFTypeRef held, const char* tag,
                                     void* layerKey) {
    if (!held) return;
    if (!tag) tag = "layer";
    // No autorelease pool is guaranteed on a guest render thread, and every
    // Metal factory call below returns an autoreleased object.
    @autoreleasepool {
        id<CAMetalDrawable> d = (id<CAMetalDrawable>)held;
        id<MTLTexture> src = d.texture;
        const int W = src ? (int)src.width : 0, H = src ? (int)src.height : 0;
        if (W <= 0 || H <= 0 || W > 16384 || H > 16384) { MacapkLayerCaptureAbandon(tag); goto done; }
        if (!g_layercap_dev) g_layercap_dev = (id<MTLDevice>)CFRetain((CFTypeRef)src.device);
        if (!g_layercap_q)   g_layercap_q   = [g_layercap_dev newCommandQueue];
        if (!g_layercap_q) { MacapkLayerCaptureAbandon(tag); goto done; }
        if (!g_layercap_dst || (int)g_layercap_dst.width != W
            || (int)g_layercap_dst.height != H
            || g_layercap_dst.pixelFormat != src.pixelFormat) {
            [g_layercap_dst release];        // MRR: replacing a +1 we own
            MTLTextureDescriptor* td =
                [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:src.pixelFormat
                                                                  width:W height:H
                                                              mipmapped:NO];
            td.usage       = MTLTextureUsageShaderRead;
            td.storageMode = MTLStorageModeShared;   // Apple Silicon unified memory
            g_layercap_dst = [g_layercap_dev newTextureWithDescriptor:td];
        }
        if (!g_layercap_dst) { MacapkLayerCaptureAbandon(tag); goto done; }
        {
            id<MTLCommandBuffer> cb = [g_layercap_q commandBuffer];
            id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
            [bl copyFromTexture:src sourceSlice:0 sourceLevel:0
                   sourceOrigin:MTLOriginMake(0, 0, 0)
                     sourceSize:MTLSizeMake(W, H, 1)
                      toTexture:g_layercap_dst destinationSlice:0 destinationLevel:0
              destinationOrigin:MTLOriginMake(0, 0, 0)];
            [bl endEncoding];
            [cb commit];
            [cb waitUntilCompleted];
        }
        {
            const size_t stride = (size_t)W * 4;
            g_layercap_buf.resize(stride * (size_t)H);
            [g_layercap_dst getBytes:g_layercap_buf.data() bytesPerRow:stride
                         fromRegion:MTLRegionMake2D(0, 0, W, H) mipmapLevel:0];
            // Metal texture rows already run TOP-DOWN (unlike a GL readback), and
            // a CAMetalLayer drawable is BGRA8, so swap to RGBA for the PNG writer
            // (which takes RGBA and would otherwise print red as blue).
            // SelfCaptureWriteRGBA scores what it writes, so do NOT score here as
            // well -- doing both printed the same verdict line twice per capture
            // and paid for two full passes over a 2560x1440 frame.
            for (size_t i = 0; i + 3 < g_layercap_buf.size(); i += 4)
                std::swap(g_layercap_buf[i], g_layercap_buf[i + 2]);
            // v1.0.1036: park the pixels for the COMPOSITE source (keyed by
            // layer identity) before the per-layer PNG is written.
            MacapkStoreLayerPixels(layerKey, g_layercap_buf.data(), W, H);
            SelfCaptureWriteRGBA(g_layercap_buf.data(), W, H, /*bottom_up=*/false, tag);
        }
    done:
        CFRelease(held);   // drop our +1; the drawable returns to the pool
    }
}

// Called from the nextDrawable swizzle for EVERY vended drawable, on whatever
// thread the guest presents from. Two jobs, in this order: finish a readback
// left pending by the previous acquire (its drawable is now presented), then
// claim a new request if one is outstanding.
static void MacapkLayerCaptureOnDrawable(CAMetalLayer* layer, id<CAMetalDrawable> d) {
    if (!MacapkLayerCaptureEnabled() || !layer || !d) return;
    if (layer.framebufferOnly) {
        // Clear it on first sight so a LATER drawable is blittable; the one we
        // are holding right now was already allocated framebufferOnly.
        layer.framebufferOnly = NO;
        static std::atomic<bool> said{false};
        if (!said.exchange(true))
            std::fprintf(stderr, "[self-capture] layer: cleared framebufferOnly on %p "
                                 "so presented drawables are readable "
                                 "(MACAPK_SELF_CAPTURE_LAYER=0 to opt out)\n", (void*)layer);
        return;
    }
    CFTypeRef finish = nullptr;
    int slot;
    {
        std::lock_guard<std::mutex> lk(g_layercap_mu);
        slot = MacapkLayerSlotFor(layer);
        if (slot >= 0) { finish = g_layercap_pending[slot]; g_layercap_pending[slot] = nullptr; }
    }
    if (slot < 0) return;                            // a third layer: not instrumented
    const char* tag = MacapkLayerTagFor(layer);
    if (finish) MacapkLayerCaptureFinish(finish, tag, (void*)layer);   // outside the lock: blocks on the GPU
    if (SelfCaptureRequested(tag)) {
        CFTypeRef held = CFRetain((CFTypeRef)d);     // +1 keeps the content valid
        CFTypeRef stale = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_layercap_mu);
            stale = g_layercap_pending[slot];        // cannot happen, but never leak
            g_layercap_pending[slot] = held;
        }
        if (stale) CFRelease(stale);
    }
}

// ---------------------------------------------------------------------------
// v1.0.835: retained LAST-FRAME snapshots + the main-thread poll.
//
// The capture used to piggyback strictly on the NEXT present, which silently
// assumed every app repaints. An idle Canvas menu does not: Frozen Bubble
// produced `canvas 76549` on one run and NO source at all on another, same
// build, minutes apart, purely on whether a repaint happened inside the window.
// So the cheap sources keep their last frame and the poll serves it.
// ---------------------------------------------------------------------------
static std::mutex           g_snap_mu;
static std::vector<uint8_t> g_snap_canvas;         // ARGB8888 (BGRA in memory)
static int                  g_snap_canvas_w = 0, g_snap_canvas_h = 0;
// How many times the LEGACY present (MacapkSnapshotCanvas) has rewritten
// g_snap_canvas. Guarded by g_snap_mu. The IOSurface present reads it at the
// end of its diff and again under its write, and a difference means the shadow
// it planned against was replaced in between (macapk_write_set_after_foreign).
static uint64_t             g_snap_canvas_seq = 0;
static std::vector<uint8_t> g_snap_dc;             // RGBA, GL-origin (bottom-up)
static int                  g_snap_dc_w = 0, g_snap_dc_h = 0;

// Record the frame a present path just handed us. Cost is one memcpy of a frame
// the caller already owns (5.8 MB for a 808x1796 Canvas app) -- affordable
// because it replaces a capture that could not happen at all.
// The snapshot is also the IOSurface ring's shadow, so the ring (if one exists)
// is invalidated in the same critical section: see
// MacapkCanvasPoolForeignWriteLocked. The count bump is a plain increment under
// the lock already held; with the gate off the only other cost is the one
// atomic load that function starts with.
static void MacapkSnapshotCanvas(const uint32_t* argb, int w, int h) {
    if (!argb || w <= 0 || h <= 0) return;
    std::lock_guard<std::mutex> lk(g_snap_mu);
    g_snap_canvas.resize((size_t)w * h * 4);
    std::memcpy(g_snap_canvas.data(), argb, g_snap_canvas.size());
    g_snap_canvas_w = w; g_snap_canvas_h = h;
    ++g_snap_canvas_seq;
    MacapkCanvasPoolForeignWriteLocked();
}
static void MacapkSnapshotDc(const uint8_t* rgba, int w, int h) {
    if (!rgba || w <= 0 || h <= 0) return;
    std::lock_guard<std::mutex> lk(g_snap_mu);
    g_snap_dc.resize((size_t)w * h * 4);
    std::memcpy(g_snap_dc.data(), rgba, g_snap_dc.size());
    g_snap_dc_w = w; g_snap_dc_h = h;
}

// v1.0.997 (2026-08-18 lag lane): run the periodic frame score OFF the present
// thread. SelfCaptureScoreFrame is two full passes over the frame and measured
// 30-50 ms at 808x1796 (the [present-trace] snap+score max), proportionally
// more at game resolutions -- inline it was a dropped-frames hitch every
// SelfCaptureScorePeriod() presents, i.e. a visible periodic stutter during
// scroll/animation (canvas path: the app's main loop; dc path: the OS main
// thread the fps counter ticks on). Both present paths retain the frame in
// g_snap_* immediately before asking for a score, so the async block scores
// the snapshot: same [frame-score] line, same tag, same cadence -- every
// consumer (debug/rendercheck.sh, recap_*, sweep.sh) is unchanged. At worst a
// busy app has retained a newer frame by the time the block runs, which no
// consumer of the line distinguishes.
static void MacapkScoreSnapshotAsync(bool dc) {
    static dispatch_queue_t q = dispatch_queue_create("macapk.frame-score",
                                                      DISPATCH_QUEUE_SERIAL);
    dispatch_async(q, ^{
        std::vector<uint8_t> copy; int w = 0, h = 0;
        {
            std::lock_guard<std::mutex> lk(g_snap_mu);
            if (dc) { copy = g_snap_dc;     w = g_snap_dc_w;     h = g_snap_dc_h; }
            else    { copy = g_snap_canvas; w = g_snap_canvas_w; h = g_snap_canvas_h; }
        }
        if (copy.empty() || w <= 0 || h <= 0) return;
        const size_t stride = (size_t)w * 4;
        if (dc) {
            // g_snap_dc rows are GL-origin (bottom-up); score top-down so the
            // 4x4 grid's row 0 is the top of the frame (the chrome_only note
            // depends on it). Swap row pairs in the private copy.
            std::vector<uint8_t> row(stride);
            for (int y = 0; y < h / 2; ++y) {
                uint8_t* a = &copy[(size_t)y * stride];
                uint8_t* b = &copy[(size_t)(h - 1 - y) * stride];
                std::memcpy(row.data(), a, stride);
                std::memcpy(a, b, stride);
                std::memcpy(b, row.data(), stride);
            }
        }
        // Byte order matches the retained snapshot: canvas holds Android ARGB
        // ints (BGRA in memory), dc holds glReadPixels RGBA.
        SelfCaptureScoreFrame(copy.data(), w, h, (int)stride,
                              /*bgra=*/!dc, dc ? "dc" : "canvas");
    });
}

// ---------------------------------------------------------------------------
// v1.0.1036: the COMPOSITE capture source -- ONE image of what the user sees.
//
// Every other source is a single surface. This one walks the render view's
// REAL sublayer tree on the main thread and blends, in zPosition order (stable
// on ties, matching CA's sort) and at each layer's actual frame, honouring
// hidden / opacity / opaque:
//   * a CAMetalLayer's pixels come from the per-layer store MacapkLayerCapture-
//     Finish feeds (the retained-drawable readback -- the only handle a process
//     has on a presented CAMetalLayer's content, permission-free);
//   * a plain CALayer's contents CGImage (the canvas / GL-dialog overlays) is
//     drawn directly;
//   * a layer with only a backgroundColor is filled.
// The base is opaque black: the MTKView never presents its own drawable (see
// the angleLayer comment in drawInMTKView) and the letterbox container behind
// it is black.
//
// WHY NOT a Metal render pass over the layer tree ("what the compositor
// does"): the inputs would be identical -- a presented CAMetalLayer's pixels
// are only reachable through the same retained-drawable readback, and
// CARenderer cannot attach a tree that is already in the window server's
// context -- so a GPU pass adds plumbing, not fidelity, on a path that runs
// once per capture request.
//
// Runs on the MAIN thread only (layer-tree reads), scheduled by the poll below
// near the END of the capture window so the per-layer readbacks have already
// refreshed the store for this window. Entries persist across windows, so an
// idle guest still composites with its last-presented frame.
//
// KNOWN LIMITS (say them rather than hide them): a THIRD CAMetalLayer (the
// video self-test path can make one) has no slot in the readback machinery, so
// it composites from a stale or absent entry -- the skip is logged; and a
// metal layer that has never presented since launch has no pixels to blend
// (logged the same way).
// Draw an IOSurface layer's contents into the composite. The IOSurface present
// path (CocoaPresentFrameFrom) writes exactly the bytes the CGImage path hands
// CoreAnimation -- premultiplied, B,G,R,A in memory -- so they are wrapped in that
// CGImage's exact format (DeviceRGB, 32-bit little, premultiplied-first) and the
// composite reads the same as it did before the switch. Rows are
// IOSurfaceGetBytesPerRow apart, which is usually wider than width * 4.
static bool MacapkDrawIOSurfaceContents(CGContextRef ctx, CGRect rectPx, IOSurfaceRef s) {
    if (IOSurfaceLock(s, kIOSurfaceLockReadOnly, nullptr) != kIOReturnSuccess) return false;
    bool drew = false;
    const size_t w = IOSurfaceGetWidth(s), h = IOSurfaceGetHeight(s);
    const size_t bpr = IOSurfaceGetBytesPerRow(s);
    if (w > 0 && h > 0 && bpr >= w * 4) {
        CGDataProviderRef p = CGDataProviderCreateWithData(
            nullptr, IOSurfaceGetBaseAddress(s), bpr * h, nullptr);
        CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
        if (p && cs) {
            CGImageRef im = CGImageCreate(w, h, 8, 32, bpr, cs,
                kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst,
                p, nullptr, false, kCGRenderingIntentDefault);
            if (im) {
                CGContextDrawImage(ctx, rectPx, im);
                CGImageRelease(im);
                drew = true;
            }
        }
        if (cs) CGColorSpaceRelease(cs);
        if (p) CGDataProviderRelease(p);
    }
    IOSurfaceUnlock(s, kIOSurfaceLockReadOnly, nullptr);
    return drew;
}

static void MacapkBuildCompositeCapture() {
    @autoreleasepool {
        MTKView* cands[3] = { g_active_render_view, g_last_metal_view,
                              g_last_gl_metal_view };
        MTKView* view = nil;
        for (MTKView* v : cands) if (v && v.window) { view = v; break; }
        if (!view) for (MTKView* v : cands) if (v) { view = v; break; }
        if (!view || !view.layer) { MacapkLayerCaptureAbandon("composite"); return; }
        CALayer* root = view.layer;
        CGFloat scale = root.contentsScale;
        if (scale <= 0) scale = view.window ? view.window.backingScaleFactor : 2.0;
        const int W = (int)std::llround(root.bounds.size.width  * scale);
        const int H = (int)std::llround(root.bounds.size.height * scale);
        if (W <= 0 || H <= 0 || W > 16384 || H > 16384) {
            MacapkLayerCaptureAbandon("composite"); return;
        }
        const size_t stride = (size_t)W * 4;
        std::vector<uint8_t> out(stride * (size_t)H);
        CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
        if (!cs) { MacapkLayerCaptureAbandon("composite"); return; }
        CGContextRef ctx = CGBitmapContextCreate(
            out.data(), (size_t)W, (size_t)H, 8, stride, cs,
            kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
        if (!ctx) { CGColorSpaceRelease(cs); MacapkLayerCaptureAbandon("composite"); return; }
        CGContextSetRGBFillColor(ctx, 0, 0, 0, 1);
        CGContextFillRect(ctx, CGRectMake(0, 0, W, H));
        // Snapshot + stable-sort the sublayers by zPosition (CA's rule: sort by
        // zPosition, insertion order breaks ties).
        NSArray* subsArr = [[root.sublayers copy] autorelease];
        std::vector<CALayer*> subs;
        for (CALayer* l in subsArr) subs.push_back(l);
        std::stable_sort(subs.begin(), subs.end(),
                         [](CALayer* a, CALayer* b) { return a.zPosition < b.zPosition; });
        const bool flipped = view.isFlipped;
        const CGFloat rootHpt = root.bounds.size.height;
        char note[512]; size_t noteLen = 0; note[0] = 0;
        for (CALayer* l : subs) {
            if (l.hidden || l.opacity <= 0.001f) continue;
            CGRect fr = l.frame;
            // Root layer coords are bottom-left-origin unless the hosting view
            // is flipped; CG bitmap user space is bottom-left too, so only a
            // flipped view needs conversion (mirrors MacapkComputeContentBox).
            CGFloat yPt = flipped ? (rootHpt - fr.origin.y - fr.size.height)
                                  : fr.origin.y;
            CGRect rectPx = CGRectMake(fr.origin.x * scale, yPt * scale,
                                       fr.size.width * scale, fr.size.height * scale);
            if (rectPx.size.width <= 0 || rectPx.size.height <= 0) continue;
            CGContextSaveGState(ctx);
            CGContextClipToRect(ctx, rectPx);
            CGContextSetAlpha(ctx, l.opacity);
            CGContextSetInterpolationQuality(ctx, kCGInterpolationNone);
            bool drew = false;
            if ([l isKindOfClass:[CAMetalLayer class]]) {
                std::vector<uint8_t> px; int lw = 0, lh = 0;
                {
                    std::lock_guard<std::mutex> lk(g_lpix_mu);
                    for (auto& e : g_lpix)
                        if (e.layer == (void*)l) { px = e.rgba; lw = e.w; lh = e.h; break; }
                }
                if (!px.empty() && lw > 0 && lh > 0) {
                    // A GL-presented drawable's alpha is routinely 0 while the
                    // layer composites opaquely; honour layer.opaque exactly as
                    // the window server does.
                    const CGBitmapInfo info = l.opaque
                        ? (CGBitmapInfo)(kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big)
                        : (CGBitmapInfo)(kCGImageAlphaLast | kCGBitmapByteOrder32Big);
                    CGDataProviderRef p = CGDataProviderCreateWithData(
                        nullptr, px.data(), px.size(), nullptr);
                    if (p) {
                        CGImageRef im = CGImageCreate((size_t)lw, (size_t)lh, 8, 32,
                                                      (size_t)lw * 4, cs, info, p,
                                                      nullptr, false,
                                                      kCGRenderingIntentDefault);
                        if (im) {
                            CGContextDrawImage(ctx, rectPx, im);
                            CGImageRelease(im);
                            drew = true;
                        }
                        CGDataProviderRelease(p);
                    }
                }
            } else {
                id c = l.contents;
                if (c && CFGetTypeID((__bridge CFTypeRef)c) == CGImageGetTypeID()) {
                    CGContextDrawImage(ctx, rectPx, (CGImageRef)c);
                    drew = true;
                } else if (c && CFGetTypeID((__bridge CFTypeRef)c) == IOSurfaceGetTypeID()) {
                    // The canvas overlay under MACAPK_PRESENT_IOSURFACE: the same
                    // premultiplied BGRA bytes the CGImage path would have handed
                    // CoreAnimation, so wrap them in the same CGImage format and
                    // draw that. Read-only lock; the image dies before the unlock.
                    drew = MacapkDrawIOSurfaceContents(ctx, rectPx, (IOSurfaceRef)c);
                }
            }
            if (!drew && l.backgroundColor
                && CGColorGetAlpha(l.backgroundColor) > 0.001) {
                CGContextSetFillColorWithColor(ctx, l.backgroundColor);
                CGContextFillRect(ctx, rectPx);
                drew = true;
            }
            if (noteLen < sizeof(note) - 64) {
                noteLen += (size_t)std::snprintf(note + noteLen,
                    sizeof(note) - noteLen, "%s%s(z=%.1f%s)",
                    noteLen ? " " : "", object_getClassName(l),
                    (double)l.zPosition, drew ? "" : ",no-pixels");
            }
            CGContextRestoreGState(ctx);
        }
        CGContextRelease(ctx);
        CGColorSpaceRelease(cs);
        std::fprintf(stderr, "[self-capture] composite: %dx%d from %zu layers: %s\n",
                     W, H, subs.size(), note[0] ? note : "(none)");
        // CGBitmapContext memory rows already run top-down; the writer scores
        // and completes the marker bit exactly as for every other source.
        SelfCaptureWriteRGBA(out.data(), W, H, /*bottom_up=*/false, "composite");
    }
}

void SelfCapturePollFromMainThread() {
    // The layer readback can be stranded if the guest presented once and then
    // went quiet: nothing will call the swizzle again to finish it. Take it over
    // here. Safe on the main thread -- the blit reads a drawable WE hold a
    // reference to, and the guest cannot be rendering into an already-presented
    // drawable.
    CFTypeRef finish[2] = {nullptr, nullptr};
    {
        std::lock_guard<std::mutex> lk(g_layercap_mu);
        // Give the guest's own next acquire a few ticks to do it first (its
        // drawable is fresher); only then step in.
        static int idle_ticks[2] = {0, 0};
        for (int s = 0; s < 2; ++s) {
            if (!g_layercap_pending[s]) { idle_ticks[s] = 0; continue; }
            if (++idle_ticks[s] >= 3) {
                finish[s] = g_layercap_pending[s];
                g_layercap_pending[s] = nullptr;
                idle_ticks[s] = 0;
            }
        }
    }
    // The tag is identity-derived, so recover it from the slot's remembered layer
    // pointer rather than from the slot index.
    for (int s = 0; s < 2; ++s)
        if (finish[s])
            MacapkLayerCaptureFinish(finish[s],
                MacapkLayerTagFor((CAMetalLayer*)g_layercap_slot_layer[s]),
                g_layercap_slot_layer[s]);

    // Serve the retained snapshots. SelfCaptureRequested is what opens the
    // capture window, so calling it here is ALSO what lets a completely idle app
    // (no present of any kind) be captured at all.
    bool have_canvas, have_dc;
    {
        std::lock_guard<std::mutex> lk(g_snap_mu);
        have_canvas = !g_snap_canvas.empty();
        have_dc     = !g_snap_dc.empty();
    }
    if (have_canvas && SelfCaptureRequested("canvas")) {
        std::vector<uint8_t> copy;
        int w, h;
        {
            std::lock_guard<std::mutex> lk(g_snap_mu);
            copy = g_snap_canvas; w = g_snap_canvas_w; h = g_snap_canvas_h;
        }
        // Android ARGB_8888 ints are B,G,R,A in little-endian memory. Swap to
        // RGBA for the shared writer; rows are already top-down.
        for (size_t i = 0; i + 3 < copy.size(); i += 4) std::swap(copy[i], copy[i + 2]);
        SelfCaptureWriteRGBA(copy.data(), w, h, /*bottom_up=*/false, "canvas");
    }
    if (have_dc && SelfCaptureRequested("dc")) {
        std::vector<uint8_t> copy;
        int w, h;
        {
            std::lock_guard<std::mutex> lk(g_snap_mu);
            copy = g_snap_dc; w = g_snap_dc_w; h = g_snap_dc_h;
        }
        SelfCaptureWriteRGBA(copy.data(), w, h, /*bottom_up=*/true, "dc");
    }

    // v1.0.1036: schedule the COMPOSITE source near the END of the capture
    // window -- late on purpose, so the per-layer readbacks above (and on the
    // guest threads) have refreshed the per-layer pixel store for THIS window
    // first. 700ms of margin keeps the claim inside the window even at the
    // 2 Hz hidden-throttle poll cadence. SelfCaptureRequested claims the bit;
    // the build then runs synchronously here on the main thread, so the claim
    // can never hold the .done marker hostage.
    if (g_cap_window_live.load(std::memory_order_acquire)) {
        const uint64_t end = g_cap_window_end_ms.load(std::memory_order_acquire);
        const uint64_t now = (uint64_t)(CACurrentMediaTime() * 1000.0);
        if (now < end && end - now <= 700 && SelfCaptureRequested("composite"))
            MacapkBuildCompositeCapture();
    }
}

void SelfCaptureScoreFrame(const uint8_t* px, int width, int height, int stride,
                           bool bgra, const char* tag) {
    FrameStats s;
    if (!MacapkComputeFrameStats(px, width, height, stride, bgra, &s)) return;
    // note=chrome_only: the ONLY non-uniform pixels are our own status bar /
    // toolbar band. Without saying so, "the app drew nothing" reads as "the app
    // drew a little" -- the exact misread that scored Calculator as rendering.
    const char* note = "";
    if (s.cells > 0 && s.cells == s.top_row && s.coverage <= 12.0) note = " note=chrome_only";
    else if (s.cells <= 4 && s.coverage <= 6.0)                    note = " note=near_empty";
    std::fprintf(stderr,
        "[frame-score] src=%s verdict=%s distinct=%u modal=#%06x modal_share=%.1f%% "
        "coverage=%.1f%% edges=%.2f%% cells=%d/16 top_row=%d size=%dx%d%s\n",
        tag ? tag : "?", MacapkFrameVerdict(s), s.distinct, s.modal, s.modal_share,
        s.coverage, s.edges, s.cells, s.top_row, width, height, note);
    std::fflush(stderr);
}

// v1.0.674: Android launcher icons are full-bleed SQUARES, so handing one
// straight to setApplicationIconImage puts a hard-cornered tile in the Dock next
// to every native app's rounded one. Mask the app's icon to the macOS icon shape
// so it reads as a Mac app.
//
// Shape: a superellipse |x/a|^n + |y/a|^n = 1 with n = 5, NOT a plain rounded
// rect. macOS (Big Sur and later) uses a continuous ("squircle") corner, and n=5
// lands within half a pixel of it at icon scale: for the 1024-canvas grid the
// shipped shape is an 824 px square with a 185.4 px continuous corner, whose
// corner sits 505.9 px from center along the diagonal; the n=5 superellipse puts
// it at 507. A circular NSBezierPath roundedRect at the same radius visibly
// bulges by comparison.
//
// Applied ONLY to the app's own icon (MACAPK_APP_ICON). The Mac.apk fallback
// icon is already drawn to the macOS shape, and re-masking it would clip its
// own edge treatment. Escape hatches: MACAPK_ICON_ROUND=0 disables, and
// MACAPK_ICON_ROUND_N overrides the exponent (2 = ellipse, high = square).
static NSImage* MacapkRoundIcon(NSImage* src) {
    if (!src || !src.isValid) return src;
    const char* off = std::getenv("MACAPK_ICON_ROUND");
    if (off && std::strcmp(off, "0") == 0) return src;
    double n = 5.0;
    if (const char* e = std::getenv("MACAPK_ICON_ROUND_N")) {
        double v = std::atof(e);
        if (v >= 2.0 && v <= 40.0) n = v;
    }
    // Size: macOS's icon grid puts the icon BODY at 824 px inside a 1024 px
    // canvas (80.47%), and the Dock lays every tile out on that canvas. An
    // Android icon drawn edge-to-edge therefore renders ~24% larger than the
    // native icons beside it, which is what made it look oversized. Inset to the
    // same ratio so it sits on the standard grid. MACAPK_ICON_SCALE overrides
    // (1.0 = full bleed, the pre-v1.0.674 look).
    double bodyScale = 824.0 / 1024.0;
    if (const char* e = std::getenv("MACAPK_ICON_SCALE")) {
        double v = std::atof(e);
        if (v >= 0.5 && v <= 1.0) bodyScale = v;
    }
    NSSize sz = [src size];
    CGFloat side = MAX(sz.width, sz.height);
    if (side < 8.0) return src;   // too small to be a real icon; leave it alone
    const CGFloat body = side * bodyScale;
    const CGFloat a = body / 2.0;
    const double inv = 2.0 / n;   // |cos t|^(2/n) parametrization of the superellipse
    NSImage* out = [NSImage imageWithSize:NSMakeSize(side, side)
                                  flipped:NO
                           drawingHandler:^BOOL(NSRect dstRect) {
        NSBezierPath* mask = [NSBezierPath bezierPath];
        const int kSegments = 512;   // smooth well past 1024 px Dock renderings
        for (int i = 0; i < kSegments; ++i) {
            double t = 2.0 * M_PI * (double)i / (double)kSegments;
            double ct = cos(t), st = sin(t);
            double x = copysign(pow(fabs(ct), inv), ct) * a;
            double y = copysign(pow(fabs(st), inv), st) * a;
            NSPoint p = NSMakePoint(NSMidX(dstRect) + x, NSMidY(dstRect) + y);
            if (i == 0) [mask moveToPoint:p]; else [mask lineToPoint:p];
        }
        [mask closePath];
        [mask addClip];
        // Aspect-FILL the BODY square: a non-square source is scaled to cover
        // and center-cropped, so the mask never exposes empty corners.
        CGFloat scale = MAX(body / sz.width, body / sz.height);
        NSSize drawn = NSMakeSize(sz.width * scale, sz.height * scale);
        NSRect target = NSMakeRect(NSMidX(dstRect) - drawn.width / 2.0,
                                   NSMidY(dstRect) - drawn.height / 2.0,
                                   drawn.width, drawn.height);
        [src drawInRect:target
               fromRect:NSZeroRect
              operation:NSCompositingOperationSourceOver
               fraction:1.0];
        return YES;
    }];
    return (out && out.isValid) ? out : src;
}

// Defined after MacApkAppearanceObserver (an ObjC class must be declared at
// global scope, and this file's body is inside `namespace macapk`). Installs the
// KVO registration that makes light/dark follow macOS live.
static void install_appearance_observer();
static void macapk_notify_task_to_front();   // v1.0.1642, defined below
static void macapk_notify_app_activated();   // v1.0.1652 cross-app inbox backstop, defined below

// v1.0.1047: THE ONE WAY THIS RUNTIME LEAVES THE PROCESS. Never call
// System.exit(), Runtime.halt(), std::exit() or ::exit() on a quit path.
//
// THE BUG THIS EXISTS FOR (reproduced deterministically on Telegram, captured
// with `sample`; see the guest-side half in bionic_shim.cpp's
// __macapk_arm64___cxa_atexit): libc exit() runs __cxa_finalize, i.e. atexit
// handlers and C++ static destructors. An arm64 guest's static destructor then
// calls back into JNI, and HotSpot's JavaThread::block_if_vm_exited() blocks
// any JNI caller after the VM is marked exited -- permanently, by design
// ("the Threads_lock is never released so we will block here until the process
// dies", hotspot vmOperations.cpp). Via System.exit that lands as a two-thread
// deadlock: the AppKit main thread sits in VMThread::execute(VM_Exit) waiting
// for the VM thread, while the VM thread sits inside ::exit() -> guest
// destructor -> JNI -> blocked. The window closes and the runtime never dies.
//
// Runtime.halt() does NOT avoid it -- JVM_Halt takes the same vm_exit ->
// os::exit -> ::exit path. No Java-side call can terminate without
// __cxa_finalize, so the escape has to be native, and it has to be _exit().
//
// _exit() is also the AOSP-faithful answer rather than a shortcut: an Android
// app process is SIGKILLed by the ActivityManager/lmkd, so atexit handlers and
// static destructors never run there either, and onDestroy is documented as not
// guaranteed. What a device DOES guarantee is onPause/onStop before a visible
// activity goes away, which is why apps persist there -- so we drive exactly
// that much first, on the Looper thread, with a timeout.
//
// NOTE the one thing given up: angle_glue.cpp registers its blob-cache summary
// with std::atexit, so that single diagnostic line no longer prints on this
// path. The cache is inert on Metal anyway, and stdio is flushed below so
// nothing else is lost.
// MACAPK_QUIT_PROCESS_GROUP (default ON; =0 restores the pre-v1.0.1658 quit
// exactly). Two changes, both about what AOSP does when an app process dies:
//
//  1. The app's FORKED processes die with it. AOSP ProcessRecord.killLocked
//     (android14-release :1208-1213) kills the pid AND its process group --
//     killProcessGroup(uid, pid, SIGKILL), every pid in the app's cgroup. We
//     only _exit()ed ourselves, so a guest fork() child was re-parented to
//     launchd and kept running: Instagram's `.Instagram` children at ppid 1,
//     ~97% CPU, after every quit (lanes18 NN residual). See
//     include/macapk/app_process_group.h for the mapping (un-exec'd forks and
//     APK native executables die; system-role helpers such as the install
//     helper and the relaunch shell do not).
//  2. AppKit's own terminate: -- the Dock tile's "Quit", logout/restart, a
//     'quit' Apple event -- is routed here explicitly (observer below). With a
//     visible window AppKit already closes it first, so the close observer ran
//     the teardown anyway; with NO visible window (hidden, miniaturised to
//     nothing, a windowless phase) terminate: fell through to exit(0), i.e. the
//     __cxa_finalize path this function exists to avoid, and skipped
//     onPause/onStop.
//
// NOT done, deliberately, and measured TWICE (debug/closelat.sh, 2026-09-29):
// taking the window off screen BEFORE the teardown finishes -- either by
// ordering it out ahead of the blocking teardown, or by returning from the
// close at once and running the teardown on a background thread that _exit()s
// (a MACAPK_ASYNC_CLOSE arm, deleted in review). Both turn Crossy Road
// (Unity)'s 14-36 ms teardown into the full 2000 ms cap: Unity's onPause waits
// for its render thread, which cannot get a drawable once the layer is off
// screen, so the pause never finishes (no JNI_OnUnload, no aaudio stop,
// onStop/onDestroy never run and pause-time saves are lost). Flappy (AndEngine
// GL): close 44 ms with the window in place vs 291 ms without. The window stays
// on screen until the teardown returns; what removes the owner's freeze is that
// the teardown no longer resumes the activity beneath (MACAPK_CLOSE_REMOVES_TASK,
// Task.removeActivities), which took Solitaire 2021 ms -> 31 ms.

// Wall-clock ms, so a harness polling the window list / the pid from ANOTHER
// process can line its own timestamps up with the [quit] lines below
// (debug/closelat.sh).
static long long macapk_wall_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long) ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

static bool macapk_quit_process_group_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_QUIT_PROCESS_GROUP");
        return !(e && e[0] == '0');
    }();
    return on;
}

// The quit under way (macapk_terminate_now), for CocoaQuitBackstopExit: its
// start (wall ms; 0 = none) and exit code.
static std::atomic<long long> g_quit_t0{0};
static std::atomic<int> g_quit_code{0};
static bool macapk_quit_under_way() { return g_quit_t0.load() != 0; }

static void macapk_terminate_now(int code) {
    // Idempotent: AppKit can deliver a close and a terminate: for one quit.
    static std::atomic<bool> s_terminating{false};
    if (s_terminating.exchange(true)) return;

    const bool group = macapk_quit_process_group_on();
    const long long t0 = macapk_wall_ms();
    g_quit_code.store(code);
    g_quit_t0.store(t0);
    std::fprintf(stderr, "[quit] begin wall=%lld group=%d main=%d\n", t0, group ? 1 : 0,
                 [NSThread isMainThread] ? 1 : 0);
    std::fflush(stderr);

    JavaVM* vm = nullptr;
    jsize n_vms = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &n_vms) == JNI_OK && n_vms > 0 && vm) {
        JNIEnv* env = nullptr;
        jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
        if (r == JNI_EDETACHED)
            vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
        if (env) {
            // ActivityHost.macapkTerminate posts the teardown to the Android
            // main Looper (a DIFFERENT thread from this AppKit one) and waits
            // with this timeout, so a guest whose main thread is already wedged
            // costs us 2s rather than hanging the quit.
            if (jclass ah = env->FindClass("com/macapk/loader/ActivityHost")) {
                if (jmethodID mt = env->GetStaticMethodID(
                        ah, "macapkTerminate", "(J)Z")) {
                    env->CallStaticBooleanMethod(ah, mt, (jlong) 2000);
                }
            }
            // Any pending exception dies with the process, but clear it so the
            // flush below is not made from a JNI-error state.
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    std::fprintf(stderr, "[quit] +%lldms android teardown returned\n", macapk_wall_ms() - t0);
    if (group) {
        std::vector<pid_t> v = KillAppProcessGroup(getpid(), SIGKILL);
        std::fprintf(stderr, "[quit] +%lldms killed %zu app-owned forked process(es)",
                     macapk_wall_ms() - t0, v.size());
        for (pid_t p : v) std::fprintf(stderr, " %d", (int) p);
        std::fprintf(stderr, "\n");
    }
    std::fprintf(stderr, "[quit] +%lldms _exit(%d)\n", macapk_wall_ms() - t0, code);
    std::fflush(nullptr);
    ::_exit(code);
}

// ActivityHost.macapkTerminate (MACAPK_QUIT_PUMPS_RENDER): while this, the
// host render thread, waits for the Android main thread's teardown, keep it
// doing what AOSP's GLThread would still be doing -- one display tick at a time.
bool CocoaHostRenderTick() {
    if (![NSThread isMainThread]) return false;
    if (g_host_draw_depth > 0) return false;
    MTKView* v = g_active_render_view;
    if (!v || !v.window) return false;
    @autoreleasepool { [v draw]; }
    return true;
}

// ActivityHost.macapkTerminate (MACAPK_QUIT_HIDE_AFTER_PAUSE). The first call
// orders every visible window out; EVERY call (the wait makes one per 8 ms slice
// while the teardown continues) flushes the implicit Core Animation transaction.
// Why the repeated flush: a window's ordering reaches the window server through
// that transaction, which AppKit commits only when this thread returns to its
// run loop -- and it will not until the teardown has finished. A single flush
// right after orderOut: is NOT enough, and neither is spinning the run loop in
// a private mode; flushing on each slice is (measured with a standalone probe,
// 2026-09-30: window blocked 2 s after orderOut: -> still on screen at 2 s with
// one flush; gone 13-22 ms after it with a flush every 8 ms, from a plain block
// AND from inside NSWindowWillCloseNotification). OpenTTD with one flush: win_ms
// 2025 although "[quit] +window off screen" was logged at +32 ms.
// animationBehavior None: orderOut: otherwise fades a titled window for ~260 ms
// (same probe); a closed window leaves at once.
bool CocoaHostHideWindowsForQuit() {
    if (![NSThread isMainThread]) return false;
    static bool s_hidden = false;
    if (!s_hidden) {
        s_hidden = true;
        int n = 0;
        for (NSWindow* w in [NSApp windows]) {
            if ([w isVisible]) {
                [w setAnimationBehavior:NSWindowAnimationBehaviorNone];
                [w orderOut:nil];
                n++;
            }
        }
        std::fprintf(stderr, "[quit] +window off screen (%d window(s)) wall=%lld\n", n, macapk_wall_ms());
        std::fflush(stderr);
    }
    [CATransaction flush];
    return true;
}

// ActivityHost's quit backstop (QUIT_BACKSTOP_GRACE_MS): the teardown wait
// pumped a render tick on the host thread and that tick has not returned well
// past the cap, so the tail of macapk_terminate_now (which runs on that thread)
// never will. Do it from here: the same process-group kill and _exit(code). The
// plain 2 s await never needed this -- a Java wait always comes back -- but a
// tick runs the guest's renderer synchronously, and nothing can preempt it.
void CocoaQuitBackstopExit() {
    const long long t0 = g_quit_t0.load();
    if (t0 == 0) return;   // no quit under way: not ours to end
    const int code = g_quit_code.load();
    std::fprintf(stderr, "[quit] +%lldms android teardown abandoned by the backstop"
                 " (the host thread is still inside a render tick)\n", macapk_wall_ms() - t0);
    if (macapk_quit_process_group_on()) {
        std::vector<pid_t> v = KillAppProcessGroup(getpid(), SIGKILL);
        std::fprintf(stderr, "[quit] +%lldms killed %zu app-owned forked process(es)",
                     macapk_wall_ms() - t0, v.size());
        for (pid_t p : v) std::fprintf(stderr, " %d", (int) p);
        std::fprintf(stderr, "\n");
    }
    std::fprintf(stderr, "[quit] +%lldms _exit(%d)\n", macapk_wall_ms() - t0, code);
    std::fflush(nullptr);
    ::_exit(code);
}

// Debug-only quit gestures for the MACAPK_INJECT_FILE driver (TouchDispatcher
// verbs "close" / "quit" / "terminate"), so the close latency can be MEASURED
// by a harness with no Accessibility grant (debug/closelat.sh). Each one takes
// the REAL path its user gesture takes:
//   close     -> -[NSWindow performClose:] == clicking the red button
//   quit      -> what the app menu's Cmd-Q item (macapkQuit:) calls
//   terminate -> -[NSApplication terminate:] == Dock "Quit" / a 'quit' AE
void CocoaDebugQuitGesture(const char* kind) {
    std::string k = kind ? kind : "";
    std::fprintf(stderr, "[quit] gesture %s wall=%lld\n", k.c_str(), macapk_wall_ms());
    std::fflush(stderr);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (k == "close") {
            NSWindow* w = g_placeholder_window;
            if (!w || ![w isVisible]) {
                for (NSWindow* c in [NSApp windows]) if ([c isVisible]) { w = c; break; }
            }
            if (w) [w performClose:nil];
        } else if (k == "quit") {
            std::fprintf(stderr, "[macapk] Quit requested; exiting cleanly\n");
            macapk_terminate_now(0);
        } else if (k == "terminate") {
            [NSApp terminate:nil];
        }
    });
}

// Guest-initiated exit, optionally with a relaunch. See cocoa_bridge.h.
//
// WHY A DETACHED HELPER RATHER THAN A DIRECT SPAWN: the app stub holds a
// single-instance flock on run/<dirName>.pid for as long as the runtime lives
// (launcher/AppStubMain.swift step 5), and a second stub that finds it held
// merely activates the running instance and exits. So the relaunch must happen
// AFTER this process is gone. /bin/sh waits for our pid to disappear (the
// kernel releases the flock with it), then reopens the bundle through
// LaunchServices -- the same path a Dock click takes, so the Dock identity,
// the stub's own setup and its run log all behave as on a normal launch. Only
// /bin/sh and /usr/bin/open are needed, both on a Dock-launched PATH.
//
// The bundle is derived from argv[0]: the stub execs the runtime through a link
// inside <App>.app/Contents/MacOS/ precisely so that path identifies the app.
// A bare (harness) run has no bundle; it re-executes its own argv instead.
void CocoaExitProcess(int code, const char* relaunch_intents_file) {
    if (relaunch_intents_file && relaunch_intents_file[0]) {
        std::string argv0;
        char*** argvp = _NSGetArgv();
        int* argcp = _NSGetArgc();
        int argc = argcp ? *argcp : 0;
        char** argv = argvp ? *argvp : nullptr;
        if (argv && argc > 0 && argv[0]) argv0 = argv[0];
        std::string script;
        std::string wait = "while /bin/kill -0 " + std::to_string((int) getpid())
                         + " 2>/dev/null; do /bin/sleep 0.1; done; ";
        auto shq = [](const std::string& in) {           // single-quote for sh
            std::string o = "'";
            for (char c : in) { if (c == '\'') o += "'\\''"; else o += c; }
            return o + "'";
        };
        std::string env = "MACAPK_RESTART_INTENTS=" + std::string(relaunch_intents_file);
        size_t k = argv0.find("/Contents/MacOS/");
        if (k != std::string::npos) {
            std::string bundle = argv0.substr(0, k);
            script = wait + "exec /usr/bin/open -n -a " + shq(bundle) + " --env " + shq(env);
        } else if (argv && argc > 0) {
            script = wait + "export " + shq(env) + "; exec";
            for (int i = 0; i < argc; i++) script += " " + shq(argv[i] ? argv[i] : "");
        }
        if (!script.empty()) {
            std::fprintf(stderr, "[macapk] exit(%d): relaunching after this process ends: %s\n",
                         code, script.c_str());
            const char* sh_argv[] = { "/bin/sh", "-c", script.c_str(), nullptr };
            posix_spawnattr_t attr;
            posix_spawnattr_init(&attr);
            // Our own session, so the helper is not torn down with our process
            // group and does not inherit the app's foreground/Dock identity.
            posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSID);
            pid_t child = 0;
            int rc = posix_spawn(&child, "/bin/sh", nullptr, &attr,
                                 const_cast<char* const*>(sh_argv), *_NSGetEnviron());
            posix_spawnattr_destroy(&attr);
            if (rc != 0) std::fprintf(stderr, "[macapk] relaunch helper spawn failed: %d\n", rc);
        } else {
            std::fprintf(stderr, "[macapk] exit(%d): no argv to relaunch from; exiting only\n", code);
        }
    }
    macapk_terminate_now(code);
}

static void MacapkSeedPanelHzOnMain();   // defined next to g_panel_hz below

// ---------------------------------------------------------------------------
// Host URL intake (MACAPK_URL_SCHEMES, default ON): the macOS end of an
// Android BROWSABLE deep link / OAuth redirect.
//
// On a device a browser that meets a link whose scheme an app claims hands it to
// that app as ACTION_VIEW + CATEGORY_BROWSABLE + FLAG_ACTIVITY_NEW_TASK
// (Chromium ExternalNavigationHandler.sanitizeQueryIntentActivitiesIntent and
// the targetIntent.addFlags(NEW_TASK) before startActivity). On macOS the same
// hand-off is LaunchServices: the app's Info.plist CFBundleURLTypes claims the
// scheme (HostUrlIntake.java keeps that key in step with the manifest's
// BROWSABLE filters), and LS sends the claiming app a kAEGetURL
// ('GURL', kInternetEventClass) Apple Event carrying the URL -- the event
// NSApplication documents for application:openURLs:, which we cannot use
// because this runtime installs no NSApplicationDelegate.
//
// The handler must be installed BEFORE [NSApp finishLaunching]: a URL that
// cold-launches the app is queued by LS and dispatched as soon as the app
// finishes launching, so a handler installed later misses exactly the cold
// case. The URL is queued here (the JVM may not exist yet) and Java pulls it
// with NativeCocoa.takeHostUrls(); a running JVM is told to drain at once.
// =0: no handler is installed, which is the pre-change behaviour (AppKit drops
// an unhandled GURL).
static std::mutex g_host_url_mu;
static std::vector<std::string> g_host_urls;
static void macapk_notify_host_url();   // defined below, next to the activation hook

void CocoaTakeHostUrls(std::vector<std::string>& out) {
    std::lock_guard<std::mutex> lk(g_host_url_mu);
    out.swap(g_host_urls);
    g_host_urls.clear();
}

void CocoaQueueHostUrl(const char* url) {
    if (!url || !url[0]) return;
    {
        std::lock_guard<std::mutex> lk(g_host_url_mu);
        if (g_host_urls.size() < 64) g_host_urls.emplace_back(url);
    }
    macapk_notify_host_url();
}

}  // namespace macapk -- the ObjC handler below must be at global scope
   // (same reason as the appearance observer further down); reopened after it.

@interface MacapkHostUrlHandler : NSObject
- (void)handleGetURLEvent:(NSAppleEventDescriptor*)event
           withReplyEvent:(NSAppleEventDescriptor*)reply;
@end

@implementation MacapkHostUrlHandler
- (void)handleGetURLEvent:(NSAppleEventDescriptor*)event
           withReplyEvent:(NSAppleEventDescriptor*)reply {
    (void)reply;
    NSString* s = [[event paramDescriptorForKeyword:keyDirectObject] stringValue];
    if (s.length == 0) return;
    const char* u = [s UTF8String];
    if (!u) return;
    // Log the scheme and the size only: an OAuth redirect's query carries a
    // one-time authorization code, which has no business in a run log.
    NSRange colon = [s rangeOfString:@":"];
    NSString* scheme = colon.location != NSNotFound
        ? [s substringToIndex:colon.location] : @"?";
    std::fprintf(stderr, "[url] GURL received scheme=%s (%zu bytes) -> queued for the app\n",
                 [scheme UTF8String], std::strlen(u));
    std::fflush(stderr);
    {
        std::lock_guard<std::mutex> lk(macapk::g_host_url_mu);
        // Bounded: a page that loops a redirect must not grow this forever.
        if (macapk::g_host_urls.size() < 64) macapk::g_host_urls.emplace_back(u);
    }
    macapk::macapk_notify_host_url();
}
@end

namespace macapk {   // reopened after MacapkHostUrlHandler

static MacapkHostUrlHandler* g_host_url_handler = nil;

static void macapk_install_host_url_handler() {
    if (!macapk_gate_value_on(std::getenv("MACAPK_URL_SCHEMES"))) return;
    if (g_host_url_handler) return;
    g_host_url_handler = [[MacapkHostUrlHandler alloc] init];
    [[NSAppleEventManager sharedAppleEventManager]
        setEventHandler:g_host_url_handler
            andSelector:@selector(handleGetURLEvent:withReplyEvent:)
          forEventClass:kInternetEventClass
             andEventID:kAEGetURL];
}

bool CocoaInit() {
    if (g_initialized.load()) return true;
    @autoreleasepool {
        // v1.0.835: install the -[CAMetalLayer nextDrawable] swizzle HERE, before
        // any layer exists. It used to be installed from CocoaEnsureNativeEglLayer,
        // i.e. only once a guest asked for a native-EGL layer -- but the swizzle is
        // now the universal capture tap and has to be in place for the framework's
        // own angleLayer, for libunity's own layer, and (the case that motivated
        // this) for a guest that creates its layer through a path we do not drive.
        // Idempotent; the acquire policy it installs is unchanged.
        macapk_install_drawable_swizzle();
        // ---- AppKit SAVED-STATE RESTORATION: OFF. ----
        //
        // A Mac.apk app creates exactly ONE window programmatically and has no
        // restorable AppKit state at all, so window restoration has nothing to
        // restore -- but it has something to ASK. When the bundle carries a
        // crash history (and an Android app that is force-quit, jetsam-killed,
        // or `kill -9`ed by a harness accumulates one fast), AppKit's
        // kAEOpenApplication handling runs
        // `-[NSPersistentUIRestorer promptToIgnorePersistentStateWithCrashHistory:]`,
        // which puts up a MODAL "reopen windows?" alert from inside
        // `_suppressFinishLaunchingFromEventHandlersWhilePerformingBlock:`.
        //
        // In a headless or unattended launch nobody can click it and the app
        // hangs forever with a window up and no content. MEASURED 2026-09-22
        // via the real per-app stub: Mindustry, Unciv, GD Lite, Vector Pinball,
        // Subway Surf, Hill Climb, Minecraft, Instagram, Thunderbird and
        // Efteling all stopped dead there -- main thread parked in
        // `[NSAlert runModal]` under `_handleAEOpenEvent:`. It reproduces only
        // through the stub, because the stub execs through a symlink INSIDE the
        // .app so AppKit adopts the bundle identity that owns the saved state
        // and the crash history; the raw dev binary has no bundle and never
        // reaches the prompt.
        //
        // `registerDefaults` (not `setObject:`) so nothing is written to the
        // user's defaults -- it supplies a default value for this process only.
        // MACAPK_APPKIT_RESTORE=1 puts the stock behaviour back for an A/B.
        if (!(std::getenv("MACAPK_APPKIT_RESTORE")
              && std::getenv("MACAPK_APPKIT_RESTORE")[0] == '1')) {
            [[NSUserDefaults standardUserDefaults] registerDefaults:
                @{@"ApplePersistenceIgnoreState": @YES}];
        }
        g_app = [NSApplication sharedApplication];
        // v1.0.1644: publish the panel refresh rate NOW, on the main thread, before any
        // Java runs. See MacapkSeedPanelHzOnMain for the deadlock this closes.
        MacapkSeedPanelHzOnMain();
        // v1.0.128: when launched by the Swift launcher app (or any
        // parent that sets MACAPK_HIDE_DOCK_ICON=1), use Accessory
        // policy so the runtime does NOT add its own Dock icon /
        // Cmd-Tab entry. Without this the user sees TWO Dock items
        // ("Mac.apk" launcher + "macapk-runtime" child) which is
        // confusing -- only the launcher should appear there.
        // Standalone CLI invocation still gets Regular policy so the
        // window is reachable via Cmd-Tab for debugging.
        // v1.0.339: when the launcher hands us the running app's icon
        // (MACAPK_APP_ICON) it has also hidden ITS OWN Dock icon, so the
        // runtime takes Regular policy and shows the APP's icon in the Dock
        // (and Cmd-Tab) -- clicking that Dock icon then focuses the game
        // window, which belongs to this process. Without an app icon we keep
        // the v1.0.128 behavior: Accessory when the launcher set
        // MACAPK_HIDE_DOCK_ICON (avoid a duplicate generic icon), Regular for
        // a standalone CLI run.
        // Resolve the Dock icon to show for the running app, in priority
        // order, so the Dock is NEVER blank (v1.0.340):
        //   1. MACAPK_APP_ICON     -- the launched app's own icon (a drawer
        //                             launch where the app ships an icon).
        //   2. MACAPK_DEFAULT_ICON -- the Mac.apk fallback the launcher
        //                             passes so an app with no icon still
        //                             gets the Mac.apk tile instead of blank.
        //   3. macapk_default_icon.png sitting next to the runtime binary --
        //                             covers a standalone CLI run with no
        //                             launcher env (resolved exe-relative).
        // Whenever ANY icon resolves we take Regular policy + set it as the
        // application icon, so the running app always has a Dock entry. Only
        // if none resolve do we fall back to the legacy hide-dock / regular
        // behavior keyed on MACAPK_HIDE_DOCK_ICON.
        NSImage* dockIcon = nil;
        const char* appIconPath = std::getenv("MACAPK_APP_ICON");
        if (appIconPath && appIconPath[0]) {
            dockIcon = [[NSImage alloc] initWithContentsOfFile:
                            [NSString stringWithUTF8String:appIconPath]];
            // The APK's icon is a square; give it the macOS icon shape so the
            // Dock tile matches every native app next to it (see MacapkRoundIcon).
            if (dockIcon && dockIcon.isValid) dockIcon = MacapkRoundIcon(dockIcon);
        }
        if (!(dockIcon && dockIcon.isValid)) {
            const char* defIconPath = std::getenv("MACAPK_DEFAULT_ICON");
            if (defIconPath && defIconPath[0]) {
                dockIcon = [[NSImage alloc] initWithContentsOfFile:
                                [NSString stringWithUTF8String:defIconPath]];
            }
        }
        if (!(dockIcon && dockIcon.isValid)) {
            // Executable-relative fallback: <dir-of-macapk-runtime>/macapk_default_icon.png.
            // Both build_launcher.sh (Resources/) and runtime/scripts/build.sh
            // (build/bin/) drop the icon next to the binary, so a bare CLI
            // invocation gets the Mac.apk icon too.
            char exePath[PATH_MAX];
            uint32_t sz = sizeof(exePath);
            if (_NSGetExecutablePath(exePath, &sz) == 0) {
                NSString* dir = [[NSString stringWithUTF8String:exePath]
                                    stringByDeletingLastPathComponent];
                NSString* cand = [dir stringByAppendingPathComponent:
                                     @"macapk_default_icon.png"];
                if ([[NSFileManager defaultManager] fileExistsAtPath:cand]) {
                    dockIcon = [[NSImage alloc] initWithContentsOfFile:cand];
                }
            }
        }
        const char* hideDock = std::getenv("MACAPK_HIDE_DOCK_ICON");
        if (dockIcon && dockIcon.isValid) {
            [g_app setActivationPolicy:NSApplicationActivationPolicyRegular];
            [g_app setApplicationIconImage:dockIcon];
        } else if (hideDock && std::strcmp(hideDock, "1") == 0) {
            [g_app setActivationPolicy:NSApplicationActivationPolicyAccessory];
        } else {
            [g_app setActivationPolicy:NSApplicationActivationPolicyRegular];
        }
        // The window title shows the app's display name (MACAPK_APP_LABEL,
        // the same label the launcher's app drawer shows) instead of the
        // package name / "Mac.apk". Used for the placeholder below and as the
        // fallback the Java Activity.activeWindowTitle() also honors.
        const char* appLabel = std::getenv("MACAPK_APP_LABEL");
        NSString* titleStr = (appLabel && appLabel[0])
            ? [NSString stringWithUTF8String:appLabel] : @"Mac.apk";
        // We don't install an NSApplicationDelegate; the runtime drives
        // its own event loop in CocoaRunLoop(). Without a delegate,
        // Cocoa would not auto-terminate when the last window closes
        // (the default for `applicationShouldTerminateAfterLastWindow-
        // Closed` is NO). We install an explicit window-close observer
        // below (v1.0.43) so X-ing the runtime window exits cleanly,
        // letting the Swift launcher's process.terminationHandler fire
        // and flip the UI back to Idle.
        // Before finishLaunching: see "Host URL intake" above.
        macapk_install_host_url_handler();
        [g_app finishLaunching];
        // Give the runtime window a real menu bar (app menu + View > Rotate/Cmd+R)
        // instead of AppKit's default empty one.
        install_menu_bar(titleStr);

        // v1.0.128: clamp content size to the host screen's visible
        // frame so a launcher request bigger than the user's display
        // (e.g. 1440x2560 retina-aspect on a 1280-tall laptop screen)
        // doesn't clip the title bar / Dock area. Visible frame
        // excludes the menu bar / Dock so the resulting window fits
        // entirely on-screen.
        NSRect screenVis = [[NSScreen mainScreen] visibleFrame];
        // v1.0.503: ASPECT-PRESERVING clamp (was per-dimension, which could
        // square an over-tall request). Shrink the app's w:h to fit the visible
        // frame, never enlarge (scale capped at 1.0). For the --orientation auto
        // path the size already fits the visible frame, so this is a no-op there
        // and the boot/sweep window size is unchanged. The aspect-correct window
        // also matches the contentAspectRatio lock set below.
        // v1.0.1637: fit the CONTENT budget (visible frame minus the title bar),
        // not the visible frame itself; see content_budget_for_visible.
        const NSSize screenBudget = content_budget_for_visible(screenVis);
        double clampSc = MIN(MIN((double)screenBudget.width  / g_phone_w,
                                 (double)screenBudget.height / g_phone_h), 1.0);
        int cw = (int)(g_phone_w * clampSc);
        int ch = (int)(g_phone_h * clampSc);
        // v1.0.1637: keep the canvas equal to what the window will SHOW. An
        // explicit launcher/CLI size bigger than the screen used to be shrunk
        // here for the window only, so g_phone_w/h (what Boot seeds the
        // display metrics from) stayed at the request and the first
        // surfaceChanged then disagreed with the launch Configuration.
        if (display_model_on() && clampSc < 1.0) {
            // Under the window policy the window box shrinks with the app box
            // (the next reshape would otherwise restore the unclamped window).
            if (g_win_w > 0 && g_win_h > 0) {
                g_win_w = (int)(g_win_w * clampSc);
                g_win_h = (int)(g_win_h * clampSc);
            }
            g_phone_w = cw; g_phone_h = ch;
            g_wp.live.win_w_pt = g_win_w; g_wp.live.win_h_pt = g_win_h;
            g_wp.live.app_w_pt = g_phone_w; g_wp.live.app_h_pt = g_phone_h;
        }
        // Open a placeholder window immediately so boot is visible.
        NSRect frame = NSMakeRect(0, 0, cw, ch);
        // v1.0.128: add Resizable so the user can drag the window
        // larger / smaller AND so Cocoa's full-screen button (green
        // traffic-light) is enabled. Without Resizable the
        // toggleFullScreen action is a no-op.
        NSWindowStyleMask style = kMacapkWindowStyle;
        g_placeholder_window =
            [[NSWindow alloc] initWithContentRect:frame
                                         styleMask:style
                                           backing:NSBackingStoreBuffered
                                             defer:NO];
        [g_placeholder_window setTitle:titleStr];
        [g_placeholder_window setBackgroundColor:[NSColor blackColor]];
        // FLAG_SECURE pushed before the window existed (see
        // macapk_apply_secure_sharing_on_create): no-op unless Java asked.
        macapk_apply_secure_sharing_on_create(g_placeholder_window);

        // v1.0.129: install the letterbox container as the window's
        // contentView. Black bg, autoresizes with the window. Any
        // MTKView later added via CocoaSetWindowContentView becomes a
        // centered native-size subview of THIS container, not the
        // direct contentView of the window.
        // v1.0.503: the container is a MacApkLetterboxContainer subclass that
        // aspect-fits its single render subview on every resize (defined later
        // in this TU; instantiated by name like MacApkMetalView). Falls back to
        // a plain NSView if the class can't be resolved (never expected).
        Class containerCls = NSClassFromString(@"MacApkLetterboxContainer");
        NSRect containerFrame = [[g_placeholder_window contentView] bounds];
        g_letterbox_container = containerCls
            ? [[containerCls alloc] initWithFrame:containerFrame]
            : [[NSView alloc] initWithFrame:containerFrame];
        [g_letterbox_container setWantsLayer:YES];
        g_letterbox_container.layer.backgroundColor =
            [[NSColor blackColor] CGColor];
        // masksToBounds prevents an MTKView LARGER than the container
        // (e.g. user shrunk the window below native size) from
        // drawing past the container's edge.
        g_letterbox_container.layer.masksToBounds = YES;
        g_letterbox_container.autoresizingMask =
            NSViewWidthSizable | NSViewHeightSizable;
        [g_placeholder_window setContentView:g_letterbox_container];

        // v1.0.503: lock the window to the app's aspect ratio so a corner-drag
        // can only resize along w:h and never distorts the content; the
        // container aspect-fits the render view into the result and the existing
        // surfaceChanged re-render chain re-renders at the new pixel size (live,
        // stretch-free, no fixed resolution). Also floor the content size to an
        // aspect-correct minimum (smaller side ~240pt, clamped to the initial
        // size) so the surface can't collapse to a sliver.
        // v1.0.1649 (phase 2a): the lock is applied by apply_window_locks_main.
        // Legacy windows, and every window while MACAPK_WINDOW_FREE_RESIZE is
        // off (the default), keep exactly the v1.0.503 lock described above;
        // with it on, the policy's lock is per class.
        apply_window_locks_main(g_win_w > 0 ? g_win_w : g_phone_w,
                                g_win_h > 0 ? g_win_h : g_phone_h, cw, ch);

        // Center on the visible frame (excludes menu bar + Dock) rather than
        // -[NSWindow center] (which sits "somewhat above center" and, combined with
        // the startup reshape below, left the window drifting off to the left). The
        // reshape path re-centers the same way once orientation resolves; this
        // covers apps that never reshape. The user can move the window afterward.
        {
            NSRect fr0 = [g_placeholder_window frame];
            ++g_own_frame_change;
            [g_placeholder_window setFrameOrigin:NSMakePoint(
                screenVis.origin.x + (screenVis.size.width  - fr0.size.width)  / 2.0,
                screenVis.origin.y + (screenVis.size.height - fr0.size.height) / 2.0)];
            --g_own_frame_change;
        }
        if (MacApkHarnessNoActivate()) [g_placeholder_window orderBack:nil];
        else {
            [g_placeholder_window makeKeyAndOrderFront:nil];
            [g_app activateIgnoringOtherApps:YES];
            MacApkFocusLogAfterShow("placeholder shown +1.5s");
        }
        // T0 marker for debug/boottime.sh. The placeholder is the first thing
        // the user sees, and "a window is up" is a different fact from "the
        // app drew something" -- measuring them apart is the whole point of
        // the boot-time budget, so the boundary has to be in the log.
        std::fprintf(stderr, "[macapk] window: placeholder shown\n");
        // Correct the position once AppKit has applied the aspect-ratio fit-shrink
        // (see recenter_placeholder_next_cycle) so the window opens centered.
        recenter_placeholder_next_cycle();
        // Native title-bar Back chevron (leading edge), the macOS-native
        // replacement for the Android on-screen nav glyph. Same KEYCODE_BACK ->
        // onBackPressed path as Esc / Cmd+[.
        InstallBackTitlebarAccessory(g_placeholder_window);
        // v1.0.1649 (phase 2a): the window delegate, BEFORE the optional
        // fullscreen toggle below, so a --fullscreen launch goes through the
        // same fullscreen transition as the green button.
        install_window_delegate(g_placeholder_window);
        // v1.0.128: optional --fullscreen (or MACAPK_FULLSCREEN=1)
        // toggles the window into native macOS full-screen on launch.
        // Honors NSWindowStyleMaskResizable above so toggleFullScreen
        // actually engages.
        const char* fs = std::getenv("MACAPK_FULLSCREEN");
        if (fs && std::strcmp(fs, "1") == 0) {
            [g_placeholder_window toggleFullScreen:nil];
        }

        // v1.0.43: exit-on-window-close hook. The Swift launcher
        // detects "runtime no longer running" only by child-process
        // termination (Process.terminationHandler), not by polling
        // window state -- so without this, X-ing the runtime window
        // leaves the runtime alive headless and the launcher's UI
        // remains stuck on "Running" / Stop.
        //
        // v1.0.766: this used to call std::exit(0) directly, with a comment
        // claiming that "triggers JVM shutdown hooks ... so save-prefs / log
        // flush / etc. still get a chance to run". That was FALSE, and it is
        // the kind of falsehood the render sweep can never catch: any app
        // persisting state from a shutdown hook lost it every time the user
        // closed the window. Measured with an embedded-JVM probe
        // (debug/jvm_shutdown_hook_probe/), which registers a Java shutdown
        // hook and then exits three ways:
        //     exit(0)          -> hook does NOT run
        //     DestroyJavaVM()  -> hook runs
        //     raise(SIGTERM)   -> hook runs ONLY WITHOUT -Xrs (see below)
        // C exit() runs atexit handlers and static destructors; HotSpot does
        // not hang Java's shutdown sequence off those.
        //
        // CORRECTED 2026-09-24: this used to go on "The launcher's Stop button
        // sends SIGTERM, so THAT path was always fine". It is not. The probe
        // (hooktest.c) originally booted its JVM without -Xrs, but the
        // runtime passes -Xrs (jvm_loader.cpp); the probe now boots with -Xrs
        // by default ("noxrs" is the old control). Under ReduceSignalUsage
        // HotSpot refuses to register SHUTDOWN1/2/3 = HUP/INT/TERM
        // (os/posix/jvm_posix.cpp), so java.lang.Terminator never installs its
        // TERM handler (the JDK says so: with -Xrs "the user is responsible for
        // ensuring that shutdown hooks are run by calling System.exit()").
        // Nothing in runtime/src installs one either, so SIGTERM takes the
        // default action and runs NO Java shutdown hooks and no deleteOnExit.
        // That is what leaked every dynamic-DEX temp jar
        // (docs/dynamic-dex-cache_2026-09-24.md). On this runtime Java hooks
        // (and deleteOnExit) run only on DestroyJavaVM or on a System.exit /
        // Runtime.exit made by our own framework code (Boot.installShutdownDump
        // relies on the latter). An APP's System.exit / Runtime.exit / halt is
        // re-pointed by the converter (com.macapk.dex.GuestRepoint) to
        // MacApkProcessExit, which ends in macapk_terminate_now -> ::_exit.
        // Window close, Cmd-Q (both macapk_terminate_now -> ::_exit), SIGTERM
        // and SIGKILL run none, and those are how users actually quit.
        //
        // v1.0.766 (SUPERSEDED by v1.0.1047, see the observer body below): ask Java to shut down.
        // System.exit() runs the registered hooks and then halts, so the
        // process still dies promptly and the launcher still sees child-process
        // termination. Falls back to the old raw exit if the VM is unreachable,
        // so closing the window can never leave the runtime alive headless --
        // which is the whole point of this observer.
        //
        // Observing g_placeholder_window specifically: cocos2d-x v2
        // and AndEngine both REPLACE the placeholder's content view
        // on the SAME NSWindow (see GLSurfaceView's macapkSurface-
        // Changed bridge), so the observer survives the swap. If a
        // future engine creates its own separate NSWindow and
        // discards the placeholder, this hook will not fire for that
        // window; revisit then.
        [[NSNotificationCenter defaultCenter]
            addObserverForName:NSWindowWillCloseNotification
                        object:g_placeholder_window
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification* /*note*/) {
                std::fprintf(stderr,
                             "[macapk] runtime window closed by user;"
                             " exiting cleanly\n");
                std::fflush(stderr);
                // v1.0.1047: was System.exit(0) with a std::exit(0) fallback.
                // BOTH deadlocked the runtime on close for any app with a guest
                // static destructor that touches JNI (measured on Telegram) --
                // see macapk_terminate_now above for the two-thread deadlock and
                // why the escape has to be native. macapk_terminate_now runs the
                // AOSP-guaranteed part of teardown (onPause/onStop/onDestroy, on
                // the Looper thread, with a timeout) and then _exit()s.
                macapk_terminate_now(0);
            }];

        // MACAPK_QUIT_PROCESS_GROUP (see macapk_terminate_now): AppKit's own
        // terminate: -- the Dock tile's "Quit", logout/restart, a 'quit' Apple
        // event -- used to end in NSApplication's exit(0). That is the
        // __cxa_finalize path macapk_terminate_now exists to avoid (the
        // Telegram JNI-from-a-static-destructor deadlock), and it skipped the
        // AOSP-guaranteed onPause/onStop, so an app quit from the Dock lost
        // what an app closed from its window kept. queue:nil runs the block
        // synchronously on the posting (main) thread, and macapk_terminate_now
        // never returns, so exit(0) is never reached.
        if (macapk_quit_process_group_on()) {
            [[NSNotificationCenter defaultCenter]
                addObserverForName:NSApplicationWillTerminateNotification
                            object:nil
                             queue:nil
                        usingBlock:^(NSNotification* /*note*/) {
                    std::fprintf(stderr, "[macapk] application terminate "
                                 "(Dock Quit / logout / quit event); exiting cleanly\n");
                    std::fflush(stderr);
                    macapk_terminate_now(0);
                }];
        }

        // MACAPK_PAUSE_WHEN_HIDDEN (see g_window_hidden above): three
        // observers on the SAME single runtime window feed one shared
        // re-evaluation. cocos2d-x v2 / AndEngine REPLACE the placeholder's
        // content view on this same NSWindow rather than creating a new one
        // (see the close-observer comment above), so registering once here
        // covers the whole app lifetime -- no re-attach needed on Activity
        // recreate / content-view swap. occlusionState alone is Apple's
        // documented power-saving signal (see the citation on
        // macapk_update_window_hidden_state) but miniaturize/deminiaturize
        // are observed too, belt-and-suspenders, in case occlusionState is
        // not reliably re-posted across a miniaturize on some macOS version --
        // the handler is idempotent so a redundant firing is a no-op.
        [[NSNotificationCenter defaultCenter]
            addObserverForName:NSWindowDidChangeOcclusionStateNotification
                        object:g_placeholder_window
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification* /*note*/) {
                macapk_update_window_hidden_state("occlusion");
            }];
        [[NSNotificationCenter defaultCenter]
            addObserverForName:NSWindowDidMiniaturizeNotification
                        object:g_placeholder_window
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification* /*note*/) {
                macapk_update_window_hidden_state("miniaturize");
            }];
        [[NSNotificationCenter defaultCenter]
            addObserverForName:NSWindowDidDeminiaturizeNotification
                        object:g_placeholder_window
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification* /*note*/) {
                macapk_update_window_hidden_state("deminiaturize");
            }];

        // Display-sleep recovery -- see macapk_kick_render_views above for the
        // measured bug and the mechanism. NOTE the notification center: this is
        // an NSWorkspace notification and is NOT posted to defaultCenter.
        [[[NSWorkspace sharedWorkspace] notificationCenter]
            addObserverForName:NSWorkspaceScreensDidWakeNotification
                        object:nil
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification* /*note*/) {
                macapk_kick_render_views("screens-did-wake");
            }];
        // Secondary trigger, belt-and-suspenders: a display waking can also
        // arrive as a screen-parameters change (resolution/arrangement settle),
        // and on some configurations that is the notification that actually
        // lands. The kick is idempotent so both firing costs one extra frame.
        [[NSNotificationCenter defaultCenter]
            addObserverForName:NSApplicationDidChangeScreenParametersNotification
                        object:nil
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification* /*note*/) {
                macapk_kick_render_views("screen-parameters-changed");
            }];

        // v1.0.1047: live Light/Dark. Deferred to a helper because the observer
        // is an ObjC class, which may only be declared at global scope, and this
        // file's body sits inside `namespace macapk` -- so the class is defined
        // further down and this call is forward-declared above.
        install_appearance_observer();

        // v1.0.1642: an Android task the runtime sent to the back (see
        // CocoaMoveTaskToBack) comes forward when the app is unhidden. AppKit
        // unhides a hidden app on a Dock click and on activation, and posts
        // this for every unhide, including one after a user's own Cmd+H;
        // the Java side ignores those.
        // [focus] trace (MacApkFocusLog): activation and key-window changes.
        for (NSNotificationName name in @[ NSApplicationDidBecomeActiveNotification,
                                          NSApplicationDidResignActiveNotification,
                                          NSWindowDidBecomeKeyNotification,
                                          NSWindowDidResignKeyNotification ]) {
            [[NSNotificationCenter defaultCenter]
                addObserverForName:name
                            object:nil
                             queue:[NSOperationQueue mainQueue]
                        usingBlock:^(NSNotification* note) {
                    MacApkFocusLog([[note name] UTF8String]);
                }];
        }
        [[NSNotificationCenter defaultCenter]
            addObserverForName:NSApplicationDidUnhideNotification
                        object:nil
                         queue:[NSOperationQueue mainQueue]
                    usingBlock:^(NSNotification* /*note*/) {
                macapk_notify_task_to_front();
            }];
        // Cross-app intent inbox backstop (MACAPK_XAPP_RECEIVE, default ON;
        // docs/cross-app-launch-and-implicit-start-design_2026-09-26.md section 7).
        // A sender's `open -a` and the stub's already-running path ACTIVATE a
        // running app, but only a HIDDEN one gets the unhide above, so without
        // this a visible target whose doorbell was skipped would hold the
        // envelope until the next hide/unhide. =0: the observer is never added.
        if (macapk_gate_value_on(std::getenv("MACAPK_XAPP_RECEIVE"))) {
            [[NSNotificationCenter defaultCenter]
                addObserverForName:NSApplicationDidBecomeActiveNotification
                            object:nil
                             queue:[NSOperationQueue mainQueue]
                        usingBlock:^(NSNotification* /*note*/) {
                    macapk_notify_app_activated();
                }];
        }
    }
    g_initialized.store(true);
    // Launched straight into the foreground (the Apps drawer, the Dock, a launcher
    // that hands over focus): WindowServer made this process frontmost the moment
    // it connected, so the app is already active here, before main() goes on to
    // boot the JVM with nobody reading events for ~1.5 s. WindowServer's
    // activation hand-off (resume + show requests on our connection) then went
    // unanswered, it stopped delivering input -- clicks never reached the window,
    // RL Sideswipe's "Log in" did nothing -- filled the connection's datagram
    // buffer and marked the app "not responding" ~15 s later, for good (owner,
    // 2026-10-06; WindowServer: "Clearing datagram buffer ... connectionIsUnresponsive:
    // 1"). Switching to another app and back recovered it. Answer the hand-off
    // now: pump the queue briefly while it is fresh. MACAPK_EARLY_ACTIVE_PUMP=0
    // skips it.
    if ([g_app isActive]) {
        const char* e = std::getenv("MACAPK_EARLY_ACTIVE_PUMP");
        if (!(e && std::strcmp(e, "0") == 0)) {
            MacApkFocusLog("active at init: pumping the activation hand-off");
            CocoaPumpEvents(0.15);
        }
    }
    return true;
}

void* CocoaGetPlaceholderWindow() {
    return (__bridge void*)g_placeholder_window;
}

// v1.0.690: NSWorkspace host-URL open (implicit ACTION_VIEW resolution target).
bool CocoaOpenHostUrl(const char* url) {
    if (!url || !url[0]) return false;
    @autoreleasepool {
        NSString* s = [NSString stringWithUTF8String:url];
        if (!s) return false;
        NSURL* u = [NSURL URLWithString:s];
        if (!u) {
            // Percent-encode as a fallback for URIs with unescaped characters.
            NSString* enc = [s stringByAddingPercentEncodingWithAllowedCharacters:
                             [NSCharacterSet URLQueryAllowedCharacterSet]];
            u = enc ? [NSURL URLWithString:enc] : nil;
        }
        if (!u) return false;
        return [[NSWorkspace sharedWorkspace] openURL:u] ? true : false;
    }
}

// Host URL intake (MACAPK_URL_SCHEMES): which applications LaunchServices ALREADY
// lists as handlers of `scheme` -- the question a device's PackageManager answers
// with a chooser when a second app claims a scheme. HostUrlIntake asks before it
// writes CFBundleURLTypes, so registering an Android app never silently takes a
// scheme over from a native macOS app (Passwords owns otpauth, Podcasts pcast,
// VLC rtsp/rtmp/...): a claim can change which bundle a scheme opens (which
// claimant LaunchServices picks is its own decision, not a rule to rely on: observed
// both ways on 2026-09-30, a rival registered afterwards did not move the owner Mac's
// default in one run while the newest registration won in clean scratch runs), so an
// unconditional claim risks a sticky hijack.
// NSWorkspace URLsForApplicationsToOpenURL: (macOS 12+, the non-deprecated
// replacement for LSCopyApplicationURLsForURL) probed with `<scheme>:/probe`.
// Returns false when the query itself is unavailable (unknown, NOT "no handler").
bool CocoaUrlSchemeHandlers(const char* scheme, std::vector<std::string>& out) {
    out.clear();
    if (!scheme || !scheme[0]) return false;
    @autoreleasepool {
        NSString* s = [NSString stringWithFormat:@"%s:/probe", scheme];
        NSURL* u = [NSURL URLWithString:s];
        if (!u) return false;
        NSArray<NSURL*>* apps = [[NSWorkspace sharedWorkspace] URLsForApplicationsToOpenURL:u];
        for (NSURL* a in apps) {
            const char* p = a.path.fileSystemRepresentation;
            if (p) out.emplace_back(p);
        }
        return true;
    }
}

// Host URL intake, redirect routing: the tab's OPENER receives a shared scheme's
// redirect. PROVEN for fbconnect ONLY (Facebook Login's Custom Tab redirect); Firebase's
// genericidp / recaptcha hand-off is an intent: URI, which this does not touch (OPEN,
// docs/signin-provider-layer_2026-09-30.md). On a device the browser routes the
// redirect itself -- Chrome's Custom Tab hands a redirect intent the client app can
// handle back to THAT client -- but LaunchServices binds a scheme to one bundle,
// chosen by LaunchServices and not by the tab's opener, so with several Mac.apk apps
// bundling the Facebook SDK the redirect reached a bundle that never opened the tab
// (Duolingo's fbconnect redirect launched Subway Surf).
// -[NSWorkspace setDefaultApplicationAtURL:toOpenURLsWithScheme:
// completionHandler:] (macOS 12) rebinds it; measured on this Mac 2026-09-30 with
// two scratch bundles: silent, completion in 1-3 ms on a background queue, the
// handler flips both ways. Not awaited: the redirect arrives seconds later, after
// the user signs in, and the caller may be the UI thread.
int CocoaSetUrlSchemeDefault(const char* bundlePath, const char* scheme) {
    if (!bundlePath || !bundlePath[0] || !scheme || !scheme[0]) return -1;
    @autoreleasepool {
        NSString* bp = [NSString stringWithUTF8String:bundlePath];
        NSString* sc = [NSString stringWithUTF8String:scheme];
        if (!bp || !sc) return -1;
        NSURL* app = [NSURL fileURLWithPath:bp isDirectory:YES];
        NSURL* probe = [NSURL URLWithString:[NSString stringWithFormat:@"%@:/probe", sc]];
        if (!app || !probe) return -1;
        NSWorkspace* ws = [NSWorkspace sharedWorkspace];
        NSURL* cur = [ws URLForApplicationToOpenURL:probe];
        if (cur && [cur.path.stringByStandardizingPath
                        isEqualToString:app.path.stringByStandardizingPath]) {
            return 0;
        }
        std::string s(scheme);
        [ws setDefaultApplicationAtURL:app toOpenURLsWithScheme:sc
                     completionHandler:^(NSError* err) {
            if (err) {
                fprintf(stderr, "[W] Mac.apk.URL: [URL] default handler for %s not changed: %s\n",
                        s.c_str(), err.localizedDescription.UTF8String ?: "?");
            }
        }];
        return 1;
    }
}

// v1.0.1034: the app's AOSP pre-O max aspect ratio (0 = uncapped), published
// from Boot once the manifest has been parsed.
//
// WHY THE WINDOW AND NOT JUST THE LETTERBOX. ViewCanvasDriver already
// letterboxes a capped app correctly, and that is what a phone must do: its
// screen is a fixed shape, so the remainder is painted as background. A Mac
// window has no fixed shape, and the auto canvas deliberately picks a 2.22:1
// phone profile (PHONE_ASPECT below), so a 1.86-capped app was handed a window
// it could never fill and the difference showed up as a permanent black band
// (owner, 2026-08-23, on Flappy Bird -- targetSdk 15).
//
// Shaping the WINDOW to the cap gives the app exactly the same content rect it
// would have had inside the letterbox -- it cannot tell a 1.86 display from a
// 1.86 letterbox on a 2.22 display -- and stops us painting dead space. The
// letterbox stays in place and simply becomes a no-op, because
// macapkLetterboxHeight returns the height unchanged once the surface already
// satisfies the cap (its 0.005f tolerance).
//
// Applied HERE rather than by clamping once from Java, because every reshape
// has to respect it: CocoaApplyRequestedOrientation recomputes the auto max
// whenever the size is not explicit, which would silently undo a one-shot
// clamp the first time an app called setRequestedOrientation.
static float g_max_aspect = 0.0f;   // long/short, e.g. 1.86

// Both are defined further down; forward-declared so the cap can reshape a
// live window from here.
static inline void dispatch_main_async_void(void (^block)(void));
// Where a reshaped window goes: centred on the screen (boot, Cmd+R, a request,
// everything before phase 3), or grown / shrunk about its own centre and kept
// on the visible frame (an activity transition, so a back is the exact inverse
// of the start that grew the window, and a window the user moved stays there).
enum class ReshapeAnchor { kCenterOnScreen, kKeepCenter };
static void reshape_placeholder_to_globals_main(ReshapeAnchor anchor = ReshapeAnchor::kCenterOnScreen);

// Escape hatch for A/B, default ON. MACAPK_FIT_WINDOW_TO_ASPECT=0 restores the
// pre-v1.0.1034 behaviour (full-height window + a visible letterbox band).
static bool fit_window_to_aspect_enabled() {
    static int cached = -1;
    if (cached < 0) {
        const char* e = std::getenv("MACAPK_FIT_WINDOW_TO_ASPECT");
        cached = (e && e[0] == '0') ? 0 : 1;
    }
    return cached != 0;
}

// Reshape the canvas so long/short <= g_max_aspect. Mirrors
// AppCompatAspectRatioPolicy.applyAspectRatio's tolerance and rounding, so the
// window and Boot.macapkLetterboxHeight agree to the pixel.
//
// v1.0.1038: GROW THE SHORT SIDE rather than shrink the long one, whenever the
// display has room. v1.0.1034 shrank the long side, which satisfies the cap but
// makes the window strictly SMALLER -- and the owner hit that immediately:
// Flappy Bird (the only portrait pre-O app in the corpus; hcr181 is the other
// capped app and runs landscape) opened at 836x1555 instead of 836x1764 and had
// to be resized by hand on every launch. Owner, 2026-08-23: "all windows should
// default to as big as possible".
//
// Growing is also the MORE faithful answer, not a concession. AOSP letterboxes
// because a phone's screen is a fixed shape it cannot change. Our "display" is a
// canvas we pick, and the auto portrait profile picks 2.22:1 (PHONE_ASPECT)
// arbitrarily -- so handing a 1.86-capped app a 1.86 canvas means it is not
// letterboxed at ALL, exactly as it would not be on a 16:9 phone. That is what
// the owner remembered as the pre-v1.0.874 behaviour.
//
// The portrait auto-canvas already fills the screen's visible HEIGHT, so the
// spare room is in WIDTH; we only grow when the result genuinely fits
// visibleFrame, and fall back to the v1.0.1034 shrink when it does not (a narrow
// display, or a landscape app whose long side is already the screen width).
static void clamp_phone_to_max_aspect() {
    if (!fit_window_to_aspect_enabled()) return;
    if (g_max_aspect < 1.0f || g_phone_w <= 0 || g_phone_h <= 0) return;
    const int lo = MIN(g_phone_w, g_phone_h);
    const int hi = MAX(g_phone_w, g_phone_h);
    const float containing = (float)hi / (float)lo;
    if (containing - g_max_aspect <= 0.005f) return;
    const int oldW = g_phone_w, oldH = g_phone_h;
    const bool portrait = (g_phone_h > g_phone_w);

    // NSScreen returns an AUTORELEASED object and this runs on a JVM thread
    // whose implicit pool never drains (the v1.0.952 CocoaPresentBitmap rule),
    // so scope a pool and carry only plain geometry out.
    double visW = 0.0, visH = 0.0;
    @autoreleasepool {
        NSScreen* screen = [NSScreen mainScreen];
        if (screen) {
            // The content budget, not the raw visible frame: "genuinely fits"
            // has to include the title bar (content_budget_for_visible).
            const NSSize b = content_budget_for_visible([screen visibleFrame]);
            visW = b.width;
            visH = b.height;
        }
    }

    const int grown = (int)((hi / g_max_aspect) + 0.5f);
    bool grew = false;
    if (grown > lo && visW > 0.0 && visH > 0.0) {
        const double needW = portrait ? (double)grown : (double)hi;
        const double needH = portrait ? (double)hi    : (double)grown;
        if (needW <= visW && needH <= visH) {
            if (portrait) g_phone_w = grown; else g_phone_h = grown;
            grew = true;
        }
    }
    if (!grew) {
        const int capped = (int)((lo * g_max_aspect) + 0.5f);
        if (portrait) g_phone_h = capped; else g_phone_w = capped;
    }
    std::fprintf(stderr,
        "[macapk] max-aspect %.2f: canvas %dx%d -> %dx%d (%s; the letterbox band "
        "is what this removes)\n",
        g_max_aspect, oldW, oldH, g_phone_w, g_phone_h,
        grew ? "grew the short side to keep full size"
             : "shrank the long side, no room to grow");
}

// AOSP's STARTING WINDOW, in the one place we can honour it.
//
// Real Android does not show black while an app boots: WindowManager puts up a
// starting window painted with the activity's own `android:windowBackground`
// (and, since API 31, the SplashScreen icon over it) BEFORE the app has drawn a
// single frame -- see PhoneWindowManager.addSplashScreen /
// SplashscreenContentDrawer, which read exactly that attribute. Our placeholder
// existed but was hardcoded black, so every Mac.apk boot showed a black
// rectangle where a device shows the app's brand colour.
//
// The colour arrives from the Java side the moment the activity's theme is
// anchored (ActivityHost, right after setTheme and before onCreate), which is
// the same point in the lifecycle AOSP has it. Alpha is ignored: a starting
// window is opaque by definition, and a non-opaque windowBackground means the
// activity is translucent, which the caller filters out.
//
// Dismissal is implicit and correct: the app's own render view is added as a
// subview of this container, so the first real frame covers it.
void CocoaSetStartingWindowColor(int argb) {
    const CGFloat r = ((argb >> 16) & 0xFF) / 255.0;
    const CGFloat g = ((argb >>  8) & 0xFF) / 255.0;
    const CGFloat b = ( argb        & 0xFF) / 255.0;
    dispatch_main_async_void(^{
        NSColor* c = [NSColor colorWithSRGBRed:r green:g blue:b alpha:1.0];
        if (g_placeholder_window) [g_placeholder_window setBackgroundColor:c];
        // Remembered (this file is not ARC: retain the CGColor) so the
        // container can go back to it when letterbox bars go away.
        if (g_starting_bg) CGColorRelease(g_starting_bg);
        g_starting_bg = CGColorRetain([c CGColor]);
        if (g_letterbox_container) {
            // Phase 2a: where the policy shows letterbox bars they are AOSP's
            // letterbox background (black), not the starting window.
            bool bars = false;
            if (g_wp.active && [[g_letterbox_container subviews] count] > 0)
                (void) render_frame_in_container(g_letterbox_container.bounds.size, &bars);
            [g_letterbox_container setWantsLayer:YES];
            g_letterbox_container.layer.backgroundColor =
                bars ? [[NSColor blackColor] CGColor] : [c CGColor];
        }
    });
}

// Re-apply the current g_phone_w/h to the placeholder window. The window-first
// boot order needs it: the window is opened from a BEST-EFFORT orientation read
// of the APK zip, and for a split bundle (.apkm/.xapk) that read legitimately
// comes back AUTO, so the authoritative sizing after the unpack has to be able
// to correct the window that is already on screen.
void CocoaReshapePlaceholder() {
    dispatch_main_async_void(^{ reshape_placeholder_to_globals_main(); });
}

void CocoaSetMaxAspectRatio(float ratio) {
    g_max_aspect = ratio;
    if (g_wp.active) {
        // The policy applied the activity's effective max aspect (PAU16 /
        // PPU16 / PAI16, activity level included) BEFORE the window opened;
        // re-clamping here from Boot's application-level reading is the late
        // second reshape the policy exists to remove.
        std::fprintf(stderr, "[macapk] max-aspect %.2f from Boot: not re-applied, the window "
                     "policy already shaped the window (its max aspect: %g)\n",
                     ratio, g_wp.live.decision.max_aspect);
        return;
    }
    clamp_phone_to_max_aspect();
    dispatch_main_async_void(^{ reshape_placeholder_to_globals_main(); });
}

void CocoaSetPhoneSize(int width, int height) {
    if (width  > 0) g_phone_w = width;
    if (height > 0) g_phone_h = height;
    if (width > 0 && height > 0) g_explicit_size = true;
    // First caller is always main.cpp's boot sizing (both setters run before
    // the JVM starts), so the seed captures the pre-app user/base rotation.
    if (!g_user_orient_seeded && width > 0 && height > 0) {
        g_user_orient_seeded = true;
        g_user_landscape = width > height;
    }
}

void CocoaSetPhoneSizeMax(bool landscape) {
    // "Auto" canvas = the biggest size this display supports, shaped to
    // the RESOLVED orientation (v1.0.345). Lives here in the runtime --
    // not the launcher -- because with --orientation auto only the
    // runtime knows the manifest's answer; an explicit size passed by
    // the launcher would PIN the shape and override a landscape
    // manifest (the v1.0.344 regression: Among Us / Plague Inc booted
    // in portrait windows).
    //
    // visibleFrame is in POINTS and excludes the menu bar / Dock (the
    // same rect CocoaInit clamps to). The Metal drawable is already
    // backing-scale multiplied, so a visibleFrame-sized window renders
    // at the panel's native pixel resolution. Landscape takes the
    // visible frame itself; portrait keeps w <= h by rotating the
    // frame's aspect and scaling it down to fit (the CocoaInit clamp is
    // per-dimension, not aspect-preserving, so an over-tall request
    // would come back square).
    // Same seed-once as CocoaSetPhoneSize: the first call is main.cpp's boot
    // sizing, so this records the user/base rotation. The later caller
    // (CocoaApplyRequestedOrientation) finds the flag already set and cannot
    // overwrite the user rotation with an APP's preference.
    if (!g_user_orient_seeded) {
        g_user_orient_seeded = true;
        g_user_landscape = landscape;
    }
    // [NSScreen mainScreen] returns an AUTORELEASED object and this runs on
    // a JVM thread whose implicit pool never drains (the v1.0.952
    // CocoaPresentBitmap rule). Scope a pool around the AppKit queries and
    // carry only plain geometry out.
    NSRect vis = NSZeroRect;
    bool have_screen = false;
    @autoreleasepool {
        NSScreen* screen = [NSScreen mainScreen];
        if (screen) {
            have_screen = true;
            vis = [screen visibleFrame];
        }
    }
    if (!have_screen) {
        // No display resolvable (headless edge case): keep the legacy
        // phone defaults so behavior matches the pre-Auto-max runtime.
        if (landscape) { g_phone_w = 640; g_phone_h = 360; }
        else           { g_phone_w = 360; g_phone_h = 640; }
        return;
    }
    // v1.0.1637: the budget is the visible frame MINUS the title bar. Taking
    // the visible frame itself made the window frame 32pt too tall; AppKit
    // clamped it and the aspect lock shrank it, so the canvas below never
    // matched the drawable (418x930 reported, 404x898 drawn). Falsifier: the
    // "auto canvas" line x2 equals the first surfaceChanged size (+/-1).
    const NSSize budget = content_budget_for_visible(vis);
    double visW = budget.width, visH = budget.height;
    if (vis.size.width < 64 || vis.size.height < 64) {
        if (landscape) { g_phone_w = 640; g_phone_h = 360; }
        else           { g_phone_w = 360; g_phone_h = 640; }
        return;
    }
    if (landscape) {
        double candW = MAX(visW, visH);
        double candH = MIN(visW, visH);
        double scale = MIN(MIN(visW / candW, visH / candH), 1.0);
        g_phone_w = (int)(candW * scale);
        g_phone_h = (int)(candH * scale);
    } else {
        // Portrait: shape the canvas to a real PHONE aspect (9:20, the
        // reference device's 1080x2400) instead of the host SCREEN's
        // aspect. Android portrait UIs are designed for a tall-narrow
        // phone; using the Mac screen's wider aspect (~0.57 w/h)
        // stretched every proportion-sensitive layout -- e.g. the
        // Calculator keypad's 0dp-square-cell buttons rendered as wide
        // OVALS instead of circles (cellW=W/4 grew larger than the row
        // height). With phone proportions the cells come out square,
        // matching the emulator reference. Largest 9:20 rect that fits
        // the visible frame, height-maxed. Generic: every portrait app
        // now gets phone proportions, not the desktop's.
        const double PHONE_ASPECT = 1080.0 / 2400.0; // w/h = 0.45 (the DISPLAY)
        // v1.0.1646: the canvas is the APP'S window, so shape it like an app's
        // window on that phone, not like the whole display. On a device an
        // ordinary app gets the display minus the status bar (24dp) and the
        // navigation bar (48dp): AOSP PhoneWindow/DecorView consume both
        // (PhoneWindow.java:146-153, DecorView.java:1213-1258, android14), so a
        // 404x898dp phone lays its apps out in 404x826dp. We draw no bars since
        // v1.0.1627, and that decision handed their 72dp to the app instead:
        // every portrait app was ~9% too tall, and the owner saw it in the
        // calculator ("buttons look elongated vertically": keys 174x197 vs the
        // emulator's 174x176, 2026-09-25). We keep the full screen HEIGHT (owner:
        // windows "as big as possible") and take the phone app's proportions by
        // making the window wider -- apps see a slightly wider phone, as on the
        // larger Pixels. MACAPK_PHONE_APP_WINDOW=0 restores the display shape;
        // with MACAPK_SYSTEM_BARS=1 the bars are drawn inside, so the display
        // shape is right again.
        static const bool app_window = []{
            const char* sb = std::getenv("MACAPK_SYSTEM_BARS");
            const char* pa = std::getenv("MACAPK_PHONE_APP_WINDOW");
            if (sb && std::strcmp(sb, "1") == 0) return false;
            if (pa && std::strcmp(pa, "0") == 0) return false;
            return true;
        }();
        const double shape = app_window
            ? (404.0 / (404.0 / PHONE_ASPECT - 24.0 - 48.0))   // w/h = 404/826 = 0.4891
            : PHONE_ASPECT;
        double h = visH;
        double w = h * shape;
        if (w > visW) { w = visW; h = w / shape; }
        g_phone_w = (int)w;
        g_phone_h = (int)h;
    }
    clamp_phone_to_max_aspect();
    std::fprintf(stderr,
        "[macapk] auto canvas: %s max %dx%d (visible frame %.0fx%.0f, "
        "content budget %.0fx%.0f)\n",
        landscape ? "landscape" : "portrait", g_phone_w, g_phone_h,
        vis.size.width, vis.size.height, visW, visH);
}

// The window's real backing scale factor. Touch coordinate mapping used a hardcoded 2.0f, which is
// correct only on a retina display: on a 1x external monitor every touch landed at twice its true
// coordinate. Every RENDER path here already reads window.backingScaleFactor; only input did not.
float CocoaBackingScale() {
    __block float bs = 2.0f;
    void (^body)(void) = ^{
        // Prefer the window the app is actually on (it may be on a second display with a different
        // scale); fall back to the main screen, then to the historical 2.0 default.
        NSWindow* w = [NSApp keyWindow];
        if (!w) w = [NSApp mainWindow];
        if (!w) { NSArray<NSWindow*>* ws = [NSApp windows]; if (ws.count > 0) w = ws.firstObject; }
        if (w) bs = (float) w.backingScaleFactor;
        else { NSScreen* scr = [NSScreen mainScreen]; if (scr) bs = (float) scr.backingScaleFactor; }
    };
    if ([NSThread isMainThread]) body(); else dispatch_sync(dispatch_get_main_queue(), body);
    return (bs >= 0.5f && bs <= 8.0f) ? bs : 2.0f;
}
// HARNESS: post a synthetic LEFT-MOUSE event into THIS process's own AppKit
// event queue (never CGEventPost: that moves the user's real cursor and
// reaches other apps). The event then takes the path a real click takes --
// -[NSApplication sendEvent:] -> -[NSWindow sendEvent:] -> hit-test ->
// acceptsFirstMouse -> the hit view's mouseDown:/mouseDragged:/mouseUp: ->
// convertPoint -> TouchDispatcher.dispatch -- which the MACAPK_INJECT_FILE
// `tap` verb deliberately skips (it enters at TouchDispatcher in SURFACE
// pixels). Every stage a user's click crosses and `tap` does not is where a
// class of "the harness taps fine, the owner's mouse does nothing / lands
// somewhere else" bug lives: an NSView stacked over the render view eating
// the click, a points->pixels mapping that assumes surface == bounds *
// backingScale, a drag the window never routes back to the view.
//
// (fx, fy) are FRACTIONS of the window's CONTENT area, top-left origin: what
// a person clicking at that spot of the visible window is doing, independent
// of every mapping of ours. action: 0 down, 1 up, 2 dragged.
// Returns false when there is no window yet.
bool CocoaPostHostMouse(int action, float fx, float fy) {
    __block bool ok = false;
    void (^body)(void) = ^{
        NSWindow* w = g_placeholder_window;
        NSView* cv = w ? [w contentView] : nil;
        if (!cv) return;
        const NSRect b = [cv bounds];
        const CGFloat vx = b.origin.x + (CGFloat)fx * b.size.width;
        const CGFloat vy = [cv isFlipped] ? b.origin.y + (CGFloat)fy * b.size.height
                                          : b.origin.y + (1.0 - (CGFloat)fy) * b.size.height;
        const NSPoint wp = [cv convertPoint:NSMakePoint(vx, vy) toView:nil];
        const NSEventType t = action == 0 ? NSEventTypeLeftMouseDown
                            : action == 1 ? NSEventTypeLeftMouseUp
                                          : NSEventTypeLeftMouseDragged;
        static NSInteger s_evno = 0;
        NSEvent* ev = [NSEvent mouseEventWithType:t
                                         location:wp
                                    modifierFlags:0
                                        timestamp:[[NSProcessInfo processInfo] systemUptime]
                                     windowNumber:[w windowNumber]
                                          context:nil
                                      eventNumber:++s_evno
                                       clickCount:(action == 2 ? 0 : 1)
                                         pressure:(action == 1 ? 0.0f : 1.0f)];
        if (!ev) return;
        std::fprintf(stderr, "[hostmouse] post action=%d frac=(%.4f,%.4f) content=%.0fx%.0f "
                     "window-pt=(%.1f,%.1f)\n", action, fx, fy, b.size.width, b.size.height,
                     wp.x, wp.y);
        if (MacApkHarnessNoActivate()) {
            // MACAPK_NO_ACTIVATE: a posted mouseDown on an inactive app is a
            // CLICK to AppKit, which activates the app and makes this window key
            // (measured 2026-10-01: every harness host tap pulled keyboard focus
            // off the owner's app). Deliver it to the hit view directly instead,
            // the way the host-key harness calls keyDown:, and keep the down's
            // view for its drags and up, as AppKit's own mouse tracking does.
            static NSView* s_mouse_target = nil;
            NSView* root = [cv superview] ? [cv superview] : cv;
            if (action == 0 || !s_mouse_target)
                s_mouse_target = [root hitTest:[root convertPoint:wp fromView:nil]];
            NSView* tv = s_mouse_target;
            if (action == 1) s_mouse_target = nil;
            if (!tv) return;
            if (action == 0) [tv mouseDown:ev];
            else if (action == 1) [tv mouseUp:ev];
            else [tv mouseDragged:ev];
        } else {
            [NSApp postEvent:ev atStart:NO];
        }
        ok = true;
    };
    if ([NSThread isMainThread]) body(); else dispatch_sync(dispatch_get_main_queue(), body);
    return ok;
}
int CocoaPhoneWidth()  { SFTRACE("CocoaPhoneWidth -> %d",  g_phone_w); return g_phone_w; }
int CocoaPhoneHeight() { SFTRACE("CocoaPhoneHeight -> %d", g_phone_h); return g_phone_h; }

// ---- the display density (v1.0.2187) -----------------------------------------
// Mac.apk's Android display is measured in POINTS: one dp is one point on every
// Mac screen, so the density is the backing scale of the screen the app opens
// on -- 320 dpi (density 2.0) on a Retina screen, 160 dpi (density 1.0) on a 1x
// monitor. Until v1.0.2187 it was 320 everywhere, which is right only on
// Retina: on a 1x monitor the surface has one pixel per point, so the app was
// told half as many dp as the window has points and every app came up at twice
// its size (owner, 2026-10-15: Aurora Store "rather zoomed in" on the WD19's
// 1080p screens -- a 498pt window reported 249dp wide), and the window policy
// read every 1x screen as a phone (sw509dp) where a Retina one is large.
//
// AOSP has the same split: a display's density (ro.sf.lcd_density, the
// DisplayInfo.logicalDensityDpi a panel ships with) is a property of the
// DISPLAY, and resources then pick the bucket nearest it. Decided ONCE per
// process, as AOSP's write-once property and DisplayMetrics.DENSITY_DEVICE_STABLE
// are; the window moving to a screen of another scale later changes the
// surface's pixels, not the density (docs/display-density-follows-screen_2026-10-15.md).
//
// MACAPK_DISPLAY_DENSITY=<dpi> pins it; 320 is the pre-v1.0.2187 fixed model.
int CocoaDisplayDensityDpi() {
    static int s_dpi = 0;
    static dispatch_once_t s_once;
    dispatch_once(&s_once, ^{
        const char* why = "screen";
        float scale = 0.0f;
        if (const char* e = std::getenv("MACAPK_DISPLAY_DENSITY"); e && *e) {
            char* end = nullptr;
            const long v = std::strtol(e, &end, 10);
            if (end && *end == '\0' && v >= 120 && v <= 640) {
                s_dpi = (int) v;
                why = "MACAPK_DISPLAY_DENSITY";
            } else {
                std::fprintf(stderr, "[macapk] MACAPK_DISPLAY_DENSITY=%s not understood "
                             "(a dpi, 120..640); following the screen\n", e);
            }
        }
        if (s_dpi == 0) {
            @autoreleasepool {
                NSScreen* screen = [NSScreen mainScreen];
                scale = screen ? (float) screen.backingScaleFactor : 0.0f;
            }
            if (scale >= 0.5f && scale <= 8.0f) {
                s_dpi = (int) std::lround(160.0f * scale);
            } else {
                s_dpi = 320;
                why = "no screen";
            }
        }
        std::fprintf(stderr, "[macapk] display density: %d dpi (density %g) from %s"
                     " (screen backing scale %g)\n",
                     s_dpi, s_dpi / 160.0, why, (double) scale);
    });
    return s_dpi;
}
float CocoaDisplayDensity() { return (float) CocoaDisplayDensityDpi() / 160.0f; }

// ---- AOSP large-screen window policy (v1.0.1647) -----------------------------
// The contract is in window_policy.h and docs/window-size-policy_2026-09-25.md
// ("Phase 1 as built"). This block only moves boxes the pure policy computed.

CocoaContentBudget CocoaQueryContentBudget() {
    CocoaContentBudget b;
    @autoreleasepool {
        NSScreen* screen = [NSScreen mainScreen];
        if (!screen) return b;
        const NSRect vis = [screen visibleFrame];
        if (vis.size.width < 64 || vis.size.height < 64) return b;
        // The SAME budget the legacy canvas fits (content_budget_for_visible),
        // so a landscape app's box is pixel-identical under both rules.
        const NSSize budget = content_budget_for_visible(vis);
        b.w_pt = (int)budget.width;
        b.h_pt = (int)budget.height;
        b.scale = (float)screen.backingScaleFactor;
        if (!(b.scale >= 0.5f && b.scale <= 8.0f)) b.scale = 2.0f;
        b.ok = b.w_pt > 0 && b.h_pt > 0;
    }
    return b;
}

bool CocoaPolicyDisplayBudget(winpolicy::DisplayBudget* out, std::string* desc) {
    const CocoaContentBudget cb = CocoaQueryContentBudget();
    const float scale = cb.ok ? cb.scale : 2.0f;
    int w_pt = cb.w_pt, h_pt = cb.h_pt, sw_dp = 0;
    char buf[160];
    if (const char* e = std::getenv("MACAPK_WINDOW_BUDGET"); e && *e) {
        if (!winpolicy::ParseBudgetOverride(e, &w_pt, &h_pt, &sw_dp)) {
            std::snprintf(buf, sizeof buf, "MACAPK_WINDOW_BUDGET=%s not understood (WxH[~sw], points)", e);
            if (desc) *desc = buf;
            return false;
        }
        std::snprintf(buf, sizeof buf, "%dx%dpt @%gx from MACAPK_WINDOW_BUDGET%s", w_pt, h_pt,
                      scale, sw_dp > 0 ? " (display sw pinned)" : "");
    } else {
        if (!cb.ok) { if (desc) *desc = "no screen"; return false; }
        std::snprintf(buf, sizeof buf, "%dx%dpt @%gx", w_pt, h_pt, scale);
    }
    out->width_px = (int)std::lround(w_pt * scale);
    out->height_px = (int)std::lround(h_pt * scale);
    out->backing_scale = scale;
    out->density = CocoaDisplayDensity();      // the launch screen's (v1.0.2187)
    out->display_sw_dp = sw_dp;
    if (desc) *desc = buf;
    return true;
}

// ---- phase 2a: fullscreen + user resize (v1.0.1649) -------------------------
// The pure transitions are winpolicy::EnterFullScreen / ExitFullScreen /
// AllowedUserResize / ClampUserResize / ApplyUserResize / PlaceApp; the gate
// (debug/winpolicy_gate.sh) drives them with the same events. Everything here
// only measures AppKit and applies their results.

// BISECT GATE, default ON. MACAPK_FS_POLICY=legacy restores the pre-phase-2
// fullscreen exactly: nothing is recorded, and the container aspect-fits the
// app box into the screen (render_frame_in_container: 76pt bars for a C1/C4
// app, a size-compat app scaled UP and told a new display, an in-window
// letterbox fitted up with it) in the starting-window colour -- the v1.0.1648
// behaviour, for A/B. The fullscreen setFrame guard in
// reshape_placeholder_to_globals_main stays on either way: it is a correctness
// fix, not a policy.
static bool fs_policy_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_FS_POLICY");
        return !(e && (std::strcmp(e, "legacy") == 0 || std::strcmp(e, "0") == 0));
    }();
    return on;
}

// FEATURE GATE, default OFF until MEASURED. MACAPK_WINDOW_FREE_RESIZE=1 turns on
// the per-class drag: C1/C2 lose the aspect lock (contentResizeIncrements) and
// the delegate clamps a live drag to the pure range (AllowedUserResize: the C2 /
// held-C1 side, the app's aspect limits, AOSP's 220dp minimum); C3/C4 keep the
// lock with the 220dp minimum. OFF (the default) is EXACTLY the v1.0.503 drag:
// every class aspect-locked, a 240pt minimum, no clamp. The policy state follows
// the resize either way (ApplyUserResize: the window is the display, the C1 side
// hold for a window-manager size), since that only records what DisplayConfig
// already commits at the end of a drag.
// Why OFF: the phase-1 plan kept it off until the G5 by-eye check, and nothing
// has measured it. A free drag is an aspect-changing DISPLAY resize, and the
// at-risk apps are the cocos2d-x v2 games that latch their size in nativeInit
// (HCR 1.8.1 -- a protected sweep row --, GD Lite, OpenGD), which can show the
// "two surface-size publications disagree" band; no sweep row drags, so the
// sweep cannot see it. Removal condition: the G5 check (a free landscape drag
// of HCR 1.8.1 / GD Lite / OpenGD on the installed stubs, plus Crossy with
// debug/drag_freeze_watch.sh) is run and notated in Current_State; then flip
// the default and keep =0 as the bisect switch.
static bool free_resize_on() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_WINDOW_FREE_RESIZE");
        return e && (std::strcmp(e, "1") == 0 || std::strcmp(e, "on") == 0
                     || std::strcmp(e, "true") == 0);
    }();
    return on;
}

// Split View tiles are fullscreen windows too (NSWindowStyleMaskFullScreen).
static bool window_is_fullscreen() {
    return g_placeholder_window
        && ([g_placeholder_window styleMask] & NSWindowStyleMaskFullScreen) != 0;
}

// The MEASURED content of `w` as a display: the content view's bounds (a
// notched panel's safe area can differ from what AppKit proposed), px at the
// window's own backing scale, the process's display density (fixed at launch).
static winpolicy::DisplayBudget measured_window_budget(NSWindow* w) {
    winpolicy::DisplayBudget b;
    if (!w) return b;
    const NSSize cs = [[w contentView] bounds].size;
    float bs = (float) w.backingScaleFactor;
    if (!(bs >= 0.5f && bs <= 8.0f)) bs = 2.0f;
    b.width_px = (int) std::lround(cs.width * bs);
    b.height_px = (int) std::lround(cs.height * bs);
    b.backing_scale = bs;
    b.density = CocoaDisplayDensity();
    return b;
}

// g_win / g_phone follow g_wp.live (the render view's box is the app box).
static void globals_from_live() {
    g_win_w = g_wp.live.win_w_pt;
    g_win_h = g_wp.live.win_h_pt;
    g_phone_w = g_wp.live.app_w_pt;
    g_phone_h = g_wp.live.app_h_pt;
}

void CocoaInstallWindowPolicy(const winpolicy::WindowTraits& traits,
                              const winpolicy::DeviceSettings& settings,
                              const std::string& pref_path,
                              winpolicy::UserWindowPref pref,
                              bool persist_pref) {
    g_wp.active       = true;
    g_wp.traits       = traits;
    g_wp.settings     = settings;
    g_wp.pref_path    = pref_path;
    g_wp.persist_pref = persist_pref;
    g_wp.live.pref    = pref;
    publish_live_xthread();
}

void CocoaClearWindowPolicy() {
    if (g_wp.seeded_rotation) g_user_orient_seeded = false;
    g_wp = WindowPolicySession();
    g_win_w = g_win_h = 0;
}

bool CocoaWindowPolicyActive() { return g_wp.active; }

void CocoaApplyWindowDecision(const winpolicy::WindowDecision& d,
                              const winpolicy::DisplayBudget& budget) {
    if (!d.policy_applies || d.box_w_pt <= 0 || d.box_h_pt <= 0) return;
    winpolicy::LiveWindow w = winpolicy::LiveFromDecision(d, budget, g_wp.live.pref);
    w.handles_side_change = g_wp.live.handles_side_change;
    // Phase 2a: a --fullscreen launch (MACAPK_FULLSCREEN=1) is already in
    // fullscreen when the authoritative decision lands. The decision is then
    // the WINDOWED state that fullscreen saved, and the live boxes are that
    // state entered into fullscreen again -- not a windowed live state over a
    // fullscreen window (whose reshape would setFrame the fullscreen window).
    const bool on_main = [NSThread isMainThread];
    const bool fs_now = g_wp.live.fullscreen || (on_main && window_is_fullscreen());
    if (fs_now && fs_policy_on() && on_main) {
        const winpolicy::DisplayBudget fsb = g_wp.live.fullscreen
            ? g_wp.live.budget : measured_window_budget(g_placeholder_window);
        const winpolicy::LiveOutcome o =
            winpolicy::EnterFullScreen(g_wp.traits, g_wp.settings, w, fsb);
        g_wp.live = o.next;
        globals_from_live();
        std::fprintf(stderr, "[I] cocoa: window decision applied inside fullscreen: app %dx%d in a "
                     "%dx%d screen (%s)\n", g_phone_w, g_phone_h, g_win_w, g_win_h, o.why.c_str());
    } else {
        g_wp.live = w;
        g_phone_w = g_win_w = d.box_w_pt;
        g_phone_h = g_win_h = d.box_h_pt;
    }
    publish_live_xthread();
    g_explicit_size = false;
    // The critique's seed-once point: the user rotation must come from THIS
    // decision, not from whichever app request happens to run first -- the
    // exact bug the seed-once code in CocoaSetPhoneSize* exists to prevent.
    g_user_orient_seeded = true;
    g_user_landscape = d.box_w_pt > d.box_h_pt;
    g_wp.seeded_rotation = true;
}

// Re-fit the render view into the (unchanged) window at the current app box:
// MacApkLetterboxContainer aspect-fits its subview to g_phone_w:g_phone_h, and
// AppKit only asks it to when the CONTAINER changes size -- an in-window
// letterbox changes the app box alone.
static void refit_letterbox_main() {
    if (g_letterbox_container)
        [g_letterbox_container resizeSubviewsWithOldSize:[g_letterbox_container bounds].size];
}

// Apply a live transition's RESULT (window_policy.cpp decided it). A reshape
// may shrink both boxes to fit the screen (reshape_placeholder_to_globals_main
// keeps them honest), so the live state is re-read from the globals after it.
static void apply_live_outcome_main(const winpolicy::LiveOutcome& o,
                                    ReshapeAnchor anchor = ReshapeAnchor::kCenterOnScreen) {
    g_wp.live = o.next;
    publish_live_xthread();
    switch (o.change) {
        case winpolicy::LiveChange::kNone:
        case winpolicy::LiveChange::kRefused:
            return;
        case winpolicy::LiveChange::kInWindow:
            g_phone_w = o.next.app_w_pt;
            g_phone_h = o.next.app_h_pt;
            refit_letterbox_main();
            return;
        case winpolicy::LiveChange::kReshapeWindow:
            g_win_w = o.next.win_w_pt;
            g_win_h = o.next.win_h_pt;
            g_phone_w = o.next.app_w_pt;
            g_phone_h = o.next.app_h_pt;
            reshape_placeholder_to_globals_main(anchor);   // re-reads g_wp.live from the globals
            return;
    }
}

// Activity.setRequestedOrientation under the policy. The pure transition
// (winpolicy::ApplyRequestedOrientation) resolves the request on the DISPLAY
// the window was decided on -- never on the current window, which after a
// letterbox or a Cmd+R is smaller than the display (AR16
// resolveFixedOrientationConfiguration's parent is the task/display) -- then
// letterboxes inside the window when the box fits, or grows the window to it.
static void apply_requested_orientation_policy_main(int screen_orientation) {
    const winpolicy::LiveOutcome o = winpolicy::ApplyRequestedOrientation(
        g_wp.traits, g_wp.settings, g_wp.live, screen_orientation);
    apply_live_outcome_main(o);
    std::fprintf(stderr, "[I] cocoa: requested orientation %s -> %s: app %dx%d in a %dx%d window (%s)\n",
                 winpolicy::OrientationName(screen_orientation), winpolicy::LiveChangeName(o.change),
                 g_phone_w, g_phone_h, g_win_w, g_win_h, o.why.c_str());
}

// Cmd+R under the policy: toggle this app's user aspect-ratio preference
// between its policy box and the PORTRAIT box, re-resolve on the screen the
// window is on now WITH the app's current requested orientation, persist
// (unless `persist` is false or the run pinned MACAPK_WINDOW_PREF), reshape.
static void toggle_window_pref_main(bool persist) {
    winpolicy::DisplayBudget screen;
    std::string desc;
    if (g_wp.live.fullscreen) {
        // Phase 2a: in fullscreen the display is the fullscreen content (the
        // pure transition ignores `screen` there). mainScreen's visibleFrame
        // inside a fullscreen Space is not the windowed budget either.
        screen = g_wp.live.budget;
    } else if (!CocoaPolicyDisplayBudget(&screen, &desc)) {
        std::fprintf(stderr, "[I] cocoa: Cmd+R: window preference unchanged -- %s\n", desc.c_str());
        return;
    }
    const winpolicy::LiveOutcome o =
        winpolicy::ToggleUserPref(g_wp.traits, g_wp.settings, g_wp.live, screen);
    apply_live_outcome_main(o);
    const char* saved = "not saved (preference unchanged)";
    if (o.pref_changed) {
        if (!persist)                saved = "not saved (self-test)";
        else if (!g_wp.persist_pref) saved = "not saved (MACAPK_WINDOW_PREF pins it)";
        else if (winpolicy::WriteUserPref(g_wp.pref_path, o.next.pref)) saved = g_wp.pref_path.c_str();
        else                         saved = "NOT saved (write failed)";
    }
    std::fprintf(stderr, "[I] cocoa: Cmd+R window preference -> %s: %s, %dx%d window, app %dx%d %s (%s; %s)\n",
                 winpolicy::UserPrefName(g_wp.live.pref), winpolicy::LiveChangeName(o.change),
                 g_win_w, g_win_h, g_phone_w, g_phone_h,
                 winpolicy::ClassName(g_wp.live.decision.cls), o.why.c_str(), saved);
}

void CocoaApplyWindowOrientation(int screen_orientation) {
    dispatch_main_async_void(^{
        if (!g_wp.active) return;
        apply_requested_orientation_policy_main(screen_orientation);
    });
}

// ---- phase 3: every activity gets its own box --------------------------------
// ActivityHost (the Android owner thread) reports each activity START and each
// REVEAL of a box owner; winpolicy::ApplyActivityTransition decides, this
// applies. docs/window-policy-per-activity-orientation_2026-09-25.md, "Phase 3
// as built" in docs/window-size-policy_2026-09-25.md.

// Main thread: one transition, applied; the render view's resulting drawable
// size in px (what DisplayConfig commits before the activity attaches).
static bool activity_window_transition_main(const std::string& component, int event,
                                            bool occludes, bool has_request, int request,
                                            int behind, int real_config_changes, int token,
                                            int cover_token, int* app_w_px, int* app_h_px,
                                            int* change) {
    if (!g_wp.active) return false;
    const winpolicy::ActivityEvent ev = event == 1 ? winpolicy::ActivityEvent::kStartRoot
                                      : event == 2 ? winpolicy::ActivityEvent::kReveal
                                      : event == 3 ? winpolicy::ActivityEvent::kStartOverFinishing
                                                   : winpolicy::ActivityEvent::kStart;
    winpolicy::ActivityWindowRequest req;
    req.component = component;
    req.token = token;
    req.occludes = occludes;
    req.has_request = has_request;
    req.request = request;
    req.behind_orientation = behind;
    req.handles_side_change = winpolicy::HandlesSideChange(real_config_changes);
    if (ev == winpolicy::ActivityEvent::kReveal) {
        auto it = g_wp_snapshots.find(token);
        if (it != g_wp_snapshots.end()) {
            req.saved = it->second;
            g_wp_snapshots.erase(it);
        }
    } else if (ev == winpolicy::ActivityEvent::kStartRoot
               || ev == winpolicy::ActivityEvent::kStartOverFinishing) {
        // A new task, or a live one whose activities all finished: nothing
        // below it is left to reveal.
        g_wp_snapshots.clear();
    }
    const winpolicy::LiveWindow before = g_wp.live;
    const winpolicy::LiveOutcome o =
        winpolicy::ApplyActivityTransition(g_wp.traits, g_wp.settings, g_wp.live, ev, req);
    // The box owner this start covers remembers what it was shown in.
    if (ev != winpolicy::ActivityEvent::kReveal && cover_token != 0)
        g_wp_snapshots[cover_token] = winpolicy::SnapshotLive(before);
    apply_live_outcome_main(o, ReshapeAnchor::kKeepCenter);
    std::fprintf(stderr, "[I] cocoa: activity %s -> %s: app %dx%d in a %dx%d window (%s)\n",
                 winpolicy::ActivityEventName(ev), winpolicy::LiveChangeName(o.change),
                 g_phone_w, g_phone_h, g_win_w, g_win_h, o.why.c_str());
    // The render view is PlaceApp's box (render_frame_in_container, k = 1 in
    // steady state) at the window's backing scale.
    const winpolicy::AppPlacement pl = winpolicy::PlaceApp(g_wp.live, g_wp.settings);
    float bs = g_placeholder_window ? (float) g_placeholder_window.backingScaleFactor
                                    : g_wp.live.budget.backing_scale;
    if (!(bs >= 0.5f && bs <= 8.0f)) bs = 2.0f;
    const int w_pt = pl.w_pt > 0 ? pl.w_pt : g_phone_w, h_pt = pl.h_pt > 0 ? pl.h_pt : g_phone_h;
    *app_w_px = (int) std::lround(w_pt * bs);
    *app_h_px = (int) std::lround(h_pt * bs);
    *change = (int) o.change;
    return true;
}

// Any thread (ActivityHost calls it on the Android owner thread, in the middle of
// a launch or a back). The transition runs on AppKit, which owns g_wp.live; the
// caller waits for it so the activity can attach with its own configuration --
// but BOUNDED, never dispatch_sync: the AppKit thread can itself be waiting on
// Java (a Unity surfaceCreated blocks it on purpose, see
// CocoaEnsureNativeEglLayer), and a stalled answer must degrade to the ordinary
// display commit a frame later, never to a hang.
static constexpr int64_t kActivityTransitionWaitMs = 500;
bool CocoaActivityWindowTransition(const std::string& component, int event, bool occludes,
                                   bool has_request, int request, int behind,
                                   int real_config_changes, int token, int cover_token,
                                   int* app_w_px, int* app_h_px, int* change) {
    if (!g_wp.active || !app_w_px || !app_h_px || !change) return false;
    // A shared control block (the same shape as the bounded nextDrawable handoff
    // in this file): the block may run after the caller gave up waiting, so it
    // owns its own copy of everything it writes.
    struct Result {
        std::mutex mu;
        std::condition_variable cv;
        bool done = false, ok = false;
        int w = 0, h = 0, change = 0;
    };
    auto result = std::make_shared<Result>();
    const std::string comp = component;
    void (^body)(void) = ^{
        int w = 0, h = 0, ch = 0;
        const bool ok = activity_window_transition_main(comp, event, occludes, has_request,
                                                        request, behind, real_config_changes,
                                                        token, cover_token, &w, &h, &ch);
        std::lock_guard<std::mutex> lk(result->mu);
        result->ok = ok;
        result->w = w;
        result->h = h;
        result->change = ch;
        result->done = true;
        result->cv.notify_all();
    };
    if ([NSThread isMainThread]) {
        body();
    } else {
        dispatch_async(dispatch_get_main_queue(), body);
        std::unique_lock<std::mutex> lk(result->mu);
        if (!result->cv.wait_for(lk, std::chrono::milliseconds(kActivityTransitionWaitMs),
                                 [&] { return result->done; })) {
            std::fprintf(stderr, "[W] cocoa: activity %s (event %d): AppKit did not apply it within "
                         "%lld ms; it lands when AppKit runs it, and its configuration through the "
                         "ordinary display commit\n", comp.c_str(), event,
                         (long long) kActivityTransitionWaitMs);
            return false;
        }
    }
    std::lock_guard<std::mutex> lk(result->mu);
    if (!result->ok) return false;
    *app_w_px = result->w;
    *app_h_px = result->h;
    *change = result->change;
    return true;
}

// Activity.setRequestedOrientation from any live activity (ActivityHost R4),
// `token` naming its record. The pure transition applies it only from the box
// owner's record -- whatever is on top of it -- and refuses it from any other
// (a buried or translucent activity, or a stale post from one since covered);
// BEHIND takes the side below.
void CocoaApplyWindowOrientationFor(const std::string& component, int token,
                                    int screen_orientation, int behind) {
    const std::string comp = component;
    dispatch_main_async_void(^{
        if (!g_wp.active) return;
        const winpolicy::LiveOutcome o = winpolicy::ApplyRequestedOrientationFrom(
            g_wp.traits, g_wp.settings, g_wp.live, comp, token, screen_orientation, behind);
        apply_live_outcome_main(o);
        std::fprintf(stderr, "[I] cocoa: requested orientation %s -> %s: app %dx%d in a %dx%d window (%s)\n",
                     winpolicy::OrientationName(screen_orientation), winpolicy::LiveChangeName(o.change),
                     g_phone_w, g_phone_h, g_win_w, g_win_h, o.why.c_str());
    });
}

// Activity.getRequestedOrientation's server answer (ActivityHost), any thread:
// winpolicy::OverrideOrientation on the published display and preference.
// traits and settings are written before the JVM starts and never after.
int CocoaWindowOverrideOrientation(const std::string& component, int record_orientation) {
    if (!g_wp.active) return record_orientation;
    winpolicy::DisplayBudget b;
    winpolicy::UserWindowPref pref;
    {
        std::lock_guard<std::mutex> lk(g_wp_xt_mu);
        b = g_wp_xt_budget;
        pref = g_wp_xt_pref;
    }
    if (b.width_px <= 0 || b.height_px <= 0) return record_orientation;
    return winpolicy::OverrideOrientation(g_wp.traits, component, b, g_wp.settings, pref,
                                          record_orientation);
}

// ---- phase 2a: the window's user-resize locks --------------------------------
// box = the window box whose aspect is kept, cur = the content size the window
// has (or is about to have); the minimum never exceeds it.
//  - legacy, or MACAPK_WINDOW_FREE_RESIZE off (the default): the v1.0.503 lock,
//    unchanged -- contentAspectRatio(box) and a 240pt short-side minimum.
//  - free resize on, a class that keeps its aspect (C3, C4):
//    contentAspectRatio(box), and AOSP's 220dp minimum
//    (winpolicy::AllowedUserResize) as the short side.
//  - free resize on, C1 / C2: no aspect lock -- contentResizeIncrements (1, 1)
//    clears it (INFERRED from the old NSWindow reference, "You can reset an
//    aspect ratio set with this method by calling setContentResizeIncrements:
//    with an NSSize of (1.0, 1.0)"; the SDK header does not say it. If it does
//    not, the drag stays aspect-locked, which is the pre-2a behaviour) -- and
//    the delegate's windowWillResize:toSize: clamps the drag instead.
static void apply_window_locks_main(double box_w, double box_h, double cur_w, double cur_h) {
    NSWindow* w = g_placeholder_window;
    if (!w || box_w <= 0 || box_h <= 0) return;
    if (window_is_fullscreen()) return;   // AppKit owns a fullscreen window's size
    double min_short = 240.0;
    if (free_resize_on() && g_wp.active && g_wp.live.decision.policy_applies) {
        const winpolicy::UserResizeRange r =
            winpolicy::AllowedUserResize(g_wp.traits, g_wp.settings, g_wp.live);
        if (r.applies && r.min_short_pt > 0) min_short = r.min_short_pt;
        if (r.applies && !r.keep_aspect) {
            [w setContentResizeIncrements:NSMakeSize(1.0, 1.0)];
            double mn = min_short;
            if (cur_w > 0 && cur_h > 0) mn = MIN(mn, MIN(cur_w, cur_h));
            [w setContentMinSize:NSMakeSize(mn, mn)];
            return;
        }
    }
    [w setContentAspectRatio:NSMakeSize(box_w, box_h)];
    const double min_sc = min_short / MIN(box_w, box_h);
    double min_w = box_w * min_sc, min_h = box_h * min_sc;
    if (cur_w > 0 && cur_h > 0 && (min_w > cur_w || min_h > cur_h)) { min_w = cur_w; min_h = cur_h; }
    [w setContentMinSize:NSMakeSize(min_w, min_h)];
}

// Re-lock the window for the CURRENT live state (after a user / window-manager
// resize, and after leaving fullscreen). A tile, Fill or Zoom is not bound by
// contentAspectRatio, so without this the lock still holds the OLD box's aspect
// and the next hand drag snaps back to it, away from the golden model
// (AllowedUserResize keeps the CURRENT window's aspect). While a C1 side hold is
// active the lock follows the APP's box instead of the window: with free resize
// off (every drag aspect-locked) the next drag then brings the window back onto
// the app's own side, which ends the hold, instead of trapping it on the other
// side. With free resize on a C1 window is unlocked either way.
static void relock_window_for_live_main(double cur_w, double cur_h) {
    const bool held = g_wp.active && g_wp.live.held_side != winpolicy::kConfigOrientUndefined
                      && g_phone_w > 0 && g_phone_h > 0;
    apply_window_locks_main(held ? g_phone_w : g_win_w, held ? g_phone_h : g_win_h, cur_w, cur_h);
}

// ---- phase 2a: where the render view sits in the letterbox container ---------
// Under the policy: the live window's layout (winpolicy::PlaceApp: the app box
// x compat_scale, offset by the letterbox position multipliers), uniformly
// scaled to fit the container and centred. In steady state the container IS
// the live window (k = 1), so the view is exactly the app box -- a size-compat
// app in fullscreen stays at 1x with bars, never scaled up. While AppKit is
// resizing the window and the live state has not caught up (a live drag, the
// fullscreen animation) the whole layout scales with the window, as the
// v1.0.503 aspect-fit did. Legacy: the v1.0.503 aspect-fit of g_phone.
// *bars: part of the container is not the app (AOSP's letterbox background).
static NSRect render_frame_in_container(NSSize cs, bool* bars) {
    if (bars) *bars = false;
    const winpolicy::LiveWindow& l = g_wp.live;
    // MACAPK_FS_POLICY=legacy: a fullscreen (or transitioning) window keeps
    // EXACTLY the pre-2a layout -- the v1.0.503 aspect-fit of the app box, and
    // the starting-window colour around it (*bars stays false) -- so the gate
    // bisects the whole fullscreen change. (Without this, an in-window letterbox
    // such as a C1 app's portrait request came out 673x898 with black bars
    // where pre-2a fitted it up to 787x1050.)
    const bool legacy_fs = !fs_policy_on() && (window_is_fullscreen() || g_fs_transition);
    if (g_wp.active && !legacy_fs && g_win_w > 0 && l.win_w_pt > 0 && l.win_h_pt > 0
        && cs.width > 0 && cs.height > 0) {
        const winpolicy::AppPlacement p = winpolicy::PlaceApp(l, g_wp.settings);
        const double ww = l.win_w_pt, wh = l.win_h_pt;
        double k = MIN(cs.width / ww, cs.height / wh);
        if (std::fabs(cs.width - ww) < 0.5 && std::fabs(cs.height - wh) < 0.5) k = 1.0;
        const double ox = (cs.width - ww * k) * 0.5;
        const double oy_top = (cs.height - wh * k) * 0.5;
        // Whole points, as MacApkAspectFitRect rounds (a fractional rect makes
        // a drawable size no layer can have -- the v1.0.781 hcr181 race).
        const double w = std::round(p.w_pt * k), h = std::round(p.h_pt * k);
        const double x = std::round(ox + p.x_pt * k);
        // PlaceApp measures y from the TOP; the container is not flipped.
        const double y = std::round(cs.height - oy_top - p.y_pt * k - p.h_pt * k);
        if (bars) *bars = !(x <= 0.0 && y <= 0.0 && w >= cs.width && h >= cs.height);
        return NSMakeRect(x, y, w, h);
    }
    return MacApkAspectFitRect(cs, (CGFloat) g_phone_w, (CGFloat) g_phone_h);
}

// The container's own colour: the starting window where the app is (unchanged
// behaviour), AOSP's letterbox background (#000000) where bars show.
static void set_container_background_main(bool bars) {
    if (!g_letterbox_container) return;
    [g_letterbox_container setWantsLayer:YES];
    CGColorRef want = (bars || !g_starting_bg) ? [[NSColor blackColor] CGColor] : g_starting_bg;
    g_letterbox_container.layer.backgroundColor = want;
}

// Main thread: lay the render view(s) out in the container (the body of
// MacApkLetterboxContainer -resizeSubviewsWithOldSize:).
static void layout_letterbox_container_main(NSView* container) {
    bool bars = false;
    const NSRect fr = render_frame_in_container(container.bounds.size, &bars);
    for (NSView* sub in [container subviews]) [sub setFrame:fr];
    if (g_wp.active) set_container_background_main(bars);
}

// ---- phase 2a: fullscreen transitions (MacApkWindowDelegate) -----------------
static const char* budget_str(const winpolicy::DisplayBudget& b, char* buf, size_t n) {
    std::snprintf(buf, n, "%dx%d px", b.width_px, b.height_px);
    return buf;
}

static void fs_log_state(const char* what, const std::string& why) {
    std::fprintf(stderr, "[I] cocoa: fullscreen %s -> app %dx%d (scale %.3f) in a %dx%d window "
                 "(%s%s; %s)\n", what, g_phone_w, g_phone_h, g_wp.live.compat_scale,
                 g_win_w, g_win_h, winpolicy::ClassName(g_wp.live.decision.cls),
                 g_wp.live.compat_restart_offered ? ", Android would offer restart" : "",
                 why.c_str());
}

static void window_will_enter_fullscreen_main() {
    g_fs_transition = true;
    // The windowed screen, captured while this is still the windowed Space: a
    // Cmd+R made in fullscreen is applied on it when the window leaves.
    g_fs_screen_ok = CocoaPolicyDisplayBudget(&g_fs_screen, nullptr);
    std::fprintf(stderr, "[I] cocoa: fullscreen: entering from a %dx%d window\n", g_win_w > 0 ? g_win_w : g_phone_w,
                 g_win_h > 0 ? g_win_h : g_phone_h);
}

static void window_did_enter_fullscreen_main(NSWindow* w) {
    g_fs_transition = false;
    const winpolicy::DisplayBudget fsb = measured_window_budget(w);
    char b[64];
    if (!g_wp.active || !fs_policy_on()) {
        std::fprintf(stderr, "[I] cocoa: fullscreen entered (%s content; %s): the windowed layout "
                     "is fitted into it\n", budget_str(fsb, b, sizeof b),
                     g_wp.active ? "MACAPK_FS_POLICY=legacy" : "legacy window rule");
        refit_letterbox_main();
        return;
    }
    // The policy's transition supersedes any reshape a caller deferred during
    // the animation (it resolves the box on the new display itself).
    g_fs_pending_reshape = false;
    const winpolicy::LiveOutcome o =
        winpolicy::EnterFullScreen(g_wp.traits, g_wp.settings, g_wp.live, fsb);
    if (o.change == winpolicy::LiveChange::kRefused) {
        std::fprintf(stderr, "[W] cocoa: fullscreen entered but not applied: %s\n", o.why.c_str());
        refit_letterbox_main();
        return;
    }
    g_wp.live = o.next;
    publish_live_xthread();
    globals_from_live();
    refit_letterbox_main();
    fs_log_state("enter", o.why + ", content " + budget_str(fsb, b, sizeof b));
}

// A change of the fullscreen content while in fullscreen: a Split View
// divider, a tile, the screen. Re-enter on the new size (the saved windowed
// state is kept); never a user resize.
static void fullscreen_content_changed_main(NSWindow* w, const char* how) {
    if (!g_wp.active || !fs_policy_on() || !g_wp.live.fullscreen) { refit_letterbox_main(); return; }
    const winpolicy::DisplayBudget fsb = measured_window_budget(w);
    if (fsb.width_px == g_wp.live.budget.width_px && fsb.height_px == g_wp.live.budget.height_px) return;
    const winpolicy::LiveOutcome o =
        winpolicy::EnterFullScreen(g_wp.traits, g_wp.settings, g_wp.live, fsb);
    if (o.change == winpolicy::LiveChange::kRefused) return;
    g_wp.live = o.next;
    publish_live_xthread();
    globals_from_live();
    refit_letterbox_main();
    fs_log_state(how, o.why);
}

static void window_will_exit_fullscreen_main() {
    g_fs_transition = true;
    if (!g_wp.active || !fs_policy_on() || !g_wp.live.fullscreen) {
        // Not tracked by the policy: a reshape deferred while fullscreen
        // (g_fs_pending_reshape) still runs at windowDidExitFullScreen.
        std::fprintf(stderr, "[I] cocoa: fullscreen: leaving\n");
        return;
    }
    g_fs_saved_live = g_wp.live;
    winpolicy::DisplayBudget screen = g_fs_screen;
    if (!g_fs_screen_ok) screen = g_wp.live.windowed.budget;   // never the fullscreen Space's frame
    const winpolicy::LiveOutcome o =
        winpolicy::ExitFullScreen(g_wp.traits, g_wp.settings, g_wp.live, screen);
    g_wp.live = o.next;
    publish_live_xthread();
    globals_from_live();
    // AppKit restores the SAVED windowed frame; a different window box is
    // applied once the window is windowed again (windowDidExitFullScreen).
    g_fs_pending_reshape = o.change == winpolicy::LiveChange::kReshapeWindow;
    refit_letterbox_main();
    std::fprintf(stderr, "[I] cocoa: fullscreen leave -> %s: app %dx%d in a %dx%d window (%s)\n",
                 winpolicy::LiveChangeName(o.change), g_phone_w, g_phone_h, g_win_w, g_win_h,
                 o.why.c_str());
}

static void window_did_exit_fullscreen_main(NSWindow* w) {
    g_fs_transition = false;
    const NSSize cs = [[w contentView] bounds].size;
    if (g_fs_pending_reshape) {
        g_fs_pending_reshape = false;
        reshape_placeholder_to_globals_main();
    } else if (!g_wp.active || g_win_w <= 0) {
        // Legacy: a legacy Cmd+R made in fullscreen transposed g_phone while
        // the frame stayed fullscreen (its reshape was deferred); AppKit then
        // restores the OLD frame. One reshape repairs the shape.
        const double want = g_phone_h > 0 ? (double) g_phone_w / g_phone_h : 0.0;
        const double got = cs.height > 0 ? cs.width / cs.height : 0.0;
        if (want > 0 && got > 0 && std::fabs(want - got) / want > 0.01) reshape_placeholder_to_globals_main();
        else refit_letterbox_main();
    } else if ((int) std::lround(cs.width) != g_win_w || (int) std::lround(cs.height) != g_win_h) {
        // The frame AppKit restored is not the window the policy's state says
        // (e.g. a request that reshaped the window DURING the enter animation,
        // whose setFrame was deferred): give the window the policy's box.
        std::fprintf(stderr, "[I] cocoa: fullscreen left into a %.0fx%.0f window, the policy says "
                     "%dx%d: reshaping\n", cs.width, cs.height, g_win_w, g_win_h);
        reshape_placeholder_to_globals_main();
    } else {
        refit_letterbox_main();
        relock_window_for_live_main(cs.width, cs.height);
    }
    std::fprintf(stderr, "[I] cocoa: fullscreen left: window %.0fx%.0f pt, app %dx%d\n",
                 cs.width, cs.height, g_phone_w, g_phone_h);
}

static void window_did_fail_fullscreen_main(bool entering) {
    g_fs_transition = false;
    if (!entering && g_wp.active && g_fs_saved_live.fullscreen) {
        // ExitFullScreen already ran in windowWillExitFullScreen: the window
        // is still fullscreen, so its state comes back -- or, when an activity
        // started or was revealed while AppKit animated, the CURRENT owner is
        // entered into fullscreen again; the epoch never rewinds either way
        // (winpolicy::FailedExitFullScreen, golden `exitfail` rows).
        const winpolicy::LiveOutcome o = winpolicy::FailedExitFullScreen(
            g_wp.traits, g_wp.settings, g_fs_saved_live, g_wp.live);
        g_wp.live = o.next;
        publish_live_xthread();
        globals_from_live();
        g_fs_pending_reshape = false;
        std::fprintf(stderr, "[I] cocoa: fullscreen exit failed -> app %dx%d in a %dx%d window (%s)\n",
                     g_phone_w, g_phone_h, g_win_w, g_win_h, o.why.c_str());
    }
    if (entering && g_fs_pending_reshape && !window_is_fullscreen()) {
        // A reshape deferred during the failed enter animation (a request or a
        // legacy Cmd+R landed between will- and didFailToEnter): the window
        // never became fullscreen, so apply it now instead of leaving the stale
        // size until some later fullscreen exit.
        g_fs_pending_reshape = false;
        reshape_placeholder_to_globals_main();
    }
    refit_letterbox_main();
    std::fprintf(stderr, "[W] cocoa: fullscreen %s FAILED (AppKit); state restored\n",
                 entering ? "enter" : "exit");
}

// ---- phase 2a: user resizes (MacApkWindowDelegate) ---------------------------
// windowWillResize:toSize: -- the live drag, clamped by the pure range. The
// classes that keep their aspect are left to contentAspectRatio. ONLY a live
// drag: AppKit also asks for some programmatic resizes (zoom), and a
// window-manager size (a tile, Fill) is accepted as given, exactly as
// winpolicy::ApplyUserResize accepts it (golden `resize:` rows, mutant 36).
static NSSize window_will_resize_main(NSWindow* w, NSSize frame_size) {
    if (!g_wp.active || !free_resize_on() || g_fs_transition || window_is_fullscreen()
        || !w.inLiveResize)
        return frame_size;
    const winpolicy::UserResizeRange r =
        winpolicy::AllowedUserResize(g_wp.traits, g_wp.settings, g_wp.live);
    if (!r.applies || r.keep_aspect) return frame_size;
    const NSRect content = [w contentRectForFrameRect:NSMakeRect(0, 0, frame_size.width, frame_size.height)];
    int cw = (int) std::lround(content.size.width), ch = (int) std::lround(content.size.height);
    winpolicy::ClampUserResize(r, &cw, &ch);
    return [w frameRectForContentRect:NSMakeRect(0, 0, cw, ch)].size;
}

// A user resize ended (a drag), or the window manager resized the window
// (tiling, Fill, Zoom, AX): the window is the display now.
static void apply_user_resize_main(NSWindow* w, const char* how) {
    if (!g_wp.active || g_fs_transition || window_is_fullscreen()) return;
    const NSSize cs = [[w contentView] bounds].size;
    const int w_pt = (int) std::lround(cs.width), h_pt = (int) std::lround(cs.height);
    if (w_pt == g_wp.live.win_w_pt && h_pt == g_wp.live.win_h_pt) return;   // the size we set
    const winpolicy::LiveOutcome o =
        winpolicy::ApplyUserResize(g_wp.traits, g_wp.settings, g_wp.live, w_pt, h_pt);
    if (o.change == winpolicy::LiveChange::kRefused) {
        std::fprintf(stderr, "[I] cocoa: user resize (%s) to %dx%d not applied: %s\n", how, w_pt, h_pt,
                     o.why.c_str());
        return;
    }
    g_wp.live = o.next;
    publish_live_xthread();
    globals_from_live();
    refit_letterbox_main();
    // The lock follows the new window (or the held app box): see
    // relock_window_for_live_main.
    relock_window_for_live_main(w_pt, h_pt);
    std::fprintf(stderr, "[I] cocoa: user resize (%s) -> %dx%d window, app %dx%d (scale %.3f) (%s)%s\n",
                 how, g_win_w, g_win_h, g_phone_w, g_phone_h, g_wp.live.compat_scale, o.why.c_str(),
                 (g_wp.live.decision.size_compat && g_wp.live.compat_scale != 1.f)
                     ? " -- KNOWN-OPEN (phase 2b): the render view is drawn at the scaled size, so "
                       "the app is told it until the drawable is frozen"
                     : "");
}

static void window_did_end_live_resize_main(NSWindow* w) {
    if (g_fs_transition) return;
    if (window_is_fullscreen()) { fullscreen_content_changed_main(w, "split-view divider"); return; }
    apply_user_resize_main(w, "drag");
}

static void window_did_resize_main(NSWindow* w) {
    if (g_own_frame_change > 0 || g_fs_transition || w.inLiveResize) return;
    if (window_is_fullscreen()) { fullscreen_content_changed_main(w, "fullscreen content"); return; }
    apply_user_resize_main(w, "window manager");
}

// Java (ActivityHost, on every resume): the top activity's real configChanges.
// Pushed rather than queried from the AppKit thread -- a synchronous JNI call
// into activity state from here is the cross-thread shape this repo has been
// bitten by. Any thread.
void CocoaSetTopActivityConfigChanges(int real_config_changes) {
    const bool handles = winpolicy::HandlesSideChange(real_config_changes);
    dispatch_main_async_void(^{
        if (g_wp.live.handles_side_change == handles) return;
        g_wp.live.handles_side_change = handles;
        if (g_wp.active)
            std::fprintf(stderr, "[I] cocoa: top activity configChanges 0x%x: %s orientation+screenSize "
                         "(a drag %s its side while relaunch is off)\n", real_config_changes,
                         handles ? "handles" : "does not handle", handles ? "may change" : "keeps");
    });
}

void CocoaRunLoop() {
    if (!g_initialized.load()) CocoaInit();
    // Optional debug-autoclick: synthetic mouse-down/up sequence(s),
    // scheduled relative to runloop start. Format of the env var is one
    // or more semicolon-separated entries:
    //
    //   MACAPK_DEBUG_AUTOCLICK=t            click view-center at t seconds
    //   MACAPK_DEBUG_AUTOCLICK=t:x,y        click at view-point (x,y) at t s
    //   MACAPK_DEBUG_AUTOCLICK=t1;t2:x,y;t3 multiple events, ordered
    //
    // View-point (x,y) uses the MTKView's bounds (points, AppKit y-up).
    // Stored as a static schedule that drawInMTKView checks each frame --
    // we used to use dispatch_after but cocos2d-x rendering can saturate
    // the main queue, leaving GCD timers starved past the schedule point.
    // Tick-based firing is reliable regardless of main-queue load.
    if (const char* env_str = std::getenv("MACAPK_DEBUG_AUTOCLICK")) {
        const char* p = env_str;
        while (*p) {
            while (*p == ' ' || *p == ';') ++p;
            if (!*p) break;
            char* end = nullptr;
            double t = std::strtod(p, &end);
            if (end == p) break;
            p = end;
            double x = -1.0, y = -1.0;
            if (*p == ':') {
                ++p;
                x = std::strtod(p, &end);
                if (end == p) break;
                p = end;
                if (*p == ',') {
                    ++p;
                    y = std::strtod(p, &end);
                    if (end == p) { x = -1.0; break; }
                    p = end;
                }
            }
            if (t > 0.0) {
                g_autoclicks.push_back({t, x, y, false, false, 0.0});
            }
            while (*p && *p != ';') ++p;
        }
        if (!g_autoclicks.empty()) {
            std::fprintf(stderr,
                "[touch] autoclick schedule: %zu entries (tick-driven)\n",
                g_autoclicks.size());
        }
    }
    // MACAPK_DEBUG_AUTOKEY="<t>:<chars>" -- at t seconds, type each
    // character in <chars> (DOWN+UP, staggered 0.15s) through the same
    // dispatchKey path the real keyDown: handler uses. Digits/letters/enter.
    if (const char* ks = std::getenv("MACAPK_DEBUG_AUTOKEY")) {
        double t0 = std::strtod(ks, nullptr);
        const char* colon = std::strchr(ks, ':');
        if (colon && t0 > 0.0) {
            int i = 0;
            for (const char* s = colon + 1; *s; ++s, ++i) {
                char c = *s;
                int kc = 0, uni = (int)(unsigned char)c;
                if      (c >= '0' && c <= '9') kc = 7  + (c - '0');
                else if (c >= 'a' && c <= 'z') kc = 29 + (c - 'a');
                else if (c >= 'A' && c <= 'Z') kc = 29 + (c - 'A');
                else if (c == '_')             { kc = 66; uni = 0; }  // _ => ENTER
                g_autokeys.push_back({t0 + i * 0.15, kc, uni, false});
            }
            std::fprintf(stderr,
                "[key] autokey schedule: %zu keystrokes starting t=%.1f\n",
                g_autokeys.size(), t0);
        }
    }
    @autoreleasepool {
        [g_app run];
    }
}

static NSString* macapk_titled(NSString* t);   // MACAPK_WINDOW_TITLE_PREFIX, below
void* CocoaCreateWindow(const char* title) {
    if (!g_initialized.load()) CocoaInit();
    __block NSWindow* w = nil;
    // Called from JVM threads with no draining pool (the v1.0.952
    // CocoaPresentBitmap rule): ns_title is autoreleased on this thread.
    // The window itself is a +1 alloc the pool cannot touch, and the body
    // runs synchronously, so draining on scope exit strands nothing.
    @autoreleasepool {
        NSString* ns_title = macapk_titled(title ? [NSString stringWithUTF8String:title] : @"Mac.apk");
        NSRect frame = NSMakeRect(0, 0, g_phone_w, g_phone_h);
        NSWindowStyleMask style = NSWindowStyleMaskTitled
                                | NSWindowStyleMaskClosable
                                | NSWindowStyleMaskMiniaturizable;
        void (^body)(void) = ^{
            w = [[NSWindow alloc] initWithContentRect:frame
                                            styleMask:style
                                              backing:NSBackingStoreBuffered
                                                defer:NO];
            [w setTitle:ns_title];
            [w center];
            macapk_apply_secure_sharing_on_create(w);   // FLAG_SECURE; no-op unless asked
        };
        if ([NSThread isMainThread]) body();
        else dispatch_sync(dispatch_get_main_queue(), body);
    }
    // v1.0.86: was `__bridge_retained` which is a no-op without ARC and
    // produces a -Warc-bridge-casts-disallowed-in-nonarc warning. The
    // window's lifetime here is managed by the `[[NSWindow alloc] init]`
    // call above (returns a +1 retained object) and the explicit
    // `[w release]` in CocoaCloseWindow. Plain `__bridge` matches the
    // actual semantics.
    return (__bridge void*)w;
}

// Globals captured when the touch-dispatcher Java side calls registerTouchDispatcher.
static JavaVM*   g_touch_vm  = nullptr;
static jobject   g_touch_dispatcher = nullptr; // global ref to com.macapk.loader.TouchDispatcher
static jmethodID g_touch_mid = nullptr;        // dispatch(int action, float x, float y, float w, float h)
static jmethodID g_key_mid   = nullptr;        // dispatchKey(int action, int keyCode, int unicodeChar, int metaState)
static jmethodID g_key_rep_mid = nullptr;      // dispatchHostKey(int action, int keyCode, int unicodeChar, int metaState, boolean autoRepeat)

// v1.0.1047: enough of the in-flight gesture to synthesize ACTION_CANCEL when
// the render view is torn out from under it.
//
// THE BUG: CocoaSetWindowContentView removes the MTKView mid-gesture on an
// activity swap, so a DOWN that has already been delivered to Java never gets
// its matching UP -- measured twice in one F-Droid run (a DOWN at the tap that
// starts AppListActivity, and again for AppDetailsActivity, both with no UP).
// The framework is then left believing a finger is still down: a stuck pressed
// state, and a ViewGroup holding a touch target that no longer exists.
//
// AOSP never does this silently. InputDispatcher/ViewRootImpl synthesize
// ACTION_CANCEL when a gesture's target goes away, and MotionEvent's own
// documentation is explicit that ACTION_CANCEL means "the gesture was aborted,
// do not treat it as a click"
// (developer.android.com/reference/android/view/MotionEvent#ACTION_CANCEL).
// Silence is the one thing a device never sends. TouchDispatcher already
// handles action 3 (TouchDispatcher.java:868, "ACTION_CANCEL -> release it"),
// so nothing new is needed on the Java side.
static bool    g_touch_is_down = false;
static CGFloat g_touch_last_x = 0, g_touch_last_y = 0;
static CGFloat g_touch_last_w = 0, g_touch_last_h = 0;

/** v1.0.1047: tell Java the macOS appearance changed, so it can drive a uiMode
 *  Configuration change (Boot.macapkOnSystemAppearanceChanged owns the opt-out
 *  and the no-op-if-unchanged test). Called from the KVO observer on the AppKit
 *  main thread; the Java side marshals to the Android main Looper itself. */
void macapk_notify_appearance_changed(bool dark) {
    JavaVM* vm = nullptr;
    jsize n_vms = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &n_vms) != JNI_OK || n_vms <= 0 || !vm) return;
    JNIEnv* env = nullptr;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED)
        vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
    if (!env) return;
    static jclass    s_cls = nullptr;
    static jmethodID s_mid = nullptr;
    if (!s_cls) {
        jclass c = env->FindClass("com/macapk/loader/Boot");
        if (!c) {
            env->ExceptionClear();
            std::fprintf(stderr, "[W] appearance: Boot not found -- the system"
                                 " appearance will not follow live\n");
            return;
        }
        s_mid = env->GetStaticMethodID(c, "macapkOnSystemAppearanceChanged", "(Z)V");
        if (!s_mid) {
            env->ExceptionClear();
            std::fprintf(stderr, "[W] appearance: Boot."
                "macapkOnSystemAppearanceChanged missing -- the framework jar is"
                " older than this runtime binary\n");
            env->DeleteLocalRef(c);
            return;
        }
        s_cls = (jclass) env->NewGlobalRef(c);
        env->DeleteLocalRef(c);
    }
    if (!s_cls || !s_mid) return;
    std::fprintf(stderr, "[macapk] macOS appearance changed -> %s\n",
                 dark ? "DARK" : "light");
    env->CallStaticVoidMethod(s_cls, s_mid, (jboolean)(dark ? JNI_TRUE : JNI_FALSE));
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

/** v1.0.1642: the app was unhidden (Dock click, Cmd+Tab, the stub's
 *  re-activation). ActivityHost.macapkTaskToFront is a no-op unless the
 *  Android task was sent to the back by the runtime (BACK at the launcher root,
 *  Activity.moveTaskToBack, or an emptied task); then it resumes the task, or
 *  starts the launcher activity again if the task had emptied. It marshals to
 *  the Android main Looper itself. */
static void macapk_notify_task_to_front() {
    JavaVM* vm = nullptr;
    jsize n_vms = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &n_vms) != JNI_OK || n_vms <= 0 || !vm) return;
    JNIEnv* env = nullptr;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED)
        vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
    if (!env) return;
    static jclass    s_cls = nullptr;
    static jmethodID s_mid = nullptr;
    if (!s_cls) {
        jclass c = env->FindClass("com/macapk/loader/ActivityHost");
        if (!c) { env->ExceptionClear(); return; }
        s_mid = env->GetStaticMethodID(c, "macapkTaskToFront", "()V");
        if (!s_mid) { env->ExceptionClear(); env->DeleteLocalRef(c); return; }
        s_cls = (jclass) env->NewGlobalRef(c);
        env->DeleteLocalRef(c);
    }
    env->CallStaticVoidMethod(s_cls, s_mid);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

/** The app became active. IntentInbox.macapkOnActivated posts a drain of this
 *  bundle's intent inbox to the Android main Looper; it is a directory listing
 *  of an almost always empty directory, and a no-op outside a bundle. */
static void macapk_notify_app_activated() {
    JavaVM* vm = nullptr;
    jsize n_vms = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &n_vms) != JNI_OK || n_vms <= 0 || !vm) return;
    JNIEnv* env = nullptr;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED)
        vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
    if (!env) return;
    static jclass    s_cls = nullptr;
    static jmethodID s_mid = nullptr;
    if (!s_cls) {
        jclass c = env->FindClass("com/macapk/loader/IntentInbox");
        if (!c) { env->ExceptionClear(); return; }
        s_mid = env->GetStaticMethodID(c, "macapkOnActivated", "()V");
        if (!s_mid) { env->ExceptionClear(); env->DeleteLocalRef(c); return; }
        s_cls = (jclass) env->NewGlobalRef(c);
        env->DeleteLocalRef(c);
    }
    env->CallStaticVoidMethod(s_cls, s_mid);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

/** A host URL was queued (see "Host URL intake"). HostUrlIntake.macapkOnHostUrl
 *  posts a drain to the Android main Looper once Boot has chosen the root; before
 *  that the URL waits in the queue and Boot takes it. No JVM yet: nothing to do. */
static void macapk_notify_host_url() {
    JavaVM* vm = nullptr;
    jsize n_vms = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &n_vms) != JNI_OK || n_vms <= 0 || !vm) return;
    JNIEnv* env = nullptr;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED)
        vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
    if (!env) return;
    static jclass    s_cls = nullptr;
    static jmethodID s_mid = nullptr;
    if (!s_cls) {
        jclass c = env->FindClass("com/macapk/loader/HostUrlIntake");
        if (!c) { env->ExceptionClear(); return; }
        s_mid = env->GetStaticMethodID(c, "macapkOnHostUrl", "()V");
        if (!s_mid) { env->ExceptionClear(); env->DeleteLocalRef(c); return; }
        s_cls = (jclass) env->NewGlobalRef(c);
        env->DeleteLocalRef(c);
    }
    env->CallStaticVoidMethod(s_cls, s_mid);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

void CocoaMoveTaskToBack() {
    dispatch_async(dispatch_get_main_queue(), ^{
        std::fprintf(stderr, "[task] moved to back -> app hidden\n");
        std::fflush(stderr);
        [NSApp hide:nil];
    });
}

void CocoaMoveTaskToFront() {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (![NSApp isHidden]) return;
        std::fprintf(stderr, "[task] brought to front by a launch -> app unhidden\n");
        std::fflush(stderr);
        [NSApp unhide:nil];
    });
}

// FLAG_SECURE mirror entry (see "FLAG_SECURE -> NSWindow.sharingType" near the
// top of this file). Any thread; the AppKit work runs on the main thread, and
// the LATEST request wins when pushes race (the block re-reads the atomic).
static void CocoaSetSecureContent(bool secure) {
    const int prev = g_secure_requested.exchange(secure ? 1 : 0);
    if (!MacapkSecureSharingEnabled()) {
        if (secure && prev == 0) {
            std::fprintf(stderr, "[window] FLAG_SECURE requested; "
                         "MACAPK_SECURE_WINDOW_SHARING=0 -> not mirrored to "
                         "NSWindow.sharingType (pre-change behaviour)\n");
            std::fflush(stderr);
        }
        return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        const bool want = g_secure_requested.load() != 0;
        // A window this process shows LATER (a sign-in or dialog-hosting
        // window) picks the flag up when it becomes key. Registered on the
        // first secure request only, so a run that never asks adds nothing.
        static bool s_observing = false;
        if (want && !s_observing) {
            s_observing = true;
            [[NSNotificationCenter defaultCenter]
                addObserverForName:NSWindowDidBecomeKeyNotification
                            object:nil
                             queue:[NSOperationQueue mainQueue]
                        usingBlock:^(NSNotification* note) {
                    if (g_secure_requested.load() == 0) return;
                    id obj = [note object];
                    if ([obj isKindOfClass:[NSWindow class]] &&
                        macapk_apply_secure_sharing((NSWindow*)obj)) {
                        std::fprintf(stderr, "[window] FLAG_SECURE -> sharingType="
                                     "None on a window that became key (%p)\n",
                                     (void*)obj);
                    }
                }];
        }
        unsigned long changed = 0, total = 0;
        if (want) {
            for (NSWindow* w in [NSApp windows]) {
                total++;
                if (macapk_apply_secure_sharing(w)) changed++;
            }
        } else {
            total = g_secure_windows ? (unsigned long)[g_secure_windows count] : 0;
            changed = macapk_clear_secure_sharing();
        }
        // Main-thread only. Log TRANSITIONS, not every resume's push; starts
        // at "cleared" so a run that never sets the flag logs nothing.
        static int s_logged = 0;
        if (changed || s_logged != (want ? 1 : 0)) {
            s_logged = want ? 1 : 0;
            std::fprintf(stderr, "[window] FLAG_SECURE %s -> sharingType=%s on %lu "
                         "of %lu window(s)\n", want ? "set" : "cleared",
                         want ? "None" : "ReadOnly", changed, total);
            std::fflush(stderr);
        }
    });
}

namespace cocoasecure {
static void JNICALL NativeCocoa_setSecureContent(JNIEnv*, jclass, jboolean secure) {
    CocoaSetSecureContent(secure == JNI_TRUE);
}

const JNINativeMethod kSecureContentMethods[] = {
    {const_cast<char*>("setSecureContent"), const_cast<char*>("(Z)V"),
     reinterpret_cast<void*>(NativeCocoa_setSecureContent)},
};

// com.macapk.loader.NativeCocoa.setSecureContent(boolean). SOFT-FAILS, like the
// clipboard / webview registrations, and deliberately NOT a row in
// native_methods.cpp's kNativeCocoaMethods: Bind() aborts the process when
// RegisterNatives meets a name the class does not declare, so a framework jar
// older than this binary would kill boot. Here a miss logs once and leaves
// FLAG_SECURE unmirrored; the Java caller catches UnsatisfiedLinkError for the
// opposite skew (a jar newer than this binary).
void RegisterSecureContentMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/NativeCocoa");
    if (!cls) {
        env->ExceptionClear();
        std::fprintf(stderr, "[W] window: FindClass(NativeCocoa) failed; "
                     "FLAG_SECURE is not mirrored this run\n");
        return;
    }
    if (env->RegisterNatives(cls, kSecureContentMethods,
            sizeof(kSecureContentMethods) / sizeof(kSecureContentMethods[0]))
            != JNI_OK) {
        env->ExceptionClear();
        // [I], not [W]: a jar that predates the Java half is an expected build
        // skew (the Java patches can land a build later than this binary), and
        // a warning printed on EVERY boot of EVERY app would sit in the triage
        // path of runs that have nothing to do with FLAG_SECURE.
        std::fprintf(stderr, "[I] window: this framework jar does not declare "
                     "NativeCocoa.setSecureContent(Z)V; FLAG_SECURE mirror "
                     "inactive this run\n");
    }
    env->DeleteLocalRef(cls);
}
}  // namespace cocoasecure

/** Deliver ACTION_CANCEL for the gesture in flight, if any. No-op when no
 *  touch is down, so callers may fire it unconditionally on any teardown. */
static void macapk_cancel_active_touch(const char* why) {
    if (!g_touch_is_down) return;
    g_touch_is_down = false;
    if (!g_touch_vm || !g_touch_dispatcher || !g_touch_mid) return;
    JNIEnv* env = nullptr;
    if (g_touch_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21)
            != JNI_OK || !env) {
        return;   // not on an attached thread; the swap runs on the main one
    }
    std::fprintf(stderr, "[touch] synthesizing ACTION_CANCEL (%s)\n", why);
    env->CallVoidMethod(g_touch_dispatcher, g_touch_mid, (jint) 3,
                        (jfloat) g_touch_last_x, (jfloat) g_touch_last_y,
                        (jfloat) g_touch_last_w, (jfloat) g_touch_last_h);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

// Bracket the ONE synchronous JNI call per input event (dispatchTouchAction:/
// dispatchKeyRawAction: below) so bionic_shim.cpp's MUTEX_FAIR boot/steady-state tax does not
// throttle the AppKit-main/JVM-main thread the user is watching for a response -- see the
// MACAPK_INPUT_FAIR_EXEMPT block in bionic_shim.cpp (above macapk_xs_pump_thread) for the full
// root-cause writeup (2026-07-23, Crossy Road "input very slow and laggy"). no-ops if the shim
// isn't loaded yet (e.g. the very first event before dlopen completes) -- input just isn't
// exempted for that one call, no functional loss.
static inline void MacApkInputPriorityBegin() {
    static void (*fn)() = (void (*)()) dlsym(RTLD_DEFAULT, "__macapk_input_priority_begin");
    if (fn) fn();
}
static inline void MacApkInputPriorityEnd() {
    static void (*fn)() = (void (*)()) dlsym(RTLD_DEFAULT, "__macapk_input_priority_end");
    if (fn) fn();
}

// Shared helper: create + configure an MTKView. The two public
// entrypoints differ only in whether they update the Canvas overlay
// target pointer.
// ---------------------------------------------------------------------------
// MACAPK_FRAME_TRACE=1 -- per-frame INTERVAL trace (frame-pacing diagnosis).
//
// WHY a separate probe when MACAPK_FRAME_STATS already exists: FRAME_STATS
// reports a 5-second MEAN (plus min/worst). A frame-pacing defect can leave the
// mean exactly correct while the DISTRIBUTION is bimodal -- which is precisely
// the shape of the decimator defect this was written to measure (a ~74 Hz
// MTKView source decimated to a 60 Hz AVERAGE emits intervals of one or two
// source periods, ~13.5 / ~27 ms, so every mean-FPS instrument in this tree
// reads a perfect 60.0 while the owner sees judder). Percentiles need the raw
// samples, so this dumps them.
//
// Cost control: samples are buffered and emitted as ONE line per 600 samples
// (~10 s at 60 Hz), never one write per frame -- a per-frame fprintf on the main
// thread would perturb the very interval being measured.
// Consumer: debug/framepace.py (parses "[frame-trace] <tag> n=.. dt_us=..").
// Each MacapkPaceTrace is written from ONE thread (both instances here are
// main-thread only), so it needs no locking.
// ---------------------------------------------------------------------------
struct MacapkPaceTrace { const char* tag; double prev; unsigned n; unsigned dt_us[600]; };

// t_end = the host uptime (CACurrentMediaTime, i.e. mach_absolute_time in
// seconds) of the batch's LAST sample. Every per-sample time is recoverable from
// it (t_i = t_end - the deltas after i), on the SAME clock as the guest-swap
// batch's t_end (bionic_shim.cpp, CLOCK_UPTIME_RAW) and HotSpot's -Xlog
// `timenanos` decorator (os::javaTimeNanos on BSD), so debug/frame_hitch_stats.py
// can line up a guest hitch against a display stall, a safepoint or a JIT event.
// Appended AFTER dt_us, so debug/framepace.py's parse is unchanged.
static void MacapkPaceFlush(MacapkPaceTrace* t, double t_end) {
    if (!t->n) return;
    // Not `static`: "display" (main thread, drawInMTKView) and "drive"
    // (macapk-vsync thread, MacapkDriveTickAt) can both flush around the
    // same time once MACAPK_VSYNC_THREAD moved the tick off the main
    // thread, and a shared static buffer let one batch's [frame-trace]
    // line get garbled by the other's concurrent snprintf. 5.5 KB of
    // stack is fine on both callers (review issue 1).
    char line[600 * 9 + 64];
    int off = 0;
    for (unsigned i = 0; i < t->n && off < (int)sizeof(line) - 12; ++i)
        off += std::snprintf(line + off, sizeof(line) - (size_t)off,
                             i ? ",%u" : "%u", t->dt_us[i]);
    std::fprintf(stderr, "[frame-trace] %s n=%u dt_us=%s t_end=%.6f\n", t->tag, t->n, line,
                 t_end);
    std::fflush(stderr);
    t->n = 0;
}

static void MacapkPaceSample(MacapkPaceTrace* t, double now) {
    if (t->prev > 0.0) {
        double d = (now - t->prev) * 1e6;          // seconds -> microseconds
        if (d < 0.0) d = 0.0;
        if (d > 4.0e9) d = 4.0e9;                  // clamp a suspend/resume gap
        t->dt_us[t->n++] = (unsigned)d;
        if (t->n >= (unsigned)(sizeof(t->dt_us) / sizeof(t->dt_us[0])))
            MacapkPaceFlush(t, now);
    }
    t->prev = now;
}

static bool MacapkFrameTraceOn(void) {
    static bool on = []{
        const char* e = std::getenv("MACAPK_FRAME_TRACE");
        return e && e[0] == '1';
    }();
    return on;
}

// MACAPK_MAIN_STALL_MS=<N> (diagnostic, default OFF; 2026-09-28, lane
// subway-frame-hitches). WHERE the AppKit main thread is when it goes N ms
// without entering drawInMTKView. An interval trace ("display" above) proves
// THAT the display tick was late; it cannot say what the main thread was doing,
// and `sample` cannot either (it aggregates, and a 10 s sample of a runtime
// stalls the whole process for ~15 s, measured). A watchdog thread polls a
// heartbeat every 2 ms; once it is N ms stale it suspends the main thread,
// reads its PC/LR/FP (PAC-stripped by the arm_thread_state64 accessors), walks
// the frame-pointer chain inside the main thread's own stack bounds, resumes it,
// and only THEN symbolizes with dladdr (dladdr takes the dyld lock, which the
// suspended thread might hold). Up to 6 samples per stall 8 ms apart, then one
// a second up to 20 (so a hang still shows where it sits), each
// printed as
//   [main-stall] +<ms since last tick> #<k>: sym+off <- sym+off <- ...
// and one "[main-stall] ended after <ms>" line. Frames in JIT'd Java code have
// no symbol and print as raw addresses. Zero cost when unset (one getenv).
static std::atomic<double> g_main_beat{0.0};
static mach_port_t g_main_mach = MACH_PORT_NULL;
static void MacapkMainStallSymbolize(const uint64_t* pcs, int n, double age_ms, int k) {
    char line[4096];
    int off = std::snprintf(line, sizeof line, "[main-stall] +%.1fms #%d:", age_ms, k);
    for (int i = 0; i < n && off < (int)sizeof(line) - 160; ++i) {
        Dl_info di;
        const char* nm = nullptr;
        uint64_t base = 0;
        if (dladdr((const void*)(uintptr_t)pcs[i], &di) && di.dli_sname) {
            nm = di.dli_sname;
            base = (uint64_t)(uintptr_t)di.dli_saddr;
        }
        if (nm)
            off += std::snprintf(line + off, sizeof(line) - (size_t)off, "%s %.100s+%llu",
                                 i ? " <-" : "", nm, (unsigned long long)(pcs[i] - base));
        else
            off += std::snprintf(line + off, sizeof(line) - (size_t)off, "%s 0x%llx",
                                 i ? " <-" : "", (unsigned long long)pcs[i]);
    }
    std::fprintf(stderr, "%s\n", line);
    std::fflush(stderr);
}
static void MacapkMainStallWatch(double thr_ms) {
    std::thread([thr_ms]() {
        pthread_setname_np("macapk-main-stall");
        pthread_t mainth = nullptr;
        // pthread_from_mach_thread_np maps the port captured on the main
        // thread back to its pthread, for its stack bounds.
        mainth = pthread_from_mach_thread_np(g_main_mach);
        const uintptr_t hi = mainth ? (uintptr_t)pthread_get_stackaddr_np(mainth) : 0;
        const uintptr_t lo = mainth ? hi - pthread_get_stacksize_np(mainth) : 0;
        double sampled_beat = -1.0, last_sample = 0.0, stall_start_beat = -1.0;
        int k = 0;
        for (;;) {
            usleep(2000);
            const double beat = g_main_beat.load(std::memory_order_acquire);
            const double now = CACurrentMediaTime();
            if (beat <= 0.0) continue;
            const double age_ms = (now - beat) * 1e3;
            if (stall_start_beat > 0.0 && beat != stall_start_beat) {
                std::fprintf(stderr, "[main-stall] ended after %.1fms (%d samples)\n",
                             (beat - stall_start_beat) * 1e3, k);
                std::fflush(stderr);
                stall_start_beat = -1.0;
            }
            if (age_ms < thr_ms) continue;
            if (beat != sampled_beat) { sampled_beat = beat; k = 0; stall_start_beat = beat; }
            // 6 samples 8 ms apart, then one a second (up to 20): a long stall
            // (a hang) still says where it sits after the first 100 ms.
            if (k >= 20 || (now - last_sample) * 1e3 < (k < 6 ? 8.0 : 1000.0)) continue;
            last_sample = now;
            uint64_t pcs[48];
            int n = 0;
            if (thread_suspend(g_main_mach) != KERN_SUCCESS) continue;
            arm_thread_state64_t st;
            mach_msg_type_number_t cnt = ARM_THREAD_STATE64_COUNT;
            if (thread_get_state(g_main_mach, ARM_THREAD_STATE64, (thread_state_t)&st, &cnt)
                    == KERN_SUCCESS) {
                pcs[n++] = (uint64_t)arm_thread_state64_get_pc(st);
                pcs[n++] = (uint64_t)arm_thread_state64_get_lr(st);
                uintptr_t fp = (uintptr_t)arm_thread_state64_get_fp(st);
                while (n < 48 && fp >= lo && fp + 16 <= hi && (fp & 7) == 0) {
                    const uintptr_t next = ((const uintptr_t*)fp)[0];
                    const uint64_t ret = ((const uint64_t*)fp)[1];
                    const uint64_t pc = (uint64_t)(uintptr_t)
                        ptrauth_strip((void*)(uintptr_t)ret, ptrauth_key_return_address);
                    if (pc == 0) break;
                    pcs[n++] = pc;
                    if (next <= fp) break;
                    fp = next;
                }
            }
            thread_resume(g_main_mach);
            MacapkMainStallSymbolize(pcs, n, age_ms, ++k);
        }
    }).detach();
}
static void MacapkMainStallBeat(double now) {
    static const double thr = []{
        const char* e = std::getenv("MACAPK_MAIN_STALL_MS");
        const double v = e ? std::atof(e) : 0.0;
        return (v >= 5.0 && v <= 10000.0) ? v : 0.0;
    }();
    if (thr <= 0.0) return;
    static bool started = false;
    if (!started) {
        started = true;
        g_main_mach = mach_thread_self();
        MacapkMainStallWatch(thr);
        std::fprintf(stderr, "[main-stall] watchdog armed: main thread > %.0f ms without a"
                     " display tick is sampled (MACAPK_MAIN_STALL_MS)\n", thr);
    }
    g_main_beat.store(now, std::memory_order_release);
}

// MACAPK_VSYNC_MATCH_PANEL (default ON) -- ask the MTKView display link for the
// panel's MEASURED refresh rate instead of a hardcoded 120.
//
// ⚠ THIS IS NOT A FRAME-PACING FIX, AND IT WAS MEASURED NEUTRAL. It was written
// to fix a hypothesised "decimator beat" and the hypothesis was REFUTED by the
// A/B that tested it. Recorded here so nobody re-derives it:
//
//   THE HYPOTHESIS. v1.0.568's comment (still a few thousand lines below) states
//   the display link "free-runs at ~74/s (preferredFramesPerSecond=120 above the
//   60 Hz panel; MTKView does NOT clamp to the panel as the old comment
//   assumed)", and an accumulator gate then decimates that to a 60 Hz AVERAGE.
//   Decimating ~74 Hz to 60 Hz cannot synthesize a 16.67 ms period -- it emits
//   whole multiples of the source period -- so the guest's frames should land at
//   ~13.5 / ~27 ms in a wandering beat with a perfectly correct mean.
//
//   THE MEASUREMENT (2026-08-17, Subway Surfers 3.62.0, 2 reps x 130 s per arm,
//   same binary, MACAPK_FRAME_TRACE=1 + debug/framepace.py). With this gate OFF
//   -- i.e. preferredFramesPerSecond still 120 -- the raw display-link interval
//   is mean 16.70 ms, p50 16.66, 59.9 Hz, stdev 3.53. That IS the panel refresh.
//   Turning the gate ON changed nothing (16.71 / 16.66 / 59.9 / 3.53).
//
//   CONCLUSION: on this host and OS today, MTKView DOES clamp preferredFrames-
//   PerSecond to the display refresh, so the "~74/s free-run" premise is stale
//   and there is no 74->60 decimation and no beat. The accumulator is decimating
//   a 60 Hz source to 60 Hz, i.e. running 1:1, which is harmless.
//
// WHY THE CHANGE IS KEPT ANYWAY. Asking a 60 Hz panel for 120 is still wrong,
// and the premise only holds while the ratio is an integer. On a panel whose
// refresh is NOT a multiple of the 60 Hz drive cadence (a 144 Hz or 75 Hz
// external monitor, where MTKView must pick some other divisor) the decimation
// ratio becomes fractional and the beat the hypothesis described WOULD appear.
// Matching the measured panel rate makes the source vsync-locked by construction
// and costs nothing when the rate already agrees. It is defensive, not curative.
// UNTESTED on ProMotion/external panels -- this box has one 60 Hz display.
//
// The real cause of the owner's "frame pacing is really random" is downstream
// and is NOT fixed by anything here: the guest's own present is unquantized. See
// docs/subway-frame-pacing-refuted-and-root-caused_2026-08-17.md.
//
// AOSP contract for the cadence itself (unchanged): Choreographer receives one
// callback per hardware vsync at the display refresh rate, and Choreographer.java
// derives its frame interval from android.view.Display.getRefreshRate(), which
// this tree returned as a hardcoded 60f until v1.0.1102. So the drive cadence
// must equal what the guest is told -- driving faster than we report is exactly
// the v1.0.568 "gameplay too fast" defect.
// v1.0.1102 keeps that invariant but stops pinning it to 60: Display.getRefreshRate(),
// MTKView.preferredFramesPerSecond and the drive cadence all now read the ONE function
// CocoaDisplayHz() (measured panel rate, clamped to MACAPK_MAX_FPS, default 120), so they
// move together on a 120 Hz panel and cannot diverge. A 60 Hz host is bit-identical to before.
//
// App-agnostic by construction: the rate is MEASURED from the screen the window
// is actually on, never assumed and never keyed to an app.
static bool MacapkVsyncMatchPanel(void) {
    static bool on = []{
        const char* e = std::getenv("MACAPK_VSYNC_MATCH_PANEL");
        return !(e && e[0] == '0');   // default ON; "0" restores the 120 free-run
    }();
    return on;
}

// Measured refresh rate of the screen `view` is on (falls back to the main
// screen, then to 60 if AppKit reports something implausible). Re-read rather
// than cached so moving the window to an external monitor, or a mode change, is
// picked up -- drawInMTKView re-applies this periodically.
//
// THE FRAME-RATE CEILING (owner request, v1.0.1102): "cap Unity games and all other games to
// 120fps; ideal set to the same refresh rate as the monitor it's on". Both halves live here:
// the rate is MEASURED from the screen the window is actually on, then CLAMPED to
// MACAPK_MAX_FPS (default 120). A 60 Hz panel therefore reports 60, a ProMotion/144/240 panel
// reports 120, and no panel can drive us above the cap.
//
// ⚠ THIS FUNCTION IS THE SINGLE SOURCE OF THE RATE, AND THAT IS LOAD-BEARING. Three things must
// agree or the guest's simulation speed breaks: (a) MTKView.preferredFramesPerSecond, (b) the
// drive cadence that emits Choreographer ticks + the Swappy present counter, and (c) what
// android.view.Display.getRefreshRate() TELLS the guest. v1.0.568 is the standing proof: the
// drive ran at the raw ~74/s display-link rate while the guest was told 60, Unity advanced its
// frame clock ~1.2x too fast, and the owner reported "gameplay too fast". Before v1.0.1102 the
// tree avoided that by hardcoding BOTH to 60 -- correct, but it pinned a 120 Hz panel to 60.
// Now all three read THIS function, so they cannot drift apart by construction. If you add a
// fourth consumer of the refresh rate, route it here too; do not re-measure NSScreen yourself.
static std::atomic<int> g_panel_hz{0};   // last measured+capped rate, for off-main-thread readers

static int MacapkMaxFps() {
    static int v = []{
        const char* e = std::getenv("MACAPK_MAX_FPS");
        int n = e ? atoi(e) : 120;
        return (n >= 24 && n <= 480) ? n : 120;
    }();
    return v;
}

// Announce the resolved rate exactly once, from whichever path resolves it first. This single
// number is what the guest is told AND what we pace at, so it is the first thing to check when a
// game looks too fast, too slow, or stuck at 60 on a 120 Hz panel; it also names the cap, so
// "why does my 144 Hz monitor give 120" is answerable from the log.
static void MacapkAnnounceHzOnce(int hz) {
    static std::atomic<int> announced{0};
    if (announced.fetch_add(1) != 0) return;
    std::fprintf(stderr, "[vsync] display rate %d Hz (cap %d, MACAPK_MAX_FPS) -- paces present + "
                 "guest drive + Display.getRefreshRate()\n", hz, MacapkMaxFps());
    std::fflush(stderr);
}

static int MacapkPanelHz(MTKView* view) {
    NSScreen* scr = view.window.screen;
    if (!scr) scr = [NSScreen mainScreen];
    NSInteger hz = scr ? scr.maximumFramesPerSecond : 0;
    if (hz < 24 || hz > 480) hz = 60;          // AppKit said something implausible
    if (hz > MacapkMaxFps()) hz = MacapkMaxFps();
    g_panel_hz.store((int)hz, std::memory_order_relaxed);
    MacapkAnnounceHzOnce((int)hz);
    return (int)hz;
}

// v1.0.1644: seed g_panel_hz from CocoaInit, i.e. on the AppKit main thread before Boot.run.
//
// THE DEADLOCK THIS CLOSES (Telegram and Signal never got past Application.onCreate; the
// 2026-09-25 fleet A/B, both STALL): Boot.run executes ON the AppKit main thread and parks in
// appInitLatch.await() while Application.onCreate runs on the Android main thread. Both apps ask
// Display.getRefreshRate() from there (Signal: FrameRateTracker.<init> from
// ApplicationContext.onCreate; Telegram: AndroidUtilities.<clinit> -> checkDisplaySize), before any
// MTKView exists, so g_panel_hz was still 0 and CocoaDisplayHz dispatch_sync'ed to the main queue
// -- which was parked waiting for that same onCreate. Latent since 2095fbd0 (2026-08-28), which
// replaced a constant `return 60f` with the measured rate.
static void MacapkSeedPanelHzOnMain() {
    NSScreen* scr = [NSScreen mainScreen];
    NSInteger v = scr ? scr.maximumFramesPerSecond : 0;
    if (v < 24 || v > 480) v = 60;
    if (v > MacapkMaxFps()) v = MacapkMaxFps();
    int expect = 0;
    g_panel_hz.compare_exchange_strong(expect, (int)v, std::memory_order_relaxed);
}

// The same rate, callable from ANY thread (the guest asks via Display.getRefreshRate on its own
// threads). NSScreen is main-thread-only, so serve the value published on the main thread
// (seeded by CocoaInit, then kept current by the drive loop). An off-main reader that still finds
// no value NEVER hops to the main queue -- the main thread may be the one waiting for it (see
// MacapkSeedPanelHzOnMain); it asks CoreGraphics, which is thread-safe, instead.
int CocoaDisplayHz() {
    int hz = g_panel_hz.load(std::memory_order_relaxed);
    if (hz) return hz;
    NSInteger v = 0;
    if ([NSThread isMainThread]) {
        NSScreen* scr = [NSScreen mainScreen];
        v = scr ? scr.maximumFramesPerSecond : 0;
    } else {
        CGDisplayModeRef mode = CGDisplayCopyDisplayMode(CGMainDisplayID());
        if (mode) {
            v = (NSInteger)(CGDisplayModeGetRefreshRate(mode) + 0.5);
            CGDisplayModeRelease(mode);
        }
    }
    if (v < 24 || v > 480) v = 60;   // also the built-in-panel case where CG reports 0
    if (v > MacapkMaxFps()) v = MacapkMaxFps();
    const int measured = (int)v;
    g_panel_hz.store(measured, std::memory_order_relaxed);
    MacapkAnnounceHzOnce(measured);
    return measured;
}

static MTKView* CocoaCreateMetalSurfaceViewImpl(int width_hint, int height_hint) {
    if (!g_initialized.load()) CocoaInit();
    NSRect frame = NSMakeRect(0, 0,
                              width_hint  > 0 ? width_hint  : g_phone_w,
                              height_hint > 0 ? height_hint : g_phone_h);
    SFTRACE("CreateMetalSurfaceViewImpl hint=%dx%d phone=%dx%d frame=%.0fx%.0f "
            "mainThreadFastPath=%d",
            width_hint, height_hint, g_phone_w, g_phone_h,
            frame.size.width, frame.size.height,
            [NSThread isMainThread] ? 1 : 0);
    __block MTKView* view = nil;
    void (^body)(void) = ^{
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        Class viewClass = NSClassFromString(@"MacApkMetalView");
        if (!viewClass) viewClass = [MTKView class]; // safe fallback
        view = [[viewClass alloc] initWithFrame:frame device:device];
        view.clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
        view.colorPixelFormat = MTLPixelFormatBGRA8Unorm;
        // Present at the panel's MEASURED refresh rate rather than a hardcoded
        // 120. Measured NEUTRAL on this 60 Hz box (MTKView already clamped);
        // kept as defence for a panel whose refresh is not a multiple of the
        // 60 Hz drive cadence. NOT the frame-pacing fix -- see the block above.
        // MACAPK_VSYNC_MATCH_PANEL=0 restores the old unconditional 120.
        view.preferredFramesPerSecond =
            MacapkVsyncMatchPanel() ? MacapkPanelHz(view) : 120;
        view.enableSetNeedsDisplay = NO;
        view.paused = NO;
    };
    if ([NSThread isMainThread]) body();
    else dispatch_sync(dispatch_get_main_queue(), body);
    return view;
}

// The host-KIND tag webview_bridge.mm reads (WvHostKind, for the WebView page
// base): is this MTKView's framework angleLayer the guest's GL (the ForGl entry
// point: NO) or an undrawn ANGLE framebuffer under the canvas overlay (the
// canvas-path entry point: YES)? drawInMTKView adds that layer to EVERY view
// with a delegate, so a layer walk cannot tell the two apart by itself, and a
// translucent page over a Path-A view's angleLayer composites to black. Keyed
// by the uniqued selector "macapkCanvasPathView" so the reader needs no
// link-time symbol from this file. Set once, at creation, by the only two
// entry points; nothing else in the runtime reads it.
static void MacapkTagHostKind(MTKView* view, BOOL canvasPath) {
    if (!view) return;
    objc_setAssociatedObject(view, (const void*)sel_registerName("macapkCanvasPathView"),
                             canvasPath ? @YES : @NO, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

void* CocoaCreateMetalSurfaceView(int width_hint, int height_hint) {
    MTKView* view = CocoaCreateMetalSurfaceViewImpl(width_hint, height_hint);
    MacapkTagHostKind(view, YES);
    g_last_metal_view = view;  // canvas overlay target
    g_canvas_host_epoch.fetch_add(1, std::memory_order_acq_rel);  // see its declaration
    // v1.0.86: see CocoaCreateWindow note -- __bridge instead of
    // __bridge_retained (which is a no-op without ARC).
    return (__bridge void*)view;
}

// v1.0.91: alternate entrypoint for SurfaceView's GL-render MTKView.
// Identical construction, but does NOT touch g_last_metal_view. The
// Canvas overlay must NEVER attach to a SurfaceView's MTKView: those
// host ANGLE's CAMetalLayer (the GL render target), and an opaque
// Canvas-overlay bitmap from a previous Path-A Activity (e.g. the
// Flappy splash Activity's white-clear overlay) would land on top
// of the GL render and block it. Keeping g_last_metal_view pointed
// at the latest Path-A MTKView means the overlay stays orphaned on
// the now-invisible Path-A MTKView, and the visible SurfaceView's
// MTKView shows clean GL output.
void* CocoaCreateMetalSurfaceViewForGl(int width_hint, int height_hint) {
    MTKView* view = CocoaCreateMetalSurfaceViewImpl(width_hint, height_hint);
    MacapkTagHostKind(view, NO);
    // Deliberately NOT setting g_last_metal_view here (see above).
    // DO track it as the GL-overlay compositing target (a separate,
    // purpose-built layer -- see CocoaPresentOverlayBitmap) so a real
    // WindowManager Dialog shown over this GL surface has somewhere to
    // composite onto instead of drawing nowhere while still eating touch.
    g_last_gl_metal_view = view;
    // It is also the canvas overlay's fallback host (no Path-A view): see
    // g_canvas_host_epoch.
    g_canvas_host_epoch.fetch_add(1, std::memory_order_acq_rel);
    return (__bridge void*)view;
}

// MacApkDecodeImageBytes: decode an encoded image (webp / heic / png /
// jpeg / ... any CGImageSource-supported format) into an Android
// ARGB_8888 pixel buffer. Returns a malloc'd uint32_t[width*height]
// (caller frees) in 0xAARRGGBB layout, or nullptr if the bytes can't
// be decoded. macOS ImageIO decodes webp natively (since macOS 11),
// which Java's javax.imageio cannot -- so this is the fallback path
// BitmapFactory.decodeStream takes when ImageIO.read returns null.
//
// Pixel format: we render into a CGBitmapContext with
// kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little, whose
// memory layout is B,G,R,A -- which read back as a native-endian int on
// arm64 is exactly Android's 0xAARRGGBB (same convention CocoaPresent
// Bitmap documents below).
extern "C" uint32_t* MacApkDecodeImageBytes(const uint8_t* data, size_t len,
                                            int* outW, int* outH) {
    if (!data || len == 0 || !outW || !outH) return nullptr;
    CFDataRef cf = CFDataCreate(kCFAllocatorDefault, data, (CFIndex)len);
    if (!cf) return nullptr;
    CGImageSourceRef src = CGImageSourceCreateWithData(cf, nullptr);
    CFRelease(cf);
    if (!src) return nullptr;
    CGImageRef img = CGImageSourceCreateImageAtIndex(src, 0, nullptr);
    CFRelease(src);
    if (!img) return nullptr;
    size_t w = CGImageGetWidth(img);
    size_t h = CGImageGetHeight(img);
    // Guard against absurd allocations (a corrupt header reporting huge
    // dimensions). 16384x16384 ARGB = 1 GiB; well past any real drawable.
    if (w == 0 || h == 0 || w > 16384 || h > 16384) {
        CGImageRelease(img);
        return nullptr;
    }
    uint32_t* buf = (uint32_t*)calloc(w * h, sizeof(uint32_t));
    if (!buf) { CGImageRelease(img); return nullptr; }
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGContextRef ctx = CGBitmapContextCreate(
        buf, w, h, 8, w * sizeof(uint32_t), cs,
        kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
    CGColorSpaceRelease(cs);
    if (!ctx) { free(buf); CGImageRelease(img); return nullptr; }
    CGContextDrawImage(ctx, CGRectMake(0, 0, (CGFloat)w, (CGFloat)h), img);
    CGContextRelease(ctx);
    CGImageRelease(img);
    // UN-premultiply back to the straight-alpha convention this pipeline uses.
    //
    // CGBitmapContext CANNOT emit straight alpha -- kCGImageAlphaFirst is not a
    // supported bitmap-context format, only kCGImageAlphaPremultipliedFirst and
    // the ignore/none variants -- so the draw above necessarily produces
    // PREMULTIPLIED pixels. But `buf` is handed straight back into the Java
    // Bitmap pipeline, whose backing store is non-premultiplied end to end
    // (Bitmap.java: "Our backing store is NON-premultiplied end to end
    // (BufferedImage.TYPE_INT_ARGB)").
    //
    // This is the exact INVERSE of the present-path defect fixed at v1.0.1095
    // (macapk_premultiply_argb, which see): there we handed straight alpha to a
    // premultiplied declaration, here we hand premultiplied data to a straight
    // consumer. Both were latent while the two errors cancelled for anything that
    // made a round trip; with the present path now correct, a translucent decoded
    // image (an alpha PNG/webp on the ImageIO fallback) would be DOUBLE-DARKENED
    // at its translucent pixels -- multiplied by alpha once here and once again at
    // present. Fixing only one of the pair is worse than fixing neither.
    //
    // Rounding: +a/2 rounds to nearest, and the result is clamped because a
    // premultiplied source may legally carry c > a (an out-of-gamut or
    // rounding-inflated component), which would otherwise exceed 255.
    for (size_t i = 0, n = w * h; i < n; ++i) {
        const uint32_t p = buf[i];
        const uint32_t a = p >> 24;
        if (a == 0xFFu) continue;            // opaque: already straight
        if (a == 0u)    { buf[i] = 0u; continue; }
        uint32_t r = (((p >> 16) & 0xFFu) * 255u + a / 2u) / a;
        uint32_t g = (((p >>  8) & 0xFFu) * 255u + a / 2u) / a;
        uint32_t b = (( p        & 0xFFu) * 255u + a / 2u) / a;
        if (r > 255u) r = 255u;
        if (g > 255u) g = 255u;
        if (b > 255u) b = 255u;
        buf[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
    *outW = (int)w;
    *outH = (int)h;
    return buf;
}

// CocoaPresentVideoFrame -- the real MediaCodec video present target
// (video_toolbox.mm's macapk_vtb_render_frame, reached from
// MediaCodec.releaseOutputBuffer(index, true) via JNI). This does NOT
// reimplement VideoToolbox-to-Metal compositing: it reuses
// macapk_vd_present_create / macapk_vd_present_to_layer (media_video.mm,
// declared extern above) exactly as the v1.0.485 MACAPK_VIDEO_SELFTEST
// driver further down this file does, and productionizes that self-test's
// inline lazy-layer-creation block into a callable entry point instead of a
// second implementation living alongside it.
//
// Z-order: real Android's SurfaceView composites BELOW the window by default
// (setZOrderOnTop(false) is the AOSP default; SurfaceView.java/Surface.java
// in this tree treat it as a no-op stub, i.e. we always behave as that
// default), with the window punching a transparent "hole" at the
// SurfaceView's bounds so the surface's own content shows through while
// sibling Views drawn after it in the layout (e.g. ExoPlayer's transport
// controls) composite on top. zPosition 0.75 mirrors that: above the
// framework angleLayer (0, GL content) and below the canvas overlay
// (0.5/1.0, the app's normal View-tree chrome painted via
// ViewCanvasDriver/CocoaPresentBitmap below). ViewCanvasDriver already
// clears to fully transparent whenever the tree contains a SurfaceView (see
// its treeHasSurfaceView / "CASE A" comment) -- that existing mechanism is
// what lets the video actually show through the hole; nothing new is needed
// on that side.
/** v1.0.997 bisect gate (default ON), shared by the two host-selection sites
 *  above and by Activity.macapkMaybeRewireNativeSurface's Java half.
 *  `MACAPK_REWIRE_KEEP_CANVAS=0` restores the pre-v1.0.997 behaviour in which a
 *  window-content rewire abandoned the View tree and stranded both the canvas
 *  overlay and the video plane on a detached MTKView. */
static bool MacapkRewireKeepCanvas() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_REWIRE_KEEP_CANVAS");
        return !(e && e[0] == '0' && e[1] == '\0');
    }();
    return on;
}

// v1.0.997 SMEAR FIX (owner report 2026-08-18: "screens just seem laggy when
// going to new tabs and scrolling seems smeary").
//
// A standalone CALayer runs an IMPLICIT ANIMATION for every animatable
// property change committed outside an explicit disable-actions transaction.
// The authority is the local SDK (developer.apple.com is JS-rendered/empty to
// a fetcher, so quote the headers, per this project's convention):
//   QuartzCore/CALayer.h:373-378 -- `contents` is "Animatable.";
//   QuartzCore/CALayer.h:831-842 -- actionForKey: searches 1. the delegate,
//     2. the layer's `actions' dictionary, 3. the style hierarchy, 4. the
//     class +defaultActionForKey:; an NSNull result "is converted to nil";
//   QuartzCore/CATransaction.h:57-59 -- the implicit duration "Defaults to
//     1/4s"; :74-75 -- "Defaults to NO, i.e. implicit animations enabled."
//
// The canvas/GL overlay layers have no delegate and had no `actions'
// dictionary, so every per-frame `layer.contents = image' fell through to the
// class default: a 0.25s crossfade FROM THE CURRENT PRESENTATION VALUE. At a
// ~60 Hz update cadence each new frame advances that fade only ~16.7/250 =
// ~7%, so the screen converges toward the newest frame with a ~250 ms time
// constant -- i.e. what the user sees is an exponentially weighted blend of
// roughly the last 15 frames. During a scroll that is literal ghosting
// (the same text visible at many offsets at once); on a tab switch it is a
// quarter-second dissolve that reads as lag. Every OTHER presenting layer in
// this file (angleLayer, g_unity_layer, the video layers) already suppresses
// the implicit action with an NSNull `actions' dictionary; these two overlays
// were the only per-frame-updated layers without it.
//
// Why no instrument ever saw it: the in-runtime self-capture and frame-score
// read the Java-side ARGB frame BEFORE CoreAnimation composites it, and the
// fleet screencaptures are taken after a ~40s settle, by which time the fade
// has converged. Only a capture DURING motion can see it (and does; see
// docs/canvas-present-smear-and-lag_2026-08-18.md).
//
// MACAPK_OVERLAY_IMPLICIT_FADE=1 restores the old behaviour (one-run A/B).
static bool MacapkOverlayImplicitFade() {
    static const bool fadeOn = [] {
        const char* e = std::getenv("MACAPK_OVERLAY_IMPLICIT_FADE");
        return e && e[0] == '1' && e[1] == '\0';
    }();
    return fadeOn;
}

static CAMetalLayer* g_video_layer         = nil;
static MTKView*      g_video_attached_view = nil;
static void*         g_video_presenter     = nullptr;  // macapk_vd_present_create() handle

// ---------------------------------------------------------------------------
// TextureView routing (added alongside the SurfaceView path above).
//
// THE PROBLEM: NativeMedia.nativeVidRender(pixbufHandle, width, height) --
// the entire signature MediaCodec.releaseOutputBuffer(index, true) calls --
// carries NO Surface or SurfaceTexture identity. So CocoaPresentVideoFrame,
// which is all that call reaches natively, cannot itself distinguish "this
// frame's Surface was a plain SurfaceView" (composite fullscreen through the
// hole, the g_video_layer machinery above) from "this frame's Surface was a
// TextureView's SurfaceTexture" (must instead be blitted into the Canvas the
// widget tree draws into, so it transforms/clips/z-orders with its siblings
// like any other View -- see the compositor answer in
// android/view/TextureView.java's file header). A fullscreen layer literally
// cannot do the second thing: it has no notion of the TextureView's bounds,
// rotation, scale or alpha at all.
//
// THE FIX stays entirely on the Java side (android/graphics/SurfaceTexture.
// java + android/view/Surface.java): `new Surface(surfaceTexture)` -- the
// real (and only) AOSP hand-off point for a producer-facing Surface backed
// by a TextureView's stream (frameworks/base's TextureView has no
// getSurface() of its own at all) -- registers the SurfaceTexture as the
// current candidate target, in Surface's own constructor. This function
// only needs to (a) cheaply ask whether such a target is registered before
// paying for any pixel work, and (b) if so, extract the decoded frame and
// hand it across.
//
// PIXEL EXTRACTION: pixbuf is the same CVPixelBufferRef the proven
// g_video_layer path below hands to macapk_vd_present_to_layer, packed
// 32BGRA (video_toolbox.mm's CreateSessionInternal decode destination
// format -- the same memory macapk_vtb_frame_copy_bgra there copies for the
// no-Surface getOutputImage path). Read directly off CVPixelBufferGetBase
// Address; no GPU texture sampling is involved on this path at all, so
// there is no Y-flip to correct for -- the bytes are already top-down, the
// same orientation Bitmap.setPixels/Canvas.drawBitmap expect. BGRA bytes
// reinterpreted as native-endian int32 on this little-endian arm64 host ARE
// Android's ARGB_8888 int value (0xAARRGGBB) with zero conversion -- the
// same identity MacApkDecodeImageBytes documents earlier in this file.
static bool MacapkOfferDecodedFrameToTextureView(void* pixbuf) {
    // Unqualified: this function sits inside `namespace macapk` (opened
    // line 58, closed line 3299), same span as keybind_set_active's
    // identical GetEnv/attach-if-detached pattern above, which this mirrors.
    JavaVM* vm = g_touch_vm;
    if (!vm) return false;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
            return false;
        attached = true;
    } else if (r != JNI_OK) {
        return false;
    }

    bool offered = false;
    jclass cls = env->FindClass("android/graphics/SurfaceTexture");
    if (!cls) {
        env->ExceptionClear();
    } else {
        // Cheap peek FIRST (one static-field read): the common case -- a
        // SurfaceView-only app, no TextureView anywhere -- must pay nothing
        // beyond this single JNI round trip. No pixel lock, no copy, no
        // second call.
        jmethodID hasTarget = env->GetStaticMethodID(cls, "macapkHasActiveVideoTarget", "()Z");
        if (!hasTarget) {
            env->ExceptionClear();
        } else if (env->CallStaticBooleanMethod(cls, hasTarget)) {
            CVPixelBufferRef pb = (CVPixelBufferRef)pixbuf;
            CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
            size_t w      = CVPixelBufferGetWidth(pb);
            size_t h      = CVPixelBufferGetHeight(pb);
            size_t stride = CVPixelBufferGetBytesPerRow(pb);
            const uint8_t* base = (const uint8_t*)CVPixelBufferGetBaseAddress(pb);
            if (base && w > 0 && h > 0 && (stride % 4) == 0) {
                jsize nInts = (jsize)((stride / 4) * h);
                jintArray arr = env->NewIntArray(nInts);
                if (arr) {
                    env->SetIntArrayRegion(arr, 0, nInts, (const jint*)base);
                    jmethodID offer = env->GetStaticMethodID(
                        cls, "macapkOfferDecodedFrame", "([IIII)Z");
                    if (!offer) {
                        env->ExceptionClear();
                    } else {
                        jboolean consumed = env->CallStaticBooleanMethod(
                            cls, offer, arr, (jint)w, (jint)h, (jint)(stride / 4));
                        if (env->ExceptionCheck()) { env->ExceptionClear(); consumed = JNI_FALSE; }
                        offered = (consumed == JNI_TRUE);
                    }
                    env->DeleteLocalRef(arr);
                }
            }
            CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
        }
        env->DeleteLocalRef(cls);
    }
    if (attached) vm->DetachCurrentThread();
    return offered;
}

// ---------------------------------------------------------------------------
// Video-plane geometry: pin the video layer to the guest SurfaceView's rect.
//
// AOSP contract this mirrors (both fetched 2026-08-17 from aosp-mirror/master):
//   * SurfaceView.onSetSurfacePositionAndScale --
//         transaction.setPosition(surface, positionLeft, positionTop);
//         transaction.setMatrix(surface, postScaleX, 0f, 0f, postScaleY);
//     with postScale = mScreenRect / mSurfaceSize and mScreenRect built from
//     getLocationInWindow + getWidth/getHeight (core/java/android/view/
//     SurfaceView.java). The compositor shows the surface AT the view's
//     on-screen rect, scaled to it -- never over the whole display.
//   * MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT = 1 ("The content is scaled
//     to the surface dimensions") is the DEFAULT surface scaling mode
//     (media/java/android/media/MediaCodec.java:5052-5092).
// Net effect on a device: decoded frame stretches to the surface buffer, the
// buffer stretches to the view rect. Our presenter already stretches the
// frame over the layer's drawable (media_video.mm's fullscreen-triangle
// blit), so setting the LAYER's frame to the view rect reproduces the
// composed transform exactly.
//
// The rect is PULLED per present (and per drawInMTKView tick while a video
// layer exists) from SurfaceView.macapkActiveVideoRectPacked() -- same
// pull-not-push reasoning as MacapkQueryContentInsetsPacked: the pull runs on
// the Cocoa main thread, which is also the thread the View tree is laid out
// on, so it cannot be stale by more than a frame and needs no invalidation
// protocol. Sentinels: 0 = no SurfaceView sink (legacy whole-window plane,
// the pre-v1.0.996 behaviour, kept for bare-Surface producers); -1 = sink
// exists but has no visible rect right now (detached / hidden / zero-sized)
// -> hide the plane and skip the present, as a device stops compositing a
// released SurfaceControl.
static jlong MacapkQueryVideoSinkRectPacked(JNIEnv* env) {
    if (!env) return 0;
    static jclass    s_cls   = nullptr;
    static jmethodID s_mid   = nullptr;
    static bool      s_tried = false;
    if (!s_tried) {
        s_tried = true;
        jclass c = env->FindClass("android/view/SurfaceView");
        if (!c) {
            env->ExceptionClear();
            std::fprintf(stderr, "[W] cocoa_v2: SurfaceView not found -- "
                                 "video layer stays whole-window\n");
        } else {
            s_mid = env->GetStaticMethodID(c, "macapkActiveVideoRectPacked", "()J");
            if (!s_mid) env->ExceptionClear();
            else        s_cls = (jclass) env->NewGlobalRef(c);
            env->DeleteLocalRef(c);
        }
    }
    if (!s_cls || !s_mid) return 0;
    jlong v = env->CallStaticLongMethod(s_cls, s_mid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

/** Bisect gate: MACAPK_VIDEO_VIEW_RECT=0 restores the pre-v1.0.996 behaviour
 *  (video plane always covers the whole window) in one run. Default ON. */
static bool MacapkVideoViewRectEnabled() {
    static int s_on = -1;
    if (s_on < 0) {
        const char* e = std::getenv("MACAPK_VIDEO_VIEW_RECT");
        s_on = (e && (std::strcmp(e, "0") == 0 || strcasecmp(e, "false") == 0))
                   ? 0 : 1;
        if (!s_on)
            std::fprintf(stderr, "[I] cocoa_v2: MACAPK_VIDEO_VIEW_RECT=0 -- "
                "video layer pinned whole-window (pre-v1.0.996 geometry)\n");
    }
    return s_on == 1;
}

/** JNIEnv for the current (main) thread, attaching if needed. The Cocoa main
 *  thread is already attached in any run that renders (drawInMTKView attaches
 *  it and never detaches), so the attach branch only fires if a video frame
 *  somehow lands before the first draw tick. */
static JNIEnv* MacapkMainThreadEnv() {
    JavaVM* vm = g_touch_vm;
    if (!vm) return nullptr;
    JNIEnv* env = nullptr;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
            return nullptr;
    } else if (r != JNI_OK) {
        return nullptr;
    }
    return env;
}

/** Position g_video_layer for this frame. Returns false when the sink is
 *  hidden and the present must be skipped. Main thread only. */
static bool MacapkApplyVideoLayerGeometry(JNIEnv* env, MTKView* view) {
    if (!g_video_layer || !view) return true;
    CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
    if (scale <= 0) scale = 2.0;
    jlong packed = (env && MacapkVideoViewRectEnabled())
                       ? MacapkQueryVideoSinkRectPacked(env) : 0;
    if (packed == -1) {
        if (!g_video_layer.hidden) g_video_layer.hidden = YES;
        return false;
    }
    CGRect  wantFrame;
    CGSize  wantDrawable;
    if (packed == 0) {
        // No SurfaceView sink: legacy whole-window plane.
        wantFrame    = view.layer.bounds;
        wantDrawable = CGSizeMake(std::round(view.bounds.size.width  * scale),
                                  std::round(view.bounds.size.height * scale));
    } else {
        // Signed 16-bit fields: x/y go negative when the view scrolls partly
        // off-screen. The host view's layer masksToBounds (set at video
        // attach) crops the plane at the window edge without distorting it.
        int16_t xs  = (int16_t) ((packed >> 48) & 0xffff);
        int16_t ys  = (int16_t) ((packed >> 32) & 0xffff);
        int     wpx = (int)     ((packed >> 16) & 0xffff);
        int     hpx = (int)     ( packed        & 0xffff);
        if (wpx <= 0 || hpx <= 0) {
            if (!g_video_layer.hidden) g_video_layer.hidden = YES;
            return false;
        }
        // Surface px (top-left origin, full-display space -- the same space
        // ViewCanvasDriver's canvas bitmap lives in) -> host layer points.
        // A CALayer hosted by a non-flipped NSView has its origin at the
        // BOTTOM-left; ask the view rather than assuming, exactly as
        // MacapkComputeContentBox does.
        CGFloat viewHpt = view.bounds.size.height;
        wantFrame.origin.x    = xs / scale;
        wantFrame.origin.y    = view.isFlipped
                                    ? (ys / scale)
                                    : (viewHpt - (ys + hpx) / scale);
        wantFrame.size.width  = wpx / scale;
        wantFrame.size.height = hpx / scale;
        wantDrawable = CGSizeMake(wpx, hpx);
    }
    if (!CGRectEqualToRect(g_video_layer.frame, wantFrame))
        g_video_layer.frame = wantFrame;
    if (!CGSizeEqualToSize(g_video_layer.drawableSize, wantDrawable))
        g_video_layer.drawableSize = wantDrawable;
    if (g_video_layer.hidden) g_video_layer.hidden = NO;
    return true;
}

extern "C" void CocoaPresentVideoFrame(void* pixbuf, int /*width*/, int /*height*/) {
    if (!pixbuf) return;
    // Route to a registered TextureView target FIRST, synchronously, on the
    // caller's own thread -- before the CFRetain/dispatch_async hop below,
    // and using the ORIGINAL pixbuf directly. This is safe because control
    // has not yet returned to Java: MediaCodec.releaseOutputBuffer calls
    // nativeVidRender (this call's synchronous caller) and only THEN calls
    // nativeVidReleaseFrame, so the caller's own CVPixelBufferRef reference
    // is still guaranteed live for the duration of this function. A
    // consumed frame returns immediately, skipping the fullscreen
    // g_video_layer machinery entirely -- a TextureView-bound video must
    // not ALSO flash fullscreen behind its own composited position.
    if (MacapkOfferDecodedFrameToTextureView(pixbuf)) return;

    // Retain across the (possibly async) hop to main: the caller's own
    // reference may be released the instant this call returns.
    CFTypeRef pb = CFRetain((CFTypeRef)pixbuf);
    void (^mainBlock)(void) = ^{
        // Same windowless-Path-A rule as CocoaPresentBitmap (v1.0.997): the
        // video plane must land on the view that is actually on screen, or the
        // decoded frames present into an orphaned layer tree and the player box
        // is black no matter how healthy the decode is.
        MTKView* view = g_last_metal_view;
        if (view && view.window == nil && MacapkRewireKeepCanvas()) view = nil;
        if (!view) view = g_active_render_view ? g_active_render_view
                                               : g_last_gl_metal_view;
        if (!view) { CFRelease(pb); return; }
        if (!g_video_layer || g_video_attached_view != view) {
            if (g_video_layer) { [g_video_layer removeFromSuperlayer]; g_video_layer = nil; }
            CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
            CAMetalLayer* vl = [CAMetalLayer layer];
            vl.device           = view.device ?: MTLCreateSystemDefaultDevice();
            vl.pixelFormat      = MTLPixelFormatBGRA8Unorm;
            vl.framebufferOnly  = YES;
            vl.opaque           = YES;
            vl.contentsScale    = scale;
            vl.frame            = view.bounds;
            vl.drawableSize     = CGSizeMake(std::round(view.bounds.size.width  * scale),
                                             std::round(view.bounds.size.height * scale));
            vl.actions = @{ @"contents": [NSNull null], @"bounds": [NSNull null], @"position": [NSNull null] };
            // v1.0.997: 0.25, not 0.75. AOSP composites a SurfaceView BELOW the
            // window (setZOrderOnTop(false) is the default and our
            // setZOrderOnTop is a no-op stub, so "below" is the only case we
            // model), which means the plane must sit under the app's View-tree
            // chrome in EVERY hosting arrangement. 0.75 only satisfied that
            // against a Path-A canvas overlay at zPosition 1.0; when the overlay
            // is hosted on a GL MTKView it sits at 0.5 (so a Dialog at 1.0 can
            // still cover the app's widgets), and 0.75 then put the video ON TOP
            // of the player's own transport controls. 0.25 is below both overlay
            // z-values and still above the framework angleLayer at 0.
            vl.zPosition = 0.25;
            // Crop, don't distort: when the sink view scrolls partly off the
            // window, the plane's frame extends past the view and the
            // SUPERLAYER's mask clips it at the edge -- content stays at its
            // own scale and is cropped, exactly what a device's window does to
            // a surface. Scoped to video-hosting views only (every other
            // sublayer -- angle/canvas/unity -- is view-sized already).
            view.layer.masksToBounds = YES;
            [view.layer addSublayer:vl];
            g_video_layer = vl;
            g_video_attached_view = view;
            std::fprintf(stderr,
                "[I] cocoa_v2: video layer (re)attached to MTKView=%p zPos=%.2f bounds=%.0fx%.0f\n",
                (void*)view, (double)vl.zPosition,
                view.layer.bounds.size.width, view.layer.bounds.size.height);
        }
        // v1.0.996: the plane's geometry is the sink SurfaceView's on-screen
        // rect (see MacapkApplyVideoLayerGeometry above), not the whole
        // window. A hidden sink skips the present entirely.
        if (!MacapkApplyVideoLayerGeometry(MacapkMainThreadEnv(), view)) {
            CFRelease(pb);
            return;
        }
        if (!g_video_presenter) g_video_presenter = macapk_vd_present_create((__bridge void*)g_video_layer.device);
        if (g_video_presenter) macapk_vd_present_to_layer(g_video_presenter, (__bridge void*)g_video_layer, (void*)pb);
        CFRelease(pb);
    };
    if ([NSThread isMainThread]) mainBlock();
    else dispatch_async(dispatch_get_main_queue(), mainBlock);
}

// Premultiply a straight-alpha ARGB buffer in place of a plain copy.
//
// WHY. Our Java backing store is NON-premultiplied end to end -- Bitmap.java says
// so itself ("Our backing store is NON-premultiplied end to end
// (BufferedImage.TYPE_INT_ARGB)"), and macapkExtractArgbFromAwt hands the int[]
// over byte-identically. But both present paths below declare the CGImage as
// `kCGImageAlphaPremultipliedFirst`. Apple's CGImage.h is explicit that those are
// two different things:
//     kCGImageAlphaPremultipliedFirst, /* For example, premultiplied ARGB */
//     kCGImageAlphaFirst,              /* For example, non-premultiplied ARGB */
// so the WindowServer composited `out = S + (1-a)*D` instead of
// `out = a*S + (1-a)*D`: every translucent pixel painted its colour at FULL
// strength.
//
// MEASURED on Vector Pinball's menu panel, whose shape is
// `<solid android:color="#b448"/>` == 0xBB444488, i.e. 73% opaque over the black
// table felt (2026-08-28, from a real `screencapture -l` of the window, not a
// self-capture sublayer):
//     emulator panel face  (50, 50, 100)   == 0xBB/255 * (68,68,136)
//     ours                 (68, 68, 136)   == raw #444488, alpha never applied
// The destination-leak term was identical on both sides, which is what rules out
// "the buffer's alpha is 0xFF" and "nothing is underneath" -- only the SOURCE
// term differed, which is uniquely what this misdeclaration produces.
//
// WHY PREMULTIPLY HERE rather than flip the flag to kCGImageAlphaFirst (which is
// legal for CGImageCreate): it keeps the declared format truthful and does not
// depend on how CoreAnimation chooses to handle unpremultiplied layer contents.
//
// ⚠ PERFORMANCE. This is the per-frame present path, and a per-pixel walk here
// has bitten this project before (Bitmap.macapkArgbBuffer's getRGB loop cost
// ~24ms/frame). It replaced a full-buffer memcpy, so the floor is the same
// read+write traffic.
//
// ⚠ CORRECTED 2026-09-25: this note used to say the per-pixel `a == 0xFF` test
// below was "perfectly branch-predicted" and kept the loop near that floor.
// There is no branch in what executes. otool of the shipped CocoaPresentBitmap
// (loop at 0x1000c0efc-0x1000c0f80) shows clang vectorized the plain loop 4
// pixels wide and IF-CONVERTED it: every pixel runs the full multiply chain
// (mul/umull, the rounding, masks) and a cmeq/cmhs/bif select picks the result
// afterwards, so an all-opaque frame pays the whole ALU cost. Measured 0.88 ms
// at 808x1796 (changelog v1.0.1095, `copy avg`), i.e. ~3.7 ms extrapolated at
// the 3360x1796 full-screen window. docs/canvas-present-cost-at-full-screen_2026-09-25.md
// has the disassembly and the arithmetic.
//
// So the fast path is now REAL: a 16-pixel block whose alphas are all 0xFF is
// stored unchanged, and one whose alphas are all 0 is stored as zeros, decided
// by one AND / OR reduction per block -- memory-bound, and byte-identical by
// definition (premultiply is the identity at a == 255 and 0 at a == 0, which is
// exactly what the plain loop computes for those pixels). A mixed block, and
// the tail, run the plain loop. debug/present_fastpath_gate.sh compiles this
// block standalone and checks it against the plain loop, positive control
// included.
// ---- BEGIN macapk_premultiply_argb (compiled standalone by debug/present_fastpath_gate.sh) ----
static inline void macapk_premultiply_argb_plain(uint32_t* dst, const uint32_t* src,
                                                 size_t n) {
    for (size_t i = 0; i < n; ++i) {
        const uint32_t p = src[i];
        const uint32_t a = p >> 24;
        if (a == 0xFFu) { dst[i] = p; continue; }   // opaque
        if (a == 0u)    { dst[i] = 0u; continue; }  // fully transparent
        // +127 rounds to nearest, matching Skia's SkMulDiv255Round intent.
        const uint32_t r = (((p >> 16) & 0xFFu) * a + 127u) / 255u;
        const uint32_t g = (((p >>  8) & 0xFFu) * a + 127u) / 255u;
        const uint32_t b = (( p        & 0xFFu) * a + 127u) / 255u;
        dst[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
}
// BISECT GATE, default ON: MACAPK_PRESENT_NEON=0 (or MACAPK_PRESENT_FASTPATH=0,
// every present fast-path step at once) runs the plain loop for every pixel.
static bool MacapkPremulBlocksEnabled() {
    static const bool on = [] {
        const char* all = std::getenv("MACAPK_PRESENT_FASTPATH");
        const char* one = std::getenv("MACAPK_PRESENT_NEON");
        return !(all && std::strcmp(all, "0") == 0)
            && !(one && std::strcmp(one, "0") == 0);
    }();
    return on;
}
static inline void macapk_premultiply_argb(uint32_t* dst, const uint32_t* src,
                                           size_t n) {
    size_t i = 0;
#if defined(__aarch64__)
    if (MacapkPremulBlocksEnabled()) {
        const uint32x4_t zero = vdupq_n_u32(0u);
        const uint32x4x4_t zeros = {{ zero, zero, zero, zero }};
        for (; i + 16 <= n; i += 16) {
            const uint32x4x4_t v = vld1q_u32_x4(src + i);
            // Every alpha is 0xFF iff the AND of the 16 pixels keeps a 0xFF top
            // byte in every lane, i.e. its smallest lane is >= 0xFF000000.
            const uint32x4_t all = vandq_u32(vandq_u32(v.val[0], v.val[1]),
                                             vandq_u32(v.val[2], v.val[3]));
            if (vminvq_u32(all) >= 0xFF000000u) { vst1q_u32_x4(dst + i, v); continue; }
            // Every alpha is 0 iff the OR of the 16 pixels has a zero top byte.
            const uint32x4_t any = vorrq_u32(vorrq_u32(v.val[0], v.val[1]),
                                             vorrq_u32(v.val[2], v.val[3]));
            if (vmaxvq_u32(any) < 0x01000000u) { vst1q_u32_x4(dst + i, zeros); continue; }
            macapk_premultiply_argb_plain(dst + i, src + i, 16);
        }
    }
#endif
    macapk_premultiply_argb_plain(dst + i, src + i, n - i);
}
// ---- END macapk_premultiply_argb ----

// ---- BEGIN macapk_present_pool_core (compiled standalone by debug/present_iosurface_gate.sh) ----
// P2/P3 of docs/canvas-present-cost-at-full-screen_2026-09-25.md, the PURE half:
// the exact row diff, the per-surface stale row spans, and the ring's
// bookkeeping. No ObjC, no IOSurface, no locks. CocoaPresentFrameFrom below
// wraps it with real IOSurfaces, the JNI pin and the main-thread publish; the
// gate drives the same code with fake surfaces and a model compositor, so what
// the gate checks is what ships.
//
// The contract being mirrored is hwui's (android14-release
// libs/hwui/renderthread/CanvasContext.cpp): an empty dirty region SKIPS the
// frame (:548-566), and a buffer older than one frame is repaired by joining
// the damage of every frame since it was last drawn (computeDirtyRect,
// :1000-1046). Here the damage is not claimed by the views but MEASURED, as an
// exact pixel diff against the last presented frame, so it cannot under-damage.

// One 64-byte block (16 pixels) of `cur` against `prev`: does any pixel differ?
static inline bool macapk_block16_differs(const uint32_t* cur, const uint32_t* prev) {
#if defined(__aarch64__)
    const uint32x4x4_t c = vld1q_u32_x4(cur);
    const uint32x4x4_t p = vld1q_u32_x4(prev);
    const uint32x4_t d = vorrq_u32(vorrq_u32(veorq_u32(c.val[0], p.val[0]),
                                             veorq_u32(c.val[1], p.val[1])),
                                   vorrq_u32(veorq_u32(c.val[2], p.val[2]),
                                             veorq_u32(c.val[3], p.val[3])));
    return vmaxvq_u32(d) != 0u;
#else
    return std::memcmp(cur, prev, 64) != 0;
#endif
}

// The changed span of one row inside [x0, x1): [*span0, *span1), or false when
// nothing there differs. Blocks are counted from x0, so the span is
// block-granular, but it always COVERS every differing pixel -- which is all
// exactness needs, since a span pixel that did not change is copied and
// premultiplied to the value it already had. Forward to the first differing
// block, backward to the last: an unchanged row costs one read of both rows, a
// changed one usually much less.
static inline bool macapk_diff_row(const uint32_t* cur, const uint32_t* prev,
                                   int x0, int x1, int* span0, int* span1) {
    const int n = x1 - x0;
    if (n <= 0) return false;
    cur += x0;
    prev += x0;
    const int nb = n / 16;          // whole 64-byte blocks
    const int tail = nb * 16;       // first pixel after them
    int first = -1;
    int i = 0;
    for (; i < nb; ++i)
        if (macapk_block16_differs(cur + i * 16, prev + i * 16)) { first = i * 16; break; }
    if (first < 0) {
        for (int k = tail; k < n; ++k)
            if (cur[k] != prev[k]) { *span0 = x0 + tail; *span1 = x1; return true; }
        return false;
    }
    int end = -1;
    for (int k = n - 1; k >= tail; --k)
        if (cur[k] != prev[k]) { end = n; break; }
    for (int j = nb - 1; end < 0 && j > i; --j)
        if (macapk_block16_differs(cur + j * 16, prev + j * 16)) end = (j + 1) * 16;
    if (end < 0) end = (i + 1) * 16;   // block i is the only one that differs
    *span0 = x0 + first;
    *span1 = x0 + end;
    return true;
}

// A set of row spans: per row, ONE span [x0, x1) (x0 >= x1 is a clean row).
// As a surface's STALE set it is what that surface lacks relative to the
// shadow; as a frame's change set it is what the diff found. A union keeps one
// covering span per row -- a superset, harmless for the same reason as above.
struct MacapkStaleSet {
    std::vector<int32_t> x0, x1;
    int rows = 0;                   // rows holding a span
    int Height() const { return (int) x0.size(); }
    void Reset(int h) { x0.assign((size_t) h, 0); x1.assign((size_t) h, 0); rows = 0; }
    void Clear() {
        std::fill(x0.begin(), x0.end(), 0);
        std::fill(x1.begin(), x1.end(), 0);
        rows = 0;
    }
    void SetFull(int w) {
        std::fill(x0.begin(), x0.end(), 0);
        std::fill(x1.begin(), x1.end(), w);
        rows = w > 0 ? Height() : 0;
    }
    void Union(int y, int a, int b) {
        if (a >= b) return;
        int32_t& s0 = x0[(size_t) y];
        int32_t& s1 = x1[(size_t) y];
        if (s0 >= s1) { s0 = a; s1 = b; ++rows; return; }
        if (a < s0) s0 = a;
        if (b > s1) s1 = b;
    }
    bool Empty() const { return rows == 0; }
};

// Diff `cur` against `shadow` (both w x h, rows contiguous) over the bound
// [bl, br) x [bt, bb), copying every changed span into the shadow as it is
// found (the row is still in cache), so on return the shadow IS the frame being
// presented. `chg` receives the frame's spans and is Reset to h here.
// VALIDATE (`outside` non-null, also Reset here): the whole frame is diffed
// whatever the bound, and every span outside the bound lands in `outside` as
// well -- an under-damage claim, named with coordinates by the caller. Those
// spans are still applied, so the frame on screen stays exact either way.
// Returns the number of changed rows.
static int macapk_diff_frame(const uint32_t* cur, uint32_t* shadow, int w, int h,
                             int bl, int bt, int br, int bb,
                             MacapkStaleSet* chg, MacapkStaleSet* outside) {
    chg->Reset(h);
    if (outside) outside->Reset(h);
    for (int y = 0; y < h; ++y) {
        const bool claimed_row = y >= bt && y < bb;
        if (!claimed_row && !outside) continue;
        const uint32_t* c = cur + (size_t) y * (size_t) w;
        uint32_t* s = shadow + (size_t) y * (size_t) w;
        int seg0[3], seg1[3];
        bool seg_out[3];
        int nseg = 0;
        if (!claimed_row) {
            seg0[nseg] = 0; seg1[nseg] = w; seg_out[nseg++] = true;
        } else {
            if (outside && bl > 0) { seg0[nseg] = 0;  seg1[nseg] = bl; seg_out[nseg++] = true; }
            seg0[nseg] = bl; seg1[nseg] = br; seg_out[nseg++] = false;
            if (outside && br < w) { seg0[nseg] = br; seg1[nseg] = w;  seg_out[nseg++] = true; }
        }
        for (int k = 0; k < nseg; ++k) {
            int a = 0, b = 0;
            if (!macapk_diff_row(c, s, seg0[k], seg1[k], &a, &b)) continue;
            std::memcpy(s + a, c + a, (size_t) (b - a) * 4);
            chg->Union(y, a, b);
            if (seg_out[k]) outside->Union(y, a, b);
        }
    }
    return chg->rows;
}

// Premultiply `set`'s spans of the straight-ARGB shadow into a surface whose
// rows are `stride_px` pixels apart -- the same bytes CocoaPresentBitmap's copy
// holds for those pixels. Returns the number of pixels written.
static size_t macapk_write_stale_rows(uint32_t* dst, size_t stride_px,
                                      const uint32_t* shadow, int w,
                                      const MacapkStaleSet& set) {
    size_t px = 0;
    const int h = set.Height();
    for (int y = 0; y < h; ++y) {
        const int a = set.x0[(size_t) y], b = set.x1[(size_t) y];
        if (a >= b) continue;
        macapk_premultiply_argb(dst + (size_t) y * stride_px + (size_t) a,
                                shadow + (size_t) y * (size_t) w + (size_t) a,
                                (size_t) (b - a));
        px += (size_t) (b - a);
    }
    return px;
}

// MACAPK_PRESENT_VERIFY's check: does the whole surface equal
// premultiply(shadow)? Returns the number of mismatching rows and the first
// mismatching pixel. `scratch` is a caller-owned row buffer.
static int macapk_verify_surface(const uint32_t* surf, size_t stride_px,
                                 const uint32_t* shadow, int w, int h,
                                 std::vector<uint32_t>* scratch,
                                 int* first_y, int* first_x,
                                 uint32_t* want, uint32_t* got) {
    int bad_rows = 0;
    scratch->resize((size_t) w);
    for (int y = 0; y < h; ++y) {
        const uint32_t* row = surf + (size_t) y * stride_px;
        macapk_premultiply_argb(scratch->data(), shadow + (size_t) y * (size_t) w, (size_t) w);
        if (std::memcmp(row, scratch->data(), (size_t) w * 4) == 0) continue;
        if (bad_rows++ == 0) {
            int x = 0;
            while (x < w && row[x] == (*scratch)[(size_t) x]) ++x;
            *first_y = y; *first_x = x;
            *want = (*scratch)[(size_t) x]; *got = row[x];
        }
    }
    return bad_rows;
}

// The ring. A slot is FREE (never shown, or its content reclaimed), PENDING (a
// main-thread block will make it the layer's contents), FRONT (the layer's
// contents) or RETIRED (was FRONT; free once the WindowServer is done with it).
// A write target is never FRONT or PENDING and never in use by anyone
// (IOSurfaceIsInUse, IOSurfaceRef.h: a pool "should not reclaim" a surface in
// use). Every stale set is kept relative to the SHADOW, not to FRONT, which is
// what makes a fallback frame, a foreign present, or a surface skipped because
// it is in use all correct without special cases.
enum : uint8_t {
    kMacapkSlotFree = 0, kMacapkSlotPending, kMacapkSlotFront, kMacapkSlotRetired
};
enum {
    kMacapkPresentNone = 0,        // nothing presented (bad args, pin failed)
    kMacapkPresentPublished = 1,   // a pool surface was written and dispatched
    kMacapkPresentElided = 2,      // identical to what the layer shows: no write, no dispatch
    kMacapkPresentFallback = 3,    // no surface free: the legacy CGImage path, this frame only
    // Plan's answer when no slot is writable and the ring may still grow. Never
    // returned to Java: the caller creates a surface with its pool lock DROPPED
    // (IOSurfaceCreate maps up to ~24 MB, behind ObjC and an autorelease pool),
    // Adds it under the lock and plans again.
    kMacapkPresentNeedSurface = 4
};
struct MacapkPoolSlot {
    void*          surf  = nullptr;   // IOSurfaceRef in the runtime; a fake in the gate
    uint8_t        state = kMacapkSlotFree;
    MacapkStaleSet stale;
};
struct MacapkPoolCore {
    static constexpr int kRing = 3;   // FRONT, one the WindowServer may still hold, the target
    static constexpr int kMax  = 4;   // grown to only when all of the ring is busy
    int w = 0, h = 0;
    int n = 0;                        // slots holding a surface
    MacapkPoolSlot slot[kMax];
    // The slot whose pixels the layer will show once the main queue drains, or
    // -1 when that is not a pool surface (nothing yet, a fallback frame, a
    // foreign present, or a publish that found no host view).
    int newest = -1;
    uint32_t newest_epoch = 0;        // g_canvas_host_epoch when `newest` was dispatched
    uint64_t gen = 1;                 // bumped by Resize: older main blocks are ignored

    // Re-key to w x h. Every surface is handed back to be released (the layer
    // and any queued main block hold their own reference). Returns the count.
    int Resize(int nw, int nh, void* released[kMax]) {
        int k = 0;
        for (int s = 0; s < n; ++s) {
            if (slot[s].surf) released[k++] = slot[s].surf;
            slot[s].surf = nullptr;
            slot[s].state = kMacapkSlotFree;
        }
        n = 0; w = nw; h = nh; newest = -1; ++gen;
        return k;
    }
    // The shadow changed by a route that recorded no spans (FORCE_FULL, a
    // foreign present): every surface is fully stale and none is known to match.
    void InvalidateAll() {
        for (int s = 0; s < n; ++s) slot[s].stale.SetFull(w);
        newest = -1;
    }
    // This frame's change goes into EVERY surface's stale set: none has been
    // written yet this frame, and the target's is taken for the write after.
    void UnionChanged(const MacapkStaleSet& chg) {
        if (chg.Empty()) return;
        for (int s = 0; s < n; ++s) {  // EVERY surface, the newest included
            MacapkStaleSet& st = slot[s].stale;
            for (int y = 0; y < h; ++y) st.Union(y, chg.x0[(size_t) y], chg.x1[(size_t) y]);
        }
    }
    // hwui's `dirty.isEmpty()` skip, exact: the surface the layer will show
    // lacks nothing the shadow has, and the host has not changed since.
    bool CanElide(uint32_t epoch) const {
        return newest >= 0 && slot[newest].stale.Empty() && newest_epoch == epoch;
    }
    // The least-stale slot that is FREE or RETIRED and not in use, or -1.
    template <class InUseFn>
    int Pick(InUseFn in_use) const {
        int best = -1;
        for (int s = 0; s < n; ++s) {
            const MacapkPoolSlot& sl = slot[s];
            if (sl.state != kMacapkSlotFree && sl.state != kMacapkSlotRetired) continue;
            if (in_use(sl.surf)) continue;
            if (best < 0 || sl.stale.rows < slot[best].stale.rows) best = s;
        }
        return best;
    }
    int Add(void* surf) {
        MacapkPoolSlot& sl = slot[n];
        sl.surf = surf;
        sl.state = kMacapkSlotFree;
        sl.stale.Reset(h);
        sl.stale.SetFull(w);          // a new surface holds nothing yet
        return n++;
    }
    // The per-frame decision, under the caller's pool lock, after the diff has
    // brought the shadow up to date: record the change, elide if nothing the
    // layer will show is stale, else claim a target or report a fallback. On
    // PUBLISHED the target is PENDING and already `newest`.
    // When nothing is writable and `may_grow` with room in the ring, it answers
    // NEED_SURFACE and claims nothing: the caller creates a surface OUTSIDE the
    // lock, Adds it and calls Plan again with the same `chg`. That second call
    // is exact because Plan is idempotent for one `chg` -- a second union of the
    // same spans, or a second InvalidateAll, changes nothing -- and nothing that
    // can run while the lock is dropped shrinks a stale set or revives `newest`
    // (Committed/Dropped change slot states and can only CLEAR `newest`; a
    // foreign present only widens stale sets and clears `newest`).
    // After a failed create the caller plans once more without growth, which
    // takes a slot freed meanwhile or falls back.
    template <class InUseFn>
    int Plan(const MacapkStaleSet& chg, bool full, bool allow_elide, uint32_t epoch,
             bool may_grow, InUseFn in_use, int* target) {
        *target = -1;
        if (full) InvalidateAll();
        else      UnionChanged(chg);
        if (allow_elide && !full && CanElide(epoch)) return kMacapkPresentElided;
        const int t = Pick(in_use);
        if (t < 0 && may_grow && n < kMax) return kMacapkPresentNeedSurface;
        if (t < 0) { newest = -1; return kMacapkPresentFallback; }
        slot[t].state = kMacapkSlotPending;
        newest = t;
        newest_epoch = epoch;
        *target = t;
        return kMacapkPresentPublished;
    }
    // Move the target's stale set out for the write. Once written the surface
    // equals the shadow, so its own set restarts empty.
    void TakeStale(int t, MacapkStaleSet* out) {
        std::swap(*out, slot[t].stale);
        if (slot[t].stale.Height() != h) slot[t].stale.Reset(h);
        else                             slot[t].stale.Clear();
    }
    // The write failed after Plan claimed `t`: give it back, fully stale.
    void Abandon(int t) {
        slot[t].state = kMacapkSlotFree;
        slot[t].stale.SetFull(w);
        if (newest == t) newest = -1;
    }
    // MAIN THREAD: a block has just set the layer's contents to `t`, or (t < 0)
    // to something that is not a pool surface -- a fallback or foreign CGImage.
    // Whatever was FRONT is now only possibly still composited: RETIRED, and
    // IOSurfaceIsInUse decides when it can be written again.
    void Committed(int t, uint64_t g) {
        if (g != gen) return;
        for (int s = 0; s < n; ++s)
            if (slot[s].state == kMacapkSlotFront) slot[s].state = kMacapkSlotRetired;
        if (t >= 0 && t < n && slot[t].state == kMacapkSlotPending) slot[t].state = kMacapkSlotFront;
    }
    // MAIN THREAD: a block found no host view, so `t` never reached a layer.
    void Dropped(int t, uint64_t g) {
        if (g != gen) return;
        if (t >= 0 && t < n && slot[t].state == kMacapkSlotPending) slot[t].state = kMacapkSlotRetired;
        if (newest == t) newest = -1;
    }
};

// The write step's guard against a FOREIGN present (the legacy CGImage path on
// another thread) that rewrote the shadow after this present diffed it.
// `planned_seq` is the shadow's foreign-write count as the diff left it, `seq`
// the count under the write's own lock. They differ when a foreign frame
// replaced the shadow wholesale in between -- after Plan moved the target's
// stale spans out, so the foreign invalidation could not reach them. Those spans
// describe the surface against the OLD shadow; written from the new one they
// would publish half of each frame. So the whole surface is written instead: it
// then holds exactly the shadow, one frame, the later one. Returns true on that
// path. `write_set` must be the target's taken set (Height() == h).
static inline bool macapk_write_set_after_foreign(uint64_t planned_seq, uint64_t seq,
                                                  int w, int h, MacapkStaleSet* write_set) {
    if (seq == planned_seq) return false;
    if (write_set->Height() != h) write_set->Reset(h);
    write_set->SetFull(w);
    return true;
}
// ---- END macapk_present_pool_core ----

// CocoaPresentBitmap: upload a Java-side ARGB pixel buffer to a
// CALayer overlay that sits above ANGLE's Metal layer. Used by
// SurfaceHolder.unlockCanvasAndPost so Canvas-based apps (Frozen
// Bubble, Sgt Puzzles, ShatteredPD) can show their Java-painted
// pixels on screen.
//
// pixels is the Android ARGB int[] format: each int is 0xAARRGGBB
// stored in native byte order (little-endian on macOS arm64). To
// build a CGImage we tell CoreGraphics the pixel bytes are in
// BGRA order with the alpha pre-multiplied -- on little-endian
// host, an ARGB int's memory layout is B,G,R,A, which matches
// kCGImageByteOrder32Little + kCGImageAlphaPremultipliedFirst.
//
// Performance: rebuilds the CGImage every frame from a fresh
// CGDataProvider; this is on the order of 5 ms for an 800x600
// bitmap, which fits comfortably inside the 16.6 ms 60 fps budget.
// If profile shows this is hot, we can switch to a CVPixelBuffer
// + IOSurface flip for zero-copy. Stub for now.
// (2026-09-25: it is hot at full-screen windows -- 6.6-7.0 ms of present at
// 3360x1796 on F-Droid. The IOSurface ring is P2 of
// docs/canvas-present-cost-at-full-screen_2026-09-25.md; the pixel window
// below is P1.2.)
//
// THE PIXEL WINDOW (2026-09-25, P1.2 of the same doc). The caller's pixels are
// readable only between `acquire` and `release`. For the JNI present
// (native_methods.cpp Cocoa_presentBitmap) that window is a
// GetPrimitiveArrayCritical PIN of the Java int[] -- no copy -- where it used to
// be GetIntArrayElements, which in HotSpot mallocs a new C-heap array and
// copies the whole array into it on EVERY call (jdk21u jni.cpp,
// DEFINE_GETSCALARARRAYELEMENTS: NEW_C_HEAP_ARRAY_RETURN_NULL +
// arraycopy_to_native, *isCopy = JNI_TRUE): a 24 MB malloc + copy + free per
// frame at the full-screen window, ~1.5-2.5 ms. A pin is a GCLocker critical
// region under our -XX:+UseSerialGC (serialHeap.cpp: pin_object =
// GCLocker::lock_critical), so no JNI call, no ObjC, no file I/O and nothing
// that can wait on a Java thread may run inside it, and it should be short.
// So EVERY read of the caller's pixels happens first, in one group: the
// premultiply into our own buffer, the straight-ARGB snapshot memcpy, and (on a
// MACAPK_DUMP_CANVAS frame only) a copy for the dump. The window closes before
// the CGImage exists, and everything after it reads our copies. Taking g_snap_mu
// (and, nested in it by the snapshot, g_pool_mu) inside the window is legal: no
// holder of either makes a JNI call or waits on a Java thread under it -- they
// copy or premultiply memory, lock an IOSurface, build a CGImage, or do the
// ring's bookkeeping -- so none can be waiting on the GC we are holding off.
typedef const uint32_t* (*MacapkPixelsAcquireFn)(void* ctx);
typedef void (*MacapkPixelsReleaseFn)(void* ctx);
namespace {
// Opens the window on construction (calls `acquire`) and closes it exactly once
// (calls `release`) at close() or at scope exit, so an early return cannot leak
// a pin. `release` also runs when `acquire` returned nullptr: a JNI release
// must tolerate a failed acquire.
class MacapkPixelWindow {
 public:
    MacapkPixelWindow(MacapkPixelsAcquireFn acquire, MacapkPixelsReleaseFn release,
                      void* ctx)
        : release_(release), ctx_(ctx), pixels_(acquire ? acquire(ctx) : nullptr) {}
    ~MacapkPixelWindow() { close(); }
    MacapkPixelWindow(const MacapkPixelWindow&) = delete;
    MacapkPixelWindow& operator=(const MacapkPixelWindow&) = delete;
    const uint32_t* pixels() const { return pixels_; }
    void close() {
        pixels_ = nullptr;
        if (MacapkPixelsReleaseFn r = release_) { release_ = nullptr; r(ctx_); }
    }
 private:
    MacapkPixelsReleaseFn release_;
    void* ctx_;
    const uint32_t* pixels_;
};
// The plain-pointer source: the caller owns the pixels for the whole call, so
// the window is the pointer itself and there is nothing to release.
const uint32_t* MacapkPixelsFromPointer(void* ctx) {
    return static_cast<const uint32_t*>(ctx);
}
// MACAPK_DUMP_CANVAS=<n>[,<n>...] frame selection, decided BEFORE the pixel
// window opens so the window only ever copies. Returns this canvas present's
// 1-based number when it is one of the listed frames, else 0. See the writer
// in CocoaPresentBitmapFrom for what the dump is for.
int MacapkCanvasDumpFrameNumber() {
    static const std::vector<int> dump_at = [] {
        std::vector<int> v;
        const char* s = std::getenv("MACAPK_DUMP_CANVAS");
        if (!s || !*s) return v;
        for (const char* p = s; *p; ) {
            char* end = nullptr;
            long n = std::strtol(p, &end, 10);
            if (end == p) break;
            if (n > 0) v.push_back((int) n);
            p = (*end == ',') ? end + 1 : end;
        }
        return v;
    }();
    if (dump_at.empty()) return 0;
    static std::atomic<int> canvas_present{0};
    const int n = canvas_present.fetch_add(1) + 1;
    return std::find(dump_at.begin(), dump_at.end(), n) != dump_at.end() ? n : 0;
}
// The MACAPK_DUMP_CANVAS writer: /tmp/macapk_canvas_<n>.ppm from a straight-ARGB
// frame (Android ARGB ints, B,G,R,A in little-endian memory), rows top-down.
// Shared by both canvas present paths so a dump means the same thing on each.
void MacapkWriteCanvasDumpPPM(int dump_n, const uint8_t* src, int width, int height) {
    char path[PATH_MAX];
    std::snprintf(path, sizeof(path), "/tmp/macapk_canvas_%d.ppm", dump_n);
    if (FILE* f = std::fopen(path, "wb")) {
        std::fprintf(f, "P6\n%d %d\n255\n", width, height);
        std::vector<uint8_t> row((size_t) width * 3);
        for (int y = 0; y < height; ++y) {
            const uint8_t* s = src + (size_t) y * (size_t) width * 4;
            for (int x = 0; x < width; ++x) {
                // Android ARGB ints are B,G,R,A in little-endian
                // memory (straight alpha: this is the caller's buffer,
                // not the premultiplied copy), so emit B,G,R reversed.
                row[(size_t) x * 3 + 0] = s[(size_t) x * 4 + 2];
                row[(size_t) x * 3 + 1] = s[(size_t) x * 4 + 1];
                row[(size_t) x * 3 + 2] = s[(size_t) x * 4 + 0];
            }
            std::fwrite(row.data(), 1, row.size(), f);
        }
        std::fclose(f);
        std::fprintf(stderr,
            "[canvas-dump] wrote %s (%dx%d)\n", path, width, height);
        std::fflush(stderr);
    }
}

// The IOSurface ring's state (P2 of
// docs/canvas-present-cost-at-full-screen_2026-09-25.md; the pure bookkeeping
// is MacapkPoolCore above, the present is CocoaPresentFrameFrom below). Three
// kinds of thread touch it, so every access is under g_pool_mu:
//   * the Java main-loop thread, presenting (Resize / Add / Plan / TakeStale /
//     Abandon, and reading `newest` for the elision check);
//   * the AppKit main thread, committing a block (Committed / Dropped);
//   * any thread running the LEGACY present on the same layer -- SurfaceView
//     and Surface apps call presentBitmap from their own drawing thread -- which
//     rewrites the shadow wholesale and so must invalidate every stale set.
// What runs under g_pool_mu is MacapkPoolCore's bookkeeping, IOSurfaceIsInUse
// (Plan's Pick asks it of at most kMax surfaces: a C query of the use count),
// CFRetain of a surface the ring still owns, and the g_pool_live store. Never a
// pixel pass, an ObjC message, a surface's creation (the ring grows with the
// lock DROPPED: Plan answers kMacapkPresentNeedSurface), a CFRelease that could
// free a surface, file I/O, or anything that waits on a Java thread. That last
// is load-bearing: the legacy present takes g_pool_mu inside its JNI critical
// pin (see MacapkCanvasPoolForeignWriteLocked), which is only legal because no
// holder can be waiting on the GC the pin holds off.
// Lock order: g_snap_mu may be held when g_pool_mu is taken (the legacy shadow
// write, the write step's Abandon), never the reverse.
std::mutex        g_pool_mu;
MacapkPoolCore    g_pool;
std::atomic<bool> g_pool_live{false};   // a pool exists (never, with the gate off)

// CALLER HOLDS g_snap_mu, and has just rewritten the shadow wholesale
// (MacapkSnapshotCanvas, the legacy present) without recording what changed:
// every surface is fully stale and none matches. Doing it UNDER g_snap_mu makes
// the shadow write and the invalidation one step, so no present can plan
// against the new shadow with the old stale sets; the write step's own race
// (a foreign write after Plan took the target's spans) is caught by
// g_snap_canvas_seq, which the caller bumped. With the gate off no pool exists
// and this is the one atomic load it always was.
void MacapkCanvasPoolForeignWriteLocked() {
    if (!g_pool_live.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lk(g_pool_mu);
    g_pool.InvalidateAll();
}
// MAIN THREAD, right after the legacy block set a CGImage as the contents: the
// FRONT surface is no longer what the layer shows.
void MacapkCanvasPoolForeignCommitted() {
    if (!g_pool_live.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lk(g_pool_mu);
    g_pool.Committed(-1, g_pool.gen);
}
}  // namespace

// The canvas overlay's host selection and (re)attach, run by EVERY canvas
// present's main block before it sets the layer's contents -- the CGImage path
// (CocoaPresentBitmapFrom) and the IOSurface path (CocoaPresentFrameFrom) alike,
// so the two can never disagree about where the frame goes. Main thread only.
// Returns the overlay layer, or nil when there is no host view yet (the frame
// is dropped, as it always was). Moved out of CocoaPresentBitmapFrom's block
// verbatim on 2026-09-26; the history below is that block's.
static CALayer* MacapkCanvasOverlayForPresent() {
    MTKView* view = g_last_metal_view;
    // v1.0.962: NO PATH-A MTKVIEW EXISTS -> composite onto the visible
    // GL surface instead of dropping the frame on the floor.
    //
    // THE BUG THIS FIXES. Activity.setContentView has two branches. When
    // the content tree HAS a native render surface (a GLSurfaceView /
    // SurfaceView descendant) it takes the else-branch, which since
    // v1.0.70 also registers a ViewCanvasDriver as an OVERLAY via
    // SurfaceView.macapkSetOverlayDriver -- the mechanism that paints the
    // plain widgets that live alongside the GL view (a button panel and a
    // GLSurfaceView as siblings under one LinearLayout). That branch never
    // calls createMetalSurfaceView, only createMetalSurfaceViewForGl, and
    // ForGl deliberately does NOT set g_last_metal_view (see its comment).
    // So for every such app g_last_metal_view stayed nil, this block hit
    // `if (!view) return`, and the overlay CALayer was NEVER CREATED and
    // never added to any layer tree. The producer side was healthy the
    // whole time -- ViewCanvasDriver laid out and painted real widgets
    // every frame -- and every pixel of it was discarded here.
    //
    // Measured on Vector Pinball (com.dozingcatsoftware.bouncy, in the
    // protected sweep, "renders nothing" for two weeks): the driver
    // composites ~143k non-transparent pixels of a real menu (Start Game /
    // Quit / High Scores / a Select Table row) into an 836x1860 buffer, the
    // in-runtime scorer reads `src=canvas verdict=SPARSE distinct=490
    // cells=8/16` off that buffer -- and the window is solid black on both
    // a window-scoped and a full-screen screencapture. The tell is that
    // "canvas overlay (re)attached to MTKView=" appears ZERO times in a
    // 50-second run in which the canvas frame-score fired repeatedly: both
    // lines are emitted by this one function, so one printing without the
    // other pins the loss to this early return.
    //
    // WHY THIS DOES NOT REINTRODUCE THE v1.0.91 FLAPPY REGRESSION. That bug
    // was a STALE Path-A overlay (a splash Activity's OPAQUE white frame)
    // covering a later Activity's GL output, and the cure was to keep the
    // overlay pointed at the Path-A view. This fallback only engages when
    // g_last_metal_view is nil, i.e. when NO Path-A MTKView has ever been
    // created, so there is no earlier Activity's frame to strand on top of
    // the GL render. Once a Path-A view does exist the target reverts to it
    // and the migration logic below is unchanged. The producer also already
    // guards the content: ViewCanvasDriver picks its clear colour per frame
    // from treeHasSurfaceView(rootView) and clears to 0x00000000 whenever a
    // SurfaceView is in the tree, so what lands here over a GL surface is
    // transparent everywhere except the widget pixels.
    //
    // g_active_render_view (the view drawInMTKView is actually ticking) is
    // preferred over g_last_gl_metal_view because it is the view that is
    // demonstrably on screen; g_last_gl_metal_view is the fallback for the
    // window between surface creation and the first draw.
    // v1.0.997: a Path-A MTKView that is NO LONGER IN A WINDOW is not a
    // host, it is a hole. Activity.macapkMaybeRewireNativeSurface hands
    // the window to a late-arriving SurfaceView's MTKView, and
    // CocoaSetWindowContentView removes every existing letterbox subview
    // to do it -- so g_last_metal_view stays non-nil while being
    // invisible, and this function kept faithfully painting every frame
    // onto an orphan. Measured on PlayTube 2026-08-18: the entire window
    // was black while the canvas overlay AND the video layer were both
    // attached to MTKView=0xb5a7ec000, which the rewire had detached.
    // `view.window == nil` is the exact question ("is this view in the
    // window server's tree"), which is why it is asked instead of
    // tracking detachment separately.
    if (view && view.window == nil && MacapkRewireKeepCanvas()) view = nil;
    bool overlay_on_gl = false;
    if (!view) {
        view = g_active_render_view ? g_active_render_view
                                    : g_last_gl_metal_view;
        overlay_on_gl = (view != nil);
    }
    if (!view) return nil;
    // v1.0.91: Migrate overlay when the active MTKView changes.
    //
    // Activities can re-call `setContentView` (AppCompat's sub-decor
    // wiring, an activity recreate, a HomeScreen -> GameActivity
    // transition that hands off to a different layout). The Java
    // side reacts by calling CocoaCreateMetalSurfaceView again,
    // creating a fresh MTKView; g_last_metal_view points at the
    // NEW one, and Cocoa swaps the window's content view. The OLD
    // MTKView gets detached from the window's view hierarchy.
    //
    // Previously this code created `g_canvas_overlay_layer` once
    // on the FIRST presentBitmap call, attached as a sublayer of
    // the OLD view.layer. When the active MTKView changed, this
    // method only updated `g_canvas_overlay_layer.contents` -- but
    // the overlay layer was still a sublayer of the OLD (no
    // longer visible) MTKView. The NEW MTKView's clearColor (opaque
    // black per CocoaCreateMetalSurfaceView) showed instead.
    //
    // Symptom: Frozen Bubble starts white (driver 1 painting onto
    // overlay-attached-to-first-MTKView, which IS the visible one
    // briefly) then goes black after the second setContentView
    // call swaps MTKView. The Java-side driver paints 1784 pixels
    // of menu buttons each frame, but those updates land on the
    // stale-but-orphaned overlay layer.
    //
    // Fix: track which view the overlay is attached to; when it
    // changes, removeFromSuperlayer + re-attach to the current
    // view. Mirrors the v1.0.78 ANGLE-layer rebind logic for the
    // exactly-equivalent issue on the Canvas overlay side.
    if (!g_canvas_overlay_layer || g_overlay_attached_view != view) {
        if (g_canvas_overlay_layer) {
            [g_canvas_overlay_layer removeFromSuperlayer];
            g_canvas_overlay_layer = nil;
        }
        CALayer* overlay = [CALayer layer];
        overlay.frame = view.layer.bounds;
        overlay.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;
        // contentsGravity = resize so the Bitmap stretches to fill
        // the view's bounds (matches Android's SurfaceView default
        // -- it scales the locked surface to the view size).
        overlay.contentsGravity = kCAGravityResize;
        overlay.opaque = NO;          // Canvas may include alpha
        overlay.masksToBounds = YES;  // crop to view
        // zPosition above the MTKView's own drawing layer (which
        // is at 0). ANGLE owns its own layer at zPosition 0 too,
        // so this needs to be > 0 to draw on top.
        //
        // v1.0.962: when this overlay is hosted on the GL MTKView (the
        // fallback above) it shares a superlayer with g_gl_overlay_layer,
        // the WindowManager-overlay target at zPosition 1.0. Sit at 0.5 so
        // the ordering is angleLayer(0) < app widgets(0.5) < Dialog(1.0)
        // < dedicated Unity/native-EGL layer(2.0): a real modal must still
        // draw over the app's own widgets, exactly as on a device. Equal
        // zPositions would leave it to sublayer insertion order, which
        // depends on which happened to present first.
        overlay.zPosition = overlay_on_gl ? 0.5 : 1.0;
        // Suppress the implicit 0.25s contents crossfade (the "smeary
        // scrolling" bug) -- see MacapkOverlayImplicitFade above for
        // the mechanism + SDK citations. Same NSNull-actions idiom as
        // angleLayer / g_unity_layer / the video layers.
        if (!MacapkOverlayImplicitFade())
            overlay.actions = @{ @"contents": [NSNull null],
                                 @"bounds":   [NSNull null],
                                 @"position": [NSNull null] };
        [view.layer addSublayer:overlay];
        g_canvas_overlay_layer = overlay;
        g_overlay_attached_view = view;
        std::fprintf(stderr,
            "[I] cocoa_v2: canvas overlay (re)attached to MTKView=%p "
            "host=%s zPos=%.1f bounds=%.0fx%.0f\n",
            (void*) view, overlay_on_gl ? "gl" : "path-a",
            (double) overlay.zPosition,
            view.layer.bounds.size.width, view.layer.bounds.size.height);
    }
    // contentsScale matches the view's backing scale so retina
    // displays don't downsample the bitmap. NSScreen.backingScaleFactor
    // is typically 2.0 on retina; the view layer also reports its
    // own contentsScale matching the backing.
    g_canvas_overlay_layer.contentsScale = view.layer.contentsScale;
    // v1.0.503: keep the overlay matched to the (possibly resized) MTKView
    // each present so a window resize scales/updates correctly (belt-and-
    // suspenders over the layer's autoresizingMask).
    g_canvas_overlay_layer.frame = view.layer.bounds;
    return g_canvas_overlay_layer;
}

void CocoaPresentBitmapFrom(int width, int height, MacapkPixelsAcquireFn acquire,
                            MacapkPixelsReleaseFn release, void* ctx);
void CocoaPresentBitmap(const uint32_t* pixels, int width, int height) {
    if (!pixels) return;
    CocoaPresentBitmapFrom(width, height, MacapkPixelsFromPointer, nullptr,
                           const_cast<uint32_t*>(pixels));
}

// `acquire`/`release` bracket the only reads of the caller's pixels; see THE
// PIXEL WINDOW above. acquire is called at most once, and release is called
// if and only if acquire was.
void CocoaPresentBitmapFrom(int width, int height, MacapkPixelsAcquireFn acquire,
                            MacapkPixelsReleaseFn release, void* ctx) {
    // ⚠ @autoreleasepool IS LOAD-BEARING -- THIS IS A MACHINE-KILLING LEAK
    // WITHOUT IT. `CFBridgingRelease(cgImage)` below compiles to a bare
    // `objc_autorelease`, and this function runs on the app's JAVA MAIN-LOOP
    // THREAD, whose implicit autorelease pool never pops for the life of the
    // process. Every presented frame therefore stayed live forever: its
    // ~5.8 MB malloc'd pixel copy PLUS CoreAnimation's per-image shared-memory
    // render copy, about 11.6 MB per present.
    //
    // Measured 2026-08-11 (phys_footprint, the Activity Monitor metric -- ps
    // RSS FALLS while this happens, because the frames compress well, which is
    // why it went unnoticed): F-Droid 1368 MB -> 4388 MB in 15 s, and a
    // mid-storm snapshot at 6595 MB split 3140 MB MALLOC_LARGE (567 pixel
    // copies) / 3144 MB CoreAnimation (584 SHM render copies). The owner saw
    // 14-37 GB and had to kill it twice to save the machine. Fleet-wide, not
    // app-specific: DeskClock merely ticking reached 1.18 GB in 4m18s. The
    // protected sweep never caught it because a settled screen stops
    // presenting and GL games bypass this path entirely.
    //
    // Draining here is safe: dispatch_async COPIES the block, which retains
    // imageObj, so the pool popping on return cannot free a frame the main
    // thread has not drawn yet.
    @autoreleasepool {

        if (width <= 0 || height <= 0) return;
        // MACAPK_PRESENT_TRACE=1 (diagnostic, off by default, zero cost unset):
        // per-phase cost of this present path -- the malloc+premultiply
        // (`copy`), the rest of the pixel window (`snap`: snapshot memcpy, dump
        // copy, release), the CGImage build, the capture/dump/score tail
        // (`post`), the whole pixel window (`window`, i.e. how long a pinned
        // present holds off the GC) and the slowest acquire, and (separately,
        // from the main thread) the CALayer contents commit -- aggregated every
        // 300 presents. Added for the 2026-08-18 "laggy tabs / smeary scroll"
        // lane so the per-frame native present cost is a measured number, not
        // an adjective; split further 2026-09-25 (the pre-split `snap+score`
        // was snap + post).
        static const bool kPresentTrace = [] {
            const char* e = std::getenv("MACAPK_PRESENT_TRACE");
            return e && e[0] == '1';
        }();
        double tp0 = kPresentTrace ? CACurrentMediaTime() : 0.0;
        // Our own copy of the pixels, which must outlive the pixel window since
        // CGDataProvider keeps a pointer. Allocated BEFORE the window opens so
        // the pinned stretch holds only the pixel passes.
        size_t bytes = static_cast<size_t>(width) * height * 4;
        void* copy = std::malloc(bytes);
        if (!copy) return;
        const int dump_n = MacapkCanvasDumpFrameNumber();
        std::vector<uint8_t> dump_px;
        if (dump_n) dump_px.resize(bytes);
        double tpW = 0.0, tpA = 0.0, tp1 = 0.0;
        {
            if (kPresentTrace) tpW = CACurrentMediaTime();
            MacapkPixelWindow win(acquire, release, ctx);
            if (kPresentTrace) tpA = CACurrentMediaTime();
            const uint32_t* pixels = win.pixels();
            if (!pixels) { std::free(copy); return; }
            macapk_premultiply_argb(static_cast<uint32_t*>(copy), pixels,
                                    static_cast<size_t>(width) * height);
            if (kPresentTrace) tp1 = CACurrentMediaTime();
            // v1.0.835: retain the frame so an IDLE menu can still be captured.
            // Without this the capture waits for a repaint that may never come --
            // Frozen Bubble produced a rich canvas capture on one run and NO
            // source at all on the next, same build, decided purely by whether
            // it happened to repaint in the window.
            // The snapshot is also the IOSurface ring's shadow: the same
            // critical section invalidates the ring (if one exists), since the
            // shadow changed by a route that recorded no spans.
            MacapkSnapshotCanvas(pixels, width, height);
            if (dump_n) std::memcpy(dump_px.data(), pixels, bytes);
            win.close();
        }
        double tp2 = kPresentTrace ? CACurrentMediaTime() : 0.0;

        CGDataProviderRef provider = CGDataProviderCreateWithData(
            nullptr, copy, bytes,
            [](void*, const void* data, size_t) { std::free(const_cast<void*>(data)); });
        if (!provider) { std::free(copy); return; }

        CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
        CGImageRef cgImage = CGImageCreate(
            width, height,
            /*bitsPerComponent*/ 8,
            /*bitsPerPixel*/ 32,
            /*bytesPerRow*/ static_cast<size_t>(width) * 4,
            cs,
            kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst,
            provider, nullptr, /*shouldInterpolate*/ false,
            kCGRenderingIntentDefault);
        CGColorSpaceRelease(cs);
        CGDataProviderRelease(provider);
        if (!cgImage) return;
        double tp3 = kPresentTrace ? CACurrentMediaTime() : 0.0;

        // Self-capture source 3 (v1.0.675): this ARGB image IS what a pure-Java /
        // Canvas / Compose app composites. Neither the GL nor the decouple hook can
        // see these apps, so without this they self-capture as a false BLANK.
        if (SelfCaptureRequested("canvas"))
            SelfCaptureWriteCGImageForCanvas((void*)cgImage, "canvas");

        // (The v1.0.835 idle-capture snapshot is taken inside the pixel window
        // above, which is the only place the caller's pixels may be read.)

        // MACAPK_DUMP_CANVAS=<n>[,<n>...] (v1.0.1596): write this ARGB frame to
        // /tmp/macapk_canvas_<n>.ppm on the listed canvas presents.
        //
        // WHY, when MACAPK_DEBUG_DUMPFRAME already exists: that one glReadPixels
        // FBO 0 inside AngleEndFrame, so it only sees the GL path. A pure-Java /
        // Canvas / Compose app composites HERE and its GL FBO is empty, so the
        // existing dump returns a fully BLACK image for an app that is drawing
        // perfectly -- measured on Instagram, whose canvas scored
        // `TINTED coverage=8.0% cells=8/16` while `macapk_frame_2700.ppm` was
        // 100% #000000. This is the canvas counterpart, and it is the
        // unconditional one: unlike the self-capture request protocol it needs
        // no request file, no completion marker and no window, so it still
        // produces a frame when macOS Screen Recording is unavailable and when
        // the request poll is not reached.
        //
        // The source is a copy of the SAME pixels MacapkSnapshotCanvas took,
        // made in the same pixel window, so what lands on disk is exactly what
        // the [frame-score] line scored. Rows are top-down here (unlike the GL
        // dump's bottom-up readback), so the PPM needs no flip. Convert with:
        //   sips -s format png /tmp/macapk_canvas_600.ppm --out frame.png
        // (Which presents are dumped is decided before the window opens, by
        // MacapkCanvasDumpFrameNumber.)
        if (dump_n) MacapkWriteCanvasDumpPPM(dump_n, dump_px.data(), width, height);

        // v1.0.835: periodic STRUCTURE score, so the log alone answers "did it draw
        // real content" with no capture request and no race. This is the metric that
        // ViewCanvasDriver's `drawn=` cannot be: `drawn` counts a flat background
        // fill, so it read 100%-of-window on Sgt Puzzles (whose frame is 4/16 cells)
        // and swung 752968 vs 3227 across two Calculator runs. Throttled -- the score
        // is two passes over the frame.
        if (const unsigned every = SelfCaptureScorePeriod()) {
            static std::atomic<unsigned> ticks{0};
            // v1.0.997: score the just-retained snapshot on the background
            // score queue (see MacapkScoreSnapshotAsync) -- inline, the
            // two-pass score measured 30-50ms ([present-trace] snap+score
            // max) and stalled the app's main loop every `every` presents:
            // a periodic dropped-frames hitch during scroll.
            if ((ticks.fetch_add(1, std::memory_order_relaxed) % every) == 0)
                MacapkScoreSnapshotAsync(/*dc=*/false);
        }
        if (kPresentTrace) {
            // Producer-side aggregate (this function runs on the ONE Java main
            // loop thread, so plain statics are single-writer). The main-thread
            // contents commit is aggregated separately inside mainBlock below.
            // `copy` keeps its pre-2026-09-25 meaning (malloc + premultiply);
            // the acquire is taken out of it and reported on its own.
            double tp4 = CACurrentMediaTime();
            static double sCopy = 0, sSnap = 0, sCg = 0, sPost = 0, sWin = 0;
            static double mCopy = 0, mSnap = 0, mCg = 0, mPost = 0, mWin = 0, mAcq = 0;
            static unsigned sN = 0;
            double dAcq = tpA - tpW;
            double dCopy = (tp1 - tp0) - dAcq, dSnap = tp2 - tp1, dCg = tp3 - tp2;
            double dPost = tp4 - tp3, dWin = tp2 - tpW;
            sCopy += dCopy; sSnap += dSnap; sCg += dCg; sPost += dPost; sWin += dWin;
            if (dCopy > mCopy) mCopy = dCopy;
            if (dSnap > mSnap) mSnap = dSnap;
            if (dCg   > mCg)   mCg   = dCg;
            if (dPost > mPost) mPost = dPost;
            if (dWin  > mWin)  mWin  = dWin;
            if (dAcq  > mAcq)  mAcq  = dAcq;
            if (++sN == 300) {
                std::fprintf(stderr,
                    "[present-trace] n=300 copy avg=%.2f max=%.2f  snap avg=%.2f max=%.2f  "
                    "cgimage avg=%.2f max=%.2f  post avg=%.2f max=%.2f  "
                    "window avg=%.2f max=%.2f  acquire max=%.2f (ms) pinned=%d blocks=%d\n",
                    sCopy / sN * 1e3, mCopy * 1e3, sSnap / sN * 1e3, mSnap * 1e3,
                    sCg / sN * 1e3, mCg * 1e3, sPost / sN * 1e3, mPost * 1e3,
                    sWin / sN * 1e3, mWin * 1e3, mAcq * 1e3,
                    release ? 1 : 0, MacapkPremulBlocksEnabled() ? 1 : 0);
                std::fflush(stderr);
                sCopy = sSnap = sCg = sPost = sWin = 0;
                mCopy = mSnap = mCg = mPost = mWin = mAcq = 0;
                sN = 0;
            }
        }

        // Marshal to the main thread; CALayer.contents must be mutated
        // there. id<CGImageRef> needs a CFBridgingRelease at the use
        // site to balance the +1 from CGImageCreate.
        id imageObj = CFBridgingRelease(cgImage);
        // Inline dispatch_main_async helper (defined later in TU; we
        // can't call the named helper from above its definition without
        // a forward decl). Keep the same semantics: run on main thread
        // immediately if already there, else dispatch_async.
        void (^mainBlock)(void) = ^{
            // Host selection + (re)attach: MacapkCanvasOverlayForPresent.
            if (!MacapkCanvasOverlayForPresent()) return;
            if (kPresentTrace) {
                double ts0 = CACurrentMediaTime();
                g_canvas_overlay_layer.contents = imageObj;
                double dSet = CACurrentMediaTime() - ts0;
                // Main-thread-only statics (this block always runs on main).
                static double sSet = 0, mSet = 0;
                static unsigned setN = 0;
                sSet += dSet;
                if (dSet > mSet) mSet = dSet;
                if (++setN == 300) {
                    std::fprintf(stderr,
                        "[present-trace] n=300 setContents avg=%.2f max=%.2f (ms)\n",
                        sSet / setN * 1e3, mSet * 1e3);
                    std::fflush(stderr);
                    sSet = 0; mSet = 0; setN = 0;
                }
            } else {
                g_canvas_overlay_layer.contents = imageObj;
            }
            MacapkCanvasPoolForeignCommitted();
        };
        if ([NSThread isMainThread]) mainBlock();
        else dispatch_async(dispatch_get_main_queue(), mainBlock);

    }
}

// ---------------------------------------------------------------------------
// Per-surface layers: a lockCanvas SurfaceView's frame on a CALayer of its OWN
// (2026-09-26, F2 of docs/fleet-misc-shatteredpd-retrowars-bobball-bouncy-
// unciv_2026-09-26.md; the Java half is "where a lockCanvas frame is shown" in
// SurfaceView.java, research in docs/surfaceview-surface-geometry_2026-09-26.md).
//
// AOSP composites a SurfaceView's buffer BELOW its window, at the view's rect
// (android14 core/java/android/view/SurfaceView.java: mSubLayer =
// APPLICATION_MEDIA_SUBLAYER :200; mScreenRect from the view's window position
// and size :1040-1043), and SurfaceView.draw punches a hole through the window
// there (:512-521, clearSurfaceViewPort :579-597), so sibling views drawn over
// the surface -- a game's HUD -- stay visible. CocoaPresentBitmap cannot say
// any of that: it REPLACES g_canvas_overlay_layer, the very layer the view tree
// is presented into, so a game thread presenting at 60 fps erased the HUD the
// view tree drew (BobBall: rows 0..199 flat black, all four HUD strings present
// in the tree dump, measured 2026-09-26).
//
// So each surface gets its own CALayer, a SIBLING of the canvas overlay (same
// host view), at zPosition overlay - 0.25 (below the view tree: 0.25 on a GL
// host, 0.75 on a Path-A host) or overlay + 0.1 when the app asked
// setZOrderOnTop. Its frame is PULLED from SurfaceView.macapkSurfaceRectPacked
// (key) on this main thread, per present and per drawInMTKView tick
// (MacapkApplySurfaceLayersGeometry), the same pull-not-push the video plane
// uses with macapkActiveVideoRectPacked, and in the same display-surface pixel
// space. 0 = the surface is gone (the layer is removed), -1 = not visible
// (hidden). MRC: g_surface_layers retains its layers.
// Bisect: MACAPK_SV_SURFACE_LAYER=0 on the Java side never reaches this.
static NSMutableDictionary* g_surface_layers = nil;   // NSNumber(key) -> CALayer; main thread only

// SurfaceView's two pull methods, resolved once (the class is on the
// runtime's own classpath, as for MacapkQueryVideoSinkRectPacked).
struct MacapkSurfacePulls {
    jclass    cls  = nullptr;
    jmethodID rect = nullptr;
    jmethodID top  = nullptr;
};
static const MacapkSurfacePulls& MacapkSurfacePullMethods(JNIEnv* env) {
    static MacapkSurfacePulls s;
    static bool tried = false;
    if (!tried && env) {
        tried = true;
        jclass c = env->FindClass("android/view/SurfaceView");
        if (!c) {
            env->ExceptionClear();
        } else {
            s.rect = env->GetStaticMethodID(c, "macapkSurfaceRectPacked", "(I)J");
            if (!s.rect) env->ExceptionClear();
            s.top = env->GetStaticMethodID(c, "macapkSurfaceZOrderOnTop", "(I)Z");
            if (!s.top) env->ExceptionClear();
            if (s.rect) s.cls = (jclass) env->NewGlobalRef(c);
            env->DeleteLocalRef(c);
        }
        if (!s.cls)
            std::fprintf(stderr, "[W] cocoa_v2: SurfaceView.macapkSurfaceRectPacked "
                                 "not found -- surface layers stay hidden\n");
    }
    return s;
}

static jlong MacapkQuerySurfaceRectPacked(JNIEnv* env, int key) {
    const MacapkSurfacePulls& p = MacapkSurfacePullMethods(env);
    if (!env || !p.cls || !p.rect) return -1;
    jlong v = env->CallStaticLongMethod(p.cls, p.rect, static_cast<jint>(key));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    return v;
}

static bool MacapkQuerySurfaceOnTop(JNIEnv* env, int key) {
    const MacapkSurfacePulls& p = MacapkSurfacePullMethods(env);
    if (!env || !p.cls || !p.top) return false;
    jboolean top = env->CallStaticBooleanMethod(p.cls, p.top, static_cast<jint>(key));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return top == JNI_TRUE;
}

/** Place one surface layer for this frame. Returns false when it must not be
 *  shown (hidden, gone, or no host yet). Main thread only. */
static bool MacapkApplySurfaceLayerGeometry(JNIEnv* env, NSNumber* nk, CALayer* layer) {
    CALayer* overlay = g_canvas_overlay_layer;
    MTKView* host = g_overlay_attached_view;
    if (!overlay || !host) { if (!layer.hidden) layer.hidden = YES; return false; }
    if (layer.superlayer != host.layer) {
        [layer removeFromSuperlayer];
        [host.layer addSublayer:layer];
    }
    const int key = nk.intValue;
    jlong packed = MacapkQuerySurfaceRectPacked(env, key);
    if (packed == 0) {
        [layer removeFromSuperlayer];
        [g_surface_layers removeObjectForKey:nk];
        return false;
    }
    int16_t xs  = (int16_t) ((packed >> 48) & 0xffff);
    int16_t ys  = (int16_t) ((packed >> 32) & 0xffff);
    int     wpx = (int)     ((packed >> 16) & 0xffff);
    int     hpx = (int)     ( packed        & 0xffff);
    if (packed == -1 || wpx <= 0 || hpx <= 0) {
        if (!layer.hidden) layer.hidden = YES;
        return false;
    }
    CGFloat scale = host.window ? host.window.backingScaleFactor : 2.0;
    if (scale <= 0) scale = 2.0;
    // Display-surface px (top-left origin) -> host layer points; ask the view
    // for its flip rather than assuming, as MacapkApplyVideoLayerGeometry does.
    CGFloat viewHpt = host.bounds.size.height;
    CGRect want;
    want.origin.x    = xs / scale;
    want.origin.y    = host.isFlipped ? (ys / scale) : (viewHpt - (ys + hpx) / scale);
    want.size.width  = wpx / scale;
    want.size.height = hpx / scale;
    if (!CGRectEqualToRect(layer.frame, want)) layer.frame = want;
    const CGFloat z = overlay.zPosition + (MacapkQuerySurfaceOnTop(env, key) ? 0.1 : -0.25);
    if (layer.zPosition != z) layer.zPosition = z;
    if (layer.contentsScale != host.layer.contentsScale)
        layer.contentsScale = host.layer.contentsScale;
    if (layer.hidden) layer.hidden = NO;
    return true;
}

/** Per drawInMTKView tick: put the native-EGL window layer (g_unity_layer) on
 *  the side of the window its owning SurfaceView asks for -- see
 *  macapk_guest_layer_z_for for the AOSP model. One static JNI call per tick,
 *  and only while that layer exists. Main thread only. Under
 *  MACAPK_SV_GUEST_BELOW=0 it returns before ANY JNI lookup, so the layer keeps
 *  the configure-time legacy slot (2.0) byte-for-byte. */
static void MacapkSyncGuestLayerZ(JNIEnv* env) {
    if (!macapk_sv_guest_below_on()) return;
    CAMetalLayer* ul = g_unity_layer;
    if (!ul || !env) return;
    static jclass    s_cls = nullptr;
    static jmethodID s_mid = nullptr;
    static bool      s_tried = false;
    if (!s_tried) {
        s_tried = true;
        jclass c = env->FindClass("android/view/SurfaceView");
        if (!c) {
            env->ExceptionClear();
        } else {
            s_mid = env->GetStaticMethodID(c, "macapkNativeWindowLayerZ", "()I");
            if (!s_mid) env->ExceptionClear();
            else s_cls = (jclass) env->NewGlobalRef(c);
            env->DeleteLocalRef(c);
        }
    }
    if (!s_cls || !s_mid) return;   // an older framework jar: the legacy slot stays
    jint answer = env->CallStaticIntMethod(s_cls, s_mid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    g_guest_layer_z_answer.store((int) answer, std::memory_order_relaxed);
    // Ordered against the LIVE canvas overlay when it shares this layer's superlayer.
    const double z = macapk_guest_layer_z((int) answer, ul.superlayer);
    if (ul.zPosition == z) return;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];   // no implicit 0.25 s zPosition animation
    ul.zPosition = z;
    [CATransaction commit];
    std::fprintf(stderr,
        "[I] cocoa_v2: native-EGL window layer zPos=%g (%s; MACAPK_SV_GUEST_BELOW)\n",
        z, answer == 1 ? "below its window, owner punches the hole"
         : answer == 2 ? "setZOrderOnTop: above its window, below Dialogs"
                       : "no owning SurfaceView known: legacy slot");
    std::fflush(stderr);
}

/** Per drawInMTKView tick: keep every surface layer glued to its view (a
 *  paused game must still move with its layout) and drop the gone ones. */
static void MacapkApplySurfaceLayersGeometry(JNIEnv* env) {
    if (!g_surface_layers || g_surface_layers.count == 0) return;
    NSArray* keys = [g_surface_layers allKeys];
    for (NSNumber* nk in keys) {
        CALayer* layer = g_surface_layers[nk];
        if (layer) (void) MacapkApplySurfaceLayerGeometry(env, nk, layer);
    }
}

void CocoaPresentSurfaceBitmapFrom(int surface_key, int width, int height,
                                   MacapkPixelsAcquireFn acquire,
                                   MacapkPixelsReleaseFn release, void* ctx) {
    // @autoreleasepool: the same load-bearing drain as CocoaPresentBitmapFrom
    // (CFBridgingRelease is a bare objc_autorelease on the app's drawing
    // thread, whose pool never pops).
    @autoreleasepool {
        if (width <= 0 || height <= 0) return;
        const size_t bytes = static_cast<size_t>(width) * height * 4;
        void* copy = std::malloc(bytes);
        if (!copy) return;
        {
            MacapkPixelWindow win(acquire, release, ctx);
            const uint32_t* pixels = win.pixels();
            if (!pixels) { std::free(copy); return; }
            macapk_premultiply_argb(static_cast<uint32_t*>(copy), pixels,
                                    static_cast<size_t>(width) * height);
            win.close();
        }
        CGDataProviderRef provider = CGDataProviderCreateWithData(
            nullptr, copy, bytes,
            [](void*, const void* data, size_t) { std::free(const_cast<void*>(data)); });
        if (!provider) { std::free(copy); return; }
        CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
        CGImageRef cgImage = CGImageCreate(
            width, height, 8, 32, static_cast<size_t>(width) * 4, cs,
            kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst,
            provider, nullptr, /*shouldInterpolate*/ false, kCGRenderingIntentDefault);
        CGColorSpaceRelease(cs);
        CGDataProviderRelease(provider);
        if (!cgImage) return;
        id imageObj = CFBridgingRelease(cgImage);
        const int key = surface_key;
        void (^mainBlock)(void) = ^{
            // Host selection + the overlay's (re)attach: the surface layer is
            // its sibling, so the overlay must exist even before the view tree
            // presented anything (it is transparent until then).
            if (!MacapkCanvasOverlayForPresent()) return;
            if (!g_surface_layers) g_surface_layers = [[NSMutableDictionary alloc] init];
            NSNumber* nk = [NSNumber numberWithInt:key];
            CALayer* layer = g_surface_layers[nk];
            if (!layer) {
                layer = [CALayer layer];
                layer.contentsGravity = kCAGravityResize;   // AOSP scales the buffer to the rect
                layer.opaque = NO;
                layer.masksToBounds = YES;
                if (!MacapkOverlayImplicitFade())
                    layer.actions = @{ @"contents":  [NSNull null],
                                       @"bounds":    [NSNull null],
                                       @"position":  [NSNull null],
                                       @"hidden":    [NSNull null],
                                       @"zPosition": [NSNull null] };
                g_surface_layers[nk] = layer;
                std::fprintf(stderr, "[I] cocoa_v2: surface layer %d created (%dx%d)\n",
                             key, width, height);
            }
            if (!MacapkApplySurfaceLayerGeometry(MacapkMainThreadEnv(), nk, layer)) return;
            layer.contents = imageObj;
        };
        if ([NSThread isMainThread]) mainBlock();
        else dispatch_async(dispatch_get_main_queue(), mainBlock);
    }
}

// ---------------------------------------------------------------------------
// CocoaPresentFrameFrom: the IOSurface-ring present (P2) with the exact diff,
// elision and validator (P3) of docs/canvas-present-cost-at-full-screen_2026-09-25.md.
// NativeCocoa.presentFrame -> native_methods.cpp Cocoa_presentFrame -> here.
// Behind MACAPK_PRESENT_IOSURFACE, which ViewCanvasDriver reads. It has been
// default ON since v1.0.1649, after the live F6 exactness check passed (verify
// mode 0 mismatches, recap bit-stable, window captures within one step per
// channel on saturated colours; see the design note). With it =0 nothing here
// runs and the ring never exists.
//
// Per frame, on the Java main-loop thread:
//   1. PIXEL WINDOW (the JNI pin; see THE PIXEL WINDOW above): under g_snap_mu,
//      diff the frame against the SHADOW (g_snap_canvas, the straight-ARGB copy
//      of the last frame presented on the layer) row by row in 64-byte NEON
//      blocks, copying each changed span into the shadow as it is found. With
//      FORCE_FULL, a new size, an empty shadow or MACAPK_PRESENT_DIFF=0, copy it
//      all instead. Nothing else happens inside the pin.
//   2. Under g_pool_mu (MacapkPoolCore::Plan): union the spans into every
//      surface's stale set; ELIDE when the surface the layer will show lacks
//      nothing and the host has not changed; else claim a target that is not
//      FRONT, not PENDING and not IOSurfaceIsInUse. When none is and the ring
//      has fewer than 4, Plan says so, the lock is DROPPED while a surface is
//      created, and the plan is redone with it added.
//   3. Outside every lock but g_snap_mu: IOSurfaceLock the target, premultiply
//      ITS stale spans from the shadow, unlock. If a legacy present rewrote the
//      shadow since step 1 (g_snap_canvas_seq moved), the whole target is
//      written instead, so it holds one whole frame and never a mix of two.
//   4. Dispatch: the main block runs the same host selection as the CGImage
//      path and sets the surface as the overlay's contents.
// With no target free the frame takes the CGImage path from the shadow
// (FALLBACK), which is always correct because stale sets are kept relative to
// the shadow, not to FRONT.
//
// The self-capture, the canvas dump and the periodic score all read the
// shadow, which after step 1 is exactly this frame; a CGImage is built only
// when a capture actually asks for one.
//
// COLOUR. CocoaPresentBitmap's CGImage is DeviceRGB. Measured on this macOS 26
// host (debug/present_iosurface_verify, the colour probe): CGColorSpaceCopy-
// PropertyList(DeviceRGB) serializes as the NAME kCGColorSpaceSRGB, and a
// DeviceRGB image colour-matches to the display (and to P3) bit-identically to
// an sRGB-tagged one, 0 of 4096 pixels differing while matching itself moved
// 98% of them. So the default tag is DeviceRGB's own serialization -- which
// reads back as sRGB, AOSP's default colour space too -- and it is switchable
// for the F6 check: MACAPK_PRESENT_IOSURFACE_CS=device (default) | srgb |
// display (the main display's own profile: no matching at all) | none
// (untagged: whatever CoreAnimation assumes).
//
// Gates: MACAPK_PRESENT_DIFF=0 (ring, but a full upload every present and no
// elision -- isolates the diff), MACAPK_PRESENT_VERIFY=1 (after every write,
// and on every elision, compare the whole surface with premultiply(shadow) and
// log mismatching rows), MACAPK_PRESENT_TRACE=1 (per-300 aggregates).
namespace {
bool MacapkPresentEnvIs(const char* name, const char* value) {
    const char* e = std::getenv(name);
    return e && std::strcmp(e, value) == 0;
}
bool MacapkPresentDiffOn() {
    static const bool on = !MacapkPresentEnvIs("MACAPK_PRESENT_DIFF", "0");
    return on;
}
bool MacapkPresentVerifyOn() {
    static const bool on = MacapkPresentEnvIs("MACAPK_PRESENT_VERIFY", "1");
    return on;
}
bool MacapkPresentFrameTraceOn() {
    static const bool on = MacapkPresentEnvIs("MACAPK_PRESENT_TRACE", "1");
    return on;
}

// The kIOSurfaceColorSpace value every ring surface is tagged with (see COLOUR
// above), computed once. nullptr = leave the surface untagged.
CFTypeRef MacapkIOSurfaceColorTag() {
    static CFTypeRef tag = [] {
        const char* e = std::getenv("MACAPK_PRESENT_IOSURFACE_CS");
        const char* mode = (e && *e) ? e : "device";
        CGColorSpaceRef cs = nullptr;
        if (std::strcmp(mode, "none") == 0) {
            cs = nullptr;
        } else if (std::strcmp(mode, "srgb") == 0) {
            cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
        } else if (std::strcmp(mode, "display") == 0) {
            cs = CGDisplayCopyColorSpace(CGMainDisplayID());
        } else {
            mode = "device";
            cs = CGColorSpaceCreateDeviceRGB();
        }
        CFPropertyListRef pl = cs ? CGColorSpaceCopyPropertyList(cs) : nullptr;
        if (cs) CGColorSpaceRelease(cs);
        // Say what the tag really is: a name (DeviceRGB's reads kCGColorSpaceSRGB)
        // or an embedded profile's byte length.
        char what[128] = "untagged";
        if (pl && CFGetTypeID(pl) == CFStringGetTypeID())
            CFStringGetCString((CFStringRef) pl, what, sizeof(what), kCFStringEncodingUTF8);
        else if (pl && CFGetTypeID(pl) == CFDataGetTypeID())
            std::snprintf(what, sizeof(what), "icc %ld bytes", (long) CFDataGetLength((CFDataRef) pl));
        else if (pl)
            std::snprintf(what, sizeof(what), "plist");
        std::fprintf(stderr, "[present-iosurface] colour tag %s -> %s\n", mode, what);
        std::fflush(stderr);
        return (CFTypeRef) pl;
    }();
    return tag;
}

// One ring surface: BGRA, 4 bytes per pixel, rows aligned the way IOSurface
// wants them (IOSurfaceGetBytesPerRow is honoured everywhere), colour-tagged.
IOSurfaceRef MacapkCreateCanvasSurface(int w, int h) {
    @autoreleasepool {
        const size_t bpr = IOSurfaceAlignProperty(kIOSurfaceBytesPerRow, (size_t) w * 4);
        NSDictionary* props = @{
            (id) kIOSurfaceWidth:           @(w),
            (id) kIOSurfaceHeight:          @(h),
            (id) kIOSurfaceBytesPerElement: @(4),
            (id) kIOSurfaceBytesPerRow:     @(bpr),
            (id) kIOSurfacePixelFormat:     @(kCVPixelFormatType_32BGRA),
        };
        IOSurfaceRef s = IOSurfaceCreate((__bridge CFDictionaryRef) props);
        if (!s) return nullptr;
        if (CFTypeRef tag = MacapkIOSurfaceColorTag()) IOSurfaceSetValue(s, kIOSurfaceColorSpace, tag);
        return s;
    }
}

// premultiply(shadow) as a CGImage in CocoaPresentBitmap's exact format: the
// FALLBACK frame's contents and the self-capture's source. Caller holds
// g_snap_mu (the shadow is read). Returns +1, or nullptr.
CGImageRef MacapkCGImageFromShadowLocked(int w, int h) {
    const size_t bytes = (size_t) w * (size_t) h * 4;
    void* copy = std::malloc(bytes);
    if (!copy) return nullptr;
    macapk_premultiply_argb(static_cast<uint32_t*>(copy),
                            reinterpret_cast<const uint32_t*>(g_snap_canvas.data()),
                            (size_t) w * (size_t) h);
    CGDataProviderRef provider = CGDataProviderCreateWithData(
        nullptr, copy, bytes,
        [](void*, const void* data, size_t) { std::free(const_cast<void*>(data)); });
    if (!provider) { std::free(copy); return nullptr; }
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGImageRef img = CGImageCreate((size_t) w, (size_t) h, 8, 32, (size_t) w * 4, cs,
        kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst,
        provider, nullptr, false, kCGRenderingIntentDefault);
    CGColorSpaceRelease(cs);
    CGDataProviderRelease(provider);
    return img;
}

// MACAPK_PRESENT_VERIFY: the whole surface against premultiply(shadow). Caller
// holds g_snap_mu. Logs mismatching rows; returns their count.
int MacapkVerifySurfaceLocked(IOSurfaceRef s, int w, int h, const char* when) {
    if (IOSurfaceLock(s, kIOSurfaceLockReadOnly, nullptr) != kIOReturnSuccess) return -1;
    static std::vector<uint32_t> scratch;   // present thread only
    int fy = -1, fx = -1;
    uint32_t want = 0, got = 0;
    const int bad = macapk_verify_surface(
        static_cast<const uint32_t*>(IOSurfaceGetBaseAddress(s)),
        IOSurfaceGetBytesPerRow(s) / 4,
        reinterpret_cast<const uint32_t*>(g_snap_canvas.data()), w, h,
        &scratch, &fy, &fx, &want, &got);
    IOSurfaceUnlock(s, kIOSurfaceLockReadOnly, nullptr);
    if (bad > 0) {
        std::fprintf(stderr,
            "[present-verify] MISMATCH after %s: %d rows differ from premultiply(shadow); "
            "first y=%d x=%d want=%08x got=%08x\n", when, bad, fy, fx, want, got);
        std::fflush(stderr);
    }
    return bad;
}

// Per-300 aggregates for MACAPK_PRESENT_TRACE (present thread only) and the
// always-on [heartbeat-diff] metric. Plain statics: one writer.
struct MacapkFrameTrace {
    unsigned n = 0, published = 0, elided = 0, fallback = 0, full = 0, verified_bad = 0;
    double sWin = 0, mWin = 0, sWrite = 0, mWrite = 0;
    unsigned long long rows = 0, px = 0, written_px = 0;
};
MacapkFrameTrace g_ftrace;
unsigned long long g_present_fallbacks = 0;   // lifetime, present thread only
unsigned long long g_present_verify_bad = 0;  // lifetime VERIFY mismatches, present thread only
// One VERIFY mismatch, into both counts: the trace's per-300 `verify-bad=` and
// the [present-verify] summary's lifetime total.
void MacapkNoteVerifyBad() {
    ++g_ftrace.verified_bad;
    ++g_present_verify_bad;
}

// [heartbeat-diff] (P3's heartbeat coverage metric): on an M6 heartbeat frame
// nothing scheduled a traversal, so any pixel that changed is a mutation that
// never invalidated -- a framework gap, named with its coordinates. Zero across
// the fleet is the evidence that would license widening the heartbeat
// (docs/damage-aware-partial-redraw-design_2026-08-30.md section 6 forbids it
// without that). Note that OUR status-bar clock is repainted on every traversed
// frame, so a change confined to the top bar band is the clock, once a minute.
struct MacapkHeartbeatDiff {
    unsigned frames = 0, changed = 0, detail_lines = 0;
    unsigned long long rows = 0;
};
MacapkHeartbeatDiff g_hbdiff;
void MacapkNoteHeartbeatDiff(const MacapkStaleSet& chg, int w) {
    MacapkHeartbeatDiff& d = g_hbdiff;
    ++d.frames;
    if (!chg.Empty()) {
        ++d.changed;
        d.rows += (unsigned long long) chg.rows;
        int y0 = -1, y1 = -1, x0 = w, x1 = 0;
        for (int y = 0; y < chg.Height(); ++y) {
            if (chg.x0[(size_t) y] >= chg.x1[(size_t) y]) continue;
            if (y0 < 0) y0 = y;
            y1 = y + 1;
            x0 = std::min(x0, (int) chg.x0[(size_t) y]);
            x1 = std::max(x1, (int) chg.x1[(size_t) y]);
        }
        // Every one of the first 20, then one in 50: a real gap names itself
        // without flooding the log if it recurs on every heartbeat.
        if (d.detail_lines < 20 || (d.changed % 50) == 0) {
            ++d.detail_lines;
            std::fprintf(stderr,
                "[heartbeat-diff] un-invalidated change: rows=%d bbox=x%d-%d,y%d-%d\n",
                chg.rows, x0, x1, y0, y1);
        }
    }
    // One summary per 10 heartbeats: M6 fires one every 30 vsyncs, so this is
    // the same ~300-frame window as the `M6: skipped N/300` line.
    if (d.frames == 10) {
        std::fprintf(stderr, "[heartbeat-diff] changed=%u/%u heartbeat frames rows=%llu\n",
                     d.changed, d.frames, d.rows);
        std::fflush(stderr);
        d.frames = d.changed = 0;
        d.rows = 0;
    }
}
}  // namespace

// Flags, mirrored by NativeCocoa.PRESENT_FLAG_*.
static constexpr int kMacapkPresentFlagForceFull = 1;   // resize, a new driver, a new bitmap
static constexpr int kMacapkPresentFlagValidate  = 2;   // diff the whole frame, log under-damage
static constexpr int kMacapkPresentFlagHeartbeat = 4;   // an M6 heartbeat frame (scheduled=false)

int CocoaPresentFrameFrom(int width, int height, int bl, int bt, int br, int bb, int flags,
                          MacapkPixelsAcquireFn acquire, MacapkPixelsReleaseFn release,
                          void* ctx) {
    // Same leak, same reason as CocoaPresentBitmap: the Java main-loop thread's
    // implicit pool never pops, and the fallback and capture autorelease.
    @autoreleasepool {
        if (width <= 0 || height <= 0) return kMacapkPresentNone;   // never acquired
        // One present at a time. The only caller is ViewCanvasDriver on the one
        // Java main-loop thread, but the scratch sets below are statics and a
        // second caller would corrupt them silently, so say it in code. Taken
        // BEFORE the pin, so a thread waiting here is never inside a critical
        // region, and the holder never waits on anything a waiter holds.
        static std::mutex present_mu;
        std::lock_guard<std::mutex> present_lk(present_mu);
        const bool trace = MacapkPresentFrameTraceOn();
        const bool validate = (flags & kMacapkPresentFlagValidate) != 0;
        // The claimed bound, clamped. It is the whole surface until the view half
        // (docs/damage-aware-partial-redraw-design_2026-08-30.md S1) supplies damage.
        bl = std::max(0, std::min(bl, width));   br = std::max(bl, std::min(br, width));
        bt = std::max(0, std::min(bt, height));  bb = std::max(bt, std::min(bb, height));
        const size_t npx = (size_t) width * (size_t) height;
        const int dump_n = MacapkCanvasDumpFrameNumber();
        std::vector<uint8_t> dump_px;
        if (dump_n) dump_px.resize(npx * 4);
        // Present-thread scratch, reused frame to frame so nothing allocates
        // inside the pin once the size has settled.
        static MacapkStaleSet chg, outside, write_set;
        bool full = (flags & kMacapkPresentFlagForceFull) != 0 || !MacapkPresentDiffOn();
        int changed_rows = 0;
        // The shadow's legacy-write count as this frame's diff leaves it; step 3
        // compares it under its own lock (macapk_write_set_after_foreign).
        uint64_t snap_seq = 0;
        double tW0 = trace ? CACurrentMediaTime() : 0.0, tW1 = 0.0;

        // ---- 1. the pixel window: diff + span copy into the shadow, nothing else.
        {
            MacapkPixelWindow win(acquire, release, ctx);
            const uint32_t* pixels = win.pixels();
            if (!pixels) return kMacapkPresentNone;
            std::lock_guard<std::mutex> lk(g_snap_mu);
            snap_seq = g_snap_canvas_seq;
            if (g_snap_canvas_w != width || g_snap_canvas_h != height
                || g_snap_canvas.size() != npx * 4)
                full = true;
            if (full) {
                g_snap_canvas.resize(npx * 4);
                std::memcpy(g_snap_canvas.data(), pixels, npx * 4);
                g_snap_canvas_w = width; g_snap_canvas_h = height;
                chg.Reset(height);
                chg.SetFull(width);
                changed_rows = height;
            } else {
                // Only the claimed bound is diffed: the claim is trusted exactly as
                // AOSP trusts its dirty rect. VALIDATE diffs the rest too and names
                // every change there (and applies it).
                changed_rows = macapk_diff_frame(
                    pixels, reinterpret_cast<uint32_t*>(g_snap_canvas.data()), width, height,
                    bl, bt, br, bb, &chg, validate ? &outside : nullptr);
            }
            if (dump_n) std::memcpy(dump_px.data(), pixels, npx * 4);
            win.close();
        }
        if (trace) tW1 = CACurrentMediaTime();
        if (validate && !full && !outside.Empty()) {
            static unsigned logged = 0;
            for (int y = 0; y < height && logged < 64; ++y) {
                if (outside.x0[(size_t) y] >= outside.x1[(size_t) y]) continue;
                ++logged;
                std::fprintf(stderr,
                    "[damage-validate] under-damage y=%d x=%d-%d (claimed x=%d-%d y=%d-%d)\n",
                    y, (int) outside.x0[(size_t) y], (int) outside.x1[(size_t) y], bl, br, bt, bb);
            }
            std::fflush(stderr);
        }
        if ((flags & kMacapkPresentFlagHeartbeat) != 0 && !full)
            MacapkNoteHeartbeatDiff(chg, width);

        // ---- 2. the ring's decision.
        int result = kMacapkPresentNone;
        int target = -1;
        IOSurfaceRef surf = nullptr;
        uint64_t gen = 0;
        const uint32_t epoch = g_canvas_host_epoch.load(std::memory_order_acquire);
        void* released[MacapkPoolCore::kMax];
        int nreleased = 0;
        // The colour tag's one-time CGColorSpace work, done here, outside every
        // lock, before the first Plan (it is otherwise first reached from the
        // surface creation below, which is also outside the lock).
        (void) MacapkIOSurfaceColorTag();
        // Plan; while it answers NEED_SURFACE, create one with g_pool_mu DROPPED
        // (see the lock's comment), then Add it and plan again: at most one
        // creation per missing slot, since each round either Adds or stops the
        // growth. Only this thread resizes or adds (present_mu), so a later round
        // never resizes and always has room for what it created.
        IOSurfaceRef fresh = nullptr;
        bool may_grow = true;
        int grew_to = 0;
        for (;;) {
            IOSurfaceRef spare = nullptr;
            {
                std::lock_guard<std::mutex> lk(g_pool_mu);
                if (g_pool.w != width || g_pool.h != height) {
                    nreleased = g_pool.Resize(width, height, released);
                    full = true;
                }
                g_pool_live.store(true, std::memory_order_release);
                if (fresh) {
                    if (g_pool.n < MacapkPoolCore::kMax) grew_to = g_pool.Add(fresh) + 1;
                    else spare = fresh;   // unreachable (see above); never leak it
                    fresh = nullptr;
                }
                result = g_pool.Plan(chg, full, MacapkPresentDiffOn(), epoch, may_grow,
                    [](void* s) { return IOSurfaceIsInUse((IOSurfaceRef) s) != 0; },
                    &target);
                if (result == kMacapkPresentPublished) {
                    g_pool.TakeStale(target, &write_set);
                    surf = (IOSurfaceRef) g_pool.slot[target].surf;
                    gen = g_pool.gen;
                }
            }
            if (spare) CFRelease(spare);
            if (result != kMacapkPresentNeedSurface) break;
            fresh = MacapkCreateCanvasSurface(width, height);
            if (!fresh) may_grow = false;   // plan once more: a freed slot, or FALLBACK
        }
        for (int k = 0; k < nreleased; ++k) CFRelease((CFTypeRef) released[k]);
        if (grew_to > MacapkPoolCore::kRing)
            std::fprintf(stderr,
                "[present-iosurface] ring grew to %d (every surface busy)\n", grew_to);

        // ---- 3. write the target's stale spans from the shadow.
        double tX0 = trace ? CACurrentMediaTime() : 0.0;
        size_t written = 0;
        bool foreign_mid = false;
        if (result == kMacapkPresentPublished) {
            std::lock_guard<std::mutex> lk(g_snap_mu);
            // The shadow can only have changed size under a foreign present of a
            // different size since step 1, which also invalidated every stale
            // set; write nothing and let the fallback show the shadow as it is.
            const bool shadow_ok = g_snap_canvas_w == width && g_snap_canvas_h == height;
            // A same-size foreign present since step 1 replaced the shadow the
            // target's spans were planned against: write the target whole.
            if (shadow_ok)
                foreign_mid = macapk_write_set_after_foreign(snap_seq, g_snap_canvas_seq,
                                                             width, height, &write_set);
            if (shadow_ok && IOSurfaceLock(surf, 0, nullptr) == kIOReturnSuccess) {
                written = macapk_write_stale_rows(
                    static_cast<uint32_t*>(IOSurfaceGetBaseAddress(surf)),
                    IOSurfaceGetBytesPerRow(surf) / 4,
                    reinterpret_cast<const uint32_t*>(g_snap_canvas.data()), width, write_set);
                IOSurfaceUnlock(surf, 0, nullptr);
                if (MacapkPresentVerifyOn()
                    && MacapkVerifySurfaceLocked(surf, width, height, "write") > 0)
                    MacapkNoteVerifyBad();
            } else {
                std::lock_guard<std::mutex> plk(g_pool_mu);
                if (g_pool.gen == gen) g_pool.Abandon(target);
                result = kMacapkPresentFallback;
                surf = nullptr;
                foreign_mid = false;   // nothing written: the FALLBACK line covers it
            }
        } else if (result == kMacapkPresentElided && MacapkPresentVerifyOn()) {
            // The elision's own claim: the surface the layer will show is the
            // shadow. Check it, whole.
            IOSurfaceRef shown = nullptr;
            {
                std::lock_guard<std::mutex> plk(g_pool_mu);
                if (g_pool.newest >= 0) {
                    shown = (IOSurfaceRef) g_pool.slot[g_pool.newest].surf;
                    CFRetain(shown);
                }
            }
            if (shown) {
                std::lock_guard<std::mutex> lk(g_snap_mu);
                // Unless a legacy present has replaced the shadow since step 1:
                // then the elided surface rightly shows the frame before it, and
                // that present's own CGImage is on its way to the layer.
                if (g_snap_canvas_seq == snap_seq
                    && MacapkVerifySurfaceLocked(shown, width, height, "elide") > 0)
                    MacapkNoteVerifyBad();
                CFRelease(shown);
            }
        }
        double tX1 = trace ? CACurrentMediaTime() : 0.0;
        if (foreign_mid) {
            // Rare by construction (two presents racing on one layer), and each
            // one is a full-surface write, so say so rather than let it read as
            // an unexplained slow frame.
            static unsigned long long foreign_mids = 0;   // present thread only
            ++foreign_mids;
            if (foreign_mids <= 5 || (foreign_mids % 300) == 0)
                std::fprintf(stderr,
                    "[present-iosurface] legacy present rewrote the shadow mid-frame #%llu: "
                    "whole-surface write\n", foreign_mids);
        }

        // ---- 4. publish.
        if (result == kMacapkPresentPublished) {
            CFRetain(surf);   // the block's reference; released at its end
            const int t = target;
            void (^mainBlock)(void) = ^{
                // Host selection + (re)attach, shared with the CGImage path. A
                // freshly created overlay layer is made only here, and its
                // contents are set on the very next line, so an elision can never
                // leave a new layer empty.
                if (!MacapkCanvasOverlayForPresent()) {
                    {
                        std::lock_guard<std::mutex> lk(g_pool_mu);
                        g_pool.Dropped(t, gen);
                    }
                    // Outside g_pool_mu: after a Resize this is the LAST reference.
                    CFRelease(surf);
                    return;
                }
                if (MacapkPresentFrameTraceOn()) {
                    double ts0 = CACurrentMediaTime();
                    g_canvas_overlay_layer.contents = (__bridge id) surf;
                    double dSet = CACurrentMediaTime() - ts0;
                    static double sSet = 0, mSet = 0;   // main thread only
                    static unsigned setN = 0;
                    sSet += dSet;
                    if (dSet > mSet) mSet = dSet;
                    if (++setN == 300) {
                        std::fprintf(stderr,
                            "[present-trace] iosurface n=300 setContents avg=%.3f max=%.3f (ms)\n",
                            sSet / setN * 1e3, mSet * 1e3);
                        std::fflush(stderr);
                        sSet = 0; mSet = 0; setN = 0;
                    }
                } else {
                    g_canvas_overlay_layer.contents = (__bridge id) surf;
                }
                {
                    std::lock_guard<std::mutex> lk(g_pool_mu);
                    g_pool.Committed(t, gen);
                }
                CFRelease(surf);
            };
            if ([NSThread isMainThread]) mainBlock();
            else dispatch_async(dispatch_get_main_queue(), mainBlock);
        } else if (result == kMacapkPresentFallback) {
            ++g_present_fallbacks;
            if (g_present_fallbacks <= 5 || (g_present_fallbacks % 300) == 0)
                std::fprintf(stderr,
                    "[present-iosurface] FALLBACK #%llu: no ring surface free, "
                    "CGImage path for this frame\n", g_present_fallbacks);
            CGImageRef img = nullptr;
            {
                std::lock_guard<std::mutex> lk(g_snap_mu);
                if (g_snap_canvas_w > 0 && g_snap_canvas_h > 0)
                    img = MacapkCGImageFromShadowLocked(g_snap_canvas_w, g_snap_canvas_h);
            }
            if (img) {
                id imageObj = CFBridgingRelease(img);
                void (^mainBlock)(void) = ^{
                    if (!MacapkCanvasOverlayForPresent()) return;
                    g_canvas_overlay_layer.contents = imageObj;
                    std::lock_guard<std::mutex> lk(g_pool_mu);
                    g_pool.Committed(-1, g_pool.gen);
                };
                if ([NSThread isMainThread]) mainBlock();
                else dispatch_async(dispatch_get_main_queue(), mainBlock);
            }
        }

        // ---- what the CGImage path did after its publish, from the shadow.
        if (SelfCaptureRequested("canvas")) {
            CGImageRef img = nullptr;
            {
                std::lock_guard<std::mutex> lk(g_snap_mu);
                if (g_snap_canvas_w > 0 && g_snap_canvas_h > 0)
                    img = MacapkCGImageFromShadowLocked(g_snap_canvas_w, g_snap_canvas_h);
            }
            if (img) {
                SelfCaptureWriteCGImageForCanvas((void*) img, "canvas");
                CGImageRelease(img);
            }
        }
        if (dump_n) MacapkWriteCanvasDumpPPM(dump_n, dump_px.data(), width, height);
        if (const unsigned every = SelfCaptureScorePeriod()) {
            static std::atomic<unsigned> ticks{0};
            if ((ticks.fetch_add(1, std::memory_order_relaxed) % every) == 0)
                MacapkScoreSnapshotAsync(/*dc=*/false);
        }

        if (trace) {
            MacapkFrameTrace& t = g_ftrace;
            const double dWin = tW1 - tW0, dWrite = tX1 - tX0;
            t.sWin += dWin;     if (dWin > t.mWin) t.mWin = dWin;
            t.sWrite += dWrite; if (dWrite > t.mWrite) t.mWrite = dWrite;
            t.rows += (unsigned long long) changed_rows;
            for (int y = 0; y < chg.Height(); ++y)
                if (chg.x0[(size_t) y] < chg.x1[(size_t) y])
                    t.px += (unsigned long long) (chg.x1[(size_t) y] - chg.x0[(size_t) y]);
            t.written_px += written;
            if (full) ++t.full;
            if (result == kMacapkPresentPublished) ++t.published;
            else if (result == kMacapkPresentElided) ++t.elided;
            else if (result == kMacapkPresentFallback) ++t.fallback;
            if (++t.n == 300) {
                int ring = 0;
                { std::lock_guard<std::mutex> lk(g_pool_mu); ring = g_pool.n; }
                std::fprintf(stderr,
                    "[present-trace] iosurface n=300 window(diff+copy, pinned) avg=%.2f max=%.2f  "
                    "write avg=%.2f max=%.2f (ms)  changed rows/frame=%.1f px/frame=%.0f "
                    "written px/frame=%.0f  published=%u elided=%u fallback=%u full=%u ring=%d "
                    "verify-bad=%u\n",
                    t.sWin / t.n * 1e3, t.mWin * 1e3, t.sWrite / t.n * 1e3, t.mWrite * 1e3,
                    (double) t.rows / t.n, (double) t.px / t.n, (double) t.written_px / t.n,
                    t.published, t.elided, t.fallback, t.full, ring, t.verified_bad);
                std::fflush(stderr);
                t = MacapkFrameTrace();
            }
        }
        if (MacapkPresentVerifyOn()) {
            // VERIFY states a clean result out loud, so "0 mismatching rows" is a
            // line in the log rather than an absence -- whether or not
            // MACAPK_PRESENT_TRACE is also set. It counts from its own lifetime
            // total: the trace's per-300 struct (verify-bad=) resets to zero, and
            // a difference taken across that reset would underflow.
            static unsigned vn = 0;
            static unsigned long long vbad_seen = 0;
            if (++vn == 300) {
                std::fprintf(stderr, "[present-verify] n=300 presents checked, mismatching=%llu\n",
                             g_present_verify_bad - vbad_seen);
                std::fflush(stderr);
                vbad_seen = g_present_verify_bad;
                vn = 0;
            }
        }
        return result;
    }
}

// CocoaPresentOverlayBitmap / CocoaClearOverlayBitmap (2026-07-23): the
// GL-surface twin of CocoaPresentBitmap above, for WindowManager overlays
// (Dialog/AlertDialog/PopupWindow) shown by an app or SDK on top of a
// cocos2d-x/AndEngine/libGDX/etc. game. Composites onto g_last_gl_metal_view
// (set by CocoaCreateMetalSurfaceViewForGl) through its OWN layer + attached-
// view tracking, deliberately separate from g_canvas_overlay_layer /
// g_overlay_attached_view so this never interacts with the Path-A Canvas-
// frame mechanism those serve (which must stay off the GL view -- see that
// function's comment). WindowOverlayStack.presentForGl (Java side) always
// renders a bitmap the SAME size as the current GL surface (transparent
// where there is no overlay content), so -- like CocoaPresentBitmap -- a
// plain stretch-to-fill is a no-op in practice and no separate position/
// size bookkeeping is needed here.
//
// pixels format: same as CocoaPresentBitmap (Android ARGB int[], BGRA +
// premultiplied-first on this little-endian host).
void CocoaPresentOverlayBitmapFrom(int width, int height, MacapkPixelsAcquireFn acquire,
                                   MacapkPixelsReleaseFn release, void* ctx);

// The GL overlay's host selection and (re)attach, run by EVERY GL-overlay
// present's main block before it sets the layer's contents -- the CGImage path
// and the IOSurface ring below alike, so the two can never disagree about where
// a dialog over a GL game goes. Main thread only. Returns the overlay layer, or
// nil when no GL view exists yet (the frame is dropped, as it always was).
// Moved out of CocoaPresentOverlayBitmapFrom's block verbatim on 2026-09-26
// (the contents/hidden stores stay with each caller).
static CALayer* MacapkGlOverlayForPresent() {
    MTKView* view = g_last_gl_metal_view;
    if (!view) return nil;
    if (!g_gl_overlay_layer || g_gl_overlay_attached_view != view) {
        if (g_gl_overlay_layer) {
            [g_gl_overlay_layer removeFromSuperlayer];
            g_gl_overlay_layer = nil;
        }
        CALayer* overlay = [CALayer layer];
        overlay.frame = view.layer.bounds;
        overlay.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;
        overlay.contentsGravity = kCAGravityResize;
        overlay.opaque = NO;          // the dialog only covers PART of the screen
        overlay.masksToBounds = YES;
        // Above both the framework angleLayer AND the dedicated Unity/
        // native-EGL layer (g_unity_layer / native-EGL dedicated layer),
        // which sit at zPosition 0 -- same reasoning as the Path-A
        // overlay above: a real modal Dialog must draw on top of
        // whatever the engine is rendering.
        overlay.zPosition = 1.0;
        // Suppress the implicit 0.25s contents crossfade -- see
        // MacapkOverlayImplicitFade above. A per-frame-updated Dialog/
        // PopupWindow overlay smears exactly like the Path-A one.
        if (!MacapkOverlayImplicitFade())
            overlay.actions = @{ @"contents": [NSNull null],
                                 @"bounds":   [NSNull null],
                                 @"position": [NSNull null] };
        [view.layer addSublayer:overlay];
        g_gl_overlay_layer = overlay;
        g_gl_overlay_attached_view = view;
        std::fprintf(stderr,
            "[I] cocoa_v2: GL overlay (re)attached to MTKView=%p\n",
            (void*) view);
    }
    g_gl_overlay_layer.contentsScale = view.layer.contentsScale;
    g_gl_overlay_layer.frame = view.layer.bounds;
    return g_gl_overlay_layer;
}

// ---- FRAMESPEED: the GL overlay through its own IOSurface ring (2026-09-26) ----
// (docs/frame-speed-canvas-hotspots_2026-09-26.md, "GL overlay")
//
// The P2/P3 treatment of the canvas present (CocoaPresentFrameFrom), for the
// Dialog / PopupWindow composited over a GL game. WindowOverlayStack.presentForGl
// hands us a surface-sized frame on every dirty present (a touch delivered to
// the dialog, an add / update / remove). The CGImage path premultiplied all of
// it into a fresh malloc, built a CGImage and let CoreAnimation copy the whole
// image again on the AppKit main thread: at the 3360x1796 window 24 MB twice
// over, per tap. Here, as on the canvas path:
//   * an exact row diff against the last overlay frame (g_ovl_shadow) finds
//     what changed; a present that changed NOTHING is elided outright (no
//     write, no dispatch -- a tap on a dialog area that does not repaint);
//   * a ring of BGRA IOSurfaces (MacapkPoolCore, the gate-proven bookkeeping of
//     debug/present_iosurface_gate.sh, its own instance) is written with only
//     the stale spans and set as the layer's contents, so CoreAnimation takes
//     the surface by reference instead of copying;
//   * no ring surface free -> the CGImage path for that one frame, from the
//     shadow, exactly as the canvas path falls back.
// Differences from the canvas ring, each deliberate: there is no FOREIGN
// present on this layer (only this function and CocoaClearOverlayBitmap touch
// it), so no shared shadow lock and no mid-frame sequence check; the shadow is
// read and written only under this present's own mutex. CocoaClearOverlayBitmap
// forgets `newest` SYNCHRONOUSLY, on the calling thread, so a dialog shown again
// with identical pixels right after a dismiss publishes instead of eliding
// against a layer the queued clear is about to empty. A host change (a new GL
// view) bumps g_canvas_host_epoch, which also forbids the next elision, so the
// main block's MacapkGlOverlayForPresent migrates the layer as before.
// CocoaClearOverlayBitmap also hands the ring's surfaces and the shadow back
// (2-4 IOSurfaces + one shadow, ~24 MB each at 3360x1796): a transient dialog
// must not leave ~100 MB resident for the life of a GL game. Its Resize bumps
// the pool's `gen`, and every main block this ring queues checks `gen` first
// and does nothing when it moved, so a present queued BEFORE a clear can never
// put the dismissed dialog back (the CGImage path has that race when the clear
// runs inline on the main thread; this path does not).
//
// ⚠ OPT-IN (fix pass, 2026-09-26): MACAPK_FRAMESPEED_OVERLAY_IOSURFACE=1 turns
// the ring on; unset or anything else is the CGImage path, exactly as before
// this change. It stays opt-in until it has been BUILT and run live through an
// installed stub on a GL app with a dialog, with MACAPK_PRESENT_TRACE=1
// MACAPK_PRESENT_VERIFY=1: `[present-trace] overlay-iosurface` published>0,
// `[present-verify] overlay` mismatching=0, the dialog visible and hittable,
// and a dismiss + identical re-show publishing. The failure it must not cause is
// documented at SurfaceView.java:825-833: a dialog over GL that is modal and
// hit-tested but draws nowhere (HCR 1.8.1's AdManager dialog ate every tap).
// MACAPK_PRESENT_DIFF=0 keeps the ring but writes the whole surface every time.
namespace {
bool MacapkOverlayRingOn() {
    static const bool on = MacapkPresentEnvIs("MACAPK_FRAMESPEED_OVERLAY_IOSURFACE", "1");
    return on;
}
// One overlay present (or ring teardown) at a time. Held across the pixel
// window, the plan, the write and the dispatch, so the shadow, the scratch sets
// and a slot's surface between Plan and the block's CFRetain are never seen
// half-done -- which is what lets CocoaClearOverlayBitmap release the ring.
std::mutex           g_ovl_present_mu;
std::mutex           g_ovl_pool_mu;   // bookkeeping only; the same rules as g_pool_mu
MacapkPoolCore       g_ovl_pool;
std::vector<uint8_t> g_ovl_shadow;    // straight ARGB of the last overlay frame
int                  g_ovl_shadow_w = 0, g_ovl_shadow_h = 0;

// MACAPK_PRESENT_VERIFY for the overlay ring: the whole surface against
// premultiply(g_ovl_shadow). Caller holds g_ovl_present_mu. Every check is
// counted, so a clean run says so out loud (`mismatching=0`) instead of being
// an absence; the first check and every 30th print the running totals.
void MacapkOverlayVerify(IOSurfaceRef s, int w, int h, const char* when) {
    static std::vector<uint32_t> scratch;          // under g_ovl_present_mu
    static unsigned long long checked = 0, bad_total = 0;
    int bad = -1;
    if (IOSurfaceLock(s, kIOSurfaceLockReadOnly, nullptr) == kIOReturnSuccess) {
        int fy = -1, fx = -1;
        uint32_t want = 0, got = 0;
        bad = macapk_verify_surface(
            static_cast<const uint32_t*>(IOSurfaceGetBaseAddress(s)),
            IOSurfaceGetBytesPerRow(s) / 4,
            reinterpret_cast<const uint32_t*>(g_ovl_shadow.data()), w, h,
            &scratch, &fy, &fx, &want, &got);
        IOSurfaceUnlock(s, kIOSurfaceLockReadOnly, nullptr);
        if (bad > 0)
            std::fprintf(stderr,
                "[present-verify] overlay MISMATCH after %s: %d rows differ from "
                "premultiply(shadow); first y=%d x=%d want=%08x got=%08x\n",
                when, bad, fy, fx, want, got);
    }
    ++checked;
    if (bad != 0) ++bad_total;   // a surface that would not lock counts as unverified
    if (checked == 1 || (checked % 30) == 0) {
        std::fprintf(stderr, "[present-verify] overlay checked=%llu mismatching=%llu\n",
                     checked, bad_total);
        std::fflush(stderr);
    }
}

// premultiply(g_ovl_shadow) as a CGImage in the CGImage path's exact format: the
// ring's FALLBACK frame. Present thread only. Returns +1, or nullptr.
CGImageRef MacapkOverlayCGImageFromShadow(int w, int h) {
    const size_t bytes = (size_t) w * (size_t) h * 4;
    if (g_ovl_shadow.size() < bytes) return nullptr;
    void* copy = std::malloc(bytes);
    if (!copy) return nullptr;
    macapk_premultiply_argb(static_cast<uint32_t*>(copy),
                            reinterpret_cast<const uint32_t*>(g_ovl_shadow.data()),
                            (size_t) w * (size_t) h);
    CGDataProviderRef provider = CGDataProviderCreateWithData(
        nullptr, copy, bytes,
        [](void*, const void* data, size_t) { std::free(const_cast<void*>(data)); });
    if (!provider) { std::free(copy); return nullptr; }
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGImageRef img = CGImageCreate((size_t) w, (size_t) h, 8, 32, (size_t) w * 4, cs,
        kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst,
        provider, nullptr, false, kCGRenderingIntentDefault);
    CGColorSpaceRelease(cs);
    CGDataProviderRelease(provider);
    return img;
}

void MacapkPresentOverlayRing(int width, int height, MacapkPixelsAcquireFn acquire,
                              MacapkPixelsReleaseFn release, void* ctx) {
    @autoreleasepool {
        // One overlay present at a time (the scratch sets and the shadow are
        // statics; CocoaClearOverlayBitmap tears the ring down under the same
        // lock). Taken BEFORE the pin, so a waiter is never inside a critical
        // region.
        std::lock_guard<std::mutex> present_lk(g_ovl_present_mu);
        const size_t npx = (size_t) width * (size_t) height;
        static MacapkStaleSet chg, write_set;
        bool full = !MacapkPresentDiffOn();

        // ---- 1. the pixel window: diff + span copy into the shadow, nothing else.
        {
            MacapkPixelWindow win(acquire, release, ctx);
            const uint32_t* pixels = win.pixels();
            if (!pixels) return;
            if (g_ovl_shadow_w != width || g_ovl_shadow_h != height
                || g_ovl_shadow.size() != npx * 4)
                full = true;
            if (full) {
                g_ovl_shadow.resize(npx * 4);
                std::memcpy(g_ovl_shadow.data(), pixels, npx * 4);
                g_ovl_shadow_w = width; g_ovl_shadow_h = height;
                chg.Reset(height);
                chg.SetFull(width);
            } else {
                macapk_diff_frame(pixels, reinterpret_cast<uint32_t*>(g_ovl_shadow.data()),
                                  width, height, 0, 0, width, height, &chg, nullptr);
            }
            win.close();
        }

        // ---- 2. the ring's decision (the canvas ring's loop, on g_ovl_pool).
        int result = kMacapkPresentNone;
        int target = -1;
        IOSurfaceRef surf = nullptr;
        uint64_t gen = 0;
        const uint32_t epoch = g_canvas_host_epoch.load(std::memory_order_acquire);
        void* released[MacapkPoolCore::kMax];
        int nreleased = 0;
        (void) MacapkIOSurfaceColorTag();   // its one-time work, outside every lock
        IOSurfaceRef fresh = nullptr;
        bool may_grow = true;
        for (;;) {
            IOSurfaceRef spare = nullptr;
            {
                std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
                if (g_ovl_pool.w != width || g_ovl_pool.h != height) {
                    nreleased = g_ovl_pool.Resize(width, height, released);
                    full = true;
                }
                if (fresh) {
                    if (g_ovl_pool.n < MacapkPoolCore::kMax) g_ovl_pool.Add(fresh);
                    else spare = fresh;
                    fresh = nullptr;
                }
                result = g_ovl_pool.Plan(chg, full, MacapkPresentDiffOn(), epoch, may_grow,
                    [](void* s) { return IOSurfaceIsInUse((IOSurfaceRef) s) != 0; },
                    &target);
                if (result == kMacapkPresentPublished) {
                    g_ovl_pool.TakeStale(target, &write_set);
                    surf = (IOSurfaceRef) g_ovl_pool.slot[target].surf;
                    gen = g_ovl_pool.gen;
                }
            }
            if (spare) CFRelease(spare);
            if (result != kMacapkPresentNeedSurface) break;
            fresh = MacapkCreateCanvasSurface(width, height);
            if (!fresh) may_grow = false;
        }
        for (int k = 0; k < nreleased; ++k) CFRelease((CFTypeRef) released[k]);

        // ---- 3. write the target's stale spans from the shadow.
        if (result == kMacapkPresentPublished) {
            if (IOSurfaceLock(surf, 0, nullptr) == kIOReturnSuccess) {
                macapk_write_stale_rows(
                    static_cast<uint32_t*>(IOSurfaceGetBaseAddress(surf)),
                    IOSurfaceGetBytesPerRow(surf) / 4,
                    reinterpret_cast<const uint32_t*>(g_ovl_shadow.data()), width, write_set);
                IOSurfaceUnlock(surf, 0, nullptr);
                if (MacapkPresentVerifyOn()) MacapkOverlayVerify(surf, width, height, "write");
            } else {
                std::lock_guard<std::mutex> plk(g_ovl_pool_mu);
                if (g_ovl_pool.gen == gen) g_ovl_pool.Abandon(target);
                result = kMacapkPresentFallback;
                surf = nullptr;
            }
        } else if (result == kMacapkPresentElided && MacapkPresentVerifyOn()) {
            // The elision's claim: the surface the layer will show IS the shadow.
            IOSurfaceRef shown = nullptr;
            {
                std::lock_guard<std::mutex> plk(g_ovl_pool_mu);
                if (g_ovl_pool.newest >= 0) {
                    shown = (IOSurfaceRef) g_ovl_pool.slot[g_ovl_pool.newest].surf;
                    if (shown) CFRetain(shown);
                }
            }
            if (shown) {
                MacapkOverlayVerify(shown, width, height, "elide");
                CFRelease(shown);
            }
        }

        // ---- 4. publish.
        if (result == kMacapkPresentPublished) {
            CFRetain(surf);   // the block's reference; released at its end
            const int t = target;
            void (^mainBlock)(void) = ^{
                // A teardown (CocoaClearOverlayBitmap) or a re-key since this
                // block was queued bumped `gen`: this frame is no longer wanted.
                // Without the check a clear that ran INLINE on the main thread
                // ahead of a queued present would be undone by it, and the
                // dismissed dialog would come back.
                bool stale;
                {
                    std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
                    stale = g_ovl_pool.gen != gen;
                }
                if (stale) { CFRelease(surf); return; }
                CALayer* layer = MacapkGlOverlayForPresent();
                if (!layer) {
                    {
                        std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
                        g_ovl_pool.Dropped(t, gen);
                    }
                    CFRelease(surf);   // outside the lock: may be the last reference
                    return;
                }
                layer.contents = (__bridge id) surf;
                layer.hidden = NO;
                {
                    std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
                    g_ovl_pool.Committed(t, gen);
                }
                CFRelease(surf);
            };
            if ([NSThread isMainThread]) mainBlock();
            else dispatch_async(dispatch_get_main_queue(), mainBlock);
        } else if (result == kMacapkPresentFallback) {
            static unsigned long long fallbacks = 0;   // present thread only
            ++fallbacks;
            if (fallbacks <= 5 || (fallbacks % 300) == 0)
                std::fprintf(stderr,
                    "[present-iosurface] overlay FALLBACK #%llu: no ring surface free, "
                    "CGImage path for this frame\n", fallbacks);
            CGImageRef img = MacapkOverlayCGImageFromShadow(width, height);
            if (img) {
                id imageObj = CFBridgingRelease(img);
                uint64_t fgen;
                {
                    std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
                    fgen = g_ovl_pool.gen;
                }
                void (^mainBlock)(void) = ^{
                    {
                        std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
                        if (g_ovl_pool.gen != fgen) return;   // torn down / re-keyed since
                    }
                    CALayer* layer = MacapkGlOverlayForPresent();
                    if (!layer) return;
                    layer.contents = imageObj;
                    layer.hidden = NO;
                    std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
                    g_ovl_pool.Committed(-1, fgen);
                };
                if ([NSThread isMainThread]) mainBlock();
                else dispatch_async(dispatch_get_main_queue(), mainBlock);
            }
        }
        if (MacapkPresentFrameTraceOn()) {
            static unsigned n = 0, published = 0, elided = 0, fb = 0;   // present thread only
            ++n;
            if (result == kMacapkPresentPublished) ++published;
            else if (result == kMacapkPresentElided) ++elided;
            else if (result == kMacapkPresentFallback) ++fb;
            if ((n % 30) == 0)
                std::fprintf(stderr,
                    "[present-trace] overlay-iosurface n=%u published=%u elided=%u fallback=%u\n",
                    n, published, elided, fb);
        }
    }
}
}  // namespace

void CocoaPresentOverlayBitmap(const uint32_t* pixels, int width, int height) {
    if (!pixels) return;
    CocoaPresentOverlayBitmapFrom(width, height, MacapkPixelsFromPointer, nullptr,
                                  const_cast<uint32_t*>(pixels));
}

// Same pixel-window contract as CocoaPresentBitmapFrom (see THE PIXEL WINDOW
// above it): the premultiply is this path's only read of the caller's pixels.
void CocoaPresentOverlayBitmapFrom(int width, int height, MacapkPixelsAcquireFn acquire,
                                   MacapkPixelsReleaseFn release, void* ctx) {
    // FRAMESPEED (2026-09-26): the IOSurface ring above, OPT-IN with
    // MACAPK_FRAMESPEED_OVERLAY_IOSURFACE=1 until it has been verified live.
    if (width > 0 && height > 0 && MacapkOverlayRingOn()) {
        MacapkPresentOverlayRing(width, height, acquire, release, ctx);
        return;
    }
    // Same leak, same reason as CocoaPresentBitmap above: this also runs
    // on the Java main-loop thread and also autoreleases a CGImage.
    @autoreleasepool {

        if (width <= 0 || height <= 0) return;
        size_t bytes = static_cast<size_t>(width) * height * 4;
        void* copy = std::malloc(bytes);
        if (!copy) return;
        {
            MacapkPixelWindow win(acquire, release, ctx);
            const uint32_t* pixels = win.pixels();
            if (!pixels) { std::free(copy); return; }
            macapk_premultiply_argb(static_cast<uint32_t*>(copy), pixels,
                                    static_cast<size_t>(width) * height);
        }

        CGDataProviderRef provider = CGDataProviderCreateWithData(
            nullptr, copy, bytes,
            [](void*, const void* data, size_t) { std::free(const_cast<void*>(data)); });
        if (!provider) { std::free(copy); return; }

        CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
        CGImageRef cgImage = CGImageCreate(
            width, height,
            /*bitsPerComponent*/ 8,
            /*bitsPerPixel*/ 32,
            /*bytesPerRow*/ static_cast<size_t>(width) * 4,
            cs,
            kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst,
            provider, nullptr, /*shouldInterpolate*/ false,
            kCGRenderingIntentDefault);
        CGColorSpaceRelease(cs);
        CGDataProviderRelease(provider);
        if (!cgImage) return;

        id imageObj = CFBridgingRelease(cgImage);
        void (^mainBlock)(void) = ^{
            // Host selection + (re)attach, shared with the ring path.
            CALayer* layer = MacapkGlOverlayForPresent();
            if (!layer) return;
            layer.contents = imageObj;
            layer.hidden = NO;
        };
        if ([NSThread isMainThread]) mainBlock();
        else dispatch_async(dispatch_get_main_queue(), mainBlock);

    }
}

// Called when WindowOverlayStack goes back to empty (the dialog was
// dismissed / its owning Activity finished) so the last-rendered overlay
// bitmap does not stay stuck on screen forever.
void CocoaClearOverlayBitmap(void) {
    void (^mainBlock)(void) = ^{
        if (g_gl_overlay_layer) g_gl_overlay_layer.contents = nil;
        if (g_gl_overlay_layer) g_gl_overlay_layer.hidden = YES;
    };
    if (!MacapkOverlayRingOn()) {   // the CGImage path: exactly the pre-change clear
        if ([NSThread isMainThread]) mainBlock();
        else dispatch_async(dispatch_get_main_queue(), mainBlock);
        return;
    }
    // FRAMESPEED ring: tear it down NOW, on this thread, under the present
    // lock. So (a) the next present publishes even if its pixels equal the
    // dismissed frame's -- it finds an empty, re-keyed pool and repaints in full
    // -- and (b) the surfaces and the shadow are handed back instead of staying
    // resident for the life of the process. Safe: the layer's contents and every
    // queued main block hold their OWN retain, Resize bumps `gen` so a queued
    // block's Committed / Dropped is ignored, and no present is between Plan and
    // its block's CFRetain while g_ovl_present_mu is held. The dispatch is inside
    // the lock too, so a present on another thread cannot queue its block ahead
    // of this clear's and have the clear hide it.
    std::lock_guard<std::mutex> present_lk(g_ovl_present_mu);
    void* released[MacapkPoolCore::kMax];
    int nreleased;
    {
        std::lock_guard<std::mutex> lk(g_ovl_pool_mu);
        nreleased = g_ovl_pool.Resize(0, 0, released);   // also newest = -1
    }
    for (int k = 0; k < nreleased; ++k) CFRelease((CFTypeRef) released[k]);
    std::vector<uint8_t>().swap(g_ovl_shadow);
    g_ovl_shadow_w = g_ovl_shadow_h = 0;
    if ([NSThread isMainThread]) mainBlock();
    else dispatch_async(dispatch_get_main_queue(), mainBlock);
}

// Cocoa is not thread-safe; NSWindow / NSView mutation must happen on
// the main thread. With the Boot.run -> activity thread split (so the
// runloop stays responsive even if onCreate hangs in lifted code),
// setContentView and friends run from non-main threads. Each
// helper bounces through dispatch_async if called off-main, so the
// caller never deadlocks. dispatch_async (not _sync) avoids the
// deadlock that happens if the caller already holds a Cocoa lock.
static inline void dispatch_main_async_void(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

// MACAPK_WINDOW_TITLE_PREFIX (harness): prepended to every window title, so a
// session can hand the owner a test copy whose window says what it is
// ("TEST Arrow Pad -- Among Us") next to his installed app. Unset: no change.
static NSString* macapk_titled(NSString* t) {
    const char* p = std::getenv("MACAPK_WINDOW_TITLE_PREFIX");
    if (!p || !*p || !t) return t;
    return [NSString stringWithFormat:@"%s%@", p, t];
}

void CocoaSetWindowTitle(void* window, const char* title) {
    if (!window || !title) return;
    // Called from JVM threads whose implicit autorelease pool never drains
    // (the v1.0.952 CocoaPresentBitmap rule); the stringWithUTF8String:
    // result is autoreleased on THIS thread. Safe to drain on return: the
    // dispatched block is copied first, which retains its captured `s`.
    @autoreleasepool {
        NSWindow* w = (__bridge NSWindow*)window;
        NSString* s = macapk_titled([NSString stringWithUTF8String:title]);
        dispatch_main_async_void(^{
            [w setTitle:s];
        });
    }
}

// Reshape the placeholder window to the CURRENT g_phone_w/g_phone_h: fit into the
// screen's visible frame (aspect-preserving, never enlarging, scale capped at
// 1.0), keep the window centered, and re-apply the aspect + min-size locks so the
// existing letterbox container + per-frame macapkSurfaceChanged re-lay-out the
// View tree (Canvas) / re-query the GL surface at the new size -- a real re-render
// at the new dimensions, NOT a pixel rotation. Main-thread only (Cocoa). Shared by
// CocoaRotateRender (Cmd+R transpose) and CocoaApplyRequestedOrientation (an
// Activity.setRequestedOrientation request).
// Build the runtime window's menu bar: a standard app menu (Hide / Quit) plus a
// "View" menu whose "Rotate" item is bound to Cmd+R -- AppKit renders the "⌘R"
// shortcut beside it automatically, which is the "hardware key shown next to the
// software button" affordance. The item uses target:nil so its action travels the
// responder chain to the key window's MacApkMetalView -macapkMenuRotate:, which
// calls the SAME CocoaRotateRender the existing Cmd+R key handler uses -- so the
// menu, its shortcut, and the keyboard path are one behavior. Before this the
// runtime had only AppKit's default (empty) menu.
// ---- Controls menu: keyboard bindings ------------------------------------
//
// Lets the user pick which KeyBindProfiles profile drives key-to-touch
// injection, so a touch-only game is playable from the keyboard. The profile
// LIST is read straight from disk here rather than over JNI: it is a directory
// of JSON files, and reading it natively keeps menu construction independent of
// whether the JVM happens to be ready. Only the SELECTION crosses into Java.
static NSString* keybind_dir(void) {
    return [NSHomeDirectory() stringByAppendingPathComponent:
            @"Library/Application Support/Mac.apk/keybinds"];
}

static void keybind_set_active(NSString* name) {
    JavaVM* vm = g_touch_vm;
    if (!vm) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
            return;
        attached = true;
    } else if (r != JNI_OK) {
        return;
    }
    jclass cls = env->FindClass("com/macapk/loader/KeyBindProfiles");
    if (!cls) {
        env->ExceptionClear();
    } else {
        // selectFromMenu, not setActiveByName: the menu's pick also switches
        // Tilt Control and Arrow Keys as D-pad off (one input mode at a time).
        jmethodID mid = env->GetStaticMethodID(cls, "selectFromMenu",
                                               "(Ljava/lang/String;)Z");
        if (!mid) {
            env->ExceptionClear();
        } else {
            jstring s = name ? env->NewStringUTF([name UTF8String]) : nullptr;
            env->CallStaticBooleanMethod(cls, mid, s);
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (s) env->DeleteLocalRef(s);
        }
        env->DeleteLocalRef(cls);
    }
    // Detach ONLY if we attached: this runs on the Cocoa main thread, which the
    // rest of the bridge may already have attached for other calls.
    if (attached) vm->DetachCurrentThread();
}

static NSString* keybind_active_name(void) {
    JavaVM* vm = g_touch_vm;
    if (!vm) return nil;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
            return nil;
        attached = true;
    } else if (r != JNI_OK) {
        return nil;
    }
    NSString* out = nil;
    jclass cls = env->FindClass("com/macapk/loader/KeyBindProfiles");
    if (!cls) {
        env->ExceptionClear();
    } else {
        jmethodID mid = env->GetStaticMethodID(cls, "activeName", "()Ljava/lang/String;");
        if (!mid) {
            env->ExceptionClear();
        } else {
            jstring s = (jstring) env->CallStaticObjectMethod(cls, mid);
            if (env->ExceptionCheck()) { env->ExceptionClear(); s = nullptr; }
            if (s) {
                const char* c = env->GetStringUTFChars(s, nullptr);
                if (c) { out = [NSString stringWithUTF8String:c]; env->ReleaseStringUTFChars(s, c); }
                env->DeleteLocalRef(s);
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (attached) vm->DetachCurrentThread();
    return out;
}

// ---- Controls menu: Tilt Control -------------------------------------------
// One checkable item that turns the arrow keys into a tilt of the virtual
// device (com.macapk.loader.TiltControl; SensorManager derives every motion
// sensor from it). The state lives in Java and is remembered per app there; this
// side only calls a static ()Z / ()V method. Tagged so the profile radio-group
// code in MacApkControlsTarget leaves it alone.
static const NSInteger kMacApkTiltMenuTag = 0x7417;
// "Arrow Keys as D-pad" (com.macapk.loader.ArrowPad), the same shape: a
// checkable item whose state lives in Java, remembered per app there. Tilt and
// it claim the same keys, so the Java side keeps them mutually exclusive and
// both checkmarks are re-read after either is toggled.
static const NSInteger kMacApkArrowPadMenuTag = 0x7418;
// "Space Bar Taps Center" (com.macapk.loader.SpaceTap), the same shape again:
// Space becomes a finger held at the centre of the surface.
static const NSInteger kMacApkSpaceTapMenuTag = 0x7419;

static bool controls_call(const char* clsName, const char* method, const char* sig);
static bool tilt_call(const char* method, const char* sig) {
    return controls_call("com/macapk/loader/TiltControl", method, sig);
}
static bool arrowpad_call(const char* method, const char* sig) {
    return controls_call("com/macapk/loader/ArrowPad", method, sig);
}
static bool spacetap_call(const char* method, const char* sig) {
    return controls_call("com/macapk/loader/SpaceTap", method, sig);
}

static bool controls_call(const char* clsName, const char* method, const char* sig) {
    JavaVM* vm = g_touch_vm;
    if (!vm) return false;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
            return false;
        attached = true;
    } else if (r != JNI_OK) {
        return false;
    }
    bool out = false;
    jclass cls = env->FindClass(clsName);
    if (!cls) {
        env->ExceptionClear();
    } else {
        jmethodID mid = env->GetStaticMethodID(cls, method, sig);
        if (!mid) {
            env->ExceptionClear();
        } else if (sig[2] == 'Z') {
            out = env->CallStaticBooleanMethod(cls, mid) == JNI_TRUE;
        } else {
            env->CallStaticVoidMethod(cls, mid);
        }
        if (env->ExceptionCheck()) { env->ExceptionClear(); out = false; }
        env->DeleteLocalRef(cls);
    }
    if (attached) vm->DetachCurrentThread();
    return out;
}

}  // namespace macapk -- ObjC @interface/@implementation may only appear at
   // GLOBAL scope, and this file's body sits inside `namespace macapk`. Reopened
   // immediately after the class. The helpers above keep internal linkage, so
   // qualifying them as macapk::... resolves within this TU.

// v1.0.1047: observe the macOS system appearance LIVE.
//
// Before this, Boot read `defaults read -g AppleInterfaceStyle` exactly once at
// startup ("Detected once here"), so toggling Light/Dark did nothing until the
// app was restarted. Android delivers a uiMode configuration change instead, and
// that is what we now drive.
//
// Apple documents KVO on NSApplication.effectiveAppearance as THE way to observe
// this. AppKit's own NSCell.h says an appearance-sensitive drawing path should
// react "by Key-Value Observing the -effectiveAppearance property on
// NSApplication"; the property is NSApplication.h, API_AVAILABLE(macos(10.14)),
// and is not deprecated (this project targets macOS 26.4+ and forbids deprecated
// APIs). Deliberately NOT AppleInterfaceThemeChangedNotification: that name is
// undeclared in the SDK.
//
// Reading the answer off effectiveAppearance rather than re-shelling `defaults`
// matters here: this fires on every toggle, and effectiveAppearance is the
// appearance AppKit actually applied to THIS process, so an app-level override
// cannot make the two disagree.
@interface MacApkAppearanceObserver : NSObject
@end
@implementation MacApkAppearanceObserver
- (void)observeValueForKeyPath:(NSString*)keyPath
                      ofObject:(id)object
                        change:(NSDictionary*)change
                       context:(void*)context {
    (void)object; (void)change; (void)context;
    if (![keyPath isEqualToString:@"effectiveAppearance"]) return;
    NSAppearanceName best =
        [[NSApp effectiveAppearance] bestMatchFromAppearancesWithNames:@[
            NSAppearanceNameAqua, NSAppearanceNameDarkAqua ]];
    BOOL dark = [best isEqualToString:NSAppearanceNameDarkAqua];
    // The Java side owns the MACAPK_FOLLOW_SYSTEM_APPEARANCE opt-out and the
    // "did the night bit actually change" test, so this upcall is deliberately
    // unconditional -- one source of truth for both, and redundant fires are
    // documented as cheap no-ops there.
    macapk::macapk_notify_appearance_changed(dark ? true : false);
}
@end
static MacApkAppearanceObserver* g_appearance_observer = nil;

@interface MacApkControlsTarget : NSObject <NSMenuDelegate>
@end
@implementation MacApkControlsTarget
// The Controls menu is ONE radio group of input modes: None, Arrow Keys as
// D-pad, Tilt Control, Space Bar Taps Center, then each key-binding profile
// (owner, 2026-10-02: more than one checked "cannot be so"; order per owner,
// 2026-10-05: the generic modes on top, per-game profiles at the bottom). Java
// owns the exclusion (KeyBindProfiles.selectFromMenu,
// TiltControl/ArrowPad/SpaceTap.setEnabled); every pick ends by
// re-reading ALL the checkmarks from Java, so the menu cannot disagree with it.
- (void)macapkPickProfile:(NSMenuItem*)item {
    NSString* name = (NSString*) [item representedObject];   // nil = "None"
    macapk::keybind_set_active(name);
    [self macapkRefreshArrowItems:[item menu]];
}
- (void)macapkToggleTilt:(NSMenuItem*)item {
    macapk::tilt_call("toggle", "()Z");
    [self macapkRefreshArrowItems:[item menu]];
}
- (void)macapkToggleArrowPad:(NSMenuItem*)item {
    macapk::arrowpad_call("toggle", "()Z");
    [self macapkRefreshArrowItems:[item menu]];
}
- (void)macapkToggleSpaceTap:(NSMenuItem*)item {
    macapk::spacetap_call("toggle", "()Z");
    [self macapkRefreshArrowItems:[item menu]];
}
// Re-read every mode's checkmark from Java (see above). "None" is on only
// when no profile AND none of the key modes is on.
- (void)macapkRefreshArrowItems:(NSMenu*)menu {
    NSString* active = macapk::keybind_active_name();
    bool tilt = macapk::tilt_call("isEnabled", "()Z");
    bool pad = macapk::arrowpad_call("isEnabled", "()Z");
    bool space = macapk::spacetap_call("isEnabled", "()Z");
    for (NSMenuItem* it in [menu itemArray]) {
        if ([it isSeparatorItem]) continue;
        BOOL on;
        if ([it tag] == macapk::kMacApkTiltMenuTag) {
            on = tilt;
        } else if ([it tag] == macapk::kMacApkArrowPadMenuTag) {
            on = pad;
        } else if ([it tag] == macapk::kMacApkSpaceTapMenuTag) {
            on = space;
        } else {
            NSString* name = (NSString*) [it representedObject];
            on = (name == nil) ? (active == nil && !tilt && !pad && !space)
                               : [name isEqualToString:active];
        }
        [it setState:on ? NSControlStateValueOn : NSControlStateValueOff];
    }
}
// The host window lost focus: AppKit will not deliver keyUp: for an arrow still
// held, so release them rather than leave the device tilted (or Space's
// finger pressed).
- (void)macapkTiltReleaseKeys:(NSNotification*)note {
    (void)note;
    macapk::tilt_call("releaseKeys", "()V");
    macapk::arrowpad_call("releaseKeys", "()V");
    macapk::spacetap_call("releaseKeys", "()V");
}
// The menu is built during window creation, which can run BEFORE Boot registers
// the touch dispatcher (and with it the JavaVM handle this file calls through).
// So the checkmark cannot be trusted at construction time. Re-read the active
// profile whenever the menu is opened, which is always long after boot.
// v1.0.1047: Cmd-Q / Quit. Deliberately NOT AppKit's `terminate:`, which ends
// in exit(0) and so runs __cxa_finalize -- the exact path that deadlocked the
// runtime on window close (see macapk_terminate_now). Routing Quit through the
// same helper keeps ONE way out of this process, so the two quit gestures
// cannot drift apart again. macapk_terminate_now does not return.
- (void)macapkQuit:(id)sender {
    (void)sender;
    std::fprintf(stderr, "[macapk] Quit requested; exiting cleanly\n");
    std::fflush(stderr);
    macapk::macapk_terminate_now(0);
}
- (void)menuNeedsUpdate:(NSMenu*)menu {
    [self macapkRefreshArrowItems:menu];
}
@end

// ---- Native title-bar Back chevron -----------------------------------------
// GLOBAL scope on purpose: an ObjC @interface/@implementation may only appear at
// global scope, and this sits in the gap between the namespace close above and
// its reopen below. A macOS-native "<" in the window title bar (leading edge),
// like Safari / Finder. It delivers the SAME KEYCODE_BACK -> Activity.onKeyUp
// (BACK) -> onBackPressed sequence Esc and Cmd+[ synthesize (MacKeyEventToAndroid
// + dispatchKeyRawAction), so it is a pure affordance with ZERO per-app code -- it
// moves "back" onto a visible native Mac control instead of an Android on-screen
// nav glyph, which is what lets the Android bottom nav bar be removed without
// stranding the user.
//
// Always enabled (no browser dim-at-root): on Android back is ALWAYS present and
// always does something (navigate, or exit at the app root), so an always-live
// chevron is the faithful analog. Activity-stack depth is a FALSE canGoBack
// signal for single-Activity (Compose / fragment) apps -- it stays 1 through deep
// in-app back navigation -- so dimming on it would hide a working Back. A real
// canGoBack signal from the Java side could make it contextual later.
static void CocoaDispatchBack() {
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return;  // dispatcher not up yet (boot): no-op, like Esc
    JNIEnv* env = nullptr;
    jint r = macapk::g_touch_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (macapk::g_touch_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;
    if (!macapk::g_key_mid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        macapk::g_key_mid = env->GetMethodID(cls, "dispatchKey", "(IIII)V");
        env->DeleteLocalRef(cls);
    }
    if (!macapk::g_key_mid) return;
    // KEYCODE_BACK = 4. Down then up, no unicode, no meta -- the exact pair a
    // hardware / Esc back key delivers through dispatchKeyRawAction.
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_key_mid, (jint)0, (jint)4, (jint)0, (jint)0);
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_key_mid, (jint)1, (jint)4, (jint)0, (jint)0);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

@interface MacApkBackAccessory : NSTitlebarAccessoryViewController
@end
@implementation MacApkBackAccessory
- (void)macapkBack:(id)sender { (void)sender; CocoaDispatchBack(); }
@end

namespace macapk {   // reopened after the ObjC class above

/** v1.0.1047: register the live-appearance KVO observer. Registered for the
 *  process lifetime and never removed -- the observer outlives every window, and
 *  the runtime leaves via _exit() (macapk_terminate_now), so there is no
 *  teardown point at which a dangling KVO registration could be observed. */
static void install_appearance_observer() {
    if (g_appearance_observer) return;
    g_appearance_observer = [[MacApkAppearanceObserver alloc] init];
    [NSApp addObserver:g_appearance_observer
            forKeyPath:@"effectiveAppearance"
               options:NSKeyValueObservingOptionNew
               context:nullptr];
}

static MacApkControlsTarget* g_controls_target = nil;

static void install_controls_menu(NSMenu* mainMenu) {
    NSMenuItem* item = [[NSMenuItem alloc] init];
    [mainMenu addItem:item];
    NSMenu* menu = [[NSMenu alloc] initWithTitle:@"Controls"];
    if (!g_controls_target) g_controls_target = [[MacApkControlsTarget alloc] init];
    // Checkmarks are refreshed on open (see menuNeedsUpdate:), because the VM
    // handle may not exist yet at this point in startup.
    [menu setDelegate:g_controls_target];

    NSString* active = keybind_active_name();

    NSMenuItem* none = [[NSMenuItem alloc] initWithTitle:@"None"
                                                  action:@selector(macapkPickProfile:)
                                           keyEquivalent:@""];
    [none setTarget:g_controls_target];
    [none setRepresentedObject:nil];
    [none setState:(active == nil) ? NSControlStateValueOn : NSControlStateValueOff];
    [menu addItem:none];

    // The generic input modes come first, under their own separator: Arrow
    // Keys as D-pad, Tilt Control, Space Bar Taps Center (owner, 2026-10-05).
    // Their checkmarks are refreshed in menuNeedsUpdate: like the profiles'
    // (the VM may not be up yet here).
    [menu addItem:[NSMenuItem separatorItem]];
    NSMenuItem* pad = [[NSMenuItem alloc] initWithTitle:@"Arrow Keys as D-pad"
                                                 action:@selector(macapkToggleArrowPad:)
                                          keyEquivalent:@""];
    [pad setTarget:g_controls_target];
    [pad setTag:kMacApkArrowPadMenuTag];
    [pad setToolTip:@"Arrow keys become the d-pad of a virtual game controller, for games "
                     "that move only with a controller. Remembered for this app."];
    [pad setState:arrowpad_call("isEnabled", "()Z") ? NSControlStateValueOn : NSControlStateValueOff];
    [menu addItem:pad];
    NSMenuItem* tilt = [[NSMenuItem alloc] initWithTitle:@"Tilt Control"
                                                  action:@selector(macapkToggleTilt:)
                                           keyEquivalent:@""];
    [tilt setTarget:g_controls_target];
    [tilt setTag:kMacApkTiltMenuTag];
    [tilt setToolTip:@"Arrow keys tilt the device: Left/Right roll, Up/Down pitch. "
                      "Release to spring back. Remembered for this app."];
    [tilt setState:tilt_call("isEnabled", "()Z") ? NSControlStateValueOn : NSControlStateValueOff];
    [menu addItem:tilt];
    NSMenuItem* space = [[NSMenuItem alloc] initWithTitle:@"Space Bar Taps Center"
                                                   action:@selector(macapkToggleSpaceTap:)
                                            keyEquivalent:@""];
    [space setTarget:g_controls_target];
    [space setTag:kMacApkSpaceTapMenuTag];
    [space setToolTip:@"Space touches the middle of the app's screen: press to tap, hold "
                       "to hold. Remembered for this app."];
    [space setState:spacetap_call("isEnabled", "()Z") ? NSControlStateValueOn : NSControlStateValueOff];
    [menu addItem:space];

    // Per-game key-binding profiles last, below their own separator.
    NSArray<NSString*>* files =
        [[NSFileManager defaultManager] contentsOfDirectoryAtPath:keybind_dir() error:nil];
    NSArray<NSString*>* sorted =
        [files sortedArrayUsingSelector:@selector(caseInsensitiveCompare:)];
    bool any = false;
    for (NSString* f in sorted) {
        if (![[f pathExtension] isEqualToString:@"json"]) continue;
        NSString* name = [f stringByDeletingPathExtension];
        if (!any) { [menu addItem:[NSMenuItem separatorItem]]; any = true; }
        NSMenuItem* mi = [[NSMenuItem alloc] initWithTitle:name
                                                    action:@selector(macapkPickProfile:)
                                             keyEquivalent:@""];
        [mi setTarget:g_controls_target];
        [mi setRepresentedObject:name];
        [mi setState:(active && [active isEqualToString:name])
                        ? NSControlStateValueOn : NSControlStateValueOff];
        [menu addItem:mi];
    }

    [[NSNotificationCenter defaultCenter] addObserver:g_controls_target
                                             selector:@selector(macapkTiltReleaseKeys:)
                                                 name:NSApplicationDidResignActiveNotification
                                               object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:g_controls_target
                                             selector:@selector(macapkTiltReleaseKeys:)
                                                 name:NSWindowDidResignKeyNotification
                                               object:nil];
    [item setSubmenu:menu];
}

// Default ON; opt out with MACAPK_TITLEBAR_BACK=0 (to A/B against the on-screen
// nav glyph while that is being removed). Namespace-scoped to match its forward
// declaration; the ObjC MacApkBackAccessory it uses lives in the global gap above.
static void InstallBackTitlebarAccessory(NSWindow* w) {
    if (!w) return;
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_TITLEBAR_BACK");
        return !e || std::strcmp(e, "0") != 0;
    }();
    if (!on) return;
    MacApkBackAccessory* acc = [[MacApkBackAccessory alloc] init];
    // chevron.backward is the system Back glyph (RTL-aware). Semibold ~13pt reads
    // like Safari's toolbar back without a bezel.
    NSImage* chev = [NSImage imageWithSystemSymbolName:@"chevron.backward"
                                accessibilityDescription:@"Back"];
    if (@available(macOS 11.0, *)) {
        NSImageSymbolConfiguration* cfg =
            [NSImageSymbolConfiguration configurationWithPointSize:13
                                                            weight:NSFontWeightSemibold
                                                             scale:NSImageSymbolScaleMedium];
        chev = [chev imageWithSymbolConfiguration:cfg];
    }
    NSButton* btn = [NSButton buttonWithImage:(chev ?: [NSImage imageNamed:NSImageNameGoLeftTemplate])
                                       target:acc
                                       action:@selector(macapkBack:)];
    btn.bordered = NO;
    btn.imagePosition = NSImageOnly;
    btn.toolTip = @"Back";
    btn.focusRingType = NSFocusRingTypeNone;
    btn.translatesAutoresizingMaskIntoConstraints = NO;
    // AppKit stretches the accessory view to the full titlebar height, and the
    // traffic lights are centered in that same titlebar -- so centering the button
    // vertically in the host aligns the chevron exactly on the traffic-light row
    // (a hardcoded y bottom-anchored it a hair low). 22x22 hit target, 2pt leading.
    NSView* host = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 28, 28)];
    [host addSubview:btn];
    [NSLayoutConstraint activateConstraints:@[
        [btn.centerYAnchor constraintEqualToAnchor:host.centerYAnchor],
        [btn.leadingAnchor constraintEqualToAnchor:host.leadingAnchor constant:2],
        [btn.widthAnchor  constraintEqualToConstant:22],
        [btn.heightAnchor constraintEqualToConstant:22],
    ]];
    acc.view = host;
    acc.layoutAttribute = NSLayoutAttributeLeading;   // leading edge, after the traffic lights
    [w addTitlebarAccessoryViewController:acc];        // window retains acc
}

static void install_menu_bar(NSString* appTitle) {
    NSMenu* mainMenu = [[NSMenu alloc] init];

    // App menu (bold, first slot): Hide + Quit.
    NSMenuItem* appItem = [[NSMenuItem alloc] init];
    [mainMenu addItem:appItem];
    NSMenu* appMenu = [[NSMenu alloc] init];
    [appMenu addItemWithTitle:[NSString stringWithFormat:@"Hide %@", appTitle]
                       action:@selector(hide:) keyEquivalent:@"h"];
    // Standard Hide Others (Option+Command+H) / Show All, right under Hide.
    NSMenuItem* hideOthers =
        [appMenu addItemWithTitle:@"Hide Others"
                           action:@selector(hideOtherApplications:) keyEquivalent:@"h"];
    [hideOthers setKeyEquivalentModifierMask:NSEventModifierFlagCommand | NSEventModifierFlagOption];
    [appMenu addItemWithTitle:@"Show All"
                       action:@selector(unhideAllApplications:) keyEquivalent:@""];
    [appMenu addItem:[NSMenuItem separatorItem]];
    // v1.0.1047: target our own quit rather than AppKit's terminate:, which
    // would reach exit(0) and re-open the __cxa_finalize deadlock. See
    // -[MacApkControlsTarget macapkQuit:] and macapk_terminate_now.
    if (!g_controls_target) g_controls_target = [[MacApkControlsTarget alloc] init];
    NSMenuItem* quitItem =
        [appMenu addItemWithTitle:[NSString stringWithFormat:@"Quit %@", appTitle]
                           action:@selector(macapkQuit:) keyEquivalent:@"q"];
    [quitItem setTarget:g_controls_target];
    [appItem setSubmenu:appMenu];

    // View menu: the software Rotate button + its Cmd+R shortcut.
    NSMenuItem* viewItem = [[NSMenuItem alloc] init];
    [mainMenu addItem:viewItem];
    NSMenu* viewMenu = [[NSMenu alloc] initWithTitle:@"View"];
    NSMenuItem* rotate =
        [[NSMenuItem alloc] initWithTitle:@"Rotate"
                                   action:@selector(macapkMenuRotate:)
                            keyEquivalent:@"r"];
    [rotate setKeyEquivalentModifierMask:NSEventModifierFlagCommand];
    [rotate setTarget:nil];   // responder chain -> MacApkMetalView
    [viewMenu addItem:rotate];
    [viewItem setSubmenu:viewMenu];

    // Controls menu: pick the keyboard-binding profile.
    install_controls_menu(mainMenu);

    // Window menu: Minimize (Cmd+M) / Zoom / Bring All to Front. Without it a
    // borderless-looking guest window had no Cmd+M at all. A Command chord is
    // offered to the window's performKeyEquivalent: and then the main menu
    // BEFORE keyDown: runs (NSApplication sendEvent:), so Cmd+M is consumed
    // here and never reaches the guest as Ctrl+M. Deliberately no Edit menu:
    // Cmd+C/V/X/A already reach the guest as Ctrl chords (host_keymap.h) and an
    // Edit menu's key equivalents would steal them. MACAPK_WINDOW_MENU=0 omits it.
    const char* wm = std::getenv("MACAPK_WINDOW_MENU");
    if (!(wm && wm[0] == '0')) {
        NSMenuItem* windowItem = [[NSMenuItem alloc] init];
        [mainMenu addItem:windowItem];
        NSMenu* windowMenu = [[NSMenu alloc] initWithTitle:@"Window"];
        [windowMenu addItemWithTitle:@"Minimize"
                              action:@selector(performMiniaturize:) keyEquivalent:@"m"];
        [windowMenu addItemWithTitle:@"Zoom"
                              action:@selector(performZoom:) keyEquivalent:@""];
        [windowMenu addItem:[NSMenuItem separatorItem]];
        [windowMenu addItemWithTitle:@"Bring All to Front"
                              action:@selector(arrangeInFront:) keyEquivalent:@""];
        [windowItem setSubmenu:windowMenu];
        [g_app setWindowsMenu:windowMenu];
    }

    [g_app setMainMenu:mainMenu];
}

static void recenter_placeholder_next_cycle() {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!g_placeholder_window) return;
        // Phase 2a: a fullscreen window (or one mid-transition) has no frame
        // of ours to centre -- AppKit owns it.
        if (window_is_fullscreen() || g_fs_transition) return;
        NSScreen* screen = [g_placeholder_window screen];
        if (!screen) screen = [NSScreen mainScreen];
        if (!screen) return;
        NSRect vis = [screen visibleFrame];
        NSRect fr  = [g_placeholder_window frame];
        ++g_own_frame_change;
        [g_placeholder_window setFrameOrigin:NSMakePoint(
            vis.origin.x + (vis.size.width  - fr.size.width)  / 2.0,
            vis.origin.y + (vis.size.height - fr.size.height) / 2.0)];
        --g_own_frame_change;
    });
}

static void reshape_placeholder_to_globals_main(ReshapeAnchor anchor) {
    if (!g_placeholder_window) return;
    // Phase 2a: NEVER give a fullscreen window a frame. AppKit documents no
    // behaviour for setFrame on a fullscreen window, and whatever it does
    // leaves the frame, the aspect lock and g_win disagreeing (and the exit
    // then restores a frame of the old shape). Every caller lands here -- a
    // live transition (which the pure policy already keeps in-window in
    // fullscreen), the boot-time CocoaReshapePlaceholder of a --fullscreen
    // launch, a legacy Cmd+R -- so the guard is here too: re-fit the render
    // view into the unchanged window, and let windowDidExitFullScreen reshape
    // once the window is windowed again.
    if (window_is_fullscreen() || g_fs_transition) {
        refit_letterbox_main();
        std::fprintf(stderr, "[I] cocoa: reshape to %dx%d deferred: the window is %s\n",
                     g_win_w > 0 ? g_win_w : g_phone_w, g_win_h > 0 ? g_win_h : g_phone_h,
                     g_fs_transition ? "entering or leaving fullscreen" : "fullscreen");
        if (!g_wp.active || !g_wp.live.fullscreen) g_fs_pending_reshape = true;
        return;
    }
    // Under the window policy the WINDOW is the window box; g_phone is the
    // app's box inside it (equal unless an in-window letterbox is live).
    const bool policy_box = g_win_w > 0 && g_win_h > 0;
    int nw = policy_box ? g_win_w : g_phone_w, nh = policy_box ? g_win_h : g_phone_h;
    SFTRACE("reshape ENTER phone=%dx%d curFrame=%.0fx%.0f",
            nw, nh, [g_placeholder_window frame].size.width,
            [g_placeholder_window frame].size.height);
    NSScreen* screen = [g_placeholder_window screen];
    if (!screen) screen = [NSScreen mainScreen];
    NSRect vis = screen ? [screen visibleFrame] : NSMakeRect(0, 0, nw, nh);
    // Fit the CONTENT budget (visible frame minus the title bar), so the frame
    // set below is never one AppKit has to constrain -- see
    // content_budget_for_visible.
    const NSSize budget = content_budget_for_visible(vis);
    double sc = MIN(MIN(budget.width / nw, budget.height / nh), 1.0);
    double cw = nw * sc, ch = nh * sc;
    // v1.0.1637: same honesty rule as CocoaInit -- a canvas the window cannot
    // show at full size becomes the size it is shown at.
    if (display_model_on() && sc < 1.0) {
        if (policy_box) {
            g_phone_w = (int)(g_phone_w * sc); g_phone_h = (int)(g_phone_h * sc);
            g_win_w = (int)cw; g_win_h = (int)ch;
        } else {
            g_phone_w = (int)cw; g_phone_h = (int)ch;
        }
    }
    // Keep the live state honest BEFORE the locks read it (AllowedUserResize
    // takes the class and the window from it).
    if (policy_box && g_wp.active) {
        g_wp.live.win_w_pt = g_win_w; g_wp.live.win_h_pt = g_win_h;
        g_wp.live.app_w_pt = g_phone_w; g_wp.live.app_h_pt = g_phone_h;
    }
    apply_window_locks_main(nw, nh, cw, ch);
    // Resize the content rect and CENTER the window on the screen's visible frame
    // (excludes the menu bar + Dock). This runs at startup right after the app's
    // orientation resolves, so the window always OPENS centered instead of drifting
    // off to one side (the old "keep current center fixed" math locked in a
    // not-yet-centered origin here, leaving the window ~58pt left of center / partly
    // off the left edge). A user rotate (Cmd+R) re-centers too, which reads clean
    // for an aspect change; the user can freely move the window afterward. animate:NO
    // -> a single crisp re-render at the new size (an animated reshape would relayout
    // every interpolated step).
    NSRect newFrame =
        [g_placeholder_window frameRectForContentRect:NSMakeRect(0, 0, cw, ch)];
    if (anchor == ReshapeAnchor::kKeepCenter) {
        // Window policy phase 3: an activity transition grows or shrinks the
        // window about its OWN centre, kept on the visible frame, so the back
        // that restores the covered activity's size is the exact inverse of
        // the start that grew it, and a window the user moved stays there.
        const NSRect old = [g_placeholder_window frame];
        newFrame.origin.x = NSMidX(old) - newFrame.size.width / 2.0;
        newFrame.origin.y = NSMidY(old) - newFrame.size.height / 2.0;
        if (NSMaxX(newFrame) > NSMaxX(vis)) newFrame.origin.x = NSMaxX(vis) - newFrame.size.width;
        if (newFrame.origin.x < vis.origin.x) newFrame.origin.x = vis.origin.x;
        if (NSMaxY(newFrame) > NSMaxY(vis)) newFrame.origin.y = NSMaxY(vis) - newFrame.size.height;
        if (newFrame.origin.y < vis.origin.y) newFrame.origin.y = vis.origin.y;
    } else {
        newFrame.origin.x = vis.origin.x + (vis.size.width  - newFrame.size.width)  / 2.0;
        newFrame.origin.y = vis.origin.y + (vis.size.height - newFrame.size.height) / 2.0;
    }
    ++g_own_frame_change;   // ours, not a user resize (windowDidResize)
    [g_placeholder_window setFrame:newFrame display:YES animate:NO];
    --g_own_frame_change;
    // Re-center after AppKit's aspect-fit shrink settles (an orientation change /
    // rotate resizes the window, and the aspect lock may then shrink it to fit the
    // visible height, un-centering it -- see recenter_placeholder_next_cycle).
    // Not for a keep-centre reshape: that would undo the anchor.
    if (anchor == ReshapeAnchor::kCenterOnScreen) recenter_placeholder_next_cycle();
    if (policy_box) refit_letterbox_main();
    // Every caller (a live transition, the boot-time orientation resolve) may
    // have just shrunk both boxes to fit the screen; the live state must say
    // what is ON screen, or the next Cmd+R / setRequestedOrientation compares
    // against a window that no longer exists and mis-classifies its change.
    if (policy_box && g_wp.active) {
        g_wp.live.win_w_pt = g_win_w; g_wp.live.win_h_pt = g_win_h;
        g_wp.live.app_w_pt = g_phone_w; g_wp.live.app_h_pt = g_phone_h;
    }
    SFTRACE("reshape EXIT newFrame=%.0fx%.0f",
            newFrame.size.width, newFrame.size.height);
}

// v1.0.504: rotate the render 90 degrees (portrait <-> landscape) on demand
// (Cmd+R). Transposes the device w:h, reshapes the aspect-locked window to the
// new orientation (fit to the visible frame, centered), and lets the existing
// aspect-fit container + per-frame macapkSurfaceChanged re-render path re-lay-out
// the View tree (Canvas) or re-query the GL surface at the transposed
// dimensions -- a real re-render at the rotated size, NOT a pixel rotation of
// the old frame. Apps that support both orientations re-flow; orientation-locked
// apps simply re-lay-out into the new rect (no crash; same as a device the app
// can't rotate). Main-thread only (Cocoa). See
// docs/live-aspect-resize-design_2026-06-24.md.
static void rotate_render(bool persist_pref) {
    dispatch_main_async_void(^{
        if (!g_placeholder_window) return;
        // v1.0.1647 (owner, 2026-09-25: "We also have command R that rotates
        // the screen"): under the window policy the display is a large screen
        // that never rotates, so Cmd+R is the per-app user control instead --
        // Android's user aspect-ratio override that forces portrait, toggled
        // and remembered per app.
        if (g_wp.active) { toggle_window_pref_main(persist_pref); return; }
        int nw = g_phone_h, nh = g_phone_w;   // transpose orientation
        g_phone_w = nw;
        g_phone_h = nh;
        // Cmd+R is the user physically turning the device, so it moves the
        // USER rotation -- a later non-committal setRequestedOrientation
        // returns here, not to the boot orientation.
        g_user_orient_seeded = true;
        g_user_landscape = nw > nh;
        reshape_placeholder_to_globals_main();
        std::fprintf(stderr, "[I] cocoa_v2: rotate render -> %dx%d\n",
                     g_phone_w, g_phone_h);
    });
}

void CocoaRotateRender() { rotate_render(true); }

// MACAPK_DEBUG_AUTOROTATE's entry: the same rotate, but a self-test must not
// leave a per-app Cmd+R preference behind for the next launch to read.
static void CocoaRotateRenderSelfTest() { rotate_render(false); }

// Apply an Activity.setRequestedOrientation(landscape/portrait) request to the
// canvas + window. Real Android's setRequestedOrientation drives WindowManager to
// (re)lay out the surface to the requested orientation; here we reshape the canvas
// to the resolved side and resize the placeholder window. If a launcher/CLI size
// was pinned (g_explicit_size), keep those dimensions and only transpose them to
// the requested side; otherwise use the auto-max canvas (CocoaSetPhoneSizeMax),
// matching main.cpp's boot sizing. Generic: any app that requests orientation at
// runtime lands here -- the old cocos2d-x v2 pattern (e.g. HCR 1.8.1, whose
// manifest declares no android:screenOrientation, so it sets SENSOR_LANDSCAPE in
// onCreate), plus many Unity/SDL titles. Apps that pin orientation in the manifest
// take the pre-JVM ScanManifestOrientation path in main.cpp; both converge on the
// same g_phone_w/h + reshape. Main-thread only (marshals internally).
void CocoaApplyRequestedOrientation(bool landscape) {
    SFTRACE("applyRequestedOrientation POSTED landscape=%d phone(before)=%dx%d",
            landscape ? 1 : 0, g_phone_w, g_phone_h);
    dispatch_main_async_void(^{
        SFTRACE("applyRequestedOrientation EXECUTING landscape=%d "
                "phone(stillBefore)=%dx%d",
                landscape ? 1 : 0, g_phone_w, g_phone_h);
        if (g_wp.active) {
            // Java routes policy sessions through CocoaApplyWindowOrientation
            // with the raw value; this boolean entry stays correct if anything
            // still calls it.
            apply_requested_orientation_policy_main(
                landscape ? winpolicy::kOrientLandscape : winpolicy::kOrientPortrait);
            return;
        }
        // v1.0.1637: a request for the side the display is ALREADY on changes
        // nothing. AOSP DisplayRotation.updateRotationUnchecked
        // (android14-release :588-591): `if (oldRotation == rotation) { // No
        // change. return false; }`. We re-derived the max canvas and reshaped
        // + re-centred the window regardless, which silently UNDID a user's
        // window resize whenever an activity re-asserted its orientation --
        // every cocos2d-x / Unity onCreate does, so a relaunched activity
        // snapped the window back to full size and (with display changes now
        // committed) set off a second relaunch. Same guard
        // CocoaRestoreUserOrientation already applies.
        if (g_phone_w != g_phone_h && (g_phone_w > g_phone_h) == landscape) {
            std::fprintf(stderr,
                         "[I] cocoa: applyRequestedOrientation -> %s: already %dx%d, "
                         "no change\n", landscape ? "landscape" : "portrait",
                         g_phone_w, g_phone_h);
            return;
        }
        if (g_explicit_size) {
            int lo = MIN(g_phone_w, g_phone_h);
            int hi = MAX(g_phone_w, g_phone_h);
            g_phone_w = landscape ? hi : lo;
            g_phone_h = landscape ? lo : hi;
        } else {
            CocoaSetPhoneSizeMax(landscape);
        }
        reshape_placeholder_to_globals_main();
        std::fprintf(stderr,
                     "[I] cocoa: applyRequestedOrientation -> %s %dx%d\n",
                     landscape ? "landscape" : "portrait",
                     g_phone_w, g_phone_h);
    });
}

// v1.0.996: apply the USER rotation (see g_user_landscape). Called when an
// activity's requested orientation goes NON-COMMITTAL (UNSPECIFIED / USER /
// FULL_USER) after having committed to a side -- the fullscreen-video exit
// pattern. AOSP's DisplayRotation.rotationForOrientation (aosp-mirror/master
// services/core/java/com/android/server/wm/DisplayRotation.java, fetched
// 2026-08-17) resolves those orientations to `preferredRotation =
// mUserRotation` whenever `mUserRotationMode == USER_ROTATION_LOCKED` --
// "Apply rotation lock. ... the user rotation expresses a weak preference for
// the direction of gravity" -- and a Mac has no accelerometer, so the
// rotation-locked model is the honest one for this host. No-op when the
// window is already on the user side (reshape is idempotent but the log line
// would be noise).
void CocoaRestoreUserOrientation() {
    bool landscape = g_user_landscape;
    // Only reshape when the SIDE actually changes: apps call UNSPECIFIED at
    // odd moments (activity transitions), and an idempotent reshape would
    // still RE-CENTER a window the user deliberately moved. AOSP likewise
    // only rotates when the resolved rotation differs from the current one.
    dispatch_main_async_void(^{
        // Under the window policy a non-committal request means "no fixed
        // orientation": the app gets its whole window back.
        if (g_wp.active) {
            apply_requested_orientation_policy_main(winpolicy::kOrientUnspecified);
            return;
        }
        bool currentlyLandscape = g_phone_w > g_phone_h;
        if (currentlyLandscape == landscape) return;
        std::fprintf(stderr,
                     "[I] cocoa: restoreUserOrientation -> %s (user rotation)\n",
                     landscape ? "landscape" : "portrait");
        CocoaApplyRequestedOrientation(landscape);
    });
}

// ---- MACAPK_WINDOW_TREE_DUMP (diagnostic, default OFF) ---------------------
// What the WINDOW SERVER is given, as opposed to what our self-capture reads:
// every window's NSView tree with each view's CALayer tree (class, frame,
// hidden, opacity, zPosition, opaque), each MTKView's draw-tick count, and the
// layer the framework's EGL window surface is bound to. Scheduled at +3 s and
// +8 s after every content-view swap. Written for the GD Meltdown interstitial
// (docs/gd-meltdown-interstitial-hidden-window_2026-10-01.md): the composite
// reads BLANK while the real window shows the paused game, so only a dump of
// the tree the window server composites can say which layer that is.
static bool MacapkWindowTreeDumpOn() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_WINDOW_TREE_DUMP");
        return e && *e == '1';
    }();
    return on;
}
// Main thread only: drawInMTKView ticks per view (cleared never; tiny).
static NSMapTable<MTKView*, NSNumber*>* g_wtree_ticks = nil;
static void MacapkWindowTreeCountTick(MTKView* view) {
    if (!MacapkWindowTreeDumpOn()) return;
    if (!g_wtree_ticks) g_wtree_ticks = [[NSMapTable weakToStrongObjectsMapTable] retain];
    NSNumber* n = [g_wtree_ticks objectForKey:view];
    const long long c = n ? n.longLongValue + 1 : 1;
    [g_wtree_ticks setObject:@(c) forKey:view];
    if (!view.window && (c == 1 || c % 300 == 0))
        std::fprintf(stderr, "[wtree] drawInMTKView on a WINDOWLESS view=%p tick=%lld\n",
                     (void*)view, c);
}
static void MacapkWindowTreeDumpLayer(CALayer* l, int depth) {
    if (!l || depth > 20) return;
    CGRect f = l.frame;
    CGColorRef bg = l.backgroundColor;
    const double bga = bg ? CGColorGetAlpha(bg) : 0.0;
    std::string extra;
    if ([l isKindOfClass:[CAMetalLayer class]]) {
        CAMetalLayer* ml = (CAMetalLayer*)l;
        char b[96];
        std::snprintf(b, sizeof b, " drawable=%.0fx%.0f%s", ml.drawableSize.width,
                      ml.drawableSize.height,
                      (void*)ml == macapk::AngleBoundLayer() ? " EGL-BOUND" : "");
        extra = b;
    }
    std::fprintf(stderr, "[wtree] %*sL %s %p frame=(%.0f,%.0f %.0fx%.0f) hidden=%d op=%.2f "
                 "z=%.2f opaque=%d bgA=%.2f contents=%d%s\n",
                 depth * 2, "", object_getClassName(l), (void*)l, f.origin.x, f.origin.y,
                 f.size.width, f.size.height, (int)l.hidden, l.opacity, l.zPosition,
                 (int)l.opaque, bga, l.contents ? 1 : 0, extra.c_str());
    for (CALayer* s in l.sublayers) MacapkWindowTreeDumpLayer(s, depth + 1);
}
static void MacapkWindowTreeDumpView(NSView* v, int depth) {
    if (!v || depth > 20) return;
    NSRect f = v.frame;
    std::string extra;
    if ([v isKindOfClass:[MTKView class]]) {
        MTKView* mv = (MTKView*)v;
        NSNumber* n = g_wtree_ticks ? [g_wtree_ticks objectForKey:mv] : nil;
        char b[160];
        std::snprintf(b, sizeof b, " MTK paused=%d ticks=%lld delegate=%p%s%s%s", (int)mv.paused,
                      n ? n.longLongValue : 0LL, (void*)mv.delegate,
                      mv == g_active_render_view ? " ACTIVE" : "",
                      mv == g_last_metal_view ? " LAST-PATH-A" : "",
                      mv == g_last_gl_metal_view ? " LAST-GL" : "");
        extra = b;
    }
    std::fprintf(stderr, "[wtree] %*sV %s %p frame=(%.0f,%.0f %.0fx%.0f) hidden=%d alpha=%.2f%s\n",
                 depth * 2, "", object_getClassName(v), (void*)v, f.origin.x, f.origin.y,
                 f.size.width, f.size.height, (int)v.hidden, v.alphaValue, extra.c_str());
    if (v.layer) MacapkWindowTreeDumpLayer(v.layer, depth + 1);
    for (NSView* s in v.subviews) MacapkWindowTreeDumpView(s, depth + 1);
}
// Main thread only.
static void MacapkWindowTreeDump(const char* why) {
    std::fprintf(stderr, "[wtree] ===== %s: windows=%lu egl-bound=%p =====\n", why,
                 (unsigned long)[[NSApp windows] count], macapk::AngleBoundLayer());
    for (NSWindow* w in [NSApp windows]) {
        std::fprintf(stderr, "[wtree] W %s %p visible=%d level=%ld%s\n",
                     object_getClassName(w), (void*)w, (int)w.isVisible, (long)w.level,
                     w == g_placeholder_window ? " PRIMARY" : "");
        MacapkWindowTreeDumpView(w.contentView, 1);
    }
    // Views that left every window but still tick (the per-view map is weak).
    if (g_wtree_ticks) {
        for (MTKView* mv in g_wtree_ticks) {
            if (mv.window) continue;
            std::fprintf(stderr, "[wtree] ORPHAN MTKView %p ticks=%lld paused=%d superview=%p\n",
                         (void*)mv, [[g_wtree_ticks objectForKey:mv] longLongValue],
                         (int)mv.paused, (void*)mv.superview);
            if (mv.layer) MacapkWindowTreeDumpLayer(mv.layer, 2);
        }
    }
    std::fflush(stderr);
}
static void MacapkWindowTreeScheduleAfterSwap() {
    if (!MacapkWindowTreeDumpOn()) return;
    static int s_swap = 0;
    const int k = ++s_swap;
    for (double sec : {3.0, 8.0}) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(sec * NSEC_PER_SEC)),
                       dispatch_get_main_queue(), ^{
            char why[64];
            std::snprintf(why, sizeof why, "swap#%d +%.0fs", k, sec);
            MacapkWindowTreeDump(why);
        });
    }
}

void CocoaSetWindowContentView(void* window, void* content_view) {
    if (!window || !content_view) return;
    NSWindow* w = (__bridge NSWindow*)window;
    NSView*  v  = (__bridge NSView*)content_view;
    SFTRACE("SetWindowContentView POSTED phone=%dx%d", g_phone_w, g_phone_h);
    dispatch_main_async_void(^{
        // v1.0.129: route the APK's content view through the letterbox
        // container (set up in CocoaInit) when the window in question
        // is our primary placeholder window. The container is black-bg
        // and auto-resizes; the APK's view goes in as a centered
        // subview at its declared native size, with both X+Y margins
        // flexible so it stays centered when the window resizes / goes
        // fullscreen instead of stretching to fill.
        //
        // Pre-v1.0.129 behavior (direct [w setContentView:v]) is
        // retained for any non-primary window the APK creates (e.g. a
        // dialog with its own NSWindow) -- those are rare and the
        // letterbox concept doesn't apply.
        if (w == g_placeholder_window && g_letterbox_container != nil) {
            // Remove any previously-added centered view (e.g. earlier
            // setContentView call on the same window during activity
            // recreate / setContentView re-invoke). The container
            // itself stays installed.
            // v1.0.1047: the view about to be removed may be mid-gesture. A
            // DOWN already delivered to Java would otherwise never get its UP,
            // leaving a stuck pressed state and a ViewGroup holding a touch
            // target that no longer exists. AOSP synthesizes ACTION_CANCEL when
            // a gesture's target goes away; silence is what a device never
            // sends. No-op when nothing is down.
            macapk_cancel_active_touch("content-view-swap");
            for (NSView* sub in [[g_letterbox_container subviews] copy]) {
                [sub removeFromSuperview];
            }
            // v1.0.503: aspect-FIT the render view into the container (was: pin
            // at native size, centered, all-margins-flexible -> a window resize
            // only grew the black bars). MacApkLetterboxContainer re-fits this
            // subview on every resize; here we set the initial frame to the same
            // fit so the first frame is right. At boot the container == the
            // device-aspect size, so the fit fills it exactly == today's native
            // centered frame (boot/sweep render unchanged). No autoresize margins
            // -- the container drives the geometry.
            // v1.0.1649: the same frame the container's own layout gives
            // (the policy's placement, or the legacy aspect-fit).
            NSRect frame = render_frame_in_container(g_letterbox_container.bounds.size, nullptr);
            SFTRACE("SetWindowContentView EXECUTING phone=%dx%d "
                    "containerBounds=%.0fx%.0f fitFrame=%.0fx%.0f+%.0f+%.0f",
                    g_phone_w, g_phone_h,
                    g_letterbox_container.bounds.size.width,
                    g_letterbox_container.bounds.size.height,
                    frame.size.width, frame.size.height,
                    frame.origin.x, frame.origin.y);
            [v setFrame:frame];
            v.autoresizingMask = NSViewNotSizable;
            [g_letterbox_container addSubview:v];
            // v1.0.1649: lay it out through the container once, so the letterbox
            // background (bars) matches a view swapped in mid-letterbox too.
            if (g_wp.active) refit_letterbox_main();
            // v1.0.1047: removing the outgoing view left the window with no
            // first responder, so an activity swapped in this way was
            // keyboard-dead until the user happened to click. CocoaShowWindow
            // is the only other makeFirstResponder site and it does not run for
            // a content-view swap. On a device the newly resumed activity's
            // window always has focus, so this is restoring the contract rather
            // than adding a convenience.
            [w makeFirstResponder:v];
        } else {
            [w setContentView:v];
        }
        // After the swap, on main: a present that reads the new value is
        // dispatched behind this block, so its host selection sees the swapped
        // hierarchy (see g_canvas_host_epoch).
        g_canvas_host_epoch.fetch_add(1, std::memory_order_acq_rel);
        MacapkWindowTreeScheduleAfterSwap();
    });
}

void CocoaShowWindow(void* window) {
    if (!window) return;
    NSWindow* w = (__bridge NSWindow*)window;
    dispatch_main_async_void(^{
        if (MacApkHarnessNoActivate()) [w orderBack:nil];
        else {
            [w makeKeyAndOrderFront:nil];
            [g_app activateIgnoringOtherApps:YES];
            MacApkFocusLogAfterShow("window shown +1.5s");
        }
        // Make the game surface the first responder so hardware keyDown:/
        // keyUp: reach MacApkMetalView without requiring a click first.
        // (A click still re-focuses it via mouseDown, so this is a
        // convenience for keyboard-first interaction like the age gate.)
        MTKView* mv = g_last_metal_view;
        if (mv && mv.window == w) [w makeFirstResponder:mv];
    });
}

void CocoaCloseWindow(void* window) {
    if (!window) return;
    // v1.0.86: __bridge_transfer is a no-op in non-ARC; the explicit
    // [w release] below replicates the intended "take ownership and
    // release at end of function" semantics (paired with the +1 retain
    // from [[NSWindow alloc] init...] in CocoaCreateWindow).
    NSWindow* w = (__bridge NSWindow*)window;
    [w close];
    [w release];
}

// --- Translation splash overlay --------------------------------------

// Strong refs held while the overlay is visible. Cleared on Hide.
// All accesses route through the main thread, so no locking needed.
static NSView*      g_xlate_overlay   = nil;
static NSTextField* g_xlate_headline  = nil;
static NSTextField* g_xlate_body      = nil;
static NSTextField* g_xlate_detail    = nil;

// User-facing copy. The body explains the wait once; the detail line
// is updated as the translator subprocess emits progress hints
// (lift-count summaries, chunk-compile progress lines).
static NSString* const kXlateBodyText =
    @"Arm32 apps require a one-time translation that gets cached for "
    @"faster subsequent runs. Starting translation. This should take "
    @"2–5 minutes depending on the app complexity.";

static NSTextField* makeOverlayLabel(NSString* text, CGFloat fontSize,
                                      bool bold, NSColor* color) {
    NSTextField* tf = [[NSTextField alloc] initWithFrame:NSZeroRect];
    [tf setEditable:NO];
    [tf setSelectable:NO];
    [tf setBezeled:NO];
    [tf setDrawsBackground:NO];
    [tf setStringValue:text ? text : @""];
    [tf setTextColor:color];
    [tf setAlignment:NSTextAlignmentCenter];
    NSFont* font = bold
        ? [NSFont boldSystemFontOfSize:fontSize]
        : [NSFont systemFontOfSize:fontSize];
    [tf setFont:font];
    [tf setLineBreakMode:NSLineBreakByWordWrapping];
    if (@available(macOS 10.10, *)) {
        // Multi-line wrapping for the body / detail. Cocoa's NSTextField
        // wraps when usesSingleLineMode is NO and a height-allowing
        // layout is set; we anchor explicit frames below so this just
        // tells AppKit to break lines instead of clipping.
        [tf.cell setWraps:YES];
        [tf.cell setUsesSingleLineMode:NO];
    }
    return tf;
}

// The overlay core. Two callers now share it -- the native-translation
// notice (below) and the COLD-BOOT setup notice that main.cpp puts up while
// UnpackApk runs -- because they are the same widget saying different words,
// and a second copy of this layout code would drift from this one.
static void ShowOverlayWithText(NSString* headline, NSString* bodyText,
                                NSString* detailText) {
    if (!g_initialized.load()) CocoaInit();
    void (^body)(void) = ^{
        if (!g_placeholder_window) return;
        NSView* parent = [g_placeholder_window contentView];
        if (!parent) return;

        // Tear down a stale overlay first so repeated Show calls (e.g.
        // multiple lib<name>.so going through findLibrary one after the
        // other) refresh the headline instead of stacking views.
        if (g_xlate_overlay) {
            [g_xlate_overlay removeFromSuperview];
            g_xlate_overlay  = nil;
            g_xlate_headline = nil;
            g_xlate_body     = nil;
            g_xlate_detail   = nil;
        }

        NSRect bounds = [parent bounds];
        g_xlate_overlay = [[NSView alloc] initWithFrame:bounds];
        [g_xlate_overlay setWantsLayer:YES];
        // Solid backing with slight translucency so the title bar shows
        // through. No blur (NSVisualEffectView) and no animation.
        g_xlate_overlay.layer.backgroundColor =
            [[NSColor colorWithCalibratedWhite:0.05 alpha:0.95] CGColor];
        // Auto-resize so the overlay tracks any later window resize.
        [g_xlate_overlay setAutoresizingMask:
            NSViewWidthSizable | NSViewHeightSizable];

        // Headline: app-level, not lib-level. The body line below
        // already explains the per-binary one-time cost; surfacing
        // "libgame.so" / "libcocos2dcpp.so" / etc. in the headline
        // just confuses users who didn't ship the APK themselves.
        g_xlate_headline = makeOverlayLabel(
            headline, 22.0, /*bold=*/true,
            [NSColor whiteColor]);
        [g_xlate_overlay addSubview:g_xlate_headline];

        // Body: the static explanation copy.
        g_xlate_body = makeOverlayLabel(
            bodyText, 13.0, /*bold=*/false,
            [NSColor colorWithCalibratedWhite:0.85 alpha:1.0]);
        [g_xlate_overlay addSubview:g_xlate_body];

        // Detail: live status from the translator's stderr. Starts
        // empty; AppClassLoader.translate streams updates via
        // CocoaUpdateTranslationOverlay as the subprocess prints
        // boundary events ("lifted N functions...", "chunked
        // compile...", etc.).
        g_xlate_detail = makeOverlayLabel(
            detailText, 11.0, /*bold=*/false,
            [NSColor colorWithCalibratedWhite:0.65 alpha:1.0]);
        [g_xlate_overlay addSubview:g_xlate_detail];

        // Layout: stack vertically, centered. Hardcoded heights because
        // NSStackView + auto-layout would pull in more AppKit overhead
        // for no UX win at this scale.
        CGFloat W = bounds.size.width;
        CGFloat H = bounds.size.height;
        CGFloat inset_x = 28.0;
        CGFloat content_w = W - 2 * inset_x;

        CGFloat headline_h = 30.0;
        CGFloat body_h     = 90.0;   // 4-5 lines of wrapped text
        CGFloat detail_h   = 18.0;
        CGFloat gap        = 14.0;
        CGFloat total_h    = headline_h + gap + body_h + gap + detail_h;
        CGFloat top_y      = (H + total_h) / 2.0;

        [g_xlate_headline setFrame:
            NSMakeRect(inset_x, top_y - headline_h, content_w, headline_h)];
        [g_xlate_body setFrame:
            NSMakeRect(inset_x, top_y - headline_h - gap - body_h,
                       content_w, body_h)];
        [g_xlate_detail setFrame:
            NSMakeRect(inset_x,
                       top_y - headline_h - gap - body_h - gap - detail_h,
                       content_w, detail_h)];

        [parent addSubview:g_xlate_overlay
                positioned:NSWindowAbove
                relativeTo:nil];
    };
    if ([NSThread isMainThread]) body();
    else dispatch_async(dispatch_get_main_queue(), body);
}

void CocoaShowTranslationOverlay(const char* libname) {
    // libname is logged for debugging but no longer surfaced in the
    // user-facing headline. End-users don't think of an Android app as
    // a collection of `lib*.so` files; "Translating app" reads as a
    // single coherent action even when the APK ships multiple native
    // libraries that each go through findLibrary separately.
    (void)libname;
    ShowOverlayWithText(@"Translating app", kXlateBodyText, @"Starting…");
}

// The COLD-BOOT setup notice. Same widget, different words: main.cpp puts it
// up while UnpackApk converts DEX and translates native code, which on a cold
// launch is the entire long pole and used to happen with NO WINDOW AT ALL --
// an arm64 app simply looked like it had failed to open. See the window-first
// boot order in runtime/src/main.cpp.
void CocoaShowSetupOverlay(const char* headline, const char* body) {
    ShowOverlayWithText(
        headline ? [NSString stringWithUTF8String:headline] : @"Setting up",
        body     ? [NSString stringWithUTF8String:body]     : @"",
        @"Starting…");
}

// Pump AppKit for up to `seconds` FROM THE MAIN THREAD, so a main thread that
// is deliberately blocking on background work still draws and stays
// responsive. This is the other half of the window-first boot order: the
// window is worth nothing if the thread that owns it never services it.
//
// nextEventMatchingMask: runs the run loop internally, so CA transactions and
// display-link observers are serviced too -- which is what actually paints the
// overlay. The inner drain uses a past date so a burst of queued events is
// dispatched without waiting `seconds` per event.
void CocoaPumpEvents(double seconds) {
    if (!g_initialized.load()) return;
    if (![NSThread isMainThread]) return;
    @autoreleasepool {
        NSDate* until = [NSDate dateWithTimeIntervalSinceNow:seconds];
        NSEvent* ev = [g_app nextEventMatchingMask:NSEventMaskAny
                                         untilDate:until
                                            inMode:NSDefaultRunLoopMode
                                           dequeue:YES];
        while (ev) {
            [g_app sendEvent:ev];
            ev = [g_app nextEventMatchingMask:NSEventMaskAny
                                    untilDate:[NSDate distantPast]
                                       inMode:NSDefaultRunLoopMode
                                      dequeue:YES];
        }
        [g_app updateWindows];
    }
}

void CocoaUpdateTranslationOverlay(const char* detail) {
    // Fires per translation progress tick from the loader (JVM) thread,
    // which never drains its implicit pool (the v1.0.952 CocoaPresentBitmap
    // rule); each call would otherwise pin one autoreleased NSString for the
    // process life. The dispatched block is copied (retaining `s`) before
    // the pool pops on return.
    @autoreleasepool {
        NSString* s = detail && *detail
            ? [NSString stringWithUTF8String:detail]
            : @"";
        void (^body)(void) = ^{
            if (!g_xlate_detail) return;
            [g_xlate_detail setStringValue:s];
        };
        if ([NSThread isMainThread]) body();
        else dispatch_async(dispatch_get_main_queue(), body);
    }
}

void CocoaHideTranslationOverlay() {
    void (^body)(void) = ^{
        if (!g_xlate_overlay) return;
        [g_xlate_overlay removeFromSuperview];
        g_xlate_overlay  = nil;
        g_xlate_headline = nil;
        g_xlate_body     = nil;
        g_xlate_detail   = nil;
    };
    if ([NSThread isMainThread]) body();
    else dispatch_async(dispatch_get_main_queue(), body);
}

}  // namespace macapk

// ---- MTKView subclass with mouse-event handlers ----
//
// Subclass of MTKView so we can override mouseDown:/Dragged:/Up: and
// forward to the Java touch dispatcher. Cocoa delivers these to the
// firstResponder; we accept first-responder status so they reach us.
// v1.0.503: letterbox / aspect-fit container -- the window's contentView.
// Black bg; its single render subview (the MTKView) is aspect-fit-centered into
// the container's bounds on every resize, so the content scales with the window
// with NO distortion. Combined with NSWindow.contentAspectRatio (set in
// CocoaInit) the window can only be dragged along the app's w:h, so the fit
// fills exactly (no bars) for a normal resize and letterboxes only for
// full-screen / zoom where the display aspect differs. Overriding
// resizeSubviewsWithOldSize: REPLACES the subview's autoresizingMask sizing.
@interface MacApkLetterboxContainer : NSView
@end
@implementation MacApkLetterboxContainer
- (void)resizeSubviewsWithOldSize:(NSSize)oldSize {
    NSArray<NSView*>* subs = [self subviews];
    if (subs.count == 0) { [super resizeSubviewsWithOldSize:oldSize]; return; }
    // v1.0.1649 (phase 2a): under the window policy the render view is the
    // live window's layout (the app box, its size-compat scale and letterbox
    // position), NOT an aspect-fit: a fit scaled a size-compat app UP to fill
    // a fullscreen screen and re-told it the display. Legacy windows keep the
    // v1.0.503 aspect-fit. See macapk::render_frame_in_container.
    macapk::layout_letterbox_container_main(self);
}
@end

// v1.0.1649 (phase 2a): the runtime window's delegate. It feeds macOS
// fullscreen and user resizes through the pure winpolicy transitions
// (window_policy.h) and applies only their results; every method is a thin
// forward to a macapk:: helper next to the policy block. AppKit calls a
// window delegate on the main thread.
@interface MacApkWindowDelegate : NSObject <NSWindowDelegate>
@end
@implementation MacApkWindowDelegate
// Returned unchanged (AppKit's own proposal); the MEASURED content is what
// windowDidEnterFullScreen uses, since a notched panel's safe area can differ.
- (NSSize)window:(NSWindow*)window willUseFullScreenContentSize:(NSSize)proposedSize {
    (void)window;
    return proposedSize;
}
- (void)windowWillEnterFullScreen:(NSNotification*)note {
    (void)note;
    macapk::window_will_enter_fullscreen_main();
}
- (void)windowDidEnterFullScreen:(NSNotification*)note {
    macapk::window_did_enter_fullscreen_main((NSWindow*)[note object]);
}
- (void)windowWillExitFullScreen:(NSNotification*)note {
    (void)note;
    macapk::window_will_exit_fullscreen_main();
}
- (void)windowDidExitFullScreen:(NSNotification*)note {
    macapk::window_did_exit_fullscreen_main((NSWindow*)[note object]);
}
- (void)windowDidFailToEnterFullScreen:(NSWindow*)window {
    (void)window;
    macapk::window_did_fail_fullscreen_main(true);
}
- (void)windowDidFailToExitFullScreen:(NSWindow*)window {
    (void)window;
    macapk::window_did_fail_fullscreen_main(false);
}
- (NSSize)windowWillResize:(NSWindow*)sender toSize:(NSSize)frameSize {
    return macapk::window_will_resize_main(sender, frameSize);
}
- (void)windowDidEndLiveResize:(NSNotification*)note {
    macapk::window_did_end_live_resize_main((NSWindow*)[note object]);
}
// Every resize, live or not: the ones that are not a live drag and not ours
// (tiling, Fill, Zoom, AX -- NSWindow.h:968-973 sends the live-resize
// notifications only for a resize-corner drag) are the user's too.
- (void)windowDidResize:(NSNotification*)note {
    macapk::window_did_resize_main((NSWindow*)[note object]);
}
@end

namespace macapk {
// The window keeps a WEAK reference to its delegate, so the delegate lives for
// the process (the runtime leaves via _exit, never tearing the window down).
static MacApkWindowDelegate* g_window_delegate = nil;
static void install_window_delegate(NSWindow* w) {
    if (!w) return;
    if (!g_window_delegate) g_window_delegate = [[MacApkWindowDelegate alloc] init];
    [w setDelegate:g_window_delegate];
}
}  // namespace macapk

// Conforms to NSTextInputClient so host text composition works (see keyDown:
// and the NSTextInputClient section below). The conformance is what makes
// NSView.inputContext non-nil at all -- AppKit documents it as "Returns nil if
// the receiver doesn't conform to NSTextInputClient protocol"
// (MacOSX.sdk/.../AppKit.framework/Headers/NSView.h:342-344), so without it
// there is no way to reach the macOS input method from this view.
@interface MacApkMetalView : MTKView <NSTextInputClient>
- (void)debugSyntheticTapAtViewX:(CGFloat)vx Y:(CGFloat)vy;
- (void)debugSyntheticTouchAction:(int)action atViewX:(CGFloat)vx Y:(CGFloat)vy;
- (void)debugSyntheticKey:(int)keycode unicode:(int)uni;
- (void)macapkMagnifyPhase:(int)phase magnification:(double)magnification at:(NSPoint)p;
- (void)macapkRotatePhase:(int)phase rotation:(double)degrees at:(NSPoint)p;
- (void)macapkScrollAt:(NSPoint)p dx:(CGFloat)dx dy:(CGFloat)dy precise:(BOOL)precise
                 phase:(NSEventPhase)ph momentumPhase:(NSEventPhase)mph;
- (void)macapkTrackpadReset;
- (void)macapkTrackpadClaimForButton;
- (void)macapkTrackpadContacts:(int)n ids:(const int*)ids phases:(const int*)phases
                            xs:(const float*)xs ys:(const float*)ys
                        cursor:(NSPoint)cur padPoints:(NSSize)pad;
@end

// MacKeyEventToAndroid lives in host_keymap.h (header-only, so
// debug/keyshortcut_gate.sh can drive it with synthetic NSEvents), together with
// the Command-as-Ctrl rule (MACAPK_CMD_AS_CTRL).
using macapk::hostkey::MacKeyEventToAndroid;

// AppKit never delivers keyUp: for a key pressed while Command is held (the
// Command keystroke is routed as a key equivalent), so a Command chord would
// reach the guest as a lone ACTION_DOWN. keyDown: delivers such a chord's DOWN
// and UP together and records the key here; keyUp: drops the matching up if
// it does arrive (e.g. Command released first, so the up carries no Command).
// Main thread only. Indexed by the Carbon virtual key code.
static bool g_cmd_chord_down[128];

// MACAPK_DEBUG_HOSTKEY_FILE=<path> (debug harness, default off): each line the
// harness appends -- "cmd+v", "cmd+shift+v", "ctrl+a", "v", "cmd+[" -- becomes
// a synthetic NSEvent keyDown/keyUp pair routed the way NSApplication routes a
// keystroke to the key window: a Command keystroke is first offered as a key
// equivalent (the window's view hierarchy, then the main menu), anything not
// consumed goes to keyDown:, then keyUp: -- UNLESS Command is held, in which
// case (v1.0.1658, review issue 2) the harness now matches AppKit and sends NO
// keyUp: at all, since a harness that always sent one is exactly what hid the
// stuck-key bug (g_cmd_chord_down never being cleared by a later PLAIN press
// of the same key) from every live run: it made every chord's late UP arrive
// right on schedule, when real AppKit never sends it. An explicit "cmdup"
// token on the line (e.g. "cmdup+cmd+v") forces the UP to be sent anyway, to
// exercise the OTHER thing keyUp: must do -- drop a late UP that does arrive
// (Command released before the key, the rare case AppKit does emit one for).
// So it exercises host_keymap.h, the Command-chord branch of keyDown:/keyUp:
// and the whole guest route, where MACAPK_DEBUG_AUTOKEY and MACAPK_INJECT_FILE
// start below all of that. Unlike a posted CGEvent it needs no Accessibility
// grant, never touches whatever app the user has in front, and does not
// depend on this window being key. Letters a-z, digits, and '[' (BACK,
// kVK_ANSI_LeftBracket) only (ANSI virtual key codes), plus the arrow keys
// ("up"/"down"/"left"/"right"), and "hold=<ms>" for a TIMED press with
// AppKit-cadence auto-repeat (e.g. "w+hold=100" a tap, "w+hold=1000" a hold).
static void MacApkDebugHostKeyFile(MacApkMetalView* mv, double now) {
    static const char* path = std::getenv("MACAPK_DEBUG_HOSTKEY_FILE");
    if (!path || !path[0] || !mv) return;
    static double last = 0.0;
    if (now - last < 0.2) return;
    last = now;
    std::string claimed = std::string(path) + ".claimed";
    if (std::rename(path, claimed.c_str()) != 0) return;
    FILE* f = std::fopen(claimed.c_str(), "r");
    if (!f) return;
    static const unsigned short kAnsi[26] = {  // kVK_ANSI_A .. kVK_ANSI_Z
        0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E,
        0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06};
    static const unsigned short kDigit[10] = {  // kVK_ANSI_0 .. kVK_ANSI_9
        0x1D, 0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19};
    char line[128];
    while (std::fgets(line, sizeof line, f)) {
        NSEventModifierFlags mods = 0;
        char key = 0;
        bool forceUp = false;   // "cmdup" token: send the UP anyway (see header comment)
        long holdMs = 0;        // "hold=<ms>": a TIMED press (see the HOLD note below)
        unichar arrow = 0;      // "up"/"down"/"left"/"right": an arrow key
        unsigned short arrowVk = 0;
        // "activate" / "deactivate" (harness focus control, pairs with
        // MACAPK_NO_ACTIVATE): make the app active with its window key for a
        // measured stretch, then hand focus back, so a test of "the app is in
        // front" holds the owner's keyboard for seconds rather than a whole run.
        if (!std::strncmp(line, "activate", 8)) {
            [[mv window] makeKeyAndOrderFront:nil];
            [NSApp activateIgnoringOtherApps:YES];
            [[mv window] makeFirstResponder:mv];
            std::fprintf(stderr, "[key] hostkey harness: activated\n");
            continue;
        }
        if (!std::strncmp(line, "deactivate", 10)) {
            [NSApp deactivate];
            [[mv window] orderBack:nil];
            std::fprintf(stderr, "[key] hostkey harness: deactivated\n");
            continue;
        }
        for (char* tok = std::strtok(line, "+ \t\r\n"); tok; tok = std::strtok(nullptr, "+ \t\r\n")) {
            if (!std::strcmp(tok, "cmd")) mods |= NSEventModifierFlagCommand;
            else if (!std::strcmp(tok, "cmdup")) forceUp = true;
            else if (!std::strcmp(tok, "ctrl")) mods |= NSEventModifierFlagControl;
            else if (!std::strcmp(tok, "shift")) mods |= NSEventModifierFlagShift;
            else if (!std::strcmp(tok, "opt")) mods |= NSEventModifierFlagOption;
            else if (!std::strncmp(tok, "hold=", 5)) holdMs = std::strtol(tok + 5, nullptr, 10);
            else if (!std::strcmp(tok, "up"))    { arrow = NSUpArrowFunctionKey;    arrowVk = 0x7E; }
            else if (!std::strcmp(tok, "down"))  { arrow = NSDownArrowFunctionKey;  arrowVk = 0x7D; }
            else if (!std::strcmp(tok, "left"))  { arrow = NSLeftArrowFunctionKey;  arrowVk = 0x7B; }
            else if (!std::strcmp(tok, "right")) { arrow = NSRightArrowFunctionKey; arrowVk = 0x7C; }
            else if (std::strlen(tok) == 1) key = tok[0];
        }
        unsigned short vk;
        if (arrow) vk = arrowVk;
        else if (key >= 'a' && key <= 'z') vk = kAnsi[key - 'a'];
        else if (key >= '0' && key <= '9') vk = kDigit[key - '0'];
        else if (key == '[') vk = 0x21;   // kVK_ANSI_LeftBracket (review issue 3: Cmd+[ BACK)
        else continue;
        // HOLD (amongus-key-tap-move lane, 2026-10-01): "hold=<ms>" presses the key
        // for <ms> the way AppKit delivers a physically held key -- keyDown:
        // (isARepeat=NO), then, once the press outlasts the user's own
        // +[NSEvent keyRepeatDelay], keyDown: (isARepeat=YES) every
        // +[NSEvent keyRepeatInterval], then ONE keyUp: -- so a short tap
        // (hold=100) and a held key (hold=1000) reach keyDown:/keyUp: with the
        // real gap between them, which the instant DOWN+UP pair below cannot
        // express. Plain and arrow keys only (no Command: AppKit sends no keyUp:
        // for a Command chord, see above).
        if (arrow && holdMs <= 0) holdMs = 1;   // an arrow has no instant-pair form below
        if (holdMs > 0 && !(mods & NSEventModifierFlagCommand)) {
            const unichar hc = arrow ? arrow : (unichar)((mods & NSEventModifierFlagShift) && key >= 'a' ? key - 32 : key);
            const NSEventModifierFlags hm = mods | (arrow ? (NSEventModifierFlagFunction | NSEventModifierFlagNumericPad) : 0);
            const NSInteger wn = [[mv window] windowNumber];
            const double t0 = [[NSProcessInfo processInfo] systemUptime];
            const double delay = [NSEvent keyRepeatDelay], interval = [NSEvent keyRepeatInterval];
            MacApkMetalView* hv = mv;
            NSEvent* (^mk)(NSEventType, BOOL) = ^NSEvent*(NSEventType ty, BOOL rep) {
                NSString* cs = [NSString stringWithCharacters:&hc length:1];
                return [NSEvent keyEventWithType:ty location:NSZeroPoint modifierFlags:hm
                    timestamp:[[NSProcessInfo processInfo] systemUptime] windowNumber:wn context:nil
                    characters:cs charactersIgnoringModifiers:cs isARepeat:rep keyCode:vk];
            };
            std::fprintf(stderr, "[key] hostkey hold vk=0x%x for %ldms (repeat delay %.0fms interval %.0fms)\n",
                         vk, holdMs, delay * 1000.0, interval * 1000.0);
            [hv keyDown:mk(NSEventTypeKeyDown, NO)];
            for (double at = delay; at * 1000.0 < (double)holdMs; at += interval) {
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(at * NSEC_PER_SEC)),
                               dispatch_get_main_queue(), ^{ [hv keyDown:mk(NSEventTypeKeyDown, YES)]; });
            }
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)holdMs * NSEC_PER_MSEC),
                           dispatch_get_main_queue(), ^{
                [hv keyUp:mk(NSEventTypeKeyUp, NO)];
                std::fprintf(stderr, "[key] hostkey hold vk=0x%x released after %.0fms\n", vk,
                             ([[NSProcessInfo processInfo] systemUptime] - t0) * 1000.0);
            });
            continue;
        }
        // What AppKit reports for the chord: charactersIgnoringModifiers is the
        // key (shifted when Shift is held); characters is the control char under
        // Control and the plain (or shifted) key otherwise, Command included.
        unichar base = (unichar)((mods & NSEventModifierFlagShift) && key >= 'a' ? key - 32 : key);
        unichar ch = (mods & NSEventModifierFlagControl) && key >= 'a' ? (unichar)(key - 'a' + 1) : base;
        NSString* chars = [NSString stringWithCharacters:&ch length:1];
        NSString* bases = [NSString stringWithCharacters:&base length:1];
        NSWindow* w = [mv window];
        NSEvent* down = [NSEvent keyEventWithType:NSEventTypeKeyDown location:NSZeroPoint
            modifierFlags:mods timestamp:[[NSProcessInfo processInfo] systemUptime]
            windowNumber:[w windowNumber] context:nil characters:chars
            charactersIgnoringModifiers:bases isARepeat:NO keyCode:vk];
        NSEvent* up = [NSEvent keyEventWithType:NSEventTypeKeyUp location:NSZeroPoint
            modifierFlags:mods timestamp:[[NSProcessInfo processInfo] systemUptime]
            windowNumber:[w windowNumber] context:nil characters:chars
            charactersIgnoringModifiers:bases isARepeat:NO keyCode:vk];
        BOOL equivalent = NO;
        if (mods & NSEventModifierFlagCommand) {
            equivalent = (w && [w performKeyEquivalent:down])
                         || [[[NSApplication sharedApplication] mainMenu] performKeyEquivalent:down];
        }
        std::fprintf(stderr, "[key] hostkey '%s%s%s%s%c' injected (key equivalent consumed=%d)\n",
                     (mods & NSEventModifierFlagCommand) ? "cmd+" : "",
                     (mods & NSEventModifierFlagControl) ? "ctrl+" : "",
                     (mods & NSEventModifierFlagOption) ? "opt+" : "",
                     (mods & NSEventModifierFlagShift) ? "shift+" : "", key, (int)equivalent);
        if (!equivalent) [mv keyDown:down];
        // AppKit never delivers keyUp: for a key pressed while Command is
        // held (see keyDown:'s own comment) -- faithful to that by default
        // (review issue 2); "cmdup" opts back in, to exercise keyUp:'s OWN
        // job of dropping a late UP that does arrive.
        if (!(mods & NSEventModifierFlagCommand) || forceUp) [mv keyUp:up];
    }
    std::fclose(f);
    std::remove(claimed.c_str());
}

// v1.0.504: Cmd+R rotates the render 90 degrees (portrait <-> landscape).
// Handled in the host (not forwarded to the guest) because macOS has no Android
// rotate affordance. charactersIgnoringModifiers keeps it keyboard-layout-stable.
static inline BOOL MacApkIsRotateShortcut(NSEvent* event) {
    if (!([event modifierFlags] & NSEventModifierFlagCommand)) return NO;
    NSString* base = [event charactersIgnoringModifiers];
    if (base.length == 0) return NO;
    unichar c = [base characterAtIndex:0];
    return (c == 'r' || c == 'R');
}

// Is a guest-side editor bound to the input method right now?
//
// WHY this gate exists: Android runs every hardware key through the IME stage
// BEFORE the View sees it (ViewRootImpl chains viewPreIme -> ime ->
// earlyPostIme -> nativePostIme -> viewPostIme, ViewRootImpl.java:1757-1763),
// but that stage is a no-op unless an IME session is actually bound --
// InputMethodManager.dispatchInputEvent returns DISPATCH_NOT_HANDLED when
// isImeSessionAvailableLocked() is false (InputMethodManager.java:4284-4316),
// so with no editor focused the key goes straight to the View with its literal
// key code. Mac.apk IS the IME, and it already records the bound target:
// macapkActiveInput() for a TextView target and macapkActiveConnection() for a
// Compose/InputConnection editor. Mirroring that gate keeps gameplay keys
// (WASD, arrows) on the raw KeyEvent path and keeps AppKit's press-and-hold
// accent HUD out of games, while still giving real editors a real input method.
//
// Main-thread only (called from keyDown:), which is what lets the resolved
// class/method handles live in function statics behind a dispatch_once.
static bool MacApkGuestTextTargetActive() {
    if (!macapk::g_touch_vm) return false;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
            return false;
    } else if (r != JNI_OK || !env) return false;

    static jclass    s_imm  = nullptr;   // global ref; framework class, never unloaded
    static jmethodID s_view = nullptr;
    static jmethodID s_conn = nullptr;
    static dispatch_once_t immOnce;
    dispatch_once(&immOnce, ^{
        jclass local = env->FindClass("android/view/inputmethod/InputMethodManager");
        if (!local) { env->ExceptionClear(); return; }
        s_imm = (jclass) env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        if (!s_imm) return;
        s_view = env->GetStaticMethodID(s_imm, "macapkActiveInput",
                                        "()Landroid/widget/TextView;");
        if (!s_view) env->ExceptionClear();
        s_conn = env->GetStaticMethodID(s_imm, "macapkActiveConnection",
                                        "()Landroid/view/inputmethod/InputConnection;");
        if (!s_conn) env->ExceptionClear();
    });
    if (!s_imm) return false;

    bool active = false;
    if (s_view) {
        jobject o = env->CallStaticObjectMethod(s_imm, s_view);
        if (env->ExceptionCheck()) { env->ExceptionClear(); o = nullptr; }
        if (o) { active = true; env->DeleteLocalRef(o); }
    }
    if (!active && s_conn) {
        jobject o = env->CallStaticObjectMethod(s_imm, s_conn);
        if (env->ExceptionCheck()) { env->ExceptionClear(); o = nullptr; }
        if (o) { active = true; env->DeleteLocalRef(o); }
    }
    return active;
}

@implementation MacApkMetalView {
    double _pinchSpan;   // synthesized finger separation for magnifyWithEvent: (view points)
    double _pinchAngle;  // synthesized finger axis for rotateWithEvent: (radians, CCW, y up)
    BOOL   _twoMag, _twoRot;  // which recognizer holds the synthesized two-finger stream open
    // Host trackpad contacts (MACAPK_TRACKPAD_MULTITOUCH, see touchesBeganWithEvent:).
    // The NSTouch.identity -> int map is PROCESS-global (MacapkTrackpadIds), not per view,
    // so a replaced view keeps the ids the mapper already tracks.
    uint64_t  _tpTickGen;  // bumps per frame; a settle tick from an older frame is stale
    // Synthesized-scroll state (see scrollWheel:). A two-finger trackpad
    // gesture is turned into a single ACTION_DOWN..MOVE*..UP touch drag.
    BOOL    _scrollActive;    // a scroll gesture is in progress
    BOOL    _scrollDownSent;  // the synthetic ACTION_DOWN has been sent
    NSPoint _scrollAnchor;    // gesture start, view points (Cocoa y-up)
    NSPoint _scrollAcc;       // accumulated synthetic-finger offset

    // Host input-method (NSTextInputClient) state. The composition buffer is
    // ours, not the guest's: we have no read access to the guest editor's text,
    // so the "document" the macOS input method sees is exactly the in-flight
    // composition and nothing else. See the NSTextInputClient section below.
    NSString* _imeMarked;      // current composing text, nil when not composing
    NSRange   _imeMarkedSel;   // selection inside _imeMarked (IME-owned caret)
    // The keyDown: being interpreted, borrowed (NOT retained) for the duration
    // of the -[NSTextInputContext handleEvent:] call that is on the stack above
    // us; the caller's autoreleased NSEvent outlives every callback.
    NSEvent*  _imeKeyEvent;
    BOOL      _imeKeyConsumed; // the input method produced text/composition
}

- (BOOL)acceptsFirstResponder { return YES; }
- (BOOL)becomeFirstResponder  { return YES; }

// Deliver the click that ACTIVATES the window, instead of swallowing it.
//
// AppKit contract (NSView.h:225, `- (BOOL)acceptsFirstMouse:(NSEvent *)event`):
// the default implementation returns NO, which means a mouse-down landing in a
// window that is not already key is consumed to activate the window and is
// NEVER delivered to the hit-tested view. No mouseDown: is sent, so
// dispatchMouseAction: never runs and the guest sees no MotionEvent at all.
// The user then clicks a second time, and that one works.
//
// Android has no such concept. There is no "activate the window first" step in
// the platform: a touch that lands on a visible window is dispatched to it,
// full stop. So NO is not merely inconvenient here, it is a divergence from the
// behaviour we are reimplementing, and it is invisible in every headless
// harness we own because injected taps go through
// TouchDispatcher.macapkInjectTap -> Activity.dispatchTouchEvent and never
// touch AppKit's event routing at all.
//
// Reported by the owner as two separate bugs that are one bug: "I have to
// double tap most things in F-Droid for them to register", and Aurora Store
// "fails to focus the app in mac and no clicks get through". Both are the
// first click being eaten whenever focus is elsewhere.
//
// NSSlider overrides this the same way and for the same reason (NSSlider.h),
// so wanting the activating click is ordinary AppKit practice, not a hack.
//
// Escape hatch for bisecting an input regression; there is no reason to want
// the old behaviour in normal use.
- (BOOL)acceptsFirstMouse:(NSEvent*)event {
    (void)event;
    const char* off = std::getenv("MACAPK_FIRST_MOUSE");
    if (off && off[0] == '0') return NO;
    return YES;
}

- (void)dispatchMouseAction:(int)action atEvent:(NSEvent*)event {
    // First-fire log per action lets us confirm Cocoa is delivering the
    // events at all. If we click and never see this line, the MTKView
    // isn't first responder (or MacApkMetalView wasn't picked up).
    static dispatch_once_t firstDownToken, firstDragToken, firstUpToken;
    if (action == 0) dispatch_once(&firstDownToken, ^{
        std::fprintf(stderr, "[touch] first mouseDown reached MacApkMetalView\n"); });
    if (action == 2) dispatch_once(&firstDragToken, ^{
        std::fprintf(stderr, "[touch] first mouseDragged reached MacApkMetalView\n"); });
    if (action == 1) dispatch_once(&firstUpToken, ^{
        std::fprintf(stderr, "[touch] first mouseUp reached MacApkMetalView\n"); });

    [self dispatchTouchAction:action
                      atPoint:[self convertPoint:[event locationInWindow]
                                        fromView:nil]];
}

// Core JNI touch dispatch by view-point (Cocoa points, y-up). Shared by
// the mouse handlers above and the synthesized scroll-drag below. The
// Java dispatch(IFFFF) scales by the backing factor and Y-flips into
// Android pixel coords (origin top-left).
// TouchDispatcher.dispatchWheel(x, y, viewW, viewH, vscroll, hscroll): YES when
// the step went to a taken native input queue.
- (BOOL)macapkWheelToQueueAt:(NSPoint)p v:(CGFloat)v h:(CGFloat)h {
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return NO;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return NO;
    } else if (r != JNI_OK || !env) return NO;
    static jmethodID mid = nullptr;
    static bool looked = false;
    if (!looked) {
        looked = true;
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        mid = env->GetMethodID(cls, "dispatchWheel", "(FFFFFF)Z");
        if (!mid && env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }
    if (!mid) return NO;
    jboolean took = env->CallBooleanMethod(macapk::g_touch_dispatcher, mid,
                                           (jfloat) p.x, (jfloat) p.y,
                                           (jfloat) self.bounds.size.width,
                                           (jfloat) self.bounds.size.height,
                                           (jfloat) v, (jfloat) h);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return NO;
    }
    return took ? YES : NO;
}

- (void)dispatchTouchAction:(int)action atPoint:(NSPoint)p {
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) {
        static dispatch_once_t warnToken;
        dispatch_once(&warnToken, ^{
            std::fprintf(stderr,
                "[touch] event received but g_touch_vm=%p g_touch_dispatcher=%p "
                "-- TouchDispatcher not registered yet\n",
                (void*)macapk::g_touch_vm,
                (void*)macapk::g_touch_dispatcher);
        });
        return;
    }
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;

    if (!macapk::g_touch_mid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        macapk::g_touch_mid = env->GetMethodID(cls, "dispatch", "(IFFFF)V");
        env->DeleteLocalRef(cls);
    }
    if (!macapk::g_touch_mid) return;

    // View bounds in points; the Java side knows the drawable is
    // bounds * backingScaleFactor pixels. We pass both so the Java
    // side can scale into Android-pixel coordinates.
    CGFloat w = self.bounds.size.width;
    CGFloat h = self.bounds.size.height;
    // v1.0.1047: remember just enough to synthesize ACTION_CANCEL if this view
    // is torn out before the UP arrives (see macapk_cancel_active_touch).
    // 0 = DOWN, 1 = UP, 3 = CANCEL; a DRAG (2) only refreshes the coordinates.
    if (action == 0)                     macapk::g_touch_is_down = true;
    else if (action == 1 || action == 3) macapk::g_touch_is_down = false;
    macapk::g_touch_last_x = p.x; macapk::g_touch_last_y = p.y;
    macapk::g_touch_last_w = w;   macapk::g_touch_last_h = h;
    // MACAPK_INPUT_TRACE=1: log how long this ONE synchronous call blocked the AppKit/JVM main
    // thread -- the direct measurement of "did my tap register yet" latency. Diagnostic only.
    static const bool kInputTrace = std::getenv("MACAPK_INPUT_TRACE") != nullptr;
    uint64_t t0 = kInputTrace ? clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) : 0;
    macapk::MacApkInputPriorityBegin();
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_touch_mid,
                        (jint) action,
                        (jfloat) p.x, (jfloat) p.y,
                        (jfloat) w, (jfloat) h);
    macapk::MacApkInputPriorityEnd();
    if (kInputTrace) {
        double ms = (double)(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) - t0) / 1e6;
        std::fprintf(stderr, "[input-trace] touch action=%d took %.2fms\n", action, ms);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// HOST MOUSE as an Android mouse (MACAPK_MOUSE_POINTER, default ON; GitHub #21).
// Every button event -- left, right and other -- updates the pressed-button set
// and hands it, with the modifier meta state and the pointer, to
// TouchDispatcher.dispatchHostMouse, which emits what AOSP's CursorInputMapper
// emits (SOURCE_MOUSE / TOOL_TYPE_MOUSE, real buttonState, BUTTON_PRESS /
// BUTTON_RELEASE, metaState). AppKit's buttonNumber 0..4 (left, right, middle,
// back, forward) is AOSP's BUTTON_PRIMARY..BUTTON_FORWARD bit order, so the bit
// is 1 << buttonNumber. =0 restores the finger path for the left button and
// leaves right / other with super, the pre-change behaviour.
static int g_host_mouse_buttons = 0;   // main thread only
static bool MacApkMousePointerEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_MOUSE_POINTER");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

- (BOOL)macapkHostMouse:(NSEvent*)event press:(int)press {
    if (!MacApkMousePointerEnabled()) return NO;
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return NO;
    NSInteger n = [event buttonNumber];
    int bit = (n >= 0 && n <= 4) ? (1 << n) : 0;
    if (press > 0) g_host_mouse_buttons |= bit;
    else if (press < 0) g_host_mouse_buttons &= ~bit;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return NO;
    } else if (r != JNI_OK || !env) return NO;
    static jmethodID mid = nullptr;
    static bool looked = false;
    if (!looked) {
        looked = true;
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        mid = env->GetMethodID(cls, "dispatchHostMouse", "(IIFFFF)V");
        if (!mid && env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }
    if (!mid) return NO;
    NSPoint p = [self convertPoint:[event locationInWindow] fromView:nil];
    CGFloat w = self.bounds.size.width, h = self.bounds.size.height;
    // Keep the teardown CANCEL bookkeeping (macapk_cancel_active_touch) in step.
    const bool down = (g_host_mouse_buttons & 0x7) != 0;
    macapk::g_touch_is_down = down;
    macapk::g_touch_last_x = p.x; macapk::g_touch_last_y = p.y;
    macapk::g_touch_last_w = w;   macapk::g_touch_last_h = h;
    const int meta = macapk::hostkey::PointerMetaState([event modifierFlags]);
    macapk::MacApkInputPriorityBegin();
    env->CallVoidMethod(macapk::g_touch_dispatcher, mid, (jint) g_host_mouse_buttons,
                        (jint) meta, (jfloat) p.x, (jfloat) p.y, (jfloat) w, (jfloat) h);
    macapk::MacApkInputPriorityEnd();
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return YES;
}

// HOVER (GitHub #25, MACAPK_HOVER_EVENTS default ON): AppKit sends mouseMoved: /
// mouseExited: to a view only through a tracking area. Pointer movement with no
// button held goes through macapkHostMouse:press:0 (an unchanged, empty button
// set), which TouchDispatcher turns into a SOURCE_MOUSE HOVER_MOVE and, per
// window, HOVER_ENTER / HOVER_EXIT; leaving the view exits the hovered window.
// ActiveAlways: Android hovers whatever window is under the pointer, focused or
// not, and so does a Mac app under the cursor.
static bool MacApkHoverEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_HOVER_EVENTS");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}
- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    if (!MacApkHoverEnabled() || !MacApkMousePointerEnabled()) return;
    for (NSTrackingArea* ta in [self.trackingAreas copy]) {
        if (ta.owner == self && ta.userInfo[@"macapk.hover"]) [self removeTrackingArea:ta];
    }
    NSTrackingArea* ta = [[NSTrackingArea alloc]
        initWithRect:NSZeroRect
             options:(NSTrackingMouseMoved | NSTrackingMouseEnteredAndExited
                      | NSTrackingActiveAlways | NSTrackingInVisibleRect)
               owner:self
            userInfo:@{ @"macapk.hover": @YES }];
    [self addTrackingArea:ta];
}
- (void)mouseMoved:(NSEvent*)event {
    if (!MacApkHoverEnabled() || ![self macapkHostMouse:event press:0]) [super mouseMoved:event];
}
- (void)mouseExited:(NSEvent*)event {
    if (!MacApkHoverEnabled() || !MacApkMousePointerEnabled()
            || !macapk::g_touch_vm || !macapk::g_touch_dispatcher) { [super mouseExited:event]; return; }
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;
    static jmethodID mid = nullptr;
    static bool looked = false;
    if (!looked) {
        looked = true;
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        mid = env->GetMethodID(cls, "dispatchHostMouseExit", "(FFFF)V");
        if (!mid && env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }
    if (!mid) return;
    NSPoint p = [self convertPoint:[event locationInWindow] fromView:nil];
    env->CallVoidMethod(macapk::g_touch_dispatcher, mid, (jfloat) p.x, (jfloat) p.y,
                        (jfloat) self.bounds.size.width, (jfloat) self.bounds.size.height);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

- (void)mouseDown:(NSEvent*)event {
    // A click on our window that did not make it key (the owner's grey-traffic-
    // light report): AppKit normally activates the app and keys the window on
    // any click, so reaching here non-key means that did not happen. A click
    // in our own window is the user choosing this app, which is exactly when
    // cooperative activation lets an app activate itself, so ask explicitly.
    NSWindow* w = [self window];
    if (w && ![w isKeyWindow] && !MacApkHarnessNoActivate()) {
        MacApkFocusLog("click on a non-key window");
        [w makeKeyAndOrderFront:nil];
        [NSApp activate];
    }
    if ([self macapkHostMouse:event press:1]) return;
    [self dispatchMouseAction:0 atEvent:event];
}
- (void)mouseDragged:(NSEvent*)event {
    if ([self macapkHostMouse:event press:0]) return;
    [self dispatchMouseAction:2 atEvent:event];
}

// HOST TRACKPAD CONTACTS -> Android multi-pointer (MACAPK_TRACKPAD_MULTITOUCH, default ON).
//
// A trackpad finger is an INDIRECT NSTouch; AppKit delivers them to a view only
// when it opts in through allowedTouchTypes ("The types of touch interactions the
// view allows"). Without that, a two-finger hold produced no event at all, so an
// app needing two pointers down at once (PianOli's settings lock) was unreachable.
// Single-finger pointing/clicking is unchanged -- it still arrives as mouse events --
// and resting touches stay excluded (wantsRestingTouches defaults to NO: "a user
// rests their thumb ... By default, these touches are not delivered"), so a thumb
// parked on the pad never counts as a finger. The mapping itself (settle, FREEFORM
// placement around the cursor, scroll/pinch precedence) is
// com.macapk.loader.TrackpadGestureMapper, which cites AOSP's TouchInputMapper.
// =0: the view never opts in, so none of the handlers below can run.
static bool MacapkTrackpadMultitouchOn() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_TRACKPAD_MULTITOUCH");
        return !(e && e[0] == '0');
    }();
    return on;
}

// NSTouch.identity -> a small int the mapper tracks a finger by. Process-global (every
// MacApkMetalView shares it; touch events arrive on the main thread, like the harness).
static NSMutableDictionary* MacapkTrackpadIds() {
    static NSMutableDictionary* d = [[NSMutableDictionary alloc] init];
    return d;
}
static int g_tpNextId = 0;

- (instancetype)initWithFrame:(CGRect)frameRect device:(id<MTLDevice>)device {
    self = [super initWithFrame:frameRect device:device];
    if (self && MacapkTrackpadMultitouchOn()) {
        // NSView.h: allowedTouchTypes "Defaults to NSTouchTypeDirect if linked on or after
        // 10_12" -- the Touch Bar only, so a trackpad finger never reached us. ADD indirect.
        self.allowedTouchTypes |= NSTouchTypeMaskIndirect;
    }
    return self;
}

// A replaced / removed view, or a window that loses key, can end a contact without
// AppKit ever sending touchesEnded / touchesCancelled to this view. An EMPTY frame
// tells the mapper every contact is gone (it CANCELs a live hold and forgets them),
// so no ghost pointer outlives the view that owned it.
- (void)viewWillMoveToWindow:(NSWindow*)newWindow {
    if (MacapkTrackpadMultitouchOn()) {
        NSNotificationCenter* nc = [NSNotificationCenter defaultCenter];
        if (self.window) {
            [self macapkTrackpadReset];
            [nc removeObserver:self name:NSWindowDidResignKeyNotification object:self.window];
        }
        if (newWindow) {
            [nc addObserver:self selector:@selector(macapkWindowResignedKey:)
                       name:NSWindowDidResignKeyNotification object:newWindow];
        }
    }
    [super viewWillMoveToWindow:newWindow];
}

- (void)macapkWindowResignedKey:(NSNotification*)note {
    (void)note;
    [self macapkTrackpadReset];
}

- (void)macapkTrackpadReset {
    [MacapkTrackpadIds() removeAllObjects];
    [self macapkTrackpadContacts:0 ids:nullptr phases:nullptr xs:nullptr ys:nullptr
                          cursor:NSZeroPoint padPoints:NSZeroSize];
}

// =0 (not handled): the NSResponder default, i.e. byte-identical to not overriding.
- (void)touchesBeganWithEvent:(NSEvent*)event     { if (![self macapkTrackpadEvent:event]) [super touchesBeganWithEvent:event]; }
- (void)touchesMovedWithEvent:(NSEvent*)event     { if (![self macapkTrackpadEvent:event]) [super touchesMovedWithEvent:event]; }
- (void)touchesEndedWithEvent:(NSEvent*)event     { if (![self macapkTrackpadEvent:event]) [super touchesEndedWithEvent:event]; }
- (void)touchesCancelledWithEvent:(NSEvent*)event { if (![self macapkTrackpadEvent:event]) [super touchesCancelledWithEvent:event]; }

// A physical two-finger CLICK is macOS's default secondary click: both fingers rest on the
// pad first, then it presses and AppKit sends rightMouseDown:. Nothing used to override it,
// so the resting fingers would engage as a hold and the lift would deliver a primary TAP to
// whatever is under the cursor (a "Delete" button, say). A button taking two fingers
// cancels / suppresses them (TrackpadGestureMapper.claim); the responder-chain behaviour
// of the click itself is unchanged ([super ...]).
- (void)rightMouseDown:(NSEvent*)event {
    [self macapkTrackpadClaimForButton];
    if (![self macapkHostMouse:event press:1]) [super rightMouseDown:event];
}
- (void)otherMouseDown:(NSEvent*)event {
    [self macapkTrackpadClaimForButton];
    if (![self macapkHostMouse:event press:1]) [super otherMouseDown:event];
}
- (void)rightMouseDragged:(NSEvent*)event { if (![self macapkHostMouse:event press:0]) [super rightMouseDragged:event]; }
- (void)otherMouseDragged:(NSEvent*)event { if (![self macapkHostMouse:event press:0]) [super otherMouseDragged:event]; }
- (void)rightMouseUp:(NSEvent*)event { if (![self macapkHostMouse:event press:-1]) [super rightMouseUp:event]; }
- (void)otherMouseUp:(NSEvent*)event { if (![self macapkHostMouse:event press:-1]) [super otherMouseUp:event]; }

// Flatten one AppKit touch event: every indirect touch of the event (the ones
// that just ended or were cancelled included, at their final position), a
// stable small id per finger, NSTouchPhase as 0 began / 1 moved / 2 stationary /
// 3 ended / 4 cancelled, the pad position normalized with y flipped to
// top-down ("(0.0,0.0) is the lower-left position on the touch device"), and the
// pad's physical size (deviceSize is in points, 72 per inch).
- (BOOL)macapkTrackpadEvent:(NSEvent*)event {
    if (!MacapkTrackpadMultitouchOn()) return NO;
    NSMutableDictionary* tpIds = MacapkTrackpadIds();
    enum { kMax = 10 };
    int ids[kMax], phases[kMax];
    float xs[kMax], ys[kMax];
    int n = 0, live = 0;
    NSSize pad = NSZeroSize;
    for (NSTouch* t in [event touchesMatchingPhase:NSTouchPhaseAny inView:self]) {
        if (n >= kMax) break;
        if (t.type != NSTouchTypeIndirect) continue;
        id key = t.identity;
        NSNumber* num = [tpIds objectForKey:key];
        if (!num) {
            num = [NSNumber numberWithInt:(g_tpNextId++ & 0x7fffffff)];
            [tpIds setObject:num forKey:key];
        }
        const NSTouchPhase tp = t.phase;
        const int ph = (tp & NSTouchPhaseBegan)     ? 0
                     : (tp & NSTouchPhaseMoved)     ? 1
                     : (tp & NSTouchPhaseEnded)     ? 3
                     : (tp & NSTouchPhaseCancelled) ? 4 : 2;
        if (ph >= 3) [tpIds removeObjectForKey:key];
        else live++;
        ids[n] = num.intValue;
        phases[n] = ph;
        xs[n] = (float)t.normalizedPosition.x;
        ys[n] = (float)(1.0 - t.normalizedPosition.y);
        pad = t.deviceSize;
        n++;
    }
    if (live == 0) [tpIds removeAllObjects];
    // The ONE stage no harness crosses (AppKit has no public NSTouch constructor), so say
    // when it first runs: a failed hand test can then tell "AppKit never delivered an
    // indirect touch" from "the mapper never settled".
    static bool s_firstIndirect = false;
    if (n > 0 && !s_firstIndirect) {
        s_firstIndirect = true;
        std::fprintf(stderr, "[trackpad] first indirect touch n=%d pad=%.0fx%.0f mm\n", n,
                     pad.width * 25.4 / 72.0, pad.height * 25.4 / 72.0);
    }
    const NSPoint cur = [self convertPoint:[self.window mouseLocationOutsideOfEventStream]
                                  fromView:nil];
    [self macapkTrackpadContacts:n ids:ids phases:phases xs:xs ys:ys cursor:cur padPoints:pad];
    return YES;
}

static JNIEnv* MacapkTouchJniEnv() {
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return nullptr;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return nullptr;
    } else if (r != JNI_OK) {
        return nullptr;
    }
    return env;
}

// The one door into TouchDispatcher.dispatchTrackpad, shared by the real touch
// handlers and the harness (CocoaDebugTrackpad).
- (void)macapkTrackpadContacts:(int)n ids:(const int*)ids phases:(const int*)phases
                            xs:(const float*)xs ys:(const float*)ys
                        cursor:(NSPoint)cur padPoints:(NSSize)pad {
    JNIEnv* env = MacapkTouchJniEnv();
    if (!env) return;
    static jmethodID s_mid = nullptr;
    if (!s_mid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        s_mid = env->GetMethodID(cls, "dispatchTrackpad", "(I[I[I[F[FFFFFFF)J");
        env->DeleteLocalRef(cls);
        if (!s_mid) { env->ExceptionClear(); return; }
    }
    jintArray jids = env->NewIntArray(n), jph = env->NewIntArray(n);
    jfloatArray jx = env->NewFloatArray(n), jy = env->NewFloatArray(n);
    if (!jids || !jph || !jx || !jy) { env->ExceptionClear(); return; }
    if (n > 0) {
        env->SetIntArrayRegion(jids, 0, n, ids);
        env->SetIntArrayRegion(jph, 0, n, phases);
        env->SetFloatArrayRegion(jx, 0, n, xs);
        env->SetFloatArrayRegion(jy, 0, n, ys);
    }
    const double mmPerPt = 25.4 / 72.0;
    const NSSize vb = self.bounds.size;
    jlong tick = env->CallLongMethod(macapk::g_touch_dispatcher, s_mid, (jint)n, jids, jph, jx, jy,
                                     (jfloat)(pad.width * mmPerPt), (jfloat)(pad.height * mmPerPt),
                                     (jfloat)cur.x, (jfloat)cur.y,
                                     (jfloat)vb.width, (jfloat)vb.height);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); tick = -1; }
    env->DeleteLocalRef(jids); env->DeleteLocalRef(jph);
    env->DeleteLocalRef(jx);   env->DeleteLocalRef(jy);
    [self macapkScheduleTrackpadTick:tick];
}

// A resting hold produces NO further touch events, so the mapper's settle
// interval needs a timer. Every frame bumps the generation, so only the tick
// asked for by the LATEST frame (or tick) ever runs.
- (void)macapkScheduleTrackpadTick:(long long)ms {
    const uint64_t gen = ++_tpTickGen;
    if (ms < 0) return;
    if (ms < 1) ms = 1;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)ms * (int64_t)NSEC_PER_MSEC),
                   dispatch_get_main_queue(), ^{
        if (self->_tpTickGen != gen) return;
        JNIEnv* env = MacapkTouchJniEnv();
        if (!env) return;
        static jmethodID s_tick = nullptr;
        if (!s_tick) {
            jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
            s_tick = env->GetMethodID(cls, "trackpadTick", "()J");
            env->DeleteLocalRef(cls);
            if (!s_tick) { env->ExceptionClear(); return; }
        }
        jlong next = env->CallLongMethod(macapk::g_touch_dispatcher, s_tick);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return; }
        [self macapkScheduleTrackpadTick:next];
    });
}

// macOS recognized a two-finger SCROLL on the fingers a hold may be using, or a host mouse
// BUTTON went down on them: the scroll / click owns them (TrackpadGestureMapper.claim).
// Pinch claims from Java; the left button claims from TouchDispatcher.dispatch().
- (void)macapkTrackpadClaim:(BOOL)systemGesture {
    if (!MacapkTrackpadMultitouchOn()) return;
    JNIEnv* env = MacapkTouchJniEnv();
    if (!env) return;
    static jmethodID s_claim = nullptr;
    if (!s_claim) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        s_claim = env->GetMethodID(cls, "trackpadClaim", "(Z)V");
        env->DeleteLocalRef(cls);
        if (!s_claim) { env->ExceptionClear(); return; }
    }
    env->CallVoidMethod(macapk::g_touch_dispatcher, s_claim, systemGesture ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}
- (void)macapkTrackpadClaimForScroll { [self macapkTrackpadClaim:YES]; }
- (void)macapkTrackpadClaimForButton { [self macapkTrackpadClaim:NO]; }
- (void)mouseUp:(NSEvent*)event {
    if ([self macapkHostMouse:event press:-1]) return;
    [self dispatchMouseAction:1 atEvent:event];
}

// TRACKPAD PINCH -> a synthesized TWO-FINGER Android gesture.
//
// Mac.apk delivered exactly ONE pointer, ever: `dispatch(IFFFF)` carries a single x/y and
// TouchDispatcher built every MotionEvent with pointerCount=1. So ACTION_POINTER_DOWN/UP were
// never generated and `ScaleGestureDetector` -- fully implemented in this tree and simply never
// fed -- could not fire. **Pinch-to-zoom was unreachable in every map, image, PDF and browser
// app**, with no error anywhere: the app just never saw a second finger. Organic Maps is in the
// corpus and is exactly this shape.
//
// macOS reports a pinch as ONE NSEvent carrying a scalar `magnification`, not as two touches, so
// the two fingers must be synthesized. We straddle the cursor horizontally with a span that grows
// and shrinks with the accumulated magnification -- the same convention the Android emulator and
// the iOS Simulator use for a trackpad pinch. AOSP's ordering (DOWN, POINTER_DOWN, MOVE*,
// POINTER_UP, UP) is produced on the Java side in TouchDispatcher.dispatchPinch.
- (void)magnifyWithEvent:(NSEvent*)event {
    NSPoint p = [self convertPoint:event.locationInWindow fromView:nil];
    // NSEventPhase is a bitmask; treat any of began/changed/ended explicitly and ignore cancelled
    // the same way ended is handled, so a cancelled pinch still lifts its fingers.
    const int phase = (event.phase & NSEventPhaseBegan) ? 0
                    : (event.phase & (NSEventPhaseEnded | NSEventPhaseCancelled)) ? 2 : 1;
    [self macapkMagnifyPhase:phase magnification:event.magnification at:p];
}

// TRACKPAD ROTATE (GitHub #22). macOS recognizes a two-finger twist as its own
// gesture, NSEventTypeRotate, carrying `rotation` in degrees (counterclockwise
// positive), just as it reports a pinch as magnify. Without this handler a twist
// that starts the moment the fingers land reached the app as nothing: the
// fingers MOVE before the 100 ms settle, so TrackpadGestureMapper leaves the
// contact to the macOS recognizers (a scroll or a pinch), and nobody handled
// the one that fired. Only a twist after a pause worked, through the hold.
// It rides the synthesized pinch: the same two fingers around the cursor, with
// their axis turned by the accumulated rotation. A twist that also spreads is
// one stream that both recognizers feed (macOS sends magnify and rotate events
// for the same contact), which is what a touchscreen app sees when two real
// fingers twist and spread at once. AOSP's touchpad stack has no rotate
// gesture (GestureConverter maps a two-finger swipe and a pinch only), so this
// is the touchscreen contract, like the hold. A rotate with no phase (not a
// trackpad gesture) has no end to close the stream with, so it is ignored.
- (void)rotateWithEvent:(NSEvent*)event {
    if (event.phase == NSEventPhaseNone) { [super rotateWithEvent:event]; return; }
    NSPoint p = [self convertPoint:event.locationInWindow fromView:nil];
    const int phase = (event.phase & NSEventPhaseBegan) ? 0
                    : (event.phase & (NSEventPhaseEnded | NSEventPhaseCancelled)) ? 2 : 1;
    [self macapkRotatePhase:phase rotation:event.rotation at:p];
}

// The bodies of magnifyWithEvent: / rotateWithEvent: below the NSEvent decode,
// so the harness (CocoaDebugTrackpad kinds 1 and 3) runs exactly what a real
// pinch or twist runs.
- (void)macapkMagnifyPhase:(int)phase magnification:(double)magnification at:(NSPoint)p {
    [self macapkTwoFinger:0 phase:phase delta:magnification at:p];
}
- (void)macapkRotatePhase:(int)phase rotation:(double)degrees at:(NSPoint)p {
    [self macapkTwoFinger:1 phase:phase delta:degrees at:p];
}

// One synthesized two-finger stream (DOWN, POINTER_DOWN, MOVE*, POINTER_UP, UP)
// held open while either recognizer (0 magnify, 1 rotate) is live. A pinch
// alone produces exactly what it always did.
- (void)macapkTwoFinger:(int)which phase:(int)phase delta:(double)d at:(NSPoint)p {
    BOOL& mine = which == 0 ? _twoMag : _twoRot;
    if (phase == 0) {
        // A began for a recognizer already live means its end was lost: close
        // that stream before starting a fresh one, never two DOWNs in a row.
        if (mine) { _twoMag = _twoRot = NO; [self dispatchPinchPhase:2 at:p span:_pinchSpan]; }
        const BOOL live = _twoMag || _twoRot;
        mine = YES;
        if (!live) {
            _pinchSpan = 120.0;             // base finger separation in view points
            _pinchAngle = 0.0;
            [self dispatchPinchPhase:0 at:p span:_pinchSpan];
        }
        return;
    }
    if (phase == 1) {
        if (!(_twoMag || _twoRot)) {
            // A change with no began (the harness, or a began this view never
            // saw): open the stream first.
            _pinchSpan = 120.0;
            _pinchAngle = 0.0;
            [self dispatchPinchPhase:0 at:p span:_pinchSpan];
        }
        mine = YES;
        if (which == 0) {
            // A magnification of +1.0 doubles the span; clamp so a violent gesture cannot invert it.
            _pinchSpan *= (1.0 + d);
            if (_pinchSpan < 16.0) _pinchSpan = 16.0;
            if (_pinchSpan > 4000.0) _pinchSpan = 4000.0;
        } else {
            _pinchAngle += d * M_PI / 180.0;
        }
        [self dispatchPinchPhase:1 at:p span:_pinchSpan];
        return;
    }
    if (!mine) return;                      // an end for a recognizer that never began
    mine = NO;
    if (!(_twoMag || _twoRot)) [self dispatchPinchPhase:2 at:p span:_pinchSpan];
}

- (void)dispatchPinchPhase:(int)phase at:(NSPoint)p span:(double)span {
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;
    static jmethodID s_pinchMid = nullptr;
    if (!s_pinchMid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        s_pinchMid = env->GetMethodID(cls, "dispatchTwoFinger", "(IFFFFFF)V");
        env->DeleteLocalRef(cls);
        if (!s_pinchMid) { env->ExceptionClear(); return; }
    }
    NSSize sz = self.bounds.size;
    env->CallVoidMethod(macapk::g_touch_dispatcher, s_pinchMid, (jint)phase,
                        (jfloat)p.x, (jfloat)p.y, (jfloat)span, (jfloat)_pinchAngle,
                        (jfloat)sz.width, (jfloat)sz.height);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

// Two-finger trackpad scroll (and classic mouse wheel) -> a synthesized
// Android touch-drag, so scrollable targets scroll the macOS-natural way.
// User request (docs/future_implementations.md #2/#3: "two finger touch on
// the mac's touchpad scrolls like it does in macOS ... lists, game views").
//
// We turn the gesture into a single ACTION_DOWN .. ACTION_MOVE* ..
// ACTION_UP drag (a finger on a touchscreen), which is the most broadly
// compatible target: engines that only understand touch (cocos2d-x lists,
// Unity ScrollRects) scroll, and -- once the View-tree scroll machinery
// (RecyclerView/ScrollView recycle + ViewCanvasDriver offset) lands -- so
// will standard-View apps. This handler only ADDS behavior (Cocoa drops
// scroll events today), so it cannot regress rendering or click/key input.
//
// Direction follows macOS: scrollingDeltaY>0 means "content moves down",
// which on a touchscreen is a finger dragged DOWN. The Java dispatch flips
// Cocoa's bottom-origin Y to Android's top-origin Y, so a downward Android
// finger is a DECREASING Cocoa Y -> acc.y -= deltaY. X is not flipped.
//
// The synthetic DOWN is held back until the gesture clears touch-slop, so a
// tiny scroll never lands as a spurious tap/click on the target underneath.
- (void)scrollWheel:(NSEvent*)event {
    [self macapkScrollAt:[self convertPoint:[event locationInWindow] fromView:nil]
                      dx:event.scrollingDeltaX dy:event.scrollingDeltaY
                 precise:event.hasPreciseScrollingDeltas
                   phase:event.phase momentumPhase:event.momentumPhase];
}

// The body of scrollWheel:, split out so the harness (CocoaDebugTrackpad kind
// 2, inject verb `hostwheel`) runs exactly what a real wheel notch runs: a
// background app cannot be sent a scroll event by pid.
- (void)macapkScrollAt:(NSPoint)p dx:(CGFloat)dx dy:(CGFloat)dy precise:(BOOL)precise
                 phase:(NSEventPhase)ph momentumPhase:(NSEventPhase)mph {
    // A classic wheel step over a window whose input queue was taken
    // (NativeActivity / GameActivity) goes there as the ACTION_SCROLL Android
    // delivers (TouchDispatcher.dispatchWheel, MACAPK_WHEEL_SCROLL_EVENT): the
    // drag below arrives inside one frame and a native app polling its pointer
    // state never sees it. AOSP signs: AXIS_VSCROLL +1 = up (macOS deltaY > 0
    // for a wheel turned away), AXIS_HSCROLL +1 = right (macOS deltaX > 0 is a
    // scroll to the LEFT). Trackpad phases keep the drag.
    if (!precise && ph == NSEventPhaseNone && mph == NSEventPhaseNone
        && (dx != 0.0 || dy != 0.0)
        && [self macapkWheelToQueueAt:p v:dy h:-dx]) {
        return;
    }
    // v1.0.569 trackpad-feel knobs (owner: two-finger lane-steering in
    // swipe games overshoots -- a natural flick travels 100-300 points +
    // a momentum tail, which a swipe-segmenting game reads as several
    // swipes).
    //  - MACAPK_SCROLL_SCALE=<f>: scale the synthetic drag travel.
    //  - MACAPK_SCROLL_NO_MOMENTUM=0/1: ignore the macOS momentum (fling)
    //    tail. With it on, the drag ends the moment the fingers lift,
    //    like a real touchscreen finger; Android-side flings still work
    //    because the guest's velocity tracker computes them from the
    //    drag's release velocity (on-device semantics).
    // v1.0.570 picked scale 0.5 + no-momentum ON (the owner A/B'd both side
    // by side for Subway steering, "100% better"). No-momentum stays the
    // default. The scale is 1.0 for the TRACKPAD since GitHub #24: a precise
    // delta is the distance the content moves under the fingers in macOS
    // points, and the synthesized finger must travel that same distance or
    // every direct-manipulation surface (a map, a list) pans at half the
    // speed of the same app on the desktop. Android's own touchpad stack
    // (GestureConverter::handleScroll, TWO_FINGER_SWIPE) moves its fake
    // finger by the gesture's full delta, unscaled. The halving the owner
    // liked mostly came from the momentum tail, which is still off.
    // MACAPK_SCROLL_SCALE=0.5 restores the old travel for an A/B.
    static const char* kScaleEnv = getenv("MACAPK_SCROLL_SCALE");
    static const CGFloat kScrollScale = []{
        double v = kScaleEnv ? atof(kScaleEnv) : 1.0;
        return (CGFloat)((v >= 0.05 && v <= 4.0) ? v : 1.0);
    }();
    static const bool kNoMomentum = []{
        const char* e = getenv("MACAPK_SCROLL_NO_MOMENTUM");
        return !e || e[0] != '0';
    }();
    // Line-based (classic wheel) deltas are small integers -> amplify so a
    // notch moves a usable distance. A notch keeps the travel it has had since
    // v1.0.570 (16 pt per line x the then-global 0.5 = 8 pt; the 24 pt
    // minimum below still applies): #24 is about the trackpad, and the wheel
    // is not direct manipulation. An explicit MACAPK_SCROLL_SCALE still
    // scales both, as before.
    if (precise) {
        dx *= kScrollScale;
        dy *= kScrollScale;
    } else {
        const CGFloat wheel = 16.0 * (kScaleEnv ? kScrollScale : 0.5);
        dx *= wheel;
        dy *= wheel;
    }

    // Momentum-only events (fingers already lifted). With the knob set,
    // close any open drag at its current position and swallow the tail.
    if (kNoMomentum && ph == NSEventPhaseNone && mph != NSEventPhaseNone) {
        if (_scrollActive) {
            if (_scrollDownSent) {
                [self dispatchTouchAction:1
                    atPoint:NSMakePoint(_scrollAnchor.x + _scrollAcc.x,
                                        _scrollAnchor.y + _scrollAcc.y)];
            }
            _scrollActive = NO;
            _scrollDownSent = NO;
        }
        return;
    }

    // Classic mouse wheel: no phase info. Emit a self-contained drag per
    // notch (DOWN -> MOVE past slop -> UP); never a tap, never a stuck
    // synthetic finger.
    if (ph == NSEventPhaseNone && mph == NSEventPhaseNone) {
        if (dx == 0.0 && dy == 0.0) return;
        // "Past slop" has to be enforced, not assumed. A notch is a 1-line
        // delta, and macOS wheel acceleration hands out FRACTIONAL lines
        // (0.1..1.0) on slow turns, so after x16 x 0.5 a notch was a
        // 0.8..8 pt drag. The guest's touch slop is 8dp (ViewConfiguration
        // TOUCH_SLOP; 1dp is 1pt here, density follows the screen's backing
        // scale), and a DOWN..UP that stays inside it is a TAP on Android:
        // turning the wheel over a list clicked whatever row was under the
        // pointer (measured 2026-10-19, Metrolist: a 1px drag on a song row
        // started that song; the owner's own wheel notches logged 0.8, 1.0,
        // 3.9 and 11.5 px drags). So a notch travels at least
        // kWheelMinTravelPt (3x slop) in the direction it was turned.
        // MACAPK_WHEEL_MIN_TRAVEL=0 restores the old travel.
        static const bool kWheelMinTravel = []{
            const char* e = getenv("MACAPK_WHEEL_MIN_TRAVEL");
            return !e || e[0] != '0';
        }();
        static const CGFloat kWheelMinTravelPt = 24.0;
        const CGFloat len = hypot(dx, dy);
        if (kWheelMinTravel && len > 0.0 && len < kWheelMinTravelPt) {
            dx *= kWheelMinTravelPt / len;
            dy *= kWheelMinTravelPt / len;
        }
        NSPoint b = NSMakePoint(p.x + dx, p.y - dy);
        [self dispatchTouchAction:0 atPoint:p];
        [self dispatchTouchAction:2 atPoint:b];
        [self dispatchTouchAction:1 atPoint:b];
        return;
    }

    // Trackpad: one continuous drag across the gesture; the momentum (fling)
    // tail arrives as a separate phase and continues it as a fresh drag.
    BOOL began  = (ph == NSEventPhaseBegan)   || (mph == NSEventPhaseBegan);
    BOOL change = (ph == NSEventPhaseChanged) || (mph == NSEventPhaseChanged);
    BOOL ended  = (ph == NSEventPhaseEnded)   || (ph == NSEventPhaseCancelled)
               || (mph == NSEventPhaseEnded)  || (mph == NSEventPhaseCancelled);

    if (began) {
        // A recognized two-finger SCROLL owns the fingers: a trackpad hold on
        // them ends (CANCEL) and cannot start for the rest of this contact.
        if (ph == NSEventPhaseBegan) [self macapkTrackpadClaimForScroll];
        if (_scrollActive && _scrollDownSent) {
            // A previous gesture never closed; lift it first.
            [self dispatchTouchAction:1
                atPoint:NSMakePoint(_scrollAnchor.x + _scrollAcc.x,
                                    _scrollAnchor.y + _scrollAcc.y)];
        }
        _scrollActive = YES;
        _scrollDownSent = NO;
        _scrollAnchor = p;
        _scrollAcc = NSZeroPoint;
    }
    if (change && _scrollActive) {
        _scrollAcc.x += dx;
        _scrollAcc.y -= dy;
        if (!_scrollDownSent
            && (fabs(_scrollAcc.x) + fabs(_scrollAcc.y) > 6.0)) {
            [self dispatchTouchAction:0 atPoint:_scrollAnchor];  // DOWN (past slop)
            _scrollDownSent = YES;
        }
        if (_scrollDownSent) {
            [self dispatchTouchAction:2
                atPoint:NSMakePoint(_scrollAnchor.x + _scrollAcc.x,
                                    _scrollAnchor.y + _scrollAcc.y)];  // MOVE
        }
    }
    if (ended && _scrollActive) {
        if (_scrollDownSent) {
            // A scroll must never land as a tap. DOWN waits for 6pt of travel,
            // but the guest's slop is 8dp (~8pt), so a short two-finger nudge
            // that ended between the two lifted as a TAP on whatever was under
            // the pointer. End such a gesture with CANCEL, which every Android
            // gesture detector treats as "no click". MACAPK_WHEEL_MIN_TRAVEL=0
            // restores the UP.
            static const bool kScrollNoTap = []{
                const char* e = getenv("MACAPK_WHEEL_MIN_TRAVEL");
                return !e || e[0] != '0';
            }();
            const bool tapSized = hypot(_scrollAcc.x, _scrollAcc.y) < 12.0;
            [self dispatchTouchAction:(kScrollNoTap && tapSized) ? 3 : 1
                atPoint:NSMakePoint(_scrollAnchor.x + _scrollAcc.x,
                                    _scrollAnchor.y + _scrollAcc.y)];  // UP (or CANCEL)
        }
        _scrollActive = NO;
        _scrollDownSent = NO;
    }
}

// Forward a host keyboard event to the Java input bridge as an Android
// KeyEvent. action: 0=ACTION_DOWN, 1=ACTION_UP. Reuses the touch
// dispatcher object (registered via registerTouchDispatcher) and resolves
// its dispatchKey(int,int,int,int) method lazily.
- (void)dispatchKeyAction:(int)action atEvent:(NSEvent*)event {
    static dispatch_once_t firstKeyToken;
    if (action == 0) dispatch_once(&firstKeyToken, ^{
        std::fprintf(stderr, "[key] first keyDown reached MacApkMetalView\n"); });
    int uni = 0, meta = 0;
    int keycode = MacKeyEventToAndroid(event, &uni, &meta);
    // NSEvent.isARepeat marks the OS auto-repeating a HELD key (it raises for a non-key event,
    // and is NO on a keyUp:). Java numbers a driver repeat (repeatCount, the first press's
    // downTime) only on this word, never by inferring it from "the same DOWN twice", because
    // a key's UP can be lost on this layer (TouchDispatcher.hostKeyEvent).
    const BOOL rep = (event.type == NSEventTypeKeyDown) ? [event isARepeat] : NO;
    [self dispatchKeyRawAction:action keyCode:keycode unicode:uni meta:meta repeat:rep];
}

// The single JNI door for key delivery. Shared by the hardware-key path
// (dispatchKeyAction:atEvent:) and the input-method commit path
// (insertText:replacementRange:) so both land the identical
// (action, keyCode, unicodeChar, metaState) shape TouchDispatcher.dispatchKey
// expects, which is what its TextView.macapkHandleSoftKey /
// InputConnection.commitText / Activity.dispatchKeyEvent routing keys off.
- (void)dispatchKeyRawAction:(int)action
                     keyCode:(int)keycode
                     unicode:(int)uni
                        meta:(int)meta
                      repeat:(BOOL)autoRepeat {
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) {
        static dispatch_once_t warnK;
        dispatch_once(&warnK, ^{
            std::fprintf(stderr,
                "[key] key event received but input dispatcher not registered yet\n"); });
        return;
    }
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;
    if (!macapk::g_key_rep_mid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        macapk::g_key_rep_mid = env->GetMethodID(cls, "dispatchHostKey", "(IIIIZ)V");
        env->DeleteLocalRef(cls);
    }
    if (!macapk::g_key_rep_mid) { if (env->ExceptionCheck()) env->ExceptionClear(); return; }
    static const bool kInputTrace = std::getenv("MACAPK_INPUT_TRACE") != nullptr;
    uint64_t t0 = kInputTrace ? clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) : 0;
    macapk::MacApkInputPriorityBegin();
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_key_rep_mid,
                        (jint) action, (jint) keycode, (jint) uni, (jint) meta,
                        (jboolean) (autoRepeat ? JNI_TRUE : JNI_FALSE));
    macapk::MacApkInputPriorityEnd();
    if (kInputTrace) {
        double ms = (double)(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) - t0) / 1e6;
        std::fprintf(stderr, "[input-trace] key action=%d took %.2fms\n", action, ms);
    }
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

// performKeyEquivalent: is the proper entry point for a Cmd-key shortcut; catch
// Cmd+R here so it works regardless of which view holds first responder.
- (BOOL)performKeyEquivalent:(NSEvent*)event {
    if (MacApkIsRotateShortcut(event)) { macapk::CocoaRotateRender(); return YES; }
    return [super performKeyEquivalent:event];
}

// Menu-bar "View > Rotate" target (the item uses target:nil, so its action
// reaches this view via the responder chain when the window is key). Same
// CocoaRotateRender as the Cmd+R key handler above -- one rotate behavior whether
// invoked by the menu, its ⌘R shortcut, or the raw keystroke.
- (void)macapkMenuRotate:(id)sender { macapk::CocoaRotateRender(); }

// Override keyDown:/keyUp: WITHOUT calling super so unhandled keys don't
// trigger the macOS alert beep; we deliver them to the guest instead.
// (Cmd+R is also caught here in case performKeyEquivalent: wasn't consulted.)
//
// v1.0.x IME: with a guest editor bound, the keystroke goes through the macOS
// input method FIRST, mirroring Android's IME stage sitting ahead of the View
// stage (ViewRootImpl.java:1757-1763). That stage is where multi-keystroke
// composition happens on real Android -- a CJK candidate selection or a
// dead-key accent never reaches the app as raw key codes, it arrives as
// InputConnection commitText/setComposingText -- and NSTextInputContext is the
// macOS equivalent, so it belongs in exactly that slot. It calls back into the
// NSTextInputClient methods below, which funnel the resulting text into the
// same dispatchKey door typed text already uses.
//
// With NO editor bound we skip the input method entirely, which is both what
// AOSP does (dispatchInputEvent -> DISPATCH_NOT_HANDLED when no IME session is
// bound, InputMethodManager.java:4284-4316) and what keeps gameplay keys on the
// literal-key-code path.
- (void)keyDown:(NSEvent*)event {
    if (MacApkIsRotateShortcut(event)) { macapk::CocoaRotateRender(); return; }
    // v1.0.1658 (review issue 2, clipboard-bridge lane): a fresh keyDown: of
    // THIS key supersedes any pending "late UP" a PREVIOUS Command chord on
    // the same vk left behind. Without this: Cmd+S sets
    // g_cmd_chord_down[S]=true (its own keyUp: never arrives -- AppKit
    // swallows it), then a later PLAIN press of S (no Command, e.g. a WASD
    // rebind) dispatches its DOWN normally, but its keyUp: sees
    // g_cmd_chord_down[S] still true and treats it as the chord's missing UP
    // -- so the plain press's own ACTION_UP is dropped and the guest never
    // sees S released (a stuck key). A fresh DOWN of that vk means whatever
    // chord set the flag is over; if this DOWN is itself a chord, the branch
    // below re-sets it.
    unsigned short vk0 = [event keyCode];
    if (vk0 < 128) g_cmd_chord_down[vk0] = false;
    // A Command chord is a shortcut, not text (host_keymap.h): it reaches the
    // guest as its Ctrl chord and bypasses the input method -- AppKit hands
    // Command keystrokes to key equivalents, never to text input -- unless a
    // composition is in flight, which owns every key until it ends. DOWN and UP
    // go together because AppKit sends no keyUp: for it (g_cmd_chord_down).
    // Cmd+[ (BACK, review issue 3) is included here on the same premise: AppKit
    // swallows ITS keyUp: too, and BACK fires on the UP (Activity.onKeyUp ->
    // onBackPressed), so a lone DOWN never went back unless Command was
    // released first.
    if (!_imeMarked && (macapk::hostkey::IsCmdShortcutChord(event)
            || (macapk::hostkey::CmdAsCtrlEnabled() && macapk::hostkey::IsCmdBack(event)))) {
        unsigned short vk = [event keyCode];
        // v1.0.1658 (review issue 5): OS auto-repeat of a HELD chord arrives
        // as a fresh, complete keyDown: (isARepeat=YES, no keyUp: of its own),
        // indistinguishable from the initial press by any of the checks
        // above. Dispatching a full DOWN+UP for every repeat would paste (or
        // copy/cut/select-all) once per repeat tick, where AOSP's shortcut
        // stage requires getRepeatCount()==0 and a device's key equivalent
        // fires once per physical press. Track it as still-down and stop.
        if ([event isARepeat]) {
            if (vk < 128) g_cmd_chord_down[vk] = true;
            return;
        }
        [self dispatchKeyAction:0 atEvent:event];
        [self dispatchKeyAction:1 atEvent:event];
        if (vk < 128) g_cmd_chord_down[vk] = true;
        return;
    }
    if (!_imeMarked && !MacApkGuestTextTargetActive()) {
        [self dispatchKeyAction:0 atEvent:event];
        return;
    }
    // Composing already in flight counts as "the IME owns this key" even if the
    // callbacks below stay silent (an Esc that only cancels the composition
    // must not also reach the guest as KEYCODE_BACK).
    BOOL wasComposing = (_imeMarked != nil);
    _imeKeyEvent = event;
    _imeKeyConsumed = NO;
    [[self inputContext] handleEvent:event];
    _imeKeyEvent = nil;
    // Nothing inserted and no composition: this key is not text. Editing and
    // navigation keys (Return, Tab, Delete, arrows, Esc, Ctrl-chords) come back
    // through doCommandBySelector: and land here, so they keep reaching the
    // guest as the literal Android KeyEvent they were before this path existed.
    if (!_imeKeyConsumed && !wasComposing && !_imeMarked)
        [self dispatchKeyAction:0 atEvent:event];
}
- (void)keyUp:(NSEvent*)event {
    unsigned short vk = [event keyCode];
    if (vk < 128 && g_cmd_chord_down[vk]) {   // its UP went with the DOWN
        g_cmd_chord_down[vk] = false;
        return;
    }
    [self dispatchKeyAction:1 atEvent:event];
}

// ---- NSTextInputClient ----
//
// Our "document" is the in-flight composition and nothing else: the guest owns
// the real text buffer (a TextView or a Compose InputConnection reached through
// InputMethodManager.macapkActiveInput/macapkActiveConnection) and Mac.apk has
// no synchronous read path into it. So we answer the geometry/content queries
// for the composition we are holding and return "no document" otherwise, which
// is enough for the input method to run its conversion session and place its
// candidate panel.
//
// KNOWN GAP: the composition is not echoed into the guest editor while it is
// being built, because that needs InputConnection.setComposingText driven on
// the guest loop thread (the composing-span work tracked separately). Until
// that lands the composition is visible in the input method's own candidate
// panel and lands in the field on commit, rather than inline as you type.

- (void)insertText:(id)string replacementRange:(NSRange)replacementRange {
    // We have no host-side document, so there is nothing to replace into; the
    // guest editor applies the commit at its own caret.
    (void)replacementRange;
    NSString* s = [string isKindOfClass:[NSAttributedString class]]
        ? [(NSAttributedString*)string string]
        : (NSString*)string;
    [self unmarkText];               // a commit ends the conversion session
    _imeKeyConsumed = YES;
    if (![s isKindOfClass:[NSString class]] || s.length == 0) return;

    // Fast path, and the one that keeps pre-IME behavior bit-identical: the
    // input method handed back exactly the character the key produced, which is
    // every keystroke on a non-composing (Latin/ASCII) input source. Deliver
    // the full Android KeyEvent so keyCode and metaState survive -- games read
    // the key code, and TouchDispatcher's soft-key routing reads both.
    NSEvent* ev = _imeKeyEvent;
    if (ev && [s isEqualToString:[ev characters]]) {
        [self dispatchKeyAction:0 atEvent:ev];
        return;
    }

    // Composed or converted text: a CJK candidate, a dead-key accent, a
    // Character-Palette insertion. Android delivers these through
    // InputConnection.commitText with no hardware key code, so we send
    // KEYCODE_UNKNOWN (0) and carry the character in the unicode slot -- the
    // same (keyCode=0, unicode=c) shape MacKeyEventToAndroid already produces
    // for an unnamed printable key. One DOWN/UP pair per UTF-16 code unit, so a
    // multi-character conversion (and a surrogate pair) reassembles in the
    // guest's buffer in order.
    for (NSUInteger i = 0; i < s.length; i++) {
        int c = (int) [s characterAtIndex:i];
        [self dispatchKeyRawAction:0 keyCode:0 unicode:c meta:0 repeat:NO];
        [self dispatchKeyRawAction:1 keyCode:0 unicode:c meta:0 repeat:NO];
    }
}

// Swallowed on purpose. NSResponder's implementation would walk the responder
// chain and end in noResponderFor: (the alert beep) for the editing selectors
// the input method emits; keyDown: instead re-delivers the untouched NSEvent as
// a literal Android KeyEvent, which is what the guest expects for Return, Tab,
// Delete, the arrows and Esc.
- (void)doCommandBySelector:(SEL)selector { (void)selector; }

- (void)setMarkedText:(id)string
        selectedRange:(NSRange)selectedRange
     replacementRange:(NSRange)replacementRange {
    (void)replacementRange;          // no host-side document to replace into
    NSString* s = [string isKindOfClass:[NSAttributedString class]]
        ? [(NSAttributedString*)string string]
        : (NSString*)string;
    _imeKeyConsumed = YES;
    BOOL empty = (![s isKindOfClass:[NSString class]] || s.length == 0);
    // Copy before releasing the previous buffer: non-ARC, and the input method
    // is entitled to hand back the very string it was given.
    NSString* next = empty ? nil : [s copy];
    NSString* prev = _imeMarked;
    _imeMarked = next;
    [prev release];
    if (!_imeMarked) { _imeMarkedSel = NSMakeRange(0, 0); return; }
    // selectedRange is relative to the string being inserted; clamp so a
    // caret past our copy can never escape the buffer.
    NSUInteger loc = MIN(selectedRange.location, _imeMarked.length);
    NSUInteger len = MIN(selectedRange.length, _imeMarked.length - loc);
    _imeMarkedSel = NSMakeRange(loc, len);
}

- (void)unmarkText {
    [_imeMarked release];
    _imeMarked = nil;
    _imeMarkedSel = NSMakeRange(0, 0);
}

- (BOOL)hasMarkedText { return _imeMarked != nil; }

- (NSRange)markedRange {
    // Our document IS the composition, so it starts at 0 when one exists.
    return _imeMarked ? NSMakeRange(0, _imeMarked.length)
                      : NSMakeRange(NSNotFound, 0);
}

- (NSRange)selectedRange {
    return _imeMarked ? _imeMarkedSel : NSMakeRange(NSNotFound, 0);
}

- (NSAttributedString*)attributedSubstringForProposedRange:(NSRange)range
                                               actualRange:(NSRangePointer)actualRange {
    // Only the composition is readable; the guest's committed text is not.
    if (!_imeMarked) {
        if (actualRange) *actualRange = NSMakeRange(NSNotFound, 0);
        return nil;
    }
    NSUInteger len = _imeMarked.length;
    if (range.location > len) {
        if (actualRange) *actualRange = NSMakeRange(NSNotFound, 0);
        return nil;
    }
    NSRange clamped = NSMakeRange(range.location,
                                  MIN(range.length, len - range.location));
    if (actualRange) *actualRange = clamped;
    NSString* sub = [_imeMarked substringWithRange:clamped];
    return [[[NSAttributedString alloc] initWithString:sub] autorelease];
}

- (NSArray<NSAttributedStringKey>*)validAttributesForMarkedText {
    // We render no attributed marked text of our own, so we recognize none.
    return @[];
}

- (NSRect)firstRectForCharacterRange:(NSRange)range
                         actualRange:(NSRangePointer)actualRange {
    if (actualRange) *actualRange = range;
    NSWindow* win = [self window];
    if (!win) return NSZeroRect;
    // PLACEHOLDER GEOMETRY. The real caret rect has to come from the guest via
    // CursorAnchorInfo (InputMethodManager.updateCursorAnchorInfo), which is
    // not plumbed yet; until it is, anchor the candidate panel at the top-left
    // of the render view so it is always on-screen and never covers the field.
    // Not flipped (MTKView keeps Cocoa's y-up), so the caret box hangs down
    // from the top edge.
    const CGFloat kInset = 8.0;
    const CGFloat kLine  = 18.0;
    NSRect caret = NSMakeRect(NSMinX(self.bounds) + kInset,
                              NSMaxY(self.bounds) - kInset - kLine,
                              1.0, kLine);
    return [win convertRectToScreen:[self convertRect:caret toView:nil]];
}

- (NSUInteger)characterIndexForPoint:(NSPoint)point {
    // No hit-testable document (the guest owns the text), so no index.
    (void)point;
    return NSNotFound;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [_imeMarked release];
    [super dealloc];
}

// Debug helper (MACAPK_DEBUG_AUTOKEY): synthesize a DOWN+UP key pair on
// the same dispatchKey path as the real keyDown:/keyUp: handlers. Bypasses
// Cocoa's NSEvent queue (and the accessibility gate that blocks injected
// CGEvents from a headless shell) so the key->guest path is testable.
- (void)debugSyntheticKey:(int)keycode unicode:(int)uni {
    std::fprintf(stderr, "[key] synthetic key keycode=%d unicode=%d injected\n",
                 keycode, uni);
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;
    if (!macapk::g_key_mid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        macapk::g_key_mid = env->GetMethodID(cls, "dispatchKey", "(IIII)V");
        env->DeleteLocalRef(cls);
    }
    if (!macapk::g_key_mid) return;
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_key_mid,
                        (jint) 0, (jint) keycode, (jint) uni, (jint) 0);  // DOWN
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_key_mid,
                        (jint) 1, (jint) keycode, (jint) uni, (jint) 0);  // UP
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

// Debug helper: synthesize a mouse-down/up pair at a view-local point.
// Bypasses Cocoa's event queue (and the macOS accessibility permission
// gate that blocks tools like cliclick / CGEventPost from a sandboxed
// shell). Wired up to MACAPK_DEBUG_AUTOCLICK below for headless
// regression testing of the input path.
- (void)debugSyntheticTapAtViewX:(CGFloat)vx Y:(CGFloat)vy {
    std::fprintf(stderr, "[touch] synthetic tap (%.1f, %.1f) injected\n",
                 (double)vx, (double)vy);
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;
    if (!macapk::g_touch_mid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        macapk::g_touch_mid = env->GetMethodID(cls, "dispatch", "(IFFFF)V");
        env->DeleteLocalRef(cls);
    }
    if (!macapk::g_touch_mid) return;
    CGFloat w = self.bounds.size.width;
    CGFloat h = self.bounds.size.height;
    // Down then up. cocos2d-x v2's ScrollView / ScrollViewEx
    // distinguishes "tap" from "scroll-cancel" by checking if the
    // up event has any positional delta from the down. A perfectly
    // stationary tap (delta=0) can be classified as a degenerate
    // event and rejected. Optionally inject a small move between
    // down and up to make the tap look real.
    //   MACAPK_DEBUG_AUTOCLICK_DRAG_PX (default 0): pixel delta added
    //   to the up coordinate to defeat dead-stationary classification.
    int drag_px = 0;
    if (const char* s = std::getenv("MACAPK_DEBUG_AUTOCLICK_DRAG_PX")) {
        int v = std::atoi(s);
        if (v >= 0 && v < 100) drag_px = v;
    }
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_touch_mid,
                        (jint) 0, (jfloat) vx, (jfloat) vy, (jfloat) w, (jfloat) h);
    if (drag_px > 0) {
        // Issue one MOVE event slightly offset, then the UP at the
        // moved position.
        env->CallVoidMethod(macapk::g_touch_dispatcher,
                             macapk::g_touch_mid,
                             (jint) 2,
                             (jfloat) (vx + drag_px),
                             (jfloat) (vy + drag_px),
                             (jfloat) w, (jfloat) h);
        env->CallVoidMethod(macapk::g_touch_dispatcher,
                             macapk::g_touch_mid,
                             (jint) 1,
                             (jfloat) (vx + drag_px),
                             (jfloat) (vy + drag_px),
                             (jfloat) w, (jfloat) h);
    } else {
        env->CallVoidMethod(macapk::g_touch_dispatcher,
                             macapk::g_touch_mid,
                             (jint) 1, (jfloat) vx, (jfloat) vy,
                             (jfloat) w, (jfloat) h);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// Single touch action (0=down, 1=up, 2=move) for MACAPK_DEBUG_TAP_HOLD,
// which fires the down and up on SEPARATE frames so Unity's uGUI sees a
// real press/release (an instant same-frame down+up is not registered as
// a click by the uGUI EventSystem).
- (void)debugSyntheticTouchAction:(int)action atViewX:(CGFloat)vx Y:(CGFloat)vy {
    if (!macapk::g_touch_vm || !macapk::g_touch_dispatcher) return;
    JNIEnv* env = nullptr;
    JavaVM* vm = macapk::g_touch_vm;
    jint r = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) return;
    if (!macapk::g_touch_mid) {
        jclass cls = env->GetObjectClass(macapk::g_touch_dispatcher);
        macapk::g_touch_mid = env->GetMethodID(cls, "dispatch", "(IFFFF)V");
        env->DeleteLocalRef(cls);
    }
    if (!macapk::g_touch_mid) return;
    CGFloat w = self.bounds.size.width;
    CGFloat h = self.bounds.size.height;
    std::fprintf(stderr, "[touch] synthetic action=%d (%.1f, %.1f)\n",
                 action, (double)vx, (double)vy);
    env->CallVoidMethod(macapk::g_touch_dispatcher, macapk::g_touch_mid,
                        (jint) action, (jfloat) vx, (jfloat) vy,
                        (jfloat) w, (jfloat) h);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
}

@end

namespace macapk {
// HARNESS (MACAPK_INJECT_FILE `trackpad` / `magnify`): run the host-trackpad body
// a real trackpad runs, entered just below the NSEvent decode -- the same
// -macapkTrackpadContacts:... / -macapkMagnifyPhase:... the touch and magnify
// handlers call, on the view AppKit would deliver to (hit-tested at the cursor),
// with the settle timer, the JNI hop and the points->pixels mapping all live.
// AppKit has no public way to synthesize an NSTouch or a magnify NSEvent, so the
// decode itself (phase bits, identity, normalizedPosition) is the one stage a
// harness cannot cross; it is a dozen lines and reads only documented fields.
// a[0],a[1] = cursor as a FRACTION of the window content, top-left origin.
// kind 0: a[2..] = (id, phase, nx, ny) per contact -- EVERY contact currently on the pad,
// stationary ones included (phase 2), exactly as AppKit's touchesMatchingPhase:NSTouchPhaseAny
// reports them: the mapper treats a tracked contact a frame does not name as lost.
// kind 1: a[2] phase, a[3] mag.  kind 3: a[2] phase, a[3] rotation degrees.
// kind 4: a precise (trackpad) scroll event, a[2] phase, a[3] dy, a[4] dx in points.
bool CocoaDebugTrackpad(int kind, const float* a, int n) {
    if (n < 2) return false;
    std::vector<float> args(a, a + n);
    __block bool ok = false;
    void (^body)(void) = ^{
        NSWindow* w = g_placeholder_window;
        NSView* cv = w ? [w contentView] : nil;
        if (!cv) return;
        const NSRect b = [cv bounds];
        const CGFloat vx = b.origin.x + (CGFloat)args[0] * b.size.width;
        const CGFloat vy = [cv isFlipped] ? b.origin.y + (CGFloat)args[1] * b.size.height
                                          : b.origin.y + (1.0 - (CGFloat)args[1]) * b.size.height;
        NSView* hit = [cv hitTest:[cv convertPoint:NSMakePoint(vx, vy) toView:[cv superview]]];
        while (hit && ![hit isKindOfClass:[MacApkMetalView class]]) hit = [hit superview];
        if (!hit) return;
        MacApkMetalView* mv = (MacApkMetalView*)hit;
        const NSPoint p = [mv convertPoint:NSMakePoint(vx, vy) fromView:cv];
        if (kind == 1) {
            if (args.size() < 4) return;
            [mv macapkMagnifyPhase:(int)args[2] magnification:(double)args[3] at:p];
            ok = true;
            return;
        }
        if (kind == 3) {
            // A trackpad twist: a[2] phase 0|1|2, a[3] rotation in degrees (CCW +).
            if (args.size() < 4) return;
            [mv macapkRotatePhase:(int)args[2] rotation:(double)args[3] at:p];
            ok = true;
            return;
        }
        if (kind == 4) {
            // A two-finger trackpad scroll event: a[2] phase 0 began | 1 changed |
            // 2 ended, a[3] scrollingDeltaY, a[4] scrollingDeltaX, in POINTS
            // (hasPreciseScrollingDeltas), no momentum.
            if (args.size() < 5) return;
            const NSEventPhase ph = args[2] == 0 ? NSEventPhaseBegan
                                  : args[2] == 1 ? NSEventPhaseChanged : NSEventPhaseEnded;
            std::fprintf(stderr, "[wheel] harness trackpad phase=%d dy=%.2f dx=%.2f pt\n",
                         (int)args[2], args[3], args[4]);
            [mv macapkScrollAt:p dx:(CGFloat)args[4] dy:(CGFloat)args[3] precise:YES
                         phase:ph momentumPhase:NSEventPhaseNone];
            ok = true;
            return;
        }
        if (kind == 2) {
            // A classic (line) wheel notch: a[2] = scrollingDeltaY, a[3] =
            // scrollingDeltaX, in lines, no phase -- the event a mouse wheel sends.
            if (args.size() < 4) return;
            std::fprintf(stderr, "[wheel] harness notch dy=%.2f dx=%.2f lines at (%.3f,%.3f)\n",
                         args[2], args[3], args[0], args[1]);
            [mv macapkScrollAt:p dx:(CGFloat)args[3] dy:(CGFloat)args[2] precise:NO
                         phase:NSEventPhaseNone momentumPhase:NSEventPhaseNone];
            ok = true;
            return;
        }
        if (!MacapkTrackpadMultitouchOn()) return;
        enum { kMax = 10 };
        int ids[kMax], phases[kMax];
        float xs[kMax], ys[kMax];
        int c = 0;
        for (size_t i = 2; i + 3 < args.size() && c < kMax; i += 4, c++) {
            ids[c] = (int)args[i]; phases[c] = (int)args[i + 1];
            xs[c] = args[i + 2];   ys[c] = args[i + 3];
        }
        std::fprintf(stderr, "[trackpad] harness frame contacts=%d cursor-frac=(%.3f,%.3f)\n",
                     c, args[0], args[1]);
        // A 14" MacBook Pro pad, in points (147 x 91 mm).
        [mv macapkTrackpadContacts:c ids:ids phases:phases xs:xs ys:ys cursor:p
                         padPoints:NSMakeSize(147.0 * 72.0 / 25.4, 91.0 * 72.0 / 25.4)];
        ok = true;
    };
    if ([NSThread isMainThread]) body(); else dispatch_sync(dispatch_get_main_queue(), body);
    return ok;
}

void CocoaRegisterTouchDispatcher(void* java_vm, long global_dispatcher_ref) {
    g_touch_vm  = (JavaVM*) java_vm;
    g_touch_dispatcher = (jobject) global_dispatcher_ref;
    g_touch_mid = nullptr;
    std::fprintf(stderr,
        "[touch] CocoaRegisterTouchDispatcher: vm=%p dispatcher=0x%lx\n",
        (void*)java_vm, global_dispatcher_ref);
}
} // namespace macapk

// ---------------------------------------------------------------------------
// THE APP WINDOW vs SystemUI SPLIT (v1.0.996).
//
// On a real device the app's window and the navigation bar are DIFFERENT
// windows, and the app's one is smaller. Measured on the reference AVD (API 30,
// 1080x2280 @ 320dpi, `dumpsys window displays`, 2026-08-17):
//
//     init=1080x2280 320dpi cur=1080x2280 app=1080x2184
//     InsetsSource ITYPE_NAVIGATION_BAR frame=[0,2184][1080,2280] visible=true
//
// `app=` is exactly 96px (48dp) shorter than the display. The bar is never
// composited ONTO app pixels; the app is handed a surface that stops where the
// bar starts. We used to give the framework's ANGLE layer the WHOLE drawable
// and then paint the 3-button strip on the overlay CALayer above it, i.e. over
// rows a GL game had legitimately rendered (owner, 2026-08-17: "HCR 1.8 runs
// great but ... the android nav bar is showing at the bottom even though its a
// full screen app"). Full write-up:
// docs/fullscreen-game-window-geometry_2026-08-17.md
//
// So the framework angleLayer is sized to the app WINDOW, while the canvas
// overlay above it stays VIEW-sized and keeps painting the bars in display
// coordinates -- which is exactly the layering AOSP has, with SystemUI's
// windows above the app's. The two sizes were equal before this change
// (measured over 14 boots with MACAPK_TRACE_SURFACE_SIZE, 2026-07-27); from
// here they differ BY DESIGN whenever a bar is visible.
//
// The inset policy itself lives in ONE place on the Java side
// (com.macapk.loader.StatusBarDriver.contentInset{Top,Bottom}Px) because it
// depends on the app's own window flags, and ViewCanvasDriver's measure/layout
// and the GL surface-size publication read the very same accessors. This is a
// PULL, once per frame: the activity lifecycle runs on `macapk-activity` while
// this runloop is already spinning (Boot.run steps 5/6), so an app can set
// FLAG_FULLSCREEN before, during or after the first drawInMTKView, and a pull
// can never be a frame stale.
// ---------------------------------------------------------------------------
//
// v1.0.997: the navigation bar is not always at the BOTTOM. On a landscape
// phone-class display AOSP puts it on the display's RIGHT edge -- measured on
// the same AVD, rotated: `cur=1796x808 app=1700x808`, ITYPE_NAVIGATION_BAR
// frame=[1700,0][1796,808], and the app's content frame runs to the bottom
// edge. So the window box needs all FOUR edges; carrying only top/bottom is
// what made a rotated video lose 96px of scarce HEIGHT instead of 96px of
// abundant WIDTH (owner, 2026-08-19: "playtube ... covers up the video").
struct MacapkContentBox {
    CGRect  framePt;   // where the app window sits in the view's layer space
    int     wPx;       // the surface the guest is handed, in pixels
    int     hPx;
    int     topPx;     // insets actually applied (all 0 == full drawable)
    int     bottomPx;
    int     leftPx;
    int     rightPx;
};

/** One JNI static call: StatusBarDriver.macapkContentInsetsPacked4() ->
 *  (top << 48) | (bottom << 32) | (left << 16) | right, in host pixels.
 *  Returns 0 (== no insets, the pre-v1.0.996 geometry) whenever the Java side
 *  cannot be reached, so a loader/classpath problem degrades to "full
 *  drawable" rather than to a zero-sized surface. */
static jlong MacapkQueryContentInsetsPacked(JNIEnv* env) {
    if (!env) return 0;
    static jclass    s_cls   = nullptr;
    static jmethodID s_mid   = nullptr;
    static bool      s_tried = false;
    if (!s_tried) {
        s_tried = true;
        jclass c = env->FindClass("com/macapk/loader/StatusBarDriver");
        if (!c) {
            env->ExceptionClear();
            std::fprintf(stderr, "[W] cocoa_v2: StatusBarDriver not found -- "
                                 "content insets stay 0 (full drawable)\n");
        } else {
            s_mid = env->GetStaticMethodID(c, "macapkContentInsetsPacked4", "()J");
            if (!s_mid) {
                env->ExceptionClear();
                std::fprintf(stderr, "[W] cocoa_v2: StatusBarDriver."
                    "macapkContentInsetsPacked4 not found -- content insets "
                    "stay 0 (full drawable); the framework jar is older than "
                    "this runtime binary\n");
            } else {
                s_cls = (jclass) env->NewGlobalRef(c);
            }
            env->DeleteLocalRef(c);
        }
    }
    if (!s_cls || !s_mid) return 0;
    jlong v = env->CallStaticLongMethod(s_cls, s_mid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

/** The app-window rect for `view`, given the insets in host pixels.
 *
 *  ORIGIN. A CALayer hosted by a non-flipped NSView has its origin at the
 *  BOTTOM-left, so the bottom inset is the frame's y offset. MTKView does not
 *  override isFlipped, but asking the view rather than assuming costs one
 *  message send and cannot be silently wrong the day that changes.
 *
 *  FAILS SAFE to the full drawable if the arithmetic would leave nothing: a
 *  guest handed a zero-height surface runs nativeInit(w, 0), gets a zero GL
 *  viewport, and draws black at 60fps. */
static MacapkContentBox MacapkComputeContentBox(MTKView* view,
                                                int insetTopPx,
                                                int insetBottomPx,
                                                int insetLeftPx,
                                                int insetRightPx) {
    CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
    if (scale <= 0) scale = 2.0;
    int fullW = (int) std::llround(view.bounds.size.width  * scale);
    int fullH = (int) std::llround(view.bounds.size.height * scale);
    int t = insetTopPx    > 0 ? insetTopPx    : 0;
    int b = insetBottomPx > 0 ? insetBottomPx : 0;
    int l = insetLeftPx   > 0 ? insetLeftPx   : 0;
    int r = insetRightPx  > 0 ? insetRightPx  : 0;
    if (fullH <= 0 || t + b >= fullH) { t = 0; b = 0; }
    if (fullW <= 0 || l + r >= fullW) { l = 0; r = 0; }
    MacapkContentBox box;
    box.topPx    = t;
    box.bottomPx = b;
    box.leftPx   = l;
    box.rightPx  = r;
    box.wPx      = fullW - l - r;
    box.hPx      = fullH - t - b;
    CGFloat gapPt = (view.isFlipped ? t : b) / scale;
    // X is unaffected by the flip: a CALayer's x grows rightward in either
    // convention, so the frame's origin.x is the LEFT inset regardless.
    box.framePt   = CGRectMake(l / scale, gapPt,
                               view.bounds.size.width  - (l + r) / scale,
                               view.bounds.size.height - (t + b) / scale);
    return box;
}

// ---- MACAPK_SV_GL_LAYER_RECT (2026-10-01): the ANGLE layer follows a GL view --
// AOSP shows a SurfaceView's buffer AT the view's rect, cropped to the window
// (SurfaceView.updateSurface :985-988 sizes it from getWidth()/getHeight();
// mScreenRect :1040-1043 places it). Our renderers draw into the one framework
// angleLayer, which was always the window box, so a GL view LARGER than its
// window (a crop/fill resolution policy: Flappy Bird's AndEngine
// RenderSurfaceView is 1346x2392 centred at y=-298 in a 1346x1796 window) was
// handed the window box and drew its whole scene squashed into it, while its
// MotionEvents stayed view-local -- every touch landed at the wrong scene point.
// SurfaceView.macapkGlLayerRectPacked() now publishes the rect of a renderer view
// the window box cannot hold (relative to the window box, top-left origin, x/y
// signed 16-bit; 0 = keep the box), the Java side tells the renderer the view's
// size, and this moves the layer onto that rect; the host view's layer clips it
// to the window exactly as the video plane is clipped (MacapkApplyVideoLayer-
// Geometry). =0 (Java side and here) restores the window box exactly.
// Research: docs/gl-surface-larger-than-window-layer-rect_2026-10-01.md.
static bool MacapkGlLayerRectEnabled() {
    static int s_on = -1;
    if (s_on < 0) {
        const char* e = std::getenv("MACAPK_SV_GL_LAYER_RECT");
        s_on = (e && std::strcmp(e, "0") == 0) ? 0 : 1;
    }
    return s_on == 1;
}

static jlong MacapkQueryGlLayerRectPacked(JNIEnv* env) {
    if (!env) return 0;
    static jclass    s_cls   = nullptr;
    static jmethodID s_mid   = nullptr;
    static bool      s_tried = false;
    if (!s_tried) {
        s_tried = true;
        jclass c = env->FindClass("android/view/SurfaceView");
        if (!c) {
            env->ExceptionClear();
        } else {
            s_mid = env->GetStaticMethodID(c, "macapkGlLayerRectPacked", "()J");
            if (!s_mid) {
                env->ExceptionClear();
                std::fprintf(stderr, "[W] cocoa_v2: SurfaceView.macapkGlLayerRectPacked "
                    "not found -- the ANGLE layer stays the window box (the framework "
                    "jar is older than this runtime binary)\n");
            } else {
                s_cls = (jclass) env->NewGlobalRef(c);
            }
            env->DeleteLocalRef(c);
        }
    }
    if (!s_cls || !s_mid) return 0;
    jlong v = env->CallStaticLongMethod(s_cls, s_mid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

// The GL view rect the ANGLE layer was last placed on (packed as
// SurfaceView.macapkGlLayerRectPacked; 0 = the window box) and the WINDOW box
// (host layer points) that rect is cropped to. Main thread only. The per-frame
// apply below writes them; the two resize-time frame resets (drawableSizeWillChange
// and the live-resize re-fire) read them, so a window drag keeps ONE geometry
// instead of flipping between "window box" and "view rect" every delta.
static long long g_gl_rect_applied = 0;
static CGRect    g_gl_window_box_pt = CGRectZero;

/** Move `box` (the WINDOW box) onto the packed GL view rect and remember the
 *  window box as the clip. Does not touch any layer. */
static bool MacapkPlaceBoxOnGlRect(MTKView* view, MacapkContentBox* box, jlong packed) {
    const int16_t xs  = (int16_t) ((packed >> 48) & 0xffff);
    const int16_t ys  = (int16_t) ((packed >> 32) & 0xffff);
    const int     wpx = (int)     ((packed >> 16) & 0xffff);
    const int     hpx = (int)     ( packed        & 0xffff);
    if (wpx <= 0 || hpx <= 0) return false;
    CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
    if (scale <= 0) scale = 2.0;
    // Box px (top-left origin) -> host layer points. A CALayer hosted by a
    // non-flipped NSView has its origin at the BOTTOM-left; ask the view, as
    // MacapkComputeContentBox does.
    CGRect fr;
    fr.origin.x    = box->framePt.origin.x + xs / scale;
    fr.origin.y    = view.isFlipped
                         ? box->framePt.origin.y + ys / scale
                         : box->framePt.origin.y + (box->hPx - (ys + hpx)) / scale;
    fr.size.width  = wpx / scale;
    fr.size.height = hpx / scale;
    g_gl_window_box_pt = box->framePt;
    box->framePt = fr;
    box->wPx     = wpx;
    box->hPx     = hpx;
    return true;
}

/** Crop the ANGLE layer to the window box while a GL rect is applied, and drop
 *  the crop when it is not. AOSP parents a SurfaceView's surface to the WINDOW's
 *  bounds layer (SurfaceView.java :1310 setParent(viewRoot.getBoundsLayer())), so
 *  what lies outside the window -- above/below an overflowing view, and the
 *  system-bar strips MacapkComputeContentBox excludes from the box -- is never
 *  shown. masksToBounds on the host view only crops at the VIEW's bounds, which
 *  include those strips, so the clip is a mask in the layer's own space: the
 *  window box minus the layer's origin, y counted from the layer's BOTTOM edge
 *  (a CALayer's own space is y-up even under a flipped host). Cheap: it writes
 *  only when the rect changed, and never animates. Call after the layer's frame
 *  is set. */
static void MacapkSyncGlClipMask(MTKView* view, CALayer* angle) {
    if (!angle) return;
    if (g_gl_rect_applied == 0) {
        if (angle.mask) {
            std::fprintf(stderr, "[I] cocoa_v2: GL clip mask removed (layer back on the window box)\n");
            [CATransaction begin];
            [CATransaction setDisableActions:YES];
            angle.mask = nil;
            [CATransaction commit];
        }
        return;
    }
    const CGRect fr = angle.frame, wb = g_gl_window_box_pt;
    CGRect m;
    m.size     = wb.size;
    m.origin.x = wb.origin.x - fr.origin.x;
    m.origin.y = view.isFlipped
                     ? fr.size.height - ((wb.origin.y - fr.origin.y) + wb.size.height)
                     : wb.origin.y - fr.origin.y;
    CALayer* ml = angle.mask;
    if (ml && CGRectEqualToRect(ml.frame, m)) return;
    std::fprintf(stderr, "[I] cocoa_v2: GL clip mask (layer space, %s): %.1f,%.1f %.1fx%.1f pt "
                 "over the %.1fx%.1f pt layer\n", view.isFlipped ? "flipped host" : "y-up host",
                 m.origin.x, m.origin.y, m.size.width, m.size.height,
                 fr.size.width, fr.size.height);
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (!ml) {
        ml = [CALayer layer];
        ml.backgroundColor = NSColor.blackColor.CGColor;   // opaque: its alpha is the crop
    }
    ml.frame = m;
    angle.mask = ml;
    [CATransaction commit];
}

/** The resize-time sites' version of the apply: the SAME transform from the
 *  last published rect, with no JNI pull (those sites run mid-drag, inside a
 *  frame that has not asked Java yet). */
__attribute__((unused))
static void MacapkReapplyGlLayerRect(MTKView* view, MacapkContentBox* box) {
    if (g_gl_rect_applied == 0 || !MacapkGlLayerRectEnabled()
        || macapk::g_native_egl_surface_live.load(std::memory_order_relaxed)) return;
    MacapkPlaceBoxOnGlRect(view, box, (jlong) g_gl_rect_applied);
}

/** Replace the box's frame + surface size with the published GL view rect
 *  (main thread, once per frame, before the angleLayer convergence). The box's
 *  insets are left alone: they describe the WINDOW, which has not moved. */
__attribute__((unused))
static void MacapkApplyGlLayerRect(JNIEnv* env, MTKView* view, MacapkContentBox* box) {
    static long long s_last   = -1;
    static bool      s_placed = false;     // the transition, NOT the packed value's sign:
                                           // a negative x makes the packed long negative
    jlong packed = (env && MacapkGlLayerRectEnabled()) ? MacapkQueryGlLayerRectPacked(env) : 0;
    if (packed == 0 || packed == -1) {
        if (s_placed)
            std::fprintf(stderr, "[I] cocoa_v2: ANGLE layer back on the window box %dx%d\n",
                         box->wPx, box->hPx);
        s_placed = false;
        s_last = 0;
        g_gl_rect_applied = 0;
        return;
    }
    const int wpx = (int) ((packed >> 16) & 0xffff);
    const int hpx = (int) ( packed        & 0xffff);
    const int bw = box->wPx, bh = box->hPx;
    if (!MacapkPlaceBoxOnGlRect(view, box, packed)) return;
    if (s_last != (long long) packed) {
        std::fprintf(stderr, "[I] cocoa_v2: ANGLE layer follows the GL view: %d,%d %dx%d px "
                     "in the %dx%d window box (MACAPK_SV_GL_LAYER_RECT)\n",
                     (int) (int16_t) ((packed >> 48) & 0xffff), (int) (int16_t) ((packed >> 32) & 0xffff),
                     wpx, hpx, bw, bh);
        s_last = (long long) packed;
    }
    s_placed = true;
    g_gl_rect_applied = (long long) packed;
    // Crop what lies outside the window, as the device's compositor does (the
    // host view's bounds first; the layer mask, once the layer exists, at the
    // window box exactly -- MacapkSyncGlClipMask).
    if (!view.layer.masksToBounds) view.layer.masksToBounds = YES;
}

// The DRIVE TICK: one display vsync handed to everything that paces on it --
// the NDK AChoreographer (__macapk_arm64_choreographer_fire), the per-thread Java
// Choreographers (JavaVsyncFire), the Swappy bridge when installed, the FramePacer
// grid and the Vulkan present credit -- decimated by an accumulator to the
// reported refresh rate. Until 2026-09-28 this body sat inline in drawInMTKView;
// it moved into a function, unchanged, so the dedicated vsync thread below
// (MACAPK_VSYNC_THREAD) can run it too. The mutex makes the accumulator and the
// per-tag traces single-writer when both the vsync thread and the main-thread
// fallback can call it; under MACAPK_VSYNC_THREAD=0 only drawInMTKView calls it,
// on the main thread, every frame, exactly as before.
static void MacapkDriveTickAt(double dnow, double kPeriod) {
    static std::mutex* tick_mu = new std::mutex();   // leaked: ticks may outlive exit()
    std::lock_guard<std::mutex> tick_lk(*tick_mu);
    static double s_accum = 0.0, s_last = 0.0;
    if (s_last == 0.0) { s_last = dnow; s_accum = kPeriod; }  // fire on the first frame
    // Review issue 10 (minor): the main-thread fallback samples dnow via
    // CACurrentMediaTime() BEFORE it can block acquiring tick_mu above, so if the
    // vsync thread ticked (and advanced s_last) while this thread was waiting on
    // the lock, dnow can read older than s_last -> a negative delta. It already
    // telescopes to zero over the next tick (s_accum just undershoots once), but
    // clamping here makes it exact instead of approximately harmless.
    if (dnow < s_last) dnow = s_last;
    s_accum += (dnow - s_last);
    s_last = dnow;
    if (s_accum >= kPeriod) {
        s_accum -= kPeriod;                       // maintain the target average
        if (s_accum > kPeriod) s_accum = kPeriod; // clamp after a stall (no burst)
        // "drive" = the cadence actually handed to the guest (Choreographer
        // tick + Swappy present counter). This is the interval the game's
        // frame clock advances on, so it is the distribution the owner's
        // "pacing is random" complaint is about.
        if (macapk::MacapkFrameTraceOn()) {
            static macapk::MacapkPaceTrace s_drive = { "drive", 0.0, 0, {0} };
            macapk::MacapkPaceSample(&s_drive, dnow);
        }
        static void (*choreoFire)(int64_t) = (void (*)(int64_t))
            dlsym(RTLD_DEFAULT, "__macapk_arm64_choreographer_fire");
        if (choreoFire) {
            choreoFire((int64_t) clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW));
        }
        // MACAPK_CHOREO_VSYNC_THREADS (2026-09-27): the SAME tick is the vsync
        // for per-thread JAVA Choreographers (Unity's "UnityChoreographer",
        // Swappy's ChoreographerThread, ExoPlayer's frame-release thread):
        // each one that asked for a vsync (Looper.macapkScheduleVsync) gets it
        // now, stamped in the System.nanoTime() timebase, on its own Looper.
        // One relaxed atomic load when no Java Choreographer is waiting, and
        // none is under MACAPK_CHOREO_VSYNC_THREADS=0 (the Java side never
        // asks), so =0 is the old path. native_methods.cpp macapk-java-vsync.
        macapk::JavaVsyncFire();
        // v1.0.849 (MACAPK_FRAME_STATS=1): choreographer-drive rate probe.
        // This is the cadence the v568 accumulator promises is 60 Hz; an
        // engine whose sim speed follows Choreographer ticks runs exactly
        // (this rate / 60) times real speed, so logging the MEASURED rate
        // either clears this path or convicts it when a game plays fast.
        // The always-on "[I] fps:" line nearby is the DISPLAY draw rate;
        // the guest's own present rate is "[frame-stats] guest-swap:" in
        // bionic_shim. The three rates together locate a pacing bug.
        {
            static const bool fs_on = [](){
                const char* e = std::getenv("MACAPK_FRAME_STATS");
                return e && e[0] == '1';
            }();
            if (fs_on) {
                static double fs_win0 = 0.0;
                static int fs_n = 0;
                double fs_now = CACurrentMediaTime();
                if (fs_win0 == 0.0) fs_win0 = fs_now;
                fs_n++;
                if (fs_now - fs_win0 >= 5.0) {
                    std::fprintf(stderr,
                        "[frame-stats] choreo: %.1fs n=%d hz=%.1f\n",
                        fs_now - fs_win0, fs_n, fs_n / (fs_now - fs_win0));
                    std::fflush(stderr);
                    fs_win0 = fs_now; fs_n = 0;
                }
            }
        }
        // Swappy (Unity "Optimized Frame Pacing") bridge: advance Swappy's
        // presented-frame counter + broadcast its condvar once per PACED frame,
        // releasing UnityMain's render thread from the pacing wait in step with
        // the 60 Hz cadence. No-ops unless a libunity Swappy layout is detected.
        //
        // MACAPK_SWAPPY_BRIDGE (2026-09-27). Not called at all unless the
        // bridge is installed -- see MacapkSwappyBridgeEnabled for the rule.
        // The shim's own tick checks the same switch; this call-site check
        // keeps the two binaries agreeing even when only one is rebuilt.
        static void (*swappyTick)(void) = (void (*)(void))
            dlsym(RTLD_DEFAULT, "__macapk_arm64_swappy_present_tick");
        if (swappyTick && MacapkSwappyBridgeEnabled()) {
            swappyTick();
        }
        // v1.0.996 AOSP FRAME PACING -- the vsync GRID this loop defines.
        // The guest's own eglSwapBuffers blocks until the grid allows its
        // next frame (bionic_shim.cpp FramePacer), which restores the
        // whole-refresh-period cadence that a real Android window surface
        // enforces and the pbuffer decouple removed. Ticked here rather
        // than inside swappy_present_tick because THAT early-returns
        // before a SwappyCommon is discovered and whenever the published
        // one goes stale -- a grid that stops ticking would leave the
        // guest waiting on it. This call is a no-op until a Swappy guest
        // is discovered, and its wait times out regardless.
        static void (*pacingTick)(void) = (void (*)(void))
            dlsym(RTLD_DEFAULT, "__macapk_arm64_frame_pacing_tick");
        if (pacingTick) {
            pacingTick();
        }
        // v1.0.611: grant one PRESENT CREDIT per composited display frame on the
        // native-EGL Vulkan (MoltenVK) path -- the display side of the in-flight cap
        // that fixes the MCPE 54% CAMetalLayer drawable-pool wedge. The guest's
        // eglSwapBuffers (present thread) blocks until a credit is available
        // (bionic_shim.cpp PresentPacer::acquire), so it stays <= K frames ahead of
        // the display and the 3-slot pool never reaches 0 free. This tick already
        // runs once per display refresh (the choreoFire/swappyTick cadence) and keeps
        // firing during the 54% spike (proven by the stuck-run sample), so it is the
        // right credit source. Gated on the vulkan path so Crossy/Unity (ANGLE-Metal)
        // never even makes the call -> byte-identical.
        //
        // Review issue 2: EXCLUDE the decouple path here. When
        // CocoaDecoupleIsActive(), the consumer of a credit is the main-thread
        // composite in CocoaDecouplePresentTick, not this vsync -- AOSP's
        // BufferQueue throttles a producer to its CONSUMER (dequeueBuffer blocks
        // until releaseBuffer), never to the display clock. drawInMTKView grants
        // the credit itself right after that composite runs, so granting it again
        // here (now on the vsync thread, possibly while the main thread and its
        // composite are stalled) would double-credit and silently reopen the
        // backpressure the decouple path depends on. On the default window-surface
        // path (decouple inactive) CoreAnimation retires drawables at refresh, so a
        // per-vsync credit here is correct and unchanged from the vsync-thread move.
        static const bool kNativeEglVulkan = []{
            const char* e = std::getenv("ANGLE_DEFAULT_PLATFORM");
            return e && std::string(e) == "vulkan";
        }();
        if (kNativeEglVulkan && !macapk::CocoaDecoupleIsActive()) {
            static void (*presentCredit)(void) = (void (*)(void))
                dlsym(RTLD_DEFAULT, "__macapk_arm64_present_credit_release");
            if (presentCredit) presentCredit();
        }
    }
}

// MACAPK_VSYNC_THREAD (default ON; 2026-09-28, lane subway-frame-hitches): the
// display vsync on its OWN thread instead of the AppKit main thread.
//
// AOSP: vsync is produced by SurfaceFlinger (Scheduler/EventThread) and handed to
// each DisplayEventReceiver on the Looper it was created with ("@param looper The
// looper to use when invoking callbacks", frameworks/base core/java/android/view/
// DisplayEventReceiver.java:105-108 (aosp-mirror/platform_frameworks_base, branch master)). A Choreographer belongs to its own Looper
// thread, so a busy UI thread delays only the UI thread's own frame; it never
// delays another Looper's vsync, and it never delays a native AChoreographer's.
//
// Mac.apk produced every vsync inside drawInMTKView, i.e. ON the AppKit main thread,
// so ANY main-thread stall withheld vsync from every paced guest thread and the
// engine advanced the whole gap in one frame. Measured on Subway Surfers 3.62.0 in
// gameplay with MACAPK_MAIN_STALL_MS=40: JNI calls parked in
// SafepointSynchronize::block (112, 150 ms), an ad SDK's WKWebView creation and
// loadRequest (1221, 412, 116 ms), a class load (61 ms), a blocked stderr write
// (147 ms); every guest frame delta > 50 ms that lined up with one showed the
// display tick stalled by the same amount. docs/subway-frame-hitches_2026-09-28.md.
//
// macOS 14+ API, no deprecated CVDisplayLink: -[NSWindow displayLinkWithTarget:
// selector:] "Returns a new display link whose callback will be invoked in-sync
// with the display the window is on. If the window is not on any display the
// callback will not be invoked." (AppKit NSWindow.h), and -[CADisplayLink
// addToRunLoop:forMode:] "Unless paused, it will fire every vsync until removed"
// on the run loop it was added to (QuartzCore CADisplayLink.h) -- here the vsync
// thread's own. Its callback runs the SAME MacapkDriveTickAt the main thread ran
// (same accumulator, same decimation to the reported refresh rate, same stamps),
// so only the THREAD changes.
//
// Hand-over, so a stopped link degrades to the old behaviour instead of freezing
// the guest: drawInMTKView ticks itself whenever the vsync thread has not beaten
// for 3 periods (not started yet, window on no display, link not firing). The
// hidden-window throttle (MACAPK_PAUSE_WHEN_HIDDEN) applies here too. The main
// thread keeps draining the AChoreographer callbacks registered on its OWN looper
// (the fire ran those inline when it ran on the main thread). =0 restores the old
// path exactly: every tick from drawInMTKView on the main thread.
static bool MacapkVsyncThreadEnabled() {
    static const bool on = macapk::vsync_thread_env_on(std::getenv("MACAPK_VSYNC_THREAD"));
    return on;
}
static std::atomic<double> g_vsync_thread_beat{0.0};  // CACurrentMediaTime of its last callback
static std::atomic<int> g_vsync_thread_state{0};      // 0 none, 1 starting, 2 running, 3 failed
// Main-thread-set-once (MacapkVsyncThreadStart), read-and-mutated only from the
// existing main-thread "panel rate changed" branch below (never from the vsync
// thread itself, which only reads via the target's macapkVsync: callback) --
// see MacapkVsyncLinkApplyHz (review issue 6).
static CADisplayLink* g_vsync_link = nil;

// The drive rate, the way drawInMTKView computes it; callable off the main thread
// (CocoaDisplayHz serves a published value there, never NSScreen).
static double MacapkDriveHzNow() {
    static const double kOverride = [] {
        const char* e = std::getenv("MACAPK_UNITY_DRIVE_HZ");
        double v = e ? atof(e) : 0.0;
        return (v >= 1.0 && v <= 480.0) ? v : 0.0;
    }();
    return kOverride > 0.0 ? kOverride : (double)macapk::CocoaDisplayHz();
}

// Review issue 6: CADisplayLink's preferredFrameRateRange defaults to the
// display's native cadence, "best-effort" (QuartzCore CADisplayLink.h). On a
// VRR/ProMotion panel the system may run the link BELOW MacapkDriveHzNow()
// (adaptive lowering, Low Power Mode), which would hand the guest fewer
// vsyncs than the refresh rate it is told via android.view.Display.
// getRefreshRate() -- the v1.0.568 bug in reverse. Pin it explicitly, same as
// MTKView.preferredFramesPerSecond already is for the main-thread source.
// Unverified on an actual VRR panel (this host is a fixed-rate 60 Hz LCD plus
// a 1080p external); harmless no-op below macOS 12 (guarded by the
// respondsToSelector check at the one call site that creates the link).
static void MacapkVsyncLinkApplyHz(CADisplayLink* link, double hz) {
    if (!link || hz < 1.0 || hz > 480.0) return;
    if ([link respondsToSelector:@selector(setPreferredFrameRateRange:)]) {
        link.preferredFrameRateRange = CAFrameRateRangeMake((float)hz, (float)hz, (float)hz);
    }
}

@interface MacApkVsyncTarget : NSObject
- (void)macapkVsync:(CADisplayLink*)link;
@end
@implementation MacApkVsyncTarget
- (void)macapkVsync:(CADisplayLink*)link {
    (void)link;
    const double now = CACurrentMediaTime();
    // Alive even while throttled, so the main thread does not take over.
    g_vsync_thread_beat.store(now, std::memory_order_release);
    if (macapk::MacapkPauseWhenHiddenEnabled()
        && macapk::g_window_hidden.load(std::memory_order_relaxed)) {
        static double s_hidden_last = 0.0;             // this thread only
        if (s_hidden_last != 0.0
            && (now - s_hidden_last) < 1.0 / macapk::MacapkHiddenThrottleHz())
            return;
        s_hidden_last = now;
    }
    MacapkDriveTickAt(now, 1.0 / MacapkDriveHzNow());
}
@end

// Main thread only (it reads the NSWindow). Starts at most once per process.
static void MacapkVsyncThreadStart(NSWindow* win) {
    int expect = 0;
    if (!g_vsync_thread_state.compare_exchange_strong(expect, 1)) return;
    CADisplayLink* link = nil;
    if ([win respondsToSelector:@selector(displayLinkWithTarget:selector:)]) {
        // MRC: the target lives as long as the process (the link retains it too).
        MacApkVsyncTarget* target = [[MacApkVsyncTarget alloc] init];
        link = [[win displayLinkWithTarget:target selector:@selector(macapkVsync:)] retain];
    }
    if (!link) {
        g_vsync_thread_state.store(3);
        std::fprintf(stderr, "[vsync] MACAPK_VSYNC_THREAD: no window display link; vsync "
                     "stays on the main thread\n");
        std::fflush(stderr);
        return;
    }
    MacapkVsyncLinkApplyHz(link, MacapkDriveHzNow());
    g_vsync_link = link;   // main thread only past this point (see g_vsync_link's comment)
    std::thread([link]() {
        pthread_setname_np("macapk-vsync");
        // SurfaceFlinger's vsync threads run at real-time priority; the closest
        // unprivileged macOS equivalent is the user-interactive QoS class.
        pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
        @autoreleasepool {
            [link addToRunLoop:[NSRunLoop currentRunLoop] forMode:NSRunLoopCommonModes];
            // Review issue 5: -[NSRunLoop runMode:beforeDate:] returns NO (immediately)
            // once the run loop has no input source or timer left in the mode. That is
            // only the display link today, so if it is ever invalidated or removed
            // (its window deallocated, the display detached) this loop would busy-spin
            // at QOS_CLASS_USER_INTERACTIVE instead of blocking. A standing Mach port
            // source keeps the run loop always non-empty, so runMode: always blocks
            // until the link fires (or forever, harmlessly, if it never does again).
            [[NSRunLoop currentRunLoop] addPort:[NSMachPort port] forMode:NSDefaultRunLoopMode];
        }
        g_vsync_thread_state.store(2);
        std::fprintf(stderr, "[vsync] display vsync now ticks on its own thread "
                     "(MACAPK_VSYNC_THREAD; =0 ticks on the AppKit main thread)\n");
        std::fflush(stderr);
        for (;;) {
            @autoreleasepool {
                [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                         beforeDate:[NSDate distantFuture]];
            }
        }
    }).detach();
}

// drawInMTKView: true = the vsync thread is ticking, so this frame must NOT tick
// (it drains its own looper's AChoreographer callbacks instead); false = tick here.
// Starts the vsync thread on the first call that has a window.
static bool MacapkVsyncThreadOwnsTick(MTKView* view, double dnow, double kPeriod) {
    if (!MacapkVsyncThreadEnabled()) return false;
    // Without the shim's drain the main thread's own AChoreographer callbacks would
    // never run once the fire moved off it: keep the old path with an older shim.
    static void (*drain)(void) = (void (*)(void))
        dlsym(RTLD_DEFAULT, "__macapk_arm64_choreographer_run_ready");
    if (!drain) return false;
    if (g_vsync_thread_state.load(std::memory_order_relaxed) == 0 && view.window)
        MacapkVsyncThreadStart(view.window);
    // The decision itself is the pure function the offline gate exercises.
    return macapk::vsync_thread_owns_tick(true, drain != nullptr,
        g_vsync_thread_beat.load(std::memory_order_acquire), dnow, kPeriod);
}

// The inline half of __macapk_arm64_choreographer_fire, for the main thread's own
// looper, when the fire itself ran on the vsync thread.
static void MacapkMainLooperChoreoDrain() {
    static void (*drain)(void) = (void (*)(void))
        dlsym(RTLD_DEFAULT, "__macapk_arm64_choreographer_run_ready");
    if (drain) drain();
}

// ---- MTKView delegate that calls back into Java per-frame ----
//
// Defined OUTSIDE the macapk namespace because Objective-C
// classes are global. The implementation reaches back into the
// namespace via free functions when needed.

@interface MacApkV2RenderDelegate : NSObject <MTKViewDelegate> {
@public
    JavaVM* javaVm;
    jobject globalGlSurfaceViewRef;
    jmethodID drawFrameMid;
    jmethodID surfaceChangedMid;
    int  lastDrawableW;
    int  lastDrawableH;
    // v1.0.503: last surface px we actually pushed via macapkSurfaceChanged.
    // Drives the live-resize re-render: when the drawable px differs from this
    // and we're not mid-drag, re-render at the new size (see drawInMTKView).
    int  sentSurfaceW;
    int  sentSurfaceH;
    int  resizeHoldFrames;     // v1.0.1637: frames a resize re-fire has waited for the display commit
    BOOL surfaceCreatedFired;
    BOOL angleInitTried;       // Whether we've attempted AngleInit
    BOOL angleInitOk;          // Whether AngleInit succeeded
    // v1.0.996: the app-window insets currently applied to angleLayer, in host
    // px. Cached on the delegate so drawableSizeWillChange -- which has no
    // JNIEnv, being a plain AppKit callback -- can keep the live-drag scale
    // matched to the WINDOW rect instead of snapping it back to view.bounds.
    int  appliedInsetTopPx;
    int  appliedInsetBottomPx;
    int  appliedInsetLeftPx;
    int  appliedInsetRightPx;
}
@property (nonatomic, strong) CAMetalLayer* angleLayer; // Dedicated sublayer ANGLE renders into
@end

namespace macapk {
// v1.0.1637: tell the Java side the PRIMARY window's render surface is now
// w x h px -- the display-size signal for DisplayConfig (Stage 1 of
// docs/window-resize-and-cross-thread-surface_2026-09-23.md). Called just
// before each macapkSurfaceChanged fire below, so the commit it posts to the
// owner loop is queued ahead of the view tree's doSurfaceChanged. Only the
// placeholder window is the display: any other window (a dialog's own
// NSWindow) must not resize the device. Main thread only (drawInMTKView).
// RETURNS true when the display is committed at w x h (or when this view is
// not the display / the Java side is unavailable), false while the commit is
// still queued: the RESIZE re-fire below waits for true so engines hear
// surfaceChanged after onConfigurationChanged, AOSP's order (see
// DisplayConfig.macapkNoteSurfaceSize).
static bool MacapkNoteDisplaySize(JNIEnv* env, MTKView* view, int w, int h) {
    if (!env || !view || view.window == nil || view.window != g_placeholder_window) return true;
    static jclass    s_cls    = nullptr;
    static jmethodID s_mid    = nullptr;
    static bool      s_failed = false;
    if (!s_cls) {
        if (s_failed) return true;
        jclass c = env->FindClass("com/macapk/loader/DisplayConfig");
        if (!c) {
            env->ExceptionClear();
            s_failed = true;
            std::fprintf(stderr, "[W] display: DisplayConfig not found -- "
                                 "Configuration will not follow a resize\n");
            return true;
        }
        s_mid = env->GetStaticMethodID(c, "macapkNoteSurfaceSize", "(II)Z");
        if (!s_mid) {
            env->ExceptionClear();
            env->DeleteLocalRef(c);
            s_failed = true;
            std::fprintf(stderr, "[W] display: DisplayConfig.macapkNoteSurfaceSize "
                "missing -- the loader jar is older than this runtime binary\n");
            return true;
        }
        s_cls = (jclass) env->NewGlobalRef(c);
        env->DeleteLocalRef(c);
    }
    const jboolean committed = env->CallStaticBooleanMethod(s_cls, s_mid, (jint) w, (jint) h);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return true; }
    return committed == JNI_TRUE;
}
}  // namespace macapk

@implementation MacApkV2RenderDelegate

- (void)mtkView:(MTKView*)view drawableSizeWillChange:(CGSize)size {
    lastDrawableW = (int)size.width;
    lastDrawableH = (int)size.height;
    // v1.0.503: keep the render sublayers' GEOMETRY (frame) matched to the view
    // so a live drag SCALES the last frame smoothly -- uniform, hence
    // stretch-free, because the window is aspect-locked (contentAspectRatio).
    // The actual re-render at the new resolution (ANGLE backbuffer resize +
    // macapkSurfaceChanged re-fire so the Canvas frame buffer reallocs + the
    // View tree re-lays-out, and GL/Unity re-query) is driven from
    // drawInMTKView gated on !inLiveResize -- so it lands ONCE the drag ends and
    // for any discrete resize (zoom button, full-screen toggle, programmatic).
    // We deliberately do NOT touch drawableSize / call AngleResize here: that
    // would re-render every drag delta and thrash a full relayout per frame.
    // v1.0.996: the app-window rect, not the raw bounds -- the angleLayer holds
    // the GUEST's surface, which excludes the system-bar regions. Using
    // view.bounds here would stretch the last frame over the nav strip for the
    // duration of a drag and then snap back on the next drawInMTKView.
    if (self.angleLayer) {
        MacapkContentBox wsBox = MacapkComputeContentBox(
            view, appliedInsetTopPx, appliedInsetBottomPx,
            appliedInsetLeftPx, appliedInsetRightPx);
        // MACAPK_SV_GL_LAYER_RECT: while a GL view's rect is applied the layer
        // follows THAT (same transform drawInMTKView applies), not the window
        // box, so a drag does not flip the layer between the two geometries.
        MacapkReapplyGlLayerRect(view, &wsBox);
        self.angleLayer.frame = wsBox.framePt;
        MacapkSyncGlClipMask(view, self.angleLayer);
    }
    if (macapk::g_unity_layer && macapk::g_unity_host_view
        && !macapk::macapk_place_scaled_native_egl_layer(
               macapk::g_unity_layer, macapk::g_unity_host_view.bounds,
               view.window ? view.window.backingScaleFactor : 2.0))
        macapk::g_unity_layer.frame = macapk::g_unity_host_view.bounds;
}

- (void)drawInMTKView:(MTKView*)view {
    // See g_host_draw_depth / CocoaHostRenderTick.
    struct HostDrawDepth {
        HostDrawDepth() { ++macapk::g_host_draw_depth; }
        ~HostDrawDepth() { --macapk::g_host_draw_depth; }
    } hostDrawDepth;
    // v1.0.572: record the actively-drawing MTKView (main thread). Only a
    // window-attached view draws, so this is the view an on-demand
    // native-EGL layer must attach to (CocoaEnsureNativeEglLayer) -- NOT
    // g_last_metal_view, which tracks the last-CREATED canvas view and can
    // point at a view the window rewire just detached.
    // The canvas overlay's fallback host is this view (see
    // macapk::g_canvas_host_epoch); a change is rare, a same-view tick is free.
    macapk::MacapkWindowTreeCountTick(view);   // MACAPK_WINDOW_TREE_DUMP (diagnostic)
    if (macapk::g_active_render_view != view)
        macapk::g_canvas_host_epoch.fetch_add(1, std::memory_order_acq_rel);
    macapk::g_active_render_view = view;

    // Stamped ABOVE the hidden-throttle gate below (review issue 8): while the
    // window is hidden this function still runs every real frame up to this
    // point, just at the 2 Hz throttle past it, so a beat taken after the gate
    // logged a false "[main-stall]" (plus a stack) every ~500 ms whenever
    // MACAPK_MAIN_STALL_MS was set and the window was merely backgrounded, not
    // actually stalled. No-op unless that env var is set (see the threshold
    // check inside).
    macapk::MacapkMainStallBeat(CACurrentMediaTime());

    // MACAPK_PAUSE_WHEN_HIDDEN: while the window is minimized/fully occluded,
    // skip this tick's present work entirely except at a low throttle rate
    // (default 2 Hz) -- see the full design comment on g_window_hidden near
    // the top of this file. g_active_render_view is deliberately set ABOVE
    // this gate: other threads (e.g. CocoaEnsureNativeEglLayer, called from
    // UnityMain / an AGDK surfaceCreated) read it expecting the latest
    // attached view regardless of host-window visibility. Everything below
    // this point -- the decouple present, the Choreographer/Swappy pump, the
    // ANGLE begin/end frame, and the macapkDrawFrame JNI call -- is the
    // expensive per-frame work real Android's compositor would skip for an
    // occluded surface, so it is what gets throttled.
    if (macapk::MacapkPauseWhenHiddenEnabled()
        && macapk::g_window_hidden.load(std::memory_order_relaxed)) {
        double nowHidden = CACurrentMediaTime();
        double period = 1.0 / macapk::MacapkHiddenThrottleHz();
        if (macapk::g_hidden_throttle_last != 0.0
            && (nowHidden - macapk::g_hidden_throttle_last) < period) {
            return;   // not due yet; no state touched below this point
        }
        macapk::g_hidden_throttle_last = nowHidden;
    }

    // v1.0.611: present the decoupled guest frame (native-EGL Vulkan path). No-op
    // unless CocoaDecoupleActivate ran -> ANGLE-Metal (Crossy/Unity) unaffected.
    macapk::CocoaDecouplePresentTick(view);
    // Review issue 2: on the decouple path the consumer of a present credit IS
    // this compositing step, not a vsync -- AOSP's BufferQueue throttles the
    // producer to the CONSUMER (dequeueBuffer blocks until releaseBuffer), not
    // to the display clock. Grant the credit here, right after the composite
    // that actually consumed a slot, so a stalled main thread (no composite)
    // withholds credit exactly as a stalled real consumer would. When decouple
    // is not active this is a no-op (CocoaDecoupleIsActive() false) and credit
    // keeps coming from MacapkDriveTickAt on the vsync thread, once per vsync,
    // which matches the default window-surface path's CoreAnimation-retires-
    // at-refresh consumer instead.
    if (macapk::CocoaDecoupleIsActive() != 0) {
        static void (*presentCreditDC)(void) = (void (*)(void))
            dlsym(RTLD_DEFAULT, "__macapk_arm64_present_credit_release");
        if (presentCreditDC) presentCreditDC();
    }
    // v1.0.835: serve retained last-frame snapshots + finish a stranded layer
    // readback. This tick is the ONLY capture path that runs when the guest draws
    // nothing at all, which is what makes an idle Canvas menu capturable. Placed
    // above the early-out-free part of the body deliberately: it must run on every
    // tick that gets here, including the 2 Hz throttled ticks while hidden.
    macapk::SelfCapturePollFromMainThread();
    // One-shot canary: tells us the MTKView display link actually
    // started firing. If this never appears in the log, either the
    // view isn't attached to a window, the runloop is starved, or
    // the delegate wasn't installed -- all distinct from "fired but
    // returned early because of missing renderer ref".
    static dispatch_once_t firstFrameToken;
    dispatch_once(&firstFrameToken, ^{
        std::fprintf(stderr,
            "[I] cocoa_v2: drawInMTKView first fired "
            "(javaVm=%p ref=%p paused=%d bounds=%.0fx%.0f drawable=%.0fx%.0f)\n",
            (void*)javaVm, (void*)globalGlSurfaceViewRef,
            (int)view.paused,
            view.bounds.size.width,  view.bounds.size.height,
            view.drawableSize.width, view.drawableSize.height);
    });

    // v1.0.174: drive Unity's per-frame render loop. Unity (libunity) renders
    // via Android's AChoreographer: each frame it registers a single-shot
    // frame callback that posts nativeRender to UnityMain's Handler and
    // re-registers itself. The NDK AChoreographer_postFrameCallback* shims
    // record those callbacks; fire them here, once per MTKView display frame,
    // with a monotonic frame time (ns). Without this, Unity rendered exactly
    // one frame then stalled (engine + surface live but the first scene never
    // pumped to activation => black). Gated on Unity apps; for cocos/AndEngine
    // the queue is empty so it's a no-op. The shim lives in the bionic-shim
    // dylib, reached via dlsym(RTLD_DEFAULT).
    // v1.0.605: fire the AChoreographer frame callbacks each display tick for ANY
    // app that posts them, NOT just Unity. This block was Unity-gated, which
    // STARVED AGDK GameActivity apps (Minecraft Bedrock / RenderDragon) of vsync:
    // MCPE posts a frame callback via AChoreographer_postFrameCallback then blocks
    // android_main in ALooper_pollOnce waiting for it -- but choreographer_fire
    // (which moves the pending callback to ready + wakes the poll so the frame runs)
    // was NEVER called for non-Unity apps -> android_main blocked forever ->
    // RenderDragon never rendered a single frame -> BLACK menu. This was the real
    // multi-session black-render root cause. Verified via the emulator oracle: MCPE
    // renders fine on the SAME ANGLE-Vulkan-SwiftShader stack, so the gap was purely
    // this Mac.apk vsync-gating, not MoltenVK/the driver/the GLES version.
    // choreographer_fire is a safe no-op when a looper has no pending callbacks
    // (cocos2d-x / AndEngine / libGDX drive their own loops), and swappy_present_tick
    // no-ops unless a libunity Swappy layout is detected, so firing this for all apps
    // is engine-agnostic (no per-package branch).
    {
        // AOSP FRAME-PACING CADENCE (v1.0.568). Drive the per-frame loop
        // (Choreographer tick + Swappy present counter) at the REPORTED display
        // refresh rate (android.view.Display.getRefreshRate(), = CocoaDisplayHz()), NOT the raw
        // MTKView present rate. On this host the MTKView display link free-runs at
        // ~74/s (preferredFramesPerSecond=120 above the 60 Hz panel; MTKView does
        // NOT clamp to the panel as the old comment assumed). Ticking Unity at 74/s
        // makes it advance its Choreographer/Swappy frame clock ~1.2x faster than
        // the 60 Hz vsync Android guarantees -> the simulation runs too fast (owner:
        // "gameplay too fast"). An ACCUMULATOR gate emits exactly one tick per
        // 1/refreshRate of real time (60/s average), so Unity renders at its target
        // rate with a correct per-frame delta; the extra Metal presents just re-show
        // the last Unity frame (correct game speed, no content judder). MACAPK_UNITY_
        // DRIVE_HZ overrides the cadence for diagnosis.
        // v1.0.1102: the cadence is the PANEL rate (capped at MACAPK_MAX_FPS, default 120),
        // read from the one function that also feeds MTKView.preferredFramesPerSecond and
        // android.view.Display.getRefreshRate() -- see MacapkPanelHz. It is re-read each frame
        // rather than latched once, so dragging the window to a different monitor changes the
        // cadence with it (the panel re-check below updates the published value). On a 60 Hz
        // panel this evaluates to exactly the old hardcoded 60, so that host is unchanged.
        // MACAPK_UNITY_DRIVE_HZ still force-overrides for diagnosis (it must then match what
        // the guest is told, or game speed breaks -- v1.0.568).
        static const double kDriveHzOverride = []{
            const char* e = std::getenv("MACAPK_UNITY_DRIVE_HZ");
            double v = e ? atof(e) : 0.0; return (v >= 1.0 && v <= 480.0) ? v : 0.0;
        }();
        const double kDriveHz = kDriveHzOverride > 0.0
                              ? kDriveHzOverride
                              : (double)macapk::CocoaDisplayHz();
        const double kPeriod = 1.0 / kDriveHz;
        double dnow = CACurrentMediaTime();
        // Re-apply the panel-matched present rate periodically so a display
        // change while running (window dragged to an external monitor, or a
        // mode switch) is picked up. Cheap: once per ~2 s, and only writes the
        // property when the measured rate actually changed.
        if (macapk::MacapkVsyncMatchPanel()) {
            static int s_panel_tick = 0;
            if ((++s_panel_tick % 120) == 0) {
                int hz = macapk::MacapkPanelHz(view);
                if (view.preferredFramesPerSecond != hz) {
                    std::fprintf(stderr, "[vsync] panel rate changed %ld -> %d Hz; "
                                 "re-matching present cadence\n",
                                 (long)view.preferredFramesPerSecond, hz);
                    std::fflush(stderr);
                    view.preferredFramesPerSecond = hz;
                    // Review issue 6: re-pin the vsync-thread's CADisplayLink to the
                    // same measured rate, the same cadence this branch already keeps
                    // view.preferredFramesPerSecond matched to.
                    MacapkVsyncLinkApplyHz(g_vsync_link, (double)hz);
                }
            }
        }
        // "display" = the raw MTKView display-link interval (the SOURCE rate).
        if (macapk::MacapkFrameTraceOn()) {
            static macapk::MacapkPaceTrace s_disp = { "display", 0.0, 0, {0} };
            macapk::MacapkPaceSample(&s_disp, dnow);
        }
        // MACAPK_VSYNC_THREAD (default ON): the display vsync comes from its own
        // thread (MacapkVsyncThread*, above), so THIS thread stalling -- a JNI call
        // parked on a JVM safepoint, an ad SDK creating a WKWebView, a class load --
        // no longer withholds vsync from the guest. The main thread only drains the
        // AChoreographer callbacks registered on its OWN looper, which the fire ran
        // inline when it ran here. It falls back to ticking here itself while the
        // vsync thread is not firing (not started yet, or its window is on no
        // display), and =0 is the old path: every tick from here.
        if (MacapkVsyncThreadOwnsTick(view, dnow, kPeriod)) {
            MacapkMainLooperChoreoDrain();
        } else {
            MacapkDriveTickAt(dnow, kPeriod);
        }
    }

    // Lightweight FPS counter: log once per 5s. Useful for spotting
    // regressions; cheap enough to leave on permanently.
    static double fpsWindowStart = 0.0;
    static int    fpsFrameCount  = 0;
    double now = CACurrentMediaTime();
    // Per-frame dt trace (MACAPK_FRAME_DT): the 5s-averaged fps line hides
    // single-frame hitches (one dropped frame barely moves a 300-frame mean),
    // so it cannot confirm the v607 mutex-fairness stutter fix. This logs any
    // frame whose delta exceeds a threshold (default 20ms ~= a dropped 60fps
    // frame) plus a 5s worst-dt summary, so we can SEE hitches directly.
    // Env-gated; zero cost when unset.
    {
        static int dt_on = -1;
        if (dt_on < 0) { const char* e = std::getenv("MACAPK_FRAME_DT");
                         dt_on = e ? 1 : 0; }
        if (dt_on == 1) {
            static double dt_prev = 0.0, dt_win = 0.0, dt_worst = 0.0;
            static int dt_hitches = 0, dt_frames = 0;
            const char* te = std::getenv("MACAPK_FRAME_DT_MS");
            double thresh = (te && *te) ? std::atof(te) / 1000.0 : 0.020;
            if (dt_prev > 0.0) {
                double dt = now - dt_prev;
                dt_frames++;
                if (dt > dt_worst) dt_worst = dt;
                if (dt > thresh) {
                    dt_hitches++;
                    std::fprintf(stderr, "[frame-dt] hitch dt=%.1fms\n", dt * 1000.0);
                }
                if (dt_win == 0.0) dt_win = now;
                if (now - dt_win >= 5.0) {
                    std::fprintf(stderr,
                        "[frame-dt] 5s: frames=%d hitches(>%.0fms)=%d worst=%.1fms\n",
                        dt_frames, thresh * 1000.0, dt_hitches, dt_worst * 1000.0);
                    dt_frames = 0; dt_hitches = 0; dt_worst = 0.0; dt_win = now;
                }
                std::fflush(stderr);
            }
            dt_prev = now;
        }
    }
    if (fpsWindowStart == 0.0) fpsWindowStart = now;
    fpsFrameCount++;
    if (now - fpsWindowStart >= 5.0) {
        std::fprintf(stderr, "[I] fps: %.1f\n",
                     fpsFrameCount / (now - fpsWindowStart));
        std::fflush(stderr);  // ensure visibility when log is redirected
        fpsFrameCount  = 0;
        fpsWindowStart = now;
    }
    // Diagnostic: log a heartbeat every 60 frames (~1s @ 60fps) for
    // the first 10 emissions, then stop. Tells us whether the
    // render loop is actually ticking even if axmol's fps reporter
    // is silent. Cheap and self-limiting.
    static int hbCount = 0;
    if ((fpsFrameCount % 60) == 0 && hbCount < 10) {
        std::fprintf(stderr, "[I] frame heartbeat #%d frame=%d\n",
                     hbCount, fpsFrameCount);
        std::fflush(stderr);
        ++hbCount;
    }
    // MACAPK_DUMP_DIRECTOR (v1.0.59 HCR 1.43 CCDirector 256-byte dump) RETIRED:
    // read-only, default-OFF; the LLVM-lifter bug it was written for was fixed at
    // v1.0.63-65 and that lifter was removed at v1.0.802. Guest word watch:
    // MACAPK_POLL_GUEST. See docs/cocos-ccdirector-memdump-retired_2026-09-27.md.

    // Tick-driven autoclick firing. Uses elapsed time since the first
    // drawInMTKView so MACAPK_DEBUG_AUTOCLICK timestamps line up with
    // observable frame events (300 frames @ 60fps ≈ 5s). Reliable
    // regardless of GCD main-queue load.
    if (!macapk::g_autoclicks.empty()) {
        static double tickBase = 0.0;
        if (tickBase == 0.0) tickBase = now;
        // MACAPK_DEBUG_TAP_HOLD: split the down and up across frames so a
        // Unity uGUI button registers the click (an instant same-frame
        // down+up does not). The env VALUE is the hold duration in seconds
        // (default 0.12 if set to a non-number); a long hold lets a capture
        // catch the button's pressed/highlight state to verify coordinates.
        static double tapHoldSecs = []() -> double {
            const char* e = std::getenv("MACAPK_DEBUG_TAP_HOLD");
            if (!e) return 0.0;
            double v = std::atof(e);
            return v > 0.0 ? v : 0.12;
        }();
        static bool tapHold = tapHoldSecs > 0.0;
        double elapsed = now - tickBase;
        MacApkMetalView* mv = [view isKindOfClass:[MacApkMetalView class]]
                                ? (MacApkMetalView*)view : nil;
        for (auto& click : macapk::g_autoclicks) {
            if (!mv) break;
            CGFloat cx = (click.x >= 0.0) ? click.x : view.bounds.size.width  * 0.5;
            CGFloat cy = (click.y >= 0.0) ? click.y : view.bounds.size.height * 0.5;
            if (!click.fired && elapsed >= click.target_t) {
                click.fired = true;
                if (tapHold) {
                    [mv debugSyntheticTouchAction:0 atViewX:cx Y:cy];  // down
                    click.x = cx; click.y = cy; click.up_t = elapsed + tapHoldSecs;
                } else {
                    [mv debugSyntheticTapAtViewX:cx Y:cy];             // down+up
                    click.up_done = true;
                }
            } else if (tapHold && click.fired && !click.up_done
                       && elapsed >= click.up_t) {
                click.up_done = true;
                [mv debugSyntheticTouchAction:1 atViewX:click.x Y:click.y];  // up
            }
        }
    }

    // MACAPK_DEBUG_HOSTKEY_FILE: host-keystroke harness (debug/keyshortcut_live.sh).
    if ([view isKindOfClass:[MacApkMetalView class]])
        MacApkDebugHostKeyFile((MacApkMetalView*)view, now);

    // Tick-driven autokey firing (MACAPK_DEBUG_AUTOKEY), mirrors autoclick.
    if (!macapk::g_autokeys.empty()) {
        static double keyTickBase = 0.0;
        if (keyTickBase == 0.0) keyTickBase = now;
        double elapsed = now - keyTickBase;
        for (auto& k : macapk::g_autokeys) {
            if (k.fired || elapsed < k.target_t) continue;
            k.fired = true;
            SEL sel = @selector(debugSyntheticKey:unicode:);
            if ([view respondsToSelector:sel]) {
                NSMethodSignature* sig = [view methodSignatureForSelector:sel];
                NSInvocation* inv = [NSInvocation invocationWithMethodSignature:sig];
                [inv setTarget:view];
                [inv setSelector:sel];
                int kc = k.keycode, uni = k.unicode;
                [inv setArgument:&kc atIndex:2];
                [inv setArgument:&uni atIndex:3];
                [inv invoke];
            }
        }
    }

    // v1.0.504: gated auto-rotate self-test (MACAPK_DEBUG_AUTOROTATE=<secs>[,<secs>...]).
    // The rotate analogue of MACAPK_DEBUG_AUTOCLICK: fires CocoaRotateRender()
    // once at each elapsed time since the first drawInMTKView, so Cmd+R rotation
    // can be verified headlessly (a synthesized Cmd+R can't reach a non-frontmost
    // custom-NSApplication Metal window). Default OFF -> zero impact.
    {
        static std::vector<double> rotTimes = []() -> std::vector<double> {
            std::vector<double> v;
            if (const char* e = std::getenv("MACAPK_DEBUG_AUTOROTATE")) {
                const char* p = e; char* end = nullptr;
                while (*p) {
                    while (*p == ' ' || *p == ',' || *p == ';') ++p;
                    if (!*p) break;
                    double t = std::strtod(p, &end);
                    if (end == p) break;
                    p = end;
                    if (t >= 0.0) v.push_back(t);
                }
            }
            return v;
        }();
        static std::vector<char> rotFired(rotTimes.size(), 0);
        if (!rotTimes.empty()) {
            static double rotBase = 0.0;
            if (rotBase == 0.0) rotBase = now;
            double elapsed = now - rotBase;
            for (size_t i = 0; i < rotTimes.size(); ++i) {
                if (!rotFired[i] && elapsed >= rotTimes[i]) {
                    rotFired[i] = 1;
                    macapk::CocoaRotateRenderSelfTest();
                }
            }
        }
    }

    // v1.0.1637: gated auto-RESIZE self-test, the resize analogue of the block
    // above. MACAPK_DEBUG_AUTORESIZE=<secs>:<scale>[,<secs>:<scale>...] sets the
    // primary window's content to <scale> x its size at the first frame, at
    // each elapsed time. Both axes are scaled by the same factor, which is what
    // keeps the shape: setFrame:display:animate: is NOT constrained by
    // contentAspectRatio / contentMinSize (those govern USER resizes), so a
    // scale below the minimum is applied as asked. A programmatic
    // setFrame is not a live drag, so it takes the same !inLiveResize re-fire
    // path a drag END does -- which is what DisplayConfig's commit hangs off.
    // v1.0.1649 (phase 2a): it is also NOT one of our own frame changes, so the
    // window delegate's windowDidResize treats it exactly like a window-manager
    // resize (tiling, Fill, AX) -> winpolicy::ApplyUserResize: the self-test
    // exercises the product path, not a test-only call.
    // A step may also name an absolute content size, <secs>:<W>x<H> (points),
    // which does NOT keep the shape -- a tile onto the other side, the case the
    // C1 side hold exists for (winpolicy::ResolveHoldingSide).
    // Default OFF -> zero impact.
    {
        struct ResizeStep { double t; double scale; double w; double h; bool fired; };
        static std::vector<ResizeStep> rzSteps = []() -> std::vector<ResizeStep> {
            std::vector<ResizeStep> v;
            if (const char* e = std::getenv("MACAPK_DEBUG_AUTORESIZE")) {
                const char* p = e; char* end = nullptr;
                while (*p) {
                    while (*p == ' ' || *p == ',' || *p == ';') ++p;
                    if (!*p) break;
                    double t = std::strtod(p, &end);
                    if (end == p) break;
                    p = end;
                    double sc = 1.0, aw = 0.0, ah = 0.0;
                    if (*p == ':') {
                        ++p;
                        sc = std::strtod(p, &end);
                        if (end == p) break;
                        p = end;
                        if (*p == 'x') {   // <W>x<H>: an absolute content size
                            ++p;
                            ah = std::strtod(p, &end);
                            if (end == p) break;
                            p = end;
                            aw = sc;
                            sc = 1.0;
                        }
                    }
                    if (aw > 0.0) {
                        if (t >= 0.0 && aw >= 100.0 && ah >= 100.0 && aw <= 8192.0 && ah <= 8192.0)
                            v.push_back({t, 1.0, aw, ah, false});
                    } else if (t >= 0.0 && sc > 0.05 && sc < 4.0) {
                        v.push_back({t, sc, 0.0, 0.0, false});
                    }
                }
            }
            return v;
        }();
        if (!rzSteps.empty() && view.window == macapk::g_placeholder_window) {
            static double rzBase = 0.0;
            static NSSize rzBaseContent = NSZeroSize;
            if (rzBase == 0.0) {
                rzBase = now;
                rzBaseContent = [view.window contentRectForFrameRect:view.window.frame].size;
            }
            const double elapsed = now - rzBase;
            for (ResizeStep& st : rzSteps) {
                if (st.fired || elapsed < st.t) continue;
                st.fired = true;
                NSWindow* w = view.window;
                if (macapk::window_is_fullscreen()) {
                    std::fprintf(stderr, "[I] cocoa_v2: autoresize x%.2f skipped: the window is "
                                 "fullscreen\n", st.scale);
                    continue;
                }
                const NSRect fr = w.frame;
                const NSSize want = st.w > 0.0
                    ? NSMakeSize(std::round(st.w), std::round(st.h))
                    : NSMakeSize(std::round(rzBaseContent.width  * st.scale),
                                 std::round(rzBaseContent.height * st.scale));
                NSRect nf = [w frameRectForContentRect:NSMakeRect(0, 0, want.width, want.height)];
                // Keep the top-left corner where it is, as a corner drag would.
                nf.origin.x = fr.origin.x;
                nf.origin.y = fr.origin.y + fr.size.height - nf.size.height;
                [w setFrame:nf display:YES animate:NO];
                const NSSize got = [w contentRectForFrameRect:w.frame].size;
                if (st.w > 0.0)
                    std::fprintf(stderr, "[I] cocoa_v2: autoresize to %.0fx%.0f -> content %.0fx%.0f pt\n",
                                 want.width, want.height, got.width, got.height);
                else
                    std::fprintf(stderr, "[I] cocoa_v2: autoresize x%.2f -> content %.0fx%.0f pt\n",
                                 st.scale, got.width, got.height);
            }
        }
    }

    // v1.0.1649 (phase 2a): gated auto-FULLSCREEN self-test, the fullscreen
    // analogue of the two blocks above. MACAPK_DEBUG_AUTOFULLSCREEN=<secs>[,<secs>...]
    // toggles native fullscreen (the green button's toggleFullScreen:) at each
    // elapsed time, so the fullscreen transitions can be verified headlessly
    // (debug/winpolicy_live.sh --fullscreen). OPT-IN ONLY: a programmatic
    // toggle switches Spaces on the owner's desktop. Default OFF -> zero impact.
    {
        static std::vector<double> fsTimes = []() -> std::vector<double> {
            std::vector<double> v;
            if (const char* e = std::getenv("MACAPK_DEBUG_AUTOFULLSCREEN")) {
                const char* p = e; char* end = nullptr;
                while (*p) {
                    while (*p == ' ' || *p == ',' || *p == ';') ++p;
                    if (!*p) break;
                    double t = std::strtod(p, &end);
                    if (end == p) break;
                    p = end;
                    if (t >= 0.0) v.push_back(t);
                }
            }
            return v;
        }();
        static std::vector<char> fsFired(fsTimes.size(), 0);
        if (!fsTimes.empty() && view.window == macapk::g_placeholder_window) {
            static double fsBase = 0.0;
            if (fsBase == 0.0) fsBase = now;
            const double elapsed = now - fsBase;
            for (size_t i = 0; i < fsTimes.size(); ++i) {
                if (fsFired[i] || elapsed < fsTimes[i]) continue;
                fsFired[i] = 1;
                std::fprintf(stderr, "[I] cocoa_v2: autofullscreen toggle at %.1fs (%s)\n", elapsed,
                             macapk::window_is_fullscreen() ? "leaving" : "entering");
                [view.window toggleFullScreen:nil];
            }
        }
    }

    if (!javaVm || !globalGlSurfaceViewRef) return;
    JNIEnv* env = nullptr;
    jint r = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        if (javaVm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    } else if (r != JNI_OK || !env) {
        return;
    }

#ifdef MACAPK_HAS_ANGLE
    // v1.0.996: pull the app-window insets for THIS frame (see the
    // MacapkComputeContentBox header). Done before the layer exists so the very
    // first surface ANGLE is given is already window-shaped -- there is no
    // "frame 1 at full size then shrink", which would have flashed the guest's
    // content 96px low for one frame (GL's origin is bottom-left).
    const jlong macapkInsetsPacked = MacapkQueryContentInsetsPacked(env);
    const int macapkInsetTopPx    = (int) ((macapkInsetsPacked >> 48) & 0xffff);
    const int macapkInsetBottomPx = (int) ((macapkInsetsPacked >> 32) & 0xffff);
    const int macapkInsetLeftPx   = (int) ((macapkInsetsPacked >> 16) & 0xffff);
    const int macapkInsetRightPx  = (int) ( macapkInsetsPacked        & 0xffff);
    MacapkContentBox macapkBox =
        MacapkComputeContentBox(view, macapkInsetTopPx, macapkInsetBottomPx,
                                macapkInsetLeftPx, macapkInsetRightPx);
    appliedInsetTopPx    = macapkBox.topPx;
    appliedInsetBottomPx = macapkBox.bottomPx;
    appliedInsetLeftPx   = macapkBox.leftPx;
    appliedInsetRightPx  = macapkBox.rightPx;

    // v1.0.997: log the window box whenever it CHANGES, including the first
    // time. The convergence check further down only prints when it has to
    // CORRECT a stale drawable, so on a boot where the layer is created at the
    // right size the very first time -- the common case for a fixed canvas --
    // nothing was ever printed and there was no cheap falsifier for "which edge
    // did the navigation bar come off". Four ints, at most one line per geometry
    // change, and it is the primary evidence line for the landscape nav-bar fix.
    {
        static int s_lastW = -1, s_lastH = -1;
        static int s_lastT = -1, s_lastB = -1, s_lastL = -1, s_lastR = -1;
        if (macapkBox.wPx != s_lastW || macapkBox.hPx != s_lastH
            || macapkBox.topPx  != s_lastT || macapkBox.bottomPx != s_lastB
            || macapkBox.leftPx != s_lastL || macapkBox.rightPx  != s_lastR) {
            s_lastW = macapkBox.wPx; s_lastH = macapkBox.hPx;
            s_lastT = macapkBox.topPx;  s_lastB = macapkBox.bottomPx;
            s_lastL = macapkBox.leftPx; s_lastR = macapkBox.rightPx;
            CGFloat lgScale = view.window ? view.window.backingScaleFactor : 2.0;
            if (lgScale <= 0) lgScale = 2.0;
            std::fprintf(stderr,
                "[I] cocoa_v2: app-window box display=%dx%d -> window=%dx%d px "
                "(insets top=%d bottom=%d left=%d right=%d)\n",
                (int) std::llround(view.bounds.size.width  * lgScale),
                (int) std::llround(view.bounds.size.height * lgScale),
                macapkBox.wPx, macapkBox.hPx,
                macapkBox.topPx, macapkBox.bottomPx,
                macapkBox.leftPx, macapkBox.rightPx);
        }
    }

    // MACAPK_SV_GL_LAYER_RECT: a renderer GL view the window box cannot hold
    // (larger than the window, or off its GL corner) moves the layer onto its
    // own rect. After the window-box log above, so that line keeps describing
    // the WINDOW. Not while a native-EGL engine owns rendering (its own layer).
    if (!macapk::g_native_egl_surface_live.load(std::memory_order_relaxed))
        MacapkApplyGlLayerRect(env, view, &macapkBox);

    // v1.0.996: keep the video plane glued to its sink SurfaceView's rect
    // even when no NEW video frame arrives -- a paused video must scroll away
    // with the player UI, and a layout change must move it. One static JNI
    // call per frame, and only while a video layer exists at all (the common
    // no-video app pays a nil check). Present-time geometry is handled inside
    // CocoaPresentVideoFrame; this covers the frames in between.
    if (macapk::g_video_layer && macapk::g_video_attached_view == view)
        macapk::MacapkApplyVideoLayerGeometry(env, view);
    // 2026-09-26: the per-surface layers (CocoaPresentSurfaceBitmapFrom) follow
    // their views between frames too; one nil/count check when there are none.
    macapk::MacapkApplySurfaceLayersGeometry(env);
    // The native-EGL window layer's side of the window (AOSP SurfaceView z-order).
    macapk::MacapkSyncGuestLayerZ(env);

    // ANGLE-driven path: ANGLE owns its own drawables on a SUBLAYER
    // sitting on top of MTKView's own layer. This is critical -- if we
    // gave ANGLE the MTKView's layer directly, MTKView's display loop
    // would still present its own (empty) drawable on top of ANGLE's
    // output every frame, leaving the screen black.
    if (!self.angleLayer) {
        CAMetalLayer* layer = [CAMetalLayer layer];
        layer.device         = view.device;
        layer.pixelFormat    = MTLPixelFormatBGRA8Unorm;
        layer.framebufferOnly = YES;
        CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
        layer.contentsScale  = scale;
        // v1.0.996: the APP WINDOW rect, not the whole view. This layer carries
        // the GUEST's rendering, and on a real device the guest's window
        // excludes the visible system bars -- the canvas overlay above this
        // layer stays view-sized and paints them. See MacapkComputeContentBox.
        layer.frame          = macapkBox.framePt;
        // drawableSize is in PIXELS, not points. Without this set, ANGLE
        // renders into a 0x0 surface and nothing reaches the screen.
        //
        // ROUND, do not truncate: an aspect-fit container used to give a
        // FRACTIONAL bounds height (fitting 1680x930 into width 1622 yielded
        // 1622 * 930/1680 = 897.8929 pt, so height * scale = 1795.7857), and
        // rounding matches what MTKView does with its own drawable.
        //
        // HISTORY, because two superseded diagnoses were written here and both
        // read as live instructions to the next session. Neither holds:
        //
        //   * "the layer truncates to 1795 while MTKView rounds to 1796, so a
        //     phantom resize fires every boot" -- FIXED at v1.0.781, which
        //     rounds the aspect-fit rect itself (MacApkAspectFitRect) so the
        //     bounds are whole points and no fractional size exists.
        //   * "the real mismatch is 48 px: the tracker is seeded from the
        //     status-bar-inset angleLayer (1748) but checked against the full
        //     view drawable (1796)" -- NOT TRUE AS DIAGNOSED. Measured
        //     2026-07-27 with MACAPK_TRACE_SURFACE_SIZE over 14 boots:
        //     angleLayer.drawableSize and view.drawableSize BOTH read
        //     3244x1796, and the live-resize branch fired ZERO times.
        //
        // So there is no phantom resize left to kill. The hcr181 "bimodal
        // render" that motivated both notes was a MEASUREMENT artifact in the
        // capture harness, not a geometry race; see debug/capture_wait.sh.
        //
        // ⚠ UPDATED v1.0.996: the two sizes now differ BY DESIGN whenever a
        // system bar is visible -- angleLayer.drawableSize is the APP WINDOW
        // (3244x1700 for hcr181, nav bar reserved) and view.drawableSize is the
        // DISPLAY (3244x1796). Do NOT read a difference in the SFTRACE line as
        // the bug the note above refuted; that bug was about the SEED for the
        // live-resize tracker, which is now taken from view.drawableSize
        // explicitly for exactly this reason. The 1748 the old note mentions
        // was, and still is, the Java view tree's own inset layout reaching
        // cocos2d-x through onSizeChanged -- a separate, correct path.
        layer.drawableSize   = CGSizeMake(macapkBox.wPx, macapkBox.hPx);
        // Suppress implicit animations on resize.
        layer.actions = @{
            @"contents": [NSNull null],
            @"bounds":   [NSNull null],
            @"position": [NSNull null]
        };
        [view.layer addSublayer:layer];
        self.angleLayer = layer;
        std::fprintf(stderr,
            "[I] cocoa_v2: ANGLE sublayer added bounds=%dx%d drawable=%dx%d "
            "scale=%.1f window-insets top=%d bottom=%d frame=(%.0f,%.0f %.0fx%.0f)\n",
                     (int) view.bounds.size.width, (int) view.bounds.size.height,
                     (int) layer.drawableSize.width, (int) layer.drawableSize.height,
                     (double) scale,
                     macapkBox.topPx, macapkBox.bottomPx,
                     (double) layer.frame.origin.x, (double) layer.frame.origin.y,
                     (double) layer.frame.size.width, (double) layer.frame.size.height);
        // v1.0.162: for Unity apps, also create a dedicated opaque layer
        // ON TOP for Unity's own EGL window surface (see g_unity_layer
        // comment). Done here (main thread, first frame) before the
        // surfaceCreated callback blocks the main thread, so it's ready
        // when UnityMain calls ANativeWindow_fromSurface.
        //
        // MACAPK_SV_LAYER_AT_CREATE (default ON) retires this engine-keyed
        // pre-create: every app now gets its layer at surface creation
        // (MacapkSvLayerAtSurfaceCreate, below), so this block runs only under
        // =0, where the condition is exactly the pre-change one.
        if (macapk::sv_unity_precreate_wanted(macapk::MacapkSvLayerAtCreateOn(),
                macapk::g_unity_app.load(std::memory_order_relaxed),
                macapk::g_unity_layer != nil)) {
            CAMetalLayer* ul = [CAMetalLayer layer];
            ul.device          = view.device;
            ul.pixelFormat     = MTLPixelFormatBGRA8Unorm;
            ul.framebufferOnly = YES;
            ul.opaque          = YES;
            ul.contentsScale   = scale;
            ul.frame           = view.bounds;
            ul.drawableSize    = CGSizeMake(std::round(view.bounds.size.width  * scale),
                                            std::round(view.bounds.size.height * scale));
            ul.actions = @{ @"contents": [NSNull null],
                            @"bounds":   [NSNull null],
                            @"position": [NSNull null] };
            [view.layer addSublayer:ul];   // added after angleLayer => on top
            // v1.0.174: unambiguously stack Unity's layer above the
            // framework's angleLayer (zPosition 0) + the canvas overlay
            // (zPosition 1), and HIDE the framework's angleLayer for Unity
            // apps. Unity renders + presents g_unity_layer directly on its
            // own (UnityMain) thread; the framework's drawInMTKView otherwise
            // keeps presenting an EMPTY angleLayer over the same bounds every
            // vsync on the main thread, which composited on top of / fought
            // Unity's content -> black screen (Unity ran 60fps but invisible).
            // angleLayer is the cocos/canvas render target, unused by Unity,
            // so hiding it for a Unity app is safe. Engine-gated (g_unity_app),
            // no per-APK code.
            ul.zPosition = macapk::macapk_guest_layer_z_now(view.layer);
            self.angleLayer.hidden = YES;
            macapk::g_unity_layer = ul;
            macapk::g_unity_host_view = view;
            std::fprintf(stderr, "[I] cocoa_v2: Unity render layer created %dx%d (opaque, zPos=%g, angleLayer hidden)\n",
                         (int) ul.drawableSize.width, (int) ul.drawableSize.height, (double) ul.zPosition);
        }
    }
    // v1.0.996: keep the layer matched to the CURRENT app-window rect. The
    // insets can move after the layer exists -- an app may set FLAG_FULLSCREEN
    // or hide/show the nav bar at runtime (Frozen Bubble's
    // HomeScreen.setWindowLayout does exactly that), and the window is
    // resizeable -- so this is a convergence, not a one-shot. Compares the
    // DRAWABLE, which is the load-bearing quantity: ANGLE's window surface
    // tracks the layer's drawableSize, so a stale one leaves the guest drawing
    // into a backbuffer of the wrong height. Frame is re-asserted alongside it
    // because drawableSizeWillChange can have moved it mid-drag.
    if (self.angleLayer) {
        CGSize haveDs = self.angleLayer.drawableSize;
        if ((int) haveDs.width  != macapkBox.wPx ||
            (int) haveDs.height != macapkBox.hPx ||
            !CGRectEqualToRect(self.angleLayer.frame, macapkBox.framePt)) {
            self.angleLayer.frame        = macapkBox.framePt;
            self.angleLayer.drawableSize = CGSizeMake(macapkBox.wPx, macapkBox.hPx);
            // Same native-EGL guard the live-resize block carries (v1.0.573):
            // once a guest engine owns rendering, do NOT re-make-current the
            // framework context -- that re-strands a command buffer on the
            // shared per-display Metal FIFO. Those engines are immersive
            // anyway, so their insets are 0 and this branch never fires.
            if (angleInitOk
                && !macapk::g_native_egl_surface_live.load(std::memory_order_relaxed))
                macapk::AngleResize(macapkBox.wPx, macapkBox.hPx);
            if ((int) haveDs.width != macapkBox.wPx
                || (int) haveDs.height != macapkBox.hPx) {
                std::fprintf(stderr,
                    "[I] cocoa_v2: app-window surface %dx%d -> %dx%d px "
                    "(insets top=%d bottom=%d left=%d right=%d)\n",
                    (int) haveDs.width, (int) haveDs.height,
                    macapkBox.wPx, macapkBox.hPx,
                    macapkBox.topPx, macapkBox.bottomPx,
                    macapkBox.leftPx, macapkBox.rightPx);
            }
        }
    }
    // MACAPK_SV_GL_LAYER_RECT: crop the (possibly moved) layer to the window box.
    MacapkSyncGlClipMask(view, self.angleLayer);

    // v1.0.572: generic native-EGL angleLayer suppression. Track the live
    // angleLayer so CocoaNoteNativeEglSurfaceLive can hide it from any
    // thread, and self-heal each frame: once a guest EGL window surface is
    // live, the framework angleLayer must STAY hidden (a delegate created
    // after an activity swap starts with a fresh, visible angleLayer).
    // Engines that render through angleLayer never set the flag.
    macapk::g_active_angle_layer = self.angleLayer;
    if (macapk::g_native_egl_surface_live.load(std::memory_order_relaxed)
        && self.angleLayer && !self.angleLayer.hidden) {
        self.angleLayer.hidden = YES;
    }
    // v1.0.485 (item 3b): gated on-screen VIDEO self-test. Proves the full
    // decode -> CVPixelBuffer -> MTLTexture -> CAMetalLayer present pipeline
    // VISUALLY with a synthetic clip (MACAPK_VIDEO_SELFTEST=<path>), since no
    // movie-texture app is in the protected sweep set. A dedicated opaque video
    // CAMetalLayer (zPos 3, on top) is created once; each frame decodes the next
    // AU and presents it, looping the clip. Default OFF -> zero impact. This is
    // the same proven g_unity_layer present model, exercising the real
    // macapk_vd_* path the NDK AMediaCodec video wiring will use.
    {
        static const char* vst_path = std::getenv("MACAPK_VIDEO_SELFTEST");
        if (vst_path && vst_path[0]) {
            static CAMetalLayer* g_video_layer = nil;
            static void* g_vdec = nullptr;
            static void* g_vpres = nullptr;
            static bool  g_vinit = false;
            if (!g_video_layer) {
                CGFloat vscale = view.window ? view.window.backingScaleFactor : 2.0;
                CAMetalLayer* vl = [CAMetalLayer layer];
                vl.device          = view.device;
                vl.pixelFormat     = MTLPixelFormatBGRA8Unorm;
                vl.framebufferOnly  = YES;
                vl.opaque          = YES;
                vl.contentsScale   = vscale;
                vl.frame           = view.bounds;
                vl.drawableSize    = CGSizeMake(std::round(view.bounds.size.width  * vscale),
                                                std::round(view.bounds.size.height * vscale));
                vl.actions = @{ @"contents": [NSNull null], @"bounds": [NSNull null], @"position": [NSNull null] };
                vl.zPosition = 3.0;   // above angleLayer(0)/canvas(1)/unity(2)
                [view.layer addSublayer:vl];
                g_video_layer = vl;
                // Make the video the visible FOREGROUND (the real movie-surface
                // model): hide the framework's angleLayer, exactly as the Unity
                // path does for g_unity_layer -- otherwise a still-visible opaque
                // angleLayer fights/wins the composite.
                self.angleLayer.hidden = YES;
                std::fprintf(stderr, "[I] cocoa_v2: VIDEO self-test layer created %dx%d (zPos=3, angleLayer hidden) for %s\n",
                             (int) vl.drawableSize.width, (int) vl.drawableSize.height, vst_path);
            }
            if (!g_vinit) {
                g_vinit = true;
                g_vpres = macapk::macapk_vd_present_create((__bridge void*)view.device);
                g_vdec  = macapk::macapk_vd_open(vst_path);
            }
            if (g_vpres && g_vdec) {
                int64_t pts = -1;
                void* f = macapk::macapk_vd_next_frame(g_vdec, &pts);
                if (!f) {                                    // loop the clip at EOS
                    macapk::macapk_vd_close(g_vdec);
                    g_vdec = macapk::macapk_vd_open(vst_path);
                    f = g_vdec ? macapk::macapk_vd_next_frame(g_vdec, &pts) : nullptr;
                }
                if (f) {
                    macapk::macapk_vd_present_to_layer(g_vpres, (__bridge void*)g_video_layer, f);
                    macapk::macapk_vd_release_frame(f);
                }
            }
        }
    }
    // video_toolbox.mm's own self-test (item: real MediaCodec video decode).
    // Same gating shape as MACAPK_VIDEO_SELFTEST just above, but drives the
    // ACTUAL android.media.MediaCodec-facing pipeline end to end -- open a
    // decoder from real csd, feed real Annex-B access units, poll for
    // output, and render each frame through CocoaPresentVideoFrame (which
    // this MTKView's own video layer -- created lazily by that function, not
    // here -- receives) -- rather than the older file-demux macapk_vd_* path
    // exercised above. Default off, zero cost when unset.
    {
        static const char* vtbst_path = std::getenv("MACAPK_VIDEO_TOOLBOX_SELFTEST");
        if (vtbst_path && vtbst_path[0]) {
            const char* vtbst_mime = std::getenv("MACAPK_VIDEO_TOOLBOX_SELFTEST_MIME");
            macapk::macapk_vtb_selftest_tick(
                (vtbst_mime && vtbst_mime[0]) ? vtbst_mime : "video/avc", vtbst_path);
        }
    }
    if (!angleInitTried) {
        CGSize sz = self.angleLayer.drawableSize;
        if (sz.width > 0 && sz.height > 0) {
            angleInitTried = YES;
            angleInitOk = macapk::AngleInit((__bridge void*)self.angleLayer,
                                             (int)sz.width, (int)sz.height);
            if (!angleInitOk) {
                std::fprintf(stderr, "[E] cocoa_v2: AngleInit failed -- frames will no-op\n");
            }
        }
    }
    // v1.0.573: once a native-EGL engine (Unity / bgfx-RenderDragon / custom)
    // owns rendering, STOP driving the framework's OWN ANGLE context here. The
    // engine renders + presents its own CAMetalLayer (the framework angleLayer
    // is already hidden), so AngleBeginFrame/EndFrame are not merely redundant:
    // making the framework context (ctx 0x1) current + presenting on the SHARED
    // ANGLE-Metal command queue every vsync contends with the engine's context
    // (ctx 0x2) on the engine's own render thread. Minecraft's bgfx render
    // thread DEADLOCKED exactly here -- a glTexImage2D upload's ANGLE
    // ensureResourceReadyForCPUAccess -> -[_MTLCommandBuffer waitUntilCompleted]
    // never completed while the main thread kept making ctx 0x1 current 60x/s
    // (verified via `sample`: framework AngleBeginFrame at #15600 while the
    // render thread sat forever in waitUntilCompleted). Skipping the framework
    // present frees the engine's command buffers to complete. cocos/canvas/
    // AndEngine apps never set this flag (they render THROUGH angleLayer), so
    // they keep the framework present unchanged.
    // v1.0.573: capture the native-EGL state ONCE per frame. The flag can flip
    // true on the engine's render thread AT ANY point during this drawInMTKView
    // call; reading it separately at begin and end let it flip BETWEEN them --
    // AngleBeginFrame would start a Metal command buffer and the end-gate would
    // then skip AngleEndFrame, leaving that buffer UNCOMMITTED at the front of
    // ANGLE-Metal's per-display shared FIFO -> the engine's next texture-upload
    // ensureResourceReadyForCPUAccess -> waitUntilCompleted blocks on it forever
    // (Minecraft's bgfx render thread hung exactly there, intermittently). A
    // single per-frame capture + "if we began, we ALWAYS end (commit)" makes the
    // transition frame self-consistent, so no buffer is ever stranded.
    bool beganAngleFrame = false;
    if (angleInitOk) {
        // v1.0.1079, GATED (MACAPK_FRAMEWORK_PRESENT_WHEN_GUEST_IDLE=1):
        // "a guest EGL surface is LIVE" is not the same claim as "the guest is
        // RENDERING". The skip below assumes it is, and for AGDK/Unity engines
        // that holds. For an app that merely creates a GLSurfaceView while its
        // real UI is drawn by ViewCanvasDriver, the surface goes live, we stop
        // presenting the framework's own content, and NOTHING reaches the
        // screen -- the window shows our clear colour and nothing else.
        //
        // MEASURED on Plague Inc (com.miniclip.plagueinc): ANGLE 3 frames total
        // vs ViewCanvasDriver 3,600+ presents at nonzero=5826224/5826224 (100%).
        // The canvas self-capture is RICH (78,144 colours, 90.2%) and shows the
        // real menu; the on-screen window is flat #016984, OUR default clear.
        // Three layer-visibility theories were REFUTED before reaching here
        // (MACAPK_NO_OFFMAIN_EGL_LAYER, the idle-hide in the reassert guard --
        // which is opt-in and never ran -- and MACAPK_KEEP_ANGLE_LAYER); all
        // three were byte-identical, because the content was never PRESENTED at
        // all, not merely covered.
        //
        // So: keep skipping while the guest is actually producing frames on its
        // own layer, and fall back to the framework present when it is idle.
        // g_guest_layer_last_present is stamped in the nextDrawable swizzle, so
        // this keys on real production, never on which app it is.
        bool guest_owns = macapk::g_native_egl_surface_live.load(std::memory_order_relaxed);
        {
            static const bool kIdleFallback =
                [] { const char* e = std::getenv("MACAPK_FRAMEWORK_PRESENT_WHEN_GUEST_IDLE");
                     return e && *e == '1'; }();
            if (guest_owns && kIdleFallback) {
                const double last =
                    macapk::g_guest_layer_last_present.load(std::memory_order_relaxed);
                const double age = CACurrentMediaTime() - last;
                // last == 0 -> the guest has NEVER presented on its own layer.
                if (last == 0.0 || age > 2.0) {
                    guest_owns = false;
                    static bool said = false;
                    if (!said) {
                        said = true;
                        std::fprintf(stderr,
                            "[present] guest EGL surface is live but IDLE (%.1fs) -- "
                            "resuming the framework present so canvas content is "
                            "visible (MACAPK_FRAMEWORK_PRESENT_WHEN_GUEST_IDLE)\n",
                            last == 0.0 ? -1.0 : age);
                        std::fflush(stderr);
                    }
                }
            }
        }
        if (guest_owns) {
            // A native-EGL engine now owns rendering. Skip the framework present
            // (its context contends with the engine's on the SHARED per-display
            // Metal command queue), but FIRST -- exactly once, on this (main)
            // thread where the framework context is current -- fully drain it:
            // glFinish commits+completes any outstanding framework buffer and
            // pops it off the shared FIFO, then the context is released so it
            // opens no further buffers. Belt-and-suspenders with the
            // beganAngleFrame commit below.
            static dispatch_once_t s_drainOnce;
            dispatch_once(&s_drainOnce, ^{
                macapk::AngleFinish();
                std::fprintf(stderr,
                    "[I] cocoa_v2: drained framework ctx before going idle "
                    "(native engine owns the shared Metal queue now)\n");
            });
        } else {
            macapk::AngleBeginFrame();
            beganAngleFrame = true;
        }
    }
#endif

    macapk::MacapkSvLayerTick(view);   // MACAPK_SV_LAYER_AT_CREATE geometry sync
    if (!surfaceCreatedFired) {
        if (!surfaceChangedMid) {
            jclass cls = env->GetObjectClass(globalGlSurfaceViewRef);
            surfaceChangedMid = env->GetMethodID(cls, "macapkSurfaceChanged", "(II)V");
            env->DeleteLocalRef(cls);
        }
        if (surfaceChangedMid) {
            // v1.0.996: this publication is the DISPLAY surface, so it is taken
            // from the VIEW's drawable and never from the angleLayer.
            //
            // It used to prefer angleLayer.drawableSize, because on the very
            // first frame view.drawableSize can read 0x0 (MTKView allocates its
            // framebuffer drawable lazily on the first present, while we pin the
            // layer's size in pixels above). That preference was harmless only
            // while the two were equal. They no longer are: the angleLayer is
            // now the APP WINDOW and this value feeds display-space consumers --
            // ViewCanvasDriver's overlay (which paints the system bars),
            // holder.fireChanged, the WindowManager overlay bounds, and the
            // live-resize tracker seeded below, which is compared against
            // view.drawableSize. Seeding that tracker from a smaller quantity
            // would make it believe the window had been resized on frame 2 and
            // re-fire a surface change every boot.
            //
            // The 0x0 first-frame case is covered by the same bounds*scale
            // fallback as before -- which is exactly the value the box was
            // derived from, so the two cannot disagree.
            int w_px = 0, h_px = 0;
            {
                // llround, NOT (int). This is the site that publishes the
                // guest's first surface size; truncating a fractional
                // bounds*scale here would publish a size one pixel off the
                // real drawable and re-create the phantom "window was resized"
                // fire that v1.0.781 removed at the source
                // (MacApkAspectFitRect now rounds, so bounds are whole points
                // and nothing fractional reaches here -- this is the belt to
                // that braces).
                CGSize sz = view.drawableSize;
                if (sz.width == 0 || sz.height == 0) {
                    CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
                    w_px = (int)std::llround(view.bounds.size.width  * scale);
                    h_px = (int)std::llround(view.bounds.size.height * scale);
                } else {
                    w_px = (int)std::llround(sz.width);
                    h_px = (int)std::llround(sz.height);
                }
            }
            // Defer the firstSurfaceChanged until we have a non-zero
            // size; at boot the placeholder window is always valid so
            // this loop converges within one frame.
            if (w_px > 0 && h_px > 0) {
                if (macapk::SurfaceSizeTraceOn()) {
                    CGSize alds = self.angleLayer ? self.angleLayer.drawableSize : CGSizeZero;
                    CGSize vds  = view.drawableSize;
                    CGFloat sc  = view.window ? view.window.backingScaleFactor : 0.0;
                    std::fprintf(stderr,
                        "[SFTRACE] t=%.4f thread=%s FIRST macapkSurfaceChanged "
                        "w_px=%d h_px=%d angleLayer.drawableSize=%.0fx%.0f "
                        "view.drawableSize=%.0fx%.0f view.bounds=%.0fx%.0f "
                        "backingScale=%.1f windowFrame=%.0fx%.0f phone=%dx%d\n",
                        CACurrentMediaTime(),
                        [NSThread isMainThread] ? "cocoa-main" : "other",
                        w_px, h_px, alds.width, alds.height,
                        vds.width, vds.height,
                        view.bounds.size.width, view.bounds.size.height,
                        (double)sc,
                        view.window ? view.window.frame.size.width  : -1.0,
                        view.window ? view.window.frame.size.height : -1.0,
                        macapk::CocoaPhoneWidth(), macapk::CocoaPhoneHeight());
                }
                // MACAPK_SV_LAYER_AT_CREATE: the window exists before the app is
                // told about it (AOSP SurfaceView.updateSurface order).
                macapk::MacapkSvLayerAtSurfaceCreate(view);
                (void) macapk::MacapkNoteDisplaySize(env, view, w_px, h_px);   // v1.0.1637: boot fire never waits
                env->CallVoidMethod(globalGlSurfaceViewRef, surfaceChangedMid,
                                    static_cast<jint>(w_px),
                                    static_cast<jint>(h_px));
                if (env->ExceptionCheck()) env->ExceptionClear();
                surfaceCreatedFired = YES;
                sentSurfaceW = w_px;   // v1.0.503: seed the live-resize tracker
                sentSurfaceH = h_px;
            }
        }
    }

    // v1.0.503: LIVE ASPECT-RESIZE re-render. After the first surface is up,
    // when the MTKView's drawable px has changed (the window was resized) and we
    // are NOT mid-drag, re-render at the new resolution: resize ANGLE's
    // backbuffer + re-fire macapkSurfaceChanged so the Canvas frame buffer
    // reallocs + the View tree re-lays-out (ViewCanvasDriver.macapkSurfaceChanged)
    // and GL/Unity re-query the surface. During a live drag we skip this -- the
    // sublayers scale the last frame (uniform, hence stretch-free, since the
    // window is aspect-locked) -- and the crisp re-render lands once inLiveResize
    // flips false at drag end (and for any discrete resize: zoom / full-screen /
    // programmatic). The boot path is unaffected: at boot the size matches what
    // the first fire seeded, so curW/curH == sentSurfaceW/H and nothing re-fires.
    // v1.0.1649 (phase 2a): nor during a fullscreen transition. The render
    // view animates through sizes nobody asked for there (the window grows or
    // shrinks around the live layout), and inLiveResize is not known to be
    // true during that animation; the display commit lands once, when the
    // transition has placed the render view at its final box.
    if (surfaceCreatedFired && surfaceChangedMid && !view.inLiveResize
        && !macapk::g_fs_transition) {
        int curW = 0, curH = 0;
        CGSize ds = view.drawableSize;
        if (ds.width > 0 && ds.height > 0) {
            curW = (int)ds.width; curH = (int)ds.height;
        } else {
            CGFloat scale = view.window ? view.window.backingScaleFactor : 2.0;
            curW = (int)(view.bounds.size.width  * scale);
            curH = (int)(view.bounds.size.height * scale);
        }
        // v1.0.1637: hold a RESIZE re-fire until DisplayConfig has committed
        // the new display (Resources/DisplayMetrics updated, onConfigurationChanged
        // delivered), so an engine hears surfaceChanged after the configuration,
        // AOSP's order. Bounded: past kMaxResizeHold frames (~0.5 s at 60 Hz) it
        // fires anyway, so a busy or wedged Android main thread can delay a
        // resize but never withhold it.
        static const int kMaxResizeHold = 30;
        const bool sizeChanged = curW > 0 && curH > 0 &&
            (curW != sentSurfaceW || curH != sentSurfaceH);
        bool fireResize = false;
        if (sizeChanged) {
            if (macapk::MacapkNoteDisplaySize(env, view, curW, curH)) {
                fireResize = true;
            } else if (++resizeHoldFrames > kMaxResizeHold) {
                std::fprintf(stderr, "[W] cocoa_v2: display commit for %dx%d not applied "
                             "after %d frames; firing surfaceChanged anyway\n",
                             curW, curH, kMaxResizeHold);
                fireResize = true;
            }
        }
        if (fireResize) {
            resizeHoldFrames = 0;
#ifdef MACAPK_HAS_ANGLE
            if (self.angleLayer) {
                // v1.0.996: the APP WINDOW inside the new drawable, not the raw
                // bounds -- otherwise a window resize silently reverted the
                // layer to full-display and put the nav strip back on top of
                // the guest's pixels until the next frame's convergence.
                // Recomputed here because view.bounds has changed since
                // macapkBox was taken at the top of this frame.
                MacapkContentBox rzBox = MacapkComputeContentBox(
                    view, macapkInsetTopPx, macapkInsetBottomPx,
                    macapkInsetLeftPx, macapkInsetRightPx);
                // MACAPK_SV_GL_LAYER_RECT: the same rect-following transform as
                // the per-frame apply (see drawableSizeWillChange).
                MacapkReapplyGlLayerRect(view, &rzBox);
                self.angleLayer.frame        = rzBox.framePt;
                self.angleLayer.drawableSize = CGSizeMake(rzBox.wPx, rzBox.hPx);
                MacapkSyncGlClipMask(view, self.angleLayer);
                appliedInsetTopPx    = rzBox.topPx;
                appliedInsetBottomPx = rzBox.bottomPx;
                appliedInsetLeftPx   = rzBox.leftPx;
                appliedInsetRightPx  = rzBox.rightPx;
                // v1.0.573: don't re-make-current + resize the framework
                // context once a native-EGL engine owns rendering -- that
                // would re-open (and re-strand) a command buffer on the shared
                // per-display Metal FIFO (the exact deadlock AngleFinish just
                // cleared). The engine resizes its own surface via the
                // g_unity_layer update below + macapkSurfaceChanged.
                if (angleInitOk
                    && !macapk::g_native_egl_surface_live.load(std::memory_order_relaxed))
                    macapk::AngleResize(rzBox.wPx, rzBox.hPx);
            }
#endif
            if (macapk::g_unity_layer
                && !(macapk::g_unity_host_view
                     && macapk::macapk_place_scaled_native_egl_layer(
                            macapk::g_unity_layer, macapk::g_unity_host_view.bounds,
                            view.window ? view.window.backingScaleFactor : 2.0))) {
                macapk::g_unity_layer.drawableSize = CGSizeMake(curW, curH);
                if (macapk::g_unity_host_view)
                    macapk::g_unity_layer.frame = macapk::g_unity_host_view.bounds;
            }
            env->CallVoidMethod(globalGlSurfaceViewRef, surfaceChangedMid,
                                static_cast<jint>(curW),
                                static_cast<jint>(curH));
            if (env->ExceptionCheck()) env->ExceptionClear();
            sentSurfaceW = curW; sentSurfaceH = curH;
            std::fprintf(stderr,
                "[I] cocoa_v2: live-resize re-render -> %dx%d px\n", curW, curH);
        }
    }

    if (!drawFrameMid) {
        jclass cls = env->GetObjectClass(globalGlSurfaceViewRef);
        drawFrameMid = env->GetMethodID(cls, "macapkDrawFrame", "()V");
        env->DeleteLocalRef(cls);
    }
    if (drawFrameMid) {
        env->CallVoidMethod(globalGlSurfaceViewRef, drawFrameMid);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

#ifdef MACAPK_HAS_ANGLE
    // v1.0.573: commit iff we began this frame (NOT a fresh flag read). If
    // AngleBeginFrame ran, AngleEndFrame MUST run to commit the buffer it
    // started -- even if the native-EGL flag flipped true mid-frame -- so no
    // uncommitted buffer is ever left stranded on the shared Metal FIFO.
    if (beganAngleFrame) macapk::AngleEndFrame();
#endif
}

@end

namespace macapk {

// v1.0.1643: a canvas-path MTKView that is no longer the window content lets
// go of its Java renderer. Every canvas-only content install creates a FRESH
// MTKView + ViewCanvasDriver (Activity.macapkInstallContentDirect) and this
// file is MRC, so the superseded view -- and its delegate's JNI GLOBAL ref to
// the driver -- lived forever; the driver's rootView held the whole screen,
// activity included (measured with debug/hprof_retainers.sh: every destroyed
// Wikipedia PageActivity was held by `jni-global -> ViewCanvasDriver.rootView`).
// Only called for canvas-path views (never a SurfaceView's GL MTKView, which
// its SurfaceView reuses when its activity comes back). The view is paused and
// loses its delegate; the MTKView object itself is not released (other globals
// may still point at it -- a follow-up).
void CocoaMetalSurfaceReleaseRenderer(void* metal_view) {
    if (!metal_view) return;
    MTKView* view = (__bridge MTKView*)metal_view;
    dispatch_async(dispatch_get_main_queue(), ^{
        id del = view.delegate;
        view.paused = YES;
        if ([del isKindOfClass:[MacApkV2RenderDelegate class]]) {
            MacApkV2RenderDelegate* d = (MacApkV2RenderDelegate*)del;
            if (d->javaVm && d->globalGlSurfaceViewRef) {
                JNIEnv* env = nullptr;
                jint r = d->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
                if (r == JNI_EDETACHED)
                    d->javaVm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
                if (env) env->DeleteGlobalRef(d->globalGlSurfaceViewRef);
                d->globalGlSurfaceViewRef = NULL;
            }
        }
        view.delegate = nil;
    });
}

void CocoaMetalSurfaceSetRenderer(void* metal_view,
                                  void* java_vm,
                                  long  global_glsurfaceview_ref) {
    if (!metal_view) return;
    MTKView* view = (__bridge MTKView*)metal_view;
    MacApkV2RenderDelegate* delegate = [[MacApkV2RenderDelegate alloc] init];
    delegate->javaVm                 = (JavaVM*)java_vm;
    delegate->globalGlSurfaceViewRef = (jobject)global_glsurfaceview_ref;
    delegate->drawFrameMid           = NULL;
    delegate->surfaceChangedMid      = NULL;
    delegate->sentSurfaceW           = 0;   // v1.0.503: live-resize tracker
    delegate->sentSurfaceH           = 0;
    delegate->resizeHoldFrames       = 0;
    delegate->surfaceCreatedFired    = NO;
    delegate->angleInitTried         = NO;
    delegate->angleInitOk            = NO;
    view.delegate = delegate;
    // Hold the delegate so it isn't collected -- associate it with the view.
    objc_setAssociatedObject(view, "macapk_v2_delegate", delegate,
                              OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

}  // namespace macapk

