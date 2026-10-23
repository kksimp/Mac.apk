// com.macapk.loader.DisplayConfig -- the ONE committed display size, the pure
// derivation of DisplayMetrics + Configuration from it, and the commit that
// applies a new size the way a device applies a display change.
//
// WHY THIS EXISTS (v1.0.1637). The window a Mac.apk app draws into IS its
// display (the window paints its own system bars), and a user can resize it.
// Before this, nothing wrote size or orientation after boot: Boot built
// DisplayMetrics + Configuration once from the native canvas globals, Display
// read those globals live (and a window drag never writes them), and the view
// tree was laid out at whatever size the surface actually became. So after any
// resize -- and, until Stage 0, even at launch -- an app was told one display
// and handed another. Full AOSP contract, the 62-app configChanges survey and
// the staged plan: docs/window-resize-and-cross-thread-surface_2026-09-23.md.
//
// THE MODEL. A window resize is a DISPLAY size change (not a freeform-window
// override), because our window is the whole device screen:
//   - one committed real size, written ONLY on the Android main (owner) thread;
//   - DisplayMetrics / Configuration derived from it by compute(), the same
//     function Boot uses, so launch and resize cannot disagree;
//   - the commit updates Resources (rebuilding the ResourceIndex only when a
//     qualifier threshold is actually crossed), tells the process
//     (Application + ComponentCallbacks) and the display listeners once, then
//     applies AOSP's per-SDK relaunch / report rules to the top activity and
//     re-lays-out the view tree.
//
// THREADING. The signal comes from the AppKit render thread (drawInMTKView ->
// macapkNoteSurfaceSize, native). It only records the newest size and posts ONE
// commit through an OWNER-ONLY Handler (v1.0.1636), so the commit never runs on
// the OS-main drain and is FIFO-ordered ahead of the doSurfaceChanged that the
// same native frame posts right after it. A newer size supersedes an older one
// that has not run yet. doSurfaceChanged also calls commitPending() before it
// measures (idempotent), for any path that reaches it directly.
//
// GATES. MACAPK_RESIZE_CONFIG=0 is the bisect gate for the whole v1.0.1637
// display model: the committed size freezes at the boot value AND the native
// canvas fit goes back to the raw visible frame (cocoa_bridge.mm
// content_budget_for_visible reads the same variable), which reproduces the
// old portrait launch metrics exactly (418x906dp for an 808x1796 surface).
// MACAPK_RESIZE_RELAUNCH=1 enables Stage 2 (see RELAUNCH_ON).
package com.macapk.loader;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

public final class DisplayConfig {
    private static final String TAG = "Mac.apk.DisplayConfig";

    private DisplayConfig() {}

    static final boolean RESIZE_ON =
        !"0".equals(System.getenv("MACAPK_RESIZE_CONFIG"));

    /** STAGE 2: relaunch an activity whose changed, bucket-filtered config bits
     *  are not all declared in android:configChanges, the way AOSP's
     *  ActivityRecord.shouldRelaunchLocked does. Default OFF until measured:
     *  the relaunch path (ActivityHost.macapkRecreate) is exercised by the
     *  light/dark flip on AppCompat apps, but a relaunch of a native-engine
     *  activity (the cocos2d-x GD titles are the corpus's at-risk case) has
     *  never been run. With it off, such an activity keeps its instance, gets
     *  no onConfigurationChanged (which AOSP would not send it either), and
     *  its view tree still learns the new configuration. */
    static final boolean RELAUNCH_ON =
        "1".equals(System.getenv("MACAPK_RESIZE_RELAUNCH"));

    // ---- the committed display ------------------------------------------------

    private static volatile int sRealW, sRealH;   // px; 0 until Boot seeds

    /** The committed display width in px (decor included). Falls back to the
     *  native canvas before Boot has seeded, which is exactly the old answer. */
    public static int realWidthPx() {
        // Gate off = the pre-v1.0.1637 answer exactly: the LIVE canvas globals
        // (which Cmd+R / setRequestedOrientation move and a drag does not).
        int w = RESIZE_ON ? sRealW : 0;
        return w > 0 ? w : Math.round(NativeCocoa.phoneWidth() * pxPerPoint());
    }
    public static int realHeightPx() {
        int h = RESIZE_ON ? sRealH : 0;
        return h > 0 ? h : Math.round(NativeCocoa.phoneHeight() * pxPerPoint());
    }

    /** Pixels per point of the canvas BEFORE the first surface says what it
     *  really is: the launch screen's backing scale, which is what the device
     *  density is derived from (v1.0.2187, CocoaDisplayDensityDpi; 2 on Retina,
     *  1 on a 1x monitor). Was a literal 2 ("retina"), so on a 1x monitor the
     *  seed described a display twice the window's pixels until the first
     *  surface commit halved it. Under a MACAPK_DISPLAY_DENSITY pin the two can
     *  differ; the first surface commit then corrects the seed, as before. */
    private static float pxPerPoint() {
        return android.util.DisplayMetrics.macapkDeviceDensity();
    }

    /** Boot: seed from the native canvas, AFTER CocoaSetMaxAspectRatio has
     *  finished reshaping it (that call can grow or shrink g_phone_w/h
     *  synchronously; reading before it measured a canvas the window no longer
     *  has). With Stage 0 the canvas equals the render view's content size, so
     *  the first surfaceChanged commits nothing. */
    static void seedFromCanvas() {
        sRealW = Math.round(NativeCocoa.phoneWidth() * pxPerPoint());
        sRealH = Math.round(NativeCocoa.phoneHeight() * pxPerPoint());
    }

    // ---- the pure derivation ---------------------------------------------------

    /** Fill `m` and the display-derived fields of `c` for a real display of
     *  realW x realH px. Density fields of `m` are kept (the density is fixed
     *  for the process, at the launch screen's). Reads the decor accessors, which read the COMMITTED display, so
     *  the caller commits the size first.
     *
     *  AOSP (android14-release DisplayContent):
     *    computeScreenAppConfiguration :2385-2399
     *      appBounds = non-decor frame; orientation = (dw <= dh) ? PORTRAIT : LANDSCAPE
     *      screenWidthDp  = (int) (configFrame.width()  / density + 0.5f)
     *      screenHeightDp = (int) (configFrame.height() / density + 0.5f)
     *    computeSizeRanges :2546-2575 / adjustDisplaySizeRanges :2577
     *      smallestScreenWidthDp = (int) (min over ALL FOUR rotations of the
     *      config-frame width / density + 0.5f)  -- rotation-INVARIANT, so a
     *      rotate (Cmd+R) is not a smallest-width change.
     *  The pre-O max-aspect letterbox and the decor ladder are unchanged from
     *  Boot's v1.0.874 / v1.0.939 / v1.0.997 code, moved here verbatim in
     *  effect; see those notes in Boot.run's history. */
    public static void compute(int realW, int realH, DisplayMetrics m, Configuration c,
                               boolean log) {
        final float density = (m.density > 0f) ? m.density
                : android.util.DisplayMetrics.macapkDeviceDensity();
        final int navW   = android.view.Display.macapkNavDecorWidthPx();
        final int navH   = android.view.Display.macapkNavDecorHeightPx();
        final int status = android.view.Display.macapkStatusDecorPx();
        // DisplayMetrics = the APP area (v1.0.939): nav decor off the axis the
        // bar occupies (v1.0.997). Identical to Display.getWidth/getHeight.
        m.widthPixels  = realW - navW;
        m.heightPixels = realH - navH;
        // AOSP's pre-O maximum aspect ratio (v1.0.874). See
        // Boot.macapkMaxAspectRatio for the gate and its citations.
        final float maxAspect = Boot.sMaxAspectRatio;
        if (maxAspect >= 1.0f) {
            int w = m.widthPixels, h = m.heightPixels;
            int shortSide = Math.min(w, h), longSide = Math.max(w, h);
            if (shortSide > 0) {
                float containingRatio = (float) longSide / (float) shortSide;
                float ratio = Math.min(containingRatio, maxAspect);
                // AppCompatAspectRatioPolicy.applyAspectRatio's tolerance
                // (ASPECT_RATIO_ROUNDING_TOLERANCE = 0.005f) and rounding.
                if (containingRatio - ratio > 0.005f) {
                    if (w < h) m.heightPixels = (int) ((w * ratio) + 0.5f);
                    else       m.widthPixels  = (int) ((h * ratio) + 0.5f);
                    if (log) {
                        NativeLog.i("Mac.apk.Boot", "pre-O max aspect " + maxAspect
                            + ": window " + w + "x" + h + " -> "
                            + m.widthPixels + "x" + m.heightPixels);
                    }
                }
            }
        }
        c.densityDpi = (m.densityDpi > 0) ? m.densityDpi
                : android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;
        c.screenWidthDp = (int) (m.widthPixels / density + 0.5f);
        // v1.0.939: the config height excludes BOTH decor bars (WMS's "config"
        // rung of the size ladder), getSize/getMetrics only the navigation bar.
        c.screenHeightDp = (int) ((realH - navH - status) / density + 0.5f);
        // Rotation-invariant smallest width: the portrait config width is the
        // short side; the landscape one is the long side minus a side nav bar
        // when the bar moves (phone-class displays). Same ladder as
        // Display.getCurrentSizeRange's smallest.x.
        final int shortSide = Math.min(realW, realH), longSide = Math.max(realW, realH);
        final int sideNav = StatusBarDriver.navBarCanMove()
                ? android.view.Display.macapkNavDecorPx() : 0;
        c.smallestScreenWidthDp =
            (int) (Math.min(shortSide, longSide - sideNav) / density + 0.5f);
        // screenLayout FOLLOWS the dp metrics (AOSP reduceScreenLayout).
        c.macapkDeriveScreenLayout();
        c.orientation = (realW <= realH)
            ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        // The @hide windowConfiguration AndroidX Window reads reflectively:
        // bounds/maxBounds the whole display, appBounds the app area.
        android.graphics.Rect whole = new android.graphics.Rect(0, 0, realW, realH);
        c.windowConfiguration.setBounds(whole);
        c.windowConfiguration.setAppBounds(
            new android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels));
        c.windowConfiguration.setMaxBounds(whole);
    }

    // ---- the resize signal -----------------------------------------------------

    private static final Object sLock = new Object();
    private static int sPendingW, sPendingH;
    private static boolean sCommitPosted;
    private static boolean sApplying;
    private static android.os.Handler sOwnerHandler;

    /** NATIVE ENTRY (cocoa_bridge.mm drawInMTKView), any thread: the primary
     *  window's render view now has a w x h px drawable.
     *
     *  RETURNS whether the display is COMMITTED at w x h -- true only when no
     *  commit is queued or mid-apply and the committed size equals w x h.
     *  For a RESIZE the native side holds its macapkSurfaceChanged re-fire
     *  until this answers true (bounded), so a SurfaceView / GLSurfaceView
     *  engine hears surfaceChanged AFTER onConfigurationChanged and with
     *  Resources/DisplayMetrics already describing the new size -- AOSP's
     *  order (ViewRootImpl applies the configuration in performTraversals
     *  before SurfaceView.updateSurface fires surfaceChanged). v1.0.1637 first
     *  shipped the opposite order: the re-fire ran synchronously on the AppKit
     *  thread while the commit sat in the owner queue, so a live Unity surface
     *  was re-created against the OLD configuration; an intermittent Unity
     *  freeze on a hand-drag resize was observed on that build.
     *  The first-surface (boot) fire ignores the answer. Answers true when the
     *  display model is off, so nothing is ever held then. */
    public static boolean macapkNoteSurfaceSize(int w, int h) {
        if (!RESIZE_ON || w <= 0 || h <= 0) return true;
        if (!android.os.Looper.MACAPK_REAL_LOOPER) {
            // The legacy looper has no owner thread to serialize on; a post
            // there lands in GLOBAL_QUEUE, which a renderer-path frame never
            // drains, so the flag below could stick. Commit inline instead.
            synchronized (sLock) { sPendingW = w; sPendingH = h; }
            commitPending();
            return true;
        }
        android.os.Handler hd;
        synchronized (sLock) {
            sPendingW = w;
            sPendingH = h;
            if (!sCommitPosted && !sApplying && w == sRealW && h == sRealH) return true;
            if (sCommitPosted) return false;   // the queued commit takes the newest size
            hd = ownerHandler();
            if (hd == null) return true;       // no main Looper yet: Boot seeds from the canvas
            sCommitPosted = true;
        }
        if (!hd.post(DisplayConfig::commitPending)) {
            synchronized (sLock) { sCommitPosted = false; }
            return true;
        }
        return false;
    }

    private static android.os.Handler ownerHandler() {
        if (sOwnerHandler == null) {
            android.os.Looper main = android.os.Looper.getMainLooper();
            if (main == null) return null;
            // OWNER-ONLY on the real-looper path: the commit mutates Resources
            // and the view tree, so it must never run on the OS-main drain
            // (v1.0.1636). The legacy path has no owner distinction.
            sOwnerHandler = android.os.Looper.MACAPK_REAL_LOOPER
                ? android.os.Handler.macapkCreateOwnerOnly(main)
                : new android.os.Handler(main);
        }
        return sOwnerHandler;
    }

    /** Owner thread: apply the newest pending size, if it differs from the
     *  committed one. Idempotent, so ViewCanvasDriver.doSurfaceChanged can call
     *  it unconditionally before it measures. */
    public static void commitPending() {
        int w, h;
        synchronized (sLock) {
            sCommitPosted = false;
            w = sPendingW;
            h = sPendingH;
            sApplying = true;
        }
        try {
            if (!RESIZE_ON || w <= 0 || h <= 0) return;
            if (w == sRealW && h == sRealH) return;
            apply(w, h);
        } catch (Throwable t) {
            NativeLog.w(TAG, "display commit " + w + "x" + h + " threw: " + t);
        } finally {
            synchronized (sLock) { sApplying = false; }
        }
    }

    /** WINDOW POLICY PHASE 3 (owner thread): commit the app box an activity
     *  transition just gave the window -- the incoming activity's box, BEFORE it
     *  is attached (ActivityHost.performLaunch), or the revealed one's, before
     *  it resumes (ActivityHost.restoreTop) -- synchronously, so the activity is
     *  created with (or comes back to) its own configuration instead of being
     *  reconfigured a frame later (docs/window-policy-per-activity-orientation_
     *  2026-09-25.md R8).
     *
     *  NOT delivered to the top activity and no traversal scheduled: on a device
     *  the covered and revealed records never change (each ActivityRecord owns
     *  its configuration); only the PROCESS follows the new top
     *  (WindowProcessController.updateActivityConfigurationListener,
     *  android16-release WindowProcessController.java:1624-1644), which is the
     *  Application dispatch apply() still does. The incoming activity attaches
     *  with this configuration; a revealed one is diffed by restoreTop's
     *  deliverPendingTo right after, which finds nothing unless its box really
     *  changed. The frame that later reports this drawable size then finds it
     *  committed (macapkNoteSurfaceSize answers true at once), so there is ONE
     *  commit per transition. A no-op before Boot has seeded the display. */
    public static void macapkCommitPolicyBox(int w, int h) {
        if (!RESIZE_ON || w <= 0 || h <= 0) return;
        if (sRealW <= 0 || sRealH <= 0) return;
        synchronized (sLock) {
            sPendingW = w;
            sPendingH = h;
            sApplying = true;
        }
        try {
            if (w == sRealW && h == sRealH) return;
            apply(w, h, true);
        } catch (Throwable t) {
            NativeLog.w(TAG, "policy box commit " + w + "x" + h + " threw: " + t);
        } finally {
            synchronized (sLock) { sApplying = false; }
        }
    }

    // ---- the commit ---------------------------------------------------------------

    private static void apply(int w, int h) { apply(w, h, false); }

    /** @param activityTransition a window-policy activity transition's box
     *  (macapkCommitPolicyBox): no delivery to the top activity, no traversal. */
    private static void apply(int w, int h, boolean activityTransition) {
        final int oldW = sRealW, oldH = sRealH;
        // A host window resize / rotate is not input, so not in a recording: note it
        // (a replay refuses at the next `window` line unless the size comes back).
        if (InputRecorder.ON && !activityTransition && oldW > 0 && oldH > 0 && (oldW != w || oldH != h))
            InputRecorder.unrecorded("host display resize " + oldW + "x" + oldH + " -> " + w + "x" + h + " px");
        // Commit first: Display/WindowManager (and the decor accessors compute()
        // reads) answer the new display from here on, as DisplayInfo does on a
        // device before the configuration change is delivered.
        sRealW = w;
        sRealH = h;
        final android.app.Application app = android.app.Application.macapk_get();
        final Resources res = (app != null) ? app.getResources() : null;
        final Configuration cur = (res != null) ? res.getConfiguration() : null;
        if (cur == null) {
            NativeLog.i(TAG, "display " + w + "x" + h + " committed before Resources "
                + "exist; boot derives from it");
            return;
        }
        final Configuration next = new Configuration(cur);
        final DisplayMetrics m = new DisplayMetrics();
        m.setTo(res.getDisplayMetrics());
        compute(w, h, m, next, false);
        final int diff = cur.diff(next);
        final boolean bounds = boundsDiffer(cur, next);
        final boolean metricsChanged = !m.equals(res.getDisplayMetrics());
        NativeLog.i(TAG, "display " + oldW + "x" + oldH + " -> " + w + "x" + h + " px: "
            + cur.screenWidthDp + "x" + cur.screenHeightDp + "dp sw" + cur.smallestScreenWidthDp
            + " -> " + next.screenWidthDp + "x" + next.screenHeightDp + "dp sw"
            + next.smallestScreenWidthDp + " " + orientName(next.orientation)
            + " diff=0x" + Integer.toHexString(diff) + (bounds ? " +bounds" : "")
            + (activityTransition ? " (activity transition)" : "")
            + " [owner=" + android.os.Looper.macapkIsActivityThread() + "]");
        if (diff == 0 && !bounds && !metricsChanged) return;

        // 1. RESOURCES. The index bakes qualifiers at construction, so build a
        //    new one only when an app or framework threshold (or orientation) is
        //    actually crossed; a drag that stays inside every bucket reuses it.
        //    Synchronous on the owner thread, as AOSP builds the new
        //    ResourcesImpl on the main thread: a one-off hitch at the end of a
        //    drag that crosses a bucket, and no window in which the new
        //    Configuration is served by resources selected for the old one.
        Object liveIdx = Resources.macapkGetResourceIndex();
        if (liveIdx instanceof ResourceIndex
                && ((ResourceIndex) liveIdx).macapkSelectionChangesFor(next)) {
            long t0 = System.nanoTime();
            ResourceIndex built = Boot.macapkNewResourceIndex(next);
            if (built != null) {
                Resources.macapkSetResourceIndex(built);
                NativeLog.i(TAG, "ResourceIndex rebuilt for "
                    + next.screenWidthDp + "x" + next.screenHeightDp + "dp "
                    + orientName(next.orientation) + " in "
                    + ((System.nanoTime() - t0) / 1_000_000L) + "ms");
            }
        }
        res.updateConfiguration(next, m);
        // AOSP ResourcesManager.applyConfigurationToResources (android14-release
        // :1337-1369) pushes the PROCESS configuration + app-area display metrics
        // into Resources.getSystem() on every global change as well. The gap this
        // comment used to record is closed: Boot seeds getSystem() at bind, so a
        // resize moves it from the real boot size to the real new one, and the
        // GMS checkin no longer reads getSystem() (CheckinClient
        // .checkinProfileMetrics). Gated by MACAPK_SYSTEM_RES_FOLLOW in Resources.
        // NOT `next` itself: it is copied from the APP's Resources, so it carries
        // anything the app wrote there with updateConfiguration (a per-app
        // locale, most often), and the system configuration must not. Only the
        // display fields move, onto the system's own copy; the layout-direction
        // bits of screenLayout follow the locale, not the display, so the
        // system keeps its own.
        Resources.macapkEditSystemConfiguration(sys -> {
            final int dir = sys.screenLayout & Configuration.SCREENLAYOUT_LAYOUTDIR_MASK;
            adoptDisplayFields(next, sys);
            sys.screenLayout = (sys.screenLayout & ~Configuration.SCREENLAYOUT_LAYOUTDIR_MASK) | dir;
        }, m);

        // 2. THE PROCESS. A display change is a GLOBAL configuration change:
        //    Application.onConfigurationChanged + every registered
        //    ComponentCallbacks, once (ConfigurationController
        //    .handleConfigurationChanged). Services/providers are not tracked
        //    as callbacks here yet.
        if (diff != 0 && app != null) {
            try { app.dispatchConfigurationChanged(new Configuration(next)); }
            catch (Throwable t) { NativeLog.w(TAG, "Application config dispatch threw: " + t); }
        }
        // 3. DISPLAY LISTENERS, once.
        try { android.hardware.display.DisplayManager.macapkNotifyDisplayChanged(0); }
        catch (Throwable t) { NativeLog.w(TAG, "display listener dispatch threw: " + t); }

        // An activity transition stops here: see macapkCommitPolicyBox.
        if (activityTransition) return;

        // 4. THE TOP ACTIVITY. Buried activities are checked when revealed
        //    (ActivityHost.restoreTop -> deliverToActivity), which is when AOSP
        //    sends a stopped activity its pending configuration.
        android.app.Activity top = ActivityHost.peek();
        if (top != null) deliverToActivity(top, next);

        // 5. TRAVERSAL: a new configuration forces the next frame to measure
        //    and lay out (ViewRootImpl.updateConfiguration -> requestLayout).
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            try { android.view.ViewRootImpl.scheduleLayout(); } catch (Throwable ignored) {}
        }
    }

    /** Apply AOSP's relaunch-then-report decision for ONE activity against the
     *  live Resources configuration. Safe to call with no pending change (it
     *  returns at once), which is how restoreTop re-checks on reveal. */
    /** @return true when a RELAUNCH was scheduled (Stage 2), in which case the
     *  caller must not keep driving this instance (AOSP relaunches INSTEAD of
     *  resuming). */
    public static boolean deliverToActivity(android.app.Activity a, Configuration next) {
        if (a == null || next == null || a.isFinishing()) return false;
        Configuration cur = a.macapkCurrentConfig();
        Configuration lastReported = a.macapkLastReportedConfig();
        if (cur == null) cur = next;
        if (lastReported == null) lastReported = cur;
        final int sdk = PresentedSdk.get();
        final int publicDiff = cur.diff(next);
        final boolean bounds = boundsDiffer(cur, next);
        final int serverDiff = lastReported.diff(next);
        if (publicDiff == 0 && !bounds && serverDiff == 0) return false;
        final int handled = a.macapkRealConfigChanged();
        final int[][] buckets = appBuckets();

        // SERVER SIDE FIRST (ActivityRecord.ensureActivityConfiguration): the
        // activity is relaunched when a changed bit that survives the bucket
        // filter is not declared. android11-release ActivityRecord
        // getConfigurationChanges filters only screenSize/smallestWidth;
        // android14-release uses SizeConfigurationBuckets.filterDiff, which
        // android13 extended to screenLayout.
        final int changes = filterDiff(sdk, serverDiff, lastReported, next, buckets, true);
        if ((changes & ~handled) != 0) {
            a.macapkSetLastReportedConfig(next);
            boolean localeChanged = (changes & (Configuration.CONFIG_LOCALE
                | Configuration.CONFIG_LAYOUT_DIRECTION)) != 0;
            if ((RELAUNCH_ON || localeChanged) && ActivityHost.macapkRecreate(a)) {
                NativeLog.i(TAG, "relaunching " + a.getClass().getName() + " (changed 0x"
                    + Integer.toHexString(changes) + " not declared in configChanges 0x"
                    + Integer.toHexString(handled) + ")");
                return true;
            }
            // Stage 2 off: keep the instance. AOSP never reports a change to an
            // activity it relaunches, so no callback; the window still learns.
            NativeLog.i(TAG, "config change 0x" + Integer.toHexString(changes)
                + " not declared by " + a.getClass().getName() + " (declared 0x"
                + Integer.toHexString(handled) + "); AOSP relaunches it, Mac.apk keeps "
                + "the instance (MACAPK_RESIZE_RELAUNCH=1 to relaunch)");
            a.macapkSetCurrentConfig(next);
            a.macapkDeliverConfigToWindow(next, false);
            return false;
        }
        a.macapkSetLastReportedConfig(next);

        // CLIENT SIDE (ActivityThread.performActivityConfigurationChanged).
        boolean report;
        boolean updateCurrent;
        if (sdk <= 30) {
            // android11: report iff every public changed bit is declared.
            report = publicDiff != 0 && (~handled & publicDiff) == 0;
            updateCurrent = publicDiff != 0;
        } else if (sdk <= 32) {
            // android12: diff is bucket-filtered first; an all-filtered change
            // with unchanged bounds returns before anything is recorded.
            int f = filterDiff(sdk, publicDiff, cur, next, buckets, false);
            report = f != 0 && (~handled & f) == 0;
            updateCurrent = f != 0 || bounds;
        } else if (sdk == 33) {
            // android13 shouldReportChange: the filtered diff, or the raw one
            // when filtering removed everything.
            int f = filterDiff(sdk, publicDiff, cur, next, buckets, false);
            int d = (f != 0) ? f : publicDiff;
            report = publicDiff != 0 && (~handled & d) == 0;
            updateCurrent = publicDiff != 0 || bounds;
        } else {
            // android14 shouldReportChange: a window-bounds change is ALWAYS
            // reported; otherwise as 13. mCurrentConfig moves only on report.
            int f = filterDiff(sdk, publicDiff, cur, next, buckets, false);
            int d = (f != 0) ? f : publicDiff;
            report = bounds || (publicDiff != 0 && (~handled & d) == 0);
            updateCurrent = report;
        }
        if (updateCurrent) a.macapkSetCurrentConfig(next);
        NativeLog.i(TAG, a.getClass().getName() + ": config 0x"
            + Integer.toHexString(publicDiff) + (bounds ? "+bounds" : "")
            + " declared 0x" + Integer.toHexString(handled) + " api" + sdk
            + (report ? " -> onConfigurationChanged" : " -> resources only"));
        a.macapkDeliverConfigToWindow(new Configuration(next), report);
        return false;
    }

    /** Copy the DISPLAY-derived fields of `from` into `to` (dp sizes,
     *  smallest width, screenLayout, orientation, density, window bounds).
     *  For a configuration built from an older snapshot -- the appearance
     *  flip's -- so it cannot revert a resize committed meanwhile. Returns
     *  true if anything changed. */
    static boolean adoptDisplayFields(Configuration from, Configuration to) {
        if (from == null || to == null) return false;
        boolean changed = to.screenWidthDp != from.screenWidthDp
            || to.screenHeightDp != from.screenHeightDp
            || to.smallestScreenWidthDp != from.smallestScreenWidthDp
            || to.screenLayout != from.screenLayout
            || to.orientation != from.orientation
            || to.densityDpi != from.densityDpi
            || boundsDiffer(from, to);
        if (!changed) return false;
        to.screenWidthDp = from.screenWidthDp;
        to.screenHeightDp = from.screenHeightDp;
        to.smallestScreenWidthDp = from.smallestScreenWidthDp;
        to.screenLayout = from.screenLayout;
        to.orientation = from.orientation;
        to.densityDpi = from.densityDpi;
        to.windowConfiguration.setBounds(from.windowConfiguration.getBounds());
        to.windowConfiguration.setAppBounds(from.windowConfiguration.getAppBounds());
        to.windowConfiguration.setMaxBounds(from.windowConfiguration.getMaxBounds());
        return true;
    }

    /** Re-check on reveal (ActivityHost.restoreTop): a buried activity is sent
     *  the configuration it missed while it was not on top. */
    public static boolean deliverPendingTo(android.app.Activity a) {
        try {
            android.app.Application app = android.app.Application.macapk_get();
            Resources res = (app != null) ? app.getResources() : null;
            Configuration live = (res != null) ? res.getConfiguration() : null;
            if (live != null) return deliverToActivity(a, new Configuration(live));
        } catch (Throwable t) {
            NativeLog.w(TAG, "reveal config re-check threw: " + t);
        }
        return false;
    }

    // ---- AOSP SizeConfigurationBuckets, per presented SDK ------------------------

    private static int[][] appBuckets() {
        Object idx = Resources.macapkGetResourceIndex();
        if (idx instanceof ResourceIndex) {
            try { return ((ResourceIndex) idx).macapkAppSizeBuckets(); }
            catch (Throwable ignored) {}
        }
        return new int[][] { new int[0], new int[0], new int[0], new int[0], new int[] { 0 } };
    }

    /** SizeConfigurationBuckets.filterDiff with the release differences that
     *  matter here (buckets are never null in this model -- an app with no size
     *  qualifiers has EMPTY buckets, which is what reportSizeConfigurations
     *  builds from an empty configuration list):
     *    API <= 30  server side only (android11 ActivityRecord
     *               getConfigurationChanges): screenSize + smallestWidth are
     *               dropped unless a threshold is crossed; screenLayout is not
     *               filtered. The API-30 client does not filter at all.
     *    API 31-32  android12 filterDiff: screenSize + smallestWidth only.
     *    API 33+    android13/14 filterDiff: also screenLayout, when its
     *               non-size fields are unchanged and neither the size bucket
     *               (shrinking always counts) nor a declared long/notlong flips. */
    static int filterDiff(int sdk, int diff, Configuration oldC, Configuration newC,
                          int[][] b, boolean serverSide) {
        if (sdk <= 30 && !serverSide) return diff;
        if ((diff & Configuration.CONFIG_SCREEN_SIZE) != 0) {
            boolean crosses = ResourceIndex.macapkDpCrosses(b[0], oldC.screenWidthDp, newC.screenWidthDp)
                || ResourceIndex.macapkDpCrosses(b[1], oldC.screenHeightDp, newC.screenHeightDp);
            if (!crosses) diff &= ~Configuration.CONFIG_SCREEN_SIZE;
        }
        if ((diff & Configuration.CONFIG_SMALLEST_SCREEN_SIZE) != 0) {
            if (!ResourceIndex.macapkDpCrosses(b[2], oldC.smallestScreenWidthDp,
                                                newC.smallestScreenWidthDp)) {
                diff &= ~Configuration.CONFIG_SMALLEST_SCREEN_SIZE;
            }
        }
        if (sdk >= 33 && (diff & Configuration.CONFIG_SCREEN_LAYOUT) != 0
                && nonSizeLayoutFieldsUnchanged(oldC.screenLayout, newC.screenLayout)) {
            boolean longSet = b[4].length > 0 && b[4][0] != 0;
            if (!crossesScreenLayoutSizeThreshold(b[3], oldC, newC)
                    && !(longSet && (oldC.screenLayout & Configuration.SCREENLAYOUT_LONG_MASK)
                                 != (newC.screenLayout & Configuration.SCREENLAYOUT_LONG_MASK))) {
                diff &= ~Configuration.CONFIG_SCREEN_LAYOUT;
            }
        }
        return diff;
    }

    /** android14-release SizeConfigurationBuckets.areNonSizeLayoutFieldsUnchanged. */
    private static boolean nonSizeLayoutFieldsUnchanged(int oldL, int newL) {
        final int nonSize = Configuration.SCREENLAYOUT_LAYOUTDIR_MASK
            | Configuration.SCREENLAYOUT_ROUND_MASK | Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        return (oldL & nonSize) == (newL & nonSize);
    }

    /** android14-release SizeConfigurationBuckets.crossesScreenLayoutSizeThreshold. */
    private static boolean crossesScreenLayoutSizeThreshold(int[] sizes, Configuration a,
                                                            Configuration b) {
        final int aSize = a.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        final int bSize = b.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        if (aSize == bSize) return false;
        // "Any time the new layout size is smaller than the old layout size, the
        // activity has crossed a size threshold."
        if (!b.isLayoutSizeAtLeast(aSize)) return true;
        if (sizes != null) {
            for (int s : sizes) {
                if (a.isLayoutSizeAtLeast(s) != b.isLayoutSizeAtLeast(s)) return true;
            }
        }
        return false;
    }

    /** android14-release ConfigurationHelper.shouldUpdateWindowMetricsBounds. */
    private static boolean boundsDiffer(Configuration a, Configuration b) {
        return !java.util.Objects.equals(a.windowConfiguration.getBounds(),
                                         b.windowConfiguration.getBounds())
            || !java.util.Objects.equals(a.windowConfiguration.getMaxBounds(),
                                         b.windowConfiguration.getMaxBounds());
    }

    private static String orientName(int o) {
        return o == Configuration.ORIENTATION_LANDSCAPE ? "land" : "port";
    }
}
