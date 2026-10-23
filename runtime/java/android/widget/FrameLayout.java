// android.widget.FrameLayout -- positions children at 0,0 by default.
// Most uses in our test corpus just use it as a single-child container
// for a SurfaceView/GLSurfaceView, so this trivial impl suffices.
package android.widget;

import android.content.Context;
import android.view.ViewGroup;

public class FrameLayout extends ViewGroup {
    public FrameLayout(Context c) { super(c); }
    public FrameLayout(Context c, android.util.AttributeSet a) { super(c, a); }
    public FrameLayout(Context c, android.util.AttributeSet a, int s) { super(c, a, s); }

    /** v1.0.125: AOSP `FrameLayout.onMeasure` -- measures every child
     *  and reports the max child size as the FrameLayout's own measured
     *  dimension. Per cs.android.com/.../widget/FrameLayout.java this
     *  is the load-bearing path for ANY widget that extends FrameLayout
     *  and inherits its onMeasure -- specifically MaterialCardView,
     *  CoordinatorLayout, and DrawerLayout. Without this, when RV's
     *  LayoutManager calls measureChildWithMargins on a card (passing
     *  UNSPECIFIED for hSpec because the LM wants natural-height), the
     *  card's onMeasure (= inherited View.onMeasure) just returns
     *  setMeasuredDimension(0, 0) and the LM stacks all cards at y=0.
     *
     *  Measure each child with the parent's spec; max them; account
     *  for the parent's own padding + suggested minimum; resolve via
     *  the MeasureSpec mode. For UNSPECIFIED parent (the RV's
     *  vertical-scroll path), the height reduces to "sum of child
     *  intrinsic heights" since `maxHeight` is unrestricted -- which
     *  for a MaterialCardView means "the inner ConstraintLayout's
     *  natural height" = sum of icon-row + name + summary heights.
     */
    /** AOSP FrameLayout.mMatchParentChildren (FrameLayout.java:82): the
     *  MATCH_PARENT children collected by the FIRST measure pass, re-measured
     *  at the container's now-known EXACT size by the second pass -- but ONLY
     *  when there is MORE THAN ONE of them (the `count > 1` gate at :230).
     *  A single MATCH_PARENT child in a wrap-content FrameLayout deliberately
     *  keeps its first-pass AT_MOST measurement, which is why a lone
     *  match_parent child of a wrap_content frame reports its CONTENT size
     *  rather than the frame's. */
    private final java.util.ArrayList<android.view.View> mMatchParentChildren =
            new java.util.ArrayList<>(1);

    /** AOSP FrameLayout.mForegroundPadding* (FrameLayout.java:68-80): the
     *  foreground drawable's own padding, harvested by setForegroundGravity
     *  when the gravity is FILL. Only ever non-zero for a FILL-gravity
     *  foreground that reports padding; everything else leaves these at 0 and
     *  the getPadding*WithForeground helpers reduce to the plain padding. */
    private int mForegroundPaddingLeft = 0;
    private int mForegroundPaddingTop = 0;
    private int mForegroundPaddingRight = 0;
    private int mForegroundPaddingBottom = 0;

    // AOSP View.isForegroundInsidePadding() reads mForegroundInfo.mInsidePadding,
    // whose default is true and whose only writer is the android:foregroundInsidePadding
    // XML attribute. Mac.apk's View has no ForegroundInfo record and the inflater
    // does not parse that attribute, so `true` is the only reachable state. Kept as
    // a named method so that a View that grows the real accessor can be wired in here
    // by changing one line instead of four call sites.
    private boolean isForegroundInsidePadding() { return true; }

    // AOSP FrameLayout.java:158-176. Measure and layout both work in the
    // foreground-aware content rect, not the plain padding rect.
    int getPaddingLeftWithForeground() {
        return isForegroundInsidePadding() ? Math.max(getPaddingLeft(), mForegroundPaddingLeft)
                : getPaddingLeft() + mForegroundPaddingLeft;
    }
    int getPaddingRightWithForeground() {
        return isForegroundInsidePadding() ? Math.max(getPaddingRight(), mForegroundPaddingRight)
                : getPaddingRight() + mForegroundPaddingRight;
    }
    int getPaddingTopWithForeground() {
        return isForegroundInsidePadding() ? Math.max(getPaddingTop(), mForegroundPaddingTop)
                : getPaddingTop() + mForegroundPaddingTop;
    }
    int getPaddingBottomWithForeground() {
        return isForegroundInsidePadding() ? Math.max(getPaddingBottom(), mForegroundPaddingBottom)
                : getPaddingBottom() + mForegroundPaddingBottom;
    }

    /** AOSP FrameLayout.setForegroundGravity (FrameLayout.java:123-147): after
     *  View normalises the gravity, a FILL foreground contributes ITS padding
     *  to the container's content rect; any other gravity clears it. */
    @Override public void setForegroundGravity(int foregroundGravity) {
        if (getForegroundGravity() != foregroundGravity) {
            super.setForegroundGravity(foregroundGravity);

            // Re-read: the super call may have applied the START/TOP defaults.
            final android.graphics.drawable.Drawable foreground = getForeground();
            if (getForegroundGravity() == android.view.Gravity.FILL && foreground != null) {
                android.graphics.Rect padding = new android.graphics.Rect();
                if (foreground.getPadding(padding)) {
                    mForegroundPaddingLeft = padding.left;
                    mForegroundPaddingTop = padding.top;
                    mForegroundPaddingRight = padding.right;
                    mForegroundPaddingBottom = padding.bottom;
                }
            } else {
                mForegroundPaddingLeft = 0;
                mForegroundPaddingTop = 0;
                mForegroundPaddingRight = 0;
                mForegroundPaddingBottom = 0;
            }

            requestLayout();
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = getChildCount();

        // AOSP FrameLayout.java:182-184. Only a non-EXACTLY axis can have a
        // MATCH_PARENT child whose first-pass measurement is provisional.
        final boolean measureMatchParentChildren =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY
                || MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY;
        mMatchParentChildren.clear();

        int maxWidth = 0;
        int maxHeight = 0;
        int childState = 0;

        // Measure each child. Pass through the parent's spec so EXACTLY
        // children get the parent's bounds and UNSPECIFIED children report
        // their natural size. measureAllChildren decides whether a GONE child
        // contributes to a wrap-content size (AOSP FrameLayout.java:193); note
        // that layoutChildren still skips GONE unconditionally, so a GONE child
        // measured here still gets a 0,0,0,0 frame.
        for (int i = 0; i < count; i++) {
            final android.view.View child = getChildAt(i);
            if (child == null) continue;
            if (mMeasureAllChildren || child.getVisibility() != android.view.View.GONE) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
                int marginLR = 0, marginTB = 0;
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                    marginLR = mlp.leftMargin + mlp.rightMargin;
                    marginTB = mlp.topMargin + mlp.bottomMargin;
                }
                maxWidth = Math.max(maxWidth, child.getMeasuredWidth() + marginLR);
                maxHeight = Math.max(maxHeight, child.getMeasuredHeight() + marginTB);
                childState = combineMeasuredStates(childState, child.getMeasuredState());
                if (measureMatchParentChildren && lp != null
                        && (lp.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            || lp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT)) {
                    mMatchParentChildren.add(child);
                }
            }
        }

        // Add the FrameLayout's own padding.
        maxWidth  += getPaddingLeftWithForeground() + getPaddingRightWithForeground();
        maxHeight += getPaddingTopWithForeground()  + getPaddingBottomWithForeground();

        // Floor at the suggested minimum (background-aware per AOSP).
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth  = Math.max(maxWidth,  getSuggestedMinimumWidth());

        // AOSP FrameLayout.java:218-223: the FOREGROUND's minimum also floors
        // the container. getSuggestedMinimum* only knows about the background,
        // so without this a frame whose only content is a large foreground
        // drawable measures to its (small) children and clips the foreground.
        final android.graphics.drawable.Drawable drawable = getForeground();
        if (drawable != null) {
            maxHeight = Math.max(maxHeight, drawable.getMinimumHeight());
            maxWidth = Math.max(maxWidth, drawable.getMinimumWidth());
        }

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));

        count = mMatchParentChildren.size();
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                final android.view.View child = mMatchParentChildren.get(i);
                final android.view.ViewGroup.LayoutParams glp = child.getLayoutParams();
                int mL = 0, mT = 0, mR = 0, mB = 0;
                if (glp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                    mL = mlp.leftMargin; mT = mlp.topMargin;
                    mR = mlp.rightMargin; mB = mlp.bottomMargin;
                }

                final int childWidthMeasureSpec;
                if (glp.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    final int width = Math.max(0, getMeasuredWidth()
                            - getPaddingLeftWithForeground() - getPaddingRightWithForeground()
                            - mL - mR);
                    childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
                } else {
                    childWidthMeasureSpec = getChildMeasureSpec(widthMeasureSpec,
                            getPaddingLeftWithForeground() + getPaddingRightWithForeground()
                            + mL + mR, glp.width);
                }

                final int childHeightMeasureSpec;
                if (glp.height == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    final int height = Math.max(0, getMeasuredHeight()
                            - getPaddingTopWithForeground() - getPaddingBottomWithForeground()
                            - mT - mB);
                    childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
                } else {
                    childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                            getPaddingTopWithForeground() + getPaddingBottomWithForeground()
                            + mT + mB, glp.height);
                }

                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }
    }

    /** v1.0.134: full AOSP FrameLayout.onLayout. Positions each child
     *  per its `layout_gravity` (default TOP|LEFT) with `layout_margin*`
     *  insets honored from the parent's content rectangle (padding
     *  subtracted). Mirrors `cs.android.com/.../widget/FrameLayout.java`
     *  `layoutChildren()`. Without honoring gravity + margins, Material's
     *  NavigationBarItemView (a FrameLayout subclass) couldn't position
     *  its icon_container (layout_gravity=center_horizontal, marginTop=8dp)
     *  separately from its BaselineLayout (layout_gravity=center|bottom),
     *  and both ended up at (0, 0). Result: icons and labels overlapped
     *  the top edge of the nav item. After this fix the icon sits at
     *  the top with its 8dp top margin, and the BaselineLayout sits at
     *  the bottom -- the M3 spec layout for a 56dp-tall nav item.
     *
     *  Pre-v1.0.134 behavior: every child laid out at (0, 0, cw, ch),
     *  ignoring gravity / margins entirely. The ViewCanvasDriver's
     *  applyLayoutGravity post-pass papered over some of the most
     *  visible cases (FAB bottom-right). With proper FrameLayout
     *  layout the post-pass becomes redundant for most cases. */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int frameW = r - l;
        int frameH = b - t;
        int parentLeft   = getPaddingLeftWithForeground();
        int parentTop    = getPaddingTopWithForeground();
        int parentRight  = frameW - getPaddingRightWithForeground();
        int parentBottom = frameH - getPaddingBottomWithForeground();
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            android.view.View c = getChildAt(i);
            if (c == null) continue;
            if (c.getVisibility() == android.view.View.GONE) continue;
            android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
            // AOSP FrameLayout.layoutChildren (FrameLayout.java:287-288) lays a
            // child out at its MEASURED size, full stop -- it never re-derives
            // the size from the LayoutParams. Mac.apk used to re-expand
            // MATCH_PARENT (and any child that measured 0) to the parent's
            // content rect here, which silently papered over measurement gaps
            // and, where measurement was already right, produced the wrong
            // frame for a correctly-measured child: the classic case is a lone
            // MATCH_PARENT child of a wrap-content FrameLayout, which AOSP
            // measures AT_MOST and leaves at its content size (see the
            // `count > 1` gate in onMeasure), and a ScrollView's content view,
            // which is measured UNSPECIFIED in the scroll axis on purpose and
            // must keep that (taller or shorter) height so scrolling works.
            final int cw = c.getMeasuredWidth();
            final int ch = c.getMeasuredHeight();
            int mL = 0, mT = 0, mR = 0, mB = 0;
            int gravity = -1; // UNSPECIFIED
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                mL = mlp.leftMargin;
                mT = mlp.topMargin;
                mR = mlp.rightMargin;
                mB = mlp.bottomMargin;
            }
            // FrameLayout.LayoutParams (or any subclass) exposes a
            // `gravity` int field; the macapkGravity surrogate is
            // also checked in case a non-FrameLayout LP was inflated
            // with android:layout_gravity (LayoutInflater stores it
            // on the base LayoutParams for cross-subclass access).
            try {
                java.lang.reflect.Field gf = findLpField(lp, "gravity");
                if (gf != null) {
                    Object gv = gf.get(lp);
                    if (gv instanceof Integer gi) gravity = gi;
                }
            } catch (Throwable ignored) {}
            if (gravity == -1) {
                try {
                    java.lang.reflect.Field gf = findLpField(lp, "macapkGravity");
                    if (gf != null) {
                        Object gv = gf.get(lp);
                        if (gv instanceof Integer gi && gi != 0) gravity = gi;
                    }
                } catch (Throwable ignored) {}
            }
            // Resolve gravity into x/y placement inside the content
            // rect, accounting for margins. AOSP FrameLayout treats
            // UNSPECIFIED_GRAVITY (-1) as DEFAULT_CHILD_GRAVITY which
            // is TOP|START (= TOP|LEFT in LTR).
            // AOSP: DEFAULT_CHILD_GRAVITY = TOP | START, and
            // Gravity.getAbsoluteGravity(gravity, getLayoutDirection()) -- so an
            // RTL FrameLayout puts an unspecified child at the top-RIGHT and a
            // bottom|end FAB at the bottom-LEFT. START/END used to be read as
            // LEFT/RIGHT unconditionally, leaving every such child on the LTR side.
            int g = (gravity == -1 || gravity == 0)
                ? (android.view.Gravity.TOP | android.view.Gravity.START) : gravity;
            g = android.view.Gravity.getAbsoluteGravity(g, getLayoutDirection());
            int horiz = g & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
            int vert  = g & android.view.Gravity.VERTICAL_GRAVITY_MASK;

            int childLeft;
            if (horiz == android.view.Gravity.CENTER_HORIZONTAL) {
                childLeft = parentLeft + (parentRight - parentLeft - cw) / 2 + mL - mR;
            } else if (horiz == android.view.Gravity.RIGHT) {
                childLeft = parentRight - cw - mR;
            } else { /* LEFT (default) */
                childLeft = parentLeft + mL;
            }
            int childTop;
            if (vert == android.view.Gravity.CENTER_VERTICAL) {
                childTop = parentTop + (parentBottom - parentTop - ch) / 2 + mT - mB;
            } else if (vert == android.view.Gravity.BOTTOM) {
                childTop = parentBottom - ch - mB;
            } else { /* TOP (default) */
                childTop = parentTop + mT;
            }
            c.layout(childLeft, childTop, childLeft + cw, childTop + ch);
        }
    }

    /** Walk the class hierarchy looking for a declared field. Used to
     *  read `gravity` off whatever LayoutParams subclass the APK
     *  attached -- FrameLayout.LayoutParams, CoordinatorLayout.LP,
     *  AppBarLayout.LP, MaterialCardView's wrapper LPs, etc. */
    private static java.lang.reflect.Field findLpField(Object owner, String name) {
        if (owner == null) return null;
        Class<?> c = owner.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    /** AOSP FrameLayout.generateDefaultLayoutParams returns
     *  MATCH_PARENT x MATCH_PARENT (frameworks/base/.../FrameLayout.java)
     *  -- NOT the WRAP_CONTENT default of ViewGroup. This is load-bearing
     *  for setContentView: AppCompat adds the activity's content root via
     *  `contentParent.addView(view)` with NO params, relying on the
     *  content FrameLayout's default to be MATCH_PARENT so the content
     *  fills the window. With the WRAP_CONTENT default, the content root
     *  (e.g. Reddit's DrawerLayout) got an AT_MOST measure spec, and
     *  DrawerLayout.onMeasure THROWS unless measured EXACTLY -- aborting
     *  the whole measure pass so the Compose subtree never measured. */
    @Override public FrameLayout.LayoutParams generateDefaultLayoutParams() {
        return new FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
    }
    // AOSP frameworks/base core/java/android/widget/FrameLayout.java: covariant
    // override narrowing ViewGroup.LayoutParams to FrameLayout.LayoutParams, same
    // as generateDefaultLayoutParams() two lines above already does correctly --
    // this one was left at the wider ViewGroup.LayoutParams descriptor despite
    // its body already constructing and returning the narrower type. Found by
    // debug/framework_lint.py's class-shape diff against android.jar.
    @Override public FrameLayout.LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public static final int UNSPECIFIED_GRAVITY = -1;
        public int gravity = UNSPECIFIED_GRAVITY;
        public LayoutParams() { super(); }
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, int gravity) { super(w, h); this.gravity = gravity; }
        public LayoutParams(ViewGroup.LayoutParams src) { super(src); }
        public LayoutParams(ViewGroup.MarginLayoutParams src) { super(src); }
        public LayoutParams(LayoutParams src) {
            super(src); this.gravity = src.gravity;
        }
        public LayoutParams(Context c, android.util.AttributeSet attrs) {}
    }

    public FrameLayout(Context c, android.util.AttributeSet a, int defStyleAttr, int defStyleRes) {
        this(c, a, defStyleAttr);
    }


    /** AOSP FrameLayout.generateLayoutParams(ViewGroup.LayoutParams): convert a
     *  foreign LayoutParams into a FrameLayout.LayoutParams.
     *
     *  ⚠ THIS USED TO BE `return lp;` -- an IDENTITY function. Combined with
     *  ViewGroup.checkLayoutParams being `return true` (so addView's
     *  `if (!checkLayoutParams(p)) p = generateLayoutParams(p)` guard never
     *  fired), a child added with a plain ViewGroup.LayoutParams KEPT it, and
     *  FrameLayout.onMeasure's cast to FrameLayout.LayoutParams then threw
     *  ClassCastException. Measured on Telegram 2026-08-24: it reached onResume
     *  and first frames, then died in onMeasure of DrawerLayoutContainer with
     *  exactly that CCE, and ViewCanvasDriver's layout block threw with it.
     *
     *  AOSP preserves margins on the conversion when the source has them
     *  (`sPreserveMarginParamsInLayoutParamConversion`, a targetSdk >= 24 gate;
     *  the pre-24 behaviour drops them). We do the modern thing unconditionally
     *  for now -- see docs/legacy-compat-audit_2026-08-24.md, which lists that
     *  flag among the missing gates; when it lands, gate this. */
    @Override
    protected android.view.ViewGroup.LayoutParams generateLayoutParams(
            android.view.ViewGroup.LayoutParams lp) {
        if (lp instanceof LayoutParams)   return new LayoutParams((LayoutParams) lp);
        if (lp instanceof android.view.ViewGroup.MarginLayoutParams)
            return new LayoutParams((android.view.ViewGroup.MarginLayoutParams) lp);
        return new LayoutParams(lp);
    }

    /** AOSP FrameLayout.checkLayoutParams: only OUR LayoutParams is acceptable.
     *  Without this override the base `return true` accepts anything and the
     *  conversion above is never reached -- which is why it sat unnoticed as an
     *  identity function. */
    @Override
    protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    private boolean mMeasureAllChildren;
    public void setMeasureAllChildren(boolean measureAll) { mMeasureAllChildren = measureAll; }
    public boolean getMeasureAllChildren() { return mMeasureAllChildren; }

    public boolean onInterceptHoverEvent(android.view.MotionEvent event) { return false; }
    public boolean onTrackballEvent(android.view.MotionEvent event) { return false; }

    /** AOSP FrameLayout.java:363 -- @Deprecated alias for getMeasureAllChildren(). */
    @Deprecated
    public boolean getConsiderGoneChildrenWhenMeasuring() { return getMeasureAllChildren(); }
}
