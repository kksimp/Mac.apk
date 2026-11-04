package android.text;

/** Mac.apk-internal: Unicode bidi ordering for the framework's own text drawing.
 *
 *  AOSP orders a line through TextLine and minikin: the paragraph direction
 *  comes from the TextDirectionHeuristic (FIRSTSTRONG_LTR by default), the
 *  line is cut at every style run and every bidi level change, and the pieces
 *  are painted in VISUAL order, each with its own direction. This tree painted
 *  style runs left to right in LOGICAL order, so every Hebrew/Arabic line with
 *  more than one run (a link, a bold word, a fallback font) came out with its
 *  pieces reversed. java.text.Bidi is the JDK's UAX #9 implementation. */
public final class MacApkBidi {
    private MacApkBidi() {}

    /** True when [start, end) holds a character that can change visual order:
     *  a right-to-left letter or an explicit bidi control. */
    public static boolean hasRtl(CharSequence s, int start, int end) {
        if (s == null) return false;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c < 0x0590) continue;
            if (c >= 0x0590 && c <= 0x08FF) return true;          // Hebrew, Arabic, Syriac, Thaana, NKo...
            if (c >= 0xFB1D && c <= 0xFDFF) return true;          // Hebrew/Arabic presentation forms
            if (c >= 0xFE70 && c <= 0xFEFF) return true;
            if (c == 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) return true;
            if (Character.isHighSurrogate(c) && i + 1 < end) {
                byte d = Character.getDirectionality(Character.toCodePoint(c, s.charAt(i + 1)));
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            }
        }
        return false;
    }

    /** FIRSTSTRONG_LTR over the '\n'-delimited paragraph that holds `pos`:
     *  the first strong character decides, and none at all means LTR. */
    public static boolean isRtlParagraph(CharSequence s, int pos) {
        if (s == null) return false;
        final int len = s.length();
        int p = Math.max(0, Math.min(pos, len));
        while (p > 0 && s.charAt(p - 1) != '\n') p--;
        for (int i = p; i < len; ) {
            int cp = Character.codePointAt(s, i);
            if (cp == '\n') break;
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** The pieces of `line` between consecutive cut points (`bounds[0..n)`,
     *  ascending offsets into `line`), cut again at every bidi level change,
     *  in VISUAL left-to-right order, flattened as start, end, level triples.
     *  An odd level is a right-to-left piece. */
    public static int[] visualPieces(String line, int[] bounds, int n, boolean paraRtl) {
        final int len = line.length();
        if (len == 0) return new int[0];
        java.text.Bidi b = new java.text.Bidi(line, paraRtl
                ? java.text.Bidi.DIRECTION_RIGHT_TO_LEFT : java.text.Bidi.DIRECTION_LEFT_TO_RIGHT);
        java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>();
        cuts.add(0);
        cuts.add(len);
        for (int i = 0; i < n; i++) {
            if (bounds[i] > 0 && bounds[i] < len) cuts.add(bounds[i]);
        }
        for (int r = 0; r < b.getRunCount(); r++) {
            cuts.add(b.getRunStart(r));
            cuts.add(b.getRunLimit(r));
        }
        Integer[] c = cuts.toArray(new Integer[0]);
        final int pieces = c.length - 1;
        byte[] levels = new byte[pieces];
        Integer[] order = new Integer[pieces];
        for (int k = 0; k < pieces; k++) {
            levels[k] = (byte) b.getLevelAt(c[k]);
            order[k] = k;
        }
        final byte[] logical = levels.clone();
        java.text.Bidi.reorderVisually(levels, 0, order, 0, pieces);
        int[] out = new int[pieces * 3];
        for (int k = 0; k < pieces; k++) {
            int j = order[k];
            out[3 * k] = c[j];
            out[3 * k + 1] = c[j + 1];
            out[3 * k + 2] = logical[j];
        }
        return out;
    }
}
