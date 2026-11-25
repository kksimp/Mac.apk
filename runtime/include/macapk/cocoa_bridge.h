// cocoa_bridge.h
//
// C interface to the runtime's Cocoa side, exposed to the Java framework
// reimpl through JNI. All functions here are callable from any thread;
// they marshal to the Cocoa main thread internally (NSApplication's
// runloop).

#pragma once

#include <vector>

#include <cstdint>
#include <string>

#include "macapk/window_policy.h"

namespace macapk {

// Initializes NSApplication. Must be called once on the main thread
// before any window is created. Idempotent.
bool CocoaInit();

// Per-launch phone canvas size. The launcher sets this from the
// screen-size picker before the runtime boots.
void CocoaSetPhoneSize(int width, int height);
/** The app's AOSP pre-O max aspect ratio (long/short, 0 = uncapped).
 *  Shapes the WINDOW to the cap so a capped app fills it instead of
 *  sitting in a letterbox band. Off-switch: MACAPK_FIT_WINDOW_TO_ASPECT=0. */
void CocoaSetMaxAspectRatio(float ratio);
// "Auto" canvas: the biggest size the main display supports, shaped to
// the resolved orientation (landscape = the visible frame; portrait =
// its rotated aspect scaled to fit). Used when no explicit
// --width/--height was given.
void CocoaSetPhoneSizeMax(bool landscape);
// Re-apply the current canvas size to the already-open placeholder window.
// Needed by the window-first boot order, where the window opens before the
// authoritative orientation is known.
void CocoaReshapePlaceholder();
// AOSP's starting window: paint the activity's own android:windowBackground
// into the placeholder so a booting app shows its brand colour instead of
// black, as a device does. Alpha is ignored (a starting window is opaque).
void CocoaSetStartingWindowColor(int argb);
// Apply a runtime Activity.setRequestedOrientation() landscape/portrait request:
// reshape the canvas + placeholder window to the requested side. Generic path for
// apps that set orientation in code rather than the manifest (old cocos2d-x v2,
// many Unity/SDL titles). Marshals to the Cocoa main thread internally.
void CocoaApplyRequestedOrientation(bool landscape);
// Apply the USER rotation (seeded from the boot-resolved orientation, moved by
// Cmd+R): the resolution AOSP's DisplayRotation gives a non-committal
// requested orientation (UNSPECIFIED/USER/FULL_USER) on a rotation-locked
// device -- `preferredRotation = mUserRotation`. The fullscreen-video exit
// path: enter forced LANDSCAPE, exit UNSPECIFIED -> back to how the user held
// the phone. Marshals to the Cocoa main thread internally.
void CocoaRestoreUserOrientation();
// The display refresh rate to report AND to pace at: the measured rate of the screen the window
// is on, clamped to MACAPK_MAX_FPS (default 120). Callable from any thread. See the long comment
// at MacapkPanelHz -- this is the SINGLE source of the rate for the present cadence, the guest
// drive cadence, and android.view.Display.getRefreshRate(); they must not diverge (v1.0.568).
float CocoaBackingScale();   // real window backing scale; input mapping used a hardcoded 2.0f
// The process's display density (v1.0.2187): the launch screen's backing scale in
// dpi -- 320 on Retina, 160 on a 1x monitor -- or MACAPK_DISPLAY_DENSITY. Decided
// once, on first call (main.cpp makes that call on the main thread before the
// JVM starts); every later call returns the same value. See cocoa_bridge.mm.
int   CocoaDisplayDensityDpi();
float CocoaDisplayDensity();     // CocoaDisplayDensityDpi() / 160
// Harness only: post a real left-mouse NSEvent into our own queue at a fraction of the
// window content (top-left origin); 0 down, 1 up, 2 dragged. See cocoa_bridge.mm.
bool CocoaPostHostMouse(int action, float fx, float fy);
// Harness only: run the host-trackpad body (NSTouch frame = kind 0, magnify = kind 1) on the
// input view under the cursor fraction a[0],a[1], below the NSEvent decode. See cocoa_bridge.mm.
bool CocoaDebugTrackpad(int kind, const float* a, int n);
int  CocoaDisplayHz();
int  CocoaPhoneWidth();
int  CocoaPhoneHeight();

// ---- AOSP large-screen window policy (window_policy.h; v1.0.1647) ----------
// The main screen's content budget -- its visible frame minus this window
// style's title bar, the SAME budget CocoaSetPhoneSizeMax fits -- in whole
// points, plus the screen's backing scale. Callable before CocoaInit, from the
// main thread.
struct CocoaContentBudget { int w_pt = 0; int h_pt = 0; float scale = 2.0f; bool ok = false; };
CocoaContentBudget CocoaQueryContentBudget();
// The DISPLAY the window policy decides on: the content budget above in
// pixels at the process's display density (CocoaDisplayDensity), or, when
// MACAPK_WINDOW_BUDGET=<W>x<H>[~<swDp>] is set, that many POINTS at the
// screen's scale (the tablet A/B uses it to sit on the emulator's 1680x818dp,
// sw850 app area). Under no override, MACAPK_WINDOW_SIZE
// (below) replaces the screen's budget the same way. `desc` says which, for the log. False when there is no
// screen (or the override does not parse -- then desc says so).
bool CocoaPolicyDisplayBudget(winpolicy::DisplayBudget* out, std::string* desc);
// MACAPK_WINDOW_SIZE=<W>x<H> (points; the launcher's per-app "Window size at
// launch", issue #133), fitted into the content budget above
// (winpolicy::FitLaunchSize). CocoaPolicyDisplayBudget decides on it in place
// of the screen; main.cpp's legacy rule (no policy, or a size under sw600dp,
// where Android would rotate the display) opens the window at it, its long
// side turned to the app's orientation. `want_*` get the size as written.
// False when unset or not WxH (logged once). Main thread, before CocoaInit too.
bool CocoaLaunchSize(int* w_pt, int* h_pt, int* want_w = nullptr, int* want_h = nullptr);
// Install the policy SESSION: the launcher's traits, the device settings and
// the per-app Cmd+R preference file. While a session is active, Cmd+R toggles
// that preference (and persists it unless `persist_pref` is false, i.e. the
// run pinned MACAPK_WINDOW_PREF), Activity.setRequestedOrientation goes
// through winpolicy::ApplyRequestedOrientation, and the post-JVM max-aspect
// clamp is skipped (the decision already applied it). Main thread.
void CocoaInstallWindowPolicy(const winpolicy::WindowTraits& traits,
                              const winpolicy::DeviceSettings& settings,
                              const std::string& pref_path,
                              winpolicy::UserWindowPref pref,
                              bool persist_pref);
// Drop the session (the authoritative read fell back to the legacy rule).
void CocoaClearWindowPolicy();
bool CocoaWindowPolicyActive();
// Size the window box AND the app box (g_phone) from a decision made on
// `budget`, start the live state from it, and seed the user rotation from it.
// Does not reshape a live window; the caller follows with
// CocoaReshapePlaceholder when one is open. Main thread.
void CocoaApplyWindowDecision(const winpolicy::WindowDecision& d,
                              const winpolicy::DisplayBudget& budget);
// Activity.setRequestedOrientation(screenOrientation) under a policy session
// (the raw ActivityInfo value, so NOSENSOR and the SENSOR_* family reach the
// port). Posts to the main thread. A no-op without a session.
void CocoaApplyWindowOrientation(int screen_orientation);

// Runs the main event loop. Blocks until the user closes the last
// window. Returns once the runloop exits naturally.
void CocoaRunLoop();

// Window + Metal-backed view. The activity's lifecycle (driven from
// Java) calls these via JNI when it sets the content view.
void* CocoaCreateWindow(const char* title);
/** Create an MTKView intended to host the Canvas overlay (Path A in
 *  Activity.setContentView -- pure View hierarchy, no SurfaceView in
 *  tree). Updates the internal "canvas target" pointer so the next
 *  CocoaPresentBitmap call (re)attaches the overlay layer here. */
void* CocoaCreateMetalSurfaceView(int width_hint, int height_hint);
/** Create an MTKView intended for GL rendering by a SurfaceView/
 *  GLSurfaceView (Path B in Activity.setContentView). Does NOT
 *  update the "canvas target" pointer -- the SurfaceView's MTKView
 *  hosts ANGLE's CAMetalLayer, not the Canvas overlay. If we routed
 *  the Canvas overlay to a SurfaceView's MTKView, an opaque overlay
 *  bitmap from a previous Path-A Activity would block the GL render
 *  underneath (the Flappy splash -> AndEngine handoff hits this --
 *  the splash Activity's Canvas overlay would otherwise paint
 *  opaque-white over the AndEngine GL output, leaving a white
 *  screen instead of the bird sprite). */
void* CocoaCreateMetalSurfaceViewForGl(int width_hint, int height_hint);
/** Returns the placeholder NSWindow created at CocoaInit time so the
 *  user sees a window immediately, before any APK lifecycle reaches
 *  setContentView. The Java side reuses this same window for content
 *  swaps so the title bar, position, and size stay stable. */
void* CocoaGetPlaceholderWindow();

// Open a URL on the macOS host via NSWorkspace. Returns true if accepted.
bool CocoaOpenHostUrl(const char* url);

// Host URL intake (MACAPK_URL_SCHEMES): move every URL LaunchServices has
// delivered through kAEGetURL since the last call into `out` (oldest first)
// and clear the queue. See cocoa_bridge.mm "Host URL intake".
void CocoaTakeHostUrls(std::vector<std::string>& out);
// Queue a URL for the app exactly as a kAEGetURL Apple Event would
// (HostUrlIntake drains it). Used by the App Link sign-in window.
void CocoaQueueHostUrl(const char* url);
// The App Link sign-in window (applink_window.mm): show `url`; a navigation to
// `return_prefix` is cancelled and queued with CocoaQueueHostUrl.
bool CocoaOpenAppLinkWindow(const char* url, const char* return_prefix);

// Host URL intake: the applications LaunchServices already lists as handlers of
// `scheme` (bundle paths). false = the query is unavailable (unknown), which is
// NOT the same as an empty list ("no handler").
bool CocoaUrlSchemeHandlers(const char* scheme, std::vector<std::string>& out);
// Host URL intake, redirect routing (MACAPK_URL_REDIRECT_OPENER): make the bundle at
// `bundlePath` the LaunchServices default handler of `scheme`. 0 = it already is,
// 1 = the switch was requested (asynchronous, NSWorkspace completion), -1 = unusable
// arguments. Never blocks the caller.
int CocoaSetUrlSchemeDefault(const char* bundlePath, const char* scheme);
void  CocoaSetWindowContentView(void* window, void* content_view);
/** Update the window's titlebar text. Called when the active Activity
 *  changes so the user sees the right name. */
void  CocoaSetWindowTitle(void* window, const char* title);
void  CocoaShowWindow(void* window);
void  CocoaCloseWindow(void* window);

// Wires the per-frame draw callback. The Java side hands us a global
// ref to a GLSurfaceView so MTKView's display link callback can call
// into Java to fire the renderer's onSurfaceCreated/Changed/onDrawFrame.
//
// java_vm is the JavaVM* obtained from JNI_CreateJavaVM; we keep it so
// background threads (the display link) can AttachCurrentThread. The
// global ref must outlive the metal view; the Cocoa bridge holds onto
// it for the lifetime of the view.
void CocoaMetalSurfaceSetRenderer(void* metal_view,
                                  void* java_vm,
                                  long  global_glsurfaceview_ref);

/** Hook the Java touch dispatcher. Called once by the loader at boot.
 *  global_dispatcher_ref is a JNI global ref to the singleton instance
 *  of com.macapk.loader.TouchDispatcher. The native MTKView subclass
 *  uses it to forward Cocoa mouseDown/Dragged/Up to Java as a
 *  MotionEvent dispatched at the active Activity. */
void CocoaRegisterTouchDispatcher(void* java_vm, long global_dispatcher_ref);

// --- Translation splash overlay --------------------------------------
//
// Shown over the placeholder window while macapk-translator runs on
// an APK's ARM32 lib. Translation is a one-time per-binary cost (2-5
// minutes on HCR-class binaries); without a status overlay, the user
// just sees a black window for that whole time and assumes the app
// hung. Three calls:
//
//   CocoaShowTranslationOverlay("game")    -- creates + installs the
//                                              overlay on top of the
//                                              placeholder window's
//                                              content view.
//   CocoaUpdateTranslationOverlay("...")   -- updates the live detail
//                                              line (e.g. "Lifting
//                                              functions..." -> "Chunk
//                                              3/6 compiling...").
//   CocoaHideTranslationOverlay()          -- removes from view
//                                              hierarchy and releases.
//
// Resource cost is intentionally near-zero: two NSTextFields and a
// solid-color NSView. No animations, no visual-effect blur, no
// progress-indicator timers. The detail line only redraws when the
// AppClassLoader's translator-output reader hands us a new string,
// which is at most a few times per minute.
//
// All three calls are idempotent and thread-safe: they marshal to
// the Cocoa main thread internally.
void CocoaShowTranslationOverlay(const char* libname);
void CocoaUpdateTranslationOverlay(const char* detail);
void CocoaHideTranslationOverlay();

// The same overlay widget with the caller's own words. Used for the COLD-BOOT
// setup notice ("First-time setup - converting <App>") that the runtime shows
// while UnpackApk converts DEX and translates native code. Update the live
// detail line with CocoaUpdateTranslationOverlay and dismiss with
// CocoaHideTranslationOverlay -- they operate on the one shared overlay.
void CocoaShowSetupOverlay(const char* headline, const char* body);

// Service AppKit for up to `seconds`. MAIN THREAD ONLY, and a no-op anywhere
// else. The window-first boot order needs it: main() creates the placeholder
// window, hands the long unpack to a worker, and then sits here so the window
// actually paints and the user can move/close it while the work happens.
void CocoaPumpEvents(double seconds);

// Re-read the nextDrawable acquire policy from the environment. CocoaInit reads
// it once, but a window-first launch decides the ANGLE backend (and so
// ANGLE_DEFAULT_PLATFORM, which the policy keys on) AFTER CocoaInit, on the
// unpack worker. main() calls this once it has applied that decision. MAIN
// THREAD, before the JVM starts.
void CocoaRefreshDrawablePolicy();

// Present an ARGB pixel buffer as the current contents of a CALayer
// overlay sitting above the MTKView. Used by SurfaceHolder.unlock-
// CanvasAndPost to show Java-side Canvas-painted Bitmaps on screen.
// pixels[] is row-major width*height ARGB ints (0xAARRGGBB) in native
// byte order. Width and height are pixels. Caller retains ownership
// of the buffer.
void CocoaPresentBitmap(const uint32_t* pixels, int width, int height);

// GL-surface twin of CocoaPresentBitmap: composites a WindowManager
// overlay (Dialog/AlertDialog/PopupWindow) on top of the CURRENT GL
// (SurfaceView-backed) MTKView, via its own layer -- CocoaPresentBitmap's
// target is deliberately never a GL view (see CocoaCreateMetalSurfaceViewForGl).
// pixels[] must be the SAME size as the GL surface (transparent where
// there is no overlay content); see WindowOverlayStack.presentForGl.
void CocoaPresentOverlayBitmap(const uint32_t* pixels, int width, int height);
// Hides/clears the GL overlay layer once WindowOverlayStack goes back to
// empty (the dialog was dismissed), so the last frame does not stick.
void CocoaClearOverlayBitmap();

// Guest-initiated process exit (System.exit / Runtime.exit / Runtime.halt,
// re-pointed here by the DEX converter). Ends the process through the same
// native _exit the window-close path uses (never libc exit: see
// macapk_terminate_now in cocoa_bridge.mm for the deadlock). When
// relaunch_intents_file is non-null the app bundle is reopened once this
// process is gone, with MACAPK_RESTART_INTENTS naming that file, which is how
// Mac.apk plays the ActivityManager's part in the "restart myself" idiom
// (com.macapk.loader.MacApkProcessExit). Does not return.
void CocoaExitProcess(int code, const char* relaunch_intents_file);

// Debug-only (MACAPK_INJECT_FILE verbs close / quit / terminate): perform the
// REAL AppKit path of a user quit gesture on the main thread -- performClose:
// on the window, the Cmd-Q action, or NSApplication terminate: (Dock "Quit").
// Used by debug/closelat.sh to measure close latency without Accessibility.
void CocoaDebugQuitGesture(const char* kind);

// One render tick of the host render thread, now: -[MTKView draw] on the
// window-attached view, i.e. drawInMTKView -- the work the display link does
// each vsync. For ActivityHost.macapkTerminate, which blocks this (AppKit main)
// thread while the Android main thread tears down, and whose teardown can wait
// on a render tick (AOSP GLThread; libGDX AndroidGraphics.pause/destroy).
// Returns false and does nothing off the main thread, when called from inside a
// tick (never re-enters drawInMTKView), or with no window-attached view.
bool CocoaHostRenderTick();

// Order every visible window of the app off screen on the first call, and flush
// the implicit CA transaction on every call so the ordering reaches the window
// server while this thread stays blocked (main thread only; false and nothing
// done elsewhere). ActivityHost.macapkTerminate calls it once the
// visible activity of the task being removed has paused (AOSP hides a finishing
// resumed activity no later than that: ActivityRecord.finishIfPossible
// setVisibility(false); its onStop/onDestroy follow the pause).
bool CocoaHostHideWindowsForQuit();

// The quit backstop (ActivityHost.QUIT_BACKSTOP_GRACE_MS), any thread: the host
// thread is still inside a render tick it pumped during macapk_terminate_now's
// teardown, well past the 2 s cap. Finishes the quit without it -- the same
// process-group kill and _exit(code) macapk_terminate_now ends in. Does not
// return. A no-op (returns) when no quit is under way.
void CocoaQuitBackstopExit();

// v1.0.1642: the Android task went behind home (ActivityHost: BACK at the
// launcher root, Activity.moveTaskToBack, or an emptied task). Hides the app;
// NSApplicationDidUnhideNotification then calls ActivityHost.macapkTaskToFront.
void CocoaMoveTaskToBack();
// A launch arrived while the task was in the back: unhide the app.
void CocoaMoveTaskToFront();
// v1.0.1643: a superseded canvas-path MTKView drops its Java renderer (pauses,
// loses its delegate, deletes the JNI global ref) so the old screen is freed.
void CocoaMetalSurfaceReleaseRenderer(void* metal_view);

}  // namespace macapk
