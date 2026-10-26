package android.widget;
import android.content.Context;
import android.view.View;

/** ImageView -- displays a Bitmap or Drawable. v1.0.69 first wiring of
 *  the onDraw path so the image actually appears on Canvas. Mirrors
 *  AOSP ImageView at the level needed for plain Activity layouts
 *  (Frozen Bubble's HomeScreen logo, F-Droid app-row icons, etc.).
 *  Scaling/cropping behavior follows the AOSP ScaleType contract
 *  (FIT_CENTER default, CENTER and FIT_XY common alternatives). */
public class ImageView extends View {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_SKIP_ALL_IMAGE_DRAW = System.getenv("MACAPK_SKIP_ALL_IMAGE_DRAW");
    private static final String ENV_MACAPK_SKIP_IMAGEVIEW_DRAW = System.getenv("MACAPK_SKIP_IMAGEVIEW_DRAW");
    private static final String ENV_MACAPK_TRACE_FEATUREIMG = System.getenv("MACAPK_TRACE_FEATUREIMG");
    private static final String ENV_MACAPK_TRACE_IMAGEVIEW_DRAW = System.getenv("MACAPK_TRACE_IMAGEVIEW_DRAW");
    private static final String ENV_MACAPK_TRACE_IMAGEVIEW_MEASURE = System.getenv("MACAPK_TRACE_IMAGEVIEW_MEASURE");
    private static final String ENV_MACAPK_TRACE_IMAGEVIEW_SET = System.getenv("MACAPK_TRACE_IMAGEVIEW_SET");
    private static final String ENV_MACAPK_TRACE_TINT = System.getenv("MACAPK_TRACE_TINT");

    public enum ScaleType { MATRIX, FIT_XY, FIT_START, FIT_CENTER, FIT_END, CENTER, CENTER_CROP, CENTER_INSIDE }

    private android.graphics.Bitmap   bitmap;
    private android.graphics.drawable.Drawable drawable;
    private ScaleType scaleType = ScaleType.FIT_CENTER;
    // 2026-09-25: the v1.0.448 `macapkImageScalePct` field (fed by a
    // LayoutInflater `case "imageScalePercent"`) was DELETED. That attribute is
    // PRIVATE to Google Calculator's PadImageButton, and the app applies it
    // itself: its ctor reads it through obtainStyledAttributes and its onDraw
    // does `canvas.scale(a, a, getWidth()*0.5f, getHeight()*0.5f)` before
    // super.onDraw (javap via debug/appclass.py). Interpreting it here too
    // squared the factor, so the keypad glyphs drew at ~0.65x the emulator's.
    // The v1.0.448 symptom it papered over (operators ~1.7x too big) came from
    // the old 6x-intrinsic cap letting 48dp glyphs fill the button; the
    // drawable path now honours FIT_CENTER for every glyph (lane layout B,
    // 2026-08-02), so the app's own scale is the only one left, as on a device.
    private android.graphics.ColorFilter colorFilter;
    // AOSP ImageView.mDrawableTintList / mHasDrawableTint / mDrawableTintMode
    // (frameworks/base core/java/android/widget/ImageView.java ~line 733
    // applyImageTint / ~line 651 setImageTintList): setImageTintList/
    // setImageTintMode were previously `{}` no-ops and their getters
    // hardcoded null with no backing field at all -- so any tint an app
    // configured (very common for Material monochrome vector icons, which
    // are drawn via a SINGLE tinted drawable rather than per-icon color
    // variants) was silently discarded, and getImageTintList() could never
    // observe it either. Same defect shape as ProgressBar's drawable
    // accessors (fixed the same day). AOSP collapses onto BlendMode
    // (setTintBlendMode); this tree keeps the PorterDuff.Mode-based
    // Drawable.setTintList/setTintMode surface CompoundButton.applyButtonTint
    // and ProgressBar already use, which is the same observable public API.
    private android.content.res.ColorStateList mImageTintList;
    private android.graphics.PorterDuff.Mode mImageTintMode;
    private boolean mHasImageTint;
    private boolean mHasImageTintMode;
    private boolean traced;  // v1.0.100: one-shot tracing flag (per-instance, ever-drawn)
    /** v1.0.101 mystery-icon diagnostic: tally per-instance draw count.
     *  Used together with MACAPK_TRACE_IMAGEVIEW_DRAW=verbose to log
     *  EVERY ImageView draw on the first ~3 frames, not just the first
     *  draw per instance. The one-shot trace (above) hides
     *  double-draws of the same ImageView; this counter exposes them. */
    private int drawCount;

    public ImageView(Context c) { super(c); }
    public ImageView(Context c, android.util.AttributeSet a) { super(c, a); macapkApplySrc(c, a, 0); }
    public ImageView(Context c, android.util.AttributeSet a, int s) { super(c, a, s); macapkApplySrc(c, a, s); }

    /** AOSP ImageView.<init> reads android:src from the styled attributes and
     *  applies it. We never did, so a programmatically-styled ImageView whose
     *  src lives in its defStyleAttr style got no image -- notably AppCompat's
     *  OverflowMenuButton (constructed `super(ctx, null, actionOverflowButtonStyle)`),
     *  whose 3-dot overflow vector comes from Widget.*.ActionButton.Overflow's
     *  android:src. Now that Context.obtainStyledAttributes resolves the defStyle
     *  style, read android:src (framework attr id 0x01010119) by NAME and apply it.
     *  Only sets when a REAL src resource resolves (getResourceId != 0), so a plain
     *  programmatic ImageView (no attrs, no styled src) is unaffected. */
    private void macapkApplySrc(Context c, android.util.AttributeSet a, int defStyleAttr) {
        macapkApplySrc(c, a, defStyleAttr, 0);
    }
    private void macapkApplySrc(Context c, android.util.AttributeSet a, int defStyleAttr, int defStyleRes) {
        if (c == null) return;
        try {
            android.content.res.TypedArray ta = c.obtainStyledAttributes(
                a, new int[]{0x01010119 /* android.R.attr.src */}, defStyleAttr, defStyleRes);
            int srcId = ta.getResourceId(0, 0);
            if (srcId != 0) {
                android.graphics.drawable.Drawable d = c.getResources().getDrawable(srcId);
                if (d != null) setImageDrawable(d);
            }
            ta.recycle();
        } catch (Throwable ignored) {}
        if (MACAPK_IMAGEVIEW_THEME_TINT) macapkApplyStyledTint(c, a, defStyleAttr, defStyleRes);
    }

    /** AOSP ImageView.<init> (android14 ImageView.java:213-230):
     *      if (a.hasValue(R.styleable.ImageView_tint)) {
     *          mDrawableTintList = a.getColorStateList(R.styleable.ImageView_tint);
     *          mHasDrawableTint = true;
     *          // Prior to L, this attribute would always set a color filter with
     *          // blending mode SRC_ATOP. Preserve that default behavior.
     *          mDrawableBlendMode = BlendMode.SRC_ATOP; mHasDrawableBlendMode = true;
     *      }
     *  obtainStyledAttributes answers from the view's XML, its style, the
     *  default style AND, last, the THEME -- so a theme item
     *  `<item name="android:tint">?iconTint</item>` tints every ImageView that
     *  does not say otherwise. NoNonsense Notes' ThemeNnnLight does exactly
     *  that (iconTint = black_alpha85) over white-filled vector icons; we read
     *  android:tint only from the XML attribute (LayoutInflater's "tint" case),
     *  so its settings icons drew white on the light grey list -- previously
     *  hidden by VectorDrawable's white-to-#333 stand-in. Read here, only when
     *  the attribute resolves, so an untinted ImageView is untouched; the
     *  inflater's direct XML case still runs afterwards and wins.
     *  MACAPK_IMAGEVIEW_THEME_TINT=0 restores the XML-only read. */
    static final boolean MACAPK_IMAGEVIEW_THEME_TINT =
        !"0".equals(System.getenv("MACAPK_IMAGEVIEW_THEME_TINT"));
    private void macapkApplyStyledTint(Context c, android.util.AttributeSet a,
                                       int defStyleAttr, int defStyleRes) {
        try {
            android.content.res.TypedArray ta = c.obtainStyledAttributes(
                a, new int[]{0x01010121 /* android.R.attr.tint */}, defStyleAttr, defStyleRes);
            try {
                if (ta.hasValue(0)) {
                    android.content.res.ColorStateList csl = ta.getColorStateList(0);
                    if (csl != null) {
                        setImageTintList(csl);
                        setImageTintMode(android.graphics.PorterDuff.Mode.SRC_ATOP);
                    }
                }
            } finally {
                ta.recycle();
            }
        } catch (Throwable ignored) {}
    }

    public void setImageBitmap(android.graphics.Bitmap b) {
        this.bitmap = b;
        // A BITMAP MUST ALSO BECOME A DRAWABLE (v1.0.868). AOSP's
        // ImageView.setImageBitmap does not store a bare bitmap at all -- it
        // wraps it and routes through setImageDrawable:
        //     mRecycleableBitmapDrawable = new BitmapDrawable(mContext.getResources(), bm);
        //     setImageDrawable(mRecycleableBitmapDrawable);
        // so getDrawable(), onMeasure's intrinsic-size path, tint and scaleType
        // all see it. Ours set only the `bitmap` field, and onMeasure begins
        //     Drawable d = drawable; if (d == null) { super.onMeasure(...); return; }
        // -- so a bitmap-backed ImageView had NO intrinsic size and fell through
        // to plain View measurement.
        //
        // MEASURED victim (tobsolitaire): its game grid builds 17 ImageViews and
        // hands each a real 333x353 bitmap (proven at runtime), with
        // `adjustViewBounds=true` and LayoutParams(0, MATCH_PARENT) inside a
        // wrap_content TableRow. With no intrinsic height, MATCH_PARENT in a
        // wrap_content row resolves to ZERO: every cell measured 192x0 and the
        // entire grid was invisible while the emulator drew 17 labelled tiles.
        //
        // onDraw is UNAFFECTED: it checks `this.bitmap` first and only falls
        // back to `drawable`, so the existing bitmap paint path still wins and
        // nothing can double-draw.
        // Guarded: this is called straight from app/library code (Glide and
        // friends) on the main thread, so it must not be able to throw where it
        // previously could not. getResources() needs a live Context.
        if (b == null) {
            this.drawable = null;
        } else {
            try {
                this.drawable = new android.graphics.drawable.BitmapDrawable(getResources(), b);
            } catch (Throwable t) {
                this.drawable = new android.graphics.drawable.BitmapDrawable(b);
            }
        }
        // v1.0.110: AOSP semantics -- swapping the bitmap MUST trigger
        // a redraw. Glide calls into setImageBitmap (via BitmapDrawable
        // unwrap) on the main thread once decode completes; without
        // invalidate the new pixels stay invisible until something
        // else dirties the view tree (which our per-frame full-redraw
        // makes free, but other paths might not).
        invalidate();
    }
    public android.graphics.Bitmap getDrawingCache() { return bitmap; }

    public void setImageDrawable(android.graphics.drawable.Drawable d) {
        this.drawable = d;
        // v1.0.132: heuristic LP-rescue. Material's NavigationBarItem-
        // View.setIconSize(?attr/itemIconSize) sets LP to (0, 0) when
        // our framework returns 0 for that dimension resource (we
        // don't fully resolve dimens.xml refs from styleables yet).
        // That collapses the icon to invisible. v1.0.131 first tried
        // promoting to WRAP_CONTENT, but ViewCanvasDriver skips the
        // measure cascade for non-RV subtrees so the icon ended up
        // 0x0 anyway. v1.0.132 second pass measured the BottomNav
        // subtree explicitly -- but NavigationBarMenuView's APK-side
        // onMeasure has its own bug (passes EXACTLY 1 to children when
        // measure is invoked from outside its natural flow), so the
        // icons ended up 1x1.
        //
        // v1.0.132 final: set the LP to an EXPLICIT pixel size of 24dp
        // = 48px at density=2 (the Material 3 default
        // mtrl_navigation_bar_item_default_icon_size). That sidesteps
        // both the measure-cascade gap AND the LinearLayout-weight bug
        // -- the icon ImageView's LP is EXACTLY 48 in each dimension,
        // its parent FrameLayout's onLayout positions it at that size,
        // and no measure pass is required.
        //
        // Pre-condition is narrow: LP must be explicitly (0, 0), not
        // WRAP_CONTENT (-2) or MATCH_PARENT (-1). Only Material's
        // setIconSize(0) hits this branch; an APK that legitimately
        // wants a 0x0 ImageView would have to type the integer
        // literal 0 which is uncommon. Generic for any Material widget
        // that calls setIconSize / setItemIconSize with an unresolved
        // dimension styleable.
        //
        // v1.0.364: do NOT apply the rescue when this view is a child of
        // a ConstraintLayout (or MotionLayout subclass). There, layout_
        // width/height == 0 is the MATCH_CONSTRAINT (0dp) sentinel, not a
        // collapsed pixel size -- androidx ConstraintLayout.LayoutParams.
        // validate() keys mHorizontalDimensionFixed entirely off
        // width==0 (-> MATCH_CONSTRAINT) vs width>0 (-> FIXED). Rewriting
        // 0/0 to 48px turns a MATCH_CONSTRAINT chain child into a FIXED
        // 48px one, which the solver then centers in its cell at 48x48
        // instead of spreading it to fill (the Calculator dec_point "."
        // collapse: its src drawable is re-set after its 0dp LP is
        // attached, so the rescue fired on the second setImageDrawable).
        // The Material bottom-nav-icon case this rescue exists for has a
        // FrameLayout/LinearLayout parent where 0 genuinely means 0px, so
        // gating on the constraint parent keeps that fix intact.
        if (d != null) {
            android.view.ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null && lp.width == 0 && lp.height == 0
                    && !zeroWidthMeansMatchConstraint()) {
                int density = Math.max(1, Math.round(android.util.DisplayMetrics.macapkDeviceDensity()));
                try {
                    if (getResources() != null
                        && getResources().getDisplayMetrics() != null
                        && getResources().getDisplayMetrics().density > 0f) {
                        density = (int) getResources().getDisplayMetrics().density;
                    }
                } catch (Throwable ignored) {}
                int px = 24 * density;  // 24dp Material default
                lp.width = px;
                lp.height = px;
                setLayoutParams(lp);
            }
        }
        android.graphics.Bitmap extracted = null;
        if (d != null) {
            // v1.0.110: BitmapDrawable + RoundedBitmapDrawable both
            // expose getBitmap(); Glide's transition (DrawableCrossFade)
            // can also hand us a TransitionDrawable wrapping two
            // BitmapDrawables -- in that case unwrap the second layer
            // (Glide sets layer 0 to the placeholder, layer 1 to the
            // loaded bitmap).
            try {
                Object got = null;
                java.lang.reflect.Method m = findGetBitmap(d.getClass());
                if (m != null) got = m.invoke(d);
                if (!(got instanceof android.graphics.Bitmap)) {
                    // Unwrap any LayerDrawable (TransitionDrawable,
                    // RippleDrawable wrapping a BitmapDrawable, etc.)
                    // by walking each layer.
                    if (d instanceof android.graphics.drawable.LayerDrawable ld) {
                        int n = ld.getNumberOfLayers();
                        for (int i = n - 1; i >= 0 && !(got instanceof android.graphics.Bitmap); i--) {
                            android.graphics.drawable.Drawable layer = ld.getDrawable(i);
                            if (layer == null) continue;
                            java.lang.reflect.Method gm = findGetBitmap(layer.getClass());
                            if (gm != null) got = gm.invoke(layer);
                        }
                    }
                }
                if (got instanceof android.graphics.Bitmap bm) extracted = bm;
            } catch (Throwable ignored) {}
        }
        this.bitmap = extracted;
        if ("1".equals(ENV_MACAPK_TRACE_IMAGEVIEW_SET)) {
            try {
                com.macapk.loader.NativeLog.i("ImageView",
                    "setImageDrawable@" + Integer.toHexString(System.identityHashCode(this))
                    + " bounds=[" + getLeft() + "," + getTop() + "-" + getRight() + "," + getBottom() + "]"
                    + " drawable=" + (d != null ? d.getClass().getSimpleName() : "null")
                    + " bitmap=" + (extracted != null
                            ? (extracted.getWidth() + "x" + extracted.getHeight()) : "null"));
            } catch (Throwable ignored) {}
        }
        // v1.0.842 (lane STATELIST): AOSP's ImageView.updateDrawable (the
        // real installer setImageDrawable/setImageResource/setImageBitmap
        // all funnel through) syncs drawable state UNCONDITIONALLY when
        // installing a new drawable --
        //   frameworks/base/core/java/android/widget/ImageView.java
        //   (android13-release, updateDrawable(Drawable)):
        //     mDrawable = d;
        //     if (d != null) {
        //         ...
        //         if (d.isStateful()) {
        //             d.setState(getDrawableState());
        //         }
        //         ...
        //         applyImageTint();
        //         ...
        //     }
        // -- applyImageTint() is a SEPARATE, additional re-sync AOSP does
        // afterward (its own comment: "the drawable... may not have been
        // stateful before applying the tint, so let's try again"), not the
        // only place state ever reaches the drawable. Our applyImageTint()
        // below early-returns when no tint was ever configured
        // (`!(mHasImageTint || mHasImageTintMode)`), so a plain
        // android:src="@drawable/some_selector" ImageView -- no tint, very
        // common for a StateListDrawable-backed icon that dims when
        // disabled/unselected -- never got a first setState() at all,
        // exactly the same bug (and same fix shape) as
        // View.setBackgroundDrawable. See that method's comment and
        // docs/statelist-initial-state_2026-07-31.md for the full
        // root-cause writeup (confirmed victim: Mastodon's "Join
        // mastodon.social" button background, a different install site).
        if (d != null && d.isStateful()) {
            try { d.setState(getDrawableState()); } catch (Throwable ignored) {}
        }
        // AOSP ImageView.updateDrawable applies any previously-configured tint
        // to the NEW drawable too (a tint set once via setImageTintList/
        // app:tint survives across setImageResource/setImageDrawable calls,
        // e.g. Material's icon-swap-but-keep-theme-color pattern).
        applyImageTint();

        // v1.0.110: invalidate so the next frame paints the new image.
        // v1.0.398: AOSP setImageDrawable also requestLayout()s -- the view's
        // measured size can depend on the drawable's intrinsic size (wrap_content
        // or adjustViewBounds). Without it, an async Glide-loaded screenshot whose
        // ImageView measured to width 0 (no drawable at first measure) never
        // re-measures, so the screenshot card stays 0-wide. requestLayout sets
        // ViewRootImpl.mLayoutRequested -> the next frame does a full measure pass.
        requestLayout();
        invalidate();
    }

    /** AOSP ImageView.applyImageTint (~line 733): mutates the current drawable
     *  before tinting so a shared/cached Drawable instance's tint doesn't bleed
     *  into other views using the same instance, matches CompoundButton.
     *  applyButtonTint / ProgressBar's drawable pipeline in this tree. */
    private void applyImageTint() {
        if (drawable == null || !(mHasImageTint || mHasImageTintMode)) return;
        drawable = drawable.mutate();
        if (mHasImageTint) drawable.setTintList(mImageTintList);
        if (mHasImageTintMode) drawable.setTintMode(mImageTintMode);
        // Diagnostic (MACAPK_TRACE_TINT, zero cost unset): the effective image
        // tint per view, so a wrong icon colour names its resolved ARGB instead
        // of being inferred from a screenshot.
        if (ENV_MACAPK_TRACE_TINT != null) {
            try {
                int eff = (mImageTintList == null) ? 0
                    : mImageTintList.getColorForState(getDrawableState(),
                                                      mImageTintList.getDefaultColor());
                com.macapk.loader.NativeLog.i("Mac.apk.ImageView",
                    "imageTint " + drawable.getClass().getSimpleName()
                    + " -> #" + Integer.toHexString(eff)
                    + " default=#" + (mImageTintList == null ? "null"
                        : Integer.toHexString(mImageTintList.getDefaultColor())));
            } catch (Throwable ignored) {}
        }
        // The drawable (or one of its children) may not have been stateful
        // before applying the tint, so try again per AOSP's own comment.
        if (drawable.isStateful()) drawable.setState(getDrawableState());
    }

    /** v1.0.364: true when a layout_width/height of 0 on THIS view means
     *  MATCH_CONSTRAINT (a ConstraintLayout/MotionLayout child) rather
     *  than 0 pixels. Used to keep the setImageDrawable LP-rescue from
     *  clobbering a 0dp ConstraintLayout child. Detected two ways for
     *  robustness against R8 minification: (1) the LayoutParams class is
     *  a ConstraintLayout.LayoutParams (its name carries the
     *  "constraintlayout" package, which is kept because the class is
     *  referenced by name from inflated XML), or (2) the parent view's
     *  class chain includes a class named "...ConstraintLayout"
     *  (ConstraintLayout's own name is likewise kept). Generic -- no
     *  package/resource-id checks. */
    private boolean zeroWidthMeansMatchConstraint() {
        try {
            android.view.ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null
                && lp.getClass().getName().toLowerCase().contains("constraintlayout")) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            Object p = getParent();
            for (Class<?> k = (p != null ? p.getClass() : null);
                    k != null; k = k.getSuperclass()) {
                if (k.getName().endsWith(".ConstraintLayout")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Walk d.getClass() up its superclass chain looking for a public
     *  getBitmap() method. BitmapDrawable declares getBitmap() but
     *  RoundedBitmapDrawable extends Drawable directly and adds its
     *  own; both should be unwrappable. */
    private static java.lang.reflect.Method findGetBitmap(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getMethod("getBitmap"); }
            catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
    public android.graphics.drawable.Drawable getDrawable() { return drawable; }
    public void setImageResource(int resId)            {
        // Resources may have indexed the resource as a Bitmap via
        // openRawResource + BitmapFactory; loader-level integration
        // is wired through the Resources object on the View's
        // Context. Falling back to null keeps the view empty
        // rather than crashing.
        try {
            if (getContext() != null && getContext().getResources() != null) {
                android.graphics.drawable.Drawable d =
                    getContext().getResources().getDrawable(resId);
                if (d != null) setImageDrawable(d);
            }
        } catch (Throwable ignored) {}
    }
    public void setScaleType(ScaleType st) { this.scaleType = st != null ? st : ScaleType.FIT_CENTER; }
    public ScaleType getScaleType()        { return scaleType; }
    // v1.0.98: FAB chain reads + writes imageMatrix during init.
    private android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
    public android.graphics.Matrix getImageMatrix() { return imageMatrix; }
    public void setImageMatrix(android.graphics.Matrix m) {
        if (m == null) this.imageMatrix.reset();
        else this.imageMatrix.set(m);
    }
    // (getDrawable already defined earlier)
    public void setImageTintList(android.content.res.ColorStateList list) {
        mImageTintList = list;
        mHasImageTint = true;
        applyImageTint();
        invalidate();
    }
    public android.content.res.ColorStateList getImageTintList() { return mImageTintList; }
    public void setImageTintMode(android.graphics.PorterDuff.Mode mode) {
        mImageTintMode = mode;
        mHasImageTintMode = true;
        applyImageTint();
        invalidate();
    }
    public android.graphics.PorterDuff.Mode getImageTintMode() { return mImageTintMode; }
    // AOSP ImageView.mAdjustViewBounds / mMaxWidth / mMaxHeight. adjustViewBounds
    // makes onMeasure preserve the drawable's aspect ratio (resize the unbounded
    // dimension from the bounded one); maxWidth/maxHeight cap the result. Used by
    // F-Droid's screenshot items to derive each screenshot's width from its aspect
    // at the fixed row height. setAdjustViewBounds also flips scaleType to
    // FIT_CENTER if currently FIT_XY -- mirroring AOSP, which forces FIT_CENTER
    // when adjustViewBounds is on (the screenshot item XML declares fitXY but
    // adjustViewBounds wins, giving aspect-correct, non-stretched thumbnails).
    private boolean adjustViewBounds = false;
    private int maxWidth  = Integer.MAX_VALUE;
    private int maxHeight = Integer.MAX_VALUE;
    public void setAdjustViewBounds(boolean b) {
        this.adjustViewBounds = b;
        if (b && scaleType == ScaleType.FIT_XY) scaleType = ScaleType.FIT_CENTER;
        requestLayout();
    }
    public boolean getAdjustViewBounds() { return adjustViewBounds; }
    public int getMaxWidth()  { return maxWidth; }
    public int getMaxHeight() { return maxHeight; }
    public void setMaxWidth(int w)  { this.maxWidth = w; requestLayout(); }
    public void setMaxHeight(int h) { this.maxHeight = h; requestLayout(); }
    public boolean getCropToPadding() { return false; }
    public void setCropToPadding(boolean v) {}
    // AOSP frameworks/base core/java/android/widget/ImageView.java (~lines
    // 1493-1508): getBaselineAlignBottom() returned `int` (hardcoded 0) and
    // setBaselineAlignBottom(boolean) was a no-op with no backing field at
    // all -- the classic half-implemented-accessor shape (see ProgressBar's
    // drawable accessors, same pattern, same day): a getter whose only
    // observable value could never reflect what the setter was asked to
    // store. The return-type mismatch alone (I vs Z) is a JVM descriptor
    // mismatch, so real AOSP-shaped guest bytecode calling
    // getBaselineAlignBottom()Z never bound to this method at all. Found by
    // debug/framework_lint.py's class-shape diff against android.jar. Now
    // wired to mBaselineAlignBottom (declared above with the other baseline
    // state), which getBaseline() already consults, and requestLayout() on
    // change matches AOSP (baseline feeds the parent's layout pass).
    private boolean mBaselineAlignBottom;
    public boolean getBaselineAlignBottom() { return mBaselineAlignBottom; }
    public void setBaselineAlignBottom(boolean aligned) {
        if (mBaselineAlignBottom != aligned) {
            mBaselineAlignBottom = aligned;
            requestLayout();
        }
    }
    public int getImageAlpha() { return 255; }
    public void setImageAlpha(int alpha) {}
    public void setImageLevel(int level) {}
    public void setColorFilter(android.graphics.ColorFilter cf) { this.colorFilter = cf; }
    public void setColorFilter(int color) { /* tint not wired through Canvas yet */ }
    public void clearColorFilter() { this.colorFilter = null; }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (canvas == null) return;
        // v1.0.100 diagnostic: skip drawing of named ImageView subclasses.
        if ("1".equals(ENV_MACAPK_SKIP_ALL_IMAGE_DRAW)) return;
        String skip = ENV_MACAPK_SKIP_IMAGEVIEW_DRAW;
        if (skip != null && !skip.isEmpty()
            && getClass().getSimpleName().equals(skip)) {
            return;
        }
        android.graphics.Bitmap b = this.bitmap;
        if (b == null) {
            // v1.0.99: fall back to drawable.draw() for non-bitmap
            // drawables (VectorDrawable, RippleDrawable wrapping a
            // shape, etc.). The canvas is already translated to the
            // view's (left,top) by ViewGroup.dispatchDraw, so draw
            // in LOCAL coords (0,0,w,h).
            if (drawable != null) {
                // AOSP configureBounds scales the drawable into the PADDED
                // content box and translates by the leading padding; see the
                // fuller citation on the bitmap path below. Ours used the full
                // view rect on both paths.
                final int dPadL = getPaddingLeft(), dPadT = getPaddingTop();
                int w = (getRight() - getLeft()) - dPadL - getPaddingRight();
                int h = (getBottom() - getTop()) - dPadT - getPaddingBottom();
                if (w > 0 && h > 0) {
                    // v1.0.100 / v1.0.101: trace per ImageView draw.
                    // MACAPK_TRACE_IMAGEVIEW_DRAW=1        -> one-shot per instance
                    // MACAPK_TRACE_IMAGEVIEW_DRAW=verbose  -> EVERY draw, with
                    //   per-instance counter (catches double-traversal of the
                    //   same ImageView per frame, which the one-shot mode hides)
                    String tracelvl = ENV_MACAPK_TRACE_IMAGEVIEW_DRAW;
                    boolean verbose = "verbose".equals(tracelvl);
                    boolean fireOnce = "1".equals(tracelvl);
                    if (verbose || (fireOnce && !traced)) {
                        drawCount++;
                        if (!verbose) traced = true;
                        try {
                            String parentChain = "";
                            android.view.ViewParent p = getParent();
                            int chainDepth = 0;
                            while (p != null && chainDepth < 4) {
                                parentChain += "<" + p.getClass().getSimpleName();
                                if (p instanceof android.view.View pv) p = pv.getParent();
                                else break;
                                chainDepth++;
                            }
                            com.macapk.loader.NativeLog.i("ImageView",
                                "draw#" + drawCount + " " + getClass().getSimpleName()
                                + "@" + Integer.toHexString(System.identityHashCode(this))
                                + " bounds=[" + getLeft() + "," + getTop()
                                + "-" + getRight() + "," + getBottom() + "]"
                                + " canvas-translate=(" + canvas.macapkGetCurrentTx()
                                + "," + canvas.macapkGetCurrentTy() + ")"
                                + " drawable=" + drawable.getClass().getSimpleName()
                                + "@" + Integer.toHexString(System.identityHashCode(drawable))
                                + " parents=" + parentChain);
                        } catch (Throwable ignored) {}
                    }
                    // v1.0.100: when the view's bounds are wildly larger
                    // than the drawable's intrinsic size (FAB falling back
                    // to its CoordinatorLayout parent's full extent because
                    // forceLayoutZeroExtentChildren ran), the AOSP CENTER
                    // ScaleType would draw at intrinsic size centered in
                    // the view. We approximate that to keep vector FAB
                    // icons from filling the entire screen. Heuristic
                    // threshold: 6x the intrinsic in either dimension.
                    int drawW = w, drawH = h, dx = 0, dy = 0;
                    int iw = drawable.getIntrinsicWidth();
                    int ih = drawable.getIntrinsicHeight();
                    if (scaleType == ScaleType.MATRIX && iw > 0 && ih > 0) {
                        // AOSP configureBounds (android-14.0.0_r1 ImageView.java
                        // :1301-1310): MATRIX keeps the drawable at its NATIVE
                        // size and uses the caller's matrix as-is; onDraw
                        // (:1441-1446) translates by the padding, concats it,
                        // draws. Material's FloatingActionButton sets MATRIX in
                        // its constructor and sizes EVERY icon through this
                        // matrix (FloatingActionButtonImpl.setImageMatrixScale:
                        // setRectToRect(intrinsic -> maxImageSize box, CENTER)),
                        // so it must win over the class-name FAB heuristic below.
                        drawable.setBounds(0, 0, iw, ih);
                        int sv = canvas.save();
                        canvas.translate(dPadL, dPadT);
                        if (imageMatrix != null && !imageMatrix.isIdentity()) {
                            canvas.concat(imageMatrix);
                        }
                        drawable.draw(canvas);
                        canvas.restoreToCount(sv);
                        return;
                    }
                    // v1.0.395: Material FloatingActionButton draws its icon at
                    // a fixed 24dp (`design_fab_image_size`) CENTERED in the
                    // ~56dp body -- the real FAB applies an internal
                    // imagePadding = (body - 24dp)/2 rather than scaling the
                    // drawable to fill. We don't run that imagePadding, so a
                    // vector search icon was filling the whole FAB ("eyeglass
                    // larger than ref", 2026-06-14). Pin the FAB icon to 24dp
                    // centered. Scoped to the FAB class so list/app-icon
                    // ImageViews (FIT_CENTER) are unaffected.
                    boolean isFab = false;
                    try { isFab = getClass().getSimpleName().endsWith("FloatingActionButton"); }
                    catch (Throwable ignored) {}
                    if (isFab) {
                        float density = android.util.DisplayMetrics.macapkDeviceDensity();
                        try {
                            if (getResources() != null
                                && getResources().getDisplayMetrics() != null
                                && getResources().getDisplayMetrics().density > 0f)
                                density = getResources().getDisplayMetrics().density;
                        } catch (Throwable ignored) {}
                        int icon = Math.round(24f * density);
                        drawW = Math.min(w, icon);
                        drawH = Math.min(h, icon);
                        dx = (w - drawW) / 2;
                        dy = (h - drawH) / 2;
                    } else if (iw > 0 && ih > 0) {
                        // lane layout B, 2026-08-02: HONOUR scaleType on the
                        // DRAWABLE path. AOSP's ImageView.configureBounds
                        // (android13-release :1281) only stretches to the view
                        // for FIT_XY (or an unknown intrinsic size); every other
                        // scaleType sets the drawable to its NATIVE size and
                        // positions it with a matrix -- CENTER translates without
                        // scaling at all. Ours consulted scaleType ONLY in the
                        // Bitmap branch, so a drawable always filled the view.
                        // That is why the ImageButton `scaleType=center` default
                        // landed earlier tonight was INERT (set, never read), and
                        // why OpenCalculator's icon keys measured 4-7x oversize
                        // (backspace ink 46x37 on the emulator vs 183x138 here,
                        // the caret stretched non-aspect 24x16 -> 164x31).
                        // The old 6x-cap heuristic below is retired: it was an
                        // approximation of CENTER/FIT_CENTER, and it let the
                        // backspace key (2.8x) through untouched.
                        switch (scaleType) {
                            case FIT_XY:
                                break;                       // fill: leave w/h
                            case CENTER: {                   // native size, centered
                                drawW = iw; drawH = ih;
                                dx = (w - drawW) / 2; dy = (h - drawH) / 2;
                                break;
                            }
                            case CENTER_INSIDE: {            // shrink only, centered
                                float s = Math.min(1f, Math.min((float) w / iw, (float) h / ih));
                                drawW = Math.max(1, Math.round(iw * s));
                                drawH = Math.max(1, Math.round(ih * s));
                                dx = (w - drawW) / 2; dy = (h - drawH) / 2;
                                break;
                            }
                            case CENTER_CROP: {              // cover, centered (clipped)
                                float s = Math.max((float) w / iw, (float) h / ih);
                                drawW = Math.max(1, Math.round(iw * s));
                                drawH = Math.max(1, Math.round(ih * s));
                                dx = (w - drawW) / 2; dy = (h - drawH) / 2;
                                break;
                            }
                            case FIT_START: case FIT_END: case FIT_CENTER: default: {
                                float s = Math.min((float) w / iw, (float) h / ih);
                                drawW = Math.max(1, Math.round(iw * s));
                                drawH = Math.max(1, Math.round(ih * s));
                                if (scaleType == ScaleType.FIT_START) { dx = 0; dy = 0; }
                                else if (scaleType == ScaleType.FIT_END) { dx = w - drawW; dy = h - drawH; }
                                else { dx = (w - drawW) / 2; dy = (h - drawH) / 2; }
                                break;
                            }
                        }
                    }
                    drawable.setBounds(0, 0, drawW, drawH);
                    // Offset into the content box (AOSP's
                    // `canvas.translate(mPaddingLeft, mPaddingTop)`).
                    dx += dPadL; dy += dPadT;
                    if (dx != 0 || dy != 0) {
                        int sv = canvas.save();
                        canvas.translate(dx, dy);
                        drawable.draw(canvas);
                        canvas.restoreToCount(sv);
                    } else {
                        drawable.draw(canvas);
                    }
                }
            }
            return;
        }

        // v1.0.110: AOSP semantics -- canvas is already translated to
        // (left, top) by ViewGroup.dispatchDraw, so onDraw must paint
        // in LOCAL coords (0..viewW, 0..viewH), NOT parent coords
        // (l..r, t..bot). The previous code passed `l, t, r, bot` which
        // caused a double-translate (canvas saw the bitmap at
        // 2*l, 2*t) so every Glide-decoded app icon painted off-screen.
        // The drawable-fallback path (just above) already uses local
        // coords; this matches.
        // AOSP ImageView.configureBounds / onDraw
        // (frameworks/base/core/java/android/widget/ImageView.java):
        //     final int vwidth  = getWidth()  - mPaddingLeft - mPaddingRight;
        //     final int vheight = getHeight() - mPaddingTop  - mPaddingBottom;
        //     ...
        //     canvas.translate(mPaddingLeft, mPaddingTop);
        // The image is scaled into the PADDED CONTENT BOX, then drawn offset by
        // the leading padding. We honoured padding in onMeasure (see the
        // getPadding* reads there) but NOT here, so every padded ImageView drew
        // its image oversized and flush to the view edge. Measured on
        // tobsolitaire, whose game tiles set a 4dp pad: our art came out
        // 190x202 against the emulator's 176x186, an 8% overdraw that also ate
        // the 16px inter-tile gaps.
        final int padL = getPaddingLeft(), padT = getPaddingTop();
        int viewW = (getRight() - getLeft()) - padL - getPaddingRight();
        int viewH = (getBottom() - getTop()) - padT - getPaddingBottom();
        if (viewW <= 0 || viewH <= 0) {
            canvas.drawBitmap(b, padL, padT, null);
            return;
        }
        int imgW = b.getWidth();
        int imgH = b.getHeight();
        // Every case below paints in content-box coords (0..viewW/0..viewH), so
        // one translate covers them all rather than threading padL/padT through
        // eight separate dx/dy computations.
        final int macapkPadSave = canvas.save();
        if (padL != 0 || padT != 0) canvas.translate(padL, padT);
        try {
        if ("1".equals(ENV_MACAPK_TRACE_FEATUREIMG)
                && getClass().getSimpleName().contains("FeatureImage")) {
            try {
                com.macapk.loader.NativeLog.i("ImageView",
                    "FeatureImage.onDraw scaleType=" + scaleType
                    + " view=" + viewW + "x" + viewH
                    + " img=" + imgW + "x" + imgH
                    + " absTop=" + getTop() + " absBottom=" + getBottom()
                    + " canvasTy=" + canvas.macapkGetCurrentTy());
            } catch (Throwable ignored) {}
        }
        switch (scaleType) {
            case FIT_XY:
                canvas.drawBitmap(b,
                    new android.graphics.Rect(0, 0, imgW, imgH),
                    new android.graphics.Rect(0, 0, viewW, viewH),
                    null);
                break;
            case CENTER:
                canvas.drawBitmap(b,
                    (viewW - imgW) / 2f,
                    (viewH - imgH) / 2f,
                    null);
                break;
            case FIT_CENTER:
            case FIT_START:
            case FIT_END:
            case CENTER_INSIDE: {
                float scale = Math.min((float) viewW / imgW,
                                        (float) viewH / imgH);
                if (scale > 1f && scaleType == ScaleType.CENTER_INSIDE) scale = 1f;
                int outW = (int) (imgW * scale);
                int outH = (int) (imgH * scale);
                int dx = (scaleType == ScaleType.FIT_START) ? 0
                       : (scaleType == ScaleType.FIT_END)   ? viewW - outW
                       : (viewW - outW) / 2;
                int dy = (scaleType == ScaleType.FIT_START) ? 0
                       : (scaleType == ScaleType.FIT_END)   ? viewH - outH
                       : (viewH - outH) / 2;
                canvas.drawBitmap(b,
                    new android.graphics.Rect(0, 0, imgW, imgH),
                    new android.graphics.Rect(dx, dy, dx + outW, dy + outH),
                    null);
                break;
            }
            case CENTER_CROP: {
                float scale = Math.max((float) viewW / imgW,
                                        (float) viewH / imgH);
                int outW = (int) (imgW * scale);
                int outH = (int) (imgH * scale);
                int dx = (viewW - outW) / 2;
                int dy = (viewH - outH) / 2;
                canvas.drawBitmap(b,
                    new android.graphics.Rect(0, 0, imgW, imgH),
                    new android.graphics.Rect(dx, dy, dx + outW, dy + outH),
                    null);
                break;
            }
            case MATRIX: {
                // AOSP configureBounds / onDraw (android-14.0.0_r1
                // ImageView.java :1301-1310, :1441-1446): the drawable keeps its
                // NATIVE size -- the DRAWABLE's intrinsic size, which for a
                // resource bitmap is density-scaled, not the bitmap's pixel
                // count -- and the image matrix is applied as-is. We drew the raw
                // bitmap at the padding origin and ignored the matrix, so every
                // icon Material's FloatingActionButton fits to its maxImageSize
                // box through that matrix came out at raw size instead: Organic
                // Maps' 128 px crowdfunding gift (32 dp box) spilled out of its
                // 48 dp map button (owner, 2026-10-06, "the present icon is too big").
                int dw = imgW, dh = imgH;
                if (drawable != null && drawable.getIntrinsicWidth() > 0
                        && drawable.getIntrinsicHeight() > 0) {
                    dw = drawable.getIntrinsicWidth();
                    dh = drawable.getIntrinsicHeight();
                }
                final int msave = canvas.save();
                if (imageMatrix != null && !imageMatrix.isIdentity()) canvas.concat(imageMatrix);
                canvas.drawBitmap(b,
                    new android.graphics.Rect(0, 0, imgW, imgH),
                    new android.graphics.Rect(0, 0, dw, dh),
                    null);
                canvas.restoreToCount(msave);
                break;
            }
            default:
                canvas.drawBitmap(b, 0, 0, null);
                break;
        }
        } finally {
            canvas.restoreToCount(macapkPadSave);
        }
    }

    // -------- v1.0.116 AOSP audit batch --------

    /** AOSP ImageView.setImageURI(Uri): loads + sets the image from a
     *  content/file URI. Resolves to InputStream via ContentResolver. */
    public void setImageURI(android.net.Uri uri) {
        if (uri == null) { setImageDrawable(null); return; }
        java.io.InputStream is = null;
        try {
            if (getContext() != null && getContext().getContentResolver() != null) {
                is = getContext().getContentResolver().openInputStream(uri);
            }
            if (is == null) {
                // File URI fallback
                String path = uri.getPath();
                if (path != null) is = new java.io.FileInputStream(path);
            }
            if (is != null) {
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(is);
                if (bm != null) setImageBitmap(bm);
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
        }
    }

    /** AOSP ImageView.setImageIcon(Icon): set the image from an Icon
     *  helper. We delegate to setImageDrawable via Icon.loadDrawable. */
    public void setImageIcon(android.graphics.drawable.Icon icon) {
        if (icon == null) { setImageDrawable(null); return; }
        try {
            android.graphics.drawable.Drawable d = icon.loadDrawable(getContext());
            if (d != null) setImageDrawable(d);
        } catch (Throwable ignored) {}
    }

    /** AOSP ImageView.setImageState(int[], boolean): apply a state
     *  vector to the drawable. We forward to drawable.setState. */
    public void setImageState(int[] state, boolean merge) {
        if (drawable != null) {
            try { drawable.setState(state); } catch (Throwable ignored) {}
        }
    }

    /** AOSP ImageView.setBaseline(int) / getBaseline(): for compound
     *  layouts. Default: -1 (no specific baseline). A baseline change affects
     *  the parent's layout pass (e.g. a baseline-aligned LinearLayout reads a
     *  child's getBaseline() while laying out), so AOSP calls requestLayout()
     *  on change; this was previously silent. getBaseline() also now honors
     *  mBaselineAlignBottom (see getBaselineAlignBottom below), which
     *  overrides the explicit baseline per AOSP frameworks/base core/java/
     *  android/widget/ImageView.java (~line 1461): true means "baseline is the
     *  view's measured bottom edge", ignoring whatever setBaseline() stored. */
    private int baseline = -1;
    public void setBaseline(int baseline) {
        if (this.baseline != baseline) {
            this.baseline = baseline;
            requestLayout();
        }
    }
    @Override public int getBaseline() {
        return mBaselineAlignBottom ? getMeasuredHeight() : baseline;
    }

    /** AOSP ImageView.setColorFilter(int, PorterDuff.Mode). */
    public void setColorFilter(int color, android.graphics.PorterDuff.Mode mode) {
        setColorFilter(new android.graphics.PorterDuffColorFilter(color, mode));
    }
    public android.graphics.ColorFilter getColorFilter() { return colorFilter; }

    /** AOSP ImageView.animateTransform(Matrix): apply an animation
     *  matrix override during a transition. No-op without rendering. */
    public void animateTransform(android.graphics.Matrix matrix) {}

    /** AOSP ImageView.drawableStateChanged: propagate state changes
     *  to the image drawable. */
    @Override public void drawableStateChanged() {
        super.drawableStateChanged();
        if (drawable != null && drawable.isStateful()) {
            try { drawable.setState(getDrawableState()); } catch (Throwable ignored) {}
        }
    }

    /** MACAPK_IMAGEVIEW_ANIM_HOOKS, default ON (2026-09-29). AOSP's ImageView
     *  has NO onAnimationStart/onAnimationEnd overrides (android14
     *  ImageView.java), so View's run: they set/clear PFLAG_ANIMATION_STARTED
     *  (AOSP View.java:27496-27511), which ViewGroup.finishAnimatingView reads
     *  before calling onAnimationEnd. Ours used to be no-ops that hid View's, so
     *  a tween animation on an ImageView never counted as started and the app's
     *  own onAnimationEnd never ran -- Simple Solitaire moves each dealt card in
     *  its ImageView subclass's onAnimationEnd, so the board never dealt. With
     *  the gate on these delegate to View, i.e. behave as if absent (the public
     *  signature is kept so an app subclass links the same either way).
     *  =0 restores the old no-op overrides exactly. */
    private static final boolean ANIM_HOOKS =
        !"0".equals(System.getenv("MACAPK_IMAGEVIEW_ANIM_HOOKS"));
    public void onAnimationStart() { if (ANIM_HOOKS) super.onAnimationStart(); }
    public void onAnimationEnd() { if (ANIM_HOOKS) super.onAnimationEnd(); }

    @Override public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (drawable != null) {
            try { drawable.setVisible(visibility == VISIBLE, false); } catch (Throwable ignored) {}
        }
    }

    // v1.0.131 second take: restore AOSP-correct ImageView.onMeasure.
    // v1.0.130's first attempt regressed list-item card layouts -- but
    // the actual cause of that regression was ConstraintLayout's
    // horizontal anchors reading from mResolved* shadow fields that
    // weren't populated (not the intrinsic-size substitution itself).
    // v1.0.131's mResolved* writes + resolveLayoutDirection call fix
    // that path; with constraint-target resolution working again, we
    // can safely use intrinsic size for wrap_content + drawable.
    //
    // The Glide async case is safe: at first measure, drawable is null
    // because Glide hasn't loaded yet, and we fall through to
    // super.onMeasure (the spec-size behavior). When Glide later sets
    // the drawable, setImageDrawable calls requestLayout -> the next
    // measure pass picks up the intrinsic size.
    //
    // Load-bearing for: bottom-nav icons (MenuItem.setIcon creates
    // the icon ImageView programmatically with wrap_content LP and a
    // synchronously-resolved VectorDrawable), FAB icons, Toolbar
    // icons, and every other Material widget that puts an ImageView
    // under wrap_content + sync drawable.
    //
    // AOSP reference:
    //   frameworks/base/core/java/android/widget/ImageView.java::onMeasure
    /** AOSP ImageView.resolveAdjustedSize: clamp desiredSize by the measure spec
     *  and maxSize. UNSPECIFIED -> min(desired,max); AT_MOST -> min(desired,spec,max);
     *  EXACTLY -> spec. */
    private int resolveAdjustedSize(int desiredSize, int maxSize, int measureSpec) {
        final int specMode = android.view.View.MeasureSpec.getMode(measureSpec);
        final int specSize = android.view.View.MeasureSpec.getSize(measureSpec);
        switch (specMode) {
            case android.view.View.MeasureSpec.AT_MOST:
                return Math.min(Math.min(desiredSize, specSize), maxSize);
            case android.view.View.MeasureSpec.EXACTLY:
                return specSize;
            default: /* UNSPECIFIED */
                return Math.min(desiredSize, maxSize);
        }
    }

    /** Labelled bisect gate (2026-09-25): the AOSP padding-inclusive wrap_content
     *  measure below changes the size of every padded wrap_content ImageView in
     *  every app. MACAPK_IMAGEVIEW_MEASURE_PADDING=0 restores the legacy
     *  min(intrinsic, spec) measure so a regression can be A/B'd in one run. */
    private static final boolean MEASURE_PADDING =
            !"0".equals(System.getenv("MACAPK_IMAGEVIEW_MEASURE_PADDING"));
    private static final boolean TRACE_MEASURE =
            "1".equals(System.getenv("MACAPK_TRACE_IMAGEVIEW_MEASURE"));
    /** Labelled bisect gate (v1.0.1648): AOSP's head of onMeasure for a NULL
     *  drawable and for a drawable with NO intrinsic size. AOSP android14-release
     *  ImageView.onMeasure (L1129+): a null drawable measures as w = h = 0 (and
     *  never adjusts view bounds); otherwise w = mDrawableWidth, h =
     *  mDrawableHeight, each floored to 1 when <= 0 -- then padding, the
     *  suggested minimum and resolveSizeAndState. The legacy code instead
     *  returned View.onMeasure for both cases, and View.getDefaultSize answers
     *  the full SPEC size under AT_MOST (View.java getDefaultSize). MEASURED
     *  victim: Google Calculator's tablet layout (layout-sw800dp-w1200dp) has a
     *  wrap_content drag handle whose src is a size-less <shape> (intrinsic
     *  -1x-1, padding 24/44 px, min 96 px). AOSP measures it 96x96 and the
     *  emulator draws exactly that; we measured it 3360x1636, and its tinted
     *  #44464f fill covered the whole history pane (tablet A/B 73.5%). Note:
     *  docs/google-calculator-tablet-two-pane_2026-09-25.md.
     *  MACAPK_IMAGEVIEW_MEASURE_NOINTRINSIC=0 restores the two early returns. */
    private static final boolean MEASURE_NOINTRINSIC =
            !"0".equals(System.getenv("MACAPK_IMAGEVIEW_MEASURE_NOINTRINSIC"));

    /** Labelled bisect gate (wave 5, 2026-09-26): the adjustViewBounds branch of
     *  onMeasure, ported line for line from AOSP android14-release
     *  ImageView.onMeasure (:1129-1245). The legacy arm differed from AOSP in
     *  three places, and the first is the one with a measured victim:
     *   1. "Allow the height to outgrow its original estimate if width is
     *      fixed" (:1217-1221). AOSP assigns the resolved value to heightSize
     *      (and, symmetrically, widthSize at :1201-1204); ours assigned it back
     *      to newHeight, so the comparison `newHeight <= heightSize` was made
     *      against the INTRINSIC estimate and a width-EXACTLY view could never
     *      grow taller than its drawable. MEASURED victim:
     *      de.tobiasbielefeld.solitaire's game-selector tiles (TableRow cell,
     *      width EXACTLY 553, height unbounded, a 333x353 BitmapDrawable,
     *      padding 8): AOSP measures 553x585 and the tablet emulator draws
     *      537-px tiles at a 586-px row pitch; ours measured 553x369, so
     *      FIT_CENTER shrank each bitmap to 333 px inside its cell
     *      (docs/fleet-canvas-games-solitaires-feudal_2026-09-26.md, F2).
     *   2. The outgrow is skipped under sCompatAdjustViewBounds
     *      (targetSdkVersion <= JELLY_BEAN_MR1, :259), which ours never read.
     *   3. AOSP never applies the suggested minimum inside the resize path; only
     *      the "measure in the normal way" else-branch (:1229-1241) does, and
     *      through resolveSizeAndState, which drops it under EXACTLY. Ours
     *      clamped to the minimum on every adjustViewBounds measure, including
     *      the both-EXACTLY case where AOSP answers the specs verbatim.
     *  MACAPK_IMAGEVIEW_ADJUST_AOSP=0 restores the legacy arm exactly.
     *  Offline gate: debug/w5widgets_gate.sh (rows iv-*). */
    private static final boolean ADJUST_AOSP =
            !"0".equals(System.getenv("MACAPK_IMAGEVIEW_ADJUST_AOSP"));

    /** AOSP ImageView.sCompatAdjustViewBounds (:162), set once in initImageView
     *  (:257-259) from the app's targetSdkVersion:
     *      sCompatAdjustViewBounds = targetSdkVersion <= Build.VERSION_CODES.JELLY_BEAN_MR1;
     *  Latched lazily from the first adjustViewBounds measure rather than the
     *  constructor, and only on a RESOLVABLE targetSdkVersion, for the same
     *  reason View.macapkInitViewCompat gives: our first views can be built on
     *  a bare Context, and latching 0 would arm the legacy branch for a modern
     *  app for the whole process. One app per process makes the lazy read
     *  answer what AOSP's constructor read answers. */
    private static boolean sCompatAdjustViewBounds;
    private static boolean sCompatAdjustDone;

    private void macapkLatchAdjustCompat() {
        if (sCompatAdjustDone) return;
        int targetSdkVersion = 0;
        try {
            final Context c = getContext();
            final android.content.pm.ApplicationInfo ai = c != null ? c.getApplicationInfo() : null;
            if (ai != null) targetSdkVersion = ai.targetSdkVersion;
        } catch (Throwable ignored) { }
        if (!android.app.Application.macapkTargetSdkKnown(targetSdkVersion)) return;   // never latch an unresolved read
        sCompatAdjustViewBounds =
                targetSdkVersion <= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
        sCompatAdjustDone = true;
    }

    /** AOSP android14-release ImageView.onMeasure (:1129-1245) for a view with
     *  mAdjustViewBounds and a non-null drawable. `w`/`h` are mDrawableWidth /
     *  mDrawableHeight already floored to 1 (:1154-1155). The arithmetic,
     *  including the float division that can yield Infinity or NaN for a zero
     *  inner height, is AOSP's verbatim; do not "fix" it. */
    private void macapkMeasureAdjustAosp(int widthMeasureSpec, int heightMeasureSpec, int w, int h) {
        macapkLatchAdjustCompat();
        final int widthSpecMode = android.view.View.MeasureSpec.getMode(widthMeasureSpec);
        final int heightSpecMode = android.view.View.MeasureSpec.getMode(heightMeasureSpec);
        // We are allowed to change the view's width / height.
        final boolean resizeWidth = widthSpecMode != android.view.View.MeasureSpec.EXACTLY;
        final boolean resizeHeight = heightSpecMode != android.view.View.MeasureSpec.EXACTLY;
        // Desired aspect ratio of the view's contents (not including padding).
        final float desiredAspect = (float) w / (float) h;
        final int pleft = getPaddingLeft();
        final int pright = getPaddingRight();
        final int ptop = getPaddingTop();
        final int pbottom = getPaddingBottom();
        int widthSize;
        int heightSize;
        if (resizeWidth || resizeHeight) {
            // Get the max possible width / height given our constraints.
            widthSize = resolveAdjustedSize(w + pleft + pright, maxWidth, widthMeasureSpec);
            heightSize = resolveAdjustedSize(h + ptop + pbottom, maxHeight, heightMeasureSpec);
            if (desiredAspect != 0.0f) {
                // See what our actual aspect ratio is.
                final float actualAspect = (float) (widthSize - pleft - pright)
                        / (heightSize - ptop - pbottom);
                if (Math.abs(actualAspect - desiredAspect) > 0.0000001) {
                    boolean done = false;
                    // Try adjusting width to be proportional to height.
                    if (resizeWidth) {
                        int newWidth = (int) (desiredAspect * (heightSize - ptop - pbottom))
                                + pleft + pright;
                        // Allow the width to outgrow its original estimate if height is fixed.
                        if (!resizeHeight && !sCompatAdjustViewBounds) {
                            widthSize = resolveAdjustedSize(newWidth, maxWidth, widthMeasureSpec);
                        }
                        if (newWidth <= widthSize) {
                            widthSize = newWidth;
                            done = true;
                        }
                    }
                    // Try adjusting height to be proportional to width.
                    if (!done && resizeHeight) {
                        int newHeight = (int) ((widthSize - pleft - pright) / desiredAspect)
                                + ptop + pbottom;
                        // Allow the height to outgrow its original estimate if width is fixed.
                        if (!resizeWidth && !sCompatAdjustViewBounds) {
                            heightSize = resolveAdjustedSize(newHeight, maxHeight, heightMeasureSpec);
                        }
                        if (newHeight <= heightSize) {
                            heightSize = newHeight;
                        }
                    }
                }
            }
        } else {
            // Not allowed to change either dimension: measure in the normal way.
            int ww = w + pleft + pright;
            int hh = h + ptop + pbottom;
            ww = Math.max(ww, getSuggestedMinimumWidth());
            hh = Math.max(hh, getSuggestedMinimumHeight());
            widthSize = resolveSizeAndState(ww, widthMeasureSpec, 0);
            heightSize = resolveSizeAndState(hh, heightMeasureSpec, 0);
        }
        setMeasuredDimension(widthSize, heightSize);
        traceMeasure("adjustViewBounds-aosp", widthMeasureSpec, heightMeasureSpec, w, h,
                widthSize, heightSize);
    }

    private void traceMeasure(String path, int wSpec, int hSpec, int iw, int ih, int mw, int mh) {
        if (!TRACE_MEASURE) return;
        try {
            com.macapk.loader.NativeLog.i("ImageView",
                "onMeasure@" + Integer.toHexString(System.identityHashCode(this))
                + " id=0x" + Integer.toHexString(getId())
                + " path=" + path
                + " wSpec=" + android.view.View.MeasureSpec.toString(wSpec)
                + " hSpec=" + android.view.View.MeasureSpec.toString(hSpec)
                + " intrinsic=" + iw + "x" + ih
                + " pad=" + getPaddingLeft() + "," + getPaddingTop() + "," + getPaddingRight() + "," + getPaddingBottom()
                + " min=" + getSuggestedMinimumWidth() + "x" + getSuggestedMinimumHeight()
                + " -> " + (mw & MEASURED_SIZE_MASK) + "x" + (mh & MEASURED_SIZE_MASK));
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        android.graphics.drawable.Drawable d = drawable;
        int iw, ih;
        if (!MEASURE_NOINTRINSIC) {
            if (d == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                traceMeasure("legacy-null", widthMeasureSpec, heightMeasureSpec, -1, -1,
                        getMeasuredWidthAndState(), getMeasuredHeightAndState());
                return;
            }
            iw = d.getIntrinsicWidth();
            ih = d.getIntrinsicHeight();
            if (iw <= 0 && ih <= 0) {
                // No intrinsic size (ColorDrawable, badly-built Vector-
                // Drawable, etc.) -- defer to spec-size base behavior.
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                traceMeasure("legacy-nointrinsic", widthMeasureSpec, heightMeasureSpec, iw, ih,
                        getMeasuredWidthAndState(), getMeasuredHeightAndState());
                return;
            }
        } else if (d == null) {
            iw = 0;                 // AOSP: "If no drawable, its intrinsic size is 0."
            ih = 0;
        } else {
            iw = d.getIntrinsicWidth();
            ih = d.getIntrinsicHeight();
        }
        // The content size AOSP measures with: 0 for a null drawable, else the
        // intrinsic size floored to 1 (AOSP: if (w <= 0) w = 1; if (h <= 0) h = 1).
        final int cw = d == null ? 0 : (iw > 0 ? iw : 1);
        final int ch = d == null ? 0 : (ih > 0 ? ih : 1);
        // adjustViewBounds: preserve the drawable's aspect ratio. With one axis
        // bounded (EXACTLY) and the other free (wrap_content -> AT_MOST/UNSPECIFIED),
        // derive the free axis from the bounded one so the image isn't squished.
        // This is the AOSP ImageView.onMeasure adjustViewBounds path (verbatim
        // shape). F-Droid's screenshots are height=EXACTLY(row) + width=wrap +
        // adjustViewBounds, so each gets width = rowHeight * (iw/ih).
        if (adjustViewBounds && d != null && (MEASURE_NOINTRINSIC || (iw > 0 && ih > 0))) {
            if (ADJUST_AOSP) {
                macapkMeasureAdjustAosp(widthMeasureSpec, heightMeasureSpec, cw, ch);
                return;
            }
            // Legacy adjustViewBounds arm (MACAPK_IMAGEVIEW_ADJUST_AOSP=0), kept
            // verbatim so the bisect gate restores the exact pre-change measure.
            // Under NOINTRINSIC this runs with the floored cw/ch, as AOSP does.
            iw = cw;
            ih = ch;
            int pl = getPaddingLeft(), pr = getPaddingRight();
            int pt = getPaddingTop(), pb = getPaddingBottom();
            int wm = android.view.View.MeasureSpec.getMode(widthMeasureSpec);
            int hm = android.view.View.MeasureSpec.getMode(heightMeasureSpec);
            boolean resizeWidth  = wm != android.view.View.MeasureSpec.EXACTLY;
            boolean resizeHeight = hm != android.view.View.MeasureSpec.EXACTLY;
            float desiredAspect = (float) iw / (float) ih;
            int widthSize  = resolveAdjustedSize(iw + pl + pr, maxWidth, widthMeasureSpec);
            int heightSize = resolveAdjustedSize(ih + pt + pb, maxHeight, heightMeasureSpec);
            if (resizeWidth || resizeHeight) {
                int innerW = widthSize - pl - pr;
                int innerH = heightSize - pt - pb;
                float actualAspect = innerH > 0 ? (float) innerW / (float) innerH : desiredAspect;
                if (Math.abs(actualAspect - desiredAspect) > 0.0000001f) {
                    boolean done = false;
                    if (resizeWidth) {
                        int newWidth = (int) (desiredAspect * (heightSize - pt - pb)) + pl + pr;
                        if (!resizeHeight) newWidth = resolveAdjustedSize(newWidth, maxWidth, widthMeasureSpec);
                        if (newWidth <= widthSize) { widthSize = newWidth; done = true; }
                    }
                    if (!done && resizeHeight) {
                        int newHeight = (int) ((widthSize - pl - pr) / desiredAspect) + pt + pb;
                        if (!resizeWidth) newHeight = resolveAdjustedSize(newHeight, maxHeight, heightMeasureSpec);
                        if (newHeight <= heightSize) { heightSize = newHeight; }
                    }
                }
            }
            if (widthSize  < getSuggestedMinimumWidth())  widthSize  = getSuggestedMinimumWidth();
            if (heightSize < getSuggestedMinimumHeight()) heightSize = getSuggestedMinimumHeight();
            setMeasuredDimension(widthSize, heightSize);
            traceMeasure("adjustViewBounds", widthMeasureSpec, heightMeasureSpec, iw, ih, widthSize, heightSize);
            return;
        }
        int wMode = android.view.View.MeasureSpec.getMode(widthMeasureSpec);
        int wSize = android.view.View.MeasureSpec.getSize(widthMeasureSpec);
        int hMode = android.view.View.MeasureSpec.getMode(heightMeasureSpec);
        int hSize = android.view.View.MeasureSpec.getSize(heightMeasureSpec);
        if (MEASURE_PADDING) {
            // AOSP android14-release ImageView.onMeasure, the non-adjustViewBounds
            // "measure in the normal way" branch:
            //     w += pleft + pright;  h += ptop + pbottom;
            //     w = Math.max(w, getSuggestedMinimumWidth()); (same for h)
            //     widthSize = resolveSizeAndState(w, widthMeasureSpec, 0);
            // with w/h = mDrawableWidth/Height, each floored to 1 when <= 0.
            // The legacy switch below used min(intrinsic, spec) and never added
            // padding, so a wrap_content ImageView with padding squeezed its
            // drawable into (intrinsic - padding): org.fossify.gallery's search
            // magnifier (48dp asset, paddingStart 32px) drew 16px wide, and
            // de.tobiasbielefeld.solitaire's drawer-header icon (96px asset,
            // paddingTop 32px) measured 96x96 and so drew into a 96x64 content
            // box (64px) where the emulator's view is 128px tall.
            // Offline gate: debug/imageview_measure_gate.sh.
            // The v1.0.131 "EXACTLY 0 -> intrinsic" fallback is kept (it is a
            // workaround for an unresolved itemIconSize, not AOSP behaviour),
            // so this changes only the AT_MOST/UNSPECIFIED arms plus the
            // TOO_SMALL state bit AOSP's resolveSizeAndState reports.
            int dw = cw + getPaddingLeft() + getPaddingRight();
            int dh = ch + getPaddingTop() + getPaddingBottom();
            dw = Math.max(dw, getSuggestedMinimumWidth());
            dh = Math.max(dh, getSuggestedMinimumHeight());
            int mw = (wMode == android.view.View.MeasureSpec.EXACTLY && wSize == 0 && iw > 0)
                    ? iw : resolveSizeAndState(dw, widthMeasureSpec, 0);
            int mh = (hMode == android.view.View.MeasureSpec.EXACTLY && hSize == 0 && ih > 0)
                    ? ih : resolveSizeAndState(dh, heightMeasureSpec, 0);
            traceMeasure("normal", widthMeasureSpec, heightMeasureSpec, iw, ih, mw, mh);
            setMeasuredDimension(mw, mh);
            return;
        }
        // Legacy min(intrinsic, spec) arm (MACAPK_IMAGEVIEW_MEASURE_PADDING=0).
        // Under NOINTRINSIC a size-less or null drawable reaches it too, so it
        // must use the floored content size or AT_MOST would give min(-1, spec).
        if (MEASURE_NOINTRINSIC) {
            iw = cw;
            ih = ch;
        }
        int outW;
        switch (wMode) {
            case android.view.View.MeasureSpec.EXACTLY:
                // v1.0.131: Material's NavigationBarItemView.setIconSize
                // sets the LP to EXACTLY(themeIconSize) where
                // themeIconSize == ?attr/itemIconSize. Our TypedArray
                // returns 0 for that dimension resource, so the icon
                // LP ends up at (0, 0). Falling through to wSize=0
                // gives a 0x0 icon -- invisible. When the spec is
                // EXACTLY 0 AND we have a drawable with non-zero
                // intrinsic, prefer the intrinsic size as a graceful
                // fallback. Real Android resolves itemIconSize via the
                // theme cascade; this branch covers our framework gap
                // generically until full dimension-resource resolution
                // lands.
                outW = (wSize == 0 && iw > 0) ? iw : wSize;
                break;
            case android.view.View.MeasureSpec.AT_MOST: outW = Math.min(iw, wSize); break;
            default: /* UNSPECIFIED */                   outW = iw; break;
        }
        int outH;
        switch (hMode) {
            case android.view.View.MeasureSpec.EXACTLY:
                outH = (hSize == 0 && ih > 0) ? ih : hSize;
                break;
            case android.view.View.MeasureSpec.AT_MOST: outH = Math.min(ih, hSize); break;
            default: /* UNSPECIFIED */                   outH = ih; break;
        }
        // 2026-08-13 (opencalculator function-row lane): minWidth/minHeight may
        // only WIDEN the measured size under AT_MOST/UNSPECIFIED, never under
        // EXACTLY. AOSP's real ImageView.onMeasure (aosp-mirror/platform_
        // frameworks_base android13-release, core/java/android/widget/
        // ImageView.java, the non-adjustViewBounds "measure in the normal way"
        // branch) computes `w = Math.max(w, getSuggestedMinimumWidth())` and
        // then ALWAYS passes it through `resolveSizeAndState(w, widthMeasureSpec,
        // 0)` (View.java) -- whose `case EXACTLY: result = specSize;` arm
        // returns the PARENT-GIVEN size verbatim, discarding `w` (and therefore
        // minWidth) entirely. This code applied the clamp unconditionally
        // instead, so an EXACTLY-measured ImageButton/ImageView (the weighted
        // second pass of a `layout_width="0dp" layout_weight="1"` LinearLayout
        // row, which gives each child an EXACT share regardless of its own
        // minWidth -- verified by reading LinearLayout.measureHorizontal's
        // `childWidth = share` branch) got silently WIDENED past its allotted
        // share whenever minWidth exceeded it.
        //
        // MEASURED victim: opencalculator's function row (5 buttons, weights
        // 1/1/1/1/0.6). Its `style="@style/CalculatorButton.Function"` chain
        // (-> CalculatorButton -> Widget.Material3.Button -> ... ->
        // Base.Widget.AppCompat.Button -> @android:style/Widget.Material.Button,
        // minWidth=88dp/minHeight=48dp) went unresolved before v1.0.974's
        // `?attr`/`@style/` style-ref fix (MACAPK_ATTRSTYLE); once resolved,
        // View's 4-arg constructor's own oSA4 read (runtime/java/android/view/
        // View.java) calls setMinimumWidth(176) [88dp @ d=2] on exponentButton
        // (an AppCompatImageButton) at construction. TRACE_LAYOUT bounds,
        // MACAPK_ATTRSTYLE=0 vs =1, same binary:
        //   square  l=0   r=168            l=0   r=168            unchanged
        //   pi      l=168 r=336            l=168 r=336            unchanged
        //   EXPONENT l=336 r=505 w=169  -> l=336 r=512 w=176   <- CLAMPED to minW
        //   factorial l=505 r=674 w=169 -> l=512 r=681 w=169     <- pushed +7px
        //   chevron l=674 r=776 w=102   -> l=681 r=783 w=102     <- pushed +7px
        // exponentButton's own weight-share width (169px) is what real Android
        // measures (weight-based EXACTLY children ignore minWidth on a device
        // exactly as LinearLayout.measureHorizontal does here); the +7px clamp
        // to 176px is what pushed every later sibling right, matching the
        // measured glyph drift (^+3.3, !+8.0, chevron+6.4 -- docs/attr-style-
        // refs-and-edittext-underline_2026-08-13.md §4). Mode-gating this leaves
        // AT_MOST/UNSPECIFIED callers (a bare wrap_content ImageButton with no
        // parent weight) unchanged -- minWidth still floors those exactly as
        // before -- and does not touch the v1.0.131 "EXACTLY 0 -> intrinsic"
        // fallback above, which already ran first and produced the real
        // `outW`/`outH` this only conditionally raises.
        //
        // NOT applied to the adjustViewBounds branch above (lines ~870-901),
        // which carries the SAME unconditional clamp shape and the SAME AOSP
        // mismatch (real AOSP's resizeWidth/resizeHeight path never consults
        // getSuggestedMinimumWidth/Height at all) -- left alone because no
        // measured victim currently exercises it and this fix is scoped to the
        // one that does. A future pass can retire it the same way once it has
        // its own falsifier.
        if (wMode != android.view.View.MeasureSpec.EXACTLY && outW < getSuggestedMinimumWidth())  outW = getSuggestedMinimumWidth();
        if (hMode != android.view.View.MeasureSpec.EXACTLY && outH < getSuggestedMinimumHeight()) outH = getSuggestedMinimumHeight();
        if ("1".equals(ENV_MACAPK_TRACE_IMAGEVIEW_MEASURE)) {
            try {
                com.macapk.loader.NativeLog.i("ImageView",
                    "onMeasure@" + Integer.toHexString(System.identityHashCode(this))
                    + " wSpec=" + wMode + "/" + wSize
                    + " hSpec=" + hMode + "/" + hSize
                    + " intrinsic=" + iw + "x" + ih
                    + " -> " + outW + "x" + outH);
            } catch (Throwable ignored) {}
        }
        setMeasuredDimension(outW, outH);
    }

    /** AOSP ImageView.matrix-management. */
    public android.graphics.Matrix getImageMatrixOrIdentity() {
        return imageMatrix != null ? imageMatrix : new android.graphics.Matrix();
    }

    /** AOSP ImageView.hasOverlappingRendering: true when ScaleType
     *  CENTER_CROP / FIT_XY overlaps (default-true is conservative). */
    @Override public boolean hasOverlappingRendering() { return true; }

    /** AOSP ImageView.setFrame(int,int,int,int): assign the bounds via super,
     *  then re-fit the drawable to the new frame.
     *
     *  ⚠ THIS MUST CALL super. It previously read `{ return false; }` -- a stub
     *  written when android.view.View had no setFrame, so it was unreachable.
     *  The moment View.layout() started routing bounds through setFrame(), this
     *  stub silently swallowed the assignment and EVERY ImageView/ImageButton
     *  laid out at [0,0-0,0] while still measuring correctly. Measured on
     *  Vector Pinball: canvas colours 484 -> 272. A no-op override of a method
     *  that assigns state is not a harmless stub. */
    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        // AOSP also calls configureBounds() here to re-fit the drawable to the
        // new frame. This tree has no such method -- ImageView computes the
        // drawable's destination rect inside onDraw instead (see the
        // "AOSP ImageView.configureBounds / onDraw" note above), which reads the
        // bounds super just assigned, so the fit is already correct on the next
        // draw and there is nothing extra to do here.
        return super.setFrame(l, t, r, b);
    }
    /** MACAPK_IMAGEVIEW_DEFSTYLERES (default ON). This used to be
     *  `this(c, a, defStyleAttr)`, dropping defStyleRes on the floor, so a view
     *  built the AOSP way from an explicit style -- android.widget.Toolbar's
     *  navigation button is `new ImageButton(ctx, null, 0, navButtonStyle)`
     *  (Toolbar.java:1496) -- got none of that style's background / minWidth /
     *  padding / scaleType / src. View's 4-arg constructor already reads the
     *  cascade with defStyleRes; this only stops discarding it. =0 restores the
     *  3-arg answer exactly (View's 3-arg constructor IS the 4-arg one with 0). */
    public ImageView(android.content.Context c, android.util.AttributeSet a, int defStyleAttr, int defStyleRes) {
        super(c, a, defStyleAttr, MACAPK_IMAGEVIEW_DEFSTYLERES ? defStyleRes : 0);
        macapkApplySrc(c, a, defStyleAttr, MACAPK_IMAGEVIEW_DEFSTYLERES ? defStyleRes : 0);
    }
    static final boolean MACAPK_IMAGEVIEW_DEFSTYLERES =
        !"0".equals(System.getenv("MACAPK_IMAGEVIEW_DEFSTYLERES"));

    protected boolean drawChild(android.graphics.Canvas canvas, android.view.View child, long drawingTime) { return false; }

    /** Deprecated 0-255 alpha form; AOSP delegates to setImageAlpha. */
    @Deprecated public void setAlpha(int alpha) { setImageAlpha(alpha); }

    // ---- API-36 surface completion ------------------------------------
    /** AOSP ImageView.setImageTintBlendMode / getImageTintBlendMode (API 29).
     *  AOSP stores the BlendMode and derives the PorterDuff.Mode; this port
     *  stores the PorterDuff.Mode (which applyImageTint pushes into the
     *  drawable), so the BlendMode is kept alongside as the exact round-trip
     *  value. Conversion is AOSP's own BlendMode.fromValue /
     *  blendModeToPorterDuffMode mapping, in MacApkBlendModes. */
    private android.graphics.BlendMode mImageBlendMode;
    public void setImageTintBlendMode(android.graphics.BlendMode blendMode) {
        mImageBlendMode = blendMode;
        setImageTintMode(MacApkBlendModes.toPorterDuff(blendMode));
    }
    public android.graphics.BlendMode getImageTintBlendMode() { return mImageBlendMode; }
}
