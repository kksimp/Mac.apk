// android.view.SurfaceView -- a View that owns an off-thread drawing
// surface. AndEngine and other games subclass this; AndEngine's
// RenderSurfaceView is a direct subclass.
//
// In v2 we allocate a native MTKView eagerly in the constructor so any
// SurfaceView subclass (including the AndEngine one) ends up with a
// real native drawable that Activity.setContentView can find when it
// walks the view tree. Non-rendering apps that wrap a SurfaceView for
// e.g. video playback will get an unused MTKView -- harmless.
//
// Renderer autodiscovery: many engines (cocos2d-x's FSGLSurfaceView,
// custom Unity wrappers, etc.) re-implement Android's GLSurfaceView
// against javax.microedition.khronos.egl.* and run their own GL
// thread. Those threads can't actually run on macOS (no real Bionic
// EGL on the host) so they die before producing frames. To still
// drive their renderer from our MTKView display link, SurfaceView
// self-registers as a MetalSurfaceCallbacks target and uses
// reflection at draw time to locate the engine's renderer field
// (typically `mRenderer` of a class implementing onDrawFrame). When
// found, macapkDrawFrame() forwards each MTKView vsync to that
// renderer's onDrawFrame so libgame.so's nativeRender hits our
// gl* shims with a current ANGLE EGL context. Engines that subclass
// our v2 android.opengl.GLSurfaceView override these methods and
// take precedence -- this base-class fallback only runs for engines
// that bypass our GLSurfaceView entirely.
package android.view;

import android.content.Context;
import com.macapk.loader.MetalSurfaceCallbacks;
import com.macapk.loader.NativeCocoa;
import com.macapk.loader.NativeLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class SurfaceView extends View implements MetalSurfaceCallbacks {
    private static final String TAG = "Mac.apk.SurfaceView";

    /** Cached renderer found via reflection on the subclass. The
     *  field's value implements onDrawFrame(GL10) and (optionally)
     *  onSurfaceCreated/Changed. Resolved on first macapkDrawFrame
     *  call after the subclass has finished setting it. We retry
     *  the lookup every frame until a renderer is found because
     *  engines typically assign mRenderer AFTER the constructor
     *  (e.g. via setRenderer() called from MainActivity.onCreate). */
    private Object   reflectedRenderer;
    /** Method handle for onDrawFrame(GL10) on the resolved renderer. */
    private Method   reflectedOnDrawFrame;
    private Method   reflectedOnSurfaceChanged;
    private Method   reflectedOnSurfaceCreated;
    private boolean  reflectedSurfaceCreatedFired;
    /** v1.0.996: the last WINDOW-box surface size actually delivered to the
     *  reflected renderer. The reflection path dispatches onSurfaceChanged only
     *  from macapkSurfaceChanged, which fires on a DISPLAY-size change -- but
     *  the app window box can move without the display moving, because an app
     *  may set FLAG_FULLSCREEN or hide/show the nav bar at runtime (Frozen
     *  Bubble's HomeScreen.setWindowLayout does exactly that). Tracked so
     *  macapkConvergeReflectedSurfaceSize can re-publish, which is what the
     *  setRenderer path already gets for free from ensureLifecycleDelivered's
     *  own lifecycleDeliveredW/H comparison. -1 = nothing delivered yet. */
    private int      reflectedDeliveredW = -1;
    private int      reflectedDeliveredH = -1;
    /** Cap retries so we don't reflect on every frame forever for
     *  SurfaceView instances that genuinely have no renderer (e.g.
     *  video preview surfaces). 600 frames at 60fps = 10s grace. */
    private int      reflectedLookupAttempts;
    private static final int MAX_RENDERER_LOOKUPS = 600;

    public SurfaceView(Context c) {
        super(c);
        // Share the singleton MTKView. ANGLE binds to whatever native
        // MTKView's CAMetalLayer it sees first; if every SurfaceView
        // had its own, the visible one and the ANGLE-bound one could
        // diverge (and they did -- caused a black screen on Flappy).
        long handle = NativeCocoa.getOrCreateSharedMetalSurfaceView(
            NativeCocoa.phoneWidth(), NativeCocoa.phoneHeight());
        setNativeHandle(handle);
        // Self-register as the per-frame target. Subclasses that
        // implement their own macapkDrawFrame (like our v2
        // GLSurfaceView) override and short-circuit the reflection
        // path. Subclasses that don't (e.g. cocos2d-x's
        // FSGLSurfaceView) get the autodiscovery fallback below.
        if (handle != 0) NativeCocoa.setRenderer(handle, this);
        macapkRegisterLiveForEgl(this);
    }
    public SurfaceView(Context c, android.util.AttributeSet a) { this(c); }
    /** AOSP API 21 four-argument constructor. AOSP's three-argument form is
     *  exactly this(context, attrs, defStyleAttr, 0), so delegating the other
     *  way is identical for defStyleRes == 0; a non-zero defStyleRes is
     *  DROPPED here, as in the existing FrameLayout/Button four-argument forms
     *  (TextView, ProgressBar and their subclasses honour it). Without it an
     *  app subclass calling the four-argument
     *  super() dies with NoSuchMethodError; see debug/ctorsdk_gate.sh. */
    public SurfaceView(Context context, android.util.AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        this(context, attrs, defStyleAttr);
    }
    public SurfaceView(Context c, android.util.AttributeSet a, int s) { this(c); }

    public SurfaceHolder getHolder() { return holder; }
    /** AOSP setZOrderOnTop (android14 SurfaceView.java:660) moves the surface's
     *  sublayer above the window (APPLICATION_PANEL_SUBLAYER) or back to
     *  APPLICATION_MEDIA_SUBLAYER. Recorded (2026-09-26) so the per-surface
     *  layer can be placed on the right side of the view tree and so draw()
     *  does not punch a hole for a surface that sits ABOVE the window
     *  (AOSP draw() only punches when !isAboveParent()). With no per-surface
     *  layer in the runtime (the default until the native half lands) nothing
     *  reads it, exactly as before. */
    private volatile boolean mZOrderOnTop;
    public void setZOrderOnTop(boolean onTop) { mZOrderOnTop = onTop; }
    /** AOSP setZOrderMediaOverlay (:638) picks MEDIA_OVERLAY vs MEDIA sublayer;
     *  both are below the window, so for this runtime it only clears onTop the
     *  way AOSP's own body does (it resets mRequestedSubLayer below the window). */
    public void setZOrderMediaOverlay(boolean isMediaOverlay) { mZOrderOnTop = false; }
    /** Does this surface sit ABOVE its window (setZOrderOnTop)? Read by
     *  WebView.macapkBackdropFor: a below-window SurfaceView punches a hole
     *  through everything its window drew before it (AOSP draw()), an
     *  above-window one does not. */
    public final boolean macapkIsZOrderOnTop() { return mZOrderOnTop; }

    // ---- DETECT: a renderer drawing for an activity that is STOPPED ---------
    // On a device a stopped activity's window is hidden and its SurfaceView's
    // surface destroyed (GLThread.surfaceDestroyed -> mHasSurface=false), so its
    // renderer cannot draw and nothing of it can reach the screen. Ours keeps
    // the view only as long as its MTKView ticks; if a renderer ever draws past
    // the stop, this says so ONCE per view, in a line debug/owner_issue_scan.py
    // reads as GL-BEHIND-TOP (the GD Meltdown "game still animating under its
    // own ad" report, docs/gd-meltdown-interstitial-hidden-window_2026-10-01.md).
    // Called by BOTH render tiers -- GLSurfaceView's setRenderer path and the
    // reflective path an engine's forked GLSurfaceView (own GLThread) is driven
    // by -- on EVERY frame a renderer is driven: the measured post-stop shape
    // is one or two frames, which a sampled check would mostly miss. Cost once
    // the owning Activity is resolved: one map lookup.
    private boolean macapkBehindTopSaid;
    private android.app.Activity macapkBehindTopActivity;
    private boolean macapkBehindTopNoActivity;
    /** The Activity that owns this view, resolved once (null: none, e.g. a view
     *  built on an Application context). */
    private android.app.Activity macapkOwnerActivity() {
        android.app.Activity a = macapkBehindTopActivity;
        if (a != null || macapkBehindTopNoActivity) return a;
        android.content.Context c = getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity)) {
            android.content.Context b = ((android.content.ContextWrapper) c).getBaseContext();
            if (b == c) break;
            c = b;
        }
        if (c instanceof android.app.Activity) return macapkBehindTopActivity = (android.app.Activity) c;
        macapkBehindTopNoActivity = true;
        return null;
    }
    /** Is this view's Activity STOPPED under another one (ActivityHost)? */
    protected final boolean macapkOwnerStoppedUnderTop() {
        try {
            android.app.Activity a = macapkOwnerActivity();
            return a != null && com.macapk.loader.ActivityHost.macapkIsStoppedUnderTop(a);
        } catch (Throwable t) {
            return false;
        }
    }
    protected final void macapkDetectDrawBehindTop(Object renderer) {
        if (macapkBehindTopSaid) return;
        if (macapkOwnerStoppedUnderTop()) {
            macapkBehindTopSaid = true;
            // Tag fixed at "GLSurfaceView": debug/owner_issue_scan.py keys on
            // "[W] GLSurfaceView: GL-BEHIND-TOP" for both render tiers.
            NativeLog.w("GLSurfaceView", "GL-BEHIND-TOP: "
                + (renderer != null ? renderer.getClass().getName() : getClass().getName())
                + " drew a frame while its activity " + macapkOwnerActivity().getClass().getName()
                + " is STOPPED under another activity (AOSP: hidden window, destroyed"
                + " surface; nothing of it may reach the screen)");
        }
    }

    /** Per AOSP SurfaceView.setSurfaceLifecycle: governs whether the Surface
     *  tracks view visibility (destroyed/recreated on hide/show, the
     *  SURFACE_LIFECYCLE_DEFAULT / _FOLLOWS_VISIBILITY behavior) or window
     *  attachment (created once at first attach, kept alive until detach,
     *  _FOLLOWS_ATTACHMENT). Our native MTKView handle is allocated once in
     *  the constructor and lives for this View's whole lifetime regardless
     *  of visibility (see class header comment), which already matches the
     *  observable behavior of FOLLOWS_ATTACHMENT -- so there is no separate
     *  code path to switch on here. This is a store-and-report-back, not a
     *  behavior change: it exists so the call resolves instead of throwing
     *  NoSuchMethodError (nothing in our SDK_INT report gates it away). */
    public static final int SURFACE_LIFECYCLE_DEFAULT = 0;
    public static final int SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY = 1;
    public static final int SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT = 2;
    private int mRequestedSurfaceLifecycleStrategy = SURFACE_LIFECYCLE_DEFAULT;
    public void setSurfaceLifecycle(int lifecycleStrategy) {
        mRequestedSurfaceLifecycleStrategy = lifecycleStrategy;
    }

    /** Per AOSP SurfaceView.setSecure: marks the surface so its content is
     *  excluded from screenshots and non-secure displays (backed on real
     *  Android by SurfaceControl.SECURE + Display.FLAG_SECURE). Mac.apk has
     *  no SurfaceFlinger and no screen-capture-exclusion path; the macOS
     *  analog is NSWindow.sharingType = .none in the native Cocoa bridge,
     *  which this file does not own and does not wire up here. Recording
     *  the flag stops the call from throwing NoSuchMethodError; actually
     *  enforcing it is a follow-on native change outside runtime/java. */
    private boolean mIsSecure;
    public void setSecure(boolean isSecure) {
        mIsSecure = isSecure;
    }

    /** Tracks whether the holder's surfaceCreated callback has fired
     *  yet. Per AOSP SurfaceView.java + the public SurfaceHolder.Callback
     *  contract, surfaceCreated MUST fire exactly once per surface
     *  lifetime (before any surfaceChanged), and surfaceChanged fires on
     *  every dimension/format change. MTKView calls macapkSurfaceChanged
     *  repeatedly when the layer resizes, so we latch this flag to keep
     *  surfaceCreated single-shot. */
    private volatile boolean holderSurfaceCreatedFired;

    /** v1.0.572: surface size recorded when the first macapkSurfaceChanged
     *  arrived before the holder dispatch could complete (no callbacks
     *  registered yet, or a callback rejected the delivery -- see
     *  macapkTryFireHolderCreated). macapkDrawFrame retries the deferred
     *  surfaceCreated dispatch each frame until it succeeds. */
    private int pendingSurfaceW;
    private int pendingSurfaceH;
    private long surfaceDeferLogCount;

    /** AOSP delivers surfaceCreated as a main-looper message, which cannot
     *  run before onResume returns -- so an app's onCreate-time setup
     *  (holder callbacks, native lib + handler registration) is always in
     *  place first. Our dispatch arrives from the Cocoa render thread and
     *  can land mid-onCreate. Readiness: someone is listening (the app's
     *  addCallback ran), OR the launch burst finished (nothing more will
     *  register; also the cue for zero-callback engines so focus delivery
     *  still happens). Deliberately NOT resumed-only: apps whose onCreate
     *  blocks on their own engine reaching the window (Minecraft's
     *  MainActivity) would deadlock -- engine waits for INIT_WINDOW,
     *  INIT_WINDOW waits for surfaceCreated, surfaceCreated would wait for
     *  onResume, onResume waits for onCreate, onCreate waits for engine.
     *
     *  REFUTED 2026-09-27, kept for the gate-OFF path: MC's onCreate reaches
     *  requestPushPermission (bytecode 351, well past
     *  nativeWaitCrashManagementSetupComplete at 121) with NO surface (MC log
     *  2026-09-26 19:16, line 3024 before holder.fireCreated at 3172); the
     *  v1.0.572 spin was the SIGSYS Breakpad self-recursion fixed that same
     *  session. AOSP cannot deliver surfaceCreated before RESUMED:
     *  handleResumeActivity runs performResumeActivity (ActivityThread.java:4995)
     *  before wm.addView (:5048), and SurfaceView calls surfaceCreated from
     *  updateSurface on the ViewRootImpl pre-draw (SurfaceView.java:213,
     *  1102-1108). And this early arm is what CREATES the AGDK handshake
     *  deadlock the v1.0.575 main-queue drain works around: android_main gets
     *  INIT_WINDOW before START, starts its window-gated boot, and parks in a
     *  runOnUiThread FutureTask just as the main thread parks in
     *  onStartNative. MACAPK_AOSP_SURFACE_ORDER=1 makes readiness "this
     *  view's activity window has been added" (first visible RESUMED,
     *  latched: ActivityHost.macapkWindowAdded), callbacks or not, so the
     *  cycle cannot form and a paused-but-visible activity (a translucent
     *  permission prompt on top) still gets its surface; default OFF until
     *  the protected sweep, boottime, Smash Hit and MC BLANK(d=1) are
     *  measured with it ON.
     *  docs/agdk-gameactivity-native-handshake-retired_2026-09-27.md. */
    private static final boolean sAospSurfaceOrder =
        "1".equals(System.getenv("MACAPK_AOSP_SURFACE_ORDER"));
    // MACAPK_TRACE_SURFACE_DISPATCH=1: name WHICH arm decided each dispatch.
    // Added for the v1.0.1135 BLANK(d=1) failure (~1 launch in 6), where MCPE
    // gains the graphics context only ONCE, never receives the second
    // macapkSurfaceChanged carrying the real ANativeWindow, and so never creates
    // its own EGL display -- our ANGLE path then presents an empty surface
    // forever at a healthy frame rate. See
    // docs/mcpe-blank-uniform-frame-surface-handover_2026-08-31.md.
    //
    // Logged ONLY on a TRANSITION of the (hasCallbacks, resumed, result) triple,
    // never per frame: this decision is evaluated on the Cocoa render thread
    // every frame, and the failure is an ordering RACE, so a per-frame log would
    // change the very timing it is meant to measure (this project has measured
    // that observer effect repeatedly -- heavy tracing took MCPE to 0/6 crosses).
    private static final boolean MACAPK_TRACE_DISPATCH =
        System.getenv("MACAPK_TRACE_SURFACE_DISPATCH") != null;
    private String macapkLastDispatchState;

    private boolean macapkHolderDispatchReady() {
        boolean cb = holder.hasCallbacks();
        boolean resumed;
        boolean res;
        if (cb && !sAospSurfaceOrder) {
            resumed = false; res = true;
        } else {
            try {
                // ON: THIS view's activity window has been added (AOSP
                // mWindowAdded, latched at its first visible resume and kept
                // across a later pause), not "the top activity is resumed now".
                resumed = sAospSurfaceOrder
                    ? com.macapk.loader.ActivityHost.macapkWindowAdded(getContext())
                    : com.macapk.loader.ActivityHost.macapkIsCurrentActivityResumed();
                res = resumed;
            } catch (Throwable t) {
                resumed = false; res = true;   // harness/embedded runs without ActivityHost
            }
        }
        if (MACAPK_TRACE_DISPATCH) {
            String st = cb + "/" + resumed + "/" + res;
            if (!st.equals(macapkLastDispatchState)) {
                macapkLastDispatchState = st;
                NativeLog.i(TAG, "[surface-dispatch] ready? hasCallbacks=" + cb
                    + " resumed=" + resumed + " -> " + res
                    + " on " + getClass().getName());
            }
        }
        return res;
    }

    /** Attempt the one-shot surfaceCreated + mandatory first surfaceChanged
     *  dispatch, then window focus. Returns false WITHOUT latching when a
     *  callback rejected the delivery -- typically AGDK GameActivity's
     *  surfaceCreated calling onSurfaceCreatedNative before
     *  initializeNativeCode has registered the native methods
     *  (UnsatisfiedLinkError). The caller keeps the size pending and
     *  macapkDrawFrame retries next frame, converging as soon as the app's
     *  onCreate finishes registering. Real Android needs no retry because
     *  surfaceCreated is a post-onResume main-looper message; the retry
     *  reconstructs that guarantee under our concurrent dispatch. */
    /** v1.0.572: focus is pending until the activity's launch burst
     *  completes. Real Android delivers onWindowFocusChanged(true) after
     *  onResume (and after the surface callbacks); delivering it mid-
     *  onCreate reached Minecraft's MainActivity.onWindowFocusChanged
     *  before its onCreate had set `this.platform` -> NPE, and the once-
     *  guarded delivery latch would have consumed GAINED_FOCUS for the
     *  whole session (focus-gated native loops then never render). The
     *  surface-before-focus ordering Unity depends on is preserved: focus
     *  fires only after holderSurfaceCreatedFired. */
    private boolean pendingFocusDelivery;

    /** Per-frame log counters. These two branches run on EVERY frame for the
     *  whole life of the app, so an unconditional line here is ~60 lines/second
     *  of noise. MEASURED 2026-08-13 on Vector Pinball: 4,117 of 4,968 log lines
     *  (83%) were "focus-gate: not pending" -- enough to swamp
     *  debug/logdigest.py, which is what sessions read instead of the raw log.
     *  Rate-limited on the same 1 / 60 / every-600 cadence the deferred-surface
     *  logging in this file already uses: the first occurrences are the ones
     *  that carry information, and the periodic one proves it is still true. */
    private long focusNotPendingLogCount;
    private long focusDeferredLogCount;

    private static boolean macapkLogNth(long n) {
        return n == 1 || n == 60 || (n % 600) == 0;
    }

    private void macapkDeliverFocusIfResumed() {
        if (!pendingFocusDelivery) {
            if (macapkLogNth(++focusNotPendingLogCount)) {
                NativeLog.i(TAG, "focus-gate: not pending (already delivered or"
                    + " never armed) x" + focusNotPendingLogCount);
            }
            return;
        }
        try {
            if (!com.macapk.loader.ActivityHost.macapkIsCurrentActivityResumed()) {
                // Stays armed on purpose: a later traversal retries. Logged
                // because "armed but never resumed" and "delivered" used to be
                // indistinguishable in the log, which is how a dropped
                // surface-ready focus went unnoticed.
                if (macapkLogNth(++focusDeferredLogCount)) {
                    NativeLog.i(TAG, "focus-gate: DEFERRED, activity not resumed"
                        + " yet x" + focusDeferredLogCount);
                }
                return;
            }
        } catch (Throwable t) { /* harness: treat as resumed */ }
        pendingFocusDelivery = false;
        try {
            // ForSurface, not the plain call: ViewCanvasDriver may already have
            // delivered focus for a GL-less-LOOKING tree (our SurfaceView had
            // not been added yet), which would make the plain call a no-op via
            // its once-per-activity guard. This is the delivery that actually
            // has a surface behind it, so it must not be the one that gets
            // dropped. See ActivityHost.macapkDeliverWindowFocusForSurface.
            com.macapk.loader.ActivityHost.macapkDeliverWindowFocusForSurface();
        } catch (Throwable t) {
            NativeLog.e(TAG, "deliverWindowFocus threw: " + t);
        }
    }

    /** Converge the DEFERRED surfaceCreated dispatch and the window-focus
     *  delivery. Runs once per frame, and must run for EVERY SurfaceView --
     *  including one whose subclass shortcuts the draw path.
     *
     *  WHY IT IS A SEPARATE METHOD (v1.0.964). It used to be inlined in
     *  macapkDrawFrame, which made it collateral damage of an unrelated
     *  optimisation: GLSurfaceView returns early from BOTH macapkDrawFrame
     *  and macapkSurfaceChanged once it owns a Renderer, so from the frame
     *  the app called setRenderer() onwards, super was never reached again
     *  and the deferred delivery was orphaned FOREVER. On real Android the
     *  two things are unrelated -- window focus comes from ViewRootImpl and
     *  has nothing to do with who draws the frame -- so skipping it with the
     *  draw work is a lifecycle divergence, not an optimisation.
     *
     *  MEASURED on Vector Pinball (JDWP, 55s settled run): the first
     *  macapkSurfaceChanged landed mid-onCreate with zero holder callbacks
     *  and an unresumed activity, so it deferred; two frames later the app
     *  called setRenderer() and the base drawFrame stopped being reached.
     *  onWindowFocusChanged(true) was therefore NEVER delivered, so
     *  BouncyActivity.unpauseGame() -> FieldDriver.start() never ran, its
     *  simulation thread did not exist at all, and FieldViewManager's
     *  cachedScale/cachedHeight sat at their Java defaults of 0.0 -- which
     *  collapses every world coordinate onto one pixel and made the GL
     *  table draw 560 zero-area triangles. See
     *  docs/glsurfaceview-window-focus-never-delivered_2026-08-13.md.
     *
     *  `widthPx`/`heightPx` seed the pending size for a caller that reached
     *  us WITHOUT going through macapkSurfaceChanged's own deferral path
     *  (a subclass that installed its renderer before the first surface
     *  callback, so super.macapkSurfaceChanged never ran); pass 0 to mean
     *  "nothing new to record". */
    /** The last surface size macapkConvergeSurfaceLifecycle saw after the
     *  holder's surfaceCreated (0 = none yet). v1.0.1637. */
    private int macapkConvergedW, macapkConvergedH;

    protected final void macapkConvergeSurfaceLifecycle(int widthPx, int heightPx) {
        // The renderer path (GLSurfaceView) never reaches macapkSurfaceChanged
        // below once it owns a Renderer, so until its native entry records the
        // display itself (macapkNoteNativeDisplaySize) this per-frame size is
        // the only one there is. Never after that: it is whatever the subclass
        // last stored, and a holder callback can store the view's own size
        // there (a GLSurfaceView registered as its own holder's Callback, as
        // AOSP GLSurfaceView.init does), which then sized the whole widget
        // tree from the GL view.
        if (!macapkDisplayFromNative) macapkNoteDisplaySize(widthPx, heightPx);
        if (!holderSurfaceCreatedFired) {
            if (pendingSurfaceW <= 0 && widthPx > 0 && heightPx > 0) {
                pendingSurfaceW = widthPx;
                pendingSurfaceH = heightPx;
            }
            // v1.0.572: deferred surfaceCreated retry. The first
            // macapkSurfaceChanged can arrive while the activity is still
            // inside onCreate (Cocoa render thread vs activity thread), before
            // the app registered its SurfaceHolder.Callback or its native
            // surface handlers. Retry each frame; macapkTryFireHolderCreated
            // refuses to latch until every callback accepts the delivery, so
            // this converges the moment the app's setup completes.
            if (pendingSurfaceW > 0
                    && macapkHolderDispatchReady()
                    && macapkTryFireHolderCreated(pendingSurfaceW, pendingSurfaceH)) {
                NativeLog.i(TAG, "deferred surfaceCreated fired on "
                    + getClass().getName() + " (listeners/native became ready)");
            }
        } else if (widthPx > 0 && heightPx > 0
                && (widthPx != macapkConvergedW || heightPx != macapkConvergedH)) {
            // v1.0.1637: a LATER size change -- a window resize -- on the
            // renderer path. GLSurfaceView.macapkSurfaceChanged drives its
            // Renderer directly and returns without super, so after the first
            // surface nothing re-fired the HOLDER: getSurfaceFrame() (which
            // sizes the ViewCanvasDriver widget overlay) and every app
            // SurfaceHolder.Callback kept the launch size forever. AOSP fires
            // surfaceChanged on every dimension change (SurfaceView
            // .updateSurface). Keyed on the SIZE THIS PATH LAST SAW, not on the
            // holder frame: an app's setFixedSize writes the frame too, and a
            // frame comparison would fight it every frame. Recorded before
            // calling out, so GLSurfaceView's own surfaceChanged ->
            // macapkSurfaceChanged re-entry sees an equal size and stops.
            macapkConvergedW = widthPx;
            macapkConvergedH = heightPx;
            if (!holder.macapkFixedSize) {
                // MACAPK_SV_VIEW_SIZE: the HOLDER's size for this display size,
                // which is the display size itself unless the view does not
                // fill its window (see macapkHolderSurfaceSize). Gate off:
                // exactly (widthPx, heightPx), as before.
                int[] s = macapkHolderSurfaceSize(widthPx, heightPx);
                holder.fireChanged(0, s[0], s[1]);
            }
        } else if (sViewSizeGate) {
            macapkConvergeHolderToLayout();
        }
        // Focus deferred at surface-dispatch time (activity not yet resumed)
        // is delivered here once the launch burst completes.
        macapkDeliverFocusIfResumed();
    }

    // ---- SurfaceHolder.Callback runs on the UI thread (MACAPK_SV_UI_THREAD_CALLBACKS)
    //
    // AOSP delivers every SurfaceHolder.Callback from ViewRootImpl.performTraversals
    // -> SurfaceView.updateSurface, i.e. ON THE UI THREAD (android14 SurfaceView
    // .java updateSurface: "callbacks = getSurfaceCallbacks(); for (SurfaceHolder
    // .Callback c : callbacks) c.surfaceCreated(mSurfaceHolder);" inside the
    // traversal), after onResume. Ours arrive from the Cocoa render thread
    // (drawInMTKView -> macapkDrawFrame -> macapkSurfaceChanged), and code that
    // pins a thread identity at construction dies there. MEASURED on Organic
    // Maps (lanes19 media-playback, 2026-09-29): MapView's surfaceCreated ->
    // Framework::CreateDrapeEngine -> routing_session.cpp:163 IsActive():
    //     CHECK(m_threadChecker.CalledOnOriginalThread())
    // -> guest abort(6) with drawInMTKView at the bottom of the stack, because
    // the Framework was built on the Android main thread in Application.onCreate.
    // So when a holder dispatch starts OFF the UI thread while the real main loop
    // runs, the whole dispatch (surfaceCreated -> surfaceChanged -> the AGDK
    // global-layout kick -> focus, in that order) is POSTED to the UI thread
    // through an owner-only Handler, and later surfaceChanged/surfaceDestroyed
    // follow the same queue, so the AOSP order is kept. The retry contract of
    // fireCreatedTolerant is unchanged: a rejected delivery does not latch and
    // the next frame posts it again (one in flight at a time).
    // =0: every callback inline on the calling thread, exactly as before.
    static final boolean SV_UI_THREAD_CALLBACKS =
        !"0".equals(System.getenv("MACAPK_SV_UI_THREAD_CALLBACKS"));
    private volatile boolean macapkCreatePosted;
    /** Native arrivals (macapkSurfaceChanged) that landed while the posted
     *  surfaceCreated was in flight, and the LATEST size among them. Guarded by
     *  macapkPostLock so "is a create in flight and not yet latched" and "record
     *  this arrival" are one step, against the posted runnable's "read what was
     *  missed and clear the in-flight flag". */
    private final Object macapkPostLock = new Object();
    private int macapkMissedArrivals;
    private int macapkMissedW, macapkMissedH;

    /** Called for every native arrival that reaches macapkSurfaceChanged before
     *  the holder latched. True when a posted create is in flight and this
     *  arrival was recorded for it (the runnable then delivers its
     *  surfaceChanged after created -> changed, in AOSP order); false when the
     *  caller must handle it as before (nothing in flight, or already latched). */
    private boolean macapkRecordArrivalDuringPostedCreate(int widthPx, int heightPx) {
        synchronized (macapkPostLock) {
            if (!macapkCreatePosted || holderSurfaceCreatedFired) return false;
            macapkMissedArrivals++;
            macapkMissedW = widthPx;
            macapkMissedH = heightPx;
            return true;
        }
    }

    static boolean macapkCallbackOffUiThread() {
        return SV_UI_THREAD_CALLBACKS && android.os.Looper.MACAPK_REAL_LOOPER
            && android.os.Looper.macapkRealMainLoopRunning()
            && !android.os.Looper.macapkIsActivityThread();
    }
    static void macapkPostToUiThread(Runnable r) {
        android.os.Handler.macapkCreateOwnerOnly(android.os.Looper.getMainLooper()).post(r);
    }

    private boolean macapkTryFireHolderCreated(int widthPx, int heightPx) {
        if (macapkCallbackOffUiThread()) {
            // Not latched here: the posted dispatch latches when every callback
            // accepted it; until then each frame's retry sees the flag and waits.
            synchronized (macapkPostLock) {
                if (macapkCreatePosted) return false;
                macapkCreatePosted = true;
                macapkMissedArrivals = 0;
            }
            macapkPostToUiThread(() -> {
                try {
                    if (!holderSurfaceCreatedFired
                            && macapkTryFireHolderCreatedHere(widthPx, heightPx)) {
                        NativeLog.i(TAG, "surfaceCreated fired on the UI thread on "
                            + getClass().getName() + " (" + widthPx + "x" + heightPx + ")");
                        macapkDeliverMissedArrivals();
                    }
                } catch (Throwable t) {
                    NativeLog.e(TAG, "UI-thread surfaceCreated dispatch threw: " + t);
                } finally {
                    synchronized (macapkPostLock) {
                        macapkCreatePosted = false;
                        macapkMissedArrivals = 0;
                    }
                }
            });
            return false;
        }
        return macapkTryFireHolderCreatedHere(widthPx, heightPx);
    }

    /** Runs on the UI thread right after the posted create latched
     *  (created -> changed(first arrival) -> global-layout -> focus). Every native
     *  arrival recorded while it was in flight fires its own surfaceChanged now,
     *  at the latest recorded size, once per arrival: the inline path fires one
     *  changed per arrival (MCPE's handover depends on the SECOND arrival
     *  reaching its holder), so the posted path must not fold them into one or
     *  lose them. An arrival after the latch takes the fired-branch of
     *  macapkSurfaceChanged instead (fireChanged posts behind this runnable),
     *  so no arrival is counted twice. */
    private void macapkDeliverMissedArrivals() {
        int n, w, h;
        synchronized (macapkPostLock) {
            n = macapkMissedArrivals; w = macapkMissedW; h = macapkMissedH;
            macapkMissedArrivals = 0;
        }
        for (int i = 0; i < n; i++) {
            int[] s = macapkHolderSurfaceSize(w, h);
            holder.fireChanged(0, s[0], s[1]);
        }
        if (n > 0) {
            NativeLog.i(TAG, "UI-thread dispatch: " + n + " native arrival(s) during the posted"
                + " surfaceCreated delivered as surfaceChanged " + w + "x" + h);
        }
    }

    private boolean macapkTryFireHolderCreatedHere(int widthPx, int heightPx) {
        if (!holder.fireCreatedTolerant()) return false;
        holderSurfaceCreatedFired = true;
        pendingSurfaceW = 0;
        pendingSurfaceH = 0;
        macapkConvergedW = widthPx;   // the resize re-fire's baseline (v1.0.1637)
        macapkConvergedH = heightPx;
        // MACAPK_SV_VIEW_SIZE: the view's laid-out size when it does not fill
        // its window (AOSP updateSurface :985-988); the display size otherwise
        // and with the gate off, as before.
        int[] firstSize = macapkHolderSurfaceSize(widthPx, heightPx);
        holder.fireChanged(0, firstSize[0], firstSize[1]);
        // v1.0.575: AGDK content-rect delivery. GameActivity registers an
        // OnGlobalLayoutListener on THIS SurfaceView's ViewTreeObserver (in
        // onCreate); its onGlobalLayout reads getWidth()/getHeight() +
        // getLocationInWindow and calls onContentRectChangedNative(handle,x,y,w,h)
        // when the rect changes from its cached (0,0,0,0). That command
        // (APP_CMD_CONTENT_RECT_CHANGED) is what android_main / RenderDragon needs
        // to size its viewport and build the main-menu scene; without it the game
        // parks after its loading frames (cohtml TaskChannel idle downstream).
        // ViewCanvasDriver dispatches global-layout on the ROOT observer per frame
        // (and View.macapkDispatchAttachedToWindow now merges this view's floating
        // observer into the root), but drive it directly here too so the listener
        // fires ONCE with a real rect the instant the surface goes live -- not only
        // on some later incidental requestLayout. Lay the view out to the real
        // (pixel) surface size first so the rect is non-zero (getWidth()==right-left).
        // Generic: any SurfaceView whose consumer watches global-layout benefits;
        // engines that ignore it (cocos2d-x GLSurfaceView) are unaffected.
        try {
            if (getWidth() == 0 || getHeight() == 0) {
                // Not a layout pass: the size snapshot (setFrame below) must
                // not read this as where the tree placed the view.
                macapkSelfLayout = true;
                try { layout(0, 0, widthPx, heightPx); }
                finally { macapkSelfLayout = false; }
            }
            android.view.ViewTreeObserver vto = getViewTreeObserver();
            if (vto != null) vto.dispatchOnGlobalLayout();
        } catch (Throwable t) {
            NativeLog.e(TAG, "content-rect global-layout dispatch threw: " + t);
        }
        pendingFocusDelivery = true;
        macapkDeliverFocusIfResumed();
        return true;
    }

    /** v1.0.70: process-wide overlay driver. When an Activity's
     *  contentView contains BOTH a SurfaceView descendant AND plain
     *  widgets (Buttons, TextViews, etc.) as siblings (Bouncy: GL
     *  field view + button panel; AndEngine: rendering view +
     *  HUD), the SurfaceView's MTKView handles GL but the plain
     *  widgets need a separate Canvas paint pass. Activity.set-
     *  ContentView wires that here by calling
     *  macapkSetOverlayDriver; every SurfaceView instance then
     *  runs the overlay's paint after its own renderer dispatch.
     *  The overlay is process-wide (static) because there's only
     *  one active Activity content view at a time, and the
     *  SurfaceView class doesn't know which Activity owns it. */
    private static MetalSurfaceCallbacks staticOverlayDriver;
    private static int staticOverlayW;
    private static int staticOverlayH;
    private static boolean staticOverlaySurfaceCreatedFired;

    /** Called from Activity.setContentView when a Canvas overlay is
     *  needed on top of an existing GL view. Wraps the driver's
     *  macapkSurfaceChanged once (when first set) and lets each
     *  SurfaceView pull it on every macapkDrawFrame. */
    public static void macapkSetOverlayDriver(MetalSurfaceCallbacks driver,
                                                  int widthPx, int heightPx) {
        staticOverlayDriver = driver;
        staticOverlayW = widthPx > 0 ? widthPx : 800;
        staticOverlayH = heightPx > 0 ? heightPx : 600;
        staticOverlaySurfaceCreatedFired = false;
        if (driver != null) {
            final MetalSurfaceCallbacks d = driver;
            final Runnable fire = () -> {
                if (staticOverlayDriver != d) return;   // superseded before the post ran
                if (staticOverlaySurfaceCreatedFired) return;   // macapkDriveOverlay already fired it
                try {
                    // v1.0.1636: send the CURRENT size, not the size captured
                    // when this runnable was created. macapkDriveOverlay may have
                    // recorded the real surface size (808x1796) between the post
                    // and the run, while the captured guess was the stale
                    // phoneWidth*2 (836x1860); firing the guess left the overlay
                    // permanently at the wrong size (the "self-corrects" note at
                    // the demote path was false for this path).
                    final int w = staticOverlayW, h = staticOverlayH;
                    d.macapkSurfaceChanged(w, h);
                    staticOverlaySurfaceCreatedFired = true;
                    NativeLog.i(TAG, "overlay driver registered " + w + "x" + h);
                } catch (Throwable t) {
                    NativeLog.e(TAG, "overlay driver surfaceChanged threw: " + t);
                }
            };
            // v1.0.997 rewire hardening (2026-08-18): this is called from
            // Activity.setContentView AND from the rewire's demote path, both
            // of which can sit INSIDE a ViewGroup.addView -> measure/layout
            // dispatch on the activity loop. ViewCanvasDriver.macapkSurface-
            // Changed only self-posts when called OFF the loop; called ON the
            // loop it ran INLINE, which (a) re-entered measure -- Compose
            // rejects it with "performMeasureAndLayout called during measure
            // layout" -- and (b) nulled the driver's frame bitmap out from
            // under the doFrame that was mid-draw (the doFrame NPE this
            // session's independent lane caught). Posting defers it until the
            // current dispatch unwinds; if the post is beaten by a newer
            // registration, macapkDriveOverlay's created-fired backstop still
            // fires it with the then-current size.
            if (android.os.Looper.MACAPK_REAL_LOOPER
                    && android.os.Looper.macapkIsActivityThread()) {
                // OWNER-ONLY (v1.0.1636): fire calls d.macapkSurfaceChanged,
                // which nulls the driver's frame bitmap. It must run on the
                // owner thread, never on the OS-main drain concurrently with a
                // doFrame -- so post it through an owner-only Handler.
                android.os.Handler.macapkCreateOwnerOnly(
                    android.os.Looper.getMainLooper()).post(fire);
            } else {
                fire.run();
            }
        }
    }

    /** Drive the overlay driver's draw + surfaceChanged-on-resize from
     *  any SurfaceView's macapkDrawFrame. Cheap when no driver is
     *  set. */
    /**
     * Derive THIS surface's real pixel size and drive the ViewCanvasDriver
     * overlay with it.
     *
     * WHY THIS IS FACTORED OUT AND `protected` (v1.0.835). The size derivation
     * below is drift-prone -- see the long comment in macapkDrawFrame about
     * points-vs-pixels re-firing macapkSurfaceChanged at the wrong size and
     * corrupting an engine's viewport into the bottom-left corner. It must
     * therefore exist exactly ONCE.
     *
     * It needs to be reachable from GLSurfaceView because of the two-tier
     * renderer dispatch: when a subclass DID call setRenderer(),
     * GLSurfaceView.macapkDrawFrame handles the frame itself and returns
     * WITHOUT calling super, so SurfaceView's prologue -- and with it this
     * overlay drive -- never ran. It composited WindowManager overlays
     * (Dialog/PopupWindow) on that path but not the ViewCanvasDriver overlay,
     * so an app whose real UI is ordinary widgets SHARING a ViewGroup with the
     * GL surface drew nothing at all.
     *
     * Measured (2026-07-29): `grep -c "ViewCanvasDriver.*fire #"` across one
     * census gave sgtpuzzles 9 (overlay driven), bouncy 0, jetpackjoyride 0 --
     * three apps, one variable. Bouncy's GL tier was provably healthy the whole
     * time: 1,800+ frames, glViewport(0,0,808,1796), 3,600 cumulative
     * glDrawElements, err=0x0 throughout.
     *
     * AOSP contract this restores, from the SurfaceView javadoc: "The view
     * hierarchy will take care of correctly compositing ... any siblings ...
     * that would normally appear on top of it."
     */
    protected final int[] macapkDriveOverlayForCurrentSurface() {
        int overlayW, overlayH;
        if (macapkDisplayDecoupled()) {
            // MACAPK_SV_DISPLAY_SIZE (2026-09-26): the overlay is the WINDOW's
            // view tree, so it is sized from the DISPLAY surface this view was
            // last given, never from the holder's frame. That frame is the
            // surface BUFFER: an app's setFixedSize writes it (AOSP
            // setFixedSize :1589-1600 only changes mRequestedWidth/Height,
            // and the window keeps its size), and under MACAPK_SV_VIEW_SIZE it
            // is the view's own size. Measured on Super Jetpack
            // (docs/fleet-unity-constant-gaps_2026-09-26.md §4 step 6):
            // setFixedSize(40,40) laid the whole widget tree out at 40x40.
            int[] d = macapkKnownDisplaySize();
            overlayW = d[0];
            overlayH = d[1];
        } else {
            android.graphics.Rect ownFrame = getHolder().getSurfaceFrame();
            overlayW = ownFrame.width();
            overlayH = ownFrame.height();
            if (overlayW <= 0 || overlayH <= 0) {
                // v1.0.1637: the committed display (a window drag never updates
                // the canvas globals this used to read).
                overlayW = Display.macapkRealWidthPx();
                overlayH = Display.macapkRealHeightPx();
            }
        }
        macapkDriveOverlay(overlayW, overlayH);
        // Returned so the caller can reuse the SAME numbers for the
        // WindowManager overlay instead of re-deriving them.
        return new int[] { overlayW, overlayH };
    }

    private static void macapkDriveOverlay(int currentW, int currentH) {
        MetalSurfaceCallbacks d = staticOverlayDriver;
        if (d == null) return;
        if (!staticOverlaySurfaceCreatedFired
            || currentW != staticOverlayW
            || currentH != staticOverlayH) {
            try {
                staticOverlayW = currentW;
                staticOverlayH = currentH;
                d.macapkSurfaceChanged(currentW, currentH);
                staticOverlaySurfaceCreatedFired = true;
            } catch (Throwable t) {
                NativeLog.e(TAG, "overlay driver surfaceChanged threw: " + t);
            }
        }
        try {
            d.macapkDrawFrame();
        } catch (Throwable t) {
            NativeLog.e(TAG, "overlay driver drawFrame threw: " + t);
        }
    }

    // ======================================================================
    //  Surface GEOMETRY (2026-09-26): which size a surface is given, and where
    //  it is shown. Research: docs/surfaceview-surface-geometry_2026-09-26.md.
    // ======================================================================
    //
    // AOSP (android14-release core/java/android/view/SurfaceView.java):
    //   updateSurface() :985-988
    //       int myWidth = mRequestedWidth;
    //       if (myWidth <= 0) myWidth = getWidth();
    //       int myHeight = mRequestedHeight;
    //       if (myHeight <= 0) myHeight = getHeight();
    //   :1120  c.surfaceChanged(mSurfaceHolder, mFormat, myWidth, myHeight);
    //   and the compositor shows that buffer at mScreenRect, the view's own
    //   window rect (:1040-1043), BELOW the window (mSubLayer =
    //   APPLICATION_MEDIA_SUBLAYER, :200) through a hole draw() punches
    //   (:512-521, clearSurfaceViewPort :579-597). setFrame() is overridden
    //   (:478-482: super.setFrame(...); updateSurface();), so every layout
    //   pass re-evaluates the size.
    //
    // Ours handed the holder the DISPLAY surface (the native drawable) and a
    // renderer the WINDOW box (StatusBarDriver.contentBox*), whatever the
    // view's own size, and a lockCanvas frame was presented over the whole
    // window. Measured (docs/fleet-misc-shatteredpd-retrowars-bobball-bouncy-
    // unciv_2026-09-26.md §1): BobBall's SurfaceView is laid out 3360x1560
    // between two HUD rows but was handed 3360x1636 (board 1.049x too big),
    // and bouncy's GLFieldView is 1226x1524 under a 112px ScoreView but its
    // renderer got 1226x1636 (half-dome bumpers, table 1.074x too big).
    //
    // WHAT CHANGES, and the boundary kept on purpose:
    //  * A view that FILLS its window (laid-out size == the root's) keeps the
    //    pre-change answer exactly. Every native consumer we have (Unity,
    //    AGDK/MCPE, native EGL through ANativeWindow, the ANGLE layer) is built
    //    at that size, so changing it there would split the holder from the
    //    layer it describes. That population (HCR, Unity, MCPE, cocos,
    //    fullscreen Canvas games) is untouched by construction. The residual
    //    AOSP gap -- a filling view in a window with VISIBLE bars is told the
    //    display, AOSP tells it the content box -- is OPEN and belongs with
    //    the native layers.
    //  * A view that does NOT fill its window gets its own laid-out size, as
    //    AOSP does: the holder once it has served a lockCanvas (its frame is
    //    then presented AT the view's rect, see macapkPlaceInDisplay /
    //    macapkPresentThroughSurfaceLayer) and, before that, by the renderer
    //    rule, because its consumer may be GL (macapkHolderSurfaceSize); a
    //    renderer only while the view's
    //    bottom-left corner is the ANGLE layer's. The one GL layer is the
    //    window box and GL's origin is its bottom-left, so glViewport(0, 0,
    //    w, h) then lands exactly on the view (bouncy's shape). A GL view
    //    placed anywhere else (a banner BELOW it) keeps the window box until
    //    the ANGLE layer can follow the view's rect natively: told its real
    //    size it would draw shifted, which is worse than drawing too big.
    //  * setFixedSize is left exactly as it was. AOSP gives the buffer the
    //    fixed size and SCALES it to the view; our native layers cannot scale
    //    a fixed buffer, and Unity requests one -- a holder that reports it
    //    while Unity's layer stays display-sized would render into a corner.
    //    OPEN, with the native layers.
    //
    // The view's size is read from a SNAPSHOT published by setFrame on the
    // thread that lays the tree out, never by calling getWidth() on the render
    // thread: a read racing a relayout could see the new root with the old
    // child, flip a filling view to "not filling" for one frame and hand a
    // latching engine (cocos) a wrong size -- the v1.0.742 failure shape
    // StatusBarDriver.contentBoxHeightPx's comment warns about.
    //
    // BISECT GATES, default ON; =0 restores the exact pre-change path:
    //   MACAPK_SV_VIEW_SIZE      holder / lockCanvas size + where it is shown
    //   MACAPK_SV_VIEW_SIZE_GL   the renderer size (reflected + GLSurfaceView)
    //   MACAPK_SV_DISPLAY_SIZE   the overlay drive and the reflected converge
    //                            read the DISPLAY size, not the holder frame,
    //                            and GLSurfaceView's holder callback does not
    //                            store the holder's size as the display
    //                            (macapkHolderSizeIsBufferSize; forced on while
    //                            either VIEW_SIZE gate is on: a view-sized
    //                            frame cannot size the window)
    //   MACAPK_SV_LATE_RENDERER  a renderer found after the surface still gets
    //                            onSurfaceChanged before its first onDrawFrame
    //   MACAPK_SV_SURFACE_LAYER  present a lockCanvas frame through the
    //                            runtime's per-surface native layer, when the
    //                            runtime has one (NativeCocoa.presentSurface-
    //                            Bitmap; absent until the native half lands)
    //   MACAPK_SV_RESUME_ONCE    (2026-09-27) do NOT re-invoke a reflected
    //                            renderer's handleOnResume() after its first
    //                            onSurfaceCreated; the app's own queued call is
    //                            delivered once, by macapkDrainForkedGlQueue.
    //                            See the comment at the (now =0-only) re-fire in
    //                            macapkReflectedSurfaceChanged and
    //                            docs/cocos2dx-handleonresume-refire-retired_2026-09-27.md
    static final boolean sViewSizeGate =
        !"0".equals(System.getenv("MACAPK_SV_VIEW_SIZE"));
    static final boolean sViewSizeGlGate =
        !"0".equals(System.getenv("MACAPK_SV_VIEW_SIZE_GL"));
    static final boolean sDisplaySizeGate =
        !"0".equals(System.getenv("MACAPK_SV_DISPLAY_SIZE"));
    static final boolean sLateRendererGate =
        !"0".equals(System.getenv("MACAPK_SV_LATE_RENDERER"));
    static final boolean sSurfaceLayerGate =
        !"0".equals(System.getenv("MACAPK_SV_SURFACE_LAYER"));
    static final boolean sResumeOnceGate =
        !"0".equals(System.getenv("MACAPK_SV_RESUME_ONCE"));
    /** MACAPK_SV_GUEST_BELOW (default ON): the native-EGL window layer (what a
     *  native producer gets from ANativeWindow_fromSurface) composites BELOW
     *  its window and the owning SurfaceView punches the hole, as AOSP does;
     *  =0 keeps the legacy slot ABOVE the whole window and no hole. See the
     *  "native window owner" section below. */
    static final boolean sGuestBelowGate =
        !"0".equals(System.getenv("MACAPK_SV_GUEST_BELOW"));

    /** MACAPK_GL_WAIT_FRAME (2026-09-27, default ON): a renderer's FIRST
     *  onSurfaceCreated waits for the view's first layout, as AOSP's
     *  mHaveFrame makes it; =0 restores the old order and the by-name
     *  setScreenWidthAndHeight call. See the section above
     *  macapkRendererMayStart. */
    static final boolean sGlWaitFrameGate =
        !"0".equals(System.getenv("MACAPK_GL_WAIT_FRAME"));

    /** For GLSurfaceView (another package): is MACAPK_GL_WAIT_FRAME on? */
    protected static boolean macapkGlWaitFrame() { return sGlWaitFrameGate; }

    private static boolean macapkDisplayDecoupled() {
        return sDisplaySizeGate || sViewSizeGate || sViewSizeGlGate;
    }

    /** The DISPLAY surface (native drawable) this view was last handed:
     *  (w << 32) | h, 0 = none yet. ONE volatile word, so a reader on another
     *  thread (a lockCanvas drawing thread, the Cocoa rect pull) never pairs a
     *  new width with an old height across a resize. Written by the native
     *  entries (macapkSurfaceChanged, and GLSurfaceView's through
     *  macapkNoteNativeDisplaySize) and, only until one of those has run, by
     *  macapkConvergeSurfaceLifecycle. */
    private volatile long macapkDisplaySize;
    /** A native entry has recorded the display: the per-frame converge size is
     *  no longer taken as the display (see macapkConvergeSurfaceLifecycle). */
    private volatile boolean macapkDisplayFromNative;

    private void macapkNoteDisplaySize(int w, int h) {
        if (w > 0 && h > 0) macapkDisplaySize = ((long) w << 32) | (h & 0xffffffffL);
    }

    /** Record the display size where the NATIVE display arrives. A subclass
     *  that handles the native macapkSurfaceChanged without calling super
     *  (GLSurfaceView with a Renderer) calls this first, before anything it
     *  does can re-enter with a HOLDER's size, which under MACAPK_SV_VIEW_SIZE
     *  is the view's own size and not the window's. */
    protected final void macapkNoteNativeDisplaySize(int w, int h) {
        if (w > 0 && h > 0) {
            macapkNoteDisplaySize(w, h);
            macapkDisplayFromNative = true;
        }
    }

    /** True while the holder's size is its own BUFFER size, which need not be
     *  the display: any MACAPK_SV_* geometry gate on. A subclass that also
     *  hears the holder's callbacks (GLSurfaceView implements
     *  SurfaceHolder.Callback2) must then not take that size as the display.
     *  False with the gates off, where the holder reports the display (or a
     *  setFixedSize the app chose), as before. */
    protected static boolean macapkHolderSizeIsBufferSize() {
        return macapkDisplayDecoupled();
    }

    /** The recorded display {w, h}, or null when none has been recorded. */
    private int[] macapkNotedDisplaySize() {
        long p = macapkDisplaySize;
        if (p == 0L) return null;
        return new int[] { (int) (p >>> 32), (int) p };
    }

    /** The display size to use when this view has not been handed one yet:
     *  the committed display (the same fallback the overlay drive used). */
    private int[] macapkKnownDisplaySize() {
        int[] d = macapkNotedDisplaySize();
        if (d != null) return d;
        return new int[] { Display.macapkRealWidthPx(), Display.macapkRealHeightPx() };
    }

    /** Where the last LAYOUT pass put this view, relative to its window's
     *  root. Immutable, published whole through a volatile field, so the
     *  render thread never sees half of a relayout. */
    private static final class LayoutSnap {
        final int w, h, rootW, rootH, x, y;
        LayoutSnap(int w, int h, int rootW, int rootH, int x, int y) {
            this.w = w; this.h = h; this.rootW = rootW; this.rootH = rootH;
            this.x = x; this.y = y;
        }
    }
    private volatile LayoutSnap macapkLayoutSnap;
    /** True while macapkTryFireHolderCreated lays the view out itself (the
     *  AGDK content-rect kick): that is not the tree placing it. */
    private boolean macapkSelfLayout;

    // ---- MACAPK_GL_WAIT_FRAME: no renderer surface before the view has a frame
    //
    // AOSP (android14-release SurfaceView.java): the OnPreDrawListener sets
    //     mHaveFrame = getWidth() > 0 && getHeight() > 0; updateSurface();  (:213-216)
    // and updateSurface() returns at once `if (!mHaveFrame)` (:961-962), before
    // surfaceCreated (:1108) and surfaceChanged (:1120). GLSurfaceView's GLThread
    // then needs mHasSurface && mWidth > 0 && mHeight > 0 (readyToDraw
    // :1628-1631) before Renderer.onSurfaceCreated (:1541). So on a device the
    // view has ALWAYS been laid out -- and the app's own onSizeChanged has run --
    // before its renderer's onSurfaceCreated. cocos2d-x and axmol are built on
    // that: Cocos2dxGLSurfaceView.onSizeChanged calls
    // mRenderer.setScreenWidthAndHeight(w, h), and onSurfaceCreated hands those
    // two fields to nativeInit(w, h) (javap of GD Lite 2.2.11, GD Meltdown,
    // HCR 1.43/1.68.1/1.8.1 and OpenGD's AxmolGLSurfaceView).
    //
    // Our native surface arrives on the render thread's first tick, BEFORE the
    // tree's first layout (the overlay drive posts that to the owner thread), so
    // the renderer used to be created against a view with no size, and a by-NAME
    // reflective setScreenWidthAndHeight(w, h) (both render paths, every frame on
    // the setRenderer path) stood in for the app's own onSizeChanged. Under this
    // gate the FIRST onSurfaceCreated waits for the frame instead, and the app's
    // own code fills the size, for any engine; the reflective call is gone.
    //
    // What counts as the frame: a layout pass that gives this view a non-empty
    // rect. Not the AGDK content-rect kick (macapkSelfLayout): that lays the
    // view out at the DISPLAY size, which is not where any tree puts it.
    // Two ways a view gets a frame no tree gives it:
    //   * it is its window's content ROOT (a bare GLSurfaceView passed to
    //     setContentView: libGDX, Arc). ViewRootImpl lays the root out at the
    //     window size (performTraversals -> performMeasure/performLayout); here
    //     nothing does, so this view does it itself, at the renderer size, on
    //     the thread that owns the tree.
    //   * a VALVE: FRAME_VALVE_MS after the first RESUMED wait tick with no
    //     frame, a view that has a size anyway (the content-rect kick sized it,
    //     running the app's own onSizeChanged) starts at once, and one with no
    //     size lays itself out the same way; FRAME_VALVE_HARD_MS after it the
    //     renderer starts regardless, WITHOUT a frame (an engine that sizes from
    //     onSizeChanged -- cocos2d-x / axmol -- then gets nativeInit 0x0; the
    //     gate's VALVE-HARD row pins that answer). Logged loudly -- a view
    //     nothing lays out is a tree bug of ours, and a device would show it no
    //     surface. Wall-clock, not render ticks: the tick cadence depends on the
    //     display (preferredFramesPerSecond 120, free-run ~74/s), so a tick
    //     count would mean 1-2 s on one Mac and 1.5-3 s on another. The tick
    //     count is kept for the log only.
    // The flag is a volatile written AFTER super.layout returns, so the app's
    // field writes from onSizeChanged (owner thread) are visible to the render
    // thread that reads the flag before calling onSurfaceCreated.
    // docs/cocos2dx-setscreensize-reflect-retired_2026-09-27.md
    private volatile boolean macapkHaveFrame;
    private volatile boolean macapkFrameValveOpen;
    private volatile boolean macapkOwnLayoutRequested;
    private int macapkFrameWaitTicks;
    private int macapkFrameValveTicks;          // resumed wait ticks, log only
    private long macapkFrameValveStartNs;       // first RESUMED wait tick
    private boolean macapkFrameValveStarted;
    private boolean macapkFrameValveFired;
    /** MACAPK_GL_WAIT_FRAME_VALVE_MS (default 2000): wall-clock ms from the
     *  first resumed wait tick to the VALVE; the hard open is 1.5x that. */
    static final long FRAME_VALVE_MS = macapkValveMs();
    static final long FRAME_VALVE_HARD_MS = FRAME_VALVE_MS * 3 / 2;

    private static long macapkValveMs() {
        String v = System.getenv("MACAPK_GL_WAIT_FRAME_VALVE_MS");
        if (v != null) {
            try { long ms = Long.parseLong(v.trim()); if (ms > 0) return ms; }
            catch (NumberFormatException e) { /* default */ }
        }
        return 2000L;
    }

    @Override
    public void layout(int l, int t, int r, int b) {
        super.layout(l, t, r, b);
        // After super: setFrame, onLayout and the app's onSizeChanged have run.
        if (sGlWaitFrameGate && !macapkSelfLayout && r > l && b > t) macapkHaveFrame = true;
    }

    /** MACAPK_GL_WAIT_FRAME: may this view's renderer get its FIRST
     *  onSurfaceCreated now? True once the view has a frame (see the section
     *  comment). displayW/H is the display surface, used only to size a view
     *  that has to lay itself out. Called from the render tick while waiting;
     *  always true with the gate off. */
    protected final boolean macapkRendererMayStart(int displayW, int displayH) {
        if (!sGlWaitFrameGate || macapkHaveFrame || macapkFrameValveOpen) return true;
        int n = ++macapkFrameWaitTicks;
        if (n == 1) {
            NativeLog.i(TAG, "[frame-gate] renderer of " + getClass().getName()
                + " waits for the view's first layout (AOSP mHaveFrame); view "
                + getWidth() + "x" + getHeight());
        }
        // No parent at all: a bare content root (libGDX / Arc pass their
        // GLSurfaceView straight to setContentView, and we install it with no
        // decor above it; it is not even attached, since nothing attaches a
        // root no ViewCanvasDriver owns). Nothing will ever lay it out.
        if (!macapkOwnLayoutRequested && getParent() == null) {
            macapkRequestOwnLayout(displayW, displayH, true,
                "window content root: nothing above it lays it out (ViewRootImpl's job)");
            if (macapkHaveFrame) return true;   // it ran inline
        }
        boolean resumed = true;
        try { resumed = com.macapk.loader.ActivityHost.macapkIsCurrentActivityResumed(); }
        catch (Throwable t) { /* harness: treat as resumed */ }
        if (!resumed) return false;
        int v = ++macapkFrameValveTicks;
        long now = System.nanoTime();
        if (!macapkFrameValveStarted) {
            macapkFrameValveStarted = true;
            macapkFrameValveStartNs = now;
        }
        long ms = (now - macapkFrameValveStartNs) / 1_000_000L;
        if (!macapkFrameValveFired && ms >= FRAME_VALVE_MS) {
            macapkFrameValveFired = true;
            NativeLog.w(TAG, "[frame-gate] VALVE: " + getClass().getName() + " still has no"
                + " frame after " + ms + " ms / " + v + " resumed ticks (view " + getWidth() + "x"
                + getHeight() + ", attached=" + macapkAttached + ") -- nothing laid it out");
            if (getWidth() > 0 && getHeight() > 0) {
                // Sized by a pass that is not a tree frame (the content-rect
                // kick in macapkTryFireHolderCreated): the app's own
                // onSizeChanged has run with that size. Start now.
                macapkFrameValveOpen = true;
                return true;
            }
            if (!macapkOwnLayoutRequested) macapkRequestOwnLayout(displayW, displayH, false, "valve");
            if (macapkHaveFrame) return true;   // it ran inline
        }
        if (ms >= FRAME_VALVE_HARD_MS) {
            macapkFrameValveOpen = true;
            NativeLog.w(TAG, "[frame-gate] VALVE OPEN: " + getClass().getName() + " still has no"
                + " frame after " + ms + " ms / " + v + " resumed ticks (the owner never ran its own"
                + " layout) -- renderer starts WITHOUT a frame: "
                + (getWidth() > 0 && getHeight() > 0
                    ? "the view has only a non-tree size " + getWidth() + "x" + getHeight()
                    : "engines that size from onSizeChanged (cocos2d-x/axmol) get nativeInit 0x0"));
            return true;
        }
        return false;
    }

    /** Lay this view out as ViewRootImpl lays out a window root -- measure
     *  EXACTLY at the renderer box, then layout -- but AT THE RENDERER BOX,
     *  not at the measured size ViewRootImpl uses (android14 :4258
     *  host.layout(0, 0, getMeasuredWidth(), getMeasuredHeight())): a bare
     *  root has no content frame to centre a smaller measured size in, so it
     *  would sit un-centred at 0,0 and touch hit-testing could move. The two
     *  differ only for a root whose onMeasure applies a resolution policy
     *  (AndEngine RatioResolutionPolicy, libGDX RatioResolutionStrategy); see
     *  OPEN QUESTIONS in docs/cocos2dx-setscreensize-reflect-retired_2026-09-27.md.
     *  On the thread that owns the tree (the v1.0.1636 owner-only rule),
     *  inline where there is no separate owner thread. A real layout pass, so
     *  it sets the frame. */
    private void macapkRequestOwnLayout(int displayW, int displayH, final boolean asRoot,
                                        final String why) {
        macapkOwnLayoutRequested = true;
        if (displayW <= 0 || displayH <= 0) {
            int[] d = macapkKnownDisplaySize();
            displayW = d[0];
            displayH = d[1];
        }
        final int[] sz = macapkRendererSurfaceSize(displayW, displayH);
        Runnable run = () -> {
            try {
                if (macapkHaveFrame) return;   // a tree pass got here first
                if (asRoot && getParent() != null) {
                    // Added to a tree between the tick and this post (a view is
                    // the MTKView delegate from its constructor, before the app's
                    // addView): that tree lays it out. Ask again if it does not.
                    macapkOwnLayoutRequested = false;
                    return;
                }
                measure(MeasureSpec.makeMeasureSpec(sz[0], MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(sz[1], MeasureSpec.EXACTLY));
                layout(0, 0, sz[0], sz[1]);
                NativeLog.i(TAG, "[frame-gate] laid out " + getClass().getName() + " at "
                    + sz[0] + "x" + sz[1] + " (" + why + ")");
            } catch (Throwable t) {
                NativeLog.e(TAG, "[frame-gate] own layout threw: " + t);
            }
        };
        // Posted only while the real main loop runs (so the post WILL run) and
        // from any thread but the owner; inline otherwise -- on the owner, in
        // the legacy loop model, and in a bare JVM (the offline gates).
        if (android.os.Looper.MACAPK_REAL_LOOPER && android.os.Looper.macapkRealMainLoopRunning()
                && !android.os.Looper.macapkIsActivityThread()) {
            android.os.Handler.macapkCreateOwnerOnly(android.os.Looper.getMainLooper()).post(run);
        } else {
            run.run();
        }
    }

    /** AOSP SurfaceView overrides setFrame (android14 :478-482) to run
     *  updateSurface() after every layout pass; this is the same hook,
     *  publishing where the pass put the view. The parent chain is already
     *  placed when a child's setFrame runs (layout is top-down), so the root's
     *  size and this view's offset in it are final here. */
    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        boolean changed = super.setFrame(left, top, right, bottom);
        if (sViewSizeGate || sViewSizeGlGate) macapkSnapLayout(right - left, bottom - top);
        return changed;
    }

    private void macapkSnapLayout(int w, int h) {
        try {
            View root = getRootView();
            if (macapkSelfLayout || getParent() == null || root == null || root == this) {
                if (!macapkSelfLayout) macapkLayoutSnap = null;
                return;
            }
            // The offset in the root by walking the parents, NOT
            // getLocationInWindow: that answers 0,0 for a view that is not
            // attached yet (AOSP transformFromViewToWindowSpace's first branch),
            // and a tree may be laid out before it is attached.
            int x = 0, y = 0;
            View v = this;
            while (v != null && v != root) {
                x += v.getLeft();
                y += v.getTop();
                ViewParent p = v.getParent();
                if (!(p instanceof View)) break;
                View pv = (View) p;
                x -= pv.getScrollX();
                y -= pv.getScrollY();
                v = pv;
            }
            macapkLayoutSnap = new LayoutSnap(w, h, root.getWidth(), root.getHeight(), x, y); /*SNAP-OFFSET*/
        } catch (Throwable t) {
            macapkLayoutSnap = null;   // a half-built tree: fall back to the legacy size
        }
    }

    /** The view's laid-out size when the tree placed it and it does NOT fill
     *  its window; null otherwise (= keep the legacy answer). {@code gl} adds
     *  the single-ANGLE-layer constraint described in the section comment. */
    private int[] macapkNonFillingViewSize(int displayW, int displayH, boolean gl) {
        LayoutSnap s = macapkLayoutSnap;
        if (s == null || !macapkAttached) return null;
        if (s.w <= 0 || s.h <= 0 || s.rootW <= 0 || s.rootH <= 0) return null;
        if (s.w == s.rootW && s.h == s.rootH) return null;          /*FILLS*/
        if (gl) {
            if (s.w > s.rootW || s.h > s.rootH) return null;
            // The ANGLE layer is the window box; the root must BE that box for
            // "the root's bottom-left" to be GL's origin.
            int boxW = com.macapk.loader.StatusBarDriver.contentBoxWidthPx(this, displayW);
            int boxH = com.macapk.loader.StatusBarDriver.contentBoxHeightPx(this, displayH);
            if (s.rootW != boxW || s.rootH != boxH) return null;
            if (s.x != 0 || s.y + s.h != s.rootH) return null;       /*GL-ANCHOR*/
        }
        return new int[] { s.w, s.h };
    }

    /** The size the HOLDER reports (surfaceChanged, getSurfaceFrame,
     *  lockCanvas) for a display surface of displayW x displayH.
     *
     *  WHICH RULE, and why it depends on lockCanvas. A holder does not say who
     *  consumes it. A Canvas consumer's frame is shown AT the view's rect (see
     *  "where a lockCanvas frame is shown"), so it can take the view's size
     *  wherever the view is. A GL/EGL consumer -- a forked GLThread, SDL, a
     *  native EGL engine reaching the surface through ANativeWindow -- draws
     *  into the ONE window-box GL layer, exactly like a renderer, so it can
     *  only take the view's size where a renderer can (the view on the GL
     *  layer's bottom-left corner); anywhere else it would draw shifted inside
     *  the window, which is worse than drawing too big (the renderer rule in
     *  the section comment). Corpus consumers of that shape include HCR's
     *  FSGLSurfaceView, Super Jetpack's GLSurfaceViewMortar, OpenTTD's
     *  GLSurfaceView_SDL, klooni's GLSurfaceViewAPI18, Wikipedia's
     *  MapLibreSurfaceView and several SDLSurface apps. So until this holder
     *  has served a lockCanvas it answers by the GL rule, and from the first
     *  lockCanvas on by the Canvas rule; macapkConvergeHolderToLayout
     *  re-delivers the size the frame after the rule changes. The first answer
     *  a Canvas app gets is therefore the GL rule's, which for a view off the
     *  GL corner is the pre-change display size, and it is corrected after its
     *  first frame (BobBall: 3360x1636, then 3360x1560). */
    private int[] macapkHolderSurfaceSize(int displayW, int displayH) {
        if (sViewSizeGate && !holder.macapkFixedSize) {
            boolean gl = !holder.macapkLockedCanvas;                   /*HOLDER-RULE*/
            int[] v = macapkNonFillingViewSize(displayW, displayH, gl);
            if (v == null && gl) v = macapkOversizeUnclaimedViewSize();
            if (v != null) return v;
        }
        return new int[] { displayW, displayH };
    }

    // ---- a holder nobody draws into yet, on a view larger than its window ------
    //
    // A "center crop" preview lays its SurfaceView out LARGER than the window
    // (negative margins) so the frame's aspect ratio survives, then waits for
    // surfaceChanged to report that size before it starts producing frames:
    // AOSP always sizes the surface from the view (updateSurface :985-988). The
    // GL rule above refuses an overflowing view, so such a holder kept the
    // window size -- and because its producer (a camera1 preview drawing with
    // lockCanvas, say) only starts after that surfaceChanged, the Canvas rule
    // never took over either: the preview never started (QR Bot, 2026-10-28).
    //
    // The GL rule protects a GL/EGL consumer; a view with a resolved renderer, a
    // GLSurfaceView, or the native-window owner (ANativeWindow_fromSurface) is
    // one, and keeps it. A holder with none of those has no GL consumer the
    // window-box layer could misplace, so it gets the view's size, as on AOSP.
    //
    // BISECT GATE, default ON: MACAPK_SV_OVERSIZE_HOLDER=0 restores the window
    // size for these holders.
    static final boolean sOversizeHolderGate =
        !"0".equals(System.getenv("MACAPK_SV_OVERSIZE_HOLDER"));

    private int[] macapkOversizeUnclaimedViewSize() {
        if (!sOversizeHolderGate) return null;
        LayoutSnap s = macapkLayoutSnap;
        if (s == null || !macapkAttached) return null;
        if (s.w <= 0 || s.h <= 0 || s.rootW <= 0 || s.rootH <= 0) return null;
        if (s.w <= s.rootW && s.h <= s.rootH) return null;           // not overflowing: the GL rule's call
        if (s.w > 16384 || s.h > 16384) return null;
        if (reflectedRenderer != null || this instanceof android.opengl.GLSurfaceView) return null;
        java.lang.ref.WeakReference<SurfaceView> owner = sNativeWindowOwner;
        if (owner != null && owner.get() == this) return null;
        return new int[] { s.w, s.h };
    }

    /** The size a RENDERER is told (onSurfaceChanged / setScreenWidthAndHeight)
     *  for a display surface of displayW x displayH. ONE accessor for the
     *  reflected path here and GLSurfaceView.ensureLifecycleDelivered, which
     *  used to read StatusBarDriver.contentBox* itself; with
     *  MACAPK_SV_VIEW_SIZE_GL=0 it is exactly that window box. */
    protected final int[] macapkRendererSurfaceSize(int displayW, int displayH) {
        int w = com.macapk.loader.StatusBarDriver.contentBoxWidthPx(this, displayW);
        int h = com.macapk.loader.StatusBarDriver.contentBoxHeightPx(this, displayH);
        if (sViewSizeGlGate && !holder.macapkFixedSize) {
            int[] v = macapkNonFillingViewSize(displayW, displayH, true);
            // MACAPK_SV_GL_LAYER_RECT: a renderer view the anchored rule
            // refused (it overflows its window, or sits off the GL layer's
            // bottom-left corner) gets its own size too, and the ANGLE layer
            // is moved to its rect (macapkGlLayerRectPacked).
            int[] p = (v == null && sGlLayerRectGate)
                ? macapkGlLayerPlacement(displayW, displayH) : null;
            if (v != null) { w = v[0]; h = v[1]; }
            else if (p != null) { w = p[2]; h = p[3]; }
            if (sGlLayerRectGate) macapkNoteGlLayer(p != null, displayW, displayH);
        }
        return new int[] { w, h };
    }

    // ---- the ANGLE layer follows a renderer view's rect (2026-10-01) ------------
    //
    // AOSP gives a SurfaceView's renderer the VIEW's size (updateSurface
    // :985-988, quoted in the geometry section) and the compositor shows that
    // buffer AT the view's rect, cropped to the window (mScreenRect, :1040-1043).
    // Our renderers draw into ONE window-box ANGLE layer, so until now a GL view
    // that did not sit on that layer's bottom-left corner kept the window box --
    // in particular every view LARGER than its window, which is what a
    // "crop"/"fill" resolution policy produces (AndEngine's, Flappy Bird's
    // RenderSurfaceView: 1346x2392 centred at y=-298 in a 1346x1796 window).
    // The renderer then drew the whole scene squashed into the box while the
    // MotionEvents it received were view-local in the 2392-tall view, so every
    // touch landed at the wrong scene point (owner report 2026-10-01, "Flappy
    // Bird does not respond to touch": the title's Play button was never hit;
    // a phone-shaped window overflows SIDEWAYS by only 12-22px, which is why a
    // harness run at the legacy size flapped). Research and measurements:
    // docs/gl-surface-larger-than-window-layer-rect_2026-10-01.md.
    //
    // The fix is the one the geometry note's OPEN item 2 names: the native side
    // pulls this rect every frame and sizes/positions the ANGLE layer to it
    // (cocoa_bridge.mm MacapkApplyGlLayerRect), the host view clipping it to the
    // window, exactly as the video plane follows its SurfaceView. The renderer
    // is then told the view's own size and glViewport(0,0,w,h) covers the view.
    // The ANCHORED case keeps the window-box layer (it already lands right).
    //
    // BISECT GATE, default ON: MACAPK_SV_GL_LAYER_RECT=0 restores the old
    // answer (the window box) and never publishes a rect.
    static final boolean sGlLayerRectGate =
        !"0".equals(System.getenv("MACAPK_SV_GL_LAYER_RECT"));
    /** The renderer view the ANGLE layer was last placed at; null = none. */
    private static volatile java.lang.ref.WeakReference<SurfaceView> sGlLayerView;
    /** The display this view's renderer was last sized for: (w << 32) | h. */
    private volatile long macapkGlLayerDisplay;

    /** {x, y, w, h} of this view relative to its window box when the layer can
     *  follow it and the anchored rule did not already size it; null otherwise.
     *  Same snapshot and preconditions as macapkNonFillingViewSize(gl). */
    private int[] macapkGlLayerPlacement(int displayW, int displayH) {
        LayoutSnap s = macapkLayoutSnap;
        if (s == null || !macapkAttached || holder.macapkFixedSize) return null;   /*GL-LAYER-ATTACHED*/
        if (s.w <= 0 || s.h <= 0 || s.rootW <= 0 || s.rootH <= 0) return null;
        if (s.w == s.rootW && s.h == s.rootH) return null;          // fills: the window box
        // A Metal drawable cannot exceed 16384 px a side; the packed rect is 16-bit.
        if (s.w > 16384 || s.h > 16384) return null;
        if (s.x < Short.MIN_VALUE || s.x > Short.MAX_VALUE
            || s.y < Short.MIN_VALUE || s.y > Short.MAX_VALUE) return null;
        // The layer's box is the window box; the root must BE it for the
        // root-relative offset to be the box-relative one.
        int boxW = com.macapk.loader.StatusBarDriver.contentBoxWidthPx(this, displayW);
        int boxH = com.macapk.loader.StatusBarDriver.contentBoxHeightPx(this, displayH);
        if (s.rootW != boxW || s.rootH != boxH) return null;
        boolean anchored = s.w <= s.rootW && s.h <= s.rootH
            && s.x == 0 && s.y + s.h == s.rootH;                   /*GL-LAYER-ANCHORED*/
        if (anchored) return null;
        return new int[] { s.x, s.y, s.w, s.h };
    }

    private void macapkNoteGlLayer(boolean placed, int displayW, int displayH) {
        java.lang.ref.WeakReference<SurfaceView> ref = sGlLayerView;
        SurfaceView cur = ref != null ? ref.get() : null;
        if (placed) {
            macapkGlLayerDisplay = ((long) displayW << 32) | (displayH & 0xffffffffL);
            if (cur != this) {
                sGlLayerView = new java.lang.ref.WeakReference<>(this);   /*GL-LAYER-PUBLISH*/
                NativeLog.i(TAG, "GL layer follows " + getClass().getName()
                    + " (view does not sit on the window box's GL corner)");
            }
        } else if (cur == this) {
            sGlLayerView = null;
        }
    }

    /** The last (view size, told size) GL-SIZE-SPLIT warned about, 0 = none. */
    private volatile long macapkGlSplitWarned;

    /** GL-SIZE-SPLIT (2026-10-01): a renderer told a size that is NOT its view's
     *  laid-out size while the view does not fill its window -- its MotionEvents
     *  arrive view-local (ViewGroup.dispatchTransformedTouchEvent offsets them by
     *  the view's left/top) but its frame is drawn in the other size, so every
     *  touch lands at the wrong point of the scene (Flappy Bird's Play button,
     *  owner report 2026-10-01). Called where a renderer is actually told a size,
     *  with the DISPLAY that size was derived from.
     *  A diagnostic line only, one per distinct state, read by
     *  debug/owner_issue_scan.py (GL-SIZE-SPLIT); never changes what is told.
     *
     *  STALE LAYOUT IS NOT A SPLIT. A display change (window resize, fullscreen,
     *  a letterbox change) reaches the renderer synchronously from the native
     *  resize fire while the relayout runs LATER on the owner loop, so for one
     *  frame the snapshot still describes the OLD window: its root is not the
     *  new display's window box. That is a pending relayout, not a disagreement
     *  (the renderer is told again once the layout lands), so it is not
     *  reported -- otherwise any window drag on any non-filling GL app, Flappy
     *  after the fix included, was flagged "touches land at the wrong point". */
    protected final void macapkWarnGlSizeSplit(int toldW, int toldH, int displayW, int displayH) {
        LayoutSnap s = macapkLayoutSnap;
        if (s == null || !macapkAttached || s.w <= 0 || s.h <= 0) return;
        if (s.w == toldW && s.h == toldH) return;
        if (s.w == s.rootW && s.h == s.rootH) return;   // fills its window: the box by design
        if (s.rootW != com.macapk.loader.StatusBarDriver.contentBoxWidthPx(this, displayW)
            || s.rootH != com.macapk.loader.StatusBarDriver.contentBoxHeightPx(this, displayH))
            return;                                      /*GL-SPLIT-FRESH*/
        long key = ((long) s.w << 48) ^ ((long) s.h << 32) ^ ((long) toldW << 16) ^ toldH;
        if (key == macapkGlSplitWarned) return;
        macapkGlSplitWarned = key;                       /*GL-SPLIT-WARN*/
        NativeLog.w(TAG, "GL-SIZE-SPLIT: " + getClass().getName() + " is laid out " + s.w + "x" + s.h
            + " at " + s.x + "," + s.y + " in a " + s.rootW + "x" + s.rootH + " window but its renderer is told "
            + toldW + "x" + toldH + " -- its touches are view-local and its frame is not, so they land at "
            + "the wrong point (docs/gl-surface-larger-than-window-layer-rect_2026-10-01.md)");
    }

    /** Native pull (Cocoa main thread, once per frame): where the ANGLE layer
     *  goes, as (x << 48) | (y << 32) | (w << 16) | h in window-box pixels,
     *  x/y signed, top-left origin; 0 = keep the window box. Recomputed from
     *  the view's CURRENT layout snapshot, so a relayout moves the layer the
     *  same frame the renderer hears the new size. */
    public static long macapkGlLayerRectPacked() {
        if (!sGlLayerRectGate || !sViewSizeGlGate) return 0L;
        java.lang.ref.WeakReference<SurfaceView> ref = sGlLayerView;
        SurfaceView v = ref != null ? ref.get() : null;
        if (v == null) return 0L;
        try {
            long d = v.macapkGlLayerDisplay;
            if (d == 0L || !v.isShown()) return 0L;                   /*GL-LAYER-SHOWN*/
            int[] p = v.macapkGlLayerPlacement((int) (d >>> 32), (int) d);
            if (p == null) return 0L;
            return ((long) (p[0] & 0xffffL) << 48)
                 | ((long) (p[1] & 0xffffL) << 32)
                 | ((long) (p[2] & 0xffffL) << 16)
                 |  (long) (p[3] & 0xffffL);
        } catch (Throwable t) {
            return 0L;   // a half-torn-down tree degrades to the window box
        }
    }

    /** Layout moved a created surface's size (AOSP: setFrame -> updateSurface
     *  -> sizeChanged -> surfaceChanged). Every frame; one comparison unless
     *  the answer changed. Never for a fixed-size holder (left as before). */
    private void macapkConvergeHolderToLayout() {
        if (holder.macapkFixedSize) return;
        int[] d = macapkNotedDisplaySize();
        if (d == null) return;
        int dW = d[0], dH = d[1];
        int[] s = macapkHolderSurfaceSize(dW, dH);
        android.graphics.Rect f = holder.getSurfaceFrame();
        if (s[0] == f.width() && s[1] == f.height()) return;
        NativeLog.i(TAG, "surface follows layout: " + f.width() + "x" + f.height()
            + " -> " + s[0] + "x" + s[1] + " (display " + dW + "x" + dH + ") on "
            + getClass().getName());
        holder.fireChanged(0, s[0], s[1]);
    }

    /** This view's top-left in DISPLAY-surface pixels -- the space the
     *  ViewCanvasDriver canvas, and so the canvas overlay, lives in. ONE
     *  formula for the video plane (macapkActiveVideoRectPacked) and the
     *  placement of a lockCanvas surface, so a hole and its plane cannot drift
     *  apart; the v1.0.997 correction it carries is documented there. */
    private void macapkWindowOrigin(int[] out) {
        int[] loc = new int[2];
        getLocationInWindow(loc);
        View root = getRootView();
        int rootLeft = root != null ? root.getLeft() : 0;
        int rootTop  = root != null ? root.getTop()  : 0;
        out[0] = loc[0] - rootLeft;
        out[1] = loc[1] - rootTop + com.macapk.loader.StatusBarDriver.heightPx(root);
    }

    // ---- where a lockCanvas frame is shown -----------------------------------
    //
    // Two routes, best first:
    //  (1) the runtime's PER-SURFACE layer (MACAPK_SV_SURFACE_LAYER): the
    //      frame goes to a CALayer of its own, at the view's rect, BELOW the
    //      view-tree overlay, and draw() punches the hole AOSP punches. This is
    //      what lets a HUD drawn by sibling views show over the game
    //      (BobBall's "Time Left" / "Score" rows, F2 of the fleet note); on the
    //      shared overlay layer the game thread's frame replaces the view
    //      tree's, last writer wins. Resolved by a capability probe
    //      (NativeCocoa.presentSurfaceBitmap), so this file builds and runs
    //      the same before and after the native half lands.
    //  (2) otherwise, a surface that is NOT display-sized (a non-filling view
    //      under MACAPK_SV_VIEW_SIZE) is composited into a display-sized frame
    //      at its rect before the legacy whole-window present -- which
    //      stretches whatever it is given over the window
    //      (kCAGravityResize), so a view-sized frame handed over as-is would
    //      be stretched anisotropically. Outside the rect the frame is
    //      transparent. The HUD still loses to the game's frame on this route.
    //  Anything else takes the legacy present unchanged.

    private static final java.util.concurrent.atomic.AtomicInteger sSurfaceKeySeq =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ConcurrentHashMap<Integer,
            java.lang.ref.WeakReference<SurfaceView>> sSurfaceByKey =
        new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int macapkSurfaceKey;
    /** AOSP mDrawFinished: a frame of this surface has been shown, so draw()
     *  now punches its hole (and a first frame asked the tree to redraw).
     *  STICKY on purpose, as AOSP's is: android14 SurfaceView.java writes
     *  mDrawFinished only at its declaration (:177, false) and in
     *  performDrawFinished (:427, true) -- neither onDetachedFromWindow
     *  (:435-461) nor either surface teardown in updateSurface (:975,
     *  :1088-1095) clears it. A re-attached view punches its hole at once; ours then
     *  shows the layer's last frame (the rect pull hid it while detached,
     *  and hiding keeps its contents) until the next present. */
    private volatile boolean macapkSurfaceLayerDrawn;

    private int macapkSurfaceKey() {
        int k = macapkSurfaceKey;
        if (k != 0) return k;
        synchronized (sSurfaceByKey) {
            k = macapkSurfaceKey;
            if (k == 0) {
                k = sSurfaceKeySeq.incrementAndGet();
                sSurfaceByKey.put(k, new java.lang.ref.WeakReference<>(this));
                macapkSurfaceKey = k;
            }
        }
        return k;
    }

    /** 0 = not probed, 1 = the runtime has a per-surface layer, -1 = it does not. */
    private static volatile int sSurfaceLayerState;
    private static volatile java.lang.invoke.MethodHandle sSurfaceLayerMH;

    private static java.lang.invoke.MethodHandle macapkSurfaceLayerPresenter() {
        if (!sSurfaceLayerGate) return null;
        int st = sSurfaceLayerState;
        if (st == 1) return sSurfaceLayerMH;
        if (st < 0) return null;
        try {
            java.lang.invoke.MethodHandle mh = java.lang.invoke.MethodHandles.publicLookup()
                .findStatic(NativeCocoa.class, "presentSurfaceBitmap",
                    java.lang.invoke.MethodType.methodType(void.class,
                        int.class, int[].class, int.class, int.class));
            sSurfaceLayerMH = mh;
            sSurfaceLayerState = 1;
            return mh;
        } catch (Throwable t) {
            sSurfaceLayerState = -1;
            NativeLog.i(TAG, "per-surface layer: not in this runtime (" + t.getClass().getSimpleName()
                + "); lockCanvas frames use the shared overlay");
            return null;
        }
    }

    /** Route (1). False = not available (or it failed): take route (2). */
    boolean macapkPresentThroughSurfaceLayer(int[] px, int w, int h) {
        java.lang.invoke.MethodHandle mh = macapkSurfaceLayerPresenter();
        if (mh == null) return false;
        int key = macapkSurfaceKey();
        try {
            mh.invokeExact(key, px, w, h);
        } catch (UnsatisfiedLinkError e) {
            // Declared but not registered: a runtime binary older than its jar.
            sSurfaceLayerState = -1;
            NativeLog.w(TAG, "per-surface layer: presentSurfaceBitmap not registered ("
                + e.getMessage() + "); lockCanvas frames use the shared overlay");
            return false;
        } catch (Throwable t) {
            NativeLog.w(TAG, "per-surface layer present threw: " + t);
            return false;
        }
        if (!macapkSurfaceLayerDrawn) {
            macapkSurfaceLayerDrawn = true;
            NativeLog.i(TAG, "per-surface layer: first frame " + w + "x" + h + " (key " + key
                + ") of " + getClass().getName() + "; the view tree now punches its hole");
            // The tree's current recording predates the hole: re-record it.
            try { postInvalidate(); } catch (Throwable ignored) { }
        }
        return true;
    }

    /** Route (2)'s frame, reused across frames (drawing thread only). */
    private int[] macapkCompositeBuf;
    int macapkCompositeW, macapkCompositeH;
    private int macapkPlacedX = Integer.MIN_VALUE, macapkPlacedY, macapkPlacedW, macapkPlacedH;

    /** Route (2): the display-sized frame to present instead of {@code px}, or
     *  null to present {@code px} the legacy way. Drawing thread. */
    int[] macapkPlaceInDisplay(int[] px, int bw, int bh) {
        if (!sViewSizeGate || px == null) return null;
        int[] d = macapkNotedDisplaySize();
        if (d == null) return null;
        int dW = d[0], dH = d[1];
        if (bw == dW && bh == dH) return null;           // display-sized: legacy IS its placement
        if (holder.macapkFixedSize || !macapkAttached) return null;
        LayoutSnap s = macapkLayoutSnap;
        if (s == null || s.w != bw || s.h != bh) return null;   // not (yet) the view's own size
        if ((long) dW * dH > Integer.MAX_VALUE || px.length < bw * bh) return null;
        int[] o = new int[2];
        try { macapkWindowOrigin(o); } catch (Throwable t) { return null; }
        int x = o[0], y = o[1];
        int[] buf = macapkCompositeBuf;
        if (buf == null || macapkCompositeW != dW || macapkCompositeH != dH) {
            buf = new int[dW * dH];
            macapkCompositeBuf = buf;
            macapkCompositeW = dW;
            macapkCompositeH = dH;
            macapkPlacedX = Integer.MIN_VALUE;
        } else if (x != macapkPlacedX || y != macapkPlacedY
                || bw != macapkPlacedW || bh != macapkPlacedH) {
            java.util.Arrays.fill(buf, 0);                // the old rect is stale
        }
        if (x != macapkPlacedX || y != macapkPlacedY || bw != macapkPlacedW || bh != macapkPlacedH) {
            NativeLog.i(TAG, "lockCanvas frame placed at " + x + "," + y + " " + bw + "x" + bh
                + " in the " + dW + "x" + dH + " display (no per-surface layer: composited"
                + " into the shared overlay) on " + getClass().getName());
        }
        macapkPlacedX = x; macapkPlacedY = y; macapkPlacedW = bw; macapkPlacedH = bh;
        int x0 = Math.max(0, x), x1 = Math.min(dW, x + bw);
        int y0 = Math.max(0, y), y1 = Math.min(dH, y + bh);
        if (x0 < x1 && y0 < y1) {
            int n = x1 - x0;
            for (int row = y0; row < y1; row++) {
                System.arraycopy(px, (row - y) * bw + (x0 - x), buf, row * dW + x0, n);
            }
        }
        return buf;
    }

    /** Native pull for the per-surface layer (Cocoa main thread, per present
     *  and per tick while the layer exists), same packing and sentinels as
     *  macapkActiveVideoRectPacked: (x << 48) | (y << 32) | (w << 16) | h in
     *  DISPLAY-surface pixels, x/y signed; 0 = no such surface any more (drop
     *  the layer); -1 = not visible right now (hide it). A display-sized or
     *  fixed-size buffer keeps the legacy whole-window placement, which is
     *  where its pixels have always been shown. */
    public static long macapkSurfaceRectPacked(int key) {
        java.lang.ref.WeakReference<SurfaceView> ref = sSurfaceByKey.get(key);
        SurfaceView v = ref != null ? ref.get() : null;
        if (v == null) {
            if (ref != null) sSurfaceByKey.remove(key);
            return 0L;
        }
        try {
            if (!v.macapkAttached || !v.isShown()) {
                v.macapkNoteLayerPull(v.macapkAttached ? -3L : -2L, -1L);
                return -1L;
            }
            int[] d = v.macapkNotedDisplaySize();
            int dW = d != null ? d[0] : 0, dH = d != null ? d[1] : 0;
            android.graphics.Rect f = v.holder.getSurfaceFrame();
            int x, y, w, h;
            if (v.holder.macapkFixedSize || (f.width() == dW && f.height() == dH)) {
                if (dW <= 0 || dH <= 0) return -1L;
                x = 0; y = 0; w = dW; h = dH;
            } else {
                w = v.getWidth();
                h = v.getHeight();
                if (w <= 0 || h <= 0) { v.macapkNoteLayerPull(-4L, -1L); return -1L; }
                int[] o = new int[2];
                v.macapkWindowOrigin(o);
                x = o[0]; y = o[1];
            }
            long packed = ((x & 0xffffL) << 48)
                 | ((y & 0xffffL) << 32)
                 | ((w & 0xffffL) << 16)
                 |  (h & 0xffffL);
            v.macapkNoteLayerPull(packed, packed);
            return packed;
        } catch (Throwable t) {
            return -1L;   // never throw across JNI into the compositor
        }
    }

    /** The rect pull's answer, logged only on a change of STATE CLASS (shown /
     *  not attached / attached but not shown / zero size): the pull runs per
     *  Cocoa tick, and a layer that stays hidden is otherwise silent (a
     *  never-attached content root read as "hidden" on every tick, which is how
     *  Frozen Bubble's black game screen stayed invisible in the logs). A shown
     *  surface that MOVES (scrolling, an animation, a window drag) changes its
     *  rect every tick and must not log one line per frame, so only the first
     *  shown rect of a run of shown answers is printed. `state` is the packed
     *  rect, or -2 not attached / -3 attached but not shown / -4 zero size;
     *  nothing is allocated unless the class changed. */
    private volatile int macapkLastLayerClass = Integer.MIN_VALUE;
    void macapkNoteLayerPull(long state, long answer) {
        final int cls = (answer == -1L) ? (int) state : 0;   // 0 = shown
        if (cls == macapkLastLayerClass) return;
        macapkLastLayerClass = cls;
        String what = state == -2L ? "hidden (not attached to a window)"
                    : state == -3L ? "hidden (attached, not shown: vis=" + getVisibility() + ")"
                    : state == -4L ? "hidden (zero size)"
                    : "shown " + (short) (answer >>> 48) + "," + (short) (answer >>> 32)
                      + " " + ((answer >>> 16) & 0xffff) + "x" + (answer & 0xffff);
        NativeLog.i(TAG, "per-surface layer pull (key " + macapkSurfaceKey + "): " + what
            + " on " + getClass().getName());
    }

    /** Native pull: does this surface sit ABOVE its window (setZOrderOnTop)? */
    public static boolean macapkSurfaceZOrderOnTop(int key) {
        java.lang.ref.WeakReference<SurfaceView> ref = sSurfaceByKey.get(key);
        SurfaceView v = ref != null ? ref.get() : null;
        return v != null && v.mZOrderOnTop;
    }

    // ---- the native window owner: below the window + a hole ------------------
    //
    // AOSP composites EVERY SurfaceView's surface below its window unless the app
    // asked setZOrderOnTop(true): mSubLayer defaults to APPLICATION_MEDIA_SUBLAYER
    // (-2) and isAboveParent() is `mSubLayer >= 0` (android14
    // core/java/android/view/SurfaceView.java :200, :1528-1530). The window then
    // shows the surface through a hole the view punches in its own draw pass once
    // the first redraw completed (draw()/dispatchDraw() :512-533,
    // `if (mDrawFinished && !isAboveParent()) clearSurfaceViewPort(canvas)`,
    // clearSurfaceViewPort :579-597 = canvas.punchHole over the view bounds;
    // mDrawFinished is set by performDrawFinished :426-432 after updateSurface's
    // surfaceCreated/Changed/RedrawNeeded callbacks, sticky). Views drawn AFTER
    // the SurfaceView (a game's HUD, a menu drawn by Canvas) composite over it.
    //
    // Our native-EGL window layer -- the CAMetalLayer a native producer gets from
    // ANativeWindow_fromSurface (Unity, AGDK, Plague Inc's GLView, SDL) -- used to
    // sit ABOVE the whole window (zPosition 2.0), so a Canvas UI sharing the
    // screen with such a surface was invisible under it (Plague Inc "stuck on the
    // blue background", docs/surfaceview-z-below-default_2026-09-29.md). Moving
    // the layer below WITHOUT the hole is what blacked out Fruit Ninja in the
    // first attempt: the window's own opaque background then covered the game.
    // So the two halves land together, keyed on the view whose Surface the
    // producer consumed (the shim's fromSurface calls
    // Surface.macapkNoteNativeWindowConsumer): the layer goes below only when
    // that view is known, and that view punches its hole.
    private static volatile java.lang.ref.WeakReference<SurfaceView> sNativeWindowOwner;
    /** Sticky: some SurfaceView HAS owned the native window. Once true the layer
     *  answer never falls back to the legacy above-window slot (0), even after the
     *  owner has been collected: a destroyed SurfaceView's surface must not jump
     *  over the NEXT screen's Canvas UI (AOSP destroys the surface with its view). */
    private static volatile boolean sHadNativeWindowOwner;
    /** AOSP mDrawFinished for the native-window route; sticky, as AOSP's is. */
    private volatile boolean macapkNativeWindowDrawFinished;
    /** The hole-punch line is logged once per ownership: the log proof the hole branch
     *  of draw() was reached after the "native window owner" line
     *  (owner_issue_scan.py HIDDEN-GUEST keys on its absence). */
    private volatile boolean macapkNativeHoleLogged;

    /** The runtime handed {@code owner}'s Surface to a native producer (null: a
     *  Surface no SurfaceView vends, e.g. one built on a SurfaceTexture or an
     *  ImageReader -- IGNORED: AOSP layering is a property of the SurfaceView,
     *  mSubLayer, and never depends on some other Surface acquiring a native
     *  window, so such a Surface must not revoke the real owner). The surface
     *  callbacks have been delivered by then (producers consume the window inside
     *  or after surfaceCreated/surfaceChanged), which is the point AOSP's
     *  performDrawFinished marks; like it, ask the tree to redraw so the hole
     *  appears (performDrawFinished's invalidate(); posted, as this can run on the
     *  producer's thread). */
    static void macapkNoteNativeWindowOwner(SurfaceView owner) {
        if (!sGuestBelowGate || owner == null) return;
        java.lang.ref.WeakReference<SurfaceView> prevRef = sNativeWindowOwner;
        SurfaceView prev = prevRef != null ? prevRef.get() : null;
        sNativeWindowOwner = new java.lang.ref.WeakReference<>(owner);
        sHadNativeWindowOwner = true;
        owner.macapkNativeWindowDrawFinished = true;
        try { owner.postInvalidate(); } catch (Throwable ignored) { }
        if (prev != null && prev != owner) {
            // the previous owner stops punching: redraw it so its hole closes
            try { prev.postInvalidate(); } catch (Throwable ignored) { }
        }
        if (prev != owner) {
            owner.macapkNativeHoleLogged = false;   // each owner line is followed by its own punch line
            NativeLog.i(TAG, "native window owner: " + owner.getClass().getName()
                + " onTop=" + owner.mZOrderOnTop + " -> its layer composites "
                + (owner.mZOrderOnTop ? "ABOVE" : "BELOW (hole punched)")
                + " the window (MACAPK_SV_GUEST_BELOW)");
        }
    }

    private boolean macapkIsNativeWindowOwner() {
        java.lang.ref.WeakReference<SurfaceView> ref = sNativeWindowOwner;
        return ref != null && ref.get() == this;
    }

    /** Native pull, every drawInMTKView tick: where the native-EGL window layer
     *  goes. 0 = no SurfaceView has EVER owned it (or MACAPK_SV_GUEST_BELOW=0):
     *  the legacy slot above the whole window; 1 = below the window
     *  (APPLICATION_MEDIA_SUBLAYER / MEDIA_OVERLAY), its owner punches the hole --
     *  also the answer once the owner is gone (nothing of a destroyed surface is
     *  visible, as on AOSP); 2 = above its own window (setZOrderOnTop(true),
     *  APPLICATION_PANEL_SUBLAYER) but still below other windows.
     *  cocoa_bridge.mm macapk_guest_layer_z_for maps the answer to a zPosition. */
    public static int macapkNativeWindowLayerZ() {
        if (!sGuestBelowGate) return 0;
        java.lang.ref.WeakReference<SurfaceView> ref = sNativeWindowOwner;
        SurfaceView v = ref != null ? ref.get() : null;
        if (v == null) return sHadNativeWindowOwner ? 1 : 0;
        return v.mZOrderOnTop ? 2 : 1;
    }

    // ---- MetalSurfaceCallbacks: reflection-driven autodiscovery ----
    @Override
    public void macapkSurfaceChanged(int widthPx, int heightPx) {
        if (MACAPK_TRACE_DISPATCH) {
            // Every arrival, with the latch state. A good MCPE run shows a
            // SECOND arrival that carries the real ANativeWindow; the BLANK
            // failure shows only the first.
            NativeLog.i(TAG, "[surface-dispatch] macapkSurfaceChanged " + widthPx
                + "x" + heightPx + " createdFired=" + holderSurfaceCreatedFired
                + " pending=" + pendingSurfaceW + "x" + pendingSurfaceH
                + " on " + getClass().getName());
        }
        // Publish the REAL drawable size for the keyboard-binding path, which
        // has to denormalise a bound point into the same surface-pixel space a
        // real click lands in. This is the authoritative source; deriving it
        // from the points-space globals instead is the drift that caused
        // v1.0.742's viewport bug.
        try { com.macapk.loader.TouchDispatcher.macapkNoteSurfaceSize(widthPx, heightPx); }
        catch (Throwable ignored) { }
        macapkNoteNativeDisplaySize(widthPx, heightPx);
        // Fire SurfaceHolder.Callback callbacks first, BEFORE the
        // reflection-renderer dispatch below. SurfaceView subclasses that
        // use lockCanvas/unlockCanvasAndPost (Frozen Bubble's GameView,
        // Bouncy's CanvasFieldView, any 2D game with a custom render
        // thread, all non-OpenGL SurfaceView consumers) register holder
        // callbacks via getHolder().addCallback() and expect them to fire
        // when the surface is ready. Without this dispatch surfaceCreated
        // never runs -> the engine's drawing thread never starts ->
        // lockCanvas/unlockCanvasAndPost never run -> screen stays black
        // even though MTKView is hosting and ANGLE is swapping.
        //
        // Per AOSP frameworks/base/core/java/android/view/SurfaceView.java
        // and the public SurfaceHolder.Callback javadoc, surfaceCreated
        // fires exactly once per surface lifetime, surfaceChanged fires
        // on every dimension/format change. We mirror that contract here.
        //
        // GLSurfaceView subclasses (the cocos2d-x v2 / AndEngine GL
        // engines) don't register holder callbacks; for them this is a
        // no-op and the reflection-renderer logic below is what drives
        // their per-frame work. So this is a strictly additive change
        // that unblocks Canvas-based renderers without affecting any
        // currently-working GL-based engine.
        // v1.0.158 DIAGNOSTIC: confirm this fires (and on which subclass
        // instance) so we can tell whether the SurfaceView our framework
        // drives is Unity's a.g (which carries the engine's holder
        // callback) or some other SurfaceView.
        //
        // v1.0.572: surfaceCreated delivery waits until the dispatch can
        // actually be heard. On real Android the surface callbacks are
        // main-looper messages that CANNOT run until onResume returns, so
        // anything an app sets up in onCreate -- registering its
        // SurfaceHolder.Callback, loading its native lib, registering
        // native surface handlers (AGDK GameActivity does all three) -- is
        // guaranteed in place before surfaceCreated. Our dispatch arrives
        // from the Cocoa render thread, which can (and for AppCompat-
        // wrapped GL activities like Minecraft's GameActivity, did) land
        // mid-onCreate: the single-shot latch then consumed surfaceCreated
        // with zero listeners and the AGDK native loop never received
        // APP_CMD_INIT_WINDOW. When not ready (or when a callback rejects
        // the delivery -- see macapkTryFireHolderCreated) we record the
        // size and macapkDrawFrame retries per frame. Window focus is
        // delivered inside macapkTryFireHolderCreated, preserving the
        // surface-before-focus ordering Unity depends on.
        if (!holderSurfaceCreatedFired) {
            if (macapkRecordArrivalDuringPostedCreate(widthPx, heightPx)) {
                // A posted surfaceCreated is in flight (SV_UI_THREAD_CALLBACKS):
                // this arrival is delivered as its own surfaceChanged right after
                // that create, in AOSP order. The size is also kept pending so a
                // create that is REJECTED (not latched) retries at the latest size.
                pendingSurfaceW = widthPx;
                pendingSurfaceH = heightPx;
            } else if (macapkHolderDispatchReady()
                    && macapkTryFireHolderCreated(widthPx, heightPx)) {
                NativeLog.i(TAG, "macapkSurfaceChanged " + widthPx + "x"
                    + heightPx + " on " + getClass().getName()
                    + " (first; fired surfaceCreated)");
            } else {
                pendingSurfaceW = widthPx;
                pendingSurfaceH = heightPx;
                long n = ++surfaceDeferLogCount;
                if (n == 1 || n == 60 || (n % 600) == 0) {
                    NativeLog.i(TAG, "macapkSurfaceChanged " + widthPx + "x"
                        + heightPx + " on " + getClass().getName()
                        + " deferred #" + n + " (listeners/native not ready; "
                        + "surfaceCreated follows onResume on real Android)");
                }
            }
        } else {
            // Every native arrival still fires, as before (MCPE's handover
            // depends on the SECOND arrival reaching its holder); only the
            // size is the holder's (== widthPx x heightPx for a view that
            // fills its window, and with MACAPK_SV_VIEW_SIZE=0).
            int[] s = macapkHolderSurfaceSize(widthPx, heightPx);
            holder.fireChanged(0, s[0], s[1]);

            // v1.0.164 (+ v1.0.572 resumed gate): surface is live; deliver
            // window focus once the launch burst is done (re-delivery safe:
            // once-guarded per activity inside ActivityHost).
            pendingFocusDelivery = true;
            macapkDeliverFocusIfResumed();
        }

        ensureRendererResolved();
        if (reflectedRenderer == null) {
            // MACAPK_SV_LATE_RENDERER (2026-09-26): keep the size for a renderer
            // that does not exist YET. AOSP's GLThread gives a renderer
            // onSurfaceChanged before its first onDrawFrame whatever order the
            // renderer and the surface arrived in (android14 GLSurfaceView.java
            // guardedRun :1541 onSurfaceCreated, :1557 onSurfaceChanged, :1573
            // onDrawFrame; `private boolean mSizeChanged = true;` :1852), so a
            // renderer installed after this call must still be sized first.
            // Returning with nothing kept is what drew Super Jetpack's
            // MortarGameView$Renderer unsized: its GameManager kept its
            // constructor default mWidth = mHeight = 40, SystemInit sent 40x40
            // to the engine, and the engine laid its whole UI out for a 40px
            // square (docs/fleet-unity-constant-gaps_2026-09-26.md §4).
            // macapkDrawFrame replays it, once, before the first onDrawFrame --
            // the same place and sequence the single-owner deferral uses.
            if (sLateRendererGate) {
                reflectedDeferredW = widthPx;
                reflectedDeferredH = heightPx;
                reflectedDeferredLate = true;
            }
            return;
        }
        // MACAPK_GL_SINGLE_OWNER (2026-09-26): while the app's own GL thread
        // owns this renderer, DEFER the reflected surface callbacks instead of
        // delivering them on OS-main. Latest size wins; macapkDrawFrame replays
        // them, once, on the first frame the gate opens. Never dropped. See the
        // single-owner section comment above macapkRendererOwnedByAppGlThread.
        if (macapkRendererOwnedByAppGlThread()) {
            reflectedDeferredW = widthPx;
            reflectedDeferredH = heightPx;
            reflectedDeferredLate = false;
            return;
        }
        // MACAPK_GL_WAIT_FRAME: no RENDERER callbacks before the view has a
        // frame (AOSP updateSurface `if (!mHaveFrame) return;`). The holder's
        // SurfaceHolder.Callback delivery (macapkTryFireHolderCreated) is NOT
        // held back here, although AOSP holds surfaceCreated (:1108) behind the
        // same mHaveFrame -- an open parity gap, see the doc. Parked like the
        // single-owner deferral above; macapkDrawFrame replays it once the frame
        // exists. Only the FIRST delivery waits.
        if (sGlWaitFrameGate && !reflectedSurfaceCreatedFired
                && !macapkRendererMayStart(widthPx, heightPx)) {
            reflectedDeferredW = widthPx;
            reflectedDeferredH = heightPx;
            reflectedDeferredLate = false;
            return;
        }
        // Delivered now, so anything still deferred is superseded by this size.
        reflectedDeferredW = 0;
        reflectedDeferredH = 0;
        reflectedDeferredLate = false;
        macapkReflectedSurfaceChanged(widthPx, heightPx);
    }

    /** The reflected renderer's half of macapkSurfaceChanged: onSurfaceChanged,
     *  setScreenWidthAndHeight, then (first time only) onSurfaceCreated (and,
     *  only under MACAPK_SV_RESUME_ONCE=0, the retired handleOnResume re-fire).
     *  Factored out UNCHANGED (2026-09-26) so the
     *  single-owner gate can replay exactly this sequence later, in exactly
     *  this order, instead of re-deriving it. */
    private void macapkReflectedSurfaceChanged(int widthPx, int heightPx) {
        // v1.0.996: the ENGINE gets the app WINDOW's surface size, not the
        // display's. widthPx/heightPx above are the full display drawable and
        // stay that way for holder.fireChanged / the overlay drive / the
        // WindowManager overlay -- those are display-space consumers, and the
        // bar painter is one of them. The renderer is not: AOSP hands it the
        // SurfaceView's own laid-out size, which stops where the nav bar
        // starts. Without this the engine renders into rows the OS then paints
        // its nav strip over (owner report, 2026-08-17: HCR's nav bar sitting
        // on the game). See StatusBarDriver.contentBoxHeightPx.
        // v1.0.997: the WIDTH is a window dimension too. A landscape phone-class
        // display puts the navigation bar on the RIGHT, so the app window is
        // narrower than the display and the ANGLE layer is sized to it -- telling
        // the engine the full display width would leave it setting a viewport
        // wider than its own backbuffer (the v1.0.742 failure shape).
        // 2026-09-26: both through macapkRendererSurfaceSize, which is that
        // window box unless the view does not fill its window and sits on the
        // GL layer's origin (MACAPK_SV_VIEW_SIZE_GL; see the geometry section).
        final int[] glSize = macapkRendererSurfaceSize(widthPx, heightPx);
        final int glW = glSize[0];
        final int glH = glSize[1];
        macapkWarnGlSizeSplit(glW, glH, widthPx, heightPx);
        reflectedDeliveredW = glW;
        reflectedDeliveredH = glH;
        try {
            // Order matters: cocos2d-x's onSurfaceCreated reads
            // mRenderer.screenWidth/screenHeight when calling
            // nativeInit. Those fields are set by either
            // setScreenWidthAndHeight (in production, Cocos2dxGLSurfaceView
            // calls it from onSizeChanged) or by onSurfaceChanged itself.
            // We don't run Cocos2dxGLSurfaceView's full lifecycle, so we
            // fire onSurfaceChanged FIRST to populate the fields, then
            // onSurfaceCreated, which now sees valid w/h and calls
            // nativeInit(w, h). This deviates from Android's standard
            // (creation -> change) but matches what the renderer
            // actually depends on, and Android renderers tolerate the
            // reorder because both methods are idempotent on the
            // common path.
            if (reflectedOnSurfaceChanged != null) {
                // A renderer callback runs as the app's Looper-less GLThread
                // (docs/gl-thread-has-no-looper_2026-10-01.md).
                android.os.Looper.macapkEnterGlRenderer();
                try {
                    reflectedOnSurfaceChanged.invoke(reflectedRenderer,
                        glArgFor(reflectedOnSurfaceChanged), glW, glH);
                } finally { android.os.Looper.macapkExitGlRenderer(); }
            }
            // Also try setScreenWidthAndHeight if the renderer exposes
            // it -- some engines populate the cached size that way
            // instead of in onSurfaceChanged. MACAPK_GL_WAIT_FRAME: not any
            // more; the app's own onSizeChanged has called it by now, since
            // this first delivery waited for the view's frame.
            if (!sGlWaitFrameGate) tryInvokeSetScreenSize(glW, glH);
            if (!reflectedSurfaceCreatedFired && reflectedOnSurfaceCreated != null) {
                android.os.Looper.macapkEnterGlRenderer();
                try {
                    reflectedOnSurfaceCreated.invoke(reflectedRenderer,
                        glArgFor(reflectedOnSurfaceCreated),
                        eglConfigArgFor(reflectedOnSurfaceCreated));
                } finally { android.os.Looper.macapkExitGlRenderer(); }
                reflectedSurfaceCreatedFired = true;
                // MACAPK_SV_RESUME_ONCE (default ON, 2026-09-27): the
                // handleOnResume RE-FIRE below is retired; =0 restores it
                // unchanged. Why it was wrong, and why nothing is lost:
                //
                // (1) Android never makes this call. AOSP GLSurfaceView's
                //     GLThread.guardedRun services mEventQueue FIRST, ahead of
                //     any readiness test (android14 GLSurfaceView.java:1330-1332
                //     `if (! mEventQueue.isEmpty()) { event =
                //     mEventQueue.remove(0); break; }`, run at :1501-1504), and
                //     ActivityThread.handleResumeActivity runs
                //     performResumeActivity (:4995) before wm.addView(decor)
                //     (:5048). So the handleOnResume that cocos2d-x v2's
                //     Cocos2dxGLSurfaceView.onResume queues runs BEFORE the
                //     surface on first launch, and nativeOnResume's
                //     `if (CCDirector::sharedDirector()->getOpenGLView())`
                //     guard makes it a no-op: a device never calls
                //     applicationWillEnterForeground at first launch, and
                //     nothing calls handleOnResume a second time. (HCR 1.8.1
                //     libgame.so nativeOnResume @0x1766b8 tests
                //     CCDirector+0x2c, the field setOpenGLView @0x130ec4
                //     writes; AppDelegate::applicationWillEnterForeground
                //     @0xcc060 is return-from-background work only.)
                // (2) When it landed (c20c9434, 2026-05-16, the OpenGD/axmol
                //     bring-up) our own queue ran queued GL events at the end
                //     of onResume, before the surface, and a vendored fork's
                //     private queue (HCR's FSGLSurfaceView) was not drained at
                //     all: the fork's GLThread dies at our EGL refusal, so the
                //     app's own handleOnResume was DROPPED. OpenGD now calls
                //     setRenderer on our GLSurfaceView and never reaches this
                //     path. Since v1.0.834 macapkDrainForkedGlQueue runs the
                //     fork's queue on this thread after the surface, so HCR's
                //     own queued Runnable already arrives, once. With the re-fire as well every HCR build
                //     measured handleOnResume TWICE at first launch (and
                //     applicationWillEnterForeground twice, where Android runs
                //     it zero times).
                // Interplay: under MACAPK_GLTHREAD_DRAIN=0 with this gate ON no
                // handleOnResume runs after the surface at all, which is also
                // what a device does at first launch. Gate:
                // debug/geh_cocos2dx-handleOnResume-refire_gate.sh.
                if (!sResumeOnceGate) {
                    // Cocos2d-x v2 lifecycle (historical, MACAPK_SV_RESUME_ONCE=0
                    // only; the premise below is refuted in the comment above):
                    // Cocos2dxGLSurfaceView.onResume queues handleOnResume via
                    // queueEvent, and nativeOnResume needs a non-NULL CCEGLView
                    // in CCDirector to call applicationWillEnterForeground. When
                    // this was written, our model ran the queue before the surface
                    // existed (or dropped it, for a fork), so this re-fire gave the
                    // hook post-surface timing. Generic for any engine that
                    // exposes a `handleOnResume()` no-arg method.
                    Method onR = findMethod(reflectedRenderer.getClass(),
                                              "handleOnResume");
                    if (onR != null) {
                        try {
                            onR.invoke(reflectedRenderer);
                            NativeLog.i(TAG, "re-fired handleOnResume after surface ready");
                        } catch (Throwable t) {
                            NativeLog.e(TAG, "handleOnResume re-fire threw: " + t);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            NativeLog.e(TAG, "reflected renderer.onSurfaceChanged threw: " + t);
        }
    }

    /** v1.0.996: re-publish the app WINDOW's surface size to the reflected
     *  renderer when the window box moves without the display moving.
     *
     *  Only the INSETS can do that (an app setting FLAG_FULLSCREEN, or
     *  hiding/showing the nav bar, after the surface already exists), and
     *  macapkSurfaceChanged -- the only other place this path dispatches
     *  onSurfaceChanged -- fires on a display-size change, so nothing else
     *  would notice. This is the reflection path's equivalent of the
     *  lifecycleDeliveredW/H comparison GLSurfaceView.ensureLifecycleDelivered
     *  already runs every frame for the setRenderer path.
     *
     *  Deliberately does NOT fire the FIRST delivery (guarded on
     *  reflectedDeliveredW > 0). macapkSurfaceChanged owns that, and it owns it
     *  in a specific order -- onSurfaceChanged BEFORE onSurfaceCreated, because
     *  cocos2d-x's onSurfaceCreated reads the cached size when calling
     *  nativeInit. Letting this converge run first would invert that and hand
     *  nativeInit a (0,0) viewport. */
    private void macapkConvergeReflectedSurfaceSize() {
        if (reflectedRenderer == null) return;
        if (reflectedDeliveredW <= 0 || reflectedDeliveredH <= 0) return;
        int w, h, boxW, boxH;
        if (macapkDisplayDecoupled()) {
            // MACAPK_SV_DISPLAY_SIZE: the DISPLAY this view was handed, not the
            // holder frame. The frame is the surface buffer, which an app's
            // setFixedSize rewrites (Super Jetpack's 40x40 would have been
            // re-published to its renderer from here) and which is the view's
            // own size under MACAPK_SV_VIEW_SIZE. The box then comes from the
            // one renderer accessor, so a relayout re-publishes too.
            int[] d = macapkKnownDisplaySize();
            w = d[0];
            h = d[1];
            if (w <= 0 || h <= 0) return;
            int[] box = macapkRendererSurfaceSize(w, h);
            boxW = box[0];
            boxH = box[1];
        } else {
            android.graphics.Rect f = getHolder().getSurfaceFrame();
            w = f.width();
            h = f.height();
            if (w <= 0 || h <= 0) return;
            boxW = com.macapk.loader.StatusBarDriver.contentBoxWidthPx(this, w);
            boxH = com.macapk.loader.StatusBarDriver.contentBoxHeightPx(this, h);
        }
        if (boxW == reflectedDeliveredW && boxH == reflectedDeliveredH) return;
        macapkWarnGlSizeSplit(boxW, boxH, w, h);
        NativeLog.i(TAG, "window box changed " + reflectedDeliveredW + "x"
            + reflectedDeliveredH + " -> " + boxW + "x" + boxH
            + " (display " + w + "x" + h + ") -- re-publishing to "
            + reflectedRenderer.getClass().getName());
        reflectedDeliveredW = boxW;
        reflectedDeliveredH = boxH;
        try {
            if (reflectedOnSurfaceChanged != null) {
                android.os.Looper.macapkEnterGlRenderer();
                try {
                    reflectedOnSurfaceChanged.invoke(reflectedRenderer,
                        glArgFor(reflectedOnSurfaceChanged), boxW, boxH);
                } finally { android.os.Looper.macapkExitGlRenderer(); }
            }
            // MACAPK_GL_WAIT_FRAME: AOSP re-publishes a window-box change only
            // through the relayout (onSizeChanged, the app's own call).
            if (!sGlWaitFrameGate) tryInvokeSetScreenSize(boxW, boxH);
        } catch (Throwable t) {
            NativeLog.e(TAG, "window-box re-publish threw: " + t);
        }
    }

    /** If the resolved renderer exposes a setScreenWidthAndHeight(int, int)
     *  helper (cocos2d-x does), call it. Returning silently on miss is
     *  fine -- engines that don't have it use onSurfaceChanged instead.
     *  Reached only with MACAPK_GL_WAIT_FRAME=0; delete with that gate. */
    private void tryInvokeSetScreenSize(int w, int h) {
        if (reflectedRenderer == null) return;
        try {
            Method m = findMethod(reflectedRenderer.getClass(),
                                   "setScreenWidthAndHeight");
            if (m != null) m.invoke(reflectedRenderer, w, h);
        } catch (Throwable ignore) { /* best-effort */ }
    }

    /** v1.0.64: dev-diagnostic counter for macapkDrawFrame. */
    private long macapkDrawFrameCount = 0;
    private long macapkDrawFrameSkipCount = 0;

    @Override
    public void macapkDrawFrame() {
        // Every entry counts, including the ones that return before the fork
        // drain below (macapkForkGlQueueServiced compares it with glDrainTick).
        glFrameTick++;
        // v1.0.70: drive the Canvas overlay (if registered by
        // Activity.setContentView when the rootView contains both a
        // GL view and plain widgets) BEFORE the GL render. The
        // overlay's painted bitmap goes to its own CALayer above the
        // MTKView, so ordering between GL paint and overlay paint
        // doesn't affect visibility -- but doing the overlay first
        // means a heavy GL onDrawFrame doesn't starve it. Skips
        // cheaply when no overlay is set.
        //
        // v1.0.742 FIX: this used to pass NativeCocoa.phoneWidth()/
        // phoneHeight() -- the LOGICAL/design phone size in POINTS -- while
        // Activity.macapkInstallContentDirect seeds the SAME overlay with
        // phoneWidth()*2/phoneHeight()*2 (an attempt at PIXELS). Since
        // points never equal pixels on a Retina (2x+) display,
        // macapkDriveOverlay's "did the size change" check tripped on
        // (essentially) every frame and re-fired the overlay's
        // macapkSurfaceChanged with the wrong, much-too-small POINTS value.
        // For any app whose GL SurfaceView shares a ViewGroup with sibling
        // widgets (an ad banner, a button panel -- the "Bouncy" pattern
        // this overlay exists for), that re-lays-out the GL view itself at
        // the wrong size, firing its Android-standard onSizeChanged and
        // corrupting the engine's own viewport down to a small rect pinned
        // at the GL origin (bottom-left) -- HCR 1.8.1's "renders in the
        // bottom-left corner until resized" (docs/hcr181-viewport-corner-
        // bug_2026-07-23.md). Use THIS surface's own last known real pixel
        // size (populated by our own macapkSurfaceChanged from the native
        // side's actual drawable pixels) instead of re-deriving one from
        // the points-space globals -- a single authoritative source that
        // can't drift out of sync with itself. Before the real surface
        // size is known yet (holder.frame still zeroed), fall back to the
        // SAME best-effort guess Activity.java seeds the overlay with, so
        // the two never disagree during the brief startup window either.
        // Same derived surface size feeds BOTH the ViewCanvasDriver overlay and
        // the WindowManager overlay below -- deriving it twice is how the two
        // drifted into a points-vs-pixels disagreement in the first place.
        int[] overlaySize = macapkDriveOverlayForCurrentSurface();
        int overlayW = overlaySize[0];
        int overlayH = overlaySize[1];

        // Composite any WindowManager overlay (Dialog/AlertDialog/PopupWindow)
        // shown on top of this surface. This belongs in SurfaceView, NOT only in
        // GLSurfaceView: under the two-tier renderer dispatch, an engine that
        // never calls setRenderer() (cocos2d-x v2, AndEngine) is driven from
        // HERE via reflective renderer discovery, so GLSurfaceView's own
        // macapkDrawFrame override -- and with it its presentOverlayIfNeeded --
        // is bypassed entirely. The dialog was therefore measured, laid out and
        // hit-tested, but drawn nowhere. Since WindowOverlayStack.deliverTouch
        // consumes an outside touch on a modal window (AOSP's default until
        // FLAG_NOT_TOUCH_MODAL is set), that invisible window swallowed 100% of
        // touch input with no log line at all -- HCR 1.8.1's AdManager rating
        // dialog killed every tap on a perfectly-rendering menu.
        //
        // Placed BEFORE the renderer gate below so it still runs when no
        // renderer has been discovered yet (otherwise a dialog shown during
        // startup is invisible AND blocking). The overlay paints into its own
        // CALayer above the MTKView, so ordering against the GL paint does not
        // affect visibility -- same reasoning as macapkDriveOverlay above.
        // Cheap when the overlay stack is empty (one boolean check).
        try {
            com.macapk.loader.WindowOverlayStack.presentForGl(overlayW, overlayH);
        } catch (Throwable t) {
            NativeLog.w(TAG, "presentForGl threw: " + t);
        }

        // Drain Handler.GLOBAL_QUEUE every frame BEFORE the renderer
        // gate below (v1.0.345; it used to sit after the early-return).
        // On real Android the main looper runs whether or not any GL
        // renderer exists, so posted main-thread runnables MUST fire
        // even while no renderer is discovered. Gating the drain on
        // renderer discovery deadlocked Plague Inc's boot: its
        // SplashActivity.onCreate never calls setContentView (the
        // splash is the window-background drawable on real Android) and
        // just does delayHandler.postDelayed(goToMainActivity, 500) --
        // goToMainActivity is what loads the engine and starts
        // MainActivity (which creates the REAL GL view + renderer). With
        // the drain below the renderer-null return, the runnable sat in
        // GLOBAL_QUEUE forever: no renderer -> no drain -> no
        // MainActivity -> no renderer. Any app whose splash posts its
        // continuation to the main looper hits the same cycle.
        //
        // Original placement rationale (still honored): the drain must
        // run AFTER the surface exists. macapkDrawFrame only fires once
        // drawInMTKView is live, so draining here -- before the
        // renderer gate, after surface creation -- satisfies both. See
        // the v1.0.64 note below for why GLSurfaceView.queueEvent-style
        // deferred GL work also needs this per-frame drain.
        try {
            android.os.Handler.drainGlobal();
        } catch (Throwable t) {
            NativeLog.e(TAG, "Handler.drainGlobal threw: " + t);
        }
        // v1.0.575: while the macapk-activity main loop is parked in a blocking
        // AGDK lifecycle handshake (onStartNative/onResumeNative), it is NOT
        // draining the real main queue. Drain the DUE messages here on the OS-main
        // render thread so a cross-thread runOnUiThread post that a blocked
        // android_main is waiting on (Minecraft's runNativeCallbackOnUiThread
        // FutureTask) completes -> android_main unblocks -> consumes APP_CMD_START
        // -> the handshake returns. Gated on the handshake flag so there is never
        // a double-drain with macapk-activity's own Looper.loop. No-op unless
        // MACAPK_REAL_LOOPER. See ActivityHost.macapkLifecycleHandshakeActive.
        if (com.macapk.loader.ActivityHost.macapkLifecycleHandshakeActive) {
            try {
                android.os.Looper.macapkDrainMainDue();
            } catch (Throwable t) {
                NativeLog.e(TAG, "macapkDrainMainDue threw: " + t);
            }
        }

        macapkConvergeSurfaceLifecycle(0, 0);

        ensureRendererResolved();
        // MACAPK_GL_SINGLE_OWNER (2026-09-26): a renderer the app's own GL
        // thread owns is never entered from OS-main -- no size re-publish, no
        // fork-queue drain, no onDrawFrame. Everything above this line (overlay,
        // WindowManager overlay, main-queue drains, holder/focus convergence) is
        // not renderer work and still runs every frame, so OS-main stays live.
        if (macapkOsMainDeclinesRenderer()) return;
        // MACAPK_GL_WAIT_FRAME: the first surface callbacks (and so any draw)
        // wait for the view's frame. Everything above still ran this tick, the
        // overlay drive included -- that is what gets the tree laid out.
        if (sGlWaitFrameGate && reflectedRenderer != null && !reflectedSurfaceCreatedFired
                && (reflectedDeferredW > 0 || reflectedDeliveredW <= 0)) {
            int[] d = reflectedDeferredW > 0
                ? new int[] { reflectedDeferredW, reflectedDeferredH } : macapkKnownDisplaySize();
            if (!macapkRendererMayStart(d[0], d[1])) return;
        }
        if (reflectedRenderer != null && reflectedDeferredW > 0) {
            // The gate just opened (the app's GL thread ended at the EGL
            // refusal, as an AOSP-derived fork does): deliver what OS-main
            // would have delivered while it was alive, once, before the first
            // onDrawFrame -- the same sequence macapkSurfaceChanged runs.
            // Also reached for a renderer that did not exist when the surface
            // arrived (MACAPK_SV_LATE_RENDERER), with its own log line.
            int w = reflectedDeferredW, h = reflectedDeferredH;
            boolean late = reflectedDeferredLate;
            reflectedDeferredW = 0;
            reflectedDeferredH = 0;
            reflectedDeferredLate = false;
            if (late) {
                NativeLog.i(TAG, "reflected renderer " + reflectedRenderer.getClass().getName()
                    + " appeared after the surface (" + w + "x" + h + "): delivering its"
                    + " surface callbacks before its first onDrawFrame (AOSP GLThread order)");
            } else {
                NativeLog.i(TAG, "reflected renderer drive: replaying deferred"
                    + " surface callbacks (" + w + "x" + h + ") to "
                    + reflectedRenderer.getClass().getName());
            }
            macapkReflectedSurfaceChanged(w, h);
        } else if (sLateRendererGate && reflectedRenderer != null && reflectedOnDrawFrame != null
                && reflectedDeliveredW <= 0) {
            // Belt to the record above: a renderer about to be drawn that was
            // NEVER sized (no surface callback reached this view with it
            // resolvable -- e.g. frames retargeted to a view whose surface
            // callback went to its predecessor). AOSP never draws an unsized
            // renderer, so size it from the display this view knows, else the
            // committed display. Deliberately NOT the holder frame: an app's
            // setFixedSize writes that, and a 40x40 there is exactly the value
            // this must not hand on.
            int[] d = macapkKnownDisplaySize();
            if (d[0] > 0 && d[1] > 0) {
                NativeLog.i(TAG, "reflected renderer " + reflectedRenderer.getClass().getName()
                    + " was never sized: delivering " + d[0] + "x" + d[1]
                    + " before its first onDrawFrame (AOSP GLThread order)");
                macapkReflectedSurfaceChanged(d[0], d[1]);
            }
        }
        macapkConvergeReflectedSurfaceSize();
        if (reflectedRenderer == null || reflectedOnDrawFrame == null) {
            // v1.0.64: this branch is the "no renderer discovered yet"
            // path. When the engine boot stalls (Google Play Services
            // hung waiting on AdMob, AnswersFilesManagerProvider main-
            // thread complaints, etc.) we keep firing macapkDrawFrame
            // every vsync but skip the renderer call. Log periodically
            // so the user can see "engine isn't drawing yet, surface
            // discovery still in progress" instead of just silence.
            long skip_n = ++macapkDrawFrameSkipCount;
            if (skip_n == 1 || skip_n == 60 || skip_n == 300
                            || (skip_n % 600) == 0) {
                NativeLog.i(TAG,
                    "macapkDrawFrame skip #" + skip_n
                    + ": no renderer resolved yet (rndrr="
                    + reflectedRenderer + " m=" + reflectedOnDrawFrame
                    + ")");
            }
            return;
        }
        // v1.0.64: log every 60th frame so the launcher panel shows
        // the renderer is actually being driven. Critical for
        // diagnosing "ANGLE swaps at 60 fps but nothing drew" cases
        // (engine paused, AdMob blocked main thread, etc.). The log
        // pairs with [I] gles: shim glXXX traffic in the rest of the
        // stream -- if we see "macapkDrawFrame fired" but no GL
        // calls in the same 60-frame window, the engine returned
        // from onDrawFrame without doing any drawing.
        long n = ++macapkDrawFrameCount;
        if (n == 1 || n == 60 || n == 300 || (n % 600) == 0) {
            NativeLog.i(TAG,
                "macapkDrawFrame fire #" + n
                + " -> "
                + reflectedRenderer.getClass().getName()
                + ".onDrawFrame");
        }
        // v1.0.64 note (drain moved above the renderer gate in
        // v1.0.345): on real Android, GLSurfaceView's `queueEvent` adds
        // runnables to the GL thread's own queue, serviced between
        // frame renders. The first thing queued during the activity
        // lifecycle is typically Cocos2dxGLSurfaceView.onResume's
        // `handleOnResume` runnable (nativeOnResume ->
        // applicationWillEnterForeground).
        // CORRECTED 2026-09-27: this note used to say that on a real GL
        // thread that runnable runs AFTER the surface is ready. It does
        // not: AOSP GLThread.guardedRun takes a queued event before any
        // readiness test (android14 GLSurfaceView.java:1330-1332,
        // :1501-1504) and onResume precedes the window's first traversal
        // (ActivityThread :4995 before :5048), so on first launch it runs
        // before the surface and nativeOnResume's getOpenGLView() guard
        // makes it a no-op. Here it runs after the surface only because
        // the fork's thread is dead and we drain for it below; that is
        // one delivery, which is why the handleOnResume re-fire is retired
        // (MACAPK_SV_RESUME_ONCE, see macapkReflectedSurfaceChanged).
        // Drain the APP'S OWN forked GL-thread event queue, before onDrawFrame.
        // AOSP ordering: GLThread.guardedRun services mEventQueue FIRST in its
        // inner loop, ahead of any readyToDraw gate. CORRECTED 2026-09-27: an
        // iteration that runs an event `continue`s back to the loop head
        // without drawing, so AOSP runs EVERY pending event before the next
        // draw (android14 GLSurfaceView.java:1330-1331, :1501-1504; HCR 1.8.1's
        // own FSGLSurfaceView$GLThread.guardedRun bci 425-515 -> goto 368, the
        // same shape). MACAPK_GLTHREAD_DRAIN_EMPTY; =0 is one per frame.
        // The fork's queued events and its onDrawFrame run on the app's own
        // GLThread on a device: no Looper (docs/gl-thread-has-no-looper_2026-10-01.md).
        android.os.Looper.macapkEnterGlRenderer();
        try { macapkDrainForkedGlQueue(); }
        finally { android.os.Looper.macapkExitGlRenderer(); }
        try {
            // On real Android the GL10 argument is never null. Engines
            // that issue GLES1-style state calls through it (Halfbrick's
            // GameManager.SystemInit calls gl.glFrontFace on Super
            // Jetpack) NPE per frame otherwise; engines that ignore it
            // (most GLES2-era code) are unaffected. glArgFor passes the
            // shared GL10 only when the parameter type accepts it.
            macapkDetectDrawBehindTop(reflectedRenderer);
            android.os.Looper.macapkEnterGlRenderer();
            try {
                reflectedOnDrawFrame.invoke(reflectedRenderer,
                    glArgFor(reflectedOnDrawFrame));
            } finally { android.os.Looper.macapkExitGlRenderer(); }
        } catch (Throwable t) {
            // Surface the CAUSE CHAIN, rate-limited: a bare
            // "InvocationTargetException" hides the actual per-frame
            // failure (and a full stack every frame would drown the
            // log). First few occurrences get the full trace; after
            // that, one line per 600 frames.
            long thrown = drawThrowCount++;
            if (thrown < 3) {
                NativeLog.e(TAG, "reflected renderer.onDrawFrame threw:");
                Throwable c = t;
                while (c != null) {
                    NativeLog.e(TAG, "  caused by " + c);
                    StackTraceElement[] st = c.getStackTrace();
                    for (int i = 0; i < st.length && i < 12; i++) {
                        NativeLog.e(TAG, "    at " + st[i]);
                    }
                    c = c.getCause();
                }
            } else if (thrown % 600 == 0) {
                Throwable root = t;
                while (root.getCause() != null) root = root.getCause();
                NativeLog.e(TAG, "reflected renderer.onDrawFrame threw "
                    + "(x" + thrown + "): root cause " + root);
            }
        }
    }

    private long drawThrowCount;

    // ---- Forked-GLSurfaceView event-queue drain -------------------------
    //
    // WHY THIS EXISTS (2026-07-29; measured root cause of Hill Climb Racing
    // 1.68.1's splash stick, and a fleet-wide silent-drop exposure).
    //
    // An app that VENDORS AOSP's GLSurfaceView.java instead of using ours (a
    // "fork" -- cocos2d-x ships FSGLSurfaceView, and it is a common pattern)
    // gets its own private `GLThread` with its own private `mEventQueue`.
    // `queueEvent` on that fork appends to the PRIVATE queue, drained only by
    // that thread's own guardedRun(). Mac.apk deliberately kills the fork's GL
    // thread -- MacApkEGL10.eglCreateContext always returns EGL_NO_CONTEXT /
    // EGL_BAD_CONFIG -- and then drives the renderer reflectively from here
    // instead. Nothing drained the fork's queue, so every runnable posted through
    // it was dropped FOREVER, while onDrawFrame kept ticking: the engine looks
    // alive, no thread appears parked, and a native loading screen that blocks on
    // one of those callbacks waits forever. Measured on HCR 1.68.1: 47,400 frames
    // at a constant `nonzero=155808/5881752 (2%)`, jstack clean, and a
    // GC.class_histogram at t=218s still holding live one-shot Runnables
    // (ConsentManager$$ExternalSyntheticLambda9,
    // MainActivity$1TimeRequestTask$$ExternalSyntheticLambda0) that would have
    // been garbage had they run. The A/B that isolates it: HCR 1.43 renders, ships
    // the SAME fork, takes the SAME EGL kill, and has ZERO app classes queueing
    // to the GL thread (vs 1.68.1's eleven).
    //
    // OUR OWN GLSurfaceView is unaffected and untouched: its queueEvent goes to
    // Handler.enqueueGlThread and is drained by Handler.drainGlobal().
    //
    // WHY DRAINING HERE IS SAFE, where reviving the thread was not: the
    // 2026-06-11 sentinel-EGL-context attempt let the fork's thread LIVE and
    // drain, which entered Stage-6 guest code from a thread the guest runtime
    // never prepared -- HCR 1.68.1 died with a NULL-pc SIGSEGV in nativeOnResume
    // and protected HCR 1.43 regressed to a GC-time crash in
    // frame::sender_for_compiled_frame. Draining on the DRIVING thread runs the
    // runnables on OS-main, the thread already executing nativeRender with ANGLE
    // current, which is also exactly where AOSP runs them (on Android the GL
    // thread IS the renderer thread). No EGL policy change is needed:
    // eglCreateContext must keep failing for every fork, unconditionally.
    // (Correction, v1.0.841: EGL10.EGL_NO_CONTEXT itself became a non-null
    // sentinel, docs/openttd-egl_2026-07-30.md -- that changes an OBJECT'S
    // identity, not whether this method's failure fires. libGDX's GLES3
    // probe (GLSurfaceView20$ContextFactory.createContext) also reads
    // eglGetError(), and separately is dead code on Mac.apk: our own
    // android.opengl.GLSurfaceView never invokes an EGLContextFactory at
    // all, it drives renderers with a fixed STUB_GL10 -- so neither this
    // comment's original claim nor the correction affects libGDX apps.)
    //
    // The `!isAlive()` gate is the safety property: an app whose GL thread DOES
    // survive owns its queue and is never touched here. (2026-09-26: and a
    // thread that survived the refusal and later EXITED is skipped too, see
    // the single-owner section below; before that, pelya's SDL fork -- which
    // does survive, docs/openttd-nativeinit-strchr-null_2026-09-26.md --
    // would have had its queue run on OS-main once its thread ended.)
    //
    // Rollback / A-B: MACAPK_GLTHREAD_DRAIN=0.
    private static final boolean sGlThreadDrain =
        !"0".equals(System.getenv("MACAPK_GLTHREAD_DRAIN"));

    // MACAPK_GLTHREAD_DRAIN_EMPTY (default ON, 2026-09-27;
    // docs/cocos2dx-touch-renderer-direct-retired_2026-09-27.md). Two AOSP
    // facts the one-per-frame drain below (v1.0.834) did not match:
    //  (1) DRAIN TO EMPTY. guardedRun takes one event, runs it, and `continue`s
    //      to the loop head, which takes the NEXT event before any draw step
    //      (android14 GLSurfaceView.java:1330-1331 + :1501-1504). So every
    //      event queued before a draw runs before that draw, including events
    //      a running event queues. The v1.0.834 comment read "one event per
    //      iteration" as "one event per frame"; the changelog records no other
    //      reason for it. On HCR that turned a touch drag into a backlog of
    //      stale MOVE/UP runnables, and it is why TouchDispatcher could not
    //      stop its own parallel delivery for a fork (every HCR touch was
    //      delivered twice: hcr_run.log 2026-09-25 :46444-46445).
    //  (2) THE VIEW'S CURRENT THREAD. queueEvent goes to whatever GLThread the
    //      view's field holds NOW. A re-attach puts a NEW thread with a NEW
    //      queue there (the fork's onAttachedToWindow, see "A SUCCESSOR OF A
    //      DEAD FORK" below; measured on HCR 1.68.1, sweep_hcr1681.log:8971).
    //      The v1.0.834 drain latched the first thread and would strand every
    //      event queued after such a re-attach, so the drain re-reads the field
    //      each frame and follows it. A live successor owns its own queue and
    //      is left alone, as before.
    // Nothing else changes: the same `!isAlive()` and single-owner survivor
    // tests decide whether OS-main may run the queue at all.
    // =0 runs macapkDrainForkedGlQueueOnePerFrame, the pre-change body verbatim.
    static final boolean sGlThreadDrainEmpty =
        !"0".equals(System.getenv("MACAPK_GLTHREAD_DRAIN_EMPTY"));
    // MACAPK_GLTHREAD_DRAIN_TRACE=1: log EVERY runnable the drain runs (class
    // name + queue left), not just #1/#10/#100, so a touch A/B can count
    // deliveries one to one. Diagnostic, default path only; changes nothing.
    private static final boolean sGlThreadDrainTrace =
        "1".equals(System.getenv("MACAPK_GLTHREAD_DRAIN_TRACE"));
    /** Safety valve for a runnable that re-queues itself on every run: AOSP
     *  would spin its own GL thread, here the loop is OS-main, so the rest waits
     *  one frame. Far above any measured burst (HCR 1.68.1 boot: 11). */
    private static final int GL_DRAIN_MAX_PER_FRAME = 4096;
    private boolean glDrainResolved;
    private volatile Thread glForkThread;
    private volatile java.util.List<?> glForkQueue;
    private volatile Object glForkMonitor;
    /** The view field resolveForkedGlQueue found the thread in. Re-read each
     *  frame (default path) to follow a re-attach, and by
     *  macapkForkGlQueueServiced from an input thread. */
    private volatile Field glForkField;
    /** macapkDrawFrame entries on this view, and the value of that count when
     *  the default-path drain last ran. A frame that returns before the drain
     *  (MACAPK_GL_SINGLE_OWNER's renderer-owned test, the wait-frame gate, "no
     *  renderer yet") does not stamp glDrainTick, so macapkForkGlQueueServiced
     *  can tell "the drain runs every frame" from "the drain is bound but no
     *  longer reached". OS-main writes both; an input thread reads them. */
    private volatile long glFrameTick;
    private volatile long glDrainTick;
    private long glDrainRan;
    private boolean glDrainCapLogged;

    /** Resolve the fork's GLThread + its event queue once, then run every
     *  queued runnable before this frame's draw, the way AOSP guardedRun does
     *  (MACAPK_GLTHREAD_DRAIN_EMPTY; =0 runs ONE per frame, the v1.0.834
     *  behaviour). Entirely structural: it keys on "a Thread field on the
     *  SurfaceView subclass" and "a List field on that Thread", never on a
     *  package or class name, so it applies to any AOSP-GLSurfaceView fork. */
    private void macapkDrainForkedGlQueue() {
        if (!sGlThreadDrainEmpty) { macapkDrainForkedGlQueueOnePerFrame(); return; }
        if (!sGlThreadDrain) return;
        if (!glDrainResolved) {
            glDrainResolved = true;
            try { resolveForkedGlQueue(); } catch (Throwable ignored) {}
        }
        // Whatever the bound thread's queue holds first, then (if the app has
        // since put a new thread in the field) the new thread's queue, so an
        // event queued on either side of a re-attach still runs before this
        // frame's draw.
        drainForkedGlQueueToEmpty();
        if (macapkFollowForkedGlThread()) drainForkedGlQueueToEmpty();
    }

    /** True when nothing but OS-main will ever run {@code t}'s queue: the
     *  thread has TERMINATED, and it is not one that SURVIVED the EGL refusal
     *  (MACAPK_GL_SINGLE_OWNER; on Android nothing runs a GL thread's queue
     *  after the thread is gone). TERMINATED, not the one-per-frame drain's
     *  `!isAlive()`: a NEW thread (the fork's `putfield mGLThread` a moment
     *  before `start()`, reachable here since the drain follows a re-attach) is
     *  about to run its own queue, so taking its events would steal them from
     *  it -- the same race the renderer drive already guards (see "a NEW thread
     *  (putfield before start())" in macapkRendererOwnedByAppGlThread). The
     *  one-per-frame body keeps its own test unchanged (gate row S1). */
    private boolean forkedGlQueueOrphaned(Thread t) {
        if (t == null || t.getState() != Thread.State.TERMINATED) return false;
        if (sGlSingleOwner && (glOwnerSurvivorName != null || sGlSurvivors.containsKey(t))) return false;
        return true;
    }

    /** Run every Runnable in the bound fork queue, FIFO, each OUTSIDE the
     *  queue's monitor (AOSP takes the event under sGLThreadManager and runs it
     *  after monitorexit), including ones queued by a runnable that runs. */
    private void drainForkedGlQueueToEmpty() {
        glDrainTick = glFrameTick;
        Thread t = glForkThread;
        java.util.List<?> q = glForkQueue;
        if (q == null || !forkedGlQueueOrphaned(t)) return;
        Object mon = (glForkMonitor != null) ? glForkMonitor : q;
        for (int i = 0; i < GL_DRAIN_MAX_PER_FRAME; i++) {
            Runnable r = null;
            int left;
            try {
                synchronized (mon) {
                    if (q.isEmpty()) return;
                    Object o = q.remove(0);
                    if (o instanceof Runnable) r = (Runnable) o;   // else drop, don't wedge
                    left = q.size();
                }
            } catch (Throwable ignored) { return; }
            if (r == null) continue;
            long n = ++glDrainRan;
            if (sGlThreadDrainTrace || n == 1 || n == 10 || (n % 100) == 0) {
                NativeLog.i(TAG, "forked GL-thread queue: ran #" + n + " "
                    + r.getClass().getName() + " (thread " + t.getName()
                    + " dead, queue left=" + left + ")"
                    + (sGlThreadDrainTrace ? " fields:" + macapkRunnableFields(r) : ""));
            }
            try {
                r.run();
            } catch (Throwable e) {
                NativeLog.w(TAG, "forked GL-thread runnable " + r.getClass().getName()
                    + " threw: " + e);
            }
        }
        if (!glDrainCapLogged) {
            glDrainCapLogged = true;
            NativeLog.w(TAG, "forked GL-thread queue: " + GL_DRAIN_MAX_PER_FRAME
                + " runnables in one frame (a runnable re-queues itself?); the rest"
                + " run next frame");
        }
    }

    /** MACAPK_GLTHREAD_DRAIN_TRACE only: the drained runnable's own non-static
     *  int / float / int[] / float[] field values, " name=value" each, read by
     *  reflection with no names assumed, so a touch A/B can pair a drained
     *  event's coordinates (a cocos2d-x runnable captures them as fields) with
     *  the injected MotionEvent. Called before run(), so it shows what the
     *  event was queued with. Never throws. */
    private static String macapkRunnableFields(Object r) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : r.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ty = f.getType();
                if (ty != int.class && ty != float.class
                        && ty != int[].class && ty != float[].class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(r);
                    String s = (v instanceof int[]) ? java.util.Arrays.toString((int[]) v)
                        : (v instanceof float[]) ? java.util.Arrays.toString((float[]) v)
                        : String.valueOf(v);
                    sb.append(' ').append(f.getName()).append('=').append(s);
                } catch (Throwable ignored) { /* next field */ }
            }
        } catch (Throwable ignored) { /* class not introspectable */ }
        return sb.toString();
    }

    /** Follow the view's CURRENT GL thread: re-read the field the thread was
     *  found in and, when the app has put a different Thread there, re-bind the
     *  drain to that thread's own queue. Returns true when it re-bound. */
    private boolean macapkFollowForkedGlThread() {
        Field f = glForkField;
        if (f == null) return false;
        Object v;
        try { v = f.get(this); } catch (Throwable e) { return false; }
        if (!(v instanceof Thread) || v == glForkThread) return false;
        Thread nt = (Thread) v;
        java.util.List<?> nq;
        try { nq = findForkedGlQueue(nt); } catch (Throwable e) { return false; }
        if (nq == null) return false;
        Thread was = glForkThread;
        glForkQueue = nq;
        glForkThread = nt;
        NativeLog.i(TAG, "forked GL-thread queue re-bound: the view now holds "
            + nt.getClass().getName() + " alive=" + nt.isAlive() + " size=" + nq.size()
            + " (was " + (was != null ? was.getName() + " alive=" + was.isAlive() : "none") + ")");
        return true;
    }

    /** Mac.apk framework-internal (TouchDispatcher, MACAPK_INPUT_UI_THREAD):
     *  true when OUR OS-main frame callback draws this view's content, i.e.
     *  macapkDrawFrame has resolved a renderer it calls onDrawFrame on (the
     *  reflected-renderer drive; GLSurfaceView adds its setRenderer branch).
     *  That is the one case where the AppKit thread IS this app's GL thread,
     *  so host input stays there. A SurfaceView whose pixels come from the
     *  app's OWN threads (a native EGL window surface -- Organic Maps' drape
     *  engine, Unity, SDL, GameActivity -- or a video/camera producer) answers
     *  false: on Android its input arrives on the UI thread like any view's
     *  (ViewRootImpl.setView binds WindowInputEventReceiver to Looper.myLooper()
     *  of the window's thread), and apps assert that (LiveData.setValue,
     *  Organic Maps' Storage ThreadChecker CHECK). Read off the loop thread;
     *  the two fields are written once each by the resolver and a stale read
     *  only picks the other route for one event.
     *
     *  A RESOLVED renderer is not enough: under MACAPK_GL_SINGLE_OWNER OS-main
     *  declines a renderer the app's own GL thread owns (an AOSP-derived
     *  GLThread fork that is alive, or survived the EGL refusal: pelya's SDL
     *  GLSurfaceView_SDL in OpenTTD, HCR's FSGLSurfaceView until its fork
     *  dies), and then the AppKit thread is NOT the app's GL thread either, so
     *  the input belongs on the UI thread like any other app-rendered surface.
     *  This reads OS-main's own per-frame verdict (osMainDeclinedRenderer)
     *  instead of re-running the ownership scan here: that scan memoises into
     *  plain fields and must stay on OS-main. */
    public boolean macapkRendersOnOsMain() {
        return reflectedRenderer != null && reflectedOnDrawFrame != null
            && !osMainDeclinedRenderer;
    }

    /** OS-main's verdict for this frame: true when the app's own GL thread
     *  owns the resolved renderer, so macapkDrawFrame does not enter it
     *  (MACAPK_GL_SINGLE_OWNER). Recorded for macapkRendersOnOsMain, which
     *  other threads read. Call on OS-main only (macapkDrawFrame). */
    boolean macapkOsMainDeclinesRenderer() {
        boolean declined = reflectedRenderer != null && macapkRendererOwnedByAppGlThread();
        osMainDeclinedRenderer = declined;
        return declined;
    }
    /** Last macapkOsMainDeclinesRenderer verdict (written on OS-main only). */
    private volatile boolean osMainDeclinedRenderer;

    /** Mac.apk framework-internal (TouchDispatcher, MACAPK_TOUCH_APP_QUEUE_ONLY):
     *  true when an event this view's own code queues NOW (onTouchEvent ->
     *  queueEvent -> the thread the view's field holds) will be run by this
     *  class before the next onDrawFrame. That needs all of: the drain on and
     *  draining to empty, bound (a frame has resolved it) to exactly the thread
     *  the field holds now, and that thread orphaned. Anything else, including
     *  a re-attach the drain has not re-bound yet, answers false, so the caller
     *  keeps its own delivery rather than leave the event in a queue nobody
     *  runs. */
    public boolean macapkForkGlQueueServiced() {
        if (!sGlThreadDrain || !sGlThreadDrainEmpty) return false;
        Field f = glForkField;
        Thread t = glForkThread;
        if (f == null || t == null || glForkQueue == null) return false;
        Object cur;
        try { cur = f.get(this); } catch (Throwable e) { return false; }
        if (cur != t) return false;
        // TERMINATED, not merely !isAlive(): a NEW thread (the fork's own
        // `putfield mGLThread` a moment before `start()`) is about to own its
        // queue, so the caller keeps its delivery until it has actually died.
        if (t.getState() != Thread.State.TERMINATED) return false;
        // The drain must still be REACHED: it binds only the first Thread-valued
        // field, while macapkDrawFrame's renderer-owned early return scans every
        // candidate, so a fork with a second live Thread could be bound here
        // and never drained (the touch would then be LOST). One missed frame is
        // allowed for the race between the tick and the drain within a frame.
        if (glDrainTick < glFrameTick - 1) return false;
        return forkedGlQueueOrphaned(t);
    }

    /** MACAPK_GLTHREAD_DRAIN_EMPTY=0: the v1.0.834 drain, verbatim -- resolve
     *  once, then at most ONE queued runnable per frame. */
    private void macapkDrainForkedGlQueueOnePerFrame() {
        if (!sGlThreadDrain) return;
        if (!glDrainResolved) {
            glDrainResolved = true;
            try { resolveForkedGlQueue(); } catch (Throwable ignored) {}
        }
        Thread t = glForkThread;
        java.util.List<?> q = glForkQueue;
        if (t == null || q == null) return;
        // The whole point: only take over for a thread that is NOT running. A
        // live GL thread drains its own queue and must not be raced.
        if (t.isAlive()) return;
        // ...nor for one that SURVIVED the EGL refusal and has since exited
        // (MACAPK_GL_SINGLE_OWNER, 2026-09-26). OS-main never stands in for
        // that renderer, so it must not stand in for its queue either: on
        // Android nothing runs a GL thread's queue after the thread is gone.
        if (sGlSingleOwner && (glOwnerSurvivorName != null || sGlSurvivors.containsKey(t))) return;
        Runnable r = null;
        Object mon = (glForkMonitor != null) ? glForkMonitor : q;
        try {
            synchronized (mon) {
                if (!q.isEmpty()) {
                    Object o = q.get(0);
                    if (o instanceof Runnable) { r = (Runnable) o; q.remove(0); }
                    else { q.remove(0); }   // not a Runnable: drop, don't wedge
                }
            }
        } catch (Throwable ignored) { return; }
        if (r == null) return;
        long n = ++glDrainRan;
        if (n == 1 || n == 10 || (n % 100) == 0) {
            NativeLog.i(TAG, "forked GL-thread queue: ran #" + n + " "
                + r.getClass().getName() + " (thread " + t.getName()
                + " dead, queue left=" + q.size() + ")");
        }
        try {
            r.run();
        } catch (Throwable e) {
            NativeLog.w(TAG, "forked GL-thread runnable " + r.getClass().getName()
                + " threw: " + e);
        }
    }

    private void resolveForkedGlQueue() {
        // 1. A Thread-valued field on this SurfaceView's subclass chain is the
        //    fork's GLThread (AOSP names it mGLThread; we do not rely on that).
        Class<?> cls = getClass();
        Thread found = null;
        Class<?> foundOn = null;
        Field foundField = null;
        while (cls != null && cls != SurfaceView.class && cls != View.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(this);
                    if (v instanceof Thread) { found = (Thread) v; foundOn = cls; foundField = f; break; }
                } catch (Throwable ignore) { /* next field */ }
            }
            if (found != null) break;
            cls = cls.getSuperclass();
        }
        if (found == null) return;
        java.util.List<?> queue = findForkedGlQueue(found);
        if (queue == null) return;
        // 3. AOSP guards mEventQueue with the STATIC sGLThreadManager on the
        //    GLSurfaceView class, not with the thread. Use it when discoverable
        //    so a concurrent queueEvent from an app thread interlocks properly;
        //    fall back to the list itself (the thread is dead, so the only other
        //    mutator is queueEvent, and a missed frame just retries next vsync).
        Object mon = null;
        if (foundOn != null) {
            for (Field f : foundOn.getDeclaredFields()) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && v.getClass().getName().endsWith("GLThreadManager")) {
                        mon = v; break;
                    }
                } catch (Throwable ignore) { /* next field */ }
            }
        }
        glForkThread = found;
        glForkQueue = queue;
        glForkMonitor = mon;
        glForkField = foundField;
        NativeLog.i(TAG, "forked GL-thread queue resolved: thread="
            + found.getClass().getName() + " alive=" + found.isAlive()
            + " queue=" + queue.getClass().getName() + " size=" + queue.size()
            + " monitor=" + (mon != null ? mon.getClass().getName() : "list"));
    }

    /** 2. of resolveForkedGlQueue: a List-valued field on the fork's Thread is
     *  its event queue (AOSP: `private ArrayList<Runnable> mEventQueue = new
     *  ArrayList<>();`). Prefer a field whose current contents are Runnables;
     *  otherwise take the first List, since the queue is normally empty at
     *  resolve time. Also used to re-bind to a re-attached successor. */
    private static java.util.List<?> findForkedGlQueue(Thread found) {
        java.util.List<?> queue = null;
        Class<?> tc = found.getClass();
        while (tc != null && tc != Thread.class && tc != Object.class) {
            for (Field f : tc.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(found);
                    if (!(v instanceof java.util.List)) continue;
                    java.util.List<?> l = (java.util.List<?>) v;
                    if (!l.isEmpty() && !(l.get(0) instanceof Runnable)) continue;
                    if (queue == null || !l.isEmpty()) queue = l;
                } catch (Throwable ignore) { /* next field */ }
            }
            tc = tc.getSuperclass();
        }
        return queue;
    }

    // ---- EGL10: is this thread a SurfaceView's own GL thread? -----------
    //
    // WHY THIS EXISTS (2026-09-26, docs/telegram-intro-logo_2026-09-26.md §5).
    // javax.microedition.khronos.egl.MacApkEGL10 used to refuse EVERY
    // eglCreateContext, because an app that vendors AOSP's GLSurfaceView (a
    // "fork": HCR's FSGLSurfaceView, OpenTTD's GLSurfaceView_SDL) must have its
    // private GL thread DIE at eglCreateContext (changelog v1.0.347; the drain
    // above depends on `!t.isAlive()`). That refusal also killed every
    // legitimate app-owned EGL10 thread, such as Telegram's IntroActivity
    // EGLThread rendering into a TextureView. MacApkEGL10 is now a real EGL10
    // over ANGLE and keeps the refusal ONLY for a thread this method names.
    //
    // THE KEY is the same structural one resolveForkedGlQueue uses: a field on
    // a live SurfaceView SUBCLASS chain (up to, not including, SurfaceView)
    // whose value is that Thread. No class or package names, so it is R8-proof.
    // Measured on the app bytecode (debug/appclass.py, 2026-09-26): all three
    // HCR builds (1.43.0, 1.68.1, 1.8.1) declare
    // `private FSGLSurfaceView$GLThread mGLThread` on a class that extends
    // android.view.SurfaceView, and OpenTTD 14.1 declares
    // `private GLSurfaceView_SDL$GLThread mGLThread` the same way. In all four,
    // setRenderer does `putfield mGLThread` BEFORE `GLThread.start()`, so the
    // field is set before the thread can make its first EGL call; there is no
    // ordering race. Telegram's EGLThread is a field of IntroActivity (a
    // BaseFragment, not a View), so it is not matched.
    //
    // Deliberately BROADER than resolveForkedGlQueue on one axis: ANY field
    // holding the thread matches (that walk takes the first Thread-valued
    // field). A false positive here only restores the pre-2026-09-26 refusal,
    // which is the safe side of the fork invariant. A SECOND, reverse key (the
    // thread's own fields point back at a SurfaceView) catches a fork that
    // keeps its thread somewhere other than a view field; see
    // threadHoldsSurfaceView below. Either key matching is enough.
    //
    // The registry holds weak references, so it never keeps a view alive, and
    // it is only ever READ by MacApkEGL10; with MACAPK_EGL10_REAL=0 nothing
    // queries it and it has no observable effect.
    private static final java.util.ArrayList<java.lang.ref.WeakReference<SurfaceView>>
        sLiveForEgl = new java.util.ArrayList<>();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Field[]>
        sGlThreadFieldCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static void macapkRegisterLiveForEgl(SurfaceView v) {
        synchronized (sLiveForEgl) {
            // Prune cleared entries as we go so the list tracks live views.
            for (int i = sLiveForEgl.size() - 1; i >= 0; i--) {
                if (sLiveForEgl.get(i).get() == null) sLiveForEgl.remove(i);
            }
            sLiveForEgl.add(new java.lang.ref.WeakReference<>(v));
        }
    }

    /** Mac.apk framework-internal: true when {@code t} is a SurfaceView's own
     *  (forked) GL thread: its own fields point at a SurfaceView (directly or
     *  through a Reference), or a field of a live SurfaceView subclass holds
     *  it. See the section comment above for the contract and evidence. */
    public static boolean macapkIsSurfaceViewGlThread(Thread t) {
        if (t == null) return false;
        if (threadHoldsSurfaceView(t)) return true; /*REVERSE*/
        java.util.ArrayList<SurfaceView> views = new java.util.ArrayList<>();
        synchronized (sLiveForEgl) {
            for (int i = sLiveForEgl.size() - 1; i >= 0; i--) {
                SurfaceView v = sLiveForEgl.get(i).get();
                if (v == null) sLiveForEgl.remove(i);
                else views.add(v);
            }
        }
        for (SurfaceView v : views) {
            for (Field f : glThreadCandidateFields(v.getClass())) {
                try {
                    Object val = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        ? f.get(null) : f.get(v);
                    if (val == t) return true;
                } catch (Throwable ignore) { /* next field */ }
            }
        }
        return false;
    }

    // THE REVERSE KEY (fix pass, same day): the THREAD holds its view. The
    // forward key above only sees a thread a view field holds DIRECTLY; a fork
    // that keeps its thread elsewhere (a holder object, the Activity, a static
    // on the thread class) would escape it, get a real context, then
    // EGL_BAD_NATIVE_WINDOW for its SurfaceHolder window, and AOSP guardedRun
    // would park with mSurfaceIsBad while still draining mEventQueue: a
    // surviving event-draining fork, the exact hazard the refusal exists for.
    // But every GLSurfaceView fork's GLThread points BACK at its view, because
    // AOSP's does: `mGLSurfaceViewWeakRef` (frameworks/base opengl/java/
    // android/opengl/GLSurfaceView.java, android14 lineage-21.0 mirror :1254,
    // `GLThread(WeakReference<GLSurfaceView> glSurfaceViewWeakRef)`) or, in
    // older copies with a non-static inner GLThread, its own `this$0`. Measured on the app
    // bytecode (javap on the converted classes.jar, 2026-09-26): HCR 1.43.0 /
    // 1.68.1 / 1.8.1 FSGLSurfaceView$GLThread, superjetpack's
    // GLSurfaceViewMortar$GLThread and its Unity ar GLSurfaceView$GLThread all
    // hold `WeakReference mGLSurfaceViewWeakRef`; OpenTTD's
    // GLSurfaceView_SDL$GLThread holds `this$0`. So every known fork is now
    // matched from BOTH ends. Not matched, measured the same way: Telegram
    // 12.9.1's eight EGL10 thread classes (IntroActivity$EGLThread, the two
    // CameraGLThreads, FilterGLThread, RenderView$CanvasInternal,
    // ThanosEffect$DrawingThread, SpoilerEffect2$SpoilerThread,
    // GLIconTextureView$RenderThread: no view field, or a this$0 that is an
    // IntroActivity, a FrameLayout or a TextureView) and Instagram's three GLTextureView
    // GLThreads (X.pvy / X.pwA / X.pwB, whose WeakReference is to a TextureView
    // subclass), so a TextureView renderer keeps its real context.
    //
    // Rule: a field declared on the thread's class chain below java.lang.Thread
    // (never Thread's own internals) whose value is a SurfaceView, directly or
    // through a java.lang.ref.Reference. Any SurfaceView counts, not only a
    // subclass: a thread holding a SurfaceView can only target a SurfaceView
    // window, which MacApkEGL10 refuses anyway, so a match there costs nothing
    // and restores the pre-change answer, the safe side.

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Field[]>
        sThreadViewFieldCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean threadHoldsSurfaceView(Thread t) {
        for (Field f : threadViewCandidateFields(t.getClass())) {
            try {
                Object v = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    ? f.get(null) : f.get(t);
                if (v instanceof java.lang.ref.Reference) {
                    v = ((java.lang.ref.Reference<?>) v).get();
                }
                if (v instanceof SurfaceView) return true;
            } catch (Throwable ignore) { /* next field */ }
        }
        return false;
    }

    /** Every reference-typed field declared on {@code c}'s chain below
     *  java.lang.Thread, made accessible once and cached per class. A plain
     *  Thread (or HandlerThread) has none that can hold a view. */
    private static Field[] threadViewCandidateFields(Class<?> c) {
        Field[] cached = sThreadViewFieldCache.get(c);
        if (cached != null) return cached;
        java.util.ArrayList<Field> out = new java.util.ArrayList<>();
        Class<?> cls = c;
        while (cls != null && cls != Thread.class) {
            try {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    try { f.setAccessible(true); out.add(f); }
                    catch (Throwable ignore) { /* a java.* class: skip */ }
                }
            } catch (Throwable ignore) { /* a field type failed to link: skip class */ }
            cls = cls.getSuperclass();
        }
        Field[] arr = out.toArray(new Field[0]);
        sThreadViewFieldCache.put(c, arr);
        return arr;
    }

    /** Every reference-typed field declared on {@code c}'s chain below
     *  SurfaceView (the resolveForkedGlQueue bounds), made accessible once and
     *  cached per class. A plain SurfaceView has none. */
    private static Field[] glThreadCandidateFields(Class<?> c) {
        Field[] cached = sGlThreadFieldCache.get(c);
        if (cached != null) return cached;
        java.util.ArrayList<Field> out = new java.util.ArrayList<>();
        Class<?> cls = c;
        while (cls != null && cls != SurfaceView.class && cls != View.class) {
            try {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    try { f.setAccessible(true); out.add(f); }
                    catch (Throwable ignore) { /* inaccessible: skip */ }
                }
            } catch (Throwable ignore) { /* a field type failed to link: skip class */ }
            cls = cls.getSuperclass();
        }
        Field[] arr = out.toArray(new Field[0]);
        sGlThreadFieldCache.put(c, arr);
        return arr;
    }

    // ---- Single owner: a renderer is driven by ONE thread ----------------
    //
    // WHY THIS EXISTS (2026-09-26, docs/openttd-nativeinit-strchr-null_2026-09-26.md).
    // AOSP's threading contract is one renderer, one GL thread, never the UI
    // thread: "The renderer will be called on a separate thread, so that
    // rendering performance is decoupled from the UI thread" (frameworks/base
    // opengl/java/android/opengl/GLSurfaceView.java:690, aosp-mirror master,
    // fetched 2026-09-26). The reflected drive in this file (macapkSurfaceChanged
    // / macapkDrawFrame calling a DISCOVERED renderer on OS-main) is Mac.apk's
    // stand-in for an app GL thread that is DEAD -- the vendored-GLSurfaceView
    // forks die at MacApkEGL10's refused eglCreateContext. It used to run
    // unconditionally, so it raced any fork that did NOT die.
    //
    // pelya's libsdl-android fork (OpenTTD's GLSurfaceView_SDL) does not die:
    // its EglHelper.start() only Log.e's an EGL_NO_CONTEXT (bytecode, OpenTTD
    // 14.1: `if_acmpne` over EGL_NO_CONTEXT to a Log.e, no athrow), then
    // createSurface() calls eglCreateWindowSurface, and GLThread.run() calls
    // mRenderer.onDrawFrame ONCE -- which is SDL's whole main loop. With OS-main
    // also calling onDrawFrame, libsdl_main's nativeInit ran twice; it adds to a
    // never-reset `static int argc`, so the second call walked argc=6 over a
    // fresh 3-entry argv and did strchr(argv[3]=NULL, '\t'). The first caller
    // was OS-main, which then sat inside SDL_main forever: no run loop, no
    // present, no input.
    //
    // THE RULE (MACAPK_GL_SINGLE_OWNER, default ON; =0 restores the old
    // unconditional drive exactly): OS-main does not enter this view's
    // reflected renderer while the app's own GL thread
    //   (a) is ALIVE, or
    //   (b) was ever marked a SURVIVOR of the EGL refusal (sticky).
    // While (a) holds the reflected onSurfaceChanged/onSurfaceCreated are
    // DEFERRED, not dropped, and replayed once, in the order macapkSurfaceChanged
    // delivers them, on the first frame the gate opens. Only (a) closes the
    // measured startup race deterministically: OpenTTD's first reflected
    // onSurfaceChanged (run.log:3576) came BEFORE its fork was refused (:3599).
    //
    // REPLAY ORDER (a disagreement, kept visible). The diagnosis note asks for onSurfaceCreated THEN
    // onSurfaceChanged. That would invert the order this path has always used
    // (onSurfaceChanged, setScreenWidthAndHeight, then onSurfaceCreated; see
    // macapkReflectedSurfaceChanged): cocos2d-x's Cocos2dxRenderer.onSurfaceCreated
    // reads screenWidth/screenHeight for nativeInit (bytecode, HCR 1.43.0 /
    // 1.68.1 / 1.8.1: getfield screenWidth/screenHeight inside onSurfaceCreated),
    // so created-first hands HCR a 0x0 viewport. The replay therefore runs the
    // UNCHANGED sequence, just later. The conflict is left visible here on purpose.
    //
    // WHICH THREAD IS "THE APP'S OWN GL THREAD". A Thread-valued field on the
    // view's subclass chain (the forward key macapkIsSurfaceViewGlThread uses),
    // TIGHTENED: the thread must also point back at THIS view (directly or
    // through a Reference: AOSP GLThread's mGLSurfaceViewWeakRef, an inner
    // class's this$0) or hold the discovered renderer itself (pelya's
    // GLThread.mRenderer). A live worker thread a view happens to hold does not
    // close the gate. Measured on the app bytecode (javap, 2026-09-26): HCR
    // 1.43.0 / 1.68.1 / 1.8.1 FSGLSurfaceView holds `FSGLSurfaceView$GLThread
    // mGLThread`, whose `WeakReference<FSGLSurfaceView> mGLSurfaceViewWeakRef`
    // points back; OpenTTD's GLSurfaceView_SDL holds `GLSurfaceView_SDL$GLThread
    // mGLThread`, which holds both `this$0` and `mRenderer`.
    //
    // WHY HCR IS STILL DRIVEN (all three builds, same bytecode shape): its
    // EglHelper.start() does `if (mEglContext == null || mEglContext ==
    // EGL_NO_CONTEXT) throwEglException("createContext")` (AOSP :1062-1064),
    // guardedRun releases the context slot and rethrows, and run()'s finally
    // only calls GLThreadManager.threadExiting -- so the thread dies having made
    // none of the calls that mark a survivor (eglCreateWindowSurface, a real
    // eglMakeCurrent, eglSwapBuffers; createSurface and swap come after start()
    // in guardedRun). The gate opens on the next frame and OS-main drives it as
    // before, one replayed surface delivery later. Verified per build with
    // debug/appclass.py on the converted classes.jar (2026-09-26): start() has
    // `ifnull`/`if_acmpne EGL_NO_CONTEXT` -> throwEglException("createContext")
    // -> `new RuntimeException`; guardedRun's RuntimeException handler around
    // start() is releaseEglContextLocked + athrow; run() catches only
    // InterruptedException. And every sweep/boottime log of all three builds
    // on 2026-09-26 shows `FATAL EXCEPTION: GLThread N` / `createContext failed:
    // 12293` right after the refusal line.
    //
    // DYING COUNTS AS DEAD. A thread whose run() has already thrown is still
    // isAlive() while the VM runs its uncaught-exception handler
    // (java.lang.Thread.dispatchUncaughtException, JDK 21: "called when a
    // thread terminates with an exception"; the thread is only marked dead by
    // JavaThread::exit's ensure_join afterwards). HCR 1.68.1 installs
    // Crashlytics as that handler, and it spent ~2.3 s writing its report for
    // the refused fork (boottime warm log, 2026-09-26 05:39: refusal by
    // 2.50 s, Boot's FATAL line at 4.79 s) -- time in which the pre-fix code was
    // already driving the renderer. Super Jetpack's GLSurfaceViewMortar fork
    // dies EARLIER, never reaching eglCreateContext at all: its
    // IntelligentConfigChooser throws "No configs match configSpec" inside
    // EglHelper.start (it asks for EGL_RENDERABLE_TYPE 0x40, ES3, which the
    // legacy table does not advertise), and its Crashlytics handler then sits
    // in "Timeout exceeded while awaiting app exception callback from Analytics
    // listener" (emu_ab_tablet_2026-09-25 run.log:6189-6191). So the rule
    // cannot be keyed on MacApkEGL10's refusal: for ANY thread that owns this
    // renderer, "running its uncaught-exception handler" counts as dead, and a
    // thread in that state can never return to its GL loop. The first frame
    // keeps its pre-fix timing. A survivor is checked first and never takes
    // this path. The fork's EVENT QUEUE still waits for the real death, as it
    // always did (macapkDrainForkedGlQueue keeps its plain isAlive test).
    //
    // A SUCCESSOR OF A DEAD FORK DOES NOT CLOSE THE GATE. An AOSP fork that is
    // detached and re-attached starts a NEW GLThread of the SAME class (AOSP
    // onAttachedToWindow :610-624; HCR 1.43.0 / 1.68.1 / 1.8.1 bytecode, javap
    // 2026-09-26: `if mDetached && mRenderer != null` -> `new
    // FSGLSurfaceView$GLThread(mThisWeakRef)`, putfield mGLThread, start()).
    // That thread waits for a surfaceCreated this class never re-sends
    // (holderSurfaceCreatedFired is latched for the view's life, and
    // SurfaceHolderImpl.fireDestroyed has no caller: we have no detach/attach
    // surface lifecycle at all), so it stays alive forever without reaching
    // EGL. Under the alive rule alone it would close the gate for good and
    // freeze a renderer the pre-fix code kept driving. So once a thread of
    // this view has TERMINATED (or entered its uncaught-exception handler)
    // without surviving, its class is recorded, and a later live thread of
    // that class does not count as the owner: the refusal that killed it is
    // deterministic, so the successor dies the same way if it ever reaches
    // EGL. It can still take the renderer by SURVIVING (rule (b) is checked
    // first). NEW (constructed, not yet started) is not "terminated": HCR's
    // own putfield-then-start() leaves a NEW thread in the field for a moment,
    // and recording that as dead would hand a pelya-shaped fork's renderer to
    // OS-main. Missing piece, not this rule's job: AOSP's surface lifecycle
    // (surfaceDestroyed on detach, surfaceCreated + surfaceChanged on
    // re-attach), without which the successor never runs at all -- exactly as
    // before this change.
    //
    // KNOWN RESIDUAL. A survivor reachable ONLY through its own back-pointer
    // (no view field holds it) is caught by the survivor mark, which runs
    // after its eglCreateWindowSurface; between the refusal and that call it
    // is not yet known. No corpus fork has that shape.
    static final boolean sGlSingleOwner =
        !"0".equals(System.getenv("MACAPK_GL_SINGLE_OWNER"));

    /** Threads MacApkEGL10 saw survive its refusal, with the call that showed
     *  it. Weak keys: a dead, unreferenced thread must not be kept alive. */
    private static final java.util.Map<Thread, String> sGlSurvivors =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** True while {@code t} is running its uncaught-exception handler: its run()
     *  has already terminated with an exception, and the VM is inside
     *  java.lang.Thread.dispatchUncaughtException, which it calls as the
     *  thread's only remaining Java frame (the handler's frames sit above it).
     *  Such a thread can never re-enter its GL loop. Costs one walk of that
     *  thread's stack per call (measured 2.4-2.9 us for a parked thread, JDK
     *  21 on this Mac, 2026-09-26), asked only about a live thread that owns this
     *  view's renderer, is not a survivor and is not the successor of a dead
     *  fork class -- a transient state (a fork parked before EGL, or one on
     *  its way out). The one long-lived case is a live app thread that never
     *  reaches EGL at all (Wikipedia's MapLibre VulkanThread is the corpus
     *  example): one walk per frame for its whole life. An empty stack
     *  means the thread has not started or has already finished, and answers
     *  false. */
    private static boolean dyingOfUncaughtException(Thread t) {
        StackTraceElement[] st;
        try {
            st = t.getStackTrace();
        } catch (Throwable e) {
            return false;
        }
        if (st == null || st.length == 0) return false;
        StackTraceElement bottom = st[st.length - 1];
        return "java.lang.Thread".equals(bottom.getClassName())
            && "dispatchUncaughtException".equals(bottom.getMethodName());
    }

    /** Deferred reflected surface size (0 = nothing deferred). */
    private int reflectedDeferredW, reflectedDeferredH;
    /** The deferred size above was kept because the renderer did not exist
     *  yet (MACAPK_SV_LATE_RENDERER), not because its GL thread owned it. */
    private boolean reflectedDeferredLate;
    /** Sticky: set (from the fork's own thread) once this view's GL thread
     *  survived the refusal; never cleared, so a nulled field or a collected
     *  Thread cannot reopen the drive. */
    private volatile String glOwnerSurvivorName;
    /** Memo of the tightened-key verdict for the last thread seen, so the
     *  per-frame scan does not re-walk that thread's fields. */
    private Thread glOwnerMemoThread;
    private boolean glOwnerMemoBelongs;
    /** Class of a GL thread of this view that terminated (or entered its
     *  uncaught-exception handler) without surviving the refusal; a later live
     *  thread of the same class is its successor and does not close the gate
     *  (see "A SUCCESSOR OF A DEAD FORK" above). Read and written on OS-main. */
    private Class<?> glOwnerDeadForkClass;
    /** Decision states already logged for this view (bit per state). */
    private int glOwnerLogged;
    private static final int OWNER_LOG_ALIVE = 1, OWNER_LOG_SURVIVED = 2,
                             OWNER_LOG_RELEASED = 4, OWNER_LOG_SUCCESSOR = 8;

    /** Called by MacApkEGL10 when a thread it REFUSED (legacyCreateContext)
     *  goes on to call {@code call} -- eglCreateWindowSurface, a non-unbind
     *  eglMakeCurrent, or eglSwapBuffers -- which an AOSP-derived fork never
     *  does after its start() threw. Marks the thread, and stickily every live
     *  view it belongs to (either key), as never to be driven from OS-main. */
    public static void macapkMarkGlThreadSurvivedRefusal(Thread t, String call) {
        if (!sGlSingleOwner || t == null) return;
        if (sGlSurvivors.putIfAbsent(t, call) != null) return;
        java.util.ArrayList<SurfaceView> views = new java.util.ArrayList<>();
        synchronized (sLiveForEgl) {
            for (int i = sLiveForEgl.size() - 1; i >= 0; i--) {
                SurfaceView v = sLiveForEgl.get(i).get();
                if (v == null) sLiveForEgl.remove(i);
                else views.add(v);
            }
        }
        StringBuilder owners = new StringBuilder();
        for (SurfaceView v : views) {
            if (viewHoldsThread(v, t) || threadPointsAt(t, v, v.reflectedRenderer)) {
                v.glOwnerSurvivorName = t.getName();
                if (owners.length() > 0) owners.append(", ");
                owners.append(v.getClass().getName());
            }
        }
        NativeLog.i(TAG, "GL thread " + t.getName() + " survived the EGL"
            + " refusal (" + call + " after EGL_NO_CONTEXT); OS-main will not drive"
            + " its renderer: views [" + owners + "]");
    }

    private static boolean viewHoldsThread(SurfaceView v, Thread t) {
        for (Field f : glThreadCandidateFields(v.getClass())) {
            try {
                Object val = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    ? f.get(null) : f.get(v);
                if (val == t) return true;
            } catch (Throwable ignore) { /* next field */ }
        }
        return false;
    }

    /** True when one of {@code t}'s own fields (below java.lang.Thread) holds
     *  {@code view}, directly or through a Reference, or holds {@code renderer}. */
    private static boolean threadPointsAt(Thread t, Object view, Object renderer) {
        for (Field f : threadViewCandidateFields(t.getClass())) {
            try {
                Object v = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    ? f.get(null) : f.get(t);
                if (v instanceof java.lang.ref.Reference) {
                    v = ((java.lang.ref.Reference<?>) v).get();
                }
                if (v == null) continue;
                if (v == view || (renderer != null && v == renderer)) return true;
            } catch (Throwable ignore) { /* next field */ }
        }
        return false;
    }

    /** Mac.apk framework-internal: does the app's own GL thread own this
     *  view's reflected renderer right now? Evaluated on OS-main, never blocks
     *  (reflective reads only). See the section comment for the rule. */
    boolean macapkRendererOwnedByAppGlThread() {
        if (!sGlSingleOwner) return false;
        Object renderer = reflectedRenderer;
        if (renderer == null) return false;
        String survivor = glOwnerSurvivorName;
        if (survivor != null) {
            macapkLogOwner(OWNER_LOG_SURVIVED, survivor, "survived EGL refusal");
            return true;
        }
        Thread alive = null;
        for (Field f : glThreadCandidateFields(getClass())) {
            Object v;
            try {
                v = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    ? f.get(null) : f.get(this);
            } catch (Throwable ignore) { continue; }
            if (!(v instanceof Thread)) continue;
            Thread t = (Thread) v;
            boolean belongs;
            if (t == glOwnerMemoThread) {
                belongs = glOwnerMemoBelongs;
            } else {
                belongs = threadPointsAt(t, this, renderer); /*BELONGS*/
                glOwnerMemoThread = t;
                glOwnerMemoBelongs = belongs;
            }
            if (!belongs) continue;
            if (sGlSurvivors.containsKey(t)) {
                glOwnerSurvivorName = t.getName();
                macapkLogOwner(OWNER_LOG_SURVIVED, t.getName(), "survived EGL refusal");
                return true;
            }
            if (alive != null) continue;
            // TERMINATED, not merely !isAlive(): a NEW thread (putfield before
            // start()) has not died and must not be recorded as a dead fork.
            boolean dead = t.getState() == Thread.State.TERMINATED; /*TERMINATED*/
            if (!dead && !t.isAlive()) continue;   // NEW: owns nothing yet
            if (!dead && t.getClass() == glOwnerDeadForkClass) { /*SUCCESSOR*/
                // A re-created fork of a class that already died here without
                // surviving (see "A SUCCESSOR OF A DEAD FORK" above).
                macapkLogSuccessor(t);
                continue;
            }
            // A thread already inside its uncaught-exception handler is dead as
            // a GL thread (see "DYING COUNTS AS DEAD" above).
            if (!dead && dyingOfUncaughtException(t)) dead = true; /*DYING*/
            if (dead) {
                glOwnerDeadForkClass = t.getClass();
                continue;
            }
            alive = t; /*ALIVE*/
        }
        if (alive != null) {
            macapkLogOwner(OWNER_LOG_ALIVE, alive.getName(), "alive");
            return true;
        }
        if ((glOwnerLogged & OWNER_LOG_ALIVE) != 0
                && (glOwnerLogged & OWNER_LOG_RELEASED) == 0) {
            glOwnerLogged |= OWNER_LOG_RELEASED;
            NativeLog.i(TAG, "reflected renderer drive resumed: "
                + renderer.getClass().getName() + " -- its app GL thread ended"
                + " (or is running its uncaught-exception handler) without"
                + " surviving the EGL refusal (AOSP-derived fork), so"
                + " OS-main drives it" + (reflectedDeferredW > 0
                    ? " after replaying the deferred surface callbacks" : ""));
        }
        return false;
    }

    /** Once per view: a live successor of a dead fork class was seen and left
     *  OS-main driving (it has not reached EGL; it would take the renderer only
     *  by surviving the refusal). */
    private void macapkLogSuccessor(Thread t) {
        if ((glOwnerLogged & OWNER_LOG_SUCCESSOR) != 0) return;
        glOwnerLogged |= OWNER_LOG_SUCCESSOR;
        NativeLog.i(TAG, "reflected renderer drive kept: app GL thread "
            + t.getName() + " (" + t.getClass().getName() + ") on "
            + getClass().getName() + " is a new thread of a class that already"
            + " died here without surviving the EGL refusal (a re-attached"
            + " AOSP-derived fork); OS-main keeps driving "
            + reflectedRenderer.getClass().getName()
            + " unless that thread survives the refusal");
    }

    /** One line per view per decision state: a stuck boot must be one grep
     *  away, and this runs every frame, so it must never repeat. */
    private void macapkLogOwner(int bit, String threadName, String why) {
        if ((glOwnerLogged & bit) != 0) return;
        glOwnerLogged |= bit;
        NativeLog.i(TAG, "reflected renderer drive declined: "
            + reflectedRenderer.getClass().getName() + " owned by app GL thread "
            + threadName + " (" + why + ") on " + getClass().getName()
            + (bit == OWNER_LOG_ALIVE
                ? "; surface callbacks deferred until that thread ends"
                : "; never driven from OS-main again (its own thread calls it, as on Android)"));
    }

    /** The GL10 argument for a reflected renderer callback: the shared
     *  GL10 implementation when the method's first parameter accepts
     *  it (javax GL10/GL interfaces), null otherwise (engines whose
     *  callback signature uses some other type). */
    private static Object glArgFor(java.lang.reflect.Method m) {
        try {
            Class<?>[] p = m.getParameterTypes();
            Object gl = android.opengl.GLSurfaceView.macapkSharedGL10();
            if (p.length >= 1 && p[0].isInstance(gl)) return gl;
        } catch (Throwable ignore) {}
        return null;
    }

    /** The EGLConfig argument for a reflected onSurfaceCreated: the window
     *  surface's config (MacApkEGL10.macapkWindowSurfaceConfig, what AOSP's
     *  GLThread passes from its chooser) when the second parameter accepts it,
     *  null otherwise. */
    private static Object eglConfigArgFor(java.lang.reflect.Method m) {
        try {
            Class<?>[] p = m.getParameterTypes();
            Object c = javax.microedition.khronos.egl.MacApkEGL10.macapkWindowSurfaceConfig();
            if (p.length >= 2 && c != null && p[1].isInstance(c)) return c;
        } catch (Throwable ignore) {}
        return null;
    }

    /** Walk this SurfaceView's class hierarchy looking for a field
     *  whose value implements onDrawFrame(...). The first match wins;
     *  cocos2d-x stores its renderer in `mRenderer` on a parent class
     *  (FSGLSurfaceView), so we walk the superclass chain. */
    private void ensureRendererResolved() {
        if (reflectedRenderer != null) return;
        if (++reflectedLookupAttempts > MAX_RENDERER_LOOKUPS) return;
        Class<?> cls = getClass();
        while (cls != null && cls != SurfaceView.class && cls != View.class) {
            for (Field f : cls.getDeclaredFields()) {
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(this);
                } catch (Throwable ignore) { continue; }
                if (v == null) continue;
                Method onDraw = findMethod(v.getClass(), "onDrawFrame");
                if (onDraw == null) continue;
                reflectedRenderer       = v;
                reflectedOnDrawFrame    = onDraw;
                reflectedOnSurfaceCreated  = findMethod(v.getClass(), "onSurfaceCreated");
                reflectedOnSurfaceChanged  = findMethod(v.getClass(), "onSurfaceChanged");
                NativeLog.i(TAG,
                    "auto-discovered renderer " + v.getClass().getName()
                  + " on field " + cls.getSimpleName() + "." + f.getName());
                return;
            }
            cls = cls.getSuperclass();
        }
    }

    /** Find a method with the given name regardless of arg types.
     *  Falls back to the first declared one on the deepest match;
     *  Cocos2dxRenderer's onDrawFrame signature is
     *  (javax.microedition.khronos.opengles.GL10), but engines that
     *  subclass it could in theory shadow with different types --
     *  we don't try to disambiguate, just take the first. */
    private static Method findMethod(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    try { m.setAccessible(true); } catch (Throwable ignore) {}
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** Holder used so AndEngine et al. can register a surfaceCreated /
     *  surfaceChanged callback. We fire those synchronously from the
     *  Activity's setContentView path so the renderer thinks the
     *  surface was created and sized. */
    // ------------------------------------------------------------------
    // Video-sink registry: WHICH SurfaceView the decoded video belongs to,
    // and WHERE it is on screen right now.
    //
    // On real Android this association is structural: the Surface a
    // SurfaceHolder vends is a producer handle onto the SurfaceView's own
    // SurfaceControl, and the compositor scales every queued buffer to the view's
    // on-screen rect -- SurfaceView.onSetSurfacePositionAndScale
    // (aosp-mirror/master core/java/android/view/SurfaceView.java, fetched
    // 2026-08-17):
    //     transaction.setPosition(surface, positionLeft, positionTop);
    //     transaction.setMatrix(surface, postScaleX /*dsdx*/, 0f, 0f,
    //                           postScaleY /*dsdy*/);
    // with postScale = mScreenRect.width() / (float) mSurfaceWidth (resp.
    // height), and mScreenRect built in updateSurface from
    // getLocationInWindow(mLocation) + getWidth()/getHeight(). The default
    // codec scaling mode composes with that: MediaCodec.
    // VIDEO_SCALING_MODE_SCALE_TO_FIT = 1, "The content is scaled to the
    // surface dimensions", and setVideoScalingMode's javadoc: "The default is
    // 'scale to fit'" (aosp-mirror/master media/java/android/media/
    // MediaCodec.java:5052-5092, fetched 2026-08-17). Net: decoded frame
    // stretches to the surface, surface stretches to the view rect.
    //
    // Mac.apk's equivalent: MediaCodec.configure/setOutputSurface register
    // the Surface's owner SurfaceView here; cocoa_bridge.mm's video layer
    // (CocoaPresentVideoFrame + the per-frame drawInMTKView refresh) PULLS
    // macapkActiveVideoRectPacked() and sets the CAMetalLayer's frame to the
    // rect, so the presenter's stretch-to-layer blit reproduces the composed
    // AOSP scaling exactly. PULLED, not pushed, for the same reason
    // StatusBarDriver.macapkContentInsetsPacked is: the pull happens on the
    // Cocoa main thread, which is also the thread ViewCanvasDriver lays the
    // tree out on, so the rect can never be stale by more than a frame and
    // there is no cross-thread invalidation protocol to get wrong.
    //
    // A WeakReference so a dismissed player screen's tree can be collected;
    // the codec outliving the view then reads "hidden" (0-sentinel cases
    // below), which is also what a released SurfaceControl shows on a device.
    private static volatile java.lang.ref.WeakReference<SurfaceView>
            sVideoSinkView;
    /** The Surface whose registration produced sVideoSinkView, so a codec
     *  released AFTER another codec re-registered does not clear the newer
     *  sink (release() passes its own surface here). */
    private static volatile android.view.Surface sVideoSinkSurface;

    /** MediaCodec.configure/setOutputSurface hand every non-null output
     *  Surface here. A SurfaceView-owned Surface becomes the active sink; any
     *  other Surface (SurfaceTexture-backed -- the TextureView path handles
     *  those -- or app-constructed) clears it, so there is exactly one notion
     *  of "current sink" and it always reflects the codec's LATEST binding. */
    public static void macapkRegisterVideoSink(android.view.Surface s) {
        SurfaceView owner = (s != null) ? s.macapkGetOwnerSurfaceView() : null;
        if (owner != null) {
            sVideoSinkView = new java.lang.ref.WeakReference<>(owner);
            sVideoSinkSurface = s;
            NativeLog.i(TAG, "video sink registered: " + owner
                + " bounds=" + owner.getWidth() + "x" + owner.getHeight());
            // One-shot structural dump: the sink's SIBLINGS decide what
            // composites over the video (media3's shutter is a sibling View
            // painted opaque black until onRenderedFirstFrame hides it), so
            // when a player box reads black on screen this line separates
            // "our plane is mispositioned" from "the app is still showing its
            // shutter" without another instrumented build.
            try {
                ViewParent p = owner.getParent();
                if (p instanceof ViewGroup) {
                    ViewGroup pg = (ViewGroup) p;
                    StringBuilder sb = new StringBuilder("video sink siblings in ")
                        .append(pg.getClass().getSimpleName()).append(':');
                    for (int i = 0; i < pg.getChildCount(); i++) {
                        View c = pg.getChildAt(i);
                        sb.append(" [").append(i).append(']')
                          .append(c.getClass().getSimpleName())
                          .append(" vis=").append(c.getVisibility())
                          .append(' ').append(c.getWidth()).append('x')
                          .append(c.getHeight())
                          .append(c == owner ? "<==sink" : "");
                    }
                    NativeLog.i(TAG, sb.toString());
                }
            } catch (Throwable ignored) {}
            // Re-record the interop draw. A Compose-hosted view's content is
            // RECORDED into a RenderNode/GraphicsLayer and REPLAYED until the
            // view invalidates; the recording that exists right now was made
            // BEFORE this registration, so draw() -- and therefore the hole
            // punch -- never re-executes without this. Measured 2026-08-17:
            // no "video sink punch" line ever fired on PlayTube until the
            // invalidate was added. postInvalidate: registration runs on the
            // app's playback/codec thread, never the UI thread.
            try { owner.postInvalidate(); } catch (Throwable ignored) {}
        } else {
            sVideoSinkView = null;
            sVideoSinkSurface = null;
            if (s != null) {
                // Diagnostic breadcrumb: names WHICH kind of non-view Surface
                // the codec was bound to (SurfaceTexture-backed goes through
                // the TextureView latch; a bare Surface keeps the legacy
                // whole-window plane).
                NativeLog.i(TAG, "video sink: non-SurfaceView Surface ("
                    + (s.macapkGetSurfaceTexture() != null
                        ? "SurfaceTexture-backed" : "bare")
                    + ") -- whole-window video plane");
            }
        }
    }
    /** Codec teardown: clear only if OUR surface is still the registered one. */
    public static void macapkUnregisterVideoSink(android.view.Surface s) {
        if (s != null && s == sVideoSinkSurface) {
            SurfaceView old = sVideoSinkView != null ? sVideoSinkView.get() : null;
            sVideoSinkView = null;
            sVideoSinkSurface = null;
            // Same re-record reasoning as registration: the punch must stop
            // replaying once the sink is gone.
            if (old != null) {
                try { old.postInvalidate(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Win.apk (windows/host): a decoded video picture for the holder Surface
     *  {@code s} -- or, with null, for the registered video sink -- shown
     *  through the owning view's own per-surface layer at its rect, below
     *  the window, which then punches its hole (the lockCanvas route of
     *  macapkPresentThroughSurfaceLayer; the Mac shows these through its video
     *  plane instead). False when no SurfaceView owns the Surface or the
     *  runtime has no per-surface layer: the caller shows it another way. */
    public static boolean macapkPresentVideoFrame(android.view.Surface s, int[] argb, int w, int h) {
        SurfaceView v = s != null ? s.macapkGetOwnerSurfaceView() : null;
        if (v == null && s == null) {
            java.lang.ref.WeakReference<SurfaceView> ref = sVideoSinkView;
            v = ref != null ? ref.get() : null;
        }
        return v != null && v.macapkPresentThroughSurfaceLayer(argb, w, h);
    }

    /** The active video sink's on-screen rect in SURFACE pixels, packed
     *  (x << 48) | (y << 32) | (w << 16) | h with each field a SIGNED 16-bit
     *  value (x/y go negative when the view is scrolled partly off-screen).
     *
     *  Sentinels: 0 = no SurfaceView sink registered (caller keeps its legacy
     *  fullscreen behaviour -- a bare-Surface producer has no view geometry);
     *  -1 = a sink IS registered but currently has no visible rect (detached,
     *  not shown, or zero-sized) -- the video plane must HIDE, exactly as a
     *  device stops showing a SurfaceControl whose view is gone.
     *
     *  COORDINATE SPACE: the full display surface -- the same space
     *  ViewCanvasDriver's canvas bitmap lives in. The driver draws the root
     *  translated down by StatusBarDriver.heightPx (canvas.translate(0,
     *  statusBarH); rootView.draw(canvas)), so a view's window coords map to
     *  surface coords by adding that same per-window inset. Using the SAME
     *  accessor the driver uses keeps the two from drifting (the
     *  measure-and-layout-must-share-one-height lesson of v1.0.865). */
    /** Last pull STATE (0=none, 1=hidden, 2=visible), logged on transition so
     *  a black player box is attributable from the log alone. */
    private static int sSinkPullState = -1;
    private static void macapkNoteSinkState(int st, String detail) {
        if (st != sSinkPullState) {
            sSinkPullState = st;
            NativeLog.i(TAG, "video sink state -> "
                + (st == 0 ? "NONE" : st == 1 ? "HIDDEN" : "VISIBLE")
                + (detail != null ? (" (" + detail + ")") : ""));
        }
    }

    /** DIAGNOSTIC (gated OFF by default via MACAPK_VIDEO_SINK_TRACE=1):
     *  periodic structural dump of the video sink's PLAYER SUBTREE -- every
     *  ancestor/sibling/descendant's visibility, alpha, bounds and (for
     *  TextViews) live text.
     *
     *  It exists to separate two explanations of a black player box that are
     *  INDISTINGUISHABLE FROM PIXELS, and that the one-shot dump at
     *  registration structurally cannot tell apart:
     *    (a) the app's shutter overlay is still VISIBLE -- the first-frame
     *        notification never reached the player UI, so the app is
     *        deliberately covering its own video, exactly as a device would;
     *    (b) the shutter is INVISIBLE and the clock text has advanced in the
     *        TREE, yet the window still shows the old pixels -- a repaint /
     *        re-record failure on OUR side, not an app-state failure.
     *  Registration runs at codec configure, strictly BEFORE any frame is
     *  rendered, so the flag it reads is always the pre-first-frame one; only
     *  a dump taken DURING steady playback can answer the question.
     *
     *  Runs on whichever thread pulls the rect (the Cocoa main thread), which
     *  is the same thread ViewCanvasDriver lays the tree out on, so it observes
     *  exactly the state the compositor is about to draw. */
    private static final boolean VIDEO_SINK_TRACE =
            "1".equals(System.getenv("MACAPK_VIDEO_SINK_TRACE"));
    private static final long VIDEO_SINK_TRACE_MS = 3000L;
    private static long sLastSinkTrace;
    /** draw() punch count, so the dump reports whether the interop subtree is
     *  re-drawn every frame or frozen on a stale recording. */
    private static long sPunchDraws;

    private static void macapkTraceSinkTree(SurfaceView sink) {
        if (!VIDEO_SINK_TRACE || sink == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastSinkTrace < VIDEO_SINK_TRACE_MS) return;
        sLastSinkTrace = now;
        try {
            // Climb three levels: that spans media3's
            // SurfaceView -> AspectRatioFrameLayout -> PlayerView, where the
            // shutter, the artwork overlays AND the transport controls (with
            // the position/duration TextViews) all live. Generic climb, not a
            // class-name match -- any player built the same way dumps the same.
            View root = sink;
            for (int i = 0; i < 3; i++) {
                ViewParent p = root.getParent();
                if (!(p instanceof View)) break;
                root = (View) p;
            }
            StringBuilder sb = new StringBuilder("video sink tree (punchDraws=")
                .append(sPunchDraws).append("):\n");
            macapkDumpSinkSubtree(root, sink, 0, sb);
            NativeLog.i(TAG, sb.toString());
        } catch (Throwable ignored) {}
    }

    private static void macapkDumpSinkSubtree(View v, View sink, int depth,
            StringBuilder sb) {
        if (v == null || depth > 6) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName())
          .append(" vis=").append(v.getVisibility())
          .append(" a=").append(v.getAlpha())
          .append(' ').append(v.getLeft()).append(',').append(v.getTop())
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        if (v == sink) sb.append(" <==SINK");
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(" text='").append(t).append('\'');
        }
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                macapkDumpSinkSubtree(g.getChildAt(i), sink, depth + 1, sb);
        }
    }

    public static long macapkActiveVideoRectPacked() {
        java.lang.ref.WeakReference<SurfaceView> ref = sVideoSinkView;
        if (ref == null) { macapkNoteSinkState(0, null); return 0L; }
        SurfaceView v = ref.get();
        if (v == null) { macapkNoteSinkState(1, "collected"); return -1L; }
        int w, h;
        try {
            if (!v.macapkAttached || !v.isShown()) {
                macapkNoteSinkState(1, "attached=" + v.macapkAttached
                    + " shown=" + v.isShown());
                return -1L;
            }
            w = v.getWidth();
            h = v.getHeight();
            if (w <= 0 || h <= 0) { macapkNoteSinkState(1, "zero-size"); return -1L; }
            // v1.0.997 CORRECTION -- this used to be
            //     y = loc[1] + StatusBarDriver.heightPx(root)
            // which DOUBLE-COUNTS the status bar. getLocationInWindow sums
            // getLeft()/getTop() up the parent chain INCLUDING the root view's
            // own top, and the root view's top already IS the status-bar height;
            // ViewCanvasDriver then applies that same inset a second time as a
            // canvas translate (canvas.translate(0, statusBarH); rootView.draw).
            // So subtract the root's own offset before adding the driver's, which
            // is correct whether or not the two happen to be equal.
            //
            // MEASURED 2026-08-18 on PlayTube: the pull answered y=96 and y=121
            // while the hole SurfaceView.draw actually punched -- read off the
            // frame canvas's own CTM -- landed at y=48 and y=73. Exactly 48px
            // apart, every time: the video plane was presenting a status bar's
            // height BELOW the hole cut for it, so the top of every frame was
            // hidden under the canvas and the bottom of the hole showed the
            // content behind it. The hole and the plane must come from ONE
            // number (the measure-and-layout-must-share-one-height lesson of
            // v1.0.865); this is that number. (2026-09-26: it lives in
            // macapkWindowOrigin now, the ONE formula shared with the
            // placement of a lockCanvas surface; unchanged.)
            int[] origin = new int[2];
            v.macapkWindowOrigin(origin);
            int x = origin[0];
            int y = origin[1];
            macapkNoteSinkState(2, x + "," + y + " " + w + "x" + h);
            macapkTraceSinkTree(v);
            return ((long) (x & 0xffffL) << 48)
                 | ((long) (y & 0xffffL) << 32)
                 | ((long) (w & 0xffffL) << 16)
                 |  (long) (h & 0xffffL);
        } catch (Throwable t) {
            // A half-torn-down tree mid-pull must degrade to "hidden", never
            // throw across JNI into the compositor.
            macapkNoteSinkState(1, "threw " + t.getClass().getSimpleName());
            return -1L;
        }
    }

    /** Is THIS view the registered video sink right now? (State-gated, never
     *  identity-gated: any app whose codec output is bound to a SurfaceView
     *  qualifies.) */
    private boolean macapkIsActiveVideoSink() {
        java.lang.ref.WeakReference<SurfaceView> ref = sVideoSinkView;
        return ref != null && ref.get() == this;
    }

    /** AOSP SurfaceView.draw punches a transparent hole in the window at the
     *  view's bounds so the surface BEHIND the window shows through --
     *  aosp-mirror/master core/java/android/view/SurfaceView.java (fetched
     *  2026-08-17): draw()/dispatchDraw() call clearSurfaceViewPort(canvas),
     *  which is `canvas.punchHole(0f, 0f, getWidth(), getHeight(), ...)`.
     *  Siblings drawn after this view (ExoPlayer's transport controls) then
     *  composite on top of the hole, which is the whole inline-player model.
     *
     *  Gated to the ACTIVE VIDEO SINK deliberately: for a full-window GL
     *  SurfaceView the ViewCanvasDriver already clears the whole canvas
     *  transparent (its CASE A), so a punch is a visual no-op there, and
     *  punching unconditionally would change every app hosting an idle
     *  SurfaceView (camera placeholders and the like) in one step -- the
     *  fully-AOSP unconditional punch is noted as an open question in
     *  docs/video-player-geometry_2026-08-17.md pending a fleet A/B. */
    /* The CLEAR-xfermode Paint that used to live here is gone: v1.0.997 calls
     * Canvas.punchHole, which is the op AOSP actually uses and which takes the
     * same explicit (0, 0, getWidth(), getHeight()) bounds the old comment
     * argued for (drawColor would have covered the whole CLIP instead). */

    /** First-fire flag for the punch log line below (diagnosis: "was
     *  SurfaceView.draw reached through the app's draw path at all?"). */
    private static boolean sPunchLogged;

    @Override
    public void draw(android.graphics.Canvas canvas) {
        if (macapkIsActiveVideoSink()) {
            // v1.0.997: AOSP's own op, not a hand-rolled CLEAR drawRect.
            // SurfaceView.clearSurfaceViewPort is
            //   canvas.punchHole(0f, 0f, getWidth(), getHeight(), 0f, 0f, alpha)
            // (aosp-mirror/master core/java/android/view/SurfaceView.java:727-745,
            // fetched 2026-08-18), and the alpha argument is load-bearing: HWUI
            // punches with DST_OUT modulated by the View's alpha, so a fading
            // SurfaceView fades its hole rather than switching it off. The old
            // CLEAR drawRect dropped that, and -- more importantly -- could not
            // escape an interposed RenderNode offscreen; see Canvas.punchHole.
            canvas.punchHole(0f, 0f, getWidth(), getHeight(), 0f, 0f, getAlpha());
            sPunchDraws++;
            if (!sPunchLogged) {
                sPunchLogged = true;
                // Is this the ROOT frame canvas, or an interposed offscreen? A
                // CLEAR/DST_OUT recorded into an offscreen that is later
                // composited SrcOver leaves the frame untouched, so the hole
                // never appears no matter how correct the punch is.
                android.graphics.Canvas root =
                        com.macapk.loader.ViewCanvasDriver.macapkRootCanvas;
                NativeLog.i(TAG, "video sink punch: first draw() reached, "
                    + getWidth() + "x" + getHeight()
                    + " canvas=@" + Integer.toHexString(System.identityHashCode(canvas))
                    + " rootCanvas=@" + (root == null ? "none"
                        : Integer.toHexString(System.identityHashCode(root)))
                    + " isRoot=" + (root != null && root == canvas));
            }
        } else if (sSurfaceLayerGate && macapkSurfaceLayerDrawn && !mZOrderOnTop) {
            // A lockCanvas surface shown through its own layer BELOW the view
            // tree: the same hole, on AOSP's own condition (android14 draw()
            // :512-521, `if (mDrawFinished && !isAboveParent())` ->
            // clearSurfaceViewPort). Without it any opaque background a parent
            // paints under this view would hide the surface. Only reachable once
            // the runtime has a per-surface layer (macapkPresentThroughSurfaceLayer).
            canvas.punchHole(0f, 0f, getWidth(), getHeight(), 0f, 0f, getAlpha());
        } else if (macapkNativeWindowDrawFinished && !mZOrderOnTop && macapkIsNativeWindowOwner()) {
            // The native-EGL window layer sits BELOW the window for this view
            // (macapkNativeWindowLayerZ == 1): the same AOSP hole, on the same
            // condition (draw() :512-521). Without it the window's own opaque
            // background -- drawn before this view -- covers the producer's
            // frames (the Fruit Ninja black-out of the first z-below attempt).
            canvas.punchHole(0f, 0f, getWidth(), getHeight(), 0f, 0f, getAlpha());
            if (!macapkNativeHoleLogged) {
                macapkNativeHoleLogged = true;
                NativeLog.i(TAG, "native window hole: first punch " + getWidth() + "x"
                    + getHeight() + " by " + getClass().getName() + " (MACAPK_SV_GUEST_BELOW)");
            }
        }
        super.draw(canvas);
    }

    private final SurfaceHolderImpl holder = new SurfaceHolderImpl(this);

    /** Legacy entry point: was originally meant to be called by
     *  Activity.setContentView. In v1.0.69 the same dispatch now lives
     *  in macapkSurfaceChanged (which MTKView already calls), so this
     *  is currently unused. Kept around in case a future caller needs
     *  to force the dispatch out-of-band. Uses the same single-shot
     *  latch as macapkSurfaceChanged so doubled invocations won't
     *  produce two surfaceCreated callbacks. */
    public final void macapkNotifySurfaceReady(int widthPx, int heightPx) {
        if (!holderSurfaceCreatedFired) {
            holder.fireCreated();
            holderSurfaceCreatedFired = true;
        }
        holder.fireChanged(0, widthPx, heightPx);
    }

    private static final class SurfaceHolderImpl implements SurfaceHolder {
        /** Owning SurfaceView, for the retroactive-delivery check below. */
        private final SurfaceView owner;
        SurfaceHolderImpl(SurfaceView owner) {
            this.owner = owner;
            // Structural on real Android (the Surface IS a handle onto this
            // view's buffer queue); here the link is what lets MediaCodec
            // recognise a SurfaceView-bound output -- see the video-sink
            // registry block above.
            surface.macapkSetOwnerSurfaceView(owner);
        }
        private final java.util.ArrayList<Callback> callbacks = new java.util.ArrayList<>();
        private final android.view.Surface surface = new android.view.Surface();
        private final android.graphics.Rect frame = new android.graphics.Rect();
        // Persistent backing bitmap. lockCanvas() returns a Canvas
        // wrapping this; unlockCanvasAndPost() is a no-op for now (no
        // Metal upload path yet) but the bitmap stays around so apps
        // can detect "next frame == still painting" via getPixel()-
        // style probes. Lazily allocated at first lockCanvas() call so
        // we can use the actual surface dimensions.
        private android.graphics.Bitmap drawBitmap;
        private android.graphics.Canvas drawCanvas;

        @Override public void addCallback(Callback cb) {
            if (cb == null || callbacks.contains(cb)) return;  // AOSP dedupes
            callbacks.add(cb);
            // v1.0.572: retroactive delivery. Our surface is live from the
            // first drawInMTKView, so a callback registered AFTER the
            // (single-shot) surfaceCreated dispatch would otherwise never
            // hear it. On real Android this ordering can't happen at boot
            // (surfaceCreated is a post-onResume main-looper message), so
            // retro-firing here reconstructs the ordering guarantee the app
            // was written against rather than inventing new behavior.
            if (owner != null && owner.holderSurfaceCreatedFired) {
                NativeLog.i(TAG, "addCallback after surfaceCreated: retro-"
                    + "delivering to " + cb.getClass().getName());
                try {
                    cb.surfaceCreated(this);
                    if (frame.width() > 0 && frame.height() > 0) {
                        cb.surfaceChanged(this, 0, frame.width(), frame.height());
                    }
                } catch (Throwable t) {
                    NativeLog.e(TAG, "retroactive surfaceCreated threw: " + t);
                }
            }
        }
        @Override public void removeCallback(Callback cb) { callbacks.remove(cb); }
        @Override public boolean isCreating()            { return false; }
        @Override public android.view.Surface getSurface() { return surface; }
        @Override public android.graphics.Rect getSurfaceFrame() { return frame; }
        @Override public void setFormat(int f)            {}
        @Override public void setKeepScreenOn(boolean s)  {}
        @Override public void setFixedSize(int w, int h)  { frame.set(0, 0, w, h); macapkFixedSize = true; }
        @Override public void setSizeFromLayout()         { macapkFixedSize = false; }
        /** 2026-09-26: this holder has served a lockCanvas, so its consumer
         *  draws with a Canvas and its frames are placed at the view's rect;
         *  until then it may be a GL/EGL consumer (macapkHolderSurfaceSize).
         *  Sticky: a surface does not change consumers. Written by the
         *  drawing thread, read by the render thread. */
        volatile boolean macapkLockedCanvas;
        /** v1.0.1637: the app pinned the buffer size (setFixedSize). AOSP's
         *  surface then keeps that size across a window resize, so the resize
         *  re-fire in macapkConvergeSurfaceLifecycle skips it. */
        boolean macapkFixedSize;
        @Override public void setType(int type)           {}
        @Override public android.graphics.Canvas lockCanvas() {
            macapkLockedCanvas = true;
            int w = frame.width(), h = frame.height();
            if (w <= 0 || h <= 0) { w = 800; h = 600; }   // sensible default
            if (drawBitmap == null || drawBitmap.getWidth() != w
                                       || drawBitmap.getHeight() != h) {
                drawBitmap = android.graphics.Bitmap.createBitmapWithCanvasBacking(w, h);
                drawCanvas = new android.graphics.Canvas(drawBitmap);
            }
            return drawCanvas;
        }
        @Override public android.graphics.Canvas lockCanvas(android.graphics.Rect dirty) {
            return lockCanvas();
        }
        @Override public android.graphics.Canvas lockHardwareCanvas() { return lockCanvas(); }
        @Override public void unlockCanvasAndPost(android.graphics.Canvas canvas) {
            // Push the painted bitmap to the screen via the
            // Cocoa-side CALayer overlay (sibling of the ANGLE Metal
            // layer). Read ARGB pixels via macapkArgbBuffer() which
            // extracts from the AWT BufferedImage that Canvas painted
            // into. v1.0.51 first wiring of the Canvas-to-Metal blit.
            if (drawBitmap == null) return;
            // v1.0.1648: the canvas's own live pixels when available (no 24 MB copy
            // at a full-screen window); presentBitmap copies synchronously on this,
            // the drawing thread, which is the contract macapkLiveArgbOrNull states.
            int[] pixels = drawBitmap.macapkLiveArgbOrNull();
            if (pixels == null) pixels = drawBitmap.macapkArgbBuffer();
            if (pixels == null) return;
            int pw = drawBitmap.getWidth(), ph = drawBitmap.getHeight();
            if (owner != null) {
                // 2026-09-26: show the frame WHERE its view is (see "where a
                // lockCanvas frame is shown" in SurfaceView): its own layer when
                // the runtime has one, else composited at the view's rect when
                // it is not display-sized; otherwise unchanged below.
                if (owner.macapkPresentThroughSurfaceLayer(pixels, pw, ph)) return;
                int[] placed = owner.macapkPlaceInDisplay(pixels, pw, ph);
                if (placed != null) {
                    pixels = placed;
                    pw = owner.macapkCompositeW;
                    ph = owner.macapkCompositeH;
                }
            }
            try {
                com.macapk.loader.NativeCocoa.presentBitmap(pixels, pw, ph);
            } catch (UnsatisfiedLinkError e) {
                // Native method not registered (headless build / older
                // runtime). Painting still happens to memory; just no
                // visible output. Logged once.
                if (!com.macapk.loader.SurfaceCanvasState.warnedMissingNative) {
                    com.macapk.loader.SurfaceCanvasState.warnedMissingNative = true;
                    System.err.println(
                        "[surfaceview] presentBitmap not registered; "
                        + "Canvas paints invisibly: " + e.getMessage());
                }
            }
        }

        /** Internal: SurfaceView's reflection renderer fallback can
         *  read the latest drawn bitmap from here so an eventual blit
         *  path can pick it up. */
        android.graphics.Bitmap macapkDrawBitmap() { return drawBitmap; }

        boolean hasCallbacks() { return !callbacks.isEmpty(); }

        void fireCreated() { fireCreatedTolerant(); }

        /** v1.0.572: per-callback tolerant surfaceCreated dispatch. Returns
         *  false when any callback threw -- the signal that the app is not
         *  ready to accept the surface yet (AGDK GameActivity's
         *  surfaceCreated calls a native method that only exists after
         *  initializeNativeCode ran; before that the call raises
         *  UnsatisfiedLinkError). The caller retries next frame WITHOUT
         *  latching, so the delivery converges instead of being consumed
         *  by a half-initialized listener. */
        boolean fireCreatedTolerant() {
            // v1.0.158 DIAGNOSTIC: log which callbacks receive
            // surfaceCreated. For Unity this is where com.unity3d.player.S
            // (the engine's SurfaceHolder.Callback) should fire and then
            // call nativeRecreateGfxState. If this logs 0 callbacks, Unity
            // never registered on the SurfaceView our framework is driving.
            java.util.ArrayList<Callback> snap = new java.util.ArrayList<>(callbacks);
            StringBuilder sb = new StringBuilder();
            for (Callback c : snap) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(c.getClass().getName());
            }
            NativeLog.i(TAG, "holder.fireCreated: " + snap.size()
                + " callback(s): [" + sb + "]");
            boolean ok = true;
            for (Callback c : snap) {
                try {
                    c.surfaceCreated(this);
                } catch (Throwable t) {
                    ok = false;
                    NativeLog.e(TAG, "surfaceCreated rejected by "
                        + c.getClass().getName() + ": " + t
                        + " (will retry next frame)");
                }
            }
            return ok;
        }
        void fireChanged(int format, int w, int h) {
            // The frame is the holder's STATE and is written where the change is
            // decided (getSurfaceFrame() answers at once, and the per-frame size
            // comparisons do not re-fire); the CALLBACKS go to the UI thread when
            // this runs off it (SV_UI_THREAD_CALLBACKS, above), behind any posted
            // surfaceCreated, so the order stays created -> changed.
            frame.set(0, 0, w, h);
            if (macapkCallbackOffUiThread()) {
                macapkPostToUiThread(() -> {
                    try {
                        for (Callback c : new java.util.ArrayList<>(callbacks)) c.surfaceChanged(this, format, w, h);
                    } catch (Throwable t) {
                        NativeLog.e(TAG, "UI-thread surfaceChanged threw: " + t);
                    }
                });
                return;
            }
            for (Callback c : new java.util.ArrayList<>(callbacks)) c.surfaceChanged(this, format, w, h);
        }
        void fireDestroyed() {
            if (macapkCallbackOffUiThread()) {
                macapkPostToUiThread(() -> {
                    try {
                        for (Callback c : new java.util.ArrayList<>(callbacks)) c.surfaceDestroyed(this);
                    } catch (Throwable t) {
                        NativeLog.e(TAG, "UI-thread surfaceDestroyed threw: " + t);
                    }
                });
                return;
            }
            for (Callback c : new java.util.ArrayList<>(callbacks)) c.surfaceDestroyed(this);
        }
    }

    // The AttachedSurfaceControl of the window this SurfaceView roots into.
    // Mac.apk has one composited window and no SurfaceControl transaction
    // pipeline, so this is a stable no-op host (applyTransactionOnDraw defaults
    // to "not deferred"). API 30.
    private final AttachedSurfaceControl mRootSurfaceControl = new AttachedSurfaceControl() { };

    public AttachedSurfaceControl getRootSurfaceControl() { return mRootSurfaceControl; }

    public boolean onTrackballEvent(android.view.MotionEvent event) { return false; }

    /** AOSP View.onPointerCaptureChange(boolean): "Called when the window
     *  has just acquired or lost pointer capture." Empty in AOSP itself --
     *  it exists purely as an overridable hook that
     *  View.dispatchPointerCaptureChanged() forwards to. Declared directly
     *  on SurfaceView, not the android.view.View base it extends, because
     *  that base is out of this change's scope; a SurfaceView subclass
     *  (openttd's engine wrapper) that overrides this and calls
     *  super.onPointerCaptureChange(hasCapture) -- the normal AOSP-idiom
     *  override -- needs some class in its supertype chain to resolve the
     *  super call against. */
    public void onPointerCaptureChange(boolean hasCapture) {}

    // ======================================================================
    //  AOSP API-36 surface completion (Mac.apk API-gap lane, android.view).
    // ======================================================================

    /** AOSP SurfaceView.getSurfaceControl(): the SurfaceControl backing this
     *  view's dedicated compositor layer. Mac.apk draws SurfaceView content into
     *  the SAME ANGLE/Metal layer as the rest of the window rather than a
     *  separate SurfaceFlinger layer, so there is no layer of its own -- but the
     *  handle must be non-null and STABLE, because the standard idiom is
     *  `new SurfaceControl.Transaction().setPosition(sv.getSurfaceControl(), ...)`
     *  and a null there is an immediate NPE inside the app. The transaction is a
     *  recording no-op (see SurfaceControl.Transaction), so a caller gets the
     *  same "queued and applied" answer it would on a device; what it does not
     *  get is a visible effect, which is already true of every other
     *  SurfaceControl operation in this tree. Referenced by YouTube. */
    private SurfaceControl mSurfaceControl;
    public SurfaceControl getSurfaceControl() {
        if (mSurfaceControl == null) {
            mSurfaceControl = new SurfaceControl.Builder()
                    .setName("SurfaceView[" + getClass().getSimpleName() + "]")
                    .build();
        }
        return mSurfaceControl;
    }

    /** AOSP SurfaceView.applyTransactionToFrame(SurfaceControl.Transaction):
     *  apply the transaction atomically WITH the next buffer this view posts.
     *  With no compositor to synchronise against, applying it now is the same
     *  observable outcome -- and dropping it would strand any committed
     *  listener the caller attached. */
    public void applyTransactionToFrame(SurfaceControl.Transaction t) {
        if (t != null) t.apply();
    }

    /** AOSP SurfaceView.setChildSurfacePackage(SurfacePackage) /
     *  getChildSurfacePackage() / clearChildSurfacePackage(): embed another
     *  process's SurfaceControlViewHost hierarchy inside this view. Mac.apk is
     *  a single process with nothing to embed (see SurfaceControlViewHost's
     *  header); the package round-trips so a caller reads back what it set, and
     *  nothing is composited -- the same as an embedded package whose remote
     *  end never produced a frame. */
    private SurfaceControlViewHost.SurfacePackage mChildSurfacePackage;
    public void setChildSurfacePackage(SurfaceControlViewHost.SurfacePackage p) {
        mChildSurfacePackage = p;
    }
    public SurfaceControlViewHost.SurfacePackage getChildSurfacePackage() {
        return mChildSurfacePackage;
    }
    public void clearChildSurfacePackage() { mChildSurfacePackage = null; }

    /** AOSP SurfaceView.setCompositionOrder(int) / getCompositionOrder()
     *  (API 36): the z-order of this view's layer relative to the window,
     *  superseding setZOrderOnTop / setZOrderMediaOverlay. Negative is behind
     *  the window, positive in front; AOSP's default is 0. */
    private int mCompositionOrder;
    public void setCompositionOrder(int order) {
        mCompositionOrder = order;
        // Keep the pre-existing z-order flags this class already honours in
        // agreement with the new API, rather than leaving two disagreeing
        // sources: AOSP documents setCompositionOrder as the replacement for
        // exactly these two calls.
        setZOrderOnTop(order > 0);
    }
    public int getCompositionOrder() { return mCompositionOrder; }

    /** AOSP SurfaceView.getHostToken(), deprecated at API 35: the IBinder a
     *  remote SurfaceControlViewHost would be created against. Nothing embeds
     *  into this view (see setChildSurfacePackage), so AOSP's own "not attached"
     *  answer -- null -- is the honest one. */
    @Deprecated
    public android.os.IBinder getHostToken() { return null; }

    /** AOSP SurfaceView.setDesiredHdrHeadroom(float) (API 35): validation is
     *  AOSP's own; the value is stored and, as on an SDR display, has no effect. */
    private float mDesiredHdrHeadroom;
    public void setDesiredHdrHeadroom(float desiredHeadroom) {
        if (!Float.isFinite(desiredHeadroom)) {
            throw new IllegalArgumentException("desiredHeadroom must be finite: "
                    + desiredHeadroom);
        }
        if (desiredHeadroom < 0 || desiredHeadroom > 10000.0f) {
            throw new IllegalArgumentException("desiredHeadroom must be in the range "
                    + "[0, 10000.0f], received: " + desiredHeadroom);
        }
        mDesiredHdrHeadroom = desiredHeadroom;
    }
}
