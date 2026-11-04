// JNI surface to the Cocoa side. Window/view handles are returned as
// `long` (native pointers); callers stuff them into "host handle"
// fields on Java view objects. Don't dereference these in Java.
package com.macapk.loader;

public final class NativeCocoa {
    private NativeCocoa() {}

    public static native boolean init();

    public static native void setPhoneSize(int width, int height);
    /** Display refresh rate in Hz: the measured rate of the screen the window is on, clamped to
     *  MACAPK_MAX_FPS (default 120). The SAME value paces the present loop and the guest drive
     *  cadence, so android.view.Display.getRefreshRate() must report this and nothing else. */
    /** Real window backing scale. Touch mapping hardcoded 2.0f, which is wrong on a 1x display. */
    public static native float backingScale();
    /** HARNESS: post a real left-mouse NSEvent (0 down, 1 up, 2 dragged) into our own AppKit
     *  queue at a FRACTION of the window content, top-left origin, so an injected click crosses
     *  every stage a user's click does (see CocoaPostHostMouse). False = no window yet. */
    public static native boolean postHostMouse(int action, float fx, float fy);
    /** HARNESS: run the host-trackpad body a real trackpad runs (CocoaDebugTrackpad), on the
     *  input view under the cursor fraction a[0],a[1]. kind 0 = one NSTouch frame (a[2..] are
     *  id, phase, nx, ny quadruples), kind 1 = a magnify event (a[2] phase, a[3] magnification).
     *  False = no window / no input view yet. */
    public static native boolean debugTrackpad(int kind, float[] a);
    public static native int  displayHz();
    public static native int  phoneWidth();
    public static native int  phoneHeight();

    /** Apply an Activity.setRequestedOrientation() request to the canvas/window.
     *  landscape=true shapes a landscape canvas; false portrait. Safe to call
     *  before the window exists (the new size is picked up at window creation).
     *  Marshals to the Cocoa main thread internally. */
    /** Publish the app's AOSP pre-O max aspect ratio so the WINDOW adopts it
     *  (0 = uncapped). Without this a capped app is handed the auto canvas's
     *  2.22:1 phone profile and can never fill it, leaving a permanent black
     *  band. Off-switch: MACAPK_FIT_WINDOW_TO_ASPECT=0. */
    public static native void setMaxAspectRatio(float ratio);

    /** AOSP's STARTING WINDOW colour. Real Android paints the activity's own
     *  `android:windowBackground` before the app has drawn anything
     *  (PhoneWindowManager.addSplashScreen / SplashscreenContentDrawer read
     *  exactly that attribute); our placeholder window was hardcoded black, so
     *  every boot showed a black rectangle where a device shows the app's brand
     *  colour. ActivityHost calls this right after the manifest theme is
     *  anchored and before onCreate -- the same point in the lifecycle AOSP
     *  has it. Alpha is ignored; a starting window is opaque by definition. */
    public static native void setStartingWindowColor(int argb);

    public static native void applyRequestedOrientation(boolean landscape);

    /** Return the canvas/window to the USER rotation (the boot-resolved
     *  orientation, moved only by Cmd+R). What a non-committal
     *  setRequestedOrientation (UNSPECIFIED/USER/FULL_USER) resolves to on a
     *  rotation-locked device per AOSP DisplayRotation.rotationForOrientation
     *  ("Apply rotation lock ... preferredRotation = mUserRotation") -- and a
     *  sensor-less Mac is honestly modelled as rotation-locked. This is how
     *  exiting fullscreen video gets back to portrait. */
    public static native void restoreUserOrientation();

    /** v1.0.1647: Activity.setRequestedOrientation under the AOSP window
     *  policy (-Dmacapk.window.policy=aosp), with the RAW ActivityInfo value
     *  so the native port sees NOSENSOR and the SENSOR_* family as Android's
     *  window manager does (window_policy.h ApplyRequestedOrientation). */
    public static native void applyWindowOrientation(int screenOrientation);

    /** v1.0.1649 (window policy phase 2a): the RESUMED top activity's real
     *  configChanges (Activity.macapkRealConfigChanged, i.e. AOSP
     *  ActivityInfo.getRealConfigChanged). The native side reads only whether
     *  orientation AND screenSize are declared (winpolicy::HandlesSideChange):
     *  while Stage-2 relaunch is off, a user drag of a free (C1) window keeps
     *  its side unless the activity handles that change itself. Pushed from
     *  ActivityHost on resume so the AppKit thread never calls into activity
     *  state. Any thread; applied on the Cocoa main thread. */
    public static native void setTopActivityConfigChanges(int realConfigChanges);

    /** FLAG_SECURE mirror (WindowManager.LayoutParams.FLAG_SECURE: "treat the
     *  content of the window as secure, preventing it from appearing in
     *  screenshots or from being viewed on non-secure displays"). True while the
     *  VISIBLE activity stack carries the flag (ActivityHost.macapkPushSecureContent);
     *  the native side sets NSWindow.sharingType = NSWindowSharingNone on this
     *  process's windows, and back to the AppKit default NSWindowSharingReadOnly
     *  when cleared. Any thread; applied on the Cocoa main thread. SOFT-registered
     *  (cocoa_bridge.mm RegisterSecureContentMethods, not kNativeCocoaMethods), so
     *  a runtime binary older than this jar leaves it unbound: callers catch
     *  UnsatisfiedLinkError. Gate MACAPK_SECURE_WINDOW_SHARING (native). */
    public static native void setSecureContent(boolean secure);

    /** Window policy phase 3 (ActivityHost "every activity gets its own box";
     *  winpolicy::ApplyActivityTransition). An activity STARTS (event 0; 1 =
     *  as the first activity of a NEW task) or is REVEALED as the box owner
     *  (2): its box is resolved from its own traits and record, and the
     *  window letterboxes it, grows to
     *  it or returns to the size it was shown in. `occludes` = its manifest
     *  theme hides what is below (FrameworkWindowStyle.ownsWindowBox); a
     *  non-occluding start inherits the box below. Event 3 = a start into the
     *  LIVE task whose activities all finished (the splash idioms): the start
     *  fit rule, nothing to inherit from. `hasRequest` / `request` =
     *  its record's own setRequestedOrientation; `behind` = the orientation a
     *  BEHIND takes (ActivityInfo.SCREEN_ORIENTATION_UNSET: none);
     *  `realConfigChanges` = ActivityInfo.getRealConfigChanged. `token` names
     *  this record; `coverToken` the box owner a start covers, whose box is
     *  remembered for its reveal. Blocks the caller until the AppKit thread has
     *  applied it (bounded), and answers {appWidthPx, appHeightPx, change} --
     *  the render view's drawable size -- or null: no policy session, or the
     *  AppKit thread did not answer in time (the box then lands through the
     *  ordinary display commit a frame later). */
    public static native int[] activityWindowTransition(String component, int event,
            boolean occludes, boolean hasRequest, int request, int behind,
            int realConfigChanges, int token, int coverToken);

    /** Phase 3 replacement of applyWindowOrientation: the request of the
     *  activity `component` whose record is `token` (activityWindowTransition's
     *  token). The native side decides: applied when that record owns the box
     *  on screen, whatever is on top of it; refused from any other record.
     *  BEHIND takes `behind`. Any thread; applied on AppKit. */
    public static native void applyWindowOrientationFor(String component, int token,
                                                        int screenOrientation, int behind);

    /** ActivityRecord.getOverrideOrientation (android16-release
     *  ActivityRecord.java:7487-7496) for `component` whose record orientation
     *  is `recordOrientation`: universal resizability drops a fixed one, the
     *  Cmd+R user aspect-ratio override maps a non-fixed one to PORTRAIT.
     *  `recordOrientation` unchanged without a policy session. Any thread. */
    public static native int windowOverrideOrientation(String component, int recordOrientation);

    public static native void runLoop();

    // v1.0.690: open a URL/URI on the macOS host (NSWorkspace). The
    // resolution target for an implicit ACTION_VIEW/SENDTO/DIAL intent.
    public static native boolean openHostUrl(String url);
    /** Open `url` in the Mac.apk sign-in window (applink_window.mm); a
     *  navigation to `returnPrefix` (scheme://host/path of the app's verified
     *  redirect) is cancelled and queued for HostUrlIntake like a GURL. */
    public static native boolean openAppLinkWindow(String url, String returnPrefix);
    /** Host URL intake (MACAPK_URL_SCHEMES): every URL LaunchServices handed this
     *  app through kAEGetURL since the last call, oldest first, and clears the
     *  queue. See HostUrlIntake. */
    public static native String[] takeHostUrls();
    /** Host URL intake: bundle paths of the applications LaunchServices already
     *  lists as handlers of `scheme`; null when the query is unavailable
     *  (unknown), an empty array for "no handler". See HostUrlIntake. */
    public static native String[] urlSchemeHandlers(String scheme);
    /** Host URL intake, redirect routing: make the bundle at `bundlePath` the
     *  LaunchServices default handler of `scheme`. 0 = already, 1 = requested
     *  (asynchronous), -1 = unusable arguments. See HostUrlIntake.claimRedirectSchemes. */
    public static native int setUrlSchemeDefault(String bundlePath, String scheme);
    public static native long createWindow(String title);
    public static native long createMetalSurfaceView(int widthHint, int heightHint);
    /** v1.0.91: variant for SurfaceView's GL render target. Same MTKView
     *  construction, but the C++ side does NOT register this view as
     *  the Canvas overlay target. Necessary because the Canvas overlay
     *  CALayer sits ABOVE the MTKView's CAMetalLayer at zPosition=1.0,
     *  so if the overlay attaches to a SurfaceView's MTKView, an
     *  opaque overlay bitmap (e.g. the Flappy splash Activity's white
     *  clear) would block the GL render underneath. Path B (SurfaceView
     *  in tree at setContentView time) uses this variant; Path A (pure
     *  View hierarchy, Canvas-only rendering) uses the original. */
    public static native long createMetalSurfaceViewForGl(int widthHint, int heightHint);
    /** MACAPK_GLSV_RENDER_MODE: a RENDERMODE_WHEN_DIRTY GLSurfaceView is about to
     *  WITHHOLD this tick (nothing will be drawn into FBO 0). Marks the frame so
     *  the native frame end (AngleEndFrame) skips the default-framebuffer readback
     *  and the frame-numbered dump, and returns true when mayDraw and a self-capture waits
     *  for a real "gl" frame, i.e. the caller should draw this tick after all (in
     *  which case the frame is NOT marked). */
    public static native boolean beginWithheldTick(boolean mayDraw);
    /** Returns the placeholder NSWindow created at CocoaInit time
     *  (visible immediately when macapk-runtime boots so the user sees a
     *  window even when MainActivity.onCreate hangs in lifted code).
     *  setContentView reuses this single NSWindow so the title bar
     *  stays consistent and the renderer takes over the same surface. */
    public static native long getPlaceholderWindow();

    /** Lazy singleton MTKView. v2 has one window and one Metal drawable;
     *  every SurfaceView/GLSurfaceView shares the SAME native handle so
     *  ANGLE only ever binds to one CAMetalLayer. Without this, the
     *  fallback GLSurfaceView (created when an app's onCreate doesn't
     *  reach setContentView; MACAPK_EMPTY_WINDOW_DECOR=0 only, by default
     *  the activity's own empty decor,
     *  docs/fallback-glsurfaceview-content-retired_2026-09-27.md) and the
     *  app's real GLSurfaceView each
     *  create their own MTKView; ANGLE binds to one but the other
     *  becomes the window's contentView, leaving a black screen. */
    private static long sharedMetalSurfaceView;
    public static synchronized long getOrCreateSharedMetalSurfaceView(int widthHint, int heightHint) {
        if (sharedMetalSurfaceView == 0) {
            // v1.0.91: use the ForGl variant. SurfaceView's MTKView
            // hosts ANGLE's CAMetalLayer (GL render target), NOT the
            // Canvas overlay. Routing the Canvas overlay through the
            // SurfaceView's MTKView would let a previous Activity's
            // opaque overlay block GL output (Flappy splash -> bird
            // sprite handoff was the smoke gun).
            sharedMetalSurfaceView = createMetalSurfaceViewForGl(widthHint, heightHint);
        }
        return sharedMetalSurfaceView;
    }
    public static native void setContentView(long window, long view);
    public static native void showWindow(long window);
    public static native void closeWindow(long window);
    public static native void setRenderer(long metalView, MetalSurfaceCallbacks callbacks);
    public static native void setWindowTitle(long window, String title);
    /** Hook the touch dispatcher: Cocoa mouseDown/Dragged/Up will call
     *  dispatcher.dispatch(action, x, y, viewW, viewH) on the Cocoa thread. */
    public static native void registerTouchDispatcher(TouchDispatcher dispatcher);

    /** Tell the native shim which subdirectory under
     *  $ANDROID_ROOT/data/data/ holds this app's persistent files
     *  + extracted assets. Boot.java computes this as
     *  "<packageName>_<versionName>" so two installs of the same
     *  package (different versions) don't share state. The shim's
     *  fopen path-remap reads this when expanding relative paths
     *  like "fonts/gamefont.fnt" into absolute on-disk reads. */
    /** Mirror ONE system property into the bionic shim's store, so a guest's
     *  `__system_property_get` answers what Java answers. Push-only: the value
     *  is computed by android.os.SystemProperties and never re-derived in C++. */
    public static native void setSystemProperty(String key, String value);

    /** End the process the only safe way this runtime has (a native _exit,
     *  never libc exit -- see cocoa_bridge.mm macapk_terminate_now), optionally
     *  after arranging for the app to be relaunched with a marshalled Intent
     *  list once this process is gone. Does not return. */
    public static native void exitProcess(int code, String relaunchIntentsFile);

    /** Debug-only (MACAPK_INJECT_FILE verbs close / quit / terminate): run the
     *  REAL AppKit path of a user quit gesture -- the red close button
     *  (performClose:), Cmd-Q, or NSApplication terminate: (the Dock's "Quit").
     *  debug/closelat.sh measures close latency through it. */
    public static native void debugQuitGesture(String kind);

    /** Run ONE render tick of the host render thread now (-[MTKView draw] ->
     *  drawInMTKView, the same work the display link does each vsync). Used by
     *  ActivityHost.macapkTerminate while the host main thread waits for the
     *  teardown: that thread is our GLThread, and apps pause on it (libGDX
     *  AndroidGraphics.pause). Returns false, doing nothing, off the host main
     *  thread, inside a tick already, or with no window-attached view. */
    public static native boolean hostRenderTick();

    /** First call: order every visible window of the app off screen, on the host
     *  main thread (ActivityHost.macapkTerminate, once the task has paused).
     *  Every call: flush the Core Animation transaction that carries the
     *  ordering, which AppKit would otherwise commit only once this blocked
     *  thread returns to its run loop. Returns false, doing nothing, off the host
     *  main thread. */
    public static native boolean hostHideWindowsForQuit();

    /** The quit backstop (ActivityHost.QUIT_BACKSTOP_GRACE_MS): the host thread
     *  went into a render tick during the close's teardown and has not come back
     *  well past the cap. Leaves the process exactly as macapk_terminate_now
     *  would have (app process-group kill, _exit with its code). Does not
     *  return. Called only on the backstop thread of a quit already under way. */
    public static native void quitBackstopExit();

    /** v1.0.1642: the task went behind home (Activity.moveTaskToBack, BACK at
     *  the launcher root, or an emptied task). macOS analogue: hide the app.
     *  Unhiding it calls ActivityHost.macapkTaskToFront. */
    public static native void moveTaskToBack();

    /** A launch arrived while the task was in the back: unhide the app. */
    public static native void moveTaskToFront();

    /** v1.0.1643: a canvas-path MTKView that is no longer the window content
     *  releases its ViewCanvasDriver (native JNI global ref) and pauses. */
    public static native void releaseRenderer(long metalView);

    public static native void setDataSubdir(String subdir);
    /** The bionic shim's synthesized host file for a /proc path, or null.
     *  Installed into java.io.FileReader by Boot so Java /proc reads match
     *  native ones. */
    public static native String remapProcPath(String procPath);

    /** Tell the loader which directory guest libraries should be reported under in
     *  /proc/self/maps, so that file and ApplicationInfo.nativeLibraryDir name the
     *  same directory the way they do on a device. Pushed, never re-derived. */
    public static native void setNativeLibDir(String dir);

    /** Hand the shim the directory behind /system/etc/fonts.xml and
     *  /system/fonts/ (android.graphics.MacApkSystemFonts). */
    public static native void setSystemFontsDir(String dir);

    /** The file behind a host font's PostScript name, from CoreText, or null when
     *  this Mac has no face by that exact name (host_fonts.cpp). Used for the CJK
     *  families of that fonts.xml (android.graphics.MacApkHostFonts). */
    public static native String hostFontFile(String postScriptName);

    /** Hand the shim the one shared volume path every app uses
     *  (SharedStorage.adoptCanonicalVolume): native /sdcard/... names it from
     *  here on, /sdcard/Android/{data,obb,media} still the app's own. */
    public static native void setSharedStorageRoot(String dir);

    /** Show a one-shot "Translating <libname>…" splash over the
     *  placeholder window. Called from AppClassLoader.translate
     *  immediately before the macapk-translator subprocess starts.
     *  Resource-light: two NSTextFields, no animation, no blur. */
    public static native void showTranslationOverlay(String libname);

    /** Update the live detail line on the splash. AppClassLoader
     *  parses the translator's stderr for boundary events and pushes
     *  short strings here ("Lifting functions…", "Compiling chunk
     *  3/6…"). Safe to call from any thread; marshals to main. */
    public static native void updateTranslationOverlay(String detail);

    /** Tear down the splash. Called from AppClassLoader.translate
     *  after the subprocess returns (regardless of exit code). */
    public static native void hideTranslationOverlay();

    /** Present an ARGB bitmap as the current frame's contents on the
     *  primary window. Used by SurfaceHolder.unlockCanvasAndPost to
     *  push the Java-side Canvas-painted Bitmap to the screen.
     *  `pixels` is in BGRA (Bitmap.getPixels returns ARGB ints which
     *  Android stores as native-int; on little-endian that's BGRA in
     *  memory). Cocoa side builds a CGImage and sets it as the
     *  contents of a CALayer overlay sitting above the MTKView.
     *
     *  `argb` is only READ, and only during this call: the native side pins
     *  it (GetPrimitiveArrayCritical, MACAPK_PRESENT_CRITICAL), takes its own
     *  premultiplied copy and releases it before returning, and never retains
     *  it. That is what lets ViewCanvasDriver pass the frame bitmap's LIVE
     *  backing array (Bitmap.macapkLiveArgbOrNull) instead of a copy of it --
     *  as long as nothing draws into that bitmap during the call, which on
     *  the drawing thread itself is true by construction. */
    public static native void presentBitmap(int[] argb, int width, int height);

    /** Present a lockCanvas SurfaceView's frame through that surface's OWN
     *  layer (2026-09-26, F2 of docs/fleet-misc-shatteredpd-retrowars-bobball-
     *  bouncy-unciv_2026-09-26.md): AOSP composites a SurfaceView's buffer
     *  BELOW its window at the view's rect (SurfaceView mSubLayer =
     *  APPLICATION_MEDIA_SUBLAYER), so the view tree -- a HUD drawn by sibling
     *  views -- shows over it through the hole SurfaceView.draw punches.
     *  presentBitmap cannot express that: it replaces the one overlay layer the
     *  view tree is also presented into. `surfaceKey` names the surface; the
     *  native side PULLS its rect (SurfaceView.macapkSurfaceRectPacked) and its
     *  z-order (SurfaceView.macapkSurfaceZOrderOnTop) on the main thread, as
     *  the video plane pulls macapkActiveVideoRectPacked. Same read-only,
     *  read-during-the-call contract as presentBitmap. SurfaceView resolves
     *  this by a capability probe, so it runs with or without it; declared here
     *  in LOCKSTEP with its RegisterNatives row in native_methods.cpp. */
    public static native void presentSurfaceBitmap(int surfaceKey, int[] argb, int width, int height);

    /** presentFrame results. */
    public static final int PRESENT_NONE = 0;              // nothing presented (bad args, pin failed)
    public static final int PRESENT_PUBLISHED = 1;         // a ring surface was written and published
    public static final int PRESENT_ELIDED_IDENTICAL = 2;  // pixel-identical to what the layer shows
    public static final int PRESENT_FALLBACK = 3;          // no ring surface free: the CGImage path, this frame
    /** presentFrame flags. */
    public static final int PRESENT_FLAG_FORCE_FULL = 1;   // treat every pixel as changed (new bitmap, new driver)
    public static final int PRESENT_FLAG_VALIDATE = 2;     // diff outside the bound too, log under-damage
    public static final int PRESENT_FLAG_HEARTBEAT = 4;    // an M6 heartbeat frame (nothing scheduled it)

    /** The IOSurface-ring present (P2/P3 of
     *  docs/canvas-present-cost-at-full-screen_2026-09-25.md). Same pixels and
     *  the same read-only, read-during-the-call contract as presentBitmap, but
     *  the native side diffs the frame against the last one presented on the
     *  layer (exactly, in 64-byte blocks), copies only the changed row spans,
     *  writes them into one of a small ring of IOSurfaces that become the
     *  layer's contents, and ELIDES the present altogether when nothing changed.
     *  The bound (left, top, right, bottom) is the region the caller claims may
     *  have changed -- the whole surface until view damage exists -- and only
     *  it is diffed unless PRESENT_FLAG_VALIDATE is set. Returns PRESENT_*.
     *  Used only under MACAPK_PRESENT_IOSURFACE=1 (off by default); declared
     *  here in lockstep with its RegisterNatives row in native_methods.cpp. */
    public static native int presentFrame(int[] argb, int width, int height,
                                          int boundLeft, int boundTop,
                                          int boundRight, int boundBottom, int flags);

    /** GL-surface twin of presentBitmap: composites a WindowManager overlay
     *  (Dialog/AlertDialog/PopupWindow) on top of the CURRENT GL surface via
     *  its own CALayer, separate from presentBitmap's target (which is
     *  deliberately never a GL view). `pixels` must be the SAME size as the
     *  GL surface (transparent where there is no overlay content) -- see
     *  WindowOverlayStack.presentForGl. Added 2026-07-23 (GD Meltdown touch
     *  investigation): a real, non-cancelable AlertDialog shown by an SDK
     *  over a GL game was winning every touch dispatch yet compositing
     *  nowhere -- an invisible, permanent, un-dismissable modal block.
     *  Same read-only, read-during-the-call contract as presentBitmap. */
    public static native void presentOverlayBitmap(int[] argb, int width, int height);
    /** Hide/clear the GL overlay layer once WindowOverlayStack goes back to
     *  empty, so the last-rendered dialog frame does not stick on screen. */
    public static native void clearOverlayBitmap();
}
