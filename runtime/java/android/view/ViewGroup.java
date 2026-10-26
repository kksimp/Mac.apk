// android.view.ViewGroup -- View that holds children. We track them in
// an ArrayList and forward layout/touch dispatch trivially. Real
// Android measures and lays out children; Mac.apk's apps either don't
// rely on layout (game-style: one full-screen GLSurfaceView child) or
// they manage layout themselves.
package android.view;

import android.content.Context;
import android.graphics.Rect;
import android.view.animation.Animation;
import android.view.animation.LayoutAnimationController;
import java.util.ArrayList;
import java.util.List;

// AOSP: `public abstract class ViewGroup extends View implements ViewParent,
// ViewManager` (frameworks/base/core/java/android/view/ViewGroup.java). The
// `ViewManager` half was missing because ViewManager was mistakenly shipped as a
// CLASS until 2026-07-29; without it, a guest doing `(ViewManager) someViewGroup`
// -- the documented way to add/remove views through an abstract handle -- got a
// ClassCastException. addView(View, LayoutParams) / updateViewLayout /
// removeView already exist below with ViewManager's exact signatures, so this is
// a declaration fix, not new behaviour.
public class ViewGroup extends View implements ViewParent, ViewManager {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_CLIP_CHILDREN = System.getenv("MACAPK_CLIP_CHILDREN");
    private static final String ENV_MACAPK_SKIP_DRAW = System.getenv("MACAPK_SKIP_DRAW");
    private static final String ENV_MACAPK_TRACE_GAP_RELAYOUT = System.getenv("MACAPK_TRACE_GAP_RELAYOUT");
    private static final String ENV_MACAPK_TRACE_RV_LAYOUT = System.getenv("MACAPK_TRACE_RV_LAYOUT");
    private static final String ENV_MACAPK_TRACE_SHADOW = System.getenv("MACAPK_TRACE_SHADOW");


    public static class LayoutParams {
        public static final int FILL_PARENT  = -1;
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width;
        public int height;

        /** AOSP ViewGroup.LayoutParams.layoutAnimationParameters: the per-child
         *  stagger bookkeeping a ViewGroup attaches before running a layout
         *  animation (ViewGroup.attachLayoutAnimationParameters), and the ONLY
         *  input LayoutAnimationController.getDelayForView reads. A missing
         *  public field is a NoSuchFieldError at the app's own assignment. */
        public android.view.animation.LayoutAnimationController.AnimationParameters
                layoutAnimationParameters;

        /** AOSP ViewGroup.LayoutParams.setBaseAttributes(TypedArray, int, int):
         *  the protected hook a LayoutParams subclass overrides to read width /
         *  height out of its own styleable, called from the (Context,
         *  AttributeSet) constructor. AOSP's body is exactly these two reads. */
        protected void setBaseAttributes(android.content.res.TypedArray a,
                int widthAttr, int heightAttr) {
            width = a.getLayoutDimension(widthAttr, "layout_width");
            height = a.getLayoutDimension(heightAttr, "layout_height");
        }

        /** v1.0.123: AOSP `ViewGroup.LayoutParams.resolveLayoutDirection`.
         *  No-op for the base LayoutParams (no RTL-aware fields).
         *  Subclasses override to resolve their locale-aware
         *  start/end / before/after attrs into absolute left/right /
         *  top/bottom equivalents.
         *
         *  Per cs.android.com/.../view/ViewGroup.java
         *  `public void resolveLayoutDirection(int layoutDirection) { }`
         *
         *  This method is invoked once per child during the
         *  View.measure() pass via the resolveRtlPropertiesIfNeeded()
         *  cascade. ConstraintLayout's LayoutParams override translates
         *  `startToEnd` (locale-aware) into `mResolvedLeftToRight`
         *  (absolute) which `applyConstraintsFromLayoutParams` reads
         *  during the constraint solve -- WITHOUT this cascade, every
         *  start/end constraint stays UNSET and the solver leaves
         *  widget.X = 0, so name + summary TextViews render overlapping
         *  the icon at x=0..96 instead of starting at x=96.
         */
        public void resolveLayoutDirection(int layoutDirection) {}

        /** v1.0.101: every LayoutParams carries a Gravity bit field so
         *  the post-layout fixup pass in ViewCanvasDriver can position
         *  children whose parent's onLayout doesn't honor gravity
         *  (typical: CoordinatorLayout / ConstraintLayout from the
         *  APK whose onLayout requires a real measure pass we don't
         *  run). 0 == Gravity.NO_GRAVITY == "don't touch". The base
         *  FrameLayout / CoordinatorLayout / RelativeLayout already
         *  declared layout_gravity on AOSP — moving it here means every
         *  LayoutParams subclass inherits gravity-awareness for free,
         *  which lets us position FAB / SnackBar / dialog-fragment
         *  children even when their parent class is an APK-bundled
         *  custom ViewGroup we can't reach into. */
        public int macapkGravity = android.view.Gravity.NO_GRAVITY;

        public LayoutParams() {}
        public LayoutParams(int w, int h) { this.width = w; this.height = h; }
        public LayoutParams(LayoutParams src) {
            this.width = src.width; this.height = src.height;
            this.macapkGravity = src.macapkGravity;
        }
        public LayoutParams(Context c, android.util.AttributeSet attrs) {}
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;
        // v1.0.123: AOSP-spec start/end margins (set via setMarginStart /
        // setMarginEnd). Per AOSP MarginLayoutParams these are stored
        // separately and resolved into leftMargin / rightMargin by
        // resolveLayoutDirection based on layoutDirection (LTR vs RTL).
        // DEFAULT_MARGIN_RELATIVE = Integer.MIN_VALUE per AOSP sentinel.
        public static final int DEFAULT_MARGIN_RELATIVE = Integer.MIN_VALUE;
        public int startMargin = DEFAULT_MARGIN_RELATIVE;
        public int endMargin   = DEFAULT_MARGIN_RELATIVE;
        public MarginLayoutParams() { super(); }
        public MarginLayoutParams(int w, int h) { super(w, h); }
        public MarginLayoutParams(LayoutParams src) { super(src); }
        public MarginLayoutParams(MarginLayoutParams src) {
            super(src);
            leftMargin = src.leftMargin; topMargin = src.topMargin;
            rightMargin = src.rightMargin; bottomMargin = src.bottomMargin;
            startMargin = src.startMargin; endMargin = src.endMargin;
        }
        public MarginLayoutParams(Context c, android.util.AttributeSet attrs) {}
        public void setMargins(int l, int t, int r, int b) {
            leftMargin = l; topMargin = t; rightMargin = r; bottomMargin = b;
        }
        public void setMarginStart(int s) { startMargin = s; }
        public void setMarginEnd(int e)   { endMargin = e; }
        public int  getMarginStart() {
            return startMargin == DEFAULT_MARGIN_RELATIVE ? leftMargin : startMargin;
        }
        public int  getMarginEnd() {
            return endMargin == DEFAULT_MARGIN_RELATIVE ? rightMargin : endMargin;
        }
        public boolean isMarginRelative() {
            return startMargin != DEFAULT_MARGIN_RELATIVE
                || endMargin != DEFAULT_MARGIN_RELATIVE;
        }
        /** v1.0.123: AOSP `MarginLayoutParams.resolveLayoutDirection`.
         *  Per cs.android.com/.../view/ViewGroup.java the LTR branch
         *  copies startMargin -> leftMargin and endMargin -> rightMargin
         *  (when set); the RTL branch swaps. Empty start/end leave
         *  leftMargin / rightMargin untouched (their XML-set values
         *  via android:layout_margin{Left,Right} are honored).
         *
         *  Also stores `layoutDirection` so subclass overrides (like
         *  ConstraintLayout.LayoutParams.resolveLayoutDirection) can
         *  call `super` + then read `getLayoutDirection()` to make
         *  the resolved-flag decision. CL's resolveLayoutDirection
         *  at line 3814 does `isRtl = (LAYOUT_DIRECTION_RTL == getLayoutDirection())`
         *  on `this` (the LayoutParams).
         */
        // AOSP-private but exposed via getLayoutDirection.
        private int mLayoutDirection = View.LAYOUT_DIRECTION_LTR;
        public int getLayoutDirection() { return mLayoutDirection; }
        public void setLayoutDirection(int dir) { this.mLayoutDirection = dir; }
        @Override
        public void resolveLayoutDirection(int layoutDirection) {
            this.mLayoutDirection = layoutDirection;
            boolean isRtl = (layoutDirection == View.LAYOUT_DIRECTION_RTL);
            if (isRtl) {
                if (startMargin != DEFAULT_MARGIN_RELATIVE) rightMargin = startMargin;
                if (endMargin   != DEFAULT_MARGIN_RELATIVE) leftMargin  = endMargin;
            } else {
                if (startMargin != DEFAULT_MARGIN_RELATIVE) leftMargin  = startMargin;
                if (endMargin   != DEFAULT_MARGIN_RELATIVE) rightMargin = endMargin;
            }
        }
    }

    public static final int FOCUS_BEFORE_DESCENDANTS = 0x20000;
    public static final int FOCUS_AFTER_DESCENDANTS  = 0x40000;
    public static final int FOCUS_BLOCK_DESCENDANTS  = 0x60000;

    private final List<View> children = new ArrayList<>();
    /** AOSP FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW: set while this group runs its
     *  own onAttachedToWindow + attach listeners; addView then defers the child's
     *  attach to the children loop of macapkDispatchAttachedToWindow. */
    boolean macapkPreventDispatchAttach;   // package-private: View.measure's attach self-heal reads it
    /** MACAPK_PREVENT_DISPATCH_ATTACH (default ON; =0 = the old order: a child added
     *  during the group's own attach dispatch attaches at once, before the group's
     *  OnAttachStateChangeListeners run). */
    static final boolean PREVENT_DISPATCH_ATTACH =
        !"0".equals(System.getenv("MACAPK_PREVENT_DISPATCH_ATTACH"));

    public ViewGroup(Context c) { super(c); }
    public ViewGroup(Context c, android.util.AttributeSet a) { super(c, a); macapkInitClipFromAttributes(c, a, 0, 0); }
    public ViewGroup(Context c, android.util.AttributeSet a, int s) { super(c, a, s); macapkInitClipFromAttributes(c, a, s, 0); }
    public ViewGroup(Context c, android.util.AttributeSet a, int s, int r) { super(c, a, s, r); macapkInitClipFromAttributes(c, a, s, r); }

    /** MACAPK_CLIP_TO_PADDING (default ON). "0" restores the pre-change path:
     *  android:clipChildren / android:clipToPadding were never read from XML or a
     *  style, and dispatchDraw never clipped children to the padding. Read once. */
    static final boolean MACAPK_CLIP_TO_PADDING =
            !"0".equals(System.getenv("MACAPK_CLIP_TO_PADDING"));

    /** AOSP ViewGroup.initFromAttributes (android14-release ViewGroup.java:733-749),
     *  run from the ViewGroup constructor:
     *      final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ViewGroup,
     *              defStyleAttr, defStyleRes);
     *      ...
     *          case R.styleable.ViewGroup_clipChildren:
     *              setClipChildren(a.getBoolean(attr, true));
     *              break;
     *          case R.styleable.ViewGroup_clipToPadding:
     *              setClipToPadding(a.getBoolean(attr, true));
     *              break;
     *  So the XML/style value is in place before the subclass constructor body
     *  (which may override it), and the setters are the VIRTUAL ones
     *  (RecyclerView's setClipToPadding override sees the call, as on a device).
     *  AOSP walks getIndexCount(), so only an attribute that is PRESENT calls its
     *  setter: hence hasValue. Before this, clipChildren="false" and
     *  clipToPadding="false" in a layout were silently ignored. The XML read and
     *  dispatchDraw's clip land together: the clip without the read would clip
     *  every clipToPadding="false" list. */
    private void macapkInitClipFromAttributes(Context c, android.util.AttributeSet a,
                                              int defStyleAttr, int defStyleRes) {
        if (!MACAPK_CLIP_TO_PADDING || c == null) return;
        if (a == null && defStyleAttr == 0 && defStyleRes == 0) return;
        // Only OUR attribute machinery is guarded; the setters run outside the
        // try so an app override that throws does so as it would on a device.
        Boolean clipChildrenAttr = null, clipToPaddingAttr = null;
        android.content.res.TypedArray ta = null;
        try {
            // Ascending attr ids (0x010100ea, 0x010100eb), as obtainStyledAttributes requires.
            ta = c.obtainStyledAttributes(a, new int[] {
                    android.R.attr.clipChildren, android.R.attr.clipToPadding },
                    defStyleAttr, defStyleRes);
            if (ta.hasValue(0)) clipChildrenAttr = ta.getBoolean(0, true);
            if (ta.hasValue(1)) clipToPaddingAttr = ta.getBoolean(1, true);
        } catch (Throwable err) {
            com.macapk.loader.GfxTrace.caught("ViewGroup.clipAttrs:" + getClass().getName(), err);
        } finally {
            if (ta != null) { try { ta.recycle(); } catch (Throwable ignored) { } }
        }
        if (clipChildrenAttr != null) setClipChildren(clipChildrenAttr);
        if (clipToPaddingAttr != null) setClipToPadding(clipToPaddingAttr);
    }

    /** Recursive attach/detach dispatch -- self first, then children
     *  (AOSP ViewGroup.dispatchAttachedToWindow order). */
    @Override public void macapkDispatchAttachedToWindow() {
        boolean wasAttached = isAttachedToWindow();
        // AOSP (android14 ViewGroup.dispatchAttachedToWindow):
        //     mGroupFlags |= FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW;
        //     super.dispatchAttachedToWindow(info, visibility);
        //     mGroupFlags &= ~FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW;
        //     final int count = mChildrenCount; ... child.dispatchAttachedToWindow(...)
        // super runs onAttachedToWindow() and then this view's
        // OnAttachStateChangeListeners. A child added DURING that (a ComposeView adds
        // its AndroidComposeView in onAttachedToWindow -> ensureCompositionCreated)
        // is not attached by addViewInner but by the loop below, i.e. AFTER this
        // group's listeners ran. Code relies on that order: Conductor's
        // ViewAttachHandler, from the controller view's onViewAttachedToWindow, puts
        // a listener on the DEEPEST first child and reports the controller attached
        // only when that child attaches. When we attached the child inside addView,
        // the listener landed on an already-attached view, never fired, and the
        // controller stayed detached for good -- Reddit's welcome sheet
        // ("Continue with Google" is gated on the screen being attached) did
        // nothing on a tap. MACAPK_PREVENT_DISPATCH_ATTACH=0 restores the old order.
        // Only the outermost dispatch of THIS group arms and clears it: a re-entrant
        // (already-attached, so no-op) call must not clear the outer one's flag.
        boolean armed = PREVENT_DISPATCH_ATTACH && !wasAttached;
        if (armed) macapkPreventDispatchAttach = true;
        try {
            super.macapkDispatchAttachedToWindow();
        } finally {
            if (armed) macapkPreventDispatchAttach = false;
        }
        if (wasAttached) return;
        for (int i = 0; i < children.size(); ++i) {
            View c = children.get(i);
            if (c != null) c.macapkDispatchAttachedToWindow();
        }
    }
    @Override public void macapkDispatchDetachedFromWindow() {
        // AOSP ViewGroup.dispatchDetachedFromWindow always walks the children;
        // each child's own attached flag decides whether it has anything to do
        // (View's detach is a no-op when not attached). v1.0.1643: this
        // returned early when the GROUP was not marked attached, so a child
        // that WAS attached under it -- e.g. an indeterminate ProgressBar --
        // never got onDetachedFromWindow, kept re-posting its 50 ms tick, and
        // pinned a destroyed activity (debug/hprof_retainers.sh).
        super.macapkDispatchDetachedFromWindow();
        // AOSP dispatchDetachedFromWindow (android14 :3964-3971): "Tear down our
        // drag tracking".
        mChildrenInterestedInDrag = null;
        mIsInterestedInDrag = false;
        mCurrentDragStartEvent = null;
        for (int i = 0; i < children.size(); ++i) {
            View c = children.get(i);
            if (c != null) c.macapkDispatchDetachedFromWindow();
        }
    }

    /** AOSP ViewGroup.dispatchConfigurationChanged (android14-release
     *  ViewGroup.java:1642-1649):
     *      public void dispatchConfigurationChanged(Configuration newConfig) {
     *          super.dispatchConfigurationChanged(newConfig);
     *          final int count = mChildrenCount;
     *          final View[] children = mChildren;
     *          for (int i = 0; i < count; i++) {
     *              children[i].dispatchConfigurationChanged(newConfig);
     *          }
     *      }
     *
     *  v1.0.1047: this override did not exist, even though View's base
     *  implementation has claimed in a comment for a long time that "ViewGroup
     *  overrides the dispatch half to recurse into children". It did not, so a
     *  configuration change reached the decor view and STOPPED -- every child in
     *  an already-inflated tree was never told. That was invisible while nothing
     *  drove a live configuration change; a macOS light/dark flip now does
     *  (Boot.macapkApplySystemAppearance), and a view that repaints on
     *  onConfigurationChanged is exactly how an app follows the system theme
     *  without being recreated. */
    @Override
    public void dispatchConfigurationChanged(android.content.res.Configuration newConfig) {
        super.dispatchConfigurationChanged(newConfig);
        // Snapshot the child list: a child's own onConfigurationChanged is app
        // code and may add or remove views (a theme flip legitimately rebuilds
        // parts of a tree), which would otherwise throw from under this walk.
        for (View child : new java.util.ArrayList<View>(children)) {
            if (child != null) {
                try { child.dispatchConfigurationChanged(newConfig); }
                catch (Throwable ignored) {}
            }
        }
    }

    public int getChildCount()                  { return children.size(); }
    public View getChildAt(int idx)             { return idx >= 0 && idx < children.size() ? children.get(idx) : null; }
    public int  indexOfChild(View v)            { return children.indexOf(v); }

    // AOSP overload funnel (android13-release ViewGroup.java:4988-5079,
    // fetched 2026-08-01): the 1-arg / 2-arg / (w,h) / (LP) overloads all
    // converge UP-arity on the VIRTUAL 3-arg addView(View,int,LayoutParams),
    // and the 3-arg form never re-enters any other public addView -- it goes
    // straight to a private terminus (AOSP addViewInner; here
    // macapkAddViewInner). The previous shape chained DOWN-arity (3-arg ->
    // virtual 2-arg), which broke subclasses that override the low-arity
    // forms to validate app-added children: material TabLayout's constructor
    // adds its own SlidingTabIndicator via super.addView(strip, 0, lp),
    // AOSP routes that to the terminus, but our down-chain re-entered
    // TabLayout's own addView(View,int) override -> "Only TabItem instances
    // can be added to TabLayout" thrown INSIDE the ctor -> FrameLayout
    // placeholder -> app's findViewById cast throws (measured: NewPipe
    // ScrollableTabLayout, campaign log 2026-08-01). It also meant a
    // subclass overriding ONLY the 3-arg form (the AOSP-canonical hook
    // point) never saw 1/2-arg adds at all.
    // ---- Layout animation (API 1) ----------------------------------------
    // AOSP ViewGroup.java: a LayoutAnimationController runs the same Animation
    // over every child as the group is laid out, staggered by the controller's
    // delay. It is set from XML via android:layoutAnimation or in code, and
    // ListView / GridView / any inflated container may carry one.
    //
    // These four were entirely ABSENT, which is a NoSuchMethodError at the call
    // site rather than a missing effect -- three corpus apps reference
    // setLayoutAnimation and two call scheduleLayoutAnimation
    // (debug/preflight.py --all, v1.0.940). An app that configures a list
    // animation in onCreate died there.
    //
    // ⚠ WE STORE AND REPORT, WE DO NOT YET ANIMATE. AOSP's
    // ViewGroup.dispatchDraw binds each child to
    // controller.getAnimationForView(child) on the first draw after
    // scheduleLayoutAnimation(); our ViewCanvasDriver draws children directly
    // and has no per-child Animation binding point, so a scheduled layout
    // animation completes instantly. That is the honest partial: the STATE
    // round-trips (an app that reads back getLayoutAnimation() gets what it
    // set), the controller's listener is notified so nothing waits forever,
    // and no call throws. Wiring the per-child bind belongs with the draw
    // path, not here.
    private LayoutAnimationController mLayoutAnimationController;
    private Animation.AnimationListener mAnimationListener;
    private boolean mLayoutAnimationScheduled;
    public void setLayoutAnimation(LayoutAnimationController controller) {
        mLayoutAnimationController = controller;
        // AOSP sets mGroupFlags |= FLAG_RUN_ANIMATION and requests a layout.
        mLayoutAnimationScheduled = true;
        requestLayout();
    }
    public LayoutAnimationController getLayoutAnimation() { return mLayoutAnimationController; }
    public void setLayoutAnimationListener(Animation.AnimationListener animationListener) {
        mAnimationListener = animationListener;
    }
    public Animation.AnimationListener getLayoutAnimationListener() { return mAnimationListener; }
    public void scheduleLayoutAnimation() { mLayoutAnimationScheduled = true; }
    /** AOSP: called once the scheduled animation has been bound to the
     *  children. We have no per-child bind (see above), so the pass is a no-op
     *  that still fires the listener -- an app gating on onAnimationEnd would
     *  otherwise never proceed. */
    protected void startLayoutAnimation() {
        if (!mLayoutAnimationScheduled) return;
        mLayoutAnimationScheduled = false;
        final Animation.AnimationListener l = mAnimationListener;
        if (l == null) return;
        final Animation a = (mLayoutAnimationController != null)
                ? mLayoutAnimationController.getAnimation() : null;
        try { l.onAnimationStart(a); } catch (Throwable ignored) {}
        try { l.onAnimationEnd(a); } catch (Throwable ignored) {}
    }

    public void addView(View child)             { addView(child, -1); }
    public void addView(View child, int index)  {
        if (child == null) return;
        // AOSP addView(view, index): resolve the child's LayoutParams (its
        // own, else generateDefaultLayoutParams()) BEFORE funneling into the
        // 3-arg overload. Required so setContentView's
        // `contentParent.addView(view)` (no params) sizes the content
        // root to fill the parent (e.g. FrameLayout -> MATCH_PARENT).
        LayoutParams params = child.getLayoutParams();
        if (params == null) {
            try { params = generateDefaultLayoutParams(); } catch (Throwable ignored) {}
        }
        addView(child, index, params);
    }
    private void macapkAddViewInner(View child, int index) {
        if (child == null) return;
        // Terminus-side net: a caller that reached here with no LayoutParams
        // at all (3-arg called with null lp on a child that never had one)
        // still gets this group's default, preserving the pre-funnel
        // behavior of every such call site.
        if (child.getLayoutParams() == null) {
            try {
                LayoutParams dlp = generateDefaultLayoutParams();
                if (dlp != null) child.setLayoutParams(dlp);
            } catch (Throwable ignored) {}
        }
        // v1.0.95: AOSP throws if child==this or child already in tree
        // ancestry. We don't throw (callers may be probing), but we
        // refuse to introduce a cycle. Without this, a self-add would
        // make every recursive tree walker (findViewById, dispatchDraw,
        // treeHasSurfaceView) spin until StackOverflowError.
        if (child == this) {
            com.macapk.loader.NativeLog.w("ViewGroup",
                "addView refused: cannot add " + getClass().getSimpleName()
                + " as a child of itself");
            return;
        }
        // v1.0.101: if `child` already has a parent, remove it from that
        // parent FIRST. AOSP throws IllegalStateException("The specified
        // child already has a parent") in this case; we auto-remove
        // instead (forgiving callers that re-add by mistake -- e.g.
        // Material's force-bind-then-re-attach pattern). The symptom
        // without this: a Latest NavigationBarItemView shows up in both
        // the BottomNavigationMenuView's children AND somewhere up the
        // tree (likely from a reparent during initial setup), and gets
        // drawn TWICE per frame -- once at the bottom-nav position
        // (correct) and once at the second parent's origin (wrong,
        // typically (0,0)). The F-Droid "small Latest-shape icon at
        // top-left" mystery from v1.0.100 traced back to exactly this.
        // Generic for every double-parenting situation -- no per-APK
        // assumption about who reparents.
        if (child.getParent() instanceof ViewGroup oldParent && oldParent != this) {
            oldParent.removeView(child);
        }
        if (index < 0 || index > children.size()) children.add(child); else children.add(index, child);
        child.setParent(this);
        // AOSP ViewGroup.addViewInner (aosp-mirror/platform_frameworks_base
        // android13-release, core/java/android/view/ViewGroup.java:5282-5284),
        // right after `child.mParent = this` and before the attach dispatch:
        //     final boolean childHasFocus = child.hasFocus();
        //     if (childHasFocus) requestChildFocus(child, child.findFocus());
        // A child can already be focused BEFORE it is attached: AOSP's own
        // <requestFocus/> tag fires INSIDE the child's OWN rInflate recursion,
        // which completes (and can therefore call View.requestFocus()) before
        // the PARENT's createViewFromTag/addView ever runs -- the same shape
        // our LayoutInflater.inflateElementInner uses for it (requestFocus()
        // is called on `view` before the caller's `vg.addView(childView)`).
        // Without this sync, a child that took focus pre-attach (mIsFocused
        // already true, its OWN drawable state already correct -- that part
        // does not need the parent chain) never gets registered on the new
        // parent's mFocused pointer, so an ANCESTOR'S hasFocus()/findFocus()
        // could not locate it after attach, same as a real device where
        // ViewGroup.hasFocus() walks mFocused down from the root. Generic:
        // fires for any child attached already-focused, not only
        // <requestFocus/>'s specific timing.
        if (child.hasFocus()) {
            requestChildFocus(child, child.findFocus());
        }
        // AOSP ViewGroup.addViewInner: a child that duplicates parent state arms
        // this group to re-fire its duplicating children whenever the group's own
        // drawable state changes (see drawableStateChanged below). Armed here and
        // never cleared, exactly like AOSP's
        // FLAG_NOTIFY_CHILDREN_ON_DRAWABLE_STATE_CHANGE.
        if (child.isDuplicateParentStateEnabled()) {
            macapkNotifyChildrenOnDrawableStateChange = true;
        }
        // AOSP semantics: a child only attaches when it joins a tree
        // that is itself window-attached (then the attach dispatch
        // recurses through the child's whole subtree). Previously we
        // fired onAttachedToWindow unconditionally on every addView,
        // which combined with the isAttachedToWindow()==true lie to
        // run attach-gated app code far too early.
        // ...EXCEPT while this group is inside its own attach dispatch
        // (onAttachedToWindow + attach listeners): AOSP addViewInner tests
        // (mGroupFlags & FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW) == 0 and leaves
        // such a child to the children loop that follows, so it attaches AFTER
        // this group's listeners ran. See macapkDispatchAttachedToWindow above.
        if (isAttachedToWindow() && !macapkPreventDispatchAttach) {
            child.macapkDispatchAttachedToWindow();
        }
        // v1.0.108: fire the AOSP onViewAdded hook so subclass
        // ViewGroups can register the new child. THIS WAS MISSING
        // and is load-bearing for ConstraintLayout -- AOSP's
        // ConstraintLayout.onViewAdded creates a ConstraintWidget for
        // the child, stores it on `LayoutParams.mWidget`, and adds it
        // to `mLayoutWidget` (the ConstraintWidgetContainer). Without
        // this call, ConstraintLayout's widget graph stays empty (or
        // stale), the constraint solver has nothing to solve, and
        // layout falls back to a no-constraint equal-divide on each
        // measure pass. Generic across any ViewGroup subclass that
        // overrides onViewAdded (CoordinatorLayout, MotionLayout,
        // DrawerLayout, etc.).
        try { onViewAdded(child); } catch (Throwable ignored) {}
        if (hierarchyListener != null) {
            try { hierarchyListener.onChildViewAdded(this, child); } catch (Throwable ignored) {}
        }
        // v1.0.572: a SurfaceView (or a subtree containing one) can join the
        // window's content tree AFTER Activity.setContentView already wired
        // the window to a canvas-only driver (AppCompat installs its
        // sub-decor before the app's view lands in ContentFrameLayout).
        // Real Android composites continuously, so the surface would still
        // go live; mirror that by letting Activity re-evaluate the window
        // wiring. No-ops immediately unless a canvas-only wiring is active.
        try { android.app.Activity.macapkMaybeRewireNativeSurface(child); }
        catch (Throwable ignored) {}
        macapkDragChildAdded(child);
    }
    /** AOSP ViewGroup.resolveLayoutParams() (android13-release
     *  ViewGroup.java:7794) RECURSES into every child:
     *      super.resolveLayoutParams();
     *      for (i...) getChildAt(i).resolveLayoutParams();
     *  and the same top-down shape drives resolveRtlPropertiesIfNeeded
     *  (:7690-7703). That cascade is what guarantees a child's LayoutParams
     *  are resolved BEFORE the parent's onMeasure reads them --
     *  ConstraintLayout's setChildrenConstraints runs inside the PARENT's
     *  measure pass and reads each child's resolved anchors, so a
     *  resolve-on-child-measure scheme (ours) is always one pass too late.
     *  Without this, `layout_constraintStart_toStartOf` / `End_toEndOf`
     *  never became left/right anchors and every dual-constrained
     *  wrap_content child rendered flush-left; a layout inflated once into
     *  a fragment never got the second dirty pass that used to hide it.
     *  See docs/canvas-ab-analyst-report_2026-08-02.md item 1. */
    @Override void macapkResolveLayoutParams() {
        super.macapkResolveLayoutParams();
        for (int i = 0; i < children.size(); i++) {
            View c = children.get(i);
            if (c != null) {
                try { c.macapkResolveLayoutParams(); } catch (Throwable ignored) {}
            }
        }
    }

    /** AOSP hook for ViewGroup subclasses to register new children.
     *  Base impl is empty -- subclasses override. */
    public void onViewAdded(View child) {}
    public void onViewRemoved(View child) {}
    public void addView(View child, int w, int h) {
        // AOSP: generateDefaultLayoutParams() with the given width/height,
        // then the 3-arg funnel.
        LayoutParams params = null;
        try { params = generateDefaultLayoutParams(); } catch (Throwable ignored) {}
        if (params == null) params = new LayoutParams(w, h);
        else { params.width = w; params.height = h; }
        addView(child, -1, params);
    }
    public void addView(View child, LayoutParams lp) { addView(child, -1, lp); }
    public void addView(View child, int index, LayoutParams lp) {
        if (child == null) return;
        // AOSP ViewGroup.addView(View, int, LayoutParams) (android13-release
        // core/java/android/view/ViewGroup.java:5063-5076):
        //     // addViewInner() will call child.requestLayout() when setting the new LayoutParams
        //     // therefore, we call requestLayout() on ourselves before, so that the child's request
        //     // will be blocked at our level
        //     requestLayout();
        //     invalidate(true);
        //     addViewInner(child, index, params, false);
        // The requestLayout() is the OVERRIDABLE one, and subclasses depend on
        // being told. androidx GridLayout overrides requestLayout() to
        // invalidateStructure() (hash := UNINITIALIZED_HASH) and has no
        // onViewAdded hook; its only other defence, consistencyCheck()'s
        // LayoutParams hash, CANNOT see a new child that replaced one at cell
        // (0,0): an undefined Interval(MIN, MIN+1) and Interval(0, 1) both hash
        // to 1. F-Droid's donate card does exactly that on every rebind
        // (removeAllViews + addView), so the new child reached onLayout with
        // span.min == Integer.MIN_VALUE -> AIOOBE, which unwound RecyclerView's
        // dispatchLayoutStep2 and killed the Install receiver.
        // docs/viewgroup-mutation-requestlayout_2026-09-28.md.
        if (MACAPK_VIEWGROUP_MUTATION_RELAYOUT) {
            requestLayout();
            invalidate();
        }
        // AOSP addViewInner():
        //     if (!checkLayoutParams(params)) { params = generateLayoutParams(params); }
        // i.e. a container converts a foreign LayoutParams into its own kind, and
        // THAT conversion is what carries margins / gravity / weight onto the
        // child. Wired here so the android.widget overrides of
        // generateLayoutParams(LayoutParams) are actually reached.
        //
        // ⚠ MEASURABLY INERT AS LANDED, and deliberately so. This tree's
        // ViewGroup.checkLayoutParams(LayoutParams) is `return true` (see its
        // declaration below) and NOTHING in runtime/java overrides it --
        // verified by a tree-wide grep at the time of writing -- so the guard
        // never fails and no conversion happens on any existing path. The
        // moment a container gains an AOSP-correct checkLayoutParams (AOSP's
        // base is `return p != null`, and each subclass tests `p instanceof
        // Its.LayoutParams`), this line starts converting, which IS a layout
        // change and must be measured against the Canvas fleet then. Landing
        // the guard now, rather than the guard and the overrides together,
        // keeps that change isolated and attributable.
        if (lp != null && !checkLayoutParams(lp)) {
            LayoutParams converted = generateLayoutParams(lp);
            if (converted != null) lp = converted;
        }
        if (lp != null) child.setLayoutParams(lp);
        macapkAddViewInner(child, index);
    }

    // removeView / removeViewAt and every bulk removal below share one tail
    // (macapkFinishRemoval): AOSP funnels all of them through
    // removeViewInternal / removeViewsInternal for the same reason, and the
    // bulk paths having drifted out of sync with this one is precisely what
    // dropped the detach dispatch on removeAllViews().
    /** AOSP ViewGroup.removeView (android13-release ViewGroup.java:5474-5479):
     *      if (removeViewInternal(view)) { requestLayout(); invalidate(true); }
     *  i.e. the overridable requestLayout() fires only when something was
     *  actually removed. Gated with addView; see MACAPK_VIEWGROUP_MUTATION_RELAYOUT. */
    public void removeView(View child) {
        if (child == null) return;
        if (!children.remove(child)) return;
        macapkFinishRemoval(child);
        macapkDragChildRemoved(child);
        if (MACAPK_VIEWGROUP_MUTATION_RELAYOUT) {
            requestLayout();
            invalidate();
        }
    }
    /** AOSP ViewGroup.removeViewAt (ViewGroup.java:5519-5523):
     *      removeViewInternal(index, getChildAt(index)); requestLayout(); invalidate(true);
     *  AOSP throws on a bad index; we keep returning quietly, and so issue no
     *  request for a removal that did not happen. */
    public void removeViewAt(int index) {
        if (index < 0 || index >= children.size()) return;
        View v = children.remove(index);
        if (v == null) return;
        macapkFinishRemoval(v);
        macapkDragChildRemoved(v);
        if (MACAPK_VIEWGROUP_MUTATION_RELAYOUT) {
            requestLayout();
            invalidate();
        }
    }
    /** AOSP ViewGroup.removeAllViews (ViewGroup.java:5730-5734) is exactly
     *  `removeAllViewsInLayout(); requestLayout(); invalidate(true);` -- all
     *  the per-child removal work lives in the inLayout variant, so delegate
     *  rather than keeping a second copy that can drift out of sync (which is
     *  how this path lost its detach dispatch in the first place).
     *
     *  The trailing requestLayout() / invalidate() used to be a deliberate
     *  deviation, "until the whole family gains them in one reviewed change".
     *  That change landed 2026-09-28 (lane fdroid-install-gridlayout): addView, removeView, removeViewAt and this
     *  method now all issue them, like AOSP (removeViews already did). The
     *  "InLayout" variants still do not, exactly as AOSP. =0 on
     *  MACAPK_VIEWGROUP_MUTATION_RELAYOUT restores the old, request-less family. */
    public void removeAllViews() {
        removeAllViewsInLayout();
        if (MACAPK_VIEWGROUP_MUTATION_RELAYOUT) {
            requestLayout();
            invalidate();
        }
    }

    /** MACAPK_VIEWGROUP_MUTATION_RELAYOUT (default ON): addView / removeView /
     *  removeViewAt / removeAllViews call this group's OVERRIDABLE
     *  requestLayout() and invalidate(), as AOSP's do (ViewGroup.java:5063-5076,
     *  5474-5479, 5519-5523, 5730-5734). A subclass that caches child structure
     *  and drops the cache in its requestLayout() override (androidx GridLayout's
     *  invalidateStructure) never heard about a new or departed child without
     *  it. =0 is the byte-identical old path: no request from any of the four.
     *  Gate: debug/vgmutation_gate.sh. */
    static final boolean MACAPK_VIEWGROUP_MUTATION_RELAYOUT =
        !"0".equals(System.getenv("MACAPK_VIEWGROUP_MUTATION_RELAYOUT"));

    public boolean shouldDelayChildPressedState() { return true; }

    // v1.0.98: SwipeRefreshLayout enables custom child draw order so
    // the spinner can render above other children. Honoured by the hit test
    // (dispatchTouchEvent) and, under MACAPK_CHILD_DRAW_ORDER (default ON), by
    // the paint pass too (dispatchDraw); =0 paints in insertion order again.
    private boolean childrenDrawingOrderEnabled;
    protected void setChildrenDrawingOrderEnabled(boolean enabled) {
        this.childrenDrawingOrderEnabled = enabled;
    }
    public boolean isChildrenDrawingOrderEnabled() { return childrenDrawingOrderEnabled; }
    protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        return drawingPosition;
    }
    public int getChildDrawingOrder(int drawingPosition) {
        return getChildDrawingOrder(getChildCount(), drawingPosition);
    }
    // Nested-scroll / touch-intercept request flow used by
    // SwipeRefreshLayout / RecyclerView / ViewPager. AOSP: set THIS group's
    // disallow flag (so it won't steal the in-progress gesture from the
    // child that requested it) and propagate up to ancestors. Reset on each
    // ACTION_DOWN in dispatchTouchEvent (per-gesture).
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // AOSP ViewGroup.java:3226-3243 short-circuits when the flag is already
        // in the requested state ("We're already in this state, assume our
        // ancestors are too"), which also stops a scrolling child that re-asks
        // on every MOVE from re-walking the whole ancestor chain each frame.
        if (disallow == disallowIntercept) return;
        disallowIntercept = disallow;
        ViewParent p = getParent();
        // AOSP forwards through the ViewParent interface, not a ViewGroup cast,
        // so a non-ViewGroup parent (a ViewRootImpl stand-in) still hears it.
        if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
    }

    public interface OnHierarchyChangeListener {
        void onChildViewAdded(View parent, View child);
        void onChildViewRemoved(View parent, View child);
    }
    private OnHierarchyChangeListener hierarchyListener;
    public void setOnHierarchyChangeListener(OnHierarchyChangeListener l) { this.hierarchyListener = l; }
    public OnHierarchyChangeListener getOnHierarchyChangeListener() { return hierarchyListener; }

    // Animation cache flags (deprecated AOSP API; some libs still set).
    public boolean isAnimationCacheEnabled() { return false; }
    public void setAnimationCacheEnabled(boolean enabled) {}
    public boolean isAlwaysDrawnWithCacheEnabled() { return false; }
    public void setAlwaysDrawnWithCacheEnabled(boolean enabled) {}
    public int getPersistentDrawingCache() { return 0; }
    public void setPersistentDrawingCache(int drawingCacheToKeep) {}
    /** AOSP FLAG_SPLIT_MOTION_EVENTS (ViewGroup.java:392), set in
     *  initViewGroup() (ViewGroup.java:714-716):
     *
     *      if (mContext.getApplicationInfo().targetSdkVersion
     *              >= Build.VERSION_CODES.HONEYCOMB) {
     *          mGroupFlags |= FLAG_SPLIT_MOTION_EVENTS;
     *      }
     *
     *  So on every app built this decade split touch is ON unless the app turns
     *  it off. We used to answer a flat `false` and never split, which is what
     *  the fwprobe golden caught (DISP.hier.grp.t1.splitEnabled ref=1 ours=0)
     *  and what made every DISP.multi.split* trace deliver the whole
     *  multi-pointer event to whichever child took the first DOWN.
     *
     *  DEVIATION, deliberate: AOSP bakes the bit into mGroupFlags in the
     *  CONSTRUCTOR. We resolve it lazily and memo the answer PROCESS-WIDE,
     *  because targetSdkVersion cannot change inside a process while a
     *  ViewGroup constructor runs on the RecyclerView recycle path, where one
     *  getApplicationInfo() per child is a measurable cost (it has been a
     *  hot-path suspect in this tree before). An explicit
     *  setMotionEventSplittingEnabled() call still wins, per-instance. */
    private static final int SPLIT_INHERIT = 0, SPLIT_ON = 1, SPLIT_OFF = 2;
    private int splitMotionEvents = SPLIT_INHERIT;
    private static int sSplitDefault = SPLIT_INHERIT;
    public boolean isMotionEventSplittingEnabled() {
        if (splitMotionEvents != SPLIT_INHERIT) return splitMotionEvents == SPLIT_ON;
        return macapkDefaultSplitMotionEvents();
    }
    public void setMotionEventSplittingEnabled(boolean split) {
        splitMotionEvents = split ? SPLIT_ON : SPLIT_OFF;
    }
    private boolean macapkDefaultSplitMotionEvents() {
        final int memo = sSplitDefault;
        if (memo != SPLIT_INHERIT) return memo == SPLIT_ON;
        int targetSdk = 0;
        try {
            Context c = getContext();
            if (c != null) {
                android.content.pm.ApplicationInfo ai = c.getApplicationInfo();
                if (ai != null) targetSdk = ai.targetSdkVersion;
            }
        } catch (Throwable ignored) { }
        // An UNRESOLVED targetSdk means our ApplicationInfo stub answered
        // before the manifest was parsed (real Android always populates it).
        // Answer the modern default -- the same call
        // Context.macapkSelectDefaultTheme makes -- but do NOT memo, so a
        // later query re-resolves against the real value. A REAL 0 (no
        // <uses-sdk>, android.app.Application.macapkTargetSdkKnown under
        // MACAPK_TARGETSDK_ZERO) is below HONEYCOMB and memoises OFF, as AOSP.
        if (!android.app.Application.macapkTargetSdkKnown(targetSdk)) return true;
        boolean on = targetSdk >= android.os.Build.VERSION_CODES.HONEYCOMB;
        sSplitDefault = on ? SPLIT_ON : SPLIT_OFF;
        return on;
    }
    /** AOSP ViewGroup.setDescendantFocusability: validates, then stores the mode
     *  in mGroupFlags (FLAG_MASK_FOCUSABILITY); ours keeps it in a plain int
     *  beside its getter. This was `{}` until v1.0.1609, so the getter reported
     *  FOCUS_BEFORE_DESCENDANTS for every group regardless of what the app set,
     *  and PhoneWindow's FOCUS_AFTER_DESCENDANTS on the decor (installDecor)
     *  could not take. addFocusables now honours the mode, so it must be real.
     *  ⚠ ViewGroup.requestFocus does NOT yet dispatch on it as AOSP's does
     *  (BEFORE: self then descendants; AFTER: descendants then self; BLOCK: self
     *  only) -- a known remaining gap, noted rather than silently half-done. */
    public void setDescendantFocusability(int f) {
        switch (f) {
            case FOCUS_BEFORE_DESCENDANTS:
            case FOCUS_AFTER_DESCENDANTS:
            case FOCUS_BLOCK_DESCENDANTS:
                break;
            default:
                throw new IllegalArgumentException("must be one of FOCUS_BEFORE_DESCENDANTS, "
                        + "FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS");
        }
        descendantFocusability = f;
    }
    /** AOSP API-1: returns the currently focused descendant, or null
     *  if no descendant has focus. We don't track keyboard focus on
     *  Mac.apk, so always null. Used by RecyclerView's LayoutManager
     *  in onLayoutChildren to preserve focus across layout passes. */
    // AOSP focus chain: the child along the path to the focused leaf (v1.0.420).
    private View mFocused;
    public View getFocusedChild() { return mFocused; }
    /** AOSP ViewGroup.requestChildFocus: record the child on the focus path,
     *  clear the previously-focused branch, and propagate to the parent so the
     *  whole root->leaf chain points at the new focus. */
    @Override public void requestChildFocus(View child, View focused) {
        // AOSP ViewGroup.requestChildFocus (android14): a blocking group ignores
        // the request, and a group that held focus ITSELF gives it up
        // (super.unFocus) once a descendant takes it. Without the unFocus a
        // RecyclerView that had focus kept mIsFocused=true under its newly
        // focused child, so findFocus() stopped at the RecyclerView and every
        // D-pad key went to it rather than to the focused card.
        if (getDescendantFocusability() == FOCUS_BLOCK_DESCENDANTS) return;
        super.unFocus(focused);
        if (mFocused != child) {
            if (mFocused != null) mFocused.unFocus(focused);  // clear the old focused branch
            mFocused = child;
        }
        ViewParent p = getParent();
        if (p != null) p.requestChildFocus(this, focused);
    }
    @Override public void clearChildFocus(View child) {
        mFocused = null;
        ViewParent p = getParent();
        if (p != null) p.clearChildFocus(this);
    }
    /** AOSP ViewGroup.hasFocus: this view or any descendant along mFocused. */
    @Override public boolean hasFocus() {
        return mIsFocused || (mFocused != null && mFocused.hasFocus());
    }
    /** AOSP ViewGroup.hasFocusable(boolean, boolean) (android14 :1266-1288) +
     *  hasFocusableChild (:1290-1306): a VISIBLE group is "focusable" when it is
     *  itself, or, unless it blocks descendants, when any child is.
     *
     *  ⚠ ViewGroup inherited View's self-only answer, so a non-focusable wrapper
     *  around a focusable view reported "nothing focusable here". RecyclerView /
     *  leanback GridLayoutManager.onAddFocusables skip every child whose
     *  hasFocusable() is false, so in leanback's headers sidebar (each header is a
     *  focusable view inside a plain NoOverlappingFrameLayout wrapper) a D-pad
     *  Down found 0 focusables and the selection never moved. */
    @Override
    boolean hasFocusable(boolean allowAutoFocus, boolean dispatchExplicit) {
        if (getVisibility() != VISIBLE) return false;
        if ((allowAutoFocus || getFocusable() != FOCUSABLE_AUTO) && isFocusable()) return true;
        if (getDescendantFocusability() != FOCUS_BLOCK_DESCENDANTS) {
            for (int i = 0; i < children.size(); i++) {
                View child = children.get(i);
                if (child == null) continue;
                if (dispatchExplicit ? child.hasExplicitFocusable() : child.hasFocusable()) return true;
            }
        }
        return false;
    }

    /** AOSP ViewGroup.handleFocusGainInternal (android14 :841-848): a group that
     *  takes focus ITSELF first unfocuses the descendant branch it was holding.
     *  Without it the old focused child kept mIsFocused under a focused parent:
     *  leanback's HeadersSupportFragment.onTransitionStart calls
     *  listView.requestFocus() on its headers grid while a header holds focus,
     *  and every later focus move "succeeded" on the stale header while
     *  findFocus() kept answering the grid, so the arrows stopped moving. */
    @Override
    void handleFocusGainInternal(int direction, android.graphics.Rect previouslyFocusedRect) {
        if (mFocused != null) {
            mFocused.unFocus(this);
            mFocused = null;
        }
        super.handleFocusGainInternal(direction, previouslyFocusedRect);
    }

    /** AOSP ViewGroup.dispatchUnhandledMove: pass the focus move that found no
     *  target down the mFocused chain, so the focused view (a ViewPager, a
     *  leanback grid) gets the last chance to act on the D-pad key. */
    @Override public boolean dispatchUnhandledMove(View focused, int direction) {
        return mFocused != null && mFocused.dispatchUnhandledMove(focused, direction);
    }
    /** AOSP ViewGroup.findFocus: walk the mFocused chain to the focused leaf. */
    @Override public View findFocus() {
        if (isFocused()) return this;
        return (mFocused != null) ? mFocused.findFocus() : null;
    }
    /** AOSP ViewGroup.unFocus: clearing this subtree's focus walks down the
     *  mFocused chain (no listener fires above the actual focused leaf). */
    @Override void unFocus(View focused) {
        if (mFocused == null) { super.unFocus(focused); }
        else { mFocused.unFocus(focused); mFocused = null; }
    }
    /** AOSP ViewGroup.dispatchWindowFocusChanged: recurse to children, then self. */
    @Override public void dispatchWindowFocusChanged(boolean hasFocus) {
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c != null) c.dispatchWindowFocusChanged(hasFocus);
        }
        super.dispatchWindowFocusChanged(hasFocus);
    }
    /** AOSP ViewGroup.dispatchVisibilityChanged: self FIRST (via super, which
     *  fires onVisibilityChanged on this ViewGroup), THEN every child -- the
     *  opposite order from dispatchWindowFocusChanged above. Per
     *  frameworks/base/core/java/android/view/ViewGroup.java (aosp-mirror
     *  android13-release, ~line 1604):
     *      @Override
     *      protected void dispatchVisibilityChanged(View changedView, int visibility) {
     *          super.dispatchVisibilityChanged(changedView, visibility);
     *          final int count = mChildrenCount;
     *          final View[] children = mChildren;
     *          for (int i = 0; i < count; i++) {
     *              children[i].dispatchVisibilityChanged(changedView, visibility);
     *          }
     *      }
     *  Without this override, View.setVisibility() on a ViewGroup fires
     *  onVisibilityChanged on the ViewGroup itself (via the base one-liner)
     *  but never reaches its children -- so a video/animation view nested
     *  inside a container that just went INVISIBLE/GONE never learns it, and
     *  keeps playing/polling while hidden. changedView/visibility are passed
     *  through unchanged so every descendant sees WHICH ancestor changed and
     *  to WHAT, not its own (possibly still-VISIBLE) flag. */
    @Override protected void dispatchVisibilityChanged(View changedView, int visibility) {
        super.dispatchVisibilityChanged(changedView, visibility);
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c != null) c.dispatchVisibilityChanged(changedView, visibility);
        }
    }
    /** AOSP ViewGroup.focusSearch(View, int) (android14-release ViewGroup.java:1074-1084):
     *
     *      if (isRootNamespace()) {
     *          // root namespace means we should consider ourselves the top of the
     *          // tree for focus searching; otherwise we could be focus searching
     *          // into other tabs.  see LocalActivityManager and TabHost for more info.
     *          return FocusFinder.getInstance().findNextFocus(this, focused, direction);
     *      } else if (mParent != null) {
     *          return mParent.focusSearch(focused, direction);
     *      }
     *      return null;
     *
     *  i.e. the search CLIMBS to the window's root-namespace decor and runs
     *  FocusFinder over the WHOLE window. From v1.0.686 to v1.0.1608 this method
     *  ran FocusFinder on `this` -- the focused view's IMMEDIATE parent -- so a
     *  sibling field that lived in a different container was never a candidate.
     *  Measured on Instagram's login form (owner-driven, v1.0.1608 witness
     *  `-> TAB: no next focusable (focused=...TextInputView)`): TAB reached
     *  Java, the activity declined it, focusSearch(FORWARD) on the username
     *  field answered null, because that field's own container holds no other
     *  focusable. The password field is one container over.
     *
     *  THE ONE DEVIATION, and why. In AOSP every ATTACHED tree has a ViewRootImpl
     *  as its top-most parent, and ViewRootImpl.focusSearch (ViewRootImpl.java:
     *  8596-8602) runs FocusFinder from the root view -- so the `return null`
     *  above is only ever reached by a DETACHED tree. Our ViewRootImpl is not a
     *  ViewParent and never parents anything, so a parentless top here is an
     *  attached window root (a WindowManager.addView'ed dialog / popup whose
     *  decor is not ours to flag). Standing in for ViewRootImpl.focusSearch at
     *  that point is the faithful translation; the only tree it changes the
     *  answer for is a detached one, where no key can arrive. */
    public View focusSearch(View focused, int direction) {
        if (isRootNamespace()) {
            return FocusFinder.getInstance().findNextFocus(this, focused, direction);
        }
        final ViewParent p = getParent();
        if (p != null) {
            return p.focusSearch(focused, direction);
        }
        // Parentless top of an attached tree: what ViewRootImpl.focusSearch does.
        return FocusFinder.getInstance().findNextFocus(this, focused, direction);
    }

    /** Protected AOSP method used by RecyclerView LayoutManager during
     *  detach/scrap. Per cs.android.com/.../view/ViewGroup.java
     *  detachViewFromParent calls removeFromArray which CLEARS the
     *  child's parent reference: `children[index].mParent = null;`.
     *  attachViewToParent re-sets it.
     *
     *  v1.0.124 fix: previously we used `children.remove(...)` which
     *  removes from the list but doesn't clear `child.parent`.
     *  RecyclerView.Recycler.recycleViewHolderInternal then checks
     *  `holder.itemView.getParent() != null` and throws
     *  "Scrapped or attached views may not be recycled" because the
     *  parent reference was still set. Clearing it here lets RV's
     *  Recycler properly recycle scrap views during dispatchLayoutStep3.
     */
    protected void detachViewFromParent(int index) {
        if (index >= 0 && index < children.size()) {
            View c = children.remove(index);
            if (c != null) c.setParent(null);
        }
    }
    protected void detachViewFromParent(View child) {
        if (children.remove(child) && child != null) {
            child.setParent(null);
        }
    }
    protected void detachAllViewsFromParent() {
        for (View c : children) {
            if (c != null) c.setParent(null);
        }
        children.clear();
    }
    protected void detachViewsFromParent(int start, int count) {
        for (int i = 0; i < count && start < children.size(); i++) {
            View c = children.remove(start);
            if (c != null) c.setParent(null);
        }
    }
    /** Protected AOSP companion: attaches a previously-detached child
     *  back to the parent. We put it in children AND restore the
     *  parent reference per AOSP `addInArray`. */
    protected void attachViewToParent(View child, int index, LayoutParams params) {
        if (child == null) return;
        try { child.setLayoutParams(params); } catch (Throwable ignored) {}
        if (index < 0 || index > children.size()) index = children.size();
        children.add(index, child);
        child.setParent(this);
    }
    protected void removeDetachedView(View child, boolean animate) {
        if (children.remove(child) && child != null) {
            child.setParent(null);
        } else if (child != null && child.getParent() == this) {
            // Not in children list but parent still points here -- clear.
            child.setParent(null);
        }
    }
    /** AOSP returns whether any scrolling was performed; with no scroll
     *  handling at this level we scrolled nothing, so false. */
    public boolean requestChildRectangleOnScreen(View child, android.graphics.Rect rect, boolean immediate) {
        return false;
    }
    public boolean onInterceptTouchEvent(MotionEvent ev) { return false; }

    // AOSP ViewGroup.getNestedScrollAxes() -- companion to the
    // NestedScrollingParent methods declared on ViewParent. 0 == this group
    // is not currently the target of a nested scroll (we don't do handoff).
    public int getNestedScrollAxes() { return 0; }

    // ==================================================================
    // TOUCH DISPATCH -- a port of AOSP ViewGroup.dispatchTouchEvent and its
    // helpers, from
    //   raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/
    //   android-11.0.0_r48/core/java/android/view/ViewGroup.java
    // (:2633-2835 dispatchTouchEvent, :2880-3015 the touch-target helpers,
    //  :3045-3069 hit testing, :3076-3146 dispatchTransformedTouchEvent).
    //
    // WHAT THIS REPLACED, AND WHY. The previous implementation kept a single
    // `View motionTarget` and re-derived everything else. Diffing the fwprobe
    // DISP battery against the API-30 emulator golden showed 104 divergent
    // answers attributable to it, in seven families:
    //   * a synthesized ACTION_CANCEL was OFFSET into child space -- AOSP's
    //     cancel path deliberately skips offsetLocation AND the matrix, so the
    //     child sees PARENT-space coordinates (:3080-3092);
    //   * after stealing a gesture we fell through to our OWN onTouchEvent for
    //     the stealing event -- AOSP only starts handling it on the NEXT event,
    //     because mFirstTouchTarget is still non-null when it picks the branch
    //     at :2783 and is unlinked inside the dispatch loop;
    //   * a stale target was never cancelled on a fresh DOWN (:2650-2656);
    //   * hit testing ignored this group's scroll while DELIVERY added it;
    //   * hit testing ignored the child's MATRIX, so a translated/scaled child
    //     was tappable where it was LAID OUT, not where it is DRAWN;
    //   * an INVISIBLE child still received touch (AOSP gates on
    //     canReceivePointerEvents, View.java:14341);
    //   * children were scanned in add order only -- no Z sort, no
    //     getChildDrawingOrder (:2719-2726);
    //   * multi-pointer streams were never SPLIT across children (:2687-2778).
    //
    // v1.0.1638: the one deliberate deviation -- the unlaid-out-child carve-out
    // documented at macapkChildFillsParent() below -- is now OFF by default,
    // so this is AOSP behaviour throughout (MACAPK_HIT_ZERO_FILLS=1 restores it
    // for an A/B).
    // ==================================================================

    /** AOSP ViewGroup.TouchTarget (:6862+). One entry per child currently
     *  receiving part of the pointer stream, newest at the HEAD (addTouchTarget
     *  prepends, and the dispatch loop walks from the head, which is why the
     *  child that took the newest pointer is offered each event first).
     *  pointerIdBits is a mask of MotionEvent pointer IDS (not indices).
     *  AOSP pools these behind a lock; we allocate, because a touch stream is
     *  at most a few objects per second and a pool is an allocation
     *  optimisation, not semantics. */
    private static final class TouchTarget {
        static final int ALL_POINTER_IDS = -1;   // all bits set
        View child;
        int pointerIdBits;
        TouchTarget next;
        TouchTarget(View child, int pointerIdBits) {
            this.child = child;
            this.pointerIdBits = pointerIdBits;
        }
    }

    private TouchTarget mFirstTouchTarget;
    private boolean disallowIntercept;  // AOSP FLAG_DISALLOW_INTERCEPT; cleared by resetTouchState
    private java.util.ArrayList<View> mPreSortedChildren;
    private static final boolean HIT_TRACE = System.getenv("MACAPK_HIT_TRACE") != null;

    /** AOSP ViewGroup.dispatchTouchEvent (:2633-2835).
     *
     *  Omitted from the port, each because the supporting machinery does not
     *  exist in this tree and its absence is behaviour-neutral here:
     *   - mInputEventConsistencyVerifier (debug-only in AOSP too);
     *   - onFilterTouchEventForSecurity (obscured-window filtering; View has no
     *     equivalent, and AOSP's default returns true);
     *   - the accessibility-focus fast path (MotionEvent has no
     *     targetAccessibilityFocus flag here);
     *   - resetCancelNextUpFlag(): View has no PFLAG_CANCEL_NEXT_UP_EVENT, so
     *     AOSP's `resetCancelNextUpFlag(x) || ...` terms are always false. See
     *     the View.java dependency note in the session report;
     *   - mLastTouchDown{Time,Index,X,Y} bookkeeping (read only by AOSP's own
     *     @hide getters, which we do not expose). */
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean handled = false;
        final int action = ev.getAction();
        final int actionMasked = action & MotionEvent.ACTION_MASK;

        // Handle an initial down. AOSP :2650-2656 -- throw away ALL previous
        // state first, because the framework may have dropped the previous
        // gesture's UP/CANCEL. The stale target is CANCELLED, not just
        // forgotten, and it happens BEFORE onInterceptTouchEvent is asked.
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            cancelAndClearTouchTargets(ev);
            resetTouchState();
        }

        // Check for interception. AOSP :2658-2673. Note the else branch: with
        // no targets and a non-DOWN action this group "continues to intercept
        // touches" WITHOUT calling onInterceptTouchEvent at all.
        final boolean intercepted;
        if (actionMasked == MotionEvent.ACTION_DOWN || mFirstTouchTarget != null) {
            if (!disallowIntercept) {
                intercepted = onInterceptTouchEvent(ev);
                ev.setAction(action);   // restore action in case it was changed
            } else {
                intercepted = false;
            }
        } else {
            intercepted = true;
        }

        // AOSP :2682-2683 is `resetCancelNextUpFlag(this) || actionMasked ==
        // ACTION_CANCEL`; the first term is always false here (see the method
        // comment above).
        final boolean canceled = actionMasked == MotionEvent.ACTION_CANCEL;

        // AOSP :2686-2688. Split touch is off for a mouse, which drives one
        // cursor rather than independent fingers.
        final boolean split = isMotionEventSplittingEnabled()
                && ev.getSource() != InputDevice.SOURCE_MOUSE;
        TouchTarget newTouchTarget = null;
        boolean alreadyDispatchedToNewTouchTarget = false;
        if (!canceled && !intercepted) {
            // AOSP :2700-2702. The target search runs ONLY for these three
            // actions -- which is what makes targeting STICKY: a MOVE never
            // re-hit-tests, so a gesture that wanders off the child (or over a
            // sibling) still belongs to the child that took the DOWN.
            if (actionMasked == MotionEvent.ACTION_DOWN
                    || (split && actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                    || actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                final int actionIndex = ev.getActionIndex();   // always 0 for down
                final int idBitsToAssign = split ? 1 << ev.getPointerId(actionIndex)
                        : TouchTarget.ALL_POINTER_IDS;

                // Clean up earlier touch targets for this pointer id in case
                // they have become out of sync (AOSP :2707-2709).
                removePointersFromTouchTargets(idBitsToAssign);

                final int childrenCount = children.size();
                if (childrenCount != 0) {
                    // AOSP reads getXCursorPosition() for a mouse; MotionEvent
                    // has no cursor-position axis here, and our host mouse is
                    // injected as SOURCE_TOUCHSCREEN anyway, so the pointer
                    // position is the only coordinate available.
                    final float x = ev.getX(actionIndex);
                    final float y = ev.getY(actionIndex);
                    // Scan children front to back (AOSP :2717-2726).
                    final java.util.ArrayList<View> preorderedList = buildTouchDispatchChildList();
                    final boolean customOrder = preorderedList == null
                            && isChildrenDrawingOrderEnabled();
                    for (int i = childrenCount - 1; i >= 0; i--) {
                        final int childIndex =
                                getAndVerifyPreorderedIndex(childrenCount, i, customOrder);
                        final View child = getAndVerifyPreorderedView(preorderedList, childIndex);
                        if (child == null) continue;   // our child list may hold nulls
                        // AOSP :2728-2731. canReceivePointerEvents() is
                        // "VISIBLE || getAnimation() != null" (View.java:14341),
                        // so an INVISIBLE or GONE child is skipped -- but one
                        // mid-animation is not. Before this port we only skipped
                        // GONE, so an INVISIBLE child ate the touch.
                        final boolean in = child.canReceivePointerEvents()
                                && isTransformedTouchPointInView(x, y, child, null);
                        if (HIT_TRACE) {
                            com.macapk.loader.NativeLog.i("Mac.apk.Hit",
                                getClass().getSimpleName() + " pt=(" + (int) x + "," + (int) y
                                + ") child=" + child.getClass().getSimpleName()
                                + " [" + child.getLeft() + "," + child.getTop()
                                + "-" + child.getRight() + "," + child.getBottom()
                                + "] vis=" + child.getVisibility()
                                + " clickable=" + child.isClickable() + " IN=" + in);
                        }
                        if (!in) continue;

                        newTouchTarget = getTouchTarget(child);
                        if (newTouchTarget != null) {
                            // Child is already receiving touch within its
                            // bounds: give it the new pointer IN ADDITION to
                            // the ones it holds, with no second dispatch
                            // (AOSP :2734-2739).
                            newTouchTarget.pointerIdBits |= idBitsToAssign;
                            break;
                        }
                        if (dispatchTransformedTouchEvent(ev, false, child, idBitsToAssign)) {
                            newTouchTarget = addTouchTarget(child, idBitsToAssign);
                            alreadyDispatchedToNewTouchTarget = true;
                            break;
                        }
                    }
                    if (preorderedList != null) preorderedList.clear();
                }

                if (newTouchTarget == null && mFirstTouchTarget != null) {
                    // No child took the new pointer -> assign it to the LEAST
                    // RECENTLY ADDED existing target (AOSP :2770-2778), i.e.
                    // the tail of the list.
                    newTouchTarget = mFirstTouchTarget;
                    while (newTouchTarget.next != null) newTouchTarget = newTouchTarget.next;
                    newTouchTarget.pointerIdBits |= idBitsToAssign;
                }
            }
        }

        // Dispatch to touch targets (AOSP :2782-2817).
        if (mFirstTouchTarget == null) {
            // No touch targets, so treat this as an ordinary view: the return
            // value is whatever THIS group's own onTouchListener/onTouchEvent
            // said, and nothing else.
            handled = dispatchTransformedTouchEvent(ev, canceled, null,
                    TouchTarget.ALL_POINTER_IDS);
        } else {
            TouchTarget predecessor = null;
            TouchTarget target = mFirstTouchTarget;
            while (target != null) {
                final TouchTarget next = target.next;
                if (alreadyDispatchedToNewTouchTarget && target == newTouchTarget) {
                    handled = true;
                } else {
                    // AOSP: resetCancelNextUpFlag(target.child) || intercepted.
                    final boolean cancelChild = intercepted;
                    if (dispatchTransformedTouchEvent(ev, cancelChild,
                            target.child, target.pointerIdBits)) {
                        handled = true;
                    }
                    if (cancelChild) {
                        if (predecessor == null) mFirstTouchTarget = next;
                        else predecessor.next = next;
                        target.child = null;
                        target.next = null;
                        target = next;
                        continue;
                    }
                }
                predecessor = target;
                target = next;
            }
        }

        // Update the target list for pointer up or cancel (AOSP :2819-2828).
        if (canceled
                || actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            resetTouchState();
        } else if (split && actionMasked == MotionEvent.ACTION_POINTER_UP) {
            removePointersFromTouchTargets(1 << ev.getPointerId(ev.getActionIndex()));
        }
        return handled;
    }

    // ---- touch-target list helpers (AOSP :2877-3015) ----

    /** AOSP :2880-2885. Also clears FLAG_DISALLOW_INTERCEPT, which is what
     *  makes requestDisallowInterceptTouchEvent PER-GESTURE. */
    private void resetTouchState() {
        clearTouchTargets();
        disallowIntercept = false;
        // AOSP also resets mNestedScrollAxes; getNestedScrollAxes() is a
        // constant 0 here (no nested-scroll handoff).
    }

    /** AOSP :2902-2912. */
    private void clearTouchTargets() {
        TouchTarget target = mFirstTouchTarget;
        while (target != null) {
            TouchTarget next = target.next;
            target.child = null;
            target.next = null;
            target = next;
        }
        mFirstTouchTarget = null;
    }

    /** AOSP :2917-2938. Every live target is sent an ACTION_CANCEL (through the
     *  cancel short-circuit in dispatchTransformedTouchEvent, so it arrives
     *  UN-OFFSET) and the list is emptied. `event` may be null, in which case a
     *  synthetic CANCEL at (0,0) is used. */
    private void cancelAndClearTouchTargets(MotionEvent event) {
        if (mFirstTouchTarget == null) return;
        boolean syntheticEvent = false;
        if (event == null) {
            final long now = android.os.SystemClock.uptimeMillis();
            event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            syntheticEvent = true;
        }
        for (TouchTarget target = mFirstTouchTarget; target != null; target = target.next) {
            dispatchTransformedTouchEvent(event, true, target.child, target.pointerIdBits);
        }
        clearTouchTargets();
        if (syntheticEvent) event.recycle();
    }

    /** AOSP :2944-2951. */
    private TouchTarget getTouchTarget(View child) {
        for (TouchTarget target = mFirstTouchTarget; target != null; target = target.next) {
            if (target.child == child) return target;
        }
        return null;
    }

    /** AOSP :2957-2962 -- prepends, so the newest target is offered each event
     *  FIRST. The fwprobe golden depends on this ordering
     *  (DISP.multi.splitTwoChildren.e1 delivers to B before A). */
    private TouchTarget addTouchTarget(View child, int pointerIdBits) {
        final TouchTarget target = new TouchTarget(child, pointerIdBits);
        target.next = mFirstTouchTarget;
        mFirstTouchTarget = target;
        return target;
    }

    /** AOSP :2967-2988. Drops the given pointer ids from every target and
     *  unlinks any target left holding none. */
    private void removePointersFromTouchTargets(int pointerIdBits) {
        TouchTarget predecessor = null;
        TouchTarget target = mFirstTouchTarget;
        while (target != null) {
            final TouchTarget next = target.next;
            if ((target.pointerIdBits & pointerIdBits) != 0) {
                target.pointerIdBits &= ~pointerIdBits;
                if (target.pointerIdBits == 0) {
                    if (predecessor == null) mFirstTouchTarget = next;
                    else predecessor.next = next;
                    target.child = null;
                    target.next = null;
                    target = next;
                    continue;
                }
            }
            predecessor = target;
            target = next;
        }
    }

    /** AOSP :2991-3015. Unlinks a view's target and hands it a synthetic
     *  ACTION_CANCEL. Provided so removal paths can adopt it (AOSP calls it
     *  from removeViewInternal); nothing calls it here yet. */
    private void cancelTouchTarget(View view) {
        TouchTarget predecessor = null;
        TouchTarget target = mFirstTouchTarget;
        while (target != null) {
            final TouchTarget next = target.next;
            if (target.child == view) {
                if (predecessor == null) mFirstTouchTarget = next;
                else predecessor.next = next;
                target.child = null;
                target.next = null;
                final long now = android.os.SystemClock.uptimeMillis();
                MotionEvent event = MotionEvent.obtain(now, now,
                        MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                view.dispatchTouchEvent(event);
                event.recycle();
                return;
            }
            predecessor = target;
            target = next;
        }
    }

    // ---- child ordering for hit testing (AOSP :2105-2118, :4411-4438) ----

    /** AOSP :2845-2847. */
    public java.util.ArrayList<View> buildTouchDispatchChildList() {
        return buildOrderedChildList();
    }

    /** AOSP :4411-4438. Null unless there is more than one child AND some child
     *  has a non-zero Z; otherwise a STABLE insertion sort ASCENDING by Z. The
     *  scan walks it in REVERSE, so the highest Z is hit-tested first. Note the
     *  consequence AOSP :2720 spells out: once this list is non-null, a custom
     *  getChildDrawingOrder is NOT consulted by the hit test -- setting a Z
     *  DISABLES the custom order for touch.
     *
     *  SCOPE NOTE: this list serves HIT TESTING. dispatchDraw() builds the same
     *  order into its own list (macapkBuildDrawOrderList, MACAPK_CHILD_DRAW_ORDER)
     *  so the paint pass and the hit test agree on what is on top. */
    java.util.ArrayList<View> buildOrderedChildList() {
        final int childrenCount = children.size();
        if (childrenCount <= 1 || !hasChildWithZ()) return null;
        if (mPreSortedChildren == null) {
            mPreSortedChildren = new java.util.ArrayList<>(childrenCount);
        }
        return macapkFillOrderedChildList(mPreSortedChildren);
    }

    /** The body of AOSP buildOrderedChildList (android14 :4451-4478): a STABLE insertion
     *  sort ascending by Z over the custom (or add) order, written into `list`. */
    private java.util.ArrayList<View> macapkFillOrderedChildList(java.util.ArrayList<View> list) {
        final int childrenCount = children.size();
        list.clear();
        list.ensureCapacity(childrenCount);
        final boolean customOrder = isChildrenDrawingOrderEnabled();
        for (int i = 0; i < childrenCount; i++) {
            final int childIndex = getAndVerifyPreorderedIndex(childrenCount, i, customOrder);
            final View nextChild = (childIndex >= 0 && childIndex < children.size())
                    ? children.get(childIndex) : null;
            final float currentZ = macapkZ(nextChild);
            int insertIndex = i;
            while (insertIndex > 0 && macapkZ(list.get(insertIndex - 1)) > currentZ) {
                insertIndex--;
            }
            list.add(insertIndex, nextChild);
        }
        return list;
    }

    /** MACAPK_CHILD_DRAW_ORDER (default ON): dispatchDraw paints children in AOSP's
     *  order -- Z-sorted, over the app's getChildDrawingOrder when it enabled one --
     *  instead of add order. =0 restores add order for every group (the pre-change
     *  paint), independently of MACAPK_DRAWCHILD_DISPATCH. See dispatchDraw. */
    static final boolean MACAPK_CHILD_DRAW_ORDER =
        !"0".equals(System.getenv("MACAPK_CHILD_DRAW_ORDER"));

    /** MACAPK_DRAWCHILD_DISPATCH (default ON): dispatchDraw paints every child
     *  through the overridable drawChild(), as AOSP does, so app overrides
     *  (DrawerLayout's scrim, CoordinatorLayout's behaviour scrim, custom dims) run.
     *  =0 restores the pre-change inline paint loop and the old drawChild body. */
    static final boolean MACAPK_DRAWCHILD_DISPATCH =
        !"0".equals(System.getenv("MACAPK_DRAWCHILD_DISPATCH"));

    /** The MACAPK_CLIP_CHILDREN=0 escape hatch, read once. The inline loop read
     *  System.getenv per dispatchDraw; the environment cannot change in-process,
     *  so this is the same answer without the per-frame lookup. */
    private static final boolean MACAPK_CLIP_CHILDREN_ENV =
        !"0".equals(System.getenv("MACAPK_CLIP_CHILDREN"));

    /** The draw pass's own preordered list. It is NOT mPreSortedChildren: the hit
     *  test fills and clears that one, and on this runtime a paint and a touch
     *  dispatch are not guaranteed to be on one thread the way AOSP's UI thread
     *  guarantees it, so sharing one mutable list would let one pass reorder the
     *  other mid-walk. */
    private java.util.ArrayList<View> mPreSortedDrawChildren;

    /** True while THIS group's dispatchDraw loop is handing children to drawChild,
     *  so drawChild can apply the pieces that were the inline loop's alone (the
     *  0-area skip, the MACAPK_CLIP_CHILDREN escape hatch) on that route and keep
     *  the old drawChild semantics for direct callers (Compose's
     *  DrawChildContainer, FragmentContainerView's disappearing children). Per
     *  instance; a child group's draw uses its own. */
    private boolean mMacapkInDispatchDraw;

    // ---- FRAMESPEED S3: quick-reject a child that lies wholly outside the clip ----
    //
    // AOSP never draws such a child. View.draw(Canvas, ViewGroup, long), the body
    // drawChild runs for every child (android14 View.java:23589-23596):
    //     if (!concatMatrix &&
    //             (parentFlags & (ViewGroup.FLAG_SUPPORT_STATIC_TRANSFORMATIONS |
    //                     ViewGroup.FLAG_CLIP_CHILDREN)) == ViewGroup.FLAG_CLIP_CHILDREN &&
    //             canvas.quickReject(mLeft, mTop, mRight, mBottom) &&
    //             (mPrivateFlags & PFLAG_DRAW_ANIMATION) == 0) {
    //         mPrivateFlags2 |= PFLAG2_VIEW_QUICK_REJECTED;
    //         return more;
    //     }
    // i.e. no animation or view matrix (concatMatrix), the parent clips its
    // children and has no static transformations, the child's bounds miss the
    // clip, and none of the child's own children is mid-animation. We drew it
    // anyway -- the whole subtree's measure-free draw (text layout, paths, AA
    // shapes) into a clip that discards every pixel: a ScrollView's or a
    // ViewPager's offscreen content, a RecyclerView's recycled-but-attached rows,
    // and (once S1+S2 run damage-clipped frames) everything outside the damage.
    // (The clip AOSP tests against differs by mode -- see "What changes" below.)
    //
    // EXACT, not a heuristic. The cull applies only where drawChild would clip the
    // child to its own bounds (the same condition as the per-child clip below), so
    // every pixel the subtree could write lies in the child's bounds intersected
    // with the current clip; our Canvas has no clip-expanding op (Region.Op
    // REPLACE/UNION/XOR are INTERSECT here, restore() only pops); and
    // Canvas.macapkQuickRejectChild tests the child rect against the ENCLOSING
    // integer bounds of the clip (or, with no clip set, the surface: Skia's device
    // clip) with a strict overlap test, so it can only answer true when that
    // intersection holds no pixel centre. Conservative
    // against AOSP where we cannot see: a child with a legacy animation is never
    // culled (AOSP culls an alpha-only one and returns `more`), and 3D rotations
    // count as a matrix (macapkHasAnyTransform). mMacapkDrawAnimation mirrors
    // PFLAG_DRAW_ANIMATION: set on a group when one of its children's legacy
    // animations wants more frames, cleared when its dispatchDraw starts (AOSP
    // ViewGroup.dispatchDraw:4285-4286).
    // What changes besides pixels: a culled subtree runs no draw-time code --
    // computeScroll, onDraw, a TextureView's frame latch, a Compose host's
    // draw-time measureAndLayout, an AOSP ProgressBar starting its Animatable in
    // onDraw -- until it is back in the clip. That is AOSP's SOFTWARE-rendering
    // behaviour, and hwui's for a child outside its parent's RenderNode
    // RECORDING bounds (a ViewPager's other pages, a closed drawer). It is NOT
    // hwui's for a child inside those bounds but off screen -- a ScrollView
    // content LinearLayout's rows: the parent records at its full size
    // (View.updateDisplayListIfDirty :22729 beginRecording(width, height)), so the
    // quickReject above keeps them, and a dirty row's onDraw / computeScroll run
    // whenever the parent re-records; viewport culling happens only in
    // RenderThread playback, which runs no app code. We skip such a row. Pixels
    // are identical either way (gate row A5); if a draw-time side effect in a
    // scrolled list goes missing, look here first (MACAPK_FRAMESPEED_CULL=0).
    // MACAPK_FRAMESPEED_CULL=0 restores the pre-change drawChild exactly.
    // Gate: debug/framespeed_gate.sh cull (positive control: a cull that ignores the
    // clip test must change pixels). docs/frame-speed-caret-clip-cull_2026-09-26.md.
    static final boolean MACAPK_FRAMESPEED_CULL =
        !"0".equals(System.getenv("MACAPK_FRAMESPEED_CULL"));
    /** Test-only (debug/framespeed_gate.sh cull, control C8): cull without the
     *  clip test, so the gate can prove its pixel comparison sees a wrong cull. */
    static final boolean MACAPK_FRAMESPEED_CULL_ALL_FOR_TEST =
        "1".equals(System.getenv("MACAPK_FRAMESPEED_CULL_ALL_FOR_TEST"));
    /** Children quick-rejected so far (the gate's proof that the cull ran). */
    static long sMacapkCulled;
    /** AOSP PFLAG_DRAW_ANIMATION on THIS group (see the block above). */
    private boolean mMacapkDrawAnimation;

    /** The AOSP quick-reject of `child`, in this group's drawChild space: true =
     *  draw nothing. `fromDispatch` as in drawChild. */
    private boolean macapkQuickRejectChild(android.graphics.Canvas canvas, View child,
                                           boolean fromDispatch) {
        if (View.MACAPK_LEGACY_ANIM && child.getAnimation() != null) return false;
        if (child.macapkHasAnyTransform() || mStaticTransformationsEnabled) return false;
        if (child instanceof ViewGroup && ((ViewGroup) child).mMacapkDrawAnimation) return false;
        final int cw = child.getRight() - child.getLeft();
        final int ch = child.getBottom() - child.getTop();
        // Exactly drawChild's per-child clip condition: no clip, no cull.
        if (!((!fromDispatch || MACAPK_CLIP_CHILDREN_ENV) && clipChildren && cw > 0 && ch > 0))
            return false;
        if (MACAPK_FRAMESPEED_CULL_ALL_FOR_TEST) return true;
        final float dx = child.getLeft() - getScrollX(), dy = child.getTop() - getScrollY();
        try {
            // Canvas.quickReject with Skia's device-clip meaning (a clip-less
            // surface canvas still rejects what lies off the surface).
            return canvas.macapkQuickRejectChild(dx, dy, dx + cw, dy + ch);
        } catch (Throwable t) {
            return false;   // an app Canvas subclass that cannot answer: draw it
        }
    }

    /** Null unless there is more than one child and some child has a non-zero Z
     *  (exactly buildOrderedChildList's condition); then the Z-sorted draw order. */
    private java.util.ArrayList<View> macapkBuildDrawOrderList() {
        final int childrenCount = children.size();
        if (childrenCount <= 1 || !hasChildWithZ()) return null;
        if (mPreSortedDrawChildren == null) {
            mPreSortedDrawChildren = new java.util.ArrayList<>(childrenCount);
        }
        return macapkFillOrderedChildList(mPreSortedDrawChildren);
    }

    private static float macapkZ(View v) { return v == null ? 0f : v.getZ(); }

    private boolean hasChildWithZ() {
        for (int i = 0; i < children.size(); i++) {
            View c = children.get(i);
            if (c != null && c.getZ() != 0) return true;
        }
        return false;
    }

    /** AOSP :2105-2118. Throws exactly as AOSP does when an app's
     *  getChildDrawingOrder override returns an out-of-range index -- a
     *  divergence here would silently hide an app bug that real Android turns
     *  into an immediate, loud failure. */
    private int getAndVerifyPreorderedIndex(int childrenCount, int i, boolean customOrder) {
        if (!customOrder) return i;
        final int childIndex1 = getChildDrawingOrder(childrenCount, i);
        if (childIndex1 >= childrenCount) {
            throw new IndexOutOfBoundsException("getChildDrawingOrder() returned invalid index "
                    + childIndex1 + " (child count is " + childrenCount + ")");
        }
        return childIndex1;
    }

    /** AOSP :3758-3771 (null entries are an AOSP RuntimeException; we skip). */
    private View getAndVerifyPreorderedView(java.util.ArrayList<View> preorderedList,
                                            int childIndex) {
        if (preorderedList != null) {
            return (childIndex >= 0 && childIndex < preorderedList.size())
                    ? preorderedList.get(childIndex) : null;
        }
        return (childIndex >= 0 && childIndex < children.size())
                ? children.get(childIndex) : null;
    }

    // ---- hit testing (AOSP :3038-3069) ----

    /** Mac.apk DEVIATION -- LOAD-BEARING, read before touching it.
     *
     *  A child that was never laid out (TRULY zero-size: BOTH width and height
     *  degenerate, e.g. [0,0-0,0]) is treated as filling the parent, so a
     *  fullscreen SurfaceView / GLSurfaceView that Mac.apk's layout pass never
     *  sized still receives touch. cocos2d-x and Unity put their surface under a
     *  FrameLayout and render natively without a Java layout pass ever running,
     *  so without this every tap in those games would be swallowed by the
     *  parent. AOSP has no equivalent -- there, pointInView on a 0x0 view is
     *  `localX < 0` and can never be true, so the child would be untappable.
     *
     *  v1.0.448: the test is `&&` (both dimensions degenerate), NOT `||`. An
     *  INTENTIONALLY-collapsed view has ONE real dimension and a real position
     *  -- e.g. Calculator's collapsed scientific pad is [0,717-836,717] (width
     *  836, height 0). With `||` every tap in the whitespace above the keypad
     *  "hit" that 0-height pad and descended into its 0-size fun_10pow button,
     *  inserting a phantom "10^". */
    //
    //  ⚠ v1.0.1638: DEFAULT OFF, and it was eating real input. The premise
    //  ("cocos2d-x and Unity surfaces are never laid out") is stale: measured
    //  with MACAPK_HIT_TRACE on GD Lite, HCR 1.8.1 and Subway, every tap reaches
    //  a surface with REAL bounds (Cocos2dxGLSurfaceView [0,0-3360,1796], Unity
    //  f [0,0-808,1796]) -- SurfaceView sizes itself and the driver lays it out
    //  -- and no tap relied on this. What it DID do: Flappy Bird stacks a 0x0
    //  AdMob AdView holding a 0x0 GestureOverlayView ABOVE its RenderSurfaceView,
    //  this carve-out routed every tap into that ad subtree, GestureOverlayView
    //  (AOSP-faithful) consumed it, and the game never saw a touch -- broken
    //  since the day it first rendered. AOSP: pointInView on a 0x0 view is never
    //  true. MACAPK_HIT_ZERO_FILLS=1 restores the old carve-out (A/B only).
    static final boolean MACAPK_HIT_ZERO_FILLS =
        "1".equals(System.getenv("MACAPK_HIT_ZERO_FILLS"));

    private static boolean macapkChildFillsParent(View c) {
        return MACAPK_HIT_ZERO_FILLS
            && c.getRight() <= c.getLeft() && c.getBottom() <= c.getTop();
    }

    /** The DELIVERY-side companion to macapkChildFillsParent: a child with
     *  EITHER dimension degenerate is dispatched to un-offset, preserving the
     *  legacy behaviour that fed full-window coordinates to game SurfaceViews.
     *  Kept as `||` verbatim from the pre-port code. In reachable states the two
     *  predicates coincide (a child that is degenerate in only one dimension can
     *  never pass the hit test, so it can never become a delivery target), but
     *  the weaker delivery test is preserved exactly rather than unified, so
     *  this port cannot change what a game surface receives. */
    private static boolean macapkSkipOffset(View c) {
        // Off with the carve-out (v1.0.1638): a degenerate child can no longer
        // pass the hit test, and AOSP always offsets into the child.
        return MACAPK_HIT_ZERO_FILLS
            && (c.getRight() <= c.getLeft() || c.getBottom() <= c.getTop());
    }

    /** AOSP View.java:17987-17990 pointInView(localX, localY, 0):
     *      localX >= -slop && localY >= -slop
     *          && localX < ((mRight - mLeft) + slop) && localY < ((mBottom - mTop) + slop)
     *  The RIGHT and BOTTOM edges are OUTSIDE. Inlined because View does not
     *  expose pointInView yet (View.java dependency note in the report). */
    private static boolean macapkPointInView(View v, float localX, float localY) {
        return localX >= 0 && localY >= 0
            && localX < (v.getRight() - v.getLeft())
            && localY < (v.getBottom() - v.getTop());
    }

    // ---- drag and drop (AOSP android14 ViewGroup.java :1690-1897) ----------
    // Ported line for line; the only divergence is that the children array is
    // our `children` list. See MacApkDragSession for where the events come from.
    private View mCurrentDragChild;
    private DragEvent mCurrentDragStartEvent;
    private java.util.HashSet<View> mChildrenInterestedInDrag;
    private boolean mIsInterestedInDrag;
    private android.graphics.PointF mMacapkDragLocalPoint;
    /** AOSP ViewGroup.getLocalPoint (android14): allocated lazily, so a group
     *  that never sees a drag pays nothing. */
    private android.graphics.PointF getMacapkDragLocalPoint() {
        if (mMacapkDragLocalPoint == null) mMacapkDragLocalPoint = new android.graphics.PointF();
        return mMacapkDragLocalPoint;
    }

    @Override
    boolean dispatchDragEnterExitInPreN(DragEvent event) {
        if (event.mAction == DragEvent.ACTION_DRAG_EXITED && mCurrentDragChild != null) {
            mCurrentDragChild.dispatchDragEnterExitInPreN(event);
            mCurrentDragChild = null;
        }
        return mIsInterestedInDrag && super.dispatchDragEnterExitInPreN(event);
    }

    @Override
    public boolean dispatchDragEvent(DragEvent event) {
        boolean retval = false;
        final float tx = event.mX;
        final float ty = event.mY;
        final android.content.ClipData td = event.mClipData;
        final android.graphics.PointF localPoint = getMacapkDragLocalPoint();
        switch (event.mAction) {
        case DragEvent.ACTION_DRAG_STARTED: {
            mCurrentDragChild = null;
            mCurrentDragStartEvent = DragEvent.obtain(event);
            if (mChildrenInterestedInDrag == null) {
                mChildrenInterestedInDrag = new java.util.HashSet<View>();
            } else {
                mChildrenInterestedInDrag.clear();
            }
            final View[] kids = children.toArray(new View[0]);
            for (View child : kids) {
                child.mMacapkDragCanAccept = false;
                child.mMacapkDragHovered = false;
                if (child.getVisibility() == VISIBLE) {
                    if (notifyChildOfDragStart(child)) retval = true;
                }
            }
            mIsInterestedInDrag = super.dispatchDragEvent(event);
            if (mIsInterestedInDrag) retval = true;
            if (!retval) {
                mCurrentDragStartEvent = null;
            }
        } break;
        case DragEvent.ACTION_DRAG_ENDED: {
            final java.util.HashSet<View> childrenInterestedInDrag = mChildrenInterestedInDrag;
            if (childrenInterestedInDrag != null) {
                for (View child : childrenInterestedInDrag.toArray(new View[0])) {
                    if (child.dispatchDragEvent(event)) retval = true;
                }
                childrenInterestedInDrag.clear();
            }
            mCurrentDragStartEvent = null;
            if (mIsInterestedInDrag) {
                if (super.dispatchDragEvent(event)) retval = true;
                mIsInterestedInDrag = false;
            }
        } break;
        case DragEvent.ACTION_DRAG_LOCATION:
        case DragEvent.ACTION_DROP: {
            View target = findFrontmostDroppableChildAt(event.mX, event.mY, localPoint);
            if (target != mCurrentDragChild) {
                if (sCascadedDragDrop) {
                    final int action = event.mAction;
                    event.mX = 0;
                    event.mY = 0;
                    event.mClipData = null;
                    if (mCurrentDragChild != null) {
                        event.mAction = DragEvent.ACTION_DRAG_EXITED;
                        mCurrentDragChild.dispatchDragEnterExitInPreN(event);
                    }
                    if (target != null) {
                        event.mAction = DragEvent.ACTION_DRAG_ENTERED;
                        target.dispatchDragEnterExitInPreN(event);
                    }
                    event.mAction = action;
                    event.mX = tx;
                    event.mY = ty;
                    event.mClipData = td;
                }
                mCurrentDragChild = target;
            }
            if (target == null && mIsInterestedInDrag) {
                target = this;
            }
            if (target != null) {
                if (target != this) {
                    event.mX = localPoint.x;
                    event.mY = localPoint.y;
                    retval = target.dispatchDragEvent(event);
                    event.mX = tx;
                    event.mY = ty;
                    if (mIsInterestedInDrag) {
                        final boolean eventWasConsumed = sCascadedDragDrop
                                ? retval : event.mEventHandlerWasCalled;
                        if (!eventWasConsumed) retval = super.dispatchDragEvent(event);
                    }
                } else {
                    retval = super.dispatchDragEvent(event);
                }
            }
        } break;
        default:
            break;
        }
        return retval;
    }

    View findFrontmostDroppableChildAt(float x, float y, android.graphics.PointF outLocalPoint) {
        for (int i = children.size() - 1; i >= 0; i--) {
            final View child = children.get(i);
            if (!child.canAcceptDrag()) continue;
            if (isTransformedTouchPointInView(x, y, child, outLocalPoint)) return child;
        }
        return null;
    }

    boolean notifyChildOfDragStart(View child) {
        final float tx = mCurrentDragStartEvent.mX;
        final float ty = mCurrentDragStartEvent.mY;
        final float[] point = new float[]{ tx, ty };
        transformPointToViewLocal(point, child);
        mCurrentDragStartEvent.mX = point[0];
        mCurrentDragStartEvent.mY = point[1];
        final boolean canAccept = child.dispatchDragEvent(mCurrentDragStartEvent);
        mCurrentDragStartEvent.mX = tx;
        mCurrentDragStartEvent.mY = ty;
        mCurrentDragStartEvent.mEventHandlerWasCalled = false;
        if (canAccept) {
            mChildrenInterestedInDrag.add(child);
            if (!child.canAcceptDrag()) {
                child.mMacapkDragCanAccept = true;
                child.refreshDrawableState();
            }
        }
        return canAccept;
    }

    // ---- mid-drag child bookkeeping (android14 ViewGroup.java) --------------
    /** addViewInner :5353: "if (mCurrentDragStartEvent != null &&
     *  child.getVisibility() == VISIBLE) { notifyChildOfDragStart(child); }" --
     *  a view added during a drag (a RecyclerView row bound while dragging) is
     *  told STARTED so it can be a drop target. */
    final void macapkDragChildAdded(View child) {
        // GATE-MUTANT(touchinput:drag-hooks) begin -- debug/touchinput_gate.sh C5
        if (mCurrentDragStartEvent != null && child.getVisibility() == VISIBLE) {
            notifyChildOfDragStart(child);
        }
        // GATE-MUTANT(touchinput:drag-hooks) end
    }

    /** onChildVisibilityChanged :1597-1602, "in all cases, for drags":
     *  "if (newVisibility == VISIBLE && mCurrentDragStartEvent != null) { if
     *  (!mChildrenInterestedInDrag.contains(child)) { notifyChildOfDragStart(child); } }"
     *  -- a drop zone revealed mid-drag gets STARTED. Called by
     *  View.setVisibility (this framework has no onChildVisibilityChanged). */
    final void macapkDragChildVisibilityChanged(View child, int newVisibility) {
        // GATE-MUTANT(touchinput:drag-hooks) begin
        if (newVisibility == VISIBLE && mCurrentDragStartEvent != null) {
            if (!mChildrenInterestedInDrag.contains(child)) {
                notifyChildOfDragStart(child);
            }
        }
        // GATE-MUTANT(touchinput:drag-hooks) end
    }

    /** removeViewInternal(int, View) :5639: "if (mCurrentDragStartEvent != null)
     *  { mChildrenInterestedInDrag.remove(view); }" -- a removed view is not
     *  sent ENDED. AOSP does this in removeViewInternal only (removeView,
     *  removeViewAt, removeViewInLayout); the bulk removeViews /
     *  removeAllViews paths do not touch the set, and neither do we. */
    final void macapkDragChildRemoved(View child) {
        // GATE-MUTANT(touchinput:drag-hooks) begin
        if (mCurrentDragStartEvent != null && mChildrenInterestedInDrag != null) {
            mChildrenInterestedInDrag.remove(child);
        }
        // GATE-MUTANT(touchinput:drag-hooks) end
    }

    /** AOSP :3045-3056. */
    protected boolean isTransformedTouchPointInView(float x, float y, View child,
                                                    android.graphics.PointF outLocalPoint) {
        if (macapkChildFillsParent(child)) {
            // See macapkChildFillsParent: the unlaid-out game surface. It gets
            // the point unchanged, which is also what the delivery side does.
            if (outLocalPoint != null) outLocalPoint.set(x, y);
            return true;
        }
        final float[] point = new float[]{ x, y };
        if (!macapkTransformPointToViewLocal(point, child)) return false;
        final boolean isInView = macapkPointInView(child, point[0], point[1]);
        if (isInView && outLocalPoint != null) outLocalPoint.set(point[0], point[1]);
        return isInView;
    }

    /** AOSP :3061-3069:
     *      point[0] += mScrollX - child.mLeft;
     *      point[1] += mScrollY - child.mTop;
     *      if (!child.hasIdentityMatrix()) child.getInverseMatrix().mapPoints(point);
     *  Note the SCROLL term: hit testing and delivery must agree about it, and
     *  before this port hit testing omitted it while delivery applied it, so a
     *  scrolled container's children were tappable at the wrong place. */
    public void transformPointToViewLocal(float[] point, View child) {
        macapkTransformPointToViewLocal(point, child);
    }

    /** @return false when the child's matrix is not invertible -- a zero scale
     *  collapses the view to a point or a line, so no touch point can be inside
     *  it. AOSP has no equivalent check because its native matrix inverse is
     *  unchecked and simply yields garbage. */
    private boolean macapkTransformPointToViewLocal(float[] point, View child) {
        point[0] += getScrollX() - child.getLeft();
        point[1] += getScrollY() - child.getTop();
        if (!child.macapkHasTransform()) return true;
        android.graphics.Matrix inv = macapkChildInverseMatrix(child);
        if (inv == null) return false;
        inv.mapPoints(point);
        return true;
    }

    // ---- state propagation to children (AOSP ViewGroup:4600-4640) ----
    //
    // View.setSelected/setActivated/setPressed call these hooks; on a plain View
    // they are no-ops, and a ViewGroup overrides them to walk its children. We
    // had the hooks but not the overrides, so a selected/pressed container never
    // told its children -- which is how a list row highlights its label and icon.

    @Override
    protected void dispatchSetSelected(boolean selected) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) getChildAt(i).setSelected(selected);
    }

    @Override
    protected void dispatchSetActivated(boolean activated) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) getChildAt(i).setActivated(activated);
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            // AOSP ViewGroup:4625, and the asymmetry is deliberate: a child that
            // is clickable in its own right must NOT show a pressed state just
            // because its parent is pressed, but CLEARING a pressed state always
            // propagates -- otherwise a child left pressed by its own touch never
            // gets un-pressed when the parent's gesture ends.
            if (!pressed || (!child.isClickable() && !child.isLongClickable())) {
                child.setPressed(pressed);
            }
        }
    }

    /** The child's render matrix: view-local coordinates -> THIS group's
     *  coordinate space (before the left/top offset).
     *
     *  Now a straight delegation to the child, which is what AOSP does
     *  (`ViewGroup.isTransformedTouchPointInView` asks `child.hasIdentityMatrix()`
     *  / `getInverseMatrix()`). This used to compose the matrix HERE from the
     *  properties View exposes, because `View.getMatrix()` returned a fresh
     *  IDENTITY for every view and routing through it would have been a no-op.
     *  View grew a real matrix (v1.0.924, composition taken verbatim from
     *  libs/hwui/RenderProperties.cpp:179-195), so the local copy is retired:
     *  two implementations of one transform is precisely the shape that lets
     *  touch and paint drift apart.
     *
     *  The order is still pinned by the golden -- DISP.coord.translateAndScaleHit
     *  (translationX=+50, scaleX=0.5, default centre pivot, tap at parent x=190)
     *  puts the delivered local x at exactly 30.0, and only translate-after-
     *  rotate/scale-about-pivot produces that. The lane that landed View's
     *  matrix re-derived that pin through the full ViewGroup step order before
     *  this delegation was made. */
    private static android.graphics.Matrix macapkChildMatrix(View child) {
        return child.getMatrix();
    }

    /** The inverse, or NULL when the child's matrix is not invertible.
     *
     *  That null contract is load-bearing and is NOT "the matrix is identity":
     *  isTransformedTouchPointInView reads a null here as "the point cannot be
     *  mapped into the child, so it is NOT inside it" and returns false. A
     *  version that returned null for identity would make a child with a
     *  degenerate-but-flagged transform untappable, and would never fire the
     *  non-invertible guard at all. Callers reach this only after
     *  macapkHasTransform(), so the identity case does not arrive here anyway. */
    private static android.graphics.Matrix macapkChildInverseMatrix(View child) {
        android.graphics.Matrix inv = new android.graphics.Matrix();
        return child.getMatrix().invert(inv) ? inv : null;
    }

    // ---- event transformation + delivery (AOSP :3071-3146) ----

    /** AOSP :3076-3146. Transforms the event into the child's coordinate space,
     *  filters out pointers the target does not own, and overrides the action
     *  when required. `child == null` means "deliver to THIS group's own View
     *  machinery" (super.dispatchTouchEvent).
     *
     *  The cancel short-circuit at the top is the single easiest thing in this
     *  method to get wrong: AOSP does NOT offsetLocation and does NOT apply the
     *  matrix for a cancel, so the child sees PARENT-space coordinates. That is
     *  deliberate -- "the important part is the action, not the contents" -- and
     *  it is why a nested cancel shows the ROOT's coordinates at every level. */
    private boolean dispatchTransformedTouchEvent(MotionEvent event, boolean cancel,
            View child, int desiredPointerIdBits) {
        boolean handled;
        final int oldAction = event.getAction();
        if (cancel || oldAction == MotionEvent.ACTION_CANCEL) {
            event.setAction(MotionEvent.ACTION_CANCEL);
            try {
                handled = (child == null) ? super.dispatchTouchEvent(event)
                                          : child.dispatchTouchEvent(event);
            } finally {
                event.setAction(oldAction);
            }
            return handled;
        }

        final int oldPointerIdBits = macapkPointerIdBits(event);
        final int newPointerIdBits = oldPointerIdBits & desiredPointerIdBits;
        // Inconsistent state that would produce a pointer-less event: drop it.
        if (newPointerIdBits == 0) return false;

        final MotionEvent transformedEvent;
        if (newPointerIdBits == oldPointerIdBits) {
            if (child == null || !child.macapkHasTransform()) {
                if (child == null) return super.dispatchTouchEvent(event);
                if (macapkSkipOffset(child)) return child.dispatchTouchEvent(event);
                final float offsetX = getScrollX() - child.getLeft();
                final float offsetY = getScrollY() - child.getTop();
                event.offsetLocation(offsetX, offsetY);
                // try/finally, unlike AOSP: a child that throws must not leave
                // the SHARED event offset for its siblings and its parent.
                try { return child.dispatchTouchEvent(event); }
                finally { event.offsetLocation(-offsetX, -offsetY); }
            }
            transformedEvent = MotionEvent.obtain(event);
        } else {
            transformedEvent = macapkSplit(event, newPointerIdBits);
            if (transformedEvent == null) return false;
        }

        try {
            if (child == null) {
                handled = super.dispatchTouchEvent(transformedEvent);
            } else {
                if (!macapkSkipOffset(child)) {
                    transformedEvent.offsetLocation(getScrollX() - child.getLeft(),
                                                    getScrollY() - child.getTop());
                }
                if (child.macapkHasTransform()) {
                    android.graphics.Matrix inv = macapkChildInverseMatrix(child);
                    if (inv != null) transformedEvent.transform(inv);
                }
                handled = child.dispatchTouchEvent(transformedEvent);
            }
        } finally {
            transformedEvent.recycle();
        }
        return handled;
    }

    /** AOSP MotionEvent.getPointerIdBits() -- a mask of the pointer IDS present
     *  in the event. Computed here because MotionEvent does not expose it. */
    private static int macapkPointerIdBits(MotionEvent ev) {
        int bits = 0;
        final int n = ev.getPointerCount();
        for (int i = 0; i < n; i++) bits |= 1 << ev.getPointerId(i);
        return bits;
    }

    /** AOSP MotionEvent.split(int idBits) (MotionEvent.java:3471-3488), which
     *  does not exist on our MotionEvent, reimplemented on top of the public
     *  multi-pointer obtain(). Keeps only the pointers whose IDS are in idBits
     *  and REMAPS the action, which is the part that makes split touch look
     *  right to a child: a POINTER_DOWN that keeps only the NEW pointer becomes
     *  ACTION_DOWN, one that keeps only the OTHER pointers becomes ACTION_MOVE,
     *  and a POINTER_DOWN that keeps several has its action INDEX renumbered
     *  into the reduced pointer array.
     *
     *  RAW COORDINATES. AOSP's split preserves the event's native raw transform.
     *  Our obtain() sets rawX/rawY from the PointerCoords it is handed, so a
     *  naive port would make getRawX() equal the CURRENT (already offset) local
     *  x. We recover it whenever the event has only been rigidly offset since it
     *  was created -- every pointer sharing one (x - rawX, y - rawY) delta --
     *  by seeding the coords with the RAW values and re-applying that delta with
     *  offsetLocation(), which moves x/y and leaves raw alone. When the deltas
     *  differ (an ancestor applied a MATRIX, so the local space is not a
     *  translation of the raw space) there is no way to express it through the
     *  public API and raw ends up equal to local. That residue is why
     *  MotionEvent.split(int) is still requested from the input lane.
     *
     *  @return null if no pointer survived (AOSP throws IllegalArgumentException;
     *  the sole caller has already screened that out). */
    private static MotionEvent macapkSplit(MotionEvent ev, int idBits) {
        final int oldPointerCount = ev.getPointerCount();
        final int oldAction = ev.getAction();
        final int oldActionMasked = oldAction & MotionEvent.ACTION_MASK;
        final int oldActionPointerIndex =
                (oldAction & MotionEvent.ACTION_POINTER_INDEX_MASK)
                        >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;

        final int[] map = new int[oldPointerCount];
        int newActionPointerIndex = -1;
        int newPointerCount = 0;
        for (int i = 0; i < oldPointerCount; i++) {
            if (((1 << ev.getPointerId(i)) & idBits) == 0) continue;
            if (i == oldActionPointerIndex) newActionPointerIndex = newPointerCount;
            map[newPointerCount++] = i;
        }
        if (newPointerCount == 0) return null;

        final int newAction;
        if (oldActionMasked == MotionEvent.ACTION_POINTER_DOWN
                || oldActionMasked == MotionEvent.ACTION_POINTER_UP) {
            if (newActionPointerIndex < 0) {
                newAction = MotionEvent.ACTION_MOVE;          // an unrelated pointer changed
            } else if (newPointerCount == 1) {
                newAction = (oldActionMasked == MotionEvent.ACTION_POINTER_DOWN)
                        ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
            } else {
                newAction = oldActionMasked
                        | (newActionPointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        } else {
            newAction = oldAction;   // simple down/up/cancel/move
        }

        final MotionEvent.PointerProperties[] pp =
                new MotionEvent.PointerProperties[newPointerCount];
        final MotionEvent.PointerCoords[] pc =
                new MotionEvent.PointerCoords[newPointerCount];
        final float dx = ev.getX(map[0]) - ev.getRawX(map[0]);
        final float dy = ev.getY(map[0]) - ev.getRawY(map[0]);
        boolean rigidOffset = true;
        for (int i = 0; i < newPointerCount; i++) {
            pp[i] = new MotionEvent.PointerProperties();
            ev.getPointerProperties(map[i], pp[i]);
            pc[i] = new MotionEvent.PointerCoords();
            ev.getPointerCoords(map[i], pc[i]);
            if (ev.getX(map[i]) - ev.getRawX(map[i]) != dx
                    || ev.getY(map[i]) - ev.getRawY(map[i]) != dy) {
                rigidOffset = false;
            }
        }
        if (rigidOffset) {
            for (int i = 0; i < newPointerCount; i++) {
                pc[i].x = ev.getRawX(map[i]);
                pc[i].y = ev.getRawY(map[i]);
            }
        }
        MotionEvent split = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), newAction,
                newPointerCount, pp, pc, ev.getMetaState(), ev.getButtonState(),
                ev.getXPrecision(), ev.getYPrecision(), ev.getDeviceId(), ev.getEdgeFlags(),
                ev.getSource(), ev.getFlags());
        if (rigidOffset && (dx != 0f || dy != 0f)) split.offsetLocation(dx, dy);
        return split;
    }

    /** AOSP ViewGroup.dispatchApplyWindowInsets: call super (View's
     *  version, which invokes the listener / onApplyWindowInsets on
     *  THIS view), then if the bag isn't consumed, cascade depth-first
     *  to each child. The first consumed return short-circuits sibling
     *  dispatch -- matching AOSP frameworks/base ViewGroup.java.
     *
     *  Generic across every APK: any ViewGroup-rooted view tree now
     *  receives WindowInsets properly. Material's BottomNavigationView
     *  (FrameLayout subclass via NavigationBarView) gets its listener
     *  invoked on the first dispatch and adds inset.bottom to its
     *  paddingBottom -- which is what makes the nav bar visually
     *  grow to its M3-spec 80dp+inset height. */
    @Override public android.view.WindowInsets dispatchApplyWindowInsets(android.view.WindowInsets insets) {
        if (insets == null) return null;
        insets = super.dispatchApplyWindowInsets(insets);
        if (insets == null || insets.isConsumed()) return insets;
        // AOSP splits here on View.sBrokenInsetsDispatch (android14-release
        // ViewGroup.java:7319-7346). This tree was unconditionally the BROKEN
        // half; 51 of 62 corpus apps target 30+ and are entitled to the NEW one.
        return View.macapkBrokenInsetsDispatch(getContext())
                ? macapkBrokenDispatchApplyWindowInsets(insets)
                : macapkNewDispatchApplyWindowInsets(insets);
    }

    /** targetSdk < 30. The MODIFIED bag threads from one child into the next,
     *  and the first child to consume stops its siblings receiving anything.
     *  AOSP calls this "broken" in its own identifier and keeps it only so apps
     *  written against it do not change behaviour. */
    private android.view.WindowInsets macapkBrokenDispatchApplyWindowInsets(
            android.view.WindowInsets insets) {
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c == null) continue;
            // NOT AOSP: AOSP dispatches to GONE children too. Preserved from the
            // previous implementation deliberately -- removing it is an
            // independent behaviour change and must be measured on its own, not
            // smuggled in with the broken/new split.
            if (c.getVisibility() == View.GONE) continue;
            insets = c.dispatchApplyWindowInsets(insets);
            if (insets == null || insets.isConsumed()) break;
        }
        return insets;
    }

    /** targetSdk >= 30. Every child receives the SAME bag, no child can starve
     *  a sibling, and the ORIGINAL bag is returned regardless of what any child
     *  did with it. */
    private android.view.WindowInsets macapkNewDispatchApplyWindowInsets(
            android.view.WindowInsets insets) {
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c == null) continue;
            if (c.getVisibility() == View.GONE) continue;
            c.dispatchApplyWindowInsets(insets);
        }
        return insets;
    }

    public LayoutParams generateLayoutParams(android.util.AttributeSet attrs) { return new LayoutParams(); }
    /** AOSP ViewGroup.generateLayoutParams(ViewGroup.LayoutParams p): convert a
     *  FOREIGN LayoutParams into one this group understands. AOSP's base body is
     *  exactly `return p;` -- every real container (LinearLayout, FrameLayout,
     *  Toolbar, GridLayout, ActionMenuView) overrides it to copy the incoming
     *  width/height plus its own margins / gravity / weight.
     *
     *  The base method was ENTIRELY ABSENT, which is worse than an API gap: the
     *  android.widget overrides that already exist could never be dispatched to,
     *  because addView() had no base method to call virtually. Declaring it is
     *  what makes those overrides reachable at all. */
    protected LayoutParams generateLayoutParams(LayoutParams p) { return p; }
    /** AOSP ViewGroup.generateDefaultLayoutParams: WRAP_CONTENT both ways. It
     *  returned the no-arg LayoutParams, which is 0x0, so a child added with a
     *  plain addView(view) to a ViewGroup that does not override this measured
     *  to nothing: WhatsApp adds its reactions container to the message row
     *  that way, and no reaction was ever shown under a message. */
    public LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
    /** AOSP ViewGroup.checkLayoutParams: `return p != null;` -- each container
     *  overrides it with `p instanceof Its.LayoutParams`, which is what makes
     *  addView convert a foreign LayoutParams (v1.0.1643; it answered true for
     *  everything, so e.g. Wikipedia's History cards kept a plain
     *  ViewGroup.LayoutParams inside a LinearLayout and the app's own cast to
     *  LinearLayout.LayoutParams threw). MACAPK_CHECK_LAYOUT_PARAMS=0 restores
     *  the old answer for an A/B. */
    protected boolean checkLayoutParams(LayoutParams p) { return MACAPK_CHECK_LP ? p != null : true; }
    private static final boolean MACAPK_CHECK_LP =
        !"0".equals(System.getenv("MACAPK_CHECK_LAYOUT_PARAMS"));

    // v1.0.121: AOSP ViewGroup flags FLAG_CLIP_CHILDREN /
    // FLAG_CLIP_TO_PADDING. Both default-on per
    // frameworks/base/core/java/android/view/ViewGroup.java:715-716.
    // The dispatchDraw path reads clipChildren so we can keep the new
    // per-child clipRect on by default and let Material widgets opt
    // out (a few do, to let shadows / ripples paint outside the
    // bounds box -- though our current Material chrome approximation
    // doesn't depend on that yet).
    protected boolean clipChildren = true;
    protected boolean clipToPadding = true;
    public void setClipChildren(boolean b) { this.clipChildren = b; }
    public boolean getClipChildren()       { return clipChildren; }
    public void setClipToPadding(boolean b) { this.clipToPadding = b; }
    public boolean getClipToPadding()       { return clipToPadding; }

    /** v1.0.76: minimal onLayout. Real Android-side ViewGroups
     *  (LinearLayout, FrameLayout, ConstraintLayout) each override
     *  onLayout to position children per their layout-specific
     *  semantics. For the BASE ViewGroup default we apply a heuristic:
     *  if only one child, fill the parent (FrameLayout-style); if many,
     *  stack vertically with equal heights (LinearLayout-vertical
     *  default). Children already positioned to a non-zero rect (by a
     *  subclass override that ran first) are left alone. */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        boolean anyLayoutRequested = false;
        int needsLayout = 0;
        int visibleCount = 0;
        for (int i = 0; i < children.size(); ++i) {
            View c = children.get(i);
            if (c == null) continue;
            if (c.getVisibility() == View.GONE) continue;
            visibleCount++;
            if (c.isLayoutRequested()) anyLayoutRequested = true;
            if (c.getRight() > c.getLeft() && c.getBottom() > c.getTop()) continue;
            needsLayout++;
        }
        // Snap GONE children to zero extent so the recursive walks +
        // dispatchDraw skip them naturally.
        for (int i = 0; i < children.size(); ++i) {
            View c = children.get(i);
            if (c == null) continue;
            if (c.getVisibility() == View.GONE
                && (c.getRight() != c.getLeft() || c.getBottom() != c.getTop())) {
                c.layout(0, 0, 0, 0);
            }
        }
        // v1.0.117: detect "GONE collapse happened since last layout" by
        // looking for gaps in the y-positions of visible children
        // (sibling at y > 0 with no preceding visible sibling claiming
        // that y range). If detected, reset visible children's bounds
        // to force re-equal-divide.
        if (visibleCount > 1) {
            // Build a simple "expected positions" check.
            int maxYOfVisible = 0;
            int sumHOfVisible = 0;
            for (int i = 0; i < children.size(); ++i) {
                View c = children.get(i);
                if (c == null || c.getVisibility() == View.GONE) continue;
                int cy = c.getTop();
                int ch = c.getBottom() - c.getTop();
                if (ch > 0) {
                    sumHOfVisible += ch;
                    if (cy + ch > maxYOfVisible) maxYOfVisible = cy + ch;
                }
            }
            // If the max-Y of visible children exceeds the sum of
            // their heights (i.e. there's an empty gap between them),
            // a GONE sibling probably took a slot that no longer exists.
            // Reset and re-equal-divide.
            if (maxYOfVisible > sumHOfVisible + (visibleCount * 4)) {
                // Diagnostic when env-gated
                if ("1".equals(ENV_MACAPK_TRACE_GAP_RELAYOUT)) {
                    try {
                        com.macapk.loader.NativeLog.i("ViewGroup",
                            "GAP-RELAYOUT " + getClass().getSimpleName()
                            + "@" + Integer.toHexString(System.identityHashCode(this))
                            + " visibleCount=" + visibleCount
                            + " sumH=" + sumHOfVisible + " maxY=" + maxYOfVisible);
                    } catch (Throwable ignored) {}
                }
                for (int i = 0; i < children.size(); ++i) {
                    View c = children.get(i);
                    if (c == null || c.getVisibility() == View.GONE) continue;
                    c.layout(0, 0, 0, 0);
                }
                needsLayout = visibleCount;
                anyLayoutRequested = true;
            }
        }
        if (anyLayoutRequested && visibleCount > 0 && needsLayout == 0) {
            for (int i = 0; i < children.size(); ++i) {
                View c = children.get(i);
                if (c == null || c.getVisibility() == View.GONE) continue;
                c.layout(0, 0, 0, 0);
            }
            needsLayout = visibleCount;
        }
        // v1.0.124: AOSP-correct re-layout pass. The previous early-
        // return on `needsLayout == 0` was the load-bearing reason RV's
        // natural dispatch flow never worked:
        // RecyclerView.onLayout calls dispatchLayout which runs
        // dispatchLayoutStep1+Step2 (LayoutManager.onLayoutChildren) --
        // but only when RV.layout(...) fires onLayout on it. With the
        // skip, after the first layout pass (where RV got positioned
        // but had no adapter yet), RV's onLayout NEVER fired again
        // because we returned early. By the time setAdapter ran, the
        // requestLayout flag had no path to a re-traversal.
        //
        // The AOSP-correct fix: always re-fire `c.layout(...)` on every
        // visible child. View.layout always invokes onLayout (true), so
        // already-positioned children's subclass onLayouts re-run with
        // the same bounds (idempotent for LinearLayout / FrameLayout /
        // CL). For RV specifically, this is the trigger that lets
        // dispatchLayout run step1+step2 -> LayoutManager.onLayoutChildren
        // -> fill() viewport gate at LinearLayoutManager.java:1678
        // (`while ((mInfinite || remainingSpace > 0) && layoutState.hasMore(state))`)
        // which attaches only the visible-window items via
        // mChildHelper -> RecyclerView.this.addView. Per the research
        // (cs.android.com / github.com/androidx/androidx):
        // dispatchLayout has a fast no-op path (RecyclerView.java:
        // 4598-4602) when mState.mLayoutStep==STEP_START && no adapter
        // updates && dimensions match, so the per-frame cost is cheap.
        if (needsLayout == 0) {
            // All children positioned. Re-fire layout with same bounds
            // so onLayout cascades into subclasses (RV.dispatchLayout
            // gets to do its STEP_START no-op or actual step1+step2).
            for (int i = 0; i < children.size(); ++i) {
                View c = children.get(i);
                if (c == null) continue;
                if (c.getVisibility() == View.GONE) continue;
                // v1.0.124 diagnostic: trace when we re-fire layout on
                // RecyclerView so we can confirm RV.onLayout->dispatchLayout
                // is being driven by our natural cascade.
                if ("1".equals(ENV_MACAPK_TRACE_RV_LAYOUT)) {
                    String cn;
                    try { cn = c.getClass().getName(); } catch (Throwable th) { cn = ""; }
                    if (cn.endsWith(".RecyclerView")) {
                        com.macapk.loader.NativeLog.i("VG",
                            "RV-onLayout-fire " + cn + "@"
                            + Integer.toHexString(System.identityHashCode(c))
                            + " bounds=[" + c.getLeft() + "," + c.getTop()
                            + "-" + c.getRight() + "," + c.getBottom() + "]");
                    }
                }
                c.layout(c.getLeft(), c.getTop(), c.getRight(), c.getBottom());
            }
            return;
        }
        if (needsLayout == 1 || visibleCount == 1) {
            // FrameLayout-style: single child fills parent.
            for (int i = 0; i < children.size(); ++i) {
                View c = children.get(i);
                if (c == null) continue;
                if (c.getVisibility() == View.GONE) continue;
                if (c.getRight() > c.getLeft() && c.getBottom() > c.getTop()) {
                    // Re-fire to cascade onLayout.
                    c.layout(c.getLeft(), c.getTop(), c.getRight(), c.getBottom());
                    continue;
                }
                c.layout(0, 0, w, h);
            }
            return;
        }
        // LinearLayout-vertical-style: stack with equal heights, skipping GONE.
        // Use visibleCount (not needsLayout) when re-layout has been
        // forced so siblings re-equal-divide consistently.
        int divisor = (anyLayoutRequested) ? visibleCount : needsLayout;
        int sliceH = h / Math.max(1, divisor);
        int idx = 0;
        for (int i = 0; i < children.size(); ++i) {
            View c = children.get(i);
            if (c == null) continue;
            if (c.getVisibility() == View.GONE) continue;
            // If we forced re-layout, all visible children get a fresh
            // slot. Otherwise only un-laid-out children get one.
            if (!anyLayoutRequested
                && c.getRight() > c.getLeft() && c.getBottom() > c.getTop()) {
                // Already positioned -- re-fire layout with same bounds
                // so onLayout cascades into subclasses (critical for RV).
                c.layout(c.getLeft(), c.getTop(), c.getRight(), c.getBottom());
                idx++;
                continue;
            }
            int y = idx * sliceH;
            c.layout(0, y, w, y + sliceH);
            idx++;
        }
    }
    /** v1.0.115: AOSP-standard helper. Builds a child's MeasureSpec
     *  from the parent's spec + the child's LayoutParams width/height.
     *  ConstraintLayout's BasicMeasure path (Measurer.measure) calls
     *  through to measureChild → this helper; without it the solver
     *  has no way to ask "what's the measured width of this
     *  wrap_content TextView?" and every MATCH_CONSTRAINT child whose
     *  end-anchor depends on a wrap_content sibling collapses to W=0.
     *  AOSP source:
     *    https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/ViewGroup.java
     *    (search for "public static int getChildMeasureSpec") */
    public static int getChildMeasureSpec(int spec, int padding, int childDimension) {
        int specMode = MeasureSpec.getMode(spec);
        int specSize = MeasureSpec.getSize(spec);
        int size = Math.max(0, specSize - padding);
        int resultSize = 0;
        int resultMode = 0;
        switch (specMode) {
            case MeasureSpec.EXACTLY:
                if (childDimension >= 0) {
                    resultSize = childDimension;
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.MATCH_PARENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.AT_MOST;
                }
                break;
            case MeasureSpec.AT_MOST:
                if (childDimension >= 0) {
                    resultSize = childDimension;
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.MATCH_PARENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.AT_MOST;
                } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.AT_MOST;
                }
                break;
            case MeasureSpec.UNSPECIFIED:
                if (childDimension >= 0) {
                    resultSize = childDimension;
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.MATCH_PARENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.UNSPECIFIED;
                } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.UNSPECIFIED;
                }
                break;
        }
        return MeasureSpec.makeMeasureSpec(Math.max(0, resultSize), resultMode);
    }

    /** v1.0.115: AOSP-standard helper. Combines getChildMeasureSpec
     *  (above) with the child's LayoutParams + parent padding to issue
     *  a single measure() call on the child. Used by ConstraintLayout's
     *  measurer to size every wrap_content child. */
    protected void measureChild(View child, int parentWidthMeasureSpec, int parentHeightMeasureSpec) {
        if (child == null) return;
        LayoutParams lp = child.getLayoutParams();
        int lpW = (lp != null) ? lp.width  : LayoutParams.WRAP_CONTENT;
        int lpH = (lp != null) ? lp.height : LayoutParams.WRAP_CONTENT;
        int childWidthMeasureSpec  = getChildMeasureSpec(parentWidthMeasureSpec,
                getPaddingLeft() + getPaddingRight(),  lpW);
        int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec,
                getPaddingTop()  + getPaddingBottom(), lpH);
        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    /** v1.0.115: AOSP-standard helper. Same as measureChild but
     *  factors in any extra margin already known to the caller (used
     *  by RelativeLayout / ConstraintLayout when measuring children
     *  that have margins). */
    protected void measureChildWithMargins(View child,
                                            int parentWidthMeasureSpec, int widthUsed,
                                            int parentHeightMeasureSpec, int heightUsed) {
        if (child == null) return;
        LayoutParams lp = child.getLayoutParams();
        int lpW = (lp != null) ? lp.width  : LayoutParams.WRAP_CONTENT;
        int lpH = (lp != null) ? lp.height : LayoutParams.WRAP_CONTENT;
        int hMargin = 0, vMargin = 0;
        if (lp instanceof MarginLayoutParams mlp) {
            hMargin = mlp.leftMargin + mlp.rightMargin;
            vMargin = mlp.topMargin  + mlp.bottomMargin;
        }
        int childWidthMeasureSpec  = getChildMeasureSpec(parentWidthMeasureSpec,
                getPaddingLeft() + getPaddingRight()  + hMargin + widthUsed,  lpW);
        int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec,
                getPaddingTop()  + getPaddingBottom() + vMargin + heightUsed, lpH);
        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        // v1.0.107: propagate the parent's measure mode to children
        // when it's EXACTLY. ConstraintLayout's solver requires an
        // EXACTLY-mode parent measure to determine cell positions --
        // with AT_MOST, the solver bails into a no-constraint
        // fallback that lays children out by equal-divide (which is
        // what we see in F-Droid's cards: 4 children at parent/4
        // width each, ignoring constraintLeft_toLeftOf="parent" /
        // constraintRight_toRightOf="parent" etc.). For UNSPECIFIED
        // / AT_MOST parents, AT_MOST stays the right choice (wrap-
        // content semantic).
        int childMode = MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY
            ? MeasureSpec.EXACTLY
            : MeasureSpec.AT_MOST;
        int childHMode = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY
            ? MeasureSpec.EXACTLY
            : MeasureSpec.AT_MOST;
        for (int i = 0; i < children.size(); ++i) {
            View c = children.get(i);
            if (c == null) continue;
            c.measure(MeasureSpec.makeMeasureSpec(w, childMode),
                      MeasureSpec.makeMeasureSpec(h, childHMode));
        }
    }

    /** AOSP ViewGroup.onDescendantInvalidated(child, target)
     *  (frameworks/base core/java/android/view/ViewGroup.java:6062-6095) --
     *  the hardware-accelerated, Rect-free damage path. A descendant whose
     *  RenderNode properties changed (translation / alpha / scale / rotation,
     *  with NO content or layout change) reports the damage up the parent
     *  chain instead of re-recording anything; AOSP terminates that chain at
     *  ViewRootImpl.onDescendantInvalidated (ViewRootImpl.java:2563-2571),
     *  which calls invalidate() -> scheduleTraversals().
     *
     *  This chain was DEAD in Mac.apk: ViewParent declared only the no-op
     *  default and no ViewGroup ever overrode it. Compose's layer
     *  implementations (RenderNodeLayer and GraphicsLayerOwnerLayer, both via
     *  WrapperRenderNodeLayerHelperMethods.onDescendantInvalidated) take
     *  exactly this call for every graphicsLayer-property-only repaint when
     *  SDK_INT >= 26 -- which is every Modifier.animateItem placement,
     *  AnimatedVisibility alpha/scale, ripple and elevation motion, and
     *  scroll-linked graphicsLayer effect. With the chain dead, the M6 dirty
     *  gate never saw those invalidations, so the only thing repainting the
     *  window was ViewCanvasDriver's ~0.5s heartbeat backstop.
     *
     *  Mac.apk is a single-window model with no ViewRootImpl-shaped ViewParent
     *  terminating the chain (the content root's parent is null), so poke the
     *  schedule authority directly the way View.invalidate() does, AND still
     *  propagate upward so a subclass override -- or a real terminator, once
     *  one exists -- observes the damage the way AOSP's shape guarantees.
     *  Both halves are idempotent: schedule() sets a single AtomicBoolean. */
    @Override public void onDescendantInvalidated(View child, View target) {
        if (android.os.Looper.MACAPK_REAL_LOOPER) ViewRootImpl.schedule();
        ViewParent p = getParent();
        if (p != null) p.onDescendantInvalidated(child, target);
    }

    /** AOSP ViewGroup.invalidateChild (ViewGroup.java:6107-6113): deprecated
     *  in favor of onDescendantInvalidated, but older library code still calls
     *  it. Its hardware-accelerated branch is literally
     *  `onDescendantInvalidated(child, child); return;` before any of the
     *  software damage-rect bookkeeping, and Mac.apk composites every frame
     *  through ANGLE with no software-layer damage path to fall back to, so
     *  that branch is the whole method for us. */
    @Override public void invalidateChild(View child, Rect dirty) {
        if (child == null) return;
        onDescendantInvalidated(child, child);
    }

    /** Standard ViewGroup behaviour: dispatchDraw paints each child
     *  in the order they were added. Real Android translates by
     *  the child's left/top before invoking; we use the child's
     *  getLeft/getTop offsets (set by the layout pass above).
     *
     *  v1.0.121: implement AOSP's per-child clip per
     *  frameworks/base/core/java/android/view/View.java:23732-23744
     *  (the package-private View.draw(Canvas, ViewGroup, long) the
     *  hardware path calls). For each child:
     *    1. canvas.save()                         -- mark restore point
     *    2. canvas.translate(child.left, top)     -- child-local origin
     *    3. if clipChildren: clipRect(0,0,w,h)    -- restrict child to bounds
     *    4. child.draw(canvas)                    -- recursive paint
     *    5. canvas.restoreToCount(save)           -- pop transform + clip
     *
     *  clipChildren=false (a flag Material widgets occasionally toggle
     *  to let shadows / ripples extend past the bounds) skips step 3.
     *  Default is ON per AOSP ViewGroup.java:715-716.
     */
    @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
        if (canvas == null) return;
        // AOSP calls computeScroll() on a view at DRAW time, immediately before
        // its scroll offset is consumed -- View.draw(Canvas, ViewGroup, long):
        //     int sx = 0; int sy = 0;
        //     if (!drawingWithRenderNode) { computeScroll(); sx = mScrollX; sy = mScrollY; }
        //     ... if (offsetForScroll) canvas.translate(mLeft - sx, mTop - sy);
        // That hook is what advances an OverScroller-driven fling: the view
        // polls computeScrollOffset(), writes the new position, and asks for
        // another frame. We apply the scroll translate PER CHILD below, using
        // THIS group's getScrollX/Y, rather than once at the child's own draw
        // entry -- so the equivalent call site is here, on `this`, before the
        // loop reads getScrollX()/getScrollY().
        //
        // NOTHING CALLED THIS BEFORE v1.0.893, so every computeScroll()
        // override in the tree was dead code and no fling could ever animate.
        // Wrapped like the per-child c.draw() below: a throwing override must
        // not abort the whole group's draw.
        try {
            computeScroll();
        } catch (Throwable scrollErr) {
            com.macapk.loader.GfxTrace.caught(
                "computeScroll:" + getClass().getName(), scrollErr);
        }
        // AOSP ViewGroup.dispatchDraw (android14-release ViewGroup.java:4276-4283,
        // restored at :4350-4352 after the child loop):
        //     final boolean clipToPadding = (flags & CLIP_TO_PADDING_MASK) == CLIP_TO_PADDING_MASK;
        //     if (clipToPadding) {
        //         clipSaveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
        //         canvas.clipRect(mScrollX + mPaddingLeft, mScrollY + mPaddingTop,
        //                 mScrollX + mRight - mLeft - mPaddingRight,
        //                 mScrollY + mBottom - mTop - mPaddingBottom);
        //     }
        // CLIP_TO_PADDING_MASK = FLAG_CLIP_TO_PADDING | FLAG_PADDING_NOT_NULL (:561),
        // and internalSetPadding (:3990-3998) sets FLAG_PADDING_NOT_NULL only while
        // some padding is non-zero. AOSP's canvas is in this group's SCROLLED frame
        // here, hence `mScrollX +`; ours is not (every child below is translated by
        // left - getScrollX() itself), so the same region is the unscrolled rect.
        // A child a layout manager places INTO the padding (androidx
        // LinearLayoutManager fills getEndPadding() past the content edge, which is
        // what makes clipToPadding="false" lists work) is therefore invisible in a
        // clipToPadding group, as on a device; before this every such child painted
        // (Instagram's story tray: the items in its 752 px right padding, which its
        // edge-scale listener leaves at 0.618/1.0/0.618 because they are past the
        // edge it measures from, were drawn -- docs/clip-to-padding_2026-09-30.md).
        int clipSaveCount = -1;
        if (MACAPK_CLIP_TO_PADDING && clipToPadding) {
            final int pl = getPaddingLeft(), pt = getPaddingTop(),
                      pr = getPaddingRight(), pb = getPaddingBottom();
            if ((pl | pt | pr | pb) != 0) {
                clipSaveCount = canvas.save();
                canvas.clipRect(pl, pt, (getRight() - getLeft()) - pr,
                        (getBottom() - getTop()) - pb);
            }
        }
        try {
            macapkDispatchDrawChildren(canvas);
        } finally {
            if (clipSaveCount >= 0) canvas.restoreToCount(clipSaveCount);
        }
    }

    /** dispatchDraw's child pass, split out so the clip-to-padding save above is
     *  restored on every exit (AOSP restores it right after its child loop). */
    private void macapkDispatchDrawChildren(android.graphics.Canvas canvas) {
        // v1.0.100: env-gated skip list (class simple-name match) so we
        // can A/B specific widgets without changing call sites. Used to
        // isolate the small top-left icon on F-Droid's Latest tab to
        // AndroidComposeView -- set MACAPK_SKIP_DRAW=AndroidComposeView
        // and rerun; if the icon disappears, Compose is the source.
        // Comma-separated for skipping multiple classes.
        String skipList = ENV_MACAPK_SKIP_DRAW;
        // DRAW ORDER (MACAPK_CHILD_DRAW_ORDER, default ON). AOSP android14
        // ViewGroup.dispatchDraw:4297-4318 --
        //     final ArrayList<View> preorderedList = drawsWithRenderNode(canvas)
        //             ? null : buildOrderedChildList();
        //     final boolean customOrder = preorderedList == null
        //             && isChildrenDrawingOrderEnabled();
        //     for (int i = 0; i < childrenCount; i++) {
        //         final int childIndex = getAndVerifyPreorderedIndex(childrenCount, i, customOrder);
        //         final View child = getAndVerifyPreorderedView(preorderedList, children, childIndex);
        // Our canvas is a software canvas (drawsWithRenderNode == false), so we take
        // the preordered branch: a Z-sorted list when some child has Z != 0 (with the
        // custom order already folded in by buildOrderedChildList), else the app's
        // getChildDrawingOrder when it enabled one. On a device the RenderNode path
        // reaches the same picture (custom order in place, negative Z before and
        // positive Z after the Z==0 children). Before this, the paint pass used add
        // order while the hit test (dispatchTouchEvent) already honoured this order,
        // so what the user saw on top was not what took the touch: Mastodon's
        // AppKit content view (reverse order, new fragment inserted at index 0)
        // painted every incoming fragment UNDER the outgoing one, and
        // SwipeRefreshLayout's spinner (index 0, elevated) drew under the list.
        // `reorder` is false when neither applies, and then the loop below is the
        // pre-change loop exactly (children.get(i), size re-read every iteration).
        // MACAPK_CHILD_DRAW_ORDER=0 forces that for every group. Both gates, the
        // routes into drawChild and the gate that checks them:
        // docs/viewgroup-drawchild-order-and-layout-listeners_2026-09-26.md,
        // debug/w5viewcore_gate.sh.
        final java.util.ArrayList<View> preordered =
                MACAPK_CHILD_DRAW_ORDER ? macapkBuildDrawOrderList() : null;
        final boolean customOrder = MACAPK_CHILD_DRAW_ORDER && preordered == null
                && isChildrenDrawingOrderEnabled();
        final boolean reorder = preordered != null || customOrder;
        final int orderCount = children.size();
        if (MACAPK_DRAWCHILD_DISPATCH) {
            // ROUTE EVERY CHILD THROUGH drawChild (MACAPK_DRAWCHILD_DISPATCH, default
            // ON). AOSP dispatchDraw paints each child ONLY as
            //     more |= drawChild(canvas, child, drawingTime);
            // and drawChild is protected and overridable -- DrawerLayout paints its
            // 0x99000000 scrim there (androidx DrawerLayout.drawChild), CoordinatorLayout
            // its behaviour scrims, SlidingPaneLayout its fade, FragmentContainerView
            // skips disappearing children there, and app views (Mastodon's AppKit
            // content view) paint dims there. We used to inline the per-child paint
            // here and call c.draw() directly, so every such override was dead code
            // (tobsolitaire's drawer opened with no scrim). The per-child body now
            // lives in drawChild(); what stays here is what AOSP keeps in
            // dispatchDraw (the visibility test) plus our skip list and the M12
            // shadow, which paints in THIS group's space before the child.
            // AOSP: `final long drawingTime = getDrawingTime();` once per dispatchDraw.
            final long drawingTime = getDrawingTime();
            // FRAMESPEED S3: AOSP dispatchDraw:4285-4286, "We will draw our child's
            // animation, let's reset the flag" -- PFLAG_DRAW_ANIMATION is re-armed
            // by this pass's children (drawChild) for the NEXT frame's cull test.
            if (MACAPK_FRAMESPEED_CULL) mMacapkDrawAnimation = false;
            final boolean outerDispatch = mMacapkInDispatchDraw;
            mMacapkInDispatchDraw = true;
            try {
                for (int i = 0; i < (reorder ? orderCount : children.size()); ++i) {
                    final View c = reorder
                            ? getAndVerifyPreorderedView(preordered,
                                    getAndVerifyPreorderedIndex(orderCount, i, customOrder))
                            : children.get(i);
                    if (c == null) continue;
                    if (c.getVisibility() != View.VISIBLE
                            && !(View.MACAPK_LEGACY_ANIM && c.getAnimation() != null)) continue;
                    if (skipList != null && !skipList.isEmpty()) {
                        String simpleName = c.getClass().getSimpleName();
                        boolean skipped = false;
                        for (String s : skipList.split(",")) {
                            if (simpleName.equals(s.trim())) { skipped = true; break; }
                        }
                        if (skipped) continue;
                    }
                    if (android.os.Looper.MACAPK_REAL_LOOPER) {
                        float ea = c.getAlpha() * c.getTransitionAlpha();
                        if (ea > 0f && c.getZ() > 0f) macapkDrawElevationShadow(canvas, c, ea);
                    }
                    // Resilience, same contract as the per-child guard inside drawChild:
                    // an APP override of drawChild that throws must blank only this
                    // child, not the whole group. AOSP would crash the app instead; the
                    // throw is still reported loudly (GfxTrace.caught, once per
                    // site+class+message with a stack). An override that threw between
                    // its own save() and restore() would leave the canvas transformed
                    // for every later sibling, so unwind to the count we entered with.
                    final int sc = canvas.getSaveCount();
                    try {
                        drawChild(canvas, c, drawingTime);
                    } catch (Throwable drawErr) {
                        com.macapk.loader.GfxTrace.caught(
                            "drawChild-override:" + getClass().getName(), drawErr);
                        if (canvas.getSaveCount() > sc) canvas.restoreToCount(sc);
                    }
                }
            } finally {
                mMacapkInDispatchDraw = outerDispatch;
            }
            if (preordered != null) preordered.clear();
            return;
        }
        // ---- MACAPK_DRAWCHILD_DISPATCH=0: the pre-change inline per-child paint,
        // byte for byte (only the child pick above is shared, and it is the old
        // children.get(i) whenever the order gate has nothing to reorder). ----
        // v1.0.121: per-child clip implementation. v1.0.121 defaulted
        // OFF (opt-in via MACAPK_CLIP_CHILDREN=1) because of a
        // layoutGridManually-era regression where the clip hid RV cards.
        // v1.0.124 deleted layoutGridManually and moved to the natural
        // AOSP RV dispatch flow; the regression went with it.
        //
        // v1.0.129: default ON (AOSP-correct), opt OUT via
        // MACAPK_CLIP_CHILDREN=0 if a regression resurfaces. With
        // the clip ON, RV children that overflow the RV's bounds get
        // clipped at the boundary -- so cards positioned past the RV's
        // bottom edge no longer leak into a sibling BottomNavigationView's
        // area. Generic effect: every ViewGroup now bounds its own
        // children, which is the documented Android contract.
        // (Under MACAPK_DRAWCHILD_DISPATCH the same clip is applied in drawChild.)
        boolean clipChildrenFlag =
            !"0".equals(ENV_MACAPK_CLIP_CHILDREN)
            && this.clipChildren;
        // AOSP passes ONE drawingTime down the whole traversal
        // (mAttachInfo.mDrawingTime). Resolved lazily below, so a frame with no
        // running animation never reads the clock at all.
        long animDrawingTime = -1L;
        for (int i = 0; i < (reorder ? orderCount : children.size()); ++i) {
            View c = reorder
                    ? getAndVerifyPreorderedView(preordered,
                            getAndVerifyPreorderedIndex(orderCount, i, customOrder))
                    : children.get(i);
            if (c == null) continue;
            // AOSP ViewGroup.dispatchDraw:
            //     if ((child.mViewFlags & VISIBILITY_MASK) == VISIBLE
            //             || child.getAnimation() != null) {
            //         more |= drawChild(canvas, child, drawingTime);
            //     }
            // The `|| getAnimation() != null` half is why a view that is made
            // INVISIBLE/GONE while its exit animation is still running keeps
            // painting until the animation finishes -- and, more importantly
            // here, keeps being PUMPED, so onAnimationEnd still fires. Without
            // it such an animation is frozen at its first frame forever and any
            // app waiting on the callback hangs. Under MACAPK_LEGACY_ANIM=0 the
            // condition collapses to the historical visibility-only test.
            if (c.getVisibility() != View.VISIBLE
                    && !(View.MACAPK_LEGACY_ANIM && c.getAnimation() != null)) continue;
            if (skipList != null && !skipList.isEmpty()) {
                String simpleName = c.getClass().getSimpleName();
                boolean skipped = false;
                for (String s : skipList.split(",")) {
                    if (simpleName.equals(s.trim())) { skipped = true; break; }
                }
                if (skipped) continue;
            }
            // Tier 3 M12: cast the child's elevation shadow in THIS (parent)
            // coordinate space, BEFORE the per-child save/translate/clip below,
            // so it extends past the child's bounds and sits under the child
            // (over earlier siblings) -- exactly where AOSP projects a RenderNode
            // shadow. Only for a child with getZ()>0 (the common z==0 case pays
            // nothing) and a visible effective alpha (so a fading view's shadow
            // fades with it). Gated on the real-loop flag -> flag-off paints no
            // shadow and is byte-for-byte unchanged.
            if (android.os.Looper.MACAPK_REAL_LOOPER) {
                float ea = c.getAlpha() * c.getTransitionAlpha();
                if (ea > 0f && c.getZ() > 0f) macapkDrawElevationShadow(canvas, c, ea);
            }
            int save = canvas.save();
            // AOSP: position the child at its layout coords MINUS this group's
            // scroll offset, so a scrolled container (ScrollView/RecyclerView)
            // draws its children shifted. getScrollX/Y are 0 for unscrolled
            // groups, so this is a no-op except where scrollTo was called.
            int dx = c.getLeft() - getScrollX(), dy = c.getTop() - getScrollY();
            if (dx != 0 || dy != 0) canvas.translate(dx, dy);
            // LEGACY TWEEN ANIMATION (View.startAnimation / R.anim). AOSP does
            // this inside View.draw(Canvas, ViewGroup, long):24761-24768 -- the
            // per-child paint entry -- which our dispatchDraw inlines, so this
            // is the same call site. `c.getAnimation()` is a single field read,
            // so a tree with no running animation pays exactly that and nothing
            // below is reached: no Transformation, no clock, no matrix.
            android.view.animation.Animation childAnim =
                    View.MACAPK_LEGACY_ANIM ? c.getAnimation() : null;
            float animAlpha = 1f;
            boolean animMore = false;
            if (childAnim != null) {
                // One clock read per frame, not per child: AOSP passes a single
                // mAttachInfo.mDrawingTime down the whole traversal, so two
                // siblings animating together must see the same instant.
                if (animDrawingTime < 0L) animDrawingTime = getDrawingTime();
                try {
                    animMore = c.applyLegacyAnimation(this, animDrawingTime, childAnim, false);
                    android.view.animation.Transformation ct = getChildTransformation();
                    // AOSP:
                    //   if (concatMatrix) { canvas.translate(-transX, -transY);
                    //                       canvas.concat(t.getMatrix());
                    //                       canvas.translate(transX, transY); }
                    // where transX/transY are -scrollX/-scrollY. The sandwich
                    // undoes this group's scroll so the animation matrix is
                    // applied about the child's own layout origin, then redoes
                    // it. concatMatrix is a.willChangeTransformationMatrix(),
                    // which AlphaAnimation answers false to (alpha only).
                    if (childAnim.willChangeTransformationMatrix()) {
                        int sxo = getScrollX(), syo = getScrollY();
                        if (sxo != 0 || syo != 0) canvas.translate(-sxo, -syo);
                        canvas.concat(ct.getMatrix());
                        if (sxo != 0 || syo != 0) canvas.translate(sxo, syo);
                    }
                    // AOSP: `float transformAlpha = t.getAlpha();
                    //        if (transformAlpha < 1) alpha *= transformAlpha;`
                    animAlpha = ct.getAlpha();
                } catch (Throwable animErr) {
                    com.macapk.loader.GfxTrace.caught(
                        "legacyAnim:" + c.getClass().getName(), animErr);
                    childAnim = null;
                }
            }
            // Tier 3 M7: apply the child's 2D transform (translationX/Y, scale,
            // rotation) so ViewPropertyAnimator / ObjectAnimator motion is
            // visible. Identity-default fast path (macapkHasTransform) keeps the
            // common case free; gated so flag-off draw is byte-for-byte unchanged.
            // AOSP order: the ANIMATION matrix is concat'd first (above), then
            // the view's own matrix (View.java:24893-24900).
            if (android.os.Looper.MACAPK_REAL_LOOPER && c.macapkHasTransform()) {
                c.macapkApplyTransform(canvas);
            }
            int cw = c.getRight()  - c.getLeft();
            int ch = c.getBottom() - c.getTop();
            // A 0-area child paints nothing visible, so skip its draw
            // entirely. Besides being free, this guards against a child
            // whose onDraw assumes non-empty bounds and throws on our
            // incomplete framework -- F-Droid's collapsed (0-height)
            // Material LinearProgressIndicator NPEs in BaseProgressIndicator
            // .onDraw (null current drawable), and that throw used to
            // propagate up and abort the ENTIRE window draw (blanking every
            // sibling below it -- the AppDetails screenshots/links section).
            // Gated on the real-loop flag so the shipped path is covered and
            // the flag-off build is byte-for-byte unchanged.
            if (android.os.Looper.MACAPK_REAL_LOOPER && (cw <= 0 || ch <= 0)) {
                canvas.restoreToCount(save);
                // A 0-area child still has to END its animation, or an app
                // waiting on onAnimationEnd for a collapsed view hangs forever.
                if (childAnim != null && !animMore) finishAnimatingView(c, childAnim);
                continue;
            }
            // Only clip when bounds are positive; a 0x0 child gets no
            // chance to leak (drawing primitives short-circuit anyway).
            if (clipChildrenFlag && cw > 0 && ch > 0) {
                // v1.0.121: structural clip (always honored). The public
                // clipRect respects MACAPK_REAL_CLIP for emergency
                // rollback; this internal entry does not, because the
                // per-child bounds clip IS the dispatchDraw contract.
                canvas.macapkClipChildRect(0, 0, cw, ch);
            }
            // Tier 3 M9 (alpha-in-draw): modulate the child by its effective
            // alpha = getAlpha() * getTransitionAlpha() -- the AOSP View.draw
            // software path (View.java: alpha = getAlpha()*getTransitionAlpha();
            // alpha<1 && onSetAlpha()==false && LAYER_TYPE_NONE -> canvas.save-
            // LayerAlpha(...)). A zero effective alpha means the view is not
            // visible to the user (AOSP View.java:11724), so skip its draw; a
            // partial alpha pushes a group-opacity layer so the whole subtree
            // fades as one unit. M7 made alpha live + animatable; this is the
            // missing draw-path half so a ViewPropertyAnimator.alpha() fade is
            // actually visible. Gated on the real-loop flag + an alpha==1 fast
            // path so opaque views and the shipped (flag-off) build are
            // byte-for-byte unchanged. The layer is unwound by restoreToCount
            // (same as the M7 transform + the per-child clip).
            if (android.os.Looper.MACAPK_REAL_LOOPER) {
                // AOSP: alpha = getAlpha() * getTransitionAlpha(), then
                // `alpha *= transformAlpha` when the animation supplied one
                // (View.java:24885-24890). animAlpha is 1f when nothing is
                // animating, so this multiply is free and exact.
                float a = c.getAlpha() * c.getTransitionAlpha() * animAlpha;
                if (a <= 0f) {
                    canvas.restoreToCount(save);
                    if (childAnim != null && !animMore) finishAnimatingView(c, childAnim);
                    continue;
                }
                if (a < 1f && cw > 0 && ch > 0) {
                    canvas.saveLayerAlpha(0, 0, cw, ch, (int) (a * 255f + 0.5f));
                }
            }
            // Resilient draw pass (lead A): wrap each child's draw so a single
            // throwing or silently-incomplete child blanks only ITSELF and the
            // rest of the tree still paints, instead of one missing/no-op Canvas
            // op aborting the whole dispatchDraw and blanking every sibling after
            // it (the measured blank-canvas symptom -- see docs/canvas-draw-
            // pipeline-gaps). The caught sink also records the gap once with a
            // full stack, so a SINGLE run enumerates every per-node draw gap
            // rather than discovering one per cold-run. The restoreToCount below
            // (also run on the catch path) returns the canvas to this child's
            // save point so the next sibling draws into a clean transform/clip.
            try {
                c.draw(canvas);
            } catch (Throwable drawErr) {
                com.macapk.loader.GfxTrace.caught("dispatchDraw:" + c.getClass().getName(), drawErr);
            }
            canvas.restoreToCount(save);
            // AOSP View.draw(Canvas, ViewGroup, long):24999-25004 --
            //     if (a != null && !more) {
            //         if (!hardwareAcceleratedCanvas && !a.getFillAfter()) onSetAlpha(255);
            //         parent.finishAnimatingView(this, a);
            //     }
            // after restoreTo, so the transform is already unwound.
            if (childAnim != null && !animMore) {
                if (!childAnim.getFillAfter()) {
                    try { c.onSetAlpha(255); } catch (Throwable ignored) {}
                }
                finishAnimatingView(c, childAnim);
            }
        }
        // AOSP: `if (preorderedList != null) preorderedList.clear();` -- do not pin
        // child references in the cached list between frames.
        if (preordered != null) preordered.clear();
    }

    /** Tier 3 M12: paint an elevated child's soft drop shadow in this parent's
     *  coordinate space. Multi-pass expanding rounded-rect fills (a cheap blur)
     *  shaped by the child's outline radius, offset down + sized by the child's
     *  Z (px), per-pass alpha scaled by the child's effective alpha. This is the
     *  observable equivalent of AOSP's hardware ambient+spot shadow (the software
     *  canvas has no real shadow primitive); drawn UNDER the child so the child's
     *  own content paints on top. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean>
        macapkShadowTraced = new java.util.concurrent.ConcurrentHashMap<>();
    private static void macapkDrawElevationShadow(android.graphics.Canvas canvas,
                                                  View c, float alpha) {
        int l = c.getLeft(), t = c.getTop(), r = c.getRight(), b = c.getBottom();
        if (r - l <= 0 || b - t <= 0) return;
        float z = c.getZ();
        if (z <= 0f) return;
        float density = android.util.DisplayMetrics.macapkDeviceDensity();
        try {
            if (c.getResources() != null && c.getResources().getDisplayMetrics() != null
                && c.getResources().getDisplayMetrics().density > 0f) {
                density = c.getResources().getDisplayMetrics().density;
            }
        } catch (Throwable ignored) {}
        float radius = c.macapkShadowRadiusPx();
        if (radius < 0f) return;  // no outline + no background surface -> no shadow (AOSP)
        if ("1".equals(ENV_MACAPK_TRACE_SHADOW)) {
            String cn = c.getClass().getSimpleName();
            if (macapkShadowTraced.putIfAbsent(cn, Boolean.TRUE) == null) {
                com.macapk.loader.NativeLog.i("Mac.apk.ViewGroup",
                    "M12 elevation shadow: " + cn + " z=" + z + "px radius=" + radius
                    + "px bounds=(" + l + "," + t + "-" + r + "," + b + ")");
            }
        }
        // Z is in px (elevation set in px / android:elevation resolved to px).
        // The fill (spread ~ Z capped at 24dp, offset Z/2, four accumulating
        // round-rect passes) is shared with the window compositor's shadow
        // (WindowOverlayStack.paintSoftShadow), so a child and a window cast
        // the same shadow and are tuned in one place.
        com.macapk.loader.WindowOverlayStack.paintSoftShadow(canvas,
                new android.graphics.Rect(l, t, r, b), radius, z, alpha, density);
    }

    // findViewById walks the recorded children list -- apps that build
    // their UI programmatically with addView + setId rely on this for
    // `parent.findViewById(id)` lookup. Recursive: a child ViewGroup's
    // findViewById is invoked, so the whole subtree is searched.
    // Frozen Bubble's HomeScreen.selectInitialButton needs this.
    @SuppressWarnings("unchecked")
    @Override
    public <T extends View> T findViewById(int searchId) {
        if (searchId == NO_ID) return null;
        return findViewByIdRec(searchId, new java.util.IdentityHashMap<>());
    }
    @SuppressWarnings("unchecked")
    private <T extends View> T findViewByIdRec(int searchId,
                                               java.util.IdentityHashMap<View, Boolean> visited) {
        if (visited.put(this, Boolean.TRUE) != null) return null;
        // Self-match first (matches AOSP ordering).
        T self = super.findViewById(searchId);
        if (self != null) return self;
        // Then recurse into children. children list is non-null per
        // ctor invariant; entries may be null if removeView left a
        // slot behind, hence the null-guard.
        // v1.0.95: cycle-safe via the IdentityHashMap. Without this,
        // a tree containing a parent->...->parent edge SOFs every
        // caller (MainActivity.findViewById, AppCompat plumbing,
        // androidx ViewCompat). The defensive walk + addView refusal
        // in addView together make a stray cycle observable (via the
        // refused-addView warning) instead of fatal.
        for (int i = 0; i < children.size(); i++) {
            View c = children.get(i);
            if (c == null) continue;
            if (c instanceof ViewGroup cvg) {
                T found = cvg.findViewByIdRec(searchId, visited);
                if (found != null) return found;
            } else if (visited.put(c, Boolean.TRUE) == null) {
                T found = c.findViewById(searchId);
                if (found != null) return found;
            }
        }
        // Views this group owns but has not attached (ListView's headers and
        // footers before the first layout), searched after the children as
        // AOSP ListView.findViewTraversal does.
        java.util.List<View> owned = macapkUnattachedFindRoots();
        if (owned != null) {
            for (View c : owned) {
                if (c == null) continue;
                if (c instanceof ViewGroup cvg) {
                    T found = cvg.findViewByIdRec(searchId, visited);
                    if (found != null) return found;
                } else if (visited.put(c, Boolean.TRUE) == null) {
                    T found = c.findViewById(searchId);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    /** Views a findViewById of this group must also search although they are
     *  not (yet) its children. None for a plain ViewGroup. */
    protected java.util.List<View> macapkUnattachedFindRoots() { return null; }

    // -------- AOSP-audit batch (v1.0.116) — missing ViewGroup public API --------

    /** AOSP ViewGroup.getDescendantFocusability / setDescendantFocusability.
     *  Mode controls how the parent participates in focus search vs
     *  its descendants. Storage only — we don't actually focus-walk. */
    private int descendantFocusability = FOCUS_BEFORE_DESCENDANTS;
    public int getDescendantFocusability() { return descendantFocusability; }
    /** AOSP ViewGroup.clearFocus(): if a CHILD holds focus, clear the child;
     *  otherwise clear our own the way View does.
     *
     *  ⚠ THIS WAS `{}`. View.clearFocus() clears mIsFocused, notifies the parent
     *  via clearChildFocus, fires onFocusChanged and refreshes drawable state --
     *  and a no-op override meant NO ViewGroup EVER CLEARED FOCUS. Focus was
     *  sticky on every container, so an EditText inside a layout kept focus (and
     *  its focused drawable state) after the container was asked to drop it.
     *  Found 2026-08-24 by debug/stubscan.py; same shape as the setFrame stubs
     *  that zeroed every widget's layout bounds earlier the same day. */
    @Override public void clearFocus() {
        if (mFocused == null) {
            super.clearFocus();
        } else {
            final View focused = mFocused;
            mFocused = null;
            focused.clearFocus();
        }
    }

    /** v1.0.124: AOSP `ViewGroup.addViewInLayout(View, int, LP, boolean)`.
     *  Called by RV's mChildHelper during the layout pass to attach a
     *  NEW child without requesting a re-layout (the layout is already
     *  in progress). Distinct from `addView` (which dispatches a full
     *  add + invalidates + requests layout) and from
     *  `attachViewToParent` (which re-attaches a previously-detached
     *  child without binding its LP).
     *
     *  Per cs.android.com/.../view/ViewGroup.java:
     *    protected boolean addViewInLayout(View child, int index,
     *        LayoutParams params, boolean preventRequestLayout) {
     *      child.mParent = null;
     *      addViewInner(child, index, params, preventRequestLayout);
     *      child.mPrivateFlags = (child.mPrivateFlags & ~PFLAG_DIRTY_MASK)
     *          | PFLAG_DRAWN;
     *      return true;
     *    }
     *
     *  v1.0.116 had concerns about double-attach -- the fix was wrong
     *  back then (we routed through addView). Correct fix here:
     *  bookkeep the add into our `children` list, set parent, set
     *  LayoutParams. Without it, RV.mChildHelper.addView throws
     *  NoSuchMethodError mid-fill, aborting layout. */
    protected boolean addViewInLayout(View child, int index, LayoutParams params) {
        return addViewInLayout(child, index, params, false);
    }
    protected boolean addViewInLayout(View child, int index, LayoutParams params,
                                       boolean preventRequestLayout) {
        if (child == null) return false;
        // Clear stale parent if any (per AOSP).
        ViewParent oldParent = child.getParent();
        if (oldParent instanceof ViewGroup ovg && ovg != this) {
            try { ovg.removeView(child); } catch (Throwable ignored) {}
        }
        if (params != null) {
            try { child.setLayoutParams(params); } catch (Throwable ignored) {}
        }
        if (index < 0 || index > children.size()) index = children.size();
        // Don't re-add if already in our list (idempotent).
        int existingIdx = children.indexOf(child);
        if (existingIdx < 0) children.add(index, child);
        child.setParent(this);
        macapkDragChildAdded(child);      // AOSP addViewInLayout -> addViewInner
        return true;
    }
    /** v1.0.124: AOSP `removeViewInLayout` -- companion of addViewInLayout
     *  for the RV scrap path. Removes from children list + clears
     *  parent without triggering requestLayout. */
    public void removeViewInLayout(View child) {
        if (children.remove(child) && child != null) {
            macapkFinishRemoval(child);
            macapkDragChildRemoved(child);
        }
    }
    public void removeViewsInLayout(int start, int count) {
        // Unlink the whole range FIRST, then run the per-child removal
        // callbacks: macapkFinishRemoval below reaches app code (detach
        // listeners, onViewRemoved) that is allowed to touch this group's
        // children, so iterating the live list while dispatching would
        // ConcurrentModificationException.
        java.util.List<View> removed = new java.util.ArrayList<>();
        for (int i = 0; i < count && start < children.size(); i++) {
            View c = children.remove(start);
            if (c != null) removed.add(c);
        }
        for (View c : removed) macapkFinishRemoval(c);
    }
    public void removeAllViewsInLayout() {
        // Copy first so the removal callbacks can safely re-enter.
        java.util.List<View> copy = new java.util.ArrayList<>(children);
        children.clear();
        for (View c : copy) {
            if (c == null) continue;
            macapkFinishRemoval(c);
        }
    }
    /** AOSP ViewGroup.removeViews(int, int) (ViewGroup.java:5580-5583): the
     *  non-"InLayout" range removal -- the same removeViewsInternal body plus
     *  the requestLayout()/invalidate() that the inLayout variant deliberately
     *  skips because a layout pass is already running. Was absent entirely, so
     *  an APK calling it took a NoSuchMethodError mid-removal. */
    public void removeViews(int start, int count) {
        removeViewsInLayout(start, count);
        requestLayout();
        invalidate();
    }

    /** The per-child tail of every removal path, matching AOSP's
     *  removeViewInternal / removeViewsInternal / removeAllViewsInLayout
     *  (ViewGroup.java:5595-5620, 5701-5745, 5794-5843), all three of which
     *  run the SAME three steps for each departing child: dispatch detach if
     *  the child was attached, dispatchViewRemoved (onViewRemoved + the
     *  hierarchy listener), and clear the parent link.
     *
     *  The detach dispatch is the load-bearing one and it was missing from
     *  every bulk removal path (only removeView / removeViewAt had it), which
     *  meant a container clearing its children never fired
     *  OnAttachStateChangeListener.onViewDetachedFromWindow. Compose's DEFAULT
     *  ViewCompositionStrategy (DisposeOnDetachedFromWindow /
     *  DisposeOnDetachedFromWindowOrReleasedFromPool) registers exactly such a
     *  listener and calls disposeComposition() from it, so a screen swapped
     *  out via removeAllViews() left its whole Composition alive forever --
     *  LaunchedEffect coroutines, DisposableEffects, running animations and
     *  AndroidUiDispatcher frame callbacks included. A silent, unbounded leak
     *  that compounds with every navigation rather than a visible failure.
     *
     *  View.macapkDispatchDetachedFromWindow already early-returns when the
     *  view is not attached, which is AOSP's `else if (detach)` guard, and
     *  ViewGroup overrides it to recurse so the whole departing subtree
     *  detaches. */
    private void macapkFinishRemoval(View child) {
        if (View.HOVER_VIEWS && mFirstHoverTarget != null) cancelHoverTarget(child);
        child.setParent(null);
        child.macapkDispatchDetachedFromWindow();
        try { onViewRemoved(child); } catch (Throwable ignored) {}
        if (hierarchyListener != null) {
            try { hierarchyListener.onChildViewRemoved(this, child); } catch (Throwable ignored) {}
        }
    }
    protected void cleanupLayoutState(View child) {}

    /** AOSP ViewGroup.bringChildToFront(View): moves the child to
     *  the end of the children list (last = topmost in draw order). */
    public void bringChildToFront(View child) {
        if (child == null) return;
        int idx = indexOfChild(child);
        if (idx < 0 || idx == getChildCount() - 1) return;
        children.remove(idx);
        children.add(child);
    }

    /** AOSP ViewGroup.endViewTransition: signals end of a child's
     *  animated transition. We don't run real transitions. */
    public void endViewTransition(View view) {}
    public void startViewTransition(View view) {}
    public boolean isTransitionGroup() { return false; }
    public void setTransitionGroup(boolean isTransitionGroup) {}
    /** AOSP ViewGroup.mTransition (ViewGroup.java:5658-5681, android14-release).
     *
     *  These two used to be a hardcoded `return null` and an empty setter, on the
     *  reasoning that "null = no transition configured, the AOSP default". The
     *  default is right; making it UNCONDITIONAL was not. AOSP's getter returns
     *  whatever was set:
     *      public void setLayoutTransition(LayoutTransition transition) {
     *          if (mTransition != null) {
     *              LayoutTransition previousTransition = mTransition;
     *              previousTransition.cancel();
     *              previousTransition.removeTransitionListener(mLayoutTransitionListener);
     *          }
     *          mTransition = transition;
     *          if (mTransition != null) {
     *              mTransition.addTransitionListener(mLayoutTransitionListener);
     *          }
     *      }
     *      public LayoutTransition getLayoutTransition() { return mTransition; }
     *
     *  A non-round-tripping pair breaks the standard read-modify idiom
     *  `container.getLayoutTransition().setDuration(...)` with an NPE. Found by
     *  the 2026-08-27 fleet census on Signal:
     *      Mac.apk.LayoutInflater: onFinishInflate threw on
     *      org.thoughtcrime.securesms.conversationlist.ConversationListItem:
     *      java.lang.NullPointerException: Cannot invoke
     *      "android.animation.LayoutTransition.setDuration(long)" because "<local1>" is null
     *  -- i.e. a group declared `android:animateLayoutChanges="true"` and then read
     *  its transition back, which on a device is never null.
     *
     *  DELIBERATE OMISSION: AOSP's add/removeTransitionListener(mLayoutTransitionListener)
     *  wires the transition into ViewGroup's own layout-suppression machinery. We do
     *  not run layout transitions, so there is no listener to register and nothing for
     *  it to suppress; registering a no-op one would be shape without substance. The
     *  cancel() of a previous transition IS kept -- it is observable through
     *  isRunning()/isChangingLayout() on the object the app still holds.
     *
     *  Net effect: an app that sets or declares a transition can read it back and
     *  configure it, exactly as on a device. Nothing animates -- same as before. */
    private android.animation.LayoutTransition mTransition;

    public android.animation.LayoutTransition getLayoutTransition() { return mTransition; }

    public void setLayoutTransition(android.animation.LayoutTransition transition) {
        if (mTransition != null) mTransition.cancel();
        mTransition = transition;
    }

    /** AOSP ViewGroup.offsetDescendantRectToMyCoords(View, Rect):
     *  translate a rect from a descendant's local coords to ours
     *  (sum the offsets walking up). */
    public void offsetDescendantRectToMyCoords(View descendant, Rect rect) {
        if (rect == null || descendant == null) return;
        View v = descendant;
        while (v != null && v != this) {
            rect.offset(v.getLeft() - v.getScrollX(), v.getTop() - v.getScrollY());
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
    }
    public void offsetRectIntoDescendantCoords(View descendant, Rect rect) {
        if (rect == null || descendant == null) return;
        // Reverse of offsetDescendantRectToMyCoords.
        java.util.ArrayList<View> chain = new java.util.ArrayList<>();
        View v = descendant;
        while (v != null && v != this) {
            chain.add(v);
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            View vc = chain.get(i);
            rect.offset(-(vc.getLeft() - vc.getScrollX()),
                        -(vc.getTop() - vc.getScrollY()));
        }
    }

    /** AOSP ViewGroup.getChildVisibleRect (:6305-6380). Maps `r` -- given in the
     *  CHILD's coordinates -- up into this group's space, CLIPS it against this
     *  group's bounds/padding/clipBounds, then recurses to our own parent, so
     *  the returned rect is the child's visible region in the topmost
     *  ancestor's space and the boolean is "any of it survives the clips".
     *
     *  The old body only offset and never clipped, so an oversized child
     *  reported its full rect. Measured by the fwprobe golden as
     *  DISP.hier.t3.N.globalVisRect: N is 300x300 inside a 300x300 M at
     *  (50,60), so the emulator answers 70,90,350,360 (clipped by M) where we
     *  answered 70,90,370,390 (unclipped). NOTE this method is not yet on the
     *  path that produced that answer: our View.getGlobalVisibleRect computes
     *  from getLocationOnScreen + width/height instead of delegating here, the
     *  way AOSP View.getGlobalVisibleRect does. That one-line View change is
     *  requested in the session report; this side is now correct for it. */
    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
        return getChildVisibleRect(child, r, offset, false);
    }

    /** @param forceParentCheck true to guarantee the call propagates to every
     *  ancestor even once the rect has been clipped away (AOSP @hide overload). */
    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset,
                                       boolean forceParentCheck) {
        if (child == null || r == null) return false;
        final android.graphics.RectF rect = new android.graphics.RectF();
        rect.set(r);

        if (child.macapkHasTransform()) macapkChildMatrix(child).mapRect(rect);

        final int dx = child.getLeft() - getScrollX();
        final int dy = child.getTop() - getScrollY();
        rect.offset(dx, dy);

        if (offset != null) {
            if (child.macapkHasTransform()) {
                float[] position = new float[]{ offset.x, offset.y };
                macapkChildMatrix(child).mapPoints(position);
                offset.x = Math.round(position[0]);
                offset.y = Math.round(position[1]);
            }
            offset.x += dx;
            offset.y += dy;
        }

        final int width = getRight() - getLeft();
        final int height = getBottom() - getTop();

        boolean rectIsVisible = true;
        final ViewParent p = getParent();
        if (p == null || (p instanceof ViewGroup && ((ViewGroup) p).getClipChildren())) {
            rectIsVisible = rect.intersect(0, 0, width, height);
        }
        // AOSP gates this on CLIP_TO_PADDING_MASK == FLAG_CLIP_TO_PADDING |
        // FLAG_PADDING_NOT_NULL, and FLAG_PADDING_NOT_NULL is only set when some
        // padding is non-zero -- so a clipToPadding group with no padding does
        // NOT take this branch.
        final boolean hasPadding = (getPaddingLeft() | getPaddingTop()
                | getPaddingRight() | getPaddingBottom()) != 0;
        if ((forceParentCheck || rectIsVisible) && clipToPadding && hasPadding) {
            rectIsVisible = rect.intersect(getPaddingLeft(), getPaddingTop(),
                    width - getPaddingRight(), height - getPaddingBottom());
        }
        r.set((int) Math.floor(rect.left), (int) Math.floor(rect.top),
              (int) Math.ceil(rect.right), (int) Math.ceil(rect.bottom));

        if ((forceParentCheck || rectIsVisible) && p != null) {
            if (p instanceof ViewGroup) {
                rectIsVisible = ((ViewGroup) p).getChildVisibleRect(this, r, offset,
                        forceParentCheck);
            } else {
                rectIsVisible = p.getChildVisibleRect(this, r, offset);
            }
        }
        return rectIsVisible;
    }

    /** AOSP ViewGroup.focusableViewAvailable: child reports its
     *  focusability changed. Propagates up. */
    public void focusableViewAvailable(View v) {
        ViewParent p = getParent();
        if (p instanceof ViewGroup vp) vp.focusableViewAvailable(v);
    }

    /** AOSP ViewGroup.addFocusables (android14-release ViewGroup.java), mirrored
     *  in full as of v1.0.1609. Three things the v1.0.686 version (children in
     *  add order, then self) did not do, each observable by a keyboard user:
     *
     *   1. descendantFocusability. FOCUS_BLOCK_DESCENDANTS offers only the group;
     *      FOCUS_BEFORE_DESCENDANTS offers the group BEFORE its children;
     *      FOCUS_AFTER_DESCENDANTS (what PhoneWindow gives the decor) offers the
     *      group only when NO descendant qualified, "to avoid the focus search
     *      finding layouts when a more precise search among the focusable
     *      children would be more interesting".
     *   2. touchscreenBlocksFocus: such a group and its subtree are reachable
     *      only by views that are focusable in touch mode, unless it is a
     *      keyboard-navigation cluster that already holds focus.
     *   3. ORDER. Children are offered in GEOMETRIC order -- rows top to bottom,
     *      then left to right within a row (FocusFinder.sort / FocusSorter) --
     *      not in the order they were added. This is what makes TAB walk a form
     *      the way the eye reads it regardless of how the layout was built.
     *
     *  This is the recursion the View leaf contract (View.addFocusables, with
     *  canTakeFocus) is built to drive. */
    @Override
    public void addFocusables(java.util.ArrayList<View> views, int direction, int focusableMode) {
        if (views == null) return;
        final int focusableCount = views.size();

        final int descendantFocusability = getDescendantFocusability();
        final boolean blockFocusForTouchscreen = shouldBlockFocusForTouchscreen();
        final boolean focusSelf = (isFocusableInTouchMode() || !blockFocusForTouchscreen);

        if (descendantFocusability == FOCUS_BLOCK_DESCENDANTS) {
            if (focusSelf) super.addFocusables(views, direction, focusableMode);
            return;
        }

        if (blockFocusForTouchscreen) focusableMode |= View.FOCUSABLES_TOUCH_MODE;

        if ((descendantFocusability == FOCUS_BEFORE_DESCENDANTS) && focusSelf) {
            super.addFocusables(views, direction, focusableMode);
        }

        int count = 0;
        final View[] kids = new View[children.size()];
        for (int i = 0; i < children.size(); ++i) {
            View child = children.get(i);
            if (child != null && child.getVisibility() == VISIBLE) kids[count++] = child;
        }
        FocusFinder.sort(kids, 0, count, this, isLayoutRtl());
        for (int i = 0; i < count; ++i) {
            kids[i].addFocusables(views, direction, focusableMode);
        }

        // When set to FOCUS_AFTER_DESCENDANTS, we only add ourselves if there
        // aren't any focusable descendants (AOSP's own comment, kept verbatim in
        // spirit above).
        if ((descendantFocusability == FOCUS_AFTER_DESCENDANTS) && focusSelf
                && focusableCount == views.size()) {
            super.addFocusables(views, direction, focusableMode);
        }
    }
    /** AOSP ViewGroup.shouldBlockFocusForTouchscreen (private): a
     *  touchscreenBlocksFocus group blocks focus on a device that HAS a
     *  touchscreen -- except when it is a keyboard-navigation cluster that
     *  already holds focus, or is not the top-most cluster of its subtree, in
     *  which case cluster navigation may enter it and move freely inside. */
    boolean shouldBlockFocusForTouchscreen() {
        if (!getTouchscreenBlocksFocus()) return false;
        boolean hasTouchscreen = true;
        try {
            android.content.Context c = getContext();
            android.content.pm.PackageManager pm = (c == null) ? null : c.getPackageManager();
            if (pm != null) {
                hasTouchscreen = pm.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
            }
        } catch (Throwable ignored) { }
        return hasTouchscreen
                && !(isKeyboardNavigationCluster()
                     && (hasFocus() || (findKeyboardNavigationCluster() != this)));
    }
    /** AOSP ViewGroup.findViewWithTagTraversal (android14-release
     *  ViewGroup.java:4800-4820):
     *
     *      if (tag != null && tag.equals(mTag)) return (T) this;
     *      for (int i = 0; i < len; i++) {
     *          View v = where[i];
     *          if ((v.mPrivateFlags & PFLAG_IS_ROOT_NAMESPACE) == 0) {
     *              v = v.findViewWithTag(tag);
     *              if (v != null) return (T) v;
     *          }
     *      }
     *      return null;
     *
     *  Until this override existed only View's LEAF traversal ran, so
     *  findViewWithTag on any ViewGroup matched the group itself and never a
     *  descendant. Measured: Telegram's IntroActivity.updateColors does
     *  `(TextView) page.findViewWithTag(tag)` on each ViewPager page (a
     *  FrameLayout whose tagged TextViews are children), got null, and the NPE
     *  aborted the dark-theme recolour halfway (fleet A/B 2026-09-24). The
     *  descent goes through the public findViewWithTag exactly as AOSP's does,
     *  so an app subclass overriding findViewWithTagTraversal is still asked.
     *  The root-namespace skip is omitted for the reason given on
     *  View.setIsRootNamespace (no decor is ever a child here). */
    @Override
    @SuppressWarnings("unchecked")
    protected <T extends View> T findViewWithTagTraversal(Object tag) {
        // Self-match through View's leaf, which compares the stored tag field
        // (AOSP's mTag) rather than an overridable getTag().
        T self = super.findViewWithTagTraversal(tag);
        if (self != null) return self;
        for (int i = 0; i < children.size(); i++) {
            View v = children.get(i);
            if (v == null) continue;
            v = v.findViewWithTag(tag);
            if (v != null) return (T) v;
        }
        return null;
    }
    /** AOSP ViewGroup.findViewByPredicateTraversal (android14-release
     *  ViewGroup.java:4827-4850): self first, then each child except
     *  `childToSkip` (the subtree an inside-out search already covered).
     *  AOSP descends through the public findViewByPredicate, which restarts
     *  each child's walk with childToSkip == null; it is the CALLER's direct
     *  child, so it cannot occur deeper, and passing it down only exposed a
     *  non-null value to an app subclass's override where AOSP shows null.
     *  AOSP also skips a child that is a root namespace; see the note on
     *  View.setIsRootNamespace for why that branch has nothing to skip here. */
    @Override
    @SuppressWarnings("unchecked")
    protected <T extends View> T findViewByPredicateTraversal(
            java.util.function.Predicate<View> predicate, View childToSkip) {
        if (predicate.test(this)) return (T) this;
        for (int i = 0; i < children.size(); i++) {
            View v = children.get(i);
            if (v == null || v == childToSkip) continue;
            v = v.findViewByPredicate(predicate);
            if (v != null) return (T) v;
        }
        return null;
    }
    @Override
    public void addTouchables(java.util.ArrayList<View> views) {
        super.addTouchables(views);
        for (int i = 0; i < children.size(); i++) {
            View child = children.get(i);
            if (child != null && child.getVisibility() == VISIBLE) {
                child.addTouchables(views);
            }
        }
    }
    public void addKeyboardNavigationClusters(java.util.Collection<View> views, int direction) {}

    /** AOSP ViewGroup.dispatchKeyEvent / dispatchKeyEventPreIme:
     *  walks the focused child chain. We don't track focus. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // AOSP: if focus is in this subtree, route the key to the focused
        // child chain; otherwise handle locally. This is what delivers key
        // input to the focused view (e.g. AndroidComposeView for text input).
        if (mFocused != null && (mFocused.mIsFocused || mFocused.hasFocus())) {
            if (mFocused.dispatchKeyEvent(event)) return true;
        }
        return super.dispatchKeyEvent(event);
    }
    public boolean dispatchKeyEventPreIme(KeyEvent event) { return false; }

    /** AOSP ViewGroup.dispatchKeyShortcutEvent (android14-release :1978-1987):
     *  <pre>
     *  if ((mPrivateFlags & (PFLAG_FOCUSED | PFLAG_HAS_BOUNDS))
     *          == (PFLAG_FOCUSED | PFLAG_HAS_BOUNDS)) {
     *      return super.dispatchKeyShortcutEvent(event);
     *  } else if (mFocused != null && (mFocused.mPrivateFlags & PFLAG_HAS_BOUNDS)
     *          == PFLAG_HAS_BOUNDS) {
     *      return mFocused.dispatchKeyShortcutEvent(event);
     *  }
     *  return false;
     *  </pre>
     *  This tree keeps no PFLAG_HAS_BOUNDS (AOSP sets it in the first
     *  setFrame), so the focus half is what is mirrored -- the same reduction
     *  dispatchKeyEvent above makes. Was inherited View's `return false`. */
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (mIsFocused) return super.dispatchKeyShortcutEvent(event);
        if (mFocused != null) return mFocused.dispatchKeyShortcutEvent(event);
        return false;
    }

    // ------------------------------------------------------------------
    // Legacy (tween) view animation: the ViewGroup half of the pump.
    // See View.applyLegacyAnimation for the contract and the cost argument.
    // ------------------------------------------------------------------

    /** AOSP ViewGroup.getChildTransformation (android16-release
     *  core/java/android/view/ViewGroup.java:4776-4781):
     *
     *      Transformation getChildTransformation() {
     *          if (mChildTransformation == null) mChildTransformation = new Transformation();
     *          return mChildTransformation;
     *      }
     *
     *  ONE scratch Transformation per group, created on first use. A group that
     *  never animates a child never allocates one, which is why the per-child
     *  draw loop must gate on getAnimation() != null BEFORE calling this. */
    private android.view.animation.Transformation mChildTransformation;
    android.view.animation.Transformation getChildTransformation() {
        if (mChildTransformation == null) {
            mChildTransformation = new android.view.animation.Transformation();
        }
        return mChildTransformation;
    }

    /** AOSP ViewGroup.finishAnimatingView(View, Animation) (android16-release
     *  ViewGroup.java:7162-7190), called from View.draw(Canvas, ViewGroup, long)
     *  :25003 as `if (a != null && !more) parent.finishAnimatingView(this, a)`.
     *
     *      if (animation != null && !animation.getFillAfter()) view.clearAnimation();
     *      if ((view.mPrivateFlags & PFLAG_ANIMATION_STARTED) == PFLAG_ANIMATION_STARTED) {
     *          view.onAnimationEnd();
     *          view.mPrivateFlags &= ~PFLAG_ANIMATION_STARTED;
     *          mGroupFlags |= FLAG_INVALIDATE_REQUIRED;
     *      }
     *
     *  Two separate end guarantees live here and both matter:
     *   - clearAnimation() drops the reference when fillAfter is false, so the
     *     view stops being pumped and the per-child loop returns to its zero-cost
     *     path. With fillAfter true AOSP deliberately KEEPS the animation set so
     *     the final transform stays applied; that costs one getTransformation per
     *     frame and asks for no further frames (more is false), so it does not
     *     spin.
     *   - the started-flag test fires the protected onAnimationEnd() hook exactly
     *     once. AOSP clears the flag here rather than trusting the override to
     *     call super, with the comment "this avoid an infinite loop, so we'd
     *     rather be safe than sorry"; kept, for the same reason.
     *
     *  NOT MIRRORED: the mDisappearingChildren branch (a view removed mid-layout
     *  that keeps drawing until its exit animation finishes). We have no
     *  disappearing-children list, so there is nothing to take the view out of. */
    void finishAnimatingView(View view, android.view.animation.Animation animation) {
        if (animation != null && !animation.getFillAfter()) {
            view.clearAnimation();
        }
        if (View.TRACE_LEGACY_ANIM) {
            com.macapk.loader.NativeLog.i("Mac.apk.LegacyAnim", "finish on " + view.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(view)) + " started=" + view.mAnimationStarted);
        }
        if (view.mAnimationStarted) {
            view.mAnimationStarted = false;
            try { view.onAnimationEnd(); } catch (Throwable endErr) {
                com.macapk.loader.GfxTrace.caught(
                    "onAnimationEnd:" + view.getClass().getName(), endErr);
            }
            // AOSP sets FLAG_INVALIDATE_REQUIRED so one more frame is drawn
            // after the animation is over. Ours has no such flag; ask directly.
            invalidate();
        }
    }

    /** AOSP ViewGroup.drawChild(canvas, child, drawingTime) (android14
     *  ViewGroup.java:4555-4557):
     *      protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
     *          return child.draw(canvas, this, drawingTime);
     *      }
     *  i.e. THE per-child paint entry: dispatchDraw reaches every child through it,
     *  and View.draw(Canvas, ViewGroup, long) (android14 View.java:23538) does the
     *  scroll translate, legacy animation, transform, clip, alpha and the recursive
     *  draw, returning `more`
     *  (true while a legacy animation still needs frames). We have no package-private
     *  View.draw(Canvas, ViewGroup, long); this method carries that body instead.
     *
     *  Under MACAPK_DRAWCHILD_DISPATCH (default ON) it is the SAME body the old
     *  inline dispatchDraw loop ran when reached from dispatchDraw (the two pieces
     *  only that loop had -- the 0-area skip and the MACAPK_CLIP_CHILDREN escape
     *  hatch -- apply on that route), so a group whose drawChild is not overridden
     *  paints exactly as before; and it is the old drawChild body when app code
     *  calls it directly. Either way it now returns AOSP's `more` instead of a
     *  constant true. =0 restores the pre-change body (macapkDrawChildLegacy)
     *  together with the inline dispatchDraw loop. */
    protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
        if (!MACAPK_DRAWCHILD_DISPATCH) return macapkDrawChildLegacy(canvas, child, drawingTime);
        if (canvas == null || child == null) return false;
        // Reached from our own dispatchDraw loop (possibly through an app override's
        // super.drawChild), or called directly by app/library code?
        final boolean fromDispatch = mMacapkInDispatchDraw;
        // FRAMESPEED S3: AOSP's quickReject (see macapkQuickRejectChild). Before the
        // save, like AOSP's test in the parent's space before its translate.
        if (MACAPK_FRAMESPEED_CULL && macapkQuickRejectChild(canvas, child, fromDispatch)) {
            sMacapkCulled++;
            return false;
        }
        int save = canvas.save();
        int dx = child.getLeft() - getScrollX(), dy = child.getTop() - getScrollY();
        if (dx != 0 || dy != 0) canvas.translate(dx, dy);
        // LEGACY TWEEN ANIMATION (View.startAnimation / R.anim): AOSP View.draw(Canvas,
        // ViewGroup, long):24761-24768. `child.getAnimation()` is one field read, so a
        // tree with no running animation pays that and nothing below it.
        android.view.animation.Animation childAnim =
                View.MACAPK_LEGACY_ANIM ? child.getAnimation() : null;
        float animAlpha = 1f;
        boolean animMore = false;
        if (childAnim != null) {
            // AOSP uses the caller's drawingTime (dispatchDraw passes ONE value to
            // every child, so siblings animating together see the same instant); a
            // direct caller that passes 0 still gets a real clock.
            long t0 = drawingTime > 0L ? drawingTime : getDrawingTime();
            try {
                animMore = child.applyLegacyAnimation(this, t0, childAnim, false);
                // FRAMESPEED S3: AOSP applyLegacyAnimation sets the PARENT's
                // PFLAG_DRAW_ANIMATION while the child wants more frames
                // (View.java:23452, :23465), which keeps THIS group out of its own
                // parent's quick-reject until the animation settles.
                if (animMore && MACAPK_FRAMESPEED_CULL) mMacapkDrawAnimation = true;
                android.view.animation.Transformation ct = getChildTransformation();
                // AOSP: translate(-transX,-transY); concat(t.getMatrix());
                // translate(transX,transY), transX/Y = -scrollX/-scrollY, so the
                // animation matrix applies about the child's own layout origin.
                if (childAnim.willChangeTransformationMatrix()) {
                    int sxo = getScrollX(), syo = getScrollY();
                    if (sxo != 0 || syo != 0) canvas.translate(-sxo, -syo);
                    canvas.concat(ct.getMatrix());
                    if (sxo != 0 || syo != 0) canvas.translate(sxo, syo);
                }
                animAlpha = ct.getAlpha();
            } catch (Throwable animErr) {
                com.macapk.loader.GfxTrace.caught(
                    "legacyAnim:" + child.getClass().getName(), animErr);
                childAnim = null;
            }
        }
        // Tier 3 M7: the child's own 2D transform, after the animation matrix
        // (AOSP View.java:24893-24900 order). Identity fast path.
        if (android.os.Looper.MACAPK_REAL_LOOPER && child.macapkHasTransform()) {
            child.macapkApplyTransform(canvas);
        }
        int cw = child.getRight()  - child.getLeft();
        int ch = child.getBottom() - child.getTop();
        // A 0-area child paints nothing visible: skip its draw (F-Droid's collapsed
        // LinearProgressIndicator NPEs in onDraw on empty bounds). It must still END
        // its animation, or an app waiting on onAnimationEnd hangs. DISPATCH ROUTE
        // ONLY: this was the inline loop's own deviation, and a direct caller --
        // Compose's DrawChildContainer, whose ViewLayers are drawn through
        // super.drawChild in a clipChildren=false container -- keeps exactly what
        // the old drawChild gave it (AOSP only quick-rejects under FLAG_CLIP_CHILDREN).
        if (fromDispatch && android.os.Looper.MACAPK_REAL_LOOPER && (cw <= 0 || ch <= 0)) {
            canvas.restoreToCount(save);
            if (childAnim != null && !animMore) finishAnimatingView(child, childAnim);
            return animMore;
        }
        // The per-child bounds clip IS the dispatchDraw contract (AOSP
        // FLAG_CLIP_CHILDREN, default on, ViewGroup.java:715-716).
        // MACAPK_CLIP_CHILDREN=0 is the historical escape hatch the inline loop read
        // (and the old drawChild did not).
        if ((!fromDispatch || MACAPK_CLIP_CHILDREN_ENV) && clipChildren && cw > 0 && ch > 0) {
            canvas.macapkClipChildRect(0, 0, cw, ch);
        }
        // Tier 3 M9: effective alpha = getAlpha() * getTransitionAlpha() * the
        // animation's alpha (AOSP View.java:24885-24890); zero = not drawn, partial =
        // one group-opacity layer so the subtree fades as a unit.
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            float a = child.getAlpha() * child.getTransitionAlpha() * animAlpha;
            if (a <= 0f) {
                canvas.restoreToCount(save);
                if (childAnim != null && !animMore) finishAnimatingView(child, childAnim);
                return animMore;
            }
            if (a < 1f && cw > 0 && ch > 0) {
                canvas.saveLayerAlpha(0, 0, cw, ch, (int) (a * 255f + 0.5f));
            }
        }
        // Resilient draw pass (lead A): one throwing child blanks only itself; the
        // restoreToCount returns the canvas to this child's save point either way.
        try {
            child.draw(canvas);
        } catch (Throwable drawErr) {
            com.macapk.loader.GfxTrace.caught((fromDispatch ? "dispatchDraw:" : "drawChild:")
                    + child.getClass().getName(), drawErr);
        }
        canvas.restoreToCount(save);
        // AOSP View.draw(Canvas, ViewGroup, long):24999-25004, after restoreTo:
        //     if (a != null && !more) {
        //         if (!hardwareAcceleratedCanvas && !a.getFillAfter()) onSetAlpha(255);
        //         parent.finishAnimatingView(this, a);
        //     }
        if (childAnim != null && !animMore) {
            if (!childAnim.getFillAfter()) {
                try { child.onSetAlpha(255); } catch (Throwable ignored) {}
            }
            finishAnimatingView(child, childAnim);
        }
        return animMore;
    }

    /** MACAPK_DRAWCHILD_DISPATCH=0: the pre-change drawChild body, verbatim. It was
     *  reached only when app code called drawChild itself, and returned a constant
     *  true. */
    private boolean macapkDrawChildLegacy(android.graphics.Canvas canvas, View child, long drawingTime) {
        if (canvas == null || child == null) return false;
        int save = canvas.save();
        int dx = child.getLeft() - getScrollX(), dy = child.getTop() - getScrollY();
        if (dx != 0 || dy != 0) canvas.translate(dx, dy);
        // Mirror dispatchDraw's legacy-animation pump (see there for the
        // contract and the zero-cost argument), so an app ViewGroup that
        // overrides dispatchDraw and calls drawChild() itself -- the AOSP
        // structure -- still runs its children's animations.
        android.view.animation.Animation childAnim =
                View.MACAPK_LEGACY_ANIM ? child.getAnimation() : null;
        float animAlpha = 1f;
        boolean animMore = false;
        if (childAnim != null) {
            // Here drawingTime is the caller's, exactly as in AOSP.
            long t0 = drawingTime > 0L ? drawingTime : getDrawingTime();
            try {
                animMore = child.applyLegacyAnimation(this, t0, childAnim, false);
                android.view.animation.Transformation ct = getChildTransformation();
                if (childAnim.willChangeTransformationMatrix()) {
                    int sxo = getScrollX(), syo = getScrollY();
                    if (sxo != 0 || syo != 0) canvas.translate(-sxo, -syo);
                    canvas.concat(ct.getMatrix());
                    if (sxo != 0 || syo != 0) canvas.translate(sxo, syo);
                }
                animAlpha = ct.getAlpha();
            } catch (Throwable animErr) {
                com.macapk.loader.GfxTrace.caught(
                    "legacyAnim:" + child.getClass().getName(), animErr);
                childAnim = null;
            }
        }
        // Mirror dispatchDraw's per-child M7 transform + M9 alpha so a code
        // path that calls drawChild() directly fades/transforms identically
        // (flag-gated; opaque/identity views and flag-off are unchanged).
        if (android.os.Looper.MACAPK_REAL_LOOPER && child.macapkHasTransform()) {
            child.macapkApplyTransform(canvas);
        }
        int cw = child.getRight()  - child.getLeft();
        int ch = child.getBottom() - child.getTop();
        if (clipChildren && cw > 0 && ch > 0) canvas.macapkClipChildRect(0, 0, cw, ch);
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            float a = child.getAlpha() * child.getTransitionAlpha() * animAlpha;
            if (a <= 0f) {
                canvas.restoreToCount(save);
                if (childAnim != null && !animMore) finishAnimatingView(child, childAnim);
                return true;
            }
            if (a < 1f && cw > 0 && ch > 0) {
                canvas.saveLayerAlpha(0, 0, cw, ch, (int) (a * 255f + 0.5f));
            }
        }
        // Resilient draw pass (lead A): same per-node guard as dispatchDraw so a
        // direct drawChild() call (RecyclerView item draw, custom ViewGroups)
        // isolates a throwing child instead of aborting the parent's draw.
        try { child.draw(canvas); }
        catch (Throwable drawErr) {
            com.macapk.loader.GfxTrace.caught("drawChild:" + child.getClass().getName(), drawErr);
        } finally { canvas.restoreToCount(save); }
        if (childAnim != null && !animMore) {
            if (!childAnim.getFillAfter()) {
                try { child.onSetAlpha(255); } catch (Throwable ignored) {}
            }
            finishAnimatingView(child, childAnim);
        }
        return true;
    }

    /** AOSP ViewGroup.drawableHotspotChanged: forwards hotspot change
     *  to all child drawable states. */
    @Override public void drawableHotspotChanged(float x, float y) {}
    public void dispatchDrawableHotspotChanged(float x, float y) {
        for (int i = 0; i < children.size(); i++) {
            View c = children.get(i);
            if (c != null) c.drawableHotspotChanged(x, y);
        }
    }

    // AOSP ViewGroup.drawableStateChanged: when a duplicateParentState child
    // armed the flag at addView, a state change on this group re-fires
    // refreshDrawableState() on exactly the children that opted in. This is the
    // ONLY propagation path by which checked / pressed / selected reaches a
    // duplicating child after that child's state cache was built; without it a
    // nav item's setChecked(true) never reaches its label or icon.
    //
    // The comment this replaces disabled the recursion on the worry that "our
    // tree state may trigger an unintended cascade". That worry belongs to the
    // setSelected/setActivated recursion noted below, which touches EVERY child;
    // this loop touches only children that explicitly asked to mirror the
    // parent, and before duplicateParentState was parsed at all no view could
    // have the flag, so it cannot reproduce the F-Droid cards regression.
    //
    // AOSP also throws here when FLAG_ADD_STATES_FROM_CHILDREN is set; ours
    // skips a duplicating child in addStatesFromChildren instead (see
    // onCreateDrawableState below), so there is nothing to check.
    private boolean macapkNotifyChildrenOnDrawableStateChange = false;
    @Override public void drawableStateChanged() {
        super.drawableStateChanged();
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return;
        if (!macapkNotifyChildrenOnDrawableStateChange) return;
        for (int i = 0; i < children.size(); i++) {
            View child = children.get(i);
            if (child != null && child.isDuplicateParentStateEnabled()) {
                try { child.refreshDrawableState(); } catch (Throwable ignored) {}
            }
        }
    }

    // Per-AOSP setSelected/setActivated SHOULD recurse, but doing so
    // regressed F-Droid's render (cards disappeared) -- adapter-side
    // setSelected on RV items must be propagating through something
    // unexpected. Leave the recursion disabled until isolated.

    /** AOSP ViewGroup.dispatchFreezeSelfOnly / ThawSelfOnly: skip
     *  children in the save/restore walk. */
    protected void dispatchFreezeSelfOnly(android.util.SparseArray<android.os.Parcelable> container) {}
    protected void dispatchThawSelfOnly(android.util.SparseArray<android.os.Parcelable> container) {}

    /** AOSP ViewGroup.dispatchProvideStructure: walk children + populate
     *  ViewStructure (Autofill / Accessibility). We don't autofill. */
    public void dispatchProvideStructure(Object structure) {}
    @Override public void dispatchProvideAutofillStructure(Object structure, int flags) {}

    /** AOSP ViewGroup.dispatchGenericFocusedEvent (android14-release
     *  ViewGroup.java:2566-2576): a non-pointer generic motion event (a
     *  joystick) goes to this group if IT is focused, else down the mFocused
     *  chain, else nowhere -- View.dispatchGenericMotionEvent then offers it to
     *  this group's own listener. AOSP also requires PFLAG_HAS_BOUNDS, which
     *  this tree does not keep; same reduction as dispatchKeyShortcutEvent. */
    @Override protected boolean dispatchGenericFocusedEvent(MotionEvent event) {
        if (mIsFocused) return super.dispatchGenericFocusedEvent(event);
        if (mFocused != null) return mFocused.dispatchGenericMotionEvent(event);
        return false;
    }

    /** AOSP ViewGroup.dispatchGenericPointerEvent (android14-release
     *  ViewGroup.java:2534-2563): a pointer generic event (a mouse wheel's
     *  ACTION_SCROLL) goes to the child UNDER THE POINTER, top-most first, in the
     *  child's own coordinates (dispatchTransformedGenericPointerEvent
     *  :2589-2604); if none handles it, this group's own View half runs (its
     *  listener, then onGenericMotionEvent), which is how a list nested in a
     *  scroller hands a wheel step it cannot use to the scroller around it.
     *  WheelScroll.VIEWS=0 keeps the old walk: every child in turn, untransformed,
     *  whatever the pointer position. */
    @Override protected boolean dispatchGenericPointerEvent(MotionEvent event) {
        if (!com.macapk.loader.WheelScroll.VIEWS) {
            for (int i = children.size() - 1; i >= 0; i--) {
                View c = children.get(i);
                if (c != null && c.dispatchGenericMotionEvent(event)) return true;
            }
            return super.dispatchGenericPointerEvent(event);
        }
        final int childrenCount = children.size();
        if (childrenCount != 0) {
            final float x = event.getX();
            final float y = event.getY();
            final java.util.ArrayList<View> preorderedList = buildOrderedChildList();
            final boolean customOrder = preorderedList == null
                    && isChildrenDrawingOrderEnabled();
            try {
                for (int i = childrenCount - 1; i >= 0; i--) {
                    final int childIndex =
                            getAndVerifyPreorderedIndex(childrenCount, i, customOrder);
                    final View child = getAndVerifyPreorderedView(preorderedList, childIndex);
                    if (child == null || !child.canReceivePointerEvents()
                            || !isTransformedTouchPointInView(x, y, child, null)) {
                        continue;
                    }
                    if (dispatchTransformedGenericPointerEvent(event, child)) return true;
                }
            } finally {
                if (preorderedList != null) preorderedList.clear();
            }
        }
        return super.dispatchGenericPointerEvent(event);
    }

    // ------------------------------------------------------------------
    // HOVER (GitHub #25, MACAPK_HOVER_EVENTS). AOSP ViewGroup.dispatchHoverEvent
    // (android14-release ViewGroup.java:2113-2300), ported: the group keeps a
    // list of hovered children (HoverTarget), sends a hover to the child under
    // the pointer top-most first, SYNTHESIZES an ENTER from a MOVE for a child
    // that was not hovered and an EXIT for each child the pointer has left, and
    // hovers itself only when no child handled the event. View.dispatchHoverEvent
    // used to answer false and this group had no override, so a hover never
    // reached any view.
    // ------------------------------------------------------------------
    private static final class HoverTarget {
        View child;
        HoverTarget next;
        HoverTarget(View child) { this.child = child; }
    }
    private HoverTarget mFirstHoverTarget;
    private boolean mHoveredSelf;

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        if (!View.HOVER_VIEWS) return super.dispatchHoverEvent(event);
        final int action = event.getAction();
        final boolean interceptHover = onInterceptHoverEvent(event);
        event.setAction(action);   // restore in case it was changed
        MotionEvent eventNoHistory = event;
        boolean handled = false;

        HoverTarget firstOldHoverTarget = mFirstHoverTarget;
        mFirstHoverTarget = null;
        if (!interceptHover && action != MotionEvent.ACTION_HOVER_EXIT) {
            final float x = event.getX();
            final float y = event.getY();
            final int childrenCount = children.size();
            if (childrenCount != 0) {
                final java.util.ArrayList<View> preorderedList = buildOrderedChildList();
                final boolean customOrder = preorderedList == null
                        && isChildrenDrawingOrderEnabled();
                HoverTarget lastHoverTarget = null;
                try {
                    for (int i = childrenCount - 1; i >= 0; i--) {
                        final int childIndex =
                                getAndVerifyPreorderedIndex(childrenCount, i, customOrder);
                        final View child = getAndVerifyPreorderedView(preorderedList, childIndex);
                        if (child == null || !child.canReceivePointerEvents()
                                || !isTransformedTouchPointInView(x, y, child, null)) {
                            continue;
                        }
                        // Reuse the child's target from the old list if it was hovered.
                        HoverTarget hoverTarget = firstOldHoverTarget;
                        final boolean wasHovered;
                        for (HoverTarget predecessor = null; ;) {
                            if (hoverTarget == null) {
                                hoverTarget = new HoverTarget(child);
                                wasHovered = false;
                                break;
                            }
                            if (hoverTarget.child == child) {
                                if (predecessor != null) predecessor.next = hoverTarget.next;
                                else firstOldHoverTarget = hoverTarget.next;
                                hoverTarget.next = null;
                                wasHovered = true;
                                break;
                            }
                            predecessor = hoverTarget;
                            hoverTarget = hoverTarget.next;
                        }
                        if (lastHoverTarget != null) lastHoverTarget.next = hoverTarget;
                        else mFirstHoverTarget = hoverTarget;
                        lastHoverTarget = hoverTarget;

                        if (action == MotionEvent.ACTION_HOVER_ENTER) {
                            if (!wasHovered) {
                                handled |= dispatchTransformedGenericPointerEvent(event, child);
                            }
                        } else if (action == MotionEvent.ACTION_HOVER_MOVE) {
                            if (!wasHovered) {
                                // Synthesize an enter from a move.
                                eventNoHistory = noHistory(eventNoHistory);
                                eventNoHistory.setAction(MotionEvent.ACTION_HOVER_ENTER);
                                handled |= dispatchTransformedGenericPointerEvent(eventNoHistory, child);
                                eventNoHistory.setAction(action);
                                handled |= dispatchTransformedGenericPointerEvent(eventNoHistory, child);
                            } else {
                                handled |= dispatchTransformedGenericPointerEvent(event, child);
                            }
                        }
                        if (handled) break;
                    }
                } finally {
                    if (preorderedList != null) preorderedList.clear();
                }
            }
        }

        // Exit every previously hovered child that is no longer hovered.
        while (firstOldHoverTarget != null) {
            final View child = firstOldHoverTarget.child;
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                handled |= dispatchTransformedGenericPointerEvent(event, child);
            } else {
                // Synthesize an exit from a move or enter; the result is ignored
                // because hover focus has moved to a different view.
                if (action == MotionEvent.ACTION_HOVER_MOVE) {
                    final boolean hoverExitPending = event.isHoverExitPending();
                    event.setHoverExitPending(true);
                    dispatchTransformedGenericPointerEvent(event, child);
                    event.setHoverExitPending(hoverExitPending);
                }
                eventNoHistory = noHistory(eventNoHistory);
                eventNoHistory.setAction(MotionEvent.ACTION_HOVER_EXIT);
                dispatchTransformedGenericPointerEvent(eventNoHistory, child);
                eventNoHistory.setAction(action);
            }
            firstOldHoverTarget = firstOldHoverTarget.next;
        }

        // Hover the group itself only when no child took the event and the group
        // is not itself being exited.
        final boolean newHoveredSelf = !handled
                && action != MotionEvent.ACTION_HOVER_EXIT && !event.isHoverExitPending();
        if (newHoveredSelf == mHoveredSelf) {
            if (newHoveredSelf) handled |= super.dispatchHoverEvent(event);
        } else {
            if (mHoveredSelf) {
                if (action == MotionEvent.ACTION_HOVER_EXIT) {
                    handled |= super.dispatchHoverEvent(event);
                } else {
                    if (action == MotionEvent.ACTION_HOVER_MOVE) super.dispatchHoverEvent(event);
                    eventNoHistory = noHistory(eventNoHistory);
                    eventNoHistory.setAction(MotionEvent.ACTION_HOVER_EXIT);
                    super.dispatchHoverEvent(eventNoHistory);
                    eventNoHistory.setAction(action);
                }
                mHoveredSelf = false;
            }
            if (newHoveredSelf) {
                if (action == MotionEvent.ACTION_HOVER_ENTER) {
                    handled |= super.dispatchHoverEvent(event);
                    mHoveredSelf = true;
                } else if (action == MotionEvent.ACTION_HOVER_MOVE) {
                    eventNoHistory = noHistory(eventNoHistory);
                    eventNoHistory.setAction(MotionEvent.ACTION_HOVER_ENTER);
                    handled |= super.dispatchHoverEvent(eventNoHistory);
                    eventNoHistory.setAction(action);
                    handled |= super.dispatchHoverEvent(eventNoHistory);
                    mHoveredSelf = true;
                }
            }
        }
        return handled;
    }
    /** AOSP obtainMotionEventNoHistoryOrSelf: a private copy the synthesized
     *  enter/exit actions can be written into without touching the caller's. */
    private static MotionEvent noHistory(MotionEvent e) {
        return e.macapkIsHoverCopy ? e : MotionEvent.macapkHoverCopy(e);
    }
    @Override protected boolean hasHoveredChild() {
        return View.HOVER_VIEWS && mFirstHoverTarget != null;
    }
    @Override protected boolean pointInHoveredChild(MotionEvent event) {
        return mFirstHoverTarget != null
                && isTransformedTouchPointInView(event.getX(), event.getY(), mFirstHoverTarget.child, null);
    }
    /** AOSP cancelHoverTarget (android14 :2311): a removed child that was hovered
     *  gets one HOVER_EXIT and leaves the list. */
    private void cancelHoverTarget(View view) {
        HoverTarget predecessor = null;
        HoverTarget target = mFirstHoverTarget;
        while (target != null) {
            final HoverTarget next = target.next;
            if (target.child == view) {
                if (predecessor == null) mFirstHoverTarget = next; else predecessor.next = next;
                final long now = android.os.SystemClock.uptimeMillis();
                MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_EXIT, 0f, 0f, 0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try { view.dispatchHoverEvent(event); } catch (Throwable ignored) {}
                return;
            }
            predecessor = target;
            target = next;
        }
    }

    /** AOSP :2589-2604, with the same child-offset rules as
     *  dispatchTransformedTouchEvent (macapkSkipOffset, the inverse matrix). */
    private boolean dispatchTransformedGenericPointerEvent(MotionEvent event, View child) {
        if (!child.macapkHasTransform()) {
            if (macapkSkipOffset(child)) return child.dispatchGenericMotionEvent(event);
            final float offsetX = getScrollX() - child.getLeft();
            final float offsetY = getScrollY() - child.getTop();
            event.offsetLocation(offsetX, offsetY);
            try { return child.dispatchGenericMotionEvent(event); }
            finally { event.offsetLocation(-offsetX, -offsetY); }
        }
        final MotionEvent transformedEvent = MotionEvent.obtain(event);
        try {
            if (!macapkSkipOffset(child)) {
                transformedEvent.offsetLocation(getScrollX() - child.getLeft(),
                                                getScrollY() - child.getTop());
            }
            android.graphics.Matrix inv = macapkChildInverseMatrix(child);
            if (inv != null) transformedEvent.transform(inv);
            return child.dispatchGenericMotionEvent(transformedEvent);
        } finally {
            transformedEvent.recycle();
        }
    }

    /** AOSP ViewGroup.getOverlay covariantly returns a ViewGroupOverlay. Apps
     *  and Material code cast the result to ViewGroupOverlay to add child
     *  Views (not just Drawables), so the declared return type must match. */
    private android.view.ViewGroupOverlay mViewGroupOverlay;
    @Override public android.view.ViewGroupOverlay getOverlay() {
        if (mViewGroupOverlay == null) mViewGroupOverlay = new android.view.ViewGroupOverlay(this);
        return mViewGroupOverlay;
    }
    @Override public int getBaseline() { return -1; }

    // dispatchSaveInstanceState / dispatchRestoreInstanceState
    // recursion also deferred until F-Droid render is back to v1.0.115
    // baseline.

    // ==================================================================
    // v1.0.686: the ViewGroup leaf-method tail (parallel to View's, v1.0.685).
    // Stored-and-returned where AOSP stores; faithful no-op / default where a
    // device without the feature does nothing. Source:
    // frameworks/base/core/java/android/view/ViewGroup.java
    // ==================================================================

    public static final int LAYOUT_MODE_CLIP_BOUNDS = 0;
    public static final int LAYOUT_MODE_OPTICAL_BOUNDS = 1;

    private int mLayoutMode = LAYOUT_MODE_CLIP_BOUNDS;
    public void setLayoutMode(int layoutMode) { mLayoutMode = layoutMode; }
    public int getLayoutMode() { return mLayoutMode; }

    private boolean mLayoutSuppressed;
    public void suppressLayout(boolean suppress) { mLayoutSuppressed = suppress; }
    public boolean isLayoutSuppressed() { return mLayoutSuppressed; }

    // AOSP ViewGroup FLAG_ADD_STATES_FROM_CHILDREN (android14 ViewGroup.java):
    //     public void setAddStatesFromChildren(boolean addsStates) {
    //         if (addsStates) mGroupFlags |= FLAG_ADD_STATES_FROM_CHILDREN;
    //         else mGroupFlags &= ~FLAG_ADD_STATES_FROM_CHILDREN;
    //         refreshDrawableState(); }
    //     protected int[] onCreateDrawableState(int extraSpace) {
    //         if ((mGroupFlags & FLAG_ADD_STATES_FROM_CHILDREN) == 0)
    //             return super.onCreateDrawableState(extraSpace);
    //         int need = 0; for each child: need += child.getDrawableState().length;
    //         int[] state = super.onCreateDrawableState(extraSpace + need);
    //         for each child: state = mergeDrawableStates(state, child.getDrawableState());
    //         return state; }
    //     public void childDrawableStateChanged(View child) {
    //         if ((mGroupFlags & FLAG_ADD_STATES_FROM_CHILDREN) != 0) refreshDrawableState(); }
    // The flag was stored and never read, so a TextInputLayout never saw its
    // EditText's state_focused: the outline stayed the idle grey and the
    // label kept the idle color while the field had focus. AOSP forbids a
    // duplicateParentState child under such a group (it throws); we skip
    // those children instead, which also keeps the two mirrors from recursing.
    private boolean mAddStatesFromChildren;
    private boolean mMacapkInChildStateChange;
    public void setAddStatesFromChildren(boolean addsStates) {
        mAddStatesFromChildren = addsStates;
        refreshDrawableState();
    }
    public boolean addStatesFromChildren() { return mAddStatesFromChildren; }
    @Override protected int[] onCreateDrawableState(int extraSpace) {
        if (!mAddStatesFromChildren || !android.os.Looper.MACAPK_REAL_LOOPER) {
            return super.onCreateDrawableState(extraSpace);
        }
        final int n = children.size();
        int need = 0;
        int[][] childStates = new int[n][];
        for (int i = 0; i < n; i++) {
            View c = children.get(i);
            if (c == null || c.isDuplicateParentStateEnabled()) continue;
            int[] cs = c.getDrawableState();
            childStates[i] = cs;
            if (cs != null) need += cs.length;
        }
        int[] state = super.onCreateDrawableState(extraSpace + need);
        for (int i = 0; i < n; i++) {
            if (childStates[i] != null) state = mergeDrawableStates(state, childStates[i]);
        }
        return state;
    }
    @Override public void childDrawableStateChanged(View child) {
        if (!mAddStatesFromChildren || mMacapkInChildStateChange) return;
        if (child != null && child.isDuplicateParentStateEnabled()) return;
        mMacapkInChildStateChange = true;
        try { refreshDrawableState(); } finally { mMacapkInChildStateChange = false; }
    }

    private boolean mTouchscreenBlocksFocus;
    public void setTouchscreenBlocksFocus(boolean touchscreenBlocksFocus) {
        mTouchscreenBlocksFocus = touchscreenBlocksFocus;
    }
    public boolean getTouchscreenBlocksFocus() { return mTouchscreenBlocksFocus; }

    /** AOSP ViewGroup.requestFocus(int, Rect) (android14 :3309-3341): dispatch
     *  on descendantFocusability -- BLOCK: this group only; BEFORE: this group,
     *  then its descendants; AFTER: descendants first, then this group.
     *
     *  ⚠ ViewGroup inherited View's leaf-only requestFocus, so asking a
     *  container for focus never reached a child: root.restoreDefaultFocus()
     *  (what leaving touch mode and an unfocused D-pad press call), a
     *  RecyclerView's or leanback grid's requestFocus(), and every app's
     *  `container.requestFocus()` returned false with nothing focused. Tab
     *  worked only because FocusFinder hands focus to a LEAF directly. */
    @Override
    public boolean requestFocus(int direction, android.graphics.Rect previouslyFocusedRect) {
        switch (getDescendantFocusability()) {
            case FOCUS_BLOCK_DESCENDANTS:
                return super.requestFocus(direction, previouslyFocusedRect);
            case FOCUS_AFTER_DESCENDANTS: {
                final boolean took = onRequestFocusInDescendants(direction, previouslyFocusedRect);
                return took || super.requestFocus(direction, previouslyFocusedRect);
            }
            case FOCUS_BEFORE_DESCENDANTS:
            default: {
                final boolean took = super.requestFocus(direction, previouslyFocusedRect);
                return took || onRequestFocusInDescendants(direction, previouslyFocusedRect);
            }
        }
    }

    /** AOSP ViewGroup.onRequestFocusInDescendants (android14 :3355-3381): offer
     *  focus to each VISIBLE child in order (reverse order unless the direction
     *  carries FOCUS_FORWARD's bit, which FOCUS_DOWN does), first taker wins.
     *  Subclasses (RecyclerView, ScrollView, leanback's BrowseFrameLayout and
     *  grids) override it. It returned false, the "do not intercept" reading of
     *  a hook whose base implementation IS the descent. */
    protected boolean onRequestFocusInDescendants(int direction,
            android.graphics.Rect previouslyFocusedRect) {
        final int count = children.size();
        int index, increment, end;
        if ((direction & FOCUS_FORWARD) != 0) {
            index = 0; increment = 1; end = count;
        } else {
            index = count - 1; increment = -1; end = -1;
        }
        for (int i = index; i != end; i += increment) {
            View child = (i < children.size()) ? children.get(i) : null;
            if (child != null && child.getVisibility() == VISIBLE
                    && child.requestFocus(direction, previouslyFocusedRect)) {
                return true;
            }
        }
        return false;
    }

    /** AOSP default: let the event through (return true) unless a subclass
     *  filters it. RecyclerView/AppBarLayout override to gate a11y events. */
    public boolean onRequestSendAccessibilityEvent(View child,
            android.view.accessibility.AccessibilityEvent event) {
        return true;
    }

    public void onWindowSystemUiVisibilityChanged(int visible) { }

    public void measureChildren(int widthMeasureSpec, int heightMeasureSpec) {
        for (int i = 0; i < children.size(); i++) {
            View child = children.get(i);
            if (child != null && child.getVisibility() != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    public void setChildrenDrawingCacheEnabled(boolean enabled) { }

    public void updateViewLayout(View view, LayoutParams params) {
        if (view == null) return;
        view.setLayoutParams(params);
        requestLayout();
    }

    // No cross-process a11y connection; AOSP populates the array from children
    // that are important for accessibility, which for us collapses to nothing.
    public void addChildrenForAccessibility(java.util.ArrayList<View> outChildren) { }

    // No ActionMode host in the Mac.apk window; AOSP returns null when no
    // ancestor starts one, which is our state.
    public android.view.ActionMode startActionModeForChild(View originalView,
            android.view.ActionMode.Callback callback) {
        return null;
    }
    public android.view.ActionMode startActionModeForChild(View originalView,
            android.view.ActionMode.Callback callback, int type) {
        return null;
    }

    public void dispatchProvideStructure(android.view.ViewStructure structure) { }

    // ======================================================================
    //  AOSP API-36 surface completion (Mac.apk API-gap lane, android.view).
    //  Members AOSP declares on ViewGroup that this file did not have. Bodies
    //  mirror frameworks/base core/java/android/view/ViewGroup.java
    //  (android16-release); where AOSP reads a private mGroupFlags bit the
    //  equivalent state is kept in a plain field here.
    // ======================================================================

    /** AOSP ViewGroup.attachLayoutAnimationParameters(View, LayoutParams, int,
     *  int):5500 -- verbatim. This is what fills in the AnimationParameters that
     *  LayoutAnimationController.getDelayForView reads. */
    protected void attachLayoutAnimationParameters(View child, LayoutParams params,
            int index, int count) {
        android.view.animation.LayoutAnimationController.AnimationParameters animationParams =
                params.layoutAnimationParameters;
        if (animationParams == null) {
            animationParams =
                new android.view.animation.LayoutAnimationController.AnimationParameters();
            params.layoutAnimationParameters = animationParams;
        }
        animationParams.count = count;
        animationParams.index = index;
    }

    /** AOSP ViewGroup.canAnimate():6477 `return mLayoutAnimationController != null`. */
    protected boolean canAnimate() { return mLayoutAnimationController != null; }

    /** AOSP ViewGroup.clearDisappearingChildren():7124 -- detaches and clears
     *  the children being kept alive only for their exit animation. This tree
     *  keeps no mDisappearingChildren list (see the "NOT MIRRORED" note on
     *  finishAnimatingView above: a child removed mid-animation is detached
     *  immediately rather than parked), so the list is always empty and AOSP's
     *  own `if (disappearingChildren != null)` guard is what fails. */
    public void clearDisappearingChildren() { }

    /** AOSP ViewGroup.getChildStaticTransformation(View, Transformation):4772
     *  `return false` -- a subclass overrides it to give each child a static
     *  alpha/matrix (Gallery, StackView). */
    protected boolean getChildStaticTransformation(View child,
            android.view.animation.Transformation t) {
        return false;
    }

    /** AOSP ViewGroup.setStaticTransformationsEnabled(boolean): opts the group
     *  into calling getChildStaticTransformation for every child. Recorded, and
     *  read back by the draw path if it ever honours static transformations;
     *  AOSP keeps it in FLAG_SUPPORT_STATIC_TRANSFORMATIONS. */
    private boolean mStaticTransformationsEnabled;
    protected void setStaticTransformationsEnabled(boolean enabled) {
        mStaticTransformationsEnabled = enabled;
    }

    /** AOSP ViewGroup.setChildrenDrawnWithCacheEnabled(boolean):6645 /
     *  isChildrenDrawnWithCacheEnabled():6623 -- FLAG_CHILDREN_DRAWN_WITH_CACHE.
     *  Deprecated at API 28 along with the drawing cache itself; a plain
     *  round-trip on AOSP too, since no cache is allocated any more. */
    private boolean mChildrenDrawnWithCache;
    @Deprecated
    protected void setChildrenDrawnWithCacheEnabled(boolean enabled) {
        mChildrenDrawnWithCache = enabled;
    }
    @Deprecated
    protected boolean isChildrenDrawnWithCacheEnabled() { return mChildrenDrawnWithCache; }

    /** AOSP ViewGroup.onInterceptHoverEvent(MotionEvent):2537 -- a group
     *  intercepts hover only to run its own scrollbar, and only for a MOUSE
     *  source. This tree allocates no ScrollBarDrawable (see
     *  View.setVerticalScrollbarThumbDrawable), so there is no scrollbar to hit
     *  and the answer is AOSP's `return false` fall-through. */
    public boolean onInterceptHoverEvent(MotionEvent event) { return false; }

    /** AOSP ViewGroup.propagateRequestedFrameRate(float, boolean):9563 -- pushes
     *  a requested frame rate down from a parent, marking it as NOT
     *  self-requested so a child's own setRequestedFrameRate still wins. Mac.apk
     *  presents at the CAMetalLayer's own rate and View.setRequestedFrameRate is
     *  already a stored hint with no consumer, so this stores it the same way
     *  for the whole subtree -- the observable behaviour of a fixed-refresh-rate
     *  device, which accepts every request and votes on none of them. */
    public void propagateRequestedFrameRate(float frameRate, boolean forceOverride) {
        setRequestedFrameRate(frameRate);
        final int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            if (child instanceof ViewGroup) {
                ((ViewGroup) child).propagateRequestedFrameRate(frameRate, forceOverride);
            } else if (child != null) {
                child.setRequestedFrameRate(frameRate);
            }
        }
    }

    /** AOSP ViewGroup.debug(int depth): the protected hierarchy dump ViewDebug
     *  drives. AOSP prints `<indent>ViewGroup { ... }` and recurses into every
     *  child; View.debug(int) is the leaf form. Nothing in this tree consumes
     *  the output (there is no view server -- see ViewDebug.dumpCapturedView),
     *  so it exists so a subclass's super-call resolves. */
    protected void debug(int depth) { }
}
