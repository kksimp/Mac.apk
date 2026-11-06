// ViewCanvasDriver -- per-frame onDraw pump for plain View / ViewGroup
// content (not SurfaceView). Sgt Puzzles, Vector Pinball, and many
// other Android apps subclass View and override onDraw(Canvas); these
// apps don't reach our SurfaceView reflection-renderer fallback
// because their root content view has no native MTKView attached.
//
// Activity.setContentView detects "no native handle in the view tree"
// and creates a Cocoa MetalSurfaceView solely to drive frame ticks.
// The MetalSurfaceView's renderer is a ViewCanvasDriver instance
// pointing at the original content view; each tick:
//   1. lazily allocate / resize a Bitmap to surface dimensions
//   2. create a Canvas wrapping the Bitmap
//   3. clear with the View's background color
//   4. call rootView.draw(canvas) -- which recurses through
//      ViewGroup.dispatchDraw into each subview's onDraw
//   5. blit the Bitmap to the screen via NativeCocoa.presentBitmap
//
// Generic: no per-APK code. Works for any subclass of View that
// overrides onDraw (the standard Android pattern).
package com.macapk.loader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

public final class ViewCanvasDriver implements MetalSurfaceCallbacks {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_AGDK_TRACE = System.getenv("MACAPK_AGDK_TRACE");
    private static final String ENV_MACAPK_CARD_GAP_PX = System.getenv("MACAPK_CARD_GAP_PX");
    private static final String ENV_MACAPK_DISPATCH_TOP_INSET = System.getenv("MACAPK_DISPATCH_TOP_INSET");
    private static final String ENV_MACAPK_DUMP_BITMAP_AT = System.getenv("MACAPK_DUMP_BITMAP_AT");
    private static final String ENV_MACAPK_DUMP_CL_WIDGETS = System.getenv("MACAPK_DUMP_CL_WIDGETS");
    private static final String ENV_MACAPK_DUMP_MOTION = System.getenv("MACAPK_DUMP_MOTION");
    private static final String ENV_MACAPK_DUMP_TREE_EVERY = System.getenv("MACAPK_DUMP_TREE_EVERY");
    private static final String ENV_MACAPK_GROW_FITS_APPBAR = System.getenv("MACAPK_GROW_FITS_APPBAR");
    private static final String ENV_MACAPK_KEEP_NAV_CENTER = System.getenv("MACAPK_KEEP_NAV_CENTER");
    private static final String ENV_MACAPK_M6_FORCE_DRAW = System.getenv("MACAPK_M6_FORCE_DRAW");
    private static final String ENV_MACAPK_MENU_TRACE = System.getenv("MACAPK_MENU_TRACE");
    private static final String ENV_MACAPK_NAV_MEASURE_CLAMP = System.getenv("MACAPK_NAV_MEASURE_CLAMP");
    private static final String ENV_MACAPK_NO_HEARTBEAT_LAYOUT = System.getenv("MACAPK_NO_HEARTBEAT_LAYOUT");
    private static final String ENV_MACAPK_PUMP_AFTER_DRIVE = System.getenv("MACAPK_PUMP_AFTER_DRIVE");
    private static final String ENV_MACAPK_RTL_RESOLVE = System.getenv("MACAPK_RTL_RESOLVE");
    private static final String ENV_MACAPK_RV_NATURAL = System.getenv("MACAPK_RV_NATURAL");
    private static final String ENV_MACAPK_RV_VISIBLE_INDEX = System.getenv("MACAPK_RV_VISIBLE_INDEX");
    private static final String ENV_MACAPK_SCROLL_DRAW_ONLY = System.getenv("MACAPK_SCROLL_DRAW_ONLY");
    private static final String ENV_MACAPK_TRACE_BARRIER = System.getenv("MACAPK_TRACE_BARRIER");
    private static final String ENV_MACAPK_TRACE_COMPOSE_FALLBACK = System.getenv("MACAPK_TRACE_COMPOSE_FALLBACK");
    private static final String ENV_MACAPK_TRACE_FULLFILL = System.getenv("MACAPK_TRACE_FULLFILL");
    private static final String ENV_MACAPK_TRACE_INSETS = System.getenv("MACAPK_TRACE_INSETS");
    private static final String ENV_MACAPK_TRACE_NAV = System.getenv("MACAPK_TRACE_NAV");
    private static final String ENV_MACAPK_TRACE_RV = System.getenv("MACAPK_TRACE_RV");

    private static boolean sLoggedVisFrameCheck;
    private static final String TAG = "Mac.apk.ViewCanvasDriver";
    private static boolean sClearTraced;   // MACAPK_TRACE_FULLFILL one-shot, see the [clear] line

    private final View rootView;
    private static long sPrevGlobalDrawOps = 0;
    /** Reused fill for the FLAG_DIM_BEHIND scrim; allocating a Paint per dialog
     *  per frame would be pure garbage at 60fps. Single-threaded (the draw
     *  pass), so no synchronization. */
    private static android.graphics.Paint sDimPaint;
    private Bitmap   bitmap;
    private Canvas   canvas;
    private int      surfaceW;
    private int      surfaceH;

    public ViewCanvasDriver(View root) {
        this.rootView = root;
        // A new driver is a new window for this root (v1.0.1643).
        if (root != null) root.macapkClearWindowRemoved();
    }

    @Override public void macapkSurfaceChanged(int widthPx, int heightPx) {
        // Tier 2 M2: this fires on the OS-main Metal callback and calls
        // rootView.layout() -- a view-tree mutation. In REAL_LOOPER mode it
        // must run on the ONE main loop, serialized with doFrame()/touch, not
        // race them. Posted (ordered before the subsequent frame post).
        if (android.os.Looper.MACAPK_REAL_LOOPER
                && !android.os.Looper.macapkIsActivityThread()) {
            loopHandler().post(() -> doSurfaceChanged(widthPx, heightPx));
            return;
        }
        doSurfaceChanged(widthPx, heightPx);
    }

    private void doSurfaceChanged(int widthPx, int heightPx) {
        // v1.0.1637: apply any pending DISPLAY change first (idempotent; the
        // native frame that resized the surface normally queued it just ahead
        // of this post), so the tree is measured under the Configuration and
        // DisplayMetrics that describe the size it is about to be given.
        DisplayConfig.commitPending();
        this.surfaceW = widthPx;
        this.surfaceH = heightPx;
        // Thread identity (v1.0.1636): the owner-only routing means this must
        // now run on the owner thread. owner=false here (or at the mid-frame
        // tripwire below) means a doSurfaceChanged still reached the OS-main
        // drain -- the cross-thread NPE root cause is back. Names are useless
        // (both threads are "main" since v1.0.939), so log owner+id.
        NativeLog.i(TAG, "surfaceChanged " + widthPx + "x" + heightPx
            + " [owner=" + android.os.Looper.macapkIsActivityThread()
            + " tid=" + Thread.currentThread().getId() + "]");
        // Drop bitmap so next draw reallocates at new size.
        bitmap = null;
        canvas = null;
        // Hand the view-tree the new dimensions via onSizeChanged-
        // adjacent callback; we use the layout setter for simplicity.
        // v1.0.134: inset the layout by the status-bar height so the
        // app lays out BELOW the system bar (WindowInsets-style push-
        // down). The status bar is painted by StatusBarDriver in
        // macapkDrawFrame; rootView.top == statusBarH so the
        // bottom-nav anchored to rootView.bottom still lands at the
        // screen's actual bottom.
        if (rootView != null) {
            int statusH = StatusBarDriver.heightPx(rootView);
            // ⚠ MEASURE BEFORE LAYOUT. Never call layout() bare here (v1.0.837).
            //
            // AOSP never lays out without measuring first: ViewRootImpl
            // .performTraversals() is always performMeasure() then performLayout().
            // A bare layout() looks harmless -- it only assigns bounds -- but
            // View.layout() CORRECTLY clears PFLAG_FORCE_LAYOUT, i.e. it clears
            // isLayoutRequested() on every view it touches. That is a fleet-wide
            // layout hazard, because androidx ConstraintLayout's dirty flag is a
            // ONE SHOT:
            //
            //   onMeasure: if (!mDirtyHierarchy) -> straight to resolveSystem
            //              mDirtyHierarchy = false           // cleared unconditionally
            //              for each child: isLayoutRequested() -> "recompute"
            //              if (!recompute) -> SKIP setChildrenConstraints() entirely
            //   setChildrenConstraints() is the ONLY place mChildren.add(widget) runs,
            //   and resolveSystem() iterates mChildren to measure children.
            //
            // So an unmeasured layout() between a CL's inflate and the tree's first
            // real measure pass consumes that one shot with every child already
            // clean. setChildrenConstraints() never runs, the solver's widget list
            // stays EMPTY for the life of the process, and the ConstraintLayout then
            // measures ITSELF correctly forever while never calling child.measure()
            // on anything. Measured on Wikipedia: 208 CL measures at
            // w=808 EXACTLY h=1622 EXACTLY, zero child measures, no onMeasure throw.
            // Second instance the same night: OpenCalculator's root CL solves while
            // both nested CLs inside SlidingUpPanelLayout have every child at 0x0.
            //
            // It poisons ANY ConstraintLayout attached after the last
            // surfaceChanged but before the next traversal -- late-attaching
            // fragments, ViewStubs, dialog subtrees -- and because it depends on
            // that ordering it is a RACE, so expect run-to-run variation.
            //
            // Measuring first restores the AOSP invariant and re-dirties nothing:
            // the children are measured while still marked layout-requested, so the
            // CL builds its widget graph on the pass that matters. (The
            // scheduleLayout() below is NOT a substitute -- it only exists on the
            // MACAPK_REAL_LOOPER path.)
            // v1.0.865: reserve the bottom nav strip unless the app opted into
            // edge-to-edge, and keep MEASURE and LAYOUT on the same height --
            // they must agree or bottom-anchored children solve against a
            // parent taller than the one they are laid out in.
            // v1.0.996: through StatusBarDriver.contentInsetBottomPx, which is
            // the SAME accessor the ANGLE render layer and the GL surface-size
            // publication now read. The gate (decorFitsSystemWindows) and the
            // value are unchanged; what changed is that there is one copy of it.
            final int navH0 = StatusBarDriver.contentInsetBottomPx(rootView);
            // v1.0.997: ...and the SIDE inset, because on a landscape
            // phone-class display AOSP puts the navigation bar on the display's
            // RIGHT edge, where it costs the app WIDTH rather than height
            // (DisplayPolicy.navigationBarPosition / :1859-1877; measured
            // app=1700x808 inside cur=1796x808). Both are 0 for an immersive or
            // edge-to-edge app, and the right one is 0 in portrait, so this is
            // inert everywhere it was inert before.
            final int navL0 = StatusBarDriver.contentInsetLeftPx(rootView);
            final int navR0 = StatusBarDriver.contentInsetRightPx(rootView);
            // v1.0.874: AOSP's pre-O max-aspect letterbox. A legacy activity
            // (targetSdk < 24, no resizeableActivity opt-in) does not get the
            // whole tall window -- it is capped to 1.86 and the remainder is
            // background. Applied HERE, on the same height the layout below
            // uses, because the drawing surface is sized natively and never
            // sees DisplayMetrics; clamping the metrics alone leaves this
            // measure at full height and the letterbox measures zero.
            // Same nav-bar INTERSECTION as the per-frame path below (see the
            // long comment at contentBottom). Both sites must agree or measure
            // and layout derive from different heights, which silently makes a
            // layout change do nothing.
            final int lb0 = Boot.macapkLetterboxHeight(widthPx, heightPx);
            final int navTop0 = heightPx - navH0;
            final int bottom0 = lb0 - Math.max(0, lb0 - navTop0);
            // Same rule as the height: MEASURE and LAYOUT must derive the width
            // from the same two locals, or every end-anchored child solves
            // against a parent wider than the one it is laid out in.
            final int left0  = navL0;
            final int right0 = widthPx - navR0;
            // v1.0.1047: ATTACH BEFORE MEASURE. AOSP's ViewRootImpl.performTraversals
            // dispatches attach (ViewRootImpl.java:3547, android14-release) BEFORE
            // every performMeasure (:3629); this method measured and laid out the
            // whole tree with no attach dispatch at all -- the only one was in
            // doFrame, one frame later.
            //
            // It matters most for Compose: AbstractComposeView.onMeasure calls
            // ensureCompositionCreated() FIRST, and that path asserts
            // isAttachedToWindow (WindowRecomposer.android.kt), so on this pass a
            // ComposeView throws "Cannot locate windowRecomposer" before it ever
            // reaches internalOnMeasure. Two independent lanes reached this same
            // site from different apps (a black-screen cluster and Thunderbird's
            // empty onboarding), which is why it is landed on ordering grounds
            // even though neither could prove it moves its own symptom.
            // macapkDispatchAttachedToWindow is idempotent, so the later doFrame
            // dispatch stays correct.
            if (rootView.macapkIsWindowRemoved()) return;   // v1.0.1643, see doFrame
            try { rootView.macapkDispatchAttachedToWindow(); }
            catch (Throwable ignored) {}
            rootView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    Math.max(0, right0 - left0), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    Math.max(0, bottom0 - statusH), android.view.View.MeasureSpec.EXACTLY));
            rootView.layout(left0, statusH, right0, bottom0);
        }
        // Tier 3 M6: a surface resize invalidates the whole tree -- force the
        // next frame to fully traverse (measure+layout, not just draw) at the new
        // dimensions.
        if (android.os.Looper.MACAPK_REAL_LOOPER) android.view.ViewRootImpl.scheduleLayout();
    }

    /** Sparse diagnostic counter: logs the first draw, then every 60th,
     *  then every 300th. Lets the launcher panel show "Canvas pipeline
     *  is alive and pushing N pixels per frame" without spamming the
     *  log at 60 fps. Mirrors the existing pattern in SurfaceView's
     *  macapkDrawFrame logging. */
    private long drawFrameCount = 0;
    /** The Canvas of the frame currently being drawn, or null outside a draw.
     *  See the assignment site in macapkDrawFrame for what it answers. */
    public static volatile android.graphics.Canvas macapkRootCanvas;
    /** FRAMESPEED S2: true while rootView.draw runs under a DAMAGE clip. A view
     *  drawn then sees a clip narrowed by the damage rect, not its real one, so
     *  S1's drawn-origin record must NOT be refreshed from it (the record from
     *  the last full frame still holds: a partial frame is draw-only, so no view
     *  moved). Read by View.macapkRecordDrawnOrigin (the S1 patch). */
    public static volatile boolean macapkPartialDraw;
    private static final boolean PUNCH_TRACE =
            "1".equals(System.getenv("MACAPK_PUNCH_TRACE"));
    private static int sPunchProbes;
    // Tier 3 M6 dirty-traversal tuning.
    private static final long M6_WARMUP_FRAMES   = 90;   // ~1.5s: always traverse during initial bring-up
    private static final long M6_HEARTBEAT_FRAMES = 30;  // ~0.5s backstop for un-invalidated mutations
    // Did the LAST traversal draw anything, and did the one before it? See the
    // present-elision block for why both are needed.
    // v1.0.1055: PER-DRIVER, not static. This app runs one driver per Activity
    // (Thunderbird's MainActivity finishes during onCreate and hands off to
    // FeatureLauncherActivity), and while these were static the OUTGOING
    // Activity's last frame decided the INCOMING one's first present. The
    // splash's ink=false latched the onboarding driver's very first frame into
    // an elision it never recovered from.
    private boolean sFrameDrewInk     = true;
    private boolean sLastPublishedInk = true;
    // Where sFrameDrewInk is sampled. Default: after the window-overlay pass,
    // so every pass that draws app pixels (rootView, ComposeViewFallback,
    // Dialog / PopupWindow / Toast windows) counts. MACAPK_COUNT_ALL_INK=0 is
    // the same bisect gate android.graphics.Canvas reads for its per-primitive
    // ink counting, and restores BOTH halves of the old behaviour: the delta
    // is taken across rootView.draw alone. Read here rather than through
    // Canvas so the loader jar does not depend on a framework-private field.
    private static final boolean INK_AFTER_ALL_PASSES =
        !"0".equals(System.getenv("MACAPK_COUNT_ALL_INK"));
    private static long    sPresentsElided   = 0;
    // P2/P3 of docs/canvas-present-cost-at-full-screen_2026-09-25.md: present
    // through NativeCocoa.presentFrame -- an IOSurface ring written with only the
    // row spans an exact diff against the last presented frame found changed,
    // and no present at all when nothing did -- instead of presentBitmap's full
    // CGImage every frame. ON by default since v1.0.1649, after the live F6
    // check (2026-09-26): MACAPK_PRESENT_VERIFY=1 reported mismatching=0 on every
    // app that crossed 300 presents; a recap_ab.sh A/B over opencalculator,
    // fdroid, anki, newpipe, deskclock and bouncy was pixel-identical OFF vs ON
    // on five and differed only in NewPipe's live content on the sixth; window
    // screencaptures of OpenCalculator and Vector Pinball (the translucent
    // overlay-on-GL case) differ by at most ONE step per channel on saturated
    // colours -- the sRGB tag's colour matching against the old DeviceRGB
    // CGImage, the AOSP-faithful choice the design names. Measured cost at
    // 3360x1796 (OpenCalculator, --drive force): present 1.37 -> 0.90 ms per
    // frame, and CoreAnimation's full-frame copy leaves the main thread.
    // MACAPK_PRESENT_IOSURFACE=0 restores presentBitmap exactly as before.
    private static final boolean PRESENT_IOSURFACE =
            !"0".equals(System.getenv("MACAPK_PRESENT_IOSURFACE"));
    // The 08-30 damage validator (docs/damage-aware-partial-redraw-design_2026-08-30.md):
    // the native diff also scans outside the claimed bound and logs every change
    // there as `[damage-validate] under-damage`. The bound is the whole surface
    // until view damage exists, so today it can only ever report nothing.
    private static final boolean DAMAGE_VALIDATE =
            "1".equals(System.getenv("MACAPK_DAMAGE_VALIDATE"));
    // presentFrame is not registered (a runtime binary older than this jar):
    // present through presentBitmap for the rest of the process.
    private static volatile boolean sPresentFrameUnavailable = false;
    // The next presentFrame must treat every pixel as changed: this driver's
    // first frame, and the first after the frame bitmap is (re)allocated.
    private boolean presentForceFull = true;

    // ---- FRAMESPEED: the idle heartbeat backs off when it proves useless ----
    // (docs/frame-speed-canvas-hotspots_2026-09-26.md, section 1d; gate:
    //  debug/framespeed_gate.sh loader, rows hb.*)
    //
    // WHY THE HEARTBEAT EXISTS. AOSP draws only when something invalidates:
    // an idle window is never traversed. Our invalidate() coverage is not yet
    // complete, so M6 forces a FULL traversal (measure, layout, draw, present)
    // every M6_HEARTBEAT_FRAMES vsyncs of an idle window, bounding how long a
    // mutation that never called invalidate() can stay off screen (~0.5 s).
    // It is a Mac.apk safety net, not an AOSP behaviour. At the 3360x1796
    // window one costs 12-30 ms of loop-thread time on OpenCalculator (the
    // frame-kind trace's "heartbeat" row), twice a second, for as long as the
    // app sits idle -- and a tap that lands on it waits for it.
    //
    // WHAT CHANGED. Since the IOSurface present (v1.0.1649) the native diff
    // answers, exactly, whether a heartbeat changed ANY pixel: presentFrame
    // returns PRESENT_ELIDED_IDENTICAL when it did not. So the net now watches
    // its own catch. After HeartbeatPolicy.QUIET_TO_WIDEN (4) consecutive
    // heartbeats that changed nothing (or published nothing: the ink-elided
    // empty tree of a native-EGL game), the interval doubles, 30 -> 60 -> 120
    // vsyncs (2 s at 60 Hz); ANY
    // heartbeat that did change pixels -- which is the net catching exactly
    // what it exists for -- snaps it back to 30, as do real user input, an
    // app-requested layout, a new frame bitmap and every present whose outcome
    // is unknown (the legacy presentBitmap path, a fallback). So an app whose
    // idle screen keeps mutating without invalidating keeps the 0.5 s bound,
    // and a genuinely idle app is traversed every 2 s instead of every 0.5 s.
    // The worst case for an un-invalidated change after a long quiet stretch
    // is therefore 2 s instead of 0.5 s; AOSP's is "never".
    //
    // Only UNSCHEDULED frames use the widened interval: a frame something
    // scheduled keeps the 30-vsync grid for its forced relayout, byte for byte,
    // so an animation or a scroll stream is unaffected. Widened intervals are
    // multiples of 30, so a heartbeat still lands on the old grid (the
    // n % 600 checkpoint and the phase trace keep their meaning). A window that
    // has presented a damage-clipped PARTIAL frame (S2) since its last heartbeat
    // is put back on the 30-vsync net as well: once S1 lands, the heartbeat is
    // the only bound on an S2 under-damage, and the 08-30 design keeps that
    // bound at 0.5 s.
    //
    // ⚠ OPT-IN (fix pass, 2026-09-26): MACAPK_FRAMESPEED_HEARTBEAT=1 enables
    // the back-off; unset (or anything else) is the fixed 30-vsync heartbeat,
    // exactly the pre-change schedule. The 08-30 design (section 6) says a
    // widening is "judged only after stage 3 has soaked with the validator
    // clean", and cocoa_bridge.mm's P3 comment names the evidence: the always-on
    // [heartbeat-diff] metric at ZERO across the fleet. No fleet run.log has
    // been captured with it yet (none of the ~60 in
    // ~/Desktop/emu_ab_tablet_2026-09-25 carries a [heartbeat-diff] line), and
    // OpenCalculator's caret is on screen ONLY through heartbeats (10 of 300
    // frames changed ~4k px at 2 Hz with no traversal scheduled) -- a live gap
    // the snap-back hides for a regular change but not for a one-off one. Flip
    // the default only after a [heartbeat-diff] census reads zero or near it.
    private static final boolean FRAMESPEED_HEARTBEAT =
            heartbeatGate(System.getenv("MACAPK_FRAMESPEED_HEARTBEAT"));
    /** The MACAPK_FRAMESPEED_HEARTBEAT reading, as a pure function the offline
     *  gate can pin (opt-in: only "1" enables the back-off). */
    static boolean heartbeatGate(String env) { return "1".equals(env); }
    private final HeartbeatPolicy hb = new HeartbeatPolicy(FRAMESPEED_HEARTBEAT);
    private long hbLastInputMillis = 0;                    // TouchDispatcher's, last seen

    /** The heartbeat's schedule, as a pure object so debug/framespeed_gate.sh
     *  can drive it offline (the driver's frame needs a window). */
    static class HeartbeatPolicy {
        static final long BASE = M6_HEARTBEAT_FRAMES;      // 30 vsyncs, the pre-change net
        static final long MAX = 120;                       // 2 s at 60 Hz
        static final int  QUIET_TO_WIDEN = 4;              // quiet heartbeats per doubling
        final boolean enabled;
        long interval = BASE;                              // current idle heartbeat period
        int  quiet = 0;                                    // consecutive quiet heartbeats
        long widenings = 0, resets = 0;                    // diagnostic, reported with M6
        HeartbeatPolicy(boolean enabled) { this.enabled = enabled; }
        /** Is vsync n a heartbeat? The widened interval applies only to a frame
         *  nothing scheduled; a scheduled frame keeps the 30-vsync grid. */
        boolean due(long n, boolean scheduled) {
            if (!enabled || scheduled) return (n % BASE) == 0;
            return (n % interval) == 0;
        }
        /** Back to the 30-vsync net. */
        void reset() {
            if (interval != BASE) resets++;
            interval = BASE;
            quiet = 0;
        }
        /** A heartbeat frame's outcome: `wasQuiet` = it provably changed no pixel. */
        void outcome(boolean wasQuiet) {
            if (!enabled) return;
            if (!wasQuiet) { reset(); return; }
            if (++quiet >= QUIET_TO_WIDEN && interval < MAX) {
                interval = Math.min(MAX, interval * 2);
                quiet = 0;
                widenings++;
            }
        }
    }

    // ---- FRAMESPEED: damage-clipped DRAW-ONLY frames (08-30 design, S2) ----
    // (docs/damage-aware-partial-redraw-design_2026-08-30.md; the numbers and
    // the S1 patch are in docs/frame-speed-canvas-hotspots_2026-09-26.md)
    //
    // AOSP's software path draws a frame through mSurface.lockCanvas(dirty):
    // the canvas is clipped to the dirty rect, pixels outside it are kept, and
    // the clear "automatically respects the clip/dirty region"
    // (ViewRootImpl.drawSoftware). Our frame bitmap already persists across
    // frames, so the same thing here is a clip around the clear, the draw and
    // the system chrome, and a present whose diff is bounded by the same rect.
    //
    // The DAMAGE comes from ViewRootImpl.macapkConsumeDamage(int[]) -- the S1
    // half, which records where an invalidated view was ACTUALLY drawn (its
    // device origin and clip at the top of View.draw) and answers 0 = none,
    // 1 = partial (out = the union, in frame-canvas device pixels), 2 = full.
    // It is looked up reflectively because S1 lands in files this lane does not
    // own; until it exists every frame is FULL and this block is inert.
    //
    // A frame is partial only when ALL of these hold (the design's section 5
    // full-redraw triggers, each tested): the frame is a scheduled DRAW-ONLY
    // frame (never a layout, warm-up or heartbeat frame -- the keystone rule);
    // S1 answered 1 with a non-empty rect; the frame bitmap is not new; the
    // previous frame's draw did not throw; nothing about the window chrome
    // changed since the last frame (surface size, GL-hole state, clear colour,
    // status-bar height and minute, nav insets, letterbox, the SurfaceView video
    // rect); no floating window is up now or was last frame; and the rect does
    // not cross the row the status bar samples its colour from.
    //
    // MACAPK_DAMAGE_VALIDATE=1 (the 08-30 falsifier) keeps drawing FULLY but
    // hands the damage to presentFrame as the claimed bound with the VALIDATE
    // flag, so the native diff names every changed pixel OUTSIDE the claim as
    // `[damage-validate] under-damage` -- run it before trusting S1 on an app.
    // MACAPK_FRAMESPEED_DAMAGE=0 restores full frames exactly.
    private static final boolean FRAMESPEED_DAMAGE =
            !"0".equals(System.getenv("MACAPK_FRAMESPEED_DAMAGE"));
    private static final java.lang.invoke.MethodHandle CONSUME_DAMAGE = lookupConsumeDamage();
    private static java.lang.invoke.MethodHandle lookupConsumeDamage() {
        try {
            return java.lang.invoke.MethodHandles.publicLookup().findVirtual(
                android.view.ViewRootImpl.class, "macapkConsumeDamage",
                java.lang.invoke.MethodType.methodType(int.class, int[].class));
        } catch (Throwable t) {
            return null;   // S1 not landed: every frame is full
        }
    }
    public static final int DAMAGE_NONE = 0, DAMAGE_PARTIAL = 1, DAMAGE_FULL = 2;
    /** S1's accumulated damage since the last call (DAMAGE_*), or DAMAGE_FULL
     *  when S1 is absent or answers anything unexpected. */
    static int macapkConsumeDamage(int[] out4) {
        final java.lang.invoke.MethodHandle mh = CONSUME_DAMAGE;
        if (mh == null) return DAMAGE_FULL;
        try {
            int k = (int) mh.invoke(android.view.ViewRootImpl.getInstance(), out4);
            return (k == DAMAGE_NONE || k == DAMAGE_PARTIAL) ? k : DAMAGE_FULL;
        } catch (Throwable t) {
            return DAMAGE_FULL;
        }
    }
    /** The partial-frame decision, pure so debug/framespeed_gate.sh can drive
     *  every trigger offline. Returns the clamped rect in out4 and true only when
     *  every condition in the block above holds. */
    public static boolean macapkPartialRect(boolean drawOnlyScheduled, int damageKind, int[] dmg,
                                     boolean forceFull, long chromeKey, long lastChromeKey,
                                     boolean overlayNow, boolean overlayLast,
                                     int surfaceW, int surfaceH, int statusBarH, int[] out4) {
        if (!drawOnlyScheduled || damageKind != DAMAGE_PARTIAL || forceFull) return false;
        if (chromeKey != lastChromeKey || overlayNow || overlayLast) return false;
        final int l = Math.max(0, dmg[0]), t = Math.max(0, dmg[1]);
        final int r = Math.min(surfaceW, dmg[2]), b = Math.min(surfaceH, dmg[3]);
        if (r <= l || b <= t) return false;
        // The status bar samples its background from the content row
        // statusBarH + 2 (sampleRowDominant); damage there can recolour the
        // whole bar, which lies outside the rect.
        if (statusBarH > 0 && t <= statusBarH + 2 && b > statusBarH + 2) return false;
        out4[0] = l; out4[1] = t; out4[2] = r; out4[3] = b;
        return true;
    }
    private final int[] dmgRect = new int[4];
    private final int[] partialRect = new int[4];
    private int dmgKind = DAMAGE_FULL;
    private boolean damageForceFullNext = true;           // first frame, and after a throw
    private long lastChromeKey = Long.MIN_VALUE;
    private boolean lastOverlayNonEmpty = true;
    private int m6PartialCount = 0;                       // partial frames since the last report
    private long m6PartialArea = 0;                       // their summed rect area (px)

    private int m6SkipCount = 0;                          // skipped frames since the last heartbeat (diagnostic)
    private int m6DrawOnlyCount = 0;                      // draw-only (no relayout) frames since the last report (diagnostic)

    // MACAPK_FRAME_PHASE_TRACE=1 (diagnostic, off by default; zero cost unset):
    // per-phase wall time of every PRESENTED frame, aggregated every 300 and
    // with an immediate line for any single frame over 33ms (two vsyncs -- the
    // "tab switch janks" shape). Added by the 2026-08-18 lag/smear lane so
    // "laggy" decomposes into pump/prep/layout/draw/bar/present numbers.
    private static final boolean PHASE_TRACE =
            "1".equals(System.getenv("MACAPK_FRAME_PHASE_TRACE"));
    private long ptSumPre, ptSumPrep, ptSumLayout, ptSumDraw, ptSumBar, ptSumPresent;
    private long ptMaxTotal; private int ptFrames, ptOver17, ptOver33, ptLayoutFrames;
    private long ptSumCkpt;
    // 2026-09-25 (P0 of docs/canvas-present-cost-at-full-screen_2026-09-25.md):
    // the n=300 line above BLENDS frames that run in different regimes, and one
    // blended mean produced a wrong conclusion. OpenCalculator's full-screen row
    // was 90 continuous warm-up frames plus 210 idle HEARTBEAT frames, and the
    // heartbeats ran 2-3.5x slower in EVERY phase (layout included, which
    // touches no pixels): a CPU-after-idle effect, not an area cost. So the trace
    // also aggregates per frame KIND, classified at the M6 gate:
    //   warmup    -- n <= M6_WARMUP_FRAMES (always traverses, always lays out)
    //   heartbeat -- the backstop fired on a frame nothing scheduled (isolated,
    //                every 30th vsync of an idle window)
    //   wake      -- a scheduled frame after >= M6_WAKE_GAP_FRAMES idle vsyncs:
    //                the first frame after idle, i.e. the input-latency face of
    //                the heartbeat's cold regime (a tap on an idle screen)
    //   full      -- a scheduled frame that lays out, in a stream (also every
    //                frame of the flag-off path and of MACAPK_M6_FORCE_DRAW=1)
    //   draw      -- a scheduled draw-only frame (scroll / animation / ripple)
    private static final int FK_WARMUP = 0, FK_HEARTBEAT = 1, FK_WAKE = 2,
                             FK_FULL = 3, FK_DRAW = 4;
    private static final String[] FK_NAMES = {"warmup", "heartbeat", "wake", "full", "draw"};
    private static final long M6_WAKE_GAP_FRAMES = 15;   // ~250 ms of skipped vsyncs
    private long m6IdleStreak = 0;                          // consecutive skipped vsyncs so far
    private PhaseKindAgg[] ptKinds;                         // lazily allocated, PHASE_TRACE only

    /** One frame kind's phase aggregate (see FK_* above). Flushed as its own
     *  `[frame-phase] kind=...` line at PT_KIND_N frames, or once the window is
     *  PT_KIND_WINDOW_NS old so a sparse kind (heartbeats arrive at ~2/s, wake
     *  frames at the drive's tap rate) still reports within a harness run. */
    private static final class PhaseKindAgg {
        static final int PT_KIND_N = 300;
        static final long PT_KIND_WINDOW_NS = 20_000_000_000L;
        final long[] totals = new long[PT_KIND_N];
        long sumPre, sumPrep, sumLayout, sumDraw, sumBar, sumPresent, sumCkpt, max, startNs;
        int n, over17, over33, elided;
    }
    // One-shot marker for the mid-frame surface-reset guard below doFrame's
    // draw section (rewire hardening, 2026-08-18).
    private boolean warnedMidFrameReset;

    // Tier 3 M8: retire the per-frame geometry "enforce/fixup/force" hacks now
    // that M5 (faithful base widgets) + M6 (scheduled traversal) make them
    // redundant. RETIRED ON THE MACAPK_REAL_LOOPER PATH ONLY: the shipped
    // (flag-off) build has none of M5/M6/M7, so it still needs every hack -- a
    // default-retired hack keeps running when the flag is off (shipped byte-for-
    // byte unchanged) and is skipped only on the real-loop path.
    //   DEFAULT_RETIRED -- verified redundant on the real path (F-Droid + the full
    //     protected sweep render correctly without them): the faithful LinearLayout
    //     (linfix), FrameLayout/LinearLayout gravity (gravity), and base-widget
    //     measure/layout (zeroext) replace these; and M15's clampNavBarMeasured-
    //     Height (between the measure + layout passes) replaces the Material nav
    //     POSITION hacks navbar (enforceM3NavigationBarHeight) + navitem
    //     (fixupNavigationBarItemViews) -- once the bar's measured height is
    //     clamped to the design nav height, RelativeLayout's alignParentBottom +
    //     the layout_above sibling lay the whole nav out correctly via the real
    //     rules (verified: F-Droid renders banner + bottom nav + content with
    //     navbar,navitem retired). The clamp is the more-faithful fix (it corrects
    //     the root measure, then the real onMeasure/onLayout chain runs) and is
    //     itself a candidate to delete once the bundled BottomNavigationMenuView's
    //     fill-its-given-height onMeasure is reproduced at the measure-spec layer.
    //   tabpager (M21, RecyclerView-for-real): RETIRED -- F-Droid's tab pager is a
    //     populated 5-item RecyclerView (MainViewAdapter) on the NATURAL path (the
    //     real RecyclerView.dispatchLayout runs since itemCount>0, M5/M6 give it a
    //     valid measure/layout). Its NonScrollingHorizontalLayoutManager lays the 5
    //     pages out horizontally, so the selected page sits at x=0 and the rest go
    //     off-screen -- the hack's force-everything-else-GONE is redundant. Verified:
    //     F-Droid renders banner + content + bottom nav with tabpager retired (the
    //     last nav/RV geometry hack gone). The remaining force-bind machinery
    //     (forceBindRecyclerView/layoutGridManually, gated by MACAPK_RV_NATURAL, NOT
    //     by hackEnabled) only fires for a populated GRID list, which no corpus app
    //     shows offline; it stays as the safety net until the natural grid path is
    //     verified against a synthetic RV test APK.
    // Overrides: MACAPK_RETIRE_HACKS=<names|all> force-OFF (testing);
    // MACAPK_KEEP_HACKS=<names> re-enable a default-retired hack (rollback).
    private static final java.util.Set<String> DEFAULT_RETIRED =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "linfix", "gravity", "zeroext", "navbar", "navitem", "tabpager"));
    private static final java.util.Set<String> RETIRED_HACKS = parseHackSet("MACAPK_RETIRE_HACKS");
    private static final java.util.Set<String> KEPT_HACKS    = parseHackSet("MACAPK_KEEP_HACKS");
    private static java.util.Set<String> parseHackSet(String envName) {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        String env = System.getenv(envName);
        if (env != null) for (String t : env.split(",")) {
            String x = t.trim().toLowerCase();
            if (!x.isEmpty()) s.add(x);
        }
        return s;
    }
    private static boolean hackEnabled(String name) {
        if (RETIRED_HACKS.contains("all") || RETIRED_HACKS.contains(name)) return false;
        if (DEFAULT_RETIRED.contains(name)
                && android.os.Looper.MACAPK_REAL_LOOPER
                && !KEPT_HACKS.contains(name)) {
            return false;   // retired on the real-loop path (M5/M6 replace it)
        }
        return true;        // flag-off (shipped) keeps every hack; nav hacks stay
    }
    // v1.0.363: once we've drawn a frame for a pure View/Compose tree,
    // deliver window focus (see the macapkDrawFrame call site).
    private boolean windowFocusDelivered = false;

    // Tier 2 M2: when MACAPK_REAL_LOOPER is on, the canvas traversal runs on
    // the ONE main loop (macapk-activity), not on the OS-main Metal callback.
    // framePending coalesces frame posts (at most one in flight); loopHandler
    // is bound to the main looper so post() lands in the real main queue.
    private final java.util.concurrent.atomic.AtomicBoolean framePending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private android.os.Handler mLoopHandler;
    private android.os.Handler loopHandler() {
        if (mLoopHandler == null)
            // OWNER-ONLY (v1.0.1636): doFrame and doSurfaceChanged post through
            // here. Both mutate the frame bitmap / view tree, so the OS-main
            // render drain (macapkDrainMainDue, active during the lifecycle
            // handshake) must PASS OVER them -- otherwise doFrame runs on OS-main
            // while doSurfaceChanged runs on the owner thread and nulls `bitmap`
            // mid-draw (the cross-thread NPE at doFrame:1559). An owner-only
            // Handler leaves them queued for runRealMainLoop on the owner thread,
            // where they serialize with each other and with touch.
            mLoopHandler = android.os.Handler.macapkCreateOwnerOnly(
                android.os.Looper.getMainLooper());
        return mLoopHandler;
    }

    @Override public void macapkDrawFrame() {
        // Tier 2 M2: the Metal display-link callback runs on the OS main
        // thread. In MACAPK_REAL_LOOPER mode, post the traversal to the ONE
        // main loop (macapk-activity) and return -- the loop runs doFrame()
        // there, serialized with posted Handler work and canvas touch, so the
        // view tree is mutated by exactly one thread (no race). The present
        // (NativeCocoa.presentBitmap) self-dispatches back to the OS main
        // thread. Coalesced via framePending: at most one frame in flight, so
        // a slow loop coalesces frames (like Android) rather than backing up.
        // Off-mode -- or re-entrantly, when already ON the loop thread -- runs
        // inline, byte-for-byte the historical path.
        if (android.os.Looper.MACAPK_REAL_LOOPER
                && !android.os.Looper.macapkIsActivityThread()) {
            if (framePending.compareAndSet(false, true)) {
                loopHandler().post(() -> { framePending.set(false); doFrame(); });
            }
            return;
        }
        doFrame();
    }

    // Compose global-snapshot apply-notification pump. Real Android's
    // androidx.compose.ui.platform.GlobalSnapshotManager registers a global
    // write observer that schedules Snapshot.sendApplyNotifications() on the
    // AndroidUiDispatcher's frame callback every frame; that call advances the
    // global snapshot and fires the apply-observers each Recomposer registered,
    // turning a MutableState write made OUTSIDE the UI composition (e.g. a
    // Reddit "molecule" CompositionViewModel writing its ViewState from its own
    // composition/coroutine) into a recompose-invalidation of the window
    // composition. Our framework drives the frame clock but never advances the
    // global snapshot, so those cross-composition writes never invalidate the
    // window Recomposer and its content composes but never re-composes/applies
    // (Reddit's WelcomeV2 login stays blank: root LayoutNode frozen at root+1
    // empty leaf, recDelta all-zero). DIAGNOSTIC HARNESS (env-driven so the tree
    // stays app-agnostic -- the obfuscated SnapshotKt class/method name varies
    // per APK): MACAPK_SNAPSHOT_CLASS=<class> [MACAPK_SNAPSHOT_METHOD=<m>, default
    // "a"] makes us reflectively call that static no-arg method each frame via
    // the app's ClassLoader. If it makes the screen render, the global-snapshot
    // pump is confirmed as the blocker and the real fix resolves
    // sendApplyNotifications generically. Removal condition: replaced by a
    // rename-proof generic pump.
    private static volatile java.lang.reflect.Method sSnapPumpMethod = null;
    private static volatile Object sSnapPumpTarget = null; // null = static; else the Companion instance
    // Three-state resolve-once cache (PERF1 speed-run lane). UNRESOLVED -> exactly one
    // Class.forName attempt is made, ever; it then latches RESOLVED (sSnapPumpMethod
    // set, invoked every frame) or ABSENT (permanently skipped).
    private static final int SNAP_UNRESOLVED = 0;
    private static final int SNAP_RESOLVED   = 1;
    private static final int SNAP_ABSENT     = 2;
        /** Observable proof that the inset dispatch RAN. Read by the harness and
     *  printed in the one-line summary below; see the catch block for why a
     *  pixel comparison cannot substitute for these. */
    private static int sInsetDispatchAttempted;
    private static int sInsetDispatchDelivered;
    private static final java.util.Set<String> sInsetDispatchSeen =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One line per process, for the harness to grep. attempted != delivered
     *  means the dispatch is throwing and every "nothing moved" result taken
     *  from this pipeline is meaningless. */
    public static String macapkInsetDispatchStats() {
        return "inset-dispatch attempted=" + sInsetDispatchAttempted
             + " delivered=" + sInsetDispatchDelivered
             + (sInsetDispatchAttempted == sInsetDispatchDelivered ? " OK" : " ⚠ THROWING");
    }

    /** The decor is the WINDOW, so it is laid out at the window FRAME, not the
     *  surface: (contentRight - contentLeft) x contentBottom -- the same frame the
     *  root was just laid out into (nav bar excluded unless the app is immersive,
     *  letterbox applied, status bar included because PhoneWindow sets
     *  FLAG_LAYOUT_IN_SCREEN). AOSP, android14 ViewRootImpl
     *  .adjustLayoutParamsForCompatibility (:2603): "if ((sysUiVis &
     *  SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) != 0 || (flags &
     *  FLAG_TRANSLUCENT_NAVIGATION) != 0) { types &= ~Type.systemBars(); }" -- only
     *  then does the window extend under the navigation bar, and contentBottom is
     *  already that frame. The surface size (surfaceH) is taller than the frame by
     *  the nav inset or the letterbox excess, which would make every
     *  "decorView.getHeight() - visibleFrame" keyboard/space check wrong.
     *  Package-private: the offline gate (debug/touchinput_gate.sh, W3) calls it. */
    static void layoutDetachedDecor(android.app.Activity owner, android.view.View rootView,
                                    int contentLeft, int contentRight, int contentBottom) {
        if (owner == null || rootView == null) return;
        try {
            owner.macapkLayoutDetachedDecor(rootView, Math.max(0, contentRight - contentLeft),
                                            contentBottom);
        } catch (Throwable ignored) {}
    }
    private static volatile int sSnapPumpState = SNAP_UNRESOLVED;
    // v1.0.438: GENERIC, AOSP-faithful Compose global-snapshot pump. Real Android's
    // androidx.compose.ui.platform.GlobalSnapshotManager schedules
    // Snapshot.sendApplyNotifications() on the UI frame whenever a snapshot WRITE
    // happens; that call advances the global snapshot and fires each Recomposer's
    // apply-observer, turning a MutableState write made OUTSIDE the current
    // composition pass (a ViewModel/molecule coroutine, a background dispatcher,
    // a snapshotFlow predicate latch -- exactly Reddit's navstack visibility gate)
    // into a recompose invalidation. Snapshot.sendApplyNotifications() is PUBLIC
    // androidx API, so it survives R8 (public surface is kept) and we can resolve
    // it by name across apps -- no per-APK env needed. We call it every frame
    // before draining the recompose loop. MACAPK_SNAPSHOT_CLASS still works as a
    // manual override for the rare fully-stripped build. No-op for non-Compose
    // apps (resolve fails once, then skips).
    //
    // PERF1 (speed-run lane, 2026-07-30): replaced the v1.0.438/439 "retry every 30
    // frames for up to ~1800 frames" design with a single-attempt cache. Measured: on
    // an app with no Compose at all, the old design threw and swallowed a
    // ClassNotFoundException on every retry (~60 throws/process before the give-up
    // window closed) -- each one fills a JVM stack trace on the draw path. v1.0.439's
    // stated reason for retrying instead of caching permanently was "the first frames
    // run BEFORE androidx.compose is loaded, so an early Class.forName misses" -- but
    // that theory does not hold up: `cl` (= rootView.getClass().getClassLoader()) is
    // fixed for this driver's entire life (rootView is `final`), and Class.forName's
    // resolvability against a FIXED classloader cannot change between attempt 1 and
    // attempt 60 -- a classloader either can locate a given class on its classpath or
    // it cannot, and that fact does not depend on how many times or how late you ask.
    // changelog.md 2026-06-21 ("THE PUMP IS REFUTED") independently confirmed this
    // empirically: this exact pump had NEVER resolved for ANY app tested (Reddit,
    // Duolingo, and an un-obfuscated synthetic Compose app alike), because `rootView`
    // is typically a plain framework FrameLayout whose classloader has no app-private
    // androidx visibility -- a structural mismatch, not a timing one -- AND that real
    // Compose apps render fine regardless, because androidx's own
    // Recomposer/GlobalSnapshotManager calls Snapshot.sendApplyNotifications() itself
    // every frame independent of whether this redundant pump ever succeeds. So a
    // single attempt is provably equivalent to 60: whichever way attempt 1 goes,
    // attempts 2-60 were always going to go the same way. The positive path (an app
    // whose rootView classloader CAN see androidx.compose) is unaffected -- it
    // resolved on attempt 1 before this change too, and still does; only the
    // (empirically always-taken) negative path stops paying for 59 pointless retries.
    private void macapkPumpSnapshot() {
        if (rootView == null) return;
        if (sSnapPumpState == SNAP_ABSENT) return;
        java.lang.reflect.Method m = sSnapPumpMethod;
        if (m == null) {
            // Reached at most once per process: the first call either sets
            // sSnapPumpMethod (falls through to invoke below) or latches
            // SNAP_ABSENT and returns, so no later call re-enters this branch.
            try {
                ClassLoader cl = rootView.getClass().getClassLoader();
                String envCls = System.getenv("MACAPK_SNAPSHOT_CLASS");
                if (envCls != null) {
                    String mth = System.getenv("MACAPK_SNAPSHOT_METHOD");
                    if (mth == null) mth = "a";
                    Class<?> c = Class.forName(envCls, true, cl);
                    m = c.getDeclaredMethod(mth);
                    m.setAccessible(true);
                    sSnapPumpTarget = null;
                    NativeLog.i(TAG, "snapshot-pump: resolved override " + envCls + "." + mth + "()");
                } else {
                    Class<?> snap = Class.forName(
                        "androidx.compose.runtime.snapshots.Snapshot", true, cl);
                    // Public API is Snapshot.Companion.sendApplyNotifications().
                    // Try the Companion instance first, then a @JvmStatic on Snapshot.
                    try {
                        Object companion = snap.getField("Companion").get(null);
                        java.lang.reflect.Method cm =
                            companion.getClass().getMethod("sendApplyNotifications");
                        cm.setAccessible(true);
                        m = cm; sSnapPumpTarget = companion;
                    } catch (Throwable noCompanion) {
                        java.lang.reflect.Method sm =
                            snap.getMethod("sendApplyNotifications");
                        sm.setAccessible(true);
                        m = sm; sSnapPumpTarget = null;
                    }
                    NativeLog.i(TAG, "snapshot-pump: resolved generic Snapshot.sendApplyNotifications()");
                }
                sSnapPumpMethod = m;
                sSnapPumpState = SNAP_RESOLVED;
            } catch (Throwable t) {
                // Confirmed absent (or R8-renamed past finding): cache permanently,
                // see the PERF1 rationale above. Logged once -- this is the one CNFE
                // the process pays, replacing what used to be up to ~60.
                sSnapPumpState = SNAP_ABSENT;
                // ⚠ WORD THIS AS "EXPECTED", NOT AS A FAILURE. This pump is
                // REDUNDANT and empirically REFUTED (changelog.md 2026-06-21,
                // "THE PUMP IS REFUTED"): it has never resolved for ANY app --
                // R8'd or un-obfuscated synthetic alike -- and Compose renders
                // correctly regardless, because the app's own
                // Recomposer.runRecomposeAndApplyChanges calls
                // Snapshot.sendApplyNotifications() itself every frame and
                // GlobalSnapshotManager.ensureStarted edge-triggers it.
                //
                // The old wording ("not available for this app") reads like a
                // missing-API defect, and it IS read that way: the 2026-08-27
                // fleet census filed it as a gap blocking 48 apps off this line
                // alone, then spent a round of analysis on 4 apps that were all
                // rendering fine. A diagnostic that misleads costs more than no
                // diagnostic. Name the redundancy in the line itself.
                NativeLog.i(TAG, "snapshot-pump: not resolvable here -- EXPECTED and HARMLESS, "
                    + "the app's own Recomposer/GlobalSnapshotManager does this itself "
                    + "(changelog 2026-06-21 'THE PUMP IS REFUTED'); not a missing API: " + t);
                return;
            }
        }
        try { m.invoke(sSnapPumpTarget); } catch (Throwable ignored) {}
    }

    private void doFrame() {
        // v1.0.1643: the window this root belonged to was removed (activity
        // destroyed). A frame queued before the native renderer was released
        // must not re-attach, lay out or draw the dead tree.
        if (rootView.macapkIsWindowRemoved()) return;
        long n = ++drawFrameCount;
        // Phase-trace timestamps (all zero when the flag is off; assignments are
        // grouped at the six fixed points named in the field comment above).
        long pt0 = PHASE_TRACE ? System.nanoTime() : 0;
        long pt1 = 0, pt2 = 0, pt3 = 0, pt4 = 0, pt5 = 0;
        // v1.0.584 AGDK diagnostic (MACAPK_AGDK_TRACE): confirm the Canvas driver is
        // ticked for a native-EGL AGDK app (MCPE) so its per-frame dispatchOnGlobalLayout
        // (which drives GameActivity.onContentRectChanged -> APP_CMD_CONTENT_RECT_CHANGED)
        // actually runs. Report the root + whether it has a live tree observer.
        if ("1".equals(ENV_MACAPK_AGDK_TRACE) && (n == 1 || n == 5 || (n % 300) == 0)) {
            android.view.ViewTreeObserver vto0 = (rootView != null) ? rootView.macapkPeekTreeObserver() : null;
            NativeLog.i(TAG, "AGDKtrace doFrame #" + n
                + " root=" + (rootView != null ? rootView.getClass().getName() : "null")
                + " w=" + surfaceW + " h=" + surfaceH
                + " vto=" + (vto0 != null ? ("@" + Integer.toHexString(System.identityHashCode(vto0))
                              + " glListeners=" + vto0.macapkHasLayoutOrDrawListeners()) : "null"));
        }
        // Pump Compose's global snapshot BEFORE the frame clock drains the
        // recompose loop, so a cross-composition MutableState write becomes an
        // invalidation the recompose pass on this same frame can pick up.
        macapkPumpSnapshot();
        // Automated input-injection driver (MACAPK_INJECT_FILE). Since v1.0.1645
        // Boot.run STARTS it, right after the TouchDispatcher is registered,
        // because this frame never runs for a pure-GL app. This call is only a
        // second chance if Boot's threw; the driver's start is a CAS, so here it
        // is a no-op. (v1.0.444 to v1.0.1644 this WAS the start point.)
        try { com.macapk.loader.TouchDispatcher.macapkStartInjectDriver(); } catch (Throwable ignored) {}
        // Gated bottom-sheet animation probe (MACAPK_PROBE_SHEET): log the live
        // BottomSheetState/SwipeableState offset/target each ~15 frames so we can
        // see whether the auth modal's enter tween has the wrong target or stalls.
        if (rootView != null) {
            // Every frame: the lite cached-`l` path is cheap and needs per-frame
            // resolution of the open trajectory; the pre-cache BFS self-throttles.
            try { ComposeViewFallback.probeBottomSheetState(rootView); } catch (Throwable ignored) {}
            // v1.0.488 gated diagnostic: dump the bespoke navstack HOST's live state
            // (MACAPK_NAVSTACK_HOST_PROBE=1) to find which visible-entry-gate input is empty
            // (host.e() readiness / back-stack / per-entry visibility). One-shot once found.
            try { ComposeViewFallback.probeNavstackHost(rootView); } catch (Throwable ignored) {}
            // Gated diagnostic/fix: force compose-host root remeasure (MACAPK_FORCE_REMEASURE).
            try { ComposeViewFallback.forceRemeasureHosts(rootView); } catch (Throwable ignored) {}
        }
        // Tier 3 M7: drive the MAIN-loop Choreographer one real frame BEFORE the
        // traversal -- runs the queued frame callbacks (ValueAnimator /
        // ViewPropertyAnimator / MotionLayout / Compose withFrameNanos) on true
        // vsync. Those callbacks compute the animation's new value and invalidate(),
        // which schedules a traversal (M6), so the M6 dirty-gate below sees the
        // tree as dirty and renders the new animation frame on THIS vsync.
        // (No-op off the main loop / flag-off.)
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            try { android.view.Choreographer.macapkDriveMainFrame(); }
            catch (Throwable t) { NativeLog.w(TAG, "Choreographer.macapkDriveMainFrame: " + t); }
            // v1.0.463 EXPERIMENT (MACAPK_PUMP_AFTER_DRIVE): pump the Compose global
            // snapshot apply AGAIN, AFTER the frame clock drained the animation's
            // withFrameNanos writes (offset etc.), so those writes are apply-notified
            // to the layout snapshot-observer BEFORE this same frame's measureAndLayout/
            // draw -> the observer fires requestRelayout and the sheet re-places THIS
            // frame. (The start-of-frame pump only sees the PREVIOUS frame's writes.)
            // The decisive test for "frame-driven offset writes never re-place because
            // they are notified a frame too late / not at all."
            if ("1".equals(ENV_MACAPK_PUMP_AFTER_DRIVE)) {
                try { macapkPumpSnapshot(); } catch (Throwable ignored) {}
            }
        }
        if (PHASE_TRACE) pt1 = System.nanoTime();   // pre = pump + probes + choreographer drive
        if (rootView == null || surfaceW <= 0 || surfaceH <= 0) {
            if (n == 1 || n == 60 || n == 300 || (n % 600) == 0) {
                NativeLog.i(TAG,
                    "macapkDrawFrame skip #" + n
                    + " (root=" + (rootView != null)
                    + " w=" + surfaceW + " h=" + surfaceH + ")");
            }
            return;
        }
        // Tier 3 M6: traverse ONLY when the tree is dirty, instead of brute-
        // forcing measure->layout->draw->present every frame. "Dirty" =
        //   (a) a traversal was scheduled (invalidate/requestLayout/setVisibility/
        //       touch/surface-change called ViewRootImpl.scheduleTraversal) -- the
        //       precise AOSP signal; active apps (animations / Compose recompose)
        //       schedule every frame and so traverse every frame, OR
        //   (b) a low-frequency heartbeat (the safety net: bounds staleness from
        //       any tree mutation that does NOT yet route through invalidate -- our
        //       coverage is incomplete -- to ~0.5s; tightened toward zero as
        //       invalidate() coverage grows in M8), OR
        //   (c) the initial warm-up (first frames, so the first layout always lands).
        // Otherwise keep the last presented frame (the CAMetalLayer retains it) --
        // a genuinely idle window does ~2 traversals/sec instead of 60.
        // (An earlier "app did work on the loop this frame" net was dropped: it
        // counted per-frame FRAMEWORK chatter -- Choreographer ticks, heartbeats --
        // as work, so every app read as busy and nothing ever skipped.)
        // Flag-off: never gated -- the driver traverses every frame as before.
        //
        // Scroll-perf split (this frame is one of three kinds):
        //  - SKIP: nothing scheduled + no heartbeat + past warmup -> keep the last
        //    presented frame (idle window).
        //  - DRAW-ONLY: a traversal was scheduled by invalidate() only (scroll's
        //    offsetChildrenVertical, animations, ripples) -> redraw the stable tree
        //    WITHOUT re-measuring/re-laying-out it. AOSP performTraversals skips
        //    performMeasure/performLayout when !mLayoutRequested; a RecyclerView /
        //    ScrollView scroll is O(visible) redraw, not an O(whole-tree) relayout.
        //  - FULL: requestLayout was called (mLayoutRequested), OR the heartbeat /
        //    warmup backstop fires -> measure + layout + draw.
        // doLayout stays true for the flag-off path (every-frame full traversal).
        boolean doLayout = true;
        // Phase-trace frame kind (see FK_* above). The flag-off path traverses
        // every vsync, so its frames are FULL frames in a stream.
        int frameKind = FK_FULL;
        dmgKind = DAMAGE_FULL;   // FRAMESPEED S2: full unless the M6 block below says otherwise
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            boolean scheduled = android.view.ViewRootImpl.getInstance().consumeTraversalScheduled();
            boolean layoutRequested = android.view.ViewRootImpl.getInstance().consumeLayoutRequested();
            // FRAMESPEED heartbeat: evidence that the app is ACTIVE puts the net
            // back to 30 vsyncs before this frame's heartbeat test. A draw-only
            // invalidate (a caret blink, a spinner) is not such evidence: it
            // repaints its own damage and says nothing about mutations elsewhere.
            if (FRAMESPEED_HEARTBEAT) {
                final long lastInput = TouchDispatcher.lastUserInputMillis();
                if (lastInput != hbLastInputMillis) { hbLastInputMillis = lastInput; hb.reset(); }
                else if (layoutRequested) hb.reset();
            }
            boolean heartbeat = hb.due(n, scheduled);
            boolean warmup = n <= M6_WARMUP_FRAMES;
            // DIAGNOSTIC (MACAPK_M6_FORCE_DRAW): force a full traversal+draw every
            // frame, bypassing the dirty-gate skip. Decisive test for "an actively
            // animating Compose subtree (a pushed ComposeBottomSheetScreen) never
            // presents because its per-frame invalidation doesn't schedule a
            // traversal." If the modal renders ONLY with this on, the real fix is
            // to route Compose animation/recompose invalidation -> ViewRootImpl
            // .schedule(). NOT a shipping flag (defeats the M6 idle-perf win).
            if ("1".equals(ENV_MACAPK_M6_FORCE_DRAW)) { scheduled = true; layoutRequested = true; }
            // Periodically report how many of the last ~300 frames were skipped
            // (the M6 win: an idle window traverses ~2x/sec instead of 60x) and how
            // many ran DRAW-ONLY (the scroll-perf win: a scroll/animation frame
            // redraws without re-measuring/re-laying-out the whole tree).
            if ((n % 300) == 0 && n > M6_WARMUP_FRAMES) {
                NativeLog.i(TAG, "M6: skipped " + m6SkipCount + "/300 traversals (idle), "
                    + m6DrawOnlyCount + "/300 draw-only (scroll/anim, no relayout)"
                    + (FRAMESPEED_HEARTBEAT ? " heartbeat=" + hb.interval
                        + " widened=" + hb.widenings + " reset=" + hb.resets : "")
                    + (FRAMESPEED_DAMAGE ? " partial=" + m6PartialCount
                        + (m6PartialCount > 0 && surfaceW > 0 && surfaceH > 0
                           ? String.format(" (%.1f%% of the window each)",
                                 100.0 * m6PartialArea / m6PartialCount / ((double) surfaceW * surfaceH))
                           : "")
                        + (CONSUME_DAMAGE == null ? " [S1 absent]" : "") : ""));
                m6PartialCount = 0;
                m6PartialArea = 0;
                m6SkipCount = 0;
                m6DrawOnlyCount = 0;
            }
            if (!scheduled && !heartbeat && !warmup) {
                m6SkipCount++;
                m6IdleStreak++;
                return;   // nothing changed -- skip the whole measure/layout/draw/present
            }
            final long idleBefore = m6IdleStreak;
            m6IdleStreak = 0;
            // Draw-only unless a real layout was requested (or the backstops fire).
            // Env rollback MACAPK_SCROLL_DRAW_ONLY=0 forces every traversal to be
            // full (the pre-split behavior) for A/B.
            doLayout = layoutRequested || heartbeat || warmup
                       || "0".equals(ENV_MACAPK_SCROLL_DRAW_ONLY);
            // DIAGNOSTIC (MACAPK_NO_HEARTBEAT_LAYOUT): drop the periodic heartbeat
            // re-layout (keep it as a DRAW-only refresh). Test for "the heartbeat
            // full re-layout re-measures the Compose modal -> Material
            // SwipeableState.processNewAnchors cancels the bottom sheet's in-flight
            // enter animation -> it freezes partway". If the modal animates in with
            // this on, the heartbeat re-layout is the churn source.
            if ("1".equals(ENV_MACAPK_NO_HEARTBEAT_LAYOUT))
                doLayout = layoutRequested || warmup;
            if (!doLayout) m6DrawOnlyCount++;
            // A heartbeat that coincides with a scheduled frame is part of a
            // stream, so it is filed with the stream (full), not as a heartbeat.
            if (warmup)                               frameKind = FK_WARMUP;
            else if (!scheduled)                      frameKind = FK_HEARTBEAT;
            else if (idleBefore >= M6_WAKE_GAP_FRAMES) frameKind = FK_WAKE;
            else                                      frameKind = doLayout ? FK_FULL : FK_DRAW;
            // FRAMESPEED S2: take S1's damage with the traversal flag (the rect
            // follows the flag). Inert (DAMAGE_FULL) until S1 exists.
            if (FRAMESPEED_DAMAGE) dmgKind = macapkConsumeDamage(dmgRect);
        }
        // ⚠ `canvas == null` is part of the test on purpose. `bitmap` and `canvas` are allocated
        // together and are only meaningful as a PAIR, but this guard used to test `bitmap` alone --
        // so if `new Canvas(bitmap)` ever threw (an OOME out of `createGraphics()` is the plausible
        // one; the heaviest apps in the corpus are 10k+ classes), `bitmap` stayed assigned and
        // `canvas` stayed null, the guard never re-allocated, and EVERY subsequent frame threw
        // NPE at the `canvas.save()` below and skipped the present. The result is the whole
        // Canvas/widget tier invisible for the entire run behind a single latched warn line --
        // exactly the "rootView.draw threw ... this.canvas is null" reported on three apps.
        // Testing the pair makes the state self-healing on the very next frame.
        // (The same mid-frame-reset family was half-fixed at v1.0.997, which added a null guard in
        // front of the PRESENT but not in front of the DRAW.)
        if (bitmap == null || canvas == null
                              || bitmap.getWidth() != surfaceW
                              || bitmap.getHeight() != surfaceH) {
            bitmap = Bitmap.createBitmapWithCanvasBacking(surfaceW, surfaceH);
            canvas = new Canvas(bitmap);
            presentForceFull = true;   // a new bitmap: nothing about the last present carries over
            hb.reset();                // FRAMESPEED heartbeat: a new surface re-proves quiet
        }
        // Clear color depends on whether the View tree has any
        // SurfaceView/GLSurfaceView descendant.
        //
        // CASE A -- tree has a SurfaceView (Flappy Bird, OpenGD, Bouncy,
        //   any cocos2d-x v2 / AndEngine / libgdx app whose Activity
        //   ContentView contains a GLSurfaceView): the GL pipeline
        //   renders its own opaque content into the ANGLE Metal layer
        //   that sits BELOW this CALayer overlay. We clear to fully
        //   transparent so the overlay is additive -- text, HUD,
        //   buttons layer on top of GL pixels.
        //
        // CASE B -- pure View hierarchy (F-Droid CalculatorActivity,
        //   sgtpuzzles menu, any AppCompat / Material activity that
        //   doesn't host a SurfaceView): there is no GL renderer below
        //   us. The MTKView clears to opaque black (cocoa_bridge.mm
        //   MTLClearColorMake(0,0,0,1)). If we use a transparent
        //   canvas clear, the user sees solid black with imperceptible
        //   antialiased text glyphs alpha-blended over it -- visually
        //   indistinguishable from "the app didn't render" even
        //   though Buttons / TextViews are painting correctly.
        //   Fix: use rootView.getBackgroundColor() when set, else an
        //   opaque white default (matching Material Theme.Light /
        //   Theme.AppCompat.Light windowBackground convention). This
        //   makes the View hierarchy actually visible against a
        //   readable backdrop.
        //
        // The detection is per-frame so a late-attached SurfaceView
        // (engines that wire up GLSurfaceView inside onResume, not
        // onCreate) switches the clear color on the very next tick.
        // Tree walk is O(N) where N is the View count (typically
        // 10-100), trivially cheap at 60 fps.
        boolean hasGl = treeHasSurfaceView(rootView);
        int clearArgb;
        final boolean macapkClearTrace = ENV_MACAPK_TRACE_FULLFILL != null;
        if (hasGl) {
            clearArgb = 0x00000000;  // transparent overlay
        } else {
            int bg = rootView.getBackgroundColor();
            if ((bg & 0xFF000000) != 0) {
                clearArgb = bg;
            } else {
                // v1.0.131: resolve the APK's declared
                // `android:colorBackground` from the theme cascade.
                // F-Droid declares it as `@color/md_theme_background`
                // = #f9f9ff (light lavender). Without this resolution
                // we cleared to opaque white, which is INDISTINGUISHABLE
                // from the Material3 elevated CardView fill
                // (colorSurfaceContainerHigh = #e6e8f0) on visual
                // contrast -- the user saw cards on white = "no card
                // backgrounds". Routing through the theme makes the
                // activity bg slightly different from the card fill
                // so chrome shows as distinct rounded shapes.
                // Generic: any Material3 APK gets its declared bg;
                // pre-Material3 or unthemed APKs fall back to white.
                //
                // v1.0.898, TWO corrections, both measured on NewPipe where this
                // one clear was 695,763 px, the largest single term left in its
                // divergence.
                //
                // (1) WRONG ATTRIBUTE. AOSP's decor background is
                // `android:windowBackground`, not `colorBackground`.
                // PhoneWindow.generateLayout reads
                // R.styleable.Window_windowBackground and installs it on the
                // DecorView; `colorBackground` is a THEME PALETTE role that
                // widgets consult, and on many themes the two differ. NewPipe
                // declares windowBackground on both of its real themes
                // (@color/light_background_color #eeeeee, dark #222222) and
                // never declares colorBackground at all, so we fell through to
                // the platform default @android:color/background_material_light
                // #fafafa. Under the first-run dialog's 0.6 dim that painted
                // #646464 where the emulator paints #5f5f5f.
                //
                // (2) WRONG ANCHOR. macapkThemeColor resolves against the STATIC
                // application theme, i.e. the MANIFEST theme, which for NewPipe
                // is the splash theme `OpeningTheme` and for 5 of the 15 fleet
                // apps is likewise a launch theme the app replaces at runtime
                // (docs/theme-anchor-and-viewinflater-census_2026-08-08.md).
                // rootView's own Context carries the theme the Activity actually
                // set, which is the one AOSP would resolve against.
                //
                // Order is AOSP's: windowBackground, then colorBackground as the
                // pre-existing behaviour, then white. A windowBackground that is
                // a DRAWABLE (OpeningTheme's @drawable/splash_background) is not
                // a colour, so the resolver declines it and we fall through
                // rather than inventing one.
                clearArgb = macapkDecorBackground(rootView);
            }
        }
        // The window backdrop a transparent WebView composites over begins with
        // this clear (WebView.macapkWindowBackdrop, MACAPK_WEBVIEW_BACKDROP).
        java.lang.ref.WeakReference<android.view.View> lcr = sLastClearRoot;
        if (lcr == null || lcr.get() != rootView) sLastClearRoot = new java.lang.ref.WeakReference<>(rootView);
        sLastClearArgb = clearArgb;
        if (macapkClearTrace) {
            // One-shot: which branch chose the frame clear, and what it chose. A sibling analysis
            // found Now in Android's frame cleared to OPAQUE BLACK although macapkDecorBackground
            // falls through to WHITE -- meaning rootView.getBackgroundColor() short-circuited the
            // theme resolver at :654-657. This line says which branch won, in one run.
            if (!sClearTraced) {
                sClearTraced = true;
                NativeLog.i(TAG, "[clear] hasGl=" + hasGl
                    + " rootBg=" + Integer.toHexString(rootView.getBackgroundColor())
                    + " decorBg=" + Integer.toHexString(macapkDecorBackground(rootView))
                    + " -> clearArgb=" + Integer.toHexString(clearArgb));
            }
        }
        // FRAMESPEED S2: decide, BEFORE the clear, whether this frame redraws
        // only S1's damage rect (see the FRAMESPEED_DAMAGE block for the full
        // trigger list). The clip goes on first so the clear, the draw, the
        // overlays and the system chrome all respect it, as AOSP's
        // lockCanvas(dirty) makes them. The chrome key folds together every
        // input of the frame that lies OUTSIDE the view tree's damage.
        boolean partial = false;
        int partialSave = -1;
        if (FRAMESPEED_DAMAGE) {
            final int sbH = StatusBarDriver.heightPx(rootView);
            long key = surfaceW;
            key = key * 31 + surfaceH;
            key = key * 31 + (hasGl ? 1 : 0);
            key = key * 31 + clearArgb;
            key = key * 31 + sbH;
            key = key * 31 + (sbH > 0 ? System.currentTimeMillis() / 60_000L : 0);
            key = key * 31 + StatusBarDriver.contentInsetBottomPx(rootView);
            key = key * 31 + StatusBarDriver.contentInsetLeftPx(rootView);
            key = key * 31 + StatusBarDriver.contentInsetRightPx(rootView);
            key = key * 31 + Boot.macapkLetterboxHeight(surfaceW, surfaceH);
            long video = 0;
            try { video = android.view.SurfaceView.macapkActiveVideoRectPacked(); } catch (Throwable ignored) {}
            key = key * 31 + video;
            final boolean overlayNow = !WindowOverlayStack.isEmpty();
            partial = macapkPartialRect(!doLayout && (frameKind == FK_DRAW || frameKind == FK_WAKE),
                    dmgKind, dmgRect, presentForceFull || damageForceFullNext,
                    key, lastChromeKey, overlayNow, lastOverlayNonEmpty,
                    surfaceW, surfaceH, sbH, partialRect);
            lastChromeKey = key;
            lastOverlayNonEmpty = overlayNow;
            damageForceFullNext = false;
            // The validator draws fully and only CLAIMS the rect (present bound).
            if (partial && !DAMAGE_VALIDATE) {
                partialSave = canvas.save();
                canvas.macapkClipChildRect(partialRect[0], partialRect[1],
                                           partialRect[2], partialRect[3]);
            }
        }
        canvas.drawColor(clearArgb);
        // v1.0.874: the LETTERBOX REMAINDER is black, not the window background.
        // AOSP letterboxes a legacy activity against the letterbox background
        // (SystemBarUtils / LetterboxConfiguration default, solid black), NOT
        // against the app's own colorBackground -- the strip is outside the
        // activity's bounds entirely, so the activity's theme has no say in it.
        // Measured on frozenbubble: the emulator paints #000000 across the
        // 296px-tall remainder while we were clearing it to the theme's #303030,
        // and at 239,168 px that single band was the largest term left in its
        // divergence after the letterbox itself landed.
        // No-op for every uncapped app -- macapkLetterboxHeight returns surfaceH.
        {
            int lbTop = Boot.macapkLetterboxHeight(surfaceW, surfaceH);
            if (lbTop < surfaceH) {
                android.graphics.Paint lb = new android.graphics.Paint();
                lb.setColor(0xFF000000);
                canvas.drawRect(new android.graphics.Rect(0, lbTop, surfaceW, surfaceH), lb);
            }
        }
        // v1.0.134: status-bar inset. The rootView's top edge sits at
        // statusH (not 0), so the activity's first child (e.g. F-Droid's
        // threat banner) lays out below the system bar instead of behind
        // it. The status bar itself is painted AFTER rootView.draw so
        // it overlays anything the app might (incorrectly) try to draw
        // over the top strip -- AOSP-correct because SystemUI owns the
        // bar, not the Activity.
        final int statusBarH = StatusBarDriver.heightPx(rootView);
        // v1.0.865 (owner call): AOSP-FAITHFUL bottom inset, replacing the
        // unconditional edge-to-edge of 2026-06-14.
        //
        // Real Android reserves the system-bar space by DEFAULT and only lets
        // the app draw behind the bars when it asks -- `WindowCompat
        // .setDecorFitsSystemWindows(window, false)`, i.e. what
        // `EdgeToEdge.enable()` calls. Laying every app out to the full surface
        // height made us unconditionally edge-to-edge, so every BOTTOM-ANCHORED
        // layout sat ~48px lower than the emulator's. Measured on anki, whose
        // whole screen is one bottom-anchored ConstraintLayout chain: our
        // solver satisfied every constraint exactly, but the chain's ANCHOR
        // (parent.bottom) was 48px too low, so the error propagated all the way
        // up through the logo and the 0dp-height gradient above it.
        //
        // So: honour the app's own declaration. Default (decor fits) reserves
        // the strip; an app that opted into edge-to-edge still gets the full
        // height. The top status bar remains a push-down inset as before.
        // v1.0.996: one accessor, shared with the measure/layout pass above and
        // with the ANGLE render layer's geometry -- see contentInsetBottomPx.
        final int navInsetH = StatusBarDriver.contentInsetBottomPx(rootView);
        // v1.0.997: the same inset can land on a SIDE edge instead. AOSP's
        // DisplayPolicy.navigationBarPosition moves the bar to the display's
        // right in landscape on a phone-class display, and takes it out of the
        // app's width there (dockFrame.right = mRestricted.right = left,
        // DisplayPolicy.java:1859-1877, android11-release). Reserving it at the
        // bottom instead cost a rotated video 96px of scarce HEIGHT.
        final int navInsetL = StatusBarDriver.contentInsetLeftPx(rootView);
        final int navInsetR = StatusBarDriver.contentInsetRightPx(rootView);
        // v1.0.874: AOSP's pre-O max-aspect letterbox, applied at the SINGLE
        // source that feeds both the per-frame measure below and both
        // rootView.layout() calls -- so measure and layout cannot disagree,
        // which is the trap the v1.0.865 bottom-inset work recorded.
        //
        // A legacy activity (targetSdk < 24 with no resizeableActivity opt-in)
        // is capped to 1.86 by the platform and the remainder is background; we
        // were handing it the whole tall window, so any layout anchored to the
        // window's centre or bottom sat too low by half the excess. frozenbubble
        // (targetSdk 12) centres 1077px of art and landed 122px below the
        // emulator. Boot.macapkLetterboxHeight is a no-op for every app that is
        // not capped, which is all but one of the 15-app fleet.
        // v1.0.891: INTERSECT the nav bar with the window frame, do not SUBTRACT
        // it from the letterbox height. AOSP computes a window inset as the
        // overlap of the bar's frame with the WINDOW's frame
        // (InsetsSource.calculateInsets / WindowState.getInsetsState), so a
        // letterboxed window that ends above the bar has a bottom inset of ZERO.
        // We were subtracting unconditionally, which for a capped app carved the
        // nav-bar height out of the letterboxed content a second time: frozen-
        // bubble's window ends at y=1503 while the nav bar occupies [1700,1796),
        // they do not touch, and yet contentBottom became 1503-96=1407 -- leaving
        // rows 1407..1502 (77,568px) showing the window clear colour instead of
        // the app, and shifting its centred 1077px of art up by 48px.
        // ZERO BLAST RADIUS on an uncapped app, which is 14 of the 15 fleet
        // pairs: there lb == surfaceH, so navTop == surfaceH - navInsetH and the
        // overlap is exactly navInsetH -- byte-identical to the old subtraction.
        final int lbHeight = Boot.macapkLetterboxHeight(surfaceW, surfaceH);
        final int navTop = surfaceH - navInsetH;
        final int contentBottom = lbHeight - Math.max(0, lbHeight - navTop);
        // The horizontal pair, derived ONCE for the measure and both layouts
        // below -- the same discipline the height's own comment insists on.
        // macapkLetterboxHeight is inert here: it returns heightPx unchanged
        // whenever widthPx >= heightPx (Boot.java), i.e. in landscape, which is
        // the only orientation where these are non-zero.
        final int contentLeft  = navInsetL;
        final int contentRight = surfaceW - navInsetR;
        // v1.0.76: re-layout the view tree every frame. AppCompat's
        // setContentView happens AFTER surfaceChanged installed the
        // initial layout, so the activity_layout's children (Buttons,
        // TextViews) get appended AFTER the initial layout pass. Re-
        // running layout every frame keeps newly-added children
        // positioned. Cheap (just int writes) and lets the simple
        // FrameLayout-style ViewGroup.onLayout fan out parent bounds
        // to children that arrived after first layout.
        // v1.0.99: drain pending Handler.post runnables every frame
        // so multi-step delayed-action chains can progress. Without
        // this, an Activity's onCreate.post -> handler.post chain
        // would stall after the first runnable runs in
        // ActivityHost.start's drainGlobal call. Frozen Bubble's
        // splash -> menu transition is driven by such a chain.
        // Tier 2 M2: in REAL_LOOPER mode the loop OWNS the main queue (this
        // doFrame already runs ON the loop), and GLOBAL_QUEUE then holds only
        // GL-thread queueEvent work that must run on the OS main thread -- so
        // do NOT drain it here. Off-mode: drain the historical per-frame main
        // queue on the OS main thread, exactly as before.
        if (!android.os.Looper.MACAPK_REAL_LOOPER) {
            try { android.os.Handler.drainGlobal(); } catch (Throwable ignored) {}
        }
        // Window-attach the root tree (idempotent fast path). Real
        // Android attaches via ViewRootImpl once the window exists --
        // for us that's "the canvas pump is running". Views inflated
        // later join an attached tree and attach via addView.
        try { rootView.macapkDispatchAttachedToWindow(); }
        catch (Throwable t) { com.macapk.loader.GfxTrace.swallow("dispatchAttachedToWindow", t); }
        // Scroll-perf: run the whole measure+layout pass (and the per-frame RV
        // measure pump / FAB + nav fixups nested in it) ONLY on a FULL traversal.
        // On a DRAW-ONLY frame (scroll/animation invalidate, no requestLayout) the
        // tree geometry is unchanged since the last full traversal, so we skip
        // straight to draw and reuse it -- mirroring AOSP performTraversals, which
        // gates performMeasure/performLayout on mLayoutRequested. `if (doLayout)
        // try {...}` keeps the existing try/catch as the guarded statement.
        if (PHASE_TRACE) pt2 = System.nanoTime();   // prep = M6 gate + bitmap + clear + letterbox + attach
        if (doLayout) try {
            // v1.0.97: run an actual measure pass before layout. AOSP-shape
            // measure-then-layout cycle is what RecyclerView (and many
            // other custom ViewGroups) expect. Without it,
            // RecyclerView.onMeasure never runs and the LayoutManager's
            // state machine doesn't initialize; setAdapter then doesn't
            // trigger onCreateViewHolder calls on the next frame. We
            // construct a MeasureSpec for EXACTLY the surface size and
            // forward through View.measure (polymorphic into
            // RecyclerView.onMeasure when it's the host of the chain).
            //
            // Full-tree measure pass (mirrors ViewRootImpl.
            // performTraversals: performMeasure with EXACTLY window
            // specs, THEN performLayout -- performMeasure's body is
            // `mView.measure(childWidthMeasureSpec,
            // childHeightMeasureSpec)`). The app's own ViewGroups run
            // their real onMeasure here; ConstraintLayout/MotionLayout
            // in particular run their constraint SOLVER in onMeasure
            // and their onLayout only copies solved widget positions.
            // Without this pass Calculator's keypad rows (4-button
            // spread_inside chains) never solved and fell to the base
            // equal-divide heuristic (rendered as 2x3 blocks of 2x2).
            // Guarded separately: a throwing app onMeasure must not
            // abort the layout/draw pipeline.
            try {
                // RTL-resolve the WHOLE subtree BEFORE measuring (the
                // v1.0.123 lesson from the RV path): ConstraintLayout's
                // setChildrenConstraints reads each child LayoutParams'
                // mResolved* anchor fields, which only populate after
                // LayoutParams.resolveLayoutDirection. Our View.measure
                // resolves the view BEING measured, but CL reads its
                // CHILDREN's params before measuring them -- so without
                // this pre-pass every start/end constraint stays UNSET
                // and the solver zeros the whole keypad. Idempotent per
                // view (isLayoutDirectionResolved short-circuits).
                if (!"0".equals(ENV_MACAPK_RTL_RESOLVE)) {
                    resolveTreeRtl(rootView);
                }
                rootView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        // v1.0.997: the window's width, not the display's --
                        // same reason as the height just below.
                        Math.max(0, contentRight - contentLeft),
                        android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        // v1.0.865: MUST match the height rootView.layout()
                        // actually gets (contentBottom - statusBarH), not the
                        // full surface. Measuring against a taller parent than
                        // the one laid out leaves every bottom-anchored child
                        // solved for the old height -- the reserved nav inset
                        // then changes the root's bounds and nothing inside
                        // moves, which is exactly what the first attempt did.
                        contentBottom - statusBarH,
                        android.view.View.MeasureSpec.EXACTLY));
            } catch (Throwable measureErr) {
                if (!warnedMeasureErr) {
                    warnedMeasureErr = true;
                    java.io.StringWriter sw = new java.io.StringWriter();
                    measureErr.printStackTrace(new java.io.PrintWriter(sw));
                    NativeLog.i(TAG, "measure-pass threw " + measureErr
                        + "\n" + sw);
                }
            }
            // M15 (F-Droid layout fix): clamp the Material nav bar's measured
            // height to the design nav height BEFORE layout. The bundled
            // BottomNavigationMenuView.onMeasure does setMeasuredDimension(w,
            // parentHeight) -- it FILLS its given height -- and NavigationBarView
            // has no onMeasure (plain FrameLayout), so a wrap_content bar measures
            // to the FULL available height. RelativeLayout then faithfully honors
            // that: the bar (alignParentBottom) fills the whole parent and the
            // layout_above app-list RecyclerView collapses to height 0 (F-Droid's
            // "nav floats mid-screen, huge empty area" bug). Clamping the bar (+
            // its MenuView + items) to design_bottom_navigation height here lets
            // RelativeLayout lay out the tree correctly -- bar pinned bottom, list
            // filling above -- via the real rules, no position hack needed. Gated
            // on the real-loop flag; MACAPK_NAV_MEASURE_CLAMP=0 to disable.
            if (android.os.Looper.MACAPK_REAL_LOOPER
                    && !"0".equals(ENV_MACAPK_NAV_MEASURE_CLAMP)) {
                try { clampNavBarMeasuredHeight(rootView); } catch (Throwable ignored) {}
            }
            rootView.layout(contentLeft, statusBarH, contentRight, contentBottom);
            // v1.0.97: now that layout has set every View's bounds,
            // selectively measure RecyclerViews against their
            // post-layout extent. RecyclerView.onMeasure owns the
            // adapter-binding pump (dispatchLayoutStep1+2 -> LM
            // .onLayoutChildren -> onCreateViewHolder /
            // onBindViewHolder). We measure ONLY RecyclerViews to
            // avoid regressing BottomNav's already-correct child
            // sizing (which doesn't need a measure pass to display).
            measureRecyclerViews(rootView);
            // After RV measure, a fresh layout call so RV's
            // post-measure layout-state takes effect.
            rootView.layout(contentLeft, statusBarH, contentRight, contentBottom);
            // The window's decor is laid out at the WINDOW's size with the
            // root, as ViewRootImpl lays out a device's decor (see
            // layoutDetachedDecor and Activity.macapkLayoutDetachedDecor,
            // MACAPK_DECOR_WINDOW_LAYOUT).
            // GATE-MUTANT(touchinput:decor-call) begin -- debug/touchinput_gate.sh
            // deletes exactly this call to prove W2 sees the production wiring.
            layoutDetachedDecor(ActivityHost.peek(), rootView, contentLeft, contentRight,
                                contentBottom);
            // GATE-MUTANT(touchinput:decor-call) end
            // One-shot self-check for the getRootWindowInsets co-requisite
            // (docs/root-window-insets-spec_2026-08-21.md). No fleet app calls
            // getWindowVisibleDisplayFrame in a PASSIVE run, so the double-count
            // it guards against is latent rather than observable -- which is
            // exactly the kind of thing that ships broken. Exercise the real
            // method against the real root view once, under the trace flag only.
            if (!sLoggedVisFrameCheck && ENV_MACAPK_TRACE_INSETS != null) {
                sLoggedVisFrameCheck = true;
                try {
                    android.graphics.Rect r = new android.graphics.Rect();
                    rootView.getWindowVisibleDisplayFrame(r);
                    android.view.WindowInsets wi0 = rootView.getRootWindowInsets();
                    android.graphics.Insets sysb = (wi0 != null) ? wi0.getInsets(
                        android.view.WindowInsets.Type.systemBars()) : null;
                    NativeLog.i(TAG, "INSETS SELF-CHECK realH="
                        + android.view.Display.macapkRealHeightPx()
                        + " systemBars=" + sysb
                        + " statusBarDriver.heightPx=" + StatusBarDriver.heightPx(null)
                        + " navBarInsetBottomPx=" + StatusBarDriver.navBarInsetBottomPx(null)
                        + " navDecorHeightPx=" + android.view.Display.macapkNavDecorHeightPx()
                        + " dm.heightPixels=" + rootView.getResources().getDisplayMetrics().heightPixels
                        + " contentBottom=" + contentBottom + " statusBarH=" + statusBarH);
                    NativeLog.i(TAG, "INSETS SELF-CHECK visibleFrame.h=" + r.height()
                        + " rootView.h=" + rootView.getHeight()
                        + " keyboardProbe=" + (rootView.getHeight() - r.height())
                        + "  (0 = correct; +96 = the nav double-count)");
                } catch (Throwable t) {
                    NativeLog.i(TAG, "INSETS SELF-CHECK threw: " + t);
                }
            }
            // v1.0.137: dispatch system-bar WindowInsets AFTER the
            // first layout pass so the OnApplyWindowInsetsListeners
            // see real bounds. The activity already excludes the
            // system-nav strip via the reduced rootView.bottom (above),
            // so the bottom inset is 0 from the bar's perspective --
            // Material's BottomNavigationView listener picks up 0 and
            // doesn't grow its paddingBottom. The dispatch still
            // matters for Compose / CoordinatorLayout listeners that
            // read the top inset for status-bar avoidance.
            try {
                if (rootView.macapkConsumeInsetsDispatchPending()) {
                    // v1.0.139: dispatch ZERO insets (the rootView
                    // already excludes the system-bar regions via its
                    // top/bottom layout insets; CoordinatorLayout
                    // would otherwise double-pad).
                    //
                    // v1.0.402: a fitsSystemWindows CollapsingToolbar /
                    // AppBarLayout (F-Droid's AppDetails hero) expects the
                    // REAL top inset so it can GROW its height by the
                    // status-bar height and bleed the feature graphic
                    // edge-to-edge under the bar -- exactly what the
                    // emulator shows. Real Android dispatches the live
                    // inset; Material AppBarLayout.onApplyWindowInsets adds
                    // getTopInset() to the appbar height + the
                    // CollapsingToolbarLayout pins the toolbar below the
                    // status area. Dispatching the real top inset shifts the
                    // scrolling content down by the bar height, aligning the
                    // whole AppDetails card/description with the emulator.
                    // The bottom stays 0 (the bottom is already edge-to-edge
                    // overlay, owner pick 2026-06-14). Env rollback to the
                    // v1.0.139 all-zero behavior: MACAPK_DISPATCH_TOP_INSET=0.
                    // Default OFF: dispatching the real top inset proved a
                    // no-op for F-Droid (our Material AppBarLayout inset
                    // machinery does not grow the appbar from it), so it adds
                    // risk without benefit. Opt in with MACAPK_DISPATCH_TOP_INSET=1.
                    // PERF1: reuse the already-computed statusBarH (same rootView,
                    // same frame, StatusBarDriver.heightPx is a pure function of
                    // display density) instead of a second StatusBarDriver.heightPx
                    // call. Dormant by default (MACAPK_DISPATCH_TOP_INSET=0), so this
                    // only matters when that diagnostic flag is opted in.
                    int topInset = "1".equals(ENV_MACAPK_DISPATCH_TOP_INSET)
                        ? statusBarH : 0;
                    android.graphics.Insets sys = (topInset > 0)
                        ? android.graphics.Insets.of(0, topInset, 0, 0)
                        : android.graphics.Insets.NONE;
                    android.view.WindowInsets sysInsets =
                        new android.view.WindowInsets.Builder()
                            .setSystemWindowInsets(sys)
                            .setStableInsets(sys)
                            .build();
                    sInsetDispatchAttempted++;
                    rootView.dispatchApplyWindowInsets(sysInsets);
                    sInsetDispatchDelivered++;
                    // Emit the proof ONCE, on the first delivery. A counter
                    // nobody prints is not an instrument: the whole reason
                    // this exists is that a harness must be able to tell
                    // "dispatched zeros" from "threw before dispatching", and
                    // it can only do that from the log.
                    if (sInsetDispatchDelivered == 1) {
                        NativeLog.i(TAG, macapkInsetDispatchStats());
                    }
                }
            } catch (Throwable t) {
                // ⚠ WAS `catch (Throwable ignored) {}` UNTIL 2026-08-21, and that
                // is the single most dangerous line in the inset pipeline: a
                // throw here produces a BYTE-IDENTICAL frame, so the failure
                // mode and the pass criterion of every inset change are THE SAME
                // OBSERVATION. Every window-model design reviewed for the
                // API-level campaign pre-registered "no pixels moved" as its
                // gate, and all of them would have passed while throwing here.
                // A screenshot cannot distinguish "dispatched zeros" from "threw
                // before dispatching"; the counters below can.
                //
                // Logged ONCE per distinct throwable class, because this runs
                // per frame and an unbounded log would be its own bug.
                String k = t.getClass().getName();
                if (sInsetDispatchSeen.add(k)) {
                    NativeLog.w(TAG, "inset dispatch THREW (" + k + "): " + t
                        + " -- attempted=" + sInsetDispatchAttempted
                        + " delivered=" + sInsetDispatchDelivered);
                }
            }
            // v1.0.143: re-enable M3 BottomNavigationView container
            // height = 80dp. v1.0.140 disabled this because poking
            // the bar's measuredHeight alone left BottomNavigation-
            // MenuView at 56dp -- items got cropped to the bottom
            // 56dp of an 80dp bar. The v1.0.143 implementation pokes
            // measuredHeight + setLeftTopRightBottom on the bar AND
            // the MenuView AND each item, so the whole chain grows
            // together. Per-frame call; each invocation is idempotent
            // (setFrame short-circuits when bounds match).
            if (hackEnabled("navbar")) enforceM3NavigationBarHeight(rootView);
            // v1.0.76: subclass-onLayout overrides (e.g. ConstraintLayout
            // in the APK) skip our base-ViewGroup.onLayout's "fill
            // parent" default. If they then fail to position their own
            // children (because they need a proper measure pass that
            // we don't provide), the children stay at (0,0,0,0). As a
            // fallback, walk the tree and force-fill any zero-extent
            // child to its parent's bounds. Children laid out properly
            // by their parent are left alone.
            // v1.0.110: enforce tab-pager single-page visibility EVERY
            // frame. F-Droid's MainViewAdapter binds each of 5 page
            // FrameLayouts at force-bind time, then async LiveData
            // observers / lifecycle callbacks can flip non-Latest pages
            // back to VISIBLE later (CategoriesViewBinder fires a
            // LiveData observer on first data load that re-attaches its
            // root, defeating the one-shot GONE we set during bind).
            // Re-enforcing per frame is cheap (~5 ints/frame) and
            // generic: any RV with 2-5 children of distinct view types
            // is treated as a tab pager, with item N selected by
            // MACAPK_RV_VISIBLE_INDEX (default 0).
            if (hackEnabled("tabpager")) enforceTabPagerVisibility(rootView);
            // v1.0.111: bypass Glide's coroutine-based HttpFetcher
            // for ImageViews whose Glide load is stuck (the StandaloneCoroutine
            // launched on Dispatchers.IO never dispatches in our env).
            // Walks the tree, finds each ImageView with a Glide-tagged
            // pending REMOTE load, reflectively extracts the URL from
            // the Glide Request's model (F-Droid DownloadRequest =>
            // indexFile.name + first https mirror.baseUrl), fetches
            // bytes via HttpURLConnection, decodes via BitmapFactory,
            // and applies via setImageDrawable. Per-ImageView dedup
            // via IdentityHashMap so subsequent frames are no-op.
            try { GlideRescue.scanAndFetch(rootView); } catch (Throwable ignored) {}
            // v1.0.118: re-stack vertical LinearLayouts when GONE children
            // leave gaps. Uses setLeftTopRightBottom (NOT c.layout) on
            // children so no cascade triggers; for RecyclerView siblings
            // the cards stay positioned where layoutGridManually put them.
            if (hackEnabled("linfix")) fixupVerticalLinearLayouts(rootView);
            if (hackEnabled("zeroext")) forceLayoutZeroExtentChildren(rootView);
            // v1.0.101: gravity-based positioning pass. Walks the tree
            // for any child whose LayoutParams.macapkGravity is set
            // (FAB at bottom|end, SnackBar at bottom, dialog content
            // at center, etc.) AND whose current bounds fill the
            // parent (a sign the parent's onLayout didn't honor
            // gravity). Re-positions using the child's intrinsic /
            // declared size + parent's bounds + margins. Also applies
            // a Material-default BOTTOM|END to any FAB whose layout
            // _gravity wasn't explicitly set (Material's
            // CoordinatorLayout.Behavior contract). Generic for all
            // CoordinatorLayout / FrameLayout / custom-ViewGroup-from-
            // APK trees -- no per-app code.
            if (hackEnabled("gravity")) applyLayoutGravity(rootView);
            // v1.0.395: on the real-loop path the broad `gravity` hack is
            // retired (the real measure/layout handles most cases), but our
            // CoordinatorLayout doesn't run Material's FAB Behavior, so a
            // FloatingActionButton anchored in it lays out at [0,0-0,0] and
            // is clipped away (the owner's "missing search icon", 2026-06-14).
            // Run a FOCUSED FAB-only pass that sizes a degenerate / parent-
            // filling FAB to its Material default (56dp) and anchors it at
            // its layout_gravity (BOTTOM|END default + 16dp margin), matching
            // the FAB Behavior contract. Generic; no per-APK code.
            else if (android.os.Looper.MACAPK_REAL_LOOPER) positionFloatingActionButtons(rootView);
            // v1.0.402: grow a fitsSystemWindows AppBarLayout (F-Droid's
            // AppDetails CollapsingToolbar hero) by the status-bar height,
            // mirroring AOSP CollapsingToolbarLayout.onMeasure which adds the
            // top window inset to its height. Our Material inset machinery
            // doesn't drive that (dispatching the inset is a no-op), so the
            // appbar stayed 1 status-bar shorter than the emulator and every
            // detail row sat ~24dp too high. This shifts the scrolling
            // content down to match. Gated on the fitsSystemWindows AppBar in
            // a CoordinatorLayout, so non-edge-to-edge apps (games,
            // Calculator) are untouched. Env rollback MACAPK_GROW_FITS_APPBAR=0.
            if (android.os.Looper.MACAPK_REAL_LOOPER
                    && !"0".equals(ENV_MACAPK_GROW_FITS_APPBAR)) {
                try { growFitsSystemWindowsAppBar(rootView); } catch (Throwable ignored) {}
            }
        } catch (Throwable layoutErr) {
            if (!warnedLayoutErr) {
                warnedLayoutErr = true;
                java.io.StringWriter sw = new java.io.StringWriter();
                layoutErr.printStackTrace(new java.io.PrintWriter(sw));
                NativeLog.i(TAG, "layout-block threw " + layoutErr + "\n" + sw.toString());
            }
        }
        // Diagnostic dump of the rootView tree on early frames so we can
        // see whether children have non-zero bounds. MACAPK_DUMP_TREE_EVERY=<N>
        // also dumps every N frames (for inspecting screens reached after boot,
        // e.g. AppDetails after a tap).
        boolean dumpEvery = false;
        try {
            String de = ENV_MACAPK_DUMP_TREE_EVERY;
            if (de != null) { long iv = Long.parseLong(de); dumpEvery = iv > 0 && (n % iv) == 0; }
        } catch (Throwable ignored) {}
        if (n == 1 || n == 60 || n == 300 || dumpEvery) {
            try { dumpTree(rootView, 0, new StringBuilder()); }
            catch (Throwable ignored) {}
            // The harness text dump (uiautomator-shaped labels, absolute clipped
            // bounds, floating windows, WebView page text) debug/drive_app.sh taps
            // by: docs/harness-text-dump_2026-09-26.md. Returns at once unless
            // MACAPK_DUMP_TREE_EVERY is set and MACAPK_TEXT_DUMP is not 0.
            try { ViewTextDump.dumpToLog(rootView, statusBarH); }
            catch (Throwable ignored) {}
            if ("1".equals(ENV_MACAPK_TRACE_BARRIER)) {
                try { dumpBarriers(rootView); } catch (Throwable ignored) {}
            }
            // Widget-graph dump for ANY ConstraintLayout in the tree
            // (not just RV cards) -- same env gate as the RV-path dump.
            // Gate widened v1.0.836. It used to fire ONLY on n == 60, which makes
            // the dump useless for the exact bug it exists to diagnose: an app
            // whose fragment (and therefore its ConstraintLayout) attaches long
            // after frame 60 never gets dumped at all. Wikipedia is that case --
            // its ConstraintLayout runs onMeasure 210 times and never measures a
            // child, and this is the one diagnostic that would show whether the
            // solver's widget graph is empty, yet it could not fire.
            // Now: frame 60 as before, then every `dumpEvery` frames, so a late
            // attach is still caught without spamming a 60 fps log.
            // (`dumpEvery` is already the "this frame is a dump frame" boolean
            // computed from MACAPK_DUMP_EVERY above -- not a count.)
            if ((n == 60 || dumpEvery)
                    && ENV_MACAPK_DUMP_CL_WIDGETS != null) {
                try { dumpConstraintLayoutWidgets(rootView); }
                catch (Throwable ignored) {}
            }
            // MotionLayout scene probe (type-driven so it works on
            // R8-minified apps): for each CL SUBCLASS view, find the
            // field whose value owns a SparseArray (the MotionScene's
            // ConstraintSet map), and report each set's constraint-map
            // size. Tells us whether ConstraintSet.readFallback
            // populated the sets (size ~= child count) or they stayed
            // empty (the zero-size measure cascade).
            if (n == 60 && ENV_MACAPK_DUMP_MOTION != null) {
                try { dumpMotionSceneState(rootView); }
                catch (Throwable ignored) {}
            }
            if (ENV_MACAPK_MENU_TRACE != null) {
                try { dumpFragmentMenuState(rootView); }
                catch (Throwable ignored) {}
            }
        }
        // v1.0.101: Material 3 NavigationBarItemView active-indicator
        // pill. The selected tab in a BottomNavigationView should show
        // a rounded-rect background behind its icon (Material 3 design
        // token "active_indicator"). Material's NavigationBarItemView
        // tries to inflate a child View for this purpose, but in our
        // framework it stays vis=GONE because the activate-state
        // animation chain doesn't reach it. We overdraw the pill onto
        // the canvas BEFORE drawing the tree, so the icon paints on
        // top of the pill. Selected detection: NavigationBarItemView
        // ships TWO labels per item (one for active, one for inactive
        // typography), and the SELECTED item is the one whose second
        // label is VISIBLE while the first is INVISIBLE. Generic for
        // any Material 3 NavigationBar / BottomNavigation.
        // Ink accounting for the present-elision starts HERE: after the clear
        // and the letterbox strip (framework background, painted every frame,
        // so counting them would stop an empty tree from ever eliding) and
        // before the first pass that can put APP pixels on this canvas. The
        // decision is sampled after the LAST such pass (the window-overlay
        // loop), not after rootView.draw; see INK_AFTER_ALL_PASSES. The canvas
        // reference is held locally so a mid-frame surface reset (which nulls
        // the field) cannot NPE the sample or compare two different canvases.
        final android.graphics.Canvas inkCanvas = canvas;
        final int inkOpsFrameStart = inkCanvas.macapkDrawOps;
        try { paintNavSelectedPills(rootView, canvas, 0, 0,
                                    new java.util.IdentityHashMap<>()); }
        catch (Throwable ignored) {}
        // v1.0.134: BaselineLayout reposition pass for Material's
        // NavigationBarItemView. The Material widget contains an icon
        // + a BaselineLayout-wrapped pair of labels; without Material's
        // own measure-cascade running, the labels end up at negative-Y
        // and the bar shows icons only. We re-position icon at top and
        // BaselineLayout (with its label children) below the icon.
        // Class-name keyed, generic for any APK with a M3 bottom nav.
        try {
            if (hackEnabled("navitem")) fixupNavigationBarItemViews(rootView);
            // ⚠ centerNavItemContent GATED OFF by default v1.0.930. It was
            // written 2026-06-14 when the nav-item measure genuinely was wrong,
            // but its own header's RETIRE condition ("when the measure chain wraps
            // the bar/items to their natural design height so Material's own
            // onLayout centers the content") is now MET -- and it had never once
            // run against a real Material nav bar, because until v1.0.928
            // BottomNavigationView's constructor threw and we substituted a
            // FrameLayout placeholder, so there were no NavigationBarItemViews to
            // centre. Now that the bar really inflates it is actively CLOBBERING a
            // correct layout: traced on F-Droid, contentArea = max(iconH=64,
            // ih=160 - inset=96) = 64 < groupH=101, so groupTop clamps to 0 and the
            // icon+label group is jammed to the item's TOP. Material had already
            // laid the icon container out at [17,24-145,88] -- 12dp top, exactly
            // m3_bottom_nav_item_padding_top and exactly the emulator's geometry --
            // and we moved it to [.,0-.,64]. Census over one run: 950 frames
            // correct, 7,120 post-clobber. That is the owner's "ours just isn't
            // centered and theirs is".
            // MACAPK_KEEP_NAV_CENTER=1 restores it as a bisect gate.
            else if (android.os.Looper.MACAPK_REAL_LOOPER
                     && "1".equals(ENV_MACAPK_KEEP_NAV_CENTER))
                centerNavItemContent(rootView);
        }
        catch (Throwable ignored) {}
        if (PHASE_TRACE) pt3 = System.nanoTime();   // layout = measure + layout + RV pump (0-cost on draw-only)
        // v1.0.144: real fix lives in ComposeViewFallback.paint --
        // the threat-banner offset was a coordinate-frame bug in the
        // fallback's recursion (it double-counted rootView.getTop()
        // because the canvas was already translated to root's frame
        // before paint() was called). See ComposeViewFallback.java
        // for the per-call diagnostic. The two diagnostic walkers
        // below (zero-paddingTop + shiftTopComposeView) stayed in
        // tree for future cases where the real source is a
        // container's paddingTop -- both gated and idempotent so
        // they're free when nothing matches.
        // v1.0.144 diagnostic: at frame N, dump BaselineLayout bounds
        // RIGHT BEFORE draw to verify fixup's setLeftTopRightBottom
        // persists into paint time. Gated on MACAPK_TRACE_NAV=1.
        if ("1".equals(ENV_MACAPK_TRACE_NAV)
            && (n == 1 || n == 60 || n == 300)) {
            try { dumpBaselineLayoutBounds(rootView, n); }
            catch (Throwable ignored) {}
        }
        // AOSP performTraversals dispatches the ViewTreeObserver here: global-
        // layout after layout (once per traversal), then pre-draw immediately
        // before draw. AndroidComposeView flushes its AndroidView interop-layout
        // callbacks via dispatchOnGlobalLayout, and every AndroidX "measure then
        // act once" OnPreDrawListener fires here. v1.0.418. (peek = no allocation
        // when nothing registered; the observer is created lazily on first
        // add*Listener via some view's getViewTreeObserver walking to root.)
        android.view.ViewTreeObserver vto = rootView.macapkPeekTreeObserver();
        if (vto != null) {
            if (doLayout) { try { vto.dispatchOnGlobalLayout(); } catch (Throwable t) { com.macapk.loader.GfxTrace.swallow("dispatchOnGlobalLayout", t); } }
            // A false return means a listener wants the draw deferred until it
            // re-lays-out; such listeners call requestLayout/invalidate themselves
            // (which marks the tree dirty for next frame), so we still draw this
            // frame to avoid a blank and let convergence happen on the next.
            try { vto.dispatchOnPreDraw(); } catch (Throwable t) { com.macapk.loader.GfxTrace.swallow("dispatchOnPreDraw", t); }
        }
        try {
            // v1.0.134: translate the canvas DOWN by the status-bar
            // height so the rootView (whose top is statusBarH in
            // logical coords) draws starting at canvas y == statusBarH.
            // View.draw paints in LOCAL coords; the parent normally
            // does this translate via dispatchDraw, but the root has
            // no parent so we apply it here.
            canvas.save();
            canvas.translate(0, statusBarH);
            // v1.0.997 DIAGNOSTIC HOOK: publish the ROOT frame canvas so a draw
            // deep in the tree can ask "am I painting into the window surface, or
            // into an interposed offscreen that will be composited back SrcOver?"
            // That question decides whether a destination-clearing op (AOSP's
            // SurfaceView hole punch) can reach the frame at all, and it cannot
            // be answered from inside the draw any other way. Cleared in the
            // finally so a throwing draw cannot leave a stale reference behind.
            macapkRootCanvas = canvas;
            macapkPartialDraw = partialSave >= 0;   // FRAMESPEED S2 (see the field)
            final int opsBefore = canvas.macapkDrawOps;
            try {
                rootView.draw(canvas);
            } finally {
                macapkRootCanvas = null;
                macapkPartialDraw = false;
            }
            // Did this traversal put ANY ink on the frame? Counted as draw OPS,
            // deliberately -- not by scanning the pixels. A pixel scan of a 25 MB
            // frame costs more than the present it is trying to avoid, which
            // would defeat the whole optimisation.
            // Only the MACAPK_COUNT_ALL_INK=0 bisect path decides here; the
            // default decides after the window-overlay pass below.
            if (!INK_AFTER_ALL_PASSES) sFrameDrewInk = (canvas.macapkDrawOps != opsBefore);
            canvas.restore();
            // MACAPK_PUNCH_TRACE=1: does the SurfaceView hole survive the draw?
            // Sampled HERE, immediately after rootView.draw and before the
            // status-bar / nav-strip / overlay post-passes, so "the punch was
            // refilled by app content" and "a post-pass repainted over it" are
            // distinguishable instead of both reading as a fully opaque frame.
            if (PUNCH_TRACE && sPunchProbes < 40) {
                try {
                    long packed = android.view.SurfaceView.macapkActiveVideoRectPacked();
                    if (packed > 0L) {
                        int rx = (int) ((packed >> 48) & 0xffffL);
                        int ry = (int) ((packed >> 32) & 0xffffL);
                        int rw = (int) ((packed >> 16) & 0xffffL);
                        int rh = (int) (packed & 0xffffL);
                        java.awt.image.BufferedImage img = bitmap.macapkAwtImage();
                        if (img != null) {
                            int clear = 0, samples = 0;
                            for (int yy = ry; yy < Math.min(ry + rh, img.getHeight()); yy += 8)
                                for (int xx = rx; xx < Math.min(rx + rw, img.getWidth()); xx += 8) {
                                    samples++;
                                    if ((img.getRGB(xx, yy) >>> 24) == 0) clear++;
                                }
                            sPunchProbes++;
                            NativeLog.i(TAG, "punch probe #" + sPunchProbes
                                + " sinkRect=" + rx + "," + ry + " " + rw + "x" + rh
                                + " transparent=" + clear + "/" + samples);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (vto != null) { try { vto.dispatchOnDraw(); } catch (Throwable t) { com.macapk.loader.GfxTrace.swallow("dispatchOnDraw", t); } }
        } catch (Throwable t) {
            // A buggy onDraw shouldn't kill the renderer. Log and
            // continue with whatever was already painted. v1.0.121:
            // include the stack trace so the next session can see
            // WHERE the NPE / exception originates without re-adding
            // diagnostics.
            try { canvas.restoreToCount(1); } catch (Throwable ignored) {}
            damageForceFullNext = true;   // FRAMESPEED S2: recovery is a full repaint
            if (!ViewCanvasDriverState.warnedOnDrawCrash) {
                ViewCanvasDriverState.warnedOnDrawCrash = true;
                NativeLog.i(TAG, "rootView.draw threw " + t);
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                NativeLog.i(TAG, "rootView.draw stack:\n" + sw.toString());
            }
        }
        // v1.0.363: deliver window focus for a pure View/Compose tree.
        // On real Android EVERY shown activity receives
        // onWindowFocusChanged(true) once its window is focused; we only
        // delivered it from SurfaceView.surfaceCreated, so GL-less apps
        // (Compose / Conductor activities like Reddit) never got it.
        // Apps gate real work on it -- e.g. starting their initial
        // navigation / nav-host setup, resuming animators, kicking the
        // first data load. Gated on !hasGl so we NEVER deliver focus
        // before a GL surface exists (that deadlocks Unity -- see
        // ActivityHost.macapkDeliverWindowFocus). n>=2 so at least one
        // measure+layout+draw has completed (the window is really up).
        // ActivityHost dedups per-activity, so this fires effectively
        // once; the flag just avoids the per-frame call.
        if (!hasGl && !windowFocusDelivered && n >= 2) {
            windowFocusDelivered = true;
            try { com.macapk.loader.ActivityHost.macapkDeliverWindowFocus(); }
            catch (Throwable ignored) {}
        }
        // v1.0.134: ComposeView fallback. Walks the tree for
        // ComposeView instances whose Compose runtime didn't paint
        // (kotlinx-coroutines / Compose frame scheduler don't fully
        // drive in our env), and paints a banner-shaped fallback with
        // text harvested from the LayoutNode tree. The fallback paints
        // in the CANVAS's pre-translate space so it has to add
        // statusBarH to the y coordinate; we do that by issuing the
        // walk with the canvas still in its translated state.
        try {
            canvas.save();
            canvas.translate(0, statusBarH);
            ComposeViewFallback.paint(rootView, canvas);
            canvas.restore();
        } catch (Throwable cfe) {
            com.macapk.loader.GfxTrace.swallow("ComposeViewFallback.paint", cfe);
            try { canvas.restoreToCount(1); } catch (Throwable t) {}
        }
        // DIAGNOSTIC (MACAPK_TRACE_COMPOSE_FALLBACK=1): dump the view tree +
        // Compose LayoutNode tree every ~60 frames even under the real loop, to
        // see whether a Compose screen's ComposeView is attached + whether its
        // composition produced sized content nodes.
        if ((n % 20) == 10) {
            try { ComposeViewFallback.macapkDiagnoseTree(rootView); } catch (Throwable ignored2) {}
        }
        // v1.0.691: composite the floating-window overlay stack (Dialog /
        // PopupWindow / Spinner dropdown / PopupMenu) OVER the content view.
        // Each layer is measured+laid out at its WindowManager.LayoutParams
        // position, then drawn. This is what turns WindowManager.addView from a
        // no-op into a real floating window. Drawn after content + Compose
        // fallback, before the status bar (AOSP composites system UI on top of
        // application windows). Same status-bar translate as rootView.draw.
        if (!WindowOverlayStack.isEmpty()) {
            java.util.List<WindowOverlayStack.Layer> layers = WindowOverlayStack.snapshot();
            // v1.0.997: ...and its WIDTH excludes a side navigation bar for the
            // same reason. NAV_BAR_LEFT is unreachable (StatusBarDriver
            // .navBarPosition cannot answer ROTATION_270), so the left term is
            // always 0 and the content origin stays x=0; were that to change,
            // this canvas.translate and WindowOverlayStack.deliverTouch would
            // both need the matching x shift.
            int ow = surfaceW - StatusBarDriver.navBarInsetLeftPx(null)
                              - StatusBarDriver.navBarInsetRightPx(null);
            // v1.0.878: a FLOATING window is centred in the frame minus BOTH
            // system bars. AOSP's PhoneWindow.generateLayout applies
            // FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_INSET_DECOR only on the
            // NON-floating branch, and LayoutParams defaults mFitInsetsTypes to
            // Type.systemBars(), so a dialog's frame excludes the nav bar too.
            // We subtracted only the status bar, so every dialog sat half the
            // nav-bar height too low. Measured on newpipe: emulator panel centre
            // y=879, ours 921 -- and 48 + (1796-48-96-640)/2 predicts 874.
            int oh = surfaceH - statusBarH - StatusBarDriver.navBarInsetBottomPx(null);
            // ONE scrim, behind the topmost dimming layer (AOSP Dimmer holds a
            // single DimState; see WindowOverlayStack.topmostDimIndex).
            final int dimAt = WindowOverlayStack.topmostDimIndex(layers);
            for (int li = 0; li < layers.size(); li++) {
                WindowOverlayStack.Layer layer = layers.get(li);
                if (layer.view == null || layer.view.getVisibility() != android.view.View.VISIBLE) {
                    continue;
                }
                try {
                    WindowOverlayStack.measureAndLayout(layer, ow, oh);
                    canvas.save();
                    canvas.translate(0, statusBarH);
                    // FLAG_DIM_BEHIND: darken everything already painted, before
                    // this layer goes down. Covers the CONTENT area only (origin
                    // is already translated past the status bar), which is what a
                    // device does -- SystemUI's bar is not dimmed by an app's
                    // dialog.
                    //
                    // NOT canvas.drawColor(): every drawColor overload in our
                    // Canvas composites as AlphaComposite.Src ("overwrite, don't
                    // blend" -- Canvas.java:484), so a 0x99-alpha black through
                    // it would REPLACE the frame with translucent black and erase
                    // the content we are trying to dim. A Paint-ed rect takes the
                    // normal SrcOver path and actually blends.
                    int dim = (li == dimAt) ? WindowOverlayStack.dimColorFor(layer) : 0;
                    if (dim != 0) {
                        if (sDimPaint == null) {
                            sDimPaint = new android.graphics.Paint();
                            sDimPaint.setStyle(android.graphics.Paint.Style.FILL);
                        }
                        sDimPaint.setColor(dim);
                        canvas.drawRect(0, 0, ow, oh, sDimPaint);
                    }
                    // A layer's view.layout put it at absolute bounds; View.draw
                    // paints in the view's own local space, so translate to its
                    // top-left before drawing.
                    canvas.translate(layer.bounds.left, layer.bounds.top);
                    // The window's own shadow first, then its tree: a window
                    // root has no parent to cast its shadow, so the compositor
                    // does it (WindowOverlayStack.drawWindow; MACAPK_WINDOW_SHADOW
                    // =0 there is the bare view.draw this line used to be).
                    WindowOverlayStack.drawWindow(canvas, layer);
                    canvas.restore();
                } catch (Throwable ot) {
                    try { canvas.restoreToCount(1); } catch (Throwable ignored3) {}
                    com.macapk.loader.GfxTrace.swallow("overlay.draw", ot);
                }
            }
        }
        // An in-window drag's shadow, above every app window as the system's
        // drag surface is (android.view.MacApkDragSession; nothing unless a
        // drag started from THIS tree is in flight).
        try { android.view.MacApkDragSession.drawShadow(canvas, rootView); }
        catch (Throwable ignored) {}
        // PRESENT-ELISION INK SAMPLE (2026-09-25). This is the last pass that
        // draws APPLICATION content onto the frame canvas: the Material nav
        // pills, rootView.draw, ComposeViewFallback and every WindowManager
        // window (Dialog / PopupWindow / Toast, plus the FLAG_DIM_BEHIND scrim)
        // all land between inkOpsFrameStart and here. What follows is
        // diagnostics and the status bar / nav strip, which are system chrome
        // drawn on EVERY frame and must stay out of the count or no frame would
        // ever elide.
        //
        // Sampling right after rootView.draw (the old site, still used under
        // MACAPK_COUNT_ALL_INK=0) made overlay windows invisible to the
        // decision. Over an empty root tree (a native-EGL game, or Candy Crush
        // once its splash is removed) the frames of a newly shown Dialog read
        // ink=false while sLastPublishedInk was already false, so they were
        // elided and the dialog never appeared; and a dialog frame that did get
        // published stayed frozen on the layer after dismiss, because the next
        // empty frame elided too. Same failure as the Candy Crush splash, on the
        // overlay path.
        if (INK_AFTER_ALL_PASSES) sFrameDrewInk = (inkCanvas.macapkDrawOps != inkOpsFrameStart);
        // DIAGNOSTIC: per-frame global draw-op delta across ALL canvases (frame +
        // every RenderNode recording). Tells us if the Compose content issues ANY
        // draws this frame (0 => the composition produces no draw calls at all).
        if (ENV_MACAPK_TRACE_COMPOSE_FALLBACK != null && (n % 60) == 31) {
            long now = android.graphics.Canvas.sGlobalDrawOps;
            long delta = now - sPrevGlobalDrawOps;
            sPrevGlobalDrawOps = now;
            NativeLog.i("Mac.apk.DrawDelta", "frame#" + n + " draw-ops=" + delta
                + " text=" + android.graphics.Canvas.sGlobalText
                + " bitmap=" + android.graphics.Canvas.sGlobalBitmap
                + " path=" + android.graphics.Canvas.sGlobalPath
                + " roundRect=" + android.graphics.Canvas.sGlobalRoundRect);
        }
        // v1.0.134: status bar overlays the very top of the canvas
        // AFTER rootView.draw -- AOSP-correct because real SystemUI
        // composites over the Activity decor, never under it.
        // Sample the dominant color of the content row just below the status
        // bar so the bar can blend with it (a colored feature-graphic header
        // reads orange under the bar, like the emulator, instead of a flat
        // lavender strip). Done after rootView.draw so the bitmap holds the
        // painted content.
        // v1.0.997 rewire hardening (2026-08-18): app code dispatched above
        // (VTO listeners, onDraw, a Fragment transaction) can call
        // Activity.setContentView / the rewire demote path, whose
        // doSurfaceChanged nulls `bitmap`/`canvas` so the NEXT frame
        // reallocates at the new size. Before macapkSetOverlayDriver deferred
        // its surfaceChanged out of the current dispatch, that null landed
        // mid-frame and the present below NPE'd ("this.bitmap is null" --
        // caught by an independent lane on 2026-08-18). The defer removes the
        // known path; this guard makes ANY mid-frame surface reset a clean
        // skipped present instead of a thrown frame.
        if (bitmap == null || canvas == null) {
            if (!warnedMidFrameReset) {
                warnedMidFrameReset = true;
                NativeLog.i(TAG, "frame #" + n
                    + " aborted: surface changed mid-frame (bitmap reset); next frame reallocates"
                    + " [owner=" + android.os.Looper.macapkIsActivityThread()
                    + " tid=" + Thread.currentThread().getId() + "]");
            }
            return;
        }
        if (PHASE_TRACE) pt4 = System.nanoTime();   // draw = VTO dispatch + rootView.draw + window overlays
        int sbBgUnder = 0;
        try {
            // One row is read (sampleRowDominant), so the live backing serves it
            // with no copy. See the present's pixel read below for why.
            int[] sp = bitmap.macapkLiveArgbOrNull();
            if (sp == null) sp = bitmap.macapkArgbBuffer();
            if (sp != null) {
                sbBgUnder = sampleRowDominant(sp, bitmap.getWidth(),
                    bitmap.getHeight(), statusBarH + 2);
            }
        } catch (Throwable ignored) {}
        try { StatusBarDriver.paint(canvas, rootView, surfaceW, statusBarH, sbBgUnder); }
        catch (Throwable ignored) {}
        // v1.0.137: paint the bottom system-nav strip. Same AOSP
        // rationale: SystemUI owns the bottom bar area, drawing the
        // gesture-nav pill (or, on 3-button-nav devices, the back/
        // home/recents icons). We render a gesture-style pill --
        // generic across all themes via colorOnBackground.
        try { StatusBarDriver.paintSystemNavStrip(canvas, rootView, surfaceW, surfaceH); }
        catch (Throwable ignored) {}
        if (PHASE_TRACE) pt5 = System.nanoTime();   // bar = status-bar sample + paint + nav strip
        // Present fast path, step 1 (2026-09-25, P1.1 of
        // docs/canvas-present-cost-at-full-screen_2026-09-25.md): read the frame
        // bitmap's LIVE backing, not a copy. Bitmap.macapkArgbBuffer() bulk-
        // copied the whole surface, and this frame did it twice -- here and for
        // the one-row status-bar sample above -- two 24 MB copies per frame at
        // 3360x1796, both paid even when the present is then elided. Both reads
        // are synchronous on this thread before the next draw (presentBitmap
        // pins, copies what it needs and retains nothing, and nothing below
        // writes to the bitmap), so the bytes are identical. The bitmap always
        // has the stock layout (createBitmapWithCanvasBacking is TYPE_INT_ARGB);
        // null means the bisect gate MACAPK_PRESENT_LIVE_ARRAY=0 (in Bitmap) is
        // off, and the copy is the fallback.
        // FRAMESPEED S2: close the damage clip. If an error path already popped
        // it (a restoreToCount(1) after a throwing draw), the pixels outside the
        // rect may have been touched, so this frame is reported whole and the
        // next one repaints fully.
        boolean presentPartial = false;
        if (partial) {
            if (DAMAGE_VALIDATE) {
                presentPartial = true;   // drawn fully; the rect is only CLAIMED, and checked
            } else if (partialSave >= 0 && canvas.getSaveCount() > partialSave) {
                canvas.restoreToCount(partialSave);
                presentPartial = true;
            } else {
                damageForceFullNext = true;
            }
            if (presentPartial) {
                m6PartialCount++;
                m6PartialArea += (long) (partialRect[2] - partialRect[0]) * (partialRect[3] - partialRect[1]);
                // FRAMESPEED heartbeat: a partial frame is only as safe as its
                // damage, and the 30-vsync heartbeat is the design's bound on an
                // under-damage (08-30 section 6). Keep it there.
                hb.reset();
            }
        }
        int[] pixels = bitmap.macapkLiveArgbOrNull();
        if (pixels == null) pixels = bitmap.macapkArgbBuffer();
        long ckNs = 0;   // phase trace: checkpoint-block time, kept out of `present`
        if (pixels != null) {
            // Diagnostic: count any pixel that ISN'T fully transparent
            // (alpha=0). Previously this masked off alpha and only
            // counted RGB-nonzero pixels, which made fully-opaque BLACK
            // text on a transparent clear (the F-Droid Calculator
            // Activity case) report as 0% even though pixels were drawn.
            // We now also separately count fully-opaque pixels so the
            // diagnostic shows both "drawn at all" and "drawn solidly."
            //
            // v1.0.90: with opaque clear (the new default for non-GL
            // apps), nonzero+opaque are both 100% — uninformative as a
            // visibility signal. Also count "non-clear-color" pixels:
            // pixels whose ARGB differs from the clear value. That
            // counts text glyphs / drawn shapes vs the blank backdrop
            // and is the real "is anything rendered" signal.
            if (n == 1 || n == 60 || n == 300 || (n % 600) == 0) {
                // Timed so the phase trace can take this diagnostic block (a
                // full-surface scan, GfxTrace and RenderNode dumps) OUT of the
                // present bucket: every n%600 frame is also a heartbeat frame
                // (600 is a multiple of 30), so it biased exactly the sparse
                // heartbeat aggregates (~0.3-0.5 ms of Calculator's average).
                final long ck0 = PHASE_TRACE ? System.nanoTime() : 0;
                int nz = 0, opaque = 0, drawn = 0;
                int clearArgb2 = clearArgb;
                for (int p : pixels) {
                    if ((p & 0xFF000000) != 0) nz++;
                    if ((p & 0xFF000000) == 0xFF000000) opaque++;
                    if (p != clearArgb2) drawn++;
                }
                NativeLog.i(TAG,
                    "macapkDrawFrame fire #" + n
                    + " present " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " nonzero=" + nz + "/" + pixels.length
                    + " (" + (100L * nz / pixels.length) + "%)"
                    + " opaque=" + opaque
                    + " drawn=" + drawn);
                // Canvas-draw-gap harvester (lead B): dump the REAL/STUB/CAUGHT
                // graphics-API tally on these checkpoint frames. With
                // MACAPK_GFX_HARVEST set this is the COMPLETE draw-API usage list
                // + the silent-no-op gaps for this app in ONE run; the CAUGHT
                // section (per-node draw throws the resilient pass swallowed)
                // prints even without the flag. See com.macapk.loader.GfxTrace /
                // docs/canvas-draw-surface-diff.
                try { com.macapk.loader.GfxTrace.dump("frame#" + n); } catch (Throwable ignored) {}
                // Compose record->replay tallies (MACAPK_RENDERNODE_STATS=1).
                // Splits "the composition emitted no draws" from "it recorded
                // its content into RenderNodes that never replayed onto the
                // frame canvas" -- the two halves of a Compose blank that look
                // identical in the presented pixels. Self-gated no-op.
                try { android.graphics.RenderNode.macapkStatsDump("frame#" + n); }
                catch (Throwable ignored) {}
                // Diagnostic PNG dump on demand. Set MACAPK_DUMP_BITMAP_AT
                // to a frame number (or 0 for "every dump frame"). Writes
                // /tmp/macapk_frame_<N>.png. Lets us visually verify that
                // the canvas content is what we think it is.
                //
                // v1.0.134: when the captured bitmap's longer side
                // exceeds CHAT_DUMP_MAX_DIMENSION (2000 px), also
                // produce a downsized companion PNG suffixed
                // `_chat.png`. The retina launcher preset is
                // 1440x2560 = 3.7 megapixels per frame; the chat
                // display struggles to load PNGs that large for
                // session-time visual verification. The chat-sized
                // copy is downsized via SCALE_SMOOTH so glyphs stay
                // legible, then re-encoded -- typically 50-80% smaller
                // on disk. The full-resolution PNG is still written
                // for pixel-precise verification (jshell + getRGB).
                String dumpAt = ENV_MACAPK_DUMP_BITMAP_AT;
                if (dumpAt != null) {
                    try {
                        long want = Long.parseLong(dumpAt);
                        if (want == 0 || want == n) {
                            java.awt.image.BufferedImage img =
                                new java.awt.image.BufferedImage(bitmap.getWidth(),
                                    bitmap.getHeight(),
                                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                            img.setRGB(0, 0, bitmap.getWidth(), bitmap.getHeight(),
                                       pixels, 0, bitmap.getWidth());
                            java.io.File out = new java.io.File(
                                "/tmp/macapk_frame_" + n + ".png");
                            javax.imageio.ImageIO.write(img, "png", out);
                            NativeLog.i(TAG, "dump bitmap -> " + out.getAbsolutePath());
                            // Chat-display companion: cap longer side
                            // at 2000 px for session-time PNG review.
                            try {
                                java.io.File small = writeChatSizedCopy(img,
                                    "/tmp/macapk_frame_" + n + "_chat.png",
                                    CHAT_DUMP_MAX_DIMENSION);
                                if (small != null) {
                                    NativeLog.i(TAG, "chat-sized -> " + small.getAbsolutePath());
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
                if (PHASE_TRACE) ckNs = System.nanoTime() - ck0;
            }
            // ---- ELIDE A PRESENT THAT PUBLISHES NOTHING -------------------
            //
            // The M6 heartbeat forces a full traversal + present every 30th
            // frame as a backstop for our incomplete invalidate() coverage. For
            // a native-EGL game (Unity / AGDK / Smash Hit) the View tree is
            // EMPTY and the canvas overlay sits BENEATH the guest's opaque EGL
            // layer -- so that heartbeat allocated, cleared, traversed,
            // extracted, copied and uploaded a ~25 MB FULLY TRANSPARENT frame
            // onto a layer nobody can see, twice a second.
            //
            // Measured on Smash Hit (2026-08-24): 14.0 ms of main-thread Java
            // plus a CoreAnimation commit, against a 16.67 ms budget -- one
            // hitch every 0.52 s, strictly phase-locked to the 30-frame grid,
            // p99 46.6 ms and 2.75% of frames over 2x the median, while every
            // mean-FPS instrument read 58-59 and saw none of it. Proof the cost
            // is the PIXELS: at 1600x900 (4.34x fewer) the same build with the
            // same heartbeat rate showed ZERO hitches in 9000 frames.
            //
            // A CALayer RETAINS its contents, so re-publishing an identical
            // empty frame is a no-op the viewer cannot distinguish. Both
            // conditions are required: skipping when the CURRENT frame is empty
            // but the PREVIOUS one had ink would leave the stale ink on screen.
            //
            // ⚠ Deliberately NOT gated on occlusion alone -- apps legitimately
            // draw dialogs and toasts over a native-EGL surface, and an
            // occlusion-only gate would swallow them. ⚠ And NOT on the existing
            // `nonzero=` pixel scan, which costs more than the present it saves.
            // The heartbeat TRAVERSAL still runs; only the publish is skipped, so
            // the backstop it exists to provide is intact (`M6: skipped` must
            // never read 300/300: 290/300 at the 30-vsync heartbeat, ~297/300
            // once the opt-in MACAPK_FRAMESPEED_HEARTBEAT=1 has widened it to 120
            // vsyncs -- the M6 line prints the current `heartbeat=` period then).
            final boolean elide = !sFrameDrewInk && !sLastPublishedInk;
            // FRAMESPEED heartbeat: what this heartbeat caught. An ink-elided
            // frame published nothing, as did an exact-diff elision; anything
            // else changed pixels or cannot say (legacy present, fallback).
            int hbPresent = -1;
            if (elide) {
                sPresentsElided++;
                if (PHASE_TRACE && (sPresentsElided % 300) == 1) {
                    NativeLog.i(TAG, "present elided (empty frame, layer retains"
                        + " contents) count=" + sPresentsElided);
                }
            } else {
                sLastPublishedInk = sFrameDrewInk;
                boolean presented = false;
                if (PRESENT_IOSURFACE && !sPresentFrameUnavailable) {
                    // The whole surface is the claimed bound until view damage
                    // exists (08-30 S1); the native diff finds what really changed.
                    // HEARTBEAT marks a frame nothing scheduled, so the native side
                    // can count pixels that changed without an invalidate
                    // ([heartbeat-diff]).
                    final int bw = bitmap.getWidth(), bh = bitmap.getHeight();
                    final int flags = (presentForceFull ? NativeCocoa.PRESENT_FLAG_FORCE_FULL : 0)
                            | (DAMAGE_VALIDATE ? NativeCocoa.PRESENT_FLAG_VALIDATE : 0)
                            | (frameKind == FK_HEARTBEAT ? NativeCocoa.PRESENT_FLAG_HEARTBEAT : 0);
                    // FRAMESPEED S2: a partial frame changed pixels only inside its
                    // rect, so that is the bound the native diff scans (AOSP trusts
                    // its dirty rect the same way); VALIDATE still diffs the rest.
                    final int bl = presentPartial ? partialRect[0] : 0;
                    final int bt = presentPartial ? partialRect[1] : 0;
                    final int br = presentPartial ? partialRect[2] : bw;
                    final int bb = presentPartial ? partialRect[3] : bh;
                    try {
                        final int r = NativeCocoa.presentFrame(pixels, bw, bh, bl, bt, br, bb, flags);
                        if (r != NativeCocoa.PRESENT_NONE) presentForceFull = false;
                        hbPresent = r;
                        presented = true;
                    } catch (UnsatisfiedLinkError e) {
                        sPresentFrameUnavailable = true;
                        NativeLog.i(TAG, "presentFrame not registered (runtime older than the"
                            + " loader jar?); presenting through presentBitmap: " + e.getMessage());
                    }
                }
                if (!presented) {
                    try {
                        NativeCocoa.presentBitmap(pixels, bitmap.getWidth(), bitmap.getHeight());
                    } catch (UnsatisfiedLinkError e) {
                        if (!ViewCanvasDriverState.warnedMissingNative) {
                            ViewCanvasDriverState.warnedMissingNative = true;
                            NativeLog.i(TAG, "presentBitmap not registered: " + e.getMessage());
                        }
                    }
                }
            }
            if (frameKind == FK_HEARTBEAT)
                hb.outcome(elide || hbPresent == NativeCocoa.PRESENT_ELIDED_IDENTICAL);
            // ⚠ NO EARLY RETURN HERE. The Tier-3 M6 cleanup below
            // (consumeTraversalScheduled) MUST still run: it discards traversals
            // this traversal scheduled, and skipping it would leave the window
            // permanently dirty -- defeating the frame-skipping this elision
            // exists to help, and making pacing WORSE rather than better.
            if (PHASE_TRACE) {
                // present = the JNI hand-off + native premultiply/snapshot +
                // CGImage build (the CALayer commit itself is async on the OS
                // main thread -- see [present-trace] setContents for that half).
                // Since 2026-09-25 the checkpoint block above (every n%600 frame)
                // is EXCLUDED from present and total and reported as `ckpt`, so
                // present + ckpt is the number this line printed before.
                long pt6 = System.nanoTime();
                long dPre = pt1 - pt0, dPrep = pt2 - pt1, dLayout = pt3 - pt2;
                long dDraw = pt4 - pt3, dBar = pt5 - pt4, dPresent = pt6 - pt5 - ckNs;
                long total = pt6 - pt0 - ckNs;
                ptSumPre += dPre; ptSumPrep += dPrep; ptSumLayout += dLayout;
                ptSumDraw += dDraw; ptSumBar += dBar; ptSumPresent += dPresent;
                ptSumCkpt += ckNs;
                if (total > ptMaxTotal) ptMaxTotal = total;
                if (total > 17_000_000L) ptOver17++;
                if (total > 33_000_000L) ptOver33++;
                if (doLayout) ptLayoutFrames++;
                ptFrames++;
                if (total > 33_000_000L) {
                    NativeLog.i(TAG, String.format(
                        "[frame-phase] JANK #%d total=%.1fms pre=%.1f prep=%.1f layout=%.1f draw=%.1f bar=%.1f present=%.1f doLayout=%b kind=%s ckpt=%.1f",
                        n, total / 1e6, dPre / 1e6, dPrep / 1e6, dLayout / 1e6,
                        dDraw / 1e6, dBar / 1e6, dPresent / 1e6, doLayout,
                        FK_NAMES[frameKind], ckNs / 1e6));
                }
                if (ptFrames == 300) {
                    NativeLog.i(TAG, String.format(
                        "[frame-phase] n=300 avg(ms): pre=%.2f prep=%.2f layout=%.2f draw=%.2f bar=%.2f present=%.2f total=%.2f | maxTotal=%.1f | >17ms=%d >33ms=%d | layoutFrames=%d | ckpt=%.2f",
                        ptSumPre / 300e6, ptSumPrep / 300e6, ptSumLayout / 300e6,
                        ptSumDraw / 300e6, ptSumBar / 300e6, ptSumPresent / 300e6,
                        (ptSumPre + ptSumPrep + ptSumLayout + ptSumDraw + ptSumBar + ptSumPresent) / 300e6,
                        ptMaxTotal / 1e6, ptOver17, ptOver33, ptLayoutFrames, ptSumCkpt / 300e6));
                    ptSumPre = ptSumPrep = ptSumLayout = ptSumDraw = ptSumBar = ptSumPresent = 0;
                    ptSumCkpt = 0;
                    ptMaxTotal = 0; ptFrames = ptOver17 = ptOver33 = ptLayoutFrames = 0;
                }
                ptRecordKind(frameKind, pt6, dPre, dPrep, dLayout, dDraw, dBar, dPresent,
                             ckNs, total, elide);
            }
        }
        // Tier 3 M6: discard any traversal scheduled BY THIS traversal. doFrame
        // runs synchronously on the loop, so every invalidate()/requestLayout()
        // raised between the gate's consume above and here is INTERNAL churn (a
        // widget's onMeasure/onLayout/draw re-requesting layout -- our inflater /
        // ConstraintLayout / RecyclerView do this every pass). AOSP guards the
        // same self-perpetuation with mHandlingLayoutInLayoutRequest/mWillDrawSoon.
        // Clearing here means only schedules from OTHER loop dispatches (real app
        // events: touch, Compose recompose, posted runnables, animations) keep the
        // window dirty, so a settled tree finally goes idle and frames skip.
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            android.view.ViewRootImpl.getInstance().consumeTraversalScheduled();
            // FRAMESPEED S2: the rect follows the flag -- damage this traversal
            // raised is discarded with the traversal it scheduled (08-30 hazard 6).
            if (FRAMESPEED_DAMAGE && CONSUME_DAMAGE != null) macapkConsumeDamage(dmgRect);
        }
    }

    /** Phase trace, per frame KIND (see FK_*): add one traced frame to its
     *  kind's aggregate, then flush every kind whose window is full or stale.
     *  Warm-up ends for good at n == M6_WARMUP_FRAMES, so it is flushed by the
     *  first frame of any other kind rather than waiting on a window it would
     *  never fill. Runs only under MACAPK_FRAME_PHASE_TRACE=1. */
    private void ptRecordKind(int kind, long now, long dPre, long dPrep, long dLayout,
                              long dDraw, long dBar, long dPresent, long dCkpt,
                              long total, boolean elided) {
        if (ptKinds == null) {
            ptKinds = new PhaseKindAgg[FK_NAMES.length];
            for (int i = 0; i < ptKinds.length; i++) ptKinds[i] = new PhaseKindAgg();
        }
        PhaseKindAgg k = ptKinds[kind];
        if (k.n == 0) k.startNs = now - total;
        k.sumPre += dPre; k.sumPrep += dPrep; k.sumLayout += dLayout;
        k.sumDraw += dDraw; k.sumBar += dBar; k.sumPresent += dPresent;
        k.sumCkpt += dCkpt;
        k.totals[k.n++] = total;
        if (total > k.max) k.max = total;
        if (total > 17_000_000L) k.over17++;
        if (total > 33_000_000L) k.over33++;
        if (elided) k.elided++;
        for (int i = 0; i < ptKinds.length; i++) {
            PhaseKindAgg a = ptKinds[i];
            if (a.n == 0) continue;
            if (a.n == PhaseKindAgg.PT_KIND_N
                    || now - a.startNs >= PhaseKindAgg.PT_KIND_WINDOW_NS
                    || (i == FK_WARMUP && kind != FK_WARMUP)) {
                ptFlushKind(i, now);
            }
        }
    }

    /** One `[frame-phase] kind=<k> n=<frames>` line. p50/p90 are nearest-rank
     *  percentiles of the per-frame total; span is the wall time the window
     *  covered, so frames/span is the rate this kind arrived at. */
    private void ptFlushKind(int kind, long now) {
        PhaseKindAgg a = ptKinds[kind];
        int n = a.n;
        if (n == 0) return;
        long[] t = java.util.Arrays.copyOf(a.totals, n);
        java.util.Arrays.sort(t);
        double d = n * 1e6;
        NativeLog.i(TAG, String.format(
            "[frame-phase] kind=%s n=%d avg(ms): pre=%.2f prep=%.2f layout=%.2f draw=%.2f bar=%.2f present=%.2f total=%.2f | p50=%.1f p90=%.1f maxTotal=%.1f | >17ms=%d >33ms=%d | ckpt=%.2f elided=%d span=%.1fs",
            FK_NAMES[kind], n, a.sumPre / d, a.sumPrep / d, a.sumLayout / d,
            a.sumDraw / d, a.sumBar / d, a.sumPresent / d,
            (a.sumPre + a.sumPrep + a.sumLayout + a.sumDraw + a.sumBar + a.sumPresent) / d,
            t[(int) Math.ceil(0.5 * n) - 1] / 1e6, t[(int) Math.ceil(0.9 * n) - 1] / 1e6,
            a.max / 1e6, a.over17, a.over33, a.sumCkpt / d, a.elided,
            (now - a.startNs) / 1e9));
        a.sumPre = a.sumPrep = a.sumLayout = a.sumDraw = a.sumBar = a.sumPresent = 0;
        a.sumCkpt = a.max = 0;
        a.n = a.over17 = a.over33 = a.elided = 0;
    }

    /** v1.0.76: post-layout fixup. Walks the tree and assigns parent's
     *  bounds to any direct child with zero-extent. We can't override
     *  subclass-imposed onLayout (the APK ships ConstraintLayout etc.
     *  with their own onLayout that runs instead of ours), so this is
     *  the catch-all that ensures children of subclass ViewGroups
     *  still get visible bounds. Subdivides the parent area among
     *  zero-extent children with a simple row/column heuristic so
     *  they don't all stack on top of each other.
     *
     *  v1.0.93: skip RelativeLayout children. RelativeLayout's onLayout
     *  honors `layout_alignParentBottom` / `layout_above` etc. via
     *  the v1.0.93 rule-based positioning; its children may end up
     *  zero-extent ON PURPOSE (e.g. an empty SwipeRefreshLayout, a
     *  RecyclerView whose width=0 because layout_above resolved to a
     *  zero-height anchor). Clobbering them with the grid-fill
     *  default reverts the intentional layout. The proper layout for
     *  RelativeLayout children comes from RelativeLayout itself. */
    /** v1.0.98: dump RecyclerView internal state via reflection so
     *  we can see why dispatchLayout isn't driving binding. Called
     *  once per RV per session (warn-once map). */
    // WEAK (v1.0.1643): a strong View-keyed map held every RecyclerView ever
    // seen -- and through it its whole activity -- for the life of the process.
    private static final java.util.Map<View, Boolean> rvDumped = new java.util.WeakHashMap<>();
    private static void dumpRvStateIfFirstSeen(View v) {
        String cn = v.getClass().getName();
        if (!cn.endsWith(".RecyclerView") && !cn.equals("androidx.recyclerview.widget.RecyclerView")) return;
        if (rvDumped.put(v, Boolean.TRUE) != null) return;
        StringBuilder sb = new StringBuilder("RV@" + Integer.toHexString(System.identityHashCode(v)) + " state: ");
        Class<?> c = v.getClass();
        String[] fields = {"mAdapter", "mLayout", "mState", "mAdapterHelper",
            "mRecycler", "mIsAttachedToWindow", "mFirstLayoutComplete",
            "mLayoutSuppressed", "mDataSetHasChangedAfterLayout"};
        for (String f : fields) {
            try {
                java.lang.reflect.Field fld = findField(c, f);
                if (fld == null) { sb.append(f).append("=<missing> "); continue; }
                fld.setAccessible(true);
                Object val = fld.get(v);
                if (val == null) {
                    sb.append(f).append("=null ");
                } else if (val instanceof Boolean || val instanceof Integer) {
                    sb.append(f).append("=").append(val).append(" ");
                } else {
                    sb.append(f).append("=").append(val.getClass().getSimpleName()).append(" ");
                }
            } catch (Throwable t) {
                sb.append(f).append("=<err:").append(t.getClass().getSimpleName()).append("> ");
            }
        }
        // Also dump State.mLayoutStep + mItemCount specifically
        try {
            java.lang.reflect.Field mState = findField(c, "mState");
            if (mState != null) {
                mState.setAccessible(true);
                Object state = mState.get(v);
                if (state != null) {
                    java.lang.reflect.Field stepF = findField(state.getClass(), "mLayoutStep");
                    if (stepF != null) {
                        stepF.setAccessible(true);
                        sb.append("State.mLayoutStep=").append(stepF.get(state)).append(" ");
                    }
                    java.lang.reflect.Field cntF = findField(state.getClass(), "mItemCount");
                    if (cntF != null) {
                        cntF.setAccessible(true);
                        sb.append("State.mItemCount=").append(cntF.get(state)).append(" ");
                    }
                }
            }
            java.lang.reflect.Field mAdapter = findField(c, "mAdapter");
            if (mAdapter != null) {
                mAdapter.setAccessible(true);
                Object adapter = mAdapter.get(v);
                if (adapter != null) {
                    java.lang.reflect.Method getItemCount = adapter.getClass().getMethod("getItemCount");
                    Object ic = getItemCount.invoke(adapter);
                    sb.append("adapter.getItemCount=").append(ic).append(" ");
                }
            }
        } catch (Throwable t) {
            sb.append(" [State/Adapter dump err: ").append(t).append("]");
        }
        NativeLog.i(TAG, sb.toString());
    }
    private static java.lang.reflect.Field findField(Class<?> c, String name) {
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    /** v1.0.97: walk for RecyclerView instances and call measure() on
     *  each with the view's current bounds. RecyclerView.onMeasure
     *  initializes the layout-state machine (dispatchLayoutStep1+2)
     *  and triggers the LayoutManager's onLayoutChildren -- which is
     *  where onCreateViewHolder / onBindViewHolder are called. Without
     *  this targeted measure, setAdapter's requestLayout goes nowhere
     *  because our base View.requestLayout is a no-op and the root
     *  measure doesn't recurse. */
    private static int mrvCallCount = 0;
    private static boolean mrvFirstLogged = false;
    private static boolean warnedLayoutErr = false;
    private static boolean warnedMeasureErr = false;
    private static boolean warnedInsetMeasure = false;
    /** Diagnostic: dump androidx ConstraintLayout Barriers -- their referenced
     *  ids (resolved or empty?) + bounds -- to tell whether the AppDetails header
     *  button overlaps the icon because the Barrier never got its referenced
     *  widgets (an inflater gap) vs a deeper solver-math issue. */
    private static void dumpBarriers(View v) {
        if (v == null) return;
        String cn = v.getClass().getSimpleName();
        if (cn.endsWith("Barrier")) {
            try {
                int[] ids = (int[]) v.getClass().getMethod("getReferencedIds").invoke(v);
                Object type = v.getClass().getMethod("getType").invoke(v);
                StringBuilder sb = new StringBuilder("Barrier@" + Integer.toHexString(System.identityHashCode(v))
                    + " type=" + type + " bounds=[" + v.getLeft() + "," + v.getTop() + "-" + v.getRight() + "," + v.getBottom() + "]"
                    + " refIds=" + (ids == null ? "null" : ids.length));
                if (ids != null) for (int id : ids) sb.append(" 0x").append(Integer.toHexString(id));
                NativeLog.i(TAG, sb.toString());
            } catch (Throwable t) { NativeLog.i(TAG, "Barrier dump err: " + t); }
        }
        if (v instanceof android.view.ViewGroup vg)
            for (int i = 0; i < vg.getChildCount(); i++) dumpBarriers(vg.getChildAt(i));
    }

    // PERF1 (speed-run lane, 2026-07-30): Field/Method cache for the RecyclerView ->
    // Adapter introspection (mAdapter field, getItemCount()/getItemViewType(int)
    // methods) that measureRecyclerViews' natural-flow probe and tryEnforceOnRv's
    // tab-pager check below both do. AOSP's RecyclerView keeps this shape fixed per
    // runtime Class for the life of the process -- a given RV subclass's mAdapter
    // field, and a given Adapter subclass's getItemCount/getItemViewType methods,
    // never move -- so re-resolving them via findField/findPublicMethod (a
    // getDeclaredField/getMethod walk) on EVERY frame for EVERY on-screen
    // RecyclerView, on the measure/layout hot path, was pure waste: the app-agnostic
    // shape this class already avoids elsewhere via per-VIEW-INSTANCE caches
    // (rvDumped/rvForceBound above), just keyed by Class instead since the same
    // adapter Class is shared across every RV instance of that type. Cheap to keep
    // correct: androidx never relocates these members at runtime, and ConcurrentHashMap
    // is safe if two ViewCanvasDriver-driven windows race to populate the same entry
    // (last write wins, both values are equivalent resolved reflection handles).
    private static final java.util.Map<Class<?>, java.lang.reflect.Field> sRvAdapterFieldCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static java.lang.reflect.Field cachedAdapterField(Class<?> rvClass) {
        java.lang.reflect.Field f = sRvAdapterFieldCache.get(rvClass);
        if (f != null) return f;
        f = findField(rvClass, "mAdapter");
        if (f != null) {
            f.setAccessible(true);
            sRvAdapterFieldCache.put(rvClass, f);
        }
        return f;
    }
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> sRvGetItemCountCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static java.lang.reflect.Method cachedGetItemCount(Class<?> adapterClass) {
        java.lang.reflect.Method m = sRvGetItemCountCache.get(adapterClass);
        if (m != null) return m;
        m = findPublicMethod(adapterClass, "getItemCount");
        if (m != null) {
            m.setAccessible(true);
            sRvGetItemCountCache.put(adapterClass, m);
        }
        return m;
    }
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> sRvGetItemViewTypeCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static java.lang.reflect.Method cachedGetItemViewType(Class<?> adapterClass) {
        java.lang.reflect.Method m = sRvGetItemViewTypeCache.get(adapterClass);
        if (m != null) return m;
        m = findPublicMethod(adapterClass, "getItemViewType", int.class);
        if (m != null) {
            m.setAccessible(true);
            sRvGetItemViewTypeCache.put(adapterClass, m);
        }
        return m;
    }

    private static void measureRecyclerViews(View v) {
        if (v == null) return;
        if (!mrvFirstLogged) {
            mrvFirstLogged = true;
            NativeLog.i(TAG, "MRV-first-entry root=" + v.getClass().getSimpleName());
        }
        // RecyclerView is in the APK's classloader -- match by class
        // name to avoid a compile-time dependency on androidx.
        String cn = v.getClass().getName();
        if ("androidx.recyclerview.widget.RecyclerView".equals(cn)
            || cn.endsWith(".RecyclerView")) {
            // v1.0.99: mark the metal callback thread as "main" so
            // androidx lifecycle / Compose / RecyclerView force-bind
            // paths pass enforceMainThreadIfNeeded.
            android.os.Looper.markCurrentThreadAsMain();
            dumpRvStateIfFirstSeen(v);
            // v1.0.124: RV.measure with EXACTLY/EXACTLY specs is a NO-OP
            // for layout dispatch by AOSP design (RecyclerView.java
            // lines 4245-4248): RV.onMeasure deliberately returns
            // without calling dispatchLayoutStep1+Step2 when both
            // specs are EXACTLY -- it defers to onLayout. Our old
            // explicit measure call here did nothing useful AND
            // wasted CPU. The natural pump is RV.onLayout (driven by
            // ViewGroup.onLayout cascading down each frame), which
            // calls dispatchLayout -> dispatchLayoutStep1+2 ->
            // LayoutManager.onLayoutChildren -> fill() viewport gate
            // (LinearLayoutManager.java:1678) which attaches only the
            // visible-window items via mChildHelper.
            //
            // Env rollback MACAPK_RV_NATURAL=0 restores the v123
            // force-bind / layoutGridManually workaround.
            if ("0".equals(ENV_MACAPK_RV_NATURAL)) {
                resetRvLayoutStep(v);
                int w = v.getRight() - v.getLeft();
                int h = v.getBottom() - v.getTop();
                if (w > 0 && h > 0) {
                    int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                        w, android.view.View.MeasureSpec.EXACTLY);
                    int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                        h, android.view.View.MeasureSpec.EXACTLY);
                    try { v.measure(wSpec, hSpec); } catch (Throwable t) {
                        NativeLog.w(TAG, "RV.measure threw " + t);
                    }
                }
                // Force-bind: bypass RV's LayoutManager / Recycler state
                // machine entirely. The natural-flow path (v1.0.124+)
                // lets the LM handle this properly via its onLayoutChildren.
                forceBindRecyclerView(v);
            } else {
                // v1.0.124: natural flow. RV.onLayout (fired from our
                // ViewGroup.onLayout cascade and re-fired per-frame
                // via the layout block at line 145+157) drives
                // dispatchLayout -> step1+step2 -> LM.onLayoutChildren
                // -> fill() viewport gate -> attaches visible-window
                // items via mChildHelper -> RV.addView -> ViewGroup.addView.
                // No reflection, no per-APK code.
                //
                // FALLBACK: if the adapter has 0 items (data load
                // pending), invoke the legacy force-bind path to
                // create stub ViewHolders. This is a temporary lie
                // until the real data load chain (Room/F-Droid sync)
                // produces items. Remove once data load works
                // end-to-end without force-bind.
                int w = v.getRight() - v.getLeft();
                int h = v.getBottom() - v.getTop();
                if (w > 0 && h > 0) {
                    // Check if adapter has items. PERF1: fa/gic are resolved once per
                    // Class and cached (see cachedAdapterField/cachedGetItemCount above)
                    // instead of walking getDeclaredField/getMethod on every frame for
                    // every on-screen RecyclerView.
                    int itemCount = -1;
                    try {
                        java.lang.reflect.Field fa = cachedAdapterField(v.getClass());
                        if (fa != null) {
                            Object adapter = fa.get(v);
                            if (adapter != null) {
                                java.lang.reflect.Method gic = cachedGetItemCount(adapter.getClass());
                                if (gic != null) {
                                    itemCount = (int) gic.invoke(adapter);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    if (itemCount == 0) {
                        // Fallback: force-bind for empty-adapter case so
                        // SOMETHING renders while data loads.
                        forceBindRecyclerView(v);
                    }
                }
                int w2 = v.getRight() - v.getLeft();
                int h2 = v.getBottom() - v.getTop();
                if (w2 > 0 && h2 > 0) {
                    // Measure first so RV knows its size.
                    int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                        w2, android.view.View.MeasureSpec.EXACTLY);
                    int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                        h2, android.view.View.MeasureSpec.EXACTLY);
                    try { v.measure(wSpec, hSpec); } catch (Throwable t) {
                        if ("1".equals(ENV_MACAPK_TRACE_RV)) {
                            NativeLog.w(TAG, "RV.measure threw " + t);
                        }
                    }
                }
            }
        }
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) measureRecyclerViews(vg.getChildAt(i));
        }
    }

    /** Force RecyclerView's mState.mLayoutStep back to STEP_START (=1).
     *  Without this, after the first onMeasure runs dispatchLayoutStep1
     *  (which advances to STEP_LAYOUT=2), all subsequent dispatchLayout
     *  calls take the else branch and may skip dispatchLayoutStep2 --
     *  the only path that calls LayoutManager.onLayoutChildren ->
     *  Recycler.getViewForPosition -> Adapter.onCreateViewHolder. */
    private static void resetRvLayoutStep(View rv) {
        try {
            java.lang.reflect.Field mState = findField(rv.getClass(), "mState");
            if (mState == null) return;
            mState.setAccessible(true);
            Object state = mState.get(rv);
            if (state == null) return;
            java.lang.reflect.Field step = findField(state.getClass(), "mLayoutStep");
            if (step == null) return;
            step.setAccessible(true);
            step.setInt(state, 1); // STEP_START
        } catch (Throwable ignored) {}
    }

    private static final java.util.Map<View, Boolean> rvForceBound = new java.util.WeakHashMap<>();   // weak: see rvDumped
    /** v1.0.98: force-bind ViewHolders into the RecyclerView via the
     *  adapter, bypassing the LayoutManager / Recycler state machine.
     *  Run ONCE per RV: for each position 0..adapter.getItemCount()-1,
     *  call createViewHolder + bindViewHolder, then addView the
     *  holder.itemView to the RecyclerView. This is the brute-force
     *  approach when the RV state machine isn't dispatching layout
     *  pipeline (some internal step bails silently because of missing
     *  framework surface).
     *
     *  The holder's itemView becomes a direct child of the RV (= what
     *  the user sees as the "tab content"). Each tab's content is
     *  populated by the holder's bind method (e.g. bindLatestView
     *  inflates main_tab_latest.xml into the FrameLayout). */
    private static void forceBindRecyclerView(View rv) {
        if (rvForceBound.put(rv, Boolean.TRUE) != null) return;
        Class<?> rvCls = rv.getClass();
        try {
            // Need rv to be sized + adapter non-null.
            if (rv.getRight() - rv.getLeft() <= 0
                || rv.getBottom() - rv.getTop() <= 0) {
                rvForceBound.remove(rv);
                return;
            }
            java.lang.reflect.Field fAdapter = findField(rvCls, "mAdapter");
            if (fAdapter == null) return;
            fAdapter.setAccessible(true);
            Object adapter = fAdapter.get(rv);
            if (adapter == null) {
                rvForceBound.remove(rv);
                return;
            }
            Class<?> adapterCls = adapter.getClass();
            int itemCount;
            try {
                java.lang.reflect.Method getItemCount = findPublicMethod(adapterCls, "getItemCount");
                if (getItemCount == null) return;
                getItemCount.setAccessible(true);
                itemCount = (int) getItemCount.invoke(adapter);
            } catch (Throwable t) {
                NativeLog.w(TAG, "force-bind: getItemCount threw " + t);
                return;
            }
            if (itemCount <= 0) {
                rvForceBound.remove(rv);
                return;
            }
            NativeLog.i(TAG, "force-binding " + itemCount + " items into "
                + rvCls.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(rv)));
            // RecyclerView.Adapter has:
            //   createViewHolder(ViewGroup, int)  -- calls our onCreateViewHolder
            //   bindViewHolder(ViewHolder, int)   -- calls our onBindViewHolder
            //   getItemViewType(int)              -- override
            java.lang.reflect.Method getItemViewType = findPublicMethod(adapterCls, "getItemViewType", int.class);
            java.lang.reflect.Method createViewHolder = findPublicMethod(adapterCls, "createViewHolder", android.view.ViewGroup.class, int.class);
            java.lang.reflect.Method bindViewHolder = findPublicMethod(adapterCls, "bindViewHolder", null, int.class);
            // bindViewHolder's first arg is ViewHolder; find by name.
            if (bindViewHolder == null) {
                for (java.lang.reflect.Method m : adapterCls.getMethods()) {
                    if ("bindViewHolder".equals(m.getName()) && m.getParameterCount() == 2) {
                        bindViewHolder = m;
                        break;
                    }
                }
            }
            if (getItemViewType == null || createViewHolder == null || bindViewHolder == null) {
                NativeLog.w(TAG, "force-bind: missing adapter methods (gvt=" + getItemViewType
                    + " cvh=" + createViewHolder + " bvh=" + bindViewHolder + ")");
                return;
            }
            try { getItemViewType.setAccessible(true); } catch (Throwable ignored) {}
            try { createViewHolder.setAccessible(true); } catch (Throwable ignored) {}
            try { bindViewHolder.setAccessible(true); } catch (Throwable ignored) {}
            // v1.0.106: distinguish "tab-pager" RVs from "list/grid" RVs.
            // The original (v1.0.99) selectedTab GONE-everything-but-one
            // logic was designed for the OUTER RV that holds N fragment
            // pages -- exactly N items each with a distinct viewType.
            // Applying it to a regular content list (e.g. LatestAdapter
            // with 134 app cards, all sharing one viewType) makes all
            // but one card GONE -- which masks the entire content area.
            // Heuristic: itemCount <= 5 AND every item has a distinct
            // viewType -> tab pager. Otherwise -> list, leave all
            // visible and let the LayoutManager position them.
            boolean isTabPagerRv = false;
            if (itemCount >= 2 && itemCount <= 5) {
                java.util.Set<Integer> typesSeen = new java.util.HashSet<>();
                try {
                    for (int k = 0; k < itemCount; k++) {
                        typesSeen.add((int) getItemViewType.invoke(adapter, k));
                    }
                    isTabPagerRv = typesSeen.size() == itemCount;
                } catch (Throwable ignored) {}
            }
            NativeLog.i(TAG, "force-bind: " + (isTabPagerRv ? "tab-pager" : "list")
                + " mode for " + itemCount + " items");
            for (int i = 0; i < itemCount; i++) {
                Object holder = null;
                int viewType = 0;
                try {
                    viewType = (int) getItemViewType.invoke(adapter, i);
                    holder = createViewHolder.invoke(adapter, rv, viewType);
                    if (holder == null) {
                        NativeLog.w(TAG, "force-bind: createViewHolder(" + i + ", type=0x"
                            + Integer.toHexString(viewType) + ") returned null");
                        continue;
                    }
                    // Add holder.itemView to RV BEFORE bind. Many
                    // androidx widgets (Compose, lifecycle-aware
                    // RecyclerView callees) walk the parent chain to
                    // find ViewTreeLifecycleOwner / ViewTreeSavedState
                    // Owner; if the itemView has no parent, the walk
                    // fails. Adding first ensures getParent() chain
                    // reaches the decor view (which carries the
                    // installed owners).
                    java.lang.reflect.Field fItemView = findField(holder.getClass(), "itemView");
                    if (fItemView == null) {
                        NativeLog.w(TAG, "force-bind: itemView field missing on " + holder.getClass());
                        continue;
                    }
                    fItemView.setAccessible(true);
                    Object itemView = fItemView.get(holder);
                    if (itemView == null) {
                        NativeLog.w(TAG, "force-bind: itemView null after create");
                        continue;
                    }
                    if (itemView instanceof View itemViewV && rv instanceof android.view.ViewGroup rvg) {
                        if (itemViewV.getParent() == null) {
                            rvg.addView(itemViewV);
                            NativeLog.i(TAG, "force-bind: item " + i
                                + " pre-attached (type=0x" + Integer.toHexString(viewType)
                                + " view=" + itemViewV.getClass().getSimpleName() + ")");
                        }
                        // v1.0.106: write the ViewHolder back into the
                        // RecyclerView.LayoutParams.mViewHolder field on
                        // the itemView's LayoutParams. AOSP's
                        // RecyclerView.attachViewToParent normally does
                        // this; bypassing it with addView leaves mViewHolder
                        // null, and GridLayoutManager.onLayoutChildren
                        // (which iterates children and reads their VH
                        // positions) NPEs. Generic across RV subclasses.
                        try {
                            android.view.ViewGroup.LayoutParams lp = itemViewV.getLayoutParams();
                            if (lp != null) {
                                java.lang.reflect.Field fVH = findField(lp.getClass(), "mViewHolder");
                                if (fVH != null) {
                                    fVH.setAccessible(true);
                                    fVH.set(lp, holder);
                                }
                            }
                        } catch (Throwable ignored) {}
                        if (isTabPagerRv) {
                            // v1.0.99: only ONE tab visible at a time, the
                            // rest GONE so the RV looks like a tab-view.
                            // v1.0.100: which tab is visible is settable via
                            // MACAPK_RV_VISIBLE_INDEX env (0..N-1, default 0)
                            // so the user can preview any tab without
                            // wiring real touch dispatch.
                            int selectedTab = parseSelectedTab();
                            itemViewV.setVisibility(i == selectedTab ? View.VISIBLE : View.GONE);
                        }
                        // else: list/grid mode -- leave the LayoutManager
                        // to position and size the children. All items
                        // stay VISIBLE; positioning happens via the
                        // attached LayoutManager's onLayoutChildren.
                    }
                    // Now bind -- the binder's findViewById +
                    // ViewTreeLifecycleOwner.get calls work because
                    // the holder's view is now in the live tree.
                    bindViewHolder.invoke(adapter, holder, i);
                    NativeLog.i(TAG, "force-bind: item " + i + " bound successfully");
                } catch (Throwable t) {
                    NativeLog.w(TAG, "force-bind: item " + i + " threw "
                        + (t.getCause() != null ? t.getCause() : t));
                    if (t.getCause() != null) {
                        java.io.StringWriter sw = new java.io.StringWriter();
                        t.getCause().printStackTrace(new java.io.PrintWriter(sw));
                        NativeLog.w(TAG, "force-bind cause stack:\n" + sw);
                    }
                }
            }
            // v1.0.106: for list/grid RVs, ask the LayoutManager to
            // actually POSITION the children. force-bind added them
            // as direct children via addView, but their bounds remain
            // unpositioned until the LayoutManager runs onLayoutChildren.
            // For a tab pager, only one child is VISIBLE and it fills
            // the RV (default child layout), so positioning isn't
            // needed there.
            //
            // v1.0.107: invoking GridLayoutManager.onLayoutChildren
            // reflectively doesn't produce a usable grid -- the
            // LayoutManager's internal ChildHelper / ViewHolder
            // bookkeeping requires our cards to have been added via
            // mLayout.addView (which routes through mChildHelper).
            // Since we bypass that with ViewGroup.addView, the
            // layout pass mis-computes per-card widths (we observed
            // every card collapsed to 10 px wide). The robust generic
            // path is to lay out the cards ourselves, respecting the
            // spanCount + SpanSizeLookup the adapter exposes: AOSP
            // grid semantic, no LayoutManager state required.
            if (!isTabPagerRv) {
                layoutGridManually(rv, adapter, itemCount);
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "force-bind failed: " + t);
        }
    }

    /** v1.0.106: invoke the RecyclerView's attached LayoutManager's
     *  onLayoutChildren so it positions the children we just
     *  force-bound. AOSP signature:
     *      LayoutManager.onLayoutChildren(Recycler, State)
     *  Both Recycler and State are private fields on the RecyclerView,
     *  accessed reflectively. Generic across LinearLayoutManager,
     *  GridLayoutManager, StaggeredGridLayoutManager, etc.
     *
     *  v1.0.107: also force `RecyclerView.LayoutManager.mWidth/mHeight`
     *  to the RV's actual dimensions before onLayoutChildren runs.
     *  AOSP normally sets these in `LayoutManager.setMeasureSpecs()`
     *  during the RV's onMeasure pass; when we force-bind we bypass
     *  the standard measure-then-layout pipeline, leaving mWidth/mHeight
     *  at 0. GridLayoutManager then computes `totalSpace = 0 - paddings`
     *  -> negative -> mCachedBorders all zero -> every cell is the
     *  default min width (~10 px) -> cards squish into a 10-wide
     *  column. Same root cause for any other LayoutManager that
     *  reads getWidth() during layout. Also push the State to a sane
     *  pre-layout state so `state.getItemCount()` returns the real
     *  count (otherwise GridLayoutManager.layoutChunk bails after
     *  the first item with State.mItemCount=0). */
    private static void runLayoutManager(View rv) {
        try {
            Class<?> rvCls = rv.getClass();
            java.lang.reflect.Field fLayout = findField(rvCls, "mLayout");
            java.lang.reflect.Field fRecycler = findField(rvCls, "mRecycler");
            java.lang.reflect.Field fState = findField(rvCls, "mState");
            java.lang.reflect.Field fAdapter = findField(rvCls, "mAdapter");
            if (fLayout == null || fRecycler == null || fState == null) return;
            fLayout.setAccessible(true);
            fRecycler.setAccessible(true);
            fState.setAccessible(true);
            Object layoutManager = fLayout.get(rv);
            Object recycler = fRecycler.get(rv);
            Object state = fState.get(rv);
            if (layoutManager == null || recycler == null || state == null) return;
            // v1.0.107: prep the LayoutManager + State.
            primeLayoutManagerForLayout(rv, layoutManager, state, fAdapter);
            java.lang.reflect.Method onLayoutChildren = null;
            for (java.lang.reflect.Method m : layoutManager.getClass().getMethods()) {
                if ("onLayoutChildren".equals(m.getName()) && m.getParameterCount() == 2) {
                    onLayoutChildren = m; break;
                }
            }
            if (onLayoutChildren == null) {
                NativeLog.w(TAG, "runLayoutManager: no onLayoutChildren on "
                    + layoutManager.getClass().getSimpleName());
                return;
            }
            onLayoutChildren.invoke(layoutManager, recycler, state);
            NativeLog.i(TAG, "runLayoutManager: " + layoutManager.getClass().getSimpleName()
                + ".onLayoutChildren done");
            dumpLayoutManagerState(layoutManager, "after-onLayoutChildren");
        } catch (Throwable t) {
            NativeLog.w(TAG, "runLayoutManager failed: " + (t.getCause() != null ? t.getCause() : t));
        }
    }

    /** v1.0.107: lay out the children of a force-bound RecyclerView
     *  manually, respecting the LayoutManager's spanCount + the
     *  SpanSizeLookup the adapter exposes. AOSP semantic: the
     *  GridLayoutManager assigns each item a position in a row of
     *  `spanCount` columns; items with `spanSize == spanCount` fill
     *  the full row; smaller spans share a row. We walk the items in
     *  order, place each span-block at (col * cellWidth, currentY),
     *  advance the column, and wrap rows.
     *
     *  Cell width = (rv.getWidth() - paddings) / spanCount.
     *  Card width = cellWidth * spanSize.
     *  Card height = view.getMeasuredHeight() if measured, else
     *  view.getLayoutParams().height if specific, else a sensible
     *  default. For LinearLayoutManager (spanCount=1), this reduces
     *  to a single column.
     *
     *  Generic: any RecyclerView with a LinearLayoutManager subclass.
     *  No per-APK code -- the spanCount and span sizes come from
     *  what the APK's own adapter configured. */
    private static void layoutGridManually(View rv, Object adapter, int itemCount) {
        try {
            int rvWidth = rv.getRight() - rv.getLeft();
            int rvHeight = rv.getBottom() - rv.getTop();
            if (rvWidth <= 0 || itemCount <= 0) return;
            // Padding (best-effort).
            int paddingLeft = 0, paddingRight = 0, paddingTop = 0;
            try {
                java.lang.reflect.Method m = rv.getClass().getMethod("getPaddingLeft");
                paddingLeft = (int) m.invoke(rv);
                m = rv.getClass().getMethod("getPaddingRight");
                paddingRight = (int) m.invoke(rv);
                m = rv.getClass().getMethod("getPaddingTop");
                paddingTop = (int) m.invoke(rv);
            } catch (Throwable ignored) {}
            int contentWidth = rvWidth - paddingLeft - paddingRight;
            // Pull the LayoutManager's spanCount + spanSizeLookup.
            int spanCount = 1;
            Object spanSizeLookup = null;
            try {
                java.lang.reflect.Field fLayout = findField(rv.getClass(), "mLayout");
                if (fLayout != null) {
                    fLayout.setAccessible(true);
                    Object lm = fLayout.get(rv);
                    if (lm != null) {
                        java.lang.reflect.Field fSC = findField(lm.getClass(), "mSpanCount");
                        if (fSC != null) {
                            fSC.setAccessible(true);
                            spanCount = fSC.getInt(lm);
                        }
                        java.lang.reflect.Field fSL = findField(lm.getClass(), "mSpanSizeLookup");
                        if (fSL != null) {
                            fSL.setAccessible(true);
                            spanSizeLookup = fSL.get(lm);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (spanCount < 1) spanCount = 1;
            int cellWidth = contentWidth / spanCount;
            java.lang.reflect.Method getSpanSize = null;
            if (spanSizeLookup != null) {
                for (java.lang.reflect.Method m : spanSizeLookup.getClass().getMethods()) {
                    if ("getSpanSize".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == int.class) {
                        getSpanSize = m;
                        // v1.0.113: F-Droid's LatestAdapter$SpanSizeLookup is a
                        // PACKAGE-PRIVATE final class. Even though getSpanSize
                        // is declared public on it, java.lang.reflect can't
                        // invoke a method whose declaring class is not
                        // accessible from the caller's classloader / package.
                        // setAccessible(true) bypasses the package check.
                        // Without this, every invoke threw IllegalAccessException
                        // and our laid-out grid used the default span=1 for
                        // every card -- losing the full-width "regular_list"
                        // pos-0 card that's a hallmark of F-Droid's Latest tab.
                        try { getSpanSize.setAccessible(true); } catch (Throwable ignored) {}
                        break;
                    }
                }
            }
            NativeLog.i(TAG, "layoutGridManually: rvWidth=" + rvWidth
                + " contentWidth=" + contentWidth + " spanCount=" + spanCount
                + " cellWidth=" + cellWidth + " items=" + itemCount
                + " spanSizeLookup=" + (spanSizeLookup != null
                    ? spanSizeLookup.getClass().getSimpleName() : "null")
                + " getSpanSize=" + (getSpanSize != null ? "found" : "null"));
            // Diagnostic: print spanSize for first 6 items.
            if (getSpanSize != null) {
                NativeLog.i(TAG, "spanSizeLookup actual class: " + spanSizeLookup.getClass().getName());
                StringBuilder spans = new StringBuilder("spanSizes for items 0..5:");
                Throwable lastT = null;
                for (int probe = 0; probe < Math.min(6, itemCount); probe++) {
                    try {
                        int s = (int) getSpanSize.invoke(spanSizeLookup, probe);
                        spans.append(" ").append(probe).append("=").append(s);
                    } catch (Throwable t) {
                        spans.append(" ").append(probe).append("=ERR");
                        lastT = t;
                    }
                }
                NativeLog.i(TAG, spans.toString());
                if (lastT != null) {
                    NativeLog.i(TAG, "spanSize ERR: " + lastT
                        + " (cause: " + (lastT.getCause() != null ? lastT.getCause() : "none") + ")");
                }
            }
            // v1.0.121: per-card inter-card margin so cards visually
            // separate. Material 3's grid spacing token is 8dp (16px
            // at density=2). Real F-Droid actually uses an
            // ItemDecoration that adds 4dp around each card (= 8dp
            // between two adjacent cards' content edges); we apply
            // the equivalent by deflating each cell by `gap/2` on
            // every side. The gap also lifts cards off the screen
            // edges. Configurable via env var for A/B testing.
            int gap = 16;  // 8dp at density=2 -- inter-card margin
            try {
                String gv = ENV_MACAPK_CARD_GAP_PX;
                if (gv != null) gap = Math.max(0, Integer.parseInt(gv));
            } catch (Throwable ignored) {}
            int halfGap = gap / 2;
            // Walk items, placing each.
            int col = 0;        // current column index
            int yTop = paddingTop + halfGap;
            int rowMaxHeight = 0;
            android.view.ViewGroup rvg = (android.view.ViewGroup) rv;
            int childCount = rvg.getChildCount();
            int itemsLaidOut = 0;
            for (int i = 0; i < itemCount && i < childCount; i++) {
                View child = rvg.getChildAt(i);
                if (child == null) continue;
                if (child.getVisibility() == View.GONE) continue;
                int spanSize = 1;
                if (getSpanSize != null) {
                    try { spanSize = (int) getSpanSize.invoke(spanSizeLookup, i); }
                    catch (Throwable ignored) {}
                }
                if (spanSize < 1) spanSize = 1;
                if (spanSize > spanCount) spanSize = spanCount;
                // If this span doesn't fit in the current row, wrap.
                if (col + spanSize > spanCount) {
                    yTop += rowMaxHeight + gap;
                    col = 0;
                    rowMaxHeight = 0;
                }
                int cellW = cellWidth * spanSize;
                // Measure the card to learn its desired height.
                // Use EXACTLY cellW for width, UNSPECIFIED for height
                // so wrap_content children report their natural
                // height.
                int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(cellW, android.view.View.MeasureSpec.EXACTLY);
                // v1.0.109: AT_MOST rvHeight, not UNSPECIFIED 0.
                // ConstraintLayout's solver sees the spec mode + size to
                // compute max bounds for MATCH_CONSTRAINT children;
                // with UNSPECIFIED 0 the solver caps every child at
                // 0 height and the widget graph collapses to zero
                // dimensions. AT_MOST rvHeight gives the solver room
                // to compute proper sizes for children with vertical
                // chains / wrap_content stacks.
                int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    Math.max(1, rvHeight),
                    android.view.View.MeasureSpec.AT_MOST);
                // v1.0.123: AOSP RTL-resolution cascade -- resolves
                // each child's LayoutParams BEFORE the card's CL
                // onMeasure runs. CL reads the resolved fields in
                // applyConstraintsFromLayoutParams to wire start/end
                // anchors; without prior resolution, every
                // startToEnd / endToEnd constraint stays UNSET and
                // the solver leaves widget.X=0. Idempotent per View
                // (View.isLayoutDirectionResolved short-circuits
                // subsequent calls). Env rollback MACAPK_RTL_RESOLVE=0.
                if (!"0".equals(ENV_MACAPK_RTL_RESOLVE)) {
                    resolveTreeRtl(child);
                }
                try { child.measure(wSpec, hSpec); }
                catch (Throwable ignored) {}
                int childH = child.getMeasuredHeight();
                if (childH <= 0) {
                    // Fall back to LayoutParams.height when measure
                    // didn't yield a positive value. Common when our
                    // ViewGroup.onMeasure didn't recurse into a card
                    // whose own onMeasure is a stub.
                    try {
                        android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
                        if (lp != null && lp.height > 0) childH = lp.height;
                    } catch (Throwable ignored) {}
                }
                if (childH <= 0) childH = 288; // 144dp guess
                // v1.0.121: deflate cell by halfGap on every side so
                // adjacent cards have `gap` px between their content
                // edges and the RV's left/right padding shows the
                // surface color through.
                int xLeft  = paddingLeft + col * cellWidth + halfGap;
                int xRight = paddingLeft + (col + spanSize) * cellWidth - halfGap;
                int yBottom = yTop + childH;
                child.layout(xLeft, yTop, xRight, yBottom);
                // v1.0.109 diagnostic: dump the first card's
                // ConstraintLayout widget graph state after layout so
                // we can see whether the solver ran (widget.getX/Y/
                // Width/Height non-zero) and whether the
                // constraint targets resolved.
                if (i == 0 && ENV_MACAPK_DUMP_CL_WIDGETS != null) {
                    dumpConstraintLayoutWidgets(child);
                }
                col += spanSize;
                if (childH > rowMaxHeight) rowMaxHeight = childH;
                if (col >= spanCount) {
                    yTop += rowMaxHeight;
                    col = 0;
                    rowMaxHeight = 0;
                }
                itemsLaidOut++;
                // v1.0.110: lay out ALL items. The previous early-break
                // (yTop > rvHeight + 200) left items 5..N at zero-bounds,
                // which our forceLayoutZeroExtentChildren heuristic then
                // grabbed and shoehorned into a single horizontal row at
                // 720/N ≈ 5 px each, causing the small-card-mash you
                // see at the top of the Latest tab. Laying everything
                // out properly here (off-screen rows are fine; they
                // simply aren't drawn because the RV's clip applies)
                // matches AOSP behavior for a non-scrolled GridLayout.
            }
            NativeLog.i(TAG, "layoutGridManually: laid out " + itemsLaidOut + " items, finalY=" + yTop);
            // v1.0.122 had a post-layout `fixupConstraintAnchors` hack
            // here, gated behind MACAPK_FIXUP_CONSTRAINTS=1, that
            // tried to compute child positions in the driver after CL's
            // solver left widget.X=0. v1.0.123 removed it: the PROPER
            // fix lives in the framework's RTL-resolution cascade
            // (View.resolveRtlPropertiesIfNeeded + per-instance dedup
            // via View.isLayoutDirectionResolved), invoked above via
            // resolveTreeRtl(child) BEFORE child.measure runs. That
            // gives CL's solver the resolved LayoutParams fields it
            // needs in applyConstraintsFromLayoutParams, so widget.X
            // is set correctly by the solver itself -- no post-pass
            // hack required.
        } catch (Throwable t) {
            NativeLog.w(TAG, "layoutGridManually failed: " + t);
        }
    }

    /** v1.0.123: recursive RTL-resolution helper. Walks the view tree
     *  and invokes `View.resolveRtlPropertiesIfNeeded()` on each node
     *  so each child's LayoutParams.resolveLayoutDirection runs --
     *  which for ConstraintLayout.LayoutParams converts the inflated
     *  `startToEnd` / `topToBottom` / `endToEnd` attrs into absolute
     *  `mResolvedLeftToRight` / `mResolvedRightToRight` fields. The
     *  androidx CL solver reads those resolved fields in
     *  `applyConstraintsFromLayoutParams` during its onMeasure; if
     *  any of them is UNSET (because resolveLayoutDirection wasn't
     *  called), `widget.immediateConnect` is skipped for that anchor,
     *  the LinearSystem sees no left-edge constraint, and the solver
     *  minimizes widget.X to 0.
     *
     *  This is called from layoutGridManually BEFORE child.measure
     *  triggers CL.onMeasure, so the resolved fields are populated
     *  by the time CL's setChildrenConstraints reads them.
     *
     *  Generic across any APK using locale-aware constraint attrs --
     *  no per-APK code. Matches the AOSP measure-time cascade
     *  described at cs.android.com/.../view/View.java
     *  (resolveRtlPropertiesIfNeeded -> resolveLayoutDirection +
     *  resolveLayoutParams) but invoked from the driver rather than
     *  from View.measure itself because invoking it from View.measure
     *  triggers a re-layout cascade through the RV invalidation
     *  chain (every measure pass re-fires layoutGridManually).
     *
     *  Per-NODE dedup lives inside View.resolveRtlPropertiesIfNeeded
     *  (the rtlPropertiesResolved one-shot), so each node pays the
     *  constraint-resolve cost at most once; the walk itself always
     *  recurses so a newly-grafted (ViewStub.inflate / addView) subtree
     *  under an already-resolved ancestor still gets resolved. See the
     *  v1.0.496 note in the body for why early-returning on a resolved
     *  node (skipping recursion) silently collapsed XML percent
     *  Guidelines on dynamically-added ConstraintLayouts.
     */
    private static void resolveTreeRtl(View v) {
        if (v == null) return;
        // Resolve THIS node (no-op when already resolved -- the one-shot
        // lives inside View.resolveRtlPropertiesIfNeeded via the
        // rtlPropertiesResolved flag), then ALWAYS recurse into children.
        //
        // v1.0.496: do NOT early-return on an already-resolved node and
        // skip the recursion (the prior "fast path" did). A later
        // ViewStub.inflate() / addView() can graft NEW, unresolved
        // children under an already-resolved ancestor (e.g. Duolingo's
        // IntroFlowFragment ViewStub-inflates its intro ConstraintLayout
        // long after the activity root was first resolved). Once rootView
        // (or any ancestor) was resolved on an early frame, the old early
        // return made this whole pass a no-op forever, so those new
        // descendants' LayoutParams never got resolveLayoutDirection().
        // For a CL child that means LayoutParams.mResolvedGuidePercent /
        // mResolvedGuideBegin/End stay UNSET; with SDK_INT>=17 (we report
        // 29) ConstraintLayout.setChildrenConstraints reads the RESOLVED
        // field, not the raw guidePercent, so an XML percent-Guideline
        // gets setGuidePercent() never called and collapses to the origin
        // -- the bottom-anchored chain (Lottie + buttons) then lands at
        // negative Y, off-screen -> blank intro. Same class of bug for
        // any locale-aware start/end constraint on a dynamically-added
        // subtree. AOSP's ViewGroup.resolveLayoutParams() ALWAYS recurses
        // into every child for exactly this reason; the per-node dedup is
        // what keeps the always-recurse walk cheap (each node resolves at
        // most once until resetRtlProperties()).
        try { v.resolveRtlPropertiesIfNeeded(); } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                resolveTreeRtl(vg.getChildAt(i));
            }
        }
    }

    /** v1.0.109 diagnostic: walk the first card's view tree, find any
     *  ConstraintLayout, and dump its internal widget graph state.
     *  Gated behind MACAPK_DUMP_CL_WIDGETS=1 so it doesn't spam
     *  production logs. The dump shows:
     *    - The CL's `mLayoutWidget` ConstraintWidgetContainer's getX/
     *      Y/Width/Height (the root widget)
     *    - For each CL child: lp.mWidget non-null? widget.getX/Y/
     *      Width/Height (the solver's per-child output)
     *  If widgets exist + widget.getX/Y are 0, the solver didn't run.
     *  If widget.getX/Y are non-zero but child.left/top don't match,
     *  the gap is in ConstraintLayout.onLayout not being invoked. */
    /** Type-driven MotionLayout probe (no field names -- they're
     *  R8-minified). For every view whose class EXTENDS a class named
     *  *.ConstraintLayout (i.e. MotionLayout subclasses), walk every
     *  field in the class chain below CL; any field value owning a
     *  SparseArray field is scene-shaped: dump the SparseArray's
     *  entries and, for each entry (a ConstraintSet), the sizes of
     *  its Map-typed fields (mConstraints keyed by view id). */
    private static void dumpMotionSceneState(View v) {
        if (v == null) return;
        try {
            Class<?> c = v.getClass();
            boolean extendsCL = false;
            for (Class<?> k = c.getSuperclass(); k != null; k = k.getSuperclass()) {
                if (k.getName().endsWith(".ConstraintLayout")) { extendsCL = true; break; }
            }
            if (extendsCL) {
                StringBuilder ints = new StringBuilder();
                for (Class<?> k = c; k != null
                        && !k.getName().endsWith(".ConstraintLayout");
                        k = k.getSuperclass()) {
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        if (f.getType() != int.class) continue;
                        f.setAccessible(true);
                        int iv = f.getInt(v);
                        if (iv != 0) {
                            ints.append(' ').append(k.getSimpleName())
                                .append('.').append(f.getName())
                                .append("=0x").append(Integer.toHexString(iv));
                        }
                    }
                }
                NativeLog.i(TAG, "MOTION probe: " + c.getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(v))
                    + " attached=" + v.isAttachedToWindow()
                    + " ints:[" + ints + ']');
                for (Class<?> k = c; k != null
                        && !k.getName().endsWith(".ConstraintLayout");
                        k = k.getSuperclass()) {
                    for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        if (ft.isPrimitive() || ft.isArray()
                            || ft.getName().startsWith("java.")
                            || ft.getName().startsWith("android.")) continue;
                        f.setAccessible(true);
                        Object scene = f.get(v);
                        if (scene == null) continue;
                        // List-typed scene fields (the Transition list)
                        // + first element's int fields (start/end ids).
                        for (java.lang.reflect.Field lf
                                : scene.getClass().getDeclaredFields()) {
                            if (!java.util.List.class
                                    .isAssignableFrom(lf.getType())) continue;
                            lf.setAccessible(true);
                            Object lv = lf.get(scene);
                            if (!(lv instanceof java.util.List<?> ls)) continue;
                            StringBuilder lb = new StringBuilder("  scene list ")
                                .append(lf.getName()).append(" size=")
                                .append(ls.size());
                            if (!ls.isEmpty() && ls.get(0) != null) {
                                Object tr = ls.get(0);
                                lb.append(" [0]:")
                                  .append(tr.getClass().getSimpleName())
                                  .append('{');
                                for (java.lang.reflect.Field tf
                                        : tr.getClass().getDeclaredFields()) {
                                    if (tf.getType() != int.class) continue;
                                    tf.setAccessible(true);
                                    int tv2 = tf.getInt(tr);
                                    if (tv2 != 0) {
                                        lb.append(tf.getName()).append("=0x")
                                          .append(Integer.toHexString(tv2))
                                          .append(' ');
                                    }
                                }
                                lb.append('}');
                            }
                            NativeLog.i(TAG, lb.toString());
                        }
                        for (java.lang.reflect.Field sf
                                : scene.getClass().getDeclaredFields()) {
                            if (!android.util.SparseArray.class
                                    .isAssignableFrom(sf.getType())) continue;
                            sf.setAccessible(true);
                            Object sa = sf.get(scene);
                            if (!(sa instanceof android.util.SparseArray<?> arr)) continue;
                            StringBuilder sb = new StringBuilder("  scene ")
                                .append(k.getSimpleName()).append('.')
                                .append(f.getName()).append(':')
                                .append(scene.getClass().getSimpleName())
                                .append(" sparse ").append(sf.getName())
                                .append(" size=").append(arr.size());
                            for (int i = 0; i < arr.size() && i < 4; i++) {
                                Object entry = arr.valueAt(i);
                                sb.append(" [0x")
                                  .append(Integer.toHexString(arr.keyAt(i)))
                                  .append("]=");
                                if (entry == null) { sb.append("null"); continue; }
                                sb.append(entry.getClass().getSimpleName());
                                for (java.lang.reflect.Field ef
                                        : entry.getClass().getDeclaredFields()) {
                                    ef.setAccessible(true);
                                    if (java.util.Map.class
                                            .isAssignableFrom(ef.getType())) {
                                        Object mp = ef.get(entry);
                                        if (mp instanceof java.util.Map<?, ?> m2) {
                                            sb.append('{').append(ef.getName())
                                              .append('=').append(m2.size()).append('}');
                                        }
                                    } else if (ef.getType() == String.class) {
                                        Object sv = ef.get(entry);
                                        if (sv != null) {
                                            sb.append('{').append(ef.getName())
                                              .append("='").append(sv).append("'}");
                                        }
                                    }
                                }
                            }
                            NativeLog.i(TAG, sb.toString());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpMotionSceneState(vg.getChildAt(i));
            }
        }
    }

    private static void dumpConstraintLayoutWidgets(View root) {
        if (root == null) return;
        try {
            java.util.List<View> cls = new java.util.ArrayList<>();
            collectByClassName(root, ".ConstraintLayout", cls);
            for (View cl : cls) {
                StringBuilder sb = new StringBuilder("CL@");
                sb.append(Integer.toHexString(System.identityHashCode(cl)));
                sb.append(" bounds=[").append(cl.getLeft()).append(',').append(cl.getTop())
                  .append('-').append(cl.getRight()).append(',').append(cl.getBottom()).append(']');
                java.lang.reflect.Field fRoot = findField(cl.getClass(), "mLayoutWidget");
                Object rootW = null;
                if (fRoot != null) {
                    fRoot.setAccessible(true);
                    rootW = fRoot.get(cl);
                }
                if (rootW != null) {
                    sb.append(" rootW.X=").append(invokeIntMethod(rootW, "getX"));
                    sb.append(" rootW.Y=").append(invokeIntMethod(rootW, "getY"));
                    sb.append(" rootW.W=").append(invokeIntMethod(rootW, "getWidth"));
                    sb.append(" rootW.H=").append(invokeIntMethod(rootW, "getHeight"));
                }
                NativeLog.i(TAG, sb.toString());
                // v1.0.109: also dump container.getChildren() since
                // lp.mWidget might be a stale reference. If the
                // container's own widget list shows non-zero positions
                // but lp.mWidget shows zeros, they're different
                // instances and we need to use the container's view.
                if (rootW != null) {
                    try {
                        java.lang.reflect.Method getChildrenM = rootW.getClass().getMethod("getChildren");
                        Object kids = getChildrenM.invoke(rootW);
                        if (kids instanceof java.util.List<?> list) {
                            for (int k = 0; k < list.size() && k < 8; k++) {
                                Object w = list.get(k);
                                if (w == null) continue;
                                StringBuilder kb = new StringBuilder("  container.children[");
                                kb.append(k).append("] ");
                                try {
                                    java.lang.reflect.Method dn = w.getClass().getMethod("getDebugName");
                                    Object name = dn.invoke(w);
                                    if (name != null) kb.append("name=").append(name).append(" ");
                                } catch (Throwable ignored) {}
                                kb.append("X=").append(invokeIntMethod(w, "getX"));
                                kb.append(" Y=").append(invokeIntMethod(w, "getY"));
                                kb.append(" W=").append(invokeIntMethod(w, "getWidth"));
                                kb.append(" H=").append(invokeIntMethod(w, "getHeight"));
                                NativeLog.i(TAG, kb.toString());
                            }
                        }
                    } catch (Throwable t) {
                        NativeLog.w(TAG, "container.getChildren dump failed: " + t);
                    }
                }
                // Walk children's widgets
                if (cl instanceof android.view.ViewGroup vg) {
                    int n = vg.getChildCount();
                    for (int j = 0; j < n && j < 8; j++) {
                        View c = vg.getChildAt(j);
                        if (c == null) continue;
                        StringBuilder cb = new StringBuilder("  child[");
                        cb.append(j).append("]=").append(c.getClass().getSimpleName())
                          .append("@").append(Integer.toHexString(System.identityHashCode(c)));
                        int id = c.getId();
                        if (id != 0 && id != -1) cb.append(" id=0x").append(Integer.toHexString(id));
                        cb.append(" bounds=[").append(c.getLeft()).append(',').append(c.getTop())
                          .append('-').append(c.getRight()).append(',').append(c.getBottom()).append(']');
                        android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
                        if (lp != null) {
                            cb.append(" lpW=").append(lp.width).append(" lpH=").append(lp.height);
                            java.lang.reflect.Field fW = findField(lp.getClass(), "mWidget");
                            if (fW != null) {
                                fW.setAccessible(true);
                                Object w = fW.get(lp);
                                if (w == null) {
                                    cb.append(" widget=NULL");
                                } else {
                                    cb.append(" widget.X=").append(invokeIntMethod(w, "getX"))
                                      .append(" widget.Y=").append(invokeIntMethod(w, "getY"))
                                      .append(" widget.W=").append(invokeIntMethod(w, "getWidth"))
                                      .append(" widget.H=").append(invokeIntMethod(w, "getHeight"));
                                }
                            }
                            // Also dump all constraint fields (-1 = unset)
                            boolean namedHit = false;
                            for (String fld : new String[] {"leftToLeft","leftToRight","rightToLeft","rightToRight",
                                "topToTop","topToBottom","bottomToTop","bottomToBottom",
                                "startToStart","startToEnd","endToStart","endToEnd",
                                "baselineToBaseline","circleConstraint"}) {
                                try {
                                    java.lang.reflect.Field cf = lp.getClass().getField(fld);
                                    int v = cf.getInt(lp);
                                    namedHit = true;
                                    if (v != -1) cb.append(" ").append(fld).append("=0x").append(Integer.toHexString(v));
                                } catch (Throwable ignored) {}
                            }
                            // Minified-app fallback: R8 renames the LP's
                            // constraint fields. Dump every declared int
                            // field that isn't UNSET(-1)/0 so we can SEE
                            // whether the parse populated anchors (values
                            // in the 0x7f0b id range or PARENT_ID 0), and
                            // dump int fields of any field whose type
                            // looks like the ConstraintWidget (short
                            // minified class name, many int fields).
                            if (!namedHit) {
                                StringBuilder mf = new StringBuilder();
                                for (java.lang.reflect.Field f
                                        : lp.getClass().getDeclaredFields()) {
                                    try {
                                        if (f.getType() == int.class) {
                                            f.setAccessible(true);
                                            int v = f.getInt(lp);
                                            if (v != -1 && v != 0) {
                                                mf.append(' ').append(f.getName())
                                                  .append("=0x")
                                                  .append(Integer.toHexString(v));
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                cb.append(" lpInts:[").append(mf).append(']');
                                for (java.lang.reflect.Field f
                                        : lp.getClass().getDeclaredFields()) {
                                    try {
                                        Class<?> ft = f.getType();
                                        if (ft.isPrimitive() || ft.isArray()
                                            || ft.getName().startsWith("java.")
                                            || ft.getName().startsWith("android.")) continue;
                                        f.setAccessible(true);
                                        Object w = f.get(lp);
                                        if (w == null) continue;
                                        StringBuilder wb = new StringBuilder(" widgetField ")
                                            .append(f.getName()).append(':')
                                            .append(w.getClass().getSimpleName())
                                            .append('{');
                                        int shown = 0;
                                        for (java.lang.reflect.Field wf
                                                : w.getClass().getDeclaredFields()) {
                                            if (wf.getType() != int.class) continue;
                                            try {
                                                wf.setAccessible(true);
                                                int v = wf.getInt(w);
                                                if (v != 0 && shown < 14) {
                                                    wb.append(wf.getName()).append('=')
                                                      .append(v).append(' ');
                                                    shown++;
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                        wb.append('}');
                                        cb.append(wb);
                                        break;
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                        NativeLog.i(TAG, cb.toString());
                    }
                }
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "dumpConstraintLayoutWidgets failed: " + t);
        }
    }

    private static void collectByClassName(View v, String suffix, java.util.List<View> out) {
        if (v == null) return;
        if (v.getClass().getName().endsWith(suffix)) out.add(v);
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) collectByClassName(vg.getChildAt(i), suffix, out);
        }
    }

    private static int invokeIntMethod(Object target, String name) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(name);
            return (int) m.invoke(target);
        } catch (Throwable ignored) {
            return -999999;
        }
    }

    /** Push the RecyclerView's width/height into the LayoutManager
     *  via reflection. AOSP normally writes these in setMeasureSpecs
     *  during the RV.onMeasure pass; we bypass that. Without proper
     *  mWidth/mHeight, GridLayoutManager.calculateItemBorders gets
     *  totalSpace=0 and every cell collapses to default-min width. */
    private static void primeLayoutManagerForLayout(View rv,
                                                    Object layoutManager,
                                                    Object state,
                                                    java.lang.reflect.Field fAdapter) {
        try {
            // Push width/height into LayoutManager
            int w = rv.getRight() - rv.getLeft();
            int h = rv.getBottom() - rv.getTop();
            java.lang.reflect.Field fMW = findField(layoutManager.getClass(), "mWidth");
            java.lang.reflect.Field fMH = findField(layoutManager.getClass(), "mHeight");
            if (fMW != null) { fMW.setAccessible(true); fMW.setInt(layoutManager, w); }
            if (fMH != null) { fMH.setAccessible(true); fMH.setInt(layoutManager, h); }
            // Push width/height MeasureSpec mode so getWidthMode/HeightMode
            // return EXACTLY (not unspecified -> default cells).
            java.lang.reflect.Field fMWM = findField(layoutManager.getClass(), "mWidthMode");
            java.lang.reflect.Field fMHM = findField(layoutManager.getClass(), "mHeightMode");
            int EXACTLY = android.view.View.MeasureSpec.EXACTLY;
            if (fMWM != null) { fMWM.setAccessible(true); fMWM.setInt(layoutManager, EXACTLY); }
            if (fMHM != null) { fMHM.setAccessible(true); fMHM.setInt(layoutManager, EXACTLY); }
            // Push the real item count into State so layoutChunk can
            // walk past position 0. AOSP normally sets mItemCount in
            // dispatchLayoutStep1 from mAdapter.getItemCount.
            if (fAdapter != null && state != null) {
                fAdapter.setAccessible(true);
                Object adapter = fAdapter.get(rv);
                if (adapter != null) {
                    java.lang.reflect.Method getItemCount = findPublicMethod(adapter.getClass(), "getItemCount");
                    int itemCount = (int) getItemCount.invoke(adapter);
                    java.lang.reflect.Field fIC = findField(state.getClass(), "mItemCount");
                    if (fIC != null) { fIC.setAccessible(true); fIC.setInt(state, itemCount); }
                }
            }
            dumpLayoutManagerState(layoutManager, "before-onLayoutChildren");
        } catch (Throwable t) {
            NativeLog.w(TAG, "primeLayoutManagerForLayout failed: " + t);
        }
    }

    /** Diagnostic: log key LayoutManager state. */
    private static void dumpLayoutManagerState(Object lm, String phase) {
        try {
            StringBuilder sb = new StringBuilder("LM(" + phase + ") ");
            sb.append(lm.getClass().getSimpleName());
            for (String field : new String[] { "mWidth", "mHeight", "mWidthMode", "mHeightMode", "mSpanCount" }) {
                java.lang.reflect.Field f = findField(lm.getClass(), field);
                if (f != null) {
                    f.setAccessible(true);
                    sb.append(" ").append(field).append("=").append(f.getInt(lm));
                }
            }
            // mCachedBorders if it exists
            java.lang.reflect.Field fCB = findField(lm.getClass(), "mCachedBorders");
            if (fCB != null) {
                fCB.setAccessible(true);
                int[] borders = (int[]) fCB.get(lm);
                if (borders != null) {
                    sb.append(" mCachedBorders=").append(java.util.Arrays.toString(borders));
                } else {
                    sb.append(" mCachedBorders=null");
                }
            }
            NativeLog.i(TAG, sb.toString());
        } catch (Throwable ignored) {}
    }

    /** v1.0.100: parse `MACAPK_RV_VISIBLE_INDEX` (an integer >= 0,
     *  default 0) which selects the single visible item index when
     *  force-binding a RecyclerView holding multi-page content (a
     *  ViewPager / fragment-tab pattern). All other items get
     *  `View.GONE` so the RV looks like a tab view showing one page
     *  at a time. Useful for previewing tabs without touch dispatch.
     *  Generic: works for any RV holding 2+ children with the same
     *  bound class, not F-Droid-specific. */
    /** v1.0.110: every-frame re-enforcement of tab-pager visibility.
     *  Walks the view tree finding any RecyclerView whose adapter has
     *  >= 2 children of DISTINCT view types and itemCount <= 5 (the
     *  tab-pager heuristic). For each such RV, hides every page-child
     *  whose index != selectedTab (0 by default). F-Droid's
     *  MainViewAdapter is exactly this shape: 5 pages, 5 view types,
     *  one selected at a time.
     *
     *  Without this, CategoriesViewBinder's LiveData observer
     *  re-attaches its layout-inflation root after the initial
     *  setVisibility(GONE) we set during force-bind, leaving both the
     *  Latest and Categories page-FrameLayouts visible at once. */
    private static void enforceTabPagerVisibility(View v) {
        if (v == null) return;
        if (v.getClass().getName().endsWith("RecyclerView")) {
            tryEnforceOnRv(v);
        }
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) enforceTabPagerVisibility(vg.getChildAt(i));
        }
    }

    // PERF1: this runs every frame (called from enforceTabPagerVisibility, which
    // hackEnabled("tabpager") keeps live on the default flag-off path) for every
    // RecyclerView in the tree. fAdapter/getItemCount/getItemViewType now come from
    // the per-Class caches above instead of a fresh getDeclaredField/getMethod walk
    // each time.
    private static void tryEnforceOnRv(View rv) {
        try {
            java.lang.reflect.Field fAdapter = cachedAdapterField(rv.getClass());
            if (fAdapter == null) return;
            Object adapter = fAdapter.get(rv);
            if (adapter == null) return;
            Class<?> ac = adapter.getClass();
            java.lang.reflect.Method getItemCount = cachedGetItemCount(ac);
            if (getItemCount == null) return;
            int n = (int) getItemCount.invoke(adapter);
            if (n < 2 || n > 5) return;
            java.lang.reflect.Method getItemViewType = cachedGetItemViewType(ac);
            if (getItemViewType == null) return;
            java.util.Set<Integer> types = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) types.add((int) getItemViewType.invoke(adapter, i));
            if (types.size() != n) return; // not a tab pager
            int sel = parseSelectedTab();
            if (sel >= n) sel = 0;
            if (!(rv instanceof android.view.ViewGroup rvg)) return;
            int childCount = rvg.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rvg.getChildAt(i);
                if (child == null) continue;
                int desired = (i == sel) ? View.VISIBLE : View.GONE;
                if (child.getVisibility() != desired) child.setVisibility(desired);
            }
        } catch (Throwable ignored) {}
    }

    private static int parseSelectedTab() {
        try {
            String v = ENV_MACAPK_RV_VISIBLE_INDEX;
            if (v == null) return 0;
            int n = Integer.parseInt(v.trim());
            if (n < 0) n = 0;
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static java.lang.reflect.Method findPublicMethod(Class<?> c, String name, Class<?>... paramTypes) {
        try {
            if (paramTypes != null) {
                for (java.lang.reflect.Method m : c.getMethods()) {
                    if (!m.getName().equals(name)) continue;
                    if (m.getParameterCount() != paramTypes.length) continue;
                    Class<?>[] mp = m.getParameterTypes();
                    boolean match = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (paramTypes[i] == null) continue;
                        if (!paramTypes[i].equals(mp[i])) { match = false; break; }
                    }
                    if (match) return m;
                }
            }
            for (java.lang.reflect.Method m : c.getMethods()) {
                if (m.getName().equals(name)) return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void forceLayoutZeroExtentChildren(View v) {
        if (v instanceof android.widget.RelativeLayout) {
            // Children of RelativeLayout were positioned via the rule
            // system; respect those positions even if zero-extent.
            // Still recurse into their subtrees in case grandchildren
            // need fixup.
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = vg.getChildAt(i);
                if (c instanceof android.view.ViewGroup) {
                    forceLayoutZeroExtentChildrenImpl(c);
                }
            }
            return;
        }
        forceLayoutZeroExtentChildrenImpl(v);
    }
    private static void forceLayoutZeroExtentChildrenImpl(View v) {
        if (!(v instanceof android.view.ViewGroup vg)) return;
        int parentW = v.getRight() - v.getLeft();
        int parentH = v.getBottom() - v.getTop();
        if (parentW <= 0 || parentH <= 0) return;
        // v1.0.124: when natural RV dispatch is on (default), skip
        // gridding the RV's children. The LM (LinearLayoutManager /
        // GridLayoutManager) attached them via mChildHelper at
        // proper bounds; gridding them into a sqrt(N) tile mash
        // (60x90 per card for 134 items) destroys that.
        //
        // Still recurse into the children's subtrees so any
        // grandchild (e.g. inner ConstraintLayout) that DOES have
        // zero bounds gets handled.
        if (!"0".equals(ENV_MACAPK_RV_NATURAL)) {
            String fqcn;
            try { fqcn = v.getClass().getName(); } catch (Throwable t) { fqcn = ""; }
            if (fqcn.endsWith(".RecyclerView")
                || fqcn.equals("androidx.recyclerview.widget.RecyclerView")) {
                int n = vg.getChildCount();
                for (int i = 0; i < n; i++) {
                    View c = vg.getChildAt(i);
                    if (c instanceof android.view.ViewGroup) {
                        forceLayoutZeroExtentChildren(c);
                    }
                }
                return;
            }
        }

        // Two-pass: collect zero-extent children, then distribute the
        // parent area among them in a simple grid.
        //
        // v1.0.100: GONE children are explicitly hidden -- if we still
        // count + position them in the grid, a 5-child RecyclerView
        // with 1 visible + 4 GONE ends up dividing into 2x3 cells and
        // the visible child only gets the top-left cell, not the
        // whole parent. Skipping GONE children for both counting and
        // layout makes single-visible-child containers fill their
        // parent (the correct AOSP semantic for vis=GONE in a layout
        // pass: "GONE participates in measure-as-zero but still takes
        // its position from its parent's onLayout"; here we have no
        // real measure/layout pass, so treating GONE as "ignore" is
        // closer to the desired observable result).
        int n = vg.getChildCount();
        int zeroCount = 0;
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            if (c == null) continue;
            if (c.getVisibility() == View.GONE) continue;
            if (c.getRight() <= c.getLeft() || c.getBottom() <= c.getTop()) {
                zeroCount++;
            }
        }
        if (zeroCount > 0) {
            // v1.0.95: choose stack direction from aspect ratio. A
            // BottomNavigationMenuView is 720x112 (wide & thin) and
            // wants 5 items side-by-side. A vertical drawer is
            // tall & thin and wants items top-to-bottom. A near-
            // square container falls back to a sqrt grid. Threshold
            // is 2x: parent more than 2x wider than tall -> horizontal,
            // 2x taller than wide -> vertical, else grid. Generic;
            // works for any AOSP widget whose onLayout was supposed
            // to position children but ran without a measure pass.
            int cols, rows;
            if (parentW >= parentH * 2) {
                cols = zeroCount;
                rows = 1;
            } else if (parentH >= parentW * 2) {
                cols = 1;
                rows = zeroCount;
            } else {
                cols = Math.max(1, (int) Math.round(Math.sqrt(zeroCount)));
                rows = (zeroCount + cols - 1) / cols;
            }
            int cellW = parentW / cols;
            int cellH = parentH / rows;
            int idx = 0;
            for (int i = 0; i < n; i++) {
                View c = vg.getChildAt(i);
                if (c == null) continue;
                if (c.getVisibility() == View.GONE) continue;
                if (c.getRight() <= c.getLeft() || c.getBottom() <= c.getTop()) {
                    int col = idx % cols;
                    int row = idx / cols;
                    int x = col * cellW;
                    int y = row * cellH;
                    c.layout(x, y, x + cellW, y + cellH);
                    idx++;
                }
            }
        }
        // Recurse so subclass-laid-out children (e.g. ConstraintLayout
        // children that got real coords) also get their grandchildren
        // checked.
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.view.ViewGroup) {
                forceLayoutZeroExtentChildren(c);
            }
        }
    }

    /** v1.0.101: post-layout gravity pass. Walks the view tree; for any
     *  child whose LayoutParams.macapkGravity is non-zero (set from
     *  XML `layout_gravity` during inflation) AND whose current bounds
     *  cover the parent's entire extent (a sign the parent's onLayout
     *  did nothing positional), re-position the child inside its
     *  parent's bounds using the gravity bits.
     *
     *  Also applies a Material-default BOTTOM|END gravity to any class
     *  whose simple name ends in "FloatingActionButton" if gravity
     *  wasn't otherwise set. Material's FAB Behavior in CoordinatorLayout
     *  defaults to bottom-end with a 16dp margin -- without this, a
     *  FAB inflated under an APK-bundled CoordinatorLayout (which
     *  doesn't run measure with our framework) inherits the parent's
     *  full extent and the cap-fallback in ImageView.onDraw renders
     *  the FAB icon centered in the entire content area instead of
     *  in the corner where the user expects it.
     *
     *  Generic for any CoordinatorLayout / FrameLayout / custom-ViewGroup
     *  tree -- no per-APK code, no class-name allowlist beyond the
     *  Material FAB default.
     *
     *  Sizing rule, in priority order:
     *    1. LayoutParams.width/height in pixels (> 0)
     *    2. drawable.getIntrinsicWidth/Height + 2 * padding (when the
     *       view has a drawable and we'd otherwise oversize it)
     *    3. 56dp (FAB / SnackBar / dialog default)
     *  Default outer margin is 16dp. v1.0.126: dimensions route through
     *  dp() helper that reads the View's actual DisplayMetrics.density,
     *  so 56dp -> 112px on the default xhdpi (d=2) phone preset, 168px
     *  on xxhdpi (d=3), etc. Previously hardcoded at density=3. */
    private static void applyLayoutGravity(View v) {
        applyLayoutGravityRec(v, new java.util.IdentityHashMap<>());
    }

    /** v1.0.395: FOCUSED FloatingActionButton sizing + positioning for the
     *  real-loop path (where the broad `gravity` hack is retired). Material's
     *  FAB lives in a CoordinatorLayout whose Behavior anchors it BOTTOM|END
     *  with a 16dp margin; our CoordinatorLayout doesn't run that Behavior,
     *  so the FAB lays out at its measured size in the parent's TOP-LEFT corner
     *  (or [0,0-0,0] when unmeasured) -- the wrong place either way. For each FAB
     *  we measure it to its Material default (childDesiredWidth/Height = 56dp) and
     *  anchor it at its layout_gravity (childGravity falls back to BOTTOM|END for
     *  FABs) with a 16dp default margin, EVERY layout pass (idempotent). The
     *  measure also populates getMeasuredWidth/Height so Material's
     *  FloatingActionButton.onTouchEvent (which hit-tests against
     *  getMeasuredContentRect) accepts taps. The FAB's chrome
     *  (View.macapkPaintMaterialChrome) + icon then render in the corner and the
     *  search tap reaches the FAB. Generic; keys only on the Material FAB class
     *  name, no per-APK data. RETIRE when the CoordinatorLayout FAB Behavior runs
     *  in the real measure/layout pass. */
    /** One-shot per class: the retirement evidence for the rescue below. */
    private static final java.util.Set<String> sFabRescueLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void positionFloatingActionButtons(View root) {
        if (root == null) return;
        positionFabsRec(root, new java.util.IdentityHashMap<>());
    }
    private static void positionFabsRec(View v,
                                        java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return;
        if (visited.put(v, Boolean.TRUE) != null) return;
        if (!(v instanceof android.view.ViewGroup vg)) return;
        int parentW = vg.getRight() - vg.getLeft();
        int parentH = vg.getBottom() - vg.getTop();
        if (parentW > 0 && parentH > 0) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c == null || c.getVisibility() == View.GONE) continue;
                String name;
                try { name = c.getClass().getSimpleName(); } catch (Throwable t) { continue; }
                if (name == null || !name.endsWith("FloatingActionButton")) continue;
                // ⚠ GATED v1.0.930 TO DEGENERATE-ONLY. Ungated, this pass was a
                // CORRUPTION and not a no-op: it overrode EVERY FAB in the tree
                // with BOTTOM|END + 16dp of its immediate parent, every frame.
                // Audited over the corpus' 94 decoded FAB elements -- 24 sit in a
                // LinearLayout (organicmaps' zoom pair and anki's 4-FAB speed-dial
                // COLLAPSE onto one rect), 14 in a ConstraintLayout (deskclock's
                // fab is constrained Start_toStartOf AND End_toEndOf = bottom
                // CENTRE, and we dragged it to bottom-right), 8 in a
                // RelativeLayout, 21 in a CoordinatorLayout whose `layout_anchor`
                // we ignored. And because the match is
                // simpleName.endsWith("FloatingActionButton") it also squashed 9
                // ExtendedFloatingActionButton / custom-FAB instances to a 56dp
                // square, ignoring fabCustomSize and app:fabSize.
                //
                // The header's premise was false: we ship NO CoordinatorLayout
                // (runtime/java/androidx/ is window-extensions only) and all 15
                // corpus apps with a FAB bundle their own, so the Behavior is the
                // APP's real code and it runs. The FAB's actual bug was
                // View.setBackgroundDrawable missing AOSP's identity early-return,
                // fixed in v1.0.929.
                //
                // So act ONLY on a FAB the real layout pass left with no usable
                // geometry -- the one case where the parent's answer cannot be
                // better than ours, and where a [0,0-0,0] child would otherwise
                // hit-test as FULLSCREEN (ViewGroup.macapkChildFillsParent).
                // The measured-dimension half of the test is deliberate insurance:
                // Material's FAB.onTouchEvent rejects a DOWN outside
                // getTouchTargetRect() -> getMeasuredContentRect() =
                // [0,0,getMeasuredWidth,getMeasuredHeight], which has NO isLaidOut
                // escape in 12 of the 15 corpus apps, so a FAB with real bounds but
                // zero measured dims would silently reject every tap.
                int cw = c.getRight() - c.getLeft();
                int ch2 = c.getBottom() - c.getTop();
                if (cw > 0 && ch2 > 0
                        && c.getMeasuredWidth() > 0 && c.getMeasuredHeight() > 0) continue;
                if (sFabRescueLogged.add(name)) {
                    android.util.Log.i("Mac.apk.ViewCanvasDriver",
                        "FAB rescue fired for " + name + " (bounds " + cw + "x" + ch2
                        + ", measured " + c.getMeasuredWidth() + "x" + c.getMeasuredHeight()
                        + ") -- if this is never seen across the fleet, this pass is"
                        + " dead code and should be DELETED, not kept.");
                }
                // v1.0.397: ALWAYS anchor the FAB to its layout_gravity, not just
                // when it is degenerate/parent-filling. Our CoordinatorLayout never
                // runs Material's FAB Behavior, so onLayout places the FAB at its
                // measured size in the parent's TOP-LEFT corner -- a non-degenerate
                // but WRONG position (the owner's "search icon on the wrong side").
                // The old "only re-place a degenerate FAB" gate skipped this once
                // the FAB was correctly measured to 56dp, leaving it at [0,0]. We
                // recompute the gravity anchor every layout pass (idempotent:
                // setLeftTopRightBottom to the same rect is a no-op), so the FAB
                // sits bottom-end where its layout_gravity asks AND the touch
                // hit-test lands on it.
                int g = childGravity(c);
                if (g == android.view.Gravity.NO_GRAVITY)
                    g = android.view.Gravity.BOTTOM | android.view.Gravity.END;
                int desiredW = childDesiredWidth(c, parentW);
                int desiredH = childDesiredHeight(c, parentH);
                int mL = 0, mT = 0, mR = 0, mB = 0;
                android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                    mL = mlp.leftMargin; mT = mlp.topMargin;
                    mR = mlp.rightMargin; mB = mlp.bottomMargin;
                }
                if (mL == 0 && mR == 0 && mT == 0 && mB == 0) {
                    int dm = dp(c, 16);   // Material FAB Behavior default margin
                    mL = mR = mT = mB = dm;
                }
                // The PARENT's resolved direction, as FrameLayout.layoutChildren uses.
                int abs = android.view.Gravity.getAbsoluteGravity(g, vg.getLayoutDirection());
                android.graphics.Rect container = new android.graphics.Rect(
                    mL, mT, parentW - mR, parentH - mB);
                android.graphics.Rect out = new android.graphics.Rect();
                android.view.Gravity.apply(abs, desiredW, desiredH, container, out);
                // v1.0.397: measure the FAB to its laid-out size BEFORE setting
                // bounds. We never ran the CoordinatorLayout FAB Behavior, so the
                // FAB was never measured -- getMeasuredWidth()/Height() stayed 0.
                // Material's FloatingActionButton.onTouchEvent rejects a DOWN whose
                // point is outside getMeasuredContentRect() (= [0,0,measuredW,
                // measuredH] minus shadow padding, expanded by touchTargetPadding);
                // with measured dims 0 that rect is empty, so EVERY tap fell through
                // to the card behind it (the owner's "search FAB does nothing").
                // EXACTLY specs make Material's FAB.onMeasure setMeasuredDimension to
                // our size, so its real touch-area check now accepts taps -> the
                // FAB's own onClickListener fires (opens search). Generic: keyed on
                // the FAB class, no per-APK code.
                try {
                    c.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            out.right - out.left, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            out.bottom - out.top, android.view.View.MeasureSpec.EXACTLY));
                } catch (Throwable ignored) {}
                // v1.0.930: layout(), NOT setLeftTopRightBottom. The rescue now
                // only runs on a view the layout pass left degenerate, and
                // layout() is what sets mIsLaidOut (Material's other hit-test
                // form, getContentRect(), returns false when !isLaidOut) and what
                // back-fills measuredWidth/Height from the layout size when they
                // are zero (View.layout). setLeftTopRightBottom does neither.
                c.layout(out.left, out.top, out.right, out.bottom);
            }
        }
        for (int i = 0; i < vg.getChildCount(); i++)
            positionFabsRec(vg.getChildAt(i), visited);
    }

    // v1.0.402: AppBarLayouts already grown this layout cycle, keyed by
    // identity -> the height we grew them TO. A full traversal resets the
    // appbar to its ungrown height (rootView.layout re-runs the bundled
    // CoordinatorLayout.onLayout), so on the next full frame the height no
    // longer matches the stored grown value and we grow again -- idempotent
    // across full frames, and a no-op if a stray call sees an
    // already-grown appbar (height == stored).
    private final java.util.WeakHashMap<View, Integer> grownAppBars =
        new java.util.WeakHashMap<>();

    /** Average the ARGB of one pixel row of the rendered bitmap. Used to
     *  let the status bar blend with the content directly beneath it.
     *  Returns 0 (transparent) when the row is empty/out of range so the
     *  status bar falls back to its themed strip. */
    private static int sampleRowDominant(int[] px, int w, int h, int y) {
        if (px == null || w <= 0 || y < 0 || y >= h) return 0;
        long sr = 0, sg = 0, sb = 0; int n = 0;
        int base = y * w;
        // Sample every few px across the width (cheap; the header is a
        // broad solid/gradient region, so a subsample is representative).
        for (int x = 0; x < w; x += 4) {
            int p = px[base + x];
            if ((p >>> 24) == 0) continue; // skip transparent
            sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF; n++;
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((int)(sr / n) << 16) | ((int)(sg / n) << 8) | (int)(sb / n);
    }

    /** Grow a fitsSystemWindows AppBarLayout (+ its CollapsingToolbarLayout
     *  / FeatureImage subtree) by the status-bar height and shift the
     *  CoordinatorLayout's scrolling sibling(s) down by the same amount.
     *  Mirrors AOSP CollapsingToolbarLayout.onMeasure adding the top window
     *  inset; our inset dispatch doesn't drive the bundled Material code, so
     *  we do it here. Generic: keyed on the AppBarLayout/CoordinatorLayout
     *  class names + fitsSystemWindows, never a package. */
    private void growFitsSystemWindowsAppBar(View root) {
        int statusH = StatusBarDriver.heightPx(root);
        if (statusH <= 0) return;
        growAppBarRec(root, statusH, new java.util.IdentityHashMap<>());
    }
    private void growAppBarRec(View v, int statusH, java.util.Map<View, Boolean> visited) {
        if (v == null || visited.put(v, Boolean.TRUE) != null) return;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            String cn;
            try { cn = v.getClass().getSimpleName(); } catch (Throwable t) { cn = ""; }
            if (cn.endsWith("CoordinatorLayout")) {
                // Find the fitsSystemWindows AppBarLayout child.
                View appbar = null;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View c = vg.getChildAt(i);
                    if (c == null) continue;
                    String ccn;
                    try { ccn = c.getClass().getSimpleName(); } catch (Throwable t) { ccn = ""; }
                    if (ccn.endsWith("AppBarLayout") && c.getFitsSystemWindows()) { appbar = c; break; }
                }
                if (appbar != null) {
                    int curH = appbar.getBottom() - appbar.getTop();
                    Integer grownTo = grownAppBars.get(appbar);
                    if (grownTo == null || grownTo.intValue() != curH) {
                        // Ungrown (fresh layout): grow appbar bottom by statusH,
                        // grow its fill-children, and shift siblings below it down.
                        int oldBottom = appbar.getBottom();
                        int newBottom = oldBottom + statusH;
                        appbar.setLeftTopRightBottom(appbar.getLeft(), appbar.getTop(),
                            appbar.getRight(), newBottom);
                        growFillDescendants(appbar, oldBottom - appbar.getTop(), statusH);
                        grownAppBars.put(appbar, newBottom - appbar.getTop());
                        // Shift CoordinatorLayout children that sit BELOW the appbar
                        // (the scrolling content) down by statusH.
                        for (int i = 0; i < vg.getChildCount(); i++) {
                            View c = vg.getChildAt(i);
                            if (c == null || c == appbar) continue;
                            if (c.getTop() >= oldBottom - 8) {
                                c.setLeftTopRightBottom(c.getLeft(), c.getTop() + statusH,
                                    c.getRight(), c.getBottom());
                            }
                        }
                    }
                    return; // don't recurse into a handled appbar tree
                }
            }
            for (int i = 0; i < vg.getChildCount(); i++)
                growAppBarRec(vg.getChildAt(i), statusH, visited);
        }
    }
    /** Grow descendants of `parent` whose bottom reaches the parent's old
     *  content height (i.e. they FILL it -- the CollapsingToolbarLayout and
     *  its match_parent FeatureImage) by `delta`, so the hero image covers
     *  the taller header. */
    private void growFillDescendants(View parent, int oldFillH, int delta) {
        if (!(parent instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup vg = (android.view.ViewGroup) parent;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c == null) continue;
            int ch = c.getBottom() - c.getTop();
            // A child that fills (or nearly fills) the parent's old height.
            if (c.getTop() == 0 && ch >= oldFillH - 2) {
                c.setLeftTopRightBottom(c.getLeft(), c.getTop(), c.getRight(),
                    c.getBottom() + delta);
                growFillDescendants(c, ch, delta);
            }
        }
    }

    /** v1.0.126: dp -> px using the View's actual Resources display
     *  metrics. The driver's FAB / nav-pill / default-margin helpers
     *  previously hardcoded density=3 (`168`, `48`, `192`, `96`) while
     *  card-spacing hardcoded density=2 (`16`); the inconsistency
     *  oversized FAB chrome by 50% on the 720x1280 phone preset whose
     *  DisplayMetrics actually reports density=2.0. AOSP equivalent is
     *  `TypedValue.applyDimension(COMPLEX_UNIT_DIP, dp, dm)`. Falls back
     *  to density=2.0 if the View has no Context yet (unattached). */
    private static int dp(View v, float dp) {
        float density = android.util.DisplayMetrics.macapkDeviceDensity();
        if (v != null) {
            try {
                android.content.res.Resources r = v.getResources();
                if (r != null) {
                    android.util.DisplayMetrics dm = r.getDisplayMetrics();
                    if (dm != null && dm.density > 0f) density = dm.density;
                }
            } catch (Throwable ignored) {}
        }
        return Math.round(dp * density);
    }
    private static void applyLayoutGravityRec(View v,
                                              java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return;
        if (visited.put(v, Boolean.TRUE) != null) return;
        if (!(v instanceof android.view.ViewGroup vg)) return;
        int parentW = v.getRight() - v.getLeft();
        int parentH = v.getBottom() - v.getTop();
        if (parentW <= 0 || parentH <= 0) {
            // Still recurse for child ViewGroups that themselves have
            // real bounds.
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                applyLayoutGravityRec(c, visited);
            }
            return;
        }
        int n = vg.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            if (c == null) continue;
            if (c.getVisibility() == View.GONE) continue;
            int g = childGravity(c);
            if (g == android.view.Gravity.NO_GRAVITY) continue;
            // Reposition the child if its bounds look like a default
            // ViewGroup.onLayout result (either: fills parent because
            // single-child FrameLayout, OR was given a half-parent slice
            // by the equal-divide-vertical fallback when multiple
            // siblings exist). v1.0.117: previously required fillsParent
            // in BOTH dimensions, but FAB siblings get split vertically
            // (720x584 instead of 720x1168), leaving FAB at half-height
            // and skipped. Detect "filled at least one dimension OR has
            // WRAP_CONTENT LayoutParams" as the signal to reposition.
            int cw = c.getRight() - c.getLeft();
            int ch = c.getBottom() - c.getTop();
            android.view.ViewGroup.LayoutParams clp = c.getLayoutParams();
            boolean defaultLaidOut =
                (cw >= parentW && ch >= parentH)            // fills both
                || (cw >= parentW)                          // fills width (vertical split)
                || (ch >= parentH)                          // fills height
                || (clp != null
                    && (clp.width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        || clp.height == android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    && cw > 0 && ch > 0);
            if (!defaultLaidOut) continue;
            int desiredW = childDesiredWidth(c, parentW);
            int desiredH = childDesiredHeight(c, parentH);
            int marginL = 0, marginT = 0, marginR = 0, marginB = 0;
            android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                marginL = mlp.leftMargin; marginT = mlp.topMargin;
                marginR = mlp.rightMargin; marginB = mlp.bottomMargin;
            }
            // Default margin -- Material 3 spec is 16dp on all sides
            // for a FAB inside a CoordinatorLayout. v1.0.126: was
            // hardcoded `48` (= 16dp at density=3) which oversized the
            // chrome's stand-off from screen edges; route through the
            // density-aware dp() helper so we get 32px at density=2.
            if (marginL == 0 && marginR == 0 && marginT == 0 && marginB == 0) {
                int defaultMargin = dp(c, 16);
                marginL = marginR = marginT = marginB = defaultMargin;
            }
            // Resolve START/END against the PARENT's resolved direction, as
            // FrameLayout.layoutChildren does. This assumed LTR, so under an RTL
            // locale it dragged every bottom|end FAB (WhatsApp's new-chat and
            // Meta AI buttons) back to the right edge after the real layout pass
            // had put it on the left.
            int abs = android.view.Gravity.getAbsoluteGravity(g, vg.getLayoutDirection());
            android.graphics.Rect container = new android.graphics.Rect(
                marginL, marginT, parentW - marginR, parentH - marginB);
            android.graphics.Rect out = new android.graphics.Rect();
            android.view.Gravity.apply(abs, desiredW, desiredH, container, out);
            c.layout(out.left, out.top, out.right, out.bottom);
        }
        // Recurse.
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            applyLayoutGravityRec(c, visited);
        }
    }

    /** Resolve the gravity for a child. Reads LayoutParams.macapkGravity
     *  first; if zero AND the class looks like a FAB, returns
     *  BOTTOM|END as the Material default. */
    private static int childGravity(View c) {
        android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
        int g = (lp != null) ? lp.macapkGravity : android.view.Gravity.NO_GRAVITY;
        if (g != android.view.Gravity.NO_GRAVITY) return g;
        String name = c.getClass().getSimpleName();
        if (name.endsWith("FloatingActionButton") || name.equals("FloatingActionButton")) {
            return android.view.Gravity.BOTTOM | android.view.Gravity.END;
        }
        return android.view.Gravity.NO_GRAVITY;
    }

    private static int childDesiredWidth(View c, int parentW) {
        android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (lp != null && lp.width > 0) return Math.min(lp.width, parentW);
        // WRAP_CONTENT (-2) or MATCH_PARENT (-1) -> use intrinsic / FAB default.
        // v1.0.126: density-aware. Was `iw + 64` (32dp padding only at
        // d=2) and `168` (56dp only at d=3); same expression now scales
        // with the View's actual DisplayMetrics. Material spec for a
        // standard FAB is 56dp body, icon drawable 24dp centered with
        // 16dp padding each side (= 32dp total).
        if (c instanceof android.widget.ImageView iv) {
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d != null && d.getIntrinsicWidth() > 0) {
                int iw = d.getIntrinsicWidth();
                return Math.min(parentW, Math.max(iw + dp(c, 32), dp(c, 56)));
            }
        }
        return Math.min(parentW, dp(c, 56));
    }

    /** v1.0.129: now a no-op stub. The Material 3 active-indicator
     *  pill paint moved to View.macapkPaintMaterialChrome on the
     *  NavigationBarItemView class match -- painting from inside the
     *  item's own draw chain gets the right z-order (after the
     *  BottomNavigationView's surfaceContainer bg paint, before the
     *  item's icon/label). Painting from here before rootView.draw was
     *  getting erased by the BottomNav's later bg paint.
     *
     *  Kept as a stub so the call site in macapkDrawFrame stays
     *  valid; deleting the call would require touching the canvas-
     *  prep block. The stub allocation cost (one IdentityHashMap +
     *  one method call per frame) is negligible. Future cleanup can
     *  inline the deletion when the surrounding code is next touched.
     */
    private static void paintNavSelectedPills(View v, android.graphics.Canvas canvas,
                                              int absX, int absY,
                                              java.util.IdentityHashMap<View, Boolean> visited) {
        // No-op: chrome painting moved to View.macapkPaintMaterialChrome.
    }

    /** A NavigationBarItemView is selected when its SECOND text label
     *  (typically the "active" label, with selected typography) is
     *  visible AND the first is invisible -- this is the exact pattern
     *  Material's NavigationBarItemView uses to swap label styles
     *  per-state. Falls back to "look for ANY visible TextView and
     *  treat the first one as the selected marker" if the tree shape
     *  differs. Returns false if no TextView children at all. */
    private static boolean isNavItemSelected(View v) {
        if (!(v instanceof android.view.ViewGroup vg)) return false;
        // Find the BaselineLayout child (contains the labels).
        android.view.ViewGroup labels = null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c != null && c.getClass().getSimpleName().equals("BaselineLayout")
                && c instanceof android.view.ViewGroup vc) {
                labels = vc;
                break;
            }
        }
        if (labels == null) return false;
        // Walk the BaselineLayout's children for TextViews.
        android.widget.TextView label0 = null, label1 = null;
        for (int i = 0; i < labels.getChildCount(); i++) {
            View c = labels.getChildAt(i);
            if (c instanceof android.widget.TextView tv) {
                if (label0 == null) label0 = tv;
                else { label1 = tv; break; }
            }
        }
        if (label0 == null) return false;
        if (label1 == null) {
            // Single label: selected if visible.
            return label0.getVisibility() == View.VISIBLE;
        }
        // Two labels: selected if the second is visible AND first is invisible.
        // (Material's NavigationBarItemView swaps via setVisibility per state.)
        return label1.getVisibility() == View.VISIBLE
            && label0.getVisibility() == View.INVISIBLE;
    }

    private static int childDesiredHeight(View c, int parentH) {
        android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (lp != null && lp.height > 0) return Math.min(lp.height, parentH);
        // v1.0.126: density-aware -- see childDesiredWidth note.
        if (c instanceof android.widget.ImageView iv) {
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d != null && d.getIntrinsicHeight() > 0) {
                int ih = d.getIntrinsicHeight();
                return Math.min(parentH, Math.max(ih + dp(c, 32), dp(c, 56)));
            }
        }
        return Math.min(parentH, dp(c, 56));
    }

    private static volatile java.lang.ref.WeakReference<android.view.View> sLastClearRoot;
    private static volatile int sLastClearArgb;

    /** The colour the last frame cleared `root`'s surface to, or `unknown` when
     *  the last frame was not drawn for that root (a dialog / popup window, or
     *  no frame yet). This is the bottom of AOSP's window: on a device the
     *  decor's windowBackground is drawn first and everything else over it. A
     *  GL host clears to 0 (its overlay is additive over the game). */
    public static int macapkFrameClearFor(android.view.View root, int unknown) {
        java.lang.ref.WeakReference<android.view.View> r = sLastClearRoot;
        if (root == null || r == null || r.get() != root) return unknown;
        return sLastClearArgb;
    }

    /** Recurse the View tree looking for a SurfaceView/GLSurfaceView
     *  descendant. Used to pick the per-frame canvas clear color:
     *  transparent when a GL pipeline is rendering behind us, opaque
     *  when this Canvas is the only renderer. Cheap O(N) walk; small
     *  N for the View hierarchies in our test set (10-100 nodes).
     *
     *  v1.0.95: cycle-detection. Some androidx animation paths
     *  (NavigationBarItemView.ActiveIndicatorTransform, RecyclerView
     *  scrap recycling) appear to occasionally produce a self-cycle
     *  in the View tree under our framework. Without an explicit
     *  visited set, this walker would StackOverflowError on the next
     *  per-frame tick and burn the renderer indefinitely. We track
     *  visited nodes via a HashSet keyed by identity (System
     *  .identityHashCode + reference-equality probe) so a cycle is
     *  detected on revisit and logged exactly once. The TRUE/FALSE
     *  answer stays correct: a cycle that doesn't contain a
     *  SurfaceView returns false. */
    /** The window's decor background colour, AOSP order, resolved against the
     *  theme the ACTIVITY actually set.
     *
     *  `android:windowBackground` is what PhoneWindow.generateLayout installs on
     *  the DecorView; `colorBackground` is a palette role widgets consult, and
     *  the two differ on plenty of real themes. We asked for the second one and
     *  asked the wrong theme, which on NewPipe meant a 695,763 px surface came
     *  out of the platform default instead of the app's own declaration.
     *
     *  The instance theme is tried first and the static application-theme anchor
     *  is kept as the fallback, so an app whose Activity theme is silent (or
     *  whose rootView has no themed Context yet) resolves exactly what it did
     *  before. UNSET is a sentinel rather than a plausible colour, because a
     *  legitimately-resolved value could otherwise be mistaken for "unresolved"
     *  and skip to the next tier.
     *
     *  A non-opaque answer is refused on purpose: this colour is a full-surface
     *  CLEAR on the no-GL path, and clearing to a translucent value leaves the
     *  MTKView's opaque black showing through, which reads as "the app did not
     *  render" (see CASE B above). AOSP composites the decor over the system
     *  background instead, which we have no equivalent of here. */
    private static int macapkDecorBackground(android.view.View rootView) {
        final int UNSET = 0x00000001;
        for (String attr : new String[]{"windowBackground", "colorBackground"}) {
            int c = macapkThemeAttrColor(rootView, attr, UNSET);
            if (c != UNSET && (c & 0xFF000000) != 0) return c;
        }
        return 0xFFFFFFFF;
    }

    /** One theme colour attribute, preferring the view's own Context theme (the
     *  setTheme() cascade) over the static manifest-pinned anchor. */
    private static int macapkThemeAttrColor(android.view.View v, String attr, int fallback) {
        try {
            android.content.Context c = (v != null) ? v.getContext() : null;
            android.content.res.Resources.Theme th = (c != null) ? c.getTheme() : null;
            if (th != null) {
                int r = th.resolveAttrColor(attr, fallback);
                if (r != fallback) return r;
            }
        } catch (Throwable ignored) {}
        try {
            return android.content.res.Resources.macapkThemeColor(attr, fallback);
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static boolean treeHasSurfaceView(android.view.View v) {
        return treeHasSurfaceViewRec(v, new java.util.IdentityHashMap<>());
    }
    private static boolean treeHasSurfaceViewRec(android.view.View v,
                                                 java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return false;
        if (visited.put(v, Boolean.TRUE) != null) {
            if (!ViewCanvasDriverState.warnedTreeCycle) {
                ViewCanvasDriverState.warnedTreeCycle = true;
                NativeLog.w(TAG, "view tree cycle detected at "
                    + v.getClass().getName() + "@" + System.identityHashCode(v)
                    + "; pruning the recursion (treeHasSurfaceView)");
            }
            return false;
        }
        if (v instanceof android.view.SurfaceView) return true;
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                if (treeHasSurfaceViewRec(vg.getChildAt(i), visited)) return true;
            }
        }
        return false;
    }

    /** Diagnostic: print View tree with bounds. Helps figure out why
     *  visible-pixel count is 0 -- typically because children have
     *  zero bounds despite the parent's layout pass running.
     *  Cycle-safe: prints "<cycle to @hash>" instead of recursing. */
    /** MACAPK_MENU_TRACE diagnostic: locate hosted androidx Fragments via the
     *  fragment_container_view_tag set on each fragment's root view, and dump
     *  their menu-state fields (boolean mHasMenu/mMenuVisible/mHidden + int
     *  mState) plus their FragmentManager's int fields (mCurState). Reveals why
     *  FragmentManager.dispatchCreateOptionsMenu skips a fragment. Read-only. */
    private static void dumpFragmentMenuState(View root) {
        if (root == null) return;
        int tagId = 0;
        try {
            android.content.res.Resources r = root.getContext().getResources();
            tagId = r.getIdentifier("fragment_container_view_tag", "id",
                root.getContext().getPackageName());
        } catch (Throwable ignored) {}
        if (tagId == 0) { NativeLog.i(TAG, "MENU-DIAG: no fragment_container_view_tag id"); return; }
        java.util.List<Object> frags = new java.util.ArrayList<>();
        collectFragmentsByTag(root, tagId, frags, new java.util.IdentityHashMap<>());
        NativeLog.i(TAG, "MENU-DIAG: found " + frags.size() + " hosted fragment(s) via tag 0x"
            + Integer.toHexString(tagId));
        for (Object f : frags) {
            StringBuilder sb = new StringBuilder("MENU-DIAG fragment ").append(f.getClass().getName());
            sb.append("\n  fields:");
            Class<?> c = f.getClass();
            int levels = 0;
            while (c != null && c != Object.class && levels < 6) {
                for (java.lang.reflect.Field fld : c.getDeclaredFields()) {
                    Class<?> t = fld.getType();
                    if (t != boolean.class && t != int.class) continue;
                    if (java.lang.reflect.Modifier.isStatic(fld.getModifiers())) continue;
                    try {
                        fld.setAccessible(true);
                        sb.append("\n    ").append(c.getSimpleName()).append('.')
                          .append(fld.getName()).append('=').append(fld.get(f));
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass(); levels++;
            }
            // Dump every reference field of the fragment whose value exposes a
            // no-arg List-returning method (= a FragmentManager, getFragments
            // renamed): its int fields include mCurState, which gates
            // dispatchCreateOptionsMenu (returns false if mCurState < CREATED).
            c = f.getClass(); levels = 0;
            java.util.IdentityHashMap<Object,Boolean> dumped = new java.util.IdentityHashMap<>();
            while (c != null && c != Object.class && levels < 6) {
                for (java.lang.reflect.Field fld : c.getDeclaredFields()) {
                    if (fld.getType().isPrimitive() || fld.getType().isArray()) continue;
                    try {
                        fld.setAccessible(true);
                        Object v = fld.get(f);
                        if (v == null || dumped.put(v, Boolean.TRUE) != null) continue;
                        // The host FragmentManager is a short obfuscated class (e.g. "ch")
                        // that exposes getFragments() (renamed, List return). Views also
                        // expose List getters, so additionally require a short class name.
                        String vcn = v.getClass().getName();
                        boolean shortName = vcn.length() <= 3 && vcn.indexOf('.') < 0;
                        if (!shortName || !exposesListGetter(v.getClass())) continue;
                        sb.append("\n  ref ").append(c.getSimpleName()).append('.')
                          .append(fld.getName()).append(" = ").append(vcn)
                          .append(" ints:");
                        for (java.lang.reflect.Field ff : v.getClass().getDeclaredFields()) {
                            if (ff.getType() != int.class
                                || java.lang.reflect.Modifier.isStatic(ff.getModifiers())) continue;
                            try { ff.setAccessible(true);
                                sb.append(' ').append(ff.getName()).append('=').append(ff.get(v));
                            } catch (Throwable ignored) {}
                        }
                        // List the fragments this manager reports (renamed getFragments).
                        java.util.List<?> kids = firstListGetter(v);
                        if (kids != null) {
                            sb.append("\n      getFragments-> ").append(kids.size()).append(": ");
                            for (Object k : kids) if (k != null)
                                sb.append(k.getClass().getName()).append(' ');
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass(); levels++;
            }
            NativeLog.i(TAG, sb.toString());
        }
    }
    private static boolean exposesListGetter(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
                if (m.getParameterCount() == 0 && m.getReturnType() == java.util.List.class)
                    return true;
            c = c.getSuperclass();
        }
        return false;
    }
    private static java.util.List<?> firstListGetter(Object o) {
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != java.util.List.class) continue;
                try { m.setAccessible(true); Object r = m.invoke(o);
                    if (r instanceof java.util.List) return (java.util.List<?>) r;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }
    private static void collectFragmentsByTag(View v, int tagId, java.util.List<Object> out,
                                              java.util.IdentityHashMap<View,Boolean> seen) {
        if (v == null || seen.put(v, Boolean.TRUE) != null) return;
        try {
            Object t = v.getTag(tagId);
            if (t != null && !out.contains(t)) out.add(t);
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) collectFragmentsByTag(vg.getChildAt(i), tagId, out, seen);
        }
    }

    private static void dumpTree(View v, int depth, StringBuilder sb) {
        dumpTreeRec(v, depth, sb, new java.util.IdentityHashMap<>());
    }
    private static void dumpTreeRec(View v, int depth, StringBuilder sb,
                                    java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return;
        if (visited.put(v, Boolean.TRUE) != null) {
            sb.setLength(0);
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append("<cycle to ").append(v.getClass().getSimpleName())
              .append("@").append(System.identityHashCode(v)).append(">");
            NativeLog.i(TAG, sb.toString());
            return;
        }
        sb.setLength(0);
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append('@')
          .append(Integer.toHexString(System.identityHashCode(v)));
        sb.append(" [").append(v.getLeft()).append(',').append(v.getTop())
          .append('-').append(v.getRight()).append(',').append(v.getBottom())
          .append("] vis=").append(v.getVisibility());
        // Show LayoutParams (request) + measured dims + LinearLayout
        // orientation alongside the laid-out bounds: makes "why did this
        // child collapse to 0" readable at a glance (e.g. a match_parent
        // child whose measured height is 0 because a measure pass aborted).
        try {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) sb.append(" lp=").append(lp.width).append('x').append(lp.height);
            // Padding AND margins, when non-zero. A container can inset its
            // content by EITHER mechanism, and a bounds-only dump shows neither
            // -- so "the inset never applied" and "it applied the other way"
            // were indistinguishable. AppCompat's ActionBarOverlayLayout is
            // exactly this case: it insets content below the action bar without
            // moving any bounds you can see at the parent level.
            if ((v.getPaddingLeft() | v.getPaddingTop()
                    | v.getPaddingRight() | v.getPaddingBottom()) != 0) {
                sb.append(" pad=").append(v.getPaddingLeft()).append(',').append(v.getPaddingTop())
                  .append(',').append(v.getPaddingRight()).append(',').append(v.getPaddingBottom());
            }
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                if ((mlp.leftMargin | mlp.topMargin | mlp.rightMargin | mlp.bottomMargin) != 0) {
                    sb.append(" margins=").append(mlp.leftMargin).append(',').append(mlp.topMargin)
                      .append(',').append(mlp.rightMargin).append(',').append(mlp.bottomMargin);
                }
            }
            // Flag a GENERIC LayoutParams. A custom ViewGroup routinely casts its
            // children's params to its OWN subclass -- AppCompat's
            // ActionBarOverlayLayout.onMeasure does `checkcast i1` before setting
            // the content's topMargin -- and a generic one makes that throw, which
            // a resilient measure guard turns into "the inset silently never
            // applied". A parent-specific subclass is normal and stays quiet; only
            // the two BASE types are reported, because those are the anomaly.
            if (lp != null) {
                // The LayoutParams CLASS, always. A custom ViewGroup routinely
                // casts its children's params to its own subclass before writing
                // geometry (AppCompat's ActionBarOverlayLayout does `checkcast
                // i1` before setting the content's topMargin), so "which class is
                // it" is the first question of any inset/margin bug -- and
                // narrower probes could not answer it: "not a base type" does not
                // mean "the RIGHT subtype". One word per line is worth it.
                sb.append(" lpcls=").append(lp.getClass().getSimpleName());
            }
            sb.append(" meas=").append(v.getMeasuredWidth()).append('x').append(v.getMeasuredHeight());
            // Render-node transform, only when not identity: a view drawn at the
            // wrong SIZE with the right bounds (IG's story tray, 2026-09-29) is a
            // transform or an unfinished animation, never a layout bug.
            if (v.getScaleX() != 1f || v.getScaleY() != 1f)
                sb.append(" scale=").append(v.getScaleX()).append(',').append(v.getScaleY());
            if (v.getAlpha() != 1f) sb.append(" alpha=").append(v.getAlpha());
            if (v.getTranslationX() != 0f || v.getTranslationY() != 0f)
                sb.append(" tr=").append(v.getTranslationX()).append(',').append(v.getTranslationY());
            if (v.getAnimation() != null) sb.append(" anim=").append(v.getAnimation().getClass().getSimpleName());
            if (v instanceof android.widget.LinearLayout ll2)
                sb.append(" ori=").append(ll2.getOrientation());
        } catch (Throwable ignored) {}
        int id = v.getId();
        if (id != 0 && id != -1) sb.append(" id=0x").append(Integer.toHexString(id));
        // v1.0.443 deep-dive: clickability + listener wiring, so we can see WITHOUT
        // synthesizing clicks whether a button is actually interactive (Calculator
        // buttons that never respond would show clk=false/oc=false here).
        try {
            if (v.isClickable() || v.hasOnClickListeners()) {
                sb.append(" clk=").append(v.isClickable())
                  .append(" oc=").append(v.hasOnClickListeners());
            }
            // A disabled view ignores taps too, so a dead button shows why.
            if (!v.isEnabled()) sb.append(" en=false");
        } catch (Throwable ignored) {}
        if (v instanceof android.widget.TextView tv) {
            CharSequence t = tv.getText();
            // KeyLogPolicy.viewText: a label verbatim, an EDITOR's contents
            // (what the user typed -- this dump runs by default at frames
            // 1/60/300) as <redacted:N>, a password field's always.
            sb.append(" text='").append(KeyLogPolicy.viewText(tv, t)).append('\'');
            // Paint state: a transparent color or 0 size explains
            // "model has text but nothing visible" instantly.
            sb.append(" tc=0x").append(
                Integer.toHexString(tv.getCurrentTextColor()));
            sb.append(" ts=").append(tv.getTextSize());
        }
        if (v instanceof android.widget.ImageView iv) {
            android.graphics.drawable.Drawable d = iv.getDrawable();
            sb.append(" drawable=").append(d == null ? "null"
                : d.getClass().getSimpleName() + "(" + d.getIntrinsicWidth() + "x"
                  + d.getIntrinsicHeight() + ")");
        }
        // Background drawable (any view): a filled button whose fill is a
        // custom Drawable (e.g. Duolingo's LipDrawable) shows here -- a null
        // bg on a button with white text explains "invisible filled button".
        try {
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg != null) {
                sb.append(" bg=").append(bg.getClass().getSimpleName());
                // For a selector background, show the current child class -- a
                // filled button whose fill never paints (white-on-white) is
                // usually a StateListDrawable wrapping an app-custom drawable.
                if (bg instanceof android.graphics.drawable.StateListDrawable sld) {
                    android.graphics.drawable.Drawable cur = sld.getCurrent();
                    sb.append("(cur=").append(cur == null ? "null" : cur.getClass().getSimpleName());
                    // Recurse one level into a LayerDrawable to see the leaf fills
                    // (a button whose green fill never paints is usually a leaf
                    // GradientDrawable whose color/tint didn't resolve).
                    if (cur instanceof android.graphics.drawable.LayerDrawable ld) {
                        sb.append('[');
                        for (int li = 0; li < ld.getNumberOfLayers(); li++) {
                            android.graphics.drawable.Drawable lyr = ld.getDrawable(li);
                            sb.append(li > 0 ? "," : "").append(lyr == null ? "null" : lyr.getClass().getSimpleName());
                            if (lyr instanceof android.graphics.drawable.GradientDrawable gd) {
                                sb.append("{fill=0x").append(Integer.toHexString(gd.macapkDumpFillColor()))
                                  .append(" has=").append(gd.macapkDumpHasFill())
                                  .append(" tint=0x").append(Integer.toHexString(gd.macapkDumpTint()))
                                  .append(" strk=0x").append(Integer.toHexString(gd.macapkDumpStroke())).append('}');
                            } else if (lyr instanceof android.graphics.drawable.ShapeDrawable shd) {
                                sb.append("{paint=0x").append(Integer.toHexString(shd.macapkDumpPaintColor()))
                                  .append(" shape=").append(shd.macapkDumpShape())
                                  .append(" a=").append(shd.macapkDumpAlpha())
                                  .append(" tint=0x").append(Integer.toHexString(shd.macapkDumpTint())).append('}');
                            }
                            // Show the layer's bounds too (a 0x0 layer never paints).
                            if (lyr != null) {
                                android.graphics.Rect lb = lyr.getBounds();
                                sb.append('@').append(lb.width()).append('x').append(lb.height());
                            }
                        }
                        sb.append(']');
                    }
                    sb.append(')');
                } else if (bg instanceof android.graphics.drawable.LayerDrawable ld) {
                    // A LayerDrawable background directly (React Native's
                    // CompositeBackgroundDrawable): each layer's class, bounds and,
                    // for an app-custom layer, the colour of its own Paint -- the
                    // three things that decide whether a filled button paints.
                    sb.append('[');
                    for (int li = 0; li < ld.getNumberOfLayers(); li++) {
                        android.graphics.drawable.Drawable lyr = ld.getDrawable(li);
                        sb.append(li > 0 ? "," : "").append(lyr == null ? "null" : lyr.getClass().getSimpleName());
                        if (lyr == null) continue;
                        android.graphics.Rect lb = lyr.getBounds();
                        sb.append('@').append(lb.width()).append('x').append(lb.height());
                        sb.append(macapkDumpPaintColor(lyr));
                    }
                    sb.append(']');
                } else {
                    sb.append(macapkDumpPaintColor(bg));
                }
            }
        } catch (Throwable ignored) {}
        NativeLog.i(TAG, sb.toString());
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                dumpTreeRec(vg.getChildAt(i), depth + 1, sb, visited);
            }
        }
    }

    // Static state class so the warn-once flags persist across the
    /** v1.0.118: walk the tree, find vertical LinearLayouts whose
     *  visible-child bounds have y-gaps (indicating a GONE sibling
     *  is still claiming a slot from the initial equal-divide), and
     *  re-stack them tightly with MATCH_PARENT children getting
     *  whatever's left. Generic across any vertical LinearLayout. */
    private static void fixupVerticalLinearLayouts(View v) {
        if (v == null) return;
        fixupLinearLayoutsRec(v, new java.util.IdentityHashMap<>());
    }

    private static void fixupLinearLayoutsRec(View v,
                                                java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return;
        if (visited.put(v, Boolean.TRUE) != null) return;
        if (!(v instanceof android.view.ViewGroup vg)) return;
        // Recurse FIRST so child LLs are stacked before parents
        // (parents may rely on child measurements after fixup).
        int n = vg.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            fixupLinearLayoutsRec(c, visited);
        }
        // Only handle android.widget.LinearLayout instances explicitly
        // marked VERTICAL. Skip horizontal (BottomNavigation etc.) and
        // skip subclasses that override onLayout (their own logic).
        if (!(vg instanceof android.widget.LinearLayout ll)) return;
        if (ll.getOrientation() != android.widget.LinearLayout.VERTICAL) return;
        // Bail if not laid out yet
        int parentH = vg.getBottom() - vg.getTop();
        int parentW = vg.getRight() - vg.getLeft();
        if (parentH <= 0 || parentW <= 0) return;
        int padT = vg.getPaddingTop();
        int padB = vg.getPaddingBottom();
        int padL = vg.getPaddingLeft();
        int padR = vg.getPaddingRight();
        int availW = parentW - padL - padR;
        int availH = parentH - padT - padB;
        // Collect visible children
        java.util.ArrayList<View> visible = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            if (c == null) continue;
            if (c.getVisibility() == View.GONE) continue;
            visible.add(c);
        }
        if (visible.size() < 2) return;
        // Two trigger conditions:
        //   (a) Y-gap: visible children leave empty space between them
        //       (typically from GONE siblings whose slot wasn't reclaimed)
        //   (b) WRAP_CONTENT vs MATCH_PARENT mis-equal-divide: a wrap-
        //       content child got the same vertical slice as a match-
        //       parent child even though wrap_content children should
        //       be small. F-Droid's LATEST tab LinearLayout has:
        //         ComposeView (wrap_content, ~80px of content)
        //         RecyclerView (match_parent)
        //       and equal-divide gives 292/292 → 280px of ComposeView
        //       whitespace above the cards. Detect by: if any child
        //       has lp.height==WRAP_CONTENT and a sibling has
        //       lp.height==MATCH_PARENT and bounds are roughly equal,
        //       re-stack with WRAP_CONTENT children at a small fixed
        //       size and MATCH_PARENT taking the rest.
        int sumH = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int wrapContentCount = 0, matchParentCount = 0;
        for (View c : visible) {
            int ch = c.getBottom() - c.getTop();
            if (ch > 0) {
                sumH += ch;
                if (c.getTop() < minY) minY = c.getTop();
                if (c.getBottom() > maxY) maxY = c.getBottom();
            }
            android.view.ViewGroup.LayoutParams clp = c.getLayoutParams();
            if (clp != null) {
                if (clp.height == android.view.ViewGroup.LayoutParams.WRAP_CONTENT) wrapContentCount++;
                else if (clp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT) matchParentCount++;
            }
        }
        if (minY == Integer.MAX_VALUE) return;
        int span = maxY - minY;
        boolean hasGap = span > sumH + (visible.size() * 8);
        // Also re-stack if a WRAP_CONTENT child got more space than
        // its natural content — sign that equal-divide gave it too
        // much. F-Droid's LATEST tab LinearLayout has ComposeView
        // (wrap_content, ~80px content) + RV (match_parent). When
        // StatusBanner goes GONE, ComposeView ends up 292 tall via
        // equal-divide of two visible children with 584 px available.
        boolean wrapOverflow = false;
        if (wrapContentCount > 0 && matchParentCount > 0) {
            for (View c : visible) {
                android.view.ViewGroup.LayoutParams clp = c.getLayoutParams();
                if (clp == null || clp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) continue;
                int ch = c.getBottom() - c.getTop();
                // If wrap_content has > 150 px and there's a match_parent
                // sibling, the equal-divide gave the wrap_content too much.
                if (ch > 150) { wrapOverflow = true; break; }
            }
        }
        if (!hasGap && !wrapOverflow) return;
        // RE-STACK.
        // Pass 1: figure out wrap_content children's natural heights.
        // For WRAP_CONTENT, we use measuredHeight if non-zero, otherwise
        // a sensible default (e.g. ComposeView with no measure → 80px).
        java.util.ArrayList<View> matchParent = new java.util.ArrayList<>();
        java.util.HashMap<View, Integer> wrapHeight = new java.util.HashMap<>();
        int fixedH = 0;
        for (View c : visible) {
            android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp != null && lp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                matchParent.add(c);
                continue;
            }
            int ch;
            if (lp != null && lp.height >= 0) {
                ch = lp.height;
            } else if (lp != null && lp.height == android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                // Use measuredHeight when available, else a small
                // default that fits typical compose-view banner
                // / status banner / call-to-action content.
                int measured = c.getMeasuredHeight();
                int curBound = c.getBottom() - c.getTop();
                if (measured > 0 && measured < availH / 2) {
                    ch = measured;
                } else if (curBound > 0 && curBound < availH / 3) {
                    // Already laid out at a sensible small size — trust it.
                    ch = curBound;
                } else {
                    // Conservative fallback: WRAP_CONTENT gets ~80px.
                    // ComposeView call_to_action_banner with no actual
                    // content is ~80px; status banner is similar.
                    ch = 80;
                }
            } else {
                ch = c.getBottom() - c.getTop();
            }
            wrapHeight.put(c, ch);
            fixedH += ch;
        }
        int leftover = Math.max(0, availH - fixedH);
        int perMatch = matchParent.isEmpty() ? 0 : leftover / matchParent.size();
        // Re-position
        int y = padT;
        for (View c : visible) {
            android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
            int mL = 0, mT = 0, mR = 0, mB = 0;
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                mL = mlp.leftMargin; mT = mlp.topMargin;
                mR = mlp.rightMargin; mB = mlp.bottomMargin;
            }
            int ch;
            boolean isMatch = (lp != null && lp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            if (isMatch) {
                ch = Math.max(0, perMatch - mT - mB);
            } else {
                Integer wh = wrapHeight.get(c);
                ch = wh != null ? wh : c.getBottom() - c.getTop();
            }
            int cw = c.getRight() - c.getLeft();
            if (cw <= 0) cw = availW - mL - mR;
            int x = padL + mL;
            y += mT;
            // ALL children: set bounds directly via setLeftTopRightBottom
            // (NOT c.layout) to bypass the onLayout cascade. For
            // RecyclerView specifically, calling layout() would propagate
            // into RV's onLayout which (with our minimal RV path) hits
            // our base ViewGroup equal-divide and resets cards to a
            // 12x12 60x32 tile grid.
            try {
                c.setLeftTopRightBottom(x, y, x + cw, y + ch);
            } catch (Throwable ignored) {
                c.layout(x, y, x + cw, y + ch);
            }
            y += ch + mB;
        }
        // v1.0.119: after re-stacking, re-run layoutGridManually for
        // any RecyclerView child to give cards a chance to grow with
        // the new RV bounds (their CL solver was clamped to the old
        // smaller RV size). Run AFTER the visibility loop completes
        // so RV's bounds are stable.
        //
        // v1.0.124: when MACAPK_RV_NATURAL is on (default), SKIP
        // relayoutRvChildren -- the natural RV.onLayout pump fires
        // from the c.setLeftTopRightBottom-or-c.layout above (which
        // already re-positioned the RV), and our ViewGroup.onLayout
        // now re-fires c.layout on already-positioned children to
        // cascade onLayout into the RV. LM.onLayoutChildren handles
        // the visible-window subset via fill() viewport gate.
        if ("0".equals(ENV_MACAPK_RV_NATURAL)) {
            for (View c : visible) {
                if (c.getClass().getName().endsWith("RecyclerView")) {
                    try { relayoutRvChildren(c); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /** v1.0.118: re-issue layoutGridManually for a RecyclerView whose
     *  children may have been bounds-reset by a recent layout call.
     *  Pulled out for use after fixupVerticalLinearLayouts. */
    private static void relayoutRvChildren(View rv) {
        try {
            java.lang.reflect.Field fAdapter = findField(rv.getClass(), "mAdapter");
            if (fAdapter == null) return;
            fAdapter.setAccessible(true);
            Object adapter = fAdapter.get(rv);
            if (adapter == null) return;
            java.lang.reflect.Method getItemCount = findPublicMethod(adapter.getClass(), "getItemCount");
            if (getItemCount == null) return;
            getItemCount.setAccessible(true);
            int n = (int) getItemCount.invoke(adapter);
            if (n <= 0) return;
            // Don't re-layout tab-pager RVs (2-5 distinct-type items)
            // -- those have their own visibility / positioning logic.
            if (n >= 2 && n <= 5) {
                java.lang.reflect.Method getItemViewType = findPublicMethod(adapter.getClass(), "getItemViewType", int.class);
                if (getItemViewType != null) {
                    getItemViewType.setAccessible(true);
                    java.util.HashSet<Integer> types = new java.util.HashSet<>();
                    for (int i = 0; i < n; i++) types.add((int) getItemViewType.invoke(adapter, i));
                    if (types.size() == n) return; // tab pager
                }
            }
            layoutGridManually(rv, adapter, n);
        } catch (Throwable ignored) {}
    }

    /** v1.0.134: cap on the longer side (in source pixels) for the
     *  chat-display companion PNG written alongside each diagnostic
     *  dump. 2000 px keeps the file size small enough for chat-time
     *  visual verification without sacrificing legibility of glyphs
     *  in the captured frame. */
    private static final int CHAT_DUMP_MAX_DIMENSION = 2000;

    /** v1.0.134: write a downsized copy of `src` to `path`, capping
     *  the longer side at `maxLonger`. If `src` is already small
     *  enough, returns null and writes nothing (the caller has the
     *  full-resolution copy already; a duplicate would waste disk).
     *  Uses Image.SCALE_SMOOTH (high-quality area-averaged) so glyphs
     *  stay readable when reviewed inline in the chat. */
    private static java.io.File writeChatSizedCopy(java.awt.image.BufferedImage src,
                                                    String path,
                                                    int maxLonger) throws java.io.IOException {
        if (src == null || path == null || maxLonger <= 0) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int longer = Math.max(w, h);
        if (longer <= maxLonger) return null; // already chat-sized
        float scale = maxLonger / (float) longer;
        int dw = Math.max(1, Math.round(w * scale));
        int dh = Math.max(1, Math.round(h * scale));
        java.awt.Image scaled = src.getScaledInstance(dw, dh,
            java.awt.Image.SCALE_SMOOTH);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
            dw, dh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.drawImage(scaled, 0, 0, null);
        } finally {
            g.dispose();
        }
        java.io.File outFile = new java.io.File(path);
        javax.imageio.ImageIO.write(out, "png", outFile);
        return outFile;
    }

    /** v1.0.136: complete the WindowInsets dispatch effect. When
     *  Material's BottomNavigationView (or any AppBarLayout / etc.)
     *  consumes the bottom system-bar inset via its
     *  OnApplyWindowInsetsListener, the listener adds the inset to
     *  the view's paddingBottom and calls requestLayout. In real
     *  Android, that requestLayout schedules a full measure+layout
     *  pass; the view's onMeasure runs with the new padding and
     *  reports a TALLER measuredHeight, then the parent
     *  (RelativeLayout / CoordinatorLayout) re-positions it.
     *
     *  In our framework, calling measure() on the tree from outside
     *  the natural inflation flow triggers Material's
     *  BottomNavigationMenuView.onMeasure bug -- it mis-computes
     *  per-child widths (collapsing the first item to 1 px). The
     *  AOSP-equivalent shortcut: bump the consumed view's
     *  measuredHeight by the inset amount via reflection. The next
     *  per-frame layout cascade hits RelativeLayout.onLayout which
     *  reads child.getMeasuredHeight() to position
     *  layout_alignParentBottom children -- so the bar correctly
     *  occupies a taller slot, and `layout_above` siblings (the
     *  RecyclerView in F-Droid's case) automatically shrink to fit
     *  the new gap. Bar's own FrameLayout.onLayout then respects its
     *  paddingBottom for child positioning, leaving the inset
     *  pixels at the bottom unused -- the visual "gesture nav
     *  reserved" strip.
     *
     *  Generic across any APK: keys on `paddingBottom > 0 AND view
     *  is a recognized inset-consumer widget class`. The class-name
     *  match is suffix-based ("*NavigationBarView", "*AppBarLayout"
     *  etc.) and APK-agnostic. */
    private static java.util.Map<View, Integer> grownByInset =
        new java.util.WeakHashMap<>();   // weak: see rvDumped
    private static void growViewsWithBottomInsetPadding(View root, int insetBottom) {
        if (root == null || insetBottom <= 0) return;
        growRec(root, insetBottom);
    }
    private static void growRec(View v, int insetBottom) {
        if (v == null) return;
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) growRec(vg.getChildAt(i), insetBottom);
        }
        String cn;
        try { cn = v.getClass().getName(); }
        catch (Throwable t) { return; }
        boolean isInsetConsumer =
               cn.endsWith("NavigationBarView")
            || cn.endsWith("BottomNavigationView")
            || cn.endsWith("NavigationRailView")
            || cn.endsWith("CoordinatorLayout")
            || cn.endsWith("AppBarLayout");
        if (!isInsetConsumer) return;
        int padBottom = v.getPaddingBottom();
        if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
            NativeLog.i(TAG, "growRec: " + v.getClass().getSimpleName()
                + " padBottom=" + padBottom + " insetBottom=" + insetBottom);
        }
        if (padBottom < insetBottom) return;
        // Idempotency: only bump once per (view, insetBottom) pair.
        Integer alreadyGrew = grownByInset.get(v);
        if (alreadyGrew != null && alreadyGrew == insetBottom) return;
        int grow = Math.min(padBottom, insetBottom);
        if (grow <= 0) return;
        // Bump measuredHeight via reflection. View.measuredHeight is
        // a protected int on the framework View; ViewCanvasDriver is
        // in a different package so we go through the field directly.
        try {
            java.lang.reflect.Field mh = findMeasuredHeightField(v.getClass());
            if (mh == null) return;
            mh.setAccessible(true);
            int currH = mh.getInt(v);
            // The pre-inset measuredHeight is what we add to. If we've
            // grown before with a smaller insetBottom, subtract that
            // first.
            int baseH = currH - (alreadyGrew != null ? alreadyGrew : 0);
            int newH = baseH + grow;
            mh.setInt(v, newH);
            grownByInset.put(v, grow);
            if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
                NativeLog.i(TAG, "growViewsWithBottomInsetPadding "
                    + v.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(v))
                    + " padBottom=" + padBottom + " grew measuredHeight "
                    + currH + " -> " + newH);
            }
        } catch (Throwable t) {
            if (!warnedInsetGrow) {
                warnedInsetGrow = true;
                NativeLog.w(TAG, "growViewsWithBottomInsetPadding poke failed: " + t);
            }
        }
    }
    private static java.lang.reflect.Field findMeasuredHeightField(Class<?> c) {
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("measuredHeight");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }
    private static boolean warnedInsetGrow = false;

    /** v1.0.143: enforce M3 spec BottomNavigationView container
     *  height = 80dp.
     *
     *  Framework-level cause of the gap: F-Droid declares the
     *  M3 dimens (`m3_comp_navigation_bar_container_height=80dp`,
     *  `m3_bottom_nav_min_height=@dimen/m3_comp_..._container_height`)
     *  but our `obtainStyledAttributes` doesn't surface these to
     *  NavigationBarView's ctor when it queries
     *  `mtrl_navigation_bar_min_height` / `android:minHeight`, so the
     *  bar settles at its content-default ~56dp.
     *
     *  v1.0.140 attempted to fix this by poking `measuredHeight` on
     *  the bar only -- the bar grew to 160 but the contained
     *  `BottomNavigationMenuView` stayed cached at 112, so the items
     *  rendered in the top 56dp of an 80dp bar with empty bg below.
     *  Reverted in v1.0.141 in favor of tightening the item layout
     *  inside a natural 56dp bar.
     *
     *  v1.0.143's complete approach:
     *  1. Find the BottomNavigationView/NavigationBarView.
     *  2. Compute target 80dp at the bar's density.
     *  3. `setMinimumHeight(80dp)` so any future onMeasure path
     *     gets the M3 floor.
     *  4. Poke `measuredHeight = 80dp` directly on the bar AND on
     *     its `BottomNavigationMenuView`/`NavigationBarMenuView`
     *     descendant -- so subsequent `RelativeLayout.onLayout`
     *     passes (which read child.measuredHeight to position the
     *     `layout_alignParentBottom`-anchored bar) see the new height.
     *  5. Adjust the bar's actual `top` via `setLeftTopRightBottom`:
     *     keep `right`/`bottom` (still anchored to parent bottom),
     *     move `top` up by (newH - oldH). Idempotent re-pokes are
     *     no-ops via setFrame's changed-check.
     *  6. Adjust MenuView's bounds to match the bar's new height
     *     (MenuView fills the bar's interior).
     *  7. Adjust each NavigationBarItemView's bottom so it fills the
     *     bar vertically (MenuView's own onLayout otherwise leaves
     *     items at their old 56dp height with a 24dp gap at the
     *     bottom of the bar). Items keep their natural item-width
     *     (MenuView's per-child width distribution is preserved).
     *
     *  Class-name suffix matching makes this generic for any APK
     *  with a Material2 or Material3 bottom navigation. The 80dp
     *  value is the M3 published spec -- per
     *  https://m3.material.io/components/navigation-bar/specs
     *  (container_height) -- not an F-Droid-specific tuning.
     *
     *  IMPORTANT: this fix runs every frame so it survives any
     *  later re-measure that snaps the bar back to 56dp. Each
     *  call's bounds-poke is idempotent (setFrame compares and
     *  bails on no-op). */
    /** M15: clamp a Material BottomNavigationView/NavigationBarView (and its
     *  MenuView + item children) measured height DOWN to the design nav height,
     *  called BETWEEN the measure and layout passes. The bundled
     *  BottomNavigationMenuView.onMeasure fills its given height and
     *  NavigationBarView has no onMeasure, so a wrap_content bar measures to the
     *  full available height; clamping here lets RelativeLayout's
     *  alignParentBottom + the layout_above sibling resolve correctly via the
     *  real rules (bar pinned bottom, app-list filling above). Walks DFS. */
    private static void clampNavBarMeasuredHeight(View v) {
        if (v == null) return;
        if (v instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                clampNavBarMeasuredHeight(vg.getChildAt(i));
            }
        }
        String cn;
        try { cn = v.getClass().getName(); } catch (Throwable t) { return; }
        if (!cn.endsWith("BottomNavigationView") && !cn.endsWith("NavigationBarView")) return;
        int mh = v.getMeasuredHeight();
        if (mh <= 0) return;
        int navH = Math.round(80f * densityOf(v));  // m3_comp_navigation_bar_container_height
        if (mh <= navH) return;  // already at/under spec -- leave it
        setMeasuredHeightDown(v, navH);
        if (v instanceof android.view.ViewGroup bar) clampNavChildrenHeight(bar, navH);
        if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
            NativeLog.i(TAG, "clampNavBarMeasuredHeight " + v.getClass().getSimpleName()
                + " mh " + mh + " -> " + navH);
        }
    }
    /** Recursively clamp the MenuView + item-view measured heights to navH so
     *  the bar's onLayout positions them within the clamped bar (an un-clamped
     *  full-height menu/item would overflow + hide the icons/labels). */
    private static void clampNavChildrenHeight(android.view.ViewGroup parent, int navH) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == null) continue;
            if (c.getMeasuredHeight() > navH) setMeasuredHeightDown(c, navH);
            String cn;
            try { cn = c.getClass().getName(); } catch (Throwable t) { continue; }
            if (c instanceof android.view.ViewGroup cvg
                    && (cn.endsWith("MenuView") || cn.endsWith("NavigationBarItemView")
                        || cn.endsWith("BottomNavigationItemView"))) {
                clampNavChildrenHeight(cvg, navH);
            }
        }
    }
    /** Set a view's measuredHeight field, downward only (never grows). */
    private static void setMeasuredHeightDown(View v, int h) {
        try {
            java.lang.reflect.Field mh = findMeasuredHeightField(v.getClass());
            if (mh != null && mh.getInt(v) > h) mh.setInt(v, h);
        } catch (Throwable ignored) {}
    }

    private static void enforceM3NavigationBarHeight(View v) {
        if (v == null) return;
        enforceM3BarRec(v);
    }
    private static void enforceM3BarRec(View v) {
        if (v == null) return;
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) enforceM3BarRec(vg.getChildAt(i));
        }
        String cn;
        try { cn = v.getClass().getName(); }
        catch (Throwable t) { return; }
        if (!cn.endsWith("BottomNavigationView")
            && !cn.endsWith("NavigationBarView")) return;
        if (!(v instanceof android.view.ViewGroup bar)) return;
        float density = densityOf(v);
        // v1.0.146: reverted v1.0.145's 54dp shrink. User preferred
        // the v1.0.144 80dp bar height; the shrink attempt + icon
        // re-positioning didn't match their mental model. Back to
        // the M3-spec 80dp `m3_comp_navigation_bar_container_height`
        // value declared in F-Droid's bundled dimens.xml.
        int m3MinH = Math.round(80f * density);
        int curW = bar.getRight() - bar.getLeft();
        int curH = bar.getBottom() - bar.getTop();
        // Only act once the bar has bounds. Initial 0-width frames
        // (pre-first-layout) get skipped; the per-frame call will
        // catch the bar on the next tick after layout assigns size.
        if (curW <= 0 || curH <= 0) return;
        // v1.0.146: only-grow semantics restored (M3 spec is a floor).
        // Material's natural ~56dp measure gets grown to 80dp; bar
        // already at-or-above 80dp gets left alone.
        if (curH >= m3MinH) return; // already at-or-above M3 spec
        try {
            // (3) setMinimumHeight floor for any future measure path.
            bar.setMinimumHeight(m3MinH);
            // (4) Poke measuredHeight on the bar (only if smaller).
            java.lang.reflect.Field mh = findMeasuredHeightField(bar.getClass());
            if (mh != null) {
                int currH = mh.getInt(bar);
                if (currH < m3MinH) mh.setInt(bar, m3MinH);
            }
            // (5) Move bar.top up by (m3MinH - curH), preserving
            // bar.bottom (= natural bottom anchor from
            // RelativeLayout's layout_alignParentBottom).
            int newTop = bar.getTop() - (m3MinH - curH);
            int newBot = bar.getBottom();
            try { bar.setLeftTopRightBottom(bar.getLeft(), newTop,
                                            bar.getRight(), newBot); }
            catch (Throwable ignored) {
                bar.layout(bar.getLeft(), newTop, bar.getRight(), newBot);
            }
            // (6, 7) Find MenuView + items inside the bar; grow MenuView
            // to the bar's full new height, grow each item to the
            // MenuView's full height. Each item's WIDTH is preserved
            // (Material's per-child distribution from inflation time).
            applyM3HeightToBarChildren(bar, m3MinH);
            if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
                NativeLog.i(TAG, "enforceM3NavigationBarHeight "
                    + bar.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(bar))
                    + " height " + curH + " -> " + m3MinH
                    + " bounds=(top=" + newTop + ", bottom=" + newBot + ")");
            }
        } catch (Throwable t) {
            if (!warnedM3BarEnforce) {
                warnedM3BarEnforce = true;
                NativeLog.w(TAG, "enforceM3NavigationBarHeight failed: " + t);
            }
        }
    }
    /** Walk the bar's tree, grow the MenuView to fill the bar's new
     *  height, grow each NavigationBarItemView to fill MenuView's
     *  height. Skips the bar itself -- caller already adjusted it. */
    private static void applyM3HeightToBarChildren(android.view.ViewGroup bar,
                                                    int barH) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c == null) continue;
            String cn;
            try { cn = c.getClass().getName(); }
            catch (Throwable t) { continue; }
            if (cn.endsWith("NavigationBarMenuView")
                || cn.endsWith("BottomNavigationMenuView")) {
                // MenuView fills the bar interior.
                int mvL = c.getLeft();
                int mvR = c.getRight();
                int mvT = 0;
                int mvB = barH;
                // v1.0.146: grow-only MenuView measuredHeight.
                try {
                    java.lang.reflect.Field mh = findMeasuredHeightField(c.getClass());
                    if (mh != null) {
                        int currH = mh.getInt(c);
                        if (currH < barH) mh.setInt(c, barH);
                    }
                } catch (Throwable ignored) {}
                try { c.setLeftTopRightBottom(mvL, mvT, mvR, mvB); }
                catch (Throwable ignored) { c.layout(mvL, mvT, mvR, mvB); }
                // Recurse into MenuView for items.
                if (c instanceof android.view.ViewGroup mv) {
                    applyM3HeightToItems(mv, barH);
                }
            }
        }
    }
    private static void applyM3HeightToItems(android.view.ViewGroup menu, int itemH) {
        for (int i = 0; i < menu.getChildCount(); i++) {
            View c = menu.getChildAt(i);
            if (c == null) continue;
            String cn;
            try { cn = c.getClass().getName(); }
            catch (Throwable t) { continue; }
            if (cn.endsWith("NavigationBarItemView")
                || cn.endsWith("BottomNavigationItemView")) {
                int iL = c.getLeft();
                int iR = c.getRight();
                // Item top stays at MenuView local y=0; item bottom
                // extends to MenuView's new height.
                int iT = 0;
                int iB = itemH;
                // v1.0.146: grow-only item measuredHeight.
                try {
                    java.lang.reflect.Field mh = findMeasuredHeightField(c.getClass());
                    if (mh != null) {
                        int currH = mh.getInt(c);
                        if (currH < itemH) mh.setInt(c, itemH);
                    }
                } catch (Throwable ignored) {}
                try { c.setLeftTopRightBottom(iL, iT, iR, iB); }
                catch (Throwable ignored) { c.layout(iL, iT, iR, iB); }
            }
        }
    }
    private static float densityOf(View v) {
        try {
            if (v.getResources() != null
                && v.getResources().getDisplayMetrics() != null
                && v.getResources().getDisplayMetrics().density > 0f) {
                return v.getResources().getDisplayMetrics().density;
            }
        } catch (Throwable ignored) {}
        return 2.0f;
    }
    private static boolean warnedM3BarEnforce = false;

    /** v1.0.134: position labels under icons inside Material's
     *  NavigationBarItemView. The widget's M3 layout structure is:
     *      NavigationBarItemView (FrameLayout-derived)
     *        ImageView (icon)
     *        BaselineLayout
     *          TextView smallLabel (inactive typography)
     *          TextView largeLabel (active typography)
     *  Material expects BaselineLayout.onLayout to place children at
     *  their baseline below the icon; with our framework's minimal
     *  ViewGroup.onLayout the BaselineLayout ends up at negative-Y
     *  and its children render off-canvas. We fix it with a per-frame
     *  walk that re-positions the icon at top-center and the
     *  BaselineLayout directly below it, then lays out the visible
     *  label inside the BaselineLayout's local space.
     *
     *  Class-name keyed on simple names ("NavigationBarItemView" /
     *  "BottomNavigationItemView" / "BaselineLayout") -- generic for
     *  any APK with a Material2 or Material3 bottom nav. No
     *  package-name dependency. */
    private static void fixupNavigationBarItemViews(View v) {
        if (v == null) return;
        fixupNavItemViewsRec(v, new java.util.IdentityHashMap<>());
    }
    /** v1.0.144 experiment: walk the tree DFS-first, find the topmost
     *  ComposeView (lowest absolute Y in rootView-local coords). Walk
     *  back up its parent chain; for each ancestor whose `top` is
     *  non-zero, log it so we can see the chain that puts the
     *  ComposeView at its current position. Then forcefully shift the
     *  whole chain up so the topmost ComposeView lands at rootView-y=0
     *  (= canvas y = statusBarH, immediately under the status bar).
     *
     *  This is intentionally heavy-handed -- it tunes the visual
     *  position without fully diagnosing where the offset comes from.
     *  Once the desired position is confirmed, the underlying source
     *  (likely a FrameLayout / LinearLayout / AppBar inset) can be
     *  identified from the chain log and zeroed at its actual layer.
     *
     *  Generic: keys on class name "ComposeView" or any subclass-name
     *  suffix match; no F-Droid-specific identifier. */
    private static View topComposeFound = null;
    private static View topComposeShiftedOnce = null;
    private static void shiftTopComposeViewToRootTop(View root, int statusBarH) {
        if (root == null) return;
        topComposeFound = null;
        findTopComposeRec(root, root);
        if (topComposeFound == null) return;
        View target = topComposeFound;
        // Walk up the parent chain (via getParent()) and log positions
        // so we can see what's contributing offset.
        if (topComposeShiftedOnce != target) {
            topComposeShiftedOnce = target;
            StringBuilder sb = new StringBuilder("topCompose chain: ");
            View v = target;
            while (v != null && v != root) {
                sb.append(v.getClass().getSimpleName())
                  .append("(top=").append(v.getTop()).append(")");
                Object p = v.getParent();
                if (p instanceof View pv) {
                    sb.append(" -> ");
                    v = pv;
                } else { break; }
            }
            NativeLog.i(TAG, sb.toString());
        }
        // Compute absolute top of the ComposeView in rootView-local coords.
        int absTop = 0;
        View v = target;
        while (v != null && v != root) {
            absTop += v.getTop();
            Object p = v.getParent();
            if (p instanceof View pv) v = pv; else break;
        }
        // If the ComposeView's absolute top is non-zero in rootView-
        // local, shift its IMMEDIATE PARENT up by (absTop - 0).
        // (Shifting the parent rather than the ComposeView itself
        // keeps other siblings -- e.g. a StatusBanner sibling -- in
        // the same relative position.)
        if (absTop > 0) {
            View parent = (View) target.getParent();
            if (parent != null && parent != root) {
                int oldTop = parent.getTop();
                int oldBot = parent.getBottom();
                // v1.0.144 experiment: doubled shift so the user can
                // clearly see the banner move. If absTop=48 (= 24dp,
                // typical statusBarH), shift up by 96 px (= 48dp) --
                // the banner ends up ~48dp ABOVE the status bar
                // (visually overlapping it). If movement is visible,
                // the shift mechanism works and we can dial the
                // amount back to exactly absTop.
                int delta  = absTop * 2;
                int newTop = oldTop - delta;
                int newBot = oldBot - delta;
                {
                    parent.setLeftTopRightBottom(parent.getLeft(), newTop,
                                                  parent.getRight(), newBot);
                    if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
                        NativeLog.i(TAG, "shiftTopCompose: parent "
                            + parent.getClass().getSimpleName()
                            + "@" + Integer.toHexString(System.identityHashCode(parent))
                            + " top " + oldTop + " -> " + newTop
                            + " bottom " + oldBot + " -> " + (oldBot - delta));
                    }
                }
            }
        }
    }
    private static void findTopComposeRec(View v, View root) {
        if (v == null) return;
        String cn = "";
        try { cn = v.getClass().getSimpleName(); } catch (Throwable t) {}
        if ("ComposeView".equals(cn) || cn.endsWith("ComposeView")) {
            // Compute its absolute y in rootView-local coords
            int y = 0;
            View w = v;
            while (w != null && w != root) {
                y += w.getTop();
                Object p = w.getParent();
                if (p instanceof View pv) w = pv; else break;
            }
            if (topComposeFound == null) {
                topComposeFound = v;
            } else {
                int oldY = 0;
                View w2 = topComposeFound;
                while (w2 != null && w2 != root) {
                    oldY += w2.getTop();
                    Object p = w2.getParent();
                    if (p instanceof View pv) w2 = pv; else break;
                }
                if (y < oldY) topComposeFound = v;
            }
        }
        if (v instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                findTopComposeRec(vg.getChildAt(i), root);
            }
        }
    }

    /** v1.0.144: zero out status-bar-inset paddingTop on AppCompat /
     *  Material containers that self-apply it. The rootView already
     *  excludes the status bar via rootView.layout(0, statusBarH, ...),
     *  so any inner container adding paddingTop=statusBarH double-pads
     *  and pushes the threat banner ~24dp lower than its intended
     *  position.
     *
     *  v1.0.139 attempted to suppress this by dispatching
     *  WindowInsets.Insets.NONE -- which works for containers that
     *  read insets via OnApplyWindowInsetsListener. But AppCompat's
     *  FitWindowsLinearLayout / FitWindowsFrameLayout / ContentFrame-
     *  Layout / CollapsingToolbarLayout apply the inset via direct
     *  fitsSystemWindows hardcoded paths during ctor or onAttach,
     *  not through the listener -- so v1.0.139 doesn't catch them.
     *
     *  Class-name suffix match keys on AppCompat / Material window-
     *  insets-aware container classes; for each match, if paddingTop
     *  is non-zero AND is close to the system bar height (= a
     *  plausibly-status-bar-applied padding), zero it via setPadding.
     *  Generic for any APK using AppCompat or Material themes.
     *
     *  Idempotent: subsequent frames see paddingTop == 0 already and
     *  setPadding short-circuits in View.setPadding when unchanged
     *  (which v1.0.137 already enforced for the requestLayout side
     *  of the WindowInsets dispatch chain).  */
    private static void zeroStatusBarPaddingTopOnContainers(View root) {
        if (root == null) return;
        int statusH = StatusBarDriver.heightPx(root);
        if (statusH <= 0) return;
        zeroStatusBarPadRec(root, statusH, new java.util.IdentityHashMap<>());
    }
    private static void zeroStatusBarPadRec(View v, int statusH,
                                             java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return;
        if (visited.put(v, Boolean.TRUE) != null) return;
        if (v instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                zeroStatusBarPadRec(vg.getChildAt(i), statusH, visited);
            }
        }
        String cn;
        try { cn = v.getClass().getName(); } catch (Throwable t) { return; }
        // Match AppCompat / Material containers that self-apply the
        // status-bar paddingTop. The list is the union of classes
        // that AppCompat's FitWindows family + Material's window-
        // insets-aware containers ship with.
        boolean isInsetContainer =
               cn.endsWith("FitWindowsLinearLayout")
            || cn.endsWith("FitWindowsFrameLayout")
            || cn.endsWith("ContentFrameLayout")
            || cn.endsWith("CoordinatorLayout")
            || cn.endsWith("AppBarLayout")
            || cn.endsWith("CollapsingToolbarLayout")
            || cn.endsWith("DrawerLayout");
        if (!isInsetContainer) return;
        int padTop = v.getPaddingTop();
        // Only zero paddings that look like status-bar applications:
        // within +/- 4 px of statusH (rounding tolerance).
        if (padTop <= 0) return;
        if (Math.abs(padTop - statusH) > 4) return;
        try {
            v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), v.getPaddingBottom());
            if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
                NativeLog.i(TAG, "zeroStatusBarPaddingTop "
                    + v.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(v))
                    + " paddingTop " + padTop + " -> 0");
            }
        } catch (Throwable ignored) {}
    }
    /** v1.0.144 diagnostic: one-shot trace per NavigationBarItemView so we
     *  can see what bounds the fixup computes. Gated on
     *  MACAPK_TRACE_NAV=1. */
    private static final java.util.Map<View, Boolean> fixupNavTraced =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());   // weak: see rvDumped

    /** v1.0.144 diagnostic: walk the tree and log every BaselineLayout's
     *  current bounds + each visible TextView child's bounds. Called
     *  RIGHT BEFORE rootView.draw to verify fixup's setLeftTopRightBottom
     *  hasn't been overwritten between fixup and paint. */
    private static void dumpBaselineLayoutBounds(View v, long frame) {
        if (v == null) return;
        String cn = "";
        try { cn = v.getClass().getSimpleName(); } catch (Throwable t) {}
        if (cn.endsWith("BottomNavigationView") || cn.endsWith("NavigationBarView")
            || cn.endsWith("BottomNavigationMenuView") || cn.endsWith("NavigationBarMenuView")) {
            NativeLog.i(TAG, "DUMP frame#" + frame + " " + cn + "@"
                + Integer.toHexString(System.identityHashCode(v))
                + " bounds=(" + v.getLeft() + "," + v.getTop() + "-"
                + v.getRight() + "," + v.getBottom() + ") measured="
                + v.getMeasuredWidth() + "x" + v.getMeasuredHeight());
        }
        if (cn.endsWith("BottomNavigationItemView") || cn.endsWith("NavigationBarItemView")) {
            StringBuilder sb = new StringBuilder();
            sb.append("DUMP frame#").append(frame)
              .append(" Item ").append(cn).append("@")
              .append(Integer.toHexString(System.identityHashCode(v)))
              .append(" bounds=(").append(v.getLeft()).append(",").append(v.getTop())
              .append("-").append(v.getRight()).append(",").append(v.getBottom())
              .append(") height=").append(v.getBottom() - v.getTop());
            if (v instanceof android.view.ViewGroup vg) {
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View c = vg.getChildAt(i);
                    if (c == null) continue;
                    sb.append("\n  item-child[").append(i).append("]=")
                      .append(c.getClass().getSimpleName())
                      .append(" vis=").append(c.getVisibility())
                      .append(" bounds=(").append(c.getLeft()).append(",").append(c.getTop())
                      .append("-").append(c.getRight()).append(",").append(c.getBottom())
                      .append(")");
                }
            }
            NativeLog.i(TAG, sb.toString());
        }
        if ("ImageView".equals(cn) || cn.endsWith("AppCompatImageView")) {
            android.widget.ImageView iv = (android.widget.ImageView) v;
            android.graphics.drawable.Drawable d = iv.getDrawable();
            String dInfo = "no-drawable";
            if (d != null) {
                android.graphics.Rect db = d.getBounds();
                dInfo = "drawable=" + d.getClass().getSimpleName() + " bounds=("
                        + db.left + "," + db.top + "-" + db.right + "," + db.bottom + ")";
            }
            NativeLog.i(TAG, "DUMP frame#" + frame + " ImageView@"
                + Integer.toHexString(System.identityHashCode(v))
                + " viewBounds=(" + v.getLeft() + "," + v.getTop() + "-"
                + v.getRight() + "," + v.getBottom() + ") " + dInfo);
        }
        if ("BaselineLayout".equals(cn)) {
            StringBuilder sb = new StringBuilder();
            sb.append("DUMP frame#").append(frame)
              .append(" BL@").append(Integer.toHexString(System.identityHashCode(v)))
              .append(" bounds=(").append(v.getLeft()).append(",").append(v.getTop())
              .append("-").append(v.getRight()).append(",").append(v.getBottom())
              .append(") height=").append(v.getBottom() - v.getTop());
            if (v instanceof android.view.ViewGroup vg) {
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View c = vg.getChildAt(i);
                    if (c == null) continue;
                    sb.append("\n  child[").append(i).append("]=")
                      .append(c.getClass().getSimpleName())
                      .append(" vis=").append(c.getVisibility())
                      .append(" bounds=(").append(c.getLeft()).append(",").append(c.getTop())
                      .append("-").append(c.getRight()).append(",").append(c.getBottom())
                      .append(")");
                    if (c instanceof android.widget.TextView tv) {
                        sb.append(" text='").append(KeyLogPolicy.viewText(tv, tv.getText())).append("'");
                    }
                }
            }
            NativeLog.i(TAG, sb.toString());
        }
        if (v instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpBaselineLayoutBounds(vg.getChildAt(i), frame);
            }
        }
    }
    private static void fixupNavItemViewsRec(View v,
                                              java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return;
        if (visited.put(v, Boolean.TRUE) != null) return;
        if (!(v instanceof android.view.ViewGroup vg)) return;
        // Recurse first so descendants get their normal positioning,
        // then fix THIS node if it's a NavigationBarItemView.
        int n = vg.getChildCount();
        for (int i = 0; i < n; i++) fixupNavItemViewsRec(vg.getChildAt(i), visited);
        String cn;
        try { cn = vg.getClass().getSimpleName(); }
        catch (Throwable t) { return; }
        if (cn == null) return;
        if (!cn.endsWith("NavigationBarItemView")
            && !cn.endsWith("BottomNavigationItemView")) return;
        int iw = vg.getRight() - vg.getLeft();
        int ih = vg.getBottom() - vg.getTop();
        if (iw <= 0 || ih <= 0) return;
        float density = android.util.DisplayMetrics.macapkDeviceDensity();
        try {
            if (vg.getResources() != null
                && vg.getResources().getDisplayMetrics() != null
                && vg.getResources().getDisplayMetrics().density > 0f) {
                density = vg.getResources().getDisplayMetrics().density;
            }
        } catch (Throwable ignored) {}
        // Find first ImageView descendant (icon) and BaselineLayout.
        android.widget.ImageView icon = findFirstImageView(vg);
        android.view.ViewGroup labels = findBaselineLayoutChild(vg);
        // v1.0.144 diagnostic: trace once per item so we can see ih
        // and the computed positions, gated on MACAPK_TRACE_NAV=1.
        boolean traceThis = "1".equals(ENV_MACAPK_TRACE_NAV)
                             && fixupNavTraced.putIfAbsent(vg, Boolean.TRUE) == null;
        if (traceThis) {
            NativeLog.i(TAG, "fixupNavItem " + cn
                + "@" + Integer.toHexString(System.identityHashCode(vg))
                + " iw=" + iw + " ih=" + ih + " density=" + density
                + " labels=" + (labels != null ? labels.getClass().getSimpleName()
                                                  + "@" + Integer.toHexString(
                                                      System.identityHashCode(labels))
                                                : "null")
                + " labelChildren=" + (labels != null ? labels.getChildCount() : 0));
        }
        // Icon size: prefer explicit LP, fall back to drawable
        // intrinsic, fall back to 24dp.
        int iconSize = Math.round(24f * density);
        if (icon != null) {
            android.view.ViewGroup.LayoutParams ilp = icon.getLayoutParams();
            if (ilp != null && ilp.width > 0 && ilp.height > 0) {
                iconSize = Math.min(ilp.width, ilp.height);
            }
        }
        // v1.0.143: M3-spec item-internal layout for an 80dp-tall
        // BottomNavigationItemView (the height enforceM3Navigation-
        // BarHeight grows the bar to, matching Material 3's
        // `m3_comp_navigation_bar_container_height=80dp` declaration).
        //
        // Per M3 BottomNavigation spec (https://m3.material.io/
        // components/navigation-bar/specs):
        //
        // - Icon: 24dp, `marginTop = 12dp` from item top so the icon
        //   center sits at item-y = 12 + 12 = 24dp. The M3
        //   active-indicator pill (32dp tall x 64dp wide) is centered
        //   on the icon center, which lands the pill at item-y =
        //   8..40dp. View.macapkPaintMaterialChrome's pill formula
        //   mirrors this so pill+icon stay aligned.
        //
        // - Label (BaselineLayout): F-Droid's bundled
        //   `design_bottom_navigation_item.xml` declares
        //   `android:layout_gravity="center|bottom"` and
        //   `android:paddingBottom="@dimen/design_bottom_navigation_label_padding"`
        //   (= 10dp). So BaselineLayout is sized to its content (the
        //   visible 14sp label = ~16dp tall) + 10dp paddingBottom,
        //   and positioned at the item's BOTTOM edge. The label
        //   itself paints at BaselineLayout-local y = 0..16dp
        //   (BL.paddingBottom places it 10dp above BL's bottom).
        //
        // Visual layout for an 80dp bar:
        //   item-y    0..12dp   = top margin
        //   item-y   12..36dp   = icon (with 32dp pill at item-y 8..40dp)
        //   item-y   40..54dp   = gap (label "below" the pill)
        //   item-y   54..70dp   = label TextView (16dp / 14sp text)
        //   item-y   70..80dp   = BaselineLayout paddingBottom (10dp)
        //
        // The `extra` term handles the rare case where the item is
        // measured larger than 80dp -- the icon top and BL bottom each
        // shift by extra/2 so the layout stays vertically centered.
        // v1.0.148: icon shifted UP another 20 px (10dp at density 2)
        // per user feedback. iconMarginTop now 2dp (was 12dp);
        // icon sits at item-y 2..26dp. Pill stays at item-y 26..58dp
        // (see View.macapkPaintMaterialChrome iconTopPad=22dp),
        // so icon and pill are now stacked: icon at top, pill below.
        int spec80 = Math.round(80f * density);
        int extra = Math.max(0, ih - spec80);
        int iconMarginTop = Math.round(2f * density) + extra / 2;
        int blPaddingBottom = Math.round(10f * density);
        // -- Icon centered horizontally in its actual parent. --
        //
        // v1.0.150 had a -15 px hardcoded LEFT shift that masked a
        // framework bug: the ImageView's bounds are relative to its
        // PARENT (the icon_container FrameLayout, which Material's
        // design_bottom_navigation_item.xml inflates), NOT to the
        // item itself. Computing `iconLeft = (iw - iconSize) / 2`
        // uses the item width (144 px) where the icon's parent is
        // only 112 px wide (= 56dp icon_container). The mismatch
        // shifts the icon 16 px right of item-center; the v1.0.150
        // -15 px hack subtracted out almost all of that.
        //
        // Framework-correct fix: walk up to the icon's actual parent
        // and use its width for centering. Idempotent regardless of
        // container size; matches AOSP's natural FrameLayout child
        // gravity=center positioning. No per-APK offset needed.
        if (icon != null) {
            View iconParent = (icon.getParent() instanceof View)
                              ? (View) icon.getParent() : null;
            int parentW = (iconParent != null
                           && iconParent.getRight() > iconParent.getLeft())
                          ? iconParent.getRight() - iconParent.getLeft()
                          : iw;
            int iconLeft = (parentW - iconSize) / 2;
            int iconTop  = iconMarginTop;
            int iconRight = iconLeft + iconSize;
            int iconBottom = iconTop + iconSize;
            try { icon.setLeftTopRightBottom(iconLeft, iconTop, iconRight, iconBottom); }
            catch (Throwable t) { icon.layout(iconLeft, iconTop, iconRight, iconBottom); }
        }
        // -- BaselineLayout positioned at item BOTTOM (gravity=center|bottom),
        //    sized to wrap the visible label + paddingBottom.
        if (labels != null) {
            // Find the visible label TextView; measure its intrinsic
            // size so we know how tall to make BaselineLayout.
            android.widget.TextView visibleLabel = null;
            int labelMw = 0, labelMh = 0;
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                iw, android.view.View.MeasureSpec.AT_MOST);
            int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                0, android.view.View.MeasureSpec.UNSPECIFIED);
            for (int i = 0; i < labels.getChildCount(); i++) {
                View c = labels.getChildAt(i);
                if (c == null) continue;
                if (c.getVisibility() != View.VISIBLE) continue;
                if (!(c instanceof android.widget.TextView tv)) continue;
                try { tv.measure(wSpec, hSpec); } catch (Throwable ignored) {}
                int mw = Math.max(1, Math.min(iw, tv.getMeasuredWidth()));
                int mh = Math.max(1, tv.getMeasuredHeight());
                if (mh <= 0) continue;
                if (visibleLabel == null
                    || mh > labelMh) { // pick the taller of inactive/active typography
                    visibleLabel = tv; labelMw = mw; labelMh = mh;
                }
            }
            // BaselineLayout height = label height + paddingBottom.
            // Bottom-align in the item: BL.bottom = item.bottom - extra/2,
            // BL.top = BL.bottom - blHeight.
            int blHeight = (visibleLabel != null)
                            ? labelMh + blPaddingBottom
                            : Math.round(26f * density); // ~16dp label + 10dp padding
            int lBot = ih - extra / 2;
            int lTop = Math.max(iconMarginTop + iconSize, lBot - blHeight);
            int lLeft = 0;
            int lRight = iw;
            if (traceThis) {
                NativeLog.i(TAG, "  visibleLabel="
                    + (visibleLabel != null ? visibleLabel.getClass().getSimpleName()
                                              + "@" + Integer.toHexString(
                                                  System.identityHashCode(visibleLabel))
                                              + " mw=" + labelMw + " mh=" + labelMh
                                              + " text='" + KeyLogPolicy.viewText(visibleLabel, visibleLabel.getText()) + "'"
                                            : "none")
                    + " blHeight=" + blHeight + " lTop=" + lTop + " lBot=" + lBot);
            }
            try { labels.setLeftTopRightBottom(lLeft, lTop, lRight, lBot); }
            catch (Throwable t) { labels.layout(lLeft, lTop, lRight, lBot); }
            if (traceThis) {
                NativeLog.i(TAG, "  POST-SET-BL: left=" + labels.getLeft()
                    + " top=" + labels.getTop() + " right=" + labels.getRight()
                    + " bottom=" + labels.getBottom());
            }
            // -- Visible label centered horizontally, padded 10dp from
            //    BL's bottom (matching paddingBottom in the XML). --
            int lh = lBot - lTop;
            for (int i = 0; i < labels.getChildCount(); i++) {
                View c = labels.getChildAt(i);
                if (c == null) continue;
                if (c.getVisibility() != View.VISIBLE || c != visibleLabel) {
                    // Snap GONE / INVISIBLE / non-visible labels to
                    // zero so they don't push the visible one.
                    try { c.setLeftTopRightBottom(0, 0, 0, 0); }
                    catch (Throwable t) { c.layout(0, 0, 0, 0); }
                    continue;
                }
                // Visible label: BL-local y = (blHeight - paddingBottom - labelHeight)
                //                          .. (blHeight - paddingBottom)
                int cBot = lh - blPaddingBottom;
                int cTop = Math.max(0, cBot - labelMh);
                int cx = (iw - labelMw) / 2;
                try { c.setLeftTopRightBottom(cx, cTop, cx + labelMw, cBot); }
                catch (Throwable t) { c.layout(cx, cTop, cx + labelMw, cBot); }
            }
        }
    }
    /** v1.0.395 (real-loop path): vertically CENTER each Material nav item's
     *  icon container + label group as a unit within the item's content area
     *  (item height minus the bottom gesture-nav inset, where the pill is
     *  painted), matching real Android.
     *
     *  Why: F-Droid's bundled `design_bottom_navigation_item.xml` gives the
     *  icon container `layout_gravity="center_horizontal"` + `marginTop=8dp`
     *  and the label group `layout_gravity="center|bottom"`, sized for the
     *  natural 56dp item (`design_bottom_navigation_height`) where icon-top +
     *  label-bottom meet in the middle. Our measure chain fills instead of
     *  wrapping (base View.onMeasure returns the AT_MOST spec size, and the
     *  bundled BottomNavigationMenuView fills the height it is given), so the
     *  bar + items measure to the FULL screen height. clampNavBarMeasured-
     *  Height then clamps the bar to 80dp, but Material's NavigationBarItemView
     *  has already positioned the content for ~56dp at the TOP of the now-160px
     *  item -- and it mis-centers the icon because the label group measured
     *  96px from the same fill bug (icon margin lands at -4 instead of +16).
     *  Net visual: icons+labels jam the top of the bar with a big empty gap
     *  below -- the "icons too high inside the nav bar" the owner reported
     *  (2026-06-14).
     *
     *  Faithful fix: realize the item XML's `center` gravity by centering the
     *  icon+label group in the content area. The gesture inset is reserved
     *  (not centered into) so the group sits ABOVE the pill, matching an
     *  Android-14 emulator capture of the same F-Droid build
     *  (diag/refs/fdroid_emulator_android14_gesturenav.png). The active-
     *  indicator pill (View.macapkPaintMaterialChrome) now derives its center
     *  from the icon's actual laid-out position, so it follows the re-center.
     *
     *  Generic: keys on the Material nav item class names; no per-APK data.
     *  RETIRE when the measure chain wraps the bar/items to their natural
     *  design height so Material's own onLayout centers the content (then no
     *  post-layout repositioning is needed). */
    private static void centerNavItemContent(View root) {
        if (root == null) return;
        // v1.0.997: the BOTTOM share of the nav inset. This reserves vertical
        // room inside a bottom-nav item, so a bar that has moved to the display's
        // right edge must contribute nothing here.
        int inset = com.macapk.loader.StatusBarDriver.navBarInsetBottomPx(root);
        centerNavItemRec(root, inset, new java.util.IdentityHashMap<>());
    }
    private static void centerNavItemRec(View v, int insetPx,
                                         java.util.IdentityHashMap<View, Boolean> visited) {
        if (v == null) return;
        if (visited.put(v, Boolean.TRUE) != null) return;
        if (!(v instanceof android.view.ViewGroup vg)) return;
        for (int i = 0; i < vg.getChildCount(); i++)
            centerNavItemRec(vg.getChildAt(i), insetPx, visited);
        String cn;
        try { cn = vg.getClass().getSimpleName(); } catch (Throwable t) { return; }
        if (cn == null) return;
        if (!cn.endsWith("NavigationBarItemView")
            && !cn.endsWith("BottomNavigationItemView")) return;
        int itemW = vg.getRight() - vg.getLeft();
        int ih = vg.getBottom() - vg.getTop();
        if (ih <= 0 || itemW <= 0) return;
        // Icon container = the icon ImageView's DIRECT-child-of-item parent
        // (the design_bottom_navigation_item structure). Fall back to the
        // ImageView itself if it is a direct child.
        android.widget.ImageView icon = findFirstImageView(vg);
        android.view.ViewGroup labels = findBaselineLayoutChild(vg);
        View iconContainer = (icon != null && icon.getParent() instanceof View)
                             ? (View) icon.getParent() : null;
        if (iconContainer != null && iconContainer.getParent() != vg) {
            iconContainer = (icon != null && icon.getParent() == vg) ? icon : null;
        }
        if (iconContainer == null && icon != null && icon.getParent() == vg)
            iconContainer = icon;
        if (iconContainer == null) return;
        int iconH = iconContainer.getBottom() - iconContainer.getTop();
        if (iconH <= 0) iconH = Math.round(24f * densityOf(vg));
        // Visible label intrinsic height (NOT the over-tall inflated container).
        int labelH = 0;
        if (labels != null) {
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                itemW, android.view.View.MeasureSpec.AT_MOST);
            int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                0, android.view.View.MeasureSpec.UNSPECIFIED);
            for (int i = 0; i < labels.getChildCount(); i++) {
                View c = labels.getChildAt(i);
                if (!(c instanceof android.widget.TextView tv)) continue;
                if (tv.getVisibility() != View.VISIBLE) continue;
                try { tv.measure(wSpec, hSpec); } catch (Throwable ignored) {}
                labelH = Math.max(labelH, tv.getMeasuredHeight());
            }
        }
        int gap = (labelH > 0) ? Math.round(4f * densityOf(vg)) : 0;
        int groupH = iconH + (labelH > 0 ? gap + labelH : 0);
        int contentAreaH = Math.max(iconH, ih - Math.max(0, insetPx));
        int groupTop = Math.max(0, (contentAreaH - groupH) / 2);
        if ("1".equals(ENV_MACAPK_TRACE_NAV)) {
            NativeLog.i(TAG, "centerNavItem " + cn + " ih=" + ih + " inset=" + insetPx
                + " iconH=" + iconH + " labelH=" + labelH + " groupH=" + groupH
                + " contentArea=" + contentAreaH + " groupTop=" + groupTop
                + " iconCont=" + iconContainer.getClass().getSimpleName()
                + "[" + iconContainer.getLeft() + "," + iconContainer.getTop()
                + "-" + iconContainer.getRight() + "," + iconContainer.getBottom() + "]");
        }
        // Move the icon container's TOP (preserve L/R and height).
        int icL = iconContainer.getLeft(), icR = iconContainer.getRight();
        try { iconContainer.setLeftTopRightBottom(icL, groupTop, icR, groupTop + iconH); }
        catch (Throwable t) { iconContainer.layout(icL, groupTop, icR, groupTop + iconH); }
        // Move the label group's TOP right below the icon (its text sits at
        // the group's top; the group can overhang into the inset region --
        // it has no background and clipChildren=false, so nothing shows).
        if (labels != null && labelH > 0) {
            int lL = labels.getLeft(), lR = labels.getRight();
            int lH = labels.getBottom() - labels.getTop();
            if (lH <= 0) lH = labelH;
            int lTop = groupTop + iconH + gap;
            try { labels.setLeftTopRightBottom(lL, lTop, lR, lTop + lH); }
            catch (Throwable t) { labels.layout(lL, lTop, lR, lTop + lH); }
        }
    }
    private static android.widget.ImageView findFirstImageView(android.view.ViewGroup g) {
        if (g == null) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof android.widget.ImageView iv) return iv;
        }
        // Some Material variants wrap the icon in an inner FrameLayout
        // (active-indicator host). Walk one level deep to find it.
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof android.view.ViewGroup vc) {
                android.widget.ImageView nested = findFirstImageView(vc);
                if (nested != null) return nested;
            }
        }
        return null;
    }
    private static android.view.ViewGroup findBaselineLayoutChild(android.view.ViewGroup g) {
        if (g == null) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null) continue;
            String name = null;
            try { name = c.getClass().getSimpleName(); } catch (Throwable t) {}
            if ("BaselineLayout".equals(name) && c instanceof android.view.ViewGroup vc) {
                return vc;
            }
        }
        return null;
    }

    // driver instance lifecycle (e.g. activity recreate).
    public static final class ViewCanvasDriverState {
        private ViewCanvasDriverState() {}
        public static volatile boolean warnedOnDrawCrash    = false;
        public static volatile boolean warnedMissingNative  = false;
        // v1.0.95: warn-once when treeHasSurfaceView detects a cycle
        // in the View tree (defense-in-depth against framework /
        // material widget bugs that introduce a parent->descendant->
        // ...->parent edge).
        public static volatile boolean warnedTreeCycle      = false;
    }

    /** Tree-dump only: " paint=0x<argb> a=<alpha>" for the FIRST android.graphics.Paint
     *  field an app-custom Drawable declares (React Native's BackgroundDrawable keeps its fill
     *  colour only there), "" for a framework drawable or when there is none. Reflection on a
     *  private field, read once per dumped view. getDeclaredFields() resolves the field types
     *  and so loads classes of an APP drawable, which the default 1/60/300 checkpoint dump (it
     *  runs in every app) must not do: the read is skipped unless MACAPK_DUMP_TREE_EVERY is
     *  set, i.e. an explicit diagnostic session. */
    static String macapkDumpPaintColor(android.graphics.drawable.Drawable d) {
        if (ENV_MACAPK_DUMP_TREE_EVERY == null) return "";
        if (d == null || d.getClass().getName().startsWith("android.")) return "";
        for (Class<?> c = d.getClass(); c != null && !c.getName().startsWith("android."); c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != android.graphics.Paint.class) continue;
                try {
                    f.setAccessible(true);
                    android.graphics.Paint p = (android.graphics.Paint) f.get(d);
                    if (p == null) continue;
                    return " paint=0x" + Integer.toHexString(p.getColor()) + " a=" + p.getAlpha();
                } catch (Throwable t) {
                    return "";
                }
            }
        }
        return "";
    }
}
