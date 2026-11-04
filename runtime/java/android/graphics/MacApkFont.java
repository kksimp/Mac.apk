// MacApkFont -- shared AWT Font provider for the Canvas/Paint text path.
//
// Android renders all framework text with Roboto (the system default
// font, /system/fonts/Roboto-Regular.ttf). Our text path previously used
// AWT's Font.SANS_SERIF (Helvetica on macOS), whose character advances
// are ~13% WIDER than Roboto for the same string (929px vs 823px for a
// 59-char run at 32px). That made every multi-line TextView wrap to MORE
// lines than the emulator, so F-Droid's AppDetails description/changelog
// overflowed their collapse cap and ellipsized, and no body text lined up
// pixel-for-pixel. Using the real Roboto makes both the measure
// (Paint.measureText) and the draw (Canvas.deriveFont) match the
// emulator's wrapping AND glyph shapes.
//
// The TTF is the exact file the emulator ships (pulled from
// /system/fonts/Roboto-Regular.ttf), bundled into android-framework.jar
// at /fonts/Roboto-Regular.ttf (build.sh) and loaded once via
// getResourceAsStream. If the resource is missing (older jar) we fall
// back to SANS_SERIF so text still renders -- never throw on the draw
// path.
package android.graphics;

import java.awt.Font;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

public final class MacApkFont {
    private MacApkFont() {}

    private static volatile Font sBase;       // unsized base Roboto (or fallback)
    private static volatile boolean sLoaded;  // load attempted
    private static volatile boolean sIsRoboto;

    // Derived (size+style) instances, cached. Key = (size<<2)|style.
    private static final ConcurrentHashMap<Integer, Font> sDerived =
        new ConcurrentHashMap<>();

    /** True once the real Roboto loaded (vs the SANS_SERIF fallback). */
    public static boolean isRoboto() { ensureBase(); return sIsRoboto; }

    private static void ensureBase() {
        if (sLoaded) return;
        synchronized (MacApkFont.class) {
            if (sLoaded) return;
            Font base = null;
            try {
                InputStream in = MacApkFont.class.getResourceAsStream(
                    "/fonts/Roboto-Regular.ttf");
                if (in != null) {
                    try {
                        // Read the BYTES, not the stream, so the head table is
                        // available for verticalExtents -- Font.createFont
                        // consumes the stream and hands back nothing about the
                        // face's vertical metrics.
                        byte[] bytes = in.readAllBytes();
                        base = createFontFromBytes(bytes);
                        rememberExtents(base, bytes);
                        sIsRoboto = true;
                    } finally {
                        try { in.close(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
                base = null;
            }
            if (base == null) {
                base = new Font(Font.SANS_SERIF, Font.PLAIN, 1);
                sIsRoboto = false;
            }
            sBase = base;
            sLoaded = true;
        }
    }

    // ================================================================
    // Font BYTES -> java.awt.Font: the ONE sanctioned way
    // ================================================================
    //
    // Never call Font.createFont(int, InputStream) anywhere else in the
    // framework. It writes a $TMPDIR/+~JF*.tmp copy per call (JDK 21
    // Font.java:1113) whose only deleters are a GC Disposer (defeated by our
    // static font caches) and a JDK shutdown hook that NEVER runs here: -Xrs
    // keeps Java off SIGTERM, and every exit is _exit, Runtime.halt or SIGKILL.
    // That leaked 1-8 copies per launch plus one per app-font lookup (measured
    // 220 files / 207 MB in 90 minutes, 2026-09-24).
    //
    // Never call Font.createFont(int, File) on an APP's file either, even
    // though it would not copy: AOSP mmaps fonts, so an app may delete or
    // overwrite its file right after Typeface.createFromFile, while AWT re-opens
    // the file BY PATH after channel-pool eviction or a thread interrupt, much
    // later and nondeterministically. Copy into the content-addressed store
    // instead (MacApkFontStore), whose files never change under a reader.
    //
    // debug/fontcopy_gate.sh enforces both rules: zero +~JF files, and exactly
    // two createFont call sites in runtime/java, both inside this method.
    // Details and citations: docs/font-tmpfile-leak-content-store_2026-09-24.md.

    private static volatile boolean sStoreWarned;

    /** Build an unsized TRUETYPE Font from font bytes, backed by the
     *  content-addressed store. The Font2D is constructed exactly as the stream
     *  path constructs it (SunFontManager.createFont2D -> new TrueTypeFont(path,
     *  null, 0, true)), so glyphs and metrics are identical; only the file it is
     *  read from differs. */
    static Font createFontFromBytes(byte[] bytes)
            throws java.io.IOException, java.awt.FontFormatException {
        java.io.File f = null;
        // Two attempts at the store. The second exists because fileFor trusts
        // its per-process memo and does not re-check a file it already
        // verified: if a cache cleaner or a user removed that file (or the
        // whole store dir) since, createFont(File) fails here, and without a
        // retry every later load of the face would leak through the legacy
        // path for the rest of the process. forget() drops the memo, so the
        // retry re-verifies on disk and rewrites whatever is missing.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                f = MacApkFontStore.fileFor(bytes);
            } catch (Throwable storeUnusable) {
                warnStoreOnce("font store unusable (" + storeUnusable
                    + "); falling back to the InputStream overload of Font.createFont,"
                    + " which leaks one $TMPDIR/+~JF*.tmp per font");
                break;
            }
            try {
                return Font.createFont(Font.TRUETYPE_FONT, f);
            } catch (java.awt.FontFormatException | java.io.IOException storeCopyRejected) {
                // After the retry this is decided by the legacy path on the
                // SAME bytes: if that also rejects them they are most likely
                // not a font; if it accepts them, the store is at fault.
                MacApkFontStore.forget(f);
            }
        }
        // Legacy path, reached only when the store cannot serve these bytes. It
        // leaks one +~JF copy, but correct glyphs beat a clean tmp dir: every
        // caller's catch would otherwise hand back SANS_SERIF with the wrong
        // metrics. For bytes that are not a font the JDK deletes its own copy
        // (SunFontManager.createFont2D's FontFormatException arm), so that case
        // leaks nothing.
        try {
            Font legacy = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(bytes));
            if (f != null) {
                warnStoreOnce("store copy " + f.getName() + " was rejected but the same bytes"
                    + " load from a stream; using the leaking legacy path");
            }
            return legacy;
        } catch (java.awt.FontFormatException notAFont) {
            // The store file is deliberately NOT unlinked: AWT also reports
            // EMFILE / EIO as FontFormatException, and the file is shared with
            // every other live runtime (see MacApkFontStore.forget).
            throw notAFont;
        }
    }

    private static void warnStoreOnce(String msg) {
        if (sStoreWarned) return;
        sStoreWarned = true;
        try { com.macapk.loader.NativeLog.w("MacApkFont", msg); } catch (Throwable ignored) {}
    }

    /** A Roboto (or fallback) Font at the given pixel size + AWT style
     *  (Font.PLAIN / BOLD / ITALIC / BOLD|ITALIC). Cached. */
    public static Font get(float sizePx, int awtStyle) {
        ensureBase();
        int sz = (int) sizePx;
        if (sz <= 0) sz = 1;
        if (awtStyle < 0 || awtStyle > 3) awtStyle = 0;
        int key = (sz << 2) | awtStyle;
        Font f = sDerived.get(key);
        if (f == null) {
            f = sBase.deriveFont(awtStyle, (float) sz);
            inheritExtents(sBase, f);
            sDerived.put(key, f);
        }
        return f;
    }

    /** Derive a sized+styled instance from an arbitrary base Font (e.g. a
     *  custom Typeface loaded from an APK asset). When base is null we fall
     *  back to the system Roboto path so text always renders. The per-base
     *  derived instances are cached on the Font itself by AWT internally;
     *  we cache by (base identity, size, style) to avoid repeated
     *  deriveFont() allocation on the hot draw path. */
    public static Font getFrom(Font base, float sizePx, int awtStyle) {
        if (base == null) return get(sizePx, awtStyle);
        int sz = (int) sizePx;
        if (sz <= 0) sz = 1;
        if (awtStyle < 0 || awtStyle > 3) awtStyle = 0;
        long id = ((long) System.identityHashCode(base) << 24)
                | ((long) sz << 2) | awtStyle;
        Font f = sDerivedFrom.get(id);
        if (f == null) {
            f = base.deriveFont(awtStyle, (float) sz);
            inheritExtents(base, f);
            sDerivedFrom.put(id, f);
        }
        return f;
    }

    private static final ConcurrentHashMap<Long, Font> sDerivedFrom =
        new ConcurrentHashMap<>();

    // ================================================================
    // Face selection -- the SINGLE place measure and draw agree on
    // ================================================================
    //
    // Paint.resolveFontMetrics, Paint.resolveFont and Canvas.deriveFont all
    // used to re-derive "which font, which AWT style" from the Paint by hand.
    // They agreed by inspection, which is exactly the property that stops being
    // true the moment one of them learns something the others do not -- and
    // measure/draw disagreement moves wrap points, so it is not a cosmetic bug.
    // They now all call select().

    /** Gate: real face selection + the minikin fakery residual. ON by default;
     *  MACAPK_FONT_REAL_STYLE=0 keeps the family lookup (so a Typeface still
     *  carries its weight) but goes back to serving every request from the one
     *  bundled Roboto-Regular with AWT's algorithmic bold/italic. Independent
     *  of MACAPK_FONT_FAMILY on purpose: with one gate for both, a fleet delta
     *  could not be attributed to the lookup or to the faces. */
    public static final boolean REAL_STYLE =
        !"0".equals(System.getenv("MACAPK_FONT_REAL_STYLE"));

    /** An unsized base font plus the AWT style to derive it at. */
    public static final class Selection {
        /** null => the single bundled Roboto-Regular (the pre-table path). */
        public final Font base;
        /** Font.PLAIN / BOLD / ITALIC / BOLD|ITALIC -- SYNTHETIC styling only. */
        public final int awtStyle;
        Selection(Font base, int awtStyle) { this.base = base; this.awtStyle = awtStyle; }
    }

    /**
     * Resolve a Paint's typeface state to a concrete face.
     *
     * The important asymmetry: `awtStyle` is *synthetic* styling, never a
     * request for weight. On real Android a bold TextView ends up with a
     * Typeface whose resolved weight is 700, minikin hands back the real
     * Roboto-Bold face, and Skia emboldens NOTHING. Emboldening a real bold
     * face is not a near-miss, it is a different artefact: an AWT algorithmic
     * embolden measures ~4px WIDER than Roboto-Bold on the fwprobe sentence
     * while reading THINNER, because it smears each glyph rather than swapping
     * in a heavier outline.
     *
     * So the only two sources of synthetic style here are the ones AOSP also
     * has: the Paint's own fakeBoldText/textSkewX flags (which TextView sets
     * from `need = style & ~typefaceStyle`, i.e. the part the face could not
     * provide), and minikin's own computeFakery residual for the winning face.
     *
     * @param tf            the Paint's typeface, may be null
     * @param paintFakeBold Paint.isFakeBoldText()
     * @param paintSkewed   Paint.getTextSkewX() != 0
     */
    public static Selection select(Typeface tf, boolean paintFakeBold, boolean paintSkewed) {
        int style = Font.PLAIN;
        if (paintFakeBold) style |= Font.BOLD;
        if (paintSkewed)   style |= Font.ITALIC;
        if (tf == null) return new Selection(null, style);

        Font appFont = tf.macapkBaseFont();
        if (REAL_STYLE) {
            if (appFont == null) {
                MacApkFontFamilies.Resolved r = tf.macapkResolvedFace();
                if (r != null) {
                    if (r.fakeBold)   style |= Font.BOLD;
                    if (r.fakeItalic) style |= Font.ITALIC;
                    return new Selection(r.base, style);
                }
            } else {
                // An app-supplied face is a ONE-FACE FAMILY on AOSP too, and it
                // goes through the same getClosestMatch/computeFakery -- the
                // family just has nothing else to offer, so the one face always
                // wins and only the FAKERY question is live.
                //
                // ⚠ This is not the same as `if (tf.isBold())`. A font that
                // DECLARES weight 700 in its OS/2 table needs no synthetic bold
                // when asked for 700, and the old code emboldened it anyway
                // because the API style said BOLD -- doubling the weight of every
                // app that ships a real bold face and sets textStyle="bold" on it.
                // Conversely a 400-only face asked for 700 legitimately DOES fake.
                int wanted = tf.getWeight();
                boolean wantItalic = (tf.getStyle() & Typeface.ITALIC) != 0;
                int actual = tf.macapkAppFaceWeight();
                boolean actualItalic = tf.macapkAppFaceItalic();
                if (wanted >= 600 && (wanted - actual) >= 200) style |= Font.BOLD;
                if (wantItalic && !actualItalic)               style |= Font.ITALIC;
                return new Selection(appFont, style);
            }
        }
        // No system family to resolve and no real-style selection (the table is
        // gated off): keep the historical behaviour of styling from the
        // typeface's own bits.
        if (tf.isBold())   style |= Font.BOLD;
        if (tf.isItalic()) style |= Font.ITALIC;
        return new Selection(appFont, style);
    }

    /** The sized java.awt.Font for a Selection, cached. */
    public static Font sized(Selection sel, float sizePx) {
        if (sel == null) return get(sizePx, Font.PLAIN);
        return sel.base != null ? getFrom(sel.base, sizePx, sel.awtStyle)
                                : get(sizePx, sel.awtStyle);
    }

    // ================================================================
    // Reading a face's OWN style out of the font file
    // ================================================================
    //
    // AOSP does not guess a custom font's weight: Typeface.createFromAsset ->
    // createFromFamiliesWithDefault(..., RESOLVE_BY_FONT_TABLE, ...) reaches
    // hwui Typeface::createFromFamilies, which asks Skia
    // (`skTypeface->fontStyle()`) and Skia reads the OS/2 table. So an app that
    // ships MyFont-Bold.ttf gets a Typeface whose getWeight() is 700 on a
    // device, and minikin then knows it does NOT need to fake bold for it.
    //
    // java.awt.Font cannot answer this: Font.createFont always hands back a
    // PLAIN 1pt instance whatever the file says, and getFontName() is a NAME,
    // not a weight. So parse the same table Skia does.
    //
    // OS/2 layout (Microsoft OpenType spec, table OS/2):
    //   +0   uint16 version
    //   +4   uint16 usWeightClass      1..1000
    //   +62  uint16 fsSelection        bit0 ITALIC, bit9 OBLIQUE
    // reached through the sfnt table directory:
    //   +0   uint32 sfntVersion ('ttcf' => a collection; its first font's
    //                            offset table is at the offset stored at +12)
    //   +4   uint16 numTables
    //   +12  16-byte records: tag(4) checksum(4) offset(4) length(4)

    // ================================================================
    // VERTICAL extents -- Paint.FontMetrics.top / .bottom
    // ================================================================
    //
    // ⚠ These used to be FABRICATED: `m.top = m.ascent - 2; m.bottom =
    // m.descent + 2;`. A hardcoded 2px, at every text size, standing in for the
    // font's real glyph extents. Everything that lays text out downstream --
    // TextView's measured height, its baseline, every line top/bottom -- reads
    // them when `includeFontPadding` is on, which is the DEFAULT, so every
    // TextView in the fleet measured short and drew its baseline high.
    //
    // Android takes them from Skia's SkFontMetrics fTop/fBottom, which for a
    // TrueType face are the FONT BOUNDING BOX out of the `head` table, and
    // rounds outward (libs/hwui/jni/Paint.cpp getFontMetricsInt):
    //     fmi->top    = SkScalarFloorToInt(metrics.fTop);
    //     fmi->bottom = SkScalarCeilToInt(metrics.fBottom);
    //
    // MEASURED against the checked-in golden, Roboto-Regular
    // (upem 2048, head yMax 2163, yMin -555) -- all three sizes exact, on BOTH
    // the height and the baseline:
    //     12px  top=-13 bottom=4   sum 17   golden h=17  baseline 13
    //     20px  top=-22 bottom=6   sum 28   golden h=28  baseline 22
    //     40px  top=-43 bottom=11  sum 54   golden h=54  baseline 43
    //
    // ⚠ IT MUST BE `head`, NOT `OS/2` usWinAscent/usWinDescent. They agree for
    // Roboto and DISAGREE for two other bundled faces -- NotoSerif (head 26 vs
    // win 28 at 20px) and DroidSansMono (head 28 vs win 24) -- so picking the
    // wrong table looks right on the font you happen to test with.
    //
    // ⚠ AWT CANNOT ANSWER THIS. FontMetrics.getMaxAscent()/getMaxDescent(),
    // which look like the right accessors, return exactly getAscent()/
    // getDescent() for these faces (measured: 19/5 at 20px, both pairs), so
    // they carry no extra information at all. The tables have to be read.

    /** {yMax, yMin, unitsPerEm} from the `head` table, or null. */
    private static int[] readHeadExtents(byte[] b) {
        try {
            int base = 0;
            if (u32(b, 0) == 0x74746366L) {          // 'ttcf': font 0
                base = (int) u32(b, 12);
                if (base < 0) return null;
            }
            int numTables = u16(b, base + 4);
            if (numTables <= 0) return null;
            for (int i = 0; i < numTables; i++) {
                int rec = base + 12 + i * 16;
                if (rec + 16 > b.length) break;
                if (b[rec] == 'h' && b[rec + 1] == 'e' && b[rec + 2] == 'a' && b[rec + 3] == 'd') {
                    int off = (int) u32(b, rec + 8);
                    int upem = u16(b, off + 18);
                    int yMin = s16(b, off + 38);
                    int yMax = s16(b, off + 42);
                    if (upem <= 0 || yMax <= yMin) return null;
                    // v1.0.940: carry the hhea lineGap alongside, as slot [3].
                    // Skia fills SkFontMetrics.fLeading from it and AOSP reports
                    // that verbatim as FontMetrics.leading, so a face with a
                    // non-zero gap must report it. Every font WE bundle has gap
                    // 0, which is why pinning leading to 0 was invisible -- but
                    // an APP-SUPPLIED font with a real gap was silently getting
                    // 0, and getFontSpacing() is descent - ascent + leading, so
                    // the app's own line spacing came out short.
                    return new int[] { yMax, yMin, upem, readHheaLineGap(b, base, numTables) };
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    /** The `hhea` table's lineGap (int16 at offset +8), in font units. 0 when
     *  the table is absent or unreadable, which is also the overwhelmingly
     *  common real value -- every face Mac.apk bundles has gap 0. */
    private static int readHheaLineGap(byte[] b, int base, int numTables) {
        try {
            for (int i = 0; i < numTables; i++) {
                int rec = base + 12 + i * 16;
                if (rec + 16 > b.length) break;
                if (b[rec] == 'h' && b[rec + 1] == 'h' && b[rec + 2] == 'e' && b[rec + 3] == 'a') {
                    int off = (int) u32(b, rec + 8);
                    return s16(b, off + 8);
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }
    private static int s16(byte[] b, int off) {
        int v = u16(b, off);
        return (v >= 0x8000) ? v - 0x10000 : v;
    }

    // Font instance -> its face's {yMax, yMin, upem}. Populated for BASE fonts
    // as they load, and propagated to every DERIVED instance in get()/getFrom()
    // -- which are the only places a derived Font is created, so anything a
    // Paint can end up holding is covered. Keyed by the Font itself because a
    // derived instance cannot be traced back to its bytes.
    private static final ConcurrentHashMap<Font, int[]> sExtents = new ConcurrentHashMap<>();

    static void rememberExtents(Font f, int[] headExtents) {
        if (f != null && headExtents != null) sExtents.put(f, headExtents);
    }
    static void rememberExtents(Font f, byte[] fontBytes) {
        rememberExtents(f, readHeadExtents(fontBytes));
    }
    private static void inheritExtents(Font base, Font derived) {
        // Carry the declared-bold flag across derivation too: fallbackBaseFor is
        // called with the SIZED primary, so a flag recorded only on the unsized
        // face would never be found.
        if (base != null && derived != null) {
            Boolean b = sDeclaredBold.get(base);
            if (b != null) sDeclaredBold.put(derived, b);
        }
        int[] e = (base == null) ? null : sExtents.get(base);
        if (e != null && derived != null) sExtents.put(derived, e);
    }

    /** {top, bottom} for a SIZED font as RAW FLOATS -- the un-rounded values,
     *  which is what `Paint.getFontMetrics()` (the float API) must report.
     *
     *  AOSP writes the float and int paths from the SAME SkFontMetrics but
     *  rounds only on the int one: `getFontMetrics` stores
     *  `SkScalarToFloat(metrics.fTop)` verbatim (libs/hwui/jni/Paint.cpp:621-625),
     *  while `getFontMetricsInt` floors top and ceils bottom (:640, :643). So
     *  the two must NOT share a rounded value -- keep `verticalExtents` (int)
     *  for getFontMetricsInt and use this for getFontMetrics.
     *
     *  Measured gap this closes: at 32px Roboto we reported top/bottom
     *  -34.0000/9.0000 (the ints, widened) against a golden -33.7969/8.6719. */
    public static float[] verticalExtentsF(Font sized) {
        if (sized == null) return null;
        int[] e = sExtents.get(sized);
        if (e == null) return null;
        double s = sized.getSize2D() / (double) e[2];
        return new float[] { (float) (-e[0] * s), (float) (-e[1] * s) };
    }

    /** {top, bottom} for a SIZED font, rounded outward exactly as
     *  getFontMetricsInt does, or null when the face's tables are unavailable
     *  (the caller then keeps the ascent/descent-derived fallback). */
    /** The face's `hhea` lineGap scaled to this SIZED font -- AOSP's
     *  FontMetrics.leading. 0 when the face has no gap (the common case) or the
     *  tables were unavailable, which is the same answer we gave before this
     *  existed, so nothing that agreed stops agreeing. */
    public static float leadingF(Font sized) {
        if (sized == null) return 0f;
        int[] e = sExtents.get(sized);
        if (e == null || e.length < 4 || e[3] == 0) return 0f;
        return (float) (e[3] * (sized.getSize2D() / (double) e[2]));
    }

    public static int[] verticalExtents(Font sized) {
        if (sized == null) return null;
        int[] e = sExtents.get(sized);
        if (e == null) return null;
        double s = sized.getSize2D() / (double) e[2];
        int top    = (int) Math.floor(-e[0] * s);
        int bottom = (int) Math.ceil(-e[1] * s);
        return new int[] { top, bottom };
    }

    // ================================================================
    // Integer-quantized glyph advances
    // ================================================================
    //
    // Android's default Paint has hinting ON and subpixel text positioning OFF,
    // so Skia quantizes EVERY GLYPH'S ADVANCE TO A WHOLE PIXEL and minikin sums
    // those integers. The tell is in the golden itself: `MMMM` measures exactly
    // 68 at 20px, which is 4 x 17.0 -- an exact multiple, which a float sum
    // essentially never produces. Roboto's own M advance is 17.461.
    //
    // MEASURED against nine golden values (7 at 20px, 2 at 32px):
    //     model                                   matches
    //     sum of round(per-glyph float), ceiled    8/9   <- this one
    //     ceil(whole-string float sum)             4/9   <- v1.0.935's model
    //     FontMetrics.stringWidth                  4/9   <- pre-v1.0.932
    // The one miss is the 32px sentence: golden 642, this model 643. That case
    // is the one the whole-string float sum got right, so this is a measured
    // trade, not a free win -- and 8/9 beats 4/9. It also fixes every remaining
    // LAYT row, which were all "Ag" measuring 25 against a reference 24.
    //
    // ⚠ AWT's OWN integer mode is NOT the same thing. With
    // FRACTIONALMETRICS off, stringWidth("M") is 18 because AWT uses the HINTED
    // advance; round(unhinted 17.461) is 17, which is what Skia produces. So
    // the fractional FRC is still required -- it is what yields the accurate
    // float advance that then gets rounded. v1.0.935's rider was necessary but
    // its whole-string-sum half was wrong.
    //
    // ⚠ MEASURE AND DRAW SHARE THE GLYPH VECTOR, not just the algorithm. The
    // quantized positions are written into the GlyphVector itself, so the draw
    // steps by exactly the advances the measure charged. Re-deriving them on
    // the draw side would reintroduce the class of drift this session already
    // fixed twice (the letter-spacing draw, and measure-vs-draw metrics mode).

    /** Gate: integer-quantized glyph advances. ON by default;
     *  MACAPK_TEXT_GLYPH_QUANTIZE=0 restores v1.0.935's whole-string float sum. */
    public static final boolean GLYPH_QUANTIZE =
        !"0".equals(System.getenv("MACAPK_TEXT_GLYPH_QUANTIZE"));

    /** A GlyphVector whose glyph positions have been snapped to cumulative
     *  INTEGER advances, plus that total width. Draw it with
     *  Graphics2D.drawGlyphVector to get ink at exactly the measured positions. */
    public static final class Quantized {
        public final java.awt.font.GlyphVector glyphs;
        public final float width;
        Quantized(java.awt.font.GlyphVector g, float w) { glyphs = g; width = w; }
    }

    /** Fonts with the KERNING attribute turned on, keyed by the plain face.
     *
     *  `deriveFont` is not free and quantize() is on the measureText hot path, so
     *  the kerning-enabled cut is built once per face. Weak keys: a Font derived
     *  per-call (size/style) must not be pinned forever. */
    private static final java.util.Map<Font, Font> sKerned =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Font, Font>());

    /** The same face with OpenType kerning enabled.
     *
     *  ⚠ OpenJDK only runs the `kern` feature when TextAttribute.KERNING is
     *  KERNING_ON -- `Font.getStringBounds` guards on `values.getKerning() == 0`
     *  -- so the attribute is REQUIRED, not an optimisation. Without it,
     *  layoutGlyphVector returns a kernDelta of exactly 0.000 (measured). */
    private static Font kernedFont(Font sized, boolean liga) {
        final java.util.Map<Font, Font> cache = liga ? sKernedLiga : sKerned;
        Font k = cache.get(sized);
        if (k == null) {
            try {
                java.util.Map<java.awt.font.TextAttribute, Object> a =
                    new java.util.HashMap<>();
                a.put(java.awt.font.TextAttribute.KERNING,
                      java.awt.font.TextAttribute.KERNING_ON);
                if (liga) {
                    a.put(java.awt.font.TextAttribute.LIGATURES,
                          java.awt.font.TextAttribute.LIGATURES_ON);
                }
                k = sized.deriveFont(a);
            } catch (Throwable t) {
                k = sized;
            }
            cache.put(sized, k);
        }
        return k;
    }

    /** The same cut with standard ligatures on as well (see ligaFor). */
    private static final java.util.Map<Font, Font> sKernedLiga =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Font, Font>());

    // --- Standard ligatures (v1.0.2240) -----------------------------------
    //
    // minikin shapes with HarfBuzz's default features, which include `liga`
    // and `clig`, and switches the two off only for letter-spaced text:
    // LayoutCore.cpp:362-371 (android11-release) -- "Disable default-on
    // non-required ligature features if letter-spacing ... should not apply
    // optional ligatures" -- `if (fabs(paint.letterSpacing) > 0.03)` pushes
    // no_liga and no_clig. Required ligatures (`rlig`) always run.
    //
    // JDK HarfBuzz runs `rlig` always but `liga` only when the font carries
    // TextAttribute.LIGATURES_ON (measured on the bundled JRE: Rhythm's
    // Google Sans cut shapes "fi" to 2 glyphs, to 1 with the attribute). Our
    // measure and draw never turned it on, so "fi" / "fl" / "ffi" never formed
    // in body text where a device forms them, and widths differed by whatever
    // the ligature saves (or costs). JDK has no `clig` switch of its own;
    // LIGATURES_ON is its whole optional-ligature control.
    //
    // MACAPK_TEXT_LIGATURES=0 restores the pre-v1.0.2240 shaping (no liga).
    public static final boolean STD_LIGATURES =
        !"0".equals(System.getenv("MACAPK_TEXT_LIGATURES"));

    /** Does minikin run optional ligatures for this letterSpacing (in ems)? */
    public static boolean ligaFor(float letterSpacing) {
        return STD_LIGATURES && Math.abs(letterSpacing) <= 0.03f;
    }

    /** null when quantization is gated off or the vector cannot be built.
     *
     *  APPLIES GPOS KERNING (v1.0.1127). AOSP measures through HarfBuzz, and
     *  minikin never disables the `kern` feature: LayoutCore.cpp:361-373
     *  (android11-release) pushes only no_liga/no_clig, and only when
     *  |letterSpacing| > 0.03. So the horizontal `kern` feature -- default-on in
     *  HarfBuzz -- runs on EVERY measurement, and :504/:532/:542 accumulate the
     *  POSITIONED advance (glyph advance + GPOS adjustment) into the width.
     *
     *  We called Font.createGlyphVector, which is a cmap MAP and not a layout: it
     *  runs no GSUB and no GPOS, so we charged raw hmtx advances and never
     *  kerned. The error is per-PAIR, so it only becomes visible once a string
     *  contains enough kerning pairs to cross an integer -- which is exactly why
     *  short strings ("A", "Sphinx") measured EXACT while the sentence and the
     *  45-char longword were 1-4px wide at every size. Measured kern totals read
     *  from the fonts' own GPOS: -69 units for the pangram (ro -20, ox -21,
     *  ov -15, ve -13), -90 for the longword (ra -40, ro -20, ov -15, vo -15).
     *  Negative control that rules out "just subtract one": NotoSerif's longword
     *  kern is -41u = -0.641px, which does NOT cross an integer, and both sides
     *  agree at 740; DroidSansMono has no GPOS at all and every mono row was
     *  already exact.
     *
     *  ⚠ ROUND THE ADVANCE, NOT THE KERN. Skia's advance funcs (installed at
     *  LayoutCore.cpp:398) return an INTEGER under Android's hinting-on /
     *  subpixel-off paint, and the GPOS adjustment is then added to that integer
     *  as a float. Quantizing the kerned delta instead reproduces neither side.
     *
     *  ⚠ KNOWN DIVERGENCE, deliberate: minikin splits a run after every space
     *  (Layout::doLayoutRunCached) so it never kerns ACROSS a space, while this
     *  lays the whole string out at once. No probe row moves either way (Roboto
     *  has no space-adjacent pair in this corpus) and one GlyphVector is what the
     *  draw path needs -- Quantized hands the SAME vector to
     *  Graphics2D.drawGlyphVector so ink lands exactly where it was measured, and
     *  per-word vectors would break that invariant. */
    public static Quantized quantize(Font sized, String text) {
        return quantize(sized, text, STD_LIGATURES);
    }

    /** `liga`: shape with standard ligatures (ligaFor(letterSpacing)). */
    public static Quantized quantize(Font sized, String text, boolean liga) {
        if (!GLYPH_QUANTIZE || sized == null || text == null || text.isEmpty()) return null;
        try {
            // layoutGlyphVector also runs GSUB, so ligatures make getNumGlyphs()
            // differ from text.length(), and isZeroAdvanceMark already maps
            // back through getGlyphCharIndex, so it survives the change.
            // ⚠ CORRECTED v1.0.2239: this said it "matches minikin (liga stays
            // on)" while kernedFont() never set LIGATURES_ON, which JDK
            // HarfBuzz needs before it runs `liga`. Since v1.0.2240 `liga`
            // follows minikin's rule; see "Standard ligatures".
            char[] chars = text.toCharArray();
            int flags = java.text.Bidi.requiresBidi(chars, 0, chars.length)
                        && new java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
                               .baseIsLeftToRight() == false
                      ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT;
            java.awt.font.GlyphVector gv;
            try {
                gv = kernedFont(sized, liga).layoutGlyphVector(textFrc(), chars, 0, chars.length, flags);
            } catch (Throwable t) {
                gv = sized.createGlyphVector(textFrc(), text);
            }
            int n = gv.getNumGlyphs();
            float x = 0f;
            for (int i = 0; i < n; i++) {
                java.awt.geom.Point2D p = gv.getGlyphPosition(i);
                // The GPOS adjustment is the gap between what the layout PLACED
                // and what the raw advance would have produced. Read it before
                // overwriting the position.
                float rawAdv = gv.getGlyphMetrics(i).getAdvance();
                float kernAdj = 0f;
                if (i + 1 <= n) {
                    try {
                        kernAdj = (float) (gv.getGlyphPosition(i + 1).getX()
                                           - p.getX() - rawAdv);
                    } catch (Throwable ignored) {}
                }
                gv.setGlyphPosition(i, new java.awt.geom.Point2D.Float(x, (float) p.getY()));
                x += isZeroAdvanceMark(gv, text, i)
                   ? 0f
                   : Math.round(rawAdv) + kernAdj;
            }
            // Position n is the vector's total advance; keep it consistent so a
            // caller reading it back gets the same number measureText returned.
            gv.setGlyphPosition(n, new java.awt.geom.Point2D.Float(x, 0f));
            return new Quantized(gv, x);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- Emoji advances (v1.0.1129) --------------------------------------
    //
    // Android's emoji advance is NotoColorEmoji's own design metric,
    // 2550/2048 em = 1.245117, ROUNDED to a whole pixel because a CBDT/CBLC
    // strike is a scaled bitmap and FreeType returns integral advances for it.
    // Read straight out of that font's hmtx, the rounded value reproduces the
    // API-30 golden on every size with no fitting: 16 -> 20, 32 -> 40, 64 -> 80,
    // 100 -> 125. We returned exactly 1.0 em at every size, which is the
    // signature of a fallback advance -- we were not measuring the font at all,
    // so every emoji-bearing string wrapped, ellipsized and centred ~20% short.
    //
    // WE SHIP NO FONT AND NO TABLE FOR THIS. The coverage question ("which
    // codepoints get emoji treatment") is answered by the JDK's own Unicode
    // data via Character.isEmojiPresentation / isEmoji, so the only datum here
    // is the one constant below. Bundling NotoColorEmoji purely for metrics
    // would ship 9.3 MB we never rasterize; a derived table would ship ~1.7 KB
    // of data derived from Google's binary that goes stale silently. Neither is
    // needed. (Rendering is unaffected either way -- the JDK logical composite
    // already paints emoji; this only fixes the NUMBER we report.)
    //
    // ⚠ THE PREDICATE IS isEmojiPresentation, NOT isEmoji, and the difference is
    // visible in ordinary prose. Measured on this JDK: 1424 codepoints carry
    // Emoji, but only 1205 carry Emoji_Presentation. The gap is characters like
    // (c) U+00A9, (tm) U+2122, U+263A and U+2764 -- all Emoji=true but
    // TEXT-presented by default, drawn by a normal font at a normal width.
    // Charging them 1.245 em would make (c) and (tm) 25% too wide in running
    // text. A text-default emoji earns the emoji advance only when U+FE0F
    // follows it, which is what Android itself resolves.
    //
    // These were REFLECTIVE until v1.0.1132, because Character.isEmoji* are
    // JDK 21 API while runtime/java/ compiled at `--release 17`. That ceiling
    // was raised to 21 (it was undocumented, nothing pinned it, and the bundled
    // JRE has always been 21), so they are now plain calls and the memoizing
    // ConcurrentHashMaps are gone with them -- boxing a codepoint per lookup
    // cost more than the Unicode table lookup it was avoiding.
    // NotoColorEmoji's own design metric, read out of its hmtx: 2550 units at
    // unitsPerEm 2048. Rounded to a whole pixel by emojiAdvance() because a
    // CBDT/CBLC strike is a scaled bitmap and FreeType returns integral
    // advances for it -- which reproduces the API-30 golden at every size with
    // no fitting: 16 -> 20, 32 -> 40, 64 -> 80, 100 -> 125.
    private static final double EMOJI_ADVANCE_EM = 2550.0 / 2048.0;

    public static boolean isEmojiPresentation(int cp) { return Character.isEmojiPresentation(cp); }
    public static boolean isEmojiCodepoint(int cp)    { return Character.isEmoji(cp); }

    /** The whole-pixel advance one emoji cluster gets at this size. */
    public static float emojiAdvance(float sizePx) {
        return (float) Math.floor(EMOJI_ADVANCE_EM * sizePx + 0.5);
    }

    /** End offset of the EMOJI grapheme cluster starting at `i`, or -1 when the
     *  text there is not emoji-presented.
     *
     *  ⚠ THE UNIT IS THE CLUSTER, NOT THE CODEPOINT, and that is load-bearing in
     *  a way no probe row can catch: the battery's emoji case is a single
     *  U+1F600, but a ZWJ family (man+ZWJ+woman+ZWJ+girl) and a flag (two
     *  regional indicators) are ONE cluster of several emoji codepoints each.
     *  Charging per codepoint would measure those 2-3x too wide while the
     *  single-emoji probe row stayed green. Consumed as part of the cluster:
     *  U+FE0F (VS16), U+20E3 (keycap), U+1F3FB..FF (skin tone), ZWJ + the emoji
     *  it joins, and exactly ONE partner regional indicator. */
    public static int emojiClusterEnd(String text, int i, int n) {
        if (text == null || i < 0 || i >= n) return -1;
        int cp = text.codePointAt(i);
        // Cheap reject before any reflection: nothing below U+203C is emoji.
        if (cp < 0x203C) return -1;
        int j = i + Character.charCount(cp);
        if (!isEmojiPresentation(cp)) {
            // Text-default (c)/(tm)/heart style: emoji ONLY with a following VS16.
            if (!isEmojiCodepoint(cp)) return -1;
            if (j >= n || text.codePointAt(j) != 0xFE0F) return -1;
        }
        boolean firstIsRI = (cp >= 0x1F1E6 && cp <= 0x1F1FF);
        while (j < n) {
            int c = text.codePointAt(j);
            if (c == 0xFE0F || c == 0x20E3 || (c >= 0x1F3FB && c <= 0x1F3FF)) {
                j += Character.charCount(c);
                continue;
            }
            if (c == 0x200D) {                       // ZWJ binds the next emoji in
                int k = j + 1;
                if (k < n) {
                    int c2 = text.codePointAt(k);
                    if (isEmojiCodepoint(c2)) { j = k + Character.charCount(c2); continue; }
                }
                break;
            }
            if (firstIsRI && c >= 0x1F1E6 && c <= 0x1F1FF) {
                j += Character.charCount(c);         // a flag is EXACTLY two RIs
                break;
            }
            break;
        }
        return j;
    }

    /** Cheap pre-test so the common all-ASCII string never pays for clustering. */
    public static boolean hasEmoji(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= 0x203C && (isEmojiPresentation(cp) || isEmojiCodepoint(cp))) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Does this glyph carry a NONSPACING mark that shaping would zero?
     *
     *  HarfBuzz zeroes nonspacing-mark advances during shaping -- via GPOS mark
     *  attachment, or `_hb_ot_shape_fallback_mark_position` when the font has no
     *  GPOS -- so on a device "e" + U+0301 measures one advance, not two.
     *
     *  We took the no-shaping fast path and charged raw hmtx. That was invisible
     *  for Roboto, whose U+0301 is already 0-width -- we got the right answer by
     *  FONT-DATA LUCK, not by shaping. DroidSansMono is a strict monospace: every
     *  glyph including U+0301 advances a full 1229 units, so "e"+U+0301 measured
     *  38 where the device says 19. Any diacritic-bearing text in a monospace
     *  font (code viewers, terminals) measured one full advance too wide PER
     *  MARK, and carets/selections drifted with it.
     *
     *  A no-op for fonts already designed with 0-width marks, so no currently
     *  exact measurement can move. Requires a preceding base: a mark in
     *  isolation is rendered on a dotted circle and does advance. */
    private static boolean isZeroAdvanceMark(java.awt.font.GlyphVector gv,
                                             String text, int glyphIndex) {
        if (glyphIndex <= 0) return false;   // no base before it
        try {
            int ci = gv.getGlyphCharIndex(glyphIndex);
            if (ci < 0 || ci >= text.length()) return false;
            int cp = text.codePointAt(ci);
            int type = Character.getType(cp);
            return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
        } catch (Throwable t) {
            return false;
        }
    }

    // measureText is hot and quantize() builds a GlyphVector, so widths are
    // cached by (font, text). Bounded: text that is not worth caching (long
    // runs) is measured without storing, so a scrolling list of unique strings
    // cannot grow this without limit.
    private static final int QUANT_CACHE_MAX_LEN = 64;
    private static final ConcurrentHashMap<String, Float> sQuantWidth =
        new ConcurrentHashMap<>();

    /** The quantized advance of `text` in `sized`, or Float.NaN when the model
     *  is gated off or unavailable (the caller then keeps its float sum). */
    public static float quantizedWidth(Font sized, String text) {
        return quantizedWidth(sized, text, STD_LIGATURES);
    }

    public static float quantizedWidth(Font sized, String text, boolean liga) {
        if (!GLYPH_QUANTIZE || sized == null || text == null || text.isEmpty()) return Float.NaN;
        boolean cacheable = text.length() <= QUANT_CACHE_MAX_LEN;
        String key = null;
        if (cacheable) {
            key = System.identityHashCode(sized) + (liga ? " L " : " ") + text;
            Float w = sQuantWidth.get(key);
            if (w != null) return w;
        }
        Quantized q = quantize(sized, text, liga);
        if (q == null) return Float.NaN;
        if (cacheable && sQuantWidth.size() < 20000) sQuantWidth.put(key, q.width);
        return q.width;
    }

    // --- Letter-spacing clusters (v1.0.2239) -------------------------------
    //
    // minikin applies letterSpacing AFTER shaping, once per HarfBuzz OUTPUT
    // cluster: LayoutCore.cpp (android11-release, LayoutPiece ctor) adds
    // letterSpaceHalf before the first glyph, a full letterSpace wherever
    // `info[i - 1].cluster != info[i].cluster`, and letterSpaceHalf after the
    // last. A required ligature collapses several characters into ONE glyph
    // and ONE cluster, so it gets ONE gap. Material Symbols draws every icon
    // that way: "home" is four characters, one `rlig` glyph (the font's GSUB
    // carries only rclt + rlig), and Material3's default bodyLarge style gives
    // it 0.5sp of letterSpacing.
    //
    // We used to split letter-spaced text into UAX#29 grapheme clusters BEFORE
    // shaping and draw each one alone, so no ligature could ever form: an icon
    // drew as its first letter, the rest clipped by the 24dp icon box (Rhythm,
    // issue #11: H / L / S / S where Home / Library / Search / Settings icons
    // belong). Measure charged one gap per grapheme, four for "home".
    //
    // The cluster unit here is HarfBuzz's default MONOTONE_GRAPHEMES level: a
    // glyph belongs to the grapheme its source character sits in, so a base +
    // combining mark (two glyphs, char indices 0 and 1 on a font without a
    // precomposed form) stays ONE cluster, and a ligature (one glyph whose char
    // index is the first character) leaves the graphemes it swallowed without a
    // glyph and therefore without a gap. For text with no ligature this is
    // exactly the old grapheme count, which is what keeps every other letter-
    // spaced string unchanged.
    //
    // Optional ligatures follow minikin's letter-spacing rule since v1.0.2240
    // ("Standard ligatures" above): on at |letterSpacing| <= 0.03 em, off past
    // it, `rlig` always. Spaced or not, measure and draw shape the same vector.

    /** Gate: shape letter-spaced text as one run and space its glyph clusters.
     *  ON by default; MACAPK_TEXT_SHAPED_SPACING=0 restores the per-grapheme
     *  draw and count (ligatures break under letterSpacing). */
    public static final boolean SHAPED_SPACING =
        !"0".equals(System.getenv("MACAPK_TEXT_SHAPED_SPACING"));

    /** For each glyph of `gv` (laid out from `text`), the ordinal of the
     *  letter-spacing cluster it belongs to, in glyph order; element
     *  `getNumGlyphs()` is the cluster count. Null when unavailable. */
    public static int[] spacingClusterOrdinals(java.awt.font.GlyphVector gv, String text) {
        if (gv == null || text == null || text.isEmpty()) return null;
        try {
            final int n = text.length();
            final int[] bounds = Paint.macapkClusterBounds(text);
            final int[] graphemeOf = new int[n];
            for (int k = 0; k + 1 < bounds.length; k++) {
                for (int c = bounds[k]; c < bounds[k + 1] && c < n; c++) graphemeOf[c] = k;
            }
            final int ng = gv.getNumGlyphs();
            final int[] out = new int[ng + 1];
            int ord = -1, prev = Integer.MIN_VALUE;
            for (int i = 0; i < ng; i++) {
                int ci = gv.getGlyphCharIndex(i);
                int key = graphemeOf[Math.max(0, Math.min(n - 1, ci))];
                if (key != prev) { ord++; prev = key; }
                out[i] = ord;
            }
            out[ng] = ord + 1;
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final ConcurrentHashMap<String, Integer> sSpacingClusters =
        new ConcurrentHashMap<>();

    /** How many letter-spacing gaps `text` takes in `sized` when shaped as one
     *  run, or -1 when the shaped model is gated off or unavailable (the caller
     *  then keeps its grapheme count). Cached like quantizedWidth: Material3
     *  text carries letterSpacing almost everywhere, so this is hot. */
    public static int spacingClusterCount(Font sized, String text, boolean liga) {
        if (!SHAPED_SPACING || sized == null || text == null || text.isEmpty()) return -1;
        boolean cacheable = text.length() <= QUANT_CACHE_MAX_LEN;
        String key = null;
        if (cacheable) {
            key = System.identityHashCode(sized) + (liga ? " L " : " ") + text;
            Integer c = sSpacingClusters.get(key);
            if (c != null) return c;
        }
        Quantized q = quantize(sized, text, liga);
        if (q == null) return -1;
        int[] ord = spacingClusterOrdinals(q.glyphs, text);
        if (ord == null) return -1;
        int count = ord[ord.length - 1];
        if (cacheable && sSpacingClusters.size() < 20000) sSpacingClusters.put(key, count);
        return count;
    }

    /** A loaded face plus the (weight, slant) the file itself declares. */
    public static final class LoadedFace {
        public final Font font;
        public final int weight;      // usWeightClass, clamped to [1,1000]
        public final boolean italic;
        LoadedFace(Font f, int w, boolean i) { font = f; weight = w; italic = i; }
    }

    private static int u16(byte[] b, int off) {
        if (off < 0 || off + 1 >= b.length) return -1;
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }
    private static long u32(byte[] b, int off) {
        if (off < 0 || off + 3 >= b.length) return -1;
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
             | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    /** (weight, italic) from the font bytes' OS/2 table, or the AOSP defaults
     *  (400, upright) when the table is missing or unreadable -- which is also
     *  what hwui falls back to ("We can't obtain any information from fonts.
     *  Just use default values"). Never throws. */
    public static int[] readOs2Style(byte[] b) {
        try {
            int base = 0;
            if (u32(b, 0) == 0x74746366L) {          // 'ttcf': use font 0
                base = (int) u32(b, 12);
                if (base < 0) return new int[] { 400, 0 };
            }
            int numTables = u16(b, base + 4);
            if (numTables <= 0) return new int[] { 400, 0 };
            for (int i = 0; i < numTables; i++) {
                int rec = base + 12 + i * 16;
                if (rec + 16 > b.length) break;
                if (b[rec] == 'O' && b[rec + 1] == 'S' && b[rec + 2] == '/' && b[rec + 3] == '2') {
                    int off = (int) u32(b, rec + 8);
                    int weight = u16(b, off + 4);
                    int fsSel  = u16(b, off + 62);
                    if (weight < 1 || weight > 1000) weight = 400;
                    // bit 0 ITALIC, bit 9 OBLIQUE -- Skia treats both as slanted.
                    boolean italic = fsSel >= 0 && ((fsSel & 0x0001) != 0 || (fsSel & 0x0200) != 0);
                    return new int[] { weight, italic ? 1 : 0 };
                }
            }
        } catch (Throwable ignored) {}
        return new int[] { 400, 0 };
    }

    /** Load a face AND its declared style from font bytes, or null. */
    public static LoadedFace loadFace(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Font f = createFontFromBytes(bytes);
            rememberExtents(f, bytes);
            int[] st = readOs2Style(bytes);
            // usWeightClass >= 600 is the SemiBold/Bold boundary minikin treats
            // as bold for fallback selection.
            rememberDeclaredBold(f, st[0] >= 600);
            return new LoadedFace(f, st[0], st[1] != 0);
        } catch (Throwable t) {
            return null;
        }
    }

    public static LoadedFace loadFace(java.io.File file) {
        if (file == null) return null;
        try { return loadFace(java.nio.file.Files.readAllBytes(file.toPath())); }
        catch (Throwable t) { return null; }
    }

    public static LoadedFace loadFace(InputStream in) {
        if (in == null) return null;
        try { return loadFace(in.readAllBytes()); }
        catch (Throwable t) { return null; }
    }

    /** Load a base (unsized) Font from a .ttf / .otf file, or null on any
     *  failure (caller falls back to the default). Android's
     *  Typeface.createFromFile / createFromAsset map to this. */
    public static Font loadBase(java.io.File file) {
        if (file == null) return null;
        // Copy into the store rather than handing AWT the app's own path: AWT
        // re-opens by path for the Font's whole life, and the app may delete
        // or overwrite its file at any time (see createFontFromBytes).
        // FileInputStream, not Files.readAllBytes: an interruptible channel would
        // turn a pending thread interrupt into a failed load here.
        try (InputStream in = new java.io.FileInputStream(file)) {
            return createFontFromBytes(in.readAllBytes());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Load a base (unsized) Font from a stream of font bytes, or null. The
     *  stream is fully consumed; the caller owns closing it. */
    public static Font loadBase(InputStream in) {
        if (in == null) return null;
        try {
            return createFontFromBytes(in.readAllBytes());
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================
    // Per-codepoint font fallback (mirrors AOSP Minikin FontCollection)
    // ================================================================
    //
    // Android resolves glyph coverage PER CODEPOINT against an ordered family
    // list: FontCollection::getFamilyForChar walks the families in fonts.xml
    // order and the first family whose cmap covers the character wins. A
    // character NO family covers stays with family 0 and renders that font's
    // .notdef. Our chain mirrors that shape with what exists on this host:
    //   1. the paint's own Typeface font (a custom app font), when set
    //   2. bundled Roboto (Android's family 0, the emulator's default)
    //   3. any Android fallback faces we bundle under /fonts/ (fonts.xml names;
    //      names not present are skipped silently)
    //   4. the JDK logical SANS_SERIF composite, which composes across the
    //      host's own fonts and so covers nearly everything, standing in for
    //      Android's long Noto tail
    //
    // MEASURED victim: deskclock's 12 bullet lines. U+25BA (BLACK RIGHT-POINTING
    // POINTER) is ABSENT from the bundled Roboto's cmap, so every bullet drew
    // Roboto's .notdef crossed box where the emulator draws a solid triangle.
    //
    // Host fonts are deliberately NOT enumerated by name: AWT's canDisplay on
    // individual macOS CFonts OVER-REPORTS coverage (measured: every host face,
    // even AppleBraille, claims U+25BA), whereas fonts created from real files
    // answer from their cmap and the logical composite is a stable deterministic
    // terminal.
    private static final String[] BUNDLED_FALLBACK_RESOURCES = {
        "/fonts/NotoSansSymbols-Regular-Subsetted.ttf",
        "/fonts/NotoSansSymbols-Regular-Subsetted2.ttf",
        // v1.0.938: Hebrew. Before this the chain fell through to the JDK
        // logical composite, which resolves Hebrew via a host face whose
        // advances are not Android's -- measured 72 against a golden 70 on the
        // fwprobe hebrew row. Both weights are bundled because the fallback is
        // weight-aware below; serving Regular to a bold run is the other half
        // of that same divergence.
        "/fonts/NotoSansHebrew-Regular.ttf",
        "/fonts/NotoSansHebrew-Bold.ttf",
    };

    // ⚠ NotoColorEmoji.ttf is deliberately NOT bundled. It LOADS under
    // Font.createFont and canDisplayUpTo even answers true, so it looks
    // available -- but it is a CBDT/CBLC colour-bitmap font and AWT gives every
    // glyph in it a **0.000 advance** (measured on the AVD's own 9.7 MB copy at
    // 32px). Bundling it would make emoji measure zero-wide and paint nothing,
    // where the JDK logical composite currently renders real colour emoji off
    // macOS at a wrong-but-nonzero advance. That is why the 8 emoji rows in
    // TXTM stay open: closing them needs a colour-font-capable draw path, not
    // a font file. Do not "just bundle it".

    /** True when this bundled fallback is the BOLD cut of its family, matched by
     *  the AOSP file-naming convention its own fonts.xml uses. */
    private static boolean isBoldFallback(int idx) {
        return idx >= 0 && idx < BUNDLED_FALLBACK_RESOURCES.length
            && BUNDLED_FALLBACK_RESOURCES[idx].contains("-Bold");
    }

    /** Faces whose FILE declares bold, regardless of the AWT style bit.
     *
     *  Needed because select() hands back a REAL bold face with AWT style PLAIN
     *  (synthetic styling is applied only when no real cut exists), so
     *  `Font.isBold()` is FALSE precisely when a genuine Roboto-Bold is the
     *  primary -- which is exactly when a bold fallback should be chosen. Two
     *  individually-correct designs composing into a bug. */
    private static final java.util.Map<Font, Boolean> sDeclaredBold =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Font, Boolean>());

    static void rememberDeclaredBold(Font f, boolean bold) {
        if (f != null) sDeclaredBold.put(f, bold);
    }

    /** True when this face (or the face it was derived from) is really bold. */
    public static boolean macapkIsRealBold(Font f) {
        if (f == null) return false;
        if (f.isBold()) return true;
        Boolean b = sDeclaredBold.get(f);
        return b != null && b;
    }

    private static volatile Font[] sBundledFallbacks;
    private static volatile Font sLogicalFallback;   // assigned last: doubles as the built flag

    private static void ensureFallbacks() {
        if (sLogicalFallback != null) return;
        synchronized (MacApkFont.class) {
            if (sLogicalFallback != null) return;
            java.util.ArrayList<Font> list = new java.util.ArrayList<>();
            for (String res : BUNDLED_FALLBACK_RESOURCES) {
                try (InputStream in = MacApkFont.class.getResourceAsStream(res)) {
                    if (in != null) {
                        byte[] bytes = in.readAllBytes();
                        Font f = createFontFromBytes(bytes);
                        rememberExtents(f, bytes);
                        rememberDeclaredBold(f, res.contains("-Bold"));
                        list.add(f);
                    }
                } catch (Throwable ignored) {}
            }
            sBundledFallbacks = list.toArray(new Font[0]);
            sLogicalFallback = new Font(Font.SANS_SERIF, Font.PLAIN, 1);
        }
    }

    /** The UNSIZED chain font that covers cp, or null when the primary already
     *  covers it, or when nothing does -- in which case the primary draws its
     *  .notdef, exactly what Minikin's family-0 last resort produces. `primary`
     *  may be a sized derived instance; canDisplay reads the cmap, which
     *  deriving does not change. */
    public static Font fallbackBaseFor(int cp, Font primary) {
        // Cached per (primary, codepoint): the answer only depends on the
        // primary's cmap/weight and the fixed chain, but measureText asks it
        // for every character of every string on every frame. JFR on WhatsApp
        // (Hebrew, which Roboto lacks, so every letter walks the chain): this
        // was the top self frame on the UI thread. BMP only, a 256-entry page
        // per touched block; racy fills are benign (same value either way).
        if (primary == null || cp < 0 || cp > 0xFFFF) return fallbackBaseForUncached(cp, primary);
        Object[][] pages = sFallbackPages.get(primary);
        if (pages == null) {
            // One entry per sized primary; a text-size animation makes a new
            // Font every frame, so the map is bounded rather than left to grow.
            if (sFallbackPages.size() >= FALLBACK_CACHE_FONTS) sFallbackPages.clear();
            pages = new Object[256][];
            Object[][] prev = sFallbackPages.putIfAbsent(primary, pages);
            if (prev != null) pages = prev;
        }
        Object[] page = pages[cp >>> 8];
        if (page == null) pages[cp >>> 8] = page = new Object[256];
        Object v = page[cp & 0xFF];
        if (v == null) {
            Font f = fallbackBaseForUncached(cp, primary);
            page[cp & 0xFF] = v = (f == null ? FALLBACK_NONE : f);
        }
        return v == FALLBACK_NONE ? null : (Font) v;
    }
    private static final Object FALLBACK_NONE = new Object();
    private static final int FALLBACK_CACHE_FONTS = 256;
    private static final java.util.concurrent.ConcurrentHashMap<Font, Object[][]> sFallbackPages =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static Font fallbackBaseForUncached(int cp, Font primary) {
        if (primary != null && primary.canDisplay(cp)) return null;
        ensureBase();
        ensureFallbacks();
        // Roboto (family 0). When `primary` IS Roboto-derived this repeats a cmap
        // miss and falls through, so there is no same-family duplicate.
        if (sBase != null && sBase.canDisplay(cp)) return sBase;
        // ⚠ WEIGHT-AWARE. minikin resolves a fallback within the requested
        // FontStyle, so a bold run whose primary lacks the glyph gets the
        // fallback family's BOLD cut, not its Regular. We used to take the
        // first covering face regardless, which drew bold Hebrew in Regular
        // beside bold Latin -- visibly lighter, and measurably narrower.
        Font[] extras = sBundledFallbacks;
        if (extras != null) {
            // NOT primary.isBold(): a real bold face carries AWT style PLAIN
            // here, so the style bit is false exactly when a genuine bold cut is
            // primary. Ask what the FILE declares.
            boolean wantBold = macapkIsRealBold(primary);
            for (int i = 0; i < extras.length; i++) {
                if (extras[i].canDisplay(cp) && isBoldFallback(i) == wantBold) return extras[i];
            }
            // No cut at the requested weight: any covering face beats none, and
            // MacApkFont.atSizeOf will apply the synthetic style on top.
            for (Font f : extras) if (f.canDisplay(cp)) return f;
        }
        return sLogicalFallback.canDisplay(cp) ? sLogicalFallback : null;
    }

    /** True when the ordered chain (primary included) has a real glyph for cp.
     *  Backs Paint.hasGlyph's "checking is done on the entire fallback chain"
     *  contract. */
    public static boolean chainCovers(int cp, Font primary) {
        return (primary != null && primary.canDisplay(cp))
            || fallbackBaseFor(cp, primary) != null;
    }

    /** Unicode Default_Ignorable_Code_Point, as HarfBuzz applies it at RUNTIME
     *  (hb-unicode.hh is_default_ignorable). Android shapes through HarfBuzz,
     *  whose hb_ot_hide_default_ignorables pass replaces these with a
     *  zero-advance INVISIBLE glyph, so real Android never inks them even when
     *  the font carries a visible glyph -- Roboto's uni200E is a real glyph with
     *  zero advance and a negative left side bearing, i.e. it inks to the LEFT of
     *  the pen. opencalculator's "( )" button label contains U+200E.
     *
     *  This mirrors HarfBuzz's RUNTIME set, not the raw Unicode property, because
     *  the runtime set is what determines Android's visible output: HarfBuzz
     *  deliberately EXCLUDES the Hangul fillers U+115F/U+1160/U+3164/U+FFA0
     *  (fonts implement those as regular spacing glyphs) and U+1BCA0..1BCA3. */
    public static boolean isDefaultIgnorable(int cp) {
        int plane = cp >> 16;
        if (plane == 0) {
            switch (cp >> 8) {
                case 0x00: return cp == 0x00AD;
                case 0x03: return cp == 0x034F;
                case 0x06: return cp == 0x061C;
                case 0x17: return cp >= 0x17B4 && cp <= 0x17B5;
                case 0x18: return cp >= 0x180B && cp <= 0x180E;
                case 0x20: return (cp >= 0x200B && cp <= 0x200F)
                        || (cp >= 0x202A && cp <= 0x202E)
                        || (cp >= 0x2060 && cp <= 0x206F);
                case 0xFE: return (cp >= 0xFE00 && cp <= 0xFE0F) || cp == 0xFEFF;
                case 0xFF: return cp >= 0xFFF0 && cp <= 0xFFF8;
                default: return false;
            }
        }
        if (plane == 1)   return cp >= 0x1D173 && cp <= 0x1D17A;
        if (plane == 0xE) return cp >= 0xE0000 && cp <= 0xE0FFF;
        return false;
    }

    // canDisplay('a') per font, cached: gates the ASCII fast path in
    // firstSpecial. A font covering 'a' covers the ASCII printables in every
    // real-world face; an icon font without Latin fails it and takes the
    // per-char probe, which is the correct behaviour for it.
    private static final ConcurrentHashMap<Font, Boolean> sLatinOk =
        new ConcurrentHashMap<>();
    private static boolean latinOk(Font f) {
        Boolean b = sLatinOk.get(f);
        if (b == null) { b = f.canDisplay('a'); sLatinOk.put(f, b); }
        return b;
    }

    /** Index of the first char of `s` needing per-run resolution (the sized
     *  primary cannot display it, or it is a default-ignorable that must be
     *  suppressed), or -1 when the whole string stays on the single-font fast
     *  path -- the overwhelmingly common case, kept at one branch per char for
     *  ASCII. A -1 here is what makes pure-ASCII UI byte-identical to before. */
    public static int firstSpecial(String s, Font primary) {
        if (s == null || primary == null) return -1;
        final int n = s.length();
        final boolean latin = latinOk(primary);
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            if (cp < 0x80) {
                if (!latin && cp >= 0x20 && !primary.canDisplay(cp)) return i;
            } else if (isDefaultIgnorable(cp)) {
                // Needs the run path so measure gives it zero advance and draw
                // gives it zero ink. Every DICP is >= 0x00AD, so the ASCII branch
                // above never needs this check.
                return i;
            } else if (!primary.canDisplay(cp)) {
                return i;
            }
            i += Character.charCount(cp);
        }
        return -1;
    }

    /** Derive an UNSIZED chain font at the sized reference's size and style so a
     *  fallback run draws at the same scale as the primary run beside it. */
    public static Font atSizeOf(Font base, Font sizedRef) {
        if (base == null || sizedRef == null) return sizedRef;
        return getFrom(base, sizedRef.getSize2D(), sizedRef.getStyle());
    }

    // ================================================================
    // Fractional text metrics
    // ================================================================
    //
    // ⚠ THIS IS WHAT MADE v1.0.932's FLOAT-ADVANCE FIX INERT. That change moved
    // measurement from FontMetrics.stringWidth (an INTEGER sum, up to half a
    // pixel of error per character) to Font.getStringBounds, which sums float
    // advances the way minikin does. But a DEFAULT FontRenderContext reports
    // "Integer text metrics mode", and under it getStringBounds degenerates to
    // exactly the same integer sum -- the fwprobe sentence measured 623.0
    // either way, so the fix shipped and moved nothing. Measured with
    // KEY_FRACTIONALMETRICS ON, the same string in the same font is
    // 641.171875 -> ceil 642, which is the checked-in emulator golden EXACTLY
    // (word 96 -> 97, longword 701 -> 704 against goldens 97 and 704).
    //
    // MEASURE AND DRAW MUST FLIP TOGETHER. If only the measure side goes
    // fractional, every wrap is computed ~3% wider than the ink AWT actually
    // paints, which is worse than either mode consistently. That is why this
    // lives here and not at each call site: the four producers are
    // metricsOf (all advance measurement), the Canvas draw surface and every
    // saveLayer through macapkInitHints, the text-shadow offscreen, and
    // textFrc() for the GlyphVector paths (getTextBounds / getTextPath), and
    // they are all fed from these two members.

    /** Gate: fractional text metrics. ON by default;
     *  MACAPK_TEXT_FRACTIONAL_METRICS=0 restores AWT's integer metrics mode. */
    public static final boolean FRACTIONAL_METRICS =
        !"0".equals(System.getenv("MACAPK_TEXT_FRACTIONAL_METRICS"));

    private static final Object FRACTIONAL_HINT = FRACTIONAL_METRICS
        ? java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON
        : java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_OFF;

    /** Put a Graphics2D into the same text-metrics mode everything else uses.
     *  Call before getFontMetrics() or any drawString(). */
    public static void applyTextMetricsHint(java.awt.Graphics2D g) {
        if (g == null) return;
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, FRACTIONAL_HINT);
    }

    /** A FontRenderContext in that same mode, for the GlyphVector paths.
     *  Constructed directly rather than pulled off a throwaway 1x1
     *  BufferedImage+Graphics2D, which is what those call sites used to do on
     *  every single invocation. */
    private static final java.awt.font.FontRenderContext TEXT_FRC =
        new java.awt.font.FontRenderContext(
            null,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            FRACTIONAL_HINT);
    public static java.awt.font.FontRenderContext textFrc() { return TEXT_FRC; }

    /** FontMetrics for an arbitrary sized Font without a live Graphics2D
     *  (cached; the 1x1 image only provides a FontRenderContext, the same
     *  pattern Paint's own metrics resolution uses). */
    private static final ConcurrentHashMap<Font, java.awt.FontMetrics> sMetricsCache =
        new ConcurrentHashMap<>();
    public static java.awt.FontMetrics metricsOf(Font f) {
        if (f == null) return null;
        java.awt.FontMetrics m = sMetricsCache.get(f);
        if (m == null) {
            try {
                java.awt.image.BufferedImage tmp = new java.awt.image.BufferedImage(
                    1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = tmp.createGraphics();
                applyTextMetricsHint(g);
                m = g.getFontMetrics(f);
                g.dispose();
                sMetricsCache.put(f, m);
            } catch (Throwable t) { return null; }
        }
        return m;
    }
}
