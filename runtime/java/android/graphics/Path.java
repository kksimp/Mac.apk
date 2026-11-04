// android.graphics.Path -- 2D path. Backed by a Java AWT Path2D.Float
// so Canvas.drawPath / Canvas.clipPath can route through Graphics2D.
//
// AOSP's Path supports a rich set of operations (cubic/conic Beziers,
// rRect, arc, op union/intersect, etc.). We implement the common
// move/line/quad/cubic/close/rect/arc/oval set; advanced ops can be
// added as games exercise them.
package android.graphics;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

public class Path {
    public enum Direction { CW, CCW }
    public enum FillType {
        WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD
    }

    // Use double-precision Path2D for stability across transforms; AWT
    // happily converts on the fly when drawn.
    private final Path2D.Double path = new Path2D.Double();
    private FillType fillType = FillType.WINDING;

    /**
     * AOSP's {@code public final long mNativePath} (frameworks/base/graphics/java/android/graphics/Path.java,
     * @UnsupportedAppUsage): the address of the native SkPath. Apps and their own JNI read it by name:
     * AndroidX's libandroidx.graphics.path.so resolves it with GetFieldID("mNativePath", "J") in JNI_OnLoad
     * on every API level, and a miss left a pending NoSuchFieldError that aborted System.loadLibrary (Play
     * Store's lyk.<clinit>, killing the main thread). This Path is a Java Path2D with no SkPath behind
     * it, so the handle is 0 (a null SkPath*). The only native reader of the field in that library is its
     * pre-API-34 iterator, which androidx selects with Build.VERSION.SDK_INT; at the API level we
     * present, Path.getPathIterator() (the platform iterator) is used instead.
     */
    public final long mNativePath = 0L;

    // --- AOSP's simple-path Region fast path: the STATE half -------------
    //
    // AOSP's BaseCanvas.drawPath does not always rasterize the path
    // (frameworks/base/graphics/java/android/graphics/BaseCanvas.java,
    // android11-release):
    //
    //     public void drawPath(@NonNull Path path, @NonNull Paint paint) {
    //         throwIfHasHwBitmapInSwMode(paint);
    //         if (path.isSimplePath && path.rects != null) {
    //             nDrawRegion(mNativeCanvasWrapper, path.rects.mNativeRegion,
    //                     paint.getNativeInstance());
    //         } else {
    //             nDrawPath(mNativeCanvasWrapper, path.readOnlyNI(),
    //                     paint.getNativeInstance());
    //         }
    //     }
    //
    // A Region is a SET of pixels, i.e. a union, so whenever that branch fires
    // THE FILL TYPE IS DISCARDED. Two nested same-direction rects fill SOLID
    // even under EVEN_ODD, because the region is their union rather than a
    // rasterized path. setFillType (AOSP Path.java:278-280) never touches
    // isSimplePath, which is precisely why the fill type gets dropped.
    //
    // MEASURED (docs/fwprobe-bitmap-raster-divergences_2026-08-10.md,
    // cluster 1), reference = emulator API 30:
    //     RAS.path.nest_cwcw_evenodd.ink     ref ink=400 (SOLID),  ours 336 (donut)
    //     RAS.path.overlap_cwcw_evenodd.ink  ref ink=448 (union),  ours 384 (hole)
    // and the control that settles it is RAS.path.inv_winding / inv_evenodd,
    // both ink=256 box=8,8,24,24 on the reference: a single addRect with an
    // INVERSE fill type paints the RECT, not its complement. No rasterizer does
    // that; a region union does.
    //
    // AOSP's own state machine (Path.java:52-58, 539-546):
    //
    //     public boolean isSimplePath = true;
    //     public Region rects;
    //     private Direction mLastDirection = null;
    //     private void detectSimplePath(float l, float t, float r, float b, Direction dir) {
    //         if (mLastDirection == null) { mLastDirection = dir; }
    //         if (mLastDirection != dir) { isSimplePath = false; }
    //         else {
    //             if (rects == null) rects = new Region();
    //             rects.op((int) l, (int) t, (int) r, (int) b, Region.Op.UNION);
    //         }
    //     }
    //
    // So the trigger is exactly one shape family: one or more addRect() calls,
    // ALL in the same Direction, with no other geometry verb and no close().
    // Every other mutator below clears the flag, which is why the mixed-
    // direction, star, triangle and unclosed cases must NOT move.
    //
    // ⚠ WHY `rects` IS A COUNT HERE AND NOT A REAL Region. AOSP accumulates an
    // SkRegion, a scanline-run structure whose union is cheap. OUR Region is
    // backed by java.awt.geom.Area (Region.java:145-155, `area.add(other.area)`),
    // where a union is a full constructive-area-geometry pass with heavy
    // allocation -- per addRect() call, on a path that custom views rebuild
    // every frame. Nothing in the tree reads Path.rects (it is @UnsupportedAppUsage
    // on a device, so apps cannot see it either), and the low-risk draw variant
    // below never needs the region's GEOMETRY, only the `!= null` predicate.
    // So we model the predicate exactly and skip the cost. If a future case ever
    // measures the region itself, materialize it here.
    public boolean isSimplePath = true;
    private int macapkAddRectCount = 0;
    private Direction macapkLastDirection = null;

    /** AOSP Path.detectSimplePath, called only from addRect(l,t,r,b,dir). */
    private void macapkDetectSimplePath(Direction dir) {
        if (macapkLastDirection == null) macapkLastDirection = dir;
        if (macapkLastDirection != dir) isSimplePath = false;
        else macapkAddRectCount++;
    }

    /** Every geometry verb that is NOT addRect clears the flag, matching the
     *  AOSP site list in Path.java (:380, :395, :410, :429, :447, :458, :509,
     *  close() :518, :590, :603, :626, :651, :684, :695, :705, addPath :715,
     *  :762, :777, :789, :799, and op() :195 which also nulls rects).
     *  Clearing restores the ordinary rasterize-the-path behaviour, so a verb
     *  we clear too eagerly can only ever be today's behaviour -- never a new
     *  one. That asymmetry is deliberate: it bounds the blast radius. */
    private void macapkNotSimple() { isSimplePath = false; }

    /** AOSP's `path.isSimplePath && path.rects != null` predicate, i.e. "this
     *  path was built only from same-direction addRect() calls, so a device
     *  would draw it as a Region and ignore its fill type". Read by
     *  Canvas.drawPath. */
    public boolean macapkIsRegionPath() {
        return isSimplePath && macapkAddRectCount > 0;
    }

    /** The Shape Canvas.drawPath should fill, honouring the Region fast path.
     *
     *  This is the LOW-RISK variant of the fix (option (b) in
     *  docs/fwprobe-bitmap-raster-divergences_2026-08-10.md cluster 1), chosen
     *  over literal AOSP deliberately: literal AOSP builds the region from
     *  `(int)`-TRUNCATED rect coordinates, so an app that adds fractional rects
     *  would start rendering on integer bounds. That is genuinely what Android
     *  does, but it is a behaviour change on a path we currently render
     *  sub-pixel-correctly and nothing in the fleet has been measured for it.
     *  For a set of same-direction rects a NON-ZERO fill IS their union, so
     *  swapping only the winding rule reproduces the reference answer for every
     *  measured case while leaving the float geometry untouched.
     *
     *  Note the cheap shape of this: when the fill type is already WINDING (the
     *  default, and the overwhelming majority of paths) the Path2D's winding
     *  rule is ALREADY WIND_NON_ZERO, so we return the live path and allocate
     *  nothing. Only an EVEN_ODD / INVERSE_EVEN_ODD path built purely from
     *  same-direction addRect()s takes the copy -- which is exactly the two
     *  divergent probe cases and nothing else in the battery.
     *
     *  Inverse fill types need no special handling: setFillType already maps
     *  INVERSE_* onto the non-inverse winding rule (we do not paint the
     *  complement), which is why inv_winding / inv_evenodd already match.
     *
     *  KNOWN RESIDUAL vs AOSP, both unmeasured by any case today: (1) no `(int)`
     *  truncation of the rect coordinates, so we stay MORE accurate than a
     *  device on a fractional addRect; (2) a STROKED simple path still strokes
     *  each rect's outline, where nDrawRegion would stroke the union boundary --
     *  a winding rule cannot express that, and swapping it changes fills only,
     *  so strokes are bit-identical to before this change. */
    public java.awt.Shape macapkDrawShape() {
        if (macapkIsRegionPath() && path.getWindingRule() != Path2D.WIND_NON_ZERO) {
            Path2D.Double union = new Path2D.Double(Path2D.WIND_NON_ZERO);
            union.append(path, false);
            return union;
        }
        return path;
    }

    public Path() {}
    public Path(Path src) {
        if (src != null) {
            path.append(src.path, false);
            // AOSP's copy-ctor (Path.java:77) copies isSimplePath + rects.
            isSimplePath = src.isSimplePath;
            macapkAddRectCount = src.macapkAddRectCount;
            macapkLastDirection = src.macapkLastDirection;
        }
    }

    /** AOSP reset() explicitly SAVES and RESTORES the fill type around the
     *  native call -- "We promised not to change this, so preserve it around
     *  the native call, which does now reset fill type" (Path.java) -- while
     *  rewind() goes straight through to SkPath::rewind, whose resetFields()
     *  sets fFillType back to kWinding. So the pair differ, and getting them
     *  the same way round costs a silently inverted fill. */
    public void reset()  { path.reset(); macapkResetSimplePath(); }

    /** Replace this path's geometry with an arbitrary AWT shape. Used by
     *  Paint.getFillPath, whose result comes out of BasicStroke as a Shape.
     *  Package-private -- not AOSP API. */
    void macapkSetShape(java.awt.Shape shape) {
        path.reset();
        macapkResetSimplePath();
        macapkNotSimple();
        if (shape != null) path.append(shape, false);
    }
    public void rewind() { path.reset(); setFillType(FillType.WINDING); macapkResetSimplePath(); }
    /** AOSP reset() (:91) and rewind() (:106) both restore isSimplePath = true
     *  and drop the accumulated rects. */
    private void macapkResetSimplePath() {
        isSimplePath = true;
        macapkAddRectCount = 0;
        macapkLastDirection = null;
    }
    public boolean isEmpty() {
        return path.getCurrentPoint() == null;
    }

    public FillType getFillType() { return fillType; }
    public void setFillType(FillType t) {
        if (t != null) {
            fillType = t;
            path.setWindingRule((t == FillType.EVEN_ODD ||
                                  t == FillType.INVERSE_EVEN_ODD)
                                 ? Path2D.WIND_EVEN_ODD
                                 : Path2D.WIND_NON_ZERO);
        }
    }
    public boolean isInverseFillType() {
        return fillType == FillType.INVERSE_WINDING ||
               fillType == FillType.INVERSE_EVEN_ODD;
    }
    public void toggleInverseFillType() {
        switch (fillType) {
            case WINDING:           fillType = FillType.INVERSE_WINDING; break;
            case EVEN_ODD:          fillType = FillType.INVERSE_EVEN_ODD; break;
            case INVERSE_WINDING:   fillType = FillType.WINDING; break;
            case INVERSE_EVEN_ODD:  fillType = FillType.EVEN_ODD; break;
        }
    }

    /** AOSP's segment verbs all carry the same sentence, e.g. Path.lineTo:
     *  "If no moveTo() call has been made for this contour, the first point is
     *  automatically set to (0,0)." (android-11.0.0_r48 Path.java:446-449.)
     *  Java2D instead THROWS java.awt.geom.IllegalPathStateException from
     *  Path2D.lineTo/quadTo/curveTo when there is no current point, so an app
     *  that used the implicit start -- perfectly legal on a device -- took an
     *  exception out of the middle of its draw pass instead of getting a shape.
     *  MEASURED (RAS.path.noMoveTo_fill): the emulator answers a filled triangle
     *  (n=2 colours), we answered THROW:java.awt.geom.IllegalPathStateException.
     *
     *  The relative forms carry the same sentence, and they used to SILENTLY
     *  DROP the segment instead -- same divergence, quieter. */
    private void macapkEnsureStart() {
        if (path.getCurrentPoint() == null) path.moveTo(0d, 0d);
    }

    public void moveTo(float x, float y)              { macapkNotSimple(); path.moveTo(x, y); }
    public void rMoveTo(float dx, float dy) {
        macapkNotSimple();
        java.awt.geom.Point2D p = path.getCurrentPoint();
        if (p == null) path.moveTo(dx, dy);
        else           path.moveTo(p.getX() + dx, p.getY() + dy);
    }
    public void lineTo(float x, float y)              { macapkNotSimple(); macapkEnsureStart(); path.lineTo(x, y); }
    public void rLineTo(float dx, float dy) {
        macapkNotSimple();
        macapkEnsureStart();
        java.awt.geom.Point2D p = path.getCurrentPoint();
        path.lineTo(p.getX() + dx, p.getY() + dy);
    }
    public void quadTo(float x1, float y1, float x2, float y2) {
        macapkNotSimple();
        macapkEnsureStart();
        path.quadTo(x1, y1, x2, y2);
    }
    public void rQuadTo(float dx1, float dy1, float dx2, float dy2) {
        macapkNotSimple();
        macapkEnsureStart();
        java.awt.geom.Point2D p = path.getCurrentPoint();
        double cx = p.getX(), cy = p.getY();
        path.quadTo(cx + dx1, cy + dy1, cx + dx2, cy + dy2);
    }
    public void cubicTo(float x1, float y1, float x2, float y2,
                          float x3, float y3) {
        macapkNotSimple();
        macapkEnsureStart();
        path.curveTo(x1, y1, x2, y2, x3, y3);
    }
    // Relative cubic bezier (control + end points relative to the current point).
    // Compose / Material draw rounded shapes + vector icons via rCubicTo; a
    // missing method threw NoSuchMethodError in rootView.draw. v1.0.429.
    public void rCubicTo(float dx1, float dy1, float dx2, float dy2, float dx3, float dy3) {
        macapkNotSimple();
        macapkEnsureStart();
        java.awt.geom.Point2D p = path.getCurrentPoint();
        double cx = p.getX(), cy = p.getY();
        path.curveTo(cx + dx1, cy + dy1, cx + dx2, cy + dy2, cx + dx3, cy + dy3);
    }
    /** ⚠ CLOSE ON AN EMPTY PATH IS A NO-OP, not an exception (v1.0.932).
     *
     *  `java.awt.geom.Path2D.closePath()` throws
     *  `IllegalPathStateException("missing initial moveto in path definition")`
     *  when no contour has been started. Skia's `SkPath::close()` does nothing
     *  in that case, and Android inherits that -- so this is the exact sibling
     *  of `macapkEnsureStart()` above (see the `RAS.path.noMoveTo_fill` probe
     *  case): AOSP's path verbs tolerate a missing contour and AWT's do not.
     *  `getCurrentPoint() == null` captures precisely the empty/post-reset case,
     *  and a double close stays safe because Path2D guards its own SEG_CLOSE.
     *
     *  THIS WAS BREAKING EVERY `MaterialShapeDrawable` IN THE FLEET. Material's
     *  `ShapeAppearancePathProvider.calculatePath` ends with
     *  `overlappedEdgePath.close()`, and that path is EMPTY for every ordinary
     *  shape -- so `MaterialShapeDrawable.draw()` threw on every frame, before
     *  painting a single fill pixel, and `View.draw`'s catch swallowed it into
     *  `macapkPaintMaterialChrome`. The throw happens before Material sets
     *  `pathDirty = false`, so it recurred forever rather than once.
     *
     *  Diagnosed by attaching jdb to a live run: the nav bar's active indicator
     *  had a perfectly healthy state machine (`isChecked=true`,
     *  `activeIndicatorProgress=1.0`, `alpha=1.0`, `scaleX=1.0`, animator null)
     *  and `MaterialShapeDrawable.draw` WAS being called each frame with
     *  `Rect(0,0-128,64)` and an opaque `fillPaint` -- and produced zero pixels.
     *  A headless harness against the app's own converted classes.jar reproduced
     *  the throw with the stack `Path.close &lt;- calculatePath:139 &lt;-
     *  calculateStrokePath:1212 &lt;- draw:988`, and with this guard painted
     *  7,416/8,192 px of the expected stadium.
     *
     *  So the bar and FAB only ever looked right because their class-name chrome
     *  branches painted approximations in the CATCH path; any MaterialShapeDrawable
     *  without such a branch -- chips, card surfaces, NavigationRail's indicator,
     *  MaterialButton shapes -- painted nothing at all. */
    public void close() {
        // AOSP close() (:518) clears isSimplePath, so a rect built by hand and
        // then closed does NOT take the Region fast path. Note addRect's own
        // closing happens on a private Path2D below, never through here.
        macapkNotSimple();
        if (path.getCurrentPoint() != null) path.closePath();
    }

    /** AOSP Path.computeBounds is `nComputeBounds` -> `SkPath::getBounds()`
     *  (frameworks/base libs/hwui/jni/Path.cpp:62-66), and SkPath's bounds are
     *  the bounds of every POINT IT STORES -- i.e. CONTROL-POINT bounds, which
     *  for a curve are conservative and can be much larger than the curve's
     *  own extent. `exact` is ignored on a device too: nComputeBounds takes no
     *  flag (Path.java:332-334), so both answers must agree.
     *
     *  ⚠ THIS CANNOT USE Path2D.getBounds2D(). On this JDK that returns TIGHT
     *  bounds (measured: moveTo(0,0);quadTo(50,100,100,0) answered
     *  0,0,100,50 where a device answers 0,0,100,100), so every rounded shape,
     *  every vector-drawable curve and every Compose path reported a smaller
     *  box than the app was told to expect -- and an app that sizes a bitmap or
     *  an invalidate rect from computeBounds then clips its own drawing. Walk
     *  the segments and take min/max over the raw coordinates instead.
     *  v1.0.429 (added), v1.0.923 (control-point semantics). */
    public void computeBounds(RectF bounds, boolean exact) {
        if (bounds == null) return;
        final double[] c = new double[6];
        float minX = 0, minY = 0, maxX = 0, maxY = 0;
        boolean have = false;
        for (java.awt.geom.PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            final int type = it.currentSegment(c);
            final int pts;
            switch (type) {
                case java.awt.geom.PathIterator.SEG_MOVETO:
                case java.awt.geom.PathIterator.SEG_LINETO:  pts = 1; break;
                case java.awt.geom.PathIterator.SEG_QUADTO:  pts = 2; break;
                case java.awt.geom.PathIterator.SEG_CUBICTO: pts = 3; break;
                default:                                     pts = 0; break;   // SEG_CLOSE
            }
            for (int i = 0; i < pts; i++) {
                float x = (float) c[i * 2], y = (float) c[i * 2 + 1];
                if (!have) { minX = maxX = x; minY = maxY = y; have = true; continue; }
                if (x < minX) minX = x; else if (x > maxX) maxX = x;
                if (y < minY) minY = y; else if (y > maxY) maxY = y;
            }
        }
        // An empty path has empty bounds, and SkRect::MakeEmpty is (0,0,0,0).
        bounds.set(have ? minX : 0f, have ? minY : 0f, have ? maxX : 0f, have ? maxY : 0f);
    }

    /** ⚠ THE DIRECTION IS LOAD-BEARING, and it used to be discarded outright.
     *
     *  AOSP: "Specifies how closed shapes (e.g. rects, ovals) are oriented when
     *  they are added to a path" -- Direction.CW / Direction.CCW
     *  (android-11.0.0_r48 Path.java:656-666, "must match enum in SkPath.h").
     *  Under the WINDING fill rule a contour's orientation is its SIGN, so two
     *  nested rects added CW+CCW have winding number 0 inside the inner one and
     *  fill as a DONUT, while CW+CW gives winding 2 and fills SOLID. Dropping
     *  the argument collapsed those two shapes into one.
     *
     *  MEASURED (RAS.path.nest_cwccw_winding, RAS.path.overlap_cwccw_winding):
     *  the emulator answers a hole -- ink 336 of 400 nested, 384 of 448
     *  overlapping -- and we answered the solid union both times.
     *
     *  Skia's SkPath::addRect starts at (l,t) for BOTH directions and then walks
     *  the corners one way or the other, so CW is (l,t) (r,t) (r,b) (l,b) and CCW
     *  is (l,t) (l,b) (r,b) (r,t). Rectangle2D's own path iterator emits exactly
     *  the CW order, so that branch is byte-identical to the previous behaviour
     *  and only CCW is new geometry.
     *
     *  KNOWN GAP, deliberately not fixed here: addOval / addCircle / addRoundRect
     *  still ignore their Direction. Reversing an Ellipse2D means reversing its
     *  four cubics, which is real work for a case no measurement covers yet --
     *  and unlike addRect it cannot be written down as four literal corners. */
    public void addRect(float l, float t, float r, float b, Direction d) {
        // THE one verb that KEEPS isSimplePath. AOSP's addRect calls
        // detectSimplePath first (Path.java:539-546), and a Direction different
        // from the previous addRect's clears the flag -- which is what keeps
        // nest_cwccw_* / overlap_cwccw_* on the ordinary rasterizer.
        macapkDetectSimplePath(d);
        if (d == Direction.CCW) {
            Path2D.Double c = new Path2D.Double();
            c.moveTo(l, t);
            c.lineTo(l, b);
            c.lineTo(r, b);
            c.lineTo(r, t);
            c.closePath();
            path.append(c, false);
            return;
        }
        path.append(new Rectangle2D.Double(l, t, r - l, b - t), false);
    }
    public void addRect(RectF rect, Direction d) {
        if (rect != null) addRect(rect.left, rect.top, rect.right, rect.bottom, d);
    }
    public void addOval(float l, float t, float r, float b, Direction d) {
        macapkNotSimple();
        path.append(new java.awt.geom.Ellipse2D.Double(l, t, r - l, b - t), false);
    }
    public void addOval(RectF rect, Direction d) {
        if (rect != null) addOval(rect.left, rect.top, rect.right, rect.bottom, d);
    }
    public void addCircle(float cx, float cy, float radius, Direction d) {
        macapkNotSimple();
        path.append(new java.awt.geom.Ellipse2D.Double(
                       cx - radius, cy - radius, 2 * radius, 2 * radius), false);
    }
    /** v1.0.124: AOSP `Path.addRoundRect` -- rounded rectangle to path.
     *  Compose UI's drawing path uses this for card / FAB shapes. We
     *  approximate via Java2D RoundRectangle2D. */
    public void addRoundRect(RectF rect, float rx, float ry, Direction d) {
        if (rect == null) return;
        macapkNotSimple();
        path.append(new java.awt.geom.RoundRectangle2D.Double(
            rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top,
            2 * rx, 2 * ry), false);
    }
    public void addRoundRect(float l, float t, float r, float b, float rx, float ry, Direction d) {
        macapkNotSimple();
        path.append(new java.awt.geom.RoundRectangle2D.Double(
            l, t, r - l, b - t, 2 * rx, 2 * ry), false);
    }
    /** Per-corner radii overload (8-arg float array: [topLeftX, topLeftY,
     *  topRightX, topRightY, bottomRightX, bottomRightY, bottomLeftX,
     *  bottomLeftY]). We average to a single radius for simplicity. */
    // v1.0.433: real per-corner radii (was averaged to a scalar). Material3's
    // whole shape system uses asymmetric corners (top-rounded bottom sheets,
    // cards with two rounded corners), so averaging produced visibly wrong
    // corners on a large fraction of Material surfaces. radii =
    // [tlX,tlY, trX,trY, brX,brY, blX,blY]; corners use quad curves (close to
    // circular, no AWT per-corner round-rect primitive).
    public void addRoundRect(RectF rect, float[] radii, Direction d) {
        if (rect == null || radii == null || radii.length < 8) return;
        appendPerCornerRoundRect(rect.left, rect.top, rect.right, rect.bottom, radii);
    }
    public void addRoundRect(float l, float t, float r, float b, float[] radii, Direction d) {
        if (radii == null || radii.length < 8) return;
        appendPerCornerRoundRect(l, t, r, b, radii);
    }
    private void appendPerCornerRoundRect(float l, float t, float r, float b, float[] rad) {
        macapkNotSimple();
        float tlx = rad[0], tly = rad[1], trx = rad[2], tryy = rad[3];
        float brx = rad[4], bry = rad[5], blx = rad[6], bly = rad[7];
        Path2D.Double pp = new Path2D.Double();
        pp.moveTo(l + tlx, t);
        pp.lineTo(r - trx, t);
        pp.quadTo(r, t, r, t + tryy);
        pp.lineTo(r, b - bry);
        pp.quadTo(r, b, r - brx, b);
        pp.lineTo(l + blx, b);
        pp.quadTo(l, b, l, b - bly);
        pp.lineTo(l, t + tly);
        pp.quadTo(l, t, l + tlx, t);
        pp.closePath();
        path.append(pp, false);
    }
    public void addArc(RectF oval, float startAngle, float sweepAngle) {
        if (oval == null) return;
        macapkNotSimple();
        path.append(new java.awt.geom.Arc2D.Double(
                       oval.left, oval.top, oval.width(), oval.height(),
                       -startAngle, -sweepAngle, java.awt.geom.Arc2D.OPEN),
                     false);
    }
    public void arcTo(RectF oval, float startAngle, float sweepAngle) {
        // AOSP arcTo(oval,start,sweep) == arcTo(...,forceMoveTo=false): the arc
        // CONNECTS to the current contour (a lineTo+arcTo chain stays joined --
        // progress indicators, donut/speedometer charts, custom shapes).
        arcTo(oval, startAngle, sweepAngle, false);
    }
    // v1.0.433: honor forceMoveTo (was ignored -> arc subpaths never connected).
    public void arcTo(RectF oval, float startAngle, float sweepAngle, boolean forceMoveTo) {
        if (oval == null) return;
        macapkNotSimple();
        java.awt.geom.Arc2D.Double arc = new java.awt.geom.Arc2D.Double(
            oval.left, oval.top, oval.width(), oval.height(),
            -startAngle, -sweepAngle, java.awt.geom.Arc2D.OPEN);
        path.append(arc, !forceMoveTo);
    }
    public void arcTo(float left, float top, float right, float bottom,
                      float startAngle, float sweepAngle, boolean forceMoveTo) {
        arcTo(new RectF(left, top, right, bottom), startAngle, sweepAngle, forceMoveTo);
    }
    public void addArc(float left, float top, float right, float bottom,
                       float startAngle, float sweepAngle) {
        addArc(new RectF(left, top, right, bottom), startAngle, sweepAngle);
    }
    public void addPath(Path src) {
        if (src == null) return;
        macapkNotSimple();                       // AOSP addPath (:715) clears it
        path.append(src.path, false);
    }
    public void addPath(Path src, float dx, float dy) {
        if (src == null) return;
        macapkNotSimple();
        Path2D.Double t = new Path2D.Double();
        t.append(src.path, false);
        t.transform(java.awt.geom.AffineTransform.getTranslateInstance(dx, dy));
        path.append(t, false);
    }
    /** AOSP offset() (:743-750) is the one transform that can KEEP the fast
     *  path: it offsets the accumulated rects by the same amount, which is only
     *  exact for an INTEGER translate, so a fractional one clears the flag. */
    public void offset(float dx, float dy) {
        if (dx != (int) dx || dy != (int) dy) macapkNotSimple();
        path.transform(java.awt.geom.AffineTransform.getTranslateInstance(dx, dy));
    }
    public void transform(Matrix matrix) {
        macapkNotSimple();
        if (matrix != null) path.transform(matrix.macapkToAffine());
    }
    public void transform(Matrix matrix, Path dst) {
        if (dst == null) return;
        dst.path.reset();
        dst.macapkResetSimplePath();
        dst.macapkNotSimple();
        dst.path.append(path, false);
        if (matrix != null) dst.path.transform(matrix.macapkToAffine());
    }

    // v1.0.426 Tier-2: real boolean path ops. AOSP Path.op() combines two paths
    // (union/intersect/difference/xor) -- used for compound clip shapes and custom
    // drawable outlines. Backed by java.awt.geom.Area (constructive area geometry),
    // so this is a faithful impl, not a stub.
    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }

    public boolean op(Path path, Op op) { return op(this, path, op); }
    public boolean op(Path path1, Path path2, Op op) {
        if (path1 == null || path2 == null) return false;
        macapkNotSimple();                   // AOSP op() (:195) also nulls rects
        macapkAddRectCount = 0;
        try {
            java.awt.geom.Area a = new java.awt.geom.Area(path1.path);
            java.awt.geom.Area b = new java.awt.geom.Area(path2.path);
            switch (op) {
                case UNION:              a.add(b); break;
                case INTERSECT:          a.intersect(b); break;
                case DIFFERENCE:         a.subtract(b); break;
                case XOR:                a.exclusiveOr(b); break;
                case REVERSE_DIFFERENCE: { java.awt.geom.Area t = new java.awt.geom.Area(b); t.subtract(a); a = t; break; }
            }
            this.path.reset();
            this.path.append(a, false);
            return true;
        } catch (Throwable t) { return false; }
    }

    // ================================================================
    // v1.0.433 surface completion -- AOSP Path methods that were missing
    // (each a NoSuchMethodError that aborts the draw pass -> blank screen,
    // per docs/canvas-draw-surface-diff).
    // ================================================================

    /** Copy another path's geometry + fill type into this one. Compose's
     *  AndroidPath / Drawable / AVD code copies paths via set() constantly; a
     *  missing method here was the single highest-risk Path gap. */
    public void set(Path src) {
        path.reset();
        macapkResetSimplePath();
        if (src != null) {
            path.append(src.path, false);
            setFillType(src.fillType);
            // AOSP set(Path) (:118) copies isSimplePath + rects from the source.
            isSimplePath = src.isSimplePath;
            macapkAddRectCount = src.macapkAddRectCount;
            macapkLastDirection = src.macapkLastDirection;
        }
    }

    // conic (rational quadratic) -- no AWT conic primitive; approximate as a
    // plain quad (exact at weight==1; close for the near-1 weights Compose emits
    // when lowering circular arcs / rounded outlines).
    public void conicTo(float x1, float y1, float x2, float y2, float weight) {
        quadTo(x1, y1, x2, y2);
    }
    public void rConicTo(float dx1, float dy1, float dx2, float dy2, float weight) {
        java.awt.geom.Point2D p = path.getCurrentPoint();
        if (p == null) return;
        double cx = p.getX(), cy = p.getY();
        quadTo((float) (cx + dx1), (float) (cy + dy1), (float) (cx + dx2), (float) (cy + dy2));
    }

    public void addPath(Path src, Matrix matrix) {
        if (src == null) return;
        macapkNotSimple();
        Path2D.Double t = new Path2D.Double();
        t.append(src.path, false);
        if (matrix != null) t.transform(matrix.macapkToAffine());
        path.append(t, false);
    }
    public void offset(float dx, float dy, Path dst) {
        if (dst == null) { offset(dx, dy); return; }
        dst.path.reset();
        dst.macapkResetSimplePath();
        dst.path.append(path, false);
        dst.path.transform(java.awt.geom.AffineTransform.getTranslateInstance(dx, dy));
        dst.setFillType(fillType);
        // Same integer-translate rule as the in-place offset above.
        dst.isSimplePath = isSimplePath && dx == (int) dx && dy == (int) dy;
        dst.macapkAddRectCount = macapkAddRectCount;
        dst.macapkLastDirection = macapkLastDirection;
    }

    /** Single-arg bounds (delegates to the 2-arg form). */
    public void computeBounds(RectF bounds) { computeBounds(bounds, true); }

    /** Monotone-ish invalidation key: a value hash of the current geometry +
     *  fill type, so Compose/graphics caches keyed on it invalidate iff the path
     *  actually changed. O(n) per call (called rarely, at cache lookups). */
    public int getGenerationId() {
        int h = 1;
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            h = 31 * h + type;
            for (int i = 0; i < 6; i++) h = 31 * h + Float.floatToIntBits((float) c[i]);
        }
        return 31 * h + fillType.ordinal();
    }

    /** Flattened approximation as [fraction, x, y] triples (AOSP contract), used
     *  by androidx path-morph / PathParser tooling. Fractions are cumulative arc
     *  length normalized to [0,1]. */
    public float[] approximate(float acceptableError) {
        double flat = Math.max(0.1, acceptableError <= 0 ? 0.5 : acceptableError);
        java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = path.getPathIterator(null, flat); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_CLOSE && !pts.isEmpty()) {
                float[] first = pts.get(0);
                pts.add(new float[]{ first[0], first[1] });
            } else {
                pts.add(new float[]{ (float) c[0], (float) c[1] });
            }
        }
        if (pts.isEmpty()) return new float[]{ 0f, 0f, 0f };
        if (pts.size() == 1) {
            float[] p0 = pts.get(0);
            return new float[]{ 0f, p0[0], p0[1] };
        }
        float total = 0f;
        float[] seg = new float[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            float dx = pts.get(i)[0] - pts.get(i - 1)[0];
            float dy = pts.get(i)[1] - pts.get(i - 1)[1];
            seg[i] = (float) Math.hypot(dx, dy);
            total += seg[i];
        }
        float[] out = new float[pts.size() * 3];
        float acc = 0f;
        for (int i = 0; i < pts.size(); i++) {
            acc += seg[i];
            out[i * 3]     = (total > 0) ? acc / total : (float) i / (pts.size() - 1);
            out[i * 3 + 1] = pts.get(i)[0];
            out[i * 3 + 2] = pts.get(i)[1];
        }
        return out;
    }

    // Cheap safety stubs that just need to EXIST (correct-safe defaults):
    public void incReserve(int extraPtCount) {}

    /** SkPath::setLastPt: replace the path's most recent POINT in place (and
     *  moveTo when the path is empty). This was a no-op stub, which is
     *  invisible in every log and every stack trace and shows up only as a
     *  shape whose last vertex never moved -- e.g. a drag handle or a
     *  live-drawn stroke that refuses to follow the finger. Path2D has no
     *  in-place point editor, so the path is rebuilt with the terminal
     *  coordinate of the last point-bearing segment replaced. */
    public void setLastPoint(float dx, float dy) {
        macapkNotSimple();                  // rebuilds the geometry point by point
        final double[] c = new double[6];
        final java.util.List<int[]> types = new java.util.ArrayList<>();
        final java.util.List<double[]> coords = new java.util.ArrayList<>();
        int lastWithPoint = -1;
        for (java.awt.geom.PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            types.add(new int[] { t });
            coords.add(new double[] { c[0], c[1], c[2], c[3], c[4], c[5] });
            if (t != java.awt.geom.PathIterator.SEG_CLOSE) lastWithPoint = types.size() - 1;
        }
        if (lastWithPoint < 0) { path.reset(); path.moveTo(dx, dy); return; }
        final int lt = types.get(lastWithPoint)[0];
        final double[] lc = coords.get(lastWithPoint);
        final int endPair = (lt == java.awt.geom.PathIterator.SEG_CUBICTO) ? 2
                          : (lt == java.awt.geom.PathIterator.SEG_QUADTO) ? 1 : 0;
        lc[endPair * 2] = dx; lc[endPair * 2 + 1] = dy;
        path.reset();
        for (int i = 0; i < types.size(); i++) {
            final double[] p = coords.get(i);
            switch (types.get(i)[0]) {
                case java.awt.geom.PathIterator.SEG_MOVETO:  path.moveTo(p[0], p[1]); break;
                case java.awt.geom.PathIterator.SEG_LINETO:  path.lineTo(p[0], p[1]); break;
                case java.awt.geom.PathIterator.SEG_QUADTO:  path.quadTo(p[0], p[1], p[2], p[3]); break;
                case java.awt.geom.PathIterator.SEG_CUBICTO: path.curveTo(p[0], p[1], p[2], p[3], p[4], p[5]); break;
                default:                                     path.closePath(); break;
            }
        }
    }
    /** AOSP: "Returns true if the path specifies a rectangle. If so, and if rect
     *  is not null, set rect to the bounds of the path." (Path.java:535-544.)
     *  The old body was a hardcoded `false` justified as "always the safe answer;
     *  the only cost is forgoing a fast rectangular-clip path" -- but it is an
     *  ANSWER an app can read, not just an internal hint, and it was wrong for
     *  the single most common path an app builds. MEASURED (RAS.sc.path.isRect):
     *  addRect(8,8,24,24,CW) then isRect(null) answers 1 on the emulator, 0 here.
     *
     *  Accepted shapes, matching what SkPath::isRect recognises: exactly one
     *  subpath, made only of lines, whose four distinct corners alternate
     *  horizontal and vertical edges and return to the start (explicitly with
     *  close(), or by a fifth point equal to the first). Zero-length segments are
     *  skipped rather than disqualifying, since a rect built by hand often
     *  repeats a corner. Any curve, any second subpath, or any non-axis-aligned
     *  edge answers false. */
    public boolean isRect(RectF rect) {
        final double[] c = new double[6];
        final double[] xs = new double[6];
        final double[] ys = new double[6];
        int n = 0;
        boolean started = false, closed = false;
        double px = 0d, py = 0d;
        for (java.awt.geom.PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                if (started) return macapkFillIsRectOut(rect, false);   // a second subpath
                started = true;
                xs[0] = px = c[0]; ys[0] = py = c[1];
                n = 1;
            } else if (t == java.awt.geom.PathIterator.SEG_LINETO) {
                if (!started || closed) return macapkFillIsRectOut(rect, false);
                if (c[0] == px && c[1] == py) continue;   // zero-length edge
                if (n >= 5) return macapkFillIsRectOut(rect, false);
                xs[n] = px = c[0]; ys[n] = py = c[1];
                n++;
            } else if (t == java.awt.geom.PathIterator.SEG_CLOSE) {
                if (closed) return macapkFillIsRectOut(rect, false);
                closed = true;
            } else {
                return macapkFillIsRectOut(rect, false);                // quad / cubic
            }
        }
        if (n == 5) {
            // An explicit final edge back to the start closes the contour.
            if (xs[4] != xs[0] || ys[4] != ys[0]) return macapkFillIsRectOut(rect, false);
            closed = true;
            n = 4;
        }
        // ⚠ An UNCLOSED four-line rect is still a rect to Skia: IsRectContour
        // accepts a contour whose implicit closing edge completes the shape,
        // so `closed` is deliberately NOT required here. MEASURED
        // (GEO.PATH.manualRectOpen.isRect.ret): the emulator answers 1.
        if (!started || n != 4) return macapkFillIsRectOut(rect, false);
        boolean vFirst = (xs[0] == xs[1]) && (ys[1] == ys[2])
                      && (xs[2] == xs[3]) && (ys[3] == ys[0]);
        boolean hFirst = (ys[0] == ys[1]) && (xs[1] == xs[2])
                      && (ys[2] == ys[3]) && (xs[3] == xs[0]);
        if (!vFirst && !hFirst) return macapkFillIsRectOut(rect, false);
        if (rect != null) {
            RectF b = new RectF();
            computeBounds(b, true);
            rect.set(b.left, b.top, b.right, b.bottom);
        }
        return true;
    }
    /** The JNI writes the out-rect UNCONDITIONALLY, not only when the answer is
     *  true: `SkRect rect; jboolean result = obj->isRect(&rect); if (jrect)
     *  GraphicsJNI::rect_to_jrectf(rect, env, jrect);` (libs/hwui/jni/Path.cpp
     *  :464-472). That local is an uninitialized C++ stack slot, and the
     *  reference device produced (0,0,0,0) for every false case, so zeroing is
     *  what an app actually observes. Leaving the caller's buffer untouched --
     *  which is what this did -- is the one answer that is definitely wrong. */
    private static boolean macapkFillIsRectOut(RectF rect, boolean result) {
        if (rect != null) rect.set(0f, 0f, 0f, 0f);
        return result;
    }

    /** SkPath::isConvex. This used to answer a blanket `false`, justified as
     *  "safe for shadow/fill algos" -- but it is an ANSWER an app reads, and it
     *  was wrong for a rectangle, a triangle and a single curve, i.e. for most
     *  shapes anyone actually builds.
     *
     *  Mirrors the two decisive rules of Skia's Convexicator
     *  (SkPath::internalGetConvexity): MORE THAN ONE CONTOUR is immediately
     *  concave, and within one contour the cross product of consecutive edge
     *  vectors must not change sign, with at most two direction reversals (a
     *  degenerate "there and back" sliver is still convex). Curves contribute
     *  their CONTROL POINTS, exactly as Skia's iterator feeds pts[1..count]
     *  into the state machine -- so convexity is decided on the control
     *  polygon, not on the flattened curve. */
    public boolean isConvex() {
        final double[] c = new double[6];
        final java.util.ArrayList<double[]> pts = new java.util.ArrayList<>();
        int contours = 0;
        for (java.awt.geom.PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            final int type = it.currentSegment(c);
            final int n;
            switch (type) {
                case java.awt.geom.PathIterator.SEG_MOVETO:
                    if (++contours > 1) return false;
                    n = 1; break;
                case java.awt.geom.PathIterator.SEG_LINETO:  n = 1; break;
                case java.awt.geom.PathIterator.SEG_QUADTO:  n = 2; break;
                case java.awt.geom.PathIterator.SEG_CUBICTO: n = 3; break;
                default:                                     n = 0; break;   // SEG_CLOSE
            }
            for (int i = 0; i < n; i++) pts.add(new double[] { c[i * 2], c[i * 2 + 1] });
        }
        // Drop consecutive duplicates; Skia's addPt returns early on them.
        final java.util.ArrayList<double[]> v = new java.util.ArrayList<>();
        for (double[] p : pts) {
            if (v.isEmpty() || v.get(v.size() - 1)[0] != p[0] || v.get(v.size() - 1)[1] != p[1]) v.add(p);
        }
        while (v.size() > 1) {   // the contour is force-closed, so drop a repeated first point
            double[] last = v.get(v.size() - 1);
            if (last[0] == v.get(0)[0] && last[1] == v.get(0)[1]) v.remove(v.size() - 1); else break;
        }
        if (v.size() < 3) return true;   // empty, a point, or a single segment
        int sign = 0, reversals = 0;
        final int count = v.size();
        for (int i = 0; i < count; i++) {
            double[] a = v.get(i);
            double[] b = v.get((i + 1) % count);
            double[] d = v.get((i + 2) % count);
            double ux = b[0] - a[0], uy = b[1] - a[1];
            double vx = d[0] - b[0], vy = d[1] - b[1];
            double cross = ux * vy - uy * vx;
            if (cross != 0) {
                int s = cross > 0 ? 1 : -1;
                if (sign == 0) sign = s; else if (s != sign) return false;
            } else if (ux * vx + uy * vy < 0) {
                if (++reversals > 2) return false;
            }
        }
        return true;
    }

    /** Internal hook used by Canvas to fetch the AWT Shape. */
    public java.awt.Shape macapkShape() { return path; }
    /** Internal: append an arbitrary AWT shape (Region.getBoundaryPath). */
    public void macapkAppendShape(java.awt.Shape s) {
        if (s == null) return;
        macapkNotSimple();     // arbitrary geometry: never the addRect-only shape
        path.append(s, false);
    }

    /** AOSP Path.getPathIterator (API 34): iterate the path's verbs/points. */
    public android.graphics.PathIterator getPathIterator() { return new android.graphics.PathIterator(this); }

    // ---- AOSP Path.isInterpolatable / Path.interpolate (API 34) ----
    //
    // Both are thin JNI wrappers over Skia (frameworks/base/libs/hwui/jni/Path.cpp:220
    // -> SkPath::interpolate). Skia's src/core/SkPath_interpolate.cpp defines:
    //
    //     CanInterpolate(from,to): from.points().size()==to.points().size()
    //                              && verbs equal && conicWeights equal
    //     SkPath::interpolate(ending, weight, out):
    //             Interpolate(*this, ending, 1 - weight)
    //     Interpolate(from,to,t): newPoint = (1-t)*fromPoint + t*toPoint
    //
    // ⚠ SO THE PARAMETER IS INVERTED RELATIVE TO THE ANDROID JAVADOC. The javadoc
    // on Path.interpolate says "a value of 0 results in a Path equivalent to this
    // path, a value of 1 ... to otherPath". The CODE does the opposite: the JNI
    // passes t straight through as Skia's `weight`, and Skia lerps by (1 - weight),
    // so t == 1 gives THIS path and t == 0 gives otherPath. We mirror the OBSERVABLE
    // behaviour (the rule for this project), not the doc comment.
    //
    // Our Path is Path2D-backed, so "verbs" are AWT segment types and there are no
    // conics (conicTo already lowers to quads), which makes the conic-weight half of
    // CanInterpolate vacuously true.

    /** Segment types of this path, in order, as an int list. */
    private java.util.List<Integer> macapkVerbs() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<Integer>();
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            out.add(Integer.valueOf(it.currentSegment(c)));
        }
        return out;
    }

    /** AOSP Path.isInterpolatable(Path) -- true iff the two paths have exactly the
     *  same structure (same verbs in the same order, hence the same point count). */
    public boolean isInterpolatable(Path otherPath) {
        if (otherPath == null) return false;
        return macapkVerbs().equals(otherPath.macapkVerbs());
    }

    /** AOSP Path.interpolate(Path,float,Path). See the block comment above for the
     *  inverted-t contract. Returns false (leaving interpolatedPath untouched) when
     *  the structures do not match, which is what Skia's interpolate does. */
    public boolean interpolate(Path otherPath, float t, Path interpolatedPath) {
        if (otherPath == null || interpolatedPath == null) return false;
        if (!isInterpolatable(otherPath)) return false;
        // Skia: Interpolate(from=this, to=other, 1 - t), newPoint = (1-u)*from + u*to
        final double u = 1.0 - t;
        interpolatedPath.reset();
        // "The result has the same fillType as the 'from' path."
        interpolatedPath.setFillType(getFillType());
        double[] a = new double[6];
        double[] b = new double[6];
        java.awt.geom.PathIterator ia = path.getPathIterator(null);
        java.awt.geom.PathIterator ib = otherPath.path.getPathIterator(null);
        double[] m = new double[6];
        while (!ia.isDone() && !ib.isDone()) {
            int seg = ia.currentSegment(a);
            ib.currentSegment(b);
            int n;
            switch (seg) {
                case java.awt.geom.PathIterator.SEG_MOVETO:  n = 2; break;
                case java.awt.geom.PathIterator.SEG_LINETO:  n = 2; break;
                case java.awt.geom.PathIterator.SEG_QUADTO:  n = 4; break;
                case java.awt.geom.PathIterator.SEG_CUBICTO: n = 6; break;
                default:                                     n = 0; break;
            }
            for (int i = 0; i < n; ++i) m[i] = a[i] * (1.0 - u) + b[i] * u;
            switch (seg) {
                case java.awt.geom.PathIterator.SEG_MOVETO:
                    interpolatedPath.moveTo((float) m[0], (float) m[1]); break;
                case java.awt.geom.PathIterator.SEG_LINETO:
                    interpolatedPath.lineTo((float) m[0], (float) m[1]); break;
                case java.awt.geom.PathIterator.SEG_QUADTO:
                    interpolatedPath.quadTo((float) m[0], (float) m[1], (float) m[2], (float) m[3]); break;
                case java.awt.geom.PathIterator.SEG_CUBICTO:
                    interpolatedPath.cubicTo((float) m[0], (float) m[1], (float) m[2], (float) m[3],
                                             (float) m[4], (float) m[5]); break;
                default:
                    interpolatedPath.close(); break;
            }
            ia.next(); ib.next();
        }
        return true;
    }

}
