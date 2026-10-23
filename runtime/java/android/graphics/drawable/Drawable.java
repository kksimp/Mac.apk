package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;

public abstract class Drawable {
    private Rect bounds = new Rect();

    public abstract void draw(Canvas canvas);
    public abstract void setAlpha(int alpha);
    public abstract void setColorFilter(ColorFilter cf);
    public abstract int  getOpacity();

    /** AOSP Drawable.setBounds(int,int,int,int) (Drawable.java:244-260): store the
     *  rect and, ONLY when it actually moved, call onBoundsChange. The dispatch was
     *  missing here, and it is not a detail: every wrapper and container in this
     *  package positions its children from onBoundsChange, so without it an
     *  InsetDrawable never inset its child, a ScaleDrawable never scaled, and any
     *  app-defined Drawable that overrides onBoundsChange (the documented way to
     *  lay out sub-drawables) was simply never told. The workaround so far was for
     *  each subclass to override setBounds and forward to the child directly, which
     *  DEFEATED the subclass's own onBoundsChange -- measured by
     *  DRW.id.int4.childBounds, which reported the child at the wrapper's full
     *  bounds (0,0,100,60) instead of the inset 4,4,96,56.
     *
     *  Deliberate deviation: AOSP also calls invalidateSelf() for the OLD bounds
     *  before the store. We do not. Our invalidateSelf goes straight to
     *  Callback.invalidateDrawable -> View.invalidate, and setBounds runs inside
     *  layout for every background in the tree, so adding it would fire a redraw
     *  storm mid-layout for no answer the probe can see. Revisit only with a
     *  measurement in hand. */
    public void setBounds(int l, int t, int r, int b) {
        if (bounds.left != l || bounds.top != t || bounds.right != r || bounds.bottom != b) {
            bounds.set(l, t, r, b);
            onBoundsChange(bounds);
        }
    }
    /** AOSP delegates to the four-int form (Drawable.java:267-269). Writing the
     *  field directly here skipped the onBoundsChange dispatch above for every
     *  caller that passes a Rect. */
    public void setBounds(Rect r)                      { setBounds(r.left, r.top, r.right, r.bottom); }
    public Rect getBounds()                            { return bounds; }
    public Rect copyBounds()                           { return new Rect(bounds); }
    // AOSP overload: copy current bounds into the caller's Rect (avoids alloc).
    // Was missing; an app Drawable subclass (e.g. calc ArrowIndicator) calls it.
    public void copyBounds(Rect b)                     { b.set(bounds); }
    public int  getIntrinsicWidth()                    { return -1; }
    public int  getIntrinsicHeight()                   { return -1; }
    /** AOSP Drawable.getMinimumWidth/Height, verbatim:
     *      final int intrinsicWidth = getIntrinsicWidth();
     *      return intrinsicWidth > 0 ? intrinsicWidth : 0;
     *  These were stubbed at -1 (v1.0.870). That silently disabled
     *  `View.getSuggestedMinimumHeight()`, whose whole job is
     *      max(mMinHeight, mBackground.getMinimumHeight())
     *  -- with -1 the background could never raise the floor, so a view sized
     *  purely by its background's intrinsic size collapsed to its content
     *  height. Measured victim: bobball's menu buttons, which get their height
     *  on a real device entirely from the btn_default 9-patch (classic
     *  `Widget.Button` sets NO minHeight) and rendered ~16px against the
     *  emulator's ~48px. TextView.onMeasure already consults
     *  getSuggestedMinimumHeight (TextView.java:1109); only this floor was
     *  missing. */
    public int  getMinimumWidth()  { int w = getIntrinsicWidth();  return w > 0 ? w : 0; }
    public int  getMinimumHeight() { int h = getIntrinsicHeight(); return h > 0 ? h : 0; }
    /** AOSP Drawable.mStateSet / setState / getState, verbatim
     *  (frameworks/base graphics/java/android/graphics/drawable/Drawable.java):
     *      private int[] mStateSet = StateSet.WILD_CARD;
     *      public boolean setState(@NonNull final int[] stateSet) {
     *          if (!Arrays.equals(mStateSet, stateSet)) {
     *              mStateSet = stateSet;
     *              return onStateChange(stateSet);
     *          }
     *          return false;
     *      }
     *      public @NonNull int[] getState() { return mStateSet; }
     *
     *  This stored NOTHING and dispatched NOTHING until v1.0.892, so every
     *  drawable that does not hand-roll its own storage resolved its tint
     *  ColorStateList against a permanently EMPTY state array -- and
     *  `onStateChange` was declared here (see below) purely as dead code.
     *
     *  It was INVISIBLE while every androidx-inflated state spec was all-zeros:
     *  a leading 0 is the padding terminator, so row 0 matched unconditionally
     *  whatever state you passed. The moment real attribute ids made row 0
     *  CONDITIONAL, an empty state failed it and the selector collapsed to its
     *  wildcard. F-Droid's FAB is the worked case, and its selector is the
     *  OPPOSITE shape to the intuitive one:
     *      [state_enabled=true -> ?colorContainer  (the FILL, conditional),
     *       <wildcard>         -> ?colorOnSurface @0.12 (the DIM)]
     *  so the FAB went grey. Pixel math closed it to one channel: the dim row
     *  over the measured surround predicts #d8d9e2, observed #d8d8e1.
     *  `MaterialShapeDrawable.calculateTintFilter` does exactly
     *  `Drawable.getState()` -> `ColorStateList.getColorForState()` (verified in
     *  fdroid's bytecode at offsets 44/64) and declares no setState/getState of
     *  its own, so it depends entirely on this base.
     *
     *  This is the unfinished half of v1.0.842 ("A control paints its DISABLED
     *  /last selector row forever"): that turn fixed the CALL SITE while the base
     *  implementation stayed a no-op, so only the three subclasses that
     *  hand-rolled their own store -- StateListDrawable, LayerDrawable,
     *  DrawableWrapper -- ever worked. Those three override setState/getState and
     *  are therefore UNAFFECTED by adding storage here; the classes that gain it
     *  are exactly the ones that were broken (MaterialShapeDrawable and the app's
     *  own Drawable subclasses, GradientDrawable, ShapeDrawable, VectorDrawable,
     *  ColorDrawable). Their overrides can be collapsed into this later.
     *
     *  AOSP stores the caller's array BY REFERENCE and does not clone; we match,
     *  because View.getDrawableState() hands out its cached array and AOSP relies
     *  on the Arrays.equals short-circuit for the common no-change call. The one
     *  divergence is the null normalise -- Arrays.equals(x, null) is false, which
     *  would store null and NPE every subclass onStateChange. */
    private int[] mStateSet = android.util.StateSet.WILD_CARD;
    /** AOSP's BASE returns false and every subclass that owns a tint ORs its own
     *  check in -- e.g. BitmapDrawable / NinePatchDrawable / GradientDrawable all
     *  do `super.isStateful() || (mTint != null && mTint.isStateful())`. We hold
     *  the tint on the BASE (mTintList, below), so the OR belongs here: doing it
     *  once covers every leaf instead of requiring the identical override on each,
     *  and it is behaviourally what AOSP's subclass set already computes.
     *  This is load-bearing, not tidiness. View.drawableStateChanged and
     *  View.setBackgroundDrawable both gate their setState() call on
     *  isStateful(), so a leaf that answers false NEVER RECEIVES A STATE and
     *  resolves its tint against WILD_CARD forever. Measured: with the state
     *  machine above but this still returning false, F-Droid's FAB background
     *  (a MaterialShapeDrawable, which overrides isStateful itself) came back
     *  green while the FAB's ICON -- a VectorDrawable, which does not -- stayed
     *  unresolved and vanished (#1c2700 went 248 -> 0). */
    public boolean isStateful() {
        return mTintList != null && mTintList.isStateful();
    }
    /** AOSP Drawable.hasFocusStateSpecified() (Drawable.java:807, API 29) --
     *  "Indicates whether this drawable has at least one state spec explicitly
     *  specifying state_focused". The BASE returns false; StateListDrawable and
     *  DrawableContainer override it. View uses it to decide whether a focused
     *  view needs a default focus highlight drawn on top -- answering true when
     *  the drawable has no focus state would SUPPRESS that highlight, so false
     *  is both AOSP's answer and the safe one. */
    public boolean hasFocusStateSpecified() { return false; }
    public boolean setState(int[] state) {
        final int[] next = (state != null) ? state : android.util.StateSet.WILD_CARD;
        if (!java.util.Arrays.equals(mStateSet, next)) {
            mStateSet = next;
            return onStateChange(next);
        }
        return false;
    }
    public int[] getState()                            { return mStateSet; }
    /** AOSP: every drawable inflated from resources HAS a ConstantState, and
     *  callers dereference it without a null check -- material's
     *  `NavigationMenuPresenter.onBindViewHolder` does
     *  `itemBackground.getConstantState().newDrawable()` unconditionally.
     *  Returning null threw an NPE inside the tree-wide layout dispatch, so
     *  everything after the drawer kept stale bounds, and it also produced the
     *  downstream `Layout state should be one of 100 but it is 10` (step2 aborted
     *  before setting STEP_ANIMATIONS; the next dispatch asserts). Measured in
     *  the UnitConverter capture run; AntennaPod's log shows the same NPE.
     *  A base state that hands back THIS drawable keeps the AOSP contract
     *  ("a drawable you can use") without a per-subclass deep copy: our
     *  drawables are effectively immutable once inflated, and `mutate()` here is
     *  already identity. Subclasses that need real copy-on-write can override.
     *  See docs/canvas-ab-analyst-report-b_2026-08-02.md item 5. */
    public ConstantState getConstantState()            { return new MacapkSelfConstantState(this); }

    /** Minimal AOSP-shaped ConstantState over an existing drawable. */
    static final class MacapkSelfConstantState extends ConstantState {
        private final Drawable d;
        MacapkSelfConstantState(Drawable d) { this.d = d; }
        @Override public Drawable newDrawable() { return d; }
        @Override public int getChangingConfigurations() { return 0; }
    }
    public Drawable mutate()                           { return this; }

    // ---- v1.0.92: surface area Material widgets / RecyclerView ----
    // need at link time. Stubs because we don't actually draw most
    // Drawable variants -- methods just store/return values.
    private Callback callback;
    public void setCallback(Callback cb) { this.callback = cb; }
    public Callback getCallback() { return callback; }
    /** Notify our callback that we need redrawing. macOS has no
     *  invalidation pump for arbitrary Drawables; views compose their
     *  own redraw scheduling at the ViewCanvasDriver layer. */
    public void invalidateSelf() {
        Callback cb = this.callback;
        if (cb != null) cb.invalidateDrawable(this);
    }
    public void scheduleSelf(Runnable what, long when) {
        Callback cb = this.callback;
        if (cb != null) cb.scheduleDrawable(this, what, when);
    }
    public void unscheduleSelf(Runnable what) {
        Callback cb = this.callback;
        if (cb != null) cb.unscheduleDrawable(this, what);
    }

    private int alpha = 0xFF;
    public int getAlpha() { return alpha; }
    // setAlpha is abstract -- subclasses override; we still need a default
    // for the getAlpha mirror to be consistent in subclasses that DON'T
    // override.
    public void setAlphaInternal(int a) { this.alpha = a; }

    /** AOSP's base answer (android14-release Drawable.java:730-732) is null too;
     *  each leaf that owns a filter overrides it (GradientDrawable/VectorDrawable
     *  return their mColorFilter, BitmapDrawable/ColorDrawable their paint's). */
    public ColorFilter getColorFilter() { return null; }
    public void clearColorFilter() { setColorFilter(null); }

    /** The explicit-colour-filter half of the Drawable contract. Default ON;
     *  `MACAPK_DRAWABLE_COLOR_FILTER=0` restores the pre-change path for a
     *  one-binary bisect: setColorFilter(int, Mode) is a no-op again, and
     *  GradientDrawable / VectorDrawable / ColorStateListDrawable go back to
     *  ignoring setColorFilter(ColorFilter) and answering a null getColorFilter.
     *  docs/drawable-color-filter-ring-sysres_2026-09-26.md. */
    public static final boolean MACAPK_DRAWABLE_COLOR_FILTER =
        !"0".equals(System.getenv("MACAPK_DRAWABLE_COLOR_FILTER"));

    /** AOSP Drawable.setColorFilter(int, PorterDuff.Mode), android14-release
     *  Drawable.java:620-628, verbatim:
     *      if (getColorFilter() instanceof PorterDuffColorFilter) {
     *          PorterDuffColorFilter existing = (PorterDuffColorFilter) getColorFilter();
     *          if (existing.getColor() == color && existing.getMode() == mode) {
     *              return;
     *          }
     *      }
     *      setColorFilter(new PorterDuffColorFilter(color, mode));
     *  This was an EMPTY method, so the single most common legacy / Kotlin tint
     *  idiom -- `d.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)` --
     *  did nothing on every drawable, including the ones whose
     *  setColorFilter(ColorFilter) was already real (BitmapDrawable,
     *  NinePatchDrawable, ColorDrawable, the containers). MEASURED victim:
     *  Fossify Gallery tints its search field with the primary colour at 25%
     *  alpha this way; the emulator draws 0.75 x bar + 0.25 x primary, we drew
     *  the full-strength primary. */
    public void setColorFilter(int color, android.graphics.PorterDuff.Mode mode) {
        if (!MACAPK_DRAWABLE_COLOR_FILTER) return;
        final ColorFilter cur = getColorFilter();
        if (cur instanceof android.graphics.PorterDuffColorFilter) {
            android.graphics.PorterDuffColorFilter existing =
                (android.graphics.PorterDuffColorFilter) cur;
            if (existing.getColor() == color && existing.getMode() == mode) {
                return;
            }
        }
        setColorFilter(new android.graphics.PorterDuffColorFilter(color, mode));
    }

    /** One flat ARGB colour through a ColorFilter, the way Skia filters every
     *  pixel of it: `dst` is the colour the paint would draw (already modulated
     *  by the drawable's alpha), the filter's colour is the Porter-Duff SOURCE.
     *  The leaves that paint flat colours (GradientDrawable, VectorDrawable,
     *  ColorStateListDrawable) filter the colour once instead of asking Canvas,
     *  because Canvas.applyPaint's solidColorForFilter answers SRC_IN with the
     *  filter colour VERBATIM, dropping the destination's alpha
     *  (SRC_IN: a = as*ad). Returns null for a filter this cannot model exactly
     *  (a BlendMode with no Porter-Duff twin, or an unknown subclass); the caller
     *  then hands the filter to the Paint and Canvas's best effort applies.
     *    PorterDuffColorFilter  GradientDrawable.macapkBlend (PorterDuff.java's
     *                           own premultiplied formulas)
     *    BlendModeColorFilter   the same, through AOSP
     *                           BlendMode.blendModeToPorterDuffMode
     *                           (android14-release BlendMode.java:525-569:
     *                           MODULATE -> MULTIPLY, PLUS -> ADD,
     *                           "b/73224934 PorterDuff Multiply maps to Skia
     *                           Modulate"; everything past OVERLAY -> null)
     *    LightingColorFilter    rgb * mul / 255 + add, clamped, alpha untouched
     *    ColorMatrixColorFilter the 4x5 matrix over unpremultiplied 0..255 */
    static Integer macapkFilterColor(ColorFilter cf, int dst) {
        if (cf instanceof android.graphics.PorterDuffColorFilter) {
            android.graphics.PorterDuffColorFilter f = (android.graphics.PorterDuffColorFilter) cf;
            if (f.getMode() == null) return null;
            return GradientDrawable.macapkBlend(f.getColor(), dst, f.getMode());
        }
        if (cf instanceof android.graphics.BlendModeColorFilter) {
            android.graphics.BlendModeColorFilter f = (android.graphics.BlendModeColorFilter) cf;
            android.graphics.PorterDuff.Mode m = macapkBlendModeToPorterDuff(f.getMode());
            return m == null ? null : GradientDrawable.macapkBlend(f.getColor(), dst, m);
        }
        if (cf instanceof android.graphics.LightingColorFilter) {
            android.graphics.LightingColorFilter f = (android.graphics.LightingColorFilter) cf;
            int mul = f.getColorMultiply(), add = f.getColorAdd();
            int out = dst & 0xFF000000;
            for (int sh = 16; sh >= 0; sh -= 8) {
                int c = (dst >> sh) & 0xff, m = (mul >> sh) & 0xff, ad = (add >> sh) & 0xff;
                int v = Math.round(c * m / 255f) + ad;
                out |= Math.min(255, v) << sh;
            }
            return out;
        }
        if (cf instanceof android.graphics.ColorMatrixColorFilter) {
            float[] m = ((android.graphics.ColorMatrixColorFilter) cf).macapkArray();
            if (m == null || m.length < 20) return null;
            float r = (dst >> 16) & 0xff, g = (dst >> 8) & 0xff, b = dst & 0xff, a = (dst >>> 24);
            int[] o = new int[4];
            for (int row = 0; row < 4; row++) {
                float v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b
                        + m[row * 5 + 3] * a + m[row * 5 + 4];
                o[row] = Math.max(0, Math.min(255, Math.round(v)));
            }
            return (o[3] << 24) | (o[0] << 16) | (o[1] << 8) | o[2];
        }
        return null;
    }

    /** AOSP BlendMode.blendModeToPorterDuffMode (android14-release
     *  BlendMode.java:525-569), verbatim table. Kept here rather than calling
     *  our BlendMode.toPorterDuffModeOrNull, which maps MULTIPLY (Skia's
     *  separable kMultiply) to PorterDuff.MULTIPLY (Skia's kModulate) and has no
     *  MODULATE or PLUS row -- the opposite of AOSP on exactly the modes a
     *  tint uses. */
    static android.graphics.PorterDuff.Mode macapkBlendModeToPorterDuff(android.graphics.BlendMode m) {
        if (m == null) return null;
        switch (m) {
            case CLEAR:    return android.graphics.PorterDuff.Mode.CLEAR;
            case SRC:      return android.graphics.PorterDuff.Mode.SRC;
            case DST:      return android.graphics.PorterDuff.Mode.DST;
            case SRC_OVER: return android.graphics.PorterDuff.Mode.SRC_OVER;
            case DST_OVER: return android.graphics.PorterDuff.Mode.DST_OVER;
            case SRC_IN:   return android.graphics.PorterDuff.Mode.SRC_IN;
            case DST_IN:   return android.graphics.PorterDuff.Mode.DST_IN;
            case SRC_OUT:  return android.graphics.PorterDuff.Mode.SRC_OUT;
            case DST_OUT:  return android.graphics.PorterDuff.Mode.DST_OUT;
            case SRC_ATOP: return android.graphics.PorterDuff.Mode.SRC_ATOP;
            case DST_ATOP: return android.graphics.PorterDuff.Mode.DST_ATOP;
            case XOR:      return android.graphics.PorterDuff.Mode.XOR;
            case DARKEN:   return android.graphics.PorterDuff.Mode.DARKEN;
            case LIGHTEN:  return android.graphics.PorterDuff.Mode.LIGHTEN;
            case MODULATE: return android.graphics.PorterDuff.Mode.MULTIPLY;
            case SCREEN:   return android.graphics.PorterDuff.Mode.SCREEN;
            case PLUS:     return android.graphics.PorterDuff.Mode.ADD;
            case OVERLAY:  return android.graphics.PorterDuff.Mode.OVERLAY;
            default:       return null;
        }
    }
    // AOSP tint storage (v1.0.423). Was all no-ops -> Compose Icon(tint=) and
    // AppCompat ?attr/colorControlNormal|colorOnSurface vectors recolored
    // nothing (icons painted their baked-in fillColor / wrong color / invisible
    // on matching backgrounds). setTint(int) routes through setTintList; a
    // subclass that actually paints (VectorDrawable) reads macapkEffectiveTint()
    // for the current state.
    protected android.content.res.ColorStateList mTintList;
    protected android.graphics.PorterDuff.Mode mTintMode = android.graphics.PorterDuff.Mode.SRC_IN;
    public void setTint(int tint) { setTintList(android.content.res.ColorStateList.valueOf(tint)); }
    public void setTintList(android.content.res.ColorStateList tint) { mTintList = tint; invalidateSelf(); }
    public void setTintMode(android.graphics.PorterDuff.Mode tintMode) { mTintMode = tintMode; invalidateSelf(); }
    public void setTintBlendMode(android.graphics.BlendMode blendMode) {}
    public android.content.res.ColorStateList getTintList() { return mTintList; }
    public android.graphics.PorterDuff.Mode getTintMode() { return mTintMode; }
    /** Effective tint color (ARGB) for the current drawable state, or 0 if no
     *  tint set. For subclass draw() implementations. */
    protected int macapkEffectiveTint() {
        if (mTintList == null) return 0;
        return mTintList.getColorForState(getState(), mTintList.getDefaultColor());
    }

    public void setDither(boolean dither) {}
    public void setFilterBitmap(boolean filter) {}
    public boolean isFilterBitmap() { return false; }
    // android:autoMirrored / setAutoMirrored: flip horizontally under RTL
    // (back arrows, chevrons). Was a no-op, so every such icon pointed the
    // LTR way in a Hebrew/Arabic UI.
    private boolean mMacapkAutoMirrored;
    public void setAutoMirrored(boolean mirrored) { mMacapkAutoMirrored = mirrored; }
    public boolean isAutoMirrored() { return mMacapkAutoMirrored; }
    public void setHotspot(float x, float y) {}
    public void setHotspotBounds(int left, int top, int right, int bottom) {}
    public void getHotspotBounds(android.graphics.Rect outRect) {}
    // v1.0.98: AOSP returns boolean (level-changed); void here breaks
    // signature-strict callers (CircularProgressIndicator).
    private int mLevel = 0;
    /** AOSP Drawable.setLevel (Drawable.java:893-899) returns onLevelChange's
     *  answer, not "the level changed":
     *      if (mLevel != level) { mLevel = level; return onLevelChange(level); }
     *      return false;
     *  The distinction is the whole contract -- true means A VISUAL CHANGE
     *  HAPPENED. Returning true for any change made a plain ColorDrawable claim
     *  it repainted on setLevel(5000) (DRW.sm.cdOpaque.setlevel5000, ref 0), which
     *  is what a ProgressBar-style caller uses to decide whether to invalidate. */
    public boolean setLevel(int level) {
        if (mLevel != level) { mLevel = level; return onLevelChange(level); }
        return false;
    }
    public int  getLevel() { return mLevel; }
    // v1.0.97: AOSP returns boolean (changed-or-not). Material's
    // BadgeDrawable.setVisible chain reads the return; void here
    // NoSuchMethodErrors during BadgeState <init>.
    private boolean mVisible = true;
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = mVisible != visible;
        mVisible = visible;
        return changed;
    }
    public boolean isVisible() { return mVisible; }
    // AOSP records the resolved direction and only calls the subclass hook
    // when it actually changes, so a subclass can recompute direction-
    // dependent geometry (mirrored insets, start/end padding) exactly once.
    // Returning a hardcoded false meant no subclass ever learned the
    // direction changed, and getLayoutDirection() lied about the current one.
    // AOSP marks setLayoutDirection final; left non-final here because final
    // is not part of the JVM descriptor (no app can legally override it
    // anyway) and dropping it keeps in-tree wrappers free to forward.
    private int mLayoutDirection;
    public boolean setLayoutDirection(int layoutDirection) {
        if (mLayoutDirection != layoutDirection) {
            mLayoutDirection = layoutDirection;
            return onLayoutDirectionChanged(layoutDirection);
        }
        return false;
    }
    public int  getLayoutDirection() { return mLayoutDirection; }
    /** AOSP Drawable.onLayoutDirectionChanged(int): the subclass hook
     *  setLayoutDirection drives. True means "my geometry changed, redraw
     *  me". The base drawable has no direction-dependent geometry. */
    public boolean onLayoutDirectionChanged(int layoutDirection) { return false; }
    public boolean getPadding(android.graphics.Rect padding) {
        if (padding != null) padding.set(0, 0, 0, 0);
        return false;
    }
    public Drawable getCurrent() { return this; }
    /** AOSP Drawable.mChangingConfigurations is a plain stored field
     *  (Drawable.java:955-993). Both accessors were stubbed, so a drawable could
     *  not report which configuration changes invalidate it -- and, because every
     *  ConstantState in AOSP is stamped from getChangingConfigurations() at
     *  getConstantState() time, the state answered 0 as well
     *  (DRW.cs.chgcfgAfterSet / DRW.cs.chgcfgPropagatesToState, ref 0x200).
     *  Subclasses that own a ConstantState OR the state's own bits in on top. */
    protected int mChangingConfigurations;
    public int  getChangingConfigurations() { return mChangingConfigurations; }
    public void setChangingConfigurations(int configs) { mChangingConfigurations = configs; }
    public void applyTheme(android.content.res.Resources.Theme t) {}
    public boolean canApplyTheme() { return false; }
    public void onBoundsChange(android.graphics.Rect bounds) {}
    // v1.0.97: AOSP-spec protected lifecycle callbacks. Material widgets'
    // setState chains expect these to exist + return false (= "state
    // change handled but no redraw needed"). MaterialShapeDrawable
    // and RippleDrawable override; the base impl is a no-op false.
    /** AOSP's BASE onStateChange returns false, but EVERY tintable leaf overrides
     *  it with the identical body -- ColorDrawable.java:226-234,
     *  ShapeDrawable.java:354-362, BitmapDrawable.java:747-755 and
     *  NinePatchDrawable are byte-for-byte the same three lines:
     *      if (state.mTint != null && state.mBlendMode != null) {
     *          mBlendModeColorFilter = updateBlendModeFilter(...);
     *          return true;
     *      }
     *      return false;
     *  Note it is true whenever a tint and a mode are BOTH present, even for a
     *  plain non-stateful colour tint -- deliberately asymmetric with isStateful(),
     *  which requires the tint itself to be stateful (DRW.cd.plainTint.setState=1
     *  vs DRW.cd.plainTint.isStateful=0). We hold the tint on the base
     *  (mTintList/mTintMode), for the same reason isStateful() is hoisted above, so
     *  the union of those overrides belongs here: one implementation covers every
     *  leaf instead of four copies. Containers and wrappers override with their own
     *  forwarding behaviour and never reach this. */
    public boolean onStateChange(int[] state) {
        return mTintList != null && mTintMode != null;
    }
    public boolean onLevelChange(int level) { return false; }
    public void jumpToCurrentState() {}
    public int[] getOptical() { return null; }
    public Drawable getDrawable(int index) { return null; }

    public interface Callback {
        void invalidateDrawable(Drawable who);
        void scheduleDrawable(Drawable who, Runnable what, long when);
        void unscheduleDrawable(Drawable who, Runnable what);
    }
    public abstract static class ConstantState {
        public abstract Drawable newDrawable();
        public Drawable newDrawable(android.content.res.Resources res) { return newDrawable(); }
        public Drawable newDrawable(android.content.res.Resources res, android.content.res.Resources.Theme theme) { return newDrawable(); }
        public abstract int getChangingConfigurations();
        public boolean canApplyTheme() { return false; }
    }

    // -------- v1.0.116 AOSP audit batch --------

    /** AOSP Drawable.inflate(Resources, XmlPullParser, AttributeSet):
     *  parses theme attrs from XML. We don't inflate from XML directly. */
    public void inflate(android.content.res.Resources r, org.xmlpull.v1.XmlPullParser parser,
                         android.util.AttributeSet attrs) throws java.io.IOException {}
    public void inflate(android.content.res.Resources r, org.xmlpull.v1.XmlPullParser parser,
                         android.util.AttributeSet attrs, android.content.res.Resources.Theme theme) throws java.io.IOException {}
    // ---- AOSP Drawable-from-XmlPullParser factory chain ----
    // createFromXml -> createFromXmlForDensity -> createFromXmlInner ->
    // createFromXmlInnerForDensity -> DrawableInflater.inflateFromXmlForDensity,
    // which dispatches on the CURRENT TAG NAME to a concrete Drawable subclass
    // and then hands the parser to that instance's inflate(). Third-party
    // image/vector/icon libraries and androidx compat shims call
    // createFromXmlInner directly on a parser they obtained from
    // Resources.getXml(id); it was absent here entirely (NoSuchMethodError)
    // and both createFromXml overloads returned a hardcoded null (NPE at the
    // caller), so the whole public API was unusable.
    //
    // NOTE for whoever lands per-subclass inflation: the tag dispatch below is
    // AOSP-faithful, but Drawable.inflate() is still a no-op in this tree and
    // no subclass overrides it, so an inflated drawable currently comes back
    // default-constructed. That gap now lives where AOSP puts it (the concrete
    // subclass's inflate), not hidden behind a missing factory.
    public static Drawable createFromXml(android.content.res.Resources r,
                                         org.xmlpull.v1.XmlPullParser parser)
            throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        return createFromXml(r, parser, null);
    }
    public static Drawable createFromXml(android.content.res.Resources r,
                                         org.xmlpull.v1.XmlPullParser parser,
                                         android.content.res.Resources.Theme theme)
            throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        return createFromXmlForDensity(r, parser, 0, theme);
    }
    public static Drawable createFromXmlForDensity(android.content.res.Resources r,
                                                   org.xmlpull.v1.XmlPullParser parser,
                                                   int density,
                                                   android.content.res.Resources.Theme theme)
            throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        android.util.AttributeSet attrs = android.util.Xml.asAttributeSet(parser);
        int type;
        while ((type = parser.next()) != org.xmlpull.v1.XmlPullParser.START_TAG
                && type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
        }
        if (type != org.xmlpull.v1.XmlPullParser.START_TAG) {
            throw new org.xmlpull.v1.XmlPullParserException("No start tag found");
        }
        Drawable drawable = createFromXmlInnerForDensity(r, parser, attrs, density, theme);
        if (drawable == null) {
            throw new RuntimeException("Unknown initial tag: " + parser.getName());
        }
        return drawable;
    }
    public static Drawable createFromXmlInner(android.content.res.Resources r,
                                              org.xmlpull.v1.XmlPullParser parser,
                                              android.util.AttributeSet attrs)
            throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        return createFromXmlInner(r, parser, attrs, null);
    }
    public static Drawable createFromXmlInner(android.content.res.Resources r,
                                              org.xmlpull.v1.XmlPullParser parser,
                                              android.util.AttributeSet attrs,
                                              android.content.res.Resources.Theme theme)
            throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        return createFromXmlInnerForDensity(r, parser, attrs, 0, theme);
    }
    // Package-private in AOSP; density is the caller's requested source
    // density. AOSP forwards it via Drawable.setSrcDensityOverride, which this
    // tree has no equivalent for (nothing here tracks a per-drawable source
    // density), so the parameter is carried for signature fidelity only.
    static Drawable createFromXmlInnerForDensity(android.content.res.Resources r,
                                                 org.xmlpull.v1.XmlPullParser parser,
                                                 android.util.AttributeSet attrs,
                                                 int density,
                                                 android.content.res.Resources.Theme theme)
            throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        String name = parser.getName();
        // AOSP: inner classes must be written Outer$Inner, which XML tag names
        // cannot express, so <drawable class="foo.bar.Outer$Inner"/> is the
        // escape hatch for a custom Drawable.
        if ("drawable".equals(name)) {
            name = attrs == null ? null : attrs.getAttributeValue(null, "class");
            if (name == null) {
                throw new android.view.InflateException(
                    "<drawable> tag must specify class attribute");
            }
        }
        Drawable drawable = inflateFromTag(name);
        if (drawable == null) {
            drawable = inflateFromClass(name);
        }
        drawable.inflate(r, parser, attrs, theme);
        return drawable;
    }
    /** AOSP DrawableInflater.inflateFromTag: the built-in tag table. */
    private static Drawable inflateFromTag(String name) {
        if (name == null) return null;
        switch (name) {
            case "selector":          return new StateListDrawable();
            case "animated-selector": return new AnimatedStateListDrawable();
            case "level-list":        return new LevelListDrawable();
            case "layer-list":        return new LayerDrawable();
            case "ripple":            return new RippleDrawable();
            case "color":             return new ColorDrawable();
            case "shape":             return new GradientDrawable();
            case "vector":            return new VectorDrawable();
            case "animated-vector":   return new AnimatedVectorDrawable();
            case "scale":             return new ScaleDrawable();
            case "clip":              return new ClipDrawable();
            case "rotate":            return new RotateDrawable();
            case "animation-list":    return new AnimationDrawable();
            case "inset":             return new InsetDrawable();
            case "bitmap":            return new BitmapDrawable();
            case "animated-image":    return new AnimatedImageDrawable();
            // AOSP also maps <transition>, <adaptive-icon>, <nine-patch> and
            // <animated-rotate>. TransitionDrawable / AdaptiveIconDrawable /
            // NinePatchDrawable declare no no-arg constructor in this tree and
            // AnimatedRotateDrawable does not exist yet, so they deliberately
            // fall through to the class-name path and surface an
            // InflateException naming the class. Substituting some other
            // drawable would silently render the wrong shape, which is worse
            // than a loud failure pointing at the file to fix.
            default:                  return null;
        }
    }
    /** AOSP DrawableInflater.inflateFromClass: the tag is a fully-qualified
     *  custom Drawable class name, loaded from the app's class loader. */
    private static Drawable inflateFromClass(String className) {
        // AOSP discriminates the failure in the message text ("Class not
        // found" / "Class is not a Drawable" / "Error inflating class");
        // library error handling greps for those, so keep them verbatim.
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = Drawable.class.getClassLoader();
            Class<? extends Drawable> clazz =
                cl.loadClass(className).asSubclass(Drawable.class);
            return clazz.getConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new android.view.InflateException("Class not found " + className, e);
        } catch (ClassCastException e) {
            throw new android.view.InflateException("Class is not a Drawable " + className, e);
        } catch (Exception e) {
            throw new android.view.InflateException("Error inflating class " + className, e);
        }
    }
    public static Drawable createFromStream(java.io.InputStream is, String srcName) {
        try {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(is);
            if (bm != null) return new BitmapDrawable(null, bm);
        } catch (Throwable ignored) {}
        return null;
    }
    public static Drawable createFromPath(String pathName) {
        try {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(pathName);
            if (bm != null) return new BitmapDrawable(null, bm);
        } catch (Throwable ignored) {}
        return null;
    }
    public static Drawable createFromResourceStream(android.content.res.Resources res,
                                                       android.util.TypedValue value,
                                                       java.io.InputStream is, String srcName) {
        return createFromStream(is, srcName);
    }
    public static Drawable createFromResourceStream(android.content.res.Resources res,
                                                       android.util.TypedValue value,
                                                       java.io.InputStream is, String srcName,
                                                       android.graphics.BitmapFactory.Options opts) {
        return createFromStream(is, srcName);
    }

    /** AOSP Drawable.getOutline(Outline): writes the drawable's outline into
     *  the caller's Outline. The parameter type is load-bearing: the JVM
     *  resolves an app's `super.getOutline(outline)` (Material's
     *  MaterialShapeDrawable and friends do exactly that) against the
     *  DECLARED class android.graphics.drawable.Drawable, so a
     *  getOutline(Object) here is a different descriptor and raises
     *  NoSuchMethodError; an app @Override with the real signature also fails
     *  to override and never gets dispatched. AOSP's default reports the
     *  bounds rect with alpha 0, the sentinel meaning "no meaningful outline,
     *  do not cast a shadow for it". */
    public void getOutline(android.graphics.Outline outline) {
        outline.setRect(getBounds());
        outline.setAlpha(0);
    }

    /** AOSP Drawable.getTransparentRegion(): returns the region of the
     *  drawable that's fully transparent. null = "I don't know." */
    public android.graphics.Region getTransparentRegion() { return null; }

    /** AOSP Drawable.resolveOpacity(int, int): combines two opacity
     *  constants. Used by LayerDrawable / TransitionDrawable. */
    public static int resolveOpacity(int op1, int op2) {
        // android.graphics.PixelFormat opacity codes:
        //   OPAQUE      (-1) > TRANSLUCENT (-3) > TRANSPARENT (-2) > UNKNOWN (0)
        // AOSP picks the lowest "definite" value. Translucent wins.
        final int OPAQUE = -1, TRANSPARENT = -2, TRANSLUCENT = -3, UNKNOWN = 0;
        if (op1 == op2) return op1;
        if (op1 == UNKNOWN || op2 == UNKNOWN) return UNKNOWN;
        if (op1 == TRANSLUCENT || op2 == TRANSLUCENT) return TRANSLUCENT;
        if (op1 == TRANSPARENT || op2 == TRANSPARENT) return TRANSPARENT;
        return OPAQUE;
    }

    /** AOSP Drawable.setBoundsParam (overload). */
    public void setBoundsParam(Rect r) { setBounds(r); }

    /** AOSP Drawable.getDirtyBounds(): the region that needs repainting
     *  on the next draw. Default: full bounds. */
    public Rect getDirtyBounds() { return getBounds(); }

    /** AOSP Drawable.isProjected(): API-21+ used by RippleDrawable to
     *  request projection to the focused view. */
    public boolean isProjected() { return false; }

    /** AOSP Drawable.canApplyTheme already present above.
     *  applyTheme overload taking int[]. */
    public boolean isAutoMirroredCompat() { return isAutoMirrored(); }

    public ColorFilter getColorFilterCompat() { return getColorFilter(); }

    /** AOSP Drawable.getLayoutInsets(): API-29+. */
    public android.graphics.Insets getLayoutInsets() { return android.graphics.Insets.NONE; }

    /** AOSP Drawable.getOpticalInsets(): `return Insets.NONE;`. Both halves
     *  matter. The return TYPE is part of the descriptor, so Object here is a
     *  NoSuchMethodError for the 22 corpus apps (androidx DrawableWrapper
     *  compat shims) that call the real `()Landroid/graphics/Insets;` form,
     *  and the null default would NPE the AOSP-shaped callers that
     *  unconditionally read insets.left on the result. */
    public android.graphics.Insets getOpticalInsets() { return android.graphics.Insets.NONE; }
}
