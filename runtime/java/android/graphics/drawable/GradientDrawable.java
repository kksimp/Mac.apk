// android.graphics.drawable.GradientDrawable -- the drawable behind an
// XML <shape>. AOSP (all line refs below are to this file):
//   https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r48/graphics/java/android/graphics/drawable/GradientDrawable.java
// Previously every setter/draw was a no-op stub, so a <shape> inflated by
// Resources.getDrawable rendered nothing. F-Droid's toolbar icons wrap a
// `background_circle` <shape oval> behind the glyph (ic_*_with_background
// layer-lists), and many Material/AppCompat backgrounds are <shape>s, so a
// non-drawing GradientDrawable left those invisible. We implement the
// common, observable subset: solid fill, oval/rect/round-rect, stroke, and
// an explicit <size>.
//
// Gradients: the colors[] passed to setColors() / the (Orientation, int[])
// constructor becomes a real Shader in draw(); the orientation-to-endpoint
// switch, the radius formula, and the useLevel scaling are ported verbatim
// from the AOSP source above. GradientDrawable.RING is DRAWN as a ring since
// v1.0.1650 (MACAPK_SHAPE_RING, see buildRing, ported from the android14-release
// source); before that it fell through to the rect/round-rect path, so Frozen
// Bubble's round back button painted a filled square. (Its getOutline answer
// was already correct: AOSP's getOutline has no RING case at all and leaves the
// outline empty.)
//
// Per-corner radii: an 8-value mRadiusArray is drawn via
// Path.addRoundRect + canvas.drawPath, and only the scalar mRadius falls
// back to canvas.drawRoundRect, matching AOSP's own draw()/buildPathIfDirty()
// split.
//
// v1.0.923 -- THE STATE MODEL. Everything above was already here; what was
// missing was the half of GradientDrawable that is not about painting, and
// it was missing wholesale: getShape/getColor/getColors/getOrientation/
// getGradientType/getAlpha/getInnerRadius/getThickness, setColor(ColorStateList),
// setPadding/getPadding, isStateful/onStateChange, getOpacity, getOutline,
// and a real ConstantState. Two consequences, both measured:
//
//  (a) setColor(ColorStateList) did not EXIST, so a code-built stateful
//      <shape> threw NoSuchMethodError at construction -- 30+ DRW cases died
//      on the constructor rather than on the question they were asking.
//  (b) getOpacity() was hardcoded TRANSLUCENT. AOSP's answer is a three-way
//      AND (:1893-1896):
//          mAlpha == 255 && mOpaqueOverBounds && isOpaqueForState()
//      where mOpaqueOverBounds is computed by GradientState.computeOpacity
//      (:2257-2280): every gradient colour opaque, SOME fill present at all,
//      and shape == RECTANGLE with no corner radius. An opaque rectangular
//      <shape> -- the single most common background in an Android app --
//      therefore reports OPAQUE, and every container above it (LayerDrawable,
//      InsetDrawable, StateListDrawable) computes ITS opacity from that. One
//      hardcoded constant here made the whole tree above it wrong.
//
// The shared/local split is load-bearing and is not cosmetic: mGradientState
// is what SIBLINGS from one ConstantState share (so setSize on a copy moves
// the original, DRW.cs.gd.leakWithoutMutate), while mPadding/mFillColor/
// mStrokeColor/mAlpha are per-instance locals derived from it (so setPadding
// on a copy does NOT make the original report padding,
// DRW.cs.gd.paddingLeak = "0 rect=0,0,0,0"). mutate() forks the state.
package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;

import android.content.res.ColorStateList;

public class GradientDrawable extends Drawable {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_TRACE_SHAPEFILL = System.getenv("MACAPK_TRACE_SHAPEFILL");

    /** fwxml (docs/fwxml-platform-xml-typed-values_2026-09-26.md). Default ON;
     *  `MACAPK_SHAPE_TINT=0` restores BOTH halves of the pre-change path for a
     *  one-binary bisect: Resources' <shape> inflater stops reading
     *  android:tint / android:tintMode, and draw() goes back to REPLACING the
     *  solid fill's colour with the tint (and leaving the stroke untinted). */
    public static final boolean MACAPK_SHAPE_TINT =
        !"0".equals(System.getenv("MACAPK_SHAPE_TINT"));

    /** Default ON. `MACAPK_SHAPE_RING=0` restores BOTH halves of the pre-change
     *  path for a one-binary bisect: Resources' <shape> inflater stops reading
     *  the ring attributes (and maps the text value "ring" to RECTANGLE again),
     *  and draw() paints a RING through the rect/round-rect path. */
    public static final boolean MACAPK_SHAPE_RING =
        !"0".equals(System.getenv("MACAPK_SHAPE_RING"));

    public enum Orientation {
        TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR
    }
    public static final int RECTANGLE = 0;
    public static final int OVAL      = 1;
    public static final int LINE      = 2;
    public static final int RING      = 3;
    /** AOSP GradientDrawable.ARC (API 36,
     *  com.android.graphics.flags.gradient_drawable_shape_arc_for_rounded_cap). */
    public static final int ARC       = 4;
    public static final int LINEAR_GRADIENT = 0;
    public static final int RADIAL_GRADIENT = 1;
    public static final int SWEEP_GRADIENT  = 2;

    /** The SHARED half. AOSP's GradientState; siblings built from one
     *  ConstantState hold the same instance until one of them mutate()s. */
    static final class GradientState extends ConstantState {
        int mChangingConfigurations;
        int mShape = RECTANGLE;
        /** Non-null exactly when a solid fill was set; mutually exclusive with
         *  mGradientColors (AOSP setSolidColors/setGradientColors each null the
         *  other, :2313-2325). */
        ColorStateList mSolidColors;
        int[] mGradientColors;
        float[] mPositions;
        Orientation mOrientation = Orientation.TOP_BOTTOM;
        int mGradientType = LINEAR_GRADIENT;
        float mCenterX = 0.5f;
        float mCenterY = 0.5f;
        float mGradientRadius = 0.5f;
        boolean mUseLevel;
        float mRadius;
        float[] mRadiusArray;
        /** AOSP GradientState.mStrokeWidth = -1 ("no stroke"), NOT 0. The
         *  distinction is observable: isOpaqueForState() consults the stroke
         *  paint whenever mStrokeWidth >= 0, so a zero-width TRANSLUCENT stroke
         *  still forces TRANSLUCENT (DRW.gdop.strokeZeroWidthTranslucent). */
        int mStrokeWidth = -1;
        ColorStateList mStrokeColors;
        float mStrokeDashWidth;
        float mStrokeDashGap;
        int mWidth = -1;
        int mHeight = -1;
        int mInnerRadius = -1;
        int mThickness = -1;
        float mInnerRadiusRatio = 3.0f;
        float mThicknessRatio = 9.0f;
        /** AOSP GradientState.mUseLevelForShape (android14-release :2056),
         *  default TRUE: a ring's sweep is driven by the drawable level unless
         *  the XML says useLevel="false", so a default ring at level 0 draws
         *  NOTHING. That is AOSP's behaviour and deliberately kept. Separate from
         *  mUseLevel, which belongs to <gradient>. */
        boolean mUseLevelForShape = true;
        Rect mPadding;
        boolean mOpaqueOverBounds;
        boolean mOpaqueOverShape;

        GradientState() { }
        GradientState(GradientState orig) {
            mChangingConfigurations = orig.mChangingConfigurations;
            mShape = orig.mShape;
            mSolidColors = orig.mSolidColors;
            mGradientColors = orig.mGradientColors != null ? orig.mGradientColors.clone() : null;
            mPositions = orig.mPositions != null ? orig.mPositions.clone() : null;
            mOrientation = orig.mOrientation;
            mGradientType = orig.mGradientType;
            mCenterX = orig.mCenterX;
            mCenterY = orig.mCenterY;
            mGradientRadius = orig.mGradientRadius;
            mUseLevel = orig.mUseLevel;
            mRadius = orig.mRadius;
            mRadiusArray = orig.mRadiusArray != null ? orig.mRadiusArray.clone() : null;
            mStrokeWidth = orig.mStrokeWidth;
            mStrokeColors = orig.mStrokeColors;
            mStrokeDashWidth = orig.mStrokeDashWidth;
            mStrokeDashGap = orig.mStrokeDashGap;
            mWidth = orig.mWidth;
            mHeight = orig.mHeight;
            mInnerRadius = orig.mInnerRadius;
            mThickness = orig.mThickness;
            mInnerRadiusRatio = orig.mInnerRadiusRatio;
            mThicknessRatio = orig.mThicknessRatio;
            mUseLevelForShape = orig.mUseLevelForShape;
            mPadding = orig.mPadding != null ? new Rect(orig.mPadding) : null;
            mOpaqueOverBounds = orig.mOpaqueOverBounds;
            mOpaqueOverShape = orig.mOpaqueOverShape;
        }

        void setShape(int shape)  { mShape = shape; computeOpacity(); }
        void setSolidColors(ColorStateList colors) {
            mGradientColors = null; mSolidColors = colors; computeOpacity();
        }
        void setGradientColors(int[] colors) {
            mGradientColors = colors; mSolidColors = null; computeOpacity();
        }
        void setStroke(int width, ColorStateList colors, float dashWidth, float dashGap) {
            mStrokeWidth = width; mStrokeColors = colors;
            mStrokeDashWidth = dashWidth; mStrokeDashGap = dashGap;
            computeOpacity();
        }
        void setCornerRadius(float radius) {
            if (radius < 0) radius = 0;
            mRadius = radius; mRadiusArray = null; computeOpacity();
        }
        void setCornerRadii(float[] radii) {
            mRadiusArray = radii;
            if (radii == null) mRadius = 0;
            computeOpacity();
        }

        /** AOSP :2257-2280, verbatim. Note the ORDER: an UNFILLED shape (no
         *  gradient AND no solid) returns before either flag is set, which is
         *  why a bare GradientDrawable is TRANSLUCENT, and mOpaqueOverBounds
         *  additionally demands a square-cornered RECTANGLE. */
        void computeOpacity() {
            mOpaqueOverBounds = false;
            mOpaqueOverShape = false;
            if (mGradientColors != null) {
                for (int i = 0; i < mGradientColors.length; i++) {
                    if (!isOpaque(mGradientColors[i])) return;
                }
            }
            if (mGradientColors == null && mSolidColors == null) return;
            mOpaqueOverShape = true;
            mOpaqueOverBounds = mShape == RECTANGLE && mRadius <= 0 && mRadiusArray == null;
        }

        @Override public Drawable newDrawable() { return new GradientDrawable(this); }
        @Override public int getChangingConfigurations() { return mChangingConfigurations; }
    }

    private static boolean isOpaque(int color) { return ((color >> 24) & 0xff) == 0xff; }

    private GradientState mGradientState;
    // The LOCAL half -- per-instance, derived from the state (AOSP's
    // updateLocalState + mFillPaint / mStrokePaint / mAlpha / mPadding).
    private int mAlpha = 255;
    private int mFillColor;
    private int mStrokeColor;
    private Rect mPadding;
    private boolean mMutated;

    public GradientDrawable() { mGradientState = new GradientState(); }
    public GradientDrawable(Orientation orientation, int[] colors) {
        mGradientState = new GradientState();
        mGradientState.mOrientation = (orientation != null) ? orientation : Orientation.TOP_BOTTOM;
        setColors(colors);
        updateLocalState();
    }
    private GradientDrawable(GradientState state) {
        mGradientState = state;
        updateLocalState();
    }

    /** AOSP updateLocalState (:2000-2050 in the r48 layout): re-derive every
     *  per-instance value from the shared state. Called from the sharing
     *  constructor and from mutate(). */
    private void updateLocalState() {
        final GradientState s = mGradientState;
        if (s.mSolidColors != null) {
            mFillColor = s.mSolidColors.getColorForState(getState(), 0);
        } else if (s.mGradientColors == null) {
            // No solid and no gradient: the app is only stroking the shape.
            mFillColor = 0;
        } else {
            // AOSP: "make sure the fill alpha is maxed out" -- the gradient
            // Shader supplies the colour, the fill paint only supplies alpha.
            mFillColor = 0xFF000000;
        }
        mPadding = s.mPadding;
        if (s.mStrokeWidth >= 0 && s.mStrokeColors != null) {
            mStrokeColor = s.mStrokeColors.getColorForState(getState(), 0);
        }
    }

    // ---- shape / geometry readback ----------------------------------
    public void setShape(int shape) { mGradientState.setShape(shape); invalidateSelf(); }
    public int  getShape()          { return mGradientState.mShape; }

    /** AOSP setColor(int) wraps the argument in a ColorStateList, which is why
     *  getColor() comes back non-null and non-stateful afterwards
     *  (DRW.gd.setColorInt.getDefault). */
    public void setColor(int argb) {
        mGradientState.setSolidColors(ColorStateList.valueOf(argb));
        mFillColor = argb;
        invalidateSelf();
    }
    /** AOSP setColor(ColorStateList) (:1069-1085): a null list means "fully
     *  transparent solid", not "no fill". */
    public void setColor(ColorStateList colorStateList) {
        if (colorStateList == null) {
            setColor(0);
        } else {
            final int color = colorStateList.getColorForState(getState(), 0);
            mGradientState.setSolidColors(colorStateList);
            mFillColor = color;
            invalidateSelf();
        }
    }
    /** AOSP getColor() is the solid ColorStateList, null when the shape is
     *  unfilled or carries a gradient instead. */
    public ColorStateList getColor() { return mGradientState.mSolidColors; }

    public void setColors(int[] colors) { setColors(colors, null); }
    /** AOSP setColors(int[], float[]): the multi-stop gradient entry point.
     *  Stores the full array so draw() can build a real Shader. */
    public void setColors(int[] colors, float[] offsets) {
        mGradientState.setGradientColors(colors);
        mGradientState.mPositions = offsets;
        invalidateSelf();
    }
    /** AOSP getColors() hands back a defensive clone, or null when the fill is
     *  solid rather than a gradient. */
    public int[] getColors() {
        return mGradientState.mGradientColors != null
                ? mGradientState.mGradientColors.clone() : null;
    }

    public void setOrientation(Orientation o) { if (o != null) mGradientState.mOrientation = o; }
    public Orientation getOrientation() { return mGradientState.mOrientation; }

    public void setCornerRadius(float r) { mGradientState.setCornerRadius(r); invalidateSelf(); }
    /** AOSP getCornerRadius(): the single uniform radius. Independent of the
     *  per-corner array -- setCornerRadii does NOT clear it, so
     *  DRW.gd.radiusThenRadii.radius is still 9. */
    public float getCornerRadius() { return mGradientState.mRadius; }

    public void setCornerRadii(float[] radii) { mGradientState.setCornerRadii(radii); invalidateSelf(); }
    /** AOSP getCornerRadii() is literally `return mGradientState.mRadiusArray.clone();`
     *  -- there is no null check, so calling it on a shape with no per-corner
     *  radii throws NullPointerException. That is not a bug we are free to
     *  "fix": DRW.gd.setCornerRadiiNull.get asks for exactly that NPE, and an
     *  app that guards its own call the way the platform forces it to would
     *  behave differently against a lenient null. */
    public float[] getCornerRadii() { return mGradientState.mRadiusArray.clone(); }

    public void setStroke(int width, int color) { setStroke(width, color, 0f, 0f); }
    public void setStroke(int width, int color, float dashWidth, float dashGap) {
        setStroke(width, ColorStateList.valueOf(color), dashWidth, dashGap);
    }
    public void setStroke(int width, ColorStateList colorStateList) {
        setStroke(width, colorStateList, 0f, 0f);
    }
    /** AOSP setStroke(int, ColorStateList, float, float): the CSL is kept on the
     *  state (that is what makes isStateful() true for a stateful stroke,
     *  DRW.gd.strokeCsl.isStateful) and resolved against the current state now;
     *  onStateChange re-resolves it later. */
    public void setStroke(int width, ColorStateList colorStateList,
                          float dashWidth, float dashGap) {
        mGradientState.setStroke(width, colorStateList, dashWidth, dashGap);
        mStrokeColor = (colorStateList != null)
                ? colorStateList.getColorForState(getState(), 0) : 0;
        invalidateSelf();
    }

    public void setGradientType(int type) { mGradientState.mGradientType = type; invalidateSelf(); }
    public int  getGradientType()         { return mGradientState.mGradientType; }
    public void setGradientCenter(float x, float y) {
        mGradientState.mCenterX = x; mGradientState.mCenterY = y;
    }
    public float getGradientCenterX() { return mGradientState.mCenterX; }
    public float getGradientCenterY() { return mGradientState.mCenterY; }
    public void setGradientRadius(float r) { mGradientState.mGradientRadius = r; }
    public float getGradientRadius()       { return mGradientState.mGradientRadius; }
    public void setUseLevel(boolean b)     { mGradientState.mUseLevel = b; }
    public boolean getUseLevel()           { return mGradientState.mUseLevel; }

    public void setSize(int width, int height) {
        mGradientState.mWidth = width; mGradientState.mHeight = height; invalidateSelf();
    }
    @Override public int getIntrinsicWidth()  { return mGradientState.mWidth; }
    @Override public int getIntrinsicHeight() { return mGradientState.mHeight; }

    // ---- ring geometry ----------------------------------------------
    public void setInnerRadius(int innerRadius) { mGradientState.mInnerRadius = innerRadius; invalidateSelf(); }
    public int  getInnerRadius()                { return mGradientState.mInnerRadius; }
    public void setThickness(int thickness)     { mGradientState.mThickness = thickness; invalidateSelf(); }
    public int  getThickness()                  { return mGradientState.mThickness; }
    public void setInnerRadiusRatio(float ratio) { mGradientState.mInnerRadiusRatio = ratio; invalidateSelf(); }
    public float getInnerRadiusRatio()           { return mGradientState.mInnerRadiusRatio; }
    public void setThicknessRatio(float ratio)   { mGradientState.mThicknessRatio = ratio; invalidateSelf(); }
    public float getThicknessRatio()             { return mGradientState.mThicknessRatio; }
    /** AOSP has no public setter for the shape-level useLevel: only
     *  updateStateFromTypedArray writes it (android14-release :1504-1505). Our
     *  <shape> inflater lives in android.content.res, so it needs this
     *  macapk-prefixed door to do the same write. */
    public void macapkSetUseLevelForShape(boolean useLevel) {
        mGradientState.mUseLevelForShape = useLevel;
        invalidateSelf();
    }
    public boolean macapkGetUseLevelForShape() { return mGradientState.mUseLevelForShape; }

    // ---- padding ----------------------------------------------------
    /** AOSP :994-1002. The Rect lives on the STATE (so it is shared) but the
     *  `mPadding` pointer is LOCAL, which is why a sibling that never had
     *  padding keeps answering false after the other one gains some
     *  (DRW.cs.gd.paddingLeak). */
    public void setPadding(int left, int top, int right, int bottom) {
        if (mGradientState.mPadding == null) mGradientState.mPadding = new Rect();
        mGradientState.mPadding.set(left, top, right, bottom);
        mPadding = mGradientState.mPadding;
        invalidateSelf();
    }
    /** AOSP :233-239. Note it returns TRUE for an explicit setPadding(0,0,0,0)
     *  -- the answer is "was a padding set", not "is it non-zero"
     *  (DRW.gd.setPaddingZero.ret = 1), which is the opposite of
     *  LayerDrawable's union test. */
    @Override public boolean getPadding(Rect padding) {
        if (mPadding != null) {
            padding.set(mPadding);
            return true;
        }
        return super.getPadding(padding);
    }

    // v1.0.498 diagnostic accessors (MACAPK_TRACE_GUIDE tree dump): a filled
    // button whose fill never paints shows fillColor 0 / hasFill false / tint 0.
    public int macapkDumpFillColor() { return mFillColor; }
    public boolean macapkDumpHasFill() { return hasFill(); }
    public int macapkDumpTint() { return macapkEffectiveTint(); }
    public int macapkDumpStroke() { return mStrokeColor; }

    /** Paint-path gate only (NOT the opacity model -- computeOpacity has its
     *  own, which counts a zero-length gradient array as "filled"). The extra
     *  length test preserves long-standing Mac.apk behaviour: setColors(new
     *  int[0]) used to clear the fill flag outright, and painting the local
     *  mFillColor there would put an opaque BLACK rect where nothing was drawn
     *  before. */
    private boolean hasFill() {
        return mGradientState.mSolidColors != null
                || (mGradientState.mGradientColors != null
                    && mGradientState.mGradientColors.length > 0);
    }

    // ---- alpha / opacity --------------------------------------------
    @Override public void setAlpha(int alpha) {
        if (alpha != mAlpha) { mAlpha = alpha; invalidateSelf(); }
    }
    /** AOSP stores the argument VERBATIM here, unlike ColorDrawable's
     *  modulation -- the pair DRW.gd.setAlpha.getAlpha / DRW.cd.setAlphaOnOpaque
     *  exists to keep the two apart. */
    @Override public int getAlpha() { return mAlpha; }

    /** AOSP GradientDrawable.mColorFilter: a per-instance local, NOT part of the
     *  shared GradientState (so a sibling from the same ConstantState does not
     *  inherit it), exactly like mAlpha. */
    private android.graphics.ColorFilter mColorFilter;

    /** AOSP android14-release :1214-1226, verbatim:
     *      public ColorFilter getColorFilter() { return mColorFilter; }
     *      public void setColorFilter(@Nullable ColorFilter colorFilter) {
     *          if (colorFilter != mColorFilter) {
     *              mColorFilter = colorFilter;
     *              invalidateSelf();
     *          }
     *      }
     *  and draw() then uses `mColorFilter != null ? mColorFilter :
     *  mBlendModeColorFilter` (:758), i.e. an explicit colour filter REPLACES the
     *  tint filter for that draw. Both were empty here, so every
     *  `shape.setColorFilter(...)` was dropped (Fossify's 25%-primary search
     *  field painted at 100%). MACAPK_DRAWABLE_COLOR_FILTER=0: the old no-op. */
    @Override public void setColorFilter(android.graphics.ColorFilter cf) {
        if (!MACAPK_DRAWABLE_COLOR_FILTER) return;
        if (cf != mColorFilter) {
            mColorFilter = cf;
            invalidateSelf();
        }
    }
    @Override public android.graphics.ColorFilter getColorFilter() {
        return MACAPK_DRAWABLE_COLOR_FILTER ? mColorFilter : null;
    }

    /** The colour filter in force for this draw, or null. */
    private android.graphics.ColorFilter macapkActiveColorFilter() {
        return MACAPK_DRAWABLE_COLOR_FILTER ? mColorFilter : null;
    }

    /** `dst` through the explicit colour filter. A filter the base cannot model
     *  exactly (Drawable.macapkFilterColor answers null) is installed on the
     *  paint instead and Canvas's own resolution applies; `dst` is then painted
     *  as is under it. */
    private static int macapkFilterOrPaint(android.graphics.ColorFilter cf, int dst, Paint p) {
        Integer f = macapkFilterColor(cf, dst);
        if (f != null) return f;
        p.setColorFilter(cf);
        return dst;
    }

    /** AOSP :1893-1896. */
    @Override public int getOpacity() {
        return (mAlpha == 255 && mGradientState.mOpaqueOverBounds && isOpaqueForState())
                ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }

    /** AOSP :1886-1900. `mStrokePaint != null` is exactly "a stroke width was
     *  set", so it is expressed here as mStrokeWidth >= 0. */
    private boolean isOpaqueForState() {
        final GradientState s = mGradientState;
        if (s.mStrokeWidth >= 0 && !isOpaque(mStrokeColor)) return false;
        // A gradient's opacity was already decided in mOpaqueOverShape, so the
        // fill colour is only consulted for a solid fill.
        if (s.mGradientColors == null && !isOpaque(mFillColor)) return false;
        return true;
    }

    /** AOSP modulateAlpha (:1837-1840): scale an alpha by this drawable's own. */
    private int modulateAlpha(int alpha) {
        int scale = mAlpha + (mAlpha >> 7);
        return alpha * scale >> 8;
    }

    /** AOSP :1902-1947. Two contracts worth keeping intact: the corner radius
     *  is CLAMPED to min(w,h)*0.5 before it reaches the Outline, matching
     *  draw(); and the outline alpha is 0 unless the shape paints at a
     *  CONSISTENT opacity (no stroke, or a stroke whose alpha equals the
     *  fill's) -- an inconsistent one cannot cast a correct shadow. There is
     *  deliberately no RING case: AOSP falls through and leaves the outline
     *  empty (DRW.gdout.ring reports empty=1). */
    @Override public void getOutline(android.graphics.Outline outline) {
        final GradientState st = mGradientState;
        final Rect bounds = getBounds();
        final boolean useFillOpacity = st.mOpaqueOverShape
                && (st.mStrokeWidth <= 0 || (mStrokeColor >>> 24) == (mFillColor >>> 24));
        outline.setAlpha(useFillOpacity
                ? modulateAlpha(mFillColor >>> 24) / 255.0f
                : 0.0f);

        switch (st.mShape) {
            case RECTANGLE:
                if (st.mRadiusArray != null) {
                    Path p = new Path();
                    p.addRoundRect(new RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
                            st.mRadiusArray, Path.Direction.CW);
                    outline.setPath(p);
                    // A per-corner radius array is a PATH outline, as in AOSP,
                    // and clipping and ripple masks follow it exactly. The
                    // shadow casters here draw round rects only, so a popup or
                    // card with android:topLeftRadius etc. cast no shadow at
                    // all -- WhatsApp's overflow menu sat shadowless on the
                    // white screen. Hand them the LARGEST corner radius: the
                    // round rect it gives is the shape itself when the corners
                    // match, and otherwise lies inside it (a shadow may fall
                    // short of a square corner, never stick out past a round one).
                    float max = 0f;
                    for (float v : st.mRadiusArray) max = Math.max(max, v);
                    outline.macapkPathShadowRadius =
                            Math.min(max, Math.min(bounds.width(), bounds.height()) * 0.5f);
                    return;
                }
                float rad = 0;
                if (st.mRadius > 0.0f) {
                    rad = Math.min(st.mRadius, Math.min(bounds.width(), bounds.height()) * 0.5f);
                }
                outline.setRoundRect(bounds, rad);
                return;
            case OVAL:
                outline.setOval(bounds);
                return;
            case LINE: {
                // Hairlines (0-width stroke) must have a non-empty outline for
                // shadows to draw correctly, so AOSP uses a very small width.
                final float halfStrokeWidth = st.mStrokeWidth < 0 ? 0.0001f : st.mStrokeWidth * 0.5f;
                final float centerY = bounds.centerY();
                final int top = (int) Math.floor(centerY - halfStrokeWidth);
                final int bottom = (int) Math.ceil(centerY + halfStrokeWidth);
                outline.setRect(bounds.left, top, bounds.right, bottom);
                return;
            }
            default:
                // RING: AOSP has no case here either.
        }
    }

    // ---- state ------------------------------------------------------
    /** AOSP :1159-1165. */
    @Override public boolean isStateful() {
        final GradientState s = mGradientState;
        return super.isStateful()
                || (s.mSolidColors != null && s.mSolidColors.isStateful())
                || (s.mStrokeColors != null && s.mStrokeColors.isStateful());
    }

    /** AOSP :1117-1156. TRUE means the resolved fill or stroke colour actually
     *  MOVED, so a stateful CSL whose new state resolves to the same colour
     *  answers false (DRW.gd.cslStateful.setStateSelected = 0). */
    @Override public boolean onStateChange(int[] stateSet) {
        boolean invalidate = false;
        final GradientState s = mGradientState;
        if (s.mSolidColors != null) {
            final int newColor = s.mSolidColors.getColorForState(stateSet, 0);
            if (mFillColor != newColor) { mFillColor = newColor; invalidate = true; }
        }
        if (s.mStrokeColors != null) {
            final int newColor = s.mStrokeColors.getColorForState(stateSet, 0);
            if (mStrokeColor != newColor) { mStrokeColor = newColor; invalidate = true; }
        }
        if (mTintList != null && mTintMode != null) invalidate = true;
        if (invalidate) { invalidateSelf(); return true; }
        return false;
    }

    /** AOSP :1183-1190: a level change always repaints a GradientDrawable,
     *  because the gradient and the ring path are level-driven. */
    @Override public boolean onLevelChange(int level) {
        super.onLevelChange(level);
        invalidateSelf();
        return true;
    }

    @Override public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mGradientState.getChangingConfigurations();
    }

    /** AOSP :1881-1884 stamps the drawable's changing-configurations into the
     *  state on the way out, which is why the state reports them back
     *  (DRW.cs.chgcfgPropagatesToState). */
    @Override public ConstantState getConstantState() {
        mGradientState.mChangingConfigurations = getChangingConfigurations();
        return mGradientState;
    }

    /** Same env gate as ColorStateList.MACAPK_CSL_THEMELESS_AOSP (read here
     *  because that field is package-private to android.content.res). */
    private static final boolean MACAPK_CSL_THEMELESS_AOSP =
        !"0".equals(System.getenv("MACAPK_CSL_THEMELESS_AOSP"));

    /** AOSP applyTheme (android14-release :1430-1467): every colour list the
     *  shape holds that a theme-less inflate left with unresolved theme
     *  references is re-resolved against `t` --
     *      if (state.mTint != null && state.mTint.canApplyTheme()) {
     *          state.mTint = state.mTint.obtainForTheme(t);
     *      }
     *  and the same for mSolidColors and mStrokeColors -- then the local
     *  colours are re-derived (updateLocalState). Gate off: the base no-op. */
    @Override public void applyTheme(android.content.res.Resources.Theme t) {
        super.applyTheme(t);
        if (!MACAPK_CSL_THEMELESS_AOSP || t == null) return;
        final GradientState st = mGradientState;
        if (mTintList != null && mTintList.canApplyTheme()) {
            mTintList = mTintList.obtainForTheme(t);
        }
        if (st.mSolidColors != null && st.mSolidColors.canApplyTheme()) {
            st.setSolidColors(st.mSolidColors.obtainForTheme(t));
        }
        if (st.mStrokeColors != null && st.mStrokeColors.canApplyTheme()) {
            st.mStrokeColors = st.mStrokeColors.obtainForTheme(t);
            st.computeOpacity();
        }
        updateLocalState();
        invalidateSelf();
    }

    /** AOSP canApplyTheme (:1530 + GradientState.canApplyTheme :2225): true while
     *  any of those lists still has something to resolve. */
    @Override public boolean canApplyTheme() {
        if (!MACAPK_CSL_THEMELESS_AOSP) return super.canApplyTheme();
        final GradientState st = mGradientState;
        return (mTintList != null && mTintList.canApplyTheme())
            || (st.mSolidColors != null && st.mSolidColors.canApplyTheme())
            || (st.mStrokeColors != null && st.mStrokeColors.canApplyTheme())
            || super.canApplyTheme();
    }

    @Override public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mGradientState = new GradientState(mGradientState);
            updateLocalState();
            mMutated = true;
        }
        return this;
    }

    // ---- painting ---------------------------------------------------
    @Override public void draw(Canvas canvas) {
        if (canvas == null) return;
        Rect b = getBounds();
        if (b == null || b.width() <= 0 || b.height() <= 0) return;
        RectF r = new RectF(b.left, b.top, b.right, b.bottom);
        // The explicit colour filter (MACAPK_DRAWABLE_COLOR_FILTER). AOSP draw()
        // :758 `colorFilter = mColorFilter != null ? mColorFilter :
        // mBlendModeColorFilter`: when one is set it REPLACES the tint for this
        // draw. `cfExact` = the filter is one Drawable.macapkFilterColor models
        // exactly, so it is applied to the colours here; any other filter rides
        // on the paint and Canvas resolves it. cf == null keeps every line below
        // on its pre-change path.
        final android.graphics.ColorFilter cf = macapkActiveColorFilter();
        final boolean cfExact = cf != null && macapkFilterColor(cf, 0xFF000000) != null;
        // RING (MACAPK_SHAPE_RING). AOSP builds the ring from mRect, the bounds
        // inset by half the stroke width (ensureValidRect :1278-1287:
        // `if (mStrokePaint != null) inset = mStrokePaint.getStrokeWidth() *
        // 0.5f`), and draws BOTH the fill and the stroke along that one path
        // (draw :853-859: `Path path = buildRing(st); canvas.drawPath(path,
        // mFillPaint); if (haveStroke) canvas.drawPath(path, mStrokePaint);`).
        // The gradient is laid over mRect as well, so `r` becomes mRect here.
        Path ringPath = null;
        if (MACAPK_SHAPE_RING && mGradientState.mShape == RING) {
            final float inset = mGradientState.mStrokeWidth >= 0
                    ? mGradientState.mStrokeWidth * 0.5f : 0f;
            r = new RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
            ringPath = buildRing(r);
        }
        // AOSP draw() (android14-release :797-801), the no-layer branch:
        //     mFillPaint.setColorFilter(colorFilter);
        //     if (colorFilter != null && st.mSolidColors == null) {
        //         mFillPaint.setColor(mAlpha << 24);
        //     }
        // and the fill is then drawn for every shape but LINE (RECTANGLE's
        // plain-rect case draws when `colorFilter != null`, :830-832; OVAL,
        // the rounded rect, the radii path and RING draw unconditionally). So a
        // tinted shape with NO fill of its own -- a stroke-only or empty
        // <shape android:tint>, or setTintList on one, which is how AppCompat /
        // Material backgroundTint reaches a GradientDrawable -- paints the tint
        // across the whole shape (opaque black at the drawable's alpha, SRC_IN
        // the tint). hasFill() alone never painted it. MACAPK_SHAPE_TINT=0:
        // false, the pre-change path. An explicit colour filter on such a shape
        // is the same case (`filterOnly`).
        final boolean noOwnFill = mGradientState.mSolidColors == null
                && (mGradientState.mGradientColors == null
                    || mGradientState.mGradientColors.length == 0)
                && mGradientState.mShape != LINE;
        final boolean tintOnly = MACAPK_SHAPE_TINT && mTintList != null && mTintMode != null
                && noOwnFill;
        final boolean filterOnly = cf != null && noOwnFill;
        if (hasFill() || tintOnly || filterOnly) {
            Paint p = new Paint();
            p.setAntiAlias(true);
            p.setStyle(Paint.Style.FILL);
            android.graphics.Shader shader = buildGradientShader(r, cf, cfExact);
            if (shader != null) {
                // Per-stop alpha is already folded in by buildGradientShader
                // (applyAlpha applied to each color), so the shader paints
                // this drawable's setAlpha() as authored with no extra gate
                // here. So is the tint (MACAPK_SHAPE_TINT, macapkTintStop), and
                // so is an exactly-modelled colour filter (macapkFilterStop).
                p.setShader(shader);
                if (cf != null && !cfExact) p.setColorFilter(cf);
                drawShape(canvas, r, p, ringPath);
            } else if (cf != null) {
                // The colour filter replaces the tint (AOSP :758). The fill
                // paint's colour is the solid at the drawable's alpha, or
                // `mAlpha << 24` when the shape has no solid of its own
                // (:799-801); a one-stop gradient keeps its single stop, as the
                // unfiltered branch below does.
                int base = filterOnly ? 0xFF000000 : mFillColor;
                if (mGradientState.mSolidColors == null && mGradientState.mGradientColors != null
                        && mGradientState.mGradientColors.length > 0) {
                    base = mGradientState.mGradientColors[0];
                }
                int filtered = macapkFilterOrPaint(cf, applyAlpha(base), p);
                if ((filtered >>> 24) != 0 || p.getColorFilter() != null) {
                    p.setColor(filtered);
                    drawShape(canvas, r, p, ringPath);
                }
            } else {
                // Solid fill: honor a tint (Material/AppCompat <shape>
                // backgrounds tinted at bind time via setTintList --
                // button/chip/surface backgrounds) over the authored
                // fill colour. v1.0.423. With MACAPK_SHAPE_TINT=0 the tint does
                // not apply to the gradient branch above -- AOSP composites a
                // tint as a ColorFilter over whatever is already painted
                // (gradient or solid; with the gate on the gradient's stops are
                // filtered in buildGradientShader). With the gate off this tree's
                // macapkEffectiveTint() model REPLACES the paint color
                // outright, which only makes sense for a single solid fill
                // colour; with the gate on (the default) the solid is filtered
                // the AOSP way just below.
                int eff = macapkEffectiveTint();
                // A ONE-stop gradient array has no Shader to build but still
                // has to paint something; AOSP would hand Skia a degenerate
                // gradient. Falling back to the single stop keeps the
                // long-standing Mac.apk behaviour for that shape.
                int base = tintOnly ? 0xFF000000 : mFillColor;   // AOSP mAlpha << 24 (applyAlpha below)
                if (mGradientState.mSolidColors == null && mGradientState.mGradientColors != null
                        && mGradientState.mGradientColors.length > 0) {
                    base = mGradientState.mGradientColors[0];
                }
                int fc = (eff != 0) ? eff : base;
                if (MACAPK_SHAPE_TINT) {
                    // AOSP draw() (android14-release :758, :796-802): the tint is
                    // a BlendModeColorFilter ON THE PAINT, so the filter sees the
                    // fill colour after its alpha was modulated, and the result
                    // is the blend of the two, not the tint alone. For the
                    // default SRC_IN that is the tint's colour at
                    // tintAlpha * fillAlpha: a white solid tinted
                    // ?attr/colorControlActivated paints exactly the accent
                    // (text_cursor_material), and a HALF-transparent solid stays
                    // half transparent, which the replacement above did not.
                    int tinted = macapkApplyTintFilter(applyAlpha(base));
                    if ((tinted >>> 24) != 0) {
                        p.setColor(tinted);
                        drawShape(canvas, r, p, ringPath);
                    }
                    fc = 0;   // painted (or blended to nothing) above
                }
                // DIAGNOSTIC (MACAPK_TRACE_SHAPEFILL=1): which of the two wins,
                // and with what.
                if ("1".equals(ENV_MACAPK_TRACE_SHAPEFILL)) {
                    android.util.Log.i("Mac.apk.ShapeFill",
                        "fill=0x" + Integer.toHexString(base)
                        + " tint=0x" + Integer.toHexString(eff)
                        + " -> 0x" + Integer.toHexString(fc)
                        + " alpha=" + mAlpha + " bounds=" + b.width() + "x" + b.height());
                }
                if (!MACAPK_SHAPE_TINT && (fc >>> 24) != 0) {
                    p.setColor(applyAlpha(fc));
                    drawShape(canvas, r, p, ringPath);
                }
            }
        }
        final int strokeWidth = mGradientState.mStrokeWidth;
        if (strokeWidth > 0 && (mStrokeColor >>> 24) != 0) {
            Paint p = new Paint();
            p.setAntiAlias(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeWidth);
            // AOSP applies the same colour filter to the stroke paint
            // (`mStrokePaint.setColorFilter(colorFilter)`, :805-808), and an
            // explicit colour filter wins over the tint there too.
            if (cf != null) {
                p.setColor(macapkFilterOrPaint(cf, applyAlpha(mStrokeColor), p));
            } else {
                p.setColor(MACAPK_SHAPE_TINT ? macapkApplyTintFilter(applyAlpha(mStrokeColor))
                                             : applyAlpha(mStrokeColor));
            }
            if (ringPath != null) {
                // The ring's stroke runs along the SAME path as its fill, built
                // from the already-inset mRect (AOSP draw :855-858).
                canvas.drawPath(ringPath, p);
            } else {
                // Inset by half the stroke so the outline stays inside the bounds.
                float h = strokeWidth / 2f;
                RectF sr = new RectF(r.left + h, r.top + h, r.right - h, r.bottom - h);
                drawShape(canvas, sr, p, null);
            }
        }
    }

    /** AOSP GradientDrawable.buildRing (android14-release :1017-1066), ported
     *  line for line, including its quirks:
     *   - `sweep` follows the level only when mUseLevelForShape (default TRUE),
     *     so a ring whose XML does not say useLevel="false" draws nothing at
     *     level 0;
     *   - x / y are HALF the rect's size, used both as the inset that centres
     *     the inner oval (correct for any origin) and as the partial sweep's
     *     start point (which therefore assumes a rect at the origin, as AOSP's
     *     does);
     *   - thickness and inner radius fall back to width / ratio.
     *  ONE deliberate deviation: the full-sweep case sets EVEN_ODD. AOSP adds
     *  the outer oval CW and the inner CCW under the default WINDING rule, so
     *  the inner winding cancels to 0 and leaves a hole. Our Path.addOval
     *  ignores its Direction argument (both ovals wind the same way), which
     *  under WINDING would fill the hole. For two NESTED ovals EVEN_ODD gives
     *  exactly the AOSP coverage (2 crossings inside the inner one, 1 between),
     *  so the result is identical and does not depend on Path's direction
     *  support. No per-draw cache: AOSP's mRingPath/mPathIsDirty is a
     *  performance detail, and rebuilding each draw is always current. */
    private Path buildRing(RectF rect) {
        final GradientState st = mGradientState;
        float sweep = st.mUseLevelForShape ? (360.0f * getLevel() / 10000.0f) : 360f;

        RectF bounds = new RectF(rect);

        float x = bounds.width() / 2.0f;
        float y = bounds.height() / 2.0f;

        float thickness = st.mThickness != -1
                ? st.mThickness : bounds.width() / st.mThicknessRatio;
        // inner radius
        float radius = st.mInnerRadius != -1
                ? st.mInnerRadius : bounds.width() / st.mInnerRadiusRatio;

        RectF innerBounds = new RectF(bounds);
        innerBounds.inset(x - radius, y - radius);

        bounds = new RectF(innerBounds);
        bounds.inset(-thickness, -thickness);

        final Path ringPath = new Path();
        // arcTo treats the sweep angle mod 360, so check for that, since we
        // think 360 means draw the entire oval
        if (sweep < 360 && sweep > -360) {
            ringPath.setFillType(Path.FillType.EVEN_ODD);
            // inner top
            ringPath.moveTo(x + radius, y);
            // outer top
            ringPath.lineTo(x + radius + thickness, y);
            // outer arc
            ringPath.arcTo(bounds, 0.0f, sweep, false);
            // inner arc
            ringPath.arcTo(innerBounds, sweep, -sweep, false);
            ringPath.close();
        } else {
            // add the entire ovals (see the EVEN_ODD note above)
            ringPath.setFillType(Path.FillType.EVEN_ODD);
            ringPath.addOval(bounds, Path.Direction.CW);
            ringPath.addOval(innerBounds, Path.Direction.CCW);
        }
        return ringPath;
    }

    /** The paint colour after AOSP's tint filter: `dst` is what the paint
     *  would draw, the filter colour is the tint for the current state
     *  (Drawable.updateBlendModeFilter, android14-release :1590-1602:
     *  `tint.getColorForState(getState(), Color.TRANSPARENT)`), and the mode is
     *  the tint mode. No tint or no mode: `dst` unchanged. The drawable paints
     *  one flat colour per pass, so blending that colour once is the same as
     *  filtering every pixel of it. */
    private int macapkApplyTintFilter(int dst) {
        if (mTintList == null || mTintMode == null) return dst;
        int tint = mTintList.getColorForState(getState(), 0x00000000);
        return macapkBlend(tint, dst, mTintMode);
    }

    /** A gradient STOP after AOSP's tint filter. AOSP sets the filter on the
     *  fill paint whatever it carries (draw() :797: the shader's output,
     *  already modulated by the paint alpha, is filtered per pixel). Filtering
     *  the stops instead is EXACT for the modes whose unpremultiplied result
     *  is linear in the destination colour and alpha -- SRC_IN (the default:
     *  the tint's RGB at tintAlpha x gradientAlpha), SRC_ATOP, MULTIPLY,
     *  DST_IN -- because the gradient interpolates unpremultiplied stops
     *  linearly; for the rest (SRC_OVER, SCREEN, ...) it is an interpolation
     *  of the right endpoints, not per-pixel exact. A stop the filter makes
     *  fully transparent keeps the RGB a per-pixel filter gives its
     *  neighbours (the colour computed as if the stop were opaque), so a fade
     *  to a transparent stop does not interpolate through black. */
    private int macapkTintStop(int stop) {
        if (mTintList == null || mTintMode == null) return stop;
        int out = macapkApplyTintFilter(stop);
        if ((out >>> 24) == 0) out = macapkApplyTintFilter(stop | 0xFF000000) & 0x00FFFFFF;
        return out;
    }

    /** A gradient STOP through an exactly-modelled colour filter: the same
     *  stop-filtering argument, and the same transparent-stop RGB rule, as
     *  macapkTintStop above. Only called with a filter macapkFilterColor
     *  models (draw passes null otherwise and puts the filter on the paint). */
    private static int macapkFilterStop(android.graphics.ColorFilter cf, int stop) {
        Integer out = macapkFilterColor(cf, stop);
        if (out == null) return stop;
        if ((out >>> 24) == 0) {
            Integer opaque = macapkFilterColor(cf, stop | 0xFF000000);
            return opaque == null ? out : (opaque & 0x00FFFFFF);
        }
        return out;
    }

    /** One Porter-Duff composite of `src` (the filter's colour) over `dst`
     *  (the painted colour), per android14-release PorterDuff.java's own
     *  formulas (:187-360, in PREMULTIPLIED components, alpha a and colour C):
     *    SRC_IN    a = as*ad             C = Cs*ad
     *    SRC_ATOP  a = ad                C = ad*Cs + (1-as)*Cd
     *    SRC_OVER  a = as + (1-as)*ad    C = Cs + (1-as)*Cd
     *    MULTIPLY  a = as*ad             C = Cs*Cd
     *    SCREEN    a = as + ad - as*ad   C = Cs + Cd - Cs*Cd
     *    ADD       a = min(as+ad, 1)     C = min(Cs+Cd, 1)
     *  plus the rest of the table for a mode an app sets in code. The result
     *  is un-premultiplied back to an ARGB int. */
    static int macapkBlend(int src, int dst, android.graphics.PorterDuff.Mode mode) {
        float as = ((src >>> 24) & 0xff) / 255f, ad = ((dst >>> 24) & 0xff) / 255f;
        float[] cs = { ((src >> 16) & 0xff) / 255f * as, ((src >> 8) & 0xff) / 255f * as,
                       (src & 0xff) / 255f * as };
        float[] cd = { ((dst >> 16) & 0xff) / 255f * ad, ((dst >> 8) & 0xff) / 255f * ad,
                       (dst & 0xff) / 255f * ad };
        float ao;
        float[] co = new float[3];
        switch (mode) {
            case CLEAR:    ao = 0f; break;
            case SRC:      ao = as; break;
            case DST:      ao = ad; break;
            case SRC_OVER: ao = as + (1 - as) * ad; break;
            case DST_OVER: ao = ad + (1 - ad) * as; break;
            case SRC_IN:   ao = as * ad; break;
            case DST_IN:   ao = as * ad; break;
            case SRC_OUT:  ao = (1 - ad) * as; break;
            case DST_OUT:  ao = (1 - as) * ad; break;
            case SRC_ATOP: ao = ad; break;
            case DST_ATOP: ao = as; break;
            case XOR:      ao = (1 - ad) * as + (1 - as) * ad; break;
            case ADD:      ao = Math.max(0f, Math.min(as + ad, 1f)); break;
            case MULTIPLY: ao = as * ad; break;
            default:       ao = as + ad - as * ad; break;   // DARKEN LIGHTEN SCREEN OVERLAY
        }
        for (int i = 0; i < 3; i++) {
            float s = cs[i], d = cd[i];
            float c;
            switch (mode) {
                case CLEAR:    c = 0f; break;
                case SRC:      c = s; break;
                case DST:      c = d; break;
                case SRC_OVER: c = s + (1 - as) * d; break;
                case DST_OVER: c = d + (1 - ad) * s; break;
                case SRC_IN:   c = s * ad; break;
                case DST_IN:   c = d * as; break;
                case SRC_OUT:  c = (1 - ad) * s; break;
                case DST_OUT:  c = (1 - as) * d; break;
                case SRC_ATOP: c = ad * s + (1 - as) * d; break;
                case DST_ATOP: c = as * d + (1 - ad) * s; break;
                case XOR:      c = (1 - ad) * s + (1 - as) * d; break;
                case DARKEN:   c = (1 - ad) * s + (1 - as) * d + Math.min(s, d); break;
                case LIGHTEN:  c = (1 - ad) * s + (1 - as) * d + Math.max(s, d); break;
                case MULTIPLY: c = s * d; break;
                case SCREEN:   c = s + d - s * d; break;
                case ADD:      c = Math.max(0f, Math.min(s + d, 1f)); break;
                case OVERLAY:  c = (2 * d < ad) ? 2 * s * d
                                                : as * ad - 2 * (ad - s) * (as - d); break;
                default:       c = s * ad; break;
            }
            co[i] = c;
        }
        int a = Math.round(Math.max(0f, Math.min(1f, ao)) * 255f);
        if (a == 0) return 0;
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            float un = co[i] / ao;   // un-premultiply
            rgb[i] = Math.round(Math.max(0f, Math.min(1f, un)) * 255f);
        }
        return (a << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    private int applyAlpha(int color) {
        if (mAlpha >= 255) return color;
        int a = (color >>> 24) * mAlpha / 255;
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** Builds the Shader for a real multi-stop gradient, mirroring AOSP
     *  GradientDrawable.ensureValidRect()'s orientation-to-endpoint switch
     *  and radius formula. Returns null when there is no multi-stop gradient
     *  configured (fewer than 2 colors), so draw() falls back to the plain
     *  solid fill path -- the dominant single-color <shape> case renders
     *  byte-identically to before this change.
     *  `cf` is the explicit colour filter in force (null = none) and `cfExact`
     *  says Drawable.macapkFilterColor models it, in which case each stop is
     *  filtered here. A filter it cannot model rides on the paint (draw() puts
     *  it there), but it STILL replaces the tint -- AOSP draw :758 picks
     *  `mColorFilter != null ? mColorFilter : mBlendModeColorFilter`, one or the
     *  other, never both -- so no stop is tinted while any filter is set. */
    private android.graphics.Shader buildGradientShader(RectF r, android.graphics.ColorFilter cf,
                                                        boolean cfExact) {
        final GradientState st = mGradientState;
        final int[] colors = st.mGradientColors;
        if (colors == null || colors.length < 2) return null;
        int n = colors.length;
        int[] stops = new int[n];
        for (int i = 0; i < n; i++) {
            stops[i] = applyAlpha(colors[i]);
            // An explicit colour filter replaces the tint (AOSP draw :758).
            if (cf != null && cfExact) stops[i] = macapkFilterStop(cf, stops[i]);
            else if (cf == null && MACAPK_SHAPE_TINT) stops[i] = macapkTintStop(stops[i]);
        }
        // AOSP: `level = mUseLevel ? getLevel() / 10000f : 1.0f`, applied to
        // BOTH the linear endpoints and the radial radius (not radius-only).
        float level = st.mUseLevel ? (getLevel() / 10000f) : 1f;
        switch (st.mGradientType) {
            case RADIAL_GRADIENT: {
                // AOSP: radius *= min(width, height) for RADIUS_TYPE_FRACTION
                // (the only mode this class exposes via setGradientRadius),
                // then *= getLevel()/10000f when useLevel.
                float radius = st.mGradientRadius * Math.min(r.width(), r.height());
                if (st.mUseLevel) radius *= level;
                if (radius <= 0f) return null;
                float cx = r.left + st.mCenterX * r.width();
                float cy = r.top + st.mCenterY * r.height();
                return new android.graphics.RadialGradient(cx, cy, radius, stops, st.mPositions,
                    android.graphics.Shader.TileMode.CLAMP);
            }
            case SWEEP_GRADIENT: {
                float cx = r.left + st.mCenterX * r.width();
                float cy = r.top + st.mCenterY * r.height();
                return new android.graphics.SweepGradient(cx, cy, stops, st.mPositions);
            }
            default: { // LINEAR_GRADIENT
                float x0, y0, x1, y1;
                switch (st.mOrientation) {
                    case TOP_BOTTOM: x0 = r.left;  y0 = r.top;    x1 = x0;              y1 = level * r.bottom; break;
                    case TR_BL:      x0 = r.right; y0 = r.top;    x1 = level * r.left;  y1 = level * r.bottom; break;
                    case RIGHT_LEFT: x0 = r.right; y0 = r.top;    x1 = level * r.left;  y1 = y0;                break;
                    case BR_TL:      x0 = r.right; y0 = r.bottom; x1 = level * r.left;  y1 = level * r.top;    break;
                    case BOTTOM_TOP: x0 = r.left;  y0 = r.bottom; x1 = x0;              y1 = level * r.top;    break;
                    case BL_TR:      x0 = r.left;  y0 = r.bottom; x1 = level * r.right; y1 = level * r.top;    break;
                    case LEFT_RIGHT: x0 = r.left;  y0 = r.top;    x1 = level * r.right; y1 = y0;                break;
                    default:         x0 = r.left;  y0 = r.top;    x1 = level * r.right; y1 = level * r.bottom; break; // TL_BR
                }
                return new android.graphics.LinearGradient(x0, y0, x1, y1, stops, st.mPositions,
                    android.graphics.Shader.TileMode.CLAMP);
            }
        }
    }

    /** `ringPath` non-null = a RING under MACAPK_SHAPE_RING: every pass paints
     *  that one path (AOSP draw :853-859), whatever `r` says. */
    private void drawShape(Canvas canvas, RectF r, Paint p, Path ringPath) {
        if (ringPath != null) {
            canvas.drawPath(ringPath, p);
            return;
        }
        final GradientState st = mGradientState;
        if (st.mShape == OVAL) {
            canvas.drawOval(r, p);
        } else if (st.mRadiusArray != null && st.mRadiusArray.length >= 8) {
            // AOSP draw()'s RECTANGLE case: "if (st.mRadiusArray != null)"
            // takes priority over the scalar radius unconditionally and is
            // drawn via a Path, not canvas.drawRoundRect. A malformed
            // (<8-length) array falls through to the scalar/rect branches below
            // instead of matching Path.addRoundRect's own silent no-op on a
            // short array (which would paint nothing).
            Path path = new Path();
            path.addRoundRect(r, st.mRadiusArray, Path.Direction.CW);
            canvas.drawPath(path, p);
        } else if (st.mRadius > 0f) {
            // AOSP clamps the uniform radius to half the shorter side so a
            // very wide/short rect doesn't hit Skia's independent per-axis
            // clamp (a "thin ellipse" look) -- same draw() RECTANGLE case.
            float rad = Math.min(st.mRadius, Math.min(r.width(), r.height()) * 0.5f);
            canvas.drawRoundRect(r, rad, rad, p);
        } else {
            canvas.drawRect(r, p);
        }
    }
}
