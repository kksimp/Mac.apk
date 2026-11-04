// android.text.TextUtils -- the standard "is this string empty/null/
// a digit/etc." utilities. Apps reach for it constantly.
package android.text;

import java.util.BitSet;
import java.util.Iterator;

public class TextUtils {
    private TextUtils() {}

    // AOSP returns a SHARED zero-length array from both split() overloads
    // (TextUtils.java:2409 "private static String[] EMPTY_STRING_ARRAY = new
    // String[]{}"), so callers that compare identity keep working.
    private static final String[] EMPTY_STRING_ARRAY = new String[]{};

    // AOSP span type ids (TextUtils.java:681-741). These are the tags in the
    // writeToParcel / CHAR_SEQUENCE_CREATOR wire format below. They are @hide
    // in AOSP but the fields are public, and android.text.style spans in this
    // tree already reference the numbers by hand, so naming them here gives
    // everyone one authoritative table to check against.
    public static final int ALIGNMENT_SPAN = 1;
    public static final int FIRST_SPAN = ALIGNMENT_SPAN;
    public static final int FOREGROUND_COLOR_SPAN = 2;
    public static final int RELATIVE_SIZE_SPAN = 3;
    public static final int SCALE_X_SPAN = 4;
    public static final int STRIKETHROUGH_SPAN = 5;
    public static final int UNDERLINE_SPAN = 6;
    public static final int STYLE_SPAN = 7;
    public static final int BULLET_SPAN = 8;
    public static final int QUOTE_SPAN = 9;
    public static final int LEADING_MARGIN_SPAN = 10;
    public static final int URL_SPAN = 11;
    public static final int BACKGROUND_COLOR_SPAN = 12;
    public static final int TYPEFACE_SPAN = 13;
    public static final int SUPERSCRIPT_SPAN = 14;
    public static final int SUBSCRIPT_SPAN = 15;
    public static final int ABSOLUTE_SIZE_SPAN = 16;
    public static final int TEXT_APPEARANCE_SPAN = 17;
    public static final int ANNOTATION = 18;
    public static final int SUGGESTION_SPAN = 19;
    public static final int SPELL_CHECK_SPAN = 20;
    public static final int SUGGESTION_RANGE_SPAN = 21;
    public static final int EASY_EDIT_SPAN = 22;
    public static final int LOCALE_SPAN = 23;
    public static final int TTS_SPAN = 24;
    public static final int ACCESSIBILITY_CLICKABLE_SPAN = 25;
    public static final int ACCESSIBILITY_URL_SPAN = 26;
    public static final int LINE_BACKGROUND_SPAN = 27;
    public static final int LINE_HEIGHT_SPAN = 28;
    public static final int ACCESSIBILITY_REPLACEMENT_SPAN = 29;
    // AOSP TextUtils.java:794-798 (android16-release) -- the two ids added
    // with android.text.style.LineBreakConfigSpan (API 35) and
    // NoWritingToolsSpan (API 36). LAST_SPAN moves with them, and it is
    // load-bearing: readSpan's range check (`spanTypeId < FIRST_SPAN ||
    // spanTypeId > LAST_SPAN`) would otherwise REJECT a legitimately
    // parcelled span of either new type.
    public static final int LINE_BREAK_CONFIG_SPAN = 30;
    public static final int NO_WRITING_TOOLS_SPAN = 31;
    public static final int LAST_SPAN = NO_WRITING_TOOLS_SPAN;

    // AOSP makeSafeForPresentation flags (TextUtils.java:117-133). API 30.
    public static final int SAFE_STRING_FLAG_TRIM = 0x1;
    public static final int SAFE_STRING_FLAG_SINGLE_LINE = 0x2;
    public static final int SAFE_STRING_FLAG_FIRST_LINE = 0x4;

    // Standard AOSP enum used by TextView.setEllipsize. Apps reference
    // it by simple name (TruncateAt.END etc). Stub-only; Mac.apk
    // doesn't actually ellipsize text.
    public enum TruncateAt {
        START, MIDDLE, END, MARQUEE, END_SMALL
    }

    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().contentEquals(b);
    }

    public static int getTrimmedLength(CharSequence s) {
        if (s == null) return 0;
        int len = s.length();
        int start = 0; int end = len;
        while (start < end && s.charAt(start) <= ' ')   start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        return end - start;
    }

    // AOSP TextUtils.isDigitsOnly (TextUtils.java:1778-1787). Two behaviours the
    // previous char-at-a-time version got wrong, both probed:
    //   * an EMPTY sequence is digits-only (the loop never runs, so AOSP falls
    //     through to `return true`). We returned false via `s.length() > 0`.
    //   * iteration is by CODE POINT, so a supplementary-plane digit such as
    //     U+104A0 (OSMANYA DIGIT ZERO) counts. Reading it as two chars sees two
    //     unpaired surrogates, neither of which is a digit, and returned false.
    // AOSP has no null guard, so a null argument is an NPE from str.length().
    public static boolean isDigitsOnly(CharSequence str) {
        final int len = str.length();
        for (int cp, i = 0; i < len; i += Character.charCount(cp)) {
            cp = Character.codePointAt(str, i);
            if (!Character.isDigit(cp)) {
                return false;
            }
        }
        return true;
    }

    // AOSP TextUtils.join(CharSequence,Object[]) (frameworks/base core/java/
    // android/text/TextUtils.java:400-421): "delimiter ... If null, the
    // string "null" will be used as the delimiter." StringBuilder.append
    // (CharSequence) already renders a null argument as the 4-char literal
    // "null" per its own contract, so appending it unconditionally (instead
    // of skipping it when delimiter==null, as the previous impl did) is what
    // reproduces that documented behavior.
    // AOSP's javadoc is explicit that a null token array is NOT tolerated: "If
    // tokens is null, a NullPointerException will be thrown" (TextUtils.java:
    // 356-358), which falls out of the unguarded `tokens.length` read. The
    // previous `if (tokens == null) return ""` swallowed a caller's bug.
    public static String join(CharSequence delimiter, Object[] tokens) {
        final int length = tokens.length;
        if (length == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(tokens[0]);
        for (int i = 1; i < length; i++) {
            sb.append(delimiter);
            sb.append(tokens[i]);
        }
        return sb.toString();
    }
    /** Same contract as the array overload: a null Iterable is an NPE, here
     *  from the unguarded `tokens.iterator()` (AOSP TextUtils.java:385-386). */
    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        Iterator<?> it = tokens.iterator();
        if (!it.hasNext()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(it.next());
        while (it.hasNext()) {
            sb.append(delimiter);
            sb.append(it.next());
        }
        return sb.toString();
    }

    // AOSP TextUtils.split(String,String) (frameworks/base core/java/android/
    // text/TextUtils.java:464-470): "This method yields the same result as
    // text.split(expression, -1) except that if text.isEmpty() then this
    // method returns an empty array whereas "".split(expression, -1) would
    // have returned an array with a single "". The -1 means that trailing
    // empty Strings are not removed from the result; for example
    // split("a,", ",") returns {"a", ""}." The previous impl used the
    // default-limit text.split(regex) (limit 0, which DROPS trailing empty
    // strings -- split("a,", ",") returned {"a"}, one element short) and
    // never special-cased the empty string (returned {""} instead of {}).
    // AOSP declares "@throws NullPointerException if expression or text is
    // null" (TextUtils.java:410-416) and the body is just `text.length()`
    // followed by the split, so a null text is an NPE at the caller rather
    // than a silent empty array. The previous null guard hid that.
    public static String[] split(String text, String regex) {
        if (text.length() == 0) {
            return EMPTY_STRING_ARRAY;
        } else {
            return text.split(regex, -1);
        }
    }

    // AOSP TextUtils.concat (TextUtils.java:1700-1733). Three contract points the
    // previous always-stringify version broke, all of them probed:
    //   * exactly ONE argument is returned AS ITSELF, "even if it is null" -- so
    //     concat(x) == x by identity, and concat((CharSequence) null) is null,
    //     not the 4-char string "null".
    //   * if ANY argument is Spanned the result is a SpannedString built through
    //     a SpannableStringBuilder, so the spans survive. Stringifying dropped
    //     every span, which is how a styled label lost its formatting the moment
    //     anything concatenated it.
    //   * only in the two-or-more, no-Spanned case does a null argument become
    //     the string "null" (StringBuilder.append's own contract).
    public static CharSequence concat(CharSequence... text) {
        if (text.length == 0) {
            return "";
        }

        if (text.length == 1) {
            return text[0];
        }

        boolean spanned = false;
        for (CharSequence piece : text) {
            if (piece instanceof Spanned) {
                spanned = true;
                break;
            }
        }

        if (spanned) {
            final SpannableStringBuilder ssb = new SpannableStringBuilder();
            for (CharSequence piece : text) {
                // AOSP: "If a piece is null, we append the string "null" for
                // compatibility with the behavior of StringBuilder"; append(null)
                // on an Editable would otherwise be a no-op or an NPE.
                ssb.append(piece == null ? "null" : piece);
            }
            return new SpannedString(ssb);
        } else {
            final StringBuilder sb = new StringBuilder();
            for (CharSequence piece : text) {
                sb.append(piece);
            }
            return sb.toString();
        }
    }

    // --- v1.0.425 Tier-2: methods reached constantly by the layout / cursor /
    // resource-template paths (AOSP TextUtils). ---

    /** AOSP stringOrSpannedString: returns the text as-is if it's already a
     *  String / SpannedString, a SpannedString copy if it carries spans, else a
     *  plain String. Used in the setText path; getting it wrong silently drops
     *  spans. We approximate: keep Spanned instances (preserves spans), stringify
     *  everything else. */
    public static CharSequence stringOrSpannedString(CharSequence source) {
        if (source == null) return null;
        if (source instanceof SpannedString) return source;
        if (source instanceof Spanned) return new SpannedString(source);
        return source.toString();
    }

    /** AOSP getOffsetBefore/After: step one full code point (handling surrogate
     *  pairs) back/forward from an offset. Cursor/selection movement calls these. */
    public static int getOffsetBefore(CharSequence text, int offset) {
        if (text == null || offset <= 0) return 0;
        char c = text.charAt(offset - 1);
        if (Character.isLowSurrogate(c) && offset >= 2
                && Character.isHighSurrogate(text.charAt(offset - 2))) {
            return offset - 2;
        }
        return offset - 1;
    }
    public static int getOffsetAfter(CharSequence text, int offset) {
        if (text == null) return 0;
        int len = text.length();
        if (offset >= len) return len;
        char c = text.charAt(offset);
        if (Character.isHighSurrogate(c) && offset + 1 < len
                && Character.isLowSurrogate(text.charAt(offset + 1))) {
            return offset + 2;
        }
        return offset + 1;
    }

    /** AOSP expandTemplate (TextUtils.java:1032-1075): substitute ^1..^9 in
     *  `template` with the given values (^^ is a literal caret). Used for
     *  resource string templating.
     *
     *  The previous implementation diverged from AOSP in two ways that the
     *  answer-probe caught on all ten of its template cases:
     *
     *  1. RESULT TYPE. AOSP works in a SpannableStringBuilder and RETURNS it,
     *     so the template's own spans (and the spans of every substituted
     *     value) survive into the result. Building a plain StringBuilder made
     *     every expandTemplate result unstyled, which silently strips the
     *     formatting from any styled string resource run through a template.
     *
     *  2. OUT-OF-RANGE IS AN ERROR, NOT AN EMPTY STRING. AOSP throws
     *     IllegalArgumentException when the template asks for ^0 or for a
     *     value beyond the ones supplied; we quietly substituted nothing, so a
     *     mismatched template/args pair produced a subtly wrong string instead
     *     of a loud failure at the call site.
     *
     *  The odd-looking `catch (IndexOutOfBoundsException ignore)` is AOSP's own
     *  and is how a trailing bare '^' terminates: charAt(i+1) runs off the end. */
    public static CharSequence expandTemplate(CharSequence template, CharSequence... values) {
        if (values.length > 9) {
            throw new IllegalArgumentException("max of 9 values are supported");
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(template);

        try {
            int i = 0;
            while (i < ssb.length()) {
                if (ssb.charAt(i) == '^') {
                    char next = ssb.charAt(i + 1);
                    if (next == '^') {
                        ssb.delete(i + 1, i + 2);
                        ++i;
                        continue;
                    } else if (Character.isDigit(next)) {
                        int which = Character.getNumericValue(next) - 1;
                        if (which < 0) {
                            throw new IllegalArgumentException(
                                "template requests value ^" + (which + 1));
                        }
                        if (which >= values.length) {
                            throw new IllegalArgumentException(
                                "template requests value ^" + (which + 1)
                                + "; only " + values.length + " provided");
                        }
                        ssb.replace(i, i + 2, values[which]);
                        i += values[which].length();
                        continue;
                    }
                }
                ++i;
            }
        } catch (IndexOutOfBoundsException ignore) {
            // happens when ^ is the last character in the string.
        }
        return ssb;
    }

    /** AOSP ellipsize: shorten `text` to fit `avail` pixels using `p`, inserting
     *  an ellipsis at `where`. Mac.apk measures via Paint; the visual ellipsis
     *  position matters for maxLines/ellipsize TextViews.
     *
     *  AOSP's real implementation (frameworks/base core/java/android/text/
     *  TextUtils.java:1367-1456) always ends up building
     *  `[0,left) + ellipsis + [right,len)`, and when the source is Spanned it
     *  splices through a SpannableStringBuilder instead of a plain String so
     *  the surviving prefix/suffix keep their spans:
     *    "SpannableStringBuilder ssb = new SpannableStringBuilder();
     *     ssb.append(text, 0, left); ssb.append(ellipsis); ssb.append(text,
     *     right, len); return ssb;"
     *  The previous impl always stringified `text` up front, so ellipsizing
     *  any styled string (a highlighted search match, Html.fromHtml output,
     *  a Compose AnnotatedString interop value) silently dropped every span
     *  on the surviving text. */
    public static CharSequence ellipsize(CharSequence text, android.graphics.Paint p,
                                         float avail, TruncateAt where) {
        return ellipsize(text, p, avail, where, false, null);
    }

    /** AOSP's `EllipsizeCallback` -- notified of the removed range. */
    public interface EllipsizeCallback {
        void ellipsized(int start, int end);
    }

    /** ZERO WIDTH NO-BREAK SPACE. AOSP's ELLIPSIS_FILLER: what `preserveLength`
     *  pads the removed run with so the result keeps the source's length
     *  (TextUtils.java:89). */
    private static final char ELLIPSIS_FILLER = '﻿';

    /**
     * AOSP TextUtils.java:1320-1410. Three behaviours here were wrong and each
     * is pinned by a golden row:
     *
     * 1. **`avail <= 0` returned the WHOLE TEXT.** AOSP subtracts the ellipsis
     *    width first and then takes the `avail < 0` "it all goes" branch, which
     *    leaves `remaining == 0` and returns the EMPTY STRING. Five probe rows
     *    (`ellipsize.{start,middle,end,marquee}.w0` and `negativeWidth`) read
     *    the full 43-character sentence where the reference reads "".
     * 2. **MARQUEE was bundled with END.** AOSP has explicit branches for START
     *    and for END/END_SMALL, and everything else -- including MARQUEE --
     *    falls into the `else`, which is the MIDDLE algorithm. `marquee.w120`
     *    is "The q…y dog" on a device and was "The quick b…" here.
     * 3. **`ellW > avail` returned the ellipsis alone.** AOSP returns "".
     */
    public static CharSequence ellipsize(CharSequence text, android.graphics.Paint p,
                                         float avail, TruncateAt where,
                                         boolean preserveLength, EllipsizeCallback callback) {
        if (text == null) return null;
        if (p == null) return text;
        String s = text.toString();
        final int len = s.length();
        if (p.measureText(s) <= avail) {
            if (callback != null) callback.ellipsized(0, 0);
            return text;
        }
        final String ell = "…";   // ELLIPSIS_NORMAL; END_SMALL would be "‥"
        float budget = avail - p.measureText(ell);
        int left, right;
        if (budget < 0) {
            // "it all goes" -- AOSP leaves left = 0, right = len.
            left = 0;
            right = len;
        } else if (where == TruncateAt.START) {
            left = 0;
            right = len - fitFromEnd(p, s, budget);
        } else if (where == TruncateAt.END || where == TruncateAt.END_SMALL) {
            left = fitFromStart(p, s, budget);
            right = len;
        } else {   // MIDDLE *and* MARQUEE -- AOSP's `else`
            int tail = fitFromEnd(p, s, budget / 2f);
            right = len - tail;
            float rest = budget - p.measureText(s.substring(right));
            left = fitFromStart(p, s, rest);
        }
        if (callback != null) callback.ellipsized(left, right);

        final int removed = right - left;
        final int remaining = len - removed;

        if (preserveLength) {
            char[] buf = s.toCharArray();
            int at = left;
            if (remaining > 0 && removed >= ell.length()) {
                ell.getChars(0, ell.length(), buf, at);
                at += ell.length();
            }   // else skip the ellipsis
            for (int i = at; i < right; i++) buf[i] = ELLIPSIS_FILLER;
            String out = new String(buf, 0, len);
            if (!(text instanceof Spanned)) return out;
            SpannableString ss = new SpannableString(out);
            copySpansFrom((Spanned) text, 0, len, Object.class, ss, 0);
            return ss;
        }

        if (remaining == 0) return "";

        if (text instanceof Spanned) {
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(text, 0, left);
            ssb.append(ell);
            ssb.append(text, right, len);
            return ssb;
        }
        return s.substring(0, left) + ell + s.substring(right);
    }
    private static int fitFromStart(android.graphics.Paint p, String s, float budget) {
        int n = 0;
        while (n < s.length() && p.measureText(s.substring(0, n + 1)) <= budget) n++;
        return n;
    }
    private static int fitFromEnd(android.graphics.Paint p, String s, float budget) {
        int n = 0;
        while (n < s.length() && p.measureText(s.substring(s.length() - n - 1)) <= budget) n++;
        return n;
    }

    // --- v1.0.46x Tier-2 completeness (AOSP TextUtils). Members the audit
    // flagged as commonly-reached but previously missing. ---

    /** AOSP getCapsMode mode bits, used to compute auto-capitalization. */
    public static final int CAP_MODE_CHARACTERS = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
    public static final int CAP_MODE_WORDS      = InputType.TYPE_TEXT_FLAG_CAP_WORDS;
    public static final int CAP_MODE_SENTENCES  = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;

    /** AOSP htmlEncode (TextUtils.java:1652-1691): escape the five XML/HTML
     *  metacharacters. AOSP has no null guard -- the body opens with
     *  `s.length()` -- so htmlEncode(null) is an NPE, not null. Returning null
     *  instead pushed the failure one call further out, to whoever appended
     *  the result. */
    public static String htmlEncode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '&':  sb.append("&amp;"); break;
                case '\'': sb.append("&#39;"); break;
                case '"':  sb.append("&quot;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /** AOSP commaEllipsize (frameworks/base core/java/android/text/TextUtils.
     *  java:1568-1637): "a, b, c" -> "a, b +N more" to fit `avail`. AOSP's own
     *  algorithm walks a per-character running width array from a
     *  MeasuredParagraph, which this tree does not have; we keep the
     *  candidate-string-refitting approach already here, but AOSP is explicit
     *  that `oneMore` and `more` are NOT symmetric: "format = " " + oneMore;"
     *  (the singular string is used VERBATIM, never formatted) vs
     *  "format = " " + String.format(more, remaining);" (the plural string IS
     *  a %d format). The previous impl ran BOTH through one helper that
     *  appended the raw count whenever the string lacked "%d" -- so a typical
     *  oneMore resource ("1 more", no placeholder) rendered as "1 more 1". */
    public static CharSequence commaEllipsize(CharSequence text, android.graphics.Paint p,
                                              float avail, String oneMore, String more) {
        // v1.0.937: rewritten to AOSP's own loop (TextUtils.java:1521-1590)
        // rather than a candidate-refitting approximation. Three observable
        // differences, all pinned by golden rows:
        //
        //  1. **The kept prefix INCLUDES the comma.** AOSP sets `ok = i + 1`
        //     where `i` is the index OF THE COMMA, then inserts `text[0, ok)`
        //     -- so the result is "alpha, beta," + " N more". Splitting on the
        //     comma and rejoining dropped it: "alpha, beta N more".
        //  2. **`avail <= 0` is not an early return.** AOSP only returns the
        //     text when it FITS; at avail 0 it falls through with `ok = 0` and
        //     returns an EMPTY SpannableStringBuilder.
        //  3. **The return type is a SpannableStringBuilder**, always, which
        //     the probe compares by class name.
        if (text == null) return "";
        if (p == null) return text;
        final int len = text.length();
        String s = text.toString();
        if (p.measureText(s) <= avail) return text;

        int commaCount = 0;
        for (int i = 0; i < len; i++) if (s.charAt(i) == ',') commaCount++;
        int remaining = commaCount + 1;

        int ok = 0;
        String okFormat = "";
        // ⚠ `int w` accumulating FLOAT widths is AOSP's own declaration, so
        // each addition truncates. Mirrored deliberately: it is the reference
        // behaviour, and a float accumulator would drift from it.
        int w = 0;
        for (int i = 0; i < len; i++) {
            w += p.measureText(s, i, i + 1);
            if (s.charAt(i) == ',') {
                String format;
                if (--remaining == 1) format = " " + oneMore;
                else                  format = " " + String.format(more, remaining);
                float moreWid = p.measureText(format);
                if (w + moreWid <= avail) {
                    ok = i + 1;
                    okFormat = format;
                }
            }
        }
        SpannableStringBuilder out = new SpannableStringBuilder(okFormat);
        out.insert(0, text, 0, ok);
        return out;
    }
    /** AOSP: `remaining == 1` uses `oneMore` verbatim (no substitution);
     *  otherwise `more` is a java.util.Formatter template applied via
     *  String.format(more, remaining) (typically "%d more"). */
    private static String moreSuffix(String oneMore, String more, int remaining) {
        if (remaining == 1) return String.valueOf(oneMore);
        if (more == null) return "+" + remaining;
        try {
            return String.format(more, remaining);
        } catch (java.util.IllegalFormatException e) {
            // Defensive fallback for a malformed template; AOSP would throw.
            return more.contains("%d") ? more.replace("%d", Integer.toString(remaining)) : more;
        }
    }

    /** AOSP copySpansFrom: copy every span in [start,end) from `source` into
     *  `dest` (offset by `destoff`), restricted to `kind` if non-null. */
    public static void copySpansFrom(Spanned source, int start, int end,
                                     Class<?> kind, Spannable dest, int destoff) {
        if (source == null || dest == null) return;
        if (kind == null) kind = Object.class;
        Object[] spans = source.getSpans(start, end, kind);
        for (Object span : spans) {
            int st = source.getSpanStart(span);
            int en = source.getSpanEnd(span);
            int fl = source.getSpanFlags(span);
            if (st < start) st = start;
            if (en > end)   en = end;
            dest.setSpan(span, st - start + destoff, en - start + destoff, fl);
        }
    }

    /** AOSP getCapsMode: which capitalization the given offset wants under
     *  `reqModes`. We implement the common WORDS / SENTENCES / CHARACTERS rules. */
    public static int getCapsMode(CharSequence cs, int off, int reqModes) {
        if (cs == null) return 0;
        if (off < 0) off = 0;
        int caps = 0;
        if ((reqModes & CAP_MODE_CHARACTERS) != 0) caps |= CAP_MODE_CHARACTERS;
        // Find the previous non-space; if at start or after sentence end, cap.
        int i = off;
        while (i > 0 && (cs.charAt(i - 1) == ' ' || cs.charAt(i - 1) == '\t')) i--;
        boolean atWordStart = (i == 0) || cs.charAt(i - 1) == ' ' || i != off;
        if ((reqModes & CAP_MODE_WORDS) != 0 && (i == 0 || off != i || off == 0
                || cs.charAt(off - 1) == ' ')) {
            caps |= CAP_MODE_WORDS;
        }
        if ((reqModes & CAP_MODE_SENTENCES) != 0) {
            int j = i;
            while (j > 0) {
                char c = cs.charAt(j - 1);
                if (c == '.' || c == '!' || c == '?') { caps |= CAP_MODE_SENTENCES; break; }
                if (c != ' ' && c != '\t') break;
                j--;
            }
            if (i == 0) caps |= CAP_MODE_SENTENCES;
        }
        return caps;
    }

    /** AOSP indexOf / lastIndexOf over CharSequence (avoids stringifying). */
    public static int indexOf(CharSequence s, char ch) { return indexOf(s, ch, 0); }
    public static int indexOf(CharSequence s, char ch, int start) {
        if (s == null) return -1;
        for (int i = Math.max(0, start); i < s.length(); i++) {
            if (s.charAt(i) == ch) return i;
        }
        return -1;
    }
    public static int indexOf(CharSequence s, char ch, int start, int end) {
        if (s == null) return -1;
        for (int i = Math.max(0, start); i < Math.min(end, s.length()); i++) {
            if (s.charAt(i) == ch) return i;
        }
        return -1;
    }
    public static int lastIndexOf(CharSequence s, char ch) {
        return lastIndexOf(s, ch, s == null ? -1 : s.length() - 1);
    }
    public static int lastIndexOf(CharSequence s, char ch, int last) {
        if (s == null) return -1;
        for (int i = Math.min(last, s.length() - 1); i >= 0; i--) {
            if (s.charAt(i) == ch) return i;
        }
        return -1;
    }
    public static int indexOf(CharSequence s, CharSequence needle) {
        if (s == null || needle == null) return -1;
        return s.toString().indexOf(needle.toString());
    }

    /** AOSP substring (TextUtils.java:337-352): copy a sub-range into a fresh
     *  String. The String / StringBuilder / StringBuffer fast paths are not
     *  just an optimisation -- they own the ARGUMENT VALIDATION. AOSP delegates
     *  to String.substring, so a reversed range (start > end) is a
     *  StringIndexOutOfBoundsException naming the bad range; allocating
     *  `new char[end - start]` first instead made it a NegativeArraySizeException
     *  that says nothing about which argument was wrong. */
    public static String substring(CharSequence source, int start, int end) {
        if (source instanceof String)
            return ((String) source).substring(start, end);
        if (source instanceof StringBuilder)
            return ((StringBuilder) source).substring(start, end);
        if (source instanceof StringBuffer)
            return ((StringBuffer) source).substring(start, end);

        char[] temp = new char[end - start];
        getChars(source, start, end, temp, 0);
        return new String(temp, 0, end - start);
    }

    /** AOSP getReverse: reversed view of a sub-range (used by some movement). */
    public static CharSequence getReverse(CharSequence source, int start, int end) {
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = end - 1; i >= start; i--) sb.append(source.charAt(i));
        return sb.toString();
    }

    /** AOSP regionMatches (TextUtils.java:305-330). NOT String.regionMatches:
     *  the two differ on out-of-range arguments, which is the whole observable
     *  difference. String.regionMatches is documented to RETURN FALSE when a
     *  region runs past either end; AOSP instead copies both regions through
     *  getChars into a scratch buffer, so an over-long region throws
     *  (StringIndexOutOfBoundsException for a String argument) rather than
     *  reporting a clean "no match". Delegating to String.regionMatches turned a
     *  caller's index bug into a silent false. */
    public static boolean regionMatches(CharSequence one, int toffset,
                                        CharSequence two, int ooffset, int len) {
        int tempLen = 2 * len;
        if (tempLen < len) {
            // Integer overflow; len is unreasonably large
            throw new IndexOutOfBoundsException();
        }
        // AOSP pulls this from a recycled static scratch buffer (obtain/recycle)
        // that may be LONGER than requested. Sizing it exactly is the tighter
        // contract and matches for every in-range call.
        char[] temp = new char[tempLen];

        getChars(one, toffset, toffset + len, temp, 0);
        getChars(two, ooffset, ooffset + len, temp, len);

        boolean match = true;
        for (int i = 0; i < len; i++) {
            if (temp[i] != temp[i + len]) {
                match = false;
                break;
            }
        }

        return match;
    }

    // AOSP TextUtils.StringSplitter -- an allocation-free tokenizer interface
    // (core/java/android/text/TextUtils.java). PreferenceManager and some
    // settings code obtain a SimpleStringSplitter and iterate it.
    public interface StringSplitter extends Iterable<String> {
        void setString(String string);
    }

    // AOSP TextUtils.SimpleStringSplitter -- single-char delimiter splitter
    // that reuses one iterator instance. Faithful to AOSP's observable
    // behavior: consecutive delimiters yield empty tokens, a trailing
    // delimiter does NOT yield a trailing empty token, an empty string yields
    // one empty token.
    public static class SimpleStringSplitter
            implements StringSplitter, java.util.Iterator<String> {
        private String mString;
        private final char mDelimiter;
        private int mPosition;
        private int mLength;

        public SimpleStringSplitter(char delimiter) {
            mDelimiter = delimiter;
        }

        @Override
        public void setString(String string) {
            mString = string;
            mPosition = 0;
            mLength = (mString != null) ? mString.length() : 0;
        }

        @Override
        public java.util.Iterator<String> iterator() {
            mPosition = 0;
            return this;
        }

        @Override
        public boolean hasNext() {
            return mPosition < mLength;
        }

        @Override
        public String next() {
            int end = mString.indexOf(mDelimiter, mPosition);
            if (end == -1) {
                end = mLength;
            }
            String result = mString.substring(mPosition, end);
            mPosition = end + 1; // skip the delimiter
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    // AOSP TextUtils.dumpSpans -- debug helper that prints each span in a
    // CharSequence via a Printer. We mirror the observable output shape; many
    // apps call it from debug code paths.
    public static void dumpSpans(CharSequence cs, android.util.Printer printer, String prefix) {
        if (cs instanceof Spanned) {
            Spanned sp = (Spanned) cs;
            Object[] spans = sp.getSpans(0, cs.length(), Object.class);
            for (Object span : spans) {
                printer.println(prefix + cs.subSequence(sp.getSpanStart(span),
                        sp.getSpanEnd(span)) + ": "
                        + Integer.toHexString(System.identityHashCode(span))
                        + " " + span.getClass().getCanonicalName()
                        + " (" + sp.getSpanStart(span) + "-" + sp.getSpanEnd(span)
                        + ") fl=#" + sp.getSpanFlags(span));
            }
        } else {
            printer.println(prefix + cs + ": (no spans)");
        }
    }

    // AOSP TextUtils.listEllipsize -- join a list of items with a separator,
    // ellipsizing the list (not the elements) to fit avail width, appending a
    // localized "and N more". We provide a faithful subset: when everything
    // fits, the full joined string; otherwise as many leading elements as fit
    // plus the more-template. moreId/locale params are accepted for ABI parity.
    public static CharSequence listEllipsize(android.content.Context context,
            java.util.List<CharSequence> elements, String separator,
            android.text.TextPaint paint, float avail, int moreId) {
        if (elements == null || elements.isEmpty()) return "";
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) full.append(separator);
            full.append(elements.get(i));
        }
        if (paint == null || paint.measureText(full.toString()) <= avail) {
            return full.toString();
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (CharSequence e : elements) {
            String candidate = (shown == 0) ? e.toString() : out + separator + e;
            if (paint.measureText(candidate) > avail && shown > 0) break;
            if (shown > 0) out.append(separator);
            out.append(e);
            shown++;
        }
        int more = elements.size() - shown;
        if (more > 0) {
            out.append(separator).append("+").append(more);
        }
        return out.toString();
    }

    // AOSP TextUtils.writeToParcel(CharSequence,...) + CHAR_SEQUENCE_CREATOR
    // (TextUtils.java:747-799 and 801-...). Mac.apk has no cross-process
    // boundary, but these are the marshaller every AOSP value class with a
    // CharSequence field uses (ClipDescription.label, CorrectionInfo,
    // AccessibilityWindowInfo.mTitle in this tree), so the format has to be
    // AOSP's or a styled label loses its styling on any round trip.
    //
    // The wire format:
    //     int kind          0 = Spanned, 1 = everything else
    //     String text       null only in the kind==1 case
    //     [kind==0 only] repeated { int spanTypeId, payload..., int start,
    //                               int end, int flags } terminated by int 0
    //
    // The previous implementation wrote only the raw string, so (a) the leading
    // kind int was missing entirely, (b) every span was dropped, and (c) a null
    // CharSequence came back as "" instead of null, which is a different value
    // to every caller that null-checks the result.
    public static void writeToParcel(CharSequence cs, android.os.Parcel p,
                                     int parcelableFlags) {
        if (cs instanceof Spanned) {
            p.writeInt(0);
            p.writeString(cs.toString());

            Spanned sp = (Spanned) cs;
            Object[] os = sp.getSpans(0, cs.length(), Object.class);

            for (int i = 0; i < os.length; i++) {
                Object o = os[i];
                Object prop = os[i];

                // A CharacterStyle may be a wrapper around the real span
                // (CharacterStyle.wrap); flatten the UNDERLYING one, but record
                // the offsets of the wrapper `o` that is actually attached.
                if (prop instanceof android.text.style.CharacterStyle) {
                    prop = ((android.text.style.CharacterStyle) prop).getUnderlying();
                }

                int spanTypeId = marshalableSpanTypeId(prop);
                // AOSP skips any span it cannot flatten (there, anything that is
                // not a ParcelableSpan). Skipping is safe precisely because the
                // record is self-delimiting: nothing is written for it at all.
                if (spanTypeId == 0) continue;

                p.writeInt(spanTypeId);
                writeSpanPayload(p, spanTypeId, prop);
                writeWhere(p, sp, o);
            }

            p.writeInt(0);
        } else {
            p.writeInt(1);
            p.writeString(cs != null ? cs.toString() : null);
        }
    }

    private static void writeWhere(android.os.Parcel p, Spanned sp, Object o) {
        p.writeInt(sp.getSpanStart(o));
        p.writeInt(sp.getSpanEnd(o));
        p.writeInt(sp.getSpanFlags(o));
    }

    private static void readSpan(android.os.Parcel p, Spannable sp, Object o) {
        // AOSP's own one-liner. The three reads MUST happen in start/end/flags
        // order; Java guarantees left-to-right argument evaluation (JLS 15.7.4),
        // which is the only reason this is safe to write this way.
        sp.setSpan(o, p.readInt(), p.readInt(), p.readInt());
    }

    // AOSP dispatches the flatten/unflatten through the ParcelableSpan
    // interface: each span reports its own getSpanTypeIdInternal() and writes
    // its own payload. We key off the CONCRETE CLASS instead, from one table
    // that drives BOTH directions, for a reason specific to this tree: several
    // android.text.style spans here already implement ParcelableSpan with the
    // WRONG AOSP id (SuperscriptSpan reports 17, which is TEXT_APPEARANCE_SPAN;
    // SubscriptSpan reports 16, which is ABSOLUTE_SIZE_SPAN; TextAppearanceSpan
    // reports 5, which is STRIKETHROUGH_SPAN). Trusting those ids would make
    // the reader decode a payload of the wrong shape and desync the rest of the
    // stream, which is far worse than dropping a span. A single table cannot
    // disagree with itself.
    //
    // REMOVAL CONDITION: once android.text.style.* declare AOSP-correct ids and
    // the colour/style/size spans implement ParcelableSpan, this collapses back
    // to AOSP's `prop instanceof ParcelableSpan` dispatch. The payload bytes
    // written here are already AOSP's for every listed type, so that switch can
    // be made without a format change.
    private static int marshalableSpanTypeId(Object prop) {
        // Most specific first, as AOSP's own note in writeToParcel requires.
        if (prop instanceof android.text.style.ForegroundColorSpan) return FOREGROUND_COLOR_SPAN;
        if (prop instanceof android.text.style.BackgroundColorSpan) return BACKGROUND_COLOR_SPAN;
        if (prop instanceof android.text.style.StyleSpan)           return STYLE_SPAN;
        if (prop instanceof android.text.style.RelativeSizeSpan)    return RELATIVE_SIZE_SPAN;
        if (prop instanceof android.text.style.ScaleXSpan)          return SCALE_X_SPAN;
        if (prop instanceof android.text.style.AbsoluteSizeSpan)    return ABSOLUTE_SIZE_SPAN;
        if (prop instanceof android.text.style.URLSpan)             return URL_SPAN;
        if (prop instanceof android.text.style.StrikethroughSpan)   return STRIKETHROUGH_SPAN;
        if (prop instanceof android.text.style.UnderlineSpan)       return UNDERLINE_SPAN;
        if (prop instanceof android.text.style.LineHeightSpan.Standard) return LINE_HEIGHT_SPAN;
        return 0;
    }

    /** Payload bytes for one span, matching that span's AOSP
     *  writeToParcelInternal exactly so the format stays interchangeable. */
    private static void writeSpanPayload(android.os.Parcel p, int spanTypeId, Object prop) {
        switch (spanTypeId) {
            case FOREGROUND_COLOR_SPAN:
                p.writeInt(((android.text.style.ForegroundColorSpan) prop).getForegroundColor());
                break;
            case BACKGROUND_COLOR_SPAN:
                p.writeInt(((android.text.style.BackgroundColorSpan) prop).getBackgroundColor());
                break;
            case STYLE_SPAN:
                // AOSP StyleSpan.writeToParcelInternal writes TWO ints: the
                // style and the font-weight adjustment (StyleSpan.java:113-116).
                // The second one arrived with StyleSpan(int,int) in API 31; we
                // used to write only the first because the field did not exist
                // here. Both ends move together -- see readSpanPayload below.
                p.writeInt(((android.text.style.StyleSpan) prop).getStyle());
                p.writeInt(((android.text.style.StyleSpan) prop).getFontWeightAdjustment());
                break;
            case RELATIVE_SIZE_SPAN:
                p.writeFloat(((android.text.style.RelativeSizeSpan) prop).getSizeChange());
                break;
            case SCALE_X_SPAN:
                p.writeFloat(((android.text.style.ScaleXSpan) prop).getScaleX());
                break;
            case ABSOLUTE_SIZE_SPAN:
                p.writeInt(((android.text.style.AbsoluteSizeSpan) prop).getSize());
                p.writeInt(((android.text.style.AbsoluteSizeSpan) prop).getDip() ? 1 : 0);
                break;
            case URL_SPAN:
                p.writeString(((android.text.style.URLSpan) prop).getURL());
                break;
            case LINE_HEIGHT_SPAN:
                // AOSP LineHeightSpan.Standard#writeToParcelInternal: a single
                // int, the physical-pixel height.
                p.writeInt(((android.text.style.LineHeightSpan.Standard) prop).getHeight());
                break;
            default:
                // STRIKETHROUGH_SPAN and UNDERLINE_SPAN carry no state, exactly
                // as their AOSP writeToParcelInternal bodies are empty.
                break;
        }
    }

    /** Inverse of writeSpanPayload. Returns null for a tag this reader does not
     *  know, which the caller must treat as "stop": an unknown tag's payload
     *  length is unknown, so the read cursor cannot be advanced past it. */
    private static Object readSpanPayload(android.os.Parcel p, int spanTypeId) {
        switch (spanTypeId) {
            case FOREGROUND_COLOR_SPAN:
                return new android.text.style.ForegroundColorSpan(p.readInt());
            case BACKGROUND_COLOR_SPAN:
                return new android.text.style.BackgroundColorSpan(p.readInt());
            case STYLE_SPAN: {
                int style = p.readInt();
                int fontWeightAdjustment = p.readInt();
                return new android.text.style.StyleSpan(style, fontWeightAdjustment);
            }
            case RELATIVE_SIZE_SPAN:
                return new android.text.style.RelativeSizeSpan(p.readFloat());
            case SCALE_X_SPAN:
                return new android.text.style.ScaleXSpan(p.readFloat());
            case ABSOLUTE_SIZE_SPAN: {
                int size = p.readInt();
                boolean dip = p.readInt() != 0;
                return new android.text.style.AbsoluteSizeSpan(size, dip);
            }
            case URL_SPAN:
                return new android.text.style.URLSpan(p.readString());
            case STRIKETHROUGH_SPAN:
                return new android.text.style.StrikethroughSpan();
            case UNDERLINE_SPAN:
                return new android.text.style.UnderlineSpan();
            case LINE_HEIGHT_SPAN:
                return new android.text.style.LineHeightSpan.Standard(p.readInt());
            default:
                return null;
        }
    }

    public static final android.os.Parcelable.Creator<CharSequence> CHAR_SEQUENCE_CREATOR =
        new android.os.Parcelable.Creator<CharSequence>() {
            @Override public CharSequence createFromParcel(android.os.Parcel p) {
                int kind = p.readInt();

                String string = p.readString();
                // AOSP returns null here, NOT "": a null CharSequence has to
                // survive the round trip as null or every caller that
                // null-checks the field reads a bogus empty label instead.
                if (string == null) {
                    return null;
                }

                if (kind == 1) {
                    return string;
                }

                SpannableString sp = new SpannableString(string);

                while (true) {
                    kind = p.readInt();

                    if (kind == 0) break;

                    Object span = readSpanPayload(p, kind);
                    if (span == null) {
                        // Written by something that speaks a tag we do not.
                        // Everything after it is unreadable; stop with the
                        // spans we did recover rather than misparse.
                        break;
                    }
                    readSpan(p, sp, span);
                }

                return sp;
            }
            @Override public CharSequence[] newArray(int size) {
                return new CharSequence[size];
            }
        };

    // --- v1.0.5xx layout-direction (AOSP TextUtils). The single
    // highest-napps method gap found in an i18n audit pass (28/37 corpus
    // apps): androidx.core.text.TextUtilsCompat.getLayoutDirectionFromLocale
    // calls straight through to this on API17+ (Material widgets,
    // ConstraintLayout start/end resolution, TextInputLayout hint gravity
    // all reach it that way), and it's the exact method AOSP's
    // View.resolveLayoutDirection() LAYOUT_DIRECTION_LOCALE branch calls
    // directly. android.view.View in this tree already has a PRIVATE
    // layoutDirectionFromLocale(Locale) duplicating this logic (its own
    // comment says so: "android.text.TextUtils has no such method in this
    // tree"); that duplicate can be collapsed to call this once View.java
    // is touched by whoever owns it, so all call sites agree. ---

    /** AOSP TextUtils.getLayoutDirectionFromLocale(Locale): the writing
     *  direction for `locale`. AOSP delegates to ICU's
     *  ULocale.forLocale(locale).isRightToLeft(); android.icu.util.ULocale
     *  has no such method in this tree, so (matching the same JDK query
     *  android.view.View's private duplicate and
     *  android.content.res.Configuration.setLayoutDirection(Locale) already
     *  use, keeping every call site's answer consistent) we answer from a
     *  language/script table (macapkIsLocaleRtl; no AWT). The AOSP body also OR's in
     *  DisplayProperties.debug_force_rtl(), a developer-settings "force
     *  RTL layout" debug toggle with no Mac.apk equivalent (there is no
     *  such settings screen here); omitted, matching the overwhelmingly
     *  common real evaluation (flag unset -> false). */
    public static int getLayoutDirectionFromLocale(java.util.Locale locale) {
        return isLocaleRightToLeft(locale)
            ? android.view.View.LAYOUT_DIRECTION_RTL
            : android.view.View.LAYOUT_DIRECTION_LTR;
    }

    // ICU's right-to-left languages and scripts (ULocale.isRightToLeft). This used
    // to ask java.awt.ComponentOrientation and patch its gaps, but loading that
    // class initialises AWT, which the converter/prep threads must never do
    // (debug/bootspeed_gate.sh OVERLAP), and Configuration's field initialiser
    // reaches it on every construction. A table needs no AWT and covers the
    // languages the JDK query answered (ar, fa, iw/he, ur) plus the ones it got
    // wrong or never knew.
    private static final java.util.Set<String> RTL_LANGUAGES =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "ar", "fa", "he", "iw", "ur", "yi", "ji", "ps", "sd", "ug", "dv", "syr", "ckb"));
    private static final java.util.Set<String> RTL_SCRIPTS =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "Arab", "Hebr", "Thaa", "Syrc", "Nkoo", "Adlm", "Rohg", "Mand", "Samr"));

    /** True when `locale` is written right to left (AOSP: ICU
     *  ULocale.forLocale(locale).isRightToLeft()). AWT-free. */
    public static boolean macapkIsLocaleRtl(java.util.Locale locale) {
        if (locale == null || locale.equals(java.util.Locale.ROOT)) return false;
        String script = locale.getScript();
        if (script != null && !script.isEmpty()) return RTL_SCRIPTS.contains(script);
        return RTL_LANGUAGES.contains(locale.getLanguage());
    }

    private static boolean isLocaleRightToLeft(java.util.Locale locale) {
        return macapkIsLocaleRtl(locale);
    }

    /** TextPaint overload (distinct descriptor from the Paint form).
     *
     *  ⚠ AOSP declares these as taking `TextPaint`, so app bytecode carries
     *  `(...Landroid/text/TextPaint;...)` in the call site's descriptor and a
     *  Paint-typed method does NOT satisfy it -- the JVM matches on the exact
     *  descriptor, and the result is a NoSuchMethodError at the call rather
     *  than a compile error anywhere. Every AOSP TextUtils entry point that
     *  takes a TextPaint needs its own overload for that reason; three probe
     *  rows were failing this way (`commaEllipsize.*`) and one more for the
     *  6-arg ellipsize. */
    public static CharSequence ellipsize(CharSequence text, android.text.TextPaint p,
            float avail, TruncateAt where) {
        return ellipsize(text, (android.graphics.Paint) p, avail, where);
    }
    public static CharSequence ellipsize(CharSequence text, android.text.TextPaint p,
            float avail, TruncateAt where, boolean preserveLength, EllipsizeCallback callback) {
        return ellipsize(text, (android.graphics.Paint) p, avail, where, preserveLength, callback);
    }
    public static CharSequence commaEllipsize(CharSequence text, android.text.TextPaint p,
            float avail, String oneMore, String more) {
        return commaEllipsize(text, (android.graphics.Paint) p, avail, oneMore, more);
    }
    /** Same null contract as the String-expression overload: AOSP reads
     *  `text.length()` unguarded (TextUtils.java:442-448). */
    public static String[] split(String text, java.util.regex.Pattern pattern) {
        if (text.length() == 0) {
            return EMPTY_STRING_ARRAY;
        } else {
            return pattern.split(text, -1);
        }
    }
    /** AOSP TextUtils.getChars (TextUtils.java:144-160): copy [start,end) of a
     *  CharSequence into dest at destoff, via an exact-class bulk-copy fast path
     *  where one exists (this is on the layout hot path, so the char-at-a-time
     *  fallback is a last resort). None of String / StringBuffer / StringBuilder
     *  implements GetChars, so ordering those three ahead of the GetChars check
     *  cannot change which branch any other type takes.
     *
     *  The hand-written bounds checks are NOT redundant with the ones inside the
     *  JDK methods being delegated to: this host's JDK and Android's libcore
     *  report an over-small DESTINATION differently, and the exception type is
     *  how a caller tells "my buffer is too small" from "my indices are wrong".
     *  Every libcore implementation ends in System.arraycopy, so Android raises
     *  ArrayIndexOutOfBoundsException. Measured on this host (JDK 21), the same
     *  call raises StringIndexOutOfBoundsException from String.getChars and a
     *  bare IndexOutOfBoundsException from StringBuilder/StringBuffer.getChars.
     *  Checking here also fixes the ORDER: libcore validates the source range
     *  first, so a call that is wrong in both ways reports the source problem. */
    public static void getChars(CharSequence s, int start, int end, char[] dest, int destoff) {
        Class<? extends CharSequence> c = s.getClass();

        if (c == String.class || c == StringBuffer.class || c == StringBuilder.class) {
            final int len = s.length();
            if (start < 0 || end > len || start > end) {
                throw new StringIndexOutOfBoundsException(
                    "start " + start + ", end " + end + ", length " + len);
            }
            final int count = end - start;
            if (count > 0 && (destoff < 0 || destoff > dest.length - count)) {
                throw new ArrayIndexOutOfBoundsException(
                    "dest.length=" + dest.length + " destoff=" + destoff + " count=" + count);
            }
            if (c == String.class)
                ((String) s).getChars(start, end, dest, destoff);
            else if (c == StringBuffer.class)
                ((StringBuffer) s).getChars(start, end, dest, destoff);
            else
                ((StringBuilder) s).getChars(start, end, dest, destoff);
        }
        else if (s instanceof GetChars)
            ((GetChars) s).getChars(start, end, dest, destoff);
        else {
            for (int i = start; i < end; i++)
                dest[destoff++] = s.charAt(i);
        }
    }

    /** AOSP TextUtils.lastIndexOf(s, ch, start, last): last index of ch in
     *  [start,last]. */
    public static int lastIndexOf(CharSequence s, char ch, int start, int last) {
        if (s == null) return -1;
        if (last > s.length() - 1) last = s.length() - 1;
        for (int i = last; i >= start; i--) if (s.charAt(i) == ch) return i;
        return -1;
    }

    public static int indexOf(CharSequence s, CharSequence needle, int start) {
        return s == null ? -1 : s.toString().indexOf(needle == null ? "" : needle.toString(), start);
    }
    public static int indexOf(CharSequence s, CharSequence needle, int start, int end) {
        int r = indexOf(s, needle, start); return (r >= 0 && r <= end) ? r : -1;
    }
    /** AOSP isGraphic(CharSequence) (TextUtils.java:1738-1755): does the
     *  sequence contain any printable character? Two fixes over the previous
     *  version, both probed:
     *    * iterate by CODE POINT. Walking chars sees an emoji as two SURROGATE
     *      chars and answers false, when the code point is OTHER_SYMBOL and the
     *      honest answer is true. That is the single most likely input for an
     *      "is this label worth showing" check to get wrong.
     *    * exclude UNASSIGNED. A noncharacter/unassigned code point is not
     *      printable, and omitting it from the exclusion list made isGraphic
     *      answer true for a string that renders as nothing. */
    public static boolean isGraphic(CharSequence str) {
        final int len = str.length();
        for (int cp, i = 0; i < len; i += Character.charCount(cp)) {
            cp = Character.codePointAt(str, i);
            int gc = Character.getType(cp);
            if (gc != Character.CONTROL
                    && gc != Character.FORMAT
                    && gc != Character.SURROGATE
                    && gc != Character.UNASSIGNED
                    && gc != Character.LINE_SEPARATOR
                    && gc != Character.PARAGRAPH_SEPARATOR
                    && gc != Character.SPACE_SEPARATOR) {
                return true;
            }
        }
        return false;
    }

    /** AOSP isGraphic(char) (TextUtils.java:1764-1773). Deprecated in AOSP
     *  ("does not support non-BMP characters and should not be used") but it is
     *  public API and a distinct descriptor, so code compiled against it hits
     *  NoSuchMethodError without it -- which is exactly what the probe saw. */
    @Deprecated
    public static boolean isGraphic(char c) {
        int gc = Character.getType(c);
        return     gc != Character.CONTROL
                && gc != Character.FORMAT
                && gc != Character.SURROGATE
                && gc != Character.UNASSIGNED
                && gc != Character.LINE_SEPARATOR
                && gc != Character.PARAGRAPH_SEPARATOR
                && gc != Character.SPACE_SEPARATOR;
    }

    /** AOSP replace (TextUtils.java:986-1010): return a new CharSequence in
     *  which each of `sources` is replaced by the corresponding element of
     *  `destinations`.
     *
     *  The two-pass shape is AOSP's and is load-bearing: pass one marks every
     *  match with a span whose KEY IS THE SOURCE STRING ITSELF, pass two reads
     *  each mark back and splices. Doing it in one pass would break as soon as
     *  a replacement changed the length of the text, because every later
     *  match's index would be stale; the span rides the edit instead. */
    public static CharSequence replace(CharSequence template,
                                       String[] sources,
                                       CharSequence[] destinations) {
        SpannableStringBuilder tb = new SpannableStringBuilder(template);

        for (int i = 0; i < sources.length; i++) {
            int where = indexOf(tb, sources[i]);

            if (where >= 0)
                tb.setSpan(sources[i], where, where + sources[i].length(),
                           Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        for (int i = 0; i < sources.length; i++) {
            int start = tb.getSpanStart(sources[i]);
            int end = tb.getSpanEnd(sources[i]);

            if (start >= 0) {
                tb.replace(start, end, destinations[i]);
            }
        }

        return tb;
    }

    // AOSP TextUtils.java:97-98.
    private static final int LINE_FEED_CODE_POINT = 10;
    private static final int NBSP_CODE_POINT = 160;

    /** AOSP isNewline (TextUtils.java:2182-2186). Note that a bare '\n' is
     *  Character.CONTROL, not LINE_SEPARATOR, hence the explicit code-point
     *  test alongside the two separator categories. */
    private static boolean isNewline(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.PARAGRAPH_SEPARATOR || type == Character.LINE_SEPARATOR
                || codePoint == LINE_FEED_CODE_POINT;
    }

    /** AOSP isWhiteSpace (TextUtils.java:2188-2190). NBSP is deliberately
     *  included here even though Character.isWhitespace excludes it. */
    private static boolean isWhiteSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || codePoint == NBSP_CODE_POINT;
    }

    /** AOSP makeSafeForPresentation (TextUtils.java:2229-2321), API 30. Clean a
     *  string that came from an untrusted source (an app's own label, a
     *  notification title) before showing it in system UI: no control
     *  characters, no unbounded length, optionally no newlines.
     *
     *  Note our SDK_INT is 29 while this method arrives in 30. It is added
     *  anyway because it is pure surface: absent, any caller gets
     *  NoSuchMethodError, and there is no API-level-dependent ANSWER here to
     *  get wrong.
     *
     *  The Html.fromHtml pass is not incidental and is the reason the results
     *  look surprising: it is what strips invalid characters and collapses
     *  whitespace, so "a\nb" is already "a b" (the newline became an HTML
     *  space) before the SINGLE_LINE / FIRST_LINE flags are ever consulted, and
     *  both flags therefore leave it alone.
     *
     *  Deviation: AOSP routes its argument checks through
     *  com.android.internal.util.Preconditions, which this tree does not have;
     *  the same checks are inlined, throwing the same types. */
    public static CharSequence makeSafeForPresentation(String unclean,
            int maxCharactersToConsider, float ellipsizeDip, int flags) {
        boolean onlyKeepFirstLine = ((flags & SAFE_STRING_FLAG_FIRST_LINE) != 0);
        boolean forceSingleLine = ((flags & SAFE_STRING_FLAG_SINGLE_LINE) != 0);
        boolean trim = ((flags & SAFE_STRING_FLAG_TRIM) != 0);

        if (unclean == null) {
            throw new NullPointerException();
        }
        if (maxCharactersToConsider < 0) {
            throw new IllegalArgumentException("maxCharactersToConsider must not be negative");
        }
        if (!(ellipsizeDip >= 0)) {   // written this way so NaN is rejected too
            throw new IllegalArgumentException("ellipsizeDip must not be negative");
        }
        if ((flags & ~(SAFE_STRING_FLAG_TRIM | SAFE_STRING_FLAG_SINGLE_LINE
                | SAFE_STRING_FLAG_FIRST_LINE)) != 0) {
            throw new IllegalArgumentException("Unknown flags");
        }
        if (onlyKeepFirstLine && forceSingleLine) {
            throw new IllegalArgumentException(
                "Cannot set SAFE_STRING_FLAG_SINGLE_LINE and SAFE_STRING_FLAG_FIRST_LINE at the"
                    + "same time");
        }

        String shortString;
        if (maxCharactersToConsider > 0) {
            shortString = unclean.substring(0, Math.min(unclean.length(), maxCharactersToConsider));
        } else {
            shortString = unclean;
        }

        // Treat string as HTML. This
        // - converts HTML symbols: e.g. &szlig; -> ss
        // - applies some HTML tags: e.g. <br> -> \n
        // - removes invalid characters such as \b
        // - removes html styling, such as <b>
        // - applies html formatting: e.g. a<p>b</p>c -> a\n\nb\n\nc
        // - replaces some html tags by "object replacement" markers: <img> -> ￼
        // - Removes leading white space
        // - Removes all trailing white space beside a single space
        // - Collapses double white space
        StringWithRemovedChars gettingCleaned = new StringWithRemovedChars(
                Html.fromHtml(shortString).toString());

        int firstNonWhiteSpace = -1;
        int firstTrailingWhiteSpace = -1;

        // Remove new lines (if requested) and control characters.
        int uncleanLength = gettingCleaned.length();
        for (int offset = 0; offset < uncleanLength; ) {
            int codePoint = gettingCleaned.codePointAt(offset);
            int type = Character.getType(codePoint);
            int codePointLen = Character.charCount(codePoint);
            boolean isNewline = isNewline(codePoint);

            if (onlyKeepFirstLine && isNewline) {
                gettingCleaned.removeAllCharAfter(offset);
                break;
            } else if (forceSingleLine && isNewline) {
                gettingCleaned.removeRange(offset, offset + codePointLen);
            } else if (type == Character.CONTROL && !isNewline) {
                gettingCleaned.removeRange(offset, offset + codePointLen);
            } else if (trim && !isWhiteSpace(codePoint)) {
                // This is only executed if the code point is not removed
                if (firstNonWhiteSpace == -1) {
                    firstNonWhiteSpace = offset;
                }
                firstTrailingWhiteSpace = offset + codePointLen;
            }

            offset += codePointLen;
        }

        if (trim) {
            // Remove leading and trailing white space
            if (firstNonWhiteSpace == -1) {
                // No non whitespace found, remove all
                gettingCleaned.removeAllCharAfter(0);
            } else {
                if (firstNonWhiteSpace > 0) {
                    gettingCleaned.removeAllCharBefore(firstNonWhiteSpace);
                }
                if (firstTrailingWhiteSpace < uncleanLength) {
                    gettingCleaned.removeAllCharAfter(firstTrailingWhiteSpace);
                }
            }
        }

        if (ellipsizeDip == 0) {
            return gettingCleaned.toString();
        } else {
            // Truncate
            final TextPaint paint = new TextPaint();
            paint.setTextSize(42);

            return TextUtils.ellipsize(gettingCleaned.toString(), paint, ellipsizeDip,
                    TextUtils.TruncateAt.END);
        }
    }

    /**
     * AOSP's private helper (TextUtils.java:2325-2402). Records removals in a
     * BitSet and applies them only at toString(), which is what lets the loop
     * above keep walking ORIGINAL offsets while deleting as it goes; compacting
     * eagerly would invalidate every offset it still holds.
     */
    private static class StringWithRemovedChars {
        /** The original string */
        private final String mOriginal;

        /**
         * One bit per char in string. If bit is set, character needs to be removed. If whole
         * bit field is not initialized nothing needs to be removed.
         */
        private BitSet mRemovedChars;

        StringWithRemovedChars(String original) {
            mOriginal = original;
        }

        /**
         * Mark all chars in a range {@code [firstRemoved - firstNonRemoved[} (not including
         * firstNonRemoved) as removed.
         */
        void removeRange(int firstRemoved, int firstNonRemoved) {
            if (mRemovedChars == null) {
                mRemovedChars = new BitSet(mOriginal.length());
            }

            mRemovedChars.set(firstRemoved, firstNonRemoved);
        }

        /**
         * Remove all characters before {@code firstNonRemoved}.
         */
        void removeAllCharBefore(int firstNonRemoved) {
            if (mRemovedChars == null) {
                mRemovedChars = new BitSet(mOriginal.length());
            }

            mRemovedChars.set(0, firstNonRemoved);
        }

        /**
         * Remove all characters after and including {@code firstRemoved}.
         */
        void removeAllCharAfter(int firstRemoved) {
            if (mRemovedChars == null) {
                mRemovedChars = new BitSet(mOriginal.length());
            }

            mRemovedChars.set(firstRemoved, mOriginal.length());
        }

        @Override
        public String toString() {
            // Common case, no chars removed
            if (mRemovedChars == null) {
                return mOriginal;
            }

            StringBuilder sb = new StringBuilder(mOriginal.length());
            for (int i = 0; i < mOriginal.length(); i++) {
                if (!mRemovedChars.get(i)) {
                    sb.append(mOriginal.charAt(i));
                }
            }

            return sb.toString();
        }

        /**
         * Return length or the original string
         */
        int length() {
            return mOriginal.length();
        }

        /**
         * Return codePoint of original string at a certain {@code offset}
         */
        int codePointAt(int offset) {
            return mOriginal.codePointAt(offset);
        }
    }
}



