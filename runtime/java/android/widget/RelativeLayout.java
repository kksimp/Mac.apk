// android.widget.RelativeLayout -- positions children relative to
// each other and to the parent. Implements the AOSP RelativeLayout
// algorithm minus the heavy dependency-graph optimisation (we do a
// fixed-point sweep until no positions change, which is O(N*P) where
// P is the longest chain length; F-Droid's activity_main has chain
// depth 2 so this is trivially fast).
//
// Per the AOSP layout contract:
//   - Each child's mLeft/mTop/mRight/mBottom is computed from the
//     set of "rules" stored on its RelativeLayout.LayoutParams.
//   - Parent-relative rules (alignParentLeft etc.) are evaluated
//     against the RelativeLayout's own padded bounds.
//   - Sibling-relative rules (above/below/leftOf/rightOf/aligns)
//     reference another child by view ID; that child must already
//     have been positioned for the rule to evaluate.
//   - Centering rules (centerInParent/centerHorizontal/centerVertical)
//     are applied post-hoc using the child's measured size.
//
// Source for the constants and algorithm: AOSP
// frameworks/base/core/java/android/widget/RelativeLayout.java
// (web-fetched 2026-05-23 to mirror the rule indices and behavior).
//
// We don't implement layout-direction RTL resolution (ALIGN_START maps
// straight to ALIGN_LEFT, ALIGN_PARENT_END straight to
// ALIGN_PARENT_RIGHT) -- macOS doesn't have an RTL system pump and the
// F-Droid layouts we target are all LTR-anchored anyway.
package android.widget;

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import com.macapk.loader.NativeLog;

public class RelativeLayout extends ViewGroup {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_TRACE_RL = System.getenv("MACAPK_TRACE_RL");

    private static final String TAG = "Mac.apk.RelativeLayout";

    // Rule indices -- match AOSP's public constants exactly so apps
    // and androidx libraries that pass these through reflection /
    // SharedPreferences round-trip cleanly. Values 0..21 directly
    // index into LayoutParams.mRules.
    public static final int TRUE = -1;

    public static final int LEFT_OF             = 0;
    public static final int RIGHT_OF            = 1;
    public static final int ABOVE               = 2;
    public static final int BELOW               = 3;
    public static final int ALIGN_BASELINE      = 4;
    public static final int ALIGN_LEFT          = 5;
    public static final int ALIGN_TOP           = 6;
    public static final int ALIGN_RIGHT         = 7;
    public static final int ALIGN_BOTTOM        = 8;
    public static final int ALIGN_PARENT_LEFT   = 9;
    public static final int ALIGN_PARENT_TOP    = 10;
    public static final int ALIGN_PARENT_RIGHT  = 11;
    public static final int ALIGN_PARENT_BOTTOM = 12;
    public static final int CENTER_IN_PARENT    = 13;
    public static final int CENTER_HORIZONTAL   = 14;
    public static final int CENTER_VERTICAL     = 15;
    public static final int START_OF            = 16;
    public static final int END_OF              = 17;
    public static final int ALIGN_START         = 18;
    public static final int ALIGN_END           = 19;
    public static final int ALIGN_PARENT_START  = 20;
    public static final int ALIGN_PARENT_END    = 21;

    private static final int VERB_COUNT         = 22;

    public RelativeLayout(Context c) { super(c); }
    public RelativeLayout(Context c, android.util.AttributeSet a) { super(c, a); }
    public RelativeLayout(Context c, android.util.AttributeSet a, int s) { super(c, a, s); }

    // v1.0.841: MEASURED root cause of Organic Maps' DownloadResourcesLegacyActivity
    // rendering as two content-blind 50/50 vertical slices (docs/maps-calculator_
    // 2026-07-31.md). Without this override, RelativeLayout inherited the base
    // ViewGroup.generateLayoutParams(AttributeSet), which is `return new
    // LayoutParams();` -- a bare ViewGroup.LayoutParams that ignores its argument
    // entirely. LayoutInflater.applyIncludeOverrides (v1.0.115, added for
    // ConstraintLayout's benefit) calls parent.generateLayoutParams(siteAttrs) and
    // uses the result UNCONDITIONALLY when non-null, so ANY `<include ...
    // android:layout_above|below|alignParentBottom|... />` whose include SITE is a
    // direct child of a RelativeLayout got its RelativeLayout-specific rules
    // silently discarded: the replacement LP was not `instanceof RelativeLayout
    // .LayoutParams`, so applyLayoutAttribute's `if (lp instanceof RelativeLayout
    // .LayoutParams rlp)` dispatch (LayoutInflater.java ~898) never ran, AND
    // RelativeLayout's own onLayout/solveBoundsForMeasure casts
    // `(lp instanceof LayoutParams) ? (LayoutParams) baseLp : null` also failed,
    // leaving `rules = EMPTY_RULES`. A rule-less, non-match-parent child can never
    // be positioned by the per-child solver (see the `hasAnyRule`/`fillW||fillH`
    // gate below), so it -- and any sibling that depends on it via layout_above/
    // below/etc, a chain that can never resolve either -- fall through to the
    // "remaining > 0" equal-vertical-stack emergency fallback (~line 869 below),
    // which ignores real content and splits the container into N equal slices.
    // Measured on activity_download_resources.xml's `<include android:id=
    // "@id/button_container" android:layout_alignParentBottom="true" .../>`:
    // parentH=1748, 2 children -> exactly the observed 874/874 split.
    //
    // Fix mirrors FrameLayout's OWN already-shipping, already-swept pattern
    // (FrameLayout.java: `generateLayoutParams(AttributeSet)` -> `new
    // LayoutParams(getContext(), attrs)`, whose constructor is ALSO an empty stub
    // -- see LayoutParams(Context, AttributeSet) below): the important thing is
    // returning the CORRECT RUNTIME TYPE, not that the constructor itself parses
    // attrs. Real value population already happens via a SEPARATE, existing,
    // already-correct attribute walk (LayoutInflater.applyLayoutAttribute /
    // applyRelativeLayoutAttribute, and applyIncludeOverrides's own attrs loop),
    // which requires exactly this `instanceof RelativeLayout.LayoutParams` to be
    // true to do its job. `LayoutInflater.buildLayoutParams` (the NORMAL,
    // non-<include> path) already special-cases "did generateLayoutParams return
    // a MEANINGFUL (non-base) subclass" and prefers it when so -- verified this is
    // SAFE for existing direct RelativeLayout children: whichever constructor
    // builds the RelativeLayout.LayoutParams skeleton (this one, or
    // buildLayoutParams's own hardcoded `new LayoutParams(WRAP_CONTENT,
    // WRAP_CONTENT)` fallback), the SAME subsequent attribute walk populates
    // width/height/rules from the element's own XML either way -- so this is
    // strictly additive for the <include>-inside-RelativeLayout case and a no-op
    // in outcome for the direct-child case (only the intermediate skeleton
    // constructor differs, never observable post-attribute-walk).
    @Override
    public LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    /** AOSP: "since RelativeLayout considers the positioning of each child
     *  relative to one another to be significant, setting gravity will
     *  affect the positioning of all children as a single unit within the
     *  parent. This happens after children have been relatively
     *  positioned" -- applied as a group post-pass at the end of onLayout
     *  below, not per-child. AdMob's banner-container subclass calls
     *  setGravity(...) directly on the RelativeLayout; a plain "put
     *  everything at the bottom" RelativeLayout relies on the same
     *  mechanism.
     *  Source (fetched 2026-07-22, quoted verbatim):
     *  frameworks/base/core/java/android/widget/RelativeLayout.java
     *  mGravity field + getGravity/setGravity/setHorizontalGravity/
     *  setVerticalGravity. */
    private int mGravity = Gravity.START | Gravity.TOP;

    public int getGravity() {
        return mGravity;
    }

    public void setGravity(int gravity) {
        if (mGravity != gravity) {
            if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
                gravity |= Gravity.START;
            }
            if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
                gravity |= Gravity.TOP;
            }
            mGravity = gravity;
            requestLayout();
        }
    }

    public void setHorizontalGravity(int horizontalGravity) {
        final int gravity = horizontalGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        if ((mGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) != gravity) {
            mGravity = (mGravity & ~Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) | gravity;
            requestLayout();
        }
    }

    public void setVerticalGravity(int verticalGravity) {
        final int gravity = verticalGravity & Gravity.VERTICAL_GRAVITY_MASK;
        if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != gravity) {
            mGravity = (mGravity & ~Gravity.VERTICAL_GRAVITY_MASK) | gravity;
            requestLayout();
        }
    }

    /** AOSP RelativeLayout.mBaselineView (RelativeLayout.java:210,
     *  android11-release): the non-GONE child this container reports ITS OWN
     *  baseline through. Selected in onMeasure below as the "top-start-most"
     *  positioned child -- see the selection loop right after the Phase 2
     *  solveBoundsForMeasure call. */
    private View mBaselineView;

    /** AOSP RelativeLayout.java:373-376, verbatim. TRAP: delegate to the
     *  chosen child's OWN getBaseline() -- never recompute font metrics
     *  here (View.getBaseline() already does the padding-aware ascent math
     *  since v1.0.937). */
    @Override
    public int getBaseline() {
        return mBaselineView != null ? mBaselineView.getBaseline() : super.getBaseline();
    }

    /** AOSP RelativeLayout.LayoutParams holds a 22-int array, one slot
     *  per rule verb. Slot value semantics:
     *    - 0  = rule not set
     *    - -1 = "true" (parent-relative rules: alignParentTop, etc.,
     *           and centering rules don't take a subject)
     *    - other = the resource ID of the sibling view this rule
     *           references (above/below/alignLeft/etc.).
     *
     *  AOSP separates "user rules" from "resolved rules" for RTL; here
     *  mRules is the resolved array and mInitialRules keeps the rules as
     *  set (see resolveLayoutDirection). */
    /** v1.0.1643: AOSP checkLayoutParams, so addView converts a foreign
     *  LayoutParams into this container's own kind (ViewGroup.addView ->
     *  generateLayoutParams). Gate MACAPK_CHECK_LAYOUT_PARAMS=0 restores the old
     *  accept-anything answer for an A/B. */
    @Override
    protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams p) {
        if (!MACAPK_CHECK_LP) return true;
        return p instanceof RelativeLayout.LayoutParams;
    }
    private static final boolean MACAPK_CHECK_LP =
        !"0".equals(System.getenv("MACAPK_CHECK_LAYOUT_PARAMS"));
    /** AOSP RelativeLayout.generateLayoutParams(ViewGroup.LayoutParams)
     *  (android14 :1130-1139): keep margins when the source has them. */
    @Override
    protected android.view.ViewGroup.LayoutParams generateLayoutParams(android.view.ViewGroup.LayoutParams lp) {
        if (android.view.View.sPreserveMarginParamsInLayoutParamConversion
                && lp instanceof android.view.ViewGroup.MarginLayoutParams) {
            return new LayoutParams((android.view.ViewGroup.MarginLayoutParams) lp);
        }
        return new LayoutParams(lp);
    }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        private final int[] mRules = new int[VERB_COUNT];

        public LayoutParams() { super(); }
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(ViewGroup.LayoutParams src) { super(src); }
        public LayoutParams(ViewGroup.MarginLayoutParams src) { super(src); }

        /** AOSP RelativeLayout.LayoutParams(LayoutParams source) --
         *  RelativeLayout.java:1414, android14-release:
         *      super(source);
         *      this.mIsRtlCompatibilityMode = source.mIsRtlCompatibilityMode;
         *      this.mRulesChanged = source.mRulesChanged;
         *      this.alignWithParent = source.alignWithParent;
         *      System.arraycopy(source.mRules, LEFT_OF, this.mRules, LEFT_OF, VERB_COUNT);
         *      System.arraycopy(source.mInitialRules, LEFT_OF, this.mInitialRules, LEFT_OF, VERB_COUNT);
         *
         *  Absent here until the 2026-08-27 fleet census turned it up on Signal
         *  (`RelativeLayout$LayoutParams.<init>(Landroid/widget/RelativeLayout$LayoutParams;)V`).
         *  It is NOT covered by the ViewGroup.LayoutParams / MarginLayoutParams
         *  overloads above: Java picks the overload at COMPILE time, so an app
         *  compiled against the real SDK emits that exact descriptor and gets a
         *  NoSuchMethodError from us -- and even if it had bound to the
         *  MarginLayoutParams overload it would have silently dropped every
         *  positioning RULE, which is the entire point of the class.
         *
         *  We collapse AOSP's mRules/mInitialRules pair into one array (see the
         *  class comment above -- LTR only), so there is one arraycopy, and
         *  mRulesChanged has no counterpart here. alignWithParent is copied. */
        public LayoutParams(LayoutParams source) {
            super(source);
            this.mIsRtlCompatibilityMode = source.mIsRtlCompatibilityMode;
            this.alignWithParent = source.alignWithParent;
            System.arraycopy(source.mRules, 0, this.mRules, 0, VERB_COUNT);
            if (source.mInitialRules != null) this.mInitialRules = source.mInitialRules.clone();
        }
        public LayoutParams(Context c, android.util.AttributeSet attrs) {
            final int targetSdkVersion = macapkTargetSdkOf(c);
            mIsRtlCompatibilityMode = android.app.Application.macapkTargetSdkKnown(targetSdkVersion)
                    && targetSdkVersion < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
        }

        /** AOSP RelativeLayout.LayoutParams.mIsRtlCompatibilityMode
         *  (RelativeLayout.java:1301-1303, android16-release):
         *      final int targetSdkVersion = c.getApplicationInfo().targetSdkVersion;
         *      mIsRtlCompatibilityMode = (targetSdkVersion < JELLY_BEAN_MR1 ||
         *              !c.getApplicationInfo().hasRtlSupport());
         *  It selects between the two halves of resolveRules (:1556 vs :1610).
         *  Unresolved targetSdk (0) takes the MODERN half, matching the
         *  convention on android.app.Application.macapkAppTargetSdk -- note this
         *  is the opposite fallback from View.isRtlCompatibilityMode, which
         *  reads a bare 0 as legacy; that inconsistency is inventoried in
         *  docs/legacy-compat-audit_2026-08-24.md 5.3 and is not resolved here.
         *
         *  DELIBERATE PARTIAL: AOSP's condition also ORs `!hasRtlSupport()`, and
         *  that term is NOT applied here. `android:supportsRtl` defaults to
         *  FALSE (MacApkPackageManager.java:3488, matching PackageParser), so
         *  including it would put EVERY app that merely omits the attribute --
         *  most of the corpus, at any targetSdk -- onto the legacy half. That is
         *  AOSP-correct but it is not a targetSdk gate, it changes modern apps,
         *  and it belongs to whoever owns RTL support end to end. The targetSdk
         *  half alone cannot touch an app at or above 17. */
        private boolean mIsRtlCompatibilityMode = false;

        private static int macapkTargetSdkOf(Context c) {
            try {
                android.content.pm.ApplicationInfo ai =
                        (c != null) ? c.getApplicationInfo() : null;
                if (ai != null && ai.targetSdkVersion > 0) return ai.targetSdkVersion;
            } catch (Throwable ignored) { }
            return 0;
        }


        /** AOSP RelativeLayout.LayoutParams.alignWithParent -- a PUBLIC FIELD
         *  (not a rule): "when true, uses the parent as the anchor if the
         *  anchor doesn't exist or if the anchor's visibility is GONE". */
        public boolean alignWithParent;

        /** AOSP RelativeLayout.java:1427. Note AOSP's own string says
         *  "ViewGroup.LayoutParams={...}", not "RelativeLayout..." -- kept
         *  verbatim, since hierarchy-dump consumers match on it. */
        public String debug(String output) {
            return output + "ViewGroup.LayoutParams={ width=" + macapkSizeToString(width)
                    + ", height=" + macapkSizeToString(height) + " }";
        }

        /** AOSP ViewGroup.LayoutParams.sizeToString(int), inlined -- this
         *  tree's ViewGroup.LayoutParams does not expose it. */
        static String macapkSizeToString(int size) {
            if (size == WRAP_CONTENT) return "wrap-content";
            if (size == MATCH_PARENT) return "match-parent";
            return String.valueOf(size);
        }

        /** addRule(verb): mark a parent-relative or centering rule
         *  with value TRUE (no subject needed). */
        public void addRule(int verb) {
            if (verb >= 0 && verb < VERB_COUNT) {
                mRules[verb] = TRUE;
                if (mInitialRules != null) mInitialRules[verb] = TRUE;
            }
        }
        /** addRule(verb, subject): the subject is either TRUE (-1) for
         *  parent-relative / centering, or a sibling view's ID for
         *  relational rules. */
        public void addRule(int verb, int subject) {
            if (verb >= 0 && verb < VERB_COUNT) {
                mRules[verb] = subject;
                if (mInitialRules != null) mInitialRules[verb] = subject;
            }
        }
        public void removeRule(int verb) {
            if (verb >= 0 && verb < VERB_COUNT) {
                mRules[verb] = 0;
                if (mInitialRules != null) mInitialRules[verb] = 0;
            }
        }

        /** AOSP mInitialRules: the rules as the app set them, before START/END
         *  were folded into LEFT/RIGHT for a direction. Snapshotted on the first
         *  resolution, so each resolution starts from the app's rules again and
         *  a direction change re-folds instead of stacking on the last fold. */
        private int[] mInitialRules;
        public int getRule(int verb) {
            return (verb >= 0 && verb < VERB_COUNT) ? mRules[verb] : 0;
        }
        public int[] getRules() { return mRules; }
        public int[] getRules(int layoutDirection) { return mRules; }
        public void resolveLayoutDirection(int layoutDirection) {
            // AOSP calls super first: start/end MARGINS resolve here too. This
            // override used to skip it, so a RelativeLayout child's
            // layout_marginStart/End never followed the direction.
            super.resolveLayoutDirection(layoutDirection);
            if (mInitialRules == null) mInitialRules = mRules.clone();
            System.arraycopy(mInitialRules, 0, mRules, 0, VERB_COUNT);
            // ALIGN_START -> ALIGN_LEFT, ALIGN_PARENT_START ->
            // ALIGN_PARENT_LEFT (and their END counterparts to RIGHT).
            //
            // AOSP splits this into two halves on mIsRtlCompatibilityMode
            // (resolveRules, RelativeLayout.java:1556 vs :1610). Under LTR the
            // only difference is PRECEDENCE: in compatibility mode a start/end
            // rule fills in a left/right rule ONLY when the absolute rule is
            // unset ("left rule is not defined but start rule is"), whereas from
            // JELLY_BEAN_MR1 the relative rule takes precedence and OVERWRITES
            // it. The modern branch below is that half, RTL included.
            if (mIsRtlCompatibilityMode) {
                if (mRules[ALIGN_START] != 0 && mRules[ALIGN_LEFT] == 0) {
                    mRules[ALIGN_LEFT] = mRules[ALIGN_START];
                }
                if (mRules[ALIGN_END] != 0 && mRules[ALIGN_RIGHT] == 0) {
                    mRules[ALIGN_RIGHT] = mRules[ALIGN_END];
                }
                if (mRules[ALIGN_PARENT_START] != 0 && mRules[ALIGN_PARENT_LEFT] == 0) {
                    mRules[ALIGN_PARENT_LEFT] = mRules[ALIGN_PARENT_START];
                }
                if (mRules[ALIGN_PARENT_END] != 0 && mRules[ALIGN_PARENT_RIGHT] == 0) {
                    mRules[ALIGN_PARENT_RIGHT] = mRules[ALIGN_PARENT_END];
                }
                if (mRules[START_OF] != 0 && mRules[LEFT_OF] == 0) {
                    mRules[LEFT_OF] = mRules[START_OF];
                }
                if (mRules[END_OF] != 0 && mRules[RIGHT_OF] == 0) {
                    mRules[RIGHT_OF] = mRules[END_OF];
                }
                // AOSP clears the relative slots after folding them; ours never
                // has, and changing that would alter getRule(ALIGN_START) for
                // every app, so it is left alone deliberately.
                return;
            }
            // AOSP resolveRules, JELLY_BEAN_MR1+ half (RelativeLayout.java:1610):
            // a start/end rule wins over a left/right one on the same axis (the
            // absolute pair is dropped), and START maps to RIGHT under RTL. This
            // was LTR-only, so in a Hebrew UI every alignParentStart / toEndOf
            // layout came out unmirrored -- WhatsApp's conversation header put
            // the back arrow and avatar on the left and the name to their right.
            // The relative slots are kept (getRule(START_OF) still answers), as
            // before; the positioner reads only the absolute ones.
            final boolean rtl = layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL;
            if ((mRules[ALIGN_START] != 0 || mRules[ALIGN_END] != 0)
                    && (mRules[ALIGN_LEFT] != 0 || mRules[ALIGN_RIGHT] != 0)) {
                mRules[ALIGN_LEFT] = 0;
                mRules[ALIGN_RIGHT] = 0;
            }
            if (mRules[ALIGN_START] != 0) mRules[rtl ? ALIGN_RIGHT : ALIGN_LEFT] = mRules[ALIGN_START];
            if (mRules[ALIGN_END] != 0)   mRules[rtl ? ALIGN_LEFT : ALIGN_RIGHT] = mRules[ALIGN_END];
            if ((mRules[START_OF] != 0 || mRules[END_OF] != 0)
                    && (mRules[LEFT_OF] != 0 || mRules[RIGHT_OF] != 0)) {
                mRules[LEFT_OF] = 0;
                mRules[RIGHT_OF] = 0;
            }
            if (mRules[START_OF] != 0) mRules[rtl ? RIGHT_OF : LEFT_OF] = mRules[START_OF];
            if (mRules[END_OF] != 0)   mRules[rtl ? LEFT_OF : RIGHT_OF] = mRules[END_OF];
            if ((mRules[ALIGN_PARENT_START] != 0 || mRules[ALIGN_PARENT_END] != 0)
                    && (mRules[ALIGN_PARENT_LEFT] != 0 || mRules[ALIGN_PARENT_RIGHT] != 0)) {
                mRules[ALIGN_PARENT_LEFT] = 0;
                mRules[ALIGN_PARENT_RIGHT] = 0;
            }
            if (mRules[ALIGN_PARENT_START] != 0)
                mRules[rtl ? ALIGN_PARENT_RIGHT : ALIGN_PARENT_LEFT] = mRules[ALIGN_PARENT_START];
            if (mRules[ALIGN_PARENT_END] != 0)
                mRules[rtl ? ALIGN_PARENT_LEFT : ALIGN_PARENT_RIGHT] = mRules[ALIGN_PARENT_END];
        }
    }

    /** AOSP-faithful onMeasure. The base ViewGroup.onMeasure measures every
     *  child at the FULL parent size (EXACTLY when the parent is EXACTLY), which
     *  blows a wrap_content child up to fill the whole RelativeLayout and makes
     *  a wrap_content RelativeLayout report the full parent size instead of its
     *  content union. AOSP instead: (1) measures each child against a spec
     *  derived from its OWN LayoutParams (AT_MOST for wrap_content); (2) positions
     *  children by their rules in measure space; (3) for a wrap_content container,
     *  sets the measured size to the union of child rects; (4) re-measures
     *  match_parent / double-anchored children EXACTLY at their resolved span so
     *  they fill correctly. We keep onLayout (the proven positioner) untouched and
     *  run a dedicated measure-space solver here.
     *  Source: frameworks/base/core/java/android/widget/RelativeLayout.java
     *  onMeasure (web-fetched contract: myWidth=-1 when UNSPECIFIED; container
     *  wrap size = max child right/bottom + padding clamped to the spec). */
    /** The app's OWN declared targetSdkVersion, or the MODERN answer
     *  (Integer.MAX_VALUE) when it cannot be read yet. Never latched, never
     *  guessed backwards -- see android.app.Application.macapkAppTargetSdk for
     *  the convention every compat gate in this tree cites. */
    private int macapkTargetSdk() {
        try {
            android.content.Context c = getContext();
            android.content.pm.ApplicationInfo ai =
                    (c != null) ? c.getApplicationInfo() : null;
            if (ai != null && android.app.Application.macapkTargetSdkKnown(ai.targetSdkVersion)) return ai.targetSdkVersion;
        } catch (Throwable ignored) { }
        return Integer.MAX_VALUE;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        final int widthMode  = android.view.View.MeasureSpec.getMode(wSpec);
        final int heightMode = android.view.View.MeasureSpec.getMode(hSpec);
        final int widthSize  = android.view.View.MeasureSpec.getSize(wSpec);
        final int heightSize = android.view.View.MeasureSpec.getSize(hSpec);

        final int padLeft   = getPaddingLeft();
        final int padTop    = getPaddingTop();
        final int padRight  = getPaddingRight();
        final int padBottom = getPaddingBottom();

        // AOSP: myWidth/myHeight is the available extent for positioning
        // parent-relative rules; -1 (unknown) when the parent spec is UNSPECIFIED.
        final int myWidth  = (widthMode  == android.view.View.MeasureSpec.UNSPECIFIED) ? -1 : widthSize;
        final int myHeight = (heightMode == android.view.View.MeasureSpec.UNSPECIFIED) ? -1 : heightSize;

        final int count = getChildCount();

        // Phase 1: initial measure of every child from its own LayoutParams, so a
        // wrap_content child shrinks to content (AT_MOST) instead of filling.
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) continue;
            if (child.getLayoutParams() instanceof LayoutParams lp) lp.resolveLayoutDirection(getLayoutDirection());
            measureChildWithMargins(child, wSpec, 0, hSpec, 0);
        }

        // Phase 2: position children by their rules in measure space. The solver
        // ALSO re-measures each child against its rule-resolved span, which is
        // what AOSP's onMeasure loop does (see solveBoundsForMeasure).
        final int[] ml = new int[count], mt = new int[count], mr = new int[count], mb = new int[count];
        final boolean[] inv = new boolean[count];
        solveBoundsForMeasure(count, myWidth < 0 ? 0 : myWidth, myHeight < 0 ? 0 : myHeight,
                myWidth, myHeight,
                padLeft, padTop, padRight, padBottom, ml, mt, mr, mb, inv,
                widthMode  != android.view.View.MeasureSpec.EXACTLY,
                heightMode != android.view.View.MeasureSpec.EXACTLY);

        // AOSP RelativeLayout.java:531-555 (android11-release): the
        // "top-start-most" positioned non-GONE child becomes this
        // container's OWN baseline provider (getBaseline() above).
        // compareLayoutPosition (:667-672): smallest top wins, ties broken
        // by smallest left. Uses the measure-space mt[]/ml[] the solver just
        // wrote, matching AOSP's own call site (onMeasure, using the
        // params.mTop/mLeft the solver just set on the same pass). Runs
        // unconditionally, every onMeasure call, so a container that loses
        // all its children also loses mBaselineView (matches AOSP: the local
        // starts null and is assigned unconditionally at the end either way).
        //
        // NOTE: iterates in getChildAt() order rather than AOSP's
        // dependency-sorted mSortedVerticalChildren. Only observable on an
        // exact top+left tie between two children -- which the rest of this
        // solver does not otherwise disambiguate either, since it has no
        // equivalent of AOSP's dependency graph to begin with.
        View baselineView = null;
        int baselineTop = 0, baselineLeft = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) continue;
            if (baselineView == null || mt[i] < baselineTop
                    || (mt[i] == baselineTop && ml[i] < baselineLeft)) {
                baselineView = child;
                baselineTop = mt[i];
                baselineLeft = ml[i];
            }
        }
        mBaselineView = baselineView;

        // Phase 3: container size. EXACTLY => the spec; otherwise the union of
        // child rects (+ right/bottom padding), clamped to the spec for AT_MOST
        // and floored at the suggested minimum.
        int width, height;
        // AOSP RelativeLayout.onMeasure:506-527 (android16-release) gates the
        // wrap_content margin terms on the APP's targetSdkVersion:
        //     if (targetSdkVersion < Build.VERSION_CODES.KITKAT) {
        //         width  = Math.max(width,  params.mRight);
        //         height = Math.max(height, params.mBottom);
        //     } else {
        //         width  = Math.max(width,  params.mRight  + params.rightMargin);
        //         height = Math.max(height, params.mBottom + params.bottomMargin);
        //     }
        // Below KitKat a wrap_content RelativeLayout EXCLUDES its children's
        // trailing margins from its own measured size. Including them makes the
        // container grow by that margin, which pushes its siblings along --
        // RelativeLayout is the dominant pre-Material layout, so this lands on
        // the first frame of any such screen.
        //
        // The `>= KITKAT` branch below is LITERALLY the previous code path, so
        // nothing changes for an app at or above 19, and an unreadable
        // ApplicationInfo resolves to MAX_VALUE and takes that same branch.
        final int targetSdkVersion = macapkTargetSdk();
        final boolean macapkPreKitKatWrapContent =
                targetSdkVersion < android.os.Build.VERSION_CODES.KITKAT;
        final boolean macapkMarginsCountTowardWrapContent = !macapkPreKitKatWrapContent;
        if (widthMode == android.view.View.MeasureSpec.EXACTLY) {
            width = widthSize;
        } else {
            int maxRight = padLeft;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child == null || child.getVisibility() == View.GONE) continue;
                int rm = (macapkMarginsCountTowardWrapContent
                        && child.getLayoutParams() instanceof LayoutParams lp)
                        ? lp.rightMargin : 0;
                maxRight = Math.max(maxRight, mr[i] + rm);
            }
            width = maxRight + padRight;
            width = Math.max(width, getSuggestedMinimumWidth());
            if (widthMode == android.view.View.MeasureSpec.AT_MOST) width = Math.min(width, widthSize);
        }
        if (heightMode == android.view.View.MeasureSpec.EXACTLY) {
            height = heightSize;
        } else {
            int maxBottom = padTop;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child == null || child.getVisibility() == View.GONE) continue;
                int bm = (macapkMarginsCountTowardWrapContent
                        && child.getLayoutParams() instanceof LayoutParams lp)
                        ? lp.bottomMargin : 0;
                maxBottom = Math.max(maxBottom, mb[i] + bm);
            }
            height = maxBottom + padBottom;
            height = Math.max(height, getSuggestedMinimumHeight());
            if (heightMode == android.view.View.MeasureSpec.AT_MOST) height = Math.min(height, heightSize);
        }

        // Phase 4: re-measure stretchable children (match_parent in a dimension,
        // or both edges anchored by rules) EXACTLY at their resolved span, now
        // that the container size is known. This is what lets a match_parent
        // child fill the container and an alignParentLeft+alignParentRight child
        // span the gap, and gives THEIR children a correct EXACTLY spec to
        // measure against.
        final int finalW = width, finalH = height;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) continue;
            if (!(child.getLayoutParams() instanceof LayoutParams lp)) continue;
            boolean stretchW = lp.width  == ViewGroup.LayoutParams.MATCH_PARENT
                    || (lp.mRules[ALIGN_PARENT_LEFT] != 0 && lp.mRules[ALIGN_PARENT_RIGHT] != 0)
                    || (lp.mRules[LEFT_OF] != 0 && lp.mRules[RIGHT_OF] != 0)
                    || (lp.mRules[ALIGN_LEFT] != 0 && lp.mRules[ALIGN_RIGHT] != 0);
            boolean stretchH = lp.height == ViewGroup.LayoutParams.MATCH_PARENT
                    || (lp.mRules[ALIGN_PARENT_TOP] != 0 && lp.mRules[ALIGN_PARENT_BOTTOM] != 0)
                    || (lp.mRules[ABOVE] != 0 && lp.mRules[BELOW] != 0)
                    || (lp.mRules[ALIGN_TOP] != 0 && lp.mRules[ALIGN_BOTTOM] != 0);
            if (!stretchW && !stretchH) continue;
            // The span is the child's RULE-RESOLVED extent, and for MATCH_PARENT it
            // must be used too. AOSP never gives a MATCH_PARENT child the whole
            // container: getChildMeasureSpec returns EXACTLY(maxAvailable), where
            // maxAvailable is bounded by whichever edge the rules fixed.
            //
            // MEASURED: a `match_parent x match_parent` list with
            // layout_above=@id/bottom_nav (the F-Droid idiom) used to be handed
            // EXACTLY(finalH) here, which overwrote the correct EXACTLY(1468) from
            // the solve above, so it measured the FULL 1636 and then
            // top = bottom - 1636 = -168 -- the list sat 168 px (one nav height)
            // too high with its last rows hidden behind the nav. With the span it
            // lands at [0,0-808,1468].
            //
            // The container's own final size still matters when the container is
            // wrap_content and shrank below the spec it solved against, so clamp
            // the span to what the container ended up with.
            int spanW = Math.min(Math.max(0, mr[i] - ml[i]),
                    Math.max(0, finalW - padLeft - padRight - lp.leftMargin - lp.rightMargin));
            int spanH = Math.min(Math.max(0, mb[i] - mt[i]),
                    Math.max(0, finalH - padTop - padBottom - lp.topMargin - lp.bottomMargin));
            int cwSpec = stretchW
                    ? android.view.View.MeasureSpec.makeMeasureSpec(
                          spanW, android.view.View.MeasureSpec.EXACTLY)
                    : childSpecFromMeasured(child.getMeasuredWidth());
            int chSpec = stretchH
                    ? android.view.View.MeasureSpec.makeMeasureSpec(
                          spanH, android.view.View.MeasureSpec.EXACTLY)
                    : childSpecFromMeasured(child.getMeasuredHeight());
            child.measure(cwSpec, chSpec);
        }

        setMeasuredDimension(width, height);
    }

    private static int childSpecFromMeasured(int size) {
        return android.view.View.MeasureSpec.makeMeasureSpec(
                Math.max(0, size), android.view.View.MeasureSpec.EXACTLY);
    }

    /** AOSP's VALUE_NOT_SET, spelled as the sentinel this file already uses for
     *  "this edge was not fixed by any rule". AOSP uses Integer.MIN_VALUE too:
     *  frameworks/base/core/java/android/widget/RelativeLayout.java
     *  `private static final int VALUE_NOT_SET = Integer.MIN_VALUE;`. */
    private static final int VALUE_NOT_SET = Integer.MIN_VALUE;

    /** AOSP RelativeLayout.getChildMeasureSpec, verbatim in behavior (fetched
     *  2026-07-30 from aosp-mirror/platform_frameworks_base main,
     *  core/java/android/widget/RelativeLayout.java).
     *
     *  This is the piece that makes a rule-anchored child measure against the
     *  space its RULES leave it rather than against the container's own spec.
     *  AOSP:
     *      if (tempStart == VALUE_NOT_SET) tempStart = startPadding + startMargin;
     *      if (tempEnd   == VALUE_NOT_SET) tempEnd   = mySize - endPadding - endMargin;
     *      final int maxAvailable = tempEnd - tempStart;
     *      ... WRAP_CONTENT -> AT_MOST(maxAvailable), MATCH_PARENT -> EXACTLY(maxAvailable)
     *
     *  Why it is load-bearing (MEASURED on Sgt Puzzles 1.49 GamePlay, res/layout/
     *  main.xml): `game_view` is wrap_content with only `layout_above=@id/status_bar`,
     *  so its bottom edge is fixed and its top edge is not. Measuring it against the
     *  CONTAINER spec gave AT_MOST(1636) -> measuredHeight 1636 -> top = bottom - 1636,
     *  i.e. the whole view above the viewport. Measuring it against its resolved span
     *  gives AT_MOST(status_bar.top) so top lands at 0.
     *
     *  `mySize < 0` means the container's spec on this axis was UNSPECIFIED; AOSP's
     *  legacy `mAllowBrokenMeasureSpecs` path is not reproduced (it exists only for
     *  pre-JELLY_BEAN_MR2 apps), so we take the modern branch. */
    private static int getChildMeasureSpec(int childStart, int childEnd,
            int childSize, int startMargin, int endMargin, int startPadding,
            int endPadding, int mySize) {
        int childSpecMode = 0;
        int childSpecSize = 0;

        final boolean isUnspecified = mySize < 0;
        if (isUnspecified) {
            if (childStart != VALUE_NOT_SET && childEnd != VALUE_NOT_SET) {
                childSpecSize = Math.max(0, childEnd - childStart);
                childSpecMode = android.view.View.MeasureSpec.EXACTLY;
            } else if (childSize >= 0) {
                childSpecSize = childSize;
                childSpecMode = android.view.View.MeasureSpec.EXACTLY;
            } else {
                childSpecSize = 0;
                childSpecMode = android.view.View.MeasureSpec.UNSPECIFIED;
            }
            return android.view.View.MeasureSpec.makeMeasureSpec(childSpecSize, childSpecMode);
        }

        int tempStart = childStart;
        int tempEnd = childEnd;
        if (tempStart == VALUE_NOT_SET) tempStart = startPadding + startMargin;
        if (tempEnd   == VALUE_NOT_SET) tempEnd   = mySize - endPadding - endMargin;

        final int maxAvailable = tempEnd - tempStart;

        if (childStart != VALUE_NOT_SET && childEnd != VALUE_NOT_SET) {
            childSpecMode = android.view.View.MeasureSpec.EXACTLY;
            childSpecSize = Math.max(0, maxAvailable);
        } else {
            if (childSize >= 0) {
                childSpecMode = android.view.View.MeasureSpec.EXACTLY;
                childSpecSize = (maxAvailable >= 0) ? Math.min(maxAvailable, childSize) : childSize;
            } else if (childSize == ViewGroup.LayoutParams.MATCH_PARENT) {
                childSpecMode = android.view.View.MeasureSpec.EXACTLY;
                childSpecSize = Math.max(0, maxAvailable);
            } else if (childSize == ViewGroup.LayoutParams.WRAP_CONTENT) {
                if (maxAvailable >= 0) {
                    childSpecMode = android.view.View.MeasureSpec.AT_MOST;
                    childSpecSize = maxAvailable;
                } else {
                    childSpecMode = android.view.View.MeasureSpec.UNSPECIFIED;
                    childSpecSize = 0;
                }
            }
        }
        return android.view.View.MeasureSpec.makeMeasureSpec(childSpecSize, childSpecMode);
    }

    /** MACAPK_RL_NORULE_TOPLEFT, default ON (2026-09-29): a rule-less,
     *  non-filling child is laid out at the parent's top-left at its measured
     *  size, as AOSP does, instead of being deferred to the equal-vertical-stack
     *  fallback. =0 restores the deferral exactly. */
    private static final boolean NORULE_TOPLEFT =
        !"0".equals(System.getenv("MACAPK_RL_NORULE_TOPLEFT"));

    /** Measure-space rule solver: a trimmed copy of onLayout's fixed-point sweep
     *  that writes resolved bounds into the out arrays instead of calling
     *  child.layout. Kept separate from onLayout so the proven layout path is
     *  untouched.
     *
     *  AOSP's onMeasure loop is applyVerticalSizeRules -> measureChild ->
     *  positionChildVertical PER CHILD, in dependency order, so a child is
     *  measured with a spec derived from the rule edges that were just resolved
     *  for it. We reproduce that here: once a child's rule edges are known (no
     *  deferral), re-measure it via getChildMeasureSpec(resolved edges) and use
     *  the resulting size to infer whichever edge the rules left open.
     *  `specW`/`specH` carry the container's own spec size (-1 for UNSPECIFIED)
     *  because getChildMeasureSpec distinguishes those; `parentW`/`parentH` stay
     *  clamped at 0 for the parent-anchor rules, which is the pre-existing
     *  behavior. */
    /** `wrapW`/`wrapH`: this axis's spec mode is NOT EXACTLY, i.e. the container
     *  is sizing itself to its children. AOSP's `positionChildVertical`
     *  (android13-release RelativeLayout.java:878-905) does NOT center in that
     *  case -- it PINS the child to the start edge, lets the wrap size be
     *  computed from the pinned positions, and re-centers in a second pass once
     *  the final size is known (onMeasure:599-613). Centering against the
     *  incoming SPEC size instead (what we did) inflates the container to about
     *  half the spec: a centered 80px child in an AT_MOST(1636) spec pushed the
     *  measured bottom to ~880, so anuto's header measured ~830-920px instead
     *  of ~126 and squeezed its GameView until the map letterboxed.
     *  Our onLayout already re-centers against the FINAL bounds unconditionally,
     *  so it IS AOSP's second pass -- pinning here is all that is needed.
     *  See docs/canvas-ab-design-lane4_2026-08-02.md. */

    /** AOSP RelativeLayout.centerHorizontal / centerVertical
     *  (android-11.0.0_r48 RelativeLayout.java:1076-1090), verbatim:
     *
     *      int left = (myWidth - childWidth) / 2;
     *
     *  `myWidth` is the container's FULL measured size, so CENTER_IN_PARENT
     *  and CENTER_HORIZONTAL/VERTICAL deliberately ignore the container's
     *  padding -- unlike every other rule in RelativeLayout, which is
     *  padding-relative. Centering in the padding-inset content box instead
     *  (what this did) biases the child by (padStart - padEnd) / 2 on each
     *  axis: with padding 10/20/30/40 a centered child landed 10px left and
     *  10px above where Android puts it. Asymmetric padding is what makes
     *  this visible, which is why it hides in every zero-padding container. */
    private static int macapkCenterOffset(int mySize, int childSize) {
        return (mySize - childSize) / 2;
    }

    /** AOSP RelativeLayout.java:964-975 applyVerticalSizeRules +
     *  :1061-1073 getRelatedViewBaselineOffset (android11-release), folded
     *  into one helper since both call sites below (solveBoundsForMeasure's
     *  measure-space solve and onLayout's real one) need the identical
     *  arithmetic and differ only in how they source the anchor's top.
     *
     *  Deliberately ZERO margin terms -- verified against both AOSP methods,
     *  neither of which reads leftMargin/topMargin/etc for this rule. Every
     *  OTHER ALIGN_* rule in this file (ALIGN_TOP, ALIGN_LEFT, ...) adds the
     *  child's own margin; ALIGN_BASELINE does not, because it is aligning
     *  two baselines, not two box edges.
     *
     *  @return the absolute Y this child's OWN top must land at so its
     *          baseline lines up with the anchor's, given the anchor's
     *          already-resolved top, the anchor's baseline (caller must have
     *          already checked it isn't -1) and this child's own baseline
     *          (-1 is valid -- e.g. a child with no text -- and simply means
     *          "align box top to the anchor's baseline"). */
    private static int macapkBaselineTop(int anchorTop, int anchorBaseline, int myBaseline) {
        int baselineOffset = anchorTop + anchorBaseline;
        if (myBaseline != -1) baselineOffset -= myBaseline;
        return baselineOffset;
    }

    private void solveBoundsForMeasure(int count, int parentW, int parentH,
            int specW, int specH,
            int padLeft, int padTop, int padRight, int padBottom,
            int[] outL, int[] outT, int[] outR, int[] outB, boolean[] inv,
            boolean wrapW, boolean wrapH) {
        final boolean[] positioned = new boolean[count];
        int positionedCount = 0, passes = 0;
        final int MAX_PASSES = count + 2;
        while (positionedCount < count && passes < MAX_PASSES) {
            passes++;
            boolean progress = false;
            for (int i = 0; i < count; i++) {
                if (positioned[i]) continue;
                View child = getChildAt(i);
                if (child == null || child.getVisibility() == View.GONE) {
                    positioned[i] = true; positionedCount++; progress = true; continue;
                }
                LayoutParams lp = (child.getLayoutParams() instanceof LayoutParams p) ? p : null;
                int[] rules = (lp != null) ? lp.mRules : EMPTY_RULES;
                int margL = (lp != null) ? lp.leftMargin   : 0;
                int margT = (lp != null) ? lp.topMargin    : 0;
                int margR = (lp != null) ? lp.rightMargin  : 0;
                int margB = (lp != null) ? lp.bottomMargin : 0;
                int childW = child.getMeasuredWidth();
                int childH = child.getMeasuredHeight();

                int leftPos = Integer.MIN_VALUE, rightPos = Integer.MIN_VALUE;
                int topPos = Integer.MIN_VALUE, bottomPos = Integer.MIN_VALUE;
                boolean defer = false;
                if (rules[ALIGN_PARENT_LEFT]  == TRUE && parentW > 0) leftPos  = padLeft + margL;
                if (rules[ALIGN_PARENT_RIGHT] == TRUE && parentW > 0) rightPos = parentW - padRight - margR;
                if (rules[ALIGN_PARENT_TOP]   == TRUE && parentH > 0) topPos   = padTop + margT;
                if (rules[ALIGN_PARENT_BOTTOM]== TRUE && parentH > 0) bottomPos= parentH - padBottom - margB;
                // sibling-relative rules
                int idx;
                // lane layout, 2026-08-02: the four RELATIVE rules must add the
                // ANCHOR's facing margin as well as the child's own. AOSP
                // (android13-release RelativeLayout.java:985-995, :921-931):
                //   params.mTop = anchorParams.mBottom
                //                 + (anchorParams.bottomMargin + params.topMargin);
                // and symmetrically for ABOVE/LEFT_OF/RIGHT_OF. We added only the
                // child's margin, so an anchor's margin silently vanished from
                // the gap. Measured: AntennaPod's welcome icon has
                // layout_marginBottom=16dp and its title is layout_below the
                // icon -- our icon->title gap read ~6px against the emulator's
                // ~37px (the missing ~31px IS that 16dp), and title->subtitle
                // was short by the title's own 8dp. This is also part of why the
                // whole welcome block sat too high.
                // See docs/canvas-ab-analyst-report_2026-08-02.md item 4.
                // ALIGN_* rules are edge ALIGNMENT, not stacking, and correctly
                // take only the child's margin (AOSP does the same).
                if (rules[LEFT_OF]   != 0) { idx = macapkRelatedIndex(rules, LEFT_OF);   if (idx>=0 && !positioned[idx]) defer=true; else if (idx>=0) rightPos = outL[idx] - margR - macapkAnchorMargin(idx, LEFT_OF); }
                if (rules[RIGHT_OF]  != 0) { idx = macapkRelatedIndex(rules, RIGHT_OF);  if (idx>=0 && !positioned[idx]) defer=true; else if (idx>=0) leftPos  = outR[idx] + margL + macapkAnchorMargin(idx, RIGHT_OF); }
                if (rules[ALIGN_LEFT]!= 0) { idx = macapkRelatedIndex(rules, ALIGN_LEFT);if (idx>=0 && !positioned[idx]) defer=true; else if (idx>=0) leftPos  = outL[idx] + margL; }
                if (rules[ALIGN_RIGHT]!=0) { idx = macapkRelatedIndex(rules, ALIGN_RIGHT);if(idx>=0 && !positioned[idx]) defer=true; else if(idx>=0) rightPos = outR[idx] - margR; }
                if (rules[ABOVE]     != 0) { idx = macapkRelatedIndex(rules, ABOVE);     if (idx>=0 && !positioned[idx]) defer=true; else if (idx>=0) bottomPos= outT[idx] - margB - macapkAnchorMargin(idx, ABOVE); }
                if (rules[BELOW]     != 0) { idx = macapkRelatedIndex(rules, BELOW);     if (idx>=0 && !positioned[idx]) defer=true; else if (idx>=0) topPos   = outB[idx] + margT + macapkAnchorMargin(idx, BELOW); }
                if (rules[ALIGN_TOP] != 0) { idx = macapkRelatedIndex(rules, ALIGN_TOP); if (idx>=0 && !positioned[idx]) defer=true; else if (idx>=0) topPos   = outT[idx] + margT; }
                if (rules[ALIGN_BOTTOM]!=0) { idx = macapkRelatedIndex(rules, ALIGN_BOTTOM);if(idx>=0 && !positioned[idx]) defer=true; else if(idx>=0) bottomPos= outB[idx] - margB; }

                // ALIGN_BASELINE (docs/fwprobe-window-view-divergences_2026-
                // 08-10.md cluster D3): AOSP RelativeLayout.java:964-975
                // applyVerticalSizeRules -- baseline OVERRIDES whatever
                // ABOVE/BELOW/ALIGN_TOP/ALIGN_BOTTOM/ALIGN_PARENT_TOP/
                // ALIGN_PARENT_BOTTOM resolved above, so this check runs
                // LAST on the vertical axis and unconditionally overwrites
                // topPos and CLEARS bottomPos when it resolves -- clearing
                // bottom is required, or a child with both alignBaseline and
                // alignParentTop keeps the wrong bottom from the earlier
                // ALIGN_PARENT_BOTTOM/ALIGN_BOTTOM check. This reproduces
                // AOSP's early-return (applyVerticalSizeRules returns
                // immediately once baselineOffset != -1, so ABOVE/BELOW/etc
                // are never even evaluated for that child) with the same net
                // result: whatever those checks wrote above gets thrown away
                // by this unconditional overwrite instead of never running.
                //
                // NOT folded into the idx<0||!positioned[idx] shorthand the
                // rules above use, because AOSP's getRelatedView treats a
                // dangling id as a DEFINITIVE "no such relation" (never
                // deferred), and getRelatedViewBaselineOffset treats an
                // anchor with baseline==-1 the same way -- both fall through
                // to whatever ABOVE/BELOW/ALIGN_*/ALIGN_PARENT_* the child
                // otherwise resolved, rather than deferring the whole child.
                // (Residual divergence, notated rather than fixed: if this
                // child ALSO has a relational rule to a DIFFERENT, never-
                // resolvable sibling, our fixed-point retry still defers the
                // whole child via that other rule's own idx<0||!positioned
                // check, where AOSP's baseline early-return would have
                // ignored that dead rule entirely. Zero fleet exposure --
                // requires two conflicting vertical rules on one child.)
                if (rules[ALIGN_BASELINE] != 0) {
                    idx = macapkRelatedIndex(rules, ALIGN_BASELINE);
                    if (idx >= 0 && !positioned[idx]) {
                        defer = true;
                    } else if (idx >= 0) {
                        final int anchorBaseline = getChildAt(idx).getBaseline();
                        if (anchorBaseline != -1) {
                            topPos = macapkBaselineTop(outT[idx], anchorBaseline, child.getBaseline());
                            bottomPos = Integer.MIN_VALUE;
                        }
                    }
                }

                // Mirrors onLayout's hBothUnresolved/vBothUnresolved gate
                // (widget-containers-02): capture BEFORE the defaults below
                // fill leftPos/rightPos/topPos/bottomPos, so centering can
                // only fire when NEITHER edge of the axis was already fixed
                // by a relational/alignParent rule above.
                final boolean hBothUnresolved =
                        (leftPos == Integer.MIN_VALUE && rightPos == Integer.MIN_VALUE);
                final boolean vBothUnresolved =
                        (topPos == Integer.MIN_VALUE && bottomPos == Integer.MIN_VALUE);
                if (defer) continue;

                // AOSP measureChild: the child's spec comes from the rule edges
                // resolved just above, NOT from the container's spec. Without this
                // a wrap_content child anchored on ONE edge measures the full
                // container extent and then gets pushed off the opposite side.
                if (lp != null) {
                    int cwSpec = getChildMeasureSpec(leftPos, rightPos, lp.width,
                            margL, margR, padLeft, padRight, specW);
                    int chSpec = getChildMeasureSpec(topPos, bottomPos, lp.height,
                            margT, margB, padTop, padBottom, specH);
                    child.measure(cwSpec, chSpec);
                    childW = child.getMeasuredWidth();
                    childH = child.getMeasuredHeight();
                }

                boolean wMatch = (lp != null && lp.width  == ViewGroup.LayoutParams.MATCH_PARENT);
                boolean hMatch = (lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT);
                // horizontal defaults -- AOSP positionChildHorizontal: the open
                // edge is ALWAYS mMeasured away from the fixed one. Never fall
                // back to "fill the container" on a 0 measurement: a legitimately
                // empty wrap_content child would then displace every sibling that
                // anchors to it.
                if (leftPos == Integer.MIN_VALUE && rightPos == Integer.MIN_VALUE) {
                    leftPos = padLeft + margL;
                    rightPos = (wMatch && parentW > 0) ? parentW - padRight - margR
                             : leftPos + childW;
                } else if (leftPos == Integer.MIN_VALUE) {
                    leftPos = rightPos - childW;
                } else if (rightPos == Integer.MIN_VALUE) {
                    rightPos = (wMatch && parentW > 0) ? parentW - padRight - margR
                             : leftPos + childW;
                }
                // vertical defaults -- AOSP positionChildVertical, same rule
                if (topPos == Integer.MIN_VALUE && bottomPos == Integer.MIN_VALUE) {
                    topPos = padTop + margT;
                    bottomPos = (hMatch && parentH > 0) ? parentH - padBottom - margB
                              : topPos + childH;
                } else if (topPos == Integer.MIN_VALUE) {
                    topPos = bottomPos - childH;
                } else if (bottomPos == Integer.MIN_VALUE) {
                    bottomPos = (hMatch && parentH > 0) ? parentH - padBottom - margB
                              : topPos + childH;
                }
                // centering -- gated on hBothUnresolved/vBothUnresolved (see above)
                // NOT when the axis is wrap-content: pin instead (see the
                // method header). The both-unset defaults above already
                // produced AOSP's `positionAtEdge` result, so skipping is the
                // pin -- no extra code, and onLayout re-centers later.
                if (hBothUnresolved && !wrapW
                        && (rules[CENTER_IN_PARENT] == TRUE || rules[CENTER_HORIZONTAL] == TRUE)) {
                    int w = (childW > 0) ? childW : (rightPos - leftPos);
                    leftPos = macapkCenterOffset(parentW, w);
                    rightPos = leftPos + w;
                }
                if (vBothUnresolved && !wrapH
                        && (rules[CENTER_IN_PARENT] == TRUE || rules[CENTER_VERTICAL] == TRUE)) {
                    int h = (childH > 0) ? childH : (bottomPos - topPos);
                    topPos = macapkCenterOffset(parentH, h);
                    bottomPos = topPos + h;
                }
                // Strictly inverted only. A ZERO-extent child (left == right or
                // top == bottom) is AOSP-legal -- an empty wrap_content view
                // anchored to one edge collapses to exactly that edge -- and must
                // not be treated as a layout failure.
                if (leftPos > rightPos || topPos > bottomPos) inv[i] = true;
                outL[i] = leftPos; outT[i] = topPos; outR[i] = rightPos; outB[i] = bottomPos;
                positioned[i] = true; positionedCount++; progress = true;
            }
            if (!progress) break;
        }
        // Unresolved children: anchor at top-left with their measured size so the
        // union math still sees them (mirrors onLayout's fallback intent without
        // the equal-stack redistribution, which is a layout-time visual choice).
        for (int i = 0; i < count; i++) {
            if (positioned[i]) continue;
            View child = getChildAt(i);
            if (child == null) continue;
            outL[i] = padLeft; outT[i] = padTop;
            outR[i] = padLeft + Math.max(0, child.getMeasuredWidth());
            outB[i] = padTop  + Math.max(0, child.getMeasuredHeight());
        }
    }

    /** Override base ViewGroup.onLayout. Each child's final mLeft / mTop /
     *  mRight / mBottom is computed from its RelativeLayout.LayoutParams
     *  rules. Children without rules anchor at the parent's top-left
     *  (AOSP default). Iterate to fixed-point so sibling-relative rules
     *  resolve regardless of declaration order. */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int parentW = r - l;
        final int parentH = b - t;
        final int padLeft   = getPaddingLeft();
        final int padTop    = getPaddingTop();
        final int padRight  = getPaddingRight();
        final int padBottom = getPaddingBottom();

        final int count = getChildCount();
        if (count == 0) return;

        // Track which children have been positioned this layout pass.
        // Rules that reference an unpositioned sibling are deferred to
        // the next iteration. Bounded retry: AOSP allows arbitrary
        // chain length, so we cap at count+1 passes (a strict topological
        // order needs at most count passes, +1 for centering pass).
        final boolean[] positioned = new boolean[count];
        // v1.0.93: track which children ended up inverted (top>=bottom
        // or left>=right) after rule-based positioning. These get
        // promoted to the equal-stack fallback below so they're
        // visible. Frozen Bubble's HomeScreen sets margT/margB pairs
        // that exceed parentH for some buttons; without the fallback
        // those buttons disappear entirely.
        final boolean[] invertedAfterRules = new boolean[count];
        int positionedCount = 0;
        int passes = 0;
        final int MAX_PASSES = count + 2;

        while (positionedCount < count && passes < MAX_PASSES) {
            passes++;
            boolean madeProgress = false;
            for (int i = 0; i < count; i++) {
                if (positioned[i]) continue;
                View child = getChildAt(i);
                if (child == null) continue;
                // AOSP RelativeLayout.onLayout (android13-release), verbatim:
                //   for (int i = 0; i < count; i++) {
                //       View child = getChildAt(i);
                //       if (child.getVisibility() != GONE) {
                //           RelativeLayout.LayoutParams st = ...;
                //           child.layout(st.mLeft, st.mTop, st.mRight, st.mBottom);
                //       }
                //   }
                // A GONE child is NEVER laid out (onMeasure skips it too:
                // both the horizontal and vertical passes are wrapped in
                // `if (child.getVisibility() != GONE)`), so its bounds stay
                // whatever they were.
                //
                // We laid GONE children out, and the "no relational rules +
                // MATCH_PARENT fills that dimension" branch below then gave a
                // GONE, never-measured (getMeasuredHeight() == 0) child whose
                // width is match_parent the parent's FULL height. Fossify
                // Gallery's GONE `directories_switch_searching` came out
                // [0,0-808,1524] in a 1524-tall parent that way, which is the
                // 1524 every sibling anchored below it then inherited. See
                // macapkRelatedIndex for the other half of that bug.
                //
                // Marked positioned so the fixed point still terminates and
                // the equal-vertical-stack fallback does not reserve a slice
                // of the container for an invisible child.
                if (child.getVisibility() == View.GONE) {
                    positioned[i] = true; positionedCount++; madeProgress = true; continue;
                }
                ViewGroup.LayoutParams baseLp = child.getLayoutParams();
                LayoutParams lp = (baseLp instanceof LayoutParams)
                    ? (LayoutParams) baseLp
                    : null;
                int[] rules = (lp != null) ? lp.mRules : EMPTY_RULES;
                int margL = (lp != null) ? lp.leftMargin   : 0;
                int margT = (lp != null) ? lp.topMargin    : 0;
                int margR = (lp != null) ? lp.rightMargin  : 0;
                int margB = (lp != null) ? lp.bottomMargin : 0;
                // Resolve START/END to LEFT/RIGHT for this layout's direction.
                if (lp != null) lp.resolveLayoutDirection(getLayoutDirection());

                if (passes == 1 && "1".equals(ENV_MACAPK_TRACE_RL)) {
                    com.macapk.loader.NativeLog.i("Mac.apk.RelLayout",
                        "child[" + i + "] " + child.getClass().getSimpleName()
                        + " isRLP=" + (lp != null) + " mw=" + child.getMeasuredWidth()
                        + " mh=" + child.getMeasuredHeight()
                        + " w=" + (lp != null ? lp.width : -99)
                        + " h=" + (lp != null ? lp.height : -99)
                        + " APT=" + rules[ALIGN_PARENT_TOP]
                        + " APB=" + rules[ALIGN_PARENT_BOTTOM]
                        + " ABOVE=" + Integer.toHexString(rules[ABOVE])
                        + " id=" + Integer.toHexString(child.getId())
                        + " parentH=" + parentH);
                }

                // v1.0.93: detect "child has no relational rules at all"
                // (no addRule was ever called on its LayoutParams). In
                // that case we DON'T want the margin-based positioning
                // path because Frozen Bubble's HomeScreen sets large
                // margT/margB values that aren't intended as Android-
                // margins but as "I'll position this absolutely later"
                // hints; combined with heightMatchParent they produce
                // inverted/zero-extent bounds. Defer to the equal-
                // vertical-stack fallback for such children.
                boolean hasAnyRule = false;
                for (int rIdx = 0; rIdx < rules.length; rIdx++) {
                    if (rules[rIdx] != 0) { hasAnyRule = true; break; }
                }
                if (!hasAnyRule) {
                    // AOSP: a child with NO positioning rules aligns to the
                    // parent's top-left, and MATCH_PARENT in a dimension
                    // fills that dimension. A no-rule MATCH_PARENT child --
                    // e.g. a full-screen background ImageView like Frozen
                    // Bubble's home_screen (MATCH_PARENT x MATCH_PARENT,
                    // added behind the menu buttons) -- MUST fill the
                    // parent. The equal-vertical-stack fallback below exists
                    // only for no-rule WRAP_CONTENT children (FB's stacked
                    // menu buttons) that can't otherwise be positioned;
                    // sweeping a match-parent background into it collapsed
                    // it to a 1/N slice (home_screen rendered as a tiny
                    // strip at the top). So: fill when match-parent, defer
                    // when not.
                    boolean fillW = (lp != null && lp.width  == ViewGroup.LayoutParams.MATCH_PARENT);
                    boolean fillH = (lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT);
                    if (fillW || fillH) {
                        int cw = child.getMeasuredWidth();
                        int ch = child.getMeasuredHeight();
                        if (cw <= 0 && lp != null && lp.width  > 0) cw = lp.width;
                        if (ch <= 0 && lp != null && lp.height > 0) ch = lp.height;
                        int lpos = padLeft + margL;
                        int tpos = padTop  + margT;
                        int rpos = fillW ? (parentW - padRight - margR)
                                         : (cw > 0 ? lpos + cw : parentW - padRight - margR);
                        int bpos = fillH ? (parentH - padBottom - margB)
                                         : (ch > 0 ? tpos + ch : parentH - padBottom - margB);
                        child.layout(lpos, tpos, rpos, bpos);
                        positioned[i] = true;
                        positionedCount++;
                        madeProgress = true;
                    } else if (NORULE_TOPLEFT) {
                        // AOSP's DEFAULT case for a no-rule child that does not
                        // fill: positionChildHorizontal / positionChildVertical
                        // (android14 RelativeLayout.java), with both edges
                        // VALUE_NOT_SET and no CENTER_* rule:
                        //   params.mLeft = mPaddingLeft + params.leftMargin;
                        //   params.mRight = params.mLeft + child.getMeasuredWidth();
                        //   params.mTop = mPaddingTop + params.topMargin;
                        //   params.mBottom = params.mTop + child.getMeasuredHeight();
                        // i.e. the child sits at the parent's top-left at its
                        // MEASURED size, and onLayout lays it out there. There is
                        // no "unpositioned" state in AOSP for such a child.
                        // Deferring it to the equal-vertical-stack fallback below
                        // turned every rule-less child into a full-width 1/N
                        // slice: Simple Solitaire's 52 cards (RelativeLayout
                        // .LayoutParams(w, h), no rules, placed with setX/setY)
                        // became 878x29 strips and the game board drew no cards
                        // (owner hand test 2026-09-29: "when selecting the game
                        // it never starts"). MACAPK_RL_NORULE_TOPLEFT=0 restores
                        // the deferral exactly.
                        int lpos = padLeft + margL;
                        int tpos = padTop  + margT;
                        child.layout(lpos, tpos,
                                     lpos + Math.max(0, child.getMeasuredWidth()),
                                     tpos + Math.max(0, child.getMeasuredHeight()));
                        positioned[i] = true;
                        positionedCount++;
                        madeProgress = true;
                    }
                    continue;  // (gate =0) no-rule + not match-parent: equal-stack fallback
                }

                // child intrinsic size: prefer measuredWidth/Height
                // if set (a measure pass ran), else width/height in
                // LayoutParams, else fall back to the view's
                // setMinimumHeight() value (set by LayoutInflater for
                // Material widget placeholders -- 56dp for BottomNav etc.),
                // else 0.
                int childW = child.getMeasuredWidth();
                int childH = child.getMeasuredHeight();
                if (childW <= 0 && lp != null && lp.width > 0)  childW = lp.width;
                if (childH <= 0 && lp != null && lp.height > 0) childH = lp.height;
                if (childW <= 0) childW = child.getMinimumWidth();
                if (childH <= 0) childH = child.getMinimumHeight();

                // ---- horizontal solve ----
                int leftPos  = Integer.MIN_VALUE;
                int rightPos = Integer.MIN_VALUE;
                boolean hDefer = false;

                if (rules[ALIGN_PARENT_LEFT] == TRUE) {
                    leftPos = padLeft + margL;
                }
                if (rules[ALIGN_PARENT_RIGHT] == TRUE) {
                    rightPos = parentW - padRight - margR;
                }
                if (rules[LEFT_OF] != 0) {
                    int idx = macapkRelatedIndex(rules, LEFT_OF);
                    if (idx >= 0 && !positioned[idx]) { hDefer = true; }
                    else if (idx >= 0) {
                        View sib = getChildAt(idx);
                        // The ANCHOR's facing margin counts too -- see
                        // macapkAnchorMargin. These four onLayout sites were
                        // MISSED when the margin fix landed in the measure-space
                        // solver: for an EXACTLY-sized container (the common
                        // match_parent case) Phase 3 discards the solver's
                        // positions and THIS pass decides where children go, so
                        // the fix was inert exactly where it mattered.
                        rightPos = sib.getLeft() - margR - macapkAnchorMargin(idx, LEFT_OF);
                    }
                }
                if (rules[RIGHT_OF] != 0) {
                    int idx = macapkRelatedIndex(rules, RIGHT_OF);
                    if (idx >= 0 && !positioned[idx]) { hDefer = true; }
                    else if (idx >= 0) {
                        View sib = getChildAt(idx);
                        leftPos = sib.getRight() + margL + macapkAnchorMargin(idx, RIGHT_OF);
                    }
                }
                if (rules[ALIGN_LEFT] != 0) {
                    int idx = macapkRelatedIndex(rules, ALIGN_LEFT);
                    if (idx >= 0 && !positioned[idx]) { hDefer = true; }
                    else if (idx >= 0) { leftPos = getChildAt(idx).getLeft() + margL; }
                }
                if (rules[ALIGN_RIGHT] != 0) {
                    int idx = macapkRelatedIndex(rules, ALIGN_RIGHT);
                    if (idx >= 0 && !positioned[idx]) { hDefer = true; }
                    else if (idx >= 0) { rightPos = getChildAt(idx).getRight() - margR; }
                }

                // ---- vertical solve ----
                int topPos    = Integer.MIN_VALUE;
                int bottomPos = Integer.MIN_VALUE;
                boolean vDefer = false;

                if (rules[ALIGN_PARENT_TOP] == TRUE) {
                    topPos = padTop + margT;
                }
                if (rules[ALIGN_PARENT_BOTTOM] == TRUE) {
                    bottomPos = parentH - padBottom - margB;
                }
                if (rules[ABOVE] != 0) {
                    int idx = macapkRelatedIndex(rules, ABOVE);
                    if (idx >= 0 && !positioned[idx]) { vDefer = true; }
                    else if (idx >= 0) { bottomPos = getChildAt(idx).getTop() - margB
                                        - macapkAnchorMargin(idx, ABOVE); }
                }
                if (rules[BELOW] != 0) {
                    int idx = macapkRelatedIndex(rules, BELOW);
                    if (idx >= 0 && !positioned[idx]) { vDefer = true; }
                    else if (idx >= 0) { topPos = getChildAt(idx).getBottom() + margT
                                     + macapkAnchorMargin(idx, BELOW); }
                }
                if (rules[ALIGN_TOP] != 0) {
                    int idx = macapkRelatedIndex(rules, ALIGN_TOP);
                    if (idx >= 0 && !positioned[idx]) { vDefer = true; }
                    else if (idx >= 0) { topPos = getChildAt(idx).getTop() + margT; }
                }
                if (rules[ALIGN_BOTTOM] != 0) {
                    int idx = macapkRelatedIndex(rules, ALIGN_BOTTOM);
                    if (idx >= 0 && !positioned[idx]) { vDefer = true; }
                    else if (idx >= 0) { bottomPos = getChildAt(idx).getBottom() - margB; }
                }

                // ALIGN_BASELINE -- see the identical block + full AOSP
                // citation in solveBoundsForMeasure above (this is the
                // onLayout twin: "already positioned" here means
                // child.layout() already ran for the anchor at an earlier
                // index THIS pass, so anchor.getTop()/getBaseline() are the
                // real final values rather than solveBoundsForMeasure's
                // measure-space outT[idx]). Runs last, unconditionally
                // overwrites topPos, clears bottomPos.
                if (rules[ALIGN_BASELINE] != 0) {
                    int idx = macapkRelatedIndex(rules, ALIGN_BASELINE);
                    if (idx >= 0 && !positioned[idx]) {
                        vDefer = true;
                    } else if (idx >= 0) {
                        View anchor = getChildAt(idx);
                        final int anchorBaseline = anchor.getBaseline();
                        if (anchorBaseline != -1) {
                            topPos = macapkBaselineTop(anchor.getTop(), anchorBaseline, child.getBaseline());
                            bottomPos = Integer.MIN_VALUE;
                        }
                    }
                }

                // AOSP's positionChildHorizontal/positionChildVertical only
                // enter the centering branch when BOTH edges of an axis are
                // still VALUE_NOT_SET after the rule solve above -- capture
                // that here, BEFORE the "defaults for unset edges" section
                // below fills leftPos/rightPos/topPos/bottomPos with real
                // values. Without this, a child positioned via BELOW/ABOVE/
                // ALIGN_*/ALIGN_PARENT_* on an axis would have that position
                // silently overwritten by the unconditional centering pass
                // if CENTER_IN_PARENT or the matching CENTER_* was also set.
                final boolean hBothUnresolved =
                        (leftPos == Integer.MIN_VALUE && rightPos == Integer.MIN_VALUE);
                final boolean vBothUnresolved =
                        (topPos == Integer.MIN_VALUE && bottomPos == Integer.MIN_VALUE);

                if (hDefer || vDefer) continue;

                // ---- defaults for unset edges ----
                // If width=match_parent (-1) and neither alignParentLeft
                // nor alignParentRight is set, span full parent. If
                // width=wrap_content and only one edge anchored, derive
                // the other from childW.
                final boolean widthMatchParent  = (lp != null && lp.width  == ViewGroup.LayoutParams.MATCH_PARENT);
                final boolean heightMatchParent = (lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT);

                if (leftPos == Integer.MIN_VALUE && rightPos == Integer.MIN_VALUE) {
                    leftPos = padLeft + margL;
                    if (widthMatchParent) {
                        rightPos = parentW - padRight - margR;
                    } else if (childW > 0) {
                        rightPos = leftPos + childW;
                    } else {
                        rightPos = parentW - padRight - margR;
                    }
                } else if (leftPos == Integer.MIN_VALUE) {
                    // AOSP positionChildHorizontal:
                    //   params.mLeft = params.mRight - child.getMeasuredWidth();
                    // UNCONDITIONALLY. The old `childW > 0 ? ... : padLeft` fallback
                    // turned a legitimately empty wrap_content child into one that
                    // spans from the parent's left edge to its anchor, which then
                    // drags every sibling anchored to ITS edge (see the vertical
                    // twin below for the measured Sgt Puzzles case).
                    leftPos = rightPos - childW;
                } else if (rightPos == Integer.MIN_VALUE) {
                    rightPos = widthMatchParent
                                ? parentW - padRight - margR
                                : leftPos + childW;
                }

                if (topPos == Integer.MIN_VALUE && bottomPos == Integer.MIN_VALUE) {
                    topPos = padTop + margT;
                    if (heightMatchParent) {
                        bottomPos = parentH - padBottom - margB;
                    } else if (childH > 0) {
                        bottomPos = topPos + childH;
                    } else {
                        bottomPos = parentH - padBottom - margB;
                    }
                } else if (topPos == Integer.MIN_VALUE) {
                    // AOSP positionChildVertical:
                    //   params.mTop = params.mBottom - child.getMeasuredHeight();
                    // UNCONDITIONALLY, whatever the measured height is.
                    //
                    // MEASURED (Sgt Puzzles 1.49, res/layout/main.xml, portrait,
                    // 2026-07-30): the `status_bar` TextView has text="" so it
                    // measures 0 px tall, and it is anchored ONLY by
                    // layout_above=@id/new_keyboard. The old fallback
                    // (`childH > 0 ? bottom - childH : padTop`) laid it out at
                    // [0,0-808,1532] -- the whole area above the keyboard -- instead
                    // of collapsing it to [0,1532-808,1532]. `game_view` is anchored
                    // layout_above=@id/status_bar, so its bottom resolved to
                    // status_bar.getTop() == 0 and its top to 0 - 1636, evicting the
                    // entire puzzle board to [0,-1636-808,0]. AOSP collapses the
                    // empty status bar, so the board lands at y=0.
                    topPos = bottomPos - childH;
                } else if (bottomPos == Integer.MIN_VALUE) {
                    bottomPos = heightMatchParent
                                ? parentH - padBottom - margB
                                : topPos + childH;
                }

                // ---- centering (final pass for this child) ----
                // AOSP gate (see hBothUnresolved/vBothUnresolved above): a
                // child already positioned on this axis by a relational or
                // alignParent rule keeps that position even if
                // CENTER_IN_PARENT or the matching CENTER_HORIZONTAL/
                // CENTER_VERTICAL is also set on it.
                if (hBothUnresolved
                        && (rules[CENTER_IN_PARENT] == TRUE || rules[CENTER_HORIZONTAL] == TRUE)) {
                    int w = (childW > 0) ? childW : (rightPos - leftPos);
                    leftPos  = macapkCenterOffset(parentW, w);
                    rightPos = leftPos + w;
                }
                if (vBothUnresolved
                        && (rules[CENTER_IN_PARENT] == TRUE || rules[CENTER_VERTICAL] == TRUE)) {
                    int h = (childH > 0) ? childH : (bottomPos - topPos);
                    topPos    = macapkCenterOffset(parentH, h);
                    bottomPos = topPos + h;
                }

                // v1.0.93: reject inverted bounds (top >= bottom or
                // left >= right). Real Android allows them but they
                // render as zero-extent; for our purposes we'd rather
                // see SOMETHING. Mark as positioned anyway (so the
                // rule loop terminates) but record the inversion
                // for the post-loop fallback.
                // STRICTLY inverted only (was `>=`). A zero-extent child is
                // AOSP-legal: an empty wrap_content view anchored to one edge
                // collapses onto that edge. Treating `top == bottom` as a failure
                // fired the equal-vertical-stack redistribution below for the
                // WHOLE container, which is how one empty TextView could scramble
                // an otherwise correct layout.
                if (leftPos > rightPos || topPos > bottomPos) {
                    invertedAfterRules[i] = true;
                }
                child.layout(leftPos, topPos, rightPos, bottomPos);
                positioned[i] = true;
                positionedCount++;
                madeProgress = true;
            }
            if (!madeProgress) break;  // cycle / impossible -- bail
        }

        // Any remaining unpositioned children: equal-vertical-stack
        // fallback, matching the pre-v1.0.93 force-fill walker. This
        // covers two FB-class cases:
        //   (a) Frozen Bubble's HomeScreen adds 5 Buttons via Java
        //       with default RelativeLayout.LayoutParams (no rules).
        //       Without this fallback, the rule loop above couldn't
        //       resolve them and we'd dump them all at full extent.
        //   (b) Cycle / missing-anchor in user-supplied XML.
        int remaining = 0;
        for (int i = 0; i < count; i++) if (!positioned[i]) remaining++;
        if (remaining > 0) {
            int sliceH = (parentH - padTop - padBottom) / remaining;
            int idx = 0;
            for (int i = 0; i < count; i++) {
                if (positioned[i]) continue;
                View child = getChildAt(i);
                if (child == null) continue;
                int y = padTop + idx * sliceH;
                child.layout(padLeft, y, parentW - padRight, y + sliceH);
                idx++;
            }
        }

        // v1.0.93: if ANY child ended up inverted, redistribute ALL
        // children as equal vertical slices. Heuristic: an inverted
        // child indicates the layout intent was something margins
        // can't express (e.g. FB's HomeScreen sets large margT/margB
        // pairs to position buttons numerically; on real Android that
        // also inverts but FB compensates elsewhere with setX/setY
        // that we don't implement). Equal-stacking the whole batch
        // restores the "5 visible buttons" outcome.
        int invertedCount = 0;
        for (int i = 0; i < count; i++) if (invertedAfterRules[i]) invertedCount++;
        if (invertedCount > 0) {
            int avail = parentH - padTop - padBottom;
            int sliceH = avail / Math.max(1, count);
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child == null) continue;
                // Never re-stack a full-height background fill (MATCH_PARENT
                // height) -- it must keep filling the parent behind the
                // redistributed children, not become one equal slice.
                ViewGroup.LayoutParams clp = child.getLayoutParams();
                if (clp != null && clp.height == ViewGroup.LayoutParams.MATCH_PARENT) continue;
                int y = padTop + i * sliceH;
                child.layout(padLeft, y, parentW - padRight, y + sliceH);
            }
        }

        // AOSP applies mGravity as a SINGLE GROUP SHIFT, not per-child: take
        // the union of every non-GONE child's already rule-resolved
        // (margin-inclusive) bounds, ask Gravity where that union should
        // sit inside the padded parent bounds, and move every child by the
        // resulting offset. Skipped entirely while mGravity is still the
        // START|TOP default, so an app that never calls setGravity() sees
        // byte-identical output to before this pass existed.
        // Source: frameworks/base/core/java/android/widget/RelativeLayout.java
        // onMeasure lines ~440-644 (web-fetched 2026-07-22), adapted from a
        // measure-time LayoutParams.mLeft/mTop/mRight/mBottom mutation to a
        // layout-time re-layout() because our onLayout positions children
        // directly instead of staging through those fields first.
        int hGrav = mGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        boolean horizontalGravity = hGrav != Gravity.START && hGrav != 0;
        int vGrav = mGravity & Gravity.VERTICAL_GRAVITY_MASK;
        boolean verticalGravity = vGrav != Gravity.TOP && vGrav != 0;
        if (horizontalGravity || verticalGravity) {
            int unionL = Integer.MAX_VALUE, unionT = Integer.MAX_VALUE;
            int unionR = Integer.MIN_VALUE, unionB = Integer.MIN_VALUE;
            boolean any = false;
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child == null || child.getVisibility() == View.GONE) continue;
                LayoutParams clp = (child.getLayoutParams() instanceof LayoutParams p) ? p : null;
                int lm = (clp != null) ? clp.leftMargin   : 0;
                int tm = (clp != null) ? clp.topMargin    : 0;
                int rm = (clp != null) ? clp.rightMargin  : 0;
                int bm = (clp != null) ? clp.bottomMargin : 0;
                unionL = Math.min(unionL, child.getLeft()   - lm);
                unionT = Math.min(unionT, child.getTop()    - tm);
                unionR = Math.max(unionR, child.getRight()  + rm);
                unionB = Math.max(unionB, child.getBottom() + bm);
                any = true;
            }
            if (any) {
                Rect selfBounds = new Rect(padLeft, padTop, parentW - padRight, parentH - padBottom);
                Rect contentBounds = new Rect();
                Gravity.apply(mGravity, unionR - unionL, unionB - unionT, selfBounds, contentBounds);
                int hOff = horizontalGravity ? contentBounds.left - unionL : 0;
                int vOff = verticalGravity   ? contentBounds.top  - unionT : 0;
                if (hOff != 0 || vOff != 0) {
                    for (int i = 0; i < count; i++) {
                        View child = getChildAt(i);
                        if (child == null || child.getVisibility() == View.GONE) continue;
                        child.layout(child.getLeft() + hOff, child.getTop() + vOff,
                                     child.getRight() + hOff, child.getBottom() + vOff);
                    }
                }
            }
        }
    }

    private static final int[] EMPTY_RULES = new int[VERB_COUNT];

    /** Linear-scan over children for the one whose id matches.
     *  Returns the index, or -1 if not found. */
    /** The ANCHOR child's margin on the edge a relative rule stacks against
     *  (AOSP adds `anchorParams.<edge>Margin` alongside the child's own --
     *  RelativeLayout.java:921-931 / :985-995, android13-release). `idx` is
     *  the anchor's index in the sorted child order these solvers use.
     *  Returns 0 for anything without MarginLayoutParams. */
    private int macapkAnchorMargin(int idx, int rule) {
        try {
            View anchor = getChildAt(idx);
            if (anchor == null) return 0;
            android.view.ViewGroup.LayoutParams alp = anchor.getLayoutParams();
            if (!(alp instanceof android.view.ViewGroup.MarginLayoutParams m)) return 0;
            switch (rule) {
                case BELOW:    return m.bottomMargin;  // child sits under the anchor's bottom edge
                case ABOVE:    return m.topMargin;     // child sits over the anchor's top edge
                case RIGHT_OF: return m.rightMargin;
                case LEFT_OF:  return m.leftMargin;
                default:       return 0;
            }
        } catch (Throwable t) { return 0; }
    }

    /** AOSP RelativeLayout.getRelatedView (android13-release
     *  RelativeLayout.java, quoted verbatim):
     *
     *      View v = node.view;
     *      // Find the first non-GONE view up the chain
     *      while (v.getVisibility() == View.GONE) {
     *          rules = ((LayoutParams) v.getLayoutParams()).getRules(v.getLayoutDirection());
     *          node = mGraph.mKeyNodes.get((rules[relation]));
     *          if (node == null || v == node.view) return null;
     *          v = node.view;
     *      }
     *
     *  expressed as a child INDEX, because every call site here indexes
     *  parallel arrays rather than holding a View.
     *
     *  A GONE sibling is NOT an anchor. AOSP re-reads the GONE view's OWN
     *  rule for the SAME relation and hops to whatever THAT names, and gives
     *  up -- returns null, so the caller treats the rule as ABSENT -- once
     *  the chain dead-ends or points back at itself. We used to resolve
     *  straight to the GONE child and read its bounds.
     *
     *  Why it matters, measured on Fossify Gallery 1.13.1 (2026-08-12):
     *  activity_main.xml stacks three GONE placeholders plus the content
     *  grid, each `layout_below` the one before, and the head of that chain
     *  (`directories_switch_searching`) is `android:visibility="gone"` in
     *  the XML. Reading its bounds put the SwipeRefreshLayout holding the
     *  whole directory grid at y=1524..3048 inside a 1524-tall parent: the
     *  entire app body -- including the "No media files have been found"
     *  empty-state text the app had just made VISIBLE -- was laid out one
     *  full screen BELOW the screen. The app drew its toolbar and nothing
     *  else, which reads as "it does not render" rather than as a layout
     *  bug.
     *
     *  A GONE placeholder with content anchored below it is the ordinary
     *  Android empty-state idiom, so this is fleet-wide, not app-specific.
     *
     *  @return the index of the first non-GONE anchor, or -1 for AOSP's
     *          null -- which callers must treat as "this rule is absent",
     *          NOT as "defer until the anchor gets positioned". */
    private int macapkRelatedIndex(int[] rules, int relation) {
        final int id = rules[relation];
        if (id == 0 || id == TRUE) return -1;
        int idx = findChildByIdSorted(id);
        // AOSP looks the id up in mKeyNodes; an id naming no child of this
        // container yields node == null, hence null, hence "rule absent".
        if (idx < 0) return -1;
        View v = getChildAt(idx);
        // AOSP's only cycle guard is the `v == node.view` self-reference
        // test below. A LONGER cycle (a -> b -> a, both GONE) would spin
        // there; a layout pass must never hang on malformed app XML, so
        // bound the walk by the child count as well.
        for (int hops = 0; v != null && v.getVisibility() == View.GONE; hops++) {
            if (hops > getChildCount()) return -1;
            final ViewGroup.LayoutParams blp = v.getLayoutParams();
            if (!(blp instanceof LayoutParams)) return -1;
            final LayoutParams alp = (LayoutParams) blp;
            // AOSP reads getRules(v.getLayoutDirection()), which folds
            // START/END into LEFT/RIGHT. A GONE child never reaches the
            // resolve call in the positioning loops (both skip it), so do
            // it here or its START/END rules stay unresolved. Same direction
            // as every other resolveLayoutDirection call in this file.
            alp.resolveLayoutDirection(getLayoutDirection());
            final int nextId = alp.mRules[relation];
            if (nextId == 0 || nextId == TRUE) return -1;
            final int nidx = findChildByIdSorted(nextId);
            if (nidx < 0 || nidx == idx) return -1;  // node == null || v == node.view
            idx = nidx;
            v = getChildAt(idx);
        }
        return (v != null) ? idx : -1;
    }

    private int findChildByIdSorted(int id) {
        if (id == TRUE) return -1;  // "true" subject -- not a sibling
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c != null && c.getId() == id) return i;
        }
        return -1;
    }

    /** Default LayoutParams for children attached via addView(view)
     *  with no explicit params. AOSP returns RelativeLayout.LayoutParams
     *  with WRAP_CONTENT; we do the same so addRule() works on whatever
     *  the inflater attached. */
    public ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public RelativeLayout(android.content.Context c, android.util.AttributeSet a, int defStyleAttr, int defStyleRes) {
        this(c, a, defStyleAttr);
    }

    // AOSP RelativeLayout.setIgnoreGravity/getIgnoreGravity was `{}` /
    // hardcoded 0 with no backing field. Scoped to the accessor-round-trip
    // fix only: AOSP's real semantic effect is excluding the named child (by
    // id) from the mGravity union-shift pass a few lines above (onLayout,
    // "AOSP applies mGravity as a SINGLE GROUP SHIFT"), which is shared,
    // already-sensitive layout code for every RelativeLayout with a non-
    // default gravity -- NOT touched here without a sweep. Reported as a
    // separate, not-done follow-up rather than guessed at.
    private int mIgnoreGravity = View.NO_ID;
    public void setIgnoreGravity(int viewId) { mIgnoreGravity = viewId; }
    public int getIgnoreGravity() { return mIgnoreGravity; }
}


