// android.content.res.Resources -- accessor for compiled XML/raw/drawable
// content keyed by integer resource IDs. v2's resource resolver is the
// piece responsible for mapping those IDs back to APK paths; for now
// this class is a thin stub that exposes the AssetManager and a
// DisplayMetrics cookie. openRawResource gets fleshed out as soon as
// we add the resources.arsc parser.
package android.content.res;

import android.util.DisplayMetrics;

import java.io.IOException;
import java.io.InputStream;

public class Resources {
    /** AOSP core/java/android/content/res/Resources.java:135 --
     *      public static final @AnyRes int ID_NULL = 0;
     *  "A resource identifier that represents no resource." */
    public static final int ID_NULL = 0;

    private final AssetManager assets;
    private final DisplayMetrics metrics;
    private final Configuration config;
    /** AOSP core/java/android/content/res/Resources.java:170 --
     *      @UnsupportedAppUsage
     *      final ClassLoader mClassLoader;
     *  "class loader for the package used to load custom resource classes."
     *  Package-private + non-static + final, exactly as AOSP declares it, so an
     *  app can reach it REFLECTIVELY. Instagram/Facebook ship a Resources
     *  subclass (obfuscated X.RWR) whose attachBaseContext -> getResources()
     *  does:
     *      Field f = Resources.class.getDeclaredField("mClassLoader");
     *      f.setAccessible(true);
     *      f.set(this, <app>.class.getClassLoader());
     *  to force its own class loader onto the Resources. Without this field the
     *  getDeclaredField throws NoSuchFieldException, A00() rethrows it wrapped,
     *  and the FIRST activity (InstagramMainActivity) never attaches -- the app
     *  never boots. A reflective set of a non-static final field of a normal
     *  class succeeds under setAccessible(true) on HotSpot JDK 21 (verified),
     *  the same as it does on ART. Default mirrors AOSP's null-classLoader
     *  fallback (Resources.java:354, getSystemClassLoader()); apps that care
     *  overwrite it as above. */
    final ClassLoader mClassLoader;
    /** Lazily-injected by the Boot loader. Holds the apktool-decoded
     *  resource ID -> file mapping. We avoid a hard dependency on the
     *  loader package here so this class stays in android-framework.jar.
     *
     *  VOLATILE since v1.0.1047. This used to be written exactly once, during
     *  boot, before any other thread existed, so plain field semantics were
     *  enough. Boot.macapkOnSystemAppearanceChanged now REPLACES it at runtime
     *  (a live light/dark flip needs an index built against the new night
     *  qualifier), and the readers are every drawing thread -- without volatile
     *  a reader is permitted to keep serving the retired index indefinitely,
     *  which would present as "the flip worked on some surfaces only". */
    private static volatile Object macapkResourceIndex;
    /** Boot-time hook: package name of the running APK (e.g.
     *  "com.fingersoft.hillclimb"). getResource*Name methods consult
     *  this to mirror AOSP behavior where resource IDs in the 0x7f
     *  range return the app's own package. */
    private static String macapkPackageName = "";

    /** ATTRSTYLE lane, 2026-08-13 (v1.0.968). Default ON; `MACAPK_ATTRSTYLE=0`
     *  restores the pre-lane behaviour of all three fixes in ONE run:
     *    1. `style="?attr"` refs resolving at all (here + ResourceIndex)
     *    2. the Widget.Material.EditText framework-style rows (ResourceIndex)
     *    3. the @android:drawable/edit_text_material underline (below)
     *  A single-binary A/B is the point: fix 3 changes EVERY EditText in every
     *  app, and the fleet capture that measures it takes ~13 minutes per side --
     *  long enough for another lane to land render-affecting work between two
     *  builds and silently confound the delta. Declared separately from
     *  ResourceIndex's identically-named switch because android.content.res and
     *  com.macapk.loader must not have a compile-time dependency in either
     *  direction (see macapkThemeColor's note below); they read the same env var. */
    public static final boolean MACAPK_ATTRSTYLE =
        !"0".equals(System.getenv("MACAPK_ATTRSTYLE"));

    /** Boot-time hook: set the resource index used by openRawResource etc. */
    public static void macapkSetResourceIndex(Object index) {
        macapkResourceIndex = index;
    }
    /** Internal accessor: lets framework code (e.g. PreferenceDefaultsScanner)
     *  pull the ResourceIndex to find the per-app apkt root. */
    public static Object macapkGetResourceIndex() {
        return macapkResourceIndex;
    }

    /** v1.0.128: convenience helpers for chrome painters (View.java,
     *  ViewCanvasDriver) to resolve a theme color or corner-radius
     *  without paying the cross-package reflection cost at each
     *  per-frame paint call. Internally still goes through reflection
     *  because Resources lives in android.content.res and
     *  ResourceIndex lives in com.macapk.loader (the two packages
     *  must not have a compile-time dependency in either direction --
     *  Resources is in the bootstrap JAR loaded before macapk-
     *  loader.jar). Cached method handles keep the per-call cost
     *  amortized to a HashMap lookup + invoke.
     *
     *  Generic for any APK with a Material3 theme cascade. */
    private static volatile java.lang.reflect.Method macapkColorMethod;
    private static volatile java.lang.reflect.Method macapkCornerMethod;
    public static int macapkThemeColor(String attrName, int fallback) {
        if (macapkResourceIndex == null) return fallback;
        try {
            java.lang.reflect.Method m = macapkColorMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass().getMethod(
                    "resolveApplicationThemeColor", String.class, int.class);
                macapkColorMethod = m;
            }
            Object r = m.invoke(macapkResourceIndex, attrName, fallback);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {}
        return fallback;
    }
    // Resolve a color resource id to ARGB via the ResourceIndex (colors.xml).
    // getColor(id) historically returned 0, which zeroed every resource-backed
    // color -- notably Compose's colorResource(R.color.X) text colors, drawn
    // transparent. Returns `fallback` when the id isn't a known color resource.
    /** Null when the id is NOT a colour resource -- the distinction the
     *  drawable path needs, which macapkColorById's fallback form erases. */
    static Integer macapkResolveColorById(int id) {
        if (macapkResourceIndex == null || id == 0) return null;
        try {
            java.lang.reflect.Method m = macapkColorByIdMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass().getMethod("resolveColorById", int.class);
                macapkColorByIdMethod = m;
            }
            Object r = m.invoke(macapkResourceIndex, id);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {}
        return null;
    }

    private static volatile java.lang.reflect.Method macapkColorByIdMethod;
    public static int macapkColorById(int id, int fallback) {
        if (macapkResourceIndex == null) return fallback;
        try {
            java.lang.reflect.Method m = macapkColorByIdMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass().getMethod("resolveColorById", int.class);
                macapkColorByIdMethod = m;
            }
            Object r = m.invoke(macapkResourceIndex, id);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {}
        return fallback;
    }
    public static float macapkThemeCornerDp(String shapeAttrName, float fallbackDp) {
        if (macapkResourceIndex == null) return fallbackDp;
        try {
            java.lang.reflect.Method m = macapkCornerMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass().getMethod(
                    "resolveApplicationThemeCornerDp", String.class, float.class);
                macapkCornerMethod = m;
            }
            Object r = m.invoke(macapkResourceIndex, shapeAttrName, fallbackDp);
            if (r instanceof Float) return (Float) r;
        } catch (Throwable ignored) {}
        return fallbackDp;
    }

    /** Boot-time hook: set the package name returned by
     *  getResourcePackageName / getResourceName for 0x7f...  ids. */
    public static void macapkSetPackageName(String pkg) {
        macapkPackageName = pkg != null ? pkg : "";
    }

    public Resources(AssetManager assets,
                     DisplayMetrics metrics,
                     Configuration config) {
        this.assets  = assets;
        this.metrics = metrics != null ? metrics : new DisplayMetrics();
        // A null configuration gets the phone defaults, as it did before
        // `new Configuration()` became AOSP's all-UNDEFINED delta
        // (MACAPK_CONFIG_UNSET_CTOR). A caller's unset or partial one gets what
        // AOSP's ResourcesImpl constructor gives it -- font scale 1, the
        // metrics' density, the default locales -- filled in place, because
        // this object is kept by reference (Boot relies on that aliasing).
        this.config  = config  != null ? config  : Configuration.macapkPhoneDefaults();
        Configuration.macapkApplyResourcesDefaults(this.config, this.metrics);
        // AOSP Resources.java:354 -- the no-classLoader path defaults to the
        // system loader. Our 3-arg constructor carries no ClassLoader (and it
        // is the super() every Resources subclass, incl. Instagram's X.RWR,
        // chains through), so mirror that fallback. Apps that need their own
        // loader set it reflectively (see the mClassLoader field comment).
        this.mClassLoader = ClassLoader.getSystemClassLoader();
    }

    /** AOSP Resources.getClassLoader() (core/java/android/content/res/Resources.java:414):
     *  "@hide public ClassLoader getClassLoader() { return mClassLoader; }". */
    public ClassLoader getClassLoader() { return mClassLoader; }

    public AssetManager getAssets()    { return assets;  }
    public DisplayMetrics getDisplayMetrics() { return metrics; }
    public Configuration getConfiguration()   { return config;  }

    // Cached the same way macapkColorMethod/macapkCornerMethod above are:
    // openRawResource is on the hot path for every drawable/xml/raw lookup
    // (getDrawable alone calls it up to twice per id on a cache miss, see
    // the DrawableCache below), and re-resolving getClass().getMethod(...)
    // on every single call was pure waste -- the resolved Method for a
    // (name, paramTypes) pair never changes for the life of the process
    // (macapkSetResourceIndex is called at most once per real launch; see
    // docs/efficiency-assets-datadir_2026-07-24.md Finding 3).
    private static volatile java.lang.reflect.Method macapkOpenRawMethod;
    public InputStream openRawResource(int id) throws android.content.res.Resources.NotFoundException {
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkOpenRawMethod;
                if (m == null) {
                    m = macapkResourceIndex.getClass().getMethod("openRaw", int.class);
                    macapkOpenRawMethod = m;
                }
                Object stream = m.invoke(macapkResourceIndex, id);
                if (stream instanceof InputStream) return (InputStream) stream;
            } catch (Throwable t) {
                throw new NotFoundException("openRawResource(0x" + Integer.toHexString(id) + ") failed: " + t);
            }
            // The index IS attached; it just has no file for this id. Say so --
            // the old code fell through to the "no resource index attached"
            // message below, which sent readers hunting a boot-order bug that
            // was not there. The "no resource index" wording also collides with
            // a known TESTING artifact (a selectively-deleted _apkt dir), which
            // made the two indistinguishable in a log. Anuto TD's real failure
            // was `openRawResource(0x0)` -- id ZERO, i.e. the CALLER's lookup
            // returned 0, not a missing index.
            throw new NotFoundException("openRawResource(0x"
                + Integer.toHexString(id) + ") -- index has no file for this id"
                + (id == 0 ? " (id is ZERO: the caller's own id lookup failed)" : ""));
        }
        throw new NotFoundException("openRawResource(0x" + Integer.toHexString(id) + ") -- no resource index attached");
    }
    public InputStream openRawResource(int id, android.util.TypedValue value)
        throws android.content.res.Resources.NotFoundException {
        return openRawResource(id);
    }

    /** Return an XmlResourceParser positioned at the start of the XML
     *  resource identified by `id`. AOSP signature; throws
     *  NotFoundException if the id isn't a known XML resource.
     *  androidx.preference.PreferenceManager.inflateFromResource calls
     *  this; without it, F-Droid (and any preference-XML-driven app)
     *  fails Application onCreate with NoSuchMethodError.
     *
     *  Resolution:
     *  1. Route through the ResourceIndex's openXml(id) -- it locates
     *     the file under apktoolDir/res/xml/ (or res/layout/, etc.)
     *     by the type/name registered in public.xml.
     *  2. Construct a DomXmlResourceParser over the returned stream.
     *  3. The parser is loaded reflectively so this framework class
     *     stays decoupled from com.macapk.loader at link time. */
    private static final boolean sXmlTrace = System.getenv("MACAPK_MENU_TRACE") != null;
    public XmlResourceParser getXml(int id)
        throws android.content.res.Resources.NotFoundException {
        if (sXmlTrace) com.macapk.loader.NativeLog.i("Mac.apk.Menu",
            "Resources.getXml(0x" + Integer.toHexString(id) + ")");
        // MACAPK_PLATFORM_LAYOUTS: a framework LAYOUT file answers the same
        // XmlBlock-semantics parser getLayout does. On a device the two are one
        // call -- getXml is loadXmlResourceParser(id, "xml") and getLayout is
        // loadXmlResourceParser(id, "layout"), the type string only naming the
        // resource in a NotFoundException (Resources.java:1284-1336, aosp main)
        // -- so inflate(res.getXml(android.R.layout.x), ...) must read the same
        // ids getLayout's parser does; the numeric DOM below spells them
        // "@0x01020014", which the inflater can only hash. Layout files only:
        // the framework res/xml, res/color, res/menu and drawable consumers were
        // verified on the DOM parser and keep it. =0 restores the old path.
        if (MACAPK_PLATFORM_LAYOUTS && (id >>> 24) == 0x01) {
            XmlResourceParser p = macapkPlatformXmlParser(id, true);
            if (p != null) return p;
        }
        // A platform file comes out of the parse memo (macapkFwXmlDoc), the
        // same DOM the compiled branch below would build; every other id is
        // opened and parsed per call, as before.
        final org.w3c.dom.Document fwDoc = macapkFwXmlDoc(id);
        if (fwDoc != null) {
            try {
                return (XmlResourceParser) Class.forName("com.macapk.loader.DomXmlResourceParser")
                    .getDeclaredConstructor(org.w3c.dom.Document.class).newInstance(fwDoc);
            } catch (Throwable t) {
                throw new NotFoundException(
                    "getXml(0x" + Integer.toHexString(id) + ") parser init failed: " + t);
            }
        }
        InputStream stream = null;
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass()
                    .getMethod("openXml", int.class);
                Object v = m.invoke(macapkResourceIndex, id);
                if (v instanceof InputStream) stream = (InputStream) v;
            } catch (Throwable ignore) { /* fall through to NotFound */ }
        }
        if (stream == null) {
            throw new NotFoundException(
                "getXml(0x" + Integer.toHexString(id) + ") -- no matching XML resource");
        }
        try {
            // COMPILED-vs-TEXT XML, same discrimination the drawable path already does (see the
            // RES_XML_TYPE comment at loadDrawable's xml branch). App resources arrive as
            // apktool-DECODED text, but a FRAMEWORK id resolved through the platform table is
            // served straight out of framework-res.apk by ResourceIndex.macapkFwOpenRaw and is
            // aapt-COMPILED binary AXML. Handing that to the text DocumentBuilder below fails
            // with "[Fatal Error] :1:1: Content is not allowed in prolog", which getXml then
            // reports as `parser init failed` -- and androidx, which called getXml because its
            // isColorInt() guard said "must be a selector file", propagates that out of the
            // widget constructor. MEASURED (newpipe, MACAPK_FW_VIEW=1): getXml(0x106023b) failed
            // exactly this way -> NavigationView's ctor threw -> the inflater fell back to a
            // FrameLayout -> ClassCastException -> onCreate aborted -> 78.00% -> 6.76% on the
            // fleet. The magic is a RES_XML_TYPE ResChunk_header: u16 type=0x0003,
            // u16 headerSize=0x0008, little-endian; text XML starts '<' or a BOM, never 0x03.
            byte[] bytes = stream.readAllBytes();
            stream.close();
            Class<?> impl = Class.forName("com.macapk.loader.DomXmlResourceParser");
            if (bytes.length >= 4 && bytes[0] == 0x03 && bytes[1] == 0x00
                                  && bytes[2] == 0x08 && bytes[3] == 0x00) {
                // fwxml: named attribute refs, no type annotations posing as
                // attributes (macapkTypeCompiledElement).
                Object doc = macapkParseCompiledXml(bytes);
                return (XmlResourceParser) impl
                    .getDeclaredConstructor(org.w3c.dom.Document.class).newInstance(doc);
            }
            // Constructor-only reflection -- the loader class is on the
            // boot classpath, so this loads from android-framework.jar's
            // sibling jar at runtime.
            Object p = impl.getDeclaredConstructor(InputStream.class)
                .newInstance(new java.io.ByteArrayInputStream(bytes));
            return (XmlResourceParser) p;
        } catch (Throwable t) {
            try { stream.close(); } catch (IOException ignore) {}
            throw new NotFoundException(
                "getXml(0x" + Integer.toHexString(id) + ") parser init failed: " + t);
        }
    }

    public String getString(int id) {
        // Route through the apktool-decoded values/strings.xml index
        // when one is attached. Returning the actual string value is
        // required by SDKs that look up config via getIdentifier +
        // getString -- most prominently FirebaseOptions.fromResource,
        // which reads google_app_id / google_api_key / project_id /
        // gcm_defaultSenderId / firebase_database_url to build a
        // FirebaseOptions. Without these, FirebaseApp.initializeApp()
        // logs "Default FirebaseApp failed to initialize because no
        // default options were found" and every later Firebase API
        // call throws IllegalStateException -- which is what blocks
        // cocos2d-x apps that touch Firebase Analytics during Activity
        // onCreate (the empty std::string -> fopen("") chain).
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass()
                    .getMethod("stringForId", int.class);
                Object v = m.invoke(macapkResourceIndex, id);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) {}
        }
        return "";
    }
    // AOSP getString(int, Object...) -- looks up the string then runs it
    // through String.format with the supplied args. Common for any localized
    // formatted label ("%1$s upvotes"); Reddit's onCreate uses it, and the
    // bare getString(int) overload alone NoSuchMethodError'd.
    public String getString(int id, Object... formatArgs) {
        String raw = getString(id);
        if (formatArgs == null || formatArgs.length == 0) return raw;
        try {
            return String.format(raw, formatArgs);
        } catch (Throwable t) {
            return raw;
        }
    }
    public CharSequence getText(int id) { return getString(id); }
    public CharSequence getText(int id, CharSequence def) {
        CharSequence cs = getString(id);
        return (cs == null || cs.length() == 0) ? def : cs;
    }
    public int getInteger(int id) {
        // Route through the apktool-decoded values/integers.xml index.
        // Used by SDKs that read constants (com.google.android.gms.
        // version = 12451000, etc.) via getInteger(R.integer.X).
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass()
                    .getMethod("integerForId", int.class);
                Object v = m.invoke(macapkResourceIndex, id);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    /** Lookup `@integer/<name>` reference by name (no id). Used by
     *  the manifest meta-data resolver in Application.makeAppInfo
     *  to convert `android:value="@integer/X"` to a real integer.
     *  Returns null when the resource isn't found. */
    public static Integer macapkIntegerForName(String name) {
        if (macapkResourceIndex == null || name == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("integerForName", String.class);
            Object v = m.invoke(macapkResourceIndex, name);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        return null;
    }
    /** Binary-manifest meta-data resolution: integer resource VALUE by
     *  resource ID (the AXML TYPE_REFERENCE case). Same reflection
     *  indirection as macapkIntegerForName. */
    public static Integer macapkIntegerForId(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("integerForId", int.class);
            Object v = m.invoke(macapkResourceIndex, id);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        return null;
    }
    /** Binary-manifest meta-data resolution: STRING resource VALUE by
     *  resource ID. A `<meta-data android:value="@string/foo">` is a
     *  TYPE_REFERENCE whose target is a string resource; real Android
     *  resolves it to the String before the SDK ever reads it (e.g.
     *  Facebook's com.facebook.sdk.ApplicationId = @string/facebook_app_id,
     *  which FacebookSdk.loadDefaultsFromMetadata REJECTS unless it reads
     *  back as a String). Same reflection indirection as macapkIntegerForId. */
    public static String macapkStringForId(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("stringForId", int.class);
            Object v = m.invoke(macapkResourceIndex, id);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return null;
    }
    /** The resource TYPE name ("string" / "integer" / "bool" / "dimen" / ...)
     *  for a resource id, so the meta-data resolver stores a reference with
     *  the Android-correct Bundle type instead of assuming every reference is
     *  an integer. */
    public static String macapkResourceTypeForId(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("typeNameOf", int.class);
            Object v = m.invoke(macapkResourceIndex, id);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return null;
    }
    /** Binary-manifest meta-data resolution: BOOLEAN resource VALUE by id. A
     *  `<meta-data android:value="@bool/foo">` reference resolves to a Boolean in
     *  AOSP's ApplicationInfo.metaData (PackageParser types a resolved
     *  TYPE_INT_BOOLEAN via putBoolean). Same reflection indirection. */
    public static Boolean macapkBoolForId(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("getBoolById", int.class);
            Object v = m.invoke(macapkResourceIndex, id);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {}
        return null;
    }
    /** Binary-manifest meta-data resolution: COLOR resource VALUE (ARGB int) by
     *  id. AOSP resolves a @color reference to its TYPE_INT_COLOR_* data and
     *  putInt's the ARGB value (inside FIRST_INT..LAST_INT). Same indirection. */
    public static Integer macapkColorForId(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("resolveColorById", int.class);
            Object v = m.invoke(macapkResourceIndex, id);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        return null;
    }
    /** Real dimension lookups via the ResourceIndex dimen table
     *  (was `return 0` stubs -- Calculator's formula autosize reads
     *  its min/max text sizes from dimens, got 0..0, and typed text
     *  rendered at size 0 = invisible). Unit handling matches the
     *  rest of the framework: dp/dip/sp scale by the device
     *  density, px/raw pass through. */
    public float getDimension(int id) {
        Float px = macapkDimenPx(id);
        return px != null ? px : 0f;
    }
    public int getDimensionPixelSize(int id) {
        Float px = macapkDimenPx(id);
        // AOSP rounds up and guarantees >= 1 for nonzero dimens.
        if (px == null) return 0;
        if (px == 0f) return 0;
        int r = Math.round(px);
        return r != 0 ? r : (px > 0 ? 1 : -1);
    }
    public int getDimensionPixelOffset(int id) {
        Float px = macapkDimenPx(id);
        return px != null ? (int) (float) px : 0;
    }
    private Float macapkDimenPx(int id) {
        if (macapkResourceIndex == null) return null;
        // fwxml: a PLATFORM dimen is read from the platform table by id. The
        // path below names the id and then looks the NAME up in the app's dimen
        // table, which for a framework id is the wrong table: the platform's
        // button_inset_vertical_material answered null (so 0), and an app dimen
        // that shares a platform dimen's name answered the app's value.
        // macapkFwValueOf hands back the settled (type, data) pair, and the
        // complex encoding decodes exactly as AOSP's getDimension does
        // (TypedValue.complexToDimension).
        if (MACAPK_FW_XML_NUMERIC && (id >>> 24) == 0x01) {
            Object[] fw = macapkFwSimpleValue(id);
            if (fw != null && fw[0] instanceof Integer t && fw[1] instanceof Integer d) {
                if (t == android.util.TypedValue.TYPE_DIMENSION) {
                    return android.util.TypedValue.complexToDimension(d, metrics);
                }
                if (t == android.util.TypedValue.TYPE_FLOAT) return Float.intBitsToFloat(d);
                if (t >= android.util.TypedValue.TYPE_FIRST_INT
                        && t <= android.util.TypedValue.TYPE_LAST_INT) return (float) d;
            }
            // No platform view (or not a simple value): the by-name path below
            // is the only answer left, as before.
        }
        try {
            java.lang.reflect.Method tn = macapkResourceIndex.getClass()
                .getMethod("macapkTypeNameOf", int.class);
            Object t = tn.invoke(macapkResourceIndex, id);
            if (!(t instanceof String[] a) || a.length < 2
                    || !"dimen".equals(a[0])) return null;
            java.lang.reflect.Method dr = macapkResourceIndex.getClass()
                .getMethod("getDimenRaw", String.class);
            Object raw = dr.invoke(macapkResourceIndex, a[1]);
            if (raw == null) return null;
            String v = raw.toString().trim();
            // lane layout, 2026-08-01: chase dimen->dimen reference chains
            // (Material3 aliases like m3_bottom_nav_min_height ->
            // @dimen/m3_comp_navigation_bar_container_height -> 80dp).
            // Previously "@dimen/..." hit Float.parseFloat, threw, and this
            // returned null -> getDimensionPixelSize 0 for EVERY aliased
            // token. Depth-capped against accidental cycles.
            int hops = 0;
            while (v.startsWith("@dimen/") && hops++ < 8) {
                Object next = dr.invoke(macapkResourceIndex, v.substring(7));
                if (next == null) return null;
                v = next.toString().trim();
            }
            int unitEnd = v.length();
            for (int i = 0; i < v.length(); i++) {
                if (Character.isLetter(v.charAt(i))) { unitEnd = i; break; }
            }
            float n = Float.parseFloat(v.substring(0, unitEnd));
            String unit = v.substring(unitEnd);
            // AOSP TypedValue.applyDimension (android11-release): PX v;
            // DIP v*density; SP v*scaledDensity; PT v*xdpi/72; IN v*xdpi;
            // MM v*xdpi/25.4. pt/in/mm previously fell through unconverted,
            // so a 9pt dimen read as 9 raw pixels.
            switch (unit) {
                case "dp": case "dip": return n * metrics.density;
                case "sp":             return n * metrics.scaledDensity;
                case "pt":             return n * metrics.xdpi * (1.0f / 72f);
                case "in":             return n * metrics.xdpi;
                case "mm":             return n * metrics.xdpi * (1.0f / 25.4f);
                default:               return n;
            }
        } catch (Throwable t) { return null; }
    }

    /** {type, name} for a resource id via the ResourceIndex, or null when the
     *  index is absent / the id is unknown. Reflective because ResourceIndex
     *  lives in com.macapk.loader and is loaded by a different loader; the
     *  resolved Method never changes for the life of the process, so it is
     *  cached the same way macapkOpenRawMethod is. */
    private static volatile java.lang.reflect.Method macapkTypeNameMethod;
    static String[] macapkTypeNameOf(int id) {
        if (macapkResourceIndex == null || id == 0) return null;
        try {
            java.lang.reflect.Method m = macapkTypeNameMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass()
                    .getMethod("macapkTypeNameOf", int.class);
                macapkTypeNameMethod = m;
            }
            Object t = m.invoke(macapkResourceIndex, id);
            if (t instanceof String[] a && a.length >= 2) return a;
        } catch (Throwable ignored) {}
        return null;
    }

    /** v1.0.95: AOSP Resources.getValue(int, TypedValue, boolean). Looks
     *  up `id` and fills `outValue` with a resolved TypedValue.
     *  resolveRefs=true means follow @ref/foo references; we ignore
     *  the flag and always do the best we can. Called by
     *  MaterialThemeOverlay during BottomNavigationView <init> to
     *  read theme attribute values.
     *
     *  For our purposes we just need outValue to be non-null with a
     *  reasonable type/data. If we can stringify the resource we
     *  set the string slot; otherwise data stays 0 and type stays
     *  TYPE_NULL. Callers that branch on outValue.type take the
     *  null-default path, which is fine for our stub. */
    public void getValue(int id, android.util.TypedValue outValue, boolean resolveRefs) {
        if (outValue == null) return;
        outValue.resourceId = id;
        // A <color> VALUE resource must report a COLOR_INT type. aapt2 stores
        // `<color name="x">#ff1b1b1b</color>` in the resource table as a colour
        // int (TYPE_INT_COLOR_ARGB8 = 0x1c), NOT as a string, and androidx's
        // ColorStateList loader gates on exactly that:
        //
        //   private static @Nullable ColorStateList inflateColorStateList(
        //           Resources resources, int resId, @Nullable Theme theme) {
        //       if (isColorInt(resources, resId)) {
        //           // The resource is a color int, we can't handle it so return null
        //           return null;
        //       }
        //       final XmlPullParser xml = resources.getXml(resId);
        //       try { return ColorStateListInflaterCompat.createFromXml(...); }
        //       catch (Exception e) { Log.w(TAG, "Failed to inflate ColorStateList..."); }
        //       return null;
        //   }
        //   private static boolean isColorInt(Resources resources, int resId) {
        //       final TypedValue value = getTypedValue();
        //       resources.getValue(resId, value, true);
        //       return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
        //               && value.type <= TypedValue.TYPE_LAST_COLOR_INT;
        //   }
        //
        // (androidx-main, core/core/src/main/java/androidx/core/content/res/
        //  ResourcesCompat.java). Note `resources.getXml(resId)` sits OUTSIDE
        // the try/catch -- so when we report a plain colour as TYPE_NULL the
        // guard says "not a colour int", androidx calls getXml() on a resource
        // that legitimately has no XML file, our NotFoundException is thrown
        // (correctly -- AOSP throws there too) and it ESCAPES into whatever
        // constructor asked for the colour.
        //
        // Measured on Google Calculator 9.1 (2026-07-29): every Material 3
        // dynamic colour (`gm3_sys_color_dynamic_light_on_surface` 0x7f0602fa,
        // `..._secondary_container` 0x7f060304, ...) is a plain <color>, so
        // MaterialToolbar / PadImageButton / ArrowIndicator all died in their
        // constructors with `getXml(0x7f0602fa) -- no matching XML resource`,
        // got FrameLayout placeholders, and onStart's `(MaterialToolbar)
        // findViewById(...)` then threw ClassCastException. Generic: any
        // AppCompat/Material app whose theme feeds a colour VALUE id to
        // TintTypedArray.getColorStateList hits this.
        //
        // Gated on "no FILE backs this id" so a real ColorStateList selector
        // (res/color/foo.xml) still reports its path further down and keeps
        // taking the XML-inflate path, which is also what AOSP does for it.
        // v1.0.917: track the reference chain the serves below follow.
        // AOSP NativeGetResourceValue (android_util_AssetManager.cpp,
        // android11-release) seeds `uint32_t ref = static_cast<uint32_t>
        // (resid)` (line 464 above already reports the queried id) and
        // ResolveReference then overwrites it with each followed reference,
        // so a ref-valued entry reports the chain TERMINUS. Our serves
        // chase inside ResourceIndex, which now publishes each followed id;
        // clear first so a direct value keeps ref = the queried id.
        macapkClearValueOrigin();
        // v1.0.9xx: the typed SOURCE row (arsc ingest, gate ON) answers two
        // things the flattened tables cannot: the stored REFERENCE under
        // resolveRefs=false (AOSP skips ResolveReference entirely and
        // reports TYPE_REFERENCE data=target, resourceId=the queried id),
        // and aapt's source encodings (#rgb -> RGB4, 0x7fff -> INT_HEX).
        // Gate OFF the map is empty and this block is inert.
        if (id != 0) {
            Object[] rawSrc = macapkRawTypedSourceOf(id);
            if (rawSrc != null) {
                int sdt = (Integer) rawSrc[0];
                int sdata = (Integer) rawSrc[1];
                if (sdt == android.util.TypedValue.TYPE_REFERENCE && !resolveRefs) {
                    if (sdata != 0) {
                        outValue.type = android.util.TypedValue.TYPE_REFERENCE;
                        outValue.data = sdata;
                        outValue.string = null;
                        return;
                    }
                } else if (sdt == android.util.TypedValue.TYPE_INT_COLOR_RGB8
                        || sdt == android.util.TypedValue.TYPE_INT_COLOR_ARGB4
                        || sdt == android.util.TypedValue.TYPE_INT_COLOR_RGB4
                        || sdt == android.util.TypedValue.TYPE_INT_HEX) {
                    outValue.type = sdt;
                    outValue.data = sdata;
                    outValue.string = null;
                    return;
                }
            }
        }
        // FRAMEWORK colour ids (package 0x01). The block below only ever fires
        // for APP ids: macapkTypeNameOf reads ResourceIndex.idToTypeName, which
        // is built from the APP's decoded table and has no framework rows, so a
        // framework id fell all the way through to the TYPE_NULL tail of this
        // method. That is the same defect the app-side branch below was written
        // to fix (v1.0.917, Google Calculator), left un-fixed on the other half
        // of the id space.
        //
        // The consequence is the one documented below: androidx's
        // ResourcesCompat.inflateColorStateList calls isColorInt() first, sees
        // TYPE_NULL, concludes "not a colour int, so it must be a selector file",
        // and calls resources.getXml(resId) -- which sits OUTSIDE its try/catch,
        // so our (correct) NotFoundException escapes into the caller.
        //
        // Measured on Aegis 3.4.2 (2026-08-27 fleet census), where the id is
        // 0x0106000d = @android:color/transparent (AOSP core/res/res/values/
        // public-final.xml:735, android14-release -- the colour ids are frozen
        // public API):
        //   Mac.apk.ActivityHost: onStart threw Resources$NotFoundException:
        //     getXml(0x106000d) -- no matching XML resource -- continuing
        //     at androidx...getColorStateList
        //     at com.google.android.material.chip.Chip.setChipBackgroundColorResource
        //     at com.beemdevelopment.aegis.ui.MainActivity.setGroups
        //     at ...MainActivity.onStart
        // onStart aborted, so the entry list never populated -- the app renders
        // at 2.3% coverage. Nothing about it is Aegis-specific: any app handing
        // an android.R.color.* id to ContextCompat/ResourcesCompat.getColorStateList
        // hits it.
        //
        // The platform table answers this directly and authoritatively -- no
        // hand-written id->name table is involved. macapkFwValueOf returns the
        // arsc's OWN {dataType, data, text, leafId} for the id (chasing in-table
        // references exactly as AssetManager2::ResolveReference does), so a
        // framework colour that really IS a file-backed selector on a device
        // (primary_text_dark and friends) does NOT report a colour int here and
        // keeps whatever path it takes today. The discriminator is the real
        // table, not a guess.
        //
        // SCOPE (widened v1.0.1102): EVERY simple type the platform table can answer, not just
        // colours. Until v1.0.1102 this accepted only TYPE_FIRST_COLOR_INT..TYPE_LAST_COLOR_INT
        // because that is what the Aegis case measured, and framework <dimen>/<bool>/<integer>/
        // <string> ids still fell through to the TYPE_NULL tail -- i.e. we answered
        // @android:color/transparent correctly while answering @android:dimen/app_icon_size with
        // "no such resource". AOSP answers all of them from the same table with the same lookup;
        // there is no principled reason for us to split them.
        //
        // What is accepted, and why exactly these: the Res_value simple types that carry their
        // whole value in (dataType, data) -- ints/bools (TYPE_FIRST_INT..TYPE_LAST_INT, which
        // already subsumes the colour range), dimensions, fractions and floats -- plus TYPE_STRING,
        // which additionally needs the table's own text. TYPE_NULL is skipped (it IS the "no
        // value" answer, so returning it here would be indistinguishable from falling through).
        // References and attributes never arrive: macapkFwValueOf chases in-table references to
        // their leaf and returns null for ?attr hops, because the caller's theme machinery owns
        // those. So this branch only ever reports a fully-resolved literal.
        //
        // MACAPK_FW_SIMPLE_VALUES=0 narrows it back to colours only, for bisecting a regression
        // to this change without reverting it.
        if (id != 0 && (id >>> 24) == 0x01) {
            Object[] fw = macapkFwSimpleValue(id);
            if (fw != null && fw[0] instanceof Integer fwType
                    && fw[1] instanceof Integer fwData
                    && macapkFwTypeAccepted(fwType)) {
                outValue.type = fwType;
                outValue.data = fwData;
                // TYPE_STRING carries its payload in the table's text, not in `data`; every other
                // accepted type is fully described by (type, data) and must leave string null.
                outValue.string = (fwType == android.util.TypedValue.TYPE_STRING
                                   && fw[2] instanceof CharSequence cs) ? cs : null;
                if (fwType == android.util.TypedValue.TYPE_STRING && outValue.string == null) {
                    // A string row whose text we could not read is NOT answerable -- fall through
                    // to the existing paths rather than handing back an empty string, which the
                    // caller cannot distinguish from a real empty resource.
                    outValue.type = android.util.TypedValue.TYPE_NULL;
                    outValue.data = 0;
                } else {
                // Report the chain terminus in resourceId when references were
                // followed, matching the app-side branch below and AOSP's
                // out_last_reference behaviour.
                macapkApplyChaseTerminus(outValue, resolveRefs);
                return;
                }
            }
            // No-view terminus. macapkFwSimpleValue needs the platform-arsc
            // view (history: built only under the opt-in arsc gate until
            // v1.0.1103, so the branch above was inert on the then-default
            // path and every framework colour reached the TYPE_NULL tail).
            // resolveColorById carries a frozen id->name table for the 28
            // classic framework colour ids (see
            // ResourceIndex.FW_CLASSIC_COLOR_NAMES), so ask it.
            //
            // FIDELITY COST, stated: a few of those ids (primary_text_dark and
            // the other text colours) are res/color SELECTORS on a device, not
            // flat colours, so reporting a colour int here means androidx skips
            // XML inflation for them. That loses nothing we had: we cannot
            // inflate a FRAMEWORK selector anyway -- macapkLoadCslFile only
            // searches the APP's decoded res/ tree -- and our own
            // getColorStateList already flattens them to a single colour. The
            // alternative is the status quo, where androidx calls getXml() and
            // our NotFoundException escapes into the app. A flat colour beats a
            // thrown exception in every one of those cases.
            int fwc = macapkColorById(id, MACAPK_NO_COLOR);
            if (fwc != MACAPK_NO_COLOR) {
                outValue.type = android.util.TypedValue.TYPE_INT_COLOR_ARGB8;
                outValue.data = fwc;
                outValue.string = null;
                macapkApplyChaseTerminus(outValue, resolveRefs);
                return;
            }
        }
        if (id != 0) {
            String[] tn = macapkTypeNameOf(id);
            if (tn != null && "color".equals(tn[0]) && macapkResourcePath(id) == null) {
                int c = macapkColorById(id, MACAPK_NO_COLOR);
                if (c != MACAPK_NO_COLOR) {
                    outValue.type = android.util.TypedValue.TYPE_INT_COLOR_ARGB8;
                    outValue.data = c;
                    outValue.string = null;
                    macapkApplyChaseTerminus(outValue, resolveRefs);
                    return;
                }
            }
            // v1.0.913: the other VALUE types used to fall through to the
            // string/integer guesses below and mostly came out TYPE_NULL
            // with data=0 -- measured by the fwprobe RESVAL family: every
            // <dimen>, <bool>, fraction and float row answered type=0 where
            // the emulator answers the aapt-compiled encoding. Serve each
            // from its raw table through the same literal compiler the rest
            // of the resolution stack now uses.
            if (tn != null) {
                switch (tn[0]) {
                    case "dimen": {
                        // The dimen table legally holds bare floats too
                        // (<item type="dimen" format="float">), so the parsed
                        // type (TYPE_DIMENSION or TYPE_FLOAT) is authoritative.
                        String d = macapkRawTableValue("getDimenRaw", id);
                        if (d != null && android.util.TypedValue
                                .macapkParseNumericLiteral(d, outValue)) {
                            outValue.string = null;
                            macapkApplyChaseTerminus(outValue, resolveRefs);
                            return;
                        }
                        break;
                    }
                    case "fraction": {
                        String d = macapkRawTableValue("getFractionRaw", id);
                        if (d != null && android.util.TypedValue
                                .macapkParseNumericLiteral(d, outValue)) {
                            outValue.string = null;
                            macapkApplyChaseTerminus(outValue, resolveRefs);
                            return;
                        }
                        break;
                    }
                    case "bool":
                        // aapt: true compiles to 0xffffffff, not 1.
                        outValue.type = android.util.TypedValue.TYPE_INT_BOOLEAN;
                        outValue.data = getBoolean(id) ? 0xffffffff : 0;
                        outValue.string = null;
                        macapkApplyChaseTerminus(outValue, resolveRefs);
                        return;
                    case "id":
                        // aapt compiles `<item type="id"/>` as boolean false.
                        outValue.type = android.util.TypedValue.TYPE_INT_BOOLEAN;
                        outValue.data = 0;
                        outValue.string = null;
                        return;
                    case "integer":
                        // Unconditional (the old `iv != 0` gate below reported
                        // a legitimate 0 as TYPE_NULL). Hex-ness of the source
                        // is not recoverable from the parsed table; DEC is the
                        // common case and the honest approximation.
                        outValue.type = android.util.TypedValue.TYPE_INT_DEC;
                        outValue.data = getInteger(id);
                        outValue.string = null;
                        return;
                    default:
                        break;
                }
            }
        }
        // Try string first.
        String s = getString(id);
        if (s != null && !s.isEmpty()) {
            outValue.type = android.util.TypedValue.TYPE_STRING;
            outValue.string = s;
            outValue.data = 0;
            macapkApplyChaseTerminus(outValue, resolveRefs);
            return;
        }
        // Fall back to integer.
        int iv = getInteger(id);
        if (iv != 0) {
            outValue.type = android.util.TypedValue.TYPE_INT_DEC;
            outValue.data = iv;
            return;
        }
        // File-backed resource (drawable / mipmap / raw / xml / anim ...).
        // AOSP fills outValue.string with the COMPILED PATH of the file
        // ("res/drawable/ic_foo.xml"); androidx.compose.ui.res.painter
        // Resource reads it to branch vector (".xml") vs bitmap, and uses
        // it as the remember() cache key. A null string makes painter
        // Resource take the bitmap branch unconditionally and throw
        // ResourceResolutionException on a <vector> XML, which aborts the
        // Compose composition + the measure pass -> the whole content
        // subtree collapses to 0x0 (Reddit's welcome/login screen was
        // blank for exactly this reason). Generic: any Compose APK that
        // does painterResource(R.drawable.x) needs the path.
        String path = macapkResourcePath(id);
        if (path != null) {
            outValue.type = android.util.TypedValue.TYPE_STRING;
            // v1.0.964: report the APK-RELATIVE compiled path ("res/font/x.otf"),
            // not our host absolute path. AOSP's outValue.string for a file-backed
            // resource is the path as stored in the resource table, which always
            // begins "res/", and androidx GATES ON THAT PREFIX:
            //
            //   final String file = value.string.toString();
            //   if (!file.startsWith("res/")) {
            //       // Early exit if the specified string is unlikely to be a
            //       // resource path.
            //       ... return null;
            //   }
            //
            // (androidx-main, core/core/src/main/java/androidx/core/content/res/
            //  ResourcesCompat.java, loadFont). Returning "/Users/.../_apkt/res/
            // font/duolingosans_bold.otf" fails that test, ResourcesCompat.getFont
            // returns null, and its caller raises
            //   Resources$NotFoundException: Font resource ID #0x7f090005
            //   could not be retrieved.
            // from inside the widget CONSTRUCTOR -- so LayoutInflater substituted a
            // FrameLayout placeholder and the app's own cast blew up far away
            // (`FrameLayout cannot be cast to JuicyButton`). Measured on Duolingo
            // 6.82.3: EVERY @font/ id failed, so every branded TextView/Button in
            // the app became a placeholder and the screen rendered 99.22% white.
            // Generic: any app whose widgets call ResourcesCompat.getFont (i.e. any
            // AppCompat app shipping res/font/) hit this, not one app's quirk.
            //
            // macapkResourcePath stays ABSOLUTE for its other callers, which use it
            // to actually OPEN the file (macapkLoadFont, macapkFontRefPath,
            // macapkNinePatchState). Only the value handed to the app is rewritten.
            // Theme.resolveAttribute's file-backed serve already did exactly this;
            // getValue was the one path that had not been converted, and the two
            // are required to agree.
            outValue.string = macapkArscRelativePath(path);
            outValue.data = 0;
            // AOSP also sets assetCookie / density; Compose only reads
            // .string, so the path alone is sufficient.
            return;
        }
        // Diagnostic (MACAPK_TRACE_GETVALUE): log the id + resolved
        // type/name whenever we can't resolve a path, so the exact
        // painterResource(id) drawable an app fails to load is visible
        // (this is how Reddit's split-only welcome_background_2 was found).
        if (System.getenv("MACAPK_TRACE_GETVALUE") != null && id != 0) {
            try {
                Object ri = macapkResourceIndex;
                String tn = "?";
                if (ri != null) {
                    java.lang.reflect.Method m = ri.getClass().getMethod("macapkTypeNameOf", int.class);
                    Object v = m.invoke(ri, id);
                    if (v instanceof String[] arr && arr.length >= 2) tn = arr[0] + "/" + arr[1];
                }
                Class<?> log = Class.forName("com.macapk.loader.NativeLog");
                log.getMethod("i", String.class, String.class).invoke(null,
                    "Mac.apk.getValue", "UNRESOLVED id=0x" + Integer.toHexString(id) + " -> " + tn);
            } catch (Throwable ignored) {}
        }
        outValue.type = android.util.TypedValue.TYPE_NULL;
        outValue.data = 0;
    }

    /** The typed source row for an id (ResourceIndex.macapkRawValueOf,
     *  arsc-gate only): {dataType, data, text}. Null when absent. */
    private Object[] macapkRawTypedSourceOf(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("macapkRawValueOf", int.class);
            Object v = m.invoke(macapkResourceIndex, id);
            if (v instanceof Object[] a && a.length >= 3) return a;
        } catch (Throwable ignored) {}
        return null;
    }

    /** getValue's terminus report (v1.0.917): overwrite the seeded queried
     *  id with the deepest reference the serve just followed, the AOSP
     *  NativeGetResourceValue ref threading (seed resid, ResolveReference
     *  overwrites per followed reference). resolveRefs=false keeps the
     *  seed, matching the device skipping ResolveReference entirely. */
    private static void macapkApplyChaseTerminus(android.util.TypedValue outValue,
            boolean resolveRefs) {
        if (!resolveRefs) return;
        int last = macapkReadValueOrigin();
        if (last != 0) outValue.resourceId = last;
    }

    /** The platform table's simple value for a framework id (gate ON), via
     *  ResourceIndex.macapkFwValueOf: {dataType, data, text, leafId} with
     *  reference chains chased and published. Null when the gate is off,
     *  the view is absent, or the entry is complex. */
    /** Which platform-table simple types getValue() will report for a framework id.
     *  See the long comment at the framework branch in getValue(). Colours are inside
     *  TYPE_FIRST_INT..TYPE_LAST_INT, so the narrowed (gate-off) form is a strict subset. */
    private static Boolean macapkFwWideTypes;
    private static boolean macapkFwTypeAccepted(int t) {
        Boolean wide = macapkFwWideTypes;
        if (wide == null) {
            wide = !"0".equals(System.getenv("MACAPK_FW_SIMPLE_VALUES"));
            macapkFwWideTypes = wide;
        }
        if (t >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                && t <= android.util.TypedValue.TYPE_LAST_COLOR_INT) return true;
        if (!wide) return false;
        return t == android.util.TypedValue.TYPE_STRING
            || t == android.util.TypedValue.TYPE_FLOAT
            || t == android.util.TypedValue.TYPE_DIMENSION
            || t == android.util.TypedValue.TYPE_FRACTION
            || (t >= android.util.TypedValue.TYPE_FIRST_INT
                && t <= android.util.TypedValue.TYPE_LAST_INT);
    }

    private Object[] macapkFwSimpleValue(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("macapkFwValueOf", int.class);
            Object v = m.invoke(macapkResourceIndex, id);
            if (v instanceof Object[] a && a.length >= 4) return a;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Resolve a resource id to its backing file path via the
     *  ResourceIndex (reflective to keep this framework class decoupled
     *  from com.macapk.loader at link time). Returns null when the id
     *  has no file (a value resource, or unknown). */
    private String macapkResourcePath(int id) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("pathFor", int.class);
            Object p = m.invoke(macapkResourceIndex, id);
            return p == null ? null : p.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public void getValue(String name, android.util.TypedValue outValue, boolean resolveRefs) {
        int id = getIdentifier(name, null, null);
        getValue(id, outValue, resolveRefs);
    }
    public void getValueForDensity(int id, int density,
                                   android.util.TypedValue outValue,
                                   boolean resolveRefs) {
        getValue(id, outValue, resolveRefs);
    }

    public int[] getIntArray(int id) {
        // Route to the apktool-decoded values/arrays.xml index. Empty
        // int[] when the id isn't an integer-array (e.g. it's a string-
        // array or wasn't found at all).
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass()
                    .getMethod("integerArrayForId", int.class);
                Object v = m.invoke(macapkResourceIndex, id);
                if (v instanceof int[]) return (int[]) v;
            } catch (Throwable ignored) {}
        }
        return new int[0];
    }
    /** AOSP `Resources.getStringArray(int)`. Returns the items of a
     *  `<string-array name="...">` from `res/values/arrays.xml`. Load-
     *  bearing for F-Droid's `R.array.default_repos` which the
     *  database fixture callback parses to seed the CoreRepository
     *  table at first-launch DB create (6-tuple per repo:
     *  name / mirrors / description / version / certificate / is-archive).
     *  Without this, no repos exist at startup and
     *  RepoUpdateManager.updateRepos has nothing to fetch. */
    public String[] getStringArray(int id) {
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass()
                    .getMethod("stringArrayForId", int.class);
                Object v = m.invoke(macapkResourceIndex, id);
                if (v instanceof String[] arr) {
                    // Resolve reference items. On a device the element cursor
                    // resolves each entry before the string coercion, so a
                    // `<item>@string/x</item>` answers x's text, never the
                    // literal "@string/x" (AssetManager.getResourceStringArray).
                    for (int i = 0; i < arr.length; i++) {
                        String it = arr[i];
                        if (it == null || it.isEmpty() || it.charAt(0) != '@') continue;
                        if ("@null".equals(it)) { arr[i] = null; continue; }
                        int rid = macapkResRefToId(it);
                        if (rid == 0) continue;
                        try {
                            String s = getString(rid);
                            if (s != null && !s.isEmpty()) arr[i] = s;
                        } catch (Throwable ignored2) {}
                    }
                    return arr;
                }
            } catch (Throwable ignored) {}
        }
        return new String[0];
    }
    /** AOSP `Resources.getTextArray(int)`. Same data as getStringArray
     *  but typed as CharSequence[]. */
    public CharSequence[] getTextArray(int id) {
        return getStringArray(id);
    }
    // ---- DrawableCache (efficiency pass) ----
    //
    // getDrawable(id) used to re-open the backing file and re-run
    // BitmapFactory.decodeStream on EVERY call -- no cache at all, unlike
    // AOSP's ResourcesImpl.mDrawableCache/mColorDrawableCache (fetched
    // raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/
    // core/java/android/content/res/ResourcesImpl.java: both are
    // DrawableCache extends ConfigurationBoundResourceCache<ConstantState>,
    // keyed by (assetCookie<<32 | resource data), storing a
    // Drawable.ConstantState -- never a Drawable instance -- and every
    // retrieval calls ConstantState.newDrawable() to hand back a FRESH
    // Drawable that shares the constant (immutable) state). Repeat lookups
    // of the same id (RecyclerView re-binding an icon across dozens of
    // rows, a toolbar redrawn every frame) paid a fresh file-open + PNG/
    // JPEG/WebP decode each time (see
    // docs/efficiency-assets-datadir_2026-07-24.md Finding 3).
    //
    // We don't have a ConstantState hierarchy wired up here (the base
    // Drawable.getConstantState() returns null, and mutate() is a no-op --
    // see Drawable.java), so MacapkDrawableConstantState plays that role
    // directly: it holds ONLY the decoded, safely-shareable pixel data
    // (the AOSP "constant" part), never per-instance state (bounds/alpha/
    // tint/callback -- the AOSP "instance" part). getDrawable still
    // constructs a brand-new BitmapDrawable/NinePatchDrawable wrapper
    // object on EVERY call, cache hit or miss, exactly as the uncached
    // code did -- so two ImageViews showing the same resource id still
    // get independently-mutable Drawable objects; only the underlying
    // Bitmap (and, for a 9-patch, its cropped content bitmap + chunk
    // bytes) is shared. That Bitmap is the one BitmapFactory.decodeStream
    // produced with its default Options (inMutable=false), so ordinary
    // drawing/tinting/bounds calls never write through to it.
    //
    // Config-bound like AOSP's cache: the key folds in the night-mode bit
    // so a cached entry can never cross a light/dark appearance boundary.
    //
    // ⚠ CORRECTED v1.0.1047. This comment used to say the night-mode key was
    // "belt-and-suspenders today" because "macapkSetResourceIndex is called at
    // most once per real launch (Boot.java:319/1366; a fresh process, never a
    // live in-place flip, is how Mac.apk picks up a new AppleInterfaceStyle)".
    // Two things were wrong with that: the cited line numbers named no such
    // call site (there was ONE, in Boot.run), and the premise is now false --
    // Boot.macapkOnSystemAppearanceChanged swaps in a freshly-built
    // ResourceIndex on a live appearance flip, so the "fresh process" claim
    // would have sent a future session looking for a relaunch that never
    // happens. The key is now load-bearing rather than defensive.
    // Belt AND suspenders even so: updateConfiguration below clears this cache
    // outright on any config change, matching AOSP's
    // mDrawableCache.onConfigurationChange(configChanges).
    //
    // Weak-valued (not a hard LRU-with-cap): mirrors AOSP's own
    // WeakReference-backed ResourceCache base rather than inventing a new
    // eviction policy, and self-bounds under memory pressure instead of
    // pinning every decoded drawable for the process lifetime.
    private static final class MacapkDrawableConstantState {
        final android.graphics.Bitmap bitmap;
        final boolean isNinePatch;
        final android.graphics.Bitmap ninePatchContent;
        final byte[] ninePatchChunk;
        final android.graphics.Rect ninePatchPadding;
        final String ninePatchPath;
        MacapkDrawableConstantState(android.graphics.Bitmap bitmap) {
            this.bitmap = bitmap;
            this.isNinePatch = false;
            this.ninePatchContent = null; this.ninePatchChunk = null;
            this.ninePatchPadding = null; this.ninePatchPath = null;
        }
        MacapkDrawableConstantState(android.graphics.Bitmap bitmap,
                android.graphics.Bitmap content, byte[] chunk,
                android.graphics.Rect padding, String path) {
            this.bitmap = bitmap;
            this.isNinePatch = true;
            this.ninePatchContent = content; this.ninePatchChunk = chunk;
            this.ninePatchPadding = padding; this.ninePatchPath = path;
        }
        android.graphics.drawable.Drawable newDrawable(Resources res) {
            if (isNinePatch) {
                return new android.graphics.drawable.NinePatchDrawable(res,
                    ninePatchContent, ninePatchChunk, ninePatchPadding, ninePatchPath);
            }
            return new android.graphics.drawable.BitmapDrawable(res, bitmap);
        }
    }
    private final java.util.Map<Long, java.lang.ref.WeakReference<MacapkDrawableConstantState>>
        macapkDrawableCache = new java.util.concurrent.ConcurrentHashMap<>();
    private long macapkDrawableCacheKey(int id) {
        int nightBits = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ((long) nightBits << 32) | (id & 0xFFFFFFFFL);
    }
    // MACAPK_DRAWABLE_CACHE_TRACE=1: log every DrawableCache hit/miss.
    // Mirrors sXmlTrace's MACAPK_MENU_TRACE pattern above -- default OFF,
    // opt-in for verifying/debugging the cache, never on the hot path
    // otherwise.
    private static final boolean sDrawableCacheTrace =
        System.getenv("MACAPK_DRAWABLE_CACHE_TRACE") != null;

    // MACAPK_TRACE_ZERO_DRAWABLE=1: log any getDrawable(id) that returns a
    // drawable whose getIntrinsicWidth()/Height() is <= 0. That is the exact
    // shape that makes a caller's Bitmap.createBitmap(intrinsicW, intrinsicH)
    // throw "width and height must be > 0" (Instagram's feed-icon crash:
    // X.5nS.A00 sizes a tint bitmap to a getDrawable(resId) drawable's
    // intrinsic size). Names the resource + drawable class so the failing
    // resource is identified without disassembling the app. Default OFF,
    // sweep-safe, diagnostic only.
    private static final boolean sZeroDrawableTrace =
        "1".equals(System.getenv("MACAPK_TRACE_ZERO_DRAWABLE"));

    /** v1.0.95: return a non-null VectorDrawable stub for any
     *  drawable resource ID. This unblocks androidx's
     *  ResourceManagerInternal.checkVectorDrawableSetup (which
     *  throws IllegalStateException("...configure VectorDrawableCompat...")
     *  if `context.getDrawable(R.drawable.abc_vector_test) == null`
     *  or is the wrong class). VectorDrawable's class name matches
     *  the AOSP-checked literal so the type test passes.
     *
     *  Drawables don't render anything visible (no path / bitmap /
     *  shape data binding); icons stay blank. But the menu / button
     *  / Material widget instantiation chains complete, so the rest
     *  of the UI gets to lay out and populate. */
    public android.graphics.drawable.Drawable getDrawable(int id) {
        return getDrawable(id, null);
    }
    /** docs/attr-theme-threading_2026-07-31.md: this is now the PRIMARY
     *  implementation (getDrawable(int) above just delegates with theme=null),
     *  matching AOSP's own Context.getDrawable(id) -> getResources()
     *  .getDrawable(id, getTheme()) shape. Previously `theme` was accepted and
     *  silently discarded (the whole body was `return getDrawable(id);`), which
     *  is the root of part (d): a `<solid android:color="?colorM3Primary"/>`
     *  inside an XML drawable had no way to resolve against anything but the
     *  static application theme, no matter which Theme the caller held. Only
     *  the XML-drawable path below actually needs threading (raster/9-patch
     *  decoding has no `?attr/` content to resolve), so the cache + bitmap
     *  branches are unchanged and theme-independent. */
    public android.graphics.drawable.Drawable getDrawable(int id, Theme theme) {
        android.graphics.drawable.Drawable macapkD = macapkGetDrawableImpl(id, theme);
        if (sZeroDrawableTrace && macapkD != null) {
            try {
                int iw = macapkD.getIntrinsicWidth();
                int ih = macapkD.getIntrinsicHeight();
                if (iw <= 0 || ih <= 0) {
                    String nm; try { nm = getResourceName(id); }
                    catch (Throwable t) { nm = "?"; }
                    String pth; try { pth = macapkResourcePath(id); }
                    catch (Throwable t) { pth = "?"; }
                    com.macapk.loader.NativeLog.w("Mac.apk.Resources",
                        "ZERO-DRAWABLE getDrawable(0x" + Integer.toHexString(id)
                        + " " + nm + ") -> " + macapkD.getClass().getName()
                        + " intrinsic=" + iw + "x" + ih
                        + " bounds=" + macapkD.getBounds() + " path=" + pth);
                }
            } catch (Throwable ignored) {}
        }
        return macapkD;
    }

    private android.graphics.drawable.Drawable macapkGetDrawableImpl(int id, Theme theme) {
        // v1.0.99: peek the first bytes to determine drawable type.
        // PNG (89 50 4E 47), JPG (FF D8), GIF (47 49 46) -> BitmapDrawable.
        // XML (text starting with '<' or whitespace+'<') -> VectorDrawable
        // attempt parse, fall through to empty. Default empty for any
        // unrecognized format. Frozen Bubble's splash.jpg works again.
        if (id == 0) {
            // MARK it as the unresolved stand-in. Callers that decide whether to
            // paint their own substitute chrome need to tell this apart from a
            // real drawable, and the class alone cannot: an app's own vector is
            // the same type. See VectorDrawable.macapkUnresolvedStub.
            android.graphics.drawable.VectorDrawable stub =
                new android.graphics.drawable.VectorDrawable();
            stub.macapkUnresolvedStub = true;
            return stub;
        }

        // A COLOUR RESOURCE IS A DRAWABLE (v1.0.931). AOSP types the value and
        // branches BEFORE any file loading (ResourcesImpl.java:625-634, 670-671,
        // android11-release, fetched):
        //     if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
        //             && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
        //         isColorDrawable = true; ...
        //     } else if (isColorDrawable) {
        //         dr = new ColorDrawable(value.data);
        //
        // We had NO colour branch at all. A colour-valued id has no file, so it
        // fell through the magic-byte peek below and came back as an EMPTY
        // VectorDrawable that paints nothing -- and, because `id != 0`, it was
        // not even marked `macapkUnresolvedStub`, so nothing downstream could
        // tell it apart from a real drawable.
        //
        // THREE consequences chained off that, all measured on F-Droid's bottom
        // nav bar (`android:background = ?colorSurfaceContainer`):
        //   1. the stub paints nothing, so the window background showed through
        //      (#f9f9ff where the emulator has #ecedf6, over the whole
        //      808x160 bar = 129,280 px);
        //   2. Material's `getBackground() instanceof ColorDrawable` test fails,
        //      so its MaterialShapeDrawable never gets a fill colour;
        //   3. View.java's `backgroundDrawable == null` gate sees a NON-NULL
        //      stub and therefore SUPPRESSES our own macapkPaintMaterialChrome
        //      fallback, so the substitute painter never ran either.
        //
        // It also settles the standing question in View.java's v898 comment
        // ("what is missing is knowing where anki's VectorDrawable comes from,
        // since it is neither our id==0 stub nor something that paints"): it is
        // a COLOUR resource id reaching the drawable path.
        //
        // Fleet-wide: every `android:background="@color/x"` or `"?attr/someColor"`
        // in any style went through here.
        //
        // The branch is now decided by the value's TYPE, as AOSP decides it
        // (v1.0.1649, cluster D1); see macapkColorDrawableValue for why the old
        // name-keyed test turned real drawables into flat colours.
        // Wave 5 (2026-09-26), docs/w5-inflater-fixes_2026-09-26.md F6: a
        // COLOUR-typed id backed by an .xml FILE is a ColorStateListDrawable,
        // not its default row flattened to a ColorDrawable. AOSP
        // (android14-release ResourcesImpl.java):
        //     :883-889  if (file.endsWith(".xml")) {
        //                   final String typeName = getResourceTypeName(id);
        //                   if (typeName != null && typeName.equals("color")) {
        //                       dr = loadColorOrXmlDrawable(wrapper, value, id, density, file);
        //     :922-926  ColorStateList csl = loadColorStateList(wrapper, value, id, null);
        //               return new ColorStateListDrawable(csl);
        // A values <color> (TYPE_INT_COLOR_*) has no file and keeps the
        // ColorDrawable below. App ids only: macapkTypeNameOf has no framework
        // rows, so a framework colour file keeps its current path.
        // MACAPK_COLOR_FILE_DRAWABLE=0 restores the flattening (the same env var
        // gates LayoutInflater.applyBackground and ColorStateListDrawable.isStateful).
        if (MACAPK_COLOR_FILE_DRAWABLE) {
            try {
                String[] tn = macapkTypeNameOf(id);
                String path = (tn != null && "color".equals(tn[0])) ? macapkResourcePath(id) : null;
                if (path != null && path.endsWith(".xml")) {
                    ColorStateList csl = getColorStateList(id, theme);
                    if (csl != null) return new android.graphics.drawable.ColorStateListDrawable(csl);
                }
            } catch (Throwable ignored) {}
        }
        try {
            Integer col = macapkColorDrawableValue(id);
            if (col != null) return new android.graphics.drawable.ColorDrawable(col);
        } catch (Throwable ignored) {}

        // DrawableCache lookup (raster path only -- see the block comment
        // above). A hit skips the file open + header peek +
        // BitmapFactory.decodeStream entirely and just builds a fresh
        // Drawable facade over the already-decoded, shared pixel data.
        long cacheKey = macapkDrawableCacheKey(id);
        java.lang.ref.WeakReference<MacapkDrawableConstantState> cachedRef =
            macapkDrawableCache.get(cacheKey);
        MacapkDrawableConstantState cached = cachedRef != null ? cachedRef.get() : null;
        if (cached != null) {
            if (sDrawableCacheTrace) com.macapk.loader.NativeLog.d("Mac.apk.Resources",
                "getDrawable(0x" + Integer.toHexString(id) + ") DrawableCache HIT");
            return cached.newDrawable(this);
        }

        byte[] head = null;
        try {
            java.io.InputStream in = openRawResource(id);
            if (in != null) {
                head = new byte[8];
                int read = in.read(head);
                in.close();
                if (read < 0) head = null;
            }
        } catch (Throwable ignored) {}
        if (head != null) {
            boolean isPng = head.length >= 4 && (head[0] & 0xFF) == 0x89
                && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            boolean isJpg = head.length >= 2 && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xFF) == 0xD8;
            boolean isGif = head.length >= 3 && head[0] == 'G'
                && head[1] == 'I' && head[2] == 'F';
            boolean isWebp = head.length >= 4 && head[0] == 'R'
                && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
            if (isPng || isJpg || isGif || isWebp) {
                try {
                    java.io.InputStream in = openRawResource(id);
                    if (in != null) {
                        // Read the entry once: the decoder wants a stream and the
                        // compiled 9-patch check (below) wants the PNG's chunks.
                        byte[] bytes;
                        try { bytes = in.readAllBytes(); }
                        finally { try { in.close(); } catch (Throwable ignored) {} }
                        android.graphics.Bitmap bm = android.graphics.BitmapFactory
                            .decodeStream(new java.io.ByteArrayInputStream(bytes));
                        if (bm != null) {
                            // A 9-patch must become a NinePatchDrawable, not a
                            // plain BitmapDrawable: only the former carries the
                            // stretch divs and the content padding. A COMPILED
                            // .9.png (the installed apk's entry, v1.0.1590) has no
                            // guide border and carries an npTc chunk instead; the
                            // border parse remains for a source-form file.
                            MacapkDrawableConstantState state =
                                isPng ? macapkNinePatchFromChunk(id, bm, bytes) : null;
                            if (state == null) state = macapkNinePatchState(id, bm);
                            if (state == null) state = new MacapkDrawableConstantState(bm);
                            macapkDrawableCache.put(cacheKey,
                                new java.lang.ref.WeakReference<>(state));
                            if (sDrawableCacheTrace) com.macapk.loader.NativeLog.d("Mac.apk.Resources",
                                "getDrawable(0x" + Integer.toHexString(id)
                                + ") DrawableCache MISS -> decoded"
                                + (state.isNinePatch ? " (9-patch)" : "")
                                + ", cached");
                            return state.newDrawable(this);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        // v1.0.405: dispatch XML drawables by ROOT TAG. Previously ANY
        // non-bitmap drawable became an empty VectorDrawable, so <layer-list>
        // / <shape> / <selector> rendered nothing -- F-Droid's toolbar icons
        // (ic_*_with_background = <layer-list> of a <shape> circle + a
        // <vector> glyph) were invisible. macapkInflateXmlDrawable returns
        // null for <vector>/unknown, keeping the VectorDrawable path.
        // Not cached: the XML-inflate tree is smaller/cheaper (see the
        // DrawableCache comment above) and, unlike a decoded Bitmap, its
        // nested Drawable/ColorDrawable children have no shared-immutable-
        // state story worked out here -- leave this path exactly as it was.
        android.graphics.drawable.Drawable xml = macapkInflateXmlDrawable(id, theme);
        if (xml != null) return xml;
        // lane layout, 2026-08-01: thread the inflating Theme into the
        // <vector> branch too, so `?attr` PATH FILLS resolve against the
        // runtime (setTheme'd) theme -- see VectorDrawable's 3-arg ctor.
        return new android.graphics.drawable.VectorDrawable(this, id, theme);
    }

    /** Wave 5 F6 gate; see the colour-file branch in macapkGetDrawableImpl. */
    private static final boolean MACAPK_COLOR_FILE_DRAWABLE =
        !"0".equals(System.getenv("MACAPK_COLOR_FILE_DRAWABLE"));

    /** Cluster D1 (v1.0.1649, docs/widget-fidelity-cluster_2026-09-25.md).
     *  Default ON; `MACAPK_DRAWABLE_COLOR_BY_TYPE=0` restores the name-keyed
     *  colour branch, so the A/B comes from one binary. */
    private static final boolean MACAPK_DRAWABLE_COLOR_BY_TYPE =
        !"0".equals(System.getenv("MACAPK_DRAWABLE_COLOR_BY_TYPE"));

    /** The colour a DRAWABLE lookup of `id` must become, or null when `id` is
     *  not colour-valued and has to load as a file.
     *
     *  AOSP decides this from the resolved value's TYPE and from nothing else
     *  (ResourcesImpl.loadDrawable, android14-release :639-650 and :678-681,
     *  fetched):
     *      if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
     *              && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
     *          isColorDrawable = true; ...
     *      } else if (isColorDrawable) {
     *          dr = new ColorDrawable(value.data);
     *      } else {
     *          dr = loadDrawableForCookie(wrapper, value, id, density);
     *
     *  Ours asked ResourceIndex.resolveColorById, whose last line is
     *  `return getColorByName(tn[1])` with the type deliberately ignored, so a
     *  drawable that merely SHARES ITS NAME with a colour became that colour.
     *  MEASURED victim: Organic Maps ships `@drawable/button_accent`
     *  (0x7f08009a, a <ripple> over a blue selector) AND `@color/button_accent`
     *  (0x7f06004b, a selector whose default row is a 30-alpha grey), and
     *  getDrawable(0x7f08009a) came back ColorDrawable alpha=30: the flat,
     *  square, disabled-grey (#e1e1e1 on white) Download button. Measured over
     *  the 51 .apk files in the corpus: 62 drawable ids in 17 apps shared a
     *  colour's name, and every one was served as that colour -- unit
     *  converter's drawer rows (navigation_item_background*, a checked-only
     *  selector) all painted #444444, Play services'
     *  common_google_signin_btn_text_* in 7 apps were flat white/black,
     *  pianoli's launcher-icon background was its colour namesake.
     *
     *  The rule now, keyed on the id's resource type:
     *   - `color`: unchanged (resolveColorById), including the res/color
     *     selector flattening it has always done.
     *   - any other type: only the id's OWN value can make it a colour -- a
     *     colour-int row (`<drawable name="notification_template_icon_bg">
     *     #3333b5e5</drawable>`, which androidx core puts in most apps and which
     *     used to fall through to an empty VectorDrawable), or a reference
     *     chased to one. A file row never is.
     *   - type unknown: unchanged, so the framework-colour and system-palette id
     *     paths resolveColorById owns keep answering exactly as before.
     *  Framework ids read the platform table's own typed value
     *  (macapkFwSimpleValue), which chases references the way
     *  AssetManager2::ResolveReference does. */
    private Integer macapkColorDrawableValue(int id) {
        if (!MACAPK_DRAWABLE_COLOR_BY_TYPE) return macapkResolveColorById(id);
        String[] tn = macapkTypeNameOf(id);
        if (tn == null || "color".equals(tn[0])) return macapkResolveColorById(id);
        return macapkTypedColorValue(id, 0);
    }

    /** The colour held by `id`'s own table row, following references; null
     *  for a file row, a non-colour value or an unknown id. See
     *  macapkColorDrawableValue. */
    private Integer macapkTypedColorValue(int id, int depth) {
        if (id == 0 || depth > 8) return null;
        final int first = android.util.TypedValue.TYPE_FIRST_COLOR_INT;
        final int last  = android.util.TypedValue.TYPE_LAST_COLOR_INT;
        if ((id >>> 24) == 0x01) {
            Object[] fw = macapkFwSimpleValue(id);
            if (fw != null && fw[0] instanceof Integer t && fw[1] instanceof Integer data
                    && t >= first && t <= last) {
                return data;
            }
            return null;
        }
        Object[] src = macapkRawTypedSourceOf(id);
        if (src == null || !(src[0] instanceof Integer t) || !(src[1] instanceof Integer data)) {
            return null;
        }
        if (t >= first && t <= last) return data;
        // TYPE_REFERENCE (0x01) or TYPE_DYNAMIC_REFERENCE (0x07, ResourceTypes.h):
        // data is the target id. A `<drawable name="a">@color/b</drawable>` alias
        // answers b's colour through the same colour branch a direct @color/b
        // lookup takes; any other target is judged by ITS own row.
        if (t == android.util.TypedValue.TYPE_REFERENCE || t == 0x07) {
            String[] ttn = macapkTypeNameOf(data);
            if (ttn != null && "color".equals(ttn[0])) return macapkResolveColorById(data);
            return macapkTypedColorValue(data, depth + 1);
        }
        return null;
    }

    // ---- 9-patch decoding (SOURCE form, not the npTc chunk) ----
    //
    // WHY SOURCE FORM: aapt2 strips a .9.png's 1-pixel marker border at
    // compile time and stores the divs + padding in a private PNG ancillary
    // chunk named "npTc" (tools/aapt2/compile/Png.cpp serializes a
    // Res_png_9patch and then calls deviceToFile()). apktool -- which
    // produces the res/ tree Mac.apk actually reads -- REVERSES that: its
    // Res9patchStreamDecoder re-draws the marker border from the chunk and
    // emits a PNG carrying NO npTc chunk. Measured on this tree: 400/400
    // sampled `_apkt/res/**/*.9.png` files hold only IHDR/IDAT/IEND, and
    // abc_list_divider_mtrl_alpha.9.png is a 3x3 whose row 0 reads
    // [transparent, opaque black, transparent]. So a chunk read would find
    // nothing; the parse that matters here is aapt's SOURCE 9-patch parse.
    // Until this landed, getDrawable handed the whole bordered image to
    // BitmapDrawable, which drew the black marker frame AND sized the
    // content 2px too large, on every 9-patch background in every app.
    //
    // The parse mirrors aapt2 NinePatch::Create
    // (tools/aapt2/compile/NinePatch.cpp:458, android14-release):
    //  - The neutral border color is transparent when pixel (0,0) has alpha
    //    0, otherwise opaque white. The marker (primary) color is opaque
    //    black; opaque red marks optical bounds, which AOSP keeps in a
    //    separate chunk and we likewise ignore.
    //  - Top row -> horizontal stretch ranges (xDivs); left column ->
    //    vertical ranges (yDivs). FillRanges records coordinates as idx-1,
    //    i.e. relative to the STRIPPED content image, and a range still open
    //    at the end closes at length-2.
    //  - Bottom row -> horizontal padding; right column -> vertical padding.
    //    PopulateBounds: start = range.start, end = length - range.end, where
    //    length is the stripped dimension. With no padding markers AOSP
    //    derives padding from the first/last stretch region.

    /** Build the cacheable NinePatchDrawable constant-state for a
     *  source-form .9.png, or null when `id` is not one (the caller then
     *  keeps the plain BitmapDrawable). Was macapkNinePatchDrawable
     *  (returned a built NinePatchDrawable directly); now returns the
     *  MacapkDrawableConstantState so getDrawable's DrawableCache can
     *  store the derived content/chunk/padding once and rebuild a fresh
     *  NinePatchDrawable from them on every cache hit -- identical
     *  derivation logic, just handed back one indirection later. */
    private MacapkDrawableConstantState macapkNinePatchState(
            int id, android.graphics.Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        // aapt: "image must be at least 3x3 (1x1 image with 1 pixel border)".
        if (w < 3 || h < 3) return null;
        // Filter on the compiled path FIRST. The border scan below reads
        // individual pixels, which forces a full ARGB extraction out of the
        // AWT-backed bitmap; an ordinary icon with a transparent corner would
        // pay that for nothing. The path resolution costs the same directory
        // walk openRawResource already performed above.
        String path = macapkResourcePath(id);
        if (path == null || !path.endsWith(".9.png")) return null;
        // aapt: the border's neutral color is judged from the top-left corner
        // and must be either transparent or opaque white.
        int corner = src.getPixel(0, 0);
        if ((corner >>> 24) != 0 && corner != 0xFFFFFFFF) return null;

        int[] xDivs = macapk9Ranges(src, true,  0);       // top row
        int[] yDivs = macapk9Ranges(src, false, 0);       // left column
        int[] hPad  = macapk9Ranges(src, true,  h - 1);   // bottom row
        int[] vPad  = macapk9Ranges(src, false, w - 1);   // right column
        // A border with no markers at all is not a valid 9-patch; leave such
        // an image alone rather than cropping a legitimate outer pixel ring.
        if (xDivs.length + yDivs.length + hPad.length + vPad.length == 0) return null;

        final int contentW = w - 2, contentH = h - 2;
        int padLeft = 0, padRight = 0, padTop = 0, padBottom = 0;
        if (hPad.length >= 2) {
            padLeft  = hPad[0];
            padRight = contentW - hPad[1];
        } else if (xDivs.length >= 2) {
            padLeft  = xDivs[0];
            padRight = contentW - xDivs[xDivs.length - 1];
        }
        if (vPad.length >= 2) {
            padTop    = vPad[0];
            padBottom = contentH - vPad[1];
        } else if (yDivs.length >= 2) {
            padTop    = yDivs[0];
            padBottom = contentH - yDivs[yDivs.length - 1];
        }

        android.graphics.Bitmap content =
            android.graphics.Bitmap.createBitmap(src, 1, 1, contentW, contentH);
        if (content == null) return null;
        // NinePatchDrawable's padding Rect holds INSETS, not coordinates.
        android.graphics.Rect padding =
            new android.graphics.Rect(padLeft, padTop, padRight, padBottom);
        return new MacapkDrawableConstantState(src, content,
            macapk9Chunk(xDivs, yDivs, padLeft, padRight, padTop, padBottom),
            padding, path);
    }

    /** A NinePatch state from a COMPILED .9.png: aapt strips the 1-px guide
     *  border and writes the stretch/padding data as an `npTc` chunk holding
     *  a Res_png_9patch in FILE order (big-endian: ResourceTypes.cpp
     *  Res_png_9patch::deviceToFile swaps every int32; NinePatchPeeker.cpp
     *  :24-37 (libs/hwui/jni, android11-release) deserializes it and calls
     *  fileToDevice()). Header, 32 bytes (ResourceTypes.h:141-190):
     *    +0 int8 wasDeserialized  +1 u8 numXDivs  +2 u8 numYDivs  +3 u8 numColors
     *    +4 u32 xDivsOffset  +8 u32 yDivsOffset   (UNSET in the file: serialize()
     *                                              copies only bytes 0-3 and 12-27;
     *                                              deserialize() computes them)
     *    +12 i32 paddingLeft  +16 paddingRight  +20 paddingTop  +24 paddingBottom
     *    +28 u32 colorsOffset (unset likewise)
     *  then int32 xDivs[], yDivs[] (flattened start,end pairs in the stripped
     *  image's coordinates) and uint32 colors[] (an opaque-region draw hint,
     *  dropped here as macapk9Chunk documents). The decoded bitmap IS the
     *  content (no border to crop). Null when the PNG carries no npTc. */
    private MacapkDrawableConstantState macapkNinePatchFromChunk(
            int id, android.graphics.Bitmap bm, byte[] png) {
        byte[] np = macapkPngChunk(png, "npTc");
        if (np == null || np.length < 32) return null;
        try {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(np)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
            int nx = np[1] & 0xff, ny = np[2] & 0xff;
            // serialize() writes ONLY the counts (bytes 0-3) and the four
            // paddings (12-27); the xDivs/yDivs/colors OFFSET fields are
            // pointers that deserialize() fills in on the device, so in the
            // file they are unset. The arrays follow the 32-byte header in
            // order: xDivs, yDivs, colors.
            int xOff = 32, yOff = 32 + nx * 4;
            int padLeft = bb.getInt(12), padRight = bb.getInt(16);
            int padTop = bb.getInt(20), padBottom = bb.getInt(24);
            if (yOff + ny * 4 > np.length) return null;
            int[] xDivs = new int[nx];
            int[] yDivs = new int[ny];
            for (int i = 0; i < nx; i++) xDivs[i] = bb.getInt(xOff + i * 4);
            for (int i = 0; i < ny; i++) yDivs[i] = bb.getInt(yOff + i * 4);
            android.graphics.Rect padding =
                new android.graphics.Rect(padLeft, padTop, padRight, padBottom);
            return new MacapkDrawableConstantState(bm, bm,
                macapk9Chunk(xDivs, yDivs, padLeft, padRight, padTop, padBottom),
                padding, macapkResourcePath(id));
        } catch (Throwable t) {
            return null;
        }
    }

    /** The data of the first PNG chunk named `type`, or null. PNG = 8-byte
     *  signature then chunks of {u32 length, 4-char type, data, u32 crc}. */
    private static byte[] macapkPngChunk(byte[] png, String type) {
        if (png == null || png.length < 8) return null;
        int p = 8;
        while (p + 8 <= png.length) {
            int len = ((png[p] & 0xff) << 24) | ((png[p + 1] & 0xff) << 16)
                    | ((png[p + 2] & 0xff) << 8) | (png[p + 3] & 0xff);
            if (len < 0 || p + 12 + len > png.length) return null;
            String t = new String(png, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (t.equals(type)) return java.util.Arrays.copyOfRange(png, p + 8, p + 8 + len);
            if (t.equals("IEND")) return null;
            p += 12 + len;
        }
        return null;
    }

    /** aapt2 FillRanges over one border line: returns the flattened
     *  (start, end) pairs of opaque-black runs, in stripped-image
     *  coordinates. `horizontal` scans row `fixed`, otherwise column
     *  `fixed`. The two corner pixels are excluded, matching aapt's
     *  `for (idx = 1; idx < length - 1; idx++)`. */
    private static int[] macapk9Ranges(android.graphics.Bitmap bm,
                                       boolean horizontal, int fixed) {
        final int kPrimaryColor = 0xFF000000;
        int len = horizontal ? bm.getWidth() : bm.getHeight();
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        int start = -1;
        for (int i = 1; i < len - 1; i++) {
            int c = horizontal ? bm.getPixel(i, fixed) : bm.getPixel(fixed, i);
            boolean primary = (c == kPrimaryColor);
            if (primary && start < 0) {
                start = i - 1;
            } else if (!primary && start >= 0) {
                out.add(start);
                out.add(i - 1);
                start = -1;
            }
        }
        // A range still open at the last scanned pixel ends at length-2, the
        // initial `Range(idx - 1, length - 2)` aapt pushes when it opens one.
        if (start >= 0) { out.add(start); out.add(len - 2); }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Serialize a Res_png_9patch in the DEVICE representation that
     *  Bitmap.getNinePatchChunk() hands to NinePatch. Layout verified against
     *  androidfw/include/androidfw/ResourceTypes.h:141 plus
     *  ResourceTypes.cpp's serialize(), which writes a fixed 32-byte header:
     *    +0  int8  wasDeserialized
     *    +1  uint8 numXDivs      +2 uint8 numYDivs    +3 uint8 numColors
     *    +4  uint32 xDivsOffset  +8 uint32 yDivsOffset
     *    +12 int32 paddingLeft   +16 paddingRight
     *    +20 int32 paddingTop    +24 paddingBottom
     *    +28 uint32 colorsOffset
     *    +32 xDivs[], yDivs[], colors[]
     *  Divs are the flattened (start, end) pairs of each stretch region, so
     *  numXDivs is twice the region count. The file representation is
     *  byte-swapped to network order by deviceToFile(); this is the DEVICE
     *  (little-endian) side, which is what an in-memory chunk carries.
     *  numColors is 0: the per-region color table is an opaque-region draw
     *  optimization, and omitting it is valid. */
    private static byte[] macapk9Chunk(int[] xDivs, int[] yDivs,
                                       int padLeft, int padRight,
                                       int padTop, int padBottom) {
        int size = 32 + xDivs.length * 4 + yDivs.length * 4;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 1);                       // wasDeserialized
        bb.put((byte) xDivs.length);
        bb.put((byte) yDivs.length);
        bb.put((byte) 0);                       // numColors
        bb.putInt(32);                          // xDivsOffset
        bb.putInt(32 + xDivs.length * 4);       // yDivsOffset
        bb.putInt(padLeft);
        bb.putInt(padRight);
        bb.putInt(padTop);
        bb.putInt(padBottom);
        bb.putInt(32 + (xDivs.length + yDivs.length) * 4);   // colorsOffset
        for (int v : xDivs) bb.putInt(v);
        for (int v : yDivs) bb.putInt(v);
        return bb.array();
    }

    // ---- v1.0.405: XML drawable inflater (<layer-list>/<shape>/<selector>) ----
    // getDrawable() used to return an empty VectorDrawable for every XML
    // drawable; only <vector> actually rendered. These build the AOSP-shaped
    // drawable for the other common root tags so they paint. SAFE: a View's
    // background drawable is never DRAWN by our View (backgrounds go through
    // setBackgroundColor / macapkPaintMaterialChrome; setBackgroundResource is
    // a no-op), so this only affects drawables that are explicitly drawn
    // (ImageView source, compound drawable) -- which were invisible before, so
    // the change is strictly additive. Gated MACAPK_XML_DRAWABLE=0 to disable.
    private static final String MACAPK_ANDROID_NS =
        "http://schemas.android.com/apk/res/android";
    private static final ThreadLocal<Integer> sXmlDrawableDepth =
        ThreadLocal.withInitial(() -> 0);

    // ---- fwxml: COMPILED platform XML read as typed values -----------------
    //
    // A framework id is served straight out of framework-res.apk, so its XML
    // (a drawable, a color selector, anything getXml hands out) arrives
    // aapt-COMPILED, and BinaryXmlParser renders each typed Res_value in its
    // numeric spelling: TYPE_ATTRIBUTE as "?0x0101042a", TYPE_REFERENCE as
    // "@0x01050077", an enum as its int ("0" for rectangle), a flag set as hex
    // ("0x57" for a gravity). Every consumer in this file was written for the
    // TEXT spelling of an app resource ("?attr/colorControlActivated",
    // "@dimen/x", "oval", "center"), so those values were UNREAD: the tint of
    // @android:drawable/text_cursor_material never resolved, the insets of
    // btn_default_mtrl_shape were 0, a layer gravity of "0x57" parsed as
    // NO_GRAVITY and filled the layer.
    //
    // AOSP never has this problem because nothing on a device reads the
    // spelling: XmlBlock hands TypedArray the (type, data) pair and
    // obtainAttributes resolves it (android14-release XmlBlock.java
    // :260-280 getAttributeValue only formats a non-string value as a
    // fallback, via TypedValue.coerceToString :769-799, which renders an
    // attribute ref as "?" + data and a reference as "@" + data). So the
    // faithful fix is to make our consumers compute what TypedArray would
    // have: an attribute ref resolved against the theme, a reference
    // resolved by ID, an enum/flag taken as its int.
    //
    // Default ON; `MACAPK_FW_XML_NUMERIC=0` restores the pre-change path at
    // every site below (the raw parse, the unnamed attribute ref, the
    // app-table dimen lookup, the text-only enum and gravity parsers), for a
    // one-binary bisect. Gate: debug/fwxml_gate.sh.
    static final boolean MACAPK_FW_XML_NUMERIC =
        !"0".equals(System.getenv("MACAPK_FW_XML_NUMERIC"));

    /** BinaryXmlParser.MACAPK_TYPE_NS: the sibling annotation attributes it
     *  adds next to each typed value ("macapkType:color" = "attribute").
     *  They are OUR bookkeeping, not part of the document. */
    private static final String MACAPK_AXML_TYPE_NS = "http://macapk/axml-type";

    /** Parse a COMPILED XML entry (the framework path; app XML arrives as text)
     *  into the DOM every consumer here reads. Gate on: the attribute
     *  references are NAMED and the type annotations are dropped
     *  (macapkTypeCompiledElement). Reflective like every BinaryXmlParser
     *  call in this package: android.content.res keeps no link-time
     *  dependency on com.macapk.loader. */
    private static org.w3c.dom.Document macapkParseCompiledXml(byte[] bytes) throws Exception {
        Class<?> bxp = Class.forName("com.macapk.loader.BinaryXmlParser");
        org.w3c.dom.Document doc = (org.w3c.dom.Document) bxp
            .getMethod("parse", byte[].class).invoke(null, (Object) bytes);
        if (MACAPK_FW_XML_NUMERIC && doc != null && doc.getDocumentElement() != null) {
            macapkTypeCompiledElement(doc.getDocumentElement());
        }
        return doc;
    }

    /** Two rewrites, recursively, so a compiled DOM reads the way the device's
     *  XmlBlock.Parser does:
     *
     *  (1) "?0x%08x" (BinaryXmlParser.refText's spelling of TYPE_ATTRIBUTE when
     *      it has no names) becomes "?android:<name>" for a platform attr, the
     *      spelling BinaryXmlParser itself uses when it DOES have names and the
     *      one every `?attr` consumer in the tree already resolves
     *      (macapkResolveColorString, macapkResolveThemeAttrRaw, TypedArray,
     *      VectorDrawable). The id is the attr's identity; the name is how our
     *      name-keyed Theme expresses that identity. MEASURED victim: NewPipe's
     *      drawer rows. NavigationView.createDefaultColorStateList reads
     *      ?android:textColorPrimary -> @android:color/text_color_primary, a
     *      platform <selector> whose rows are `android:color="?0x01010030"`
     *      (?attr/colorForeground); androidx's ColorStateListInflaterCompat
     *      read that through getXml -> TypedArray.getColor, nothing could name
     *      it, and every row took the inflater's Color.MAGENTA default. The
     *      list was never theme-less: the theme was right there, the ATTRIBUTE
     *      was unreadable.
     *
     *  (2) The MACAPK_TYPE_NS annotations are removed. AOSP's parser reports
     *      only the document's own attributes (XmlBlock.Parser
     *      getAttributeCount :248-256 is the native attribute count), but ours
     *      handed "alpha=attribute", "color=attribute", "state_enabled=boolean"
     *      to app code as REAL attributes: androidx's inflater then read their
     *      getAttributeNameResource (app R.attr.color, 0) as extra STATE SPECS,
     *      and a 0 in a state spec is the terminator. */
    private static void macapkTypeCompiledElement(org.w3c.dom.Element e) {
        org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
        java.util.List<org.w3c.dom.Attr> notes = null;
        for (int i = 0; attrs != null && i < attrs.getLength(); i++) {
            org.w3c.dom.Attr a = (org.w3c.dom.Attr) attrs.item(i);
            if (MACAPK_AXML_TYPE_NS.equals(a.getNamespaceURI())) {
                if (notes == null) notes = new java.util.ArrayList<>();
                notes.add(a);
                continue;
            }
            String named = macapkNamedAttrRef(a.getValue());
            if (named != null) a.setValue(named);
        }
        if (notes != null) for (org.w3c.dom.Attr n : notes) e.removeAttributeNode(n);
        org.w3c.dom.NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof org.w3c.dom.Element k) macapkTypeCompiledElement(k);
        }
    }

    /** "?0x0101042a" -> "?android:colorControlActivated" (a platform attr) or
     *  "?<name>" (an app attr); null for anything that is not exactly
     *  BinaryXmlParser's numeric attribute spelling ("?0x" + 8 hex digits), or
     *  an id no table names as an attr, which then keeps its old answer. */
    static String macapkNamedAttrRef(String v) {
        if (!MACAPK_FW_XML_NUMERIC || v == null) return null;
        v = v.trim();
        if (v.length() != 11 || !v.startsWith("?0x")) return null;
        int id;
        try { id = (int) Long.parseLong(v.substring(3), 16); }
        catch (NumberFormatException nfe) { return null; }
        if (id == 0) return null;
        String[] tn = macapkTypeNameOf(id);
        if (tn == null || tn.length < 2 || !"attr".equals(tn[0])
                || tn[1] == null || tn[1].isEmpty()) return null;
        return ((id >>> 24) == 0x01 ? "?android:" : "?") + tn[1];
    }

    /** A compiled enum/flag int ("1", "0x57", "-1") as TypedArray.getInt reads
     *  it, or null when `v` is not numeric (a text symbol, which the caller
     *  maps itself). Gate off: always null, the text-only parse. */
    private static Integer macapkCompiledInt(String v) {
        if (!MACAPK_FW_XML_NUMERIC || v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        char c0 = t.charAt(0);
        if (!(Character.isDigit(c0) || (c0 == '-' && t.length() > 1))) return null;
        try { return Integer.decode(t); }
        catch (NumberFormatException nfe) { return null; }
    }

    /** Gravity from either spelling: the compiled flag int ("0x57", aapt types
     *  a flag assignment TYPE_INT_HEX) or the text form Gravity.parse reads.
     *  Gravity.parse("0x57") is NO_GRAVITY, which is how the platform
     *  btn_toggle_material's layer (item gravity 0x57 = fill_horizontal|bottom)
     *  filled its whole box. */
    private static int macapkParseGravity(String v) {
        Integer n = macapkCompiledInt(v);
        if (n != null) return n;
        return android.view.Gravity.parse(v);
    }

    private android.graphics.drawable.Drawable macapkInflateXmlDrawable(int id, Theme theme) {
        if ("0".equals(System.getenv("MACAPK_XML_DRAWABLE"))) return null;
        int depth = sXmlDrawableDepth.get();
        if (depth > 8) return null;  // cycle / pathological nesting guard
        sXmlDrawableDepth.set(depth + 1);
        try {
            // Platform files come out of the parse memo (macapkFwXmlDoc): the
            // same DOM the compiled branch below builds, parsed once per path.
            org.w3c.dom.Document doc = macapkFwXmlDoc(id);
            byte[] bytes = null;
            if (doc == null) {
                java.io.InputStream in = openRawResource(id);
                if (in == null) return null;
                bytes = in.readAllBytes();
                try { in.close(); } catch (Throwable ignored) {}
            }
            if (doc == null && bytes.length >= 4
                    && bytes[0] == 0x03 && bytes[1] == 0x00
                    && bytes[2] == 0x08 && bytes[3] == 0x00) {
                // RES_XML_TYPE ResChunk_header (u16 type=0x0003, u16
                // headerSize=0x0008 -- the same 4-byte magic AssetManager.
                // openXmlResourceParser discriminates on): COMPILED binary
                // XML. A text XML entry starts '<' or a BOM, never 0x03. App
                // drawables arrive as apktool-decoded text, so this branch
                // is the FRAMEWORK path: an id resolved through the
                // platform table serves its entry straight out of the
                // framework apk (ResourceIndex.macapkFwOpenRaw), un-decoded.
                // AOSP's analogue is loadDrawableForCookie's xml branch
                // handing the compiled file to loadXmlResourceParser
                // (ResourcesImpl.java, android11-release); our
                // BinaryXmlParser produces a namespace-aware DOM whose
                // android:* attributes the inflaters below already read
                // (same parser the compiled-manifest and framework-CSL
                // paths use). Reflective like AssetManager's call site, so
                // android.content.res keeps no link-time dependency on
                // com.macapk.loader. fwxml: through macapkParseCompiledXml, so
                // the typed values read as TypedArray would read them.
                doc = macapkParseCompiledXml(bytes);
            } else if (doc == null) {
                javax.xml.parsers.DocumentBuilderFactory f =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                doc = f.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(bytes));
            }
            org.w3c.dom.Element root = doc.getDocumentElement();
            if (root == null) return null;
            android.graphics.drawable.Drawable d = macapkInflateDrawableElement(root, theme);
            if (d != null) return d;
            // We PARSED a root tag and still produced nothing. Returning null
            // here sends getDrawable to its `new VectorDrawable(this, id,
            // theme)` fallback, which fabricates a 24x24dp intrinsic size for
            // XML that is not a vector; see macapkUnhandledRootDrawable.
            // Returning null when the parse itself FAILED (the catch below) is
            // correct and unchanged: there we know nothing about the file.
            return macapkUnhandledRootDrawable(macapkLocalTag(root));
        } catch (Throwable t) {
            return null;
        } finally {
            sXmlDrawableDepth.set(depth);
        }
    }

    private static String macapkLocalTag(org.w3c.dom.Element e) {
        String tag = e.getTagName();
        if (tag == null) return "";
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private android.graphics.drawable.Drawable macapkInflateDrawableElement(org.w3c.dom.Element e, Theme theme) {
        if (e == null) return null;
        switch (macapkLocalTag(e)) {
            case "layer-list": return macapkInflateLayerList(e, theme);
            // <ripple> is NOT a layer-list: it carries android:color (the press
            // highlight) and its android.R.id/mask child is a shape, not a
            // visible layer. Sharing the layer-list inflater meant the colour was
            // dropped on the floor and every mask-only <ripple> -- which is what
            // ?attr/selectableItemBackground is, its ONLY child being the mask --
            // inflated to zero layers, hence null, hence the transparent
            // ColorDrawable from macapkUnhandledRootDrawable. See
            // macapkInflateRipple.
            case "ripple":     return macapkInflateRipple(e, theme);
            case "shape":    return macapkInflateShape(e, theme);
            // `animated-selector` reuses the plain selector inflater: it only reads
            // <item> children and already skips <transition> elements, so the
            // static state list comes out right and only the transition animation
            // is absent (cosmetic, not a no-pixels gap).
            case "selector": case "animated-selector": return macapkInflateSelector(e, theme);
            // <inset> was UNHANDLED and fell to `default`, and it is not obscure:
            // androidx.appcompat's own default button
            // (abc_btn_default_mtrl_shape.xml, abc_btn_colored_material.xml), edit
            // text (abc_edit_text_material.xml), dialog (mtrl_dialog_background.xml,
            // abc_dialog_material_background.xml), spinner
            // (abc_spinner_textfield_background_material.xml) and notification-action
            // backgrounds are ALL <inset>-rooted, verbatim-identical across the two
            // corpus APKs that were statically decoded to check. So every AppCompat
            // app's default chrome for those widgets inflated to an empty
            // VectorDrawable -- i.e. invisible -- unless a theme overrode it.
            // InsetDrawable itself was already correct; it was simply never reached.
            case "inset":    return macapkInflateInset(e, theme);
            case "adaptive-icon": return macapkInflateAdaptiveIcon(e, theme);
            // icon-lifecycle fan-out lane (2026-07-23, BUG B re-open): a
            // <vector> reached THIS switch either as the root of its own
            // resource file (macapkInflateXmlDrawable, called from
            // getDrawable(id)) or nested INLINE inside another drawable's
            // XML (macapkInflateItemDrawable -> here, for an
            // <adaptive-icon>/<layer-list> child with no android:drawable
            // ref). The old `default: return null` comment assumed a
            // caller-side fallback existed for the <vector> case, which is
            // true ONLY for the id-based top-level path (getDrawable(id)
            // falls back to `new VectorDrawable(this, id)` when
            // macapkInflateXmlDrawable returns null) -- the NESTED path has
            // no such fallback, so an inline <vector> silently inflated to
            // nothing. Confirmed root cause of Calculator's launcher icon
            // (its <adaptive-icon><background> is a bare <vector> child,
            // no ref) rendering solid black: composited to a no-op layer,
            // leaving AdaptiveIconDrawable's black canvas foundation as the
            // whole result. Handling it here covers BOTH callers uniformly
            // (and is a no-op behavior change for the top-level path: same
            // class gets constructed, just without the redundant re-parse
            // the old fallback did via a second openRawResource() read).
            // lane layout, 2026-08-01: thread `theme` so an inline <vector>'s
            // `?attr` path fills resolve like the standalone-file path does.
            // `this` (the Resources) is required: without it the vector cannot
            // resolve aapt2 inline <gradient> fills or `@color/` path fills.
            case "vector":   return new android.graphics.drawable.VectorDrawable(e, theme, this);
            // <bitmap> and <nine-patch> were UNHANDLED and fell to `default`.
            // See macapkInflateBitmap for why that was expensive to find.
            case "bitmap": case "nine-patch": return macapkInflateBitmap(e, theme);
            // ---- v1.0.1037: the tags that had NO case at all ----------------
            //
            // The four comments above this line each record the SAME bug being
            // found and patched one tag at a time (<inset>, <bitmap>,
            // <nine-patch>, nested <vector>): an unhandled root tag fell to
            // `default: return null`, getDrawable answered with `new
            // VectorDrawable(this, id, theme)`, and a non-vector XML parsed as
            // a vector yields zero paths -- so draw() returns immediately while
            // getIntrinsicWidth/Height keep claiming 24x24dp. Nothing throws at
            // any step. Rather than add a fifth one-tag patch, this closes the
            // whole set that AOSP's DrawableInflater.inflateFromTag
            // (core/java/android/graphics/drawable/DrawableInflater.java:127-172,
            // android16-release) knows and we did not, and then fixes the
            // DEFAULT itself (see macapkUnhandledRootDrawable).
            //
            // Measured across the 14 installed apps' decoded res trees, by root
            // tag: animated-vector 76, transition 8, animation-list 2,
            // level-list 1. animated-vector is the big one -- and note
            // AnimatedVectorDrawable.macapkSetInner, the hook its own header
            // comment says "the resource inflater hands us the base vector to
            // paint", had ZERO callers, so every one of those 76 drawables was
            // an invisible 24x24.
            case "animated-vector": return macapkInflateAnimatedVector(e, theme);
            case "animated-image":  return macapkInflateAnimatedImage(e, theme);
            case "scale":           return macapkInflateScale(e, theme);
            case "clip":            return macapkInflateClip(e, theme);
            // AOSP maps <rotate> to RotateDrawable and <animated-rotate> to the
            // @hide AnimatedRotateDrawable (DrawableInflater.java:150,:152). We
            // have no AnimatedRotateDrawable class; its NON-animating appearance
            // is exactly a RotateDrawable at its start angle, so both tags land
            // there. The spinner keeps its artwork and does not spin, which is a
            // stated gap rather than an invisible one.
            case "rotate": case "animated-rotate": return macapkInflateRotate(e, theme);
            case "level-list":      return macapkInflateLevelList(e, theme);
            case "animation-list":  return macapkInflateAnimationList(e, theme);
            case "transition":      return macapkInflateTransition(e, theme);
            // AOSP: `case "color": drawable = new ColorDrawable(); break;`
            // (DrawableInflater.java:157), then ColorDrawable.inflate reads
            // android:color. A root <color> is how a solid-colour drawable
            // resource is spelled.
            case "color":           return macapkInflateColorDrawable(e, theme);
            default:
                // A FULLY-QUALIFIED CLASS NAME is a first-class drawable root on
                // Android, not an exotic case. AOSP's DrawableInflater tries the
                // known tags, gets null, and then reflects:
                //   inflateFromXmlForDensity: "Drawable drawable = inflateFromTag(name);
                //   if (drawable == null) { drawable = inflateFromClass(name); }
                //   drawable.inflate(mRes, parser, attrs, theme);"
                //   (DrawableInflater.java:136-141, android16-release)
                // and inflateFromClass resolves the name through the RESOURCES'
                // ClassLoader (DrawableInflater.java:200 + Resources.java:426),
                // never the thread's.
                //
                // Measured on Instagram: 2,745 of its 5,501 drawable XML files --
                // 49.9% -- have a custom class root (AppFbIconDrawable x1525,
                // AppIkonDrawable x841, AppRedrawableDrawable x163, ...). Every one
                // of them was becoming a transparent, size-less ColorDrawable, i.e.
                // half the app's iconography silently invisible. Generic: the same
                // applies to every Facebook/Messenger/WhatsApp build and to any app
                // using <drawable class="...">.
                {
                    android.graphics.drawable.Drawable byClass =
                        macapkInflateDrawableFromClass(e, theme);
                    if (byClass != null) return byClass;
                }
                macapkWarnUnhandledDrawableTag(macapkLocalTag(e));
                return null;
        }
    }

    /** AOSP's {@code DrawableInflater.inflateFromClass} + the {@code <drawable
     *  class="...">} escape hatch, over our DOM.
     *
     *  Two things AOSP does that are easy to get wrong here:
     *  1. The class is resolved through the RESOURCES' ClassLoader
     *     ({@code DrawableInflater.java:200}, fed by {@code Resources.java:426}),
     *     NOT the thread context ClassLoader. A guest thread with no Java frames
     *     has the SYSTEM loader as its TCCL, so TCCL would miss every app class.
     *  2. The inner-class escape hatch: an XML tag cannot contain '$', so
     *     {@code <drawable class="Outer$Inner">} is how a nested Drawable is
     *     spelled ({@code DrawableInflater.java:129-134}).
     *
     *  Unlike AOSP we do NOT let a failure throw out of getDrawable. AOSP raises
     *  InflateException; here that would propagate through every inflate and turn
     *  an invisible icon into a dead app, which is the same reasoning that gives
     *  macapkUnhandledRootDrawable its transparent terminus. We keep AOSP's three
     *  message texts (libraries grep for them) but log once per class and fall
     *  through to that terminus. */
    private android.graphics.drawable.Drawable macapkInflateDrawableFromClass(
            org.w3c.dom.Element e, Theme theme) {
        String name = macapkLocalTag(e);
        if (name == null) return null;
        if ("drawable".equals(name)) {
            String cls = e.getAttribute("class");
            if (cls == null || cls.isEmpty()) return null;
            name = cls;
        } else if (name.indexOf('.') < 0) {
            // Not a class name: an unknown BARE tag is a genuine gap in our
            // switch and should still reach the warning, not a ClassNotFound.
            return null;
        }
        try {
            ClassLoader cl = getClassLoader();
            if (cl == null) cl = Resources.class.getClassLoader();
            Class<?> raw = cl.loadClass(name);
            if (!android.graphics.drawable.Drawable.class.isAssignableFrom(raw)) {
                macapkWarnDrawableClass(name, "Class is not a Drawable " + name);
                return null;
            }
            Class<? extends android.graphics.drawable.Drawable> clazz =
                raw.asSubclass(android.graphics.drawable.Drawable.class);
            android.graphics.drawable.Drawable d =
                clazz.getDeclaredConstructor().newInstance();
            // The app's inflate() wants a real XmlPullParser+AttributeSet over
            // THIS element, positioned AT the drawable's START_TAG. AOSP's
            // Drawable.createFromXmlForDensity advances the parser to the first
            // START_TAG ("Look for the root node" seek loop) BEFORE it calls
            // drawable.inflate(r, parser, attrs, theme) -- so a drawable whose
            // inflate() reads its OWN attributes via obtainAttributes(attrs,
            // styleable), the common case, finds them at the current position.
            //
            // Our DomXmlResourceParser is created at START_DOCUMENT (cursor -1).
            // Handing it over un-advanced (the old behavior here) means such a
            // drawable reads getAttributeCount() == -1 and every getInt/getString
            // returns its DEFAULT. That is exactly why Instagram's bottom-nav
            // AppIkonDrawable icons came back size 0x0: com.facebook.fbui...
            // AppIkonDrawable.inflate (X.2c1) passes the AttributeSet straight to
            // its inflateInner WITHOUT seeking, so with the parser still at
            // START_DOCUMENT it read fbui:size_dp / fbui:vector_index as 0, its
            // packed-vector atlas (X.0TV.A00(0,0)) returned null, wrap() was
            // never called, the delegate stayed null and getIntrinsicWidth()
            // returned 0 -- and X.5nS.A00's Bitmap.createBitmap(intrinsicW,
            // intrinsicH) threw "width and height must be > 0", killing the feed.
            // Seek to the START_TAG exactly as AOSP does; app-agnostic across
            // every <class>-rooted drawable that reads its own attributes.
            Object parser = macapkParserForElement(e);
            if (parser == null) return null;
            org.xmlpull.v1.XmlPullParser xpp = (org.xmlpull.v1.XmlPullParser) parser;
            int evt = xpp.getEventType();
            while (evt != org.xmlpull.v1.XmlPullParser.START_TAG
                    && evt != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                evt = xpp.next();
            }
            d.inflate(this, xpp, (android.util.AttributeSet) parser, theme);
            return d;
        } catch (ClassNotFoundException ex) {
            macapkWarnDrawableClass(name, "Class not found " + name);
        } catch (NoSuchMethodException ex) {
            macapkWarnDrawableClass(name, "Error inflating class " + name
                + " (no public no-arg constructor)");
        } catch (Throwable ex) {
            macapkWarnDrawableClass(name, "Error inflating class " + name
                + ": " + ex);
        }
        return null;
    }

    /** One line per CLASS, not per inflate -- a screen full of icons from one
     *  broken class would otherwise be a screen full of log. */
    private static final java.util.Set<String> sWarnedDrawableClasses =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static void macapkWarnDrawableClass(String cls, String msg) {
        if (cls == null || !sWarnedDrawableClasses.add(cls)) return;
        com.macapk.loader.NativeLog.w("Mac.apk.Resources",
            "drawable XML: " + msg + " -- it will not paint");
    }

    /** A parser/AttributeSet positioned on {@code e}, built by re-rooting the
     *  element into its own document. Reflective for the same reason the
     *  BinaryXmlParser call above is: android.content.res keeps no link-time
     *  dependency on com.macapk.loader. */
    private Object macapkParserForElement(org.w3c.dom.Element e) {
        try {
            org.w3c.dom.Document owner = e.getOwnerDocument();
            org.w3c.dom.Document doc;
            if (owner != null && owner.getDocumentElement() == e) {
                doc = owner;                       // already the root: reuse it
            } else {
                javax.xml.parsers.DocumentBuilderFactory f =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                doc = f.newDocumentBuilder().newDocument();
                doc.appendChild(doc.importNode(e, true));
            }
            Class<?> p = Class.forName("com.macapk.loader.DomXmlResourceParser");
            return p.getConstructor(org.w3c.dom.Document.class).newInstance(doc);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tags we have already complained about, so the warning is one line per
     *  tag for the process rather than one per inflate. */
    private static final java.util.Set<String> sWarnedDrawableTags =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static void macapkWarnUnhandledDrawableTag(String tag) {
        if (tag == null || tag.isEmpty()) return;
        if (!sWarnedDrawableTags.add(tag)) return;
        // ⚠ CORRECTED 2026-09-21: this used to claim "AOSP
        // DrawableInflater.inflateFromTag ends with throw new
        // XmlPullParserException(...) so on a device this is never silent."
        // That is FALSE against android16-release: inflateFromTag's switch ends
        // `default: return null;` (DrawableInflater.java:188-189) and the CALLER
        // falls through to inflateFromClass (:136-138). Believing it throws is
        // why this branch was built as a terminus instead of a fallthrough, and
        // why ~50% of Instagram's drawables were invisible. The class fallback
        // above is the missing half; this warning is now only for a genuinely
        // unknown BARE tag. Say it once, by name.
        com.macapk.loader.NativeLog.w("Mac.apk.Resources",
            "drawable XML: no inflater for root tag <" + tag + ">;"
            + " it will not paint. Add a case to macapkInflateDrawableElement.");
    }

    /** What a TOP-LEVEL drawable resource becomes when its root tag parsed but
     *  no inflater produced anything.
     *
     *  It must NOT be getDrawable's `new VectorDrawable(this, id, theme)`
     *  fallback. That fallback is the actual defect behind every one of the
     *  five one-tag patches in this file: it hands back a drawable that paints
     *  nothing AND claims a 24x24dp intrinsic size, and the fabricated SIZE is
     *  the part that does real damage -- sfsolitaire allocated 20 atlas bitmaps
     *  at 24x24, sliced 52 card faces out of them and painted blanks, with
     *  every step succeeding. A -1 intrinsic would have named itself instantly.
     *
     *  AOSP throws here (DrawableInflater.java:171). We deliberately do not:
     *  a throw out of getDrawable propagates through every inflate and would
     *  convert an invisible widget into a dead app, and this lane cannot run
     *  the sweep to measure that. The honest middle is a fully transparent
     *  ColorDrawable, whose getIntrinsicWidth/Height are -1 ("I do not know my
     *  size"), paired with the loud warning above. Nothing is fabricated.
     *
     *  Returns null for a <vector> root so that path keeps its historical
     *  id-based re-parse in getDrawable. */
    private android.graphics.drawable.Drawable macapkUnhandledRootDrawable(String rootTag) {
        if ("vector".equals(rootTag)) return null;
        return new android.graphics.drawable.ColorDrawable(0x00000000);
    }

    /** The child drawable of a wrapper tag, in the three spellings apktool can
     *  produce: the android:drawable attribute, an aapt2 inline
     *  {@code <aapt:attr name="android:drawable">} child, or (for the tags that
     *  allow it) a bare nested drawable element. macapkInflateItemDrawable
     *  covers the first and last but would pick up an {@code <aapt:attr>} or a
     *  {@code <target>} element as if it were the child, which is why this
     *  exists rather than reusing it directly. */
    private android.graphics.drawable.Drawable macapkWrapperChild(
            org.w3c.dom.Element root, String attrName, Theme theme) {
        String ref = root.getAttributeNS(MACAPK_ANDROID_NS, attrName);
        if (ref != null && !ref.isEmpty()) return macapkResolveDrawableRef(ref, theme);
        org.w3c.dom.NodeList kids = root.getChildNodes();
        // Pass 1: an explicit aapt:attr naming this attribute.
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (!(n instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element el = (org.w3c.dom.Element) n;
            if (!"attr".equals(macapkLocalTag(el))) continue;
            String nm = el.getAttribute("name");
            if (nm == null || !nm.endsWith(attrName)) continue;
            org.w3c.dom.NodeList inner = el.getChildNodes();
            for (int j = 0; j < inner.getLength(); j++) {
                org.w3c.dom.Node c = inner.item(j);
                if (c instanceof org.w3c.dom.Element) {
                    return macapkInflateDrawableElement((org.w3c.dom.Element) c, theme);
                }
            }
        }
        // Pass 2: a bare nested drawable element, skipping the metadata tags
        // that are not drawables (<target> on animated-vector, <item> handled
        // by the list inflaters).
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (!(n instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element el = (org.w3c.dom.Element) n;
            String tag = macapkLocalTag(el);
            // <target>/<set> are animated-vector animator bindings, <item> is
            // owned by the list inflaters, <attr> was handled in pass 1. None
            // of them is the wrapper's drawable. (Real sample, Aurora's
            // mtrl_switch_thumb_unchecked_checked.xml: an animated-vector whose
            // only child is a <set> of <target>s.)
            if ("target".equals(tag) || "item".equals(tag)
                    || "attr".equals(tag) || "set".equals(tag)) continue;
            return macapkInflateDrawableElement(el, theme);
        }
        return null;
    }

    /** AOSP AnimatedVectorDrawable.inflate (graphics/java/android/graphics/
     *  drawable/AnimatedVectorDrawable.java, android16-release): android:drawable
     *  names the base VectorDrawable, and each {@code <target>} binds an
     *  animator to a named path/group. We paint the base vector and do not run
     *  the timeline (AnimatedVectorDrawable.start() settles immediately and
     *  fires onAnimationEnd, so a caller gated on completion still proceeds). */
    private android.graphics.drawable.Drawable macapkInflateAnimatedVector(
            org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.Drawable inner = macapkWrapperChild(root, "drawable", theme);
        if (inner == null) return null;
        android.graphics.drawable.AnimatedVectorDrawable avd =
            new android.graphics.drawable.AnimatedVectorDrawable();
        avd.macapkSetInner(inner);
        return avd;
    }

    /** AOSP AnimatedImageDrawable: {@code <animated-image android:src=...>}
     *  playing an animated WebP/GIF. BitmapFactory decodes the FIRST FRAME only,
     *  so hand that frame to AnimatedImageDrawable (which is written for exactly
     *  this) when we can, and otherwise return the decoded drawable itself
     *  rather than nothing. */
    private android.graphics.drawable.Drawable macapkInflateAnimatedImage(
            org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.Drawable src = macapkWrapperChild(root, "src", theme);
        if (src == null) return null;
        if (src instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap bm =
                ((android.graphics.drawable.BitmapDrawable) src).getBitmap();
            if (bm != null) {
                android.graphics.drawable.AnimatedImageDrawable aid =
                    new android.graphics.drawable.AnimatedImageDrawable();
                aid.macapkSetBitmap(bm);
                return aid;
            }
        }
        return src;
    }

    /** AOSP ScaleDrawable.updateStateFromTypedArray: scaleWidth/scaleHeight are
     *  TYPE_FRACTION ("70%" -> 0.7), scaleGravity is a gravity mask, and a
     *  missing/invalid fraction means "do not scale this axis" (-1). */
    private android.graphics.drawable.Drawable macapkInflateScale(
            org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.Drawable child = macapkWrapperChild(root, "drawable", theme);
        if (child == null) return null;
        int gravity = macapkGravityAttr(root, "scaleGravity", android.view.Gravity.LEFT);
        return new android.graphics.drawable.ScaleDrawable(child, gravity,
            macapkPercentAttr(root, "scaleWidth",  -1f),
            macapkPercentAttr(root, "scaleHeight", -1f));
    }

    /** AOSP ClipDrawable.updateStateFromTypedArray: clipOrientation defaults to
     *  HORIZONTAL(1) and gravity to LEFT. */
    private android.graphics.drawable.Drawable macapkInflateClip(
            org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.Drawable child = macapkWrapperChild(root, "drawable", theme);
        if (child == null) return null;
        String o = root.getAttributeNS(MACAPK_ANDROID_NS, "clipOrientation");
        int orientation = (o != null && o.toLowerCase(java.util.Locale.ROOT).contains("vertical"))
            ? android.graphics.drawable.ClipDrawable.VERTICAL
            : android.graphics.drawable.ClipDrawable.HORIZONTAL;
        // fwxml: a compiled clipOrientation is its flag int (attrs.xml
        // android14-release :6974-6979, horizontal=1 vertical=2), which the
        // text test above reads as HORIZONTAL whatever it says. AOSP takes the
        // int as is: `a.getInt(R.styleable.ClipDrawable_clipOrientation, ...)`.
        Integer on = macapkCompiledInt(o);
        if (on != null) orientation = on;
        return new android.graphics.drawable.ClipDrawable(child,
            macapkGravityAttr(root, "gravity", android.view.Gravity.LEFT), orientation);
    }

    /** AOSP RotateDrawable.updateStateFromTypedArray: fromDegrees/toDegrees
     *  default 0/360, pivotX/pivotY default 0.5 RELATIVE (TYPE_FRACTION). */
    private android.graphics.drawable.Drawable macapkInflateRotate(
            org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.Drawable child = macapkWrapperChild(root, "drawable", theme);
        if (child == null) return null;
        android.graphics.drawable.RotateDrawable rd =
            new android.graphics.drawable.RotateDrawable(child);
        rd.setFromDegrees(macapkFloatAttr(root, "fromDegrees", 0f));
        rd.setToDegrees(macapkFloatAttr(root, "toDegrees", 360f));
        String px = root.getAttributeNS(MACAPK_ANDROID_NS, "pivotX");
        String py = root.getAttributeNS(MACAPK_ANDROID_NS, "pivotY");
        rd.setPivotXRelative(px == null || px.isEmpty() || px.endsWith("%"));
        rd.setPivotYRelative(py == null || py.isEmpty() || py.endsWith("%"));
        rd.setPivotX(macapkPercentAttr(root, "pivotX", 0.5f));
        rd.setPivotY(macapkPercentAttr(root, "pivotY", 0.5f));
        return rd;
    }

    /** AOSP LevelListDrawable.inflate: each {@code <item>} carries
     *  android:minLevel / android:maxLevel (defaults 0 / MAX_VALUE) plus a
     *  drawable. */
    private android.graphics.drawable.Drawable macapkInflateLevelList(
            org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.LevelListDrawable lld =
            new android.graphics.drawable.LevelListDrawable();
        int n = 0;
        org.w3c.dom.NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node node = kids.item(i);
            if (!(node instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element item = (org.w3c.dom.Element) node;
            if (!"item".equals(macapkLocalTag(item))) continue;
            android.graphics.drawable.Drawable d = macapkInflateItemDrawable(item, theme);
            if (d == null) continue;
            lld.addLevel(macapkIntAttr(item, "minLevel", 0),
                         macapkIntAttr(item, "maxLevel", Integer.MAX_VALUE), d);
            n++;
        }
        return n > 0 ? lld : null;
    }

    /** AOSP AnimationDrawable.inflate: {@code <animation-list android:oneshot>}
     *  of {@code <item android:drawable android:duration>} frames. */
    private android.graphics.drawable.Drawable macapkInflateAnimationList(
            org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.AnimationDrawable ad =
            new android.graphics.drawable.AnimationDrawable();
        String one = root.getAttributeNS(MACAPK_ANDROID_NS, "oneshot");
        ad.setOneShot("true".equalsIgnoreCase(one));
        int n = 0;
        org.w3c.dom.NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node node = kids.item(i);
            if (!(node instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element item = (org.w3c.dom.Element) node;
            if (!"item".equals(macapkLocalTag(item))) continue;
            android.graphics.drawable.Drawable d = macapkInflateItemDrawable(item, theme);
            if (d == null) continue;
            ad.addFrame(d, macapkIntAttr(item, "duration", 0));
            n++;
        }
        return n > 0 ? ad : null;
    }

    /** AOSP TransitionDrawable extends LayerDrawable and inflates the same
     *  {@code <item>} list; it cross-fades between exactly two of them on
     *  startTransition(). With no transition driven it shows layer 0, which is
     *  the state a never-started TransitionDrawable is in on a device too. */
    private android.graphics.drawable.Drawable macapkInflateTransition(
            org.w3c.dom.Element root, Theme theme) {
        java.util.List<android.graphics.drawable.Drawable> layers = new java.util.ArrayList<>();
        org.w3c.dom.NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node node = kids.item(i);
            if (!(node instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element item = (org.w3c.dom.Element) node;
            if (!"item".equals(macapkLocalTag(item))) continue;
            android.graphics.drawable.Drawable d = macapkInflateItemDrawable(item, theme);
            if (d != null) layers.add(d);
        }
        if (layers.isEmpty()) return null;
        return new android.graphics.drawable.TransitionDrawable(
            layers.toArray(new android.graphics.drawable.Drawable[0]));
    }

    /** AOSP ColorDrawable.inflate: a root {@code <color android:color=...>}.
     *  The value may be a literal, an @color reference or a ?attr. */
    private android.graphics.drawable.Drawable macapkInflateColorDrawable(
            org.w3c.dom.Element root, Theme theme) {
        String c = root.getAttributeNS(MACAPK_ANDROID_NS, "color");
        if (c == null || c.isEmpty()) return null;
        return new android.graphics.drawable.ColorDrawable(macapkResolveColorString(c, 0, theme));
    }

    private int macapkGravityAttr(org.w3c.dom.Element e, String name, int def) {
        String v = e.getAttributeNS(MACAPK_ANDROID_NS, name);
        if (v == null || v.isEmpty()) return def;
        try { return macapkParseGravity(v); }
        catch (Throwable t) { return def; }
    }

    private int macapkIntAttr(org.w3c.dom.Element e, String name, int def) {
        String v = e.getAttributeNS(MACAPK_ANDROID_NS, name);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException nfe) { return def; }
    }

    private float macapkFloatAttr(org.w3c.dom.Element e, String name, float def) {
        String v = e.getAttributeNS(MACAPK_ANDROID_NS, name);
        if (v == null || v.isEmpty()) return def;
        try { return Float.parseFloat(v.trim()); }
        catch (NumberFormatException nfe) { return def; }
    }

    /** A boolean attribute as TypedArray.getBoolean reads it: the text form
     *  ("true"/"false", how an app's XML renders) or the compiled
     *  TYPE_INT_BOOLEAN int, where any non-zero value is true (aapt writes
     *  0xffffffff). Anything else keeps `def`. */
    private static boolean macapkBoolAttr(org.w3c.dom.Element e, String name, boolean def) {
        String v = e.getAttributeNS(MACAPK_ANDROID_NS, name);
        if (v == null) return def;
        v = v.trim();
        if (v.isEmpty()) return def;
        if ("true".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v)) return false;
        try { return Long.decode(v) != 0L; }
        catch (NumberFormatException nfe) { return def; }
    }

    /** A TYPE_FRACTION attribute: "70%" -> 0.7f, a bare float passes through
     *  (aapt keeps the "%" through apktool's decode, but a hand-written
     *  drawable may use either). */
    private float macapkPercentAttr(org.w3c.dom.Element e, String name, float def) {
        String v = e.getAttributeNS(MACAPK_ANDROID_NS, name);
        if (v == null || v.isEmpty()) return def;
        v = v.trim();
        boolean pct = v.endsWith("%");
        if (pct) v = v.substring(0, v.length() - 1).trim();
        try {
            float f = Float.parseFloat(v);
            return pct ? f / 100f : f;
        } catch (NumberFormatException nfe) { return def; }
    }

    /** AOSP BitmapDrawable.inflate / updateStateFromTypedArray
     *  (frameworks/base/graphics/java/android/graphics/drawable/BitmapDrawable.java):
     *  a <bitmap> element is a THIN WRAPPER naming a raster asset via
     *  android:src, layering positioning / tiling / alpha / tint on top.
     *
     *  WHY THIS WAS INVISIBLE FOR SO LONG. The tag fell to `default: return
     *  null`, which sent getDrawable() to its `new VectorDrawable(this, id,
     *  theme)` fallback -- and a <bitmap> parsed as a <vector> yields ZERO
     *  paths, so draw() returns immediately (VectorDrawable: `if (canvas ==
     *  null || paths.isEmpty()) return;`) while the intrinsic size stays at the
     *  24x24dp default. The result is a fully transparent 24x24 drawable that
     *  throws NOTHING, at any step.
     *
     *  sfsolitaire is the case that exposed it: it loads 20 sprite ATLASES this
     *  way (cardback, medsuits, smallsuits, the rank fonts), reads
     *  getIntrinsicWidth/Height, allocates an offscreen Bitmap that size,
     *  Drawable.draw()s the atlas into it, then slices 52 card faces out with
     *  drawBitmap(src,dst). Every step SUCCEEDED -- 24x24 is a perfectly valid
     *  allocation -- so the app painted its card OUTLINES correctly and every
     *  rank, suit and card back came out blank. A -1 or 0 intrinsic would have
     *  thrown out of createBitmap and named itself years ago; 24 did not.
     *
     *  Not a one-app gap: 672 <bitmap> elements across 46 corpus apps.
     *
     *  <nine-patch> shares the shape (android:src at a .9.png) and was equally
     *  unhandled. getDrawable's raster branch already builds a real
     *  NinePatchDrawable for the referenced file, so resolving the ref IS the
     *  whole fix -- hence the shared case above. */
    private android.graphics.drawable.Drawable macapkInflateBitmap(org.w3c.dom.Element root, Theme theme) {
        String src = root.getAttributeNS(MACAPK_ANDROID_NS, "src");
        if (src == null || src.isEmpty()) return null;
        android.graphics.drawable.Drawable d = macapkResolveDrawableRef(src, theme);

        // AOSP NinePatchDrawable.updateStateFromTypedArray (graphics/java/
        // android/graphics/drawable/NinePatchDrawable.java, android13-release):
        //     state.mBaseAlpha = a.getFloat(R.styleable.NinePatchDrawable_alpha, ...);
        //     final int tintMode = a.getInt(R.styleable.NinePatchDrawable_tintMode, -1);
        //     ...
        //     final ColorStateList tint = a.getColorStateList(R.styleable.NinePatchDrawable_tint);
        //     if (tint != null) state.mTint = tint;
        // -- tint is read directly off NinePatchDrawable's OWN typed array; it
        // is not a BitmapDrawable-only attribute the way gravity/tileMode are
        // (NinePatchDrawable has no styleable entry for either). setTint/
        // setTintList are also declared on the shared Drawable base in this
        // tree (Drawable.java), so applying tint here, before the
        // BitmapDrawable-only gate below, is safe for any resolved drawable.
        // This MUST run before the early return just below or a <nine-patch>'s
        // tint is silently dropped -- confirmed root cause of AppCompat's
        // abc_edit_text_material.xml (EditText underline:
        // android:tint="?colorControlNormal" unfocused / "?colorControlActivated"
        // focused) rendering the same untinted tone in both states: the
        // geometry and image arrived, the tint never did. See
        // docs/hard-fixes-catalog.md if this needs a durable entry.
        String tint = root.getAttributeNS(MACAPK_ANDROID_NS, "tint");
        if (d != null && tint != null && !tint.isEmpty()) {
            int c = macapkResolveColorString(tint, 0, theme);
            if (c != 0) d.setTint(c);
        }

        // A .9.png resolves to a NinePatchDrawable (which does NOT extend
        // BitmapDrawable here): it already carries its own stretch divs and
        // content padding, and re-wrapping would throw both away. Anything else
        // non-raster (a nested <vector>/<shape> some apps point src at) is
        // likewise returned untouched rather than forced into a bitmap shape.
        // (tint was already applied above, before this gate, so it survives
        // the early return; gravity/tileMode/alpha below remain
        // BitmapDrawable-only, matching AOSP's per-tag styleable split.)
        if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return d;
        android.graphics.drawable.BitmapDrawable bd =
            (android.graphics.drawable.BitmapDrawable) d;
        // getDrawable -> ConstantState.newDrawable hands back a FRESH facade
        // over shared pixel data, so configuring in place cannot leak into
        // another holder of the same asset, and it preserves the density the
        // raster path already adopted.

        // ⚠ GRAVITY IS SET ONLY WHEN PRESENT. AOSP's default is Gravity.FILL and
        // our field already defaults to FILL, but Gravity.parse("") returns
        // NO_GRAVITY -- so an unconditional setGravity would silently flip all
        // 342 fleet sites that omit the attribute from stretch-to-bounds to
        // draw-at-intrinsic-size.
        if (root.hasAttributeNS(MACAPK_ANDROID_NS, "gravity")) {
            String g = root.getAttributeNS(MACAPK_ANDROID_NS, "gravity");
            if (g != null && !g.isEmpty()) bd.setGravity(macapkParseGravity(g));
        }

        // AOSP: tileMode is the both-axes shorthand; tileModeX/Y override per
        // axis. "disabled" means null (blit once), which is also the default.
        android.graphics.Shader.TileMode both =
            macapkParseTileMode(root.getAttributeNS(MACAPK_ANDROID_NS, "tileMode"));
        android.graphics.Shader.TileMode tx = both, ty = both;
        if (root.hasAttributeNS(MACAPK_ANDROID_NS, "tileModeX"))
            tx = macapkParseTileMode(root.getAttributeNS(MACAPK_ANDROID_NS, "tileModeX"));
        if (root.hasAttributeNS(MACAPK_ANDROID_NS, "tileModeY"))
            ty = macapkParseTileMode(root.getAttributeNS(MACAPK_ANDROID_NS, "tileModeY"));
        if (tx != null || ty != null) bd.setTileModeXY(tx, ty);

        String alpha = root.getAttributeNS(MACAPK_ANDROID_NS, "alpha");
        // fwxml: `?android:disabledAlpha` (the platform ratingbar_*_material
        // bitmaps) is a theme float; AOSP reads it with a.getFloat after
        // obtainAttributes resolved the reference. Resolve it the same way,
        // then parse the literal as before.
        if (MACAPK_FW_XML_NUMERIC && alpha != null && alpha.trim().startsWith("?")) {
            String raw = macapkResolveThemeAttrRaw(alpha.trim(), theme);
            alpha = raw;
        }
        if (alpha != null && !alpha.isEmpty()) {
            try { bd.setAlpha(Math.round(Float.parseFloat(alpha) * 255f)); }
            catch (Throwable ignored) {}
        }
        // tint is applied earlier in this method (before the BitmapDrawable
        // gate above), so a plain <bitmap> gets it exactly as before and a
        // <nine-patch> no longer loses it. Not duplicated here.
        // antialias / filter / dither are already no-ops on BitmapDrawable
        // (Java2D decides its own interpolation), so accepting them silently is
        // the honest behaviour rather than pretending to store them.
        return bd;
    }

    /** AOSP Shader.TileMode from a <bitmap> tileMode attribute. AOSP's own
     *  enum value for "disabled" is -1, which BitmapDrawable maps to a null
     *  TileMode meaning "blit once" rather than tile -- so null here is the
     *  correct answer for absent AND for explicitly-disabled. */
    private static android.graphics.Shader.TileMode macapkParseTileMode(String v) {
        if (v == null || v.isEmpty()) return null;
        // fwxml: the compiled enum int (attrs.xml android14-release :6801-6811:
        // disabled=-1 clamp=0 repeat=1 mirror=2).
        Integer n = macapkCompiledInt(v);
        if (n != null) {
            switch (n) {
                case 0:  return android.graphics.Shader.TileMode.CLAMP;
                case 1:  return android.graphics.Shader.TileMode.REPEAT;
                case 2:  return android.graphics.Shader.TileMode.MIRROR;
                default: return null;   // -1 disabled / unknown
            }
        }
        switch (v) {
            case "repeat": return android.graphics.Shader.TileMode.REPEAT;
            case "mirror": return android.graphics.Shader.TileMode.MIRROR;
            case "clamp":  return android.graphics.Shader.TileMode.CLAMP;
            default:       return null;   // "disabled" / unknown
        }
    }

    /** Inflate <adaptive-icon> (the modern launcher-icon format): a background
     *  + foreground drawable (each `android:drawable="@color/.."` / `"@drawable/.."`,
     *  the foreground commonly a <vector>). Used to render an app's launcher icon
     *  for the macOS library tile (apps that ship ONLY an adaptive/vector icon
     *  -- e.g. duolingo -- have no raster mipmap, so the C++ unpacker's raster
     *  extractor finds nothing). Reuses macapkInflateItemDrawable for the child
     *  drawable refs; AdaptiveIconDrawable.draw composites bg + fg. */
    private android.graphics.drawable.Drawable macapkInflateAdaptiveIcon(org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.Drawable bg = null, fg = null;
        org.w3c.dom.NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (!(n instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element el = (org.w3c.dom.Element) n;
            String tag = macapkLocalTag(el);
            if ("background".equals(tag))      bg = macapkInflateItemDrawable(el, theme);
            else if ("foreground".equals(tag)) fg = macapkInflateItemDrawable(el, theme);
            // <monochrome> (themed icons) ignored -- not needed for the tile.
        }
        return new android.graphics.drawable.AdaptiveIconDrawable(bg, fg);
    }

    /** AOSP InsetDrawable inflation: a single child plus uniform or per-side
     *  insets. Mirrors InsetDrawable's own attribute set (android:inset as the
     *  uniform fallback, then insetLeft/Top/Right/Bottom). */
    private android.graphics.drawable.Drawable macapkInflateInset(org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.Drawable child = macapkInflateItemDrawable(root, theme);
        if (child == null) return null;
        int uniform = macapkDimAttr(root, "inset", 0, theme);
        return new android.graphics.drawable.InsetDrawable(
            child,
            macapkDimAttr(root, "insetLeft",   uniform, theme),
            macapkDimAttr(root, "insetTop",    uniform, theme),
            macapkDimAttr(root, "insetRight",  uniform, theme),
            macapkDimAttr(root, "insetBottom", uniform, theme));
    }

    private android.graphics.drawable.Drawable macapkInflateLayerList(org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.LayerDrawable ld =
            new android.graphics.drawable.LayerDrawable();
        macapkFillLayerList(root, theme, ld, false);
        return ld.getNumberOfLayers() > 0 ? ld : null;
    }

    /** AOSP LayerDrawable.inflateLayers reads EVERY item's id
     *  (android14-release LayerDrawable.java updateLayerFromTypedArray:
     *  `layer.mId = a.getResourceId(R.styleable.LayerDrawableItem_id, layer.mId);`),
     *  and findDrawableByLayerId depends on it. We recorded only the ripple mask,
     *  so every framework progress drawable (progress_horizontal_material: items
     *  @android:id/background, secondaryProgress, progress) had three NO_ID
     *  layers; ProgressBar's setVisualProgress then fell back to the WHOLE
     *  drawable for both progress and secondaryProgress, the second call reset
     *  the level to 0, and a SeekBar/ProgressBar drew an empty track (lanes19
     *  media-playback, 2026-09-29; debug/progressdrawable_gate.sh D4).
     *  MACAPK_LAYER_ITEM_IDS=0: only the mask id is recorded, as before. */
    static final boolean MACAPK_LAYER_ITEM_IDS =
        !"0".equals(System.getenv("MACAPK_LAYER_ITEM_IDS"));

    /** An <item android:id> value as an int: the resolved-id forms
     *  BinaryXmlParser produces ("@0x0102000d", "0x0102000d", decimal) or the
     *  symbolic "@[+][*][pkg:]id/name" of text XML. 0 when absent/unresolvable. */
    private int macapkLayerItemId(String idv) {
        if (idv == null) return 0;
        String v = idv.trim();
        if (v.isEmpty()) return 0;
        if (v.startsWith("@")) v = v.substring(1);
        if (v.startsWith("+")) v = v.substring(1);
        if (v.startsWith("*")) v = v.substring(1);
        try {
            if (v.startsWith("0x") || v.startsWith("0X")) return Integer.parseUnsignedInt(v.substring(2), 16);
            if (!v.isEmpty() && Character.isDigit(v.charAt(0))) return Integer.parseInt(v);
        } catch (Throwable t) { return 0; }
        int slash = v.indexOf('/');
        if (slash < 0) return 0;
        String name = v.substring(slash + 1);
        String head = v.substring(0, slash);
        int colon = head.indexOf(':');
        String pkg = colon >= 0 ? head.substring(0, colon) : null;
        try {
            return pkg != null ? getIdentifier(name, "id", pkg) : getIdentifier(name, "id", null);
        } catch (Throwable t) { return 0; }
    }

    /** Is this <item android:id="..."> the ripple mask?
     *
     *  The attribute reaches us in three spellings depending on which parser
     *  produced the DOM: "@android:id/mask" (apktool-decoded app resources),
     *  and a resolved-id form from BinaryXmlParser on the compiled framework
     *  path. Both are accepted -- the old inline test was `contains("mask")`
     *  only, which the id form does not satisfy. */
    private static boolean macapkIsMaskItemId(String idv) {
        if (idv == null || idv.isEmpty()) return false;
        if (idv.contains("mask")) return true;
        String v = idv.trim();
        if (v.startsWith("@")) v = v.substring(1);
        try {
            int id = v.startsWith("0x") || v.startsWith("0X")
                ? Integer.parseUnsignedInt(v.substring(2), 16)
                : Integer.parseInt(v);
            return id == android.graphics.drawable.RippleDrawable.ID_MASK;
        } catch (Throwable t) { return false; }
    }

    /** AOSP <ripple> inflation: RippleDrawable.inflate reads android:color and
     *  android:radius into its own state, then lets LayerDrawable.inflate read
     *  the <item> children -- and the mask child is a REAL layer, tagged
     *  android.R.id.mask (RippleDrawable.java:263, android14-release:
     *      addLayer(mask, null, android.R.id.mask, 0, 0, 0, 0);)
     *  which every draw path then keys off rather than skipping at inflation.
     *  https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/graphics/java/android/graphics/drawable/RippleDrawable.java
     *
     *  updateStateFromTypedArray (:595-618) is the colour half:
     *      final ColorStateList color = a.getColorStateList(R.styleable.RippleDrawable_color);
     *      if (color != null) mState.mColor = color;
     *      mState.mMaxRadius = a.getDimensionPixelSize(R.styleable.RippleDrawable_radius, ...);
     *
     *  Gate-off (the default) returns the pre-existing plain-LayerDrawable
     *  inflation unchanged, mask skipped and colour dropped, so nothing about
     *  the shipped path moves. */
    private android.graphics.drawable.Drawable macapkInflateRipple(org.w3c.dom.Element root, Theme theme) {
        if (!android.graphics.drawable.RippleDrawable.MACAPK_RIPPLE_PRESS) {
            if (!MACAPK_RIPPLE_MASK_BY_ID) return macapkInflateLayerList(root, theme);
            // Gate-off keeps the layer-list rendering, but the MASK is still
            // recognised by id in both spellings. The layer-list test is
            // `contains("mask")`, which the compiled platform XML's
            // `android:id="@0x0102002e"` never satisfies, so every platform
            // <ripple> painted its mask: `item_background_material`, whose
            // only child is a solid WHITE mask (`<item android:id="@id/mask">
            // <color android:color="@color/white"/>`), came back a LayerDrawable
            // that filled the view white. That is the drawable behind
            // ?android:selectableItemBackground, i.e. every AppCompat v21+ list
            // row and clickable header that reads it by id. AOSP never paints
            // the mask (RippleDrawable.drawContent, android14-release
            // :1155-1164: "Draw everything except the mask",
            // `if (array[i].mId != R.id.mask)`).
            android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable();
            macapkFillLayerList(root, theme, ld, false, true);
            return ld.getNumberOfLayers() > 0 ? ld : null;
        }
        android.graphics.drawable.RippleDrawable rd =
            new android.graphics.drawable.RippleDrawable();
        // A single resolved colour rather than a real ColorStateList: our
        // <ripple android:color> values are `?attr/colorControlHighlight` or a
        // literal, and macapkResolveColorString is the resolver every other
        // drawable attribute in this file already goes through. A ripple whose
        // colour is a genuine per-state selector would flatten to its resolved
        // row here; none in the corpus is one.
        int c = macapkResolveColorString(
            root.getAttributeNS(MACAPK_ANDROID_NS, "color"), 0, theme);
        if (c != 0) rd.setColor(android.content.res.ColorStateList.valueOf(c));
        macapkFillLayerList(root, theme, rd, true);
        // AOSP RippleState.mMaxRadius defaults to RADIUS_AUTO (-1); only a real
        // android:radius overrides it.
        int radius = macapkDimAttr(root, "radius", -1, theme);
        if (radius >= 0) rd.setRadius(radius);
        // Returned even with zero layers, unlike macapkInflateLayerList: a
        // <ripple> that is nothing but a press highlight is still a drawable
        // with observable behaviour, and it is also stateful, which the
        // transparent ColorDrawable fallback is not.
        return rd;
    }

    /** The shared <item> walk for <layer-list> and <ripple>. keepMask=false
     *  reproduces the historical layer-list behaviour exactly (mask children
     *  dropped); keepMask=true adds the mask as a layer tagged
     *  RippleDrawable.ID_MASK, which is what AOSP does for a <ripple>. */
    private void macapkFillLayerList(org.w3c.dom.Element root, Theme theme,
                                     android.graphics.drawable.LayerDrawable ld,
                                     boolean keepMask) {
        macapkFillLayerList(root, theme, ld, keepMask, false);
    }

    /** Default ON; `MACAPK_RIPPLE_MASK_BY_ID=0` restores the gate-off <ripple>'s
     *  `contains("mask")`-only test (which paints a compiled platform ripple's
     *  mask), for a one-binary bisect. See macapkInflateRipple. */
    private static final boolean MACAPK_RIPPLE_MASK_BY_ID =
        !"0".equals(System.getenv("MACAPK_RIPPLE_MASK_BY_ID"));

    /** `rippleMaskById`: the element is a <ripple> being rendered as a plain
     *  layer list, so its mask is DROPPED when recognised in either spelling
     *  (macapkIsMaskItemId), not only the `contains("mask")` one. A real
     *  <layer-list> passes false and keeps its historical test. */
    private void macapkFillLayerList(org.w3c.dom.Element root, Theme theme,
                                     android.graphics.drawable.LayerDrawable ld,
                                     boolean keepMask, boolean rippleMaskById) {
        org.w3c.dom.NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (!(n instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element item = (org.w3c.dom.Element) n;
            if (!"item".equals(macapkLocalTag(item))) continue;
            // A ripple's mask <item android:id="@android:id/mask"> is a clip
            // mask, not a visible layer. For a <layer-list> it is dropped (a
            // layer-list has no mask concept and a stray one would paint); for
            // a <ripple> it is kept and TAGGED, because RippleDrawable shapes
            // its press highlight from the mask's outline and already skips
            // ID_MASK in drawContent.
            String idv = item.getAttributeNS(MACAPK_ANDROID_NS, "id");
            // The keepMask=false test is the ORIGINAL `contains("mask")` verbatim,
            // not the broader macapkIsMaskItemId: widening it would newly drop a
            // <layer-list> item whose android:id happens to be the numeric mask
            // id, which is a behaviour change on the default path. The broader
            // test is used only where the mask has to be RECOGNISED to be tagged.
            boolean isMask;
            if (keepMask) {
                isMask = macapkIsMaskItemId(idv);
            } else {
                if (rippleMaskById ? macapkIsMaskItemId(idv)
                                   : (idv != null && idv.contains("mask"))) continue;
                isMask = false;
            }
            android.graphics.drawable.Drawable layer = macapkInflateItemDrawable(item, theme);
            if (layer == null) continue;
            int idx = ld.addLayer(layer);
            if (isMask) ld.setId(idx, android.graphics.drawable.RippleDrawable.ID_MASK);
            else if (MACAPK_LAYER_ITEM_IDS) {
                int rid = macapkLayerItemId(idv);
                if (rid != 0) ld.setId(idx, rid);
            }
            // PER-SIDE setters, not the bulk setLayerInset()/setLayerInsetRelative().
            // Our bulk setters mirror AOSP's byte for byte, which means each one
            // CLOBBERS the other pair back to its default (setLayerInset resets
            // start/end to INSET_UNDEFINED; setLayerInsetRelative resets left/right
            // to 0), so calling both for one item silently loses whichever ran first.
            // AOSP's updateLayerFromTypedArray treats all six as independent fields.
            ld.setLayerInsetLeft(idx,   macapkDimAttr(item, "left", 0, theme));
            ld.setLayerInsetTop(idx,    macapkDimAttr(item, "top", 0, theme));
            ld.setLayerInsetRight(idx,  macapkDimAttr(item, "right", 0, theme));
            ld.setLayerInsetBottom(idx, macapkDimAttr(item, "bottom", 0, theme));
            ld.setLayerInsetStart(idx,  macapkDimAttr(item, "start",
                android.graphics.drawable.LayerDrawable.INSET_UNDEFINED, theme));
            ld.setLayerInsetEnd(idx,    macapkDimAttr(item, "end",
                android.graphics.drawable.LayerDrawable.INSET_UNDEFINED, theme));
            // width/height/gravity were NEVER READ. LayerDrawable itself was always
            // correct -- it matches AOSP's updateLayerBoundsInternal/resolveGravity
            // exactly -- but with these attributes never reaching the ChildDrawable,
            // every layer kept the defaults (width=height=-1, gravity=NO_GRAVITY),
            // and resolveGravity's own correct AOSP rule ("an axis with no gravity
            // bit and no explicit size fills") forced FILL on every layer. That is
            // why an 80dp centred logo rendered full-bleed: the bug was in the
            // INFLATER, not the drawable.
            ld.setLayerWidth(idx,  macapkDimAttr(item, "width",  -1, theme));
            ld.setLayerHeight(idx, macapkDimAttr(item, "height", -1, theme));
            String gv = item.getAttributeNS(MACAPK_ANDROID_NS, "gravity");
            if (gv != null && !gv.isEmpty()) {
                ld.setLayerGravity(idx, macapkParseGravity(gv));
            }
        }
    }

    // v1.0.423: build a REAL StateListDrawable (each <item> with its parsed
    // state set) instead of collapsing the selector to one static child, so
    // pressed/checked/disabled visuals swap on state. Paired with
    // StateListDrawable.draw (now delegates to the current-state child) + the
    // View-side setState plumbing.
    private android.graphics.drawable.Drawable macapkInflateSelector(org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        int count = 0;
        org.w3c.dom.NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (!(n instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element item = (org.w3c.dom.Element) n;
            if (!"item".equals(macapkLocalTag(item))) continue;
            android.graphics.drawable.Drawable d = macapkInflateItemDrawable(item, theme);
            if (d == null) continue;
            sld.addState(macapkParseStateSet(item), d);
            count++;
        }
        return count > 0 ? sld : null;
    }
    /** Parse android:state_X="true|false" to the framework attr ids the View's
     *  getDrawableState emits (positive = must be present, negative = absent). */
    private int[] macapkParseStateSet(org.w3c.dom.Element item) {
        // Delegate the name->id lookup to ColorStateList.stateAttrId, which
        // already carries all 22 public framework state attrs (verified against
        // public-final.xml). This method used to hardcode EIGHT of them, and a
        // dropped state is worse than a missing one: StateListDrawable is
        // first-match-wins, so an <item> whose only qualifier we failed to parse
        // becomes an UNCONDITIONAL match and PROMOTES itself to the default.
        //
        // Measured on the corpus: of the six selectors using state_hovered, four
        // are visible bugs (Duolingo's btn_circle_grey and btn_select_vector_style,
        // Google Calculator's gm3_fab_branded_icon_{dark,light}) -- in each the
        // hovered item sorts BEFORE the default, so the hover artwork won at rest.
        java.util.ArrayList<Integer> st = new java.util.ArrayList<>();
        org.w3c.dom.NamedNodeMap attrs = item.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node n = attrs.item(i);
            // Namespace-check preserves the old getAttributeNS-only semantics:
            // a same-named attr in an app namespace must not be picked up.
            // An APP-namespace state (Material's app:state_with_icon,
            // app:state_error, app:state_indeterminate) is a state too: AOSP
            // StateListDrawable.extractStateSet keeps every attribute by its id
            // whatever the namespace, and ColorStateList here already resolves
            // app states by name. Skipping it made the item UNCONDITIONAL --
            // the promote-to-default failure described above. Measured:
            // mtrl_switch_thumb's <item app:state_with_icon="true"
            // drawable=mtrl_switch_thumb_checked> sorts before the unchecked item,
            // so every OFF MaterialSwitch drew the big checked thumb (ytdlnis,
            // issue #5, 2026-10-19). MACAPK_SELECTOR_APP_STATES=0 restores the skip.
            boolean appNs = MACAPK_SELECTOR_APP_STATES
                && "http://schemas.android.com/apk/res-auto".equals(n.getNamespaceURI());
            if (!MACAPK_ANDROID_NS.equals(n.getNamespaceURI()) && !appNs) continue;
            String local = n.getLocalName();
            if (local == null || !local.startsWith("state_")) continue;
            int id = ColorStateList.stateAttrId(this, local);
            if (id == 0) continue;
            st.add("true".equals(n.getNodeValue()) ? id : -id);
        }
        int[] out = new int[st.size()];
        for (int i = 0; i < out.length; i++) out[i] = st.get(i);
        return out;
    }

    /** Resolve an <item>'s drawable: an `android:drawable="@.../x"` reference,
     *  or an inline child element (<shape>, <vector>, ...). */
    /** Synthesize a FRAMEWORK drawable we ship no asset for.
     *
     *  WHY THIS EXISTS (v1.0.869). A theme's `buttonStyle` delegation now
     *  resolves to a framework button style (v1.0.858/867), but those styles'
     *  single most visible contribution is `background=@drawable/btn_default*`,
     *  which is a framework asset. The standing rule was "serve framework style
     *  GEOMETRY only, never background", because handing back a dangling
     *  `@android:drawable/...` would replace an app's own working background
     *  with NOTHING. That rule is satisfied differently here: we return a REAL
     *  Drawable, so nothing dangles.
     *
     *  MEASURED victims: bobball's six menu buttons and anuto's two tutorial
     *  buttons render as bare text -- no face, no height -- against an emulator
     *  that draws proper raised buttons.
     *
     *  Only called when the APP's own resource table has no such name, so an
     *  app shipping its own `btn_default` is untouched.
     *
     *  v1.0.1649 (cluster D2): a name that is not listed no longer answers
     *  null; it loads from the platform resource table
     *  (macapkFrameworkTableDrawable). The listed rows still answer FIRST. */
    /** Public entry to the framework-drawable synthesizer, for callers holding a
     *  `@android:drawable/<name>` string rather than an id (LayoutInflater's
     *  applyBackground). A framework drawable has no id to resolve -- the app's
     *  table has no such name -- so an id-based route cannot reach it. */
    public android.graphics.drawable.Drawable macapkFrameworkDrawableByName(String name) {
        return macapkFrameworkDrawable(name, null);
    }

    /** Theme-aware sibling: a platform drawable's `?attr` content (a ripple's
     *  `?attr/colorControlHighlight`, a tint) resolves against `theme` rather
     *  than the static application theme. Callers that hold the view's Theme
     *  (LayoutInflater.applyBackground has it as `bgTheme`) should prefer this. */
    public android.graphics.drawable.Drawable macapkFrameworkDrawableByName(String name,
                                                                             Theme theme) {
        return macapkFrameworkDrawable(name, theme);
    }

    /** The CHILD of `@android:drawable/edit_text_material` -- the Material
     *  EditText underline, i.e. the two `textfield_*_mtrl_alpha` 9-patches and
     *  the selector that picks between them, collapsed into one stateful
     *  Drawable. See the `edit_text_material` case in macapkFrameworkDrawable for
     *  the AOSP XML this mirrors and for where every number below comes from.
     *
     *  Why a class and not a StateListDrawable over two GradientDrawables: the
     *  9-patch reports `getPadding() = L0 T0 R0 B8` while drawing its bar at the
     *  very BOTTOM of its box. Padding and geometry are therefore INDEPENDENT
     *  here -- the text sits 4dp above a bar that is itself 7dp above the view's
     *  bottom edge -- and no composition of insets can express that, because an
     *  InsetDrawable's bottom inset moves the bar and the padding together. It
     *  is ~40 lines to reproduce the decoded bytes exactly and nothing about it
     *  dangles.
     *
     *  All dimensions are the xhdpi asset's, expressed in dp and re-scaled, so
     *  this is correct at any density rather than only at the one it was read
     *  at: content 12.5x11dp, bar 2dp focused / 1dp not, padding 4dp bottom. */
    private static final class MacapkEditTextBar extends android.graphics.drawable.Drawable {
        // android.R.attr ids, frozen for binary compatibility (core/res/res/
        // values/public-final.xml). View.onCreateDrawableState emits exactly
        // these ints into the state array, and View.STATE_* are private there.
        private static final int STATE_FOCUSED = 0x0101009c;
        private static final int STATE_ENABLED = 0x0101009e;
        private static final int STATE_PRESSED = 0x010100a7;

        private final float d;
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private int alpha = 255;

        MacapkEditTextBar(float density) { this.d = density; }

        /** AOSP's selector, read top-down: disabled -> normal art; NOT pressed
         *  and NOT focused -> normal art; otherwise (so: pressed or focused, and
         *  enabled) -> activated art. `state_enabled` absent from the array means
         *  the view is disabled, which is how View.onCreateDrawableState encodes
         *  it (it appends STATE_ENABLED only when isEnabled()). */
        private boolean activated() {
            final int[] s = getState();
            if (s == null || s.length == 0) return false;
            boolean enabled = false, hot = false;
            for (int v : s) {
                if (v == STATE_ENABLED) enabled = true;
                else if (v == STATE_FOCUSED || v == STATE_PRESSED) hot = true;
            }
            return enabled && hot;
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            if (b == null || b.isEmpty()) return;
            final boolean on = activated();
            // 2dp activated / 1dp not == the decoded 4px / 2px visible faces at
            // d=2. Never let rounding erase the line on a low-density display.
            final int h = Math.max(1, Math.round((on ? 2f : 1f) * d));
            // ?attr/colorControlActivated / ?attr/colorControlNormal, resolved at
            // DRAW rather than at construction: a background can be built during
            // early inflation, before the activity theme is applied, and a colour
            // baked then would be the wrong one for the life of the view.
            // Fallbacks are AOSP's own light-theme values -- colors_material.xml:42
            // accent_material_light = material_deep_teal_500 = #009688, and
            // secondary_text_material_light = #8a000000 (Theme.Material.Light's
            // colorControlNormal is ?android:textColorSecondary).
            final int c = on ? macapkThemeColor("colorControlActivated", 0xFF009688)
                             : macapkThemeColor("colorControlNormal",    0x8A000000);
            // Drawable.setAlpha SCALES the drawable's own alpha, it does not
            // replace it -- colorControlNormal is #8a000000, i.e. already 54%
            // opaque, and a caller passing 255 must not promote it to opaque.
            final int a0 = c >>> 24;
            final int a  = (alpha >= 255) ? a0 : (a0 * alpha) / 255;
            paint.setColor((a << 24) | (c & 0x00FFFFFF));
            canvas.drawRect(b.left, b.bottom - h, b.right, b.bottom, paint);
        }

        // content=25x22 at d=2 -> 12.5 x 11 dp. The intrinsic HEIGHT is the whole
        // box including the 18px of transparency above the bar, which is what
        // getMinimumHeight (and therefore View's suggested minimum) reads.
        @Override public int getIntrinsicWidth()  { return Math.round(12.5f * d); }
        @Override public int getIntrinsicHeight() { return Math.round(11f * d); }

        /** The decoded `getPadding() = L0 T0 R0 B8` (4dp). This is the gap
         *  between the text baseline box and the underline. */
        @Override public boolean getPadding(android.graphics.Rect padding) {
            if (padding == null) return false;
            padding.set(0, 0, 0, Math.round(4f * d));
            return true;
        }

        // MUST answer true, or View.drawableStateChanged / setBackgroundDrawable
        // skip setState entirely and the bar is stuck on the unfocused art
        // forever (see the note on Drawable.isStateful).
        @Override public boolean isStateful() { return true; }
        // public, matching this tree's Drawable (AOSP declares it protected; ours
        // is public and an override may not narrow access).
        @Override public boolean onStateChange(int[] state) { invalidateSelf(); return true; }

        @Override public void setAlpha(int a) { this.alpha = a; invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** The Material CHECKBOX / RADIO indicator, synthesized.
     *
     *  WHY THIS EXISTS. `Widget.Material.CompoundButton.CheckBox` sets
     *  `button = ?attr/listChoiceIndicatorMultiple`, which themes_material.xml
     *  points at `@drawable/btn_check_material_anim` -- a framework
     *  ANIMATED-VECTOR we do not ship. ResourceIndex.macapkFrameworkStyleDef
     *  therefore answers with the APP'S bundled AppCompat twin
     *  (`@drawable/abc_btn_check_material`), which works for the ~30 corpus APKs
     *  that use AppCompat and resolves to id 0 for every app that does not. Its
     *  own comment says so: "an app that does NOT bundle it resolves to id 0
     *  downstream and nothing is installed". That was a deliberate no-regression
     *  choice, not a claim the result was right -- a plain framework-themed app
     *  got NO indicator at all.
     *
     *  MEASURED VICTIM: Vector Pinball (com.dozingcatsoftware.bouncy, theme
     *  `Theme.VPTheme`, no AppCompat). Its "Unlimited Balls" CheckBox measures
     *  186x38 -- exactly its text, with no room for a box -- against an emulator
     *  drawing a checkbox beside the label. Found 2026-08-28 by the fleet
     *  emudiff INK IMBALANCE column, then localised with
     *  `MACAPK_TRACE_DEFSTYLE_APPLY=1`, which printed
     *  `Widget.Material.CompoundButton.CheckBox.button = ?attr/listChoiceIndicatorMultiple`.
     *
     *  GEOMETRY IS EXACT, decoded from the AOSP vectors (android14-release):
     *    core/res/res/drawable/btn_checkbox_unchecked_mtrl.xml
     *      32dp wide/high over a 48-unit viewport; group translate (24,24),
     *      outer scale 0.2, inner `box_dilate` scale 7.5 => net 1.5. The box
     *      path runs -9..+9 with a 2-unit corner radius and a -7..+7 hole, so
     *      at 32dp: an 18dp rounded square, 2dp stroke, 2dp corner radius,
     *      centred in a 32dp box.
     *    core/res/res/drawable/btn_radio_off_mtrl.xml
     *      32dp over a 32-unit viewport, centred (16,16); ring path radius 9
     *      with a 2-unit wall, dot path radius 5 => an 18dp circle, 2dp stroke,
     *      and a 10dp dot when checked.
     *  TINT IS EXACT, from core/res/res/color/control_checkable_material.xml:
     *      state_enabled=false -> ?attr/colorControlNormal at ?attr/disabledAlpha
     *      state_checked=true  -> ?attr/colorControlActivated
     *      otherwise           -> ?attr/colorControlNormal
     *
     *  APPROXIMATED, and named as such: the CHECKED artwork. AOSP fills the
     *  rounded square and knocks a tick out of it with a vector path; we fill the
     *  same square and stroke a two-segment tick over it in the window
     *  background colour. The box, its size, its radius and its colour are the
     *  spec; the tick's exact curve is not, and no animation is reproduced (the
     *  real asset is an animated-selector with two transition drawables).
     *
     *  Deliberately NOT registered as `btn_check_material_anim`: the style table
     *  keeps pointing at the AppCompat twin, which still WINS wherever the app
     *  bundles it (real art + its real tint). This is reached only from
     *  LayoutInflater.macapkInstallButtonDrawable's id==0 fallback, so an
     *  AppCompat app's rendering is byte-identical to before. */
    static final class MacapkCompoundButtonDrawable
            extends android.graphics.drawable.Drawable {
        // android.R.attr ids, frozen in core/res/res/values/public-final.xml.
        // View.onCreateDrawableState emits exactly these ints.
        private static final int STATE_ENABLED = 0x0101009e;
        private static final int STATE_CHECKED = 0x010100a0;

        private final float d;
        private final boolean radio;
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private int alpha = 255;

        /** The theme the indicator was requested for (the VIEW's, e.g. a
         *  dialog's overlay), or null for the application theme. AOSP tints
         *  control_checkable_material with the view theme's ?colorControlNormal /
         *  ?colorControlActivated; reading the APPLICATION theme instead drew the
         *  unchecked ring in Privacy Friendly Notes' ListPreference dialog white on white
         *  (AppTheme sets colorControlNormal=@color/white for its dark toolbar,
         *  the dialog overlay sets it to the brand blue). */
        private final Theme theme;

        MacapkCompoundButtonDrawable(float density, boolean radio) {
            this(density, radio, null);
        }
        MacapkCompoundButtonDrawable(float density, boolean radio, Theme theme) {
            this.d = density;
            this.radio = radio;
            this.theme = theme;
            paint.setAntiAlias(true);
        }
        private int macapkColor(String attr, int fallback) {
            if (theme != null) {
                try { return theme.resolveAttrColor(attr, fallback); } catch (Throwable ignored) {}
            }
            return Resources.macapkThemeColor(attr, fallback);
        }

        /** `state_enabled` ABSENT means disabled -- View.onCreateDrawableState
         *  appends it only when isEnabled(), so its absence is the encoding. */
        private boolean checked() {
            final int[] st = getState();
            if (st == null) return false;
            for (int v : st) if (v == STATE_CHECKED) return true;
            return false;
        }
        private boolean enabled() {
            final int[] st = getState();
            if (st == null || st.length == 0) return true;
            for (int v : st) if (v == STATE_ENABLED) return true;
            return false;
        }

        @Override public boolean isStateful() { return true; }
        // AOSP declares this `protected`; THIS tree's Drawable declares it
        // `public` (see Drawable.java's note on why), and Java forbids reducing
        // visibility in an override.
        @Override public boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;   // our appearance always depends on checked/enabled
        }

        // 32dp, the shipped asset's own width/height -- this is the number that
        // makes the row grow to a real control height instead of hugging text.
        @Override public int getIntrinsicWidth()  { return Math.round(32f * d); }
        @Override public int getIntrinsicHeight() { return Math.round(32f * d); }

        /** Resolved at DRAW, not construction: an indicator can be built during
         *  early inflation, before the activity theme is applied, and a colour
         *  baked then would be wrong for the life of the view (the same reasoning
         *  MacapkEditTextBar records). */
        private int tint() {
            final int c = checked()
                ? macapkColor("colorControlActivated", 0xFF6200EE)
                : macapkColor("colorControlNormal", 0x8A000000);
            if (enabled()) return c;
            // ?attr/disabledAlpha; AOSP's material default is 0.5 (themes_material
            // .xml). Scale the alpha channel rather than blending toward a
            // background we do not know.
            final int a = (int) (((c >>> 24) & 0xFF) * 0.5f);
            return (a << 24) | (c & 0x00FFFFFF);
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            if (b == null || b.isEmpty()) return;
            final float cx = b.exactCenterX(), cy = b.exactCenterY();
            final float stroke = 2f * d;
            final int col = tint();
            paint.setColor(col);
            paint.setAlpha(((col >>> 24) & 0xFF) * alpha / 255);
            if (radio) {
                final float rOuter = 9f * d - stroke / 2f;   // 18dp circle, 2dp wall
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                canvas.drawCircle(cx, cy, rOuter, paint);
                if (checked()) {
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, 5f * d, paint);   // 10dp dot
                }
                return;
            }
            // 18dp rounded square, 2dp corner radius.
            final float half = 9f * d;
            final float r = 2f * d;
            final android.graphics.RectF box = new android.graphics.RectF(
                cx - half + stroke / 2f, cy - half + stroke / 2f,
                cx + half - stroke / 2f, cy + half - stroke / 2f);
            if (!checked()) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                canvas.drawRoundRect(box, r, r, paint);
                return;
            }
            paint.setStyle(android.graphics.Paint.Style.FILL);
            canvas.drawRoundRect(box, r, r, paint);
            // The tick, knocked out in the window background colour. APPROXIMATE
            // (see the class comment): AOSP carves it out of the filled square
            // with a vector path.
            paint.setColor(macapkColor("colorBackground", 0xFFFFFFFF));
            paint.setAlpha(alpha);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(2f * d);
            paint.setStrokeCap(android.graphics.Paint.Cap.SQUARE);
            final android.graphics.Path tick = new android.graphics.Path();
            tick.moveTo(cx - 4.5f * d, cy);
            tick.lineTo(cx - 1.5f * d, cy + 3.5f * d);
            tick.lineTo(cx + 4.5f * d, cy - 3.0f * d);
            canvas.drawPath(tick, paint);
        }

        @Override public void setAlpha(int a) { this.alpha = a; invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private android.graphics.drawable.Drawable macapkFrameworkDrawable(String name, Theme theme) {
        return macapkFrameworkDrawable(name, theme, true);
    }

    /** `useTable=false` answers from the hand rows alone: that is the
     *  historical contract of the APP-reference fallback in
     *  macapkResolveDrawableRef (an `@drawable/X` the app table cannot name),
     *  which no platform table lookup should widen. */
    private android.graphics.drawable.Drawable macapkFrameworkDrawable(String name, Theme theme,
                                                                     boolean useTable) {
        if (name == null) return null;
        float d = android.util.DisplayMetrics.macapkDeviceDensity();
        try {
            android.util.DisplayMetrics dm = getDisplayMetrics();
            if (dm != null && dm.density > 0f) d = dm.density;
        } catch (Throwable ignored) {}
        switch (name) {
            // MATERIAL -- EXACT, from AOSP XML (fetched 2026-08-03):
            //   drawable/btn_default_material.xml
            //     <ripple color=?attr/colorControlHighlight>
            //       <item drawable="@drawable/btn_default_mtrl_shape"/>
            //   drawable/btn_default_mtrl_shape.xml
            //     <inset insetLeft/Right=@dimen/button_inset_horizontal_material
            //            insetTop/Bottom=@dimen/button_inset_vertical_material>
            //       <shape rectangle tint="?attr/colorButtonNormal">
            //         <corners radius="?attr/buttonCornerRadius"/>
            //         <solid color="@color/white"/>
            // with button_inset_horizontal_material = @dimen/control_inset_material
            // = 4dp, button_inset_vertical_material = 6dp, and buttonCornerRadius
            // = @dimen/control_corner_material = 2dp (themes_material.xml:109/487).
            // The white solid tinted by ?colorButtonNormal resolves to that
            // colour, which our palette now serves (#d6d7d7 / #5a595b, v1.0.866).
            // THE EDITTEXT UNDERLINE (ATTRSTYLE lane, 2026-08-13). Every
            // AppCompat app's field background chain terminates here:
            //   Base.Widget.AppCompat.EditText  android:background=?editTextBackground
            //     -> Base.V22.Theme.AppCompat   editTextBackground=?android:editTextBackground
            //       -> themes_material.xml:93 (and :465 for .Light -- SAME value)
            //            <item name="editTextBackground">@drawable/edit_text_material</item>
            // and we answered NOTHING for that name, so the field had no
            // background at all: no underline, and -- the part that moves layout
            // -- none of the 42px of padding the real drawable reports.
            //
            // AOSP drawable/edit_text_material.xml, quoted verbatim:
            //   <inset android:insetLeft/insetRight="@dimen/edit_text_inset_horizontal_material"
            //          android:insetTop="@dimen/edit_text_inset_top_material"
            //          android:insetBottom="@dimen/edit_text_inset_bottom_material">
            //     <selector>
            //       <item android:state_enabled="false">
            //         <nine-patch src="@drawable/textfield_default_mtrl_alpha"   tint="?attr/colorControlNormal"/>
            //       <item android:state_pressed="false" android:state_focused="false">
            //         <nine-patch src="@drawable/textfield_default_mtrl_alpha"   tint="?attr/colorControlNormal"/>
            //       <item>
            //         <nine-patch src="@drawable/textfield_activated_mtrl_alpha" tint="?attr/colorControlActivated"/>
            // with dimens_material.xml:124-126
            //   edit_text_inset_horizontal_material  4dp
            //   edit_text_inset_top_material        10dp
            //   edit_text_inset_bottom_material      7dp
            //
            // The two 9-patches have no XML spec, so their geometry is DECODED
            // from the shipped SDK asset rather than measured off a screenshot,
            // exactly as btn_default below now is (`debug/ninepatch.py --sdk
            // textfield_activated_mtrl_alpha`, drawable-xhdpi, d=2):
            //   activated: file=27x24 content=25x22  transparent T18 B0
            //              visible face 25x4   getPadding L0 T0 R0 B8
            //   default:   file=27x24 content=25x22  transparent T18 B2
            //              visible face 25x2   getPadding L0 T0 R0 B8
            // i.e. a bar at the BOTTOM of an 11dp box, 2dp thick when focused and
            // 1dp when not, with 4dp of padding under it. MacapkEditTextBar below
            // reproduces exactly that; the InsetDrawable here reproduces the
            // <inset>. Total reported padding = L8 T20 R8 B22 at d=2, of which the
            // 42px vertical is the layout-moving part.
            //
            // ⚠ THIS CHANGES EVERY EditText IN EVERY APP, which is why it is under
            // MACAPK_ATTRSTYLE: the A/B has to come from one binary. It is also
            // why it is a REAL Drawable and not a `@android:drawable/...` string
            // handed back into the resolver -- this table's addition rule (see
            // ResourceIndex.macapkFrameworkStyleDef) is that a row pointing at a
            // framework asset we do not ship is worse than no row.
            case "edit_text_material": {
                if (!MACAPK_ATTRSTYLE) return null;
                MacapkEditTextBar bar = new MacapkEditTextBar(d);
                return new android.graphics.drawable.InsetDrawable(bar,
                    Math.round(4f * d), Math.round(10f * d),
                    Math.round(4f * d), Math.round(7f * d));
            }
            // See MacapkCompoundButtonDrawable's header for the decoded AOSP
            // geometry and what is exact vs approximated. Reached only from
            // LayoutInflater's id==0 fallback, so an AppCompat app that bundles
            // the real abc_* asset is unaffected.
            case "btn_check_material":
                return new MacapkCompoundButtonDrawable(d, false, theme);
            case "btn_radio_material":
                return new MacapkCompoundButtonDrawable(d, true, theme);
            case "btn_default_material": {
                android.graphics.drawable.GradientDrawable s =
                    new android.graphics.drawable.GradientDrawable();
                s.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                s.setColor(macapkThemeColor("colorButtonNormal", 0xFFD6D7D7));
                s.setCornerRadius(2f * d);
                int ih = Math.round(4f * d), iv = Math.round(6f * d);
                return new android.graphics.drawable.InsetDrawable(s, ih, iv, ih, iv);
            }
            // CLASSIC. The colours are still MEASURED off the reference AVD
            // rendering bobball (2026-08-03) -- a vertical gradient
            // #e6e6e6 -> #c7c7c7 with a #424242 hairline border -- but the
            // GEOMETRY is no longer approximated. v1.0.930 reads it from the
            // SHIPPED ASSET instead of from a screenshot:
            //   android-emulator/android-sdk/platforms/android-30/data/res/
            //       drawable-xhdpi/btn_default_normal.9.png
            // decoding its 9-patch guide border gives
            //   file=50x98  content=48x96   TRANSPARENT margin L6 T1 R6 B8
            //   getPadding() = L21 T13 R23 B23
            // and the content box is 24x48 **dp** at every density (mdpi 24x48,
            // hdpi 36x72, xhdpi 48x96). So the INTRINSIC height is 48dp = 96px at
            // d=2, of which only 87px is visible face; the rest is the asset's
            // own transparent margin, which is what separates two stacked buttons.
            //
            // ⚠ WHAT WAS WRONG: `setSize(-1, round(40f*d))` = 80px set the
            // intrinsic to the VISIBLE FACE height, and returned the shape bare.
            // AOSP's intrinsic is the whole content box INCLUDING the transparent
            // margin. Measured on bobball's six menu buttons: the emulator's pitch
            // (= View height, since Widget.Button sets no minHeight so all of it
            // comes from this drawable) is exactly 96px with a 14px gap between
            // faces; ours was exactly 80px with NO gap. 80 is not an approximation,
            // it is round(40f*d) bit-for-bit -- the code was the cause.
            //
            // Wrapping in an InsetDrawable is what both sibling cases in this same
            // switch already do (btn_default_material above, btn_default_holo_dark
            // below); classic was the only one returning bare.
            // InsetDrawable.getIntrinsicHeight grows the child by the fixed dims,
            // so 87 + 1 + 8 = 96.
            // ⚠ MARGIN CORRECTED v1.0.936. The v1.0.930 comment above quoted
            // "TRANSPARENT margin L6 T1 R6 B8" and "87px face", and the code
            // below implemented exactly that. `debug/ninepatch.py --sdk
            // btn_default_normal` -- which DECODES THE SHIPPED ASSET rather than
            // recalling it -- reports **L5 T1 R5 B7, visible face 38x88**. Both
            // readings give the same 48x96 intrinsic (88+1+7 == 87+1+8 == 96),
            // which is why the v930 fix worked and the discrepancy survived: the
            // number that mattered then was the TOTAL. The face and the side
            // insets were still 1px out, so the drawn button was 1px narrow on
            // each side and 1px short. The tool wins; it reads the bytes.
            // ⚠ v1.0.1650 (MACAPK_FW_BTN_TABLE, default ON): btn_default and
            // btn_default_small now load the REAL platform selector + 9-patches
            // from the table FIRST, and the synthesized rows below are only the
            // fallback when the table cannot answer. The rows reproduced the
            // asset's transparent MARGIN and intrinsic height but never its
            // CONTENT PADDING: getPadding() answered the margin (L5 T1 R5 B7 /
            // L2 T0 R2 B5) where the 9-patch reports L21 T13 R23 B23 /
            // L13 T8 R12 B17 (debug/ninepatch.py on the android-34 xhdpi asset,
            // and the table route offline, agree). NinePatchDrawable.getPadding
            // (android14-release :270-277) returns the chunk's padding and
            // View.setBackgroundDrawable applies it, so every classic Button's
            // label touched its face edges, sat 2px low, and a wrap_content
            // button came out 16-21px narrow (anuto's header: Menu face 67 vs
            // 83, Next Wave 132 vs 148;
            // docs/fleet-games-pk2-mindustry-anuto_2026-09-26.md F2). The table
            // also carries the real state rows (pressed/focused/disabled) and
            // the 40/48px intrinsic WIDTH the rows lacked (-1). This applies
            // whatever `useTable` says: these names were always answered by the
            // rows, so the table changes WHAT they answer, never WHETHER, and
            // useTable=false's contract is only that the table never WIDENS the
            // set of names the app-reference fallback serves.
            // The face COLOUR: the classic assets are GREY+ALPHA PNGs, and
            // BitmapFactory's getRGB path brightens that colour type by the sRGB
            // transfer (sample 232 -> 245), so until the BitmapFactory grey+alpha
            // fix lands the table face reads ~10-23 levels LIGHTER than the AVD
            // (the rows below read ~10 darker). Measured offline, with the fix
            // the table face column matches the AVD's to within 1 level
            // (docs/drawable-color-filter-ring-sysres_2026-09-26.md).
            case "btn_default": {
                if (MACAPK_FW_BTN_TABLE) {
                    android.graphics.drawable.Drawable t = macapkFrameworkTableDrawable(name, theme);
                    if (t != null) return t;
                }
                android.graphics.drawable.GradientDrawable s =
                    new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xFFE6E6E6, 0xFFC7C7C7});
                s.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                s.setCornerRadius(2f * d);
                s.setStroke(Math.max(1, Math.round(d / 2f)), 0xFF424242);
                s.setSize(-1, Math.round(44f * d));     // 88px face @ d=2 (96 - T1 - B7)
                return new android.graphics.drawable.InsetDrawable(
                    s, Math.round(2.5f * d), Math.round(0.5f * d),
                       Math.round(2.5f * d), Math.round(3.5f * d));
            }
            // CLASSIC SMALL. `Widget.Button.Small` sets ONLY
            // `background=@drawable/btn_default_small`, so without this case the
            // whole style was a geometric no-op and a small classic button got
            // no face at all -- ResourceIndex's buttonStyleSmall row said so in
            // its own comment ("a framework 9-patch we do not ship ... Drawing a
            // synthesized default button face is a separate decision").
            //
            // Same construction as `btn_default` and the same source of truth:
            // `debug/ninepatch.py --sdk btn_default_small` decodes
            // drawable-xhdpi/btn_default_small_normal.9.png as
            //     file=42x74  content=40x72 (20x36dp)
            //     TRANSPARENT margin L2 T0 R2 B5   visible face 36x67
            // so the intrinsic is 36dp tall of which 33.5dp is face. Colours are
            // shared with btn_default deliberately: it is the same classic button
            // art at a smaller size, and inventing a second palette would be a
            // guess where this one was measured off the reference AVD.
            case "btn_default_small": {
                // Table first under MACAPK_FW_BTN_TABLE; see btn_default above.
                if (MACAPK_FW_BTN_TABLE) {
                    android.graphics.drawable.Drawable t = macapkFrameworkTableDrawable(name, theme);
                    if (t != null) return t;
                }
                android.graphics.drawable.GradientDrawable s =
                    new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0xFFE6E6E6, 0xFFC7C7C7});
                s.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                s.setCornerRadius(2f * d);
                s.setStroke(Math.max(1, Math.round(d / 2f)), 0xFF424242);
                s.setSize(-1, Math.round(33.5f * d));   // 67px face @ d=2 (72 - T0 - B5)
                return new android.graphics.drawable.InsetDrawable(
                    s, Math.round(1f * d), 0,
                       Math.round(1f * d), Math.round(2.5f * d));
            }
            // HOLO -- MEASURED 2026-08-08, and flagged as such exactly like the
            // classic case above. The note here used to say "no app in the corpus
            // renders one on the reference AVD, so there is nothing to measure ...
            // add it the day a Holo app gives us a reference". frozenbubble is
            // that app: its HomeScreen declares no theme and targets SDK 12, so
            // selectSystemTheme gives it Theme.Holo, and the AVD draws this
            // background for all four of its menu buttons.
            //
            // btn_default_normal_holo_dark.9.png is a 9-patch, so there is no XML
            // spec to mirror and the values below were SOLVED off the reference
            // rather than guessed. Method: least squares of `emu = m*art + c` per
            // channel over ~52k px inside the three unfocused faces, using our own
            // render as the un-occluded art (the splash bitmap is pixel-aligned
            // between the two captures). All three channels returned m = 0.2559 to
            // four decimals, which is what says the face is ONE flat translucent
            // colour and not a gradient:
            //     alpha = 1 - 0.2559 = 0.744 (190/255)
            //     rgb   = (40.6, 46.9, 52.3) -> #292F34
            // Cross-check: composited over the white part of the art (254) the
            // model predicts (95.5,100.0,103.7); the emulator reads (95,99,103) =
            // #5f6367, which imgdiff independently names as that region's dominant
            // emulator colour.
            //
            // The 4dp inset is geometry, not colour: the view is 324x96
            // (setWidth(36*9) and Widget.Holo.Button's minHeight 48dip) and the
            // fully covered face core is 308x80, i.e. 324-16 by 96-16 at density 2.
            //
            // NOT modelled, on purpose: the 1px lighter top bevel (~(77,82,84) at
            // the same alpha, ~1240px across four buttons) -- GradientDrawable
            // .setStroke draws all four sides, so faking it would be worse than
            // omitting it -- and the FOCUSED state (holo_blue_light #33B5E5 at 40%
            // over an opaque #32ACD5 2dp border), which needs a StateListDrawable
            // and focus plumbing rather than a colour.
            //
            // DARK ONLY: the light asset was never measured, and ResourceIndex
            // returns null for light-Holo so nothing can reach a name we cannot
            // build.
            //
            // ⚠ v1.0.1650 (MACAPK_FW_HOLO_BTN_TABLE, default ON): the REAL
            // platform selector is served from the table FIRST. AOSP
            // core/res/res/drawable/btn_default_holo_dark.xml (android14-release)
            // is a selector -- window-unfocused normal/disabled, pressed,
            // focused & enabled -> btn_default_focused_holo_dark, enabled ->
            // normal, focused -> disabled_focused, default -> disabled -- and the
            // single-state row below can never show the focused face. MEASURED
            // victim: Frozen Bubble requestFocus()es its Arcade button and the
            // AVD draws it blue; ours stayed grey
            // (docs/fleet-misc-rocks-frozen-sgt-anarch_2026-09-26.md F5).
            // Offline, the table's NORMAL face over white reads (96,101,105)
            // against this row's (96,100,104) and the AVD's (95,99,103), so the
            // least-squares match below is kept by the real asset; its focused
            // face reads (173,225,245). Padding/intrinsic become the 9-patch's
            // (24,16,24,16 / 52x64) instead of the 4dp inset's 8,8,8,8 / none.
            case "btn_default_holo_dark": {
                if (MACAPK_FW_HOLO_BTN_TABLE) {
                    android.graphics.drawable.Drawable t = macapkFrameworkTableDrawable(name, theme);
                    if (t != null) return t;
                }
                android.graphics.drawable.GradientDrawable s =
                    new android.graphics.drawable.GradientDrawable();
                s.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                s.setColor(0xBE292F34);
                s.setCornerRadius(1f * d);
                int in = Math.round(4f * d);
                return new android.graphics.drawable.InsetDrawable(s, in, in, in, in);
            }
            // Every other name loads from the REAL platform table (cluster D2).
            default: return useTable ? macapkFrameworkTableDrawable(name, theme) : null;
        }
    }

    /** Cluster D2 (v1.0.1649, docs/widget-fidelity-cluster_2026-09-25.md).
     *  Default ON; `MACAPK_FW_DRAWABLE_TABLE=0` restores the hand-written list
     *  as the ONLY source of `@android:drawable/<name>` (anything unlisted then
     *  answers null again), and restores the app-table-first lookup in
     *  macapkResolveDrawableRef, for a one-binary bisect. */
    private static final boolean MACAPK_FW_DRAWABLE_TABLE =
        !"0".equals(System.getenv("MACAPK_FW_DRAWABLE_TABLE"));

    /** Default ON. `MACAPK_FW_BTN_TABLE=0` restores the synthesized
     *  btn_default / btn_default_small rows as the first (and only) answer for
     *  those two names -- the exact pre-change path. The table is also skipped
     *  when MACAPK_FW_DRAWABLE_TABLE=0 (macapkFrameworkTableDrawable answers
     *  null), and whenever it cannot answer, so the rows stay the fallback. */
    static final boolean MACAPK_FW_BTN_TABLE =
        !"0".equals(System.getenv("MACAPK_FW_BTN_TABLE"));

    /** Default ON. `MACAPK_FW_HOLO_BTN_TABLE=0` restores the single-state
     *  btn_default_holo_dark row as the first answer -- the pre-change path. */
    static final boolean MACAPK_FW_HOLO_BTN_TABLE =
        !"0".equals(System.getenv("MACAPK_FW_HOLO_BTN_TABLE"));

    /** `@android:drawable/<name>` from the platform resource table
     *  (tools/framework-res.apk), through the SAME id path a
     *  TypedArray.getDrawable of a framework id already takes: name -> 0x0108
     *  id via the platform view's name map (ResourceIndex.idOfName's "android"
     *  package branch), then getDrawable(id, theme), which serves the entry out
     *  of the framework apk (macapkFwOpenRaw) and inflates its compiled XML or
     *  decodes its bitmap / 9-patch.
     *
     *  WHY. The switch above synthesizes six names and answered null for every
     *  other one, so a framework drawable reached BY NAME (LayoutInflater's
     *  applyBackground / applyImageSrc, Button's framework buttonStyle, a
     *  `@android:drawable/` reference inside an XML drawable) silently never
     *  landed, while the same drawable reached BY ID loaded fine. MEASURED
     *  victim: NewPipe `drawer_header.xml:18`,
     *      <Button android:background="?selectableItemBackground">
     *  resolves ?selectableItemBackground -> ?android:selectableItemBackground
     *  -> @android:drawable/item_background_material (themes_material.xml:114)
     *  -> null, so the element's background never landed and tier-3
     *  buttonStyle's grey btn_default face covered the drawer header. It also
     *  covers `android:src="@android:drawable/ic_menu_close_clear_cancel"`
     *  (18 of 62 corpus APKs), a real 64x64 PNG that used to answer null.
     *
     *  The hand rows above stay FIRST on purpose: each was matched against the
     *  reference AVD (btn_default*, the Holo face, the EditText underline, the
     *  compound-button indicators), and the table path does not yet reproduce
     *  them. Measured offline: btn_default_material from the table is a WHITE,
     *  inset-less face where the hand row draws #d6d7d7 inside 4dp/6dp insets,
     *  because the compiled platform XML carries `android:tint="?0x0101042b"`
     *  (a <shape> tint our shape inflater does not read, and which our
     *  GradientDrawable would apply as a colour REPLACEMENT rather than AOSP's
     *  SRC_IN) and `@0x0105xxxx` dimension references that macapkDimAttr
     *  resolves to 0. Retiring a hand row needs those fixed and its own
     *  measured A/B.
     *
     *  A table answer that is an empty VectorDrawable (our getDrawable's
     *  last-resort stand-in for a file it could not inflate, with a fabricated
     *  24dp size) is NOT returned: the caller then keeps the "no framework
     *  drawable" answer it had, rather than trading a missing background for an
     *  invisible one that suppresses every fallback. */
    private android.graphics.drawable.Drawable macapkFrameworkTableDrawable(String name,
                                                                          Theme theme) {
        if (!MACAPK_FW_DRAWABLE_TABLE || name == null || name.isEmpty()) return null;
        int fid = getIdentifier(name, "drawable", "android");
        if (fid == 0 || (fid >>> 24) != 0x01) return null;
        android.graphics.drawable.Drawable dr;
        try { dr = getDrawable(fid, theme); }
        catch (Throwable t) { return null; }
        if (dr instanceof android.graphics.drawable.VectorDrawable vd
                && (vd.macapkUnresolvedStub || vd.macapkPaintsNothing())) {
            return null;
        }
        return dr;
    }

    private android.graphics.drawable.Drawable macapkInflateItemDrawable(org.w3c.dom.Element item, Theme theme) {
        String ref = item.getAttributeNS(MACAPK_ANDROID_NS, "drawable");
        if (ref != null && !ref.isEmpty()) return macapkResolveDrawableRef(ref, theme);
        org.w3c.dom.NodeList kids = item.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (n instanceof org.w3c.dom.Element) {
                return macapkInflateDrawableElement((org.w3c.dom.Element) n, theme);
            }
        }
        return null;
    }

    /** docs/attr-theme-threading_2026-07-31.md: theme-less convenience overload
     *  for the one external caller with no Theme to offer
     *  (MacapkResourceArray.getDrawable -- obtainTypedArray has no Theme concept
     *  in AOSP). Delegates with theme=null, byte-identical to the old behavior. */
    private android.graphics.drawable.Drawable macapkResolveDrawableRef(String ref) {
        return macapkResolveDrawableRef(ref, null);
    }
    private android.graphics.drawable.Drawable macapkResolveDrawableRef(String ref, Theme theme) {
        if (ref == null) return null;
        ref = ref.trim();
        if (ref.isEmpty()) return null;
        if (ref.startsWith("#")) {
            try { return new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.macapkParseResourceColor(ref)); }
            catch (Throwable t) { return null; }
        }
        if (ref.startsWith("?")) {
            return new android.graphics.drawable.ColorDrawable(
                macapkResolveColorString(ref, 0, theme));
        }
        if (ref.startsWith("@")) {
            String body = ref.substring(1);
            // "@0x01......": a compiled FRAMEWORK reference -- how
            // BinaryXmlParser renders a TYPE_REFERENCE, which is the only
            // spelling a framework compiled drawable's <item
            // android:drawable=...> can carry (framework XML references
            // internal ids, and the app table cannot name them). Recurse
            // straight into getDrawable so the framework loading path
            // (openRaw's 0x01 branch + the compiled-XML inflate) serves the
            // nested asset. Scoped to package 0x01: app drawable XML is
            // apktool-decoded text and never carries an id-shaped ref, so
            // an app-id "@0x7f..." here keeps its historical null.
            if (body.startsWith("0x") || body.startsWith("0X")) {
                try {
                    int hid = (int) Long.parseLong(body.substring(2), 16);
                    // v1.0.1590: any package. An app XML read from the installed
                    // apk renders its references by NAME, so a hex app id only
                    // reaches here for an id no member's table names; chasing
                    // it by id is still the device's answer.
                    if (hid != 0) return getDrawable(hid, theme);
                } catch (NumberFormatException ignored) {}
                return null;
            }
            String type = "drawable";
            String name = body;
            String pkg = null;
            int slash = body.indexOf('/');
            if (slash >= 0) {
                String left = body.substring(0, slash);
                name = body.substring(slash + 1);
                int cc = left.indexOf(':');
                if (cc >= 0) {
                    pkg = left.substring(0, cc);
                    left = left.substring(cc + 1);  // strip "android:"
                }
                if (!left.isEmpty()) type = left;
            }
            // `@android:drawable/X` names the PLATFORM's X (aapt2 compiles it to
            // a 0x01 id), never an app resource that happens to share the name,
            // so it must not reach the app-table lookup below. It used to: the
            // package was stripped and getIdentifier(X, "drawable", null) asked
            // the app first. Hand rows first, then the platform table, exactly
            // as macapkFrameworkDrawableByName serves a bare name.
            if (MACAPK_FW_DRAWABLE_TABLE && "android".equals(pkg) && "drawable".equals(type)) {
                return macapkFrameworkDrawable(name, theme);
            }
            if ("color".equals(type)) {
                int cid = getIdentifier(name, "color", null);
                return new android.graphics.drawable.ColorDrawable(cid != 0 ? getColor(cid) : 0);
            }
            // icon-lifecycle fan-out lane (2026-07-23, BUG B re-open):
            // root-cause fix -- honor the reference's OWN parsed `type`
            // FIRST. `mipmap` and `drawable` are DIFFERENT resource-id
            // namespaces in real AOSP (R.mipmap.x and R.drawable.x are
            // different ints even when x is the same name string --
            // confirmed against ResourceIndex.idOfName/public.xml, which
            // key by "type/name" and correctly keep them separate). The
            // OLD code below always checked "drawable" before the parsed
            // type, so a `@mipmap/name` reference silently preferred an
            // UNRELATED same-named drawable-type resource when one
            // existed. Mindustry ships exactly that collision: a REAL
            // asset at mipmap-xxxhdpi/ic_launcher_background.png (etc, one
            // per density) alongside a 1x1 black PNG at
            // res/drawable/ic_launcher_background.png (an AAPT2-emitted
            // filler/placeholder for the unqualified "drawable" type slot,
            // never meant to be the resolution target of the mipmap-typed
            // reference the <adaptive-icon> XML actually declares). The
            // old priority order picked the 1x1 stub, stretched it to
            // 192x192, and both the background AND foreground layers
            // rendered as solid black. Fall back to the OTHER common type
            // only when the reference's own type doesn't resolve (keeps
            // tolerating the sloppy apps this fallback chain originally
            // existed for, that reference @drawable/x for an asset really
            // typed mipmap or vice versa).
            int rid = getIdentifier(name, type, null);
            if (rid == 0 && !"mipmap".equals(type))   rid = getIdentifier(name, "mipmap", null);
            if (rid == 0 && !"drawable".equals(type)) rid = getIdentifier(name, "drawable", null);
            // FRAMEWORK drawables the app cannot possibly provide (v1.0.869).
            // Reached only when the app's own table has nothing by this name,
            // so an app that ships its own `btn_default` still wins.
            if (rid == 0) {
                android.graphics.drawable.Drawable fw = macapkFrameworkDrawable(name, theme, false);
                if (fw != null) return fw;
            }
            // Thread theme through the recursion so a nested @drawable/X (e.g. a
            // <layer-list> item referencing another drawable FILE) resolves any
            // ?attr/ colors it declares against the SAME Theme, not the static
            // application theme. docs/attr-theme-threading_2026-07-31.md.
            if (rid != 0) return getDrawable(rid, theme);  // recurse -> inner vector/shape
            return null;
        }
        return null;
    }

    /** Default ON; `MACAPK_SHAPE_SOLID_CSL=0` restores the flattened solid for a
     *  one-binary bisect. See macapkShapeSolidSelector. */
    private static final boolean MACAPK_SHAPE_SOLID_CSL =
        !"0".equals(System.getenv("MACAPK_SHAPE_SOLID_CSL"));

    /** The ColorStateList a `<shape><solid android:color="@color/x">` must keep
     *  when x is a res/color SELECTOR, or null to take the flat-colour path.
     *
     *  AOSP reads the solid as a LIST and lets the drawable pick the row per
     *  state (GradientDrawable.updateGradientDrawableSolid, android14-release
     *  :1729-1733, fetched):
     *      final ColorStateList colorStateList = a.getColorStateList(
     *              R.styleable.GradientDrawableSolid_color);
     *      if (colorStateList != null) { setColor(colorStateList); }
     *  and onStateChange (:1135-1141) re-resolves getColorForState per state.
     *  We flattened it through macapkResolveColorString, which answers the
     *  selector's DEFAULT row -- for an "enabled -> accent, else -> grey"
     *  selector that is the grey, in every state.
     *
     *  It stayed hidden until cluster D1 (macapkColorDrawableValue): Organic
     *  Maps' `@drawable/button_accent_normal` is a shape whose solid is
     *  `@color/button_accent` (enabled -> #249cf2, else black_12), and it used
     *  to be served as the unrelated `@color/button_accent_normal` namesake,
     *  which happened to BE the accent. Loading the real shape without this
     *  turned the enabled Download button from a wrong flat colour into a
     *  wrong rounded grey one.
     *
     *  App ids only: macapkLoadCslFile opens the APP's res/color file by name,
     *  so a framework id would read an app file that merely shares the name.
     *  The framework path keeps its existing flat answer. Our GradientDrawable
     *  already carries the per-state solid (setColor(ColorStateList) +
     *  onStateChange); only this inflater never handed it one.
     *
     *  Themed loads only. A theme-less load would inflate the list with no
     *  Theme, which leaves every `?attr` row at Color.MAGENTA (AOSP's own
     *  null-theme answer, but AOSP then runs applyTheme and we do not), where
     *  the flat path answers from the application theme. Every View path
     *  (TypedArray.getDrawable, LayoutInflater.applyBackground,
     *  Context.getDrawable) passes a Theme; a theme-less caller keeps exactly
     *  the answer it had. A stateless list also keeps the flat path, which
     *  resolves to the same single colour. */
    private android.content.res.ColorStateList macapkShapeSolidSelector(String v, Theme theme) {
        if (!MACAPK_SHAPE_SOLID_CSL || v == null || theme == null) return null;
        v = v.trim();
        if (!v.startsWith("@")) return null;
        int cid = macapkResRefToId(v);
        if (cid == 0 || (cid >>> 24) == 0x01) return null;
        android.content.res.ColorStateList csl = macapkLoadCslFile(cid, theme);
        return (csl != null && csl.isStateful()) ? csl : null;
    }

    /** A drawable tintMode, in either spelling, as AOSP's
     *  Drawable.parseTintMode maps it (android14-release Drawable.java
     *  :1695-1705): 3 src_over, 5 src_in, 9 src_atop, 14 multiply, 15 screen,
     *  16 add; any other PRESENT value is the default SRC_IN. Null when the
     *  attribute is absent (AOSP's -1: the state keeps its mode). NOT
     *  PorterDuff.Mode.intToMode: that table is keyed by the NATIVE ints, where
     *  14 is SCREEN, not the XML enum value where 14 is multiply. */
    private static android.graphics.PorterDuff.Mode macapkParseTintMode(String v) {
        if (v == null || v.trim().isEmpty()) return null;
        String t = v.trim();
        int n;
        Integer ci = macapkCompiledInt(t);
        if (ci != null) {
            n = ci;
        } else {
            switch (t) {
                case "src_over": n = 3; break;
                case "src_in":   n = 5; break;
                case "src_atop": n = 9; break;
                case "multiply": n = 14; break;
                case "screen":   n = 15; break;
                case "add":      n = 16; break;
                // An unknown symbol, or (gate off) a compiled int: a PRESENT
                // value AOSP would not recognise either, so the default below.
                default:         n = -2;
            }
        }
        switch (n) {
            case -1: return null;
            case 3:  return android.graphics.PorterDuff.Mode.SRC_OVER;
            case 9:  return android.graphics.PorterDuff.Mode.SRC_ATOP;
            case 14: return android.graphics.PorterDuff.Mode.MULTIPLY;
            case 15: return android.graphics.PorterDuff.Mode.SCREEN;
            case 16: return android.graphics.PorterDuff.Mode.ADD;
            default: return android.graphics.PorterDuff.Mode.SRC_IN;
        }
    }

    /** A colour-list attribute value as AOSP's TypedArray.getColorStateList
     *  would hand it back: null when absent or unresolvable; the selector FILE
     *  (inflated against `theme`) when the value, or the theme's value for a
     *  `?attr`, names one; else a single-colour list. A tint must stay a LIST:
     *  ?attr/colorControlNormal is a selector in AppCompat themes, and its
     *  disabled row is what a disabled widget's tint shows. */
    private android.content.res.ColorStateList macapkColorStateListValue(String v, Theme theme) {
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || "@null".equals(v) || "@empty".equals(v)) return null;
        // THEME-LESS: one flat colour, resolved the way every other theme-less
        // colour in this file is (macapkResolveColorString's application-theme
        // fallback; macapkShapeSolidSelector keeps theme-less loads flat for the
        // same reason). Loading a selector FILE here would inflate it with no
        // theme, and a `?attr` reached through the application theme often
        // names one whose rows are themselves `?attr` (ShatteredPD's
        // colorControlNormal -> a platform text selector): every row the
        // MAGENTA placeholder, a magenta scrollbar thumb. Measured with the
        // resdrawable census before this line existed.
        if (theme == null) {
            int c = macapkResolveColorString(v, MACAPK_NO_COLOR, null);
            return c == MACAPK_NO_COLOR ? null : android.content.res.ColorStateList.valueOf(c);
        }
        String ref = v;
        if (ref.startsWith("?")) {
            String raw = macapkResolveThemeAttrRaw(ref, theme);
            if (raw != null && raw.trim().startsWith("@")) ref = raw.trim();
        }
        if (ref.startsWith("@")) {
            int id = macapkResRefToId(ref);
            if (id != 0) {
                android.content.res.ColorStateList csl = macapkLoadCslFile(id, theme);
                if (csl != null) return csl;
            }
        }
        int c = macapkResolveColorString(v, MACAPK_NO_COLOR, theme);
        return c == MACAPK_NO_COLOR ? null : android.content.res.ColorStateList.valueOf(c);
    }

    private android.graphics.drawable.GradientDrawable macapkInflateShape(org.w3c.dom.Element root, Theme theme) {
        android.graphics.drawable.GradientDrawable gd =
            new android.graphics.drawable.GradientDrawable();
        String shapeAttr = root.getAttributeNS(MACAPK_ANDROID_NS, "shape");
        gd.setShape("oval".equals(shapeAttr)
            ? android.graphics.drawable.GradientDrawable.OVAL
            : android.graphics.drawable.GradientDrawable.RECTANGLE);
        // fwxml: a compiled shape is its enum int, taken as is --
        // `state.mShape = a.getInt(R.styleable.GradientDrawable_shape,
        // state.mShape)` (GradientDrawable.java :1484, android14-release;
        // attrs.xml :6506-6515 rectangle=0 oval=1 line=2 ring=3). The text test
        // above read every compiled value, oval "1" included, as RECTANGLE.
        Integer shapeInt = macapkCompiledInt(shapeAttr);
        if (shapeInt != null) gd.setShape(shapeInt);
        // RING (MACAPK_SHAPE_RING). An app's own XML reaches here with the enum
        // rendered as its NAME ("ring", as getXml shows for Frozen Bubble's
        // round_button), which the text test above filed as RECTANGLE; only the
        // compiled platform form ("3") reached RING. Then AOSP
        // GradientDrawable.updateStateFromTypedArray (android14-release
        // :1487-1506), verbatim in shape:
        //     if (state.mShape == RING) {
        //         state.mInnerRadius = a.getDimensionPixelSize(innerRadius, state.mInnerRadius);
        //         if (state.mInnerRadius == -1) {
        //             state.mInnerRadiusRatio = a.getFloat(innerRadiusRatio, state.mInnerRadiusRatio);
        //         }
        //         state.mThickness = a.getDimensionPixelSize(thickness, state.mThickness);
        //         if (state.mThickness == -1) {
        //             state.mThicknessRatio = a.getFloat(thicknessRatio, state.mThicknessRatio);
        //         }
        //         state.mUseLevelForShape = a.getBoolean(useLevel, state.mUseLevelForShape);
        //     }
        // None of it was read, and GradientDrawable had no RING draw, so the
        // ring painted as a filled rectangle over its whole box (Frozen Bubble's
        // 120x120 back button: a square where the AVD draws a 100px disk).
        // `useLevel` here is the SHAPE's attribute; <gradient useLevel> is a
        // different field (mUseLevel) read with the gradient.
        if (android.graphics.drawable.GradientDrawable.MACAPK_SHAPE_RING) {
            if ("ring".equals(shapeAttr == null ? null : shapeAttr.trim())) {
                gd.setShape(android.graphics.drawable.GradientDrawable.RING);
            }
            if (gd.getShape() == android.graphics.drawable.GradientDrawable.RING) {
                int inner = macapkDimAttr(root, "innerRadius", -1, theme);
                gd.setInnerRadius(inner);
                if (inner == -1) {
                    gd.setInnerRadiusRatio(macapkFloatAttr(root, "innerRadiusRatio",
                        gd.getInnerRadiusRatio()));
                }
                int thick = macapkDimAttr(root, "thickness", -1, theme);
                gd.setThickness(thick);
                if (thick == -1) {
                    gd.setThicknessRatio(macapkFloatAttr(root, "thicknessRatio",
                        gd.getThicknessRatio()));
                }
                gd.macapkSetUseLevelForShape(macapkBoolAttr(root, "useLevel",
                    gd.macapkGetUseLevelForShape()));
            }
        }
        // <shape android:tint / android:tintMode> (MACAPK_SHAPE_TINT). AOSP
        // GradientDrawable.updateStateFromTypedArray, android14-release
        // :1508-1516:
        //     final int tintMode = a.getInt(R.styleable.GradientDrawable_tintMode, -1);
        //     if (tintMode != -1) {
        //         state.mBlendMode = Drawable.parseBlendMode(tintMode, BlendMode.SRC_IN);
        //     }
        //     final ColorStateList tint = a.getColorStateList(R.styleable.GradientDrawable_tint);
        //     if (tint != null) {
        //         state.mTint = tint;
        //     }
        // Neither was read, so every tinted platform shape painted its raw
        // solid: @android:drawable/text_cursor_material (a white 2dp bar tinted
        // ?attr/colorControlActivated) was an invisible WHITE caret, and
        // btn_default_mtrl_shape / btn_colored_material (white, tinted
        // ?attr/colorButtonNormal / ?attr/colorAccent) were white faces.
        // GradientDrawable composes the tint the AOSP way (a BlendModeColorFilter
        // over the fill and the stroke) under the same gate.
        if (android.graphics.drawable.GradientDrawable.MACAPK_SHAPE_TINT) {
            android.graphics.PorterDuff.Mode mode = macapkParseTintMode(
                root.getAttributeNS(MACAPK_ANDROID_NS, "tintMode"));
            if (mode != null) gd.setTintMode(mode);
            android.content.res.ColorStateList tint = macapkColorStateListValue(
                root.getAttributeNS(MACAPK_ANDROID_NS, "tint"), theme);
            if (tint != null) gd.setTintList(tint);
        }
        org.w3c.dom.NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (!(n instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element c = (org.w3c.dom.Element) n;
            switch (macapkLocalTag(c)) {
                case "solid": {
                    String solid = c.getAttributeNS(MACAPK_ANDROID_NS, "color");
                    android.content.res.ColorStateList solidList =
                        macapkShapeSolidSelector(solid, theme);
                    if (solidList != null) gd.setColor(solidList);
                    else gd.setColor(macapkResolveColorString(solid, 0, theme));
                    break;
                }
                case "corners": {
                    // AOSP updateDrawableCorners reads the bare radius AND the
                    // four per-corner attrs, then builds the 8-float array:
                    //   { tl,tl, tr,tr, br,br, bl,bl }
                    // (GradientDrawable.setCornerRadii's javadoc: "top-left,
                    // top-right, bottom-right, bottom-left").
                    //
                    // ⚠ WE READ ONLY `android:radius`. The per-corner attrs were
                    // never read ANYWHERE in this file, so 237 corpus files
                    // across 32 apps lost their rounding -- and 235 of those 237
                    // declare NO bare `radius` fallback, so `rad` came out 0,
                    // setCornerRadius was never called, and they rendered with
                    // PERFECTLY SQUARE CORNERS. 30 of them are genuinely
                    // asymmetric (a top-rounded sheet, a leading-rounded chip).
                    //
                    // Both halves of the machinery were already correct and
                    // simply never fed: GradientDrawable.setCornerRadii stores
                    // the array and draw() prefers it over the scalar, and
                    // Path.addRoundRect(RectF,float[8],Direction) unpacks it in
                    // exactly AOSP's order. This is the same shape as the
                    // <bitmap> gap (v872) and the radial/sweep shader below --
                    // the DRAWING code is right, the INFLATER never calls it.
                    int rad = macapkDimAttr(c, "radius", 0, theme);
                    int tl = macapkDimAttr(c, "topLeftRadius",     rad, theme);
                    int tr = macapkDimAttr(c, "topRightRadius",    rad, theme);
                    int br = macapkDimAttr(c, "bottomRightRadius", rad, theme);
                    int bl = macapkDimAttr(c, "bottomLeftRadius",  rad, theme);
                    if (tl != rad || tr != rad || br != rad || bl != rad) {
                        gd.setCornerRadii(new float[] {
                            tl, tl, tr, tr, br, br, bl, bl });
                    } else if (rad > 0) {
                        // All four equal: keep the scalar path byte-identical
                        // to before, so a shape that was already rounding
                        // correctly cannot change.
                        gd.setCornerRadius(rad);
                    }
                    break;
                }
                case "stroke":
                    gd.setStroke(macapkDimAttr(c, "width", 0, theme),
                        macapkResolveColorString(
                            c.getAttributeNS(MACAPK_ANDROID_NS, "color"), 0, theme));
                    break;
                case "size":
                    gd.setSize(macapkDimAttr(c, "width", -1, theme), macapkDimAttr(c, "height", -1, theme));
                    break;
                case "gradient": {
                    // v1.0.499: a <shape> with a <gradient> (and no <solid>) was
                    // parsed into a GradientDrawable with NO fill -> it drew
                    // NOTHING. Duolingo's JuicyButton face/lip use exactly this:
                    // a gradient <shape> rendered into a Bitmap via
                    // Drawable.toBitmap, then a BitmapShader fill -> the
                    // off-screen bitmap was transparent -> the GET STARTED button
                    // had no green fill. Parse the startColor/centerColor/
                    // endColor stops + angle so the gradient renders.
                    java.util.ArrayList<Integer> stops = new java.util.ArrayList<>();
                    String sc = c.getAttributeNS(MACAPK_ANDROID_NS, "startColor");
                    String cc = c.getAttributeNS(MACAPK_ANDROID_NS, "centerColor");
                    String ec = c.getAttributeNS(MACAPK_ANDROID_NS, "endColor");
                    if (sc != null && !sc.isEmpty()) stops.add(macapkResolveColorString(sc, 0, theme));
                    if (cc != null && !cc.isEmpty()) stops.add(macapkResolveColorString(cc, 0, theme));
                    if (ec != null && !ec.isEmpty()) stops.add(macapkResolveColorString(ec, 0, theme));
                    if (stops.size() == 1) {
                        gd.setColor(stops.get(0));
                    } else if (stops.size() >= 2) {
                        int[] arr = new int[stops.size()];
                        for (int k = 0; k < arr.length; k++) arr[k] = stops.get(k);
                        // android:angle is a multiple of 45; map to a CW Orientation.
                        String angS = c.getAttributeNS(MACAPK_ANDROID_NS, "angle");
                        int ang = 0;
                        try { if (angS != null && !angS.isEmpty()) ang = ((int) Float.parseFloat(angS.trim())) % 360; }
                        catch (Throwable ignored) {}
                        if (ang < 0) ang += 360;
                        gd.setOrientation(macapkAngleToOrientation(ang));
                        gd.setColors(arr);
                    }
                    break;
                }
            }
        }
        return gd;
    }

    /** AOSP GradientDrawable angle (multiple of 45, CCW from +x in screen
     *  coords) -> our Orientation (the direction the gradient flows). */
    private static android.graphics.drawable.GradientDrawable.Orientation macapkAngleToOrientation(int angle) {
        switch (angle) {
            case 0:   return android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT;
            case 45:  return android.graphics.drawable.GradientDrawable.Orientation.BL_TR;
            case 90:  return android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP;
            case 135: return android.graphics.drawable.GradientDrawable.Orientation.BR_TL;
            case 180: return android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT;
            case 225: return android.graphics.drawable.GradientDrawable.Orientation.TR_BL;
            case 270: return android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM;
            case 315: return android.graphics.drawable.GradientDrawable.Orientation.TL_BR;
            default:  return android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM;
        }
    }

    /** Resolve an android:* dimension attribute, INCLUDING an `@dimen/...`
     *  reference. The `@` case was previously unhandled and fell through to the
     *  caller's default (usually 0), which is live and visible today:
     *  androidx.appcompat's own default button shape
     *  (`abc_btn_default_mtrl_shape.xml`, `abc_btn_colored_material.xml`) writes
     *  `<corners android:radius="@dimen/abc_control_corner_material"/>`, so every
     *  AppCompat button, dialog, snackbar and tooltip has been rendering with a
     *  ZERO corner radius -- square instead of rounded. Confirmed by statically
     *  decoding two corpus APKs (F-Droid, OpenCalculator), where those files are
     *  verbatim-identical.
     *  TypedArray.dimenPx() in this same file already resolves the equivalent
     *  case for the other attribute-reading path; this mirrors it, so the two
     *  paths now agree.
     *
     *  fwxml (MACAPK_FW_XML_NUMERIC): a `?attr` dimension now resolves against
     *  `theme` (null: the application theme, the same fallback the colour
     *  path takes), as AOSP's obtainAttributes resolves every attribute
     *  reference before getDimensionPixelSize reads it. It was the one value
     *  kind this inflater could not read, and the platform's own button shapes
     *  carry one: btn_default_mtrl_shape / btn_colored_material's
     *  `<corners android:radius="?attr/buttonCornerRadius"/>` (compiled
     *  "?0x01010575") came out square. Gate off: `?` keeps answering `def`. */
    private int macapkDimAttr(org.w3c.dom.Element e, String name, int def, Theme theme) {
        String v = e.getAttributeNS(MACAPK_ANDROID_NS, name);
        if (MACAPK_FW_XML_NUMERIC && v != null && v.trim().startsWith("?")) {
            Float px = macapkThemeDimenPx(v.trim(), theme);
            return px != null ? Math.round(px) : def;
        }
        if (v != null && v.startsWith("@")) {
            Float ref = macapkResolveDimenRef(v);
            return ref != null ? Math.round(ref) : def;
        }
        Float px = macapkParseDimenString(v);
        return px != null ? (int) (float) px : def;
    }

    /** A `?attr` dimension: the theme's value for the attribute (chased through
     *  `?` hops by macapkResolveThemeAttrRaw), then read as a literal or a
     *  reference exactly like an attribute that held it directly. */
    private Float macapkThemeDimenPx(String expr, Theme theme) {
        String raw = macapkResolveThemeAttrRaw(expr, theme);
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.startsWith("@")) return macapkResolveDimenRef(raw);
        return macapkParseDimenString(raw);
    }

    /** `@dimen/foo`, `@android:dimen/foo`, `@pkg:dimen/foo` -> pixels, or null. */
    private Float macapkResolveDimenRef(String ref) {
        String body = ref.substring(1);
        // fwxml: a reference is an ID. "@0x01050077" (a compiled platform
        // TYPE_REFERENCE; btn_default_mtrl_shape's insets and padding) used to
        // reach getIdentifier("0x01050077", "dimen") and answer null, and
        // "@android:dimen/x" had its package stripped and was looked up in the
        // APP's table, where it is absent or, worse, a different value that
        // merely shares the name. AOSP resolves both by id against the table
        // that owns the id (ResourcesImpl.getValue -> AssetManager2).
        if (MACAPK_FW_XML_NUMERIC) {
            int rid = 0;
            if (body.startsWith("0x") || body.startsWith("0X")) {
                try { rid = (int) Long.parseLong(body.substring(2), 16); }
                catch (NumberFormatException nfe) { return null; }
                if (rid == 0) return null;
            } else if (body.startsWith("android:")) {
                rid = macapkResRefToId(ref);
            }
            if (rid != 0) return macapkDimenPx(rid);
        }
        String type = "dimen";
        String name = body;
        int slash = body.indexOf('/');
        if (slash >= 0) {
            String left = body.substring(0, slash);
            name = body.substring(slash + 1);
            int colon = left.indexOf(':');
            if (colon >= 0) left = left.substring(colon + 1);
            if (!left.isEmpty()) type = left;
        }
        int id = getIdentifier(name, type, null);
        return id != 0 ? macapkDimenPx(id) : null;
    }

    private Float macapkParseDimenString(String v) {
        return TypedArray.macapkParseDimenLiteral(v, metrics);
    }

    // Package-private (v1.0.499): TypedArray.getColor delegates here so a
    // styled color attr that references a resource (app:faceColor="@color/X",
    // ?attr/colorX) resolves instead of falling back to the default.
    //
    // docs/attr-theme-threading_2026-07-31.md: theme-less convenience overload,
    // kept for every call site that genuinely has no Theme to offer
    // (MacapkResourceArray -- Resources.obtainTypedArray has no Theme concept
    // in AOSP either). Delegates to the 3-arg overload with theme=null, which
    // resolves `?attr/` exactly as before (the static application-theme-only
    // path) -- so this split changes no observable behavior on its own.
    int macapkResolveColorString(String v, int def) {
        return macapkResolveColorString(v, def, null);
    }

    /** Theme-aware sibling of the above. Part (d) of
     *  docs/newapps-a_2026-07-30.md / docs/attr-theme-threading_2026-07-31.md:
     *  an `?attr/X` value used to resolve ONLY against the static application
     *  theme (ResourceIndex.resolveApplicationThemeColor), no matter which
     *  Context/Theme actually inflated the XML that referenced it -- so a
     *  View-level `android:theme="@style/X"` overlay (LayoutInflater's
     *  createViewFromTag theme step, MACAPK_VIEW_THEME) never reached a
     *  `<solid android:color="?attr/X"/>` inside a background drawable, even
     *  though it already reached a direct TypedArray/View attribute read via
     *  Theme.resolveAttribute. When `theme` is supplied, walk ITS applied-style
     *  cascade first (Theme.resolveAttrColor), falling back to the application
     *  theme -- identical to the theme==null path when the cascade is empty
     *  (the common case: no override style was ever pushed), so this is a
     *  strict superset, not a behavior change, for every existing caller that
     *  has no Theme to pass. */
    int macapkResolveColorString(String v, int def, Theme theme) {
        if (v == null) return def;
        v = v.trim();
        if (v.isEmpty()) return def;
        if (v.startsWith("#")) {
            try { return android.graphics.Color.macapkParseResourceColor(v); }
            catch (Throwable t) { return def; }
        }
        if (v.startsWith("?")) {
            // fwxml: a compiled "?0x0101...." names its attr first (see
            // macapkTypeCompiledElement). This is the entry every colour read
            // shares -- TypedArray.getColor, ColorStateList.resolveColor, the
            // drawable inflaters -- so a compiled value from a DOM this file did
            // not build (AssetManager's, XmlBlock's) is readable here too.
            String named = macapkNamedAttrRef(v);
            if (named != null) v = named;
            String attr = v.substring(1);
            int sl = attr.indexOf('/');
            if (sl >= 0) attr = attr.substring(sl + 1);  // ?attr/x -> x
            int out = theme != null ? theme.resolveAttrColor(attr, def)
                                    : macapkThemeColor(attr, def);
            // DIAGNOSTIC (MACAPK_TRACE_ATTRCOLOR=1): which tier answered a
            // `?attr` colour, and with what. The XML-drawable path and the
            // View/TypedArray path reach here with DIFFERENT `theme` arguments,
            // and opencalculator renders proof that one of them resolves while
            // the other does not -- its `android:backgroundTint="?button_X_color"`
            // buttons are correct while the identical `?button_color` inside
            // @drawable/circle's <solid> paints opaque black.
            if ("1".equals(System.getenv("MACAPK_TRACE_ATTRCOLOR"))) {
                android.util.Log.i("Mac.apk.AttrColor", "?" + attr
                    + " theme=" + (theme == null ? "NULL" : "obj")
                    + " -> 0x" + Integer.toHexString(out)
                    + (out == def ? "  (== DEFAULT, i.e. UNRESOLVED)" : ""));
            }
            return out;
        }
        if (v.startsWith("@")) {
            // v1.0.834: route through the ResourceIndex colour-expression
            // resolver FIRST. The old code stripped the package qualifier and
            // asked getIdentifier(name, "color", null) -- the APP's table only
            // -- so `@android:color/white` could never resolve (no app
            // resource is named "white") and every XML-drawable
            // `<solid android:color="@android:color/*">` silently took the
            // caller's default. That was the residual half of the framework
            // colour-table gap: the table already existed in
            // ResourceIndex.resolveColorRef (10 named constants + the
            // Material You @android:color/system_* palette); this resolver
            // just was not using it. resolveColorRef ALSO handles `@color/X`
            // reference chains that getIdentifier+getColor cannot follow.
            Integer c = macapkResolveColorExpr(v);
            if (c != null) return c;
            String body = v.substring(1);
            int sl = body.indexOf('/');
            String name = sl >= 0 ? body.substring(sl + 1) : body;
            int cid = getIdentifier(name, "color", null);
            if (cid != 0) { try { return getColor(cid); } catch (Throwable t) {} }
            return def;
        }
        return def;
    }

    /** Reflective bridge to ResourceIndex.macapkResolveColorExpr -- the single
     *  framework/app colour-expression table. Same reflection rationale as
     *  macapkThemeColor above (android.content.res must not have a
     *  compile-time dependency on com.macapk.loader). Returns null when the
     *  expression resolves to nothing, which is distinct from resolving to
     *  colour 0 (fully transparent, a legitimate value). */
    // (The MACAPK_FILE_RES_TYPES set that lived here died with v1.0.913's
    // resolveAttribute rewire: the shared resolver decides file-backedness
    // from ResourceIndex.pathFor per id instead of a type-name allowlist,
    // which also covers res/color selector files the list had to exclude.)

    /** The `<type>` component of a resource reference token, or null. Accepts
     *  the same three spellings as macapkResRefToId. `@0x…` carries no type
     *  name, so it returns null (the caller then treats it as an opaque
     *  reference, which is what it is). */
    static String macapkResRefType(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '@') return null;
        String body = raw.substring(1);
        if (body.startsWith("+")) body = body.substring(1);
        if (body.startsWith("0x") || body.startsWith("0X")) return null;
        int slash = body.indexOf('/');
        if (slash <= 0) return null;
        String type = body.substring(0, slash);
        int colon = type.indexOf(':');
        return colon >= 0 ? type.substring(colon + 1) : type;
    }

    /** Turn a resources-XML reference token into its numeric resource id.
     *  Accepts the three spellings that reach us: `@0x7f080123` (how
     *  BinaryXmlParser renders a TYPE_REFERENCE), `@drawable/foo`, and
     *  `@android:drawable/foo` / `@pkg:type/name`. Returns 0 when the id
     *  cannot be produced -- which for a `@android:` reference is the honest
     *  answer (we ship no framework resource table) and is also AOSP's value
     *  for a null resource. */
    static int macapkResRefToId(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '@') return 0;
        String body = raw.substring(1);
        if (body.startsWith("+")) body = body.substring(1);   // "@+id/foo"
        if (body.startsWith("0x") || body.startsWith("0X")) {
            try { return (int) Long.parseLong(body.substring(2), 16); }
            catch (NumberFormatException ignored) { return 0; }
        }
        int slash = body.indexOf('/');
        if (slash <= 0) return 0;
        String type = body.substring(0, slash);
        String name = body.substring(slash + 1);
        int colon = type.indexOf(':');
        String pkg = null;
        if (colon >= 0) {
            // v1.0.917: "@android:type/name" resolves through the platform
            // table (ResourceIndex.idOfName's "android" package branch, gate
            // ON; the same map NAME.getIdentifier reads). Gate OFF that
            // branch returns 0, which is this method's historical answer
            // here. Other foreign packages stay unresolvable.
            String p = type.substring(0, colon);
            if ("android".equals(p)) pkg = p;
            else return 0;
            type = type.substring(colon + 1);
        }
        if (macapkResourceIndex == null) return 0;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("idOfName", String.class, String.class, String.class);
            Object id = m.invoke(macapkResourceIndex, name, type, pkg);
            if (id instanceof Integer) return (Integer) id;
        } catch (Throwable ignored) {}
        return 0;
    }

    // ------------------------------------------------------------------
    // v1.0.917: the last-reference channel (reflective bridge).
    //
    // AOSP's resolution APIs report the id of the LAST reference
    // ResolveReference followed alongside the value; the JNI CopyValue
    // writes it into TypedValue.resourceId (android_util_AssetManager.cpp,
    // android11-release: theme resolution seeds `uint32_t ref = 0u`,
    // NativeGetResourceValue seeds `ref = resid`, ResolveReference does
    // `*out_last_reference = value->data` per followed reference). Our
    // string pipeline consumes reference chains inside ResourceIndex (the
    // framework settle, colour chains, chaseValueRef, string chains), which
    // now publishes each followed id into a per-thread slot there. These
    // helpers read and reset that slot; a consumer reads IMMEDIATELY after
    // the producing call so no unrelated query can intervene.
    // ------------------------------------------------------------------
    private static volatile java.lang.reflect.Method macapkOriginReadMethod;
    private static volatile java.lang.reflect.Method macapkOriginClearMethod;
    static int macapkReadValueOrigin() {
        if (macapkResourceIndex == null) return 0;
        try {
            java.lang.reflect.Method m = macapkOriginReadMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass()
                    .getMethod("macapkLastValueOriginId");
                macapkOriginReadMethod = m;
            }
            Object v = m.invoke(macapkResourceIndex);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        return 0;
    }
    static void macapkClearValueOrigin() {
        if (macapkResourceIndex == null) return;
        try {
            java.lang.reflect.Method m = macapkOriginClearMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass()
                    .getMethod("macapkClearValueOrigin");
                macapkOriginClearMethod = m;
            }
            m.invoke(macapkResourceIndex);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------
    // The ONE raw-value -> TypedValue resolver (v1.0.913).
    //
    // WHY: real Android hands every styled/theme/resolved attribute back as
    // aapt's COMPILED encoding (Res_value type + data + the id of the
    // resource a reference chain ended in). Our pipeline carries SOURCE
    // TEXT, and each consumer used to re-derive a partial encoding of its
    // own -- which is exactly how the fwprobe differential found dimensions
    // with data=0, resourceId always 0, and whole styles answering nothing.
    // This method is the single place source text becomes a TypedValue, and
    // it mirrors the resolution AOSP does in AttributeResolution.cpp
    // (android11-release):
    //   - ApplyStyle:292 "we prioritize values coming from, first XML
    //     attributes, then XML style, then default style, and finally the
    //     theme" -- the CALLER picks the raw string by that order; this
    //     method is the "resolve" half that follows.
    //   - a `?attr` value resolves against the theme (ResolveAttributeReference
    //     / Theme::GetAttribute), and keeps resolving through further hops.
    //     Theme::GetAttribute chases these regardless of resolveRefs, which
    //     is why resolveAttribute(..., false) still answers a `?`-chain.
    //   - a `@ref` value under resolveRefs=true is followed to its LEAF
    //     (AssetManager2::ResolveReference), with out.resourceId = the id
    //     of the resource the value was loaded from; under
    //     resolveRefs=false it stays TYPE_REFERENCE with data = the id and
    //     resourceId untouched (golden RESOLVENR rows show res=0).
    //   - "@null" collapses to absent (ApplyStyle:371 turns
    //     TYPE_REFERENCE/data==0 back into TYPE_NULL).
    //   - literals compile per ResourceTypes.cpp: colors by digit count
    //     (#rgb 0x1f / #argb 0x1e / #rrggbb 0x1d / #aarrggbb 0x1c, 4-bit
    //     channels DUPLICATED), booleans TRUE = 0xffffffff, ints dec/hex,
    //     dimensions/fractions via TypedValue.macapkParseNumericLiteral,
    //     enum -> TYPE_INT_DEC / flags -> TYPE_INT_HEX via the app's own
    //     attrs.xml (ResourceIndex.resolveAttrEnumValue).
    //
    // `theme` may be null: hops then resolve against the static application
    // theme (macapkResolveThemeAttrRaw), the pre-threading behavior.
    // Returns false and leaves `out` unspecified when the raw value cannot
    // be resolved -- callers treat that exactly like an absent attribute,
    // which keeps every getter's caller-default contract intact (the
    // v1.0.894 lesson: never hand back a value you could not validate).
    // ------------------------------------------------------------------
    boolean macapkResolveRawToTypedValue(String raw, String attrName, Theme theme,
                                          boolean resolveRefs, android.util.TypedValue out) {
        // Compatibility shape: no last-reference threading. Callers that
        // model AOSP's resourceId reporting (Theme.resolveAttribute's main
        // path, TypedArray's record) use the threading overload below; this
        // form keeps every other caller's output byte-identical, including
        // resolveAttribute's compensation-sourced fallback branch whose
        // legacyShape guard keys on resourceId (see that branch's comment).
        return macapkResolveRawToTypedValue(raw, attrName, theme, resolveRefs,
                                            out, false, 0);
    }

    /** The threading overload (v1.0.917). `callerOrigin` is the
     *  last-reference id recorded while the CALLER obtained `raw` (0 when
     *  none): the theme walk settles framework scalar references to their
     *  leaf literal, and AOSP reports the settled reference's id in
     *  TypedValue.resourceId (ResolveReference's out_last_reference; the
     *  JNI CopyValue). `threadOrigin` gates every new resourceId behavior
     *  so the 5-arg shape above stays byte-identical. */
    boolean macapkResolveRawToTypedValue(String raw, String attrName, Theme theme,
                                          boolean resolveRefs, android.util.TypedValue out,
                                          boolean threadOrigin, int callerOrigin) {
        if (raw == null || out == null) return false;
        raw = raw.trim();
        // ?attr hops first. AOSP caps the chain (kMaxIterations = 20 in
        // AssetManager2's theme resolution) so a cycle cannot hang.
        for (int hop = 0; hop < 20 && !raw.isEmpty() && raw.charAt(0) == '?'; hop++) {
            String next = null;
            if (threadOrigin) macapkClearValueOrigin();
            if (theme != null) {
                String name;
                if (raw.startsWith("?attr/"))              name = raw.substring(6);
                else if (raw.startsWith("?android:attr/")) name = "android:" + raw.substring(14);
                else                                        name = raw.substring(1);
                // A bare hop name is an app attribute (`?attr/X`); android: names
                // fall straight through to the per-style walk.
                try { next = theme.macapkResolveAppAttrRawHonest(name); }
                catch (Throwable ignored) {}
            } else {
                next = macapkResolveThemeAttrRaw(raw);
            }
            if (next == null || next.trim().equals(raw)) return false;  // theme silent: absent
            // The hop's producer owns the last-reference slot now; its
            // recording replaces whatever the caller observed for the
            // pre-hop raw. AOSP analogue: Theme::GetAttribute's ?attr hops
            // never touch out_last_reference, and resolution continues
            // from the hopped-to entry.
            if (threadOrigin) callerOrigin = macapkReadValueOrigin();
            raw = next.trim();
        }
        if (raw.isEmpty()) return false;
        out.string = null;
        out.resourceId = 0;
        if ("@null".equals(raw)) return false;
        if (raw.charAt(0) == '@') {
            final boolean framework = raw.startsWith("@android:");
            final int id = macapkResRefToId(raw);
            String rtype = macapkResRefType(raw);
            // An APP reference whose id we cannot produce is OUR index gap,
            // not an absent attribute; report absent so the caller default
            // wins (same policy resolveAttribute has carried since v1.0.834).
            if (id == 0 && !framework) return false;
            if (resolveRefs && id != 0) {
                // v1.0.917: fresh slot, so the serves below report only the
                // references THEY follow past `id`. AOSP shape: res is `id`
                // for a one-hop chain and the deepest followed id otherwise
                // (ResolveReference publishes per iteration). All gated on
                // threadOrigin so the 5-arg callers keep res = id exactly.
                if (threadOrigin) macapkClearValueOrigin();
                // FILE-backed resource of any type (drawable / layout / xml /
                // a res/color selector / font / raw): the leaf value in the
                // real table is TYPE_STRING carrying the compiled path.
                String path = macapkResourcePath(id);
                if (path != null && !path.isEmpty()) {
                    out.type = android.util.TypedValue.TYPE_STRING;
                    out.string = macapkArscRelativePath(path);
                    out.resourceId = id;
                    return true;
                }
                // FRAMEWORK id under the gate: serve the platform table's own
                // Res_value (macapkFwValueOf chases in-table references and
                // publishes each followed id). This is where a framework
                // res/color selector or drawable answers TYPE_STRING with its
                // compiled path -- the same shape the app-side pathFor serve
                // above produces and exactly what the device answers -- and
                // where a colour literal keeps aapt's digit-count flavour
                // (#rrggbb stays TYPE_INT_COLOR_RGB8) instead of flattening
                // to ARGB8. Complex targets (a style bag) and ?attr-valued
                // leaves return null here and keep every fallback below.
                if ((id >>> 24) == 0x01) {
                    Object[] fwv = macapkFwSimpleValue(id);
                    if (fwv != null) {
                        int fdt = (Integer) fwv[0];
                        int fdata = (Integer) fwv[1];
                        String ftext = (String) fwv[2];
                        if (fdt == android.util.TypedValue.TYPE_STRING) {
                            if (ftext != null && !ftext.isEmpty()) {
                                out.type = android.util.TypedValue.TYPE_STRING;
                                out.string = ftext;
                                out.data = 0;
                                out.resourceId = macapkChaseTerminus(id, threadOrigin);
                                return true;
                            }
                        } else if (fdt >= android.util.TypedValue.TYPE_FIRST_INT
                                       && fdt <= android.util.TypedValue.TYPE_LAST_INT
                                   || fdt == android.util.TypedValue.TYPE_FLOAT
                                   || fdt == android.util.TypedValue.TYPE_DIMENSION
                                   || fdt == android.util.TypedValue.TYPE_FRACTION) {
                            out.type = fdt;
                            out.data = fdata;
                            out.string = null;
                            out.resourceId = macapkChaseTerminus(id, threadOrigin);
                            return true;
                        }
                    }
                }
                // The "@0x%08x" spelling (binary-XML and framework-view
                // rendering) carries no type name; the index knows the id's
                // type, and deriving it here lets the typed serves below
                // answer instead of falling through to an opaque reference.
                if (rtype == null) {
                    String[] dtn = macapkTypeNameOf(id);
                    if (dtn != null) rtype = dtn[0];
                }
                if ("string".equals(rtype)) {
                    String s = getString(id);
                    // getString converts a missing entry to "" (its own AOSP
                    // missing-but-tolerated contract), which at THIS surface
                    // would serve an empty string for a framework entry the
                    // table lacks: an app id always has a real row here, a
                    // framework "" means unresolvable. Fall through to the
                    // honest TYPE_REFERENCE instead of lying with "".
                    if (s != null && (!framework || !s.isEmpty())) {
                        out.type = android.util.TypedValue.TYPE_STRING;
                        out.string = s;
                        out.resourceId = macapkChaseTerminus(id, threadOrigin);
                        return true;
                    }
                } else if ("color".equals(rtype)) {
                    int c = macapkColorById(id, MACAPK_NO_COLOR);
                    if (c == MACAPK_NO_COLOR) {
                        // The by-id table can miss where the expression
                        // resolver (which chases @color -> @color chains and
                        // the @android:color table) still answers.
                        Integer ce = macapkResolveColorExpr(raw);
                        if (ce != null) c = ce;
                    }
                    if (c != MACAPK_NO_COLOR) {
                        // Digit-count typing is unavailable through the id
                        // table (it stores the parsed int); ARGB8 is what
                        // every 8-digit source compiles to and what all the
                        // measured emulator answers for value colours show.
                        out.type = android.util.TypedValue.TYPE_INT_COLOR_ARGB8;
                        out.data = c;
                        out.resourceId = macapkChaseTerminus(id, threadOrigin);
                        return true;
                    }
                } else if ("dimen".equals(rtype)) {
                    String d = macapkRawTableValue("getDimenRaw", id);
                    if (d != null && android.util.TypedValue.macapkParseNumericLiteral(d, out)) {
                        // A <dimen> table row may legally hold a bare float
                        // (<item type="dimen" format="float">), so the parsed
                        // type (DIMENSION or FLOAT) is already right.
                        out.resourceId = macapkChaseTerminus(id, threadOrigin);
                        return true;
                    }
                } else if ("fraction".equals(rtype)) {
                    String d = macapkRawTableValue("getFractionRaw", id);
                    if (d != null && android.util.TypedValue.macapkParseNumericLiteral(d, out)) {
                        out.resourceId = macapkChaseTerminus(id, threadOrigin);
                        return true;
                    }
                } else if ("bool".equals(rtype)) {
                    out.type = android.util.TypedValue.TYPE_INT_BOOLEAN;
                    out.data = getBoolean(id) ? 0xffffffff : 0;
                    out.resourceId = macapkChaseTerminus(id, threadOrigin);
                    return true;
                } else if ("integer".equals(rtype)) {
                    out.type = android.util.TypedValue.TYPE_INT_DEC;
                    out.data = getInteger(id);
                    out.resourceId = macapkChaseTerminus(id, threadOrigin);
                    return true;
                } else if ("id".equals(rtype)) {
                    // aapt compiles `<item type="id"/>` as boolean false;
                    // following an @id reference lands on that leaf.
                    out.type = android.util.TypedValue.TYPE_INT_BOOLEAN;
                    out.data = 0;
                    out.resourceId = id;
                    return true;
                }
                // Bag resources (@style/X, @array/X, @plurals/X) and leaves we
                // cannot type: stay a reference, resourceId = the id, which is
                // AOSP's own resolution result when ResolveReference stops at
                // a non-value entry.
                out.type = android.util.TypedValue.TYPE_REFERENCE;
                out.data = id;
                out.resourceId = id;
                return true;
            }
            // A framework `@android:color/...` ref carries no numeric id for
            // us (no framework table), but the colour-expression table DOES
            // resolve the well-known entries; a bare TYPE_REFERENCE data=0
            // here would drop a colour the old resolveAttribute served.
            if (resolveRefs && framework && "color".equals(rtype)) {
                Integer ce = macapkResolveColorExpr(raw);
                if (ce != null) {
                    out.type = android.util.TypedValue.TYPE_INT_COLOR_ARGB8;
                    out.data = ce;
                    out.resourceId = id;
                    return true;
                }
            }
            // resolveRefs == false (or an unservable framework ref): the
            // stored form IS the answer. Golden RESOLVENR rows carry res=0.
            out.type = android.util.TypedValue.TYPE_REFERENCE;
            out.data = id;
            out.resourceId = 0;
            return true;
        }
        if (raw.charAt(0) == '#') {
            if (!macapkParseColorLiteral(raw, out)) return false;
            macapkApplySettleOrigin(out, resolveRefs, threadOrigin, callerOrigin);
            return true;
        }
        if ("true".equalsIgnoreCase(raw)) {
            out.type = android.util.TypedValue.TYPE_INT_BOOLEAN;
            out.data = 0xffffffff;   // aapt: (uint32_t)-1, ResourceTypes.cpp:5806
            macapkApplySettleOrigin(out, resolveRefs, threadOrigin, callerOrigin);
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            out.type = android.util.TypedValue.TYPE_INT_BOOLEAN;
            out.data = 0;
            macapkApplySettleOrigin(out, resolveRefs, threadOrigin, callerOrigin);
            return true;
        }
        if (android.util.TypedValue.macapkParseNumericLiteral(raw, out)) {
            macapkApplySettleOrigin(out, resolveRefs, threadOrigin, callerOrigin);
            return true;
        }
        // Enum / flag tokens compile to ints via the ATTR's own declaration.
        // aapt writes enums as TYPE_INT_DEC and flag sets as TYPE_INT_HEX;
        // we approximate flag-ness by the presence of '|' (the app's
        // attrs.xml flag/enum split is not exposed per-attr), which types a
        // SINGLE-token flag value as DEC instead of HEX -- same data, one
        // type nuance, noted in docs.
        if (attrName != null) {
            try {
                Object ri = macapkResourceIndex;
                if (ri != null) {
                    // Flag-aware first: the platform table's own <attr> bags
                    // (gate ON) know both the symbol ints AND flag-ness, so a
                    // single-token flag value ("center") types TYPE_INT_HEX
                    // exactly as aapt compiled it. ResourceTypes.cpp writes
                    // enum assignments DEC and flag assignments HEX.
                    Object typed = ri.getClass()
                        .getMethod("macapkFwAttrEnumTyped", String.class, String.class)
                        .invoke(ri, attrName, raw);
                    if (typed instanceof int[] ta && ta.length == 2) {
                        out.type = ta[1] != 0
                            ? android.util.TypedValue.TYPE_INT_HEX
                            : android.util.TypedValue.TYPE_INT_DEC;
                        out.data = ta[0];
                        return true;
                    }
                    Object r = ri.getClass()
                        .getMethod("resolveAttrEnumValue", String.class, String.class)
                        .invoke(ri, attrName, raw);
                    if (r instanceof Integer iv) {
                        out.type = raw.indexOf('|') >= 0
                            ? android.util.TypedValue.TYPE_INT_HEX
                            : android.util.TypedValue.TYPE_INT_DEC;
                        out.data = iv;
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        out.type = android.util.TypedValue.TYPE_STRING;
        out.string = raw;
        return true;
    }

    /** LITERAL packs report the settle origin (v1.0.917): when the raw the
     *  caller (or a ?attr hop) obtained was a framework scalar reference
     *  settled to its leaf literal by the view builder, AOSP would have
     *  followed that reference at resolve time and reported its id
     *  (ResolveReference's out_last_reference). Under resolveRefs=false the
     *  device reports the STORED form with resourceId 0, and a settled
     *  literal cannot reproduce the stored reference anyway, so nothing is
     *  applied there. A literal that never was a reference has origin 0 and
     *  keeps resourceId 0, the AOSP literal contract. */
    private static void macapkApplySettleOrigin(android.util.TypedValue out,
            boolean resolveRefs, boolean threadOrigin, int callerOrigin) {
        if (threadOrigin && resolveRefs && callerOrigin != 0
                && out.resourceId == 0) {
            out.resourceId = callerOrigin;
        }
    }

    /** res for a followed reference chain: the deepest id the serves just
     *  published, or `id` itself when nothing deeper was followed. Matches
     *  ResolveReference's per-iteration out_last_reference with the chain
     *  entry counting as the first followed reference. Gated: 5-arg-shape
     *  callers always report `id`, their historical answer. */
    private static int macapkChaseTerminus(int id, boolean threadOrigin) {
        if (threadOrigin) {
            int last = macapkReadValueOrigin();
            if (last != 0) return last;
        }
        return id;
    }

    /** `#rgb` / `#argb` / `#rrggbb` / `#aarrggbb` exactly as ResourceTypes.cpp
     *  stringToValue compiles them: the short forms DUPLICATE each 4-bit
     *  channel (0xf -> 0xff) and each length has its own TYPE_INT_COLOR_*. */
    private static boolean macapkParseColorLiteral(String s, android.util.TypedValue out) {
        int len = s.length() - 1;
        long v = 0;
        for (int i = 1; i <= len; i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) return false;
            v = (v << 4) | d;
        }
        switch (len) {
            case 3: {
                int r = (int) (v >> 8) & 0xf, g = (int) (v >> 4) & 0xf, b = (int) v & 0xf;
                out.type = android.util.TypedValue.TYPE_INT_COLOR_RGB4;
                out.data = 0xff000000 | (r << 20) | (r << 16) | (g << 12) | (g << 8) | (b << 4) | b;
                return true;
            }
            case 4: {
                int a = (int) (v >> 12) & 0xf, r = (int) (v >> 8) & 0xf, g = (int) (v >> 4) & 0xf, b = (int) v & 0xf;
                out.type = android.util.TypedValue.TYPE_INT_COLOR_ARGB4;
                out.data = (a << 28) | (a << 24) | (r << 20) | (r << 16)
                         | (g << 12) | (g << 8) | (b << 4) | b;
                return true;
            }
            case 6:
                out.type = android.util.TypedValue.TYPE_INT_COLOR_RGB8;
                out.data = 0xff000000 | (int) v;
                return true;
            case 8:
                out.type = android.util.TypedValue.TYPE_INT_COLOR_ARGB8;
                out.data = (int) v;
                return true;
            default:
                return false;
        }
    }

    /** The compiled-table spelling of a resource file path: real Android
     *  stores "res/drawable/foo.xml" (what the emulator answers); our
     *  extraction serves an absolute path under the app's _apkt dir. Strip
     *  to the final "res/" component so TypedValue.string matches the
     *  device encoding. Consumers that OPEN files keep using pathFor's
     *  absolute form (Resources.getValue stays absolute for exactly that
     *  reason); this shape is only for the value-record surface, whose
     *  consumers dispatch on the id/extension. */
    private static String macapkArscRelativePath(String abs) {
        if (abs == null) return null;
        int i = abs.lastIndexOf("/res/");
        return i >= 0 ? abs.substring(i + 1) : abs;
    }

    /** Read a raw value-table entry (dimen/fraction) by resource ID through
     *  the reflective ResourceIndex: id -> entry name -> get<T>Raw(name). */
    private String macapkRawTableValue(String method, int id) {
        if (macapkResourceIndex == null) return null;
        try {
            String[] tn = macapkTypeNameOf(id);
            if (tn == null || tn.length < 2 || tn[1] == null) return null;
            Object v = macapkResourceIndex.getClass()
                .getMethod(method, String.class)
                .invoke(macapkResourceIndex, tn[1]);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) { return null; }
    }

    /** Resolve a `?attr/X` / `?android:attr/X` / `?X` theme-attribute
     *  reference to the RAW value string the theme cascade declares for it
     *  (e.g. "@drawable/abc_item_background_holo_light", "#ff0000", "16dp").
     *  Returns null when nothing in the cascade declares it.
     *
     *  WHY: AOSP's obtainStyledAttributes resolves `?attr/` indirection inside
     *  AssetManager BEFORE the TypedArray is handed back, so a caller doing
     *  `ta.getResourceId(i, 0)` on an attribute written as
     *  `android:background="?attr/selectableItemBackground"` gets a real id.
     *  Our TypedArray reads the attribute string straight off the
     *  AttributeSet, so it has to do that indirection itself. */
    private static volatile java.lang.reflect.Method macapkThemeRawMethod;
    private static volatile java.lang.reflect.Method macapkThemeNameMethod;
    static String macapkResolveThemeAttrRaw(String expr) {
        if (macapkResourceIndex == null || expr == null) return null;
        expr = expr.trim();
        String named = macapkNamedAttrRef(expr);   // fwxml: "?0x...." -> "?android:x"
        if (named != null) expr = named;
        if (expr.isEmpty() || expr.charAt(0) != '?') return null;
        // Namespace-preserving strip, matching ResourceIndex.resolveAttrInTheme's
        // key convention: "?android:attr/X" -> "android:X", "?attr/X" -> "X",
        // "?X" / "?android:X" -> as written.
        String attr;
        if (expr.startsWith("?attr/"))              attr = expr.substring(6);
        else if (expr.startsWith("?android:attr/"))  attr = "android:" + expr.substring(14);
        else                                         attr = expr.substring(1);
        if (attr.isEmpty()) return null;
        try {
            java.lang.reflect.Method mName = macapkThemeNameMethod;
            if (mName == null) {
                mName = macapkResourceIndex.getClass()
                    .getMethod("getApplicationThemeName");
                macapkThemeNameMethod = mName;
            }
            Object theme = mName.invoke(macapkResourceIndex);
            if (!(theme instanceof String)) return null;
            java.lang.reflect.Method m = macapkThemeRawMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass().getMethod(
                    "resolveAttrInTheme", String.class, String.class);
                macapkThemeRawMethod = m;
            }
            Object r = m.invoke(macapkResourceIndex, theme, attr);
            if (r instanceof String) {
                String raw = ((String) r).trim();
                // One level of chained indirection ("?a" -> "?android:a") is
                // common in the AppCompat/Material aliasing chains; follow it
                // once and stop (the cascade walk itself is cycle-guarded).
                if (raw.startsWith("?")) {
                    String next = macapkResolveThemeAttrRawOnce(raw);
                    return next != null ? next : null;
                }
                return raw.isEmpty() ? null : raw;
            }
        } catch (Throwable ignored) {}
        return null;
    }
    /** v1.0.933: Theme-taking overload of the resolver above -- chase the
     *  `?attr` chain against THE THEME the TypedArray was obtained from,
     *  mirroring macapkResolveRawToTypedValue's hop loop (kMaxIterations=20,
     *  AssetManager2), instead of the static application-theme anchor. AOSP
     *  resolves a style item's attribute reference against the theme under
     *  resolution (AttributeResolution.cpp ApplyStyle -> Theme::GetAttribute);
     *  the static anchor is only right when no Theme exists. This was the one
     *  residual pre-v913 static-anchor site: macapkRecordAt and the colour
     *  getters already thread `theme`, so getResourceId disagreed with
     *  getColor on any ContextThemeWrapper whose overlay REBINDS an attr
     *  (materialThemeOverlay: 23 of 63 corpus apps rebind, F-Droid's nav
     *  pill green-vs-blue was the visible case).
     *
     *  DEAD-END TERMINUS: when the theme walk answers null, fall back to the
     *  application-theme anchor (the 1-arg form). Our Theme.setTo copies the
     *  appliedStyles cascade LIST, and a manifest-only Activity theme has an
     *  EMPTY cascade (the manifest theme lives in ResourceIndex as a NAME;
     *  applyStyle drops framework style ids it cannot name), so a wrapper
     *  over such an Activity carries ONLY the overlay -- the un-rebound half
     *  of a chain would dead-end where AOSP's merged-table setTo still holds
     *  the base. The terminus is the SAME compensation resolveAttrRaw already
     *  applies to an empty cascade, extended to the copied-cascade case, and
     *  it preserves today's static-anchor answer everywhere the theme cannot
     *  answer. KNOWN DEVIATION: a genuinely-fresh standalone theme
     *  (newTheme+applyStyle(X)) reading an attr X never declared now answers
     *  the app theme where a device answers absent -- the same deviation
     *  window the empty-cascade compensation already owns.
     *  RETIRE both together: when applyStyle can NAME framework styles by
     *  default (macapkTypeNameOf already can under the MacapkFwView gate),
     *  every real base enters the cascade and the dead-end disappears.
     *  MACAPK_ATTR_RESID_THEME=0 restores the pre-fix static anchor (bisect
     *  gate for the 23-app recapture, not a supported mode). */
    private static final boolean MACAPK_ATTR_RESID_THEME =
        !"0".equals(System.getenv("MACAPK_ATTR_RESID_THEME"));
    static String macapkResolveThemeAttrRaw(String expr, Theme theme) {
        if (theme == null || !MACAPK_ATTR_RESID_THEME) {
            return macapkResolveThemeAttrRaw(expr);
        }
        if (expr == null) return null;
        String raw = expr.trim();
        String named = macapkNamedAttrRef(raw);   // fwxml: "?0x...." -> "?android:x"
        if (named != null) raw = named;
        for (int hop = 0; hop < 20 && !raw.isEmpty() && raw.charAt(0) == '?'; hop++) {
            String name;
            if (raw.startsWith("?attr/"))              name = raw.substring(6);
            else if (raw.startsWith("?android:attr/")) name = "android:" + raw.substring(14);
            else                                        name = raw.substring(1);
            if (name.isEmpty()) return null;
            String next = null;
            try { next = theme.macapkResolveAppAttrRawHonest(name); }
            catch (Throwable ignored) {}
            if (next == null) {
                // Cascade dead-end: the application-theme terminus (header).
                return macapkResolveThemeAttrRaw(raw);
            }
            next = next.trim();
            if (next.equals(raw)) return null;   // self-referential: absent
            raw = next;
        }
        return (raw.isEmpty() || raw.charAt(0) == '?') ? null : raw;
    }

    private static String macapkResolveThemeAttrRawOnce(String expr) {
        if (macapkResourceIndex == null || expr == null
                || expr.isEmpty() || expr.charAt(0) != '?') return null;
        String attr;
        if (expr.startsWith("?attr/"))              attr = expr.substring(6);
        else if (expr.startsWith("?android:attr/"))  attr = "android:" + expr.substring(14);
        else                                         attr = expr.substring(1);
        try {
            Object theme = macapkThemeNameMethod.invoke(macapkResourceIndex);
            if (!(theme instanceof String)) return null;
            Object r = macapkThemeRawMethod.invoke(macapkResourceIndex, theme, attr);
            if (r instanceof String) {
                String raw = ((String) r).trim();
                return (raw.isEmpty() || raw.startsWith("?")) ? null : raw;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static volatile java.lang.reflect.Method macapkColorExprMethod;
    /** PUBLIC (v1.0.870) so callers outside android.content.res can reach the
     *  ONE framework/app colour table instead of keeping their own. Made public
     *  for LayoutInflater.macapkResolveColorValue, which had grown a private
     *  13-entry `@android:color/*` list that shadowed this one and answered
     *  null for everything else -- the same duplicate-table divergence that
     *  v1.0.868 fixed between the theme table and resolveColorRef. */
    public static Integer macapkResolveColorExpr(String raw) {
        if (macapkResourceIndex == null || raw == null) return null;
        try {
            java.lang.reflect.Method m = macapkColorExprMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass().getMethod(
                    "macapkResolveColorExpr", String.class);
                macapkColorExprMethod = m;
            }
            Object r = m.invoke(macapkResourceIndex, raw);
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {}
        return null;
    }

    /** v1.0.92: getLayout(int) returns an XmlResourceParser pointing at
     *  the named layout. Delegates to ResourceIndex.openXml -> apktool'd
     *  layout XML stream wrapped in a parser. Material widgets call
     *  this to inflate sub-layouts (menu items, etc.). */
    public android.content.res.XmlResourceParser getLayout(int id) {
        // MACAPK_PLATFORM_LAYOUTS (default ON): a FRAMEWORK id opens its
        // compiled entry in framework-res.apk as an XmlBlock-semantics parser
        // (com.macapk.loader.axml.PlatformXml), the way AOSP's
        // loadXmlResourceParser(id, "layout") opens the XmlBlock of the file
        // the id resolves to (ResourcesImpl.java, aosp main). Typed getters
        // answer AOSP's (type, data) pairs and getAttributeNameResource the
        // compiled attr ids; the old path was a DomXmlResourceParser over a
        // DOM with every value spelled numerically ("-1", "0x10",
        // "@0x01020014"), which our text-keyed inflater could not read. =0
        // restores it. See docs/platform-layouts-axml_2026-09-26.md.
        if (MACAPK_PLATFORM_LAYOUTS && (id >>> 24) == 0x01) {
            XmlResourceParser p = macapkPlatformXmlParser(id, false);
            if (p != null) return p;
        }
        return getXml(id);
    }

    static final boolean MACAPK_PLATFORM_LAYOUTS =
        !"0".equals(System.getenv("MACAPK_PLATFORM_LAYOUTS"));
    private static volatile java.lang.reflect.Method macapkPlatformOpenMethod;
    private static volatile java.lang.reflect.Method macapkPlatformOpenLayoutMethod;

    /** PlatformXml.open(id) (any framework XML file) or, with `layoutOnly`,
     *  PlatformXml.openLayout(id) (only a res/layout* file), reflective like
     *  every com.macapk.loader call in this class; null when the id has no
     *  such compiled framework file. */
    private static XmlResourceParser macapkPlatformXmlParser(int id, boolean layoutOnly) {
        try {
            java.lang.reflect.Method m = layoutOnly ? macapkPlatformOpenLayoutMethod : macapkPlatformOpenMethod;
            if (m == null) {
                m = Class.forName("com.macapk.loader.axml.PlatformXml")
                    .getMethod(layoutOnly ? "openLayout" : "open", int.class);
                if (layoutOnly) macapkPlatformOpenLayoutMethod = m; else macapkPlatformOpenMethod = m;
            }
            Object p = m.invoke(null, id);
            return p instanceof XmlResourceParser x ? x : null;
        } catch (Throwable t) {
            return null;
        }
    }
    public android.content.res.XmlResourceParser getAnimation(int id) {
        return getXml(id);
    }

    // Per AOSP, Resources.getColor(int) was the legacy API (pre-API
    // 23); newer code uses Context.getColor or getColor(int, Theme).
    // We expose both. Frozen Bubble's HomeScreen.setBackgroundImage
    // calls getColor(int); absent, the activity hits NoSuchMethodError
    // during onCreate.
    //
    // These used to return 0 unconditionally. That silently zeroed every
    // resource-backed color: Jetpack Compose's colorResource(R.color.X)
    // text colors all resolved to 0x0 (transparent), so Compose UI drew
    // invisible text even though composition + draw both ran. Resolve
    // the id against the parsed colors.xml via ResourceIndex; fall back
    // to 0 (transparent) only when the id is genuinely not a color
    // resource, matching the historical "no resource matched" behavior.
    public int getColor(int id) { return getColor(id, null); }
    /** AOSP getColor(int, Theme) (android11-release Resources.java:1055-1073):
     *  an int-typed value answers its data verbatim; a STRING-typed value --
     *  i.e. a file-backed `res/color/<sel>.xml` -- loads the ColorStateList
     *  against `theme` and answers its default colour:
     *      final ColorStateList csl = impl.loadColorStateList(this, value, id, theme);
     *      return csl.getDefaultColor();
     *  We used to route BOTH overloads through macapkColorById, which drops
     *  the theme -- for a selector whose rows are `?attr` references that
     *  means the wildcard row can never be chased (golden: theme'd load
     *  answers the theme's colour, theme-less load answers the inflater's
     *  MAGENTA default; ours answered 0 for both). Literal-valued selectors
     *  computed the same default either way, so only theme-attr selectors
     *  moved when this landed. */
    public int getColor(int id, Theme theme) {
        android.content.res.ColorStateList csl = macapkLoadCslFile(id, theme);
        if (csl != null) return csl.getDefaultColor();
        return macapkColorById(id, 0);
    }

    // ColorStateList accessors. AOSP returns the inflated <selector> color
    // list, or for a plain <color> resource a degenerate single-color list
    // (ColorStateList.valueOf). Previously these returned null unconditionally,
    // so any widget reading a themed text/tint color (TextView.setTextColor,
    // tint lists, Material disabled-state colors) NPE'd or silently lost the
    // color. We don't parse <selector> color XML through this path yet, but we
    // CAN resolve the common case -- a <color> resource id -- to a single-color
    // list, which is a faithful ColorStateList for a stateless color. Returns
    // null only when the id resolves to no color at all (AOSP throws
    // NotFoundException; null is the non-throwing default our callers tolerate).
    /** AOSP getColorStateList(int) (android11-release Resources.java:
     *  1094-1103) is exactly `getColorStateList(id, null)` (plus a log line
     *  when the result still carries unresolved theme attrs). It used to
     *  flatten straight to ColorStateList.valueOf(one colour) WITHOUT ever
     *  opening the selector file, so the theme-less overload -- the one
     *  TextView.setTextColor-era app code and our own inflation TypedArrays
     *  call -- lost every state row: fwprobe CSLNT.* measured all nine
     *  selectors answering their default for every state vector and
     *  isStateful()==0 throughout, while the theme'd CSL.* twins were
     *  already exact. */
    public android.content.res.ColorStateList getColorStateList(int id) {
        if (id == 0) return null;
        android.content.res.ColorStateList csl = macapkLoadCslFile(id, null);
        if (csl != null) return csl;
        // Inline <color> values (and colour ids R8 merged under another
        // type): a single-colour list is the faithful representation.
        int c = macapkColorById(id, MACAPK_NO_COLOR);
        if (c != MACAPK_NO_COLOR) return android.content.res.ColorStateList.valueOf(c);
        return null;
    }
    public android.content.res.ColorStateList getColorStateList(int id, Theme theme) {
        // lane layout A, 2026-08-02: load FILE-BASED selectors (`res/color/*.xml`).
        // AOSP (android13-release Resources.java:1133-1139):
        //   impl.getValue(id, value, true);
        //   return impl.loadColorStateList(this, value, id, theme);
        // which inflates the selector through ColorStateList.createFromXml(...,
        // theme). We only ever resolved INLINE <color> values, so every
        // state-styled colour -- checked/disabled/pressed text colours, icon
        // tints, and the whole family of M3 `@color/m3_*` token selectors --
        // fell through. Worse, TypedArray.getColorStateList papers over the
        // null with a BLACK single-colour list, so the failure looked like a
        // deliberate colour rather than a missing file.
        // Our ColorStateList.createFromXml already exists AND resolves `?attr`
        // rows against the Theme; it simply was never called from here.
        // See docs/canvas-ab-analyst-report-a_2026-08-02.md item 6.
        android.content.res.ColorStateList csl = macapkLoadCslFile(id, theme);
        if (csl != null) return csl;
        return getColorStateList(id);
    }

    /** The one file-backed selector loader both getColorStateList overloads
     *  and getColor(int, Theme) share. Returns null when the id is not a
     *  res/color file (an inline value, an unknown id) or the parse fails --
     *  callers keep their inline-value fallback. `theme` may be null: the
     *  inflater then leaves `?attr` rows at Color.MAGENTA, which is AOSP's
     *  null-theme answer (see ColorStateList.resolveColor). */
    /** Ids proven NOT to be file-backed colour selectors. getColor(int) sits
     *  on warm paths and now consults the selector loader first; without this
     *  memo every inline <color> lookup would re-list res/ directories per
     *  call. Negative-only on purpose: whether an id has a res/color file is
     *  theme-independent, while the PARSED list is not (its `?attr` rows
     *  resolve against the caller's theme), so positives are re-inflated per
     *  call exactly as the two-arg overload has done since 2026-08-02. */
    private final java.util.Set<Integer> macapkCslFileMiss =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private android.content.res.ColorStateList macapkLoadCslFile(int id, Theme theme) {
        if (macapkCslFileMiss.contains(id)) return null;
        // fwxml: a PLATFORM colour id is a platform file, served by id out of
        // framework-res.apk (compiled) and inflated against `theme`, as AOSP's
        // ResourcesImpl.loadComplexColorForCookie does for any cookie
        // (android14-release :1141-1190: loadXmlResourceParser(file, id, ...) ->
        // ColorStateList.createFromXmlInner(wrapper, parser, attrs, theme)).
        // The by-NAME branch below opens the APP's res/color/<name>.xml, which
        // for a framework id is at best absent (the selector then flattened to
        // one colour: @android:color/text_color_primary came back a single
        // black row for a dark theme) and at worst an unrelated app file that
        // shares the name.
        if (MACAPK_FW_XML_NUMERIC && (id >>> 24) == 0x01) {
            android.content.res.ColorStateList fw = macapkLoadFwCslFile(id, theme);
            if (fw == null) macapkCslFileMiss.add(id);
            return fw;
        }
        try {
            String[] tn = macapkTypeNameOf(id);
            if (tn != null && "color".equals(tn[0])) {
                java.io.InputStream in = macapkOpenResFileByName("color", tn[1]);
                if (in != null) {
                    try {
                        org.xmlpull.v1.XmlPullParser p = macapkNewPullParser(in);
                        if (p != null) {
                            return android.content.res.ColorStateList.createFromXml(this, p, theme);
                        }
                    } finally { try { in.close(); } catch (Throwable ignored) {} }
                }
            }
        } catch (Throwable ignored) {}
        macapkCslFileMiss.add(id);
        return null;
    }

    /** The platform <selector> behind framework colour `id`, or null when the id
     *  is a plain colour VALUE (no file), not a colour, or not a selector. The
     *  miss is theme-independent, so the caller memoises it. */
    private android.content.res.ColorStateList macapkLoadFwCslFile(int id, Theme theme) {
        try {
            String[] tn = macapkTypeNameOf(id);
            if (tn == null || !"color".equals(tn[0])) return null;
            // A file entry settles to TYPE_STRING carrying its path
            // ("res/color/text_color_primary.xml"); a colour VALUE settles to a
            // colour int and has no file to open.
            Object[] fv = macapkFwSimpleValue(id);
            if (fv == null || !(fv[0] instanceof Integer ft)
                    || ft != android.util.TypedValue.TYPE_STRING
                    || !(fv[2] instanceof CharSequence path)
                    || !path.toString().endsWith(".xml")) return null;
            org.w3c.dom.Document doc = macapkFwXmlDoc(id);   // the parse memo
            if (doc == null) {
                byte[] bytes;
                try (java.io.InputStream in = openRawResource(id)) { bytes = in.readAllBytes(); }
                if (bytes.length < 4 || bytes[0] != 0x03 || bytes[1] != 0x00) return null;
                doc = macapkParseCompiledXml(bytes);
            }
            Class<?> impl = Class.forName("com.macapk.loader.DomXmlResourceParser");
            org.xmlpull.v1.XmlPullParser p = (org.xmlpull.v1.XmlPullParser) impl
                .getDeclaredConstructor(org.w3c.dom.Document.class).newInstance(doc);
            return android.content.res.ColorStateList.createFromXml(this, p, theme);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Default ON; `MACAPK_FW_XML_CACHE=0` re-reads and re-parses every
     *  framework XML entry per call, the pre-change cost (see macapkFwXmlDoc). */
    static final boolean MACAPK_FW_XML_CACHE =
        !"0".equals(System.getenv("MACAPK_FW_XML_CACHE"));

    /** Framework XML entries by their in-apk path ("res/color/text_color_primary.xml"):
     *  the compiled bytes, and the DOM macapkParseCompiledXml made of them. */
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]> sMacapkFwXmlBytes =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, org.w3c.dom.Document> sMacapkFwXmlDocs =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The in-apk path of platform XML file `id` ("res/drawable/x.xml"), or null
     *  when `id` is not a file-backed platform .xml entry or the memo is off.
     *  It is the CONFIG-SELECTED leaf (macapkFwValueOf chases the reference
     *  chain and picks the configuration's entry), so a night / density /
     *  version variant is its own key. */
    private String macapkFwXmlPath(int id) {
        if (!MACAPK_FW_XML_CACHE || (id >>> 24) != 0x01) return null;
        Object[] fv = macapkFwSimpleValue(id);
        if (fv == null || !(fv[0] instanceof Integer ft)
                || ft != android.util.TypedValue.TYPE_STRING
                || !(fv[2] instanceof CharSequence cs)) return null;
        final String path = cs.toString();
        return path.startsWith("res/") && path.endsWith(".xml") ? path : null;
    }

    /** A private copy of the parsed DOM of platform XML file `id`
     *  (macapkParseCompiledXml's output), or null when `id` is not a compiled
     *  platform .xml entry or the memo is off -- the caller then opens and
     *  parses it itself, exactly as before.
     *
     *  Why: every platform file is served by ResourceIndex.macapkFwOpenRaw,
     *  which opens the framework-res.apk as a ZipFile per call (~0.5 ms,
     *  measured offline with a warm JIT), and BinaryXmlParser.parse then costs
     *  ~40-70 us. A platform colour selector is inflated on every
     *  getColorStateList(id, theme) -- TextView's text colours reach one up to
     *  six times per constructor (?android:textColorPrimary ->
     *  @android:color/text_color_primary) -- and the drawable and getXml
     *  platform paths pay the same. framework-res is immutable for the
     *  process, so the file is read and parsed ONCE per path; each caller gets
     *  a deep clone (~5 us), because the inflaters and DomXmlResourceParser
     *  walk the DOM and a Xerces DOM keeps mutable node-list caches, so one
     *  shared tree is not safe across threads (the clone is taken under the
     *  master's lock for the same reason). AOSP's analogue is the asset cache
     *  under AssetManager.openXmlBlockAsset (the XmlBlock is parsed once) plus
     *  ResourcesImpl's per-theme ComplexColor cache; the INFLATED list is not
     *  cached here, since its `?attr` rows depend on the caller's theme. */
    private org.w3c.dom.Document macapkFwXmlDoc(int id) {
        final String path = macapkFwXmlPath(id);
        if (path == null) return null;
        org.w3c.dom.Document master = sMacapkFwXmlDocs.get(path);
        if (master == null) {
            byte[] bytes = sMacapkFwXmlBytes.get(path);
            if (bytes == null) {
                try (java.io.InputStream in = openRawResource(id)) {
                    bytes = in.readAllBytes();
                } catch (Throwable t) {
                    return null;
                }
                sMacapkFwXmlBytes.putIfAbsent(path, bytes);
            }
            if (bytes.length < 4 || bytes[0] != 0x03 || bytes[1] != 0x00
                    || bytes[2] != 0x08 || bytes[3] != 0x00) return null;   // not RES_XML_TYPE
            try {
                master = macapkParseCompiledXml(bytes);
            } catch (Throwable t) {
                return null;
            }
            if (master == null || master.getDocumentElement() == null) return null;
            org.w3c.dom.Document prev = sMacapkFwXmlDocs.putIfAbsent(path, master);
            if (prev != null) master = prev;
        }
        synchronized (master) {
            return (org.w3c.dom.Document) master.cloneNode(true);
        }
    }

    /** Open the app's `<type>/<name>` file resource (config-selected, from
     *  the installed apk set's rows -- v1.0.1590; this used to list the
     *  decoded `_apkt/res/<type>-*` directories, which no longer exist). */
    private java.io.InputStream macapkOpenResFileByName(String type, String name) {
        try {
            Object ri = macapkGetResourceIndex();
            if (ri == null) return null;
            Object in = ri.getClass().getMethod("openRawByName", String.class, String.class)
                .invoke(ri, type, name);
            return in instanceof java.io.InputStream s ? s : null;
        } catch (Throwable ignored) {}
        return null;
    }
    /** A namespace-aware pull parser positioned at the document start. */
    private static org.xmlpull.v1.XmlPullParser macapkNewPullParser(java.io.InputStream in) {
        try {
            org.xmlpull.v1.XmlPullParserFactory f =
                org.xmlpull.v1.XmlPullParserFactory.newInstance();
            f.setNamespaceAware(true);
            org.xmlpull.v1.XmlPullParser p = f.newPullParser();
            p.setInput(in, null);
            return p;
        } catch (Throwable t) { return null; }
    }
    // Sentinel distinguishing "resolved to color 0 (transparent)" from "no
    // color resource matched". 0 is a legitimate ARGB value (fully
    // transparent), so we cannot use it as the not-found marker.
    private static final int MACAPK_NO_COLOR = 0x00BADC01;

    // Boolean / float numeric accessors common in resources XML.
    private static volatile java.lang.reflect.Method macapkBoolByIdMethod;
    /** AOSP Resources.getBoolean: resolve the bool resource value
     *  (config-qualified via ResourceIndex). Was a `return false` stub, which
     *  forced every feature-flag bool false -- e.g. Calculator's toolbar history
     *  button is gated on R.bool.feature_history (false in values/ but true in
     *  values-h320dp, the config the calc runs in), so the button stayed gone.
     *  Returns false when the id isn't a known bool (AOSP throws NotFoundException,
     *  but false is the safe non-throwing default our callers tolerate). */
    public boolean getBoolean(int id) {
        if (macapkResourceIndex == null) return false;
        try {
            java.lang.reflect.Method m = macapkBoolByIdMethod;
            if (m == null) {
                m = macapkResourceIndex.getClass().getMethod("getBoolById", int.class);
                macapkBoolByIdMethod = m;
            }
            Object r = m.invoke(macapkResourceIndex, id);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {}
        return false;
    }
    public float getFloat(int id) { return 0f; }
    public float getFraction(int id, int base, int pbase) {
        // AOSP Resources.getFraction (Resources.java:1130, android11-release):
        // getValue then complexToFraction(data, base, pbase) -- %  multiplies
        // base, %p multiplies pbase (TypedValue.COMPLEX_UNIT_FRACTION[_PARENT]).
        // Was a `return 0f` stub: every caller read 0 for both forms.
        android.util.TypedValue tv = new android.util.TypedValue();
        try { getValue(id, tv, true); } catch (Throwable t) { return 0f; }
        if (tv.type == android.util.TypedValue.TYPE_FRACTION) {
            return android.util.TypedValue.complexToFraction(tv.data, base, pbase);
        }
        return 0f;
    }

    // AOSP Resources expose four ID -> name lookups. Apps use these
    // to construct content:// URIs, to build asset paths from
    // resource ids, or to log a friendly name during init. Mirrors
    // frameworks/base/core/java/android/content/res/Resources.java's
    // public surface: each method throws NotFoundException if the id
    // is invalid. We never have full arsc parsing here, so the
    // entry/type lookups return synthesized strings keyed on the
    // numeric id when the resource index can't resolve them; the
    // package name routes to the runtime APK's package for 0x7f
    // ids and "android" for 0x01 (framework) ids, matching the
    // resource-id namespacing baked into aapt.
    public String getResourcePackageName(int id) {
        // High byte of resource id encodes the package. 0x01 = the
        // android framework package; 0x7f = the app's own package.
        // Anything else (0x02-0x7e, library) we return the app
        // package as a best-effort fallback.
        int pkgId = (id >>> 24) & 0xff;
        if (pkgId == 0x01) return "android";
        return macapkPackageName;
    }
    public String getResourceTypeName(int id) {
        // macapkTypeNameOf serves the app table AND the framework table's
        // public map (the platform resolution view), so
        // android.R ids finally answer their real type. AOSP answers "attr"
        // for android.R.attr.colorPrimary etc.; we answered "" for every
        // framework id because this went through the app-only typeNameOf
        // (fwprobe NAME.getResourceTypeName.fw.*, 9 rows).
        String[] tn = macapkTypeNameOf(id);
        if (tn != null && tn[0] != null && !tn[0].isEmpty()) return tn[0];
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass().getMethod("typeNameOf", int.class);
                Object n = m.invoke(macapkResourceIndex, id);
                if (n != null) return n.toString();
            } catch (Throwable ignored) {}
        }
        return "";
    }
    public String getResourceEntryName(int id) {
        // Same routing as getResourceTypeName above: the fw-aware map first
        // (framework colours/strings/styles get their real entry names under
        // the gate), then the legacy entryNameOf (app table + the curated
        // framework-attr rows that answer gate-off).
        String[] tn = macapkTypeNameOf(id);
        if (tn != null && tn[1] != null && !tn[1].isEmpty()) return tn[1];
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass().getMethod("entryNameOf", int.class);
                Object n = m.invoke(macapkResourceIndex, id);
                if (n != null) return n.toString();
            } catch (Throwable ignored) {}
        }
        // Stable synthesized fallback. Apps that build paths from
        // this string get something deterministic per-id rather
        // than a NullPointerException.
        return "id_0x" + Integer.toHexString(id);
    }
    public String getResourceName(int id) {
        return getResourcePackageName(id) + ":"
             + getResourceTypeName(id)    + "/"
             + getResourceEntryName(id);
    }

    // Reverse of getResourceName: look up a resource id by name.
    // AOSP returns 0 when the resource doesn't exist; callers like
    // FirebaseOptions.fromResource and StringResourceValueReader
    // treat a 0 result as "absent" and continue without crashing.
    // We route through the apktool resource index when available
    // and otherwise return 0 -- mirroring the AOSP "missing entry"
    // contract.
    public int getIdentifier(String name, String defType, String defPackage) {
        if (name == null || name.isEmpty()) return 0;
        if (macapkResourceIndex != null) {
            try {
                java.lang.reflect.Method m = macapkResourceIndex.getClass()
                    .getMethod("idOfName", String.class, String.class, String.class);
                Object id = m.invoke(macapkResourceIndex, name, defType, defPackage);
                if (id instanceof Integer) return (Integer) id;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    public Theme newTheme() { return new Theme(); }

    // AOSP Resources.getSystem() returns the GLOBAL system Resources (type
    // Resources, NOT Theme -- the old stub's return type was wrong, so callers
    // like protobuf/AndroidX utilities that do
    // `Resources.getSystem().getDisplayMetrics()` hit
    // NoSuchMethodError: 'Resources Resources.getSystem()'). Return a lazily
    // built system Resources carrying default (xhdpi) DisplayMetrics so dp/sp
    // conversions against it are sane.
    private static volatile Resources sSystem;
    public static Resources getSystem() {
        Resources r = sSystem;
        if (r == null) {
            synchronized (Resources.class) {
                r = sSystem;
                if (r == null) {
                    DisplayMetrics dm = new DisplayMetrics();
                    dm.setToDefaults();
                    // The phone defaults until Boot's bind push (see
                    // updateSystemConfiguration), not the unset delta that
                    // `new Configuration()` is under MACAPK_CONFIG_UNSET_CTOR.
                    r = sSystem = new Resources(null, dm, Configuration.macapkPhoneDefaults());
                }
            }
        }
        return r;
    }

    /** Default ON. `MACAPK_SYSTEM_RES_FOLLOW=0` makes updateSystemConfiguration
     *  a no-op again, so Resources.getSystem() keeps the frozen
     *  DisplayMetrics.setToDefaults() answer (720x1280, orientation 0, sw 0)
     *  from boot onward -- the exact pre-change path, for a one-binary bisect. */
    public static final boolean MACAPK_SYSTEM_RES_FOLLOW =
        !"0".equals(System.getenv("MACAPK_SYSTEM_RES_FOLLOW"));

    /** AOSP Resources.updateSystemConfiguration (android14-release
     *  Resources.java:2189-2196, a hidden public static):
     *      public static void updateSystemConfiguration(Configuration config,
     *              DisplayMetrics metrics, CompatibilityInfo compat) {
     *          if (mSystem != null) {
     *              mSystem.updateConfiguration(config, metrics, compat);
     *          }
     *      }
     *  ResourcesManager.applyConfigurationToResources (:1368-1369) calls it with
     *  the process configuration and the APP-AREA display metrics
     *  (`getDisplayMetrics(config)` -> DisplayInfo.getAppMetrics) at bind
     *  (ActivityThread.handleBindApplication :6793-6799, "Update the system
     *  configuration since its preloaded and might not reflect configuration
     *  changes") and on every global configuration change. Nothing here ever
     *  did the equivalent, so getSystem() answered 720x1280 for the life of the
     *  process. MEASURED victim: Solitaire CG sizes every card from
     *  `Resources.getSystem().getDisplayMetrics().widthPixels`
     *  (SolitaireView.<init> -> Card.SetSize), so on the 3360px tablet its cards
     *  were 63px wide where Android draws 357px (and 65 vs 69 on the phone).
     *  docs/fleet-canvas-games-solitaires-feudal_2026-09-26.md F1.
     *
     *  Deliberate deviation: AOSP null-checks mSystem because the zygote
     *  preloads it (ZygoteInit.preloadResources `mResources =
     *  Resources.getSystem()`), so it always exists by bind. Ours is lazy, so
     *  this CREATES it rather than skipping the update: skipping would leave the
     *  first later getSystem() at the frozen defaults, which is the bug.
     *  updateConfiguration updates the metrics and configuration IN PLACE (the
     *  metrics by setTo, the configuration by AOSP's updateFrom merge since
     *  MACAPK_RES_CONFIG_MERGE -- every caller here passes a fully populated
     *  configuration, for which the merge and the old copy agree), so a caller
     *  that cached getSystem().getDisplayMetrics() sees the new values, as on
     *  AOSP. We have no CompatibilityInfo; `compat` is carried as Object, like
     *  the 3-arg updateConfiguration it forwards to.
     *
     *  Callers: Boot at bind (after the app Resources are built, and before any
     *  app code runs, so its `cfg` IS the process configuration). The two later
     *  pushes -- DisplayConfig after a display commit, Boot's live uiMode flip --
     *  go through macapkEditSystemConfiguration below instead, because the
     *  configuration they hold is the APP's. */
    public static void updateSystemConfiguration(Configuration config, DisplayMetrics metrics,
                                                 Object compat) {
        if (!MACAPK_SYSTEM_RES_FOLLOW) return;
        getSystem().updateConfiguration(config, metrics, compat);
    }

    /** A LATER process-level change, pushed into the system Resources the way
     *  AOSP pushes it: `edit` changes only the fields the event changes, on a
     *  COPY OF THE SYSTEM CONFIGURATION ITSELF, and the result goes through
     *  updateSystemConfiguration.
     *
     *  Why not pass the caller's configuration straight through: AOSP
     *  ConfigurationController.handleConfigurationChanged (android14-release
     *  :146-182) hands applyConfigurationToResources the PROCESS-GLOBAL
     *  configuration from the system server, and
     *  ResourcesManager.applyConfigurationToResources (:1337-1369) pushes exactly
     *  that into `Resources.updateSystemConfiguration(config, displayMetrics,
     *  compat)`. What our DisplayConfig and Boot's appearance flip hold is
     *  different: they build `next` from the APP's Resources configuration,
     *  which carries whatever the app wrote into it with
     *  Resources.updateConfiguration -- most often a per-app locale (Aegis
     *  AegisActivity.onCreate does it whenever its language preference is not
     *  "system"). Pushing that into getSystem() would report the app's own
     *  locale as the SYSTEM locale after the first window resize or appearance
     *  flip, and apps that read getSystem().getConfiguration() to learn the
     *  device language (Aegis itself to go back to "system", AnkiDroid's
     *  GeneralSettingsFragment, F-Droid's org.fdroid.database.AppKt) would stay
     *  in the wrong language. Our system configuration is seeded from the bind
     *  configuration and only ever changed by these edits, so it stays the
     *  process configuration.
     *  Gated with the rest of the follow (MACAPK_SYSTEM_RES_FOLLOW=0: a no-op,
     *  `edit` is not even run). Synchronized so two edits cannot interleave
     *  their copy and their push; both callers run on the owner thread today. */
    public static void macapkEditSystemConfiguration(
            java.util.function.Consumer<Configuration> edit, DisplayMetrics metrics) {
        if (!MACAPK_SYSTEM_RES_FOLLOW) return;
        synchronized (MACAPK_SYSTEM_EDIT_LOCK) {
            final Resources sys = getSystem();
            final Configuration next = new Configuration(sys.getConfiguration());
            if (edit != null) edit.accept(next);
            updateSystemConfiguration(next, metrics, null);
        }
    }
    private static final Object MACAPK_SYSTEM_EDIT_LOCK = new Object();

    /** Theme is an INNER (non-static) class in AOSP so getResources()
     *  can return Resources.this. Glide's DrawableDecoderCompat.loadDrawableV7
     *  calls theme.getResources() to acquire the Resources for
     *  Resources.getDrawableForDensity; making Theme non-static gives
     *  us the same observable behavior. AOSP:
     *    https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/content/res/Resources.java
     *    (search for "public final class Theme" + "public Resources getResources()") */
    /** Mirror of android.view.ContextThemeWrapper.MACAPK_VIEW_THEME (default
     *  OFF). Read from the environment here rather than importing the view
     *  class so android.content.res keeps no dependency on android.view; it is
     *  the SAME env var and must stay in step with the canonical declaration. */
    static final boolean MACAPK_VIEW_THEME_FLAG = macapkViewThemeFlag();
    private static boolean macapkViewThemeFlag() {
        // lane layout, 2026-08-01: default ON, matching the canonical
        // declaration in android.view.ContextThemeWrapper (see its comment
        // for the measured rationale). MACAPK_VIEW_THEME=0 opts out.
        String v = System.getenv("MACAPK_VIEW_THEME");
        if (v == null || v.isEmpty()) return true;
        return !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    /** AOSP :2541 -- `mResourcesImpl.finishPreloading()`. On a device this
     *  ends the zygote's drawable/colour preload phase and freezes the
     *  preloaded caches; it is called once by the zygote, never by an app.
     *  There is no preload phase here (nothing is warmed before the app
     *  starts), so there is nothing to finish. */
    public final void finishPreloading() { }

    /** AOSP :1520 -- the resource id of the XML file an AttributeSet was
     *  inflated FROM (used by the layout inspector to point at a source file).
     *  AOSP reads it out of the native XmlBlock; our AttributeSet
     *  implementations carry no source id, so ID_NULL -- which is AOSP's own
     *  answer for an AttributeSet that did not come from a resource file. */
    public static int getAttributeSetSourceResId(android.util.AttributeSet set) {
        return ID_NULL;
    }

    /** AOSP :2878 -- registers extra APK paths for a shared library's
     *  resources, and is guarded upstream by a build flag:
     *      if (Flags.registerResourcePaths()) { ...ResourcesManager... }
     *      else throw new UnsupportedOperationException("Flag ... is disabled.");
     *  Mac.apk has no ResourcesManager and no multi-APK resource path list, so
     *  it is in the flag-disabled state -- and AOSP's own behaviour there is
     *  the throw, not a silent success. Accepting the call and registering
     *  nothing would leave the caller believing a library's resources are
     *  reachable when they are not. */
    public static void registerResourcePaths(String uniqueId,
                                             android.content.pm.ApplicationInfo appInfo) {
        throw new UnsupportedOperationException(
                "Flag android.content.res.register_resource_paths is disabled.");
    }

    /** MACAPK_THEME_APP_ATTR_CASCADE (default ON): see
     *  Theme.macapkResolveAppAttrRawHonest. =0 restores the per-style cascade
     *  walk for every caller, exactly as before. */
    static final boolean MACAPK_SELECTOR_APP_STATES =
        !"0".equals(System.getenv("MACAPK_SELECTOR_APP_STATES"));
    static final boolean MACAPK_THEME_SELECTOR_ATTR_ROW =
        !"0".equals(System.getenv("MACAPK_THEME_SELECTOR_ATTR_ROW"));
    static final boolean MACAPK_THEME_APP_ATTR_CASCADE =
        !"0".equals(System.getenv("MACAPK_THEME_APP_ATTR_CASCADE"));
    /** ResourceIndex.resolveAttrInThemeExact, looked up once (the same cached
     *  reflective-handle idiom as macapkThemeRawMethod): the app-attr path runs
     *  inside every resolveAttribute a widget constructor makes. */
    private static volatile java.lang.reflect.Method macapkThemeExactMethod;
    /** StyleBackedAttributeSet answers an app-package (res-auto) slot by the
     *  app attribute's identity only; see its getAttributeValue. Off under
     *  MACAPK_ATTR_IDENTITY=0 too, so that gate keeps restoring every
     *  pre-identity answer. */
    static final boolean MACAPK_STYLE_APP_SLOT_EXACT =
        !"0".equals(System.getenv("MACAPK_ATTR_IDENTITY"))
        && !"0".equals(System.getenv("MACAPK_STYLE_APP_SLOT_EXACT"));
    private static final String MACAPK_NS_RES_AUTO =
        "http://schemas.android.com/apk/res-auto";

    public final class Theme {
        // v1.0.128: per-Theme applied-style stack. Real AOSP keeps the
        // theme as a native AssetManager-side data structure with each
        // applied style merged into a flat attribute table. We mirror
        // the contract with a lightweight cascade: each applyStyle
        // pushes a style NAME (resolved via the ResourceIndex from the
        // resId), and resolveAttribute walks the most-recently-pushed
        // first looking for an item with the matching attr name.
        //
        // Generic: works for any APK whose AppCompatActivity /
        // ContextThemeWrapper applies a manifest theme + per-Activity
        // overrides. Token cost: a few strings per Theme instance.
        private final java.util.LinkedList<String> appliedStyles =
            new java.util.LinkedList<>();

        public void applyStyle(int resId, boolean force) {
            if (macapkResourceIndex == null) return;
            try {
                java.lang.reflect.Method mTypeName =
                    macapkResourceIndex.getClass().getMethod(
                        "macapkTypeNameOf", int.class);
                Object tn = mTypeName.invoke(macapkResourceIndex, resId);
                // v1.0.893: TYPE GUARD. We pushed `((String[]) tn)[1]` for ANY
                // id that mapped to any entry, so a non-style resource whose
                // name happened to look like a theme entered the cascade as one.
                // Both siblings guard and this one did not:
                //   ResourceIndex.styleNameForIdRef -- `if (... "style".equals(tn[0])) return tn[1];`
                //   Resources.resolveAttribute      -- `if (!"attr".equals(attrType)) return false;`
                // It compounds with frameworkThemeAttr, which admits any name
                // equal to "Theme" or starting with "Theme." -- so a mis-typed
                // entry named Theme.* would additionally be served a synthetic
                // full Material platform theme.
                String styleName = (tn instanceof String[]
                        && ((String[]) tn).length >= 2
                        && "style".equals(((String[]) tn)[0]))
                    ? ((String[]) tn)[1] : null;
                // v1.0.886 probe. Two suspected defects live on this line and
                // only a run can tell them apart:
                //   (a) NO TYPE GUARD -- we push whatever ((String[]) tn)[1] is
                //       without checking tn[0].equals("style"), so any id that
                //       maps to any entry becomes a theme. Both siblings guard:
                //       ResourceIndex.styleNameForIdRef:866 and
                //       Resources.resolveAttribute (`if (!"attr".equals(...))`).
                //   (b) `force` IS IGNORED (see the comment below) -- a
                //       ThemeOverlay that legitimately sets three attributes
                //       hijacks every attribute of the theme, and AppCompat and
                //       Material push overlays constantly.
                // Logging tn[0] tests (a); logging force tests (b).
                if (System.getenv("MACAPK_TRACE_WINDOW_FLAGS") != null) {
                    try {
                        com.macapk.loader.NativeLog.i("Mac.apk.Theme",
                            "applyStyle id=0x" + Integer.toHexString(resId)
                            + " type=" + ((tn instanceof String[]) ? ((String[]) tn)[0] : "?")
                            + " name=" + styleName
                            + " force=" + force);
                    } catch (Throwable ignored2) {}
                }
                if (styleName != null && !styleName.isEmpty()) {
                    // v1.0.893: HONOUR `force`. The list is a priority stack and
                    // resolution walks it head-to-tail, so head == highest.
                    // AOSP (AssetManager2.cpp:1653-1655, and the header doc at
                    // AssetManager2.h:556-558):
                    //   "- normal way never overrides the existing attribute, so
                    //      only unique style attributes are added
                    //    - forced way overrides anything for that attribute"
                    // implemented as `if (force || IsUndefined(entry_it->value))`.
                    // So force=true must OUTRANK everything already applied
                    // (addFirst) and force=false must only fill what nothing has
                    // set yet, i.e. rank BELOW everything already applied
                    // (addLast). We did addFirst unconditionally -- correct for
                    // force=true, exactly backwards for force=false, and the
                    // comment here admitted it.
                    // Verified against AOSP priority for every ordering:
                    //   A(t) B(t) C(t) -> C>B>A  ours [C,B,A]
                    //   A(f) B(f) C(f) -> A>B>C  ours [A,B,C]
                    //   A(t) B(f) C(t) -> C>A>B  ours [C,A,B]
                    //   A(f) B(t) C(f) -> B>A>C  ours [B,A,C]
                    // LIVE IMPACT TODAY: ZERO, and deliberately landed anyway.
                    // All three of our own applyStyle call sites pass true
                    // (Context.java:119, :158, ContextThemeWrapper.java:139), as
                    // do AppCompat's ContextThemeWrapper and Material's
                    // MaterialThemeOverlay. Only an app calling
                    // getTheme().applyStyle(id, false) itself is affected -- and
                    // when one does, the attribution should already be clean.
                    // KNOWN RESIDUAL, not chased: AOSP's force=true with an
                    // UNDEFINED (@empty) value DELETES the attribute. We have no
                    // undefined representation, so that case still leaves a lower
                    // style's value standing.
                    if (force) appliedStyles.addFirst(styleName);
                    else       appliedStyles.addLast(styleName);
                }
            } catch (Throwable ignored) {}
        }

        public boolean resolveAttribute(int resId, android.util.TypedValue outValue, boolean resolveRefs) {
            // MACAPK_TRACE_RESOLVEATTR=<name|*>: Theme.resolveAttribute is a DIFFERENT path from
            // TypedArray, so MACAPK_TRACE_ATTR is blind to it -- and it is the path androidx uses
            // for the splash-screen handoff (SplashScreen.Impl.install reads
            // R.attr.postSplashScreenTheme here and only calls activity.setTheme() if this returns
            // true). A silent false there strands the app on its SPLASH theme for the rest of the
            // session, which is invisible in every log we had.
            final String trAttr = System.getenv("MACAPK_TRACE_RESOLVEATTR");
            if (macapkResourceIndex == null) {
                if (trAttr != null) com.macapk.loader.NativeLog.i("Mac.apk.ResolveAttr",
                    "resolveAttribute(0x" + Integer.toHexString(resId) + ") -> NO INDEX");
                return false;
            }
            if (outValue == null) return false;
            try {
                // Map the attribute resource id to its name. The
                // ResourceIndex's idToTypeName covers app-declared attrs;
                // for framework attrs (0x0101....) we don't have a name
                // table, so those still return false. Material custom
                // attrs (in F-Droid's 0x7f04xxxx range) DO resolve.
                java.lang.reflect.Method mTypeName =
                    macapkResourceIndex.getClass().getMethod(
                        "macapkTypeNameOf", int.class);
                Object tn = mTypeName.invoke(macapkResourceIndex, resId);
                if (!(tn instanceof String[])) return false;
                String attrType = ((String[]) tn)[0];
                String attrName = ((String[]) tn)[1];
                final boolean trHit = trAttr != null
                    && ("*".equals(trAttr) || trAttr.equals(attrName));
                if (!"attr".equals(attrType)) {
                    if (trHit) com.macapk.loader.NativeLog.i("Mac.apk.ResolveAttr",
                        attrName + ": type is '" + attrType + "', not attr -> false");
                    return false;
                }

                // Walk this Theme's applied-style cascade, head-first
                // (most-recent applyStyle wins). v1.0.913: through the
                // HONEST lookup -- a populated cascade answers for itself;
                // the application-theme fallback only compensates for a
                // theme our wiring never populated (see
                // macapkResolveAttrRawHonest for the measured reason).
                final boolean fallbackSourced = !macapkHasAppliedStyles();
                // An APP attribute (not 0x01) is keyed by its own identity
                // across the whole cascade first (macapkResolveAppAttrRawHonest).
                String raw = ((resId >>> 24) != 0x01)
                    ? macapkResolveAppAttrRawHonest(attrName)
                    : macapkResolveAttrRawHonest(attrName);
                if (trHit) com.macapk.loader.NativeLog.i("Mac.apk.ResolveAttr",
                    attrName + " (0x" + Integer.toHexString(resId) + "): raw=" + raw
                    + " hasAppliedStyles=" + !fallbackSourced);
                if (raw == null) return false;
                // v1.0.917: capture the last reference the theme walk just
                // followed (the framework view's settled scalar items record
                // it; resolveAttrInTheme cleared the slot on entry, so a
                // literal answer reads 0). Read IMMEDIATELY, before anything
                // else can query the index. AOSP counterpart: the ref
                // out-param threaded from Theme::GetAttribute's caller
                // through ResolveReference into TypedValue.resourceId
                // (android_util_AssetManager.cpp NativeThemeGetAttributeValue,
                // android11-release, seeds `uint32_t ref = 0u`).
                final int rawOrigin = macapkReadValueOrigin();
                raw = raw.trim();
                if (fallbackSourced) {
                    // COMPENSATION-SOURCED answer: keep the pre-v1.0.913
                    // conservative shape surface. The old packer only ever
                    // served references, colours and dimension-shaped values
                    // from this fallback; plain literals (strings, ints,
                    // bools, floats, enums) always answered "not defined",
                    // and BOTH the fleet's manifest-only apps and Android's
                    // own unthemed contexts are built on that (the probe's
                    // application-context anchor answers ok=0 for app attrs
                    // on the emulator; packing the manifest theme's
                    // "dark-string" here measurably flipped that agreeing
                    // row). Full-fidelity packing applies once a theme is
                    // actually modelled -- and once the manifest theme is
                    // applied to the cascade at activity creation, this
                    // branch stops firing on activities entirely.
                    boolean ok = macapkResolveRawToTypedValue(
                            raw, attrName, this, resolveRefs, outValue);
                    if (!ok) return false;
                    int t = outValue.type;
                    boolean legacyShape = outValue.resourceId != 0
                        || t == android.util.TypedValue.TYPE_REFERENCE
                        || (t >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                            && t <= android.util.TypedValue.TYPE_LAST_COLOR_INT)
                        || t == android.util.TypedValue.TYPE_DIMENSION;
                    return legacyShape;
                }

                // v1.0.913: delegate the raw -> TypedValue step to the ONE
                // resolver (macapkResolveRawToTypedValue above). What that
                // replaces, and why each replacement is the fix and not a
                // drive-by (all measured by debug/fwprobe.sh against the
                // emulator golden):
                //  - the `?attr` hop chain used to consult ONLY the
                //    application theme, so a hop inside an applyStyle'd
                //    theme answered from the WRONG theme (probeColorHop1
                //    read ProbeDark's value while the emulator reads the
                //    cascade's); hops now walk this Theme's cascade first,
                //    and they run under resolveRefs=false too, which is
                //    Theme::GetAttribute's real behavior.
                //  - dimensions were TYPE_DIMENSION with string="24.0dp"
                //    and data=0, so complexToDimensionPixelSize(data) gave
                //    ZERO; data now carries aapt's complex encoding.
                //  - a file-backed `@color/selector.xml` was flattened to a
                //    single colour int; the emulator answers TYPE_STRING +
                //    the res/color path + resourceId (the deskclock v899
                //    class of bug, at this call site).
                //  - TypedValue.resourceId was never set for followed
                //    references; callers use it to tell "from a resource"
                //    apart from a literal.
                //  - plain string / int / bool / float / enum / flag theme
                //    values answered "not defined"; they now answer typed,
                //    which is what Material's MotionUtils easing path needs
                //    (it REQUIRES a TYPE_STRING result for cubic-bezier).
                // The v1.0.834 reference semantics (Wikipedia's white-screen
                // fix; the MotionUtils leaf-type contract) moved INTO the
                // shared resolver verbatim: @string refs answer TYPE_STRING
                // with the value, file-backed refs TYPE_STRING with the path,
                // bags stay TYPE_REFERENCE with data=resourceId=id, and an
                // unservable framework ref is an honest TYPE_REFERENCE 0.
                // v1.0.917: the threading shape reports the last followed
                // reference in resourceId (rawOrigin for settled literals,
                // the chain terminus for served references), the AOSP
                // CopyValue contract.
                boolean ok = macapkResolveRawToTypedValue(raw, attrName, this,
                                                    resolveRefs, outValue,
                                                    true, rawOrigin);
                if (trHit) com.macapk.loader.NativeLog.i("Mac.apk.ResolveAttr",
                    attrName + " -> ok=" + ok + " type=0x" + Integer.toHexString(outValue.type)
                    + " data=0x" + Integer.toHexString(outValue.data)
                    + " resourceId=0x" + Integer.toHexString(outValue.resourceId)
                    + " string=" + outValue.string);
                return ok;
            } catch (Throwable t) {
                return false;
            }
        }

        /** Name-keyed sibling of resolveAttribute(int, TypedValue, boolean)
         *  above: the Theme-aware counterpart to the static
         *  Resources.macapkThemeColor(String, int), which only ever consulted
         *  the APPLICATION theme (ResourceIndex.resolveApplicationThemeColor)
         *  no matter which Theme actually held the attribute -- the root of
         *  part (d) in docs/newapps-a_2026-07-30.md /
         *  docs/attr-theme-threading_2026-07-31.md. Walks THIS Theme's
         *  applied-style cascade FIRST -- so a View-level android:theme
         *  overlay's own colour wins -- then falls back to the application
         *  theme, exactly mirroring resolveAttribute's two-tier order.
         *
         *  `attrName` is looked up directly by NAME rather than by resource
         *  id: the cascade walk underneath (ResourceIndex.resolveAttrToColor /
         *  resolveAttrInTheme) is name-keyed, and going through an id would
         *  need a name -> id reverse lookup this framework does not maintain
         *  for framework ("android:"-namespaced) attrs.
         *
         *  No new resolution logic -- reuses ResourceIndex.resolveAttrToColor
         *  / resolveApplicationThemeColor verbatim, the same cascade-then-
         *  fallback plumbing resolveAttribute already does for the id-keyed
         *  case (including that method's OWN `?attr/` indirection chasing,
         *  which resolveAttrToColor -> resolveColorRef already performs
         *  internally). A Theme with an EMPTY cascade -- no applyStyle ever
         *  pushed a style, the default when MACAPK_VIEW_THEME is off, or for
         *  a bare Activity theme -- returns EXACTLY what the static
         *  macapkThemeColor(attrName, fallback) returns, so every existing
         *  caller of the theme==null overload is unaffected. */
        public int resolveAttrColor(String attrName, int fallback) {
            if (macapkResourceIndex == null || attrName == null) return fallback;
            Integer inCascade = macapkResolveAttrColorInCascade(attrName);
            if (inCascade != null) return inCascade;
            // UNCHANGED terminal behaviour. Keeping it means the walk above can
            // only ADD a resolution, never remove one that works today -- the same
            // discipline docs/attr-theme-threading_2026-07-31.md applied when this
            // method was introduced.
            try {
                java.lang.reflect.Method mAppColor = macapkResourceIndex.getClass()
                    .getMethod("resolveApplicationThemeColor", String.class, int.class);
                Object r = mAppColor.invoke(macapkResourceIndex, attrName, fallback);
                if (r instanceof Integer) return (Integer) r;
            } catch (Throwable ignored) {}
            return fallback;
        }

        /** AOSP AssetManager2.cpp:1733 / :1141 kMaxIterations. */
        private static final int MACAPK_MAX_ATTR_HOPS = 20;

        /** AOSP's `Theme::GetAttribute`, expressed over our appliedStyles cascade.
         *  Returns null when nothing in the cascade yields a colour, so the caller
         *  keeps its previous terminal fallback.
         *
         *  WHY THIS REPLACES THE PER-STYLE resolveAttrToColor LOOP. That loop asked
         *  ResourceIndex to convert each style's value in ISOLATION, and
         *  `resolveColorRef`'s `?attr` branch re-anchors on the STATIC
         *  `applicationThemeName`, i.e. the MANIFEST theme. So a value that is
         *  itself a `?attr` got resolved against a theme the caller is not using.
         *  AOSP has no such global: `Theme::ResolveAttributeReference`
         *  (AssetManager2.cpp:1758) resolves through `this->GetAttribute`, and
         *  `GetAttribute` (:1732) chases attribute-to-attribute indirection inside
         *  THIS theme's own merged table, bounded by kMaxIterations = 20. One
         *  resolution context per Theme.
         *
         *  MEASURED victim (NewPipe, 2026-08-08). res/layout/toolbar_layout.xml's
         *  Toolbar carries `android:theme="@style/ToolbarTheme"`, whose chain ends
         *  at AppCompat's `Platform.ThemeOverlay.AppCompat` (parent=""), which
         *  declares only framework aliases: `android:colorPrimary = ?colorPrimary`,
         *  meaning "whatever the theme I am overlaid on says". Our pass-2
         *  bare-to-`android:` fallback answered the bare query from that alias, the
         *  old code then resolved `?colorPrimary` against the manifest theme
         *  `OpeningTheme` (the SPLASH theme, whose colorPrimary is deliberately
         *  `@android:color/transparent`), and the toolbar painted
         *  setBackgroundColor(0). The correct answer, the Activity theme's own
         *  colorPrimary, was sitting one entry lower in the very same cascade.
         *
         *  THE SELF-ALIAS RULE. On AOSP `android:colorPrimary` and AppCompat's
         *  `colorPrimary` are different resource ids, so chasing one to the other
         *  is not a cycle; GetAttribute finds the bare entry at whatever priority
         *  declared it. Our resolveAttrInTheme collapses the two namespaces in its
         *  pass 2, which turns the alias into an apparent self-reference. When a
         *  hop resolves to the attribute we just asked for, we therefore continue
         *  the scan BELOW the style that answered instead of giving up: that
         *  reproduces AOSP's answer under the collapsed key space.
         *
         *  The application theme is appended as the LAST cascade entry, not
         *  consulted first, so a Theme with an empty cascade resolves exactly what
         *  it did before, and a `?attr` hop can still reach the application theme
         *  after every style the caller actually applied. */
        private Integer macapkResolveAttrColorInCascade(String attrName) {
            return macapkResolveAttrColorInCascade(attrName, 0);
        }

        /** The default row of selector `name` when it is a `?attr`, resolved in
         *  this theme's cascade, with the row's android:alpha applied (AOSP
         *  modulateColor). Null when the default row is not a theme attribute. */
        private Integer macapkSelectorAttrRowColor(String name, int depth) {
            try {
                Object row = macapkResourceIndex.getClass()
                    .getMethod("macapkSelectorDefaultAttrRow", String.class)
                    .invoke(macapkResourceIndex, name);
                if (!(row instanceof String[] r) || r.length < 2) return null;
                String next = macapkAttrRefName(r[0]);
                if (next == null || next.isEmpty()) return null;
                Integer c = macapkResolveAttrColorInCascade(next, depth + 1);
                if (c == null || r[1] == null) return c;
                float mod = Float.parseFloat(r[1]);
                int a = Math.round(((c >>> 24) & 0xFF) * mod);
                a = Math.max(0, Math.min(255, a));
                return (a << 24) | (c & 0x00FFFFFF);
            } catch (Throwable t) {
                return null;
            }
        }

        private Integer macapkResolveAttrColorInCascade(String attrName, int depth) {
            try {
                java.util.List<String> cascade =
                    new java.util.ArrayList<>(appliedStyles);
                java.lang.reflect.Method mAppName = macapkResourceIndex.getClass()
                    .getMethod("getApplicationThemeName");
                Object appName = mAppName.invoke(macapkResourceIndex);
                if (appName instanceof String an && !an.isEmpty()
                        && !cascade.contains(an)) {
                    cascade.add(an);
                }
                if (cascade.isEmpty()) return null;
                java.lang.reflect.Method mResolve = macapkResourceIndex.getClass()
                    .getMethod("resolveAttrInTheme", String.class, String.class);
                java.util.Set<String> seen = new java.util.HashSet<>();
                seen.add(attrName);
                String cur = attrName;
                int from = 0;
                for (int hop = 0; hop <= MACAPK_MAX_ATTR_HOPS; ++hop) {
                    String raw = null;
                    int answered = -1;
                    for (int i = from; i < cascade.size(); ++i) {
                        Object r = mResolve.invoke(macapkResourceIndex,
                                                   cascade.get(i), cur);
                        if (r instanceof String s && !s.trim().isEmpty()) {
                            raw = s.trim();
                            answered = i;
                            break;
                        }
                    }
                    if (raw == null) return null;
                    if (raw.charAt(0) == '?') {
                        String next = macapkAttrRefName(raw);
                        if (next == null || next.isEmpty()) return null;
                        // Self-alias, or an attr we already chased: take the next
                        // lower declarer rather than looping. See THE SELF-ALIAS
                        // RULE above.
                        if (!seen.add(next) || next.equals(cur)) {
                            from = answered + 1;
                            continue;
                        }
                        cur = next;
                        from = 0;
                        continue;
                    }
                    Integer c = macapkResolveColorExpr(raw);
                    if (c != null) return c;
                    // A res/color selector whose default row is itself a theme
                    // attribute (AdGuard's navigation_icon_color ->
                    // selector_background_navigation_item -> ?navigation_icon_color_normal):
                    // AOSP inflates the ColorStateList against THIS theme, so chase
                    // that row here. The theme-less resolver above cannot, and
                    // answered null -- the stroke-only bottom-nav icons painted
                    // transparent (issue #5, 2026-10-19).
                    // MACAPK_THEME_SELECTOR_ATTR_ROW=0 restores the null.
                    if (MACAPK_THEME_SELECTOR_ATTR_ROW && depth < 4 && raw.startsWith("@color/")) {
                        Integer sc = macapkSelectorAttrRowColor(raw.substring(7), depth);
                        if (sc != null) return sc;
                    }
                    // Declared, but not a colour (a @drawable, say). The old
                    // per-style loop skipped such a style and kept walking;
                    // preserve that exactly.
                    from = answered + 1;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        /** `?attr/X` / `?android:attr/X` / `?X` to the lookup key
         *  ResourceIndex.resolveAttrInTheme expects. Namespace-PRESERVING, matching
         *  resolveColorRef's own convention: `android:X` and `X` are distinct
         *  attributes that AppCompat/M3 alias to each other, and collapsing them
         *  loops the colorControlNormal chain. */
        private static String macapkAttrRefName(String raw) {
            if (raw == null || raw.isEmpty() || raw.charAt(0) != '?') return null;
            if (raw.startsWith("?attr/")) return raw.substring(6);
            if (raw.startsWith("?android:attr/")) return "android:" + raw.substring(14);
            return raw.substring(1);
        }
        /** RAW-value sibling of resolveAttrColor above, for callers that need
         *  the UNCONVERTED declared value (e.g. "@drawable/splash_background")
         *  rather than a colour int -- resolveAttrColor's underlying
         *  resolveAttrToColor -> resolveColorRef chain silently drops a
         *  reference-shaped value that isn't ultimately a colour, which is
         *  correct for a colour-typed caller but throws away information a
         *  drawable-resolving caller needs. Same two-tier cascade-then-
         *  application-theme order as resolveAttrColor and resolveAttribute
         *  (int, TypedValue, boolean) above (which already does exactly this
         *  walk for the id-keyed case -- see its own doc comment); this is
         *  the name-keyed sibling of THAT raw-value lookup, not new logic.
         *  MEASURED need (NewPipe bring-up, 2026-07-31): NewPipe's
         *  res/layout/drawer_layout.xml sets
         *  `android:background="?android:attr/windowBackground"` directly on
         *  its NavigationView. windowBackground's value differs by TYPE
         *  across NewPipe's own themes -- OpeningTheme (the splash theme)
         *  declares it as `@drawable/splash_background` (a layer-list
         *  drawable), DarkTheme as `@color/dark_background_color` (a plain
         *  colour) -- so a colour-only resolver can answer for DarkTheme but
         *  has nothing to hand back for OpeningTheme's drawable, and
         *  LayoutInflater.applyBackground had no path from "the resolved
         *  `?attr/X` names a `@drawable/Y`" to actually inflating Y. See
         *  that method's own comment for the consumer. */
        /** The THEME-TIER lookup: this Theme's applied-style cascade ONLY,
         *  no application-theme fallback. AOSP's ApplyStyle theme tier is
         *  `theme->GetAttribute(...)` on THE theme object -- a standalone
         *  `newTheme()+applyStyle(X)` answers only what X's chain declares.
         *  resolveAttrRaw below deliberately keeps its application-theme
         *  fallback (it is the long-standing compensation for sparse
         *  activity cascades, and the per-getter chains lean on it); using
         *  IT as the tier source would leak the app theme's values into
         *  every standalone theme, exactly inverting the fwprobe's
         *  ProbeBase.Empty rows (`ref has=0`). */
        /** Whether any applyStyle has populated this Theme. The theme tier
         *  only answers honestly for such themes: a manifest-only activity
         *  theme never reaches appliedStyles (the loader tracks it as a
         *  NAME in ResourceIndex, not as an applied style -- see
         *  ResourceIndex's manifest-anchor comment), so an empty cascade
         *  means "our wiring has not modelled this theme" and the legacy
         *  behavior must hold. See TypedArray.macapkThemeTierLive. */
        public boolean macapkHasAppliedStyles() {
            return !appliedStyles.isEmpty();
        }

        public String macapkResolveAttrRawCascadeOnly(String attrName) {
            if (macapkResourceIndex == null || attrName == null) return null;
            try {
                java.lang.reflect.Method mResolve = macapkResourceIndex.getClass()
                    .getMethod("resolveAttrInTheme", String.class, String.class);
                for (String style : appliedStyles) {
                    Object r = mResolve.invoke(macapkResourceIndex, style, attrName);
                    if (r instanceof String s && !s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        /** The raw lookup resolveAttribute (and the shared resolver's ?attr
         *  hops) uses: a POPULATED cascade answers for itself, cascade-only;
         *  the application-theme fallback fires only for a theme our wiring
         *  never modelled (empty cascade -- the manifest-only Activity case).
         *  WHY the split: the fallback is load-bearing compensation for
         *  sparse activity cascades, but on a standalone
         *  `newTheme()+applyStyle(X)` it hands back the MANIFEST theme's
         *  values for attributes X never declared. The old value packer
         *  masked that for strings and `?`-hops by failing to encode them
         *  (`ok=0` by accident); the real resolver would have surfaced the
         *  manifest theme's answers -- measured offline, it flipped
         *  RESOLVE.app.empty.probeString (a v1.0.888 calibration row) from
         *  agreeing `ok=0` to a wrong `dark-string`. */
        public String macapkResolveAttrRawHonest(String attrName) {
            if (macapkHasAppliedStyles()) {
                return macapkResolveAttrRawCascadeOnly(attrName);
            }
            return resolveAttrRaw(attrName);
        }

        /** MACAPK_THEME_APP_ATTR_CASCADE (default ON; =0 restores the per-style
         *  walk below exactly, for bisecting). An APP-namespace attribute (a
         *  0x7f id, or the bare name of an `?attr/X` hop) is answered by the
         *  highest-priority applied style whose chain declares that EXACT key;
         *  only when no applied style declares it anywhere does the per-style
         *  walk (with its bare -> `android:` alias fallback) run.
         *
         *  WHY. AOSP's theme is one merged bag keyed by attribute ID, so app
         *  `colorPrimary` (0x7f) and `android:colorPrimary` (0x01010433) are
         *  different entries and an alias can never answer for the other
         *  (AssetManager2 Theme::GetAttribute looks the id up in the bag;
         *  ThemeOverlay.AppCompat's `android:colorPrimary = ?attr/colorPrimary`
         *  bridge is an ordinary entry under the framework id). Our cascade is
         *  a list of style NAMES and resolveAttrInTheme runs its alias pass per
         *  style, so on a two-style cascade [ThemeOverlay.AppCompat.Dialog,
         *  AppTheme] the head overlay answered app `colorPrimary` with its own
         *  `android:colorPrimary = ?attr/colorPrimary` bridge before AppTheme's
         *  real `colorPrimary` was ever asked, and the hop looped to absent.
         *  MEASURED (2026-09-26, dialog lane): once Dialog wraps its context in
         *  ?android:dialogTheme, 19 of 62 corpus apps answered 14-15 colour
         *  attributes ABSENT through resolveAttribute on a dialog context
         *  (colorPrimary, colorPrimaryDark, colorAccent, colorControlNormal /
         *  Activated / Highlight, colorButtonNormal, their android: twins),
         *  and Fossify Gallery's own MaterialCheckBox tint path threw
         *  IllegalArgumentException ("requires a value for the
         *  colorControlActivated attribute") through MaterialColors.getColor.
         *  The same loss already hit every NON-dialog context wrapped in an
         *  AppCompat-derived overlay: off dialogs (62 APKs, 516 contexts: the
         *  activity, each Material widget's materialThemeOverlay, the
         *  actionBar(Popup)Theme overlays, ThemeOverlay.AppCompat.* by name)
         *  this moves 1811 answers in 138 toolbar / action-bar / popup overlay
         *  contexts of 20 apps -- 1798 absent -> value (1766 equal to the
         *  activity's), 13 value -> value (textColorAlertDialogListItem: the
         *  overlay's android: twin no longer answers the app attribute) -- and
         *  none on an activity or a widget materialThemeOverlay context
         *  (debug/dialogtheme_gate.sh --overlays). */
        public String macapkResolveAppAttrRawHonest(String attrName) {
            if (MACAPK_THEME_APP_ATTR_CASCADE && attrName != null
                    && !attrName.startsWith("android:")
                    && appliedStyles.size() > 1 && macapkResourceIndex != null) {
                try {
                    java.lang.reflect.Method mExact = macapkThemeExactMethod;
                    if (mExact == null) {
                        mExact = macapkResourceIndex.getClass()
                            .getMethod("resolveAttrInThemeExact", String.class, String.class);
                        macapkThemeExactMethod = mExact;
                    }
                    for (String style : appliedStyles) {
                        Object r = mExact.invoke(macapkResourceIndex, style, attrName);
                        if (r instanceof String s && !s.isEmpty()) return s;
                    }
                } catch (Throwable ignored) {}
            }
            return macapkResolveAttrRawHonest(attrName);
        }

        public String resolveAttrRaw(String attrName) {
            if (macapkResourceIndex == null || attrName == null) return null;
            // v1.0.886 probe. This method resolves against appliedStyles FIRST
            // and only then the application theme, so the answer depends on
            // whatever applyStyle last pushed -- not on the manifest theme.
            // A lane simulated the resolver over the on-disk XML and showed all
            // of fdroid / wikipedia / deskclock answer windowNoTitle='true'
            // CORRECTLY from the MANIFEST root, while each carries a sibling on
            // the same root name that answers 'false' (wikipedia
            // AppTheme.ActionBar, fdroid Theme.AppCompat.*). So a wrong answer
            // is one bad appliedStyles head away, and this trace names the head.
            final boolean trace = System.getenv("MACAPK_TRACE_WINDOW_FLAGS") != null
                                  && attrName.startsWith("window");
            String answer = null, from = null;
            try {
                java.lang.reflect.Method mResolve = macapkResourceIndex.getClass()
                    .getMethod("resolveAttrInTheme", String.class, String.class);
                for (String style : appliedStyles) {
                    Object r = mResolve.invoke(macapkResourceIndex, style, attrName);
                    if (r instanceof String s && !s.isEmpty()) {
                        answer = s; from = style;
                        if (!trace) return s;
                        break;
                    }
                }
                if (answer == null) {
                    java.lang.reflect.Method mAppName = macapkResourceIndex.getClass()
                        .getMethod("getApplicationThemeName");
                    Object appName = mAppName.invoke(macapkResourceIndex);
                    if (appName instanceof String an) {
                        Object r = mResolve.invoke(macapkResourceIndex, an, attrName);
                        if (r instanceof String s && !s.isEmpty()) {
                            answer = s; from = "APPTHEME:" + an;
                            if (!trace) return s;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (trace) {
                try {
                    com.macapk.loader.NativeLog.i("Mac.apk.Theme",
                        "resolveAttrRaw " + attrName + " -> " + answer
                        + "  from=" + from
                        + "  appliedStyles=" + appliedStyles);
                } catch (Throwable ignored2) {}
            }
            return answer;
        }
        // (parseHexColorStatic died with v1.0.913: resolveAttribute's hex
        // literals now compile through macapkParseColorLiteral, which also
        // carries aapt's per-digit-count TYPE_INT_COLOR_* typing.)
        /** API-1+ -- copy state from another Theme. AppCompatActivity
         *  uses this during attachBaseContext theming.
         *
         *  AOSP ResourcesImpl.ThemeImpl.setTo replaces this theme's whole
         *  attribute table with the other's, so a subsequent applyStyle layers
         *  ON TOP of the inherited state. Our equivalent state is the
         *  appliedStyles cascade, so copying it IS the faithful operation.
         *
         *  Gated on MACAPK_VIEW_THEME (default **ON** since 2026-08-01 -- set it
         *  to 0 to opt out; canonical declaration in
         *  android.view.ContextThemeWrapper -- read here from the environment
         *  directly to avoid an android.content.res -> android.view dependency).
         *  This comment said "default OFF" until v1.0.890 while the reader
         *  (macapkViewThemeOn, below) returned true on an unset variable, so
         *  anyone reasoning from the comment concluded setTo was dormant when it
         *  is in fact live -- which matters, because the appliedStyles cascade
         *  this copies is what the whole theme resolution walks.
         *  This was an empty no-op before, and it is ContextThemeWrapper's
         *  theme-inheritance step; turning it on unconditionally would change
         *  what every existing setTo() caller (AppCompatActivity among them)
         *  observes, so it moves with the same flag as the rest of View-level
         *  theming rather than on its own. */
        public void setTo(Theme other) {
            if (!MACAPK_VIEW_THEME_FLAG) return;
            if (other == null || other == this) return;
            appliedStyles.clear();
            appliedStyles.addAll(other.appliedStyles);
        }
        /** API-1+ — combine theme styles. */
        public void rebase() {}
        /** v1.0.110 — Glide loadDrawableV7 expects this method.
         *  AOSP returns `Resources.this` since Theme is a non-static
         *  inner class. */
        public Resources getResources() { return Resources.this; }

        /** AOSP :1821 -- `mThemeImpl.dump(priority, tag, prefix)`, a
         *  Log-based debug dump of every attribute in the theme cascade.
         *  AOSP's implementation lives entirely in the native AssetManager2
         *  ResTable theme; there is no equivalent structure to walk here (our
         *  theme is a resolved attr map built by ResourceIndex), so there is
         *  nothing to print. Deliberately silent rather than logging a
         *  fabricated table. */
        public void dump(int priority, String tag, String prefix) { }

        /** AOSP :1958 -- "the ordered list of resource IDs that are considered
         *  when resolving attribute values", used by the layout inspector.
         *  AOSP itself returns an EMPTY array (not null) whenever the native
         *  side has no stack recorded, and that is the branch we are always
         *  in: this tree resolves attributes through ResourceIndex without
         *  recording the cascade. Empty, never null, matching AOSP's own
         *  null-to-empty normalisation. */
        public int[] getAttributeResolutionStack(int defStyleAttr, int defStyleRes,
                                                 int explicitStyleRes) {
            return new int[0];
        }

        /** AOSP :1918 -- resolve the `style="..."` attribute an AttributeSet
         *  carries into the style resource id it names. Ported verbatim,
         *  including both branches (an `attr` reference is resolved through
         *  the theme; a direct `style` reference is returned as-is) and the
         *  ID_NULL result for everything else. */
        public int getExplicitStyle(android.util.AttributeSet set) {
            if (set == null) return ID_NULL;
            int styleAttr = set.getStyleAttribute();
            if (styleAttr == ID_NULL) return ID_NULL;
            String styleAttrType = getResources().getResourceTypeName(styleAttr);
            if ("attr".equals(styleAttrType)) {
                android.util.TypedValue explicitStyle = new android.util.TypedValue();
                boolean resolved = resolveAttribute(styleAttr, explicitStyle, true);
                if (resolved) {
                    return explicitStyle.resourceId;
                }
            } else if ("style".equals(styleAttrType)) {
                return styleAttr;
            }
            return ID_NULL;
        }
        /** API-3+ — get theme attribute count. */
        public TypedArray obtainStyledAttributes(int[] attrs) {
            // AOSP: `mThemeImpl.obtainStyledAttributes(this, null, attrs, 0, 0)`
            // -- a THEME probe (null parser, no defStyles), so every value comes
            // from the final tier, the theme (AttributeResolution.cpp:292
            // "...and finally the theme").
            // v1.0.913: the theme actually IS a value source now
            // (macapkThemeTier), so hasValue/getValue/getIndexCount and the
            // validated typed getters answer what the theme declares instead
            // of a universal-true/empty record. getString deliberately does
            // NOT read this tier while MACAPK_THEME_STRING_FALLBACK is off --
            // that is the gated viewInflaterClass delivery (see
            // TypedArray.macapkThemeString for the measured reason).
            TypedArray ta = new TypedArray(Resources.this, attrs, Theme.this);
            ta.macapkThemeTier = true;
            return ta;
        }
        /** AOSP Resources.Theme.obtainStyledAttributes(int resId, int[] attrs):
         *  "Return a TypedArray holding the values defined by the style
         *  resource resId which are listed in attrs." So the STYLE named by
         *  resId is the value source -- this is not a theme-only probe.
         *
         *  We used to return an all-defaults TypedArray, which silently turned
         *  "read attribute X out of style S" into "return the caller's
         *  default". Measured on Anuto TD (2026-07-29): its own
         *  `Theme.getResourceId(int attrId)` is
         *      TypedArray a = mContext.obtainStyledAttributes(
         *              mThemeStyleId, new int[] { attrId });
         *      int id = a.getResourceId(0, 0);
         *  i.e. every sprite bitmap in the game is looked up by resolving an
         *  attr against the app's chosen style. With the stub, every lookup
         *  returned 0, so `BitmapFactory.decodeResource(res, 0)` returned null
         *  and `AnutoApplication.onCreate` NPE'd on `Bitmap.getWidth()` --
         *  the Application never finished, so GameActivity could not even be
         *  constructed. Resolve the style and bind its items (the same
         *  StyleBackedAttributeSet the defStyleRes path already uses), so the
         *  returned TypedArray reads real values by attribute name.
         *  Generic: any app reading a style's attrs programmatically. */
        public TypedArray obtainStyledAttributes(int resId, int[] attrs) {
            // v1.0.913: AOSP routes this to ApplyStyle(defStyleRes=resId) --
            // Resources.java:1594 `mThemeImpl.obtainStyledAttributes(this,
            // null, attrs, 0, resId)` -- and ApplyStyle fills each attribute
            // from the STYLE bag first "and finally the theme"
            // (AttributeResolution.cpp:292). We carried only the style half,
            // so every attribute the style did not restate read as absent:
            // the whole OSA.style fwprobe family (132 false negatives, e.g.
            // a parentless one-item style answered nothing for the other 20
            // theme-supplied attrs). The style stays the bound AttributeSet
            // (first tier); macapkThemeTier adds the theme fallback.
            if (resId != 0) {
                String styleName = resolveDefStyleName(0, resId);
                if (styleName != null) {
                    // docs/attr-theme-threading_2026-07-31.md: thread `this`
                    // (the Theme) so a styled colour attr resolved from `resId`
                    // can chase a `?attr/` value against THIS theme's cascade.
                    TypedArray ta = new TypedArray(Resources.this, attrs,
                        new StyleBackedAttributeSet(styleName), this);
                    ta.macapkThemeTier = true;
                    return ta;
                }
            }
            // Unresolvable style id (framework style ids, until the platform
            // table lands) or resId == 0: the theme tier still answers, which
            // both matches ApplyStyle and replaces the old stub TypedArray
            // whose hasValue lied "true" for all 21 attrs (the OSA.fwstyle
            // false-positive class, 52 rows).
            TypedArray ta = new TypedArray(Resources.this, attrs, this);
            ta.macapkThemeTier = true;
            return ta;
        }
        public TypedArray obtainStyledAttributes(android.util.AttributeSet set, int[] attrs,
                                                  int defStyleAttr, int defStyleRes) {
            // BIND the AttributeSet when present so the returned TypedArray
            // reads real per-attribute values from the inflated XML (same
            // as Resources.obtainAttributes / Context.obtainStyledAttributes,
            // which already bind). androidx.core.content.res.TypedArrayUtils.
            // obtainAttributes routes the Compose VECTOR parser here (theme
            // != null path: theme.obtainStyledAttributes(set, attrs, 0, 0)),
            // so without the set the parser reads viewportWidth=0 and throws
            // "viewportWidth > 0", aborting composition + the measure pass
            // (Reddit welcome screen blank). When set == null this is a
            // theme-only probe (AppCompat's window-feature check); keep the
            // old set-less TypedArray so that path is byte-for-byte unchanged.
            if (set == null) {
                // No inflated XML AttributeSet. AOSP still resolves the
                // defStyleAttr -> theme style (then defStyleRes) and reads the
                // styled attrs from it. Our old behavior dropped that, so a
                // PROGRAMMATICALLY-created styled view got nothing -- most
                // visibly AppCompat's OverflowMenuButton, constructed as
                // `super(ctx, null, R.attr.actionOverflowButtonStyle)`, whose
                // android:src (the 3-dot overflow vector) lives in the style
                // Widget.AppCompat.ActionButton.Overflow, so the toolbar
                // overflow icon never drew. Resolve the style and bind its
                // values; the TypedArray then reads android:src/background/etc.
                // by attribute NAME via resolveAttrInTheme. Generic across any
                // app's styled widgets. Gated on a non-zero defStyle so the
                // theme-only probe path (AppCompat's window-feature check,
                // defStyleAttr==0) stays byte-for-byte unchanged.
                if (defStyleAttr != 0 || defStyleRes != 0) {
                    String styleName = resolveDefStyleName(defStyleAttr, defStyleRes);
                    if (styleName != null) {
                        // docs/attr-theme-threading_2026-07-31.md: thread `this`.
                        TypedArray ta = new TypedArray(Resources.this, attrs,
                            new StyleBackedAttributeSet(styleName), this);
                        ta.macapkThemeTier = true;
                        return ta;
                    }
                }
                // v1.0.913: set == null and no (resolvable) defStyle is
                // AOSP's pure theme read -- ApplyStyle with only the theme
                // tier left. Bind the theme as the value source instead of
                // returning the all-defaults stub. AppCompat's window-flag
                // probe rides this path; getBoolean answers value-first from
                // the SAME theme cascade its window-flag special case was
                // already reading, so that check's inputs are unchanged.
                TypedArray ta = new TypedArray(Resources.this, attrs, this);
                ta.macapkThemeTier = true;
                return ta;
            }
            // v1.0.499: AOSP obtainStyledAttributes cascade -- when the inflated
            // AttributeSet carries a `style="@style/X"` attr, the style's items
            // fill in any attr the direct XML doesn't set (direct attrs still
            // win). Our old set!=null path bound ONLY the direct attrs, so a
            // styled custom-attr widget got the style's values dropped. The most
            // visible instance: Duolingo's JuicyButton reads faceColor/lipColor
            // from a button style (style="@style/...PrimaryButton" ->
            // <item name="faceColor">@color/juicyMacaw</item>); with the style
            // unread, faceColor resolved to 0 and the GET STARTED button rendered
            // with NO green fill (white-on-white). Generic across any app's
            // styled widgets. Only FILLS missing attrs (direct values win), so a
            // view that set an attr directly is unaffected.
            // lane layout, 2026-08-01: honor defStyleAttr/defStyleRes in the
            // set != null path too. AOSP's documented precedence for
            // obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes) is
            //   XML attrs > style="@style/X" > defStyleAttr's style >
            //   defStyleRes > theme
            // and we implemented only the first two tiers here (the defStyle
            // tiers existed ONLY in the set == null branch above). Every
            // Material widget reads its DESIGN TOKENS through tier 3 -- e.g.
            // NavigationBarView(ctx, attrs, R.attr.bottomNavigationStyle, ...)
            // pulls itemPaddingTop/itemPaddingBottom/activeIndicator dimens
            // and the 80dp bar height from Widget.Material3.BottomNavigation-
            // View via defStyleAttr. With tier 3 dropped, AntennaPod's bottom
            // bar measured 63dp tall with the item block hugging the top edge
            // (icon top +2.5dp vs the emulator's +17.5dp -- measured pixel
            // bands, emulator A/B 2026-08-01, docs/canvas-layout-fidelity_
            // 2026-08-01.md). Layering: StyleMergedAttributeSet nests, so
            // direct attrs win, then the style attr's style, then defStyle.
            String mergedStyle = macapkStyleNameFromSet(set);
            String defStyleName = resolveDefStyleName(defStyleAttr, defStyleRes);
            // Diagnostic (env-gated, zero cost unset): show the defStyle
            // resolution per 4-arg parse so a token-delivery failure names
            // its broken link instead of hiding behind a caller default.
            if (System.getenv("MACAPK_TRACE_DEFSTYLE") != null && defStyleAttr != 0) {
                try {
                    com.macapk.loader.NativeLog.i("Mac.apk.TypedArray",
                        "oSA4 dsa=0x" + Integer.toHexString(defStyleAttr)
                        + " dsr=0x" + Integer.toHexString(defStyleRes)
                        + " styleAttr=" + mergedStyle
                        + " defStyle=" + defStyleName
                        + " nAttrs=" + (attrs == null ? 0 : attrs.length));
                } catch (Throwable ignored) {}
            }
            android.util.AttributeSet layered = set;
            if (mergedStyle != null)
                layered = new StyleMergedAttributeSet(layered, mergedStyle);
            if (defStyleName != null && !defStyleName.equals(mergedStyle))
                layered = new StyleMergedAttributeSet(layered, defStyleName);
            // docs/attr-theme-threading_2026-07-31.md: thread `this` (the
            // Theme) -- this is the MOST COMMON real-world shape (a View/
            // widget ctor's own obtainStyledAttributes(attrs, styleable,
            // defStyleAttr, 0) call with a real inflated AttributeSet), so it
            // is the single highest-leverage wiring point for TypedArray
            // .getColor's Theme-aware resolution.
            return new TypedArray(Resources.this, attrs, layered, this);
        }

        /** The style NAME referenced by an AttributeSet's `style=` attr (or null
         *  when absent / unresolvable). This is TIER 2 of AOSP's documented
         *  obtainStyledAttributes precedence -- XML attrs > style= > defStyleAttr
         *  > defStyleRes > theme -- so when it answers null the tier is simply
         *  DROPPED and the widget silently takes tier 3's answer instead.
         *
         *  ATTRSTYLE lane, 2026-08-13: it answered null for `style="?someAttr"`,
         *  which is how EVERY AppCompat AlertDialog spells its three buttons
         *  (abc_alert_dialog_button_bar_material.xml:
         *  `style="?buttonBarPositiveButtonStyle"`) and its button bar
         *  (`style="?buttonBarStyle"`). The full diagnosis, the AOSP citations and
         *  the measured consequence are at ResourceIndex.styleNameForIdRef, which
         *  is where the shared half of the fix lives.
         *
         *  The hop-following is done HERE, before the hand-off, because THIS
         *  object is the one holding the right cascade: a dialog's decor is
         *  inflated against the dialog's own Theme, whose appliedStyles may carry
         *  an alertDialogTheme / ThemeOverlay that the APPLICATION theme does not.
         *  ResourceIndex's own follow (macapkFollowStyleAttrRef) walks only the
         *  application theme and is the correct fallback for callers with no
         *  Theme in hand; resolving here means the better cascade wins when there
         *  is one, and the terminal `@style/…` that comes back is a plain
         *  reference either resolver handles identically. */
        private String macapkStyleNameFromSet(android.util.AttributeSet set) {
            try {
                if (macapkResourceIndex == null || set == null) return null;
                String ref = set.getAttributeValue(null, "style");
                if (ref == null || ref.isEmpty()) return null;
                if (MACAPK_ATTRSTYLE) ref = macapkFollowThemeAttrRef(ref.trim());
                Object n = macapkResourceIndex.getClass()
                    .getMethod("styleNameForIdRefPublic", String.class)
                    .invoke(macapkResourceIndex, ref);
                return (n instanceof String) ? (String) n : null;
            } catch (Throwable ignored) { return null; }
        }

        /** Follow a `?attr` chain through THIS Theme's applied-style cascade, then
         *  the application theme, until it lands on something that is not an
         *  attribute reference.
         *
         *  Behaviour is deliberately IDENTICAL to the inline loop inside
         *  resolveDefStyleName (v1.0.858) -- same hop cap, same prefix
         *  normalisation, same "keep the last value on a dead end" exit -- because
         *  that loop is proven on the fleet and this method is not a refactor of
         *  it. Notably it does NOT strip a bare `android:` prefix: resolveAttrInTheme
         *  is namespace-aware, and `?android:buttonStyle` resolving as
         *  `android:buttonStyle` is exactly what makes the platform-delegation fix
         *  work. Do not "tidy" that.
         *
         *  Returns the input unchanged when it is not a `?` ref, so callers can
         *  invoke it unconditionally. */
        private String macapkFollowThemeAttrRef(String ref) {
            String sr = ref;
            try {
                if (macapkResourceIndex == null || sr == null) return ref;
                java.lang.reflect.Method mResolve = macapkResourceIndex.getClass()
                    .getMethod("resolveAttrInTheme", String.class, String.class);
                for (int hop = 0; hop < 4 && sr.startsWith("?"); hop++) {
                    String refAttr = sr.substring(1);
                    if (refAttr.startsWith("attr/")) refAttr = refAttr.substring(5);
                    else if (refAttr.startsWith("android:attr/")) refAttr = refAttr.substring(13);
                    String next = null;
                    for (String style : appliedStyles) {
                        Object r = mResolve.invoke(macapkResourceIndex, style, refAttr);
                        if (r instanceof String) { next = (String) r; break; }
                    }
                    if (next == null) {
                        Object appName = macapkResourceIndex.getClass()
                            .getMethod("getApplicationThemeName").invoke(macapkResourceIndex);
                        if (appName instanceof String) {
                            Object r = mResolve.invoke(macapkResourceIndex, appName, refAttr);
                            if (r instanceof String) next = (String) r;
                        }
                    }
                    if (next == null || next.trim().equals(sr)) break;
                    sr = next.trim();
                }
            } catch (Throwable ignored) { return ref; }
            return sr;
        }

        /** Resolve a defStyleAttr (a theme attr id like actionOverflowButtonStyle)
         *  or defStyleRes (a style id) to the style's NAME, walking this Theme's
         *  applied-style cascade then the application theme (mirrors
         *  resolveAttribute). Returns null when no style is found. */
        private String resolveDefStyleName(int defStyleAttr, int defStyleRes) {
            if (macapkResourceIndex == null) return null;
            try {
                java.lang.reflect.Method mTypeName = macapkResourceIndex.getClass()
                    .getMethod("macapkTypeNameOf", int.class);
                String styleRef = null;
                String attrName = null;
                if (defStyleAttr != 0) {
                    Object tn = mTypeName.invoke(macapkResourceIndex, defStyleAttr);
                    attrName = (tn instanceof String[]) ? ((String[]) tn)[1] : null;
                    // v1.0.869: fall back to the FROZEN framework style-attr
                    // table. macapkTypeNameOf knows only the APP's ids, but every
                    // framework widget passes a FRAMEWORK attr here (AOSP's
                    // TextView ctor passes textViewStyle = 0x01010084, Button
                    // passes buttonStyle = 0x01010048). Without a name the theme
                    // was never consulted at all and this returned null -- tier 3
                    // of AOSP's documented precedence was simply dead for any app
                    // that inherits the attr from a framework theme instead of
                    // restating it. See ResourceIndex.macapkFrameworkStyleAttrName.
                    if (attrName == null) {
                        try {
                            attrName = (String) macapkResourceIndex.getClass()
                                .getMethod("macapkFrameworkStyleAttrName", int.class)
                                .invoke(null, defStyleAttr);
                        } catch (Throwable ignored) {}
                    }
                    if (attrName != null) {
                        java.lang.reflect.Method mResolve = macapkResourceIndex.getClass()
                            .getMethod("resolveAttrInTheme", String.class, String.class);
                        for (String style : appliedStyles) {
                            Object r = mResolve.invoke(macapkResourceIndex, style, attrName);
                            if (r instanceof String) { styleRef = (String) r; break; }
                        }
                        if (styleRef == null) {
                            Object appName = macapkResourceIndex.getClass()
                                .getMethod("getApplicationThemeName").invoke(macapkResourceIndex);
                            if (appName instanceof String) {
                                Object r = mResolve.invoke(macapkResourceIndex, appName, attrName);
                                if (r instanceof String) styleRef = (String) r;
                            }
                        }
                    }
                }
                String styleName = null;
                if (styleRef != null) {
                    String sr = styleRef.trim();
                    // lane c4, 2026-08-02: FOLLOW a `?attr` indirection instead
                    // of dead-ending on it. An AppCompat app's theme routinely
                    // DELEGATES a widget-style attr to the platform one --
                    // opensudoku's styles.xml:1467 is literally
                    //     <item name="buttonStyle">?android:buttonStyle</item>
                    // -- so `styleRef` comes back as "?android:buttonStyle",
                    // which is neither an @style/ ref nor a numeric id, and the
                    // old code handed it to styleNameForIdRefPublic (which only
                    // parses `@<id>`) and got null. Result: the widget was built
                    // with NO default chrome, measured 57px tall against the
                    // emulator's 96px. Re-resolving the referenced attr reaches
                    // the platform theme table, which now answers `buttonStyle`.
                    // Bounded loop, not recursion: a theme chain can hop more
                    // than once (?a -> ?b -> @style/X), but a cycle must not
                    // hang the inflater, so cap the hops and drop out.
                    for (int hop = 0; hop < 4 && sr.startsWith("?"); hop++) {
                        String refAttr = sr.substring(1);
                        if (refAttr.startsWith("attr/")) refAttr = refAttr.substring(5);
                        else if (refAttr.startsWith("android:attr/")) refAttr = refAttr.substring(13);
                        String next = null;
                        java.lang.reflect.Method mResolve2 = macapkResourceIndex.getClass()
                            .getMethod("resolveAttrInTheme", String.class, String.class);
                        for (String style : appliedStyles) {
                            Object r = mResolve2.invoke(macapkResourceIndex, style, refAttr);
                            if (r instanceof String) { next = (String) r; break; }
                        }
                        if (next == null) {
                            Object appName = macapkResourceIndex.getClass()
                                .getMethod("getApplicationThemeName").invoke(macapkResourceIndex);
                            if (appName instanceof String) {
                                Object r = mResolve2.invoke(macapkResourceIndex, appName, refAttr);
                                if (r instanceof String) next = (String) r;
                            }
                        }
                        if (next == null || next.trim().equals(sr)) break;
                        sr = next.trim();
                    }
                    if (sr.startsWith("@style/")) {
                        // Named style ref -- styleNameForIdRef only parses numeric
                        // @<id> refs, so strip the type prefix directly.
                        styleName = sr.substring(7);
                    } else if (sr.startsWith("@android:style/")) {
                        // The platform table answers with a fully-qualified
                        // framework style; frameworkStyleAttr strips the prefix
                        // itself, but carry the name through in the same shape
                        // the @style/ branch produces.
                        styleName = sr.substring(15);
                    } else if (!sr.startsWith("?")) {
                        Object sn = macapkResourceIndex.getClass()
                            .getMethod("styleNameForIdRefPublic", String.class)
                            .invoke(macapkResourceIndex, sr);
                        if (sn instanceof String) styleName = (String) sn;
                    }
                }
                // Fall back to defStyleRes (the style id itself) when the attr
                // didn't resolve.
                if (styleName == null && defStyleRes != 0) {
                    Object tn = mTypeName.invoke(macapkResourceIndex, defStyleRes);
                    if (tn instanceof String[] && "style".equals(((String[]) tn)[0])) {
                        styleName = ((String[]) tn)[1];
                    }
                }
                // Well-known framework widget styles. The app theme often
                // delegates these to the FRAMEWORK attr (e.g.
                // actionOverflowButtonStyle -> ?android:actionOverflowButtonStyle),
                // so the app-side resolution yields a "?..." ref we can't follow.
                // Map the attr NAME straight to the framework style whose attrs
                // ResourceIndex.frameworkStyleAttr provides (the 3-dot overflow src).
                if (styleName == null && "actionOverflowButtonStyle".equals(attrName)) {
                    styleName = "Widget.Material.ActionButton.Overflow";
                }
                if (styleName == null) return null;
                Object def = macapkResourceIndex.getClass()
                    .getMethod("getStyleByName", String.class)
                    .invoke(macapkResourceIndex, styleName);
                // Accept app-declared styles AND framework styles we model attrs
                // for (getStyleByName is null for the latter, but
                // resolveAttrInTheme -> frameworkStyleAttr still answers).
                if (def != null) return styleName;
                return styleName.startsWith("Widget.Material.") ? styleName : null;
            } catch (Throwable ignored) { return null; }
        }
        public int getChangingConfigurations() { return 0; }
        public android.graphics.drawable.Drawable getDrawable(int id) { return null; }
        // Theme.getColorStateList resolves a color id within the theme; for a
        // plain <color> id this is the same single-color list the enclosing
        // Resources resolves. Delegate so themed color reads stop returning
        // null (NPE in tinting). The enclosing Resources is the implicit
        // outer instance.
        public android.content.res.ColorStateList getColorStateList(int id) {
            return Resources.this.getColorStateList(id, this);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException() { super(); }
        public NotFoundException(String msg) { super(msg); }
        /** AOSP core/java/android/content/res/Resources.java -- API 24 added
         *  the cause-carrying form; the resource loader wraps the underlying
         *  IOException/XmlPullParserException in it. */
        public NotFoundException(String name, Exception cause) {
            super(name, cause);
        }
    }

    /** A read-only AttributeSet backed by a resolved STYLE: every
     *  getAttributeValue(name) looks the attr up in the style's cascade via
     *  ResourceIndex.resolveAttrInTheme (which walks the style's parent chain
     *  and handles the android:-namespace alias). Used by Theme.obtainStyledAttributes
     *  to make a TypedArray built from a defStyleAttr/defStyleRes read the
     *  style's android:src/background/etc. -- the path AppCompat's
     *  programmatically-created OverflowMenuButton relies on for its 3-dot icon. */
    static final class StyleBackedAttributeSet implements android.util.AttributeSet {
        private final String styleName;
        StyleBackedAttributeSet(String styleName) { this.styleName = styleName; }
        @Override public String getAttributeValue(String namespace, String name) {
            if (name == null || macapkResourceIndex == null) return null;
            // An APP-package slot (TypedArray asks for it in the res-auto
            // namespace) is filled by the app attribute's own 0x7f id and
            // nothing else: AttributeResolution.cpp ApplyStyle matches the
            // style bag by `cur_ident` (:277 xml_style_attr_finder.Find,
            // :289 def_style_attr_finder.Find, android14-release). So a style
            // that declares only `android:X` leaves the app slot `X` EMPTY on a
            // device. resolveAttrInTheme's pass 2 (bare -> `android:X`) is the
            // right fallback for a bare-NAME theme query, but for an app slot
            // it crosses identities: the style's `android:X` answered app `X`.
            //
            // MEASURED VICTIM: F-Droid's Settings. Its preferences.xml gives
            // LiveSeekBarPreference `android:layout="@layout/preference_seekbar"`
            // (its own SeekBarForegroundThumb). androidx Preference reads
            // TypedArrayUtils.getResourceId(a, Preference_layout,
            // Preference_android_layout, ...), where the APP `layout` slot wins.
            // The defStyle Preference.SeekBarPreference.Material sets only
            // `android:layout` (the stock preference_widget_seekbar_material),
            // which pass 2 fed to the app slot, so the stock layout beat the
            // app's own and onBindViewHolder's cast to SeekBarForegroundThumb
            // threw ClassCastException on AppCompatSeekBar.
            //
            // resolveAttrInThemeExact is pass 1 alone (the exact app key up the
            // chain; a platform style never answers it). The framework-slot
            // half of the same rule is TypedArray.macapkFrameworkSlotValue,
            // which asks this set for `android:X` and is unaffected.
            // MACAPK_STYLE_APP_SLOT_EXACT=0 (or MACAPK_ATTR_IDENTITY=0) restores
            // the aliasing walk: a labelled bisect gate.
            if (MACAPK_STYLE_APP_SLOT_EXACT && MACAPK_NS_RES_AUTO.equals(namespace)
                    && !name.startsWith("android:")) {
                try {
                    java.lang.reflect.Method mExact = macapkThemeExactMethod;
                    if (mExact == null) {
                        mExact = macapkResourceIndex.getClass()
                            .getMethod("resolveAttrInThemeExact", String.class, String.class);
                        macapkThemeExactMethod = mExact;
                    }
                    Object v = mExact.invoke(macapkResourceIndex, styleName, name);
                    return (v instanceof String) ? (String) v : null;
                } catch (Throwable ignored) { return null; }
            }
            try {
                Object v = macapkResourceIndex.getClass()
                    .getMethod("resolveAttrInTheme", String.class, String.class)
                    .invoke(macapkResourceIndex, styleName, name);
                return (v instanceof String) ? (String) v : null;
            } catch (Throwable ignored) { return null; }
        }
        @Override public int getAttributeCount() { return 0; }
        @Override public String getAttributeName(int index) { return null; }
        @Override public String getAttributeValue(int index) { return null; }
        @Override public String getPositionDescription() { return "style:" + styleName; }
        @Override public int getAttributeNameResource(int index) { return 0; }
        @Override public int getAttributeListValue(String ns, String a, String[] o, int d) { return d; }
        @Override public boolean getAttributeBooleanValue(String ns, String a, boolean d) { return d; }
        @Override public int getAttributeResourceValue(String ns, String a, int d) { return d; }
        @Override public int getAttributeIntValue(String ns, String a, int d) { return d; }
        @Override public int getAttributeUnsignedIntValue(String ns, String a, int d) { return d; }
        @Override public float getAttributeFloatValue(String ns, String a, float d) { return d; }
        @Override public int getAttributeListValue(int index, String[] o, int d) { return d; }
        @Override public boolean getAttributeBooleanValue(int index, boolean d) { return d; }
        @Override public int getAttributeResourceValue(int index, int d) { return d; }
        @Override public int getAttributeIntValue(int index, int d) { return d; }
        @Override public int getAttributeUnsignedIntValue(int index, int d) { return d; }
        @Override public float getAttributeFloatValue(int index, float d) { return d; }
        @Override public String getIdAttribute() { return null; }
        @Override public String getClassAttribute() { return null; }
        @Override public int getIdAttributeResourceValue(int d) { return d; }
        @Override public int getStyleAttribute() { return 0; }
    }

    /** v1.0.499: an AttributeSet that reads the direct inflated attrs FIRST,
     *  then falls back to a `style="@style/X"` style's items by attr name --
     *  the AOSP obtainStyledAttributes cascade (style < direct attrs). Used so
     *  styled custom-attr widgets (Duolingo's JuicyButton faceColor/lipColor)
     *  pick up the style's values instead of dropping to defaults. */
    static final class StyleMergedAttributeSet implements android.util.AttributeSet {
        private final android.util.AttributeSet direct;
        private final StyleBackedAttributeSet style;
        StyleMergedAttributeSet(android.util.AttributeSet direct, String styleName) {
            this.direct = direct;
            this.style = new StyleBackedAttributeSet(styleName);
        }
        @Override public String getAttributeValue(String ns, String name) {
            String v = direct.getAttributeValue(ns, name);
            return (v != null) ? v : style.getAttributeValue(ns, name);
        }
        @Override public int getAttributeCount() { return direct.getAttributeCount(); }
        @Override public String getAttributeName(int i) { return direct.getAttributeName(i); }
        @Override public String getAttributeValue(int i) { return direct.getAttributeValue(i); }
        @Override public String getPositionDescription() { return direct.getPositionDescription(); }
        @Override public int getAttributeNameResource(int i) { return direct.getAttributeNameResource(i); }
        @Override public int getAttributeListValue(String n, String a, String[] o, int d) { return direct.getAttributeListValue(n, a, o, d); }
        @Override public boolean getAttributeBooleanValue(String n, String a, boolean d) { return direct.getAttributeBooleanValue(n, a, d); }
        @Override public int getAttributeResourceValue(String n, String a, int d) { return direct.getAttributeResourceValue(n, a, d); }
        @Override public int getAttributeIntValue(String n, String a, int d) { return direct.getAttributeIntValue(n, a, d); }
        @Override public int getAttributeUnsignedIntValue(String n, String a, int d) { return direct.getAttributeUnsignedIntValue(n, a, d); }
        @Override public float getAttributeFloatValue(String n, String a, float d) { return direct.getAttributeFloatValue(n, a, d); }
        @Override public int getAttributeListValue(int i, String[] o, int d) { return direct.getAttributeListValue(i, o, d); }
        @Override public boolean getAttributeBooleanValue(int i, boolean d) { return direct.getAttributeBooleanValue(i, d); }
        @Override public int getAttributeResourceValue(int i, int d) { return direct.getAttributeResourceValue(i, d); }
        @Override public int getAttributeIntValue(int i, int d) { return direct.getAttributeIntValue(i, d); }
        @Override public int getAttributeUnsignedIntValue(int i, int d) { return direct.getAttributeUnsignedIntValue(i, d); }
        @Override public float getAttributeFloatValue(int i, float d) { return direct.getAttributeFloatValue(i, d); }
        @Override public String getIdAttribute() { return direct.getIdAttribute(); }
        @Override public String getClassAttribute() { return direct.getClassAttribute(); }
        @Override public int getIdAttributeResourceValue(int d) { return direct.getIdAttributeResourceValue(d); }
        @Override public int getStyleAttribute() { return direct.getStyleAttribute(); }
    }

    // -------- v1.0.116 AOSP audit batch --------

    /** AOSP Resources.getQuantityString: select the plural form for the
     *  quantity, then (3-arg) String.format it with the args. We resolve
     *  the real <plurals> resource via ResourceIndex (id -> {quantity ->
     *  template}) and pick the CLDR keyword for the count -- F-Droid's
     *  "Updated %d days ago" subtitle is a plural; the old getText-based
     *  fallback couldn't read a <plurals> and returned "". */
    public String getQuantityString(int id, int quantity) {
        String t = macapkPluralTemplate(id, quantity);
        if (t == null) {
            // Fallback: treat as a plain string (legacy behavior).
            try {
                CharSequence s = getText(id);
                return s != null ? String.format(s.toString(), quantity) : "";
            } catch (Throwable ignored) { return ""; }
        }
        // 2-arg form: AOSP returns the raw selected text. We additionally
        // substitute the count when the template carries a single numeric
        // placeholder, matching the common caller expectation.
        try { return String.format(t, quantity); }
        catch (Throwable ignored) { return t; }
    }
    public String getQuantityString(int id, int quantity, Object... formatArgs) {
        String t = macapkPluralTemplate(id, quantity);
        if (t == null) {
            try {
                CharSequence s = getText(id);
                t = (s != null) ? s.toString() : null;
            } catch (Throwable ignored) { t = null; }
            if (t == null) return "";
        }
        // AOSP: format the selected plural template with the supplied args
        // (the quantity is only used to SELECT the form, not auto-prepended).
        if (formatArgs == null || formatArgs.length == 0) return t;
        try { return String.format(t, formatArgs); }
        catch (Throwable ignored) { return t; }
    }
    public CharSequence getQuantityText(int id, int quantity) {
        String t = macapkPluralTemplate(id, quantity);
        return t != null ? t : getQuantityString(id, quantity);
    }
    /** Resolve a <plurals> id to its template for `quantity` via the
     *  ResourceIndex. Returns null when the id isn't a known plural. */
    private String macapkPluralTemplate(int id, int quantity) {
        if (macapkResourceIndex == null) return null;
        try {
            java.lang.reflect.Method m = macapkResourceIndex.getClass()
                .getMethod("pluralForId", int.class, String.class);
            Object v = m.invoke(macapkResourceIndex, id, macapkPluralKeyword(quantity));
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable ignored) { return null; }
    }
    /** CLDR plural keyword for a count. The active test locale is English
     *  (one for n==1, other otherwise); pluralForName falls back to
     *  "other" for any locale whose specific keyword we don't emit, which
     *  is the universal form, so this stays correct for the resources we
     *  resolve. */
    private static String macapkPluralKeyword(long n) {
        return (n == 1) ? "one" : "other";
    }

    /** AOSP Resources.obtainAttributes(AttributeSet, int[]): builds a
     *  TypedArray from the attribute set's values. */
    public TypedArray obtainAttributes(android.util.AttributeSet set, int[] attrs) {
        return new TypedArray(this, attrs, set);
    }

    /** AOSP Resources.obtainTypedArray(int) (Resources.java:729): resolves an
     *  `<array>` resource into a TypedArray whose index i carries item i's
     *  declared value, so getColor(i) / getResourceId(i) / getDrawable(i) /
     *  getString(i) read the real per-item data. (Note AOSP leaves
     *  `mIndices[0] = 0`, i.e. getIndexCount() is 0 for an array-backed
     *  TypedArray -- callers iterate 0..length()-1, not via getIndex.)
     *
     *  This used to `return new TypedArray(this, new int[0])`, ignoring `id`
     *  entirely: every caller got length()==0 and default values back with no
     *  exception, even though ResourceIndex.loadArrayValues had already
     *  parsed the items. The raw item strings ARE the compiled values, so we
     *  bind them and type-infer per index the same way an AttributeSet-backed
     *  TypedArray does.
     *
     *  DEVIATION, deliberate: AOSP throws NotFoundException for an id that is
     *  not an array. ResourceIndex only scans `res/values/*.xml` for arrays
     *  (not the config-qualified `res/values-*` dirs), so a genuinely-declared
     *  array can still miss here; turning today's silent-empty into a crash
     *  for those would be a regression, not a fix. Unresolvable ids keep the
     *  old empty TypedArray until the index covers qualified dirs. */
    public TypedArray obtainTypedArray(int id) {
        String[] items = macapkArrayItems(id);
        if (items == null) return new TypedArray(this, new int[0]);
        return new MacapkResourceArray(this, items);
    }

    /** The raw per-item strings of an `<array>` / `<string-array>` /
     *  `<integer-array>` resource, or null when the id is not a known array.
     *  Goes through the reflective ResourceIndex directly (rather than
     *  getStringArray) because the framework wrappers collapse "not an array"
     *  and "empty array" into the same empty result. */
    private String[] macapkArrayItems(int id) {
        if (macapkResourceIndex == null || id == 0) return null;
        try {
            Object v = macapkResourceIndex.getClass()
                .getMethod("stringArrayForId", int.class)
                .invoke(macapkResourceIndex, id);
            if (v instanceof String[] sa && sa.length > 0) return sa;
        } catch (Throwable ignored) {}
        try {
            Object v = macapkResourceIndex.getClass()
                .getMethod("integerArrayForId", int.class)
                .invoke(macapkResourceIndex, id);
            if (v instanceof int[] ia && ia.length > 0) {
                String[] out = new String[ia.length];
                for (int i = 0; i < ia.length; i++) out[i] = Integer.toString(ia[i]);
                return out;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** The TypedArray obtainTypedArray hands back: index i is item i of the
     *  `<array>` resource. AOSP reaches the same observable surface through
     *  AssetManager.getResourceArray filling the compiled value vector; we
     *  carry the decoded item strings and type-infer per read, which is the
     *  same thing TypedArray already does for AttributeSet-backed values.
     *  Package-private nested class so it lives beside TypedArray without
     *  changing TypedArray's own shape. */
    static final class MacapkResourceArray extends TypedArray {
        private final String[] items;

        MacapkResourceArray(Resources r, String[] items) {
            super(r, items.length);
            this.items = items;
        }

        private String raw(int index) {
            if (index < 0 || index >= items.length) return null;
            String v = items[index];
            if (v == null) return null;
            v = v.trim();
            return v.isEmpty() ? null : v;
        }

        @Override public boolean hasValue(int index)        { return raw(index) != null; }
        @Override public boolean hasValueOrEmpty(int index)  { return index >= 0 && index < items.length; }

        @Override public String getString(int index) {
            String v = raw(index);
            if (v == null) return null;
            if (v.startsWith("@")) {
                int id = getResourceId(index, 0);
                Resources r = getResources();
                if (id != 0 && r != null) {
                    try { return r.getString(id); } catch (Throwable ignored) {}
                }
                return null;
            }
            return v;
        }
        @Override public CharSequence getText(int index) { return getString(index); }
        @Override public String getNonResourceString(int index) {
            String v = raw(index);
            return (v == null || v.startsWith("@") || v.startsWith("?")) ? null : v;
        }

        @Override public int getResourceId(int index, int def) {
            String v = raw(index);
            if (v == null || !v.startsWith("@")) return def;
            String body = v.substring(1);
            if (body.startsWith("+")) body = body.substring(1);
            String type = "id";
            int slash = body.indexOf('/');
            if (slash > 0) {
                String left = body.substring(0, slash);
                int colon = left.indexOf(':');
                if (colon >= 0) left = left.substring(colon + 1);   // strip "android:"
                if (!left.isEmpty()) type = left;
                body = body.substring(slash + 1);
            }
            Resources r = getResources();
            if (r == null) return def;
            int id = r.getIdentifier(body, type, null);
            return id != 0 ? id : def;
        }
        @Override public int getSourceResourceId(int index, int def) {
            return getResourceId(index, def);
        }

        @Override public int getColor(int index, int def) {
            String v = raw(index);
            Resources r = getResources();
            if (v == null || r == null) return def;
            return r.macapkResolveColorString(v, def);
        }
        @Override public android.content.res.ColorStateList getColorStateList(int index) {
            Resources r = getResources();
            int id = getResourceId(index, 0);
            if (id != 0 && r != null) {
                try {
                    android.content.res.ColorStateList csl = r.getColorStateList(id);
                    if (csl != null) return csl;
                } catch (Throwable ignored) {}
            }
            int c = getColor(index, Integer.MIN_VALUE);
            return c != Integer.MIN_VALUE
                ? android.content.res.ColorStateList.valueOf(c) : null;
        }

        @Override public boolean getBoolean(int index, boolean def) {
            String v = raw(index);
            if ("true".equals(v))  return true;
            if ("false".equals(v)) return false;
            return def;
        }
        @Override public int getInt(int index, int def) {
            String v = raw(index);
            if (v == null) return def;
            try { return Integer.parseInt(v); } catch (NumberFormatException ignored) {}
            // Hex / octal forms parseInt rejects ("0x7f", "#ff0000"). Tried
            // second because decode() reads a leading-zero decimal like "08"
            // as octal and throws.
            try { return Integer.decode(v); } catch (NumberFormatException ignored) {}
            return def;
        }
        @Override public int getInteger(int index, int def) { return getInt(index, def); }
        @Override public float getFloat(int index, float def) {
            String v = raw(index);
            if (v == null) return def;
            try { return Float.parseFloat(v); } catch (NumberFormatException ignored) {}
            return def;
        }

        @Override public float getDimension(int index, float def) {
            Float px = dimenPx(index);
            return px != null ? px : def;
        }
        @Override public int getDimensionPixelSize(int index, int def) {
            Float px = dimenPx(index);
            if (px == null) return def;
            // AOSP rounds and guarantees a nonzero dimension stays >= 1px.
            int rounded = Math.round(px);
            if (rounded == 0 && px != 0f) return px > 0 ? 1 : -1;
            return rounded;
        }
        @Override public int getDimensionPixelOffset(int index, int def) {
            Float px = dimenPx(index);
            return px != null ? (int) (float) px : def;
        }
        /** AOSP: an int item answers its data (the enum names aapt compiles to
         *  -1 / -2 included), a dimension its pixel size, anything else def. */
        @Override public int getLayoutDimension(int index, int def) {
            Integer e = macapkLayoutEnumOrInt(raw(index));
            if (e != null) return e;
            Float px = dimenPx(index);
            return px != null ? getDimensionPixelSize(index, def) : def;
        }
        @Override public int getLayoutDimension(int index, String name) {
            Integer e = macapkLayoutEnumOrInt(raw(index));
            if (e != null) return e;
            if (dimenPx(index) != null) return getDimensionPixelSize(index, 0);
            throw new UnsupportedOperationException(getPositionDescription()
                    + ": You must supply a " + name + " attribute.");
        }
        private Float dimenPx(int index) {
            String v = raw(index);
            if (v == null) return null;
            if (v.startsWith("@")) {
                int id = getResourceId(index, 0);
                Resources r = getResources();
                if (id == 0 || r == null) return null;
                // macapkDimenPx, not getDimension: the former returns null for
                // "no such dimen", which a real 0dp must not be confused with.
                return r.macapkDimenPx(id);
            }
            Resources r = getResources();
            return TypedArray.macapkParseDimenLiteral(v, r != null ? r.getDisplayMetrics() : null);
        }
        @Override public float getFraction(int index, int base, int pbase, float def) {
            String v = raw(index);
            if (v == null || v.startsWith("@") || v.startsWith("?")) return def;
            try {
                if (v.endsWith("%p")) {
                    return Float.parseFloat(v.substring(0, v.length() - 2)) / 100f * pbase;
                }
                if (v.endsWith("%")) {
                    return Float.parseFloat(v.substring(0, v.length() - 1)) / 100f * base;
                }
                return Float.parseFloat(v) * base;
            } catch (NumberFormatException e) { return def; }
        }

        @Override public android.graphics.drawable.Drawable getDrawable(int index) {
            String v = raw(index);
            Resources r = getResources();
            if (v == null || r == null) return null;
            // Shared with the XML-drawable inflater: handles "#argb", "?attr/x",
            // "@drawable/x", "@mipmap/x" and "@color/x" exactly alike.
            return r.macapkResolveDrawableRef(v);
        }
        @Override public CharSequence[] getTextArray(int index) {
            int id = getResourceId(index, 0);
            Resources r = getResources();
            if (id != 0 && r != null) {
                try { return r.getTextArray(id); } catch (Throwable ignored) {}
            }
            return new CharSequence[0];
        }

        @Override public int getType(int index) {
            String v = raw(index);
            if (v == null) return android.util.TypedValue.TYPE_NULL;
            if (v.startsWith("@") || v.startsWith("?")) {
                return android.util.TypedValue.TYPE_REFERENCE;
            }
            if (v.startsWith("#")) return android.util.TypedValue.TYPE_INT_COLOR_ARGB8;
            if (v.equals("true") || v.equals("false")) {
                return android.util.TypedValue.TYPE_INT_BOOLEAN;
            }
            for (String u : new String[]{"dip", "dp", "sp", "px", "pt", "in", "mm"}) {
                if (v.endsWith(u)) return android.util.TypedValue.TYPE_DIMENSION;
            }
            if (v.endsWith("%")) return android.util.TypedValue.TYPE_FRACTION;
            try { Integer.decode(v);   return android.util.TypedValue.TYPE_INT_DEC; }
            catch (NumberFormatException ignore) {}
            try { Float.parseFloat(v); return android.util.TypedValue.TYPE_FLOAT; }
            catch (NumberFormatException ignore) {}
            return android.util.TypedValue.TYPE_STRING;
        }

        @Override public boolean getValue(int index, android.util.TypedValue outValue) {
            if (outValue == null) return false;
            String v = raw(index);
            if (v == null) {
                outValue.type = android.util.TypedValue.TYPE_NULL;
                outValue.string = null;
                outValue.data = 0;
                return false;
            }
            // v1.0.9xx: the ONE raw->TypedValue resolver, with reference
            // threading, replaces the ad-hoc packer below: on a device the
            // array cursor RESOLVES each element (AssetManager2::ResolveArray),
            // so `@color/x` answers the leaf colour with resourceId = x, and
            // `@dimen/y` the packed complex. The legacy switch remains the
            // fallback for anything the resolver cannot validate.
            Resources r = getResources();
            if (r != null) {
                android.util.TypedValue out = new android.util.TypedValue();
                try {
                    Resources.macapkClearValueOrigin();
                    if (r.macapkResolveRawToTypedValue(v, null, null, true, out, true, 0)) {
                        outValue.type = out.type;
                        outValue.data = out.data;
                        outValue.resourceId = out.resourceId;
                        outValue.string = out.string;
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
            int type = getType(index);
            outValue.type = type;
            outValue.string = null;
            outValue.data = 0;
            switch (type) {
                case android.util.TypedValue.TYPE_REFERENCE:
                    outValue.data = getResourceId(index, 0);
                    outValue.resourceId = outValue.data;
                    break;
                case android.util.TypedValue.TYPE_INT_COLOR_ARGB8:
                    outValue.data = getColor(index, 0);
                    break;
                case android.util.TypedValue.TYPE_INT_BOOLEAN:
                    outValue.data = getBoolean(index, false) ? 1 : 0;
                    break;
                case android.util.TypedValue.TYPE_INT_DEC:
                    outValue.data = getInt(index, 0);
                    break;
                case android.util.TypedValue.TYPE_FLOAT:
                    outValue.data = Float.floatToIntBits(getFloat(index, 0f));
                    break;
                case android.util.TypedValue.TYPE_DIMENSION:
                    outValue.data = (int) getDimension(index, 0f);
                    break;
                default:
                    outValue.string = v;
                    break;
            }
            return true;
        }
        /** AOSP: null when the slot holds no value (see TypedArray.peekValue). */
        @Override public android.util.TypedValue peekValue(int index) {
            android.util.TypedValue v = new android.util.TypedValue();
            return getValue(index, v) && v.type != android.util.TypedValue.TYPE_NULL ? v : null;
        }
        @Override public android.util.TypedValue macapkPeekRecord(int index) {
            android.util.TypedValue v = new android.util.TypedValue();
            getValue(index, v);
            return v;
        }
    }

    /** AOSP Resources.getDrawableForDensity(int, int)/(int, int, Theme):
     *  load a drawable at a specific density. We don't have density
     *  variants, so route to getDrawable. */
    public android.graphics.drawable.Drawable getDrawableForDensity(int id, int density) {
        return getDrawable(id);
    }
    public android.graphics.drawable.Drawable getDrawableForDensity(int id, int density, Theme theme) {
        return getDrawable(id, theme);
    }

    /** AOSP Resources.openRawResourceFd(int): returns an
     *  AssetFileDescriptor for the raw resource. We don't have a
     *  real fd; null = "not available." */
    public AssetFileDescriptor openRawResourceFd(int id) { return null; }

    /** AOSP Resources.parseBundleExtra(String, AttributeSet, Bundle):
     *  parses an <extra> tag's attributes into the given bundle. */
    public void parseBundleExtra(String tagName, android.util.AttributeSet attrs, android.os.Bundle outBundle) {}
    public void parseBundleExtras(android.content.res.XmlResourceParser parser, android.os.Bundle outBundle) {}

    /** AOSP Resources.updateConfiguration(Configuration, DisplayMetrics):
     *  copies the incoming configuration into this Resources' live
     *  Configuration so subsequent getConfiguration() reads reflect a
     *  configuration change (rotation / night-mode / locale / density). The
     *  field reference is final, so we copy fields in place via setTo rather
     *  than swapping the object -- callers (Activity.dispatchConfigurationChanged)
     *  and any cached getConfiguration() result therefore observe the update.
     *  A non-null DisplayMetrics is copied in (see the v1.0.1637 note in the
     *  body); passing null leaves it unchanged, matching AOSP's tolerance of a
     *  null metrics argument.
     *
     *  v1.0.1047: this used to be the field copy and NOTHING ELSE, which made
     *  it a lie for any config change that a cached artifact depends on. AOSP's
     *  ResourcesImpl.updateConfiguration (android14-release
     *  ResourcesImpl.java:371-497) computes the changed bits first
     *  (`final @Config int configChanges = calcConfigChanges(config);`), pushes
     *  the new configuration into the AssetManager
     *  (`mAssets.setConfiguration(... mConfiguration.uiMode, ...)`, :461-474)
     *  and then INVALIDATES, at :482-488:
     *      mDrawableCache.onConfigurationChange(configChanges);
     *      mColorDrawableCache.onConfigurationChange(configChanges);
     *      mComplexColorCache.onConfigurationChange(configChanges);
     *      mAnimatorCache.onConfigurationChange(configChanges);
     *      mStateListAnimatorCache.onConfigurationChange(configChanges);
     *      flushLayoutCache();
     *  Our AssetManager half is the ResourceIndex, which Boot rebuilds and
     *  swaps for a night flip (a ResourceIndex bakes its qualifiers at
     *  construction, so there is no setConfiguration to call). What was missing
     *  is the invalidation, so a drawable decoded before the change kept being
     *  served after it.
     *
     *  We drop the WHOLE drawable cache rather than filtering by
     *  changingConfigurations the way AOSP's ThemedResourceCache does, because
     *  we do not track per-entry changingConfigurations. That is conservative in
     *  the safe direction: AOSP guarantees no entry survives a change it depends
     *  on, and evicting extra entries costs only a re-decode.
     *
     *  Wave 7 (2026-09-26, MACAPK_RES_CONFIG_MERGE): the update MERGES, it does
     *  not copy. AOSP Resources.updateConfiguration (android14-release
     *  Resources.java:2170-2180) forwards to ResourcesImpl.updateConfiguration
     *  (ResourcesImpl.java:371-497), whose change set comes from
     *  calcConfigChanges (:507-525):
     *      mTmpConfig.setTo(config);
     *      ...
     *      if (mTmpConfig.getLocales().isEmpty()) {
     *          mTmpConfig.setLocales(LocaleList.getDefault());
     *      }
     *      return mConfiguration.updateFrom(mTmpConfig);
     *  and updateFrom skips every UNDEFINED field of the delta. The copy that
     *  was here made a PARTIAL configuration wipe the rest: the classic locale
     *  idiom `c = new Configuration(); c.locale = X; res.updateConfiguration(c,
     *  dm)` -- Aegis on every activity create, Telegram's applyLanguage,
     *  Minecraft, HCR 1.8.1's getjar SDK -- replaced the app's density, uiMode,
     *  screen sizes, screen layout and window bounds with the delta's values
     *  (the phone defaults of our old no-arg constructor, or zeros once that
     *  constructor is AOSP's unset()). Measured by debug/w7rescfg_gate.sh.
     *  After the merge AOSP (:402-407) gives an empty locale list the default
     *  one, then (:432-441) derives the DisplayMetrics density from the merged
     *  configuration and the scaled density from its font scale:
     *      if (mConfiguration.densityDpi != Configuration.DENSITY_DPI_UNDEFINED) {
     *          mMetrics.densityDpi = mConfiguration.densityDpi;
     *          mMetrics.density =
     *                  mConfiguration.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
     *      }
     *      // Protect against an unset fontScale.
     *      mMetrics.scaledDensity = mMetrics.density *
     *              (mConfiguration.fontScale != 0 ? mConfiguration.fontScale : 1.0f);
     *  (DENSITY_DEFAULT_SCALE is 1.0f / DENSITY_DEFAULT, DisplayMetrics.java:225.)
     *  Not mirrored, with reasons: the CompatibilityInfo SCALING (we have no
     *  compat mode, so isScalingRequired() and !supportsScreen() never hold;
     *  what the default CompatibilityInfo still does, the density write, is
     *  mirrored in macapkCalcConfigChanges); the CONFIG_LOCALE best-match of a multi-locale list against the
     *  AssetManager's locales (:409-429, our ResourceIndex selects its locale at
     *  construction and exposes no locale set to match against); the
     *  fontScaleConverter and the plural-rule refresh (no such state here).
     *  MACAPK_RES_CONFIG_MERGE=0 restores the pre-change copy exactly. It is a
     *  member of Configuration's STAGED merge family (see the gate block in
     *  Configuration.java): unset, it follows MACAPK_CONFIG_MERGE_FAMILY, and
     *  that unset, Configuration.MACAPK_CONFIG_MERGE_DEFAULT_ON -- false until
     *  the enable bundle (debug/w7rescfg_verify/enable.py) wires the
     *  macapkPhoneDefaults() sites and flips it; `=1` forces it on. */
    static final boolean MACAPK_RES_CONFIG_MERGE =
        Configuration.macapkMergeFamilyGate("MACAPK_RES_CONFIG_MERGE");

    public void updateConfiguration(Configuration config, DisplayMetrics metrics) {
        if (MACAPK_RES_CONFIG_MERGE) {
            macapkUpdateConfigurationMerge(config, metrics);
            return;
        }
        // v1.0.1637: honour `metrics`. AOSP ResourcesImpl.updateConfiguration
        // (android14-release) copies it FIRST, unconditionally and before any
        // config early-out:
        //     if (metrics != null) { mMetrics.setTo(metrics); }
        // This method used to ignore the argument, which is why getDisplayMetrics()
        // could never follow a display resize (the field is final, so setTo in
        // place is also how every holder of the returned object sees the change).
        // A caller passing getDisplayMetrics() itself (the common locale-switch
        // idiom) is a harmless self-copy.
        if (metrics != null && metrics != this.metrics) {
            try { this.metrics.setTo(metrics); } catch (Throwable ignored) {}
        }
        if (config == null || this.config == null || config == this.config) return;
        // AOSP calcConfigChanges runs BEFORE the copy, against the old values.
        int configChanges;
        try {
            configChanges = this.config.diff(config);
        } catch (Throwable t) {
            // AOSP's calcConfigChanges(null) returns 0xFFFFFFFF, i.e. "assume
            // everything changed". A diff we could not compute is the same
            // state of knowledge, so invalidate rather than skip.
            configChanges = 0xFFFFFFFF;
        }
        try { this.config.setTo(config); } catch (Throwable ignored) {}
        if (configChanges == 0) return;
        try { macapkDrawableCache.clear(); } catch (Throwable ignored) {}
        try { flushLayoutCache(); } catch (Throwable ignored) {}
    }

    /** MACAPK_RES_CONFIG_MERGE: AOSP ResourcesImpl.updateConfiguration's order
     *  (android14-release :386-488) on our state -- see the method comment above
     *  for the quoted source and what is left out. */
    private void macapkUpdateConfigurationMerge(Configuration config, DisplayMetrics metrics) {
        // :386-388 `if (metrics != null) { mMetrics.setTo(metrics); }` -- first,
        // before any configuration work. Passing getDisplayMetrics() itself (the
        // common locale-switch idiom) is a self-copy, skipped.
        if (metrics != null && metrics != this.metrics) {
            try { this.metrics.setTo(metrics); } catch (Throwable ignored) {}
        }
        if (this.config == null) return;
        final Configuration cur = this.config;
        // :400 `final @Config int configChanges = calcConfigChanges(config);`
        int configChanges;
        try {
            configChanges = macapkCalcConfigChanges(config);
        } catch (Throwable t) {
            // Same state of knowledge as calcConfigChanges(null): assume every
            // flag changed, so nothing cached survives a merge we could not do.
            configChanges = 0xFFFFFFFF;
        }
        // :402-407 "If even after the update there are no Locales set, grab the
        // default locales." (A no-op while MACAPK_CONFIG_LOCALE_LIST is off:
        // getLocales() then never answers empty.)
        try {
            if (cur.macapkLocalesEmpty()) cur.setLocales(Configuration.macapkDefaultLocales());
        } catch (Throwable ignored) {}
        // :432-441 the metrics follow the merged configuration.
        if (cur.densityDpi != Configuration.DENSITY_DPI_UNDEFINED) {
            this.metrics.densityDpi = cur.densityDpi;
            this.metrics.density = cur.densityDpi * (1.0f / DisplayMetrics.DENSITY_DEFAULT);
        }
        this.metrics.scaledDensity = this.metrics.density
                * (cur.fontScale != 0 ? cur.fontScale : 1.0f);
        // :482-488 the caches, by the change set (see the eviction note above).
        if (configChanges == 0) return;
        try { macapkDrawableCache.clear(); } catch (Throwable ignored) {}
        try { flushLayoutCache(); } catch (Throwable ignored) {}
    }

    /** AOSP ResourcesImpl.calcConfigChanges (android14-release :507-525):
     *      if (config == null) {
     *          // If there is no configuration, assume all flags have changed.
     *          return 0xFFFFFFFF;
     *      }
     *      mTmpConfig.setTo(config);
     *      int density = config.densityDpi;
     *      if (density == Configuration.DENSITY_DPI_UNDEFINED) {
     *          density = mMetrics.noncompatDensityDpi;
     *      }
     *      mDisplayAdjustments.getCompatibilityInfo().applyToConfiguration(density, mTmpConfig);
     *      if (mTmpConfig.getLocales().isEmpty()) {
     *          mTmpConfig.setLocales(LocaleList.getDefault());
     *      }
     *      return mConfiguration.updateFrom(mTmpConfig);
     *  applyToConfiguration (CompatibilityInfo.java:582-599) writes
     *  `inoutConfig.densityDpi = displayDensity;` unconditionally, and scales
     *  only when isScalingRequired(), which never holds here. So a delta with
     *  an UNDEFINED density takes the density of the metrics the caller just
     *  passed (mMetrics is already setTo them, :386-388); it is not left out of
     *  the merge. Our DisplayMetrics has no noncompat* copy, and with no compat
     *  scaling noncompatDensityDpi IS densityDpi. Every corpus caller passes
     *  getDisplayMetrics() itself, so the fill is our own density back; a caller
     *  that passes other metrics with a partial configuration gets the
     *  configuration AND the metrics at the metrics' density, where the merge
     *  without the fill kept the old density and then overwrote the caller's
     *  metrics with it (the inverse).
     *  The copy matters beyond style: neither fill may write into the caller's
     *  object. A self-update (`config` IS our configuration, e.g. after an app
     *  wrote getConfiguration().locale directly) merges a snapshot of itself,
     *  which changes nothing, exactly as AOSP's does. */
    private int macapkCalcConfigChanges(Configuration config) {
        if (config == null) return 0xFFFFFFFF;
        final Configuration tmp = new Configuration(config);
        if (tmp.densityDpi == Configuration.DENSITY_DPI_UNDEFINED && this.metrics.densityDpi > 0) {
            tmp.densityDpi = this.metrics.densityDpi;
        }
        if (tmp.macapkLocalesEmpty()) tmp.setLocales(Configuration.macapkDefaultLocales());
        return this.config.macapkMergeFrom(tmp);
    }
    public void updateConfiguration(Configuration config, DisplayMetrics metrics, Object compatInfo) {
        updateConfiguration(config, metrics);
    }

    /** AOSP Resources.getResourceForeground: returns Drawable from a theme attr. */
    public Object getResourceForeground(int id) { return null; }

    /** AOSP Resources.getMovie (API 1, deprecated): load an animated GIF as a
     *  Movie. No Movie decoder wired, so callers fall back to a static drawable. */
    public android.graphics.Movie getMovie(int id) { return null; }

    /** AOSP Resources.getFont(int): API-26+ load a font resource.
     *
     *  AOSP (Resources.java:470, android14-release) resolves the id to its
     *  compiled file path and hands it to ResourcesImpl.loadFont (:953),
     *  which branches on the extension: an `.xml` path is a `<font-family>`
     *  descriptor parsed by FontResourcesParser and realized by
     *  Typeface.createFromResources; anything else is a raw font file loaded
     *  through Typeface.Builder. getFont throws NotFoundException when that
     *  yields nothing.
     *
     *  This method used to `return Typeface.DEFAULT` without ever looking at
     *  `id`, so EVERY `@font/...` resource silently resolved to the system
     *  typeface: an app's branded font was dropped across its entire UI with
     *  no exception raised anywhere. */
    public android.graphics.Typeface getFont(int id) {
        android.graphics.Typeface tf = macapkLoadFont(id);
        if (tf != null) return tf;
        throw new NotFoundException("Font resource ID #0x" + Integer.toHexString(id));
    }

    /** ResourcesImpl.loadFont's extension branch. Returns null when the id is
     *  not a resolvable font resource (the caller then raises the AOSP
     *  NotFoundException). */
    private android.graphics.Typeface macapkLoadFont(int id) {
        if (id == 0) return null;
        String path = macapkResourcePath(id);
        if (path == null) return null;
        if (path.endsWith(".xml")) return macapkLoadFontFamily(id);
        // A raw font file: AOSP hands it to Typeface.Builder with
        // RESOLVE_BY_FONT_TABLE, i.e. the weight and slant come from the font's
        // OWN OS/2 table. This used to force 400/upright with a comment saying
        // "we cannot read those" -- v1.0.936 can (MacApkFont.readOs2Style), so
        // `@font/mybold` now reports weight 700 the way a device does instead of
        // claiming to be Regular. The bytes come through openRawResource, which
        // serves the installed apk's entry (v1.0.1590) or a decoded-tree file.
        try (java.io.InputStream in = openRawResource(id)) {
            if (in == null) return null;
            return android.graphics.Typeface.Builder.macapkFromStream(in).build();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Build a Typeface from a raw font resource (ttf/otf/ttc). Mirrors AOSP's
     *  `new Typeface.Builder(assets, file).setWeight(w).setItalic(i).build()`,
     *  which falls back to the default typeface rather than throwing when the
     *  bytes will not parse. Null only when the resource cannot be opened. */
    private android.graphics.Typeface macapkTypefaceFromResource(
            int rid, int weight, boolean italic) {
        try (java.io.InputStream in = openRawResource(rid)) {
            if (in == null) return null;
            return android.graphics.Typeface.Builder.macapkFromStream(in)
                .setWeight(weight).setItalic(italic).build();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parse a `res/font/<name>.xml` `<font-family>` descriptor. AOSP
     *  FontResourcesParser.readFamily recognizes two shapes:
     *
     *  1. A DOWNLOADABLE font: fontProviderAuthority + fontProviderPackage +
     *     fontProviderQuery name a Content Provider (in practice the Google
     *     Fonts provider, which ships inside GMS). AOSP asks
     *     FontsContract.getFontSync for it and, when the provider is not on
     *     the device, hands back the DEFAULT typeface (Typeface.java:330,
     *     "Downloaded font and it wasn't cached ... return a default font
     *     instead"). Mac.apk answers the de-Googled no-GMS contract, so no
     *     provider resolves and DEFAULT is the AOSP-faithful answer here --
     *     never a pretend fetch.
     *
     *  2. STATIC files: one `<font android:font="@font/x" android:fontWeight=
     *     "400" android:fontStyle="normal"/>` per variant. AOSP builds a
     *     FontFamily over all of them and selects the entry with the lowest
     *     FontStyle.getMatchScore against FontStyle(FONT_WEIGHT_NORMAL=400,
     *     FONT_SLANT_UPRIGHT), keeping that entry's style on the returned
     *     Typeface. We have no FontFamily/CustomFallbackBuilder, so we load
     *     exactly that best-match file; Typeface.create(family, style) then
     *     derives the bold/italic variants off it the way Paint expects. */
    private android.graphics.Typeface macapkLoadFontFamily(int xmlId) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            org.w3c.dom.Document doc;
            try (java.io.InputStream in = openRawResource(xmlId)) {
                if (in == null) return null;
                doc = f.newDocumentBuilder().parse(in);
            }
            org.w3c.dom.Element root = doc.getDocumentElement();
            if (root == null || !"font-family".equals(macapkLocalTag(root))) return null;

            if (macapkFontAttr(root, "fontProviderAuthority") != null
                    && macapkFontAttr(root, "fontProviderPackage") != null
                    && macapkFontAttr(root, "fontProviderQuery") != null) {
                return android.graphics.Typeface.DEFAULT;
            }

            // v1.0.936: build the WHOLE family, not just its best 400-upright
            // entry. AOSP's FontResourcesParser + Typeface.createFromResources
            // construct a FontFamily over EVERY <font> element and then match
            // into it, so an app shipping Regular + Bold and asking for bold
            // gets its OWN Bold FILE. Loading only the best default-style match
            // meant `Typeface.create(appFamily, BOLD)` had a single 400 face to
            // work with and minikin's fakery ALGORITHMICALLY EMBOLDENED it --
            // a synthetic weight standing in for a real font the app shipped.
            //
            // Registering the faces with MacApkFontFamilies routes them through
            // the identical getClosestMatch / computeFakery path as a system
            // family, including the file-ORDER tiebreak (descriptor order is
            // preserved below for exactly that reason).
            java.util.List<Object[]> faces = new java.util.ArrayList<>();
            int bestRid = 0;
            int bestScore = Integer.MAX_VALUE;
            int bestWeight = 400;
            boolean bestItalic = false;
            org.w3c.dom.NodeList kids = root.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                org.w3c.dom.Node n = kids.item(i);
                if (!(n instanceof org.w3c.dom.Element)) continue;
                org.w3c.dom.Element e = (org.w3c.dom.Element) n;
                if (!"font".equals(macapkLocalTag(e))) continue;
                int rid = macapkFontRefId(macapkFontAttr(e, "font"));
                if (rid == 0) continue;
                String path = macapkResourcePath(rid);
                if (path == null || path.endsWith(".xml")) continue;
                android.graphics.MacApkFont.LoadedFace lf;
                try (java.io.InputStream fin = openRawResource(rid)) {
                    lf = fin == null ? null : android.graphics.MacApkFont.loadFace(fin);
                } catch (Throwable t) { lf = null; }
                if (lf == null || lf.font == null) continue;
                // An entry that DECLARES fontWeight/fontStyle wins over the
                // file's own table -- AOSP passes the declared values straight
                // to the FontFamily and only falls back to RESOLVE_BY_FONT_TABLE
                // when the attribute is absent. We can now honour both halves
                // of that because MacApkFont.loadFace reads the OS/2 table.
                int weight = lf.weight;
                String w = macapkFontAttr(e, "fontWeight");
                if (w != null) {
                    try { weight = Integer.parseInt(w.trim()); }
                    catch (NumberFormatException ignored) {}
                }
                String st = macapkFontAttr(e, "fontStyle");
                boolean italic = (st != null) ? "italic".equals(st) : lf.italic;
                faces.add(new Object[] { weight, italic, lf.font });
                // FontStyle.getMatchScore: |dWeight|/100 + (slant differs ? 2 : 0).
                int score = Math.abs(weight - 400) / 100 + (italic ? 2 : 0);
                if (score < bestScore) {
                    bestScore  = score;
                    bestRid    = rid;
                    bestWeight = weight;
                    bestItalic = italic;
                }
            }
            if (faces.isEmpty()) return null;
            String fam = android.graphics.MacApkFontFamilies.registerAppFamily(faces);
            android.graphics.Typeface tf = android.graphics.Typeface.macapkFromAppFamily(fam);
            if (tf != null) return tf;
            // Table gated off (MACAPK_FONT_FAMILY=0): fall back to the single
            // best default-style match, which is the pre-v936 behaviour.
            return macapkTypefaceFromResource(bestRid, bestWeight, bestItalic);
        } catch (Throwable t) {
            return null;
        }
    }

    /** androidx's compat font XML declares the SAME attribute names under the
     *  `app:` (res-auto) namespace alongside `android:`, so a font-family
     *  attribute has to be looked up in both. */
    private static final String MACAPK_RES_AUTO_NS =
        "http://schemas.android.com/apk/res-auto";
    private static String macapkFontAttr(org.w3c.dom.Element e, String name) {
        String v = e.getAttributeNS(MACAPK_ANDROID_NS, name);
        if (v == null || v.isEmpty()) v = e.getAttributeNS(MACAPK_RES_AUTO_NS, name);
        if (v == null || v.isEmpty()) v = e.getAttribute(name);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** Resolve a `<font android:font="@font/x">` reference to the font
     *  resource id, or 0 when it is not a resolvable font resource. */
    private int macapkFontRefId(String ref) {
        if (ref == null) return 0;
        ref = ref.trim();
        if (!ref.startsWith("@")) return 0;
        String body = ref.substring(1);
        int slash = body.indexOf('/');
        String name = slash >= 0 ? body.substring(slash + 1) : body;
        return getIdentifier(name, "font", null);
    }

    public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return System.identityHashCode(this); }

    /** AOSP Resources.flushLayoutCache: drops the cached XmlResourceParser
     *  for layout resources. We don't cache. */
    public void flushLayoutCache() {}

    /** AOSP Resources.getCompatibilityInfo: API-1+ legacy. */
    public Object getCompatibilityInfo() { return null; }

    /** AOSP Resources.addLoaders (API 30): registers runtime resource loaders.
     *  Mac.apk resolves resources from the base apk, so held loaders contribute
     *  no overrides; we accept them so an app that layers a ResourcesLoader does
     *  not crash. */
    public void addLoaders(android.content.res.loader.ResourcesLoader... loaders) { }

    /** AOSP Resources.removeLoaders (API 30). */
    public void removeLoaders(android.content.res.loader.ResourcesLoader... loaders) { }

}

