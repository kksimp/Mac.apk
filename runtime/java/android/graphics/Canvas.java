// android.graphics.Canvas -- 2D drawing target backed by a Java AWT
// Graphics2D. Renders into a Bitmap that owns a BufferedImage; the
// SurfaceView path lifts the bitmap to the Metal layer via texture
// upload on unlockCanvasAndPost.
//
// History: prior to v1.0.50 this was a complete stub (all draw methods
// were `{}`). Frozen Bubble, Sgt Puzzles, ShatteredPD, and many other
// Canvas-based apps were stuck on a black screen because their
// per-frame `holder.lockCanvas() ... draw* ... unlockCanvasAndPost`
// loop drew into a void. Real implementation routes through the
// Bitmap's AWT BufferedImage so the existing Cocos2dxBitmap text-
// rasterization path keeps working unchanged.
package android.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

public class Canvas {
    /** AOSP Canvas.ALL_SAVE_FLAG (0x1F) -- the only saveLayer flag value that
     *  still has meaning; every other CLIP_*_SAVE_FLAG was removed in API 28.
     *  saveLayer(..., int saveFlags) callers pass it by name. */
    public static final int ALL_SAVE_FLAG = 0x1F;

    // Target bitmap (may be null if drawing-to-void or pre-setBitmap).
    private Bitmap bitmap;
    private BufferedImage image;       // == bitmap.macapkAwtImage() when present
    private Graphics2D g;              // current graphics context

    // Transform stack for save() / restore(). Stores AffineTransform
    // snapshots; we can extend to clip + paint state later as apps
    // exercise those paths.
    private final Deque<AffineTransform> txStack = new ArrayDeque<>();
    // v1.0.121: parallel clip stack -- one entry per outstanding save().
    // Java2D's `Graphics2D.getClip()` returns the active Shape; we
    // snapshot it on save() and restore it on restore(). Without this,
    // a clipRect(0,0,childW,childH) issued before child.draw() would
    // leak into the next sibling's draw because Graphics2D.clip is
    // *additive intersection* (each subsequent clip() narrows the
    // region; only setClip() replaces it outright).
    //
    // ArrayDeque rejects null elements (NPE at addFirst). A fresh
    // Graphics2D returns null from getClip() ("no clip set"). We
    // encode that as the singleton sentinel NO_CLIP, swapped back to
    // null on restore(). Java2D's setClip(null) is the correct
    // "clear the user clip" call per Graphics.setClip javadoc.
    private static final java.awt.Shape NO_CLIP = new java.awt.Rectangle(
        Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2,
        Integer.MAX_VALUE,     Integer.MAX_VALUE);
    private final Deque<java.awt.Shape> clipStack = new ArrayDeque<>();

    // Tier 3 M9 (alpha-in-draw): parallel LAYER stack -- one entry per
    // outstanding save(), so it stays in lockstep with txStack/clipStack and
    // restoreToCount()'s counting is unchanged. A plain save() pushes the
    // NO_LAYER sentinel; saveLayerAlpha()/saveLayer() push a real Layer that
    // redirects subsequent draws into an offscreen BufferedImage and, on the
    // matching restore(), composites that layer back onto its parent with the
    // requested alpha (AlphaComposite SRC_OVER). This is AOSP Canvas.save-
    // LayerAlpha's group-opacity semantics: the whole subtree drawn between
    // save and restore fades as ONE unit (no double-blending where the child
    // overdraws itself), exactly what View.draw() relies on for an alpha < 1
    // view whose hasOverlappingRendering() is true (the base-View default).
    private static final class Layer {
        BufferedImage parentImage; // image to composite back onto
        Graphics2D    parentG;     // parent graphics context
        Graphics2D    layerG;      // the layer's own graphics (to dispose)
        BufferedImage layerImage;  // the offscreen layer drawn into
        float         alpha;       // 0..1 composite alpha
        int           awtRule = AlphaComposite.SRC_OVER; // PorterDuff composite rule (Lottie mattes)
        java.awt.Shape clipDev; // effective device-space saveLayer clip, including parent clipping
        // MACAPK_FRAMESPEED_LAYER (see LayerPool below): the device rect outside
        // which this layer provably holds only transparent pixels (null = the
        // gate is off: composite the whole layer, as before), and the pool entry
        // the layer image came from (null = allocated the pre-change way).
        java.awt.Rectangle inkDev;
        LayerPool.Entry    pooled;
    }
    private static final Layer NO_LAYER = new Layer();

    // ---- FRAMESPEED: saveLayer cost at full-screen windows (2026-09-26) ----
    //
    // Every saveLayer / saveLayerAlpha allocated a CANVAS-SIZED offscreen and,
    // on restore, composited the WHOLE of it back SrcOver. That is the right
    // SHAPE (the layer keeps the parent's coordinates, so everything drawn into
    // it rasterizes exactly as it would on the parent), but its cost is the
    // canvas area, not the child's. ViewGroup.dispatchDraw pushes one for every
    // child with 0 < alpha < 1 (a fade, a disabled view, a ViewPropertyAnimator
    // alpha), so at the 3360x1796 window each such child cost a fresh 24 MB
    // int[] (zeroed, then garbage for SerialGC) plus a 6.03 Mpx blend. MEASURED
    // on a bare JVM against the built framework jar (debug/framespeed_bench):
    // a 600x200 alpha child with one round rect took 7.47 ms with the layer and
    // 0.19 ms without it.
    //
    // Two facts make a smaller cost EXACT, with no change to what is drawn:
    //  1. Nothing reaches a layer's pixels except through its Graphics2D, and
    //     every such write is confined to that Graphics2D's clip, which starts
    //     as the parent's clip intersected with the saveLayer bounds and can
    //     only NARROW inside the layer (clipRect/clipPath intersect,
    //     clipOutRect/clipOutPath subtract, restore() pops back to it; no op in
    //     this file widens a clip, and the only unclipped write,
    //     macapkFillWholeCanvas's fast clear, is taken only on the bitmap's own
    //     image, never on a layer). A nested layer's composite onto this one
    //     writes transparent over transparent outside its own ink. So outside
    //     the device bounding box of the layer's INITIAL clip every pixel is
    //     still the 0x00000000 it was allocated with.
    //  2. Java2D's SrcOver blit (with or without an extra alpha) leaves the
    //     destination untouched wherever the source alpha is 0 -- it skips the
    //     pixel. So compositing only that box is identical to compositing the
    //     whole layer.
    // So a SrcOver layer is composited through a clip of that box (inkDev), and
    // its image is recycled through a small pool whose entries remember the box
    // they may have dirtied and zero exactly that on reuse -- which leaves the
    // image all-zero, i.e. identical to a fresh allocation. A non-SrcOver layer
    // (a Lottie matte) keeps its clipDev composite exactly as before: DST_IN and
    // friends CHANGE the destination where the source is transparent, so its
    // composite region must not shrink; only its allocation is pooled.
    //
    // Proof: debug/framespeed_gate.sh (layer.*), gate ON vs the pre-change path
    // on the same drawing programs, byte for byte, with a positive control whose
    // box is one pixel too small. MACAPK_FRAMESPEED_LAYER=0 restores the
    // canvas-sized allocation and the full composite exactly.
    static final boolean FRAMESPEED_LAYER =
        !"0".equals(System.getenv("MACAPK_FRAMESPEED_LAYER"));
    /** Recycled layer images, keyed by size. A layer is canvas-sized, so in
     *  practice there is one size per canvas (the window frame, and each
     *  RenderNode recording). Bounded: at most PER_SIZE free images per size and
     *  max(MAX_PIXELS, PER_SIZE images of the size being returned) in total --
     *  MAX_PIXELS is 2 full-screen frames at 3360x1796, and the second term keeps
     *  a LARGER window (a 5K display's 5120x2880 is 14.7 Mpx, more than
     *  MAX_PIXELS for one image) poolable instead of silently never pooling;
     *  beyond that an image is simply dropped for the GC, which is what every
     *  layer used to be. The references are soft, so the GC can still take an
     *  idle pool back under pressure. */
    static final class LayerPool {
        static final int PER_SIZE = 2;
        static final long MAX_PIXELS = 2L * 3360 * 1796;
        static final class Entry {
            final BufferedImage img;
            final int[] data;          // img's backing array (stock TYPE_INT_ARGB)
            int dx0, dy0, dx1, dy1;    // the only rect that may hold non-zero pixels
            Entry(BufferedImage img, int[] data) { this.img = img; this.data = data; }
        }
        // SOFT references: an idle pool is memory the GC may take back under
        // pressure (a cleared slot is simply skipped), so an app that used a
        // fade once does not pin 24 MB images for the rest of its life.
        private static final java.util.ArrayList<java.lang.ref.SoftReference<Entry>> FREE =
            new java.util.ArrayList<>();
        /** A zeroed w x h TYPE_INT_ARGB image, from the pool when one is free. */
        static Entry acquire(int w, int h) {
            Entry e = null;
            synchronized (FREE) {
                for (int i = FREE.size() - 1; i >= 0; i--) {
                    Entry c = FREE.get(i).get();
                    if (c == null) { FREE.remove(i); continue; }
                    if (c.img.getWidth() == w && c.img.getHeight() == h) {
                        FREE.remove(i);
                        e = c;
                        break;
                    }
                }
            }
            if (e == null) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] data = stockArgbData(img);
                if (data == null) return null;   // never for a fresh TYPE_INT_ARGB
                return new Entry(img, data);
            }
            sFsLayersReused++;
            // Zero exactly the rect the last user may have written; everything
            // else is still zero from allocation or from an earlier clear.
            for (int y = e.dy0; y < e.dy1; y++)
                java.util.Arrays.fill(e.data, y * w + e.dx0, y * w + e.dx1, 0);
            e.dx0 = e.dy0 = e.dx1 = e.dy1 = 0;
            return e;
        }
        /** Give an image back; `ink` bounds every pixel the layer may have written. */
        static void release(Entry e, java.awt.Rectangle ink) {
            if (e == null) return;
            final int w = e.img.getWidth(), h = e.img.getHeight();
            if (ink == null) { e.dx0 = 0; e.dy0 = 0; e.dx1 = w; e.dy1 = h; }
            else {
                e.dx0 = Math.max(0, ink.x);  e.dx1 = Math.min(w, ink.x + ink.width);
                e.dy0 = Math.max(0, ink.y);  e.dy1 = Math.min(h, ink.y + ink.height);
                if (e.dx1 <= e.dx0 || e.dy1 <= e.dy0) e.dx0 = e.dy0 = e.dx1 = e.dy1 = 0;
            }
            synchronized (FREE) {
                int same = 0;
                long pixels = 0;
                for (int i = FREE.size() - 1; i >= 0; i--) {
                    Entry c = FREE.get(i).get();
                    if (c == null) { FREE.remove(i); continue; }
                    pixels += (long) c.img.getWidth() * c.img.getHeight();
                    if (c.img.getWidth() == w && c.img.getHeight() == h) same++;
                }
                if (same >= PER_SIZE
                        || pixels + (long) w * h > Math.max(MAX_PIXELS, (long) PER_SIZE * w * h)) return;
                FREE.add(new java.lang.ref.SoftReference<>(e));
            }
        }
    }
    /** Device-space bounding box, grown by 2 px and clamped to the image, of the
     *  clip `lg` starts a layer with: a superset of every pixel the layer can
     *  receive (fact 1 above). The clip's user-space bounds are mapped through
     *  lg's own transform, so the box is right under any CTM; the 2 px absorb
     *  Java2D's clip rounding (Region.clipRound) and floating-point noise. A
     *  null clip, or any non-finite value, answers the whole image. */
    private static java.awt.Rectangle macapkLayerInkBounds(Graphics2D lg, int w, int h) {
        try {
            Shape clip = lg.getClip();
            if (clip != null) {
                Rectangle2D ub = clip.getBounds2D();
                Rectangle2D db = lg.getTransform().createTransformedShape(ub).getBounds2D();
                double lx = db.getMinX(), ly = db.getMinY(), hx = db.getMaxX(), hy = db.getMaxY();
                if (Double.isFinite(lx) && Double.isFinite(ly) && Double.isFinite(hx) && Double.isFinite(hy)) {
                    int x0 = (int) Math.max(0, Math.min(w, Math.floor(lx) - 2));
                    int y0 = (int) Math.max(0, Math.min(h, Math.floor(ly) - 2));
                    int x1 = (int) Math.max(x0, Math.min(w, Math.ceil(hx) + 2));
                    int y1 = (int) Math.max(y0, Math.min(h, Math.ceil(hy) + 2));
                    return new java.awt.Rectangle(x0, y0, x1 - x0, y1 - y0);
                }
            }
        } catch (Throwable ignored) { }
        return new java.awt.Rectangle(0, 0, w, h);
    }
    // DIAGNOSTIC for the gate's positive control only: shrink every ink box by
    // this many pixels on each side. A box that is too small must lose pixels,
    // or the gate cannot see a wrong box. Never set outside the gate.
    static final int FRAMESPEED_LAYER_SHRINK_FOR_TEST = macapkEnvInt(
        "MACAPK_FRAMESPEED_LAYER_SHRINK_FOR_TEST", 0);
    private static int macapkEnvInt(String k, int def) {
        try {
            String v = System.getenv(k);
            return (v == null || v.isEmpty()) ? def : Integer.parseInt(v.trim());
        } catch (Throwable t) { return def; }
    }
    private final Deque<Layer> layerStack = new ArrayDeque<>();

    // Diagnostic (MACAPK_RENDERNODE_TRACE): count primitive draw ops so we
    // can tell whether Compose's leaf draws reach a RecordingCanvas.
    public int macapkDrawOps = 0;
    // Global draw-op counter across ALL Canvas instances (frame canvas + every
    // RenderNode recording canvas). ViewCanvasDriver reads the per-frame delta
    // to verify whether a Compose screen issues ANY draw calls -- a Compose
    // content that composes but never draws (e.g. Reddit's blank login) shows
    // zero delta even though the View tree measures + draws.
    public static volatile long sGlobalDrawOps = 0;
    // Per-type global counters (diagnostic): text/bitmap/path are the content
    // ops (buttons, images, vector icons); a Compose screen that composes but
    // never issues these draws an empty UI.
    public static volatile long sGlobalText = 0, sGlobalBitmap = 0, sGlobalPath = 0, sGlobalRoundRect = 0;
    // drawRect is the load-bearing op for solid-color fills (a Surface/Box
    // background, a Compose .background() modifier). Counted separately so a
    // RenderNode recording that fills its surface color is distinguishable
    // from one that drew genuinely nothing (the latter is the Reddit blank).
    public static volatile long sGlobalRect = 0;

    // EVERY PIXEL-WRITING PRIMITIVE IS INK (2026-09-25).
    //
    // macapkDrawOps is no longer only a diagnostic: ViewCanvasDriver's
    // present-elision gate (`elide = !sFrameDrewInk && !sLastPublishedInk`)
    // reads its delta across rootView.draw() as "did this frame put anything
    // on the canvas". A primitive that writes pixels without bumping it makes
    // a frame that DID draw look empty, and once two such frames follow each
    // other the overlay layer freezes on whatever was published last.
    //
    // Measured on Candy Crush (fleet A/B 2026-09-24): com.king.core.SplashView
    // draws only through drawBitmap(Bitmap, Rect, RectF, Paint), which never
    // counted. Frame #1 was published solely because sLastPublishedInk starts
    // true; every later present was elided, including the empty frames after
    // "Splash screen removed", so the King splash sat over a GL title screen
    // that was rendering RICH underneath for the whole run.
    //
    // The sites that used to be missing are the three remaining drawBitmap
    // overloads (Rect/Rect, and through it Rect/RectF; Matrix), the int[]
    // colour blits, the drawColor/drawARGB/drawRGB whole-canvas fills and
    // punchHole (which changes the frame from the plain clear just as surely as
    // ink does). An op that draws nothing (drawMesh, which has no programmable
    // pipeline here) still does not count, so an empty native-EGL tree still
    // elides, which is the case the elision exists for.
    //
    // Bisect gate, default ON: MACAPK_COUNT_ALL_INK=0 restores the old count
    // (only drawBitmap(x,y), drawVertices, applyPaint and drawRenderNode), so a
    // present-rate or hitch change can be attributed to this in one A/B.
    private static final boolean MACAPK_COUNT_ALL_INK =
        !"0".equals(System.getenv("MACAPK_COUNT_ALL_INK"));
    /** Count one ink op on a site that predates the all-primitives rule. */
    private void macapkMarkInk() {
        // The generation bump comes FIRST and is not under the ink gate: which
        // sites COUNT for the present-elision heuristic is a separate question
        // from whether this canvas's bitmap was written.
        macapkGenBump();
        if (!MACAPK_COUNT_ALL_INK) return;
        macapkDrawOps++; sGlobalDrawOps++;
    }
    /** P1 (Bitmap.getGenerationId): a draw op on this canvas modifies its
     *  bitmap. Skia dirties the pixel ref's generation ID on EVERY raster draw,
     *  before it knows whether the op lands anywhere (SkBitmapDevice.cpp:74,
     *  :193, :299-301, android14-release), so this runs at the same ink sites
     *  as the op counter, whatever the clip. Inside a saveLayer the pixels
     *  reach `bitmap` at the restore, which is still a modification of it.
     *  One volatile store; MACAPK_BITMAP_GENID=0 makes it nothing. */
    private void macapkGenBump() {
        final Bitmap b = bitmap;
        if (b != null) b.macapkNotifyPixelsChanged();
    }

    public Canvas() { /* no bitmap; draw* calls become no-ops */ }
    public Canvas(Bitmap b) { setBitmap(b); }

    public int getWidth()  { return bitmap != null ? bitmap.getWidth()  : 0; }
    public int getHeight() { return bitmap != null ? bitmap.getHeight() : 0; }
    /** AOSP `Canvas.isHardwareAccelerated()`. On a real device the
     *  app window's draw canvas IS hardware accelerated; we report true
     *  to match that (the backing happens to be a BufferedImage, but the
     *  app shouldn't care). This is load-bearing for modern Jetpack
     *  Compose: GraphicsLayer/RenderNodeLayer records its content into a
     *  RenderNode and composites it back with `canvas.drawRenderNode(node)`
     *  ONLY when the destination canvas reports hardware acceleration.
     *  Reporting false made Compose record the whole UI into a RenderNode
     *  and then NEVER composite it (the software fallback for a V29
     *  RenderNode layer can't replay a RenderNode), so Reddit's Compose
     *  login screen recorded 836x1652 of content and drew nothing. Now
     *  that Canvas.drawRenderNode is implemented (replays the recorded
     *  offscreen bitmap with the node's transform/clip/alpha), reporting
     *  true completes the record->replay chain. v1.0.124 set this false
     *  because drawRenderNode didn't exist yet; that's no longer true. */
    public boolean isHardwareAccelerated() { return true; }
    /** AOSP `Canvas.getDensity` -- in DPI per inch. Used by some
     *  drawing paths to scale stroke widths / text sizes. Default
     *  unscaled = 160 (the AOSP "medium DPI" baseline). */
    public int getDensity() { return 160; }
    public void setDensity(int d) {}

    public void setBitmap(Bitmap b) {
        this.bitmap = b;
        if (b == null) { image = null; g = null; return; }
        image = b.macapkAwtImage();
        if (image == null) {
            // Lazily upgrade to canvas-backed if the caller wants to
            // draw into a plain Bitmap. The lifecycle here matches
            // AOSP's "Bitmap must be mutable" rule loosely; we accept
            // any Bitmap and back it as needed.
            int w = b.getWidth(), h = b.getHeight();
            if (w > 0 && h > 0) {
                // SEED FROM THE BITMAP'S EXISTING PIXELS. AOSP's Canvas(Bitmap)
                // ctor WRAPS the bitmap's pixel storage (nInitRaster) and never
                // clears it -- the "decode an image, wrap it in a Canvas, stamp
                // something on top" idiom depends on that, and it is common.
                //
                // We used to install a BLANK BufferedImage here, which silently
                // ERASED any bitmap whose pixels live in argbBuffer -- i.e.
                // every BitmapFactory-decoded bitmap, since macapkFinishDecode
                // populates argbBuffer and leaves awtImage null, and both
                // Bitmap.copy and createBitmap(Bitmap) preserve that shape. The
                // app then drew onto a blank surface and its base image was
                // gone, with no error anywhere. Found via tobsolitaire, whose
                // white label chip (decode -> copy -> new Canvas -> drawText)
                // vanished leaving only the text and its white shadow halo.
                //
                // ⚠ ORDER IS LOAD-BEARING: read macapkArgbBuffer() BEFORE
                // macapkSetAwtImage. Once an awtImage is installed the getter
                // re-extracts FROM it (Bitmap.macapkArgbBuffer), so reading
                // afterwards hands back the blank pixels just written -- and
                // zeroes argbBuffer as a side effect.
                int[] existing = b.macapkArgbBuffer();
                image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                if (existing != null && existing.length >= w * h) {
                    image.setRGB(0, 0, w, h, existing, 0, w);
                }
                b.macapkSetAwtImage(image);
            }
        }
        g = (image != null) ? image.createGraphics() : null;
        macapkInitHints(g);
    }

    /** The rendering hints every Graphics2D this Canvas owns starts with --
     *  the real canvas in setBitmap and every offscreen layer in pushLayer, so
     *  a draw cannot rasterize differently depending on whether it happens to
     *  be inside a saveLayer.
     *
     *  KEY_ANTIALIASING starts ON only as a default; applyPaint sets it from
     *  the Paint on every draw (both directions -- see the note there).
     *
     *  ⚠ KEY_STROKE_CONTROL = VALUE_STROKE_PURE IS LOAD-BEARING, and it is not
     *  the Java2D default. The default (VALUE_STROKE_NORMALIZE) rounds stroked
     *  geometry to the nearest PIXEL CENTRE before rasterizing, i.e. it adds
     *  (+0.5, +0.5) to integer coordinates, "to improve uniformity or spacing
     *  of lines and overall aesthetics" (RenderingHints.VALUE_STROKE_NORMALIZE
     *  javadoc). Skia does no such thing: it rasterizes the stroke outline
     *  where the app put it. The displacement is a WHOLE HALF PIXEL and it
     *  moved every stroked edge in the framework down and to the right.
     *
     *  MEASURED against the emulator answer-differential (battery RAS,
     *  2026-08-09), drawLine(4,16,28,16) with strokeWidth 4:
     *      reference  ink=96  box=4,14,28,18   (rows 14..17, cols 4..27)
     *      ours       ink=125 box=4,14,29,19   (5 rows x 25 cols)
     *  125 = 5*25 is exactly what a band of y in [14.5,18.5] x [4.5,28.5]
     *  produces once antialiasing feathers the two half-covered edge rows and
     *  columns -- the normalized geometry, not ours. Every stroked RAS case
     *  (rect.stroke2/stroke4, path.closed_stroke4, line.*, pstate.dash_*,
     *  pstate.join_*, point.p_w4_square) carried the same +1 px on the right
     *  and bottom of its ink box. VALUE_STROKE_PURE is the hint that says
     *  "geometry should be left unmodified and rendered with sub-pixel
     *  accuracy", which is Skia's contract.
     *
     *  drawLine's own comment used to predict this: "If anything ever sets
     *  VALUE_STROKE_PURE, this becomes visible everywhere." It does, and the
     *  visible result is the AOSP-correct one. FILLS were never normalized
     *  (measured: drawRect(8.25,...) keeps its 0.75 edge coverage), so this
     *  hint only moves stroked geometry. */
    private static void macapkInitHints(Graphics2D gg) {
        if (gg == null) return;
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                            RenderingHints.VALUE_STROKE_PURE);
        // Fractional text metrics, matching the measure side exactly. Without
        // it the DRAW quantises each glyph advance to a whole pixel while
        // Paint.measureText sums float advances, so a wrap computed to fit runs
        // ~3% wider than the ink that lands -- see MacApkFont.FRACTIONAL_METRICS
        // for why the two must never be flipped separately.
        MacApkFont.applyTextMetricsHint(gg);
    }

    // --- Save / restore ----------------------------------------------
    //
    // AOSP semantics (per android.graphics.Canvas javadoc):
    //   - getSaveCount() returns 1 on a fresh canvas; each save() bumps
    //     it by 1, each restore() drops it by 1.
    //   - save() returns "the value to pass to restoreToCount() to
    //     balance this save()". I.e. the pre-save count.
    //   - restoreToCount(N) pops until saveCount == N.
    //
    // Our backing stack `txStack` carries one entry per outstanding
    // save: stack size = saveCount - 1. So a fresh canvas has size 0,
    // and we report getSaveCount() = txStack.size() + 1.
    //
    // The old code returned txStack.size() AFTER push (= 1, 2, 3 ...)
    // -- numerically same as AOSP -- but `restoreToCount(N)` used `>`
    // instead of `>=`, so the SAVE that produced N never got popped.
    // The transform stack grew unbounded, and every ViewGroup.dispatch-
    // Draw iteration accumulated its child's translate on top of all
    // previous children's. Practical effect: in a 5-column grid like
    // F-Droid's Calculator, child #1 paints at its real X, child #2
    // paints at X1+X2, child #3 at X1+X2+X3, etc -- everything past
    // the first off-axis child renders off-bitmap. Visually: only one
    // button per ViewGroup row was ever visible.
    //
    // Single-character fix (`>` -> `>=` in restoreToCount), plus an
    // explicit saveCount mirror so getSaveCount() returns 1 on a
    // fresh canvas to match AOSP exactly.
    public int save() {
        if (g != null) {
            txStack.push(new AffineTransform(g.getTransform()));
            // v1.0.121: snapshot clip alongside transform. Env-gated --
            // turning off via MACAPK_SAVE_CLIP=0 reverts to the
            // pre-v121 behavior (save only transform). Useful as an
            // emergency rollback if the clip save/restore introduces
            // a regression for any APK's draw pattern.
            if (SAVE_CLIP) {
                java.awt.Shape c = g.getClip();
                clipStack.push(c != null ? c : NO_CLIP);
            }
            // M9: keep the layer stack in lockstep. A plain save() never
            // introduces a layer, so push the sentinel.
            layerStack.push(NO_LAYER);
        }
        return txStack.size();
    }
    public void restore() {
        if (g != null && !txStack.isEmpty()) {
            // M9: if this save introduced an offscreen layer, composite it
            // back onto its parent (with the layer's alpha) BEFORE restoring
            // the parent's transform/clip from the stacks below. After this
            // branch `g`/`image` point back at the parent so the stack pops
            // act on the parent context, exactly as in the no-layer case.
            Layer layer = layerStack.isEmpty() ? NO_LAYER : layerStack.pop();
            if (layer != NO_LAYER) {
                layer.layerG.dispose();
                Graphics2D pg = layer.parentG;
                image = layer.parentImage;
                g = pg;
                java.awt.Composite oldComp = pg.getComposite();
                // Full-size blit: the layer is canvas-sized and already carries
                // the child's transform + clip baked into its pixels, so paint
                // it 1:1 under identity transform / no clip. SRC_OVER * alpha
                // only touches the pixels the child actually drew (the rest of
                // the layer is transparent), which is the group-opacity result.
                pg.setTransform(new AffineTransform());
                // v1.0.497: a non-SRC_OVER PorterDuff layer (Lottie matte:
                // DST_IN/DST_OUT/...) MUST be clipped to the saveLayer bounds.
                // DST_IN clears the destination everywhere the source (matte) is
                // transparent; our layer is full-canvas, so an unclipped DST_IN
                // would wipe the whole background outside the matte (turned
                // Duolingo's screen black). Clipping to the device-space
                // saveLayer rect keeps the mask inside the owl and leaves the
                // background untouched. SRC_OVER layers stay unclipped (the
                // transparent rest of the layer is a no-op for SRC_OVER).
                // MACAPK_FRAMESPEED_LAYER: a SrcOver layer composites through its
                // ink box instead of the whole canvas -- identical, because the
                // layer is transparent outside it and SrcOver skips transparent
                // source pixels (see LayerPool's header). A matte keeps clipDev.
                // Only while the clip stack restores pg's clip right after this
                // (SAVE_CLIP, the default): under MACAPK_SAVE_CLIP=0 the composite's
                // clip is what the parent keeps drawing with, and the pre-change
                // path left it null.
                pg.setClip(layer.awtRule != AlphaComposite.SRC_OVER ? layer.clipDev
                           : (SAVE_CLIP ? layer.inkDev : null));
                pg.setComposite(AlphaComposite.getInstance(
                        layer.awtRule, Math.max(0f, Math.min(1f, layer.alpha))));
                pg.drawImage(layer.layerImage, 0, 0, null);
                pg.setComposite(oldComp);
                // The layer's pixels are consumed; recycle its image. The ink box
                // bounds what it may have written whatever the composite rule was.
                if (layer.pooled != null) {
                    LayerPool.release(layer.pooled, layer.inkDev);
                    layer.pooled = null;
                }
                // transform + clip are restored from the stacks just below.
            }
            g.setTransform(txStack.pop());
            if (SAVE_CLIP && !clipStack.isEmpty()) {
                java.awt.Shape c = clipStack.pop();
                g.setClip(c == NO_CLIP ? null : c);
            }
        }
    }
    private static final boolean SAVE_CLIP =
        !"0".equals(System.getenv("MACAPK_SAVE_CLIP"));
    public int  getSaveCount() { return txStack.size() + 1; }
    public void restoreToCount(int saveCount) {
        // ⚠ TWO GUARDS, both needed to avoid an INFINITE LOOP.
        // save() only pushes when `g != null`, so on a Canvas with no backing
        // Graphics2D the stack stays EMPTY while save() still returns 0 -- and
        // `0 >= 0` is true forever, with restore() unable to make progress. Any
        // draw path that save()s, hits a null-g Canvas and then restores would
        // hang the UI thread with no exception and no log. VectorDrawable.draw
        // and ImageView both have that exposure.
        // AOSP additionally documents saveCount < 1 as illegal; we return
        // rather than throw, because a hang is the worse failure and this is a
        // cleanup path callers run in `finally`.
        if (saveCount < 1) return;
        while (!txStack.isEmpty() && txStack.size() >= saveCount) restore();
    }

    // --- Offscreen layers (group opacity) ----------------------------
    //
    // AOSP Canvas.saveLayerAlpha / saveLayer. Behaves like save() but
    // redirects every subsequent draw into a transparent offscreen layer;
    // the matching restore()/restoreToCount() composites that layer back onto
    // the parent with the requested alpha. View.draw() uses this for a view
    // with 0 < alpha < 1 whose hasOverlappingRendering() is true (the base-
    // View default), so the whole subtree fades as ONE unit instead of per-
    // primitive (which double-blends where the view overdraws itself).
    public int saveLayerAlpha(float l, float t, float r, float b, int alpha) {
        // The bounds now reach pushLayer so the layer is CLIPPED to them, as
        // SkCanvas::saveLayer does. They used to be dropped on the floor here
        // (and the RectF form did not even forward them), so a layer whose
        // bounds were smaller than the draw let the overflow survive restore.
        return pushLayer(Math.max(0, Math.min(255, alpha)) / 255f,
                         AlphaComposite.SRC_OVER, l, t, r, b);
    }
    public int saveLayerAlpha(RectF bounds, int alpha) {
        if (bounds == null) return saveLayerAlpha(0, 0, 0, 0, alpha);
        return saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha);
    }
    /** AOSP deprecated overload carrying explicit save flags; we honor none of
     *  them (ALL_SAVE_FLAG is the only modern value) and layer regardless. */
    public int saveLayerAlpha(float l, float t, float r, float b, int alpha, int saveFlags) {
        return saveLayerAlpha(l, t, r, b, alpha);
    }
    /** AOSP keeps all four saveLayerAlpha forms (2 coordinate shapes x with and
     *  without the deprecated saveFlags). Java resolves a call site on the exact
     *  descriptor, so an APK compiled against the RectF+flags form does NOT fall
     *  through to the RectF form -- the missing sibling is a NoSuchMethodError
     *  that aborts the whole draw pass at that saveLayer. */
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        return saveLayerAlpha(bounds, alpha);
    }
    /** AOSP Canvas.saveLayer(bounds, paint): the layer alpha comes from the
     *  paint's alpha (null paint == fully opaque == a plain save). The paint's
     *  PorterDuff xfermode (if any) selects the composite RULE blended back at
     *  restore() -- SRC_OVER ordinarily, DST_IN/DST_OUT/SRC_IN/... for Lottie
     *  mattes/masks. A non-SRC_OVER rule REQUIRES the saveLayer bounds (so the
     *  composite is clipped to the matte region, not the whole canvas); when the
     *  bounds are missing/degenerate it falls back to SRC_OVER. (color-filter
     *  still ignored.) */
    public int saveLayer(float l, float t, float r, float b, Paint paint) {
        int a = (paint != null) ? paint.getAlpha() : 255;
        // v1.0.500: a saveLayer() with explicit bounds + a paint FORCES a real
        // offscreen (AOSP always allocates one). The SRC_OVER-at-full-alpha
        // degenerate-to-save() optimization breaks Lottie MATTES: BaseLayer.draw
        // does saveLayer(contentPaint) then clearCanvas() (PorterDuff CLEAR) to
        // wipe the layer before drawing into it. If the content layer degenerates
        // to a plain save(), that CLEAR erases the FRAME under the owl (turning
        // it transparent -> presents BLACK) instead of clearing a private
        // offscreen. Forcing the content layer to a real offscreen makes
        // clearCanvas wipe only that offscreen; on restore it composites
        // SRC_OVER and leaves the rest of the frame intact. saveLayerAlpha()
        // (View group-opacity, no clearCanvas) keeps the optimization.
        boolean forceLayer = (r > l && b > t);
        return pushLayer(Math.max(0, Math.min(255, a)) / 255f, awtRuleForPaint(paint), l, t, r, b, forceLayer);
    }
    public int saveLayer(RectF bounds, Paint paint) {
        if (bounds == null) return saveLayer(0, 0, 0, 0, paint);
        return saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
    }
    public int saveLayer(float l, float t, float r, float b, Paint paint, int saveFlags) {
        return saveLayer(l, t, r, b, paint);
    }
    /** The RectF+saveFlags sibling. See saveLayerAlpha(RectF,int,int): the
     *  descriptor must exist independently of the RectF form. */
    public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
        return saveLayer(bounds, paint);
    }

    /** Shared layer setup. Alpha >= 1 (or no graphics) degenerates to a plain
     *  save() -- under SRC_OVER an opaque layer is pixel-identical to drawing
     *  straight onto the parent, so we skip the offscreen allocation. */
    private int pushLayer(float alpha01, int awtRule, float l, float t, float r, float b) {
        return pushLayer(alpha01, awtRule, l, t, r, b, false);
    }
    /** Layer setup with an explicit Porter-Duff rule. Non-SrcOver rules require an offscreen
     *  even at full alpha; restore clips their composite to the intersection of the saveLayer
     *  bounds, the parent clip and the device so a foreground mask cannot erase unrelated UI. */
    private int pushLayer(float alpha01, int awtRule, float l, float t, float r, float b, boolean forceLayer) {
        if (g == null || image == null) return save();
        java.awt.Shape clipDev = null;
        if (awtRule != AlphaComposite.SRC_OVER) {
            java.awt.geom.Area region = new java.awt.geom.Area(
                new java.awt.Rectangle(0, 0, image.getWidth(), image.getHeight()));
            if (r > l && b > t) {
                region.intersect(new java.awt.geom.Area(g.getTransform().createTransformedShape(
                    new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t))));
            }
            java.awt.Shape parentClip = g.getClip();
            if (parentClip != null) {
                region.intersect(new java.awt.geom.Area(g.getTransform().createTransformedShape(parentClip)));
            }
            clipDev = region;
        }
        // Ordinary SRC_OVER-at-full-alpha degenerates to a plain save() (a real
        // matte never does, because awtRule != SRC_OVER keeps it off this path)
        // UNLESS forceLayer (a saveLayer(paint)-with-bounds content layer that
        // may be cleared/matted -- see saveLayer above).
        if (alpha01 >= 1f && awtRule == AlphaComposite.SRC_OVER && !forceLayer) return save();
        // 1. Snapshot transform + clip exactly as save() does, keeping the
        //    three stacks in lockstep for restoreToCount().
        txStack.push(new AffineTransform(g.getTransform()));
        if (SAVE_CLIP) {
            java.awt.Shape c = g.getClip();
            clipStack.push(c != null ? c : NO_CLIP);
        }
        // 2. Allocate the offscreen layer; inherit the parent's transform + clip
        //    so the child draws into it identically to the real canvas.
        //    MACAPK_FRAMESPEED_LAYER: from the pool, zeroed (see LayerPool).
        LayerPool.Entry pooled = FRAMESPEED_LAYER
                ? LayerPool.acquire(image.getWidth(), image.getHeight()) : null;
        BufferedImage li = (pooled != null) ? pooled.img : new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = li.createGraphics();
        macapkInitHints(lg);
        lg.setTransform(g.getTransform());
        lg.setClip(g.getClip());
        // AOSP/Skia: saveLayer's bounds are "a hint... the layer is clipped to
        // them" -- SkCanvas::saveLayer intersects the clip with the requested
        // bounds, so content drawn OUTSIDE the layer rect is discarded rather
        // than surviving the restore. The bounds are in USER space at the moment
        // of the saveLayer, which is why this clips through lg (already carrying
        // the parent's CTM) instead of pre-transforming.
        // MEASURED (RAS.clip.saveLayer_bounded): saveLayer(RectF(8,8,24,24))
        // then drawRect(0,0,32,32) -- reference ink=256 box=8,8,24,24, ours
        // ink=1024 box=0,0,32,32 because the layer was full-canvas and unclipped.
        // Degenerate bounds (the 0,0,0,0 form used when a caller passes none)
        // must NOT clip, or every bounds-less layer would become empty.
        if (r > l && b > t) {
            lg.clip(new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t));
        }
        Layer layer = new Layer();
        layer.parentImage = image;
        layer.parentG     = g;
        layer.layerG      = lg;
        layer.layerImage  = li;
        layer.alpha       = alpha01;
        layer.awtRule     = awtRule;
        layer.clipDev     = clipDev;
        layer.pooled      = pooled;
        if (FRAMESPEED_LAYER) {
            java.awt.Rectangle ink = macapkLayerInkBounds(lg, li.getWidth(), li.getHeight());
            final int k = FRAMESPEED_LAYER_SHRINK_FOR_TEST;
            if (k > 0) ink = new java.awt.Rectangle(ink.x + k, ink.y + k,
                    Math.max(0, ink.width - 2 * k), Math.max(0, ink.height - 2 * k));
            layer.inkDev = ink;
            if (pooled != null) sFsLayersPooled++;
            if (awtRule == AlphaComposite.SRC_OVER) sFsLayersBoxed++;
        }
        layerStack.push(layer);
        // 3. Redirect drawing to the layer.
        image = li;
        g = lg;
        return txStack.size();
    }

    /** Porter-Duff rule used when restoring a saved layer. Modern BlendMode takes precedence
     *  over the legacy Xfermode, exactly as it does for primitive/bitmap draws. */
    private static int awtRuleForPaint(Paint paint) {
        if (paint == null) return AlphaComposite.SRC_OVER;
        try {
            android.graphics.BlendMode blendMode = paint.getBlendMode();
            android.graphics.PorterDuff.Mode m;
            if (blendMode != null) {
                m = blendMode.toPorterDuffModeOrNull();
            } else {
                android.graphics.Xfermode xm = paint.getXfermode();
                m = xm instanceof android.graphics.PorterDuffXfermode
                    ? ((android.graphics.PorterDuffXfermode) xm).getMode() : null;
            }
            if (m != null) {
                switch (m) {
                    case CLEAR:    return AlphaComposite.CLEAR;
                    case SRC:      return AlphaComposite.SRC;
                    case DST:      return AlphaComposite.DST;
                    case SRC_OVER: return AlphaComposite.SRC_OVER;
                    case DST_OVER: return AlphaComposite.DST_OVER;
                    case SRC_IN:   return AlphaComposite.SRC_IN;
                    case DST_IN:   return AlphaComposite.DST_IN;
                    case SRC_OUT:  return AlphaComposite.SRC_OUT;
                    case DST_OUT:  return AlphaComposite.DST_OUT;
                    case SRC_ATOP: return AlphaComposite.SRC_ATOP;
                    case DST_ATOP: return AlphaComposite.DST_ATOP;
                    case XOR:      return AlphaComposite.XOR;
                    default:       return AlphaComposite.SRC_OVER;
                }
            }
        } catch (Throwable ignored) {}
        return AlphaComposite.SRC_OVER;
    }

    // --- Transforms ---------------------------------------------------
    public void translate(float dx, float dy) { if (g != null) g.translate(dx, dy); }
    public void scale(float sx, float sy)     { if (g != null) g.scale(sx, sy); }
    /** AOSP pivot variant: scale about (px, py). Same contract as
     *  translate(px, py); scale(sx, sy); translate(-px, -py).
     *  Calculator's pad press-animation draw path uses this. */
    public void scale(float sx, float sy, float px, float py) {
        if (g == null) return;
        g.translate(px, py);
        g.scale(sx, sy);
        g.translate(-px, -py);
    }
    /** v1.0.100: diagnostic -- read current AffineTransform translation
     *  components so callers can correlate local draw coords with the
     *  absolute screen position. tx is the AffineTransform.m02 (tx)
     *  in g.getTransform(); ty is m12. Returns -1f when no graphics
     *  context is available (off-screen canvas). */
    public float macapkGetCurrentTx() {
        if (g == null) return -1f;
        return (float) g.getTransform().getTranslateX();
    }
    public float macapkGetCurrentTy() {
        if (g == null) return -1f;
        return (float) g.getTransform().getTranslateY();
    }
    /** Diagnostic -- everything about the canvas state that decides whether a
     *  draw op produces visible pixels, in one line. Callers gate it behind
     *  their own trace env var; this method itself is never called on a hot
     *  path.
     *
     *  Exists because macapkGetCurrentTx/Ty above are NOT sufficient to judge
     *  "where did this land", and reading them alone has already misled one
     *  investigation: they report the CTM's m02/m12 only, so a concat() or a
     *  scale() is invisible in them, and a translation of 0 is equally
     *  consistent with "positioned at the origin" and "positioned by a matrix
     *  this accessor cannot see".
     *
     *  The load-bearing field is `tgt`: a draw into an offscreen Bitmap or
     *  into a saveLayer looks IDENTICAL in transform, clip and paint to a draw
     *  into the frame, so "painted correctly into a buffer that is never
     *  composited" is otherwise completely silent. tgt reports the backing
     *  image's identity + size, and `layers` how many saveLayer()s are
     *  outstanding, so that case is visible as data. */
    public String macapkTraceState() {
        if (g == null) return "g=null";
        StringBuilder sb = new StringBuilder();
        try {
            AffineTransform t = g.getTransform();
            sb.append("ctm=[").append((float) t.getScaleX()).append(',')
              .append((float) t.getShearY()).append(',')
              .append((float) t.getShearX()).append(',')
              .append((float) t.getScaleY()).append(',')
              .append((float) t.getTranslateX()).append(',')
              .append((float) t.getTranslateY()).append(']');
            java.awt.Rectangle cu = g.getClipBounds();   // USER space
            sb.append(" clipU=").append(cu == null ? "none" : cu.toString());
            if (cu != null) {
                java.awt.geom.Rectangle2D cd = t.createTransformedShape(cu).getBounds2D();
                sb.append(" clipDev=[").append((float) cd.getX()).append(',')
                  .append((float) cd.getY()).append(' ')
                  .append((float) cd.getWidth()).append('x')
                  .append((float) cd.getHeight()).append(']');
            }
            sb.append(" tgt=");
            if (image == null) sb.append("null");
            else sb.append('@').append(Integer.toHexString(System.identityHashCode(image)))
                   .append(' ').append(image.getWidth()).append('x').append(image.getHeight());
            sb.append(" bmp=");
            if (bitmap == null) sb.append("null");
            else {
                sb.append('@').append(Integer.toHexString(System.identityHashCode(bitmap)));
                // Whether `image` is still the bitmap's own backing store answers
                // "am I inside a saveLayer" independently of the layer count.
                try { sb.append(image == bitmap.macapkAwtImage() ? " (direct)" : " (redirected)"); }
                catch (Throwable ignored) {}
            }
            int realLayers = 0;
            for (Layer l : layerStack) if (l != NO_LAYER) realLayers++;
            sb.append(" layers=").append(realLayers).append('/').append(layerStack.size());
            sb.append(" composite=").append(g.getComposite());
        } catch (Throwable t) {
            sb.append(" <trace-failed: ").append(t).append('>');
        }
        return sb.toString();
    }
    /** Diagnostic companion to macapkTraceState: how much ink is actually IN
     *  the backing image right now, and where. Callers gate it behind their own
     *  trace env var; it is O(pixels) and must never run on a hot path.
     *
     *  This is the measurement that separates "we rasterized nothing" from "we
     *  rasterized correctly into a buffer that something downstream drops" --
     *  two states that look identical from every other vantage point, including
     *  the final screenshot. */
    public String macapkTraceInk() {
        if (image == null) return "ink=no-image";
        try {
            int w = image.getWidth(), h = image.getHeight();
            int ink = 0, x0 = w, y0 = h, x1 = -1, y1 = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((image.getRGB(x, y) >>> 24) == 0) continue;
                    ink++;
                    if (x < x0) x0 = x;
                    if (y < y0) y0 = y;
                    if (x > x1) x1 = x;
                    if (y > y1) y1 = y;
                }
            }
            return "ink=" + ink + "/" + (w * h)
                 + (ink == 0 ? "" : (" box=[" + x0 + "," + y0 + " " + x1 + "," + y1 + "]"));
        } catch (Throwable t) {
            return "ink=<" + t + ">";
        }
    }
    public void rotate(float degrees)         { if (g != null) g.rotate(Math.toRadians(degrees)); }
    public void rotate(float degrees, float px, float py) {
        if (g == null) return;
        g.translate(px, py);
        g.rotate(Math.toRadians(degrees));
        g.translate(-px, -py);
    }
    public void skew(float sx, float sy) {
        if (g == null) return;
        AffineTransform t = g.getTransform();
        t.shear(sx, sy);
        g.setTransform(t);
    }
    /** AOSP Canvas.concat(Matrix) -- pre-concatenate the matrix onto the
     *  current transform (points map through `matrix` first, then the
     *  existing transform). Jetpack Compose's hardware-layer software
     *  path calls canvas.concat(renderNode.getMatrix()) to position a
     *  graphicsLayer's content, so this is load-bearing for Compose UIs. */
    public void concat(Matrix matrix) {
        if (g == null || matrix == null) return;
        g.transform(matrix.macapkToAffine());
    }
    /** AOSP Canvas.getMatrix(): the current transform as a Matrix. Coil's SVG
     *  decoder reads it (canvas.getMatrix().preConcat(...)) to position the SVG;
     *  a missing method NoSuchMethodError'd, so network SVG icons (Now-in-Android
     *  topic icons) failed to render. v1.0.429. */
    public Matrix getMatrix() {
        Matrix out = new Matrix();
        if (g != null) out.macapkFromAffine(g.getTransform());
        return out;
    }
    public void getMatrix(Matrix out) {
        if (out != null && g != null) out.macapkFromAffine(g.getTransform());
    }
    public void setMatrix(Matrix matrix) {
        if (g == null) return;
        if (matrix == null) return;
        // AOSP setMatrix replaces the current matrix relative to the initial
        // canvas state; we approximate by resetting to identity then applying.
        g.setTransform(matrix.macapkToAffine());
    }

    // --- Fill / clear -------------------------------------------------
    //
    // ⚠ DELIBERATE, KNOWN DIVERGENCE, DO NOT "FIX" WITHOUT AUDITING THE CALLERS.
    // AOSP BaseCanvas.drawColor(int) is
    //     nDrawColor(mNativeCanvasWrapper, color, BlendMode.SRC_OVER...porterDuffMode)
    // (android-11.0.0_r48 BaseCanvas.java:264-266), and Canvas.drawColor's javadoc
    // says "using srcover porterduff mode" -- so on a real device a TRANSLUCENT
    // colour BLENDS. We composite Src (overwrite) instead, and that is on purpose:
    // this tree's clear-to-background callers (ViewCanvasDriver's per-frame
    // canvas.drawColor(clearArgb), PorterDuff.Mode.CLEAR routed through
    // drawColor(0)) depend on drawColor(0) actually clearing the surface, which a
    // faithful SRC_OVER makes a NO-OP. A previous session measured a regression
    // here (v1.0.866, dialog scrims: a translucent drawColor erased the frame it
    // was meant to dim) and left the standing instruction to route around it with
    // a Paint-ed drawRect rather than change this.
    //
    // The answer-differential agrees this is not currently costing us a strict
    // answer: RAS.color.translucent_default's strict rows (.npal, .ink) MATCH the
    // emulator, because both SRC and SRC_OVER produce one colour over 1024 pixels;
    // only the tolerant RASM row sees the value. Retiring the divergence means
    // auditing every drawColor(0)-as-clear caller onto the CLEAR overload FIRST,
    // then flipping this to SrcOver -- not flipping it here.
    //
    // The MODE-TAKING overloads below are a different matter and are now honoured
    // exactly (see drawColor(int, PorterDuff.Mode)).
    public void drawColor(int color) {
        if (!TRACE_DRAWCOST) { drawColorUntraced(color); return; }
        final long t0 = System.nanoTime();
        try { drawColorUntraced(color); } finally { drawCost(t0, "color", null); }
    }
    private void drawColorUntraced(int color) {
        macapkFillWholeCanvas(color, AlphaComposite.Src);
    }
    public void drawARGB(int a, int r, int gg, int b) {
        drawColor(((a & 0xff) << 24) | ((r & 0xff) << 16) | ((gg & 0xff) << 8) | (b & 0xff));
    }
    /** AOSP: "Fill the entire canvas' bitmap (restricted to the current clip)
     *  with the specified color and porter-duff xfermode." Two properties of that
     *  sentence are load-bearing and were both wrong here:
     *   - "the entire canvas' bitmap" is DEVICE space, so the fill must ignore the
     *     current matrix (we filled 0,0,w,h through the CTM, which under a scale
     *     or a translate covered the wrong region);
     *   - "restricted to the current clip" -- which g already applies.
     *  The composite is reset afterwards so a mode can never leak into the next
     *  draw on this Graphics2D. */
    private static final boolean FAST_CLEAR = !"0".equals(System.getenv("MACAPK_FAST_CLEAR"));

    private void macapkFillWholeCanvas(int color, java.awt.Composite comp) {
        if (g == null || image == null) return;
        // A whole-canvas fill is ink: an app view that clears itself to a
        // colour and draws nothing else must still replace the last published
        // frame. See macapkMarkInk.
        macapkMarkInk();
        java.awt.Paint oldPaint = g.getPaint();
        java.awt.Composite oldComp = g.getComposite();
        AffineTransform oldTx = g.getTransform();
        try {
            g.setTransform(new AffineTransform());
            // v1.0.1648 fast clear: a Src fill of the WHOLE, unclipped canvas is
            // a plain store of one straight-ARGB word into every pixel of the
            // TYPE_INT_ARGB backing, so write it directly instead of running
            // Java2D's fill loop. Only when nothing can make the result differ:
            // the Src composite, no clip (or a clip that covers the canvas), and
            // the bitmap's live array (Bitmap.macapkLiveArgbOrNull, exact
            // length). MEASURED at 3360x1796: 0.39 ms vs 1.74 ms, and this is
            // the full-surface clear in every Canvas frame's prep phase
            // (docs/canvas-present-cost-at-full-screen_2026-09-25.md); stored
            // words identical for alpha 0x00/0x01/0x80/0xFF incl. 0x00FFFFFF
            // (debug/present_fastpath_gate.sh). MACAPK_FAST_CLEAR=0 disables.
            // ⚠ CORRECTED 2026-09-26 (debug/framespeed_gate.sh, clear.*): the
            // word Java2D stores is NOT the colour for a TRANSPARENT colour with
            // non-zero RGB (0x00FFFFFF, 0x00123456) once KEY_ANTIALIASING is on
            // -- the AA mask-fill loop zeroes a source whose alpha is 0 and
            // stores 0x00000000 -- so the claim above held only in the non-AA
            // state it was measured in. Those colours now take Java2D; every
            // other colour is identical in both AA states. This correction is
            // part of MACAPK_FRAMESPEED_RECT: =0 restores the v1.0.1648 fast
            // clear exactly, stored word and all (bisect the fast clear itself
            // with MACAPK_FAST_CLEAR=0, which takes Java2D for every colour).
            final boolean exactWord = !FRAMESPEED_RECT
                    || (color >>> 24) != 0 || (color & 0x00FFFFFF) == 0;
            if (FAST_CLEAR && exactWord && comp == AlphaComposite.Src && bitmap != null
                    && bitmap.macapkAwtImage() == image) {
                java.awt.Shape clip = g.getClip();
                if (clip == null || (clip instanceof java.awt.Rectangle
                        && ((java.awt.Rectangle) clip).contains(0, 0, image.getWidth(), image.getHeight()))) {
                    int[] live = bitmap.macapkLiveArgbOrNull();
                    if (live != null && live.length == image.getWidth() * image.getHeight()) {
                        java.util.Arrays.fill(live, color);
                        return;
                    }
                }
            }
            // MACAPK_FRAMESPEED_RECT (2026-09-26): the same stores under a
            // RECTANGULAR clip, and on any stock TYPE_INT_ARGB image (a layer, a
            // RenderNode recording, an app bitmap), not only the frame: Java2D
            // fills exactly the clip's device region with the colour word, so
            // the region's rows are filled directly. The transform is identity
            // here, so macapkDeviceClipRect reads the device clip with no round
            // trip; a path clip or a half-pixel tie falls through to Java2D.
            // This is the damage-clipped clear of a partial redraw, and a view's
            // own drawColor under its child clip. Proof: framespeed_gate clear.*.
            if (FRAMESPEED_RECT && FAST_CLEAR && exactWord && comp == AlphaComposite.Src
                    && g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL) == RenderingHints.VALUE_STROKE_PURE) {
                final int[] cr = new int[4];
                final int[] data = macapkDeviceClipRect(cr) ? stockArgbData(image) : null;
                if (data != null) {
                    final int w = image.getWidth();
                    final int x1 = cr[2] + FRAMESPEED_RECT_GROW_FOR_TEST;
                    for (int y = cr[1]; y < cr[3]; y++)
                        if (x1 > cr[0]) java.util.Arrays.fill(data, y * w + cr[0], y * w + Math.min(w, x1), color);
                    sFsClearFills++;
                    return;
                }
                // MACAPK_FRAMESPEED_CLIPSPAN: the same Src stores under a PATH
                // clip, through Java2D's own clip region read back as spans
                // (macapkClipSpans; the CTM is identity here). A clipToOutline
                // view's or a circular avatar's drawColor. Proof: clear.* rows
                // with the clipPath case.
                if (FRAMESPEED_CLIPSPAN) {
                    final int w = image.getWidth(), h = image.getHeight();
                    final int[] d2 = (long) w * h >= CLIPSPAN_MIN_PIXELS ? stockArgbData(image) : null;
                    final int[] sp = d2 != null ? macapkClipSpans(0, 0, w, h) : null;
                    if (sp != null) {
                        macapkFillSpans(d2, w, sp, 0, 0, w, h, color);
                        sFsClipSpanFills++;
                        return;
                    }
                }
            }
            g.setComposite(comp);
            g.setColor(toAwtColor(color));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            g.setTransform(oldTx);
            g.setPaint(oldPaint);
            g.setComposite(oldComp);
        }
    }
    // AOSP API-1 overloads taking a blend mode. CoordinatorLayout / window
    // backgrounds call drawColor(color, PorterDuff.Mode.SRC) (or .CLEAR) to
    // paint their background; without the overload rootView.draw throws
    // NoSuchMethodError and the whole screen renders blank.
    //
    // These used to DISCARD the mode and fall through to drawColor(int)'s Src,
    // so every mode painted the source colour over the whole tile. MEASURED
    // (battery RAS, 2026-08-09): DST must be a no-op and answered "the tile is
    // unchanged" on the emulator against a repainted tile here; DST_OVER onto an
    // opaque destination is likewise a no-op; MULTIPLY of 0xFF00FFFF over
    // 0xFF102030 must give 0xFF002030 and SCREEN 0xFF10FFFF, and we answered the
    // raw source both times. compositeForPorterDuff maps all 18 modes -- the 12
    // Porter-Duff alpha rules to AlphaComposite, the 6 separable blends to
    // BlendComposite -- so the argument now means what AOSP says it means.
    public void drawColor(int color, android.graphics.PorterDuff.Mode mode) {
        if (mode == null) { drawColor(color); return; }
        macapkFillWholeCanvas(color, compositeForPorterDuff(mode));
    }
    public void drawColor(int color, android.graphics.BlendMode mode) {
        if (mode == null) { drawColor(color); return; }
        macapkFillWholeCanvas(color, compositeFor(mode));
    }
    /** AOSP `Canvas.drawPaint(Paint)`: "Fill the entire canvas' bitmap (restricted to the current
     *  clip) with the specified paint" -- the PAINT, so its shader is sampled, its colour filter
     *  applied and its blend mode honoured, exactly as if the app had drawn an unclipped rect.
     *
     *  ⚠ This used to be `drawColor(p.getColor())`, which is wrong twice over:
     *    1. it DISCARDS the shader/colour-filter, so a gradient-paint drawPaint painted one flat
     *       colour -- and with a shader set the paint's colour is usually an ignored leftover
     *       (frequently opaque black), so the screen filled black instead of ramping;
     *    2. `drawColor(int)` is deliberately SRC in this file (it OVERWRITES rather than blending --
     *       see its own comment), so a translucent paint erased what was underneath instead of
     *       compositing over it.
     *  Routing through drawRect over the whole canvas in device space gives the paint its full
     *  treatment and the correct SRC_OVER-by-default blending, while `g`'s clip still restricts it,
     *  which is the AOSP contract. */
    public void drawPaint(Paint p) {
        if (!TRACE_DRAWCOST) { drawPaintUntraced(p); return; }
        final long t0 = System.nanoTime();
        try { drawPaintUntraced(p); } finally { drawCost(t0, "paint", p); }
    }
    private void drawPaintUntraced(Paint p) {
        if (p == null) return;
        if (image == null || g == null) { drawColor(p.getColor()); return; }
        java.awt.geom.AffineTransform saved = g.getTransform();
        try {
            // Whole-canvas in DEVICE space: AOSP fills the bitmap, not a transformed rect.
            g.setTransform(new java.awt.geom.AffineTransform());
            drawRect(0f, 0f, image.getWidth(), image.getHeight(), p);
        } finally {
            g.setTransform(saved);
        }
    }

    // --- Shadow layer (Paint.setShadowLayer) --------------------------
    //
    // AOSP Paint.setShadowLayer javadoc: "This draws a shadow layer below the
    // main layer, with the specified offset and color, and blur radius... Can be
    // used to create a blurred shadow underneath text. Support for use with
    // other drawing operations is constrained to the software rendering
    // pipeline." Our Canvas IS a software pipeline (Graphics2D over a
    // BufferedImage), so the AOSP condition that legitimizes a non-text shadow
    // always holds here; before this, only drawText consulted the shadow and
    // every shape shadow (card outline, badge circle, chart bar) silently
    // vanished.
    //
    // The technique is the one drawText already uses: paint the same geometry
    // once in the shadow color at the +dx/+dy offset, plus a 1px cardinal spread
    // when a blur radius is set so the edge reads feathered rather than as a
    // hard duplicate. Everything is guarded on hasShadowLayer(), so a paint
    // without a shadow renders byte-identically to before and allocates nothing.
    private static boolean hasShadow(Paint p) {
        try { return p != null && p.hasShadowLayer(); } catch (Throwable t) { return false; }
    }
    /** @param forceStroke geometry with no area (a line) can only be stroked,
     *  whatever the paint style says -- the main draw strokes it too. */
    private void drawShapeShadow(Shape s, Paint p, boolean forceStroke) {
        if (g == null || s == null || p == null) return;
        AffineTransform savedTx = g.getTransform();
        java.awt.Paint savedPaint = g.getPaint();
        java.awt.Stroke savedStroke = g.getStroke();
        java.awt.Composite savedComp = g.getComposite();
        try {
            boolean stroke = forceStroke || p.getStyle() == Paint.Style.STROKE;
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(toAwtColor(p.getShadowColor()));
            if (stroke) {
                float sw = p.getStrokeWidth();
                g.setStroke(macapkStrokeFor(p, sw));
            }
            g.translate((double) p.getShadowDx(), (double) p.getShadowDy());
            shadowPass(s, stroke);
            if (p.getShadowRadius() >= 1f) {
                g.translate(1d, 0d);   shadowPass(s, stroke);
                g.translate(-2d, 0d);  shadowPass(s, stroke);
                g.translate(1d, 1d);   shadowPass(s, stroke);
                g.translate(0d, -2d);  shadowPass(s, stroke);
            }
        } catch (Throwable t) {
            com.macapk.loader.GfxTrace.swallow("Canvas.drawShapeShadow", t);
        } finally {
            g.setTransform(savedTx);
            g.setPaint(savedPaint);
            g.setStroke(savedStroke);
            g.setComposite(savedComp);
        }
    }
    private void shadowPass(Shape s, boolean stroke) {
        if (stroke) g.draw(s); else g.fill(s);
    }

    // --- Primitive shapes --------------------------------------------
    public void drawRect(float l, float t, float r, float b, Paint p) {
        if (!TRACE_DRAWCOST) { drawRectUntraced(l, t, r, b, p); return; }
        final long t0 = System.nanoTime();
        try { drawRectUntraced(l, t, r, b, p); } finally { drawCost(t0, "rect "+(int)(r-l)+"x"+(int)(b-t), p); }
    }
    private void drawRectUntraced(float l, float t, float r, float b, Paint p) {
        if (g == null || p == null) return;
        sGlobalRect++;
        com.macapk.loader.GfxTrace.real("Canvas.drawRect");
        // This used to truncate to int in USER space, before the transform.
        // Same mechanism v1.0.876 fixed in clipRect, whose comment called
        // clipRect "the odd one out" -- true within the clip family, false
        // across this file: every shape op did it. Three compounding costs,
        // each multiplied by the current CTM:
        //   1. truncation is toward ZERO, not floor, so (int)(-0.5f) == 0 and
        //      a negative coordinate rounds the WRONG WAY;
        //   2. the extent truncates INDEPENDENTLY of the position, so a rect
        //      can lose a pixel at one edge and keep it at the other;
        //   3. a sub-1.0 extent becomes 0 and Graphics2D fills NOTHING.
        //      anuto's HealthBar draws its bar 0.1f units tall under
        //      concat(worldMatrix) at 80.8 px/unit; (int)0.1f == 0, so every
        //      enemy health bar is invisible while every call succeeds.
        // Rectangle2D.Float + g.fill/g.draw is what drawPath and clipOutRect
        // already use: the Shape is transformed by the CTM and rasterized in
        // DEVICE space, which is what Skia does.
        // MEASURED (JDK 21, ANTIALIAS_ON): for INTEGER input g.fill(
        // Rectangle2D.Float) is byte-identical to g.fillRect(int) and g.draw()
        // to g.drawRect(int). ColorDrawable and GradientDrawable both pass
        // integer getBounds(), so the dominant framework path does not move a
        // pixel; only fractional callers change.
        // ⚠ THE RECT IS SORTED. The previous comment here said "Deliberately NOT
        // normalized: an inverted rect (l > r) stays empty and draws nothing,
        // matching SkRect::isEmpty on real Android." That is REFUTED by
        // measurement and by Skia's source. SkCanvas::drawRect is:
        //     void SkCanvas::drawRect(const SkRect& r, const SkPaint& paint) {
        //         // To avoid redundant logic in our culling code and various
        //         // backends, we always sort rects before passing them along.
        //         this->onDrawRect(r.makeSorted(), paint);
        //     }
        // (google/skia src/core/SkCanvas.cpp). MEASURED (RAS.rect.invertedX /
        // invertedXY): drawRect(24,8,8,24) answers ink=256 box=8,8,24,24 on the
        // emulator -- the normalised rect, fully painted -- against ink=0
        // box=none here. isEmpty() is what SkRect::isEmpty reports for an
        // UNSORTED rect, but drawRect never sees an unsorted one.
        // A genuinely degenerate rect (zero width or height) still draws nothing,
        // because sorting leaves it degenerate -- RAS.rect.zeroW/zeroH/zeroWH
        // already agreed with the emulator and must keep agreeing.
        float rl = Math.min(l, r), rt = Math.min(t, b);
        Rectangle2D.Float s = new Rectangle2D.Float(rl, rt, Math.abs(r - l), Math.abs(b - t));
        if (hasShadow(p)) drawShapeShadow(s, p, false);
        applyPaint(p);
        if (p.getStyle() == Paint.Style.STROKE) {
            g.draw(macapkSkiaGeom(s, p));
            macapkDashedRectCornerJoin(s, p);
        } else {
            Shape fs = macapkSkiaGeom(s, p);
            if (!macapkFastOpaqueRectFill(fs)) g.fill(fs);
            if (p.getStyle() == Paint.Style.FILL_AND_STROKE) g.draw(macapkSkiaGeom(s, p));
            macapkTraceFullFill(s, p);
        }
    }

    // ---- FRAMESPEED: opaque rect fills (2026-09-26) ------------------------
    //
    // A ColorDrawable, a setBackgroundColor view, a list row, a toolbar or a
    // root layout's background is one drawRect of an opaque colour, and at the
    // 3360x1796 window those are the largest fills in a frame. Java2D fills them
    // with its per-pixel loop at ~0.34 ns/px; the same stores through
    // Arrays.fill (a HotSpot intrinsic) run at ~0.07 ns/px. MEASURED on a bare
    // JVM (debug/framespeed_bench): a full-window drawRect 2.06 ms, a 1000x1000
    // one 0.36 ms against 0.07 ms of Arrays.fill.
    //
    // EXACT, and only where exactness is provable:
    //  - the paint Java2D would fill with is an OPAQUE java.awt.Color and the
    //    composite is SrcOver or Src at alpha 1: every covered pixel becomes the
    //    colour's ARGB word, whatever it held (SrcOver's coverage-255, alpha-255
    //    case stores the source; Src stores it outright);
    //  - the CTM is identity or a pure translation, and every DEVICE edge of the
    //    rect is an integer (to 1e-6): the covered pixels are then exactly the
    //    integer rect, antialiased or not -- Marlin's coverage of an integer edge
    //    is 0 or 255, and the non-AA pixel-centre rule includes exactly [L, R);
    //  - the clip is absent or a rectangle whose device-space region can be
    //    reproduced exactly (macapkDeviceClipRect: Java2D's own clipRound, and a
    //    refusal on any half-pixel tie);
    //  - the destination is a stock TYPE_INT_ARGB image (one int per pixel).
    // Anything else takes g.fill exactly as before. A translucent colour, a
    // shader, a blend mode, a rounded or fractional rect, a scaled or rotated
    // CTM and a path clip are all left to Java2D.
    //
    // Proof: debug/framespeed_gate.sh (rect.*): gate ON vs OFF on the same
    // drawing programs, byte for byte, including fractional edges one ulp from
    // an integer, half-pixel clips, translucent colours and both AA states;
    // positive control MACAPK_FRAMESPEED_RECT_GROW_FOR_TEST=1 fills one extra
    // column and must mismatch. MACAPK_FRAMESPEED_RECT=0 restores g.fill.
    static final boolean FRAMESPEED_RECT =
        !"0".equals(System.getenv("MACAPK_FRAMESPEED_RECT"));
    // Gate positive control only: fill this many extra columns on the right.
    static final int FRAMESPEED_RECT_GROW_FOR_TEST = macapkEnvInt(
        "MACAPK_FRAMESPEED_RECT_GROW_FOR_TEST", 0);
    /** How often each FRAMESPEED fast path actually ran (diagnostic; read by
     *  debug/framespeed_gate.sh to prove its battery exercises them, and by
     *  ViewCanvasDriver's MACAPK_FRAME_PHASE_TRACE line). Unsynchronized: the
     *  counts are approximate across threads and exact on one. */
    public static long sFsRectFills, sFsClearFills, sFsLayersPooled, sFsLayersBoxed, sFsLayersReused;
    private static final double FRAMESPEED_EPS = 1e-6;
    /** The integer nearest v when v is within FRAMESPEED_EPS of it, else
     *  Integer.MIN_VALUE (the caller's "not exact" answer). */
    private static int macapkNearInt(double v) {
        if (!(Math.abs(v) < 1e9)) return Integer.MIN_VALUE;
        double r = Math.rint(v);
        return (Math.abs(v - r) <= FRAMESPEED_EPS) ? (int) r : Integer.MIN_VALUE;
    }
    /** Region.clipRound(v) = ceil(v - 0.5), or Integer.MIN_VALUE when v lies
     *  within FRAMESPEED_EPS of a half-pixel tie, where a reconstruction one ulp
     *  away from Java2D's own value could round the other way. */
    private static int macapkClipRound(double v) {
        if (!(Math.abs(v) < 1e9)) return Integer.MIN_VALUE;
        double t = v - 0.5;
        if (Math.abs(t - Math.rint(t)) <= FRAMESPEED_EPS && t != Math.rint(t))
            return Integer.MIN_VALUE;
        return (int) Math.ceil(t);
    }
    /** The device-space integer rect {x0,y0,x1,y1} Java2D clips a fill to right
     *  now -- the image bounds intersected with a rectangular user clip, rounded
     *  as SunGraphics2D.validateCompClip does (Region.getIntersection(Rectangle2D)
     *  -> clipRound) -- into `out`, or false when that cannot be reproduced
     *  exactly: a non-rectangular clip, a CTM that is not a pure translation, or
     *  a half-pixel tie. Requires g and image. */
    private boolean macapkDeviceClipRect(int[] out) {
        AffineTransform t = g.getTransform();
        if ((t.getType() & ~AffineTransform.TYPE_TRANSLATION) != 0) return false;
        final double tx = t.getTranslateX(), ty = t.getTranslateY();
        int x0 = 0, y0 = 0, x1 = image.getWidth(), y1 = image.getHeight();
        Shape c = g.getClip();
        if (c != null) {
            if (!(c instanceof Rectangle2D)) return false;
            Rectangle2D r = (Rectangle2D) c;
            int cx0, cy0, cx1, cy1;
            if (c instanceof java.awt.Rectangle) {
                // Region.getIntersection(Rectangle): the ints, no rounding. The
                // user-space Rectangle is the device one shifted by the int
                // translation (SunGraphics2D.transformShape(int, int, Shape)).
                java.awt.Rectangle ir = (java.awt.Rectangle) c;
                int itx = macapkNearInt(tx), ity = macapkNearInt(ty);
                if (itx == Integer.MIN_VALUE || ity == Integer.MIN_VALUE
                        || tx != itx || ty != ity) return false;
                cx0 = ir.x + itx; cy0 = ir.y + ity;
                cx1 = cx0 + ir.width; cy1 = cy0 + ir.height;
                if (ir.width < 0 || ir.height < 0) return false;
            } else {
                cx0 = macapkClipRound(r.getMinX() + tx); cy0 = macapkClipRound(r.getMinY() + ty);
                cx1 = macapkClipRound(r.getMaxX() + tx); cy1 = macapkClipRound(r.getMaxY() + ty);
                if (cx0 == Integer.MIN_VALUE || cy0 == Integer.MIN_VALUE
                        || cx1 == Integer.MIN_VALUE || cy1 == Integer.MIN_VALUE) return false;
            }
            x0 = Math.max(x0, cx0); y0 = Math.max(y0, cy0);
            x1 = Math.min(x1, cx1); y1 = Math.min(y1, cy1);
        }
        out[0] = x0; out[1] = y0; out[2] = x1; out[3] = y1;
        return true;
    }
    /** FRAMESPEED damage (docs/frame-speed-canvas-hotspots_2026-09-26.md, the
     *  S1 patch): where on THIS canvas's pixels would a draw at local (0,0) land
     *  right now, and what can it reach? Writes {tx, ty, clipL, clipT, clipR,
     *  clipB} -- the CTM's integer translation and a device-space SUPERSET of the
     *  current clip (the whole bitmap when unclipped) -- and returns true; or
     *  returns false when the CTM is anything but an integer translation, so the
     *  caller must treat the draw's footprint as unknown. A View records this at
     *  the top of View.draw so a later invalidate(Rect) can be turned into device
     *  damage from where the view was ACTUALLY drawn (including any parent's
     *  custom translation), which is how hwui's damage is formed too. */
    public boolean macapkDeviceOrigin(int[] out6) {
        if (g == null || image == null || out6 == null || out6.length < 6) return false;
        AffineTransform t = g.getTransform();
        if ((t.getType() & ~AffineTransform.TYPE_TRANSLATION) != 0) return false;
        final double tx = t.getTranslateX(), ty = t.getTranslateY();
        if (tx != Math.rint(tx) || ty != Math.rint(ty) || !(Math.abs(tx) < 1e8) || !(Math.abs(ty) < 1e8))
            return false;
        final int itx = (int) tx, ity = (int) ty;
        int x0 = 0, y0 = 0, x1 = image.getWidth(), y1 = image.getHeight();
        java.awt.Rectangle cb = g.getClipBounds();   // user space, outward-rounded
        if (cb != null) {
            x0 = Math.max(x0, cb.x + itx - 1);  y0 = Math.max(y0, cb.y + ity - 1);
            x1 = Math.min(x1, cb.x + cb.width + itx + 1);
            y1 = Math.min(y1, cb.y + cb.height + ity + 1);
        }
        out6[0] = itx; out6[1] = ity;
        out6[2] = x0; out6[3] = y0; out6[4] = Math.max(x0, x1); out6[5] = Math.max(y0, y1);
        return true;
    }

    /** g.fill(s) for an opaque solid axis-aligned integer rect, as direct stores
     *  into the backing array; see the block comment above for the exact
     *  preconditions. Returns false (and draws nothing) when any precondition
     *  fails, so the caller fills through Java2D. */
    private boolean macapkFastOpaqueRectFill(Shape s) {
        if (!FRAMESPEED_RECT || g == null || image == null || !(s instanceof Rectangle2D)) return false;
        java.awt.Paint gp = g.getPaint();
        if (!(gp instanceof Color)) return false;
        final int argb = ((Color) gp).getRGB();
        if ((argb >>> 24) != 0xFF) return false;
        java.awt.Composite comp = g.getComposite();
        if (!(comp instanceof AlphaComposite)) return false;
        AlphaComposite ac = (AlphaComposite) comp;
        if (ac.getAlpha() != 1f
                || (ac.getRule() != AlphaComposite.SRC_OVER && ac.getRule() != AlphaComposite.SRC))
            return false;
        // STROKE_PURE (macapkInitHints sets it on every Graphics2D this Canvas
        // owns): under STROKE_NORMALIZE Java2D nudges fill geometry toward pixel
        // centres, so an integer rect would no longer be exactly its pixels.
        if (g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL) != RenderingHints.VALUE_STROKE_PURE)
            return false;
        Rectangle2D r = (Rectangle2D) s;
        if (!(r.getWidth() > 0) || !(r.getHeight() > 0)) return false;
        final int[] clip = new int[4];
        if (!macapkDeviceClipRect(clip)) {   // also rejects a non-translate CTM
            // MACAPK_FRAMESPEED_CLIPSPAN: a PATH clip, filled through the cached
            // span list of Java2D's own clip region (see macapkClipSpans).
            return FRAMESPEED_CLIPSPAN && macapkClipSpanRectFill(r, argb);
        }
        final AffineTransform t = g.getTransform();
        final double tx = t.getTranslateX(), ty = t.getTranslateY();
        // Device edges the way PixelToParallelogramConverter.fillRectangle forms
        // them (origin + extent after the translation); both must be integers.
        final double dl = r.getX() + tx, dt = r.getY() + ty;
        final int L = macapkNearInt(dl), T = macapkNearInt(dt);
        final int R = macapkNearInt(dl + r.getWidth()), B = macapkNearInt(dt + r.getHeight());
        if (L == Integer.MIN_VALUE || T == Integer.MIN_VALUE
                || R == Integer.MIN_VALUE || B == Integer.MIN_VALUE) return false;
        final int[] data = stockArgbData(image);
        if (data == null) return false;
        final int w = image.getWidth();
        final int x0 = Math.max(L, clip[0]);
        final int x1 = Math.min(R + FRAMESPEED_RECT_GROW_FOR_TEST, clip[2]);
        final int y0 = Math.max(T, clip[1]), y1 = Math.min(B, clip[3]);
        // A tall run one or two pixels wide (a vertical divider) is no faster
        // here than in Java2D's loop -- one cache line per row either way -- so
        // leave it there. Nothing has been written yet, so declining is free.
        if (x1 - x0 <= 2 && y1 - y0 > 32) return false;
        if (x1 - x0 > 8) {
            for (int y = y0; y < y1; y++) java.util.Arrays.fill(data, y * w + x0, y * w + x1, argb);
        } else if (x1 > x0) {
            // A narrow run: per-row Arrays.fill calls cost more than the stores.
            for (int y = y0; y < y1; y++)
                for (int i = y * w + x0, e = y * w + x1; i < e; i++) data[i] = argb;
        }
        sFsRectFills++;
        return true;
    }

    // ---- FRAMESPEED: opaque fills under a PATH clip (2026-09-26) -------------
    //
    // A rect or a drawColor under clipPath -- a clipToOutline view's rectangular
    // background inside its rounded outline, a card's children, a circular
    // avatar -- ran at 2.6-5.8 ns/px (docs/frame-speed-canvas-hotspots_
    // 2026-09-26.md 1b): with a non-rectangular clip SurfaceData.validatePipe
    // sends a non-AA fill through paintViaShape (the general PaintContext pipe,
    // span by span) and an AA one through AAShapePipe + SpanClipRenderer, and
    // both come down to "every pixel of rect n clipRegion becomes the colour".
    //
    // EXACT BY CONSTRUCTION, not by reimplementation. Java2D's clip region is
    // computed once per clip()/setClip() in SunGraphics2D.validateCompClip
    // (JDK 21 SunGraphics2D.java:1900-1923):
    //     PathIterator cpi = usrClip.getPathIterator(null);
    //     ShapeSpanIterator sr = LoopPipe.getFillSSI(this);   // adjust = !STROKE_PURE
    //     sr.setOutputArea(devClip); sr.appendPath(cpi); ... Region.getInstance(box, sr)
    // i.e. a pure function of (the device-space usrClip's segment types and FLOAT
    // coordinates -- appendPath reads currentSegment(float[]) --, its winding
    // rule, devClip = the image bounds, the stroke hint). We never rasterize the
    // path ourselves: we hand Java2D that same input on a scratch TYPE_BYTE_GRAY
    // image of the SAME size (so devClip is identical) and read back which
    // pixels it wrote. That set IS the region, and it is cached as per-row spans
    // keyed by exactly those inputs, so the next frame's identical clip costs a
    // lookup.
    //
    // HOW THE MASK IS WRITTEN (fix pass): the path is FILLED, not set as a clip.
    // A non-AA fill under a shape clip goes through paintViaShape
    // (SurfaceData.validatePipe :674-680, CLIP_SHAPE), the very pipe this code
    // exists to avoid, so a mask built by "setClip(path); fillRect(bounds)" cost
    // as much as the fill it replaced (1.7-3.5 ms for a 1000x600 card). With NO
    // clip set, non-AA, a solid colour and a WIDE BasicStroke, g.fill(path)
    // lands in LoopPipe.fill (PixelToPgramLoopConverter.fill :151-165 hands any
    // non-Rectangle2D shape to its outpipe, colorPrimitives = LoopPipe; JDK 21
    // LoopPipe.java:285-320):
    //     ShapeSpanIterator sr = getFillSSI(sg2d);        // adjust = !STROKE_PURE
    //     sr.setOutputArea(sg2d.getCompClip());            // no clip: devClip
    //     sr.appendPath(s.getPathIterator(null));          // identity transform
    //     fillSpans(sg2d, sr);                             // the FillSpans loop
    // i.e. the SAME iterator fed the SAME floats (setClip keeps
    // cloneShape(path) = new GeneralPath(path), an exact float copy,
    // SunGraphics2D.java:509) over the SAME output area as validateCompClip, so
    // the pixels FillSpans writes are exactly the spans Region.getInstance(box,
    // sr) collects (Region.java:373-395: appendSpan of every nextSpan). The
    // stroke must be WIDE: under STROKE_THIN, which the default stroke is,
    // LoopPipe.fill takes fillPathLoop.FillPath instead (:286-305) --
    // ProcessPath's own rasterizer, not the clip's (positive control
    // MACAPK_FRAMESPEED_CLIPSPAN_THIN_FOR_TEST=1, gate row G). A build now
    // costs 5-10x less than the clip-and-fillRect one and 3-9x less than the
    // Java2D fill (fix-pass numbers in docs/frame-speed-caret-clip-cull_
    // 2026-09-26.md).
    // The fill writes the whole region, so the box scanned and cleared must
    // contain all of it: it is the control-point HULL of the key's floats, one
    // pixel wider. Every edge the iterator makes lies in that hull (line ends
    // are key coordinates; curves are split at float midpoints, which stay
    // between their ends), and a region pixel's centre lies inside those edges.
    // Gate row G checks the mask is left all-zero after every build.
    //
    // The one step that is not Java2D's own: reading usrClip back. getClip()
    // returns it UNTRANSFORMED (x - tx, in double) and we re-apply the CTM, so a
    // coordinate could differ from Java2D's by a double rounding. A coordinate is
    // accepted only if no value within 2 double-ulps of it rounds to a different
    // FLOAT (macapkClipKey), which is exactly what the rasterizer consumes; else
    // the fill falls through to Java2D. (For a clip set under an integer
    // translation -- every view-tree clip -- the round trip is exact anyway.)
    //
    // Preconditions otherwise as macapkFastOpaqueRectFill's (opaque Color,
    // SrcOver/Src at alpha 1, STROKE_PURE, translate-only CTM, integer device
    // edges, stock TYPE_INT_ARGB), plus a floor of CLIPSPAN_MIN_PIXELS so a tiny
    // fill never pays for a key it cannot win back.
    //
    // ADMISSION (fix pass). A build is paid BEFORE its span fill, so on a clip
    // seen only once it is all extra cost -- and the key is the DEVICE path, so
    // it is new on every frame the clip moves on screen (a clipToOutline card in
    // a scrolling list, a ViewPager swipe, a reveal), exactly the frames drawn
    // back to back. With the old clip-and-fillRect build such a fill cost
    // 1.2-2.4x the Java2D fill (the fix-pass review's measurement). The build now
    // costs about 0.3-0.9 ns per pixel of the hull box (more for a small path,
    // where the fixed cost dominates) against Java2D's 3-5 ns per pixel filled,
    // so a first sighting is built at once when the fill covers at least HALF of
    // the hull box (a background, a drawColor: a moving card then fills several
    // times faster than Java2D), and otherwise only on its SECOND sighting (a
    // small child fill inside a large clip, where the build would be the bigger
    // cost), i.e. when the clip recurs: the next fill under it, or the same
    // frame redrawn. The seen set is a direct-mapped table of key hashes; a
    // collision can only admit a key early or late, never change a pixel (every
    // build is exact).
    // MACAPK_FRAMESPEED_CLIPSPAN_ADMIT=1 builds every first sighting (the
    // pre-fix-pass policy: the positive control C9 for gate row F); =2 builds
    // none (every key waits for its second sighting: an A/B arm).
    //
    // MACAPK_FRAMESPEED_CLIPSPAN=0 restores Java2D for every path-clipped fill
    // (and MACAPK_FRAMESPEED_RECT=0 disables this with the rest of the rect
    // path). Proof: debug/framespeed_gate.sh canvas, clipfill.* and clear.*,
    // ON == OFF byte for byte; positive control
    // MACAPK_FRAMESPEED_CLIPSPAN_GROW_FOR_TEST=1 widens every span by a pixel
    // and must mismatch. Row F: a first sighting never builds.
    static final boolean FRAMESPEED_CLIPSPAN =
        !"0".equals(System.getenv("MACAPK_FRAMESPEED_CLIPSPAN"));
    static final int FRAMESPEED_CLIPSPAN_GROW_FOR_TEST = macapkEnvInt(
        "MACAPK_FRAMESPEED_CLIPSPAN_GROW_FOR_TEST", 0);
    /** 0 (default): the half-cover rule above; 1: every first sighting is
     *  built (the pre-fix-pass policy, gate row F's positive control C9); 2:
     *  every key waits for its second sighting. */
    static final int FRAMESPEED_CLIPSPAN_ADMIT = macapkEnvInt(
        "MACAPK_FRAMESPEED_CLIPSPAN_ADMIT", 0);
    /** Test only: build the mask with the default THIN stroke, i.e. through
     *  ProcessPath's FillPath rasterizer instead of the clip's (gate row G's
     *  positive control; must mismatch). */
    static final boolean FRAMESPEED_CLIPSPAN_THIN_FOR_TEST =
        "1".equals(System.getenv("MACAPK_FRAMESPEED_CLIPSPAN_THIN_FOR_TEST"));
    /** Any width above 1 makes LoopPipe.fill take the span-iterator path
     *  (SunGraphics2D.validateBasicStroke :1068-1078: STROKE_WIDE). */
    private static final java.awt.BasicStroke CLIPSPAN_WIDE_STROKE = new java.awt.BasicStroke(2f);
    /** Eight mask bytes at a time for the readback scan (runs are long). */
    private static final java.lang.invoke.VarHandle CLIPSPAN_LONGS =
        java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);
    private static final int CLIPSPAN_MIN_PIXELS = 4096;
    private static final int CLIPSPAN_CACHE_ENTRIES = 64;
    private static final int CLIPSPAN_MAX_KEY = 1 << 16;      // ints; a bigger path is not cached
    private static final int CLIPSPAN_SEEN = 256;             // power of two
    /** Deferred = first sightings left to Java2D by the admission rule. */
    public static long sFsClipSpanFills, sFsClipSpanBuilds, sFsClipSpanDeferred;
    /** key (see macapkClipKey) -> packed spans (see macapkBuildClipSpans).
     *  Access-ordered, evicted by hand past CLIPSPAN_CACHE_ENTRIES (no anonymous
     *  subclass: a new nested class would move FRAMEWORK_ID). Guarded by itself;
     *  the scratch masks and the seen table are only touched under the same lock. */
    private static final java.util.LinkedHashMap<java.nio.IntBuffer, int[]> sClipSpanCache =
        new java.util.LinkedHashMap<>(CLIPSPAN_CACHE_ENTRIES * 2, 0.75f, true);
    /** Hashes of keys seen once and not yet built, direct-mapped by the low bits.
     *  A plain int[] rather than a set class, for the same FRAMEWORK_ID reason. */
    private static final int[] sClipSeen = new int[CLIPSPAN_SEEN];
    private static final boolean[] sClipSeenUsed = new boolean[CLIPSPAN_SEEN];
    /** The scratch masks, most recently used first. Two are kept because the
     *  mask must be the canvas's own size and builds alternate between canvases
     *  (the frame, a RenderNode recording, a layer): one slot would reallocate a
     *  multi-MB byte image (6 MB at 3360x1796) on every such alternation. */
    private static java.lang.ref.SoftReference<BufferedImage> sClipMask, sClipMask2;

    /** The rect fill of macapkFastOpaqueRectFill under a non-rectangular clip.
     *  Draws nothing and returns false when any precondition fails. */
    private boolean macapkClipSpanRectFill(Rectangle2D r, int argb) {
        final AffineTransform t = g.getTransform();
        if ((t.getType() & ~AffineTransform.TYPE_TRANSLATION) != 0) return false;
        final double dl = r.getX() + t.getTranslateX(), dt = r.getY() + t.getTranslateY();
        final int L = macapkNearInt(dl), T = macapkNearInt(dt);
        final int R = macapkNearInt(dl + r.getWidth()), B = macapkNearInt(dt + r.getHeight());
        if (L == Integer.MIN_VALUE || T == Integer.MIN_VALUE
                || R == Integer.MIN_VALUE || B == Integer.MIN_VALUE) return false;
        final int w = image.getWidth(), h = image.getHeight();
        final long area = (long) Math.max(0, Math.min(R, w) - Math.max(L, 0))
                * Math.max(0, Math.min(B, h) - Math.max(T, 0));
        if (area < CLIPSPAN_MIN_PIXELS) return false;
        final int[] data = stockArgbData(image);
        if (data == null) return false;
        final int[] sp = macapkClipSpans(L, T, R, B);
        if (sp == null) return false;
        macapkFillSpans(data, w, sp, L, T, R, B, argb);
        sFsClipSpanFills++;
        return true;
    }

    /** Stores `argb` into every pixel of [L,R) x [T,B) inside the packed spans. */
    private static void macapkFillSpans(int[] data, int w, int[] sp, int L, int T, int R, int B, int argb) {
        final int sy0 = sp[0], sy1 = sp[1];
        final int y0 = Math.max(T, sy0), y1 = Math.min(B, sy1);
        for (int y = y0; y < y1; y++) {
            final int k1 = sp[2 + (y - sy0) + 1];
            for (int k = sp[2 + (y - sy0)]; k < k1; k += 2) {
                final int a = Math.max(L, sp[k]);
                final int b = Math.min(Math.min(R, w), sp[k + 1] + FRAMESPEED_CLIPSPAN_GROW_FOR_TEST);
                if (b > a) java.util.Arrays.fill(data, y * w + a, y * w + b, argb);
            }
        }
    }

    /** The current clip's device-space region as packed spans, from the cache
     *  or built now (if admitted: macapkClipAdmit), for a fill of the device
     *  rect [L,R) x [T,B); null when the clip is absent or rectangular (the
     *  callers' own paths), the CTM is not a translation, the region cannot be
     *  reproduced exactly (macapkClipKey), or the key is not admitted yet: the
     *  caller then fills through Java2D. Requires g and image. */
    private int[] macapkClipSpans(int L, int T, int R, int B) {
        if (g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL) != RenderingHints.VALUE_STROKE_PURE)
            return null;
        final int[] key = macapkClipKey();
        if (key == null) return null;
        final java.nio.IntBuffer kb = java.nio.IntBuffer.wrap(key);
        synchronized (sClipSpanCache) {
            int[] sp = sClipSpanCache.get(kb);
            if (sp != null) return sp;
            final int[] box = macapkClipHullBox(key);
            if (!macapkClipAdmit(kb, box, L, T, R, B)) return null;
            sp = macapkBuildClipSpans(key, box);
            if (sp == null) return null;
            sClipSpanCache.put(kb, sp);
            if (sClipSpanCache.size() > CLIPSPAN_CACHE_ENTRIES) {
                java.util.Iterator<java.nio.IntBuffer> it = sClipSpanCache.keySet().iterator();
                it.next();
                it.remove();
            }
            sFsClipSpanBuilds++;
            return sp;
        }
    }

    /** ADMISSION (see the block comment): whether a key missing from the cache
     *  is built now. A fill covering at least half of the hull box is; any other
     *  first sighting is recorded and left to Java2D, and the slot keeps that
     *  key's hash until a second sighting builds it or another key takes the
     *  slot. Caller holds sClipSpanCache. */
    private static boolean macapkClipAdmit(java.nio.IntBuffer kb, int[] box, int L, int T, int R, int B) {
        if (FRAMESPEED_CLIPSPAN_ADMIT == 1) return true;
        if (FRAMESPEED_CLIPSPAN_ADMIT != 2) {
            final long area = (long) (box[2] - box[0]) * (box[3] - box[1]);
            final long cover = (long) Math.max(0, Math.min(R, box[2]) - Math.max(L, box[0]))
                    * Math.max(0, Math.min(B, box[3]) - Math.max(T, box[1]));
            if (2 * cover >= area) return true;
        }
        final int hk = kb.hashCode();
        final int slot = (hk ^ (hk >>> 16)) & (CLIPSPAN_SEEN - 1);
        if (!sClipSeenUsed[slot] || sClipSeen[slot] != hk) {
            sClipSeen[slot] = hk;
            sClipSeenUsed[slot] = true;
            sFsClipSpanDeferred++;
            return false;
        }
        sClipSeenUsed[slot] = false;   // admitted: the cache holds it from now on
        return true;
    }

    /** Coordinates (floats) a PathIterator segment of this type carries. */
    private static int macapkSegCoords(int type) {
        return type == java.awt.geom.PathIterator.SEG_CLOSE ? 0
             : type == java.awt.geom.PathIterator.SEG_QUADTO ? 4
             : type == java.awt.geom.PathIterator.SEG_CUBICTO ? 6 : 2;
    }

    /** {w, h, windingRule, then per segment: type, float bits of its device
     *  coordinates} of the current clip, exactly as validateCompClip fed them to
     *  the rasterizer -- or null (see macapkClipSpans). */
    private int[] macapkClipKey() {
        final Shape c = g.getClip();
        if (c == null || c instanceof Rectangle2D) return null;
        final AffineTransform t = g.getTransform();
        if ((t.getType() & ~AffineTransform.TYPE_TRANSLATION) != 0) return null;
        final double tx = Math.abs(t.getTranslateX()), ty = Math.abs(t.getTranslateY());
        final java.awt.geom.PathIterator pi = c.getPathIterator(t);
        final double[] seg = new double[6];
        int[] key = new int[64];
        int n = 3;
        while (!pi.isDone()) {
            final int type = pi.currentSegment(seg);
            final int nc = macapkSegCoords(type);
            if (n + 1 + nc > key.length) {
                if (key.length >= CLIPSPAN_MAX_KEY) return null;
                key = java.util.Arrays.copyOf(key, key.length * 2);
            }
            key[n++] = type;
            for (int i = 0; i < nc; i++) {
                final double v = seg[i];
                final float f = (float) v;
                // getClip() handed back (x - T) and the iterator re-added T: the
                // value may be off Java2D's own by a double rounding either way.
                // Accept it only when every double within 2 ulps rounds to f.
                final double e = 2.0 * Math.ulp(Math.abs(v) + ((i & 1) == 0 ? tx : ty));
                if (!Float.isFinite(f) || (float) (v - e) != f || (float) (v + e) != f) return null;
                key[n++] = Float.floatToRawIntBits(f);
            }
            pi.next();
        }
        key[0] = image.getWidth();
        key[1] = image.getHeight();
        key[2] = pi.getWindingRule();
        return java.util.Arrays.copyOf(key, n);
    }

    /** The box macapkBuildClipSpans scans and clears for `key`: the
     *  control-point hull of its floats, one pixel wider on every side, within
     *  the image, as {x0, y0, x1, y1}; empty (x1 == x0, y1 == y0) for a path with
     *  no coordinates or none on the image. It holds every pixel the mask fill
     *  can write (see the block comment). */
    private static int[] macapkClipHullBox(int[] key) {
        final int w = key[0], h = key[1];
        float lx = Float.POSITIVE_INFINITY, ly = Float.POSITIVE_INFINITY;
        float hx = Float.NEGATIVE_INFINITY, hy = Float.NEGATIVE_INFINITY;
        for (int i = 3; i < key.length; ) {
            final int nc = macapkSegCoords(key[i++]);
            for (int j = 0; j < nc; j += 2) {
                final float x = Float.intBitsToFloat(key[i + j]), y = Float.intBitsToFloat(key[i + j + 1]);
                lx = Math.min(lx, x); hx = Math.max(hx, x);
                ly = Math.min(ly, y); hy = Math.max(hy, y);
            }
            i += nc;
        }
        final int x0 = (int) Math.min(w, Math.max(0, Math.floor(lx) - 1));
        final int y0 = (int) Math.min(h, Math.max(0, Math.floor(ly) - 1));
        final int x1 = (int) Math.max(x0, Math.min(w, Math.ceil(hx) + 1));
        final int y1 = (int) Math.max(y0, Math.min(h, Math.ceil(hy) + 1));
        return (x1 > x0 && y1 > y0) ? new int[]{x0, y0, x1, y1} : new int[]{x0, y0, x0, y0};
    }

    /** The device path `key` describes (its floats, verbatim). */
    private static java.awt.geom.Path2D.Float macapkKeyPath(int[] key) {
        final java.awt.geom.Path2D.Float dev = new java.awt.geom.Path2D.Float(key[2]);
        for (int i = 3; i < key.length; ) {
            final int type = key[i++];
            switch (type) {
                case java.awt.geom.PathIterator.SEG_MOVETO:
                    dev.moveTo(Float.intBitsToFloat(key[i]), Float.intBitsToFloat(key[i + 1])); i += 2; break;
                case java.awt.geom.PathIterator.SEG_LINETO:
                    dev.lineTo(Float.intBitsToFloat(key[i]), Float.intBitsToFloat(key[i + 1])); i += 2; break;
                case java.awt.geom.PathIterator.SEG_QUADTO:
                    dev.quadTo(Float.intBitsToFloat(key[i]), Float.intBitsToFloat(key[i + 1]),
                               Float.intBitsToFloat(key[i + 2]), Float.intBitsToFloat(key[i + 3])); i += 4; break;
                case java.awt.geom.PathIterator.SEG_CUBICTO:
                    dev.curveTo(Float.intBitsToFloat(key[i]), Float.intBitsToFloat(key[i + 1]),
                                Float.intBitsToFloat(key[i + 2]), Float.intBitsToFloat(key[i + 3]),
                                Float.intBitsToFloat(key[i + 4]), Float.intBitsToFloat(key[i + 5])); i += 6; break;
                default: dev.closePath(); break;
            }
        }
        return dev;
    }

    /** Java2D's region for the clip `key` describes, read back off a scratch
     *  byte image of the same size (see the block comment above) inside `box`
     *  (macapkClipHullBox), packed as {y0, y1, rowStart[y0..y1] (y1 - y0 + 1
     *  entries, absolute indices into this array), then [x0, x1) pairs}. The
     *  mask is left all-zero. Caller holds sClipSpanCache. */
    private static int[] macapkBuildClipSpans(int[] key, int[] box) {
        final int w = key[0], h = key[1];
        if (w <= 0 || h <= 0) return null;
        final int x0 = box[0], y0 = box[1], x1 = box[2], y1 = box[3];
        final int rows = y1 - y0;
        if (rows <= 0 || x1 <= x0) return new int[]{y0, y0, 3};   // no pixel can be in it
        int[] out = new int[2 + rows + 1 + 64];
        out[0] = y0; out[1] = y1;
        int n = 2 + rows + 1;
        final BufferedImage m = macapkClipMask(w, h);   // now sClipMask
        final Graphics2D mg = m.createGraphics();
        try {
            // No clip, non-AA, solid, a WIDE stroke: LoopPipe.fill's span-
            // iterator arm, the clip's own rasterization (block comment).
            // STROKE_PURE makes getFillSSI's adjust false, as for the clip.
            mg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            if (!FRAMESPEED_CLIPSPAN_THIN_FOR_TEST) mg.setStroke(CLIPSPAN_WIDE_STROKE);
            mg.setColor(Color.WHITE);
            mg.fill(macapkKeyPath(key));
        } catch (Throwable t) {
            sClipMask = null;   // never reuse a mask that may hold ink
            return null;
        } finally {
            mg.dispose();
        }
        try {
            final byte[] px = ((java.awt.image.DataBufferByte) m.getRaster().getDataBuffer()).getData();
            for (int y = y0; y < y1; y++) {
                out[2 + (y - y0)] = n;
                final int row = y * w;
                int x = x0;
                while (x < x1) {
                    while (x + 8 <= x1 && (long) CLIPSPAN_LONGS.get(px, row + x) == 0L) x += 8;
                    while (x < x1 && px[row + x] == 0) x++;
                    if (x >= x1) break;
                    final int s = x;
                    while (x + 8 <= x1 && (long) CLIPSPAN_LONGS.get(px, row + x) == -1L) x += 8;
                    while (x < x1 && px[row + x] != 0) x++;
                    java.util.Arrays.fill(px, row + s, row + x, (byte) 0);   // leave it clean
                    if (n + 2 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    out[n++] = s;
                    out[n++] = x;
                }
            }
        } catch (Throwable t) {
            sClipMask = null;   // never reuse a mask that may still hold ink
            return null;
        }
        out[2 + rows] = n;
        return java.util.Arrays.copyOf(out, n);
    }

    /** The clean scratch mask for a w x h canvas, made the most recently used
     *  (sClipMask), reusing either kept size before allocating. A build that
     *  fails drops sClipMask, which is therefore always the one it used. Caller
     *  holds sClipSpanCache. */
    private static BufferedImage macapkClipMask(int w, int h) {
        BufferedImage m = sClipMask == null ? null : sClipMask.get();
        if (m != null && m.getWidth() == w && m.getHeight() == h) return m;
        final BufferedImage m2 = sClipMask2 == null ? null : sClipMask2.get();
        if (m2 != null && m2.getWidth() == w && m2.getHeight() == h) {
            final java.lang.ref.SoftReference<BufferedImage> t = sClipMask;
            sClipMask = sClipMask2;
            sClipMask2 = t;
            return m2;
        }
        m = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        sClipMask2 = sClipMask;
        sClipMask = new java.lang.ref.SoftReference<>(m);
        return m;
    }

    /** MACAPK_TRACE_FULLFILL -- diagnostic for "a full-screen fill was ISSUED but never reaches the
     *  frame". Logs any fill covering >=60% of the destination, WITH A READBACK of the pixel it just
     *  wrote -- which is the only way to tell three otherwise-identical failures apart:
     *    (a) issued with a broken value -> colour/alpha wrong in the log
     *    (b) issued but not landing     -> readback != the paint colour (clip/CTM anomaly)
     *    (c) landed but lost downstream -> readback == colour, yet the frame lacks it (replay bug)
     *  Written for Now in Android, whose two opaque Surface underlays (colorScheme.surface, and
     *  surfaceColorAtElevation(surface, 2.dp)) are issued inside the SAME drawBlocks whose cards
     *  reach the frame byte-perfect -- so they are issued AND lost, and no existing trace can say
     *  where. Off unless the env var is set; one getenv, cached. */
    private static Boolean sFullFillTrace;
    private void macapkTraceFullFill(java.awt.geom.Rectangle2D.Float s, Paint p) {
        Boolean on = sFullFillTrace;
        if (on == null) { on = System.getenv("MACAPK_TRACE_FULLFILL") != null; sFullFillTrace = on; }
        if (!on) return;
        try {
            BufferedImage img = this.image;
            if (img == null || g == null) return;
            java.awt.Rectangle dev = g.getTransform().createTransformedShape(s).getBounds();
            long area = (long) Math.max(0, dev.width) * Math.max(0, dev.height);
            long full = (long) img.getWidth() * img.getHeight();
            if (full <= 0 || area * 100L < full * 60L) return;
            int cx = Math.max(0, Math.min(img.getWidth() - 1, dev.x + dev.width / 2));
            int cy = Math.max(0, Math.min(img.getHeight() - 1, dev.y + dev.height / 2));
            java.awt.Shape clip = g.getClip();
            com.macapk.loader.NativeLog.i("Mac.apk.FullFill",
                "canvas=" + getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this))
                + " img=" + img.getWidth() + "x" + img.getHeight()
                + " dev=" + dev.x + "," + dev.y + "," + dev.width + "x" + dev.height
                + " color=" + Integer.toHexString(p.getColor())
                + " shader=" + (p.getShader() == null ? "none" : p.getShader().getClass().getSimpleName())
                + " alpha=" + p.getAlpha()
                + " clip=" + (clip == null ? "none" : clip.getBounds().toString())
                + " readback=" + Integer.toHexString(img.getRGB(cx, cy)));
        } catch (Throwable ignored) { /* a diagnostic must never break drawing */ }
    }


    /** Skia SYNTHESISES the start corner of a dashed closed rect, and without it
     *  that one join is never stroked.
     *
     *  A dashed path is cut into segments before stroking, so the join between
     *  the LAST segment and the FIRST one does not exist any more: at the rect's
     *  start point (left, top) the incoming dash ends with a butt cap at y=top
     *  and the outgoing dash starts with a butt cap at x=left, leaving the
     *  hw x hw outer square uncovered. Skia works around it by appending a tiny
     *  right angle at that point purely so the stroker draws the join:
     *
     *      // if rect is closed, starts in a dash, and ends in a dash, add the
     *      // initial join. potentially a better fix is described here:
     *      // bug.skia.org/7445
     *      if (src.isRect(nullptr) && src.isLastContourClosed() &&
     *          is_even(initialDashIndex)) { ... cullPathStorage.moveTo(midPoint - v);
     *          cullPathStorage.lineTo(midPoint); ... lineTo(midPoint - v); }
     *
     *  (external/skia @ android11-release, src/utils/SkDashPath.cpp:305-352.)
     *  The arms are SK_ScalarNearlyZero long, so they rasterise to nothing; only
     *  the MITER between them lands, and it lands exactly on the missing square.
     *
     *  ⚠ PORTING THE TINY RIGHT ANGLE LITERALLY DOES NOT WORK, twice over, and
     *  both were MEASURED rather than assumed -- do not spend a cycle re-trying:
     *   - Stroking it with BasicStroke: AWT's stroker DROPS a sub-pixel contour.
     *     ink=0 at arm lengths 0.0039 / 0.01 / 0.05 / 0.1 / 0.5; it first draws
     *     at 1.0, where it paints 11 pixels instead of 4.
     *   - createStrokedShape(tinyRightAngle) then g.fill(): the outline geometry
     *     is right (bounds [6,6 4x4]) but Java2D's non-AA fill of the sub-pixel
     *     arm slivers lights whole pixel rows: 11 px for MITER, 8 for ROUND and
     *     BEVEL.
     *  So fill the join wedge directly, in USER space so the CTM applies. This
     *  reproduces the emulator's sig AND ink byte-for-byte (ink 128 -> 132,
     *  box 6,6,26,26 either way) -- RAS.pstate.dash_onRectStroke.{sig,ink}.
     *
     *  The gate is Skia's own, evaluated on the paint, so a dash on a LINE
     *  (RAS.pstate.dash_4_4_phase0 / phase2 / dash_8_4_phase0, all passing) can
     *  never reach here: those go through drawLine, and Skia's workaround is
     *  itself gated on src.isRect().
     *
     *  NOT DONE HERE: drawPath() with a closed-rect path takes the same Skia
     *  branch, but no probe row measures it and Path has no isRect() we can ask,
     *  so it stays a known gap rather than a guess. */
    // --- Skia scan-conversion parity (v1.0.1128, SHIPS OFF) ---------------
    //
    // Java2D and Skia break the SAME tie in OPPOSITE directions. Skia lights a
    // span [SkFixedRoundToInt(left), SkFixedRoundToInt(rite)) with X seeded at
    // the scanline CENTRE (google/skia src/core/SkScan_Path.cpp:256-274, and
    // src/core/SkEdge.h where SkEdge_Compute_DY adds half a pixel in SkFDot6),
    // so a pixel is in when L < centre <= R. Java2D uses L <= centre < R. The
    // two agree everywhere except when a device edge lands exactly on a pixel
    // centre, where Java2D drops the column Skia keeps. Measured against the
    // emulator reference (debug/fwprobe/golden/emulator-api30.tsv):
    // rect.fillHalf ink=210 where the device says 225, rect.stroke3 173 against
    // 192, point.p_w1_butt 0 against 1.
    //
    // The cure is to nudge the geometry a hair in +X so an edge sitting ON a
    // centre falls just past it. THE NUDGE MUST NOT GO INTO THE CTM. That was
    // tried first and REFUTED, for three separately measured reasons:
    //   1. Java2D samples a TexturePaint at the destination pixel's integer
    //      CORNER, so under an integer CTM every sample sits exactly on a texel
    //      boundary and ANY negative nudge floors to the previous texel: an
    //      8-wide tile reading 0,8,16..56 renders as 56,0,8,16.. -- every
    //      BitmapShader shifted one WHOLE texel. There is no dead zone; 1e-9
    //      does it as hard as 1e-2. Skia is immune because it samples at pixel
    //      CENTRES (src/core/SkBitmapProcState.h adds SK_ScalarHalf).
    //      ⚠ scripts/sweep.sh CANNOT see that: a one-texel shift of a tiled
    //      fill is a column permutation and preserves the colour count exactly.
    //   2. Graphics2D stores the clip in DEVICE space and getClip() rounds
    //      OUTWARD, so a CTM carrying the epsilon reports getClipBounds for
    //      clipRect(8,8,24,24) as (7,8,24,24) -- and re-reading under a
    //      reconstructed clean CTM recovers the x offset but never the width.
    //   3. Canvas.getMatrix() would hand an app back its own translate(8,0) as
    //      8.001, and quickReject would answer off the same polluted bounds.
    //
    // So the nudge goes on the SHAPE, inverse-mapped through the CTM so the
    // DEVICE displacement is exactly the epsilon whatever the matrix is. The CTM
    // is never touched, which leaves getMatrix, getClipBounds, quickReject, the
    // saveLayer matte clip and all 14 setTransform sites correct BY
    // CONSTRUCTION, with no compensating edit anywhere.
    //
    // THE SHAPE'S CONCRETE CLASS IS PRESERVED. Java2D has a Rectangle2D fill
    // fast path whose rule differs from its general-path rasterizer, so
    // rewriting a Rectangle2D as a Path2D changes the pixels on its own
    // (measured: ink 210 vs 225 for identical geometry).
    //
    // NON-ANTIALIASED ONLY. The rule above is Skia's non-AA scan converter
    // (SkRasterClip.cpp:170 guards the same family on `fIsBW && !doAA`). With AA
    // on, coverage is fractional and the epsilon only jitters edge alpha by an
    // LSB, so the gate keeps the cost set equal to the benefit set.
    //
    // 1e-3 is not arbitrary: Java2D quantises device coordinates onto a ~1/1024
    // grid, so anything below ~9.77e-4 is rounded away and does nothing at all.
    //
    // NOT CLOSED BY THIS: the CLIP keeps Java2D's rule (an epsilon at the clip
    // install corrupts getClipBounds exactly as reason 2, however it got there).
    // Curves are unreachable too -- Ellipse2D and Arc2D PIE move 0px under any
    // epsilon -- so do NOT claim path.noMoveTo_fill or oval.stroke4_wide.
    //
    // ⚠ SHIPS OFF; set MACAPK_SKIA_FILL_RULE=1 to enable. The spec that produced
    // this says default ON and is device-verified at 19 rows / 3 fixes / 0
    // regressions, but the verification it calls for is sweep + recap_fleet PLUS
    // EYES on four BitmapShader-heavy fleet apps (aurora, newpipe, mastodon,
    // antennapod, which must be byte-identical), and the sweep is blind to the
    // one failure mode that matters. Flip the default only after that pass.
    // Full spec, anchors and falsifier lists:
    // docs/skia-fill-rule-geometry-offset_2026-08-31.md
    private static final boolean MACAPK_SKIA_FILL_RULE =
        "1".equals(System.getenv("MACAPK_SKIA_FILL_RULE"));
    // 1e-2, NOT the 1e-3 the design harness recommended. MEASURED: at 1e-3 this
    // whole change is INERT in the real pipeline -- the gate fires, the offset is
    // applied, and ZERO probe rows move -- while at 1e-2 RASM.rect.fillHalf.ink
    // lands on the golden exactly (ink=225 box=9,9,24,24). 1e-3 sits only ~2.4%
    // above Java2D's ~1/1024 (9.77e-4) coordinate quantum, and the real Canvas
    // carries a density CTM the isolated harness did not, so the user-space
    // offset it inverse-maps to falls under the quantum and rounds away. The
    // harness measured CTM-independence and was wrong about it for that reason.
    // Env-tunable so the next value sweep costs a run, not a rebuild.
    private static final double MACAPK_SKIA_EPS_DEV_X = macapkEnvDouble(
        "MACAPK_SKIA_EPS", 1e-2);
    private static double macapkEnvDouble(String k, double def) {
        try {
            String v = System.getenv(k);
            return (v == null || v.isEmpty()) ? def : Double.parseDouble(v);
        } catch (Throwable t) { return def; }
    }

    /** Returns s displaced so Java2D's span rule answers what Skia's does.
     *  Returns s unchanged when the gate is off, the paint is antialiased, or
     *  the CTM is singular (a singular CTM paints nothing anyway). */
    private static boolean sSkiaGeomAnnounced = false;
    private Shape macapkSkiaGeom(Shape s, Paint p) {
        if (!MACAPK_SKIA_FILL_RULE || s == null || g == null) return s;
        if (p == null || p.isAntiAlias()) return s;
        // Announce ONCE that the gate actually fired. Without this an inert
        // change reads exactly like a working one: the first cut of this patch
        // was wired at all 13 call sites, built clean, and moved ZERO probe rows,
        // and nothing in the output distinguished "gate off" from "gate on but
        // never reached".
        if (!sSkiaGeomAnnounced) {
            sSkiaGeomAnnounced = true;
            System.err.println("[skia-fill-rule] ON -- geometry offset active");
        }
        AffineTransform m = g.getTransform();
        double m00 = m.getScaleX(), m01 = m.getShearX();
        double m10 = m.getShearY(), m11 = m.getScaleY();
        double det = m00 * m11 - m01 * m10;
        if (!(Math.abs(det) > 1e-12)) return s;
        // Inverse-map the device vector (eps, 0) into user space by hand rather
        // than via createInverse(), which allocates and throws on a singular
        // matrix we have already rejected.
        double dx = m11 * MACAPK_SKIA_EPS_DEV_X / det;
        double dy = -m10 * MACAPK_SKIA_EPS_DEV_X / det;
        if (s instanceof Rectangle2D) {
            Rectangle2D r = (Rectangle2D) s;
            return new Rectangle2D.Double(r.getX() + dx, r.getY() + dy,
                                          r.getWidth(), r.getHeight());
        }
        if (s instanceof java.awt.geom.Ellipse2D) {
            java.awt.geom.Ellipse2D e = (java.awt.geom.Ellipse2D) s;
            return new java.awt.geom.Ellipse2D.Double(e.getX() + dx, e.getY() + dy,
                                                      e.getWidth(), e.getHeight());
        }
        if (s instanceof RoundRectangle2D) {
            RoundRectangle2D r = (RoundRectangle2D) s;
            return new RoundRectangle2D.Double(r.getX() + dx, r.getY() + dy,
                                               r.getWidth(), r.getHeight(),
                                               r.getArcWidth(), r.getArcHeight());
        }
        if (s instanceof java.awt.geom.Arc2D) {
            java.awt.geom.Arc2D a = (java.awt.geom.Arc2D) s;
            return new java.awt.geom.Arc2D.Double(a.getX() + dx, a.getY() + dy,
                                                  a.getWidth(), a.getHeight(),
                                                  a.getAngleStart(), a.getAngleExtent(),
                                                  a.getArcType());
        }
        if (s instanceof java.awt.geom.Line2D) {
            java.awt.geom.Line2D l = (java.awt.geom.Line2D) s;
            return new java.awt.geom.Line2D.Double(l.getX1() + dx, l.getY1() + dy,
                                                   l.getX2() + dx, l.getY2() + dy);
        }
        // Path2D / Area already use the general-path rasterizer, so a plain
        // translate cannot switch which rasterizer runs.
        return AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(s);
    }

    private void macapkDashedRectCornerJoin(Rectangle2D.Float r, Paint p) {
        if (r.width <= 0f || r.height <= 0f) return;
        // SkDashPath::InternalFilter returns early for kFill and
        // kStrokeAndFill, so a FILL_AND_STROKE rect is not dashed at all on
        // Android and therefore has no missing corner either.
        if (p.getStyle() != Paint.Style.STROKE) return;
        PathEffect pe;
        try { pe = p.getPathEffect(); } catch (Throwable ignored) { return; }
        if (!(pe instanceof DashPathEffect)) return;
        float[] iv = ((DashPathEffect) pe).macapkIntervals();
        // Skia asserts an even count and our stroke builder rejects negatives;
        // anything else is not a dash Android would have drawn.
        if (iv == null || iv.length < 2 || (iv.length & 1) != 0) return;
        float total = 0f;
        for (float d : iv) { if (d < 0f) return; total += d; }
        if (total <= 0f) return;

        // SkDashPath::CalcDashParameters: normalise the phase (a negative phase
        // "flips"), then find which interval it lands in and how much of that
        // interval remains.
        float phase = ((DashPathEffect) pe).macapkPhase();
        if (phase < 0f) {
            phase = -phase;
            if (phase > total) phase = phase % total;
            phase = total - phase;
            if (phase == total) phase = 0f;
        } else if (phase >= total) {
            phase = phase % total;
        }
        int initialIndex = 0;
        float initialLength = iv[0];
        for (int i = 0; i < iv.length; i++) {
            float gap = iv[i];
            if (phase > gap || (phase == gap && gap != 0f)) {
                phase -= gap;
            } else {
                initialIndex = i;
                initialLength = gap - phase;
                break;
            }
        }
        // "starts in a dash": the even intervals are the ON runs.
        if ((initialIndex & 1) != 0) return;

        // "ends in a dash, or ends at the beginning of an off run".
        float endPhase = (2f * (r.width + r.height) + initialLength) % total;
        int index = 0;
        while (endPhase > iv[index]) {
            endPhase -= iv[index++];
            if (index == iv.length) { endPhase = 0f; break; }
        }
        if (((index & 1) == 0) != (endPhase > 0f)) return;

        float sw = p.getStrokeWidth();
        float hw = sw / 2f;   // hairline (0) stays 0 here; see macapkStrokeFor
        float l = r.x, t = r.y;
        Paint.Join j;
        try { j = p.getStrokeJoin(); } catch (Throwable ignored) { j = Paint.Join.MITER; }
        Shape wedge;
        if (j == Paint.Join.ROUND) {
            // The quarter disc the round join would have painted.
            wedge = new java.awt.geom.Arc2D.Float(l - hw, t - hw, hw * 2f, hw * 2f,
                    90f, 90f, java.awt.geom.Arc2D.PIE);
        } else if (j == Paint.Join.BEVEL) {
            wedge = macapkTriangle(l - hw, t, l, t - hw, l, t);
        } else {
            // MITER. A 90-degree corner needs a ratio of sqrt(2), so any limit
            // below that bevels instead -- the same test macapkStrokeFor makes.
            float miter = 10f;
            try { float m = p.getStrokeMiter(); if (m >= 1f) miter = m; } catch (Throwable ignored) {}
            wedge = (miter >= 1.4142136f)
                    ? new Rectangle2D.Float(l - hw, t - hw, hw, hw)
                    : macapkTriangle(l - hw, t, l, t - hw, l, t);
        }
        g.fill(macapkSkiaGeom(wedge, p));
    }
    private static Shape macapkTriangle(float x0, float y0, float x1, float y1,
                                        float x2, float y2) {
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        p.lineTo(x2, y2);
        p.closePath();
        return p;
    }

    public void drawRect(Rect r, Paint p) {
        if (r != null) drawRect(r.left, r.top, r.right, r.bottom, p);
    }
    public void drawRect(RectF r, Paint p) {
        if (r != null) drawRect(r.left, r.top, r.right, r.bottom, p);
    }
    public void drawLine(float startX, float startY,
                          float stopX, float stopY, Paint p) {
        if (g == null || p == null) return;
        // See drawRect. Note the shadow pass ALREADY built a Line2D.Float and
        // then fed it int-truncated values, so shadow and line agreed on being
        // wrong together.
        // ⚠ EXPECT LITTLE VISIBLE CHANGE UNDER AN IDENTITY CTM. Java2D defaults
        // KEY_STROKE_CONTROL to VALUE_STROKE_NORMALIZE, which snaps a thin
        // stroke to the pixel grid -- measured: a 1px vertical line at x=10.0
        // and at x=10.4 rasterize IDENTICALLY (diffpx 0). The fix still matters
        // under a SCALED CTM (normalization snaps sub-pixel; it cannot undo a
        // displacement of whole pixels) and for thick strokes. If anything ever
        // sets VALUE_STROKE_PURE, this becomes visible everywhere.
        java.awt.geom.Line2D.Float s =
                new java.awt.geom.Line2D.Float(startX, startY, stopX, stopY);
        if (hasShadow(p)) drawShapeShadow(s, p, true);
        applyPaint(p);
        g.draw(macapkSkiaGeom(s, p));
    }
    public void drawCircle(float cx, float cy, float radius, Paint p) {
        if (g == null || p == null) return;
        // See drawRect for the general defect. This op is the worst case of
        // cost (2): the ORIGIN (cx - radius) and the DIAMETER (2 * radius)
        // truncated INDEPENDENTLY, so a circle drifted up to 1px off-centre AND
        // shrank up to 1px at the same time, and any radius below 0.5 vanished.
        // Unlike drawRect there is no integer-input fast path here -- radius is
        // almost always some w/2f -- so every circle in the fleet moves.
        java.awt.geom.Ellipse2D.Float s = new java.awt.geom.Ellipse2D.Float(
                cx - radius, cy - radius, 2f * radius, 2f * radius);
        if (hasShadow(p)) drawShapeShadow(s, p, false);
        applyPaint(p);
        if (p.getStyle() == Paint.Style.STROKE) {
            g.draw(macapkSkiaGeom(s, p));
        } else {
            g.fill(macapkSkiaGeom(s, p));
            if (p.getStyle() == Paint.Style.FILL_AND_STROKE) g.draw(macapkSkiaGeom(s, p));
        }
    }
    public void drawOval(RectF oval, Paint p) {
        if (g == null || p == null || oval == null) return;
        // See drawRect. Reached by GradientDrawable's OVAL shape and by
        // drawable/shapes/OvalShape, both of which pass integer getBounds(), so
        // the framework path is byte-identical; app callers with fractional
        // geometry are the ones that move.
        java.awt.geom.Ellipse2D.Float s = new java.awt.geom.Ellipse2D.Float(
                oval.left, oval.top,
                oval.right - oval.left, oval.bottom - oval.top);
        if (hasShadow(p)) drawShapeShadow(s, p, false);
        applyPaint(p);
        if (p.getStyle() == Paint.Style.STROKE) g.draw(s);
        else g.fill(s);
    }
    /** AOSP `drawArc(RectF, startAngle, sweepAngle, useCenter, Paint)`.
     *  Used by SwipeRefreshLayout's CircleImageView for the progress
     *  arc and by Material Charts / Canvas-based widgets. AOSP
     *  semantics: angles in degrees, 0deg is 3 o'clock, positive is
     *  clockwise. Java2D Graphics.drawArc takes the SAME bounds but
     *  angles in degrees where 0deg is 3 o'clock and positive is
     *  COUNTER-clockwise, so we negate. useCenter=true draws a pie
     *  slice (connect arc endpoints to the oval's center). */
    public void drawArc(RectF oval, float startAngle, float sweepAngle, boolean useCenter, Paint p) {
        if (g == null || p == null || oval == null) return;
        // Skia, SkCanvas::drawArc documentation: "If SkRect oval is empty or
        // sweepAngle is zero, nothing is drawn." The check happens BEFORE
        // useCenter is consulted, so a zero-sweep WEDGE is not the degenerate
        // two-coincident-radii shape it looks like -- it is nothing at all.
        // MEASURED (RAS.arc.wedge_stroke4_sw0): the emulator answers n=1 colour
        // (an untouched tile) while Arc2D.PIE with extent 0 gave us a stroked
        // 4px line from the centre out to the +x radius (probe 24,15 lit).
        // SkRect::isEmpty() is fLeft >= fRight || fTop >= fBottom.
        if (sweepAngle == 0f || oval.left >= oval.right || oval.top >= oval.bottom) return;
        applyPaint(p);
        // See drawRect for the bounds defect. The ANGLES truncated too: int
        // startDeg/extentDeg quantized every arc to whole degrees, so an
        // animated sweep STEPPED instead of rotating (ProgressBar's
        // indeterminate spinner drives startAngle = phase * 360f / PERIOD) and
        // any sweep under 1 degree drew nothing at all.
        // Java2D draws arcs counter-clockwise from 0deg = 3-o'clock; AOSP draws
        // clockwise. Negate start + sweep to match, exactly as before.
        // MEASURED: g.drawArc(int) is byte-identical to g.draw(Arc2D.OPEN), and
        // g.fillArc(int) to g.fill(Arc2D.PIE) -- so this swap preserves current
        // behaviour exactly while removing the truncation.
        // ⚠ The old comment here claimed "stroke draws the arc + the two radii".
        // That is FALSE and measured so: g.drawArc == Arc2D.OPEN (diff 0), while
        // draw(Arc2D.PIE) differs by 129px -- PIE is the one that adds the radii,
        // and PIE is what AOSP/Skia build for useCenter (arcTo + lineTo(centre) +
        // close). Fixing that is a BEHAVIOUR change, not a precision one, so it
        // was deferred out of that stage as a behaviour change; v1.0.902 is it.
        //
        // CLOSURE TYPE, pinned to Skia. SkiaCanvas::drawArc hands useCenter to
        // SkArc::Make as `useCenter ? Type::kWedge : Type::kArc`, and
        // SkPathPriv::CreateDrawArcPath builds the path that then gets stroked OR
        // filled:
        //     if (arc.isWedge()) builder.moveTo(oval.centerX(), oval.centerY());
        //     bool forceMoveTo = !arc.isWedge();
        //     builder.arcTo(oval, startAngle, sweepAngle, forceMoveTo);
        //     if (arc.isWedge()) builder.close();
        // So there are FOUR cases, not two, and we had two of them wrong:
        //   useCenter + STROKE -> the closed wedge, radii PAINTED  = Arc2D.PIE
        //                         (we drew Arc2D.OPEN: the radii were missing)
        //   useCenter + FILL   -> the same wedge                   = Arc2D.PIE
        //   open      + STROKE -> the curve only                   = Arc2D.OPEN
        //   open      + FILL   -> an unclosed contour, implicitly closed by the
        //                         rasteriser, i.e. a CHORD -- and we called
        //                         g.draw here, so it was STROKED, never filled.
        // Verified against Java2D rather than assumed, headless probe on a
        // 300x200 oval with a 6px antialiased stroke:
        //   draw(OPEN) vs draw(PIE)            = 1974 px  -> PIE is what adds the
        //                                         two radii, which is kWedge.
        //   fill(CHORD) vs fill(OPEN)          = 0 px     -> the CHORD spelling
        //                                         documents intent without moving
        //                                         a pixel; the fill bug is the
        //                                         g.draw, not the closure constant.
        // ⚠ A lane reported draw(PIE) as pixel-IDENTICAL to a hand-built
        // moveTo(centre)/arc/close path; re-running that here gave 869 px, not 0.
        // The residual is join rendering at the two centre vertices, not a
        // structural difference, so PIE remains the correct Java2D spelling of a
        // stroked wedge -- but do not quote "diff 0" for it.
        //
        // The >=360 short-circuit belongs to THIS stage, it is not an extra.
        // SkiaCanvas::drawArc tests it BEFORE consulting useCenter:
        //     if (fabs(sweepAngle) >= 360.0f) { mCanvas->drawOval(arc, p); }
        // so a full-sweep wedge is a plain oval with NO radii. Without it, every
        // full-circle ring an app draws with useCenter=true would sprout a spoke
        // the moment PIE landed -- and antennapod's CircularProgressBar
        // demonstrably passes a literal 360.0f sweep.
        //
        // FILL_AND_STROKE deliberately fills only, matching drawOval directly
        // above; changing that is a separate behaviour change with its own
        // attribution.
        float x = oval.left, y = oval.top;
        float w = oval.right - oval.left, h = oval.bottom - oval.top;
        float startDeg = -startAngle;
        float extentDeg = -sweepAngle;
        boolean stroke = p.getStyle() == Paint.Style.STROKE;
        if (Math.abs(sweepAngle) >= 360f) {
            java.awt.geom.Ellipse2D.Float full =
                    new java.awt.geom.Ellipse2D.Float(x, y, w, h);
            if (stroke) g.draw(macapkSkiaGeom(full, p)); else g.fill(macapkSkiaGeom(full, p));
            return;
        }
        int arcType = useCenter ? java.awt.geom.Arc2D.PIE
                                : (stroke ? java.awt.geom.Arc2D.OPEN
                                          : java.awt.geom.Arc2D.CHORD);
        java.awt.geom.Arc2D.Float s = new java.awt.geom.Arc2D.Float(
                x, y, w, h, startDeg, extentDeg, arcType);
        if (stroke) g.draw(macapkSkiaGeom(s, p)); else g.fill(macapkSkiaGeom(s, p));
    }
    /** Float-bounds variant added in API 21. */
    public void drawArc(float left, float top, float right, float bottom,
                        float startAngle, float sweepAngle, boolean useCenter, Paint p) {
        drawArc(new RectF(left, top, right, bottom), startAngle, sweepAngle, useCenter, p);
    }
    public void drawRoundRect(RectF rect, float rx, float ry, Paint p) {
        if (!TRACE_DRAWCOST) { drawRoundRectUntraced(rect, rx, ry, p); return; }
        final long t0 = System.nanoTime();
        try { drawRoundRectUntraced(rect, rx, ry, p); } finally { drawCost(t0, "rrect", p); }
    }
    private void drawRoundRectUntraced(RectF rect, float rx, float ry, Paint p) {
        if (g == null || p == null || rect == null) return;
        sGlobalRoundRect++;
        // See drawRect. The corner RADII truncated too -- arcW = (int)(2 * rx),
        // so a 4.5px radius became 4.0px and any radius below 0.5 squared the
        // corner off entirely. That is the same loss v1.0.884 fixed on the
        // PARSING side for <corners>: the radius arrived here correct and was
        // thrown away again one layer later.
        // No FILL_AND_STROKE branch here, matching the previous code exactly --
        // adding one is a separate behaviour change, not a precision fix.
        // Skia, SkCanvas::drawRoundRect: "If rx or ry are less than zero, they
        // are treated as if they are zero." RoundRectangle2D instead takes the
        // ABSOLUTE value of its arc extents, so a negative radius produced a
        // ROUNDED rect where AOSP produces a square-cornered one. MEASURED
        // (RAS.rrect.fill_rNeg): drawRoundRect(8,8,24,24, -4, -4) answers a plain
        // 256-pixel square on the emulator against 252 pixels with cut corners
        // here. (The other half of the same sentence -- "if rx plus ry exceeds
        // rect width or rect height, radii are scaled down to fit" -- is already
        // satisfied, because RoundRectangle2D clamps its arc extents to the
        // rect's own width/height, which is the same shape for the symmetric
        // case rx == ry that a scale-to-fit produces.)
        float crx = rx > 0f ? rx : 0f;
        float cry = ry > 0f ? ry : 0f;
        RoundRectangle2D.Float s = new RoundRectangle2D.Float(
                rect.left, rect.top,
                rect.right - rect.left, rect.bottom - rect.top,
                2f * crx, 2f * cry);
        if (hasShadow(p)) drawShapeShadow(s, p, false);
        applyPaint(p);
        if (p.getStyle() == Paint.Style.STROKE) g.draw(s);
        else g.fill(s);
    }
    // API 21+ 6-float overload. Compose / Material3 draw rounded cards + buttons
    // via this signature; without it rootView.draw threw NoSuchMethodError and the
    // whole draw pass aborted (Now-in-Android's cards). v1.0.429.
    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint p) {
        drawRoundRect(new RectF(left, top, right, bottom), rx, ry, p);
    }
    /** AOSP drawPoint -> SkCanvas::drawPoint -> drawPoints(kPoints_PointMode).
     *
     *  ⚠ A POINT IS NOT A ZERO-LENGTH LINE. This used to route to
     *  drawLine(x,y,x,y), and the two disagree in exactly the default case.
     *  SkCanvas::drawPoints documents the shape:
     *      "If mode is kPoints_PointMode, the shape of point drawn depends on
     *       paint SkPaint::Cap. If paint is set to SkPaint::kRound_Cap, each
     *       point draws a circle of diameter SkPaint stroke width. If paint is
     *       set to SkPaint::kSquare_Cap or SkPaint::kButt_Cap, each point draws
     *       a square of width and height SkPaint stroke width."
     *  so a BUTT-capped point draws a SQUARE, whereas a zero-length BUTT contour
     *  handed to a stroker has no area and draws nothing -- which is what Java2D
     *  correctly did with the line spelling. BUTT is the Paint default, so the
     *  common case (drawPoint / drawPoints with a plain stroke paint) drew
     *  NOTHING AT ALL. MEASURED (RAS.point.p_w2_butt, p_w4_butt, pts3_w4,
     *  pts_oddCount): the emulator answers a 2x2 / 4x4 square per point, ours
     *  answered ink=0 box=none on all four.
     *
     *  SkDraw::drawPoints builds that geometry and then FILLS it ("temporarily
     *  mark the paint as filling"), so the paint's style is irrelevant here and
     *  g.fill is right for both shapes. A strokeWidth of 0 is Skia's hairline
     *  (one device pixel); we approximate it with a 1-unit square, which is the
     *  same shape a width-1 point gives. */
    public void drawPoint(float x, float y, Paint p) {
        if (g == null || p == null) return;
        float w = p.getStrokeWidth();
        if (!(w > 0f)) w = 1f;
        float h = w * 0.5f;
        Paint.Cap cap = Paint.Cap.BUTT;
        try { if (p.getStrokeCap() != null) cap = p.getStrokeCap(); } catch (Throwable ignored) {}
        Shape s = (cap == Paint.Cap.ROUND)
                ? new java.awt.geom.Ellipse2D.Float(x - h, y - h, w, w)
                : new Rectangle2D.Float(x - h, y - h, w, w);
        if (hasShadow(p)) drawShapeShadow(s, p, false);
        applyPaint(p);
        g.fill(s);
    }
    public void drawPoints(float[] pts, int offset, int count, Paint p) {
        if (pts == null) return;
        // SkiaCanvas::drawPoints does `count >>= 1` after rejecting count < 2, so
        // an ODD count draws floor(count/2) points rather than throwing -- the
        // trailing lone coordinate is simply ignored. This loop already had that
        // shape; it is spelled out here because RAS.point.pts_oddCount measures it.
        for (int i = 0; i + 1 < count; i += 2) {
            drawPoint(pts[offset + i], pts[offset + i + 1], p);
        }
    }
    /** AOSP convenience form: the whole array, offset 0. Same descriptor rule
     *  as the other missing siblings -- it must exist on its own. */
    public void drawPoints(float[] pts, Paint p) {
        if (pts != null) drawPoints(pts, 0, pts.length, p);
    }

    // --- Text ---------------------------------------------------------
    public void drawText(String text, float x, float y, Paint p) {
        if (g == null || p == null || text == null) return;
        sGlobalText++;
        com.macapk.loader.GfxTrace.real("Canvas.drawText");
        applyPaint(p);
        Font macapkPrimaryFont = deriveFont(p);
        g.setFont(macapkPrimaryFont);
        // Paint.Align and the text decorations both need the run's advance, and
        // hwui uses ONE number for both: layout.getAdvance(). Computed only when
        // one of them is in play, so a plain LEFT, undecorated draw (nearly
        // every TextView line) costs nothing extra.
        final Paint.Align align = p.getTextAlign();
        final boolean aligned = MACAPK_TEXT_ALIGN
            && (align == Paint.Align.CENTER || align == Paint.Align.RIGHT);
        final boolean decorated = MACAPK_TEXT_DECORATIONS
            && (p.isUnderlineText() || p.isStrikeThruText());
        final float advance = (aligned || decorated) ? macapkRunAdvance(text, p) : 0f;
        // hwui Canvas::drawText (libs/hwui/hwui/Canvas.cpp, android14-release):
        //     x += MinikinUtils::xOffsetForTextAlign(&paint, layout);
        //     paint.setTextAlign(Paint::kLeft_Align);
        // and MinikinUtils::xOffsetForTextAlign returns
        //     kCenter_Align: layout.getAdvance() * -0.5f
        //     kRight_Align:  -layout.getAdvance()
        // i.e. x names the run's centre / right end and the whole run moves,
        // not each glyph. We used to ignore the alignment entirely and draw
        // every run LEFT-aligned at x: solitaire_cg's RIGHT-aligned timer drawn
        // at (W - 9) started at x = W - 9 and fell off the screen, and anuto's
        // CENTER-aligned tower prices lost their last digit past the view edge.
        // drawTextRun / drawPosText / the char[] and CharSequence forms all
        // funnel through here, as they do into Canvas::drawText in hwui, so
        // they inherit it. The shadow below moves with the run, as the looper
        // pass does on a device.
        if (aligned) {
            x -= (align == Paint.Align.CENTER) ? advance * 0.5f : advance;
        }
        // AOSP drop shadow (Paint.setShadowLayer): draw the glyphs in the
        // shadow color at the +dx/+dy offset BEFORE the main text. The
        // soft blur (radius) is approximated by a 1px spread around the
        // offset so a small radius reads as a soft edge rather than a
        // hard duplicate. Only runs when a shadow is actually set, so
        // text without a shadow renders exactly as before (no regression).
        // v1.0.902: the "cheap soft spread" above drew the shadow string FIVE
        // times (centre + 4 cardinal +/-1px). Five overlapping composites of a
        // colour with alpha A give 1-(1-A)^5, so a deliberately TRANSLUCENT
        // shadow came out effectively opaque. Measured on frozenbubble, which
        // calls setShadowLayer(5, 5, 5, 0x7F050000): alpha 0x7F = 0.498, so the
        // stamps predict 1 - 0.502^5 = 0.968, and solving our rendered ink
        // against the known background gives 0.97. That exact agreement is the
        // identification. It painted a hard black slab under every menu label --
        // 3,186 near-black px inside the button column against the emulator's 0.
        // The spread was never a blur, it was an alpha bug.
        //
        // Skia blurs the glyph MASK once with SkBlurMaskFilter and composites it
        // at +dx/+dy under the main text, so blur properly and composite alpha
        // exactly once.
        if (p.hasShadowLayer()) {
            float sx = x + p.getShadowDx();
            float sy = y + p.getShadowDy();
            boolean blurred = p.getShadowRadius() > 0f
                && macapkDrawBlurredTextShadow(text, sx, sy,
                                               p.getShadowRadius(), p.getShadowColor());
            if (!blurred) {
                // radius 0 is a HARD shadow on AOSP too, and this is also the
                // safe fallback when the offscreen cannot be built.
                Color main = g.getColor();
                g.setColor(toAwtColor(p.getShadowColor()));
                macapkDrawSpaced(text, sx, sy, macapkPrimaryFont, p);
                g.setColor(main);
            }
        }
        macapkDrawSpaced(text, x, y, macapkPrimaryFont, p);
        if (decorated) macapkDrawTextDecorations(x, y, advance, p);
    }

    /** Gate: honour Paint.Align in drawText / drawTextOnPath. Default ON;
     *  MACAPK_TEXT_ALIGN=0 goes back to drawing every run left-aligned at x,
     *  the pre-2026-09-25 behaviour, so a fleet delta can be attributed. */
    private static final boolean MACAPK_TEXT_ALIGN =
        !"0".equals(System.getenv("MACAPK_TEXT_ALIGN"));
    /** Gate: draw UNDERLINE_TEXT_FLAG / STRIKE_THRU_TEXT_FLAG. Default ON;
     *  MACAPK_TEXT_DECORATIONS=0 restores the old behaviour of drawing neither
     *  (every UnderlineSpan, URLSpan, StrikethroughSpan and setPaintFlags
     *  underline in the fleet was silently dropped). */
    private static final boolean MACAPK_TEXT_DECORATIONS =
        !"0".equals(System.getenv("MACAPK_TEXT_DECORATIONS"));

    /** The run's advance as hwui's layout.getAdvance() reports it: the RAW
     *  advance, not measureText's Math.ceil of it. Paint.getRunAdvance with
     *  offset == end is exactly Paint's raw advance of the whole run (the
     *  same number measureText ceils), so alignment and decorations agree
     *  with what the app measured, including letter spacing and fallback
     *  runs, by construction rather than by re-deriving it here. */
    private static float macapkRunAdvance(String text, Paint p) {
        final int n = text.length();
        if (n == 0) return 0f;
        try {
            return p.getRunAdvance(text, 0, n, 0, n, false, n);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** hwui Canvas::drawTextDecorations (libs/hwui/hwui/Canvas.cpp,
     *  android14-release), called from SkiaCanvas::drawGlyphs right after the
     *  glyph blob with the ALIGNED x and the layout's total advance:
     *      left = x; right = x + length;
     *      underline: position = metrics.hasUnderlinePosition()
     *                     ? fUnderlinePosition : textSize * kStdUnderline_Top;
     *                 thickness likewise, else textSize * kStdUnderline_Thickness;
     *                 drawStroke(left, right, y + position, thickness, paint)
     *      strike:    position  = textSize * kStdStrikeThru_Top;
     *                 thickness = textSize * kStdStrikeThru_Thickness;
     *                 drawStroke(left, right, y + position, thickness, paint)
     *  with drawStroke = drawRect(left, top, right, top + max(thickness, 1), paint)
     *  -- the SAME paint, so its colour, alpha, shader, style and shadow looper
     *  all apply (SkiaCanvas::drawRect runs applyLooper).
     *
     *  Paint.getUnderlinePosition / getUnderlineThickness / getStrikeThru*
     *  already implement exactly that metric-or-constant choice (they mirror
     *  the same JNI getters in android_graphics_Paint.cpp), so this reads them
     *  instead of restating the constants. For a TrueType face AWT's underline
     *  offset is -post.underlinePosition / upem (sun.font.TrueTypeFont
     *  .setUnderlineMetrics), which is the value Skia's FreeType port computes
     *  for fUnderlinePosition, so Roboto's underline lands where a device puts it.
     *
     *  hwui calls this once per FONT RUN, each time over the full advance; that
     *  only differs from one call when the paint is translucent AND the text
     *  needed a fallback font, and is not reproduced here. */
    private void macapkDrawTextDecorations(float x, float y, float length, Paint p) {
        if (length <= 0f) return;
        final float left = x, right = x + length;
        if (p.isUnderlineText()) {
            float top = y + p.getUnderlinePosition();
            float th = Math.max(p.getUnderlineThickness(), 1.0f);
            drawRect(left, top, right, top + th, p);
        }
        if (p.isStrikeThruText()) {
            float top = y + p.getStrikeThruPosition();
            float th = Math.max(p.getStrikeThruThickness(), 1.0f);
            drawRect(left, top, right, top + th, p);
        }
    }

    /** Gate: the letter-spaced draw path. ON by default;
     *  MACAPK_DRAW_LETTER_SPACING=0 goes back to drawing one unspaced AWT run,
     *  which is what makes a fleet delta attributable to THIS rather than to
     *  the font-family work landing alongside it. */
    private static final boolean MACAPK_DRAW_LETTER_SPACING =
        !"0".equals(System.getenv("MACAPK_DRAW_LETTER_SPACING"));

    /**
     * drawText with android:letterSpacing honoured.
     *
     * ⚠ Paint has applied letter-spacing since v1.0.930/932, but Canvas had
     * ZERO letterSpacing references: drawText handed the whole string to one
     * `g.drawString`, which lays glyphs out with no inter-cluster advance at
     * all. So every Material label MEASURED spaced and DREW unspaced -- the
     * residual behind F-Droid's "Categories" reading meas=125 against painted
     * 114 -- and the wider the measure, the further left the ink sat inside its
     * own box under centre gravity.
     *
     * minikin's layout (LayoutCore.cpp, LayoutPiece ctor, android-11.0.0_r48):
     * half a letterSpace before the first glyph, a full one at each cluster
     * boundary, half after the last. Equivalently: each cluster occupies
     * `advance + letterSpace`, with the glyph drawn half a space into its own
     * cell. Total width is therefore `sum(advances) + n * letterSpace` for n
     * clusters, which is exactly what macapkRawAdvance charges -- so measure
     * and draw agree by construction rather than by coincidence.
     */
    private void macapkDrawSpaced(String text, float x, float y, Font primary, Paint p) {
        float ls = 0f;
        try { ls = p.getLetterSpacing(); } catch (Throwable ignored) {}
        float gap = ls * p.getTextSize();
        if (!MACAPK_DRAW_LETTER_SPACING || gap == 0f || text.isEmpty()) {
            macapkDrawStringRuns(text, x, y, primary);
            return;
        }
        // v1.0.2239: shape the WHOLE run first, then space its glyph clusters,
        // which is minikin's order (LayoutCore.cpp adds the gaps while walking
        // HarfBuzz's output). Drawing grapheme by grapheme made every ligature
        // impossible -- an icon font such as Material Symbols, whose "home" is a
        // single rlig glyph, drew the letter H instead -- and lost kerning across
        // clusters that measureText (one quantized vector) still charged. Same
        // vector, same cluster rule (MacApkFont.spacingClusterOrdinals) as the
        // measure side, so measure and draw agree by construction.
        if (MacApkFont.SHAPED_SPACING && MacApkFont.firstSpecial(text, primary) < 0) {
            MacApkFont.Quantized q = MacApkFont.quantize(g.getFont(), text,
                                                         MacApkFont.ligaFor(ls));
            int[] ord = (q == null) ? null : MacApkFont.spacingClusterOrdinals(q.glyphs, text);
            if (ord != null) {
                java.awt.font.GlyphVector gv = q.glyphs;
                final int ng = gv.getNumGlyphs();
                for (int i = 0; i <= ng; i++) {
                    java.awt.geom.Point2D gp = gv.getGlyphPosition(i);
                    // Glyph i sits half a gap in, plus one gap per cluster before
                    // its own; position ng (the total advance) carries every gap.
                    float shift = (i < ng) ? gap * (0.5f + ord[i]) : gap * ord[ng];
                    gv.setGlyphPosition(i, new java.awt.geom.Point2D.Float(
                        (float) gp.getX() + shift, (float) gp.getY()));
                }
                g.drawGlyphVector(gv, x, y);
                return;
            }
        }
        // Same UAX#29 segmentation the measure side counts with -- shared, not
        // re-derived, because a disagreement here is a whole gap per cluster.
        int[] bounds = android.graphics.Paint.macapkClusterBounds(text);
        float penX = x + gap * 0.5f;
        for (int k = 0; k + 1 < bounds.length; k++) {
            String cluster = text.substring(bounds[k], bounds[k + 1]);
            macapkDrawStringRuns(cluster, penX, y, primary);
            penX += android.graphics.Paint.macapkAdvanceIn(cluster, primary) + gap;
        }
    }

    /** Draw text split into runs by RESOLVED FONT, mirroring Android's
     *  per-codepoint font itemization (Minikin's FontCollection): each maximal
     *  run of characters resolving to the same chain font draws with that font
     *  and advances by that font's own advances, so a character the primary lacks
     *  (deskclock's U+25BA bullets against Roboto) renders through a fallback
     *  face instead of a .notdef box.
     *
     *  Strings the primary fully covers take the single-drawString fast path and
     *  are byte-identical to the previous behaviour -- that is what keeps every
     *  pure-ASCII screen in the fleet untouched.
     *
     *  Default-ignorable characters draw NOTHING: HarfBuzz's
     *  hb_ot_hide_default_ignorables replaces them with a zero-advance invisible
     *  glyph after shaping, so even when the font carries visible ink for them
     *  (Roboto's uni200E) real Android never paints it.
     *
     *  The font is restored on exit because the shadow pass re-enters with the
     *  same Graphics2D state. */
    private void macapkDrawStringRuns(String text, float x, float y, Font primary) {
        if (MacApkFont.firstSpecial(text, primary) < 0) {
            // ⚠ v1.0.937: draw the SAME GlyphVector Paint measured, with its
            // per-glyph positions already snapped to integer advances. Calling
            // g.drawString here instead would lay the glyphs out at AWT's float
            // advances while measureText charged the quantized ones -- a drift
            // of up to half a pixel per glyph, which is the exact class of
            // measure-vs-draw disagreement this file has now been bitten by
            // twice (the unspaced letter-spacing draw, and the metrics mode).
            MacApkFont.Quantized q = MacApkFont.quantize(g.getFont(), text);
            if (q != null) g.drawGlyphVector(q.glyphs, x, y);
            else           g.drawString(text, x, y);
            return;
        }
        final int n = text.length();
        final Font saved = g.getFont();
        float cx = x;
        int i = 0;
        final float sizePx = (primary != null) ? primary.getSize2D() : 0f;
        while (i < n) {
            // Mirror of Paint.macapkMeasureRuns: an emoji cluster advances the
            // pen by the measured emoji advance, so the glyphs AFTER it land
            // where measureText said they would. The cluster itself is still
            // painted by the JDK composite (we rasterize no colour font), so it
            // may sit narrow inside its box -- a far smaller defect than every
            // emoji-bearing string wrapping at the wrong point.
            int ee = MacApkFont.emojiClusterEnd(text, i, n);
            if (ee > i) {
                String ec = text.substring(i, ee);
                g.drawString(ec, cx, y);
                cx += MacApkFont.emojiAdvance(sizePx);
                i = ee;
                continue;
            }
            int cp = text.codePointAt(i);
            if (MacApkFont.isDefaultIgnorable(cp)) { i += Character.charCount(cp); continue; }
            // ASCII controls stay with the current run; they have no cmap entry
            // anywhere and AWT renders them invisibly.
            Font base = (cp < 0x20) ? null : MacApkFont.fallbackBaseFor(cp, primary);
            int runStart = i;
            i += Character.charCount(cp);
            while (i < n) {
                int cp2 = text.codePointAt(i);
                if (MacApkFont.isDefaultIgnorable(cp2)) break;
                Font b2 = (cp2 < 0x20) ? null : MacApkFont.fallbackBaseFor(cp2, primary);
                if (b2 != base) break;
                i += Character.charCount(cp2);
            }
            String run = text.substring(runStart, i);
            Font f = (base == null) ? primary : MacApkFont.atSizeOf(base, primary);
            g.setFont(f);
            g.drawString(run, cx, y);
            java.awt.FontMetrics rfm = g.getFontMetrics(f);
            cx += rfm.stringWidth(run);
        }
        g.setFont(saved);
    }
    /** Rasterize the glyphs into an offscreen ARGB image, blur its ALPHA channel,
     *  and draw the result at the shadow offset. Blurring alpha alone is exact
     *  here and cannot fringe: every pixel of the offscreen carries the same
     *  shadow RGB and only its coverage varies, which is precisely the mask Skia
     *  blurs. Returns false when the offscreen cannot be built, so the caller
     *  falls back to one hard stamp.
     *
     *  Skia radius to sigma: SkBlurMask::ConvertRadiusToSigma(r) = r*0.57735+0.5.
     *  Three separable box passes are the standard Gaussian approximation, and
     *  each pass is a running sum, so cost is linear in pixels rather than in
     *  kernel area (a real convolution at radius 5 would be ~500 multiplies per
     *  pixel, which is not affordable per frame). */
    private boolean macapkDrawBlurredTextShadow(String text, float sx, float sy,
                                                float radius, int shadowColor) {
        try {
            if (g == null || text == null || text.isEmpty()) return false;
            if (((shadowColor >>> 24) & 0xff) == 0) return true;   // nothing to draw
            java.awt.FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text);
            int asc = fm.getMaxAscent(), desc = fm.getMaxDescent();
            if (tw <= 0 || asc + desc <= 0) return false;
            float sigma = radius * 0.57735f + 0.5f;
            int box = Math.max(1, Math.min(16, Math.round(sigma)));
            int pad = box * 3 + 2;
            int iw = tw + pad * 2, ih = asc + desc + pad * 2;
            if ((long) iw * (long) ih > 2000000L) return false;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = img.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Same text-metrics mode as the real surface: the shadow is drawn
            // from the same string at the same size and is composited under it,
            // so a different advance mode here offsets the blur from its text.
            MacApkFont.applyTextMetricsHint(ig);
            ig.setFont(g.getFont());
            ig.setColor(toAwtColor(shadowColor));
            ig.drawString(text, (float) pad, (float) (pad + asc));
            ig.dispose();
            int[] px = img.getRGB(0, 0, iw, ih, null, 0, iw);
            macapkBoxBlurAlpha(px, iw, ih, box, shadowColor & 0x00ffffff);
            img.setRGB(0, 0, iw, ih, px, 0, iw);
            g.drawImage(img, Math.round(sx) - pad, Math.round(sy) - asc - pad, null);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Three separable box passes over the ALPHA channel of an INT_ARGB array, in
     *  place, then restamp every pixel's RGB with the shadow colour (the
     *  fully-transparent pixels came out of the offscreen as black, and blurring
     *  would otherwise darken the halo). */
    private static void macapkBoxBlurAlpha(int[] px, int w, int h, int box, int rgb) {
        int[] a = new int[w * h];
        for (int i = 0; i < px.length; i++) a[i] = (px[i] >>> 24) & 0xff;
        int[] t = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            macapkBoxBlurRows(a, t, w, h, box);
            macapkBoxBlurCols(t, a, w, h, box);
        }
        for (int i = 0; i < px.length; i++) {
            int al = a[i]; if (al > 255) al = 255; if (al < 0) al = 0;
            px[i] = (al << 24) | rgb;
        }
    }
    private static void macapkBoxBlurRows(int[] src, int[] dst, int w, int h, int r) {
        int win = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w, sum = 0;
            for (int i = -r; i <= r; i++) sum += src[base + Math.max(0, Math.min(w - 1, i))];
            for (int x = 0; x < w; x++) {
                dst[base + x] = sum / win;
                sum += src[base + Math.max(0, Math.min(w - 1, x + r + 1))]
                     - src[base + Math.max(0, Math.min(w - 1, x - r))];
            }
        }
    }
    private static void macapkBoxBlurCols(int[] src, int[] dst, int w, int h, int r) {
        int win = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int i = -r; i <= r; i++) sum += src[Math.max(0, Math.min(h - 1, i)) * w + x];
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = sum / win;
                sum += src[Math.max(0, Math.min(h - 1, y + r + 1)) * w + x]
                     - src[Math.max(0, Math.min(h - 1, y - r)) * w + x];
            }
        }
    }

    /** AOSP carries a String-specific start/end overload alongside the
     *  CharSequence one. Bytecode resolves on the exact descriptor, so a call
     *  site compiled against (String,int,int,float,float,Paint) never falls
     *  through to the CharSequence sibling even though String is a
     *  CharSequence -- without this the draw pass dies on NoSuchMethodError. */
    public void drawText(String text, int start, int end,
                          float x, float y, Paint p) {
        if (text == null) return;
        drawText(text.substring(start, end), x, y, p);
    }
    public void drawText(CharSequence text, int start, int end,
                          float x, float y, Paint p) {
        if (text == null) return;
        sGlobalText++;
        drawText(text.subSequence(start, end).toString(), x, y, p);
    }
    public void drawText(char[] text, int index, int count,
                          float x, float y, Paint p) {
        if (text == null) return;
        sGlobalText++;
        drawText(new String(text, index, count), x, y, p);
    }
    public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint p) {
        if (g == null || p == null || text == null || text.isEmpty()) return;
        if (path == null) return;
        sGlobalText++;
        // Real path-following text: walk the path with PathMeasure and place
        // each glyph at the cumulative advance distance, rotated to the path
        // tangent at that point, lifted by vOffset along the normal. Mirrors
        // AOSP's SkDraw::drawTextOnPath observable result. hOffset shifts the
        // whole run forward along the path; vOffset is the perpendicular
        // baseline offset (positive = away from the path on the +normal side).
        com.macapk.loader.GfxTrace.real("Canvas.drawTextOnPath");
        applyPaint(p);
        java.awt.Font font = deriveFont(p);
        g.setFont(font);
        java.awt.FontMetrics fm = g.getFontMetrics(font);

        PathMeasure pm = new PathMeasure(path, false);
        float pathLen = pm.getLength();
        if (pathLen <= 0f) { drawText(text, hOffset, vOffset, p); return; }

        float[] pos = new float[2];
        float[] tan = new float[2];
        float distance = hOffset;
        // hwui Canvas::drawTextOnPath: hOffset += MinikinUtils::
        // hOffsetForTextAlign(&paint, layout, path), which for CENTER is
        // -0.5f * (layout.getAdvance() - pathLength) and for RIGHT the full
        // (advance - pathLength). So CENTER centres the run on the middle of
        // the path and RIGHT ends it at the end of the path, both still shifted
        // by hOffset. The advance used is the sum of
        // the per-glyph advances this loop steps by, so the aligned run is
        // placed by the same layout that draws it.
        Paint.Align align = p.getTextAlign();
        if (MACAPK_TEXT_ALIGN && (align == Paint.Align.CENTER || align == Paint.Align.RIGHT)) {
            float adv = 0f;
            for (int i = 0; i < text.length(); i++) adv += fm.stringWidth(text.substring(i, i + 1));
            distance += ((align == Paint.Align.CENTER) ? -0.5f : -1f) * (adv - pathLen);
        }
        java.awt.geom.AffineTransform saved = g.getTransform();
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            float w = fm.stringWidth(ch);
            // Place the glyph centered at distance + half its advance.
            float mid = distance + w * 0.5f;
            if (mid > pathLen) break;
            if (pm.getPosTan(mid, pos, tan)) {
                double angle = Math.atan2(tan[1], tan[0]);
                // Perpendicular (normal) direction for vOffset.
                float nx = -tan[1];
                float ny = tan[0];
                float px = pos[0] + nx * vOffset;
                float py = pos[1] + ny * vOffset;
                g.translate(px, py);
                g.rotate(angle);
                // Draw the glyph centered horizontally on the tangent point.
                g.drawString(ch, -w * 0.5f, 0f);
                g.setTransform(saved);
            }
            distance += w;
        }
        g.setTransform(saved);
    }
    /** AOSP char[]+index+count sibling of drawTextOnPath. Delegates onto the
     *  real PathMeasure-driven placement above; it exists separately because
     *  bytecode resolves on the exact descriptor. */
    public void drawTextOnPath(char[] text, int index, int count, Path path,
                               float hOffset, float vOffset, Paint p) {
        if (text == null) return;
        drawTextOnPath(new String(text, index, count), path, hOffset, vOffset, p);
    }

    // --- RenderNode replay (hardware-layer path) ----------------------
    /** AOSP RecordingCanvas.drawRenderNode(RenderNode) -- replays a
     *  child node's recorded display list with the node's position,
     *  transform, clip and alpha applied. Jetpack Compose's GraphicsLayer
     *  / RenderNodeLayer takes this path when the destination canvas is
     *  hardware accelerated (our RecordingCanvas reports true). An op-list
     *  node (MACAPK_RENDERNODE_OPLIST, the default) replays its ops here by
     *  reference: see macapkReplayOpListNode. A BITMAP node (the gate off, or
     *  a node that fell back) recorded into an offscreen bitmap via
     *  beginRecording; here we save, position+transform+clip, then blit that
     *  bitmap with an alpha composite. No-op if the node never recorded (the
     *  layer used the software re-invoke-drawBlock path instead). */
    private static final boolean sRenderNodeTrace =
        System.getenv("MACAPK_RENDERNODE_TRACE") != null;
    /** v1.0.997 bisect gate (default ON): `MACAPK_PUNCH_PROPAGATE=0` keeps the
     *  punch local to whatever canvas it was issued on, which is the
     *  pre-v1.0.997 behaviour (a hole that never reaches the frame when a
     *  RenderNode interposes). */
    private static final boolean PUNCH_PROPAGATE =
            !"0".equals(System.getenv("MACAPK_PUNCH_PROPAGATE"));
    /** MACAPK_PUNCH_TRACE=1: log the first 16 punch applications with the canvas
     *  they landed on and the device rect, so the propagation chain from the
     *  SurfaceView up to the frame canvas can be read off one run. */
    private static final boolean PUNCH_TRACE =
            "1".equals(System.getenv("MACAPK_PUNCH_TRACE"));
    private static int sPunchTraced;

    /** AOSP Canvas.punchHole(left, top, right, bottom, rx, ry, alpha)
     *  (BaseCanvas.java:701, BaseRecordingCanvas.java:628 -> nPunchHole), the op
     *  SurfaceView.clearSurfaceViewPort issues so the surface composited BEHIND
     *  the window shows through at the view's bounds, while siblings drawn after
     *  the SurfaceView (a player's transport controls) composite on top.
     *
     *  HWUI's implementation is DST_OUT, not CLEAR -- libs/hwui/SkiaCanvas.cpp
     *  :267-273 (fetched 2026-08-18):
     *      SkPaint paint; paint.setColor(SkColors::kBlack);
     *      paint.setAlphaf(alpha); paint.setBlendMode(SkBlendMode::kDstOut);
     *      mCanvas->drawRRect(rect, paint);
     *  The two are numerically identical for an opaque source; DST_OUT is what
     *  carries the View's alpha (dst *= 1 - alpha), so a half-faded SurfaceView
     *  punches a half-hole. AlphaComposite.getInstance(DST_OUT, a) multiplies the
     *  source alpha by a, which is exactly setAlphaf(alpha) on an opaque paint.
     *
     *  The second half -- recording the hole on the node being recorded -- has no
     *  AOSP counterpart because AOSP does not need one; see RenderNode.Punch for
     *  why our buffered replay does. */
    public void punchHole(float left, float top, float right, float bottom,
                          float rx, float ry, float alpha) {
        if (g == null) return;
        float w = right - left, h = bottom - top;
        if (w <= 0f || h <= 0f) return;
        // A hole changes the frame away from the plain clear, so a frame whose
        // only op is the punch must not be elided (see macapkMarkInk). Counted
        // HERE and not in macapkPunchShape: a hole re-applied from a replayed
        // child node is already counted by drawRenderNode.
        macapkMarkInk();
        java.awt.Shape shape = (rx > 0f || ry > 0f)
            ? new java.awt.geom.RoundRectangle2D.Float(left, top, w, h, rx * 2f, ry * 2f)
            : (java.awt.Shape) new Rectangle2D.Float(left, top, w, h);
        macapkPunchShape(shape, alpha);
    }

    /** The punch core, shared by punchHole (a fresh hole) and drawRenderNode (a
     *  hole a child node recorded, being re-applied one level up). Applies
     *  DST_OUT here AND, when this canvas is recording a RenderNode, hands the
     *  device-space shape to that node so the next replay carries it further up.
     *  Recursion terminates at the frame canvas, which is not a RecordingCanvas. */
    void macapkPunchShape(java.awt.Shape shape, float alpha) {
        if (g == null || shape == null) return;
        float a = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        if (a <= 0f) return;   // a fully transparent SurfaceView punches nothing
        java.awt.Composite oldComp = g.getComposite();
        java.awt.Paint oldPaint = g.getPaint();
        try {
            g.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.DST_OUT, a));
            g.setColor(java.awt.Color.BLACK);   // colour is irrelevant to DST_OUT
            g.fill(shape);
        } catch (Throwable t) {
            com.macapk.loader.GfxTrace.caught("Canvas.punchHole", t);
        } finally {
            g.setComposite(oldComp);
            g.setPaint(oldPaint);
        }
        if (PUNCH_TRACE && sPunchTraced < 16) {
            sPunchTraced++;
            RenderNode ow = (this instanceof RecordingCanvas)
                    ? ((RecordingCanvas) this).macapkOwner() : null;
            java.awt.Rectangle dev = g.getTransform()
                    .createTransformedShape(shape).getBounds();
            com.macapk.loader.NativeLog.i("Mac.apk.Canvas",
                "punch #" + sPunchTraced + " on " + getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(this))
                + " recording=" + (this instanceof RecordingCanvas)
                + " owner=@" + (ow == null ? "none"
                    : Integer.toHexString(System.identityHashCode(ow)))
                + " dev=" + dev.x + "," + dev.y + " " + dev.width + "x" + dev.height
                + " a=" + a);
        }
        if (!PUNCH_PROPAGATE) return;
        if (this instanceof RecordingCanvas) {
            RenderNode owner = ((RecordingCanvas) this).macapkOwner();
            if (owner != null) {
                // Device space of THIS recording == the owner node's recording
                // space, which is the space drawRenderNode re-establishes before
                // it blits, so storing the CTM-mapped shape needs no extra
                // bookkeeping at replay time.
                try {
                    owner.macapkAddPunch(
                        g.getTransform().createTransformedShape(shape), a);
                } catch (Throwable ignored) {}
            }
        }
    }

    public void drawRenderNode(RenderNode node) {
        if (RenderNode.macapkStats && node != null) {
            RenderNode.sStReplays.incrementAndGet();
            if (g == null) RenderNode.sStReplayNoG.incrementAndGet();
        }
        if (g == null || node == null) return;
        // v1.0.1055: A REPLAY IS INK. Counting it is load-bearing, not tidiness.
        //
        // ViewCanvasDriver's present-elision gate decides "did this traversal
        // draw anything" by diffing canvas.macapkDrawOps across rootView.draw()
        // -- deliberately by OPS, not by scanning pixels, because a pixel scan
        // of a 25 MB frame costs more than the present it is avoiding. But the
        // counter predates the RenderNode path and had exactly three increment
        // sites (drawBitmap, drawVertices, applyPaint); replaying a display list
        // incremented NOTHING, and this method composites through g.drawImage so
        // it misses drawBitmap's site too.
        //
        // Consequence, measured on Thunderbird: a 100% Compose screen is drawn
        // entirely by replays, so it wrote 177,281 pixels while reporting
        // sFrameDrewInk=false. The elision then latched permanently and the
        // overlay kept the last non-elided present -- the SPLASH frame. The tell
        // is arithmetic: rendercheck read the canvas at coverage 0.3% and the
        // splash frame is 4561/1451168 = 0.3143%.
        //
        // The elision itself stays correct for the case it exists for (a native
        // EGL game with an empty View tree uploading a 25 MB transparent frame
        // twice a second): an empty tree issues no replays either, so it still
        // elides.
        macapkDrawOps++; sGlobalDrawOps++; macapkGenBump();
        // Remember who flattened this node's pixels, so the node can tell us
        // when that copy has gone stale. See RenderNode's stale-ancestor block:
        // our replay copies pixels where hwui's stores a reference, so without
        // this link a leaf that re-records never reaches the screen.
        // Registered BEFORE the rec==null early-out on purpose: a node whose
        // first replay found no recording still needs the link for its second.
        if (this instanceof RecordingCanvas) {
            RenderNode owner = ((RecordingCanvas) this).macapkOwner();
            if (owner != null) node.macapkSetReplayParent(owner);
        }
        // Stage A (MACAPK_RENDERNODE_OPLIST): a node recorded as an OP LIST is
        // replayed by reference. Only reachable here when THIS canvas draws
        // immediately (the frame, a Bitmap canvas, a layer, a bitmap-mode
        // recording): an op-list RecordingCanvas records the call instead.
        final MacApkDisplayList dl = node.macapkDisplayList();
        if (dl != null) { macapkReplayOpListNode(node, dl); return; }
        Bitmap rec = node.macapkRecordedBitmap();
        if (RenderNode.macapkStats && rec == null) RenderNode.sStReplayNoRec.incrementAndGet();
        if (sRenderNodeTrace) com.macapk.loader.NativeLog.i("Mac.apk.RenderNode",
            "drawRenderNode id=" + Integer.toHexString(System.identityHashCode(node))
            + " hw=" + isHardwareAccelerated() + " rec="
            + (rec != null) + " recNonNull=" + (rec != null ? rec.macapkAwtImage() != null : false)
            + " pos=" + node.getLeft() + "," + node.getTop()
            + " " + node.getWidth() + "x" + node.getHeight() + " alpha=" + node.macapkAlpha());
        if (rec == null) return;
        int sc = save();
        translate(node.getLeft(), node.getTop());
        node.macapkApplyTransform(this);
        node.macapkApplyClip(this);
        float a = node.macapkAlpha();
        // v1.0.997: re-apply the holes this node recorded, BEFORE its pixels land.
        // Order matters and mirrors AOSP's temporal order: on a device the
        // SurfaceView punches and the siblings drawn after it paint over the hole.
        // Here those siblings are already inside `rec`, so punching first and
        // blitting second reproduces it; punching after would erase them.
        // The save/translate/transform/clip above have made user space
        // node-local, which is the space the shapes were stored in.
        java.util.ArrayList<RenderNode.Punch> holes = node.macapkPunches();
        if (holes != null) {
            for (int hi = 0; hi < holes.size(); hi++) {
                RenderNode.Punch ph = holes.get(hi);
                macapkPunchShape(ph.shape, ph.alpha * a);
            }
        }
        java.awt.Composite old = null;
        if (a < 1f) {
            old = g.getComposite();
            float ca = a < 0f ? 0f : (a > 1f ? 1f : a);
            g.setComposite(java.awt.AlphaComposite.getInstance(
                java.awt.AlphaComposite.SRC_OVER, ca));
        }
        BufferedImage src = bitmapAsImage(rec);
        // Apply the immutable Skia ImageFilter DAG to this node's recorded source. The
        // legacy software blur remains only for effects without a native filter.
        RenderEffect re = node.macapkRenderEffect();
        if (src != null && re != null && re.macapkIsBlur()) {
            if (re.hasSkiaFilter()) {
                src = macapkApplySkiaFilter(src, re.getNativeFilter());
            } else {
                src = blurImage(src, Math.max(re.macapkBlurRadiusX(), re.macapkBlurRadiusY()));
            }
        }
        if (RenderNode.macapkStats) {
            // "Did the replay reach any pixels?" -- after position + transform
            // + clip are applied, an empty device clip means the layer's
            // content was clipped entirely out of the destination, which looks
            // identical to "never drew" in the final frame.
            boolean clippedOut;
            try {
                java.awt.Rectangle cb = g.getClipBounds();
                clippedOut = (cb != null && (cb.width <= 0 || cb.height <= 0));
            } catch (Throwable t) { clippedOut = false; }
            if (clippedOut) RenderNode.sStReplayClipped.incrementAndGet();
            else if (src != null) RenderNode.sStReplayOk.incrementAndGet();
        }
        // Same trace family as the drawBitmap blits, on purpose: this replay is
        // ALSO a bitmap-into-bitmap copy, it just goes through g.drawImage
        // rather than drawBitmap, so a trace on drawBitmap alone cannot see it
        // and the chain looks like it stops here when it does not. Printing the
        // recorded bitmap's identity here lets a reader FOLLOW one image from
        // the drawable that painted it, through each offscreen it is copied
        // into, to the surface that is actually presented -- and see which link
        // is missing.
        macapkTraceBlit("rendernode", rec,
            "pos=" + node.getLeft() + "," + node.getTop()
            + " " + node.getWidth() + "x" + node.getHeight() + " alpha=" + a);
        if (src != null) g.drawImage(src, 0, 0, null);
        if (old != null) g.setComposite(old);
        restoreToCount(sc);
    }

    /** Replay an op-list RenderNode onto this (immediate) canvas, applying the
     *  node's properties LIVE, in hwui's order (libs/hwui/pipeline/skia/
     *  RenderNodeDrawable.cpp android14-release, forceDraw, setViewProperties
     *  :364-434 and drawContent :215-360):
     *   1. RenderNode::nothingToDraw (RenderNode.h) skips the whole node for
     *      alpha <= 0, a zero scale, or a clip-to-outline with an EMPTY outline;
     *   2. translate(left, top), then the transform (our macapkApplyTransform,
     *      the same composition the pre-change path used);
     *   3. a clipToBounds node whose bounds miss the clip is skipped, :231
     *      `bool quickRejected = properties.getClipToBounds() &&
     *      canvas->quickReject(bounds);` (macapkQuickRejectChild: exact, and it
     *      also rejects against the device when no clip was set);
     *   4. the node's kind (RenderNode.macapkLayerKind). A LAYER -- a
     *      compositing layer, a blur, and, the everyday case, an overlapping
     *      alpha < 1 node, which RenderProperties::promotedToLayer makes a
     *      RenderLayer -- and a ModulateAlpha node first run the hole pre-pass
     *      (MacApkDisplayList.replayPunches, hwui's TransformCanvas pass,
     *      :295-299): a punchHole inside the node, or below it, then reaches
     *      THIS canvas although the content goes to a layer;
     *   5. the content. Alpha < 1: saveLayerAlpha(bounds, (int)(alpha * 255))
     *      where bounds is the clipping rect for the node's clip flags, or
     *      (0, 0, w, h) with none, and only the outline clip is left -- the
     *      pixels a node-sized RenderLayer composited at alpha gives, and what
     *      setViewProperties does itself for a node that does not fit a layer
     *      (:397-406). ModulateAlpha (hasOverlappingRendering false: hwui
     *      multiplies the alpha into each paint) takes the same layer, which is
     *      what the pre-change path did for every node. setUseCompositingLayer:
     *      an isolated offscreen composited with the layer paint, here a bounded
     *      saveLayer. A blur needs the layer's PIXELS, so its ops go to a
     *      node-sized bitmap that is blurred and composited exactly as the
     *      pre-change path composited every node (the box blur is unchanged).
     *      Otherwise the clip, and the ops draw straight onto this canvas: a
     *      CLEAR or a punchHole reaches the frame, as on hwui.
     *  A layer's content re-renders on every replay; hwui keeps a clean layer
     *  between frames, which this path does not (yet): the measured cost is in
     *  docs/rendernode-oplist-stage-a_2026-09-26.md. */
    private void macapkReplayOpListNode(RenderNode node, MacApkDisplayList dl) {
        if (sRenderNodeTrace) com.macapk.loader.NativeLog.i("Mac.apk.RenderNode",
            "drawRenderNode id=" + Integer.toHexString(System.identityHashCode(node))
            + " oplist ops=" + dl.size() + " pos=" + node.getLeft() + "," + node.getTop()
            + " " + node.getWidth() + "x" + node.getHeight() + " alpha=" + node.macapkAlpha());
        if (dl.size() == 0 || node.macapkNothingToDraw()) return;
        final float a = node.macapkAlpha();
        final int sc = save();
        try {
            translate(node.getLeft(), node.getTop());
            node.macapkApplyTransform(this);
            if (node.getClipToBounds()
                    && macapkQuickRejectChild(0, 0, node.getWidth(), node.getHeight())) return;
            final int kind = node.macapkLayerKind();
            if ((kind == RenderNode.KIND_LAYER || kind == RenderNode.KIND_MODULATE)
                    && dl.hasHolePunches()) {
                // Before the layer, under the node's clip as setViewProperties
                // leaves it: a layer's alpha < 1 drops the bounds clip.
                final int ps = save();
                if (kind == RenderNode.KIND_LAYER) node.macapkApplyLayerClip(this);
                else node.macapkApplyClip(this);
                dl.replayPunches(this, kind == RenderNode.KIND_MODULATE
                        && !MacApkDisplayList.TEST_PREPASS_NOFACTOR ? a : 1f,
                        kind == RenderNode.KIND_LAYER);
                restoreToCount(ps);
            }
            final RenderEffect re = node.macapkRenderEffect();
            final boolean blur = re != null && re.macapkIsBlur();
            if (!blur && node.getUseCompositingLayer()) {
                // An hwui RenderLayer with no effect: the content goes to an
                // isolated node-sized offscreen, composited with the layer
                // paint (its alpha x the node alpha, and its blend mode) --
                // layerNeedsPaint, RenderNodeDrawable.cpp:182-193. A saveLayer
                // with bounds is exactly that on this canvas (always a real,
                // pooled layer; its composite is clipped to the ink box), and it
                // renders the content in device space, so a transformed layer is
                // not resampled. The clip rect and outline then clip what lands.
                final Paint lp = node.macapkLayerPaint();
                final int la = (int) ((lp != null ? lp.getAlpha() : 255) * Math.max(0f, Math.min(1f, a)));
                if (la <= 0) return;
                final Paint layer = (lp != null) ? new Paint(lp) : new Paint();
                layer.setAlpha(la);
                saveLayer(0, 0, node.getWidth(), node.getHeight(), layer);
                node.macapkApplyClip(this);
                if (RenderNode.macapkStats) RenderNode.sStReplayOk.incrementAndGet();
                dl.replay(this);
                return;
            }
            if (blur) {
                node.macapkApplyClip(this);
                final int w = Math.max(1, node.getWidth()), h = Math.max(1, node.getHeight());
                final Bitmap off = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                dl.replay(new Canvas(off));
                BufferedImage src = bitmapAsImage(off);
                if (src == null) return;
                if (re.hasSkiaFilter()) {
                    src = macapkApplySkiaFilter(src, re.getNativeFilter());
                } else {
                    src = blurImage(src, Math.max(re.macapkBlurRadiusX(), re.macapkBlurRadiusY()));
                }
                // The layer paint's alpha times the node alpha, in 8 bits, as
                // hwui's layerNeedsPaint does: paint->setAlpha(alpha() * multiplier).
                final Paint lp = node.macapkLayerPaint();
                final int la = (int) ((lp != null ? lp.getAlpha() : 255) * Math.max(0f, Math.min(1f, a)));
                if (la <= 0) return;
                macapkTraceBlit("rendernode-layer", off, "pos=" + node.getLeft() + "," + node.getTop()
                    + " " + node.getWidth() + "x" + node.getHeight() + " alpha=" + a);
                java.awt.Composite old = g.getComposite();
                java.awt.Composite composite = bitmapRuleFor(lp);
                if (composite == null) composite = java.awt.AlphaComposite.SrcOver;
                if (composite instanceof java.awt.AlphaComposite ac) {
                    composite = java.awt.AlphaComposite.getInstance(ac.getRule(), la / 255f);
                } else if (composite instanceof BlendComposite bc) {
                    composite = bc.withAlpha(la);
                }
                g.setComposite(composite);
                g.drawImage(src, 0, 0, null);
                g.setComposite(old);
                if (RenderNode.macapkStats) RenderNode.sStReplayOk.incrementAndGet();
                return;
            }
            if (a < 1f) {
                // Both overlapping-rendering values take the layer; see the header.
                final float[] lb = node.macapkLayerBounds();
                if (!(lb[2] > lb[0] && lb[3] > lb[1])) return;   // an empty layer takes nothing
                saveLayerAlpha(lb[0], lb[1], lb[2], lb[3], (int) (a * 255));
                node.macapkApplyOutlineClip(this);
            } else {
                node.macapkApplyClip(this);
            }
            if (RenderNode.macapkStats) RenderNode.sStReplayOk.incrementAndGet();
            dl.replay(this);
        } finally {
            restoreToCount(sc);
        }
    }

    // Z-ordering support (public API since API 29). Per AOSP
    // frameworks/base/graphics/java/android/graphics/Canvas.java, the BASE
    // Canvas defines enableZ()/disableZ() as empty no-ops; only the hardware
    // RecordingCanvas (DisplayListCanvas) overrides them to reorder
    // drawRenderNode() calls by elevation/translationZ and render their
    // shadows. Our Canvas is software and draws in declaration order, so the
    // no-op is the AOSP-faithful behavior (we forgo only the drop-shadow
    // effect, which is cosmetic; the content itself draws correctly).
    //
    // These MUST exist: Jetpack Compose's draw pass calls canvas.enableZ()
    // before drawing any elevated content -- a Surface, Card, or any
    // Modifier.shadow()/Modifier.graphicsLayer{ shadowElevation } node
    // (ComposeViewGroup.dispatchDraw / AndroidComposeView). Without the
    // method, the FIRST elevated node throws NoSuchMethodError mid-draw,
    // which aborts the entire View.draw traversal -- so that node AND every
    // sibling drawn after it stays blank. This was the blocker behind
    // Reddit's WelcomeV2 login screen (its content sits inside an elevated
    // RPL Surface) and any modern Compose UI using elevation. Verified via
    // debug/composetest: a Modifier.shadow() row plus all rows after it went
    // blank with "rootView.draw threw NoSuchMethodError: enableZ()".
    public void enableZ() { /* software canvas: no Z reorder, no shadow */ }
    public void disableZ() { /* software canvas: no Z reorder, no shadow */ }

    // --- Bitmap blits -------------------------------------------------
    //
    // ⚠ Paint.isFilterBitmap() USED TO BE IGNORED ENTIRELY, so every scaled
    // bitmap in the fleet was nearest-neighbour. `new Paint()` has
    // FILTER_BITMAP_FLAG set on a device (Paint.HIDDEN_DEFAULT_PAINT_FLAGS,
    // mirroring AOSP), so essentially every non-1:1 drawBitmap is BILINEAR on
    // real Android and none was here: scaled icons, photos, BitmapDrawable at a
    // density mismatch, NinePatch stretches.
    //
    // MEASURED (docs/fwprobe-rasm-divergences_2026-08-10.md, cluster C7),
    // RASM.bmp.dst2x_filter -- an 8x8 source blitted into a 16x16 dst with
    // filterBitmap ON:
    //     reference  n=17  16,16=ff9fbf2f   (bilinear)
    //     ours       n=5   16,16=ffffff00   (raw source colour = nearest)
    //
    // THE GATE IS SKIA'S OWN, NOT A HEURISTIC. SkDraw::drawBitmap takes the
    // unfiltered sprite path when SkTreatAsSprite(matrix, dimensions, paint)
    // holds (src/core/SkDraw.cpp:1090-1092, android11-release):
    //
    //     if (bitmap.colorType() != kAlpha_8_SkColorType
    //         && SkTreatAsSprite(matrix, bitmap.dimensions(), *paint)) {
    //
    // That is what keeps a 1:1 blit crisp. It also PRESERVES the property the
    // v1.0.683-era comment on drawBitmap below was protecting: a fractional
    // TRANSLATE must reposition without manufacturing interpolation colours.
    // Skia's rule says exactly that -- with antialiasing off, a translate-only
    // matrix is a sprite whatever its fractional part (the "quick success
    // check" below) -- so pixel-art sprite blits stay nearest and only genuine
    // SCALING interpolates. (The v1.0.683 fix itself was a different subsystem
    // entirely: the GLES path's glTexImage2D MIN/WRAP substitution in
    // native_methods.cpp GLES20_glTexImage2D_buf, since retired -- see
    // docs/gles20-teximage-param-substitution-retired_2026-09-27.md. Flappy's
    // colour band goes through that path, not through here.)
    //
    // Default ON; MACAPK_FILTER_BITMAP=0 restores nearest-neighbour everywhere
    // so the fleet effect can be A/B'd in one run.
    private static final boolean MACAPK_FILTER_BITMAP =
        !"0".equals(System.getenv("MACAPK_FILTER_BITMAP"));

    /** Skia's SkTreatAsSprite, transcribed from external/skia
     *  src/core/SkMatrix.cpp:1632-1685 (android11-release):
     *
     *      static const unsigned kAntiAliasSubpixelBits = 4;
     *      const unsigned subpixelBits = paint.isAntiAlias() ? kAntiAliasSubpixelBits : 0;
     *      // quick reject on affine or perspective
     *      if (mat.getType() & ~(kScale_Mask | kTranslate_Mask)) return false;
     *      // quick success check
     *      if (!subpixelBits && !(mat.getType() & ~kTranslate_Mask)) return true;
     *      // mapRect supports negative scales, so we eliminate those first
     *      if (mat.getScaleX() < 0 || mat.getScaleY() < 0) return false;
     *      SkRect dst; SkIRect isrc = SkIRect::MakeSize(size);
     *      { SkRect src; src.set(isrc); mat.mapRect(&dst, src); }
     *      isrc.offset(SkScalarRoundToInt(mat.getTranslateX()),
     *                  SkScalarRoundToInt(mat.getTranslateY()));
     *      if (subpixelBits) { isrc <<= subpixelBits; dst *= (1 << subpixelBits); }
     *      SkIRect idst; dst.round(&idst);
     *      return isrc == idst;
     *
     *  i.e. "would the computed dst-rect align such that there is a 1-to-1
     *  correspondence between src and dst pixels" (SkMatrixUtils.h:17-25).
     *  Java2D has no perspective, so the affine reject is the shear test, and
     *  a rotation (shear != 0) is rejected exactly as Skia's kAffine_Mask does.
     *  SkScalarRoundToInt is floor(x + 0.5), which is what Math.floor(v + 0.5)
     *  spells here; SkRect::round applies it to all four edges. */
    private static boolean macapkTreatAsSprite(AffineTransform mat, int w, int h,
                                               boolean antiAlias) {
        if (mat == null) return true;
        final int subpixelBits = antiAlias ? 4 : 0;
        final double sx = mat.getScaleX(), sy = mat.getScaleY();
        // quick reject on affine (shear/rotation)
        if (mat.getShearX() != 0d || mat.getShearY() != 0d) return false;
        final boolean unitScale = (sx == 1d && sy == 1d);
        // quick success check: translate-only, no AA -- a sprite whatever the
        // fractional translate is. THIS is the pixel-art-preserving branch.
        if (subpixelBits == 0 && unitScale) return true;
        if (sx < 0d || sy < 0d) return false;
        final double tx = mat.getTranslateX(), ty = mat.getTranslateY();
        // dst = mat.mapRect({0,0,w,h}); no shear, non-negative scales, so it is
        // already sorted and reduces to the four edges below.
        double dl = tx, dt = ty, dr = tx + w * sx, db = ty + h * sy;
        long il = macapkRoundToInt(tx),      it = macapkRoundToInt(ty);
        long ir = il + w,                    ib = it + h;
        if (subpixelBits != 0) {
            final int shift = subpixelBits;
            final double scale = 1 << shift;
            il <<= shift; it <<= shift; ir <<= shift; ib <<= shift;
            dl *= scale; dt *= scale; dr *= scale; db *= scale;
        }
        return il == macapkRoundToInt(dl) && it == macapkRoundToInt(dt)
            && ir == macapkRoundToInt(dr) && ib == macapkRoundToInt(db);
    }
    /** SkScalarRoundToInt: floor(x + 0.5). */
    private static long macapkRoundToInt(double v) { return (long) Math.floor(v + 0.5d); }

    /** Should this blit interpolate? Paint says yes AND Skia's sprite rule says
     *  the blit is not 1:1. `blit` maps the SOURCE PIXEL BLOCK (0,0,w,h) into
     *  canvas user space; it is composed with the live CTM first, exactly as
     *  SkDraw::drawBitmap does with `matrix.setConcat(*fMatrix, prematrix)`
     *  (SkDraw.cpp:1083). A null Paint means no Java Paint reached the native
     *  layer at all, so Skia uses a default SkPaint with filtering off. */
    private boolean macapkFilterBlit(Paint p, AffineTransform blit, int w, int h) {
        if (!MACAPK_FILTER_BITMAP || p == null || w <= 0 || h <= 0) return false;
        if (!p.isFilterBitmap()) return false;
        AffineTransform eff;
        try {
            eff = new AffineTransform(g.getTransform());
        } catch (Throwable t) { return false; }
        if (blit != null) eff.concatenate(blit);
        return !macapkTreatAsSprite(eff, w, h, p.isAntiAlias());
    }
    /** Turn KEY_INTERPOLATION on for one blit; returns the token to restore
     *  (null when nothing was changed, so the paired call is a no-op). Java2D's
     *  unset default is nearest-neighbour, which is the state this Canvas is
     *  in everywhere else -- macapkInitHints never sets KEY_INTERPOLATION. */
    private Object macapkBeginFilter(boolean filter) {
        if (!filter) return null;
        Object prev = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        return (prev != null) ? prev
                              : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
    }
    private void macapkEndFilter(Object restore) {
        if (restore != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, restore);
    }

    // Diagnostic: the CONSUMER half of MACAPK_TRACE_VECTORDRAW. A vector that
    // rasterizes correctly into an offscreen Bitmap (Coil's
    // DrawableUtils.convertToBitmap does exactly that) and then never reaches a
    // real surface is invisible to every trace on the producing side -- the
    // VectorDrawable traces all report SUCCESS. This says whether the resulting
    // bitmap is ever blitted, into what, and where.
    //
    // ONE env var on purpose (the same one), because two switches for the two
    // halves of one question is two things to keep in step. Read once into a
    // static: drawBitmap is hot and getenv is not free.
    private static final boolean TRACE_BLIT =
        "1".equals(System.getenv("MACAPK_TRACE_VECTORDRAW"));
    // Bounded so an enabled trace cannot bury the log: a 60 fps screen issues
    // these by the thousand. The budget is per SOURCE image rather than global
    // -- a global cap is spent entirely on whatever draws first, and the point
    // of this trace is to follow ONE image through every surface it is copied
    // into, which needs the rare source to still get a line after the common
    // one has drawn ten thousand times.
    private static final java.util.HashMap<Integer, Integer> sBlitTraceSeen =
        new java.util.HashMap<>();
    private static final int BLIT_TRACE_PER_SRC = 4;
    private void macapkTraceBlit(String how, Bitmap b, String dst) {
        if (!TRACE_BLIT || b == null) return;
        int c;
        synchronized (sBlitTraceSeen) {
            int key = System.identityHashCode(b);
            Integer n = sBlitTraceSeen.get(key);
            c = (n == null) ? 0 : n;
            if (c >= BLIT_TRACE_PER_SRC) return;
            sBlitTraceSeen.put(key, c + 1);
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("blit ").append(how)
              .append(" src=@").append(Integer.toHexString(System.identityHashCode(b)))
              .append(' ').append(b.getWidth()).append('x').append(b.getHeight())
              .append(" dst=").append(dst).append(" into ").append(macapkTraceState());
            // First sighting of a given image only: WHO is copying it. Which
            // pipeline issues a copy decides where a lost image was lost, and
            // the surrounding sizes alone cannot tell a layer re-record from an
            // unrelated scratch buffer of the same dimensions.
            if (c == 0) {
                StackTraceElement[] st = new Throwable().getStackTrace();
                for (int i = 2; i < st.length && i <= 12; i++) sb.append("\n    ").append(st[i]);
            }
            com.macapk.loader.NativeLog.i("VectorDrawable", sb.toString());
        } catch (Throwable ignored) {}
    }

    public void drawBitmap(Bitmap b, float x, float y, Paint p) {
        if (!TRACE_DRAWCOST) { drawBitmapUntraced(b, x, y, p); return; }
        final long t0 = System.nanoTime();
        try { drawBitmapUntraced(b, x, y, p); } finally { drawCost(t0, "bmp-xy "+bdim(b), p); }
    }
    private void drawBitmapUntraced(Bitmap b, float x, float y, Paint p) {
        if (g == null || b == null) return;
        sGlobalBitmap++;
        macapkTraceBlit("xy", b, "(" + x + "," + y + ")");
        com.macapk.loader.GfxTrace.real("Canvas.drawBitmap");
        macapkDrawOps++; sGlobalDrawOps++; macapkGenBump();
        // Blits don't go through applyPaint; set the paint-alpha SrcOver
        // composite (so a Clear left by a preceding blend-mode shape op cannot
        // erase this image, AND a Coil/Glide cross-fade paint alpha fades it)
        // and apply the paint's color filter (icon/image tint) to the source
        // image first (v1.0.433).
        applyBitmapComposite(p);
        BufferedImage src = prepImage(b, p);
        // Was g.drawImage(src, (int) x, (int) y, null) -- a float position
        // truncated to whole pixels before the blit, so a sprite at a fractional
        // position (or any blit under a scaled CTM) snapped.
        // MEASURED: at integer x/y an AffineTransform translate is byte-identical
        // to the int form, and interpolation is OFF here, so a fractional
        // translate REPOSITIONS WITHOUT manufacturing interpolation colours.
        // That is the v1.0.683 hazard (GL_LINEAR blurring pixel art and
        // inverting flappy's colour band) and it measurably does not apply here:
        // shade count 3 -> 3 across a fractional blit.
        // (Interpolation is no longer unconditionally unset -- see the
        // isFilterBitmap block above -- but Skia's sprite rule keeps a
        // translate-only, non-antialiased blit on the nearest path, which is
        // exactly the property this paragraph measured. VERIFIED: the gate
        // answers sprite=true for translate(8.5,8.5) with aa off.)
        if (src != null) {
            AffineTransform blit = AffineTransform.getTranslateInstance(x, y);
            // Sprite unless the CTM scales: a fractional translate alone stays
            // nearest, which is the property the paragraph above depends on.
            Object rest = macapkBeginFilter(
                macapkFilterBlit(p, blit, src.getWidth(), src.getHeight()));
            try { g.drawImage(src, blit, null); }
            finally { macapkEndFilter(rest); }
        }
    }
    public void drawBitmap(Bitmap b, Rect srcR, Rect dstR, Paint p) {
        if (!TRACE_DRAWCOST) { drawBitmapUntraced(b, srcR, dstR, p); return; }
        final long t0 = System.nanoTime();
        try { drawBitmapUntraced(b, srcR, dstR, p); } finally { drawCost(t0, "bmp-rect "+bdim(b)+" dst="+(dstR==null?"-":dstR.width()+"x"+dstR.height()), p); }
    }
    private void drawBitmapUntraced(Bitmap b, Rect srcR, Rect dstR, Paint p) {
        if (g == null || b == null || dstR == null) return;
        sGlobalBitmap++;
        macapkTraceBlit("srcdst", b, (srcR == null ? "src=null " : ("src=" + srcR + " ")) + dstR);
        // Ink, like the (x, y) overload: this is also the path the RectF
        // overload and NinePatch take, and the one Candy Crush's splash used.
        macapkMarkInk();
        applyBitmapComposite(p);
        BufferedImage src = prepImage(b, p);
        if (src == null) return;
        // The src-to-dst mapping IS the prematrix Skia's sprite test consumes:
        // it takes the source pixel block (0,0,sw,sh) onto dstR. When the two
        // are the same size and dstR sits on integers this stays a sprite, so
        // 1:1 NinePatch/atlas blits keep their exact pixels.
        final int sw = (srcR != null) ? srcR.width()  : src.getWidth();
        final int sh = (srcR != null) ? srcR.height() : src.getHeight();
        AffineTransform blit = null;
        if (sw > 0 && sh > 0) {
            blit = AffineTransform.getTranslateInstance(dstR.left, dstR.top);
            blit.scale(dstR.width() / (double) sw, dstR.height() / (double) sh);
        }
        final boolean filt = macapkFilterBlit(p, blit, sw, sh);
        if (filt && sw > 0 && sh > 0
                && macapkScaledBlit(src, srcR != null ? srcR.left : 0, srcR != null ? srcR.top : 0,
                                    (srcR != null ? srcR.left : 0) + sw, (srcR != null ? srcR.top : 0) + sh,
                                    dstR.left, dstR.top, dstR.right, dstR.bottom)) return;
        Object rest = macapkBeginFilter(filt);
        try {
            if (srcR == null) {
                g.drawImage(src, dstR.left, dstR.top, dstR.width(), dstR.height(), null);
            } else {
                g.drawImage(src, dstR.left, dstR.top, dstR.right, dstR.bottom,
                                  srcR.left,  srcR.top,  srcR.right,  srcR.bottom, null);
            }
        } finally { macapkEndFilter(rest); }
    }
    public void drawBitmap(Bitmap b, Rect srcR, RectF dstR, Paint p) {
        if (dstR == null) return;
        sGlobalBitmap++;
        drawBitmap(b, srcR, new Rect((int) dstR.left, (int) dstR.top,
                                       (int) dstR.right, (int) dstR.bottom), p);
    }
    public void drawBitmap(Bitmap b, Matrix matrix, Paint p) {
        if (!TRACE_DRAWCOST) { drawBitmapUntraced(b, matrix, p); return; }
        final long t0 = System.nanoTime();
        try { drawBitmapUntraced(b, matrix, p); } finally { drawCost(t0, "bmp-mx "+bdim(b), p); }
    }
    private void drawBitmapUntraced(Bitmap b, Matrix matrix, Paint p) {
        if (g == null || b == null) return;
        sGlobalBitmap++;
        macapkTraceBlit("matrix", b, String.valueOf(matrix));
        macapkMarkInk();
        applyBitmapComposite(p);
        BufferedImage src = prepImage(b, p);
        if (src == null) return;
        AffineTransform tx = (matrix != null) ? matrix.macapkToAffine() : new AffineTransform();
        final boolean filt = macapkFilterBlit(p, tx, src.getWidth(), src.getHeight());
        // A positive scale + translate matrix is a src-to-dst rect mapping, the same
        // blit the Rect overload makes (Skia: SkDraw::drawBitmap concats it into the CTM).
        if (filt && (tx.getType() & ~(AffineTransform.TYPE_TRANSLATION
                | AffineTransform.TYPE_GENERAL_SCALE | AffineTransform.TYPE_UNIFORM_SCALE)) == 0
                && tx.getScaleX() > 0 && tx.getScaleY() > 0
                && macapkScaledBlit(src, 0, 0, src.getWidth(), src.getHeight(),
                        tx.getTranslateX(), tx.getTranslateY(),
                        tx.getTranslateX() + src.getWidth() * tx.getScaleX(),
                        tx.getTranslateY() + src.getHeight() * tx.getScaleY())) return;
        Object rest = macapkBeginFilter(filt);
        try { g.drawImage(src, tx, null); }
        finally { macapkEndFilter(rest); }
    }
    public void drawBitmap(int[] colors, int offset, int stride,
                            float x, float y, int width, int height,
                            boolean hasAlpha, Paint p) {
        if (g == null || colors == null || width <= 0 || height <= 0) return;
        sGlobalBitmap++;
        macapkMarkInk();
        g.setComposite(AlphaComposite.SrcOver);
        BufferedImage tmp = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        tmp.setRGB(0, 0, width, height, colors, offset, stride);
        // Same float-position truncation as the Bitmap overload above; this
        // site was not in the original inventory. See that comment.
        //
        // DELIBERATELY NOT given the isFilterBitmap treatment the three Bitmap
        // overloads got. This site ignores the Paint ENTIRELY -- no
        // applyBitmapComposite, no colour filter, `p` is unread -- so honouring
        // one Paint property while dropping the rest would be incoherent.
        // It is also always a pure translate, i.e. a sprite under Skia's rule
        // unless the CTM scales, so the gap is narrow. Fixing the whole
        // paint-is-ignored defect here is its own change.
        g.drawImage(tmp, AffineTransform.getTranslateInstance(x, y), null);
    }
    /** The original API-1 int-coordinate form. AOSP keeps BOTH this and the
     *  float-coordinate sibling above; the int one is the descriptor older
     *  bytecode actually emits, so it has to exist in its own right. */
    public void drawBitmap(int[] colors, int offset, int stride,
                            int x, int y, int width, int height,
                            boolean hasAlpha, Paint p) {
        drawBitmap(colors, offset, stride, (float) x, (float) y, width, height, hasAlpha, p);
    }

    // --- Path drawing -------------------------------------------------

    /** AOSP's BaseCanvas.drawPath draws a path built only from same-direction
     *  addRect() calls as a REGION, which discards the fill type (a region is a
     *  union). Full contract, citation and measurements: Path.macapkDrawShape.
     *
     *  Default ON; MACAPK_SIMPLE_PATH_REGION=0 reverts to always honouring the
     *  fill type, so the two rows this closes
     *  (RAS.path.{nest,overlap}_cwcw_evenodd) can be A/B'd in one run. */
    private static final boolean MACAPK_SIMPLE_PATH_REGION =
        !"0".equals(System.getenv("MACAPK_SIMPLE_PATH_REGION"));

    public void drawPath(Path path, Paint p) {
        if (!TRACE_DRAWCOST) { drawPathUntraced(path, p); return; }
        final long t0 = System.nanoTime();
        try { drawPathUntraced(path, p); } finally { drawCost(t0, "path", p); }
    }
    private void drawPathUntraced(Path path, Paint p) {
        if (g == null || p == null || path == null) return;
        sGlobalPath++;
        com.macapk.loader.GfxTrace.real("Canvas.drawPath");
        // macapkDrawShape returns the live path unchanged unless the Region
        // fast path applies AND the fill type is EVEN_ODD, so the common case
        // allocates nothing and cannot change. Only drawPath consults it:
        // clipPath / clipOutPath / quickReject keep macapkShape(), because
        // SkCanvas::clipPath has no such fast path.
        Shape s = MACAPK_SIMPLE_PATH_REGION ? path.macapkDrawShape() : path.macapkShape();
        if (s != null) {
            // Skia applies a PathEffect by transforming the geometry BEFORE the
            // style, and the shadow layer shadows that transformed geometry.
            s = macapkPathEffectShape(s, p);
            if (hasShadow(p)) drawShapeShadow(s, p, false);
        }
        applyPaint(p);
        if (s == null) return;
        if (p.getStyle() == Paint.Style.STROKE) g.draw(s);
        else g.fill(s);
    }

    // --- PathEffect geometry -------------------------------------------
    //
    // Skia applies a PathEffect by TRANSFORMING the drawn geometry, then the
    // paint style (fill/stroke) applies to the result. Only DashPathEffect and
    // CornerPathEffect have java.awt BasicStroke analogues, which is why
    // macapkStrokeFor models those two; the other four resolved at the type
    // level, were stored on the Paint, and then changed nothing about what got
    // drawn -- a silent no-op. These build the transformed Shape at draw time,
    // which is where the effect actually belongs.
    //
    //   DiscretePathEffect -- SkDiscretePathEffect::onFilterPath: walk each
    //     contour in even segmentLength-ish steps and displace every sampled
    //     point ALONG THE CONTOUR NORMAL by up to +/- deviation (the hand-drawn
    //     look). Skia seeds its LCG from the effect's seedAssist, which the Java
    //     constructor leaves at 0, so the jitter is stable frame to frame rather
    //     than crawling; we seed from the effect's parameters for the same
    //     stability.
    //   PathDashPathEffect -- SkPath1DPathEffect: stamp the shape path along the
    //     drawn path every `advance` units starting at `phase`, oriented per
    //     Style. ROTATE follows the tangent; TRANSLATE does not. MORPH truly
    //     bends the stamp along the curve, which we approximate with the tangent
    //     rotation (exact wherever the span under a stamp is straight).
    //   ComposePathEffect -- inner effect first, then outer over its result.
    //   SumPathEffect -- both effects applied to the original, both drawn.
    //
    // Anything else (including Dash/Corner) returns the input Shape untouched,
    // so nothing that renders today changes.
    private Shape macapkPathEffectShape(Shape s, Paint p) {
        PathEffect pe;
        try { pe = p.getPathEffect(); } catch (Throwable t) { return s; }
        if (pe == null) return s;
        try {
            Shape out = applyPathEffect(s, pe, 0);
            return (out != null) ? out : s;
        } catch (Throwable t) {
            com.macapk.loader.GfxTrace.swallow("Canvas.pathEffect", t);
            return s;
        }
    }
    /** Recursive worker; depth bounds a pathologically nested Compose/Sum. */
    private Shape applyPathEffect(Shape s, PathEffect pe, int depth) {
        if (s == null || pe == null || depth > 8) return s;
        if (pe instanceof ComposePathEffect) {
            ComposePathEffect ce = (ComposePathEffect) pe;
            return applyPathEffect(applyPathEffect(s, ce.macapkInner(), depth + 1),
                                   ce.macapkOuter(), depth + 1);
        }
        if (pe instanceof SumPathEffect) {
            SumPathEffect se = (SumPathEffect) pe;
            Shape a = applyPathEffect(s, se.macapkFirst(), depth + 1);
            Shape b = applyPathEffect(s, se.macapkSecond(), depth + 1);
            java.awt.geom.Path2D.Float sum = new java.awt.geom.Path2D.Float();
            if (a != null) sum.append(a, false);
            if (b != null) sum.append(b, false);
            return sum;
        }
        if (pe instanceof DiscretePathEffect) {
            DiscretePathEffect de = (DiscretePathEffect) pe;
            return discretizeShape(s, de.macapkSegmentLength(), de.macapkDeviation());
        }
        if (pe instanceof PathDashPathEffect) {
            PathDashPathEffect pd = (PathDashPathEffect) pe;
            return stampShapeAlong(s, pd.macapkShape(), pd.macapkAdvance(),
                                   pd.macapkPhase(), pd.macapkStyle());
        }
        return s;
    }
    // Upper bound on generated segments/stamps for one contour. A denormal
    // segmentLength or advance would otherwise pin the draw thread; Skia has the
    // same exposure but we are on the UI thread here.
    private static final int MAX_EFFECT_STEPS = 20000;
    private static Shape discretizeShape(Shape s, float segLength, float deviation) {
        if (!(segLength > 0f) || deviation == 0f) return s;
        Path src = new Path();
        src.macapkAppendShape(s);
        PathMeasure pm = new PathMeasure(src, false);
        java.awt.geom.Path2D.Float out = new java.awt.geom.Path2D.Float();
        java.util.Random rnd = new java.util.Random(
                (Float.floatToIntBits(segLength) * 31L) ^ Float.floatToIntBits(deviation));
        float[] pos = new float[2], tan = new float[2];
        boolean any = false;
        do {
            float length = pm.getLength();
            // Skia skips a contour shorter than the deviation.
            if (length <= Math.abs(deviation)) continue;
            int n = Math.round(length / segLength);
            if (n < 1) n = 1;
            if (n > MAX_EFFECT_STEPS) n = MAX_EFFECT_STEPS;
            float delta = length / n;
            float distance = 0f;
            boolean closed = pm.isClosed();
            // Skia offsets a closed contour by half a step and emits one fewer
            // point, so the start point is not duplicated by the close.
            if (closed) { n -= 1; distance += delta / 2f; }
            boolean started = false;
            if (pm.getPosTan(distance, pos, tan)) {
                perturbAlongNormal(pos, tan, (rnd.nextFloat() * 2f - 1f) * deviation);
                out.moveTo(pos[0], pos[1]);
                started = true; any = true;
            }
            while (--n >= 0 && started) {
                distance += delta;
                if (pm.getPosTan(distance, pos, tan)) {
                    perturbAlongNormal(pos, tan, (rnd.nextFloat() * 2f - 1f) * deviation);
                    out.lineTo(pos[0], pos[1]);
                }
            }
            if (started && closed) out.closePath();
        } while (pm.nextContour());
        return any ? out : s;
    }
    /** SkDiscretePathEffect's Perterb(): displace along the contour normal (the
     *  unit tangent rotated 90 degrees), never along the tangent itself. */
    private static void perturbAlongNormal(float[] pos, float[] tan, float scale) {
        float nx = -tan[1], ny = tan[0];
        float len = (float) Math.hypot(nx, ny);
        if (!(len > 0f)) return;
        pos[0] += nx / len * scale;
        pos[1] += ny / len * scale;
    }
    private static Shape stampShapeAlong(Shape s, Path stamp, float advance, float phase,
                                         PathDashPathEffect.Style style) {
        if (stamp == null || !(advance > 0f)) return s;
        Shape stampShape = stamp.macapkShape();
        if (stampShape == null) return s;
        Path src = new Path();
        src.macapkAppendShape(s);
        PathMeasure pm = new PathMeasure(src, false);
        java.awt.geom.Path2D.Float out = new java.awt.geom.Path2D.Float();
        // TRANSLATE keeps the stamp axis-aligned; ROTATE and (approximated)
        // MORPH orient it to the path tangent.
        boolean rotate = (style != PathDashPathEffect.Style.TRANSLATE);
        float[] pos = new float[2], tan = new float[2];
        boolean any = false;
        do {
            float length = pm.getLength();
            if (!(length > 0f)) continue;
            float d = phase % advance;
            if (d < 0f) d += advance;
            for (int i = 0; d <= length && i < MAX_EFFECT_STEPS; i++, d += advance) {
                if (!pm.getPosTan(d, pos, tan)) continue;
                AffineTransform at = AffineTransform.getTranslateInstance(pos[0], pos[1]);
                if (rotate) at.rotate(Math.atan2(tan[1], tan[0]));
                out.append(at.createTransformedShape(stampShape), false);
                any = true;
            }
        } while (pm.nextContour());
        return any ? out : s;
    }

    // ================================================================
    // v1.0.433 surface completion -- AOSP public Canvas methods that
    // were MISSING (each a NoSuchMethodError that aborts the whole draw
    // pass -> blank screen, per docs/canvas-draw-surface-diff). Filled
    // with AWT-backed impls or safe defaults so the THROW failure mode
    // is structurally eliminated; the few unmodelable ops are no-ops
    // logged STUB by the harvester so they surface as silent gaps.
    // ================================================================

    // drawTextRun -- the PRIMARY low-level text entry for modern Compose /
    // StaticLayout / TextLine. Context indices + isRtl are bidi-shaping hints;
    // the visible run is what paints, so we mirror drawText.
    public void drawTextRun(char[] text, int index, int count,
                            int contextIndex, int contextCount,
                            float x, float y, boolean isRtl, Paint p) {
        if (text == null) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawTextRun(char[])");
        drawText(new String(text, index, count), x, y, p);
    }
    public void drawTextRun(CharSequence text, int start, int end,
                            int contextStart, int contextEnd,
                            float x, float y, boolean isRtl, Paint p) {
        if (text == null) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawTextRun(CharSequence)");
        drawText(text.subSequence(start, end).toString(), x, y, p);
    }

    // drawDoubleRoundRect -- Material3 outlined borders (OutlinedCard /
    // OutlinedTextField / Modifier.border on a rounded shape). The ring between
    // outer and inner IS the drawn shape (Area difference).
    public void drawDoubleRoundRect(RectF outer, float outerRx, float outerRy,
                                    RectF inner, float innerRx, float innerRy, Paint p) {
        if (g == null || p == null || outer == null || inner == null) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawDoubleRoundRect");
        applyPaint(p);
        Area a = new Area(new RoundRectangle2D.Float(outer.left, outer.top,
            outer.right - outer.left, outer.bottom - outer.top, 2 * outerRx, 2 * outerRy));
        a.subtract(new Area(new RoundRectangle2D.Float(inner.left, inner.top,
            inner.right - inner.left, inner.bottom - inner.top, 2 * innerRx, 2 * innerRy)));
        g.fill(macapkSkiaGeom(a, p));
    }
    public void drawDoubleRoundRect(RectF outer, float[] outerRadii,
                                    RectF inner, float[] innerRadii, Paint p) {
        drawDoubleRoundRect(outer, avgRad(outerRadii, 0), avgRad(outerRadii, 1),
                            inner, avgRad(innerRadii, 0), avgRad(innerRadii, 1), p);
    }
    private static float avgRad(float[] r, int parity) {
        if (r == null || r.length < 8) return 0f;
        return (r[parity] + r[parity + 2] + r[parity + 4] + r[parity + 6]) / 4f;
    }

    // drawColor(long) / drawRGB -- wide-gamut + legacy fills.
    public void drawColor(long color) { drawColor(colorLongToArgb(color)); }
    public void drawColor(long color, android.graphics.BlendMode mode) {
        drawColor(colorLongToArgb(color), mode);
    }
    public void drawColor(long color, android.graphics.PorterDuff.Mode mode) {
        drawColor(colorLongToArgb(color), mode);
    }
    public void drawRGB(int r, int gg, int b) {
        drawColor(0xFF000000 | ((r & 0xff) << 16) | ((gg & 0xff) << 8) | (b & 0xff));
    }
    // Compose/android Color longs pack sRGB as (argb << 32) with colorspace id 0
    // in the low 6 bits (android.graphics.Color.pack/toArgb). sRGB fast path =
    // high 32 bits; wide-gamut (id != 0) is half-float and approximated.
    private static int colorLongToArgb(long color) { return (int) (color >>> 32); }

    // drawLines (grouped by 4: x0,y0,x1,y1,...) / float-bounds drawOval.
    public void drawLines(float[] pts, int offset, int count, Paint p) {
        if (g == null || p == null || pts == null) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawLines");
        applyPaint(p);
        int end = Math.min(offset + count, pts.length);
        // Same int-truncation defect as drawLine, and this one was NOT in the
        // original inventory. One reused Line2D avoids an allocation per segment
        // on a hot per-frame path (chart grids, sparklines, sudoku rules).
        java.awt.geom.Line2D.Float s = new java.awt.geom.Line2D.Float();
        for (int i = offset; i + 3 < end; i += 4) {
            s.setLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3]);
            g.draw(macapkSkiaGeom(s, p));
        }
    }
    public void drawLines(float[] pts, Paint p) { if (pts != null) drawLines(pts, 0, pts.length, p); }
    public void drawOval(float left, float top, float right, float bottom, Paint p) {
        drawOval(new RectF(left, top, right, bottom), p);
    }

    // clipOut* -- DIFFERENCE clip (subtract a region). quickReject -- skip-test.
    public boolean clipOutRect(float left, float top, float right, float bottom) {
        if (g == null || !REAL_CLIP) return true;
        com.macapk.loader.GfxTrace.real("Canvas.clipOutRect");
        Area a = currentClipArea();
        a.subtract(new Area(new Rectangle2D.Float(left, top, right - left, bottom - top)));
        g.setClip(a);
        return !a.isEmpty();
    }
    public boolean clipOutRect(int l, int t, int r, int b) { return clipOutRect((float) l, (float) t, (float) r, (float) b); }
    public boolean clipOutRect(Rect rect)  { return rect == null ? true : clipOutRect(rect.left, rect.top, rect.right, rect.bottom); }
    public boolean clipOutRect(RectF rect) { return rect == null ? true : clipOutRect(rect.left, rect.top, rect.right, rect.bottom); }
    public boolean clipOutPath(Path path) {
        if (g == null || path == null || !REAL_CLIP) return true;
        com.macapk.loader.GfxTrace.real("Canvas.clipOutPath");
        Shape s = path.macapkShape();
        if (s == null) return true;
        Area a = currentClipArea();
        a.subtract(new Area(s));
        g.setClip(a);
        return !a.isEmpty();
    }
    /** AOSP Canvas.drawRegion(Region, Paint) (API 36). SkCanvas::onDrawRegion
     *  builds the region's boundary path and draws THAT with the paint, which is
     *  exactly what this does -- so it is a delegation, not an approximation. */
    public void drawRegion(Region region, Paint paint) {
        if (region == null) throw new NullPointerException("region must not be null");
        if (paint == null) throw new NullPointerException("paint must not be null");
        com.macapk.loader.GfxTrace.real("Canvas.drawRegion");
        drawPath(region.getBoundaryPath(), paint);
    }

    /** AOSP Canvas.clipShader(Shader) (API 36) -- "Modify the current clip with
     *  the specified shader ... the shader's alpha channel is used".
     *
     *  ⚠ NOT IMPLEMENTED, AND THE CLIP IS LEFT UNCHANGED. java.awt.Graphics2D
     *  clips to a SHAPE; it has no alpha-mask clip, and our Canvas has no
     *  per-pixel shader evaluation to build one from (the same reason
     *  RuntimeShader in this package is a value holder). Narrowing the clip to
     *  nothing would be the other guess and would DELETE content that AOSP
     *  draws, so leaving it open is the smaller divergence. Flagged here rather
     *  than pretended: content that AOSP would soft-mask draws unmasked. */
    public void clipShader(Shader shader) {
        if (shader == null) throw new NullPointerException("shader must not be null");
        com.macapk.loader.GfxTrace.stub("Canvas.clipShader");
    }

    /** AOSP Canvas.clipOutShader(Shader) (API 36) -- the difference form of
     *  clipShader. Same limitation and same reasoning; see clipShader above. */
    public void clipOutShader(Shader shader) {
        if (shader == null) throw new NullPointerException("shader must not be null");
        com.macapk.loader.GfxTrace.stub("Canvas.clipOutShader");
    }

    /** AOSP Canvas.drawMesh(Mesh, BlendMode, Paint) (API 34). A Mesh carries an
     *  AGSL vertex/fragment program pair (see MeshSpecification, which is a data
     *  holder for the same reason). We have no programmable pipeline on AWT, so
     *  the geometry cannot be shaded and nothing is drawn. The null checks are
     *  AOSP's @NonNull contract; blendMode is @Nullable and means MODULATE. */
    public void drawMesh(Mesh mesh, BlendMode blendMode, Paint paint) {
        if (mesh == null) throw new NullPointerException("mesh must not be null");
        if (paint == null) throw new NullPointerException("paint must not be null");
        com.macapk.loader.GfxTrace.stub("Canvas.drawMesh");
    }

    private Area currentClipArea() {
        Shape c = g.getClip();
        return new Area(c != null ? c : new Rectangle2D.Float(0, 0, getWidth(), getHeight()));
    }
    // quickReject: true => geometry entirely outside the current clip (skip it).
    // Conservative: when no clip bounds are known, return false (never reject).
    public boolean quickReject(float left, float top, float right, float bottom) {
        if (g == null) return false;
        java.awt.Rectangle cb = g.getClipBounds();
        return cb != null && !cb.intersects(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }
    /** FRAMESPEED S3 (ViewGroup.macapkQuickRejectChild, MACAPK_FRAMESPEED_CULL):
     *  quickReject with Skia's DEVICE-clip meaning. SkCanvas::quickReject tests
     *  against the device clip, and a surface canvas with no clipRect still clips
     *  to the surface, so a child wholly off the surface is rejected even at the
     *  root; the public quickReject above answers false whenever no clip was set
     *  (g.getClipBounds() is null then), which would leave every top-level
     *  offscreen child drawn. Exact either way: Java2D clips every op to the
     *  image (SunGraphics2D devClip), so a rect whose device bounds do not
     *  overlap the image, or whose user bounds miss the enclosing integer clip
     *  bounds (a strict test, so touching edges share no pixel centre), cannot
     *  receive a pixel. Requires a non-empty rect. */
    public boolean macapkQuickRejectChild(float l, float t, float r, float b) {
        if (g == null || image == null || !(r > l) || !(b > t)) return false;
        final java.awt.Rectangle cb = g.getClipBounds();
        if (cb != null) return !cb.intersects(l, t, r - l, b - t);
        final Rectangle2D dev = g.getTransform()
                .createTransformedShape(new Rectangle2D.Float(l, t, r - l, b - t)).getBounds2D();
        return !dev.intersects(0, 0, image.getWidth(), image.getHeight());
    }
    public boolean quickReject(RectF r) { return r != null && quickReject(r.left, r.top, r.right, r.bottom); }
    public boolean quickReject(Path path) {
        if (g == null || path == null) return false;
        Shape s = path.macapkShape();
        if (s == null) return false;
        Rectangle2D b = s.getBounds2D();
        return quickReject((float) b.getMinX(), (float) b.getMinY(), (float) b.getMaxX(), (float) b.getMaxY());
    }
    // Deprecated API-1 enum-Op overloads (still emitted by some bytecode).
    public enum EdgeType { BW, AA }
    public boolean quickReject(RectF r, EdgeType t) { return quickReject(r); }
    public boolean quickReject(Path p, EdgeType t) { return quickReject(p); }
    public boolean quickReject(float l, float t, float r, float b, EdgeType e) { return quickReject(l, t, r, b); }

    // DrawFilter + capability getters (must exist; software canvas ignores them).
    private DrawFilter mDrawFilter;
    public DrawFilter getDrawFilter() { return mDrawFilter; }
    public void setDrawFilter(DrawFilter filter) { this.mDrawFilter = filter; }
    public int getMaximumBitmapWidth()  { return 32766; }
    public int getMaximumBitmapHeight() { return 32766; }
    public boolean isOpaque() { return false; }
    public Rect getClipBounds() { Rect r = new Rect(); getClipBounds(r); return r; }
    /** AOSP deprecated save(int saveFlags); we always treat it as ALL_SAVE. */
    public int save(int saveFlags) { return save(); }

    // Picture / NinePatch replay.
    // AOSP android14 Canvas.java:1304-1336:
    //     drawPicture(picture):      picture.endRecording(); int rc = save();
    //                                picture.draw(this); restoreToCount(rc);
    //     drawPicture(picture, dst): save(); translate(dst.left, dst.top);
    //                                if (w > 0 && h > 0) scale(dst.width() / w, dst.height() / h);
    //                                drawPicture(picture); restore();
    // The two dst overloads used to IGNORE dst and draw at the origin, unscaled
    // ("Draw the picture, stretched to fit into the dst rectangle" is their whole
    // contract), and the plain form neither ended an open recording nor balanced
    // what the picture left saved. BISECT GATE, default ON:
    // MACAPK_DRAWPICTURE_AOSP=0 restores the pre-change bodies exactly.
    static final boolean DRAWPICTURE_AOSP = !"0".equals(System.getenv("MACAPK_DRAWPICTURE_AOSP"));
    public void drawPicture(Picture picture) {
        if (picture == null) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawPicture");
        if (!DRAWPICTURE_AOSP) { picture.draw(this); return; }
        picture.endRecording();
        final int rc = save();
        try { picture.draw(this); }
        finally { restoreToCount(rc); }
    }
    public void drawPicture(Picture picture, RectF dst) {
        if (!DRAWPICTURE_AOSP || picture == null || dst == null) { drawPicture(picture); return; }
        save();
        try {
            translate(dst.left, dst.top);
            if (picture.getWidth() > 0 && picture.getHeight() > 0) {
                scale(dst.width() / picture.getWidth(), dst.height() / picture.getHeight());
            }
            drawPicture(picture);
        } finally { restore(); }
    }
    public void drawPicture(Picture picture, Rect dst) {
        if (!DRAWPICTURE_AOSP || picture == null || dst == null) { drawPicture(picture); return; }
        save();
        try {
            translate(dst.left, dst.top);
            if (picture.getWidth() > 0 && picture.getHeight() > 0) {
                scale((float) dst.width() / picture.getWidth(),
                      (float) dst.height() / picture.getHeight());
            }
            drawPicture(picture);
        } finally { restore(); }
    }
    public void drawPatch(NinePatch patch, Rect dst, Paint p) {
        if (patch != null && dst != null) { com.macapk.loader.GfxTrace.real("Canvas.drawPatch"); patch.draw(this, dst, p); }
    }
    public void drawPatch(NinePatch patch, RectF dst, Paint p) {
        if (patch != null && dst != null) { com.macapk.loader.GfxTrace.real("Canvas.drawPatch"); patch.draw(this, dst); }
    }

    /** AOSP `Canvas.VertexMode` -- how drawVertices walks the vertex array. The
     *  int payload is AOSP's own `nativeInt` (TRIANGLES=0, TRIANGLE_STRIP=1,
     *  TRIANGLE_FAN=2); it is part of the class shape apps link against, so it
     *  is reproduced exactly rather than left to the enum ordinal. */
    public enum VertexMode {
        TRIANGLES(0),
        TRIANGLE_STRIP(1),
        TRIANGLE_FAN(2);

        VertexMode(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        /*package*/ final int nativeInt;
    }

    // drawVertices -- a Gouraud-shaded / shader-textured triangle mesh.
    //
    // AOSP contract (BaseCanvas.drawVertices + the Canvas javadoc): vertexCount
    // is the number of VALUES in verts (two per vertex, hence vertexCount/2
    // vertices), texs carries the same value count, colors carries one entry PER
    // VERTEX (which is why AOSP range-checks it against vertexCount/2), and
    // indices -- when present -- selects vertices by 16-bit index. Skia
    // rasterizes each triangle interpolating the per-vertex colors across it and
    // sampling the paint's shader at the interpolated texture coordinate.
    //
    // Java2D has neither a mesh primitive nor a Gouraud fill, so the mesh is
    // rasterized here into a device-space ARGB scratch buffer with barycentric
    // interpolation (exactly the quantity Skia interpolates) and blitted once
    // through Graphics2D, which keeps the canvas clip, composite and saveLayer
    // redirection intact. This was previously a total no-op, so every mesh warp
    // / gradient-mesh effect drew nothing at all with no error.
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset,
                             float[] texs, int texOffset, int[] colors, int colorOffset,
                             short[] indices, int indexOffset, int indexCount, Paint p) {
        if (verts == null) return;
        // AOSP validates the ranges before touching native and throws
        // ArrayIndexOutOfBoundsException; mirror it so a malformed mesh fails
        // the same way it would on a device instead of drawing garbage.
        checkRange(verts.length, vertOffset, vertexCount);
        if (texs != null)    checkRange(texs.length, texOffset, vertexCount);
        if (colors != null)  checkRange(colors.length, colorOffset, vertexCount / 2);
        if (indices != null) checkRange(indices.length, indexOffset, indexCount);
        if (g == null || image == null || p == null || mode == null) return;
        int nVerts = vertexCount / 2;
        if (nVerts < 3) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawVertices");
        macapkDrawOps++; sGlobalDrawOps++; macapkGenBump();

        // The mesh is rasterized in device space, so push the vertices through
        // the current transform once up front. AffineTransform's srcOff/dstOff
        // are raw array indices, which is also what AOSP's vertOffset counts.
        AffineTransform ctm = g.getTransform();
        float[] dev = new float[nVerts * 2];
        ctm.transform(verts, vertOffset, dev, 0, nVerts);

        // Scratch-buffer bounds: the mesh extent clipped to the target image and
        // to the (device-space) canvas clip, so a huge off-screen mesh costs
        // nothing and the buffer can never exceed one full canvas.
        float minXf = Float.MAX_VALUE, minYf = Float.MAX_VALUE;
        float maxXf = -Float.MAX_VALUE, maxYf = -Float.MAX_VALUE;
        for (int i = 0; i < nVerts; i++) {
            float vx = dev[i * 2], vy = dev[i * 2 + 1];
            if (Float.isNaN(vx) || Float.isNaN(vy)) return;
            if (vx < minXf) minXf = vx;
            if (vx > maxXf) maxXf = vx;
            if (vy < minYf) minYf = vy;
            if (vy > maxYf) maxYf = vy;
        }
        java.awt.Rectangle bb = new java.awt.Rectangle(
                (int) Math.floor(minXf), (int) Math.floor(minYf),
                (int) Math.ceil(maxXf - minXf) + 1, (int) Math.ceil(maxYf - minYf) + 1);
        bb = bb.intersection(new java.awt.Rectangle(0, 0, image.getWidth(), image.getHeight()));
        Shape userClip = g.getClip();
        Shape devClip = (userClip != null) ? ctm.createTransformedShape(userClip) : null;
        if (devClip != null) bb = bb.intersection(devClip.getBounds());
        if (bb.isEmpty()) return;

        // Fill source. No colors and no texture => the paint color (Skia's
        // behavior), resolved through a color filter the same way applyPaint
        // resolves one for a shape fill.
        int flat = p.getColor();
        ColorFilter cf = p.getColorFilter();
        if (cf != null) {
            Integer tinted = solidColorForFilter(cf, flat);
            if (tinted != null) flat = tinted;
        }
        // Texture source: texs are coordinates in the shader's space. The
        // shader's local matrix maps that space onto the sampled bitmap, so the
        // texel is localMatrix^-1 * texCoord.
        BufferedImage tex = null;
        AffineTransform texInv = null;
        Shader.TileMode tileX = Shader.TileMode.CLAMP, tileY = Shader.TileMode.CLAMP;
        if (texs != null) {
            Shader sh = null;
            try { sh = p.getShader(); } catch (Throwable ignored) {}
            if (sh instanceof BitmapShader) {
                BitmapShader bs = (BitmapShader) sh;
                tex = (bs.bitmap != null) ? bitmapAsImage(bs.bitmap) : null;
                if (bs.tileX != null) tileX = bs.tileX;
                if (bs.tileY != null) tileY = bs.tileY;
                AffineTransform lm = bs.macapkLocalAffine();
                if (lm != null) {
                    try { texInv = lm.createInverse(); } catch (Throwable ignored) { texInv = null; }
                }
            }
        }

        int[] px = new int[bb.width * bb.height];
        float[] xy = new float[6];
        float[] uv = (texs != null && tex != null) ? new float[6] : null;
        int[] cols = (colors != null) ? new int[3] : null;
        int seq = (indices != null && indexCount > 0) ? indexCount : nVerts;
        // TRIANGLES consumes the sequence in disjoint threes; TRIANGLE_STRIP
        // slides the same triple one position at a time; TRIANGLE_FAN pins the
        // first vertex and walks (0, s, s+1).
        boolean fan = (mode == VertexMode.TRIANGLE_FAN);
        int step = (mode == VertexMode.TRIANGLES) ? 3 : 1;
        int start = fan ? 1 : 0;
        int limit = fan ? seq - 1 : seq - 2;
        for (int s = start; s < limit; s += step) {
            int i0, i1, i2;
            if (fan) {
                i0 = meshIndex(indices, indexOffset, indexCount, 0);
                i1 = meshIndex(indices, indexOffset, indexCount, s);
                i2 = meshIndex(indices, indexOffset, indexCount, s + 1);
            } else {
                i0 = meshIndex(indices, indexOffset, indexCount, s);
                i1 = meshIndex(indices, indexOffset, indexCount, s + 1);
                i2 = meshIndex(indices, indexOffset, indexCount, s + 2);
            }
            if (i0 < 0 || i1 < 0 || i2 < 0) continue;
            if (i0 >= nVerts || i1 >= nVerts || i2 >= nVerts) continue;
            xy[0] = dev[i0 * 2]; xy[1] = dev[i0 * 2 + 1];
            xy[2] = dev[i1 * 2]; xy[3] = dev[i1 * 2 + 1];
            xy[4] = dev[i2 * 2]; xy[5] = dev[i2 * 2 + 1];
            if (cols != null) {
                cols[0] = colors[colorOffset + i0];
                cols[1] = colors[colorOffset + i1];
                cols[2] = colors[colorOffset + i2];
            }
            if (uv != null) {
                uv[0] = texs[texOffset + i0 * 2]; uv[1] = texs[texOffset + i0 * 2 + 1];
                uv[2] = texs[texOffset + i1 * 2]; uv[3] = texs[texOffset + i1 * 2 + 1];
                uv[4] = texs[texOffset + i2 * 2]; uv[5] = texs[texOffset + i2 * 2 + 1];
            }
            meshTriangle(px, bb, xy, cols, uv, tex, texInv, tileX, tileY, flat);
        }

        BufferedImage mesh = new BufferedImage(bb.width, bb.height, BufferedImage.TYPE_INT_ARGB);
        mesh.setRGB(0, 0, bb.width, bb.height, px, 0, bb.width);
        AffineTransform savedTx = g.getTransform();
        Shape savedClip = g.getClip();
        java.awt.Composite savedComp = g.getComposite();
        try {
            // px already carries the transform, so blit under identity with the
            // clip re-expressed in device space; SrcOver because each mesh pixel
            // carries its own alpha (Skia does not additionally apply the paint
            // alpha on top of interpolated vertex colors).
            g.setTransform(new AffineTransform());
            g.setClip(devClip);
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(mesh, bb.x, bb.y, null);
        } finally {
            g.setTransform(savedTx);
            g.setClip(savedClip);
            g.setComposite(savedComp);
        }
    }

    /** AOSP BaseCanvas.checkRange -- same formula, same exception. */
    private static void checkRange(int length, int offset, int count) {
        if ((offset | count) < 0 || offset + count > length) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
    /** Vertex index at sequence position s: through the (unsigned 16-bit) index
     *  array when one was supplied, otherwise the position itself. */
    private static int meshIndex(short[] indices, int indexOffset, int indexCount, int s) {
        if (indices == null || indexCount <= 0) return s;
        if (s < 0 || s >= indexCount) return -1;
        return indices[indexOffset + s] & 0xffff;
    }
    /** Rasterize one mesh triangle into the device-space scratch buffer. The
     *  barycentric weights at each pixel center are what Skia interpolates for
     *  Gouraud shading and for texture coordinates, so this is the same
     *  quantity, not an approximation of it. A small negative tolerance on the
     *  inside test keeps shared edges from leaving uncovered seams. */
    private static void meshTriangle(int[] dst, java.awt.Rectangle bb, float[] xy,
                                     int[] cols, float[] uv, BufferedImage tex,
                                     AffineTransform texInv,
                                     Shader.TileMode tileX, Shader.TileMode tileY, int flat) {
        float ax = xy[0], ay = xy[1], bx = xy[2], by = xy[3], cx = xy[4], cy = xy[5];
        float area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
        if (area == 0f || Float.isNaN(area) || Float.isInfinite(area)) return;
        float inv = 1f / area;
        int minX = (int) Math.floor(Math.min(ax, Math.min(bx, cx)));
        int maxX = (int) Math.ceil(Math.max(ax, Math.max(bx, cx))) + 1;
        int minY = (int) Math.floor(Math.min(ay, Math.min(by, cy)));
        int maxY = (int) Math.ceil(Math.max(ay, Math.max(by, cy))) + 1;
        if (minX < bb.x) minX = bb.x;
        if (minY < bb.y) minY = bb.y;
        if (maxX > bb.x + bb.width)  maxX = bb.x + bb.width;
        if (maxY > bb.y + bb.height) maxY = bb.y + bb.height;
        for (int y = minY; y < maxY; y++) {
            int row = (y - bb.y) * bb.width - bb.x;
            float py = y + 0.5f;
            for (int x = minX; x < maxX; x++) {
                float pxc = x + 0.5f;
                float w0 = ((bx - pxc) * (cy - py) - (cx - pxc) * (by - py)) * inv;
                float w1 = ((cx - pxc) * (ay - py) - (ax - pxc) * (cy - py)) * inv;
                float w2 = 1f - w0 - w1;
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                int argb = flat;
                if (cols != null) argb = lerpTri(cols[0], cols[1], cols[2], w0, w1, w2);
                if (tex != null && uv != null) {
                    float u = w0 * uv[0] + w1 * uv[2] + w2 * uv[4];
                    float v = w0 * uv[1] + w1 * uv[3] + w2 * uv[5];
                    int texel = sampleTexel(tex, texInv, u, v, tileX, tileY);
                    // Skia modulates the vertex colors with the shader sample
                    // when a mesh supplies both.
                    argb = (cols != null) ? multiplyColors(argb, texel) : texel;
                }
                if ((argb >>> 24) != 0) dst[row + x] = argb;
            }
        }
    }
    /** Barycentric blend of three ARGB vertex colors. */
    private static int lerpTri(int c0, int c1, int c2, float w0, float w1, float w2) {
        int a = clamp255(((c0 >>> 24) & 0xff) * w0 + ((c1 >>> 24) & 0xff) * w1 + ((c2 >>> 24) & 0xff) * w2);
        int r = clamp255(((c0 >>> 16) & 0xff) * w0 + ((c1 >>> 16) & 0xff) * w1 + ((c2 >>> 16) & 0xff) * w2);
        int gr = clamp255(((c0 >>> 8) & 0xff) * w0 + ((c1 >>> 8) & 0xff) * w1 + ((c2 >>> 8) & 0xff) * w2);
        int b = clamp255((c0 & 0xff) * w0 + (c1 & 0xff) * w1 + (c2 & 0xff) * w2);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }
    /** Nearest-neighbour texel fetch honoring the BitmapShader's tile modes.
     *  DECAL samples outside the bitmap as transparent, per its AOSP contract. */
    private static int sampleTexel(BufferedImage tex, AffineTransform texInv, float u, float v,
                                   Shader.TileMode tileX, Shader.TileMode tileY) {
        double sx = u, sy = v;
        if (texInv != null) {
            double[] pt = { u, v };
            texInv.transform(pt, 0, pt, 0, 1);
            sx = pt[0]; sy = pt[1];
        }
        int ix = tileCoord((int) Math.floor(sx), tex.getWidth(), tileX);
        int iy = tileCoord((int) Math.floor(sy), tex.getHeight(), tileY);
        if (ix < 0 || iy < 0) return 0;
        return tex.getRGB(ix, iy);
    }
    private static int tileCoord(int i, int n, Shader.TileMode mode) {
        if (n <= 0) return -1;
        if (mode == Shader.TileMode.REPEAT) {
            i %= n; if (i < 0) i += n; return i;
        }
        if (mode == Shader.TileMode.MIRROR) {
            int period = 2 * n;
            int m = i % period; if (m < 0) m += period;
            return (m < n) ? m : (period - 1 - m);
        }
        if (mode == Shader.TileMode.DECAL) return (i < 0 || i >= n) ? -1 : i;
        return (i < 0) ? 0 : (i >= n ? n - 1 : i);   // CLAMP
    }

    public void drawBitmapMesh(Bitmap b, int meshWidth, int meshHeight, float[] verts,
                               int vertOffset, int[] colors, int colorOffset, Paint p) {
        com.macapk.loader.GfxTrace.stub("Canvas.drawBitmapMesh");
        if (b != null && verts != null && verts.length >= vertOffset + 2)
            drawBitmap(b, verts[vertOffset], verts[vertOffset + 1], p);
    }
    public void drawPosText(char[] text, int index, int count, float[] pos, Paint p) {
        if (text == null || pos == null) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawPosText");
        for (int i = 0; i < count && (i * 2 + 1) < pos.length; i++)
            drawText(new String(text, index + i, 1), pos[i * 2], pos[i * 2 + 1], p);
    }
    public void drawPosText(String text, float[] pos, Paint p) {
        if (text != null) drawPosText(text.toCharArray(), 0, text.length(), pos, p);
    }

    // --- Clipping ----
    //
    // v1.0.121: real clip. Per AOSP Canvas.clipRect javadoc + the
    // ViewGroup per-child draw protocol (View.java:23732-23744 in
    // android-14): the parent calls `canvas.clipRect(0, 0, childW,
    // childH)` between translate-to-child and child.draw(), so child
    // content can't paint past its bounds. Without an actual clip,
    // long text / oversized icons spill into neighbor cards.
    //
    // Java2D's `Graphics2D.clip(Shape)` performs INTERSECTION with the
    // existing clip, which matches AOSP's default `Region.Op.INTERSECT`.
    // A draw call after clip() is bounded to the intersection. We
    // snapshot/restore via the save()/restore() clipStack so each
    // sibling starts with the parent's clip, not the previous
    // sibling's narrower one.
    //
    // Float coords get rounded to ints via the Rectangle constructor;
    // the per-child clipRect in dispatchDraw uses integer view bounds
    // so this lossy rounding never matters in practice.
    //
    // PUBLIC clipRect entry: APK code may call this (e.g.,
    // MaterialShapeDrawable.draw narrows to a rounded shape). Honor
    // it -- without the clip, anti-aliased shape edges blur over
    // neighbors. Env var MACAPK_REAL_CLIP=0 reverts to the no-op
    // stub for emergency rollback.
    private static final boolean REAL_CLIP =
        !"0".equals(System.getenv("MACAPK_REAL_CLIP"));
    public boolean clipRect(float l, float t, float r, float b) {
        if (g == null || !REAL_CLIP) return true;
        // ⚠ THIS USED TO TRUNCATE TO int IN USER SPACE, BEFORE THE TRANSFORM.
        // AOSP passes floats straight through (Canvas.clipRect -> nClipRect
        // takes rect.left/top/right/bottom as floats), and the comment above
        // justified the cast on the grounds that "the per-child clipRect in
        // dispatchDraw uses integer view bounds so this lossy rounding never
        // matters in practice" -- which is true of that caller and false of
        // every APP caller, which the same comment block acknowledges exists.
        //
        // The cost is multiplied by the current transform. `(int)(-0.5f)` is 0
        // (truncation toward zero, not floor), so a half-unit clip offset under
        // a scaling CTM lands half a UNIT out. Measured on anuto, whose renderer
        // does concat(worldMatrix) then clipRect(RectF(-0.5, -0.5, w-0.5, h-0.5))
        // at 80.8 px/unit: all four edges were displaced by exactly 40px, giving
        // a 40px black band down the left of the board and cutting 40px off the
        // bottom of the world. Predicted and measured agreed on every edge.
        //
        // g.clip(Shape) intersects in USER space, which is AOSP's Op.INTERSECT
        // semantics; clipPath below already uses it, and clipOutRect already
        // uses Rectangle2D.Float -- this method was the odd one out.
        // macapkClipChildRect (the per-child hot path) still takes exact ints
        // and is deliberately left alone.
        float w = r - l, h = b - t;
        if (w <= 0f || h <= 0f) {
            // Empty clip: nothing draws. Sentinel the next siblings can't
            // draw inside.
            g.setClip(new java.awt.Rectangle((int) l, (int) t, 0, 0));
            return false;
        }
        g.clip(new java.awt.geom.Rectangle2D.Float(l, t, w, h));
        return true;
    }
    public boolean clipRect(Rect rect) {
        if (rect == null) return true;
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }
    public boolean clipRect(RectF rect) {
        if (rect == null) return true;
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }
    public boolean clipRect(int l, int t, int r, int b) {
        return clipRect((float) l, (float) t, (float) r, (float) b);
    }
    /** AOSP 5-arg overloads with Region.Op. Stale-comment fix (v1.0.834):
     *  this used to say "we treat all Ops as INTERSECT ... proper Region
     *  support would need an actual region implementation" -- true when
     *  v1.0.124 landed, not since Region.java's v1.0.433 Area-backed
     *  rewrite gave clipOutRect/clipOutPath real DIFFERENCE (subtract)
     *  semantics right below. AOSP's own checkValidClipOp (Canvas.java,
     *  verified against aosp-mirror main) restricts these deprecated
     *  overloads to exactly INTERSECT and DIFFERENCE as of API 28 (P) --
     *  any other Region.Op throws IllegalArgumentException on a real
     *  device -- so those two are the only ops a modern app can legally
     *  pass. Silently treating a DIFFERENCE clip as INTERSECT left content
     *  that should have been clipped OUT (a "cut a hole" / spotlight-
     *  overlay pattern) still visible: an ignored clip that silently
     *  leaks drawing, the exact failure mode this file's clip stack
     *  exists to prevent. Route DIFFERENCE to the already-correct
     *  clipOutRect/clipOutPath below; anything else (INTERSECT, or a
     *  pre-P app's now-invalid legacy value) keeps the prior INTERSECT
     *  behavior rather than newly throwing. */
    public boolean clipRect(float l, float t, float r, float b, android.graphics.Region.Op op) {
        if (op == android.graphics.Region.Op.DIFFERENCE) return clipOutRect(l, t, r, b);
        return clipRect(l, t, r, b);
    }
    public boolean clipRect(int l, int t, int r, int b, android.graphics.Region.Op op) {
        return clipRect((float) l, (float) t, (float) r, (float) b, op);
    }
    public boolean clipRect(Rect rect, android.graphics.Region.Op op) {
        if (op == android.graphics.Region.Op.DIFFERENCE) return clipOutRect(rect);
        return clipRect(rect);
    }
    public boolean clipRect(RectF rect, android.graphics.Region.Op op) {
        if (op == android.graphics.Region.Op.DIFFERENCE) return clipOutRect(rect);
        return clipRect(rect);
    }
    public boolean clipPath(android.graphics.Path path, android.graphics.Region.Op op) {
        if (op == android.graphics.Region.Op.DIFFERENCE) return clipOutPath(path);
        return clipPath(path);
    }
    /** Internal entry from ViewGroup.dispatchDraw that ALWAYS applies
     *  the clip regardless of MACAPK_REAL_CLIP, because the
     *  bounds-restricting clip is structural -- the rendering
     *  contract dispatchDraw promises. APK code keeps the
     *  rollback knob; our own per-child clip does not. */
    public boolean macapkClipChildRect(int l, int t, int r, int b) {
        if (g == null) return true;
        int w = r - l, h = b - t;
        if (w <= 0 || h <= 0) {
            g.setClip(new java.awt.Rectangle(l, t, 0, 0));
            return false;
        }
        g.clipRect(l, t, w, h);
        return true;
    }
    public boolean clipPath(Path path) {
        if (g == null || path == null || !REAL_CLIP) return true;
        java.awt.Shape s = path.macapkShape();
        if (s == null) return true;
        g.clip(s);
        return true;
    }
    public boolean getClipBounds(Rect bounds) {
        if (bounds == null) return true;
        if (g == null) {
            bounds.set(0, 0, getWidth(), getHeight());
            return true;
        }
        java.awt.Rectangle r = g.getClipBounds();
        if (r != null) {
            bounds.set(r.x, r.y, r.x + r.width, r.y + r.height);
        } else {
            bounds.set(0, 0, getWidth(), getHeight());
        }
        return true;
    }

    // --- Shader sampling (gradients / bitmap fills) v1.0.433 ----------
    //
    // Paint.setShader stored a LinearGradient / RadialGradient / SweepGradient /
    // BitmapShader, but applyPaint historically ignored it and filled with the
    // (often default-BLACK or transparent) paint color, so every Compose/
    // Material3 gradient surface, scrim, branded header, progress arc, and
    // BitmapShader-rounded image rendered as a solid box or invisibly. We map
    // each shader to the nearest java.awt Paint so g.setPaint samples the
    // gradient across ALL fill/stroke ops at once. Returns null when no faithful
    // mapping is possible (then applyPaint falls back to the solid color). Every
    // AWT constructor that can throw on degenerate geometry is guarded.
    private java.awt.Paint awtPaintForShader(Shader shader) { return awtPaintForShader(shader, null); }
    /** REPEAT and MIRROR tile; a TexturePaint can reproduce both. */
    private static boolean macapkTiles(Shader.TileMode m) {
        return m == Shader.TileMode.REPEAT || m == Shader.TileMode.MIRROR;
    }
    /** MACAPK_BITMAPSHADER_MATRIX (default ON; =0 = every BitmapShader is a
     *  java.awt.TexturePaint at the bitmap's own size, the pre-v1.0.2271 path):
     *  a BitmapShader with a local matrix, or with a CLAMP / DECAL axis, is
     *  sampled through CTM x localMatrix with its tile modes
     *  (MacApkBitmapShaderPaint). TexturePaint stays for REPEAT / MIRROR with no
     *  local matrix, where it is exact. */
    static final boolean BITMAPSHADER_MATRIX =
        !"0".equals(System.getenv("MACAPK_BITMAPSHADER_MATRIX"));
    private java.awt.Paint awtPaintForShader(Shader shader, Paint p) {
        try {
            AffineTransform lm = shader.macapkLocalAffine();
            if (shader instanceof LinearGradient) {
                LinearGradient lg = (LinearGradient) shader;
                if (lg.colors == null || lg.colors.length == 0) return null;
                if (lg.colors.length == 1) return toAwtColor(lg.colors[0]);
                Point2D p0 = new Point2D.Float(lg.x0, lg.y0);
                Point2D p1 = new Point2D.Float(lg.x1, lg.y1);
                if (p0.distance(p1) < 1e-3) return toAwtColor(lg.colors[lg.colors.length - 1]);
                float[] fr = sanitizeFractions(lg.positions, lg.colors.length);
                Color[] cols = toAwtColors(lg.colors);
                MultipleGradientPaint.CycleMethod cyc = cycleFor(lg.tile);
                if (lm != null)
                    return new LinearGradientPaint(p0, p1, fr, cols, cyc,
                        MultipleGradientPaint.ColorSpaceType.SRGB, lm);
                return new LinearGradientPaint(p0, p1, fr, cols, cyc);
            }
            if (shader instanceof RadialGradient) {
                RadialGradient rg = (RadialGradient) shader;
                if (rg.colors == null || rg.colors.length == 0 || rg.radius <= 0) return null;
                if (rg.colors.length == 1) return toAwtColor(rg.colors[0]);
                float[] fr = sanitizeFractions(rg.stops, rg.colors.length);
                Color[] cols = toAwtColors(rg.colors);
                Point2D c = new Point2D.Float(rg.centerX, rg.centerY);
                MultipleGradientPaint.CycleMethod cyc = cycleFor(rg.tile);
                if (lm != null)
                    return new RadialGradientPaint(c, rg.radius, c, fr, cols, cyc,
                        MultipleGradientPaint.ColorSpaceType.SRGB, lm);
                return new RadialGradientPaint(c, rg.radius, fr, cols, cyc);
            }
            if (shader instanceof BitmapShader) {
                BitmapShader bs = (BitmapShader) shader;
                BufferedImage img = (bs.bitmap != null) ? bitmapAsImage(bs.bitmap) : null;
                if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return null;
                // TexturePaint only ever tiles REPEAT. CLAMP/MIRROR/DECAL have no AWT analogue,
                // so historically all three approximated as a REPEAT tile anchored at the origin --
                // fine for the dominant rounded-image-fill case (one tile sized to the shape),
                // wrong whenever the filled shape is larger than the bitmap.
                //
                // MIRROR is EXACTLY representable and is now built properly: Skia's MIRROR reflects
                // the image on every other tile, so a 2x2 arrangement (original, x-flipped,
                // y-flipped, xy-flipped) tiled with plain REPEAT reproduces it pixel for pixel. The
                // only cost is one 4x-area scratch image, built once per paint resolution.
                // CLAMP (edge-texel extension) and DECAL (transparent outside) still approximate --
                // they need padding sized to the fill bounds, which is not available here; that is
                // stated rather than silently lumped in with MIRROR.
                if (BITMAPSHADER_MATRIX) {
                    final boolean hasMatrix = lm != null && !lm.isIdentity();
                    final boolean clampish = !macapkTiles(bs.tileX) || !macapkTiles(bs.tileY);
                    if (hasMatrix || clampish) {
                        final int fm = bs.getFilterMode();
                        final boolean bilinear = fm == BitmapShader.FILTER_MODE_LINEAR
                            || (fm == BitmapShader.FILTER_MODE_DEFAULT && (p == null || p.isFilterBitmap()));
                        return new MacApkBitmapShaderPaint(img, hasMatrix ? lm : null,
                            bs.tileX, bs.tileY, bilinear);
                    }
                }
                BufferedImage tile = img;
                boolean mx = (bs.tileX == Shader.TileMode.MIRROR);
                boolean my = (bs.tileY == Shader.TileMode.MIRROR);
                if (mx || my) tile = macapkMirrorTileCached(bs, img, mx, my);
                Rectangle2D anchor = new Rectangle2D.Float(0, 0, tile.getWidth(), tile.getHeight());
                return new TexturePaint(tile, anchor);
            }
            if (shader instanceof SweepGradient) {
                SweepGradient sg = (SweepGradient) shader;
                if (sg.colors == null || sg.colors.length == 0) return null;
                if (sg.colors.length == 1) return toAwtColor(sg.colors[0]);
                java.awt.Paint sp = sweepTexture(sg);
                return (sp != null) ? sp
                    : toAwtColor(sg.colors[sg.colors.length / 2]);  // fallback: mid color
            }
            if (shader instanceof ComposeShader) {
                // Cannot truly compose two shaders on a software canvas; sample
                // the first so the fill is approximately right rather than blank.
                return awtPaintForShader(((ComposeShader) shader).macapkA(), p);
            }
            if (shader instanceof RuntimeShader) {
                return new MacApkRuntimeShaderPaint((RuntimeShader) shader, p);
            }
        } catch (Throwable t) {
            // Degenerate gradient geometry / bad fractions -> solid-color fallback.
        }
        return null;
    }
    /** AWT MultipleGradientPaint requires fractions[] length == colors, each in
     *  [0,1] and STRICTLY increasing. Android allows null (even spacing) and
     *  tolerates duplicate stops; sanitize to AWT's stricter contract. */
    private static float[] sanitizeFractions(float[] pos, int n) {
        float[] fr = new float[n];
        if (pos != null && pos.length == n) {
            System.arraycopy(pos, 0, fr, 0, n);
        } else {
            for (int i = 0; i < n; i++) fr[i] = (n == 1) ? 0f : (float) i / (n - 1);
        }
        float prev = -1f; boolean bad = false;
        for (int i = 0; i < n; i++) {
            float v = fr[i];
            if (v < 0f) v = 0f; if (v > 1f) v = 1f;
            if (v <= prev) v = prev + 1e-4f;
            if (v > 1f) bad = true;
            fr[i] = v; prev = v;
        }
        if (bad) for (int i = 0; i < n; i++) fr[i] = (n == 1) ? 0f : (float) i / (n - 1);
        return fr;
    }
    private static Color[] toAwtColors(int[] argb) {
        Color[] c = new Color[argb.length];
        int[] fixed = macapkPremulSafeStops(argb);
        for (int i = 0; i < fixed.length; i++) c[i] = toAwtColor(fixed[i]);
        return c;
    }

    /** SKIA INTERPOLATES GRADIENTS IN PREMULTIPLIED SPACE; AWT DOES NOT. Carry the neighbour's RGB
     *  into any fully-transparent stop so a straight-alpha interpolator produces Skia's answer.
     *
     *  THE BUG THIS FIXES. A stop of `Color.Transparent` is 0x00000000 -- transparent BLACK.
     *  `java.awt.MultipleGradientPaint` interpolates the four channels independently in
     *  NON-premultiplied sRGB, so a ramp from opaque white to transparent black passes through
     *  semi-transparent GREY: at alpha a the RGB is 255*a, not 255. Skia interpolates gradients with
     *  premultiplied colours (SkGradientShader; `kInterpolateColorsInPremul` is the legacy spelling
     *  of what modern Skia does by default), where the same ramp stays white and only alpha falls.
     *  Compose's `Brush.linearGradient(... to Color.Transparent)` is extremely common, so this
     *  darkened every such gradient on our side.
     *
     *  MEASURED (Now in Android, whose background is exactly this shape -- two full-screen ramps,
     *  opaque->Transparent and Transparent->opaque). A `MACAPK_TRACE_FULLFILL` readback of the
     *  pixel the fill had just written reported `readback=4f4d4a4a`: alpha 0x4f (0.31), RGB 77.
     *  The app's stop is near-white 250, and 250 * 0.31 = 77.5 -- i.e. exactly straight-alpha
     *  interpolation toward black. Whole-screen ours/device ratio bottomed at 0.635 mid-screen,
     *  where the two ramps' transparent ends meet and the error is largest, while every OPAQUE
     *  element matched the device at exactly 1.000. That V profile is this bug's signature.
     *
     *  THE FIX is the standard way to emulate premultiplied interpolation on a straight-alpha
     *  interpolator: a stop with alpha 0 contributes no colour, so its RGB is free -- set it to the
     *  nearest non-transparent neighbour's RGB. The ramp then holds that RGB while alpha falls to 0,
     *  which is what premultiplied interpolation produces. Stops that are NOT fully transparent are
     *  untouched, so this cannot change any gradient that lacks a transparent stop.
     *
     *  ⚠ Only alpha==0 stops qualify. A partially-transparent stop carries real colour and
     *  rewriting it WOULD change the ramp. An all-transparent gradient is left alone (nothing to
     *  borrow, and it paints nothing either way). */
    private static int[] macapkPremulSafeStops(int[] argb) {
        if (argb == null || argb.length < 2) return argb;
        int firstOpaque = -1;
        for (int i = 0; i < argb.length; i++) {
            if ((argb[i] >>> 24) != 0) { firstOpaque = i; break; }
        }
        if (firstOpaque < 0) return argb;           // every stop transparent: nothing to borrow
        boolean any = false;
        for (int i = 0; i < argb.length; i++) { if ((argb[i] >>> 24) == 0) { any = true; break; } }
        if (!any) return argb;                      // no transparent stop: untouched
        int[] out = argb.clone();
        // Walk outward from each transparent stop to the nearest stop that has colour, preferring
        // the closer side; ties go to the earlier stop, which matches Skia's stop ordering.
        for (int i = 0; i < out.length; i++) {
            if ((argb[i] >>> 24) != 0) continue;
            int lo = -1, hi = -1;
            for (int j = i - 1; j >= 0; j--) if ((argb[j] >>> 24) != 0) { lo = j; break; }
            for (int j = i + 1; j < argb.length; j++) if ((argb[j] >>> 24) != 0) { hi = j; break; }
            int src = (lo < 0) ? hi : (hi < 0) ? lo : ((i - lo) <= (hi - i) ? lo : hi);
            out[i] = (argb[src] & 0x00FFFFFF);      // neighbour's RGB, alpha stays 0
        }
        return out;
    }
    private static MultipleGradientPaint.CycleMethod cycleFor(Shader.TileMode t) {
        if (t == Shader.TileMode.REPEAT) return MultipleGradientPaint.CycleMethod.REPEAT;
        if (t == Shader.TileMode.MIRROR) return MultipleGradientPaint.CycleMethod.REFLECT;
        return MultipleGradientPaint.CycleMethod.NO_CYCLE;  // CLAMP / DECAL / null
    }
    // SweepGradient (conic/angular) has no AWT analogue: render it per-pixel into
    // a canvas-sized texture (angle from center -> interpolated stop color) bound
    // via TexturePaint anchored 1:1 so one tile covers the fill (no visible
    // repeat). Cached per SweepGradient instance via SoftReference -- a rotating
    // spinner rotates the canvas, not the gradient, so the texture stays valid
    // and is computed once. v1.0.434.
    private static final java.util.Map<SweepGradient, java.lang.ref.SoftReference<TexturePaint>> sSweepCache =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private TexturePaint sweepTexture(SweepGradient sg) {
        if (image == null) return null;
        java.lang.ref.SoftReference<TexturePaint> ref = sSweepCache.get(sg);
        TexturePaint cached = (ref != null) ? ref.get() : null;
        if (cached != null) return cached;
        int w = image.getWidth(), h = image.getHeight();
        if (w <= 0 || h <= 0) return null;
        BufferedImage tex = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        float[] fr = sanitizeFractions(sg.positions, sg.colors.length);
        Color[] cols = toAwtColors(sg.colors);
        double cx = sg.cx, cy = sg.cy;
        final double TWO_PI = Math.PI * 2;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Android sweep: angle 0 at +x (3 o'clock), increasing CLOCKWISE.
                // In screen coords (y down), atan2(dy,dx) already runs clockwise.
                double ang = Math.atan2(y - cy, x - cx);
                if (ang < 0) ang += TWO_PI;
                row[x] = lerpStops((float) (ang / TWO_PI), fr, cols);
            }
            tex.setRGB(0, y, w, 1, row, 0, w);
        }
        TexturePaint tp = new TexturePaint(tex, new Rectangle2D.Float(0, 0, w, h));
        sSweepCache.put(sg, new java.lang.ref.SoftReference<>(tp));
        return tp;
    }
    private static int lerpStops(float t, float[] fr, Color[] cols) {
        if (t <= fr[0]) return cols[0].getRGB();
        if (t >= fr[fr.length - 1]) return cols[cols.length - 1].getRGB();
        for (int i = 1; i < fr.length; i++) {
            if (t <= fr[i]) {
                float span = fr[i] - fr[i - 1];
                float u = span > 0 ? (t - fr[i - 1]) / span : 0f;
                return lerpColor(cols[i - 1], cols[i], u);
            }
        }
        return cols[cols.length - 1].getRGB();
    }
    private static int lerpColor(Color a, Color b, float u) {
        int oa = (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * u + 0.5f);
        int or = (int) (a.getRed()   + (b.getRed()   - a.getRed())   * u + 0.5f);
        int og = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * u + 0.5f);
        int ob = (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * u + 0.5f);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    // --- Color filters (tints) v1.0.433 ------------------------------
    //
    // Paint.setColorFilter stored a PorterDuff/BlendMode/ColorMatrix/Lighting
    // filter that Canvas ignored, so Compose/Material3 icon + vector + image
    // tints were silent no-ops (icons kept their authored color, often black or
    // invisible against the surface). For shape/text fills we resolve the filter
    // to a single ARGB (used by applyPaint); for images we filter per-pixel
    // (drawBitmap -> applyColorFilterToImage). Returns null when the filter
    // should leave the base color unchanged (DST modes / unknown), so the caller
    // keeps the original paint color rather than blanking.
    private Integer solidColorForFilter(ColorFilter cf, int baseColor) {
        if (cf instanceof PorterDuffColorFilter) {
            PorterDuffColorFilter f = (PorterDuffColorFilter) cf;
            return tintForPorterDuff(f.getMode(), f.getColor(), baseColor);
        }
        if (cf instanceof BlendModeColorFilter) {
            BlendModeColorFilter f = (BlendModeColorFilter) cf;
            return tintForBlend(f.getMode(), f.getColor(), baseColor);
        }
        if (cf instanceof LightingColorFilter) {
            LightingColorFilter f = (LightingColorFilter) cf;
            return applyLighting(baseColor, f.getColorMultiply(), f.getColorAdd());
        }
        if (cf instanceof ColorMatrixColorFilter) {
            return applyColorMatrix(baseColor, ((ColorMatrixColorFilter) cf).macapkArray());
        }
        return null;
    }
    private static Integer tintForPorterDuff(PorterDuff.Mode m, int filter, int base) {
        if (m == null) return filter;
        switch (m) {
            case SRC: case SRC_IN: case SRC_ATOP: case SRC_OVER: return filter;
            case DST: case DST_IN: case DST_OUT: case DST_ATOP: case DST_OVER: return null;
            case MULTIPLY: return multiplyColors(base, filter);
            case SCREEN:   return screenColors(base, filter);
            case ADD:      return addColors(base, filter);
            case CLEAR:    return 0x00000000;
            default:       return filter;  // best-effort recolor (LIGHTEN/DARKEN/OVERLAY)
        }
    }
    private static Integer tintForBlend(BlendMode m, int filter, int base) {
        if (m == null) return filter;
        if (m == BlendMode.SRC || m == BlendMode.SRC_IN || m == BlendMode.SRC_ATOP || m == BlendMode.SRC_OVER) return filter;
        if (m == BlendMode.DST || m == BlendMode.DST_IN || m == BlendMode.DST_OUT || m == BlendMode.DST_ATOP || m == BlendMode.DST_OVER) return null;
        if (m == BlendMode.MODULATE || m == BlendMode.MULTIPLY) return multiplyColors(base, filter);
        if (m == BlendMode.SCREEN) return screenColors(base, filter);
        if (m == BlendMode.PLUS)   return addColors(base, filter);
        if (m == BlendMode.CLEAR)  return 0x00000000;
        return filter;
    }
    /** Apply a color filter to one whole image (drawBitmap path), after scaling every pixel's
     *  alpha by `preAlpha` (255 = leave it; see blendPreFilterAlpha). */
    private BufferedImage applyColorFilterToImage(BufferedImage src, ColorFilter cf, int preAlpha) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int[] live = stockArgbData(src);
            if (live != null) return filterPixels(live, w, h, cf, preAlpha);
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                src.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++)
                    row[x] = filterPixel(cf, preAlpha >= 255 ? row[x] : scaleAlpha(row[x], preAlpha));
                out.setRGB(0, y, w, 1, row, 0, w);
            }
            return out;
        } catch (Throwable t) { return src; }
    }
    /** filterPixel over a packed non-premultiplied 0xAARRGGBB array (w*h, row stride w), into a
     *  new TYPE_INT_ARGB image written through its backing array. On that type getRGB and setRGB
     *  are value-identity (DirectColorModel, standard masks, not premultiplied), so this is the
     *  row loop above without the per-pixel ColorModel round trip. The PorterDuff and BlendMode
     *  filters, by far the common ones, have their dispatch hoisted out of the loop. */
    private BufferedImage filterPixels(int[] in, int w, int h, ColorFilter cf, int preAlpha) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] o = ((java.awt.image.DataBufferInt) out.getRaster().getDataBuffer()).getData();
        final int n = w * h;
        final boolean pre = preAlpha < 255;
        if (cf instanceof PorterDuffColorFilter f) {
            final PorterDuff.Mode m = f.getMode();
            final int c = f.getColor();
            for (int i = 0; i < n; i++) o[i] = pdfPixel(m, c, pre ? scaleAlpha(in[i], preAlpha) : in[i]);
        } else if (cf instanceof BlendModeColorFilter f) {
            final BlendMode m = f.getMode();
            final int c = f.getColor();
            for (int i = 0; i < n; i++) o[i] = blendPixel(m, c, pre ? scaleAlpha(in[i], preAlpha) : in[i]);
        } else {
            for (int i = 0; i < n; i++) o[i] = filterPixel(cf, pre ? scaleAlpha(in[i], preAlpha) : in[i]);
        }
        return out;
    }
    private int filterPixel(ColorFilter cf, int pixel) {
        if (cf instanceof PorterDuffColorFilter) {
            PorterDuffColorFilter f = (PorterDuffColorFilter) cf;
            return pdfPixel(f.getMode(), f.getColor(), pixel);
        }
        if (cf instanceof BlendModeColorFilter) {
            BlendModeColorFilter f = (BlendModeColorFilter) cf;
            return blendPixel(f.getMode(), f.getColor(), pixel);
        }
        if (cf instanceof LightingColorFilter) {
            LightingColorFilter f = (LightingColorFilter) cf;
            return applyLighting(pixel, f.getColorMultiply(), f.getColorAdd());
        }
        if (cf instanceof ColorMatrixColorFilter) {
            return applyColorMatrix(pixel, ((ColorMatrixColorFilter) cf).macapkArray());
        }
        return pixel;
    }
    // Skia color-filter semantics: filter color is "src", pixel is "dst".
    private static int pdfPixel(PorterDuff.Mode m, int color, int pixel) {
        if (m == null) return recolorPixel(color, pixel);
        switch (m) {
            case SRC:                return color;
            case SRC_IN: case SRC_ATOP: return recolorPixel(color, pixel);
            case DST: case DST_IN: case DST_OUT: case DST_ATOP: case DST_OVER: return pixel;
            case MULTIPLY:           return multiplyColors(color, pixel);
            case SCREEN:             return screenColors(color, pixel);
            case ADD:                return addColors(color, pixel);
            case CLEAR:              return 0;
            default:                 return recolorPixel(color, pixel);
        }
    }
    private static int blendPixel(BlendMode m, int color, int pixel) {
        if (m == null) return recolorPixel(color, pixel);
        if (m == BlendMode.SRC) return color;
        if (m == BlendMode.SRC_IN || m == BlendMode.SRC_ATOP) return recolorPixel(color, pixel);
        if (m == BlendMode.DST || m == BlendMode.DST_IN || m == BlendMode.DST_OUT || m == BlendMode.DST_ATOP || m == BlendMode.DST_OVER) return pixel;
        if (m == BlendMode.MODULATE || m == BlendMode.MULTIPLY) return multiplyColors(color, pixel);
        if (m == BlendMode.SCREEN) return screenColors(color, pixel);
        if (m == BlendMode.PLUS)   return addColors(color, pixel);
        if (m == BlendMode.CLEAR)  return 0;
        return recolorPixel(color, pixel);
    }
    /** Recolor a pixel to the filter RGB, keeping alpha = pixelAlpha*filterAlpha
     *  (the classic SRC_IN "tint an icon" result). */
    private static int recolorPixel(int color, int pixel) {
        int pa = (pixel >>> 24) & 0xff, ca = (color >>> 24) & 0xff;
        return ((pa * ca / 255) << 24) | (color & 0x00FFFFFF);
    }
    private static int multiplyColors(int a, int b) {
        int aa=(a>>>24)&0xff, ar=(a>>16)&0xff, ag=(a>>8)&0xff, ab=a&0xff;
        int ba=(b>>>24)&0xff, br=(b>>16)&0xff, bg=(b>>8)&0xff, bb=b&0xff;
        return ((aa*ba/255)<<24)|((ar*br/255)<<16)|((ag*bg/255)<<8)|(ab*bb/255);
    }
    private static int screenColors(int a, int b) {
        int aa=(a>>>24)&0xff, ar=(a>>16)&0xff, ag=(a>>8)&0xff, ab=a&0xff;
        int ba=(b>>>24)&0xff, br=(b>>16)&0xff, bg=(b>>8)&0xff, bb=b&0xff;
        return (scr(aa,ba)<<24)|(scr(ar,br)<<16)|(scr(ag,bg)<<8)|scr(ab,bb);
    }
    private static int scr(int s, int d) { return 255 - ((255 - s) * (255 - d) / 255); }
    private static int addColors(int a, int b) {
        int aa=(a>>>24)&0xff, ar=(a>>16)&0xff, ag=(a>>8)&0xff, ab=a&0xff;
        int ba=(b>>>24)&0xff, br=(b>>16)&0xff, bg=(b>>8)&0xff, bb=b&0xff;
        return (Math.min(255,aa+ba)<<24)|(Math.min(255,ar+br)<<16)|(Math.min(255,ag+bg)<<8)|Math.min(255,ab+bb);
    }
    private static int applyLighting(int src, int mul, int add) {
        int sa=(src>>>24)&0xff, sr=(src>>16)&0xff, sg=(src>>8)&0xff, sb=src&0xff;
        int mr=(mul>>16)&0xff, mg=(mul>>8)&0xff, mb=mul&0xff;
        int ar=(add>>16)&0xff, ag=(add>>8)&0xff, ab=add&0xff;
        return (sa<<24)
            | (Math.min(255, sr*mr/255 + ar) << 16)
            | (Math.min(255, sg*mg/255 + ag) << 8)
            |  Math.min(255, sb*mb/255 + ab);
    }
    private static int applyColorMatrix(int argb, float[] m) {
        if (m == null || m.length < 20) return argb;
        float a=(argb>>>24)&0xff, r=(argb>>16)&0xff, gg=(argb>>8)&0xff, b=argb&0xff;
        int nr=clamp255(m[0]*r  + m[1]*gg  + m[2]*b  + m[3]*a  + m[4]);
        int ng=clamp255(m[5]*r  + m[6]*gg  + m[7]*b  + m[8]*a  + m[9]);
        int nb=clamp255(m[10]*r + m[11]*gg + m[12]*b + m[13]*a + m[14]);
        int na=clamp255(m[15]*r + m[16]*gg + m[17]*b + m[18]*a + m[19]);
        return (na<<24)|(nr<<16)|(ng<<8)|nb;
    }
    private static int clamp255(float v) { int i=(int)(v+0.5f); return i<0?0:(i>255?255:i); }

    /** Resolve the (possibly color-filtered) AWT image for a Bitmap draw. Call AFTER
     *  applyBitmapComposite: the two split the paint alpha between them. */
    private BufferedImage prepImage(Bitmap b, Paint p) {
        BufferedImage src = bitmapAsImage(b);
        if (src != null && p != null && p.getColorFilter() != null) {
            src = filteredImage(b, src, p.getColorFilter(), blendPreFilterAlpha(p));
        }
        return src;
    }

    // --- Colour-filtered bitmap cache ---------------------------------
    //
    // A colour-filtered blit used to re-filter the WHOLE source on every draw.
    // For a large bitmap drawn every frame that is the dominant cost: Telegram's
    // 1636x3362 wallpaper pattern (PorterDuffColorFilter(black, SRC_IN)) spent
    // ~44 ms per frame here before a single pixel was blended. AOSP pays nothing
    // comparable -- Skia runs the filter as a pipeline stage per sampled pixel.
    //
    // So large filtered sources are cached per Bitmap. Validity is checked by
    // CONTENT, not by the generation ID. Bitmap.getGenerationId() has been real
    // since P1 (MACAPK_BITMAP_GENID, docs/rendernode-oplist-stage-a_2026-09-26.md),
    // but its coverage depends on EVERY writer calling macapkNotifyPixelsChanged
    // (Canvas ink sites, setPixel(s), erase, copyPixelsFromBuffer, reconfigure,
    // the NDK unlock commit, an inBitmap decode), and a writer that is missed
    // there would make this cache hand back a stale filtered image; a content
    // compare cannot go stale. The entry keeps the exact source pixels it was
    // filtered from and is reused only when the live pixels are identical (a
    // vectorized Arrays.equals, ~2-3 ms on 5.5 Mpx), so a stale result is
    // impossible. The filter is also keyed by its
    // PARAMETERS, not its identity, because LightingColorFilter and
    // ColorMatrixColorFilter are mutable.
    //
    // Only sources >= FILTER_CACHE_MIN_PIXELS in the stock TYPE_INT_ARGB layout
    // are cached (an icon tint is cheap to redo and not worth two copies), at most
    // FILTER_CACHE_MAX_ENTRIES of them, all softly referenced.
    private static final int FILTER_CACHE_MIN_PIXELS = 256 * 256;
    private static final int FILTER_CACHE_MAX_ENTRIES = 8;
    private static final class FilteredEntry {
        final java.lang.ref.WeakReference<Bitmap> owner;
        final Object filterKey;
        final int preAlpha, w, h;
        final java.lang.ref.SoftReference<int[]> source;
        final java.lang.ref.SoftReference<BufferedImage> image;
        FilteredEntry(Bitmap owner, Object filterKey, int preAlpha, int w, int h,
                      int[] source, BufferedImage image) {
            this.owner = new java.lang.ref.WeakReference<>(owner);
            this.filterKey = filterKey; this.preAlpha = preAlpha; this.w = w; this.h = h;
            this.source = new java.lang.ref.SoftReference<>(source);
            this.image = new java.lang.ref.SoftReference<>(image);
        }
    }
    /** Keyed by the Bitmap's identity hash (the entry's WeakReference settles a
     *  collision), least-recently-used first out. */
    private static final java.util.LinkedHashMap<Integer, FilteredEntry> sFilteredCache =
        new java.util.LinkedHashMap<Integer, FilteredEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Integer, FilteredEntry> e) {
                return size() > FILTER_CACHE_MAX_ENTRIES;
            }
        };

    /** `src` (the pixels of `b`) through `cf`, with `preAlpha` applied first. */
    private BufferedImage filteredImage(Bitmap b, BufferedImage src, ColorFilter cf, int preAlpha) {
        final int w = src.getWidth(), h = src.getHeight();
        final int[] live = (MACAPK_BITMAP_FILTER_CACHE && (long) w * h >= FILTER_CACHE_MIN_PIXELS)
                         ? stockArgbData(src) : null;
        final Object key = (live != null) ? filterKey(cf) : null;
        if (key == null) return applyColorFilterToImage(src, cf, preAlpha);
        final int n = w * h;
        final Integer id = System.identityHashCode(b);
        FilteredEntry e;
        synchronized (sFilteredCache) { e = sFilteredCache.get(id); }
        if (e != null && e.owner.get() == b && e.w == w && e.h == h
                && e.preAlpha == preAlpha && key.equals(e.filterKey)) {
            int[] was = e.source.get();
            BufferedImage img = e.image.get();
            if (was != null && img != null && java.util.Arrays.equals(live, 0, n, was, 0, n))
                return img;
        }
        // Snapshot FIRST and filter the snapshot, so the entry's image is exactly
        // the filter of the pixels it will be validated against, even if another
        // thread writes the bitmap meanwhile.
        final int[] snap = java.util.Arrays.copyOf(live, n);
        final BufferedImage out;
        try {
            out = filterPixels(snap, w, h, cf, preAlpha);
        } catch (Throwable t) {
            return applyColorFilterToImage(src, cf, preAlpha);
        }
        synchronized (sFilteredCache) {
            // Drop entries whose Bitmap is gone, so their two soft copies do not sit
            // on the heap until eviction happens to reach them.
            sFilteredCache.values().removeIf(x -> x.owner.get() == null);
            sFilteredCache.put(id, new FilteredEntry(b, key, preAlpha, w, h, snap, out));
        }
        return out;
    }
    /** The backing array of a stock TYPE_INT_ARGB image (non-premultiplied 0xAARRGGBB, one int
     *  per pixel, row stride == width, no offset), else null. Same guard as
     *  Bitmap.macapkExtractArgbFromAwt, plus the sample-model translation a sub-image would carry.
     *  getData() makes the raster un-accelerable, which costs nothing for these software-only
     *  images (that comment again). */
    // ---- Scaled, FILTERED bitmap blit (MACAPK_BITMAP_SCALE_FAST, default ON) ----
    //
    // WHY. A filtered drawBitmap under a scale went through Java2D's GENERIC
    // transformed-image pipe: TransformHelper inverse-maps every destination pixel,
    // bilinear-samples it into a temporary IntArgbPre tile, and then MaskBlits the
    // tile -- or, under a blend mode, hands it to BlendComposite one tile at a time.
    // Telegram's MotionBackgroundDrawable draws two of these EVERY frame over its
    // 3360x1796 window: a 60x80 gradient upscaled to cover (49 ms) and the
    // 1796x3691 wallpaper pattern under SOFT_LIGHT with a colour filter (70 ms) --
    // 120 of a 137 ms frame (MACAPK_TRACE_DRAWCOST, 2026-10-01). On a device Skia
    // samples these per pixel in its raster pipeline (or the GPU does), and pays
    // nothing like it.
    //
    // WHAT. The same arithmetic, in one separable pass straight into the
    // destination's int[]: per destination row, the two source rows it samples are
    // premultiplied once (cached while the row index repeats) and interpolated
    // vertically; per destination pixel the result is interpolated horizontally and
    // composited. The interpolation is Java2D's own (TransformHelper's BL_ACCUM on
    // IntArgbPre with 8-bit weights taken from 32.32 fixed-point coordinates, clamped
    // to the source rect's edge pixels): bilinear interpolation with integer weights
    // is order-independent, so the y-then-x pass reproduces its x-then-y integers
    // exactly GIVEN THE SAME WEIGHTS. The weights are not always the same: Java2D
    // steps every row and column in 32.32 from the transform's origin at the
    // destination bounds (TransformHelper.c: ybase = DblToLong(yorig); rowylong +=
    // dydylong; FractOfLong(ylong - LongOneHalf)), while this recomputes each row's
    // start (and the column span's start) in double, so a weight can land one step
    // (1/256) away at a boundary -- the 1/255 residue the gate budgets
    // (BitmapScaleCheck 'compare'; the 2/255 TOL row and the differing-pixel budget).
    // Coverage is the pixel-centre rule both Java2D (calculateEdges) and
    // Skia (non-AA rect, and AA on integer edges) use. A transparent source pixel
    // over an opaque destination is skipped (SrcOver writes nothing; every blend
    // mode but MODULATE leaves the destination, BlendComposite.blendPx), which is
    // most of Telegram's ~89%-transparent pattern.
    //
    // SCOPE (anything else returns false and takes g.drawImage exactly as before):
    // bilinear only (a sprite or nearest blit is already a fast native loop), a
    // positive axis-aligned scale, a translation-only CTM, a rectangular clip
    // (macapkDeviceClipRect), stock TYPE_INT_ARGB source and destination, and the
    // composite applyBitmapComposite set: SrcOver (any paint alpha) or a
    // BlendComposite. A destination edge within 1e-6 of a half-pixel tie that lies
    // inside the clip is refused, because Java2D's fixed-point centre test could
    // round it either way.
    //
    // Proof: debug/bitmapscale_gate.sh -- ON vs OFF over random sources, scales,
    // offsets, clips, paint alphas and blend modes: identical coverage, every pixel
    // within 2/255 per channel, a budget of visible differing pixels (per case and
    // total: a systematic 1/255 error hides under the 2/255 bound), the BANDED path
    // (>= 256K px) byte-identical for 1/4/7 bands, and the timing row; the =0 arm
    // fails the timing row, and a pre-fix div8 table and a late-starting band each
    // fail their own row. MACAPK_BITMAP_SCALE_FAST=0 restores g.drawImage.
    // Doc: docs/telegram-wallpaper-scaled-blit_2026-10-01.md.
    static final boolean BITMAP_SCALE_FAST =
        !"0".equals(System.getenv("MACAPK_BITMAP_SCALE_FAST"));
    /** How many draws the fast path served (diagnostic; the gate reads it). */
    public static long sFsScaledBlits;
    /** Java2D's mul8table / div8table, bit for bit (jdk21u java2d/loops/AlphaMath.c initAlphaTables:
     *  mul8[i][j] = (j * i * 0x010101 + 2^23) >> 24; div8[i][j] = 255 for j >= i, else
     *  (2^23 + j * inc) >> 24 with inc = ((0xff << 24) + i / 2) / i). */
    private static final byte[] SB_MUL8 = new byte[256 * 256], SB_DIV8 = new byte[256 * 256];
    static {
        for (int i = 1; i < 256; i++) {
            final long inc = (long) i * 0x010101L;
            for (int j = 1; j < 256; j++) SB_MUL8[(i << 8) | j] = (byte) ((inc * j + (1L << 23)) >>> 24);
            final long dinc = ((0xffL << 24) + i / 2) / i;   // AlphaMath.c: the + i/2 rounds the increment
            for (int j = 0; j < 256; j++)
                SB_DIV8[(i << 8) | j] = (byte) (j >= i ? 255 : (int) ((j * dinc + (1L << 23)) >>> 24));
        }
    }
    private static int sbMul8(int a, int b) { return SB_MUL8[(a << 8) | b] & 0xff; }
    private static int sbDiv8(int a, int b) { return SB_DIV8[(a << 8) | b] & 0xff; }
    /** Java2D's DblToLong: a double coordinate as 32.32 fixed point. */
    private static long sbFix(double d) { return (long) (d * 4294967296.0); }

    /** Draw src's [sx0,sx1) x [sy0,sy1) bilinear-filtered onto the USER-space rect
     *  [ux0,ux1) x [uy0,uy1) with g's current composite, or return false having
     *  touched nothing (see the block above for when). */
    private boolean macapkScaledBlit(BufferedImage src, int sx0, int sy0, int sx1, int sy1,
                                     double ux0, double uy0, double ux1, double uy1) {
        if (!BITMAP_SCALE_FAST || g == null || image == null || src == null || src == image) return false;
        if (sx1 <= sx0 || sy1 <= sy0 || !(ux1 > ux0) || !(uy1 > uy0)) return false;
        if (sx0 < 0 || sy0 < 0 || sx1 > src.getWidth() || sy1 > src.getHeight()) return false;
        final int[] dd = stockArgbData(image), sd = stockArgbData(src);
        if (dd == null || sd == null) return false;
        final int[] clip = new int[4];
        if (!macapkDeviceClipRect(clip)) return false;
        final java.awt.Composite comp = g.getComposite();
        BlendComposite.Ctx blend = null;
        boolean zeroKeeps = true;
        int extraA = 255;
        if (comp instanceof AlphaComposite ac) {
            if (ac.getRule() != AlphaComposite.SRC_OVER) return false;
            extraA = (int) (ac.getAlpha() * 255.0 + 0.5);   // DeclareAndInitExtraAlpha
            if (extraA <= 0) return true;                   // Java2D draws nothing either
        } else if (comp instanceof BlendComposite bc) {
            blend = bc.macapkCtx(true, image.isAlphaPremultiplied());
            zeroKeeps = bc.macapkZeroSourceKeepsOpaqueDst();
        } else {
            return false;
        }
        final AffineTransform t = g.getTransform();
        final double DX0 = ux0 + t.getTranslateX(), DX1 = ux1 + t.getTranslateX();
        final double DY0 = uy0 + t.getTranslateY(), DY1 = uy1 + t.getTranslateY();
        final int X0 = sbEdge(DX0, clip[0], clip[2], true), X1 = sbEdge(DX1, clip[0], clip[2], false);
        final int Y0 = sbEdge(DY0, clip[1], clip[3], true), Y1 = sbEdge(DY1, clip[1], clip[3], false);
        if (X0 == Integer.MIN_VALUE || X1 == Integer.MIN_VALUE
                || Y0 == Integer.MIN_VALUE || Y1 == Integer.MIN_VALUE) return false;
        sFsScaledBlits++;
        if (X1 <= X0 || Y1 <= Y0) return true;   // nothing visible: Java2D draws nothing
        final int W = image.getWidth(), SW = src.getWidth();
        final double kx = (sx1 - sx0) / (DX1 - DX0), ky = (sy1 - sy0) / (DY1 - DY0);
        // Columns: the two source columns each destination column blends, and the
        // weight (TransformHelper: xwhole/xfract of a 32.32 coordinate stepped by
        // dxdx from the span's first pixel centre, minus 0.5 for bilinear; the
        // weight is the fraction's top 8 bits; edge pixels clamp).
        final int n = X1 - X0;
        final int[] c0 = new int[n], c1 = new int[n], fx = new int[n];
        final long xl0 = sbFix((X0 + 0.5 - DX0) * kx - 0.5), dxl = sbFix(kx);
        final int cw = sx1 - sx0;
        int imin = Integer.MAX_VALUE, imax = Integer.MIN_VALUE;
        for (int k = 0; k < n; k++) {
            final long xl = xl0 + k * dxl;
            int xw = (int) (xl >> 32);
            fx[k] = (int) (xl >>> 24) & 0xff;
            final int a = Math.max(0, Math.min(cw - 1, xw));
            final int b = (xw >= 0 && xw < cw - 1) ? xw + 1 : a;
            c0[k] = a; c1[k] = b;
            if (a < imin) imin = a;
            if (b > imax) imax = b;
        }
        for (int k = 0; k < n; k++) { c0[k] -= imin; c1[k] -= imin; }
        final int span = imax - imin + 1;
        final SbJob job = new SbJob(dd, W, sd, SW, sx0 + imin, sy0, sy1 - sy0, span, X0, n, c0, c1, fx,
                                    DY0, ky, blend, zeroKeeps, extraA);
        final long px = (long) n * (Y1 - Y0);
        final int bands = (px >= SB_PARALLEL_MIN_PX) ? Math.min(SB_THREADS, Math.max(1, (Y1 - Y0) / 16)) : 1;
        if (bands <= 1) { job.rows(Y0, Y1); return true; }
        // Split the rows into bands: each band only reads the shared source and the
        // per-column tables and only writes its own destination rows, and the
        // arithmetic is per pixel, so the output is identical for any band count.
        final java.util.concurrent.Future<?>[] fs = new java.util.concurrent.Future<?>[bands - 1];
        final int rowsPer = (Y1 - Y0 + bands - 1) / bands;
        for (int i = 1; i < bands; i++) {
            final int a0 = Y0 + i * rowsPer, a1 = Math.min(Y1, a0 + rowsPer);
            fs[i - 1] = (a0 < a1) ? sbPool().submit(() -> job.rows(a0, a1)) : null;
        }
        Throwable err = null;
        try { job.rows(Y0, Math.min(Y1, Y0 + rowsPer)); } catch (Throwable th) { err = th; }
        for (java.util.concurrent.Future<?> f : fs) {
            if (f == null) continue;
            boolean interrupted = false;
            while (true) {
                try { f.get(); break; }
                catch (InterruptedException ie) { interrupted = true; }
                catch (java.util.concurrent.ExecutionException ee) { if (err == null) err = ee.getCause(); break; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (err instanceof RuntimeException re) throw re;
        if (err instanceof Error e) throw e;
        return true;
    }
    /** Rows of one scaled blit; a band of them per thread. Per-band scratch (the
     *  two cached premultiplied source rows, the vertical pass, the blend memo)
     *  lives in rows(), so bands share nothing they write. */
    private static final class SbJob {
        final int[] dd, sd, c0, c1, fx; final int W, SW, sxFrom, sy0, ch, span, X0, n, extraA;
        final double DY0, ky; final BlendComposite.Ctx blend; final boolean zeroKeeps;
        SbJob(int[] dd, int W, int[] sd, int SW, int sxFrom, int sy0, int ch, int span, int X0, int n,
              int[] c0, int[] c1, int[] fx, double DY0, double ky, BlendComposite.Ctx blend,
              boolean zeroKeeps, int extraA) {
            this.dd = dd; this.W = W; this.sd = sd; this.SW = SW; this.sxFrom = sxFrom; this.sy0 = sy0;
            this.ch = ch; this.span = span; this.X0 = X0; this.n = n; this.c0 = c0; this.c1 = c1;
            this.fx = fx; this.DY0 = DY0; this.ky = ky; this.blend = blend; this.zeroKeeps = zeroKeeps;
            this.extraA = extraA;
        }
        void rows(int yFrom, int yTo) {
            final int[] va = new int[span], vr = new int[span], vg = new int[span], vb = new int[span];
            // Two premultiplied source rows, cached by source row index.
            final int[] rowA = new int[span], rowB = new int[span];
            int rowAj = -1, rowBj = -1;
            final int[] memo = { 0, 0, (blend != null) ? blend.blendPx(0, 0) : 0 };
            for (int y = yFrom; y < yTo; y++) {
                // Each row's start is recomputed in double here; Java2D steps rows in 32.32
                // from the bounds origin (rowylong += dydylong), so a weight may differ by one
                // step (1/256) at a boundary -- see the comment above macapkScaledBlit.
                final long yl = sbFix((y + 0.5 - DY0) * ky - 0.5);
                final int yw = (int) (yl >> 32), fy = (int) (yl >>> 24) & 0xff;
                final int j0 = Math.max(0, Math.min(ch - 1, yw));
                final int j1 = (yw >= 0 && yw < ch - 1) ? yw + 1 : j0;
                int[] r0, r1;
                if (rowAj == j0) r0 = rowA;
                else if (rowBj == j0) r0 = rowB;
                else { // load into whichever slot does not hold j1
                    if (rowAj == j1) { sbPremulRow(sd, SW, sxFrom, sy0 + j0, span, rowB); rowBj = j0; r0 = rowB; }
                    else { sbPremulRow(sd, SW, sxFrom, sy0 + j0, span, rowA); rowAj = j0; r0 = rowA; }
                }
                if (rowAj == j1) r1 = rowA;
                else if (rowBj == j1) r1 = rowB;
                else {
                    if (r0 == rowA) { sbPremulRow(sd, SW, sxFrom, sy0 + j1, span, rowB); rowBj = j1; r1 = rowB; }
                    else { sbPremulRow(sd, SW, sxFrom, sy0 + j1, span, rowA); rowAj = j1; r1 = rowA; }
                }
                boolean any = false;
                for (int i = 0; i < span; i++) {
                    final int p0 = r0[i], p1 = r1[i];
                    if ((p0 | p1) == 0) { va[i] = 0; vr[i] = 0; vg[i] = 0; vb[i] = 0; continue; }
                    any = true;
                    final int a0 = p0 >>> 24, a1 = p1 >>> 24;
                    final int R0 = (p0 >> 16) & 0xff, R1 = (p1 >> 16) & 0xff;
                    final int G0 = (p0 >> 8) & 0xff, G1 = (p1 >> 8) & 0xff;
                    final int B0 = p0 & 0xff, B1 = p1 & 0xff;
                    va[i] = (a0 << 8) + (a1 - a0) * fy;
                    vr[i] = (R0 << 8) + (R1 - R0) * fy;
                    vg[i] = (G0 << 8) + (G1 - G0) * fy;
                    vb[i] = (B0 << 8) + (B1 - B0) * fy;
                }
                if (!any && (blend == null || zeroKeeps)) {
                    // A fully transparent source row: SrcOver writes nothing, and a
                    // blend leaves every OPAQUE destination pixel alone; only a
                    // translucent one can still change.
                    if (blend == null) continue;
                    final int base = y * W + X0;
                    for (int k = 0; k < n; k++) {
                        final int d = dd[base + k];
                        if ((d >>> 24) != 255) dd[base + k] = blend.blendPx(0, d);
                    }
                    continue;
                }
                final int base = y * W + X0;
                if (blend == null) sbRowSrcOver(dd, base, n, c0, c1, fx, va, vr, vg, vb, extraA);
                else sbRowBlend(dd, base, n, c0, c1, fx, va, vr, vg, vb, blend, zeroKeeps, memo);
            }
        }
    }
    /** Bands per large blit (MACAPK_BITMAP_SCALE_THREADS; 1 = serial). Default:
     *  half the cores, at most 4 -- the draw thread is one of them. */
    static final int SB_THREADS = Math.max(1, macapkEnvInt("MACAPK_BITMAP_SCALE_THREADS",
            Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2))));
    /** Below this many destination pixels a blit runs on the calling thread. */
    private static final long SB_PARALLEL_MIN_PX = 256L * 1024;
    private static volatile java.util.concurrent.ExecutorService sSbPool;
    private static java.util.concurrent.ExecutorService sbPool() {
        java.util.concurrent.ExecutorService p = sSbPool;
        if (p != null) return p;
        synchronized (Canvas.class) {
            if (sSbPool == null) {
                final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
                sSbPool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, SB_THREADS - 1), r -> {
                    Thread t = new Thread(r, "macapk-raster-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
            }
            return sSbPool;
        }
    }
    /** One destination row, SrcOver: horizontal interpolation of the vertical pass
     *  (va..vb, 16-bit fixed) and the IntArgbPre -> IntArgb SrcOver. Consecutive
     *  columns that blend the SAME source pair (every column of an upscale's run)
     *  share their loads; only the weight changes. Kept as its own small method so
     *  the JIT compiles the hot loop on its own. */
    private static void sbRowSrcOver(int[] dd, int base, int n, int[] c0, int[] c1, int[] fx,
                                     int[] va, int[] vr, int[] vg, int[] vb, int extraA) {
        int k = 0;
        while (k < n) {
            final int i0 = c0[k], i1 = c1[k];
            final int A0 = (va[i0] << 8) + (1 << 15), dA = va[i1] - va[i0];
            final int R0 = (vr[i0] << 8) + (1 << 15), dR = vr[i1] - vr[i0];
            final int G0 = (vg[i0] << 8) + (1 << 15), dG = vg[i1] - vg[i0];
            final int B0 = (vb[i0] << 8) + (1 << 15), dB = vb[i1] - vb[i0];
            do {
                final int f = fx[k];
                final int A = (A0 + dA * f) >> 16;
                if (A != 0) {
                    final int R = (R0 + dR * f) >> 16, G = (G0 + dG * f) >> 16, B = (B0 + dB * f) >> 16;
                    if (A == 255 && extraA == 255) dd[base + k] = 0xff000000 | (R << 16) | (G << 8) | B;
                    else dd[base + k] = sbSrcOverPreToArgb(A, R, G, B, extraA, dd[base + k]);
                }
                k++;
            } while (k < n && c0[k] == i0 && c1[k] == i1);
        }
    }
    /** One destination row through a BlendComposite. blendPx is a pure function of
     *  (source, destination), and a pattern over a smooth gradient repeats the same
     *  pair along a run, so a one-entry memo = {lastS, lastD, lastR} skips it. */
    private static void sbRowBlend(int[] dd, int base, int n, int[] c0, int[] c1, int[] fx,
                                   int[] va, int[] vr, int[] vg, int[] vb,
                                   BlendComposite.Ctx blend, boolean zeroKeeps, int[] memo) {
        int lastS = memo[0], lastD = memo[1], lastR = memo[2];
        int k = 0;
        while (k < n) {
            final int i0 = c0[k], i1 = c1[k];
            final int A0 = (va[i0] << 8) + (1 << 15), dA = va[i1] - va[i0];
            final int R0 = (vr[i0] << 8) + (1 << 15), dR = vr[i1] - vr[i0];
            final int G0 = (vg[i0] << 8) + (1 << 15), dG = vg[i1] - vg[i0];
            final int B0 = (vb[i0] << 8) + (1 << 15), dB = vb[i1] - vb[i0];
            do {
                final int f = fx[k];
                final int A = (A0 + dA * f) >> 16;
                final int d = dd[base + k];
                if (A != 0 || !zeroKeeps || (d >>> 24) != 255) {
                    final int sv = (A << 24) | (((R0 + dR * f) >> 16) << 16)
                                 | (((G0 + dG * f) >> 16) << 8) | ((B0 + dB * f) >> 16);
                    if (sv != lastS || d != lastD) {
                        lastS = sv; lastD = d;
                        lastR = blend.blendPx(sv, d);
                    }
                    dd[base + k] = lastR;
                }
                k++;
            } while (k < n && c0[k] == i0 && c1[k] == i1);
        }
        memo[0] = lastS; memo[1] = lastD; memo[2] = lastR;
    }
    /** The first (lo=true) or one-past-last covered device column/row for a
     *  destination edge at v: pixel centres inside the rect, i.e. ceil(v - 0.5),
     *  clamped to the clip [c0, c1]; Integer.MIN_VALUE when v sits on a half-pixel
     *  tie (within 1e-6) that the clip does not hide. */
    private static int sbEdge(double v, int c0, int c1, boolean lo) {
        // macapkClipRound accepts an EXACT half-pixel (it is exact for a clip);
        // for coverage it is the ambiguous case too: Java2D tests the centre in
        // 32.32 fixed point after an inverse transform, which can land on either
        // side of the source edge.
        final double t = v - 0.5;
        final int e = (Math.abs(t - Math.rint(t)) <= FRAMESPEED_EPS) ? Integer.MIN_VALUE : macapkClipRound(v);
        if (e == Integer.MIN_VALUE) {
            if (!(Math.abs(v) < 1e9)) return Integer.MIN_VALUE;
            final int f = (int) Math.floor(v);
            if (lo ? f + 1 <= c0 : f >= c1) return lo ? c0 : c1;   // the tie is clipped away
            return Integer.MIN_VALUE;
        }
        return Math.max(c0, Math.min(c1, e));
    }
    /** Source row `sy`, columns [sxFrom, sxFrom + n), as IntArgbPre (Java2D's
     *  IntArgb -> IntArgbPre load: each colour MUL8'd by its alpha). */
    private static void sbPremulRow(int[] sd, int sw, int sxFrom, int sy, int n, int[] out) {
        final int off = sy * sw + sxFrom;
        for (int i = 0; i < n; i++) {
            final int p = sd[off + i];
            final int a = p >>> 24;
            if (a == 255) { out[i] = p; continue; }
            if (a == 0) { out[i] = 0; continue; }
            final int r = SB_MUL8[(a << 8) | ((p >> 16) & 0xff)] & 0xff;
            final int gg = SB_MUL8[(a << 8) | ((p >> 8) & 0xff)] & 0xff;
            final int b = SB_MUL8[(a << 8) | (p & 0xff)] & 0xff;
            out[i] = (a << 24) | (r << 16) | (gg << 8) | b;
        }
    }
    /** One IntArgbPre source pixel SrcOver an IntArgb destination at paint alpha
     *  extraA: Java2D's DEFINE_SRCOVER_MASKBLIT with no mask (AlphaMacros.h). */
    private static int sbSrcOverPreToArgb(int sA, int sR, int sG, int sB, int extraA, int d) {
        int resA = (extraA == 255) ? sA : sbMul8(extraA, sA);
        if (resA == 0) return d;
        final int srcF = extraA;   // a premultiplied source scales its colours by extraA
        int resR = sR, resG = sG, resB = sB;
        if (resA < 255) {
            if (srcF < 255) { resR = sbMul8(srcF, resR); resG = sbMul8(srcF, resG); resB = sbMul8(srcF, resB); }
            final int dstF = 255 - resA;
            final int dA = d >>> 24;
            final int dstA = sbMul8(dstF, dA);
            resA += dstA;
            if (dstA != 0) {
                int dR = (d >> 16) & 0xff, dG = (d >> 8) & 0xff, dB = d & 0xff;
                if (dstA < 255) { dR = sbMul8(dstA, dR); dG = sbMul8(dstA, dG); dB = sbMul8(dstA, dB); }
                resR += dR; resG += dG; resB += dB;
            }
            if (resA < 255) { resR = sbDiv8(resA, resR); resG = sbDiv8(resA, resG); resB = sbDiv8(resA, resB); }
        } else if (srcF < 255) {
            resR = sbMul8(srcF, resR); resG = sbMul8(srcF, resG); resB = sbMul8(srcF, resB);
        }
        return (resA << 24) | (resR << 16) | (resG << 8) | resB;
    }

    private static int[] stockArgbData(BufferedImage img) {
        if (img.getType() != BufferedImage.TYPE_INT_ARGB) return null;
        java.awt.image.WritableRaster r = img.getRaster();
        java.awt.image.DataBuffer db = r.getDataBuffer();
        java.awt.image.SampleModel sm = r.getSampleModel();
        final int w = img.getWidth(), h = img.getHeight();
        if (!(db instanceof java.awt.image.DataBufferInt) || db.getOffset() != 0
                || db.getNumBanks() != 1
                || !(sm instanceof java.awt.image.SinglePixelPackedSampleModel)
                || ((java.awt.image.SinglePixelPackedSampleModel) sm).getScanlineStride() != w
                || r.getSampleModelTranslateX() != 0 || r.getSampleModelTranslateY() != 0
                || r.getWidth() != w || r.getHeight() != h) return null;
        int[] data = ((java.awt.image.DataBufferInt) db).getData();
        return (data.length >= w * h) ? data : null;
    }
    /** Everything filterPixel reads from `cf`, as a value; null for a filter kind it does not know
     *  (those are never cached). */
    private static Object filterKey(ColorFilter cf) {
        if (cf instanceof PorterDuffColorFilter f)
            return java.util.Arrays.asList(cf.getClass(), f.getMode(), f.getColor());
        if (cf instanceof BlendModeColorFilter f)
            return java.util.Arrays.asList(cf.getClass(), f.getMode(), f.getColor());
        if (cf instanceof LightingColorFilter f)
            return java.util.Arrays.asList(cf.getClass(), f.getColorMultiply(), f.getColorAdd());
        if (cf instanceof ColorMatrixColorFilter f) {
            float[] m = f.macapkArray();
            if (m == null) return null;
            java.util.List<Object> k = new java.util.ArrayList<>(m.length + 1);
            k.add(cf.getClass());
            for (float v : m) k.add(v);
            return k;
        }
        return null;
    }
    /** Skia's paint-alpha modulation of one straight-alpha pixel: alpha * a / 255, rounded. */
    private static int scaleAlpha(int px, int a) {
        return ((((px >>> 24) * a + 127) / 255) << 24) | (px & 0x00ffffff);
    }
    /** Box-blur a recorded layer bitmap (RenderEffect blur approximation). Radius
     *  capped so a large blur stays bounded; EDGE_NO_OP keeps the edges from
     *  darkening. v1.0.434. */
    private static BufferedImage blurImage(BufferedImage src, float radius) {
        int r = Math.max(1, Math.min(25, Math.round(radius)));
        int size = r * 2 + 1;
        float[] data = new float[size * size];
        java.util.Arrays.fill(data, 1f / (size * size));
        try {
            java.awt.image.Kernel k = new java.awt.image.Kernel(size, size, data);
            java.awt.image.ConvolveOp op = new java.awt.image.ConvolveOp(
                k, java.awt.image.ConvolveOp.EDGE_NO_OP, null);
            BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
            op.filter(src, dst);
            return dst;
        } catch (Throwable t) { return src; }
    }
    private static BufferedImage macapkApplySkiaFilter(BufferedImage src, long filterPtr) {
        if (src == null || filterPtr == 0 || !com.macapk.graphics.MacApkSkia.isAvailable()) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        try {
            int[] inPixels = null;
            java.awt.image.Raster raster = src.getRaster();
            if (src.getType() == BufferedImage.TYPE_INT_ARGB
                    && raster.getDataBuffer() instanceof java.awt.image.DataBufferInt
                    && raster.getDataBuffer().getOffset() == 0
                    && raster.getDataBuffer().getNumBanks() == 1
                    && raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0
                    && raster.getSampleModel() instanceof java.awt.image.SinglePixelPackedSampleModel
                    && ((java.awt.image.SinglePixelPackedSampleModel) raster.getSampleModel()).getScanlineStride() == w) {
                inPixels = ((java.awt.image.DataBufferInt) raster.getDataBuffer()).getData();
            }
            if (inPixels == null) inPixels = src.getRGB(0, 0, w, h, null, 0, w);
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] outPixels = ((java.awt.image.DataBufferInt) dst.getRaster().getDataBuffer()).getData();
            if (com.macapk.graphics.MacApkSkia.applyFilter(filterPtr, inPixels, w, h, outPixels)) return dst;
        } catch (Throwable t) {
            System.err.println("[W] macapkApplySkiaFilter error: " + t);
        }
        return src;
    }
    /** Composite for a bitmap blit: the paint's blend mode, modulated by the paint alpha (so a
     *  Coil/Glide cross-fade, whose paint alpha ramps 0..255, actually fades).
     *
     *  ⚠ This used to force SRC_OVER unconditionally, discarding any BlendMode/Xfermode the app had
     *  set. AOSP `Canvas.drawBitmap(..., Paint)` honours the paint's xfermode like every other draw,
     *  and Skia applies `SkPaint::getBlendMode` to image draws. The forced SrcOver silently broke
     *  the standard bitmap-mask idiom (`drawBitmap(mask, ..., paintWith(DST_IN))`), additive
     *  particle sprites (ADD/PLUS) and lighting overlays (MULTIPLY) -- all of which just composited
     *  normally instead, i.e. wrong pixels with no error anywhere.
     *
     *  The original intent -- "a leaked Clear must not erase images" -- is preserved by the
     *  fall-through: with no blend mode and no PorterDuff xfermode we still set SrcOver EXPLICITLY
     *  rather than inheriting whatever was last on the Graphics2D. So a stale composite still
     *  cannot leak in; only an alpha the app actually asked for gets through. */
    private void applyBitmapComposite(Paint p) {
        int a = (p != null) ? p.getAlpha() : 255;
        float ea = Math.max(0f, Math.min(1f, a / 255f));
        java.awt.Composite c = bitmapRuleFor(p);
        if (c == null) {
            g.setComposite(a >= 255 ? AlphaComposite.SrcOver
                                    : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ea));
            return;
        }
        // Fold the paint alpha into the chosen rule. AlphaComposite carries it natively. A
        // BlendComposite (the separable modes) takes it through withAlpha -- UNLESS the paint also
        // has a colour filter, where Skia scales BEFORE the filter (see PAINT ALPHA at
        // BlendComposite) and prepImage folds it into the filter pass instead (blendPreFilterAlpha).
        //
        // ⚠ The AlphaComposite fold still runs AFTER the colour filter, which is the pre-existing
        // order mismatch with Skia: it matters only for a filter that does not scale with source
        // alpha (PorterDuffColorFilter SRC/SCREEN/ADD, a ColorMatrix with alpha or bias terms).
        // Left as it was; the blend-mode path does not copy it.
        if (a < 255 && c instanceof AlphaComposite)
            c = AlphaComposite.getInstance(((AlphaComposite) c).getRule(), ea);
        else if (a < 255 && c instanceof BlendComposite && MACAPK_BLEND_PAINT_ALPHA
                 && p.getColorFilter() == null)
            c = ((BlendComposite) c).withAlpha(a);
        g.setComposite(c);
    }
    /** The composite a bitmap blit's paint asks for, before any paint alpha: its BlendMode, else
     *  its PorterDuffXfermode, else null (plain SRC_OVER). */
    private static java.awt.Composite bitmapRuleFor(Paint p) {
        if (p == null) return null;
        android.graphics.BlendMode bm = p.getBlendMode();
        if (bm != null) return compositeFor(bm);
        if (p.getXfermode() instanceof android.graphics.PorterDuffXfermode)
            return compositeForPorterDuff(
                    ((android.graphics.PorterDuffXfermode) p.getXfermode()).getMode());
        return null;
    }
    /** The paint alpha prepImage must apply to the source BEFORE the colour filter, or 255 for
     *  none. Skia's order is image -> paint alpha -> colour filter -> blend
     *  (SkColorFilterShader::appendStages: `if (fAlpha != 1.0f) append(scale_1_float, fAlpha)`,
     *  then `fFilter->appendStages`). Non-255 only for a separable-blend blit with a colour filter:
     *  the AlphaComposite modes keep their historical after-the-filter fold (applyBitmapComposite),
     *  and a blit with no filter folds it into the composite, which is the same thing. */
    private static int blendPreFilterAlpha(Paint p) {
        if (!MACAPK_BLEND_PAINT_ALPHA || p == null || p.getColorFilter() == null) return 255;
        int a = Math.max(0, p.getAlpha());
        if (a >= 255) return 255;
        return (bitmapRuleFor(p) instanceof BlendComposite) ? a : 255;
    }

    // --- Helpers ------------------------------------------------------
    /** Build Skia's MIRROR tile: the source plus its reflections, so plain REPEAT tiling of the
     *  result is pixel-identical to Skia's mirrored tiling. Mirrors only the axes asked for, so
     *  MIRROR-on-x with REPEAT-on-y produces a 2w x h tile, not 2w x 2h. */
    /** applyPaint runs this for EVERY fill/stroke op, so building the 4x-area
     *  mirrored tile inline meant an animated mirrored fill allocated one per
     *  draw at 60fps. A BitmapShader is immutable (bitmap/tileX/tileY are final),
     *  so the tile is cacheable -- same SoftReference idiom the SweepGradient
     *  branch below already uses, and for the same reason. */
    private static final java.util.Map<BitmapShader, java.lang.ref.SoftReference<BufferedImage>>
        sMirrorCache = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static BufferedImage macapkMirrorTileCached(BitmapShader bs, BufferedImage src,
                                                        boolean mx, boolean my) {
        java.lang.ref.SoftReference<BufferedImage> ref = sMirrorCache.get(bs);
        BufferedImage t = (ref != null) ? ref.get() : null;
        // A mutated bitmap changes `src`'s dimensions at most; a stale tile of the
        // right size is still built from the same immutable shader, so size is a
        // sufficient validity check here.
        int wantW = mx ? src.getWidth() * 2 : src.getWidth();
        int wantH = my ? src.getHeight() * 2 : src.getHeight();
        if (t != null && t.getWidth() == wantW && t.getHeight() == wantH) return t;
        t = macapkMirrorTile(src, mx, my);
        sMirrorCache.put(bs, new java.lang.ref.SoftReference<>(t));
        return t;
    }

    private static BufferedImage macapkMirrorTile(BufferedImage src, boolean mx, boolean my) {
        int w = src.getWidth(), h = src.getHeight();
        int tw = mx ? w * 2 : w, th = my ? h * 2 : h;
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D tg = out.createGraphics();
        try {
            tg.drawImage(src, 0, 0, null);
            if (mx) tg.drawImage(src, w * 2, 0, -w, h, null);                 // flip x
            if (my) tg.drawImage(src, 0, h * 2, w, -h, null);                 // flip y
            if (mx && my) tg.drawImage(src, w * 2, h * 2, -w, -h, null);      // flip both
        } finally { tg.dispose(); }
        return out;
    }

    private BufferedImage bitmapAsImage(Bitmap b) {
        BufferedImage src = b.macapkAwtImage();
        if (src != null) return src;
        // Build a transient BufferedImage from argbBuffer if present.
        int[] argb = b.macapkArgbBuffer();
        if (argb == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return null;
        // MACAPK_BITMAP_ARGB_VIEW (default ON): draw from a zero-copy view of the bitmap's
        // own pixels instead of copying them into a fresh image on every draw (Bitmap.java,
        // macapkArgbView, has the why and the byte-identity argument). =0: the old copy.
        if (Bitmap.ARGB_VIEW) {
            BufferedImage v = b.macapkArgbView();
            if (v != null) return v;
        }
        BufferedImage tmp = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        tmp.setRGB(0, 0, w, h, argb, 0, w);
        return tmp;
    }
    private void applyPaint(Paint p) {
        macapkDrawOps++; sGlobalDrawOps++; macapkGenBump();
        int baseColor = p.getColor();
        // v1.0.433: honor the Paint's color filter + shader, the two systemic
        // silent-no-op gaps (see docs/canvas-draw-surface-diff). Color filter
        // wins for the common recolor case (icon/vector/text tint): a SRC_IN/
        // SRC_ATOP/MODULATE PorterDuff/BlendMode filter, or a ColorMatrix /
        // Lighting filter, resolves to a single ARGB for shape/text fills
        // (per-pixel matrix filtering of an IMAGE is done in drawBitmap). When
        // no filter is set, a Shader (LinearGradient/RadialGradient/etc.) drives
        // the fill via an AWT gradient/texture Paint so gradients actually
        // sample instead of painting the default-black solid color.
        ColorFilter cf = p.getColorFilter();
        Integer tint = (cf != null) ? solidColorForFilter(cf, baseColor) : null;
        Shader shader = (tint == null) ? p.getShader() : null;
        // True only while an AWT shader Paint is really installed: when the shader has no AWT
        // form we fall back to the paint colour, whose alpha byte already carries the paint alpha.
        boolean shaderInstalled = false;
        if (tint != null) {
            g.setColor(toAwtColor(tint));
            com.macapk.loader.GfxTrace.feature("colorFilter", cf.getClass().getSimpleName(), true);
        } else if (shader != null) {
            java.awt.Paint sp = awtPaintForShader(shader, p);
            if (sp != null) {
                shaderInstalled = true;
                g.setPaint(sp);
                com.macapk.loader.GfxTrace.feature("shader", shader.getClass().getSimpleName(), true);
            } else {
                g.setColor(toAwtColor(baseColor));
                com.macapk.loader.GfxTrace.feature("shader", shader.getClass().getSimpleName(), false);
            }
        } else {
            g.setColor(toAwtColor(baseColor));
        }
        float sw = p.getStrokeWidth();
        g.setStroke(macapkStrokeFor(p, sw));
        // ⚠ BOTH DIRECTIONS. This used to be an `if` with no `else`, which made
        // Paint.setAntiAlias(false) a NO-OP: once anything turned KEY_ANTIALIASING
        // on (setBitmap does, as the default) nothing ever turned it off again, so
        // a paint that explicitly asked for hard edges got feathered ones.
        //
        // Skia treats antialiasing as a per-paint REQUEST that a Bitmap-backed
        // (software) canvas honours exactly: with SkPaint::setAntiAlias(false) a
        // pixel's coverage is 0 or 1, so the only colours in the output are the
        // ones the app supplied. MEASURED (battery RAS, 2026-08-09): ~40 non-AA
        // cases -- every curve, every stroked shape, the pentagram, the join
        // matrix -- answered "n=2 colours" on the emulator and "n=14..53 colours"
        // here, purely because of the missing else.
        //
        // KEY_TEXT_ANTIALIASING is deliberately NOT touched: it is a separate hint
        // and drawText must stay smoothed regardless of the shape AA flag, which
        // is also what Skia does (glyph masks are antialiased unless the paint
        // asks otherwise via its own flag).
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                           p.isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON
                                           : RenderingHints.VALUE_ANTIALIAS_OFF);
        // v1.0.395: honor Paint.setBlendMode (API 29). Jetpack Compose's
        // VectorPainter renders each vector into an offscreen DrawCache,
        // and DrawCache.drawCachedImage clears the cache first with
        // `drawRect(color=Black, blendMode=Clear)` (see Compose UI
        // graphics/vector/DrawCache.kt). We previously ignored the blend
        // mode and filled opaque black, so every cached vector icon (the
        // banner X close button, ImageVector icons generally) rendered as
        // a black square. Mapping the Porter-Duff modes to AWT
        // AlphaComposite makes Clear actually clear the region to
        // transparent; the default (null / SRC_OVER) resets to SrcOver so
        // a prior Clear can't leak into the next draw on this Graphics2D.
        //
        // v1.0.500: ALSO honor the LEGACY Paint.setXfermode(PorterDuffXfermode)
        // for draw ops, not just the modern setBlendMode. Lottie (and many
        // pre-API-29 engines) composite via setXfermode -- our Paint stores
        // mXfermode and mBlendMode in SEPARATE fields, so a paint that only set
        // an Xfermode had getBlendMode()==null and was drawn SrcOver. That is
        // exactly Lottie's matte path: BaseLayer.clearCanvas draws a rect with
        // clearPaint = PorterDuffXfermode(CLEAR) (color defaults to opaque
        // black) to clear the content/matte layer; ignored, it painted a solid
        // BLACK BOX over the Duo owl's matted face. BlendMode (when set) wins;
        // otherwise a PorterDuffXfermode maps through awtRuleForPaint (CLEAR ->
        // AlphaComposite.Clear, DST_IN, etc.); otherwise SrcOver.
        //
        // Paint alpha with a shader is the same Skia rule in every branch below
        // (scale_1_float after the shader stages, ahead of the blend), so the
        // BlendMode and Xfermode branches fold it too -- they used to set the
        // bare composite and draw a shader fill at full strength.
        // MACAPK_BLEND_PAINT_ALPHA=0 restores that (see BlendComposite).
        android.graphics.BlendMode bm = p.getBlendMode();
        if (bm != null) {
            g.setComposite(withShaderAlpha(compositeFor(bm), shaderInstalled ? p.getAlpha() : 255));
        } else if (p.getXfermode() instanceof android.graphics.PorterDuffXfermode) {
            android.graphics.PorterDuff.Mode pm =
                    ((android.graphics.PorterDuffXfermode) p.getXfermode()).getMode();
            g.setComposite(withShaderAlpha(compositeForPorterDuff(pm),
                                           shaderInstalled ? p.getAlpha() : 255));
        } else {
            // PAINT ALPHA MUST STILL APPLY WHEN A SHADER IS SET.
            // AOSP `Paint.setColor` javadoc: with a shader installed the paint's colour is ignored
            // "except that the alpha is still applied" -- Skia modulates the shader's output by the
            // paint alpha. Our solid-colour path gets this for free (the alpha rides in the high
            // byte of `toAwtColor(baseColor)`), but the shader path calls `g.setPaint(awtPaint)`,
            // and an awt Paint carries no separate alpha -- so a shader fill rendered at FULL
            // strength no matter what the app asked for.
            //
            // Compose hits this constantly: `DrawScope.drawRect(brush, alpha = ...)` lowers to
            // exactly "shader installed, paint alpha set", so every fade-in/out over a gradient or
            // bitmap brush held at 100% instead of animating.
            //
            // AlphaComposite carries the extra alpha, which is the same modulation Skia applies, so
            // routing it through the composite is exact for the default SrcOver case. Only the
            // shader path needs it -- a solid fill would double-apply.
            //
            // ...and so would a shader with no AWT form (a plain Shader, a RuntimeShader, a
            // BitmapShader over a bitmap with no pixels): awtPaintForShader returns null and the
            // fill falls back to the paint colour, whose alpha byte already IS the paint alpha, so
            // keying this on `shader != null` drew that fallback at alpha^2 (alpha 86 -> ~29).
            // Keyed on shaderInstalled, like the BlendMode and Xfermode branches above.
            // MACAPK_BLEND_PAINT_ALPHA=0 restores the old key.
            boolean shaderAlpha = MACAPK_BLEND_PAINT_ALPHA ? shaderInstalled : (shader != null);
            int pa = shaderAlpha ? p.getAlpha() : 255;
            g.setComposite(pa >= 255 ? AlphaComposite.SrcOver
                                     : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pa / 255f));
        }
    }
    /** `c` with a shader fill's paint alpha `a` (0..255) folded into its source. */
    private static java.awt.Composite withShaderAlpha(java.awt.Composite c, int a) {
        if (!MACAPK_BLEND_PAINT_ALPHA || a >= 255) return c;
        if (c instanceof AlphaComposite ac)
            return AlphaComposite.getInstance(ac.getRule(), ac.getAlpha() * (Math.max(0, a) / 255f));
        if (c instanceof BlendComposite bc) return bc.withAlpha(a);
        return c;
    }

    /** Map android.graphics.BlendMode to an AWT Composite. The 12 Porter-Duff
     *  ALPHA modes have exact AlphaComposite equivalents; the SEPARABLE blend
     *  modes have no AlphaComposite analogue and get BlendComposite (a real
     *  per-pixel blender), which is why this no longer degrades them to SrcOver.
     *  The four NON-separable modes (HUE/SATURATION/COLOR/LUMINOSITY) operate on
     *  the colour as a whole rather than per channel and are still SrcOver --
     *  that gap is named rather than hidden. */
    private static java.awt.Composite compositeFor(android.graphics.BlendMode bm) {
        if (bm == null) return AlphaComposite.SrcOver;
        if (bm == android.graphics.BlendMode.CLEAR)    return AlphaComposite.Clear;
        if (bm == android.graphics.BlendMode.SRC)      return AlphaComposite.Src;
        if (bm == android.graphics.BlendMode.DST)      return AlphaComposite.Dst;
        if (bm == android.graphics.BlendMode.SRC_OVER) return AlphaComposite.SrcOver;
        if (bm == android.graphics.BlendMode.DST_OVER) return AlphaComposite.DstOver;
        if (bm == android.graphics.BlendMode.SRC_IN)   return AlphaComposite.SrcIn;
        if (bm == android.graphics.BlendMode.DST_IN)   return AlphaComposite.DstIn;
        if (bm == android.graphics.BlendMode.SRC_OUT)  return AlphaComposite.SrcOut;
        if (bm == android.graphics.BlendMode.DST_OUT)  return AlphaComposite.DstOut;
        if (bm == android.graphics.BlendMode.SRC_ATOP) return AlphaComposite.SrcAtop;
        if (bm == android.graphics.BlendMode.DST_ATOP) return AlphaComposite.DstAtop;
        if (bm == android.graphics.BlendMode.XOR)      return AlphaComposite.Xor;
        if (bm == android.graphics.BlendMode.PLUS)        return BlendComposite.PLUS;
        if (bm == android.graphics.BlendMode.MODULATE)    return BlendComposite.MODULATE;
        if (bm == android.graphics.BlendMode.SCREEN)      return BlendComposite.SCREEN;
        if (bm == android.graphics.BlendMode.OVERLAY)     return BlendComposite.OVERLAY;
        if (bm == android.graphics.BlendMode.DARKEN)      return BlendComposite.DARKEN;
        if (bm == android.graphics.BlendMode.LIGHTEN)     return BlendComposite.LIGHTEN;
        if (bm == android.graphics.BlendMode.COLOR_DODGE) return BlendComposite.COLOR_DODGE;
        if (bm == android.graphics.BlendMode.COLOR_BURN)  return BlendComposite.COLOR_BURN;
        if (bm == android.graphics.BlendMode.HARD_LIGHT)  return BlendComposite.HARD_LIGHT;
        if (bm == android.graphics.BlendMode.SOFT_LIGHT)  return BlendComposite.SOFT_LIGHT;
        if (bm == android.graphics.BlendMode.DIFFERENCE)  return BlendComposite.DIFFERENCE;
        if (bm == android.graphics.BlendMode.EXCLUSION)   return BlendComposite.EXCLUSION;
        if (bm == android.graphics.BlendMode.MULTIPLY)    return BlendComposite.MULTIPLY;
        return AlphaComposite.SrcOver;   // HUE/SATURATION/COLOR/LUMINOSITY
    }

    /** The same mapping for the legacy PorterDuff.Mode spelling. AOSP's
     *  PorterDuff.Mode carries SIX modes that are not Porter-Duff alpha
     *  operators at all -- DARKEN, LIGHTEN, MULTIPLY, SCREEN, ADD, OVERLAY --
     *  and those are precisely the ones AlphaComposite cannot express. */
    private static java.awt.Composite compositeForPorterDuff(android.graphics.PorterDuff.Mode m) {
        if (m == null) return AlphaComposite.SrcOver;
        switch (m) {
            case CLEAR:    return AlphaComposite.Clear;
            case SRC:      return AlphaComposite.Src;
            case DST:      return AlphaComposite.Dst;
            case SRC_OVER: return AlphaComposite.SrcOver;
            case DST_OVER: return AlphaComposite.DstOver;
            case SRC_IN:   return AlphaComposite.SrcIn;
            case DST_IN:   return AlphaComposite.DstIn;
            case SRC_OUT:  return AlphaComposite.SrcOut;
            case DST_OUT:  return AlphaComposite.DstOut;
            case SRC_ATOP: return AlphaComposite.SrcAtop;
            case DST_ATOP: return AlphaComposite.DstAtop;
            case XOR:      return AlphaComposite.Xor;
            case DARKEN:   return BlendComposite.DARKEN;
            case LIGHTEN:  return BlendComposite.LIGHTEN;
            case MULTIPLY: return BlendComposite.MULTIPLY;
            case SCREEN:   return BlendComposite.SCREEN;
            case ADD:      return BlendComposite.PLUS;
            case OVERLAY:  return BlendComposite.OVERLAY;
            default:       return AlphaComposite.SrcOver;
        }
    }

    // ------------------------------------------------------------------
    // Separable blend modes as a real java.awt.Composite
    // ------------------------------------------------------------------
    //
    // AlphaComposite implements only the twelve Porter-Duff ALPHA operators.
    // Android's PorterDuff.Mode and BlendMode both carry SEPARABLE BLEND modes
    // on top of those (MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, ADD, and --
    // via BlendMode -- COLOR_DODGE/COLOR_BURN/HARD_LIGHT/SOFT_LIGHT/DIFFERENCE/
    // EXCLUSION/MODULATE), and every one of them used to fall through
    // `default: return AlphaComposite.SRC_OVER`, i.e. the mode was accepted,
    // stored, and then silently ignored. MEASURED (RAS.xfer.multiply/screen and
    // RAS.color.mode_multiply/mode_screen): the emulator answers 0xFF002030 and
    // 0xFF10FFFF, we answered the raw source colour 0xFF00FFFF both times.
    //
    // The arithmetic is the standard separable-blend formula (W3C
    // compositing-1 s9, which is what SkBlendMode implements), stated on
    // PREMULTIPLIED values:
    //     ar          = as + ad*(1 - as)
    //     Cr*ar       = (1 - ad)*as*Cs + (1 - as)*ad*Cd + as*ad*B(Cs, Cd)
    // For the fully opaque case both the emulator and this reduce to B(Cs, Cd)
    // exactly, with no rounding term -- which is why the probe pinned its source
    // channels to 0x00/0xFF and kept those rows in the STRICT family.
    // kPlus/ADD is not of that shape: Skia defines it as min(1, s + d) on
    // premultiplied values including alpha, and kModulate as s*d, so both are
    // handled directly.
    //
    // Cost: Java2D falls off its fast blit loops for a custom Composite, so this
    // is per-pixel Java. That is confined to paints that actually ask for one of
    // these modes; SRC_OVER and the eleven other alpha modes keep AlphaComposite
    // and are untouched. The per-pixel work is kept small by reading Java2D's
    // packed-int rasters directly (composePacked) and by skipping source pixels
    // that contribute nothing (blendPx); see MACAPK_BLEND_FASTPATH below.
    //
    // PAINT ALPHA. Skia modulates the source by the paint alpha BEFORE the blend:
    // SkRasterPipelineBlitter.cpp appends `scale_1_float(dstPaintColor->fA)` right
    // after the shader stages, and for a paint with a colour filter
    // SkColorFilterShader::appendStages does the same scale ahead of the filter
    // (SkPaintPriv::RemoveColorFilter: "SkColorFilterShader will modulate the
    // shader color by paint alpha before applying the filter"). So a separable
    // mode at paint alpha a blends a source whose alpha is `as * a/255`. This
    // class used to have no extra-alpha form at all, which drew every blend-mode
    // bitmap and shader fill at FULL strength. MEASURED victim: Telegram's chat
    // wallpaper pattern (SOFT_LIGHT + PorterDuffColorFilter(black, SRC_IN) at
    // alpha 86 over 0xff88b884) came out #498544 where Skia's formula and the
    // emulator's measured darkening (G ratio 0.888) give #73a76f -- about three
    // times too dark. docs/telegram-chat-wallpaper-pattern_2026-09-25.md.
    // Bisect: MACAPK_BLEND_PAINT_ALPHA=0 restores the full-strength draw (and the
    // SRC_OVER branch of applyPaint's alpha^2 on a shader with no AWT form).
    private static final boolean MACAPK_BLEND_PAINT_ALPHA =
        !"0".equals(System.getenv("MACAPK_BLEND_PAINT_ALPHA"));
    // The packed-int compose path and the colour-filtered bitmap cache are pure
    // speedups: both are pixel-identical to the generic path by construction
    // (debug/blendalpha_gate.sh checks it). MEASURED victim: the same Telegram
    // draw, a 1636x3362 pattern scaled over a 3360x1636 window, cost ~187 ms:
    // ~44 re-filtering the whole 5.5 Mpx source, ~95 in Raster.getPixel/setPixel,
    // and ~46 in Java2D's own bilinear resample, which this does not touch. After:
    // ~61 ms (the same bitmap drawn SRC_OVER is ~38); a 1:1 blit 133 -> 11 ms.
    // `debug/blendalpha_gate.sh --perf` reproduces the table. Bisect:
    // MACAPK_BLEND_FASTPATH=0 keeps every compose on the generic Raster path;
    // MACAPK_BITMAP_FILTER_CACHE=0 re-filters on every draw as before.
    private static final boolean MACAPK_BLEND_FASTPATH =
        !"0".equals(System.getenv("MACAPK_BLEND_FASTPATH"));
    private static final boolean MACAPK_BITMAP_FILTER_CACHE =
        !"0".equals(System.getenv("MACAPK_BITMAP_FILTER_CACHE"));

    // ---- MACAPK_TRACE_DRAWCOST=1: which draw OP dominates a frame (diagnostic) ----
    // The frame-phase trace says "draw = 137 ms"; a JFR of the draw thread cannot
    // say which call, because the hot code is a native Java2D loop or a callback
    // whose stack JFR truncates at the native entry. This times the public draw
    // entry points (drawBitmap x3, drawRect, drawRoundRect, drawPath, drawPaint,
    // drawColor) and every 5 s logs the 14 heaviest keys -- op + source/dest size,
    // composite, shader, colour filter, filter flag, device clip, and the calling
    // class -- as `[draw-cost]` (tag Mac.apk.DrawCost). It found Telegram's two
    // wallpaper blits (120 of 137 ms) in one run (2026-10-01,
    // docs/telegram-wallpaper-scaled-blit_2026-10-01.md). It walks the stack per
    // op, so its own cost inflates frame times: use it to RANK ops, never to
    // measure a frame. Off (unset): each entry point is one static-final branch.
    static final boolean TRACE_DRAWCOST = "1".equals(System.getenv("MACAPK_TRACE_DRAWCOST"));
    private static final java.util.HashMap<String, long[]> sDrawCost = new java.util.HashMap<>();
    private static long sDrawCostT0;
    private static String bdim(Bitmap b) { return b == null ? "null" : b.getWidth() + "x" + b.getHeight(); }
    private void drawCost(long t0, String what, Paint p) {
        long dt = System.nanoTime() - t0;
        String caller = "?";
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 2; i < st.length; i++) {
            String c = st[i].getClassName();
            if (!c.startsWith("android.graphics.") ) { caller = c.substring(c.lastIndexOf('.') + 1) + "." + st[i].getMethodName(); break; }
        }
        String comp = "";
        try { java.awt.Composite c = g.getComposite(); comp = c.getClass().getSimpleName(); if (c instanceof java.awt.AlphaComposite ac) comp += ac.getRule(); } catch (Throwable ignored) {}
        String key = what + " comp=" + comp + (p != null && p.getShader() != null ? " sh=" + p.getShader().getClass().getSimpleName() : "")
            + (p != null && p.getColorFilter() != null ? " cf=" + p.getColorFilter().getClass().getSimpleName() : "")
            + (p != null && p.getXfermode() != null ? " xf" : "") + " filt=" + (p != null && p.isFilterBitmap())
            + " clip=" + clipDim() + " <- " + caller;
        long now = System.nanoTime();
        synchronized (sDrawCost) {
            long[] a = sDrawCost.computeIfAbsent(key, k -> new long[2]); a[0]++; a[1] += dt;
            if (sDrawCostT0 == 0) sDrawCostT0 = now;
            if (now - sDrawCostT0 > 5_000_000_000L) {
                StringBuilder sb = new StringBuilder("[draw-cost] 5s top by total ms:");
                sDrawCost.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(14)
                    .forEach(e -> sb.append(String.format("%n  %8.1fms n=%-5d %s", e.getValue()[1] / 1e6, e.getValue()[0], e.getKey())));
                com.macapk.loader.NativeLog.i("Mac.apk.DrawCost", sb.toString());
                sDrawCost.clear(); sDrawCostT0 = now;
            }
        }
    }
    private String clipDim() { try { java.awt.Rectangle r = g.getClipBounds(); return r == null ? "none" : r.width + "x" + r.height; } catch (Throwable t) { return "?"; } }
    private static final class BlendComposite implements java.awt.Composite {
        private static final int B_MULTIPLY = 0, B_SCREEN = 1, B_OVERLAY = 2,
                                 B_DARKEN = 3, B_LIGHTEN = 4, B_COLOR_DODGE = 5,
                                 B_COLOR_BURN = 6, B_HARD_LIGHT = 7, B_SOFT_LIGHT = 8,
                                 B_DIFFERENCE = 9, B_EXCLUSION = 10,
                                 B_PLUS = 11, B_MODULATE = 12;

        static final BlendComposite MULTIPLY    = new BlendComposite(B_MULTIPLY);
        static final BlendComposite SCREEN      = new BlendComposite(B_SCREEN);
        static final BlendComposite OVERLAY     = new BlendComposite(B_OVERLAY);
        static final BlendComposite DARKEN      = new BlendComposite(B_DARKEN);
        static final BlendComposite LIGHTEN     = new BlendComposite(B_LIGHTEN);
        static final BlendComposite COLOR_DODGE = new BlendComposite(B_COLOR_DODGE);
        static final BlendComposite COLOR_BURN  = new BlendComposite(B_COLOR_BURN);
        static final BlendComposite HARD_LIGHT  = new BlendComposite(B_HARD_LIGHT);
        static final BlendComposite SOFT_LIGHT  = new BlendComposite(B_SOFT_LIGHT);
        static final BlendComposite DIFFERENCE  = new BlendComposite(B_DIFFERENCE);
        static final BlendComposite EXCLUSION   = new BlendComposite(B_EXCLUSION);
        static final BlendComposite PLUS        = new BlendComposite(B_PLUS);
        static final BlendComposite MODULATE    = new BlendComposite(B_MODULATE);

        private final int mode;
        /** Paint alpha folded into the source, 0..1; 1 for the shared instances
         *  above. Applied to the STRAIGHT source alpha, after unpremultiplying, so
         *  the colour channels are left alone (see PAINT ALPHA above). */
        private final float extraAlpha;
        private BlendComposite(int mode) { this(mode, 1f); }
        private BlendComposite(int mode, float extraAlpha) {
            this.mode = mode; this.extraAlpha = extraAlpha;
        }

        /** The per-pixel arithmetic for a caller that composes itself (macapkScaledBlit). */
        Ctx macapkCtx(boolean srcPre, boolean dstPre) { return new Ctx(mode, srcPre, dstPre, extraAlpha); }
        /** blendPx(0, d) == d for an opaque d (every mode but MODULATE; see blendPx). */
        boolean macapkZeroSourceKeepsOpaqueDst() { return mode != B_MODULATE; }

        /** This mode at paint alpha `a` (0..255). 255 is this very instance, so a
         *  fully opaque paint takes the same arithmetic it always did. */
        BlendComposite withAlpha(int a) {
            if (a >= 255) return this;
            return new BlendComposite(mode, extraAlpha * (Math.max(0, a) / 255f));
        }

        @Override
        public java.awt.CompositeContext createContext(java.awt.image.ColorModel srcCM,
                                                       java.awt.image.ColorModel dstCM,
                                                       RenderingHints hints) {
            return new Ctx(mode,
                           srcCM != null && srcCM.isAlphaPremultiplied(),
                           dstCM != null && dstCM.isAlphaPremultiplied(),
                           extraAlpha);
        }

        /** One blend channel, all inputs and outputs in 0..1. */
        private static float blend(int mode, float cs, float cd) {
            switch (mode) {
                case B_MULTIPLY:    return cs * cd;
                case B_SCREEN:      return cs + cd - cs * cd;
                case B_OVERLAY:     return hardLight(cd, cs);   // OVERLAY is HARD_LIGHT with the operands swapped
                case B_DARKEN:      return Math.min(cs, cd);
                case B_LIGHTEN:     return Math.max(cs, cd);
                case B_COLOR_DODGE:
                    if (cd <= 0f) return 0f;
                    if (cs >= 1f) return 1f;
                    return Math.min(1f, cd / (1f - cs));
                case B_COLOR_BURN:
                    if (cd >= 1f) return 1f;
                    if (cs <= 0f) return 0f;
                    return 1f - Math.min(1f, (1f - cd) / cs);
                case B_HARD_LIGHT:  return hardLight(cs, cd);
                case B_SOFT_LIGHT:  return softLight(cs, cd);
                case B_DIFFERENCE:  return Math.abs(cs - cd);
                case B_EXCLUSION:   return cs + cd - 2f * cs * cd;
                default:            return cs;
            }
        }
        private static float hardLight(float cs, float cd) {
            return (cs <= 0.5f) ? (cd * 2f * cs)
                                : (cd + (2f * cs - 1f) - cd * (2f * cs - 1f));
        }
        private static float softLight(float cs, float cd) {
            float d;
            if (cd <= 0.25f) d = ((16f * cd - 12f) * cd + 4f) * cd;
            else             d = (float) Math.sqrt(cd);
            return (cs <= 0.5f) ? (cd - (1f - 2f * cs) * cd * (1f - cd))
                                : (cd + (2f * cs - 1f) * (d - cd));
        }

        private static final class Ctx implements java.awt.CompositeContext {
            private final int mode;
            private final boolean srcPre, dstPre;
            private final float extraAlpha;
            Ctx(int mode, boolean srcPre, boolean dstPre, float extraAlpha) {
                this.mode = mode; this.srcPre = srcPre; this.dstPre = dstPre;
                this.extraAlpha = extraAlpha;
            }
            @Override public void dispose() {}
            @Override
            public void compose(java.awt.image.Raster src, java.awt.image.Raster dstIn,
                                java.awt.image.WritableRaster dstOut) {
                try { composeImpl(src, dstIn, dstOut); }
                catch (Throwable t) {
                    // A blend that blows up must not take the whole draw pass with
                    // it -- fall back to leaving the destination alone.
                    com.macapk.loader.GfxTrace.swallow("Canvas.BlendComposite", t);
                }
            }
            private void composeImpl(java.awt.image.Raster src, java.awt.image.Raster dstIn,
                                     java.awt.image.WritableRaster dstOut) {
                final int w = Math.min(Math.min(src.getWidth(), dstIn.getWidth()), dstOut.getWidth());
                final int h = Math.min(Math.min(src.getHeight(), dstIn.getHeight()), dstOut.getHeight());
                if (w <= 0 || h <= 0) return;
                if (MACAPK_BLEND_FASTPATH && composePacked(src, dstIn, dstOut, w, h)) return;
                // The three rasters are aligned but each carries its OWN origin;
                // indexing them all from 0 is the common shortcut and is wrong for
                // a translated child raster.
                final int sx0 = src.getMinX(),    sy0 = src.getMinY();
                final int dx0 = dstIn.getMinX(),  dy0 = dstIn.getMinY();
                final int ox0 = dstOut.getMinX(), oy0 = dstOut.getMinY();
                final int sBands = src.getNumBands(), dBands = dstIn.getNumBands();
                final int oBands = dstOut.getNumBands();
                final int[] sp = new int[Math.max(4, sBands)];
                final int[] dp = new int[Math.max(4, dBands)];
                final int[] op = new int[Math.max(4, oBands)];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        src.getPixel(sx0 + x, sy0 + y, sp);
                        dstIn.getPixel(dx0 + x, dy0 + y, dp);
                        // A raster with no alpha band is opaque, which is what the
                        // 0xff alpha byte says to blendPx.
                        int r = blendPx(pack(sp, sBands > 3), pack(dp, dBands > 3));
                        op[0] = (r >>> 16) & 0xff; op[1] = (r >>> 8) & 0xff; op[2] = r & 0xff;
                        if (oBands > 3) op[3] = r >>> 24;
                        dstOut.setPixel(ox0 + x, oy0 + y, op);
                    }
                }
            }
            private static int pack(int[] p, boolean hasAlpha) {
                return ((hasAlpha ? c8(p[3]) : 0xff) << 24)
                     | (c8(p[0]) << 16) | (c8(p[1]) << 8) | c8(p[2]);
            }
            /** Every layout Java2D hands a Composite has 8-bit samples; a wider one
             *  saturates, as the float formula used to when it divided it by 255. */
            private static int c8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

            /** compose() over Java2D's own packed-int rasters (IntArgb, IntArgbPre,
             *  IntRgb: one DataBufferInt bank behind a SinglePixelPackedSampleModel
             *  with the standard masks), reading and writing the backing arrays
             *  directly instead of through Raster.getPixel/setPixel. MEASURED: those
             *  two calls were ~95 of the ~187 ms a full-window SOFT_LIGHT pattern
             *  blit cost, and ~120 of a 1:1 one's 135. Returns false, having
             *  touched nothing, for any other layout.
             *
             *  Pixel-identical to the generic loop above by construction: both feed
             *  the same packed values to the same blendPx, and a destination with no
             *  alpha band keeps its unused top byte exactly as setPixel does
             *  (SinglePixelPackedSampleModel.setPixel only rewrites the masked
             *  bits). debug/blendalpha_gate.sh diffs the two on random images. */
            private boolean composePacked(java.awt.image.Raster src, java.awt.image.Raster dstIn,
                                          java.awt.image.WritableRaster dstOut, int w, int h) {
                final int[] sd = packedData(src), dd = packedData(dstIn), od = packedData(dstOut);
                if (sd == null || dd == null || od == null) return false;
                final boolean sA = src.getNumBands() > 3, dA = dstIn.getNumBands() > 3;
                final boolean oA = dstOut.getNumBands() > 3;
                final int ss = packedStride(src), ds = packedStride(dstIn), os = packedStride(dstOut);
                int si = packedIndex(src), di = packedIndex(dstIn), oi = packedIndex(dstOut);
                // One-entry memo: a pattern stroke over a smooth gradient repeats the
                // same (source, destination) pair across neighbouring pixels, and
                // blendPx is a pure function of the pair.
                int lastS = 0, lastD = 0, lastR = blendPx(0, 0);
                for (int y = 0; y < h; y++, si += ss, di += ds, oi += os) {
                    for (int x = 0; x < w; x++) {
                        int s = sd[si + x]; if (!sA) s |= 0xff000000;
                        int d = dd[di + x]; if (!dA) d |= 0xff000000;
                        if (s != lastS || d != lastD) { lastS = s; lastD = d; lastR = blendPx(s, d); }
                        od[oi + x] = oA ? lastR : ((od[oi + x] & 0xff000000) | (lastR & 0x00ffffff));
                    }
                }
                return true;
            }
            /** The bank-0 array when `r` stores each pixel as one 0x(AA)RRGGBB int. */
            private static int[] packedData(java.awt.image.Raster r) {
                if (!(r.getDataBuffer() instanceof java.awt.image.DataBufferInt db)
                        || db.getNumBanks() != 1) return null;
                if (!(r.getSampleModel()
                        instanceof java.awt.image.SinglePixelPackedSampleModel sm)) return null;
                int[] m = sm.getBitMasks();
                if (m.length < 3 || m.length > 4
                        || m[0] != 0x00ff0000 || m[1] != 0x0000ff00 || m[2] != 0x000000ff
                        || (m.length == 4 && m[3] != 0xff000000)) return null;
                return db.getData();
            }
            private static int packedStride(java.awt.image.Raster r) {
                return ((java.awt.image.SinglePixelPackedSampleModel) r.getSampleModel())
                        .getScanlineStride();
            }
            /** Array index of the raster's (minX, minY) pixel: Raster.getPixel maps
             *  (x, y) to sample (x - sampleModelTranslateX, y - ...), and
             *  DataBufferInt.getElem(i) reads data[i + offset]. */
            private static int packedIndex(java.awt.image.Raster r) {
                return r.getDataBuffer().getOffset()
                     + (r.getMinY() - r.getSampleModelTranslateY()) * packedStride(r)
                     + (r.getMinX() - r.getSampleModelTranslateX());
            }

            /** One pixel: packed 0xAARRGGBB source and destination (premultiplied or
             *  not per srcPre / dstPre) to the packed result, in the destination's
             *  own premultiplication. Both compose paths call this and nothing else
             *  does the arithmetic, so they cannot drift apart. */
            int blendPx(int s, int d) {
                final int sa8 = s >>> 24, da8 = d >>> 24;
                // A source that contributes nothing leaves an OPAQUE destination
                // exactly as it was, in every mode but MODULATE (s*d, which clears
                // it): with as = 0 and ad = 1 the formula below is ar = 1, Cr = cd.
                // Short-circuiting it keeps a mostly-transparent overlay (Telegram's
                // pattern is ~89% alpha 0) off the float path. A translucent
                // destination is NOT short-circuited: a straight-alpha dst at alpha 0
                // comes out as 0x00000000, not as itself.
                if (da8 == 255 && mode != B_MODULATE && (sa8 == 0 || extraAlpha == 0f)) return d;
                float as = sa8 / 255f;
                final float ad = da8 / 255f;
                // Work in NON-premultiplied colour; the formula below
                // re-premultiplies where it needs to.
                float sr = ((s >>> 16) & 0xff) / 255f, sg = ((s >>> 8) & 0xff) / 255f,
                      sb = (s & 0xff) / 255f;
                float dr = ((d >>> 16) & 0xff) / 255f, dg = ((d >>> 8) & 0xff) / 255f,
                      db = (d & 0xff) / 255f;
                if (srcPre && as > 0f) { sr /= as; sg /= as; sb /= as; }
                if (dstPre && ad > 0f) { dr /= ad; dg /= ad; db /= ad; }
                // Paint alpha scales the straight source alpha (Skia's scale_1_float
                // ahead of the blend stage). 1f for an opaque paint: exact.
                as *= extraAlpha;
                float ar, rr, rg, rb;
                if (mode == B_PLUS) {
                    ar = Math.min(1f, as + ad);
                    rr = Math.min(1f, as * sr + ad * dr);
                    rg = Math.min(1f, as * sg + ad * dg);
                    rb = Math.min(1f, as * sb + ad * db);
                } else if (mode == B_MODULATE) {
                    ar = as * ad;
                    rr = (as * sr) * (ad * dr);
                    rg = (as * sg) * (ad * dg);
                    rb = (as * sb) * (ad * db);
                } else {
                    ar = as + ad * (1f - as);
                    rr = premulBlend(mode, as, ad, sr, dr);
                    rg = premulBlend(mode, as, ad, sg, dg);
                    rb = premulBlend(mode, as, ad, sb, db);
                }
                // rr/rg/rb are PREMULTIPLIED at this point.
                if (!dstPre && ar > 0f) { rr /= ar; rg /= ar; rb /= ar; }
                else if (!dstPre)       { rr = 0f; rg = 0f; rb = 0f; }
                return (to255(ar) << 24) | (to255(rr) << 16) | (to255(rg) << 8) | to255(rb);
            }
            private float premulBlend(int mode, float as, float ad, float cs, float cd) {
                return (1f - ad) * as * cs + (1f - as) * ad * cd
                        + as * ad * blend(mode, cs, cd);
            }
            private static int to255(float v) {
                int i = Math.round(v * 255f);
                return i < 0 ? 0 : (i > 255 ? 255 : i);
            }
        }
    }
    /** The font this Canvas draws `p`'s text with. Shares MacApkFont.select
     *  with Paint's measure path, which is the only thing that guarantees a
     *  wrap computed by measureText lands where the ink actually lands: an
     *  android:fontFamily="sans-serif-medium" label measured in Medium and
     *  drawn in Regular would wrap ~4% early on every line. */
    private Font deriveFont(Paint p) {
        float size = p.getTextSize() > 0 ? p.getTextSize() : 12f;
        try {
            return MacApkFont.sized(
                MacApkFont.select(p.getTypeface(), p.isFakeBoldText(),
                                  p.getTextSkewX() != 0f), size);
        } catch (Throwable ignored) {
            return MacApkFont.get(size, Font.PLAIN);
        }
    }
    private static Color toAwtColor(int argb) {
        int a = (argb >>> 24) & 0xff;
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>>  8) & 0xff;
        int b = (argb       ) & 0xff;
        return new Color(r, g, b, a);
    }

    /** Build a java.awt.BasicStroke matching the Paint's width, cap, join,
     *  miter, and -- when set -- a DashPathEffect's dash array + phase.
     *  AWT's BasicStroke dash convention (on/off lengths, dash_phase) is
     *  the same one Android's DashPathEffect uses, so a dashed stroke
     *  (borders, dividers, charts) renders correctly. Other PathEffect
     *  kinds (corner/discrete/compose/sum/pathdash) have no BasicStroke
     *  equivalent; the path geometry is what they transform, so the stroke
     *  falls back to a plain solid stroke and the effect (when applied at
     *  the path-build site) still shapes the geometry. */
    /** ⚠ width 0 is HAIRLINE and must be passed to BasicStroke AS ZERO.
     *
     *  Skia: strokeWidth 0 means a hairline -- exactly 1 DEVICE pixel whatever
     *  the CTM is. Java2D's BasicStroke(0) has the same semantics (it is
     *  identical to BasicStroke(1) at identity, and stays 1px under scale).
     *
     *  We used to clamp `sw > 0 ? sw : 1f` at three call sites, which turned a
     *  hairline into 1 USER unit -- and the CTM then scaled it. Since AOSP's
     *  DEFAULT Paint stroke width IS 0, every default-paint stroke drawn under
     *  a scaled canvas came out 2x/3x too fat: charts, zoomed custom views,
     *  anything drawing into a scaled matrix. Measured by fwprobe:
     *  RASM.line.hair_scaled2.ink read 2px wide under scale(2) where the
     *  reference reads 1. */
    private static BasicStroke macapkStrokeFor(Paint p, float width) {
        int cap = BasicStroke.CAP_BUTT;
        int join = BasicStroke.JOIN_MITER;
        float miter = 10f;
        try {
            Paint.Cap c = p.getStrokeCap();
            if (c == Paint.Cap.ROUND) cap = BasicStroke.CAP_ROUND;
            else if (c == Paint.Cap.SQUARE) cap = BasicStroke.CAP_SQUARE;
            Paint.Join j = p.getStrokeJoin();
            if (j == Paint.Join.ROUND) join = BasicStroke.JOIN_ROUND;
            else if (j == Paint.Join.BEVEL) join = BasicStroke.JOIN_BEVEL;
            float m = p.getStrokeMiter();
            // `> 1f` silently ignored a miter limit of exactly 1, leaving the
            // 10f default -- so setStrokeMiter(1) mitered a right angle that
            // Skia bevels (a 90-degree corner needs ratio sqrt(2) > 1).
            // BasicStroke's own precondition is miterlimit >= 1, so >= is both
            // AOSP-faithful and still safe.
            if (m >= 1f) miter = m;
        } catch (Throwable ignored) {}

        PathEffect pe = null;
        try { pe = p.getPathEffect(); } catch (Throwable ignored) {}
        // A CornerPathEffect rounds the joins between segments; the closest
        // BasicStroke equivalent is JOIN_ROUND, so reflect that on the
        // stroke (a faithful software approximation of the rounded corners).
        if (pe instanceof CornerPathEffect) {
            join = BasicStroke.JOIN_ROUND;
        }
        if (pe instanceof DashPathEffect) {
            DashPathEffect dpe = (DashPathEffect) pe;
            float[] dash = dpe.macapkIntervals();
            // BasicStroke rejects all-zero / non-positive dash entries; AOSP
            // tolerates them as "no dash". Guard so we never throw on draw.
            boolean valid = dash != null && dash.length >= 2;
            if (valid) {
                for (float d : dash) if (d < 0f) { valid = false; break; }
            }
            if (valid) {
                boolean anyPositive = false;
                for (float d : dash) if (d > 0f) { anyPositive = true; break; }
                if (anyPositive) {
                    try {
                        return new BasicStroke(width, cap, join, miter, dash, dpe.macapkPhase());
                    } catch (Throwable ignored) {}
                }
            }
        }
        return new BasicStroke(width, cap, join, miter);
    }

    /** AOSP Canvas.drawGlyphs (API 31): "Draw array of glyphs with specified
     *  font" at explicit positions, bypassing text shaping entirely -- the
     *  javadoc is explicit that "[the] typeface set to this paint is
     *  ignored", the Font argument is the face. This was a genuine empty
     *  body (a silent no-op), not an approximation. We have no HarfBuzz
     *  shaper anywhere in this runtime, so there is no real glyph-index
     *  space to decode a Skia/Font glyph id against -- but this codebase
     *  already established the only "glyph" contract that exists here:
     *  TextRunShaper.shape() (android/graphics/text/TextRunShaper.java)
     *  fabricates one glyph per UTF-16 char with `ids[i] = run.charAt(i)`,
     *  i.e. the glyph ID IS the char code. Consuming that same fabricated
     *  ID space is not a hack local to this method, it is the established
     *  convention every "glyph" in this runtime already uses. */
    public void drawGlyphs(int[] glyphIds, int glyphIdOffset, float[] positions, int positionOffset,
            int glyphCount, android.graphics.fonts.Font font, Paint paint) {
        if (g == null || paint == null || glyphIds == null || positions == null || glyphCount <= 0) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawGlyphs");
        applyPaint(paint);
        g.setFont(deriveFontForGlyphs(font, paint));
        for (int i = 0; i < glyphCount; i++) {
            int gid = glyphIds[glyphIdOffset + i];
            if (gid < 0 || gid > 0xFFFF) continue;  // outside the char-code glyph space above
            float x = positions[positionOffset + i * 2];
            float y = positions[positionOffset + i * 2 + 1];
            g.drawString(String.valueOf((char) gid), x, y);
        }
    }
    /** drawGlyphs' Font replaces the Paint's typeface (see javadoc quote
     *  above), so resolve size/bold/italic from paint as deriveFont() does,
     *  but the FACE from font. Font.macapkSystemFont() (the singleton
     *  TextRunShaper always hands back -- see its class comment) needs no
     *  file load; a real custom Font (built via Font.Builder from an app
     *  asset/buffer) is loaded once via the same MacApkFont.loadBase path
     *  Typeface.createFromFile/CustomFallbackBuilder use, cached by Font
     *  identity (WeakHashMap, same pattern as sSweepCache below) so a
     *  drawGlyphs-per-frame caller does not reparse the font file every
     *  call. */
    private static final java.util.Map<android.graphics.fonts.Font, java.awt.Font> sGlyphFontBaseCache =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private Font deriveFontForGlyphs(android.graphics.fonts.Font font, Paint p) {
        float size = p.getTextSize() > 0 ? p.getTextSize() : 12f;
        int style = Font.PLAIN;
        try {
            if (p.isFakeBoldText()) style |= Font.BOLD;
            if (p.getTextSkewX() != 0f) style |= Font.ITALIC;
        } catch (Throwable ignored) {}
        Font base = null;
        if (font != null) {
            try {
                android.graphics.fonts.FontStyle fs = font.getStyle();
                if (fs != null) {
                    if (fs.getWeight() >= 600) style |= Font.BOLD;
                    if (fs.getSlant() == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC) style |= Font.ITALIC;
                }
                if (font.getFile() != null || font.getBuffer() != null) {
                    base = sGlyphFontBaseCache.computeIfAbsent(font, f -> {
                        if (f.getFile() != null) return MacApkFont.loadBase(f.getFile());
                        java.nio.ByteBuffer b = f.getBuffer().duplicate();
                        byte[] bytes = new byte[b.remaining()];
                        b.get(bytes);
                        return MacApkFont.loadBase(new java.io.ByteArrayInputStream(bytes));
                    });
                }
            } catch (Throwable ignored) {}
        }
        return (base != null) ? MacApkFont.getFrom(base, size, style) : MacApkFont.get(size, style);
    }
    /** AOSP Canvas.drawTextRun(MeasuredText,...): "See drawTextRun(CharSequence,
     *  ...) for more details. This method uses a MeasuredText rather than
     *  CharSequence." Was a genuine empty body. MeasuredText.getChars() (our
     *  android/graphics/text/MeasuredText.java) exposes the backing char[] the
     *  Builder measured, so this delegates through drawText exactly the way
     *  the char[] and CharSequence siblings above already do -- context
     *  indices and isRtl remain shaping hints we don't act on, same as those
     *  siblings. */
    public void drawTextRun(android.graphics.text.MeasuredText text, int start, int end,
            int contextStart, int contextEnd, float x, float y, boolean isRtl, Paint paint) {
        if (text == null) return;
        com.macapk.loader.GfxTrace.real("Canvas.drawTextRun(MeasuredText)");
        char[] chars = text.getChars();
        if (chars == null || start < 0 || end > chars.length || start > end) return;
        drawText(new String(chars, start, end - start), x, y, paint);
    }

    // AOSP clipRegion(Region[, Op]) (Canvas.java, verified against aosp-mirror
    // main): both overloads are @removed + @Deprecated and their real
    // implementation is `return false;` unconditionally -- "Unlike all other
    // clip calls this API does not respect the current matrix. Use
    // clipRect(Rect) as an alternative." This is one of the rare cases where
    // matching AOSP means matching a method that does nothing even on a real
    // device (not reachable from normal compiled bytecode either: @removed
    // means it is absent from the public SDK stub apps compile against).
    // Was returning `region != null && !region.isEmpty()`, which -- for the
    // no-op-either-way case -- is a difference without a practical effect,
    // but the exact return value matters if reflection-based caller code
    // branches on it.
    public boolean clipRegion(android.graphics.Region region) { return false; }
    public boolean clipRegion(android.graphics.Region region, android.graphics.Region.Op op) { return false; }
    public long getNativeCanvasWrapper() { return 0L; }
    public void setScreenDensity(int density) { }
}


