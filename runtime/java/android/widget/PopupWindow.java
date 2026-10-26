// android.widget.PopupWindow -- floating, focusable window typically
// anchored over an existing view. HCR's LiveOps EULA dialog (and many
// other apps using in-app popups like "rate me" prompts) construct
// PopupWindow at <clinit>; without the class the load triggers
// NoClassDefFoundError which leaves the LiveOps init chain in an
// undefined half-initialized state.
//
// ⚠ HISTORICAL: this class used to be a pure stub ("Mac.apk has no real popup
// chrome, so all the show/dismiss methods are no-ops"). That has not been true
// since WindowOverlayStack landed at v1.0.691 -- Mac.apk owns one NSWindow and
// composites every floating window as an overlay layer over the Activity
// content, which is exactly what a popup needs. show/dismiss/update now go
// through WindowManager.addView/removeView/updateViewLayout like AOSP's do; see
// the block comment on showAtLocation below for what is and is not ported.
// AOSP reference:
// https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/widget/PopupWindow.java
package android.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.transition.Transition;
import android.view.View;
import android.view.ViewGroup;

public class PopupWindow {
    // Input mode constants per AOSP.
    public static final int INPUT_METHOD_FROM_FOCUSABLE = 0;
    public static final int INPUT_METHOD_NEEDED         = 1;
    public static final int INPUT_METHOD_NOT_NEEDED     = 2;

    private Context context;
    private View contentView;
    private int width  = ViewGroup.LayoutParams.WRAP_CONTENT;
    private int height = ViewGroup.LayoutParams.WRAP_CONTENT;
    private boolean focusable;
    private boolean touchable = true;
    private boolean outsideTouchable;
    private boolean clippingEnabled = true;
    // AOSP PopupWindow.mSplitTouchEnabled: tri-state (unset/false/true) so
    // isSplitTouchEnabled() can fall back to "true iff targetSdk >= HONEYCOMB"
    // when the app never called the setter. Only the setter is in this
    // lane's scope; the field keeps AOSP's exact tri-state shape so a future
    // isSplitTouchEnabled() getter can be dropped in without touching this.
    private int  mSplitTouchEnabled = -1;
    private int  inputMethodMode = INPUT_METHOD_FROM_FOCUSABLE;
    private int  softInputMode;
    private int  windowLayoutType;
    private int  animationStyle = -1;
    // AOSP PopupWindow.mEnterTransition / mExitTransition. These are NOT
    // app-only API: the Material/AppCompat themes declare popupEnterTransition
    // and popupExitTransition as default style attributes, and the 4-arg
    // (Context, AttributeSet, defStyleAttr, defStyleRes) constructor inflates
    // them and calls setEnterTransition()/setExitTransition() itself, so
    // ListPopupWindow / PopupMenu / Spinner dropdowns reach these methods on a
    // plain themed app that never mentions popup transitions.
    private Transition enterTransition;
    private Transition exitTransition;
    private Drawable backgroundDrawable;
    private boolean showing;
    private OnDismissListener onDismissListener;

    public PopupWindow()                                          {}
    // AOSP chains (Context) -> (Context, attrs) -> (.., popupWindowStyle) ->
    // (.., defStyleRes 0), and the last one reads the style: popupBackground,
    // overlapAnchor, popupElevation (PopupWindow.java:240-275). These bodies used
    // to only store the context, so every popup an app built from a style got
    // NO background -- WhatsApp's overflow menu drew its items straight over the
    // chat list.
    public PopupWindow(Context c)                                 { this(c, null); }
    public PopupWindow(Context c, android.util.AttributeSet a)    { this(c, a, 0x01010076 /* popupWindowStyle */); }
    public PopupWindow(Context c, android.util.AttributeSet a, int s) { this(c, a, s, 0); }
    public PopupWindow(Context c, android.util.AttributeSet a, int s, int r) {
        this.context = c;
        macapkApplyStyle(c, a, s, r);
    }

    /** Sorted, as a styleable is: popupBackground, overlapAnchor, popupElevation. */
    private static final int[] MACAPK_POPUP_ATTRS = { 0x01010176, 0x01010462, 0x0101048c };

    private void macapkApplyStyle(Context c, android.util.AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        if (c == null) return;
        android.content.res.TypedArray a = null;
        try {
            a = c.obtainStyledAttributes(attrs, MACAPK_POPUP_ATTRS, defStyleAttr, defStyleRes);
            if (a.hasValue(0)) backgroundDrawable = a.getDrawable(0);
            if (a.hasValue(1)) mOverlapAnchor = a.getBoolean(1, false);
            if (a.hasValue(2)) mElevation = a.getDimension(2, 0f);
        } catch (Throwable t) {
            // A style that cannot be resolved must never break construction.
        } finally {
            if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
        }
    }
    public PopupWindow(int w, int h)                              { this.width = w; this.height = h; }
    public PopupWindow(View contentView)                          { this.contentView = contentView; }
    public PopupWindow(View contentView, int w, int h)            { this.contentView = contentView; this.width = w; this.height = h; }
    public PopupWindow(View contentView, int w, int h, boolean focusable) { this(contentView, w, h); this.focusable = focusable; }

    public View   getContentView()                  { return contentView; }
    public void   setContentView(View v)            { this.contentView = v; }
    public int    getWidth()                        { return width; }
    public void   setWidth(int w)                   { this.width = w; }
    public int    getHeight()                       { return height; }
    public void   setHeight(int h)                  { this.height = h; }
    public boolean isFocusable()                    { return focusable; }
    public void   setFocusable(boolean f)           { this.focusable = f; }
    public boolean isTouchable()                    { return touchable; }
    public void   setTouchable(boolean t)           { this.touchable = t; }
    public boolean isOutsideTouchable()             { return outsideTouchable; }
    public void   setOutsideTouchable(boolean t)    { this.outsideTouchable = t; }
    public boolean isClippingEnabled()              { return clippingEnabled; }
    public void   setClippingEnabled(boolean c)     { this.clippingEnabled = c; }
    /** AOSP setSplitTouchEnabled: whether a pointer that goes down over
     *  another window continues delivering its own touches to THIS popup
     *  (split) or the first-down window claims every pointer for its
     *  lifetime (not split). Mac.apk has no overlapping-window touch router
     *  yet, so nothing downstream consults this flag, but it is stored
     *  faithfully (matching AOSP's exact tri-state encoding) rather than a
     *  bare no-op. */
    public void   setSplitTouchEnabled(boolean enabled) { mSplitTouchEnabled = enabled ? 1 : 0; }
    public int    getInputMethodMode()              { return inputMethodMode; }
    public void   setInputMethodMode(int m)         { this.inputMethodMode = m; }
    public int    getSoftInputMode()                { return softInputMode; }
    public void   setSoftInputMode(int m)           { this.softInputMode = m; }
    public int    getWindowLayoutType()             { return windowLayoutType; }
    public void   setWindowLayoutType(int t)        { this.windowLayoutType = t; }
    public int    getAnimationStyle()               { return animationStyle; }
    public void   setAnimationStyle(int s)          { this.animationStyle = s; }
    public Drawable getBackground()                 { return backgroundDrawable; }
    public void   setBackgroundDrawable(Drawable d) { this.backgroundDrawable = d; }
    public boolean isShowing()                      { return showing; }

    // Enter / exit Transition. AOSP's bodies for these four are exactly a field
    // write / field read (PopupWindow.java setEnterTransition/getEnterTransition/
    // setExitTransition/getExitTransition); the playback itself lives in the
    // private PopupDecorView, which requests the enter transition when the popup
    // window is attached and starts the exit transition on dismiss. Mac.apk has
    // no popup decor window to attach the content view to yet, so nothing is
    // played -- but the accessors must round-trip, because a caller that sets a
    // Transition and reads it back (AppCompat does exactly this when it clones a
    // popup's style) has to see its own write, and every caller has to be able
    // to resolve the method at all.
    public void setEnterTransition(Transition enterTransition) {
        this.enterTransition = enterTransition;
    }
    public Transition getEnterTransition()          { return enterTransition; }
    public void setExitTransition(Transition exitTransition) {
        this.exitTransition = exitTransition;
    }
    public Transition getExitTransition()           { return exitTransition; }

    // ---------------------------------------------------------------------
    // Show / dismiss.
    //
    // ⚠ These were no-ops (`showAsDropDown(...) { showing = true; }`, every
    // update() empty). Consequence: every Spinner dropdown, every overflow /
    // PopupMenu, every AutoCompleteTextView suggestion list and every app-built
    // popup in a non-AppCompat app built its view tree correctly and then drew
    // ZERO pixels. The v677 audit measured PopupWindow alone in 31 of 37 apps.
    //
    // What changed: AOSP's real mechanism is `mWindowManager.addView(decorView, p)`
    // (android16-release core/java/android/widget/PopupWindow.java:1570-1583,
    // invokePopup), and this tree HAS that mechanism -- WindowOverlayStack
    // (com/macapk/loader/WindowOverlayStack.java, v1.0.691) is the process-wide
    // floating-window stack that WindowManager.addView feeds, ViewCanvasDriver
    // composites over the Activity content, and TouchDispatcher hit-tests top
    // down. Its own header names PopupWindow as an intended client. Dialog.show
    // (android/app/Dialog.java:383-394) is the working precedent this follows.
    //
    // Simplifications, stated rather than hidden:
    //  - No PopupBackgroundView wrapper. AOSP nests content inside a second view
    //    that owns mBackground (:1494-1499); we set the background on the decor
    //    itself. Same painted result and same padding contribution, one level
    //    less nesting.
    //  - No enter/exit Transition playback. The elevation is real: the decor
    //    carries it (preparePopup) and WindowOverlayStack casts the shadow.
    //  - findDropDownPosition's tryFitVertical flip-above and the resize
    //    passes (:1817-1900) are NOT ported; only the horizontal shift back
    //    into the window is (macapkPositionAsDropDown). WindowOverlayStack
    //    .measureAndLayout already clamps a layer to the screen, so a dropdown
    //    that does not fit below its anchor SLIDES up instead of FLIPPING above
    //    it. isAboveAnchor() therefore reports the clamped outcome, not a
    //    decision we made. This is the one place a device and Mac.apk will place
    //    a popup differently, and it only shows near the bottom edge.
    private android.view.View mDecorView;
    private android.view.WindowManager mAddedTo;
    private int mGravity = android.view.Gravity.NO_GRAVITY;
    private boolean mIsDropdown;
    private boolean mAboveAnchor;
    private java.lang.ref.WeakReference<View> mAnchorRef;
    private int mAnchorXoff, mAnchorYoff, mAnchorGravity;
    // AOSP PopupWindow's own defaults: mNotTouchModal is false, i.e. a popup is
    // TOUCH MODAL unless setTouchModal(false) says otherwise, which is what makes
    // a tap anywhere outside a Spinner dropdown close it instead of falling
    // through to whatever is behind it.
    private boolean mNotTouchModal;
    private boolean mLayoutInScreen;
    private boolean mLayoutInsetDecor;
    private boolean mIgnoreCheekPress;
    private boolean mClipToScreen;

    /** AOSP's PopupDecorView (PopupWindow.java:2528-2586). The two behaviours
     *  that matter here are its onTouchEvent (:2570-2584): an ACTION_DOWN whose
     *  point is outside the decor's own bounds, or an ACTION_OUTSIDE from the
     *  window manager, DISMISSES the popup and consumes the event. Without this
     *  a modal popup would swallow every touch in the app forever, which would be
     *  strictly worse than the no-op it replaces.
     *
     *  WindowOverlayStack.deliverTouch (:466-499) is what feeds this: a touch
     *  inside the layer's bounds is dispatched normally, and a touch outside a
     *  layer that does NOT carry FLAG_NOT_TOUCH_MODAL arrives here as action 4
     *  (ACTION_OUTSIDE) and is consumed. */
    private final class PopupDecorView extends FrameLayout {
        PopupDecorView(Context c) { super(c); }

        /** AOSP android14 PopupWindow.PopupDecorView.dispatchKeyEvent: BACK or
         *  ESCAPE tracked on the down dismisses the popup on the up. v1.0.1642:
         *  absent, so BACK went past an open popup to the activity behind it. */
        @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == android.view.KeyEvent.KEYCODE_ESCAPE) {
                if (getKeyDispatcherState() == null) {
                    return super.dispatchKeyEvent(event);
                }
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    final android.view.KeyEvent.DispatcherState state = getKeyDispatcherState();
                    if (state != null) state.startTracking(event, this);
                    return true;
                } else if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    final android.view.KeyEvent.DispatcherState state = getKeyDispatcherState();
                    if (state != null && state.isTracking(event) && !event.isCanceled()) {
                        dismiss();
                        return true;
                    }
                }
                return super.dispatchKeyEvent(event);
            }
            return super.dispatchKeyEvent(event);
        }

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            if (mTouchInterceptor != null && mTouchInterceptor.onTouch(this, ev)) return true;
            return super.dispatchTouchEvent(ev);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();
            final int action = event.getAction();
            if (action == android.view.MotionEvent.ACTION_DOWN
                    && (x < 0 || x >= getWidth() || y < 0 || y >= getHeight())) {
                dismiss();
                return true;
            } else if (action == android.view.MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private Context macapkContext() {
        if (context != null) return context;
        if (contentView != null) return contentView.getContext();
        return null;
    }

    private android.view.WindowManager macapkWindowManager() {
        Context c = macapkContext();
        if (c == null) return null;
        Object wm = c.getSystemService(Context.WINDOW_SERVICE);
        return (wm instanceof android.view.WindowManager) ? (android.view.WindowManager) wm : null;
    }

    /** AOSP PopupWindow.computeGravity (PopupWindow.java:1600-1606). NO_GRAVITY
     *  means START|TOP, i.e. x/y are absolute offsets from the window's top-left,
     *  which is exactly how WindowOverlayStack.measureAndLayout reads lp.x/lp.y
     *  for a LEFT|TOP layer. Never leave this 0: measureAndLayout treats gravity
     *  0 as CENTER. */
    private int computeGravity() {
        return mGravity == android.view.Gravity.NO_GRAVITY
                ? (android.view.Gravity.START | android.view.Gravity.TOP)
                : mGravity;
    }

    /** AOSP PopupWindow.computeFlags (PopupWindow.java:1654-1699), verbatim minus
     *  FLAG_LAYOUT_ATTACHED_IN_DECOR (no such constant in this tree's
     *  WindowManager.LayoutParams). The flags that are load-bearing HERE are
     *  FLAG_NOT_TOUCHABLE and FLAG_NOT_TOUCH_MODAL, which WindowOverlayStack
     *  .deliverTouch reads directly, and FLAG_NOT_FOCUSABLE. */
    private int computeFlags(int curFlags) {
        curFlags &= ~(android.view.WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES
                | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH);
        if (mIgnoreCheekPress) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;
        }
        if (!focusable) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            if (inputMethodMode == INPUT_METHOD_NEEDED) {
                curFlags |= android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
        } else if (inputMethodMode == INPUT_METHOD_NOT_NEEDED) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }
        if (!touchable) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (outsideTouchable) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }
        if (!clippingEnabled || mClipToScreen) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }
        if (mSplitTouchEnabled == 1) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
        }
        if (mLayoutInScreen) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }
        if (mLayoutInsetDecor) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
        }
        if (mNotTouchModal) {
            curFlags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
        return curFlags;
    }

    /** AOSP PopupWindow.createPopupLayoutParams (PopupWindow.java:1614-1651). */
    private android.view.WindowManager.LayoutParams createPopupLayoutParams() {
        android.view.WindowManager.LayoutParams p =
                new android.view.WindowManager.LayoutParams();
        p.gravity = computeGravity();
        p.flags   = computeFlags(p.flags);
        p.type    = windowLayoutType != 0 ? windowLayoutType
                  : android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        p.softInputMode = softInputMode;
        p.width  = width;
        p.height = height;
        return p;
    }

    /** AOSP PopupWindow.preparePopup (PopupWindow.java:1476-1512): build the decor
     *  that actually joins the window stack, wrapping the app's content view. */
    private boolean preparePopup() {
        Context c = macapkContext();
        if (c == null || contentView == null) return false;
        // A content view re-shown after a dismiss may still be parented to the
        // previous decor. AOSP detaches it in dismissImmediate; do the same here
        // rather than letting addView throw.
        try {
            android.view.ViewParent vp = contentView.getParent();
            if (vp instanceof ViewGroup) ((ViewGroup) vp).removeView(contentView);
        } catch (Throwable ignored) { }
        PopupDecorView decor = new PopupDecorView(c);
        if (backgroundDrawable != null) decor.setBackgroundDrawable(backgroundDrawable);
        // AOSP preparePopup: mBackgroundView.setElevation(mElevation) (the style's
        // popupElevation or setElevation). WindowOverlayStack casts a window's
        // shadow from its root's Z, so a popup now gets the shadow that sets it
        // off from a same-coloured screen behind it.
        if (mElevation > 0f) decor.setElevation(mElevation);
        try {
            decor.addView(contentView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            return false;
        }
        mDecorView = decor;
        return true;
    }

    /** AOSP PopupWindow.invokePopup (PopupWindow.java:1570-1583). */
    private void invokePopup(android.view.WindowManager.LayoutParams p) {
        android.view.WindowManager wm = macapkWindowManager();
        if (wm == null || mDecorView == null) { showing = false; return; }
        wm.addView(mDecorView, p);
        mAddedTo = wm;
    }

    /** AOSP PopupWindow.findDropDownPosition (PopupWindow.java:1733-1816), reduced
     *  to the part this tree can honour: align to the anchor's bottom-left in
     *  WINDOW coordinates plus the caller's offsets, adjust for a RIGHT
     *  horizontal gravity (START/END resolved by the anchor's direction), and
     *  shift the popup back inside the window horizontally. Window
     *  coordinates rather than AOSP's
     *  screen-minus-appRoot arithmetic because WindowOverlayStack's layer bounds
     *  and TouchDispatcher's hit-testing are both in that same space.
     *
     *  Returns AOSP's `outParams.y < drawingLocation[1]` -- with no flip-above
     *  pass that is always false here; see the note above about the clamp. */
    private boolean macapkPositionAsDropDown(View anchor,
            android.view.WindowManager.LayoutParams p, int xoff, int yoff, int gravity) {
        if (anchor == null) return false;
        final int anchorH = anchor.getHeight();
        final int anchorW = anchor.getWidth();
        if (mOverlapAnchor) yoff -= anchorH;
        int[] loc = new int[2];
        try { anchor.getLocationInWindow(loc); } catch (Throwable ignored) { }
        p.x = loc[0] + xoff;
        p.y = loc[1] + anchorH + yoff;
        p.gravity = computeGravity();
        // AOSP resolves START/END against the ANCHOR's layout direction
        // (Gravity.getAbsoluteGravity(gravity, anchor.getLayoutDirection())), so
        // under RTL the default TOP|START aligns the popup's right edge with the
        // anchor's. The raw gravity was tested here, which never matched RIGHT.
        final int hgrav = android.view.Gravity.getAbsoluteGravity(gravity, anchor.getLayoutDirection())
                & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
        int w = p.width;
        if (w < 0) w = macapkMeasuredDecorWidth(anchor);
        if (hgrav == android.view.Gravity.RIGHT && w >= 0) {
            p.x -= w - anchorW;
        }
        // AOSP tryFitHorizontal / positionInDisplayHorizontal (PopupWindow.java
        // :1860-1900): a popup that would cross the display edge is shifted back
        // inside it. Not ported before, so an RTL overflow menu anchored at the
        // left edge hung off the screen and its longest item was cut.
        View root = anchor.getRootView();
        int rootW = root != null ? root.getWidth() : 0;
        if (rootW > 0 && w > 0) {
            if (p.x + w > rootW) p.x = rootW - w;
            if (p.x < 0) p.x = 0;
        }
        return p.y < loc[1];
    }

    /** The decor's width for a WRAP_CONTENT popup, measured against the window. */
    private int macapkMeasuredDecorWidth(View anchor) {
        if (mDecorView == null) return -1;
        try {
            View root = anchor.getRootView();
            int rw = root != null ? root.getWidth() : 0;
            int rh = root != null ? root.getHeight() : 0;
            if (rw <= 0) return -1;
            mDecorView.measure(View.MeasureSpec.makeMeasureSpec(rw, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(Math.max(rh, 1), View.MeasureSpec.AT_MOST));
            return mDecorView.getMeasuredWidth();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** AOSP PopupWindow.showAtLocation (PopupWindow.java:1327-1346). */
    public void showAtLocation(View parent, int gravity, int x, int y) {
        if (showing || contentView == null) return;
        if (context == null && parent != null) context = parent.getContext();
        mIsShowingDetach();
        showing = true;
        mIsDropdown = false;
        mGravity = gravity;
        if (!preparePopup()) { showing = false; return; }
        android.view.WindowManager.LayoutParams p = createPopupLayoutParams();
        p.x = x;
        p.y = y;
        invokePopup(p);
    }

    public void showAsDropDown(View anchor) { showAsDropDown(anchor, 0, 0); }
    public void showAsDropDown(View anchor, int xoff, int yoff) {
        showAsDropDown(anchor, xoff, yoff, android.view.Gravity.TOP | android.view.Gravity.START);
    }
    /** AOSP PopupWindow.showAsDropDown(View,int,int,int) (PopupWindow.java:1406-1428). */
    public void showAsDropDown(View anchor, int xoff, int yoff, int gravity) {
        if (showing || contentView == null || anchor == null) return;
        if (context == null) context = anchor.getContext();
        mIsShowingDetach();
        mAnchorRef = new java.lang.ref.WeakReference<>(anchor);
        mAnchorXoff = xoff; mAnchorYoff = yoff; mAnchorGravity = gravity;
        showing = true;
        mIsDropdown = true;
        if (!preparePopup()) { showing = false; return; }
        android.view.WindowManager.LayoutParams p = createPopupLayoutParams();
        mAboveAnchor = macapkPositionAsDropDown(anchor, p, xoff, yoff, gravity);
        invokePopup(p);
    }

    /** AOSP's detachFromAnchor, reduced: drop any stale anchor/decor references
     *  left behind by a previous show that was never dismissed cleanly. */
    private void mIsShowingDetach() {
        mAnchorRef = null;
        mAboveAnchor = false;
        if (mDecorView != null && mAddedTo != null) {
            try { mAddedTo.removeView(mDecorView); } catch (Throwable ignored) { }
        }
        mDecorView = null;
        mAddedTo = null;
    }

    /** AOSP PopupWindow.update() (PopupWindow.java:2400+): re-derive the layout
     *  params and push them at the window manager. For a dropdown that means
     *  re-running the anchor positioning, which is how a popup follows an anchor
     *  that scrolled. Was an empty method, so a popup never moved or resized. */
    public void update() {
        if (!showing || mDecorView == null || mAddedTo == null) return;
        android.view.WindowManager.LayoutParams p = createPopupLayoutParams();
        if (mIsDropdown) {
            View anchor = mAnchorRef != null ? mAnchorRef.get() : null;
            if (anchor != null) {
                mAboveAnchor = macapkPositionAsDropDown(anchor, p,
                        mAnchorXoff, mAnchorYoff, mAnchorGravity);
            }
        }
        try { mAddedTo.updateViewLayout(mDecorView, p); } catch (Throwable ignored) { }
    }
    public void update(int width, int height) {
        if (width >= 0) this.width = width;
        if (height >= 0) this.height = height;
        update();
    }
    public void update(int x, int y, int w, int h) { update(x, y, w, h, false); }
    public void update(int x, int y, int w, int h, boolean force) {
        if (w >= 0) this.width = w;
        if (h >= 0) this.height = h;
        if (!showing || mDecorView == null || mAddedTo == null) return;
        android.view.WindowManager.LayoutParams p = createPopupLayoutParams();
        p.x = x;
        p.y = y;
        mIsDropdown = false;
        try { mAddedTo.updateViewLayout(mDecorView, p); } catch (Throwable ignored) { }
    }
    public void update(View anchor, int width, int height) {
        update(anchor, false, 0, 0, width, height);
    }
    public void update(View anchor, int xoff, int yoff, int w, int h) {
        update(anchor, true, xoff, yoff, w, h);
    }
    private void update(View anchor, boolean updateLocation, int xoff, int yoff, int w, int h) {
        if (w >= 0) this.width = w;
        if (h >= 0) this.height = h;
        if (anchor != null) {
            mAnchorRef = new java.lang.ref.WeakReference<>(anchor);
            if (updateLocation) { mAnchorXoff = xoff; mAnchorYoff = yoff; }
            mIsDropdown = true;
        }
        update();
    }

    /** AOSP PopupWindow.dismiss -> dismissImmediate (PopupWindow.java:2026-2110):
     *  remove the decor from the window manager, detach the content view so the
     *  same PopupWindow can be shown again, then fire the dismiss listener. The
     *  removeView is what this method's no-op predecessor could not do and is
     *  what keeps a modal popup from swallowing input permanently. */
    public void dismiss()                                               {
        if (!showing) return;
        showing = false;
        android.view.View decor = mDecorView;
        android.view.WindowManager wm = mAddedTo;
        mDecorView = null;
        mAddedTo = null;
        mAnchorRef = null;
        if (decor != null && wm != null) {
            try { wm.removeView(decor); } catch (Throwable ignored) { }
        }
        try {
            if (contentView != null && contentView.getParent() instanceof ViewGroup) {
                ((ViewGroup) contentView.getParent()).removeView(contentView);
            }
        } catch (Throwable ignored) { }
        if (onDismissListener != null) onDismissListener.onDismiss();
    }
    public void setOnDismissListener(OnDismissListener l) { this.onDismissListener = l; }

    public interface OnDismissListener {
        void onDismiss();
    }

    private boolean mOverlapAnchor;
    private android.view.View.OnTouchListener mTouchInterceptor;
    /** AOSP setOverlapAnchor (API 21): whether the popup may overlap its anchor. */
    public void setOverlapAnchor(boolean overlapAnchor) { mOverlapAnchor = overlapAnchor; }
    public boolean getOverlapAnchor() { return mOverlapAnchor; }
    /** AOSP setTouchInterceptor (API 3): a listener that can intercept the
     *  popup's outside/inside touches before its content sees them. */
    public void setTouchInterceptor(android.view.View.OnTouchListener l) { mTouchInterceptor = l; }


    public int getMaxAvailableHeight(android.view.View anchor) { return getMaxAvailableHeight(anchor, 0); }
    public int getMaxAvailableHeight(android.view.View anchor, int yOffset) { return getMaxAvailableHeight(anchor, yOffset, false); }
    public int getMaxAvailableHeight(android.view.View anchor, int yOffset, boolean ignoreBottomDecorations) {
        return anchor != null && anchor.getRootView() != null ? anchor.getRootView().getHeight() : 0;
    }
    /** AOSP isAboveAnchor(): whether the popup ended up above its anchor.
     *  See macapkPositionAsDropDown -- with no flip-above pass this is the
     *  clamped outcome, which today is always false. */
    public boolean isAboveAnchor() { return mAboveAnchor; }
    // AOSP :1061 / :610 / :514 -- all three discarded their argument, which is
    // why the matching getters (isAttachedInDecor / getElevation /
    // getEpicenterBounds, added below) had nothing to read. Storing them is
    // additive: mAttachedInDecor is consumed by nothing in this port's window
    // path, mElevation becomes the decor's elevation in preparePopup, and
    // mEpicenterBounds only orients a transition we do not run.
    public void setAttachedInDecor(boolean enabled) { mAttachedInDecor = enabled; }
    public void setElevation(float elevation) { mElevation = elevation; }
    /** AOSP :514 defensively COPIES the Rect in and out, so a caller mutating
     *  its own Rect afterwards cannot change the popup's epicenter. */
    public void setEpicenterBounds(android.graphics.Rect bounds) {
        mEpicenterBounds = bounds != null ? new android.graphics.Rect(bounds) : null;
    }
    /** AOSP setIsClippedToScreen: `mClipToScreen = enabled`, and computeFlags
     *  turns it into FLAG_LAYOUT_NO_LIMITS. */
    public void setIsClippedToScreen(boolean enabled) { mClipToScreen = enabled; }
    /** AOSP setTouchModal: `mNotTouchModal = !touchModal`. Load-bearing now that
     *  the popup really joins the window stack -- WindowOverlayStack.deliverTouch
     *  reads FLAG_NOT_TOUCH_MODAL to decide whether an outside touch is consumed
     *  (and dismisses the popup) or falls through to the layer below. */
    public void setTouchModal(boolean touchModal) { mNotTouchModal = !touchModal; }
    /** AOSP setLayoutInScreenEnabled / setLayoutInsetDecor: both feed computeFlags. */
    public void setLayoutInScreenEnabled(boolean enabled) { mLayoutInScreen = enabled; }
    public boolean isLayoutInScreenEnabled() { return mLayoutInScreen; }
    public void setLayoutInsetDecor(boolean enabled) { mLayoutInsetDecor = enabled; }
    public boolean isLayoutInsetDecor() { return mLayoutInsetDecor; }
    /** AOSP isSplitTouchEnabled() (PopupWindow.java:967): the tri-state field
     *  falls back to `mContext.getApplicationInfo().targetSdkVersion >=
     *  Build.VERSION_CODES.HONEYCOMB`.
     *
     *  This comment used to end "which every app in this corpus is" and skip
     *  the read. MEASURED FALSE 2026-08-24: jetpackjoyride declares no
     *  targetSdkVersion at all, so PackageParser's rule (absent target defaults
     *  to minSdk) makes its effective target 8. Re-measure such a claim with
     *  `debug/compatgate.py --corpus ../Apks` rather than asserting it.
     *
     *  Below Honeycomb a popup does NOT split touch with the window behind it,
     *  so the second pointer stays with the popup instead of leaking through.
     *  An explicit setSplitTouchEnabled() still wins (the tri-state), and an
     *  unreadable ApplicationInfo takes the MODERN answer -- the previous
     *  behaviour, unchanged for every app at or above 11. */
    public boolean isSplitTouchEnabled() {
        if (mSplitTouchEnabled < 0) {
            try {
                android.content.Context c = macapkContext();
                android.content.pm.ApplicationInfo ai =
                        (c != null) ? c.getApplicationInfo() : null;
                if (ai != null && android.app.Application.macapkTargetSdkKnown(ai.targetSdkVersion)) {
                    return ai.targetSdkVersion
                            >= android.os.Build.VERSION_CODES.HONEYCOMB;
                }
            } catch (Throwable ignored) { }
            return true;
        }
        return mSplitTouchEnabled == 1;
    }

    // ==================================================================
    // API-36 surface completion (apidiff: android.widget.PopupWindow had 9
    // public members missing -- every one of them the READ half of a setter
    // that already existed here). AOSP:
    // frameworks/base/core/java/android/widget/PopupWindow.java
    // (android16-release); cited line numbers are from that file.
    // ==================================================================

    // AOSP :190 -- note the default is TRUE, not false.
    private boolean mAttachedInDecor = true;
    private float mElevation;
    private android.graphics.Rect mEpicenterBounds;
    // AOSP :197/:202 -- the measure specs handed to the window manager,
    // 0 by default meaning "use the width/height fields instead".
    private int mWidthMode;
    private int mHeightMode;

    /** AOSP :1051 / :598 / :495. getEpicenterBounds copies out, matching the
     *  copy-in in setEpicenterBounds above. */
    public boolean isAttachedInDecor() { return mAttachedInDecor; }
    public float getElevation() { return mElevation; }
    public android.graphics.Rect getEpicenterBounds() {
        return mEpicenterBounds != null ? new android.graphics.Rect(mEpicenterBounds) : null;
    }

    /** AOSP :923 / :1025 / :1141. isTouchModal is the read side of
     *  setTouchModal, which stores the NEGATION (mNotTouchModal). */
    public boolean isClippedToScreen() { return mClipToScreen; }
    public boolean isLaidOutInScreen() { return mLayoutInScreen; }
    public boolean isTouchModal()      { return !mNotTouchModal; }

    /** AOSP :1038 -- the modern name for setLayoutInScreenEnabled, same field. */
    public void setIsLaidOutInScreen(boolean enabled) { mLayoutInScreen = enabled; }

    /** AOSP :633 -- one-way latch, exactly as AOSP writes it (there is no
     *  argument and no way to clear it). It suppresses
     *  FLAG_IGNORE_CHEEK_PRESSES on the popup's window. */
    public void setIgnoreCheekPress() { mIgnoreCheekPress = true; }

    /** AOSP :1170. */
    public void setWindowLayoutMode(int widthSpec, int heightSpec) {
        mWidthMode = widthSpec;
        mHeightMode = heightSpec;
    }
}
