// android.graphics.Paint -- bag of drawing attributes. We track just
// enough state for apps to set/get without throwing.
package android.graphics;

public class Paint {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_PAINT_AA_DEFAULT = System.getenv("MACAPK_PAINT_AA_DEFAULT");

    public static class FontMetrics {
        public float top, ascent, descent, bottom, leading;
    }
    /** Integer-valued counterpart to FontMetrics. Cocos2dx's bitmap
     *  text uses FontMetricsInt directly. */
    public static class FontMetricsInt {
        public int top, ascent, descent, bottom, leading;

        /** AOSP Paint.FontMetricsInt.set(FontMetricsInt) (Paint.java:2604). */
        public void set(FontMetricsInt fontMetricsInt) {
            top = fontMetricsInt.top;
            ascent = fontMetricsInt.ascent;
            descent = fontMetricsInt.descent;
            bottom = fontMetricsInt.bottom;
            leading = fontMetricsInt.leading;
        }

        /** AOSP Paint.FontMetricsInt.set(FontMetrics) (Paint.java:2617). The
         *  rounding is NOT uniform and AOSP's own comment says it matches
         *  GraphicsJNI::set_metrics_int: top FLOORS, bottom CEILS, the other
         *  three ROUND. This is the same rule getFontMetricsInt() follows. */
        public void set(FontMetrics fontMetrics) {
            top = (int) Math.floor(fontMetrics.top);
            ascent = Math.round(fontMetrics.ascent);
            descent = Math.round(fontMetrics.descent);
            bottom = (int) Math.ceil(fontMetrics.bottom);
            leading = Math.round(fontMetrics.leading);
        }
    }
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    private Cap strokeCap = Cap.BUTT;
    private Join strokeJoin = Join.MITER;
    private float strokeMiter = 4.0f;
    public Cap getStrokeCap()    { return strokeCap; }
    public void setStrokeCap(Cap c) { if (c != null) strokeCap = c; }
    public Join getStrokeJoin()  { return strokeJoin; }
    public void setStrokeJoin(Join j) { if (j != null) strokeJoin = j; }
    public float getStrokeMiter() { return strokeMiter; }
    public void  setStrokeMiter(float m) { strokeMiter = m; }

    public static final int ANTI_ALIAS_FLAG    = 0x01;
    public static final int FILTER_BITMAP_FLAG = 0x02;
    public static final int DITHER_FLAG        = 0x04;
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    public static final int LINEAR_TEXT_FLAG   = 0x40;
    public static final int SUBPIXEL_TEXT_FLAG = 0x80;
    public static final int DEV_KERN_TEXT_FLAG = 0x100;
    public static final int EMBEDDED_BITMAP_TEXT_FLAG = 0x400;
    // ⚠ CORRECTED v1.0.929. This used to claim "AOSP: new Paint() sets
    // ANTI_ALIAS_FLAG ... isAntiAlias()==true". That is FALSE, and the line
    // below it (reset(), which correctly says "no ANTI_ALIAS") contradicted it.
    // Fetched from aosp-mirror android11-release graphics/java/android/graphics/
    // Paint.java:
    //     public Paint() { this(0); }
    //     public Paint(int flags) { ...; setFlags(flags | HIDDEN_DEFAULT_PAINT_FLAGS); ... }
    //     static final int HIDDEN_DEFAULT_PAINT_FLAGS =
    //             DEV_KERN_TEXT_FLAG | EMBEDDED_BITMAP_TEXT_FLAG | FILTER_BITMAP_FLAG;
    // ANTI_ALIAS_FLAG (0x01) is NOT among the hidden defaults, so a fresh
    // `new Paint()` has isAntiAlias() == FALSE on a device. Caught by the
    // answer-differential: RAS.sc.paint.defaults, reference `aa=0`, ours `aa=1`.
    // Apps that want smooth edges pass the flag explicitly -- AOSP's own TextView
    // does exactly that (`new TextPaint(Paint.ANTI_ALIAS_FLAG)`), which is why
    // text is unaffected by this correction.
    static final int HIDDEN_DEFAULT_PAINT_FLAGS =
            DEV_KERN_TEXT_FLAG | EMBEDDED_BITMAP_TEXT_FLAG | FILTER_BITMAP_FLAG;

    private int color = 0xFF000000;
    private float strokeWidth;
    private float textSize = 12f;
    private Style style = Style.FILL;
    private Align align = Align.LEFT;
    private boolean antiAlias;
    // v1.0.124: AOSP Paint stores a bitmask of all flags (ANTI_ALIAS_FLAG,
    // FILTER_BITMAP_FLAG, ...) as a single int. getFlags returns the
    // composite mask; individual setters update both the bool field
    // (for accessor convenience) and the mask bit. Compose UI's
    // TextPaintExtensions_androidKt.setTextMotion reads getFlags
    // during measure -- without it, every Compose-rendered text view
    // throws NoSuchMethodError mid-RV-dispatch.
    //
    // Per cs.android.com/.../graphics/Paint.java getFlags returns the
    // composite. Default flags: EMBEDDED_BITMAP_TEXT_FLAG only on
    // some API levels; we default to 0 (no flags set) which is
    // safe for all rendering paths.
    private int flagsMask = 0;

    // v1.0.929: `this(0)`, matching AOSP. See HIDDEN_DEFAULT_PAINT_FLAGS above.
    // MACAPK_PAINT_AA_DEFAULT=1 restores the old antialias-by-default as a
    // labelled bisect gate, because this changes the edge treatment of every
    // shape an app draws with a bare `new Paint()`.
    public Paint() { this("1".equals(ENV_MACAPK_PAINT_AA_DEFAULT)
                            ? ANTI_ALIAS_FLAG : 0); }
    public Paint(int flags) {
        flagsMask = flags | HIDDEN_DEFAULT_PAINT_FLAGS;
        antiAlias = (flagsMask & ANTI_ALIAS_FLAG) != 0;
    }
    public Paint(Paint p) { if (p != null) set(p); }

    /** AOSP Paint.set(Paint) is `nSet(...)` (a FULL SkPaint assignment) plus
     *  `setClassVariablesFrom(src)`, which copies every Java-side field --
     *  mColor, mColorFilter, mMaskFilter, mPathEffect, mShader, mTypeface,
     *  mXfermode, the shadow-layer quad, locales, font feature/variation
     *  settings. The copy ctor `Paint(Paint)` routes through the same helper.
     *
     *  ⚠ THIS COPIED 7 OF 22 FIELDS and silently dropped the rest -- shader,
     *  colour filter, xfermode/blend mode, typeface, path effect, mask filter,
     *  stroke cap/join/miter, the shadow layer, letter spacing, text skew/scale.
     *  So a cloned Paint lost its gradient (flat fill of the base colour), its
     *  tint (untinted icon), and its xfermode (a CLEAR/DST_IN mask silently
     *  reverting to SRC_OVER -- the shape of the v395 black-square and v500
     *  Lottie-matte bugs).
     *  Blast radius when found: `Paint(Paint)` is used by 41 of 62 corpus apps
     *  (102 classes) and `set(Paint)` by 26 (32 classes), and the dropped
     *  fields are the most-set ones in the fleet -- setShader 45 apps/644 refs,
     *  setColorFilter 46/567, setXfermode 41/435, setTypeface 40/386. */
    public void set(Paint p) {
        if (p == null || p == this) return;
        color = p.color; strokeWidth = p.strokeWidth; textSize = p.textSize;
        style = p.style; align = p.align; antiAlias = p.antiAlias;
        flagsMask = p.flagsMask;
        strokeCap = p.strokeCap; strokeJoin = p.strokeJoin; strokeMiter = p.strokeMiter;
        textSkewX = p.textSkewX; textScaleX_ = p.textScaleX_;
        letterSpacing = p.letterSpacing; wordSpacing = p.wordSpacing;
        fontFeatureSettings = p.fontFeatureSettings;
        mFontVariationSettings = p.mFontVariationSettings;
        mColorFilter = p.mColorFilter; mShader = p.mShader;
        mPathEffect = p.mPathEffect; mMaskFilter = p.mMaskFilter;
        mTypeface = p.mTypeface; mXfermode = p.mXfermode; mBlendMode = p.mBlendMode;
        shadowRadius = p.shadowRadius; shadowDx = p.shadowDx;
        shadowDy = p.shadowDy; shadowColor = p.shadowColor;
        hinting = p.hinting; elegantTextHeight = p.elegantTextHeight;
        startHyphenEdit = p.startHyphenEdit; endHyphenEdit = p.endHyphenEdit;
        textLocale = p.textLocale;
    }
    /** AOSP Paint.reset() is `nReset` (native: style FILL, align LEFT, stroke
     *  cap/join/miter, textSize, scaleX/skewX) PLUS explicit nulling of
     *  mColorFilter, mMaskFilter, mPathEffect, mShader, mTypeface, mXfermode,
     *  the font feature/variation settings and the whole shadow-layer quad.
     *
     *  ⚠ THIS RESET 5 FIELDS and left every object field STALE -- the exact
     *  mirror of the set(Paint) bug. A POOLED paint (the common idiom: keep one
     *  Paint, reset() it between draws) kept the previous draw's gradient or
     *  tint, so the next primitive filled with the wrong thing -- or kept a
     *  stale CLEAR xfermode and drew NOTHING. It did not even reset `style` or
     *  `align`, so a stroke-only paint stayed stroke-only forever.
     *  reset() is used by 28 of 62 corpus apps (53 classes); the union with the
     *  set/copy-ctor paths above is 43 of 62. */
    public void reset() {
        color = 0xFF000000; strokeWidth = 0; textSize = 12f;
        // AOSP reset() sets flags to the hidden defaults (no ANTI_ALIAS).
        flagsMask = HIDDEN_DEFAULT_PAINT_FLAGS; antiAlias = false;
        style = Style.FILL; align = Align.LEFT;
        strokeCap = Cap.BUTT; strokeJoin = Join.MITER; strokeMiter = 4.0f;
        textSkewX = 0f; textScaleX_ = 1f;
        letterSpacing = 0f; wordSpacing = 0f;
        fontFeatureSettings = null; mFontVariationSettings = null;
        mColorFilter = null; mShader = null; mPathEffect = null;
        mMaskFilter = null; mTypeface = null; mXfermode = null; mBlendMode = null;
        shadowRadius = 0f; shadowDx = 0f; shadowDy = 0f; shadowColor = 0;
        hinting = HINTING_ON; elegantTextHeight = false;
        startHyphenEdit = START_HYPHEN_EDIT_NO_EDIT;
        endHyphenEdit   = END_HYPHEN_EDIT_NO_EDIT;
        textLocale = java.util.Locale.getDefault();
    }

    /** v1.0.124: AOSP `Paint.getFlags()` returns the composite bitmask
     *  of all set flags. Compose UI's text rendering chain reads this
     *  during measure. */
    public int getFlags() { return flagsMask; }
    public void setFlags(int flags) {
        flagsMask = flags;
        antiAlias = (flags & ANTI_ALIAS_FLAG) != 0;
    }
    /** AOSP companion: get/set individual flag bits. */
    public boolean isUnderlineText()    { return (flagsMask & UNDERLINE_TEXT_FLAG) != 0; }
    public void setUnderlineText(boolean v) {
        flagsMask = v ? (flagsMask | UNDERLINE_TEXT_FLAG) : (flagsMask & ~UNDERLINE_TEXT_FLAG);
    }
    public boolean isStrikeThruText()   { return (flagsMask & STRIKE_THRU_TEXT_FLAG) != 0; }
    public void setStrikeThruText(boolean v) {
        flagsMask = v ? (flagsMask | STRIKE_THRU_TEXT_FLAG) : (flagsMask & ~STRIKE_THRU_TEXT_FLAG);
    }
    public boolean isFakeBoldText()     { return (flagsMask & FAKE_BOLD_TEXT_FLAG) != 0; }
    public void setFakeBoldText(boolean v) {
        flagsMask = v ? (flagsMask | FAKE_BOLD_TEXT_FLAG) : (flagsMask & ~FAKE_BOLD_TEXT_FLAG);
    }
    public boolean isLinearText()       { return (flagsMask & LINEAR_TEXT_FLAG) != 0; }
    public void setLinearText(boolean v) {
        flagsMask = v ? (flagsMask | LINEAR_TEXT_FLAG) : (flagsMask & ~LINEAR_TEXT_FLAG);
    }
    public boolean isSubpixelText()     { return (flagsMask & SUBPIXEL_TEXT_FLAG) != 0; }
    public void setSubpixelText(boolean v) {
        flagsMask = v ? (flagsMask | SUBPIXEL_TEXT_FLAG) : (flagsMask & ~SUBPIXEL_TEXT_FLAG);
    }
    public boolean isFilterBitmap()     { return (flagsMask & FILTER_BITMAP_FLAG) != 0; }
    public void setFilterBitmap(boolean v) {
        flagsMask = v ? (flagsMask | FILTER_BITMAP_FLAG) : (flagsMask & ~FILTER_BITMAP_FLAG);
    }
    public boolean isDither()           { return (flagsMask & DITHER_FLAG) != 0; }
    public void setDither(boolean v) {
        flagsMask = v ? (flagsMask | DITHER_FLAG) : (flagsMask & ~DITHER_FLAG);
    }

    private static final boolean TRACE_COMPOSE = "1".equals(System.getenv("MACAPK_TRACE_COMPOSE"));
    private static int sComposeColorLogs = 0;
    private static void traceComposeColor(String how, int val) {
        if (!TRACE_COMPOSE || sComposeColorLogs >= 25) return;
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        boolean fromCompose = false;
        for (int i = 2; i < st.length; i++) {
            if (st[i].getClassName().contains("compose")) { fromCompose = true; break; }
        }
        if (!fromCompose) return;
        sComposeColorLogs++;
        StringBuilder sb = new StringBuilder("paint." + how + "(0x" + Integer.toHexString(val) + ") from Compose:");
        for (int i = 2; i < Math.min(st.length, 10); i++) sb.append("\n    ").append(st[i]);
        com.macapk.loader.NativeLog.i("Mac.apk.ComposeColor", sb.toString());
    }
    public int  getColor() { return color; }
    public void setColor(int c) { color = c; traceComposeColor("setColor", c); }
    public int  getAlpha() { return (color >>> 24) & 0xff; }
    public void setAlpha(int a) { color = (a << 24) | (color & 0xffffff); traceComposeColor("setAlpha", color); }
    /** Set color from ARGB components. AOSP Paint.setARGB packs the
     *  four bytes into a single ARGB int. Frozen Bubble's ScoreView
     *  + many custom widget paints chain through this rather than
     *  via setColor + hex literal. */
    public void setARGB(int a, int r, int g, int b) {
        color = ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
    }
    public float getStrokeWidth() { return strokeWidth; }
    public void  setStrokeWidth(float w) { strokeWidth = w; }
    public float getTextSize() { return textSize; }
    public void  setTextSize(float s) { textSize = s; }
    // v1.0.97: text-shaping accessors used by Material's typography
    // chain (italic + condensed/expanded fonts). We don't apply skew
    // or scale to drawn glyphs; just store + report for round-trip.
    private float textSkewX = 0f;
    private float textScaleX_ = 1f;
    public float getTextSkewX() { return textSkewX; }
    public void  setTextSkewX(float v) { textSkewX = v; }
    public float getTextScaleX() { return textScaleX_; }
    public void  setTextScaleX(float v) { textScaleX_ = v; }
    // AOSP Paint.setLetterSpacing javadoc verbatim: "The value is in 'EM'
    // units. Typical values for slight expansion will be around 0.05.
    // Negative values tighten text." This used to discard the value
    // entirely (no backing field existed anywhere in the class); now it's
    // real storage AND is applied as extra advance between adjacent
    // characters inside our own measureText/getTextWidths/breakText/
    // getRunAdvance/getOffsetForAdvance below (n-1 gaps for n characters,
    // matching the EM-per-gap contract). Canvas.drawText's glyph
    // placement does not yet consult this field (Canvas.java derives its
    // own AWT Font independently of Paint's measurement helpers), so
    // letter-spaced text now measures/wraps correctly but still draws
    // without the extra visual gap until that file's draw path is updated
    // to match.
    private float letterSpacing = 0f;
    public float getLetterSpacing() { return letterSpacing; }
    public void  setLetterSpacing(float v) { letterSpacing = v; }
    // AOSP Paint.setFontFeatureSettings: CSS font-feature-settings syntax
    // (e.g. "tnum" 1 for tabular figures), fed to native shaping. We have
    // no shaper to apply it to (same "store only" limitation as
    // MaskFilter/PathEffect/Xfermode further down this file), but
    // round-tripping the caller's actual value -- instead of always
    // discarding it and returning null -- is a real, honest fix on its
    // own for any code that reads the value back. AOSP normalizes an
    // empty string to null (a "" settings string means "no features",
    // same as never having set one); verified against the AOSP source.
    private String fontFeatureSettings;
    public String getFontFeatureSettings() { return fontFeatureSettings; }
    public void   setFontFeatureSettings(String s) {
        fontFeatureSettings = (s != null && s.isEmpty()) ? null : s;
    }
    // (isFakeBoldText / setFakeBoldText already defined later in the file)
    public Style getStyle() { return style; }
    public void  setStyle(Style s) { style = s; }
    public Align getTextAlign() { return align; }
    public void  setTextAlign(Align a) { align = a; }
    public boolean isAntiAlias() { return antiAlias; }
    public void setAntiAlias(boolean aa) {
        antiAlias = aa;
        // v1.0.124: keep the composite flags mask in sync so getFlags()
        // returns the right value.
        flagsMask = aa ? (flagsMask | ANTI_ALIAS_FLAG) : (flagsMask & ~ANTI_ALIAS_FLAG);
    }

    // v1.0.132: real text measurement via AWT FontMetrics. The old
    // `text.length() * textSize * 0.5f` approximation under-counted by
    // ~15% for proportional fonts: real average char width is closer
    // to 0.55-0.6 of textSize, and individual characters vary widely
    // (i=0.25, m=0.85, capital W=0.95). The under-count made every
    // wrap_content TextView size to a width SHORTER than the actual
    // rendered text needed -- so onDraw's ellipsize check kicked in
    // and titles like "geteduroam" rendered as "geted..." inside a
    // 720-wide card that had plenty of space.
    //
    // Java's Graphics2D.FontMetrics provides per-glyph width via
    // stringWidth(). One Font instance per textSize is cached.
    // Generic for every TextView measure / draw call across every
    // APK -- AOSP TextView ultimately also delegates to a native
    // text-shaping engine; this is the equivalent in our render path.
    // ⚠ resolveFontMetrics used to re-derive the font/style by hand, as did
    // resolveFont and Canvas.deriveFont; a comment here argued the duplication
    // was worth it to keep a (size,style)-keyed FontMetrics fast path. That
    // stopped being true once a Typeface can name a real FAMILY: an int key of
    // (size,style) cannot distinguish Roboto-Medium from Roboto-Regular, so
    // keeping it would have served the wrong face to whichever paint asked
    // second. All three now go through MacApkFont.select, and the caching moves
    // to where it can be correct -- getFrom keys by base-font identity, and
    // metricsOf keys by the resolved Font instance -- so the hot path still
    // does no allocation after the first call per (face, size, style).
    private java.awt.FontMetrics resolveFontMetrics() {
        java.awt.Font f = resolveFont();
        return f == null ? null : MacApkFont.metricsOf(f);
    }
    /** The java.awt.Font this Paint measures and draws with: family face
     *  selected by the typeface's resolved (weight, slant), synthetic styling
     *  applied only where AOSP applies it. */
    private java.awt.Font resolveFont() {
        int sz = Math.max(1, (int) Math.round(textSize));
        try {
            return MacApkFont.sized(
                MacApkFont.select(mTypeface, isFakeBoldText(), textSkewX != 0f), sz);
        } catch (Throwable t) { return null; }
    }
    /** Public door to resolveFont(), for TextView's multi-line layout path.
     *  TextView's onMeasure/onDraw hand-roll their own line geometry rather
     *  than consulting Layout/StaticLayout (see TextView's class header), so
     *  they need to ask "does line k need a fallback font" themselves --
     *  AOSP's useLineSpacingFromFallbacks, StaticLayout.java's out() caller
     *  -- without re-deriving typeface selection. MacApkFont.select/sized
     *  already does that resolution once, here; this is the one place it is
     *  exposed outside this class. */
    public java.awt.Font macapkResolvedFont() { return resolveFont(); }
    /** The RAW advance of `text` -- the number AOSP's `nGetTextAdvances`
     *  returns, BEFORE the `(float) Math.ceil(...)` that only measureText
     *  applies. Every advance-producing API in this class funnels through
     *  here, which is the structural guarantee AOSP gets for free: all three
     *  measureText overloads reach one nGetTextAdvances
     *  (Paint.java:2179 / :2211 / :2265 at android-11.0.0_r48), as do
     *  getTextWidths (:2424), breakText (:2299) and getRunAdvance (:2929).
     *
     *  Before v1.0.923 the String path did per-codepoint font fallback plus
     *  default-ignorable zeroing while the char[] path called
     *  `FontMetrics.charsWidth` directly and skipped both, so the two
     *  overloads answered DIFFERENT widths for the same characters -- 8 of
     *  the fwprobe TXT.overload cases (cjk / emoji / hebrew / mixed, base and
     *  bold). An app that happened to call the char[] overload measured
     *  something the String overload would have measured differently, which
     *  is invisible until it wraps wrong.
     *
     *  getTextWidths / breakText / getRunAdvance deliberately consume the RAW
     *  value: AOSP's native paths write un-ceiled advances into their out
     *  params, which is why `sum(getTextWidths) == measureText` is a
     *  one-pixel band in AOSP rather than an equality. Ceiling them here
     *  would push their sums a whole pixel PER CHARACTER above measureText. */
    private float macapkRawAdvance(String text) {
        if (text == null || text.isEmpty()) return 0f;
        // A zero (or negative) text size makes every advance zero on AOSP --
        // minikin scales the glyph advances by the size and has no minimum.
        // resolveFontMetrics()/resolveFont() clamp the AWT size to >= 1 because
        // java.awt.Font rejects 0, so that clamp has to be undone HERE or a
        // 0-size paint measures at 1px (fwprobe TXT.zero.size0_measureText).
        if (textSize <= 0f) return 0f;
        java.awt.FontMetrics fm = resolveFontMetrics();
        float base;
        // ⚠ v1.0.938: textScaleX scales the FONT, before quantization -- not
        // the finished sum after it. minikin hands scaleX to SkFont::setScaleX,
        // so Skia scales each glyph and THEN rounds it to a whole pixel;
        // scaling an already-rounded total instead multiplies the rounding
        // error by scaleX. Measured on the two TXTM scaleX rows: the reference
        // ratios are 1.9984 and 1.9794 while ours were a suspiciously exact
        // 2.0000 -- which is the tell that we were scaling a quantized number.
        // `scaled` is the font every advance below is measured with; the
        // trailing `base *= textScaleX_` is gone with it.
        java.awt.Font scaled = (fm == null) ? null : fm.getFont();
        if (scaled != null && textScaleX_ != 1f) {
            scaled = scaled.deriveFont(java.awt.geom.AffineTransform
                        .getScaleInstance(textScaleX_, 1.0));
        }
        if (fm == null) {
            base = text.length() * textSize * 0.55f;  // fallback
        } else if (MacApkFont.firstSpecial(text, fm.getFont()) < 0
                   && !Float.isNaN(MacApkFont.quantizedWidth(scaled, text,
                                                             MacApkFont.ligaFor(letterSpacing)))) {
            // ⚠ v1.0.937: sum of INTEGER-QUANTIZED per-glyph advances, which is
            // what Skia produces under Android's default hinting-on /
            // subpixel-off Paint. Measured 8/9 against the golden where the
            // whole-string float sum below scored 4/9 -- see
            // MacApkFont.GLYPH_QUANTIZE for the table and the one case it
            // trades away. The draw path steps by the SAME GlyphVector.
            base = MacApkFont.quantizedWidth(scaled, text, MacApkFont.ligaFor(letterSpacing));
        } else if (MacApkFont.firstSpecial(text, fm.getFont()) < 0) {
            // ⚠ v1.0.932: getStringBounds, NOT stringWidth. `FontMetrics.stringWidth`
            // sums INTEGER per-character advances, so it accumulates up to half a
            // pixel of rounding error per character -- on a 44-char sentence that
            // is ~19px. Measured against the checked-in emulator golden:
            //   TXTM.adv.base.sentence  golden 642, ours 623, AWT float advance
            //     for the SAME string in the SAME font 641.172 -> ceil 642, EXACT.
            //   longword 670 -> 701 (golden 704), word 92 -> 96 (golden 97).
            // minikin sums float advances and rounds once at the end, which is
            // what getStringBounds does. Wrap points depend on this, so measure
            // drifting from the reference drifts the whole line-break layout.
            base = (float) scaled
                .getStringBounds(text, fm.getFontRenderContext()).getWidth();
        } else {
            // Per-codepoint font fallback: measure each run with the font
            // Canvas.drawText will actually draw it with. Measure and draw MUST
            // agree or the wrap count drifts from the emulator, so this mirrors
            // macapkDrawStringRuns exactly, including the zero advance HarfBuzz
            // gives a default-ignorable.
            base = macapkMeasureRuns(text, scaled);
        }
        // ⚠ CORRECTED v1.0.932. This used to add `(codePointCount - 1)` gaps.
        // AOSP adds ONE GAP PER GRAPHEME CLUSTER -- n of them, not n-1 -- because
        // minikin appends the letter-space AFTER each cluster's advance rather
        // than between clusters. PROVEN against the checked-in golden by
        // differencing TXTM.adv.base.* and TXTM.adv.ls010.* (32px, ls=0.1 ->
        // 3.2px/gap) and dividing:
        //     string          AOSP gaps   ours (old)
        //     "A"                  1          0
        //     "Sphinx" (6 ch)      6          5
        //     sentence (44 ch)    44         43
        //     5 spaces             5          4
        //     emoji (1 cluster)    1          0      <- was zero spacing entirely
        //     e + U+0301           1          1
        //     hebrew / cjk (4)     4          3
        // Two separate errors: the off-by-one, and CLUSTERS vs CODEPOINTS (an
        // emoji is one cluster of two codepoints; a base+combining pair is one
        // cluster of two codepoints too -- codePointCount got that second case
        // right by accident and the first case wrong).
        //
        // v1.0.2239: on the single-font path the gaps are counted on the SHAPED
        // run (MacApkFont.spacingClusterCount), because minikin spaces HarfBuzz
        // output clusters and a ligature is one of them -- Material Symbols'
        // "home" is one rlig glyph and takes one gap, not four. Without a
        // ligature the count equals the grapheme count above.
        int gaps = 0;
        if (letterSpacing != 0f && !text.isEmpty()) {
            gaps = -1;
            if (fm != null && MacApkFont.firstSpecial(text, fm.getFont()) < 0) {
                gaps = MacApkFont.spacingClusterCount(scaled, text,
                                                      MacApkFont.ligaFor(letterSpacing));
            }
            if (gaps < 0) gaps = macapkClusterCount(text);
            base += gaps * letterSpacing * textSize;
        }
        // The letter-spacing term IS still scaled here: minikin computes
        // `letterSpace = letterSpacing * size * scaleX`, so the gap scales even
        // though the glyph advances were already scaled by the font transform
        // above. Scaling `base` as a whole would double-scale them.
        if (textScaleX_ != 1f && letterSpacing != 0f && !text.isEmpty()) {
            base += gaps * letterSpacing * textSize
                    * (textScaleX_ - 1f);
        }
        return base;
    }

    /** Per-char advances in the shape AOSP's nGetTextAdvances produces and
     *  nBreakText consumes: each grapheme cluster's whole advance --
     *  including exactly ONE letter-space, because minikin adds
     *  letterSpaceHalf on each side of every cluster (LayoutCore.cpp,
     *  LayoutPiece ctor, android-11.0.0_r48: `mAdvances[clusterBaseIndex]
     *  += letterSpaceHalf` at the run edges and both halves at each
     *  internal cluster boundary) -- attributed to the cluster's LEADING
     *  code unit,
     *  every other unit of the cluster reading 0. AOSP's breakText builds
     *  this array once via MinikinUtils::measureText and accumulates it
     *  (libs/hwui/jni/Paint.cpp breakText), and getTextWidths returns the
     *  same array, which is the structural guarantee that their sums sit in
     *  the one-ceil band below measureText (which ceils the SAME sum).
     *
     *  Deliberately built on macapkRawAdvance per cluster: a single-cluster
     *  slice already carries its own one gap (clusterCount == 1), so no
     *  caller may add a separate per-character gap on top -- that is the
     *  double count that broke TXT.break.ls010.sentence.fullWidthBand at
     *  v1.0.932 (the walkers added an explicit inter-char gap AND inherited
     *  the embedded per-cluster gap, accumulating 2n-1 gaps against
     *  measureText's n). */
    /** Per-cluster advances read out of the SAME laid-out vector measureText
     *  uses, so `sum(getTextWidths) == measureText` holds BY CONSTRUCTION.
     *
     *  ⚠ WHY THIS EXISTS (v1.0.1127). macapkCharAdvances measures each cluster
     *  INDEPENDENTLY -- `macapkRawAdvance(text.substring(a,b))` -- which by
     *  construction can never observe a kern PAIR, since a pair spans two
     *  clusters. The moment measureText started applying GPOS kerning, the two
     *  APIs disagreed: the pangram measured 642 while its per-character widths
     *  still summed to 643. That is two sources of truth for one question, which
     *  is a worse defect than the missing kerning was -- an app that lays out
     *  from getTextWidths and hit-tests from measureText drifts a pixel per
     *  kern pair. It cost 4 fwprobe rows (TXT.break.*.fullWidthBand,
     *  TXT.widths.sentence.sumBand) and they are the reason this method exists.
     *
     *  AOSP gets the same guarantee structurally: minikin fills mAdvances[] from
     *  the same HarfBuzz pass that produces the width, attributing a cluster's
     *  whole advance to its LEADING unit and 0 to the trailing units
     *  (LayoutCore.cpp; javadoc at Paint.java:2542). Reading position DELTAS out
     *  of the quantized vector reproduces exactly that, kern included, because
     *  quantize() already folded the GPOS adjustment into the positions.
     *
     *  Returns null when the string does not take the quantized path (font
     *  fallback runs, no metrics, scaleX-derived face unavailable); the caller
     *  then keeps the per-cluster walk. */
    private float[] macapkKernedClusterAdvances(String text) {
        try {
            if (textSize <= 0f) return null;
            java.awt.FontMetrics fm = resolveFontMetrics();
            if (fm == null) return null;
            if (MacApkFont.firstSpecial(text, fm.getFont()) >= 0) return null;
            java.awt.Font scaled = fm.getFont();
            if (scaled != null && textScaleX_ != 1f) {
                scaled = scaled.deriveFont(java.awt.geom.AffineTransform
                            .getScaleInstance(textScaleX_, 1.0));
            }
            MacApkFont.Quantized q = MacApkFont.quantize(scaled, text,
                                                         MacApkFont.ligaFor(letterSpacing));
            if (q == null) return null;
            final int n = text.length();
            final float[] perChar = new float[n];
            final int ng = q.glyphs.getNumGlyphs();
            for (int g = 0; g < ng; g++) {
                int ci = q.glyphs.getGlyphCharIndex(g);
                if (ci < 0 || ci >= n) continue;
                float a = (float) (q.glyphs.getGlyphPosition(g + 1).getX()
                                   - q.glyphs.getGlyphPosition(g).getX());
                perChar[ci] += a;
            }
            // Which graphemes begin a letter-spacing cluster: those holding a
            // glyph of their own. A grapheme a ligature swallowed has none, so it
            // takes no gap -- the same rule measureText counts by
            // (MacApkFont.spacingClusterCount), or the two would disagree by a
            // gap per swallowed character.
            final boolean[] ownsGlyph = new boolean[n];
            final boolean shapedGaps = MacApkFont.SHAPED_SPACING;
            for (int g = 0; g < ng; g++) {
                int ci = q.glyphs.getGlyphCharIndex(g);
                if (ci >= 0 && ci < n) ownsGlyph[ci] = true;
            }
            // Fold each cluster's chars onto its BASE index, AOSP's attribution.
            final float[] out = new float[n];
            java.text.BreakIterator it =
                java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
            it.setText(text);
            int a0 = it.first();
            for (int b = it.next(); b != java.text.BreakIterator.DONE;
                    a0 = b, b = it.next()) {
                float sum = 0f;
                for (int k = a0; k < b && k < n; k++) sum += perChar[k];
                // One letter-space gap per CLUSTER, matching the n-not-n-1 rule
                // macapkRawAdvance documents above.
                boolean starts = !shapedGaps;
                for (int k = a0; !starts && k < b && k < n; k++) starts = ownsGlyph[k];
                if (letterSpacing != 0f && starts) sum += letterSpacing * textSize;
                out[a0] = sum;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private float[] macapkCharAdvances(String text) {
        final int n = text.length();
        final float[] kerned = macapkKernedClusterAdvances(text);
        if (kerned != null) return kerned;
        final float[] out = new float[n];
        try {
            java.text.BreakIterator it =
                java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
            it.setText(text);
            int a = it.first();
            for (int b = it.next(); b != java.text.BreakIterator.DONE;
                    a = b, b = it.next()) {
                out[a] = macapkRawAdvance(text.substring(a, b));
            }
        } catch (Throwable t) {
            // Same degraded unit macapkClusterCount falls back to: codepoints.
            int i = 0;
            while (i < n) {
                int cc = Character.charCount(text.codePointAt(i));
                out[i] = macapkRawAdvance(text.substring(i, i + cc));
                i += cc;
            }
        }
        return out;
    }

    /** AOSP measureText ends in `(float) Math.ceil(...)` on EVERY path
     *  (Paint.java:2179, :2188, :2211, :2219), so a measureText result is
     *  always a whole number. Our letterSpacing term used to be added AFTER
     *  AWT's already-integral width and never rounded, so a letter-spaced
     *  measurement came back fractional (fwprobe TXT.ceil.ls010.*). */
    private float macapkMeasure(String text) {
        return (float) Math.ceil(macapkRawAdvance(text));
    }

    public float measureText(String text) {
        // AOSP Paint.java:2229 -- IAE on null, then straight delegation.
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        return measureText(text, 0, text.length());
    }
    /** Slow path of measureText for strings the primary font does not fully
     *  cover: the sum of per-run advances, each run measured with its resolved
     *  chain font, mirroring Canvas.macapkDrawStringRuns exactly. */
    static float macapkMeasureRuns(String text, java.awt.Font primary) {
        final int n = text.length();
        float w = 0f;
        int i = 0;
        final float sizePx = (primary != null) ? primary.getSize2D() : 0f;
        while (i < n) {
            // An emoji CLUSTER is one advance, whatever it is made of: a ZWJ
            // family and a two-codepoint flag are single clusters. Charged from
            // NotoColorEmoji's own metric rather than from whatever face the
            // fallback chain happens to pick, which was answering exactly 1.0 em.
            // The draw side steps by the SAME value (Canvas.macapkDrawStringRuns)
            // or measure and draw disagree, which is the defect class this file
            // has already been bitten by twice.
            int ee = MacApkFont.emojiClusterEnd(text, i, n);
            if (ee > i) {
                w += MacApkFont.emojiAdvance(sizePx);
                i = ee;
                continue;
            }
            int cp = text.codePointAt(i);
            // HarfBuzz zero-advances default ignorables
            // (hb_ot_zero_width_default_ignorables), so they contribute nothing
            // to the measured width, matching the draw-side skip.
            if (MacApkFont.isDefaultIgnorable(cp)) { i += Character.charCount(cp); continue; }
            java.awt.Font base = (cp < 0x20) ? null : MacApkFont.fallbackBaseFor(cp, primary);
            int runStart = i;
            i += Character.charCount(cp);
            while (i < n) {
                int cp2 = text.codePointAt(i);
                if (MacApkFont.isDefaultIgnorable(cp2)) break;
                java.awt.Font b2 = (cp2 < 0x20) ? null : MacApkFont.fallbackBaseFor(cp2, primary);
                if (b2 != base) break;
                i += Character.charCount(cp2);
            }
            String run = text.substring(runStart, i);
            java.awt.FontMetrics rm = MacApkFont.metricsOf(
                base == null ? primary : MacApkFont.atSizeOf(base, primary));
            // Same float-advance rule as the fast path above (v1.0.932): summing
            // INTEGER per-run advances re-introduces exactly the rounding this
            // fix removes, and the fallback path is the one the probe's
            // non-Latin strings take.
            if (rm != null) {
                // Same quantized model as the fast path -- a mixed-font string
                // must not measure by a different rule than a single-font one.
                // liga OFF: Canvas.macapkDrawStringRuns paints these runs with
                // Graphics2D.drawString, which forms no optional ligature, and
                // measure must charge what draw paints.
                float qw = MacApkFont.quantizedWidth(rm.getFont(), run, false);
                w += Float.isNaN(qw)
                   ? (float) rm.getFont().getStringBounds(run, rm.getFontRenderContext()).getWidth()
                   : qw;
            }
        }
        return w;
    }

    /** Grapheme-cluster boundaries: offsets 0 .. text.length(), so cluster k is
     *  `text.substring(out[k], out[k+1])` and there are `out.length - 1` of
     *  them.
     *
     *  Exists so the DRAW path can step cluster by cluster using the exact same
     *  UAX#29 segmentation the MEASURE path counts with. Re-deriving the
     *  clustering on the draw side -- by codepoint, say -- would make an emoji
     *  or a base+combining pair take two gaps where measureText charged one,
     *  and the two would silently disagree by a gap per cluster. */
    static int[] macapkClusterBounds(String text) {
        if (text == null || text.isEmpty()) return new int[] { 0 };
        java.util.ArrayList<Integer> b = new java.util.ArrayList<>();
        try {
            java.text.BreakIterator it =
                java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
            it.setText(text);
            for (int e = it.first(); e != java.text.BreakIterator.DONE; e = it.next()) {
                b.add(e);
            }
        } catch (Throwable t) {
            b.clear();
            // Same degraded unit macapkClusterCount falls back to: codepoints.
            for (int i = 0; i <= text.length(); ) {
                b.add(i);
                if (i == text.length()) break;
                i += Character.charCount(text.codePointAt(i));
            }
        }
        int[] out = new int[b.size()];
        for (int k = 0; k < out.length; k++) out[k] = b.get(k);
        return out;
    }

    /** The UNSPACED advance of `s` drawn in `primary` -- i.e. macapkRawAdvance's
     *  answer with the letter-spacing term left off, and with the same
     *  fast-path/fallback-run split so a cluster the primary cannot display
     *  still steps by the width of the face that will actually draw it.
     *
     *  The draw path steps by this. It must NOT call macapkRawAdvance for a
     *  single cluster, because that already embeds one full gap (clusterCount
     *  == 1) -- adding a gap on top of it is the same double count that broke
     *  the per-char walkers at v1.0.932. */
    static float macapkAdvanceIn(String s, java.awt.Font primary) {
        if (s == null || s.isEmpty() || primary == null) return 0f;
        if (MacApkFont.firstSpecial(s, primary) < 0) {
            // A single grapheme from the per-grapheme spaced draw, or a run that
            // draw paints with drawString: no optional ligature either way.
            float qw = MacApkFont.quantizedWidth(primary, s, false);
            if (!Float.isNaN(qw)) return qw;
            java.awt.FontMetrics fm = MacApkFont.metricsOf(primary);
            if (fm == null) return 0f;
            return (float) fm.getFont()
                .getStringBounds(s, fm.getFontRenderContext()).getWidth();
        }
        return macapkMeasureRuns(s, primary);
    }

    private static final ThreadLocal<java.text.BreakIterator> CLUSTER_ITER =
        ThreadLocal.withInitial(() -> java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT));

    /** True when UAX #29 makes every char of `text` its own grapheme cluster:
     *  no CR (CR LF is one cluster), surrogate, Hangul jamo, Prepend char,
     *  combining mark, SpacingMark or format char (ZWJ, ZWNJ, bidi controls). */
    private static boolean macapkTriviallyClustered(String text) {
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c < 0x0300) {
                if (c == 0x0D) return false;   // CR: CR LF is one cluster
                continue;
            }
            if (Character.isSurrogate(c)) return false;
            if ((c >= 0x1100 && c <= 0x11FF) || (c >= 0xA960 && c <= 0xA97F) || (c >= 0xD7B0 && c <= 0xD7FF)) return false;
            if ((c >= 0x0600 && c <= 0x0605) || c == 0x06DD || c == 0x070F || c == 0x08E2 || c == 0x0D4E) return false;
            // The two SpacingMarks whose general category is Lo, not Mc: Thai
            // SARA AM and Lao AM join the preceding letter's cluster.
            if (c == 0x0E33 || c == 0x0EB3) return false;
            switch (Character.getType(c)) {
                case Character.NON_SPACING_MARK:
                case Character.ENCLOSING_MARK:
                case Character.COMBINING_SPACING_MARK:
                case Character.FORMAT:
                    return false;
                default:
            }
        }
        return true;
    }

    /** Grapheme-cluster count, the unit AOSP's letter-spacing is applied per.
     *  `BreakIterator.getCharacterInstance` is the JDK's UAX#29 implementation,
     *  which is the same standard minikin follows. */
    static int macapkClusterCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Fast path: with no combining mark, format char, surrogate, CR, Hangul
        // jamo or prepend char, UAX #29 makes every char its own cluster. That is
        // nearly every UI string (plain Latin, Hebrew without niqqud), and it
        // skips building a BreakIterator, which showed in the UI-thread profile.
        if (macapkTriviallyClustered(text)) return text.length();
        try {
            java.text.BreakIterator it = CLUSTER_ITER.get();
            it.setText(text);
            int n = 0;
            for (int e = it.first(); e != java.text.BreakIterator.DONE
                    && (e = it.next()) != java.text.BreakIterator.DONE; ) n++;
            return n;
        } catch (Throwable t) {
            return text.codePointCount(0, text.length());
        }
    }

    /** AOSP Paint.getTextWidths family: per-character advance widths into
     *  `widths`, returning the number of JAVA CHARS measured (AOSP:2509
     *  `return end - start;`), NOT the number that happened to fit.
     *
     *  ⚠ AOSP throws AIOOBE when `widths` is shorter than the run
     *  (Paint.java:2500 / :2416); this used to write `min(len, widths.length)`
     *  entries and return that, handing the caller a plausible WRONG count
     *  with no error -- so a short array silently truncated the measurement
     *  instead of reporting programmer error. */
    public int getTextWidths(String text, float[] widths) {
        // AOSP Paint.java:2531 delegates using text.length(), which
        // dereferences a null text BEFORE any argument check -- so null is an
        // NPE on this overload and an IAE on every other one. Faithful.
        return getTextWidths(text, 0, text.length(), widths);
    }
    public int getTextWidths(String text, int start, int end, float[] widths) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end - start > widths.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (text.length() == 0 || start == end) return 0;
        return macapkTextWidths(text.substring(start, end), widths);
    }
    public int getTextWidths(CharSequence text, int start, int end, float[] widths) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end - start > widths.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (text.length() == 0 || start == end) return 0;
        return macapkTextWidths(text.subSequence(start, end).toString(), widths);
    }
    public int getTextWidths(char[] text, int index, int count, float[] widths) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((index | count) < 0 || index + count > text.length || count > widths.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (text.length == 0 || count == 0) return 0;
        return macapkTextWidths(new String(text, index, count), widths);
    }
    /** Shared body of the getTextWidths overloads: exactly the advances
     *  array macapkCharAdvances builds. AOSP attributes a multi-unit
     *  cluster's whole advance to its leading unit and 0 to the trailing
     *  units (javadoc at Paint.java:2542; minikin writes
     *  mAdvances[clusterBaseIndex]); iterating GRAPHEME CLUSTERS gives a
     *  base+combining-mark pair that attribution too, where the old
     *  per-codepoint walk measured the lone combining mark in isolation.
     *  True shaping awareness (ligatures, Arabic joining) inside a cluster
     *  still needs layoutGlyphVector, a larger change left as a follow-on.
     *
     *  The advances written are RAW (un-ceiled), like AOSP's, so their sum
     *  sits within one pixel BELOW measureText rather than a whole pixel per
     *  character above it. */
    private int macapkTextWidths(String text, float[] widths) {
        final float[] adv = macapkCharAdvances(text);
        for (int i = 0; i < adv.length; i++) widths[i] = adv[i];
        return adv.length;
    }
    public float measureText(char[] text, int index, int count) {
        // AOSP Paint.java:2168 -- IAE on null; :2171 -- AIOOBE when the run
        // overruns the array or either bound is negative.
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (text.length == 0 || count == 0) return 0f;
        return macapkMeasure(new String(text, index, count));
    }
    public float measureText(CharSequence text, int start, int end) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (text.length() == 0 || start == end) return 0f;
        return macapkMeasure(text.subSequence(start, end).toString());
    }
    public float measureText(String text, int start, int end) {
        // AOSP Paint.java:2203 -- the single validation expression
        // (start | end | (end - start) | (len - end)) < 0, which catches a
        // negative start, a reversed range and an end past the length in one
        // test and raises a plain IndexOutOfBoundsException. Letting
        // String.substring raise instead produced the SUBCLASS
        // StringIndexOutOfBoundsException, which is a different answer to a
        // caller that catches on the exact type.
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (text.length() == 0 || start == end) return 0f;
        return macapkMeasure(text.substring(start, end));
    }
    // AOSP getTextBounds javadoc verbatim: "the smallest rectangle that
    // encloses all of the characters, with an implied origin at (0,0)"
    // -- the tight glyph INK box, a different (usually smaller) thing
    // than the font's full line-box (what ascent()/descent() give) or
    // the character ADVANCE width (what measureText gives, which
    // includes side-bearing/spacing that layout needs but an ink box
    // doesn't). All 3 overloads used to hardcode the same disproven
    // top=-0.8*textSize/bottom=0.2*textSize line-box split that
    // ascent()/descent() moved away from (see that method's own comment
    // above), and the char[]/CharSequence overloads additionally used
    // `count * textSize * 0.5f` for width -- the same crude per-char
    // approximation measureText() itself replaced everywhere else. All 3
    // now share one real implementation via AWT's
    // Font.createGlyphVector(...).getVisualBounds(), which returns the
    // tight ink-outline bounds directly (BadgeDrawable-style dot
    // positioning is exactly this API's intended use). Java2D's
    // coordinate convention already matches AOSP's sign convention here
    // (y grows downward; ascending ink is negative-y from the baseline),
    // same as this file's own ascent()/descent().
    private void computeInkBounds(String text, android.graphics.Rect bounds) {
        bounds.left = 0; bounds.top = 0; bounds.right = 0; bounds.bottom = 0;
        if (text == null || text.isEmpty()) return;
        java.awt.Font f = resolveFont();
        if (f != null) {
            try {
                // ⚠ RUN-SPLIT, exactly as macapkMeasureRuns does.
                //
                // This used to call f.createGlyphVector(frc, text) on the ONE
                // resolved font. createGlyphVector performs NO font fallback, so
                // every character outside the primary face's cmap came back as
                // Roboto's .notdef box -- and the ink bounds of four tofu boxes
                // are not the ink bounds of the text. Measured by fwprobe on
                // "你好世界" @32: we answered right=56 where the device answers
                // 127, i.e. ~44% of the true width, and top/bottom were the
                // notdef box rather than the glyphs.
                //
                // Worse than the number: measureText DOES fall back (via
                // macapkMeasureRuns), so measure and ink DISAGREED ABOUT THE SAME
                // STRING -- the exact inconsistency that breaks the custom-widget
                // positioning getTextBounds exists for. Splitting here uses the
                // same run boundaries and the same quantized advances, so the two
                // cannot drift apart again.
                //
                // MacApkFont.textFrc() rather than a throwaway 1x1
                // BufferedImage's default context: the ink bounds of a
                // multi-character string accumulate the same per-glyph advances
                // measureText does, so a different metrics mode here would make
                // getTextBounds disagree with measureText about the same text.
                final int n = text.length();
                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                float penX = 0f;
                float lastGlyphPenX = 0f;   // origin of the FINAL glyph, see below
                boolean any = false;
                int i = 0;
                while (i < n) {
                    int cp = text.codePointAt(i);
                    if (MacApkFont.isDefaultIgnorable(cp)) { i += Character.charCount(cp); continue; }
                    java.awt.Font base = (cp < 0x20) ? null : MacApkFont.fallbackBaseFor(cp, f);
                    int runStart = i;
                    i += Character.charCount(cp);
                    while (i < n) {
                        int cp2 = text.codePointAt(i);
                        if (MacApkFont.isDefaultIgnorable(cp2)) break;
                        java.awt.Font b2 = (cp2 < 0x20) ? null : MacApkFont.fallbackBaseFor(cp2, f);
                        if (b2 != base) break;
                        i += Character.charCount(cp2);
                    }
                    String run = text.substring(runStart, i);
                    java.awt.Font rf = (base == null) ? f : MacApkFont.atSizeOf(base, f);
                    // ⚠ MEASURE THE INK AT THE POSITIONS WE ACTUALLY DRAW AT.
                    // AOSP offsets each glyph's bounds by the SAME running pen
                    // that carries the quantized advance and the GPOS kern, then
                    // joins them (LayoutCore.cpp:526-528 `glyphBounds.offset(x,y);
                    // mBounds.join(glyphBounds); x += xAdvance;`), and hwui's
                    // doTextBounds (jni/Paint.cpp:367-372) roundOut()s that ONCE
                    // -- which is what the floor/ceil below already does.
                    //
                    // createGlyphVector positions glyphs at RAW float advances,
                    // so the right edge was computed from a pen this code never
                    // uses: it advances BETWEEN runs by quantizedWidth but was
                    // laying out WITHIN a run unquantized and unkerned. Both
                    // corrections are needed and neither alone is right --
                    // measured on the pangram, raw gives 639, quantized-only
                    // overshoots to 641, and quantized+kern gives the device's
                    // 640. Reusing quantize() gets both for free and keeps ink
                    // bounds and drawn ink derived from ONE vector.
                    MacApkFont.Quantized q = MacApkFont.quantize(rf, run,
                                                                 MacApkFont.ligaFor(letterSpacing));
                    java.awt.font.GlyphVector gv = (q != null) ? q.glyphs
                        : rf.createGlyphVector(MacApkFont.textFrc(), run);
                    java.awt.geom.Rectangle2D vb = gv.getVisualBounds();
                    if (!vb.isEmpty()) {
                        minX = Math.min(minX, penX + vb.getX());
                        minY = Math.min(minY, vb.getY());
                        maxX = Math.max(maxX, penX + vb.getX() + vb.getWidth());
                        maxY = Math.max(maxY, vb.getY() + vb.getHeight());
                        any = true;
                    }
                    // The origin of this run's LAST glyph, for the ink-less case
                    // below. AOSP's accumulator ends up AT that origin, not past
                    // the final advance.
                    try {
                        int gc = gv.getNumGlyphs();
                        if (gc > 0) {
                            lastGlyphPenX = penX + (float) gv.getGlyphPosition(gc - 1).getX();
                        }
                    } catch (Throwable ignored) { }
                    // Advance the pen by the SAME quantized width the measure
                    // path charges, so run k+1's ink lands where it is drawn.
                    float qw = MacApkFont.quantizedWidth(rf, run,
                                                         MacApkFont.ligaFor(letterSpacing));
                    penX += Float.isNaN(qw)
                          ? (float) rf.getStringBounds(run, MacApkFont.textFrc()).getWidth()
                          : qw;
                }
                if (any) {
                    bounds.left   = (int) Math.floor(minX);
                    bounds.top    = (int) Math.floor(minY);
                    bounds.right  = (int) Math.ceil(maxX);
                    bounds.bottom = (int) Math.ceil(maxY);
                } else {
                    // Ink-less text (all whitespace). AOSP's MinikinRect.join:
                    //   if (isEmpty()) { set(r); } else if (!r.isEmpty()) {...}
                    // Every space's rect is empty, so the accumulator never
                    // stops being empty and each positioned-empty rect OVERWRITES
                    // it -- leaving a degenerate point at the LAST GLYPH'S ORIGIN,
                    // not past the final advance. For "     " that is 4 advances,
                    // not 5 (measured: device says 32, five advances would be 40).
                    bounds.left = bounds.right = Math.round(lastGlyphPenX);
                    bounds.top = bounds.bottom = 0;
                }
                return;
            } catch (Throwable ignored) { /* fall through to the approximation */ }
        }
        // Font resolution/glyph layout failed (should not happen in
        // practice): fall back to the old crude line-box rather than
        // leaving bounds all-zero.
        bounds.top = (int) (-textSize * 0.8f);
        bounds.right = (int) measureText(text);
        bounds.bottom = (int) (textSize * 0.2f);
    }
    // ⚠ ARGUMENT ORDER IS LOAD-BEARING (AOSP Paint.java:2795, :2819, :2842).
    // AOSP validates the RANGE first and the null Rect second, and it
    // dereferences text.length() before either -- so a null text is an NPE,
    // a reversed range is an IOOBE (AIOOBE on the char[] overload), and a null
    // Rect is an NPE with the message "need bounds Rect". These used to
    // return silently on every one of those, so a caller with a swapped
    // start/end got an all-zero Rect and positioned a badge/caret at the
    // origin instead of learning it had a bug.
    public void getTextBounds(String text, int start, int end, android.graphics.Rect bounds) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        computeInkBounds(text.substring(start, end), bounds);
    }
    public void getTextBounds(char[] text, int index, int count, android.graphics.Rect bounds) {
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        computeInkBounds(new String(text, index, count), bounds);
    }
    public void getTextBounds(CharSequence text, int start, int end, android.graphics.Rect bounds) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        computeInkBounds(text.subSequence(start, end).toString(), bounds);
    }
    // Real Roboto ascent/descent (AWT reports ascent positive; AOSP's
    // Paint.ascent() is negative). Roboto's line box is ~1.19x the text
    // size; the old hardcoded 0.8/0.2 split made it exactly 1.0x, so every
    // multi-line block (F-Droid's description/changelog) was ~19% too
    // short vertically vs the emulator. Falls back to the 0.8/0.2 split if
    // the font metrics are unavailable.
    //
    // ⚠ v1.0.939: these read the FLOAT line metrics, not java.awt.FontMetrics.
    // AOSP's ascent()/descent() hand back the raw SkScalar with NO rounding
    // (android11-release libs/hwui/jni/Paint.cpp:949-959,
    //     static jfloat ascent(...)  { ...; return SkScalarToFloat(metrics.fAscent); }
    //     static jfloat descent(...) { ...; return SkScalarToFloat(metrics.fDescent); }
    // and the float getFontMetrics at :621-625 writes every field the same way),
    // whereas java.awt.FontMetrics.getAscent()/getDescent() return an `int` --
    // measured to be the CEIL of the true value (ceil(29.6875)=30,
    // ceil(59.375)=60, ceil(24.4141)=25, which is exactly the column the
    // answer-differential recorded as ours). So 33 of the 82 divergent TXTM
    // rows were one rounding step, not a font problem: every reference value
    // is the face's own `hhea` scaled by size/upem, and our own bundled
    // Roboto-Regular carries `hhea asc 1900 desc -500 gap 0, head upem 2048`,
    // i.e. -1900*32/2048 = -29.6875 = the golden `TXTM.metric.base.ascent`.
    //
    // java.awt.Font.getLineMetrics() carries those same numbers UNROUNDED.
    // Measured across {Roboto-Regular, Roboto-Bold, DroidSansMono,
    // NotoSerif-Regular} x {12,16,20,32,40,64,100}px: it reproduces the golden
    // ascent/descent/fontSpacing to the digit at every size on every face
    // (32px -> -29.6875 / 7.8125 / 37.5000; 64px -> -59.3750 / 15.6250 /
    // 75.0000; 100px -> -92.7734 / 24.4141 / 117.1875). Nothing about WHICH
    // face we serve changes here -- only which accessor we read it through.
    //
    // The trailing 0.8/0.2 fallback stays for the case where no font resolves
    // at all; it is a shape, not a measurement, and returning 0 there would
    // collapse every line box.
    //
    // MACAPK_TEXT_FLOAT_METRICS=0 restores the integer path. Gated because the
    // blast radius is every TextView in the fleet: ascent()/descent() feed
    // getFontMetrics, getFontMetricsInt, getFontSpacing, TextView's
    // includeFontPadding=false line height, Layout's line geometry and the
    // sub/superscript baseline shifts.
    private static final boolean MACAPK_FLOAT_METRICS =
        !"0".equals(System.getenv("MACAPK_TEXT_FLOAT_METRICS"));

    /** LineMetrics for a resolved Font, cached the way MacApkFont.metricsOf
     *  caches java.awt.FontMetrics -- keyed by the resolved Font instance, so
     *  the measure hot path allocates nothing after the first call per
     *  (face, size, style). getLineMetrics() otherwise builds a fresh
     *  LineMetrics on every ascent()/descent(), and TextView asks for those
     *  several times per measure pass.
     *
     *  The sample string only matters for a COMPOSITE font (the JDK logical
     *  fallback we land on when Roboto fails to load); for a physical face
     *  "Ag", "", "x" and non-Latin samples all measured identical. It is the
     *  same Font instance resolveFontMetrics() reads, so the two accessors
     *  can never disagree about WHICH face they describe. */
    private static final java.util.concurrent.ConcurrentHashMap<java.awt.Font,
            java.awt.font.LineMetrics> sLineMetricsCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private java.awt.font.LineMetrics resolveLineMetrics() {
        java.awt.Font f = resolveFont();
        if (f == null) return null;
        java.awt.font.LineMetrics lm = sLineMetricsCache.get(f);
        if (lm == null) {
            try {
                lm = f.getLineMetrics("Ag", MacApkFont.textFrc());
                sLineMetricsCache.put(f, lm);
            } catch (Throwable t) { return null; }
        }
        return lm;
    }
    public float ascent()  {
        if (MACAPK_FLOAT_METRICS) {
            java.awt.font.LineMetrics lm = resolveLineMetrics();
            if (lm != null) return -lm.getAscent();
        }
        java.awt.FontMetrics fm = resolveFontMetrics();
        return (fm != null) ? -fm.getAscent() : -textSize * 0.8f;
    }
    public float descent() {
        if (MACAPK_FLOAT_METRICS) {
            java.awt.font.LineMetrics lm = resolveLineMetrics();
            if (lm != null) return lm.getDescent();
        }
        java.awt.FontMetrics fm = resolveFontMetrics();
        return (fm != null) ? fm.getDescent() : textSize * 0.2f;
    }
    /** `top` and `bottom` -- the font's real vertical extents.
     *
     *  ⚠ These were FABRICATED as `ascent - 2` / `descent + 2`: a hardcoded 2px
     *  at every text size, standing in for the font bounding box. They are not
     *  a decoration -- `includeFontPadding` is ON by default, and under it
     *  TextView measures its height and places its baseline from top/bottom
     *  rather than ascent/descent. So the constant made EVERY TextView in the
     *  fleet measure short and draw its baseline high: at 20px the reference
     *  height is 28 and ours was 24, at 40px 54 against 48.
     *
     *  Android reads Skia's SkFontMetrics fTop/fBottom (the `head` table's
     *  font bounding box for a TrueType face) and rounds OUTWARD --
     *  libs/hwui/jni/Paint.cpp getFontMetricsInt:
     *      fmi->top    = SkScalarFloorToInt(metrics.fTop);
     *      fmi->bottom = SkScalarCeilToInt(metrics.fBottom);
     *  which is what MacApkFont.verticalExtents computes, exact on all three
     *  probe sizes for both height and baseline.
     *
     *  The ±2 fallback is kept for the case where the face's tables are
     *  unreadable (the JDK logical fallback font has none), because returning
     *  ascent/descent unchanged there would make top/bottom meaningless rather
     *  than merely approximate. */
    /** The RAW float top/bottom, for the float getFontMetrics. See that method. */
    private float[] macapkVerticalExtentsF() {
        try { return MacApkFont.verticalExtentsF(resolveFont()); }
        catch (Throwable t) { return null; }
    }
    private int[] macapkVerticalExtents() {
        try { return MacApkFont.verticalExtents(resolveFont()); }
        catch (Throwable t) { return null; }
    }
    /** The face's hhea lineGap at this size -- AOSP's FontMetrics.leading. */
    private float macapkLeading() {
        try { return MacApkFont.leadingF(resolveFont()); }
        catch (Throwable t) { return 0f; }
    }
    /** ⚠ `top`/`bottom` are still the ROUNDED ints MacApkFont.verticalExtents
     *  computes, while AOSP's float path writes the raw fTop/fBottom
     *  (Paint.cpp:621,624). They are within one of the reference and drive the
     *  includeFontPadding line box, which is exact against the golden at
     *  12/20/40px -- so they are deliberately left alone here. Closing the
     *  remaining 12 `TXTM.metric.*.{top,bottom}` rows needs a `float[]` sibling
     *  to MacApkFont.verticalExtents returning {-yMax*size/upem,
     *  -yMin*size/upem} un-rounded (that file is another lane's). The existing
     *  int[] must stay either way: getFontMetricsInt wants top FLOORED and
     *  bottom CEILED, which is exactly what it already returns, so only this
     *  float accessor would take the new sibling. */
    public FontMetrics getFontMetrics() {
        FontMetrics m = new FontMetrics();
        m.ascent = ascent(); m.descent = descent();
        // ⚠ v1.0.938: the FLOAT api reports RAW top/bottom. AOSP writes the
        // float and int paths from the same SkFontMetrics but rounds only the
        // int one -- getFontMetrics stores SkScalarToFloat(fTop) verbatim
        // (libs/hwui/jni/Paint.cpp:621-625) while getFontMetricsInt floors top
        // and ceils bottom (:640, :643). Reusing the rounded ints here reported
        // -34.0000/9.0000 at 32px against a golden -33.7969/8.6719.
        float[] tbf = macapkVerticalExtentsF();
        if (tbf != null) { m.top = tbf[0]; m.bottom = tbf[1]; }
        else             { m.top = m.ascent - 2; m.bottom = m.descent + 2; }
        // v1.0.940: the face's REAL `hhea` lineGap, which is what Skia puts in
        // SkFontMetrics.fLeading and AOSP reports verbatim. This was pinned to
        // 0, and the pin was invisible because every font WE bundle has gap 0 --
        // but an APP-SUPPLIED font with a real gap got 0 too, and
        // getFontSpacing() is `descent - ascent + leading`, so that app's own
        // line spacing came out short with nothing to point at.
        // Reads through MacApkFont.leadingF, which returns 0 for a face with no
        // gap or unreadable tables, so every currently-agreeing row keeps its
        // answer.
        m.leading = macapkLeading();
        return m;
    }
    /** ⚠ The rounding rule is AOSP's, per field, and the two paths must not
     *  drift apart: android11-release libs/hwui/jni/Paint.cpp getFontMetricsInt
     *  (:630-647) does
     *      int ascent  = SkScalarRoundToInt(metrics.fAscent);    // :634
     *      int descent = SkScalarRoundToInt(metrics.fDescent);   // :635
     *      int leading = SkScalarRoundToInt(metrics.fLeading);   // :636
     *      ...top    = SkScalarFloorToInt(metrics.fTop);         // :640
     *      ...bottom = SkScalarCeilToInt(metrics.fBottom);       // :643
     *  -- ROUND on ascent/descent/leading, floor/ceil ONLY on top/bottom.
     *  Skia's SkScalarRoundToInt is sk_float_round2int, defined as
     *  `saturate2int(floor(x + 0.5f))` (external/skia include/private/
     *  SkFloatingPoint.h:145), which is exactly java.lang.Math.round(float).
     *
     *  This used to floor the ascent and ceil the descent. That was invisible
     *  while ascent()/descent() were integral, and became a live error the
     *  moment they went fractional: at 64px the reference int ascent is -59
     *  (round(-59.375)) and flooring gives -60 -- the single divergent
     *  `TXTM.mint.sz64.ascent` row, and the assertion `TXTM.q.roundingRule.sz64`
     *  which PASSES today and would have broken. */
    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt m = new FontMetricsInt();
        m.ascent = ascentInt();
        m.descent = descentInt();
        int[] tb = macapkVerticalExtents();
        if (tb != null) { m.top = tb[0]; m.bottom = tb[1]; }
        else            { m.top = m.ascent - 2; m.bottom = m.descent + 2; }
        // ROUND, per the rule documented above: hwui's getFontMetricsInt does
        // SkScalarRoundToInt(metrics.fLeading) (Paint.cpp:636), not floor/ceil.
        m.leading = Math.round(macapkLeading());
        return m;
    }
    /** AOSP returns `descent - ascent + leading` computed from the SAME ints it
     *  just wrote (Paint.cpp:646), with no clamp, and it runs the metrics even
     *  when the out param is null (`if (metricsObj)` guards only the writes).
     *  This used to return `max(0, ceil(descent()) - floor(ascent()))`, which is
     *  a DIFFERENT rounding rule from the fields beside it -- so the returned
     *  height and the written ascent/descent could disagree by a pixel as soon
     *  as the metrics stopped being integral (TXT.metricint.returnIdentity). */
    public int getFontMetricsInt(FontMetricsInt out) {
        FontMetricsInt m = getFontMetricsInt();
        if (out != null) {
            out.ascent = m.ascent; out.descent = m.descent;
            out.top = m.top; out.bottom = m.bottom; out.leading = m.leading;
        }
        return m.descent - m.ascent + m.leading;
    }
    // The single place the float metrics become ints. Everything integral in
    // this class goes through these two so the Int path can never drift from
    // the float path (see getFontMetricsInt's citation above).
    private int ascentInt()  { return Math.round(ascent()); }
    private int descentInt() { return Math.round(descent()); }

    public ColorFilter setColorFilter(ColorFilter cf) { mColorFilter = cf; return cf; }
    public ColorFilter getColorFilter() { return mColorFilter; }
    private ColorFilter mColorFilter;
    // v1.0.124: AOSP `Paint.setShader` returns Shader (the param) per
    // cs.android.com/.../graphics/Paint.java. Compose UI's
    // SimpleGraphicsLayerModifier uses the return value to chain
    // shader assignments.
    public android.graphics.Shader setShader(android.graphics.Shader s) {
        this.mShader = s;
        return s;
    }
    public android.graphics.Shader getShader() { return mShader; }
    private android.graphics.Shader mShader;
    // PathEffect / MaskFilter -- store + return per AOSP. Apps that
    // use these for drop-shadows / dashed lines get state preserved
    // even though our Canvas doesn't render the effect.
    public android.graphics.PathEffect setPathEffect(android.graphics.PathEffect e) {
        this.mPathEffect = e; return e;
    }
    public android.graphics.PathEffect getPathEffect() { return mPathEffect; }
    private android.graphics.PathEffect mPathEffect;
    // MaskFilter not implemented in our framework -- leave Paint without it.
    // setTypeface defined later in file (uses Typeface unqualified).
    /** AOSP setShadowLayer paints a soft shadow behind drawn text /
     *  shapes. Canvas.drawText now honors this as a drop shadow (text
     *  re-drawn in shadowColor at the +dx/+dy offset before the main
     *  glyphs); the soft blur (radius) is approximated by a small
     *  spread. A shadow is "active" only when shadowColor has non-zero
     *  alpha AND some radius/offset is set -- so text that never calls
     *  setShadowLayer renders byte-identically to before. GD Lite's
     *  Cocos2dxBitmap and Frozen Bubble's menu Buttons set this. */
    private float shadowRadius, shadowDx, shadowDy;
    private int   shadowColor;
    public void setShadowLayer(float radius, float dx, float dy, int color) {
        this.shadowRadius = radius; this.shadowDx = dx;
        this.shadowDy = dy; this.shadowColor = color;
    }
    public void clearShadowLayer() {
        this.shadowRadius = 0; this.shadowDx = 0; this.shadowDy = 0; this.shadowColor = 0;
    }
    public float getShadowRadius() { return shadowRadius; }
    public float getShadowDx()     { return shadowDx; }
    public float getShadowDy()     { return shadowDy; }
    public int   getShadowColor()  { return shadowColor; }
    /** True when a drop shadow should actually be painted: a visible
     *  (non-transparent) shadow color plus some blur or offset. */
    public boolean hasShadowLayer() {
        return ((shadowColor >>> 24) != 0)
            && (shadowRadius > 0f || shadowDx != 0f || shadowDy != 0f);
    }
    /** AOSP changed the return type from void to Typeface in API 31
     *  (returns the previous Typeface). Apps compiled against the
     *  newer SDK do `INVOKEVIRTUAL Paint.setTypeface(Typeface)Typeface`
     *  -- the void-return version raises NoSuchMethodError on the
     *  exact descriptor mismatch. Returning the previous typeface
     *  satisfies both old void-discard and new typed-consumer
     *  callers because Java bytecode resolution matches on the full
     *  descriptor; we can't ALSO keep a void overload (different
     *  return type doesn't disambiguate Java methods), so the new
     *  signature wins. */
    private Typeface mTypeface;
    public Typeface setTypeface(Typeface t) {
        Typeface previous = mTypeface;
        mTypeface = t;
        return previous;
    }
    public Typeface getTypeface() { return mTypeface; }

    // setXfermode / getXfermode now STORE + round-trip (defined in the v1.0.433
    // surface-completion block below; the old discard-and-return stubs were
    // removed to avoid a duplicate-method definition).
    /** v1.0.124: AOSP Paint.setBlendMode (API 29+) -- replaces Xfermode.
     *  We don't actually composite via BlendMode; just store the
     *  reference so callers can query it back. */
    private android.graphics.BlendMode mBlendMode;
    public void setBlendMode(android.graphics.BlendMode bm) { this.mBlendMode = bm; }
    public android.graphics.BlendMode getBlendMode() { return mBlendMode; }
    // setFakeBoldText / isFakeBoldText now defined near the flag-mask block above.

    // v1.0.124: comprehensive Compose-text-rendering surface. Compose UI's
    // TextPaintExtensions_androidKt.setTextMotion + AndroidParagraphIntrinsics
    // chain through these during measure -- each missing one is a
    // NoSuchMethodError that aborts the layout pass mid-RV-dispatch.
    public static final int HINTING_OFF = 0;
    public static final int HINTING_ON  = 1;
    private int hinting = HINTING_ON;
    public void setHinting(int h) { hinting = h; }
    public int  getHinting() { return hinting; }

    // Word spacing (API 29+) -- defaults to 0 (no extra space between words).
    private float wordSpacing = 0f;
    public void  setWordSpacing(float w) { wordSpacing = w; }
    public float getWordSpacing() { return wordSpacing; }

    /**
     * API 28. "Returns true if the two paints will produce the same text
     * measurement" -- deliberately NOT full Paint equality: colour, stroke,
     * shadow and every other purely-visual property are excluded, because none
     * of them can move a glyph advance.
     *
     * AOSP implements it natively as MinikinPaint equality
     * (libs/hwui/jni/Paint.cpp:1016-1031 -> minikin
     * include/minikin/MinikinPaint.h:84-90 at android11-release):
     *
     *     return size == paint.size && scaleX == paint.scaleX && skewX == paint.skewX &&
     *            letterSpacing == paint.letterSpacing && wordSpacing == paint.wordSpacing &&
     *            fontFlags == paint.fontFlags && localeListId == paint.localeListId &&
     *            fontStyle == paint.fontStyle && familyVariant == paint.familyVariant &&
     *            fontFeatureSettings == paint.fontFeatureSettings &&
     *            font.get() == paint.font.get();
     *
     * ⚠ `size` is NOT the raw text size. MinikinUtils::prepareMinikinPaint
     * (libs/hwui/hwui/MinikinUtils.cpp) writes
     *     minikinPaint.size = font.isLinearMetrics() ? font.getSize()
     *                                                : static_cast<int>(font.getSize());
     * so with LINEAR_TEXT_FLAG *off* -- the default -- the size is TRUNCATED to
     * an int before the comparison, and two paints at 12.2 and 12.8 really are
     * equal for measurement. With the flag on they are not. That truncation is
     * the one part of this method a reasonable implementation gets wrong.
     *
     * ⚠ `fontStyle` and `font.get()` are the RESOLVED weight/slant and the font
     * COLLECTION, not the Typeface object -- so two distinct Typeface instances
     * that resolve to the same family and weight compare EQUAL. That is why the
     * comparison below reads the family name and the resolved weight/slant
     * rather than doing `mTypeface == other.mTypeface`.
     *
     * Written because androidx's text layout uses it as a cache key: a wrong
     * `true` reuses a layout measured with a different font, and a wrong
     * `false` throws away every cached layout on every frame. It is also why
     * debug/fwprobe/offline.sh could not compile TextCases.
     */
    public boolean equalsForTextMeasurement(Paint other) {
        if (other == null) return false;
        if (other == this) return true;   // AOSP: `if (lPaint == rPaint) return true;`

        // size, with minikin's linear-metrics truncation.
        float lSize = isLinearText() ? textSize : (int) textSize;
        float rSize = other.isLinearText() ? other.textSize : (int) other.textSize;
        if (lSize != rSize) return false;
        if (isLinearText() != other.isLinearText()) return false;

        if (textScaleX_   != other.textScaleX_)   return false;   // scaleX
        if (textSkewX     != other.textSkewX)     return false;   // skewX
        if (letterSpacing != other.letterSpacing) return false;
        if (wordSpacing   != other.wordSpacing)   return false;

        // fontFlags: the measurement-affecting subset of the paint flags.
        // MinikinFontSkia::packFontFlags carries the fake-bold bit and the
        // hinting/subpixel/linear-metrics ones; colour and stroke flags are not
        // in it. We compare the ones this tree's measurement path can actually
        // read -- fake bold (which selects an emboldened AWT face) and linear
        // text (already compared above via the size rule).
        if (isFakeBoldText() != other.isFakeBoldText()) return false;

        // localeListId
        if (!java.util.Objects.equals(textLocale, other.textLocale)) return false;

        // fontStyle + font.get(): the resolved weight/slant and the family,
        // NOT typeface identity.
        Typeface lt = mTypeface, rt = other.mTypeface;
        int lw = (lt != null) ? lt.getWeight() : 400;
        int rw = (rt != null) ? rt.getWeight() : 400;
        if (lw != rw) return false;
        int ls = (lt != null) ? (lt.getStyle() & Typeface.ITALIC) : 0;
        int rs = (rt != null) ? (rt.getStyle() & Typeface.ITALIC) : 0;
        if (ls != rs) return false;
        if (!java.util.Objects.equals(lt != null ? lt.macapkFamilyName() : null,
                                      rt != null ? rt.macapkFamilyName() : null)) return false;
        // An app-supplied face is its own "collection"; compare by identity the
        // way the native pointer comparison does.
        if ((lt != null ? lt.macapkBaseFont() : null)
                != (rt != null ? rt.macapkBaseFont() : null)) return false;

        // fontFeatureSettings (+ variation settings, which select a different
        // concrete face on a variable font and so belong to the same class).
        if (!java.util.Objects.equals(fontFeatureSettings, other.fontFeatureSettings)) return false;
        if (!java.util.Objects.equals(mFontVariationSettings, other.mFontVariationSettings)) return false;

        // familyVariant (Paint.setFontVariant / elegant text height) has no
        // storage in this tree; when it gains one it belongs here.
        return true;
    }

    // Hyphen edits (API 29+). Stored only; we don't actually hyphenate.
    public static final int START_HYPHEN_EDIT_NO_EDIT = 0;
    public static final int END_HYPHEN_EDIT_NO_EDIT   = 0;
    // The rest of AOSP's hyphen-edit enum (Paint.java, API 29) plus the API-35/36
    // text-run flags. Values verbatim from the API-36 signature file. javac inlines
    // these into the caller so a missing one cannot fail at runtime -- but it CAN
    // fail a reflective lookup, and a caller that passes a hyphen edit we never
    // declared would be passing a number we do not recognise.
    public static final int START_HYPHEN_EDIT_INSERT_HYPHEN = 1;
    public static final int START_HYPHEN_EDIT_INSERT_ZWJ = 2;
    public static final int END_HYPHEN_EDIT_REPLACE_WITH_HYPHEN = 1;
    public static final int END_HYPHEN_EDIT_INSERT_HYPHEN = 2;
    public static final int END_HYPHEN_EDIT_INSERT_ARMENIAN_HYPHEN = 3;
    public static final int END_HYPHEN_EDIT_INSERT_MAQAF = 4;
    public static final int END_HYPHEN_EDIT_INSERT_UCAS_HYPHEN = 5;
    public static final int END_HYPHEN_EDIT_INSERT_ZWJ_AND_HYPHEN = 6;
    /** API 35 (com.android.text.flags.vertical_text_layout). */
    public static final int VERTICAL_TEXT_FLAG = 0x1000;
    /** API 35 (com.android.text.flags.letter_spacing_justification) -- marks a
     *  text run as sitting at the left/right edge of its line, which suppresses
     *  the justification letter-spacing on that side. */
    public static final int TEXT_RUN_FLAG_LEFT_EDGE = 0x2000;
    public static final int TEXT_RUN_FLAG_RIGHT_EDGE = 0x4000;
    private int startHyphenEdit = START_HYPHEN_EDIT_NO_EDIT;
    private int endHyphenEdit   = END_HYPHEN_EDIT_NO_EDIT;
    public void setStartHyphenEdit(int e) { startHyphenEdit = e; }
    public int  getStartHyphenEdit() { return startHyphenEdit; }
    public void setEndHyphenEdit(int e)   { endHyphenEdit = e; }
    public int  getEndHyphenEdit() { return endHyphenEdit; }

    // Elegant text height (API 21+) -- affects baseline metrics for
    // tall scripts. Default false on AOSP; just store.
    private boolean elegantTextHeight = false;
    public void setElegantTextHeight(boolean v) { elegantTextHeight = v; }
    public boolean isElegantTextHeight() { return elegantTextHeight; }

    // Text locale (API 17+).
    private java.util.Locale textLocale = java.util.Locale.getDefault();
    public void setTextLocale(java.util.Locale l) { if (l != null) textLocale = l; }
    public java.util.Locale getTextLocale() { return textLocale; }
    public android.os.LocaleList getTextLocales() {
        return new android.os.LocaleList(textLocale);
    }
    public void setTextLocales(android.os.LocaleList l) {
        if (l != null && !l.isEmpty()) textLocale = l.get(0);
    }

    /** AOSP Paint.getFlags companion -- setLinearText / etc. flag setters
     *  already defined near the flag-mask block above. */

    // FontMetrics / getFontMetricsInt already defined earlier in the file.

    // ================================================================
    // v1.0.433 surface completion -- AOSP Paint methods that were missing
    // (each a NoSuchMethodError that aborts the MEASURE pass mid-Compose-
    // text-layout -> blank text/screen, per docs/canvas-draw-surface-diff).
    // ================================================================

    // MaskFilter (blur/glow). Stored only; Canvas may approximate later.
    private android.graphics.MaskFilter mMaskFilter;
    public android.graphics.MaskFilter setMaskFilter(android.graphics.MaskFilter mf) { this.mMaskFilter = mf; return mf; }
    public android.graphics.MaskFilter getMaskFilter() { return mMaskFilter; }

    // Xfermode: now STORED + returned (was discarded). The modern BlendMode path
    // (Canvas.compositeFor(getBlendMode())) remains the active compositing knob;
    // we deliberately do NOT auto-derive a BlendMode from a legacy
    // PorterDuffXfermode here, to avoid changing compositing for the cocos/AOSP
    // game paths that set one. getXfermode now round-trips per AOSP.
    private android.graphics.Xfermode mXfermode;
    public android.graphics.Xfermode setXfermode(android.graphics.Xfermode xfermode) { this.mXfermode = xfermode; return xfermode; }
    public android.graphics.Xfermode getXfermode() { return mXfermode; }

    // ColorLong (Compose packs Color as a long). sRGB fast path = high 32 bits.
    public long getColorLong() { return ((long) color & 0xffffffffL) << 32; }
    public void setColor(long c) { color = (int) (c >>> 32); }

    // AOSP-named shadow-layer getters (API 29+). We already store these via
    // setShadowLayer; expose the faithful names + the ColorLong overload.
    public float getShadowLayerRadius() { return shadowRadius; }
    public float getShadowLayerDx()     { return shadowDx; }
    public float getShadowLayerDy()     { return shadowDy; }
    public int   getShadowLayerColor()  { return shadowColor; }
    public long  getShadowLayerColorLong() { return ((long) shadowColor & 0xffffffffL) << 32; }
    public void  setShadowLayer(float radius, float dx, float dy, long shadowColorLong) {
        setShadowLayer(radius, dx, dy, (int) (shadowColorLong >>> 32));
    }

    // Text-shaping / advance family. Called during Compose / StaticLayout MEASURE;
    // any one missing aborts the measure pass and blanks the text subtree. All
    // implementable on our AWT measureText (no GPU).
    /** AOSP: `getFontSpacing()` is literally `getFontMetrics(null)`
     *  (Paint.java:2155), and getFontMetrics returns `descent - ascent +
     *  leading` (libs/hwui/jni/Paint.cpp:610, :621-627). Routing the spacing
     *  through the metrics rather than recomputing `descent() - ascent()`
     *  keeps the two APIs identical BY CONSTRUCTION -- a framework whose
     *  getFontSpacing forgets `leading` diverges here and nowhere else, and
     *  our leading is only 0 today by accident of the AWT metrics we read. */
    public float getFontSpacing() { return getFontMetrics(null); }
    public float getFontMetrics(FontMetrics metrics) {
        FontMetrics m = getFontMetrics();
        if (metrics != null) {
            metrics.top = m.top; metrics.ascent = m.ascent; metrics.descent = m.descent;
            metrics.bottom = m.bottom; metrics.leading = m.leading;
        }
        return m.descent - m.ascent + m.leading;
    }
    /** AOSP javadoc verbatim: "Determine whether the typeface set on the
     *  paint has a glyph supporting the string... Checking is done on the
     *  entire fallback chain, not just the immediate font referenced."
     *  Real Android checks per-Typeface glyph coverage natively; this used
     *  to unconditionally answer true for any non-empty string regardless
     *  of the Typeface or the string's content, so EmojiCompat and
     *  icon-font libraries (the actual callers, per corpus demand) never
     *  detected a missing glyph and never substituted.
     *  java.awt.Font.canDisplayUpTo returns -1 iff every char is
     *  displayable -- exact for the single-codepoint case. We resolve to
     *  one Font per Typeface (no fallback-chain concept), so
     *  multi-codepoint ligature/variation-selector-specific coverage (an
     *  emoji flag pair, a variation selector) narrows to "every codepoint
     *  individually displayable" rather than true cluster-glyph detection
     *  -- an honest per-codepoint answer, not a fabricated one. */
    public boolean hasGlyph(String string) {
        if (string == null || string.isEmpty()) return false;
        java.awt.Font f = resolveFont();
        // Font resolution failing is not expected in practice; don't
        // regress below the old always-true behavior in that case.
        if (f == null) return true;
        // ⚠ v1.0.938: ask the FALLBACK CHAIN, not just the primary face.
        // AOSP's javadoc is explicit -- "checking is done on the entire fallback
        // chain" -- because a device renders a character through whichever Noto
        // face covers it, and hasGlyph is how an app decides whether it may
        // DISPLAY that character at all (emoji pickers, symbol keyboards and
        // "does this device support X" probes all gate on it). Answering from
        // the primary alone made us say "no glyph" for characters we then went
        // on to draw correctly through MacApkFont's chain.
        //
        // MacApkFont.chainCovers already existed for exactly this, its javadoc
        // said so, and it had ZERO callers -- the plumbing was written and never
        // connected.
        for (int i = 0; i < string.length(); ) {
            int cp = string.codePointAt(i);
            // A default-ignorable is never inked by HarfBuzz, but every font is
            // required to "support" it; AOSP answers true rather than probing a
            // cmap that may legitimately lack it.
            if (!MacApkFont.isDefaultIgnorable(cp) && !MacApkFont.chainCovers(cp, f)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    // ================================================================
    // getRunAdvance / getOffsetForAdvance
    //
    // ⚠ THE PARAMETER ORDER IS (text, start, end, contextStart, contextEnd,
    // isRtl, offset) -- AOSP Paint.java:2909 and :2989 at android-11.0.0_r48,
    // javadoc "0 <= contextStart <= start <= offset <= end <= contextEnd <=
    // text.length must hold on entry".
    //
    // Until v1.0.923 this class declared the two pairs TRANSPOSED, as
    // (text, contextStart, contextEnd, start, end, ...). Both orders are
    // (Object,int,int,int,int,boolean,int) so the descriptor is identical:
    // guest bytecode LINKS and RUNS against the wrong one with no error, and
    // it even agrees BY ACCIDENT on every call where start == contextStart,
    // which is most of them. Where it did not agree it answered a different
    // substring's width entirely -- a caret placed at the wrong character, a
    // run measured from the start of the paragraph instead of the start of
    // the run. Measured by fwprobe with start=4 end=7 ctx=0..13 on
    // "one two three": AOSP answers the width of "two"; we answered the width
    // of "one two".
    //
    // Nothing inside runtime/java called either method, so there was no
    // internal caller adapted to the wrong order to fix alongside this
    // (audited by hand at v1.0.923 -- the compiler cannot catch it, the
    // types being identical).
    // ================================================================

    /** Advance of text[start..offset), shaped in the context
     *  [contextStart, contextEnd). We have no shaper, so the context indices
     *  and isRtl only participate in validation and the advance is the
     *  substring width. RAW (un-ceiled) like AOSP's nGetRunAdvance -- only
     *  measureText ceils. */
    public float getRunAdvance(char[] text, int start, int end, int contextStart, int contextEnd,
                               boolean isRtl, int offset) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((contextStart | start | offset | end | contextEnd
                | start - contextStart | offset - start | end - offset
                | contextEnd - end | text.length - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end == start) {
            return 0.0f;
        }
        return macapkRawAdvance(new String(text, start, offset - start));
    }
    public float getRunAdvance(CharSequence text, int start, int end, int contextStart,
                               int contextEnd, boolean isRtl, int offset) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((contextStart | start | offset | end | contextEnd
                | start - contextStart | offset - start | end - offset
                | contextEnd - end | text.length() - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end == start) {
            return 0.0f;
        }
        // AOSP re-bases onto the context window before delegating
        // (Paint.java:2956); mirror it so both overloads answer identically.
        char[] buf = text.subSequence(contextStart, contextEnd).toString().toCharArray();
        return getRunAdvance(buf, start - contextStart, end - contextStart, 0,
                contextEnd - contextStart, isRtl, offset - contextStart);
    }
    /** Inverse of getRunAdvance: the offset in [start, end] whose position is
     *  closest to `advance`. AOSP lands on a grapheme-cluster boundary; we
     *  advance by CODEPOINT, which at least never returns an offset that
     *  splits a surrogate pair. */
    public int getOffsetForAdvance(char[] text, int start, int end, int contextStart,
                                   int contextEnd, boolean isRtl, float advance) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        // Note: neither `offset` nor `advance` appears in AOSP's validation
        // expression here (Paint.java:2994) -- unlike getRunAdvance's.
        if ((contextStart | start | end | contextEnd
                | start - contextStart | end - start | contextEnd - end
                | text.length - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        // AOSP lands on a GRAPHEME-CLUSTER boundary closest to `advance`
        // (minikin Measurement.cpp getOffsetForAdvance walks
        // GraphemeBreak::isGraphemeBreak boundaries and minimises
        // |position - advance|, android-11.0.0_r48); the midpoint rule over
        // per-cluster advances picks the same boundary. Each cluster's
        // advance carries its own single letter-space (macapkRawAdvance on a
        // one-cluster slice embeds exactly one gap -- see macapkCharAdvances);
        // the old explicit inter-char gap on top of that double-counted it.
        final String sub = new String(text, start, end - start);
        try {
            java.text.BreakIterator it =
                java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
            it.setText(sub);
            float acc = 0f;
            int a = it.first();
            for (int b = it.next(); b != java.text.BreakIterator.DONE;
                    a = b, b = it.next()) {
                float w = macapkRawAdvance(sub.substring(a, b));
                if (acc + w / 2f > advance) return start + a;
                acc += w;
            }
            return end;
        } catch (Throwable t) {
            // Codepoint fallback, macapkCharAdvances' same degraded unit.
            float acc = 0f;
            int i = start;
            while (i < end) {
                int cc = Character.charCount(Character.codePointAt(text, i));
                float w = macapkRawAdvance(new String(text, i, cc));
                if (acc + w / 2f > advance) break;
                acc += w;
                i += cc;
            }
            return i;
        }
    }
    public int getOffsetForAdvance(CharSequence text, int start, int end, int contextStart,
                                   int contextEnd, boolean isRtl, float advance) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((contextStart | start | end | contextEnd
                | start - contextStart | end - start | contextEnd - end
                | text.length() - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        // AOSP Paint.java:3040: re-base onto the context window, delegate, and
        // add contextStart back onto the result.
        char[] buf = text.subSequence(contextStart, contextEnd).toString().toCharArray();
        return getOffsetForAdvance(buf, start - contextStart, end - contextStart, 0,
                contextEnd - contextStart, isRtl, advance) + contextStart;
    }
    /** Count of chars from index that fit in maxWidth; measuredWidth[0] = width.
     *  AOSP contract (char[] overload javadoc, verified against AOSP source):
     *  "If count is negative, then the characters are measured in reverse
     *  order" -- i.e. starting from the LAST character in
     *  [index, index+|count|) and walking backward, so the return value is
     *  how many characters fit counting from the END of that range (the
     *  standard "ellipsize the start of a long path/URL" idiom). The
     *  String/CharSequence overloads below now translate their own
     *  measureForwards flag into a negated count exactly the way AOSP's
     *  own delegation does
     *  (`breakText(buf, 0, -(end - start), maxWidth, measuredWidth)`); the
     *  old code always passed a positive count, silently discarding
     *  measureForwards==false on every caller. */
    public int breakText(char[] text, int index, int count, float maxWidth, float[] measuredWidth) {
        // AOSP Paint.java:2288 -- IAE on null; :2292 -- AIOOBE when
        // text.length - index < abs(count).
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (index < 0 || text.length - index < Math.abs(count)) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (text.length == 0 || count == 0) return 0;
        boolean forward = count >= 0;
        int n = Math.abs(count);
        // Mirror of AOSP's native helper (libs/hwui/jni/Paint.cpp breakText,
        // android-11.0.0_r48): build the per-char advances array ONCE (theirs
        // comes from MinikinUtils::measureText, the same array getTextWidths
        // returns), then accumulate in scan order. `measured + width >
        // maxWidth` breaks -- equal still fits -- and on the BACKWARD scan the
        // count is only committed at a nonzero width ("properly handle
        // clusters when scanning backwards"): a cluster's trailing
        // zero-advance units are counted only once their leading unit fits.
        // The advances are RAW (un-ceiled) and each cluster carries exactly
        // one letter-space, so measuredWidth[0] sits in the one-ceil band
        // below the matching measureText, which ceils the SAME sum. The old
        // body measured each UTF-16 unit in isolation (splitting surrogate
        // pairs) and added an explicit inter-char gap on top of the gap
        // macapkRawAdvance already embeds per cluster -- 2n-1 gaps against
        // measureText's n (the v1.0.932 notated regression,
        // TXT.break.ls010.sentence.fullWidthBand).
        float[] adv = macapkCharAdvances(new String(text, index, n));
        float measured = 0f;
        int measuredCount = 0;
        for (int i = 0; i < n; i++) {
            int idx = forward ? i : (n - i - 1);
            float width = adv[idx];
            if (measured + width > maxWidth) break;
            if (forward || width != 0.0f) measuredCount = i + 1;
            measured += width;
        }
        if (measuredWidth != null && measuredWidth.length > 0) measuredWidth[0] = measured;
        return measuredCount;
    }
    public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        // AOSP Paint.java:2380 -- IAE on null (this overload does NOT return 0).
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (text.length() == 0) return 0;
        char[] buf = text.toCharArray();
        int count = measureForwards ? buf.length : -buf.length;
        return breakText(buf, 0, count, maxWidth, measuredWidth);
    }
    public int breakText(CharSequence text, int start, int end, boolean measureForwards,
                         float maxWidth, float[] measuredWidth) {
        // AOSP Paint.java:2335 -- the same single validation expression as
        // measureText, raising a plain IndexOutOfBoundsException. Letting
        // subSequence raise instead produced StringIndexOutOfBoundsException,
        // a different class to a caller catching on the exact type.
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (text.length() == 0 || start == end) return 0;
        char[] buf = text.subSequence(start, end).toString().toCharArray();
        // AOSP:2352 translates measureForwards into a NEGATED count rather
        // than a separate flag, so the backwards direction actually reaches
        // the char[] body.
        int count = measureForwards ? buf.length : -buf.length;
        return breakText(buf, 0, count, maxWidth, measuredWidth);
    }

    /** AOSP Paint.getTextPath: "Returns the path (outline) for the specified
     *  text, offset to x,y" (frameworks/base graphics/java/android/graphics/
     *  Paint.java). This used to be a genuine no-op (`path.reset()` and
     *  nothing else, on EVERY call) rather than an approximation -- any
     *  caller tracing text as a vector shape (an outlined/stroked logo
     *  title, hit-testing a glyph, morphing text into another path) got a
     *  silently empty path with no error. AWT's Font.createGlyphVector +
     *  GlyphVector.getOutline(x,y) give the real glyph outline in exactly
     *  the coordinate space AOSP documents (offset to x,y already applied)
     *  -- the same FontRenderContext/GlyphVector machinery computeInkBounds()
     *  above already uses for getTextBounds(), just asking for the outline
     *  instead of its bounding box, and resolveFont() so the outline traces
     *  the same glyphs (custom Typeface included) that Canvas.drawText would
     *  actually paint. */
    private void computeTextPath(String text, float x, float y, android.graphics.Path path) {
        path.reset();
        if (text == null || text.isEmpty()) return;
        java.awt.Font f = resolveFont();
        if (f == null) return;
        try {
            // Same shared context as computeInkBounds, for the same reason: the
            // outline's glyph positions accumulate the measured advances.
            java.awt.font.GlyphVector gv =
                f.createGlyphVector(MacApkFont.textFrc(), text);
            java.awt.Shape outline = gv.getOutline(x, y);
            path.macapkAppendShape(outline);
        } catch (Throwable ignored) { /* leave path empty, the old stub's outcome on any failure */ }
    }
    public void getTextPath(String text, int start, int end, float x, float y, android.graphics.Path path) {
        if (path == null || text == null) return;
        if (start < 0 || end > text.length() || start > end) return;
        computeTextPath(text.substring(start, end), x, y, path);
    }
    public void getTextPath(char[] text, int index, int count, float x, float y, android.graphics.Path path) {
        if (path == null || text == null) return;
        if (index < 0 || count < 0 || index + count > text.length) return;
        computeTextPath(new String(text, index, count), x, y, path);
    }

    public String getFontVariationSettings() { return mFontVariationSettings; }
    public boolean setFontVariationSettings(String settings) { mFontVariationSettings = settings; return true; }
    private String mFontVariationSettings;
    // AOSP Paint cursor-movement options (Paint.java:329-359). Apps compiled
    // against the SDK inline these as constants, but a reflective or
    // non-inlining caller reads the field, so they have to exist.
    public static final int CURSOR_AFTER = 0;
    public static final int CURSOR_AT_OR_AFTER = 1;
    public static final int CURSOR_BEFORE = 2;
    public static final int CURSOR_AT_OR_BEFORE = 3;
    public static final int CURSOR_AT = 4;
    private static final int CURSOR_OPT_MAX_VALUE = CURSOR_AT;

    /** AOSP Paint.getTextRunCursor (Paint.java:2730 for the String form).
     *
     *  ⚠ NOTE FOR WHOEVER OWNS android/text/TextPaint.java: TextPaint
     *  OVERRIDES this method with the same descriptor, so for a TextPaint
     *  receiver -- which is what every text-layout caller actually holds --
     *  virtual dispatch reaches TextPaint's copy and NEVER this one. That
     *  override validates nothing and treats CURSOR_BEFORE (2) as a FORWARD
     *  move. Deleting it makes this implementation live; there is no reason
     *  for TextPaint to have its own (AOSP's TextPaint does not).
     *
     *  Two things were wrong here and are fixed: the validation expression
     *  (AOSP raises IndexOutOfBoundsException for a negative/limits-violating
     *  index or a cursorOpt above CURSOR_AT, we silently returned a number),
     *  and the direction table (2 is CURSOR_BEFORE, a BACKWARD move; we moved
     *  forward on it). "Valid cursor position" is a grapheme-cluster boundary
     *  in AOSP; java.text.BreakIterator's character instance computes the same
     *  UAX#29 boundaries, so it stands in for minikin here. */
    public int getTextRunCursor(CharSequence text, int contextStart, int contextEnd,
                                boolean isRtl, int offset, int cursorOpt) {
        if (((contextStart | contextEnd | offset | (contextEnd - contextStart)
                | (offset - contextStart) | (contextEnd - offset)
                | (text.length() - contextEnd) | cursorOpt) < 0)
                || cursorOpt > CURSOR_OPT_MAX_VALUE) {
            throw new IndexOutOfBoundsException();
        }
        java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance();
        it.setText(text.subSequence(contextStart, contextEnd).toString());
        final int rel = offset - contextStart;
        final boolean atBoundary = it.isBoundary(rel);
        switch (cursorOpt) {
            case CURSOR_AT:
                return atBoundary ? offset : -1;
            case CURSOR_AT_OR_AFTER:
                if (atBoundary) return offset;
                // fall through
            case CURSOR_AFTER: {
                int n = it.following(rel);
                return (n == java.text.BreakIterator.DONE) ? contextEnd : n + contextStart;
            }
            case CURSOR_AT_OR_BEFORE:
                if (atBoundary) return offset;
                // fall through
            case CURSOR_BEFORE:
            default: {
                int p = it.preceding(rel);
                return (p == java.text.BreakIterator.DONE) ? contextStart : p + contextStart;
            }
        }
    }

    // ================= AOSP API-36 surface added 2026-08-24 ==================
    // Everything below mirrors frameworks/base/graphics/java/android/graphics/Paint.java
    // (android16-release) and, where the behaviour lives in native code,
    // libs/hwui/jni/Paint.cpp + libs/hwui/hwui/Paint.h. Cited per method.

    /** AOSP Paint.getFillPath(src, dst) (Paint.java:1374) -> SkPaint::getFillPath.
     *  "Applies any/all effects (patheffect, stroking) to src, returning the
     *  result in dst. ... Returns true if the path should be filled, or false if
     *  it should be drawn with a hairline (width == 0)."
     *
     *  Our raster is java.awt, so the stroker is BasicStroke.createStrokedShape,
     *  which is the same mathematical operation Skia's stroker performs. The
     *  hairline case (width == 0 with a stroking style) is NOT stroked and
     *  returns false, exactly as Skia does. PathEffects are not applied -- we
     *  only model dashing, and only inside Canvas -- so a dashed paint yields the
     *  undashed outline; that is a known narrowing, flagged here rather than
     *  silently pretended. */
    public boolean getFillPath(android.graphics.Path src, android.graphics.Path dst) {
        if (src == null || dst == null) return true;
        Style st = getStyle();
        if (st == Style.FILL) {
            dst.set(src);
            return true;
        }
        float w = getStrokeWidth();
        if (w <= 0f) {
            // Hairline: Skia leaves the geometry alone and reports "do not fill".
            dst.set(src);
            return false;
        }
        int cap = java.awt.BasicStroke.CAP_BUTT;
        if (getStrokeCap() == Cap.ROUND)  cap = java.awt.BasicStroke.CAP_ROUND;
        if (getStrokeCap() == Cap.SQUARE) cap = java.awt.BasicStroke.CAP_SQUARE;
        int join = java.awt.BasicStroke.JOIN_MITER;
        if (getStrokeJoin() == Join.ROUND) join = java.awt.BasicStroke.JOIN_ROUND;
        if (getStrokeJoin() == Join.BEVEL) join = java.awt.BasicStroke.JOIN_BEVEL;
        java.awt.BasicStroke bs = new java.awt.BasicStroke(
                w, cap, join, Math.max(1f, getStrokeMiter()));
        java.awt.Shape outline = bs.createStrokedShape(src.macapkDrawShape());
        dst.macapkSetShape(outline);
        return true;
    }

    // ---- underline / strike-through geometry (API 29) ----
    //
    // libs/hwui/hwui/Paint.h:42-51 defines the fallbacks:
    //     kStdUnderline_Offset     =  1/9
    //     kStdUnderline_Thickness  =  1/18
    //     kStdUnderline_Top        =  kStdUnderline_Offset - 0.5*kStdUnderline_Thickness
    //     kStdStrikeThru_Offset    = -6/21
    //     kStdStrikeThru_Thickness =  kStdUnderline_Thickness
    //     kStdStrikeThru_Top       =  kStdStrikeThru_Offset - 0.5*kStdStrikeThru_Thickness
    // libs/hwui/jni/Paint.cpp:1075-1107: the UNDERLINE pair prefers the font's own
    // OS/2 values and falls back to the constants; the STRIKE-THROUGH pair ALWAYS
    // uses the constants and never consults the font. Mirrored exactly.
    private static final float kStdUnderline_Offset     =  1.0f / 9.0f;
    private static final float kStdUnderline_Thickness  =  1.0f / 18.0f;
    private static final float kStdUnderline_Top =
            kStdUnderline_Offset - 0.5f * kStdUnderline_Thickness;
    private static final float kStdStrikeThru_Offset    = -6.0f / 21.0f;
    private static final float kStdStrikeThru_Thickness = kStdUnderline_Thickness;
    private static final float kStdStrikeThru_Top =
            kStdStrikeThru_Offset - 0.5f * kStdStrikeThru_Thickness;

    /** java.awt's per-font underline metrics, or null when no font resolves. */
    private java.awt.font.LineMetrics macapkLineMetrics() {
        try {
            java.awt.Font f = resolveFont();
            if (f == null) return null;
            java.awt.font.FontRenderContext frc =
                    new java.awt.font.FontRenderContext(null, isAntiAlias(), true);
            return f.getLineMetrics("x", frc);
        } catch (Throwable t) { return null; }
    }

    /** AOSP Paint.getUnderlinePosition() -- distance from the baseline down to
     *  the TOP of the underline stroke. Positive means below the baseline, the
     *  same sign convention java.awt.font.LineMetrics uses. */
    public float getUnderlinePosition() {
        java.awt.font.LineMetrics lm = macapkLineMetrics();
        if (lm != null) {
            float p = lm.getUnderlineOffset();
            if (p != 0f && !Float.isNaN(p)) return p;
        }
        return kStdUnderline_Top * getTextSize();
    }

    /** AOSP Paint.getUnderlineThickness(). */
    public float getUnderlineThickness() {
        java.awt.font.LineMetrics lm = macapkLineMetrics();
        if (lm != null) {
            float t = lm.getUnderlineThickness();
            if (t > 0f && !Float.isNaN(t)) return t;
        }
        return kStdUnderline_Thickness * getTextSize();
    }

    /** AOSP Paint.getStrikeThruPosition(). Note hwui does NOT read the font
     *  here -- it is always the constant times the text size, so neither do we. */
    public float getStrikeThruPosition() {
        return kStdStrikeThru_Top * getTextSize();
    }

    /** AOSP Paint.getStrikeThruThickness(). Constant, as above. */
    public float getStrikeThruThickness() {
        return kStdStrikeThru_Thickness * getTextSize();
    }

    // ---- locale-aware font metrics (API 34) ----
    //
    // AOSP's pair passes useLocale=true to the same native getter, which widens
    // the metrics to cover the fallback fonts for the paint's text locale so a
    // line's height does not change when a fallback glyph appears. Our metrics
    // already come from one resolved AWT face and we do no per-locale fallback
    // union, so the locale-aware answer equals the plain one. That is a
    // NARROWING, not a lie: the contract is "at least as tall", and returning the
    // same values keeps the two APIs self-consistent instead of inventing a
    // padding factor.
    public void getFontMetricsForLocale(FontMetrics metrics) {
        getFontMetrics(metrics);
    }

    public void getFontMetricsIntForLocale(FontMetricsInt metrics) {
        getFontMetricsInt(metrics);
    }

    // ---- per-run font metrics (API 34) ----

    /** AOSP Paint.getFontMetricsInt(CharSequence,...) (Paint.java:2474). The
     *  argument checks are AOSP's, verbatim and in order -- they are observable
     *  and several are IllegalArgumentException where a reader would expect
     *  IndexOutOfBounds. count == 0 short-circuits to the plain getter. */
    public void getFontMetricsInt(CharSequence text, int start, int count,
            int contextStart, int contextCount, boolean isRtl, FontMetricsInt outMetrics) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (start < 0 || start >= text.length()) {
            throw new IllegalArgumentException("start argument is out of bounds.");
        }
        if (count < 0 || start + count > text.length()) {
            throw new IllegalArgumentException("count argument is out of bounds.");
        }
        if (contextStart < 0 || contextStart >= text.length()) {
            throw new IllegalArgumentException("ctxStart argument is out of bounds.");
        }
        if (contextCount < 0 || contextStart + contextCount > text.length()) {
            throw new IllegalArgumentException("ctxCount argument is out of bounds.");
        }
        if (outMetrics == null) {
            throw new IllegalArgumentException("outMetrics must not be null.");
        }
        // Per-run metrics differ from the paint's own only when a fallback font
        // is selected for some of the run. We resolve ONE face per paint, so the
        // run's metrics are the paint's metrics; see getFontMetricsForLocale.
        getFontMetricsInt(outMetrics);
    }

    /** AOSP Paint.getFontMetricsInt(char[],...) (Paint.java:2538). */
    public void getFontMetricsInt(char[] text, int start, int count,
            int contextStart, int contextCount, boolean isRtl, FontMetricsInt outMetrics) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (start < 0 || start >= text.length) {
            throw new IllegalArgumentException("start argument is out of bounds.");
        }
        if (count < 0 || start + count > text.length) {
            throw new IllegalArgumentException("count argument is out of bounds.");
        }
        if (contextStart < 0 || contextStart >= text.length) {
            throw new IllegalArgumentException("ctxStart argument is out of bounds.");
        }
        if (contextCount < 0 || contextStart + contextCount > text.length) {
            throw new IllegalArgumentException("ctxCount argument is out of bounds.");
        }
        if (outMetrics == null) {
            throw new IllegalArgumentException("outMetrics must not be null.");
        }
        getFontMetricsInt(outMetrics);
    }

    // ---- per-character advances ----

    /** AOSP Paint.getTextRunAdvances(char[], ...) (Paint.java:3171). Writes one
     *  advance per char into `advances` starting at `advancesIndex` and returns
     *  the run's total advance. The bounds expression is AOSP's, verbatim: it is
     *  a single OR-of-differences and it throws IndexOutOfBoundsException. */
    public float getTextRunAdvances(char[] chars, int index, int count,
            int contextIndex, int contextCount, boolean isRtl,
            float[] advances, int advancesIndex) {
        if (chars == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((index | count | contextIndex | contextCount | advancesIndex
                | (index - contextIndex) | (contextCount - count)
                | ((contextIndex + contextCount) - (index + count))
                | (chars.length - (contextIndex + contextCount))
                | (advances == null ? 0
                    : (advances.length - (advancesIndex + count)))) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (chars.length == 0 || count == 0) {
            return 0f;
        }
        float[] adv = macapkCharAdvances(new String(chars, index, count));
        float total = 0f;
        for (int i = 0; i < count; i++) {
            float a = i < adv.length ? adv[i] : 0f;
            if (advances != null) advances[advancesIndex + i] = a;
            total += a;
        }
        return total;
    }

    /** AOSP Paint.getRunCharacterAdvance(char[], ...) (Paint.java:3580). Returns
     *  the advance of [start, offset) and, when `advances` is non-null, fills in
     *  the per-character advances for [start, end) at advancesIndex. The four
     *  range checks and their messages are AOSP's. */
    public float getRunCharacterAdvance(char[] text, int start, int end, int contextStart,
            int contextEnd, boolean isRtl, int offset, float[] advances, int advancesIndex) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (contextStart < 0 || contextEnd > text.length) {
            throw new IndexOutOfBoundsException("Invalid Context Range: " + contextStart + ", "
                    + contextEnd + " must be in 0, " + text.length);
        }
        if (start < contextStart || contextEnd < end) {
            throw new IndexOutOfBoundsException("Invalid start/end range: " + start + ", " + end
                    + " must be in " + contextStart + ", " + contextEnd);
        }
        if (offset < start || end < offset) {
            throw new IndexOutOfBoundsException("Invalid offset position: " + offset
                    + " must be in " + start + ", " + end);
        }
        if (advances != null && advances.length < advancesIndex - start + end) {
            throw new IndexOutOfBoundsException("Given array doesn't have enough space to receive "
                    + "the result, advances.length: " + advances.length + " advanceIndex: "
                    + advancesIndex + " needed space: " + (offset - start));
        }
        if (end == start) {
            return 0.0f;
        }
        float[] adv = macapkCharAdvances(new String(text, start, end - start));
        float upToOffset = 0f;
        for (int i = 0; i < end - start; i++) {
            float a = i < adv.length ? adv[i] : 0f;
            if (advances != null) advances[advancesIndex + i] = a;
            if (start + i < offset) upToOffset += a;
        }
        return upToOffset;
    }

    /** AOSP Paint.getRunCharacterAdvance(CharSequence, ...) (Paint.java:3667). */
    public float getRunCharacterAdvance(CharSequence text, int start, int end, int contextStart,
            int contextEnd, boolean isRtl, int offset, float[] advances, int advancesIndex) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (contextStart < 0 || contextEnd > text.length()) {
            throw new IndexOutOfBoundsException("Invalid Context Range: " + contextStart + ", "
                    + contextEnd + " must be in 0, " + text.length());
        }
        if (start < contextStart || contextEnd < end) {
            throw new IndexOutOfBoundsException("Invalid start/end range: " + start + ", " + end
                    + " must be in " + contextStart + ", " + contextEnd);
        }
        if (offset < start || end < offset) {
            throw new IndexOutOfBoundsException("Invalid offset position: " + offset
                    + " must be in " + start + ", " + end);
        }
        if (advances != null && advances.length < advancesIndex - start + end) {
            throw new IndexOutOfBoundsException("Given array doesn't have enough space to receive "
                    + "the result, advances.length: " + advances.length + " advanceIndex: "
                    + advancesIndex + " needed space: " + (offset - start));
        }
        if (end == start) {
            return 0.0f;
        }
        char[] buf = text.subSequence(contextStart, contextEnd).toString().toCharArray();
        return getRunCharacterAdvance(buf, start - contextStart, end - contextStart, 0,
                contextEnd - contextStart, isRtl, offset - contextStart,
                advances, advancesIndex);
    }

    // ---- font-variation override (API 36) ----
    //
    // AOSP Paint.java:2201/2240. The override is a SEPARATE slot from
    // setFontVariationSettings: the settings string is the app's request, the
    // override is what the framework forces on top of it (used by the
    // typeface-redesign path). We have no variable-font shaper, so both slots
    // store and read back; keeping them separate is what makes the getters
    // answer what the app set, which is the observable half.
    private String mFontVariationOverride;

    /** AOSP Paint.setFontVariationOverride(String). Void, and a no-op when the
     *  value is unchanged. */
    public void setFontVariationOverride(String fontVariationOverride) {
        if (java.util.Objects.equals(fontVariationOverride, mFontVariationOverride)) {
            return;
        }
        // AOSP parses through FontVariationAxis.fromFontVariationSettingsForList,
        // which THROWS on a malformed string. Do the same parse so a bad value
        // fails here rather than being silently stored.
        android.graphics.fonts.FontVariationAxis.fromFontVariationSettings(
                fontVariationOverride);
        mFontVariationOverride = fontVariationOverride;
    }

    /** AOSP Paint.getFontVariationOverride(). */
    public String getFontVariationOverride() { return mFontVariationOverride; }
}
