// android.graphics.Outline -- AOSP class describing a View's outline,
// used by ViewOutlineProvider for elevation shadows + the clip path
// when View.setClipToOutline(true). We don't render shadows, but we
// MUST record the shape: View.draw consults the outline to clip the
// view to a circle / round-rect (the calculator keypad buttons set
// clipToOutline + a round-rect provider with radius=min(w,h)/2 to
// become circles; without recording the shape every button paints
// its tint as a full square).
// AOSP: frameworks/base/graphics/java/android/graphics/Outline.java
package android.graphics;

public final class Outline {
    public static final int MODE_EMPTY      = 0;
    public static final int MODE_ROUND_RECT = 1;
    public static final int MODE_PATH       = 2;  // AOSP MODE_CONVEX_PATH alias

    public int mMode = MODE_EMPTY;
    public final Rect mRect = new Rect();
    /** AOSP RADIUS_UNDEFINED (Outline.java:39). NOT 0: zero is a legitimate
     *  radius (a sharp-cornered round rect), so a separate sentinel is the only
     *  way a caller can tell "no radius set" from "radius zero". AOSP uses
     *  Float.NEGATIVE_INFINITY and setEmpty/setPath/non-square setOval reset to
     *  it. It formats as the literal -Infinity, which is a correct answer here,
     *  not a formatter bug. */
    public float mRadius = Float.NEGATIVE_INFINITY;
    public Path mPath;
    /** AOSP declares this with NO initializer (Outline.java:75), so a fresh
     *  Outline's alpha is 0.0f, not 1.0f. It matters because
     *  ViewOutlineProvider.BOUNDS and PADDED_BOUNDS call setRect and never touch
     *  alpha, so the fresh-Outline default leaks straight through to the caller;
     *  only BACKGROUND sets it explicitly. Nine VW.out.*.alpha rows were this
     *  one character. */
    public float mAlpha;
    /** Mac.apk-internal (not AOSP): for a PATH outline that is a rect with
     *  per-corner radii (GradientDrawable's radius array), the largest of those
     *  radii, else NaN. The shadow casters (WindowOverlayStack.drawWindowShadow,
     *  View.macapkShadowRadiusPx) draw round rects only; with this they cast
     *  the round rect of the path's bounds and this radius, which lies inside
     *  the shape. Clipping and ripple masks still use the exact path. */
    public float macapkPathShadowRadius = Float.NaN;

    public Outline() {}
    public Outline(Outline src) { set(src); }

    public void setEmpty() {
        mMode = MODE_EMPTY; mPath = null; mRect.setEmpty(); mRadius = Float.NEGATIVE_INFINITY;
        macapkPathShadowRadius = Float.NaN;
    }
    public boolean isEmpty() { return mMode == MODE_EMPTY; }
    /** AOSP, by PLATFORM level: up to API 32 an outline can clip unless it is a
     *  PATH (android12L-release Outline.java, `return mMode != MODE_PATH;`); from
     *  API 33 every shape can ("As of API 33, all Outline shapes support
     *  clipping", android14-release Outline.java:118-128, `return true;`).
     *
     *  1a5da7da (2026-08-09) pinned the pre-33 answer because the runtime then
     *  reported SDK_INT 29 against an API-30 reference, which was right at the
     *  time. Since PresentedSdk presents clamp(targetSdk, 29, 36), a modern app
     *  sees a 33+ device and must get that device's answer. It matters beyond
     *  the probe: Compose's GraphicsLayer.configureOutlineAndClip, given a path
     *  outline that "cannot clip" and clip = true, turns the layer's clip off and
     *  discardDisplayList()s it (it expects a re-record that clips by path),
     *  and this Compose build never re-records a discarded node by itself, so the
     *  whole layer drew nothing: Rhythm's Settings and Now Playing screens (a
     *  path-shaped Surface) were blank on Mac.apk, never on an API 33+ device.
     *  Our clipToOutline already clips by path (RenderNode.macapkApplyOutlineClip
     *  and View's outline clip), so the 33+ answer is honoured end to end. */
    public boolean canClip() {
        return (CANCLIP_BY_PLATFORM && android.os.Build.VERSION.SDK_INT >= 33)
            || mMode != MODE_PATH;
    }
    /** MACAPK_OUTLINE_CANCLIP_PLATFORM=0 restores the pre-v1.0.2241 answer
     *  (a path never clips, at every level). */
    private static final boolean CANCLIP_BY_PLATFORM =
        !"0".equals(System.getenv("MACAPK_OUTLINE_CANCLIP_PLATFORM"));

    public void setRect(int left, int top, int right, int bottom) {
        setRoundRect(left, top, right, bottom, 0f);
    }
    public void setRect(Rect rect) {
        if (rect != null) setRect(rect.left, rect.top, rect.right, rect.bottom);
    }
    public void setRoundRect(int left, int top, int right, int bottom, float radius) {
        if (left >= right || top >= bottom) { setEmpty(); return; }
        mMode = MODE_ROUND_RECT;
        mRect.set(left, top, right, bottom);
        mRadius = radius;
        mPath = null;
        macapkPathShadowRadius = Float.NaN;
    }
    public void setRoundRect(Rect rect, float radius) {
        if (rect != null) setRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius);
    }
    public void setOval(int left, int top, int right, int bottom) {
        if (left >= right || top >= bottom) { setEmpty(); return; }
        if ((right - left) == (bottom - top)) {
            // square bounds -> a circle, expressible as a round-rect.
            setRoundRect(left, top, right, bottom, (right - left) / 2f);
            return;
        }
        mMode = MODE_PATH;
        mRadius = Float.NEGATIVE_INFINITY;
        if (mPath == null) mPath = new Path();
        mPath.reset();
        mPath.addOval(left, top, right, bottom, Path.Direction.CW);
        mRect.setEmpty();
        macapkPathShadowRadius = Float.NaN;
    }
    public void setOval(Rect rect) {
        if (rect != null) setOval(rect.left, rect.top, rect.right, rect.bottom);
    }
    public void setConvexPath(Path path) { setPath(path); }
    public void setPath(Path path) {
        if (path == null || path.isEmpty()) { setEmpty(); return; }
        mMode = MODE_PATH;
        mRadius = Float.NEGATIVE_INFINITY;
        if (mPath == null) mPath = new Path();
        mPath.reset();
        mPath.addPath(path);
        mRect.setEmpty();
        macapkPathShadowRadius = Float.NaN;
    }
    public float getRadius() { return mRadius; }
    public boolean getRect(Rect outRect) {
        if (mMode == MODE_ROUND_RECT && outRect != null) { outRect.set(mRect); return true; }
        return false;
    }
    public void setAlpha(float alpha) { mAlpha = alpha; }
    public float getAlpha() { return mAlpha; }
    public void set(Outline src) {
        if (src == null) { setEmpty(); return; }
        mMode = src.mMode;
        mRect.set(src.mRect);
        mRadius = src.mRadius;
        mAlpha = src.mAlpha;
        mPath = (src.mPath == null) ? null : new Path(src.mPath);
        macapkPathShadowRadius = src.macapkPathShadowRadius;
    }
    public void offset(int dx, int dy) {
        if (mMode == MODE_ROUND_RECT) mRect.offset(dx, dy);
        else if (mMode == MODE_PATH && mPath != null) mPath.offset(dx, dy);
    }

    /** Mac.apk runtime helper (not AOSP): realize the recorded outline
     *  as a clip Path in the view's local coords. Returns null for an
     *  empty outline. View.draw clips to this when clipToOutline is on. */
    public Path macapkClipPath() {
        if (mMode == MODE_PATH && mPath != null) return mPath;
        if (mMode == MODE_ROUND_RECT && !mRect.isEmpty()) {
            Path p = new Path();
            p.addRoundRect(mRect.left, mRect.top, mRect.right, mRect.bottom,
                           mRadius, mRadius, Path.Direction.CW);
            return p;
        }
        return null;
    }
}
