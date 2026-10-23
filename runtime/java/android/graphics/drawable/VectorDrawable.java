// android.graphics.drawable.VectorDrawable -- AOSP-shape vector
// drawable that parses the apktool-decoded XML at construction time
// and renders each <path> via Canvas.drawPath.
//
// Supports the SVG-style path command set: M m L l H h V v C c S s
// Q q T t A a Z z. Coordinates are in viewport space; the draw step
// scales viewport -> bounds.
//
// Limitations:
// - <group> translate/scale/rotate/pivot are fully handled (matching AOSP's
//   complete VGroup attribute set); nested groups compose correctly.
// - <path> trimPathStart/trimPathEnd/trimPathOffset are applied once at
//   parse time (see applyTrim) -- re-applying per frame is only needed once
//   AnimatedVectorDrawable actually animates them (a separate, still-open
//   gap: graphics-drawable-12).
// - <clip-path> is ignored.
// - Tinting (?colorControlNormal etc) falls back to fillColor.
// - Pattern fills are ignored. aapt2-hoisted inline <gradient> fillColor/
//   strokeColor is handled for FILL only (macapkResolveGradient) -- a
//   gradient-filled STROKE still falls back to fillColor's default (this
//   stale line said "gradients are ignored" outright for months after
//   gradient support landed 2026-08-02; v1.0.851 fixed the resolve-to-null
//   case, and this turn fixed fillAlpha never attenuating the resolved
//   shader -- see macapkResolveGradient's doc comment for the AOSP citation).
//
// v1.0.99 (continuation of v1.0.98 push toward F-Droid 100%).
package android.graphics.drawable;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// AOSP-conformance (framework_lint vs android-30 stub jar): AOSP does not
// mark this final. Final-when-AOSP-is-not is the dangerous direction -- an
// app compiled against real Android that subclasses this fails to resolve
// against our jar. See docs/aosp-conformance-audit_2026-08-23.md.
public class VectorDrawable extends Drawable {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_TRACE_VECTORDRAW = System.getenv("MACAPK_TRACE_VECTORDRAW");

    /** TRUE when this instance is the EMPTY STAND-IN `Resources.getDrawable`
     *  returns for a reference it could not resolve (id == 0), not a real vector
     *  the app authored.
     *
     *  It exists because "does this view have a background drawable" is NOT the
     *  same question as "will something actually paint". Our synthetic Material
     *  chrome is a stand-in for a drawable we cannot render, and it must defer to
     *  a REAL background (opencalculator's keys are a `<ripple>` over a rounded
     *  `<shape>`, which renders correctly and must not be painted over) while
     *  still standing in for THIS, which paints nothing at all. Distinguishing
     *  them by class alone is not enough: an app's own genuine VectorDrawable is
     *  the same class. */
    public boolean macapkUnresolvedStub;

    /** True when this instance will draw NOTHING, whatever bounds it is given.
     *  `draw()` opens with `if (canvas == null || paths.isEmpty()) return;`, so an
     *  empty path list is exactly that condition and this is not a guess about it.
     *
     *  It exists because "does this view have a background drawable" is a
     *  different question from "will anything paint", and our synthetic Material
     *  chrome has to answer the second. There are TWO ways to end up here and the
     *  `macapkUnresolvedStub` flag only covers the first: `Resources.getDrawable`
     *  hands back an empty instance for `id == 0`, AND it falls back to a
     *  VectorDrawable for any XML drawable it cannot otherwise turn into
     *  something, which then parses zero paths. Measured: AnkiDroid's own
     *  MaterialButton calls `super.setBackgroundDrawable` with a VectorDrawable
     *  whose `macapkUnresolvedStub` is FALSE and which still paints nothing --
     *  which is why gating on the flag alone regressed it. */
    public boolean macapkPaintsNothing() { return paths.isEmpty(); }

    private float viewportWidth = 24, viewportHeight = 24;
    private float intrinsicWidthDp = 24, intrinsicHeightDp = 24;
    private final List<PathSpec> paths = new ArrayList<>();
    private final Paint paint = new Paint();
    private int tint = 0;
    /** The <vector> declared android:tint (see effectiveFillColor). */
    private boolean macapkTintDeclared;
    private int alpha = 255;

    public VectorDrawable() {}

    public VectorDrawable(InputStream xmlStream) {
        if (xmlStream == null) return;
        Throwable err = null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            DocumentBuilder db = f.newDocumentBuilder();
            Document doc = db.parse(xmlStream);
            Element root = doc.getDocumentElement();
            if (root != null) parseVector(root);
        } catch (Throwable t) {
            err = t;
        }
        // v1.0.100: trace parse outcome ALWAYS (success + path count, or
        // the exception); previously the trace was inside the same try
        // block as parseVector, so any failure swallowed both the
        // exception AND the trace -- ic_categories/ic_updates/ic_settings
        // silently produced an empty VectorDrawable.
        try {
            if (err != null) {
                com.macapk.loader.NativeLog.i("VectorDrawable",
                    "parse FAILED: " + err);
            } else {
                com.macapk.loader.NativeLog.i("VectorDrawable",
                    "parsed " + paths.size() + " path(s), viewport="
                    + viewportWidth + "x" + viewportHeight
                    + ", intrinsic=" + intrinsicWidthDp + "x" + intrinsicHeightDp);
            }
        } catch (Throwable ignored) {}
    }

    public VectorDrawable(Resources res, int resId) {
        // macapkRes must be live BEFORE the parse: an aapt2 inline-gradient
        // fill is resolved during the DOM walk (macapkResolveGradient).
        this.macapkRes = res;
        macapkParseFrom(openSafe(res, resId));
    }
    /** Shared body of the stream constructors (kept separate from the
     *  `this(...)` chain so a field can be seeded before parsing). */
    private void macapkParseFrom(InputStream xmlStream) {
        if (xmlStream == null) return;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(xmlStream);
            Element root = doc.getDocumentElement();
            if (root != null) parseVector(root);
        } catch (Throwable t) {
            try { com.macapk.loader.NativeLog.i("VectorDrawable", "parse FAILED: " + t); }
            catch (Throwable ignored) {}
        }
    }

    /** lane layout, 2026-08-01: Theme-aware construction. A `?attr` path
     *  fill must resolve against the theme of the INFLATING context, not
     *  only the static application theme -- AOSP threads the Theme into
     *  VectorDrawable.inflate and resolves per-path colors through it
     *  (GradientColor/ColorStateList obtainForTheme). Measured victim:
     *  AntennaPod's bottom-nav icons are <vector> paths with
     *  android:fillColor="?action_icon_color", an attr its MANIFEST
     *  application theme (Theme.AntennaPod.Splash) never declares -- the
     *  app setTheme()s to Theme.AntennaPod.Dynamic.* at startup, which
     *  DOES declare it (@color/black|white). The static resolver walked
     *  the Splash chain, missed, and every path painted the transparent
     *  fallback: a fully invisible bottom bar (emulator A/B 2026-08-01).
     *  The field must be assigned BEFORE parsing (fills resolve during
     *  the DOM walk), which is why this ctor does not chain through the
     *  2-arg one. */
    public VectorDrawable(Resources res, int resId,
                          android.content.res.Resources.Theme theme) {
        this.macapkTheme = theme;
        this.macapkRes = res;
        InputStream in = openSafe(res, resId);
        if (in == null) return;
        Throwable err = null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            DocumentBuilder db = f.newDocumentBuilder();
            Document doc = db.parse(in);
            Element root = doc.getDocumentElement();
            if (root != null) parseVector(root);
        } catch (Throwable t) {
            err = t;
        }
        try {
            if (err != null) {
                com.macapk.loader.NativeLog.i("VectorDrawable",
                    "parse FAILED: " + err);
            }
        } catch (Throwable ignored) {}
    }
    private android.content.res.Resources.Theme macapkTheme;

    /** icon-lifecycle fan-out lane (2026-07-23, BUG B re-open): construct
     *  directly from an ALREADY-PARSED DOM element, for a <vector> that
     *  appears INLINE as a child of another drawable's XML (an
     *  <adaptive-icon>'s <background>/<foreground>, or a <layer-list>'s
     *  <item>) rather than as its own standalone resource file with an id.
     *  Real AOSP inflation (Drawable.createFromXmlInner) recurses into the
     *  SAME per-tag inflate logic regardless of nesting depth -- a <vector>
     *  is a <vector> whether it is the root of its own resource file or
     *  nested inline; this mirrors that for our DOM-based path by reusing
     *  parseVector() directly instead of re-serializing/re-parsing.
     *  Root cause this closes: Resources.macapkInflateDrawableElement had
     *  no case for "vector" (only layer-list/ripple/shape/selector/
     *  adaptive-icon), so an inline <vector> silently inflated to nothing
     *  (null) -- confirmed via Calculator's launcher icon, whose
     *  <adaptive-icon><background> is a BARE <vector> child element (no
     *  android:drawable ref at all), which composited to a fully
     *  transparent/no-op layer, leaving AdaptiveIconDrawable's black
     *  canvas foundation as the entire rendered icon. */
    public VectorDrawable(Element root) {
        if (root != null) {
            try { parseVector(root); }
            catch (Throwable ignored) {}
        }
    }
    /** Inline-<vector> form with the inflating Theme (see the 3-arg
     *  resource ctor above). Theme must be set before the parse. */
    public VectorDrawable(Element root, android.content.res.Resources.Theme theme) {
        this(root, theme, null);
    }
    /** lane layout A, 2026-08-02: the Resources-carrying form. The 2-arg
     *  Element ctor above left `macapkRes` NULL, and `macapkResolveGradient`
     *  opens with `if (... macapkRes == null) return null` -- so aapt2 inline
     *  `<gradient>` fills silently resolved to nothing for EVERY standalone
     *  vector, because `Resources.macapkInflateDrawableElement`'s `case
     *  "vector"` routes through the Element ctor and the 3-arg
     *  (Resources,int,Theme) ctor that DOES seed macapkRes became unreachable
     *  for standalone vector files. That is why AnkiDroid's logo star stayed
     *  white after the gradient work landed earlier tonight: `fillColor=
     *  "@drawable/$ankidroid_logo__0"` produced colour 0 (alpha 0), the path
     *  was skipped, and the plain white under-star showed through.
     *  See docs/canvas-ab-analyst-report-a_2026-08-02.md item 5a. */
    public VectorDrawable(Element root, android.content.res.Resources.Theme theme,
                          Resources res) {
        this.macapkTheme = theme;
        this.macapkRes = res;
        if (root != null) {
            try { parseVector(root); }
            catch (Throwable ignored) {}
        }
    }
    private static InputStream openSafe(Resources res, int resId) {
        if (res == null || resId == 0) return null;
        try { return res.openRawResource(resId); }
        catch (Throwable t) { return null; }
    }

    private static final String NS = "http://schemas.android.com/apk/res/android";

    private void parseVector(Element root) {
        // <vector> root attributes
        viewportWidth  = parseFloat(root.getAttributeNS(NS, "viewportWidth"),  viewportWidth);
        viewportHeight = parseFloat(root.getAttributeNS(NS, "viewportHeight"), viewportHeight);
        intrinsicWidthDp  = parseDpToPx(root.getAttributeNS(NS, "width"),  intrinsicWidthDp);
        intrinsicHeightDp = parseDpToPx(root.getAttributeNS(NS, "height"), intrinsicHeightDp);
        final String tintAttr = root.getAttributeNS(NS, "tint");
        macapkTintDeclared = tintAttr != null && !tintAttr.trim().isEmpty();
        tint = parseColor(root.getAttributeNS(NS, "tint"), 0);

        // Walk children: <path>, <group>
        walkChildren(root, new ArrayList<GroupXform>());
    }

    private void walkChildren(Element elem, List<GroupXform> chain) {
        NodeList kids = elem.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String tag = e.getTagName();
            if ("path".equals(tag)) {
                String pathData = e.getAttributeNS(NS, "pathData");
                if (pathData != null && pathData.startsWith("@")) pathData = macapkPathDataRef(pathData);
                int fillColor = parseColor(e.getAttributeNS(NS, "fillColor"), 0);
                int strokeColor = parseColor(e.getAttributeNS(NS, "strokeColor"), 0);
                float strokeWidth = parseFloat(e.getAttributeNS(NS, "strokeWidth"), 0f);
                // AOSP VFullPath.updateStateFromTypedArray: trimPathStart/End
                // default 0/1 (the whole path), trimPathOffset defaults 0 -- the
                // standard mechanism behind animated checkmarks/progress rings.
                float trimStart  = parseFloat(e.getAttributeNS(NS, "trimPathStart"),  0f);
                float trimEnd    = parseFloat(e.getAttributeNS(NS, "trimPathEnd"),    1f);
                float trimOffset = parseFloat(e.getAttributeNS(NS, "trimPathOffset"), 0f);
                // lane layout A, 2026-08-02: fillAlpha / strokeAlpha were never
                // parsed. AOSP multiplies them into the corresponding colour's
                // alpha (VectorDrawable.java:2084,
                // `fillAlpha = a.getFloat(R.styleable.VectorDrawablePath_fillAlpha, fillAlpha)`).
                // Measured: AntennaPod's hand-drawn arrow
                // (strokeAlpha="0.4" fillAlpha="0.4") painted OPAQUE black
                // against the emulator's 40% grey, and AnkiDroid's logo sheen /
                // shadow paths (fillAlpha 0.1-0.3) painted at full strength as
                // harsh white and black bars.
                float fillAlpha   = parseFloat(e.getAttributeNS(NS, "fillAlpha"),   1f);
                float strokeAlpha = parseFloat(e.getAttributeNS(NS, "strokeAlpha"), 1f);
                if (fillAlpha >= 0f && fillAlpha < 1f)
                    fillColor = macapkScaleAlpha(fillColor, fillAlpha);
                if (strokeAlpha >= 0f && strokeAlpha < 1f)
                    strokeColor = macapkScaleAlpha(strokeColor, strokeAlpha);
                if (pathData != null && !pathData.isEmpty()) {
                    Path p = parsePathData(pathData);
                    if (p != null) {
                        p = applyTrim(p, trimStart, trimEnd, trimOffset);
                        PathSpec spec = new PathSpec();
                        spec.path = p;
                        spec.fillColor = fillColor;
                        spec.strokeColor = strokeColor;
                        spec.strokeWidth = strokeWidth;
                        // aapt2 inline-gradient fill (see macapkResolveGradient).
                        // fillAlpha must reach the shader too -- see the comment
                        // on macapkResolveGradient's signature below for why.
                        spec.fillShader = macapkResolveGradient(
                            e.getAttributeNS(NS, "fillColor"), fillAlpha);
                        // chain is never mutated by this scope (every descent
                        // below appends to a fresh copy before recursing), so
                        // sharing the reference across sibling <path> leaves
                        // under the same <group> is safe.
                        spec.groupChain = chain.isEmpty() ? null : chain;
                        paths.add(spec);
                    }
                }
            } else if ("group".equals(tag)) {
                float gtx = parseFloat(e.getAttributeNS(NS, "translateX"), 0f);
                float gty = parseFloat(e.getAttributeNS(NS, "translateY"), 0f);
                float gsx = parseFloat(e.getAttributeNS(NS, "scaleX"),     1f);
                float gsy = parseFloat(e.getAttributeNS(NS, "scaleY"),     1f);
                // AOSP VGroup.updateStateFromTypedArray also parses rotation/
                // pivotX/pivotY as independent group attributes (composed
                // "scale, rotate, then translate" -- see GroupXform/draw()
                // below). A mirrored/diagonal icon variant authored via
                // <group rotation=...> instead of duplicating path data
                // rendered unrotated before this.
                float grot = parseFloat(e.getAttributeNS(NS, "rotation"), 0f);
                float gpx  = parseFloat(e.getAttributeNS(NS, "pivotX"),   0f);
                float gpy  = parseFloat(e.getAttributeNS(NS, "pivotY"),   0f);
                List<GroupXform> childChain = new ArrayList<GroupXform>(chain);
                if (gtx != 0f || gty != 0f || gsx != 1f || gsy != 1f || grot != 0f) {
                    childChain.add(new GroupXform(gtx, gty, gsx, gsy, grot, gpx, gpy));
                }
                walkChildren(e, childChain);
            }
            // <clip-path> ignored
        }
    }

    /** One <group>'s own local transform (translate/scale/rotate/pivot),
     *  captured in root-to-leaf order per PathSpec so draw() can replay the
     *  exact AOSP composition order (hwui VectorDrawable.cpp
     *  Group::getLocalMatrix): reset, postTranslate(-pivot),
     *  postScale(scale), postRotate(rotation), postTranslate(translate +
     *  pivot) -- i.e. scale and rotate happen around the pivot, then
     *  translate happens last. */
    private static final class GroupXform {
        final float tx, ty, sx, sy, rotation, pivotX, pivotY;
        GroupXform(float tx, float ty, float sx, float sy, float rotation,
                   float pivotX, float pivotY) {
            this.tx = tx; this.ty = ty; this.sx = sx; this.sy = sy;
            this.rotation = rotation; this.pivotX = pivotX; this.pivotY = pivotY;
        }
    }

    private static class PathSpec {
        Path path;
        int  fillColor;
        int  strokeColor;
        float strokeWidth;
        List<GroupXform> groupChain;
        /** Gradient fill, when `android:fillColor` referenced an aapt2 inline
         *  `<gradient>` instead of a colour (see macapkResolveGradient). */
        android.graphics.Shader fillShader;
    }

    /** The Resources this vector was inflated from, kept so a `fillColor`
     *  that references an aapt2 inline `<gradient>` file can be resolved. */
    private Resources macapkRes;

    /** aapt2 hoists an inline `<gradient>` fill into its own drawable file and
     *  rewrites the attribute as a reference, e.g.
     *      android:fillColor="@drawable/$ankidroid_logo__0"
     *  where that file's ROOT is `<gradient>`. We resolved such a value with
     *  parseColor, which returns the caller default for any `@` reference, so
     *  every gradient-filled path painted nothing (or the monochrome-white
     *  fallback). Measured victim: AnkiDroid's logo star rendered WHITE instead
     *  of the #29b6f6 -> #0288d1 blue gradient
     *  (docs/canvas-ab-analyst-report_2026-08-02.md item 7).
     *  Returns null when the reference is not a gradient, leaving the existing
     *  colour path untouched. Coordinates are VIEWPORT-space, which is the
     *  space paths are built in, so the shader lines up without extra mapping.
     *
     *  `fillAlpha` (AOSP <path android:fillAlpha>) must attenuate a gradient
     *  fill exactly as it attenuates a solid fillColor. AOSP's hwui applies
     *  this at the PAINT, not the colour: FullPath::draw (libs/hwui/
     *  VectorDrawable.cpp, android13-release, :163-166) does
     *      paint.setColor(applyAlpha(SK_ColorBLACK, fillAlpha));
     *      paint.setShader(fillGradient);
     *  where applyAlpha (:150-153) is `SkColorSetA(color, alphaBytes*alpha)` --
     *  i.e. the paint's own alpha still multiplies the shader's per-pixel
     *  output, same mechanism as the solid-color branch two lines below it.
     *  We have no "paint alpha over a shader" concept in the Canvas/Paint
     *  bridge (Paint.java, do-not-touch here), so we fold the same factor
     *  into the gradient's own stop colours at construction time instead --
     *  final per-pixel alpha is stopAlpha*fillAlpha either way, so the two
     *  mechanisms are visually identical.
     *
     *  Confirmed missing on AnkiDroid's own onboarding logo (docs/
     *  hard-fixes-catalog.md's "gradient fills ... UNVERIFIED" note, and the
     *  KNOWN OPEN item in docs/canvas-ab-analyst-report_2026-08-02.md #7-10):
     *  ankidroid_logo.xml's two full-card-outline "sheen" paths
     *  ($ankidroid_logo__1 / __2, both android:fillAlpha="0.1", drawn AFTER
     *  the star) painted at full ~100% strength instead of 10%, washing the
     *  solid #434748 card into a pale gradient and overpainting the half of
     *  the star that geometrically falls under the card's own outline path
     *  -- which is why the star looked "smaller/clipped" rather than just
     *  discoloured: it is genuinely being painted over, not mis-scaled.
     *  fillAlpha co-occurs with a gradient fillColor on other apps' own
     *  vector art too (measured: Thunderbird's ic_app_logo.xml, two paths at
     *  fillAlpha 0.9/0.7), so this is a generic VectorDrawable gap, not an
     *  AnkiDroid-specific one. */
    private android.graphics.Shader macapkResolveGradient(String ref, float fillAlpha) {
        if (ref == null || !ref.startsWith("@") || macapkRes == null) return null;
        try {
            String body = ref.substring(1);
            int slash = body.indexOf('/');
            if (slash <= 0) return null;
            String type = body.substring(0, slash), name = body.substring(slash + 1);
            InputStream in = null;
            int id = macapkRes.getIdentifier(name, type, null);
            if (id != 0) in = openSafe(macapkRes, id);
            if (in == null) {
                // aapt2's hoisted-gradient files are named `$parent__N` and are
                // NOT registered in public.xml (they are private, generated
                // entries), so getIdentifier legitimately returns 0 for them.
                // Fall back to a direct file lookup in the decoded res tree --
                // the same place the parent drawable itself came from.
                in = macapkOpenResFile(type, name);
                if (in == null) return null;
            }
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(in);
            Element g = doc.getDocumentElement();
            if (g == null || !"gradient".equals(g.getTagName())) return null;
            // <item android:color android:offset> rows, in document order.
            NodeList items = g.getElementsByTagName("item");
            java.util.ArrayList<Integer> cols = new java.util.ArrayList<>();
            java.util.ArrayList<Float>   offs = new java.util.ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                Element it = (Element) items.item(i);
                Integer c = parseHexOnly(it.getAttributeNS(NS, "color"));
                if (c == null) continue;
                cols.add(c);
                offs.add(parseFloat(it.getAttributeNS(NS, "offset"), i));
            }
            if (cols.size() < 2) {
                // Fall back to the start/center/end triple form.
                Integer s = parseHexOnly(g.getAttributeNS(NS, "startColor"));
                Integer e = parseHexOnly(g.getAttributeNS(NS, "endColor"));
                Integer c = parseHexOnly(g.getAttributeNS(NS, "centerColor"));
                cols.clear(); offs.clear();
                if (s != null) { cols.add(s); offs.add(0f); }
                if (c != null) { cols.add(c); offs.add(0.5f); }
                if (e != null) { cols.add(e); offs.add(1f); }
                if (cols.size() < 2) return null;
            }
            int[] colors = new int[cols.size()];
            float[] stops = new float[cols.size()];
            for (int i = 0; i < cols.size(); i++) { colors[i] = cols.get(i); stops[i] = offs.get(i); }
            // The path's fillAlpha, folded into every stop's own alpha (see the
            // AOSP citation on this method's doc comment above). Mirrors the
            // solid-fillColor scaling at the "path".equals(tag) call site
            // exactly (same macapkScaleAlpha helper, same >=0/<1 guard).
            if (fillAlpha >= 0f && fillAlpha < 1f)
                for (int i = 0; i < colors.length; i++) colors[i] = macapkScaleAlpha(colors[i], fillAlpha);
            // AWT's LinearGradientPaint requires strictly increasing stops.
            for (int i = 1; i < stops.length; i++)
                if (stops[i] <= stops[i - 1]) stops[i] = Math.min(1f, stops[i - 1] + 1e-4f);
            String gtype = g.getAttributeNS(NS, "type");
            if ("radial".equals(gtype)) {
                float cx = parseFloat(g.getAttributeNS(NS, "centerX"), 0f);
                float cy = parseFloat(g.getAttributeNS(NS, "centerY"), 0f);
                float r  = parseFloat(g.getAttributeNS(NS, "gradientRadius"), 0f);
                if (r <= 0f) return null;
                return new android.graphics.RadialGradient(cx, cy, r, colors, stops,
                    android.graphics.Shader.TileMode.CLAMP);
            }
            if ("sweep".equals(gtype)) return null;   // no AWT equivalent; leave the colour path
            float x0 = parseFloat(g.getAttributeNS(NS, "startX"), 0f);
            float y0 = parseFloat(g.getAttributeNS(NS, "startY"), 0f);
            float x1 = parseFloat(g.getAttributeNS(NS, "endX"),   0f);
            float y1 = parseFloat(g.getAttributeNS(NS, "endY"),   0f);
            if (x0 == x1 && y0 == y1) return null;
            return new android.graphics.LinearGradient(x0, y0, x1, y1, colors, stops,
                android.graphics.Shader.TileMode.CLAMP);
        } catch (Throwable t) { return null; }
    }
    /** Open the app's `<type>/<name>` file resource from the installed apk
     *  set's rows (ResourceIndex.openRawByName, v1.0.1590). Used for the
     *  resources aapt2 generates privately (`$foo__0`, no public.xml entry,
     *  but a named table entry all the same); this used to list the decoded
     *  `_apkt/res/<type>-*` directories, which no longer exist. */
    private InputStream macapkOpenResFile(String type, String name) {
        try {
            Object ri = Resources.macapkGetResourceIndex();
            if (ri == null) return null;
            Object in = ri.getClass().getMethod("openRawByName", String.class, String.class)
                .invoke(ri, type, name);
            return in instanceof InputStream s ? s : null;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Multiply a colour's alpha channel by `f` (AOSP fillAlpha/strokeAlpha). */
    private static int macapkScaleAlpha(int color, float f) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** #hex only -- never a reference, so gradient stops can't recurse. */
    private static Integer parseHexOnly(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) != '#') return null;
        try { return android.graphics.Color.macapkParseResourceColor(s); }
        catch (Throwable t) { return null; }
    }

    /** Mirrors hwui's VectorDrawable.cpp applyTrim(): trims a path to the
     *  [trimPathStart, trimPathEnd] fraction of its arc length, offset by
     *  trimPathOffset (all fractions wrap via fmod 1.0), extracting the
     *  sub-segment through PathMeasure -- the same mechanism real Android
     *  uses via SkPathMeasure. When start ends up after end (the offset
     *  wrapped the window around the contour), AOSP stitches two segments:
     *  [start,len] then [0,end]. Java's `%` already matches C's fmod sign
     *  convention for our inputs (both follow the dividend's sign). */
    private static Path applyTrim(Path in, float trimStart, float trimEnd, float trimOffset) {
        if (trimStart == 0f && trimEnd == 1f) return in;
        Path out = new Path();
        if (trimStart == trimEnd) return out;
        PathMeasure measure = new PathMeasure(in, false);
        float len = measure.getLength();
        float start = len * ((trimStart + trimOffset) % 1.0f);
        float end   = len * ((trimEnd + trimOffset) % 1.0f);
        if (start > end) {
            measure.getSegment(start, len, out, true);
            if (end > 0) measure.getSegment(0, end, out, true);
        } else {
            measure.getSegment(start, end, out, true);
        }
        return out;
    }

    /** The colour a path's FILL will actually be painted with: the authored
     *  fillColor, tinted, then run through the monochrome-icon substitutions,
     *  then scaled by the drawable's alpha.
     *
     *  Extracted so the DRAW GUARD and the substitutions cannot drift apart.
     *  They had: the guard tested the raw `fillColor` while the substitutions
     *  rewrote a local, so the transparent-path substitution below computed a
     *  visible colour that the guard then discarded -- unreachable code, and
     *  silent, since a skipped path leaves no trace. One function, one answer.
     *
     *  The tint is a real TINT (lane layout, 2026-08-01). AOSP builds a
     *  PorterDuffColorFilter(tint, SRC_IN) and hands it to the paint
     *  (VectorDrawable.draw -> updateTintFilter); SRC_IN with a solid colour
     *  means every painted pixel takes the TINT's RGB while keeping the
     *  source's alpha. Ours previously used `tint` only as a FLAG that
     *  suppressed the grey substitution, and drew the path's own colour -- so
     *  `<vector android:tint="?colorControlNormal">` over
     *  `<path android:fillColor="#ffffff">` (how every AppCompat/Material icon
     *  set is authored) painted literal WHITE. Measured victim: F-Droid's
     *  bottom-nav Categories/Nearby/Updates/Settings icons were white on a
     *  white bar = invisible, while the emulator drew them dark grey
     *  (emulator A/B, docs/canvas-layout-fidelity_2026-08-01.md). It got
     *  WORSE, not better, when Theme threading made `?attr` tints actually
     *  resolve: a tint of 0 used to fall through to the 0xFF333333
     *  approximation. */
    private static int effectiveFillColor(int fillColor, int tint, int alpha, boolean monochrome,
                                          boolean tintDeclared) {
        return effectiveFillColor(fillColor, tint, alpha, monochrome, tintDeclared, false);
    }

    /** A path with a VISIBLE stroke and a transparent fill is a stroke-only
     *  outline: AOSP draws its stroke and no fill (VFullPath fills only when
     *  fillColor != TRANSPARENT). The transparent-fill-takes-the-tint
     *  substitution below filled it solid. Measured: Material's switch
     *  mtrl_switch_track_decoration (a 2px ?colorOutline ring, tinted
     *  colorOutline when unchecked) painted the whole unchecked track
     *  outline-grey, hiding the same-coloured thumb -- every off MaterialSwitch
     *  was a featureless grey pill (ytdlnis settings, issue #5, 2026-10-19).
     *  MACAPK_VD_STROKE_NO_TINT_FILL=0 restores the fill. */
    static final boolean MACAPK_VD_TRANSPARENT_TINT =
        !"0".equals(System.getenv("MACAPK_VD_TRANSPARENT_TINT"));
    static final boolean MACAPK_VD_STROKE_NO_TINT_FILL =
        !"0".equals(System.getenv("MACAPK_VD_STROKE_NO_TINT_FILL"));
    private static boolean macapkStrokeOnly(PathSpec s) {
        return MACAPK_VD_STROKE_NO_TINT_FILL && s != null && (s.strokeColor >>> 24) != 0
            && s.strokeWidth > 0f && (s.fillColor >>> 24) == 0 && s.fillShader == null;
    }

    private static int effectiveFillColor(int fillColor, int tint, int alpha, boolean monochrome,
                                          boolean tintDeclared, boolean strokeOnly) {
        if (strokeOnly) return 0;
        int color = fillColor;
        if (tint != 0) {
            int sa = color >>> 24;
            if (sa != 0) {
                int ta = tint >>> 24;
                int outA = (ta == 0) ? sa : (sa * ta) / 255;
                color = (outA << 24) | (tint & 0x00FFFFFF);
            }
        }
        // The white -> grey stand-in is for a vector that DECLARES a theme tint
        // (`android:tint="?colorControlNormal"`) we could not resolve: its white
        // paths are a mask, not a colour. A vector that declares NO tint paints
        // its fill as authored (AOSP VFullPath draws fillColor, nothing else), so
        // a white icon made for a dark toolbar stays white. Substituting there
        // painted Privacy Friendly Notes' white "sort A-Z" action #333333 on its
        // dark-blue toolbar, i.e. nearly invisible.
        if (monochrome && tintDeclared && color == 0xFFFFFFFF && tint == 0) color = 0xFF333333;
        // A TRANSPARENT fill under a tint takes the tint. This half has always
        // run and is unchanged.
        //
        // Its `tint == 0 -> 0xFF333333` twin is DELETED rather than repaired,
        // and that is the deliberate answer to "the substitution at :591 was
        // dead code". It was stranded because the draw guard tested the RAW
        // fillColor, so a transparent path was skipped whatever colour was
        // computed for it. Repairing the guard (which this function now makes
        // structural -- one place computes the colour, and the guard asks IT)
        // would have made the twin reachable for the first time, and reachable
        // it is WRONG:
        //   - AOSP skips a fully transparent fill outright (VFullPath draws
        //     only when fillColor != Color.TRANSPARENT), and
        //   - `monochrome` is true for any vector whose paths are all white or
        //     all transparent, so an icon that uses a transparent path as a
        //     spacer or a mask alongside a white one would have started
        //     painting a grey BLOB over the spacer.
        // Its stated purpose -- keep a themed icon visible when we fail to
        // resolve its `?attr` tint -- was written before Theme threading made
        // those tints actually resolve, so it now guards a case we mostly do
        // not have while creating one we do.
        //
        // NET PIXEL CHANGE: none. The branch never executed, and with it gone a
        // transparent untinted path is still skipped, exactly as before.
        // FALSIFIER: an icon that goes INVISIBLE where it used to be grey means
        // the branch was live after all and the guard analysis is wrong.
        if (monochrome && (color >>> 24) == 0 && tint != 0) color = tint;
        return applyAlpha(color, alpha);
    }

    @Override
    public void draw(Canvas canvas) {
        if (canvas == null || paths.isEmpty()) return;
        // Honor a PROGRAMMATIC tint (Compose Icon(tint=), AppCompat
        // DrawableCompat.setTintList / ?attr/colorControlNormal) over the XML
        // android:tint -- the base now stores it; recompute per-draw for the
        // current state. v1.0.423.
        int effTint = macapkEffectiveTint();
        if (effTint != 0) tint = effTint;
        // A programmatic tint that is fully TRANSPARENT under SRC_IN (the default
        // mode) paints nothing on AOSP: the PorterDuffColorFilter keeps the
        // source's coverage but multiplies by the tint's alpha 0. Ours read
        // tint 0 as "no tint" and drew the authored colours. Measured:
        // Material's switch decoration ring is tinted transparent when CHECKED
        // (mtrl_switch_track_decoration_tint), and every checked MaterialSwitch
        // kept a grey ?colorOutline ring (ytdlnis settings, issue #5).
        // MACAPK_VD_TRANSPARENT_TINT=0 restores drawing.
        if (MACAPK_VD_TRANSPARENT_TINT && mTintList != null && (effTint >>> 24) == 0
                && (mTintMode == null || mTintMode == android.graphics.PorterDuff.Mode.SRC_IN)
                && mColorFilter == null) {
            return;
        }
        // The explicit colour filter (MACAPK_DRAWABLE_COLOR_FILTER). AOSP
        // VectorDrawable.draw (android14-release): "Color filters always override
        // tint filters." -- `colorFilter = (mColorFilter == null ?
        // mBlendModeColorFilter : mColorFilter)`, applied to the whole rendered
        // tree. Ours paints path by path, so the filter is applied to each
        // path's colour (exact for one path, and for overlapping paths under an
        // opaque SRC_IN filter, the common recolour idiom); a filter that
        // Drawable.macapkFilterColor cannot model rides on the paint instead.
        // cf == null leaves every line below on its pre-change path.
        final android.graphics.ColorFilter cf = MACAPK_DRAWABLE_COLOR_FILTER ? mColorFilter : null;
        final boolean cfExact = cf != null && macapkFilterColor(cf, 0xFF000000) != null;
        final android.graphics.ColorFilter paintCf = (cf != null && !cfExact) ? cf : null;
        int boundsW = getBounds().width();
        int boundsH = getBounds().height();
        if (boundsW <= 0) boundsW = (int) intrinsicWidthDp;
        if (boundsH <= 0) boundsH = (int) intrinsicHeightDp;
        if (boundsW <= 0 || boundsH <= 0) return;
        float scaleX = boundsW / viewportWidth;
        float scaleY = boundsH / viewportHeight;
        // v1.0.101: mystery-icon diagnostic. Logs the absolute screen
        // position where the vector lands, computed from current canvas
        // translate + bounds. Lets us see WHERE a drawable actually
        // paints regardless of which ImageView wrapped it.
        if ("1".equals(ENV_MACAPK_TRACE_VECTORDRAW)) {
            try {
                float tx = canvas.macapkGetCurrentTx();
                float ty = canvas.macapkGetCurrentTy();
                com.macapk.loader.NativeLog.i("VectorDrawable",
                    "draw@" + Integer.toHexString(System.identityHashCode(this))
                    + " paths=" + paths.size()
                    + " bounds=" + getBounds()
                    + " vp=" + viewportWidth + "x" + viewportHeight
                    + " abs=(" + (tx + getBounds().left) + ","
                    + (ty + getBounds().top) + ")-("
                    + (tx + getBounds().right) + ","
                    + (ty + getBounds().bottom) + ")");
            } catch (Throwable ignored) {}
        }
        int saveCount = canvas.save();
        try {
            // ⚠ HONOUR THE BOUNDS ORIGIN. This used to ignore
            // getBounds().left/top on the theory that "callers (ImageView)
            // already translate the canvas" -- true of ImageView, which always
            // passes origin 0, but FALSE in general, and VectorDrawable was the
            // LONE outlier: GradientDrawable, BitmapDrawable and LayerDrawable
            // all honour the origin.
            //
            // It broke every caller that positions a drawable the AOSP way, by
            // setBounds() rather than by translating. Measured 2026-08-24:
            // MaterialButton publishes its icon centring PURELY as
            // icon.setBounds(iconLeft, ...), so Aegis's "next" arrow drew 15px
            // off; F-Droid's share glyph misregistered against its circle for
            // the same reason.
            final android.graphics.Rect b = getBounds();
            if (b.left != 0 || b.top != 0) canvas.translate(b.left, b.top);
            canvas.scale(scaleX, scaleY);
            paint.setAntiAlias(true);
            // v1.0.501: the white->gray / transparent->gray substitutions below
            // are a theme-icon approximation: a MONOCHROME vector that is all
            // white/transparent paths relies on a (?colorControlNormal) theme
            // tint we don't resolve, so without it it'd be invisible on a white
            // background. But a FULL-COLOR vector (e.g. duolingo's launcher-icon
            // owl: greens + orange + white eyes) uses white intentionally, and
            // forcing those whites to gray turned the owl's eyes into black
            // blobs. Only approximate when the vector is genuinely monochrome.
            boolean monochrome = true;
            for (PathSpec s : paths) {
                // A GRADIENT-filled path carries fillColor 0 (it paints through
                // fillShader instead, see :580), so the alpha test below skipped
                // it as "transparent" -- and a logo built from a coloured
                // gradient mark plus white lettering was misread as monochrome.
                // Every white path then got repainted 0xFF333333 while the
                // gradient survived, which is exactly what mastodon's splash
                // showed: the purple `m` intact, the wordmark and eye dot grey.
                // A vector containing a gradient is never a flat themed icon.
                if (s.fillShader != null) { monochrome = false; break; }
                int c = s.fillColor;
                if ((c >>> 24) == 0) continue;          // transparent path, ignore
                if (c != 0xFFFFFFFF) { monochrome = false; break; }  // a real color
            }
            // Same gate as the draw@ line above (ONE env var, deliberately -- a
            // second one would be another thing to keep in step). This half
            // answers "reached draw but painted nothing": it reports the values
            // the :624 draw GUARD actually tests, so a silently-skipped path is
            // visible as data instead of as an absent icon. Added chasing Aurora
            // Store's six blank onboarding icons, which reach draw with paths=1
            // and correct bounds/viewport.
            if ("1".equals(ENV_MACAPK_TRACE_VECTORDRAW)) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("paint@").append(Integer.toHexString(System.identityHashCode(this)))
                      .append(" mono=").append(monochrome)
                      .append(" tint=0x").append(Integer.toHexString(tint))
                      .append(" alpha=").append(alpha);
                    for (PathSpec s : paths) {
                        sb.append(" | fill=0x").append(Integer.toHexString(s.fillColor))
                          .append(" stroke=0x").append(Integer.toHexString(s.strokeColor))
                          .append(" sw=").append(s.strokeWidth)
                          .append(" shader=").append(s.fillShader != null)
                          // The draw guard verbatim -- false here means this path is
                          // skipped. It reads the SAME effectiveFillColor() the draw
                          // loop paints with, so the trace cannot disagree with the
                          // code it is reporting on (it could, and did, before).
                          .append(" eff=0x").append(Integer.toHexString(
                              effectiveFillColor(s.fillColor, tint, alpha, monochrome, macapkTintDeclared, macapkStrokeOnly(s))))
                          .append(" WILLDRAW=").append(
                              (s.fillShader != null && tint == 0)
                              || (effectiveFillColor(s.fillColor, tint, alpha, monochrome, macapkTintDeclared, macapkStrokeOnly(s))
                                  & 0xFF000000) != 0);
                        // The GEOMETRY. A pathData string our parser cannot read
                        // yields a Path with no segments, which draws with a
                        // passing colour guard and produces zero pixels -- exactly
                        // the failure shape being chased here, and invisible in
                        // every colour field above. Empty/degenerate bounds here
                        // means the parser, not the pipeline.
                        try {
                            java.awt.Shape sh = s.path == null ? null : s.path.macapkShape();
                            java.awt.geom.Rectangle2D r = sh == null ? null : sh.getBounds2D();
                            sb.append(" geom=").append(r == null ? "null"
                                : ("[" + (float) r.getX() + "," + (float) r.getY() + " "
                                   + (float) r.getWidth() + "x" + (float) r.getHeight() + "]"));
                        } catch (Throwable t) { sb.append(" geom=<").append(t).append('>'); }
                    }
                    // WHERE the ink lands and WHAT it lands in -- see
                    // Canvas.macapkTraceState. The target identity separates
                    // "drawn into the frame" from "drawn into an offscreen
                    // buffer nobody composites", which nothing above can.
                    sb.append(" || ").append(canvas.macapkTraceState());
                    com.macapk.loader.NativeLog.i("VectorDrawable", sb.toString());
                    // WHO asked for this draw. R8 renames the app's own classes,
                    // so the frame names are only a shape -- but the FRAMEWORK
                    // frames in between (ImageView / RenderNode / a Compose
                    // painter bridge) are ours and are named, which is enough to
                    // tell which pipeline is driving.
                    StringBuilder cs = new StringBuilder("caller@")
                        .append(Integer.toHexString(System.identityHashCode(this)));
                    StackTraceElement[] st = new Throwable().getStackTrace();
                    for (int i = 1; i < st.length && i <= 16; i++) {
                        cs.append("\n    ").append(st[i]);
                    }
                    com.macapk.loader.NativeLog.i("VectorDrawable", cs.toString());
                } catch (Throwable ignored) {}
            }
            paint.setColorFilter(paintCf);
            for (PathSpec spec : paths) {
                int color = (cf != null)
                        ? macapkFilteredFill(spec.fillColor, alpha, cfExact ? cf : null)
                        : effectiveFillColor(spec.fillColor, tint, alpha, monochrome, macapkTintDeclared, macapkStrokeOnly(spec));
                // v1.0.501 (extended: graphics-drawable-11): apply the path's
                // accumulated <group> transform chain, root-to-leaf. A canvas
                // op composes with the CURRENT transform such that the
                // most-recently-called op is applied FIRST to subsequent
                // drawing (e.g. translate(100,100) then rotate(45) draws a
                // shape rotated about the new origin, then moved -- the LAST
                // call is closest to the object). To realize hwui's per-group
                // M = postTranslate(tx+px,ty+py) . postRotate(rot) .
                // postScale(sx,sy) . postTranslate(-px,-py) we therefore call,
                // in this exact order: translate(tx+px,ty+py) -> rotate(rot) ->
                // scale(sx,sy) -> translate(-px,-py). Nesting multiple ancestor
                // groups this way composes correctly because each child's
                // calls land deeper (closer to the path than its parent's).
                boolean grouped = spec.groupChain != null && !spec.groupChain.isEmpty();
                int sc = grouped ? canvas.save() : -1;
                if (grouped) {
                    for (GroupXform g : spec.groupChain) {
                        canvas.translate(g.tx + g.pivotX, g.ty + g.pivotY);
                        if (g.rotation != 0f) canvas.rotate(g.rotation);
                        canvas.scale(g.sx, g.sy);
                        canvas.translate(-g.pivotX, -g.pivotY);
                    }
                }
                // A gradient fill paints via the shader (unless a tint is set,
                // which per SRC_IN replaces the whole fill with a flat colour).
                // An explicit colour filter replaces the tint, so a gradient path
                // under one still paints its shader, through the filter (on the
                // paint: a shader's pixels cannot be filtered as one colour).
                if (spec.fillShader != null && (tint == 0 || cf != null)) {
                    android.graphics.Shader prev = paint.getShader();
                    paint.setShader(spec.fillShader);
                    paint.setStyle(Paint.Style.FILL);
                    if (cf != null) paint.setColorFilter(cf);
                    canvas.drawPath(spec.path, paint);
                    if (cf != null) paint.setColorFilter(paintCf);
                    paint.setShader(prev);
                // ---- Draw guard: test the colour we are ABOUT TO PAINT. ----
                // This used to test the RAW `spec.fillColor` (plus tint/shader),
                // i.e. the value BEFORE the monochrome substitutions above, which
                // made one of those substitutions unreachable: for a monochrome
                // icon whose path is fully transparent and which carries no tint,
                // the second substitution computes a visible 0xFF333333 and this
                // guard then refused to draw it, because the raw fillColor's alpha
                // is 0. Dead code, and silently so -- the path was skipped with no
                // trace. Consulting `color` makes the guard agree with the
                // substitution chain by construction, so a future substitution
                // cannot be stranded the same way.
                //
                // The two cases where the old and new tests disagree in the other
                // direction (raw guard passed, `color` is transparent) are a
                // zero-alpha fill and a tint applied to a zero-alpha fill; both
                // painted a fully transparent nothing under SRC_OVER, so no pixel
                // changes -- the draw call simply stops being issued.
                //
                // NOTE (unfixed, deliberately out of scope here): a GRADIENT path
                // under a non-zero tint still paints nothing. Gradient paths carry
                // fillColor 0, so the tint block above leaves `color` at 0 and this
                // branch has always painted transparent. AOSP would paint the
                // shader tinted via PorterDuffColorFilter(SRC_IN). Same zero pixels
                // before and after this change.
                } else if ((color & 0xFF000000) != 0) {
                    paint.setColor(color);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawPath(spec.path, paint);
                }
                if (spec.strokeColor != 0 && spec.strokeWidth > 0) {
                    // The tint filter covers STROKES too (AOSP sets it on the
                    // paint, which serves both fill and stroke passes) -- a
                    // stroke-only themed icon would otherwise keep its authored
                    // colour while its fills tinted.
                    int sc2 = spec.strokeColor;
                    if (cf != null) {
                        // The colour filter replaces the tint on strokes too.
                        int c2 = applyAlpha(sc2, alpha);
                        Integer f = cfExact ? macapkFilterColor(cf, c2) : null;
                        paint.setColor(f != null ? f : c2);
                    } else {
                        if (tint != 0) {
                            int sa = sc2 >>> 24;
                            if (sa != 0) {
                                int ta = tint >>> 24;
                                int outA = (ta == 0) ? sa : (sa * ta) / 255;
                                sc2 = (outA << 24) | (tint & 0x00FFFFFF);
                            }
                        }
                        paint.setColor(applyAlpha(sc2, alpha));
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(spec.strokeWidth);
                    canvas.drawPath(spec.path, paint);
                }
                if (grouped) canvas.restoreToCount(sc);
            }
            // The ONE fact none of the traces above can supply: did the draw
            // loop actually deposit pixels? Everything else here reports an
            // INTENTION (the colour we chose, the geometry we hold, the state
            // we drew into); this reports the RESULT. Without it, "rasterized
            // nothing" and "rasterized fine into a buffer the caller discards"
            // are indistinguishable, and the pipeline downstream of this file
            // cannot be blamed or cleared.
            if ("1".equals(ENV_MACAPK_TRACE_VECTORDRAW)) {
                try {
                    com.macapk.loader.NativeLog.i("VectorDrawable",
                        "after@" + Integer.toHexString(System.identityHashCode(this))
                        + " " + canvas.macapkTraceInk());
                } catch (Throwable ignored) {}
            }
        } finally {
            canvas.restoreToCount(saveCount);
        }
    }

    private static int applyAlpha(int color, int alpha) {
        int a = ((color >>> 24) * alpha) / 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override public void setAlpha(int alpha) { this.alpha = alpha; }

    /** AOSP VectorDrawable.mColorFilter, android14-release :472-481:
     *      public void setColorFilter(ColorFilter colorFilter) {
     *          mColorFilter = colorFilter;
     *          invalidateSelf();
     *      }
     *      public ColorFilter getColorFilter() { return mColorFilter; }
     *  Both were empty here, so a colour filter on a vector icon was dropped
     *  (and so was CompoundButton's button tint, which this tree delivers as a
     *  PorterDuffColorFilter). MACAPK_DRAWABLE_COLOR_FILTER=0: the old no-op. */
    private android.graphics.ColorFilter mColorFilter;
    @Override public void setColorFilter(android.graphics.ColorFilter cf) {
        if (!MACAPK_DRAWABLE_COLOR_FILTER) return;
        mColorFilter = cf;
        invalidateSelf();
    }
    @Override public android.graphics.ColorFilter getColorFilter() {
        return MACAPK_DRAWABLE_COLOR_FILTER ? mColorFilter : null;
    }

    /** A path's fill under an explicit colour filter: the authored colour at the
     *  drawable's alpha, then the filter (`cf` null = the filter is on the
     *  paint). No monochrome substitution: those stand in for an UNRESOLVED
     *  tint, and a colour filter is the app saying exactly what colour it
     *  wants. A fully transparent fill stays skipped, as AOSP's VFullPath skips
     *  it (it draws the fill only when fillColor != Color.TRANSPARENT). */
    private static int macapkFilteredFill(int fillColor, int alpha, android.graphics.ColorFilter cf) {
        if ((fillColor >>> 24) == 0) return 0;
        int c = applyAlpha(fillColor, alpha);
        if (cf == null) return c;
        Integer f = macapkFilterColor(cf, c);
        return f != null ? f : c;
    }
    @Override public int getOpacity() { return -3; /* TRANSLUCENT */ }
    @Override public int getIntrinsicWidth()  { return (int) intrinsicWidthDp; }
    @Override public int getIntrinsicHeight() { return (int) intrinsicHeightDp; }

    // -------- path-data parser (SVG-style) --------

    private static Path parsePathData(String d) {
        Path path = new Path();
        if (d == null || d.isEmpty()) return path;
        int len = d.length();
        int i = 0;
        char prevCmd = 'M';
        float curX = 0, curY = 0;
        float startX = 0, startY = 0;
        float lastCubicX = 0, lastCubicY = 0;
        float lastQuadX = 0, lastQuadY = 0;
        while (i < len) {
            char c = d.charAt(i);
            if (Character.isWhitespace(c) || c == ',') { i++; continue; }
            // No-progress guard, UNCONDITIONAL (a hang has no legitimate A/B
            // arm). An implicit (repeated) command at a character that starts
            // neither a command nor a number (the '@' of an unresolved
            // "@string/..." reference, a '#', a '/') consumed nothing, left i
            // where it was, and the loop appended to the Path forever until
            // OutOfMemoryError. See the check after the switch.
            final int macapkBefore = i;
            char cmd;
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else {
                // Implicit command: same as previous, but lowercase M becomes l
                cmd = prevCmd;
                if (cmd == 'M') cmd = 'L';
                else if (cmd == 'm') cmd = 'l';
            }
            prevCmd = cmd;
            switch (cmd) {
                case 'M': {
                    float[] xy = readPair(d, i, 2); i = (int) xy[2];
                    curX = xy[0]; curY = xy[1];
                    path.moveTo(curX, curY);
                    startX = curX; startY = curY;
                    break;
                }
                case 'm': {
                    float[] xy = readPair(d, i, 2); i = (int) xy[2];
                    curX += xy[0]; curY += xy[1];
                    path.moveTo(curX, curY);
                    startX = curX; startY = curY;
                    break;
                }
                case 'L': {
                    float[] xy = readPair(d, i, 2); i = (int) xy[2];
                    curX = xy[0]; curY = xy[1];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'l': {
                    float[] xy = readPair(d, i, 2); i = (int) xy[2];
                    curX += xy[0]; curY += xy[1];
                    path.lineTo(curX, curY);
                    break;
                }
                // v1.0.100: readPair returns float[n+1] where index n holds
                // the new parse position. H/h/V/v read ONE value, so the
                // new-index slot is xy[1], NOT xy[2]. The prior xy[2]
                // threw ArrayIndexOutOfBoundsException for every icon
                // using horizontal/vertical lineto (ic_categories,
                // ic_updates, ic_settings) -- silently swallowed by the
                // outer try, so the icon ended up with zero paths and
                // rendered as nothing.
                case 'H': {
                    float[] xy = readPair(d, i, 1); i = (int) xy[1];
                    curX = xy[0];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'h': {
                    float[] xy = readPair(d, i, 1); i = (int) xy[1];
                    curX += xy[0];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'V': {
                    float[] xy = readPair(d, i, 1); i = (int) xy[1];
                    curY = xy[0];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'v': {
                    float[] xy = readPair(d, i, 1); i = (int) xy[1];
                    curY += xy[0];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'C': {
                    float[] xy = readPair(d, i, 6); i = (int) xy[6];
                    path.cubicTo(xy[0], xy[1], xy[2], xy[3], xy[4], xy[5]);
                    lastCubicX = xy[2]; lastCubicY = xy[3];
                    curX = xy[4]; curY = xy[5];
                    break;
                }
                case 'c': {
                    float[] xy = readPair(d, i, 6); i = (int) xy[6];
                    float x1 = curX + xy[0], y1 = curY + xy[1];
                    float x2 = curX + xy[2], y2 = curY + xy[3];
                    float x3 = curX + xy[4], y3 = curY + xy[5];
                    path.cubicTo(x1, y1, x2, y2, x3, y3);
                    lastCubicX = x2; lastCubicY = y2;
                    curX = x3; curY = y3;
                    break;
                }
                case 'S': {
                    float[] xy = readPair(d, i, 4); i = (int) xy[4];
                    float x1 = 2*curX - lastCubicX;
                    float y1 = 2*curY - lastCubicY;
                    path.cubicTo(x1, y1, xy[0], xy[1], xy[2], xy[3]);
                    lastCubicX = xy[0]; lastCubicY = xy[1];
                    curX = xy[2]; curY = xy[3];
                    break;
                }
                case 's': {
                    float[] xy = readPair(d, i, 4); i = (int) xy[4];
                    float x1 = 2*curX - lastCubicX;
                    float y1 = 2*curY - lastCubicY;
                    float x2 = curX + xy[0], y2 = curY + xy[1];
                    float x3 = curX + xy[2], y3 = curY + xy[3];
                    path.cubicTo(x1, y1, x2, y2, x3, y3);
                    lastCubicX = x2; lastCubicY = y2;
                    curX = x3; curY = y3;
                    break;
                }
                case 'Q': {
                    float[] xy = readPair(d, i, 4); i = (int) xy[4];
                    path.quadTo(xy[0], xy[1], xy[2], xy[3]);
                    lastQuadX = xy[0]; lastQuadY = xy[1];
                    curX = xy[2]; curY = xy[3];
                    break;
                }
                case 'q': {
                    float[] xy = readPair(d, i, 4); i = (int) xy[4];
                    float x1 = curX + xy[0], y1 = curY + xy[1];
                    float x2 = curX + xy[2], y2 = curY + xy[3];
                    path.quadTo(x1, y1, x2, y2);
                    lastQuadX = x1; lastQuadY = y1;
                    curX = x2; curY = y2;
                    break;
                }
                case 'T': {
                    float[] xy = readPair(d, i, 2); i = (int) xy[2];
                    float x1 = 2*curX - lastQuadX;
                    float y1 = 2*curY - lastQuadY;
                    path.quadTo(x1, y1, xy[0], xy[1]);
                    lastQuadX = x1; lastQuadY = y1;
                    curX = xy[0]; curY = xy[1];
                    break;
                }
                case 't': {
                    float[] xy = readPair(d, i, 2); i = (int) xy[2];
                    float x1 = 2*curX - lastQuadX;
                    float y1 = 2*curY - lastQuadY;
                    float x2 = curX + xy[0], y2 = curY + xy[1];
                    path.quadTo(x1, y1, x2, y2);
                    lastQuadX = x1; lastQuadY = y1;
                    curX = x2; curY = y2;
                    break;
                }
                case 'A':
                case 'a': {
                    // Elliptical arc: 7 params (rx ry x-axis-rotation
                    // large-arc-flag sweep-flag x y).
                    // lane layout, 2026-08-02: REAL arc -> cubic conversion.
                    // This used to emit a lineTo to the arc's endpoint, which
                    // silently destroys the single most common icon idiom
                    // there is: a circle drawn as `m-r,0 a r,r 0 1,1 2r,0
                    // a r,r 0 1,1 -2r,0` collapses to a zero-area segment, so
                    // the whole disc VANISHES. Measured: DeskClock's 60dp
                    // header icon drew a 6x26px red sliver (only the straight
                    // seconds-hand stroke survived) instead of a blue clock
                    // face -- the stroke length proved viewport scaling was
                    // already correct, so the arcs were the entire loss
                    // (docs/canvas-ab-analyst-report_2026-08-02.md item 7).
                    float[] xy = readPair(d, i, 7); i = (int) xy[7];
                    float rx = xy[0], ry = xy[1], phi = xy[2];
                    boolean largeArc = xy[3] != 0f, sweep = xy[4] != 0f;
                    float ex = xy[5], ey = xy[6];
                    if (cmd == 'a') { ex += curX; ey += curY; }
                    macapkArcTo(path, curX, curY, ex, ey, rx, ry, phi, largeArc, sweep);
                    curX = ex; curY = ey;
                    break;
                }
                case 'Z':
                case 'z': {
                    path.close();
                    curX = startX; curY = startY;
                    // AOSP ignores anything between a Z and the next command
                    // letter: getFloats returns before reading a single number
                    // when the verb is z/Z (libs/hwui/PathParser.cpp:126-128,
                    // android14), and nextStart treats every ASCII letter but
                    // e/E as the next command (:29-43). A number after Z used to
                    // be taken as an implicit Z that consumed nothing, which is
                    // the hang above ("M0,0 L1,1 Z 5").
                    while (i < len && !macapkIsVerbStart(d.charAt(i))) i++;
                    break;
                }
                default:
                    // Unknown command -- skip one number to avoid infinite loop.
                    // v1.0.100: readPair(n=1) returns float[2], new index at xy[1].
                    float[] xy = readPair(d, i, 1); i = (int) xy[1];
                    break;
            }
            if (i == macapkBefore) {
                // AOSP rejects the WHOLE string here rather than keeping the
                // geometry parsed so far: getPathDataFromAsciiString stops at
                // the first segment that is not a valid verb or whose floats do
                // not parse (libs/hwui/PathParser.cpp:236-244, android14) and
                // the JNI layer throws IllegalArgumentException, which is what
                // new PathParser.PathData(pathString) raises in
                // VFullPath.updateStateFromTypedArray (VectorDrawable.java:2026-
                // 2029). The message follows the native one's shape.
                throw new IllegalArgumentException("'" + d.charAt(i)
                    + "' starts neither a command nor a number. Failure occurred at position "
                    + i + " of path: " + d);
            }
        }
        return path;
    }

    /** AOSP PathParser.cpp nextStart (:29-43): a command starts at any ASCII
     *  letter except 'e' / 'E', which belong to a float's exponent. */
    private static boolean macapkIsVerbStart(char c) {
        return ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) && c != 'e' && c != 'E';
    }

    /** SVG/Android elliptical-arc -> cubic beziers, appended to `path`.
     *  Transcription of the standard endpoint->center parameterisation
     *  (SVG 1.1 implementation notes F.6.5 / F.6.6), the same algorithm
     *  androidx.core's PathParser.drawArc/arcToBezier uses -- including the
     *  out-of-range radii correction (F.6.6.2) and the <=90-degree segment
     *  split that keeps each cubic's error negligible. */
    private static void macapkArcTo(Path path, float x0, float y0,
                                    float x1, float y1,
                                    float rx, float ry, float phiDeg,
                                    boolean largeArc, boolean sweep) {
        if (rx == 0f || ry == 0f) { path.lineTo(x1, y1); return; }
        if (x0 == x1 && y0 == y1) return;             // degenerate: no-op per spec
        rx = Math.abs(rx); ry = Math.abs(ry);
        double phi = Math.toRadians(phiDeg);
        double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);
        // F.6.5.1: midpoint transform into the ellipse's own frame.
        double dx2 = (x0 - x1) / 2.0, dy2 = (y0 - y1) / 2.0;
        double x1p =  cosPhi * dx2 + sinPhi * dy2;
        double y1p = -sinPhi * dx2 + cosPhi * dy2;
        // F.6.6.2: scale up radii that cannot span the chord.
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1.0) {
            double s = Math.sqrt(lambda);
            rx = (float) (rx * s); ry = (float) (ry * s);
        }
        double rx2 = (double) rx * rx, ry2 = (double) ry * ry;
        // F.6.5.2: centre in the transformed frame.
        double num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
        double den = rx2 * y1p * y1p + ry2 * x1p * x1p;
        double coef = (den == 0) ? 0 : Math.sqrt(Math.max(0.0, num / den));
        if (largeArc == sweep) coef = -coef;
        double cxp =  coef * (rx * y1p / ry);
        double cyp = -coef * (ry * x1p / rx);
        // F.6.5.3: centre back in user space.
        double cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2.0;
        double cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2.0;
        // F.6.5.5/6: start angle and sweep.
        double ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry;
        double vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry;
        double theta1 = Math.atan2(uy, ux);
        double dTheta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && dTheta > 0) dTheta -= 2 * Math.PI;
        else if (sweep && dTheta < 0) dTheta += 2 * Math.PI;
        // Split into <=90-degree pieces; each becomes one cubic.
        int segs = (int) Math.ceil(Math.abs(dTheta / (Math.PI / 2.0)));
        if (segs == 0) return;
        double delta = dTheta / segs;
        // Control-point magnitude for a circular arc of angle `delta`.
        double t = 4.0 / 3.0 * Math.tan(delta / 4.0);
        double theta = theta1;
        for (int s = 0; s < segs; s++) {
            double cosT1 = Math.cos(theta), sinT1 = Math.sin(theta);
            double theta2 = theta + delta;
            double cosT2 = Math.cos(theta2), sinT2 = Math.sin(theta2);
            // Endpoint of this segment, in user space.
            double e2x = cx + rx * cosPhi * cosT2 - ry * sinPhi * sinT2;
            double e2y = cy + rx * sinPhi * cosT2 + ry * cosPhi * sinT2;
            // Derivatives at both ends give the control points.
            double d1x = -rx * cosPhi * sinT1 - ry * sinPhi * cosT1;
            double d1y = -rx * sinPhi * sinT1 + ry * cosPhi * cosT1;
            double d2x = -rx * cosPhi * sinT2 - ry * sinPhi * cosT2;
            double d2y = -rx * sinPhi * sinT2 + ry * cosPhi * cosT2;
            double s1x = cx + rx * cosPhi * cosT1 - ry * sinPhi * sinT1;
            double s1y = cy + rx * sinPhi * cosT1 + ry * cosPhi * sinT1;
            path.cubicTo((float) (s1x + t * d1x), (float) (s1y + t * d1y),
                         (float) (e2x - t * d2x), (float) (e2y - t * d2y),
                         (float) e2x, (float) e2y);
            theta = theta2;
        }
    }

    /** Read N floats starting at index i. Returns [v0..vN-1, newIndex]
     *  packed into a length-(N+1) float[]. Parses "x,y" or "x y" or
     *  "x-y" (negative sign is implicit separator). */
    private static float[] readPair(String d, int i, int n) {
        float[] out = new float[n + 1];
        int len = d.length();
        for (int k = 0; k < n; k++) {
            // Skip whitespace + commas
            while (i < len && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) i++;
            int start = i;
            if (i < len && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
            boolean sawDot = false;
            boolean sawExp = false;
            while (i < len) {
                char c = d.charAt(i);
                if (Character.isDigit(c)) { i++; continue; }
                if (c == '.' && !sawDot) { sawDot = true; i++; continue; }
                if ((c == 'e' || c == 'E') && !sawExp) {
                    sawExp = true; sawDot = true; i++;
                    if (i < len && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                    continue;
                }
                break;
            }
            if (start == i) { out[k] = 0f; }
            else {
                try { out[k] = Float.parseFloat(d.substring(start, i)); }
                catch (NumberFormatException nfe) { out[k] = 0f; }
            }
        }
        out[n] = i;
        return out;
    }

    private static float parseFloat(String s, float def) {
        if (s == null || s.isEmpty()) return def;
        // AOSP reads every numeric vector attribute through TypedArray
        // (VGroup.updateStateFromTypedArray: a.getFloat(scaleX, ...);
        // android14 VectorDrawable.java:1403-1416), which resolves a
        // reference first. `@dimen/icon_scale` fell through to the unit strip
        // below and returned the DEFAULT -- OpenCalculator's backspace/exponent
        // icons (group scale 0.7 + translate 3.5 via @dimen) drew 1.43x too big.
        // v1.0.1643.
        if (s.charAt(0) == '@' && LAYOUT_AOSP_1643) s = macapkResolveNumericRef(s);
        if (s == null || s.isEmpty() || s.charAt(0) == '@') return def;
        // Strip trailing unit suffix (dp/sp/px/etc).
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) { end = i; break; }
        }
        try { return Float.parseFloat(s.substring(0, end)); }
        catch (NumberFormatException e) { return def; }
    }

    /** `@dimen/X`, `@integer/X` or `@0x7f......` (a dimen or integer id) -> its
     *  raw table text ("0.7", "3.5", "24dp", "52"), chasing dimen -> dimen chains as
     *  Resources.macapkDimenPx does; anything it cannot resolve comes back
     *  unchanged. `?attr` floats are not resolved here (left as a gap). */
    /** See android.view.LayoutInflater.LAYOUT_AOSP_1643 (bisect gate). */
    private static final boolean LAYOUT_AOSP_1643 =
        !"0".equals(System.getenv("MACAPK_LAYOUT_AOSP_1643"));

    /** BISECT GATE (2026-10-12, default ON): an `@integer` reference in a
     *  numeric vector attribute (viewportWidth/Height, a group pivot) resolves to
     *  its value, as TypedArray.getFloat does. =0: it falls back to the
     *  attribute's default, the pre-change geometry. */
    private static final boolean VD_INTEGER_REF =
        !"0".equals(System.getenv("MACAPK_VD_INTEGER_REF"));

    private static String macapkResolveNumericRef(String s) {
        try {
            Object ri = Resources.macapkGetResourceIndex();
            if (ri == null) return s;
            java.lang.reflect.Method m = ri.getClass().getMethod("getDimenRaw", String.class);
            String v = s;
            for (int hops = 0; hops < 8 && v != null && v.startsWith("@"); hops++) {
                String name = null;
                if (v.startsWith("@dimen/")) {
                    name = v.substring(7);
                } else if (v.startsWith("@integer/")) {
                    // An @integer reference: AOSP's TypedArray.getFloat resolves the
                    // reference and returns an INT-typed value as a float
                    // (TypedArray.java getFloat: `type >= TYPE_FIRST_INT &&
                    // type <= TYPE_LAST_INT -> return data`). Material Components
                    // sizes its switch this way: viewportWidth=
                    // "@integer/mtrl_switch_track_viewport_width" (52), and the
                    // default 24 we fell back to drew the 52x32 track ~2x too wide
                    // and the thumb off the view's edge (ytdlnis 1.9.0, 2026-10-12).
                    if (!VD_INTEGER_REF) return v;
                    Object r = ri.getClass().getMethod("integerForName", String.class)
                        .invoke(ri, v.substring(9));
                    return r instanceof Integer n ? Integer.toString(n) : s;
                } else if (v.startsWith("@0x")) {
                    // BinaryXmlParser writes `@0x%08x` when it cannot name the
                    // id; recover type/name from the table.
                    int id = (int) Long.parseLong(v.substring(3), 16);
                    Object tn = ri.getClass().getMethod("macapkTypeNameOf", int.class)
                        .invoke(ri, id);
                    if (tn instanceof String[] a && a.length >= 2 && "dimen".equals(a[0])) {
                        name = a[1];
                    } else if (VD_INTEGER_REF && tn instanceof String[] a
                               && a.length >= 2 && "integer".equals(a[0])) {
                        // As the @integer/ branch above.
                        Object r = ri.getClass().getMethod("integerForId", int.class)
                            .invoke(ri, id);
                        return r instanceof Integer n ? Integer.toString(n) : s;
                    }
                }
                if (name == null) return v;
                Object r = m.invoke(ri, name);
                if (!(r instanceof String)) return s;
                v = ((String) r).trim();
            }
            return v;
        } catch (Throwable ignored) {
            return s;
        }
    }

    /** BISECT GATE (2026-09-26, default ON; debug/factorymerge_gate.sh, dep
     *  rows): a reference-valued android:pathData is RESOLVED, as AOSP's
     *  TypedArray.getString(R.styleable.VectorDrawablePath_pathData) resolves it
     *  (android14 VectorDrawable.java:2026). Material Components ships its
     *  checkbox and switch geometry that way (mtrl_ic_checkbox_checked:
     *  pathData="@string/mtrl_checkbox_button_path_checked"; 23 of Aegis's 136
     *  pathData values). =0: the raw reference reaches parsePathData, which
     *  rejects it (IllegalArgumentException) -- the pre-change geometry (none)
     *  without the pre-change hang. The no-progress guard in parsePathData is
     *  NOT behind this gate. */
    private static final boolean MACAPK_VD_PATHDATA_REF =
        !"0".equals(System.getenv("MACAPK_VD_PATHDATA_REF"));

    /** android:pathData as TypedArray.getString hands it to AOSP's VFullPath.
     *  "@null" is a null value there, so getString returns null and the path
     *  simply gets no data (VectorDrawable.java:2027 `if (pathString != null)`).
     *  A reference we cannot resolve is left RAW, so parsePathData rejects it
     *  loudly (the constructor logs "parse FAILED") instead of drawing nothing
     *  in silence: on a device the same reference would have resolved. */
    private static String macapkPathDataRef(String raw) {
        if (!MACAPK_VD_PATHDATA_REF) return raw;
        if ("@null".equals(raw)) return null;
        String v = macapkResolveStringRef(raw);
        return v != null ? v : raw;
    }

    /** A string reference (`@string/x`, `@android:string/x`, `@*android:string/x`,
     *  or BinaryXmlParser's `@0x7f......` when it cannot name the id) resolved to
     *  its value, or null when it cannot be. Goes through the process
     *  ResourceIndex, like macapkResolveNumericRef, so it works for every
     *  constructor, including the Element ones that carry no Resources.
     *  ResourceIndex.stringForId follows string -> string alias chains itself
     *  (macapkResolveStringChain) and serves platform strings for 0x01 ids. An
     *  explicit package prefix selects the namespace, as AOSP's
     *  "package:type/entry" form does (ResourceIndex.idOfName reads "android"
     *  from the platform table and never from the app's). */
    private static String macapkResolveStringRef(String ref) {
        if (ref == null || !ref.startsWith("@")) return null;
        try {
            Object ri = Resources.macapkGetResourceIndex();
            if (ri == null) return null;
            int id;
            if (ref.startsWith("@0x")) {
                id = (int) Long.parseLong(ref.substring(3), 16);
            } else {
                String body = ref.substring(1);
                if (body.startsWith("*")) body = body.substring(1);   // a private platform ref
                int slash = body.indexOf('/');
                if (slash <= 0) return null;
                String type = body.substring(0, slash), pkg = null;
                int colon = type.indexOf(':');
                if (colon >= 0) { pkg = type.substring(0, colon); type = type.substring(colon + 1); }
                Object o = ri.getClass().getMethod("idOfName", String.class, String.class, String.class)
                    .invoke(ri, body.substring(slash + 1), type, pkg);
                id = o instanceof Integer n ? n : 0;
            }
            if (id == 0) return null;
            Object v = ri.getClass().getMethod("stringForId", int.class).invoke(ri, id);
            return v instanceof String str && !str.isEmpty() ? str : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static float parseDpToPx(String s, float defPx) {
        // AOSP convention: 1dp = density px, at the device density.
        final float d = android.util.DisplayMetrics.macapkDeviceDensity();
        float v = parseFloat(s, defPx / d);
        return v * d;
    }

    private int parseColor(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        if (s.startsWith("?")) {
            // Theme-attribute fill ("?android:colorControlNormal",
            // "?attr/colorOnSurface"). Real Android resolves these
            // against the inflating context's theme; we resolve
            // through the INFLATING Theme when one was threaded in
            // (macapkTheme, lane layout 2026-08-01 -- its cascade walk
            // already falls back to the application theme on a miss),
            // else through the static application-theme cascade as
            // before. Previously ALWAYS static: an attr declared only
            // on the runtime setTheme() theme (AntennaPod's
            // ?action_icon_color) missed and every such path painted
            // the transparent fallback -- invisible bottom-nav icons.
            // Namespace-preserving: "?android:attr/X" -> "android:X"
            // (a FRAMEWORK attr, distinct from app attr "X" -- see
            // ResourceIndex.resolveAttrInTheme); "?attr/X" -> "X".
            String attr = s.substring(1);
            if (attr.startsWith("android:attr/"))
                attr = "android:" + attr.substring(13);
            else if (attr.startsWith("attr/")) attr = attr.substring(5);
            // "?0x0101....": the COMPILED form (BinaryXmlParser's rendering
            // of TYPE_ATTRIBUTE), which is what a framework compiled
            // drawable carries -- e.g. control_background_40dp_material's
            // ripple colour ?colorControlHighlight arrives as its id. Map
            // the id back to the attr NAME through the platform table, then
            // resolve by name exactly like the source-text form above.
            if (attr.startsWith("0x") || attr.startsWith("0X")) {
                String mapped = null;
                try {
                    int aid = (int) Long.parseLong(attr.substring(2), 16);
                    Object ri = Resources.macapkGetResourceIndex();
                    if (ri != null && (aid >>> 24) == 0x01) {
                        Object tn = ri.getClass()
                            .getMethod("macapkFwEntryTypeName", int.class)
                            .invoke(ri, aid);
                        if (tn instanceof String t && t.startsWith("attr/"))
                            mapped = "android:" + t.substring(5);
                    }
                } catch (Throwable ignored) {}
                if (mapped == null) return def;
                attr = mapped;
            }
            if (macapkTheme != null)
                return macapkTheme.resolveAttrColor(attr, def);
            return android.content.res.Resources.macapkThemeColor(attr, def);
        }
        if (s.startsWith("@")) {
            // lane layout A, 2026-08-02: RESOLVE `@color/...` path fills.
            // AOSP inflates fillColor as a full ComplexColor with Resources in
            // hand, so `@android:color/white` is a real colour; we returned the
            // caller's default (0 = transparent) and the path vanished.
            // Measured: DeskClock's clock HANDS are
            // `fillColor="@android:color/white"` -- only the grey hub dot and
            // the red seconds stroke painted, which is exactly the capture.
            // (A `@drawable/$name__N` gradient ref is handled separately by
            // macapkResolveGradient; it returns null here and paints via the
            // shader instead.) See docs/canvas-ab-analyst-report-a_2026-08-02.md
            // item 5b.
            try {
                Object ri = Resources.macapkGetResourceIndex();
                if (ri != null) {
                    Object c = ri.getClass()
                        .getMethod("macapkResolveColorExpr", String.class)
                        .invoke(ri, s);
                    if (c instanceof Integer) return (Integer) c;
                }
            } catch (Throwable ignored) {}
            return def;
        }
        if (s.startsWith("#")) {
            // macapkParseResourceColor handles the #RGB/#ARGB shorthand apktool can
            // re-emit, which the public Color.parseColor rejects as AOSP does.
            try { return android.graphics.Color.macapkParseResourceColor(s); }
            catch (Throwable e) { return def; }
        }
        return def;
    }
}
