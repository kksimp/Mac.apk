// android.graphics.MacApkDisplayList -- the OP LIST behind an op-list
// RenderNode / Picture recording (Stage A of
// docs/display-list-rendernode-design_2026-09-26.md). Not AOSP API; package-
// private on purpose.
//
// WHAT IT REPLACES. A RenderNode used to be a per-node ARGB Bitmap: every
// beginRecording allocated a fresh node-sized bitmap, the content rasterized
// into it, and Canvas.drawRenderNode composited it back with SrcOver. That is
// the wrong STRUCTURE, not merely a slow one:
//   - it is a snapshot, so a child that re-records is not seen by a parent that
//     flattened it; two mechanisms (macapkChildRecorded, macapkPropertyChanged)
//     then force the whole ancestor chain to re-record, each level paying a
//     full-size allocation and a full-size composite. MEASURED at 3360x1796 on a
//     Compose-shaped tree: record-all 91 ms, one leaf re-record 33 ms;
//   - a destination-clearing op (CLEAR, SRC, DST_OUT, punchHole) only clears the
//     node's own offscreen, never the frame (the v1.0.997 punch workaround;
//     here a hole reaches the frame directly, and from under a layer through
//     hwui's TransformCanvas pre-pass, replayPunches);
//   - overlapping translucent content composites twice (8448 px differ from a
//     direct draw on two overlapping translucent circles, design note M3).
// hwui records OPS and holds child nodes BY REFERENCE (RecordingCanvas
// .drawRenderNode -> nDrawRenderNode; RenderNode.java:47-51 android14: "A
// RenderNode only needs its display list re-recorded when its content alone
// should be changed"). This class is that list, on our software Canvas.
//
// HOW. Ops are recorded at the android.graphics.Canvas API level (one op per
// public method call, RecordingCanvas overrides every one) and replayed by
// calling the SAME public method on the target canvas. So a replay inherits
// every Canvas fast path, layer pool and blend composite with no second
// implementation, and it is pixel-identical to issuing the calls directly --
// which is the contract debug/displaylist_gate.sh proves. Arguments that the
// app may mutate after the call are SNAPSHOT at record time, the way hwui
// copies them into its DisplayListData (Paint, Path, Rect/RectF, Matrix, the
// arrays, the text range, a Shader's local matrix); immutable-after-record
// objects and the things AOSP draws BY REFERENCE (RenderNode, Bitmap,
// NinePatch, Typeface, MeasuredText, Font) are held by reference.
//
// Save counts are recorded in the recording's own space (1 = the recording's
// base) and rebased onto the target's count at replay, and setMatrix is
// replayed relative to the target's matrix AT THE START of the replay, as
// hwui's DisplayListData does (android14 libs/hwui/RecordingCanvas.cpp:165-166
// `SetMatrix::draw(c, original) { c->setMatrix(SkM44(original) * matrix); }`,
// where :943-946 `DisplayListData::draw(SkCanvas* canvas) { ...
// this->map(draw_fns, canvas, canvas->getTotalMatrix()); }` captures `original`
// ONCE, before the first op). Skia's own SkPicture playback is the same:
// SkRecordDraw.h:52 captures fInitialCTM in the Draw constructor and
// SkRecordDraw.cpp:98 replays `setMatrix(fInitialCTM.asM33() * r.matrix)`.
//
// Bitmaps are held BY REFERENCE, so a later pixel write shows at the next
// replay, AND the display list keeps the pixels alive past a recycle(): hwui
// records `bitmap.makeImage()`, which (hwui/Bitmap.cpp:400-413) wraps
// `sk_ref_sp(this)` with kNever_SkCopyPixelsMode, while Bitmap.recycle only
// drops the Java wrapper's ref (jni/Bitmap.cpp:55-63 BitmapWrapper::freePixels
// `mBitmap.reset()`). See pixelRef() below and Bitmap.macapkPixelRef().
package android.graphics;

import java.util.Arrays;

final class MacApkDisplayList {

    // ---- Gates ------------------------------------------------------------
    /** Stage A, default ON. MACAPK_RENDERNODE_OPLIST=0 restores the
     *  bitmap-per-node RenderNode (beginRecording allocates a node-sized ARGB
     *  bitmap, drawRenderNode composites it) exactly, including the
     *  stale-ancestor re-record mechanisms that path needs. */
    static final boolean OPLIST = !"0".equals(System.getenv("MACAPK_RENDERNODE_OPLIST"));
    /** Picture on the same op list, default ON. MACAPK_PICTURE_OPLIST=0 restores
     *  the rasterize-into-a-bitmap-then-blit Picture exactly. Separate from
     *  OPLIST so a Picture regression can be bisected on its own. */
    static final boolean PICTURE_OPLIST = !"0".equals(System.getenv("MACAPK_PICTURE_OPLIST"));

    // ---- Test hooks: POSITIVE CONTROLS for debug/displaylist_gate.sh ONLY ----
    // Each one re-creates a specific way a recorder can be wrong, so the gate
    // can prove its rows are able to see that failure. Never set outside the
    // gate. All static final: an unset hook costs nothing.
    /** C1: silently drop every op recorded by the named Canvas method. */
    static final String TEST_DROP_OP = System.getenv("MACAPK_DL_TEST_DROP_OP");
    /** C2 and friends: comma list of snapshot kinds to take BY REFERENCE
     *  instead (paint, path, shader, array). */
    private static final String TEST_BYREF = System.getenv("MACAPK_DL_TEST_BYREF");
    static final boolean TEST_PAINT_BYREF  = testByref("paint");
    static final boolean TEST_PATH_BYREF   = testByref("path");
    static final boolean TEST_SHADER_BYREF = testByref("shader");
    static final boolean TEST_ARRAY_BYREF  = testByref("array");
    /** The pre-fix bitmap reference: no pixel ref, so a recycle() before the
     *  replay loses the pixels. */
    static final boolean TEST_BITMAP_BYREF = testByref("bitmap");
    /** The pre-fix Paint snapshot: a mutable ColorFilter stays shared. */
    static final boolean TEST_COLORFILTER_BYREF = testByref("colorfilter");
    /** C3: the pre-fix LAZY setMatrix base (the target matrix when the first
     *  setMatrix op runs, i.e. after the list's own earlier transforms). */
    static final boolean TEST_LAZY_BASE = "1".equals(System.getenv("MACAPK_DL_TEST_LAZY_BASE"));
    /** C17-C19, the hole-punch pre-pass (see replayPunches): "off" = no
     *  pre-pass (the first Stage A version: a hole under a layer never reaches
     *  the frame); "greedy" = the naive model, every hole reaches the frame at
     *  the alpha of every ModulateAlpha above it (recurse into nested layers
     *  and through app layers, multiply under a TransformCanvas: three things
     *  hwui does not do); "nofactor" = ignore a ModulateAlpha node's alpha on
     *  its holes. */
    private static final String TEST_PREPASS = System.getenv("MACAPK_DL_TEST_PREPASS");
    static final boolean TEST_PREPASS_OFF = "off".equals(TEST_PREPASS);
    static final boolean TEST_PREPASS_GREEDY = "greedy".equals(TEST_PREPASS);
    static final boolean TEST_PREPASS_NOFACTOR = "nofactor".equals(TEST_PREPASS);
    private static boolean testByref(String k) {
        if (TEST_BYREF == null) return false;
        for (String s : TEST_BYREF.split(",")) if (s.trim().equals(k)) return true;
        return false;
    }

    // ---- The list ---------------------------------------------------------
    /** One recorded call. `x` carries the replay's base save count. */
    interface Op { void run(Canvas c, Replay x); }
    /** The call a Paint-taking op makes, given the (snapshot) Paint. */
    interface PDraw { void draw(Canvas c, Paint p); }

    /** Per-replay context. */
    static final class Replay {
        final Canvas target;
        final int base;          // target.getSaveCount() when this list started
        /** The target's matrix when this list STARTED, captured before the first
         *  op (hwui's `original`). Null for a list with no setMatrix, which is
         *  nearly every list, so they pay nothing. */
        private Matrix initial;
        private Matrix scratch;
        Replay(Canvas target, int base, Matrix initial) {
            this.target = target; this.base = base; this.initial = initial;
        }
        /** The recording-space save count `n` (1 = the list's base) on the target. */
        int rebase(int n) { return base + n - 1; }
        /** Only the C3 control reaches the lazy read: without it, replay()
         *  captured `initial` already for every list that holds a setMatrix. */
        Matrix initialMatrix() {
            if (initial == null) initial = target.getMatrix();
            return initial;
        }
        /** One Matrix per replay for PaintSnap's shader check, not one per op. */
        Matrix scratch() {
            if (scratch == null) scratch = new Matrix();
            return scratch;
        }
    }

    /** What an op is, for the hole-punch pre-pass (replayPunches), which must
     *  run the state ops, skip the pixel ops, and treat the other three apart. */
    static final byte K_DRAW = 0;    // writes pixels: skipped by the pre-pass
    static final byte K_STATE = 1;   // save / restore / transform / clip (and a saveLayer our Canvas makes a plain save)
    static final byte K_LAYER = 2;   // a saveLayer our Canvas makes a REAL offscreen
    static final byte K_PUNCH = 3;   // punchHole (a PunchOp)
    static final byte K_NODE = 4;    // drawRenderNode (a NodeOp)

    /** The drawRenderNode op: the child BY REFERENCE, as hwui's
     *  RenderNodeDrawable holds it. A class of its own so the pre-pass can
     *  reach the child. */
    static final class NodeOp implements Op {
        final RenderNode node;
        NodeOp(RenderNode node) { this.node = node; }
        @Override public void run(Canvas c, Replay x) { c.drawRenderNode(node); }
    }
    /** The punchHole op. hwui's SkiaRecordingCanvas::punchHole records TWO
     *  things (android14 libs/hwui/pipeline/skia/SkiaRecordingCanvas.cpp:73-92):
     *  a HOLE_PUNCH_ANNOTATION carrying rect, radii and alpha, and a kDstOut
     *  rrect that clears the rect "within the layer itself". run() is the
     *  second (our Canvas.punchHole is that DST_OUT); punch() is the first, as
     *  TransformCanvas::onDrawAnnotation turns it into a DST_OUT on the
     *  canvas UNDER a layer. */
    static final class PunchOp implements Op {
        final float l, t, r, b, rx, ry, alpha;
        PunchOp(float l, float t, float r, float b, float rx, float ry, float alpha) {
            this.l = l; this.t = t; this.r = r; this.b = b; this.rx = rx; this.ry = ry; this.alpha = alpha;
        }
        @Override public void run(Canvas c, Replay x) { c.punchHole(l, t, r, b, rx, ry, alpha); }
        void punch(Canvas c, float factor) { c.punchHole(l, t, r, b, rx, ry, alpha * factor); }
    }

    private Op[] ops = new Op[16];
    private byte[] kinds = new byte[16];
    private int size;
    /** The child nodes this list draws (hwui SkiaDisplayList::mChildNodes), so
     *  hasHolePunches can look below without walking every op. */
    private RenderNode[] children;
    private int nChildren;
    /** This list itself holds a punchHole (hwui SkiaDisplayList::mHasHolePunches,
     *  set by SkiaRecordingCanvas::punchHole). */
    private boolean hasPunch;
    /** Pixel-writing ops recorded (the RenderNode stats' "leaf draws"). */
    int drawOps;
    /** Set when a recording saw a pixel op that no override recorded. */
    boolean unrecorded;
    /** A recording that must not accept more ops (finished, or the sentinel). */
    private boolean sealed;
    /** The list holds a setMatrix op, so replay() must capture the start matrix. */
    private boolean hasSetMatrix;

    /** A list that swallows every add: the canvas of a finished recording. */
    static final MacApkDisplayList SEALED = new MacApkDisplayList();
    static { SEALED.sealed = true; }

    int size() { return size; }
    void seal() { sealed = true; }
    void noteSetMatrix() { if (!sealed) hasSetMatrix = true; }

    void add(String method, Op op, boolean pixel) { add(method, op, pixel ? K_DRAW : K_STATE); }
    void add(String method, Op op, byte kind) {
        if (sealed) return;
        if (TEST_DROP_OP != null && TEST_DROP_OP.equals(method)) return;
        if (size == ops.length) {
            ops = Arrays.copyOf(ops, size * 2);
            kinds = Arrays.copyOf(kinds, size * 2);
        }
        kinds[size] = kind;
        ops[size++] = op;
        if (kind == K_DRAW || kind == K_PUNCH || kind == K_NODE) drawOps++;
        if (kind == K_PUNCH) hasPunch = true;
        if (kind == K_NODE) {
            if (children == null) children = new RenderNode[4];
            else if (nChildren == children.length) children = Arrays.copyOf(children, nChildren * 2);
            children[nChildren++] = ((NodeOp) op).node;
        }
    }

    // ---- Replay -----------------------------------------------------------
    /** Nesting guard: a node drawn into its own recording (directly or through a
     *  cycle) would otherwise recurse until the stack overflows. hwui has no
     *  cycle either way; this only turns an app bug into a cut-off instead of a
     *  crash. Per thread, because a Picture can be played off the UI thread. */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    static final int MAX_DEPTH = 64;
    private static volatile boolean sDepthLogged;

    /** Play every op onto `c`, then pop whatever the recording left saved. The
     *  CALLER owns any save around this (drawRenderNode's node setup, Picture's
     *  SkAutoCanvasRestore). Returns false when the nesting guard refused. */
    boolean replay(Canvas c) {
        final int[] d = DEPTH.get();
        if (d[0] >= MAX_DEPTH) {
            if (!sDepthLogged) {
                sDepthLogged = true;
                try {
                    com.macapk.loader.NativeLog.i("Mac.apk.RenderNode",
                        "display-list replay nested " + MAX_DEPTH + " deep: a node is drawn into "
                        + "its own recording; the cycle is cut here");
                } catch (Throwable ignored) { }
            }
            return false;
        }
        d[0]++;
        final int base = c.getSaveCount();
        try {
            final Replay x = new Replay(c, base,
                    (hasSetMatrix && !TEST_LAZY_BASE) ? c.getMatrix() : null);
            final Op[] o = ops;
            final int n = size;
            for (int i = 0; i < n; i++) o[i].run(c, x);
        } finally {
            c.restoreToCount(base);
            d[0]--;
        }
        return true;
    }

    // ---- Hole punches under a layer (hwui's TransformCanvas pass) ----------
    //
    // A punchHole replayed INSIDE a layer (a promoted alpha node, a compositing
    // layer, a blur) clears only that layer; composited back SrcOver, the hole
    // never reaches the frame. hwui fixes that with a pre-pass: before it
    // composites a RenderLayer it re-plays the layer's list onto the PARENT
    // canvas through a TransformCanvas that drops every draw but keeps the
    // clip/matrix ops and turns each hole annotation into a kDstOut rrect
    // (android14 libs/hwui/pipeline/skia/RenderNodeDrawable.cpp:295-299):
    //     if (renderNode->hasHolePunches()) {
    //         canvas->save();
    //         TransformCanvas transformCanvas(canvas, SkBlendMode::kDstOut);
    //         displayList->draw(&transformCanvas);
    //         canvas->restore();
    //     }
    // TransformCanvas.cpp:29-42 onDrawAnnotation draws the rrect at the
    // annotation's OWN alpha; :59 onFilter returns false, so every paint op is
    // dropped. It is an SkPaintFilterCanvas, which does NOT filter saveLayer
    // (external/skia src/utils/SkNWayCanvas.cpp:107-116 getSaveLayerStrategy
    // forwards it to the wrapped canvas as a real layer), so a hole inside an app saveLayer lands in that layer
    // and is lost. A nested RenderLayer child runs its own TransformCanvas
    // whose rrect goes to the OUTER TransformCanvas and is filtered away, so
    // it is lost too. hasHolePunches is the node's own list OR any child's
    // (RenderNode.cpp:265-271).
    //
    // A ModulateAlpha node (alpha < 1, hasOverlappingRendering false) is not a
    // layer on hwui: its list draws through an AlphaFilterCanvas that
    // multiplies the alpha into every paint (RenderNodeDrawable.cpp:195-213,
    // used at :352-356), the hole's kDstOut rrect
    // included. Our replay still gives it a saveLayerAlpha, so the same
    // pre-pass stands in, with the alpha as a factor; there a nested
    // RenderLayer's own TransformCanvas rrect DOES reach (through the alpha
    // filter, so times the factor), and nested alpha multiplies.
    //
    // `tc` is the mode: true under a TransformCanvas (alpha ignored, nested
    // layers dropped), false under a ModulateAlpha filter chain.

    /** Does this list, or any list below it, hold a punchHole? Bounded: a
     *  shared or cyclic tree cannot make it run away, and when the budget is
     *  spent it answers TRUE, the answer that only costs a pre-pass. */
    boolean hasHolePunches() {
        if (TEST_PREPASS_OFF) return false;
        return hasHolePunches(new int[] { HOLES_BUDGET }, 0);
    }
    private static final int HOLES_BUDGET = 4096;
    private boolean hasHolePunches(int[] budget, int depth) {
        if (hasPunch) return true;
        if (depth >= MAX_DEPTH || --budget[0] < 0) return budget[0] < 0;
        final RenderNode[] ch = children;
        for (int i = 0, n = nChildren; i < n; i++) {
            final MacApkDisplayList d = ch[i].macapkDisplayList();
            if (d != null ? d.hasHolePunches(budget, depth + 1) : ch[i].macapkPunches() != null) return true;
        }
        return false;
    }

    /** The pre-pass: this list's holes, and those its children deliver, onto
     *  `c` at `factor`. The CALLER has applied the node's translate, transform
     *  and clip, as setViewProperties has before drawContent runs this. */
    void replayPunches(Canvas c, float factor, boolean tc) {
        replayPunches(c, factor, tc, new int[] { HOLES_BUDGET }, 0);
    }
    private void replayPunches(Canvas c, float factor, boolean tc, int[] budget, int depth) {
        if (depth >= MAX_DEPTH || --budget[0] < 0) return;
        final int base = c.getSaveCount();
        // A real app layer is open while the save count is above layerFloor.
        int layerFloor = Integer.MAX_VALUE;
        try {
            final Replay x = new Replay(c, base,
                    (hasSetMatrix && !TEST_LAZY_BASE) ? c.getMatrix() : null);
            final Op[] o = ops;
            final byte[] k = kinds;
            for (int i = 0, n = size; i < n; i++) {
                switch (k[i]) {
                    case K_STATE:
                        o[i].run(c, x);
                        break;
                    case K_LAYER:
                        // The layer's content is dropped with it, so only its
                        // save matters: the rebased restores stay balanced.
                        if (TEST_PREPASS_GREEDY) { c.save(); break; }
                        if (c.getSaveCount() < layerFloor) layerFloor = c.getSaveCount();
                        c.save();
                        break;
                    case K_PUNCH:
                        if (c.getSaveCount() <= layerFloor) ((PunchOp) o[i]).punch(c, factor);
                        break;
                    case K_NODE:
                        if (c.getSaveCount() <= layerFloor) prePassNode(c, ((NodeOp) o[i]).node, factor, tc, budget, depth + 1);
                        break;
                    default:
                        break;   // K_DRAW: TransformCanvas::onFilter drops it
                }
                if (layerFloor != Integer.MAX_VALUE && c.getSaveCount() <= layerFloor) layerFloor = Integer.MAX_VALUE;
            }
        } finally {
            c.restoreToCount(base);
        }
    }

    /** One child of the pre-pass, set up the way hwui's RenderNodeDrawable
     *  sets it up on the TransformCanvas (forceDraw, setViewProperties,
     *  drawContent), so exactly the holes that reach hwui's frame reach ours. */
    private static void prePassNode(Canvas c, RenderNode n, float factor, boolean tc,
                                    int[] budget, int depth) {
        if (n.macapkNothingToDraw()) return;     // forceDraw: nothingToDraw && mComposeLayer
        final MacApkDisplayList d = n.macapkDisplayList();
        final java.util.ArrayList<RenderNode.Punch> bm = (d == null) ? n.macapkPunches() : null;
        // Only a subtree that holds a hole is worth walking (its own budget, so
        // a big hole-free sibling cannot starve the walk below).
        if (d == null ? bm == null : !d.hasHolePunches()) return;
        final int kind = n.macapkLayerKind();
        if (!TEST_PREPASS_GREEDY) {
            // A saveLayerAlpha that does not fit a layer is a real layer on the
            // wrapped canvas; a nested RenderLayer's rrect is filtered by ours.
            if (kind == RenderNode.KIND_SWALLOW) return;
            if (kind == RenderNode.KIND_LAYER && tc) return;
        }
        final int sc = c.save();
        try {
            c.translate(n.getLeft(), n.getTop());
            n.macapkApplyTransform(c);
            if (n.getClipToBounds() && c.macapkQuickRejectChild(0, 0, n.getWidth(), n.getHeight())) return;
            float f = factor;
            boolean t = tc;
            if (kind == RenderNode.KIND_LAYER) {
                n.macapkApplyLayerClip(c);
                t = true;   // its own TransformCanvas, on the (alpha-filtered) canvas above
            } else {
                n.macapkApplyClip(c);
                if (kind == RenderNode.KIND_MODULATE && (!tc || TEST_PREPASS_GREEDY) && !TEST_PREPASS_NOFACTOR) {
                    f = factor * n.macapkAlpha();
                }
            }
            if (d != null) {
                d.replayPunches(c, f, t, budget, depth);
            } else {
                // A BITMAP-mode child (the fallback): the holes it recorded in
                // its own device space, the space just set up.
                for (int i = 0; i < bm.size(); i++) c.macapkPunchShape(bm.get(i).shape, bm.get(i).alpha * f);
            }
        } finally {
            c.restoreToCount(sc);
        }
    }

    // ---- The recording canvas's state-only backing ------------------------
    /** A Bitmap that reports w x h but owns ONE ROW of pixels: its AWT image is a
     *  TYPE_INT_ARGB raster with scanline stride 0, so every row aliases row 0.
     *
     *  Why a backing at all: the recording must answer every Canvas query
     *  (getClipBounds, quickReject, getMatrix, getSaveCount, getWidth,
     *  macapkQuickRejectChild) exactly as today's bitmap-backed recording canvas
     *  of the same size does. Those answers come from Canvas's own Graphics2D
     *  transform/clip state plus the image size, so the cheapest EXACT way is to
     *  run Canvas's own state code on a real Graphics2D whose device is the right
     *  size -- and a Graphics2D's transform and user clip do not depend on how
     *  many pixels the image holds. Nothing is ever rasterized into it: every
     *  pixel method is overridden to record instead (the gate's API-shape row
     *  proves there is no gap, and RecordingCanvas's tripwire logs one if there
     *  ever is). 13 KB at 3360 px wide, instead of 24 MB. */
    static Bitmap stateBitmap(int w, int h) {
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final java.awt.image.DataBufferInt db = new java.awt.image.DataBufferInt(w);
        final java.awt.image.WritableRaster r = java.awt.image.Raster.createPackedRaster(
                db, w, h, 0 /* scanline stride: every row aliases row 0 */,
                new int[] { 0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000 }, null);
        b.macapkSetAwtImage(new java.awt.image.BufferedImage(
                java.awt.image.ColorModel.getRGBdefault(), r, false, null));
        return b;
    }

    // ---- Snapshots --------------------------------------------------------
    /** A Paint as it was at record time. A TextPaint keeps its extra fields
     *  (TextPaint.set copies them); an app subclass is read through its Paint
     *  fields, which is what a device's native SkPaint snapshot sees too. */
    static Paint snap(Paint p) {
        if (p == null || TEST_PAINT_BYREF) return p;
        final Paint c = copy(p);
        if (!TEST_COLORFILTER_BYREF) {
            final ColorFilter f = snap(c.getColorFilter());
            if (f != c.getColorFilter()) c.setColorFilter(f);
        }
        return c;
    }
    private static Paint copy(Paint p) {
        if (p instanceof android.text.TextPaint) {
            final android.text.TextPaint t = new android.text.TextPaint();
            t.set((android.text.TextPaint) p);
            return t;
        }
        return new Paint(p);
    }
    /** The two ColorFilters an app can change after the draw. Paint(Paint)
     *  shares the filter object, and ColorMatrixColorFilter.setColorMatrix /
     *  setColorMatrixArray and LightingColorFilter.setColorMultiply /
     *  setColorAdd rewrite it in place. On a device they do not reach a
     *  recording: each setter calls discardNativeInstance() (android14
     *  ColorMatrixColorFilter.java:83-84, :112-114; LightingColorFilter.java
     *  :78-81, :104-107), so the SkColorFilter the display list captured at record
     *  time is untouched. Every other filter of ours is immutable. Exact class
     *  only: an app subclass keeps its identity (and its own behaviour). Canvas's
     *  filtered-bitmap cache keys filters by PARAMETERS, so a copy costs no cache
     *  miss. */
    static ColorFilter snap(ColorFilter f) {
        if (f == null) return null;
        if (f.getClass() == ColorMatrixColorFilter.class) {
            return new ColorMatrixColorFilter(((ColorMatrixColorFilter) f).macapkArray());
        }
        if (f.getClass() == LightingColorFilter.class) {
            final LightingColorFilter l = (LightingColorFilter) f;
            return new LightingColorFilter(l.getColorMultiply(), l.getColorAdd());
        }
        return f;
    }
    /** The pixel storage a recorded bitmap draw keeps for a recycle(): see
     *  Bitmap.macapkPixelRef. Null when there is nothing to keep: a null or
     *  already recycled bitmap draws as the direct call on it would. */
    static Bitmap pixelRef(Bitmap b) {
        return (b == null || TEST_BITMAP_BYREF) ? null : b.macapkPixelRef();
    }
    /** The bitmap a replay draws: the live one, so a pixel write after the
     *  recording shows (the by-reference half), unless the app recycled it, in
     *  which case the storage it had at that moment (the kept-alive half). */
    static Bitmap live(Bitmap b, Bitmap pixelRef) {
        return (pixelRef != null && b.isRecycled()) ? pixelRef : b;
    }
    /** Path(Path) does not carry the fill type (it appends the geometry into a
     *  fresh WIND_NON_ZERO Path2D), so an EVEN_ODD path would replay WINDING;
     *  the fill type is copied explicitly. */
    static Path snap(Path p) {
        if (p == null || TEST_PATH_BYREF) return p;
        final Path c = new Path(p);
        c.setFillType(p.getFillType());
        return c;
    }
    static RectF snap(RectF r) { return r == null ? null : new RectF(r); }
    static Rect snap(Rect r) { return r == null ? null : new Rect(r); }
    static Matrix snap(Matrix m) { return m == null ? null : new Matrix(m); }
    static Region snap(Region r) { return r == null ? null : new Region(r); }
    static float[] snap(float[] a) { return (a == null || TEST_ARRAY_BYREF) ? a : a.clone(); }
    static int[] snap(int[] a) { return (a == null || TEST_ARRAY_BYREF) ? a : a.clone(); }
    static short[] snap(short[] a) { return (a == null || TEST_ARRAY_BYREF) ? a : a.clone(); }
    static char[] snap(char[] a) { return (a == null || TEST_ARRAY_BYREF) ? a : a.clone(); }
    /** a[from, to) as a private copy: the elements the call reads, as hwui
     *  copies only them. RecordingCanvas validates the range first with AOSP's
     *  own check, so this never pads or throws. Under the C2 array control the
     *  array itself comes back, so a caller that rebases its offset must test
     *  `copy == a`. */
    static char[] range(char[] a, int from, int to) { return TEST_ARRAY_BYREF ? a : Arrays.copyOfRange(a, from, to); }
    static float[] range(float[] a, int from, int to) { return TEST_ARRAY_BYREF ? a : Arrays.copyOfRange(a, from, to); }
    static int[] range(int[] a, int from, int to) { return TEST_ARRAY_BYREF ? a : Arrays.copyOfRange(a, from, to); }
    /** The chars (and spans) of text[start, end) as an immutable value. A String
     *  is already immutable and is kept whole (the caller keeps its indices); any
     *  other CharSequence is copied over the RANGE only, so a TextView drawing
     *  line by line never copies the whole text per line. Span objects stay
     *  shared (SpannedString copies the span table, not the spans) -- the same
     *  residual the design note records (8.3). */
    static CharSequence snapRange(CharSequence t, int start, int end) {
        if (t == null || t instanceof String) return t;
        final CharSequence sub = t.subSequence(start, end);
        if (sub instanceof android.text.Spanned) return new android.text.SpannedString(sub);
        return sub.toString();
    }

    /** A Paint snapshot plus its Shader's local matrix at record time.
     *
     *  Paint(Paint) shares the Shader object, and our Shader's local matrix is a
     *  mutable field, so a setLocalMatrix after the draw would reach the replay.
     *  On a device it does not: the display list holds the native SkShader built
     *  at record time, and a later setLocalMatrix makes the Java Shader discard
     *  and rebuild ITS native instance, leaving the recorded one untouched. So
     *  the matrix is captured here and, only in the rare case the live one has
     *  moved, put back around the replayed call.
     *
     *  A BitmapShader's bitmap is kept like any recorded bitmap (see pixelRef):
     *  if the app recycles it, the replay draws through a private BitmapShader
     *  on the kept storage, built once. */
    static final class PaintSnap {
        final Paint paint;
        private final Shader shader;
        private final Matrix local;     // null = the shader had no local matrix
        private final BitmapShader bitmapShader;  // the shader, when it is a BitmapShader
        private final Bitmap shaderPixels;        // ... and its bitmap's pixel ref
        private Paint recycledPaint;        // built on first need; see run()
        PaintSnap(Paint p) {
            paint = snap(p);
            Shader s = (paint != null) ? paint.getShader() : null;
            if (s instanceof RuntimeShader && !TEST_SHADER_BYREF) {
                s = ((RuntimeShader) s).macapkSnapshot();
                paint.setShader(s);
            }
            if (s != null && !TEST_SHADER_BYREF) {
                Matrix m = new Matrix();
                shader = s;
                local = s.getLocalMatrix(m) ? m : null;
            } else {
                shader = null;
                local = null;
            }
            bitmapShader = (s instanceof BitmapShader) ? (BitmapShader) s : null;
            shaderPixels = (bitmapShader != null) ? pixelRef(bitmapShader.bitmap) : null;
        }
        /** Make the recorded call with the snapshot Paint, the shader's local
         *  matrix as it was at record time. No allocation on the common path
         *  (the comparison matrix is the replay's one scratch). */
        void run(Canvas c, Replay x, PDraw d) {
            if (shaderPixels != null && bitmapShader.bitmap.isRecycled()) {
                d.draw(c, recycledPaint());
                return;
            }
            if (shader == null) { d.draw(c, paint); return; }
            final Matrix cur = x.scratch();
            final boolean had = shader.getLocalMatrix(cur);
            final boolean same = (local == null) ? !had : (had && local.equals(cur));
            if (same) { d.draw(c, paint); return; }
            final Matrix back = had ? new Matrix(cur) : null;
            shader.setLocalMatrix(local);
            try { d.draw(c, paint); }
            finally { shader.setLocalMatrix(back); }
        }
        /** The snapshot Paint on a BitmapShader over the kept storage, with the
         *  recorded local matrix and the shader's sampling settings. Private to
         *  this op, so nothing has to be put back. */
        private Paint recycledPaint() {
            Paint q = recycledPaint;
            if (q == null) {
                final BitmapShader bs = bitmapShader;
                final BitmapShader own = new BitmapShader(shaderPixels, bs.tileX, bs.tileY);
                own.setFilterMode(bs.getFilterMode());
                own.setMaxAnisotropy(bs.getMaxAnisotropy());
                if (shader != null) {
                    own.setLocalMatrix(local);
                } else {   // the C2 shader control: whatever the live one holds
                    final Matrix lm = new Matrix();
                    own.setLocalMatrix(bs.getLocalMatrix(lm) ? lm : null);
                }
                q = copy(paint);
                q.setShader(own);
                recycledPaint = q;
            }
            return q;
        }
    }
}
