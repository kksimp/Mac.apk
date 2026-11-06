package android.text;

/**
 * Mac.apk-internal: AOSP TextLine.handleReplacement for Mac.apk's two
 * run-splitting line painters, android.widget.TextView.macapkDrawLine and
 * Layout.drawSpannedLine.
 *
 * Both painters split a line at every CharacterStyle transition, apply the
 * covering spans' updateDrawState and draw the run's characters with
 * drawText. A ReplacementSpan (ImageSpan, DynamicDrawableSpan, an app's emoji
 * span) is a CharacterStyle too, so its run went down the same path: its
 * updateDrawState is a no-op, and the characters it stands for were drawn as
 * text. The measure side (Layout.macapkMeasureSpanned) already sized such a
 * run by getSize(), so the line made room for the image and then painted the
 * raw characters into it -- every inline image and every app-drawn emoji was
 * missing. WhatsApp draws its emoji (message text, reactions, the country
 * flags in the phone-number picker) as ImageSpans over the code points.
 *
 * AOSP never paints a replaced run as text: TextLine.handleRun hands it to
 * handleReplacement, which advances by getSize() and calls the span's draw()
 * with the line's top, baseline and bottom.
 */
public final class MacApkReplacement {
    private MacApkReplacement() {}

    /** The span ATTACHED to `sp` whose ReplacementSpan sizes the run [rs, re),
     *  or null. The same choice Layout.macapkMeasureSpanned makes -- the last
     *  covering MetricAffectingSpan whose getUnderlying() is a ReplacementSpan
     *  -- so the width drawn is the width measured. The attached span is
     *  returned rather than the replacement so the caller can read its range:
     *  a CharacterStyle.wrap()ped replacement is not itself in the text. */
    public static android.text.style.MetricAffectingSpan covering(Spanned sp, int rs, int re) {
        if (sp == null || re <= rs) return null;
        android.text.style.MetricAffectingSpan[] spans;
        try {
            spans = sp.getSpans(rs, re, android.text.style.MetricAffectingSpan.class);
        } catch (Throwable t) {
            return null;
        }
        if (spans == null) return null;
        android.text.style.MetricAffectingSpan found = null;
        for (android.text.style.MetricAffectingSpan s : spans) {
            if (s == null) continue;
            if (sp.getSpanStart(s) > rs || sp.getSpanEnd(s) < re) continue;
            if (s.getUnderlying() instanceof android.text.style.ReplacementSpan) found = s;
        }
        return found;
    }

    /** Paints `r` over [start, end) with its left edge at `x` and returns its
     *  advance, as handleReplacement does. top and bottom are the run's font
     *  box around the baseline; the canvas is restored afterwards, so a span
     *  that forgets its own restore cannot shift the rest of the line. */
    public static float draw(android.graphics.Canvas canvas,
                             android.text.style.ReplacementSpan r, Spanned sp,
                             int start, int end, float x, float baselineY,
                             android.text.TextPaint p) {
        float w;
        try {
            w = r.getSize(p, sp, start, end, null);
        } catch (Throwable t) {
            w = p.measureText(sp, start, end);
        }
        final int y = Math.round(baselineY);
        final int top = (int) Math.floor(baselineY + p.ascent());
        final int bottom = (int) Math.ceil(baselineY + p.descent());
        final int save = canvas.save();
        try {
            r.draw(canvas, sp, start, end, x, top, y, bottom, p);
        } catch (Throwable t) {
            // A guest span that throws must not take the frame down with it.
        } finally {
            canvas.restoreToCount(save);
        }
        return w;
    }
}
