// Index over apktool's decoded res/ tree.
//
// At unpack time we already shell out to apktool to decode
// AndroidManifest.xml; apktool's --no-res flag was DROPPED so the rest
// of the resource tree (drawable/, raw/, layout/, values/, etc.) lands
// at <appDir>/_apkt/res/. apktool also writes a `values/public.xml`
// mapping `<public type="raw" name="atlas" id="0x7f040000" />` for
// every resource ID the APK exports.
//
// This class loads that mapping once at boot and serves
// Resources.openRawResource(int) by walking the apktool directory.

package com.macapk.loader;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ResourceIndex {
    private static final String TAG = "Mac.apk.Resources";

    /** Map: resource id -> {type, name}. e.g. 0x7f040000 -> {"raw","atlas"} */
    private final Map<Integer, String[]> idToTypeName = new HashMap<>();
    /** Reverse: "<type>/<name>" -> resource id. Built when we need to
     *  service Resources.getIdentifier(name, type, pkg) -- called by
     *  the Firebase SDK (and many others) to find e.g.
     *  "string/google_app_id" without compile-time R.string.* IDs. */
    private final Map<String, Integer> nameToId = new HashMap<>();
    /** Resolved string-resource values: "<name>" -> "<value>". Loaded
     *  from `res/values/strings.xml`. Used by Resources.getString(id)
     *  via id -> type/name lookup. Stored by NAME rather than ID so
     *  the same map serves config-qualified resources too (we treat
     *  the default values/ directory as authoritative). */
    private final Map<String, String> stringValues = new HashMap<>();
    /** Resolved plural-resource values: "<name>" -> ("<quantity>" ->
     *  "<template>"). Loaded from res/values/plurals.xml. Used by
     *  Resources.getQuantityString(id, n[, args]) to pick the per-count
     *  form (one/other/...) and format it. F-Droid's AppDetails
     *  "Updated %d days ago" subtitle is a plural; without this the
     *  getQuantityString fallback returned "" and the line was blank. */
    private final Map<String, java.util.Map<String, String>> pluralValues = new HashMap<>();
    /** Resolved integer-resource values: "<name>" -> Integer. Loaded
     *  from `res/values/integers.xml`. Used by Resources.getInteger(id)
     *  and the Manifest meta-data parser to resolve `@integer/<name>`
     *  references (HCR 1.43's `<meta-data android:name=
     *  "com.google.android.gms.version" android:value=
     *  "@integer/google_play_services_version"/>` is the canonical
     *  case -- without resolution the value stays the raw string
     *  "@integer/..." and Bundle.getInt(NumberFormatException) fires). */
    private final Map<String, Integer> integerValues = new HashMap<>();
    /** Resolved string-array values: "<name>" -> String[]. Loaded from
     *  `<string-array name="..."> <item>...</item> ... </string-array>`
     *  in `res/values/arrays.xml`. Used by Resources.getStringArray(id).
     *  Load-bearing for F-Droid's `R.array.default_repos` which the
     *  database fixture callback parses to seed the CoreRepository
     *  table on first launch (6-field tuples per repo: name, mirrors
     *  whitespace-separated, description, version, certificate, ...). */
    private final Map<String, String[]> stringArrayValues = new HashMap<>();
    /** Resolved integer-array values, same shape as stringArrayValues
     *  but for `<integer-array>` declarations. */
    private final Map<String, int[]> integerArrayValues = new HashMap<>();

    /** Resolved color values: `<color name="md_theme_secondaryContainer">
     *  #95bc18</color>` -> 0xFF95BC18. Loaded from `res/values/colors.xml`
     *  (plus any other values-*.xml). Per AOSP `Resources.getColor(id)`,
     *  color names are flat (no nesting), and values are hex literals
     *  optionally with alpha. Apps that use `?attr/colorX` (theme-
     *  attribute references) resolve through the style cascade to a
     *  `@color/X` reference, then this map. */
    private final Map<String, Integer> colorValues = new HashMap<>();
    /** Color names whose value is itself a reference (`@color/other` or
     *  `@android:color/transparent`). Stored separately so the resolver
     *  can chain through them recursively. */
    private final Map<String, String> colorRefs = new HashMap<>();

    /** Parsed style definitions. Each `<style name="X" parent="@style/Y">
     *  <item name="attrName">value</item>...</style>` becomes one
     *  StyleDef whose `items` map holds the attr-name -> raw-value
     *  pairs as written in styles.xml (no resolution -- "@color/X" and
     *  "?attr/X" stay verbatim). Resolution is lazy via
     *  resolveAttrInTheme so the same map serves any theme cascade
     *  rooted at any style.
     *
     *  AOSP equivalent: aapt2 compiles styles into a flat table where
     *  every <item> retains its declared value (typed or referenced).
     *  Real Android resolves through `Resources.Theme.applyStyle` +
     *  `Theme.resolveAttribute`; we mirror that contract via
     *  resolveAttrInTheme below. */
    public static final class StyleDef {
        public final String name;
        public final String parent;  // may be null if no parent
        public final Map<String, String> items;
        public StyleDef(String n, String p, Map<String, String> i) {
            this.name = n;
            this.parent = p;
            this.items = i;
        }
    }
    private final Map<String, StyleDef> styleByName = new HashMap<>();

    /** File-resource ALIAS entries declared in res/values ("layout/
     *  display" -> "@layout/display_one_line"). aapt compiles these as
     *  TYPE_REFERENCE entries with no file of their own; AssetManager
     *  resolves them via the resolveRefs=true reference walk before
     *  any file open. openRaw mirrors that chase. Keyed
     *  "<type>/<name>", value is the raw "@type/name" reference. */
    private final Map<String, String> fileAliases = new HashMap<>();

    // ---- FILE-backed resources served from the installed apks (v1.0.1590,
    // Phase 5 steps 5-7 of docs/disk-footprint-and-space-efficiency_
    // 2026-09-19.md; the gap assessment is docs/resources-in-place-gap-
    // assessment_2026-09-20.md section 3) ----
    //
    // A device never has a decoded res/ tree: a layout, drawable, selector,
    // font or raw file is the zip entry that the id's FILE ROW names
    // (value = "res/layout/x.xml", or a shortened "res/0c.9.png" -- 38 of 51
    // corpus apks ship shortened paths, so a directory-scan resolver cannot
    // be mirrored on the zip at all), config-selected across every member of
    // the set with the same rule the value rows use. AssetManager2::
    // FindEntryInternal picks the best config across the package group and
    // ResourcesImpl.loadXmlResourceParser / loadDrawableForCookie then open
    // that entry in the member the cookie names.
    //
    // fileRowById: id -> {memberIndex (into macapkTables/macapkMembers),
    // entry path, config}. Filled by macapkIngestFileRows in ascending
    // file-config score, later members first on ties, last-put-wins -- so
    // the entry that survives is the best config, base on a tie.
    // A compiled XML entry is rendered to TEXT on the way out
    // (BinaryXmlParser.parse(bytes, names) + toText), in apktool's exact
    // spelling, so every text-XML consumer above this class is unchanged.
    // (v1.0.1590 step 9b: the decoded-tree resolver this used to fall back
    // to, and its MACAPK_RES_FILES_IN_PLACE=0 gate, are deleted -- the rows
    // are the only source, as on a device.)
    private final Map<Integer, Object[]> fileRowById = new HashMap<>();
    private List<File> macapkMembers = new ArrayList<>();        // apk per table
    private List<ArscReader> macapkTables = new ArrayList<>();   // parsed tables, base first
    private final Map<Integer, java.util.zip.ZipFile> macapkMemberZips = new HashMap<>();

    /** Application-level theme name (e.g. "Theme.App"), set by Boot
     *  after Manifest.parse() finds `<application android:theme=
     *  "@style/Theme.App">`. Used by ViewCanvasDriver's chrome
     *  painters as the cascade root when resolving `?attr/colorX` for
     *  cards / bottom-nav / FAB chrome. Real Android resolves the
     *  theme per-Activity via ActivityThread.handleLaunchActivity
     *  calling `Activity.setTheme(activityInfo.theme)`; we simplify
     *  to the app-level theme because every Mac.apk activity in
     *  steady-state shares the application theme (per-activity
     *  overrides like Theme.App.Transparent apply only to dialog-
     *  shaped activities that don't reach the chrome painter). */
    private String applicationThemeName;

    // ---- v1.0.445 config-qualified value resolution ----
    // The device Configuration captured at construction, used to pick
    // the best-matching config-qualified `res/values*` directory for
    // value resources (file aliases, dimens, fractions). Real Android
    // resolves `@layout/display` (a TYPE_REFERENCE alias) against the
    // current config; e.g. Google Calculator declares
    //   values/layouts.xml        : display -> @layout/display_one_line
    //   values-h700dp/layouts.xml : display -> @layout/display_two_line
    // so on a >=700dp-tall screen it inflates the two-line display
    // (which is the ONLY variant carrying @id/result_preview, the live
    // "type 7+3 -> grey 10" result preview). Reading values/ ONLY made
    // us always pick display_one_line, silently dropping the preview.
    // These fields drive matchingValueDirs(); SDK_INT covers the
    // `-v<N>` version qualifier.
    private int cfgScreenWidthDp        = 360;
    private int cfgScreenHeightDp       = 640;
    private int cfgSmallestScreenWidthDp= 360;
    private int cfgOrientation          = 1;     // 1=port, 2=land (Configuration.ORIENTATION_*)
    private boolean cfgNight            = false;
    private int cfgDensityDpi           = 320;
    /** Configuration.screenLayout as the resource matcher reads it: the SIZE
     *  class (SCREENLAYOUT_SIZE_MASK) and the LONG aspect (SCREENLAYOUT_LONG_MASK)
     *  bits, nothing else. Taken VERBATIM from the Configuration this index is
     *  built for, as AssetManager.setConfiguration hands screenLayout to the
     *  native ResTable_config unchanged. DisplayConfig.compute derives that
     *  field from the live dp metrics with AOSP's reduceScreenLayout
     *  (Configuration.macapkDeriveScreenLayout), so the index selects for exactly
     *  the class the app is TOLD it has -- the one thing that must not differ.
     *  Without a Configuration it is derived the same way from the 360x640dp
     *  defaults above (NORMAL | LONG_YES). Read by qualifierScore only while
     *  MACAPK_RES_SIZECLASS is on. */
    private int cfgScreenLayout         = 0;
    private final int cfgSdkInt         = android.os.Build.VERSION.SDK_INT;
    /** The SDK level qualifierScore compares `-v<N>` against, for BOTH
     *  ingest paths (see macapkUnifiedResSdk below and the 2026-08-19 note).
     *  History first. The apktool text path originally used cfgSdkInt; the
     *  arsc path raised it to MACAPK_ARSC_RES_SDK: the
     *  hardcoded CONFIG_DIRS list below deliberately serves values-v31/v34
     *  colors and styles DESPITE Build.VERSION.SDK_INT being 29 ("we present
     *  as a modern device; the emulator reference is API 34" -- that reference was the `crossy-test`
     *  AVD (API 34 arm64), since deleted under disk pressure; see the provenance note at
     *  SYS_PALETTE, settled 2026-08-30 -- see its
     *  comment), while qualifierScore would reject them at 29. A unified
     *  selection path has to pick ONE answer, and 29 would silently drop
     *  the v31/v34 variants CONFIG_DIRS serves today (Material You overlays
     *  among them), regressing against both the current default and the
     *  API-34 emulator reference. So the arsc path generalizes CONFIG_DIRS'
     *  intent to every resource type instead of keeping it a colors/styles
     *  carve-out. Consequence, stated plainly: under the gate, -v30..-v34
     *  qualified dirs ALSO become eligible for the file-selection paths
     *  (layout-v31, color-v31 selectors) that share this scorer.
     *
     *  2026-08-08: when PresentedSdk chose the level explicitly
     *  (MACAPK_SDK_INT / MACAPK_SDK_FROM_TARGET), cfgResSdk stays equal to
     *  cfgSdkInt everywhere -- ONE level, coherently -- and this constant
     *  is inert. It remains only to preserve the legacy default's
     *  behaviour, and dies with it on flip day.
     *
     *  2026-08-19: the TEXT path now uses the SAME unified level instead of
     *  the raw 29. The old split (CONFIG_DIRS force-serving values-v31/v34
     *  colors and styles while qualifierScore scored FILE selectors at 29)
     *  handed apps a resource universe no real device has: Aurora Store's
     *  values-v31 theme resolved a textColor to
     *  @color/m3_dynamic_default_color_secondary_text (0x7f06008a), whose
     *  ONLY definition is the FILE selector res/color-v31/<name>.xml, which
     *  the same index then refused to serve -- so Resources.getXml threw
     *  NotFoundException and androidx's ColorStateList inflate failed on a
     *  resource our own values path had handed out ("CSLCompat: Failed to
     *  inflate ColorStateList", repeating on every affected frame). A
     *  device at 29 serves NEITHER half; a device at 34 serves BOTH; only
     *  our mix served one half. The arsc path already unified at 34 for
     *  exactly this reason, so the text path now matches it. */
    private int cfgResSdk               = macapkUnifiedResSdk();
    /** ONE `-v<N>` acceptance level for the whole index, values AND file
     *  selectors, both ingest paths: the explicitly presented level when
     *  PresentedSdk chose it, else MACAPK_ARSC_RES_SDK (the level the
     *  legacy CONFIG_DIRS carve-out always implied). */
    private static int macapkUnifiedResSdk() {
        return com.macapk.loader.PresentedSdk.isExplicit()
                ? android.os.Build.VERSION.SDK_INT : MACAPK_ARSC_RES_SDK;
    }
    private static final int MACAPK_ARSC_RES_SDK = 34;

    // ---- locale-qualified value resolution ----
    // AOSP ranks language/script/region as qualifier precedence #2 (only
    // MCC/MNC outranks it), well above orientation, night mode, density and
    // every dp threshold, so a matching `res/values-<lang>[-r<REGION>]`
    // directory overrides `res/values/` for every value resource:
    // https://developer.android.com/guide/topics/resources/providing-resources
    // Before this, qualifierScore() dropped EVERY locale-qualified dir and
    // the string loader read `res/values` unconditionally, so a localized
    // APK always rendered in whatever language its base values/ happened to
    // be -- silently, with no exception, for every non-default-locale user.
    //
    // The device locale comes from the Configuration we were constructed
    // with (whose own field initializer is Locale.getDefault(), i.e. the
    // host's language on macOS), so rebuilding the index after an in-app
    // language switch re-resolves against the new locale.
    private String cfgLanguage = "";    // lowercase ISO-639, "" when unknown
    private String cfgRegion   = "";    // uppercase ISO-3166, "" when unknown

    /** `apkPath` is the installed base.apk (v1.0.1585; the split_*.apk
     *  siblings are the rest of the app, see InstalledSplits). Every member's
     *  resources.arsc is the index's ONLY source (values, file rows, names);
     *  a pre-1590 code dir's `_apkt/` text tree is never read.
     *  v1.0.1590 step 9b: the `File apktoolDir` parameter the constructors
     *  carried for the text tree is gone with it. */
    public ResourceIndex(android.content.res.Configuration cfg, String apkPath) {
        if (cfg != null) {
            cfgScreenWidthDp         = cfg.screenWidthDp;
            cfgScreenHeightDp        = cfg.screenHeightDp;
            cfgSmallestScreenWidthDp = cfg.smallestScreenWidthDp;
            cfgOrientation           = cfg.orientation != 0 ? cfg.orientation : 1;
            cfgNight = (cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            // Mirror for the static framework-theme table (`.DayNight` polarity).
            macapkCfgNightStatic = cfgNight;
            if (cfg.densityDpi > 0) cfgDensityDpi = cfg.densityDpi;
        }
        cfgScreenLayout = (cfg != null) ? cfg.screenLayout
                                        : macapkDerivedScreenLayout(cfgScreenWidthDp, cfgScreenHeightDp);
        java.util.Locale loc = (cfg != null && cfg.locale != null)
            ? cfg.locale : java.util.Locale.getDefault();
        if (loc != null) {
            // Locale.getLanguage() returns the same legacy ISO-639 codes
            // Android's resource table stores ("iw"/"in"/"ji"), so the two
            // spellings already agree and need no remapping.
            String lang = loc.getLanguage();
            if (lang != null) cfgLanguage = lang.toLowerCase(java.util.Locale.ROOT);
            String ctry = loc.getCountry();
            if (ctry != null) cfgRegion = ctry.toUpperCase(java.util.Locale.ROOT);
        }
        // ---- the data source: resources.arsc, of every member of the installed set ----
        // Ingested directly (ArscReader, byte-verified vs apktool at
        // v1.0.756-757 and on the whole corpus 2026-09-20: 189,595 ids /
        // 1,516,059 values / 96,956 bags, 0 reader gaps) -- what a device does
        // (AssetManager2 reads the tables in place). History: OFF at v1.0.911
        // behind MACAPK_ARSC_INDEX=1 while three ingest defects stood (the
        // platform table chosen by cwd, aapt2 collapsed names folding ids onto
        // one key, split tables not read at all -- fixed in v1.0.1589,
        // docs/resources-in-place-gap-assessment_2026-09-20.md FINDING 1/2 and
        // section 2, each with its falsifier measured by debug/ridiff.sh);
        // DEFAULT ON at v1.0.1589 with apktool's text tree as the fallback and
        // `=0` as the bisect; v1.0.1590 retired apktool (step 9) and then the
        // text loaders and the gate (step 9b), so this is the only path. An
        // ingest failure leaves the index EMPTY and says so loudly: on a device
        // a package whose table cannot be read does not install at all.
        boolean arscIngested = macapkTryArscIngest(apkPath);
        // ---- MACAPK_FW_VIEW (v1.0.1103, default ON): DECOUPLE the platform-table resolution
        // view from the APP-table gate above ----
        //
        // These two were coupled by accident, and the coupling was a real AOSP gap. The gate above
        // decides ONE thing: whether the APP's resources.arsc is ingested instead of apktool's
        // decoded text tree. macapkFwView is something else entirely -- a read-only resolution view
        // over the PLATFORM table (framework-res.apk), which macapkFrameworkTable() loads
        // unconditionally and which macapkBuildFwView() is already handed directly (see
        // macapkTryArscIngest: `ArscReader fw = macapkFrameworkTable(); ... macapkBuildFwView(fw)`).
        // Nothing about answering a FRAMEWORK id depends on how we read the APP's table.
        //
        // The consequence of the coupling: on the default path macapkFwView was null, so
        // Resources.getValue()'s framework branch was inert and every framework <dimen>/<bool>/
        // <integer>/<string> id fell through to TYPE_NULL -- i.e. we told apps "no such resource"
        // for ids that are frozen public API. Colours survived only because resolveColorById
        // carries a hand-transcribed 28-entry fallback table (FW_CLASSIC_COLOR_NAMES); nothing
        // covered the other types.
        //
        // COST, measured before landing (not estimated): `framework resolution view: 1310 styles,
        // 8189 values, 29/2737 configs, 290ms`. Paid once -- macapkFwTableCache and the view are
        // both effectively process-lifetime (a Configuration change constructs a new ResourceIndex
        // but the table cache is static).
        //
        // BLAST RADIUS, stated because it is larger than "getValue answers more ids": macapkFwView
        // is read by ~8 call sites (framework styles, attr enum/flag compilation, theme lookups),
        // so turning it on switches those from the hand-transcribed tables to the platform table.
        // That is the AOSP-correct source, but it is a source SWITCH, which is why this was judged
        // on a before/after debug/recap_fleet.sh capture rather than on a sweep alone -- and the
        // capture is exactly why the gate ships OFF. See macapkFwViewEnabled() for the numbers and
        // the root cause (Material's TextAppearance validation fails under platform-table attrs,
        // which aborts deskclock's onCreate outright).
        // CACHED ACROSS INSTANCES, because a Configuration change (rotation, night-mode flip)
        // constructs a brand-new ResourceIndex and macapkBuildFwView re-PARSES the framework arsc
        // -- so without this every rotation would pay the 290ms again. The key deliberately
        // OVER-specifies: it carries every cfg field this class tracks, not just the density that
        // macapkFwCfgScore actually reads today. Over-keying only costs a rebuild that we would
        // have done anyway; under-keying would silently serve a view selected for the wrong
        // configuration, which is the kind of bug that shows up as one wrong dimension months
        // later. If a future change makes the view depend on more state, the key already covers it.
        if (macapkFwView == null && macapkFwViewEnabled()) {
            ArscReader fwOnly = macapkFrameworkTable();
            if (fwOnly != null) {
                // macapkBuildFwView is the cache wrapper (in-memory keyed by
                // config, then the on-disk replay); it only reaches the 290 ms
                // full parse on a genuine miss.
                macapkFwView = macapkBuildFwView(fwOnly);
            } else {
                // Same reasoning as the arsc path: a missing framework table is not fatal, it just
                // leaves the hand-transcribed tables serving, exactly as before v1103.
                NativeLog.w(TAG, "framework resolution view unavailable "
                    + "(no apktool framework table); framework ids keep the legacy tables");
            }
        }
        if (!arscIngested) {
            NativeLog.e(TAG, "resources.arsc NOT ingested for " + apkPath
                + " -- the installed apk set is the only resource source (v1.0.1590),"
                + " so every resource lookup of this app will fail");
        }
    }

    // ================================================================
    // The ingest: resources.arsc, read directly (v1.0.911; the only path
    // since v1.0.1590)
    // ================================================================
    //
    // The v757 plan's "THE SWAP": feed this index from ArscReader (the
    // native binary-table parser, byte-verified against apktool on 22/22
    // fleet apps and the whole corpus) instead of from apktool's decoded
    // values text tree. Since v1.0.1590 everything FILE-backed (layouts,
    // drawables, res/color selectors, menu/xml/font files) is served from
    // the members' file rows too (macapkIngestFileRows / macapkReadFileRow);
    // the text loaders this section replaced are deleted.
    //
    // The one deliberate semantic unification over the old text path: it
    // selected value configs through TWO parallel mechanisms --
    // matchingValueDirs/qualifierScore for most types, but a hardcoded
    // CONFIG_DIRS list (values, values-v31, values-v34) for colors and
    // styles. CONFIG_DIRS had no night/locale/orientation entries, which is
    // why values-night colors and styles -- shipped by 41 of the 62 decoded
    // corpus apps -- were unreachable while every OTHER value type honoured
    // night. Here every row of every type is scored by the SAME
    // qualifierScore, so values-night colors/styles fall out of the existing
    // night handling; the v31/v34 half of CONFIG_DIRS' intent is preserved by
    // cfgResSdk (see that field's comment).
    //
    // Override semantics: configs apply in ASCENDING qualifierScore order and
    // each row put()s, so the best matching config wins per key -- the
    // last-put-wins contract, in one place.

    /** MACAPK_FW_VIEW -- build the PLATFORM-table resolution view regardless of the APP-table gate.
     *
     *  **DEFAULT ON since v1.0.1104.** It shipped OFF at v1.0.1103 because a before/after
     *  `debug/recap_fleet.sh` measured deskclock 97.27 -> 0.00 and newpipe 76.69 -> 6.76. BOTH were
     *  bugs of ours that the platform table merely exposed, and both are now fixed:
     *
     *   1. `macapkFwItemText`'s TYPE_REFERENCE branch rendered every type except
     *      color/string/dimen/bool/integer/fraction as a raw `@0x%08x`, discarding the name it
     *      already held. A `style/` ref therefore reached our NAME-based resolvers as
     *      `@0x010301ed` instead of `@android:style/TextAppearance.Material`. AOSP's
     *      `@android:style/Widget` is `{textAppearance=?android:textAppearance}`, so Material's
     *      ThemeEnforcement found no usable resource id and threw "requires a valid TextAppearance
     *      attribute" -> FrameLayout fallback -> ClassCastException -> onCreate aborted (deskclock).
     *   2. `Resources.getXml` built a TEXT DomXmlResourceParser unconditionally, but a framework id
     *      is served straight out of framework-res.apk as aapt-COMPILED AXML, which fails
     *      "Content is not allowed in prolog". It now sniffs the RES_XML_TYPE magic and routes to
     *      BinaryXmlParser, the same discrimination the drawable path already did (newpipe).
     *
     *  Re-measured with both fixes, same 17 pairs, only this gate changed: **mean delta +0.04**,
     *  deskclock 97.27, newpipe 77.53, bobball 89.45, bouncy +0.62, anuto +1.50, and the largest
     *  single dip is unitconverter -1.22. That is what made the flip defensible; a sweep could not
     *  have judged it (all 13 protected rows are games on colour bands).
     *
     *  `MACAPK_FW_VIEW=0` restores the pre-v1103 behaviour (view built only under the app gate),
     *  which is the bisect handle if a Canvas-tier row moves. */
    private static boolean macapkFwViewEnabled() {
        return !"0".equals(System.getenv("MACAPK_FW_VIEW"));
    }

    /** BISECT GATE (2026-09-26, default ON). A framework selector row whose
     *  android:alpha is a theme attribute has it resolved (see the selector
     *  flattener). =0 restores the pre-change skip, which left such rows opaque. */
    private static final boolean MACAPK_FWCSL_THEME_ALPHA =
        !"0".equals(System.getenv("MACAPK_FWCSL_THEME_ALPHA"));

    /** "?0x01010552" (a compiled framework ?attr) -> its value in the application
     *  theme as the flattener's alpha code already understands it ("0.87", or an
     *  "@0x..." float reference), or the input unchanged when it does not resolve
     *  (the caller then skips it, the pre-change behaviour). */
    private String macapkFwThemeAlpha(String alphaAttr) {
        try {
            MacapkFwView vv = macapkFwView;
            int attrId = (int) Long.parseLong(alphaAttr.substring(3), 16);
            String tn = vv == null ? null : vv.publicMap.get(attrId);
            if (tn == null || !tn.startsWith("attr/") || applicationThemeName == null) return alphaAttr;
            String raw = resolveAttrInTheme(applicationThemeName, "android:" + tn.substring(5));
            if (raw == null) return alphaAttr;
            raw = raw.trim();
            if (raw.startsWith("@0x")) return raw;
            Float.parseFloat(raw);   // only a literal float is handed on
            return raw;
        } catch (Throwable t) {
            return alphaAttr;
        }
    }

    private static Boolean macapkFwStyleTraceCache;
    private static boolean macapkFwStyleTrace() {
        Boolean b = macapkFwStyleTraceCache;
        if (b == null) { b = "1".equals(System.getenv("MACAPK_TRACE_FWSTYLE"));
                         macapkFwStyleTraceCache = b; }
        return b;
    }

    /** Process-lifetime cache of the platform resolution view, keyed by the configuration it was
     *  selected for. See the constructor comment for why the key over-specifies. */
    private static volatile MacapkFwView macapkFwViewCache;
    private static volatile String macapkFwViewKey;

    /** Try the arsc ingest. Returns false -- with all partially-filled maps
     *  wiped -- on ANY failure, so the caller falls back to the apktool
     *  text ingest. Never throws. */
    private boolean macapkTryArscIngest(String apkPath) {
        try {
            ArscReader fw = macapkFrameworkTable();
            if (fw == null) {
                // The framework table is REQUIRED, not optional: without its
                // id->name map every @android: reference (style parents
                // included) degrades to a raw hex id, which breaks the
                // framework-theme family detection downstream -- a subtly
                // wrong index is worse than an empty one.
                NativeLog.e(TAG, "arsc ingest: no platform resource table is "
                    + "available (see macapkFrameworkApkFile); the index stays EMPTY");
                return false;
            }
            List<ArscReader> tables = macapkReadArscTables(apkPath, fw);
            if (tables.isEmpty()) {
                NativeLog.e(TAG, "arsc ingest: no resources.arsc found (apk="
                    + apkPath + "); the index stays EMPTY");
                return false;
            }
            // Raise the -v<N> acceptance to the level CONFIG_DIRS already
            // implies BEFORE any row is scored (see cfgResSdk's comment).
            // COHERENCE EXCEPTION (2026-08-08): when PresentedSdk chose the
            // level (MACAPK_SDK_INT / MACAPK_SDK_FROM_TARGET), the -v<N>
            // acceptance IS that level -- one number everywhere -- and the
            // 34 paper-over constant goes inert. It survives only for the
            // legacy default, where SDK_INT=29 still contradicts the
            // v31/v34 serving it exists to preserve.
            cfgResSdk = macapkUnifiedResSdk();
            macapkIngestRows(tables);
            // The RESOLUTION view over the platform table itself (framework
            // themes, widget styles, colours). Additive: its failure leaves
            // the hand-transcribed tables serving.
            macapkFwView = macapkBuildFwView(fw);
            return true;
        } catch (Throwable t) {
            NativeLog.e(TAG, "arsc ingest FAILED (" + t
                + "); the index stays EMPTY");
            macapkClearIngestState();
            macapkFwView = null;
            // Keep the unified level coherent even for the empty index (see
            // cfgResSdk's 2026-08-19 note).
            cfgResSdk = macapkUnifiedResSdk();
            return false;
        }
    }

    /** The parsed resource tables of EVERY member of the installed set that
     *  carries one, base first, then the splits in InstalledSplits order.
     *
     *  On a device every member is mounted into the one AssetManager
     *  (ResourcesManager.extractApkKeys, core/java/android/app/
     *  ResourcesManager.java:629-652 android11-release: mResDir first, then
     *  each mSplitResDirs entry in array order; createAssetManager :702-716
     *  hands them to AssetManager.Builder.addApkAssets in that order) and
     *  every split's table declares the SAME package id (0x7f), so they form
     *  ONE package group (AssetManager2::BuildDynamicRefTable, libs/androidfw/
     *  AssetManager2.cpp:227-229). A config split's table is where the
     *  density/locale-qualified rows live -- language splits carry hundreds
     *  to ~20k strings each, density splits the drawable file rows plus a
     *  few dimens (measured 2026-09-20 on the corpus, docs/resources-in-
     *  place-gap-assessment_2026-09-20.md section 2). Reading the base alone
     *  answered "Continue" where a German device answers "Weiter".
     *
     *  Each table is parsed against the same platform map, so cross-member
     *  references render identically. A member without a table (an ABI or
     *  asset-pack split) is skipped. The legacy synthetic `<codeDir>/
     *  _merged.apk` is consulted only when no member carries a table at all
     *  (a code dir from before v1.0.1586 whose apkPath is not an installed
     *  base). */
    private List<ArscReader> macapkReadArscTables(String apkPath, ArscReader fw)
            throws Exception {
        List<byte[]> blobs = new ArrayList<>();
        List<File> owners = new ArrayList<>();
        for (File m : InstalledSplits.of(apkPath)) {
            byte[] bytes;
            try {
                bytes = macapkReadArscEntry(m);
            } catch (Throwable t) {
                // One unreadable sibling (a truncated split_*.apk) must not
                // drop the whole app to the text tree; a device would fail
                // to load THAT split and keep the rest.
                NativeLog.w(TAG, "arsc ingest: skipping " + m.getName() + " (" + t + ")");
                continue;
            }
            if (bytes != null) { blobs.add(bytes); owners.add(m); }
        }
        List<ArscReader> out = new ArrayList<>();
        if (blobs.isEmpty()) return out;
        macapkMembers = owners;
        // A reference from one member to an id whose ENTRY (and so whose
        // name) lives only in another member -- the base's theme naming a
        // drawable that ships in the density splits -- must render by name,
        // as it does on a device where all the tables are one group. The
        // reader names ids from its own table and the map it is handed, so
        // the map is the platform's plus every member's names (an index-only
        // pass, no rows built), gathered BEFORE any member is fully parsed.
        Map<Integer, String> names = fw.publicMap();
        if (blobs.size() > 1) {
            names = new HashMap<>(fw.publicMap());
            for (byte[] b : blobs) {
                for (Map.Entry<Integer, String> en : ArscReader.parseFrameworkIndex(b).publicMap().entrySet())
                    names.putIfAbsent(en.getKey(), en.getValue());
            }
        }
        for (byte[] b : blobs) out.add(ArscReader.parse(b, names, fw));
        macapkTables = out;
        return out;
    }

    /** `resources.arsc` of one zip, or null when the file is not a zip or has
     *  no table. */
    private static byte[] macapkReadArscEntry(File f) throws java.io.IOException {
        if (f == null || !f.isFile()) return null;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
            java.util.zip.ZipEntry e = zf.getEntry("resources.arsc");
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    /** The parsed FRAMEWORK table (id -> name map + attr enum/flag bags),
     *  from apktool's own framework apk -- the exact table apktool resolves
     *  @android: references and framework enum names against, so using it
     *  keeps the arsc ingest's text forms identical to the apktool forms
     *  every consumer above this class was verified on. Cached statically:
     *  the table is immutable and ~18.6MB, and a Configuration change
     *  constructs a brand-new ResourceIndex in the same process. */
    private static volatile ArscReader macapkFwTableCache;
    /** Boot.prefetchFrameworkResources: index the platform table on a background thread. */
    static void prefetchFrameworkTable() {
        try { macapkFrameworkTable(); } catch (Throwable ignore) { }
    }
    private static ArscReader macapkFrameworkTable() {
        ArscReader c = macapkFwTableCache;
        if (c != null) return c;
        synchronized (ResourceIndex.class) {
            if (macapkFwTableCache != null) return macapkFwTableCache;
            File f = macapkFrameworkApkFile();
            if (f == null) return null;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
                java.util.zip.ZipEntry e = zf.getEntry("resources.arsc");
                if (e == null) return null;
                byte[] bytes;
                try (InputStream in = zf.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                macapkFwTableCache = ArscReader.parseFrameworkIndex(bytes);
                NativeLog.i(TAG, "framework resource table indexed: "
                    + macapkFwTableCache.publicMap().size() + " ids from " + f);
                return macapkFwTableCache;
            } catch (Throwable t) {
                NativeLog.w(TAG, "framework table parse failed: " + t);
                return null;
            }
        }
    }

    // ================================================================
    // The FRAMEWORK RESOLUTION VIEW (gate-only, v1.0.913)
    // ================================================================
    //
    // Real Android resolves @android: references and platform-theme
    // attributes against framework-res.apk's own resource table. Until now
    // this class answered those from hand-transcribed tables
    // (frameworkThemeAttr, macapkFrameworkStyleDef, macapkFrameworkButtonGeom,
    // the @android:color switch in resolveColorRef): roughly 107 rows, each a
    // human retyping a value that exists in binary form on this machine.
    // The platform table itself is parsed into this view and consulted
    // FIRST; the hand tables remain the fallback whenever the table is
    // unavailable or does not contain the queried name.
    //
    // TABLE SOURCE. MACAPK_FW_RES_APK=<path> when set (diagnostic override,
    // e.g. a framework-res.apk matching a reference device's API level), else
    // apktool's own ~/Library/apktool/framework/1.apk. PROVENANCE: apktool
    // 3.0.2's bundled table is built by the apktool project from AOSP
    // (Apache-2.0) at API 36 (its manifest reads versionCode=36,
    // versionName=16, targetSdk=36; verified 2026-08-08). Nothing is bundled
    // or redistributed by this change: it reads a file already on disk.
    // Shipping a table of our own is a separate decision, tracked in
    // docs/framework-res-table_2026-08-08.md. Known consequence of the level
    // skew: PUBLIC ids (frozen by AOSP) and long-stable values match an
    // API-30 reference, but INTERNAL ids (0x0106xxxx internals and friends)
    // shifted between levels, so chains that terminate in an internal
    // resource carry a different final resourceId than an API-30 device.
    //
    // MEMORY / COST (measured offline 2026-08-08, apktool table): full parse
    // 185ms for 248,474 rows across 2,914 configs; this view then keeps only
    // the config-selected slice (~1.3k styles + ~11k simple values, a few
    // MB), and the parsed reader is dropped on return. The view is rebuilt
    // per ResourceIndex construction (once per Configuration change).

    /** Gate-only framework resolution view; null = hand tables serve. */
    private volatile MacapkFwView macapkFwView;

    private static final class MacapkFwView {
        final File apk;                                  // for lazy compiled-CSL loads
        final Map<Integer, String> publicMap;            // id -> "type/name" (shared with the index cache)
        final Map<String, Integer> nameToId = new HashMap<>();
        final Map<String, StyleDef> styles = new HashMap<>();
        final Map<Integer, Object[]> simple = new HashMap<>();   // id -> {Integer dataType, Integer data, String text}
        final Map<String, Integer> cslCache = new HashMap<>();   // res/color path -> flattened default (null cached)
        /** "styleName itemKey" (space-joined; neither component can contain
         *  a space) -> the id of the LAST framework reference the
         *  item renderer followed when it settled a scalar item to its leaf
         *  literal (macapkFwItemText's dimen/bool/integer/fraction branch).
         *  AOSP reports that id as TypedValue.resourceId: ResolveReference
         *  (AssetManager2.cpp android11-release) does
         *  `*out_last_reference = in_out_value->data` per followed reference
         *  and the JNI CopyValue writes it into mResourceId. Our settle
         *  destroys the reference at view-build time, so the id is recorded
         *  here and re-published at query time (macapkFwStyleAttr). Items
         *  that were literals in the arsc never had a reference to follow,
         *  are absent here, and correctly answer resourceId 0. */
        final Map<String, Integer> settledOrigin = new HashMap<>();
        /** Framework attr name -> {symbol -> packed int} for <attr> bags that
         *  declare enum/flag symbols, plus the set of FLAG-typed attr names.
         *  aapt writes an enum assignment as TYPE_INT_DEC and a flag set as
         *  TYPE_INT_HEX (ResourceTypes.cpp), and the view's style items render
         *  those ints back to symbol names ("center"), so answering the probe
         *  (and any consumer of the raw string) faithfully needs the reverse
         *  map AND the flag-ness. Built once in macapkBuildFwView. */
        final Map<String, Map<String, Integer>> attrEnum = new HashMap<>();
        final java.util.Set<String> attrFlagged = new java.util.HashSet<>();
        MacapkFwView(File apk, Map<Integer, String> publicMap) {
            this.apk = apk;
            this.publicMap = publicMap;
        }
    }

    // ------------------------------------------------------------------
    // The last-reference channel (v1.0.917).
    //
    // WHY: AOSP's resolution APIs report, alongside type and data, the id
    // of the LAST reference ResolveReference followed to reach the value
    // (out_last_reference; the JNI glue seeds it 0 for theme resolution
    // and with the queried id for Resources.getValue, then CopyValue
    // stores it in TypedValue.resourceId). Our pipeline carries values as
    // strings and consumes reference chains in several places (the fw
    // style settle, the colour chain, chaseValueRef, the string chain),
    // so by the time a value is packed the chain terminus is gone. Every
    // chain consumer below PUBLISHES the id it dereferences into this
    // per-thread slot; the few packing sites that need it read it
    // immediately after the producing call returns. Contract:
    //  - resolveAttrInTheme clears the slot on entry, so an answer that
    //    followed no reference reads 0 (AOSP: a literal theme entry
    //    reports resourceId 0).
    //  - publications overwrite, so the slot holds the LAST followed id,
    //    which is exactly AOSP's out_last_reference semantics.
    //  - with the framework view absent (gate OFF) the settle origins are
    //    never baked; app-side chain publications still occur but every
    //    reader is a new v1.0.917 site, so gate-OFF packing output is
    //    unchanged relative to the readers' absence before this change.
    // ------------------------------------------------------------------
    private static final ThreadLocal<int[]> MACAPK_LAST_VALUE_ORIGIN =
        ThreadLocal.withInitial(() -> new int[1]);

    /** Read the last-reference slot. Callers read IMMEDIATELY after the
     *  producing call (resolveAttrInTheme / the getValue serves); any other
     *  distance from the producer risks a stale value. */
    public int macapkLastValueOriginId() { return MACAPK_LAST_VALUE_ORIGIN.get()[0]; }

    /** Reset the last-reference slot. resolveAttrInTheme does this on
     *  entry; Resources.getValue and the shared resolver's hop loop do it
     *  before serves whose producers do not pass through resolveAttrInTheme. */
    public void macapkClearValueOrigin() { MACAPK_LAST_VALUE_ORIGIN.get()[0] = 0; }

    private void macapkPublishValueOrigin(int id) {
        if (id != 0) MACAPK_LAST_VALUE_ORIGIN.get()[0] = id;
    }

    /** The framework apk this process resolves against, in preference
     *  order:
     *   (1) MACAPK_FW_RES_APK -- the explicit pin (debug/fwprobe.sh pins an
     *       API-30 build for parity with its API-30 golden AVD), which the
     *       runtime DEFAULTS to the apk bundled next to its tools (main.cpp,
     *       <binary_dir>/tools/framework-res.apk: the FULL API-36 platform
     *       apk built from AOSP by scripts/build_framework_res.sh, Apache-2.0)
     *       so a bundle launch and a dev-tree launch read the SAME table;
     *   (2) the same file found RELATIVE TO THE JAR this class runs from --
     *       what an offline harness with no env (ridiff, levelprobe,
     *       nightaudit, a bare JVM on the built jars) resolves against.
     *  Nothing else. v1.0.1590 dropped the two fallbacks that used to follow:
     *  apktool's self-extracted ~/Library/apktool/framework/1.apk (apktool is
     *  not run any more, and that table-only jar cannot serve framework FILE
     *  resources -- newpipe went blank on it) and the CWD-RELATIVE dev-tree
     *  builds (thirdparty/framework-res/framework-res-api<level>.apk), whose
     *  API-30 copy mis-named every post-30 public id and was chosen whenever
     *  the cwd happened to be the repo -- the divergence that
     *  docs/resources-in-place-gap-assessment_2026-09-20.md FINDING 1 pinned.
     *  A build that lacks the apk is a BROKEN build (runtime/scripts/build.sh
     *  stages it; scripts/stage_framework_res.sh refuses a table-only
     *  stand-in), so the honest answer is null plus a loud line, never a
     *  quietly different table. The parse log line downstream names whichever
     *  file won and its id count (15,122 = API 36), so a capture's
     *  provenance is in its log. */
    private static File macapkFrameworkApkFile() {
        String env = System.getenv("MACAPK_FW_RES_APK");
        if (env != null && !env.isEmpty()) {
            File f = new File(env);
            if (f.isFile()) return f;
            NativeLog.w(TAG, "MACAPK_FW_RES_APK=" + env
                + " is not a file; looking next to the runtime's jars instead");
        }
        try {
            java.security.CodeSource cs = ResourceIndex.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File jar = new File(cs.getLocation().toURI());
                File libDir = jar.isFile() ? jar.getParentFile() : jar;
                if (libDir != null) {
                    for (String rel : new String[] { "../bin/tools/framework-res.apk",
                                                     "../Resources/tools/framework-res.apk" }) {
                        File c = new File(libDir, rel);
                        if (c.isFile()) return c.getCanonicalFile();
                    }
                }
            }
        } catch (Throwable ignored) { /* fall through */ }
        NativeLog.e(TAG, "framework resource apk NOT FOUND: MACAPK_FW_RES_APK is unset/invalid and no"
            + " tools/framework-res.apk sits next to the runtime's jars -- this build is incomplete"
            + " (runtime/scripts/build.sh stages it); every @android: reference will fail");
        return null;
    }

    /** Config score for FRAMEWORK-table rows. Density and -v<N> are handled
     *  here; every other qualifier delegates to qualifierScore so the two
     *  paths cannot drift. The two deliberate differences from the app path:
     *  - -v<N> is accepted unconditionally. The platform table only carries
     *    overlays up to its own build level, so vetting them against our
     *    advertised SDK would strip the table's own internal structure and
     *    compose a theme no real device ever shipped.
     *  - density qualifiers are scored instead of dropped (the app path's
     *    values dirs are density-free, the framework's drawable entries are
     *    not). Banded ABOVE version (<=100) and BELOW night (300), matching
     *    AOSP ResTable_config precedence: night > density > version. */
    private int macapkFwCfgScore(String cfg) {
        String quals = cfg.startsWith("-") ? cfg.substring(1) : cfg;
        // qualifierScore("") is 0 unless MACAPK_RES_SIZECLASS lifts every
        // class-less config by one step (macapkSizeClassBand). The lift only
        // preserves the old order if it is UNIFORM, so the default row and a
        // density- or version-only row (which never reach qualifierScore
        // otherwise) take it too; skipping them would drop `-xhdpi-v21` below
        // `-night`, a reorder this gate has no business making.
        if (quals.isEmpty()) return qualifierScore("");
        int extra = 0;
        StringBuilder rest = new StringBuilder();
        for (String q : quals.split("-")) {
            if (q.isEmpty()) continue;
            if (q.length() >= 2 && q.charAt(0) == 'v' && allDigits(q.substring(1))) {
                extra += 1 + Math.min(Integer.parseInt(q.substring(1)), 99);
                continue;
            }
            Integer dg = macapkFwDensityGoodness(q);
            if (dg != null) { extra += dg; continue; }
            if (rest.length() > 0) rest.append('-');
            rest.append(q);
        }
        if (rest.length() == 0) return extra + qualifierScore("");
        int base = qualifierScore(rest.toString());
        return base < 0 ? -1 : base + extra;
    }

    /** Density-qualifier goodness relative to cfgDensityDpi. AOSP's
     *  isBetterThan prefers an exact match, then the closest scale-down
     *  candidate (higher density), then the closest scale-up; anydpi (the
     *  vector variants) beats every raster bucket. Only the ORDER matters,
     *  the band keeps every value in (100, 300). */
    private Integer macapkFwDensityGoodness(String q) {
        int d;
        switch (q) {
            case "anydpi":  return 295;
            case "nodpi":   return 292;
            case "ldpi":    d = 120; break;
            case "mdpi":    d = 160; break;
            case "tvdpi":   d = 213; break;
            case "hdpi":    d = 240; break;
            case "xhdpi":   d = 320; break;
            case "xxhdpi":  d = 480; break;
            case "xxxhdpi": d = 640; break;
            default:
                if (q.endsWith("dpi") && q.length() > 3
                        && allDigits(q.substring(0, q.length() - 3))) {
                    d = Integer.parseInt(q.substring(0, q.length() - 3));
                    break;
                }
                return null;
        }
        if (d == cfgDensityDpi) return 290;
        if (d > cfgDensityDpi) return 280 - Math.min(60, (d - cfgDensityDpi) / 16);
        return 200 - Math.min(80, (cfgDensityDpi - d) / 4);
    }

    // ================================================================
    // The framework-view REPLAY cache (v1.0.1636)
    // ================================================================
    //
    // macapkBuildFwViewUncached full-parses framework-res.apk's 52 MB
    // resources.arsc and keeps the config-selected slice: 279-334 ms on the
    // boot thread, BEFORE first feedback, on EVERY app (measured 2026-09-22
    // from the boottime .tl timeline; the prefetch thread only covers the id
    // map, not this). Yet the OUTPUT is a pure function of three things:
    //   1. framework-res.apk's bytes (size + mtime identify the staged file;
    //      the build re-stages it, so a dev rebuild bumps the mtime and
    //      invalidates the cache for free),
    //   2. the configuration slice key (macapkFwViewCfgKey), and
    //   3. this builder's logic (FORMAT_VER, bumped by hand on any change to
    //      macapkBuildFwViewUncached / macapkFwStyleDef / macapkFwItemText /
    //      ArscReader parse shape).
    // So the slice is serialized to ~/Library/Caches/MacApk/fwview and replayed
    // on the next launch: the miss path parses once and writes, every warm
    // launch reads the compact slice (a few hundred KB) instead of the 52 MB.
    // publicMap/nameToId are NOT serialized -- they are rebuilt from the same
    // framework table (indexReader) the live build reads, so a replayed view is
    // byte-for-byte the object the build would have produced. cslCache starts
    // empty either way (filled lazily).
    //
    // The cache is FRAMEWORK-GLOBAL, not per-app (the view depends on the
    // platform table + config, never on the app), so it lives in the shared
    // MacApk cache, shared across every installed app and the dev tree. It is
    // regenerable and never read by guest code. MACAPK_FWVIEW_DISK_CACHE=0
    // bypasses it; MACAPK_FWVIEW_DISK_CACHE_VERIFY=1 builds AND replays and
    // logs any field mismatch (the positive control).
    private static final int MACAPK_FWVIEW_CACHE_MAGIC = 0x4657_5631;   // "FWV1"
    private static final int MACAPK_FWVIEW_CACHE_FORMAT_VER = 1;

    /** The framework's own -w/-h/-sw dp thresholds, set by the last live build
     *  (macapkBuildFwViewUncached) so the wrapper can canonicalize the config
     *  key against them. int[3][] = {widthThresholds, heightThresholds,
     *  smallestWidthThresholds}, each sorted ascending; under
     *  MACAPK_RES_SIZECLASS int[5][], adding {sizeClasses} and {longSet}
     *  (macapkFwBucketsFromConfigs). Instance field: a
     *  ResourceIndex is built on one thread and the wrapper reads it right
     *  after the synchronous build returns. */
    private int[][] macapkLastBuildThresholds;

    /** The configuration slice key. The non-dp fields (density, night,
     *  orientation, locale, resSdk) are exact; the three dp fields are
     *  CANONICALIZED to the framework's own qualifier thresholds when they are
     *  known, so the key is invariant to a sub-threshold pixel jitter in the
     *  window size (the visible frame can wobble +/-1 px between launches,
     *  which moved screenHeightDp 906<->907 and defeated an exact-dp key) yet
     *  still splits at every real -wNdp/-hNdp/-swNdp boundary (a device dp in
     *  [t_i, t_{i+1}) selects exactly the framework configs with N <= t_i, so
     *  keying on t_i is selection-exact). With thresholds unknown (the
     *  first-ever launch after a build, before the sidecar exists) it falls
     *  back to the raw dp -- that launch misses and writes the sidecar, and
     *  every launch after it canonicalizes and hits. */
    private String macapkFwViewCfgKey(int[][] thr) {
        String w, h, sw;
        if (thr != null) {
            w  = Integer.toString(macapkFwCanon(cfgScreenWidthDp, thr[0]));
            h  = Integer.toString(macapkFwCanon(cfgScreenHeightDp, thr[1]));
            sw = Integer.toString(macapkFwCanon(cfgSmallestScreenWidthDp, thr[2]));
        } else {
            w  = cfgScreenWidthDp + "~";     // raw marker: no canonicalization yet
            h  = cfgScreenHeightDp + "~";
            sw = cfgSmallestScreenWidthDp + "~";
        }
        // MACAPK_RES_SIZECLASS: the platform table's values-large / -xlarge
        // rows are now selected by the size class, which the canonicalized dp
        // above does NOT determine (the class follows reduceScreenLayout's
        // 470 / 640x480 / 960x720dp and 5:3 boundaries, not the table's own
        // -w/-h/-sw thresholds), so it joins the key. Only the bits the table
        // can select on (macapkLayoutBitsSelectedBy on thr): the staged table
        // ships no long/notlong, so the aspect stays OUT of the key and a 5:3
        // crossing shares one slice instead of spawning an identical one. thr
        // unknown (null, or a pre-fix 3-array sidecar) keeps both bits, so the
        // raw first-launch key stays exact. Appended only while the gate is on,
        // so `=0` keeps the pre-fix key and its cached slices.
        final int sl = cfgScreenLayout & macapkLayoutBitsSelectedBy(thr);
        return cfgDensityDpi + "|" + cfgNight + "|" + cfgOrientation + "|"
            + w + "x" + h + "|" + sw
            + "|" + cfgLanguage + "-" + cfgRegion + "|" + cfgResSdk
            + (MACAPK_RES_SIZECLASS ? "|sl" + Integer.toHexString(sl) : "");
    }

    /** The largest threshold <= dp (0 if none), i.e. the qualifier bucket dp
     *  falls in. Selection-preserving: a -wNdp/-hNdp/-swNdp framework config is
     *  accepted iff N <= dp, and the most specific accepted one wins, so the
     *  outcome depends only on this value. */
    private static int macapkFwCanon(int dp, int[] thr) {
        int best = 0;
        if (thr != null) for (int t : thr) { if (t <= dp && t > best) best = t; }
        return best;
    }

    /** Extract the sorted unique -wNdp / -hNdp / -swNdp thresholds from a set
     *  of framework config qualifier strings ("-en-rUS-sw600dp-v31", ...). */
    private static int[][] macapkFwThresholdsFromConfigs(java.util.Collection<String> cfgs) {
        java.util.TreeSet<Integer> w = new java.util.TreeSet<>();
        java.util.TreeSet<Integer> h = new java.util.TreeSet<>();
        java.util.TreeSet<Integer> sw = new java.util.TreeSet<>();
        for (String cfg : cfgs) {
            if (cfg == null || cfg.isEmpty()) continue;
            for (String q : cfg.split("-")) {
                if (q.length() < 4 || !q.endsWith("dp")) continue;
                String mid = q.substring(0, q.length() - 2);   // strip "dp"
                if (mid.startsWith("sw") && allDigits(mid.substring(2))) {
                    sw.add(Integer.parseInt(mid.substring(2)));
                } else if (mid.startsWith("w") && allDigits(mid.substring(1))) {
                    w.add(Integer.parseInt(mid.substring(1)));
                } else if (mid.startsWith("h") && allDigits(mid.substring(1))) {
                    h.add(Integer.parseInt(mid.substring(1)));
                }
            }
        }
        return new int[][] { macapkIntSet(w), macapkIntSet(h), macapkIntSet(sw) };
    }

    private static int[] macapkIntSet(java.util.TreeSet<Integer> s) {
        int[] a = new int[s.size()];
        int i = 0;
        for (int v : s) a[i++] = v;
        return a;
    }

    // ---- display-resize support (v1.0.1637) ----------------------------------
    // docs/window-resize-and-cross-thread-surface_2026-09-23.md, Stage 1. A
    // window resize changes screenWidthDp/screenHeightDp (and orientation on a
    // rotate), and this class bakes those at construction. Two questions follow,
    // and both are answered from the qualifier strings of the tables already in
    // memory, so neither re-reads anything.

    /** The APP's own size qualifiers: {-wNdp, -hNdp, -swNdp, screen-size
     *  buckets (SCREENLAYOUT_SIZE_*), {1 if any long/notlong}}. APP ONLY, framework
     *  excluded, because that is what AOSP's size buckets are built from:
     *  ActivityThread.reportSizeConfigurations -> Resources.getSizeConfigurations
     *  -> NativeGetSizeConfigurations -> AssetManager2::GetResourceConfigurations(
     *  exclude_system=true, exclude_mipmap=false) (android14-release
     *  core/jni/android_util_AssetManager.cpp:1038-1041). Lazily built once per
     *  index; the tables never change after ingest. */
    private volatile int[][] macapkAppSizeBuckets;

    public int[][] macapkAppSizeBuckets() {
        int[][] b = macapkAppSizeBuckets;
        if (b != null) return b;
        java.util.HashSet<String> cfgs = new java.util.HashSet<>();
        for (ArscReader t : macapkTables) {
            for (ArscReader.Row r : t.rows()) {
                if (r.config != null && !r.config.isEmpty()) cfgs.add(r.config);
            }
        }
        int[][] dp = macapkFwThresholdsFromConfigs(cfgs);
        int[][] sa = macapkSizeAspectFromConfigs(cfgs);
        b = new int[][] { dp[0], dp[1], dp[2], sa[0], sa[1] };
        macapkAppSizeBuckets = b;
        return b;
    }

    /** The screen-size classes (SCREENLAYOUT_SIZE_*, sorted) a set of config
     *  qualifier strings names, and {1} if any of them names long/notlong
     *  else {0}: arrays 3 and 4 of the macapkAppSizeBuckets shape. Shared by
     *  the app buckets and the framework's (macapkFwBucketsFromConfigs). */
    private static int[][] macapkSizeAspectFromConfigs(java.util.Collection<String> cfgs) {
        java.util.TreeSet<Integer> sizes = new java.util.TreeSet<>();
        boolean longSet = false;
        for (String cfg : cfgs) {
            if (cfg == null || cfg.isEmpty()) continue;
            for (String q : cfg.split("-")) {
                int sz = macapkSizeClassOf(q);
                if (sz != 0) sizes.add(sz);
                else if (q.equals("long") || q.equals("notlong")) longSet = true;
            }
        }
        return new int[][] { macapkIntSet(sizes), new int[] { longSet ? 1 : 0 } };
    }

    /** The framework's buckets: its -w/-h/-sw dp thresholds and, under
     *  MACAPK_RES_SIZECLASS, the size classes and aspect flag it ships (the
     *  macapkAppSizeBuckets shape, 5 arrays). With the gate off it is exactly
     *  macapkFwThresholdsFromConfigs (3 arrays), so `=0` writes the pre-fix
     *  sidecar byte for byte. */
    private static int[][] macapkFwBucketsFromConfigs(java.util.Collection<String> cfgs) {
        int[][] dp = macapkFwThresholdsFromConfigs(cfgs);
        if (!MACAPK_RES_SIZECLASS) return dp;
        int[][] sa = macapkSizeAspectFromConfigs(cfgs);
        return new int[][] { dp[0], dp[1], dp[2], sa[0], sa[1] };
    }

    /** Which Configuration.screenLayout bits can change what a table with
     *  buckets `b` (the macapkAppSizeBuckets shape) SELECTS: the size class only
     *  if the table ships a size-class config, the long aspect only if it ships
     *  long/notlong. Exact, not a heuristic: with no class config in a table the
     *  class moves nothing but the "no class" lift of macapkSizeClassBand, which
     *  is the same for every row and so reorders nothing; with no long/notlong
     *  config the aspect is never read at all. `b` without the two layout arrays
     *  (null, or the 3-array framework sidecar written before
     *  MACAPK_RES_SIZECLASS) is UNKNOWN and keeps both, which only costs a
     *  rebuild. 0 with the gate off: selection ignores screenLayout there.
     *  This is AOSP's own economy -- SizeConfigurationBuckets
     *  .crossesScreenLayoutLongThreshold answers "no" unless the app declared
     *  long/notlong (mScreenLayoutLongSet, android14-release
     *  core/java/android/window/SizeConfigurationBuckets.java) -- applied to
     *  the question "does the index need rebuilding" instead of "does the
     *  activity need relaunching". */
    private static int macapkLayoutBitsSelectedBy(int[][] b) {
        if (!MACAPK_RES_SIZECLASS) return 0;
        final int size = android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK;
        final int lng = android.content.res.Configuration.SCREENLAYOUT_LONG_MASK;
        if (b == null || b.length < 5) return size | lng;
        int m = 0;
        if (b[3].length > 0) m |= size;
        if (b[4].length > 0 && b[4][0] != 0) m |= lng;
        return m;
    }

    /** Would an index built for `next` SELECT different resources than this
     *  one? True iff a qualifier this class matches on could flip: orientation,
     *  density, night, the screen size class or long aspect when the app's or the
     *  framework's table ships a config naming it (MACAPK_RES_SIZECLASS;
     *  macapkLayoutBitsSelectedBy), or a -w/-h/-swNdp threshold of the app's tables OR the
     *  framework's crossed. A qualifier N is accepted iff N <= dp, so selection
     *  flips exactly when a threshold lies between the old and new value -- the
     *  same test as AOSP's SizeConfigurationBuckets.crossesSizeThreshold.
     *  Framework thresholds unknown (first launch after a framework build, before
     *  the sidecar exists) answers "any dp change", which only costs a rebuild. */
    public boolean macapkSelectionChangesFor(android.content.res.Configuration next) {
        if (next == null) return false;
        int o = next.orientation != 0 ? next.orientation : 1;
        if (o != cfgOrientation) return true;
        if (next.densityDpi > 0 && next.densityDpi != cfgDensityDpi) return true;
        boolean night = (next.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (night != cfgNight) return true;
        int[][] app = macapkAppSizeBuckets();
        int[][] fw = null;
        try {
            File fwApk = macapkFrameworkApkFile();
            File dir = macapkFwViewCacheDir();
            if (fwApk != null && dir != null) fw = macapkFwThresholds(macapkFwIdHash(fwApk), dir);
        } catch (Throwable ignored) {}
        // MACAPK_RES_SIZECLASS: the size class and the long aspect select
        // resources too, but only through configs that NAME them. The platform
        // table ships -large / -xlarge (so a class change always rebuilds) and,
        // measured on the staged framework-res.apk, no long/notlong -- so for an
        // app that ships none either, a drag across the 5:3 aspect line changes
        // no selection and must not force a synchronous rebuild plus a
        // framework-view miss (the v1.0.1636/1637 resize contract: rebuild only
        // when a bucket some table ships is crossed). Framework side unknown
        // (no sidecar yet, or a pre-fix one) keeps both bits. 0 with the gate off.
        final int layoutBits = macapkLayoutBitsSelectedBy(app) | macapkLayoutBitsSelectedBy(fw);
        if (((next.screenLayout ^ cfgScreenLayout) & layoutBits) != 0) return true;
        if (macapkDpCrosses(app[0], cfgScreenWidthDp, next.screenWidthDp)
                || macapkDpCrosses(app[1], cfgScreenHeightDp, next.screenHeightDp)
                || macapkDpCrosses(app[2], cfgSmallestScreenWidthDp, next.smallestScreenWidthDp)) {
            return true;
        }
        if (fw == null) {
            return next.screenWidthDp != cfgScreenWidthDp
                || next.screenHeightDp != cfgScreenHeightDp
                || next.smallestScreenWidthDp != cfgSmallestScreenWidthDp;
        }
        return macapkDpCrosses(fw[0], cfgScreenWidthDp, next.screenWidthDp)
            || macapkDpCrosses(fw[1], cfgScreenHeightDp, next.screenHeightDp)
            || macapkDpCrosses(fw[2], cfgSmallestScreenWidthDp, next.smallestScreenWidthDp);
    }

    /** AOSP SizeConfigurationBuckets.crossesSizeThreshold (android14-release
     *  core/java/android/window/SizeConfigurationBuckets.java), verbatim logic. */
    public static boolean macapkDpCrosses(int[] thresholds, int firstDp, int secondDp) {
        if (thresholds == null) return false;
        for (int i = thresholds.length - 1; i >= 0; i--) {
            final int t = thresholds[i];
            if ((firstDp < t && secondDp >= t) || (firstDp >= t && secondDp < t)) return true;
        }
        return false;
    }

    /** Per-idHash cache of the framework's dp thresholds within THIS process,
     *  so a config-change rebuild does not re-read the sidecar. */
    private static final Map<String, int[][]> sFwThresholdCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The framework's dp thresholds for a build identity: this process's build
     *  (fast path), else the sidecar written by an earlier launch, else null
     *  (the first-ever launch after this framework build). */
    private static int[][] macapkFwThresholds(String idHash, File dir) {
        int[][] mem = sFwThresholdCache.get(idHash);
        if (mem != null) return mem;
        int[][] out = macapkReadFwThresholdsFile(new File(dir, "fw-" + idHash + ".thr"));
        if (out != null) sFwThresholdCache.put(idHash, out);
        return out;
    }

    /** Parse one `.thr` sidecar: MAGIC, then the three dp arrays, then --
     *  written only under MACAPK_RES_SIZECLASS -- the framework's size classes
     *  and its aspect flag (macapkFwBucketsFromConfigs). A file that ends after
     *  the dp arrays is the pre-fix format and comes back as 3 arrays, which
     *  macapkLayoutBitsSelectedBy reads as "layout unknown". The pre-fix READER
     *  stops after the third array, so the longer file stays readable by an
     *  older build sharing ~/Library/Caches/MacApk/fwview. Null when absent or
     *  corrupt. */
    private static int[][] macapkReadFwThresholdsFile(File thr) {
        if (!thr.isFile()) return null;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(thr)))) {
            if (in.readInt() != MACAPK_FWVIEW_CACHE_MAGIC) return null;
            int[][] out = new int[3][];
            for (int d = 0; d < 3; d++) {
                int n = in.readInt();
                if (n < 0 || n > 4096) return null;
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                out[d] = a;
            }
            int nSizes;
            try { nSizes = in.readInt(); } catch (java.io.EOFException pre) { return out; }
            // Anything malformed past the dp arrays degrades to "layout
            // unknown" (the dp arrays are still good), never to a wrong mask.
            if (nSizes < 0 || nSizes > 4) return out;
            int[] sizes = new int[nSizes];
            for (int i = 0; i < nSizes; i++) sizes[i] = in.readInt();
            if (in.readInt() != 1) return out;
            int[] lng = new int[] { in.readInt() };
            return new int[][] { out[0], out[1], out[2], sizes, lng };
        } catch (Throwable t) { return null; }
    }

    private static void macapkWriteFwThresholds(String idHash, File dir, int[][] thr) {
        if (thr == null) return;
        sFwThresholdCache.put(idHash, thr);
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            File f = new File(dir, "fw-" + idHash + ".thr");
            // Idempotent content; first writer wins -- except that a pre-fix
            // 3-array sidecar is UPGRADED when this build knows the layout
            // arrays. Without that, the framework-res.apk that wrote it would
            // keep reading "layout unknown" (so the view key keeps the aspect
            // bit) while every build writes its slice under the masked key:
            // a miss on every launch until the next restage.
            if (f.isFile()) {
                if (thr.length <= 3) return;   // the pre-fix rule (and all of `=0`)
                int[][] have = macapkReadFwThresholdsFile(f);
                if (have == null || have.length >= thr.length) return;
            }
            File tmp = new File(dir, f.getName() + ".tmp." + macapkFwPid());
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
                out.writeInt(MACAPK_FWVIEW_CACHE_MAGIC);
                for (int d = 0; d < thr.length; d++) {
                    out.writeInt(thr[d].length);
                    for (int v : thr[d]) out.writeInt(v);
                }
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable ignore) { /* best-effort */ }
    }

    private static boolean macapkFwViewDiskCacheEnabled() {
        return !"0".equals(System.getenv("MACAPK_FWVIEW_DISK_CACHE"));
    }

    private static File macapkFwViewCacheDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) home = System.getenv("HOME");
        if (home == null || home.isEmpty()) return null;
        return new File(home + "/Library/Caches/MacApk/fwview");
    }

    private static volatile boolean sFwViewDiskWarned;

    /** The caching wrapper the two build sites call. Order: process-lifetime
     *  in-memory (keyed by config), then the on-disk replay, then the real
     *  full parse (which then writes the disk slice). Returns null only when
     *  the uncached build returns null; the callers keep the hand tables. */
    private MacapkFwView macapkBuildFwView(ArscReader indexReader) {
        File fwApk = macapkFrameworkApkFile();
        File dir = macapkFwViewCacheDir();
        String idHash = (fwApk != null) ? macapkFwIdHash(fwApk) : null;
        boolean verify = "1".equals(System.getenv("MACAPK_FWVIEW_DISK_CACHE_VERIFY"));
        boolean diskOk = fwApk != null && dir != null && idHash != null
            && macapkFwViewDiskCacheEnabled();

        // Canonicalize the config key against the framework's own dp thresholds
        // when they are known (this process's earlier build, or the sidecar),
        // so a sub-threshold pixel jitter cannot spawn a spurious miss.
        int[][] thr = diskOk ? macapkFwThresholds(idHash, dir) : null;
        String cfgKey = macapkFwViewCfgKey(thr);

        MacapkFwView mem = macapkFwViewCache;
        if (mem != null && cfgKey.equals(macapkFwViewKey)) return mem;

        if (diskOk && !verify && thr != null) {
            try {
                long t0 = System.nanoTime();
                MacapkFwView disk = macapkLoadFwViewFromDisk(fwApk, dir, idHash, cfgKey, indexReader);
                if (disk != null) {
                    NativeLog.i(TAG, "framework resolution view: replayed "
                        + disk.styles.size() + " styles, " + disk.simple.size()
                        + " values from disk cache in "
                        + ((System.nanoTime() - t0) / 1_000_000) + "ms (cfg " + cfgKey + ")");
                    macapkFwViewCache = disk;
                    macapkFwViewKey = cfgKey;
                    return disk;
                }
            } catch (Throwable t) {
                if (!sFwViewDiskWarned) {
                    sFwViewDiskWarned = true;
                    NativeLog.w(TAG, "framework view disk replay failed (" + t
                        + "); building live");
                }
            }
        }

        macapkLastBuildThresholds = null;
        MacapkFwView built = macapkBuildFwViewUncached(indexReader);
        if (built != null) {
            // The build filled macapkLastBuildThresholds; recompute the key
            // against them (the lookup above may have used the raw key on the
            // first-ever launch) and persist under it, so the next launch hits.
            int[][] builtThr = macapkLastBuildThresholds;
            String canonKey = (builtThr != null) ? macapkFwViewCfgKey(builtThr) : cfgKey;
            macapkFwViewCache = built;
            macapkFwViewKey = canonKey;
            if (diskOk && !verify) {
                try {
                    macapkWriteFwThresholds(idHash, dir, builtThr);
                    macapkWriteFwViewToDisk(built, fwApk, dir, idHash, canonKey);
                } catch (Throwable t) {
                    if (!sFwViewDiskWarned) {
                        sFwViewDiskWarned = true;
                        NativeLog.w(TAG, "framework view disk write failed (" + t
                            + "); the live view is unaffected");
                    }
                }
            }
            if (verify && diskOk) macapkVerifyFwViewDisk(built, fwApk, dir, idHash, canonKey, indexReader);
        }
        return built;
    }

    /** The build identity of the staged framework-res.apk: path + size + mtime
     *  + FORMAT_VER, hashed. A dev rebuild re-stages the apk (mtime bumps) so a
     *  logic change to the builder invalidates the cache even without a
     *  FORMAT_VER bump. */
    private static String macapkFwIdHash(File fwApk) {
        return macapkFwViewHash(fwApk.getAbsolutePath() + "|" + fwApk.length() + "|"
            + fwApk.lastModified() + "|v" + MACAPK_FWVIEW_CACHE_FORMAT_VER);
    }

    /** ~/Library/Caches/MacApk/fwview/fw-<idHash>-<cfgHash>.fv. */
    private static File macapkFwViewDiskFile(File dir, String idHash, String cfgKey) {
        return new File(dir, "fw-" + idHash + "-" + macapkFwViewHash(cfgKey) + ".fv");
    }

    /** 16-hex FNV-1a of a key -- a filename token, not a security digest. */
    private static String macapkFwViewHash(String s) {
        long h = 0xcbf29ce484222325L;
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte x : b) { h ^= (x & 0xff); h *= 0x100000001b3L; }
        return String.format("%016x", h);
    }

    /** Replay a serialized slice into a live MacapkFwView, or null on any
     *  mismatch/corruption/miss. The header key is re-checked in full so a
     *  filename-hash collision cannot serve the wrong slice. */
    private MacapkFwView macapkLoadFwViewFromDisk(File fwApk, File dir, String idHash,
                                                  String cfgKey, ArscReader indexReader) throws Exception {
        File f = macapkFwViewDiskFile(dir, idHash, cfgKey);
        if (!f.isFile()) return null;
        String wantHeader = fwApk.getAbsolutePath() + "|" + fwApk.length() + "|"
            + fwApk.lastModified() + "|v" + MACAPK_FWVIEW_CACHE_FORMAT_VER + "|" + cfgKey;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16))) {
            if (in.readInt() != MACAPK_FWVIEW_CACHE_MAGIC) return null;
            if (in.readInt() != MACAPK_FWVIEW_CACHE_FORMAT_VER) return null;
            if (!wantHeader.equals(in.readUTF())) return null;   // stale/collision
            MacapkFwView v = new MacapkFwView(fwApk, indexReader.publicMap());
            for (Map.Entry<Integer, String> en : v.publicMap.entrySet()) {
                v.nameToId.put(en.getValue(), en.getKey());
            }
            int nStyles = in.readInt();
            for (int i = 0; i < nStyles; i++) {
                String name = in.readUTF();
                String parent = in.readBoolean() ? in.readUTF() : null;
                int nItems = in.readInt();
                Map<String, String> items = new HashMap<>();
                for (int j = 0; j < nItems; j++) {
                    String k = in.readUTF();
                    items.put(k, in.readUTF());
                }
                v.styles.put(name, new StyleDef(name, parent, items));
            }
            int nSimple = in.readInt();
            for (int i = 0; i < nSimple; i++) {
                int id = in.readInt();
                int dt = in.readInt();
                int data = in.readInt();
                String text = in.readBoolean() ? in.readUTF() : null;
                v.simple.put(id, new Object[] { dt, data, text });
            }
            int nEnum = in.readInt();
            for (int i = 0; i < nEnum; i++) {
                String an = in.readUTF();
                int nSym = in.readInt();
                Map<String, Integer> syms = new HashMap<>();
                for (int j = 0; j < nSym; j++) {
                    String sn = in.readUTF();
                    syms.put(sn, in.readInt());
                }
                v.attrEnum.put(an, syms);
            }
            int nFlag = in.readInt();
            for (int i = 0; i < nFlag; i++) v.attrFlagged.add(in.readUTF());
            int nOrigin = in.readInt();
            for (int i = 0; i < nOrigin; i++) {
                String k = in.readUTF();
                v.settledOrigin.put(k, in.readInt());
            }
            return v;
        }
    }

    /** Serialize the slice to a temp file and rename into place, so a
     *  concurrent launch reading the same path never sees a partial file.
     *  Best-effort: any failure leaves the live view untouched. */
    private void macapkWriteFwViewToDisk(MacapkFwView v, File fwApk, File dir,
                                         String idHash, String cfgKey) throws Exception {
        File f = macapkFwViewDiskFile(dir, idHash, cfgKey);
        if (!dir.isDirectory()) dir.mkdirs();
        String header = fwApk.getAbsolutePath() + "|" + fwApk.length() + "|"
            + fwApk.lastModified() + "|v" + MACAPK_FWVIEW_CACHE_FORMAT_VER + "|" + cfgKey;
        File tmp = new File(dir, f.getName() + ".tmp." + macapkFwPid());
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp), 1 << 16))) {
            out.writeInt(MACAPK_FWVIEW_CACHE_MAGIC);
            out.writeInt(MACAPK_FWVIEW_CACHE_FORMAT_VER);
            out.writeUTF(header);
            out.writeInt(v.styles.size());
            for (StyleDef sd : v.styles.values()) {
                out.writeUTF(sd.name);
                out.writeBoolean(sd.parent != null);
                if (sd.parent != null) out.writeUTF(sd.parent);
                out.writeInt(sd.items.size());
                for (Map.Entry<String, String> it : sd.items.entrySet()) {
                    out.writeUTF(it.getKey());
                    out.writeUTF(it.getValue());
                }
            }
            out.writeInt(v.simple.size());
            for (Map.Entry<Integer, Object[]> en : v.simple.entrySet()) {
                Object[] o = en.getValue();
                out.writeInt(en.getKey());
                out.writeInt((Integer) o[0]);
                out.writeInt((Integer) o[1]);
                String text = (String) o[2];
                out.writeBoolean(text != null);
                if (text != null) out.writeUTF(text);
            }
            out.writeInt(v.attrEnum.size());
            for (Map.Entry<String, Map<String, Integer>> en : v.attrEnum.entrySet()) {
                out.writeUTF(en.getKey());
                out.writeInt(en.getValue().size());
                for (Map.Entry<String, Integer> se : en.getValue().entrySet()) {
                    out.writeUTF(se.getKey());
                    out.writeInt(se.getValue());
                }
            }
            out.writeInt(v.attrFlagged.size());
            for (String s : v.attrFlagged) out.writeUTF(s);
            out.writeInt(v.settledOrigin.size());
            for (Map.Entry<String, Integer> en : v.settledOrigin.entrySet()) {
                out.writeUTF(en.getKey());
                out.writeInt(en.getValue());
            }
        } catch (Throwable t) {
            tmp.delete();
            throw t;
        }
        if (!tmp.renameTo(f)) {
            // Another launch won the race, or the FS refused rename; both are fine.
            tmp.delete();
        }
        macapkPruneFwViewCache(dir, idHash, f.getName());
    }

    /** Drop slices and sidecars from OTHER framework builds (a different id
     *  hash): a dev rebuild re-stages framework-res.apk, so its files become
     *  dead weight. Cheap and best-effort; never touches THIS build's files
     *  (slices `fw-<id>-<cfg>.fv` AND the threshold sidecar `fw-<id>.thr`). */
    private static void macapkPruneFwViewCache(File dir, String keepIdHash, String keepName) {
        try {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            String keepSlices  = "fw-" + keepIdHash + "-";     // this build's slices
            String keepSidecar = "fw-" + keepIdHash + ".";     // this build's .thr (and .thr.tmp)
            for (File k : kids) {
                String n = k.getName();
                if (!n.startsWith("fw-")) continue;
                if (n.startsWith(keepSlices) || n.startsWith(keepSidecar)) continue;
                if (n.equals(keepName)) continue;
                k.delete();
            }
        } catch (Throwable ignore) { /* best-effort */ }
    }

    private static int macapkFwPid() {
        try { return (int) ProcessHandle.current().pid(); }
        catch (Throwable t) { return (int) (System.nanoTime() & 0x7fffffff); }
    }

    /** MACAPK_FWVIEW_DISK_CACHE_VERIFY=1: write, replay, and compare every
     *  field against the live build -- the positive control that the replayed
     *  object equals the built one. Logs PASS or the first divergence. */
    private void macapkVerifyFwViewDisk(MacapkFwView built, File fwApk, File dir,
                                        String idHash, String cfgKey, ArscReader indexReader) {
        try {
            macapkWriteFwViewToDisk(built, fwApk, dir, idHash, cfgKey);
            MacapkFwView disk = macapkLoadFwViewFromDisk(fwApk, dir, idHash, cfgKey, indexReader);
            if (disk == null) { NativeLog.e(TAG, "fwview verify: replay returned null"); return; }
            String d = macapkFwViewDiff(built, disk);
            if (d == null) {
                NativeLog.i(TAG, "fwview verify: PASS (" + built.styles.size()
                    + " styles, " + built.simple.size() + " values round-trip identical)");
            } else {
                NativeLog.e(TAG, "fwview verify: MISMATCH -- " + d);
            }
        } catch (Throwable t) {
            NativeLog.e(TAG, "fwview verify threw: " + t);
        }
    }

    /** First structural difference between two views, or null if identical
     *  (publicMap/nameToId/cslCache excluded -- reconstructed, not serialized). */
    private static String macapkFwViewDiff(MacapkFwView a, MacapkFwView b) {
        if (a.styles.size() != b.styles.size())
            return "style count " + a.styles.size() + " vs " + b.styles.size();
        for (Map.Entry<String, StyleDef> en : a.styles.entrySet()) {
            StyleDef sb = b.styles.get(en.getKey());
            StyleDef sa = en.getValue();
            if (sb == null) return "style missing: " + en.getKey();
            if (!java.util.Objects.equals(sa.parent, sb.parent))
                return "style " + en.getKey() + " parent " + sa.parent + " vs " + sb.parent;
            if (!sa.items.equals(sb.items)) return "style " + en.getKey() + " items differ";
        }
        if (a.simple.size() != b.simple.size())
            return "simple count " + a.simple.size() + " vs " + b.simple.size();
        for (Map.Entry<Integer, Object[]> en : a.simple.entrySet()) {
            Object[] ob = b.simple.get(en.getKey());
            Object[] oa = en.getValue();
            if (ob == null) return "simple missing: 0x" + Integer.toHexString(en.getKey());
            if (!oa[0].equals(ob[0]) || !oa[1].equals(ob[1])
                    || !java.util.Objects.equals(oa[2], ob[2]))
                return "simple 0x" + Integer.toHexString(en.getKey()) + " differs";
        }
        if (!a.attrEnum.equals(b.attrEnum)) return "attrEnum differs";
        if (!a.attrFlagged.equals(b.attrFlagged)) return "attrFlagged differs";
        if (!a.settledOrigin.equals(b.settledOrigin)) return "settledOrigin differs";
        return null;
    }

    /** Build the resolution view: full-parse the framework table, keep the
     *  config-selected slice. Returns null on ANY failure; the caller keeps
     *  the hand tables, never the text fallback (this view is additive). */
    private MacapkFwView macapkBuildFwViewUncached(ArscReader indexReader) {
        try {
            File apk = macapkFrameworkApkFile();
            if (apk == null) return null;
            long t0 = System.nanoTime();
            byte[] arsc;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
                java.util.zip.ZipEntry e = zf.getEntry("resources.arsc");
                if (e == null) return null;
                try (InputStream in = zf.getInputStream(e)) {
                    arsc = in.readAllBytes();
                }
            }
            // Full parse with NO injected maps: the framework table renders
            // its own references and its own attr enum/flag symbol names.
            ArscReader full = ArscReader.parse(arsc);
            MacapkFwView v = new MacapkFwView(apk, indexReader.publicMap());
            for (Map.Entry<Integer, String> en : v.publicMap.entrySet()) {
                v.nameToId.put(en.getValue(), en.getKey());
            }
            java.util.LinkedHashMap<String, List<ArscReader.Row>> byCfg =
                new java.util.LinkedHashMap<>();
            for (ArscReader.Row row : full.rows()) {
                byCfg.computeIfAbsent(row.config, k -> new ArrayList<>()).add(row);
            }
            // The framework's own -w/-h/-sw dp thresholds, captured for the
            // replay cache key (see macapkFwThresholdsFromConfigs), plus under
            // MACAPK_RES_SIZECLASS the size classes / aspect it ships
            // (macapkFwBucketsFromConfigs). Stashed for the wrapper to name the
            // write file and the sidecar.
            macapkLastBuildThresholds = macapkFwBucketsFromConfigs(byCfg.keySet());
            List<String> selected = new ArrayList<>();
            final Map<String, Integer> score = new HashMap<>();
            for (String cfg : byCfg.keySet()) {
                int s = macapkFwCfgScore(cfg);
                if (s < 0) continue;
                score.put(cfg, s);
                selected.add(cfg);
            }
            selected.sort(Comparator.comparingInt(score::get));
            // Pass 1: simple values (ascending, last-put-wins, the same
            // override contract macapkIngestRows implements).
            for (String cfg : selected) {
                for (ArscReader.Row row : byCfg.get(cfg)) {
                    if (!row.complex) {
                        v.simple.put(row.resId,
                            new Object[] { row.dataType, row.data, row.value });
                    }
                }
            }
            // Pass 1.6: framework <attr> enum/flag symbol tables. ArscReader
            // renders style-item enum ints back to their SOURCE names using
            // these same bags, so the reverse map must exist for the resolver
            // to compile "center" back to TYPE_INT_HEX 0x11 the way aapt did.
            // ATTR_TYPE internal slot: nameRef 0x01000000, data = format mask
            // (ResourceTypes.h ResTable_map: TYPE_ENUM 1<<16, TYPE_FLAGS 1<<17).
            for (String cfg : selected) {
                for (ArscReader.Row row : byCfg.get(cfg)) {
                    if (!row.complex || !"attr".equals(row.type)) continue;
                    if (v.attrEnum.containsKey(row.name)) continue;
                    int format = 0;
                    Map<String, Integer> syms = null;
                    for (ArscReader.MapItem it : row.items) {
                        if (it.nameRef == 0x01000000) { format = it.data; continue; }
                        if ((it.nameRef & 0xffff0000) != 0
                                && (it.nameRef & 0x00ff0000) == 0) continue;
                        String sn = it.name;
                        if (sn == null || sn.isEmpty()) continue;
                        int sl = sn.indexOf('/');
                        if (sl >= 0) sn = sn.substring(sl + 1);
                        if (syms == null) syms = new HashMap<>();
                        syms.put(sn, it.data);
                    }
                    if (syms == null || (format & 0x00030000) == 0) continue;
                    v.attrEnum.put(row.name, syms);
                    if ((format & 0x00020000) != 0) v.attrFlagged.add(row.name);
                }
            }
            // Pass 2: styles, AFTER simple so scalar-leaf settling in the
            // item renderer sees the final selected values.
            for (String cfg : selected) {
                for (ArscReader.Row row : byCfg.get(cfg)) {
                    if (row.complex && "style".equals(row.type)) {
                        v.styles.put(row.name, macapkFwStyleDef(v, row));
                    }
                }
            }
            NativeLog.i(TAG, "framework resolution view: " + v.styles.size()
                + " styles, " + v.simple.size() + " values, "
                + selected.size() + "/" + byCfg.size() + " configs, "
                + ((System.nanoTime() - t0) / 1_000_000) + "ms from "
                + apk.getName());
            return v;
        } catch (Throwable t) {
            NativeLog.w(TAG, "framework resolution view build failed (" + t
                + "); hand tables remain");
            return null;
        }
    }

    /** One framework style row -> a StyleDef the existing walk understands.
     *  The parent keeps its @android:style/ spelling so the chain can never
     *  collide with an app style of the same bare name (on a device the
     *  parent is an id and cannot cross into the app package). */
    private StyleDef macapkFwStyleDef(MacapkFwView v, ArscReader.Row row) {
        String parent = null;
        if (row.parentRef != 0) {
            String tn = v.publicMap.get(row.parentRef);
            parent = (tn != null && tn.startsWith("style/"))
                ? "@android:style/" + tn.substring(6)
                : String.format("@0x%08x", row.parentRef);
        }
        Map<String, String> items = new HashMap<>();
        int[] origin = new int[1];
        for (ArscReader.MapItem it : row.items) {
            String k = macapkStyleItemKey(it.name);
            if (k != null) {
                origin[0] = 0;
                items.put(k, macapkFwItemText(v, it.dataType, it.data, it.value, 0, origin));
                if (origin[0] != 0) {
                    v.settledOrigin.put(row.name + " " + k, origin[0]);
                }
            }
        }
        return new StyleDef(row.name, parent, items);
    }

    /** Render one framework Res_value for APP-SIDE consumption. ArscReader
     *  renders in-table references bare ("@style/X", "?attr"), which app-side
     *  parsers would resolve against the APP table, so the framework
     *  namespace must be restored. The reference TARGET TYPE picks the
     *  spelling, each matched to what the existing consumers can use:
     *  - color/string  -> "@android:type/name". resolveColorRef's gate branch
     *    resolves the colour form against this same view.
     *  - dimen/bool/integer/fraction -> settled to the LEAF LITERAL
     *    ("56.0dip", "false"). A scalar's literal IS its resolved value
     *    (AOSP's own file contains it) and every dimension parser in the
     *    tree rejects the @android:dimen spelling outright; this is the
     *    actionBarSize precedent (v1.0.858) generalized.
     *  - everything else (style/drawable/anim/...) -> "@0x%08x", the SAME
     *    spelling BinaryXmlParser already emits for binary-layout refs, so
     *    every consumer that handles compiled XML handles it: it round-trips
     *    through Resources.macapkResRefToId to a real id where the
     *    "@android:type/name" spelling hard-stops at 0 there.
     *  - attribute refs -> "?android:name"; the theme walk settles them. */
    private String macapkFwItemText(MacapkFwView v, int dataType, int data,
                                    String rendered, int depth) {
        return macapkFwItemText(v, dataType, data, rendered, depth, null);
    }

    /** `originOut`, when non-null, receives the id of the LAST reference
     *  this renderer followed while SETTLING a scalar to its leaf literal
     *  (untouched otherwise). That is AOSP ResolveReference's
     *  out_last_reference for exactly the hops the settle consumes; the
     *  spellings that keep their reference in the string (@android:type/name,
     *  @0x) leave it for the resolver to derive from the string itself. */
    private String macapkFwItemText(MacapkFwView v, int dataType, int data,
                                    String rendered, int depth, int[] originOut) {
        switch (dataType) {
            case ArscReader.TYPE_ATTRIBUTE:
            case ArscReader.TYPE_DYNAMIC_ATTRIBUTE: {
                String tn = v.publicMap.get(data);
                if (tn != null && tn.startsWith("attr/")) {
                    return "?android:" + tn.substring(5);
                }
                return String.format("?0x%08x", data);
            }
            case ArscReader.TYPE_REFERENCE:
            case ArscReader.TYPE_DYNAMIC_REFERENCE: {
                if (data == 0) return "@null";
                String tn = v.publicMap.get(data);
                if (tn == null) return String.format("@0x%08x", data);
                int slash = tn.indexOf('/');
                String type = slash > 0 ? tn.substring(0, slash) : "";
                switch (type) {
                    case "color":
                    case "string":
                        return "@android:" + tn;
                    case "dimen":
                    case "bool":
                    case "integer":
                    case "fraction": {
                        if (depth < 20) {
                            Object[] leaf = v.simple.get(data);
                            if (leaf != null) {
                                // Record BEFORE recursing: a deeper follow
                                // overwrites, leaving the LAST followed id,
                                // matching ResolveReference's per-iteration
                                // `*out_last_reference = value->data`.
                                if (originOut != null) originOut[0] = data;
                                return macapkFwItemText(v, (Integer) leaf[0],
                                    (Integer) leaf[1], (String) leaf[2],
                                    depth + 1, originOut);
                            }
                        }
                        return "@android:" + tn;
                    }
                    default:
                        // ⚠ v1.0.1104 FIX. This used to `return String.format("@0x%08x", data)`
                        // for every remaining type -- style/ above all -- THROWING AWAY the name
                        // it already holds in `tn`. Nothing downstream can consume a raw id: our
                        // resolvers, the style-chain walk and the theme cascade are all NAME-based,
                        // which is why the hand-transcribed table spells these `@android:style/X`.
                        //
                        // MEASURED consequence (deskclock, MACAPK_FW_VIEW=1): AOSP's
                        // `@android:style/Widget` is `{textAppearance=?android:textAppearance}`, and
                        // `?android:textAppearance` resolved to `@0x010301ed` instead of
                        // `@android:style/TextAppearance.Material`. Material's ThemeEnforcement then
                        // read no usable resource id and threw "This component requires that you
                        // specify a valid TextAppearance attribute", the inflater fell back to a
                        // FrameLayout, the app's cast threw ClassCastException, onCreate aborted and
                        // the whole activity rendered nothing -- deskclock 97.27% -> 0.00% on the
                        // fleet, newpipe 76.69% -> 6.76%.
                        //
                        // `tn` is non-null here by construction (the null case returned above), so
                        // the name is always available; the hex form now survives only as the
                        // unreachable-by-construction fallback. Spelling a reference we cannot
                        // satisfy as `@android:drawable/foo` rather than `@0x...` is no worse -- it
                        // is the same dangling reference, in the spelling the rest of the tree reads.
                        return "@android:" + tn;
                }
            }
            default:
                return rendered;   // literals arrive final from the reader
        }
    }

    /** The config-selected SIMPLE value of a framework id under the gate:
     *  {Integer dataType, Integer data, String text, Integer leafId}, with
     *  in-table REFERENCE chains chased (cap 20, AOSP kMaxIterations) and
     *  each followed id published to the last-reference channel exactly as
     *  AssetManager2::ResolveReference does per iteration. Returns null when
     *  the view is absent, the id is unknown, the entry is complex (a bag:
     *  style/array/plurals/attr), or the leaf is an ATTRIBUTE (the caller's
     *  theme machinery owns ?attr hops). dataType uses Res_value numbering,
     *  which TypedValue shares for every type surfaced here. */
    public Object[] macapkFwValueOf(int id) {
        MacapkFwView v = macapkFwView;
        if (v == null) return null;
        int cur = id;
        for (int hop = 0; hop < 20; hop++) {
            Object[] e = v.simple.get(cur);
            if (e == null) return null;
            int dt = (Integer) e[0];
            if (dt == ArscReader.TYPE_REFERENCE
                    || dt == ArscReader.TYPE_DYNAMIC_REFERENCE) {
                int next = (Integer) e[1];
                if (next == 0) return null;
                macapkPublishValueOrigin(next);
                cur = next;
                continue;
            }
            if (dt == ArscReader.TYPE_ATTRIBUTE
                    || dt == ArscReader.TYPE_DYNAMIC_ATTRIBUTE) return null;
            return new Object[] { dt, e[1], e[2], cur };
        }
        return null;
    }

    /** Enum/flag compilation for a FRAMEWORK attr under the gate: the packed
     *  int for `value` ("center", "bold|italic") per the platform table's own
     *  <attr> bag, plus the attr's flag-ness (aapt types flag assignments
     *  TYPE_INT_HEX even for a single token, enums TYPE_INT_DEC). Returns
     *  {value, isFlags ? 1 : 0} or null. Mirrors resolveAttrEnumValue's
     *  all-or-nothing '|' handling. */
    public int[] macapkFwAttrEnumTyped(String attrName, String value) {
        MacapkFwView v = macapkFwView;
        if (v == null || attrName == null || value == null) return null;
        String an = attrName;
        int slash = an.lastIndexOf('/');
        if (slash >= 0) an = an.substring(slash + 1);
        int colon = an.lastIndexOf(':');
        if (colon >= 0) an = an.substring(colon + 1);
        Map<String, Integer> syms = v.attrEnum.get(an);
        if (syms == null) return null;
        boolean flags = v.attrFlagged.contains(an);
        String val = value.trim();
        if (val.isEmpty()) return null;
        if (val.indexOf('|') < 0) {
            Integer one = syms.get(val);
            return one == null ? null : new int[] { one, flags ? 1 : 0 };
        }
        if (!flags) return null;
        int acc = 0;
        for (String part : val.split("\\|")) {
            Integer pv = syms.get(part.trim());
            if (pv == null) return null;
            acc |= pv;
        }
        return new int[] { acc, 1 };
    }

    private static String macapkFwNormalizeStyleName(String rawName) {
        String n = rawName;
        if (n.startsWith("@android:style/"))  n = n.substring(15);
        else if (n.startsWith("@style/"))     n = n.substring(7);
        else if (n.startsWith("android:"))    n = n.substring(8);
        return n;
    }

    private boolean macapkFwHasStyle(String rawName) {
        MacapkFwView v = macapkFwView;
        return v != null && v.styles.containsKey(macapkFwNormalizeStyleName(rawName));
    }

    /** Re-entrancy budget for ?attr hop settling. Theme::GetAttribute
     *  (AssetManager2.cpp, android11-release) caps attribute indirection at
     *  20; hop settling below re-enters the full 3-pass resolve, so the cap
     *  must be process-wide per thread rather than per call to terminate a
     *  mutual a->b->a cycle. */
    private static final ThreadLocal<int[]> MACAPK_FW_HOP_DEPTH =
        ThreadLocal.withInitial(() -> new int[1]);

    /** (root, hop) settles IN PROGRESS on this thread. Re-entering one is a
     *  cycle: the inner query can only replay the recursion it is already part
     *  of until the depth cap answers null, so answer null at once.
     *
     *  WHY (measured 2026-09-22). Platform items that point at THEMSELVES --
     *  TextAppearance.Material textColorHint=?textColorHint and
     *  textColorLink=?textColorLink, Widget.CompoundButton
     *  textAppearance=?textAppearance; 30 such items in framework-res -- read
     *  through a STYLE root re-entered this loop until the 20-deep cap, and
     *  two passes per level both hop, so one lookup cost ~2^20 resolves.
     *  debug/levelprobe.sh on Aegis's TextAppearance.Material3.LabelLarge:
     *  textColor alone 0.93 s, plus textColorHint and textColorLink 4.86 s.
     *  In a running app every Material Chip reads both through a TypedArray
     *  (hasValue + get), which held Aegis's LAUNCH_ACTIVITY for 6.6 s.
     *  AOSP Theme::GetAttribute (AssetManager2.cpp) walks such a chain
     *  iteratively with a 20-step budget, i.e. linearly. Answers are
     *  unchanged: this resolver returns the first value any pass finds, so a
     *  re-entered query's answer cannot differ from the one being computed,
     *  and the depth-0 dead end below still hands back the unsettled raw. */
    private static final ThreadLocal<java.util.Set<String>> MACAPK_FW_HOP_ACTIVE =
        ThreadLocal.withInitial(java.util.HashSet::new);

    /** Resolve `attrName` through the REAL framework style/theme chain.
     *  Returns null when the table lacks the name (caller falls back to the
     *  hand tables) or genuinely carries no such attribute (caller's pass
     *  machinery continues).
     *
     *  Pass discipline mirrors the hand tables exactly: a framework THEME
     *  (Theme / Theme.*) answers only when allowFrameworkTheme (pass 3), so
     *  an app's own bare -> android: fallback keeps its precedence; widget
     *  and TextAppearance styles answer in any pass, as
     *  frameworkStyleAttr's rows always did.
     *
     *  TWIN-SWAP RETIRED (v1.0.920). The listChoiceIndicator{Single,
     *  Multiple} theme rows used to answer AppCompat's app-bundled
     *  abc_btn_{radio,check}_material here, because the table's own
     *  values name framework drawables (btn_radio_material_anim and
     *  friends) this runtime could not open or inflate. Framework
     *  drawable loading now exists (openRaw's framework branch + the
     *  compiled-XML drawable path in Resources), so the table answers
     *  honestly and a non-AppCompat app resolves the REAL asset instead
     *  of a dangling app-namespace name (the fwprobe
     *  OSA.fwstyle.WidgetMaterialCheckBox.android_button case: has=1
     *  got=0 because @drawable/abc_btn_check_material has no id in an
     *  app that bundles no AppCompat).
     *
     *  The overflow-src row below is the LAST remaining swap of this
     *  shape. It stays because its consumer is AppCompat's
     *  OverflowMenuButton src path (LayoutInflater/TypedArray tint
     *  machinery, a concurrent lane's surface at retirement time), and
     *  its honest value has not been render-verified on a real toolbar.
     *  Removal condition: a gate-ON AppCompat toolbar renders the 3-dot
     *  icon from the table's own @0x reference. */
    private String macapkFwStyleAttr(String styleName, String attrName,
                                     boolean allowFrameworkTheme,
                                     String rootTheme) {
        MacapkFwView v = macapkFwView;
        if (v == null) return null;
        String n = macapkFwNormalizeStyleName(styleName);
        String a = attrName;
        if (a.startsWith("android:attr/"))   a = a.substring(13);
        else if (a.startsWith("android:"))   a = a.substring(8);
        boolean isTheme = n.equals("Theme") || n.startsWith("Theme.");
        if (isTheme && !allowFrameworkTheme) return null;
        if ("src".equals(a)
                && (n.equals("Widget.Material.ActionButton.Overflow")
                    || n.equals("Widget.Material.Light.ActionButton.Overflow")
                    || n.equals("Widget.ActionButton.Overflow"))) {
            return "@drawable/abc_ic_menu_overflow_material";
        }
        StyleDef d = v.styles.get(n);
        if (d == null) return null;
        String val = null;
        String owner = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (d != null && seen.add(d.name)) {
            String got = d.items.get(a);
            if (got != null) { val = got; owner = d.name; break; }
            if (d.parent == null) break;
            d = v.styles.get(macapkFwNormalizeStyleName(d.parent));
        }
        if (val == null) return null;
        // Re-publish the settle-time last-reference for this item (baked by
        // macapkFwStyleDef). A '?' value is not settled and takes the hop
        // loop below instead, where the re-entrant resolveAttrInTheme call
        // owns the slot; a literal that never was a reference has no baked
        // row and correctly leaves the slot at resolveAttrInTheme's
        // entry-clear 0 (AOSP: a literal answers resourceId 0).
        if (owner != null && !val.startsWith("?")) {
            Integer so = v.settledOrigin.get(owner + " " + a);
            if (so != null) macapkPublishValueOrigin(so);
        }
        // Attribute indirections settle INSIDE the theme, restarting at the
        // ROOT so an app override higher in the cascade wins: that is what
        // the merged-bag theme gives on a device (Theme::GetAttribute).
        int[] depth = MACAPK_FW_HOP_DEPTH.get();
        java.util.Set<String> active = MACAPK_FW_HOP_ACTIVE.get();
        final String hopRoot = rootTheme != null ? rootTheme : styleName;
        String preHop = val;
        for (int i = 0; i < 20 && val != null && val.startsWith("?"); ++i) {
            if (depth[0] >= 20) return null;
            String hop = val.startsWith("?android:attr/") ? "android:" + val.substring(14)
                       : val.startsWith("?android:")      ? "android:" + val.substring(9)
                       : val.startsWith("?attr/")         ? val.substring(6)
                       : val.substring(1);
            final String key = hopRoot + '\u0001' + hop;
            if (!active.add(key)) { val = null; break; }   // re-entry = cycle (see above)
            ++depth[0];
            try {
                val = resolveAttrInTheme(hopRoot, hop);
            } finally {
                --depth[0];
                active.remove(key);
            }
        }
        // A dead-ended hop returns the UNSETTLED raw, not null. When this
        // query serves a WIDGET STYLE's bag (rootTheme == the style itself,
        // the OSA fetch shape), a ?attr item names a THEME attribute the
        // style chain legitimately lacks; AOSP resolves it against the
        // INVOKING theme (AttributeResolution.cpp:310 entry.value ->
        // theme->ResolveAttributeReference), which our callers thread into
        // TypedArray/the resolver. Handing the raw back lets that theme do
        // exactly that; every existing consumer either resolves a leading
        // '?' itself or stops on no-progress (the resolver's ?-loop breaks
        // when a hop answers the same raw), so a theme-side dead end stays
        // an absent attribute as before.
        // Depth-0 ONLY: inside another query's hop (depth > 0) a dead end
        // must stay null or the settle machinery re-hops the unsettled raw
        // it just failed to settle, exponentially (measured: the offline
        // replay spun for minutes at 20^k re-entries before this gate).
        if (val == null && depth[0] == 0 && preHop.startsWith("?")) return preHop;
        return val;
    }

    /** "@android:color/<name>" through the platform table: typed colour rows
     *  answer directly, references chase, ?attr rows go through the
     *  application theme (the colorRefs contract), and file-backed selectors
     *  flatten to their AOSP default via the compiled-XML reader. */
    private Integer macapkFwColorByName(String name, java.util.Set<String> visited) {
        MacapkFwView v = macapkFwView;
        if (v == null || name == null || name.isEmpty()) return null;
        Integer id = v.nameToId.get("color/" + name);
        if (id == null) return null;
        // v1.0.917: entering the framework table by NAME is the resolver
        // following "@android:color/name", one ResolveReference iteration;
        // publish it (further in-table refs publish deeper below).
        macapkPublishValueOrigin(id);
        return macapkFwColorById(id, visited, 0);
    }

    private Integer macapkFwColorById(int id, java.util.Set<String> visited, int depth) {
        MacapkFwView v = macapkFwView;
        if (v == null || depth > 20) return null;
        Object[] e = v.simple.get(id);
        if (e == null) return null;
        int dt = (Integer) e[0];
        int data = (Integer) e[1];
        switch (dt) {
            case ArscReader.TYPE_COLOR_ARGB8:
            case ArscReader.TYPE_COLOR_RGB8:
            case ArscReader.TYPE_COLOR_ARGB4:
            case ArscReader.TYPE_COLOR_RGB4:
            case ArscReader.TYPE_INT_DEC:
            case ArscReader.TYPE_INT_HEX:
                // Res_value.data is the fully-expanded packed ARGB (aapt2
                // fills alpha and doubles nibbles at compile time).
                return data;
            case ArscReader.TYPE_REFERENCE:
            case ArscReader.TYPE_DYNAMIC_REFERENCE:
                // v1.0.917: per-iteration out_last_reference, as in AOSP's
                // ResolveReference loop.
                macapkPublishValueOrigin(data);
                return macapkFwColorById(data, visited, depth + 1);
            case ArscReader.TYPE_ATTRIBUTE:
            case ArscReader.TYPE_DYNAMIC_ATTRIBUTE: {
                String tn = v.publicMap.get(data);
                if (tn == null || !tn.startsWith("attr/")) return null;
                if (applicationThemeName == null) return null;
                String raw = resolveAttrInTheme(applicationThemeName,
                    "android:" + tn.substring(5));
                return raw == null ? null : resolveColorRef(raw, visited);
            }
            case ArscReader.TYPE_STRING: {
                String path = (String) e[2];
                if (path != null && path.startsWith("res/") && path.endsWith(".xml")) {
                    return macapkFwCslDefault(path, visited);
                }
                return null;
            }
            default:
                return null;
        }
    }

    /** Default colour of a COMPILED framework ColorStateList (a res/color/
     *  entry inside the framework apk). Mirrors colorFromSelectorFile's AOSP
     *  default rule exactly: seed from the FIRST item, let any later
     *  STATELESS item override, skip rows whose colour needs a Theme, and
     *  give each row a FRESH visited set seeded from the outer one (the
     *  sibling-rows-name-the-same-colour trap, see colorFromSelectorFile). */
    private Integer macapkFwCslDefault(String path, java.util.Set<String> visited) {
        MacapkFwView v = macapkFwView;
        if (v == null) return null;
        if (!visited.add("fwcsl/" + path)) return null;
        if (v.cslCache.containsKey(path)) return v.cslCache.get(path);
        Integer answer = null;
        try {
            byte[] xml;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(v.apk)) {
                java.util.zip.ZipEntry e = zf.getEntry(path);
                if (e == null) { v.cslCache.put(path, null); return null; }
                try (InputStream in = zf.getInputStream(e)) {
                    xml = in.readAllBytes();
                }
            }
            org.w3c.dom.Document doc = BinaryXmlParser.parse(xml);
            NodeList items = doc.getElementsByTagName("item");
            Integer chosen = null;
            for (int i = 0; i < items.getLength(); ++i) {
                Element el = (Element) items.item(i);
                String colorAttr = el.getAttribute("android:color");
                if (colorAttr == null || colorAttr.isEmpty()) continue;
                colorAttr = colorAttr.trim();
                Integer c = null;
                if (colorAttr.startsWith("#")) {
                    c = parseHexColor(colorAttr);
                } else if (colorAttr.startsWith("@0x")) {
                    // BinaryXmlParser renders references as raw ids.
                    try {
                        c = macapkFwColorById(
                            (int) Long.parseLong(colorAttr.substring(3), 16),
                            new java.util.HashSet<>(visited), 0);
                    } catch (NumberFormatException ignored) {}
                } else if (colorAttr.startsWith("?0x")) {
                    // A theme-dependent row. The platform's CORE text colours
                    // are built this way (text_color_primary.xml's every row
                    // is ?textColorPrimaryNoDisable), so skipping it, as the
                    // text-path colorFromSelectorFile does, would gut them
                    // and fall through to the appearance-based fallback rows.
                    // Resolve through the application theme, the SAME
                    // convention resolveColorRef's own "?" branch uses. The
                    // outer visited set breaks a selector that loops back to
                    // its own colour name.
                    try {
                        int attrId = (int) Long.parseLong(colorAttr.substring(3), 16);
                        MacapkFwView vv = macapkFwView;
                        String tn = vv == null ? null : vv.publicMap.get(attrId);
                        if (tn != null && tn.startsWith("attr/")
                                && applicationThemeName != null) {
                            String raw = resolveAttrInTheme(applicationThemeName,
                                "android:" + tn.substring(5));
                            if (raw != null) {
                                c = resolveColorRef(raw,
                                    new java.util.HashSet<>(visited));
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                } else if (colorAttr.startsWith("?")) {
                    continue;   // a named ?attr form never appears in compiled XML
                }
                if (c == null) continue;
                String alphaAttr = el.getAttribute("android:alpha");
                // BISECT GATE MACAPK_FWCSL_THEME_ALPHA (2026-09-26, default ON;
                // docs/fleet-newpipe-vlc-residual_2026-09-26.md). A THEME-ATTRIBUTE
                // alpha row ("?0x01010552" = ?attr/primaryContentAlpha) was skipped
                // below, so the row kept the colour's own alpha. The platform's core
                // text colours are built exactly that way (text_color_primary.xml:
                // color ?colorForeground, alpha ?primaryContentAlpha; 22 of the 111
                // framework res/color selectors carry such a row), so every Material
                // light app's ?android:textColorPrimary / Secondary came out OPAQUE
                // black where a device paints #de000000 -- NewPipe's dialog text read
                // #000000 against the emulator's #212121. AOSP ColorStateList.inflate
                // reads the row through a THEMED TypedArray (obtainAttributes(r,
                // theme, attrs, R.styleable.ColorStateListItem)) and applies
                // getFloat(ColorStateListItem_alpha, 1.0f), so the theme attribute IS
                // resolved. Resolve it through the application theme, the same
                // convention the ?0x colour branch above uses; an alpha that does not
                // resolve is skipped exactly as before. =0 restores the skip.
                if (MACAPK_FWCSL_THEME_ALPHA && alphaAttr != null && alphaAttr.startsWith("?0x")) {
                    alphaAttr = macapkFwThemeAlpha(alphaAttr);
                }
                if (alphaAttr != null && !alphaAttr.isEmpty()
                        && !alphaAttr.startsWith("?")) {
                    Float mod = null;
                    try {
                        if (alphaAttr.startsWith("@0x")) {
                            Object[] fe = v.simple.get(
                                (int) Long.parseLong(alphaAttr.substring(3), 16));
                            if (fe != null
                                    && (Integer) fe[0] == ArscReader.TYPE_FLOAT) {
                                mod = Float.intBitsToFloat((Integer) fe[1]);
                            }
                        } else {
                            mod = Float.parseFloat(alphaAttr.trim());
                        }
                    } catch (Throwable ignored) {}
                    if (mod != null) {
                        int al = Math.round(((c >>> 24) & 0xFF) * mod);
                        if (al < 0) al = 0; else if (al > 255) al = 255;
                        c = (al << 24) | (c & 0x00FFFFFF);
                    }
                }
                boolean hasState = false;
                org.w3c.dom.NamedNodeMap at = el.getAttributes();
                for (int j = 0; at != null && j < at.getLength(); ++j) {
                    String an = at.item(j).getNodeName();
                    // macapkType:* annotations mirror real attr names, so the
                    // state_ substring test stays a faithful signal.
                    if (an.contains("state_")) { hasState = true; break; }
                }
                if (chosen == null || !hasState) chosen = c;
            }
            answer = chosen;
        } catch (Throwable ignored) {
            answer = null;
        }
        v.cslCache.put(path, answer);
        return answer;
    }

    /** Wipe every map the ingest may have partially filled, so the text
     *  fallback starts from the same empty state a fresh instance would. */
    /** Every FILE row of every member (a non-complex TYPE_STRING entry whose
     *  value names a zip entry under res/), config-selected: ascending
     *  file-config score, later members first on ties, last-put-wins.
     *  Returns the number of ids that got a file. */
    /** Cheap shape test for a packed-resource token: a known blob letter, then
     *  two hex fields, pipe-separated ("L|47C1C0|8B8|9047"). Runs on every
     *  non-res/ string row of every app, so it must stay allocation-free and
     *  bail on the first byte that does not fit. */
    private static boolean macapkLooksLikePackedToken(String v) {
        if (v == null || v.length() < 6) return false;
        if (v.charAt(1) != '|') return false;
        if (!MACAPK_PACKED_BLOBS.containsKey(v.charAt(0))) return false;
        int bars = 0;
        for (int i = 2; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '|') { bars++; if (bars >= 2) return true; continue; }
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return false;   // needs at least offset|length|
    }

    private int macapkIngestFileRows(List<ArscReader> tables) {
        fileRowById.clear();
        final Map<String, Integer> cfgScore = new HashMap<>();
        List<Object[]> ordered = new ArrayList<>();   // {score, -memberIndex, row}
        for (int mi = 0; mi < tables.size(); mi++) {
            for (ArscReader.Row row : tables.get(mi).rows()) {
                if (row.complex || row.dataType != ArscReader.TYPE_STRING) continue;
                if (row.value == null) continue;
                // A file-backed entry's value is normally the compiled path
                // ("res/layout/x.xml"). It is NOT always: an app may pack its
                // resources itself and rewrite the table entry to a PRIVATE token,
                // then decode that token in its own Resources subclass. Instagram
                // 445 does exactly this -- 5,947 of its 6,017 layout entries read
                // like "L|47C1C0|8B8|9047" (an offset/length into its own blob) and
                // only 70 are real res/ paths -- and its Resources.getLayout()
                // override decodes them (which is also why it reflects XmlBlock).
                //
                // Dropping those rows made getValue() report nothing for the id, so
                // the app's own decoder had nothing to decode and fell through to
                // super, where openRawResource legitimately missed: setContentView
                // then threw and InstagramMainActivity finished itself.
                //
                // So: keep a non-res/ string when the resource TYPE is one that is
                // always file-backed. A device reports the table's string verbatim;
                // this does the same. Types whose STRING value is real content
                // (string/plurals/array/...) are NOT in the set, so a plain
                // <string> can never be mistaken for a file.
                // Resource optimizers also shorten real ZIP paths (for example
                // r/mht.xml). Accept those only for file-backed resource types
                // and only when the owning APK contains the exact file. A plain
                // string whose text happens to match a ZIP entry stays a value.
                if (!row.value.startsWith("res/")
                        && !macapkLooksLikePackedToken(row.value)) {
                    if (!FILE_ALIAS_TYPES.contains(row.type)
                            && !"color".equals(row.type)
                            && !"navigation".equals(row.type)) continue;
                    try {
                        java.util.zip.ZipEntry file = macapkMemberZip(mi).getEntry(row.value);
                        if (file == null || file.isDirectory()) continue;
                    } catch (java.io.IOException unreadableMember) {
                        continue;
                    }
                }
                Integer sc = cfgScore.get(row.config);
                if (sc == null) {
                    sc = macapkFileCfgScore(row.config);
                    cfgScore.put(row.config, sc);
                }
                if (sc < 0) continue;
                ordered.add(new Object[] { sc, -mi, row });
            }
        }
        ordered.sort((a, b) -> {
            int c = Integer.compare((Integer) a[0], (Integer) b[0]);
            return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
        });
        for (Object[] o : ordered) {
            ArscReader.Row row = (ArscReader.Row) o[2];
            fileRowById.put(row.resId, new Object[] { -((Integer) o[1]), row.value, row.config });
        }
        return fileRowById.size();
    }

    /** Config score for a FILE row: the value scorer for every qualifier
     *  except density, which the value path drops (values dirs are density-
     *  free) and files must rank. AOSP ResTable_config::isBetterThan, density
     *  clause: DENSITY_ANY beats scaling any bucket; an exact match beats the
     *  rest; among the rest the closest scale-DOWN (a bigger bucket) beats the
     *  closest scale-up; an unqualified config counts as mdpi (160) and nodpi
     *  as a very large density (it loses to the exact bucket and to a closer
     *  scale-down, beats every smaller bucket). Banded above version and
     *  below night like the framework scorer. */
    private int macapkFileCfgScore(String cfg) {
        String quals = cfg.startsWith("-") ? cfg.substring(1) : cfg;
        int density = 0;   // 0 = unqualified
        StringBuilder rest = new StringBuilder();
        if (!quals.isEmpty()) {
            for (String q : quals.split("-")) {
                if (q.isEmpty()) continue;
                int d = macapkDensityOf(q);
                if (d != -1) { density = d; continue; }
                if (rest.length() > 0) rest.append('-');
                rest.append(q);
            }
        }
        int base = qualifierScore(rest.toString());
        if (base < 0) return -1;
        int goodness;
        if (density == 0xfffe) {                       // anydpi
            goodness = 295;
        } else {
            int d = (density == 0) ? 160 : density;
            if (d == cfgDensityDpi) goodness = 290;
            else if (d > cfgDensityDpi) {
                // nodpi (0xffff) is "the biggest": it ranks below every real
                // bigger bucket and above every smaller one.
                goodness = (density == 0xffff) ? 220 : 280 - Math.min(59, (d - cfgDensityDpi) / 16);
            } else {
                goodness = 200 - Math.min(80, (cfgDensityDpi - d) / 4);
            }
        }
        return base + goodness;
    }

    /** The density a qualifier stands for: a bucket's dpi, 0xfffe for
     *  anydpi, 0xffff for nodpi, -1 when the qualifier is not a density. */
    private static int macapkDensityOf(String q) {
        switch (q) {
            case "anydpi":  return 0xfffe;
            case "nodpi":   return 0xffff;
            case "ldpi":    return 120;
            case "mdpi":    return 160;
            case "tvdpi":   return 213;
            case "hdpi":    return 240;
            case "xhdpi":   return 320;
            case "xxhdpi":  return 480;
            case "xxxhdpi": return 640;
            default:
                if (q.endsWith("dpi") && q.length() > 3
                        && allDigits(q.substring(0, q.length() - 3))) {
                    return Integer.parseInt(q.substring(0, q.length() - 3));
                }
                return -1;
        }
    }

    /** The member zip that owns table `mi`, opened once and kept for the
     *  life of this index (ZipFile is safe for concurrent reads; Android's
     *  AssetManager keeps every ApkAssets open the same way). */
    private java.util.zip.ZipFile macapkMemberZip(int mi) throws java.io.IOException {
        synchronized (macapkMemberZips) {
            java.util.zip.ZipFile zf = macapkMemberZips.get(mi);
            if (zf == null) {
                zf = new java.util.zip.ZipFile(macapkMembers.get(mi));
                macapkMemberZips.put(mi, zf);
            }
            return zf;
        }
    }

    /** The bytes of `id`'s file entry from the member that owns it, with a
     *  compiled XML entry rendered to the decoded-text form; null when the
     *  id has no file row or the entry cannot be read. */
    private byte[] macapkReadFileRow(int id) {
        Object[] fr = fileRowById.get(id);
        if (fr == null) return null;
        int mi = (Integer) fr[0];
        String path = (String) fr[1];
        try {
            // A PACKED entry: the app keeps this file in its own blob and the
            // table holds a token instead of a path. Serve the slice.
            byte[] packed = macapkPackedBlobBytes(path);
            if (packed != null) {
                if (BinaryXmlParser.isBinaryXml(packed)) {
                    org.w3c.dom.Document doc = BinaryXmlParser.parse(packed, macapkNames());
                    return BinaryXmlParser.toText(doc)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                return packed;
            }
            java.util.zip.ZipFile zf = macapkMemberZip(mi);
            java.util.zip.ZipEntry e = zf.getEntry(path);
            if (e == null) return null;
            byte[] bytes;
            try (InputStream in = zf.getInputStream(e)) { bytes = in.readAllBytes(); }
            if (path.endsWith(".xml") && BinaryXmlParser.isBinaryXml(bytes)) {
                org.w3c.dom.Document doc = BinaryXmlParser.parse(bytes, macapkNames());
                return BinaryXmlParser.toText(doc).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return bytes;
        } catch (Throwable t) {
            NativeLog.w(TAG, "file row 0x" + Integer.toHexString(id) + " (" + path
                + " in " + macapkMembers.get(mi).getName() + ") unreadable: " + t);
            return null;
        }
    }

    // ---- PACKED resource blobs (Meta apps) -------------------------------
    //
    // An app may keep its compiled resources in ONE blob of its own and rewrite
    // each resources.arsc entry from "res/layout/x.xml" to a private token:
    //
    //     L|<hexOffset>|<hexLength>|<hexHash>
    //
    // MEASURED on Instagram 445: 5,947 of its 6,017 layout entries are tokens and
    // only 70 are real res/ paths; consecutive tokens are contiguous
    // (0xF538 + 0x5C4 == 0xFAFC), and the bytes at each offset in the
    // xz-decompressed assets/layouts.bin.xz begin 03 00 08 00 -- RES_XML_TYPE,
    // a compiled-XML chunk. Its main activity's layout is one of them, so without
    // this the inflater has nothing to inflate, setContentView fails and the app
    // finishes itself.
    //
    // This is a FORMAT we support, not an app we special-case: the trigger is the
    // token's SHAPE plus the presence of the blob asset, never a package name --
    // the same footing as the Facebook superpack format we already unpack for
    // native libs. Any app packing resources this way is served.
    private static final Map<Character, String> MACAPK_PACKED_BLOBS = new HashMap<>();
    static { MACAPK_PACKED_BLOBS.put('L', "assets/layouts.bin.xz"); }

    /** Decompressed blob per asset name; one entry, built on first use. */
    private final Map<String, byte[]> macapkBlobCache = new HashMap<>();
    private final java.util.Set<String> macapkBlobMisses = new java.util.HashSet<>();

    /** The bytes a packed token addresses, or null when `path` is an ordinary
     *  path (the overwhelmingly common case -- this must stay cheap). */
    private byte[] macapkPackedBlobBytes(String path) {
        if (path == null || path.length() < 6 || path.charAt(1) != '|') return null;
        String blobName = MACAPK_PACKED_BLOBS.get(path.charAt(0));
        if (blobName == null) return null;
        int p2 = path.indexOf('|', 2);
        if (p2 < 0) return null;
        int p3 = path.indexOf('|', p2 + 1);
        if (p3 < 0) return null;
        final long off, len;
        try {
            off = Long.parseLong(path.substring(2, p2), 16);
            len = Long.parseLong(path.substring(p2 + 1, p3), 16);
        } catch (NumberFormatException nfe) { return null; }
        if (off < 0 || len <= 0) return null;
        byte[] blob = macapkPackedBlob(blobName);
        if (blob == null || off + len > blob.length) return null;
        byte[] out = new byte[(int) len];
        System.arraycopy(blob, (int) off, out, 0, (int) len);
        return out;
    }

    /** Find the blob in the apk set and xz-decompress it, once. */
    private byte[] macapkPackedBlob(String blobName) {
        byte[] cached = macapkBlobCache.get(blobName);
        if (cached != null) return cached;
        if (macapkBlobMisses.contains(blobName)) return null;
        byte[] out = null;
        try {
            byte[] xz = null;
            for (int mi = 0; mi < macapkMembers.size() && xz == null; mi++) {
                java.util.zip.ZipFile zf = macapkMemberZip(mi);
                if (zf == null) continue;
                java.util.zip.ZipEntry e = zf.getEntry(blobName);
                if (e == null) continue;
                try (InputStream in = zf.getInputStream(e)) { xz = in.readAllBytes(); }
            }
            if (xz != null) {
                // macOS's Compression framework; Java SE has no XZ decoder.
                out = NativeOs.xzDecompress(xz);
                if (out != null) {
                    NativeLog.i(TAG, "packed resource blob " + blobName + ": "
                            + xz.length + " bytes xz -> " + out.length
                            + " decompressed (serving this app's packed resources)");
                } else {
                    NativeLog.w(TAG, "packed resource blob " + blobName
                            + " could not be xz-decompressed; its resources stay unavailable");
                }
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "packed resource blob " + blobName + " unreadable: " + t);
            out = null;
        }
        if (out == null) { macapkBlobMisses.add(blobName); return null; }
        macapkBlobCache.put(blobName, out);
        return out;
    }

    /** Every id of `type` that has a FILE row (the installed apk's entries),
     *  ascending -- how a caller enumerates "all res/xml/ files" now that no
     *  decoded directory exists to list. */
    public java.util.List<Integer> macapkFileIdsOfType(String type) {
        java.util.List<Integer> out = new ArrayList<>();
        for (Integer id : fileRowById.keySet()) {
            String[] tn = idToTypeName.get(id);
            if (tn != null && type.equals(tn[0])) out.add(id);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** The names a compiled app XML refers to: the app's ids (every member),
     *  the platform's, and the enum/flag symbols of both attr sets. */
    private BinaryXmlParser.Names macapkNamesCache;
    private BinaryXmlParser.Names macapkNames() {
        BinaryXmlParser.Names n = macapkNamesCache;
        if (n != null) return n;
        final ArscReader fw = macapkFrameworkTable();
        n = new BinaryXmlParser.Names() {
            @Override public String typeName(int resId) {
                String[] tn = idToTypeName.get(resId);
                if (tn != null) return tn[0] + "/" + tn[1];
                if (fw != null) return fw.publicMap().get(resId);
                return null;
            }
            @Override public String enumText(int attrResId, int dataType, int data) {
                for (ArscReader t : macapkTables) {
                    if (t.hasAttrSymbols(attrResId)) return t.renderAttrInt(attrResId, dataType, data);
                }
                if (fw != null && fw.hasAttrSymbols(attrResId))
                    return fw.renderAttrInt(attrResId, dataType, data);
                return null;
            }
        };
        macapkNamesCache = n;
        return n;
    }

    private void macapkClearIngestState() {
        fileRowById.clear();
        // The typed-by-id source is filled per row too; a half-ingested
        // table must not leave entries that Resources.getValue would serve
        // ahead of the text loaders' values (v1.0.1589).
        rawTypedById.clear();
        idToTypeName.clear();
        nameToId.clear();
        stringValues.clear();
        pluralValues.clear();
        integerValues.clear();
        stringArrayValues.clear();
        integerArrayValues.clear();
        colorValues.clear();
        colorRefs.clear();
        styleByName.clear();
        fileAliases.clear();
        dimenValues.clear();
        fractionValues.clear();
        boolValues.clear();
        attrEnums.clear();
        attrFlagSets.clear();
    }

    /** Ingest every row of every member's table into the same maps the text
     *  loaders fill, in ascending config-precedence order.
     *
     *  SELECTION ACROSS MEMBERS follows AssetManager2::FindEntryInternal
     *  (libs/androidfw/AssetManager2.cpp:827-925, aosp main): the packages of
     *  the group are walked in mount order; the first matching config is the
     *  candidate and a later member replaces it ONLY when its config
     *  isBetterThan the candidate's (:890) -- an EQUAL config in a later
     *  member is skipped (:893-898). So the best config wins across all
     *  members and, on a tie, the EARLIEST-mounted member (the base). With
     *  last-put-wins that is: rows sorted by ascending score, and among equal
     *  scores the later members put FIRST so the base's put lands last.
     *
     *  NAMES are taken from the id map built first (macapkIngestNames), not
     *  from the row: an aapt2 `--collapse-resource-names` table keys every
     *  collapsed entry with the one pool string "(name removed)", and keying
     *  the value maps by that name folded 552 Instagram string ids onto one
     *  slot (docs/resources-in-place-gap-assessment_2026-09-20.md FINDING 2).
     *  Each such id gets apktool's spelling, APKTOOL_RENAMED_0x<id>, so the
     *  text and arsc paths stay diff-clean. */
    private void macapkIngestRows(List<ArscReader> tables) {
        // 1) The id <-> type/name maps (public.xml's job), base first: on a
        // tie between members the first-mounted name is kept (they agree in
        // any real set; the rule is written down so it is a rule).
        java.util.Set<String> collapsed = new java.util.HashSet<>();
        for (ArscReader r : tables) {
            for (Map.Entry<Integer, String> en : r.publicMap().entrySet()) {
                int id = en.getKey();
                if (idToTypeName.containsKey(id)) continue;
                String tn = en.getValue();
                int slash = tn.indexOf('/');
                if (slash <= 0) continue;
                String type = tn.substring(0, slash);
                String name = tn.substring(slash + 1);
                Integer other = nameToId.get(tn);
                if ("(name removed)".equals(name) || (other != null && other != id)) {
                    name = String.format("APKTOOL_RENAMED_0x%08x", id);
                    tn = type + "/" + name;
                    collapsed.add(tn);
                }
                idToTypeName.put(id, new String[] { type, name });
                nameToId.put(tn, id);
            }
        }

        // 2) Bucket rows by config qualifier, score with the SAME scorer the
        // text path uses (Row.config is byte-compatible with apktool's
        // values-<quals> suffix by ArscReader's contract), drop incompatible
        // configs, apply ascending so better configs override per key.
        final Map<String, Integer> cfgScore = new HashMap<>();
        java.util.Set<String> allCfgs = new java.util.LinkedHashSet<>();
        List<Object[]> ordered = new ArrayList<>();   // {score, -memberIndex, row}
        long totalRows = 0;
        for (int mi = 0; mi < tables.size(); mi++) {
            for (ArscReader.Row row : tables.get(mi).rows()) {
                ++totalRows;
                allCfgs.add(row.config);
                Integer sc = cfgScore.get(row.config);
                if (sc == null) {
                    sc = qualifierScore(row.config.isEmpty() ? "" : row.config.substring(1));
                    cfgScore.put(row.config, sc);
                }
                if (sc < 0) continue;
                ordered.add(new Object[] { sc, -mi, row });
            }
        }
        // Stable: rows of one member+config keep their table order.
        ordered.sort((a, b) -> {
            int c = Integer.compare((Integer) a[0], (Integer) b[0]);
            return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
        });
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, Integer> en : cfgScore.entrySet())
            if (en.getValue() >= 0) selected.add(en.getKey());
        selected.sort(Comparator.comparingInt(cfgScore::get));

        int[] n = new int[12];   // strings, plurals, ints, sarr, iarr, colors,
                                 // colorRefs, styles, aliases, dimenFrac, bools, attrVals
        for (Object[] o : ordered) {
            macapkIngestRow((ArscReader.Row) o[2], n);
        }
        int nFiles = macapkIngestFileRows(tables);

        // One headline line + the per-category counts in the text loaders'
        // shapes, so log diffs against a default-path run line up.
        StringBuilder cfgs = new StringBuilder();
        for (int i = 0; i < selected.size() && i < 12; ++i) {
            if (i > 0) cfgs.append(' ');
            cfgs.append(selected.get(i).isEmpty() ? "(default)" : selected.get(i));
        }
        if (selected.size() > 12) {
            cfgs.append(" +").append(selected.size() - 12).append(" more");
        }
        // MACAPK_RES_SIZECLASS: the list above is the 12 LOWEST-scoring
        // selected configs, and a size-class config scores high, so a -large /
        // -xlarge one routinely hides in "+N more" (measured: tobsolitaire
        // 23/121 and sgtpuzzles 23/135 select -large on 1680x898, neither shows
        // it). Name the selected size/aspect configs explicitly so the log
        // answers "did the class apply?" on its own.
        StringBuilder layoutSel = new StringBuilder();
        if (MACAPK_RES_SIZECLASS) {
            int k = 0;
            for (String s : selected) {
                if (!macapkNamesSizeOrAspect(s)) continue;
                if (k++ < 12) layoutSel.append(' ').append(s);
            }
            if (k > 12) layoutSel.append(" +").append(k - 12).append(" more");
            layoutSel.insert(0, "; size/aspect configs selected (" + k + "):");
            if (k == 0) layoutSel.append(" none");
        }
        NativeLog.i(TAG, "resources.arsc ingest (the installed apk set, in place): "
            + tables.size() + " table(s), " + idToTypeName.size() + " ids, " + totalRows
            + " rows, " + selected.size() + "/" + allCfgs.size() + " configs selected: " + cfgs
            + layoutSel
            + (collapsed.isEmpty() ? "" : "; " + collapsed.size() + " collapsed name(s) synthesised"));
        NativeLog.i(TAG, "loaded " + n[0] + " string values (arsc)");
        NativeLog.i(TAG, "loaded " + n[1] + " plural resources (arsc)");
        NativeLog.i(TAG, "loaded " + n[2] + " integer values (arsc)");
        NativeLog.i(TAG, "loaded " + n[3] + " string-array(s) + " + n[4]
            + " integer-array(s) (arsc)");
        NativeLog.i(TAG, "loaded " + n[5] + " color values + " + n[6]
            + " color refs (arsc)");
        NativeLog.i(TAG, "loaded " + n[7] + " style definitions (arsc)");
        NativeLog.i(TAG, "loaded " + n[8] + " file-alias entries (arsc)");
        NativeLog.i(TAG, "loaded " + nFiles + " file rows across " + tables.size()
            + " member(s) (arsc; served in place)");
        NativeLog.i(TAG, "loaded " + n[9] + " dimen + fraction values (arsc)");
        NativeLog.i(TAG, "loaded " + n[10] + " bool values (arsc)");
        NativeLog.i(TAG, "loaded " + n[11]
            + " enum/flag constants (from the app's attr bags, arsc)");
    }

    // ResourceTypes.h ResTable_map: ATTR_TYPE's data is the attr format
    // bitmask; enum/flag symbol presence is what makes a bag worth mapping.
    private static final int MACAPK_ATTR_TYPE_ENUM  = 1 << 16;
    private static final int MACAPK_ATTR_TYPE_FLAGS = 1 << 17;

    /** Keep references tied to their resource ID after collapsed names have
     *  been assigned distinct names by macapkIngestRows. The reader's original
     *  formatted name may be shared by thousands of otherwise different IDs. */
    private String macapkIngestReference(int type, int id, String fallback) {
        boolean attr = type == ArscReader.TYPE_ATTRIBUTE
                || type == ArscReader.TYPE_DYNAMIC_ATTRIBUTE;
        boolean ref = type == ArscReader.TYPE_REFERENCE
                || type == ArscReader.TYPE_DYNAMIC_REFERENCE;
        if (!attr && !ref) return fallback;
        String[] target = idToTypeName.get(id);
        if (target == null) return fallback;
        return (attr ? "?" : "@") + target[0] + "/" + target[1];
    }

    /** One row into the map the matching text loader would have filled.
     *  Each branch's contract is the corresponding load*Values method. */
    private void macapkIngestRow(ArscReader.Row row, int[] n) {
        // The (possibly synthesised) name the id map settled on -- see
        // macapkIngestRows; the row's own key only when the id is unmapped.
        String name = row.name;
        String[] tn = idToTypeName.get(row.resId);
        if (tn != null && tn[1] != null) name = tn[1];
        if (name == null || name.isEmpty()) return;
        // The typed SOURCE of every simple value row, keyed by id: aapt's
        // dataType survives here (digit-count colour flavours, hex-ness of
        // ints, reference-ness) where the name-keyed tables flatten it.
        // Resources.getValue serves from this first under the gate; absent
        // (gate OFF) every consumer keeps the flattened tables. Last-put
        // wins in the same ascending-config order as the tables themselves.
        if (!row.complex) {
            rawTypedById.put(row.resId,
                new Object[] { row.dataType, row.data, row.value });
        }
        String value = macapkIngestReference(row.dataType, row.data, row.value);
        switch (row.type) {
            case "string":
                if (!row.complex) {
                    // The pool string is the FINAL runtime value: aapt2
                    // unescaped at compile time, so unlike the text path no
                    // macapkUnescapeAaptString round-trip applies here.
                    // References ("@string/x") arrive formatted and are
                    // chased at read time by macapkResolveStringChain,
                    // same as the text path.
                    stringValues.put(name, value);
                    ++n[0];
                }
                return;
            case "plurals":
                if (row.complex) {
                    java.util.Map<String, String> forms = new java.util.HashMap<>();
                    for (ArscReader.MapItem it : row.items) {
                        // mapItemName yields exactly the CLDR quantity
                        // keywords (other/zero/one/two/few/many) for a
                        // plurals bag; anything else is unresolvable.
                        if (it.name != null && !it.name.isEmpty()) {
                            forms.put(it.name, macapkIngestReference(it.dataType, it.data, it.value));
                        }
                    }
                    if (!forms.isEmpty()) { pluralValues.put(name, forms); ++n[1]; }
                }
                return;
            case "integer":
                if (!row.complex && (row.dataType == ArscReader.TYPE_INT_DEC
                        || row.dataType == ArscReader.TYPE_INT_HEX)) {
                    // Typed data straight from the table; the text path had
                    // to re-parse decimal/hex strings. Reference-valued
                    // integers stay unstored, matching the text loader's
                    // NumberFormatException skip.
                    integerValues.put(name, row.data);
                    ++n[2];
                }
                return;
            case "array":
                if (row.complex) {
                    int count = row.items.size();
                    String[] arr = new String[count];
                    boolean allInt = count > 0;
                    for (int i = 0; i < count; ++i) {
                        ArscReader.MapItem it = row.items.get(i);
                        String itemValue = macapkIngestReference(it.dataType, it.data, it.value);
                        arr[i] = itemValue != null ? itemValue : "";
                        allInt &= (it.dataType == ArscReader.TYPE_INT_DEC
                                || it.dataType == ArscReader.TYPE_INT_HEX);
                    }
                    // The binary table has ONE array type; the item types
                    // recover the source tag: <integer-array> items compile
                    // as INT_*, <string-array>/<array> items as strings or
                    // references. All-int arrays land in BOTH maps because
                    // AOSP's getStringArray coerces ints to their decimal
                    // strings (AssetManager string coercion), so answering
                    // both is the faithful behaviour.
                    if (allInt) {
                        int[] ia = new int[count];
                        for (int i = 0; i < count; ++i) ia[i] = row.items.get(i).data;
                        integerArrayValues.put(name, ia);
                        ++n[4];
                    }
                    if (!allInt || count == 0) {
                        if (count == 0) integerArrayValues.put(name, new int[0]);
                        stringArrayValues.put(name, arr);
                        ++n[3];
                    } else {
                        stringArrayValues.put(name, arr);
                    }
                }
                return;
            case "color":
                if (!row.complex) {
                    switch (row.dataType) {
                        case ArscReader.TYPE_COLOR_ARGB8:
                        case ArscReader.TYPE_COLOR_RGB8:
                        case ArscReader.TYPE_COLOR_ARGB4:
                        case ArscReader.TYPE_COLOR_RGB4:
                            // Res_value.data is already the fully-expanded
                            // packed ARGB int for all four flavours (aapt2
                            // fills the alpha byte and doubles the nibbles
                            // at compile time; TypedValue.data is what
                            // Resources.getColor returns on a device).
                            colorValues.put(name, row.data);
                            colorRefs.remove(name);
                            ++n[5];
                            return;
                        case ArscReader.TYPE_REFERENCE:
                        case ArscReader.TYPE_DYNAMIC_REFERENCE:
                        case ArscReader.TYPE_ATTRIBUTE:
                        case ArscReader.TYPE_DYNAMIC_ATTRIBUTE:
                            // "@color/x" / "@android:color/x" / "?attr/x",
                            // resolved lazily by resolveColorRef exactly as
                            // text-path colorRefs entries are. The "?" form
                            // is a small widening over the text loader
                            // (which only stored "@"), and resolveColorRef
                            // already handles it.
                            colorRefs.put(name, value);
                            colorValues.remove(name);
                            ++n[6];
                            return;
                        default:
                            return;   // TYPE_NULL / malformed: contribute nothing
                    }
                }
                return;
            case "style":
                if (row.complex) {
                    // parentRef is the compile-time truth: aapt2 already
                    // performed (or suppressed) the dotted-name inference,
                    // so parentRef==0 means genuinely no parent and NO
                    // inference re-runs here. This is the v1.0.886/888
                    // parent="" semantic for free, and the
                    // MACAPK_STYLE_PARENT_EMPTY escape hatch (a text-decode
                    // repair knob) intentionally does not apply.
                    String parent = row.parentRef != 0
                            ? macapkIngestReference(ArscReader.TYPE_REFERENCE, row.parentRef, row.parent)
                            : null;
                    Map<String, String> items = new HashMap<>();
                    for (ArscReader.MapItem it : row.items) {
                        String k = macapkStyleItemKey(it.name);
                        String[] itemName = idToTypeName.get(it.nameRef);
                        if (itemName != null && "attr".equals(itemName[0])) k = itemName[1];
                        if (k != null) items.put(k, macapkIngestReference(it.dataType, it.data, it.value));
                    }
                    styleByName.put(name, new StyleDef(name, parent, items));
                    ++n[7];
                }
                return;
            case "dimen":
                if (!row.complex && !value.isEmpty()) {
                    // Covers plain <dimen> (formatted "16.0dp"), references
                    // ("@dimen/x", chased at read time), AND the float
                    // <item type="dimen"> form the text loader missed (it
                    // only read <dimen> tags).
                    dimenValues.put(name, value);
                    ++n[9];
                }
                return;
            case "fraction":
                if (!row.complex && !value.isEmpty()) {
                    fractionValues.put(name, value);
                    ++n[9];
                }
                return;
            case "bool":
                if (!row.complex && !value.isEmpty()) {
                    boolValues.put(name, value);   // "true"/"false"/"@bool/x"
                    ++n[10];
                }
                return;
            case "attr":
                if (row.complex) {
                    int format = 0;
                    List<ArscReader.MapItem> syms = null;
                    for (ArscReader.MapItem it : row.items) {
                        if ("^type".equals(it.name)) {
                            format = it.data;
                        } else if (it.name != null && !it.name.isEmpty()
                                   && it.name.charAt(0) != '^') {
                            // A symbol slot: name is the generated @id
                            // reference ("id/showTitle"), data the int the
                            // symbol stands for.
                            if (syms == null) syms = new ArrayList<>();
                            syms.add(it);
                        }
                    }
                    boolean isEnum  = (format & MACAPK_ATTR_TYPE_ENUM)  != 0;
                    boolean isFlags = (format & MACAPK_ATTR_TYPE_FLAGS) != 0;
                    if (syms != null && (isEnum || isFlags)) {
                        Map<String, Integer> vals =
                            attrEnums.computeIfAbsent(name, k -> new HashMap<>());
                        for (ArscReader.MapItem it : syms) {
                            int slash = it.name.lastIndexOf('/');
                            String cn = slash >= 0 ? it.name.substring(slash + 1) : it.name;
                            if (!cn.isEmpty()) { vals.put(cn, it.data); ++n[11]; }
                        }
                        if (isFlags) attrFlagSets.add(name);
                    }
                }
                return;
            default:
                // File-backed types: a TYPE_REFERENCE entry is a values-
                // declared file ALIAS (no file of its own); everything else
                // (the file-path string entries) resolves from disk exactly
                // as before.
                if (!row.complex && FILE_ALIAS_TYPES.contains(row.type)
                        && (row.dataType == ArscReader.TYPE_REFERENCE
                            || row.dataType == ArscReader.TYPE_DYNAMIC_REFERENCE)
                        && value.startsWith("@") && value.indexOf('/') > 0) {
                    fileAliases.put(row.type + "/" + name, value);
                    ++n[8];
                }
        }
    }

    /** A style bag item's key, converted from ArscReader's "type/name" attr
     *  spelling to the decoded-text spelling every consumer expects:
     *      "attr/textColor"          -> "textColor"
     *      "android:attr/textColor"  -> "android:textColor"
     *  Returns null for internal slots ("^type"), unresolvable names, and
     *  non-attr keys, which the caller drops (a style item that cannot be
     *  keyed cannot be looked up either way). */
    private static String macapkStyleItemKey(String bagItemName) {
        if (bagItemName == null || bagItemName.isEmpty()) return null;
        if (bagItemName.charAt(0) == '^') return null;
        int a = bagItemName.indexOf("attr/");
        if (a == 0) return bagItemName.substring(5);
        if (a > 0 && bagItemName.charAt(a - 1) == ':') {
            return bagItemName.substring(0, a) + bagItemName.substring(a + 5);
        }
        return null;
    }

    /** id -> {Integer dataType, Integer data, String text} for every simple
     *  value row the arsc ingest saw (gate ON only; empty otherwise). The
     *  pre-chase SOURCE encoding, which the name-keyed tables cannot carry:
     *  a `#0f0` colour keeps TYPE_INT_COLOR_RGB4, `0x7fff` keeps
     *  TYPE_INT_HEX, and a reference-valued entry keeps TYPE_REFERENCE with
     *  data = the target id. */
    private final Map<Integer, Object[]> rawTypedById = new HashMap<>();

    public Object[] macapkRawValueOf(int id) { return rawTypedById.get(id); }

    /** Raw `<dimen name="X">16.0dip</dimen>` strings (unit kept) and
     *  `<fraction name="Y">15%</fraction>` strings from res/values.
     *  Served to TypedArray so `@dimen/X` margins and `@fraction/Y`
     *  guideline percents resolve (both were silently lost before --
     *  Calculator's display/keypad guidelines are percent-positioned
     *  and collapsed to zero, degenerating the whole MotionLayout
     *  solve). */
    private final Map<String, String> dimenValues = new HashMap<>();
    private final Map<String, String> fractionValues = new HashMap<>();
    public String getDimenRaw(String name)    {
        String v = chaseValueRef(dimenValues.get(name), new java.util.HashSet<>());
        if (v != null) return v;
        // v1.0.917: a framework dimen NAME (reached via macapkTypeNameOf on
        // a framework id this pipeline handed out in TypedValue.resourceId)
        // serves from the platform table under the gate. The app's own
        // table always wins above, so a name collision changes nothing.
        MacapkFwView fv = macapkFwView;
        if (fv != null) {
            Integer fid = fv.nameToId.get("dimen/" + name);
            if (fid != null) return macapkFwLeafText(fid, 0);
        }
        return null;
    }
    public String getFractionRaw(String name) { return chaseValueRef(fractionValues.get(name), new java.util.HashSet<>()); }

    /** Config-qualified `<bool name="X">true</bool>` values. Was missing
     *  entirely (Resources.getBoolean was a `return false` stub), so any
     *  feature-flag bool read at runtime resolved false. The visible victim:
     *  Google Calculator's toolbar history button is gated on
     *  R.bool.feature_history, which is false in values/bools.xml but TRUE in
     *  values-h320dp/bools.xml (the config the calc's h320dp layout runs in);
     *  the false stub forced the button gone. Resolved config-qualified via the
     *  same matchingValueDirs/qualifierScore machinery as dimens/fractions, and
     *  @bool/ references are chased to a literal. */
    private final Map<String, String> boolValues = new HashMap<>();
    /** attr name -> (enum/flag constant name -> int value), harvested from the
     *  app's OWN `res/values/attrs.xml`. See loadAttrEnums(). */
    private final Map<String, Map<String, Integer>> attrEnums = new HashMap<>();
    /** attrs whose constants are `<flag>` (OR-combinable via `a|b`) rather than
     *  `<enum>` (exactly one). Only flags may be combined. */
    private final java.util.Set<String> attrFlagSets = new java.util.HashSet<>();
    public Boolean getBoolByName(String name) {
        if (name == null) return null;
        String v = chaseValueRef(boolValues.get(name), new java.util.HashSet<>());
        if (v == null) return null;
        v = v.trim();
        if ("true".equalsIgnoreCase(v))  return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
        return null;
    }
    /** Resolve a bool resource id (0x7f05....) to its value, or null if the id
     *  isn't a known bool. Mirrors resolveColorById's id->name->value walk. */
    public Boolean getBoolById(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn == null || tn.length < 2 || tn[1] == null) {
            // v1.0.917: framework bool ids from the platform table (gate
            // only), same coherence rationale as resolveColorById's branch.
            MacapkFwView v = macapkFwView;
            if (v != null && (id >>> 24) == 0x01) {
                String leaf = macapkFwLeafText(id, 0);
                if ("true".equalsIgnoreCase(leaf))  return Boolean.TRUE;
                if ("false".equalsIgnoreCase(leaf)) return Boolean.FALSE;
            }
            return null;
        }
        return getBoolByName(tn[1]);
    }

    /** Follow a framework simple-value reference chain to its leaf and
     *  return the leaf's RENDERED literal text (ArscReader's rendering,
     *  e.g. "56.0dip", "true", "0.5"), or null when the view is absent or
     *  the id is unknown. Publishes each followed id (v1.0.917 last-
     *  reference channel). This is what makes a framework dimen/bool id
     *  handed out in TypedValue.resourceId servable when a caller
     *  re-fetches it by id, as a device's resource table would. */
    private String macapkFwLeafText(int id, int depth) {
        MacapkFwView v = macapkFwView;
        if (v == null || depth > 20) return null;
        Object[] e = v.simple.get(id);
        if (e == null) return null;
        int dt = (Integer) e[0];
        if (dt == ArscReader.TYPE_REFERENCE
                || dt == ArscReader.TYPE_DYNAMIC_REFERENCE) {
            int data = (Integer) e[1];
            if (data == 0) return null;
            macapkPublishValueOrigin(data);
            return macapkFwLeafText(data, depth + 1);
        }
        return (String) e[2];
    }

    /** v1.0.446: follow `@fraction/X` / `@dimen/X` value references to a
     *  final literal. Config-qualified value dirs frequently express a
     *  qualified resource as a reference to another (e.g. Calculator's
     *  values-h320dp `history_display_divider_screen_percent ->
     *  @fraction/toolbar_bottom_screen_percent -> 0.083`); without
     *  chasing, TypedArray.getFraction got the literal "@fraction/..."
     *  string and parsed garbage (guideline jumped to y=-1745). Cycle-
     *  and depth-guarded. Returns the literal unchanged when not a ref. */
    private String chaseValueRef(String v, java.util.Set<String> seen) {
        if (v == null) return null;
        v = v.trim();
        if (seen.size() > 16) return v;          // depth backstop
        if (v.startsWith("@fraction/")) {
            String n = v.substring(10);
            if (!seen.add("fraction/" + n)) return null;
            // Each dereference publishes the followed id (v1.0.917), the
            // per-hop `*out_last_reference = value->data` AOSP's
            // ResolveReference performs; Resources.getValue reads the slot
            // to report the chain terminus in TypedValue.resourceId. The
            // direct map read keeps idOfName's synthetic-string-id branch
            // out of the picture: a fabricated id must never be published.
            macapkPublishValueOrigin(macapkNameToIdDirect("fraction", n));
            return chaseValueRef(fractionValues.get(n), seen);
        }
        if (v.startsWith("@dimen/")) {
            String n = v.substring(7);
            if (!seen.add("dimen/" + n)) return null;
            macapkPublishValueOrigin(macapkNameToIdDirect("dimen", n));
            return chaseValueRef(dimenValues.get(n), seen);
        }
        if (v.startsWith("@bool/")) {
            String n = v.substring(6);
            if (!seen.add("bool/" + n)) return null;
            macapkPublishValueOrigin(macapkNameToIdDirect("bool", n));
            return chaseValueRef(boolValues.get(n), seen);
        }
        return v;   // literal (e.g. "0.083", "16.0dip") or unsupported ref shape
    }

    /** nameToId WITHOUT idOfName's synthesis or package handling: the raw
     *  public.xml row or 0. The last-reference publications use this so a
     *  chain through a name the table lacks publishes nothing rather than
     *  a hash-derived id. */
    private int macapkNameToIdDirect(String type, String name) {
        Integer id = nameToId.get(type + "/" + name);
        return id != null ? id : 0;
    }

    // ---- efficiency pass: one DOM parse per file, one dir-scan per prefix ----
    //
    // Before this, each of the 8 value-tag loaders below (strings, plurals,
    // arrays, dimens/fractions, bools, file-aliases, colors, styles)
    // independently re-derived the matching `res/values*` directory list
    // AND re-parsed every file in it from scratch with its own fresh
    // DocumentBuilderFactory, discarding the DOM tree once it pulled out
    // its one tag type -- an 8x-redundant full-tree re-parse of the same
    // files on every single launch, unconditionally, before Application
    // exists (see docs/efficiency-framework-static-init_2026-07-24.md
    // Finding 1). Manifest.java (parsed immediately before this class in
    // Boot.run()) already shows the right shape one file over: parse
    // once, walk the resulting Document many times. These two caches make
    // ResourceIndex do the same: matchingDirsCache memoizes the directory
    // list per type-prefix (was re-scored 6x for "values" alone);
    // parsedValuesDocs memoizes the parsed Document per file (a file like
    // res/values/colors.xml sits in BOTH the matchingValueDirs("values")
    // set and the fixed color/style CONFIG_DIRS set, so it was parsed by
    // all 8 loaders -- now the first loader to touch it parses it, every
    // other loader (regardless of which directory-selection strategy
    // found the file) reuses the cached Document). Both caches are
    // instance fields, so they live and die with one ResourceIndex
    // instance; a Configuration change always constructs a WHOLE NEW
    // ResourceIndex rather than mutating one in place, so there is no
    // stale-cache risk across a config change.
    // ⚠ v1.0.1047: the conclusion above still holds, but the citation it used
    // to carry ("Boot.java:319, 1366") named no such call sites and had not for
    // a long time. The real ones are Boot.run (the boot-time build) and
    // Boot.macapkApplySystemAppearance (the live light/dark flip, which exists
    // PRECISELY because this class bakes cfgNight and every values-* family at
    // construction and therefore cannot be re-qualified in place).
    private final Map<String, List<File>> matchingDirsCache = new HashMap<>();
    private final Map<File, org.w3c.dom.Document> parsedValuesDocs = new HashMap<>();

    /** Score the dash-separated qualifier string of a `res/<type>-<quals>`
     *  directory against the device Configuration. Returns -1 if ANY
     *  qualifier is incompatible or unrecognized; otherwise a >=0
     *  precedence score (higher == more specific). Empty quals (the
     *  default dir) scores 0, or, under MACAPK_RES_SIZECLASS on a normal-or-
     *  larger configuration, the "no size class" band (see
     *  macapkSizeClassBand: one constant added to EVERY config that names no
     *  size class, so their order among themselves is unchanged). Weights
     *  follow AOSP qualifier precedence (smallestWidth > width > height >
     *  screen size > aspect > orientation > night > version) so the most
     *  specific compatible dir wins on ascending-sort apply. */
    private int qualifierScore(String quals) {
        if (quals.isEmpty()) return macapkSizeClassBand(0);
        int score = 0;
        int sizeClass = 0;   // the SCREENLAYOUT_SIZE_* this config names; 0 = none
        String[] parts = quals.split("-");
        for (int qi = 0; qi < parts.length; ++qi) {
            String q = parts[qi];
            if (q.isEmpty()) continue;
            // -v<N> API version: compatible iff device SDK >= N. cfgResSdk
            // equals cfgSdkInt on the default path; the arsc path raises it
            // (see the field's comment).
            if (q.length() >= 2 && q.charAt(0) == 'v' && allDigits(q.substring(1))) {
                int n = Integer.parseInt(q.substring(1));
                if (cfgResSdk < n) return -1;
                score += 1 + Math.min(n, 99);
                continue;
            }
            // -sw<N>dp smallest width
            if (q.length() > 4 && q.startsWith("sw") && q.endsWith("dp")
                && allDigits(q.substring(2, q.length() - 2))) {
                int n = Integer.parseInt(q.substring(2, q.length() - 2));
                if (cfgSmallestScreenWidthDp < n) return -1;
                score += 10000 + n;
                continue;
            }
            // -w<N>dp available width
            if (q.length() > 3 && q.charAt(0) == 'w' && q.endsWith("dp")
                && allDigits(q.substring(1, q.length() - 2))) {
                int n = Integer.parseInt(q.substring(1, q.length() - 2));
                if (cfgScreenWidthDp < n) return -1;
                score += 9000 + n;
                continue;
            }
            // -h<N>dp available height
            if (q.length() > 3 && q.charAt(0) == 'h' && q.endsWith("dp")
                && allDigits(q.substring(1, q.length() - 2))) {
                int n = Integer.parseInt(q.substring(1, q.length() - 2));
                if (cfgScreenHeightDp < n) return -1;
                score += 8000 + n;
                continue;
            }
            // orientation
            if (q.equals("port")) { if (cfgOrientation != 1) return -1; score += 400; continue; }
            if (q.equals("land")) { if (cfgOrientation != 2) return -1; score += 400; continue; }
            // night mode
            if (q.equals("night"))    { if (!cfgNight) return -1; score += 300; continue; }
            if (q.equals("notnight")) { if (cfgNight)  return -1; score += 300; continue; }
            // ---- layout direction, screen aspect, screen shape ----
            // AOSP ResTable_config precedence, high to low, for the qualifiers
            // this method handles:
            //   layoutdir > smallestWidth > width > height > size class
            //   > aspect > round > orientation > night > version
            // Our bands mirror that: ldltr 20000 > sw 10000+n > w 9000+n
            //   > h 8000+n > size class 0..4 x 1600 (MACAPK_RES_SIZECLASS; see
            //   macapkSizeClassBand) > long 700 > notround 600 > port/land 400
            //   > night 300 > -v<N> 1+min(N,99).
            // ⚠ KNOWN DIVERGENCE, stated not hidden: AOSP compares
            // lexicographically and this method SUMS. A sum only approximates a
            // lexicographic order, and it can disagree when a directory carries
            // several qualifiers at once. The magnitudes above are separated far
            // enough that the cases seen in the corpus rank correctly; a real
            // fix is a comparator, which is a larger change than any bug so far
            // has justified. The size-class band made one shape of it LIVE in
            // principle: a class can now outrank a LARGER sw/w/h dp
            // (sw600dp-large over sw720dp); MACAPK_SIZE_CLASS_STEP lists every
            // known shape with measured scores, and debug/sizeclass_gate.sh
            // counts them. No corpus table carries one.
            //
            // layoutdir is a REAL AOSP qualifier: ResTable_config compares it
            // ABOVE orientation, so a matching -ldltr dir must outrank the
            // unqualified one. Scoring it 0 tied it with base `values`, and
            // because the sort in matchingValueDirs is STABLE that left the
            // winner to File.listFiles() order, i.e. to the filesystem. Latent
            // until 2026-08-20, when colours and styles started going through
            // matchingValueDirs: opencalculator defines
            // Base.Widget.AppCompat.Spinner.Underlined in BOTH values/styles.xml
            // and values-ldltr/styles.xml with DIFFERENT parents.
            // layoutdir outranks smallestWidth in AOSP's table, so it is banded
            // above sw's 10000 rather than merely above orientation. MEASURED:
            // 11 decoded app dirs ship a values-ldltr* with content, including
            // values-ldltr-v21, so this is not a one-app curiosity.
            if (q.equals("ldltr")) { score += 20000; continue; }
            // SCREEN ASPECT and SCREEN SHAPE. These used to be accepted
            // "neutrally" at score 0 alongside ldltr, with the comment "harmless
            // on our device". They are not harmless in either direction:
            //   - accepting at 0 TIES with the unqualified `values` dir, and the
            //     sort is stable, so the filesystem decided the winner;
            //   - accepting a qualifier the device does not MATCH is simply
            //     wrong. `values-round` is a WATCH resource set. We are a
            //     rectangular window, so a device would reject it outright, and
            //     we were serving it. MEASURED: one decoded app (YouTube) ships
            //     a values-round with content.
            // Our window is always a rectangle and always "long" (aspect >= 1.5
            // in both orientations: 808x1796 portrait is 2.22, 3244x1796
            // landscape is 1.81), so the answers are constants rather than a
            // new Configuration field, and they are stated here rather than
            // hidden behind a silent accept.
            // ⚠ SUPERSEDED for "long"/"notlong" under MACAPK_RES_SIZECLASS (the
            // branch just below): "always long" stopped being true at v1.0.1647,
            // when the automatic window became Android 16's large-screen window.
            // AOSP's own rule (reduceScreenLayout: long iff (long*3)/5 >=
            // short-1) makes the portrait-locked 673x898dp window NOTLONG
            // (538 < 672) and 835x898dp NOTLONG too; only the unlocked
            // 1680x898dp one is LONG. The two lines after the gated branch are
            // the pre-fix constants, kept verbatim for MACAPK_RES_SIZECLASS=0.
            if (MACAPK_RES_SIZECLASS) {
                // ---- screen SIZE class (small/normal/large/xlarge) ----
                // AOSP ResTable_config::match (android14-release
                // libs/androidfw/ResourceTypes.cpp:2888-2894):
                //     const int screenSize = screenLayout&MASK_SCREENSIZE;
                //     const int setScreenSize = settings.screenLayout&MASK_SCREENSIZE;
                //     // Any screen sizes for larger screens than the setting do not
                //     // match.
                //     if (screenSize != 0 && screenSize > setScreenSize) {
                //         return false;
                //     }
                // i.e. a size-qualified config matches the device's class and
                // every SMALLER class; the ranking among matches is
                // macapkSizeClassBand's (isBetterThan :2624-2651). The qualifier
                // spellings are ConfigDescription.cpp parseScreenLayoutSize
                // (:138-172). An undefined device size (0) rejects every one,
                // exactly as match() does.
                int sz = macapkSizeClassOf(q);
                if (sz != 0) {
                    if (sz > (cfgScreenLayout
                              & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK)) return -1;
                    sizeClass = sz;
                    continue;
                }
                // ---- screen ASPECT (long/notlong) ----
                // match() :2896-2900: a config naming an aspect matches only the
                // SAME aspect (screenLong != 0 && screenLong != setScreenLong ->
                // no match), so notlong is a real match on a notlong window and
                // long is rejected there. isBetterThan :2652-2655 ranks a config
                // that names the aspect above one that does not, directly below
                // the size class -- the +700 band this method already used.
                // Parse spellings: ConfigDescription.cpp parseScreenLayoutLong
                // (:174-196).
                if (q.equals("long") || q.equals("notlong")) {
                    int want = q.equals("long")
                        ? android.content.res.Configuration.SCREENLAYOUT_LONG_YES
                        : android.content.res.Configuration.SCREENLAYOUT_LONG_NO;
                    if ((cfgScreenLayout & android.content.res.Configuration.SCREENLAYOUT_LONG_MASK)
                            != want) return -1;
                    score += 700;
                    continue;
                }
            }
            if (q.equals("long"))     { score += 700; continue; }
            if (q.equals("notlong"))  return -1;
            if (q.equals("notround")) { score += 600; continue; }
            if (q.equals("round"))    return -1;
            if (q.equals("ldrtl")) return -1;   // we are LTR
            // ---- locale (AOSP qualifier precedence #2) ----
            // Two spellings, both emitted by aapt2/apktool:
            //   values-fr, values-fr-rCA          (legacy; split() has
            //                                      already cut the region
            //                                      into the NEXT token)
            //   values-b+sr+Latn+RS               (BCP-47, API 21+; the
            //                                      subtags are '+'-joined
            //                                      so the token stays whole)
            // AOSP's ResTable_config::match() requires the language to be
            // equal and, when the directory declares a region, the region to
            // be equal as well; anything else is not a match. So a
            // non-matching locale dir falls through to the conservative drop
            // below exactly as before -- this branch only ever ADDS the dirs
            // the device locale actually selects. That also makes the
            // 3-letter uiMode qualifier "car" a non-issue: it would have to
            // BE the device language to be accepted.
            String lang = null, region = null;
            boolean regionFromNextToken = false;
            if (q.length() > 2 && q.startsWith("b+")) {
                String[] sub = q.substring(2).split("\\+");
                lang = sub[0];
                for (int k = 1; k < sub.length; ++k) {
                    // A 2-letter or 3-digit subtag is the region; a 4-letter
                    // subtag is the script, which we have no preference to
                    // match against and therefore neither require nor reject.
                    if (sub[k].length() == 2
                        || (sub[k].length() == 3 && allDigits(sub[k]))) {
                        region = sub[k];
                    }
                }
            } else if (isLangCode(q)) {
                lang = q;
                if (qi + 1 < parts.length && isRegionCode(parts[qi + 1])) {
                    region = parts[qi + 1].substring(1);
                    regionFromNextToken = true;
                }
            }
            if (lang != null && !cfgLanguage.isEmpty()
                && lang.equalsIgnoreCase(cfgLanguage)
                && (region == null || region.equalsIgnoreCase(cfgRegion))) {
                if (regionFromNextToken) ++qi;   // consume the -r<REGION> token
                // Bands chosen so ANY locale match outranks every qualifier
                // scored above (max ~28000 combined), and an exact-region
                // match outranks a language-only match no matter what lower-
                // precedence qualifiers the language-only dir also carries.
                score += 100000;
                if (region != null) score += 50000;
                continue;
            }
            // foreign locale, foreign density, uiMode, mcc/mnc, screen-size
            // class (MACAPK_RES_SIZECLASS=0 only), or anything unknown -> drop
            // conservatively.
            return -1;
        }
        return score + macapkSizeClassBand(sizeClass);
    }

    /** MACAPK_RES_SIZECLASS (2026-09-25), default ON: match the screen-size
     *  CLASS qualifiers (small/normal/large/xlarge) and the screen ASPECT
     *  qualifiers (long/notlong) against the Configuration's screenLayout, the
     *  way AOSP ResTable_config::match / isBetterThan do (qualifierScore quotes
     *  both).
     *
     *  WHY. qualifierScore used to return -1 for every size class, so every
     *  values-large / values-xlarge / layout-large / drawable-xlarge row an app
     *  ships was ignored. Harmless while the window was a phone; wrong since
     *  v1.0.1647 made the automatic window Android 16's large-screen window:
     *  an unlocked app opens at 1680x898dp (XLARGE, long), a portrait-locked
     *  one at 673x898dp or 835x898dp (LARGE, notlong). MEASURED on the corpus
     *  (debug/sizeclass_gate.sh --corpus, 2026-09-25): 41 of 62 APKs ship
     *  size-class configs and ALL 41 change selection on every Mac window --
     *  AppCompat's abc_dialog_min_width_* / abc_dialog_fixed_* (65% -> 55%
     *  large, 45% xlarge) and its DialogWhenLarge styles in almost all of them;
     *  NewPipe's layout-large-land fragment_video_detail and VLC's
     *  cover_media_switcher_item on the landscape window; AnkiDroid's
     *  menu-xlarge deck_picker; Duolingo's integer/is_tablet 0 -> 1 and its
     *  large font sizes; solitaire_cg's whole card deck from drawable-large-* /
     *  drawable-xlarge-*; tobsolitaire's and sgtpuzzles' large dimens. None ships
     *  long/notlong. opencalculator's layout-small / dimen-small now MATCH and,
     *  correctly, still lose to the unqualified rows. The platform table ships
     *  values-large / -xlarge dimens (dialog_min_width_*), 12 DialogWhenLarge
     *  styles (-> *.Dialog.FixedSize) and 6 xlarge layouts, which the framework
     *  view now selects too. The victim the dialog lane found:
     *  `@dimen/abc_dialog_min_width_major` resolved to the `values` 65% on every
     *  window, so Dialog.java carries a hand table of the 55%/45% large/xlarge
     *  values (its REMOVAL CONDITION is this fix).
     *
     *  `=0` restores the pre-fix scorer exactly: every size class dropped,
     *  `long` always accepted (+700), `notlong` always rejected, and every
     *  score numerically what it was (macapkSizeClassBand returns 0 and the
     *  framework-view cache key is unchanged). */
    static final boolean MACAPK_RES_SIZECLASS =
        !"0".equals(System.getenv("MACAPK_RES_SIZECLASS"));

    /** The width of one size-class step in qualifierScore's sum. The class
     *  sits between height and aspect in AOSP's isBetterThan order, which a
     *  sum can only APPROXIMATE, and 1600 is the compromise between two
     *  constraints that cannot both hold at their worst:
     *   - ABOVE the lower-precedence qualifiers: one step must exceed what they
     *     can add to one config. For a value row that is aspect 700 + notround
     *     600 + orientation 400 + night 300 + version <=100 = 2100; a file row
     *     adds density goodness <=295.
     *   - BELOW height: on an xlarge window the matching classes span FOUR
     *     steps (small 0 .. xlarge 4), and `h<N>dp-small` must still beat
     *     `xlarge` plus its lower qualifiers, i.e. 8000 + N > 4 * step + extras.
     *  WHERE 1600 IS EXACT (debug/sizeclass_gate.sh gates it: every pair of 540
     *  value-row and 336 file-row configs on 7 windows, 0 order failures at
     *  height or size): configs whose dp qualifiers are EQUAL, or where one
     *  side names no dp, and whose lower qualifiers total < 1600 -- every value
     *  row EXCEPT one carrying long/notlong AND notround AND orientation or
     *  night. So `h<N>dp-small` beats every xlarge value row that carries no
     *  notround, for any N.
     *  KNOWN LIMITS OF THE SUM, stated rather than hidden (the gate's `mix`
     *  battery counts each shape and prints examples; none occurs in the corpus
     *  -- no app and no platform config combines a class with sw/w/h or
     *  notround, and the gate's --corpus arm checks every corpus winner against
     *  the AOSP oracle):
     *   1. The CLASS DELTA OVERRIDES A dp DIFFERENCE smaller than it, of the
     *      same or a higher tier, in either direction (key difference x 1600,
     *      give or take the lower qualifiers). Measured at 1680x898:
     *      sw600dp-large 15400 beats sw720dp 12320, h600dp-large 13400 beats
     *      h800dp 10400, w600dp-large 14400 beats w820dp 11420, h400dp-xlarge
     *      14800 beats w600dp 11200, and downward, sw600dp 12200 beats
     *      sw720dp-small 10720 (small sits one step BELOW the unqualified
     *      row); AOSP picks the other side of each, because it compares sw
     *      first and then the w+h delta, both before the class
     *      (ResourceTypes.cpp:2587-2623). The pre-fix scorer got these right
     *      only because it rejected EVERY class row, so it also dropped a lone
     *      sw600dp-large outright.
     *   2. A value row with long/notlong + notround + orientation (1700) or
     *      + night (1600, a tie) out-ranks, or ties, the next class up.
     *   3. A file row whose lower qualifiers plus density goodness reach 1600
     *      out-ranks the next class up; an h<N>dp-small row with N below its
     *      rival's extras minus 1600 loses to xlarge.
     *  None of these can be fixed by picking a different step. Shape 1 needs a
     *  1-dp difference to outweigh the whole class spread, i.e. the dp terms
     *  rescaled, and that REORDERS configs that name no class (w820dp vs
     *  w800dp-land-night, pre-existing sum errors of their own) -- the thing this
     *  gate promises not to do. The real fix is the lexicographic comparator
     *  qualifierScore's KNOWN DIVERGENCE note names (a long key, sw > w+h
     *  delta > class > aspect > round > orientation > night > density >
     *  version), landed under its own bisect gate with the AOSP-oracle battery
     *  as its instrument. */
    private static final int MACAPK_SIZE_CLASS_STEP = 1600;

    /** The size-class part of a config's score: AOSP ResTable_config::
     *  isBetterThan (android14-release ResourceTypes.cpp:2624-2651), which
     *  compares the size class right after the w/h dp qualifiers:
     *      // A little backwards compatibility here: undefined is
     *      // considered equivalent to normal.  But only if the
     *      // requested size is at least normal; otherwise, small
     *      // is better than the default.
     *      ...
     *      if (fixedMySL == fixedOSL) {
     *          // If the two are the same, but 'this' is actually
     *          // undefined, then the other is really a better match.
     *          if (mySL == 0) return false;
     *          return true;
     *      }
     *      if (fixedMySL != fixedOSL) {
     *          return fixedMySL > fixedOSL;
     *      }
     *  So on a normal-or-larger configuration the order among MATCHING
     *  configs is small < (no class) < normal < large < xlarge -- a
     *  `values-small` row loses even to the unqualified `values` row, and
     *  loses to it whatever lower-precedence qualifiers it also carries
     *  (`values-small-land` < `values` in landscape). A sum can only express
     *  "below the unqualified row" if the unqualified row is NOT at zero, so
     *  every config that names no class is lifted by one constant step here;
     *  a constant added to all of them leaves their order among themselves
     *  exactly as before, and -1 (no match) is never touched. On a SMALL
     *  configuration no lift is needed: (no class) 0 < small 1 step. With an
     *  undefined device size every class was rejected in qualifierScore, so
     *  `sizeClass` is 0 here and the band is 0. */
    private int macapkSizeClassBand(int sizeClass) {
        if (!MACAPK_RES_SIZECLASS) return 0;
        final int dev = cfgScreenLayout & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK;
        if (dev >= android.content.res.Configuration.SCREENLAYOUT_SIZE_NORMAL) {
            final int key;
            if (sizeClass == 0) key = 1;                                  // no class
            else if (sizeClass == android.content.res.Configuration.SCREENLAYOUT_SIZE_SMALL) key = 0;
            else key = sizeClass;                                         // normal 2, large 3, xlarge 4
            return key * MACAPK_SIZE_CLASS_STEP;
        }
        return sizeClass * MACAPK_SIZE_CLASS_STEP;
    }

    /** Does a config string ("-large-land", "-notlong", ...) name a size class
     *  or an aspect? */
    private static boolean macapkNamesSizeOrAspect(String cfg) {
        if (cfg == null || cfg.isEmpty()) return false;
        for (String q : cfg.split("-")) {
            if (macapkSizeClassOf(q) != 0 || q.equals("long") || q.equals("notlong")) return true;
        }
        return false;
    }

    /** The SCREENLAYOUT_SIZE_* a qualifier token names, 0 when it names none
     *  (ConfigDescription.cpp parseScreenLayoutSize). */
    private static int macapkSizeClassOf(String q) {
        switch (q) {
            case "small":  return android.content.res.Configuration.SCREENLAYOUT_SIZE_SMALL;
            case "normal": return android.content.res.Configuration.SCREENLAYOUT_SIZE_NORMAL;
            case "large":  return android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE;
            case "xlarge": return android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE;
            default:       return 0;
        }
    }

    /** screenLayout for a w x h dp window, through the same port of AOSP's
     *  reduceScreenLayout that DisplayConfig uses (so MACAPK_SCREENLAYOUT_DERIVE
     *  governs both alike). Only the no-Configuration constructor path needs it. */
    private static int macapkDerivedScreenLayout(int wDp, int hDp) {
        // The phone defaults, not the unset `new Configuration()` delta
        // (MACAPK_CONFIG_UNSET_CTOR): macapkDeriveScreenLayout keeps the
        // incoming layout-direction bits, and this path has always answered LTR.
        android.content.res.Configuration c =
            android.content.res.Configuration.macapkPhoneDefaults();
        c.screenWidthDp = wDp;
        c.screenHeightDp = hDp;
        c.macapkDeriveScreenLayout();
        return c.screenLayout;
    }

    /** An Android locale-qualifier language subtag: 2 or 3 lowercase ASCII
     *  letters (ISO-639-1, or ISO-639-2 for languages with no 2-letter
     *  code). */
    private static boolean isLangCode(String s) {
        if (s.length() != 2 && s.length() != 3) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    /** The legacy region subtag spelling: `r` followed by the 2 uppercase
     *  ISO-3166 letters, e.g. "rUS" in `values-en-rUS`. */
    private static boolean isRegionCode(String s) {
        if (s.length() != 3 || s.charAt(0) != 'r') return false;
        for (int i = 1; i < 3; i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++)
            if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** Resolve an enum/flag STRING to its int using the app's own attrs.xml.
     *  Returns null when the attr or any named constant is unknown -- the caller
     *  must then fall back rather than guess, because a partially-resolved flag
     *  set is worse than none. */
    public Integer resolveAttrEnumValue(String attrName, String value) {
        if (attrName == null || value == null) return null;
        String an = attrName;
        int slash = an.lastIndexOf('/');
        if (slash >= 0) an = an.substring(slash + 1);
        int colon = an.lastIndexOf(':');
        if (colon >= 0) an = an.substring(colon + 1);
        Map<String, Integer> vals = attrEnums.get(an);
        if (vals == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (v.indexOf('|') < 0) return vals.get(v);
        if (!attrFlagSets.contains(an)) return null;   // never OR an <enum>
        int acc = 0;
        for (String part : v.split("\\|")) {
            Integer pv = vals.get(part.trim());
            if (pv == null) return null;               // all-or-nothing
            acc |= pv;
        }
        return acc;
    }

    /** Parse file-resource alias entries out of res/values/*.xml.
     *  Two decoded forms exist for the same aapt TYPE_REFERENCE entry:
     *    <item type="layout" name="display">@layout/display_one_line</item>
     *    <layout name="display">@layout/display_one_line</layout>
     *  Only file-backed types participate (a `<color name="x">@color/y`
     *  ref is value-resolved by colorRefs, not a file alias). Base
     *  values/ only -- same default-config policy as the string loader. */
    /** The file-backed resource types whose values-declared TYPE_REFERENCE
     *  entries are file ALIASES. Shared by loadFileAliases (text path) and
     *  the arsc ingest so the two cannot drift. */
    private static final java.util.Set<String> FILE_ALIAS_TYPES = java.util.Set.of(
        "layout", "drawable", "mipmap", "anim", "animator", "xml",
        "menu", "raw", "interpolator", "transition", "font");

    // ---- v1.0.128 theme-attribute resolution ----
    //
    // Background: F-Droid (and any Material3 app) declares a theme tree
    //   Theme.App -> Base.Theme.App -> Theme.Material3.DayNight.NoActionBar
    // with 60+ items like `<item name="colorSecondaryContainer">@color/
    // md_theme_secondaryContainer</item>` and `<item name="material-
    // CardViewStyle">@style/Widget.App.CardView</item>`. The APK's
    // layouts reference these via `?attr/X` (e.g. cards use
    // `?attr/colorSurfaceContainerHigh` as their background).
    //
    // Pre-v1.0.128, Mac.apk's Resources.Theme.resolveAttribute() always
    // returned false, so chrome painters fell back to Material's
    // hardcoded defaults (white card, purple nav pill). This was visible
    // in F-Droid as "right layout, wrong colors."
    //
    // The fix here is a name-based resolver: given an attribute NAME
    // (e.g. "colorSecondaryContainer") and a starting theme style name
    // (e.g. "Theme.App"), walk the parent chain looking for an item
    // with that name. Then resolve the value (hex literal / @color/X /
    // ?attr/Y / @android:color/Z) to the final form requested
    // (int color, dp dimension, etc.).
    //
    // The resolver is generic -- works for ANY APK with a normal
    // Material3 theme cascade, no per-package code.

    /** Records the application-level theme as discovered in
     *  AndroidManifest's `<application android:theme="@style/X">`.
     *  Boot.java wires this after Manifest.parse. */
    public void setApplicationThemeName(String name) {
        if (name == null || name.isEmpty()) {
            this.applicationThemeName = null;
            return;
        }
        // Accept either bare "Theme.App" or full "@style/Theme.App".
        if (name.startsWith("@style/")) name = name.substring(7);
        // The binary-manifest parser (BinaryXmlParser) emits reference-
        // typed attributes in raw resource-id form ("@0x7f150535")
        // because an AXML decode has no resource table attached. Real
        // Android applies the manifest theme BY ID (ActivityThread ->
        // ContextThemeWrapper.setTheme(resid) -> Theme.applyStyle(resid))
        // and the resource table maps the id to the style entry; our
        // styleByName table is keyed by NAME, so mirror that id->entry
        // step through public.xml's id->name map. Without this the
        // whole theme cascade is dead for any binary-parsed manifest
        // (Material3 resolveOrThrow then aborts view inflation, e.g.
        // on colorSurfaceContainer). Keyed on the reference shape,
        // never on the app.
        if (name.startsWith("@")) {
            String resolved = styleNameForIdRef(name);
            if (resolved != null) {
                NativeLog.i(TAG, "application theme id ref " + name
                               + " -> style \"" + resolved + "\"");
                name = resolved;
            } else {
                NativeLog.w(TAG, "application theme id ref " + name
                               + " has no style entry in public.xml");
            }
        }
        this.applicationThemeName = name;
        NativeLog.i(TAG, "application theme set to " + name);
    }

    /** ATTRSTYLE lane, 2026-08-13. Default ON; `MACAPK_ATTRSTYLE=0` restores the
     *  pre-v1.0.968 behaviour of every fix in this lane in ONE run, so the
     *  fleet A/B below can be re-derived from a single binary instead of two
     *  builds taken minutes apart while other lanes are landing work.
     *  Companion switch of the same name in Resources.java. */
    static final boolean MACAPK_ATTRSTYLE =
        !"0".equals(System.getenv("MACAPK_ATTRSTYLE"));

    /** Resolve a STYLE REFERENCE to the style's NAME. Three spellings, all of
     *  which real Android resolves through the same ResTable path:
     *
     *    "@0x7f150535" / "@2131…"   a raw resource-id ref (what a binary AXML
     *                               decode emits, since it has no table attached)
     *    "@style/X" / "@android:style/X"
     *                               the TEXT spelling apktool emits
     *    "?X" / "?attr/X" / "?android:attr/X"
     *                               a THEME ATTRIBUTE ref, which AOSP's
     *                               ResTable::resolveReference follows through the
     *                               theme (TYPE_ATTRIBUTE -> Theme::getAttribute)
     *                               until it lands on a real value
     *
     *  ⚠ THE `?attr` ARM IS THE SAME BUG THIS PROJECT ALREADY FIXED ONCE, in a
     *  second place. v1.0.858 (`Resources.Theme.resolveDefStyleName`) taught the
     *  **defStyleAttr** tier to follow `?attr`, after an AppCompat theme
     *  delegating `<item name="buttonStyle">?android:buttonStyle</item>` left
     *  opensudoku's buttons 57px tall against the emulator's 96px. That fix's
     *  own memory note predicted the shape would recur ("when you find yourself
     *  special-casing a name, check whether the SHAPE is what is unhandled").
     *  It recurred on the **style= tier**, which routes here:
     *
     *    abc_alert_dialog_button_bar_material.xml (AppCompat, in EVERY app that
     *    shows an AlertDialog) declares its three buttons as
     *        <Button android:id="@android:id/button1" style="?buttonBarPositiveButtonStyle" />
     *    and the ScrollView above them as style="?buttonBarStyle".
     *
     *  With `?` dead-ending here, `Resources.Theme.macapkStyleNameFromSet`
     *  returned null, the style= tier of obtainStyledAttributes was DROPPED, and
     *  the buttons fell through to tier 3 (defStyleAttr=buttonStyle) ->
     *  Widget.Material.Button: minWidth 88dip = 176px, minHeight 48dip = 96px,
     *  plus the raised btn_default_material face. AppCompat's real answer for
     *  these is Widget.AppCompat.Button.ButtonBar.AlertDialog -> minWidth 64dp
     *  (128px) and a BORDERLESS parent. Two 176px buttons plus the bar's 24dp
     *  padding do not fit a dialog's width, so ButtonBarLayout STACKS them, which
     *  is why the whole button bar diverged in height AND position rather than
     *  only in width.
     *
     *  The `@style/` arm closes the sibling spelling gap on the SAME caller: this
     *  method previously threw NumberFormatException on a name and returned null,
     *  with the comment "handled by caller" -- true of two of its three callers
     *  (LayoutInflater.styleNameFromRef strips it, resolveDefStyleName strips it)
     *  and FALSE of macapkStyleNameFromSet, which is exactly the caller the
     *  `?attr` chain terminates in (`?buttonBarPositiveButtonStyle` ->
     *  `?buttonBarButtonStyle` -> `@style/Widget.AppCompat.Button.ButtonBar.AlertDialog`).
     *  Without it the follow loop would resolve correctly and then throw the
     *  answer away.
     *
     *  Returns null when the string isn't a style reference or the referent isn't
     *  a known style. */
    private String styleNameForIdRef(String ref) {
        if (ref == null || ref.length() < 2) return null;
        if (MACAPK_ATTRSTYLE) {
            ref = macapkFollowStyleAttrRef(ref.trim());
            if (ref == null || ref.length() < 2) return null;
            // The TEXT spellings. Checked before the numeric parse because
            // "style/X" is not a number and the parse below would discard it.
            if (ref.startsWith("@style/"))         return ref.substring(7);
            if (ref.startsWith("@android:style/")) return ref.substring(15);
        }
        if (ref.charAt(0) != '@') return null;
        String body = ref.substring(1);
        int id;
        try {
            if (body.startsWith("0x") || body.startsWith("0X")) {
                id = (int) Long.parseLong(body.substring(2), 16);
            } else {
                id = (int) Long.parseLong(body, 10);
            }
        } catch (NumberFormatException e) {
            return null;  // "@style/X" handled by caller; names aren't numeric
        }
        String[] tn = idToTypeName.get(id);
        if (tn != null && tn.length >= 2 && "style".equals(tn[0])) return tn[1];
        // lane design 1, 2026-08-02: FRAMEWORK style ids. A binary AXML manifest
        // carries `android:theme` as a raw id ref ("@0x01030006"), and this map
        // only knows the APP's ids -- so applicationThemeName stayed the raw
        // ref and the ENTIRE theme cascade died for such an app. Measured:
        // bobball logs `application theme set to @0x01030006`, which is
        // Theme.NoTitleBar, i.e. classic-DARK -- the exact family whose text
        // polarity makes its menu visible.
        // Ids verified verbatim against aosp-mirror core/res/res/values/
        // public-final.xml (fetched 2026-08-02); that file's own header notes
        // the table is frozen for binary compatibility.
        if (tn == null) {
            // Under the gate the platform table names EVERY framework style
            // id; the 8-row table below stays as the gate-OFF path.
            MacapkFwView v = macapkFwView;
            if (v != null) {
                String s = v.publicMap.get(id);
                if (s != null && s.startsWith("style/")) return s.substring(6);
            }
            String fw = MACAPK_FW_STYLE_BY_ID.get(id);
            if (fw != null) return fw;
        }
        return null;
    }

    /** The AOSP PUBLIC framework style ids this runtime can NAME without the
     *  platform resource table (the curated fallback under the view).
     *  Ids verified verbatim against aosp-mirror core/res/res/values/
     *  public-final.xml (android13-release; re-fetched 2026-08-12); that
     *  file's own header notes the table is frozen for binary compatibility,
     *  so these ids are the same on every API level.
     *
     *  ONE table, read in BOTH directions, deliberately. It used to be an
     *  id -> name switch inlined in styleNameForIdRef, which left two holes
     *  that only look unrelated:
     *    - `macapkTypeNameOf` did not consult it, so Resources.Theme
     *      .applyStyle DROPPED a framework style id (TypedArray.java:284
     *      names this exact defect) and an activity whose manifest theme is
     *      a platform theme ran with an EMPTY applied-style cascade;
     *    - there was no name -> id direction at all, so the apktool TEXT
     *      manifest's `@android:style/X` spelling resolved to 0 in
     *      MacApkPackageManager.resolveResRef and setTheme was never called.
     *  Both holes end at the same place: such an activity had NO theme, so a
     *  game declaring only `@android:style/Theme.NoTitleBar.Fullscreen` never
     *  produced FLAG_FULLSCREEN and we painted a status bar over it.
     *
     *  DELIBERATELY the SAME EIGHT NAMES the old switch carried -- not every
     *  frozen `Theme.*` id. The id -> name direction is therefore byte-
     *  identical to before and the only new behaviour is that these eight can
     *  now be reached from a name and from macapkTypeNameOf. Adding a row
     *  (Theme.Dialog, Theme.NoDisplay and Theme.Translucent.* are the ones
     *  the corpus actually declares, all on NON-main activities) makes that
     *  activity start applying a platform theme where it applies none today,
     *  which is AOSP-correct but is a behavioural change that wants its own
     *  measurement -- do not extend this list as a drive-by. */
    private static final java.util.Map<Integer, String> MACAPK_FW_STYLE_BY_ID;
    private static final java.util.Map<String, Integer> MACAPK_FW_STYLE_BY_NAME;
    static {
        java.util.Map<Integer, String> byId = new java.util.HashMap<>();
        byId.put(0x01030005, "Theme");
        byId.put(0x01030006, "Theme.NoTitleBar");
        byId.put(0x01030007, "Theme.NoTitleBar.Fullscreen");
        byId.put(0x01030008, "Theme.Black");
        byId.put(0x01030009, "Theme.Black.NoTitleBar");
        byId.put(0x0103000c, "Theme.Light");
        byId.put(0x0103006b, "Theme.Holo");
        byId.put(0x0103006e, "Theme.Holo.Light");
        java.util.Map<String, Integer> byName = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, String> e : byId.entrySet()) {
            byName.put(e.getValue(), e.getKey());
        }
        MACAPK_FW_STYLE_BY_ID = java.util.Collections.unmodifiableMap(byId);
        MACAPK_FW_STYLE_BY_NAME = java.util.Collections.unmodifiableMap(byName);
    }

    /** Framework style NAME -> its frozen public id, or 0 (AOSP's "no
     *  resource") when we cannot name it. The reverse of the table above.
     *  Accepts the bare name, `@android:style/X` and `android:style/X`.
     *
     *  Consumed by MacApkPackageManager.resolveResRef so that
     *  `<activity android:theme="@android:style/Theme.NoTitleBar.Fullscreen">`
     *  yields a real id and ActivityThread's `if (theme != 0) setTheme(theme)`
     *  mirror in ActivityHost fires. The platform table answers first, so the
     *  curated list is the fallback and never the ceiling. */
    public int idOfFrameworkStyleName(String name) {
        if (name == null || name.isEmpty()) return 0;
        String n = name;
        if (n.startsWith("@android:style/")) n = n.substring(15);
        else if (n.startsWith("android:style/")) n = n.substring(14);
        else if (n.startsWith("@style/")) n = n.substring(7);
        MacapkFwView v = macapkFwView;
        if (v != null) {
            Integer fid = v.nameToId.get("style/" + n);
            if (fid != null) return fid;
        }
        Integer id = MACAPK_FW_STYLE_BY_NAME.get(n);
        return id != null ? id : 0;
    }

    public String getApplicationThemeName() { return applicationThemeName; }

    /** Public wrapper: resolve a style id-ref ("@0x7f13012e") to its
     *  style name. LayoutInflater needs this for android:textAppearance
     *  values that arrive as resource-id refs from a binary-parsed
     *  layout. Returns null for non-id-ref strings. */
    public String styleNameForIdRefPublic(String ref) {
        return styleNameForIdRef(ref);
    }

    /** Follow a `?attr` chain through the APPLICATION theme until it lands on
     *  something that is not an attribute reference. Non-`?` input is returned
     *  unchanged, so this is safe to call unconditionally on any style ref.
     *
     *  This is the theme-UNAWARE resolver: it walks `applicationThemeName`,
     *  which is the only cascade a plain ResourceIndex caller (LayoutInflater's
     *  textAppearance path, the manifest theme) has. A caller that holds a real
     *  `Resources.Theme` has a BETTER cascade -- the activity's appliedStyles --
     *  and resolves the hops itself before calling in; see
     *  Resources.Theme.macapkFollowThemeAttrRef, which is the tier that actually
     *  matters for an AlertDialog (its decor is inflated against the dialog's own
     *  theme, which may carry a ThemeOverlay the application theme does not).
     *  Both exist on purpose: the app theme is the correct FALLBACK, not a
     *  duplicate of the better one.
     *
     *  Bounded + cycle-guarded for the same reason resolveDefStyleName's loop is:
     *  `?a -> ?b -> @style/X` is normal (AppCompat's buttonBar attrs are a
     *  two-hop chain), and a malformed table must not hang the inflater. Four
     *  hops is what the sibling loop uses; the deepest real chain measured in the
     *  corpus is two (`?buttonBarPositiveButtonStyle` -> `?buttonBarButtonStyle`
     *  -> `@style/…`).
     *
     *  Spellings accepted, all three of which appear in shipped AppCompat XML:
     *  `?X`, `?attr/X`, `?android:attr/X` (and `?android:X`, which is what a
     *  platform-delegating theme item looks like after apktool decodes it). */
    private String macapkFollowStyleAttrRef(String ref) {
        if (ref == null || ref.isEmpty() || ref.charAt(0) != '?') return ref;
        String theme = applicationThemeName;
        // setApplicationThemeName itself routes through styleNameForIdRef before
        // the field is assigned, so there genuinely is no cascade to walk on that
        // one call. Hand the token back rather than inventing an answer -- the
        // same honest-null policy the framework-id table above uses.
        if (theme == null) return ref;
        String cur = ref;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int hop = 0; hop < 4 && cur.startsWith("?"); hop++) {
            String attr = cur.substring(1);
            // NAMESPACE-PRESERVING normalisation, because resolveAttrInTheme is
            // namespace-aware by contract: it walks the whole cascade for the
            // exact key first and only then retries with an `android:` prefix.
            // So `?android:attr/X` must arrive as `android:X`, not as bare `X` --
            // handing it the bare form inverts the precedence and can match an
            // app's own same-named attr that real Android would never consult.
            //   ?attr/X          -> X
            //   ?X               -> X
            //   ?android:attr/X  -> android:X
            //   ?android:X       -> android:X   (already in that shape)
            if (attr.startsWith("attr/"))              attr = attr.substring(5);
            else if (attr.startsWith("android:attr/")) attr = "android:" + attr.substring(13);
            if (!seen.add(attr)) return null;
            String next = resolveAttrInTheme(theme, attr);
            if (next == null) return null;
            next = next.trim();
            if (next.equals(cur)) return null;
            cur = next;
        }
        return cur.startsWith("?") ? null : cur;
    }

    public Integer getColorByName(String name) {
        Integer direct = colorValues.get(name);
        if (direct != null) return direct;
        // Try to resolve a reference (e.g. md_theme_X -> @color/other).
        String ref = colorRefs.get(name);
        if (ref != null) return resolveColorRef(ref, new java.util.HashSet<>());
        // A colour resource is not only a values/*.xml <color> entry. AOSP treats
        // res/color/<name>.xml -- a <selector> ColorStateList -- as the same
        // resource TYPE, and Resources.getColorStateList(id, theme) already loads
        // those. The NAME-keyed path did not, so every theme attribute pointing at
        // one resolved to null and each caller silently took its own fallback.
        return colorFromSelectorFile(name, new java.util.HashSet<>());
    }

    /** Read `res/color[-qualifier]/<name>.xml` -- a ColorStateList `<selector>` --
     *  and return the colour AOSP would report as its DEFAULT, or null when there
     *  is no such file or nothing in it resolves.
     *
     *  WHY THIS EXISTS. `@color/X` names two different kinds of resource: a
     *  `<color>` entry in values/*.xml, and a file-based `<selector>` in
     *  res/color/. Only the first was indexed here. MEASURED VICTIM (fdroid and
     *  deskclock, 2026-08-08): Material3's `Base.V14.Theme.Material3.Light` sets
     *      android:textColorPrimary   = @color/m3_default_color_primary_text
     *      android:textColorSecondary = @color/m3_default_color_secondary_text
     *  and both are selectors over `m3_sys_color_light_on_surface` (#1d1b20) and
     *  `..._on_surface_variant` (#49454f). Neither resolved, so every TextView
     *  kept its own field initialiser, which is opaque BLACK. The emulator paints
     *  4,996 px of #1d1b20 and 10,855 px of #49454f on F-Droid's Latest tab and we
     *  painted ZERO of either; on deskclock the emulator paints 33,542 px of
     *  #1d1b20 and ZERO pure black while we painted 118,625 px of pure black.
     *
     *  THE DEFAULT-COLOUR RULE is AOSP's, from ColorStateList.inflate:
     *      final int color = modulateColor(baseColor, alphaMod, lStar);
     *      if (listSize == 0 || stateSpec.length == 0) { defaultColor = color; }
     *  Seed from the FIRST item, then let ANY later item with an EMPTY state spec
     *  override it. That is what makes m3_default_color_primary_text answer with
     *  its enabled row rather than its 0.38-alpha disabled row.
     *
     *  A row whose colour is a `?attr` reference is SKIPPED rather than guessed:
     *  resolving one needs a Theme, which this class does not have, and a wrong
     *  guess here would be invisible. Such a row simply does not become the
     *  default, so the selector falls through to whichever row does.
     *
     *  v1.0.1590: the selector is the installed set's FILE row for
     *  `color/<name>` -- one document, already config-selected across the
     *  members by the AssetManager2 rule (so res/color-night/ wins over
     *  res/color/ in night mode exactly as the directory walk used to
     *  arrange), rendered from the compiled XML in apktool's text form. The
     *  decoded-tree walk (`matchingValueDirs("color")`) is gone with the
     *  tree. */
    /** The DEFAULT row of a res/color selector file when that row is a theme
     *  attribute: {"?attr", alphaOrNull}, else null. AOSP ColorStateList
     *  inflates against a Theme and its default colour is the first row, or a
     *  later stateless one (ColorStateList.inflate: `if (listSize == 0 ||
     *  stateSpec.length == 0) defaultColor = color`). colorFromSelectorFile has
     *  no Theme and skips `?` rows, so a selector whose default row is a theme
     *  attribute resolved to nothing; Resources.Theme uses this to chase that
     *  row in its OWN cascade. Picks the default row by colorFromSelectorFile's
     *  rule over ALL rows, and answers only when the winner is a `?` row. */
    public String[] macapkSelectorDefaultAttrRow(String name) {
        if (name == null || name.isEmpty()) return null;
        Integer fid = nameToId.get("color/" + name);
        byte[] rowBytes = fid != null ? macapkReadFileRow(fid) : null;
        if (rowBytes == null) return null;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(rowBytes));
            NodeList items = doc.getElementsByTagName("item");
            String[] chosen = null;
            for (int i = 0; i < items.getLength(); ++i) {
                Element e = (Element) items.item(i);
                String colorAttr = e.getAttribute("android:color");
                if (colorAttr == null || colorAttr.trim().isEmpty()) continue;
                boolean hasState = false;
                org.w3c.dom.NamedNodeMap at = e.getAttributes();
                for (int j = 0; at != null && j < at.getLength(); ++j) {
                    if (at.item(j).getNodeName().contains("state_")) { hasState = true; break; }
                }
                if (chosen == null || !hasState) {
                    String alpha = e.getAttribute("android:alpha");
                    if (alpha != null && alpha.trim().startsWith("@dimen/"))
                        alpha = getDimenRaw(alpha.trim().substring(7));
                    chosen = new String[]{ colorAttr.trim(),
                        alpha == null || alpha.trim().isEmpty() ? null : alpha.trim() };
                }
            }
            return chosen != null && chosen[0].startsWith("?") ? chosen : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Integer colorFromSelectorFile(String name, java.util.Set<String> visited) {
        if (name == null || name.isEmpty()) return null;
        // Shared cycle guard with resolveColorRef: a selector row pointing back at
        // its own resource must not recurse forever.
        if (!visited.add("color-file/" + name)) return null;
        Integer answer = null;
        Integer fid = nameToId.get("color/" + name);
        byte[] rowBytes = fid != null ? macapkReadFileRow(fid) : null;
        if (rowBytes != null) {
            org.w3c.dom.Document doc = null;
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                doc = dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(rowBytes));
            } catch (Throwable t) {
                NativeLog.w(TAG, "selector parse failed for color/" + name + ": " + t);
            }
            if (doc == null) return null;
            try {
                NodeList items = doc.getElementsByTagName("item");
                Integer chosen = null;
                for (int i = 0; i < items.getLength(); ++i) {
                    Element e = (Element) items.item(i);
                    String colorAttr = e.getAttribute("android:color");
                    if (colorAttr == null || colorAttr.isEmpty()) continue;
                    colorAttr = colorAttr.trim();
                    if (colorAttr.startsWith("?")) continue;   // needs a Theme
                    // A FRESH visited set per row. Sharing one across the
                    // selector's rows is wrong and silently inverts the result:
                    // sibling rows routinely name the SAME colour (the M3 text
                    // selectors point both their disabled and their enabled row
                    // at m3_sys_color_light_on_surface, differing only in alpha),
                    // so the shared guard treated row 2 as a cycle, returned
                    // null, and left the DISABLED row standing as the default.
                    // Measured: deskclock's body text came out #1d1b20 at 38%
                    // over white = (168,166,170) instead of opaque #1d1b20.
                    // The guard still has to exist -- a row CAN point back at its
                    // own selector -- so seed each row's set from the outer one,
                    // which carries the selector-file key added above.
                    Integer c = colorAttr.startsWith("#")
                        ? parseHexColor(colorAttr)
                        : resolveColorRef(colorAttr, new java.util.HashSet<>(visited));
                    if (c == null) continue;
                    // AOSP modulateColor: android:alpha scales the colour's own
                    // alpha channel. The value is a float, often behind a @dimen.
                    String alphaAttr = e.getAttribute("android:alpha");
                    if (alphaAttr != null && !alphaAttr.isEmpty()) {
                        String av = alphaAttr.trim();
                        if (av.startsWith("@dimen/")) av = getDimenRaw(av.substring(7));
                        try {
                            if (av != null && !av.isEmpty()) {
                                float mod = Float.parseFloat(av.trim());
                                int a = Math.round(((c >>> 24) & 0xFF) * mod);
                                if (a < 0) a = 0; else if (a > 255) a = 255;
                                c = (a << 24) | (c & 0x00FFFFFF);
                            }
                        } catch (Throwable ignored) {}
                    }
                    // AOSP modulateColor, second half: android:lStar (API 31)
                    // REPLACES the colour's lightness, keeping its CAM16 hue and
                    // chroma. This is how Material3 builds its whole tonal
                    // palette on 31-33 -- one base plus a per-tone L* -- so
                    // ignoring it collapsed every tone onto the base. Aurora's
                    // m3_ref_palette_dynamic_neutral_variant6 (lStar 6, near
                    // black) and ...variant98 (lStar 98, near white) both
                    // resolved to their shared base #5C5E67 before this.
                    String lStarAttr = e.getAttribute("android:lStar");
                    if (lStarAttr != null && !lStarAttr.isEmpty()) {
                        String lv = lStarAttr.trim();
                        if (lv.startsWith("@dimen/")) lv = getDimenRaw(lv.substring(7));
                        try {
                            if (lv != null && !lv.isEmpty()) {
                                float lStar = Float.parseFloat(lv.trim());
                                // alphaMod 1.0f: the alpha was already applied
                                // above, and re-applying it here would square it.
                                c = com.android.internal.graphics.cam.CamColor
                                        .modulateColor(c, 1.0f, lStar);
                            }
                        } catch (Throwable ignored) {}
                    }
                    boolean hasState = false;
                    org.w3c.dom.NamedNodeMap at = e.getAttributes();
                    for (int j = 0; at != null && j < at.getLength(); ++j) {
                        if (at.item(j).getNodeName().contains("state_")) { hasState = true; break; }
                    }
                    if (chosen == null || !hasState) chosen = c;
                }
                if (chosen != null) answer = chosen;
            } catch (Throwable ignored) {}
        }
        return answer;
    }

    /** Resolve a color resource id (e.g. 0x7f060123) to an ARGB int, or
     *  null if it's not a known color resource. Wired to
     *  Resources.getColor(id) -- which used to return 0 (transparent),
     *  silently zeroing every resource-backed color. The most visible
     *  victim: Jetpack Compose's `colorResource(R.color.X)` text colors
     *  resolved to 0x0, so all such Compose text drew transparent
     *  (invisible) even though the composition + draw worked. */
    public Integer resolveColorById(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn == null || tn.length < 2 || tn[1] == null) {
            // v1.0.917: FRAMEWORK colour ids resolve from the platform table
            // under the gate, exactly as Resources.getColor(android.R.color.x)
            // does on a device. Required for coherence now that resolution
            // reports framework ids in TypedValue.resourceId: androidx's
            // "resourceId != 0 -> re-fetch by id" pattern (MaterialColors,
            // AppCompatResources) must find the id servable.
            // system_* stays OFF this path for the same reason as
            // resolveColorRef's gate branch: those entries are rewritten by
            // a runtime resource overlay on a device, the table carries only
            // static placeholders, and serving them here would shadow the
            // computed dynamic palette (the measured deskclock regression,
            // 2026-08-08). A system_* miss falls back to the raw-expression
            // machinery, which keeps its dynamic path.
            // Answer system_* from SYS_PALETTE, the SAME table the NAME path
            // uses, so the two can no longer disagree about one resource. This
            // does NOT reintroduce the 2026-08-08 deskclock regression the
            // comment above guards against: that was macapkFwColorById serving
            // the framework table's STATIC placeholders and shadowing the
            // computed palette. This serves the computed palette itself.
            String sysName = macapkSystemPaletteName(id);
            if (sysName != null) {
                Integer sysc = SYS_PALETTE.get(sysName);
                if (sysc != null) return sysc;
            }
            MacapkFwView v = macapkFwView;
            if (v != null && (id >>> 24) == 0x01) {
                String fwtn = v.publicMap.get(id);
                if (fwtn == null || !fwtn.startsWith("color/system_")) {
                    return macapkFwColorById(id, new java.util.HashSet<>(), 0);
                }
            }
            // VIEW-INDEPENDENT terminus for the CLASSIC framework colour ids.
            // Everything above this line needs macapkFwView, which (history:
            // until v1.0.1103) was built only under the opt-in arsc gate, so
            // on the then-default path a framework colour id had no id->name
            // source at all and this method returned null for every one of them, while the
            // NAME path (getColorByName's switch, below) answered 21 of the 28
            // perfectly well. That split is the exact hazard recorded in
            // docs/ and in the auto-memory as "probing by NAME proves nothing
            // about the ID path": one resource, two answers.
            //
            // It is safe to hardcode BECAUSE these ids are frozen. They live in
            // AOSP core/res/res/values/public-final.xml -- the file whose whole
            // purpose is that its assignments can never change -- and they are
            // CONTIGUOUS, 0x01060000..0x0106001b with no gaps (verified against
            // android14-release; the same reasoning and the same file already
            // justify SYS_ID_RAMP_BASE/SYS_ID_ROLE_BASE just below for the
            // system_* block that starts at 0x0106001c).
            //
            // This is an id->NAME map only. The VALUES still come from
            // getColorByName, so there is exactly one place a framework colour
            // is defined and the two paths can no longer disagree. The seven
            // names that table does not carry return null and behave as before.
            String classic = macapkClassicFwColorName(id);
            if (classic != null) {
                // Route through resolveColorRef("@android:color/<name>"), NOT
                // getColorByName. getColorByName reads the APP's colorValues /
                // colorRefs / res-color-selector chain and knows nothing about
                // the platform's own names; the transcribed framework table is
                // in resolveColorRef's "@android:color/" branch. Sending the
                // name to the wrong resolver returns null and this whole block
                // is inert -- measured exactly that way on the first attempt,
                // with Mac.apk.getValue logging `UNRESOLVED id=0x106000d`.
                // resolveColorRef is also the path that consults the platform
                // arsc first when the gate IS on, so the two agree by
                // construction rather than by coincidence.
                Integer cc = resolveColorRef("@android:color/" + classic,
                                             new java.util.HashSet<>());
                if (cc != null) return cc;
            }
            return null;
        }
        // tn[0]=type ("color"/"attr"/...), tn[1]=name. Some color refs are
        // filed under non-"color" types after R8 merges; try the name map
        // regardless of the declared type.
        return getColorByName(tn[1]);
    }

    public StyleDef getStyleByName(String name) {
        if (name == null) return null;
        StyleDef s = styleByName.get(name);
        if (s != null) return s;
        // v1.0.901: continue the walk INTO the framework style table when the
        // app's decoded table has no such name. This is one hook because AOSP
        // resolves both consumers through the same object: AssetManager2::GetBag
        // is RECURSIVE ("Get the parent and do a merge of the keys"), so a style
        // enters the theme WITH ITS WHOLE FLATTENED ANCESTRY, framework terminus
        // included. Our two consumers -- the `style=` flattener
        // (LayoutInflater.macapkStyleItems, which walks parents through THIS
        // method) and the defStyleAttr resolver -- both stopped dead at the
        // boundary because this returned null there.
        //
        // Only the app's table is authoritative: a hit above always wins, so an
        // app that declares its own style by one of these names is untouched.
        // Under the gate the REAL platform table answers next; the
        // hand-transcribed rows remain the gate-OFF terminus.
        MacapkFwView v = macapkFwView;
        if (v != null) {
            StyleDef fs = v.styles.get(macapkFwNormalizeStyleName(name));
            if (fs != null) {
                // MACAPK_TRACE_FWSTYLE=1: which style names the PLATFORM table answers that the
                // app's table did not. This is the exact seam where MACAPK_FW_VIEW changes
                // behaviour, so it is the first thing to read when the gate moves a fleet row.
                if (macapkFwStyleTrace()) NativeLog.i(TAG, "[fw-style] " + name
                    + " -> platform table (normalized " + macapkFwNormalizeStyleName(name)
                    + ", " + (fs.items == null ? 0 : fs.items.size()) + " items, parent="
                    + fs.parent + ") items=" + fs.items);
                return fs;
            }
        }
        return macapkFrameworkStyleDef(name);
    }

    /** The FRAMEWORK STYLE table: what `@android:style/<X>` resolves to when the
     *  app's decoded table cannot possibly contain it. Returns a synthetic
     *  StyleDef so the ordinary parent-chain walk continues through it.
     *
     *  THE ADDITION RULE (read before adding a row). A row belongs here only when
     *  BOTH hold: (a) the value is quoted from AOSP with a file:line, and (b) it
     *  ends in PAINTED PIXELS on our side -- a leaf literal, a `?attr` our theme
     *  cascade already answers, or a resource the app itself ships. A row whose
     *  value is a framework DRAWABLE we do not ship is actively WORSE than no row:
     *  it replaces the app's working value with a dangling reference
     *  (docs/refuted-approaches.md, v1.0.863). That is also why the geometry attrs
     *  are absent -- AOSP's `Widget.CompoundButton.*` set no minHeight/minWidth/
     *  padding at all, and its `Widget.Material.CompoundButton.*` add only a
     *  `background` that is a framework ripple.
     *
     *  MEASURED victim: unitconverter's Help dialog. Its message TextView carries
     *  `style="@style/TextAppearance.AppCompat.Subhead"`, whose chain is
     *      TextAppearance.AppCompat.Subhead
     *        -> Base.TextAppearance.AppCompat.Subhead        (the app's own)
     *        -> @android:style/TextAppearance.Material.Subhead  [MISSING]
     *  so the chain dead-ended and the TextView kept 14sp where AOSP gives 16sp.
     *  27/32 = 0.844 and 33/39 = 0.846 in two independent measurements -- the same
     *  ratio, which is what says it is text SIZE and not line spacing.
     *
     *  The dimen is emitted as a LITERAL rather than as `@android:dimen/...`
     *  because a dimen is a leaf value: the reference form needs four independent
     *  parsers taught the framework namespace and adds a fifth emit/resolve
     *  shadow-table pair, which is the exact bug class of v1.0.866
     *  (docs/refuted-approaches.md).
     *
     *  Names arrive either `@android:style/`-prefixed (from a parent= chain) or
     *  bare (from our own parent strings), so both spellings normalise here. */
    private static StyleDef macapkFrameworkStyleDef(String rawName) {
        String n = rawName;
        if (n.startsWith("@android:style/")) n = n.substring(15);
        else if (n.startsWith("@style/"))    n = n.substring(7);
        else if (n.startsWith("android:"))   n = n.substring(8);
        switch (n) {
            // styles_material.xml TextAppearance.Material.* -> dimens_material.xml
            //   text_size_subhead_material   16sp
            //   text_size_title_material     20sp
            //   text_size_headline_material  24sp
            //   text_size_body_1/2_material  14sp
            //   text_size_caption_material   12sp
            //
            // SIZE ONLY. `textColor` (?attr/textColorPrimary) and `fontFamily`
            // from the same AOSP rows are deliberately WITHHELD: our theme cascade
            // already answers colour for the TextView on its own, and injecting it
            // here would change PRECEDENCE for every TextView whose style chain
            // terminates in a TextAppearance. Serving a dark palette to a light app
            // is exactly the 10-point regression shape v1.0.888 exists to avoid.
            // Size has a measured victim; colour does not.
            case "TextAppearance.Material.Subhead":
                return macapkStyle(n, null, "textSize", "16sp");
            case "TextAppearance.Material.Headline":
                return macapkStyle(n, null, "textSize", "24sp");
            case "TextAppearance.Material.Title":
                return macapkStyle(n, null, "textSize", "20sp");
            case "TextAppearance.Material.Body1":
            case "TextAppearance.Material.Body2":
                return macapkStyle(n, null, "textSize", "14sp");
            case "TextAppearance.Material.Caption":
                return macapkStyle(n, null, "textSize", "12sp");
            // ---- CompoundButton: the INDICATOR only. --------------------------
            // styles_material.xml:
            //   <style name="Widget.Material.CompoundButton.RadioButton"
            //          parent="Widget.CompoundButton.RadioButton">
            //     <item name="background">@drawable/control_background_40dp_material</item>
            //   </style>
            //   Widget.Material.Light.CompoundButton.RadioButton is a bare alias.
            // The `background` row is a framework RIPPLE we do not ship, so it is
            // NOT served -- per the addition rule above, a dangling framework
            // drawable is worse than no row. Only the PARENT LINK is modelled,
            // which is what carries `button` up from the classic style.
            case "Widget.Material.CompoundButton.RadioButton":
                return macapkStyle(n, "Widget.CompoundButton.RadioButton", null, null);
            case "Widget.Material.Light.CompoundButton.RadioButton":
                return macapkStyle(n, "Widget.Material.CompoundButton.RadioButton", null, null);
            case "Widget.Material.CompoundButton.CheckBox":
                return macapkStyle(n, "Widget.CompoundButton.CheckBox", null, null);
            case "Widget.Material.Light.CompoundButton.CheckBox":
                return macapkStyle(n, "Widget.Material.CompoundButton.CheckBox", null, null);
            // styles.xml -- the ONLY level in the whole chain that declares `button`:
            //   <style name="Widget.CompoundButton.RadioButton">
            //     <item name="button">?attr/listChoiceIndicatorSingle</item>
            //   </style>
            //
            // parent=null on purpose. The real AOSP parent is `Widget.CompoundButton`,
            // which sets textColor=?attr/textColorPrimaryDisableOnly and
            // gravity=center_vertical|start. Modelling it would push a text COLOUR
            // into every checkbox and radio in the fleet through a cascade that
            // already answers colour on its own -- the same precedence hazard the
            // TextAppearance rows above refuse. The indicator has a measured
            // victim; the colour does not.
            case "Widget.CompoundButton.RadioButton":
                return macapkStyle(n, null, "button", "?attr/listChoiceIndicatorSingle");
            case "Widget.CompoundButton.CheckBox":
                return macapkStyle(n, null, "button", "?attr/listChoiceIndicatorMultiple");
            // ---- EditText: the TEXT SIZE only. ---------------------------------
            // Every AppCompat app's field chain terminates here and, until now,
            // dead-ended:
            //   Widget.AppCompat.EditText
            //     -> Base.Widget.AppCompat.EditText   (android:background=?editTextBackground)
            //       -> @android:style/Widget.Material.EditText          [WAS MISSING]
            //
            // AOSP, quoted:
            //   styles_material.xml:669
            //       <style name="Widget.Material.EditText" parent="Widget.EditText"/>
            //   styles_material.xml:1143
            //       <style name="Widget.Material.Light.EditText" parent="Widget.Material.EditText"/>
            //   styles.xml:532-543  <style name="Widget.EditText">
            //       <item name="textAppearance">?attr/textAppearanceMediumInverse</item>
            //       ... focusable / focusableInTouchMode / clickable / background /
            //           textColor / gravity / breakStrategy / hyphenationFrequency
            //   themes_material.xml:84  (and :456 for .Light -- SAME value)
            //       <item name="textAppearanceMediumInverse">@style/TextAppearance.Material.Medium.Inverse</item>
            //   styles_material.xml:281-284  TextAppearance.Material.Medium
            //       <item name="textSize">@dimen/text_size_medium_material</item>
            //   dimens_material.xml:88   <dimen name="text_size_medium_material">18sp</dimen>
            //
            // so an EditText is **18sp**, not the 14sp a bare TextView defaults to.
            // MEASURED victim: unitconverter's two conversion fields, whose
            // TableRow is the row everything below it rides up against.
            //
            // The dimen is flattened to a LITERAL `textSize` rather than modelled
            // as the real `textAppearance` -> `?attr` -> style -> `@dimen` chain,
            // for the reason this table's header already gives for the
            // TextAppearance.Material.* rows above: a leaf value needs no
            // namespace-aware parser and no shadow emit/resolve pair. Same
            // convention, same citation discipline.
            //
            // textColor / focusable / gravity / background are deliberately
            // WITHHELD, per this table's addition rule. `background` is the
            // interesting omission: it is `?attr/editTextBackground`, i.e. the
            // underline -- but serving it FROM HERE would only re-point at a
            // framework drawable, and the AppCompat chain above already sets
            // `android:background=?editTextBackground` one level down. The
            // underline is fixed where it is actually missing, by making
            // `@android:drawable/edit_text_material` a real Drawable
            // (Resources.macapkFrameworkDrawable). focusable/clickable are
            // already set unconditionally in EditText.macapkInitEditable.
            case "Widget.Material.EditText":
                return MACAPK_ATTRSTYLE ? macapkStyle(n, "Widget.EditText", null, null) : null;
            case "Widget.Material.Light.EditText":
                return MACAPK_ATTRSTYLE ? macapkStyle(n, "Widget.Material.EditText", null, null) : null;
            case "Widget.EditText":
                return MACAPK_ATTRSTYLE ? macapkStyle(n, null, "textSize", "18sp") : null;
            default:
                return null;
        }
    }

    /** One-item StyleDef helper for the table above. */
    private static StyleDef macapkStyle(String name, String parent, String k, String v) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (k != null) m.put(k, v);
        return new StyleDef(name, parent, m);
    }

    /** Cluster A1 (widget-fidelity lane, 2026-09-26). Default ON;
     *  `MACAPK_ATTR_IDENTITY=0` restores the pre-fix walk for a bisect.
     *
     *  WHAT. resolveAttrInTheme runs pass 1 with the BARE name over the whole
     *  chain, then pass 2 with `android:` + name. App styles key a framework
     *  attribute as `android:X`; the platform styles at the root of every
     *  AppCompat / Material chain (served by macapkFwStyleAttr and the
     *  hand tables in frameworkStyleAttr) key the same attribute BARE, because
     *  inside framework-res `X` IS `android:X`. So a bare query walked past
     *  every app style (none declares a bare `X`) and matched the PLATFORM
     *  ancestor, and the leaf's own `android:X` was never consulted: a
     *  framework value shadowed the app's own style item.
     *
     *  AOSP. A style item is identified by attribute RESOURCE ID, never by
     *  name. AttributeResolution.cpp ApplyStyle (android14-release):
     *      :253  const uint32_t cur_ident = attrs[ii];
     *      :277  xml_style_attr_finder.Find(cur_ident)
     *      :289  def_style_attr_finder.Find(cur_ident)
     *  and a platform style bag can only hold 0x0101xxxx attribute ids, so an
     *  app-namespace (0x7f) attribute can never match inside one. The guard
     *  says exactly that: a style the app table does not hold (a platform
     *  style) never answers a bare name. Pass 2 then finds the leaf-most app
     *  `android:X` or, failing that, the same platform value as before.
     *
     *  MEASURED VICTIMS (offline, the apps' own resources,
     *  docs/widget-fidelity-cluster_2026-09-25.md "Cluster A"):
     *    Google Calculator Widget.GoogleMaterial3.Button.IconButton:
     *      background btn_default_material -> @empty, minWidth 88dp -> 20dp
     *      (with A2 below, the app's MaterialButton builds its own
     *      RippleDrawable instead of the white 176x96 framework box);
     *    VLC VLC.Onboarding.Title / .Text textSize 24sp -> 22sp / 16sp;
     *    F-Droid TextAppearance.Material3.BodyLarge 14sp -> 16sp,
     *      TitleLarge 20 -> 22sp, HeadlineSmall 20 -> 24sp.
     *  BLAST RADIUS, measured (debug/attrident_gate.sh census, 10 apps: every
     *  widget / TextAppearance style x 15 attributes): 4,228 of 134,700 bare
     *  answers change (3.1%; 1.2% newpipe/unitconverter .. 5.0% Google
     *  Calculator), NONE to null, and every one of them equals the OFF run's
     *  `android:` answer for the same style, i.e. it IS the app leaf's own item.
     *  By attribute: textAppearance 1158, background 836, stateListAnimator 589,
     *  textSize 557, minWidth 377, textColor 329, minHeight 169, fontFamily 80,
     *  textStyle 68, textColorHint 36, gravity 25, textAllCaps 4. No `android:`
     *  query moves, and 0 of 400 THEME answers (40 attributes x 10 app themes)
     *  move: a Theme.* platform root never answered pass 1 (macapkFwStyleAttr
     *  and frameworkStyleAttr hold the theme table for pass 3), and pass 3's
     *  bare walk now yields to its `android:` walk, which reaches the same
     *  platform table through the same prefix strip. With the gate at 0 the
     *  census is byte-identical to the pre-fix source (checked by
     *  `debug/attrident_gate.sh --control`). */
    static final boolean MACAPK_ATTR_IDENTITY =
        !"0".equals(System.getenv("MACAPK_ATTR_IDENTITY"));

    /** Walk the theme cascade looking for the first style that declares
     *  `<item name="attrName">value</item>`. Returns the raw value
     *  (may be `@color/X`, `?attr/Y`, `#RRGGBB`, `@style/Z`, `16dp`,
     *  etc.). Returns null if no style in the chain declares it.
     *  `visited` guards against malformed parent loops. */
    public String resolveAttrInTheme(String themeStyleName, String attrName) {
        if (attrName == null) return null;
        // Namespace-aware resolution. The framework attr "android:X"
        // and the app/library attr "X" are DIFFERENT attributes on
        // real Android; appcompat/M3 themes alias them to each other
        // (`<item name="android:colorControlNormal">?colorControlNormal
        // </item>` at V21 plus `<item name="colorControlNormal">
        // ?android:textColorPrimary</item>` lower down). The old
        // per-style "try bare, then android:-prefixed" fallback made
        // `?colorControlNormal` re-match the android:-prefixed alias
        // at V21 (which sits ABOVE the bare declaration in the chain)
        // and the reference chain looped to null -- every theme-tinted
        // vector glyph painted transparent. Pass 1 walks the WHOLE
        // cascade for the exact key; only if the bare name is absent
        // everywhere do we fall back to the android:-prefixed form
        // (pass 2), which keeps `colorBackground`-style callers
        // working when the theme only declares the framework copy.
        // Material You dynamic-color overlay takes precedence (mirrors
        // DynamicColors applying the overlay ABOVE the activity theme).
        // Gated on the GoogleMaterial3 family inside dynamicOverlayValue.
        // ONLY for the bare M3 color tokens (colorTertiary,
        // colorOnSurface, ...) whose overlay values are SOLID dynamic
        // colors; the overlay's android:textColor* rebindings point at
        // ColorStateList resources we can't resolve (null -> invisible
        // glyphs), so android:-prefixed attrs deliberately stay on the
        // static cascade.
        // v1.0.917: fresh last-reference slot per top-level theme query, so
        // an answer that follows no reference reads origin 0 (the AOSP
        // literal-entry contract). Re-entrant queries (the fw table's ?attr
        // hop settling) re-clear and re-publish; the deepest producer's
        // state is what the consumer reads, which is exactly AOSP's
        // out_last_reference threading through nested resolution.
        macapkClearValueOrigin();
        if (!attrName.startsWith("android:")) {
            String dyn = dynamicOverlayValue(themeStyleName, attrName);
            if (dyn != null) return dyn;
        }
        String v = resolveAttrInThemeImpl(themeStyleName, attrName,
            new java.util.HashSet<>());
        if (v == null && !attrName.startsWith("android:")) {
            v = resolveAttrInThemeImpl(themeStyleName,
                "android:" + attrName, new java.util.HashSet<>());
        }
        // PASS 3 (v1.0.834) -- the PLATFORM theme default, and it MUST run
        // last. The framework theme table (frameworkThemeAttr) sits at the
        // TERMINAL style of every AppCompat/Material chain, so consulting it
        // during pass 1 would preempt pass 2's bare -> `android:` fallback,
        // which is how an app that declares only the framework-namespaced copy
        // answers a bare query. MEASURED cost of getting this wrong
        // (Wikipedia 2.7.50591): its `AppTheme` in values-v31 declares
        // `android:windowBackground = ?border_color` and
        // `android:colorBackground = ?background_color` but no BARE
        // `windowBackground`/`colorBackground`; with the table in pass 1 the
        // bare query answered `?android:colorBackground` from the platform
        // instead, and the whole page background flipped from #f8f9fa to
        // #ffffff. The app's own value must always win.
        if (v == null) {
            v = resolveAttrInThemeImpl(themeStyleName, attrName,
                new java.util.HashSet<>(), true);
            if (v == null && !attrName.startsWith("android:")) {
                v = resolveAttrInThemeImpl(themeStyleName,
                    "android:" + attrName, new java.util.HashSet<>(), true);
            }
        }
        return v;
    }
    /** PASS 1 of resolveAttrInTheme ONLY (the Material You overlay, then the
     *  exact key up this style's parent chain): no `android:` alias fallback
     *  (pass 2) and no platform-theme default (pass 3). A multi-style Theme
     *  cascade needs this to ask pass 1 of EVERY applied style before pass 2 of
     *  ANY of them -- see Resources.Theme.macapkResolveAppAttrRawHonest for the
     *  measured victim. A framework (`android:`) name has no app-namespace
     *  identity and answers null here. */
    public String resolveAttrInThemeExact(String themeStyleName, String attrName) {
        if (attrName == null || attrName.startsWith("android:")) return null;
        macapkClearValueOrigin();
        String dyn = dynamicOverlayValue(themeStyleName, attrName);
        if (dyn != null) return dyn;
        return resolveAttrInThemeImpl(themeStyleName, attrName,
            new java.util.HashSet<>());
    }
    private String resolveAttrInThemeImpl(String themeStyleName,
                                          String attrName,
                                          java.util.Set<String> visited) {
        return resolveAttrInThemeImpl(themeStyleName, attrName, visited, false,
                                      themeStyleName);
    }
    private String resolveAttrInThemeImpl(String themeStyleName,
                                          String attrName,
                                          java.util.Set<String> visited,
                                          boolean allowFrameworkTheme) {
        return resolveAttrInThemeImpl(themeStyleName, attrName, visited,
                                      allowFrameworkTheme, themeStyleName);
    }
    /** `rootTheme` is the name the WHOLE query started from (v1.0.913): the
     *  framework-table walk settles ?attr indirections by re-resolving
     *  against the root, so an app override higher in the cascade wins,
     *  exactly as the merged-bag theme behaves on a device. */
    private String resolveAttrInThemeImpl(String themeStyleName,
                                          String attrName,
                                          java.util.Set<String> visited,
                                          boolean allowFrameworkTheme,
                                          String rootTheme) {
        if (themeStyleName == null || attrName == null) return null;
        if (!visited.add(themeStyleName)) return null;  // cycle guard
        StyleDef s = styleByName.get(themeStyleName);
        if (s == null && MACAPK_ATTR_IDENTITY && !attrName.startsWith("android:")) {
            // A platform style can never answer a BARE (app-namespace) name.
            // See MACAPK_ATTR_IDENTITY for the AOSP citation and the measured
            // victims; resolveAttrInTheme's pass 2 re-walks with `android:X`,
            // which finds the leaf-most app `android:X` first and this same
            // platform ancestor last, in AOSP's order.
            return null;
        }
        if (s == null) {
            // The style isn't in the app's decoded table -- typically a
            // FRAMEWORK style (@android:style/Widget.Material.*) that an
            // AppCompat base style inherits from. Under the gate the REAL
            // platform table answers; for names the table contains it is
            // authoritative (a null means the chain genuinely lacks the
            // attribute, and the caller's pass machinery continues). The
            // hand-transcribed tables serve names outside it, and the whole
            // gate-OFF path, unchanged.
            if (macapkFwView != null) {
                String fv = macapkFwStyleAttr(themeStyleName, attrName,
                                              allowFrameworkTheme, rootTheme);
                if (fv != null) return fv;
                if (macapkFwHasStyle(themeStyleName)) return null;
            }
            return frameworkStyleAttr(themeStyleName, attrName,
                                      allowFrameworkTheme);
        }
        // Exact-key match only; namespace handling lives in
        // resolveAttrInTheme above.
        String v = s.items.get(attrName);
        if (v != null) return v;
        // Walk up the parent chain. Parent may be "@style/X" or "X".
        String p = s.parent;
        if (p == null) return null;
        if (p.startsWith("@style/")) p = p.substring(7);
        return resolveAttrInThemeImpl(p, attrName, visited,
                                      allowFrameworkTheme, rootTheme);
    }

    /** Key attrs of well-known FRAMEWORK widget styles that AppCompat/Material
     *  base styles inherit from but which aren't decoded into our app style
     *  table. Currently the action-bar overflow button's android:src: the
     *  framework style Widget.Material.ActionButton.Overflow sets it to the
     *  3-dot overflow drawable. AppCompat apps bundle the identical
     *  abc_ic_menu_overflow_material vector, so point android:src there; the
     *  toolbar overflow button (AppCompat OverflowMenuButton, styled via
     *  ?actionOverflowButtonStyle) then renders its icon. Generic across any
     *  AppCompat app; a no-op for apps that don't bundle the drawable
     *  (downstream getIdentifier returns 0 and nothing is set). */
    private String frameworkStyleAttr(String styleName, String attrName,
                                      boolean allowFrameworkTheme) {
        if (styleName == null || attrName == null) return null;
        String n = styleName;
        if (n.startsWith("@android:style/")) n = n.substring(15);
        else if (n.startsWith("@style/"))    n = n.substring(7);
        else if (n.startsWith("android:"))   n = n.substring(8);
        if ("src".equals(attrName) || "android:src".equals(attrName)) {
            if (n.equals("Widget.Material.ActionButton.Overflow")
                || n.equals("Widget.Material.Light.ActionButton.Overflow")
                || n.equals("Widget.ActionButton.Overflow")) {
                return "@drawable/abc_ic_menu_overflow_material";
            }
        }
        // lane c4, 2026-08-02: the framework BUTTON styles' geometry. Reached
        // once a theme's `buttonStyle` resolves here (see frameworkThemeAttr),
        // which is what a `?android:buttonStyle` delegation now does.
        // Verbatim from AOSP core/res/res/values/styles_material.xml:
        //   <style name="Widget.Material.Button">
        //     <item name="background">@drawable/btn_default_material</item>
        //     <item name="textAppearance">?attr/textAppearanceButton</item>
        //     <item name="minHeight">48dip</item>
        //     <item name="minWidth">88dip</item>
        //     <item name="gravity">center_vertical|center_horizontal</item>
        //   </style>
        // `Widget.Material.Light.Button` is declared as a bare alias of it.
        // Per-family values live in macapkFrameworkButtonGeom (Holo and classic
        // do NOT share Material's numbers -- see its citation block).
        // We deliberately serve only the GEOMETRY + gravity rows, not
        // `background`/`stateListAnimator`: the background is a framework
        // drawable we do not ship, and returning a dangling ref would replace
        // an app's own working background with nothing. Geometry alone is what
        // the measured 57px-vs-96px gap needs, and it cannot repaint anything.
        {
            String a = attrName;
            if (a.startsWith("android:attr/")) a = a.substring(13);
            else if (a.startsWith("android:")) a = a.substring(8);
            String geom = macapkFrameworkButtonGeom(n, a);
            if (geom != null) return geom;
            // v1.0.906: the SAME framework style table getStyleByName falls back
            // to, consulted per-attr here and walked through its PARENT CHAIN.
            // One table, two consumers -- the point being that a new row lights up
            // BOTH the `style=` flattener and the defStyleAttr resolver, instead of
            // the shadow-table pair that has already cost this tree three separate
            // bugs (v868, v870, v871). The app's own table is never consulted from
            // here; these names cannot be in it.
            //
            // ⚠ WIDGET STYLES ONLY, and that restriction is MEASURED, not
            // cautious. Without it the TextAppearance rows become reachable
            // through the defStyleAttr path as well as the `style=` flattener,
            // and that CHANGES PRECEDENCE for every TextView whose chain
            // terminates in a TextAppearance: antennapod's bottom-nav labels grew
            // a size they do not have on the device (96.45 -> 96.20, and the crop
            // showed visibly bolder/larger Home/Queue/Inbox than the emulator).
            // The TextAppearance rows still serve the `style=` consumer, which is
            // where their measured victim was and where they cost nothing.
            if (!n.startsWith("Widget.")) {
                return allowFrameworkTheme ? frameworkThemeAttr(n, attrName) : null;
            }
            for (StyleDef fs = macapkFrameworkStyleDef(n); fs != null; ) {
                String fv = fs.items.get(a);
                if (fv != null) return fv;
                if (fs.parent == null) break;
                StyleDef next = macapkFrameworkStyleDef(fs.parent);
                if (next == null || next == fs) break;
                fs = next;
            }
        }
        // The platform THEME table is pass-3 only; see resolveAttrInTheme.
        return allowFrameworkTheme ? frameworkThemeAttr(n, attrName) : null;
    }

    /** The PLATFORM THEME attribute table -- what a cascade that terminates in
     *  a framework theme (`@android:style/Theme.Material.Light.NoActionBar`,
     *  `Theme.Holo`, `Theme.DeviceDefault`, plain `Theme`) resolves to.
     *
     *  WHY THIS EXISTS (v1.0.834). Every AppCompat / MaterialComponents /
     *  Material3 theme chain bottoms out in a framework theme, and the
     *  AppCompat base styles deliberately ALIAS half their attributes into the
     *  framework namespace -- e.g. `Base.V21.Theme.AppCompat.Light` declares
     *      <item name="selectableItemBackgroundBorderless">
     *        ?android:selectableItemBackgroundBorderless</item>
     *  On a device that `?android:` chase lands in framework-res.apk. Here it
     *  landed on `styleByName.get("@android:style/Theme.Material.Light.NoActionBar")
     *  == null` and the whole chase returned null, so Theme.resolveAttribute
     *  reported "attribute not found".
     *
     *  Measured consequence (Wikipedia 2.7.50591, 2026-07-29): the app's own
     *  guard `ResourceUtil.getThemedAttributeId` throws
     *      IllegalArgumentException: Attribute not found; ID=2130969985
     *  (= 0x7f040581 = attr/selectableItemBackgroundBorderless) from
     *  NotificationButtonView.<init> inside MainFragment.onCreateView, so
     *  FragmentStateManager.createView never attached the view and
     *  @id/fragment_container stayed EMPTY -- a white screen with only the
     *  status bar + toolbar drawn (drawn=142208 = 808x176, every frame).
     *  Verified cascade for that app:
     *      AppTheme -> Theme.Material3.Light.NoActionBar -> ... ->
     *      Base.V21.Theme.AppCompat.Light  [declares the ?android: alias] ->
     *      ... -> Platform.V21.AppCompat.Light ->
     *      @android:style/Theme.Material.Light.NoActionBar  [MISSING]
     *
     *  Values are verbatim from AOSP
     *  frameworks/base/core/res/res/values/themes_material.xml, styles
     *  `Theme.Material` and `Theme.Material.Light` (aosp-mirror
     *  platform_frameworks_base, main, fetched 2026-07-29). Framework
     *  resources are returned FRAMEWORK-NAMESPACED (`@android:drawable/...`,
     *  `@android:color/...`) on purpose: we ship no framework resource table,
     *  so the caller must be able to tell "this names a platform resource we
     *  cannot serve" from "this names an app resource". A `@android:color/*`
     *  name outside resolveColorRef's table still resolves to null, exactly as
     *  before -- so this table changes NO existing colour, it only lets
     *  Theme.resolveAttribute stop lying about whether the attribute exists.
     *
     *  Attribute names arrive either bare or `android:`-prefixed; when the
     *  DECLARING style is the framework theme itself the two spellings name the
     *  same platform attribute, so the prefix is stripped. */
    // Framework theme families. UNKNOWN means "an app-named Theme.* we do not
    // recognise" -- it keeps the pre-2026-08-02 behaviour verbatim.
    /** Geometry of the framework's plain BUTTON widget styles, per FAMILY --
     *  the three families genuinely differ and must NOT share one row.
     *  Fetched and quoted 2026-08-02:
     *    styles.xml        `Widget.Button`         -- NO minHeight, NO minWidth
     *                                                 (only gravity center)
     *    styles_holo.xml   `Widget.Holo.Button`    -- minHeight 48dip, minWidth 64dip
     *    styles_material.xml `Widget.Material.Button` -- minHeight 48dip, minWidth 88dip
     *  An earlier draft of this served Material's 48/88 for all three from
     *  recall; classic has no minimums at all and Holo's minWidth is 64, so
     *  that would have invented a value AOSP does not have. Verified against
     *  source instead -- the struct-offset rule applies to style values too.
     *
     *  Exact-match on purpose, never a `startsWith("Widget.")/endsWith("Button")`
     *  sniff, so `.Borderless`/`.Small`/`.Toggle`/`.Inset` variants -- whose
     *  geometry genuinely differs -- cannot silently inherit these rows.
     *  Returns null when the style is not one we have cited values for. */
    private static String macapkFrameworkButtonGeom(String n, String attr) {
        final boolean material = n.equals("Widget.Material.Button")
                              || n.equals("Widget.Material.Light.Button");
        final boolean holo     = n.equals("Widget.Holo.Button")
                              || n.equals("Widget.Holo.Light.Button");
        final boolean classic  = n.equals("Widget.Button");
        // The `.Small` variants, added v1.0.867 alongside the buttonStyleSmall
        // delegation row. Kept as their OWN flags rather than folded into the
        // above, because their numbers differ -- Material's `.Small` overrides
        // minWidth DOWN to 48dip where the plain style says 88dip, so reusing
        // the base row would make every small button 40dip too wide.
        // Fetched 2026-08-03:
        //   styles_material.xml:553 Widget.Material.Button.Small
        //       -> minHeight 48dip, minWidth 48dip
        //                     :1118 Widget.Material.Light.Button.Small = alias
        //   styles_holo.xml:334/758 Widget.Holo[.Light].Button.Small
        //       -> minHeight 48dip, minWidth 48dip
        //   styles.xml:368          Widget.Button.Small
        //       -> background ONLY (a 9-patch we do not ship); NO minimums,
        //          so classic small stays geometrically unconstrained, which
        //          is what AOSP does.
        final boolean materialSmall = n.equals("Widget.Material.Button.Small")
                                   || n.equals("Widget.Material.Light.Button.Small");
        final boolean holoSmall     = n.equals("Widget.Holo.Button.Small")
                                   || n.equals("Widget.Holo.Light.Button.Small");
        final boolean classicSmall  = n.equals("Widget.Button.Small");
        if (!material && !holo && !classic
                && !materialSmall && !holoSmall && !classicSmall) return null;
        switch (attr) {
            case "minHeight":
                return (material || holo || materialSmall || holoSmall) ? "48dip" : null;
            case "minWidth":
                if (materialSmall || holoSmall) return "48dip";
                return material ? "88dip" : (holo ? "64dip" : null);
            // All of them set gravity center_vertical|center_horizontal (classic
            // declares it; Holo and every `.Small` inherit it via
            // parent="Widget[.Family].Button").
            case "gravity":   return "center_vertical|center_horizontal";
            // v1.0.869: the BACKGROUND, which is the single most visible thing
            // these styles contribute and which was withheld until now.
            //   styles.xml:363          Widget.Button        -> @drawable/btn_default
            //   styles_material.xml:534 Widget.Material.Button -> @drawable/btn_default_material
            // The withholding rule was "never serve a framework background,
            // because a dangling @android:drawable/... replaces an app's own
            // working background with nothing". That reasoning still stands --
            // it is satisfied by Resources.macapkFrameworkDrawable, which
            // SYNTHESIZES these two into real Drawables, so nothing dangles.
            // Only the two names it can actually build are emitted; HOLO is
            // omitted on purpose (no reference to measure -- see that method).
            case "background":
                if (material || materialSmall) return "@android:drawable/btn_default_material";
                // ⚠ v1.0.936: classicSmall gets its OWN asset. AOSP's
                // styles.xml is explicit that the two differ --
                //     Widget.Button        -> @drawable/btn_default
                //     Widget.Button.Small  -> @drawable/btn_default_small
                // -- and the assets really are different sizes (48x96 vs 40x72
                // intrinsic at xhdpi, decoded with debug/ninepatch.py). Routing
                // Small to the LARGE drawable, as this line did, gave every
                // small classic button the full-size 48dp face: the style's one
                // and only contribution, applied at the wrong scale. Now that
                // Resources.macapkFrameworkDrawable synthesizes
                // btn_default_small, the correct name can be served without
                // dangling.
                if (classicSmall)              return "@android:drawable/btn_default_small";
                if (classic)                   return "@android:drawable/btn_default";
                // HOLO, added v1.0.900. The note above says Holo is omitted "no
                // reference to measure"; frozenbubble is that reference. Its
                // HomeScreen declares no theme and targets SDK 12, so
                // selectSystemTheme gives it Theme.Holo, and the AVD draws
                // btn_default_holo_dark for all four menu buttons.
                // Resources.macapkFrameworkDrawable now synthesizes it from the
                // measured values, so nothing dangles.
                //
                // DARK ONLY, on purpose. The alpha and colour below were solved
                // off a dark-Holo reference; the LIGHT asset has never been
                // measured and no fleet app is light-Holo, so emitting the same
                // shape for it would be inventing a value rather than mirroring
                // one. Returning null keeps light-Holo exactly as it is today.
                if ((holo || holoSmall) && !n.contains(".Light")) {
                    return "@android:drawable/btn_default_holo_dark";
                }
                return null;
            // CLASSIC ONLY, and that asymmetry is AOSP's, not ours (both fetched
            // 2026-08-03):
            //   styles.xml `Widget.Button`
            //       <item name="textColor">@color/primary_text_light</item>
            //   styles_material.xml `Widget.Material.Button`  -- NO textColor;
            //       Material buttons take the theme's.
            // It matters because the classic button FACE is light grey while a
            // classic DARK theme's textColorPrimary is WHITE. bobball is exactly
            // that: `Theme.NoTitleBar` (dark family) painting white labels onto
            // the light grey face this same table now supplies -- legible on
            // neither. `primary_text_light` is #000000 in our colour table.
            case "textColor":
                return (classic || classicSmall) ? "@android:color/primary_text_light" : null;
            // MATERIAL BUTTONS UPPERCASE THEIR LABEL, and we were rendering them
            // lowercase against an emulator that does not. Chain, fetched
            // 2026-08-05:
            //   styles_material.xml:537  Widget.Material.Button
            //       <item name="textAppearance">?attr/textAppearanceButton</item>
            //   themes_material.xml      textAppearanceButton -> TextAppearance.Material.Button
            //   styles_material.xml:251-256  TextAppearance.Material.Button
            //       <item name="textAllCaps">true</item>
            // Classic and Holo do NOT: classic's `Widget.Button` uses
            // `?attr/textAppearanceSmallInverse` and Holo's `?attr/textAppearanceMedium`,
            // neither of which sets textAllCaps -- so this is Material-only, like
            // the textColor row above is classic-only. Two families, two opposite
            // asymmetries, both AOSP's.
            case "textAllCaps":
                return (material || materialSmall) ? "true" : null;
            default:          return null;
        }
    }


    /** FROZEN framework attr ids for the widget-DEFAULT-STYLE attributes
     *  (`buttonStyle`, `textViewStyle`, `checkboxStyle`, ...), id -> name.
     *
     *  WHY THIS EXISTS (v1.0.869). `Resources.Theme.resolveDefStyleName` turns
     *  a `defStyleAttr` into a style by first asking `macapkTypeNameOf(id)` for
     *  the attribute's NAME -- and that only knows the APP's ids. Every widget
     *  built by the framework passes a FRAMEWORK attr there (AOSP's TextView
     *  ctor passes `com.android.internal.R.attr.textViewStyle` = 0x01010084,
     *  Button passes `buttonStyle` = 0x01010048), so the name came back null,
     *  the theme was never consulted, and `defStyle=null` -- the whole tier-3
     *  "widget default style" layer was dead for any app that does NOT
     *  explicitly delegate the attr in its own theme.
     *
     *  MEASURED: bobball's six menu buttons render as bare text with no face and
     *  no height, against an emulator drawing proper raised buttons. Its theme
     *  (`Theme.VPTheme` -> `@android:style/Theme.NoTitleBar.Fullscreen`)
     *  inherits `buttonStyle` from the framework rather than restating it, so
     *  the v1.0.858 `?android:<attr>` DELEGATION fix could never reach it --
     *  that fix handles a theme that says `<item name="buttonStyle">?android:
     *  buttonStyle</item>`, and bobball's simply says nothing at all.
     *
     *  Scoped DELIBERATELY to the 111 attrs whose name CONTAINS `Style`, rather
     *  than all 1571 public attrs: this is the exact consumer being fixed, and a
     *  complete table would make framework attr ids resolve in every other caller
     *  of macapkTypeNameOf too -- a much wider behavioural change than the one
     *  being measured here.
     *
     *  ⚠ v1.0.871 BUG FIX: the v1.0.869 generator matched names ENDING in
     *  `Style`, which silently dropped 19 real framework style attrs whose name
     *  merely CONTAINS it -- including `buttonStyleToggle` (0x0101004b, passed by
     *  BOTH android.widget.ToggleButton and AppCompatToggleButton, so their tier 3
     *  was dead exactly as buttonStyle's was before v869) and the whole
     *  `progressBarStyleHorizontal|Small|Large` family, which is the commonest
     *  ProgressBar spelling in the corpus. Matching on CONTAINS is why this is now
     *  111 rows. A suffix match on a naming convention is exactly the kind of
     *  almost-right filter that hides a fifth of a table.
     *
     *  Generated verbatim from AOSP core/res/res/values/public-final.xml
     *  (fetched 2026-08-03); that file's header states these ids are
     *  permanently frozen, which is what makes hardcoding them correct.
     *  Returns null for anything else, so every existing lookup is unchanged. */
    public static String macapkFrameworkStyleAttrName(int id) {
        switch (id) {
            case 0x01010048: return "buttonStyle";
            case 0x01010049: return "buttonStyleSmall";
            case 0x0101004a: return "buttonStyleInset";
            case 0x0101004b: return "buttonStyleToggle";
            case 0x0101005b: return "windowTitleStyle";
            case 0x0101005c: return "windowTitleBackgroundStyle";
            case 0x0101005d: return "alertDialogStyle";
            case 0x0101006a: return "absListViewStyle";
            case 0x0101006b: return "autoCompleteTextViewStyle";
            case 0x0101006c: return "checkboxStyle";
            case 0x0101006d: return "dropDownListViewStyle";
            case 0x0101006e: return "editTextStyle";
            case 0x0101006f: return "expandableListViewStyle";
            case 0x01010070: return "galleryStyle";
            case 0x01010071: return "gridViewStyle";
            case 0x01010072: return "imageButtonStyle";
            case 0x01010073: return "imageWellStyle";
            case 0x01010074: return "listViewStyle";
            case 0x01010075: return "listViewWhiteStyle";
            case 0x01010076: return "popupWindowStyle";
            case 0x01010077: return "progressBarStyle";
            case 0x01010078: return "progressBarStyleHorizontal";
            case 0x01010079: return "progressBarStyleSmall";
            case 0x0101007a: return "progressBarStyleLarge";
            case 0x0101007b: return "seekBarStyle";
            case 0x0101007c: return "ratingBarStyle";
            case 0x0101007d: return "ratingBarStyleSmall";
            case 0x0101007e: return "radioButtonStyle";
            case 0x0101007f: return "scrollbarStyle";
            case 0x01010080: return "scrollViewStyle";
            case 0x01010081: return "spinnerStyle";
            case 0x01010082: return "starStyle";
            case 0x01010083: return "tabWidgetStyle";
            case 0x01010084: return "textViewStyle";
            case 0x01010085: return "webViewStyle";
            case 0x01010086: return "dropDownItemStyle";
            case 0x01010087: return "spinnerDropDownItemStyle";
            case 0x01010089: return "spinnerItemStyle";
            case 0x0101008a: return "mapViewStyle";
            case 0x0101008b: return "preferenceScreenStyle";
            case 0x0101008c: return "preferenceCategoryStyle";
            case 0x0101008d: return "preferenceInformationStyle";
            case 0x0101008e: return "preferenceStyle";
            case 0x0101008f: return "checkBoxPreferenceStyle";
            case 0x01010090: return "yesNoPreferenceStyle";
            case 0x01010091: return "dialogPreferenceStyle";
            case 0x01010092: return "editTextPreferenceStyle";
            case 0x01010093: return "ringtonePreferenceStyle";
            case 0x01010097: return "textStyle";
            case 0x010100ae: return "windowAnimationStyle";
            case 0x01010208: return "listSeparatorTextViewStyle";
            case 0x0101020f: return "progressBarStyleSmallTitle";
            case 0x01010210: return "ratingBarStyleIndicator";
            case 0x01010230: return "candidatesTextStyleSpans";
            case 0x01010287: return "progressBarStyleInverse";
            case 0x01010288: return "progressBarStyleSmallInverse";
            case 0x01010289: return "progressBarStyleLargeInverse";
            case 0x010102ae: return "quickContactBadgeStyleWindowSmall";
            case 0x010102af: return "quickContactBadgeStyleWindowMedium";
            case 0x010102b0: return "quickContactBadgeStyleWindowLarge";
            case 0x010102b1: return "quickContactBadgeStyleSmallWindowSmall";
            case 0x010102b2: return "quickContactBadgeStyleSmallWindowMedium";
            case 0x010102b3: return "quickContactBadgeStyleSmallWindowLarge";
            case 0x010102b6: return "expandableListViewWhiteStyle";
            case 0x010102b9: return "webTextViewStyle";
            case 0x010102c8: return "textSelectHandleWindowStyle";
            case 0x010102c9: return "popupAnimationStyle";
            case 0x010102ce: return "actionBarStyle";
            case 0x010102d6: return "dropDownSpinnerStyle";
            case 0x010102d7: return "actionDropDownStyle";
            case 0x010102d8: return "actionButtonStyle";
            case 0x010102f3: return "actionBarTabStyle";
            case 0x010102f4: return "actionBarTabBarStyle";
            case 0x010102f5: return "actionBarTabTextStyle";
            case 0x010102f6: return "actionOverflowButtonStyle";
            case 0x010102f7: return "actionModeCloseButtonStyle";
            case 0x010102f8: return "titleTextStyle";
            case 0x010102f9: return "subtitleTextStyle";
            case 0x010102ff: return "listPopupWindowStyle";
            case 0x01010300: return "popupMenuStyle";
            case 0x01010318: return "indeterminateProgressStyle";
            case 0x0101032b: return "borderlessButtonStyle";
            case 0x0101032e: return "buttonBarStyle";
            case 0x0101032f: return "buttonBarButtonStyle";
            case 0x01010330: return "segmentedButtonStyle";
            case 0x01010353: return "horizontalScrollViewStyle";
            case 0x0101035c: return "datePickerStyle";
            case 0x0101035d: return "calendarViewStyle";
            case 0x0101036d: return "switchPreferenceStyle";
            case 0x01010373: return "textSuggestionsWindowStyle";
            case 0x01010388: return "actionBarSplitStyle";
            case 0x01010394: return "actionModeStyle";
            case 0x010103ad: return "mediaRouteButtonStyle";
            case 0x010103c8: return "checkedTextViewStyle";
            case 0x010103f7: return "fastScrollStyle";
            case 0x0101043e: return "stackViewStyle";
            case 0x0101043f: return "switchStyle";
            case 0x01010444: return "actionOverflowMenuStyle";
            case 0x01010480: return "searchViewStyle";
            case 0x01010489: return "buttonBarPositiveButtonStyle";
            case 0x0101048a: return "buttonBarNeutralButtonStyle";
            case 0x0101048b: return "buttonBarNegativeButtonStyle";
            case 0x0101049d: return "timePickerStyle";
            case 0x010104aa: return "toolbarStyle";
            case 0x010104f2: return "listMenuViewStyle";
            case 0x01010501: return "contextPopupMenuStyle";
            case 0x01010506: return "preferenceFragmentStyle";
            case 0x01010524: return "numberPickerStyle";
            case 0x0101053f: return "fontStyle";
            case 0x0101066e: return "lineBreakStyle";
            case 0x0101066f: return "lineBreakWordStyle";
            default: return null;
        }
    }

    private static final int FAM_UNKNOWN = 0, FAM_CLASSIC = 1, FAM_HOLO = 2, FAM_MATERIAL = 3;
    /** Segments AOSP actually ships under the `Theme.` namespace
     *  (frameworks/base core/res/res/values/themes*.xml + public-final.xml). */
    private static final java.util.Set<String> MACAPK_FRAMEWORK_THEME_SEGMENTS =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "Light", "Black", "NoTitleBar", "NoDisplay", "Fullscreen", "Translucent",
            "Wallpaper", "WallpaperSettings", "Dialog", "DialogWhenLarge", "Panel",
            "InputMethod", "WithActionBar", "GlobalSearch", "SearchBar", "Holo",
            "Material", "DeviceDefault", "NoActionBar", "DarkActionBar", "Alert"));
    /** Which framework family a theme NAME belongs to, or FAM_UNKNOWN when the
     *  name is not one AOSP ships (an app theme that reached us undecoded). */
    private static int macapkThemeFamily(String s) {
        if (s == null) return FAM_UNKNOWN;
        if (s.contains(".Material") || s.contains(".DeviceDefault")) return FAM_MATERIAL;
        if (s.contains(".Holo")) return FAM_HOLO;
        if (s.equals("Theme")) return FAM_CLASSIC;
        if (!s.startsWith("Theme.")) return FAM_UNKNOWN;
        // Every remaining segment must be a framework one for this to be a
        // framework theme (`Theme.Black.NoTitleBar` yes, `Theme.App.Blue` no).
        for (String seg : s.substring(6).split("\\.")) {
            if (!MACAPK_FRAMEWORK_THEME_SEGMENTS.contains(seg)) return FAM_UNKNOWN;
        }
        return FAM_CLASSIC;
    }
    /** Mirror of cfgNight for the static theme-attr table (the instance field
     *  is set from Configuration.uiMode in the ctor). */
    private static volatile boolean macapkCfgNightStatic = false;

    private static String frameworkThemeAttr(String plainStyleName, String attrName) {
        String s = plainStyleName;
        // Only framework THEMES answer theme attributes. Widget/TextAppearance
        // styles must keep falling through to null.
        if (s == null || !(s.equals("Theme") || s.startsWith("Theme."))) return null;
        String a = attrName;
        if (a.startsWith("android:attr/")) a = a.substring(13);
        else if (a.startsWith("android:"))  a = a.substring(8);
        // AOSP spells the light variants ".Light" / ".DayNight"; everything
        // else inherits the dark palette.
        final boolean light = s.contains(".Light")
            || (s.contains(".DayNight") ? !macapkCfgNightStatic : false);
        // lane design 1, 2026-08-02: which FRAMEWORK THEME FAMILY is this?
        // AOSP has four (classic `Theme`, `Theme.Holo`, `Theme.Material`, and
        // `Theme.DeviceDefault` which styles_device_defaults.xml delegates
        // straight to Material). They differ in widget chrome AND in text
        // polarity, and until now every `Theme.*` name got Material's answers
        // with a `.Light`-substring sniff for polarity.
        //
        // TWO-TIER RECOGNIZER, deliberately conservative: a name whose segment
        // after `Theme.` is a KNOWN framework one gets full family treatment;
        // anything else (an app theme that merely happens to be called
        // "Theme.App" and reached here undecoded) keeps EXACTLY the previous
        // answers. That is what makes this safe to land on its own -- the apps
        // the old sniff got right cannot change, and a decoded app theme never
        // reaches this method at all (styleByName is consulted first).
        final int family = macapkThemeFamily(s);
        switch (a) {
            // --- identical in Theme.Material and Theme.Material.Light ---
            case "selectableItemBackground":
                return "@android:drawable/item_background_material";
            case "selectableItemBackgroundBorderless":
                return "@android:drawable/item_background_borderless_material";
            case "actionBarItemBackground":
                return "@android:drawable/action_bar_item_background_material";
            case "listDivider":
                return "@android:drawable/list_divider_material";
            // THE EDITTEXT UNDERLINE'S FIRST HALF (ATTRSTYLE lane, 2026-08-13).
            // Every AppCompat theme from Base.V22 up declares
            //     <item name="editTextBackground">?android:editTextBackground</item>
            // -- it DELEGATES the field background to the platform, exactly the
            // shape v1.0.858 documented for buttonStyle. With no row here that
            // delegation resolved to nothing, so the field had no background:
            // no underline and, more importantly for layout, none of the 42px of
            // padding the real drawable reports. Adding the drawable synthesizer
            // alone would have changed NOTHING, because nothing ever asked for it.
            //
            // themes_material.xml:93 (Theme.Material) and :465 (Theme.Material.Light)
            //     <item name="editTextBackground">@drawable/edit_text_material</item>
            // -- the SAME value in both, hence no light/dark split.
            //
            // MATERIAL ONLY, deliberately. The other families point at assets we
            // do not synthesize -- themes.xml:101 `@drawable/edit_text`,
            // themes_holo.xml:106/474 `@drawable/edit_text_holo_dark|light` -- and
            // this table's addition rule is that a row pointing at a framework
            // drawable we do not ship is WORSE than no row: it replaces a working
            // (here: absent) value with a dangling reference. Add those the day
            // their art is measured, as btn_default and btn_default_normal_holo_dark
            // were.
            case "editTextBackground":
                return (MACAPK_ATTRSTYLE && family == FAM_MATERIAL)
                    ? "@android:drawable/edit_text_material" : null;
            // THE CANARY MATERIAL'S ThemeEnforcement CHECKS (v1.0.927). Both
            // Theme.Material and Theme.Material.Light set it to the same value,
            // so there is no family split here:
            //   themes_material.xml:50-51 (Theme.Material) and :335-336
            //   (Theme.Material.Light)
            //       <item name="textAppearance">@style/TextAppearance.Material</item>
            // An app theme chain that terminates in the platform theme -- which
            // is nearly all of them -- inherits it and never redeclares it, so
            // this row is the ONLY place it can come from with the real platform
            // table gated off. Missing it made
            // `ThemeEnforcement.checkTextAppearance` read -1 and throw
            // `IllegalArgumentException: This component requires that you specify
            // a valid TextAppearance attribute`, out of the CONSTRUCTOR of every
            // BottomNavigationView / NavigationRailView -- which our LayoutInflater
            // then replaced with a FrameLayout placeholder, whose cast in the app's
            // own onCreate aborted the whole activity. deskclock and antennapod
            // rendered BLANK because of this one absent row.
            // ⚠ SPLIT BY FAMILY v1.0.931. v930 returned the Material value
            // unconditionally, ignoring `family` -- which would have served a
            // MATERIAL text appearance to a CLASSIC-themed app the moment tier 5
            // started reaching those apps (bobball's `Theme.NoTitleBar` resolves
            // FAM_CLASSIC). Each family declares its own, fetched 2026-08-09:
            //   themes.xml:58,729        Theme / Theme.Light
            //       <item name="textAppearance">@style/TextAppearance</item>
            //   themes_holo.xml:68,923   Theme.Holo
            //       <item name="textAppearance">@style/TextAppearance.Holo</item>
            //   themes_holo.xml:436,1060 Theme.Holo.Light
            //       <item name="textAppearance">@style/TextAppearance.Holo.Light</item>
            //   themes_material.xml:50-51,335-336  Theme.Material[.Light]
            //       <item name="textAppearance">@style/TextAppearance.Material</item>
            // The Holo LIGHT variant differs from dark, unlike Material's, so
            // this cannot collapse to one row per family without losing it.
            case "textAppearance":
                if (family == FAM_MATERIAL) return "@android:style/TextAppearance.Material";
                if (family == FAM_HOLO)
                    return s.contains("Light") ? "@android:style/TextAppearance.Holo.Light"
                                               : "@android:style/TextAppearance.Holo";
                return "@android:style/TextAppearance";
            case "textAppearanceInverse":
                if (family == FAM_MATERIAL)
                    return "@android:style/TextAppearance.Material.Inverse";
                if (family == FAM_HOLO)
                    return s.contains("Light") ? "@android:style/TextAppearance.Holo.Light.Inverse"
                                               : "@android:style/TextAppearance.Holo.Inverse";
                return "@android:style/TextAppearance.Inverse";
            // SYSTEM BAR rows (v1.0.900), the inputs AOSP's
            // DecorView.calculateBarColor reads. themes_material.xml's
            // Theme.Material[.Light] set windowDrawsSystemBarBackgrounds=true and
            // statusBarColor=?attr/colorPrimaryDark; the legacy themes.xml Theme
            // sets false and @color/black, and Theme.Holo inherits Theme's false.
            // That false is not a detail: it is calculateBarColor's branch 2,
            // which returns BLACK regardless of any colour the theme declares,
            // and it is why a legacy-themed app shows a black bar on a device
            // even when it sets colorPrimaryDark.
            case "windowDrawsSystemBarBackgrounds":
                return family == FAM_MATERIAL ? "true" : "false";
            case "statusBarColor":
                return family == FAM_MATERIAL ? "?attr/colorPrimaryDark"
                                              : "@android:color/black";
            // ⚠ NOT the transparent default. PhoneWindow.generateLayout resolves
            // an UNSET navigationBarColor through navigation_bar_default
            // (#00808080) to navigation_bar_compatible (@android:color/black), so
            // the value a device actually paints for "the app left this alone" is
            // OPAQUE BLACK. Returning the transparent default here would flip the
            // nav bar of every app that leaves it alone to the window background.
            case "navigationBarColor":
                return "@android:color/black";
            // THE THEME ROUTE TO A HIDDEN STATUS BAR (v1.0.962). The OTHER
            // route -- setSystemUiVisibility's HIDE bits -- is handled in
            // Window.macapkControlInsetsForCompatibility; this is the one an
            // app takes by declaring a `.Fullscreen` platform theme and never
            // calling anything at all (Hill Climb Racing 1.8.1 has ZERO
            // setSystemUiVisibility callers in its bytecode, its .so or its
            // base.apk, yet is fullscreen on a device).
            //
            // AOSP core/res/res/values/themes.xml, all FOUR `.Fullscreen`
            // platform themes and no others:
            //   :480 Theme.NoTitleBar.Fullscreen
            //   :600 Theme.Light.NoTitleBar.Fullscreen
            //   :622 Theme.Black.NoTitleBar.Fullscreen
            //   :694 Theme.Translucent.NoTitleBar.Fullscreen
            //       <item name="windowFullscreen">true</item>
            // and themes_material.xml adds exactly two more, plus two that
            // INHERIT it (verified 2026-08-12; no NON-Fullscreen Material theme
            // declares the attribute at all):
            //   Theme.Material.NoActionBar.Fullscreen        declares true
            //   Theme.Material.Light.NoActionBar.Fullscreen  declares true
            //   Theme.Material[.Light].Dialog.Presentation   parent = the above
            // so the row keys on the style NAME, not on the family. The
            // `.Dialog.Presentation` clause is the ONE framework case whose name
            // does not say `.Fullscreen` while the theme is (a Presentation
            // covers a secondary display, so every family's variant inherits a
            // fullscreen parent). Nothing in the corpus declares it; it is here
            // so the table is not knowingly wrong.
            // PhoneWindow.generateLayout:2541-2543 turns the attribute into
            // FLAG_FULLSCREEN, which is mirrored in Activity's
            // macapkGenerateLayout.
            //
            // NULL, not "false", for every other framework theme: themes.xml
            // does not declare the attribute anywhere else, so absent is the
            // honest answer and the caller's own `def` (false, matching the
            // styleable default AOSP passes) applies. Returning "false" here
            // would additionally out-rank nothing but would claim the platform
            // says something it does not.
            //
            // An app style that inherits a `.Fullscreen` theme reaches this
            // through the parent walk (bouncy's `Theme.VPTheme` and flappy's
            // `AppTheme` both do), and an app style that RE-DECLARES
            // windowFullscreen wins, because resolveAttrInThemeImpl checks its
            // own items before recursing to the parent.
            case "windowFullscreen":
                return (s.contains(".Fullscreen")
                        || s.endsWith(".Dialog.Presentation")) ? "true" : null;
            // themes_material.xml:183 (Theme.Material) and :556
            // (Theme.Material.Light) both carry
            //     <item name="windowActivityTransitions">true</item>
            // and the legacy `Theme` / `Theme.Holo` families predate the
            // attribute entirely, so they keep the AOSP default of false by
            // falling through to null here. PhoneWindow.generateLayout:2456-2458
            // turns a true into requestFeature(FEATURE_ACTIVITY_TRANSITIONS),
            // which is the only reason this row exists: without it a
            // Material-themed window answers hasFeature(FEATURE_ACTIVITY_
            // TRANSITIONS) = false where every device answers true.
            //
            // Deliberately NOT accompanied by windowActionBar/windowNoTitle,
            // whose real values are @bool/config_windowActionBarSupported /
            // @bool/config_windowNoTitleDefault: those two are read by
            // TypedArray.getBoolean's AppCompat compensation path, so answering
            // them here changes which decor layout AppCompat inflates fleet-wide
            // (see the long note at TypedArray.java:526 -- that flip has been
            // measured and is visually inert at best). They need that
            // measurement, not a drive-by row.
            case "windowActivityTransitions":
                return family == FAM_MATERIAL ? "true" : null;
            // themes_material.xml:
            //   <item name="listChoiceIndicatorSingle">@drawable/btn_radio_material_anim</item>
            //   <item name="listChoiceIndicatorMultiple">@drawable/btn_check_material_anim</item>
            // Those two are framework ANIMATED-VECTOR drawables we do not ship, so
            // returning their real names would dangle and paint nothing. We return
            // the APP'S OWN BUNDLED TWIN instead -- exactly the shape this method
            // already uses for the action-bar overflow icon, and for the same
            // reason: AppCompat ships a pixel-equivalent asset in every app that
            // uses it (30 of the corpus APKs carry abc_btn_radio_material.xml), and
            // an app that does NOT bundle it resolves to id 0 downstream and
            // nothing is installed, so this cannot regress a non-AppCompat app.
            //
            // ⚠ These assets are MASKS, not finished art: the shipped PNGs are
            // pure black on transparent, and AppCompat pairs them at runtime with
            // @color/abc_tint_btn_checkable. Anything that installs one MUST also
            // apply that tint or it paints a black blob AND grows the row by the
            // drawable's 32dp intrinsic height. LayoutInflater enforces the
            // pairing by refusing to install when the tint does not resolve.
            case "listChoiceIndicatorSingle":
                return "@drawable/abc_btn_radio_material";
            case "listChoiceIndicatorMultiple":
                return "@drawable/abc_btn_check_material";
            case "homeAsUpIndicator":
                return "@android:drawable/ic_ab_back_material";
            case "dividerHorizontal":
            case "dividerVertical":
                return "?android:listDivider";
            case "windowBackground":
            case "panelBackground":
                return "?android:colorBackground";
            case "colorControlNormal":
                return "?android:textColorSecondary";
            case "colorControlActivated":
                return "?android:colorAccent";
            // lane design 1, 2026-08-02: answer text polarity from the THEME's
            // family, not from the host appearance. AOSP wires each family to
            // its own colour (themes.xml:61 classic dark -> primary_text_dark;
            // :517 classic light -> primary_text_light; themes_holo.xml:71 ->
            // primary_text_holo_dark; themes_material.xml:63 -> the
            // text_color_primary CSL whose enabled row is
            // primary_text_default_material_{dark,light}).
            // The old single row resolved to `cfgNight ? white : #DE000000`,
            // i.e. it followed the macOS appearance -- so an app on a DARK
            // legacy theme (bobball/bouncy/frozenbubble, all
            // Theme[.Black].NoTitleBar) painted near-BLACK text on its black
            // window and the whole menu was invisible. The cfgNight rows stay
            // in resolveColorRef for direct `@android:color/text_color_primary`
            // references and for the FAM_UNKNOWN tier, which are genuinely
            // appearance-following.
            case "textColorPrimary":
                switch (family) {
                    case FAM_CLASSIC:  return light ? "@android:color/primary_text_light"
                                                    : "@android:color/primary_text_dark";
                    case FAM_HOLO:     return light ? "@android:color/primary_text_holo_light"
                                                    : "@android:color/primary_text_holo_dark";
                    case FAM_MATERIAL: return light ? "@android:color/primary_text_default_material_light"
                                                    : "@android:color/primary_text_default_material_dark";
                    default:           return "@android:color/text_color_primary";
                }
            case "textColorPrimaryDisableOnly":
            case "textColorPrimaryNoDisable":
                return "?android:textColorPrimary";
            case "textColorSecondary":
                switch (family) {
                    case FAM_CLASSIC:  return light ? "@android:color/secondary_text_light"
                                                    : "@android:color/secondary_text_dark";
                    case FAM_HOLO:     return light ? "@android:color/secondary_text_holo_light"
                                                    : "@android:color/secondary_text_holo_dark";
                    case FAM_MATERIAL: return light ? "@android:color/secondary_text_default_material_light"
                                                    : "@android:color/secondary_text_default_material_dark";
                    default:           return "@android:color/text_color_secondary";
                }
            case "actionBarSize":
                // AOSP core/res/res/values/dimens_material.xml:
                //   <dimen name="action_bar_default_height_material">56dp</dimen>
                // Emitted as the LITERAL, not `@android:dimen/...`, because we
                // ship NO framework dimen table and ALL FOUR dimension parsers
                // reject the framework namespace outright:
                //   Resources.macapkResolveDimenRef  -> getIdentifier(app table) -> 0 -> null
                //   TypedArray.macapkParseDimenToPx  -> `if (v.startsWith("@")) return null`
                //   LayoutInflater.parseDimensionPx  -> "// @android:dimen/* ... unknown here" -> null
                //   Theme.resolveAttribute           -> TYPE_REFERENCE, resourceId 0
                // A dimen is a LEAF VALUE, not a resource needing a lookup --
                // AOSP's own file literally contains `56dp` -- so the literal IS
                // the resolved answer, and emitting it makes the platform default
                // byte-identical to an app writing `<item name="actionBarSize">
                // 56dip</item>` itself. Emitting the reference instead created a
                // dangling name of exactly the kind that caused v1.0.866.
                //
                // MEASURED COST of the dangle: AppCompat's own
                // `Base.V21.Theme.AppCompat[.Light]` declares
                // `<item name="actionBarSize">?android:actionBarSize</item>` and
                // is the CHILD of `Base.V7...`, which declares the servable
                // `@dimen/abc_action_bar_default_height_material` -- so the child
                // wins and delegates to us, and we answered null. It dead-ends in
                // 24 of 43 corpus apps; 9 use `?actionBarSize` in a layout.
                // NewPipe is the clean case: its `toolbar_layout.xml` uses it for
                // BOTH `layout_height` and `minHeight`, and `activity_main.xml`
                // uses it for the content's `layout_marginTop`. Null meant the
                // Toolbar fell back to wrap_content (96px around a 48dp nav
                // button instead of 112px) AND the whole feed lost its 112px top
                // offset -- the toolbar and the tab strip drew at the SAME y,
                // superimposed, with every row below 112px out of phase.
                // Two independent lanes converged on this line.
                return "56dip";
            // lane c4, 2026-08-02: the WIDGET-STYLE attrs a framework theme
            // binds. An AppCompat app's own theme routinely DELEGATES these to
            // the platform (opensudoku's styles.xml:1467 is literally
            // `<item name="buttonStyle">?android:buttonStyle</item>`), so with
            // no row here the delegation dead-ends and the widget is built with
            // NO default chrome at all.
            // Measured victim: opensudoku's menu buttons laid out 57px tall
            // against the emulator's 96px, because `Widget.Material.Button`'s
            // `minHeight` 48dip never arrived (MACAPK_TRACE_DEFSTYLE printed
            // `defStyle=null ... minH=-1`). 48dip * density 2 = 96px, which is
            // exactly the emulator's measured button pitch.
            // Cited: themes_material.xml `Theme.Material` ->
            //   <item name="buttonStyle">@style/Widget.Material.Button</item>
            // and `Theme.Material.Light` -> `@style/Widget.Material.Light.Button`
            // (which AOSP declares as a bare alias of the former).
            case "buttonStyle":
                switch (family) {
                    case FAM_HOLO:     return light ? "@android:style/Widget.Holo.Light.Button"
                                                    : "@android:style/Widget.Holo.Button";
                    case FAM_MATERIAL: return light ? "@android:style/Widget.Material.Light.Button"
                                                    : "@android:style/Widget.Material.Button";
                    // Classic `Theme` has no per-polarity split for this one.
                    case FAM_CLASSIC:  return "@android:style/Widget.Button";
                    default:           return null;
                }
            case "borderlessButtonStyle":
                return family == FAM_MATERIAL
                    ? "@android:style/Widget.Material.Button.Borderless" : null;
            // radioButtonStyle / checkboxStyle, added v1.0.906. An AppCompat theme
            // routinely delegates these straight to the platform
            // (unitconverter's AppTheme declares
            // `radioButtonStyle = ?android:radioButtonStyle`), and with no row here
            // that delegation dead-ended exactly as `buttonStyle` did before
            // v1.0.858 -- so the indicator attribute never arrived and every radio
            // and checkbox drew no circle at all.
            //   themes.xml            -> @style/Widget.CompoundButton.RadioButton
            //   themes_material.xml   -> @style/Widget.Material[.Light].CompoundButton.RadioButton
            //   themes_holo.xml       -> @style/Widget.Holo[.Light].CompoundButton.RadioButton
            // Holo is served through the Material rows because the only thing we
            // take from the chain is the `button` indicator, which is identical
            // across the families; the family-specific pieces of those styles are
            // the framework ripple background we deliberately do not serve.
            case "radioButtonStyle":
                switch (family) {
                    case FAM_MATERIAL:
                    case FAM_HOLO:
                        return light ? "@android:style/Widget.Material.Light.CompoundButton.RadioButton"
                                     : "@android:style/Widget.Material.CompoundButton.RadioButton";
                    case FAM_CLASSIC:  return "@android:style/Widget.CompoundButton.RadioButton";
                    default:           return null;
                }
            case "checkboxStyle":
                switch (family) {
                    case FAM_MATERIAL:
                    case FAM_HOLO:
                        return light ? "@android:style/Widget.Material.Light.CompoundButton.CheckBox"
                                     : "@android:style/Widget.Material.CompoundButton.CheckBox";
                    case FAM_CLASSIC:  return "@android:style/Widget.CompoundButton.CheckBox";
                    default:           return null;
                }
            // buttonStyleSmall, added v1.0.867 -- anuto's tutorial panel uses
            // `style="?android:buttonStyleSmall"` on both its buttons, and with
            // no row the delegation dead-ends exactly as `buttonStyle` did
            // before v1.0.858. Bindings fetched 2026-08-03:
            //   themes.xml:114          -> @style/Widget.Button.Small
            //   themes_material.xml:106 -> @style/Widget.Material.Button.Small
            //                      :484 -> @style/Widget.Material.Light.Button.Small
            //   themes_holo.xml:123/487 -> @style/Widget.Holo[.Light].Button.Small
            // ⚠ The families genuinely differ, as with buttonStyle: Material's
            // and Holo's `.Small` set minHeight/minWidth 48dip, while CLASSIC's
            // `Widget.Button.Small` sets ONLY `background=@drawable/btn_default_small`
            // -- a framework 9-patch we do not ship. So this row is a geometric
            // no-op for classic-theme apps (anuto is one), and deliberately so:
            // serving a dangling `@android:drawable/...` would replace an app's
            // own working background with nothing. Drawing a synthesized default
            // button face is a separate decision, tracked in Current_State.
            case "buttonStyleSmall":
                switch (family) {
                    case FAM_HOLO:     return light ? "@android:style/Widget.Holo.Light.Button.Small"
                                                    : "@android:style/Widget.Holo.Button.Small";
                    case FAM_MATERIAL: return light ? "@android:style/Widget.Material.Light.Button.Small"
                                                    : "@android:style/Widget.Material.Button.Small";
                    case FAM_CLASSIC:  return "@android:style/Widget.Button.Small";
                    default:           return null;
                }
            // --- light/dark pairs ---
            case "colorControlHighlight":
                return light ? "@android:color/ripple_material_light"
                             : "@android:color/ripple_material_dark";
            case "colorAccent":
                return light ? "@android:color/accent_material_light"
                             : "@android:color/accent_material_dark";
            case "colorPrimary":
                return light ? "@android:color/primary_material_light"
                             : "@android:color/primary_material_dark";
            case "colorPrimaryDark":
                return light ? "@android:color/primary_dark_material_light"
                             : "@android:color/primary_dark_material_dark";
            case "colorBackground":
                return light ? "@android:color/background_material_light"
                             : "@android:color/background_material_dark";
            // The FLOATING-window background -- what a Dialog's panel is filled
            // with. themes_material.xml:48/424 binds it per polarity, and
            // `Theme.Material.BaseDialog` (:940, :1126) then rebinds the dialog
            // window's own `colorBackground` to `?attr/colorBackgroundFloating`,
            // which `@drawable/dialog_background_material` paints as its solid.
            // Dialog.DialogWindow reads this to build its decor background.
            case "colorBackgroundFloating":
                return light ? "@android:color/background_floating_material_light"
                             : "@android:color/background_floating_material_dark";
            case "colorForeground":
                return light ? "@android:color/foreground_material_light"
                             : "@android:color/foreground_material_dark";
            case "colorButtonNormal":
                return light ? "@android:color/btn_default_material_light"
                             : "@android:color/btn_default_material_dark";
            case "disabledAlpha":
                return light ? "@android:dimen/disabled_alpha_material_light"
                             : "@android:dimen/disabled_alpha_material_dark";
            default:
                return null;
        }
    }

    /** Resolve a theme attribute to a packed ARGB color, walking
     *  references as needed. Returns null if the attribute isn't
     *  declared anywhere in the cascade or the value isn't a color.
     *  Generic over any APK's color tokens. */
    public Integer resolveAttrToColor(String themeStyleName, String attrName) {
        String raw = resolveAttrInTheme(themeStyleName, attrName);
        if (raw == null) return null;
        // Pass themeStyleName as the ANCHOR. Until v1.0.1022 this method took a
        // theme name, resolved the attribute in it, and then handed the raw
        // value to a resolveColorRef that re-anchored every `?attr` hop on the
        // process-wide applicationThemeName -- so the parameter was a lie for
        // exactly the values that needed it. AOSP's Theme::GetAttribute
        // (AssetManager2.cpp:1732) chases attribute indirection inside ONE
        // theme's merged table; the hop must stay in the theme we were asked
        // about. Measured on newpipe with debug/levelprobe.sh:
        //   LightTheme / colorControlActivated -> `?colorPrimary` -> NULL
        // because LevelProbe never sets an application theme, so the `?` branch
        // had no global to borrow. The sibling resolveAttrInThemeImpl already
        // took a rootTheme parameter for this exact reason at v1.0.913; the
        // value-conversion half never got it.
        return resolveColorRef(raw, new java.util.HashSet<>(), themeStyleName);
    }

    /** THE single entry point for "turn one resources-XML colour expression
     *  into an ARGB int". Handles `#hex`, `@color/X` (including a
     *  reference chain), `@android:color/*` (the framework colour table AND
     *  the Material You `@android:color/system_*` palette), and `?attr/Y`
     *  theme indirection. Returns null when the expression names nothing we
     *  can resolve.
     *
     *  Made public in v1.0.834 so `Resources.macapkResolveColorString` --
     *  the XML-drawable / TypedArray.getColor path -- routes through the SAME
     *  table instead of its own `getIdentifier(name,"color",null)` lookup
     *  against the APP's table only. That lookup could never resolve
     *  `@android:color/white` (there is no app resource by that name), so
     *  `<solid android:color="@android:color/white"/>` fell through to the
     *  caller's default. This is the last consumer that had its own idea of
     *  the framework colour table. */
    public Integer macapkResolveColorExpr(String raw) {
        return resolveColorRef(raw, new java.util.HashSet<>());
    }

    /** Resolve a single value expression to a color. Handles hex
     *  literals, `@color/X`, `?attr/Y` references (recurses on the
     *  current application theme), and a small set of `@android:color/*`
     *  framework constants. */
    /** Old 2-arg form: NO anchor, so `?attr` hops resolve against the
     *  process-wide applicationThemeName exactly as before. Every pre-existing
     *  caller keeps its behaviour byte-for-byte; only callers that genuinely
     *  know which theme they are resolving in should pass one. Deliberately
     *  additive -- a blanket change to this shared helper is the shape that
     *  zeroed opencalculator's 184,033 keypad pixels at v1.0.894
     *  (docs/refuted-approaches.md). */
    private Integer resolveColorRef(String raw, java.util.Set<String> visited) {
        return resolveColorRef(raw, visited, null);
    }

    private Integer resolveColorRef(String raw, java.util.Set<String> visited,
                                    String anchorTheme) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.isEmpty()) return null;
        if (!visited.add(raw)) return null;  // cycle guard
        if (raw.startsWith("#")) return parseHexColor(raw);
        // "@0x01......": a compiled FRAMEWORK reference (BinaryXmlParser's
        // rendering of TYPE_REFERENCE, and macapkFwItemText's rendering of
        // any non-color/string target). Reached by the framework-drawable
        // path: a compiled <vector android:tint="@0x01060128"> resolves its
        // tint here. Routed through macapkFwColorById, which follows
        // reference chains, settles ?attr leaves against the app theme, and
        // flattens a res/color selector to its default row -- all existing,
        // measured machinery. Scoped to package 0x01 under the gate so the
        // gate-OFF path and app-id behaviour are byte-identical (an app-id
        // "@0x7f..." here answered null before and still does; app drawable
        // XML is apktool-decoded TEXT and never carries this spelling).
        if (raw.startsWith("@0x") || raw.startsWith("@0X")) {
            try {
                int rid = (int) Long.parseLong(raw.substring(3), 16);
                if ((rid >>> 24) == 0x01 && macapkFwView != null) {
                    macapkPublishValueOrigin(rid);
                    return macapkFwColorById(rid, visited, 0);
                }
            } catch (NumberFormatException ignored) {}
            return null;
        }
        if (raw.startsWith("@color/")) {
            String name = raw.substring(7);
            // v1.0.917: dereferencing "@color/name" is one ResolveReference
            // iteration; publish the id being followed so the consumer sees
            // the LAST one (a chain of refs publishes each in turn). The
            // direct nameToId read never fabricates an id.
            macapkPublishValueOrigin(macapkNameToIdDirect("color", name));
            Integer direct = colorValues.get(name);
            if (direct != null) return direct;
            String ref = colorRefs.get(name);
            if (ref != null) return resolveColorRef(ref, visited, anchorTheme);
            // Same reason as getColorByName: the name may denote a file-based
            // res/color/<name>.xml selector rather than a values <color>. This is
            // the branch the THEME-ATTRIBUTE path uses (Theme.resolveAttrColor ->
            // macapkResolveAttrColorInCascade -> macapkResolveColorExpr), which is
            // how ?android:textColorPrimary reaches
            // @color/m3_default_color_primary_text on every Material3 app.
            return colorFromSelectorFile(name, visited);
        }
        if (raw.startsWith("@android:color/")) {
            String name = raw.substring(15);
            // Under the gate the platform table answers first (typed rows,
            // reference chains, and file-backed selector defaults); the
            // transcribed rows below stay as the fallback and the entire
            // gate-OFF path. EXCEPTION: the Material You system_* palette
            // stays on the DYNAMIC path below. On a device those entries are
            // rewritten by a runtime resource overlay, so the table carries
            // only static placeholder defaults; serving them here shadowed
            // the computed dynamic palette (measured on deskclock:
            // md_theme_primary #ff475d92 -> #ff4b5d8b, 2026-08-08).
            if (macapkFwView != null && !name.startsWith("system_")) {
                Integer fw = macapkFwColorByName(name, visited);
                if (fw != null) return fw;
            }
            switch (name) {
                case "transparent": return 0x00000000;
                case "white":       return 0xFFFFFFFF;
                case "black":       return 0xFF000000;
                case "darker_gray": return 0xFFAAAAAA;
                case "background_light":      return 0xFFFFFFFF;
                case "background_dark":       return 0xFF000000;
                case "primary_text_light":    return 0xFF000000;
                case "primary_text_dark":     return 0xFFFFFFFF;
                case "secondary_text_light":  return 0xFF424242;
                case "secondary_text_dark":   return 0xFFB3B3B3;
                // lane layout, 2026-08-01: text_color_primary/secondary were
                // MISSING, which broke the framework chain this table exists to
                // serve: frameworkStyleAttr maps `colorControlNormal` ->
                // `?android:textColorSecondary` -> `@android:color/
                // text_color_secondary` -> (nothing) -> null. Every AppCompat/
                // Material icon authored as `<vector android:tint=
                // "?colorControlNormal">` therefore got NO tint. AOSP's real
                // values are ColorStateLists over ?textColorPrimary with
                // disabled alpha; the ENABLED entry is what a tint read wants,
                // and it is theme-polarity dependent (Theme.Material vs
                // .Material.Light) -- so answer from the same `light` flag
                // frameworkStyleAttr uses rather than a single constant.
                // Values are AOSP's material palette:
                // primary_text_default_material_{light,dark} = #de000000 /
                // #ffffffff, secondary_text_default_material_{light,dark} =
                // #8a000000 / #b3ffffff.
                case "text_color_primary":
                    return cfgNight ? 0xFFFFFFFF : 0xDE000000;
                case "text_color_secondary":
                    return cfgNight ? 0xB3FFFFFF : 0x8A000000;
                case "primary_text_material_light":   return 0xDE000000;
                case "primary_text_material_dark":    return 0xFFFFFFFF;
                case "secondary_text_material_light": return 0x8A000000;
                case "secondary_text_material_dark":  return 0xB3FFFFFF;
                // lane design 1, 2026-08-02: the per-FAMILY text colours the
                // polarity-aware theme rows resolve to. Values are AOSP's,
                // cited per family:
                //  classic  colors.xml:44,46-48  (primary_text_{light,dark}
                //           chain through bright_foreground_{light,dark} to
                //           background_{dark,light}) -> #000000 / #FFFFFF
                //  holo     colors_holo.xml:27-30 -> #000000 / #FFF3F3F3
                //  material colors_material.xml:59,64 -> #DE000000 / #FFFFFFFF
                // (primary_text_{light,dark} and secondary_text_{light,dark}
                // already have rows above -- classic values, unchanged.)
                case "primary_text_holo_light":       return 0xFF000000;
                case "primary_text_holo_dark":        return 0xFFF3F3F3;
                case "secondary_text_holo_light":     return 0xFF444444;
                case "secondary_text_holo_dark":      return 0xFFBEBEBE;
                case "primary_text_default_material_light":   return 0xDE000000;
                case "primary_text_default_material_dark":    return 0xFFFFFFFF;
                case "secondary_text_default_material_light": return 0x8A000000;
                case "secondary_text_default_material_dark":  return 0xB3FFFFFF;
                // ?colorButtonNormal resolves to these (colors_material.xml:45-46)
                // and had no rows, so it returned null.
                case "button_material_light":         return 0xFFD6D7D7;
                case "button_material_dark":          return 0xFF5A595B;
                // lane canvas, 2026-08-03: THE PLATFORM THEME'S OWN PALETTE.
                //
                // frameworkThemeAttr (the pass-3 platform theme table) answers
                // `colorBackground` with `@android:color/background_material_dark`,
                // `colorAccent` with `accent_material_*`, and so on -- but NONE of
                // those names had a row here, so every one of them resolved to
                // null. The table was emitting names its own resolver could not
                // read: a theme lookup that "succeeded" still returned nothing, and
                // each caller silently took its fallback.
                //
                // MEASURED VICTIM (unitconverter 5.7.3, 2026-08-03): its manifest
                // theme is `AppTheme -> AppBaseTheme -> Theme.AppCompat.NoActionBar`
                // -- the DARK AppCompat theme (the app ships a separate
                // `AppThemeLight` it applies at runtime). That chain is app-bundled
                // down to `Platform.V21.AppCompat`, whose parent is the framework
                // `@android:style/Theme.Material.NoActionBar`, and AppCompat
                // deliberately never declares `colorBackground` itself -- the
                // platform theme owns it. So ViewCanvasDriver's
                //   macapkThemeColor("colorBackground", 0xFFFFFFFF)
                // took the WHITE fallback and we cleared the frame to white while
                // the emulator drew #303030. Scored same=2.41% against the
                // emulator, the worst pair in the whole fleet, essentially all of
                // it this one missing row.
                //
                // Values fetched verbatim 2026-08-03 from aosp-mirror
                // platform_frameworks_base@main
                // core/res/res/values/colors_material.xml, resolving each
                // reference chain to its literal:
                //   :22 background_material_dark  -> @color/material_grey_850  :83 #ff303030
                //   :23 background_material_light -> @color/material_grey_50   :89 #fffafafa
                //   :42 accent_material_light -> @color/material_deep_teal_500 :94 #ff008577
                //   :43 accent_material_dark  -> @color/material_deep_teal_200 :92 #ff80cbc4
                //   :28 primary_material_light -> @color/material_grey_100     :88 #fff5f5f5
                //   :27 primary_material_dark  -> @color/material_grey_900     :82 #ff212121
                //   :32 primary_dark_material_light -> @color/material_grey_600 :85 #ff757575
                //   :31 primary_dark_material_dark  -> @color/black             #ff000000
                //   :20 foreground_material_light -> @color/black              #ff000000
                //   :19 foreground_material_dark  -> @color/white              #ffffffff
                // NOTE the light background is #FAFAFA, NOT #FFFFFF -- the old
                // fallback was subtly wrong for light themes too, not just dark.
                case "background_material_dark":      return 0xFF303030;
                case "background_material_light":     return 0xFFFAFAFA;
                case "accent_material_light":         return 0xFF008577;
                case "accent_material_dark":          return 0xFF80CBC4;
                case "primary_material_light":        return 0xFFF5F5F5;
                case "primary_material_dark":         return 0xFF212121;
                case "primary_dark_material_light":   return 0xFF757575;
                case "primary_dark_material_dark":    return 0xFF000000;
                case "foreground_material_light":     return 0xFF000000;
                case "foreground_material_dark":      return 0xFFFFFFFF;
                //   :25 background_floating_material_light -> @color/white   #ffffffff
                //   :24 background_floating_material_dark  -> @color/material_grey_800
                //                                              :81 #ff424242
                // The dark value is corroborated by the emulator: unitconverter's
                // Help dialog panel measures exactly #424242 there.
                case "background_floating_material_light": return 0xFFFFFFFF;
                case "background_floating_material_dark":  return 0xFF424242;
                // THE HOLO PALETTE (v1.0.871). These are PUBLIC framework colours
                // that apps reference directly, and they lived ONLY in
                // LayoutInflater's private 11-entry switch -- which meant any
                // consumer other than that one method got null. Values verbatim
                // from AOSP core/res/res/values/colors_holo.xml, fetched
                // 2026-08-05.
                //
                // MEASURED victims, all from a corpus grep:
                //  - AnkiDroid res/drawable/ic_link.xml is
                //    `<vector android:tint="@android:color/holo_blue_dark">`, and
                //    add/browse/review_shortcut.xml use it as a `<path
                //    android:fillColor>`. VectorDrawable.parseColor gets null,
                //    returns the 0 default, and VectorDrawable.draw then SKIPS the
                //    fill because (fillColor & 0xFF000000) == 0 -- the icon paints
                //    nothing at all.
                //  - Organic Maps ic_location_arrow_blue.xml -> holo_blue_light.
                // holo_blue_dark is the most-referenced of the set and was in
                // NEITHER table.
                case "holo_blue_bright":   return 0xFF00DDFF;
                case "holo_blue_light":    return 0xFF33B5E5;
                case "holo_blue_dark":     return 0xFF0099CC;
                case "holo_green_light":   return 0xFF99CC00;
                case "holo_green_dark":    return 0xFF669900;
                case "holo_red_light":     return 0xFFFF4444;
                case "holo_red_dark":      return 0xFFCC0000;
                case "holo_orange_light":  return 0xFFFFBB33;
                case "holo_orange_dark":   return 0xFFFF8800;
                case "holo_purple":        return 0xFFAA66CC;
                // These two are ColorStateLists, not plain colours, so the value
                // served is the ENABLED entry -- what a solid-colour read wants.
                // core/res/res/color/btn_default_material_{light,dark}.xml each
                // declare two items over @color/button_material_{light,dark}: a
                // state_enabled="false" row at ?attr/disabledAlpha, and the
                // default. So the enabled colour is button_material_* above.
                // (The name differs by more than a suffix -- `btn_default_` vs
                // `button_` -- which is exactly why the existing button_material_*
                // rows never matched what colorButtonNormal actually emits.)
                case "btn_default_material_light":    return 0xFFD6D7D7;
                case "btn_default_material_dark":     return 0xFF5A595B;
                // Also ColorStateLists: core/res/res/color/ripple_material_*.xml is
                // a single item, `android:color="@color/foreground_material_*"` at
                // `android:alpha="@dimen/highlight_alpha_material_*"`, and
                // colors_material.xml:75-76 gives BOTH alphas as 0.20.
                // 0.20 * 255 = 51 = 0x33, applied to #000000 / #ffffff.
                case "ripple_material_light":         return 0x33000000;
                case "ripple_material_dark":          return 0x33FFFFFF;
            }
            // Material You system palette (@android:color/system_*).
            Integer sys = macapkSystemPaletteColor(name);
            if (sys != null) return sys;
            return null;
        }
        if (raw.startsWith("?")) {
            // Theme-attribute reference. `?attr/X` and `?X` are the
            // app-namespace forms; `?android:attr/X` / `?android:X`
            // are the framework-namespace forms. The namespace is
            // PRESERVED into the lookup key ("android:X") because the
            // two are distinct attributes that M3/appcompat themes
            // alias to each other; collapsing them looped the
            // colorControlNormal chain (see resolveAttrInTheme).
            String attr;
            if (raw.startsWith("?attr/")) attr = raw.substring(6);
            else if (raw.startsWith("?android:attr/"))
                attr = "android:" + raw.substring(14);
            else attr = raw.substring(1);  // keeps "android:" if present
            // The anchor, when the caller supplied one, else the historical
            // process-wide theme. Threaded through the recursion so a chain of
            // ?attr hops stays inside the theme we were asked about.
            String anchor = (anchorTheme != null) ? anchorTheme : applicationThemeName;
            if (anchor == null) return null;
            String chained = resolveAttrInTheme(anchor, attr);
            if (chained == null) return null;
            return resolveColorRef(chained, visited, anchorTheme);
        }
        return null;
    }

    /** Parse #RGB / #RRGGBB / #AARRGGBB / #ARGB into a packed ARGB
     *  int. Mirrors AOSP `Color.parseColor` for the shorthand forms.
     *  Returns null on malformed input. */
    private static Integer parseHexColor(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) != '#') return null;
        try {
            String hex = s.substring(1);
            // Expand 3- and 4-digit shorthand: #RGB -> #RRGGBB, #ARGB -> #AARRGGBB.
            if (hex.length() == 3 || hex.length() == 4) {
                StringBuilder sb = new StringBuilder(hex.length() * 2);
                for (int i = 0; i < hex.length(); ++i) {
                    sb.append(hex.charAt(i)).append(hex.charAt(i));
                }
                hex = sb.toString();
            }
            long v = Long.parseLong(hex, 16);
            // 6-digit: opaque RGB; default alpha 0xFF.
            if (hex.length() == 6) v |= 0xFF000000L;
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Convenience for chrome painters: resolve a theme color attribute
     *  off the application theme, with a fallback if the cascade
     *  doesn't declare it (e.g. a non-Material3 APK that uses a different
     *  attr name). */
    public int resolveApplicationThemeColor(String attrName, int fallback) {
        if (applicationThemeName == null) return fallback;
        Integer c = resolveAttrToColor(applicationThemeName, attrName);
        return c != null ? c : fallback;
    }

    /** Resolve a `?attr/shapeAppearanceMediumComponent`-style indirection
     *  to its declared cornerSize. shapeAppearance attrs point at
     *  another style (e.g. `@style/ShapeAppearance.App.MediumComponent`)
     *  that has `<item name="cornerSize">16dp</item>`. Returns the dp
     *  value as a float, or `fallbackDp` if the cascade doesn't
     *  declare the shape attribute. Generic over the M3 token names:
     *  shapeAppearanceSmall/Medium/LargeComponent etc. */
    public float resolveApplicationThemeCornerDp(String shapeAttrName,
                                                  float fallbackDp) {
        if (applicationThemeName == null) return fallbackDp;
        String shapeRef = resolveAttrInTheme(applicationThemeName,
                                              shapeAttrName);
        if (shapeRef == null) return fallbackDp;
        // shapeRef is "@style/X.Y" or "X.Y".
        String shapeStyle = shapeRef.startsWith("@style/")
            ? shapeRef.substring(7) : shapeRef;
        // Walk THAT style's cascade for the `cornerSize` item -- the
        // declared cornerSize on the leaf style wins over its parent.
        String cornerSize = resolveAttrInTheme(shapeStyle, "cornerSize");
        if (cornerSize == null) return fallbackDp;
        return parseDpValue(cornerSize, fallbackDp);
    }

        /** Parse a `<dimen>` text-content style value: "16dp", "16.0dp",
     *  "8sp", or a bare number. Returns the numeric portion; the unit
     *  is implied dp by the caller for shape / margin work. */
    private static float parseDpValue(String s, float fallback) {
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty()) return fallback;
        // Strip common suffixes.
        for (String suf : new String[] { "dp", "dip", "sp", "px" }) {
            if (s.endsWith(suf)) { s = s.substring(0, s.length() - suf.length()); break; }
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean has(int id) { return idToTypeName.containsKey(id); }

    /** Reverse lookup used by Resources.getIdentifier(...). AOSP returns
     *  0 when the resource doesn't exist; callers like
     *  FirebaseOptions.fromResource treat 0 as "not configured" and
     *  fall back to default-options-not-found behavior. */
    public int idOfName(String name, String type, String pkg) {
        if (name == null || name.isEmpty()) return 0;
        // AOSP getIdentifier: "name" may be fully qualified
        // ("package:type/entry" or "type/entry"), in which case its
        // components override defType/defPackage. Both forms returned 0
        // here before (the composed key never matched), so parsing them is
        // strictly additive.
        int qColon = name.indexOf(':');
        int qSlash = name.indexOf('/');
        if (qSlash > 0) {
            if (qColon > 0 && qColon < qSlash) {
                pkg = name.substring(0, qColon);
                type = name.substring(qColon + 1, qSlash);
            } else {
                type = name.substring(0, qSlash);
            }
            name = name.substring(qSlash + 1);
        }
        // v1.0.917: an explicit "android" package answers from the platform
        // table (gate ON) and NEVER from the app's own namespace; on a
        // device getIdentifier(name, type, "android") reads framework-res.
        // Gate OFF keeps the historical 0, which is also the honest answer
        // ("we ship no framework table" there). This is what makes
        // Resources.getIdentifier's defPackage="android" light up and what
        // macapkResRefToId uses for "@android:type/name" references.
        if ("android".equals(pkg)) {
            MacapkFwView v = macapkFwView;
            if (v != null) {
                Integer fid = v.nameToId.get((type == null ? "" : type) + "/" + name);
                if (fid != null) return fid;
            }
            // FROZEN framework ids for the handful of platform resources our
            // pass-3 tables REFERENCE BY NAME (v1.0.927). With the real table
            // gated off, returning 0 here made frameworkThemeAttr's own answers
            // unresolvable -- it hands back "@android:style/TextAppearance.Material"
            // and then nothing can turn that string into an id, so
            // TypedArray.getResourceId fell through to the caller's default and
            // Material's ThemeEnforcement threw. A name we ourselves emit has to
            // be resolvable by us.
            //
            // Ids READ OUT OF THE REAL framework-res table (API 30) rather than
            // transcribed: `style/TextAppearance.Material` = 0x010301ed and
            // `style/TextAppearance.Material.Inverse` = 0x010301f8. Do NOT source
            // these from a decompiler dump; that dependency has burned this
            // project before.
            if ("style".equals(type)) {
                if ("TextAppearance.Material".equals(name))         return 0x010301ed;
                if ("TextAppearance.Material.Inverse".equals(name)) return 0x010301f8;
            }
            return 0;
        }
        // Any other package (including the app's own, and Firebase's null):
        // the app's single namespace, unchanged.
        String key = (type == null ? "" : type) + "/" + name;
        Integer id = nameToId.get(key);
        if (id != null) return id;
        // Some APKs (older aapt builds) don't list every string in
        // public.xml. If the caller asked for a string and we have
        // the value loaded, synthesize a stable hash-derived id so
        // Resources.getString(id) can route back through stringForId.
        if ("string".equals(type) && stringValues.containsKey(name)) {
            int synth = 0x7f000000 | (name.hashCode() & 0x00ffffff);
            // Cache the synthetic id so subsequent getString(id) lookups
            // round-trip correctly.
            idToTypeName.put(synth, new String[] { "string", name });
            nameToId.put(key, synth);
            return synth;
        }
        return 0;
    }

    /** Look up the string value for a resource id. Returns null if the
     *  id is not a string resource or wasn't loaded from strings.xml.
     *  Resources.getString(id) calls this and converts null -> "" so
     *  callers see the same "missing-but-tolerated" contract as on
     *  real Android. */
    public String stringForId(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn == null) {
            String hand = macapkFrameworkString(id);
            if (hand != null) return hand;
            // v1.0.917: the REAL platform string table under the gate, the
            // superset of the frozen hand rows above (which stay first: they
            // are verified and cover the gate-OFF path). What a device's
            // Resources.getString(android.R.string.x) serves. Ref chains
            // publish into the last-reference channel so getValue reports
            // the terminus.
            MacapkFwView v = macapkFwView;
            if (v != null && (id >>> 24) == 0x01) {
                String fwtn = v.publicMap.get(id);
                if (fwtn != null && fwtn.startsWith("string/")) {
                    return macapkFwLeafText(id, 0);
                }
            }
            return null;
        }
        if (!"string".equals(tn[0])) return null;
        return macapkResolveStringChain(stringValues.get(tn[1]));
    }

    /** AOSP AssetManager2.cpp:1141 kMaxIterations. See macapkResolveStringChain. */
    private static final int MACAPK_MAX_STRING_REF_HOPS = 20;

    /** Follow an `@string/X` chain to a literal, because a `<string>` value may
     *  itself be a reference to another `<string>`:
     *      <string name="default_theme_value">@string/auto_device_theme_key</string>
     *      <string name="auto_device_theme_key">auto_device_theme</string>
     *
     *  AOSP resolves that in `AssetManager2::ResolveReference`, which loops until
     *  the value stops being a TYPE_REFERENCE, bounded by
     *      constexpr const uint32_t kMaxIterations = 20;
     *  (frameworks/base/libs/androidfw/AssetManager2.cpp:1141; the exit test at
     *  :1154-1157 also stops on a SELF-reference, `result->data == resolve_resid`).
     *  On a dead end AOSP keeps the last value that resolved, per its own comment
     *  at :1146-1147, so that is what this returns too. Keeping the last token also
     *  leaves a dangling `@string/missing` VISIBLE to whoever reads it rather than
     *  silently becoming "".
     *
     *  MEASURED victim (NewPipe, 2026-08-08). Its theme selector reads
     *  `R.string.default_theme_value` as the fallback for the "theme" preference and
     *  compares the result against `R.string.auto_device_theme_key` /
     *  `light_theme_key` / `black_theme_key`, whose values are LITERALS. Only the
     *  `*_value` side goes through a reference, so with one hop missing the two
     *  sides can never be equal. Nothing matched and the app fell through to its
     *  hardcoded DarkTheme initialiser on a device reporting UI_MODE_NIGHT_NO, so
     *  every `?colorPrimary` surface painted `dark_youtube_primary` #992722 where
     *  the emulator paints `light_youtube_primary` #e53935. That asymmetry is why a
     *  single missing hop is fatal instead of self-cancelling: both sides being
     *  unresolved would still have compared equal.
     *
     *  Read time rather than load time on purpose: `loadStringValues` fills the map
     *  per config-qualified directory in ASCENDING precedence, so a chain's target
     *  can still be overridden by a later directory after an earlier one is parsed.
     *  Resolving at lookup is both AOSP's order and the only order that is correct
     *  for a localized APK.
     *
     *  Same guard shape as `resolveColorRef`: a bounded loop plus a visited set.
     *  The visited set is keyed on the TARGET NAME, so a two-step loop (a -> b -> a)
     *  is caught as well as a self-reference. */
    private String macapkResolveStringChain(String v) {
        if (v == null) return null;
        java.util.Set<String> visited = new java.util.HashSet<>();
        String cur = v;
        for (int i = 0; i < MACAPK_MAX_STRING_REF_HOPS; ++i) {
            String t = cur.trim();
            if (t.length() < 2 || t.charAt(0) != '@') return cur;
            int slash = t.indexOf('/');
            if (slash <= 0) return cur;
            String type = t.substring(1, slash);
            int colon = type.indexOf(':');
            if (colon >= 0) {
                // A framework `@android:string/X` cannot resolve out of the app's
                // own table; hand the token back rather than inventing a value.
                // Same honest-zero policy as macapkResRefToId.
                if ("android".equals(type.substring(0, colon))) return cur;
                type = type.substring(colon + 1);
            }
            if (!"string".equals(type)) return cur;
            String name = t.substring(slash + 1);
            if (!visited.add(name)) return cur;
            String next = stringValues.get(name);
            if (next == null) return cur;
            // v1.0.917: publish the followed id (AOSP ResolveReference's
            // out_last_reference); Resources.getValue reads the slot so a
            // ref-valued <string> reports the terminus, not the queried id.
            macapkPublishValueOrigin(macapkNameToIdDirect("string", name));
            cur = next;
        }
        return cur;
    }

    /** The FROZEN public framework strings (`android.R.string.*`).
     *
     *  WHY (v1.0.871). `idToTypeName` only ever holds the APP's own decoded
     *  `0x7f……` ids, so a framework `0x0104……` missed, `stringForId` returned
     *  null, and `Resources.getString` fell through to its `return ""`.
     *
     *  MEASURED victim: newpipe's "Keep Android Open" dialog. Its
     *  `MainActivity.onCreate` does, at bci 2461-2480,
     *      ldc_w 17039370            // 0x0104000a == android.R.string.ok
     *      invokevirtual AlertDialog$Builder.setPositiveButton(I, …)
     *  so the OK button's label came back "" and rendered as a zero-width gap --
     *  the emulator shows "OK". Any app using `android.R.string.ok/cancel` for a
     *  dialog button hits this, which is a very common idiom.
     *
     *  Same provenance as the frozen `@android:id/*` ids (v1.0.867) and the
     *  frozen `*Style` attr ids (v1.0.869): AOSP
     *  core/res/res/values/public-final.xml states these ids are permanently
     *  frozen, which is what makes hardcoding them correct rather than a guess.
     *  English values from core/res/res/values/strings.xml, both fetched
     *  2026-08-05.
     *
     *  ⚠ TRAP, verified rather than assumed: modern AOSP maps `yes` -> "OK" and
     *  `no` -> "Cancel", NOT "Yes"/"No". Do not "correct" those two. */
    private static String macapkFrameworkString(int id) {
        switch (id) {
            case 0x01040000: return "Cancel";       // cancel
            case 0x01040001: return "Copy";         // copy
            case 0x01040003: return "Cut";          // cut
            case 0x01040009: return "Cancel";       // no      (AOSP maps to Cancel)
            case 0x0104000a: return "OK";           // ok
            case 0x0104000b: return "Paste";        // paste
            case 0x0104000c: return "Search";       // search_go
            case 0x0104000d: return "Select all";   // selectAll
            case 0x0104000f: return "<Untitled>";   // untitled
            case 0x01040013: return "OK";           // yes     (AOSP maps to OK)
            case 0x01040014: return "Attention";    // dialog_alert_title
            default: return null;
        }
    }

    /** Look up integer-resource value by NAME (no id lookup). Used by
     *  the Manifest meta-data parser to resolve `@integer/<name>`
     *  references. Returns null if not found. */
    public Integer integerForName(String name) {
        if (name == null) return null;
        return integerValues.get(name);
    }
    /** Look up integer-resource value by ID. Resources.getInteger(id). */
    public Integer integerForId(int id) {
        if ((id >>> 24) == 0x01) {
            Object[] value = macapkFwValueOf(id);
            if (value == null) return null;
            int type = (Integer) value[0];
            // Match Resources.getInteger's integer-typed value contract,
            // including framework references resolved by macapkFwValueOf.
            return type >= 0x10 && type <= 0x1f ? (Integer) value[1] : null;
        }
        String[] tn = idToTypeName.get(id);
        if (tn == null) return null;
        if (!"integer".equals(tn[0])) return null;
        return integerValues.get(tn[1]);
    }

    /** Look up string-array by ID. Resources.getStringArray(id). Returns
     *  null if the id isn't an array or wasn't loaded; the framework
     *  wrapper converts null to {@code new String[0]} so callers see
     *  the AOSP "missing but tolerated" contract. */
    public String[] stringArrayForId(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn == null) return null;
        if (!"array".equals(tn[0])) return null;
        return stringArrayValues.get(tn[1]);
    }
    public String[] stringArrayForName(String name) {
        if (name == null) return null;
        return stringArrayValues.get(name);
    }
    public int[] integerArrayForId(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn == null) return null;
        if (!"array".equals(tn[0])) return null;
        return integerArrayValues.get(tn[1]);
    }

    /** Mirror AOSP's Resources.getResourceTypeName(id). Returns the
     *  type (drawable/string/raw/etc.) or empty if not registered. */
    public String typeNameOf(int id) {
        String[] tn = idToTypeName.get(id);
        return tn != null ? tn[0] : "";
    }

    /** v1.0.128: returns {type, name} for a resource id, or null if
     *  not in the table. Used by Theme.resolveAttribute to translate
     *  the attribute resId into a name for the theme cascade walker.
     *  Public + named with the `macapk` prefix so cross-package
     *  reflection in Resources.java has a stable handle. */
    public String[] macapkTypeNameOf(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn == null) {
            // Under the gate, FRAMEWORK ids (0x01xxxxxx) resolve from the
            // platform table, which is what real Android does for any id in
            // the android package. This is what lets Theme.applyStyle push a
            // platform theme and Theme.resolveAttribute walk a framework
            // attr; gate OFF keeps the historical null (the curated
            // macapkFrameworkStyleAttrName covers its consumers there).
            MacapkFwView v = macapkFwView;
            if (v != null) {
                String s = v.publicMap.get(id);
                if (s != null) {
                    int slash = s.indexOf('/');
                    if (slash > 0) {
                        return new String[] {
                            s.substring(0, slash), s.substring(slash + 1) };
                    }
                }
            }
            // GATE-OFF: the curated frozen framework THEME ids. Without this
            // row `Resources.Theme.applyStyle` silently dropped every platform
            // theme an activity declares -- it names the id through this method
            // and pushes nothing when the name is null -- so an activity whose
            // ONLY theme is a platform one ran with an EMPTY applied-style
            // cascade. TypedArray.java:284 documents the same defect from the
            // consumer side ("applyStyle drops a framework style id because
            // macapkTypeNameOf only knows the APP's ids"). styleNameForIdRef
            // has always answered these ids for the APPLICATION theme; this
            // makes the ACTIVITY theme path agree with it instead of
            // contradicting it. See MACAPK_FW_STYLE_BY_ID for why the list is
            // eight rows and not every frozen Theme.* id.
            String fwStyle = MACAPK_FW_STYLE_BY_ID.get(id);
            if (fwStyle != null) return new String[] { "style", fwStyle };
            return null;
        }
        // Defensive: copy so the caller can't mutate our table.
        return new String[] { tn[0], tn[1] };
    }

    /** Mirror AOSP's Resources.getResourceEntryName(id).
     *
     *  v1.0.95: when the ID is in the Android framework's
     *  android.R.attr range (0x0101xxxx), the apktool public.xml
     *  doesn't include it (those are framework IDs, not app IDs).
     *  Fall back to a hardcoded mapping for the ~30 standard
     *  framework attribute IDs that styleable arrays (like
     *  R.styleable.MenuItem) reference. Without this, androidx's
     *  TypedArray.getText(R.styleable.MenuItem_android_title)
     *  can't bind to the actual `android:title` XML attr because
     *  attrNameAt returns null, the AttributeSet lookup is
     *  skipped, and menu titles render as empty strings. */
    /** aapt2's string-value unescape, applied at load so guest code sees what
     *  a real resource table would hand it. Ours stored `getTextContent()`
     *  RAW, so the standard apostrophe-protecting idiom
     *  `<string name="x">"Anki's ..."</string>` rendered WITH literal double
     *  quotes on screen (measured: AnkiDroid's onboarding subtitle vs the
     *  emulator; docs/canvas-ab-analyst-report_2026-08-02.md item 9), and
     *  every `\n` in a string rendered as a literal backslash-n.
     *
     *  Rules transcribed from aapt2 `ResourceUtils.cpp`
     *  StringBuilder::AppendText (android13-release):
     *    - an UNESCAPED `"` toggles quoting and is NOT emitted;
     *    - inside quotes, whitespace is preserved verbatim;
     *    - outside quotes, a run of whitespace collapses to ONE space;
     *    - `\\` escapes: n -> newline, t -> tab, uXXXX -> code point,
     *      anything else -> the literal character (covers \' \" \\ \@ \? \#).
     *  Leading/trailing unquoted whitespace is trimmed, as aapt2 does. */
    static String macapkUnescapeAaptString(String raw) {
        if (raw == null) return null;
        final int n = raw.length();
        if (n == 0) return raw;
        // Fast path: nothing aapt2 would transform.
        boolean interesting = false;
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\' || c == '\n' || c == '\t' || c == '\r') { interesting = true; break; }
            if (c == ' ' && i + 1 < n && raw.charAt(i + 1) == ' ') { interesting = true; break; }
        }
        if (!interesting) return raw;
        StringBuilder out = new StringBuilder(n);
        boolean quoted = false;
        boolean pendingSpace = false;
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c == '"' ) { quoted = !quoted; continue; }
            if (c == '\\' && i + 1 < n) {
                char e = raw.charAt(++i);
                if (pendingSpace) { out.append(' '); pendingSpace = false; }
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'u': {
                        if (i + 4 < n) {
                            try {
                                out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ex) { out.append('u'); }
                        } else out.append('u');
                        break;
                    }
                    default: out.append(e); break;    // \' \" \\ \@ \? \# and friends
                }
                continue;
            }
            if (!quoted && (c == ' ' || c == '\n' || c == '\t' || c == '\r')) {
                // Collapse an unquoted whitespace run to a single space, and
                // drop it entirely at the very start (aapt2 trims).
                if (out.length() > 0) pendingSpace = true;
                continue;
            }
            if (pendingSpace) { out.append(' '); pendingSpace = false; }
            out.append(c);
        }
        return out.toString();   // trailing pendingSpace intentionally dropped
    }

    public String entryNameOf(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn != null) return tn[1];
        // Framework attr fallback. IDs are stable across SDK
        // versions (android.R.attr.* in frameworks/base/core/res
        // /res/values/public.xml). Listed here are the attributes
        // commonly referenced by Material / androidx styleable
        // arrays.
        String fw = frameworkAttrName(id);
        return fw;
    }

    /** Return the canonical attr name for a standard framework
     *  resource ID (0x0101xxxx range), or null if unknown.
     *
     *  Values verified against AOSP's frameworks/base/core/res/res
     *  /values/public.xml (the binary-compatibility frozen table --
     *  these IDs cannot change between SDK versions per the comment
     *  block at the top of that file).
     *
     *  ANSWER ORDER (changed 2026-08-23): the generated, SHIPPED table
     *  FrameworkAttrIds is consulted FIRST, and the hand-curated switch
     *  below is only a fallback for anything that table lacks (nothing,
     *  today). It used to be the other way round, with the wide table
     *  read at runtime from androguard's bundled public.xml under
     *  /opt/homebrew/lib/python3.13/site-packages. That file is a
     *  Homebrew python package and python was DROPPED from this
     *  project's requirements at v1.0.766, so it exists on exactly one
     *  machine: this one. On every other machine -- including every
     *  machine that installs the DMG -- ~1,370 framework attrs became
     *  unnameable, and an unnameable attr does not throw here, it
     *  silently resolves to its default. Five already-fixed bugs in
     *  this file's history share that root cause, each patched by
     *  hardcoding one more case.
     *
     *  Putting the generated table first (rather than last) is
     *  deliberate: it is machine-derived from AOSP's own frozen
     *  public-final.xml, so where the two ever disagree the generated
     *  one is right. That is not hypothetical -- the switch's entry for
     *  0x010102f8 was wrong when the table was introduced. */
    private static String frameworkAttrName(int id) {
        // The generated, shipped, complete table (1,581 ids straight out of
        // AOSP public-final.xml + public-staging.xml). One array index after a
        // one-time split; no I/O, no map, no host dependency.
        String gen = FrameworkAttrIds.name(id);
        if (gen != null) return gen;
        // Fallback for anything the generated table does not declare. Every
        // case here is currently ALSO in that table (asserted by
        // `debug/gen_framework_attrs.py --check`), so this is a safety net for
        // a future id rather than a live path. The comments are kept because
        // they are the record of which attrs mattered and why.
        switch (id) {
            case 0x01010000: return "theme";
            case 0x01010001: return "label";
            case 0x01010002: return "icon";
            case 0x01010003: return "name";
            case 0x0101000e: return "enabled";
            case 0x010100d0: return "id";
            case 0x010100f4: return "layout_width";
            case 0x010100f5: return "layout_height";
            case 0x01010106: return "checked";
            case 0x01010194: return "visible";
            case 0x010101e1: return "title";          // KEY for menu titles
            case 0x010101e5: return "checkable";
            case 0x01010095: return "textSize";
            case 0x01010096: return "typeface";
            case 0x01010097: return "textStyle";
            case 0x01010098: return "textColor";
            // fontFamily (v1.0.935). Same "the NAME lookup fails first" trap
            // the textAppearance note below documents: TextView.setTextAppearance
            // asks a style-bound TypedArray for attr 0x010103ac, and without a
            // name row attrNameAt returns null, the style is never consulted for
            // it, and getString answers the default -- so every Material 3
            // typescale style's android:fontFamily reads as absent and every
            // label renders weight 400 instead of 500. Id read out of the real
            // framework-res API-30 table (core/res/res/values/public.xml at
            // android11-release), cross-checked against the four ids this file
            // already carries: textSize/textStyle/textColor/fontStyle all agree.
            case 0x010103ac: return "fontFamily";
            // textFontWeight (API 28, v1.0.936). Same unnameable-attribute trap:
            // TextView reads it out of a TextAppearance and a non-negative value
            // takes the createAbsolute path (Typeface.create(tf, weight, italic))
            // INSTEAD of the +300 relative one, so dropping it silently reroutes
            // the weight arithmetic. Id 0x01010585, same public.xml revision as
            // the rows above.
            case 0x01010585: return "textFontWeight";
            // textAppearance (v1.0.928). WITHOUT THIS ROW THE ATTRIBUTE IS
            // UNNAMEABLE, so `TypedArray.attrNameAt` returns null, the theme
            // tier is never consulted by name, and `getResourceId` falls
            // through to the caller's default -- which is how Material's
            // `ThemeEnforcement.checkTextAppearance` read -1 and threw
            // `IllegalArgumentException: This component requires that you
            // specify a valid TextAppearance attribute` out of the CONSTRUCTOR
            // of every BottomNavigationView / NavigationRailView. Our
            // LayoutInflater then substituted a FrameLayout placeholder and the
            // app's own cast aborted onCreate, so deskclock and antennapod
            // rendered entirely BLANK and F-Droid's bottom bar was empty.
            //
            // v1.0.927 added the VALUE (frameworkThemeAttr -> "@android:style/
            // TextAppearance.Material", the AOSP themes_material.xml:50-51/
            // 335-336 contract) and the ID (idOfName -> 0x010301ed), and the
            // symptom did NOT move, because the NAME lookup fails first. All
            // three links are needed.
            //
            // Id read out of the real framework-res API-30 table, not
            // transcribed: attr 0x01010034 = textAppearance.
            case 0x01010034: return "textAppearance";
            // minWidth/minHeight: read by View's defStyle-carrying ctors
            // (style-supplied android:minHeight, e.g. Material3's 80dp
            // bottom-nav floor).
            //
            // 0x010102f8 was here as "titleTextAppearance" and that was WRONG.
            // AOSP public-final.xml (android16-release :1343) says
            //   <public type="attr" name="titleTextStyle" id="0x010102f8" />
            // and titleTextAppearance is 0x0101042e (:2153). A second table in
            // this same file (macapkFrameworkButtonGeom's switch) already had
            // the correct name for the same id, so the two disagreed. Found by
            // diffing every case label in this method against the generated
            // FrameworkAttrIds table -- which is the argument for having a
            // generated table at all, and the reason
            // `debug/gen_framework_attrs.py --check` now asserts the agreement.
            case 0x0101013f: return "minWidth";
            case 0x01010140: return "minHeight";
            case 0x010102f8: return "titleTextStyle";
            case 0x010100af: return "gravity";
            // layout_gravity: id verified against aosp-mirror
            // platform_frameworks_base core/res/res/values/public-final.xml
            // (fetched 2026-08-01): <public type="attr" name="layout_gravity"
            // id="0x010100b3"/>. Load-bearing for every library LayoutParams
            // ctor that reads {android.R.attr.layout_gravity} via
            // obtainStyledAttributes (androidx DrawerLayout, CoordinatorLayout,
            // material NavigationView...). Only the androguard fallback table
            // below covered it before -- a file that exists on this box purely
            // as a leftover install (python was dropped from the requirements
            // at v1.0.766), so a fresh machine silently lost every drawer.
            case 0x010100b3: return "layout_gravity";
            case 0x010100c4: return "orientation";
            case 0x010100d4: return "background";
            // android:font / fontWeight / fontStyle / ttcIndex /
            // fontVariationSettings -- the mirror half of the six-row fix
            // docs/font-family-xml-frameworkattr-gap_2026-08-13.md landed in
            // TypedArray.java's OWN frameworkAttrName (v1.0.965). THIS table is
            // the one `attrNameAt` actually reaches FIRST (TypedArray.attrNameAt
            // -> Resources.macapkGetResourceIndex().entryNameOf -> this method;
            // TypedArray's own table is only a fallback for when entryNameOf
            // returns null), so leaving it out here is what made every one of
            // these five ids unnameable in practice, regardless of the fallback
            // row existing. `fontFamily` (0x010103ac, case above, v1.0.935) and
            // `textFontWeight` (0x01010585, case above, v1.0.936) already prove
            // this exact trap was hit and fixed on THIS table before, for a
            // different (TextAppearance typescale) bug -- these five are the
            // same trap for FontResourcesParserCompat#readFont's <font> child
            // attrs: android:font/fontWeight/fontStyle/ttcIndex/
            // fontVariationSettings, needed so a BUNDLED (non-downloadable)
            // res/font/*.xml <font-family> resolves its <font> children when
            // authored with ONLY android:-namespaced attributes (no app:
            // fallback in the XML -- GD Meltdown's corpus example carries both
            // and so was not visibly broken by this specific gap; a
            // hand-authored or differently-merged font-family need not be so
            // lucky). Without a name row here, attrNameAt falls through to
            // TypedArray's OWN table, which now has all five -- so this row set
            // is currently redundant in the sense that the crash is already
            // fixed via the fallback, but NOT redundant as a matter of
            // correctness: entryNameOf is the FIRST-consulted table by
            // contract, and every other id in this switch answers there, not
            // via the fallback. Ids re-verified independently this session via
            // `curl` (not WebFetch, which truncates this file) against AOSP
            // core/res/res/values/public-final.xml
            // (raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/
            // main/..., fetched 2026-08-13) -- byte-identical to the citation
            // TypedArray.java carries for the same five ids:
            //   <public type="attr" name="font" id="0x01010532" />
            //   <public type="attr" name="fontWeight" id="0x01010533" />
            //   <public type="attr" name="fontStyle" id="0x0101053f" />
            //   <public type="attr" name="ttcIndex" id="0x0101056f" />
            //   <public type="attr" name="fontVariationSettings" id="0x01010570" />
            // Cross-reference: TypedArray.java's frameworkAttrName carries the
            // identical six rows (including fontFamily) with the same citation;
            // that file is owned by another lane as of this writing so the
            // reciprocal pointer comment could not be added there in this turn
            // -- see docs/font-family-xml-frameworkattr-gap_2026-08-13.md's
            // OPEN QUESTIONS for the note that this table still needed the fix.
            case 0x01010532: return "font";
            case 0x01010533: return "fontWeight";
            case 0x0101053f: return "fontStyle";
            case 0x0101056f: return "ttcIndex";
            case 0x01010570: return "fontVariationSettings";
        }
        // LAST RESORT. Kept only so a machine that HAS androguard can still
        // answer an id that postdates the generated table; it is not part of
        // the shipped contract and must never be relied on. Reaching it at all
        // means FrameworkAttrIds is stale, so say so once, loudly, instead of
        // silently succeeding here and silently failing everywhere else.
        ensureAndroguardLoaded();
        String ag = androguardFrameworkAttrs.get(id);
        if (ag != null) warnAndroguardReached(id, ag);
        return ag;
    }

    private static volatile boolean androguardWarned = false;

    /** One line, once per process. The point is to make the host-dependent
     *  path visible on the ONE machine that has it, because that is the
     *  machine where the missing dependency is invisible. */
    private static void warnAndroguardReached(int id, String name) {
        if (androguardWarned) return;
        androguardWarned = true;
        NativeLog.w("ResourceIndex",
            "framework attr 0x" + Integer.toHexString(id) + " (" + name + ")"
            + " was named by the HOST androguard public.xml, not by the"
            + " shipped FrameworkAttrIds table. That table is STALE and this"
            + " attr resolves to its default on every machine but this one."
            + " Re-run: python3 debug/gen_framework_attrs.py");
    }

    private static volatile boolean androguardLoaded = false;
    private static final java.util.Map<Integer, String> androguardFrameworkAttrs =
        new java.util.HashMap<>();

    /** Lazy-load the framework attribute table from androguard's bundled
     *  public.xml, if this machine happens to have it.
     *
     *  ⚠ THIS IS NOT A SHIPPED CAPABILITY and nothing may depend on it. It is
     *  a Homebrew python package; python left this project's requirements at
     *  v1.0.766. It sits BEHIND the generated FrameworkAttrIds table now, and
     *  reaching it logs a warning, precisely because a dependency that is
     *  present on the dev machine and absent everywhere else is the shape of
     *  bug this whole change exists to remove. Delete this method once
     *  FrameworkAttrIds has been regenerated against a branch new enough that
     *  the warning can no longer fire. */
    private static void ensureAndroguardLoaded() {
        if (androguardLoaded) return;
        synchronized (androguardFrameworkAttrs) {
            if (androguardLoaded) return;
            androguardLoaded = true;
            String path = "/opt/homebrew/lib/python3.13/site-packages"
                + "/androguard/core/resources/public.xml";
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f))) {
                String line;
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "<public\\s+type=\"attr\"\\s+name=\"([^\"]+)\"\\s+id=\"(0x[0-9a-fA-F]+)\"");
                while ((line = r.readLine()) != null) {
                    java.util.regex.Matcher m = p.matcher(line);
                    if (m.find()) {
                        String name = m.group(1);
                        int id = (int) Long.parseLong(m.group(2).substring(2), 16);
                        androguardFrameworkAttrs.put(id, name);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Locate XML resources -- preferences, menus, layouts, drawables-
     *  as-xml, etc. AOSP's Resources.getXml(id) takes ANY xml-type
     *  resource, not just `res/xml/<name>.xml`; the same resource id
     *  can point at `res/layout/foo.xml`, `res/menu/main.xml`, or
     *  `res/xml/prefs.xml` depending on what `<public type=...>`
     *  registered. The returned stream feeds DomXmlResourceParser
     *  (see runtime/java/com/macapk/loader/DomXmlResourceParser.java).
     *  Returns null when no file matches so Resources.getXml can throw
     *  NotFoundException -- the AOSP contract. */
    public InputStream openXml(int id) throws FileNotFoundException {
        return openRaw(id);
    }

    /** Resolve `id` to its file entry in the installed apk set (the
     *  member's config-selected FILE row; a compiled XML as decoded text).
     *  Returns the open stream or null if no resource matches.
     *
     *  Follows file ALIASES the way real Android does. aapt lets a
     *  resource entry be a plain reference to another file-backed
     *  resource (`<item type="layout" name="display">@layout/
     *  display_one_line</item>`, decoded by apktool as `<layout
     *  name="display">@layout/display_one_line</layout>` in
     *  res/values/layouts.xml); such an entry has NO file of its own.
     *  AOSP ResourcesImpl.loadXmlResourceParser calls
     *  `getValue(id, value, resolveRefs=true)` -- which chases the
     *  reference chain inside AssetManager -- and only then opens
     *  `value.string` as the file. Calculator's `@layout/display`
     *  include is exactly this shape; without the chase the include
     *  fails and the display subtree never inflates. */
    /** A file-backed APP resource by `type/name`, through the same row-first
     *  path as openRaw(id): the installed set's tables know every entry by
     *  name, INCLUDING the private ones aapt2 generates (`$foo__0` gradient
     *  and inline-vector children), which never had a public.xml line. This
     *  is what Resources' res/color selector loader and VectorDrawable's
     *  private-resource opener use since v1.0.1590; both used to list the
     *  decoded `_apkt/res/<type>-*` directories, which no longer exist.
     *  Returns null when the name is unknown or not file-backed. */
    public InputStream openRawByName(String type, String name) {
        if (type == null || name == null) return null;
        Integer id = nameToId.get(type + "/" + name);
        if (id == null) return null;
        try {
            return openRawByTypeName(id, type, name, new java.util.HashSet<>());
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    public InputStream openRaw(int id) throws FileNotFoundException {
        // FRAMEWORK package (0x01......): the app extraction has no such
        // file; under the gate the platform table's own apk serves it.
        // An app id can never carry package 0x01, so this branch cannot
        // shadow an app resource.
        if ((id >>> 24) == 0x01) return macapkFwOpenRaw(id);
        String[] tn = idToTypeName.get(id);
        if (tn == null) return null;
        return openRawByTypeName(id, tn[0], tn[1],
            new java.util.HashSet<>());
    }

    /** Serve a FRAMEWORK file-backed resource (gate ON) as a stream: the
     *  platform table's config-selected entry for `id` is TYPE_STRING
     *  carrying the compiled path inside the framework apk
     *  ("res/drawable/btn_check_material_anim.xml"), and the bytes are
     *  that zip entry's. This is the Mac.apk analogue of AOSP
     *  ResourcesImpl.loadDrawableForCookie (android11-release), whose
     *  non-xml branch is `mAssets.openNonAsset(value.assetCookie, file,
     *  ACCESS_STREAMING)` and whose xml branch hands the same
     *  cookie+path pair to loadXmlResourceParser: the COOKIE names
     *  which apk, the value's STRING names the entry inside it. Our
     *  framework "cookie" is always the table apk the view was built
     *  from. In-table reference chains are chased by macapkFwValueOf
     *  (AssetManager2::ResolveReference semantics, cap 20), so a
     *  file-alias entry serves its target's bytes, matching openRaw's
     *  app-side alias behaviour. Returns null when the gate is off,
     *  the id has no entry, or the entry is not file-backed; the
     *  caller's NotFoundException contract is unchanged.
     *
     *  COST: one ZipFile open per call (~1-3 ms against the 48 MB
     *  table apk, central-directory only) and one entry decompression;
     *  the bytes are copied out so no zip handle outlives the call.
     *  Lazy by construction -- nothing is read until a framework
     *  drawable is actually requested. Raster results are cached
     *  downstream by Resources' DrawableCache exactly like app assets;
     *  XML inflates are uncached there, matching the app-side path. */
    private InputStream macapkFwOpenRaw(int id) {
        MacapkFwView v = macapkFwView;
        if (v == null) return null;
        Object[] leaf = macapkFwValueOf(id);
        if (leaf == null || (Integer) leaf[0] != ArscReader.TYPE_STRING) return null;
        String path = (String) leaf[2];
        if (path == null || !path.startsWith("res/")) return null;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(v.apk)) {
            java.util.zip.ZipEntry e = zf.getEntry(path);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return new java.io.ByteArrayInputStream(in.readAllBytes());
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** The platform table's "type/name" for a framework id (gate ON), or
     *  null. Despite the field's name this map is fed one row per parsed
     *  entry (ArscReader.publicMap putIfAbsent), so internal entries
     *  resolve too -- which is what a compiled framework XML needs, since
     *  its `?0x`/`@0x` references are mostly internal ids. Consumers:
     *  VectorDrawable's compiled `?0x` tint resolution maps the attr id
     *  back to its name before the theme walk. */
    public String macapkFwEntryTypeName(int id) {
        MacapkFwView v = macapkFwView;
        return v == null ? null : v.publicMap.get(id);
    }

    /** Resolve `id` to the on-disk file path of its backing resource
     *  (or null if there is no file). Mirrors openRaw's resolution
     *  (same subdir candidates + alias chase) but returns the path
     *  instead of opening the file. AOSP `Resources.getValue(id,
     *  outValue, true)` fills `outValue.string` with this compiled
     *  path; `androidx.compose.ui.res.painterResource` reads it and
     *  branches vector (path ends ".xml") vs bitmap on the extension,
     *  then uses it as the cache key -- a null path makes it take the
     *  bitmap branch unconditionally and throw ResourceResolution
     *  Exception ("Error attempting to load resource: null") when the
     *  resource is actually a <vector> XML. */
    public String pathFor(int id) {
        String[] tn = idToTypeName.get(id);
        if (tn == null) return null;
        try {
            return pathForByTypeName(id, tn[0], tn[1], new java.util.HashSet<>());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Config-compatible `res/<typePrefix>*` subdir NAMES, BEST match
     *  FIRST (descending qualifier score). For file-resource resolution:
     *  the first compatible dir that actually contains the file wins,
     *  mirroring AOSP's best-config selection. Uses the same
     *  qualifierScore() as the value-alias path (matchingValueDirs);
     *  incompatible/unknown-qualifier dirs are dropped. v1.0.446.
     *
     *  Memoized per typePrefix (compatibleSubdirsCache): called once per
     *  file-backed resource LOOKUP (subdirCandidatesFor -> here, for
     *  every non-drawable/mipmap type -- layout/menu/xml/anim/font/...),
     *  so a list-heavy screen re-resolving the same handful of types
     *  many times (RecyclerView row layouts, repeated menu inflates)
     *  otherwise re-`listFiles()`+re-scores the same dirs every time.
     *  Same invalidation story as matchingValueDirs: fixed for the life
     *  of this instance. */
    private final Map<String, List<String>> compatibleSubdirsCache = new HashMap<>();
    private String pathForByTypeName(int id, String type, String name,
                                     java.util.Set<String> visited) {
        if (type == null || name == null) return null;
        if (!visited.add(type + "/" + name)) return null;
        // The installed apk's own entry path (v1.0.1590): what AOSP's
        // outValue.string carries for a file-backed resource, verbatim from
        // the table ("res/font/x.otf", or a shortened "res/ab.otf").
        Object[] fr = fileRowById.get(id);
        if (fr != null) return (String) fr[1];
        // No file of its own: chase a values-declared alias entry to the
        // TARGET's id (the rows are keyed by id, and the alias row is a
        // TYPE_REFERENCE, not a file), mirroring openRaw.
        String aliasTarget = fileAliases.get(type + "/" + name);
        if (aliasTarget != null && aliasTarget.startsWith("@")
                && aliasTarget.indexOf('/') > 0) {
            String body = aliasTarget.substring(1);
            int slash = body.indexOf('/');
            String t2 = body.substring(0, slash);
            String n2 = body.substring(slash + 1);
            if (t2.indexOf(':') < 0) {
                Integer id2 = nameToId.get(t2 + "/" + n2);
                String chased = pathForByTypeName(id2 != null ? id2 : id, t2, n2, visited);
                if (chased != null) return chased;
            }
        }
        return null;
    }
    private InputStream openRawByTypeName(int id, String type, String name,
                                          java.util.Set<String> visited)
            throws FileNotFoundException {
        if (type == null || name == null) return null;
        if (!visited.add(type + "/" + name)) return null;  // cycle guard
        // The installed apk's own entry (v1.0.1590), config-selected across
        // the set; compiled XML comes back as decoded-form text. (The
        // decoded-tree directory scan that used to follow is gone -- step 9b.)
        byte[] bytes = macapkReadFileRow(id);
        if (bytes != null) {
            if (NativeLog.isLoggable(NativeLog.DEBUG))
                NativeLog.d(TAG, "openRawResource(0x" + Integer.toHexString(id)
                    + ") -> " + fileRowById.get(id)[1] + " (in place)");
            return new java.io.ByteArrayInputStream(bytes);
        }
        // No file of its own: chase a values-declared alias entry
        // ("layout/display" -> "@layout/display_one_line"), mirroring
        // AssetManager's resolveRefs=true reference walk -- to the TARGET's
        // id, because the rows are keyed by id and the alias row itself is a
        // TYPE_REFERENCE (Calculator's `@layout/display` include, and its
        // display_two_line variant on a tall screen, are exactly this shape).
        String aliasTarget = fileAliases.get(type + "/" + name);
        if (aliasTarget != null && aliasTarget.startsWith("@")
                && aliasTarget.indexOf('/') > 0) {
            String body = aliasTarget.substring(1);
            int slash = body.indexOf('/');
            String t2 = body.substring(0, slash);
            String n2 = body.substring(slash + 1);
            // Namespaced targets ("@android:layout/x") are framework
            // resources served by the platform table, not by this chase.
            if (t2.indexOf(':') < 0) {
                Integer id2 = nameToId.get(t2 + "/" + n2);
                NativeLog.i(TAG, "openRawResource(0x"
                    + Integer.toHexString(id) + ") alias " + type + "/"
                    + name + " -> " + aliasTarget
                    + (id2 != null ? String.format(" (0x%08x)", id2) : " (no id)"));
                InputStream chased = openRawByTypeName(id2 != null ? id2 : id, t2, n2, visited);
                if (chased != null) return chased;
            }
        }
        NativeLog.w(TAG, "openRawResource(0x" + Integer.toHexString(id) +
                          ") missed: type=" + type + " name=" + name);
        return null;
    }

    /** Select a plural template by resource id + CLDR quantity keyword
     *  ("one"/"other"/...). Falls back to "other" (the universal form),
     *  then any available form. Returns null if the id isn't a known
     *  plural. Resources.getQuantityString resolves the keyword from the
     *  count per the active locale's rules. */
    public String pluralForId(int id, String quantityKeyword) {
        String[] tn = idToTypeName.get(id);
        if (tn == null || tn.length < 2 || !"plurals".equals(tn[0])) return null;
        return pluralForName(tn[1], quantityKeyword);
    }
    public String pluralForName(String name, String quantityKeyword) {
        if (name == null) return null;
        java.util.Map<String, String> forms = pluralValues.get(name);
        if (forms == null) return null;
        String t = (quantityKeyword != null) ? forms.get(quantityKeyword) : null;
        if (t == null) t = forms.get("other");
        if (t == null && !forms.isEmpty()) t = forms.values().iterator().next();
        return t;
    }

    /** v1.0.128: Parse `<color name="X">value</color>` entries from
     *  `res/values/colors.xml` (and any other values-*.xml that
     *  declares colors). Concrete hex values populate `colorValues`;
     *  references (`@color/X` / `@android:color/X`) populate
     *  `colorRefs` so the resolver can chain through them.
     *
     *  Generic across any Material3-styled app. F-Droid declares
     *  ~110 color tokens (md_theme_background, md_theme_primary,
     *  md_theme_secondaryContainer, etc.); without this parser they
     *  were inaccessible and chrome painters fell back to Material's
     *  baseline defaults.
     *
     *  AOSP equivalent path: aapt2 compiles `colors.xml` into the
     *  resource table; `Resources.getColor(R.color.X)` looks it up by
     *  ID. We work by NAME because our painter callers don't have R.X
     *  IDs -- they ask for a Material attribute (e.g.
     *  "colorSecondaryContainer") which the cascade resolves to a
     *  `@color/X` ref, then we look up by name. */
    /** Config dirs in PRECEDENCE order (low -> high). We present as a
     *  modern device (the emulator/reference is API 34), so the
     *  API-qualified resource variants OVERRIDE the base ones -- this
     *  is how aapt selects resources on a real Android 12+/14 device.
     *  It is what activates Material You dynamic color: the populated
     *  ThemeOverlay.*.DynamicColors.* styles + gm3_sys_color_dynamic_*
     *  colors live ONLY in values-v31/v34, and the dynamic palette
     *  routes through @android:color/system_* (resolved by
     *  macapkSystemPaletteColor). Generic over any Material3/MaterialYou
     *  APK; keyed on config qualifiers, never on a package. */
    // ---- CONFIG_DIRS / resolvedConfigDirs(): REMOVED 2026-08-20 --------------
    //
    // This was `{"res/values", "res/values-v31", "res/values-v34"}`, walked by
    // loadColorValues and loadStyleValues INSTEAD of matchingValueDirs(). Every
    // other resource family (strings, plurals, integers, arrays, dimens, bools,
    // attrs) already went through matchingValueDirs/qualifierScore, so colours
    // and styles were the only two that could not see ANY qualifier outside the
    // three spelled here.
    //
    // The consequence was a real, fleet-wide, level-independent bug:
    // **res/values-night was never read for colours or styles**, so an app's
    // dark-theme override simply did not exist to us. Measured on Aurora Store
    // by driving this class offline (debug/levelprobe.sh): its AppTheme resolved
    // parent Theme.Material3.DynamicColors.LIGHT.NoActionBar in NIGHT mode at
    // presented 29, 33 and 34 alike, although res/values-night/styles.xml
    // declares ...DARK.NoActionBar. At presented 34 the app's own
    // res/values-v34/colors.xml then became reachable and mapped the theme onto
    // the *_light role aliases, so android:colorBackground resolved #fffefbff
    // and the runtime measured that exact colour across 72% of the screen. That
    // is the "Aurora Store renders ALL WHITE" regression that got
    // MACAPK_SDK_FROM_TARGET reverted on 2026-08-20 without a diagnosis.
    // 36 of 67 decoded apps on this machine ship values-night resources.
    //
    // Routing both loaders through matchingValueDirs("values") is not a
    // widening, it is the removal of a special case, and it preserves what the
    // hardcoded list was actually FOR:
    //   - qualifierScore accepts -v<N> iff cfgResSdk >= N, and cfgResSdk is 34
    //     on the default path (macapkUnifiedResSdk -> MACAPK_ARSC_RES_SDK), so
    //     values-v31 and values-v34 are still both served by default. The
    //     force-serve is preserved by construction rather than by a literal.
    //   - AOSP ResTable_config precedence puts night ABOVE version, and so does
    //     qualifierScore (night +300, -v34 +35), so a values-night entry wins
    //     over a values-v34 one. That is the correct answer and the hardcoded
    //     list could not express it at all.
    //   - At an EXPLICIT low level the v31/v34 dirs now drop out, which is what
    //     a real device of that level does. Measured harmless: MACAPK_SDK_INT=29
    //     (cfgResSdk 29, both dirs dropped) renders Aurora at the same modal
    //     colour and the same coverage as the default cell.
    //
    // Removing the last caller also retires one of the two PresentedSdk
    // .isExplicit() carve-outs. That carve-out was measured to be a NO-OP for
    // any presented level >= 34 (it only filtered values-v<N> for N > level, and
    // the list topped out at v34), which is why the Aurora white screen turned
    // out to be the SDK_INT change alone and not the resource-selection switch.

    // ===== Material You dynamic color =====
    //
    // The default (no-custom-wallpaper) Material You system palette.
    //
    // ⚠ SINCE 2026-09-26 THIS TABLE IS THE FALLBACK, NOT THE ANSWER (MACAPK_MONET_WALLPAPER_SEED,
    // default ON). On a device SystemUI's ThemeOverlayController derives the whole system_*
    // block from the WALLPAPER and installs it as fabricated overlays; the end of the static
    // block below now does the same from the Mac desktop picture (com.macapk.loader.monet,
    // AOSP android14-release's extraction + ColorScheme + SchemeTonalSpot, byte-exact against
    // CTS's valid_themes.xml). This table stands only when the gate is off or the host shows
    // no readable picture. Measured against the REAL android14 answer for its own seed
    // (GOOGLE_BLUE, which is what an achromatic wallpaper derives to): the 5 ramps differ only
    // at _500 (by 1 per channel, the table sits at L* 50 instead of Shades.MIDDLE_LSTAR 49.6),
    // but 26 role aliases differ (surfaces T98/T6 not T99/T10, the M3-baseline error roles
    // instead of monet's hue-25/chroma-84 palette, outline_dark T60 not T50). They are left
    // as they were so the gate-off path stays the pre-change palette byte for byte; see
    // docs/monet-wallpaper-seed_2026-09-26.md before "fixing" them.
    //
    // ⚠ PROVENANCE CORRECTED 2026-08-20. This used to say the ramps are "as
    // AOSP ships it in frameworks/base/core/res/res/values/colors.xml (the
    // Google Blue 0xFF1B6EF3 seed)". They are NOT from that file. Measured
    // against android14-release: AOSP's system_accent1_600 is #00668B (teal)
    // where ours is #475D92, and its system_neutral1_900 is #191C1E where ours
    // is #1A1B20. THE VALUES ARE KEPT ANYWAY and that is deliberate: AOSP's
    // statics are placeholders a booted device OVERWRITES from the wallpaper,
    // PROVENANCE, settled 2026-08-30 (this replaces a RECONCILE flag that was half right).
    //
    // These are NOT "an emulator capture". They are the SysUI monet TONAL_SPOT palette generated
    // from the no-usable-seed fallback seed GOOGLE_BLUE 0xFF1B6EF3 --
    // packages/SystemUI/monet/src/com/android/systemui/monet/ColorScheme.kt (android14-release)
    // substitutes it when `seed == Color.TRANSPARENT` or the seed's CAM chroma < 5; the TONAL_SPOT
    // specs are a1 chroma 36, a2 chroma 16, a3 hue+60 chroma 24, n1 chroma 6, n2 chroma 8.
    // REGENERATED 2026-08-30 with our OWN CamColor (after `debug/levelprobe.sh --cam-gate` printed
    // CAM GATE OK): 64 of 65 rows match within delta<=2 per channel and 30 are byte-exact,
    // including every load-bearing tone (a1_600 #475D92, a2_100 #DCE2F9, a3_400/500/600 #725572,
    // n1_100-800, n2_600-900). The sole outlier is accent3_10 (#FFFBFF vs #FFFBFA, delta 5 in blue
    // at tone 99 -- both essentially white, nothing paints it at size).
    //
    // A fixed table is the RIGHT shape while Mac.apk has no wallpaper: a device derives these from
    // the wallpaper seed via CAM16/HCT, and with no usable seed monet's own code falls back to
    // GOOGLE_BLUE, so (seed, TONAL_SPOT) -> palette is deterministic and this is a cache of exactly
    // what AOSP would compute.
    //
    // ⚠ Do NOT "fix" these by matching AOSP's core/res/values/colors.xml statics (accent1_600
    // #00668B teal and friends). Those carry the comment "This value can be overlaid at runtime by
    // OverlayManager RROs" -- they are placeholders a booted device REPLACES, so not matching them
    // is correct.
    //
    // Historical note, since a previous version of this comment cited "the API-34 emulator
    // reference this project compares against" and a later reader could not find one: that AVD was
    // real and was named `crossy-test` (API 34 arm64 google_apis; changelog v1.0.245 / v1.0.360 /
    // v1.0.436). It was deleted under disk pressure. Its surviving captures are
    // `debug/refs/ref_calculator.png` and `debug/refs/fdroid_emulator_android14_gesturenav.png`.
    // and ours are the better match to the API-34 emulator reference this
    // project compares against. What was wrong was the citation, not the data,
    // and a wrong citation is worse than none because the next session goes and
    // "fixes" the numbers to match a file that no running device serves.
    // Apps reference these via
    // @android:color/system_accentN_TTT / system_neutralN_TTT and the
    // API-34 role aliases @android:color/system_<role>_<light|dark>.
    // Real Android fills these at runtime from the wallpaper; with no
    // wallpaper it falls back to exactly this table. Calculator's
    // GoogleMaterial3 dynamic-color overlay routes colorTertiary etc.
    // here (-> system_tertiary_light -> accent3 tone-40 mauve), which
    // is what the emulator reference shows. Verified ramps match the
    // measured reference (accent3_600=#725572 mauve = the '=' button,
    // accent1_100=#D9E2FF the AC, accent2_100=#DCE2F9 the operators).
    private static final java.util.Map<String, Integer> SYS_PALETTE =
        new SystemPalette();

    /** SYS_PALETTE's type: the fixed table built by the static block below, with the
     *  wallpaper overlay (com.macapk.loader.monet) laid over it on the FIRST READ instead of
     *  inside this class's <clinit>.
     *
     *  WHY (2026-09-26, speed). Every app initialises ResourceIndex early and the main thread
     *  waits on that class init, but only dynamic-colour apps ever read a system_* colour.
     *  Resolving inside <clinit> made every app -- every game in the protected sweep, every
     *  app presented below SDK 31 -- pay the resolve: a desktop query + cache read each
     *  launch, and ~0.15-0.45 s of render+quantize on the first launch after a wallpaper
     *  change. Now <clinit> only STARTS it (Monet.prefetch, a daemon thread) and the first
     *  get() joins it through Monet.state()'s monitor, by which time it has usually finished.
     *  An app that never reads the palette never waits. debug/monet_gate.sh row clinit-lazy
     *  proves <clinit> completes while the resolve is held.
     *
     *  Threading: put() is only called by the static block (single-threaded, before any
     *  read). settle() writes the overlay under this object's monitor and THEN sets the
     *  volatile flag; every reader checks the flag first, so a reader sees either none of the
     *  overlay (and settles, blocking) or all of it. The two consumers only call get(); the
     *  gate's reflective TreeMap copy goes through size()/entrySet(). With the gate off the
     *  overlay is null and this answers exactly the table the static block built. */
    private static final class SystemPalette extends java.util.AbstractMap<String, Integer> {
        private final HashMap<String, Integer> m = new HashMap<>();
        private volatile boolean settled;

        @Override public Integer put(String k, Integer v) { return m.put(k, v); }

        private void settle() {
            if (settled) return;
            synchronized (this) {
                if (settled) return;
                try {
                    java.util.Map<String, Integer> monet =
                            com.macapk.loader.monet.Monet.systemPaletteOverlay();
                    if (monet != null) m.putAll(monet);
                } catch (Throwable t) {
                    // The fixed table is the pre-change answer; keep it. Even a missing
                    // class must not take every system_* lookup down with it.
                }
                settled = true;
            }
        }

        @Override public Integer get(Object k) { settle(); return m.get(k); }
        @Override public boolean containsKey(Object k) { settle(); return m.containsKey(k); }
        @Override public int size() { settle(); return m.size(); }
        @Override public java.util.Set<java.util.Map.Entry<String, Integer>> entrySet() {
            settle();
            return java.util.Collections.unmodifiableMap(m).entrySet();
        }
    }
    private static final int[] SYS_TONES =
        {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
    private static void putRamp(String prefix, int[] vals) {
        for (int i = 0; i < SYS_TONES.length; i++) {
            SYS_PALETTE.put(prefix + SYS_TONES[i], 0xFF000000 | vals[i]);
        }
    }
    private static int ramp(int[] r, int tone) {
        for (int i = 0; i < SYS_TONES.length; i++) {
            if (SYS_TONES[i] == tone) return 0xFF000000 | r[i];
        }
        return 0xFF000000 | r[0];
    }
    static {
        int[] a1 = {0xFFFFFF,0xFEFBFF,0xEEF0FF,0xD9E2FF,0xB0C6FF,0x94AAE4,
                    0x7A90C8,0x6076AC,0x475D92,0x2F4578,0x152E60,0x001945,0x000000};
        int[] a2 = {0xFFFFFF,0xFEFBFF,0xEEF0FF,0xDCE2F9,0xC0C6DC,0xA4ABC1,
                    0x8A90A5,0x70778B,0x575E71,0x404659,0x2A3042,0x151B2C,0x000000};
        int[] a3 = {0xFFFFFF,0xFFFBFF,0xFFEBFA,0xFDD7FA,0xE0BBDD,0xC3A0C1,
                    0xA886A6,0x8C6D8C,0x725572,0x593D59,0x412742,0x2A122C,0x000000};
        int[] n1 = {0xFFFFFF,0xFEFBFF,0xF1F0F7,0xE2E2E9,0xC6C6CD,0xABABB1,
                    0x909097,0x76777D,0x5D5E64,0x45464C,0x2F3036,0x1A1B20,0x000000};
        int[] n2 = {0xFFFFFF,0xFEFBFF,0xF0F0FA,0xE1E2EC,0xC5C6D0,0xA9ABB4,
                    0x8F9099,0x757780,0x5C5E67,0x44464F,0x2E3038,0x191B23,0x000000};
        putRamp("system_accent1_", a1);
        putRamp("system_accent2_", a2);
        putRamp("system_accent3_", a3);
        putRamp("system_neutral1_", n1);
        putRamp("system_neutral2_", n2);
        // API-34 role aliases. M3 light scheme tone assignments:
        // base role = T40 (_600), container = T90 (_100), on-X = T100
        // (_0, white), on-container = T10 (_900); dark scheme inverts.
        // Light:
        SYS_PALETTE.put("system_primary_light",              ramp(a1, 600));
        SYS_PALETTE.put("system_on_primary_light",           ramp(a1, 0));
        SYS_PALETTE.put("system_primary_container_light",    ramp(a1, 100));
        SYS_PALETTE.put("system_on_primary_container_light", ramp(a1, 900));
        SYS_PALETTE.put("system_secondary_light",            ramp(a2, 600));
        SYS_PALETTE.put("system_on_secondary_light",         ramp(a2, 0));
        SYS_PALETTE.put("system_secondary_container_light",  ramp(a2, 100));
        SYS_PALETTE.put("system_on_secondary_container_light", ramp(a2, 900));
        SYS_PALETTE.put("system_tertiary_light",             ramp(a3, 600));
        SYS_PALETTE.put("system_on_tertiary_light",          ramp(a3, 0));
        SYS_PALETTE.put("system_tertiary_container_light",   ramp(a3, 100));
        SYS_PALETTE.put("system_on_tertiary_container_light", ramp(a3, 900));
        SYS_PALETTE.put("system_surface_light",              ramp(n1, 10));
        SYS_PALETTE.put("system_on_surface_light",           ramp(n1, 900));
        SYS_PALETTE.put("system_surface_variant_light",      ramp(n2, 100));
        SYS_PALETTE.put("system_on_surface_variant_light",   ramp(n2, 700));
        SYS_PALETTE.put("system_surface_bright_light",       ramp(n1, 10));
        SYS_PALETTE.put("system_surface_dim_light",          ramp(n1, 200));
        // The LIGHT surface-container ladder, AOSP literals (2026-08-20).
        // It used to be ramp-derived and COLLAPSED: container_light and
        // container_high_light were BOTH ramp(n1,50), so two adjacent M3
        // elevation steps painted the identical colour and every card, sheet
        // and menu read flat. low_light was ramp(n1,10) = #FEFBFF, i.e. the
        // plain surface, so the lowest container was invisible too.
        // The cause is structural, not a typo: this ladder needs M3 tones
        // 94/92/90/96/98 and SYS_TONES carries none of them, which is the same
        // reason the DARK ladder below is literals. Values read from AOSP
        // android14-release core/res/res/values/colors.xml. Safe to adopt even
        // though our ACCENT ramps differ from AOSP's (ours blue, AOSP teal):
        // these are NEUTRAL-derived, and our neutral ramp matches AOSP's
        // closely (n1 tone 900 = 0x1A1B20 against AOSP #191C1E).
        SYS_PALETTE.put("system_surface_container_lowest_light",  0xFFFFFFFF);
        SYS_PALETTE.put("system_surface_container_low_light",     0xFFF5F3F7);
        SYS_PALETTE.put("system_surface_container_light",         0xFFEFEDF1);
        SYS_PALETTE.put("system_surface_container_high_light",    0xFFE9E7EC);
        SYS_PALETTE.put("system_surface_container_highest_light", 0xFFE3E2E6);
        SYS_PALETTE.put("system_background_light",           ramp(n1, 10));
        SYS_PALETTE.put("system_on_background_light",        ramp(n1, 900));
        SYS_PALETTE.put("system_outline_light",              ramp(n2, 500));
        SYS_PALETTE.put("system_outline_variant_light",      ramp(n2, 200));
        SYS_PALETTE.put("system_error_light",                0xFFB3261E);
        SYS_PALETTE.put("system_on_error_light",             0xFFFFFFFF);
        SYS_PALETTE.put("system_error_container_light",      0xFFF9DEDC);
        SYS_PALETTE.put("system_on_error_container_light",   0xFF410E0B);
        // Dark scheme (base role = T80 (_200), container = T30 (_700),
        // on-X = T20 (_800), on-container = T90 (_100)).
        SYS_PALETTE.put("system_primary_dark",               ramp(a1, 200));
        SYS_PALETTE.put("system_on_primary_dark",            ramp(a1, 800));
        SYS_PALETTE.put("system_primary_container_dark",     ramp(a1, 700));
        SYS_PALETTE.put("system_on_primary_container_dark",  ramp(a1, 100));
        SYS_PALETTE.put("system_secondary_dark",             ramp(a2, 200));
        SYS_PALETTE.put("system_secondary_container_dark",   ramp(a2, 700));
        SYS_PALETTE.put("system_tertiary_dark",              ramp(a3, 200));
        SYS_PALETTE.put("system_on_tertiary_dark",           ramp(a3, 800));
        SYS_PALETTE.put("system_tertiary_container_dark",    ramp(a3, 700));
        SYS_PALETTE.put("system_surface_dark",               ramp(n1, 900));
        SYS_PALETTE.put("system_on_surface_dark",            ramp(n1, 100));
        // AOSP literal: ramp(n1,700) was #45464C against AOSP's #343538, a
        // delta of 20 per channel and the largest step in the dark ladder.
        SYS_PALETTE.put("system_surface_container_highest_dark",  0xFF343538);
        SYS_PALETTE.put("system_background_dark",            ramp(n1, 900));
        SYS_PALETTE.put("system_on_background_dark",         ramp(n1, 100));
        // The dark half of this table used to stop here, with 14 entries
        // against the light half's 31, so 17 dark roles and all 12 API-34
        // "fixed" roles resolved to nothing. Measured 2026-08-20: of 14 probed
        // *_dark aliases only 4 answered. An app on a dynamic-colour dark theme
        // therefore got a HALF-TRANSPARENT ColorScheme even once the theme
        // polarity was right. Derived from the SAME ramps as the light block so
        // both polarities stay on one seed; android ramp tone = 1000 - 10 * M3 tone.
        SYS_PALETTE.put("system_on_secondary_dark",            ramp(a2, 800));
        SYS_PALETTE.put("system_on_secondary_container_dark",  ramp(a2, 100));
        SYS_PALETTE.put("system_on_tertiary_container_dark",   ramp(a3, 100));
        SYS_PALETTE.put("system_surface_variant_dark",         ramp(n2, 700));
        SYS_PALETTE.put("system_on_surface_variant_dark",      ramp(n2, 200));
        // AOSP serves ONE outline value in both polarities (light == dark ==
        // its own neutral2_500), so this mirrors the light entry rather than
        // the M3 spec's lighter T60.
        SYS_PALETTE.put("system_outline_dark",                 ramp(n2, 500));
        SYS_PALETTE.put("system_outline_variant_dark",         ramp(n2, 700));
        // M3 puts surface and surfaceDim at the same neutral tone. Our 13-slot
        // ramp has no tone 6, so both approximate it with tone 10: coherent
        // with system_surface_dark above, not exact.
        SYS_PALETTE.put("system_surface_dim_dark",             ramp(n1, 900));
        // The dark surface-container ladder needs M3 tones 4/12/17/24, none of
        // which SYS_TONES carries. Deriving them from the nearest available
        // tone would collapse three ladder steps onto one value and flatten
        // every elevated surface, so these five stay AOSP literals. Replace
        // them if the ramp ever gains those tones.
        SYS_PALETTE.put("system_surface_container_lowest_dark", 0xFF0D0E11);
        SYS_PALETTE.put("system_surface_container_low_dark",    0xFF1B1B1F);
        SYS_PALETTE.put("system_surface_container_dark",        0xFF1F1F23);
        SYS_PALETTE.put("system_surface_container_high_dark",   0xFF292A2D);
        SYS_PALETTE.put("system_surface_bright_dark",           0xFF38393C);
        // The M3 baseline error palette is fixed rather than seed-derived.
        // These are the baseline DARK values, matching the light block above.
        SYS_PALETTE.put("system_error_dark",                    0xFFF2B8B5);
        SYS_PALETTE.put("system_on_error_dark",                 0xFF601410);
        SYS_PALETTE.put("system_error_container_dark",          0xFF8C1D18);
        SYS_PALETTE.put("system_on_error_container_dark",       0xFFF9DEDC);
        // The API-34 "fixed" roles carry LIGHT-scheme tones in BOTH polarities,
        // which is why they have no _light/_dark suffix. All twelve derive.
        SYS_PALETTE.put("system_primary_fixed",                ramp(a1, 100));
        SYS_PALETTE.put("system_primary_fixed_dim",            ramp(a1, 200));
        SYS_PALETTE.put("system_on_primary_fixed",             ramp(a1, 900));
        SYS_PALETTE.put("system_on_primary_fixed_variant",     ramp(a1, 700));
        SYS_PALETTE.put("system_secondary_fixed",              ramp(a2, 100));
        SYS_PALETTE.put("system_secondary_fixed_dim",          ramp(a2, 200));
        SYS_PALETTE.put("system_on_secondary_fixed",           ramp(a2, 900));
        SYS_PALETTE.put("system_on_secondary_fixed_variant",   ramp(a2, 700));
        SYS_PALETTE.put("system_tertiary_fixed",               ramp(a3, 100));
        SYS_PALETTE.put("system_tertiary_fixed_dim",           ramp(a3, 200));
        SYS_PALETTE.put("system_on_tertiary_fixed",            ramp(a3, 900));
        SYS_PALETTE.put("system_on_tertiary_fixed_variant",    ramp(a3, 700));

        // Material You from the Mac wallpaper (MACAPK_MONET_WALLPAPER_SEED, default ON; =0
        // leaves this table exactly as built above). What ThemeOverlayController.createOverlays
        // installs for the wallpaper's seed: system_{accent1,2,3,neutral1,2}_{10..1000} from
        // ColorScheme(seed, TONAL_SPOT), system_<role>_{light,dark} and the 12 fixed roles from
        // SchemeTonalSpot. Overlaid ON TOP of the table, as a FabricatedOverlay sits on top of
        // framework-res: the _0 shades, which AOSP never overlays, keep their static #ffffff /
        // #000000, and roles the table never had (control_*, text_*_inverse*,
        // palette_key_color_*) start answering from the seed as they do on a device. Resolved
        // once per process, cached on disk per wallpaper identity (Monet.state()), and applied
        // LAZILY by SystemPalette.settle() on the first read; here we only start it on its own
        // thread so it overlaps the rest of boot. Any failure keeps the table. Guarded, so even
        // a missing class cannot fail this <clinit> and take every resource lookup down with it.
        try {
            com.macapk.loader.monet.Monet.prefetch();
        } catch (Throwable t) {
            // No prefetch: the first system_* read resolves inline.
        }
    }

    // ---- @android:color/system_* by INTEGER id ------------------------------
    //
    // WHY (2026-08-20). One resource must resolve to the same value whether it
    // is asked for by NAME or by ID. It did not: @android:color/system_surface_dark
    // as text returned 0xFF1A1B20 through macapkSystemPaletteColor, while
    // getColor(0x01060097) returned 0, because resolveColorById had no id->name
    // source without the (then opt-in) platform view and deliberately excluded
    // system_* even with it. The id path is the one that matters: Compose Material3's
    // ColorResourceHelper.getColor(context, android.R.color.system_*) reads
    // these BY ID, never by name, so an entire dynamic ColorScheme came back
    // fully transparent. Measured on Aurora Store: every system_* id answered
    // NULL, and at presented 34 that left the window background showing through
    // across 72 percent of the screen.
    //
    // The ids are public API and CONTIGUOUS. Verified against AOSP
    // core/res/res/values/public-final.xml (android14-release): 166 system_*
    // colour ids run 0x0106001c..0x010600c1 with no gaps; 0x0106001d begins a
    // 5x13 block matching {neutral1,neutral2,accent1,accent2,accent3} x
    // SYS_TONES exactly, and 0x0106005e begins 100 role aliases. So the ramp
    // half is computed and only the role half needs a literal table.
    /** The 28 CLASSIC framework colour ids, IN ID ORDER, from AOSP
     *  core/res/res/values/public-final.xml (android14-release, lines 712-766):
     *  0x01060000 darker_gray .. 0x0106001b holo_blue_bright, contiguous, with
     *  the system_* block picking up immediately after at 0x0106001c. The order
     *  IS the id: never sort this.
     *
     *  Used by resolveColorById to give the DEFAULT (gate-off) path an id->name
     *  source for framework colours; see the comment at its call site. */
    private static final String[] FW_CLASSIC_COLOR_NAMES = {
        "darker_gray", "primary_text_dark", "primary_text_dark_nodisable",
        "primary_text_light", "primary_text_light_nodisable",
        "secondary_text_dark", "secondary_text_dark_nodisable",
        "secondary_text_light", "secondary_text_light_nodisable",
        "tab_indicator_text", "widget_edittext_dark", "white", "black",
        "transparent", "background_dark", "background_light",
        "tertiary_text_dark", "tertiary_text_light",
        "holo_blue_light", "holo_blue_dark", "holo_green_light",
        "holo_green_dark", "holo_red_light", "holo_red_dark",
        "holo_orange_light", "holo_orange_dark", "holo_purple",
        "holo_blue_bright",
    };
    private static final int FW_CLASSIC_COLOR_BASE = 0x01060000;

    /** name for a classic framework colour id, or null if the id is not one. */
    static String macapkClassicFwColorName(int id) {
        int i = id - FW_CLASSIC_COLOR_BASE;
        return (i >= 0 && i < FW_CLASSIC_COLOR_NAMES.length)
                ? FW_CLASSIC_COLOR_NAMES[i] : null;
    }

    private static final int SYS_ID_RAMP_BASE = 0x0106001D;   // system_neutral1_0
    private static final int SYS_ID_ROLE_BASE = 0x0106005E;   // system_primary_container_light
    private static final String[] SYS_ID_RAMPS =
        { "system_neutral1_", "system_neutral2_",
          "system_accent1_", "system_accent2_", "system_accent3_" };
    /** The 100 role aliases in ID ORDER. The order IS the id: never sort this. */
    private static final String[] SYS_ID_ROLES = {
        "system_primary_container_light", "system_on_primary_container_light",
        "system_primary_light", "system_on_primary_light", "system_secondary_container_light",
        "system_on_secondary_container_light", "system_secondary_light",
        "system_on_secondary_light", "system_tertiary_container_light",
        "system_on_tertiary_container_light", "system_tertiary_light",
        "system_on_tertiary_light", "system_background_light", "system_on_background_light",
        "system_surface_light", "system_on_surface_light",
        "system_surface_container_low_light", "system_surface_container_lowest_light",
        "system_surface_container_light", "system_surface_container_high_light",
        "system_surface_container_highest_light", "system_surface_bright_light",
        "system_surface_dim_light", "system_surface_variant_light",
        "system_on_surface_variant_light", "system_outline_light", "system_error_light",
        "system_on_error_light", "system_error_container_light",
        "system_on_error_container_light", "system_control_activated_light",
        "system_control_normal_light", "system_control_highlight_light",
        "system_text_primary_inverse_light",
        "system_text_secondary_and_tertiary_inverse_light",
        "system_text_primary_inverse_disable_only_light",
        "system_text_secondary_and_tertiary_inverse_disabled_light",
        "system_text_hint_inverse_light", "system_palette_key_color_primary_light",
        "system_palette_key_color_secondary_light", "system_palette_key_color_tertiary_light",
        "system_palette_key_color_neutral_light",
        "system_palette_key_color_neutral_variant_light", "system_primary_container_dark",
        "system_on_primary_container_dark", "system_primary_dark", "system_on_primary_dark",
        "system_secondary_container_dark", "system_on_secondary_container_dark",
        "system_secondary_dark", "system_on_secondary_dark", "system_tertiary_container_dark",
        "system_on_tertiary_container_dark", "system_tertiary_dark", "system_on_tertiary_dark",
        "system_background_dark", "system_on_background_dark", "system_surface_dark",
        "system_on_surface_dark", "system_surface_container_low_dark",
        "system_surface_container_lowest_dark", "system_surface_container_dark",
        "system_surface_container_high_dark", "system_surface_container_highest_dark",
        "system_surface_bright_dark", "system_surface_dim_dark", "system_surface_variant_dark",
        "system_on_surface_variant_dark", "system_outline_dark", "system_error_dark",
        "system_on_error_dark", "system_error_container_dark",
        "system_on_error_container_dark", "system_control_activated_dark",
        "system_control_normal_dark", "system_control_highlight_dark",
        "system_text_primary_inverse_dark", "system_text_secondary_and_tertiary_inverse_dark",
        "system_text_primary_inverse_disable_only_dark",
        "system_text_secondary_and_tertiary_inverse_disabled_dark",
        "system_text_hint_inverse_dark", "system_palette_key_color_primary_dark",
        "system_palette_key_color_secondary_dark", "system_palette_key_color_tertiary_dark",
        "system_palette_key_color_neutral_dark",
        "system_palette_key_color_neutral_variant_dark", "system_primary_fixed",
        "system_primary_fixed_dim", "system_on_primary_fixed",
        "system_on_primary_fixed_variant", "system_secondary_fixed",
        "system_secondary_fixed_dim", "system_on_secondary_fixed",
        "system_on_secondary_fixed_variant", "system_tertiary_fixed",
        "system_tertiary_fixed_dim", "system_on_tertiary_fixed",
        "system_on_tertiary_fixed_variant", "system_outline_variant_light",
        "system_outline_variant_dark"
    };

    /** Map a framework colour id inside the Material You block to its AOSP
     *  resource name. Returns null for every other id, app ids included. */
    private static String macapkSystemPaletteName(int id) {
        int r = id - SYS_ID_RAMP_BASE;
        if (r >= 0 && r < SYS_ID_RAMPS.length * SYS_TONES.length) {
            return SYS_ID_RAMPS[r / SYS_TONES.length] + SYS_TONES[r % SYS_TONES.length];
        }
        int a = id - SYS_ID_ROLE_BASE;
        if (a >= 0 && a < SYS_ID_ROLES.length) return SYS_ID_ROLES[a];
        return null;
    }

    /** Resolve @android:color/system_* (the Material You palette) to
     *  the AOSP default-seed value. Returns null for non-system names.
     *  Generic: keyed on the standard system_* names, never on a
     *  package. */
    static Integer macapkSystemPaletteColor(String name) {
        if (name == null) return null;
        return SYS_PALETTE.get(name);
    }

    /** If the theme rooted at `themeStyleName` opts into Material You
     *  dynamic color (its dynamicColorThemeOverlay names a
     *  *GoogleMaterial3* overlay -- Google's themes auto-apply dynamic
     *  color, unlike plain Material3 which needs a programmatic
     *  DynamicColors.applyToActivities opt-in we don't emulate), and
     *  that overlay declares `attrName`, return the overlay's value.
     *  This is the generic discriminator that gives Google Calculator
     *  its dynamic mauve while leaving F-Droid (ThemeOverlay.Material3
     *  .DynamicColors, no programmatic opt-in) on its static cascade. */
    private String dynamicOverlayValue(String themeStyleName, String attrName) {
        if (themeStyleName == null || attrName == null) return null;
        if ("dynamicColorThemeOverlay".equals(attrName)) return null;
        String ov = resolveAttrInThemeImpl(themeStyleName,
            "dynamicColorThemeOverlay", new java.util.HashSet<>());
        if (ov == null) return null;
        String ovn = ov.startsWith("@style/") ? ov.substring(7) : ov;
        if (!ovn.contains("GoogleMaterial3")) return null;
        return resolveAttrInThemeImpl(ovn, attrName, new java.util.HashSet<>());
    }
}
