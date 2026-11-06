// android.graphics.drawable.BitmapDrawable -- AOSP-shape wrapper
// around a Bitmap. Resources.getDrawable returns one of these for
// PNG/JPG/WebP/GIF resources; ImageView.setImageDrawable then
// uses getBitmap() to extract the bitmap for drawing. v1.0.99.
//
// AOSP ref: frameworks/base/graphics/java/android/graphics/drawable/BitmapDrawable.java
package android.graphics.drawable;

import android.graphics.Shader;

public class BitmapDrawable extends Drawable {
    private android.graphics.Bitmap bitmap;

    public BitmapDrawable() {}
    public BitmapDrawable(android.graphics.Bitmap bm) { this.bitmap = bm; }
    public BitmapDrawable(android.content.res.Resources res, android.graphics.Bitmap bm) {
        this.bitmap = bm;
        adoptDensity(res);
    }
    public BitmapDrawable(android.content.res.Resources res, String path) {
        try {
            java.io.InputStream in = new java.io.FileInputStream(path);
            this.bitmap = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
        } catch (Throwable ignored) {}
        adoptDensity(res);
    }
    public BitmapDrawable(android.content.res.Resources res, java.io.InputStream in) {
        try {
            this.bitmap = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {}
        adoptDensity(res);
    }

    public android.graphics.Bitmap getBitmap() { return bitmap; }
    public void setBitmap(android.graphics.Bitmap bm) {
        this.bitmap = bm;
        // A new bitmap needs a new shader when tiling is configured (AOSP sets
        // mRebuildShader from setBitmap via BitmapState).
        mRebuildShader = (mTileModeX != null || mTileModeY != null);
    }

    // AOSP positioning + paint hints. Apps configure these before
    // blitting the bitmap into a surface (the Subway/Crossy/DumbWays
    // overlay-driver surfaceChanged path called setGravity -> the method
    // was missing -> NoSuchMethodError). Gravity defaults to FILL (0x77)
    // per AOSP BitmapDrawable, which draw() honours below: FILL stretches
    // to the bounds, every other value positions the bitmap at its own
    // size inside them.
    private int gravity = android.view.Gravity.FILL;
    /** AOSP BitmapDrawable's paint is `new Paint(DEFAULT_PAINT_FLAGS)` where
     *  DEFAULT_PAINT_FLAGS = FILTER_BITMAP_FLAG | DITHER_FLAG -- NOT
     *  ANTI_ALIAS_FLAG, which is why hasAntiAlias() is false on a fresh
     *  BitmapDrawable (DRW.bd.antiAlias, ref "default=0 set=1"). A blit is
     *  smoothed by bilinear FILTERING, not by edge anti-aliasing; our Canvas
     *  only consults isAntiAlias() on the shape/path path, so this does not
     *  change how a bitmap is sampled. */
    private final android.graphics.Paint paint = new android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG);
    public void setGravity(int g) { this.gravity = g; invalidateSelf(); }
    public int  getGravity()      { return gravity; }
    public android.graphics.Paint getPaint() { return paint; }
    public void setAntiAlias(boolean a)    { paint.setAntiAlias(a); invalidateSelf(); }
    public boolean hasAntiAlias()          { return paint.isAntiAlias(); }
    public void setFilterBitmap(boolean f) {}
    public void setDither(boolean d)       {}
    /** AOSP BitmapDrawable.setMipMap(boolean) forwards to the Bitmap; ours has
     *  no mip chain, so it records the request so hasMipMap() can answer it. */
    public void setMipMap(boolean m)       { mMipMap = m; }
    /** AOSP BitmapDrawable.hasMipMap() -- "Indicates whether the mipmap hint is
     *  enabled on this drawable's bitmap." Reports what setMipMap was given. */
    public boolean hasMipMap()             { return mMipMap; }
    private boolean mMipMap;
    public void setAutoMirrored(boolean m) {}
    public boolean isAutoMirrored()        { return false; }

    // ---- tile modes -------------------------------------------------
    // AOSP: "By default, the drawable does not repeat its bitmap" -- both
    // modes start null, and a null pair means "blit once", not CLAMP-tile.
    // Once either is set, AOSP paints through a BitmapShader over the whole
    // bounds instead of a stretch blit, which is how repeating background
    // textures work. Canvas maps a BitmapShader onto a TexturePaint, so the
    // repeat is real rather than a stored-and-ignored flag.
    private Shader.TileMode mTileModeX;
    private Shader.TileMode mTileModeY;
    private boolean mRebuildShader;

    public Shader.TileMode getTileModeX() { return mTileModeX; }
    public Shader.TileMode getTileModeY() { return mTileModeY; }
    public void setTileModeX(Shader.TileMode mode) { setTileModeXY(mode, mTileModeY); }
    public void setTileModeY(Shader.TileMode mode) { setTileModeXY(mTileModeX, mode); }
    public void setTileModeXY(Shader.TileMode xmode, Shader.TileMode ymode) {
        if (mTileModeX != xmode || mTileModeY != ymode) {
            mTileModeX = xmode;
            mTileModeY = ymode;
            mRebuildShader = true;
            invalidateSelf();
        }
    }

    // ---- target density ---------------------------------------------
    // AOSP scales the drawable's intrinsic size from the BITMAP's density to
    // the density of the display it will be rendered at
    // (Bitmap.getScaledWidth(mTargetDensity)). Mac.apk's display bucket is the
    // device density (DisplayMetrics.DENSITY_DEVICE_STABLE) and Bitmap's own
    // default source density is the same bucket, so an unconfigured BitmapDrawable
    // scales by exactly 1.0 -- the same answer a device whose display matches
    // the asset density gives. A Resources-carrying constructor adopts that
    // Resources' real density, as AOSP does.
    private int mTargetDensity = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;

    private void adoptDensity(android.content.res.Resources res) {
        if (res == null) return;
        try {
            android.util.DisplayMetrics dm = res.getDisplayMetrics();
            if (dm != null && dm.densityDpi != 0) mTargetDensity = dm.densityDpi;
        } catch (Throwable ignored) {}
    }

    /** AOSP: `mTargetDensity = density == 0 ? DisplayMetrics.DENSITY_DEFAULT : density`. */
    public void setTargetDensity(int density) {
        if (mTargetDensity != density) {
            mTargetDensity = (density == 0)
                ? android.util.DisplayMetrics.DENSITY_DEFAULT : density;
            invalidateSelf();
        }
    }
    public void setTargetDensity(android.util.DisplayMetrics metrics) {
        if (metrics != null) setTargetDensity(metrics.densityDpi);
    }
    public void setTargetDensity(android.graphics.Canvas canvas) {
        if (canvas != null) setTargetDensity(canvas.getDensity());
    }

    // Scratch rects reused per frame; AOSP keeps mDstRect on the drawable for
    // the same reason (draw() runs every frame and Drawables are not shared
    // across threads).
    private final android.graphics.Rect mSrcRect = new android.graphics.Rect();
    private final android.graphics.Rect mDstRect = new android.graphics.Rect();
    private final android.graphics.Rect mContainer = new android.graphics.Rect();

    @Override public void draw(android.graphics.Canvas canvas) {
        if (canvas == null || bitmap == null) return;
        final int bw = bitmap.getWidth();
        final int bh = bitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;

        if (mRebuildShader) {
            final android.graphics.Paint p = getPaint();
            if (mTileModeX == null && mTileModeY == null) {
                p.setShader(null);
            } else {
                // AOSP substitutes CLAMP for an unset axis when the other axis
                // tiles, so a REPEAT-x / null-y drawable still gets a shader.
                p.setShader(new android.graphics.BitmapShader(bitmap,
                        mTileModeX == null ? Shader.TileMode.CLAMP : mTileModeX,
                        mTileModeY == null ? Shader.TileMode.CLAMP : mTileModeY));
            }
            mRebuildShader = false;
        }

        // Callers that never called setBounds leave an empty rect on that axis.
        // Painting at the bitmap's own size there is long-standing Mac.apk
        // behaviour that several in-tree blit paths depend on; AOSP would draw
        // nothing, which would be a silent regression for them.
        final android.graphics.Rect bounds = getBounds();
        final int cw = bounds.width()  > 0 ? bounds.width()  : bw;
        final int ch = bounds.height() > 0 ? bounds.height() : bh;
        mContainer.set(bounds.left, bounds.top, bounds.left + cw, bounds.top + ch);

        if (mTileModeX != null || mTileModeY != null) {
            // AOSP: when a tile mode is active the destination is the full
            // bounds and the shader supplies the repetition (gravity does not
            // participate -- copyBounds(mDstRect), mOpticalInsets = NONE).
            final android.graphics.ColorFilter tf = macapkTintFilter();
            final boolean tinted = tf != null && paint.getColorFilter() == null;
            if (tinted) paint.setColorFilter(tf);
            try {
                canvas.drawRect(mContainer, getPaint());
            } finally {
                if (tinted) paint.setColorFilter(null);
            }
            return;
        }

        applyGravity(gravity, bw, bh, mContainer, mDstRect, getLayoutDirection());
        mSrcRect.set(0, 0, bw, bh);
        // Pass the configured paint (alpha / color filter) so Glide/Coil
        // cross-fade transitions (which tween BitmapDrawable alpha) actually
        // fade instead of popping. v1.0.423.
        // AOSP BitmapDrawable.draw: "if (mTintFilter != null && paint.getColorFilter()
        // == null) paint.setColorFilter(mTintFilter)". The tint was stored on the
        // Drawable base and never drawn, so every white PNG glyph an app tints at
        // runtime (WhatsApp's toolbar back/search icons, PalGate's buttons) stayed
        // white on a light surface.
        final android.graphics.ColorFilter tf = macapkTintFilter();
        final boolean tinted = tf != null && paint.getColorFilter() == null;
        if (tinted) paint.setColorFilter(tf);
        try {
            canvas.drawBitmap(bitmap, mSrcRect, mDstRect, paint);
        } finally {
            if (tinted) paint.setColorFilter(null);
        }
    }

    // One filter per (colour, mode), reused across frames like AOSP's mTintFilter
    // (updateTintFilter), so the canvas's filtered-image cache keeps hitting.
    private android.graphics.PorterDuffColorFilter mMacapkTintFilter;
    private int mMacapkTintColor;
    private android.graphics.PorterDuff.Mode mMacapkTintFilterMode;
    private android.graphics.ColorFilter macapkTintFilter() {
        final android.graphics.PorterDuff.Mode mode = mTintMode;
        if (mTintList == null || mode == null) return null;
        final int color = macapkEffectiveTint();
        if (mMacapkTintFilter == null || color != mMacapkTintColor || mode != mMacapkTintFilterMode) {
            mMacapkTintFilter = new android.graphics.PorterDuffColorFilter(color, mode);
            mMacapkTintColor = color;
            mMacapkTintFilterMode = mode;
        }
        return mMacapkTintFilter;
    }

    /** AOSP Gravity.apply(gravity, w, h, container, outRect, layoutDirection).
     *  android.view.Gravity.apply in this tree switches only on
     *  LEFT/RIGHT/CENTER and falls through to "place at the container's
     *  left/top at the requested size" for FILL_HORIZONTAL/FILL_VERTICAL, so
     *  calling it with this class's default FILL gravity would shrink every
     *  bitmap to its intrinsic size instead of stretching it to the bounds.
     *  Gravity.java is outside this change, so the placement is computed here
     *  off the same AXIS_PULL bits AOSP's Gravity switches on. */
    private static void applyGravity(int gravity, int w, int h,
                                     android.graphics.Rect container,
                                     android.graphics.Rect outRect,
                                     int layoutDirection) {
        final int g = android.view.Gravity.getAbsoluteGravity(gravity, layoutDirection);
        final int xPull = (android.view.Gravity.AXIS_PULL_BEFORE
                         | android.view.Gravity.AXIS_PULL_AFTER) << android.view.Gravity.AXIS_X_SHIFT;
        final int yPull = (android.view.Gravity.AXIS_PULL_BEFORE
                         | android.view.Gravity.AXIS_PULL_AFTER) << android.view.Gravity.AXIS_Y_SHIFT;
        switch (g & xPull) {
            case 0:
                outRect.left = container.left + ((container.right - container.left - w) / 2);
                outRect.right = outRect.left + w;
                break;
            case android.view.Gravity.AXIS_PULL_BEFORE << android.view.Gravity.AXIS_X_SHIFT:
                outRect.left = container.left;
                outRect.right = outRect.left + w;
                break;
            case android.view.Gravity.AXIS_PULL_AFTER << android.view.Gravity.AXIS_X_SHIFT:
                outRect.right = container.right;
                outRect.left = outRect.right - w;
                break;
            default:
                outRect.left = container.left;
                outRect.right = container.right;
                break;
        }
        switch (g & yPull) {
            case 0:
                outRect.top = container.top + ((container.bottom - container.top - h) / 2);
                outRect.bottom = outRect.top + h;
                break;
            case android.view.Gravity.AXIS_PULL_BEFORE << android.view.Gravity.AXIS_Y_SHIFT:
                outRect.top = container.top;
                outRect.bottom = outRect.top + h;
                break;
            case android.view.Gravity.AXIS_PULL_AFTER << android.view.Gravity.AXIS_Y_SHIFT:
                outRect.bottom = container.bottom;
                outRect.top = outRect.bottom - h;
                break;
            default:
                outRect.top = container.top;
                outRect.bottom = container.bottom;
                break;
        }
    }

    @Override public void setAlpha(int alpha) { getPaint().setAlpha(alpha); invalidateSelf(); }
    /** AOSP reads the alpha back off the PAINT (BitmapDrawable.java, getAlpha).
     *  Without the override this fell through to Drawable's unrelated alpha
     *  field, which setAlpha never touched -- so a Glide/Coil cross-fade that
     *  reads getAlpha() back saw 255 the whole way through
     *  (DRW.bd.alpha128.getAlpha, ref 128). */
    @Override public int getAlpha() { return getPaint().getAlpha(); }
    @Override public void setColorFilter(android.graphics.ColorFilter cf) { getPaint().setColorFilter(cf); invalidateSelf(); }
    /** AOSP BitmapDrawable.getColorFilter (android14-release :672-675):
     *  `return mBitmapState.mPaint.getColorFilter();`. It was missing, so the base
     *  answered null and Drawable.setColorFilter(int, Mode)'s same-filter
     *  early-out could never fire. MACAPK_DRAWABLE_COLOR_FILTER=0: the old null. */
    @Override public android.graphics.ColorFilter getColorFilter() {
        return MACAPK_DRAWABLE_COLOR_FILTER ? getPaint().getColorFilter() : null;
    }
    /** AOSP BitmapDrawable.getOpacity. The GRAVITY gate comes FIRST and is an
     *  EQUALITY test: anything other than exactly Gravity.FILL is TRANSLUCENT
     *  before the bitmap is even consulted, because a non-filling gravity leaves
     *  part of the bounds unpainted. FILL_HORIZONTAL (0x07) is the near-miss
     *  that a `(g & FILL) != 0` test would wrongly call opaque. Then an alpha
     *  channel or a non-255 paint alpha demotes it as well.
     *
     *  KNOWN GAP (not fixable from this file): the bitmap.hasAlpha() term is
     *  only as good as android.graphics.Bitmap, and ours defaults hasAlpha to
     *  true for EVERY config -- including RGB_565, which has no alpha channel at
     *  all. So DRW.bd.rgb565.opacity / .gravity.fill.opacity / .tiled.opacity
     *  still answer TRANSLUCENT where the device says OPAQUE. The fix belongs in
     *  Bitmap.createBitmap (hasAlpha = config != RGB_565), which is another
     *  lane's file. DRW.bd.argbNoAlpha.opacity, where the probe calls
     *  setHasAlpha(false) explicitly, is correct with this change alone. */
    @Override public int  getOpacity() {
        if (gravity != android.view.Gravity.FILL) return android.graphics.PixelFormat.TRANSLUCENT;
        return (bitmap == null || bitmap.hasAlpha() || paint.getAlpha() < 255)
                ? android.graphics.PixelFormat.TRANSLUCENT
                : android.graphics.PixelFormat.OPAQUE;
    }
    // AOSP computeBitmapSize(): the intrinsic size is the bitmap scaled from
    // its own density to the target density.
    @Override public int  getIntrinsicWidth()  {
        return bitmap != null ? bitmap.getScaledWidth(mTargetDensity)  : -1;
    }
    @Override public int  getIntrinsicHeight() {
        return bitmap != null ? bitmap.getScaledHeight(mTargetDensity) : -1;
    }
}
