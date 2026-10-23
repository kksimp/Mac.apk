// android.content.res.Configuration -- a flat record of locale,
// orientation, screen layout, etc. We populate the fields apps actually
// read; the rest stay at sensible defaults.
package android.content.res;

import java.util.Locale;

public final class Configuration implements Comparable<Configuration>, android.os.Parcelable {
    // AOSP ActivityInfo.CONFIG_* change bits, returned by diff(). AppCompatDelegate
    // and ComponentActivity diff old vs new Configuration on a config change and
    // act on the set bits (day-night reapply, recomposition); a constant-0 diff
    // read as "nothing changed" so those paths were skipped. v1.0.425.
    public static final int CONFIG_MCC                  = 0x0001;
    public static final int CONFIG_MNC                  = 0x0002;
    public static final int CONFIG_LOCALE               = 0x0004;
    public static final int CONFIG_TOUCHSCREEN          = 0x0008;
    public static final int CONFIG_KEYBOARD             = 0x0010;
    public static final int CONFIG_KEYBOARD_HIDDEN      = 0x0020;
    public static final int CONFIG_NAVIGATION           = 0x0040;
    public static final int CONFIG_ORIENTATION          = 0x0080;
    public static final int CONFIG_SCREEN_LAYOUT        = 0x0100;
    public static final int CONFIG_UI_MODE              = 0x0200;
    public static final int CONFIG_SCREEN_SIZE          = 0x0400;
    public static final int CONFIG_SMALLEST_SCREEN_SIZE = 0x0800;
    public static final int CONFIG_DENSITY              = 0x1000;
    public static final int CONFIG_LAYOUT_DIRECTION     = 0x2000;
    public static final int CONFIG_COLOR_MODE           = 0x4000;
    public static final int CONFIG_FONT_SCALE           = 0x40000000;

    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_PORTRAIT  = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;

    // UI mode flags (AOSP). androidx.appcompat reads
    // configuration.uiMode during attachBaseContext to apply
    // night-mode logic. Without `uiMode` as a real field,
    // NoSuchFieldError fires at activity construction.
    public static final int UI_MODE_TYPE_MASK         = 0x0f;
    public static final int UI_MODE_TYPE_UNDEFINED    = 0x00;
    public static final int UI_MODE_TYPE_NORMAL       = 0x01;
    public static final int UI_MODE_TYPE_DESK         = 0x02;
    public static final int UI_MODE_TYPE_CAR          = 0x03;
    public static final int UI_MODE_TYPE_TELEVISION   = 0x04;
    public static final int UI_MODE_TYPE_APPLIANCE    = 0x05;
    public static final int UI_MODE_TYPE_WATCH        = 0x06;
    public static final int UI_MODE_TYPE_VR_HEADSET   = 0x07;
    public static final int UI_MODE_NIGHT_MASK        = 0x30;
    public static final int UI_MODE_NIGHT_UNDEFINED   = 0x00;
    public static final int UI_MODE_NIGHT_NO          = 0x10;
    public static final int UI_MODE_NIGHT_YES         = 0x20;

    // Screen-layout flags.
    public static final int SCREENLAYOUT_SIZE_MASK     = 0x0f;
    public static final int SCREENLAYOUT_SIZE_UNDEFINED = 0x00;
    public static final int SCREENLAYOUT_SIZE_SMALL    = 0x01;
    public static final int SCREENLAYOUT_SIZE_NORMAL   = 0x02;
    public static final int SCREENLAYOUT_SIZE_LARGE    = 0x03;
    public static final int SCREENLAYOUT_SIZE_XLARGE   = 0x04;
    public static final int SCREENLAYOUT_LONG_MASK     = 0x30;
    /** AOSP @hide: set when the screen is bigger than an old app expects, so
     *  the platform runs it in size-compatibility mode. Needed by the port of
     *  reduceScreenLayout below, which sets it. */
    public static final int SCREENLAYOUT_COMPAT_NEEDED = 0x10000000;
    public static final int SCREENLAYOUT_LONG_NO       = 0x10;
    public static final int SCREENLAYOUT_LONG_YES      = 0x20;
    public static final int SCREENLAYOUT_LAYOUTDIR_MASK = 0xC0;
    public static final int SCREENLAYOUT_LAYOUTDIR_LTR  = 0x40;
    public static final int SCREENLAYOUT_LAYOUTDIR_RTL  = 0x80;
    public static final int SCREENLAYOUT_ROUND_MASK    = 0x300;
    public static final int SCREENLAYOUT_ROUND_NO      = 0x100;
    public static final int SCREENLAYOUT_ROUND_YES     = 0x200;

    // Touchscreen / keyboard / nav constants.
    public static final int TOUCHSCREEN_UNDEFINED      = 0;
    public static final int TOUCHSCREEN_NOTOUCH        = 1;
    /** AOSP :579 -- @Deprecated ("stylus-only touchscreens are not
     *  supported"), value 2. Present because a switch arm compiled against it
     *  still needs the name to resolve reflectively; nothing produces it. */
    @Deprecated
    public static final int TOUCHSCREEN_STYLUS         = 2;
    public static final int TOUCHSCREEN_FINGER         = 3;

    /** AOSP :719 -- @Deprecated, value 3. Same rationale as
     *  TOUCHSCREEN_STYLUS: never produced, but nameable. */
    @Deprecated
    public static final int ORIENTATION_SQUARE = 3;

    // ---- The rest of the API-36 public constant surface of Configuration,
    // from AOSP core/java/android/content/res/Configuration.java
    // (android16-release). Values transcribed from that file; the ones written
    // as expressions (COLOR_MODE_HDR_NO, SCREENLAYOUT_UNDEFINED, ...) are
    // AOSP's own expressions rather than pre-computed, so they cannot drift
    // from the masks/shifts they are built from.
    public static final int MNC_ZERO = 0xffff;
    public static final int GRAMMATICAL_GENDER_NOT_SPECIFIED = 0;
    public static final int GRAMMATICAL_GENDER_NEUTRAL = 1;
    public static final int GRAMMATICAL_GENDER_FEMININE = 2;
    public static final int GRAMMATICAL_GENDER_MASCULINE = 3;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_MASK = 0x3;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED = 0x0;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_NO = 0x1;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_YES = 0x2;
    public static final int COLOR_MODE_HDR_MASK = 0xc;
    public static final int COLOR_MODE_HDR_SHIFT = 2;
    public static final int COLOR_MODE_HDR_UNDEFINED = 0x0;
    public static final int COLOR_MODE_HDR_NO = 0x1 << COLOR_MODE_HDR_SHIFT;
    public static final int COLOR_MODE_HDR_YES = 0x2 << COLOR_MODE_HDR_SHIFT;
    public static final int COLOR_MODE_UNDEFINED = COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED | COLOR_MODE_HDR_UNDEFINED;
    public static final int SCREENLAYOUT_LONG_UNDEFINED = 0x00;
    public static final int SCREENLAYOUT_LAYOUTDIR_SHIFT = 6;
    public static final int SCREENLAYOUT_LAYOUTDIR_UNDEFINED = 0x00;
    public static final int SCREENLAYOUT_ROUND_UNDEFINED = 0x00;
    public static final int SCREENLAYOUT_UNDEFINED = SCREENLAYOUT_SIZE_UNDEFINED | SCREENLAYOUT_LONG_UNDEFINED | SCREENLAYOUT_LAYOUTDIR_UNDEFINED | SCREENLAYOUT_ROUND_UNDEFINED;
    public static final int FONT_WEIGHT_ADJUSTMENT_UNDEFINED = Integer.MAX_VALUE;
    public static final int HARDKEYBOARDHIDDEN_UNDEFINED = 0;
    public static final int HARDKEYBOARDHIDDEN_NO = 1;
    public static final int HARDKEYBOARDHIDDEN_YES = 2;
    public static final int NAVIGATIONHIDDEN_UNDEFINED = 0;
    public static final int NAVIGATIONHIDDEN_NO = 1;
    public static final int NAVIGATIONHIDDEN_YES = 2;
    public static final int SCREEN_WIDTH_DP_UNDEFINED = 0;
    public static final int SCREEN_HEIGHT_DP_UNDEFINED = 0;
    public static final int SMALLEST_SCREEN_WIDTH_DP_UNDEFINED = 0;
    public static final int KEYBOARD_UNDEFINED         = 0;
    public static final int KEYBOARD_NOKEYS            = 1;
    public static final int KEYBOARD_QWERTY            = 2;
    public static final int KEYBOARD_12KEY             = 3;
    public static final int KEYBOARDHIDDEN_UNDEFINED   = 0;
    public static final int KEYBOARDHIDDEN_NO          = 1;
    public static final int KEYBOARDHIDDEN_YES         = 2;
    public static final int NAVIGATION_UNDEFINED       = 0;
    public static final int NAVIGATION_NONAV           = 1;
    public static final int NAVIGATION_DPAD            = 2;
    public static final int NAVIGATION_TRACKBALL       = 3;
    public static final int NAVIGATION_WHEEL           = 4;

    public static final int DENSITY_DPI_UNDEFINED      = 0;
    public static final int DENSITY_DPI_ANY            = 0xfffe;
    public static final int DENSITY_DPI_NONE           = 0xffff;

    // ---- Partial configurations and the locale list (wave 7, 2026-09-26) ----
    // docs/configuration-partial-update-and-locale-list_2026-09-26.md. Four
    // bisect gates (this file's three plus Resources' MACAPK_RES_CONFIG_MERGE);
    // each `=0` restores the exact pre-change path of its own piece. Read once,
    // like every MACAPK_* gate in this tree.
    //
    // ⚠ STAGED ROLLOUT. The MERGE FAMILY -- MACAPK_RES_CONFIG_MERGE,
    //   MACAPK_CONFIG_UPDATE_FROM, MACAPK_CONFIG_UNSET_CTOR -- defaults to
    //   MACAPK_CONFIG_MERGE_DEFAULT_ON below (its STATUS line says which way),
    //   which stays false until the enable bundle lands
    //   (debug/w7rescfg_verify/enable.py --apply). The unset
    //   constructor is only safe once every framework site that used
    //   `new Configuration()` as a phone-defaults factory asks for
    //   macapkPhoneDefaults() instead (Boot's process configuration, getSystem(),
    //   the null-config Resources constructor, ResourceIndex's derived layout);
    //   those files belong to other lanes, and a tree that has the constructor
    //   ON without them boots every app with a null locale and a zero font
    //   scale. The merges are held back with it because, without the unset
    //   constructor, a fresh delta is fully populated and a merge of it is the
    //   old wipe plus a metrics re-derivation that nothing has measured. The
    //   bundle wires the sites AND flips the constant in one step, and
    //   debug/w7rescfg_gate.sh reports STAGED (tree safe, bundle verified in an
    //   overlay) until it does. Each family gate: its own variable if set
    //   (`0` off, anything else on), else MACAPK_CONFIG_MERGE_FAMILY if set
    //   (the one-variable A/B for the whole family: `=0` is the exact
    //   pre-change path of all three, which a single member's `=0` is not),
    //   else the family default.
    //   MACAPK_CONFIG_LOCALE_LIST needs no wiring and is default-ON already.
    //
    // MACAPK_CONFIG_UPDATE_FROM -- updateFrom() is AOSP's field-by-field MERGE
    //   that skips every UNDEFINED field of the delta (android14-release
    //   Configuration.java:1598-1782). `=0`: diff() then a wholesale setTo(),
    //   which copied a partial delta's untouched fields over the target.
    //   (Resources.updateConfiguration has its own gate, MACAPK_RES_CONFIG_MERGE,
    //   and calls macapkMergeFrom directly, so the two bisect independently.)
    //
    // MACAPK_CONFIG_UNSET_CTOR -- `new Configuration()` is AOSP's unset():
    //   every field UNDEFINED, fontScale 0, no locale (android14 :1036-1038 ->
    //   :1580-1583 -> setToDefaults :1549-1574). That is what makes the classic
    //       Configuration c = new Configuration(); c.locale = X;
    //       res.updateConfiguration(c, res.getDisplayMetrics());
    //   a LOCALE-only update: the merge sees every other field undefined and
    //   leaves the app's density, uiMode, sizes and window bounds alone. Ours
    //   used to carry the phone defaults (360x640dp, 320dpi, NIGHT_NO, ...),
    //   which a merge would copy as if the app had asked for them. Measured in
    //   the corpus (debug/w7rescfg_verify/corpus_scan.py): Aegis on every
    //   activity create, Telegram's LocaleController.applyLanguage, Minecraft's
    //   MainActivity$10, HCR 1.8.1's getjar Utility.setLocale.
    //   HONOURED ONLY WHILE BOTH MERGES ARE ON: through a copying path an
    //   all-undefined delta would zero the target's font scale and density,
    //   which is strictly worse than the wipe this replaces. Framework code that
    //   wants a fully populated configuration to fill in (Boot's process
    //   configuration, getSystem(), the null-config Resources constructor, the
    //   resource index's derived layout) must ask for macapkPhoneDefaults()
    //   instead, and the Resources constructor must fill an unset one the way
    //   AOSP's ResourcesImpl constructor does (macapkApplyResourcesDefaults);
    //   the enable bundle makes both edits. (Dialog.macapkSizeClass also builds
    //   one with the constructor and needs nothing: it keeps only the size bits
    //   macapkDeriveScreenLayout computes from its own seed.)
    //   `=0`: the constructor keeps the phone defaults, and setTo / the parcel do
    //   not carry fontWeightAdjustment and the grammatical gender.
    //
    // MACAPK_CONFIG_LOCALE_LIST -- a private mLocaleList kept in step with the
    //   deprecated public `locale` field by AOSP's fixUpLocaleList (:1049-1054),
    //   so getLocales() answers what the configuration says -- including a
    //   locale an old app wrote straight into the field -- instead of the host
    //   default whatever the field held. `=0`: getLocales() returns
    //   LocaleList.getDefault(), setLocales/setLocale touch only the field, and
    //   diff / compareTo / setTo / generateDelta / the parcel compare and copy
    //   the primary locale alone. (hashCode hashes the primary locale on both
    //   sides of the gate; see hashCode.) The list is LAZY for a phone-default
    //   configuration and stays lazy through the framework's own copies and
    //   comparisons, because materialising it runs `defaults read` once
    //   (LocaleList.getDefault) and those sit on every app's boot path.
    /** The merge family's default (see STAGED ROLLOUT above). The enable
     *  bundle flips it to true in the same edit that wires the
     *  macapkPhoneDefaults() sites; never flip it on its own. */
    // STATUS: ENABLED -- flipped by the enable bundle, with the wiring.
    static final boolean MACAPK_CONFIG_MERGE_DEFAULT_ON = true;

    /** One merge-family gate: `name` if set, else MACAPK_CONFIG_MERGE_FAMILY if
     *  set, else MACAPK_CONFIG_MERGE_DEFAULT_ON; a set value is off for `0` and
     *  on for anything else. Resources reads MACAPK_RES_CONFIG_MERGE through
     *  this too, so the family moves as one. */
    static boolean macapkMergeFamilyGate(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) v = System.getenv("MACAPK_CONFIG_MERGE_FAMILY");
        if (v == null || v.isEmpty()) return MACAPK_CONFIG_MERGE_DEFAULT_ON;
        return !"0".equals(v);
    }

    static final boolean MACAPK_CONFIG_UPDATE_FROM =
        macapkMergeFamilyGate("MACAPK_CONFIG_UPDATE_FROM");
    static final boolean MACAPK_CONFIG_UNSET_CTOR =
        MACAPK_CONFIG_UPDATE_FROM
        && macapkMergeFamilyGate("MACAPK_RES_CONFIG_MERGE")
        && macapkMergeFamilyGate("MACAPK_CONFIG_UNSET_CTOR");
    static final boolean MACAPK_CONFIG_LOCALE_LIST =
        !"0".equals(System.getenv("MACAPK_CONFIG_LOCALE_LIST"));

    /** AOSP @hide ActivityInfo.CONFIG_WINDOW_CONFIGURATION (android14-release
     *  ActivityInfo.java:976), the bit updateFrom reports when the window
     *  configuration moved. Our ActivityInfo does not carry the hidden constant. */
    private static final int MACAPK_CONFIG_WINDOW_CONFIGURATION = 0x20000000;

    /** AOSP :142, @Deprecated but public and still written by old apps. The
     *  initializer is the phone default (the host locale); unset() clears it. */
    public Locale locale = Locale.getDefault();

    /** AOSP :144. Null here means "not materialised yet": a configuration built
     *  from the phone defaults derives its list lazily in fixUpLocaleList, so
     *  constructing one never runs LocaleList.getDefault()'s host query. Unused
     *  while MACAPK_CONFIG_LOCALE_LIST is off. */
    private android.os.LocaleList mLocaleList;

    /** AOSP fixUpLocaleList (android14-release :1047-1054):
     *      // This brings mLocaleList in sync with locale in case a user of the
     *      // older API who doesn't know about setLocales() has changed locale
     *      // directly.
     *      if ((locale == null && !mLocaleList.isEmpty()) ||
     *              (locale != null && !locale.equals(mLocaleList.get(0)))) {
     *          mLocaleList = locale == null ? LocaleList.getEmptyLocaleList()
     *                                       : new LocaleList(locale);
     *      }
     *  plus the lazy first step for a phone-default configuration: its list is
     *  the HOST list (LocaleList.getDefault(), the user's ranked AppleLanguages)
     *  when that list leads with its locale, so the host's secondary languages
     *  survive as getLocales() has always reported them, and AOSP's rule decides
     *  otherwise. The raw host list and not macapkDefaultLocales(): on a device
     *  the process configuration's list comes from the system's languages, and
     *  an app's own Locale.setDefault does not rewrite it. */
    private void fixUpLocaleList() {
        if (mLocaleList == null) {
            android.os.LocaleList d = android.os.LocaleList.getDefault();
            mLocaleList = (d != null && locale != null && locale.equals(d.get(0)))
                ? d : android.os.LocaleList.getEmptyLocaleList();
        }
        if ((locale == null && !mLocaleList.isEmpty())
                || (locale != null && !locale.equals(mLocaleList.get(0)))) {
            mLocaleList = locale == null
                ? android.os.LocaleList.getEmptyLocaleList()
                : new android.os.LocaleList(locale);
        }
    }

    /** Whether the locale list is empty, without materialising a lazy one: a
     *  derived list (mLocaleList null) is empty exactly when `locale` is null,
     *  by fixUpLocaleList's two steps. Materialising costs a `defaults read`
     *  subprocess the first time (LocaleList.getDefault), and a configuration
     *  is copied, compared and merged on the boot path of every app, so the
     *  checks the framework itself makes must not force it. */
    boolean macapkLocalesEmpty() {
        // With the list off, getLocales() is LocaleList.getDefault(), which is
        // never empty (its fallback is the JVM's own locale).
        if (!MACAPK_CONFIG_LOCALE_LIST) return false;
        if (mLocaleList == null) return locale == null;
        fixUpLocaleList();
        return mLocaleList.isEmpty();
    }

    /** mLocaleList.equals(o.mLocaleList) after both are reconciled, without
     *  materialising two lazy lists: both derive from their `locale` through
     *  the same default list, so equal locales give equal lists and different
     *  ones give different heads. */
    boolean macapkSameLocales(Configuration o) {
        if (mLocaleList == null && o.mLocaleList == null) {
            return java.util.Objects.equals(locale, o.locale);
        }
        fixUpLocaleList();
        o.fixUpLocaleList();
        return mLocaleList.equals(o.mLocaleList);
    }

    /** The default locale list with AOSP's head rule. AOSP LocaleList.getDefault
     *  (android14-release LocaleList.java:503-526) leads with Locale.getDefault()
     *  -- "someone has called Locale.setDefault() since we last set or adjusted
     *  the default locale list. So let's recalculate the locale list." -- as
     *  `new LocaleList(defaultLocale, sLastExplicitlySetLocaleList)`, which puts
     *  the top locale first and drops its duplicate from the rest (:197-226).
     *  Our LocaleList.getDefault() is the host's AppleLanguages list and does
     *  not follow Locale.setDefault, so an app that calls Locale.setDefault(X)
     *  (Aegis, Telegram, Minecraft all do, right before their locale update)
     *  would otherwise have a later locale-less update reset it to the host
     *  language. The right home for this rule is LocaleList.getDefault itself;
     *  it is applied here because that file is not this lane's. */
    static android.os.LocaleList macapkDefaultLocales() {
        final android.os.LocaleList d = android.os.LocaleList.getDefault();
        final Locale top = Locale.getDefault();
        if (top == null || (d != null && top.equals(d.get(0)))) {
            return d != null ? d : android.os.LocaleList.getEmptyLocaleList();
        }
        final int n = (d == null) ? 0 : d.size();
        final java.util.ArrayList<Locale> out = new java.util.ArrayList<>(n + 1);
        out.add(top);
        for (int i = 0; i < n; i++) {
            Locale l = d.get(i);
            if (l != null && !l.equals(top)) out.add(l);
        }
        return new android.os.LocaleList(out.toArray(new Locale[0]));
    }

    /** AOSP API-24+ getLocales (android14-release :2308-2311):
     *      fixUpLocaleList();
     *      return mLocaleList;
     *  So a locale written into the deprecated field is what the list reports.
     *  Ours returned LocaleList.getDefault() whatever the configuration held, so
     *  an app reading its own configuration's locales (AppCompat's per-app
     *  locales, Aegis on API 24+, AnkiDroid, F-Droid) never saw the value it
     *  set, and a configuration with no locale reported the host's. */
    public android.os.LocaleList getLocales() {
        if (!MACAPK_CONFIG_LOCALE_LIST) return android.os.LocaleList.getDefault();
        fixUpLocaleList();
        return mLocaleList;
    }
    /** AOSP :2324-2328 -- replaces the list, sets `locale` to its head (null for
     *  an empty list) and the layout direction from it. */
    public void setLocales(android.os.LocaleList locales) {
        if (!MACAPK_CONFIG_LOCALE_LIST) {
            if (locales != null && locales.size() > 0) {
                this.locale = locales.get(0);
            }
            return;
        }
        mLocaleList = locales == null ? android.os.LocaleList.getEmptyLocaleList() : locales;
        locale = mLocaleList.get(0);
        setLayoutDirection(locale);
    }
    /** AOSP :2342-2344 -- a one-locale list through setLocales. */
    public void setLocale(Locale loc) {
        if (!MACAPK_CONFIG_LOCALE_LIST) { this.locale = loc; return; }
        setLocales(loc == null ? android.os.LocaleList.getEmptyLocaleList()
                               : new android.os.LocaleList(loc));
    }
    /** AOSP @hide (public in bytecode) :2351-2354 -- clears the locale without
     *  changing the layout direction. New surface; no pre-change caller. */
    public void clearLocales() {
        mLocaleList = android.os.LocaleList.getEmptyLocaleList();
        locale = null;
    }

    /** AOSP Configuration.reduceScreenLayout (android14-release :419-479),
     *  verbatim including its "DO NOT CHANGE" magic numbers.
     *
     *  WHY THIS EXISTS (2026-08-21). `screenLayout` was a HARDCODED
     *  `SIZE_NORMAL | LONG_NO | LAYOUTDIR_LTR` with no runtime writer anywhere,
     *  so `SCREENLAYOUT_LONG` could never be YES. That is not cosmetic: anuto's
     *  own GameActivity.onCreate disassembles to
     *      if ((screenLayout & 0x30) == 0x20) getWindow().clearFlags(0x400)
     *  i.e. SCREENLAYOUT_LONG_MASK / LONG_YES -> clear FLAG_FULLSCREEN. On a
     *  real device that guard FIRES and the status bar stays; with LONG_NO
     *  hardcoded it can never fire, so we hide a bar a device shows.
     *
     *  Our own ResourceIndex already asserted the opposite in a comment ("our
     *  window is ... always long"), so two halves of the framework disagreed and
     *  the app-visible half was the wrong one. At 404x898dp AOSP computes
     *  (898*3)/5 = 538 >= 403, i.e. LONG_YES.
     *
     *  NOT hardcoded to LONG_YES: the launcher offers user-chosen screen sizes,
     *  so it is derived from the live metrics exactly as AOSP derives it. */
    static int macapkReduceScreenLayout(int curLayout, int longSizeDp, int shortSizeDp) {
        int screenLayoutSize;
        boolean screenLayoutLong;
        boolean screenLayoutCompatNeeded;
        if (longSizeDp < 470) {
            screenLayoutSize = SCREENLAYOUT_SIZE_SMALL;
            screenLayoutLong = false;
            screenLayoutCompatNeeded = false;
        } else {
            if (longSizeDp >= 960 && shortSizeDp >= 720) {
                screenLayoutSize = SCREENLAYOUT_SIZE_XLARGE;
            } else if (longSizeDp >= 640 && shortSizeDp >= 480) {
                screenLayoutSize = SCREENLAYOUT_SIZE_LARGE;
            } else {
                screenLayoutSize = SCREENLAYOUT_SIZE_NORMAL;
            }
            screenLayoutCompatNeeded = (shortSizeDp > 321 || longSizeDp > 570);
            // Anything wider than WVGA (5:3) is considered long.
            screenLayoutLong = (((longSizeDp * 3) / 5) >= (shortSizeDp - 1));
        }
        if (!screenLayoutLong) {
            curLayout = (curLayout & ~SCREENLAYOUT_LONG_MASK) | SCREENLAYOUT_LONG_NO;
        }
        if (screenLayoutCompatNeeded) curLayout |= SCREENLAYOUT_COMPAT_NEEDED;
        int curSize = curLayout & SCREENLAYOUT_SIZE_MASK;
        if (screenLayoutSize < curSize) {
            curLayout = (curLayout & ~SCREENLAYOUT_SIZE_MASK) | screenLayoutSize;
        }
        return curLayout;
    }

    /** Recompute screenLayout from this Configuration's live dp metrics,
     *  preserving the layout direction. AOSP seeds the reduction optimistically
     *  at LONG_YES | SIZE_XLARGE (Configuration.java:415) and reduces down;
     *  doing the same here is what makes the port faithful rather than
     *  approximate. Kill switch: MACAPK_SCREENLAYOUT_DERIVE=0. */
    public void macapkDeriveScreenLayout() {
        if ("0".equals(System.getenv("MACAPK_SCREENLAYOUT_DERIVE"))) return;
        int w = screenWidthDp, h = screenHeightDp;
        if (w <= 0 || h <= 0) return;
        int longDp = Math.max(w, h), shortDp = Math.min(w, h);
        int dir = screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK;
        int seed = SCREENLAYOUT_LONG_YES | SCREENLAYOUT_SIZE_XLARGE;
        screenLayout = macapkReduceScreenLayout(seed, longDp, shortDp) | dir;
    }

    /** The layout-direction bits that go with the default locale. These defaults
     *  pair `locale = Locale.getDefault()` with a hardcoded LAYOUTDIR_LTR, so a
     *  Hebrew or Arabic host gave every app an RTL locale in an LTR layout and
     *  nothing mirrored; a device sets the two together. */
    private static int macapkDefaultLayoutDir() {
        try {
            return java.awt.ComponentOrientation.getOrientation(Locale.getDefault()).isLeftToRight()
                ? SCREENLAYOUT_LAYOUTDIR_LTR : SCREENLAYOUT_LAYOUTDIR_RTL;
        } catch (Throwable t) {
            return SCREENLAYOUT_LAYOUTDIR_LTR;
        }
    }
    public int orientation = ORIENTATION_PORTRAIT;
    public int screenLayout = SCREENLAYOUT_SIZE_NORMAL | SCREENLAYOUT_LONG_NO | macapkDefaultLayoutDir();
    public int screenWidthDp = 360;
    public int screenHeightDp = 640;
    public int smallestScreenWidthDp = 360;
    public int densityDpi = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;  // was 320 (v1.0.2187)
    public float fontScale = 1.0f;
    /** AOSP API-8+ field. Bit-field of UI_MODE_TYPE_* + UI_MODE_NIGHT_*.
     *  androidx.appcompat.app.AppCompatDelegate reads this during
     *  attachBaseContext to apply day/night theming. */
    public int uiMode = UI_MODE_TYPE_NORMAL | UI_MODE_NIGHT_NO;
    public int touchscreen = TOUCHSCREEN_FINGER;
    /** AOSP DisplayContent.computeScreenConfiguration (android14 :2444-2512):
     *  KEYBOARD_QWERTY when any NON-virtual input device is
     *  KEYBOARD_TYPE_ALPHABETIC, and then HARDKEYBOARDHIDDEN_NO (adjustConfigurationLw
     *  hides it only for NOKEYS or a closed lid). Mac.apk registers exactly one
     *  such device, the host keyboard (InputManager.MACAPK_HOST_KEYBOARD), so the
     *  answer is that gate; =0 keeps the pre-2026-10-01 NOKEYS / YES. Read as a
     *  constant rather than by walking the device registry because that walk
     *  sizes the touchscreen from the Display, which itself builds a Configuration. */
    private static int macapkKeyboard() {
        return android.hardware.input.InputManager.MACAPK_HOST_KEYBOARD
                ? KEYBOARD_QWERTY : KEYBOARD_NOKEYS;
    }
    private static int macapkHardKeyboardHidden() {
        return android.hardware.input.InputManager.MACAPK_HOST_KEYBOARD
                ? HARDKEYBOARDHIDDEN_NO : KEYBOARDHIDDEN_YES;
    }
    public int keyboard = macapkKeyboard();
    public int keyboardHidden = KEYBOARDHIDDEN_NO;
    public int hardKeyboardHidden = macapkHardKeyboardHidden();
    public int navigation = NAVIGATION_NONAV;
    public int navigationHidden = KEYBOARDHIDDEN_YES;
    public int colorMode = 0;
    public int mcc = 0;  // mobile country code
    public int mnc = 0;  // mobile network code
    /** AOSP @hide field, `public final` there too. No app can name the type at compile
     *  time, but AndroidX Window reaches it via Class.getDeclaredField("windowConfiguration")
     *  to answer "how big is the current window" -- and getDeclaredField does NOT search
     *  supertypes, so it has to be declared right here. Measured: Signal 172401 threw
     *  NoSuchFieldException four times during boot from
     *  androidx.window.layout.util.BoundsHelperApi28Impl/Api29Impl.currentWindowBounds.
     *  Populated with the real window rect by Boot alongside the DisplayMetrics; see
     *  android.app.WindowConfiguration for the full reflective contract. */
    public final android.app.WindowConfiguration windowConfiguration =
            new android.app.WindowConfiguration();

    /** AOSP (android14-release :1036-1038): `public Configuration() { unset(); }`
     *  -- "Construct an invalid Configuration. This state is only suitable for
     *  constructing a Configuration delta that will be applied to some valid
     *  Configuration object." (MACAPK_CONFIG_UNSET_CTOR; with it off, the field
     *  initializers' phone defaults stand, as before.) */
    public Configuration() {
        if (MACAPK_CONFIG_UNSET_CTOR) unset();
    }
    /** AOSP :1043-1045 -- a deep copy. The field initializers run first and
     *  setTo overwrites every field it copies. */
    public Configuration(Configuration o) { setTo(o); }

    /** A fully populated configuration for FRAMEWORK code to fill in: exactly
     *  what `new Configuration()` produced before MACAPK_CONFIG_UNSET_CTOR (the
     *  phone defaults below -- 360x640dp, 320dpi, portrait, NIGHT_NO, finger
     *  touchscreen, no keys, host locale -- and fontWeightAdjustment 0). Boot's
     *  process configuration, Resources.getSystem(), the null-config Resources
     *  constructor and ResourceIndex's derived screen layout used the no-arg
     *  constructor as this factory; on AOSP the process configuration comes
     *  fully populated from the system server instead, and no framework code
     *  relies on the constructor's values. Not public API (the name is ours). */
    public static Configuration macapkPhoneDefaults() {
        final Configuration c = new Configuration();
        c.setToDefaults();
        c.fontWeightAdjustment = 0;
        c.mGrammaticalGender = GRAMMATICAL_GENDER_UNDEFINED;
        return c;
    }

    /** What the Resources constructor does to the configuration it is handed,
     *  once the enable bundle calls this from Resources(AssetManager,
     *  DisplayMetrics, Configuration). AOSP android14-release Resources.java
     *  :317-320 builds a ResourcesImpl, whose constructor (ResourcesImpl.java
     *  :177-184) is
     *      mMetrics.setToDefaults();
     *      mDisplayAdjustments = displayAdjustments;
     *      mConfiguration.setToDefaults();
     *      updateConfiguration(config, metrics, displayAdjustments.getCompatibilityInfo());
     *  so the configuration it serves is AOSP's defaults with the caller's
     *  defined fields merged over them. For an unset or partial caller that
     *  means three fills: font scale 1 unless the caller's is positive
     *  (setToDefaults :1550, and updateFrom copies fontScale only when > 0),
     *  the density from the metrics when the caller's is UNDEFINED
     *  (calcConfigChanges :514-519 and CompatibilityInfo.applyToConfiguration
     *  :595 `inoutConfig.densityDpi = displayDensity;`), and the default locales
     *  when the caller has none (calcConfigChanges :521-523). Ours keeps the
     *  caller's object BY REFERENCE -- Boot's process configuration relies on
     *  that aliasing -- so the same three fills are made in place. A populated
     *  configuration (every framework caller once the bundle is in) has none of
     *  the three gaps and is left alone, and so is everything while
     *  MACAPK_CONFIG_UNSET_CTOR is off, when no configuration is unset. The
     *  emptiness test does not materialise a lazy locale list. Not mirrored:
     *  AOSP's screen sizes stay UNDEFINED for such a caller here too, since
     *  setToDefaults leaves them undefined and nothing fills them. */
    static void macapkApplyResourcesDefaults(Configuration c, android.util.DisplayMetrics m) {
        if (!MACAPK_CONFIG_UNSET_CTOR || c == null) return;
        if (!(c.fontScale > 0)) c.fontScale = 1.0f;
        if (c.densityDpi == DENSITY_DPI_UNDEFINED && m != null && m.densityDpi > 0) {
            c.densityDpi = m.densityDpi;
        }
        final boolean noLocales = MACAPK_CONFIG_LOCALE_LIST ? c.macapkLocalesEmpty()
                                                            : c.locale == null;
        if (noLocales) c.setLocales(macapkDefaultLocales());
    }

    /** AOSP @hide (public in bytecode), android14-release :1580-1583:
     *      public void unset() { setToDefaults(); fontScale = 0; }
     *  where AOSP's setToDefaults (:1549-1574) is every field UNDEFINED except
     *  fontScale 1, an empty locale list and a null locale. Ours cannot call our
     *  own setToDefaults(), which deliberately answers the phone defaults
     *  instead (v1.0.426, "not all-zero, which would collapse Compose's
     *  WindowSizeClass" -- a caller that explicitly resets a configuration it
     *  then reads), so the AOSP values are written here directly. The
     *  grammatical gender follows the android16 model this file already uses
     *  (setToDefaults :1596 there: GRAMMATICAL_GENDER_UNDEFINED). */
    public void unset() {
        fontScale = 0;
        mcc = mnc = 0;
        mLocaleList = android.os.LocaleList.getEmptyLocaleList();
        locale = null;
        touchscreen = TOUCHSCREEN_UNDEFINED;
        keyboard = KEYBOARD_UNDEFINED;
        keyboardHidden = KEYBOARDHIDDEN_UNDEFINED;
        hardKeyboardHidden = HARDKEYBOARDHIDDEN_UNDEFINED;
        navigation = NAVIGATION_UNDEFINED;
        navigationHidden = NAVIGATIONHIDDEN_UNDEFINED;
        orientation = ORIENTATION_UNDEFINED;
        screenLayout = SCREENLAYOUT_UNDEFINED;
        colorMode = COLOR_MODE_UNDEFINED;
        uiMode = UI_MODE_TYPE_UNDEFINED;
        screenWidthDp = SCREEN_WIDTH_DP_UNDEFINED;
        screenHeightDp = SCREEN_HEIGHT_DP_UNDEFINED;
        smallestScreenWidthDp = SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
        densityDpi = DENSITY_DPI_UNDEFINED;
        windowConfiguration.setToDefaults();
        fontWeightAdjustment = FONT_WEIGHT_ADJUSTMENT_UNDEFINED;
        mGrammaticalGender = GRAMMATICAL_GENDER_UNDEFINED;
    }

    /** AOSP API-11+ helper. True if the current screen-layout size bucket is at
     *  least {@code size} (one of SCREENLAYOUT_SIZE_*). AppCompat (and MC's
     *  MainActivity via it) calls this during layout inflation; a missing method
     *  threw NoSuchMethodError on the UI thread. Mirrors frameworks/base's impl:
     *  UNDEFINED is never "at least" anything; otherwise compare the masked
     *  bucket. */
    public boolean isLayoutSizeAtLeast(int size) {
        int cur = screenLayout & SCREENLAYOUT_SIZE_MASK;
        if (cur == SCREENLAYOUT_SIZE_UNDEFINED) return false;
        return cur >= size;
    }
    /** AOSP setToDefaults resets every field. We reset to the same sensible
     *  non-zero values the field initializers carry (Boot overwrites the real
     *  window-derived ones after construction), so a caller that explicitly
     *  resets a Configuration gets a usable phone-default config, not all-zero
     *  (which would collapse Compose's WindowSizeClass). v1.0.426. */
    public void setToDefaults() {
        fontScale = 1.0f; mcc = 0; mnc = 0; locale = Locale.getDefault();
        // The list re-materialises from the new locale on first use (see
        // fixUpLocaleList); inert while MACAPK_CONFIG_LOCALE_LIST is off.
        mLocaleList = null;
        orientation = ORIENTATION_PORTRAIT;
        screenLayout = SCREENLAYOUT_SIZE_NORMAL | SCREENLAYOUT_LONG_NO | macapkDefaultLayoutDir();
        screenWidthDp = 360; screenHeightDp = 640; smallestScreenWidthDp = 360;
        densityDpi = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;
        uiMode = UI_MODE_TYPE_NORMAL | UI_MODE_NIGHT_NO;
        touchscreen = TOUCHSCREEN_FINGER; keyboard = macapkKeyboard();
        keyboardHidden = KEYBOARDHIDDEN_NO; hardKeyboardHidden = macapkHardKeyboardHidden();
        navigation = NAVIGATION_NONAV; navigationHidden = KEYBOARDHIDDEN_YES; colorMode = 0;
        windowConfiguration.setToDefaults();
    }
    public void setTo(Configuration o) {
        if (o == null) return;
        locale = o.locale;
        if (MACAPK_CONFIG_LOCALE_LIST) {
            // AOSP :1072-1073: `o.fixUpLocaleList(); mLocaleList = o.mLocaleList;`
            // A lazy source list stays lazy in the copy: `locale` was copied
            // above, so the copy derives exactly the list the source would.
            if (o.mLocaleList == null) {
                mLocaleList = null;
            } else {
                o.fixUpLocaleList();
                mLocaleList = o.mLocaleList;
            }
        }
        if (MACAPK_CONFIG_UNSET_CTOR) {
            // AOSP :1074 and :1096 copy both. Before the unset constructor
            // every configuration held the field initializers' 0 / UNDEFINED,
            // so leaving them out was invisible. An unset delta now holds
            // FONT_WEIGHT_ADJUSTMENT_UNDEFINED, and a copy of it (Resources'
            // calcConfigChanges works on one) that fell back to the
            // initializer's 0 would stop being a delta for this field and
            // reset an adjusted weight to 0.
            mGrammaticalGender = o.mGrammaticalGender;
            fontWeightAdjustment = o.fontWeightAdjustment;
        }
        orientation = o.orientation;
        screenLayout = o.screenLayout;
        screenWidthDp = o.screenWidthDp;
        screenHeightDp = o.screenHeightDp;
        smallestScreenWidthDp = o.smallestScreenWidthDp;
        densityDpi = o.densityDpi;
        fontScale = o.fontScale;
        uiMode = o.uiMode;
        touchscreen = o.touchscreen;
        keyboard = o.keyboard;
        keyboardHidden = o.keyboardHidden;
        hardKeyboardHidden = o.hardKeyboardHidden;
        navigation = o.navigation;
        navigationHidden = o.navigationHidden;
        colorMode = o.colorMode;
        mcc = o.mcc;
        mnc = o.mnc;
        // AOSP's setTo does the same in-place copy. The field is final, so a Configuration
        // copy must mutate the existing instance rather than reassign it -- otherwise every
        // copy would silently carry the DEFAULT empty bounds and AndroidX would fall back
        // to the display size, which is exactly the bug this field exists to fix.
        windowConfiguration.setTo(o.windowConfiguration);
    }

    /** AOSP Configuration.diff: the set of CONFIG_* bits whose field differs
     *  between this and `delta` (delta's non-default field wins, AOSP semantics).
     *  v1.0.425. */
    public int diff(Configuration delta) {
        if (delta == null) return 0;
        int changed = 0;
        if (delta.fontScale != 0 && fontScale != delta.fontScale) changed |= CONFIG_FONT_SCALE;
        if (delta.mcc != 0 && mcc != delta.mcc) changed |= CONFIG_MCC;
        if (delta.mnc != 0 && mnc != delta.mnc) changed |= CONFIG_MNC;
        if (MACAPK_CONFIG_LOCALE_LIST) {
            // AOSP diff (android14-release :1906-1912): the LISTS, after both
            // are reconciled with their `locale` field.
            if (!delta.macapkLocalesEmpty() && !macapkSameLocales(delta)) {
                changed |= CONFIG_LOCALE | CONFIG_LAYOUT_DIRECTION;
            }
        } else if (delta.locale != null && (locale == null || !locale.equals(delta.locale))) {
            changed |= CONFIG_LOCALE | CONFIG_LAYOUT_DIRECTION;
        }
        if (delta.touchscreen != TOUCHSCREEN_UNDEFINED && touchscreen != delta.touchscreen) changed |= CONFIG_TOUCHSCREEN;
        if (delta.keyboard != KEYBOARD_UNDEFINED && keyboard != delta.keyboard) changed |= CONFIG_KEYBOARD;
        if (delta.keyboardHidden != KEYBOARDHIDDEN_UNDEFINED && keyboardHidden != delta.keyboardHidden) changed |= CONFIG_KEYBOARD_HIDDEN;
        if (delta.navigation != NAVIGATION_UNDEFINED && navigation != delta.navigation) changed |= CONFIG_NAVIGATION;
        if (delta.orientation != ORIENTATION_UNDEFINED && orientation != delta.orientation) changed |= CONFIG_ORIENTATION;
        if (delta.screenLayout != 0 && screenLayout != delta.screenLayout) changed |= CONFIG_SCREEN_LAYOUT;
        if (delta.colorMode != 0 && colorMode != delta.colorMode) changed |= CONFIG_COLOR_MODE;
        if (delta.uiMode != 0 && uiMode != delta.uiMode) changed |= CONFIG_UI_MODE;
        if (delta.screenWidthDp != 0 && screenWidthDp != delta.screenWidthDp) changed |= CONFIG_SCREEN_SIZE;
        if (delta.screenHeightDp != 0 && screenHeightDp != delta.screenHeightDp) changed |= CONFIG_SCREEN_SIZE;
        if (delta.smallestScreenWidthDp != 0 && smallestScreenWidthDp != delta.smallestScreenWidthDp) changed |= CONFIG_SMALLEST_SCREEN_SIZE;
        if (delta.densityDpi != DENSITY_DPI_UNDEFINED && densityDpi != delta.densityDpi) changed |= CONFIG_DENSITY;
        return changed;
    }

    /** AOSP compareTo: a deterministic field-by-field ordering (also the basis
     *  for equals/hashCode). Returns 0 iff every compared field is equal. */
    @Override public int compareTo(Configuration that) {
        if (that == null) return 1;
        float n = fontScale - that.fontScale;
        if (n != 0) return n < 0 ? -1 : 1;
        int d = mcc - that.mcc;                                   if (d != 0) return d;
        d = mnc - that.mnc;                                       if (d != 0) return d;
        if (MACAPK_CONFIG_LOCALE_LIST) {
            d = macapkCompareLocaleLists(that);                   if (d != 0) return d;
        } else {
            String la = locale != null ? locale.toString() : "";
            String lb = that.locale != null ? that.locale.toString() : "";
            d = la.compareTo(lb);                                 if (d != 0) return d;
        }
        d = touchscreen - that.touchscreen;                       if (d != 0) return d;
        d = keyboard - that.keyboard;                             if (d != 0) return d;
        d = keyboardHidden - that.keyboardHidden;                 if (d != 0) return d;
        d = hardKeyboardHidden - that.hardKeyboardHidden;         if (d != 0) return d;
        d = navigation - that.navigation;                         if (d != 0) return d;
        d = navigationHidden - that.navigationHidden;             if (d != 0) return d;
        d = orientation - that.orientation;                       if (d != 0) return d;
        d = screenLayout - that.screenLayout;                     if (d != 0) return d;
        d = colorMode - that.colorMode;                           if (d != 0) return d;
        d = uiMode - that.uiMode;                                 if (d != 0) return d;
        d = screenWidthDp - that.screenWidthDp;                   if (d != 0) return d;
        d = screenHeightDp - that.screenHeightDp;                 if (d != 0) return d;
        d = smallestScreenWidthDp - that.smallestScreenWidthDp;   if (d != 0) return d;
        d = densityDpi - that.densityDpi;                         if (d != 0) return d;
        return 0;
    }
    /** AOSP compareTo's locale step (android14-release :2172-2194): both lists
     *  reconciled first; "for backward compatibility, we consider an empty
     *  locale list to be greater than any non-empty locale list"; otherwise
     *  language, country, variant and language tag per position, then length. */
    private int macapkCompareLocaleLists(Configuration that) {
        if (macapkSameLocales(that)) return 0;
        fixUpLocaleList();
        that.fixUpLocaleList();
        if (this.mLocaleList.isEmpty()) {
            return that.mLocaleList.isEmpty() ? 0 : 1;
        } else if (that.mLocaleList.isEmpty()) {
            return -1;
        }
        final int minSize = Math.min(this.mLocaleList.size(), that.mLocaleList.size());
        for (int i = 0; i < minSize; ++i) {
            final Locale a = this.mLocaleList.get(i);
            final Locale b = that.mLocaleList.get(i);
            if (a == b) continue;
            if (a == null) return -1;  // our LocaleList admits a null element;
            if (b == null) return 1;   // AOSP's constructor rejects one
            int n = a.getLanguage().compareTo(b.getLanguage());
            if (n != 0) return n;
            n = a.getCountry().compareTo(b.getCountry());
            if (n != 0) return n;
            n = a.getVariant().compareTo(b.getVariant());
            if (n != 0) return n;
            n = a.toLanguageTag().compareTo(b.toLanguageTag());
            if (n != 0) return n;
        }
        return this.mLocaleList.size() - that.mLocaleList.size();
    }
    public boolean equals(Configuration that) { return that != null && compareTo(that) == 0; }
    @Override public boolean equals(Object that) {
        return (that instanceof Configuration) && equals((Configuration) that);
    }
    @Override public int hashCode() {
        int result = 17;
        result = 31 * result + Float.floatToIntBits(fontScale);
        result = 31 * result + mcc + mnc + orientation + screenLayout + uiMode
                 + screenWidthDp + screenHeightDp + smallestScreenWidthDp + densityDpi;
        // AOSP hashes `mLocaleList.hashCode()` (:2254). The primary locale is
        // kept here under MACAPK_CONFIG_LOCALE_LIST too: equal reconciled
        // lists have equal heads and the head IS `locale` after the fix-up, so
        // this stays consistent with equals(), and it never materialises a
        // lazy list (see macapkLocalesEmpty).
        result = 31 * result + (locale != null ? locale.hashCode() : 0);
        return result;
    }
    /** AOSP updateFrom (android14-release :1598-1782): "Copies the fields from
     *  delta into this Configuration object, keeping track of which ones have
     *  changed. Any undefined fields in {@code delta} are ignored and not copied
     *  in to the current Configuration." MACAPK_CONFIG_UPDATE_FROM=0 restores the
     *  pre-change diff-then-copy. */
    public int updateFrom(Configuration delta) {
        if (MACAPK_CONFIG_UPDATE_FROM) return macapkMergeFrom(delta);
        int changed = diff(delta);
        setTo(delta);
        return changed;
    }

    /** The AOSP merge itself, field for field in AOSP's order, for updateFrom
     *  and for Resources.updateConfiguration (ResourcesImpl.calcConfigChanges ->
     *  `mConfiguration.updateFrom(mTmpConfig)`), which gates it separately.
     *  Left out: userSetLocale, compat*Dp, assetsSeq and seq (hidden fields this
     *  class does not have), and the window configuration's rotation /
     *  always-on-top / display fields (likewise absent from ours). */
    int macapkMergeFrom(Configuration delta) {
        int changed = 0;
        if (delta.fontScale > 0 && fontScale != delta.fontScale) {
            changed |= CONFIG_FONT_SCALE;
            fontScale = delta.fontScale;
        }
        if (delta.mcc != 0 && mcc != delta.mcc) {
            changed |= CONFIG_MCC;
            mcc = delta.mcc;
        }
        if (delta.mnc != 0 && mnc != delta.mnc) {
            changed |= CONFIG_MNC;
            mnc = delta.mnc;
        }
        if (MACAPK_CONFIG_LOCALE_LIST) {
            if (!delta.macapkLocalesEmpty() && !macapkSameLocales(delta)) {
                changed |= CONFIG_LOCALE;
                delta.fixUpLocaleList();
                mLocaleList = delta.mLocaleList;
                // "delta.locale can't be null, since delta.mLocaleList is not
                // empty" -- true after the fix-up for AOSP's LocaleList; ours
                // admits a null element, hence the null-safe compare.
                if (!java.util.Objects.equals(delta.locale, locale)) {
                    locale = delta.locale != null ? (Locale) delta.locale.clone() : null;
                    // "If locale has changed, then layout direction is also changed ..."
                    changed |= CONFIG_LAYOUT_DIRECTION;
                    // "... and we need to update the layout direction"
                    setLayoutDirection(locale);
                }
            }
        } else if (delta.locale != null && !delta.locale.equals(locale)) {
            // The list-free form of the same step, for MACAPK_CONFIG_LOCALE_LIST=0:
            // only the primary locale exists to compare.
            changed |= CONFIG_LOCALE | CONFIG_LAYOUT_DIRECTION;
            locale = (Locale) delta.locale.clone();
            setLayoutDirection(locale);
        }
        final int deltaScreenLayoutDir = delta.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK;
        if (deltaScreenLayoutDir != SCREENLAYOUT_LAYOUTDIR_UNDEFINED
                && deltaScreenLayoutDir != (screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK)) {
            screenLayout = (screenLayout & ~SCREENLAYOUT_LAYOUTDIR_MASK) | deltaScreenLayoutDir;
            changed |= CONFIG_LAYOUT_DIRECTION;
        }
        if (delta.touchscreen != TOUCHSCREEN_UNDEFINED && touchscreen != delta.touchscreen) {
            changed |= CONFIG_TOUCHSCREEN;
            touchscreen = delta.touchscreen;
        }
        if (delta.keyboard != KEYBOARD_UNDEFINED && keyboard != delta.keyboard) {
            changed |= CONFIG_KEYBOARD;
            keyboard = delta.keyboard;
        }
        if (delta.keyboardHidden != KEYBOARDHIDDEN_UNDEFINED
                && keyboardHidden != delta.keyboardHidden) {
            changed |= CONFIG_KEYBOARD_HIDDEN;
            keyboardHidden = delta.keyboardHidden;
        }
        if (delta.hardKeyboardHidden != HARDKEYBOARDHIDDEN_UNDEFINED
                && hardKeyboardHidden != delta.hardKeyboardHidden) {
            changed |= CONFIG_KEYBOARD_HIDDEN;
            hardKeyboardHidden = delta.hardKeyboardHidden;
        }
        if (delta.navigation != NAVIGATION_UNDEFINED && navigation != delta.navigation) {
            changed |= CONFIG_NAVIGATION;
            navigation = delta.navigation;
        }
        if (delta.navigationHidden != NAVIGATIONHIDDEN_UNDEFINED
                && navigationHidden != delta.navigationHidden) {
            changed |= CONFIG_KEYBOARD_HIDDEN;
            navigationHidden = delta.navigationHidden;
        }
        if (delta.orientation != ORIENTATION_UNDEFINED && orientation != delta.orientation) {
            changed |= CONFIG_ORIENTATION;
            orientation = delta.orientation;
        }
        if (((delta.screenLayout & SCREENLAYOUT_SIZE_MASK) != SCREENLAYOUT_SIZE_UNDEFINED)
                && (delta.screenLayout & SCREENLAYOUT_SIZE_MASK)
                != (screenLayout & SCREENLAYOUT_SIZE_MASK)) {
            changed |= CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_SIZE_MASK)
                    | (delta.screenLayout & SCREENLAYOUT_SIZE_MASK);
        }
        if (((delta.screenLayout & SCREENLAYOUT_LONG_MASK) != SCREENLAYOUT_LONG_UNDEFINED)
                && (delta.screenLayout & SCREENLAYOUT_LONG_MASK)
                != (screenLayout & SCREENLAYOUT_LONG_MASK)) {
            changed |= CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_LONG_MASK)
                    | (delta.screenLayout & SCREENLAYOUT_LONG_MASK);
        }
        if (((delta.screenLayout & SCREENLAYOUT_ROUND_MASK) != SCREENLAYOUT_ROUND_UNDEFINED)
                && (delta.screenLayout & SCREENLAYOUT_ROUND_MASK)
                != (screenLayout & SCREENLAYOUT_ROUND_MASK)) {
            changed |= CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_ROUND_MASK)
                    | (delta.screenLayout & SCREENLAYOUT_ROUND_MASK);
        }
        if ((delta.screenLayout & SCREENLAYOUT_COMPAT_NEEDED)
                != (screenLayout & SCREENLAYOUT_COMPAT_NEEDED)
                && delta.screenLayout != 0) {
            changed |= CONFIG_SCREEN_LAYOUT;
            screenLayout = (screenLayout & ~SCREENLAYOUT_COMPAT_NEEDED)
                    | (delta.screenLayout & SCREENLAYOUT_COMPAT_NEEDED);
        }
        if (((delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)
                    != COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED)
                && (delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)
                != (colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)) {
            changed |= CONFIG_COLOR_MODE;
            colorMode = (colorMode & ~COLOR_MODE_WIDE_COLOR_GAMUT_MASK)
                    | (delta.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK);
        }
        if (((delta.colorMode & COLOR_MODE_HDR_MASK) != COLOR_MODE_HDR_UNDEFINED)
                && (delta.colorMode & COLOR_MODE_HDR_MASK)
                != (colorMode & COLOR_MODE_HDR_MASK)) {
            changed |= CONFIG_COLOR_MODE;
            colorMode = (colorMode & ~COLOR_MODE_HDR_MASK)
                    | (delta.colorMode & COLOR_MODE_HDR_MASK);
        }
        if (delta.uiMode != (UI_MODE_TYPE_UNDEFINED | UI_MODE_NIGHT_UNDEFINED)
                && uiMode != delta.uiMode) {
            changed |= CONFIG_UI_MODE;
            if ((delta.uiMode & UI_MODE_TYPE_MASK) != UI_MODE_TYPE_UNDEFINED) {
                uiMode = (uiMode & ~UI_MODE_TYPE_MASK) | (delta.uiMode & UI_MODE_TYPE_MASK);
            }
            if ((delta.uiMode & UI_MODE_NIGHT_MASK) != UI_MODE_NIGHT_UNDEFINED) {
                uiMode = (uiMode & ~UI_MODE_NIGHT_MASK) | (delta.uiMode & UI_MODE_NIGHT_MASK);
            }
        }
        if (delta.screenWidthDp != SCREEN_WIDTH_DP_UNDEFINED
                && screenWidthDp != delta.screenWidthDp) {
            changed |= CONFIG_SCREEN_SIZE;
            screenWidthDp = delta.screenWidthDp;
        }
        if (delta.screenHeightDp != SCREEN_HEIGHT_DP_UNDEFINED
                && screenHeightDp != delta.screenHeightDp) {
            changed |= CONFIG_SCREEN_SIZE;
            screenHeightDp = delta.screenHeightDp;
        }
        if (delta.smallestScreenWidthDp != SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
                && smallestScreenWidthDp != delta.smallestScreenWidthDp) {
            changed |= CONFIG_SMALLEST_SCREEN_SIZE;
            smallestScreenWidthDp = delta.smallestScreenWidthDp;
        }
        if (delta.densityDpi != DENSITY_DPI_UNDEFINED && densityDpi != delta.densityDpi) {
            changed |= CONFIG_DENSITY;
            densityDpi = delta.densityDpi;
        }
        if (macapkMergeWindowConfiguration(windowConfiguration, delta.windowConfiguration)) {
            changed |= MACAPK_CONFIG_WINDOW_CONFIGURATION;
        }
        if (delta.fontWeightAdjustment != FONT_WEIGHT_ADJUSTMENT_UNDEFINED
                && delta.fontWeightAdjustment != fontWeightAdjustment) {
            changed |= android.content.pm.ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT;
            fontWeightAdjustment = delta.fontWeightAdjustment;
        }
        // android16-release :1799-1803 (the model this file's constants follow):
        // an UNDEFINED gender is not copied. android14 copied it unconditionally
        // with NOT_SPECIFIED as the unset value, which is the same answer here.
        if (delta.mGrammaticalGender != GRAMMATICAL_GENDER_UNDEFINED
                && delta.mGrammaticalGender != mGrammaticalGender) {
            changed |= android.content.pm.ActivityInfo.CONFIG_GRAMMATICAL_GENDER;
            mGrammaticalGender = delta.mGrammaticalGender;
        }
        return changed;
    }

    /** AOSP WindowConfiguration.updateFrom (android14-release
     *  WindowConfiguration.java, the bounds / app bounds / max bounds /
     *  windowing mode / activity type arms): "Only allow override if bounds is
     *  not empty"; an app-bounds override needs a non-null rect; the two modes
     *  skip UNDEFINED. Through the public accessors, since WindowConfiguration
     *  is not this lane's file. Returns whether anything moved. */
    private static boolean macapkMergeWindowConfiguration(android.app.WindowConfiguration to,
                                                          android.app.WindowConfiguration delta) {
        boolean changed = false;
        final android.graphics.Rect b = delta.getBounds();
        if (!b.isEmpty() && !b.equals(to.getBounds())) {
            to.setBounds(b);
            changed = true;
        }
        final android.graphics.Rect ab = delta.getAppBounds();
        if (ab != null && !ab.equals(to.getAppBounds())) {
            to.setAppBounds(ab);
            changed = true;
        }
        final android.graphics.Rect mb = delta.getMaxBounds();
        if (!mb.isEmpty() && !mb.equals(to.getMaxBounds())) {
            to.setMaxBounds(mb);
            changed = true;
        }
        if (delta.getWindowingMode() != android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
                && to.getWindowingMode() != delta.getWindowingMode()) {
            to.setWindowingMode(delta.getWindowingMode());
            changed = true;
        }
        if (delta.getActivityType() != android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
                && to.getActivityType() != delta.getActivityType()) {
            to.setActivityType(delta.getActivityType());
            changed = true;
        }
        return changed;
    }

    // Android Wear API. axmol's AxmolEngine.isScreenRound() calls
    // this to detect circular displays. We're always on a rectangular
    // macOS window, so return false. Without this method, axmol's
    // per-frame `nativeRender` throws NoSuchMethodError each tick,
    // breaking the render pipeline before LoadingLayer can complete.
    public boolean isScreenRound() { return false; }

    // v1.0.98: layoutDirection (LTR/RTL) accessors. Compose +
    // Material RTL-aware widgets call getLayoutDirection during init. AOSP
    // derives it from the SCREENLAYOUT_LAYOUTDIR bits (RTL=1, LTR=0 per
    // View.LAYOUT_DIRECTION_*), and setLayoutDirection(Locale) sets those bits
    // from the locale's writing direction. Was hardcoded LTR + a no-op setter,
    // so an Arabic/Hebrew/Farsi Configuration reported LTR.
    public int getLayoutDirection() {
        final int dir = screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK;
        // On a device the layout-direction bits are never left UNDEFINED: the
        // system configuration sets them together with the locale. Ours can
        // arrive with a locale and no bits ("he-IL" with screenLayout 0x02),
        // which mirrored nothing for a Hebrew/Arabic user; read the locale then.
        if (dir == SCREENLAYOUT_LAYOUTDIR_UNDEFINED) {
            try {
                Locale loc = locale;
                if (loc != null && !java.awt.ComponentOrientation.getOrientation(loc).isLeftToRight()) return 1;
            } catch (Throwable ignored) {}
            return 0;
        }
        return dir == SCREENLAYOUT_LAYOUTDIR_RTL ? 1 : 0;
    }
    public void setLayoutDirection(java.util.Locale loc) {
        boolean rtl = false;
        try { if (loc != null) rtl = !java.awt.ComponentOrientation.getOrientation(loc).isLeftToRight(); }
        catch (Throwable ignored) {}
        screenLayout = (screenLayout & ~SCREENLAYOUT_LAYOUTDIR_MASK)
                     | (rtl ? SCREENLAYOUT_LAYOUTDIR_RTL : SCREENLAYOUT_LAYOUTDIR_LTR);
    }

    // v1.0.426 Tier-2: implements Parcelable. AppCompatDelegate/ComponentActivity
    // parcel a Configuration through SavedState; our in-process Parcel writes the
    // fields directly so a write -> setDataPosition(0) -> CREATOR round-trip
    // reconstructs an equal Configuration.
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeFloat(fontScale); dest.writeInt(mcc); dest.writeInt(mnc);
        dest.writeString(locale != null ? locale.toString() : "");
        dest.writeInt(orientation); dest.writeInt(screenLayout); dest.writeInt(colorMode);
        dest.writeInt(uiMode); dest.writeInt(touchscreen); dest.writeInt(keyboard);
        dest.writeInt(keyboardHidden); dest.writeInt(hardKeyboardHidden);
        dest.writeInt(navigation); dest.writeInt(navigationHidden);
        dest.writeInt(screenWidthDp); dest.writeInt(screenHeightDp);
        dest.writeInt(smallestScreenWidthDp); dest.writeInt(densityDpi);
        // Appended LAST, and read back last, so the layout stays positional-compatible
        // with everything written before this field existed. Without parcelling it, a
        // Configuration that round-trips through a Parcel (saved instance state) would
        // come back with empty bounds and quietly re-introduce the fallback.
        writeRect(dest, windowConfiguration.getBounds());
        writeRect(dest, windowConfiguration.getAppBounds());
        // Appended after the bounds on the same rule, each only under its gate
        // (a gate is process-wide, so writer and reader always agree). AOSP
        // parcels both ints (android14-release :2097-2098) and the whole
        // locale list (`fixUpLocaleList(); dest.writeTypedObject(mLocaleList,
        // flags)`); the primary-locale string above stays for the gate-off
        // reader, and the list carries the secondaries it cannot.
        if (MACAPK_CONFIG_UNSET_CTOR) {
            dest.writeInt(fontWeightAdjustment);
            dest.writeInt(mGrammaticalGender);
        }
        if (MACAPK_CONFIG_LOCALE_LIST) {
            fixUpLocaleList();
            dest.writeString(mLocaleList.toLanguageTags());
        }
    }
    /** getAppBounds() is legitimately nullable in AOSP, so the null is encoded rather
     *  than assumed away -- reading back a Rect that was never written would desync
     *  every subsequent field in the parcel. */
    private static void writeRect(android.os.Parcel dest, android.graphics.Rect r) {
        if (r == null) { dest.writeInt(0); return; }
        dest.writeInt(1);
        dest.writeInt(r.left); dest.writeInt(r.top); dest.writeInt(r.right); dest.writeInt(r.bottom);
    }
    private static android.graphics.Rect readRect(android.os.Parcel source) {
        if (source.readInt() == 0) return null;
        // Read into locals first. Java does evaluate arguments left to right, but the
        // read order here is the parcel's wire order and should not depend on the reader
        // knowing that rule.
        int l = source.readInt(), t = source.readInt();
        int r = source.readInt(), b = source.readInt();
        return new android.graphics.Rect(l, t, r, b);
    }
    public void readFromParcel(android.os.Parcel source) {
        fontScale = source.readFloat(); mcc = source.readInt(); mnc = source.readInt();
        String loc = source.readString();
        locale = (loc != null && !loc.isEmpty()) ? Locale.forLanguageTag(loc.replace('_', '-')) : null;
        orientation = source.readInt(); screenLayout = source.readInt(); colorMode = source.readInt();
        uiMode = source.readInt(); touchscreen = source.readInt(); keyboard = source.readInt();
        keyboardHidden = source.readInt(); hardKeyboardHidden = source.readInt();
        navigation = source.readInt(); navigationHidden = source.readInt();
        screenWidthDp = source.readInt(); screenHeightDp = source.readInt();
        smallestScreenWidthDp = source.readInt(); densityDpi = source.readInt();
        windowConfiguration.setBounds(readRect(source));
        windowConfiguration.setAppBounds(readRect(source));
        if (MACAPK_CONFIG_UNSET_CTOR) {
            fontWeightAdjustment = source.readInt();
            mGrammaticalGender = source.readInt();
        }
        if (MACAPK_CONFIG_LOCALE_LIST) {
            // AOSP readFromParcel (:2106-2107): the list, then `locale = mLocaleList.get(0)`.
            mLocaleList = android.os.LocaleList.forLanguageTags(source.readString());
            locale = mLocaleList.get(0);
        }
    }
    public static final android.os.Parcelable.Creator<Configuration> CREATOR =
        new android.os.Parcelable.Creator<Configuration>() {
            @Override public Configuration createFromParcel(android.os.Parcel source) {
                Configuration c = new Configuration();
                c.readFromParcel(source);
                return c;
            }
            @Override public Configuration[] newArray(int size) { return new Configuration[size]; }
        };

    /** AOSP Configuration.needNewResources: whether a config delta requires new
     *  Resources. Mac.apk reloads on any handled change, so report false for the
     *  fast path unless something unhandled changed. */
    public static boolean needNewResources(int configChanges, int interestingChanges) {
        return (configChanges & (interestingChanges | 0x20000000)) != 0;
    }

    /** AOSP core/java/android/content/res/Configuration.java:2461 --
     *      return (colorMode & COLOR_MODE_HDR_MASK) == COLOR_MODE_HDR_YES;
     *  Derived from colorMode rather than hardcoded false, so it follows
     *  whatever the field is set to (it is 0 == COLOR_MODE_HDR_UNDEFINED by
     *  default here, which is not YES -- same answer, honest derivation). */
    public boolean isScreenHdr() {
        return (colorMode & COLOR_MODE_HDR_MASK) == COLOR_MODE_HDR_YES;
    }

    /** AOSP :2452 -- the wide-colour-gamut half of the same field. */
    public boolean isScreenWideColorGamut() {
        return (colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)
                == COLOR_MODE_WIDE_COLOR_GAMUT_YES;
    }

    /** AOSP :2182 --
     *      return (uiMode & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES;
     *  So this tracks the SAME uiMode field Boot sets from the macOS
     *  AppleInterfaceStyle; it is not an independent night-mode opinion. */
    public boolean isNightModeActive() {
        return (uiMode & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES;
    }

    /** AOSP :2311 -- the user's grammatical-gender preference, mapping the
     *  internal UNDEFINED sentinel to the public NOT_SPECIFIED. Mac.apk has no
     *  UI to set one, so it stays NOT_SPECIFIED -- which is exactly what a
     *  device reports until the user picks a gender. */
    public int getGrammaticalGender() {
        return mGrammaticalGender == GRAMMATICAL_GENDER_UNDEFINED
                ? GRAMMATICAL_GENDER_NOT_SPECIFIED : mGrammaticalGender;
    }
    /** AOSP's @hide raw accessor + setter, public in bytecode. AOSP's
     *  GRAMMATICAL_GENDER_UNDEFINED is @hide and is -1. */
    public static final int GRAMMATICAL_GENDER_UNDEFINED = -1;
    public int getGrammaticalGenderRaw() { return mGrammaticalGender; }
    public void setGrammaticalGender(int grammaticalGender) {
        mGrammaticalGender = grammaticalGender;
    }
    private int mGrammaticalGender = GRAMMATICAL_GENDER_UNDEFINED;

    /** AOSP :2824 -- "generate a delta Configuration between base and change:
     *  the returned Configuration contains only the fields that DIFFER".
     *  Ported field for field, including that the mask-based fields
     *  (screenLayout, colorMode, uiMode) are OR-ed in per sub-mask rather than
     *  copied wholesale. The two fields AOSP compares that do not exist on
     *  this class -- assetsSeq and windowConfiguration, both @hide -- are
     *  omitted; everything an app can read is covered. */
    public static Configuration generateDelta(Configuration base, Configuration change) {
        final Configuration delta = new Configuration();
        if (base.fontScale != change.fontScale) delta.fontScale = change.fontScale;
        if (base.mcc != change.mcc) delta.mcc = change.mcc;
        if (base.mnc != change.mnc) delta.mnc = change.mnc;
        if (MACAPK_CONFIG_LOCALE_LIST) {
            // AOSP generateDelta (android14-release :2798-2802): compare the
            // reconciled LISTS and hand over both halves.
            if (!base.macapkSameLocales(change)) {
                change.fixUpLocaleList();
                delta.mLocaleList = change.mLocaleList;
                delta.locale = change.locale;
            }
        } else if (!java.util.Objects.equals(base.locale, change.locale)) {
            delta.locale = change.locale;
        }
        if (base.mGrammaticalGender != change.mGrammaticalGender) {
            delta.mGrammaticalGender = change.mGrammaticalGender;
        }
        if (base.touchscreen != change.touchscreen) delta.touchscreen = change.touchscreen;
        if (base.keyboard != change.keyboard) delta.keyboard = change.keyboard;
        if (base.keyboardHidden != change.keyboardHidden) {
            delta.keyboardHidden = change.keyboardHidden;
        }
        if (base.hardKeyboardHidden != change.hardKeyboardHidden) {
            delta.hardKeyboardHidden = change.hardKeyboardHidden;
        }
        if (base.navigation != change.navigation) delta.navigation = change.navigation;
        if (base.navigationHidden != change.navigationHidden) {
            delta.navigationHidden = change.navigationHidden;
        }
        if (base.orientation != change.orientation) delta.orientation = change.orientation;
        if ((base.screenLayout & SCREENLAYOUT_SIZE_MASK)
                != (change.screenLayout & SCREENLAYOUT_SIZE_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_SIZE_MASK;
        }
        if ((base.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK)
                != (change.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK;
        }
        if ((base.screenLayout & SCREENLAYOUT_LONG_MASK)
                != (change.screenLayout & SCREENLAYOUT_LONG_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_LONG_MASK;
        }
        if ((base.screenLayout & SCREENLAYOUT_ROUND_MASK)
                != (change.screenLayout & SCREENLAYOUT_ROUND_MASK)) {
            delta.screenLayout |= change.screenLayout & SCREENLAYOUT_ROUND_MASK;
        }
        if ((base.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)
                != (change.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK)) {
            delta.colorMode |= change.colorMode & COLOR_MODE_WIDE_COLOR_GAMUT_MASK;
        }
        if ((base.colorMode & COLOR_MODE_HDR_MASK)
                != (change.colorMode & COLOR_MODE_HDR_MASK)) {
            delta.colorMode |= change.colorMode & COLOR_MODE_HDR_MASK;
        }
        if ((base.uiMode & UI_MODE_TYPE_MASK) != (change.uiMode & UI_MODE_TYPE_MASK)) {
            delta.uiMode |= change.uiMode & UI_MODE_TYPE_MASK;
        }
        if ((base.uiMode & UI_MODE_NIGHT_MASK) != (change.uiMode & UI_MODE_NIGHT_MASK)) {
            delta.uiMode |= change.uiMode & UI_MODE_NIGHT_MASK;
        }
        if (base.screenWidthDp != change.screenWidthDp) {
            delta.screenWidthDp = change.screenWidthDp;
        }
        if (base.screenHeightDp != change.screenHeightDp) {
            delta.screenHeightDp = change.screenHeightDp;
        }
        if (base.smallestScreenWidthDp != change.smallestScreenWidthDp) {
            delta.smallestScreenWidthDp = change.smallestScreenWidthDp;
        }
        if (base.densityDpi != change.densityDpi) delta.densityDpi = change.densityDpi;
        if (base.fontWeightAdjustment != change.fontWeightAdjustment) {
            delta.fontWeightAdjustment = change.fontWeightAdjustment;
        }
        return delta;
    }

    public int fontWeightAdjustment;
}



