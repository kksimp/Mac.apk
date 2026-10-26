package android.widget;
import android.content.Context;
import android.view.View;
// AOSP: `public class TextView extends View implements
// ViewTreeObserver.OnPreDrawListener`
// (frameworks/base/core/java/android/widget/TextView.java). Free: onPreDraw() is
// already implemented below. Inherited by EditText, Button, CompoundButton,
// CheckBox, RadioButton, CheckedTextView, AutoCompleteTextView and the rest of
// the TextView family, so one declaration closes 14 assignability gaps.
public class TextView extends View
        implements android.view.ViewTreeObserver.OnPreDrawListener {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_TEXT_SP = System.getenv("MACAPK_TEXT_SP");

    /** The RAW text, AOSP's `mText`. getText()/getEditableText()/length() and
     *  every input path read and write THIS. Never drawn directly -- see
     *  mTransformed. */
    private CharSequence text = "";

    /** The DISPLAYED text, AOSP's `mTransformed`
     *  (frameworks/base core/java/android/widget/TextView.java:763). AOSP
     *  computes it in setText:
     *      if (mTransformation == null) { mTransformed = text; }
     *      else { mTransformed = mTransformation.getTransformation(text, this); }
     *      if (mTransformed == null) { mTransformed = ""; }
     *  (TextView.java:6575-6583) and then builds the LAYOUT over mTransformed
     *  (makeSingleLayout: `StaticLayout.Builder.obtain(mTransformed, 0,
     *  mTransformed.length(), ...)`, TextView.java:9529-9530), while getText()
     *  keeps returning mText (TextView.java:2442). That split is the whole
     *  reason a password EditText shows dots on screen but hands the app back
     *  its real password.
     *
     *  We have no Layout class, so "the layout is built over mTransformed"
     *  becomes "onDraw / onMeasure / getLineCount read macapkDisplayText()".
     *  AOSP's documented postcondition for setText is that both mText and
     *  mTransformed are non-null afterwards (TextView.java:6423-6426); every
     *  write to `text` in this file goes through macapkSetTextInternal to
     *  preserve it. */
    private CharSequence mTransformed = "";

    /** THE TEXT CARET (cluster E, docs/widget-fidelity-cluster_2026-09-25.md).
     *  Bisect gate, default ON: MACAPK_TEXT_CARET=0 restores the pre-caret
     *  TextView exactly -- no Editor is ever created, isCursorVisible() answers
     *  a constant true, setCursorVisible() discards, getOffsetForPosition()
     *  answers -1, getTotalPaddingTop/Bottom exclude the gravity offset,
     *  getLayout() ignores includeFontPadding, wraps a singleLine field and
     *  drops metric spans, a keystroke appends at the END whatever the caret,
     *  and a style span set on getText() before the first edit is not painted
     *  (getLayout's header, macapkEditAtSelection, spanChange's appearance
     *  arm). Measured victims: OpenCalculator
     *  (emulator draws a 2dp #4bb6e8 caret at the end of its display EditText,
     *  x=784..787; ours drew none and a tap could not move it -- fleet step 2
     *  EMU-ONLY) and every other focused editable field. Offline gate:
     *  debug/caret_gate.sh (its positive control runs with this gate at 0). */
    static final boolean MACAPK_TEXT_CARET =
        !"0".equals(System.getenv("MACAPK_TEXT_CARET"));

    /** AOSP TextView.onCheckIsTextEditor (android14-release TextView.java:9698-9700):
     *      return mEditor != null && mEditor.mInputType != EditorInfo.TYPE_NULL;
     *  i.e. a PLAIN TextView carrying an inputType is a text editor. Ours
     *  inherited View's constant false, so Google Calculator's formula
     *  (CalculatorFormula, a TextView whose style sets
     *  android:inputType=textNoSuggestions) could never show the caret the
     *  emulator draws (emu_2, a 2dp #445e91 bar at x=762..765).
     *
     *  ⚠ DEFAULT OFF, deliberately, and ONLY this piece of the caret work. The
     *  same answer makes the focused formula an IME target
     *  (InputMethodManager.macapkStartInput's IS_TEXT_EDITOR gate), the app's
     *  own onCreateInputConnection returns null, and macapkFallbackConnection
     *  then SUBSTITUTES a MacApkEditorInputConnection -- which a real device
     *  never does -- so TouchDispatcher would commitText() every printable
     *  Mac keystroke straight into the formula text before
     *  com.android.calculator2.Calculator.dispatchKeyEvent (the app's own
     *  hardware-key handler) ever saw it. That is a typing regression this lane
     *  could not measure (no launch), so it stays behind a flip until the IMM
     *  fallback stops overriding an app's explicit null connection.
     *  MACAPK_TEXT_EDITOR_AOSP=1 turns it on. */
    static final boolean MACAPK_TEXT_EDITOR_AOSP =
        "1".equals(System.getenv("MACAPK_TEXT_EDITOR_AOSP"));

    /** AOSP TextView.VERY_WIDE (:457). */
    static final int VERY_WIDE = 1024 * 1024; // XXX should be much larger

    /** AOSP TextView.mEditor: the caret/blink state (see Editor.java). Created
     *  lazily at the same points AOSP's createEditorIfNeeded() runs, and never
     *  with MACAPK_TEXT_CARET off. Field NAME kept: apps reflect on it. */
    Editor mEditor;

    /** AOSP createEditorIfNeeded (:15376-15380). */
    private void createEditorIfNeeded() {
        if (!MACAPK_TEXT_CARET) return;
        if (mEditor == null) mEditor = new Editor(this);
    }

    /** AOSP ctor, the `else if (editable)` branch (:1710-1713): a view whose
     *  getDefaultEditable() holds, with no android:inputType and no
     *  android:editable="false", gets TYPE_CLASS_TEXT, which is what makes
     *  onCheckIsTextEditor() true for it. Read only by onCheckIsTextEditor. */
    private boolean macapkEditableClassText;

    /** The view's OWN android:textCursorDrawable, as written (null when the
     *  XML/style says nothing, in which case the theme is asked lazily). */
    private String macapkCursorDrawableAttr;
    private boolean macapkCursorDrawableResolved;

    /** The view's OWN android:textSelectHandle / Left / Right, as written (null
     *  when the XML/style says nothing: the getters then ask the theme, as
     *  AOSP's obtainStyledAttributes falls back to it). */
    private String macapkSelectHandleAttr, macapkSelectHandleLeftAttr, macapkSelectHandleRightAttr;
    private boolean macapkSelectHandleResolved, macapkSelectHandleLeftResolved, macapkSelectHandleRightResolved;

    private static String macapkNonEmpty(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    /** AOSP TextView.mCursorVisibleFromAttr (:12097-12125 backing field). */
    private boolean mCursorVisibleFromAttr = true;

    /** Constructor tail for the caret. AOSP does all of this inside its
     *  constructor's attribute loop: TextView_cursorVisible (:1427-1431),
     *  TextView_textCursorDrawable (:1504-1506), TextView_editable
     *  (:1259-1260), and the editable branch creating the Editor. */
    private void macapkInitCaret(android.util.AttributeSet a) {
        if (a != null) {
            try {
                macapkSelectHandleAttr = macapkNonEmpty(a.getAttributeValue(MACAPK_ANDROID_NS, "textSelectHandle"));
                macapkSelectHandleLeftAttr = macapkNonEmpty(a.getAttributeValue(MACAPK_ANDROID_NS, "textSelectHandleLeft"));
                macapkSelectHandleRightAttr = macapkNonEmpty(a.getAttributeValue(MACAPK_ANDROID_NS, "textSelectHandleRight"));
            } catch (Throwable ignored) {}
        }
        if (!MACAPK_TEXT_CARET) return;
        boolean editableAttrFalse = false;
        if (a != null) {
            try {
                String cv = a.getAttributeValue(MACAPK_ANDROID_NS, "cursorVisible");
                if ("false".equals(cv) || "0".equals(cv)) setCursorVisible(false);
                String cd = a.getAttributeValue(MACAPK_ANDROID_NS, "textCursorDrawable");
                if (cd != null && !cd.isEmpty()) macapkCursorDrawableAttr = cd;
                String ed = a.getAttributeValue(MACAPK_ANDROID_NS, "editable");
                editableAttrFalse = "false".equals(ed) || "0".equals(ed);
            } catch (Throwable ignored) {}
        }
        if (getDefaultEditable() && !editableAttrFalse) {
            macapkEditableClassText = true;
            createEditorIfNeeded();
        }
    }

    /** The View half still runs View(c, a, defStyleAttr), exactly the super
     *  call the old `this(c, a, defStyleAttr)` delegation reached, so the View
     *  tier is unchanged; only the text-appearance read (cluster B3) now sees
     *  defStyleRes, as AOSP's does (android14 TextView.java :1196-1197 and
     *  :1243-1244 pass it to both obtainStyledAttributes calls). */
    public TextView(Context c, android.util.AttributeSet a, int defStyleAttr, int defStyleRes) {
        super(c, a, defStyleAttr);
        macapkCtorTail(c, a, defStyleAttr, defStyleRes);
    }

    /** AOSP gives TextView its default colour from the THEME
     *  (`?android:textColorPrimary`, resolved through the widget default
     *  style), not from a constant. Ours hardcoded opaque BLACK, so an app on
     *  a legacy DARK theme drew black-on-black. Measured victim: Bouncy's menu
     *  (`Theme.VPTheme` -> `@android:style/Theme.NoTitleBar.Fullscreen`, whose
     *  base `Theme` sets `textColorPrimary=@color/primary_text_dark` = WHITE):
     *  START GAME / HIGH SCORES / HELP / PREFERENCES / QUIT were all invisible
     *  on the black play field, while the one row with an explicit `#f0f0f0`
     *  rendered fine -- exactly the fingerprint
     *  (docs/canvas-ab-analyst-report_2026-08-02.md item 8).
     *  Any android:textColor / textAppearance still wins: the inflater applies
     *  those after construction. Falls back to the previous black on failure,
     *  so an app whose theme we cannot resolve is unchanged.
     *
     *  `have` names the colours the caller already holds and must not be
     *  re-seeded: bit 1 the text colour, 2 the hint, 4 the link (see
     *  macapkCtorTail's seed-last order). Returns true when it stored anything. */
    private boolean macapkInitDefaultTextColor(Context c, int have) {
        if (c == null) return false;
        boolean stored = false;
        try {
            android.content.res.Resources.Theme t = c.getTheme();
            if (t == null) return false;
            if ((have & 1) == 0) {
                // A THEME-LEVEL `android:textColor` outranks textColorPrimary
                // (v1.0.867). AOSP resolves every TextView attribute through
                // obtainStyledAttributes(attrs, R.styleable.TextView, defStyleAttr,
                // defStyleRes), whose LAST fallback tier is the theme itself -- so a
                // theme that declares `android:textColor` supplies it to every
                // TextView that does not set its own, and it wins over the
                // textColorPrimary the widget default style would otherwise give.
                // That is precisely why an app declares it there.
                //
                // MEASURED victim (anuto 34): its GameActivity runs on
                // `@android:style/Theme.NoTitleBar.Fullscreen` -- classic DARK
                // family, so textColorPrimary is WHITE, which is AOSP-correct for
                // that theme and which we answered correctly. But the tutorial
                // panel's context carries the app's OWN `ColourTheme.Popup`, which
                // declares BOTH `backgroundPopup` (a light drawable) AND
                // `android:textColor = #000000`. We honoured the background and
                // ignored the text colour, so the whole panel rendered WHITE ON
                // WHITE: "Getting Started", the body paragraph and both buttons all
                // laid out at correct bounds with tc=0xffffffff and painted nothing.
                // The emulator draws them black.
                //
                // Checked FIRST, and only a declared value is taken -- an app whose
                // theme is silent falls through to the previous textColorPrimary
                // behaviour unchanged, so this cannot alter any app that does not
                // declare a theme-level textColor.
                // A/B'd behind MACAPK_THEME_TEXTCOLOR=0 before landing (flag then
                // removed): ON vs OFF is score-IDENTICAL on mastodon 80.43,
                // opensudoku 70.10 and deskclock 93.29, and 76.82 vs 76.98 on anuto
                // (noise). So this tier does not disturb any app that was already
                // right -- it only fills in the apps whose theme actually declares
                // the attribute, where it is the difference between visible text and
                // none. The fleet regressions in v1.0.867 are the nav-bar height
                // change, not this.
                int col = t.resolveAttrColor("android:textColor", 0);
                if (col == 0) col = t.resolveAttrColor("android:textColorPrimary", 0);
                if (col == 0) col = t.resolveAttrColor("textColorPrimary", 0);
                // Store the LIST as well as the int. AOSP's equivalent step is
                // `setTextColor(attributes.mTextColor)` (:4350-4351), which writes
                // both, so getTextColors() and getCurrentTextColor() always agree.
                // Writing only the int would leave getTextColors() reporting the
                // constructor default while the widget painted the theme colour --
                // a quieter version of the null bug above, and the kind of
                // disagreement an app reads to derive a second colour from.
                if (col != 0) {
                    this.textColor  = col;
                    this.textColors = android.content.res.ColorStateList.valueOf(col);
                    stored = true;
                }
            }
            // The HINT colour comes from the same obtainStyledAttributes chain
            // in AOSP (R.styleable.TextView_textColorHint, applied through
            // setHintTextColor), and every platform theme declares
            // `textColorHint` -- Theme.Material sets it to a dimmed
            // textColorSecondary -- so on a device mHintTextColor is
            // essentially never null and getCurrentHintTextColor
            // (TextView.java:5899) returns the dimmed value rather than
            // mCurTextColor. We never resolved it, so a hint would have painted
            // at the FULL text colour and been indistinguishable from typed
            // text. Resolved here by the same route, and only when the theme
            // actually declares it (an app whose theme is silent keeps AOSP's
            // documented mHintTextColor == null fallback to the text colour).
            if ((have & 2) == 0) {
                int hc = t.resolveAttrColor("android:textColorHint", 0);
                if (hc == 0) hc = t.resolveAttrColor("textColorHint", 0);
                if (hc != 0) {
                    this.hintTextColors = android.content.res.ColorStateList.valueOf(hc);
                    this.hintColor = hc;
                    stored = true;
                }
            }
            // v1.0.1645: the LINK colour, same chain (TextAppearance_textColorLink,
            // theme default ?android:textColorLink; AOSP TextView ctor ->
            // setLinkTextColor). Needed now that URLSpan inherits
            // ClickableSpan.updateDrawState, which paints TextPaint.linkColor: without
            // it every link drew in TextPaint's placeholder default.
            if ((have & 4) == 0) {
                int lc = t.resolveAttrColor("android:textColorLink", 0);
                if (lc == 0) lc = t.resolveAttrColor("textColorLink", 0);
                if (lc != 0) {
                    this.mLinkTextColor = android.content.res.ColorStateList.valueOf(lc);
                    stored = true;
                }
            }
        } catch (Throwable ignored) {}
        return stored;
    }
    /** AOSP chains TextView(Context) to this(context, null) and TextView(Context,
     *  AttributeSet) to this(context, attrs, R.attr.textViewStyle) (android14
     *  :1140-1146). Ours keeps its own View super calls (the View tier is not
     *  this change's), and hands the text-appearance read the defStyleAttr the
     *  AOSP chain would have passed -- see macapkImplicitDefStyleAttr. */
    public TextView(Context c) {
        super(c);
        macapkCtorTail(c, null, macapkImplicitDefStyleAttr(), 0);
    }
    public TextView(Context c, android.util.AttributeSet a) {
        super(c, a);
        macapkCtorTail(c, a, macapkImplicitDefStyleAttr(), 0);
    }
    public TextView(Context c, android.util.AttributeSet a, int s) {
        super(c, a, s);
        macapkCtorTail(c, a, s, 0);
    }

    /** The shared constructor body. With MACAPK_TEXTVIEW_CTOR_APPEARANCE=0 it
     *  is the pre-B3 sequence exactly (macapkApplyInputTypeAttrs(null), which
     *  the one-arg constructor used to skip, returns at its first line).
     *
     *  SEED LAST (MACAPK_TEXTVIEW_CTOR_SEED_LAST, default on; only meaningful
     *  with the constructor read on). The theme-derived colour seed
     *  (macapkInitDefaultTextColor) exists for the colours NO tier supplies: AOSP
     *  seeds black (:1180-1181) and a null hint / link (TextAppearanceAttributes
     *  :4089-4090) and lets the read fill them, and our read now does. Seeding first and then overwriting cost
     *  up to seven theme walks per TextView whose answers were thrown away
     *  (measured 16% of a construction by JFR, the seed's
     *  Theme.resolveAttrColor chain). So with the read on, the seed runs AFTER
     *  it, only for the colours it left unset, and then re-resolves the current
     *  colours exactly as the read's own setters would have (updateTextColors,
     *  which also lands the link colour on a paint the read already created).
     *  Every final colour list and current colour is the one the seed-first
     *  order produced: a colour the read supplies overwrote the seed there, and
     *  one it does not is the seed's either way. =0 restores seed-first. */
    static final boolean MACAPK_TEXTVIEW_CTOR_SEED_LAST =
        !"0".equals(System.getenv("MACAPK_TEXTVIEW_CTOR_SEED_LAST"));

    private void macapkCtorTail(Context c, android.util.AttributeSet a,
                                int defStyleAttr, int defStyleRes) {
        if (MACAPK_TEXTVIEW_CTOR_APPEARANCE && MACAPK_TEXTVIEW_CTOR_SEED_LAST) {
            macapkInitDefaultTextSizeOnly(c);
            final android.content.res.ColorStateList unseededText = textColors;
            macapkCtorTextAppearance(c, a, defStyleAttr, defStyleRes);
            int have = (textColors != unseededText ? 1 : 0)
                     | (hintTextColors != null ? 2 : 0)
                     | (mLinkTextColor != null ? 4 : 0);
            if (have != 7 && macapkInitDefaultTextColor(c, have)) updateTextColors();
        } else {
            macapkInitDefaultTextSize(c);
            macapkCtorTextAppearance(c, a, defStyleAttr, defStyleRes);
        }
        macapkApplyInputTypeAttrs(a);
        macapkInitCaret(a);
        macapkInstallDefaultMovement();
    }

    // ------------------------------------------------------------------
    // CLUSTER B3, THE TEXTVIEW HALF (docs/widget-fidelity-cluster_2026-09-25.md,
    // docs/textview-ctor-appearance_2026-09-26.md): the constructor reads its
    // text appearance the AOSP way.
    //
    // AOSP android14-release TextView.java, the constructor:
    //   :1196-1197  TypedArray a = theme.obtainStyledAttributes(attrs,
    //                   com.android.internal.R.styleable.TextViewAppearance,
    //                   defStyleAttr, defStyleRes);
    //   :1201-1202  int ap = a.getResourceId(
    //                   com.android.internal.R.styleable.TextViewAppearance_textAppearance, -1);
    //   :1204-1206  if (ap != -1) { appearance = theme.obtainStyledAttributes(
    //                   ap, com.android.internal.R.styleable.TextAppearance); ... }
    //   :1210-1213  readTextAppearance(context, appearance, attributes, false);
    //               attributes.mFontFamilyExplicit = false;
    //   :1243-1244  a = theme.obtainStyledAttributes(attrs,
    //                   com.android.internal.R.styleable.TextView, defStyleAttr, defStyleRes);
    //   :1249       readTextAppearance(context, a, attributes, true);
    //   :1812-1818  if (isMonospaceEnforced) attributes.mTypefaceIndex = MONOSPACE;
    //   :1821       applyTextAppearance(attributes);
    // so a view's text size / colour / face / caps / spacing / shadow come from
    // ONE textAppearance (the highest tier that names one: XML, style=,
    // defStyleAttr, defStyleRes, theme) and are then overridden attribute by
    // attribute by the TextView styleable itself over the same five tiers.
    //
    // Ours read NOTHING here: the inflater applied the element's and its
    // style='s attributes after construction, and the defStyleAttr / defStyleRes
    // / theme tiers never arrived at all. Two consequences, both measured by
    // debug/textctor_gate.sh on the apps' own classes:
    //   * every subclass constructor that reads the styled state DURING its own
    //     constructor saw the 14sp black default instead of its style's
    //     (measured: Material's ExtendedFloatingActionButton captures
    //     getTextColors() as the originalTextCsl it restores after every
    //     extend/shrink), and
    //   * a widget whose text style lives only in its default style (a
    //     MaterialButton's TextAppearance.Material3.LabelLarge: sans-serif-medium,
    //     letterSpacing 0.1/14; a Button's allCaps) never got it, because the
    //     inflater's tier-3 pass serves Button/EditText/CheckBox/RadioButton only
    //     and nothing serves a programmatic view.
    // Precedence stays AOSP's: the inflater's later element-level application
    // still overwrites what this read set (it applies the XML / style= tiers,
    // which outrank everything read here).
    //
    // Bisect gate MACAPK_TEXTVIEW_CTOR_APPEARANCE, default ON; =0 restores the
    // exact pre-change constructor (nothing read, macapkTypefaceIndex never set).
    // ------------------------------------------------------------------
    static final boolean MACAPK_TEXTVIEW_CTOR_APPEARANCE =
        !"0".equals(System.getenv("MACAPK_TEXTVIEW_CTOR_APPEARANCE"));

    /** R.styleable.TextViewAppearance: {textAppearance} (attrs.xml
     *  <declare-styleable name="TextViewAppearance">). Id from public-final.xml:
     *  textAppearance = 0x01010034. */
    private static final int[] MACAPK_TVA_ATTRS = { 0x01010034 };

    /** The TextAppearance attributes this port applies, as ids. The TextView
     *  styleable carries the SAME attributes (AOSP's sAppearanceValues,
     *  :4157-4208, maps each TextView_X index to TextAppearance_X), and our
     *  TypedArray is keyed by attribute id, so one array serves both reads
     *  and the index translation AOSP needs is the identity here. Ids from
     *  android14 public-final.xml. Deliberately NOT read: textColorHighlight
     *  (nothing in this port paints the selection highlight, and the theme
     *  tier answers it through ?attr rows our resolver cannot chase, e.g.
     *  AnkiDroid's reads 0xffff00ff), textLocale, fontVariationSettings and
     *  the line-break styles (no consumer). */
    private static final int[] MACAPK_APPEARANCE_ATTRS = {
        0x01010098, // 0  textColor
        0x0101009a, // 1  textColorHint
        0x0101009b, // 2  textColorLink
        0x01010095, // 3  textSize
        0x01010096, // 4  typeface
        0x010103ac, // 5  fontFamily
        0x01010097, // 6  textStyle
        0x01010585, // 7  textFontWeight
        0x0101038c, // 8  textAllCaps
        0x01010161, // 9  shadowColor
        0x01010162, // 10 shadowDx
        0x01010163, // 11 shadowDy
        0x01010164, // 12 shadowRadius
        0x0101045d, // 13 elegantTextHeight
        0x0101057b, // 14 fallbackLineSpacing
        0x010104b6, // 15 letterSpacing
        0x010104b7, // 16 fontFeatureSettings
    };

    /** AOSP's typeface enum (attrs.xml <attr name="typeface">: normal 0, sans
     *  1, serif 2, monospace 3) and TextView's constants for it (:411-414). */
    private static final int MACAPK_TF_DEFAULT = -1, MACAPK_TF_SANS = 1,
                             MACAPK_TF_SERIF = 2, MACAPK_TF_MONOSPACE = 3;

    /** android:typeface as AOSP's mTypefaceIndex, or MACAPK_TF_DEFAULT. Only
     *  the constructor read sets it (so with the gate off it never leaves -1
     *  and macapkApplyTypefaceFromAttrs behaves exactly as before); it is kept
     *  on the view because setTypefaceFromAttrs is re-run by every later
     *  textStyle / fontFamily setter and must keep the same base face. */
    private int macapkTypefaceIndex = MACAPK_TF_DEFAULT;

    /** AOSP TextView.TextAppearanceAttributes (:4084-4114), the fields this
     *  port applies. mTextColor starts null rather than AOSP's
     *  ColorStateList.valueOf(0xFF000000) and mTextSize at -1 rather than 15
     *  (:1181-1182): the theme-derived defaults (macapkInitDefaultTextSizeOnly
     *  before the read, macapkInitDefaultTextColor after it for the colours it
     *  left unset, see macapkCtorTail) stand in for those seeds, so a view whose
     *  tiers all come back empty keeps what it had before this change instead
     *  of dropping to 15px black. */
    private static final class MacapkTextAppearance {
        android.content.res.ColorStateList textColor, textColorHint, textColorLink;
        int textSize = -1;
        int textSizeUnit = -1;
        String fontFamily;
        android.graphics.Typeface fontTypeface;
        boolean fontFamilyExplicit;
        int typefaceIndex = -1;
        int textStyle = 0;
        int fontWeight = -1;
        boolean allCaps;
        int shadowColor;
        float shadowDx, shadowDy, shadowRadius;
        boolean hasElegant, elegant;
        boolean hasFallbackLineSpacing, fallbackLineSpacing;
        boolean hasLetterSpacing;
        float letterSpacing;
        String fontFeatureSettings;
    }

    /** The defStyleAttr AOSP's own constructor chain passes when a view is
     *  built through TextView(Context) or TextView(Context, AttributeSet),
     *  i.e. when no subclass constructor supplied one. On a device those
     *  entry points are reached through the MOST-DERIVED framework class's
     *  two-arg constructor, and each framework class chains to its own
     *  default style (android14-release, each file's (Context, AttributeSet)
     *  constructor):
     *    TextView :1144-1146                        textViewStyle 0x01010084
     *    Button :116-118                            buttonStyle 0x01010048
     *    EditText :92-94, ExtractEditText :37-39    editTextStyle 0x0101006e
     *    AutoCompleteTextView :175-177,
     *    MultiAutoCompleteTextView :64-66           autoCompleteTextViewStyle 0x0101006b
     *    CheckedTextView :81-83                     checkedTextViewStyle 0x010103c8
     *    DigitalClock :55-57 -> TextView            textViewStyle
     *  Our framework widgets call super(c, a) instead of chaining, so all of
     *  them land here; walking up from the runtime class to the first
     *  framework class answers what that class's own constructor would have
     *  passed. An app class that extends one of them without a defStyleAttr
     *  of its own inherits its parent's, as it does on a device. Classes
     *  whose AOSP two-arg constructor passes 0 (CompoundButton :95-97,
     *  Chronometer :102-104, TextClock :241-243) reach TextView through the
     *  three-arg constructor and never come here. */
    private int macapkImplicitDefStyleAttr() {
        for (Class<?> k = getClass(); k != null; k = k.getSuperclass()) {
            switch (k.getName()) {
                case "android.widget.TextView":
                case "android.widget.DigitalClock":
                    return 0x01010084;
                case "android.widget.Button":
                    return 0x01010048;
                case "android.widget.EditText":
                case "android.inputmethodservice.ExtractEditText":
                    return 0x0101006e;
                case "android.widget.AutoCompleteTextView":
                case "android.widget.MultiAutoCompleteTextView":
                    return 0x0101006b;
                case "android.widget.CheckedTextView":
                    return 0x010103c8;
                default:
                    break;
            }
        }
        return 0x01010084;
    }

    /** The constructor read itself; see the block comment above. Everything a
     *  resolver cannot answer is simply absent, and a throw anywhere leaves the
     *  view with its defaults (the colour seed still fills whatever the read did
     *  not set): a widget must never fail to construct because its appearance
     *  could not be read. */
    private void macapkCtorTextAppearance(Context context, android.util.AttributeSet attrs,
                                          int defStyleAttr, int defStyleRes) {
        if (!MACAPK_TEXTVIEW_CTOR_APPEARANCE || context == null) return;
        try {
            final android.content.res.Resources.Theme theme = context.getTheme();
            if (theme == null) return;
            final MacapkTextAppearance attributes = new MacapkTextAppearance();

            // :1196-1213. ap == 0 is treated as "none": AOSP's getResourceId
            // never answers 0 (TypedArray.java getResourceId returns defValue
            // unless `resid != 0`), so only our resolver could produce it.
            android.content.res.TypedArray a = theme.obtainStyledAttributes(
                attrs, MACAPK_TVA_ATTRS, defStyleAttr, defStyleRes);
            int ap = a.getResourceId(0, -1);
            a.recycle();
            if (ap != -1 && ap != 0) {
                android.content.res.TypedArray appearance =
                    theme.obtainStyledAttributes(ap, MACAPK_APPEARANCE_ATTRS);
                macapkReadTextAppearance(context, appearance, attributes);
                attributes.fontFamilyExplicit = false;
                appearance.recycle();
            }

            // :1243-1249: the TextView styleable over the same tiers overrides
            // the appearance attribute by attribute.
            a = theme.obtainStyledAttributes(
                attrs, MACAPK_APPEARANCE_ATTRS, defStyleAttr, defStyleRes);
            macapkReadTextAppearance(context, a, attributes);
            a.recycle();

            // :1810-1818. A password field is drawn monospace unless a
            // fontFamily was given explicitly in the TextView styleable (the
            // `mTypefaceIndex != -1 && !mFontFamilyExplicit` rule in
            // applyTextAppearance then drops an appearance's family).
            if (macapkIsPasswordAttrs(attrs)) attributes.typefaceIndex = MACAPK_TF_MONOSPACE;

            macapkApplyTextAppearance(attributes);
        } catch (Throwable ignored) {
            // Keep whatever the defaults and any partial application left.
        }
    }

    /** AOSP readTextAppearance (:4221-4347), for the attributes in
     *  MACAPK_APPEARANCE_ATTRS. AOSP walks getIndexCount()/getIndex(), i.e. only
     *  the attributes some tier actually supplies, including an explicit @empty
     *  (in the index list, AOSP ApplyStyle :340), which resets a colour to null
     *  the way it does on a device. The walk here visits the same set in the
     *  same order through hasValueOrEmpty(i), which is AOSP's own test for
     *  index-list membership (TypedArray.java :1195-1204, `type != TYPE_NULL ||
     *  data == DATA_NULL_EMPTY`): our getIndex(at) rescans from index 0 on every
     *  call, so walking it cost O(n^2) full tier resolutions per array, measured
     *  as the largest single share of this read's time. A TypedArray with no
     *  value source at all answers our hasValueOrEmpty with a legacy universal
     *  true while its getIndexCount() is 0, so when every slot claims a value
     *  (which a real source never does for these 17) getIndexCount() decides. */
    private void macapkReadTextAppearance(Context context,
                                          android.content.res.TypedArray appearance,
                                          MacapkTextAppearance attributes) {
        final int n = appearance.length();
        final boolean[] present = new boolean[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (appearance.hasValueOrEmpty(i)) { present[i] = true; count++; }
        }
        if (count == 0 || (count == n && appearance.getIndexCount() == 0)) return;
        for (int attr = 0; attr < n; attr++) {
            if (!present[attr]) continue;
            try {
                // A reference NO tier could resolve (a `?attr` the theme does
                // not define) reads back as TYPE_NULL with DATA_NULL_UNDEFINED
                // from our TypedArray, and getColorStateList then answers a
                // BLACK stand-in list. On a device that value stays
                // TYPE_ATTRIBUTE and TypedArray.getColorStateList throws
                // "Failed to resolve attribute" (android14 TypedArray.java
                // getColorStateList), so no device ever paints that black: the
                // attribute contributes nothing, and so it does here. Measured
                // on VLC's about.xml under its launch theme, where
                // `android:textColor="?about_text_primary"` does not resolve:
                // white text turned black. An explicit @empty (DATA_NULL_EMPTY)
                // is a real value and still goes through.
                // macapkPeekRecord, not peekValue: peekValue is null for every
                // TYPE_NULL slot (AOSP), and this test needs the @empty data.
                android.util.TypedValue rec = appearance.macapkPeekRecord(attr);
                if (rec.type == android.util.TypedValue.TYPE_NULL
                        && rec.data != 1 /* TypedValue.DATA_NULL_EMPTY */) {
                    continue;
                }
                switch (attr) {
                    case 0: // TextAppearance_textColor
                        attributes.textColor = appearance.getColorStateList(attr);
                        break;
                    case 1: // TextAppearance_textColorHint
                        attributes.textColorHint = appearance.getColorStateList(attr);
                        break;
                    case 2: // TextAppearance_textColorLink
                        attributes.textColorLink = appearance.getColorStateList(attr);
                        break;
                    case 3: { // TextAppearance_textSize
                        attributes.textSize =
                            appearance.getDimensionPixelSize(attr, attributes.textSize);
                        // AOSP: `appearance.peekValue(attr).getComplexUnit()`.
                        // Only a dimension record carries a unit; anything else
                        // reports PX, which is what the stored size is in.
                        android.util.TypedValue pv = appearance.peekValue(attr);
                        attributes.textSizeUnit =
                            (pv != null && pv.type == android.util.TypedValue.TYPE_DIMENSION)
                                ? pv.getComplexUnit()
                                : android.util.TypedValue.COMPLEX_UNIT_PX;
                        break;
                    }
                    case 4: { // TextAppearance_typeface
                        // aapt compiles the enum name to its int; our
                        // AttributeSet can still carry the NAME, and
                        // TypedArray's enum table has no `typeface` row, so a
                        // name that getInt cannot parse is mapped here from
                        // attrs.xml :1369-1374 (normal 0, sans 1, serif 2,
                        // monospace 3).
                        int ti = appearance.getInt(attr, Integer.MIN_VALUE);
                        if (ti == Integer.MIN_VALUE) {
                            String tn = appearance.getString(attr);
                            ti = "normal".equals(tn) ? 0 : "sans".equals(tn) ? MACAPK_TF_SANS
                               : "serif".equals(tn) ? MACAPK_TF_SERIF
                               : "monospace".equals(tn) ? MACAPK_TF_MONOSPACE
                               : attributes.typefaceIndex;
                        }
                        attributes.typefaceIndex = ti;
                        if (attributes.typefaceIndex != -1 && !attributes.fontFamilyExplicit) {
                            attributes.fontFamily = null;
                        }
                        break;
                    }
                    case 5: // TextAppearance_fontFamily
                        // AOSP gates the font-resource attempt on
                        // `!context.isRestricted() && context.canLoadUnsafeResources()`,
                        // both of which hold for an app's own context.
                        try {
                            attributes.fontTypeface = appearance.getFont(attr);
                        } catch (UnsupportedOperationException
                                 | android.content.res.Resources.NotFoundException e) {
                            // Expected if it is not a font resource.
                        }
                        if (attributes.fontTypeface == null) {
                            attributes.fontFamily = appearance.getString(attr);
                        }
                        attributes.fontFamilyExplicit = true;
                        break;
                    case 6: // TextAppearance_textStyle
                        attributes.textStyle = appearance.getInt(attr, attributes.textStyle);
                        break;
                    case 7: // TextAppearance_textFontWeight
                        attributes.fontWeight = appearance.getInt(attr, attributes.fontWeight);
                        break;
                    case 8: // TextAppearance_textAllCaps
                        attributes.allCaps = appearance.getBoolean(attr, attributes.allCaps);
                        break;
                    case 9: // TextAppearance_shadowColor
                        // AOSP reads it with getInt, which answers a colour
                        // record's data (TYPE_INT_COLOR_* sit inside
                        // TYPE_FIRST_INT..TYPE_LAST_INT). Our getInt does not
                        // resolve a #colour / @color value, getColor does, and
                        // answers the same int for every value AOSP accepts.
                        attributes.shadowColor = appearance.getColor(attr, attributes.shadowColor);
                        break;
                    case 10:
                        attributes.shadowDx = appearance.getFloat(attr, attributes.shadowDx);
                        break;
                    case 11:
                        attributes.shadowDy = appearance.getFloat(attr, attributes.shadowDy);
                        break;
                    case 12:
                        attributes.shadowRadius = appearance.getFloat(attr, attributes.shadowRadius);
                        break;
                    case 13:
                        attributes.hasElegant = true;
                        attributes.elegant = appearance.getBoolean(attr, attributes.elegant);
                        break;
                    case 14:
                        attributes.hasFallbackLineSpacing = true;
                        attributes.fallbackLineSpacing =
                            appearance.getBoolean(attr, attributes.fallbackLineSpacing);
                        break;
                    case 15:
                        attributes.hasLetterSpacing = true;
                        attributes.letterSpacing =
                            appearance.getFloat(attr, attributes.letterSpacing);
                        break;
                    case 16:
                        attributes.fontFeatureSettings = appearance.getString(attr);
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
                // One unreadable attribute must not cost the others.
            }
        }
    }

    /** AOSP applyTextAppearance (:4349-4423), through the same setters AOSP
     *  calls. The text size goes in raw, as AOSP's private setRawTextSize
     *  does: routing it through the public setTextSize(int, float) would
     *  reach a subclass override (AppCompatTextView's, which consults its
     *  not-yet-constructed helper) from inside the super constructor, a call
     *  a device never makes. */
    private void macapkApplyTextAppearance(MacapkTextAppearance attributes) {
        if (attributes.textColor != null) setTextColor(attributes.textColor);
        if (attributes.textColorHint != null) setHintTextColor(attributes.textColorHint);
        if (attributes.textColorLink != null) setLinkTextColor(attributes.textColorLink);
        if (attributes.textSize != -1) {
            mTextSizeUnit = attributes.textSizeUnit;
            if (textSizePx != attributes.textSize) {
                textSizePx = attributes.textSize;
                macapkSyncPaintTextSize();
                requestLayout();
                invalidate();
            }
        }
        if (attributes.typefaceIndex != -1 && !attributes.fontFamilyExplicit) {
            attributes.fontFamily = null;
        }
        // setTypefaceFromAttrs(mFontTypeface, mFontFamily, mTypefaceIndex,
        // mTextStyle, mFontWeight), unconditionally, as AOSP does: the fields
        // it reads are this view's, so later textStyle / fontFamily setters
        // (the inflater's) resolve against the same base.
        macapkFontTypeface = attributes.fontTypeface;
        macapkFontFamily = attributes.fontFamily;
        macapkTypefaceIndex = attributes.typefaceIndex;
        macapkTextStyle = attributes.textStyle;
        macapkFontWeight = attributes.fontWeight;
        macapkApplyTypefaceFromAttrs();
        if (attributes.shadowColor != 0) {
            setShadowLayer(attributes.shadowRadius, attributes.shadowDx,
                           attributes.shadowDy, attributes.shadowColor);
        }
        if (attributes.allCaps) {
            setTransformationMethod(new android.text.method.AllCapsTransformationMethod());
        }
        if (attributes.hasElegant) setElegantTextHeight(attributes.elegant);
        if (attributes.hasFallbackLineSpacing) setFallbackLineSpacing(attributes.fallbackLineSpacing);
        if (attributes.hasLetterSpacing) setLetterSpacing(attributes.letterSpacing);
        if (attributes.fontFeatureSettings != null) setFontFeatureSettings(attributes.fontFeatureSettings);
    }

    /** AOSP's isPassword (:1810-1811): android:password (:1449), or an inputType
     *  whose class|variation is text/password, text/webPassword or
     *  number/password (:1612-1619). Read from the element, the same source
     *  macapkApplyInputTypeAttrs uses for the transformation, so the monospace
     *  face and the dots can never disagree. */
    private static boolean macapkIsPasswordAttrs(android.util.AttributeSet a) {
        if (a == null) return false;
        try {
            if ("true".equals(a.getAttributeValue(MACAPK_ANDROID_NS, "password"))) return true;
            String it = a.getAttributeValue(MACAPK_ANDROID_NS, "inputType");
            if (it == null || it.isEmpty()) return false;
            int variation = macapkParseInputType(it)
                & (android.text.InputType.TYPE_MASK_CLASS | android.text.InputType.TYPE_MASK_VARIATION);
            return variation == (android.text.InputType.TYPE_CLASS_TEXT
                                 | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                || variation == (android.text.InputType.TYPE_CLASS_TEXT
                                 | android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || variation == (android.text.InputType.TYPE_CLASS_NUMBER
                                 | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        } catch (Throwable t) {
            return false;
        }
    }

    /** AOSP TextView constructor (android14 :1177) `mMovement =
     *  getDefaultMovementMethod();`, then its setText (:1842) runs
     *  `mMovement.initialize(this, (Spannable) text)` (:7166-7167). For an
     *  EditText that is ArrowKeyMovementMethod, whose initialize puts the
     *  selection at 0 -- so on a device an EditText NEVER answers
     *  getSelectionStart() == -1. v1.0.1642: neither step existed, so every
     *  EditText reported -1, and apps that do text.subSequence(0,
     *  getSelectionStart()) threw (OpenCalculator: every on-screen digit tap
     *  died in a background task and the digit never appeared). A plain
     *  TextView's default is null, which leaves it untouched. */
    private void macapkInstallDefaultMovement() {
        if (movementMethod == null) movementMethod = getDefaultMovementMethod();
        macapkInitializeMovement();
    }

    /** `mMovement.initialize(this, text)` on the current Editable. */
    private void macapkInitializeMovement() {
        android.text.method.MovementMethod mm = movementMethod;
        if (mm == null) return;
        try { mm.initialize(this, getEditableText()); } catch (Throwable ignored) {}
    }

    /** Point `text` at `t` and recompute `mTransformed` from it. AOSP splits
     *  this across setTextInternal (TextView.java:1868-1872, which just stores
     *  mText) and the mTransformed block right after it in setText
     *  (TextView.java:6573-6583); we fuse them because every write to `text`
     *  in this file must keep the pair in sync, and there is no third state
     *  (mSpannable / mPrecomputed) to maintain.
     *
     *  EVERY assignment to `this.text` must route through here. A direct
     *  `this.text = x` leaves mTransformed stale, which for a password field
     *  means the previous value keeps rendering -- or, worse on the first
     *  setText after setTransformationMethod, the RAW value renders. */
    private void macapkSetTextInternal(CharSequence t) {
        this.text = t;
        macapkUpdateTransformed();
    }

    /** Recompute mTransformed from the current raw text + transformation.
     *  Verbatim shape of AOSP TextView.java:6575-6583. */
    private void macapkUpdateTransformed() {
        CharSequence raw = (this.text != null) ? this.text : "";
        CharSequence out;
        if (mTransformationMethod == null) {
            out = raw;
        } else {
            try {
                out = mTransformationMethod.getTransformation(raw, this);
            } catch (Throwable t) {
                // A guest-supplied TransformationMethod that throws must not
                // take the whole draw pass with it. AOSP has no try/catch here
                // (a throwing transformation crashes the app), but our onDraw
                // runs inside ViewCanvasDriver where one bad widget would blank
                // the entire window. Fall back to the untransformed text, which
                // is what AOSP renders when mTransformation is null.
                out = raw;
            }
        }
        // AOSP: "Should not happen if the transformation method follows the
        // non-null postcondition." (TextView.java:6580-6583)
        this.mTransformed = (out != null) ? out : "";
    }

    /** The text this view actually PAINTS and MEASURES: AOSP's mTransformed.
     *  Callers that need the app-visible value must use getText() instead. */
    private CharSequence macapkDisplayText() {
        CharSequence d = mTransformed;
        if (d == null) {
            // Defensive: a subclass constructed before our field initializer
            // ran, or a path that somehow bypassed macapkSetTextInternal.
            d = (text != null) ? text : "";
        }
        return d;
    }

    /** v1.0.121: AOSP TextView's default text-appearance has
     *  textSize=14sp. Without scaling by displayMetrics.density,
     *  TextView's `textSizePx=14f` raw-px default ends up *smaller*
     *  than any layout XML that explicitly sets textSize="13sp"
     *  (which LayoutInflater.parseDimensionPx scales to 13*density
     *  = 26px on xhdpi). F-Droid's Podcini.X card summary was
     *  drawing BIGGER than the name because of this inversion. The
     *  fix: scale the default to the equivalent of 14sp on the
     *  current density, matching AOSP TypedValue.applyDimension. */
    private void macapkInitDefaultTextSize(Context c) {
        macapkInitDefaultTextColor(c, 0);
        macapkInitDefaultTextSizeOnly(c);
    }
    private void macapkInitDefaultTextSizeOnly(Context c) {
        // v1.0.121: env-gate the density default. Setting MACAPK_TEXT_SP=0
        // reverts to the static 14f raw-px default so we can A/B this fix.
        if ("0".equals(ENV_MACAPK_TEXT_SP)) return;
        try {
            android.util.DisplayMetrics dm =
                c != null && c.getResources() != null
                    ? c.getResources().getDisplayMetrics() : null;
            float density = (dm != null && dm.scaledDensity > 0f)
                ? dm.scaledDensity
                : (dm != null && dm.density > 0f ? dm.density : 1f);
            this.textSizePx = 14f * density;
            macapkSyncPaintTextSize();
        } catch (Throwable t) {
            // Leave the static 14f default if the context isn't usable
            // yet (some test paths construct TextView before Resources
            // are bound).
        }
    }
    private static final String MACAPK_ANDROID_NS =
        "http://schemas.android.com/apk/res/android";

    /** Consume `android:inputType` / `android:password` from the inflating
     *  XML. AOSP does this in TextView's OWN constructor, not in
     *  LayoutInflater: it reads TextView_inputType (TextView.java:1377-1379)
     *  and TextView_password (:1365-1367), derives
     *      final boolean isPassword = password || passwordInputType
     *              || webPasswordInputType || numberPasswordInputType;
     *  (:1717-1718) from the CLASS|VARIATION slice of the parsed inputType
     *  (:1519-1526), and then
     *      if (isPassword) {
     *          setTransformationMethod(PasswordTransformationMethod.getInstance());
     *      }
     *  (:1730-1732). Mirroring it here rather than in LayoutInflater keeps the
     *  ownership where AOSP puts it and means a subclass constructed with an
     *  AttributeSet gets the same treatment.
     *
     *  Our LayoutInflater hands View constructors a DOM-backed AttributeSet
     *  (LayoutInflater.java:1531-1533) whose values are the raw XML strings,
     *  so we parse the flag names ourselves instead of going through a
     *  TypedArray. Before this, `android:inputType` was dropped on the floor
     *  entirely -- the inflater has no case for it and no reflective fallback
     *  -- so an `<EditText android:inputType="textPassword"/>` field, the most
     *  common way to declare a password box, was never even told it was one. */
    /** `android:imeOptions` -> the EditorInfo.IME_* bitmask AOSP's
     *  R.styleable.TextView_imeOptions enum/flag table produces. Names and
     *  values from frameworks/base core/res/res/values/attrs.xml (the
     *  <attr name="imeOptions"> block) and EditorInfo's own constants; the
     *  six actions are an ENUM (mutually exclusive, in the low byte) and the
     *  rest are FLAGS, so a plain OR over '|'-separated names reproduces
     *  aapt's own encoding. Unknown names are skipped rather than failing the
     *  whole attribute -- a newer platform flag must not cost us the action. */
    private static int macapkParseImeOptions(String v) {
        int out = 0;
        for (String tok : v.split("\\|")) {
            switch (tok.trim()) {
                case "normal":               out |= android.view.inputmethod.EditorInfo.IME_NULL; break;
                case "actionUnspecified":    out |= android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED; break;
                case "actionNone":           out |= android.view.inputmethod.EditorInfo.IME_ACTION_NONE; break;
                case "actionGo":             out |= android.view.inputmethod.EditorInfo.IME_ACTION_GO; break;
                case "actionSearch":         out |= android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; break;
                case "actionSend":           out |= android.view.inputmethod.EditorInfo.IME_ACTION_SEND; break;
                case "actionNext":           out |= android.view.inputmethod.EditorInfo.IME_ACTION_NEXT; break;
                case "actionDone":           out |= android.view.inputmethod.EditorInfo.IME_ACTION_DONE; break;
                case "actionPrevious":       out |= android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS; break;
                case "flagNoPersonalizedLearning":
                    out |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; break;
                case "flagNoFullscreen":     out |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN; break;
                case "flagNavigatePrevious": out |= android.view.inputmethod.EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS; break;
                case "flagNavigateNext":     out |= android.view.inputmethod.EditorInfo.IME_FLAG_NAVIGATE_NEXT; break;
                case "flagNoExtractUi":      out |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI; break;
                case "flagNoAccessoryAction":out |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_ACCESSORY_ACTION; break;
                case "flagNoEnterAction":    out |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION; break;
                case "flagForceAscii":       out |= android.view.inputmethod.EditorInfo.IME_FLAG_FORCE_ASCII; break;
                default: break;
            }
        }
        return out;
    }

    private void macapkApplyInputTypeAttrs(android.util.AttributeSet a) {
        if (a == null) return;
        try {
            String it = a.getAttributeValue(MACAPK_ANDROID_NS, "inputType");
            if (it != null && !it.isEmpty()) {
                int parsed = macapkParseInputType(it);
                if (parsed != 0) setInputType(parsed);
            }
            // android:imeOptions. Same story as inputType above: the inflater
            // has no case for it, so mImeOptions stayed 0 for every
            // XML-declared field -- and 0 is IME_ACTION_UNSPECIFIED, so a
            // search box that says android:imeOptions="actionSearch" reported
            // "no action" to its own OnEditorActionListener when Enter was
            // pressed (see macapkActionIdForEnterEvent). Flags are '|'-joined
            // names exactly as in inputType.
            String imeOpts = a.getAttributeValue(MACAPK_ANDROID_NS, "imeOptions");
            if (imeOpts != null && !imeOpts.isEmpty())
                setImeOptions(macapkParseImeOptions(imeOpts));

            // Legacy android:password="true" (AOSP TextView_password). Applied
            // after inputType so an explicit password="true" still masks a
            // field whose inputType did not say so, matching AOSP's OR.
            String pw = a.getAttributeValue(MACAPK_ANDROID_NS, "password");
            if ("true".equals(pw)) {
                setTransformationMethod(
                    android.text.method.PasswordTransformationMethod.getInstance());
            }

            // AOSP ctor (android14-release TextView.java:1784-1786):
            //     if (singleLine && getKeyListener() == null && ellipsize == ELLIPSIZE_NOT_SET) {
            //         ellipsize = ELLIPSIZE_END;
            //     }
            // so an XML singleLine label with no android:ellipsize ends in an
            // ellipsis, while a programmatic setSingleLine(true) does not. The
            // pre-gate draw dotted EVERY one-line overflow, which hid that
            // difference; now that the ellipsis follows mEllipsize, the XML
            // default has to exist or those labels would lose it. AOSP's key
            // listener exists at that point for an editable view or any
            // inputType (:1640-1725), which is what the two conjuncts below
            // stand in for, since ours is only ever set explicitly. An explicit
            // android:ellipsize is applied by the inflater after construction
            // and still wins.
            if (MACAPK_TEXT_LINES_AOSP) {
                String sl = a.getAttributeValue(MACAPK_ANDROID_NS, "singleLine");
                String el = a.getAttributeValue(MACAPK_ANDROID_NS, "ellipsize");
                if (("true".equals(sl) || "1".equals(sl))
                        && el == null
                        && getKeyListener() == null && !getDefaultEditable() && mInputType == 0) {
                    mEllipsize = android.text.TextUtils.TruncateAt.END;
                }
                // The ctor's ELLIPSIZE_MARQUEE case (:1797-1806): with
                // config_ui_enableFadingMarquee=false it sets
                // mMarqueeFadeMode = MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS, which is
                // what later turns the layout's MARQUEE into END_SMALL. "4" is
                // the attrs.xml enum value, for a binary AttributeSet that did
                // not round-trip the symbolic name. A style-sourced marquee
                // reaches macapkSetXmlMarquee through LayoutInflater instead.
                if ("marquee".equals(el) || "4".equals(el)) mMarqueeShowEllipsis = true;
            }
        } catch (Throwable ignored) {
            // An attribute we cannot parse must never break inflation.
        }
    }

    /** Parse an `android:inputType` attribute value into the InputType bits.
     *  AOSP declares inputType as a <flag> set in frameworks/base/core/res/
     *  res/values/attrs.xml, so values OR together across '|' ("textPassword|
     *  textMultiLine"). Compiled-but-not-symbolised APKs can also present the
     *  raw int, so a leading 0x / plain decimal is accepted too. Unknown names
     *  are skipped rather than failing the whole attribute. */
    private static int macapkParseInputType(String value) {
        String v = value.trim();
        if (v.isEmpty()) return 0;
        // Raw numeric form (aapt-compiled value that did not round-trip to a
        // symbolic name).
        try {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return (int) Long.parseLong(v.substring(2), 16);
            }
            if (v.length() > 0 && (Character.isDigit(v.charAt(0)) || v.charAt(0) == '-')) {
                return (int) Long.parseLong(v);
            }
        } catch (Throwable ignored) {}
        int out = 0;
        for (String part : v.split("\\|")) {
            Integer bits = MACAPK_INPUT_TYPE_NAMES.get(part.trim());
            if (bits != null) out |= bits;
        }
        return out;
    }

    /** The attrs.xml <flag> table for android:inputType, composed from the
     *  android.text.InputType constants (which are themselves quoted verbatim
     *  from AOSP) rather than from hardcoded hex, so the two cannot drift. */
    private static final java.util.Map<String, Integer> MACAPK_INPUT_TYPE_NAMES =
            macapkBuildInputTypeNames();
    private static java.util.Map<String, Integer> macapkBuildInputTypeNames() {
        java.util.HashMap<String, Integer> m = new java.util.HashMap<>();
        final int T = android.text.InputType.TYPE_CLASS_TEXT;
        final int N = android.text.InputType.TYPE_CLASS_NUMBER;
        final int P = android.text.InputType.TYPE_CLASS_PHONE;
        final int D = android.text.InputType.TYPE_CLASS_DATETIME;
        m.put("none",                android.text.InputType.TYPE_NULL);
        m.put("text",                T);
        m.put("textCapCharacters",   T | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        m.put("textCapWords",        T | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        m.put("textCapSentences",    T | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        m.put("textAutoCorrect",     T | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        m.put("textAutoComplete",    T | android.text.InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);
        m.put("textMultiLine",       T | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        m.put("textImeMultiLine",    T | android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE);
        m.put("textNoSuggestions",   T | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        m.put("textEnableTextConversionSuggestions",
              T | android.text.InputType.TYPE_TEXT_FLAG_ENABLE_TEXT_CONVERSION_SUGGESTIONS);
        m.put("textUri",             T | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        m.put("textEmailAddress",    T | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        m.put("textEmailSubject",    T | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT);
        m.put("textShortMessage",    T | android.text.InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
        m.put("textLongMessage",     T | android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE);
        m.put("textPersonName",      T | android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        m.put("textPostalAddress",   T | android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        m.put("textPassword",        T | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        m.put("textVisiblePassword", T | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        m.put("textWebEditText",     T | android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT);
        m.put("textFilter",          T | android.text.InputType.TYPE_TEXT_VARIATION_FILTER);
        m.put("textPhonetic",        T | android.text.InputType.TYPE_TEXT_VARIATION_PHONETIC);
        m.put("textWebEmailAddress", T | android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        m.put("textWebPassword",     T | android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        m.put("number",              N);
        m.put("numberSigned",        N | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        m.put("numberDecimal",       N | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        m.put("numberPassword",      N | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        m.put("phone",               P);
        m.put("datetime",            D);
        m.put("date",                D | android.text.InputType.TYPE_DATETIME_VARIATION_DATE);
        m.put("time",                D | android.text.InputType.TYPE_DATETIME_VARIATION_TIME);
        return m;
    }

    /** AOSP TextView.getText() returns mText -- the RAW text -- not
     *  mTransformed (frameworks/base core/java/android/widget/TextView.java:
     *  2442). This is the half of the split that lets an app read its own
     *  password back: the screen shows dots, getText() hands over the real
     *  characters. Do not "fix" this to return the displayed text. */
    public CharSequence getText() { return text; }
    static final boolean MACAPK_TEXT_WATCHER_EDITABLE =
        !"0".equals(System.getenv("MACAPK_TEXT_WATCHER_EDITABLE"));
    /** AOSP TextView.setText(CharSequence) (android14, `final`): `setText(text,
     *  mBufferType)`. Every setText reaches the 2-arg overload, because that is
     *  the one subclasses override (SmartTube's MarqueeTextViewCompat mirrors its
     *  text into an inner TextView there).
     *
     *  ⚠ The delegation ran the other way (the 2-arg called this one), so an app
     *  override of setText(CharSequence, BufferType) never saw setText(text):
     *  MarqueeTextViewCompat's inner copy stayed empty and a focused, scrolling
     *  SmartTube card title drew nothing. */
    public void setText(CharSequence t) {
        setText(t, mBufferType);
    }
    /** The last BufferType setText was called with (AOSP mBufferType). */
    private BufferType mBufferType = BufferType.NORMAL;
    private void macapkSetTextImpl(CharSequence t) {
        // AOSP routes EVERY programmatic setText through the TextWatcher
        // before/on/after-TextChanged notifications + requestLayout/invalidate
        // (androidx TextInputLayout/MaterialTextView floating-label/error/
        // counter, two-way data-binding afterTextChanged, search-as-you-type).
        // Was a raw field assignment that fired nothing. firingWatchers guards a
        // re-entrant setText a watcher may do. v1.0.423.
        CharSequence n = (t != null) ? t : "";
        // AOSP setText (:7011-7024), BEFORE the filters: a Spanned carrying a
        // TruncateAt.MARQUEE span switches the view to MARQUEE with the
        // SWITCH_SHOW_ELLIPSIS fade mode (config_ui_enableFadingMarquee=false),
        // the one non-XML path that makes a MARQUEE layout END_SMALL.
        if (MACAPK_TEXT_LINES_AOSP && n instanceof android.text.Spanned) {
            try {
                if (((android.text.Spanned) n).getSpanStart(
                        android.text.TextUtils.TruncateAt.MARQUEE) >= 0) {
                    mMarqueeShowEllipsis = true;
                    setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
                }
            } catch (Throwable ignored) {}
        }
        // AOSP runs the incoming value through the registered InputFilters
        // before anything else (TextView.java:7349), so maxLength / textAllCaps
        // apply to programmatic setText too, not just typed input.
        n = macapkApplyFilters(n);
        CharSequence old = this.text;
        if (TW_TRACE && getClass().getSimpleName().contains("CalculatorResult")) {
            com.macapk.loader.NativeLog.i("Mac.apk.TW",
                "CalculatorResult.setText('" + com.macapk.loader.KeyLogPolicy.viewText(this, n)
                + "') vis=" + getVisibility());
        }
        if (textWatchers != null && !textWatchers.isEmpty() && !firingWatchers) {
            firingWatchers = true;
            try {
                int oldLen = old != null ? old.length() : 0;
                for (android.text.TextWatcher w : textWatchers) {
                    try { w.beforeTextChanged(old, 0, oldLen, n.length()); } catch (Throwable ignored) {}
                }
                // AOSP setText (:7136-7141): with listeners registered
                // (needEditableForNotification) the buffer is made an Editable
                // (mEditableFactory.newEditable) BEFORE it is stored, and
                // sendAfterTextChanged((Editable) text) then always runs. We
                // stored the caller's String and skipped afterTextChanged, so
                // TextInputLayout -- whose label logic lives in afterTextChanged
                // -- never collapsed its hint over programmatic text: "Title"
                // drawn full size over the field's value (ytdlnis download
                // sheet, issue #5, 2026-10-19). MACAPK_TEXT_WATCHER_EDITABLE=0
                // restores the old store.
                if (MACAPK_TEXT_WATCHER_EDITABLE && !(n instanceof android.text.Editable)) {
                    n = android.text.Editable.Factory.getInstance().newEditable(n);
                }
                macapkSetTextInternal(n);
                macapkInitializeMovement();
                if (TW_TRACE) com.macapk.loader.NativeLog.i("Mac.apk.TW",
                    "setText fired " + textWatchers.size() + " watcher(s) on "
                    + getClass().getSimpleName() + " text='"
                    + com.macapk.loader.KeyLogPolicy.viewText(this, n) + "'");
                for (android.text.TextWatcher w : textWatchers) {
                    try { w.onTextChanged(n, 0, oldLen, n.length()); } catch (Throwable ignored) {}
                }
                if (n instanceof android.text.Editable) {
                    for (android.text.TextWatcher w : textWatchers) {
                        try { w.afterTextChanged((android.text.Editable) n); } catch (Throwable ignored) {}
                    }
                }
            } finally { firingWatchers = false; }
        } else {
            macapkSetTextInternal(n);
            macapkInitializeMovement();
        }
        // AOSP setText (:7166-7167): the movement method initializes the new
        // buffer (ArrowKeyMovementMethod -> selection 0) BEFORE
        // sendOnTextChanged (:7182) / sendAfterTextChanged (:7196). Both
        // branches above do it right after the text lands, so a formatter
        // watcher's own setText + setSelection(len) is not undone afterwards.
        if (MACAPK_TEXT_CHECK_RELAYOUT) {
            macapkCheckForRelayout();
        } else {
            requestLayout();
            invalidate();
        }
    }

    /** Labelled bisect gate (wave 5, 2026-09-26): AOSP setText ends
     *      if (mLayout != null) { checkForRelayout(); }
     *  (android14-release TextView.java:7178-7179), and checkForRelayout
     *  (:11246-11298) only INVALIDATES a view whose width is static and whose
     *  text height is unchanged (or whose height is fixed); it requests a new
     *  layout only when the height changed under a dynamic height, the width is
     *  dynamic, or the view is MARQUEE. Ours ended every setText with
     *  requestLayout(), so each tick of a timer, score or HUD label scheduled a
     *  whole-tree measure+layout traversal (and our View.measure has no
     *  spec-unchanged skip, View.java's compat header, so that traversal
     *  re-measures every view). MEASURED victim: org.bobstuff.bobball's
     *  updateStatus does four setText calls on two weight-1, 0-width HUD
     *  TextViews every 10th frame: ~6 whole-tree traversals a second during
     *  play where AOSP schedules none
     *  (docs/fleet-misc-shatteredpd-retrowars-bobball-bouncy-unciv_2026-09-26.md,
     *  E2). MACAPK_TEXT_CHECK_RELAYOUT=0 restores the unconditional
     *  requestLayout(); invalidate(). Offline gate: debug/w5widgets_gate.sh
     *  (rows tv-*). */
    static final boolean MACAPK_TEXT_CHECK_RELAYOUT =
        !"0".equals(System.getenv("MACAPK_TEXT_CHECK_RELAYOUT"));

    // What the last onMeasure measured the text WITH, and what it got. This is
    // our stand-in for AOSP's mLayout / mHintLayout: we have no persistent
    // text Layout, so "build the new layout at the old width and compare its
    // height with the old one" becomes "re-run the SAME content measure with
    // the SAME inputs over the new text and compare". Re-using the exact inputs
    // is what keeps the comparison from ever disagreeing with onMeasure itself
    // (a stale height would otherwise stick until something else relayouts).
    /** AOSP `mLayout != null`: a measure recorded the inputs below. */
    private boolean mMacapkRelayoutValid;
    private boolean mMacapkRelayoutAosp;
    private int mMacapkRelayoutMode, mMacapkRelayoutSize, mMacapkRelayoutCap;
    private boolean mMacapkRelayoutHoriz;
    /** AOSP mLayout.getHeight() and the capped line count onMeasure read. */
    private int mMacapkRelayoutTextH, mMacapkRelayoutTextLines;
    /** AOSP mHintLayout.getHeight(); -1 when our measure built no hint. */
    private int mMacapkRelayoutHintH = -1;
    /** The width the recording onMeasure resolved, AOSP's mLayout width plus
     *  the compound padding (see macapkRelayoutWrapsAtLayoutWidth). */
    private int mMacapkRelayoutMeasuredW = -1;

    /** Called by both onMeasure arms with the exact arguments they passed to
     *  macapkMeasureContent for the TEXT, its result, and the hint's height. */
    private void macapkRecordRelayoutInputs(boolean aosp, int mode, int size, int cap,
            boolean horiz, int[] text, int hintH) {
        mMacapkRelayoutAosp = aosp;
        mMacapkRelayoutMode = mode;
        mMacapkRelayoutSize = size;
        mMacapkRelayoutCap = cap;
        mMacapkRelayoutHoriz = horiz;
        mMacapkRelayoutTextH = text[1];
        mMacapkRelayoutTextLines = text[2];
        mMacapkRelayoutHintH = hintH;
        // Filled in by the same onMeasure once it has resolved its width; until
        // then no spec size can match it, so the check stays conservative.
        mMacapkRelayoutMeasuredW = -1;
        mMacapkRelayoutValid = true;
    }

    /** AOSP checkForRelayout's static-width test, second half:
     *  `mMaxWidthMode == mMinWidthMode && mMaxWidth == mMinWidth`. Our modes are
     *  EMS when m{Max,Min}Ems >= 0 and PIXELS otherwise (setWidth/setEms set
     *  both bounds to the same value, which is what makes such a view static).
     *  The PIXELS limits only bind the measure under MACAPK_TEXT_LINES_AOSP (the
     *  legacy onMeasure applies ems alone), so with that gate off only an ems
     *  pair counts, or a wrap view would be called static while it is not. */
    private boolean macapkWidthLimitsFixed() {
        if (mMaxEms >= 0 || mMinEms >= 0) return mMaxEms >= 0 && mMaxEms == mMinEms;
        return MACAPK_TEXT_LINES_AOSP && mMaxWidthPx == mMinWidthPx;
    }

    /** AOSP TextView.checkForRelayout (android14-release :11246-11298):
     *      if ((mLayoutParams.width != LayoutParams.WRAP_CONTENT
     *              || (mMaxWidthMode == mMinWidthMode && mMaxWidth == mMinWidth))
     *              && (mHint == null || mHintLayout != null)
     *              && (mRight - mLeft - getCompoundPaddingLeft() - getCompoundPaddingRight() > 0)) {
     *          int oldht = mLayout.getHeight();
     *          ... makeNewLayout(want, hintWant, ..., false);
     *          if (mEllipsize != TextUtils.TruncateAt.MARQUEE) {
     *              if (mLayoutParams.height != LayoutParams.WRAP_CONTENT
     *                      && mLayoutParams.height != LayoutParams.MATCH_PARENT) {
     *                  autoSizeText(); invalidate(); return;
     *              }
     *              if (mLayout.getHeight() == oldht
     *                      && (mHintLayout == null || mHintLayout.getHeight() == oldht)) {
     *                  autoSizeText(); invalidate(); return;
     *              }
     *          }
     *          requestLayout(); invalidate();
     *      } else {
     *          nullLayouts(); requestLayout(); invalidate();
     *      }
     *  (autoSizeText is a no-op here: this TextView implements no autosize.)
     *  DELIBERATE, conservative deviations -- each one requests a layout where
     *  AOSP might not, never the reverse, so none can leave a stale size:
     *   - never measured (AOSP's `mLayout == null`, which skips the check
     *     entirely and requests NOTHING), no LayoutParams, or a layout already
     *     pending: keep the pre-change requestLayout();
     *   - the capped line count must match too, because our minLines rule reads
     *     it (macapkDesiredHeight) and AOSP's layout-height test cannot see a
     *     change hidden under a maxLines cap smaller than minLines;
     *   - the last measure must have wrapped the text at the width AOSP's old
     *     text layout has, the view's measured width
     *     (macapkRelayoutWrapsAtLayoutWidth). Otherwise we cannot rebuild the
     *     new layout at `want` and compare it with the old one, so we keep the
     *     pre-change requestLayout(). */
    private void macapkCheckForRelayout() {
        final android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (!mMacapkRelayoutValid || lp == null || isLayoutRequested()) {
            requestLayout();
            invalidate();
            return;
        }
        final boolean hasHint = mHint != null && mHint.length() > 0;
        final int contentW = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if ((lp.width != android.view.ViewGroup.LayoutParams.WRAP_CONTENT || macapkWidthLimitsFixed())
                && (!hasHint || mMacapkRelayoutHintH >= 0)
                && contentW > 0
                && macapkRelayoutWrapsAtLayoutWidth()) {
            // Static width: measure the new text exactly as onMeasure last did.
            final int oldht = mMacapkRelayoutTextH;
            final int oldLines = mMacapkRelayoutTextLines;
            final int[] t = mMacapkRelayoutAosp
                ? macapkMeasureContent(macapkDisplayText(), mMacapkRelayoutMode, mMacapkRelayoutSize,
                                       true, mMacapkRelayoutCap, mMacapkRelayoutHoriz)
                : macapkMeasureContent(macapkDisplayText(), mMacapkRelayoutMode, mMacapkRelayoutSize);
            // AOSP makeNewLayout replaced mLayout with the new one: the NEXT
            // setText compares against this text, not the measured one.
            mMacapkRelayoutTextH = t[1];
            mMacapkRelayoutTextLines = t[2];
            if (mEllipsize != android.text.TextUtils.TruncateAt.MARQUEE) {
                // In a fixed-height view, so use our new text layout.
                if (lp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        && lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    invalidate();
                    return;
                }
                // Dynamic height, but height has stayed the same.
                if (t[1] == oldht && t[2] == oldLines
                        && (!hasHint || mMacapkRelayoutHintH == oldht)) {
                    invalidate();
                    return;
                }
            }
            // The height has changed and we have a dynamic height.
            requestLayout();
            invalidate();
        } else {
            // Dynamic width: AOSP nullLayouts(); the next onMeasure records afresh.
            // Also reached when the recorded wrap width is not the measured
            // width (see macapkRelayoutWrapsAtLayoutWidth): the pre-change answer.
            mMacapkRelayoutValid = false;
            requestLayout();
            invalidate();
        }
    }

    /** True when re-running the last onMeasure's content measure wraps the new
     *  text at the width AOSP's checkForRelayout builds the new layout at. That
     *  width is the OLD layout's (android14-release TextView.java:11257-11267):
     *      int want = mLayout.getWidth();
     *      makeNewLayout(want, hintWant, UNKNOWN_BORING, UNKNOWN_BORING, ...);
     *  and onMeasure built that layout at the view's FINAL measured width
     *  (:10960-10970):
     *      int want = width - getCompoundPaddingLeft() - getCompoundPaddingRight();
     *      if (mHorizontallyScrolling) want = VERY_WIDE;
     *      ... makeNewLayout(want, hintWant, boring, hintBoring, ...);
     *  Our content measure wraps at the measure SPEC size minus the same compound
     *  padding, so the two agree when that spec size IS the measured width (an
     *  EXACTLY spec, or an AT_MOST the view filled), or when nothing wraps at all
     *  (horizontally scrolling, AOSP's VERY_WIDE). They disagree for an
     *  UNSPECIFIED spec (our measure never wraps) and for an AT_MOST the view
     *  did not fill. The common case is a MATCH_PARENT TextView under a
     *  wrap_content FrameLayout, measured AT_MOST 1000 and 13 px wide: longer
     *  text re-measured at the spec is still one line, "same height", while AOSP
     *  re-wraps it at 13 px, sees the height grow and requests a layout. Deciding
     *  from the spec left the text clipped until an unrelated relayout. */
    private boolean macapkRelayoutWrapsAtLayoutWidth() {
        return mMacapkRelayoutHoriz
            || (mMacapkRelayoutMode != android.view.View.MeasureSpec.UNSPECIFIED
                && mMacapkRelayoutSize == mMacapkRelayoutMeasuredW);
    }

    // v1.0.95: resolve string resource ID via Context.getResources.getText.
    // Without this, any androidx widget that ends a setTitle chain at
    // TextView.setText(int) (NavigationBarItemView, RatingBar label,
    // etc.) displays nothing -- the BottomNav tabs were rendering 5
    // empty labels until this landed.
    public void setText(int resId) {
        // AOSP (android14 TextView.java:7294-7295): `setText(getContext()
        // .getResources().getText(resid))` -- the full setText path, so
        // filters, TextWatchers, the movement-method initialize and relayout
        // all apply. v1.0.1643: this set the field directly, so
        // editText.setText(R.string.x) left the selection at -1.
        Context c = getContext();
        CharSequence s = null;
        if (c != null && resId != 0) {
            try { s = c.getResources().getText(resId); } catch (Throwable ignored) {}
        }
        setText(s != null ? s : "");
    }

    // Text appearance accessors. Apps query the current text size to
    // do programmatic layout (Frozen Bubble's HomeScreen.addBackButton
    // chains getTextSize -> setTextSize for a derived button style).
    // Default to 14sp so math-using callers don't divide by zero.
    private float textSizePx = 14f;
    private int   textColor  = 0xFF000000;
    /** AOSP seeds the text colour in the TextView CONSTRUCTOR and applies it
     *  unconditionally, so getTextColors() is never null on a constructed
     *  TextView (android14-release core/java/android/widget/TextView.java):
     *      :1181  final TextAppearanceAttributes attributes = new TextAppearanceAttributes();
     *      :1182  attributes.mTextColor = ColorStateList.valueOf(0xFF000000);
     *      :4350  if (attributes.mTextColor != null) {
     *      :4351      setTextColor(attributes.mTextColor);
     *  and :5313 getTextColors() returns that field directly.
     *
     *  Ours was left null until the first setTextColor call, so any app that
     *  READS the colour before setting one got a null and an NPE. Signal does
     *  exactly that while binding every conversation row
     *  (SpoilerRendererDelegate `view.textColors.defaultColor`): 40 NPEs in one
     *  hand-test run, each replacing a FromTextView/EmojiTextView with an empty
     *  placeholder. Seed from the same literal AOSP uses; the int field above
     *  already carried that default, so the two now start out agreeing.
     *  valueOf() is not stateful, so this cannot make drawableStateChanged
     *  re-resolve (and therefore cannot overwrite a theme colour). */
    private android.content.res.ColorStateList textColors =
        android.content.res.ColorStateList.valueOf(0xFF000000);
    // Typeface style bits (Typeface.BOLD / ITALIC). Set from
    // android:textStyle or a resolved textAppearance, or via
    // setTypeface(tf, style). Applied to the draw Paint as fake-bold /
    // italic in onDraw + onMeasure (our Canvas.deriveFont reads the
    // Paint flags). F-Droid's bold summary needs this.
    private int macapkTextStyle = 0;
    /** android:fontFamily, as the raw family NAME (a fonts.xml family or alias
     *  such as "sans-serif-medium"), or null. Kept beside macapkTextStyle
     *  because AOSP resolves the two TOGETHER in setTypefaceFromAttrs and the
     *  order matters: the family is looked up at NORMAL and the style is then
     *  applied on top, so fontFamily="sans-serif-medium" + textStyle="bold"
     *  lands on weight 800 (Roboto-Black), not 700 (Roboto-Bold). Our inflater
     *  applies XML attributes one at a time and in document order, so both
     *  setters re-run the resolution rather than assuming they arrive in a
     *  particular order. */
    private String macapkFontFamily = null;
    /** android:textFontWeight (API 28), or -1 when unset. AOSP keeps it as a
     *  separate input to setTypefaceFromAttrs precisely because a non-negative
     *  weight takes a DIFFERENT arithmetic path -- see
     *  macapkApplyTypefaceFromAttrs. */
    private int macapkFontWeight = -1;
    /** AOSP's `attributes.mFontTypeface`: a Typeface loaded from a FONT
     *  RESOURCE (`android:fontFamily="@font/brand"`), which takes precedence
     *  over the family-name path -- setTypefaceFromAttrs tests
     *  `typeface == null && familyName != null` first. */
    private android.graphics.Typeface macapkFontTypeface = null;
    // The algorithmic styling AOSP computes in setTypeface(tf, style) as
    // `need = style & ~typefaceStyle` -- only what the RESOLVED FACE cannot
    // provide. Held as fields because several call sites build a throwaway
    // Paint and route it through applyTextStyleToPaint.
    private boolean macapkFakeBold = false;
    private boolean macapkFakeItalic = false;

    public void setMacapkTextStyle(int style) {
        this.macapkTextStyle = style;
        macapkApplyTypefaceFromAttrs();
    }
    public int  getMacapkTextStyle() { return macapkTextStyle; }

    /** android:fontFamily. Null or an unknown name is not an error: AOSP's
     *  getSystemDefaultTypeface answers Typeface.DEFAULT for a name that is not
     *  in fonts.xml. */
    public void setMacapkFontFamily(String family) {
        this.macapkFontFamily = family;
        this.macapkFontTypeface = null;   // a NAME supersedes an earlier @font/
        macapkApplyTypefaceFromAttrs();
    }

    /** android:fontFamily="@font/x" -- a font RESOURCE rather than a system
     *  family name. Supersedes any family name, per AOSP's precedence. */
    public void setMacapkFontResource(android.graphics.Typeface tf) {
        if (tf == null) return;
        this.macapkFontTypeface = tf;
        this.macapkFontFamily = null;
        macapkApplyTypefaceFromAttrs();
    }
    public String getMacapkFontFamily() { return macapkFontFamily; }

    /** android:textFontWeight (API 28). Pass -1 to clear it. */
    public void setMacapkFontWeight(int weight) {
        this.macapkFontWeight = weight;
        macapkApplyTypefaceFromAttrs();
    }
    public int getMacapkFontWeight() { return macapkFontWeight; }

    /** AOSP TextView.setTypefaceFromAttrs (TextView.java:2148-2173) for the
     *  case our inflater actually produces -- no explicit Typeface object, an
     *  optional family name:
     *
     *      if (typeface == null && familyName != null) {
     *          final Typeface normalTypeface = Typeface.create(familyName, Typeface.NORMAL);
     *          resolveStyleAndSetTypeface(normalTypeface, style, weight);
     *      } ... else { resolveStyleAndSetTypeface(null, style, weight); }
     *
     *  Note the family is resolved at NORMAL -- the style is applied afterwards,
     *  which is what routes it through the +300 relative-weight arithmetic.
     *
     *  and resolveStyleAndSetTypeface (TextView.java:2176-2185):
     *
     *      if (weight >= 0) {
     *          weight = Math.min(FontStyle.FONT_WEIGHT_MAX, weight);
     *          final boolean italic = (style & Typeface.ITALIC) != 0;
     *          setTypeface(Typeface.create(typeface, weight, italic));
     *      } else {
     *          setTypeface(typeface, style);
     *      }
     *
     *  ⚠ THE TWO BRANCHES ARE NOT VARIATIONS ON EACH OTHER. With an explicit
     *  weight the call goes to `Typeface.create(tf, weight, italic)` --
     *  createAbsolute, which takes the requested weight VERBATIM with no +300 --
     *  and to the ONE-ARG setTypeface, which never touches fakeBoldText or
     *  textSkewX. So `textStyle="bold"` alongside `textFontWeight="500"` gives
     *  weight 500 and NO synthetic bold, where `textStyle="bold"` alone gives
     *  700. Routing both branches through the same helper would quietly add 300
     *  to every explicit weight. */
    private void macapkApplyTypefaceFromAttrs() {
        // AOSP's precedence: an explicit Typeface (a @font/ RESOURCE) wins over
        // a family name, and the name path is only taken when there is no
        // resource -- `if (typeface == null && familyName != null)`.
        // With neither, AOSP falls back to the android:typeface enum
        // (setTypefaceFromAttrs :2367-2382); macapkTypefaceIndex stays
        // MACAPK_TF_DEFAULT (-> null, the old answer) unless the constructor
        // read (cluster B3) found one.
        android.graphics.Typeface base =
              (macapkFontTypeface != null) ? macapkFontTypeface
            : (macapkFontFamily != null)
                ? android.graphics.Typeface.create(macapkFontFamily,
                                                   android.graphics.Typeface.NORMAL)
            : macapkTypefaceIndex == MACAPK_TF_SANS ? android.graphics.Typeface.SANS_SERIF
            : macapkTypefaceIndex == MACAPK_TF_SERIF ? android.graphics.Typeface.SERIF
            : macapkTypefaceIndex == MACAPK_TF_MONOSPACE ? android.graphics.Typeface.MONOSPACE
            : null;
        if (macapkFontWeight >= 0) {
            // FontStyle.FONT_WEIGHT_MAX == 1000.
            int w = Math.min(1000, macapkFontWeight);
            boolean italic = (macapkTextStyle & android.graphics.Typeface.ITALIC) != 0;
            macapkFakeBold = false;
            macapkFakeItalic = false;
            android.text.TextPaint p = getPaint();
            p.setFakeBoldText(false);
            p.setTextSkewX(0f);
            setTypeface(android.graphics.Typeface.create(base, w, italic));
        } else {
            setTypeface(base, macapkTextStyle);
        }
    }

    private void applyTextStyleToPaint(android.graphics.Paint p) {
        if (p == null) return;
        // The typeface carries the family + weight; the two flags carry only
        // the residual AOSP could not satisfy with a real face. Before the
        // fonts.xml table existed this method set fakeBoldText straight off the
        // textStyle bits, which meant every android:textStyle="bold" label was
        // an AWT algorithmic embolden of Roboto-Regular rather than Roboto-Bold.
        p.setTypeface(getTypeface());
        p.setFakeBoldText(macapkFakeBold);
        p.setTextSkewX(macapkFakeItalic ? -0.25f : 0f);
    }
    /** AOSP's private setRawTextSize (:4649-4661) with the unit recorded the way
     *  the constructor records it: how android:textSize from XML reaches the
     *  view. Mac.apk's inflater applies XML attributes after construction, and
     *  doing that through the public setTextSize(int, float) reached app
     *  overrides a device never calls for XML: SmartTube's
     *  MarqueeTextViewCompat forwards setTextSize(unit, size) to its inner
     *  TextView as setTextSize(size) (sp), so the 16sp title it had already
     *  converted to 32 px came out as 32 sp, twice the size. */
    public void macapkSetRawTextSize(float px, int unit) {
        mTextSizeUnit = unit;
        if (textSizePx != px) {
            textSizePx = px;
            macapkSyncPaintTextSize();
            requestLayout();
            invalidate();
        }
    }
    public void setTextSize(float size) { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size); }
    public void setTextSize(int unit, float size) {
        // AOSP TextView.setTextSize(int,float) remembers the UNIT as well as the
        // resolved pixel size (mTextSizeUnit), because getTextSizeUnit() reports
        // it back (AOSP TextView.java:4783). Recording it here changes nothing
        // else about this method.
        mTextSizeUnit = unit;
        // AOSP TextView.setTextSize converts `size` from `unit` to px via the
        // display metrics. Storing 18 raw px for setTextSize(COMPLEX_UNIT_SP, 18)
        // made the text render ~half size on a 2x display; now we honor the unit.
        // v1.0.425.
        android.content.Context c = getContext();
        android.util.DisplayMetrics dm = (c != null && c.getResources() != null)
            ? c.getResources().getDisplayMetrics() : null;
        this.textSizePx = (dm != null)
            ? android.util.TypedValue.applyDimension(unit, size, dm)
            : size;
        macapkSyncPaintTextSize();
        if (TW_TRACE) {
            String cn = getClass().getSimpleName();
            if (cn.contains("CalculatorFormula") || cn.contains("CalculatorResult")) {
                com.macapk.loader.NativeLog.i("Mac.apk.TS",
                    cn + ".setTextSize unit=" + unit + " size=" + size + " -> px="
                    + this.textSizePx + " w=" + getWidth() + " mw=" + getMeasuredWidth()
                    + " h=" + getHeight());
                if (cn.contains("CalculatorResult") && this.textSizePx > 150f) {
                    StringBuilder sb = new StringBuilder("  caller: ");
                    StackTraceElement[] st = new Throwable().getStackTrace();
                    for (int i = 1; i < Math.min(st.length, 8); i++)
                        sb.append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(" <- ");
                    com.macapk.loader.NativeLog.i("Mac.apk.TS", sb.toString());
                }
            }
        }
    }
    public float getTextSize() { return textSizePx; }
    public void setTextColor(int color) {
        // AOSP TextView.setTextColor(int): `mTextColor =
        // ColorStateList.valueOf(color); updateTextColors();`. Replacing the
        // stored LIST is load-bearing, not tidiness: keeping a stale stateful
        // list here would let the next drawableStateChanged overwrite the int the
        // caller just set. valueOf matches any state, so the resolved current
        // colour is exactly `color`.
        this.textColors = android.content.res.ColorStateList.valueOf(color);
        this.textColor = color;
    }
    public void setTextColor(android.content.res.ColorStateList colors) {
        // AOSP TextView.setTextColor(ColorStateList) stores the list and
        // re-resolves through updateTextColors(). The old getDefaultColor()
        // collapse froze every stateful widget on its wildcard colour: a CHECKED
        // bottom-nav label painted the unchecked colorOnSurfaceVariant forever
        // (F-Droid: emulator 295 px of #181c21, ours zero anywhere in the image).
        // Flag-off keeps the historical collapse so the shipped
        // MACAPK_REAL_LOOPER=0 escape hatch stays byte-identical.
        this.textColors = colors;
        if (colors != null) {
            if (android.os.Looper.MACAPK_REAL_LOOPER) updateTextColors();
            else this.textColor = colors.getDefaultColor();
        }
    }
    /** AOSP TextView.updateTextColors: re-resolve the current colour from the
     *  stored list against getDrawableState() and invalidate on change. The `0`
     *  fallback mirrors AOSP verbatim -- on a real device a stateful list that
     *  matches no row paints TRANSPARENT, and we must fail the same way rather
     *  than invent a safer answer, because a colour appearing where the device
     *  shows nothing is the harder bug to find. */
    private void updateTextColors() {
        if (textColors != null) {
            int color = textColors.getColorForState(getDrawableState(), 0);
            if (color != textColor) {
                textColor = color;
                invalidate();
            }
        }
        // AOSP's updateTextColors re-resolves the HINT colour in the same pass
        // (TextView.java:7005-7013) and invalidates only when the hint is what
        // is actually on screen (`if (mText.length() == 0)`), because a hint
        // colour change is invisible while there is text.
        if (hintTextColors != null) {
            int hcol = hintTextColors.getColorForState(getDrawableState(), 0);
            if (hcol != hintColor) {
                hintColor = hcol;
                if (text == null || text.length() == 0) invalidate();
            }
        }
        // AOSP updateTextColors also resolves mLinkTextColor into
        // mTextPaint.linkColor (the value ClickableSpan/URLSpan.updateDrawState
        // paints with), invalidating on change.
        if (mLinkTextColor != null && paint != null) {
            int lcol = mLinkTextColor.getColorForState(getDrawableState(), 0);
            if (lcol != paint.linkColor) {
                paint.linkColor = lcol;
                invalidate();
            }
        }
    }
    /** AOSP TextView.drawableStateChanged: when any stored colour list is
     *  stateful, re-resolve on every state change. The hint list is consulted
     *  for statefulness per AOSP, and since onDraw paints the hint (see the
     *  hint branch there) that re-resolution is now load-bearing rather than
     *  bookkeeping. */
    @Override
    public void drawableStateChanged() {
        super.drawableStateChanged();
        // AOSP TextView.drawableStateChanged (TextView.java:7013-7020, master):
        //     if (mDrawables != null) {
        //         final int[] state = getDrawableState();
        //         for (Drawable dr : mDrawables.mShowing) {
        //             if (dr != null && dr.isStateful() && dr.setState(state)) {
        //                 invalidateDrawable(dr);
        //             }
        //         }
        //     }
        // Missing here, so a STATEFUL compound drawable never left its default
        // state: a CheckedTextView row's drawableStart indicator (AOSP's
        // select_dialog_multichoice/singlechoice_material) could not show
        // state_checked, nor any drawableStart <selector> show pressed/focused.
        macapkForwardCompoundState(mDrawableLeft);
        macapkForwardCompoundState(mDrawableTop);
        macapkForwardCompoundState(mDrawableRight);
        macapkForwardCompoundState(mDrawableBottom);
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return;
        if ((textColors != null && textColors.isStateful())
                || (hintTextColors != null && hintTextColors.isStateful())) {
            updateTextColors();
        }
    }
    private void macapkForwardCompoundState(android.graphics.drawable.Drawable dr) {
        if (dr != null && dr.isStateful() && dr.setState(getDrawableState())) {
            invalidateDrawable(dr);
        }
    }
    public int getCurrentTextColor() { return textColor; }
    public android.content.res.ColorStateList getTextColors() { return textColors; }
    // AOSP getPaint() returns TextPaint, not Paint -- the descriptor
    // is part of the linkage; app bytecode compiled against
    // ()Landroid/text/TextPaint; NoSuchMethodErrors on a Paint-typed
    // version (gated Calculator's onStart). TextPaint extends Paint,
    // so internal Paint-typed callers are unaffected.
    private android.text.TextPaint paint;
    /** AOSP setTextSize writes the size into mTextPaint at once (setRawTextSize),
     *  so getPaint().getTextSize() always answers the view's size. Ours kept the
     *  size in textSizePx and copied it into the paint only when measuring or
     *  drawing, so code that reads the paint in between got the Paint default
     *  (12px): WhatsApp sizes its emoji images from getPaint().getTextSize() when
     *  the text is set, and a reaction's heart came out 13px instead of 32px. */
    private void macapkSyncPaintTextSize() {
        if (paint != null) paint.setTextSize(textSizePx);
    }
    public android.text.TextPaint getPaint() {
        if (paint == null) {
            paint = new android.text.TextPaint();
            paint.setTextSize(textSizePx);
            // A link colour set before the paint existed (ctor theme default,
            // an early setLinkTextColor) has to land on it too.
            if (mLinkTextColor != null) {
                paint.linkColor = mLinkTextColor.getColorForState(getDrawableState(), 0);
            }
        }
        return paint;
    }
    // Compound drawables (android:drawableStart/Top/End/Bottom or
    // setCompoundDrawables*). v1.0.405: stored + measured + drawn so a
    // leading/trailing icon renders. ActionMenuItemView (the toolbar's
    // "share"/action items) sets its icon via setCompoundDrawables(icon,
    // null,null,null) -- without this the icon was dropped, AND the
    // empty-text item measured to width 0, which our View.measure
    // `measuredWidth==0->specWidth` fallback then inflated to the full
    // toolbar width (pushing the overflow button off). Including the
    // drawable width in getCompoundPaddingLeft makes onMeasure size the
    // item to the icon (non-zero), so the fallback no longer fires and
    // the ActionMenuView has room for the overflow.
    // NOTE: we add ONLY the drawable's contribution, NOT getPadding*(), to
    // stay consistent with the framework's existing text layout (which
    // ignores view padding -- these used to return a flat 0); honoring
    // padding here would shift every TextView in every app.
    private android.graphics.drawable.Drawable mDrawableLeft, mDrawableTop,
        mDrawableRight, mDrawableBottom;
    private int compoundDrawablePadding = 0;
    private static int macapkDrawableW(android.graphics.drawable.Drawable d) {
        if (d == null) return 0;
        int w = d.getBounds().width();
        if (w <= 0) w = Math.max(0, d.getIntrinsicWidth());
        return w;
    }
    private static int macapkDrawableH(android.graphics.drawable.Drawable d) {
        if (d == null) return 0;
        int h = d.getBounds().height();
        if (h <= 0) h = Math.max(0, d.getIntrinsicHeight());
        return h;
    }
    // lane layout A, 2026-08-02: these must INCLUDE the view's own padding.
    // AOSP (android13-release TextView.java:2758-2765):
    //   public int getCompoundPaddingLeft() {
    //       final Drawables dr = mDrawables;
    //       if (dr == null || dr.mShowing[Drawables.LEFT] == null) {
    //           return mPaddingLeft;
    //       } else {
    //           return mPaddingLeft + dr.mDrawablePadding + dr.mDrawableSizeLeft;
    //       }
    //   }
    // Ours returned 0 when there was no compound drawable, and omitted the
    // padding even when there was one -- and since BOTH onDraw and onMeasure
    // route their content box through these four methods, `android:padding*`
    // on any TextView was silently ignored fleet-wide. Measured: AnkiDroid's
    // subtitle (paddingHorizontal=30dp) spanned x=8..798 instead of wrapping
    // inside 60px insets; DeskClock's section headers (paddingVertical=20dp)
    // gave a 24px divider->title gap against the emulator's 67px, compressing
    // the whole page. See docs/canvas-ab-analyst-report-a_2026-08-02.md item 2.
    public int getCompoundPaddingLeft() {
        return getPaddingLeft() + (mDrawableLeft != null
            ? macapkDrawableW(mDrawableLeft) + compoundDrawablePadding : 0);
    }
    public int getCompoundPaddingRight() {
        return getPaddingRight() + (mDrawableRight != null
            ? macapkDrawableW(mDrawableRight) + compoundDrawablePadding : 0);
    }
    public int getCompoundPaddingTop() {
        return getPaddingTop() + (mDrawableTop != null
            ? macapkDrawableH(mDrawableTop) + compoundDrawablePadding : 0);
    }
    public int getCompoundPaddingBottom() {
        return getPaddingBottom() + (mDrawableBottom != null
            ? macapkDrawableH(mDrawableBottom) + compoundDrawablePadding : 0);
    }
    public void setCompoundDrawablesWithIntrinsicBounds(int l, int t, int r, int b) {
        android.content.res.Resources res = null;
        try { res = getResources(); } catch (Throwable ignored) {}
        setCompoundDrawablesWithIntrinsicBounds(
            macapkDrawableFromRes(res, l), macapkDrawableFromRes(res, t),
            macapkDrawableFromRes(res, r), macapkDrawableFromRes(res, b));
    }
    private static android.graphics.drawable.Drawable macapkDrawableFromRes(
            android.content.res.Resources res, int id) {
        if (res == null || id == 0) return null;
        try { return res.getDrawable(id); } catch (Throwable t) { return null; }
    }
    /** Paint any set compound drawables (leading/trailing/top/bottom icons).
     *
     *  AOSP positions each one by TRANSLATING THE CANVAS and then letting the
     *  drawable paint at its OWN bounds; it never touches the bounds at draw
     *  time (android16-release TextView.java:9295-9335, the LEFT arm quoted):
     *
     *      int vspace = bottom - top - compoundPaddingBottom - compoundPaddingTop;
     *      int hspace = right - left - compoundPaddingRight - compoundPaddingLeft;
     *      if (dr.mShowing[Drawables.LEFT] != null) {
     *          canvas.save();
     *          canvas.translate(scrollX + mPaddingLeft + leftOffset,
     *                  scrollY + compoundPaddingTop + (vspace - dr.mDrawableHeightLeft) / 2);
     *          dr.mShowing[Drawables.LEFT].draw(canvas);
     *          canvas.restore();
     *      }
     *
     *  ⚠ THIS IS THE FIX FOR "the icon is drawn in the wrong place", and it is a
     *  FLEET-WIDE defect, not an app quirk. The previous body positioned the icon
     *  by REWRITING its bounds to `(0, (h-dh)/2, dw, ...)`. Two things were wrong
     *  with that, and each one alone misplaces the icon:
     *
     *  1. x=0 is not the icon's home. AOSP puts the LEFT drawable at
     *     `mPaddingLeft` and the RIGHT one at `width - mPaddingRight - size`;
     *     TOP/BOTTOM sit at mPaddingTop / height-mPaddingBottom and centre
     *     across `hspace`, which is the padded content box, not the whole view.
     *     Our own getCompoundPadding*() have honoured the view's padding since
     *     the 2026-08-02 layout pass, so the TEXT was laid out inside the
     *     padding while its icon was not -- the two halves of the same widget
     *     disagreed.
     *  2. Overwriting the bounds DESTROYS a caller-chosen origin.
     *     `setCompoundDrawables` (AOSP and ours) only READS the drawable's
     *     bounds; the caller owns them. com.google.android.material.button
     *     .MaterialButton is built on exactly that contract: updateIconPosition()
     *     computes `iconLeft = (buttonWidth - textWidth - paddingEnd - iconSize
     *     - iconPadding - paddingStart) / 2` and publishes it purely as
     *     `icon.setBounds(iconLeft, iconTop, iconLeft + w, iconTop + h)` -- that
     *     offset is the ONLY thing that centres an icon-only MaterialButton's
     *     glyph, and we erased it every frame.
     *
     *  MEASURED, both apps the owner named, same defect:
     *   * Aegis 3.4.2 intro (`activity_intro.xml`): `btnNext`/`btnPrevious` are
     *     65dp square Widget.Material3.Button.IconButton.Filled.Tonal buttons
     *     with `app:icon` and no text, laid out at [638,1502-768,1632] (130px,
     *     pad=20). The arrow ink measured x 648..687 / y 1565..1594 -- 35px left
     *     of centre AND 35px above it, spilling over the top-left of the circle.
     *     35 == mPaddingLeft(20) + MaterialButton's iconLeft(15), i.e. exactly
     *     the two offsets this method threw away.
     *   * F-Droid 1.23.2 app details: the `action_share` item
     *     (res/menu/details2.xml, showAsAction="ifRoom") is an androidx
     *     ActionMenuItemView, a TextView subclass with empty text whose icon
     *     arrives through `setCompoundDrawables(icon, null, null, null)` after
     *     `icon.setBounds(0, 0, w, h)`. Its `Widget.AppCompat.ActionButton`
     *     padding is likewise dropped, which is the "buttons on an app's detail
     *     page are slightly MISALIGNED -- the share button and its neighbours"
     *     the owner reported on 2026-08-09
     *     (docs/owner-run-findings_2026-08-09.md item 2.2).
     *
     *  Deliberate deviations from the AOSP text above, both local conventions:
     *   * no `scrollX`/`scrollY` term. AOSP's View.draw pre-translates a view's
     *     canvas by its own scroll and TextView adds it back; this tree does not
     *     (ViewGroup.dispatchDraw applies the PARENT's scroll, and TextView's own
     *     text draw uses no scroll term either). Adding it here would move every
     *     icon in a scrolled TextView by the wrong amount.
     *   * sizes come from macapkDrawableW/H (bounds, else intrinsic) rather than
     *     AOSP's cached mDrawableSizeLeft / mDrawableHeightLeft (and the three
     *     sibling pairs), which we do not keep.
     *
     *  ⚠ KNOWN RESIDUAL, and it is NOT in this file: android.graphics.drawable
     *  .VectorDrawable.draw() ignores `getBounds().left/top` outright ("Bounds
     *  left/top are NOT used here -- callers (ImageView) already translate",
     *  VectorDrawable.java:649-651). So for a vector icon this method now lands
     *  the drawable's BOX correctly, but a caller-set bounds ORIGIN inside that
     *  box (MaterialButton's iconLeft) is still discarded by the drawable
     *  itself. ImageView and CompoundButton are the only other callers and both
     *  pass an origin of 0 / rely on it, so honouring left/top there is safe --
     *  but that file belongs to another lane. */
    private void macapkDrawCompoundDrawables(android.graphics.Canvas canvas) {
        if (mDrawableLeft == null && mDrawableTop == null
            && mDrawableRight == null && mDrawableBottom == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final int cpLeft = getCompoundPaddingLeft(), cpTop = getCompoundPaddingTop();
        final int cpRight = getCompoundPaddingRight(), cpBottom = getCompoundPaddingBottom();
        final int vspace = h - cpTop - cpBottom;
        final int hspace = w - cpLeft - cpRight;
        android.graphics.drawable.Drawable d;
        if ((d = mDrawableLeft) != null) {
            macapkDrawOneCompound(canvas, d, getPaddingLeft(),
                cpTop + (vspace - macapkDrawableH(d)) / 2);
        }
        if ((d = mDrawableRight) != null) {
            macapkDrawOneCompound(canvas, d, w - getPaddingRight() - macapkDrawableW(d),
                cpTop + (vspace - macapkDrawableH(d)) / 2);
        }
        if ((d = mDrawableTop) != null) {
            macapkDrawOneCompound(canvas, d, cpLeft + (hspace - macapkDrawableW(d)) / 2,
                getPaddingTop());
        }
        if ((d = mDrawableBottom) != null) {
            macapkDrawOneCompound(canvas, d, cpLeft + (hspace - macapkDrawableW(d)) / 2,
                h - getPaddingBottom() - macapkDrawableH(d));
        }
    }

    /** One `canvas.save() / translate / draw / restore` triple, AOSP's shape.
     *  A drawable that throws must not abort the rest of the view's draw (the
     *  ViewGroup.dispatchDraw lesson from v1.0.404: one throwing child used to
     *  take the whole window down), and the canvas must be restored either way.
     *
     *  ⚠ The `sv > 0` guard is not paranoia, it is a HANG guard, and the bug it
     *  routes around is in android.graphics.Canvas, not here. On a canvas with
     *  no backing Graphics2D (`g == null` -- an off-screen/no-op Canvas, e.g.
     *  `new Canvas()`), `save()` skips the push and returns txStack.size() == 0,
     *  while `restore()` is a no-op on an empty stack -- so
     *  `restoreToCount(0)`'s `while (txStack.size() >= saveCount) restore();`
     *  spins FOREVER (Canvas.java:258-324). ImageView's identical
     *  save/restoreToCount idiom has the same exposure. Reported to the lane
     *  that owns android/graphics; until it is fixed, fall back to the paired
     *  translate that CompoundButton.onDraw already uses, which is exactly
     *  equivalent for a pure translation and cannot spin. */
    private static void macapkDrawOneCompound(android.graphics.Canvas canvas,
            android.graphics.drawable.Drawable d, int dx, int dy) {
        int sv = canvas.save();
        try {
            canvas.translate(dx, dy);
            d.draw(canvas);
        } catch (Throwable ignored) {
        } finally {
            if (sv > 0) canvas.restoreToCount(sv);
            else        canvas.translate(-dx, -dy);
        }
    }

    // Text shadow (drop-shadow effect). Frozen Bubble's HomeScreen
    // .addHomeButtons calls setShadowLayer for each button. We store the
    // shadow and apply it to the draw Paint in onDraw (Canvas.drawText
    // honors Paint.setShadowLayer as a drop shadow). Previously a no-op
    // stub that silently swallowed the call -- AOSP TextView actually
    // paints the shadow behind the glyphs.
    private float shadowRadius, shadowDx, shadowDy;
    private int   shadowColor;
    public void setShadowLayer(float radius, float dx, float dy, int color) {
        this.shadowRadius = radius; this.shadowDx = dx;
        this.shadowDy = dy; this.shadowColor = color;
        invalidate();
    }
    public float getShadowRadius() { return shadowRadius; }
    public float getShadowDx()     { return shadowDx; }
    public float getShadowDy()     { return shadowDy; }
    public int   getShadowColor()  { return shadowColor; }
    // setTextScaleX / getTextScaleX (font width compression).
    private float textScaleX = 1f;
    public void  setTextScaleX(float s) { this.textScaleX = s; }
    public float getTextScaleX()        { return textScaleX; }
    // setIncludeFontPadding (ascent/descent padding). AOSP stores the flag
    // and rebuilds the Layout; it is the includePad argument our measure /
    // draw would hand to StaticLayout. Storing it (was a discarded no-op with
    // a hardcoded `true` getter) at minimum makes the getter honest for the
    // androidx code that reads it back.
    // frameworks/base core/java/android/widget/TextView.java:11245.
    private boolean mIncludePad = true;
    public void setIncludeFontPadding(boolean include) {
        if (mIncludePad != include) {
            mIncludePad = include;
            requestLayout();
            invalidate();
        }
    }
    public boolean getIncludeFontPadding() { return mIncludePad; }

    /**
     * The effective per-line ascent and descent, honouring `includeFontPadding`.
     *
     * ⚠ v1.0.937. The flag was STORED AND NEVER READ -- the comment above said
     * so, and called making the getter honest a win in itself -- so every
     * measurement in this class used `fm.ascent`/`fm.descent` regardless. But
     * `includeFontPadding` DEFAULTS TO TRUE, and under it AOSP lays every line
     * out with `fm.top`/`fm.bottom` instead (Layout.java getLineTop/getLineBottom,
     * StaticLayout's `includePad` argument, BoringLayout.make). The result was a
     * systematic height error on EVERY TextView in the fleet:
     *
     *     text size   reference h   ours       reference baseline   ours
     *       12px          17         15               13             12
     *       20px          28         24               22             19
     *       40px          54         48               43             38
     *
     * and the proof that it is THIS flag rather than a font-metrics error is
     * the pair of probe rows either side of it: with padding OFF the reference
     * and we agree exactly (h=24 at 20px), with it ON the reference moves to 28
     * and we did not move at all.
     *
     * Returns {ascent, descent} in AOSP's sign convention (ascent negative).
     */
    /**
     * ⚠ v1.0.938: takes `FontMetricsInt`, NOT the float `FontMetrics`.
     *
     * AOSP lays text out from the ROUNDED metrics: `StaticLayout.generate` and
     * `BoringLayout` both declare `Paint.FontMetricsInt fm` and fill it with
     * `getFontMetricsInt(fm)`, and `Layout.getLineTop`/`getLineBottom` read
     * those ints. Only the float `getFontMetrics()` API reports raw values, and
     * it is not what a layout consumes.
     *
     * That distinction became load-bearing the moment `getFontMetrics()` started
     * returning the true floats (same version): at 12px the raw extents are
     * -12.6738/3.2520, so `ceil(bottom - top)` is 16, while the ints -13/4 give
     * 17 -- and 17 is the reference. Feeding TextView the floats regressed 11
     * LAYT rows by exactly 1px each, every one of them a height. The probe
     * caught it; the checkpoint numbers in this class's own comment (17/28/54)
     * are the guard.
     */
    private int[] macapkLineExtents(android.graphics.Paint.FontMetricsInt fm) {
        if (fm == null) return new int[] { 0, 0 };
        return mIncludePad ? new int[] { fm.top, fm.bottom }
                           : new int[] { fm.ascent, fm.descent };
    }
    /** Baseline-to-baseline height of one line, under the current padding flag. */
    private float macapkLineHeight(android.graphics.Paint.FontMetricsInt fm) {
        int[] e = macapkLineExtents(fm);
        return e[1] - e[0];
    }
    /** Distance from a line's TOP to its baseline, under the current flag. */
    private float macapkLineAscent(android.graphics.Paint.FontMetricsInt fm) {
        return -macapkLineExtents(fm)[0];
    }

    /** AOSP TextView.setLineSpacing(add, mult) (frameworks/base core/java/
     *  android/widget/TextView.java:6476): stores mSpacingAdd/mSpacingMult,
     *  which Layout turns into a baseline-to-baseline advance of
     *  `(descent - ascent) * mult + add` for every line EXCEPT the last (see
     *  the javadoc: "the value by which each line height other than the last
     *  line will be multiplied by"). Both arguments used to be discarded, so
     *  every Material typography spec that expresses line-height through
     *  lineSpacingExtra / lineSpacingMultiplier rendered with the raw font
     *  leading. The defaults (0f / 1f) make macapkLineTops() (below) return
     *  the same geometry as no spacing call at all, i.e. byte-identical
     *  layout for anyone who never calls this. */
    private float mSpacingAdd = 0f;
    private float mSpacingMult = 1.0f;
    public void setLineSpacing(float add, float mult) {
        if (mSpacingAdd != add || mSpacingMult != mult) {
            mSpacingAdd = add;
            mSpacingMult = mult;
            requestLayout();
            invalidate();
        }
    }
    public float getLineSpacingExtra()      { return mSpacingAdd; }
    public float getLineSpacingMultiplier() { return mSpacingMult; }

    /** AOSP StaticLayout.Builder's useLineSpacingFromFallbacks, default ON
     *  for any app targeting API 28+ (android13-release core/java/android/
     *  widget/TextView.java:4658-4667 -- STATICLAYOUT_FALLBACK_LINESPACING is
     *  `@EnabledSince(targetSdkVersion = P)`; TextView.isFallbackLineSpacing's
     *  own javadoc: "whether fallback line spacing is enabled, true by
     *  default"). A line whose glyphs need a font OUTSIDE the primary
     *  typeface's cmap widens THAT line's ascent/descent to the fallback
     *  font's own -- StaticLayout.Builder.setUseLineSpacingFromFallbacks's
     *  javadoc: "needed to avoid text from consecutive lines running into
     *  each other". Gated on targetSdkVersion the same way LinearLayout
     *  gates sRemeasureWeightedChildren -- same static-latch shape, not
     *  shared code, because TextView has no single constructor entry point
     *  the way LinearLayout does (Button/EditText/CheckBox/... each chain
     *  super(...) directly rather than a common init). Default true: an
     *  unreadable ApplicationInfo degrades to the modern answer, matching
     *  every app in the current corpus (LinearLayout's own comment makes the
     *  same call for the same reason). */
    private static boolean sFallbackLineSpacingDone = false;
    private static boolean sUseFallbackLineSpacing = true;
    private boolean macapkUseFallbackLineSpacing() {
        if (!sFallbackLineSpacingDone) {
            try {
                android.content.Context c = getContext();
                android.content.pm.ApplicationInfo ai = (c != null) ? c.getApplicationInfo() : null;
                // A REAL 0 (no <uses-sdk>) is below P and latches false, as on
                // AOSP; only an unresolved read keeps the modern default
                // (android.app.Application.macapkTargetSdkKnown, MACAPK_TARGETSDK_ZERO).
                if (ai != null && android.app.Application.macapkTargetSdkKnown(ai.targetSdkVersion)) {
                    sUseFallbackLineSpacing = ai.targetSdkVersion >= android.os.Build.VERSION_CODES.P;
                    sFallbackLineSpacingDone = true;
                }
            } catch (Throwable ignored) { }
        }
        return sUseFallbackLineSpacing;
    }

    /** {ascent, descent} for an arbitrary SIZED fallback face, AOSP-signed
     *  (ascent negative) and rounded exactly as Paint's own ascentInt()/
     *  descentInt() round the primary typeface -- Math.round of the float
     *  java.awt.font.LineMetrics, NOT java.awt.FontMetrics's coarser int API
     *  (see Paint.java's ascent()/descent() header for why: FontMetrics
     *  reports the CEIL of the true value). Cached: the fallback chain is a
     *  handful of bundled faces (Roboto, NotoSansSymbols x2, NotoSansHebrew
     *  x2 -- MacApkFont.BUNDLED_FALLBACK_RESOURCES) at whatever sizes the
     *  app's TextViews use, so this cannot grow unbounded. */
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
     *  cover any FALLBACK font `line`'s codepoints need beyond the paint's
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
     *  Per-codepoint coverage reuses MacApkFont.fallbackBaseFor, the same
     *  chain Paint's own width measurement already walks (macapkMeasureRuns),
     *  so "does this line need a fallback face" can never disagree between
     *  how wide the line measured and how tall it lays out.
     *
     *  Returns `base` UNCHANGED (same reference) when the line is pure-
     *  primary, the overwhelmingly common case -- callers must not mutate
     *  the returned array in place for that reason.
     *
     *  MEASURED (docs/textview-multiline-stride-first-last-padding_2026-08-13.md
     *  "NEXT"): deskclock's first_launch_main_feature_message bullets every
     *  one of 12 lines with U+25BA, which bundled Roboto's cmap lacks
     *  (MacApkFont.java's own BUNDLED_FALLBACK_RESOURCES comment already
     *  names this exact character/victim). At 32px (16sp x density 2.0) the
     *  NotoSansSymbols-Regular-Subsetted fallback's ascent/descent
     *  (round(-34.2031)=-34, round(9.3750)=9) are taller than Roboto's own
     *  (-30, 8) -- 5px of extra height this restores on every bulleted line.
     *  Each visible gap in that block spans one bulleted + one blank
     *  line-advance, so 5px on the bulleted line alone averages to ~2.5px
     *  per line-advance over the pair -- the residual macapkLineTops' own
     *  v1.0.962 header left as "NEXT" work. */
    private int[] macapkFallbackVertical(String line, android.graphics.Paint paint, int[] base) {
        if (!macapkUseFallbackLineSpacing() || line == null || line.isEmpty() || paint == null) {
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

    /** Line k's own {ascent,descent,top,bottom}, widened per
     *  macapkFallbackVertical when `lines` carries line k's actual text.
     *  `lines`/`paint` may be null (the viewH-bounding search in onDraw,
     *  which does not yet know how a candidate line count wraps into text)
     *  -- callers that pass null get the primary-only fm back, exactly the
     *  pre-fallback-widening behavior. */
    private int[] macapkLineVertical(android.graphics.Paint.FontMetricsInt fm, int k,
            java.util.List<String> lines, android.graphics.Paint paint) {
        int[] base = { fm.ascent, fm.descent, fm.top, fm.bottom };
        if (lines == null || paint == null || k < 0 || k >= lines.size()) return base;
        return macapkFallbackVertical(lines.get(k), paint, base);
    }

    /** ⚠ v1.0.962. AOSP StaticLayout.out() (android13-release core/java/
     *  android/text/StaticLayout.java:1047-1067) widens ascent/descent to
     *  fm.top/fm.bottom ONLY for the very first and very last line of the
     *  WHOLE layout, and applies mSpacingAdd/mSpacingMult to every line
     *  EXCEPT the last:
     *
     *      if (firstLine) { if (trackPad) mTopPadding = top - above;
     *                       if (includePad) above = top; }
     *      if (lastLine)  { if (trackPad) mBottomPadding = bottom - below;
     *                       if (includePad) below = bottom; }
     *      if (needMultiply && (addLastLineLineSpacing || !lastLine)) {
     *          double ex = (below - above) * (spacingmult - 1) + spacingadd;
     *          extra = (ex >= 0) ? (int)(ex + 0.5) : -(int)(-ex + 0.5);
     *      }
     *      v += (below - above) + extra;
     *
     *  Our Layout.java's computeLineGeometry() already implements this for a
     *  real StaticLayout. This class's onMeasure/onDraw wrap path is a
     *  SEPARATE, hand-rolled implementation (Layout/StaticLayout are never
     *  consulted by onMeasure/onDraw -- see the class header) that instead
     *  used macapkLineAdvance(macapkLineHeight(fm)), i.e. the FULL padded
     *  height, as the stride between EVERY line -- so an n-line wrapped- or
     *  \n-joined block (a bulleted description, a multi-line label) grew by
     *  (n - 1) * ((fm.bottom - fm.top) - (fm.descent - fm.ascent)) more than
     *  AOSP: e.g. +4px (2dp) per extra line at 20px Roboto (28 padded vs 24
     *  raw). A one-line block is exactly right either way: its single line
     *  is simultaneously first AND last, so both widenings apply exactly
     *  once each, which IS macapkLineHeight(fm) -- macapkLineTops(fm, 1, lines,
     *  paint)[1] equals it (both widen identically per macapkFallbackVertical
     *  when the one line needs a fallback face; both stay unchanged when it
     *  does not).
     *
     *  Returns AOSP's own `lineTops` shape (Layout.java's private field,
     *  StaticLayout.java:1044): result[k] is line k's own top as a Y offset
     *  from the block's top edge; result[n] is the block's total height. */
    private float[] macapkLineTops(android.graphics.Paint.FontMetricsInt fm, int n,
            java.util.List<String> lines, android.graphics.Paint paint) {
        float[] tops = new float[Math.max(n, 0) + 1];
        if (n <= 0 || fm == null) return tops;
        final boolean needMultiply = (mSpacingMult != 1f || mSpacingAdd != 0f);
        float v = 0f;
        for (int k = 0; k < n; k++) {
            boolean lastLine = (k == n - 1);
            int[] m = macapkLineVertical(fm, k, lines, paint);
            float above = (k == 0) ? (mIncludePad ? m[2] : m[0]) : m[0];
            float below = lastLine ? (mIncludePad ? m[3] : m[1]) : m[1];
            float extra = 0f;
            if (needMultiply && !lastLine) {
                // AOSP EXTRA_ROUNDING = 0.5 (StaticLayout.java:1484), truncated
                // toward zero on each side (StaticLayout.java:1069-1075).
                double ex = (below - above) * (mSpacingMult - 1) + mSpacingAdd;
                extra = (ex >= 0) ? (int) (ex + 0.5) : -(int) (-ex + 0.5);
            }
            tops[k] = v;
            v += (below - above) + extra;
        }
        tops[n] = v;
        return tops;
    }
    /** Line k's own "above" -- ascent, widened to fm.top ONLY for line 0
     *  under includeFontPadding (StaticLayout.java:1047-1054), and further
     *  widened per macapkFallbackVertical when `lines` carries line k's own
     *  text. Negative, like Paint.ascent(). */
    private float macapkLineTopAbove(android.graphics.Paint.FontMetricsInt fm, int k,
            java.util.List<String> lines, android.graphics.Paint paint) {
        int[] m = macapkLineVertical(fm, k, lines, paint);
        return (k == 0) ? (mIncludePad ? m[2] : m[0]) : m[0];
    }
    /** Total height of an n-line block: macapkLineTops(fm, n, lines, paint)[n].
     *  Named for the common case (onMeasure) where only the total is needed. */
    private float macapkBlockHeight(android.graphics.Paint.FontMetricsInt fm, int n,
            java.util.List<String> lines, android.graphics.Paint paint) {
        return macapkLineTops(fm, n, lines, paint)[Math.max(n, 0)];
    }

    /** AOSP TextView.append(CharSequence, int, int) (frameworks/base core/
     *  java/android/widget/TextView.java:6955): upgrades the display buffer
     *  to EDITABLE and inserts into it, so the spans already on the buffer
     *  survive and the TextWatchers see an INCREMENTAL (start=oldLen,
     *  before=0, count=added) delta. The old body was
     *  setText(text.toString() + s), which flattened the buffer to a bare
     *  String (dropping every style span and the selection markers) and
     *  reported a full-buffer replace instead. */
    public void append(CharSequence s) { append(s, 0, s == null ? 0 : s.length()); }
    public void append(CharSequence s, int start, int end) {
        if (s == null) return;
        // getEditableText() caches per text identity, so re-pointing `text` at
        // the buffer keeps getText() and getEditableText() the same object
        // across repeated appends -- AOSP's single-Editable model.
        android.text.Editable buf = getEditableText();
        int oldLen = buf.length();
        // AOSP propagates the TextView's InputFilters onto the Editable, so an
        // append is filtered just like a keystroke (dest = current buffer,
        // dstart == dend == the insertion point at the end).
        CharSequence add = s;
        int addStart = start, addEnd = end;
        if (mFilters != null && mFilters.length > 0) {
            try {
                add = macapkFilterInsertion(s.subSequence(start, end), oldLen, oldLen);
            } catch (Throwable ignored) { add = null; }
            addStart = 0;
            addEnd = (add == null) ? 0 : add.length();
        }
        int added = Math.max(0, addEnd - addStart);
        boolean fire = textWatchers != null && !textWatchers.isEmpty() && !firingWatchers;
        if (fire) {
            firingWatchers = true;
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.beforeTextChanged(buf, oldLen, 0, added); } catch (Throwable ignored) {}
        }
        if (added > 0) { try { buf.append(add, addStart, addEnd); } catch (Throwable ignored) {} }
        // append() mutates the Editable IN PLACE, so `text` may already BE
        // `buf` -- but mTransformed is a snapshot taken at the last setText and
        // does not observe in-place edits (PasswordCharSequence wraps the
        // source, but a plain `mTransformed = text` alias would). Re-run the
        // transformation unconditionally.
        macapkSetTextInternal(buf);
        this.mCachedEditableSrc = buf;
        if (fire) {
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.onTextChanged(buf, oldLen, 0, added); } catch (Throwable ignored) {}
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.afterTextChanged(buf); } catch (Throwable ignored) {}
            firingWatchers = false;
        }
        requestLayout();
        invalidate();
    }
    // AOSP TextView.setError(CharSequence)/(CharSequence,Drawable)/getError()
    // (frameworks/base core/java/android/widget/TextView.java ~lines 8440-8466):
    // the single-arg setter was `{}` with no backing field, and the
    // two-arg (CharSequence, Drawable) overload was ABSENT entirely (a guest
    // calling it directly would get NoSuchMethodError, not just a wrong
    // value). AOSP's real setError resolves a system error-indicator
    // drawable, positions it as the right-hand compound drawable, and shows
    // a popup while the view has focus, all via an internal Editor this tree
    // has no equivalent of -- scoped to the message accessor round-trip only,
    // which is what a form-validation caller checking `getError() != null`
    // (or re-reading a previously-set message) actually observes.
    private CharSequence mError;
    public void setError(CharSequence error) { mError = error; }
    public void setError(CharSequence error, android.graphics.drawable.Drawable icon) { mError = error; }
    public CharSequence getError() { return mError; }
    /** AOSP :5911 -- `mLinkTextColor = ColorStateList.valueOf(color)`, i.e. the
     *  int form funnels into the SAME ColorStateList field the list form uses,
     *  which is what makes getLinkTextColors() answer after either call. */
    public void setLinkTextColor(int color) {
        mLinkTextColor = android.content.res.ColorStateList.valueOf(color);
        updateTextColors();
    }
    /** AOSP TextView.setHighlightColor(int)/:5672 getHighlightColor(). It used
     *  to discard the value, so the (previously missing) getter had nothing to
     *  read. The colour is the selection highlight, which this port does not
     *  paint, so this is state only. */
    public void setHighlightColor(int color) { mHighlightColor = color; }

    // Size/layout setters on TextView per AOSP. The explicit*/lines/maxLines/
    // minLines fields are the PRE-GATE state (MACAPK_TEXT_LINES_AOSP=0 reads
    // them); with the gate on, measure and draw read the AOSP limit pair
    // declared below.
    private int explicitHeight = -1, explicitWidth = -1;
    private int explicitMaxHeight = Integer.MAX_VALUE, explicitMaxWidth = Integer.MAX_VALUE;
    private int explicitMinHeight, explicitMinWidth;
    private int lines = -1, maxLines = Integer.MAX_VALUE, minLines = 0;
    // AOSP: a px-expressed width bound flips the width mode back off EMS, so
    // a later setWidth/setMaxWidth/setMinWidth overrides an earlier setEms
    // (and getMaxEms/getMinEms then report -1).
    // AOSP tracks whether the HEIGHT maximum was last expressed in PIXELS or in
    // LINES (mMaxMode), because getMaxHeight() answers -1 in the LINES case --
    // there is no pixel answer to give. mMaxHeightInPixels is that flag; AOSP's
    // field default is LINES, i.e. false. It still answers getMaxHeight() with
    // the gate off; with it on, getMaxHeight() reads mMaxModeLines/mMaximum.
    //
    // ---- AOSP height/width LIMITS (MACAPK_TEXT_LINES_AOSP, default ON) ----
    // The fields above were written by these setters and read by NOTHING in
    // measure or draw, so `android:lines="2"` drew three lines (F-Droid's
    // large-card summaries, fleet A/B 2026-09-25) and setWidth/setHeight were
    // measurement no-ops (Frozen Bubble sizes its menu buttons with
    // `setWidth((int) (getTextSize() * 9))` and they shrink-wrapped instead).
    // AOSP keeps ONE maximum and ONE minimum per axis plus a unit MODE, and the
    // setters overwrite each other through them (android14-release
    // TextView.java:887-905, setters :5758-6155):
    //     setMinLines(n)   { mMinimum = n;          mMinMode = LINES;  }
    //     setMinHeight(px) { mMinimum = px;         mMinMode = PIXELS; }
    //     setMaxLines(n)   { mMaximum = n;          mMaxMode = LINES;  }
    //     setMaxHeight(px) { mMaximum = px;         mMaxMode = PIXELS; }
    //     setLines(n)      { mMaximum = mMinimum = n;  both modes LINES;  }
    //     setHeight(px)    { mMaximum = mMinimum = px; both modes PIXELS; }
    //     setMinWidth(px)  { mMinWidth = px; mMinWidthMode = PIXELS; }  (and Max/setWidth alike)
    // Defaults: mMaximum = Integer.MAX_VALUE, mMinimum = 0, both modes LINES;
    // mMaxWidth = Integer.MAX_VALUE, mMinWidth = 0, both width modes PIXELS.
    // getDesiredHeight (:11153-11199) and onMeasure (:10937-10955) are the
    // only readers; see macapkOnMeasureAosp. The EMS width mode is still the
    // existing mMaxEms/mMinEms pair (>= 0 means EMS), which setWidth/
    // setMaxWidth/setMinWidth already clear.
    //
    // BISECT GATE: MACAPK_TEXT_LINES_AOSP=0 restores the old measure/draw/
    // Layout behaviour for this whole change set (these limits, the
    // ellipsisMayBeApplied cut in Layout, the ellipsis-only-when-asked draw
    // and the hanging trailing space in the line breaker). Text layout touches
    // every app, so the switch exists to A/B any fleet movement in one run.
    static final boolean MACAPK_TEXT_LINES_AOSP =
        !"0".equals(System.getenv("MACAPK_TEXT_LINES_AOSP"));
    private int mMaximum = Integer.MAX_VALUE, mMinimum = 0;
    private boolean mMaxModeLines = true, mMinModeLines = true;
    private int mMaxWidthPx = Integer.MAX_VALUE, mMinWidthPx = 0;
    /** AOSP's setters all end `requestLayout(); invalidate();`. Only when the
     *  value actually moves here, so an app that re-applies the same limit
     *  from inside its own onMeasure/onBind does not schedule a pass per call. */
    private void macapkLimitChanged(boolean changed) {
        if (changed && MACAPK_TEXT_LINES_AOSP) { requestLayout(); invalidate(); }
    }
    private void macapkSetMax(int v, boolean lines) {
        boolean ch = mMaximum != v || mMaxModeLines != lines;
        mMaximum = v; mMaxModeLines = lines;
        macapkLimitChanged(ch);
    }
    private void macapkSetMin(int v, boolean lines) {
        boolean ch = mMinimum != v || mMinModeLines != lines;
        mMinimum = v; mMinModeLines = lines;
        macapkLimitChanged(ch);
    }
    public void setHeight(int h) {
        this.explicitHeight = h; mMaxHeightInPixels = true;
        macapkSetMin(h, false);
        macapkSetMax(h, false);
    }
    public void setWidth(int w) {
        this.explicitWidth  = w; mMaxEms = mMinEms = -1;
        boolean ch = mMaxWidthPx != w || mMinWidthPx != w;
        mMaxWidthPx = mMinWidthPx = w;
        macapkLimitChanged(ch);
    }
    public void setMaxHeight(int h) {
        this.explicitMaxHeight = h; mMaxHeightInPixels = true;
        macapkSetMax(h, false);
    }
    public void setMaxWidth(int w)  {
        this.explicitMaxWidth  = w; mMaxEms = -1;
        boolean ch = mMaxWidthPx != w;
        mMaxWidthPx = w;
        macapkLimitChanged(ch);
    }
    public void setMinHeight(int h) {
        this.explicitMinHeight = h;
        // AOSP's setMinHeight writes mMinimum only; View's own mMinHeight is a
        // separate attribute the View constructor reads from XML. Mirroring
        // into setMinimumHeight made a later setMinLines unable to replace the
        // pixel minimum, which AOSP's single mMinimum slot does.
        if (!MACAPK_TEXT_LINES_AOSP) setMinimumHeight(h);
        macapkSetMin(h, false);
    }
    public void setMinWidth(int w)  {
        this.explicitMinWidth  = w; mMinEms = -1;
        if (!MACAPK_TEXT_LINES_AOSP) setMinimumWidth(w);
        boolean ch = mMinWidthPx != w;
        mMinWidthPx = w;
        macapkLimitChanged(ch);
    }
    public void setLines(int n) {
        this.lines = n; mMaxHeightInPixels = false;
        macapkSetMin(n, true);
        macapkSetMax(n, true);
    }
    public void setMinLines(int n)  { this.minLines = n; macapkSetMin(n, true); }
    /** The maxLines the measure/draw/Layout paths honour: AOSP's
     *  `mMaxMode == LINES ? mMaximum : Integer.MAX_VALUE` (the exact argument
     *  makeSingleLayout hands StaticLayout.Builder.setMaxLines, :10757), with
     *  a non-positive count read as "no limit" the way this file always has.
     *  Gate OFF: the old `maxLines` field, which only setMaxLines and
     *  setSingleLine wrote. */
    private int macapkMaxLines() {
        if (!MACAPK_TEXT_LINES_AOSP) return (maxLines > 0) ? maxLines : Integer.MAX_VALUE;
        return (mMaxModeLines && mMaximum > 0) ? mMaximum : Integer.MAX_VALUE;
    }
    /** AOSP applySingleLine(true) sets setHorizontallyScrolling(true) and
     *  setLines(1) (:11918-11924): ONE unwrapped line, clipped or ellipsized
     *  at the view edge. setInputType only sets the flag (see there), so the
     *  line limit is checked too, keeping those EditTexts on the wrap path
     *  they have always used. */
    private boolean macapkHorizontallyScrolling() {
        return MACAPK_TEXT_LINES_AOSP && macapkSingleLine && macapkMaxLines() == 1;
    }
    /** The ellipsize mode TextView actually hands its layout
     *  (makeNewLayout, :10559-10566):
     *      boolean shouldEllipsize = mEllipsize != null && getKeyListener() == null;
     *      if (mEllipsize == MARQUEE && mMarqueeFadeMode == MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS)
     *          effectiveEllipsize = TruncateAt.END_SMALL;
     *  MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS is set in exactly two places: the
     *  constructor's XML `ellipsize="marquee"` case (:1797-1806) and setText of
     *  a Spanned carrying a TruncateAt.MARQUEE span (:7011-7024), each only
     *  because config_ui_enableFadingMarquee is false on a phone (core/res
     *  values/config.xml:188). The field defaults to MARQUEE_FADE_NORMAL
     *  (:785) and setEllipsize (:12019-12030) never touches it, so a
     *  PROGRAMMATIC setEllipsize(MARQUEE) hands the layout MARQUEE itself,
     *  which no layout ellipsizes (BoringLayout.init treats MARQUEE like null;
     *  StaticLayout's ellipsisMayBeApplied excludes it): the full text, clipped.
     *  mMarqueeShowEllipsis is that fade mode. We never run the marquee
     *  animation, so an XML MARQUEE view stays in its not-selected state. */
    private android.text.TextUtils.TruncateAt macapkEffectiveEllipsize() {
        if (mEllipsize == null || getKeyListener() != null) return null;
        if (mEllipsize == android.text.TextUtils.TruncateAt.MARQUEE)
            return mMarqueeShowEllipsis ? android.text.TextUtils.TruncateAt.END_SMALL
                                        : android.text.TextUtils.TruncateAt.MARQUEE;
        return mEllipsize;
    }
    /** AOSP mMarqueeFadeMode == MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS (see
     *  macapkEffectiveEllipsize). Sticky, as the AOSP field is: nothing but
     *  the marquee start/stop cycle ever moves it back, and we never start one. */
    private boolean mMarqueeShowEllipsis;
    /** The XML-origin half of the MARQUEE fade mode. AOSP decides it in the
     *  constructor from the TypedArray, which includes style= and defStyle
     *  values; our constructor only sees the element's own AttributeSet, so
     *  LayoutInflater's `ellipsize` case (which is fed by both the element and
     *  its style items) calls this before setEllipsize(MARQUEE). Not public
     *  API: the macapk prefix marks it as the inflater's hook. */
    public void macapkSetXmlMarquee() {
        if (!mMarqueeShowEllipsis) {
            mMarqueeShowEllipsis = true;
            macapkLimitChanged(mEllipsize == android.text.TextUtils.TruncateAt.MARQUEE);
        }
    }
    // (setMaxLines is defined further down already; don't redefine)
    private android.text.TextUtils.TruncateAt mEllipsize;
    /** AOSP :12019-12030 relayouts when the mode changes (it caps the hint's
     *  measured height and decides the ellipsis). */
    public void setEllipsize(android.text.TextUtils.TruncateAt where) {
        boolean ch = this.mEllipsize != where;
        this.mEllipsize = where;
        macapkLimitChanged(ch);
    }
    /** AOSP TextView.setAllCaps (android13-release :10560) installs an
     *  `AllCapsTransformationMethod` -- it is a TRANSFORMATION, so it changes
     *  what is measured and drawn but not `getText()`. Ours was a no-op and the
     *  inflater had no case, so Material's textAllCaps button style and every
     *  `android:textAllCaps="true"` were dropped (Mastodon's divider read "or"
     *  where the emulator reads "OR"; NewPipe's dialog buttons were lowercase).
     *  Routing it through the existing mTransformationMethod seam means
     *  macapkDisplayText() -- which onDraw, onMeasure and getLayout all already
     *  share -- picks it up with no other change.
     *  See docs/canvas-ab-analyst-report-b_2026-08-02.md item 11. */
    public void setAllCaps(boolean allCaps) {
        if (allCaps) {
            setTransformationMethod(new android.text.method.AllCapsTransformationMethod());
        } else if (mTransformationMethod instanceof android.text.method.AllCapsTransformationMethod) {
            setTransformationMethod(null);
        }
    }
    public boolean isAllCaps() {
        return mTransformationMethod instanceof android.text.method.AllCapsTransformationMethod;
    }
    /** AOSP TextView.getLineCount(): the number of lines the text wraps to
     *  at the current width -- the FULL count, NOT capped by maxLines
     *  (maxLines limits DRAWING via mMaximumVisibleLineCount, not the
     *  Layout's line count). F-Droid's AppDetails decides "More" button
     *  visibility with `descriptionView.getLineCount() > 5`; the old stub
     *  returned 1 so "More" never showed and a long collapsed description
     *  looked like the whole thing. We wrap the full text the same way
     *  onDraw does. */
    public int  getLineCount() {
        // AOSP's getLineCount IS `mLayout != null ? mLayout.getLineCount() : 0`
        // (android13-release TextView.java:8433) and mLayout is built over
        // mTransformed, so the count describes the DISPLAYED text.
        // lane layout, 2026-08-01: delegate to getLayout() so the pair can
        // never disagree again -- the old self-wrapping body returned >= 1
        // while getLayout() was still the null stub, and app code iterating
        // `getLayout().getLineWidth(i)` for `i < getLineCount()` (Material's
        // MaterialButton.getTextLayoutWidth, from onLayout) NPE'd and aborted
        // the whole layout pass. F-Droid's `descriptionView.getLineCount() > 5`
        // "More"-button check (the reason the old body existed) still works:
        // it runs post-layout, where getLayout() is non-null and wraps the
        // same text at the same width.
        android.text.Layout l = getLayout();
        return l == null ? 0 : l.getLineCount();
    }
    public int  getLineHeight() {
        // ⚠ v1.0.962. AOSP TextView.getLineHeight() (android13-release
        // TextView.java:2479) is EXACTLY
        // `FastMath.round(mTextPaint.getFontMetricsInt(null) * mSpacingMult
        // + mSpacingAdd)`, and Paint.getFontMetricsInt(null) is
        // `descent - ascent + leading` UNCONDITIONALLY -- includeFontPadding
        // never enters it (libs/hwui/jni/Graphics.cpp
        // GraphicsJNI::set_metrics_int returns `descent - ascent + leading`,
        // full stop; our own Paint.getFontMetricsInt(FontMetricsInt) already
        // mirrors that). This used to route through macapkLineHeight(fm),
        // which returns the PADDED (fm.bottom - fm.top) height whenever
        // includeFontPadding is on (the default) -- so the getter, and
        // everything downstream that trusted it as "one line's advance"
        // (the ems-width bound below), answered a few px too large. Falls
        // back to textSizePx on error, same as before.
        try {
            android.text.TextPaint p = getPaint();
            p.setTextSize(textSizePx);
            applyTextStyleToPaint(p);
            int fontHeight = p.getFontMetricsInt(null);
            if (fontHeight > 0) return Math.round(fontHeight * mSpacingMult + mSpacingAdd);
        } catch (Throwable ignored) {}
        return (int) textSizePx;
    }
    /** AOSP TextView.java:4392-4402. Was an EMPTY METHOD, so a TextView could
     *  never hold a typeface at all -- every app that called
     *  setTypeface(Typeface.create("sans-serif-medium", NORMAL)) had the call
     *  silently discarded, and so did every XML android:fontFamily. */
    public void setTypeface(android.graphics.Typeface tf) {
        android.text.TextPaint p = getPaint();
        if (p.getTypeface() != tf) {
            p.setTypeface(tf);
            // AOSP nulls its layouts here; ours are rebuilt per pass, so the
            // relayout+invalidate is the whole observable effect.
            requestLayout();
            invalidate();
        }
    }

    /** AOSP TextView.java:2269-2288, verbatim in shape:
     *
     *      if (style > 0) {
     *          if (tf == null) { tf = Typeface.defaultFromStyle(style); }
     *          else            { tf = Typeface.create(tf, style); }
     *          setTypeface(tf);
     *          int typefaceStyle = tf != null ? tf.getStyle() : 0;
     *          int need = style & ~typefaceStyle;
     *          mTextPaint.setFakeBoldText((need & Typeface.BOLD) != 0);
     *          mTextPaint.setTextSkewX((need & Typeface.ITALIC) != 0 ? -0.25f : 0);
     *      } else {
     *          mTextPaint.setFakeBoldText(false);
     *          mTextPaint.setTextSkewX(0);
     *          setTypeface(tf);
     *      }
     *
     *  ⚠ `need = style & ~typefaceStyle` is the rule that says: fake ONLY what
     *  the resolved face does not already provide. With the real faces bundled,
     *  a BOLD request resolves to Roboto-Bold whose getStyle() is BOLD, so need
     *  == 0 and nothing is emboldened synthetically. Keeping the old
     *  "textStyle has BOLD, therefore fake-bold" behaviour on top of a real
     *  bold face would double-apply -- and an AWT algorithmic embolden is
     *  measurably ~4px WIDER than real Roboto-Bold while reading THINNER, so
     *  the result is a smear rather than a weight. */
    public void setTypeface(android.graphics.Typeface tf, int style) {
        this.macapkTextStyle = Math.max(style, 0);
        if (style > 0) {
            if (tf == null) tf = android.graphics.Typeface.defaultFromStyle(style);
            else            tf = android.graphics.Typeface.create(tf, style);

            setTypeface(tf);
            int typefaceStyle = tf != null ? tf.getStyle() : 0;
            int need = style & ~typefaceStyle;
            macapkFakeBold   = (need & android.graphics.Typeface.BOLD) != 0;
            macapkFakeItalic = (need & android.graphics.Typeface.ITALIC) != 0;
        } else {
            macapkFakeBold = false;
            macapkFakeItalic = false;
            setTypeface(tf);
        }
        android.text.TextPaint p = getPaint();
        p.setFakeBoldText(macapkFakeBold);
        p.setTextSkewX(macapkFakeItalic ? -0.25f : 0f);
    }
    // AOSP TextView.setGravity stores the gravity used to position the text
    // within the view bounds (honored in onDraw). Was previously a no-op, so
    // android:gravity / Button's default center gravity were ignored and all
    // text drew top-left. (Field `gravity` is declared below.)
    public void setGravity(int gravity) { this.gravity = gravity; invalidate(); }
    // AOSP TextView.length() is `mText.length()` (TextView.java:2448-2450) --
    // the RAW length, like getText(). PasswordTransformationMethod is
    // length-preserving so the two agree today, but the contract is "how many
    // characters does the app have", not "how many glyphs are on screen".
    public int  length() { return text.length(); }
    // hasFocus() is deliberately NOT overridden here. It used to return a
    // hardcoded false, which shadowed View.hasFocus()'s real mIsFocused state
    // for TextView and every widget under it (EditText, Button, CompoundButton,
    // CheckedTextView, ...) and left hasFocus() silently disagreeing with
    // isFocused() on the same view. AOSP's TextView does not override
    // hasFocus() either -- it inherits View's.

    /** AOSP EditText.selectAll() (core/java/android/widget/EditText.java:204)
     *  is `Selection.selectAll(getText())`. The empty stub already lived on
     *  TextView and EditText inherits it, so the body goes here;
     *  android.text.Selection already implements the primitive correctly. */
    public void selectAll() {
        android.text.Editable e = getEditableText();
        if (e instanceof android.text.Spannable)
            android.text.Selection.selectAll((android.text.Spannable) e);
    }

    /** AOSP TextView.setSelectAllOnFocus(boolean) (frameworks/base core/java/
     *  android/widget/TextView.java:12546) stores the flag on the Editor;
     *  Editor.onFocusChanged then does `if (mSelectAllOnFocus)
     *  mTextView.selectAllText()` on focus gain. We have no Editor, so the
     *  flag lives here and onFocusChanged below performs the select-all --
     *  same observable behavior for the search/URL fields that use it. */
    private boolean mSelectAllOnFocus;
    public void setSelectAllOnFocus(boolean selectAllOnFocus) {
        createEditorIfNeeded();   // AOSP :12077-12079 -- the flag lives on the Editor there
        this.mSelectAllOnFocus = selectAllOnFocus;
    }
    /** For Editor.onFocusChanged / onTouchUpEvent (AOSP reads mEditor.mSelectAllOnFocus). */
    boolean macapkSelectAllOnFocus() { return mSelectAllOnFocus; }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                                   android.graphics.Rect previouslyFocusedRect) {
        // AOSP runs the Editor's focus work BEFORE super (which fires the
        // OnFocusChangeListener), so a listener observing the selection sees
        // the select-all already applied.
        if (mEditor != null) {
            // AOSP :12610 `if (mEditor != null) mEditor.onFocusChanged(focused,
            // direction);` -- restarts the blink phase, runs the movement
            // method's onTakeFocus, then the select-all, then makeBlink.
            mEditor.onFocusChanged(gainFocus, direction);
        } else if (gainFocus && mSelectAllOnFocus) {
            selectAll();
        }
        // AOSP TextView.onFocusChanged (frameworks/base core/java/android/
        // widget/TextView.java:11315-11317), also immediately before super:
        //     if (mTransformation != null) {
        //         mTransformation.onFocusChanged(this, mText, focused,
        //                 direction, previouslyFocusedRect);
        //     }
        // Note the RAW mText is what the transformation is handed. This is the
        // callback PasswordTransformationMethod uses to drive the reveal-last-
        // typed-character animation, which we do not render, but a guest's own
        // TransformationMethod may rely on it.
        if (mTransformationMethod != null) {
            try {
                mTransformationMethod.onFocusChanged(this, text, gainFocus,
                    direction, previouslyFocusedRect);
            } catch (Throwable ignored) {}
        }
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }

    // ---- text-change listeners + soft-keyboard input (v1.0.337) ----
    // Real TextWatcher wiring so Unity's soft-input bridge works: Unity's
    // com.unity3d.player.S is a TextWatcher it registers via
    // addTextChangedListener; when the (hidden) EditText's text changes it
    // fires the watcher, which pushes the text to the engine via
    // UnityPlayer.nativeSetInputString. Previously these were no-ops, so a
    // hardware keystroke never reached a Unity InputField (room codes, chat,
    // names). Mirrors AOSP TextView's before/on/afterTextChanged firing.
    private java.util.ArrayList<android.text.TextWatcher> textWatchers;
    private android.text.method.KeyListener editKeyListener; // non-null => editable
    private OnEditorActionListener editorActionListener;
    private boolean firingWatchers;

    public void setOnEditorActionListener(OnEditorActionListener l) {
        // AOSP: createEditorIfNeeded(); mEditor.createInputContentTypeIfNeeded();
        macapkInputContentType = true;
        this.editorActionListener = l;
    }
    static final boolean TW_TRACE = System.getenv("MACAPK_TW_TRACE") != null;
    public void addTextChangedListener(android.text.TextWatcher w) {
        if (w == null) return;
        if (textWatchers == null) textWatchers = new java.util.ArrayList<>();
        if (!textWatchers.contains(w)) textWatchers.add(w);
        if (TW_TRACE) com.macapk.loader.NativeLog.i("Mac.apk.TW",
            "addTextChangedListener on " + getClass().getSimpleName() + " watcher=" + w.getClass().getName());
    }
    public void removeTextChangedListener(android.text.TextWatcher w) {
        if (textWatchers != null) textWatchers.remove(w);
    }

    private android.text.Editable mCachedEditable;
    private CharSequence mCachedEditableSrc;

    /** AOSP: an Editable view of the text (EditText.getText narrows to this).
     *  v1.0.365: return a PERSISTENT Editable, not a fresh copy each call.
     *  AOSP's EditText stores its text as a single Editable and getText()/
     *  getEditableText() return that same instance, so SELECTION spans set
     *  via Selection.setSelection(getText(), ...) survive to the next
     *  getSelectionStart()/getSelectionEnd() read. Returning a fresh
     *  SpannableStringBuilder each call dropped the selection (always -1),
     *  which made Google Calculator's backspace handler -- it does
     *  setSelection(...) then loops on getSelectionStart() -- never
     *  converge (infinite recursion -> stack overflow on the main thread).
     *  We rebuild the buffer only when setText changed the underlying text
     *  (identity compare), so edits within one text version persist. */
    public android.text.Editable getEditableText() {
        if (mCachedEditable == null || mCachedEditableSrc != text) {
            android.text.SpannableStringBuilder buf =
                new android.text.SpannableStringBuilder(text != null ? text : "");
            if (getDefaultEditable() || editKeyListener != null) {
                // AOSP setText attaches its ChangeWatcher to the Editable
                // (android14 TextView.java:7153-7155, SPAN_INCLUSIVE_INCLUSIVE
                // over the whole text) so an IN-PLACE edit updates the view.
                buf.setSpan(macapkChangeWatcher(), 0, buf.length(),
                        android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
            mCachedEditable = buf;
            mCachedEditableSrc = text;
        }
        return mCachedEditable;
    }

    private MacapkChangeWatcher mChangeWatcher;
    private MacapkChangeWatcher macapkChangeWatcher() {
        if (mChangeWatcher == null) mChangeWatcher = new MacapkChangeWatcher();
        return mChangeWatcher;
    }

    /** AOSP TextView.ChangeWatcher (android14 :15816): the TextWatcher span on
     *  the view's own Editable. An app that edits getText() in place
     *  (`getText().insert(getSelectionStart(), s)` -- emoji pickers, format
     *  toolbars, MultiAutoCompleteTextView.replaceText) must see the field
     *  repaint and its TextWatchers fire. v1.0.1642: our Editable was a
     *  detached copy, so such edits vanished; they used to THROW first (the
     *  selection was -1), and the fixed selection made them reachable. Once
     *  edited in place, the buffer BECOMES the text, as append() already does.
     *  Only the view's CURRENT buffer is honoured; a stale one an app kept a
     *  reference to is ignored. NoCopySpan: never carried into copies. */
    private final class MacapkChangeWatcher
            implements android.text.TextWatcher, android.text.SpanWatcher, android.text.NoCopySpan {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (s != mCachedEditable || firingWatchers || textWatchers == null) return;
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers)) {
                try { w.beforeTextChanged(s, start, count, after); } catch (Throwable ignored) {}
            }
        }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (s != mCachedEditable) return;
            macapkSetTextInternal(mCachedEditable);
            mCachedEditableSrc = mCachedEditable;
            // AOSP handleTextChanged -> updateAfterEdit (:12424-12439).
            try { macapkUpdateAfterEdit(); } catch (Throwable ignored) {}
            if (firingWatchers || textWatchers == null) return;
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers)) {
                try { w.onTextChanged(s, start, before, count); } catch (Throwable ignored) {}
            }
        }
        @Override public void afterTextChanged(android.text.Editable b) {
            if (b != mCachedEditable) return;
            requestLayout();
            invalidate();
            if (firingWatchers || textWatchers == null) return;
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers)) {
                try { w.afterTextChanged(b); } catch (Throwable ignored) {}
            }
        }
        // AOSP ChangeWatcher's SpanWatcher half (android14 TextView.java,
        // class ChangeWatcher): the three callbacks funnel into spanChange with
        // -1 for the side that does not exist. This is how a caret MOVE
        // reaches the view -- Selection.setSelection just moves two marker
        // spans on the buffer.
        // Each call is fenced: these run INSIDE the app's own setSpan /
        // Selection.setSelection / Editable edit, and caret bookkeeping (which
        // may build a Layout) must never turn an app's edit into an exception.
        @Override public void onSpanChanged(android.text.Spannable buf, Object what,
                                            int s, int e, int st, int en) {
            if (buf != mCachedEditable) return;
            try { spanChange(buf, what, s, st, e, en); } catch (Throwable ignored) {}
        }
        @Override public void onSpanAdded(android.text.Spannable buf, Object what, int s, int e) {
            if (buf != mCachedEditable) return;
            try { spanChange(buf, what, -1, s, -1, e); } catch (Throwable ignored) {}
        }
        @Override public void onSpanRemoved(android.text.Spannable buf, Object what, int s, int e) {
            if (buf != mCachedEditable) return;
            try { spanChange(buf, what, s, -1, e, -1); } catch (Throwable ignored) {}
        }
    }

    /** AOSP TextView.spanChange (:12475-12550), the SELECTION and APPEARANCE
     *  arms: a moved marker invalidates the old and new caret region and
     *  restarts the blink (so the caret is solid right after it moves, as on a
     *  device), a selection set while unfocused is remembered for
     *  onFocusChanged, and a style span repaints (see the arm's comment).
     *  NOT ported here: the onSelectionChanged(selStart, selEnd) dispatch and
     *  the meta-state / IME batch-edit arms. Those change what app code
     *  observes, not where the caret or the glyphs are. */
    void spanChange(android.text.Spanned buf, Object what,
                    int oldStart, int newStart, int oldEnd, int newEnd) {
        if (!MACAPK_TEXT_CARET) return;
        boolean selChanged = false;
        if (what == android.text.Selection.SELECTION_END) {
            selChanged = true;
            if (oldStart >= 0 || newStart >= 0) {
                invalidateCursor(android.text.Selection.getSelectionStart(buf), oldStart, newStart);
                if (mEditor != null) mEditor.makeBlink();
            }
        }
        if (what == android.text.Selection.SELECTION_START) {
            selChanged = true;
            if (oldStart >= 0 || newStart >= 0) {
                int end = android.text.Selection.getSelectionEnd(buf);
                invalidateCursor(end, oldStart, newStart);
            }
        }
        if (selChanged) {
            mHighlightPathBogus = true;
            if (mEditor != null && !isFocused()) mEditor.mSelectionMoved = true;
        }
        // AOSP spanChange's appearance arm (:12520-12550):
        //     if (what instanceof UpdateAppearance || what instanceof ParagraphStyle
        //             || what instanceof CharacterStyle) { invalidate(); ...; checkForResize(); }
        // and for a MetricAffectingSpan, `invalidateCursor()` when there is a
        // selection. On a device the Editable IS mText, so a style span set on
        // getText() is painted and laid out at once. Here the buffer is a copy
        // of the text until the first in-place edit makes it the text (the
        // ChangeWatcher's rule), so a span set between setText and that edit --
        // `getText().setSpan(new StyleSpan(BOLD), ...)`, an emoji span, a size
        // span -- never reached onDraw, getLayout() or the caret. A style span
        // on the current buffer makes the buffer the text, exactly as an edit
        // does, and then invalidates as AOSP does.
        if (what instanceof android.text.style.UpdateAppearance
                || what instanceof android.text.style.ParagraphStyle
                || what instanceof android.text.style.CharacterStyle) {
            if (buf == mCachedEditable && text != buf) {
                macapkSetTextInternal(mCachedEditable);
                mCachedEditableSrc = mCachedEditable;
            }
            mHighlightPathBogus = true;
            invalidate();
            if (what instanceof android.text.style.MetricAffectingSpan) {
                // checkForResize: the text's extent can change.
                requestLayout();
                if (android.text.Selection.getSelectionStart(buf) >= 0) invalidateCursor();
            }
        }
    }

    /** AOSP updateAfterEdit (:12424-12439), the caret part:
     *      if (curs >= 0) { mHighlightPathBogus = true; mEditor.makeBlink(); }
     *  An edit restarts the blink even when it did not move the caret. */
    private void macapkUpdateAfterEdit() {
        if (!MACAPK_TEXT_CARET) return;
        if (getSelectionStart() >= 0) {
            mHighlightPathBogus = true;
            if (mEditor != null) mEditor.makeBlink();
        }
    }

    /** Replace the text and fire the registered TextWatchers in AOSP order
     *  (beforeTextChanged -> set -> onTextChanged -> afterTextChanged).
     *  Used by both app setText and our soft-key input path. */
    protected void macapkSetTextAndNotify(CharSequence n) {
        CharSequence old = text != null ? text : "";
        if (n == null) n = "";
        boolean fire = textWatchers != null && !textWatchers.isEmpty() && !firingWatchers;
        if (fire) {
            firingWatchers = true;
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.beforeTextChanged(old, 0, old.length(), n.length()); } catch (Throwable ignore) {}
        }
        macapkSetTextInternal(n);
        // Typing appends at / deletes from the end (see macapkHandleSoftKey), so
        // the caret belongs at the end, as the Editable's selection span would
        // be after an in-place insert on a device. Without it the rebuilt
        // buffer reported -1 after every typed character.
        if (movementMethod != null) {
            try {
                android.text.Editable e = getEditableText();
                android.text.Selection.setSelection(e, e.length());
            } catch (Throwable ignored) {}
        }
        if (fire) {
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.onTextChanged(text, 0, old.length(), text.length()); } catch (Throwable ignore) {}
            android.text.Editable e = getEditableText();
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.afterTextChanged(e); } catch (Throwable ignore) {}
            firingWatchers = false;
        }
        invalidate();
    }

    /** Apply one host keystroke to this text field (Mac.apk's IME).
     *  Returns true if consumed. Routed here by TouchDispatcher when this
     *  view is the active soft-input target (see InputMethodManager). */
    public boolean macapkHandleSoftKey(int unicodeChar, int keyCode) {
        return macapkHandleSoftKey(unicodeChar, keyCode, null);
    }

    /** The overload that carries the originating KeyEvent, so the editor-action
     *  listener sees the real event AOSP's doKeyDown hands it. `ev` is null on
     *  the soft-input route, which mirrors AOSP's onEditorAction(int). */
    public boolean macapkHandleSoftKey(int unicodeChar, int keyCode, android.view.KeyEvent ev) {
        // KEYCODE_DEL=67, KEYCODE_FORWARD_DEL=112, KEYCODE_ENTER=66.
        boolean backspace = keyCode == 67 || keyCode == 112
                            || unicodeChar == 8 || unicodeChar == 127;
        boolean enter     = keyCode == 66 || unicodeChar == 10 || unicodeChar == 13;
        String cur = text != null ? text.toString() : "";
        if (backspace) {
            // Gate MACAPK_TEXT_CARET: at the SELECTION, not at the end (see
            // macapkEditAtSelection). KEYCODE_FORWARD_DEL deletes forward.
            if (MACAPK_TEXT_CARET) {
                macapkDeleteAtSelection(keyCode == 112);
                return true;
            }
            if (cur.length() > 0) macapkSetTextAndNotify(cur.substring(0, cur.length() - 1));
            return true;
        }
        if (enter) {
            // AOSP TextView.doKeyDown, KEYCODE_ENTER (android14-release
            // TextView.java:9387-9405):
            //
            //     if (mEditor.mInputContentType.onEditorActionListener != null
            //             && mEditor.mInputContentType.onEditorActionListener.onEditorAction(
            //                     this, getActionIdForEnterEvent(), event)) { ... }
            //
            // Both arguments used to be wrong here, and each one on its own is
            // enough to make a search box ignore Enter:
            //
            //  * the action id was hardcoded IME_ACTION_DONE (6). A field
            //    declaring android:imeOptions="actionSearch" tests
            //    actionId == IME_ACTION_SEARCH and saw 6, so it declined.
            //  * the KeyEvent was null. The commonest listener idiom in the
            //    wild is `actionId == X || (event != null
            //    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)`, and a null
            //    event fails the second half even when the first half is right.
            //    On a real device that half ALWAYS holds for a hardware Enter.
            //
            // `ev` is null only on the soft-input route (TouchDispatcher's
            // macapkActiveInput path), which is the same shape AOSP's
            // onEditorAction(int) uses when an IME action button is tapped
            // (TextView.java:7965-7969 passes null there too).
            // A MULTI-LINE field takes Enter as a NEWLINE, on both routes. AOSP:
            // TextView.onCreateInputConnection sets IME_FLAG_NO_ENTER_ACTION for
            // every isMultilineInputType field ("Multi-line text editors should
            // always show an enter key", android14 TextView.java:9061-9064), so a
            // keyboard's Enter commits '\n' there instead of an editor action; and
            // doKeyDown lets a hardware Enter through to the key listener (which
            // inserts '\n') unless shouldAdvanceFocusOnEnter() -- singleLine, or
            // an email / URI variation. Both used to end below as an editor
            // action (soft route) or a swallowed key, so Enter never started a new
            // line in any note editor (NoNonsense Notes, whose title is the first
            // line, could not have a body). The test is AOSP's isMultilineInputType
            // on the declared input type, so a field we cannot prove multi-line
            // keeps the old behaviour.
            if (macapkEnterInsertsNewline()) {
                // A keyboard's Enter reaches the field as a KEYCODE_ENTER key
                // event (LatinIME sendDownUpKeyEvent), so the app's listener is
                // asked first on both routes, as doKeyDown does.
                final android.view.KeyEvent kev = ev != null ? ev
                    : new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                                                android.view.KeyEvent.KEYCODE_ENTER);
                if (editorActionListener == null || !macapkEditorActionConsumes(kev)) {
                    if (MACAPK_TEXT_CARET) {
                        macapkInsertAtSelection("\n");
                    } else {
                        CharSequence ins = macapkFilterInsertion("\n", cur.length(), cur.length());
                        if (ins != null && ins.length() > 0) macapkSetTextAndNotify(cur + ins);
                    }
                }
                return true;
            }
            // Soft-input route (no KeyEvent) == the IME's action button, which
            // AOSP delivers as onEditorAction(actionId): listener first, then the
            // NEXT/PREVIOUS/DONE defaults, and a subclass override runs too.
            if (ev == null && MACAPK_EDITOR_ACTION_DEFAULTS) {
                // A throw here (AOSP's IllegalStateException for a focus search
                // that returns an unfocusable view) is caught like the
                // listener's was: a gap in OUR focus finder must not kill the
                // app's key thread.
                try { onEditorAction(macapkActionIdForEnterEvent()); }
                catch (Throwable ignore) {}
                return true;
            }
            if (editorActionListener != null) {
                try {
                    if (editorActionListener.onEditorAction(
                            this, macapkActionIdForEnterEvent(), ev)) return true;
                } catch (Throwable ignore) {}
            }
            // SCOPED DELTA from AOSP, for a field we cannot prove multi-line (a
            // provable one took the newline above): AOSP would now fall through
            // and let the key listener decide. We consume instead. Our
            // KeyListener chain is inert (see onKeyDown's header), so falling
            // through would drop the key rather than insert anything, and
            // turning it into a literal '\n' for every field we cannot prove is
            // multi-line would put newlines into game name/room-code fields that
            // today commit cleanly. Revisit together with a real
            // ArrowKeyMovementMethod/KeyListener edit path.
            return true;
        }
        if (unicodeChar >= 32 && unicodeChar != 127) {
            if (MACAPK_TEXT_CARET) {
                macapkInsertAtSelection(new String(Character.toChars(unicodeChar)));
                return true;
            }
            // AOSP inserts through Editable.replace, which consults the
            // InputFilters with dest=current buffer and dstart==dend==caret.
            // A filter may shorten (LengthFilter at the cap) or transform
            // (AllCaps) the inserted slice, or reject it entirely ("").
            CharSequence ins = macapkFilterInsertion(
                new String(Character.toChars(unicodeChar)), cur.length(), cur.length());
            if (ins == null || ins.length() == 0) return true;  // consumed, filtered out
            macapkSetTextAndNotify(cur + ins);
            return true;
        }
        return false;   // non-text key (arrows, etc.) -> let the game handle it
    }

    /** Enter starts a new line here: AOSP's isMultilineInputType(getInputType())
     *  (`(type & (TYPE_MASK_CLASS | TYPE_TEXT_FLAG_MULTI_LINE)) ==
     *  (TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE)`) and not
     *  shouldAdvanceFocusOnEnter() (singleLine, or the email / URI
     *  variations), android14 TextView.java:7495-7498, :9310-9325. */
    private boolean macapkEnterInsertsNewline() {
        final int t = getInputType();
        final int ml = android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        if ((t & (android.text.InputType.TYPE_MASK_CLASS | ml))
                != (android.text.InputType.TYPE_CLASS_TEXT | ml)) return false;
        if (isSingleLine()) return false;
        final int variation = t & android.text.InputType.TYPE_MASK_VARIATION;
        return variation != android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            && variation != android.text.InputType.TYPE_TEXT_VARIATION_URI;
    }

    private boolean macapkEditorActionConsumes(android.view.KeyEvent ev) {
        try {
            return editorActionListener.onEditorAction(this, macapkActionIdForEnterEvent(), ev);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** The selection an edit applies to, as AOSP's key listeners read it:
     *  {min, max} of the two markers (QwertyKeyListener.onKeyDown's
     *  `selStart = Math.min(a, b); selEnd = Math.max(a, b);`). A buffer with
     *  NO selection edits at its END here, where AOSP would reset it to 0
     *  (`if (selStart < 0 || selEnd < 0) { selStart = selEnd = 0; ... }`):
     *  a focused EditText always has one (ArrowKeyMovementMethod.initialize
     *  sets it on every setText), and a view that never got one has typed at
     *  its end in this port since v1.0.337, which a keystroke must not reverse. */
    private int[] macapkEditRange(android.text.Editable buf) {
        final int len = buf.length();
        int a = android.text.Selection.getSelectionStart(buf);
        int b = android.text.Selection.getSelectionEnd(buf);
        if (a < 0 || b < 0) return new int[]{ len, len, 0 };
        if (a > b) { int t = a; a = b; b = t; }
        return new int[]{ Math.min(a, len), Math.min(b, len), 1 };
    }

    /** Gate MACAPK_TEXT_CARET: one typed character goes IN at the selection,
     *  replacing a range selection, as AOSP's QwertyKeyListener.onKeyDown does
     *  (`content.replace(selStart, selEnd, String.valueOf((char) i))`,
     *  android14 QwertyKeyListener.java, after collapsing a range with
     *  `Selection.setSelection(content, selEnd)`), leaving the caret after it.
     *  The InputFilters see the real destination range (dstart, dend). */
    private void macapkInsertAtSelection(CharSequence typed) {
        final android.text.Editable buf = getEditableText();
        final int[] r = macapkEditRange(buf);
        final CharSequence ins = macapkFilterInsertion(typed, r[0], r[1]);
        // A filter that rejects the character (""/null) leaves the buffer
        // alone, as SpannableStringBuilder.replace's no-op test does when
        // nothing is removed either; with a range selected, the range still
        // goes (replace(a, b, "") is a delete).
        final int added = (ins == null) ? 0 : ins.length();
        if (added == 0 && r[0] == r[1]) return;
        macapkEditAtSelection(buf, r[0], r[1], added == 0 ? "" : ins, r[2] != 0);
    }

    /** Gate MACAPK_TEXT_CARET: BaseKeyListener.backspace / forwardDelete
     *  (android14 BaseKeyListener.backspaceOrForwardDelete): a range selection
     *  is deleted as a whole (deleteSelection); otherwise ONE code point
     *  before (backspace) or after (forward delete) the caret. AOSP steps by
     *  getOffsetForBackspaceKey / getOffsetForForwardDeleteKey, which also
     *  keep emoji ZWJ / keycap / variation sequences whole; this steps by code
     *  point, so a surrogate pair is never split, but a multi-code-point emoji
     *  takes one keystroke per code point. The old path removed one UTF-16
     *  unit from the END whatever the key or the caret. */
    private void macapkDeleteAtSelection(boolean forward) {
        final android.text.Editable buf = getEditableText();
        final int[] r = macapkEditRange(buf);
        int a = r[0], b = r[1];
        if (a == b) {
            final int len = buf.length();
            if (forward) {
                if (a >= len) return;
                b = (a + 1 < len && Character.isHighSurrogate(buf.charAt(a))
                        && Character.isLowSurrogate(buf.charAt(a + 1))) ? a + 2 : a + 1;
            } else {
                if (a <= 0) return;
                a = (a >= 2 && Character.isLowSurrogate(buf.charAt(a - 1))
                        && Character.isHighSurrogate(buf.charAt(a - 2))) ? a - 2 : a - 1;
            }
        }
        macapkEditAtSelection(buf, a, b, "", r[2] != 0);
    }

    /** Replace [a, b) of the view's OWN Editable with `ins`, in place, and
     *  leave the caret at a + ins.length() -- the one place both keystroke
     *  edits land, shaped exactly like append(): the TextWatchers see the real
     *  (start, before, count) delta instead of a whole-buffer replace, and
     *  every span on the buffer (style, selection markers) survives.
     *
     *  WHY (cluster E review). Once the caret became visible and a tap could
     *  move it (Editor.onTouchUpEvent), the old path -- rebuild the text as
     *  `cur + ins`, then force the selection to the end -- turned a tap-then-
     *  type into a lie: "12345", tap between 2 and 3, type x gave "12345x" with
     *  the caret at 6, where a device gives "12x345" with the caret at 3.
     *
     *  The caret goes to a + added EXPLICITLY, although SpannableStringBuilder
     *  already carries its POINT selection there: that is the end state every
     *  AOSP key listener reaches (the collapsed insertion point after the
     *  insert; the start of the deleted range), and saying it does not depend
     *  on this port's span-offset arithmetic. `hadSelection` false (a view
     *  that never had one) keeps the old rule: a selection only where there is
     *  a movement method. */
    private void macapkEditAtSelection(android.text.Editable buf, int a, int b,
                                       CharSequence ins, boolean hadSelection) {
        final int removed = b - a;
        final int added = ins.length();
        final boolean fire = textWatchers != null && !textWatchers.isEmpty() && !firingWatchers;
        if (fire) {
            firingWatchers = true;
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.beforeTextChanged(buf, a, removed, added); } catch (Throwable ignored) {}
        }
        try {
            if (added > 0) buf.replace(a, b, ins);
            else if (removed > 0) buf.delete(a, b);
        } catch (Throwable ignored) {}
        // The buffer BECOMES the text (append()'s rule): the ChangeWatcher does
        // this itself when it is on the buffer, and must not be relied on, since
        // only a default-editable view or one with a key listener carries it.
        macapkSetTextInternal(buf);
        this.mCachedEditableSrc = buf;
        if (hadSelection || movementMethod != null) {
            try {
                android.text.Selection.setSelection(buf, Math.min(a + added, buf.length()));
            } catch (Throwable ignored) {}
        }
        // AOSP handleTextChanged -> updateAfterEdit: the caret is solid while
        // typing. Idempotent with the ChangeWatcher's own call.
        try { macapkUpdateAfterEdit(); } catch (Throwable ignored) {}
        if (fire) {
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.onTextChanged(buf, a, removed, added); } catch (Throwable ignored) {}
            for (android.text.TextWatcher w : new java.util.ArrayList<>(textWatchers))
                try { w.afterTextChanged(buf); } catch (Throwable ignored) {}
            firingWatchers = false;
        }
        requestLayout();
        invalidate();
    }

    /** AOSP TextView.isTextEditable():
     *
     *      boolean isTextEditable() {
     *          return mText instanceof Editable && onCheckIsTextEditor() && isEnabled();
     *      }
     *   (frameworks/base core/java/android/widget/TextView.java:11056-11058)
     *
     *  The first conjunct is constant-true for the editable case in this tree:
     *  EditText.getText() returns getEditableText(), so the buffer an editor
     *  edits is always an Editable. Same reasoning EditText.getAutofillType
     *  already records. */
    private boolean macapkIsTextEditor() {
        return onCheckIsTextEditor() && isEnabled();
    }

    /** AOSP TextView.onKeyDown -> doKeyDown: a TextView that is a text editor
     *  consumes the keystroke THROUGH ITS KEY LISTENER and edits the Editable
     *  directly. No IME is involved:
     *
     *      if (mEditor != null && mEditor.mKeyListener != null) {
     *          ...
     *          final boolean handled = mEditor.mKeyListener.onKeyDown(this,
     *                  (Editable) mText, keyCode, event);
     *          if (handled) return KEY_DOWN_HANDLED_BY_KEY_LISTENER;
     *      }
     *   (frameworks/base core/java/android/widget/TextView.java, android14-release:
     *    onKeyDown -> doKeyDown:9372, the mKeyListener branch)
     *
     *  That is why a hardware keyboard types into a focused EditText on a real
     *  device with the soft keyboard hidden. We had NO override, so
     *  View.onKeyDown's `return false` dropped every character, and the only way
     *  text ever reached a field was InputMethodManager.macapkActiveInput --
     *  which is set when an app SHOWS the soft keyboard. A field the app focuses
     *  programmatically therefore ignored the keyboard entirely.
     *
     *  Measured 2026-08-12 on F-Droid's search screen, whose EditText
     *  auto-focuses when the search Activity opens: six characters reached
     *  TouchDispatcher.dispatchKey with the correct unicode, Activity dispatch
     *  routed them to the focused view, every one returned false, and the field
     *  stayed text=''. On macOS the physical keyboard is THE text input method,
     *  so this was not an edge case -- it was every search box in every app.
     *
     *  Our KeyListener chain (MetaKeyKeyListener and below) returns false from
     *  onKeyDown today, so the AOSP call is made first and insertion falls
     *  through to macapkHandleSoftKey -- the SAME routine the soft-input path
     *  uses. One insertion path, so InputFilters, TextWatchers and the editor
     *  action behave identically however the character arrived, and there is no
     *  third path to keep in step. If a real listener lands later its `true`
     *  wins automatically, exactly as AOSP specifies. */
    private static final boolean sTraceIme = "1".equals(System.getenv("MACAPK_TRACE_IME"));
    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent ev) {
        if (sTraceIme) {
            System.err.println("[Mac.apk.IME] TextView.onKeyDown " + getClass().getName()
                + " editor=" + macapkIsTextEditor()
                + " enabled=" + isEnabled() + " checkIsEditor=" + onCheckIsTextEditor()
                + " focused=" + isFocused() + " focusableInTouchMode=" + isFocusableInTouchMode());
        }
        if (macapkIsTextEditor()) {
            android.text.method.KeyListener kl = mKeyListener;
            if (kl != null) {
                try {
                    if (kl.onKeyDown(this, getEditableText(), keyCode, ev)) return true;
                } catch (Throwable ignore) {}
            }
            if (macapkHandleSoftKey(ev != null ? ev.getUnicodeChar() : 0, keyCode, ev)) return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    /** The UP half of the above. AOSP routes it to the same listener
     *  (doKeyUp -> mEditor.mKeyListener.onKeyUp); we additionally swallow the
     *  UP that matches a DOWN we consumed, so an app never observes half a
     *  keystroke -- the same rule TouchDispatcher.doDispatchKey applies on the
     *  soft-input path, and for the same reason. */
    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent ev) {
        if (macapkIsTextEditor()) {
            android.text.method.KeyListener kl = mKeyListener;
            if (kl != null) {
                try {
                    if (kl.onKeyUp(this, getEditableText(), keyCode, ev)) return true;
                } catch (Throwable ignore) {}
            }
            int u = ev != null ? ev.getUnicodeChar() : 0;
            boolean textKey = (u >= 32 && u != 127)
                || keyCode == 67 || keyCode == 112 || keyCode == 66
                || u == 8 || u == 10 || u == 13;
            if (textKey) return true;
        }
        return super.onKeyUp(keyCode, ev);
    }

    /** Paint the configured text using the configured size and color
     *  at the view's bounds. Mirrors AOSP TextView.onDraw at the
     *  level of detail needed for plain-button / plain-label menu
     *  screens (Frozen Bubble's HomeScreen, F-Droid's About row,
     *  any 2D app without HTML/spannable text). Production-grade
     *  text shaping (RTL/BiDi, complex script, ellipsizing, multi-
     *  line) is intentionally out of scope -- the AWT Graphics2D
     *  drawString already does enough to make the buttons readable
     *  on the test set.
     *
     *  Why this exists at all: every plain Android widget (Button,
     *  TextView, EditText) without an onDraw override would paint
     *  nothing through ViewCanvasDriver, leaving the entire menu
     *  blank. This is the missing rendering primitive that blocked
     *  Frozen Bubble's HomeScreen and similar Canvas-only apps. */
    /** v1.0.109: real onMeasure that respects WRAP_CONTENT for text
     *  width / height. AOSP TextView walks its text via the Layout
     *  class to compute multi-line text bounds; we approximate with
     *  a single-line `paint.measureText(text)` for the width and
     *  `textSizePx * 1.2` per line for height. Critical for
     *  ConstraintLayout's solver -- when a child has lpW=WRAP_CONTENT
     *  and is referenced by another child's startToEnd / endToStart
     *  anchor, the solver asks the measurer for the wrap-content
     *  width. If we return 0 the solver punts and the entire
     *  constraint chain collapses to zero dimensions. F-Droid's
     *  app card horizontal layout has `<include id=new_tag>` (WRAP_CONTENT)
     *  that the name TextView's endToStart anchors to; without a
     *  non-zero wrap width on new_tag, name.width can't be solved,
     *  and the cascade leaves every TextView at 0x0. */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (MACAPK_TEXT_LINES_AOSP) { macapkOnMeasureAosp(wSpec, hSpec); return; }
        int wMode = android.view.View.MeasureSpec.getMode(wSpec);
        int wSize = android.view.View.MeasureSpec.getSize(wSpec);
        int hMode = android.view.View.MeasureSpec.getMode(hSpec);
        int hSize = android.view.View.MeasureSpec.getSize(hSpec);

        // Compute desired content dimensions from the text.
        // MEASURE THE TRANSFORMED TEXT, not the raw text. AOSP measures via
        // the Layout, which is built over mTransformed (makeSingleLayout,
        // TextView.java:9529-9530; the autosize path spells it out at
        // TextView.java:9885-9887: `final CharSequence text = mTransformed !=
        // null ? mTransformed : getText();`). For a password field the two
        // differ in GLYPH WIDTH even though the lengths match, so measuring the
        // raw text would size the view to the real characters' advance and leak
        // the password's shape through the layout.
        final CharSequence dispM = macapkDisplayText();
        int[] macapkTextSize = macapkMeasureContent(dispM, wMode, wSize);
        int textW = macapkTextSize[0];
        int textH = macapkTextSize[1];
        // THE HINT PARTICIPATES IN MEASUREMENT, and unconditionally -- not only
        // when the text is empty. AOSP onMeasure widens the width to the hint
        // (`if (hintWidth > width) { width = hintWidth; }`,
        // TextView.java:11385-11387) and getDesiredHeight takes
        // `Math.max(getDesiredHeight(mLayout, true), getDesiredHeight(mHintLayout, ...))`
        // (:11597-11601). This is what gives an EMPTY search field its size: with
        // no text and an unmeasured hint the content box is one blank line wide
        // by one line tall, so even a correctly painted hint would be clipped to
        // nothing. Measured through macapkMeasureContent so the wrap, the
        // fallback-font widening and the block-height rules are identical to the
        // text path.
        int macapkHintH = -1;
        if (mHint != null && mHint.length() > 0) {
            int[] macapkHintSize = macapkMeasureContent(mHint, wMode, wSize);
            macapkHintH = macapkHintSize[1];
            if (macapkHintSize[0] > textW) textW = macapkHintSize[0];
            if (macapkHintSize[1] > textH) textH = macapkHintSize[1];
        }
        // setText's checkForRelayout (MACAPK_TEXT_CHECK_RELAYOUT) re-runs this
        // exact text measure over the new text; record its inputs and result.
        macapkRecordRelayoutInputs(false, wMode, wSize, 0, false, macapkTextSize, macapkHintH);
        // AOSP fits LEFT/RIGHT compound drawables vertically: the measured
        // height accounts for their heights (TextView tracks
        // mDrawableHeightLeft/Right and takes the max against the text
        // height). The other half of the icon-only ActionMenuItemView fix.
        int macapkDrawH = Math.max(
            mDrawableLeft  != null ? macapkDrawableH(mDrawableLeft)  : 0,
            mDrawableRight != null ? macapkDrawableH(mDrawableRight) : 0);
        if (macapkDrawH > textH) textH = macapkDrawH;
        // Add compound padding for AOSP-correctness (text bounds vs
        // padded view bounds).
        textW += getCompoundPaddingLeft() + getCompoundPaddingRight();
        textH += getCompoundPaddingTop()  + getCompoundPaddingBottom();

        int finalW, finalH;
        if (wMode == android.view.View.MeasureSpec.EXACTLY)      finalW = wSize;
        else if (wMode == android.view.View.MeasureSpec.AT_MOST) finalW = Math.min(wSize, textW);
        else /* UNSPECIFIED */                                    finalW = textW;
        if (hMode == android.view.View.MeasureSpec.EXACTLY)      finalH = hSize;
        else if (hMode == android.view.View.MeasureSpec.AT_MOST) finalH = Math.min(hSize, textH);
        else /* UNSPECIFIED */                                    finalH = textH;
        // AOSP TextView.onMeasure clamps to the suggested minimum (android:minWidth/
        // minHeight, background min). Without this a MaterialButton with a 40dp
        // minHeight collapsed to its text height (~28px). Only the non-EXACTLY axes
        // grow (EXACTLY is a hard spec); minimums are usually 0 so other views are
        // unaffected.
        // AOSP onMeasure applies an ems-expressed min/max width as
        // `ems * getLineHeight()` px (TextView treats the line height as the
        // em box) and only on a non-EXACTLY width spec. Guarded on "was
        // setEms/setMaxEms/setMinEms actually called" (-1 = unset) so every
        // view that never uses ems measures exactly as before.
        if (wMode != android.view.View.MeasureSpec.EXACTLY
                && (mMaxEms >= 0 || mMinEms >= 0)) {
            int em = getLineHeight();
            if (mMaxEms >= 0) finalW = Math.min(finalW, mMaxEms * em);
            if (mMinEms >= 0) finalW = Math.max(finalW, mMinEms * em);
        }
        if (wMode != android.view.View.MeasureSpec.EXACTLY)
            finalW = Math.max(finalW, getSuggestedMinimumWidth());
        if (hMode != android.view.View.MeasureSpec.EXACTLY)
            finalH = Math.max(finalH, getSuggestedMinimumHeight());
        mMacapkRelayoutMeasuredW = finalW;
        setMeasuredDimension(finalW, finalH);
    }

    /** AOSP TextView.onMeasure (android14-release TextView.java:10881-11028)
     *  with its height half, getDesiredHeight (:11147-11199), and the
     *  mMaximum/mMinimum limits the setters above now feed. Differences from
     *  the pre-gate onMeasure below, each of them AOSP's order:
     *   - the width limits (px or ems) clamp BEFORE the AT_MOST clamp, and the
     *     text is wrapped at that clamped width, because AOSP lays the text out
     *     at `want = width - padding` after clamping (:10937-10960);
     *   - getSuggestedMinimumWidth/Height are folded in before the AT_MOST
     *     clamp as well (:10952 and getDesiredHeight's last line), so a
     *     minimum never pushes a view past its parent's AT_MOST bound;
     *   - height is the first min(lineCount, maxLines) lines, never the whole
     *     text (StaticLayout.getHeight(cap)), and minLines ADDS
     *     getLineHeight() per missing line:
     *         if (mMinMode == LINES) {
     *             if (linecount < mMinimum)
     *                 desired += getLineHeight() * (mMinimum - linecount);
     *         } else desired = Math.max(desired, mMinimum);
     *   - a PIXELS maximum caps the padded height: `desired = Math.min(desired,
     *     mMaximum)` (:11174-11175).
     *  The hint is measured the same way but capped only when mEllipsize is
     *  set (getDesiredHeight(mHintLayout, mEllipsize != null), :11147-11151). */
    private void macapkOnMeasureAosp(int wSpec, int hSpec) {
        final int wMode = android.view.View.MeasureSpec.getMode(wSpec);
        final int wSize = android.view.View.MeasureSpec.getSize(wSpec);
        final int hMode = android.view.View.MeasureSpec.getMode(hSpec);
        final int hSize = android.view.View.MeasureSpec.getSize(hSpec);
        final int padL = getCompoundPaddingLeft(), padR = getCompoundPaddingRight();
        final int em = (mMaxEms >= 0 || mMinEms >= 0) ? getLineHeight() : 0;
        final int maxW = (mMaxEms >= 0) ? mMaxEms * em : mMaxWidthPx;
        final int minW = (mMinEms >= 0) ? mMinEms * em : mMinWidthPx;
        // The width the text is laid out at. EXACTLY is the parent's word; any
        // other mode is bounded by the view's own maximum as well.
        int mMode = wMode, mSize = wSize;
        if (wMode != android.view.View.MeasureSpec.EXACTLY && maxW != Integer.MAX_VALUE) {
            if (wMode == android.view.View.MeasureSpec.AT_MOST) {
                mSize = Math.min(wSize, maxW);
            } else {
                mMode = android.view.View.MeasureSpec.AT_MOST;
                mSize = maxW;
            }
        }
        final boolean horiz = macapkHorizontallyScrolling();
        final int maxLinesEff = macapkMaxLines();
        final int cap = (maxLinesEff == Integer.MAX_VALUE) ? 0 : maxLinesEff;
        final int[] t = macapkMeasureContent(macapkDisplayText(), mMode, mSize, true, cap, horiz);
        int textW = t[0];
        int desiredH = macapkDesiredHeight(t[1], t[2]);
        int hintH = -1;
        if (mHint != null && mHint.length() > 0) {
            int[] h = macapkMeasureContent(mHint, mMode, mSize, true,
                                           mEllipsize != null ? cap : 0, horiz);
            hintH = h[1];
            if (h[0] > textW) textW = h[0];
            desiredH = Math.max(desiredH, macapkDesiredHeight(h[1], h[2]));
        }
        // setText's checkForRelayout (MACAPK_TEXT_CHECK_RELAYOUT) re-runs this
        // exact text measure over the new text; record its inputs and result.
        macapkRecordRelayoutInputs(true, mMode, mSize, cap, horiz, t, hintH);
        int width;
        if (wMode == android.view.View.MeasureSpec.EXACTLY) {
            width = wSize;
        } else {
            width = textW + padL + padR;
            width = Math.min(width, maxW);
            width = Math.max(width, minW);
            width = Math.max(width, getSuggestedMinimumWidth());
            if (wMode == android.view.View.MeasureSpec.AT_MOST) width = Math.min(wSize, width);
        }
        int height;
        if (hMode == android.view.View.MeasureSpec.EXACTLY) {
            height = hSize;
        } else {
            height = desiredH;
            if (hMode == android.view.View.MeasureSpec.AT_MOST) height = Math.min(desiredH, hSize);
        }
        mMacapkRelayoutMeasuredW = width;
        setMeasuredDimension(width, height);
    }

    /** getDesiredHeight(layout, cap) from :11153 on, given the (already
     *  capped) text-block height and the layout's full line count. */
    private int macapkDesiredHeight(int layoutH, int lineCount) {
        int desired = layoutH;
        int drawH = Math.max(
            mDrawableLeft  != null ? macapkDrawableH(mDrawableLeft)  : 0,
            mDrawableRight != null ? macapkDrawableH(mDrawableRight) : 0);
        if (drawH > desired) desired = drawH;
        desired += getCompoundPaddingTop() + getCompoundPaddingBottom();
        if (!mMaxModeLines) desired = Math.min(desired, mMaximum);
        if (mMinModeLines) {
            if (lineCount < mMinimum) desired += getLineHeight() * (mMinimum - lineCount);
        } else {
            desired = Math.max(desired, mMinimum);
        }
        return Math.max(desired, getSuggestedMinimumHeight());
    }

    /** onMeasure's content-size computation for ONE CharSequence, factored out
     *  of onMeasure so the HINT can be measured through exactly the same code
     *  as the text. AOSP gets that for free because both are Layouts; we have
     *  one greedy wrap, so the sharing has to be explicit. Returns {w, h} in px,
     *  excluding compound padding (the caller adds it once).
     *
     *  NOTE the empty-input branch is not dead: AOSP measures an empty
     *  TextView one line tall, and both an empty text and an absent hint reach
     *  it. */
    private int[] macapkMeasureContent(CharSequence dispM, int wMode, int wSize) {
        return macapkMeasureContent(dispM, wMode, wSize, false, 0, false);
    }

    /** `aosp` (MACAPK_TEXT_LINES_AOSP): wrap the WHOLE text and return
     *  {w, h, lineCount}, where h is the height of the first
     *  min(lineCount, capLines) lines -- AOSP's StaticLayout.getHeight(cap),
     *  which answers mMaxLineHeight, "the height as if [the last visible line]
     *  was the last line" (StaticLayout.java:1138-1145, :1484-1494). The count
     *  stands in for the FULL one, because getDesiredHeight's minLines test
     *  reads layout.getLineCount() of a layout that maxLines alone never
     *  shortens; it is counted only up to max(capLines, minLines), which is
     *  exact for that one test. w is AOSP's desired width: the widest
     *  unwrapped paragraph, clamped to the available width.
     *  capLines <= 0 means no cap (the hint when mEllipsize == null, per
     *  getDesiredHeight's "Don't cap the hint" :11158-11161). `horiz` measures
     *  one unwrapped line per paragraph, the horizontally-scrolling layout. */
    private int[] macapkMeasureContent(CharSequence dispM, int wMode, int wSize,
                                       boolean aosp, int capLines, boolean horiz) {
            int textW = 0;
            int textH = 0;
            int lineCount = 1;
            final int cap = (capLines > 0) ? capLines : Integer.MAX_VALUE;
            if (dispM.length() > 0) {
                try {
                    android.graphics.Paint p = getPaint();
                    p.setTextSize(textSizePx);
                    applyTextStyleToPaint(p);
                    String s = dispM.toString();
                    android.graphics.Paint.FontMetricsInt fm = p.getFontMetricsInt();
                    // ⚠ v1.0.962: was `(lines - 1) * lineAdv + lineH` with BOTH
                    // terms built from macapkLineHeight(fm) -- the FULL padded
                    // (fm.bottom - fm.top) height -- used as a uniform per-line
                    // stride. AOSP only pads the very first line's top and the
                    // very last line's bottom of the whole block; every line in
                    // between advances by the raw (fm.descent - fm.ascent). See
                    // macapkLineTops's own header for the citation and the
                    // n == 1 equivalence proof. macapkBlockHeight(fm, n)
                    // replaces the old two-variable formula for every n.
                    // v1.0.402: measure multi-line height by ACTUALLY wrapping
                    // (the same greedy wrap onDraw uses), which honors explicit
                    // \n breaks. The old estimate divided the whole string's
                    // single-line width by the available width, ignoring the
                    // newlines in F-Droid's changelog / multi-paragraph
                    // description -> it under-counted lines, the view measured
                    // too short, and onDraw clipped + ellipsized the tail
                    // ("...cameras and la…"). Wrapping here makes the measured
                    // height match the rendered height so the full text shows.
                    if (!horiz && wMode != android.view.View.MeasureSpec.UNSPECIFIED && wSize > 0) {
                        int avail = Math.max(1, wSize - getCompoundPaddingLeft() - getCompoundPaddingRight());
                        // 0 = unbounded. With `aosp`, the full line count is
                        // read by exactly one test, getDesiredHeight's
                        // `linecount < mMinimum` (LINES mode), so wrapping
                        // max(cap, mMinimum) lines answers it exactly while
                        // keeping the per-character wrap bounded, the way
                        // StaticLayout itself stops at maxLines for END. A
                        // no-limit view still wraps whole (its height needs it).
                        int wrapCap = aosp
                            ? ((cap == Integer.MAX_VALUE) ? 0
                                : Math.max(cap, mMinModeLines ? mMinimum : 0))
                            : ((maxLines > 0) ? maxLines : 0);
                        // Wrap by RANGE, not by string, so the widths below can
                        // be measured span-aware from the original CharSequence.
                        java.util.ArrayList<int[]> wr = wrapRanges(s, dispM, avail, p, wrapCap);
                        java.util.ArrayList<String> wl = new java.util.ArrayList<>(wr.size());
                        for (int[] r : wr) wl.add(s.substring(r[0], r[1]));
                        int lines = Math.max(1, wl.size());
                        lineCount = lines;
                        if (aosp) lines = Math.min(lines, cap);
                        // MACAPK_TEXTVIEW_SPAN_LINES: the same span-aware line geometry onDraw
                        // paints with, so a size-span paragraph is measured as tall as it draws
                        // (AOSP getDesiredHeight is layout.getLineTop(lineCount)).
                        final float[][] spanGeomM = MACAPK_TEXTVIEW_SPAN_LINES
                            ? macapkSpanLineGeometry(dispM, getPaint(), avail, wr, lines) : null;
                        textH = (int) Math.ceil(spanGeomM != null ? spanGeomM[0][lines]
                            : macapkBlockHeight(fm, lines, wl, p));
                        // Width: the widest wrapped line (AT_MOST shrinks to it;
                        // EXACTLY ignores it). Never exceed avail.
                        final boolean spannedW = macapkHasMetricSpans(dispM);
                        final android.text.TextPaint workW = spannedW ? new android.text.TextPaint() : null;
                        float maxLineW = 0f;
                        if (aosp) {
                            // AOSP onMeasure's width is NOT the widest wrapped
                            // line: it is `des = getDesiredWidthWithLimit(...)`,
                            // the widest UNWRAPPED paragraph, clamped to
                            // widthSize under AT_MOST (:10899-10955). So any
                            // text that wraps measures the full available
                            // width, and the END ellipsis that onDraw cuts at
                            // the measured width cuts where a device does.
                            // Stops at the first paragraph that reaches avail.
                            int from = 0;
                            for (int k = 0; k <= s.length() && maxLineW < avail; k++) {
                                if (k == s.length() || s.charAt(k) == '\n') {
                                    float w = macapkRunWidth(dispM, s, from, k, p, workW, spannedW);
                                    if (w > maxLineW) maxLineW = w;
                                    from = k + 1;
                                }
                            }
                        } else {
                            for (int[] r : wr) {
                                float w = macapkRunWidth(dispM, s, r[0], r[1], p, workW, spannedW);
                                if (w > maxLineW) maxLineW = w;
                            }
                        }
                        textW = (int) Math.ceil(Math.min(maxLineW, avail));
                    } else {
                        // Unconstrained width: one line per \n-delimited run.
                        // Collects the substrings (not just a count) so the fallback-
                        // widening block height below can check each one for a
                        // codepoint outside the primary typeface's cmap, the same as
                        // the wrapped branch above does with `wl`.
                        int lines = 1;
                        float maxLineW = 0f;
                        int from = 0;
                        final boolean spannedU = macapkHasMetricSpans(dispM);
                        final android.text.TextPaint workU = spannedU ? new android.text.TextPaint() : null;
                        java.util.ArrayList<String> unwrapped = new java.util.ArrayList<>();
                        for (int k = 0; k <= s.length(); k++) {
                            if (k == s.length() || s.charAt(k) == '\n') {
                                String seg = s.substring(from, k);
                                unwrapped.add(seg);
                                float w = macapkRunWidth(dispM, s, from, k, p, workU, spannedU);
                                if (w > maxLineW) maxLineW = w;
                                if (k < s.length()) lines++;
                                from = k + 1;
                            }
                        }
                        lineCount = lines;
                        if (aosp) lines = Math.min(lines, cap);
                        else if (maxLines > 0 && lines > maxLines) lines = maxLines;
                        textW = (int) Math.ceil(maxLineW);
                        textH = (int) Math.ceil(macapkBlockHeight(fm, lines, unwrapped, p));
                    }
                } catch (Throwable ignored) {
                    textW = dispM.length() * Math.max(8, (int) (textSizePx * 0.55f));
                    textH = (int) (textSizePx > 0 ? textSizePx * 1.2f : 18f);
                }
            } else {
                // lane layout, 2026-08-01: AOSP measures an EMPTY TextView ONE
                // LINE tall, not zero -- mLayout over "" still has one line, so
                // the desired height is the line height (+ padding below). Ours
                // measured 0, which was flagged as the open divergence in
                // docs/board-layout_2026-07-30.md (Sgt Puzzles' status_bar) and
                // is half of why an icon-only ActionMenuItemView (a TextView
                // subclass with text='') collapsed to 48x0 and the toolbar
                // search icon vanished (AntennaPod tree dump, 2026-08-01).
                try {
                    android.graphics.Paint p = getPaint();
                    p.setTextSize(textSizePx);
                    applyTextStyleToPaint(p);
                    android.graphics.Paint.FontMetricsInt fm = p.getFontMetricsInt();
                    textH = (int) Math.ceil(macapkLineHeight(fm));
                } catch (Throwable ignored) {
                    textH = (int) (textSizePx > 0 ? textSizePx * 1.2f : 18f);
                }
            }
        return new int[] { textW, textH, lineCount };
    }

    /** v1.0.117: word-wrap with maxLines + ellipsize. AOSP's
     *  TextView uses StaticLayout for multi-line text shaping; we do
     *  a simplified greedy wrap (split on spaces, fit per measureText)
     *  with end-ellipsize on the last line when maxLines is exceeded.
     *  Visible improvement on F-Droid: card summary texts no longer
     *  overflow into neighbor cells; long names properly truncate
     *  with ellipsis. */
    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (canvas == null) return;
        // Draw leading/trailing/top/bottom icons FIRST, before the empty-text
        // early-return -- an icon-only ActionMenuItemView has no text but must
        // still paint its compound-drawable icon.
        macapkDrawCompoundDrawables(canvas);
        // THE CARET, before the text and before any empty-text early return:
        // AOSP's Editor.onDraw paints the cursor drawable (and Layout.draw the
        // cursor path) UNDER the glyphs, and an empty focused field still shows
        // its insertion point. Draws nothing, and so marks no ink, on the off
        // half of the blink or when no caret applies.
        if (mEditor != null) macapkDrawCursor(canvas);
        // THE PIXELS COME FROM mTransformed, NEVER FROM mText. AOSP's onDraw
        // hands mLayout.draw(canvas) the layout it built over mTransformed
        // (TextView.java:9529-9530). Reading `text` here is exactly the bug
        // that rendered password fields in plain text.
        CharSequence disp = macapkDisplayText();
        // THE HINT. AOSP onDraw (TextView.java:7003-7016):
        //     int color = mCurTextColor;
        //     Layout layout = mLayout;
        //     if (mHint != null && !mHideHint && mText.length() == 0) {
        //         if (mHintTextColor != null) color = mCurHintTextColor;
        //         layout = mHintLayout;
        //     }
        //     mTextPaint.setColor(color);
        // We have no separate hint Layout to swap in, so we mirror the RESULT:
        // the hint goes down the SAME wrap/ellipsize/gravity/draw path the real
        // text takes, at the hint colour. Two details worth keeping straight:
        //   - the condition is on the RAW text being empty (AOSP tests
        //     mText.length()), not on the transformed text;
        //   - AOSP builds mHintLayout over mHint ITSELF (:11006-11023), never
        //     over a transformed copy, so android:textAllCaps and a password
        //     TransformationMethod do NOT apply to a hint. Reading mHint
        //     directly here reproduces that.
        // Until this landed, onDraw early-returned on empty text before ever
        // consulting mHint, so EVERY placeholder in every search box, login
        // field and form in every app painted nothing.
        int drawColor = textColor;
        if ((text == null || text.length() == 0) && mHint != null && mHint.length() > 0) {
            disp = mHint;
            drawColor = getCurrentHintTextColor();
        }
        if (disp.length() == 0) return;
        android.text.TextPaint p = getPaint();
        p.setColor(drawColor);
        p.setTextSize(textSizePx);
        applyTextStyleToPaint(p);
        // Apply (or clear) the text drop shadow on the shared draw Paint
        // so both the single-line and word-wrapped drawText paths below
        // render it. Clearing when unset keeps no-shadow TextViews byte-
        // identical to before (Canvas.drawText skips the shadow pass).
        if (shadowColor != 0 && (shadowRadius > 0f || shadowDx != 0f || shadowDy != 0f)) {
            p.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);
        } else {
            p.clearShadowLayer();
        }
        int padL = getCompoundPaddingLeft();
        int padT = getCompoundPaddingTop();
        int padR = getCompoundPaddingRight();
        int padB = getCompoundPaddingBottom();
        final int viewW0 = getWidth() - padL - padR;
        final int viewH = getHeight() - padT - padB;
        String s = disp.toString();
        // THE DRAW-TIME GEOMETRY: the font metrics, the span-aware widths, the
        // one-line decision, and the ellipsized line with its vertical metrics
        // (macapkComputeDrawGeom). Gate MACAPK_TEXT_DRAW_MEMO (default ON):
        // reused across draws while every input it was derived from is
        // unchanged, which is what AOSP's onDraw gets from drawing its cached
        // mLayout (see macapkDrawGeomMemo). Gate OFF, or text whose widths a
        // memo cannot vouch for (a custom or replacement metric span): derived
        // afresh on every draw, exactly as before.
        MacapkDrawGeom geom = MACAPK_TEXT_DRAW_MEMO
            ? macapkDrawGeomMemo(disp, s, p, viewW0, viewH) : null;
        final android.text.Spanned styled;
        if (geom != null) {
            // This draw's own answer, from the span walk the memo check made.
            styled = mDmStyled;
        } else {
            geom = macapkComputeDrawGeom(disp, s, p, viewW0, viewH);
            // The text as a Spanned iff it actually carries character-level
            // styling; null (the common case) keeps the plain single-drawText
            // path below completely unchanged.
            styled = macapkStyledText(disp);
        }
        final android.graphics.Paint.FontMetricsInt fm = geom.fm;
        final boolean spannedD = geom.spannedD;
        final int viewW = geom.viewW;
        if (!geom.quick && MACAPK_TEXT_LINES_AOSP) {
            // A memo hit whose wrap memo was built under THIS geometry: its
            // lines, widths and tops are still exact, so only gravity and the
            // glyphs remain (macapkPaintWrapped). Only ever true under the gate.
            if (geom == mDmWrapFor && mDrawCacheLines != null) {
                macapkPaintWrapped(canvas, styled, p, fm, padL, padT, viewW, viewH);
                return;
            }
            final android.text.TextPaint workD = spannedD ? new android.text.TextPaint() : null;
            macapkDrawWrappedAosp(canvas, disp, s, styled, p, fm, padL, padT, viewW, viewH,
                                  spannedD, workD);
            if (geom == mDrawGeom && mDrawCacheLines != null) mDmWrapFor = geom;
            return;
        }
        if (geom.quick) {
            // Gravity is read per draw, never memoised: AOSP's setGravity only
            // rebuilds the layout for a HORIZONTAL change (TextView.java:5631-
            // 5659), and here it is a pure offset over the cached width.
            float x = padL + horizontalGravityOffset(viewW, geom.textW);
            float baselineY = padT + verticalGravityOffset(viewH, geom.qLineH) + geom.qAscent;
            // An un-ellipsized line wider than the view is CLIPPED at the
            // compound padding, exactly as AOSP's onDraw clips every layout
            // (`canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)`,
            // clipLeft/Right = the compound padding, TextView.java:9017-9031).
            // Only this case needs it: every other line fits its box.
            final boolean clip = MACAPK_TEXT_LINES_AOSP && geom.textW > viewW;
            if (clip) {
                canvas.save();
                canvas.clipRect((float) padL, 0f, (float) (padL + viewW), (float) getHeight());
            }
            macapkDrawLine(canvas, styled, geom.drawStr, 0, geom.srcCount, x, baselineY, p);
            if (clip) canvas.restore();
            return;
        }
        // MACAPK_TEXT_LINES_AOSP=0 only: the pre-AOSP greedy wrap, re-derived
        // on every draw as it always was.
        final android.text.TextPaint workD = spannedD ? new android.text.TextPaint() : null;
        // Multi-line: word-wrap greedy.
        int maxL = (maxLines <= 0) ? 16 : maxLines;
        // Bound by viewH too (don't draw past view bottom). ⚠ v1.0.962: was
        // `floor((viewH - lineH) / lineAdv) + 1`, the closed form for a
        // UNIFORM per-line stride -- the same padded-stride bug
        // macapkBlockHeight's header describes. The AOSP-correct height is
        // no longer a uniform stride (only the very first/last line of the
        // block are padded), so there is no closed form; maxL is already
        // small (capped at 16), so a linear search for the largest n with
        // macapkBlockHeight(fm, n) <= viewH is cheap and exact.
        int linesByH = maxL;
        if (viewH > 0) {
            linesByH = 1;
            // lines=null, paint=null here on purpose: this is a bound on HOW
            // MANY lines might fit before wrapRanges below has decided what
            // text lands on which line, so there is no per-line text yet to
            // check for a fallback glyph. macapkLineVertical(..., null, null)
            // degrades to the primary-only fm, i.e. the pre-widening bound --
            // at worst this search is off by a line in the rare case where
            // viewH is tight AND every wrapped line needs a fallback face,
            // which the actual per-line placement below (macapkLineTops,
            // fully fallback-aware) still draws correctly.
            while (linesByH < maxL && macapkBlockHeight(fm, linesByH + 1, null, null) <= viewH) linesByH++;
        }
        maxL = Math.min(maxL, linesByH);
        java.util.ArrayList<int[]> ranges = wrapRanges(s, disp, viewW, p, maxL);
        java.util.ArrayList<String> lines = new java.util.ArrayList<>(ranges.size());
        for (int[] r : ranges) lines.add(s.substring(r[0], r[1]));
        // Per-line count of characters that map back to the source (the last
        // line loses some to the end-ellipsis appended below).
        int[] srcCounts = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) srcCounts[i] = lines.get(i).length();
        // Apply end-ellipsize to the LAST visible line when the text overflows
        // the maxLines box (AOSP TextUtils.TruncateAt.END). v1.0.406: truncate
        // the last WRAPPED line in place + "…". The previous version recomputed
        // the remainder via `s.substring(sum-of-line-lengths)`, but
        // sum-of-line-lengths UNDERCOUNTS by the spaces wrapText strips at each
        // break -> the last line started a few chars early with a stray leading
        // space (F-Droid's collapsed description rendered "  premium…Material 3
        // UI…" instead of flush-left "premium…"). The "+ maxL" slack avoids a
        // false ellipsis when the text fits in exactly maxL lines.
        if (lines.size() == maxL && totalLength(lines) + maxL < s.length()) {
            String last = lines.get(maxL - 1);
            float ellW = p.measureText("…");
            int end = last.length();
            while (end > 0 && p.measureText(last.substring(0, end)) + ellW > viewW) end--;
            String head = last.substring(0, end);
            while (head.length() > 0 && head.charAt(head.length() - 1) == ' ')
                head = head.substring(0, head.length() - 1);
            lines.set(maxL - 1, head + "…");
            srcCounts[maxL - 1] = head.length();
        }
        // ⚠ v1.0.962: per-line baselines now come from macapkLineTops(fm, n),
        // NOT a uniform `baselineY + i * lineAdv` stride -- see that
        // method's header for the AOSP citation and why the old stride made
        // an n-line block (n - 1) advances too tall. blockTop is gravity-
        // positioned against the block's TOTAL height (macapkBlockHeight,
        // the same n-line total onMeasure now uses), so CENTER/BOTTOM
        // gravity still lands the whole block correctly.
        int nLines = lines.size();
        float[] tops = macapkLineTops(fm, nLines, lines, p);
        float blockTop = padT + verticalGravityOffset(viewH, tops[nLines]);
        for (int i = 0; i < nLines; i++) {
            String line = lines.get(i);
            // Measure BY OFFSET so the gravity offset matches what gets painted;
            // an ellipsized last line is a synthesized string whose spans no
            // longer line up, so that one keeps the plain measure.
            int[] rg = ranges.get(i);
            float lineW = (line.length() == rg[1] - rg[0])
                ? macapkRunWidth(disp, s, rg[0], rg[1], p, workD, spannedD)
                : p.measureText(line);
            float x = padL + horizontalGravityOffset(viewW, lineW);
            float baselineY = blockTop + tops[i] - macapkLineTopAbove(fm, i, lines, p);
            macapkDrawLine(canvas, styled, line, ranges.get(i)[0], srcCounts[i],
                x, baselineY, p);
        }
    }

    // ---- the draw-geometry memo (MACAPK_TEXT_DRAW_MEMO) ----------------------
    //
    // WHY. AOSP's onDraw measures nothing. It draws the layout it already has:
    //
    //     if (mLayout == null) { assumeLayout(); }
    //     Layout layout = mLayout;
    //     ...
    //     layout.draw(canvas, mHighlightPaths, mHighlightPaints, highlight,
    //             mHighlightPaint, cursorOffsetVertical);
    //
    // (android14-release core/java/android/widget/TextView.java:8989-8993,
    // :9070-9071), and Layout.drawText walks the line starts, tops and
    // descents stored when that layout was built (Layout.java:592-620); a
    // BoringLayout even keeps its one width (`return mMax;`,
    // BoringLayout.java:549-550). The layout is rebuilt only when an input
    // changes: every setter that touches one ends `nullLayouts();
    // requestLayout(); invalidate();` -- setRawTextSize (:4649-4661),
    // setTypeface (:4735-4742), setPaintFlags (:5688-5697), setEllipsize
    // (:12019-12029), setLetterSpacing / setFontFeatureSettings / setTextLocales
    // likewise -- onMeasure rebuilds it when the width or the line limit moved
    // (`layoutChanged = (mLayout.getWidth() != want) || ...`, `maximumChanged`,
    // :10972-10983), setText rebuilds it through checkForRelayout (:11246),
    // and a span change on a Spannable reaches spanChange (:12531-12536), whose
    // DynamicLayout reflows. setGravity only rebuilds for a HORIZONTAL change
    // (:5631-5659).
    //
    // Ours re-derived all of it on every draw -- getFontMetricsInt, a full-text
    // measureText, the fallback-face walk per codepoint, and for an ellipsized
    // line a binary search of measureText calls -- which is 1.75-3.7 ms of pure
    // logic per frame for 28 TextViews (docs/display-list-rendernode-design_
    // 2026-09-26.md, M1 and side finding 1) and the dominant cost of a damage-
    // clipped walk on a text screen.
    //
    // HOW. There is no setter hook here to miss: the memo is keyed on the
    // DERIVED inputs as onDraw sees them, compared on every draw, so it cannot
    // go stale through a path nobody thought to invalidate from:
    //   - the drawn text's CONTENT (mTransformed, or the hint) and its
    //     CharacterStyle span set with each span's range (a span added, moved
    //     or removed on the same Spannable is a change, as it is for
    //     DynamicLayout);
    //   - every Paint property the measuring reads, AFTER onDraw has applied
    //     this view's own size / typeface / style to it -- so a direct
    //     getPaint().setLetterSpacing(...), which AOSP does NOT invalidate on
    //     ("Use this only to consult the Paint's properties and not to change
    //     them", TextView.java:5459), is a change here too;
    //   - the box (width and height inside the compound padding), the line
    //     limit, singleLine, the effective ellipsize, includeFontPadding, the
    //     line spacing, and the fallback-line-spacing latch.
    // Gravity, alignment, colour, shadow and the canvas are read per draw and
    // never cached, so a draw from the memo is the same draw, pixel for pixel
    // (debug/w9textdraw_gate.sh proves it against a freshly built view).
    //
    // NOT CACHED: a text carrying a metric span that is not one of the
    // framework's own immutable android.text.style classes, or any
    // ReplacementSpan (ImageSpan, an emoji span). Their widths can change
    // without the span set changing -- a drawable's bounds, a custom span's
    // own state -- so their geometry is derived on every draw as before.
    //
    // MACAPK_TEXT_DRAW_MEMO=0 derives everything on every draw, the
    // pre-change path.
    static final boolean MACAPK_TEXT_DRAW_MEMO =
        !"0".equals(System.getenv("MACAPK_TEXT_DRAW_MEMO"));

    /** What onDraw derives from the text, the paint and the box, plus the key
     *  it was derived under. The value half is exactly the locals the per-draw
     *  path used to compute, so painting from it cannot differ. */
    private static final class MacapkDrawGeom {
        // ---- value ----
        android.graphics.Paint.FontMetricsInt fm;
        boolean spannedD;       // macapkHasMetricSpans(disp)
        int viewW;              // the box width, or the text's own width when <= 0
        boolean quick;          // the one-line path
        String drawStr;         // quick: the line as drawn (ellipsized or not)
        int srcCount;           // quick: leading chars of drawStr that map to the source
        float qLineH, qAscent;  // quick: the line's height and top-to-baseline
        float textW;            // quick: the width gravity positions by
        // ---- key ----
        boolean cacheable;      // false: the key is kept only to invalidate the wrap memo
        String kText;
        Object[] kSpans;
        int[] kRanges;
        int kViewW, kViewH;
        float kSize, kSkew, kScale, kLetter, kWord;
        android.graphics.Typeface kTypeface;
        int kFlags, kHinting, kStartHy, kEndHy;
        boolean kElegant;
        String kFeatures, kVariations;
        java.util.Locale kLocale;
        int kMaxLinesRaw, kMaxLines;
        boolean kSingleLine, kIncludePad, kFallback;
        android.text.TextUtils.TruncateAt kEff;
        float kSpacingMult, kSpacingAdd;
    }
    private static final Object[] MACAPK_DM_NO_SPANS = new Object[0];
    private static final int[] MACAPK_DM_NO_RANGES = new int[0];
    /** The memo (null until the first memoised draw, and after a text the memo
     *  cannot use drops it). */
    private MacapkDrawGeom mDrawGeom;
    /** The geometry the wrap memo (mDrawCache*) was last completed under. */
    private MacapkDrawGeom mDmWrapFor;
    /** macapkStyledText's answer for THIS draw, from the memo's span walk. */
    private android.text.Spanned mDmStyled;
    /** How many times the memo was (re)derived. Read by debug/w9textdraw_gate.sh
     *  to prove a steady redraw is served from the memo and a change is not. */
    private int mDmComputes;

    /** The geometry for this draw from the memo, re-derived first if any input
     *  changed; null when the text cannot be memoised (the caller derives it
     *  per draw). */
    private MacapkDrawGeom macapkDrawGeomMemo(CharSequence disp, String s,
                                              android.text.TextPaint p, int viewW0, int viewH) {
        android.text.Spanned sp = null;
        android.text.style.CharacterStyle[] cs = null;
        if (disp instanceof android.text.Spanned) {
            sp = (android.text.Spanned) disp;
            try {
                cs = sp.getSpans(0, sp.length(), android.text.style.CharacterStyle.class);
            } catch (Throwable t) {
                // macapkStyledText swallows the same throw; so does the per-draw path.
                macapkDmDrop();
                return null;
            }
        }
        final boolean cacheable = macapkDmSpansCacheable(cs);
        mDmStyled = (cs != null && cs.length > 0) ? sp : null;
        MacapkDrawGeom g = mDrawGeom;
        if (g != null && macapkDmKeySame(g, s, sp, cs, p, viewW0, viewH)) {
            return g.cacheable ? g : null;
        }
        // Some input moved: nothing derived from the old inputs may be reused,
        // including the wrap memo, whose own key never looked at the spans.
        macapkDmClearWrapMemo();
        mDmWrapFor = null;
        mDmComputes++;
        g = cacheable ? macapkComputeDrawGeom(disp, s, p, viewW0, viewH) : new MacapkDrawGeom();
        g.cacheable = cacheable;
        macapkDmRecordKey(g, s, sp, cs, p, viewW0, viewH);
        mDrawGeom = g;
        return cacheable ? g : null;
    }

    private void macapkDmDrop() {
        mDrawGeom = null;
        mDmWrapFor = null;
        mDmStyled = null;
        macapkDmClearWrapMemo();
    }

    private void macapkDmClearWrapMemo() {
        mWrapCacheDisp = null;
        mWrapCacheText = null;
        mWrapCacheKey = null;
        mWrapCacheRanges = null;
        mDrawCacheKey = null;
        mDrawCacheLines = null;
    }

    /** The per-draw derivation (the body onDraw ran on every draw before the
     *  memo; MACAPK_TEXT_DRAW_MEMO=0 still runs it on every draw). */
    private MacapkDrawGeom macapkComputeDrawGeom(CharSequence disp, String s,
                                                 android.text.TextPaint p, int viewW0, int viewH) {
        final MacapkDrawGeom g = new MacapkDrawGeom();
        g.fm = p.getFontMetricsInt();
        int viewW = viewW0;
        // Span-aware widths for this whole draw pass: `disp` may style its
        // metrics through spans, and measuring the plain String would size and
        // wrap in the base font while macapkDrawLine paints in the span's.
        // Computed ONCE per derivation, never per character.
        final boolean spannedD = macapkHasMetricSpans(disp);
        final android.text.TextPaint workD = spannedD ? new android.text.TextPaint() : null;
        if (viewW <= 0) viewW = (int) macapkRunWidth(disp, s, 0, s.length(), p, workD, spannedD);
        // Quick path: single-line text that fits.
        float fullW = macapkRunWidth(disp, s, 0, s.length(), p, workD, spannedD);
        // Gate ON: the one-line path is for a HORIZONTALLY SCROLLING view
        // (singleLine) or for text that is genuinely one line. maxLines == 1
        // on its own no longer lands here: AOSP still word-wraps that layout
        // and shows its first line, ellipsized only when mEllipsize asks
        // (StaticLayout.generate :873-898, out() :1063-1081).
        final boolean quick = MACAPK_TEXT_LINES_AOSP
            ? (macapkHorizontallyScrolling() || (fullW <= viewW && s.indexOf('\n') < 0))
            : (fullW <= viewW || maxLines == 1);
        g.spannedD = spannedD;
        g.viewW = viewW;
        g.quick = quick;
        if (!quick) return g;
        String drawStr = s;
        int qSrc = -1;   // chars of drawStr that map to the source; -1 = derive below
        if (MACAPK_TEXT_LINES_AOSP) {
            // AOSP draws an ellipsis only through the layout's ellipsize
            // mode; with none, the line is simply clipped at the edge. A
            // MARQUEE that reaches the layout as MARQUEE (programmatic
            // setEllipsize, see macapkEffectiveEllipsize) is never
            // ellipsized either: BoringLayout.init treats it like null.
            final android.text.TextUtils.TruncateAt eff = macapkEffectiveEllipsize();
            if (fullW > viewW && eff != null
                    && eff != android.text.TextUtils.TruncateAt.MARQUEE) {
                if (eff == android.text.TextUtils.TruncateAt.START
                        || eff == android.text.TextUtils.TruncateAt.MIDDLE) {
                    CharSequence e = android.text.TextUtils.ellipsize(s, p, viewW, eff);
                    drawStr = (e == null) ? "" : e.toString();
                    qSrc = 0;    // the kept runs no longer start at offset 0
                } else {
                    int keep = macapkEndEllipsisKeep(disp, s, 0, s.length(), p, workD,
                        spannedD, viewW, macapkEllipsisString(eff));
                    drawStr = s.substring(0, keep) + macapkEllipsisString(eff);
                    qSrc = keep;
                }
            }
        } else if (fullW > viewW && maxLines == 1) {
            drawStr = ellipsize(s, viewW, p);
        }
        // Single line's own height, under the current includeFontPadding
        // flag AND widened for a fallback-font glyph in drawStr, exactly
        // as macapkBlockHeight(fm, 1, ..., p) widens onMeasure's height for
        // the same one-line text (macapkLineTops' header: a one-line block
        // is simultaneously first AND last, so this quick path and that
        // n==1 call must agree, or onMeasure would allocate more height
        // than onDraw ever uses). Computed from drawStr (post-ellipsize),
        // not the source `s` -- the fallback face only matters for glyphs
        // actually drawn.
        int[] qm = macapkLineVertical(g.fm, 0, java.util.Collections.singletonList(drawStr), p);
        g.qLineH = mIncludePad ? (qm[3] - qm[2]) : (qm[1] - qm[0]);
        g.qAscent = -(mIncludePad ? qm[2] : qm[0]);
        // Gate MACAPK_TEXT_CARET: an un-ellipsized line is positioned by its
        // SPAN-AWARE width (fullW), the width macapkDrawLine paints and the
        // layout the caret reads from measures. The base-paint measure put a
        // right/centre-aligned bold or size-spanned line short of its edge,
        // so the caret and the glyphs disagreed by the span's delta.
        // Unspanned text: fullW IS p.measureText(s) (macapkRunWidth's header).
        g.textW = (MACAPK_TEXT_CARET && drawStr == s) ? fullW : p.measureText(drawStr);
        // srcCount = how many leading chars of drawStr map back to the
        // source text; the ellipsize() tail ("…") has no source offset.
        g.srcCount = (qSrc >= 0) ? qSrc
                   : (drawStr == s) ? drawStr.length()
                                    : Math.max(0, drawStr.length() - 1);
        g.drawStr = drawStr;
        return g;
    }

    /** Can this span set's widths be memoised? Every metric span must be one of
     *  the framework's own android.text.style classes (their measure state is
     *  fixed at construction) and none may be a ReplacementSpan, whose getSize
     *  may read state that changes under an unchanged span (an ImageSpan's
     *  drawable bounds). Non-metric CharacterStyles never reach the geometry. */
    private static boolean macapkDmSpansCacheable(android.text.style.CharacterStyle[] cs) {
        if (cs == null) return true;
        for (android.text.style.CharacterStyle c : cs) {
            if (!(c instanceof android.text.style.MetricAffectingSpan)) continue;
            final android.text.style.MetricAffectingSpan u;
            try {
                u = ((android.text.style.MetricAffectingSpan) c).getUnderlying();
            } catch (Throwable t) {
                return false;
            }
            if (u instanceof android.text.style.ReplacementSpan
                    || c instanceof android.text.style.ReplacementSpan) return false;
            if (!macapkDmFrameworkSpan(c.getClass())
                    || (u != null && !macapkDmFrameworkSpan(u.getClass()))) return false;
        }
        return true;
    }
    private static boolean macapkDmFrameworkSpan(Class<?> c) {
        return c.getClassLoader() == TextView.class.getClassLoader()
            && c.getName().startsWith("android.text.style.");
    }

    /** Every input of macapkComputeDrawGeom, as this draw sees it, against the
     *  recorded key. One line per input class; debug/w9textdraw_gate.sh builds
     *  its positive controls by neutralising exactly one of these lines. */
    private boolean macapkDmKeySame(MacapkDrawGeom g, String s, android.text.Spanned sp,
                                    android.text.style.CharacterStyle[] cs,
                                    android.text.TextPaint p, int viewW0, int viewH) {
        if (!s.equals(g.kText)) return false;                                   // W9-KEY-TEXT
        if (!macapkDmSpansSame(g, sp, cs)) return false;                        // W9-KEY-SPANS
        if (!macapkDmPaintSame(g, p)) return false;                             // W9-KEY-PAINT
        if (g.kViewW != viewW0 || g.kViewH != viewH) return false;              // W9-KEY-BOX
        if (!macapkDmStateSame(g)) return false;                                // W9-KEY-STATE
        return true;
    }

    private static boolean macapkDmSpansSame(MacapkDrawGeom g, android.text.Spanned sp,
                                             android.text.style.CharacterStyle[] cs) {
        final int n = (cs == null) ? 0 : cs.length;
        if (n != g.kSpans.length) return false;
        for (int i = 0; i < n; i++) {
            final Object o = cs[i];
            if (o != g.kSpans[i]
                    || sp.getSpanStart(o) != g.kRanges[2 * i]
                    || sp.getSpanEnd(o) != g.kRanges[2 * i + 1]) return false;
        }
        return true;
    }

    /** Every Paint property the derivation measures through: the size, face and
     *  synthetic style (the font), scaleX / skew / letter and word spacing (the
     *  advances), and the rest of what equalsForTextMeasurement names. Exact
     *  bit compares: equalsForTextMeasurement itself truncates the size the way
     *  minikin does, but our measure reads the float, so it is too coarse to
     *  key on. */
    private static boolean macapkDmPaintSame(MacapkDrawGeom g, android.graphics.Paint p) {
        return Float.floatToIntBits(g.kSize) == Float.floatToIntBits(p.getTextSize())
            && g.kTypeface == p.getTypeface()
            && g.kFlags == p.getFlags()
            && Float.floatToIntBits(g.kSkew) == Float.floatToIntBits(p.getTextSkewX())
            && Float.floatToIntBits(g.kScale) == Float.floatToIntBits(p.getTextScaleX())
            && Float.floatToIntBits(g.kLetter) == Float.floatToIntBits(p.getLetterSpacing())
            && Float.floatToIntBits(g.kWord) == Float.floatToIntBits(p.getWordSpacing())
            && g.kHinting == p.getHinting()
            && g.kElegant == p.isElegantTextHeight()
            && g.kStartHy == p.getStartHyphenEdit()
            && g.kEndHy == p.getEndHyphenEdit()
            && java.util.Objects.equals(g.kFeatures, p.getFontFeatureSettings())
            && java.util.Objects.equals(g.kVariations, p.getFontVariationSettings())
            && java.util.Objects.equals(g.kLocale, p.getTextLocale());
    }

    private boolean macapkDmStateSame(MacapkDrawGeom g) {
        return g.kMaxLinesRaw == maxLines
            && g.kMaxLines == macapkMaxLines()
            && g.kSingleLine == macapkSingleLine
            && g.kEff == macapkEffectiveEllipsize()
            && g.kIncludePad == mIncludePad
            && Float.floatToIntBits(g.kSpacingMult) == Float.floatToIntBits(mSpacingMult)
            && Float.floatToIntBits(g.kSpacingAdd) == Float.floatToIntBits(mSpacingAdd)
            && g.kFallback == macapkUseFallbackLineSpacing();
    }

    private void macapkDmRecordKey(MacapkDrawGeom g, String s, android.text.Spanned sp,
                                   android.text.style.CharacterStyle[] cs,
                                   android.text.TextPaint p, int viewW0, int viewH) {
        g.kText = s;
        final int n = (cs == null) ? 0 : cs.length;
        if (n == 0) {
            g.kSpans = MACAPK_DM_NO_SPANS;
            g.kRanges = MACAPK_DM_NO_RANGES;
        } else {
            g.kSpans = cs.clone();
            g.kRanges = new int[2 * n];
            for (int i = 0; i < n; i++) {
                g.kRanges[2 * i] = sp.getSpanStart(cs[i]);
                g.kRanges[2 * i + 1] = sp.getSpanEnd(cs[i]);
            }
        }
        g.kViewW = viewW0;
        g.kViewH = viewH;
        g.kSize = p.getTextSize();
        g.kTypeface = p.getTypeface();
        g.kFlags = p.getFlags();
        g.kSkew = p.getTextSkewX();
        g.kScale = p.getTextScaleX();
        g.kLetter = p.getLetterSpacing();
        g.kWord = p.getWordSpacing();
        g.kHinting = p.getHinting();
        g.kElegant = p.isElegantTextHeight();
        g.kStartHy = p.getStartHyphenEdit();
        g.kEndHy = p.getEndHyphenEdit();
        g.kFeatures = p.getFontFeatureSettings();
        g.kVariations = p.getFontVariationSettings();
        g.kLocale = p.getTextLocale();
        g.kMaxLinesRaw = maxLines;
        g.kMaxLines = macapkMaxLines();
        g.kSingleLine = macapkSingleLine;
        g.kEff = macapkEffectiveEllipsize();
        g.kIncludePad = mIncludePad;
        g.kSpacingMult = mSpacingMult;
        g.kSpacingAdd = mSpacingAdd;
        g.kFallback = macapkUseFallbackLineSpacing();
    }

    // macapkDrawWrappedAosp's wrap memo (see there).
    private CharSequence mWrapCacheDisp;
    private String mWrapCacheText, mWrapCacheKey;
    private java.util.ArrayList<int[]> mWrapCacheRanges;
    // ... and its draw memo: the visible lines after the ellipsis walk, keyed
    // by mDrawCacheKey on top of the wrap memo's text identity.
    private String mDrawCacheKey;
    private java.util.ArrayList<int[]> mDrawCacheRanges;
    private java.util.ArrayList<String> mDrawCacheLines;
    private int[] mDrawCacheSrcCounts;
    private float[] mDrawCacheLineW, mDrawCacheTops, mDrawCacheAbove;

    /** TextUtils.getEllipsisString (android14-release TextUtils.java:138-140):
     *  END_SMALL, which is what a non-fading MARQUEE becomes, uses the TWO DOT
     *  LEADER; every other mode the horizontal ellipsis. */
    private static String macapkEllipsisString(android.text.TextUtils.TruncateAt where) {
        return (where == android.text.TextUtils.TruncateAt.END_SMALL) ? "\u2025" : "\u2026";
    }

    /** StaticLayout.calculateEllipsis for END / END_SMALL (:1176-1235), over
     *  [start, end) of `s`: how many leading chars stay before the ellipsis.
     *  AOSP walks per-char widths and stops at the first char where
     *  `w + sum + ellipsisWidth > avail`; the prefix widths are monotone, so a
     *  binary search over them finds the same index.
     *  There is deliberately no forceEllipsis parameter. AOSP forces only when
     *  more text follows the last visible line, and then that line is a merged
     *  PARAGRAPH whose range INCLUDES its '\n' (generate's paraEnd), so
     *  `ellipsisStart = len - 1; ellipsisCount = 1` replaces the zero-width
     *  newline, never a glyph. Callers pass the range WITHOUT the '\n' and
     *  always append the ellipsis, which puts it in that same slot. */
    private static int macapkEndEllipsisKeep(CharSequence src, String s, int start, int end,
                                             android.graphics.Paint p,
                                             android.text.TextPaint work, boolean spanned,
                                             float avail, String ell) {
        final int len = end - start;
        final float ellW = p.measureText(ell);
        int lo = 0, hi = len;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (macapkRunWidth(src, s, start, start + mid, p, work, spanned) + ellW <= avail) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** Multi-line draw under MACAPK_TEXT_LINES_AOSP. Mirrors what a StaticLayout
     *  built by makeSingleLayout (:10747-10762) shows through TextView's clip:
     *   - the text is wrapped WHOLE; maxLines only decides how many of those
     *     lines are visible (getExtendedPaddingTop/Bottom place the visible
     *     block, :2923-2990), it does not shorten the layout;
     *   - lines are merged and ellipsized ONLY when
     *         ellipsisMayBeApplied = ellipsize != null
     *             && (ellipsize == END
     *                 || (mMaximumVisibleLineCount == 1 && ellipsize != MARQUEE))
     *     (StaticLayout.generate :873-898). A multi-line MARQUEE (F-Droid's
     *     description, setMaxLines(5) + MARQUEE) therefore draws five plain
     *     lines with no ellipsis, and a view with no ellipsize never gets one;
     *   - the merge folds the rest of the PARAGRAPH into the last visible line
     *     and calculateEllipsis cuts it by width (so it reads "No acco...", not
     *     "No..."), forced when more text follows (out() :1066-1078) -- into
     *     the paragraph's newline slot, never over a glyph.
     *  Lines that do not fit the view height are not drawn, as before. */
    private void macapkDrawWrappedAosp(android.graphics.Canvas canvas, CharSequence disp, String s,
                                       android.text.Spanned styled, android.text.TextPaint p,
                                       android.graphics.Paint.FontMetricsInt fm,
                                       int padL, int padT, int viewW, int viewH,
                                       boolean spannedD, android.text.TextPaint workD) {
        final int cap = macapkMaxLines();
        final android.text.TextUtils.TruncateAt eff = macapkEffectiveEllipsize();
        // The WHOLE text is wrapped here (the old path wrapped only up to
        // maxLines, and stopped at 16 lines only for a non-positive maxLines),
        // and onDraw can run every frame, so the break result is memoised on
        // everything the greedy wrap reads: the text, the width and the paint
        // state applyTextStyleToPaint derived from this view.
        final String wrapKey = viewW + "|" + p.getTextSize() + "|" + macapkTextStyle + "|"
            + System.identityHashCode(p.getTypeface()) + "|" + p.getLetterSpacing() + "|"
            + p.getTextScaleX() + "|" + System.identityHashCode(disp);
        // Everything derived from the wrap as well -- the visible-line count,
        // the ellipsis walk, each line's width and the line tops -- is memoised
        // on the wrap key plus what else those read (the line limit, the
        // effective ellipsize, the view height, and the vertical metrics
        // inputs), so a steady view does no measuring at all per frame.
        final String drawKey = wrapKey + "|" + cap + "|" + eff + "|" + viewH + "|"
            + mIncludePad + "|" + mSpacingMult + "|" + mSpacingAdd + "|"
            + fm.top + "," + fm.ascent + "," + fm.descent + "," + fm.bottom;
        if (disp == mWrapCacheDisp && s.equals(mWrapCacheText) && drawKey.equals(mDrawCacheKey)
                && mDrawCacheLines != null) {
            macapkPaintWrapped(canvas, styled, p, fm, padL, padT, viewW, viewH);
            return;
        }
        java.util.ArrayList<int[]> all;
        if (disp == mWrapCacheDisp && s.equals(mWrapCacheText) && wrapKey.equals(mWrapCacheKey)) {
            all = mWrapCacheRanges;
        } else {
            all = wrapRanges(s, disp, viewW, p, 0);
            mWrapCacheDisp = disp; mWrapCacheText = s; mWrapCacheKey = wrapKey; mWrapCacheRanges = all;
        }
        mDrawCacheKey = null;
        mDrawCacheLines = null;
        final int total = all.size();
        if (total == 0) return;
        int maxL = Math.min(total, cap);
        if (viewH > 0 && maxL > 1) {
            // Largest n in [1, maxL] whose block fits viewH. The block height
            // grows with n, so a binary search replaces the old linear walk,
            // which was bounded only by the 16-line cap that no longer exists.
            int lo = 1, hi = maxL;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (macapkBlockHeight(fm, mid, null, null) <= viewH) lo = mid; else hi = mid - 1;
            }
            maxL = lo;
        }
        final java.util.ArrayList<int[]> ranges = new java.util.ArrayList<>(all.subList(0, maxL));
        final java.util.ArrayList<String> lines = new java.util.ArrayList<>(maxL);
        for (int[] r : ranges) lines.add(s.substring(r[0], r[1]));
        final int[] srcCounts = new int[maxL];
        final boolean[] synth = new boolean[maxL];   // text no longer equals its range
        for (int i = 0; i < maxL; i++) srcCounts[i] = lines.get(i).length();
        final boolean ellipsisMayBeApplied = eff != null
            && (eff == android.text.TextUtils.TruncateAt.END
                || (cap == 1 && eff != android.text.TextUtils.TruncateAt.MARQUEE));
        if (ellipsisMayBeApplied && maxL == cap) {
            final int last = cap - 1;
            final int ls = ranges.get(last)[0];
            final int nl = s.indexOf('\n', ls);
            int le = (nl < 0) ? s.length() : nl;
            final boolean moreChars = nl >= 0 && nl + 1 < s.length();
            // AOSP's textWidth for the merged line is lineWidths[], which the
            // line breaker computes without the trailing (hanging) spaces.
            int visEnd = le;
            while (visEnd > ls && macapkIsLineEndSpace(s.charAt(visEnd - 1))) visEnd--;
            final float tw = macapkRunWidth(disp, s, ls, visEnd, p, workD, spannedD);
            if (tw > viewW || moreChars) {
                if (eff == android.text.TextUtils.TruncateAt.START
                        || eff == android.text.TextUtils.TruncateAt.MIDDLE) {
                    CharSequence e = android.text.TextUtils.ellipsize(
                        s.substring(ls, le), p, viewW, eff);
                    lines.set(last, e == null ? "" : e.toString());
                    srcCounts[last] = 0;
                } else {
                    // Walk [ls, le) WITHOUT the '\n' and append the ellipsis
                    // unconditionally. When moreChars, AOSP's merged line is
                    // [ls, nl + 1) and its forceEllipsis replaces the zero-width
                    // newline (calculateEllipsis `ellipsisStart = len - 1`), so
                    // "Beta\n" shows "Beta..." -- forcing over [ls, nl) would
                    // eat the 'a'. Otherwise tw > viewW already cut it short.
                    final String ell = macapkEllipsisString(eff);
                    int keep = macapkEndEllipsisKeep(disp, s, ls, le, p, workD, spannedD,
                                                     viewW, ell);
                    lines.set(last, s.substring(ls, ls + keep) + ell);
                    srcCounts[last] = keep;
                }
                synth[last] = true;
            } else if (le > ranges.get(last)[1]) {
                // The merged line fits after all: draw it whole.
                ranges.set(last, new int[]{ ls, le });
                lines.set(last, s.substring(ls, le));
                srcCounts[last] = le - ls;
            }
        }
        final int nLines = lines.size();
        final float[] lineW = new float[nLines];
        for (int i = 0; i < nLines; i++) {
            final int[] rg = ranges.get(i);
            lineW[i] = synth[i] ? p.measureText(lines.get(i))
                : macapkRunWidth(disp, s, rg[0], rg[1], p, workD, spannedD);
        }
        mDrawCacheRanges = ranges;
        mDrawCacheLines = lines;
        mDrawCacheSrcCounts = srcCounts;
        mDrawCacheLineW = lineW;
        float[][] spanGeom = MACAPK_TEXTVIEW_SPAN_LINES
            ? macapkSpanLineGeometry(disp, p, viewW, ranges, nLines) : null;
        if (spanGeom != null) {
            mDrawCacheTops = spanGeom[0];
            mDrawCacheAbove = spanGeom[1];
        } else {
            mDrawCacheTops = macapkLineTops(fm, nLines, lines, p);
            final float[] above = new float[nLines];
            for (int i = 0; i < nLines; i++) above[i] = macapkLineTopAbove(fm, i, lines, p);
            mDrawCacheAbove = above;
        }
        mDrawCacheKey = drawKey;
        macapkPaintWrapped(canvas, styled, p, fm, padL, padT, viewW, viewH);
    }

    /** MACAPK_TEXTVIEW_SPAN_LINES (default ON, 2026-10-01; =0 = the span-blind
     *  line positions below, byte-identical): a wrapped TextView whose text
     *  carries a MetricAffectingSpan or LineHeightSpan takes each line's TOP
     *  and BASELINE from android.text.Layout, which already applies both the
     *  AOSP way (macapkSpanVertical, applyLineHeightSpans), instead of from
     *  the BASE paint's metrics. */
    static final boolean MACAPK_TEXTVIEW_SPAN_LINES =
        !"0".equals(System.getenv("MACAPK_TEXTVIEW_SPAN_LINES"));

    /** {tops[nLines + 1], above[nLines]} for the wrapped lines `ranges`, from a
     *  StaticLayout over the same text, paint and width; null when the text has
     *  no span that moves line metrics, or when that Layout broke the lines
     *  differently (the old geometry then stays).
     *
     *  WHY. AOSP's onDraw draws mLayout (TextView.java onDraw -> layout.draw),
     *  a StaticLayout whose per-line ascent/descent are the union over the
     *  line's MetricAffectingSpan runs and are then handed to every
     *  LineHeightSpan.chooseHeight (StaticLayout.generate/out). This path
     *  positioned every line from the BASE paint's FontMetrics, so text whose
     *  size lives entirely in spans -- React Native sets ALL text styling as
     *  spans (ReactAbsoluteSizeSpan + CustomLineHeightSpan) on a TextView whose
     *  own size stays at the default -- got a ~17px line pitch under ~32px
     *  glyphs and every line of a paragraph was painted over the first
     *  (Efteling's welcome text, 2026-10-01). Layout is the one implementation
     *  of those AOSP rules in this tree, so the TextView reuses it rather than
     *  growing a second copy; only the VERTICAL positions are taken, the line
     *  text, widths and paint stay exactly as before. */
    private float[][] macapkSpanLineGeometry(CharSequence disp, android.text.TextPaint p,
                                             int viewW, java.util.List<int[]> ranges, int nLines) {
        if (!(disp instanceof android.text.Spanned) || nLines <= 0 || viewW <= 0) return null;
        final android.text.Spanned sp = (android.text.Spanned) disp;
        final int len = sp.length();
        if (sp.getSpans(0, len, android.text.style.MetricAffectingSpan.class).length == 0
                && sp.getSpans(0, len, android.text.style.LineHeightSpan.class).length == 0) {
            return null;
        }
        final android.text.Layout l;
        try {
            l = android.text.StaticLayout.Builder.obtain(disp, 0, len, p, viewW)
                .setIncludePad(mIncludePad)
                .setLineSpacing(mSpacingAdd, mSpacingMult)
                .build();
        } catch (Throwable t) {
            return null;
        }
        if (l.getLineCount() < nLines) return null;
        for (int i = 0; i < nLines; i++) {
            if (l.getLineStart(i) != ranges.get(i)[0]) return null;   // broke differently
        }
        final float[] tops = new float[nLines + 1];
        final float[] above = new float[nLines];
        for (int i = 0; i < nLines; i++) {
            tops[i] = l.getLineTop(i);
            above[i] = l.getLineTop(i) - l.getLineBaseline(i);   // AOSP getLineAscent, negative
        }
        tops[nLines] = l.getLineTop(nLines);
        return new float[][]{ tops, above };
    }

    /** The per-frame half of macapkDrawWrappedAosp: gravity and paint only,
     *  over the memoised lines. Gravity is read here, not memoised, because
     *  setGravity does not change any line's text or width. */
    private void macapkPaintWrapped(android.graphics.Canvas canvas, android.text.Spanned styled,
                                    android.text.TextPaint p,
                                    android.graphics.Paint.FontMetricsInt fm,
                                    int padL, int padT, int viewW, int viewH) {
        final java.util.ArrayList<String> lines = mDrawCacheLines;
        final float[] tops = mDrawCacheTops;
        final int nLines = lines.size();
        final float blockTop = padT + verticalGravityOffset(viewH, tops[nLines]);
        for (int i = 0; i < nLines; i++) {
            final String line = lines.get(i);
            final int[] rg = mDrawCacheRanges.get(i);
            final float x = padL + horizontalGravityOffset(viewW, mDrawCacheLineW[i]);
            final float baselineY = blockTop + tops[i] - mDrawCacheAbove[i];
            macapkDrawLine(canvas, styled, line, rg[0], mDrawCacheSrcCounts[i], x, baselineY, p);
        }
    }

    /** minikin LineBreakerUtil.h isLineEndSpace (android14-release :47-56):
     *  the characters the line breaker lets HANG past the line's width. */
    static boolean macapkIsLineEndSpace(char c) {
        return c == '\n' || c == ' ' || c == 0x1680
            || (0x2000 <= c && c <= 0x200A && c != 0x2007)
            || c == 0x205F || c == 0x3000;
    }

    /** The text as a Spanned iff it actually carries CharacterStyle spans.
     *  Returning null for everything else keeps the (overwhelmingly common)
     *  plain-text draw path on the single canvas.drawText it always used.
     *
     *  `t` MUST be the same CharSequence onDraw turned into the drawn String:
     *  macapkDrawLine indexes the spans with offsets into the DRAWN text, so
     *  passing the raw text while drawing the transformed one would apply
     *  spans at shifted offsets whenever the transformation changes length.
     *  It also matters when it does not: PasswordTransformationMethod returns
     *  a plain (non-Spanned) PasswordCharSequence, so a styled password field
     *  correctly falls to the flat draw path here, exactly as on AOSP where
     *  the Layout is built over that same non-Spanned sequence. */
    private android.text.Spanned macapkStyledText(CharSequence t) {
        if (!(t instanceof android.text.Spanned) || t.length() == 0) return null;
        android.text.Spanned sp = (android.text.Spanned) t;
        try {
            android.text.style.CharacterStyle[] cs =
                sp.getSpans(0, sp.length(), android.text.style.CharacterStyle.class);
            return (cs != null && cs.length > 0) ? sp : null;
        } catch (Throwable ignored) { return null; }
    }

    private android.graphics.Paint mSpanBgPaint;
    /** Whole-paint snapshot for macapkDrawLine; see the note there. */
    private android.text.TextPaint mDrawBase;

    /** Draw one laid-out line. `line` is the source slice starting at
     *  `srcStart` for `srcCount` characters, optionally followed by literal
     *  characters (our end-ellipsis) that have no source offset.
     *
     *  When the text carries CharacterStyle spans, AOSP's Layout/TextLine
     *  splits the line at every span transition and applies the covering
     *  spans' updateDrawState(TextPaint) to a working paint before drawing
     *  that run -- which is why a ForegroundColorSpan/StyleSpan/Underline-
     *  Span on a substring renders differently from its neighbours. We drew
     *  text.toString() with one paint, so every character-level span was
     *  silently dropped (Html.fromHtml output, Compose AnnotatedString
     *  interop, highlighted search matches all rendered flat).
     *
     *  We snapshot the WHOLE paint, reset to that snapshot before each run,
     *  and restore at the end. onDraw already mutates this paint every frame.
     *
     *  ⚠ This used to snapshot a hand-listed set of FIELDS instead, on the
     *  stated grounds that "Paint.set does not carry skew / typeface /
     *  shadow". That claim is STALE -- graphics/Paint.java:126-144 copies
     *  typeface, letterSpacing, textScaleX and skew -- and the field list
     *  omitted exactly those, so a TypefaceSpan's setTypeface and a
     *  letter-spacing span's setLetterSpacing LEAKED out of their run: an
     *  unspanned run after a spanned one drew in the span's face, and the
     *  shared paint kept the last span's typeface after the draw returned.
     *  AOSP cannot leak -- TextLine.handleRun does `wp.set(mPaint)` per run. */
    private void macapkDrawLine(android.graphics.Canvas canvas, android.text.Spanned sp,
                                 String line, int srcStart, int srcCount,
                                 float x, float baselineY, android.text.TextPaint p) {
        if (sp == null) { canvas.drawText(line, x, baselineY, p); return; }
        if (mDrawBase == null) mDrawBase = new android.text.TextPaint();
        mDrawBase.set(p);
        float cx = x;
        final int end = Math.min(srcStart + srcCount, sp.length());
        int runStart = srcStart;
        while (runStart < end) {
            int runEnd = sp.nextSpanTransition(runStart, end,
                android.text.style.CharacterStyle.class);
            if (runEnd <= runStart || runEnd > end) runEnd = end;
            p.set(mDrawBase);      // full reset: typeface + letterSpacing too
            android.text.style.CharacterStyle[] runSpans =
                sp.getSpans(runStart, runEnd, android.text.style.CharacterStyle.class);
            if (runSpans != null) {
                for (android.text.style.CharacterStyle cs : runSpans) {
                    if (cs == null) continue;
                    // getSpans is inclusive at the boundaries; skip the spans
                    // that merely touch this run without covering it.
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
        p.set(mDrawBase);          // leave the shared paint exactly as found
        if (srcCount < line.length())
            canvas.drawText(line.substring(srcCount), cx, baselineY, p);
    }

    // Gravity-based text positioning (AOSP TextView honors android:gravity to
    // place the text block within the view bounds). Horizontal: CENTER ->
    // centered, RIGHT -> right, else left. Vertical: CENTER -> centered, BOTTOM
    // -> bottom, else top. NO_GRAVITY (the default) draws top-left, matching the
    // prior behavior, so plain TextViews are unaffected; Buttons (default
    // CENTER) and any android:gravity now position correctly.
    private float horizontalGravityOffset(int availW, float contentW) {
        // AOSP resolves android:textAlignment FIRST: TextView.getLayoutAlignment()
        // (android13-release TextView.java:9263-9310) switches on
        // getTextAlignment() and only the TEXT_ALIGNMENT_GRAVITY case consults
        // android:gravity. TEXT_START/TEXT_END are paragraph-direction-relative
        // (we render LTR-only today, so START==left / END==right, same
        // simplification the rest of this file already makes); VIEW_START/
        // VIEW_END are layout-direction-relative (LTR: left/right); INHERIT
        // resolves up the tree in View.getTextAlignment(), landing on GRAVITY
        // at the root. Measured victim: AntennaPod's welcome TextViews
        // (match_parent + textAlignment="center") drew flush-left.
        switch (getTextAlignment()) {
            case android.view.View.TEXT_ALIGNMENT_CENTER:
                return Math.max(0f, (availW - contentW) / 2f);
            case android.view.View.TEXT_ALIGNMENT_TEXT_END:
            case android.view.View.TEXT_ALIGNMENT_VIEW_END:
                return Math.max(0f, availW - contentW);
            case android.view.View.TEXT_ALIGNMENT_TEXT_START:
            case android.view.View.TEXT_ALIGNMENT_VIEW_START:
                return 0f;
            default: // TEXT_ALIGNMENT_GRAVITY (or unresolved): fall through to gravity
        }
        int h = gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
        if (h == android.view.Gravity.CENTER_HORIZONTAL) return Math.max(0f, (availW - contentW) / 2f);
        if (h == (android.view.Gravity.RIGHT & android.view.Gravity.HORIZONTAL_GRAVITY_MASK))
            return Math.max(0f, availW - contentW);
        return 0f;
    }
    private float verticalGravityOffset(int availH, float contentH) {
        int v = gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
        if (v == android.view.Gravity.CENTER_VERTICAL) return Math.max(0f, (availH - contentH) / 2f);
        if (v == (android.view.Gravity.BOTTOM & android.view.Gravity.VERTICAL_GRAVITY_MASK))
            return Math.max(0f, availH - contentH);
        return 0f;
    }

    private static int totalLength(java.util.List<String> lines) {
        int sum = 0;
        for (String l : lines) sum += l.length();
        // Approximate space accounting from joining lines (1 space per join).
        if (lines.size() > 1) sum += lines.size() - 1;
        return sum;
    }

    /** Greedy word-wrap. Splits `s` into a list of lines each fitting
     *  within `widthPx` per `paint.measureText`. Stops at maxLines (or
     *  unbounded if maxLines <= 0). */
    private static java.util.ArrayList<String> wrapText(String s, int widthPx, android.graphics.Paint p, int maxLines) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (int[] r : wrapRanges(s, widthPx, p, maxLines)) lines.add(s.substring(r[0], r[1]));
        return lines;
    }

    // ---- span-aware measurement, TextView side -------------------------
    //
    // Same defect as Layout's, same fix: we broke lines and sized the view
    // with the BASE paint while macapkDrawLine painted each run with the
    // span's. See Layout.macapkMeasureSpanned's header and
    // docs/text-measure-vs-paint-divergence_2026-09-21.md.
    //
    // These are static because wrapRanges is, and the offsets it already works
    // in are what make this cheap: it reports {start,end} pairs into `s`, so
    // the spanned source can be measured by OFFSET with no extra bookkeeping.

    /** Does this text carry any MetricAffectingSpan at all? Checked ONCE per
     *  wrap/measure pass, never per character -- wrapRanges measures a growing
     *  prefix once per character, so a getSpans() call in that loop would
     *  allocate an array per character. */
    private static boolean macapkHasMetricSpans(CharSequence src) {
        if (!(src instanceof android.text.Spanned) || src.length() == 0) return false;
        try {
            android.text.style.MetricAffectingSpan[] m =
                ((android.text.Spanned) src).getSpans(0, src.length(),
                    android.text.style.MetricAffectingSpan.class);
            return m != null && m.length > 0;
        } catch (Throwable ignored) { return false; }
    }

    /** Width of [start,end). `spanned` is macapkHasMetricSpans' answer for this
     *  pass; when false this is exactly the old `p.measureText(s.substring(..))`
     *  -- measureText(String,int,int) and measureText(String) converge on the
     *  same implementation, so the unspanned path cannot move a pixel. */
    private static float macapkRunWidth(CharSequence src, String s, int start, int end,
                                        android.graphics.Paint p,
                                        android.text.TextPaint work, boolean spanned) {
        if (spanned && p instanceof android.text.TextPaint) {
            try {
                return android.text.Layout.macapkMeasureSpanned(src, start, end,
                           (android.text.TextPaint) p, work);
            } catch (Throwable ignored) { }
        }
        return p.measureText(s, start, end);
    }

    /** The same greedy wrap as wrapText, but reported as {startOffset,
     *  endOffset} pairs into `s`. onDraw needs the SOURCE offsets of each
     *  line to look up the CharacterStyle spans covering it; wrapText is a
     *  thin substring() wrapper over this so the two can never disagree
     *  about where the breaks fall. */
    private static java.util.ArrayList<int[]> wrapRanges(String s, int widthPx, android.graphics.Paint p, int maxLines) {
        return wrapRanges(s, null, widthPx, p, maxLines);
    }

    /** `src` is the ORIGINAL CharSequence `s` was flattened from, or null when
     *  the caller has none. Passing it makes the wrap span-aware: a view whose
     *  text styles its metrics through spans was otherwise broken into lines
     *  using the base font and then painted in the span's, so every line
     *  overflowed and got clipped. */
    private static java.util.ArrayList<int[]> wrapRanges(String s, CharSequence src, int widthPx,
                                                         android.graphics.Paint p, int maxLines) {
        java.util.ArrayList<int[]> lines = new java.util.ArrayList<>();
        if (s == null || s.length() == 0) return lines;
        final boolean spanned = macapkHasMetricSpans(src);
        final android.text.TextPaint work = spanned ? new android.text.TextPaint() : null;
        int n = s.length();
        int i = 0;
        while (i < n && (maxLines <= 0 || lines.size() < maxLines)) {
            // Find the longest prefix of s.substring(i) that fits in widthPx.
            int lineStart = i;
            int lineEnd = i;
            int lastSpace = -1;
            while (lineEnd < n) {
                char c = s.charAt(lineEnd);
                if (c == '\n') break;
                int candidateEnd = lineEnd + 1;
                float w = macapkRunWidth(src, s, lineStart, candidateEnd, p, work, spanned);
                // A trailing space HANGS: minikin's greedy breaker keeps the
                // width WITHOUT line-end spaces as the line's width
                // (LineBreakerUtil.h :200-211, "If we break a line on a
                // line-ending space, that space goes away"), so a space never
                // makes a line overflow on its own. Counting it broke F-Droid's
                // 'Encrypted container' (228px of ink) out of a 236px column.
                if (w > widthPx && !(MACAPK_TEXT_LINES_AOSP && macapkIsLineEndSpace(c))) {
                    // Doesn't fit. Break at last space if any.
                    if (lastSpace > lineStart) {
                        lineEnd = lastSpace;
                    } // else hard-break at lineEnd (already excluded last char)
                    break;
                }
                if (c == ' ') lastSpace = lineEnd;
                lineEnd = candidateEnd;
            }
            // Emit line.
            lines.add(new int[]{ lineStart, lineEnd });
            // Advance past line. If \n, skip it. If space-break, skip spaces.
            if (lineEnd < n && s.charAt(lineEnd) == '\n') lineEnd++;
            while (lineEnd < n && s.charAt(lineEnd) == ' ') lineEnd++;
            if (lineEnd == lineStart) break;  // safety: avoid infinite loop
            i = lineEnd;
        }
        return lines;
    }

    /** Add "..." to the end of `s` such that the result fits widthPx. */
    private static String ellipsize(String s, int widthPx, android.graphics.Paint p) {
        if (s == null || s.length() == 0) return s;
        String ell = "…";  // single-char ellipsis
        float ellW = p.measureText(ell);
        // Quick path: if even ellipsis doesn't fit, return empty.
        if (ellW > widthPx) return "";
        int lo = 0, hi = s.length();
        // Binary-search for the longest prefix where prefix+ell fits.
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid)) + ellW <= widthPx) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (lo == 0) return ell;
        return s.substring(0, lo) + ell;
    }
    // IME-related. Cocos2dx and many game launchers configure hidden
    // EditText fields with input-type / IME-action options during onCreate;
    // we don't surface keyboard chrome on macOS, so there is still no popup/
    // keyboard behavior driven by these values. But the getters were
    // hardcoded 0 with the setters discarding their argument entirely, which
    // is a stricter bug than "no chrome": non-chrome callers that branch on
    // the configured value read it back wrong too. MEASURED 2026-07-30 via
    // javap -c on com.google.android.material.textfield.
    // PasswordToggleEndIconDelegate (present in the beemdevelopment.aegis and
    // antennapod corpus jars): it calls TextView.getInputType() four times
    // and TextView.getTransformationMethod()/setTransformationMethod() to
    // decide whether to show its password-visibility-toggle end icon and to
    // install/remove PasswordTransformationMethod. Now a real field
    // round-trip; still no IME popup chrome, per the original intent.
    private int mInputType;

    /** AOSP TextView.setInputType(int) (frameworks/base core/java/android/
     *  widget/TextView.java:6879-6905) is NOT a plain field setter -- it
     *  installs the password mask:
     *      final boolean wasPassword = isPasswordInputType(getInputType());
     *      final boolean wasVisiblePassword = isVisiblePasswordInputType(...);
     *      setInputType(type, false);
     *      final boolean isPassword = isPasswordInputType(type);
     *      ...
     *      if (isPassword) {
     *          setTransformationMethod(PasswordTransformationMethod.getInstance());
     *          setTypefaceFromAttrs(..., MONOSPACE, ...);
     *      }
     *  This is the ONLY route by which a plain `editText.setInputType(
     *  InputType.TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD)` -- the way
     *  most apps and every Material TextInputLayout ask for a masked field --
     *  ever becomes dots on screen.
     *
     *  Scoped deltas from AOSP, both deliberate:
     *  - AOSP's "leaving password mode" cleanup goes through
     *    applySingleLine(singleLine, !isPassword, ...) (TextView.java:6912),
     *    which lands on setTransformationMethod(SingleLineTransformationMethod
     *    .getInstance()) or setTransformationMethod(null) depending on the
     *    multi-line flag. Only the setSingleLine() API installs
     *    SingleLineTransformationMethod here (MACAPK_SINGLELINE_TRANSFORM),
     *    so mirroring the single-line half here would newline-collapse every
     *    field whose inputType merely lacks textMultiLine -- a much wider
     *    change than this lane. We clear to null, which is AOSP's own
     *    multi-line answer and matches the rest of this tree.
     *  - No setTypefaceFromAttrs(MONOSPACE): AOSP switches password fields to
     *    a monospace face so the dots align. Cosmetic, and our Paint has no
     *    typeface-by-index selection. */
    public void setInputType(int type) {
        // AOSP setInputType(type, false) -> setKeyListenerOnly -> createEditorIfNeeded.
        createEditorIfNeeded();
        final boolean wasPassword = macapkIsPasswordInputType(mInputType);
        final boolean wasVisiblePassword = macapkIsVisiblePasswordInputType(mInputType);
        mInputType = type;
        // AOSP TextView.setInputType -> applySingleLine(!isMultilineInputType(type),
        // !isPassword, true, true) (android14-release TextView.java:7490), whose
        // FIRST statement is `mSingleLine = singleLine` (:11919). So on a real
        // device an inputType WITHOUT textMultiLine makes the field single-line --
        // which is nearly every EditText, and certainly every search box.
        //
        // Only the FLAG is applied here. The rest of applySingleLine (setLines(1),
        // setHorizontallyScrolling, SingleLineTransformationMethod, the
        // MAX_LENGTH_FOR_SINGLE_LINE_EDIT_TEXT filter) stays out for the reason
        // already recorded above this method: only setSingleLine() installs
        // SingleLineTransformationMethod, and installing the layout half
        // here would newline-collapse fields fleet-wide. The flag on its own is
        // what isSingleLine() -- and therefore macapkActionIdForEnterEvent() --
        // needs to answer correctly, and it is also the value AOSP's
        // onCreateDrawableState reads for state_multiline.
        macapkSingleLine = !macapkIsMultilineInputType(type);
        final boolean isPassword = macapkIsPasswordInputType(type);
        if (isPassword) {
            setTransformationMethod(
                android.text.method.PasswordTransformationMethod.getInstance());
        } else if ((wasPassword || wasVisiblePassword)
                && mTransformationMethod
                    == android.text.method.PasswordTransformationMethod.getInstance()) {
            setTransformationMethod(null);
        }
    }
    public int  getInputType() { return mInputType; }

    /** AOSP TextView.isPasswordInputType (TextView.java:6953-6962), verbatim:
     *      final int variation =
     *              inputType & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_MASK_VARIATION);
     *      return variation == (TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD)
     *          || variation == (TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD)
     *          || variation == (TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD);
     *  The mask matters: the FLAGS nibble (multiline / cap / autocorrect) must
     *  be stripped or `textPassword|textMultiLine` would not compare equal. */
    /** AOSP TextView.isMultilineInputType (android14-release TextView.java:
     *  7410-7413), verbatim:
     *      return (type & (EditorInfo.TYPE_MASK_CLASS | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE))
     *              == (EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE); */
    static boolean macapkIsMultilineInputType(int type) {
        return (type & (android.text.InputType.TYPE_MASK_CLASS
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE))
            == (android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    }

    static boolean macapkIsPasswordInputType(int inputType) {
        final int variation = inputType
            & (android.text.InputType.TYPE_MASK_CLASS
               | android.text.InputType.TYPE_MASK_VARIATION);
        return variation == (android.text.InputType.TYPE_CLASS_TEXT
                             | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            || variation == (android.text.InputType.TYPE_CLASS_TEXT
                             | android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            || variation == (android.text.InputType.TYPE_CLASS_NUMBER
                             | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    /** AOSP TextView.isVisiblePasswordInputType (TextView.java:6964-6969).
     *  textVisiblePassword is explicitly NOT masked -- it only forces the
     *  monospace face and suppresses IME suggestions. */
    static boolean macapkIsVisiblePasswordInputType(int inputType) {
        final int variation = inputType
            & (android.text.InputType.TYPE_MASK_CLASS
               | android.text.InputType.TYPE_MASK_VARIATION);
        return variation == (android.text.InputType.TYPE_CLASS_TEXT
                             | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    }
    /** AOSP TextView.isAnyPasswordInputType (@hide; android14-release
     *  TextView.java:7525-7528), verbatim:
     *      final int inputType = getInputType();
     *      return isPasswordInputType(inputType) || isVisiblePasswordInputType(inputType);
     *  Public because AOSP's is; Mac.apk's own reader is the input recorder,
     *  which must never write what is typed into a password field. */
    public boolean isAnyPasswordInputType() {
        final int inputType = getInputType();
        return macapkIsPasswordInputType(inputType) || macapkIsVisiblePasswordInputType(inputType);
    }
    /** AOSP TextView.hasPasswordTransformationMethod (android14-release
     *  TextView.java:7516-7518): `mTransformation instanceof
     *  PasswordTransformationMethod` -- the android:password="true" form, which
     *  masks a field without a password inputType. */
    public boolean macapkHasPasswordTransformationMethod() {
        return mTransformationMethod instanceof android.text.method.PasswordTransformationMethod;
    }
    private int mImeOptions;
    public void setImeOptions(int imeOptions) {
        // AOSP TextView.setImeOptions: createEditorIfNeeded();
        // mEditor.createInputContentTypeIfNeeded(); then stores the options.
        macapkInputContentType = true;
        mImeOptions = imeOptions;
    }
    /** AOSP `mEditor.mInputContentType != null`: created by setImeOptions,
     *  setOnEditorActionListener (and the XML imeOptions attr, which calls
     *  setImeOptions). onEditorAction's default NEXT/PREVIOUS/DONE handling
     *  only runs when it exists. */
    private boolean macapkInputContentType;
    public int  getImeOptions() { return mImeOptions; }
    /** AOSP TextView.mSingleLine (android11-release TextView.java:782,
     *  default false; set by applySingleLine :10258-10260). Tracked as its
     *  own flag rather than inferred from maxLines because AOSP treats them
     *  as distinct knobs: setMaxLines(1) constrains layout WITHOUT setting
     *  mSingleLine, and only mSingleLine feeds onCreateDrawableState's
     *  multiline decision below. */
    private boolean macapkSingleLine;
    /** AOSP setSingleLine(boolean) -> applySingleLine(singleLine, true, true,
     *  true) (:11918-11960): true is setLines(1), false is
     *  setMaxLines(Integer.MAX_VALUE). The LIMIT half is mirrored into the
     *  AOSP mMaximum/mMinimum pair; the transformation half is not (see
     *  setInputType for why). */
    public void setSingleLine(boolean s) {
        macapkSingleLine = s;
        if (s) maxLines = 1;
        if (s) { macapkSetMin(1, true); macapkSetMax(1, true); }
        else   { macapkSetMax(Integer.MAX_VALUE, true); }
        // AOSP setSingleLine -> applySingleLine(singleLine, true, true, true)
        // (android14 TextView.java:11915-11940):
        //     if (singleLine) { setLines(1); setHorizontallyScrolling(true);
        //         setTransformationMethod(SingleLineTransformationMethod.getInstance()); }
        //     else { ...; setTransformationMethod(null); }
        // Without the transformation a single-line view kept its '\n': it
        // MEASURED only the first paragraph while the one-line draw path ran
        // the whole text on, so it ellipsized almost at once. MEASURED:
        // NoNonsense Notes' search result row (title + "\n" + body, wrap_content,
        // setSingleLine(true) when "rows" = 1) measured 99 px and showed
        // "Shopping li ...". Scoped: the API call only (setInputType still sets
        // the flag alone, see setInputType), and an app transformation such as
        // a password mask is left in place. MACAPK_SINGLELINE_TRANSFORM=0
        // restores the old behaviour.
        if (MACAPK_SINGLELINE_TRANSFORM) {
            final android.text.method.TransformationMethod sl =
                android.text.method.SingleLineTransformationMethod.getInstance();
            if (s) {
                // Replace whatever is installed, as AOSP does -- AppCompat's
                // setTransformationMethod override re-wraps it in emoji2's
                // EmojiTransformationMethod, which every AppCompatTextView
                // already carries -- except a password mask: our inflater
                // applies XML singleLine AFTER the constructor chain, where
                // AOSP's applySingleLine runs before setInputType installs it.
                if (!(mTransformationMethod instanceof android.text.method.PasswordTransformationMethod)) {
                    setTransformationMethod(sl);
                    mMacapkSingleLineTransform = true;
                }
            } else if (mMacapkSingleLineTransform) {
                // AOSP's applySingleLine(false, ...) clears the transformation.
                // Only undo one this method installed, so a field that was
                // never set single-line here keeps its own.
                mMacapkSingleLineTransform = false;
                setTransformationMethod(null);
            }
        }
    }
    static final boolean MACAPK_SINGLELINE_TRANSFORM =
        !"0".equals(System.getenv("MACAPK_SINGLELINE_TRANSFORM"));
    /** setSingleLine(true) installed SingleLineTransformationMethod (possibly
     *  re-wrapped by an AppCompat subclass, so not comparable by identity). */
    private boolean mMacapkSingleLineTransform;
    public void setSingleLine() { setSingleLine(true); }

    /** AOSP TextView.isSingleLine() (android14-release TextView.java:7405-7408),
     *  `return mSingleLine;` -- public API, and the conjunct
     *  getActionIdForEnterEvent() below is built on. */
    public boolean isSingleLine() { return macapkSingleLine; }

    /** AOSP TextView.getActionIdForEnterEvent() (android14-release
     *  TextView.java:9688-9695), verbatim:
     *
     *      private int getActionIdForEnterEvent() {
     *          // If it's not single line, no action
     *          if (!isSingleLine()) return EditorInfo.IME_NULL;
     *          // Return the action that was specified for Enter
     *          return getImeOptions() & EditorInfo.IME_MASK_ACTION;
     *      }
     *
     *  This is what a HARDWARE Enter reports to an OnEditorActionListener
     *  (doKeyDown, TextView.java:9387-9405) -- the field's OWN declared action,
     *  never a fixed one. */
    private int macapkActionIdForEnterEvent() {
        if (!isSingleLine()) return android.view.inputmethod.EditorInfo.IME_NULL;
        return getImeOptions() & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
    }

    /** `android.R.attr.state_multiline`, the id AOSP's MULTILINE_STATE_SET
     *  carries (StateSet-built from R.attr.state_multiline = 0x0101034d,
     *  verified against core/res public.xml). */
    private static final int[] MACAPK_MULTILINE_STATE_SET = { 0x0101034d };

    /** AOSP TextView.onCreateDrawableState (android11-release
     *  TextView.java:7797-7824):
     *      if (mSingleLine) { drawableState = super.onCreateDrawableState(extraSpace); }
     *      else { drawableState = super.onCreateDrawableState(extraSpace + 1);
     *             mergeDrawableStates(drawableState, MULTILINE_STATE_SET); }
     *  then, only when isTextSelectable(), strips state_pressed. A default
     *  TextView is NOT single-line, so state_multiline is present on every
     *  plain TextView/Button/EditText on real Android (fwprobe DRAWSTATE
     *  golden carries `multiline` on all TextView-family rows; ours had it
     *  nowhere). The isTextSelectable branch is omitted because this tree's
     *  isTextSelectable() is a constant false -- mirror it together with that
     *  method if it ever becomes real. */
    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        if (macapkSingleLine) {
            return super.onCreateDrawableState(extraSpace);
        }
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
        android.view.View.mergeDrawableStates(drawableState, MACAPK_MULTILINE_STATE_SET);
        return drawableState;
    }
    public void setMaxLines(int n) { this.maxLines = n; mMaxHeightInPixels = false; macapkSetMax(n, true); }
    private CharSequence mHint;
    /** AOSP TextView.setHint -> setHintInternal (TextView.java:7681-7697):
     *  store mHint, then `if (mLayout != null) checkForRelayout();` and
     *  `if (mText.length() == 0) invalidate();`. The requestLayout half is
     *  load-bearing and was missing: the hint participates in MEASUREMENT
     *  (onMeasure takes max(textWidth, hintWidth), :11385-11387), so an
     *  invalidate alone leaves an empty field measured at zero width and the
     *  hint clipped away even once it is painted. */
    public void setHint(CharSequence hint) {
        this.mHint = hint;
        requestLayout();
        invalidate();
    }
    // AOSP resId overload; Material TextInputLayout reads the wrapped EditText's
    // hint via getHint(), so a stored value (not the previous null) matters.
    public void setHint(int resId) {
        try { android.content.Context c = getContext(); if (c != null) setHint(c.getString(resId)); }
        catch (Throwable ignored) {}
    }
    /** AOSP TextView.setHintTextColor(int) (TextView.java:5857-5860):
     *      mHintTextColor = ColorStateList.valueOf(color); updateTextColors();
     *  This was an empty no-op, so `android:textColorHint` and every app-side
     *  setHintTextColor(int) were silently discarded -- the ColorStateList
     *  overload right below stored a value while the far commoner int overload
     *  threw it away. */
    public void setHintTextColor(int color) {
        setHintTextColor(android.content.res.ColorStateList.valueOf(color));
    }
    public void setHorizontallyScrolling(boolean h) {}
    /** AOSP TextView.setFilters (frameworks/base core/java/android/widget/
     *  TextView.java:8538) rejects null and stores the array; getFilters
     *  (:8586) hands it back (the field starts at NO_FILTERS). The array used
     *  to be discarded outright, so InputFilter.LengthFilter never enforced
     *  android:maxLength, InputFilter.AllCaps never upper-cased, and custom
     *  digits/pattern filters never constrained anything. */
    private static final android.text.InputFilter[] NO_FILTERS = new android.text.InputFilter[0];
    private android.text.InputFilter[] mFilters = NO_FILTERS;
    public void setFilters(android.text.InputFilter[] filters) {
        if (filters == null) throw new IllegalArgumentException();
        mFilters = filters;
    }
    public android.text.InputFilter[] getFilters() { return mFilters; }

    /** The destination handed to a filter when the WHOLE buffer is being
     *  replaced. AOSP setText uses EMPTY_SPANNED with dstart==dend==0. */
    private static final android.text.Spanned MACAPK_EMPTY_SPANNED =
        new android.text.SpannedString("");

    /** AOSP setText runs the whole new value through every filter with an
     *  empty destination -- `mFilters[i].filter(text, 0, text.length(),
     *  EMPTY_SPANNED, 0, 0)`, keeping the replacement whenever it is non-null
     *  (frameworks/base core/java/android/widget/TextView.java:7349-7355). */
    private CharSequence macapkApplyFilters(CharSequence n) {
        if (n == null || mFilters == null || mFilters.length == 0) return n;
        for (android.text.InputFilter f : mFilters) {
            if (f == null) continue;
            try {
                CharSequence out = f.filter(n, 0, n.length(), MACAPK_EMPTY_SPANNED, 0, 0);
                if (out != null) n = out;
            } catch (Throwable ignored) {}
        }
        return n;
    }

    /** AOSP funnels every keystroke / IME commit through Editable.replace(),
     *  which calls `filter(source, start, end, dest, dstart, dend)` with the
     *  CURRENT buffer as `dest` and the replaced range as [dstart, dend).
     *  LengthFilter needs exactly that shape to know how much room is left
     *  (`keep = max - (dest.length() - (dend - dstart))`). Returns the text
     *  that is actually allowed to be inserted. */
    private CharSequence macapkFilterInsertion(CharSequence source, int dstart, int dend) {
        if (source == null || mFilters == null || mFilters.length == 0) return source;
        android.text.Spanned dest = (text instanceof android.text.Spanned)
            ? (android.text.Spanned) text
            : new android.text.SpannedString(text != null ? text : "");
        for (android.text.InputFilter f : mFilters) {
            if (f == null) continue;
            try {
                CharSequence out = f.filter(source, 0, source.length(), dest, dstart, dend);
                if (out != null) source = out;
            } catch (Throwable ignored) {}
        }
        return source;
    }

    public interface OnEditorActionListener {
        boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event);
    }

    // -------- v1.0.93 batch: TextView surface area for Material widgets --------
    // Methods harvested from a javap survey of F-Droid's classes.jar that
    // reference android.widget.TextView. Each one was failing as a
    // NoSuchMethodError during BottomNavigationView / RecyclerView /
    // NavigationBarMenuView construction. Implementations are no-ops or
    // sensible defaults; the widgets don't render Material chrome through
    // these (no styling pipeline) but the activity lifecycle reaches
    // setContentView without throwing.

    public int  getGravityValue() { return 0; }  // gravity stored elsewhere; helper only
    public CharSequence getHint() { return mHint; }
    /** AOSP getters (:5780/5823/5863/5903/6032/6112) answer -1 when the
     *  bound was last expressed in the OTHER unit -- there is no line count
     *  for a pixel limit and no pixel value for a line limit. */
    public int  getMaxLines() {
        if (!MACAPK_TEXT_LINES_AOSP) return maxLines > 0 ? maxLines : Integer.MAX_VALUE;
        return mMaxModeLines ? mMaximum : -1;
    }
    public int  getMinHeight() {
        if (!MACAPK_TEXT_LINES_AOSP) return 0;
        return mMinModeLines ? -1 : mMinimum;
    }
    public int  getMinWidth() {
        if (!MACAPK_TEXT_LINES_AOSP) return 0;
        return mMinEms >= 0 ? -1 : mMinWidthPx;
    }
    public int  getBaseline() {
        // AOSP TextView.getBaseline = distance from the view top to the first
        // line's text baseline. Horizontal LinearLayouts default baselineAligned=
        // true and align children on this value, so a label + bold-value row sits
        // on a common baseline. Was -1 ("no baseline") which disabled alignment.
        // v1.0.425.
        // v1.0.937: through the padding-aware extents. The first line's top is
        // fm.top under the default includeFontPadding, so the baseline sits
        // -top below it, not -ascent. Measured against the golden at 20px:
        // reference 22, and -ascent gives 19.
        android.graphics.Paint p = new android.graphics.Paint();
        p.setTextSize(textSizePx);
        applyTextStyleToPaint(p);
        return getPaddingTop()
             + (int) Math.ceil(macapkLineAscent(p.getFontMetricsInt()));
    }
    // AOSP TextView.getBreakStrategy/setBreakStrategy and
    // getHyphenationFrequency/setHyphenationFrequency were `{}` / hardcoded 0
    // with no backing field. Accessor round-trip only -- this tree has no
    // LineBreaker-based text layout to actually consult a break strategy or
    // hyphenation frequency against (see getLayout() below). Defaults verified
    // against AOSP (not guessed): TextView.java ~line 1202-1203 initializes
    // `mBreakStrategy = Layout.BREAK_STRATEGY_SIMPLE` (LineBreaker.java: = 0)
    // and `mHyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE` (Layout.
    // java: = 0); our android.text.Layout does not define either constant, so
    // both defaults are the verified literal 0 rather than a symbolic ref.
    private int mBreakStrategy;
    public int  getBreakStrategy() { return mBreakStrategy; }
    public void setBreakStrategy(int strategy) { mBreakStrategy = strategy; }
    private int mHyphenationFrequency;
    public int  getHyphenationFrequency() { return mHyphenationFrequency; }
    public void setHyphenationFrequency(int freq) { mHyphenationFrequency = freq; }
    /** AOSP TextView.setLineHeight(int) delegates to setLineHeightPx
     *  (frameworks/base core/java/android/widget/TextView.java:6531/6535),
     *  which is implemented purely in terms of setLineSpacing:
     *  `setLineSpacing(lineHeight - fontHeight, 1f)` where fontHeight is
     *  getPaint().getFontMetricsInt(null). It used to be an empty stub, so
     *  android:lineHeight and every Material3 TextAppearance that sets one
     *  had no effect on vertical rhythm at all. */
    public void setLineHeight(int lineHeight) { setLineHeightPx(lineHeight); }

    /** AOSP TextView.setLineHeight(int unit, float) (:6566): the
     *  TypedValue-unit-aware overload the Material3 typography styles call.
     *  Converts through the display metrics, then runs the same mechanism.
     *  (AOSP additionally re-proportions the value when non-linear font
     *  scaling is active; there is no FontScaleConverter on this platform,
     *  so the plain applyDimension path is the whole implementation.) */
    public void setLineHeight(int unit, float lineHeight) {
        android.content.Context c = getContext();
        android.util.DisplayMetrics dm = (c != null && c.getResources() != null)
            ? c.getResources().getDisplayMetrics() : null;
        setLineHeightPx(dm != null
            ? android.util.TypedValue.applyDimension(unit, lineHeight, dm)
            : lineHeight);
    }

    private void setLineHeightPx(float lineHeight) {
        if (lineHeight < 0f)
            throw new IllegalArgumentException(
                "Expecting non-negative lineHeight while the input is " + lineHeight);
        // The shared draw paint only carries the current text size once
        // measure/draw has run, so size it here the same way getLineHeight()
        // does before asking for the font height.
        android.text.TextPaint p = getPaint();
        p.setTextSize(textSizePx);
        applyTextStyleToPaint(p);
        final int fontHeight = p.getFontMetricsInt(null);
        if (lineHeight != fontHeight) setLineSpacing(lineHeight - fontHeight, 1f);
    }

    /** AOSP TextView.setMaxEms/setMinEms/setEms (frameworks/base core/java/
     *  android/widget/TextView.java:6355/6271/6434) store a width expressed
     *  in "em" units and flip the width mode to EMS; onMeasure then treats it
     *  as `ems * getLineHeight()` px. -1 means "this bound is not expressed in
     *  ems", which is exactly what AOSP's getMaxEms/getMinEms return then. */
    private int mMaxEms = -1, mMinEms = -1;
    public void setMaxEms(int maxEms) {
        mMaxEms = maxEms; requestLayout(); invalidate();
    }
    public void setMinEms(int minEms) {
        mMinEms = minEms; requestLayout(); invalidate();
    }
    public void setEms(int ems) {
        mMaxEms = mMinEms = ems; requestLayout(); invalidate();
    }

    /** AOSP TextView.getPaintFlags/setPaintFlags (frameworks/base core/java/
     *  android/widget/TextView.java:5983/5993) pass straight through to the
     *  text paint's flag word, reflowing when it actually changes. This is the
     *  only API for programmatic strikethrough/underline
     *  (`tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG)`,
     *  the struck-out-original-price idiom); both were missing entirely, so
     *  that call site threw NoSuchMethodError. */
    public int getPaintFlags() { return getPaint().getFlags(); }
    public void setPaintFlags(int flags) {
        android.text.TextPaint p = getPaint();
        if (p.getFlags() != flags) {
            p.setFlags(flags);
            requestLayout();
            invalidate();
        }
    }
    /** AOSP TextView.getOffsetForPosition (android14-release :15073-15078):
     *      if (getLayout() == null) return -1;
     *      final int line = getLineAtCoordinate(y);
     *      final int offset = getOffsetAtCoordinate(line, x);
     *  It answered a constant -1, so no tap could place the caret (Editor.
     *  onTouchUpEvent is its caller) and every app hit-testing its own text
     *  through it got "no layout". The inverse of the caret placement by
     *  construction: both go through getLayout() and the same paddings. */
    public int getOffsetForPosition(float x, float y) {
        if (!MACAPK_TEXT_CARET) return -1;
        if (getLayout() == null) return -1;
        final int line = getLineAtCoordinate(y);
        return getOffsetAtCoordinate(line, x);
    }

    /** :15080-15087, verbatim -- including AOSP's clamp against
     *  `getWidth() - getTotalPaddingRight() - 1` in a coordinate that already
     *  had the left padding removed. */
    float convertToLocalHorizontalCoordinate(float x) {
        x -= getTotalPaddingLeft();
        x = Math.max(0.0f, x);
        x = Math.min(getWidth() - getTotalPaddingRight() - 1, x);
        x += getScrollX();
        return x;
    }

    /** :15090-15097, verbatim. */
    int getLineAtCoordinate(float y) {
        y -= getTotalPaddingTop();
        y = Math.max(0.0f, y);
        y = Math.min(getHeight() - getTotalPaddingBottom() - 1, y);
        y += getScrollY();
        return getLayout().getLineForVertical((int) y);
    }

    /** :15105-15109. transformedToOriginal is the identity here: no
     *  TransformationMethod in this tree implements OffsetMapping, which is the
     *  only case where AOSP's mapping is not the identity either. */
    int getOffsetAtCoordinate(int line, float x) {
        x = convertToLocalHorizontalCoordinate(x);
        return getLayout().getOffsetForHorizontal(line, x);
    }

    /** AOSP getVerticalOffset(boolean) (:8268-8290): how far the laid-out
     *  text block sits below the top padding. Evaluated over THIS port's
     *  vertical-gravity rule (verticalGravityOffset: CENTER_VERTICAL centres,
     *  BOTTOM bottoms, anything else is top -- NO_GRAVITY included, which AOSP
     *  never sees because its default is TOP|START) and over getHeight(), the
     *  box the text paint path uses, so the caret lands in the SAME line box the
     *  glyphs were painted in. AOSP rounds the centre case with `>> 1`; the text
     *  paint path keeps the half pixel, so the two can differ by at most 0.5px.
     *  forceNormal is kept for signature parity -- Telegram's EditTextBoldCursor
     *  looks this method up by reflection as getVerticalOffset(boolean). There
     *  is no separate hint Layout here, so both values use the text layout. */
    int getVerticalOffset(boolean forceNormal) {
        return macapkVerticalOffset(getLayout());
    }

    /** getVerticalOffset over a Layout the caller already holds: getLayout()
     *  builds its cache key over the whole text, and the per-frame caret draw
     *  would otherwise pay for it several times. */
    private int macapkVerticalOffset(android.text.Layout l) {
        if (l == null) return 0;
        final int g = gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
        final int boxht = macapkBoxHeight();
        final int textht = l.getHeight();
        if (textht >= boxht) return 0;
        if (g == (android.view.Gravity.BOTTOM & android.view.Gravity.VERTICAL_GRAVITY_MASK)) {
            return boxht - textht;
        }
        if (g == android.view.Gravity.CENTER_VERTICAL) return (boxht - textht) >> 1;
        return 0;
    }

    /** AOSP getBottomVerticalOffset(boolean) (:8292-8314), same rule. */
    private int getBottomVerticalOffset(boolean forceNormal) {
        final android.text.Layout l = getLayout();
        if (l == null) return 0;
        final int g = gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
        final int boxht = macapkBoxHeight();
        final int textht = l.getHeight();
        if (textht >= boxht) return 0;
        if (g == (android.view.Gravity.BOTTOM & android.view.Gravity.VERTICAL_GRAVITY_MASK)) return 0;
        if (g == android.view.Gravity.CENTER_VERTICAL) return (boxht - textht) >> 1;
        return boxht - textht;
    }

    /** AOSP getBoxHeight (:8259-8265) over the draw path's box. */
    private int macapkBoxHeight() {
        int h = getHeight();
        if (h <= 0) h = getMeasuredHeight();
        return h - getCompoundPaddingTop() - getCompoundPaddingBottom();
    }

    // ---- the caret draw (AOSP getUpdatedHighlightPath + Editor.onDraw) -----

    /** AOSP mHighlightPath / mHighlightPaint / mHighlightPathBogus
     *  (:958-963): the cursor path used when there is no cursor DRAWABLE (a
     *  theme whose textCursorDrawable is @null, e.g. the legacy Theme). */
    private android.graphics.Path mHighlightPath;
    private android.graphics.Paint mHighlightPaint;
    private boolean mHighlightPathBogus = true;

    /** AOSP TextView.onDraw's caret half. getUpdatedHighlightPath (:8865-8885):
     *      if (mMovement != null && (isFocused() || isPressed()) && selStart >= 0) {
     *          if (selStart == selEnd) {
     *              if (mEditor != null && mEditor.shouldRenderCursor()) {
     *                  ... mLayout.getCursorPath(selStart, mHighlightPath, mText);
     *                  mEditor.updateCursorPosition();
     *                  highlightPaint.setColor(mCurTextColor);
     *                  highlightPaint.setStyle(Paint.Style.STROKE);
     *  then onDraw clips to the compound-padded box (:9011-9031), translates by
     *  (compoundPaddingLeft, extendedPaddingTop + voffsetCursor) (:9036-9056),
     *  and Editor.onDraw draws the cursor DRAWABLE in place of the path when
     *  there is one (:2116-2123). A range selection's highlight is not drawn
     *  here (selection rendering is outside the caret lane). */
    private void macapkDrawCursor(android.graphics.Canvas canvas) {
        final Editor ed = mEditor;
        if (ed == null || movementMethod == null || !(isFocused() || isPressed())) return;
        final int selStart = getSelectionStart();
        if (selStart < 0 || selStart != getSelectionEnd()) return;
        // FRAMESPEED caret (Editor.macapkShouldDrawCursor): the answer recorded
        // on the first draw after an invalidate, not a fresh clock read on every
        // frame -- AOSP replays an un-invalidated TextView's display list.
        if (!ed.macapkShouldDrawCursor()) return;
        final android.text.Layout layout = getLayout();
        if (layout == null) return;
        final int cpl = getCompoundPaddingLeft(), cpr = getCompoundPaddingRight();
        final int cpt = getCompoundPaddingTop(), cpb = getCompoundPaddingBottom();
        final int scrollX = getScrollX(), scrollY = getScrollY();
        final int vspace = getHeight() - cpb - cpt;
        final int maxScrollY = layout.getHeight() - vspace;
        float clipLeft = cpl + scrollX;
        float clipTop = (scrollY == 0) ? 0 : cpt + scrollY;
        float clipRight = getWidth() - cpr + scrollX;
        float clipBottom = getHeight() + scrollY - ((scrollY == maxScrollY) ? 0 : cpb);
        if (shadowRadius != 0) {
            clipLeft += Math.min(0, shadowDx - shadowRadius);
            clipRight += Math.max(0, shadowDx + shadowRadius);
            clipTop += Math.min(0, shadowDy - shadowRadius);
            clipBottom += Math.max(0, shadowDy + shadowRadius);
        }
        final int saved = canvas.save();
        try {
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom);
            canvas.translate(cpl, cpt + macapkVerticalOffset(layout));
            ed.loadCursorDrawable();
            if (ed.mDrawableForCursor != null) {
                ed.updateCursorPosition(layout);
                ed.drawCursor(canvas, 0);
            } else {
                if (mHighlightPath == null) mHighlightPath = new android.graphics.Path();
                mHighlightPath.reset();
                layout.getCursorPath(selStart, mHighlightPath, getEditableText());
                if (mHighlightPaint == null) {
                    mHighlightPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                }
                mHighlightPaint.setColor(textColor);
                mHighlightPaint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawPath(mHighlightPath, mHighlightPaint);
            }
            mHighlightPathBogus = false;
        } catch (Throwable ignored) {
            // A caret must never take the text down with it.
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    /** FRAMESPEED caret: every invalidate of THIS view is what would clear
     *  PFLAG_DRAWING_CACHE_VALID on a device (View.invalidateInternal,
     *  android14 View.java:19984-19987), so the next draw re-records the caret
     *  (Editor.macapkShouldDrawCursor). All three public entry points are
     *  overridden because they need not reach each other: AOSP's rect forms go
     *  straight to invalidateInternal, never the virtual invalidate(). Nothing
     *  else changes -- each calls straight through to View. */
    @Override public void invalidate() {
        if (mEditor != null) mEditor.macapkNoteInvalidated();
        super.invalidate();
    }
    @Override public void invalidate(int l, int t, int r, int b) {
        if (mEditor != null) mEditor.macapkNoteInvalidated();
        super.invalidate(l, t, r, b);
    }
    @Override public void invalidate(android.graphics.Rect dirty) {
        if (mEditor != null) mEditor.macapkNoteInvalidated();
        super.invalidate(dirty);
    }
    /** FRAMESPEED caret: requestLayout and forceLayout set PFLAG_INVALIDATED
     *  (android14 View.java:27039-27040, :27058-27059), and the next
     *  draw(Canvas, ViewGroup, long) turns that into mRecreateDisplayList
     *  (:23601), so a device re-records this view after either one too. */
    @Override public void requestLayout() {
        if (mEditor != null) mEditor.macapkNoteInvalidated();
        super.requestLayout();
    }
    @Override public void forceLayout() {
        if (mEditor != null) mEditor.macapkNoteInvalidated();
        super.forceLayout();
    }

    /** AOSP invalidateCursorPath (:8316-8355): invalidate just the caret --
     *  the drawable's bounds, or the stroked path's -- in view coordinates.
     *  The Blink runnable calls this on every edge, and nothing else. */
    void invalidateCursorPath() {
        if (mHighlightPathBogus) {
            invalidateCursor();
            return;
        }
        final int horizontalPadding = getCompoundPaddingLeft();
        final int verticalPadding = getCompoundPaddingTop() + getVerticalOffset(true);
        if (mEditor == null || mEditor.mDrawableForCursor == null) {
            if (mHighlightPath == null) {
                invalidateCursor();
                return;
            }
            float thick = (float) Math.ceil(getPaint().getStrokeWidth());
            if (thick < 1.0f) thick = 1.0f;
            thick /= 2.0f;
            final android.graphics.RectF r = new android.graphics.RectF();
            mHighlightPath.computeBounds(r, false);
            invalidate((int) Math.floor(horizontalPadding + r.left - thick),
                    (int) Math.floor(verticalPadding + r.top - thick),
                    (int) Math.ceil(horizontalPadding + r.right + thick),
                    (int) Math.ceil(verticalPadding + r.bottom + thick));
        } else {
            final android.graphics.Rect bounds = mEditor.mDrawableForCursor.getBounds();
            invalidate(bounds.left + horizontalPadding, bounds.top + verticalPadding,
                    bounds.right + horizontalPadding, bounds.bottom + verticalPadding);
        }
    }

    /** AOSP invalidateCursor() (:8357-8361). */
    void invalidateCursor() {
        final int where = getSelectionEnd();
        invalidateCursor(where, where, where);
    }

    /** AOSP invalidateCursor(int, int, int) (:8363-8369). */
    private void invalidateCursor(int a, int b, int c) {
        if (a >= 0 || b >= 0 || c >= 0) {
            final int start = Math.min(Math.min(a, b), c);
            final int end = Math.max(Math.max(a, b), c);
            invalidateRegion(start, end, true /* Also invalidates blinking cursor */);
        }
    }

    /** AOSP invalidateRegion (:8374-8420): the lines covering [start, end],
     *  widened to the caret drawable's bounds, in view coordinates. */
    void invalidateRegion(int start, int end, boolean invalidateCursor) {
        final android.text.Layout layout = getLayout();
        if (layout == null) {
            invalidate();
            return;
        }
        final int lineStart = layout.getLineForOffset(Math.max(0, start));
        int top = layout.getLineTop(lineStart);
        // "the descent from the line above can hang down into the line we
        // really want to redraw" (:8385-8391)
        if (lineStart > 0) top -= layout.getLineDescent(lineStart - 1);
        final int lineEnd = (start == end) ? lineStart : layout.getLineForOffset(Math.max(0, end));
        int bottom = layout.getLineBottom(lineEnd);
        if (invalidateCursor && mEditor != null && mEditor.mDrawableForCursor != null) {
            final android.graphics.Rect bounds = mEditor.mDrawableForCursor.getBounds();
            top = Math.min(top, bounds.top);
            bottom = Math.max(bottom, bounds.bottom);
        }
        final int compoundPaddingLeft = getCompoundPaddingLeft();
        final int verticalPadding = getCompoundPaddingTop() + getVerticalOffset(true);
        int left, right;
        if (lineStart == lineEnd && !invalidateCursor) {
            left = (int) layout.getPrimaryHorizontal(start) + compoundPaddingLeft;
            right = (int) (layout.getPrimaryHorizontal(end) + 1.0) + compoundPaddingLeft;
        } else {
            // Rectangle bounding box when the region spans several lines
            left = compoundPaddingLeft;
            right = getWidth() - getCompoundPaddingRight();
        }
        invalidate(getScrollX() + left, verticalPadding + top,
                getScrollX() + right, verticalPadding + bottom);
    }

    public android.graphics.drawable.Drawable[] getCompoundDrawables() {
        return new android.graphics.drawable.Drawable[]{
            mDrawableLeft, mDrawableTop, mDrawableRight, mDrawableBottom };
    }
    public android.graphics.drawable.Drawable[] getCompoundDrawablesRelative() {
        // LTR: start==left, end==right (we don't track RTL on TextView).
        return new android.graphics.drawable.Drawable[]{
            mDrawableLeft, mDrawableTop, mDrawableRight, mDrawableBottom };
    }
    public int  getTotalPaddingLeft() { return getCompoundPaddingLeft(); }
    public int  getTotalPaddingRight() { return getCompoundPaddingRight(); }
    /** AOSP :3029-3031 `getExtendedPaddingTop() + getVerticalOffset(true)` and
     *  :3038-3040 `getExtendedPaddingBottom() + getBottomVerticalOffset(true)`:
     *  the TOTAL padding includes the gravity offset of the text block, which
     *  is what getOffsetForPosition subtracts to find the tapped line. The
     *  compound padding stands in for the extended one because the text paint
     *  path positions from it (they agree unless maxLines clips the layout). */
    public int  getTotalPaddingTop() {
        if (!MACAPK_TEXT_CARET) return getCompoundPaddingTop();
        return getCompoundPaddingTop() + getVerticalOffset(true);
    }
    public int  getTotalPaddingBottom() {
        if (!MACAPK_TEXT_CARET) return getCompoundPaddingBottom();
        return getCompoundPaddingBottom() + getBottomVerticalOffset(true);
    }
    public void setCompoundDrawablePadding(int pad) {
        this.compoundDrawablePadding = pad;
    }
    public int getCompoundDrawablePadding() { return compoundDrawablePadding; }
    public void setCompoundDrawables(android.graphics.drawable.Drawable left,
                                       android.graphics.drawable.Drawable top,
                                       android.graphics.drawable.Drawable right,
                                       android.graphics.drawable.Drawable bottom) {
        mDrawableLeft = left; mDrawableTop = top;
        mDrawableRight = right; mDrawableBottom = bottom;
        // Bounds are the CALLER's to own -- AOSP's setCompoundDrawables only
        // READS them (`left.copyBounds(compoundRect); dr.mDrawableSizeLeft =
        // compoundRect.width();`, TextView.java:3226-3232) and it is
        // setCompoundDrawables*WithIntrinsicBounds* that assigns them. But a
        // drawable handed here with EMPTY bounds used to be rescued by the draw
        // path, which rewrote the bounds every frame; now that the draw path
        // correctly leaves them alone, that rescue has to happen once, here, or
        // such an icon would paint at size 0. macapkSetIntrinsicBounds is a
        // no-op on any drawable that already has bounds, so a caller-chosen
        // origin/size (MaterialButton's iconLeft) is never disturbed.
        macapkSetIntrinsicBounds(left);  macapkSetIntrinsicBounds(top);
        macapkSetIntrinsicBounds(right); macapkSetIntrinsicBounds(bottom);
        // AOSP re-applies the recorded compound-drawable tint whenever the
        // drawables are (re)assigned, so a tint set before the icon still
        // takes effect.
        applyCompoundDrawableTint();
        try { requestLayout(); } catch (Throwable ignored) {}
        try { invalidate(); } catch (Throwable ignored) {}
    }
    public void setCompoundDrawablesRelative(android.graphics.drawable.Drawable start,
                                               android.graphics.drawable.Drawable top,
                                               android.graphics.drawable.Drawable end,
                                               android.graphics.drawable.Drawable bottom) {
        // LTR mapping (no RTL tracking on TextView): start->left, end->right.
        setCompoundDrawables(start, top, end, bottom);
    }
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(int start, int top, int end, int bottom) {
        setCompoundDrawablesWithIntrinsicBounds(start, top, end, bottom);
    }
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(
        android.graphics.drawable.Drawable start, android.graphics.drawable.Drawable top,
        android.graphics.drawable.Drawable end, android.graphics.drawable.Drawable bottom) {
        // setCompoundDrawablesWithIntrinsicBounds sizes each drawable to its
        // intrinsic bounds before storing (AOSP contract).
        macapkSetIntrinsicBounds(start); macapkSetIntrinsicBounds(top);
        macapkSetIntrinsicBounds(end);   macapkSetIntrinsicBounds(bottom);
        setCompoundDrawables(start, top, end, bottom);
    }
    public void setCompoundDrawablesWithIntrinsicBounds(
        android.graphics.drawable.Drawable left, android.graphics.drawable.Drawable top,
        android.graphics.drawable.Drawable right, android.graphics.drawable.Drawable bottom) {
        macapkSetIntrinsicBounds(left); macapkSetIntrinsicBounds(top);
        macapkSetIntrinsicBounds(right); macapkSetIntrinsicBounds(bottom);
        setCompoundDrawables(left, top, right, bottom);
    }
    private static void macapkSetIntrinsicBounds(android.graphics.drawable.Drawable d) {
        if (d == null) return;
        if (d.getBounds().width() <= 0 || d.getBounds().height() <= 0) {
            int iw = Math.max(0, d.getIntrinsicWidth());
            int ih = Math.max(0, d.getIntrinsicHeight());
            if (iw > 0 && ih > 0) d.setBounds(0, 0, iw, ih);
        }
    }
    /** AOSP TextView.setCompoundDrawableTintList / setCompoundDrawableTintMode
     *  (frameworks/base core/java/android/widget/TextView.java:3637 / :3668)
     *  record the tint on the Drawables state and immediately push it onto
     *  every compound drawable through applyCompoundDrawableTint() ->
     *  Drawable.mutate() + setTintList/setTintMode (:3725). Both setters used
     *  to discard their argument outright and neither getter existed, so a
     *  theme-driven app:drawableTint painted the icon in its authored XML
     *  color and reading the tint back threw NoSuchMethodError. */
    private android.content.res.ColorStateList mDrawableTintList;
    private android.graphics.PorterDuff.Mode mDrawableTintMode;
    private boolean mHasDrawableTint, mHasDrawableTintMode;
    public void setCompoundDrawableTintList(android.content.res.ColorStateList tint) {
        mDrawableTintList = tint;
        mHasDrawableTint = true;
        applyCompoundDrawableTint();
    }
    public android.content.res.ColorStateList getCompoundDrawableTintList() {
        return mDrawableTintList;
    }
    public void setCompoundDrawableTintMode(android.graphics.PorterDuff.Mode mode) {
        mDrawableTintMode = mode;
        mHasDrawableTintMode = true;
        applyCompoundDrawableTint();
    }
    public android.graphics.PorterDuff.Mode getCompoundDrawableTintMode() {
        return mDrawableTintMode;
    }
    private void applyCompoundDrawableTint() {
        if (!mHasDrawableTint && !mHasDrawableTintMode) return;
        android.graphics.drawable.Drawable[] showing = {
            mDrawableLeft, mDrawableTop, mDrawableRight, mDrawableBottom };
        for (android.graphics.drawable.Drawable d : showing) {
            if (d == null) continue;
            try {
                // AOSP mutates first so tinting one view's icon can't bleed
                // into every other view sharing the drawable's constant state.
                d.mutate();
                if (mHasDrawableTint)     d.setTintList(mDrawableTintList);
                if (mHasDrawableTintMode) d.setTintMode(mDrawableTintMode);
            } catch (Throwable ignored) {}
        }
        invalidate();
    }

    /** API 23+ -- apply a styled-attribute TextAppearance. We don't
     *  resolve theme attrs end-to-end, so this is a no-op. */
    /** lane layout, 2026-08-01: real setTextAppearance. AOSP reads the
     *  TextAppearance styleable from `resId` and applies textSize /
     *  textStyle / textColor (TextView.java setTextAppearance ->
     *  readTextAppearance -> applyTextAppearance). Ours were no-op stubs,
     *  which is why every Toolbar TITLE painted at the default size:
     *  androidx Toolbar sizes its title exclusively via
     *  setTextAppearance(context, styleRes from titleTextAppearance)
     *  (measured: AntennaPod "Home" small at the default size vs the
     *  emulator's large title, docs/canvas-layout-fidelity_2026-08-01.md).
     *  Theme.obtainStyledAttributes(resId, attrs) resolves the style by id
     *  and binds its items (StyleBackedAttributeSet walks the style's
     *  parent chain), so @dimen/?attr values inside the appearance resolve
     *  through the same machinery as everything else. Ids verified against
     *  AOSP public-final.xml: textSize=0x01010095, textStyle=0x01010097,
     *  textColor=0x01010098. */
    public void setTextAppearance(int resId) { setTextAppearance(getContext(), resId); }
    public void setTextAppearance(android.content.Context ctx, int resId) {
        if (resId == 0 || ctx == null) return;
        try {
            android.content.res.Resources.Theme t = ctx.getTheme();
            if (t == null) return;
            // v1.0.930: letterSpacing joins the set. AOSP's TextAppearance
            // reader applies it (TextView.java:3950-3951, 4060-4063, 4125-4126,
            // `TextAppearance_letterSpacing -> setLetterSpacing(...)`), and
            // EVERY Material 3 typescale style sets it -- labelMedium/Large,
            // titleMedium, bodyLarge and the rest -- so dropping it made every
            // Material label, button and app-bar title in the fleet measure and
            // draw about 4% narrow. Ids read out of the real framework-res
            // API-30 table, not transcribed: letterSpacing = 0x010104b6.
            // v1.0.935: fontFamily joins the set. AOSP's readTextAppearance
            // maps TextAppearance_fontFamily -> attributes.mFontFamily and
            // hands it to setTypefaceFromAttrs (TextView.java:4017-4026,
            // :4105), so a typescale style that names a family applies it. This
            // is the dominant path for Material 3: every m3 label/title style
            // sets android:fontFamily, usually via an @string indirection --
            // m3_ref_typeface_plain_medium resolves to "sans-serif-medium" --
            // so dropping it rendered every Material label, button and app-bar
            // title at weight 400 where Android renders 500. Id read out of the
            // real framework-res API-30 table, not transcribed:
            // fontFamily = 0x010103ac (cross-checked against the four ids this
            // method already trusted).
            android.content.res.TypedArray a = t.obtainStyledAttributes(resId,
                new int[]{ 0x01010095 /*textSize*/, 0x01010097 /*textStyle*/,
                           0x01010098 /*textColor*/, 0x010104b6 /*letterSpacing*/,
                           0x010103ac /*fontFamily*/, 0x01010585 /*textFontWeight*/ });
            int sizePx = a.getDimensionPixelSize(0, -1);
            int style  = a.getInt(1, -1);
            int color  = a.getColor(2, Integer.MIN_VALUE);
            float ls   = a.getFloat(3, Float.NaN);
            // AOSP readTextAppearance (TextView.java:4017-4026) tries the FONT
            // RESOURCE first and only reads the value as a family NAME when
            // that yields nothing:
            //     try { attributes.mFontTypeface = appearance.getFont(attr); }
            //     catch (UnsupportedOperationException | Resources.NotFoundException e) {
            //         // Expected if it is not a font resource.
            //     }
            //     if (attributes.mFontTypeface == null) {
            //         attributes.mFontFamily = appearance.getString(attr);
            //     }
            android.graphics.Typeface fontRes = null;
            try { fontRes = a.getFont(4); }
            catch (UnsupportedOperationException | android.content.res.Resources.NotFoundException e) {
                // Expected if it is not a font resource.
            } catch (Throwable ignored) { }
            String fam = (fontRes == null) ? a.getString(4) : null;
            int weight = a.getInt(5, -1);
            a.recycle();
            if (sizePx > 0)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
            if (fontRes != null) setMacapkFontResource(fontRes);
            else if (fam != null) setMacapkFontFamily(fam);
            if (weight >= 0) setMacapkFontWeight(weight);
            if (style >= 0) setMacapkTextStyle(style);
            if (color != Integer.MIN_VALUE) setTextColor(color);
            if (!Float.isNaN(ls)) setLetterSpacing(ls);
            invalidate();
        } catch (Throwable ignored) {}
    }

    // Text-direction / locale / break-strategy
    // AOSP returns android.text.TextDirectionHeuristic; the typed interface
    // exists in our tree so use it (Object descriptor would NoSuchMethodError).
    public android.text.TextDirectionHeuristic getTextDirectionHeuristic() { return null; }
    public java.util.Locale getTextLocale() { return java.util.Locale.getDefault(); }
    public void setTextLocale(java.util.Locale locale) {}
    /** AOSP :4573 getTextLocales() reads mTextPaint.getTextLocales(), so the
     *  locales must land on the PAINT, not on a side field -- our Paint has both
     *  accessors (android/graphics/Paint.java:1231/:1234). This used to discard. */
    public void setTextLocales(android.os.LocaleList locales) {
        getPaint().setTextLocales(locales);
    }
    // v1.0.100: F-Droid's StatusBanner calls getTransformationMethod() with
    // an explicit android.text.method.TransformationMethod return type in the
    // bytecode -- the prior `Object` return signature missed the JNI method
    // descriptor and we threw NoSuchMethodError. AOSP returns the typed
    // TransformationMethod interface; mirror that.
    //
    // v1.0.(prev session) made the setter store its argument (it had been `{}`
    // with no backing field) but nothing consumed the stored method, so a
    // password EditText still painted its real characters. THAT is fixed here
    // (2026-07-30): the draw / measure / line-count paths now read
    // macapkDisplayText() == mTransformed, computed through this method. See
    // the mTransformed field comment at the top of this file for the AOSP
    // citations.
    private android.text.method.TransformationMethod mTransformationMethod;

    /** AOSP TextView.setTransformationMethod (frameworks/base core/java/
     *  android/widget/TextView.java:2694-2725):
     *      if (method == mTransformation) return;   // avoids the setText below
     *      ...
     *      mTransformation = method;
     *      ...
     *      setText(mText);
     *
     *  We do the same identity early-out, then recompute mTransformed and
     *  re-layout rather than re-entering setText(). Re-entering our setText
     *  would re-run the InputFilters and re-fire every TextWatcher on a mere
     *  mask/reveal toggle -- Material's PasswordToggleEndIconDelegate flips
     *  the transformation on EVERY tap of the eye icon, and TextInputLayout's
     *  counter/error watchers would see a spurious full-buffer replace each
     *  time. AOSP can afford setText(mText) because its notifyBefore path is
     *  driven off the ChangeWatcher span rather than a re-entrant filter pass.
     *  The observable subset AOSP's setText(mText) produces here -- mTransformed
     *  recomputed, layout invalidated -- is exactly what we do.
     *
     *  NOT mirrored (deliberately, with no behavior consequence in this tree):
     *  the TransformationMethod2 / mAllowTransformationLengthChange branch and
     *  the `sp.setSpan(mTransformation, ...)` on a Spannable body. See the
     *  length-change note on macapkDisplayText's callers in
     *  docs/textview-transformation_2026-07-31.md. */
    public void setTransformationMethod(android.text.method.TransformationMethod method) {
        if (method == mTransformationMethod) return;
        mTransformationMethod = method;
        macapkUpdateTransformed();
        requestLayout();
        invalidate();
    }
    public android.text.method.TransformationMethod getTransformationMethod() { return mTransformationMethod; }
    // AOSP TextView.getKeyListener()/setKeyListener() use the typed
    // android.text.method.KeyListener interface. The prior `Object` return
    // mismatched the bytecode descriptor (getKeyListener()Landroid/text/
    // method/KeyListener;) and would raise NoSuchMethodError -- the same
    // class of bug that the v1.0.100 getTransformationMethod fix addressed.
    // setKeyListener was entirely absent; EditText configuration (e.g.
    // setKeyListener(DigitsKeyListener.getInstance())) is extremely common,
    // so the missing setter was a hard NoSuchMethodError. We store the
    // listener without driving input (no soft keyboard plumbing yet) but the
    // symbol+state contract apps depend on is now correct.
    public android.text.method.KeyListener getKeyListener() { return mKeyListener; }
    public void setKeyListener(android.text.method.KeyListener input) {
        // AOSP setKeyListenerOnly (:2711-2714): `if (mEditor == null && input == null)
        // return; createEditorIfNeeded();`
        if (input != null) createEditorIfNeeded();
        mKeyListener = input;
    }
    private android.text.method.KeyListener mKeyListener;
    /** AOSP TextView.getLayout(): the text Layout, or null before the view has
     *  been laid out. The return type MUST be android.text.Layout (not Object):
     *  Material's MaterialButton.updateIconPosition resolves getLayout()Landroid/
     *  text/Layout; -- an Object descriptor mismatched and raised NoSuchMethodError
     *  inside onLayout, aborting the detail RecyclerView's layout pass.
     *
     *  lane layout, 2026-08-01: RETURNS A REAL StaticLayout now. The previous
     *  `return null;` stub predated the v1.0.394 android.text.Layout word-wrap
     *  engine and broke the AOSP invariant that getLineCount() and getLayout()
     *  describe the SAME object (AOSP getLineCount() IS
     *  `mLayout != null ? mLayout.getLineCount() : 0`, android13-release
     *  TextView.java:8433). Our getLineCount() wrapped text itself and
     *  returned >= 1 while getLayout() stayed null forever, so the standard
     *  app idiom
     *      for (int i = 0; i < getLineCount(); i++)
     *          max = max(max, getLayout().getLineWidth(i));
     *  (verbatim in Material's MaterialButton.getTextLayoutWidth, called from
     *  its onLayout) threw NPE mid-layout and ABORTED THE WHOLE LAYOUT PASS --
     *  every view after the first MaterialButton in traversal order kept
     *  stale/zero bounds (measured: AnkiDroid login missing its EditTexts,
     *  deskclock, campaign logs 2026-08-01). The layout is cached and rebuilt
     *  only when (text, width, size, style, alignment, maxLines, ellipsize,
     *  spacing) change; it wraps with the same greedy break the Layout engine
     *  gives Compose, over the same TRANSFORMED text onDraw paints.
     *
     *  Gate MACAPK_TEXT_CARET (default ON) changes three things here, because
     *  the caret, getOffsetForPosition and the gravity offset are all read off
     *  this layout and must describe the text onDraw actually paints:
     *   1. A HORIZONTALLY SCROLLING view (singleLine) gets ONE unwrapped line,
     *      as AOSP's makeNewLayout does (`if (mHorizontallyScrolling) want =
     *      VERY_WIDE;`, android14 TextView.java:10465 and :10963). It used to
     *      wrap at the view width: a 53-char query in a 360px singleLine field
     *      had 3 layout lines while onDraw painted 1, so the end-of-text caret
     *      (the typing position) landed on line 2 and was clipped away, and a
     *      mid-text caret was drawn on a phantom line over unrelated glyphs.
     *      Measured victim: every AppCompat SearchView (search_src_text is
     *      android:singleLine="true") once the query outgrows the field.
     *   2. Text carrying MetricAffectingSpans is laid out WITH them (a snapshot
     *      SpannedString; AOSP builds mLayout over the Spanned mTransformed).
     *      The layout used to see disp.toString(), so a BOLD or
     *      RelativeSizeSpan run was measured in the base font while onDraw
     *      painted it span-aware: the caret after "WWWWW" (bold) sat 123 of its
     *      324 pixels inside the glyphs. Unspanned text builds exactly as before.
     *   3. The text is compared apart from the parameters instead of being
     *      concatenated into the key on every call (the caret asks for the
     *      layout several times per frame), and the typeface, fake bold/italic
     *      and letter spacing join the key: setTypeface after the first
     *      getLayout() used to be answered from the stale layout. */
    private android.text.StaticLayout macapkCachedLayout;
    private String macapkCachedLayoutKey;
    /** Gate ON only: the text the cached layout was built over, and its
     *  metric-span signature (macapkMetricSpanSignature, "" when none). */
    private String macapkCachedLayoutText;
    private String macapkCachedLayoutSpans;
    public android.text.Layout getLayout() {
        final CharSequence disp = macapkDisplayText();
        int avail = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (avail <= 0) {
            int mw = getMeasuredWidth();
            if (mw > 0) avail = mw - getCompoundPaddingLeft() - getCompoundPaddingRight();
        }
        if (avail <= 0) return null;   // AOSP: null before first measure/layout
        android.text.Layout.Alignment align;
        switch (getTextAlignment()) {
            case android.view.View.TEXT_ALIGNMENT_CENTER:
                align = android.text.Layout.Alignment.ALIGN_CENTER; break;
            case android.view.View.TEXT_ALIGNMENT_TEXT_END:
            case android.view.View.TEXT_ALIGNMENT_VIEW_END:
                align = android.text.Layout.Alignment.ALIGN_OPPOSITE; break;
            case android.view.View.TEXT_ALIGNMENT_TEXT_START:
            case android.view.View.TEXT_ALIGNMENT_VIEW_START:
                align = android.text.Layout.Alignment.ALIGN_NORMAL; break;
            default: {
                int hg = gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
                if (hg == android.view.Gravity.CENTER_HORIZONTAL)
                    align = android.text.Layout.Alignment.ALIGN_CENTER;
                else if (hg == (android.view.Gravity.RIGHT
                        & android.view.Gravity.HORIZONTAL_GRAVITY_MASK))
                    align = android.text.Layout.Alignment.ALIGN_OPPOSITE;
                else align = android.text.Layout.Alignment.ALIGN_NORMAL;
            }
        }
        // Gate ON: the same limits the draw path honours -- AOSP hands
        // StaticLayout.Builder `mMaxMode == LINES ? mMaximum : MAX_VALUE` and
        // the EFFECTIVE ellipsize (MARQUEE -> END_SMALL, none with a key
        // listener), makeSingleLayout :10747-10762. setLines(n) used to reach
        // neither, so getLineCount() and the drawn text disagreed.
        final int layoutMaxLines = MACAPK_TEXT_LINES_AOSP ? macapkMaxLines() : maxLines;
        final android.text.TextUtils.TruncateAt layoutEllipsize =
            MACAPK_TEXT_LINES_AOSP ? macapkEffectiveEllipsize() : mEllipsize;
        // Gate MACAPK_TEXT_CARET: the layout also carries includeFontPadding and
        // fallback line spacing, as AOSP makeSingleLayout's builder does
        // (`.setIncludePad(mIncludePad)` and
        // `.setUseLineSpacingFromFallbacks(isFallbackLineSpacingForStaticLayout())`,
        // :10747-10762). Without them getLayout()'s line boxes were always the
        // padded ones, 11px taller than the text actually painted by a view with
        // includeFontPadding=false (80px text, measured offline), and the caret --
        // which is placed from these line boxes -- sat below its glyphs.
        final boolean layoutIncludePad = !MACAPK_TEXT_CARET || mIncludePad;
        final boolean layoutFallback = MACAPK_TEXT_CARET && macapkUseFallbackLineSpacing();
        if (MACAPK_TEXT_CARET) {
            return macapkCaretLayout(disp, avail, align, layoutMaxLines, layoutEllipsize,
                                     layoutIncludePad, layoutFallback);
        }
        // Gate OFF: the pre-caret body, unchanged.
        final String dispStr = disp.toString();
        final String key = avail + "|" + textSizePx + "|" + macapkTextStyle + "|"
            + align + "|" + layoutMaxLines + "|" + layoutEllipsize + "|" + mSpacingMult + "|"
            + mSpacingAdd + "|" + layoutIncludePad + "|" + layoutFallback + "|" + dispStr;
        if (macapkCachedLayout != null && key.equals(macapkCachedLayoutKey))
            return macapkCachedLayout;
        // The layout gets its OWN TextPaint: getPaint() is the shared draw
        // paint this file mutates constantly, and Layout breaks lines lazily,
        // so capturing the shared instance would wrap with whatever state the
        // paint happens to hold at first query.
        android.text.TextPaint lp = new android.text.TextPaint(getPaint());
        lp.setTextSize(textSizePx);
        applyTextStyleToPaint(lp);
        android.text.StaticLayout.Builder b = android.text.StaticLayout.Builder
            .obtain(dispStr, 0, dispStr.length(), lp, avail)
            .setAlignment(align)
            .setLineSpacing(mSpacingAdd, mSpacingMult);
        if (layoutMaxLines > 0 && layoutMaxLines != Integer.MAX_VALUE) b.setMaxLines(layoutMaxLines);
        if (layoutEllipsize != null) b.setEllipsize(layoutEllipsize);
        macapkCachedLayout = b.build();
        macapkCachedLayoutKey = key;
        return macapkCachedLayout;
    }

    /** getLayout() under MACAPK_TEXT_CARET: see getLayout's header for the
     *  three differences from the gate-OFF body. */
    private android.text.Layout macapkCaretLayout(CharSequence disp, int avail,
            android.text.Layout.Alignment align, int layoutMaxLines,
            android.text.TextUtils.TruncateAt layoutEllipsize,
            boolean layoutIncludePad, boolean layoutFallback) {
        // A layout that ELLIPSIZES keeps the real width, as AOSP's does: with
        // an effective ellipsize makeSingleLayout builds at ellipsisWidth, the
        // view's own content width (:10747-10762). MARQUEE never ellipsizes
        // (BoringLayout.init treats it like null), so it unwraps like no
        // ellipsize at all. An EditText never gets here with an ellipsize:
        // macapkEffectiveEllipsize drops it when there is a key listener.
        final boolean horiz = macapkHorizontallyScrolling()
            && (layoutEllipsize == null || layoutEllipsize == android.text.TextUtils.TruncateAt.MARQUEE);
        final String spans = macapkMetricSpanSignature(disp);
        final android.graphics.Typeface tf = getTypeface();
        final String key = avail + "|" + textSizePx + "|" + macapkTextStyle + "|"
            + align + "|" + layoutMaxLines + "|" + layoutEllipsize + "|" + mSpacingMult + "|"
            + mSpacingAdd + "|" + layoutIncludePad + "|" + layoutFallback + "|" + horiz + "|"
            + (tf == null ? 0 : System.identityHashCode(tf)) + "|" + macapkFakeBold + "|"
            + macapkFakeItalic + "|" + getPaint().getLetterSpacing();
        if (macapkCachedLayout != null && key.equals(macapkCachedLayoutKey)
                && spans.equals(macapkCachedLayoutSpans)
                && macapkCachedLayoutText != null && macapkCachedLayoutText.contentEquals(disp)) {
            return macapkCachedLayout;
        }
        final String dispStr = disp.toString();
        // The layout reads a SNAPSHOT, never the live Editable: Layout breaks
        // lines lazily, and an app that keeps the layout it was handed (as
        // Telegram's caret code does) must not see the buffer change under it.
        // SpannedString copies every span (AOSP's backward-compatible rule),
        // including ReplacementSpans, which size their run.
        final CharSequence src = spans.isEmpty() ? dispStr : new android.text.SpannedString(disp);
        android.text.TextPaint lp = new android.text.TextPaint(getPaint());
        lp.setTextSize(textSizePx);
        applyTextStyleToPaint(lp);
        int layoutW = avail;
        android.text.Layout.Alignment layoutAlign = align;
        if (horiz) {
            // AOSP lays a horizontally scrolling view out VERY_WIDE and then
            // SCROLLS it (bringTextIntoView / bringPointIntoView). This port
            // never scrolls a TextView's content: onDraw pins a line wider than
            // the field to the LEFT content edge whatever the gravity
            // (horizontalGravityOffset clamps at 0) and clips it. So the layout
            // is exactly as wide as its widest paragraph and aligned NORMAL when
            // that overflows -- line 0 then sits where the glyphs are painted,
            // and a caret past the visible width takes clampHorizontalPosition's
            // rightmost arm, inside the field, on the painted line. A literal
            // VERY_WIDE would put an ALIGN_OPPOSITE caret a million px away.
            final float want = macapkWidestParagraph(src, dispStr, lp);
            if (want > avail) {
                layoutW = (int) Math.ceil(want);
                layoutAlign = android.text.Layout.Alignment.ALIGN_NORMAL;
            }
        }
        android.text.StaticLayout.Builder b = android.text.StaticLayout.Builder
            .obtain(src, 0, src.length(), lp, layoutW)
            .setAlignment(layoutAlign)
            .setLineSpacing(mSpacingAdd, mSpacingMult)
            .setIncludePad(layoutIncludePad)
            .setUseLineSpacingFromFallbacks(layoutFallback);
        if (layoutMaxLines > 0 && layoutMaxLines != Integer.MAX_VALUE) b.setMaxLines(layoutMaxLines);
        if (layoutEllipsize != null) b.setEllipsize(layoutEllipsize);
        macapkCachedLayout = b.build();
        macapkCachedLayoutKey = key;
        macapkCachedLayoutText = dispStr;
        macapkCachedLayoutSpans = spans;
        return macapkCachedLayout;
    }

    /** Identity + range of every MetricAffectingSpan on `t` (ReplacementSpan
     *  included), or "" when there is none -- the one thing about a Spanned
     *  that changes its layout without changing its characters. A span MOVED
     *  or re-set on unchanged text changes the signature, so the cached layout
     *  is rebuilt, as AOSP's DynamicLayout reflows on a span change. */
    private static String macapkMetricSpanSignature(CharSequence t) {
        if (!(t instanceof android.text.Spanned) || t.length() == 0) return "";
        try {
            final android.text.Spanned sp = (android.text.Spanned) t;
            final android.text.style.MetricAffectingSpan[] m =
                sp.getSpans(0, t.length(), android.text.style.MetricAffectingSpan.class);
            if (m == null || m.length == 0) return "";
            final StringBuilder sb = new StringBuilder(m.length * 16);
            for (android.text.style.MetricAffectingSpan s : m) {
                sb.append(System.identityHashCode(s)).append(':')
                  .append(sp.getSpanStart(s)).append('-').append(sp.getSpanEnd(s)).append(';');
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Width of the widest '\n'-separated paragraph of `src`, span-aware when
     *  `src` is Spanned -- Layout.getDesiredWidth's answer, measured the way
     *  the layout itself will measure each line (Layout.macapkMeasureSpanned). */
    private static float macapkWidestParagraph(CharSequence src, String s, android.text.TextPaint lp) {
        final boolean spanned = src instanceof android.text.Spanned;
        final android.text.TextPaint work = spanned ? new android.text.TextPaint() : null;
        float max = 0f;
        int st = 0;
        final int n = s.length();
        while (st <= n) {
            int en = s.indexOf('\n', st);
            if (en < 0) en = n;
            if (en > st) {
                final float w = spanned
                    ? android.text.Layout.macapkMeasureSpanned(src, st, en, lp, work)
                    : lp.measureText(s, st, en);
                if (w > max) max = w;
            }
            st = en + 1;
        }
        return max;
    }
    // AOSP TextView.getTextClassifier() returns the typed
    // android.view.textclassifier.TextClassifier. The prior `Object`
    // descriptor mismatched bytecode that resolves
    // getTextClassifier()Landroid/view/textclassifier/TextClassifier; and
    // could raise NoSuchMethodError -- same class of bug as the
    // getTransformationMethod fix. We lazily create a default
    // TextClassifier so the return is non-null (AOSP never returns null
    // here; it hands back the NO_OP/default classifier) and store any
    // app-supplied override.
    public android.view.textclassifier.TextClassifier getTextClassifier() {
        if (mTextClassifier == null) {
            // TextClassifier became an INTERFACE on 2026-07-29 (AOSP declares it
            // one; as a class it broke every guest `implements TextClassifier`
            // with IncompatibleClassChangeError). AOSP hands back the NO_OP
            // constant when there is no on-device model, which is exactly what
            // `new TextClassifier()` used to build here.
            mTextClassifier = android.view.textclassifier.TextClassifier.NO_OP;
        }
        return mTextClassifier;
    }
    public void setTextClassifier(android.view.textclassifier.TextClassifier classifier) {
        mTextClassifier = classifier;
    }
    private android.view.textclassifier.TextClassifier mTextClassifier;
    public android.text.PrecomputedText.Params getTextMetricsParams() {
        return new android.text.PrecomputedText.Params.Builder(getPaint()).build();
    }
    public boolean isHorizontallyScrollable() { return false; }

    // Auto-size (API 26+). On API 26+ AppCompatTextView DELEGATES
    // autosize to these platform methods, and app code reads the
    // bounds back via TextViewCompat (Calculator's formula view does
    // exactly that: max = getAutoSizeMaxTextSize(), then steps its
    // own size between min/max). The old no-op stubs returned 0, so
    // the stepper computed size 0 and typed text painted invisibly.
    // AOSP stores these in px (the configuration setter converts via
    // TypedValue.applyDimension; the device density matches the
    // framework's dp handling elsewhere). We store + return; we do
    // not run the platform's own auto-stepper (apps like Calculator
    // step themselves; TextView consumers get the stored bounds).
    public static final int AUTO_SIZE_TEXT_TYPE_NONE    = 0;
    public static final int AUTO_SIZE_TEXT_TYPE_UNIFORM = 1;
    private int autoSizeTextType = AUTO_SIZE_TEXT_TYPE_NONE;
    private int autoSizeMinPx = -1, autoSizeMaxPx = -1, autoSizeStepPx = -1;
    private int[] autoSizePresetSizes = new int[0];
    public int  getAutoSizeTextType() { return autoSizeTextType; }
    public int  getAutoSizeMinTextSize() { return autoSizeMinPx; }
    public int  getAutoSizeMaxTextSize() { return autoSizeMaxPx; }
    public int  getAutoSizeStepGranularity() { return autoSizeStepPx; }
    public int[] getAutoSizeTextAvailableSizes() { return autoSizePresetSizes; }
    public void setAutoSizeTextTypeWithDefaults(int autoSizeTextType) {
        this.autoSizeTextType = autoSizeTextType;
        if (autoSizeTextType == AUTO_SIZE_TEXT_TYPE_UNIFORM
                && autoSizeMinPx <= 0) {
            // AOSP defaults: 12sp min, 112sp max, 1px granularity.
            autoSizeMinPx = (int) (12 * android.util.DisplayMetrics.macapkDeviceDensity());
            autoSizeMaxPx = (int) (112 * android.util.DisplayMetrics.macapkDeviceDensity());
            autoSizeStepPx = 1;
        }
    }
    public void setAutoSizeTextTypeUniformWithConfiguration(
        int autoSizeMinTextSize, int autoSizeMaxTextSize,
        int autoSizeStepGranularity, int unit) {
        // unit: 0=px (COMPLEX_UNIT_PX), 2=sp. Convert sp/dp by the
        // device density; px passes through.
        float scale = (unit == 0) ? 1f : android.util.DisplayMetrics.macapkDeviceDensity();
        this.autoSizeMinPx  = (int) (autoSizeMinTextSize * scale);
        this.autoSizeMaxPx  = (int) (autoSizeMaxTextSize * scale);
        this.autoSizeStepPx = Math.max(1, (int) (autoSizeStepGranularity * scale));
        this.autoSizeTextType = AUTO_SIZE_TEXT_TYPE_UNIFORM;
    }
    public void setAutoSizeTextTypeUniformWithPresetSizes(int[] presetSizes, int unit) {
        if (presetSizes != null && presetSizes.length > 0) {
            float scale = (unit == 0) ? 1f : android.util.DisplayMetrics.macapkDeviceDensity();
            autoSizePresetSizes = new int[presetSizes.length];
            for (int i = 0; i < presetSizes.length; i++) {
                autoSizePresetSizes[i] = (int) (presetSizes[i] * scale);
            }
            java.util.Arrays.sort(autoSizePresetSizes);
            autoSizeMinPx = autoSizePresetSizes[0];
            autoSizeMaxPx = autoSizePresetSizes[autoSizePresetSizes.length - 1];
            this.autoSizeTextType = AUTO_SIZE_TEXT_TYPE_UNIFORM;
        }
    }

    // First / last baseline (API 28+).
    public void setFirstBaselineToTopHeight(int firstBaselineToTopHeight) {}
    public void setLastBaselineToBottomHeight(int lastBaselineToBottomHeight) {}

    // Font variation (API 26+).
    // AOSP TextView.setFontVariationSettings/getFontVariationSettings: the
    // setter already returned AOSP's real `boolean` (whether the platform
    // successfully applied the settings) but stored nothing, so the getter
    // was hardcoded null regardless. Accessor round-trip only; the variable-
    // font axis values are not applied to the paint used for measuring/
    // drawing here.
    private String mFontVariationSettings;
    public boolean setFontVariationSettings(String fontVariationSettings) {
        mFontVariationSettings = fontVariationSettings;
        return true;
    }
    public String  getFontVariationSettings() { return mFontVariationSettings; }

    // Font feature settings (API 21+). AOSP stores the CSS-style
    // feature string on the TextPaint (mTextPaint.setFontFeatureSettings)
    // and relayouts; we keep the value so getFontFeatureSettings
    // round-trips. Calculator's formula/result TextViews call this
    // from their constructors ("tnum" tabular digits); without it the
    // views fail instantiation and the inflater placeholders them.
    private String fontFeatureSettings;
    public void setFontFeatureSettings(String fontFeatureSettings) {
        this.fontFeatureSettings = fontFeatureSettings;
    }
    public String getFontFeatureSettings() { return fontFeatureSettings; }

    // Editing extras (most are no-ops since we don't host an IME).
    public void beginBatchEdit() {}
    public void endBatchEdit()   {}
    // AOSP: setEditableFactory(Editable.Factory). Object descriptor mismatch
    // would NoSuchMethodError; Editable.Factory exists in our tree.
    public void setEditableFactory(android.text.Editable.Factory factory) {}
    // AOSP-typed signatures: app bytecode links against
    // setMovementMethod(MovementMethod) / setCustomSelectionAction-
    // ModeCallback(ActionMode.Callback); an Object-typed stub is a
    // different descriptor and throws NoSuchMethodError at the call
    // site (gated Calculator's formula/result view constructors).
    private android.text.method.MovementMethod movementMethod;
    public void setMovementMethod(android.text.method.MovementMethod method) {
        // AOSP (:2747-2761) re-runs setText(mText) so the new method
        // initializes the buffer; we initialize directly (same selection
        // result, without re-firing TextWatchers for unchanged text).
        if (this.movementMethod != method) {
            this.movementMethod = method;
            // AOSP re-runs setText only `if (movement != null && mSpannable
            // == null)`; an EditText's text is always Spannable there, so its
            // caret is never reset by a movement-method change.
            if (method != null && !getDefaultEditable()
                    && !(text instanceof android.text.Spannable)) {
                macapkInitializeMovement();
            }
        }
    }
    private android.view.ActionMode.Callback customSelectionActionModeCallback;
    public void setCustomSelectionActionModeCallback(
            android.view.ActionMode.Callback cb) {
        this.customSelectionActionModeCallback = cb;
    }
    public android.view.ActionMode.Callback getCustomSelectionActionModeCallback() {
        return customSelectionActionModeCallback;
    }
    // API 23+ floating insertion-toolbar callback (same pattern as the
    // selection callback above).
    private android.view.ActionMode.Callback customInsertionActionModeCallback;
    public void setCustomInsertionActionModeCallback(
            android.view.ActionMode.Callback cb) {
        this.customInsertionActionModeCallback = cb;
    }
    public android.view.ActionMode.Callback getCustomInsertionActionModeCallback() {
        return customInsertionActionModeCallback;
    }

    // API 21+: whether focusing should pop the soft keyboard. We have
    // no IME, so only the stored flag matters (apps with custom
    // keypads, e.g. calculators, set false from their constructors).
    private boolean showSoftInputOnFocus = true;
    public void setShowSoftInputOnFocus(boolean show) {
        createEditorIfNeeded();   // AOSP :5358-5361 keeps the flag on the Editor
        this.showSoftInputOnFocus = show;
    }
    public boolean getShowSoftInputOnFocus() { return showSoftInputOnFocus; }

    // ---- copy / cut / paste / select-all (AOSP android14-release) ----------
    //
    // AOSP TextView.onTextContextMenuItem (:14510) is where EVERY text copy and
    // paste on the platform lands: the floating toolbar, the IME
    // (EditableInputConnection.performContextMenuAction calls it), and the
    // Ctrl+C/X/V/A shortcuts (onKeyShortcut below). It used to answer false and
    // do nothing, so no EditText in any app could copy or paste even though
    // ClipboardManager itself works. The ids are android.R.id's public values
    // (core/res/res/values/public-final.xml: selectAll 0x0102001f, cut
    // 0x01020020, copy 0x01020021, paste 0x01020022, pasteAsPlainText
    // 0x01020031); this tree ships no android.R, so they are named here, as
    // ExtractEditText already does for selectAll.
    static final int ID_SELECT_ALL = 0x0102001f;
    static final int ID_CUT = 0x01020020;
    static final int ID_COPY = 0x01020021;
    static final int ID_PASTE = 0x01020022;
    static final int ID_PASTE_AS_PLAIN_TEXT = 0x01020031;

    public boolean onTextContextMenuItem(int id) {
        final CharSequence cur = getText() != null ? getText() : "";
        int min = 0;
        int max = cur.length();
        if (isFocused()) {
            final int selStart = getSelectionStart();
            final int selEnd = getSelectionEnd();
            min = Math.max(0, Math.min(selStart, selEnd));
            max = Math.max(0, Math.max(selStart, selEnd));
        }
        switch (id) {
            case ID_SELECT_ALL:
                macapkSelectAllText();
                return true;
            case ID_PASTE:
                macapkPaste(true);
                return true;
            case ID_PASTE_AS_PLAIN_TEXT:
                macapkPaste(false);
                return true;
            case ID_CUT: {
                final android.content.ClipData cutData = android.content.ClipData.newPlainText(
                        null, macapkTransformedText(cur, min, max));
                if (macapkSetPrimaryClip(cutData)) {
                    // AOSP deleteText_internal(min, max) -> ((Editable) mText).delete.
                    macapkEditAtSelection(getEditableText(), min, Math.min(max, cur.length()), "", true);
                } else {
                    Toast.makeText(getContext(), "Failed to copy to clipboard", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            case ID_COPY: {
                // AOSP: "For link action mode in a non-selectable/non-focusable
                // TextView, make sure that we set the appropriate min/max."
                final int selStart = getSelectionStart();
                final int selEnd = getSelectionEnd();
                min = Math.max(0, Math.min(selStart, selEnd));
                max = Math.max(0, Math.max(selStart, selEnd));
                final android.content.ClipData copyData = android.content.ClipData.newPlainText(
                        null, macapkTransformedText(cur, min, max));
                if (!macapkSetPrimaryClip(copyData)) {
                    // R.string.failed_to_copy_to_clipboard
                    Toast.makeText(getContext(), "Failed to copy to clipboard", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        }
        return false;
    }

    /** AOSP getTransformedText(start, end): what the user SEES, so a
     *  password field copies its dots, not its secret. */
    private CharSequence macapkTransformedText(CharSequence cur, int start, int end) {
        CharSequence shown = cur;
        android.text.method.TransformationMethod tm = getTransformationMethod();
        if (tm != null) {
            try { shown = tm.getTransformation(cur, this); } catch (RuntimeException ignored) { shown = cur; }
        }
        final int n = shown.length();
        return shown.subSequence(Math.min(start, n), Math.min(end, n)).toString();
    }

    /** AOSP selectAllText(): Selection.setSelection(mSpannable, 0, length). */
    private boolean macapkSelectAllText() {
        selectAll();
        return getText() != null && getText().length() > 0;
    }

    private android.content.ClipboardManager macapkClipboard() {
        Object o = getContext() != null
            ? getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) : null;
        return (o instanceof android.content.ClipboardManager) ? (android.content.ClipboardManager) o : null;
    }

    /** AOSP setPrimaryClip(ClipData): any throw is a failed copy. */
    private boolean macapkSetPrimaryClip(android.content.ClipData clip) {
        android.content.ClipboardManager cm = macapkClipboard();
        if (cm == null) return false;
        try {
            cm.setPrimaryClip(clip);
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /** AOSP paste(withFormatting): the clip goes through the unified
     *  content-receive path, so an app's OnReceiveContentListener sees it first
     *  and TextView's own default (onReceiveContent below) inserts what is left.
     *  View.performReceiveContent does that chaining itself (the View hunk of
     *  this same change), and AOSP's paste() discards its result: whatever the
     *  chain hands back was declined by every receiver, including this view's
     *  own onReceiveContent. Calling onReceiveContent again on it would run an
     *  app subclass's override twice. */
    private void macapkPaste(boolean withFormatting) {
        android.content.ClipboardManager cm = macapkClipboard();
        android.content.ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        if (clip == null) return;
        final android.view.ContentInfo payload = new android.view.ContentInfo.Builder(
                clip, android.view.ContentInfo.SOURCE_CLIPBOARD)
            .setFlags(withFormatting ? 0 : android.view.ContentInfo.FLAG_CONVERT_TO_PLAIN_TEXT)
            .build();
        performReceiveContent(payload);
    }

    /** AOSP TextView.onReceiveContent -> TextViewOnReceiveContentListener
     *  (android14-release widget/TextViewOnReceiveContentListener.java): every
     *  item's text replaces the selection (the first) or follows it after a
     *  newline (the rest); IME-sourced content is not text and is handed back. */
    @Override public android.view.ContentInfo onReceiveContent(android.view.ContentInfo payload) {
        if (mEditor == null || payload == null) return payload;
        if (payload.getSource() == android.view.ContentInfo.SOURCE_INPUT_METHOD) return payload;
        final android.content.ClipData clip = payload.getClip();
        final boolean plain = (payload.getFlags() & android.view.ContentInfo.FLAG_CONVERT_TO_PLAIN_TEXT) != 0;
        final android.text.Editable buf = getEditableText();
        boolean didFirst = false;
        for (int i = 0; i < clip.getItemCount(); i++) {
            final android.content.ClipData.Item item = clip.getItemAt(i);
            CharSequence itemText = plain ? item.coerceToText(getContext()) : item.coerceToStyledText(getContext());
            if (plain && itemText instanceof android.text.Spanned) itemText = itemText.toString();
            if (itemText == null) continue;
            final int[] r = macapkEditRange(buf);
            if (!didFirst) {
                macapkEditAtSelection(buf, r[0], r[1], itemText, true);
                didFirst = true;
            } else {
                macapkEditAtSelection(buf, r[1], r[1], "\n" + itemText, true);
            }
        }
        return null;
    }

    /** AOSP canCut / canCopy / canPaste (:14932-14990) as this tree can state
     *  them: no copy out of a password field, a selection to copy, an editable
     *  buffer and a clip to paste. */
    boolean canCut() {
        return !macapkHasPasswordTransformation() && getText() != null && getText().length() > 0
            && hasSelection() && mEditor != null && macapkIsTextEditor();
    }
    boolean canCopy() {
        return !macapkHasPasswordTransformation() && getText() != null && getText().length() > 0
            && hasSelection() && mEditor != null;
    }
    boolean canPaste() {
        if (mEditor == null || !macapkIsTextEditor()) return false;
        if (getSelectionStart() < 0 || getSelectionEnd() < 0) return false;
        android.content.ClipboardManager cm = macapkClipboard();
        return cm != null && cm.hasPrimaryClip();
    }
    private boolean macapkHasPasswordTransformation() {
        return getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod;
    }

    /** AOSP canSelectText() (:13070-13072), the guard TextView.onKeyShortcut's
     *  Ctrl+A case actually uses (:13124-13126):
     *      return mText.length() != 0 && mEditor != null && mEditor.hasSelectionController();
     *  As this tree can state it (no separate selection-controller object):
     *  text non-empty, enabled, and either a text editor or explicitly
     *  text-selectable (AOSP's textCanBeSelected(); our isTextSelectable() is
     *  still a `return false` stub, so this reduces to editable fields today).
     *  v1.0.1658 (review issue 4, clipboard-bridge lane): the plain
     *  `getText().length() > 0` this replaced let Ctrl+A/Cmd+A on a focused
     *  Button or CheckBox (TextViews with no Editor) be swallowed here as
     *  SELECT_ALL instead of falling through to Activity/AppCompat
     *  onKeyShortcut, which is where a device answers it. */
    private boolean canSelectText() {
        return getText() != null && getText().length() > 0 && isEnabled()
            && (onCheckIsTextEditor() || isTextSelectable());
    }

    /** AOSP TextView.onKeyShortcut (:13066): Ctrl+A/X/C/V, Ctrl+Shift+V. */
    @Override public boolean onKeyShortcut(int keyCode, android.view.KeyEvent event) {
        if (event.hasModifiers(android.view.KeyEvent.META_CTRL_ON)) {
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_A:
                    if (canSelectText()) return onTextContextMenuItem(ID_SELECT_ALL);
                    break;
                case android.view.KeyEvent.KEYCODE_X:
                    if (canCut()) return onTextContextMenuItem(ID_CUT);
                    break;
                case android.view.KeyEvent.KEYCODE_C:
                    if (canCopy()) return onTextContextMenuItem(ID_COPY);
                    break;
                case android.view.KeyEvent.KEYCODE_V:
                    if (canPaste()) return onTextContextMenuItem(ID_PASTE);
                    break;
            }
        } else if (event.hasModifiers(android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_SHIFT_ON)) {
            if (keyCode == android.view.KeyEvent.KEYCODE_V && canPaste()) {
                return onTextContextMenuItem(ID_PASTE_AS_PLAIN_TEXT);
            }
        }
        return super.onKeyShortcut(keyCode, event);
    }

    /** AOSP TextView.BufferType: how setText stores the text
     *  (NORMAL = as-is, SPANNABLE = wrap in a Spannable, EDITABLE =
     *  wrap in an Editable). We keep the CharSequence either way;
     *  the enum exists so setText(text, BufferType.SPANNABLE) callers
     *  link (Calculator's formula view uses SPANNABLE). */
    public enum BufferType { NORMAL, SPANNABLE, EDITABLE }
    public void setText(CharSequence t, BufferType type) {
        // AOSP setText (:7052-7054): an EDITABLE buffer creates the Editor.
        if (type == BufferType.EDITABLE) createEditorIfNeeded();
        if (type != null) mBufferType = type;
        macapkSetTextImpl(t);
    }
    public void setTextIsSelectable(boolean selectable) {}
    /** AOSP :8398 getPrivateImeOptions reads Editor.InputContentType.
     *  privateImeOptions; this port flattens InputContentType onto TextView (see
     *  the setImeActionLabel block near the end of this file), so the setter
     *  that used to discard now stores. */
    public void setPrivateImeOptions(String type) { mPrivateImeOptions = type; }
    @Override public android.view.inputmethod.InputConnection onCreateInputConnection(
            android.view.inputmethod.EditorInfo outAttrs) { return null; }

    // Background helpers (TextView re-declares setBackgroundDrawable /
    // setBackgroundResource to do icon-tint propagation on real Android;
    // we forward to View.setBackground).
    @Override public void setBackgroundResource(int resId) {
        // MUST forward. This was `{}` -- an empty override that swallowed every
        // background-by-resource on any TextView/Button/CheckBox, contradicting
        // the comment directly above it ("we forward to View.setBackground").
        // Same shape as the setFrame stubs that zeroed every widget's layout
        // bounds; found by debug/stubscan.py.
        super.setBackgroundResource(resId);
    }
    @Override public void setBackgroundDrawable(android.graphics.drawable.Drawable d) {
        super.setBackgroundDrawable(d);
    }
    @Override public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        // AOSP setEnabled (:2440-2468) "start or stop the cursor blinking as appropriate":
        // isTextEditable() has an isEnabled() conjunct.
        if (mEditor != null) mEditor.makeBlink();
    }
    /** AOSP TextView.java:4416-4419: `return mTextPaint.getTypeface();`. It
     *  returned a hardcoded null, so an app reading its own typeface back --
     *  androidx does this to derive a bold variant of whatever the theme set --
     *  saw "no typeface" and started from Typeface.DEFAULT. */
    public android.graphics.Typeface getTypeface() { return getPaint().getTypeface(); }

    // Layout / lifecycle callbacks. View's onAttachedToWindow is
    // public, not protected; we just inherit. drawableStateChanged
    // and onLayout/onMeasure are protected -- override them on demand.
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {}
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {}

    // -------- v1.0.116 AOSP audit batch --------
    // Per cs.android.com/.../frameworks/base/core/java/android/widget/TextView.java:
    // getters and stateful methods F-Droid + androidx reference.

    private int gravity = android.view.Gravity.NO_GRAVITY;
    public int getGravity() { return gravity; }
    /** AOSP TextView.setGravity: stores the gravity for text-draw
     *  positioning + propagates to LayoutParams via Gravity bits. */
    // Note: setGravity is already declared above as void; promote to
    // store the value.
    public void macapkSetGravityValue(int g) { this.gravity = g; }

    private android.content.res.ColorStateList hintTextColors;
    /** AOSP's mCurHintTextColor (TextView.java:574): the hint colour resolved
     *  against the CURRENT drawable state, refreshed by updateTextColors
     *  (:7005-7013). Only meaningful while hintTextColors != null, which is
     *  exactly what getCurrentHintTextColor checks (:5899). */
    private int hintColor;
    /** ⚠ this used to read a SECOND, private `hint` field that nothing ever
     *  wrote, so it returned null for every view while setHint was filling
     *  mHint a thousand lines up. One hint, one field. */
    public CharSequence macapkGetHint() { return mHint; }
    public android.content.res.ColorStateList getHintTextColors() { return hintTextColors; }
    /** AOSP TextView.getCurrentHintTextColor (TextView.java:5897-5900):
     *      return mHintTextColor != null ? mCurHintTextColor : mCurTextColor;
     *  i.e. a TextView whose theme never supplied a textColorHint paints its
     *  hint at the ordinary text colour. onDraw's hint branch reads this. */
    public final int getCurrentHintTextColor() {
        return hintTextColors != null ? hintColor : textColor;
    }
    public void setHintTextColor(android.content.res.ColorStateList colors) {
        // AOSP (:5872-5875): store the list, then updateTextColors()
        // re-resolves mCurHintTextColor against the current drawable state.
        this.hintTextColors = colors;
        updateTextColors();
    }

    /** ⚠ v1.0.930: this used to be a DEAD FIELD STORE. `setLetterSpacing`
     *  wrote `this.letterSpacing` and nothing ever read it, so every Material
     *  label in the fleet measured and drew ~4% narrow.
     *
     *  AOSP backs BOTH accessors with the Paint (android11-release,
     *  core/java/android/widget/TextView.java:4503, 4518):
     *      public float getLetterSpacing() { return mTextPaint.getLetterSpacing(); }
     *      public void setLetterSpacing(float letterSpacing) {
     *          if (letterSpacing != mTextPaint.getLetterSpacing()) {
     *              mTextPaint.setLetterSpacing(letterSpacing);
     *              ...
     *  and `Paint` on our side ALREADY implements the measurement correctly
     *  (Paint.java:416-419, `(codePointCount - 1) * letterSpacing * textSize`,
     *  AOSP's EM-scaled gap with surrogate pairs counted once). Only the wire
     *  was missing.
     *
     *  MEASURED on F-Droid's bottom-nav labels, where M3's
     *  `TextAppearance.M3.Sys.Typescale.LabelMedium` sets
     *  letterSpacing=0.04166667 at 12sp (= 1.0px per gap at density 2):
     *  emulator vs ours, Nearby 80/75, Updates 95/87, Settings 95/87,
     *  Categories 125/114 -- each within a pixel of the missing
     *  (chars-1) * 1px. Equal glyph HEIGHT with unequal WIDTH is the
     *  signature of spacing, not size; our text size was already exactly 12sp.
     *  (The active "Latest" label does NOT fit that model -- 71 vs 70 -- so
     *  something additional differs on the selected item. Not explained.) */
    public float getLetterSpacing() {
        try { return getPaint().getLetterSpacing(); }
        catch (Throwable t) { return letterSpacing; }
    }
    public void setLetterSpacing(float spacing) {
        this.letterSpacing = spacing;
        try {
            android.text.TextPaint p = getPaint();
            if (p != null && p.getLetterSpacing() != spacing) {
                p.setLetterSpacing(spacing);
                requestLayout();
                invalidate();
            }
        } catch (Throwable ignored) {}
    }
    private float letterSpacing;

    /** AOSP TextView.getLineBounds(line, outBounds): writes the
     *  line's visible bounds (l, top, r, bottom) into outBounds and
     *  returns the baseline. We render a single line, so line 0 is
     *  the whole text. */
    public int getLineBounds(int line, android.graphics.Rect outBounds) {
        if (outBounds != null) outBounds.set(0, 0, getWidth(), getHeight());
        return (int) textSizePx;
    }

    private boolean linksClickable = true;
    public boolean getLinksClickable() { return linksClickable; }
    public void setLinksClickable(boolean clickable) { this.linksClickable = clickable; }

    public int getMinLines() {
        if (!MACAPK_TEXT_LINES_AOSP) return minLines;
        return mMinModeLines ? mMinimum : -1;
    }
    // AOSP: the max/min width in ems, or -1 when the bound was set in px
    // (setMaxWidth/setMinWidth/setWidth clear the EMS mode below).
    public int getMaxEms() { return mMaxEms; }
    public int getMinEms() { return mMinEms; }

    /** AOSP TextView.getMovementMethod(): returns the registered
     *  MovementMethod (scrollable text, link clicks). Typed per the
     *  AOSP signature; stored by setMovementMethod above. */
    public android.text.method.MovementMethod getMovementMethod() {
        return movementMethod;
    }

    /** AOSP TextView.getSelectionStart / getSelectionEnd / setSelection: the
     *  selection is stored as SELECTION_START/END marker spans on the editable
     *  text. v1.0.426 Tier-2: wire these to the persistent editable via Selection
     *  (was -1 / no-op), so EditText-based cursor/selection round-trips through
     *  the TextView API the same way it already does through Selection directly.
     *  Safe: getEditableText() is persistent (v1.0.365) and SpannableStringBuilder
     *  now tracks span offsets, so the markers ride edits. */
    public int getSelectionStart() { return android.text.Selection.getSelectionStart(getEditableText()); }
    public int getSelectionEnd()   { return android.text.Selection.getSelectionEnd(getEditableText()); }
    public boolean hasSelection() {
        int s = getSelectionStart(), e = getSelectionEnd();
        return s >= 0 && e >= 0 && s != e;
    }
    public void setSelection(int start, int stop) {
        android.text.Editable e = getEditableText();
        if (e instanceof android.text.Spannable) {
            int len = e.length();
            android.text.Selection.setSelection((android.text.Spannable) e,
                Math.max(0, Math.min(start, len)), Math.max(0, Math.min(stop, len)));
        }
    }
    public void setSelection(int index) { setSelection(index, index); }

    /** AOSP TextView.getEllipsize. The mode is honoured by onDraw and getLayout
     *  through macapkEffectiveEllipsize (gate MACAPK_TEXT_LINES_AOSP). */
    public android.text.TextUtils.TruncateAt getEllipsize() { return mEllipsize; }

    /** AOSP TextView.setCursorVisible / isCursorVisible (android14-release
     *  :12097-12155). Both were stubs -- isCursorVisible() a constant true,
     *  setCursorVisible() a discard -- so an app that hides the caret and reads
     *  the flag back (OpenCalculator's MainActivity does both, bytecode
     *  9468/9482) saw its write vanish. `visible && mEditor == null` returns
     *  early exactly as AOSP's does: visible is the default, so no Editor is
     *  created just to hold it. mImeIsConsumingInput is always false here (no
     *  IME ever consumes input in place), so the attr value is the value. */
    public void setCursorVisible(boolean visible) {
        if (!MACAPK_TEXT_CARET) return;
        mCursorVisibleFromAttr = visible;
        updateCursorVisibleInternal();
    }
    private void updateCursorVisibleInternal() {
        final boolean visible = mCursorVisibleFromAttr;
        if (visible && mEditor == null) return;
        createEditorIfNeeded();
        if (mEditor.mCursorVisible != visible) {
            mEditor.mCursorVisible = visible;
            invalidate();
            mEditor.makeBlink();
        }
    }
    public boolean isCursorVisible() {
        if (!MACAPK_TEXT_CARET) return true;
        // "true is the default value" (:12142)
        return mEditor == null ? true : mEditor.mCursorVisible;
    }
    /** AOSP :12153 (@hide). */
    public boolean isCursorVisibleFromAttr() { return mCursorVisibleFromAttr; }

    /** AOSP isTextEditable (:12876-12878):
     *      return mText instanceof Editable && onCheckIsTextEditor() && isEnabled();
     *  Package-private, as in AOSP; Editor.isCursorVisible() asks it. The
     *  Editable conjunct is carried by getEditableText() in this tree (see
     *  macapkIsTextEditor). */
    boolean isTextEditable() { return macapkIsTextEditor(); }

    /** See MACAPK_TEXT_EDITOR_AOSP for why this is behind a default-OFF flip. */
    @Override public boolean onCheckIsTextEditor() {
        if (!MACAPK_TEXT_EDITOR_AOSP) return super.onCheckIsTextEditor();
        return mEditor != null && (mInputType != android.text.InputType.TYPE_NULL
                                   || macapkEditableClassText);
    }

    // ---- caret lifecycle: AOSP forwards each to the Editor (:8494-8524,
    //      :12628-12634); the Blink runs only while attached and focused. ----
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mEditor != null) mEditor.onAttachedToWindow();
    }
    @Override public void onDetachedFromWindow() {
        if (mEditor != null) mEditor.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }
    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mEditor != null) mEditor.onWindowFocusChanged(hasWindowFocus);
    }
    @Override public void onScreenStateChanged(int screenState) {
        super.onScreenStateChanged(screenState);
        if (mEditor != null) mEditor.onScreenStateChanged(screenState);
    }

    /** AOSP TextView.mTextSelectHandle / mTextSelectHandleRes and the Left/
     *  Right counterparts (android11-release TextView.java:839-853). AOSP
     *  keeps the resolved Drawable separate from the resource id that
     *  produced it: setTextSelectHandle*(int) resolves through
     *  Context.getDrawable and caches the result, while
     *  setTextSelectHandle*(Drawable) stores an explicit Drawable directly
     *  and zeroes the res id so a later getter call can't overwrite it with
     *  a stale resource ("this might be stale if setTextSelectHandle* is
     *  used", AOSP's own comment at :839-840).
     *
     *  Referenced by telegram/vlc/thunderbird's own selection-handle theming
     *  code. AOSP's setter also calls mEditor.loadHandleDrawables() to
     *  repaint an on-screen handle popup immediately; this tree's Editor
     *  ports only the caret (see Editor.java), with no handle-popup renderer,
     *  so only the part an app can actually OBSERVE -- the stored value
     *  round-tripping through the getter -- is mirrored. */
    private android.graphics.drawable.Drawable mTextSelectHandle;
    private int mTextSelectHandleRes;
    private android.graphics.drawable.Drawable mTextSelectHandleLeft;
    private int mTextSelectHandleLeftRes;
    private android.graphics.drawable.Drawable mTextSelectHandleRight;
    private int mTextSelectHandleRightRes;

    /** AOSP TextView.setTextSelectHandle(Drawable) (TextView.java:3609-3618).
     *  Deviation: AOSP routes the null check through
     *  com.android.internal.util.Preconditions, which this tree does not
     *  have (same deviation already noted at TextUtils.makeSafeForPresentation);
     *  the identical check is inlined here, throwing the same
     *  NullPointerException. */
    public void setTextSelectHandle(android.graphics.drawable.Drawable textSelectHandle) {
        if (textSelectHandle == null) {
            throw new NullPointerException("The text select handle should not be null.");
        }
        mTextSelectHandle = textSelectHandle;
        mTextSelectHandleRes = 0;
    }
    /** AOSP TextView.setTextSelectHandle(int) (TextView.java:3630-3635). */
    public void setTextSelectHandle(int textSelectHandle) {
        if (textSelectHandle == 0) {
            throw new IllegalArgumentException(
                "The text select handle should be a valid drawable resource id.");
        }
        setTextSelectHandle(getContext().getDrawable(textSelectHandle));
    }
    /** AOSP TextView.getTextSelectHandle() (TextView.java:3649-3654): lazily
     *  resolves mTextSelectHandleRes into a Drawable on first read and caches
     *  it. AOSP's constructor fills mTextSelectHandleRes from
     *  TextView_textSelectHandle, i.e. the view's XML/style, else the THEME
     *  (Theme.Material and every AppCompat/MaterialComponents theme on it:
     *  @drawable/text_select_handle_middle_material), so on a device the
     *  getter is non-null for any themed TextView. That theme value is
     *  resolved lazily here, once, the way getTextCursorDrawable does it.
     *  React Native's ReactTextInputManager.setSelectionHandleColor does
     *  checkNotNull(view.textSelectHandle) on API 29+; a null here threw
     *  inside RN's UI-operation frame, which puts its UIManager in an illegal
     *  state and drops every later UI update (Xbox: taps did nothing). */
    public android.graphics.drawable.Drawable getTextSelectHandle() {
        if (mTextSelectHandle == null && mTextSelectHandleRes != 0) {
            mTextSelectHandle = getContext().getDrawable(mTextSelectHandleRes);
        } else if (mTextSelectHandle == null && !macapkSelectHandleResolved) {
            macapkSelectHandleResolved = true;
            int[] id = new int[1];
            try { mTextSelectHandle = macapkResolveAttrDrawable(macapkSelectHandleAttr, "android:textSelectHandle", id); }
            catch (Throwable ignored) {}
            if (id[0] != 0) mTextSelectHandleRes = id[0];
        }
        return mTextSelectHandle;
    }

    /** AOSP TextView.setTextSelectHandleLeft(Drawable) (TextView.java:3667-3675). */
    public void setTextSelectHandleLeft(android.graphics.drawable.Drawable textSelectHandleLeft) {
        if (textSelectHandleLeft == null) {
            throw new NullPointerException("The left text select handle should not be null.");
        }
        mTextSelectHandleLeft = textSelectHandleLeft;
        mTextSelectHandleLeftRes = 0;
    }
    /** AOSP TextView.setTextSelectHandleLeft(int) (TextView.java:3688-3692). */
    public void setTextSelectHandleLeft(int textSelectHandleLeft) {
        if (textSelectHandleLeft == 0) {
            throw new IllegalArgumentException(
                "The text select left handle should be a valid drawable resource id.");
        }
        setTextSelectHandleLeft(getContext().getDrawable(textSelectHandleLeft));
    }
    /** AOSP TextView.getTextSelectHandleLeft() (TextView.java:3706-3711). */
    public android.graphics.drawable.Drawable getTextSelectHandleLeft() {
        if (mTextSelectHandleLeft == null && mTextSelectHandleLeftRes != 0) {
            mTextSelectHandleLeft = getContext().getDrawable(mTextSelectHandleLeftRes);
        } else if (mTextSelectHandleLeft == null && !macapkSelectHandleLeftResolved) {
            macapkSelectHandleLeftResolved = true;
            int[] id = new int[1];
            try { mTextSelectHandleLeft = macapkResolveAttrDrawable(macapkSelectHandleLeftAttr, "android:textSelectHandleLeft", id); }
            catch (Throwable ignored) {}
            if (id[0] != 0) mTextSelectHandleLeftRes = id[0];
        }
        return mTextSelectHandleLeft;
    }

    /** AOSP TextView.setTextSelectHandleRight(Drawable) (TextView.java:3724-3732). */
    public void setTextSelectHandleRight(android.graphics.drawable.Drawable textSelectHandleRight) {
        if (textSelectHandleRight == null) {
            throw new NullPointerException("The right text select handle should not be null.");
        }
        mTextSelectHandleRight = textSelectHandleRight;
        mTextSelectHandleRightRes = 0;
    }
    /** AOSP TextView.setTextSelectHandleRight(int) (TextView.java:3745-3749). */
    public void setTextSelectHandleRight(int textSelectHandleRight) {
        if (textSelectHandleRight == 0) {
            throw new IllegalArgumentException(
                "The text select right handle should be a valid drawable resource id.");
        }
        setTextSelectHandleRight(getContext().getDrawable(textSelectHandleRight));
    }
    /** AOSP TextView.getTextSelectHandleRight() (TextView.java:3763-3768). */
    public android.graphics.drawable.Drawable getTextSelectHandleRight() {
        if (mTextSelectHandleRight == null && mTextSelectHandleRightRes != 0) {
            mTextSelectHandleRight = getContext().getDrawable(mTextSelectHandleRightRes);
        } else if (mTextSelectHandleRight == null && !macapkSelectHandleRightResolved) {
            macapkSelectHandleRightResolved = true;
            int[] id = new int[1];
            try { mTextSelectHandleRight = macapkResolveAttrDrawable(macapkSelectHandleRightAttr, "android:textSelectHandleRight", id); }
            catch (Throwable ignored) {}
            if (id[0] != 0) mTextSelectHandleRightRes = id[0];
        }
        return mTextSelectHandleRight;
    }

    /** AOSP TextView.setTextCursorDrawable / getTextCursorDrawable
     *  (frameworks/base core/java/android/widget/TextView.java, android15-release
     *  lines ~4097-4133). This API lives on TextView, not EditText -- AOSP's
     *  EditText declares no override. It was previously ONLY present as
     *  `EditText.getTextCursorDrawable()` hardcoded `return null;` with no
     *  setter and no backing field at all, so any caller that set a cursor
     *  drawable and read it back (or just called the setter) got either a
     *  NoSuchMethodError or a silently discarded value. Confirmed live in the
     *  corpus 2026-07-30 via javap -c on org.fossify.gallery's own guest
     *  bytecode: `invokevirtual android/widget/EditText.setTextCursorDrawable:
     *  (Landroid/graphics/drawable/Drawable;)V` and the getter counterpart.
     *  invokevirtual resolution walks EditText -> TextView, so defining this
     *  once here (and removing EditText's wrong override) satisfies that exact
     *  call. Since cluster E (MACAPK_TEXT_CARET) the value is also DRAWN: the
     *  Editor loads it as its caret drawable, exactly as AOSP's
     *  mEditor.loadCursorDrawable() does. */
    private android.graphics.drawable.Drawable mCursorDrawable;
    /** AOSP mCursorDrawableRes (:981), an APP drawable id. A FRAMEWORK drawable
     *  has no id in this tree and is resolved by name instead (see
     *  macapkResolveAttrDrawable). Field NAME kept: apps reflect on it. */
    int mCursorDrawableRes;
    /** AOSP :4009-4015. The Editor's copy is loaded once and not replaced, as
     *  on a device. */
    public void setTextCursorDrawable(android.graphics.drawable.Drawable textCursorDrawable) {
        mCursorDrawable = textCursorDrawable;
        mCursorDrawableRes = 0;
        macapkCursorDrawableResolved = true;   // an explicit set supersedes the theme
        if (mEditor != null) mEditor.loadCursorDrawable();
    }
    public void setTextCursorDrawable(int textCursorDrawableResId) {
        setTextCursorDrawable(textCursorDrawableResId != 0
                ? getContext().getDrawable(textCursorDrawableResId) : null);
    }
    /** AOSP :4042-4047:
     *      if (mCursorDrawable == null && mCursorDrawableRes != 0) {
     *          mCursorDrawable = mContext.getDrawable(mCursorDrawableRes);
     *      }
     *  where the constructor set mCursorDrawableRes from
     *  TextView_textCursorDrawable -- the view's XML/style, else the THEME
     *  (Theme.Material and every AppCompat/MaterialComponents theme built on
     *  it: @drawable/text_cursor_material; the legacy Theme: @null, which means
     *  the 1px path caret in the text colour). Resolved lazily here, once, so a
     *  TextView that never draws a caret never pays for the theme walk. */
    public android.graphics.drawable.Drawable getTextCursorDrawable() {
        if (MACAPK_TEXT_CARET && mCursorDrawable == null) {
            if (mCursorDrawableRes != 0) {
                try { mCursorDrawable = getContext().getDrawable(mCursorDrawableRes); }
                catch (Throwable ignored) {}
            } else if (!macapkCursorDrawableResolved) {
                macapkCursorDrawableResolved = true;
                try {
                    int[] id = new int[1];
                    mCursorDrawable = macapkResolveAttrDrawable(macapkCursorDrawableAttr,
                            "android:textCursorDrawable", id);
                    if (id[0] != 0) mCursorDrawableRes = id[0];
                }
                catch (Throwable ignored) {}
            }
        }
        return mCursorDrawable;
    }

    /** A TextView drawable attribute (textCursorDrawable, textSelectHandle*):
     *  the view's own value `ref`, else the theme's `themeAttr`, as a Drawable.
     *  An APP reference resolves to its id, returned in outRes[0] for the
     *  caller's mXxxRes field, as the AOSP constructor would have filled it.
     *  A FRAMEWORK reference -- the Material default, `@android:drawable/text_cursor_material`, measured as the
     *  resolved theme value for both Google Calculator (Theme.Legacy.Calculator)
     *  and OpenCalculator (AppTheme) -- goes to Resources' framework-drawable
     *  table (text_cursor_material itself excepted, see below). */
    private android.graphics.drawable.Drawable macapkResolveAttrDrawable(String ref, String themeAttr,
                                                                          int[] outRes) {
        final Context c = getContext();
        if (c == null) return null;
        if (ref == null) {
            android.content.res.Resources.Theme t = c.getTheme();
            if (t != null) ref = t.resolveAttrRaw(themeAttr);
        }
        if (ref == null) return null;
        ref = ref.trim();
        if (ref.isEmpty() || "@null".equals(ref) || "@empty".equals(ref) || ref.startsWith("?")) {
            return null;
        }
        final android.content.res.Resources res = c.getResources();
        if (res == null) return null;
        if (ref.startsWith("@0x") || (ref.length() > 1 && Character.isDigit(ref.charAt(1)))) {
            try {
                String num = ref.substring(1);
                int id = num.startsWith("0x") ? (int) Long.parseLong(num.substring(2), 16)
                                               : (int) Long.parseLong(num);
                if (id != 0) {
                    outRes[0] = id;
                    return c.getDrawable(id);
                }
            } catch (Throwable ignored) {}
            return null;
        }
        final String name = ref.substring(ref.lastIndexOf('/') + 1);
        if (!ref.startsWith("@android:")) {
            int id = 0;
            try { id = res.getIdentifier(name, "drawable", c.getPackageName()); }
            catch (Throwable ignored) {}
            if (id != 0) {
                outRes[0] = id;
                return c.getDrawable(id);
            }
            // An unqualified name the APP table lacks came out of a FRAMEWORK
            // style (a platform theme writes its own drawables unqualified), so
            // it falls through to the framework lookup below.
        }
        // text_cursor_material is built HERE, ahead of the platform table: the
        // table copy (Resources cluster D2) inflates its <shape android:tint=
        // "?attr/colorControlActivated"> WITHOUT the tint -- the shape inflater
        // does not read android:tint -- so it is a WHITE bar, invisible on a
        // light field. Measured offline 2026-09-26 against the working tree
        // (debug/caret_gate.sh theme row: the drawable draws, bar colour none).
        if ("text_cursor_material".equals(name)) return macapkTextCursorMaterial();
        android.graphics.drawable.Drawable d = null;
        try { d = res.macapkFrameworkDrawableByName(name); } catch (Throwable ignored) {}
        return d;
    }

    /** AOSP core/res/res/drawable/text_cursor_material.xml (android14-release;
     *  byte-identical in android11-release), built in code because no
     *  framework-drawable path in this tree produces it correctly yet:
     *      <inset android:inset="2dp">
     *          <shape android:tint="?attr/colorControlActivated" android:shape="rectangle">
     *              <size android:height="2dp" android:width="2dp" />
     *              <solid android:color="@color/white" />
     *  A white solid tinted SRC_IN is the tint colour, so the shape's colour IS
     *  colorControlActivated, resolved against THIS view's theme as
     *  Context.getDrawable would. Sizes follow the inflater's rounding: <size>
     *  via getDimensionPixelSize (round, at least 1), the inset via
     *  getDimensionPixelOffset (truncate). Fallback colour: Theme.Material.Light's
     *  colorAccent, material_deep_teal_500 #009688 (colors_material.xml).
     *  REMOVAL CONDITION: delete this (and the name check that routes to it in
     *  macapkResolveAttrDrawable) once the platform-table copy of
     *  text_cursor_material carries its tint -- the drawable inflater reading
     *  <shape android:tint> and applying it SRC_IN, as AOSP's GradientDrawable
     *  does. Until then the table copy is a WHITE bar. */
    private android.graphics.drawable.Drawable macapkTextCursorMaterial() {
        final Context c = getContext();
        float density = android.util.DisplayMetrics.macapkDeviceDensity();
        try {
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            if (dm != null && dm.density > 0f) density = dm.density;
        } catch (Throwable ignored) {}
        final int size = Math.max(1, (int) (2f * density + 0.5f));
        final int inset = (int) (2f * density);
        int tint = 0;
        try {
            android.content.res.Resources.Theme t = c.getTheme();
            if (t != null) {
                tint = t.resolveAttrColor("android:colorControlActivated", 0);
                if (tint == 0) tint = t.resolveAttrColor("colorControlActivated", 0);
                if (tint == 0) tint = t.resolveAttrColor("android:colorAccent", 0);
                if (tint == 0) tint = t.resolveAttrColor("colorAccent", 0);
            }
        } catch (Throwable ignored) {}
        if (tint == 0) tint = 0xFF009688;
        final android.graphics.drawable.GradientDrawable shape =
            new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setSize(size, size);
        shape.setColor(tint);
        return new android.graphics.drawable.InsetDrawable(shape, inset);
    }

    /** Gate MACAPK_EDITOR_ACTION_DEFAULTS (default ON; =0 restores the old
     *  soft-route ENTER, which only called the OnEditorActionListener, and the
     *  old empty onEditorAction(int)). */
    static final boolean MACAPK_EDITOR_ACTION_DEFAULTS =
        !"0".equals(System.getenv("MACAPK_EDITOR_ACTION_DEFAULTS"));

    /** AOSP TextView.onEditorAction(int) (android14-release TextView.java:7963):
     *
     *      final Editor.InputContentType ict = mEditor == null ? null : mEditor.mInputContentType;
     *      if (ict != null) {
     *          if (ict.onEditorActionListener != null) {
     *              if (ict.onEditorActionListener.onEditorAction(this, actionCode, null)) return;
     *          }
     *          if (actionCode == EditorInfo.IME_ACTION_NEXT) {
     *              View v = focusSearch(FOCUS_FORWARD);
     *              if (v != null) { if (!v.requestFocus(FOCUS_FORWARD)) throw new IllegalStateException(...); }
     *              return;
     *          } else if (actionCode == EditorInfo.IME_ACTION_PREVIOUS) { ...FOCUS_BACKWARD...; return;
     *          } else if (actionCode == EditorInfo.IME_ACTION_DONE) {
     *              InputMethodManager imm = getInputMethodManager();
     *              if (imm != null && imm.isActive(this)) imm.hideSoftInputFromWindow(getWindowToken(), 0);
     *              return;
     *          }
     *      }
     *      ViewRootImpl viewRootImpl = getViewRootImpl();
     *      if (viewRootImpl != null) { ...dispatchKeyFromIme(ENTER down/up, FLAG_EDITOR_ACTION)... }
     *
     *  The IME calls this for its action button; on Mac.apk the host keyboard IS
     *  the IME, so the soft-input ENTER route (macapkHandleSoftKey with no
     *  KeyEvent) calls it. It used to be `{}` and that route called only the
     *  listener, so a field declaring imeOptions="actionNext" never advanced
     *  focus on Enter, actionDone never closed input, and a subclass override
     *  (NumberPicker.CustomEditText commits the typed value in it) never ran.
     *  SCOPED DELTA, unchanged: the trailing FLAG_EDITOR_ACTION ENTER key
     *  re-dispatch is not modelled (the caller consumes the key, as before). */
    public void onEditorAction(int actionCode) {
        if (!MACAPK_EDITOR_ACTION_DEFAULTS) return;   // =0: the old empty method
        if (!macapkInputContentType) return;
        if (editorActionListener != null) {
            if (editorActionListener.onEditorAction(this, actionCode, null)) return;
        }
        if (actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
            View v = focusSearch(FOCUS_FORWARD);
            if (v != null) {
                if (!v.requestFocus(FOCUS_FORWARD)) {
                    throw new IllegalStateException("focus search returned a view "
                            + "that wasn't able to take focus!");
                }
            }
        } else if (actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS) {
            View v = focusSearch(FOCUS_BACKWARD);
            if (v != null) {
                if (!v.requestFocus(FOCUS_BACKWARD)) {
                    throw new IllegalStateException("focus search returned a view "
                            + "that wasn't able to take focus!");
                }
            }
        } else if (actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                    getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null && imm.isActive(this)) {
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        }
    }
    public boolean onPreDraw() { return true; }

    /** AOSP TextView.computeScroll: scrolls the text to keep cursor
     *  visible. No-op without scrolling. */
    public void computeScroll() {}

    /** AOSP TextView.invalidateDrawable: subclasses re-invalidate
     *  when a compound drawable changes. */
    @Override public void invalidateDrawable(android.graphics.drawable.Drawable drawable) {
        // FRAMESPEED caret: View.invalidateDrawable schedules through a
        // NON-virtual path (it must not re-enter an overridden invalidate()), so
        // the invalidate overrides above never see it; on a device it re-records
        // the view like any other invalidate.
        if (mEditor != null) mEditor.macapkNoteInvalidated();
        super.invalidateDrawable(drawable);
    }

    // onSaveInstanceState / onRestoreInstanceState removed -- no
    // longer present on the base View class either.

    /** AOSP TextView.onTouchEvent (frameworks/base core/java/android/widget/
     *  TextView.java:13178) forwards the event to the registered
     *  MovementMethod once super has run:
     *
     *      if ((mMovement != null || onCheckIsTextEditor()) && isEnabled()
     *              && mText instanceof Spannable && mLayout != null) {
     *          if (mMovement != null)
     *              handled |= mMovement.onTouchEvent(this, mSpannable, event);
     *
     *  That dispatch was missing here entirely -- the movementMethod field was
     *  stored by setMovementMethod and then never read -- which is one of the
     *  reasons tapping a ClickableSpan/URLSpan does nothing. Mirrored verbatim,
     *  including the `mLayout != null` guard: LinkMovementMethod hit-tests
     *  through the Layout, so dispatching without one would crash it. */
    @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
        // AOSP :12719-12733: the Editor sees the event BEFORE super, so its
        // ACTION_DOWN reset of mTouchFocusSelected precedes the focus change
        // super's ACTION_UP may cause.
        final Editor ed = mEditor;
        if (ed != null && ev != null) ed.onTouchEvent(ev);
        boolean handled = super.onTouchEvent(ev);
        android.text.method.MovementMethod mm = movementMethod;
        if (mm != null && isEnabled() && text instanceof android.text.Spannable
                && getLayout() != null) {
            try {
                handled |= mm.onTouchEvent(this, (android.text.Spannable) text, ev);
            } catch (Throwable ignored) {}
        }
        // AOSP :12761-12790: a FINISHED tap (ACTION_UP while focused -- super
        // has just taken focus for a focusableInTouchMode field) on an editable
        // view moves the caret to the tapped offset via
        // Editor.onTouchUpEvent -> getOffsetForPosition -> Selection.setSelection.
        // The AOSP guard's `mText instanceof Spannable` is carried by
        // getEditableText() here. The showSoftInput half is left to the IME path.
        if (ed != null && ev != null
                && ev.getActionMasked() == android.view.MotionEvent.ACTION_UP
                && isFocused() && isEnabled() && getLayout() != null && isTextEditable()) {
            try { ed.onTouchUpEvent(ev); } catch (Throwable ignored) {}
            handled = true;
        }
        return handled;
    }

    @Override public void setSelected(boolean s) { super.setSelected(s); }

    /** AOSP TextView.setExtractedText: set raw text from an IME extracted
     *  text update. No-op. */
    public void setExtractedText(android.view.inputmethod.ExtractedText text) {}
    // AOSP signature is extractText(ExtractedTextRequest, ExtractedText).
    // The first param was Object, which mismatches the bytecode descriptor;
    // ExtractedTextRequest exists in our tree so use the typed form.
    public boolean extractText(android.view.inputmethod.ExtractedTextRequest request,
                                android.view.inputmethod.ExtractedText outText) {
        return false;
    }

    /** AOSP TextView.bringPointIntoView / moveCursorToVisibleOffset. */
    public boolean bringPointIntoView(int offset) { return false; }
    public boolean moveCursorToVisibleOffset() { return false; }

    /** AOSP TextView.invalidateDrawable + drawableHotspotChanged. */
    public void onLocaleChanged() {}
    public void clearComposingText() {}
    /** AOSP :12886-12888. */
    public boolean didTouchFocusSelect() { return mEditor != null && mEditor.mTouchFocusSelected; }

    /** AOSP :12543 getMarqueeRepeatLimit() reads mMarqueeRepeatLimit. The
     *  setter used to discard. This port runs no marquee animation, so the limit
     *  is state only. */
    public void setMarqueeRepeatLimit(int marqueeLimit) { mMarqueeRepeatLimit = marqueeLimit; }

    protected boolean drawChild(android.graphics.Canvas canvas, android.view.View child, long drawingTime) { return false; }
    public int getCompoundPaddingStart() { return getCompoundPaddingLeft(); }
    public int getCompoundPaddingEnd() { return getCompoundPaddingRight(); }
    public int getExtendedPaddingTop() { return getPaddingTop(); }
    public int getExtendedPaddingBottom() { return getPaddingBottom(); }
    public int getTotalPaddingStart() { return getCompoundPaddingStart(); }
    public int getTotalPaddingEnd() { return getCompoundPaddingEnd(); }
    // AOSP TextView.getLineBreakWordStyle/setLineBreakWordStyle was
    // hardcoded 0 with a no-op setter. Default 0 verified against AOSP
    // (TextView.java DEFAULT_LINE_BREAK_WORD_STYLE = LineBreakConfig.
    // LINE_BREAK_WORD_STYLE_NONE = 0), so the round-trip fix keeps the same
    // default an unconfigured view already had.
    private int mLineBreakWordStyle;
    public int getLineBreakWordStyle() { return mLineBreakWordStyle; }
    public void setLineBreakWordStyle(int lineBreakWordStyle) { mLineBreakWordStyle = lineBreakWordStyle; }
    public android.text.style.URLSpan[] getUrls() { return new android.text.style.URLSpan[0]; }
    public boolean isTextSelectable() { return false; }
    public boolean onKeyPreIme(int keyCode, android.view.KeyEvent event) { return false; }
    protected void onSelectionChanged(int selStart, int selEnd) { }
    /** AOSP :3929 getAutoLinkMask() reads mAutoLinkMask. The setter used to
     *  discard. NOTE this stores the mask only: this port does not run Linkify
     *  over the text, so setting a mask still creates no URLSpans -- that is a
     *  text-linkification gap, separate from the accessor pair. */
    public void setAutoLinkMask(int mask) { mAutoLinkMask = mask; }
    /** AOSP :5122 isFallbackLineSpacing() reads mUseFallbackLineSpacing, whose
     *  default is ON. The setter used to discard.
     *  ⚠ Deliberately state-only: AOSP's setter also nulls the layout and
     *  re-measures, and TextView measurement in this tree is verified correct
     *  (dh=0 per text line against the emulator), so this lane does not touch
     *  it. */
    public void setFallbackLineSpacing(boolean enabled) { mUseFallbackLineSpacing = enabled; }
    /** AOSP backs setImeActionLabel/getImeActionLabel/getImeActionId with
     *  Editor.InputContentType.imeActionLabel/imeActionId
     *  (android11-release TextView.java:6989-7018); this tree has no Editor
     *  class (same simplification as the text-select-handle block above and
     *  the existing mImeOptions field a few hundred lines up), so the pair
     *  is flattened onto TextView directly. This method used to be `{ }` --
     *  it took both arguments and silently discarded them -- which left no
     *  way for the getters below to ever report anything but their
     *  defaults, so fixing the body is required for them to satisfy AOSP's
     *  contract rather than a cosmetic touch-up. Referenced by
     *  telegram/vlc/thunderbird, which set a custom IME action (e.g. "Send")
     *  and expect to read it back via the getters. */
    private CharSequence mImeActionLabel;
    private int mImeActionId;
    public void setImeActionLabel(CharSequence label, int actionId) {
        mImeActionLabel = label;
        mImeActionId = actionId;
    }
    /** AOSP TextView.getImeActionLabel() (TextView.java:7002-7006). Null
     *  until setImeActionLabel has been called, matching AOSP's own default
     *  branch (mEditor == null || mInputContentType == null). */
    public CharSequence getImeActionLabel() { return mImeActionLabel; }
    /** AOSP TextView.getImeActionId() (TextView.java:7014-7018). Zero until
     *  setImeActionLabel has been called, matching AOSP's own default. */
    public int getImeActionId() { return mImeActionId; }

    /** AOSP TextView.getInputExtras(boolean) (TextView.java:7172-7184):
     *      if (mEditor == null && !create) return null;
     *      createEditorIfNeeded();
     *      if (mEditor.mInputContentType == null) {
     *          if (!create) return null;
     *          mEditor.createInputContentTypeIfNeeded();
     *      }
     *      if (mEditor.mInputContentType.extras == null) {
     *          if (!create) return null;
     *          mEditor.mInputContentType.extras = new Bundle();
     *      }
     *      return mEditor.mInputContentType.extras;
     *  Collapsed onto one Bundle field the way the fields just above flatten
     *  the rest of Editor.InputContentType: null when nothing has been
     *  created yet and create is false, otherwise lazily allocated once and
     *  the SAME instance returned on every later call so a caller's edits to
     *  the returned Bundle persist ("can be viewed as well as modified", the
     *  AOSP javadoc). AOSP's create=true path can also be reached from
     *  setInputExtras(int), which parses an <input-extras> XML resource into
     *  the bundle; that setter is out of this lane's scope (nothing else in
     *  this tree reads or writes it), so here create=true always starts from
     *  an empty Bundle, same as an AOSP TextView whose XML never declared
     *  android:editorExtras. */
    private android.os.Bundle mInputExtras;
    public android.os.Bundle getInputExtras(boolean create) {
        if (mInputExtras == null && create) {
            mInputExtras = new android.os.Bundle();
        }
        return mInputExtras;
    }
    /** AOSP :8475 getImeHintLocales() reads Editor.InputContentType.
     *  imeHintLocales -- flattened onto TextView here like the rest of that
     *  struct. The setter used to discard, so the getter (which
     *  com.ichi2.anki actually calls -- one of only five android.widget gaps
     *  the 56-app corpus references) could not have answered correctly. */
    public void setImeHintLocales(android.os.LocaleList hintLocales) {
        mImeHintLocales = hintLocales;
    }
    /** AOSP :5926. */
    public void setLinkTextColor(android.content.res.ColorStateList colors) {
        mLinkTextColor = colors;
        updateTextColors();
    }
    /** AOSP :7555-7559 also stores the type on the Editor; this port keeps the
     *  pre-existing "no store" behaviour (getInputType is an IME-lane answer)
     *  and mirrors only the Editor creation. */
    public void setRawInputType(int type) {
        if (type == android.text.InputType.TYPE_NULL && mEditor == null) return;
        createEditorIfNeeded();
    }
    public void setSpannableFactory(android.text.Spannable.Factory factory) { }

    // ==================================================================
    // API-36 surface completion (apidiff: android.widget.TextView had 59 public
    // members missing -- the largest single class gap in android.widget).
    // Bodies mirror frameworks/base/core/java/android/widget/TextView.java
    // (android16-release); cited line numbers are from that file.
    //
    // ⚠ SCOPE RULE APPLIED THROUGHOUT THIS BLOCK. TextView is on the hot path of
    // every screen this runtime draws, and its MEASUREMENT is verified correct
    // (dh=0 on every text line against the emulator). So nothing here nulls a
    // layout, re-measures, or changes padding. Where AOSP's setter has a layout
    // side effect, the value is stored and the side effect is called out in the
    // comment instead of being smuggled in -- see setFirstBaselineToTopHeight /
    // setLastBaselineToBottomHeight below, which are the two that matter.
    // ==================================================================

    private int mHighlightColor;
    private android.content.res.ColorStateList mLinkTextColor;
    private String mPrivateImeOptions;
    private android.os.LocaleList mImeHintLocales;
    private int mAutoLinkMask;
    private int mMarqueeRepeatLimit = 3;   // AOSP field default
    private boolean mUseFallbackLineSpacing = true;  // AOSP default is ON
    private boolean mFreezesText;
    private int mJustificationMode;        // Layout.JUSTIFICATION_MODE_NONE
    private int mLineBreakStyle;           // LINE_BREAK_STYLE_NONE
    private boolean mUseBoundsForWidth;
    private boolean mShiftDrawingOffsetForStartOverhang;
    private boolean mUseLocalePreferredLineHeightForMinimum;
    private android.graphics.Paint.FontMetrics mMinimumFontMetrics;
    private int[] mSearchResultHighlights;
    private int mFocusedSearchResultIndex = FOCUSED_SEARCH_RESULT_INDEX_NONE;
    private int mSearchResultHighlightColor;
    private int mFocusedSearchResultHighlightColor;
    private android.graphics.BlendMode mDrawableBlendMode;
    private Scroller mScroller;

    /** AOSP :6741 -- "no focused search result". */
    public static final int FOCUSED_SEARCH_RESULT_INDEX_NONE = -1;

    // ---- plain reads of the fields the setters above now keep ----------
    /** AOSP :3929 -- final in AOSP. */
    public final int getAutoLinkMask() { return mAutoLinkMask; }
    /** AOSP :5672 / :5941 / :12543 / :8398 / :8475. */
    public int getHighlightColor() { return mHighlightColor; }
    public final android.content.res.ColorStateList getLinkTextColors() { return mLinkTextColor; }
    public int getMarqueeRepeatLimit() { return mMarqueeRepeatLimit; }
    public String getPrivateImeOptions() { return mPrivateImeOptions; }
    public android.os.LocaleList getImeHintLocales() { return mImeHintLocales; }
    /** AOSP :5122 -- true by default. */
    public boolean isFallbackLineSpacing() { return mUseFallbackLineSpacing; }

    /** AOSP :7197 / :7210 -- whether the whole text is written into the saved
     *  instance state. AOSP's EditText always answers true; a plain TextView
     *  answers the stored flag. */
    public void setFreezesText(boolean freezesText) { mFreezesText = freezesText; }
    public boolean getFreezesText() { return mFreezesText; }

    /** AOSP :6225 / :6434. The -1 answer is not a placeholder: AOSP returns -1
     *  when the maximum was last set in EMS or LINES rather than pixels, because
     *  there is no pixel answer to give. This port keeps the pixel maximum in
     *  explicitMaxHeight/explicitMaxWidth and the em maximum in mMaxEms, and
     *  setMaxWidth() already clears mMaxEms -- so "mMaxEms >= 0" is exactly
     *  AOSP's "mMaxWidthMode != PIXELS". */
    public int getMaxHeight() {
        // Gate ON reads AOSP's shared slot, so setHeight(px) answers px here
        // too (AOSP: `mMaxMode == PIXELS ? mMaximum : -1`).
        if (MACAPK_TEXT_LINES_AOSP) return mMaxModeLines ? -1 : mMaximum;
        return mMaxHeightInPixels ? explicitMaxHeight : -1;
    }
    /** AOSP mMaxMode -- LINES by default, so an untouched TextView answers -1
     *  from getMaxHeight(), NOT its internal Integer.MAX_VALUE. */
    private boolean mMaxHeightInPixels;
    public int getMaxWidth() { return mMaxEms >= 0 ? -1 : (MACAPK_TEXT_LINES_AOSP ? mMaxWidthPx : explicitMaxWidth); }

    // ---- paint-backed properties ---------------------------------------
    /** AOSP :4573 / :5146 / :4888 -- all three read or write the TEXT PAINT,
     *  not a TextView field, which this tree's Paint supports directly.
     *  setElegantTextHeight does NOT re-measure here (see the scope rule at the
     *  top of this block); AOSP's does. */
    public android.os.LocaleList getTextLocales() { return getPaint().getTextLocales(); }
    public boolean isElegantTextHeight() { return getPaint().isElegantTextHeight(); }
    public void setElegantTextHeight(boolean elegant) {
        if (elegant != getPaint().isElegantTextHeight()) {
            getPaint().setElegantTextHeight(elegant);
            invalidate();
        }
    }

    /** AOSP :4783 -- the UNIT the last setTextSize(unit, size) used. This
     *  class's setTextSize(int unit, float size) records it below. */
    public int getTextSizeUnit() { return mTextSizeUnit; }
    private int mTextSizeUnit = android.util.TypedValue.COMPLEX_UNIT_SP;

    // ---- line breaking / justification ---------------------------------
    /** AOSP :5443 / :5461 / :5341 / :5386. Stored, not re-measured (scope rule):
     *  this port's onMeasure does not consult either value, so nulling a layout
     *  would only cost a re-measure and change no pixel. */
    public void setJustificationMode(int justificationMode) {
        mJustificationMode = justificationMode;
        invalidate();
    }
    public int getJustificationMode() { return mJustificationMode; }
    public void setLineBreakStyle(int lineBreakStyle) {
        if (mLineBreakStyle != lineBreakStyle) { mLineBreakStyle = lineBreakStyle; invalidate(); }
    }
    public int getLineBreakStyle() { return mLineBreakStyle; }

    /** AOSP :4956 / :4976 / :5003 / :5023 -- the "measure text by its GLYPH
     *  BOUNDS instead of its advances" pair (API 34), for scripts whose glyphs
     *  overhang their advance width. Stored only: honouring them WOULD change
     *  measured width, and TextView measurement in this tree is verified
     *  correct, so this lane does not touch it. */
    public void setUseBoundsForWidth(boolean useBoundsForWidth) {
        mUseBoundsForWidth = useBoundsForWidth;
    }
    public boolean getUseBoundsForWidth() { return mUseBoundsForWidth; }
    public void setShiftDrawingOffsetForStartOverhang(boolean shift) {
        mShiftDrawingOffsetForStartOverhang = shift;
    }
    public boolean getShiftDrawingOffsetForStartOverhang() {
        return mShiftDrawingOffsetForStartOverhang;
    }

    /** AOSP :5057 / :5075 / :5110 / :5090 -- the minimum font metrics used for
     *  line spacing (API 34). AOSP's setter is a bare field assignment too. */
    public void setMinimumFontMetrics(android.graphics.Paint.FontMetrics minimumFontMetrics) {
        mMinimumFontMetrics = minimumFontMetrics;
    }
    public android.graphics.Paint.FontMetrics getMinimumFontMetrics() { return mMinimumFontMetrics; }
    public void setLocalePreferredLineHeightForMinimumUsed(boolean flag) {
        mUseLocalePreferredLineHeightForMinimum = flag;
    }
    public boolean isLocalePreferredLineHeightForMinimumUsed() {
        return mUseLocalePreferredLineHeightForMinimum;
    }

    // ---- baseline distances --------------------------------------------
    /** AOSP :3901 / :3912 -- pure reads: the first baseline sits
     *  |fontMetrics.top| below the top padding, the last sits
     *  fontMetrics.bottom above the bottom padding. Exact, and safe.
     *  ⚠ THE MATCHING SETTERS ARE NOT WIRED. AOSP's
     *  setFirstBaselineToTopHeight/setLastBaselineToBottomHeight work by CALLING
     *  setPadding() to make the requested distance come out; this file's two
     *  setters (a few hundred lines up) are inert, and wiring them would change
     *  padding and therefore measurement -- which this lane is explicitly not
     *  touching. So these getters answer the view's ACTUAL baseline distance
     *  (which is AOSP's definition) but will not reflect a preceding
     *  setFirstBaselineToTopHeight() call. Reported as a known, deliberate gap. */
    public int getFirstBaselineToTopHeight() {
        return getPaddingTop() - getPaint().getFontMetricsInt().top;
    }
    public int getLastBaselineToBottomHeight() {
        return getPaddingBottom() + getPaint().getFontMetricsInt().bottom;
    }

    // ---- search-result highlighting (API 34) ---------------------------
    /** AOSP :6694 -- a FLATTENED range array (start,end,start,end,...), so an
     *  odd length is rejected. AOSP's own validation is reproduced, including
     *  the null-clears-it case. */
    public void setSearchResultHighlights(int... ranges) {
        if (ranges == null) {
            mSearchResultHighlights = null;
            return;
        }
        if (ranges.length % 2 == 1) {
            throw new IllegalArgumentException("Flatten ranges must have even numbered elements");
        }
        for (int j = 0; j < ranges.length / 2; ++j) {
            final int start = ranges[j * 2];
            final int end = ranges[j * 2 + 1];
            if (start > end) {
                throw new IllegalArgumentException(
                        "Reverse range found in the flatten range: " + start + ", " + end);
            }
        }
        mFocusedSearchResultIndex = FOCUSED_SEARCH_RESULT_INDEX_NONE;
        mSearchResultHighlights = ranges;
        invalidate();
    }
    public int[] getSearchResultHighlights() { return mSearchResultHighlights; }

    /** AOSP :6768 -- both preconditions are AOSP's, and they are the whole
     *  observable contract of this setter, so they are reproduced exactly. */
    public void setFocusedSearchResultIndex(int index) {
        if (mSearchResultHighlights == null) {
            throw new IllegalArgumentException("Search result range must be set beforehand.");
        }
        if (index < -1 || index >= mSearchResultHighlights.length / 2) {
            throw new IllegalArgumentException("Focused index(" + index + ") must be larger than "
                    + "-1 and less than range count(" + (mSearchResultHighlights.length / 2) + ")");
        }
        mFocusedSearchResultIndex = index;
        invalidate();
    }
    public int getFocusedSearchResultIndex() { return mFocusedSearchResultIndex; }

    /** AOSP :6823 / :6847 / :6870 / :6894 -- bare field assignments in AOSP too. */
    public void setSearchResultHighlightColor(int color) { mSearchResultHighlightColor = color; }
    public int getSearchResultHighlightColor() { return mSearchResultHighlightColor; }
    public void setFocusedSearchResultHighlightColor(int color) {
        mFocusedSearchResultHighlightColor = color;
    }
    public int getFocusedSearchResultHighlightColor() { return mFocusedSearchResultHighlightColor; }

    // ---- compound-drawable tint blend mode ------------------------------
    /** AOSP :3683 / :3720. The PorterDuff.Mode pair already exists above and is
     *  what applyCompoundDrawableTint pushes into the drawables; the BlendMode
     *  is the exact round-trip value, converted through MacApkBlendModes
     *  (AOSP's own BlendMode.fromValue / blendModeToPorterDuffMode mapping). */
    public void setCompoundDrawableTintBlendMode(android.graphics.BlendMode blendMode) {
        mDrawableBlendMode = blendMode;
        setCompoundDrawableTintMode(MacApkBlendModes.toPorterDuff(blendMode));
    }
    public android.graphics.BlendMode getCompoundDrawableTintBlendMode() {
        return mDrawableBlendMode;
    }

    // ---- text setting ---------------------------------------------------
    /** AOSP :7560 -- set the text from a char[] window. AOSP wraps the array in
     *  a reusable CharWrapper so no copy is made; we copy into a String, which
     *  is observably identical to the caller (AOSP's own javadoc warns the array
     *  must not be mutated afterwards, so a copy is strictly safer). The bounds
     *  check is AOSP's, including throwing IndexOutOfBoundsException. */
    public final void setText(char[] text, int start, int len) {
        if (text == null || start < 0 || len < 0 || start + len > text.length) {
            throw new IndexOutOfBoundsException(start + ", " + len);
        }
        setText(new String(text, start, len));
    }

    /** AOSP :7301 / :7605 -- set the text but preserve the selection, clamped
     *  into the new text's length. */
    public final void setTextKeepState(CharSequence text) {
        setTextKeepState(text, BufferType.NORMAL);
    }
    public final void setTextKeepState(CharSequence text, BufferType type) {
        final int start = getSelectionStart();
        final int end = getSelectionEnd();
        final int len = text == null ? 0 : text.length();

        setText(text, type);

        if (start >= 0 || end >= 0) {
            final android.text.Editable e = getEditableText();
            if (e != null) {
                android.text.Selection.setSelection(e,
                        Math.max(0, Math.min(start, len)),
                        Math.max(0, Math.min(end, len)));
            }
        }
    }

    /** AOSP :5418 -- adopt a PrecomputedText.Params wholesale: the paint, the
     *  text direction, the break strategy, the hyphenation frequency and the
     *  line-break config. Applying it makes a later
     *  PrecomputedText.create(text, params) safe to hand to setText, which is
     *  the entire point of the API. */
    public void setTextMetricsParams(android.text.PrecomputedText.Params params) {
        if (params == null) return;
        getPaint().set(params.getTextPaint());
        mBreakStrategy = params.getBreakStrategy();
        mHyphenationFrequency = params.getHyphenationFrequency();
        final android.graphics.text.LineBreakConfig cfg = params.getLineBreakConfig();
        if (cfg != null) {
            mLineBreakStyle = cfg.getLineBreakStyle();
            setLineBreakWordStyle(cfg.getLineBreakWordStyle());
        }
        requestLayout();
        invalidate();
    }

    // ---- IME plumbing ---------------------------------------------------
    /** AOSP :10349 / :10357 / :10320 / :10378 -- all four are "intentionally
     *  empty" or `return false` in AOSP itself; they exist so a subclass can
     *  hook the IME's batch-edit / completion / private-command traffic. */
    public void onBeginBatchEdit() { }
    public void onEndBatchEdit() { }
    public void onCommitCompletion(android.view.inputmethod.CompletionInfo text) { }
    public boolean onPrivateIMECommand(String action, android.os.Bundle data) { return false; }

    /** AOSP :10333 -- delegates to Editor.onCommitCorrection, which flashes the
     *  corrected word's background. There is no Editor in this tree (the same
     *  simplification the text-select-handle block above documents) and no
     *  correction-flash animation, so AOSP's `mEditor == null` path applies. */
    public void onCommitCorrection(android.view.inputmethod.CorrectionInfo info) { }

    /** AOSP :8414 -- parse an <input-extras> XML resource into the input-extras
     *  Bundle that getInputExtras(true) hands back. AOSP declares it
     *  `throws XmlPullParserException, IOException`; the API-36 signature file
     *  lists it without a throws clause on the compiled form, and this tree's
     *  Resources.parseBundleExtras is a no-op (Resources.java:5125), so the
     *  bundle is created empty -- the same state an AOSP TextView is in when the
     *  named resource declares no extras. */
    public void setInputExtras(int xmlResId) {
        final android.os.Bundle extras = getInputExtras(true);
        try {
            getResources().parseBundleExtras(getResources().getXml(xmlResId), extras);
        } catch (Throwable ignored) { }
    }

    /** AOSP :15174 -- is this view the IME's current target? */
    public boolean isInputMethodTarget() {
        try {
            final Object o = getContext().getSystemService(
                    android.content.Context.INPUT_METHOD_SERVICE);
            if (o instanceof android.view.inputmethod.InputMethodManager) {
                return ((android.view.inputmethod.InputMethodManager) o).isActive(this);
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** AOSP :15360 -- suggestions are offered only for plain-ish TEXT input:
     *  the class must be TYPE_CLASS_TEXT, the NO_SUGGESTIONS flag must be
     *  clear, and the variation must be one AOSP whitelists. The variation list
     *  is AOSP's, verbatim. */
    public boolean isSuggestionsEnabled() {
        final int inputType = getInputType();
        if ((inputType & android.text.InputType.TYPE_MASK_CLASS)
                != android.text.InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        if ((inputType & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) > 0) return false;
        // AOSP names these off EditorInfo, which `implements InputType` -- so
        // EditorInfo.TYPE_* IS InputType.TYPE_*. This tree's EditorInfo does not
        // declare the interface (an android.view.inputmethod gap), so the same
        // constants are read from their defining class instead.
        final int variation = inputType & android.text.InputType.TYPE_MASK_VARIATION;
        return variation == android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                || variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT
                || variation == android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
                || variation == android.text.InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
                || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
    }

    // ---- subclass hooks --------------------------------------------------
    /** AOSP :2563 / :2570 -- protected, and this is exactly how EditText turns
     *  a TextView into an editable one (it overrides getDefaultEditable to
     *  return true and getDefaultMovementMethod to return ArrowKeyMovementMethod).
     *  A plain TextView answers false/null, which is AOSP's body verbatim. */
    protected boolean getDefaultEditable() { return false; }
    protected android.text.method.MovementMethod getDefaultMovementMethod() { return null; }

    /** AOSP :13458 -- the Scroller a marquee/scrolling TextView animates with.
     *  AOSP's body is the bare field assignment. This port runs no marquee, so
     *  nothing steps the scroller. */
    public void setScroller(Scroller s) { mScroller = s; }

    /** AOSP :8533 -- protected; AOSP re-runs the marquee and lets the Editor
     *  reposition after the frame moves. Neither exists here, so this is the
     *  plain super call, which is what AOSP's `mEditor == null` +
     *  no-marquee state also reduces to.
     *  NOTE: no @Override -- this tree's android.view.View does not declare
     *  setFrame(int,int,int,int) (an android.view gap), so nothing calls this;
     *  it is here because the API-36 surface has it and a subclass may. */
    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        // ⚠ MUST call super: this assigns the view's bounds. It previously read
        // `{ return false; }`, written when android.view.View had no setFrame so
        // the override was unreachable -- the note above still describes that
        // world. Once View.layout() routed bounds through setFrame(), this stub
        // discarded them and every TextView/Button/CheckBox laid out at
        // [0,0-0,0] while measuring correctly (Vector Pinball: 484 -> 272).
        // AOSP additionally pokes mEditor and restarts the marquee; neither
        // exists in this tree, and AOSP's own body reduces to the super call
        // when mEditor is null and nothing is marqueeing.
        return super.setFrame(l, t, r, b);
    }

    /** AOSP :11862 -- the two-argument bringPointIntoView. The one-argument form
     *  above delegates to it in AOSP; here the one-argument form is already
     *  inert (this port never scrolls a TextView's own content -- there is no
     *  Layout-driven scroll), so this returns the same "nothing changed". */
    public boolean bringPointIntoView(int offset, boolean requestRectWithoutFocus) {
        return false;
    }

    /** AOSP :12223 -- the ViewDebug hierarchy dump hook. AOSP calls
     *  super.debug(depth) first; this tree's android.view.View does not declare
     *  debug(int) (an android.view gap), so only TextView's own line is
     *  emitted. The format is AOSP's. */
    public void debug(int depth) {
        final StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");
        String output = indent + "frame={" + getLeft() + ", " + getTop() + ", "
                + getRight() + ", " + getBottom() + "} scroll={" + getScrollX() + ", "
                + getScrollY() + "} ";
        final CharSequence t = getText();
        if (t != null) {
            output += "mText=\"" + t + "\" ";
            final android.text.Layout layout = getLayout();
            if (layout != null) {
                output += "mLayout width=" + layout.getWidth() + " height=" + layout.getHeight();
            }
        } else {
            output += "mText=NULL";
        }
        android.util.Log.d("View", output);
    }
}
