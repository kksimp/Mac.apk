// AOSP `Layout` -- base for text-rendering layouts (StaticLayout,
// BoringLayout, DynamicLayout). Compose UI's AndroidParagraph and the
// classic TextView both build one to measure + draw multi-line text.
//
// v1.0.394: REAL greedy word-wrap. The previous stub reported
// getLineCount()==1 and drew the whole string on one baseline, so any
// text wider than its constraint (e.g. F-Droid's "android is under
// threat" Compose banner) ran off the edge instead of wrapping. We now
// break the text into lines at `width` using paint.measureText (break at
// the last space that fits; hard-split a single over-wide word), honor
// explicit '\n', and expose AOSP-shape per-line geometry so Compose's
// measure (height/lineCount/lineTop/baseline) and draw both wrap.
package android.text;

public abstract class Layout {
    public enum Alignment {
        ALIGN_NORMAL,
        ALIGN_OPPOSITE,
        ALIGN_CENTER,
        ALIGN_LEFT,
        ALIGN_RIGHT,
    }

    // AOSP Layout.java:150-205 (android16-release). Compile-time constants, so
    // javac inlines them at the call site and a missing one is invisible at
    // runtime -- but they must exist for anything WE compile against this
    // class, and getBreakStrategy()/getHyphenationFrequency()/
    // getJustificationMode() below are documented in terms of them.
    public static final int BREAK_STRATEGY_SIMPLE = 0;
    public static final int BREAK_STRATEGY_HIGH_QUALITY = 1;
    public static final int BREAK_STRATEGY_BALANCED = 2;

    public static final int HYPHENATION_FREQUENCY_NONE = 0;
    public static final int HYPHENATION_FREQUENCY_NORMAL = 1;
    public static final int HYPHENATION_FREQUENCY_FULL = 2;
    public static final int HYPHENATION_FREQUENCY_NORMAL_FAST = 3;
    public static final int HYPHENATION_FREQUENCY_FULL_FAST = 4;

    public static final int JUSTIFICATION_MODE_NONE = 0;
    public static final int JUSTIFICATION_MODE_INTER_WORD = 1;
    public static final int JUSTIFICATION_MODE_INTER_CHARACTER = 2;

    /** AOSP Layout.java:200-205. */
    public static final float DEFAULT_LINESPACING_MULTIPLIER = 1.0f;
    public static final float DEFAULT_LINESPACING_ADDITION = 0.0f;

    // NOT final: AOSP's Layout.replaceWith(...) (Layout.java:410-425) reassigns
    // all six of these in place, which is what makes
    // BoringLayout.replaceOrMake() able to return `this`. See
    // macapkReplaceWith() below. Nothing else in this tree writes them.
    protected CharSequence text;
    protected android.text.TextPaint paint;
    // NOT final: AOSP Layout.increaseWidthTo(int) (Layout.java:1259-1265)
    // assigns mWidth. Everything else in this class still treats it as
    // read-only.
    protected int width;
    protected Alignment alignment;
    protected float spacingMult;
    protected float spacingAdd;

    // Lazily-computed line breaks: lineStarts[k] = start offset of line k;
    // line k spans [lineStarts[k], lineStarts[k+1]) (last line ends at len).
    private int[] lineStarts;
    // Per-line vertical geometry, in the exact shape AOSP's StaticLayout
    // stores it (StaticLayout.java:1044-1049): lineTops has lineCount + 1
    // entries and the last IS the total height; lineAscents[k] is AOSP's
    // `above` for line k, negative like Paint.ascent(). Everything else --
    // bottom, baseline, descent, height -- is DERIVED from those two, which
    // is what makes the AOSP identities (Layout.java:897, :1301, :1313, :1321)
    // hold by construction instead of by three independent roundings agreeing.
    //
    // These used to be materialized only when a LineHeightSpan asked for a
    // different height, with a "uniform pitch" fast path alongside; the two
    // paths were separate arithmetic and only one of them could implement
    // includeFontPadding. Now there is one path.
    private int[] lineTops;
    private int[] lineAscents;
    private boolean lineHeightSpansApplied;
    // AOSP's StaticLayout.mTopPadding / mBottomPadding: how far the first
    // line's ascent was widened to `top` and the last line's descent to
    // `bottom` (StaticLayout.java:998-1017). Zero unless includePad.
    private int topPadding, bottomPadding;
    // StaticLayout's `includepad`, which AOSP hands to generate() as BOTH
    // includepad and trackPad (StaticLayout.java:545,
    // `generate(b, b.mIncludePad, b.mIncludePad)`) -- so the padding is
    // tracked exactly when it is applied.
    //
    // Defaults FALSE at this base class on purpose: BoringLayout and
    // DynamicLayout construct through the 6-arg protected constructor and
    // never asked for padding, so their geometry stays exactly as it was.
    // StaticLayout sets it explicitly (its own AOSP default is true).
    private boolean includePad;
    // AOSP StaticLayout.Builder's useLineSpacingFromFallbacks / the per-
    // instance mFallbackLineSpacing it is threaded into (android13-release
    // core/java/android/text/StaticLayout.java:449 Builder field, :651
    // ctor-copy, :1435 instance field). Shared on the base Layout, unlike
    // AOSP's own per-subclass private fields, because our StaticLayout AND
    // BoringLayout both need it and this tree already threads includePad
    // the same shared-field way (comment above). Defaults false, matching
    // both AOSP's own base Layout.isFallbackLineSpacingEnabled() (Layout.
    // java:968, "return false") AND Builder.obtain()'s explicit
    // `b.mFallbackLineSpacing = false` (StaticLayout.java:108) -- a caller
    // must opt in, same as includePad's own "off unless asked" shape.
    private boolean useFallbackLineSpacing;

    // ---- Builder parameters AOSP records on the Layout and exposes through
    // final getters (Layout.java:2400-2520, android16-release). Every one of
    // these getters was missing; the StaticLayout/DynamicLayout Builders in
    // this tree already COLLECTED breakStrategy / hyphenationFrequency /
    // justificationMode and then dropped them on the floor, so
    // getBreakStrategy() had nothing to answer with either. Defaults below are
    // AOSP's own Builder defaults.
    private int breakStrategy = BREAK_STRATEGY_SIMPLE;
    private int hyphenationFrequency = HYPHENATION_FREQUENCY_NONE;
    private int justificationMode = JUSTIFICATION_MODE_NONE;
    private int[] leftIndents;
    private int[] rightIndents;
    private boolean useBoundsForWidth;
    private boolean shiftDrawingOffsetForStartOverhang;
    private android.graphics.Paint.FontMetrics minimumFontMetrics;
    private android.graphics.text.LineBreakConfig lineBreakConfig =
            android.graphics.text.LineBreakConfig.NONE;

    /** Subclass hook for the StaticLayout/DynamicLayout Builder parameters
     *  above. Not AOSP API -- AOSP passes them through the package-private
     *  Layout constructor that our Builders do not use. None of them changes
     *  the line breaking we actually perform, so this does NOT invalidate the
     *  cached lines; see the individual getters for what each one is (and is
     *  not) honoured for. */
    protected void macapkSetBuilderParams(int breakStrategy, int hyphenationFrequency,
            int justificationMode, int[] leftIndents, int[] rightIndents,
            boolean useBoundsForWidth, boolean shiftDrawingOffsetForStartOverhang,
            android.graphics.Paint.FontMetrics minimumFontMetrics,
            android.graphics.text.LineBreakConfig lineBreakConfig) {
        this.breakStrategy = breakStrategy;
        this.hyphenationFrequency = hyphenationFrequency;
        this.justificationMode = justificationMode;
        this.leftIndents = leftIndents;
        this.rightIndents = rightIndents;
        this.useBoundsForWidth = useBoundsForWidth;
        this.shiftDrawingOffsetForStartOverhang = shiftDrawingOffsetForStartOverhang;
        this.minimumFontMetrics = minimumFontMetrics;
        if (lineBreakConfig != null) this.lineBreakConfig = lineBreakConfig;
    }
    // AOSP Layout.mTextDir (Layout.java:2554), set through the 7-arg
    // protected constructor and read by getParagraphDirection.
    private android.text.TextDirectionHeuristic textDir
        = android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR;

    // v1.0.46x: maxLines / ellipsize support. AOSP StaticLayout truncates
    // the broken lines to at most maxLines and, when an ellipsize mode is
    // set, replaces the overflowing tail of the last visible line with an
    // ellipsis. The base Layout owns these so StaticLayout (and any future
    // DynamicLayout) can drive them through configure(); the default
    // (maxLines == MAX_VALUE, ellipsize == null) preserves the prior
    // full-text behavior for every existing caller.
    private int maxLines = Integer.MAX_VALUE;
    private android.text.TextUtils.TruncateAt ellipsizeMode;
    // BISECT GATE, shared by name with android.widget.TextView:
    // MACAPK_TEXT_LINES_AOSP=0 restores the pre-gate line breaking and
    // maxLines cut (every multi-line ellipsize mode cut the layout; a trailing
    // space counted against the width). Text layout touches every app.
    static final boolean MACAPK_TEXT_LINES_AOSP =
        !"0".equals(System.getenv("MACAPK_TEXT_LINES_AOSP"));
    // v1.0.2239: before splitting a word that overflows, ask whether the WHOLE
    // word fits when shaped as one run. MACAPK_TEXT_WORD_FIT=0 restores the
    // prefix-only test (an icon-font ligature word wraps one letter per line).
    static final boolean MACAPK_TEXT_WORD_FIT =
        !"0".equals(System.getenv("MACAPK_TEXT_WORD_FIT"));
    private int ellipsizedWidth;
    // Per-line ellipsis geometry (AOSP getEllipsisStart/getEllipsisCount).
    private int[] ellipsisStart;
    private int[] ellipsisCount;
    private CharSequence[] ellipsisLineText;

    /** Subclass hook: install maxLines + ellipsize before any line query.
     *  Mirrors StaticLayout.Builder.setMaxLines/setEllipsize semantics. */
    protected void configure(int maxLines, android.text.TextUtils.TruncateAt ellipsize,
                             int ellipsizedWidth) {
        this.maxLines = (maxLines <= 0) ? Integer.MAX_VALUE : maxLines;
        this.ellipsizeMode = ellipsize;
        this.ellipsizedWidth = ellipsizedWidth > 0 ? ellipsizedWidth : width;
        // Force a re-break with the new constraints. The per-line span memo is
        // keyed to the OLD line breaks, so it has to be invalidated with them
        // or a re-broken layout keeps the previous line tops and every
        // baseline lands on the wrong row.
        invalidateLines();
    }

    protected Layout(CharSequence text, android.text.TextPaint paint,
                      int width, Alignment align,
                      float spacingMult, float spacingAdd) {
        // AOSP Layout.java:226 delegates to the textDir form with
        // FIRSTSTRONG_LTR, which is also the Builder's default.
        this(text, paint, width, align,
            android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR, spacingMult, spacingAdd);
    }

    /** AOSP Layout.java:248 -- the constructor that carries the paragraph
     *  direction heuristic. getParagraphDirection() cannot answer anything but
     *  a hardcoded LTR without it. */
    protected Layout(CharSequence text, android.text.TextPaint paint,
                      int width, Alignment align,
                      android.text.TextDirectionHeuristic textDir,
                      float spacingMult, float spacingAdd) {
        this.text = text;
        this.paint = paint;
        this.width = width;
        this.alignment = align;
        this.spacingMult = spacingMult;
        this.spacingAdd = spacingAdd;
        if (textDir != null) this.textDir = textDir;
    }

    /** AOSP Layout.java:832. */
    public final android.text.TextDirectionHeuristic getTextDirectionHeuristic() {
        return textDir;
    }

    /** Subclass hook (not AOSP API -- AOSP threads these through the
     *  StaticLayout.Builder that this base class stands in for). Lines are
     *  generated lazily, so a subclass constructor may call this after super()
     *  and before any line query. */
    protected void macapkSetTextDirection(android.text.TextDirectionHeuristic dir) {
        if (dir != null && dir != this.textDir) {
            this.textDir = dir;
            invalidateLines();
        }
    }

    /** Subclass hook for StaticLayout's `includepad`. See the field comment. */
    protected void macapkSetIncludePad(boolean v) {
        if (v != this.includePad) {
            this.includePad = v;
            invalidateLines();
        }
    }

    /** Subclass hook for StaticLayout/BoringLayout's `useLineSpacingFromFallbacks`
     *  (StaticLayout.Builder) / `useFallbackLineSpacing` (BoringLayout's 11-arg
     *  ctor). See the field comment. */
    protected void macapkSetUseFallbackLineSpacing(boolean v) {
        if (v != this.useFallbackLineSpacing) {
            this.useFallbackLineSpacing = v;
            invalidateLines();
        }
    }

    /**
     * AOSP Layout.replaceWith(CharSequence, TextPaint, int, Alignment, float,
     * float) (Layout.java:410-425) -- rebind this Layout to new text and
     * parameters IN PLACE, discarding the cached line breaks. AOSP guards the
     * width:
     *     if (width < 0) throw new IllegalArgumentException("Layout: " + width + " < 0");
     * It is package-private there and reached only through
     * BoringLayout.replaceOrMake(); same here.
     */
    void macapkReplaceWith(CharSequence text, android.text.TextPaint paint, int width,
            Alignment align, float spacingmult, float spacingadd) {
        if (width < 0) {
            throw new IllegalArgumentException("Layout: " + width + " < 0");
        }
        this.text = text;
        this.paint = paint;
        this.width = width;
        this.alignment = align;
        this.spacingMult = spacingmult;
        this.spacingAdd = spacingadd;
        invalidateLines();
    }

    private void invalidateLines() {
        this.lineStarts = null;
        this.lineHeightSpansApplied = false;
        this.lineTops = null;
        this.lineAscents = null;
        // The has-metric-spans answer is only valid for one layout pass: a
        // SpannableStringBuilder can gain a span without its identity
        // changing, and a stale "no spans" answer would silently put us back
        // on the span-blind measure this whole helper exists to remove.
        this.mMetricSpanKey = null;
    }

    public CharSequence getText() { return text; }
    public android.text.TextPaint getPaint() { return paint; }
    public int getWidth() { return width; }
    public Alignment getAlignment() { return alignment; }
    public float getSpacingMultiplier() { return spacingMult; }
    public float getSpacingAdd() { return spacingAdd; }

    // ---- span-aware measurement (AOSP parity) --------------------------
    //
    // WHY THIS EXISTS. Every measuring site in this file used to call
    // `paint.measureText(<plain String>)`, i.e. the BASE paint with no span
    // walk, while draw() -> drawSpannedLine() applies each CharacterStyle
    // before painting. So a view could be SIZED from one font and size and
    // PAINTED in another, and the difference showed up as text truncated
    // mid-word at the right edge. Measured on Instagram: its onboarding text
    // is a Spannable carrying TypefaceSpan (a font from the APK's assets),
    // AbsoluteSizeSpan and a letter-spacing MetricAffectingSpan, while the
    // base TextPaint came from the theme's textAppearance -- so we broke
    // lines in Roboto at the theme size and painted them in the app's font at
    // the span's size. The button label "Continue" was sized to the Roboto
    // measure (103px) and painted as "Continu" (101px) plus a clipped sliver.
    //
    // AOSP cannot diverge here, and that is the whole point:
    //   MeasuredParagraph.buildForStaticLayout (android13-release, :447-466)
    //   walks nextSpanTransition(..., MetricAffectingSpan.class) and calls
    //   applyMetricsAffectingSpan, which does
    //       mCachedPaint.set(paint); ... span.updateMeasureState(mCachedPaint);
    //   before measuring; and TextLine.handleRun does `wp.set(mPaint)` and
    //   then MEASURES AND DRAWS with that same work paint.
    //
    // The tell that we had it wrong was a one-liner: `grep MetricAffectingSpan`
    // over this file and TextView.java returned NOTHING, while AOSP's entire
    // measurement design is built on that class.
    //
    // Full write-up: docs/text-measure-vs-paint-divergence_2026-09-21.md
    private android.text.TextPaint mWorkPaint;   // reused; never handed out

    /**
     * Width of src[start,end) with every MetricAffectingSpan applied, the way
     * AOSP measures it. Falls back to a plain measure when the text carries no
     * spans, so the unspanned path keeps its previous behaviour exactly.
     */
    float macapkMeasure(CharSequence src, int start, int end) {
        if (paint == null || src == null || start >= end) return 0f;
        // FAST PATH, and it matters: ensureLines() measures a growing prefix
        // once PER CHARACTER, so anything done here is done O(n) times per
        // line. When the text carries no MetricAffectingSpan at all -- the
        // overwhelmingly common case -- skip the work paint copy and the
        // getSpans allocation entirely and measure exactly as before.
        if (!hasMetricSpans(src)) return paint.measureText(src, start, end);
        if (mWorkPaint == null) mWorkPaint = new android.text.TextPaint();
        return macapkMeasureSpanned(src, start, end, paint, mWorkPaint);
    }

    /** Cached "does this text carry any MetricAffectingSpan at all". Keyed on
     *  identity so a replaced CharSequence recomputes (macapkReplaceWith). */
    private CharSequence mMetricSpanKey;
    private boolean mMetricSpanFlag;
    private boolean hasMetricSpans(CharSequence src) {
        if (!(src instanceof android.text.Spanned)) return false;
        if (src == mMetricSpanKey) return mMetricSpanFlag;
        boolean has = false;
        try {
            android.text.style.MetricAffectingSpan[] s =
                ((android.text.Spanned) src).getSpans(0, src.length(),
                    android.text.style.MetricAffectingSpan.class);
            has = (s != null && s.length > 0);
        } catch (Throwable ignored) { }
        mMetricSpanKey = src;
        mMetricSpanFlag = has;
        return has;
    }

    /**
     * Shared by Layout and TextView. `work` is a scratch paint owned by the
     * caller; it is overwritten on every call and must never be the base.
     */
    public static float macapkMeasureSpanned(CharSequence src, int start, int end,
                                             android.text.TextPaint base,
                                             android.text.TextPaint work) {
        if (base == null || src == null || start >= end) return 0f;
        if (!(src instanceof android.text.Spanned)) {
            return base.measureText(src, start, end);
        }
        final android.text.Spanned sp = (android.text.Spanned) src;
        if (work == null) work = new android.text.TextPaint();
        float total = 0f;
        int runStart = start;
        while (runStart < end) {
            int runEnd = sp.nextSpanTransition(runStart, end,
                    android.text.style.MetricAffectingSpan.class);
            if (runEnd <= runStart) runEnd = end;          // never fail to advance
            work.set(base);
            android.text.style.MetricAffectingSpan[] spans =
                    sp.getSpans(runStart, runEnd, android.text.style.MetricAffectingSpan.class);
            // AOSP takes the LAST ReplacementSpan covering the run and lets it
            // size the whole run, ignoring the other metric spans there
            // (MeasuredParagraph.applyReplacementRun). Mirror that.
            android.text.style.ReplacementSpan replacement = null;
            if (spans != null) {
                for (android.text.style.MetricAffectingSpan s : spans) {
                    if (s == null) continue;
                    android.text.style.MetricAffectingSpan u = s.getUnderlying();
                    if (u instanceof android.text.style.ReplacementSpan) {
                        replacement = (android.text.style.ReplacementSpan) u;
                    }
                }
                if (replacement == null) {
                    for (android.text.style.MetricAffectingSpan s : spans) {
                        if (s == null) continue;
                        try {
                            s.updateMeasureState(work);
                        } catch (Throwable t) {
                            // A guest span that throws must not take the whole
                            // layout down; AOSP would propagate, but a broken
                            // measure is recoverable and a dead activity is not.
                        }
                    }
                }
            }
            if (replacement != null) {
                try {
                    total += replacement.getSize(work, src, runStart, runEnd, null);
                } catch (Throwable t) {
                    total += work.measureText(src, runStart, runEnd);
                }
            } else {
                total += work.measureText(src, runStart, runEnd);
            }
            runStart = runEnd;
        }
        return total;
    }

    /** Scratch paint for the VERTICAL span walk. Separate from mWorkPaint so a
     *  measure in flight can never have its work paint stamped on underneath
     *  it -- computeLineGeometry runs inside ensureLines, which is exactly
     *  where macapkMeasure is also active. */
    private android.text.TextPaint mVertWorkPaint;

    /**
     * Widen a line's vertical metrics for the MetricAffectingSpans covering it.
     * `m` is {above, below, top, bottom} from the BASE paint; the return widens
     * it to the union over the line's runs. Ascent/top are NEGATIVE (above the
     * baseline), so widening means taking the MINIMUM of those and the MAXIMUM
     * of descent/bottom.
     *
     * WHY THIS EXISTS. computeLineGeometry read `paint.getFontMetricsInt` ONCE,
     * from the base paint, and reused that single ascent/descent for every
     * line. So a line whose text sets its size through a span got a line box
     * sized for the theme's base font while draw() painted the glyphs at the
     * span's larger size, and the glyph TOPS were clipped by the box. Measured
     * on Instagram's onboarding heading: the RCTextView was 41px tall with its
     * top at y=64, the baseline sat at y=91 (so 27px of ascent), and the real
     * font needed ~35px -- the ink began with a hard 0->56 jump at exactly the
     * view's top edge, the M's apexes were sliced flat and the T in "Terms"
     * lost its entire crossbar, leaving "lerms".
     *
     * This is the VERTICAL twin of macapkMeasureSpanned, which fixed the same
     * span-blindness horizontally in v1.0.1601/1602. AOSP cannot diverge here
     * either: MeasuredParagraph applies each MetricAffectingSpan to a work
     * paint and StaticLayout takes fm.ascent = min / fm.descent = max across
     * the line's runs.
     *
     * Full write-up: docs/text-measure-vs-paint-divergence_2026-09-21.md
     */
    private int[] macapkSpanVertical(int start, int end, int[] m) {
        if (paint == null || start >= end) return m;
        // Same fast path as macapkMeasure: no metric spans, nothing to widen,
        // and the base metrics are returned untouched so an unspanned app's
        // line geometry is bit-identical to before.
        if (!hasMetricSpans(text)) return m;
        final android.text.Spanned sp = (android.text.Spanned) text;
        if (mVertWorkPaint == null) mVertWorkPaint = new android.text.TextPaint();
        int above = m[0], below = m[1], top = m[2], bottom = m[3];
        final android.graphics.Paint.FontMetricsInt fm =
            new android.graphics.Paint.FontMetricsInt();
        int runStart = start;
        while (runStart < end) {
            int runEnd = sp.nextSpanTransition(runStart, end,
                    android.text.style.MetricAffectingSpan.class);
            if (runEnd <= runStart) runEnd = end;      // never fail to advance
            mVertWorkPaint.set(paint);
            android.text.style.MetricAffectingSpan[] spans =
                sp.getSpans(runStart, runEnd, android.text.style.MetricAffectingSpan.class);
            android.text.style.ReplacementSpan replacement = null;
            if (spans != null) {
                for (android.text.style.MetricAffectingSpan s : spans) {
                    if (s == null) continue;
                    android.text.style.MetricAffectingSpan u = s.getUnderlying();
                    if (u instanceof android.text.style.ReplacementSpan) {
                        replacement = (android.text.style.ReplacementSpan) u;
                    }
                }
                if (replacement == null) {
                    for (android.text.style.MetricAffectingSpan s : spans) {
                        if (s == null) continue;
                        try { s.updateMeasureState(mVertWorkPaint); }
                        catch (Throwable ignored) { }
                    }
                }
            }
            if (replacement != null) {
                // AOSP hands a ReplacementSpan the CURRENT metrics and lets
                // getSize overwrite them (Layout/TextLine do the same), so seed
                // fm before the call rather than after.
                fm.top = top; fm.ascent = above; fm.descent = below; fm.bottom = bottom;
                try { replacement.getSize(mVertWorkPaint, text, runStart, runEnd, fm); }
                catch (Throwable t) { mVertWorkPaint.getFontMetricsInt(fm); }
            } else {
                mVertWorkPaint.getFontMetricsInt(fm);
            }
            if (fm.ascent  < above)  above  = fm.ascent;
            if (fm.descent > below)  below  = fm.descent;
            if (fm.top     < top)    top    = fm.top;
            if (fm.bottom  > bottom) bottom = fm.bottom;
            runStart = runEnd;
        }
        return new int[]{above, below, top, bottom};
    }

    // ---- line breaking -------------------------------------------------
    private void ensureLines() {
        if (lineStarts != null) return;
        final String s = (text == null) ? "" : text.toString();
        final int n = s.length();
        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        starts.add(0);
        if (paint == null || width <= 0 || n == 0) {
            lineStarts = new int[]{0};
            computeLineGeometry();
            return;
        }
        // Real Unicode line-break opportunities (UAX#14, via the JDK's
        // rule-based BreakIterator) instead of only the literal ASCII
        // space: hyphens, dashes, CJK ideograph boundaries, zero-width
        // space etc all become legal wrap points, matching AOSP LineBreaker's
        // break-opportunity set even under the bare-default BREAK_STRATEGY_
        // SIMPLE greedy strategy (StaticLayout.Builder's own un-configured
        // default per AOSP source -- break-opportunity detection is a
        // separate concern from break STRATEGY, which only governs how
        // greedily/optimally the available opportunities get chosen).
        // Boundary values from BreakIterator are already "legal start of
        // next line" offsets (the position right after any trailing
        // whitespace/hyphen of the previous word), so no +1 translation is
        // needed the way the old literal-space index did.
        java.text.BreakIterator lineBreakIter = java.text.BreakIterator.getLineInstance(paint.getTextLocale());
        lineBreakIter.setText(s);
        int[] breakPositions;
        {
            java.util.ArrayList<Integer> bp = new java.util.ArrayList<>();
            for (int b = lineBreakIter.first(); b != java.text.BreakIterator.DONE; b = lineBreakIter.next()) {
                bp.add(b);
            }
            breakPositions = new int[bp.size()];
            for (int k = 0; k < breakPositions.length; k++) breakPositions[k] = bp.get(k);
        }
        int lineBegin = 0;
        int lastBreak = -1;   // last legal break-opportunity boundary on the current line
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {              // hard break
                starts.add(i + 1);
                lineBegin = i + 1; lastBreak = -1; i++;
                continue;
            }
            // Does [lineBegin, i] still fit?
            // Measure by OFFSET against the original CharSequence, not against
            // `s`, so MetricAffectingSpans resolve. `s` is text.toString(), so
            // the offsets are identical.
            // A line-end space HANGS rather than overflowing: minikin's greedy
            // breaker does not count it in the line's width (LineBreakerUtil.h
            // :200-211, "If we break a line on a line-ending space, that space
            // goes away"; isLineEndSpace :47-56). So "Encrypted container"
            // stays one line in a column its ink fits, as on a device.
            if (i > lineBegin
                    && !(MACAPK_TEXT_LINES_AOSP && isLineEndSpace(c))
                    && macapkMeasure(text != null ? text : s, lineBegin, i + 1) > width) {
                // A desperate (mid-word) break, but only if the word really does
                // not fit. minikin's greedy breaker measures a WORD from the
                // advances of the whole shaped run (LineBreaker.cpp; MeasuredText
                // shapes the paragraph once and attributes a ligature's advance to
                // its first character), never from a re-shaped PREFIX. The two only
                // differ when shaping the whole word makes it narrower than one of
                // its prefixes, i.e. a ligature or kerning: Material Symbols'
                // "light_mode" is one 1em rlig glyph, while its prefix "li" is two
                // 1em letters, so the prefix test wrapped every icon one character
                // per line and only the first letter survived maxLines=1 (Rhythm,
                // issue #11). For text with no such shrink the whole word is at least
                // as wide as the overflowing prefix, so this changes nothing.
                if (MACAPK_TEXT_WORD_FIT && lastBreak <= lineBegin) {
                    int wordEnd = n;
                    for (int bp : breakPositions) {
                        if (bp > i + 1) { wordEnd = bp; break; }
                    }
                    int we = wordEnd;
                    while (we > i + 1 && (isLineEndSpace(s.charAt(we - 1))
                                          || s.charAt(we - 1) == '\n')) we--;
                    if (we > i + 1
                            && macapkMeasure(text != null ? text : s, lineBegin, we) <= width) {
                        i = we;
                        if (java.util.Arrays.binarySearch(breakPositions, we) >= 0) lastBreak = we;
                        continue;
                    }
                }
                int breakAt;
                if (lastBreak > lineBegin) {
                    breakAt = lastBreak;            // wrap at the last break opportunity
                } else {
                    breakAt = (i > lineBegin) ? i : lineBegin + 1;  // split long word
                }
                if (breakAt <= lineBegin) breakAt = lineBegin + 1;  // always advance
                starts.add(breakAt);
                lineBegin = breakAt; lastBreak = -1; i = breakAt;
                continue;
            }
            if (java.util.Arrays.binarySearch(breakPositions, i + 1) >= 0) lastBreak = i + 1;
            i++;
        }
        lineStarts = new int[starts.size()];
        for (int k = 0; k < lineStarts.length; k++) lineStarts[k] = starts.get(k);
        applyMaxLinesAndEllipsize(s);
        computeLineGeometry();
    }

    // {ascent, descent} for a sized fallback java.awt.Font, AOSP-signed
    // (ascent negative) and rounded exactly as Paint's own ascentInt()/
    // descentInt() round the primary typeface -- Math.round of the float
    // java.awt.font.LineMetrics, not the coarser java.awt.FontMetrics int
    // API (see Paint.java's ascent()/descent() header for why). Mirrors
    // TextView.macapkFallbackFontVertical (widget/TextView.java) exactly;
    // a SEPARATE cache because the two classes cannot share a private
    // static field without editing TextView.java, which this fix does not
    // do. Bounded the same way TextView's own cache is: the fallback chain
    // is a handful of bundled faces at whatever sizes this app's text uses.
    private static final java.util.concurrent.ConcurrentHashMap<java.awt.Font, int[]>
        sFallbackVerticalCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static int[] macapkFallbackFontVertical(java.awt.Font sized) {
        if (sized == null) return null;
        int[] v = sFallbackVerticalCache.get(sized);
        if (v == null) {
            try {
                java.awt.font.LineMetrics lm =
                    sized.getLineMetrics("Ag", android.graphics.MacApkFont.textFrc());
                v = new int[] { Math.round(-lm.getAscent()), Math.round(lm.getDescent()) };
            } catch (Throwable t) { return null; }
            sFallbackVerticalCache.put(sized, v);
        }
        return v;
    }

    /** `base` ({ascent,descent,top,bottom}, AOSP-signed) widened to also
     *  cover any FALLBACK font `line`'s codepoints need beyond this Layout's
     *  own typeface -- AOSP StaticLayout.out()'s caller (android13-release
     *  core/java/android/text/StaticLayout.java:888-905):
     *      final int ascent = mFallbackLineSpacing
     *              ? Math.min(fmAscent, Math.round(ascents[breakIndex])) : fmAscent;
     *      final int descent = mFallbackLineSpacing
     *              ? Math.max(fmDescent, Math.round(descents[breakIndex])) : fmDescent;
     *      if (mFallbackLineSpacing) {
     *          if (ascent < fmTop) fmTop = ascent;
     *          if (descent > fmBottom) fmBottom = descent;
     *      }
     *  top/bottom are clamped to the SAME widened ascent/descent, not a
     *  separately-fetched fallback top/bottom -- AOSP has no such value here.
     *
     *  Identical algorithm to TextView.macapkFallbackVertical (widget/
     *  TextView.java, v1.0.962/976), ported here rather than shared because
     *  that method is private and this fix does not touch TextView.java.
     *  Per-codepoint coverage reuses the same MacApkFont.fallbackBaseFor
     *  chain Paint's own width measurement already walks (macapkMeasureRuns),
     *  so "does this line need a fallback face" can never disagree between
     *  how wide the line measured and how tall it lays out.
     *
     *  Gated on useFallbackLineSpacing -- returns `base` UNCHANGED (same
     *  reference) when this Layout did not opt in, `line` is null/empty, or
     *  no codepoint on it needs a fallback face (the overwhelmingly common
     *  case even when opted in). Callers must not mutate the returned array
     *  in place for that reason. */
    private int[] macapkFallbackVertical(String line, int[] base) {
        if (!useFallbackLineSpacing || line == null || line.isEmpty() || paint == null) {
            return base;
        }
        java.awt.Font primary;
        try { primary = paint.macapkResolvedFont(); } catch (Throwable t) { return base; }
        if (primary == null) return base;
        int asc = base[0], desc = base[1], top = base[2], bottom = base[3];
        boolean widened = false;
        int n = line.length();
        for (int i = 0; i < n; ) {
            int cp = line.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20) continue; // control chars: never a glyph, never a fallback trigger
            java.awt.Font fb;
            try { fb = android.graphics.MacApkFont.fallbackBaseFor(cp, primary); }
            catch (Throwable t) { continue; }
            if (fb == null) continue;
            java.awt.Font sized;
            try {
                sized = android.graphics.MacApkFont.getFrom(fb, primary.getSize2D(), primary.getStyle());
            } catch (Throwable t) { continue; }
            int[] fv = macapkFallbackFontVertical(sized);
            if (fv == null) continue;
            if (fv[0] < asc)  { asc = fv[0]; widened = true; }
            if (fv[1] > desc) { desc = fv[1]; widened = true; }
        }
        if (!widened) return base;
        if (asc < top) top = asc;
        if (desc > bottom) bottom = desc;
        return new int[] { asc, desc, top, bottom };
    }

    /** The AOSP line-box arithmetic, once per line, in INT font metrics --
     *  StaticLayout.out() (StaticLayout.java:996-1046) transcribed:
     *
     *    above = fm.ascent; below = fm.descent;
     *    if (firstLine) { if (trackPad) mTopPadding = top - above;
     *                     if (includePad) above = top; }
     *    if (lastLine)  { if (trackPad) mBottomPadding = bottom - below;
     *                     if (includePad) below = bottom; }
     *    if (needMultiply && !lastLine) extra = (int)(ex + 0.5) ... ;
     *    v += (below - above) + extra;
     *
     *  Two AOSP behaviours this brings in that we did not have:
     *
     *  (1) includeFontPadding. StaticLayout.Builder stored setIncludePad and
     *      dropped it, so an includePad layout and a no-pad layout came out
     *      the SAME height and getTopPadding()/getBottomPadding() were
     *      hardcoded 0. AOSP widens the first line's ascent to the font's
     *      `top` and the last line's descent to `bottom`, once each whatever
     *      the line count, so the height delta is exactly
     *      (ascent - top) + (bottom - descent).
     *
     *  (2) spacingAdd applies to every line EXCEPT the last
     *      (StaticLayout.java:1020, `needMultiply && (addLastLineLineSpacing
     *      || !lastLine)`, and the Builder never sets addLastLineLineSpacing).
     *      We multiplied a uniform per-line height by spacingMult and added
     *      spacingAdd to ALL n lines, so setLineSpacing(10, 1) grew the layout
     *      by 10*n where AOSP grows it by exactly 10*(n-1). The rounding is
     *      AOSP's too: EXTRA_ROUNDING is 0.5 (StaticLayout.java:1429) and the
     *      truncation is toward zero on each side of it.
     *
     *  (3) useFallbackLineSpacing (StaticLayout.Builder.
     *      setUseLineSpacingFromFallbacks / BoringLayout's matching ctor
     *      param). AOSP widens a line's OWN ascent/descent to a fallback
     *      font's metrics when that line carries a glyph outside the
     *      primary typeface's cmap (android13-release StaticLayout.java:
     *      888-905) -- see macapkFallbackVertical below. This is the
     *      Layout/StaticLayout/BoringLayout/Compose counterpart of
     *      TextView's own v1.0.976 fix for its separate hand-rolled
     *      wrap/draw path (widget/TextView.java's own macapkFallbackVertical
     *      private method of the same name); the two paths share no code
     *      and had to be fixed separately. */
    private void computeLineGeometry() {
        final int n = lineStarts.length;
        int fmTop, fmAscent, fmDescent, fmBottom;
        if (paint != null) {
            android.graphics.Paint.FontMetricsInt fm = new android.graphics.Paint.FontMetricsInt();
            paint.getFontMetricsInt(fm);
            fmTop = fm.top; fmAscent = fm.ascent; fmDescent = fm.descent; fmBottom = fm.bottom;
        } else {
            fmTop = fmAscent = -12; fmDescent = fmBottom = 3;
        }
        if (fmDescent - fmAscent <= 0) {
            // A degenerate paint must not collapse every line onto one row.
            float ts = (paint != null && paint.getTextSize() > 0) ? paint.getTextSize() : 12f;
            fmAscent = -Math.round(ts); fmDescent = Math.round(ts * 0.2f);
            fmTop = Math.min(fmTop, fmAscent); fmBottom = Math.max(fmBottom, fmDescent);
        }
        // AOSP StaticLayout.java:621.
        final boolean needMultiply = (spacingMult != 1f || spacingAdd != 0f);

        lineTops = new int[n + 1];
        lineAscents = new int[n];
        topPadding = 0;
        bottomPadding = 0;
        int v = 0;
        for (int k = 0; k < n; k++) {
            final boolean lastLine = (k == n - 1);
            int above = fmAscent, below = fmDescent, top = fmTop, bottom = fmBottom;
            // Widen THIS line's ascent/descent (and the top/bottom the
            // first/last-line includePad widening below clamps to) when it
            // carries a glyph outside the primary typeface's cmap. A no-op
            // (above/below/top/bottom unchanged) when useFallbackLineSpacing
            // is off or the line is pure-primary -- see macapkFallbackVertical.
            // ★ SPAN-AWARE VERTICAL METRICS, and this must run BEFORE the
            // fallback widening (AOSP applies spans in MeasuredParagraph and
            // the cmap fallback in StaticLayout, in that order). Without it a
            // line whose text carries an AbsoluteSizeSpan/RelativeSizeSpan is
            // given the BASE paint's ascent while its glyphs are painted at
            // the span's larger size, so the glyph TOPS are clipped by the
            // line box. See macapkSpanVertical.
            {
                int lsStart = lineStarts[k];
                int lsEnd = (k + 1 < n) ? lineStarts[k + 1] : textLen();
                int[] sm = macapkSpanVertical(lsStart, lsEnd,
                                              new int[]{above, below, top, bottom});
                above = sm[0]; below = sm[1]; top = sm[2]; bottom = sm[3];
            }
            if (useFallbackLineSpacing) {
                int[] m = macapkFallbackVertical(lineText(k), new int[]{above, below, top, bottom});
                above = m[0]; below = m[1]; top = m[2]; bottom = m[3];
            }
            if (k == 0 && includePad) {
                topPadding = top - above;   // tracked BEFORE the widening
                above = top;
            }
            if (lastLine && includePad) {
                bottomPadding = bottom - below;
                below = bottom;
            }
            int extra = 0;
            if (needMultiply && !lastLine) {
                double ex = (below - above) * (spacingMult - 1) + spacingAdd;
                extra = (ex >= 0) ? (int) (ex + 0.5) : -(int) (-ex + 0.5);
            }
            lineTops[k] = v;
            lineAscents[k] = above;
            v += (below - above) + extra;
        }
        lineTops[n] = v;
    }

    /** AOSP: clamp the line array to maxLines and ellipsize the last visible
     *  line when an overflow exists. We measure with the paint to place the
     *  ellipsis so a maxLines/ellipsize TextView no longer renders the whole
     *  (overflowing) string. */
    private void applyMaxLinesAndEllipsize(String s) {
        ellipsisStart = new int[lineStarts.length];
        ellipsisCount = new int[lineStarts.length];
        ellipsisLineText = new CharSequence[lineStarts.length];

        // ⚠ maxLines ALONE does not shorten a StaticLayout on AOSP. Line
        // generation stops early only when the layout is actually ellipsized
        // (StaticLayout.java:884, `if (mLineCount >= mMaximumVisibleLineCount
        // && mEllipsized) return;`), and the pre-emptive truncating branch at
        // :790 is guarded by `ellipsisMayBeApplied`, which requires a non-null
        // ellipsize. TextView caps the DRAWN height itself, through
        // StaticLayout.getHeight(cap) -- the Layout keeps every line.
        // We used to clamp unconditionally, so a maxLines=1 no-ellipsize
        // layout answered getLineCount()==1 where AOSP answers 4.
        // Gate ON: AOSP's actual guard, StaticLayout.generate (android14-release
        // StaticLayout.java:873-879):
        //     final boolean ellipsisMayBeApplied = ellipsize != null
        //             && (ellipsize == TextUtils.TruncateAt.END
        //                 || (mMaximumVisibleLineCount == 1
        //                         && ellipsize != TextUtils.TruncateAt.MARQUEE));
        // so a multi-line MARQUEE / START / MIDDLE layout keeps EVERY line and
        // getLineCount() exceeds maxLines. F-Droid shows its description's
        // "More" button only when `getLineCount() > 5` after setMaxLines(5) +
        // MARQUEE; cutting here hid the button on every app page.
        final boolean ellipsisMayBeApplied = MACAPK_TEXT_LINES_AOSP
            ? (ellipsizeMode != null
                && (ellipsizeMode == android.text.TextUtils.TruncateAt.END
                    || (maxLines == 1
                        && ellipsizeMode != android.text.TextUtils.TruncateAt.MARQUEE)))
            : ellipsizeMode != null;
        boolean overflow = ellipsisMayBeApplied && lineStarts.length > maxLines;
        if (overflow) {
            int[] clamped = new int[maxLines];
            System.arraycopy(lineStarts, 0, clamped, 0, maxLines);
            lineStarts = clamped;
            ellipsisStart = new int[maxLines];
            ellipsisCount = new int[maxLines];
            ellipsisLineText = new CharSequence[maxLines];
        }

        if (ellipsizeMode == null || paint == null) return;
        // Gate ON: out() only ellipsizes a multi-line layout's last visible
        // line for END, and a one-line layout for anything but MARQUEE
        // (:1066-1075). A MARQUEE line is never ellipsized by the layout.
        if (MACAPK_TEXT_LINES_AOSP && !overflow
                && (ellipsizeMode == android.text.TextUtils.TruncateAt.MARQUEE
                    || (lineStarts.length > 1
                        && ellipsizeMode != android.text.TextUtils.TruncateAt.END))) return;
        // Ellipsize the last visible line if there is hidden content after it,
        // OR (single-line case) if that line itself overflows the width.
        int last = lineStarts.length - 1;
        if (last < 0) return;
        int lineBeginIdx = lineStarts[last];
        int lineEndIdx = s.length();
        // Gate ON: StaticLayout.generate merges the overflow into the last
        // visible line only WITHIN its paragraph (:881-896, the merged line
        // ends at paraEnd, which includes the '\n'), so the ellipsized text is
        // the rest of this paragraph, never the paragraphs after it. `paraNl`
        // is that '\n' (-1 when the paragraph runs to the end of the text).
        int paraNl = -1;
        if (MACAPK_TEXT_LINES_AOSP && overflow && lineBeginIdx < s.length()) {
            paraNl = s.indexOf('\n', lineBeginIdx);
            if (paraNl >= 0) lineEndIdx = paraNl;
        }
        // out()'s moreChars for the merged line: text follows its paraEnd.
        final boolean moreChars = paraNl >= 0 && paraNl + 1 < s.length();
        // The visible last line's natural end is the start of the next line if
        // it existed pre-clamp; reconstruct it from the raw string up to the
        // next hard break for the non-overflow single-line/end measurement.
        int naturalEnd = s.length();
        if (!overflow) {
            // last line spans [lineBeginIdx, naturalEnd) already (no clamp).
            naturalEnd = s.length();
        }
        String lineStr = s.substring(Math.min(lineBeginIdx, s.length()),
                                     Math.min(lineEndIdx, s.length()));
        // Strip a trailing newline for measurement.
        if (lineStr.endsWith("\n")) lineStr = lineStr.substring(0, lineStr.length() - 1);
        float avail = (ellipsizedWidth > 0 ? ellipsizedWidth : width);
        if (avail <= 0) return;
        // Span-aware width test, measured BY OFFSET against the original
        // CharSequence -- `lineStr` is a plain String and carries no spans, so
        // testing it would decide "it fits" using the base font while the line
        // gets painted in the span's. That is the bug this file's
        // macapkMeasure exists for, and it is also why no ellipsis was ever
        // produced for Instagram's clipped text.
        int mStart = Math.min(lineBeginIdx, s.length());
        int mEnd   = Math.min(lineEndIdx, s.length());
        if (mEnd > mStart && s.charAt(mEnd - 1) == '\n') mEnd--;
        boolean needsEllipsis = overflow
                || macapkMeasure(text != null ? text : s, mStart, mEnd) > avail;
        if (!needsEllipsis) return;
        CharSequence truncated = android.text.TextUtils.ellipsize(
            lineStr, paint, avail, ellipsizeMode);
        final boolean endMode = ellipsizeMode == android.text.TextUtils.TruncateAt.END
            || ellipsizeMode == android.text.TextUtils.TruncateAt.END_SMALL;
        if (MACAPK_TEXT_LINES_AOSP && overflow && endMode && truncated != null) {
            // AOSP's merged line is [lineStart, paraNl + 1): it INCLUDES the
            // paragraph's '\n', so its length is one more than lineStr's.
            final int paraLen = lineStr.length() + (paraNl >= 0 ? 1 : 0);
            if (truncated.toString().equals(lineStr)) {
                if (!moreChars) {
                    // Nothing follows and it fits: out() does not force, so
                    // the line is drawn whole with no ellipsis.
                    ellipsisLineText[last] = truncated;
                    return;
                }
                // forceEllipsis (out :1068, calculateEllipsis :1224-1227):
                // `ellipsisStart = len - 1; ellipsisCount = 1` replaces the
                // LAST char of the merged line, which is the zero-width '\n'
                // -- so "Beta" shows "Beta...", not "Bet...", and
                // getEllipsisCount() is 1, which is what a "Read more" check
                // (`getEllipsisCount(getLineCount() - 1) > 0`) reads.
                truncated = lineStr
                    + (ellipsizeMode == android.text.TextUtils.TruncateAt.END_SMALL ? "\u2025" : "\u2026");
                ellipsisLineText[last] = truncated;
                ellipsisStart[last] = lineStr.length();
                ellipsisCount[last] = 1;
                return;
            }
            // A width cut: the ellipsis char itself is NOT a kept source char,
            // and everything from the cut to paraEnd (the '\n' included) is
            // counted as ellipsized, calculateEllipsis' `len - i`.
            ellipsisLineText[last] = truncated;
            final int st = Math.max(0, Math.min(truncated.length() - 1, lineStr.length()));
            ellipsisStart[last] = st;
            ellipsisCount[last] = Math.max(0, paraLen - st);
            return;
        }
        ellipsisLineText[last] = truncated;
        // Record AOSP-shape ellipsis geometry: count of chars replaced by the
        // ellipsis on this line, relative to the line start.
        // For an END cut the appended ellipsis char is not a kept char;
        // counting it put ellipsisStart one past the cut and, when the line
        // fit, left ellipsisCount at 0 on a line that draws an ellipsis.
        int kept = truncated == null ? 0 : truncated.length();
        if (MACAPK_TEXT_LINES_AOSP && endMode && truncated != null
                && !truncated.toString().equals(lineStr)) kept--;
        ellipsisStart[last] = Math.max(0, Math.min(kept, lineStr.length()));
        ellipsisCount[last] = Math.max(0, lineStr.length() - ellipsisStart[last]);
    }

    /** minikin LineBreakerUtil.h isLineEndSpace (android14-release :47-56). */
    static boolean isLineEndSpace(char c) {
        return c == '\n' || c == ' ' || c == 0x1680
            || (0x2000 <= c && c <= 0x200A && c != 0x2007)
            || c == 0x205F || c == 0x3000;
    }

    private int textLen() { return text == null ? 0 : text.length(); }

    public int getLineCount() { ensureLines(); return lineStarts.length; }
    /** AOSP Layout.java:792 -- `return getLineTop(getLineCount());`, i.e. the
     *  height IS the top of the one-past-the-end line, never an independent
     *  sum. That is the identity TXT.layout.*.heightIsLineTopOfCount asserts. */
    public int getHeight() { return getLineTop(getLineCount()); }

    /** AOSP's `StaticLayout.generate()` collects the paragraph's LineHeightSpans
     *  and `out()` seeds a FontMetricsInt with the line's own above/below, calls
     *  `chooseHeight()` on each span, then READS THE MUTATED fm BACK. That
     *  callback is the ONLY way a span gets to set a line's height, and it is the
     *  only mechanism Jetpack Compose uses for `TextStyle.lineHeight`:
     *  AndroidParagraph attaches
     *  `androidx.compose.ui.text.android.style.LineHeightStyleSpan`, which
     *  implements `android.text.style.LineHeightSpan` and decides first/last-line
     *  trimming by comparing the start/end it is handed against its own span
     *  range, so the per-line offsets have to be real rather than 0/length.
     *
     *  Nothing in this tree called `chooseHeight()` at all (it had zero call
     *  sites outside the interface declaration), so every Compose Text laid out
     *  at the raw font height whatever its typography declared. Measured on
     *  Wikipedia's onboarding, which is `setContent`-only with no
     *  `setContentView` anywhere: body pitch 38px against the emulator's 48
     *  (`lineHeight` 24sp x density 2.0). That is also why the v1.0.895
     *  line-spacing attribute work measured inert on this app -- the screen it
     *  was tested against never goes through the inflater at all.
     *
     *  Deliberately NOT folded into ensureLines(): line BREAKING is width-driven
     *  and unaffected by vertical metrics, so it keeps its own cheap path.
     *
     *  Seeds `fm` with the fallback-widened per-line metrics
     *  (macapkFallbackVertical) before handing it to chooseHeight(), not the
     *  bare per-paint base -- same order AOSP uses (android13-release
     *  StaticLayout.java:981-989: fm.ascent/descent/top/bottom are set from
     *  the ALREADY fallback-widened above/below/top/bottom, THEN each span's
     *  chooseHeight() reads/mutates fm). Skipping this would silently make
     *  computeLineGeometry's fallback widening invisible for EVERY Compose
     *  Text with an explicit or Material3-default TextStyle.lineHeight
     *  (chooseHeight is the only mechanism Compose uses for lineHeight, per
     *  above), because this method OVERWRITES lineTops/lineAscents right
     *  after computeLineGeometry runs, whenever the text carries
     *  LineHeightSpans. */
    private void applyLineHeightSpans() {
        if (lineHeightSpansApplied) return;
        lineHeightSpansApplied = true;
        if (!(text instanceof android.text.Spanned) || paint == null) return;
        android.text.style.LineHeightSpan[] spans;
        try {
            spans = ((android.text.Spanned) text).getSpans(0, text.length(),
                android.text.style.LineHeightSpan.class);
        } catch (Throwable ignored) { return; }
        if (spans == null || spans.length == 0) return;
        final int n = lineStarts.length;
        final int len = textLen();
        final boolean needMultiply = (spacingMult != 1f || spacingAdd != 0f);
        int[] tops = new int[n + 1];
        int[] asc = new int[n];
        android.graphics.Paint.FontMetricsInt base = new android.graphics.Paint.FontMetricsInt();
        paint.getFontMetricsInt(base);
        int v = 0;
        int tp = 0, bp = 0;
        for (int k = 0; k < n; k++) {
            int st = lineStarts[k];
            int en = (k + 1 < n) ? lineStarts[k + 1] : len;
            android.graphics.Paint.FontMetricsInt fm = new android.graphics.Paint.FontMetricsInt();
            fm.top = base.top; fm.ascent = base.ascent;
            fm.descent = base.descent; fm.bottom = base.bottom;
            if (useFallbackLineSpacing) {
                int[] w = macapkFallbackVertical(lineText(k),
                    new int[]{fm.ascent, fm.descent, fm.top, fm.bottom});
                fm.ascent = w[0]; fm.descent = w[1]; fm.top = w[2]; fm.bottom = w[3];
            }
            for (android.text.style.LineHeightSpan s : spans) {
                try { s.chooseHeight(text, st, en, v, v, fm); }
                catch (Throwable ignored) {}
            }
            // AOSP runs chooseHeight FIRST and only then applies the
            // includePad widening (generate() mutates fm, out() reads it and
            // then does `if (includePad) above = top;`), so a span that moves
            // ascent without moving top is partly undone on the first line.
            // That is real AOSP behaviour, not an approximation of it.
            int above = fm.ascent, below = fm.descent;
            final boolean lastLine = (k == n - 1);
            if (k == 0 && includePad) { tp = fm.top - above; above = fm.top; }
            if (lastLine && includePad) { bp = fm.bottom - below; below = fm.bottom; }
            int extra = 0;
            if (needMultiply && !lastLine) {
                double ex = (below - above) * (spacingMult - 1) + spacingAdd;
                extra = (ex >= 0) ? (int) (ex + 0.5) : -(int) (-ex + 0.5);
            }
            tops[k] = v;
            asc[k] = above;
            int h = (below - above) + extra;
            // A span that collapses a line to nothing would break the strictly
            // increasing-tops invariant every caller indexes by; fall back to
            // the span-free height for that line rather than stack baselines.
            if (h <= 0) h = lineTops[k + 1] - lineTops[k];
            v += h;
        }
        tops[n] = v;
        lineTops = tops; lineAscents = asc;
        topPadding = tp; bottomPadding = bp;
    }
    public int getLineStart(int line) {
        ensureLines();
        if (line <= 0) return 0;
        if (line >= lineStarts.length) return textLen();
        return lineStarts[line];
    }
    public int getLineEnd(int line) { return getLineStart(line + 1); }
    public int getLineTop(int line) {
        ensureLines(); applyLineHeightSpans();
        int k = line < 0 ? 0 : (line >= lineTops.length ? lineTops.length - 1 : line);
        return lineTops[k];
    }
    /** AOSP Layout.java:1712 -- `getLineTop(line + 1)`, verbatim. */
    public int getLineBottom(int line) { return getLineTop(line + 1); }
    /** AOSP Layout.java:1729 -- `getLineTop(line+1) - getLineDescent(line)`.
     *  Equal to top(line) - above(line) given how getLineDescent is derived,
     *  but written AOSP's way so the identity is the definition. */
    public int getLineBaseline(int line) {
        return getLineTop(line + 1) - getLineDescent(line);
    }
    /** AOSP Layout.java:1738 -- `getLineTop(line) - (getLineTop(line+1) -
     *  getLineDescent(line))`, which reduces to the stored `above`. */
    public int getLineAscent(int line) {
        ensureLines(); applyLineHeightSpans();
        if (line >= 0 && line < lineAscents.length) return lineAscents[line];
        return lineAscents.length > 0 ? lineAscents[lineAscents.length - 1] : 0;
    }
    /** AOSP stores `below + extra` per line (StaticLayout.java:1046). Here
     *  that is (top(k+1) - top(k)) + above(k), since the stored top delta is
     *  (below - above) + extra. */
    public int getLineDescent(int line) {
        ensureLines(); applyLineHeightSpans();
        if (line >= 0 && line + 1 < lineTops.length) {
            return lineTops[line + 1] - lineTops[line] + lineAscents[line];
        }
        return lineAscents.length > 0
            ? lineTops[lineTops.length - 1] - lineTops[lineTops.length - 2]
                + lineAscents[lineAscents.length - 1]
            : 0;
    }
    public int getLineTop(int line, boolean includeLineSpacing) { return getLineTop(line); }

    /** Text of line `line` with a trailing '\n' (if any) stripped. */
    private String lineText(int line) {
        ensureLines();
        if (text == null) return "";
        if (ellipsisLineText != null && line >= 0 && line < ellipsisLineText.length
                && ellipsisLineText[line] != null) {
            return ellipsisLineText[line].toString();
        }
        int st = getLineStart(line), en = getLineEnd(line);
        if (en > st && en <= textLen() && text.charAt(en - 1) == '\n') en--;
        if (en < st) en = st;
        return text.subSequence(st, en).toString();
    }
    /** AOSP Layout.java:1394-1401, verbatim: "Gets the unsigned horizontal
     *  extent of the specified line, including leading margin indent AND
     *  TRAILING WHITESPACE." It is getLineExtent(line, full=true). */
    public float getLineWidth(int line) {
        if (paint == null) return 0f;
        // An ellipsized line carries SYNTHESIZED text (its trailing run was
        // replaced with an ellipsis), so the original spans no longer line up
        // with those offsets -- measure that string plainly. Every other line
        // is measured BY OFFSET so MetricAffectingSpans resolve.
        if (ellipsisLineText != null && line >= 0 && line < ellipsisLineText.length
                && ellipsisLineText[line] != null) {
            return paint.measureText(ellipsisLineText[line].toString());
        }
        ensureLines();
        if (text == null) return 0f;
        int st = getLineStart(line), en = getLineEnd(line);
        if (en > st && en <= textLen() && text.charAt(en - 1) == '\n') en--;
        if (en < st) en = st;
        return macapkMeasure(text, st, en);
    }

    private String visibleLineText(int line) {
        ensureLines();
        if (text == null) return "";
        // An ellipsized line already had its trailing run replaced.
        if (ellipsisLineText != null && line >= 0 && line < ellipsisLineText.length
                && ellipsisLineText[line] != null) {
            return ellipsisLineText[line].toString();
        }
        int st = getLineStart(line), en = getLineVisibleEnd(line);
        if (en < st) en = st;
        return text.subSequence(st, en).toString();
    }

    /** ⚠ getLineMax was ALIASED to getLineWidth, which is AOSP-BACKWARDS: AOSP
     *  defines getLineMax as getLineExtent(line, full=FALSE), i.e. the extent
     *  EXCLUDING trailing whitespace (it walks getLineVisibleEnd), while
     *  getLineWidth INCLUDES it.
     *
     *  The alias is not cosmetic, because the alignment path below is defined
     *  against the visible extent: with the alias, a CENTER- or RIGHT-aligned
     *  line carrying trailing spaces was displaced by half of / all of that
     *  whitespace. A trailing space is invisible, so the text simply sat wrong
     *  and nothing pointed at why. Found by the TXTM answer-differential lane
     *  while chasing a different row. */
    public float getLineMax(int line) {
        if (paint == null) return 0f;
        if (ellipsisLineText != null && line >= 0 && line < ellipsisLineText.length
                && ellipsisLineText[line] != null) {
            return paint.measureText(ellipsisLineText[line].toString());
        }
        ensureLines();
        if (text == null) return 0f;
        int st = getLineStart(line), en = getLineVisibleEnd(line);
        if (en < st) en = st;
        return macapkMeasure(text, st, en);
    }
    public float getLineLeft(int line) {
        ensureLines();
        // AOSP aligns on the VISIBLE extent (getLineMax), not the full one.
        // NORMAL and OPPOSITE are relative to the paragraph direction: an RTL
        // paragraph's NORMAL is the right edge (AOSP Layout.getLineLeft).
        Alignment a = alignment;
        if (a == Alignment.ALIGN_NORMAL || a == Alignment.ALIGN_OPPOSITE || a == null) {
            boolean rtl = getParagraphDirection(line) == DIR_RIGHT_TO_LEFT;
            boolean right = (a == Alignment.ALIGN_OPPOSITE) != rtl;
            a = right ? Alignment.ALIGN_RIGHT : Alignment.ALIGN_LEFT;
        }
        switch (a) {
            case ALIGN_CENTER:   return Math.max(0f, (width - getLineMax(line)) / 2f);
            case ALIGN_RIGHT:    return Math.max(0f, width - getLineMax(line));
            default:             return 0f;
        }
    }
    public float getLineRight(int line) { return getLineLeft(line) + getLineWidth(line); }

    public int getLineForOffset(int offset) {
        ensureLines();
        for (int k = lineStarts.length - 1; k >= 0; --k)
            if (offset >= lineStarts[k]) return k;
        return 0;
    }
    public int getLineForVertical(int vertical) {
        ensureLines(); applyLineHeightSpans();
        // Lines are not a uniform pitch (includePad widens the first and last,
        // and a LineHeightSpan can move any of them), so a divide would land on
        // the wrong row; scan the real tops.
        for (int k = getLineCount() - 1; k >= 1; --k) {
            if (vertical >= lineTops[k]) return k;
        }
        return 0;
    }
    public int getOffsetForHorizontal(int line, float horiz) {
        ensureLines();
        if (paint == null || text == null) return getLineStart(line);
        int st = getLineStart(line), en = getLineEnd(line);
        float left = getLineLeft(line);
        for (int o = st; o < en; o++) {
            float w = left + macapkMeasure(text, st, o + 1);
            if (w >= horiz) return o;
        }
        return en;
    }
    public boolean isRtlCharAt(int offset) { return false; }
    /** AOSP: +1 / -1 (Layout.java:1731), stamped onto each line from the
     *  paragraph's bidi result -- StaticLayout.out() writes
     *  `measured.getParagraphDir()` (StaticLayout.java:926, :1053) and
     *  MeasuredParagraph derives that from the TextDirectionHeuristic the
     *  Builder was given.
     *
     *  This used to return a hardcoded DIR_LEFT_TO_RIGHT, so an explicitly
     *  RTL-directed layout, and every Hebrew/Arabic paragraph under the
     *  DEFAULT first-strong heuristic, reported LTR. The heuristics themselves
     *  are already a real port of the Unicode Bidi P2/P3 algorithm
     *  (TextDirectionHeuristics), so the answer only needed wiring up.
     *
     *  Resolved per PARAGRAPH, not per line: AOSP runs the bidi once per
     *  '\n'-delimited paragraph and every line of it inherits that direction,
     *  so a line is widened to its paragraph before the heuristic is asked. */
    public int getParagraphDirection(int line) {
        ensureLines();
        final CharSequence t = text;
        if (t == null || textDir == null) return DIR_LEFT_TO_RIGHT;
        final int len = t.length();
        int ln = line < 0 ? 0 : (line >= lineStarts.length ? lineStarts.length - 1 : line);
        int pStart = lineStarts[ln];
        if (pStart > len) pStart = len;
        while (pStart > 0 && t.charAt(pStart - 1) != '\n') pStart--;
        int pEnd = pStart;
        while (pEnd < len && t.charAt(pEnd) != '\n') pEnd++;
        try {
            return textDir.isRtl(t, pStart, pEnd - pStart)
                ? DIR_RIGHT_TO_LEFT : DIR_LEFT_TO_RIGHT;
        } catch (Throwable ignored) {
            return DIR_LEFT_TO_RIGHT;
        }
    }
    // AOSP: `public abstract boolean getLineContainsTab(int)`. We keep it concrete
    // (subclasses here do not track tabs), but the RETURN TYPE has to be boolean or
    // SDK-compiled bytecode cannot link to it.
    // frameworks/base/core/java/android/text/Layout.java
    public boolean getLineContainsTab(int line) { return false; }
    public int getEllipsisCount(int line) {
        ensureLines();
        if (ellipsisCount != null && line >= 0 && line < ellipsisCount.length)
            return ellipsisCount[line];
        return 0;
    }
    public int getEllipsisStart(int line) {
        ensureLines();
        if (ellipsisStart != null && line >= 0 && line < ellipsisStart.length)
            return ellipsisStart[line];
        return 0;
    }
    public android.text.Layout.Directions getLineDirections(int line) { return DIRS_ALL_LEFT_TO_RIGHT; }
    public static class Directions {
        public int[] mDirections;
        public Directions() { mDirections = new int[]{0, 0}; }
    }
    public static final Directions DIRS_ALL_LEFT_TO_RIGHT = new Directions();
    public static final Directions DIRS_ALL_RIGHT_TO_LEFT = new Directions();
    public static final int DIR_LEFT_TO_RIGHT = 1;
    public static final int DIR_RIGHT_TO_LEFT = -1;

    private static final boolean TRACE_COMPOSE = "1".equals(System.getenv("MACAPK_TRACE_COMPOSE"));
    public void draw(android.graphics.Canvas canvas) {
        if (canvas == null || text == null || paint == null) return;
        ensureLines();
        final int count = getLineCount();
        if (TRACE_COMPOSE) {
            String t = text.toString();
            com.macapk.loader.NativeLog.i("Mac.apk.LayoutDraw",
                "draw '" + (t.length() > 40 ? t.substring(0, 40) : t) + "' color=0x"
                + Integer.toHexString(paint.getColor()) + " size=" + paint.getTextSize()
                + " width=" + width + " lines=" + count);
        }
        // AOSP Layout.drawText hands each line to TextLine, which splits it at
        // every CharacterStyle transition and applies updateDrawState(TextPaint)
        // before painting that run (frameworks/base core/java/android/text/
        // Layout.java:440+, TextLine.java) -- that's how a ForegroundColorSpan/
        // BackgroundColorSpan/StyleSpan/UnderlineSpan/Sub-or-SuperscriptSpan on a
        // substring ends up painted differently from its neighbours. TextLine
        // itself is not part of this tree (it's a large shaping/BiDi-aware
        // class); mirror the local equivalent android.widget.TextView.onDraw
        // already uses (same run-splitting approach, proven there), rather than
        // draw the whole line with one Paint -- which is what this method did
        // before, silently discarding every character-level span on ANY direct
        // Layout/StaticLayout caller (e.g. Compose's AndroidParagraph, or any
        // app that builds a StaticLayout and calls draw() itself -- a standard
        // pattern for spannable text in a custom View, independent of TextView).
        android.text.Spanned spanned = spannedCharacterStyles();
        for (int k = 0; k < count; k++) {
            String ln = MACAPK_TEXT_LINES_AOSP ? drawLineText(k, count) : lineText(k);
            if (ln.isEmpty()) continue;
            float x = getLineLeft(k);
            float y = getLineBaseline(k);
            // An ellipsized line's text is synthesized (original run + "...")
            // with no 1:1 offset mapping back into `text`; draw it with the
            // base paint rather than guess at a span mapping across the
            // ellipsis, matching this method's prior behavior for that case.
            if (spanned == null || (ellipsisLineText != null && k < ellipsisLineText.length
                    && ellipsisLineText[k] != null)) {
                if (getParagraphDirection(k) == DIR_RIGHT_TO_LEFT
                        || MacApkBidi.hasRtl(ln, 0, ln.length())) {
                    canvas.drawTextRun(ln, 0, ln.length(), 0, ln.length(), x, y,
                            getParagraphDirection(k) == DIR_RIGHT_TO_LEFT, paint);
                } else {
                    canvas.drawText(ln, x, y, paint);
                }
            } else {
                drawSpannedLine(canvas, spanned, ln, getLineStart(k), x, y);
            }
        }
    }

    /** The range AOSP Layout.drawText hands TextLine for line k:
     *  [getLineStart(k), getLineVisibleEnd(k, start, end)) (android14-release
     *  Layout.java:613), whose private three-arg form (:2369-2390) is
     *      if (line == getLineCount() - 1) return end;
     *      for (; end > start; end--) {
     *          ch = text.charAt(end - 1);
     *          if (ch == '\n') return end - 1;
     *          if (!TextLine.isLineEndSpace(ch)) break;
     *      }
     *      return end;
     *  so a soft-wrapped line's hanging spaces are not drawn. Glyphs are
     *  unchanged (a space has no ink), but Canvas now draws UNDERLINE /
     *  STRIKE_THRU / UnderlineSpan across the run's whole advance, which
     *  overhung those spaces on every wrapped line. The public
     *  getLineVisibleEnd below is a different, older approximation (it also
     *  trims the last line and a space before a '\n') that alignment reads;
     *  it is left alone here. Ellipsized lines keep their synthesized text. */
    private String drawLineText(int line, int count) {
        if (ellipsisLineText != null && line >= 0 && line < ellipsisLineText.length
                && ellipsisLineText[line] != null) {
            return ellipsisLineText[line].toString();
        }
        if (text == null) return "";
        final int st = getLineStart(line);
        int en = getLineEnd(line);
        if (en > textLen()) en = textLen();
        if (line == count - 1) {
            // AOSP returns `end` as is; a trailing '\n' there is the text's own
            // last char, which lineText has always stripped, so strip it too.
            if (en > st && text.charAt(en - 1) == '\n') en--;
        } else {
            for (; en > st; en--) {
                char ch = text.charAt(en - 1);
                if (ch == '\n') { en--; break; }
                if (!(ch == '\t' || (ch != '\n' && isLineEndSpace(ch)))) break;
            }
        }
        if (en < st) en = st;
        return text.subSequence(st, en).toString();
    }

    /** The text as a Spanned iff it actually carries CharacterStyle spans;
     *  null (the common plain-text case) keeps the single-drawText path above
     *  completely unchanged. Mirrors TextView.macapkStyledText(). */
    private android.text.Spanned spannedCharacterStyles() {
        if (!(text instanceof android.text.Spanned) || text.length() == 0) return null;
        android.text.Spanned sp = (android.text.Spanned) text;
        try {
            android.text.style.CharacterStyle[] cs =
                sp.getSpans(0, sp.length(), android.text.style.CharacterStyle.class);
            return (cs != null && cs.length > 0) ? sp : null;
        } catch (Throwable ignored) { return null; }
    }

    private android.graphics.Paint mSpanBgPaint;
    /** Whole-paint snapshot for drawSpannedLine; see the note there. */
    private android.text.TextPaint mDrawBase;

    /** One style run of a spanned line, [runStart, runEnd) in `sp`, painted at
     *  `cx` in the given direction with its CharacterStyles applied to a reset
     *  copy of the paint. Returns its advance. */
    private float macapkDrawSpanRun(android.graphics.Canvas canvas, android.text.Spanned sp,
                                    String line, int srcStart, int runStart, int runEnd,
                                    float cx, float baselineY, boolean rtl) {
        final android.text.TextPaint p = paint;
        p.set(mDrawBase);
        android.text.style.CharacterStyle[] runSpans =
            sp.getSpans(runStart, runEnd, android.text.style.CharacterStyle.class);
        if (runSpans != null) {
            for (android.text.style.CharacterStyle cs : runSpans) {
                if (cs == null) continue;
                if (sp.getSpanStart(cs) >= runEnd || sp.getSpanEnd(cs) <= runStart) continue;
                try { cs.updateDrawState(p); } catch (Throwable ignored) {}
            }
        }
        String run = line.substring(runStart - srcStart, runEnd - srcStart);
        float runW = p.measureText(run);
        if (p.bgColor != 0) {
            if (mSpanBgPaint == null) mSpanBgPaint = new android.graphics.Paint();
            mSpanBgPaint.setColor(p.bgColor);
            canvas.drawRect(cx, baselineY + p.ascent(), cx + runW,
                baselineY + p.descent(), mSpanBgPaint);
        }
        canvas.drawTextRun(run, 0, run.length(), 0, run.length(), cx, baselineY + p.baselineShift, rtl, p);
        return runW;
    }

    /** Draw one laid-out line, splitting it at every CharacterStyle transition
     *  and applying the covering spans' updateDrawState(TextPaint) before
     *  painting that run. `srcStart` is the offset into `text` where `line`
     *  begins. Mirrors TextView.macapkDrawLine(): snapshot the fields a
     *  CharacterStyle may touch on the shared `paint`, reset to that snapshot
     *  before each run, restore at the end -- `paint` is a long-lived field
     *  callers (Compose, TextView) may read again after draw() returns. */
    private void drawSpannedLine(android.graphics.Canvas canvas, android.text.Spanned sp,
                                  String line, int srcStart, float x, float baselineY) {
        final android.text.TextPaint p = paint;
        // ⚠ SNAPSHOT THE WHOLE PAINT, not a hand-listed set of fields.
        // This used to save colour/size/flags/skew/bgColor/baselineShift and
        // reset only those per run, so a TypefaceSpan's setTypeface and a
        // letter-spacing span's setLetterSpacing LEAKED: an unspanned run
        // following a spanned one drew in the span's face, and after draw()
        // returned `paint` still carried the last span's typeface, making any
        // later re-measure order-dependent. AOSP cannot leak -- TextLine
        // .handleRun does `wp.set(mPaint)` into a WORK paint for every run.
        // (The comment at TextView.java:2215 claiming Paint.set does not carry
        //  typeface/skew is STALE: Paint.set copies typeface, letterSpacing,
        //  textScaleX and skew -- graphics/Paint.java:126-144.)
        if (mDrawBase == null) mDrawBase = new android.text.TextPaint();
        mDrawBase.set(p);
        float cx = x;
        final int end = srcStart + line.length();
        if (MacApkBidi.hasRtl(line, 0, line.length())) {
            // Cut at every style run and every bidi level change, then paint
            // the pieces in visual order, each with its own direction.
            int[] bounds = new int[line.length() + 1];
            int nb = 0;
            for (int rs = srcStart; rs < end; ) {
                int re = sp.nextSpanTransition(rs, end, android.text.style.CharacterStyle.class);
                if (re <= rs || re > end) re = end;
                bounds[nb++] = re - srcStart;
                rs = re;
            }
            int[] pieces = MacApkBidi.visualPieces(line, bounds, nb, MacApkBidi.isRtlParagraph(sp, srcStart));
            for (int k = 0; k < pieces.length; k += 3) {
                cx += macapkDrawSpanRun(canvas, sp, line, srcStart, srcStart + pieces[k],
                        srcStart + pieces[k + 1], cx, baselineY, (pieces[k + 2] & 1) != 0);
            }
            p.set(mDrawBase);
            return;
        }
        int runStart = srcStart;
        while (runStart < end) {
            int runEnd = sp.nextSpanTransition(runStart, end, android.text.style.CharacterStyle.class);
            if (runEnd <= runStart || runEnd > end) runEnd = end;
            p.set(mDrawBase);          // full reset: typeface + letterSpacing too
            android.text.style.CharacterStyle[] runSpans =
                sp.getSpans(runStart, runEnd, android.text.style.CharacterStyle.class);
            if (runSpans != null) {
                for (android.text.style.CharacterStyle cs : runSpans) {
                    if (cs == null) continue;
                    // getSpans is inclusive at the boundaries; skip spans that
                    // merely touch this run without covering it.
                    if (sp.getSpanStart(cs) >= runEnd || sp.getSpanEnd(cs) <= runStart) continue;
                    try { cs.updateDrawState(p); } catch (Throwable ignored) {}
                }
            }
            String run = line.substring(runStart - srcStart, runEnd - srcStart);
            float runW = p.measureText(run);
            // BackgroundColorSpan writes TextPaint.bgColor; AOSP's TextLine
            // fills the run's line box with it before painting the glyphs.
            if (p.bgColor != 0) {
                if (mSpanBgPaint == null) mSpanBgPaint = new android.graphics.Paint();
                mSpanBgPaint.setColor(p.bgColor);
                canvas.drawRect(cx, baselineY + p.ascent(), cx + runW,
                    baselineY + p.descent(), mSpanBgPaint);
            }
            // AOSP offsets the baseline by TextPaint.baselineShift (Sub/
            // SuperscriptSpan are implemented entirely through that field).
            canvas.drawText(run, cx, baselineY + p.baselineShift, p);
            cx += runW;
            runStart = runEnd;
        }
        p.set(mDrawBase);              // leave the shared paint exactly as found
    }

    /** AOSP `Layout.getDesiredWidth` -- min width to lay out on one line. */
    public static float getDesiredWidth(CharSequence text, android.text.TextPaint paint) {
        if (text == null || paint == null) return 0f;
        // Honor explicit newlines: the desired width is the widest paragraph.
        String s = text.toString();
        float max = 0f;
        int from = 0;
        // One scratch paint for the whole walk; macapkMeasureSpanned overwrites
        // it per run and never hands it out.
        final android.text.TextPaint work = new android.text.TextPaint();
        while (from <= s.length()) {
            int nl = s.indexOf('\n', from);
            int to = (nl < 0) ? s.length() : nl;
            // Measure against `text`, not `s`: a String carries no spans, and
            // this is the width RenderCore-style measurers size a view from.
            max = Math.max(max, macapkMeasureSpanned(text, from, to, paint, work));
            if (nl < 0) break;
            from = nl + 1;
        }
        return max;
    }
    public static float getDesiredWidth(CharSequence text, int start, int end, android.text.TextPaint paint) {
        if (text == null || paint == null) return 0f;
        return getDesiredWidth(text.subSequence(start, end), paint);
    }
    public static float getDesiredWidthWithLimit(CharSequence text, int start, int end,
                                                  android.text.TextPaint paint, float upperLimit) {
        float w = getDesiredWidth(text, start, end, paint);
        return Math.min(w, upperLimit);
    }

    /** AOSP Layout.getRangeForRect (API 34): the text range whose segments (as
     *  found by `segmentFinder`) fall inside `area` per `inclusionStrategy`.
     *  Used by handwriting / gesture selection. Mac.apk does not compute
     *  per-segment glyph bounds for this path, so it reports "no range found"
     *  (null), the same value AOSP returns when nothing qualifies. */
    public int[] getRangeForRect(android.graphics.RectF area,
            android.text.SegmentFinder segmentFinder,
            TextInclusionStrategy inclusionStrategy) {
        return null;
    }

    /** Decides whether a text segment counts as inside a selection rect. API 34.
     *  Source: frameworks/base/core/java/android/text/Layout.java */
    public interface TextInclusionStrategy {
        boolean isSegmentInside(android.graphics.RectF segmentBounds,
                                android.graphics.RectF area);
    }

    /** AOSP StaticLayout.mTopPadding: `top - above` measured on the FIRST line
     *  before includePad widens `above` to `top` (StaticLayout.java:998), so
     *  it is <= 0. Zero unless includePad, because AOSP ties trackPad to
     *  includepad (StaticLayout.java:545). */
    public int getTopPadding() { ensureLines(); applyLineHeightSpans(); return topPadding; }
    /** AOSP StaticLayout.mBottomPadding: `bottom - below` on the LAST line
     *  (StaticLayout.java:1010), so it is >= 0. */
    public int getBottomPadding() { ensureLines(); applyLineHeightSpans(); return bottomPadding; }

    /** AOSP Layout.java:968 -- the base class hardcodes `return false`; every
     *  AOSP subclass that wants a different answer carries its OWN field and
     *  overrides this (StaticLayout.java:1406 `return mFallbackLineSpacing`;
     *  BoringLayout.java's own `mUseFallbackLineSpacing` -- both fetched and
     *  read directly, android13-release). We share one field on the base
     *  class instead (see the field comment), so StaticLayout/BoringLayout
     *  need no override at all -- this IS their answer, once their Builder/
     *  ctor threads the value in via macapkSetUseFallbackLineSpacing.
     *
     *  DynamicLayout is deliberately NOT threaded (no
     *  macapkSetUseFallbackLineSpacing call anywhere in DynamicLayout.java):
     *  AOSP's own DynamicLayout.java has no isFallbackLineSpacingEnabled()
     *  override either, despite threading mFallbackLineSpacing into its OWN
     *  internal StaticLayout build (DynamicLayout.java:604,
     *  `.setUseLineSpacingFromFallbacks(mFallbackLineSpacing)`) -- so a real
     *  DynamicLayout's public getter answers false from the inherited Layout
     *  default REGARDLESS of what its own Builder was given. That is AOSP's
     *  actual observable behavior (confirmed by the absence of the override
     *  in AOSP source), not a gap of ours to close. */
    public boolean isFallbackLineSpacingEnabled() { return useFallbackLineSpacing; }
    /** AOSP Layout.getPrimaryHorizontal(int) (android14-release Layout.java:
     *  1324-1337) -> getHorizontal(offset, trailing, clamped) (:1369-1398):
     *
     *      int start = getLineStart(line);
     *      ...
     *      float wid = tl.measure(offset - start, trailing, null);
     *      ...
     *      return getLineStartPos(line, left, right) + wid;
     *
     *  i.e. "how far along the line does a caret inserted at `offset` sit".
     *
     *  This used to be a hardcoded `return 0f`, and it is NOT a corner the
     *  fleet can ignore: JETPACK COMPOSE DRAWS ITS TEXT CARET FROM IT.
     *  Compose's LayoutHelper.getHorizontalPosition (androidx.compose.ui.text
     *  .android; verified in Aurora Store 4.8.4's own R8-minified `c84.b/.c`,
     *  which invokevirtuals android/text/Layout.getPrimaryHorizontal and
     *  .getSecondaryHorizontal) feeds AndroidParagraph.getCursorRect, so with
     *  0f every Compose TextField painted its caret at the LEFT EDGE of the
     *  field no matter where the caret actually was. Measured 2026-08-23 on
     *  Aurora Store's search box: the field read "vlcd" with the cursor drawn
     *  before the "v". Same zero also flattened selection-handle placement,
     *  getCursorPath and every getBoundingBox-style query on the View path.
     *
     *  LTR only, deliberately and in step with the rest of this class:
     *  getOffsetForHorizontal (:687) and isRtlCharAt (:698) make the same
     *  simplification, and the two are now exact inverses of each other --
     *  tap-to-place-caret and draw-the-caret agree by construction, which is
     *  the property that actually matters on screen. A bidi line would need
     *  the TextLine/Directions machinery this tree does not have; when that
     *  lands, both methods move together.
     *
     *  getLineLeft() stands in for AOSP's getLineStartPos(line, left, right):
     *  for an LTR paragraph the two agree by definition, and it already
     *  carries this class's CENTER/RIGHT/OPPOSITE alignment handling. */
    public float getPrimaryHorizontal(int offset) {
        ensureLines();
        if (paint == null || text == null) return 0f;
        final int len = textLen();
        if (offset < 0) offset = 0;
        if (offset > len) offset = len;
        final int line = getLineForOffset(offset);
        final int start = getLineStart(line);
        // Clamp to this line's own VISIBLE end. getLineEnd() includes the
        // trailing '\n', and an offset sitting on that newline belongs to the
        // end of this line rather than to the start of the next one -- the
        // same trim lineText() applies before measuring a line's width, so
        // getPrimaryHorizontal(lineEnd) and getLineWidth() agree.
        int end = getLineEnd(line);
        if (end > start && end <= len && text.charAt(end - 1) == '\n') end--;
        if (offset < start) offset = start;
        if (offset > end) offset = end;
        final float wid = (offset > start)
            ? macapkMeasure(text, start, offset)
            : 0f;
        return getLineLeft(line) + wid;
    }
    public float getSecondaryHorizontal(int offset) { return getPrimaryHorizontal(offset); }

    /** AOSP Layout.getSelectionPath (android16-release Layout.java:3110-3114)
     *  -> getSelection(start, end, consumer) (:3126-3179) -> addSelection
     *  (:3064-3103):
     *
     *      public void getSelectionPath(int start, int end, Path dest) {
     *          dest.reset();
     *          getSelection(start, end, (left, top, right, bottom, layout) ->
     *                  dest.addRect(left, top, right, bottom, Path.Direction.CW));
     *      }
     *
     *  This method used to be the `dest.reset()` and nothing else, so the
     *  highlight Path handed back was always EMPTY and a text selection
     *  rendered NO highlight anywhere -- both the View/Editor path and
     *  Compose's AndroidParagraph paint the selection by filling exactly this
     *  Path, and filling an empty Path paints nothing.
     *
     *  The three cases AOSP distinguishes, reproduced:
     *    - start == end -> empty path (an empty selection has no highlight);
     *    - both offsets on ONE line -> a single rect between the two caret x's;
     *    - across lines -> the first line's tail, every whole line between, and
     *      the last line's head. AOSP emits the "blank remainder of the line"
     *      filler as its OWN rect rather than merging it into the text rect
     *      (:3146-3153 and :3170-3177), and so does this, because the two are
     *      only adjacent when getLineRight() lands exactly on the last glyph.
     *
     *  LTR ONLY, deliberately, and in step with the rest of this class:
     *  isRtlCharAt (:698) is hardcoded false and there is no Directions /
     *  TextLine machinery here, so AOSP's per-direction-run loop inside
     *  addSelection collapses to the single run
     *  [max(start, lineStart), min(end, lineEnd)] and the DIR_RIGHT_TO_LEFT
     *  filler branches are unreachable. When bidi lands, this moves together
     *  with getPrimaryHorizontal and getOffsetForHorizontal, which make the
     *  same simplification.
     *
     *  Note the x coordinates come from getPrimaryHorizontal, which was itself
     *  a hardcoded 0f until v1.0.1036 -- so even a correct rect walk landed
     *  before that would have produced a zero-width highlight pinned to the
     *  left edge. The two fixes only work as a pair. */
    public void getSelectionPath(int start, int end, android.graphics.Path dest) {
        if (dest == null) return;
        dest.reset();
        if (start == end) return;
        // AOSP getSelection (:3131-3135) normalizes a reversed range rather
        // than rejecting it; a drag-to-select upwards produces end < start.
        if (end < start) { int t = end; end = start; start = t; }
        ensureLines();
        final int len = textLen();
        if (start < 0) start = 0;
        if (end > len) end = len;
        if (start >= end) return;

        final int startline = getLineForOffset(start);
        final int endline = getLineForOffset(end);
        final float w = getWidth();

        if (startline == endline) {
            macapkAddSelectionRun(dest, startline, start, end,
                                  getLineTop(startline), getLineBottom(endline));
            return;
        }
        // First line: `start` to the end of its text, then the blank remainder
        // out to the layout width (AOSP :3143-3153).
        int top = getLineTop(startline);
        int bottom = getLineBottom(startline);
        macapkAddSelectionRun(dest, startline, start, getLineEnd(startline), top, bottom);
        macapkAddSelectionRect(dest, getLineRight(startline), top, w, bottom);
        // Every whole line in between (AOSP :3155-3163).
        for (int i = startline + 1; i < endline; i++) {
            macapkAddSelectionRect(dest, 0f, getLineTop(i), w, getLineBottom(i));
        }
        // Last line: its head up to `end`, then the blank run to its left,
        // which is non-empty only under a CENTER / RIGHT alignment
        // (AOSP :3165-3177).
        top = getLineTop(endline);
        bottom = getLineBottom(endline);
        macapkAddSelectionRun(dest, endline, getLineStart(endline), end, top, bottom);
        macapkAddSelectionRect(dest, 0f, top, getLineLeft(endline), bottom);
    }

    /** The LTR reduction of AOSP's addSelection (Layout.java:3064-3103): one
     *  rect for the part of [start, end) that lies on `line`. AOSP loops over
     *  the line's direction runs and emits a rect per run; with one implicit
     *  LTR run per line that loop has exactly one iteration. */
    private void macapkAddSelectionRun(android.graphics.Path dest, int line,
                                       int start, int end, int top, int bottom) {
        int linestart = getLineStart(line);
        int lineend = getLineEnd(line);
        // AOSP :3070-3072 -- a trailing '\n' is not part of the selectable run
        // on this line, it belongs to the break itself.
        if (lineend > linestart && lineend <= textLen()
                && text != null && text.charAt(lineend - 1) == '\n') {
            lineend--;
        }
        final int st = Math.max(start, linestart);
        final int en = Math.min(end, lineend);
        if (st >= en) return;                       // AOSP :3084, `if (st != en)`
        final float h1 = getPrimaryHorizontal(st);
        final float h2 = getPrimaryHorizontal(en);
        macapkAddSelectionRect(dest, Math.min(h1, h2), top, Math.max(h1, h2), bottom);
    }

    /** Skia's SkPath::addRect drops a degenerate rect, so AOSP's consumer can
     *  hand one over freely (the zero-width fillers above do exactly that on a
     *  left-aligned line). Guard here so our Path is not left holding an empty
     *  subpath that a later isEmpty()/bounds query would misreport. */
    private static void macapkAddSelectionRect(android.graphics.Path dest,
                                               float l, float t, float r, float b) {
        if (r <= l || b <= t) return;
        dest.addRect(l, t, r, b, android.graphics.Path.Direction.CW);
    }

    /** AOSP Layout.getCursorPath (android16-release Layout.java:3001-3061):
     *
     *      dest.reset();
     *      int line = getLineForOffset(point);
     *      int top = getLineTop(line);
     *      int bottom = getLineBottom(line, false);
     *      float h1 = getPrimaryHorizontal(point, clamped) - 0.5f;
     *      ...
     *      if (h1 < 0.5f) h1 = 0.5f;
     *      dest.moveTo(h1, top);
     *      dest.lineTo(h1, bottom);
     *
     *  The method was MISSING from this class entirely, so guest code that
     *  draws its caret through the Layout (rather than through Compose's own
     *  getCursorRect) got a NoSuchMethodError instead of a caret.
     *
     *  The plain vertical line IS the whole path here, and that is AOSP's own
     *  behaviour rather than a shortcut: the shift / alt arrowhead decorations
     *  (:3026-3060) are gated on
     *  `TextKeyListener.getMetaState(editingBuffer, META_SHIFT_ON | ...)` being
     *  non-zero, and this tree's MetaKeyKeyListener.getMetaState is a
     *  documented constant 0 because Mac.apk routes text input through the
     *  IME / InputConnection path, not the hardware meta-state machine
     *  (MetaKeyKeyListener.java:9-12). With caps == fn == 0, AOSP draws exactly
     *  the two-point line below.
     *
     *  AOSP's `clamped` argument comes from shouldClampCursor(line), which is
     *  true for the LTR ALIGN_NORMAL case this class supports; our
     *  getPrimaryHorizontal already clamps the offset into the line's own
     *  visible range, so the clamped and unclamped answers coincide. */
    public void getCursorPath(int point, android.graphics.Path dest,
                              CharSequence editingBuffer) {
        if (dest == null) return;
        dest.reset();
        ensureLines();
        final int line = getLineForOffset(point);
        final int top = getLineTop(line);
        final int bottom = getLineBottom(line);
        // The -0.5f then clamp-to-0.5f is AOSP's own half-pixel centring of a
        // 1px stroke (:3006, :3024-3025), not a rounding nicety: without it a
        // caret at offset 0 straddles x=0 and half of it is clipped away.
        float h1 = getPrimaryHorizontal(point) - 0.5f;
        if (h1 < 0.5f) h1 = 0.5f;
        dest.moveTo(h1, top);
        dest.lineTo(h1, bottom);
    }
    public Alignment getParagraphAlignment(int line) { return getAlignment(); }

    /** AOSP: the end offset of `line` EXCLUDING trailing whitespace -- what
     *  getLineMax and every alignment computation are defined against.
     *
     *  This was `return getLineEnd(line)`, i.e. it INCLUDED the trailing
     *  whitespace it exists to exclude, which made it indistinguishable from
     *  getLineEnd and silently wrong for its one purpose. */
    public int getLineVisibleEnd(int line) {
        ensureLines();
        int st = getLineStart(line), en = getLineEnd(line);
        if (text == null) return st;
        if (en > st && en <= textLen() && text.charAt(en - 1) == '\n') en--;
        while (en > st) {
            char c = text.charAt(en - 1);
            if (c != ' ' && c != '\t') break;
            en--;
        }
        return en;
    }
    public int getEllipsizedWidth() { return getWidth(); }

    // =====================================================================
    // API-36 surface that was missing wholesale. Sources cited per method
    // against frameworks/base/core/java/android/text/Layout.java
    // (android16-release).
    // =====================================================================

    /** AOSP Layout.java:212-214: {@code RectF::intersects}. */
    public static final TextInclusionStrategy INCLUSION_STRATEGY_ANY_OVERLAP =
            new TextInclusionStrategy() {
                @Override public boolean isSegmentInside(android.graphics.RectF segmentBounds,
                        android.graphics.RectF area) {
                    return android.graphics.RectF.intersects(segmentBounds, area);
                }
            };

    /** AOSP Layout.java:220-222. */
    public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_CENTER =
            new TextInclusionStrategy() {
                @Override public boolean isSegmentInside(android.graphics.RectF segmentBounds,
                        android.graphics.RectF area) {
                    return area.contains(segmentBounds.centerX(), segmentBounds.centerY());
                }
            };

    /** AOSP Layout.java:229-230. */
    public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_ALL =
            new TextInclusionStrategy() {
                @Override public boolean isSegmentInside(android.graphics.RectF segmentBounds,
                        android.graphics.RectF area) {
                    return area.contains(segmentBounds);
                }
            };

    /** AOSP Layout.java:3430-3432. Whether the text this layout was built over
     *  carries spans -- the flag AOSP branches on before ever calling
     *  getSpans(). Protected + final, exactly as AOSP declares it. */
    protected final boolean isSpanned() {
        return text instanceof Spanned;
    }

    /**
     * AOSP Layout.java:1259-1265:
     *     if (wid < mWidth) throw new RuntimeException("attempted to reduce Layout width");
     *     mWidth = wid;
     * Note AOSP's own doc: it does NOT reflow. Ours does re-break lazily on the
     * next query (the base class recomputes whenever lineStarts is null), which
     * would be a DIFFERENT observable result, so the cached breaks are
     * deliberately left in place here to match the documented contract.
     */
    public final void increaseWidthTo(int wid) {
        if (wid < width) {
            throw new RuntimeException("attempted to reduce Layout width");
        }
        width = wid;
    }

    /** AOSP Layout.java:1377-1385. Fills `bounds` with the line's extents and
     *  returns its baseline. AOSP's own "// ???" comments mark left/right as
     *  the whole layout width rather than the line's ink. */
    public int getLineBounds(int line, android.graphics.Rect bounds) {
        if (bounds != null) {
            bounds.left = 0;
            bounds.top = getLineTop(line);
            bounds.right = width;
            bounds.bottom = getLineTop(line + 1);
        }
        return getLineBaseline(line);
    }

    /**
     * AOSP Layout.java:2846-2852:
     *     if (includeLineSpacing) return getLineTop(line + 1);
     *     else return getLineTop(line + 1) - getLineExtra(line);
     *
     * getLineExtra() is @hide and returns 0 on the base class (Layout.java:
     * 2884), which is what this tree computes too -- our per-line spacing is
     * already folded into lineTops rather than tracked separately -- so both
     * branches agree here, as they do for a plain AOSP Layout.
     *
     */
    public int getLineBottom(int line, boolean includeLineSpacing) {
        return getLineTop(line + 1);
    }

    /** AOSP Layout.java:3210-3218. The left edge of the paragraph, inset by a
     *  LeadingMarginSpan. Zero for an RTL paragraph or unspanned text; our
     *  leading-margin support is limited to the unspanned answer, so a
     *  LeadingMarginSpan does not inset this yet (see getParagraphRight). */
    public final int getParagraphLeft(int line) {
        int left = 0;
        int dir = getParagraphDirection(line);
        if (dir == DIR_RIGHT_TO_LEFT || !isSpanned()) {
            return left; // leading margin has no impact, or no styles
        }
        return left + getParagraphLeadingMargin(line);
    }

    /** AOSP Layout.java:3222-3229. */
    public final int getParagraphRight(int line) {
        int right = width;
        int dir = getParagraphDirection(line);
        if (dir == DIR_LEFT_TO_RIGHT || !isSpanned()) {
            return right; // leading margin has no impact, or no styles
        }
        return right - getParagraphLeadingMargin(line);
    }

    /**
     * AOSP Layout.java:3236+ getParagraphLeadingMargin(line): sums the
     * LeadingMarginSpan.getLeadingMargin() of every such span covering the
     * line, with LeadingMarginSpan2 counted only for its first `n` lines.
     */
    private int getParagraphLeadingMargin(int line) {
        if (!isSpanned()) return 0;
        Spanned sp = (Spanned) text;
        int lineStart = getLineStart(line);
        int lineEnd = getLineEnd(line);
        android.text.style.LeadingMarginSpan[] spans;
        try {
            spans = sp.getSpans(lineStart, lineEnd, android.text.style.LeadingMarginSpan.class);
        } catch (Throwable ignored) {
            return 0;
        }
        if (spans == null || spans.length == 0) return 0;
        int margin = 0;
        boolean useFirstLineMargin = lineStart == 0
                || (lineStart > 0 && text.charAt(lineStart - 1) == '\n');
        for (int i = 0; i < spans.length; i++) {
            if (spans[i] instanceof android.text.style.LeadingMarginSpan.LeadingMarginSpan2) {
                int spStart = sp.getSpanStart(spans[i]);
                int spanLine = getLineForOffset(spStart);
                int count = ((android.text.style.LeadingMarginSpan.LeadingMarginSpan2)
                        spans[i]).getLeadingMarginLineCount();
                // Line might be -1 if the span is not attached to the text.
                useFirstLineMargin = spanLine >= 0 && line < spanLine + count;
            }
        }
        for (int i = 0; i < spans.length; i++) {
            margin += spans[i].getLeadingMargin(useFirstLineMargin);
        }
        return margin;
    }

    /** AOSP Layout.java:2882-2884. */
    public int getOffsetToLeftOf(int offset) {
        return getOffsetToLeftRightOf(offset, true);
    }

    /** AOSP Layout.java:2886-2888. */
    public int getOffsetToRightOf(int offset) {
        return getOffsetToLeftRightOf(offset, false);
    }

    /**
     * AOSP Layout.java:2890-2946. The line-crossing half is reproduced
     * verbatim: whether moving left ADVANCES depends on the paragraph
     * direction, walking off the end of a line moves to the next line (or
     * refuses at the very end), and crossing into a line with the opposite
     * direction flips the requested direction.
     *
     * ⚠ AOSP then delegates the WITHIN-LINE step to
     * TextLine.getOffsetToLeftRightOf, which walks the bidi runs and stops on
     * grapheme-cluster boundaries. This tree has no TextLine/Directions
     * machinery -- getLineDirections() always answers DIRS_ALL_LEFT_TO_RIGHT
     * and isRtlCharAt() always answers false -- so within a line we step by one
     * CODE POINT (surrogate pairs kept together), which is exactly what the
     * bidi walk reduces to for the all-LTR layout this class produces. On
     * mixed-direction text the caret will step in logical rather than visual
     * order. When the TextLine machinery lands, this and getPrimaryHorizontal
     * move together.
     */
    private int getOffsetToLeftRightOf(int caret, boolean toLeft) {
        ensureLines();
        final int len = textLen();
        if (caret < 0) caret = 0;
        if (caret > len) caret = len;

        int line = getLineForOffset(caret);
        int lineStart = getLineStart(line);
        int lineEnd = getLineEnd(line);
        int lineDir = getParagraphDirection(line);

        boolean lineChanged = false;
        boolean advance = toLeft == (lineDir == DIR_RIGHT_TO_LEFT);
        // if walking off line, look at the line we are headed to
        if (advance) {
            if (caret == lineEnd) {
                if (line < getLineCount() - 1) {
                    lineChanged = true;
                    ++line;
                } else {
                    return caret; // at very end, don't move
                }
            }
        } else {
            if (caret == lineStart) {
                if (line > 0) {
                    lineChanged = true;
                    --line;
                } else {
                    return caret; // at very start, don't move
                }
            }
        }

        if (lineChanged) {
            lineStart = getLineStart(line);
            lineEnd = getLineEnd(line);
            int newDir = getParagraphDirection(line);
            if (newDir != lineDir) {
                // unusual case: we want to walk onto the line, but it runs in a
                // different direction than this one, so we fake movement in the
                // opposite direction.
                toLeft = !toLeft;
                lineDir = newDir;
            }
        }

        final boolean back = toLeft == (lineDir == DIR_LEFT_TO_RIGHT);
        int result = back ? prevCodePointOffset(caret) : nextCodePointOffset(caret);
        if (result < lineStart) result = lineStart;
        if (result > lineEnd) result = lineEnd;
        return result;
    }

    private int prevCodePointOffset(int offset) {
        if (offset <= 0) return 0;
        int o = offset - 1;
        if (o > 0 && text != null && Character.isLowSurrogate(text.charAt(o))
                && Character.isHighSurrogate(text.charAt(o - 1))) {
            o--;
        }
        return o;
    }

    private int nextCodePointOffset(int offset) {
        final int len = textLen();
        if (offset >= len) return len;
        int o = offset + 1;
        if (o < len && text != null && Character.isHighSurrogate(text.charAt(offset))
                && Character.isLowSurrogate(text.charAt(o))) {
            o++;
        }
        return o;
    }

    /**
     * AOSP Layout.java:1859-1893. Fills `bounds` with four floats per character
     * in [start, end): left, top, right, bottom, starting at `boundsStart`. The
     * three throws are part of the contract and are reproduced exactly, in
     * AOSP's order (range check, null check, capacity check).
     *
     * AOSP measures each character through TextLine's horizontal bounds; we use
     * getPrimaryHorizontal on either side of the character, which is the same
     * quantity for the LTR layouts this class produces.
     */
    public void fillCharacterBounds(int start, int end, float[] bounds, int boundsStart) {
        final int len = textLen();
        if (start < 0 || end < start || end > len) {
            throw new IndexOutOfBoundsException("given range: " + start + ", " + end + " is "
                    + "out of the text range: 0, " + len);
        }
        if (bounds == null) {
            throw new IllegalArgumentException("bounds can't be null.");
        }
        final int neededLength = 4 * (end - start);
        if (neededLength > bounds.length - boundsStart) {
            throw new IndexOutOfBoundsException("bounds doesn't have enough space to store the "
                    + "result, needed: " + neededLength + " had: "
                    + (bounds.length - boundsStart));
        }
        if (start == end) {
            return;
        }
        ensureLines();
        for (int index = start; index < end; index++) {
            final int line = getLineForOffset(index);
            final int boundsIndex = boundsStart + 4 * (index - start);
            bounds[boundsIndex] = getPrimaryHorizontal(index);
            bounds[boundsIndex + 1] = getLineTop(line);
            bounds[boundsIndex + 2] = getPrimaryHorizontal(index + 1);
            bounds[boundsIndex + 3] = getLineBottom(line);
        }
    }

    /**
     * AOSP Layout.java:1303-1345. The bounding box of what is actually DRAWN,
     * which can differ from lineTop/lineBottom because those come from the
     * font's metrics while this comes from the glyphs.
     *
     * AOSP walks each line through TextLine.metrics into a RectF. Without that
     * machinery we report the union of each line's laid-out box
     * (getLineLeft/getLineTop/getLineRight/getLineBottom), which is the same
     * rectangle whenever no glyph overhangs its advance -- the case for every
     * layout this class currently produces. It is never null, matching @NonNull.
     */
    public android.graphics.RectF computeDrawingBoundingBox() {
        ensureLines();
        android.graphics.RectF out = new android.graphics.RectF(0f, 0f, 0f, 0f);
        final int lineCount = getLineCount();
        if (lineCount == 0) return out;
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (int line = 0; line < lineCount; line++) {
            left = Math.min(left, getLineLeft(line));
            right = Math.max(right, getLineRight(line));
            top = Math.min(top, getLineTop(line));
            bottom = Math.max(bottom, getLineBottom(line));
        }
        out.set(left, top, right, bottom);
        return out;
    }

    /**
     * AOSP Layout.java:467-471 -- draw with a highlight path (a selection
     * rectangle or a caret) painted between the background and the text.
     * Delegates to the six-argument form, as AOSP does.
     */
    public void draw(android.graphics.Canvas canvas, android.graphics.Path selectionHighlight,
            android.graphics.Paint selectionHighlightPaint, int cursorOffsetVertical) {
        draw(canvas, null, null, selectionHighlight, selectionHighlightPaint,
                cursorOffsetVertical);
    }

    /**
     * AOSP Layout.java:496-540 -- the full draw: background, then each
     * highlight path with its paint, then the selection/cursor path, then the
     * text on top. `cursorOffsetVertical` translates the canvas for the
     * highlight phase only, which is why the save/restore wraps just that part.
     *
     * highlightPaths and highlightPaints are index-aligned; AOSP requires
     * equal lengths, and we simply stop at the shorter of the two rather than
     * throwing, because a mismatch is a caller bug that should not take out
     * the whole draw.
     */
    public void draw(android.graphics.Canvas canvas,
            java.util.List<android.graphics.Path> highlightPaths,
            java.util.List<android.graphics.Paint> highlightPaints,
            android.graphics.Path selectionPath,
            android.graphics.Paint selectionPaint,
            int cursorOffsetVertical) {
        if (canvas == null) return;
        drawBackground(canvas);
        final boolean hasHighlight = (highlightPaths != null && highlightPaints != null
                && !highlightPaths.isEmpty()) || selectionPath != null;
        if (hasHighlight) {
            int saved = -1;
            if (cursorOffsetVertical != 0) {
                saved = canvas.save();
                canvas.translate(0, cursorOffsetVertical);
            }
            if (highlightPaths != null && highlightPaints != null) {
                int n = Math.min(highlightPaths.size(), highlightPaints.size());
                for (int i = 0; i < n; i++) {
                    android.graphics.Path path = highlightPaths.get(i);
                    android.graphics.Paint p = highlightPaints.get(i);
                    if (path != null && p != null) canvas.drawPath(path, p);
                }
            }
            if (selectionPath != null && selectionPaint != null) {
                canvas.drawPath(selectionPath, selectionPaint);
            }
            if (saved >= 0) canvas.restoreToCount(saved);
        }
        drawText(canvas);
    }

    /**
     * AOSP Layout.java:563-570 -- the TEXT half of draw(), with no background,
     * highlight or selection. draw(Canvas) is the composition of
     * drawBackground(canvas) + drawText(canvas), and this class's existing
     * one-argument draw() is that composition, so drawText delegates to it.
     */
    public void drawText(android.graphics.Canvas canvas) {
        if (canvas == null) return;
        draw(canvas);
    }

    /**
     * AOSP Layout.java:583-589 -- the BACKGROUND half of draw(): the
     * per-paragraph background painted by any LineBackgroundSpan on the text.
     * AOSP draws nothing here when the text carries no such span, which is the
     * common case and the only case this tree can reach today (nothing
     * currently constructs a LineBackgroundSpan-bearing layout), so an
     * unspanned layout is a no-op exactly as on a device.
     */
    public void drawBackground(android.graphics.Canvas canvas) {
        if (canvas == null || !isSpanned()) return;
        Spanned sp = (Spanned) text;
        android.text.style.LineBackgroundSpan[] spans;
        try {
            spans = sp.getSpans(0, textLen(), android.text.style.LineBackgroundSpan.class);
        } catch (Throwable ignored) {
            return;
        }
        if (spans == null || spans.length == 0) return;
        ensureLines();
        final int lineCount = getLineCount();
        for (int line = 0; line < lineCount; line++) {
            final int lineStart = getLineStart(line);
            final int lineEnd = getLineEnd(line);
            final int top = getLineTop(line);
            final int baseline = getLineBaseline(line);
            final int bottom = getLineBottom(line);
            for (int i = 0; i < spans.length; i++) {
                int spanStart = sp.getSpanStart(spans[i]);
                int spanEnd = sp.getSpanEnd(spans[i]);
                if (spanEnd <= lineStart || spanStart >= lineEnd) continue;
                try {
                    spans[i].drawBackground(canvas, paint, 0, width, top, baseline, bottom,
                            text, lineStart, lineEnd, line);
                } catch (Throwable ignored) {}
            }
        }
    }

    // ---- Builder-parameter getters. AOSP declares all of these final. -------

    /** AOSP Layout.java:2400+. The strategy the layout was BUILT with.
     *  ⚠ Reports what the Builder was given; our line breaker is always the
     *  greedy BREAK_STRATEGY_SIMPLE algorithm, so a layout built with
     *  BREAK_STRATEGY_BALANCED answers BALANCED while breaking simply. */
    public final int getBreakStrategy() { return breakStrategy; }

    /** AOSP Layout.java:2410+. ⚠ Same caveat: we do not hyphenate. */
    public final int getHyphenationFrequency() { return hyphenationFrequency; }

    /** AOSP Layout.java:2420+. ⚠ Same caveat: we do not justify. */
    public final int getJustificationMode() { return justificationMode; }

    /** AOSP Layout.java:2430+. Null when no indents were set, as on AOSP. */
    public final int[] getLeftIndents() { return leftIndents; }

    /** AOSP Layout.java:2440+. */
    public final int[] getRightIndents() { return rightIndents; }

    /** AOSP Layout.java:2450+. The truncation mode this layout was built with,
     *  null when none. Backed by the same field configure() already stores. */
    public final android.text.TextUtils.TruncateAt getEllipsize() { return ellipsizeMode; }

    /** AOSP Layout.java:2460+. */
    public final int getMaxLines() { return maxLines; }

    /** AOSP Layout.java:2470+ getLineSpacingAmount() -- the `add` of
     *  setLineSpacing(add, mult). */
    public final float getLineSpacingAmount() { return spacingAdd; }

    /** AOSP Layout.java:2480+ getLineSpacingMultiplier() -- the `mult`. */
    public final float getLineSpacingMultiplier() { return spacingMult; }

    /** AOSP Layout.java:2490+ isFontPaddingIncluded() -- the includePad the
     *  layout was built with. */
    public final boolean isFontPaddingIncluded() { return includePad; }

    /** AOSP Layout.java:2500+. */
    public boolean getUseBoundsForWidth() { return useBoundsForWidth; }

    /** AOSP Layout.java:2510+. */
    public boolean getShiftDrawingOffsetForStartOverhang() {
        return shiftDrawingOffsetForStartOverhang;
    }

    /** AOSP Layout.java:2520+. Null unless the Builder was given one, which is
     *  AOSP's documented default. */
    public android.graphics.Paint.FontMetrics getMinimumFontMetrics() {
        return minimumFontMetrics;
    }

    /** AOSP Layout.java:2530+. Never null -- LineBreakConfig.NONE when unset. */
    public android.graphics.text.LineBreakConfig getLineBreakConfig() {
        return lineBreakConfig;
    }

    /**
     * AOSP Layout.Builder (API 35, Layout.java's nested `public static final
     * class Builder`) -- the generic layout builder that replaced reaching
     * straight for StaticLayout.Builder. build() returns a Layout, and AOSP's
     * own implementation picks BoringLayout when the text qualifies and
     * StaticLayout otherwise.
     *
     * We always build a StaticLayout. That is not a shortcut: BoringLayout is
     * a FAST PATH, not a different rendering, and this tree's
     * BoringLayout.isBoring() already declines it unconditionally (see that
     * file), so a device and Mac.apk produce the same geometry either way --
     * the only difference is which concrete class comes back, and AOSP's
     * contract here is a Layout.
     */
    public static final class Builder {
        private final CharSequence mText;
        private final int mStart;
        private final int mEnd;
        private final TextPaint mPaint;
        private final int mWidth;

        private Alignment mAlignment = Alignment.ALIGN_NORMAL;
        private TextDirectionHeuristic mTextDir = TextDirectionHeuristics.FIRSTSTRONG_LTR;
        private float mLineSpacingAmount = DEFAULT_LINESPACING_ADDITION;
        private float mLineSpacingMultiplier = DEFAULT_LINESPACING_MULTIPLIER;
        private boolean mIncludePad = true;
        private boolean mFallbackLineSpacing = false;
        private int mEllipsizedWidth;
        private TextUtils.TruncateAt mEllipsize;
        private int mMaxLines = Integer.MAX_VALUE;
        private int mBreakStrategy = BREAK_STRATEGY_SIMPLE;
        private int mHyphenationFrequency = HYPHENATION_FREQUENCY_NONE;
        private int[] mLeftIndents;
        private int[] mRightIndents;
        private int mJustificationMode = JUSTIFICATION_MODE_NONE;
        private boolean mUseBoundsForWidth = false;
        private boolean mShiftDrawingOffsetForStartOverhang = false;
        private android.graphics.Paint.FontMetrics mMinimumFontMetrics;
        private android.graphics.text.LineBreakConfig mLineBreakConfig =
                android.graphics.text.LineBreakConfig.NONE;

        public Builder(CharSequence text, int start, int end, TextPaint paint, int width) {
            if (text == null) throw new NullPointerException("text must not be null");
            if (paint == null) throw new NullPointerException("paint must not be null");
            if (start < 0 || end < start || end > text.length()) {
                throw new IllegalArgumentException("invalid range: " + start + ", " + end);
            }
            if (width < 0) throw new IllegalArgumentException("width must not be negative");
            mText = text;
            mStart = start;
            mEnd = end;
            mPaint = paint;
            mWidth = width;
            mEllipsizedWidth = width;
        }

        public Builder setAlignment(Alignment alignment) {
            mAlignment = alignment;
            return this;
        }

        public Builder setTextDirectionHeuristic(TextDirectionHeuristic textDirection) {
            mTextDir = textDirection;
            return this;
        }

        /** AOSP names the two line-spacing setters after the GETTERS on Layout
         *  (getLineSpacingAmount / getLineSpacingMultiplier), not after
         *  StaticLayout.Builder.setLineSpacing(add, mult). */
        public Builder setLineSpacingAmount(float lineSpacingAmount) {
            mLineSpacingAmount = lineSpacingAmount;
            return this;
        }

        public Builder setLineSpacingMultiplier(float lineSpacingMultiplier) {
            mLineSpacingMultiplier = lineSpacingMultiplier;
            return this;
        }

        /** AOSP setFontPaddingIncluded -- StaticLayout.Builder spells the same
         *  thing setIncludePad. */
        public Builder setFontPaddingIncluded(boolean fontPaddingIncluded) {
            mIncludePad = fontPaddingIncluded;
            return this;
        }

        public Builder setFallbackLineSpacingEnabled(boolean fallbackLineSpacing) {
            mFallbackLineSpacing = fallbackLineSpacing;
            return this;
        }

        public Builder setEllipsizedWidth(int ellipsizedWidth) {
            mEllipsizedWidth = ellipsizedWidth;
            return this;
        }

        public Builder setEllipsize(TextUtils.TruncateAt ellipsize) {
            mEllipsize = ellipsize;
            return this;
        }

        public Builder setMaxLines(int maxLines) {
            mMaxLines = maxLines;
            return this;
        }

        public Builder setBreakStrategy(int breakStrategy) {
            mBreakStrategy = breakStrategy;
            return this;
        }

        public Builder setHyphenationFrequency(int hyphenationFrequency) {
            mHyphenationFrequency = hyphenationFrequency;
            return this;
        }

        public Builder setLeftIndents(int[] leftIndents) {
            mLeftIndents = leftIndents;
            return this;
        }

        public Builder setRightIndents(int[] rightIndents) {
            mRightIndents = rightIndents;
            return this;
        }

        public Builder setJustificationMode(int justificationMode) {
            mJustificationMode = justificationMode;
            return this;
        }

        public Builder setUseBoundsForWidth(boolean useBoundsForWidth) {
            mUseBoundsForWidth = useBoundsForWidth;
            return this;
        }

        public Builder setShiftDrawingOffsetForStartOverhang(boolean shift) {
            mShiftDrawingOffsetForStartOverhang = shift;
            return this;
        }

        public Builder setMinimumFontMetrics(android.graphics.Paint.FontMetrics metrics) {
            mMinimumFontMetrics = metrics;
            return this;
        }

        public Builder setLineBreakConfig(android.graphics.text.LineBreakConfig lineBreakConfig) {
            if (lineBreakConfig != null) mLineBreakConfig = lineBreakConfig;
            return this;
        }

        public Layout build() {
            StaticLayout.Builder b = StaticLayout.Builder
                    .obtain(mText, mStart, mEnd, mPaint, mWidth)
                    .setAlignment(mAlignment)
                    .setTextDirection(mTextDir)
                    .setLineSpacing(mLineSpacingAmount, mLineSpacingMultiplier)
                    .setIncludePad(mIncludePad)
                    .setUseLineSpacingFromFallbacks(mFallbackLineSpacing)
                    .setEllipsizedWidth(mEllipsizedWidth)
                    .setEllipsize(mEllipsize)
                    .setMaxLines(mMaxLines)
                    .setBreakStrategy(mBreakStrategy)
                    .setHyphenationFrequency(mHyphenationFrequency)
                    .setIndents(mLeftIndents, mRightIndents)
                    .setJustificationMode(mJustificationMode)
                    .setUseBoundsForWidth(mUseBoundsForWidth)
                    .setShiftDrawingOffsetForStartOverhang(mShiftDrawingOffsetForStartOverhang)
                    .setMinimumFontMetrics(mMinimumFontMetrics)
                    .setLineBreakConfig(mLineBreakConfig);
            return b.build();
        }
    }

    /**
     * AOSP Layout.java:2960+ getLineLetterSpacingUnitCount(line, includeTrailingWhitespace):
     * the number of letter-spacing units on the line, which is only non-zero
     * under JUSTIFICATION_MODE_INTER_CHARACTER. We never justify, so this is
     * always 0 -- the same answer a device gives for an unjustified layout.
     * The range check is AOSP's (@IntRange(from = 0) on both the argument and
     * the result).
     */
    public int getLineLetterSpacingUnitCount(int line, boolean includeTrailingWhitespace) {
        return 0;
    }
}



