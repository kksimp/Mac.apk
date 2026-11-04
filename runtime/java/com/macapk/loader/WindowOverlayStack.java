// WindowOverlayStack -- the process-wide stack of floating windows added via
// WindowManager.addView (v1.0.691).
//
// Mac.apk owns ONE NSWindow, so every Dialog / PopupWindow / PopupMenu / Spinner
// dropdown / AutoCompleteTextView suggestion list / Toast that AOSP would put in
// its own window is instead an OVERLAY LAYER composited over the Activity's
// content view. Before this, MacApkWindowManager.addView was a hard no-op, so
// all of those built their view tree correctly and then drew ZERO pixels -- the
// single highest-blast-radius no-op the v677 audit found (31/37 apps use
// PopupWindow alone).
//
// This class is only the REGISTRY + geometry. ViewCanvasDriver draws the stack
// after the content view each frame, and TouchDispatcher hit-tests it top-down
// before the Activity. Both call into here so the ordering lives in one place.
package com.macapk.loader;

import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

public final class WindowOverlayStack {

    /** One floating window: its root view plus the params that place it. */
    public static final class Layer {
        public final View view;
        public WindowManager.LayoutParams params;
        public final Rect bounds = new Rect();   // last laid-out screen rect
        /** The Dialog that owns this decor, when one does. AOSP reaches the
         *  Dialog through the DecorView's Window callback; we have no such
         *  chain, so an outside tap had nothing to cancel and was simply
         *  swallowed. Weak so a leaked layer cannot pin an Activity. */
        java.lang.ref.WeakReference<android.app.Dialog> owner;
        /** The Activity whose window token this window was added under (AOSP:
         *  LayoutParams.token = the activity's token, via its WindowManager).
         *  Recovered from the view's Context. Null for an Application-context
         *  window, which no activity's destruction removes. */
        java.lang.ref.WeakReference<android.app.Activity> ownerActivity;
        /** Where presentForGl last RASTERIZED this layer, and whether it was
         *  drawn at all (visible) in that raster. Touched only by the GL thread
         *  that drives presentForGl. See RERASTER_ON_RELAYOUT. */
        final Rect lastRasterBounds = new Rect();
        boolean lastRasterDrawn;
        Layer(View view, WindowManager.LayoutParams params) {
            this.view = view;
            this.params = params;
            android.app.Activity a = activityOf(view != null ? view.getContext() : null);
            if (a != null) ownerActivity = new java.lang.ref.WeakReference<>(a);
        }
    }

    /** Called by Dialog.show() right after its decor is added, so an
     *  ACTION_OUTSIDE on that layer can reach Dialog.cancel(). */
    public static void macapkSetOwner(View decor, android.app.Dialog d) {
        if (decor == null || d == null) return;
        synchronized (LOCK) {
            for (Layer l : LAYERS) {
                if (l.view == decor) { l.owner = new java.lang.ref.WeakReference<>(d); return; }
            }
        }
    }

    // Add order == z order (last added is on top), matching AOSP's window stack
    // for same-type application windows. Guarded because addView can be called
    // off the loop thread (a Handler.post from a background thread) while the
    // draw/touch code reads it on the loop thread.
    private static final Object LOCK = new Object();
    private static final List<Layer> LAYERS = new ArrayList<>();

    // GL-surface compositing (2026-07-23, see presentForGl below): whether the
    // stack's rendered content needs to be re-rasterized and pushed to native
    // before the next present, and whether the GL overlay layer currently
    // holds a bitmap that must be cleared once the stack goes back to empty.
    private static volatile boolean sDirty = false;
    private static volatile boolean sGlOverlayShowing = false;

    // FRAMESPEED (2026-09-26, docs/frame-speed-canvas-hotspots_2026-09-26.md):
    // presentForGl rasterizes the WHOLE surface on every dirty present (a touch
    // delivered to the dialog, an add/update/remove), and it used to allocate a
    // fresh surface-sized ARGB_8888 Bitmap each time and recycle it: at the
    // 3360x1796 window a 24 MB int[] (zeroed, then SerialGC garbage) plus a new
    // BufferedImage, ~1 ms before a single pixel is drawn (measured on a bare
    // JVM, debug/framespeed_bench). The overlay is now drawn into ONE retained
    // bitmap of the surface size, zeroed through its live array before each
    // draw -- the same all-transparent starting state a fresh bitmap has -- and
    // given back when the stack empties or the surface resizes. A new Canvas is
    // still made per present, so the transform and clip start clean exactly as
    // before. Only the GL thread that drives presentForGl touches it.
    // MACAPK_FRAMESPEED_OVERLAY=0 restores the per-present allocate + recycle.
    static final boolean OVERLAY_REUSE =
        !"0".equals(System.getenv("MACAPK_FRAMESPEED_OVERLAY"));
    private static volatile android.graphics.Bitmap sOverlayBmp;

    /** The bitmap one GL-overlay present draws into: all-transparent, w x h. */
    static android.graphics.Bitmap overlayBitmap(int w, int h) {
        if (OVERLAY_REUSE) {
            android.graphics.Bitmap b = sOverlayBmp;
            if (b != null && b.getWidth() == w && b.getHeight() == h) {
                int[] live = b.macapkLiveArgbOrNull();
                if (live != null) {
                    java.util.Arrays.fill(live, 0);
                    return b;
                }
            }
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        if (OVERLAY_REUSE) {
            // Install the canvas backing now, so the next present finds the live
            // array (a fresh Bitmap has no AWT image until a Canvas wraps it).
            new android.graphics.Canvas(b);
            sOverlayBmp = b;
        }
        return b;
    }
    /** MACAPK_OVERLAY_RERASTER_ON_RELAYOUT (default ON; =0 restores the raster
     *  that only an add / update / remove / key / inside-touch refreshes, for
     *  bisecting). presentForGl re-measures and re-lays out every layer on EVERY
     *  frame -- touch hit-testing reads those bounds -- but used to re-rasterize
     *  only on those explicit events. So a layer whose measured size or position
     *  changed on its own kept its OLD picture while touches followed the NEW
     *  layout. MEASURED on GD Meltdown's consent dialog (a GL game, so this is
     *  the only compositor it has): drawn 676px tall at y 512..1124 while hit
     *  tested as Rect(588, 629 - 2772, 1007), 378 tall, so the drawn CONTINUE sat
     *  149px above where a tap on it landed and the first click on it only
     *  re-dirtied the raster
     *  (docs/fleet-games-smashhit-gd-meltdown-jumplite_2026-09-26.md, G4). AOSP
     *  has no "re-laid out but picture kept" state: ViewRootImpl
     *  .performTraversals draws after any layout that moved or resized the
     *  window. Now a layer whose bounds or visibility differ from its last
     *  raster marks the stack dirty. (A per-view invalidate inside an unmoved
     *  layer -- a spinner, a caret -- still needs a per-root invalidate signal;
     *  our View.invalidate() is process-global, see the lane report.) */
    static final boolean RERASTER_ON_RELAYOUT =
        !"0".equals(System.getenv("MACAPK_OVERLAY_RERASTER_ON_RELAYOUT"));

    /** MACAPK_WINDOW_SHADOW (default ON; =0 draws a window's tree with no
     *  shadow, the pre-change overlay). A window whose root has a Z (a dialog
     *  decor with its theme's windowElevation) casts its outline's shadow under
     *  itself, as the window's renderer does on a device -- see drawWindow. */
    static final boolean WINDOW_SHADOW =
        !"0".equals(System.getenv("MACAPK_WINDOW_SHADOW"));

    /** Test seam for the offline gate (debug/w5alertdialog_gate.sh): when set,
     *  presentForGl hands its raster here instead of to NativeCocoa, which a
     *  bare JVM cannot link. Never set by the runtime. */
    interface PresentSink { void present(int[] argb, int w, int h); }
    static volatile PresentSink sPresentSink;

    // Env-gated (MACAPK_OVERLAY_DIAG=1), fire-once dump of the exact layer/Button
    // rects presentForGl computes, so a click target can be derived precisely
    // when debugging overlay hit-testing. Zero cost when unset.
    private static final boolean DIAG = "1".equals(System.getenv("MACAPK_OVERLAY_DIAG"));
    private static volatile boolean sLoggedOnce = false;
    /** Rate-limit for the modal-consume log below. A touch swallowed by an
     *  overlay produces NO other trace, so this is the only signal. */
    private static int sConsumeLogged = 0;

    private WindowOverlayStack() { }

    /** AOSP WindowManagerGlobal (android14-release) gives the window's root view
     *  the params it was added with: addView does `view.setLayoutParams(wparams)`
     *  (:403) before mViews.add, and updateViewLayout does the same (:433). So on
     *  a device `root.getLayoutParams()` IS the WindowManager.LayoutParams, and
     *  apps read their window flags back through it. Ours never did, so the
     *  root answered null. Measured: King's usdk PopupDialog.open (Candy Crush's
     *  first-run Terms of Use) calls wndManager.addView(decorView, lp), then
     *  reads `((WindowManager.LayoutParams) decorView.getLayoutParams()).flags`
     *  (bytecode 479-510, source line 172) to clear FLAG_NOT_FOCUSABLE and
     *  updateViewLayout -- and died there with an NPE ("Cannot read field
     *  flags because <local2> is null", run.log 2026-09-26), leaving the popup
     *  window added but never focusable (BACK and keys went to the game). MACAPK_WM_SET_LAYOUT_PARAMS=0 restores
     *  the pre-change registry that leaves the root's LayoutParams untouched. */
    static final boolean SET_LAYOUT_PARAMS =
        !"0".equals(System.getenv("MACAPK_WM_SET_LAYOUT_PARAMS"));

    private static void giveRootItsParams(View view, WindowManager.LayoutParams wlp) {
        if (!SET_LAYOUT_PARAMS || wlp == null) return;
        // View.setLayoutParams only stores and resolves (no parent: a window
        // root has none), so it cannot fail on a root; guarded anyway, since a
        // throw here would lose the window the app just asked for.
        try { view.setLayoutParams(wlp); } catch (Throwable ignored) { }
    }

    public static void add(View view, ViewGroup.LayoutParams params) {
        if (view == null) return;
        WindowManager.LayoutParams wlp = asWlp(params);
        giveRootItsParams(view, wlp);
        synchronized (LOCK) {
            // AOSP replaces the params if the same view is re-added rather than
            // stacking a duplicate.
            for (Layer l : LAYERS) {
                if (l.view == view) { l.params = wlp; return; }
            }
            LAYERS.add(new Layer(view, wlp));
        }
        // Tell the view it is attached. A window we put on screen genuinely IS
        // attached, but only ViewCanvasDriver ever dispatched this, and only for
        // the content root -- so every Dialog, PopupWindow and Spinner popup
        // answered isShown()=false and getLocationInWindow()=0,0 while visibly
        // on screen. That was harmless while those getters were stubs; once
        // View.java was made AOSP-faithful (v1.0.924, DISP.hier.* rows) the
        // detached branch became REAL, and ProgressBar.setVisible(isShown())
        // would blank a spinner inside a dialog while popup anchoring and IME
        // cursor anchoring computed from 0,0.
        view.macapkClearWindowRemoved();   // a new window for this root
        try { view.macapkDispatchAttachedToWindow(); }
        catch (Throwable ignored) { /* a view that refuses to attach still shows */ }
        sDirty = true;
        try {
            NativeLog.i("Mac.apk.Overlay", "addView " + view.getClass().getName()
                    + " (stack=" + size() + ") w=" + wlp.width + " h=" + wlp.height
                    + " gravity=" + wlp.gravity + " type=" + wlp.type
                    + " flags=0x" + Integer.toHexString(wlp.flags)
                    + " by=" + firstGuestFrame()
                    + " thread=" + Thread.currentThread().getName()
                    + (android.os.Looper.macapkInGlRendererCall() ? " gl=1" : "")
                    + " app=" + firstAppFrame()
                    + " text=[" + overlayText(view) + "]");
        } catch (Throwable ignored) { /* NativeLog unavailable off-runtime */ }
    }

    /** The text an overlay actually displays. A window that is modal but never
     *  composited is a silent input killer -- every touch vanishes with no
     *  trace. Logging its text turns "touch does nothing" into a one-line
     *  diagnosis (e.g. a cocos2d-x CCMessageBox naming the engine's error). */
    private static String overlayText(View v) {
        StringBuilder sb = new StringBuilder();
        collectText(v, sb, 0);
        return sb.toString();
    }

    private static void collectText(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 6 || sb.length() > 160) return;
        if (v instanceof android.widget.TextView) {
            // KeyLogPolicy.viewText: a label verbatim, an editor's / password field's
            // contents as <redacted:N> (a dialog can hold a filled-in EditText).
            final android.widget.TextView tv = (android.widget.TextView) v;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(KeyLogPolicy.viewText(tv, cs));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), sb, depth + 1);
        }
    }

    /** The first frame that is neither ours nor the platform's: the APP code
     *  that asked for this window. firstGuestFrame() stops at three frames and
     *  so, for a Dialog, only ever names Dialog.show <- Activity.showDialog;
     *  the caller that matters (e.g. a billing helper that shows a dialog id
     *  meant for something else) sits further down. Together with thread= it
     *  answers "who opened this window, and on which thread": a window opened
     *  from the GL renderer thread is one an AOSP device would have refused
     *  (Dialog's `new Handler()` throws there, docs/gl-thread-has-no-looper_2026-10-01.md). */
    public static String firstAppFrame() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("android.") || c.startsWith("androidx.")
                || c.startsWith("java.") || c.startsWith("javax.")
                || c.startsWith("jdk.") || c.startsWith("sun.")
                || c.startsWith("com.macapk.") || c.startsWith("dalvik.")
                || c.startsWith("com.android.internal.")) continue;
            return c + "." + e.getMethodName();
        }
        return "?";
    }

    /** The first stack frame OUTSIDE our own window plumbing -- i.e. whoever in
     *  the guest (or in our framework) actually asked for this window. An
     *  overlay that consumes touches is invisible in a log that only says a
     *  FrameLayout appeared; naming the adder makes it one grep. */
    private static String firstGuestFrame() {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            // Skip our own window plumbing -- it is the same for every caller
            // and tells you nothing about who wanted the window.
            if (c.startsWith("com.macapk.loader.WindowOverlayStack")
                || c.startsWith("com.macapk.loader.MacApkWindowManager")
                || c.startsWith("android.view.WindowManager")) continue;
            if (shown > 0) sb.append(" <- ");
            sb.append(c).append('.').append(e.getMethodName());
            if (++shown == 3) break;
        }
        return shown == 0 ? "?" : sb.toString();
    }

    public static void update(View view, ViewGroup.LayoutParams params) {
        WindowManager.LayoutParams wlp = asWlp(params);
        boolean found = false;
        synchronized (LOCK) {
            for (Layer l : LAYERS) {
                if (l.view == view) { l.params = wlp; sDirty = true; found = true; break; }
            }
        }
        // AOSP updateViewLayout :433 (see SET_LAYOUT_PARAMS). AOSP sets it and
        // then throws for a view it does not know; we stay lenient and leave an
        // unknown view alone.
        if (found) giveRootItsParams(view, wlp);
    }

    public static void remove(View view) {
        boolean removed = false;
        synchronized (LOCK) {
            for (int i = LAYERS.size() - 1; i >= 0; i--) {
                if (LAYERS.get(i).view == view) { LAYERS.remove(i); sDirty = true; removed = true; break; }
            }
        }
        // A removed window's pixels must go with it. AOSP tears down the
        // ViewRootImpl and its surface in removeView; we keep a RASTERIZED copy
        // in a CALayer, and the Canvas driver additionally paints the overlay
        // stack into its own layer and IDLE-SKIPS repaints when nothing is
        // scheduled (the M6 gate). So a dismissed dialog could stay on screen as
        // a ghost -- the app is gone, taps fall through to the game beneath, and
        // the user is left tapping an image that cannot respond. That is exactly
        // what HCR 1.8.1's rate dialog did: its Rate listener provably ran to
        // completion (it wrote APP_RATED=1 to disk) and dismiss() was called,
        // yet the popup stayed visible.
        //
        // Scheduling a traversal here defeats the idle-skip for one frame, so
        // whichever compositor painted the overlay repaints without it.
        if (removed) {
            // AOSP WindowManagerGlobal.removeViewLocked -> ViewRootImpl.die ->
            // doDie -> dispatchDetachedFromWindow: a removed window's tree is
            // detached (Dialog.dismissDialog uses removeViewImmediate, so this is
            // synchronous). v1.0.1643: add() attached but remove() never
            // detached, so a dismissed dialog's ProgressBar kept ticking forever,
            // pinning its activity and forcing a traversal 20x a second.
            try { view.macapkDispatchDetachedFromWindow(); } catch (Throwable ignored) { }
            try { android.view.ViewRootImpl.schedule(); } catch (Throwable ignored) { }
        }
        // Symmetric with add()'s log above: unambiguous proof a dialog/overlay
        // was actually dismissed (vs. just not re-logged). Cheap (fires only on
        // a real removal), so kept unconditionally like the add() log.
        if (removed) {
            try {
                NativeLog.i("Mac.apk.Overlay", "removeView " + view.getClass().getName()
                    + " (stack now=" + size() + ")");
            } catch (Throwable ignored) { }
        }
    }

    private static android.app.Activity activityOf(android.content.Context c) {
        for (int i = 0; c != null && i < 16; i++) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            if (!(c instanceof android.content.ContextWrapper)) return null;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** AOSP WindowManagerGlobal.closeAll(token, ...), which
     *  ActivityThread.handleDestroyActivity runs after onDestroy (android14
     *  :5674-5693): every window the destroyed activity still has on screen is
     *  removed (AOSP logs it as leaked). v1.0.1642: nothing did this, so a
     *  popup or dialog outlived its activity and kept drawing over, and taking
     *  input from, whatever came next. */
    public static void closeAll(android.app.Activity a) {
        if (a == null) return;
        java.util.List<View> doomed = new java.util.ArrayList<>();
        synchronized (LOCK) {
            for (Layer l : LAYERS) {
                android.app.Activity o = l.ownerActivity != null ? l.ownerActivity.get() : null;
                if (o == a) doomed.add(l.view);
            }
        }
        for (View v : doomed) {
            try {
                NativeLog.w("Mac.apk.Overlay", a.getClass().getName()
                    + " has leaked window " + v.getClass().getName()
                    + " that was originally added here -- removing it");
            } catch (Throwable ignored) { }
            remove(v);
        }
    }

    /** Key delivery to the FOCUSED window, which is the top visible window
     *  that is not FLAG_NOT_FOCUSABLE (AOSP: input dispatch targets the
     *  focused window; the activity behind a dialog never sees the key). A
     *  Dialog's window gets the key through its Window.Callback, as its
     *  DecorView would deliver it. Returns false when no overlay is focusable,
     *  i.e. the activity window has focus.
     *
     *  v1.0.1658 (review issue 1, clipboard-bridge lane): once a focused
     *  overlay is found this method always `return true`s, the same as before
     *  -- an overlay OWNS the key even when its own view tree declines it, the
     *  way a device's ViewRootImpl for that window would keep going internally
     *  rather than letting the key fall through to a window BEHIND it. AOSP
     *  runs the shortcut stage (ViewRootImpl.java:6972-6984) on that same
     *  window's DecorView after the view tree declines, so this method now
     *  does the same in-place instead of returning early and leaving the
     *  caller to re-run macapkKeyShortcutStage against the ACTIVITY window --
     *  which is a different window and, before this fix, was never reached at
     *  all here because this method's old unconditional `return true` made
     *  the caller believe the key was already handled. See
     *  TouchDispatcher.macapkShortcutEligible for the shared predicate and
     *  docs/clipboard-keyboard-shortcuts_2026-09-28.md section "overlay
     *  shortcut stage was dead code". */
    public static boolean deliverKey(android.view.KeyEvent ev) {
        return deliverKeyResult(ev) != OVERLAY_NONE;
    }

    /** deliverKeyResult / deliverGenericMotion answers: no focusable overlay
     *  is open (the activity window is focused), or the focused overlay
     *  declined / handled the event. deliverKey collapses the last two into
     *  "the overlay owns it"; a caller that runs a stage AFTER the view tree
     *  (the gamepad fallback keys, GamepadBridge) needs them apart. */
    public static final int OVERLAY_NONE = -1;
    public static final int OVERLAY_UNHANDLED = 0;
    public static final int OVERLAY_HANDLED = 1;

    /** A generic motion event (a joystick) for the focused overlay window:
     *  AOSP delivers it to the focused window exactly like a key
     *  (InputDispatcher findFocusedWindowTargetsLocked serves both), so the
     *  same top-most focusable layer gets it, through its Dialog's
     *  Window.Callback when it has one. */
    public static int deliverGenericMotion(android.view.MotionEvent ev) {
        List<Layer> layers = snapshot();
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            View v = layer.view;
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            int flags = layer.params != null ? layer.params.flags : 0;
            if ((flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) continue;
            android.app.Dialog d = (layer.owner != null) ? layer.owner.get() : null;
            android.view.Window w = d != null ? d.getWindow() : null;
            android.view.Window.Callback cb = w != null ? w.getCallback() : null;
            boolean handled = false;
            try {
                if (d != null) {
                    handled = cb != null ? cb.dispatchGenericMotionEvent(ev) : d.dispatchGenericMotionEvent(ev);
                } else {
                    handled = v.dispatchGenericMotionEvent(ev);
                }
            } catch (Throwable t) {
                try { NativeLog.w("Mac.apk.Overlay", "overlay generic motion threw: " + t); } catch (Throwable ig) { }
            }
            sDirty = true;
            return handled ? OVERLAY_HANDLED : OVERLAY_UNHANDLED;
        }
        return OVERLAY_NONE;
    }

    /** A POINTER generic event (a mouse wheel's ACTION_SCROLL, v1.0.2273) for
     *  the window under the pointer, by the same rules as a touch DOWN
     *  (hitTestDeliver): the top-most touchable layer containing the point gets
     *  it in its own coordinates; outside a MODAL layer it is swallowed (the
     *  content behind a dialog does not scroll); a non-modal layer lets it
     *  through. @return true when an overlay took it (handled or swallowed). */
    public static boolean deliverPointerGeneric(android.view.MotionEvent ev,
                                                float contentX, float contentY) {
        final List<Layer> layers = snapshot();
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            View v = layer.view;
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            int flags = layer.params != null ? layer.params.flags : 0;
            if ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) continue;
            if (layer.bounds.contains((int) contentX, (int) contentY)) {
                final float dx = (contentX - layer.bounds.left) - ev.getX();
                final float dy = (contentY - layer.bounds.top) - ev.getY();
                ev.offsetLocation(dx, dy);
                try {
                    android.app.Dialog d = (layer.owner != null) ? layer.owner.get() : null;
                    android.view.Window w = d != null ? d.getWindow() : null;
                    android.view.Window.Callback cb = w != null ? w.getCallback() : null;
                    if (cb != null) cb.dispatchGenericMotionEvent(ev);
                    else v.dispatchGenericMotionEvent(ev);
                } catch (Throwable t) {
                    try { NativeLog.w("Mac.apk.Overlay", "overlay wheel threw: " + t); } catch (Throwable ig) { }
                } finally {
                    ev.offsetLocation(-dx, -dy);
                }
                sDirty = true;
                return true;
            }
            final boolean nonModal = NOT_FOCUSABLE_IS_NOT_MODAL
                ? (flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) != 0
                : (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0;
            if (nonModal) continue;
            return true;
        }
        return false;
    }

    /** HOVER (GitHub #25): which window the pointer hovers, by deliverPointerGeneric's
     *  rules -- the top-most touchable layer containing the point (its view), or
     *  HOVER_SWALLOWED when a modal layer above it owns the pointer, or null for
     *  the activity window underneath. TouchDispatcher sends HOVER_ENTER / EXIT
     *  when this answer changes, as InputDispatcher does per window. */
    public static final Object HOVER_SWALLOWED = new Object();
    public static Object hoverWindowAt(float contentX, float contentY) {
        final List<Layer> layers = snapshot();
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            View v = layer.view;
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            int flags = layer.params != null ? layer.params.flags : 0;
            if ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) continue;
            if (layer.bounds.contains((int) contentX, (int) contentY)) return v;
            final boolean nonModal = NOT_FOCUSABLE_IS_NOT_MODAL
                ? (flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) != 0
                : (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0;
            if (nonModal) continue;
            return HOVER_SWALLOWED;
        }
        return null;
    }
    /** Hand one generic pointer event to the layer whose root view is
     *  `layerView`, in that layer's coordinates, through its Dialog's window
     *  callback when it has one (as deliverPointerGeneric does). False when the
     *  layer has gone. */
    public static boolean deliverGenericToLayer(View layerView, android.view.MotionEvent ev,
                                                float contentX, float contentY) {
        for (Layer layer : snapshot()) {
            if (layer.view != layerView) continue;
            final float dx = (contentX - layer.bounds.left) - ev.getX();
            final float dy = (contentY - layer.bounds.top) - ev.getY();
            ev.offsetLocation(dx, dy);
            try {
                android.app.Dialog d = (layer.owner != null) ? layer.owner.get() : null;
                android.view.Window w = d != null ? d.getWindow() : null;
                android.view.Window.Callback cb = w != null ? w.getCallback() : null;
                if (cb != null) cb.dispatchGenericMotionEvent(ev);
                else layerView.dispatchGenericMotionEvent(ev);
            } catch (Throwable t) {
                try { NativeLog.w("Mac.apk.Overlay", "overlay hover threw: " + t); } catch (Throwable ig) { }
            } finally {
                ev.offsetLocation(-dx, -dy);
            }
            sDirty = true;
            return true;
        }
        return false;
    }

    public static int deliverKeyResult(android.view.KeyEvent ev) {
        List<Layer> layers = snapshot();
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            View v = layer.view;
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            int flags = layer.params != null ? layer.params.flags : 0;
            if ((flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) continue;
            android.app.Dialog d = (layer.owner != null) ? layer.owner.get() : null;
            android.view.Window w = d != null ? d.getWindow() : null;
            android.view.Window.Callback cb = w != null ? w.getCallback() : null;
            boolean handled = false;
            try {
                if (d != null) {
                    handled = cb != null ? cb.dispatchKeyEvent(ev) : d.dispatchKeyEvent(ev);
                } else {
                    handled = v.dispatchKeyEvent(ev);
                }
            } catch (Throwable t) {
                try { NativeLog.w("Mac.apk.Overlay", "overlay key threw: " + t); } catch (Throwable ig) { }
            }
            // AOSP ViewRootImpl.java:6972-6984: only after the view tree
            // declined, on the SAME focused window's DecorView. Same
            // eligibility predicate as the activity-window stage
            // (TouchDispatcher.macapkShortcutEligible) so an overlay and the
            // activity window agree on what counts as a shortcut.
            if (!handled && TouchDispatcher.macapkShortcutEligible(ev)) {
                try {
                    handled = d != null
                        ? (cb != null ? cb.dispatchKeyShortcutEvent(ev) : d.dispatchKeyShortcutEvent(ev))
                        : v.dispatchKeyShortcutEvent(ev);
                } catch (Throwable t) {
                    try { NativeLog.w("Mac.apk.Overlay", "overlay key shortcut threw: " + t); } catch (Throwable ig) { }
                }
            }
            sDirty = true;
            try {
                if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                    NativeLog.i("Mac.apk.Overlay", "BACK "
                        + (ev.getAction() == android.view.KeyEvent.ACTION_DOWN ? "down" : "up")
                        + " -> focused window " + v.getClass().getName()
                        + " handled=" + handled);
                }
            } catch (Throwable ignored) { }
            return handled ? OVERLAY_HANDLED : OVERLAY_UNHANDLED;
        }
        return OVERLAY_NONE;
    }

    public static boolean isEmpty() {
        synchronized (LOCK) { return LAYERS.isEmpty(); }
    }

    public static int size() {
        synchronized (LOCK) { return LAYERS.size(); }
    }

    /** Snapshot for the draw/touch pass (bottom-to-top). */
    public static List<Layer> snapshot() {
        synchronized (LOCK) { return new ArrayList<>(LAYERS); }
    }

    private static WindowManager.LayoutParams asWlp(ViewGroup.LayoutParams p) {
        if (p instanceof WindowManager.LayoutParams) return (WindowManager.LayoutParams) p;
        WindowManager.LayoutParams w = new WindowManager.LayoutParams();
        if (p != null) { w.width = p.width; w.height = p.height; }
        return w;
    }

    /**
     * Measure and lay a layer out inside a `screenW x screenH` window, honoring
     * its LayoutParams width/height (MATCH_PARENT / WRAP_CONTENT / fixed),
     * gravity, and x/y offsets. Writes the result into `layer.bounds` and calls
     * view.layout so a subsequent draw paints at the right place.
     */
    public static void measureAndLayout(Layer layer, int screenW, int screenH) {
        final View v = layer.view;
        final WindowManager.LayoutParams lp = layer.params;

        int wSpec = specFor(lp.width, screenW);
        int hSpec = specFor(lp.height, screenH);
        try { v.measure(wSpec, hSpec); } catch (Throwable ignored) { }

        int w = v.getMeasuredWidth();
        int h = v.getMeasuredHeight();
        if (w <= 0) w = (lp.width >= 0) ? lp.width : screenW;
        if (h <= 0) h = (lp.height >= 0) ? lp.height : screenH;
        if (w > screenW) w = screenW;
        if (h > screenH) h = screenH;

        int gravity = lp.gravity == 0 ? (Gravity.CENTER) : lp.gravity;
        int left, top;

        final int hg = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (hg == Gravity.RIGHT || hg == Gravity.END) {
            left = screenW - w - lp.x;
        } else if (hg == Gravity.CENTER_HORIZONTAL || hg == 0) {
            left = (screenW - w) / 2 + lp.x;
        } else { // LEFT / START
            left = lp.x;
        }

        final int vg = gravity & Gravity.VERTICAL_GRAVITY_MASK;
        if (vg == Gravity.BOTTOM) {
            top = screenH - h - lp.y;
        } else if (vg == Gravity.CENTER_VERTICAL || vg == 0) {
            top = (screenH - h) / 2 + lp.y;
        } else { // TOP
            top = lp.y;
        }

        // Keep the layer on screen.
        if (left < 0) left = 0;
        if (top < 0) top = 0;
        if (left + w > screenW) left = Math.max(0, screenW - w);
        if (top + h > screenH) top = Math.max(0, screenH - h);

        layer.bounds.set(left, top, left + w, top + h);
        try { v.layout(left, top, left + w, top + h); } catch (Throwable ignored) { }
    }

    /**
     * The FLAG_DIM_BEHIND scrim colour for one layer, or 0 when it does not dim.
     *
     * AOSP: WindowManagerService dims everything BEHIND a window that carries
     * FLAG_DIM_BEHIND, by `dimAmount` (0..1) of black -- see
     * WindowManager.LayoutParams.FLAG_DIM_BEHIND ("everything behind this window
     * will be dimmed ... use dimAmount to control the amount of dim"). Every
     * dialog theme enables it (backgroundDimEnabled defaults true) at
     * ?backgroundDimAmount = 0.6.
     *
     * Returned as a packed ARGB so the two composite paths (ViewCanvasDriver's
     * Canvas pass and presentForGl's GL pass) share ONE definition of the scrim
     * instead of each growing its own.
     *
     * This answers "what colour WOULD this layer dim with", per layer. It does
     * NOT decide whether to paint it -- callers must ask topmostDimIndex, because
     * only ONE layer's dim is ever painted. Do not loop this over every layer.
     */
    public static int dimColorFor(Layer layer) {
        if (layer == null || layer.params == null) return 0;
        if ((layer.params.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0) return 0;
        float a = layer.params.dimAmount;
        if (a <= 0f) return 0;
        if (a > 1f) a = 1f;
        return Math.round(a * 255f) << 24;   // black at `dimAmount` alpha
    }

    /**
     * Index of the layer whose dim the composite should paint, or -1 for none.
     *
     * THERE IS EXACTLY ONE DIM, NOT ONE PER DIALOG. AOSP's Dimmer holds a single
     * `DimState mDimState` per host container, and both of its entry points say
     * verbatim: "If multiple containers call this method, only the changes
     * relative to the TOPMOST will be applied"
     * (services/core/java/com/android/server/wm/Dimmer.java:212, :228, fetched
     * 2026-08-03). So N stacked dialogs produce ONE scrim at the topmost one's
     * dimAmount -- they do NOT compound.
     *
     * MEASURED cost of getting this wrong: newpipe shows TWO dialogs from its
     * MainActivity.onCreate; dimming per-layer gave 1-(0.4^2) = 0.84 effective
     * dim, a near-black screen where the device draws a clearly readable 0.6.
     * Scored 13.13% against the emulator, WORSE than the 23.59% we had with no
     * scrim at all.
     *
     * Invisible layers are skipped, so a hidden dialog cannot dim the screen.
     */
    public static int topmostDimIndex(List<Layer> layers) {
        if (layers == null) return -1;
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer l = layers.get(i);
            if (l == null || l.view == null || l.view.getVisibility() != View.VISIBLE) continue;
            if (dimColorFor(l) != 0) return i;
        }
        return -1;
    }

    /**
     * Draw one window: its root's elevation shadow, then its view tree. The
     * canvas must already be translated to the layer's top-left (both
     * compositors do that before drawing a layer).
     *
     * AOSP draws a window's own shadow in the WINDOW's renderer, not in any
     * parent: PhoneWindow.generateLayout does `mDecor.setElevation(mElevation)`
     * (android14 PhoneWindow.java:2736) and PhoneWindow.setElevation grows the
     * surface insets (`attrs.setSurfaceInsets(mDecor, ...)`, :1509) so the
     * decor's RenderNode has room to project its outline's shadow inside the
     * window's own surface. View.draw never paints the view's own shadow -- a
     * parent's drawChild does -- and a window root has no parent, so a bare
     * `view.draw(canvas)` could never show one. This is that missing renderer
     * step: the root's Z, shaped by its OUTLINE (View.rebuildOutline: setEmpty,
     * setAlpha(1), then the outline provider -- for a dialog decor the
     * background's outline, i.e. the visible panel inside the 16dp inset, not
     * the decor rect), scaled by the outline alpha and the view alpha.
     *
     * The shadow itself is the same soft multi-pass fill ViewGroup's M12 casts
     * for an elevated child (ViewGroup.macapkDrawElevationShadow): our software
     * Canvas has no ambient/spot shadow primitive, and a window and a child
     * shadow should look alike. Its constants are duplicated below rather than
     * shared because that method is private to another lane's file; the patch
     * that shares them is debug/w5alertdialog_verify/viewgroup-shared-shadow.patch.
     */
    public static void drawWindow(android.graphics.Canvas canvas, Layer layer) {
        if (layer == null || layer.view == null) return;
        if (WINDOW_SHADOW) {
            try { drawWindowShadow(canvas, layer.view); } catch (Throwable ignored) { }
        }
        layer.view.draw(canvas);
    }

    /** The shadow half of drawWindow; a no-op for a root with no Z, an empty
     *  outline or a zero outline alpha (AOSP casts nothing for those either). */
    static void drawWindowShadow(android.graphics.Canvas canvas, View root) {
        final float z = root.getZ();
        if (!(z > 0f)) return;
        final int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        final android.view.ViewOutlineProvider provider = root.getOutlineProvider();
        if (provider == null) return;
        // View.drawBackground sets the background's bounds to the view's size
        // just before it draws; the outline is read from those bounds, so give
        // them the same value first (the first raster of a window happens before
        // its first draw).
        final android.graphics.drawable.Drawable bg = root.getBackground();
        if (bg != null) {
            final Rect b = bg.getBounds();
            if (b.left != 0 || b.top != 0 || b.right != w || b.bottom != h) bg.setBounds(0, 0, w, h);
        }
        final android.graphics.Outline outline = new android.graphics.Outline();
        outline.setEmpty();
        outline.setAlpha(1.0f);
        provider.getOutline(root, outline);
        final Rect r = new Rect();
        final float radius;
        if (outline.mMode == android.graphics.Outline.MODE_ROUND_RECT && !outline.mRect.isEmpty()) {
            r.set(outline.mRect);
            radius = Math.max(0f, outline.mRadius);
        } else if (outline.mMode == android.graphics.Outline.MODE_PATH && outline.mPath != null
                && !Float.isNaN(outline.macapkPathShadowRadius)) {
            // A rect with mixed corner radii (GradientDrawable's radius array, a
            // usual popup-menu background): cast the round rect of its bounds and
            // its largest corner radius, which lies inside the shape, so the
            // shadow never shows outside a rounded corner.
            final android.graphics.RectF pb = new android.graphics.RectF();
            outline.mPath.computeBounds(pb, true);
            r.set(Math.round(pb.left), Math.round(pb.top), Math.round(pb.right), Math.round(pb.bottom));
            if (r.isEmpty()) return;
            radius = Math.max(0f, outline.macapkPathShadowRadius);
        } else {
            // EMPTY casts nothing (AOSP too). Any other convex PATH is skipped
            // rather than approximated: a square shadow under a rounded panel
            // would show dark corners outside it, and the M12 rule is to
            // under-shadow rather than paint a box. Every dialog background in
            // the corpus census is a round rect (GradientDrawable with a uniform
            // radius, MaterialShapeDrawable's isRoundRect()).
            return;
        }
        final float alpha = Math.max(0f, Math.min(1f, outline.mAlpha))
                * Math.max(0f, Math.min(1f, root.getAlpha()));
        if (alpha <= 0f) return;
        float density = android.util.DisplayMetrics.macapkDeviceDensity();
        try {
            if (root.getResources() != null && root.getResources().getDisplayMetrics() != null
                    && root.getResources().getDisplayMetrics().density > 0f) {
                density = root.getResources().getDisplayMetrics().density;
            }
        } catch (Throwable ignored) { }
        paintSoftShadow(canvas, r, radius, z, alpha, density);
    }

    /** ViewGroup.macapkDrawElevationShadow's fill, for an outline rect `r` in
     *  the canvas's space: spread ~ Z (capped at 24dp), offset down by half the
     *  spread (the light is above), four expanding round-rect passes whose
     *  alphas accumulate into a soft core, outermost first. */
    public static void paintSoftShadow(android.graphics.Canvas canvas, Rect r, float radius,
                                float z, float alpha, float density) {
        alpha = Math.max(0f, Math.min(1f, alpha));
        int spread = Math.round(z);
        final int cap = Math.round(24f * density);
        if (spread > cap) spread = cap;
        if (spread < 1) spread = 1;
        final int yOff = Math.max(1, spread / 2);
        final int[] base = { 0x22, 0x1a, 0x12, 0x0a };  // inner -> outer
        final android.graphics.Paint sh = new android.graphics.Paint();
        sh.setAntiAlias(true);
        sh.setStyle(android.graphics.Paint.Style.FILL);
        for (int i = base.length - 1; i >= 0; --i) {
            final int g = spread * (i + 1) / base.length;
            final int pa = Math.round(base[i] * alpha);
            if (pa <= 0) continue;
            sh.setColor((pa & 0xff) << 24);
            final float rad = radius + g;
            canvas.drawRoundRect(new android.graphics.RectF(
                    r.left - g, r.top - g + yOff, r.right + g, r.bottom + g + yOff), rad, rad, sh);
        }
    }

    private static int specFor(int dim, int available) {
        if (dim == ViewGroup.LayoutParams.MATCH_PARENT) {
            return View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY);
        }
        if (dim == ViewGroup.LayoutParams.WRAP_CONTENT) {
            return View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST);
        }
        return View.MeasureSpec.makeMeasureSpec(dim, View.MeasureSpec.EXACTLY);
    }

    /**
     * GL-surface counterpart of ViewCanvasDriver's per-frame overlay draw
     * pass (added 2026-07-23). ViewCanvasDriver.macapkDrawFrame -- the ONLY
     * previous caller of measureAndLayout -- never runs for a GL-rendered
     * app: GLSurfaceView.macapkDrawFrame forwards straight to the engine's
     * own onDrawFrame (org.cocos2dx.lib.Cocos2dxRenderer etc.) and returns,
     * so a WindowManager overlay (Dialog/AlertDialog/PopupWindow) shown over
     * a cocos2d-x/AndEngine/libGDX game was NEVER measured or laid out --
     * layer.bounds stayed at Rect()'s zero-initialized (0,0,0,0) forever.
     * TouchDispatcher still checked WindowOverlayStack FIRST on every touch
     * (before the Activity / renderer-direct path), so with bounds always
     * (0,0,0,0) every touch anywhere on screen read as "outside" the overlay
     * and -- because AOSP's modal-by-default semantics apply until
     * FLAG_NOT_TOUCH_MODAL is set -- got delivered as ACTION_OUTSIDE and
     * unconditionally consumed. Concretely: AppLovin's Terms/Privacy consent
     * AlertDialog (com.applovin.impl.z0, builder.setCancelable(false)) came
     * up over Geometry Dash Meltdown's cocos2d-x scene, and every tap on the
     * game's own visible "NOTICE ... OK" button was silently swallowed by
     * this invisible, never-measured, non-cancelable dialog forever after.
     *
     * Call once per GL frame (GLSurfaceView.macapkDrawFrame, after the
     * engine's own onDrawFrame) with the CURRENT surface pixel size. Cheap
     * when the stack is empty (one boolean check). When non-empty: keeps
     * every visible layer's bounds fresh every call (measure+layout is
     * simple int math, matching ViewCanvasDriver's existing "re-layout every
     * frame" cost model) so touch hit-testing is always correct, but only
     * re-rasterizes + pushes a bitmap to native when `sDirty` (add/remove/
     * update/a delivered touch) -- a full-surface ARGB bitmap alloc+copy is
     * NOT something to pay 60x/sec for a mostly-static dialog. See
     * docs/gl-overlay-dialog-compositing_2026-07-23.md.
     */
    /** MACAPK_OVERLAY_OWNER_THREAD (default ON, 2026-10-05; =0 = measure, lay out
     *  and draw on the calling OS-main render thread, as before). A window's
     *  views belong to the thread that created them (AOSP ViewRootImpl.checkThread):
     *  measure, layout and draw run on the app's main thread, never on the
     *  compositor. The GL path called this from SurfaceView.macapkDrawFrame on
     *  the OS-main thread every frame while the app's main thread was filling the
     *  same dialog, so two threads ran one ConstraintLayout solver at once:
     *  Asphalt Legends' Didomi consent dialog threw ArrayIndexOutOfBounds /
     *  NullPointerException inside androidx.constraintlayout.core on both threads
     *  and the game stayed on its title screen after it. Now the render thread
     *  posts ONE coalesced owner-only message and the owner does the whole pass;
     *  the native present it ends in already dispatches to the AppKit thread. */
    static final boolean OVERLAY_OWNER_THREAD =
        !"0".equals(System.getenv("MACAPK_OVERLAY_OWNER_THREAD"));
    private static final java.util.concurrent.atomic.AtomicBoolean sOwnerPassPending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile int sPendingW, sPendingH;
    private static android.os.Handler sOwnerHandler;

    public static void presentForGl(int surfaceW, int surfaceH) {
        if (OVERLAY_OWNER_THREAD && !android.os.Looper.macapkIsActivityThread()
                && android.os.Looper.macapkRealMainLoopRunning()) {
            // Nothing showing and nothing left on screen: stay off the queue.
            if (!sGlOverlayShowing && isEmpty()) return;
            sPendingW = surfaceW;
            sPendingH = surfaceH;
            if (!sOwnerPassPending.compareAndSet(false, true)) return;
            android.os.Handler h = sOwnerHandler;
            if (h == null) {
                h = android.os.Handler.macapkCreateOwnerOnly(android.os.Looper.getMainLooper());
                sOwnerHandler = h;
            }
            h.post(() -> {
                sOwnerPassPending.set(false);
                try { presentForGlHere(sPendingW, sPendingH); }
                catch (Throwable t) { NativeLog.w("Mac.apk.Overlay", "presentForGl threw: " + t); }
            });
            return;
        }
        presentForGlHere(surfaceW, surfaceH);
    }

    private static void presentForGlHere(int surfaceW, int surfaceH) {
        List<Layer> layers = snapshot();
        if (layers.isEmpty()) {
            if (sGlOverlayShowing) {
                try { NativeCocoa.clearOverlayBitmap(); } catch (Throwable ignored) { }
                sGlOverlayShowing = false;
            }
            // Nothing to composite: give the reused surface-sized bitmap back.
            sOverlayBmp = null;
            return;
        }
        if (surfaceW <= 0 || surfaceH <= 0) return;
        int statusBarH = 0;
        try { statusBarH = StatusBarDriver.heightPx(null); } catch (Throwable ignored) { }
        // v1.0.878: match the Canvas path -- a floating window's frame excludes
        // BOTH system bars (see ViewCanvasDriver's citation). These two must
        // agree or the GL and Canvas composites place dialogs differently.
        int contentH = Math.max(1, surfaceH - statusBarH
                                   - StatusBarDriver.navBarInsetBottomPx(null));
        // v1.0.997: and the WIDTH excludes a side navigation bar. Must stay
        // identical to ViewCanvasDriver's floating-window box (its `ow`/`oh`
        // pair) -- the two comments already say so, and this is the axis the
        // split added.
        int contentW = Math.max(1, surfaceW - StatusBarDriver.navBarInsetLeftPx(null)
                                            - StatusBarDriver.navBarInsetRightPx(null));

        // Keep bounds fresh every call (cheap; touch hit-testing depends on it),
        // regardless of the render-side dirty gate below.
        for (Layer layer : layers) {
            if (layer.view == null || layer.view.getVisibility() != View.VISIBLE) continue;
            try { measureAndLayout(layer, contentW, contentH); } catch (Throwable ignored) { }
        }

        // Env-gated (MACAPK_OVERLAY_DIAG=1) fire-once dump of the exact absolute
        // rects (layer decor + every Button in its tree) so a click target can
        // be computed precisely instead of estimated from a screenshot.
        if (DIAG && !sLoggedOnce) {
            sLoggedOnce = true;
            try {
                for (Layer layer : layers) {
                    if (layer.view == null) continue;
                    NativeLog.i("Mac.apk.Overlay", "DIAG layer bounds(content-space)=" + layer.bounds
                        + " statusBarH=" + statusBarH + " surface=" + surfaceW + "x" + surfaceH);
                    // layer.view.getLeft()/getTop() are ALREADY absolute (measureAndLayout
                    // calls v.layout() with absolute content-space coords, and this view
                    // has no real ViewGroup parent) -- offX=0 here, only statusBarH shifts
                    // content-space up into full-surface space, matching the draw pass above.
                    dumpButtonsAbs(layer.view, 0, statusBarH);
                }
            } catch (Throwable ignored) { }
        }

        if (RERASTER_ON_RELAYOUT && !sDirty) {
            for (Layer layer : layers) {
                final boolean drawn = layer.view != null
                        && layer.view.getVisibility() == View.VISIBLE;
                if (drawn != layer.lastRasterDrawn
                        || (drawn && !layer.bounds.equals(layer.lastRasterBounds))) {
                    sDirty = true;
                    break;
                }
            }
        }
        if (!sDirty) return;
        try {
            android.graphics.Bitmap bmp = overlayBitmap(surfaceW, surfaceH);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            android.graphics.Paint dimPaint = null;
            final int dimAt = topmostDimIndex(layers);
            for (int li = 0; li < layers.size(); li++) {
                Layer layer = layers.get(li);
                if (layer.view == null || layer.view.getVisibility() != View.VISIBLE) continue;
                canvas.save();
                // layer.bounds is in CONTENT space (status bar excluded), matching
                // TouchDispatcher's contentY -- shift back into full-surface space.
                canvas.translate(0, statusBarH);
                // FLAG_DIM_BEHIND, same contract as ViewCanvasDriver's Canvas
                // pass (see dimColorFor). NOTE this bitmap is composited OVER the
                // GL frame by the native side, so the scrim genuinely darkens the
                // game underneath even though we never see those pixels here --
                // the alpha we lay down is what does it. Must be a Paint-ed rect,
                // not drawColor: our drawColor composites as Src and would stamp
                // opaque-ish black into the overlay bitmap instead of a scrim.
                // Painted for the TOPMOST dimming layer only -- see
                // topmostDimIndex for why dims do not compound.
                int dim = (li == dimAt) ? dimColorFor(layer) : 0;
                if (dim != 0) {
                    if (dimPaint == null) {
                        dimPaint = new android.graphics.Paint();
                        dimPaint.setStyle(android.graphics.Paint.Style.FILL);
                    }
                    dimPaint.setColor(dim);
                    canvas.drawRect(0, 0, surfaceW, contentH, dimPaint);
                }
                canvas.translate(layer.bounds.left, layer.bounds.top);
                try { drawWindow(canvas, layer); } catch (Throwable ignored) { }
                canvas.restore();
            }
            // v1.0.1648: present straight from the canvas's live pixels when
            // available (Bitmap.macapkLiveArgbOrNull: read-only, on this drawing
            // thread, before any further draw), and BEFORE recycle -- the native
            // present copies synchronously. The copying fallback is unchanged.
            int[] pixels = bmp.macapkLiveArgbOrNull();
            if (pixels == null) {
                pixels = new int[surfaceW * surfaceH];
                bmp.getPixels(pixels, 0, surfaceW, 0, 0, surfaceW, surfaceH);
            }
            final PresentSink sink = sPresentSink;
            if (sink != null) sink.present(pixels, surfaceW, surfaceH);
            else NativeCocoa.presentOverlayBitmap(pixels, surfaceW, surfaceH);
            if (!OVERLAY_REUSE) bmp.recycle();
            sGlOverlayShowing = true;
            sDirty = false;
            // What this raster drew, for RERASTER_ON_RELAYOUT's next comparison.
            for (Layer layer : layers) {
                layer.lastRasterDrawn = layer.view != null
                        && layer.view.getVisibility() == View.VISIBLE;
                layer.lastRasterBounds.set(layer.bounds);
            }
        } catch (Throwable t) {
            try { NativeLog.w("Mac.apk.Overlay", "presentForGl threw: " + t); } catch (Throwable ignored) { }
        }
    }

    // Gated diagnostic helper (see DIAG above): walk a view tree logging every
    // Button's ABSOLUTE (content-space, i.e. what deliverTouch's contentX/contentY
    // compares against) rect. offX/offY is the tree root's own absolute top-left.
    private static void dumpButtonsAbs(View v, int offX, int offY) {
        if (v == null) return;
        int absL = offX + v.getLeft(), absT = offY + v.getTop();
        if (v instanceof android.widget.Button) {
            NativeLog.i("Mac.apk.Overlay", "DIAG Button text=\"" + KeyLogPolicy.viewText((android.widget.Button) v, ((android.widget.Button) v).getText())
                + "\" abs=(" + absL + "," + absT + ")-(" + (absL + v.getWidth()) + "," + (absT + v.getHeight())
                + ") vis=" + v.getVisibility());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpButtonsAbs(g.getChildAt(i), absL, absT);
            }
        }
    }

    /** MACAPK_OVERLAY_NOTFOCUSABLE_NONMODAL, default ON (2026-09-29): a
     *  FLAG_NOT_FOCUSABLE overlay is not touch-modal (AOSP LayoutParams.isModal),
     *  so an outside touch falls through to the layers / activity below it.
     *  The same test also delivers ACTION_OUTSIDE to a FLAG_NOT_TOUCH_MODAL layer
     *  that asked for it (FLAG_WATCH_OUTSIDE_TOUCH), on the DOWN only. */
    static final boolean NOT_FOCUSABLE_IS_NOT_MODAL =
        !"0".equals(System.getenv("MACAPK_OVERLAY_NOTFOCUSABLE_NONMODAL"));

    /** MACAPK_OVERLAY_GESTURE_PIN, default ON (2026-09-29): a touch gesture belongs
     *  to the window that took its DOWN for its whole life, as on a device.
     *  Native InputDispatcher records the touched window(s) at ACTION_DOWN and
     *  sends every later MOVE/UP/CANCEL of that pointer to them, wherever the
     *  pointer is and whatever has been added or removed since; a window that is
     *  REMOVED mid-gesture gets a CANCEL and the rest of the gesture is not
     *  redirected to whatever is now underneath (TouchState /
     *  InputDispatcher::dispatchMotionLocked, frameworks/native/services/
     *  inputflinger/dispatcher). This stack instead re-hit-tested every event
     *  against the CURRENT layers, so (a) a popup / balloon / dropdown that
     *  dismissed itself on the DOWN handed the matching UP to the layer or the
     *  Activity behind it -- a tap on Wikipedia's tooltip balloon closed it AND
     *  clicked the article header image behind both windows, opening the gallery
     *  (owner hand test 2026-09-29: "Gets stuck on pop ups"), and a tap outside
     *  a Spinner dropdown dismissed it and pressed the widget under it; (b) a
     *  dialog that appeared mid-gesture received an orphan UP for a DOWN it never
     *  saw; (c) a drag that left its window's bounds turned into ACTION_OUTSIDE
     *  events and its UP never arrived. =0 restores the per-event hit test. */
    static final boolean GESTURE_PIN = !"0".equals(System.getenv("MACAPK_OVERLAY_GESTURE_PIN"));
    /** MACAPK_OVERLAY_RAW_SCREEN (default ON): a floating window's MotionEvent
     *  carries SCREEN coordinates as its raw values and the window frame as its
     *  offset, as AOSP's InputDispatcher delivers it (see dispatchTo). =0: raw
     *  equals the window-local point, the pre-2026-09-30 behaviour. */
    static final boolean RAW_SCREEN = !"0".equals(System.getenv("MACAPK_OVERLAY_RAW_SCREEN"));
    private static final int PIN_NONE = 0, PIN_ACTIVITY = 1, PIN_OVERLAY = 2;
    private static int sPinKind = PIN_NONE;
    private static Layer sPinLayer;
    /** The pin came from a touch-modal window's outside-DOWN (consumed as
     *  ACTION_OUTSIDE), not from a DOWN inside the window: later events of that
     *  gesture keep the old per-event handling while the window lives. */
    private static boolean sPinOutside;

    private static void clearPin() { sPinKind = PIN_NONE; sPinLayer = null; sPinOutside = false; }

    /** TouchDispatcher, on every DOWN, BEFORE it asks whether the stack is empty:
     *  until deliverTouch pins an overlay, the new gesture is the Activity's
     *  (a dialog that appears mid-gesture must not steal it). */
    public static void macapkGestureDown() {
        if (!GESTURE_PIN) return;
        sPinKind = PIN_ACTIVITY; sPinLayer = null; sPinOutside = false;
    }

    /** True while the current gesture is pinned to an overlay window -- even one
     *  that has since been removed, so the stack may be EMPTY and the rest of the
     *  gesture still has to be swallowed rather than handed to the Activity. */
    public static boolean gesturePinnedToOverlay() {
        return GESTURE_PIN && sPinKind == PIN_OVERLAY;
    }

    /**
     * Deliver a touch to the overlay stack if one should receive it. Returns
     * true if an overlay consumed the touch (so the caller must NOT also
     * dispatch to the Activity).
     *
     * `contentX/contentY` are in CONTENT space (screen minus the status bar),
     * matching layer.bounds. AOSP modal semantics: the topmost touchable
     * overlay captures every touch -- inside its bounds it dispatches normally;
     * a tap OUTSIDE its bounds is delivered as ACTION_OUTSIDE (which is how a
     * dialog/popup dismisses on outside-tap). FLAG_NOT_TOUCH_MODAL lets an
     * outside touch fall through; FLAG_NOT_TOUCHABLE skips the layer.
     *
     * ⚠ INVESTIGATED 2026-08-23 (framework_lint audit, item V-mods-2): this
     * ACTION_OUTSIDE delivery is deliberately UNCONDITIONAL, not gated on
     * WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, even though real
     * InputDispatcher DOES gate synthetic ACTION_OUTSIDE on that flag
     * (frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp,
     * findOutsideTargetsLocked: "Any WATCH_OUTSIDE_TOUCH window ... will not
     * get ACTION_OUTSIDE" without it). That is NOT the mechanism a stock
     * Dialog relies on, though. Dialog's default window sets neither
     * FLAG_NOT_TOUCH_MODAL nor FLAG_WATCH_OUTSIDE_TOUCH (core/java/android/
     * app/Dialog.java, PhoneWindow.java -- grepped, zero hits), so on a real
     * device the dialog's fullscreen touch-modal window receives the outside
     * tap as an ordinary ACTION_UP whose coordinates sit outside the decor
     * view, and Window.shouldCloseOnTouch (core/java/android/view/Window.java)
     * dismisses on EITHER of two independent conditions:
     *     (ACTION_UP && isOutOfBounds(decorView)) || action == ACTION_OUTSIDE
     * We do not model a fullscreen touch-modal window frame separate from the
     * dialog's own content bounds, so we have no ACTION_UP-outside-decor path
     * to trigger the first branch -- our synthetic ACTION_OUTSIDE is standing
     * in for it via the second branch, which shouldCloseOnTouch treats as
     * equally sufficient. Gating this on FLAG_WATCH_OUTSIDE_TOUCH would match
     * the native dispatcher's flag check in isolation but would BREAK Dialog
     * dismissal outright, because stock Dialogs never set that flag. Left
     * unconditional on purpose; do not "fix" this without also modelling the
     * geometric ACTION_UP-outside-decor path.
     */
    public static boolean deliverTouch(int action, float contentX, float contentY) {
        final List<Layer> layers = snapshot();
        final boolean end = action == 1 /* UP */ || action == 3 /* CANCEL */;
        if (GESTURE_PIN) {
            if (action == 0 /* DOWN */) {
                clearPin();
            } else if (sPinKind == PIN_ACTIVITY) {
                // The Activity's gesture: an overlay that showed up after its DOWN
                // does not receive the rest of it.
                if (end) clearPin();
                return false;
            } else if (sPinKind == PIN_OVERLAY) {
                final Layer pinned = sPinLayer;
                if (pinned == null || !layers.contains(pinned)) {
                    // Removed mid-gesture: AOSP cancels the window and the rest of the
                    // gesture goes nowhere.
                    if (end) clearPin();
                    return true;
                }
                if (!sPinOutside && pinned.view != null) {
                    dispatchTo(pinned.view, pinned, action, contentX, contentY, 0);
                    sDirty = true;
                    if (end) clearPin();
                    return true;
                }
            }
        }
        final boolean consumed = hitTestDeliver(layers, action, contentX, contentY);
        if (GESTURE_PIN) {
            if (action == 0 && !consumed) sPinKind = PIN_ACTIVITY;
            if (end) clearPin();
        }
        return consumed;
    }

    private static boolean hitTestDeliver(List<Layer> layers, int action, float contentX, float contentY) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            View v = layer.view;
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            int flags = layer.params != null ? layer.params.flags : 0;
            if ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) continue;

            boolean inside = layer.bounds.contains((int) contentX, (int) contentY);
            if (inside) {
                if (GESTURE_PIN && action == 0) { sPinKind = PIN_OVERLAY; sPinLayer = layer; sPinOutside = false; }
                dispatchTo(v, layer, action, contentX, contentY, 0 /* MotionEvent.ACTION_DOWN base */);
                sDirty = true;  // e.g. a Button's pressed-state drawable changed
                return true;
            }
            // Outside the bounds.
            //
            // AOSP LayoutParams.isModal() (android14 WindowManager.java:5627):
            //   return (flags & (FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE)) == 0;
            // and FLAG_NOT_FOCUSABLE's own javadoc (:2349-2353): "This flag
            // will also enable FLAG_NOT_TOUCH_MODAL whether or not that is
            // explicitly set." So a not-focusable window (a PopupWindow that
            // was not setFocusable(true): tooltips, onboarding callouts, most
            // anchored hints) lets an outside touch through to the window
            // behind. A non-modal window only HEARS about it when it asked to,
            // with FLAG_WATCH_OUTSIDE_TOUCH, and then only on the DOWN (native
            // InputDispatcher.findOutsideTargetsLocked delivers ACTION_OUTSIDE
            // to watch-outside windows when a new gesture starts, whatever their
            // modality). We treated NOT_FOCUSABLE as modal and swallowed every
            // outside touch, so an app whose tooltip does not dismiss itself on
            // ACTION_OUTSIDE was stuck behind it forever (Wikipedia's feed
            // tooltip, owner hand test 2026-09-29: "Gets stuck on pop ups").
            // MACAPK_OVERLAY_NOTFOCUSABLE_NONMODAL=0 restores the old treatment
            // (NOT_TOUCH_MODAL only, and no ACTION_OUTSIDE to it).
            final boolean nonModal = NOT_FOCUSABLE_IS_NOT_MODAL
                ? (flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) != 0
                : (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0;
            if (nonModal) {
                if (NOT_FOCUSABLE_IS_NOT_MODAL
                        && (flags & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0
                        && action == 0 /* ACTION_DOWN */) {
                    dispatchTo(v, layer, 4 /* ACTION_OUTSIDE */, contentX, contentY, 4);
                    sDirty = true;
                }
                // Non-modal: this layer does not capture outside touches; try lower layers.
                continue;
            }
            // Modal: deliver ACTION_OUTSIDE (action 4) so it can dismiss, and
            // consume so the touch does not leak to content behind it.
            if (sConsumeLogged++ < 8) {
                try {
                    NativeLog.i("Mac.apk.Overlay", "touch CONSUMED as modal-outside by "
                        + v.getClass().getName() + " bounds=" + layer.bounds
                        + " flags=0x" + Integer.toHexString(flags)
                        + " at (" + contentX + "," + contentY + ")");
                } catch (Throwable ignored) { }
            }
            if (GESTURE_PIN && action == 0) { sPinKind = PIN_OVERLAY; sPinLayer = layer; sPinOutside = true; }
            dispatchTo(v, layer, 4 /* ACTION_OUTSIDE */, contentX, contentY, 4);
            // AOSP Window.shouldCloseOnTouch: an ACTION_OUTSIDE (or an ACTION_UP
            // landing outside the decor) cancels a dialog that is cancelable and
            // canceled-on-touch-outside. Nothing in this framework handled
            // ACTION_OUTSIDE except PopupWindow, so taps outside a modal dialog
            // were swallowed forever and such a dialog could NEVER be dismissed
            // by tapping away from it -- on a device it can. That alone makes any
            // dialog feel undismissable.
            try {
                android.app.Dialog d = (layer.owner != null) ? layer.owner.get() : null;
                if (d != null && d.macapkCancelOnTouchOutside()) d.cancel();
            } catch (Throwable ignored) { }
            return true;
        }
        return false;
    }

    /** When the CURRENT overlay gesture went down, in uptimeMillis. 0 = no
     *  gesture in flight. Mirrors TouchDispatcher's own latch; the two are
     *  separate because an overlay swallows the stream the Activity never
     *  sees, so one shared latch would be clobbered by whichever ran last. */
    private static long sOverlayDownTime;

    private static void dispatchTo(View v, Layer layer, int action,
                                   float contentX, float contentY, int unused) {
        long t = android.os.SystemClock.uptimeMillis();
        // Window-local coordinates: relative to the layer's top-left.
        float lx = contentX - layer.bounds.left;
        float ly = contentY - layer.bounds.top;
        // Build through TouchDispatcher's shared factory, NOT MotionEvent's
        // short-form obtain. That factory used to force SOURCE_TOUCHSCREEN /
        // TOOL_TYPE_FINGER itself; as of v1.0.924 its defaults are AOSP-exact
        // (SOURCE_UNKNOWN / TOOL_TYPE_UNKNOWN / deviceId 0), so calling it here
        // would hand every dialog and popup an event that claims to come from
        // no device at all. Anything routing on (source & SOURCE_CLASS_POINTER)
        // drops such an event outright.
        //
        // downTime is LATCHED on the DOWN, per AOSP: it names when the gesture
        // began and stays constant for the stream, which is what long-press and
        // fling classification key on.
        if (action == android.view.MotionEvent.ACTION_DOWN) sOverlayDownTime = t;
        long down = sOverlayDownTime != 0L ? sOverlayDownTime : t;
        // RAW = SCREEN, OFFSET = -WINDOW FRAME. AOSP InputDispatcher hands a
        // window its touch as screen coordinates plus the window's offset
        // (android11 InputDispatcher.cpp:2028, TouchState::addPointers(pointerIds,
        // -windowInfo->frameLeft, -windowInfo->frameTop, ...)), and MotionEvent
        // keeps the two apart: getAxisValue(X) = raw * xScale + mXOffset while
        // getRawAxisValue returns raw untouched (Input.cpp:414-427). So getX()
        // is window-local and getRawX() is where the finger is on the SCREEN
        // (MotionEvent.getRawX javadoc: "before it had been adjusted for the
        // containing window and views"). We built the event FROM the local
        // point, so a floating window's getRawX() answered window-local
        // coordinates while getLocationOnScreen() of its own views answered
        // screen ones, and any code comparing the two saw every touch as far
        // outside. Measured on Wikipedia (skydoves Balloon, dismissWhenTouchMargin):
        // the touch interceptor tests wrapper.getLocationOnScreen().x (441) >
        // event.getRawX() (106) on ACTION_UP and swallows the UP, so the tooltip's
        // own "Got it" button saw DOWN and never UP: the balloon could never be
        // closed ("gets stuck on pop ups", owner hand test 2026-09-29).
        // getX()/getY() are identical at the window root (contentX + (-left) ==
        // contentX - left in IEEE arithmetic); a child view's getX can differ by
        // at most 1 ulp for a point outside the window (ACTION_OUTSIDE, a pinned
        // out-of-bounds MOVE/UP), because the new path rounds once
        // (contentX + (D - left)) and the old one twice ((contentX - left) + D).
        // MACAPK_OVERLAY_RAW_SCREEN=0 restores raw == local, exact.
        android.view.MotionEvent ev;
        if (RAW_SCREEN) {
            ev = TouchDispatcher.touchEvent(down, t, action, contentX, contentY, 0);
            ev.offsetLocation(-layer.bounds.left, -layer.bounds.top);
        } else {
            ev = TouchDispatcher.touchEvent(down, t, action, lx, ly, 0);
        }
        try { v.dispatchTouchEvent(ev); }
        catch (Throwable thr) { try { NativeLog.w("Mac.apk.Overlay", "overlay touch threw: " + thr); } catch (Throwable ig) {} }
        finally { ev.recycle(); }
    }
}
