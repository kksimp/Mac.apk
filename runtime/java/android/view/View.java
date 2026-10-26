// android.view.View -- the absolute base class of the UI hierarchy.
// In v2, View is a thin Java shell that holds layout state. It does
// NOT own a native Cocoa view by default; only specific subclasses
// (SurfaceView, ImageView, TextView ...) attach a native handle.
//
// We expose enough of View's surface that AndEngine and similar
// frameworks can subclass it without ClassCastException at runtime.
package android.view;

import android.content.Context;
import android.graphics.Rect;

// AOSP: `public class View implements Drawable.Callback, KeyEvent.Callback,
// AccessibilityEventSource`. The Drawable.Callback piece is load-bearing: a
// Drawable (e.g. Lottie's LottieDrawable, AnimationDrawable, animated VectorDrawable)
// holds its host View as its callback and, on every animation tick, calls
// drawable.invalidateSelf() -> callback.invalidateDrawable(this). The JVM resolves
// that via invokeinterface Drawable$Callback; if View only HAS the methods but does
// not DECLARE the interface, the call throws IncompatibleClassChangeError (Lottie's
// LottieAnimationView "does not implement the requested interface Drawable$Callback"),
// which fired every frame and left duolingo's Lottie splash logo blank.
// The KeyEvent.Callback piece is the same kind of contract: AOSP declares
// `View implements ... KeyEvent.Callback ...`, and a KeyEvent routed through
// the Callback interface type (KeyEvent.dispatch, an app's own key router)
// resolves via invokeinterface -- a View that merely HAS onKeyDown/onKeyUp but
// does not DECLARE the interface throws IncompatibleClassChangeError there.
// AccessibilityEventSource (docs/type-identity-audit_2026-08-12.md) is the
// same shape again: View already carried AOSP-descriptor-identical
// sendAccessibilityEvent(int) and sendAccessibilityEventUnchecked
// (AccessibilityEvent) -- see the "AOSP accessibility hook pairs" block below
// -- but never declared the type, so `instanceof AccessibilityEventSource`
// answered false and any caller holding a View only through this interface
// (an androidx accessibility helper, a delegate that type-checks its host)
// would throw IncompatibleClassChangeError on the invokeinterface, exactly
// like the other two. Zero new methods; this is a pure declaration fix.
public class View implements android.graphics.drawable.Drawable.Callback,
                              android.view.KeyEvent.Callback,
                              android.view.accessibility.AccessibilityEventSource {
    // Process-environment snapshots for the per-draw / per-frame / per-layout /
    // per-event sites below (debug/w9textdraw_verify/hoist_getenv.py). A
    // System.getenv call encodes the name and hashes it on every call; the
    // environment it reads is copied once at JVM start (JDK ProcessEnvironment,
    // "subsequent calls to putenv/setenv from C will not be visible from Java
    // code"), so one read at class init is the same value. First in the body, so
    // no other static initializer of this class can observe them unset.
    private static final String ENV_MACAPK_CARD_CLIP = System.getenv("MACAPK_CARD_CLIP");
    private static final String ENV_MACAPK_CLIP_OUTLINE = System.getenv("MACAPK_CLIP_OUTLINE");
    private static final String ENV_MACAPK_EDGE_TO_EDGE = System.getenv("MACAPK_EDGE_TO_EDGE");
    private static final String ENV_MACAPK_INSETS_DISPATCH_LEGACY = System.getenv("MACAPK_INSETS_DISPATCH_LEGACY");
    private static final String ENV_MACAPK_TRACE_BGTINT = System.getenv("MACAPK_TRACE_BGTINT");
    private static final String ENV_MACAPK_TRACE_CHROME = System.getenv("MACAPK_TRACE_CHROME");
    private static final String ENV_MACAPK_TRACE_DEFSTYLE = System.getenv("MACAPK_TRACE_DEFSTYLE");
    private static final String ENV_MACAPK_TRACE_EDITMODE = System.getenv("MACAPK_TRACE_EDITMODE");
    private static final String ENV_MACAPK_TRACE_INSETS = System.getenv("MACAPK_TRACE_INSETS");
    private static final String ENV_MACAPK_TRACE_MEASURE = System.getenv("MACAPK_TRACE_MEASURE");
    private static final String ENV_MACAPK_TRACE_RIPPLE = System.getenv("MACAPK_TRACE_RIPPLE");
    private static final String ENV_MACAPK_TRACE_SETBG = System.getenv("MACAPK_TRACE_SETBG");
    private static final String ENV_MACAPK_TRACE_TOUCH = System.getenv("MACAPK_TRACE_TOUCH");

    static final boolean TRACE_COMPOSE = "1".equals(System.getenv("MACAPK_TRACE_COMPOSE"));
    // ---- Public constants used by AndEngine + apps ----
    public static final int VISIBLE   = 0x00000000;
    public static final int INVISIBLE = 0x00000004;
    public static final int GONE      = 0x00000008;

    public static final int NO_ID     = -1;

    // System UI flags. AndEngine does setSystemUiVisibility(...) at boot.
    public static final int SYSTEM_UI_FLAG_VISIBLE              = 0;
    public static final int SYSTEM_UI_FLAG_LOW_PROFILE          = 0x1;
    public static final int SYSTEM_UI_FLAG_HIDE_NAVIGATION      = 0x2;
    public static final int SYSTEM_UI_FLAG_FULLSCREEN           = 0x4;
    public static final int SYSTEM_UI_FLAG_LAYOUT_STABLE        = 0x100;
    public static final int SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 0x200;
    public static final int SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN    = 0x400;
    public static final int SYSTEM_UI_FLAG_IMMERSIVE            = 0x800;
    public static final int SYSTEM_UI_FLAG_IMMERSIVE_STICKY     = 0x1000;

    public static final int LAYER_TYPE_NONE     = 0;
    public static final int LAYER_TYPE_SOFTWARE = 1;
    public static final int LAYER_TYPE_HARDWARE = 2;

    // ---- Layout state ----
    private final Context context;
    private int id = NO_ID;
    private int left, top, right, bottom;
    private int paddingLeft, paddingTop, paddingRight, paddingBottom;
    /** AOSP's user-padding bookkeeping (View.java:4560-4590). `paddingLeft..Bottom`
     *  above are AOSP's mPadding* -- the RESOLVED, absolute padding actually used
     *  for measure/draw. These four record what the CALLER asked for, which is a
     *  different question and the only way to answer isPaddingRelative() and to
     *  re-resolve start/end into left/right when the layout direction changes.
     *  UNDEFINED_PADDING is AOSP's sentinel (View.java:4582,
     *  `private static final int UNDEFINED_PADDING = Integer.MIN_VALUE;`) meaning
     *  "this edge was never set relatively", so 0 stays a legal relative padding. */
    private static final int UNDEFINED_PADDING = Integer.MIN_VALUE;
    private int mUserPaddingStart = UNDEFINED_PADDING;
    private int mUserPaddingEnd = UNDEFINED_PADDING;
    private int mUserPaddingLeftInitial;
    private int mUserPaddingRightInitial;
    private boolean mPaddingResolved;
    private int visibility = VISIBLE;
    private boolean enabled = true;
    private boolean focusable = false;
    private boolean focusableInTouchMode = false;
    // AOSP focus state machine (v1.0.420). mIsFocused = this view holds focus
    // (PFLAG_FOCUSED). Was hard-stubbed: requestFocus()=>true stored nothing,
    // isFocused()/hasFocus()=>false, onFocusChanged never fired -> Compose
    // text-field focus + key routing dead (the login-screen blocker).
    boolean mIsFocused = false;
    /** AOSP View focus directions. */
    public static final int FOCUS_BACKWARD = 0x00000001;
    public static final int FOCUS_FORWARD  = 0x00000002;
    public static final int FOCUS_LEFT     = 0x00000011;
    public static final int FOCUS_UP       = 0x00000021;
    public static final int FOCUS_RIGHT    = 0x00000042;
    public static final int FOCUS_DOWN     = 0x00000082;
    private boolean clickable = false;
    private boolean longClickable = false;
    private boolean pressed = false;
    private float alpha = 1.0f;
    private Object tag;
    private ViewParent parent;
    private ViewGroup.LayoutParams layoutParams;

    // ---- Listeners ----
    private OnTouchListener onTouchListener;
    private OnClickListener onClickListener;
    private OnLongClickListener onLongClickListener;
    private OnFocusChangeListener onFocusChangeListener;

    // Native handle if the subclass attached one (Cocoa view pointer).
    protected long nativeHandle;

    public View(Context context) {
        this.context = context;
        // AOSP View(Context) latches sThrowOnInvalidFloatProperties from
        // targetSdkVersion here (View.java:5370) -- the FIRST View constructed
        // in the process decides it for every View afterwards. See
        // sanitizeFloatPropertyValue.
        macapkLatchFloatPropertyThrow(context);
        macapkInitViewCompat(context);
    }

    /** AOSP's `if (!sCompatibilityDone && context != null)` block, View.java
     *  :5876-5922 (android16-release): the first View built in the process
     *  resolves the whole compat flag set from the APP's targetSdkVersion.
     *
     *  Only the flags with a REAL consumer in this tree live here. AOSP sets 13;
     *  declaring one whose behaviour we do not implement would be a gate that
     *  cannot fire, which reads as coverage and is worse than an honest gap.
     *  The unimplemented ones are inventoried in
     *  docs/legacy-compat-audit_2026-08-24.md; several are already stuck on
     *  AOSP's LEGACY branch for every app (our measure() has no
     *  spec-unchanged skip at all, so sAlwaysRemeasureExactly is effectively
     *  always true), and gating those means implementing the MODERN behaviour
     *  first -- a separate, sweepable change.
     *
     *  DEVIATION from AOSP, deliberate and the same one macapkLatchFloatPropertyThrow
     *  makes: AOSP latches unconditionally because a real process always has an
     *  ApplicationInfo by the time the first View exists. Here the first View can
     *  be built against a bare or half-built Context, and latching off that would
     *  arm a legacy branch for a modern app FOR THE WHOLE PROCESS. So the latch
     *  only closes on a resolvable targetSdkVersion; until then every constructor
     *  re-tries and the MODERN defaults stand. */
    private static boolean sViewCompatDone;

    /** AOSP View.sPreserveMarginParamsInLayoutParamConversion (View.java:5891):
     *      // Prior to N, we would drop margins in LayoutParam conversions. The
     *      // fix triggers bugs in apps so we target check it to avoid breaking
     *      // existing apps.
     *      sPreserveMarginParamsInLayoutParamConversion =
     *              targetSdkVersion >= Build.VERSION_CODES.N;
     *  Consumed where AOSP consumes it: a ViewGroup's
     *  generateLayoutParams(ViewGroup.LayoutParams) (RelativeLayout.java:1131 is
     *  AOSP's own instance). Default TRUE, i.e. the existing behaviour, so no
     *  app at or above 24 changes. */
    protected static boolean sPreserveMarginParamsInLayoutParamConversion = true;

    /** AOSP: pre-N apps get cascaded ENTERED/EXITED (dispatchDragEnterExitInPreN). */
    static boolean sCascadedDragDrop;
    /** AOSP: pre-P apps may provide a 0-size drag shadow (made 1x1). */
    static boolean sAcceptZeroSizeDragShadow;
    static void macapkInitViewCompat(Context context) {
        if (sViewCompatDone) return;
        int targetSdkVersion = 0;
        boolean resolved = false;
        try {
            android.content.pm.ApplicationInfo ai =
                    (context != null) ? context.getApplicationInfo() : null;
            if (ai != null && android.app.Application.macapkTargetSdkKnown(ai.targetSdkVersion)) {
                targetSdkVersion = ai.targetSdkVersion;
                resolved = true;
            }
        } catch (Throwable ignored) { }
        if (!resolved) return;   // never latch an unresolved read
        sPreserveMarginParamsInLayoutParamConversion =
                targetSdkVersion >= android.os.Build.VERSION_CODES.N;
        // AOSP View(Context) constructor (android14-release):
        //     sCanFocusZeroSized = targetSdkVersion < Build.VERSION_CODES.P;
        // consumed by canTakeFocus() (the addFocusables qualification).
        sCanFocusZeroSized = targetSdkVersion < android.os.Build.VERSION_CODES.P;
        // AOSP View(Context) (android14 :5533, :5548):
        //     sCascadedDragDrop = targetSdkVersion < Build.VERSION_CODES.N;
        //     sAcceptZeroSizeDragShadow = targetSdkVersion < Build.VERSION_CODES.P;
        sCascadedDragDrop = targetSdkVersion < android.os.Build.VERSION_CODES.N;
        sAcceptZeroSizeDragShadow = targetSdkVersion < android.os.Build.VERSION_CODES.P;
        sViewCompatDone = true;
    }
    // AOSP's View(Context, AttributeSet) constructor resolves android:id
    // SYNCHRONOUSLY (core/java/android/view/View.java parses R.styleable.View_id
    // inline), so getId() is valid from inside a SUBCLASS'S OWN constructor --
    // real custom Views rely on this, and so does
    // androidx.fragment.app.FragmentContainerView's real 3-arg constructor,
    // which reads ((View) this).getId() before returning (it is called
    // directly, not through our post-construction LayoutInflater.applyAttributes
    // pass -- see FragmentInflaterFactory.tryRealContainerCtor). We used to leave
    // `id` at its NO_ID default here and apply it only after construction, which
    // is fine for any view that reads getId() no earlier than onFinishInflate,
    // but wrong for one that reads it inside its own <init>. Setting it twice
    // (once here, once in applyAttributes) is harmless -- same value, same
    // source (DomBackedAttributeSet.getAttributeResourceValue, already used the
    // same way by FragmentInflaterFactory).
    public View(Context context, android.util.AttributeSet attrs) {
        this(context);
        if (attrs != null) {
            int rid = attrs.getAttributeResourceValue(null, "id", NO_ID);
            if (rid != NO_ID) this.id = rid;
        }
    }
    public View(Context context, android.util.AttributeSet attrs, int defStyleAttr) { this(context, attrs, defStyleAttr, 0); }
    // 4-arg AOSP ctor (defStyleAttr + defStyleRes). Custom views that chain to
    // it (e.g. Calculator's HistoryLayout extends LinearLayout via the 4-arg
    // super) NoSuchMethodError'd without it.
    public View(Context context, android.util.AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        this(context, attrs);
        // lane layout, 2026-08-01: AOSP's View constructor reads
        // R.styleable.View_minWidth/View_minHeight through the FULL default-
        // style cascade (XML attrs > style > defStyleAttr > defStyleRes >
        // theme) and calls setMinimumWidth/Height. Ours dropped defStyleAttr/
        // defStyleRes on the floor, so a widget style's `android:minHeight`
        // never landed -- measured victim: Base.Widget.Material3.
        // BottomNavigationView sets android:minHeight=@dimen/m3_bottom_nav_
        // min_height (an 80dp ref-chain) via defStyleAttr=bottomNavigation-
        // Style, and the bar measured 63dp (its wrap height) instead of 80dp
        // (emulator A/B, docs/canvas-layout-fidelity_2026-08-01.md). Only the
        // defStyle-carrying constructors take this read: the 2-arg XML path
        // is LayoutInflater-driven exactly as before. Ids verified against
        // AOSP public-final.xml: minWidth=0x0101013f, minHeight=0x01010140.
        if (context != null && (defStyleAttr != 0 || defStyleRes != 0)) {
            try {
                // Cluster B3 (widget-fidelity, 2026-09-26): the padding family
                // rides the SAME cascade read (see macapkReadCtorPadding). With
                // MACAPK_VIEW_CTOR_PADDING=0 the call is the pre-B3 three-slot
                // array, byte for byte.
                final boolean readPad = MACAPK_VIEW_CTOR_PADDING;
                android.content.res.TypedArray a = context.obtainStyledAttributes(
                    attrs, readPad ? CTOR_STYLED_ATTRS_WITH_PADDING : CTOR_STYLED_ATTRS,
                    defStyleAttr, defStyleRes);
                int mw = a.getDimensionPixelSize(0, -1);
                int mh = a.getDimensionPixelSize(1, -1);
                // AOSP's View constructor reads R.styleable.View_background from
                // the same default-style cascade and calls setBackground. Ours
                // never did, so a background declared ONLY by a widget's style
                // was dropped. That is load-bearing well beyond the pixels:
                // material's NavigationBarView ctor does
                //     if (getBackground() instanceof ColorDrawable)
                //         shape.setFillColor(ColorStateList.valueOf(((ColorDrawable)bg).getColor()));
                // so with no style background it hands MaterialShapeDrawable NO
                // fill colour and the shape paints Paint-default BLACK. Measured:
                // Base.Widget.Material3.BottomNavigationView declares
                // `android:background=?colorSurfaceContainer` (-> #ecedf6 light
                // in F-Droid's own values/colors.xml), and our bottom bar
                // rendered BLACK once background drawables started painting at
                // all (docs/canvas-layout-fidelity_2026-08-01.md PHASE 2).
                // A colour-valued attr arrives here as a ColorDrawable via
                // TypedArray.getDrawable -> Resources, which is exactly the
                // shape material's instanceof test wants.
                android.graphics.drawable.Drawable styleBg = null;
                boolean declaredNullBg = false;
                try {
                    if (a.hasValue(2)) styleBg = a.getDrawable(2);
                    else declaredNullBg = a.macapkDeclaredTypeNull(2);
                } catch (Throwable ignored) {}
                MacapkCtorPadding ctorPad = null;
                if (readPad) {
                    try { ctorPad = macapkReadCtorPadding(a, 3); } catch (Throwable ignored) {}
                }
                a.recycle();
                // AOSP's attribute loop writes the user-padding bookkeeping
                // BEFORE setBackground (android14 View.java:5708-5746), so a
                // padded background installed next overwrites it exactly as it
                // does on a device.
                if (ctorPad != null) macapkCtorPaddingLoopWrites(ctorPad);
                // Only when the view has nothing yet: an XML android:background
                // on the element itself is applied later by the inflater and
                // must win, and AOSP's own precedence is XML-over-style anyway.
                if (styleBg != null && backgroundDrawable == null
                        && !macapkBackgroundExplicitlySet) {
                    setBackgroundDrawable(styleBg);
                }
                // ...and resolves the padding AFTER it (:6245-6310), so a
                // declared padding beats the background's own.
                if (ctorPad != null) macapkCtorPaddingResolve(ctorPad);
                // Cluster A3 (widget-fidelity, 2026-09-26): a background
                // DECLARED as @null / @empty (element, style or theme tier) is
                // AOSP's TYPE_NULL record, so hasValue is false and nothing is
                // installed, exactly as on a device. It still counts as
                // "declared" for macapkBackgroundExplicitlySet, the flag that
                // keeps the stand-in Toolbar / AppBarLayout chrome off: before
                // A2 the VectorDrawable stub set it by accident, and without
                // this a declared-transparent Toolbar got an opaque colorSurface
                // slab (F-Droid's own androidx Toolbar, background=@null: 0 ->
                // 20000 ink offline; Signal's DarkOverflowToolbar in the
                // corpus). Mac.apk-internal flag, so no AOSP deviation.
                if (declaredNullBg) macapkBackgroundExplicitlySet = true;
                if (ENV_MACAPK_TRACE_DEFSTYLE != null) {
                    try {
                        com.macapk.loader.NativeLog.i("Mac.apk.View",
                            "ctor4 " + getClass().getSimpleName()
                            + " dsa=0x" + Integer.toHexString(defStyleAttr)
                            + " dsr=0x" + Integer.toHexString(defStyleRes)
                            + " minW=" + mw + " minH=" + mh
                            + (ctorPad == null ? ""
                               : " padMask=0x" + Integer.toHexString(macapkCtorPaddingMask)
                                 + " pad=" + paddingLeft + "," + paddingTop + ","
                                 + paddingRight + "," + paddingBottom));
                    } catch (Throwable ignored) {}
                }
                if (mw >= 0) setMinimumWidth(mw);
                if (mh >= 0) setMinimumHeight(mh);
            } catch (Throwable ignored) {}
            // AOSP's View constructor reads R.styleable.View_elevation from the
            // same four-tier cascade (android14 View.java, `case
            // R.styleable.View_elevation: elevation = a.getDimension(...)` ->
            // setElevation), so a widget whose DEFAULT STYLE declares
            // android:elevation sits above its siblings. Ours read it only from
            // the element / style= tiers in the inflater. Measured: Material3
            // SearchView (Widget.Material3.SearchView, android:elevation
            // m3_searchview_elevation) is the FIRST child of ytdlnis's
            // CoordinatorLayout; at Z 0 the app bar and the result list drew
            // over the open search page (issue #5, 2026-10-19). Separate read so
            // the slot indexes above stay put. The inflater applies an element
            // android:elevation afterwards, which still wins.
            // MACAPK_VIEW_CTOR_ELEVATION=0 restores the old constructor.
            if (MACAPK_VIEW_CTOR_ELEVATION) {
                try {
                    android.content.res.TypedArray ea = context.obtainStyledAttributes(
                        attrs, CTOR_ELEVATION_ATTR, defStyleAttr, defStyleRes);
                    if (ea.hasValue(0)) {
                        float ev = ea.getDimension(0, 0f);
                        if (ev != 0f && getElevation() == 0f) setElevation(ev);
                    }
                    ea.recycle();
                } catch (Throwable ignored) {}
            }
        }
    }
    static final boolean MACAPK_VIEW_CTOR_ELEVATION =
        !"0".equals(System.getenv("MACAPK_VIEW_CTOR_ELEVATION"));
    private static final int[] CTOR_ELEVATION_ATTR = { 0x01010440 /*elevation*/ };

    // ------------------------------------------------------------------
    // Cluster B3 (widget-fidelity, 2026-09-26): CONSTRUCTOR-TIER PADDING.
    //
    // AOSP reads the whole padding family inside the View constructor, through
    // the same four-tier cascade as everything else it reads there (android14
    // View.java:5642-5643):
    //     final TypedArray a = context.obtainStyledAttributes(
    //             attrs, com.android.internal.R.styleable.View, defStyleAttr, defStyleRes);
    // so a widget's DEFAULT STYLE (defStyleAttr / defStyleRes) delivers its
    // padding before the subclass constructor runs. Ours read only
    // minWidth / minHeight / background here and left padding to the inflater,
    // which applies tiers 1 and 2 AFTER construction and never tier 3.
    //
    // That ordering is observable, not cosmetic. Material's MaterialButtonHelper
    // CAPTURES the padding inside the MaterialButton constructor and writes back
    // padding + insets (both apps' own shipped copies, read with javap:
    // gcalc bzh / anki, getPaddingStart/Top/End/Bottom, then
    // setPaddingRelative(start + insetLeft, top + insetTop, ...)). With no
    // constructor-tier padding it captured 0 and the inset alone survived, so
    // no later padding pass can reproduce the device answer:
    //   anki "Get Started" (tier 3 Widget.Material3.Button, 24dp / 6dp + the
    //     4dp insets): AOSP 48,20,48,20; ours 0,8,0,8 out of the constructor.
    //   gcalc history button (?materialIconButtonStyle, 10dp + 4dp insets):
    //     AOSP 28 all sides and 96x96.
    // Measured offline on the apps' OWN classes: debug/ctorpad_gate.sh.
    //
    // MACAPK_VIEW_CTOR_PADDING=0 restores the pre-B3 constructor exactly (the
    // three-slot read, no padding touched): a labelled bisect gate.
    // ------------------------------------------------------------------
    static final boolean MACAPK_VIEW_CTOR_PADDING =
        !"0".equals(System.getenv("MACAPK_VIEW_CTOR_PADDING"));

    /** The pre-B3 constructor read. Ids from AOSP public-final.xml
     *  (android14-release): minWidth 0x0101013f, minHeight 0x01010140,
     *  background 0x010100d4. */
    private static final int[] CTOR_STYLED_ATTRS = {
        0x0101013f /*minWidth*/, 0x01010140 /*minHeight*/, 0x010100d4 /*background*/ };
    /** The same three, then the nine padding attributes, in the ORDER of
     *  MACAPK_CTOR_PAD_* below. Ids from public-final.xml (android14-release)
     *  :241-245, :2019-2020, :2780-2781. */
    private static final int[] CTOR_STYLED_ATTRS_WITH_PADDING = {
        0x0101013f /*minWidth*/, 0x01010140 /*minHeight*/, 0x010100d4 /*background*/,
        0x010100d5 /*padding*/,       0x010100d6 /*paddingLeft*/,
        0x010100d7 /*paddingTop*/,    0x010100d8 /*paddingRight*/,
        0x010100d9 /*paddingBottom*/, 0x010103b3 /*paddingStart*/,
        0x010103b4 /*paddingEnd*/,    0x0101053d /*paddingHorizontal*/,
        0x0101053e /*paddingVertical*/ };
    /** Bit per padding attribute in macapkCtorPaddingMask (also the slot
     *  offset past the first three). */
    static final int MACAPK_CTOR_PAD_ALL = 0, MACAPK_CTOR_PAD_LEFT = 1,
        MACAPK_CTOR_PAD_TOP = 2, MACAPK_CTOR_PAD_RIGHT = 3, MACAPK_CTOR_PAD_BOTTOM = 4,
        MACAPK_CTOR_PAD_START = 5, MACAPK_CTOR_PAD_END = 6,
        MACAPK_CTOR_PAD_HORIZONTAL = 7, MACAPK_CTOR_PAD_VERTICAL = 8;

    /** Which padding attributes THIS view's 4-arg constructor found in the
     *  cascade (bit = MACAPK_CTOR_PAD_*); 0 when it read none (gate off, no
     *  default style, or no padding in any tier). Mac.apk-internal. */
    int macapkCtorPaddingMask;
    /** What the constructor read, for macapkCtorPaddingPx. Null when it read
     *  no padding. */
    private MacapkCtorPadding macapkCtorPadding;

    /** The pixel value this view's 4-arg constructor read for the padding
     *  attribute with LOCAL name `localName` ("padding", "paddingStart", ...),
     *  or Integer.MIN_VALUE when the constructor did not read that attribute
     *  (absent in every tier, gate off, or no default style).
     *
     *  Mac.apk-internal, for LayoutInflater's post-construction tier-1/2
     *  padding replay. AOSP applies padding exactly once, in the constructor,
     *  and a subclass constructor may then build on it: MaterialButtonHelper
     *  reads it back and writes padding + insets. A replay of the SAME value
     *  after construction therefore cannot add anything and can only undo the
     *  subclass (gcalc's history button: 28 -> 20). This lets the inflater
     *  skip exactly those re-applications and keep every other one. */
    int macapkCtorPaddingPx(String localName) {
        MacapkCtorPadding p = macapkCtorPadding;
        if (p == null || localName == null) return Integer.MIN_VALUE;
        int k;
        switch (localName) {
            case "padding":           k = MACAPK_CTOR_PAD_ALL; break;
            case "paddingLeft":       k = MACAPK_CTOR_PAD_LEFT; break;
            case "paddingTop":        k = MACAPK_CTOR_PAD_TOP; break;
            case "paddingRight":      k = MACAPK_CTOR_PAD_RIGHT; break;
            case "paddingBottom":     k = MACAPK_CTOR_PAD_BOTTOM; break;
            case "paddingStart":      k = MACAPK_CTOR_PAD_START; break;
            case "paddingEnd":        k = MACAPK_CTOR_PAD_END; break;
            case "paddingHorizontal": k = MACAPK_CTOR_PAD_HORIZONTAL; break;
            case "paddingVertical":   k = MACAPK_CTOR_PAD_VERTICAL; break;
            default: return Integer.MIN_VALUE;
        }
        if ((p.mask & (1 << k)) == 0) return Integer.MIN_VALUE;
        switch (k) {
            case MACAPK_CTOR_PAD_ALL:        return p.padding;
            case MACAPK_CTOR_PAD_LEFT:       return p.left;
            case MACAPK_CTOR_PAD_TOP:        return p.top;
            case MACAPK_CTOR_PAD_RIGHT:      return p.right;
            case MACAPK_CTOR_PAD_BOTTOM:     return p.bottom;
            case MACAPK_CTOR_PAD_START:      return p.start;
            case MACAPK_CTOR_PAD_END:        return p.end;
            case MACAPK_CTOR_PAD_HORIZONTAL: return p.paddingHorizontal;
            default:                         return p.paddingVertical;
        }
    }

    /** The values AOSP's attribute loop collects (android14 View.java
     *  :5654-5666 locals). -1 / UNDEFINED_PADDING are AOSP's own "not given"
     *  values. */
    private static final class MacapkCtorPadding {
        int mask;
        int padding = -1, paddingHorizontal = -1, paddingVertical = -1;
        int left = -1, top = -1, right = -1, bottom = -1;
        int start = UNDEFINED_PADDING, end = UNDEFINED_PADDING;
        boolean leftDefined, rightDefined, startDefined, endDefined;
    }

    /** Read the nine padding slots starting at `base`, the way AOSP's loop
     *  does (View.java:5705-5746): padding / paddingHorizontal / paddingLeft /
     *  paddingRight mark their sides DEFINED; paddingStart / paddingEnd are
     *  defined only when the value is not UNDEFINED_PADDING.
     *
     *  "Present" is AOSP's index list: a value, or an explicit @empty
     *  (TypedArray.hasValueOrEmpty; @null is not in it). One Mac.apk-only
     *  guard: a value our TypedArray HAS but cannot parse as a dimension (a
     *  reference form it does not chase yet) is treated as absent rather than
     *  as AOSP's @empty, so that side keeps its pre-B3 value instead of
     *  becoming -1. A device would have parsed it. Returns null when no slot
     *  is present, so a view with no padding anywhere is untouched. */
    private static MacapkCtorPadding macapkReadCtorPadding(
            android.content.res.TypedArray a, int base) {
        MacapkCtorPadding p = null;
        for (int k = 0; k <= MACAPK_CTOR_PAD_VERTICAL; k++) {
            int i = base + k;
            if (!a.hasValueOrEmpty(i)) continue;
            int v = a.getDimensionPixelSize(i, UNDEFINED_PADDING);
            if (v == UNDEFINED_PADDING && a.hasValue(i)) continue;   // unparseable: see above
            if (p == null) p = new MacapkCtorPadding();
            p.mask |= 1 << k;
            switch (k) {
                case MACAPK_CTOR_PAD_ALL:
                    p.padding = (v == UNDEFINED_PADDING) ? -1 : v;
                    p.leftDefined = p.rightDefined = true;
                    break;
                case MACAPK_CTOR_PAD_HORIZONTAL:
                    p.paddingHorizontal = (v == UNDEFINED_PADDING) ? -1 : v;
                    p.leftDefined = p.rightDefined = true;
                    break;
                case MACAPK_CTOR_PAD_VERTICAL:
                    p.paddingVertical = (v == UNDEFINED_PADDING) ? -1 : v;
                    break;
                case MACAPK_CTOR_PAD_LEFT:
                    p.left = (v == UNDEFINED_PADDING) ? -1 : v;
                    p.leftDefined = true;
                    break;
                case MACAPK_CTOR_PAD_TOP:
                    p.top = (v == UNDEFINED_PADDING) ? -1 : v;
                    break;
                case MACAPK_CTOR_PAD_RIGHT:
                    p.right = (v == UNDEFINED_PADDING) ? -1 : v;
                    p.rightDefined = true;
                    break;
                case MACAPK_CTOR_PAD_BOTTOM:
                    p.bottom = (v == UNDEFINED_PADDING) ? -1 : v;
                    break;
                case MACAPK_CTOR_PAD_START:
                    p.start = v;
                    p.startDefined = (v != UNDEFINED_PADDING);
                    break;
                case MACAPK_CTOR_PAD_END:
                    p.end = v;
                    p.endDefined = (v != UNDEFINED_PADDING);
                    break;
            }
        }
        return p;
    }

    /** The bookkeeping AOSP's loop writes as it goes (View.java:5708-5733),
     *  in R.styleable.View index order. aapt2 sorts a styleable's attributes
     *  by resource id (JavaClassGenerator, sorted_attributes), so the order is
     *  padding (0x010100d5), paddingLeft (d6), paddingRight (d8), then
     *  paddingHorizontal (0x0101053d): the last one present wins
     *  mUserPaddingLeftInitial / RightInitial, and the post-loop resolution
     *  below overwrites it in every case but one (relative padding present and
     *  only paddingLeft / paddingRight given). Then, as at :6238-6239, the
     *  start/end user padding is cached before setBackground. */
    private void macapkCtorPaddingLoopWrites(MacapkCtorPadding p) {
        if ((p.mask & (1 << MACAPK_CTOR_PAD_ALL)) != 0) {
            mUserPaddingLeftInitial = p.padding;
            mUserPaddingRightInitial = p.padding;
        }
        if ((p.mask & (1 << MACAPK_CTOR_PAD_LEFT)) != 0) mUserPaddingLeftInitial = p.left;
        if ((p.mask & (1 << MACAPK_CTOR_PAD_RIGHT)) != 0) mUserPaddingRightInitial = p.right;
        if ((p.mask & (1 << MACAPK_CTOR_PAD_HORIZONTAL)) != 0) {
            mUserPaddingLeftInitial = p.paddingHorizontal;
            mUserPaddingRightInitial = p.paddingHorizontal;
        }
        mUserPaddingStart = p.start;
        mUserPaddingEnd = p.end;
    }

    /** AOSP's post-loop padding resolution, android14 View.java:6245-6310,
     *  quoted where it decides:
     *      // Valid paddingHorizontal/paddingVertical beats leftPadding, rightPadding, topPadding,
     *      // bottomPadding, and padding set by background.  Valid padding beats everything.
     *      if (padding >= 0) { ... } else { if (paddingHorizontal >= 0) {...}
     *                                       if (paddingVertical >= 0) {...} }
     *      if (isRtlCompatibilityMode()) {
     *          if (!mLeftPaddingDefined && startPaddingDefined) leftPadding = startPadding;
     *          mUserPaddingLeftInitial = (leftPadding >= 0) ? leftPadding : mUserPaddingLeftInitial;
     *          ... (right / end the same) ...
     *      } else {
     *          final boolean hasRelativePadding = startPaddingDefined || endPaddingDefined;
     *          if (mLeftPaddingDefined && !hasRelativePadding) mUserPaddingLeftInitial = leftPadding;
     *          if (mRightPaddingDefined && !hasRelativePadding) mUserPaddingRightInitial = rightPadding;
     *      }
     *      internalSetPadding(mUserPaddingLeftInitial,
     *              topPadding >= 0 ? topPadding : mPaddingTop,
     *              mUserPaddingRightInitial,
     *              bottomPadding >= 0 ? bottomPadding : mPaddingBottom);
     *  Outside compatibility mode start/end are NOT applied here: they sit in
     *  mUserPaddingStart/End and resolvePadding (:21705) maps them onto
     *  left/right, which getPaddingStart() and measure() trigger here as on a
     *  device. This is the same rule set as LayoutInflater's
     *  macapkApplyResolvedPadding (the inflater's tier-1/2 replay), kept
     *  separate because that one works on raw strings after construction. */
    private void macapkCtorPaddingResolve(MacapkCtorPadding p) {
        int leftPadding = p.left, topPadding = p.top;
        int rightPadding = p.right, bottomPadding = p.bottom;
        if (p.padding >= 0) {
            leftPadding = topPadding = rightPadding = bottomPadding = p.padding;
            mUserPaddingLeftInitial = p.padding;
            mUserPaddingRightInitial = p.padding;
        } else {
            if (p.paddingHorizontal >= 0) {
                leftPadding = rightPadding = p.paddingHorizontal;
                mUserPaddingLeftInitial = p.paddingHorizontal;
                mUserPaddingRightInitial = p.paddingHorizontal;
            }
            if (p.paddingVertical >= 0) {
                topPadding = bottomPadding = p.paddingVertical;
            }
        }
        if (isRtlCompatibilityMode()) {
            if (!p.leftDefined && p.startDefined) leftPadding = p.start;
            mUserPaddingLeftInitial = (leftPadding >= 0) ? leftPadding : mUserPaddingLeftInitial;
            if (!p.rightDefined && p.endDefined) rightPadding = p.end;
            mUserPaddingRightInitial = (rightPadding >= 0) ? rightPadding : mUserPaddingRightInitial;
        } else {
            final boolean hasRelativePadding = p.startDefined || p.endDefined;
            if (p.leftDefined && !hasRelativePadding) mUserPaddingLeftInitial = leftPadding;
            if (p.rightDefined && !hasRelativePadding) mUserPaddingRightInitial = rightPadding;
        }
        internalSetPadding(mUserPaddingLeftInitial,
                topPadding >= 0 ? topPadding : paddingTop,
                mUserPaddingRightInitial,
                bottomPadding >= 0 ? bottomPadding : paddingBottom);
        // A fresh AOSP view is unresolved (PFLAG2_PADDING_RESOLVED clear), so
        // the first getPaddingStart() / measure() resolves start/end.
        mPaddingResolved = false;
        macapkCtorPaddingMask = p.mask;
        macapkCtorPadding = p;
    }

    /** AOSP View.onFinishInflate(): the LayoutInflater calls this on each
     *  inflated view once it AND all its children are inflated. Custom
     *  ViewGroups grab their children here (mField = findViewById(R.id.x)),
     *  so the inflater MUST call it -- without it Calculator's HistoryLayout
     *  left its RecyclerView field null and NPE'd in onStart. Subclasses
     *  override; base is a no-op. */
    public void onFinishInflate() {}

    /** AOSP View.onVisibilityChanged(View changedView, int visibility): called
     *  when the visibility of the view or an ancestor changes. Custom views
     *  override it (often calling super.onVisibilityChanged(...)) to start/stop
     *  work when shown/hidden -- duolingo's SplashScreenView chain does, so the
     *  missing super impl threw NoSuchMethodError and aborted the splash setup.
     *  Base is a no-op (matches AOSP). */
    protected void onVisibilityChanged(View changedView, int visibility) {}

    /** AOSP View.dispatchVisibilityChanged(View, int): "Dispatch a view
     *  visibility change down the view hierarchy. ViewGroups should override
     *  to route to their children." Per frameworks/base/core/java/android/
     *  view/View.java (aosp-mirror android13-release, ~line 15335) the base
     *  is exactly this one-liner; ViewGroup overrides to call super (fires
     *  onVisibilityChanged on itself) then recurse into every child with the
     *  SAME changedView/visibility, so a deeply nested descendant learns an
     *  ANCESTOR went INVISIBLE/GONE even though its own visibility flag never
     *  changed. Before this method existed, onVisibilityChanged (above) was
     *  declared -- so a subclass's `super.onVisibilityChanged(...)` call
     *  linked -- but nothing in the framework ever CALLED it: setVisibility()
     *  only requested a re-layout/redraw. Any widget that starts/stops
     *  animations, video, or polling from this hook (AOSP's own ImageView,
     *  and most video-player views) silently never got the stop signal. See
     *  setVisibility() below for the call site. */
    protected void dispatchVisibilityChanged(View changedView, int visibility) {
        onVisibilityChanged(changedView, visibility);
    }

    public final Context getContext() { return context; }
    public android.content.res.Resources getResources() {
        return context != null ? context.getResources() : null;
    }
    /** AOSP getHandler():
     *      final AttachInfo attachInfo = mAttachInfo;
     *      if (attachInfo != null) return attachInfo.mHandler;
     *      return null;
     *  Two bugs in the old one-liner. (1) It answered non-null while DETACHED,
     *  which is the opposite of the documented contract (fwprobe
     *  VW.det.handler: reference reports getHandler()==null). (2) It handed out
     *  a FRESH Handler on every call, so `v.getHandler().removeCallbacks(r)`
     *  could never cancel anything posted through `v.getHandler().post(r)` --
     *  a silent leak of pending work, not just a wrong answer.
     *
     *  Same macapkAttached stand-in and the same MACAPK_ISSHOWN_DETACHED=0
     *  bisect gate as getWindowVisibility()/isShown(), because it is the same
     *  root cause: our attach tracking is behind AOSP's for overlay roots, and
     *  an app that calls getHandler() on a view we have not marked attached
     *  would now see null where it used to see a Handler. */
    private android.os.Handler mViewHandler;
    public android.os.Handler getHandler() {
        if (sIsShownDetachedAosp && !macapkAttached) return null;
        if (mViewHandler == null) mViewHandler = new android.os.Handler();
        return mViewHandler;
    }
    // android.view.Display getter (API 17+). Flappy Bird's
    // AndEngine setup queries display metrics during onCreate;
    // absent, NoSuchMethodError aborts the activity. Return null
    // -- callers null-check and fall back to context.getResources()
    // .getDisplayMetrics(). This is the AOSP behaviour for a View
    // that's not yet attached to a window.
    public android.view.Display getDisplay() { return null; }

    public int  getId()             { return id; }
    public void setId(int id)       { this.id = id; }
    public Object getTag()          { return tag; }
    public void   setTag(Object t)  { this.tag = t; }
    // v1.0.98: keyed tags MUST actually store + return. Used by
    // ViewTreeLifecycleOwner / ViewTreeSavedStateRegistryOwner /
    // ViewTreeViewModelStoreOwner to attach implicit owners up the
    // view hierarchy. Without storage, androidx.lifecycle.get()
    // throws IllegalStateException("not found") and the entire
    // Compose / lifecycle-aware widget chain breaks.
    private java.util.HashMap<Integer, Object> keyedTags;
    /** v1.0.409: snapshot of all keyed-tag VALUES set on this view (the storage
     *  behind setTag(int,Object)). ActivityHost walks the view tree reading
     *  these to find androidx ViewTreeLifecycleOwner entries -- Compose and
     *  Conductor store the LifecycleOwner as a keyed tag on the view -- so it can
     *  drive that owner's LifecycleRegistry to RESUMED (the recomposer binds to
     *  whatever ViewTreeLifecycleOwner the ComposeView resolves, which under
     *  Conductor is the controller's OwnViewTreeLifecycleAndRegistry, not the
     *  activity). Empty when no keyed tags are set. */
    public java.util.List<Object> macapkKeyedTagValues() {
        if (keyedTags == null || keyedTags.isEmpty()) return java.util.Collections.emptyList();
        return new java.util.ArrayList<>(keyedTags.values());
    }
    public Object getTag(int key) {
        Object v = keyedTags != null ? keyedTags.get(key) : null;
        // v1.0.412 DIAGNOSTIC: AOSP doSetContent (Wrapper.android.kt) reads
        // composeView.getTag(R.id.wrapped_composition) and REUSES the existing
        // WrappedComposition (whose slot table is already populated -> the next
        // composeContent runs with inserting==false -> createNode is skipped) if
        // it is non-null. Log when a WrappedComposition tag is RETURNED (reuse
        // path taken) so we can confirm whether the welcome AndroidComposeView is
        // composing against a reused/populated composition. Gated off by default.
        if (v != null && com.macapk.loader.ComposeViewFallback.MACAPK_TRACE_COMPOSE_INSERT
                && "androidx.compose.ui.platform.a0".equals(v.getClass().getName())) {
            com.macapk.loader.NativeLog.i("Mac.apk.WrapComp",
                "getTag(WrappedComposition) -> REUSE on " + getClass().getName()
                + "@" + Integer.toHexString(System.identityHashCode(this)) + " key=" + key);
        }
        if (v != null) return v;
        // v1.0.98: parent-chain walk is done by androidx's
        // findViewTreeXxx via getParent(); they pass through Views
        // and stop at ViewParent that isn't a View. But for newly-
        // created Views still detached (RecyclerView ViewHolder's
        // itemView during onCreateViewHolder, before addView), the
        // walk fails. Fall back to a Context-keyed registry --
        // when ActivityHost calls ViewTreeLifecycleOwner.set on
        // the decor, we also store the same tag under the Activity
        // context so detached views in the same activity can find
        // the owner via getContext().
        // v1.0.437: the context fallback may return ONLY an inherited view-tree
        // OWNER (Lifecycle / ViewModelStore / SavedStateRegistry owner) -- those
        // legitimately inherit down the tree, so a detached descendant resolving
        // an ancestor's owner via context is correct. A PER-VIEW tag absent on
        // THIS view must stay absent (return null) so androidx creates a fresh
        // per-view object rather than reusing another view's. Without this gate,
        // androidx doSetContent's getTag(R.id.wrapped_composition) returned the
        // FIRST AndroidComposeView's WrappedComposition for every later one (it
        // was mirrored context-wide), so all N ComposeViews reused ONE
        // composition (UiApplier bound to the first owner's root) -> only the
        // last setContent's LayoutNodes survived, every earlier ComposeView
        // rendered blank. Reddit: 2 RedditComposeViews share one composition ->
        // welcome blank. Reproduced in debug/composetest crosscomp (3 Compose
        // Views -> only the last rendered).
        // v1.0.437: walk the ContextWrapper chain (getBaseContext) so a view
        // whose context is a ContextThemeWrapper (Conductor controllers, dialogs,
        // theme overlays) still resolves an owner mirrored under a BASE context
        // (e.g. the activity). Without this, Reddit's welcome RedditComposeView --
        // attached while its Conductor subtree root is not yet parented to the
        // decor, so androidx's parent-walk can't reach the owner -- found no owner
        // (its ContextThemeWrapper had no ctx entry) and threw
        // "ViewTreeLifecycleOwner not found" in onAttachedToWindow, so its
        // composition never got created.
        for (Context ctx = context; ctx != null;
                ctx = (ctx instanceof android.content.ContextWrapper)
                        ? ((android.content.ContextWrapper) ctx).getBaseContext() : null) {
            java.util.HashMap<Integer, Object> ctxTags = sContextKeyedTags.get(ctx);
            if (ctxTags != null) {
                Object cv = ctxTags.get(key);
                if (isViewTreeOwnerTagValue(cv)) return cv;
            }
        }
        // v1.0.439: LAST-RESORT view-tree-owner fallback = the current Activity.
        // Real Android's ComponentActivity.initializeViewTreeOwners sets the
        // activity ITSELF as the decor's ViewTreeLifecycle/ViewModelStore/
        // SavedStateRegistry owner, so any descendant resolves the activity as the
        // root owner (until a closer owner overrides it). Mac.apk cannot replicate
        // that install reliably for R8-renamed apps (name-based ViewTreeXxxOwner.set
        // is ClassNotFound), and Conductor sets its OwnViewTreeLifecycleAndRegistry
        // on the controller view only AFTER the welcome ComposeView's first
        // measure/attach -- a race that left the welcome RedditComposeView unable to
        // resolve ANY owner -> "ViewTreeLifecycleOwner not found" -> composition
        // never created (blank login). When the requested key is one androidx has
        // used for a real owner ELSEWHERE in this process (so we know it's an
        // owner-key, learned rename-proof via the structural isViewTreeOwnerTagValue
        // gate in setTag), return the Activity, which IS a Lifecycle/ViewModelStore/
        // SavedStateRegistry owner and is driven to RESUMED. This is the AOSP root
        // owner. WrappedComposition's key is never an owner-key, so the
        // multi-ComposeView fix is preserved (no per-view tag leaks here).
        if (isViewTreeOwnerKey(key)) {
            Object owner = activityOwnerFor();
            boolean ok = owner != null && macapkActivitySatisfiesOwnerKey(owner, key);
            if (OWNER_TRACE && !sWarnedOwnerFallback) {
                sWarnedOwnerFallback = true;
                com.macapk.loader.NativeLog.i("View", "owner-fallback key=" + key
                    + " -> " + (owner == null ? "null" : owner.getClass().getName()));
            }
            if (ok) return owner;
        }
        return null;
    }
    /** Replicate the AOSP INVARIANT, not just the AOSP effect.
     *
     *  ComponentActivity.initializeViewTreeOwners() sets the activity as the owner
     *  for a key only because ComponentActivity IMPLEMENTS that key's interface.
     *  Our fallback above skips the setter entirely and answers getTag(ownerKey)
     *  with the activity directly, so without this check it answers for families
     *  the activity is not an owner of. androidx does not defend against that: the
     *  consumer casts whatever getTag returned straight to the interface (Aurora's
     *  minified LocalNavigationEventDispatcherOwner getter is literally
     *  `(eb5) composer.consume(local)`), so a wrong answer becomes a
     *  ClassCastException where returning null would have produced androidx's own
     *  clean, catchable "No X was provided via LocalXOwner" IllegalStateException.
     *  Turning a clean error into a CCE for some future app is the regression this
     *  closes: it is the same silently-wrong-instead-of-loudly-absent shape that
     *  hid a missing --patch-module dir here for ~100 versions.
     *
     *  ⚠ WHAT THIS CANNOT DECIDE, stated plainly rather than implied. On an
     *  R8-minified APK every androidx interface NAME is gone, so column [1] does
     *  not resolve and there is nothing to test the activity against. We then
     *  preserve the existing behaviour (answer with the activity) because that is
     *  overwhelmingly right -- a minified app that bundles an owner family got it
     *  by extending ComponentActivity, which implements all six. Refusing instead
     *  would regress every R8'd Compose app, Aurora included (all six of its
     *  interfaces are ClassNotFound). So this hardens the un-minified case
     *  COMPLETELY and the minified case NOT AT ALL, and the residual is: a
     *  minified app that hosts Compose in something that is not a
     *  ComponentActivity still gets a CCE. Closing that needs a rename-proof way
     *  to identify an interface, which we do not have -- the owner interfaces are
     *  single-method and structurally indistinguishable from each other.
     *
     *  Narrowing-only by construction: this can turn a returned activity into
     *  null, never the reverse, and only when isInstance PROVES the mismatch. */
    private boolean macapkActivitySatisfiesOwnerKey(Object owner, int key) {
        String iface = macapkOwnerIfaceForKey(key);
        // Key learned structurally via setTag rather than from the table (so we
        // have no interface name for it): unchanged behaviour.
        if (iface == null) return true;
        Class<?> ic;
        try {
            ic = Class.forName(iface, false, owner.getClass().getClassLoader());
        } catch (Throwable renamedOrAbsent) {
            return true; // cannot decide; see the R8 note above
        }
        if (ic.isInstance(owner)) return true;
        if (sOwnerRefusedKeys.add(key)) {
            com.macapk.loader.NativeLog.i("View", "owner-fallback REFUSED key=" + key
                + " (" + iface + "): " + owner.getClass().getName()
                + " does not implement it -- returning null so androidx raises its"
                + " own error instead of a ClassCastException");
        }
        return false;
    }
    /** The owner INTERFACE name for a tag key, via the key's rename-proof resource
     *  entry name and MACAPK_VIEW_TREE_OWNERS. null = not a table row. Cached
     *  because getTag(ownerKey) runs per frame; "" is the negative sentinel. */
    private String macapkOwnerIfaceForKey(int key) {
        String c = sOwnerIfaceByKey.get(key);
        if (c == null) {
            c = "";
            try {
                if (context != null) {
                    android.content.res.Resources res = context.getResources();
                    if (res != null) {
                        String nm = res.getResourceEntryName(key);
                        if (nm != null) {
                            for (String[] row : MACAPK_VIEW_TREE_OWNERS) {
                                if (row[0].equals(nm)) { c = row[1]; break; }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            sOwnerIfaceByKey.put(key, c);
        }
        return c.isEmpty() ? null : c;
    }
    private static final java.util.Map<Integer,String> sOwnerIfaceByKey =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Integer> sOwnerRefusedKeys =
        java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    private static final boolean OWNER_TRACE = System.getenv("MACAPK_OWNER_TRACE") != null;
    private static boolean sWarnedOwnerFallback = false;
    /** THE androidx view-tree owner families. This table is the SINGLE place any
     *  of them is named; everything else in the codebase derives from it. One row
     *  per family, columns:
     *
     *    [0] resource entry NAME. Rename-proof: R8 renames classes but not
     *        resource entry names, so this is the only column usable on a
     *        minified APK, and it is what View.getTag / ActivityHost key off.
     *    [1] owner INTERFACE. Absent (ClassNotFound) on a minified APK.
     *    [2] the setter's Kotlin file-class. Each carries @file:JvmName(<this
     *        name>) with @JvmName("set") on the function, which is what makes a
     *        uniform getMethod("set", View, <col 1>) legal for every row.
     *
     *  The rows are exactly what ComponentActivity.initializeViewTreeOwners()
     *  installs on the decor view (androidx-main
     *  activity/activity/src/main/java/androidx/activity/ComponentActivity.kt):
     *
     *      window.decorView.setViewTreeLifecycleOwner(this)
     *      window.decorView.setViewTreeViewModelStoreOwner(this)
     *      window.decorView.setViewTreeSavedStateRegistryOwner(this)
     *      window.decorView.setViewTreeOnBackPressedDispatcherOwner(this)
     *      window.decorView.setViewTreeFullyDrawnReporterOwner(this)
     *      window.decorView.setViewTreeNavigationEventDispatcherOwner(this)
     *
     *  WHY IT IS ONE TABLE (v1.0.959). The same six families used to be spelled
     *  out in FOUR places: this name set, ActivityHost.VIEW_TREE_OWNER_RES_NAMES,
     *  and ActivityHost's parallel setterClasses[]/ownerIfaces[] arrays (which
     *  were not even in the same ORDER as each other). Keeping four lists in step
     *  by comment is what failed: androidx.navigationevent shipped a SIXTH owner
     *  in 2025 and every list stayed at five, so Aurora Store's composition
     *  consumed LocalNavigationEventDispatcherOwner, got null, and Kotlin's
     *  checkNotNull threw "No NavigationEventDispatcher was provided via
     *  LocalNavigationEventDispatcherOwner" INSIDE composition. setContent never
     *  returned, AbstractComposeView.composition stayed null, and onMeasure re-ran
     *  ensureCompositionCreated on the next frame forever (463 identical throws in
     *  one 180 s run, screen blank throughout). Adding a seventh family is now one
     *  row here, and there is nothing left to keep in step.
     *
     *  NOT an owner and deliberately absent: view_tree_disjoint_parent. That tag
     *  holds a ViewParent used by getParentOrViewTreeDisjointParent() to continue
     *  the upward walk, so answering it with the Activity would hand androidx a
     *  ClassCastException instead of a parent.
     *
     *  Read-only by convention (Java has no immutable array literal). */
    public static final String[][] MACAPK_VIEW_TREE_OWNERS = {
        {"view_tree_lifecycle_owner",
         "androidx.lifecycle.LifecycleOwner",
         "androidx.lifecycle.ViewTreeLifecycleOwner"},
        {"view_tree_view_model_store_owner",
         "androidx.lifecycle.ViewModelStoreOwner",
         "androidx.lifecycle.ViewTreeViewModelStoreOwner"},
        {"view_tree_saved_state_registry_owner",
         "androidx.savedstate.SavedStateRegistryOwner",
         "androidx.savedstate.ViewTreeSavedStateRegistryOwner"},
        {"view_tree_on_back_pressed_dispatcher_owner",
         "androidx.activity.OnBackPressedDispatcherOwner",
         "androidx.activity.ViewTreeOnBackPressedDispatcherOwner"},
        {"view_tree_fully_drawn_reporter_owner",
         "androidx.activity.FullyDrawnReporterOwner",
         "androidx.activity.ViewTreeFullyDrawnReporterOwner"},
        {"view_tree_navigation_event_dispatcher_owner",
         "androidx.navigationevent.NavigationEventDispatcherOwner",
         "androidx.navigationevent.ViewTreeNavigationEventDispatcherOwner"},
    };
    private static final java.util.Set<String> VIEW_TREE_OWNER_RES_NAMES = macapkOwnerResNames();
    private static java.util.Set<String> macapkOwnerResNames() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        for (String[] row : MACAPK_VIEW_TREE_OWNERS) s.add(row[0]);
        return s;
    }
    private static final java.util.Map<Integer,Boolean> sOwnerKeyByNameCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** True iff `key` is one of androidx's view-tree-owner tag ids. Forward set
     *  (sObservedOwnerKeys, learned from setTag) OR reverse-resolved from the
     *  QUERIED key's resource entry name (rename-proof; handles the case where the
     *  owner was never set on any view so the key was never observed). */
    private boolean isViewTreeOwnerKey(int key) {
        if (sObservedOwnerKeys.contains(key)) return true;
        if (key == 0) return false;
        Boolean c = sOwnerKeyByNameCache.get(key);
        if (c != null) return c;
        boolean owner = false;
        try {
            if (context != null) {
                android.content.res.Resources res = context.getResources();
                if (res != null) {
                    String nm = res.getResourceEntryName(key);
                    owner = nm != null && VIEW_TREE_OWNER_RES_NAMES.contains(nm);
                }
            }
        } catch (Throwable ignored) {}
        sOwnerKeyByNameCache.put(key, owner);
        if (owner) sObservedOwnerKeys.add(key);
        return owner;
    }
    /** The view-tree root owner = this view's own Activity (walk the ContextWrapper
     *  chain), falling back to the top Activity. A ComponentActivity implements
     *  Lifecycle/ViewModelStore/SavedStateRegistry owner and is driven to RESUMED,
     *  so androidx's `getTag(ownerKey) as? XxxOwner` succeeds -- the AOSP root
     *  owner that ComponentActivity.initializeViewTreeOwners would have installed. */
    private Object activityOwnerFor() {
        for (Context ctx = context; ctx != null;
                ctx = (ctx instanceof android.content.ContextWrapper)
                        ? ((android.content.ContextWrapper) ctx).getBaseContext() : null) {
            if (ctx instanceof android.app.Activity) return ctx;
        }
        try { return com.macapk.loader.ActivityHost.peek(); } catch (Throwable t) { return null; }
    }
    /** v1.0.439: keys under which androidx has set a real view-tree owner anywhere
     *  in this process (the Lifecycle/ViewModelStore/SavedStateRegistry tag ids,
     *  learned rename-proof via setTag's structural owner gate). Used by getTag to
     *  fall back to the Activity as the root owner. */
    private static final java.util.Set<Integer> sObservedOwnerKeys =
        java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    /** v1.0.439: register a known view-tree-owner tag key (resolved rename-proof
     *  from the androidx owner resource NAMES, e.g. "view_tree_lifecycle_owner",
     *  which survive R8). ActivityHost calls this at activity start so getTag's
     *  Activity fallback fires even when androidx's ComponentActivity.
     *  initializeViewTreeOwners never ran (Conductor uses a programmatic container,
     *  no setContentView -> the owner was never set on any view, so it could not be
     *  learned by observation). */
    public static void macapkRegisterOwnerKey(int key) {
        if (key != 0) sObservedOwnerKeys.add(key);
    }
    public void setTag(int key, Object t) {
        // AOSP setTag(int, Object) (View.java:25202-25211) REJECTS a key whose
        // package byte is 0x00 (undefined) or 0x01 (framework):
        //     if ((key >>> 24) < 2) throw new IllegalArgumentException(...)
        // The shift is UNSIGNED, so 0xffffffff (package byte 0xff) is accepted
        // while 0x01010001 is not. We accepted every key, which means an app
        // that stashes state under a framework id here would find it working
        // and then crash on a real device -- the exact inversion this probe
        // exists to catch (fwprobe VW.id.keytag.set.{zero,pkg00,pkg00max,
        // framework,frameworkmax}). The throw is before any store, so a
        // rejected key leaves no tag behind.
        if ((key >>> 24) < 2) {
            throw new IllegalArgumentException("The key must be an application-specific "
                    + "resource id.");
        }
        if (keyedTags == null) keyedTags = new java.util.HashMap<>();
        // v1.0.412 DIAGNOSTIC: log when a FRESH WrappedComposition is tagged
        // (doSetContent's create path) -- paired with the getTag REUSE log, this
        // tells us whether the welcome AndroidComposeView's composition is created
        // fresh (one setTag, getTag null first) or reused (getTag non-null).
        if (t != null && com.macapk.loader.ComposeViewFallback.MACAPK_TRACE_COMPOSE_INSERT
                && "androidx.compose.ui.platform.a0".equals(t.getClass().getName())) {
            com.macapk.loader.NativeLog.i("Mac.apk.WrapComp",
                "setTag(WrappedComposition) -> FRESH on " + getClass().getName()
                + "@" + Integer.toHexString(System.identityHashCode(this)) + " key=" + key);
        }
        keyedTags.put(key, t);
        // Mirror view-tree-owner tags into the Context-scoped registry so
        // detached descendants can find them via getContext().
        // v1.0.437: mirror ONLY actual owner values (Lifecycle/ViewModelStore/
        // SavedStateRegistry owner). Mirroring EVERY key (the old behavior) leaked
        // per-view tags -- most damagingly androidx's R.id.wrapped_composition --
        // context-wide, so the 2nd..Nth AndroidComposeView's getTag returned the
        // FIRST view's WrappedComposition and reused its composition (only the
        // last ComposeView then rendered; all earlier ones blank -- the Reddit
        // welcome bug, reproduced in debug/composetest crosscomp). Owners DO
        // inherit down the tree, so mirroring them is correct; per-view tags must
        // stay per-view.
        if (isViewTreeOwnerTagValue(t)) {
            // v1.0.439: remember this key is an owner-key so getTag can fall back
            // to the Activity for descendants that resolve no owner (the Conductor
            // attach race). Learned rename-proof via the structural gate.
            if (sObservedOwnerKeys.add(key) && OWNER_TRACE) {
                com.macapk.loader.NativeLog.i("View", "owner-key observed key=" + key
                    + " value=" + t.getClass().getName());
            }
            sObservedOwnerKeys.add(key);
            if (context != null) {
                java.util.HashMap<Integer, Object> m = sContextKeyedTags
                    .computeIfAbsent(context, k -> new java.util.HashMap<>());
                // v1.0.1643: AOSP's root owner for an activity-context view is
                // the Activity (ComponentActivity.initializeViewTreeOwners on the
                // decor). A SUBTREE owner -- a Fragment view's
                // FragmentViewLifecycleOwner, a Dialog built on the raw activity
                // context -- must not replace it in this per-context fallback, or
                // every later lookup resolves to (and pins) that narrower owner.
                Object prev = m.get(key);
                android.app.Activity act = macapkActivityOf(context);
                if (!(prev != null && prev == act && t != act)) m.put(key, t);
            }
        }
        // v1.0.410: Conductor sets ViewTreeLifecycleOwner on the controller view
        // via an ancestor-change listener that fires AFTER the view attaches, so
        // the attach-time lifecycle drive misses it. Re-drive when an owner tag
        // is set on an already-attached view, so a Compose WindowRecomposer bound
        // to that owner reaches RESUMED and its recompose+apply loop runs. setTag
        // is infrequent and the drive is debounced, so this is cheap. See
        // ActivityHost.macapkScheduleViewTreeLifecycleDrive.
        if (macapkAttached && t != null) {
            try { com.macapk.loader.ActivityHost.macapkScheduleViewTreeLifecycleDrive(); }
            catch (Throwable ignored) {}
        }
    }
    /** Context-scoped tag registry: mirror of setTag(int, Object)
     *  calls so detached Views in the same activity context can
     *  find ViewTreeXxxOwner tags.
     *
     *  ⚠ v1.0.1643: this said "cleared when the context goes away (we use weak
     *  references via IdentityHashMap)". IdentityHashMap is STRONG: every
     *  activity context that ever got an owner tag -- i.e. every activity --
     *  was held for the life of the process, with its whole view tree
     *  (measured: 4 of 4 Wikipedia PageActivity instances, and their WebViews,
     *  survived a forced GC after open/back). A WeakHashMap alone does not fix
     *  it: the values ARE the activity (it is its own LifecycleOwner), which
     *  keeps the key reachable. So the entry is also removed when the activity
     *  is destroyed (macapkForgetContext, from ActivityHost.onActivityFinished).
     *  Context does not override equals/hashCode, so WeakHashMap keeps the
     *  identity semantics. */
    private static final java.util.Map<Context, java.util.HashMap<Integer, Object>> sContextKeyedTags
        = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static android.app.Activity macapkActivityOf(Context c) {
        for (int i = 0; c != null && i < 16; i++) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            if (!(c instanceof android.content.ContextWrapper)) return null;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Drop every context-keyed tag of `c` and of any wrapper around it (a
     *  ContextThemeWrapper over the activity). Called when the activity is
     *  destroyed. */
    public static void macapkForgetContext(Context c) {
        if (c == null) return;
        synchronized (sContextKeyedTags) {
            java.util.Iterator<Context> it = sContextKeyedTags.keySet().iterator();
            while (it.hasNext()) {
                Context k = it.next();
                for (Context x = k; x != null;
                        x = (x instanceof android.content.ContextWrapper)
                                ? ((android.content.ContextWrapper) x).getBaseContext() : null) {
                    if (x == c) { it.remove(); break; }
                }
            }
        }
    }

    /** v1.0.437/438: true iff a keyed-tag value is a view-tree OWNER -- the only
     *  tag kind that legitimately inherits down the view tree and may be resolved
     *  via the context-scoped fallback. Per-view tags (androidx's WrappedComposition,
     *  inspection slot-table sets, Glide view-target tags, ...) must NOT leak across
     *  sibling views, or androidx reuses the wrong per-view object (the v1.0.437
     *  multi-ComposeView bug). Two-tier match: (1) interface NAME (works for
     *  un-obfuscated apps), then (2) a rename-proof STRUCTURAL probe -- a
     *  LifecycleOwner has getLifecycle():Lifecycle and Lifecycle has
     *  getCurrentState():Lifecycle$State; Lifecycle$State IS loadable even in a
     *  fully R8'd app (ActivityHost.driveViewTreeLifecycles relies on this), so we
     *  detect an owner as "has a no-arg method returning Lifecycle$State (it IS a
     *  Lifecycle/registry) OR a no-arg method whose return type has one (it HAS a
     *  getLifecycle)". v1.0.437's NAME-ONLY check rejected Reddit's R8-renamed
     *  Conductor owner, so the welcome RedditComposeView could not resolve its
     *  ViewTreeLifecycleOwner via the fallback and threw at compose-create
     *  ("ViewTreeLifecycleOwner not found"). WrappedComposition has no public
     *  Lifecycle getter (it only privately observes one) so it stays excluded.
     *  Cached per-class. */
    private static boolean isViewTreeOwnerTagValue(Object v) {
        if (v == null) return false;
        Class<?> vc = v.getClass();
        Boolean cached = sOwnerTagClassCache.get(vc);
        if (cached != null) return cached;
        // (1) name-based fast path (un-obfuscated apps) -- reliable, cacheable.
        java.util.ArrayDeque<Class<?>> q = new java.util.ArrayDeque<>();
        java.util.HashSet<Class<?>> seen = new java.util.HashSet<>();
        for (Class<?> c = vc; c != null; c = c.getSuperclass()) q.add(c);
        while (!q.isEmpty()) {
            Class<?> c = q.poll();
            if (c == null || !seen.add(c)) continue;
            String n = c.getName();
            if (n.equals("androidx.lifecycle.LifecycleOwner")
             || n.equals("androidx.lifecycle.ViewModelStoreOwner")
             || n.equals("androidx.savedstate.SavedStateRegistryOwner")) {
                sOwnerTagClassCache.put(vc, true); return true;
            }
            for (Class<?> i : c.getInterfaces()) q.add(i);
        }
        // (2) rename-proof structural probe -- needs Lifecycle$State. If it is not
        // resolvable YET (early boot / a framework view's bootstrap classloader),
        // return false but DO NOT cache, so a later call (once androidx is loaded)
        // can still detect the owner. Caching a premature false here is what made
        // the activity read as non-owner ("ownerShaped=false").
        Class<?> stateCls = lifecycleStateClass(vc.getClassLoader());
        if (stateCls == null) return false;
        boolean structural = isStructuralLifecycleOwner(vc, stateCls);
        sOwnerTagClassCache.put(vc, structural);
        return structural;
    }
    private static final java.util.Map<Class<?>, Boolean> sOwnerTagClassCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Class<?> sLifecycleStateCls = null;
    /** Resolve androidx.lifecycle.Lifecycle$State rename-proof. Cache SUCCESS only
     *  (never a permanent failure -- the class may not be loaded yet). Try the
     *  value's classloader, then the current activity's (the app classloader). */
    private static Class<?> lifecycleStateClass(ClassLoader cl) {
        Class<?> c = sLifecycleStateCls;
        if (c != null) return c;
        c = tryLoadLifecycleState(cl);
        if (c == null) {
            try {
                Object a = com.macapk.loader.ActivityHost.peek();
                if (a != null) c = tryLoadLifecycleState(a.getClass().getClassLoader());
            } catch (Throwable ignored) {}
        }
        if (c != null) sLifecycleStateCls = c;
        return c;
    }
    private static Class<?> tryLoadLifecycleState(ClassLoader cl) {
        if (cl == null) return null;
        try { return Class.forName("androidx.lifecycle.Lifecycle$State", false, cl); }
        catch (Throwable t) { return null; }
    }
    private static boolean returnsLifecycleState(Class<?> t, Class<?> stateCls) {
        if (t == null || t.isPrimitive() || t == Object.class) return false;
        try {
            for (java.lang.reflect.Method m : t.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == stateCls) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
    private static boolean isStructuralLifecycleOwner(Class<?> vc, Class<?> stateCls) {
        if (stateCls == null) return false;
        try {
            // (a) v itself is a Lifecycle/registry: has getCurrentState():State.
            if (returnsLifecycleState(vc, stateCls)) return true;
            // (b) v HAS a getLifecycle(): some no-arg method returns a type that
            // itself has a no-arg method returning Lifecycle$State.
            for (java.lang.reflect.Method m : vc.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> r = m.getReturnType();
                if (r == vc) continue; // avoid trivial self-recursion
                if (returnsLifecycleState(r, stateCls)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // Per AOSP View.setMinimumHeight/Width (int). Stored but not
    // used in layout because Mac.apk's layout pass is a stub --
    // these still need to be callable or apps that programmatically
    // size their UI hit NoSuchMethodError (Frozen Bubble's button
    // setup at boot is an example).
    private int minimumWidth, minimumHeight;
    public void setMinimumHeight(int h) { this.minimumHeight = h; }
    public int  getMinimumHeight()      { return minimumHeight; }
    public void setMinimumWidth(int w)  { this.minimumWidth = w; }
    public int  getMinimumWidth()       { return minimumWidth; }

    /** v1.0.124: AOSP `View.getSuggestedMinimumWidth/Height` -- protected
     *  per AOSP, but kept public-visible since APK classes
     *  (CoordinatorLayout.onMeasure, ConstraintLayout.onMeasure,
     *  AppBarLayout.onMeasure, every Material widget that overrides
     *  onMeasure) call it. Returns the larger of the explicit minimum
     *  (set via setMinimum*) and the background drawable's intrinsic
     *  minimum size.
     *
     *  Per cs.android.com/.../view/View.java:
     *    protected int getSuggestedMinimumWidth() {
     *        return (mBackground == null) ? mMinWidth :
     *               max(mMinWidth, mBackground.getMinimumWidth());
     *    }
     *
     *  Adding this was BLOCKED in v1.0.116 because returning 0 (when
     *  mMinWidth was 0 and mBackground null) seemed to collapse card
     *  heights via ConstraintLayout's Math.max clamp. That diagnosis
     *  was incorrect -- the real cause was the missing
     *  getSuggestedMinimum methods entirely, which made
     *  CoordinatorLayout.onMeasure throw NoSuchMethodError mid-RV-
     *  dispatch and abort the whole layout pass. That swallowed
     *  exception aborted dispatchLayoutStep2 so LM.onLayoutChildren's
     *  per-card attach + measure work was lost.
     */
    protected int getSuggestedMinimumWidth() {
        return (backgroundDrawable == null) ? minimumWidth
            : Math.max(minimumWidth, backgroundDrawable.getMinimumWidth());
    }
    protected int getSuggestedMinimumHeight() {
        return (backgroundDrawable == null) ? minimumHeight
            : Math.max(minimumHeight, backgroundDrawable.getMinimumHeight());
    }

    // findViewById is on View (since API 1) -- ViewGroup subclasses
    // recursively walk children. The base View just self-matches: if
    // this view's id is the requested id, return this; otherwise null.
    // Frozen Bubble's HomeScreen.selectInitialButton does
    // `myLayout.findViewById(id).requestFocus()` after `myLayout.addView`
    // with `button.setId(id)`; with a stub-null return, it NPEs.
    // T cast in declaration matches AOSP's <T extends View> signature.
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int searchId) {
        if (searchId != NO_ID && this.id == searchId) return (T) this;
        return null;
    }
    @SuppressWarnings("unchecked")
    public <T extends View> T requireViewById(int searchId) {
        T v = findViewById(searchId);
        if (v == null) throw new IllegalArgumentException(
            "ID does not reference a View inside this View");
        return v;
    }

    // Animation hooks. AOSP View has animate() returning a fluent
    // ViewPropertyAnimator; we return a no-op animator so apps that
    // chain .x().y().alpha().start() don't NPE on the first method.
    public android.view.ViewPropertyAnimator animate() {
        // Tier 3 M7: pass `this` so the (now real) ViewPropertyAnimator can
        // capture + drive this view's properties on the vsync Choreographer.
        return new android.view.ViewPropertyAnimator(this);
    }
    /** AOSP View.clearAnimation():
     *      if (mCurrentAnimation != null) mCurrentAnimation.detach();
     *      mCurrentAnimation = null;
     *  detach() is the load-bearing half: it fires onAnimationEnd for an
     *  animation that was started and is being torn down before it finished, so
     *  an app waiting on that callback is released rather than hung. */
    public void clearAnimation() {
        android.view.animation.Animation a = mCurrentAnimation;
        mCurrentAnimation = null;
        if (a != null) {
            try { a.detach(); } catch (Throwable ignored) {}
        }
    }

    // Generic motion listener registration (gamepad/wheel/joystick). AOSP
    // View.setOnGenericMotionListener stores into ListenerInfo and
    // dispatchGenericMotionEvent gives it first crack before onGenericMotionEvent
    // (frameworks/base View.java:8368 + :16741-16817). This used to DISCARD the
    // listener, so every joystick/scroll-wheel callback a game view registered was
    // dead on arrival regardless of what the native input bridge produced.
    private OnGenericMotionListener onGenericMotionListener;
    public void setOnGenericMotionListener(OnGenericMotionListener listener) {
        this.onGenericMotionListener = listener;
    }

    // Common newer-API View methods. selected/activated back the M23
    // drawable-state system; setters refresh state (flag-gated inside
    // refreshDrawableState) and invalidate so a state-dependent drawable
    // repaints. AOSP setSelected also propagates to children, but our
    // dispatchSetSelected is unused by the canaries; keep it local.
    // selected/activated/hovered are read by the M23 drawable-state system
    // ONLY when MACAPK_REAL_LOOPER is on. Flag-OFF the setters keep their
    // historical no-op behavior (selected/activated/hovered stay false, so
    // the accessors return false exactly as the old stubs did) -> the
    // shipped build is byte-for-byte unchanged.
    public boolean isSelected()  { return selected; }
    public void setSelected(boolean s) {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return;
        if (selected != s) {
            selected = s; refreshDrawableState(); invalidate();
            dispatchSetSelected(s);
        }
    }
    public boolean isActivated() { return activated; }
    public void setActivated(boolean a) {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return;
        if (activated != a) {
            activated = a; refreshDrawableState(); invalidate();
            dispatchSetActivated(a);
        }
    }
    /** AOSP dispatchSetSelected / dispatchSetActivated / dispatchSetPressed are
     *  empty protected hooks on View that setSelected/setActivated/setPressed
     *  call unconditionally; ViewGroup overrides each to push the state down to
     *  its children. The CALLS were missing here, so a container going
     *  selected/activated/pressed never told its children -- which is how a
     *  selected list row fails to tint the labels inside it, since a child's
     *  own drawable state is what its background selector reads
     *  (fwprobe VW.ds.dispatch.*). Declaring the hooks is this file's half;
     *  the overrides that walk mChildren belong to ViewGroup. */
    protected void dispatchSetSelected(boolean selected) {}
    protected void dispatchSetActivated(boolean activated) {}
    protected void dispatchSetPressed(boolean pressed) {}
    public boolean isHovered() { return hovered; }
    public void setHovered(boolean h) {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return;
        // AOSP View.setHovered (android14 :17898): refreshDrawableState, then
        // the onHoverChanged hook.
        if (hovered != h) { hovered = h; refreshDrawableState(); onHoverChanged(h); }
    }
    /** AOSP setDrawingCacheEnabled/isDrawingCacheEnabled (View.java:21112) --
     *  a plain mViewFlags bit. Deprecated since API 28 and we still build no
     *  cache bitmap, but the FLAG is observable and a widget that sets it and
     *  re-reads it must not be told false (fwprobe
     *  VW.flag.drawingCacheEnabled.true). getDrawingCache() still answers null,
     *  exactly as it did before, so nothing new is promised. */
    private boolean mDrawingCacheEnabled;
    public void setDrawingCacheEnabled(boolean enabled) { mDrawingCacheEnabled = enabled; }
    public boolean isDrawingCacheEnabled() { return mDrawingCacheEnabled; }
    /** AOSP: `return mAttachInfo != null && mAttachInfo.mHardwareAccelerated;`
     *  (frameworks/base View.java:24522). Mac.apk's window IS hardware
     *  accelerated -- every draw lands on a Metal/ANGLE-backed surface and
     *  android.graphics.Canvas.isHardwareAccelerated() already reports true --
     *  so the only honest answer once the view is attached is true.
     *  macapkAttached is our stand-in for mAttachInfo != null, which keeps
     *  the AOSP "false while detached" half intact; the previous hardcoded
     *  false made View and Canvas report opposite answers for the same fact,
     *  a state that cannot occur on a real device. */
    public boolean isHardwareAccelerated() { return macapkAttached; }
    // Tier 3 M7: real 2D transform state (was no-op stubs). Defaults are IDENTITY
    // (translation 0, scale 1, rotation 0), so a non-animated view is visually
    // unchanged; only when an animator (ViewPropertyAnimator / ObjectAnimator /
    // ValueAnimator) drives these does the view move/scale/rotate. Each setter
    // invalidate()s on change so the animation schedules a traversal (M6) and the
    // new frame renders; the transform is APPLIED in ViewGroup.dispatchDraw
    // (gated on MACAPK_REAL_LOOPER). pivot defaults to the view's center (NaN).
    private float transX = 0f, transY = 0f;
    private float scaleX = 1f, scaleY = 1f;
    private float rotation = 0f;
    private float pivotX = Float.NaN, pivotY = Float.NaN;
    public float getRotation()   { return rotation; }
    public void  setRotation(float r) { if (rotation != r) { rotation = r; invalidate(); } }
    public float getScaleX()     { return scaleX; }
    public float getScaleY()     { return scaleY; }
    public void  setScaleX(float s) { if (scaleX != s) { scaleX = sanitizeFloatPropertyValue(s, "scaleX"); invalidate(); } }
    public void  setScaleY(float s) { if (scaleY != s) { scaleY = sanitizeFloatPropertyValue(s, "scaleY"); invalidate(); } }
    /** AOSP sanitizeFloatPropertyValue (View.java:17452-17484) and the latch
     *  that arms it (:5370, inside View(Context)):
     *      sThrowOnInvalidFloatProperties = targetSdkVersion >= Build.VERSION_CODES.P;
     *  setScaleX:16822, setScaleY:16860, setElevation:17576 and
     *  setTranslationZ:17672 each run their argument through it BEFORE touching
     *  the RenderNode, so on any app targeting P+ a NaN or an infinity is an
     *  IllegalArgumentException and the property keeps its old value. We stored
     *  the poison instead, which is worse than a crash: a NaN scale silently
     *  propagates into every matrix the view participates in and the whole
     *  subtree stops drawing with no error anywhere (fwprobe
     *  VW.tx.sanitize.*: reference THROW + property unchanged, ours noThrow +
     *  property NaN/Infinity).
     *
     *  The latch is static and set by the FIRST View constructed in the
     *  process, exactly as on AOSP -- so it is also a direct read of whether
     *  Context.getApplicationInfo() gave us a real targetSdkVersion. A host
     *  that answers 0 there silently CLAMPS instead of throwing, which is the
     *  pre-P behaviour and still better than storing the poison. */
    /** AOSP View.sBrokenInsetsDispatch. android14-release View.java:5550 is
     *  simply `sBrokenInsetsDispatch = targetSdkVersion < Build.VERSION_CODES.R`
     *  (the android11-release form carried an extra transitional
     *  `ViewRootImpl.sNewInsetsMode` term that is gone by 14).
     *
     *  It selects between two genuinely different ViewGroup dispatch
     *  behaviours, see ViewGroup.dispatchApplyWindowInsets:
     *    targetSdk <  30  BROKEN: thread the possibly-MODIFIED bag from each
     *                     child into the next sibling, and stop at the first
     *                     child that consumes. One child could therefore starve
     *                     its siblings of insets.
     *    targetSdk >= 30  NEW: every child gets the SAME original bag and the
     *                     original bag is returned. Siblings are independent.
     *
     *  This is a targetSdk COMPAT GATE, not a device-level check: AOSP keys it
     *  on the APP's targetSdkVersion, so it is correct to land while we still
     *  present a flat 29, and 51 of the 62 corpus apps are entitled to the NEW
     *  path today. It is also invisible until the dispatched bag carries real
     *  insets, which is exactly why it lands BEFORE that and not after.
     *
     *  Resolved lazily and latched, the way LinearLayout.macapkInitCompat does,
     *  because View has no compat-init hook and no Context at class-load. A
     *  Context that cannot answer does NOT latch, so a later call can still get
     *  the real value rather than freezing the fallback forever. */
    private static volatile int sCompatTargetSdk;   // 0 == not resolved yet
    static boolean macapkBrokenInsetsDispatch(android.content.Context ctx) {
        // Kill switch: force the legacy behaviour for EVERY app in one run, so
        // a regression can be A/B'd without a bisect.
        if ("1".equals(ENV_MACAPK_INSETS_DISPATCH_LEGACY)) return true;
        int t = sCompatTargetSdk;
        if (t == 0) {
            try {
                android.content.pm.ApplicationInfo ai =
                        (ctx != null) ? ctx.getApplicationInfo() : null;
                if (ai != null && ai.targetSdkVersion > 0) {
                    t = ai.targetSdkVersion;
                    sCompatTargetSdk = t;   // latch only a REAL answer
                }
            } catch (Throwable ignored) { }
            // Unresolved: assume the legacy path, which is what every app got
            // before this existed. Never guess forward.
            if (t == 0) return true;
        }
        return t < 30;   // Build.VERSION_CODES.R
    }

    private static boolean sThrowOnInvalidFloatProperties;
    private static boolean sFloatPropertyLatchDone;
    static void macapkLatchFloatPropertyThrow(Context c) {
        if (sFloatPropertyLatchDone) return;
        try {
            // Deviation from AOSP, and deliberate: AOSP latches unconditionally
            // because a real process always has an ApplicationInfo by the time
            // the first View exists. Here the first View can be built against a
            // bare or half-built Context, and latching FALSE off that would arm
            // the pre-P clamping behaviour for the whole process from a
            // transient. So the latch only closes once a targetSdkVersion is
            // actually readable; until then every call re-tries and the
            // conservative clamp applies.
            android.content.pm.ApplicationInfo ai =
                (c != null) ? c.getApplicationInfo() : null;
            if (ai != null && android.app.Application.macapkTargetSdkKnown(ai.targetSdkVersion)) {
                sThrowOnInvalidFloatProperties =
                    ai.targetSdkVersion >= android.os.Build.VERSION_CODES.P;
                sFloatPropertyLatchDone = true;
            }
        } catch (Throwable ignored) {}
    }
    private static float sanitizeFloatPropertyValue(float value, String propertyName) {
        return sanitizeFloatPropertyValue(value, propertyName, -Float.MAX_VALUE, Float.MAX_VALUE);
    }
    private static float sanitizeFloatPropertyValue(float value, String propertyName,
            float min, float max) {
        // The expected "nothing bad happened" path
        if (value >= min && value <= max) return value;
        if (value < min || value == Float.NEGATIVE_INFINITY) {
            if (sThrowOnInvalidFloatProperties) {
                throw new IllegalArgumentException("Cannot set '" + propertyName + "' to "
                        + value + ", the value must be >= " + min);
            }
            return min;
        }
        if (value > max || value == Float.POSITIVE_INFINITY) {
            if (sThrowOnInvalidFloatProperties) {
                throw new IllegalArgumentException("Cannot set '" + propertyName + "' to "
                        + value + ", the value must be <= " + max);
            }
            return max;
        }
        if (Float.isNaN(value)) {
            if (sThrowOnInvalidFloatProperties) {
                throw new IllegalArgumentException(
                        "Cannot set '" + propertyName + "' to Float.NaN");
            }
            return 0;
        }
        return value;
    }
    public float getTranslationX() { return transX; }
    public float getTranslationY() { return transY; }
    public void  setTranslationX(float t) { if (transX != t) { transX = t; invalidate(); } }
    public void  setTranslationY(float t) { if (transY != t) { transY = t; invalidate(); } }
    // Tier 3 M12: real elevation / translationZ storage (were no-op stubs).
    // getZ() = elevation + translationZ (AOSP View.getZ). A non-zero Z makes
    // ViewGroup.dispatchDraw cast an outline-shaped soft shadow UNDER the view
    // (drawn in the parent's coordinate space so it extends past the view's
    // bounds, scaled by Z, shaped by the view's ViewOutlineProvider so rounded
    // views get rounded shadows). The shadow draw is gated on MACAPK_REAL_LOOPER,
    // so the shipped (flag-off) build paints no elevation shadow and is
    // byte-for-byte unchanged. setElevation invalidate()s on a real change so the
    // shadow re-renders; invalidate() is itself flag-gated.
    private float mElevation = 0f, mTranslationZ = 0f;
    public float getElevation() { return mElevation; }
    public void  setElevation(float e) { if (mElevation != e) { mElevation = sanitizeFloatPropertyValue(e, "elevation"); invalidate(); } }
    public float getPivotX() { return Float.isNaN(pivotX) ? (right - left) / 2f : pivotX; }
    public float getPivotY() { return Float.isNaN(pivotY) ? (bottom - top) / 2f : pivotY; }
    public void  setPivotX(float p) { pivotX = p; invalidate(); }
    public void  setPivotY(float p) { pivotY = p; invalidate(); }
    public float getX()          { return left + transX; }
    public float getY()          { return top + transY; }
    public void  setX(float x)   { setTranslationX(x - left); }
    public void  setY(float y)   { setTranslationY(y - top); }
    /** Tier 3 M7: true iff this view has a non-identity 2D transform (so the
     *  draw path can skip the matrix work for the common identity case). */
    public boolean macapkHasTransform() {
        return transX != 0f || transY != 0f || scaleX != 1f || scaleY != 1f || rotation != 0f;
    }
    /** Cheap pre-guard for the matrix-aware coordinate walks
     *  (transformFromViewToWindowSpace, getHitRect, getBoundsOnScreen). Pure
     *  field compares, where hasIdentityMatrix() rebuilds the whole Matrix and
     *  asks SkMatrix for its type -- and those walks run per level of the tree
     *  on every window-space query. Superset of macapkHasTransform: that one
     *  omits rotationX/rotationY because the canvas path handles only the 2D
     *  transform, but the 3D rotations DO produce a non-identity matrix. */
    boolean macapkHasAnyTransform() {
        return macapkHasTransform() || rotationX != 0f || rotationY != 0f;
    }
    /** Tier 3 M7: apply this view's transform to the canvas (caller has already
     *  translated to the view's left/top origin). Scale/rotate happen about the
     *  pivot (default: view center). */
    public void macapkApplyTransform(android.graphics.Canvas c) {
        if (transX != 0f || transY != 0f) c.translate(transX, transY);
        if (rotation != 0f || scaleX != 1f || scaleY != 1f) {
            float px = getPivotX(), py = getPivotY();
            c.translate(px, py);
            if (rotation != 0f) c.rotate(rotation);
            if (scaleX != 1f || scaleY != 1f) c.scale(scaleX, scaleY);
            c.translate(-px, -py);
        }
    }

    // Scroll / fading-edge configuration. AOSP funnels every one of these
    // through setFlags(x, MASK) on the private mViewFlags word and answers the
    // getter straight out of that word, so the OBSERVABLE contract is a plain
    // round trip. We keep discrete fields rather than reproducing mViewFlags
    // (which is private in AOSP and therefore not part of the contract), but
    // the answers are the same. Before v1.0.923 the setters were no-ops and the
    // getters were hardcoded false, so a widget that configured itself in its
    // constructor and re-read the value later got its own write back as false:
    // fwprobe VW.flag.{horizontal,vertical}{FadingEdge,ScrollBar}Enabled.true,
    // keepScreenOn.true, willNotCacheDrawing.true, drawingCacheEnabled.true,
    // nestedScrollingEnabled.true all read 1 on the reference and 0 here.
    // We still draw no scrollbars and no fading edge; the STATE is now honest.
    private boolean mHorizontalFadingEdgeEnabled;
    private boolean mVerticalFadingEdgeEnabled;
    private boolean mHorizontalScrollBarEnabled;
    private boolean mVerticalScrollBarEnabled;
    public void setHorizontalFadingEdgeEnabled(boolean enabled) { mHorizontalFadingEdgeEnabled = enabled; }
    public void setVerticalFadingEdgeEnabled(boolean enabled)   { mVerticalFadingEdgeEnabled = enabled; }
    public boolean isHorizontalFadingEdgeEnabled() { return mHorizontalFadingEdgeEnabled; }
    public boolean isVerticalFadingEdgeEnabled()   { return mVerticalFadingEdgeEnabled; }
    public void setHorizontalScrollBarEnabled(boolean enabled)  { mHorizontalScrollBarEnabled = enabled; }
    public void setVerticalScrollBarEnabled(boolean enabled)    { mVerticalScrollBarEnabled = enabled; }
    public boolean isHorizontalScrollBarEnabled() { return mHorizontalScrollBarEnabled; }
    public boolean isVerticalScrollBarEnabled()   { return mVerticalScrollBarEnabled; }
    /** AOSP setScrollbarFadingEnabled/isScrollbarFadingEnabled
     *  (View.java:19276-19297). The getter is
     *      `return mScrollCache != null && mScrollCache.fadeScrollBars;`
     *  so a view that never touched the API answers FALSE even though the
     *  cache's own default is true -- the allocation of the cache IS the
     *  state. mScrollbarFadingSet stands in for "mScrollCache != null"; we
     *  have no ScrollabilityCache because we draw no scrollbars. */
    private boolean mScrollbarFadingSet;
    private boolean mScrollbarFadingEnabled;
    public void setScrollbarFadingEnabled(boolean enabled) {
        mScrollbarFadingSet = true;
        mScrollbarFadingEnabled = enabled;
    }
    public boolean isScrollbarFadingEnabled() { return mScrollbarFadingSet && mScrollbarFadingEnabled; }
    /** AOSP getVerticalScrollbarWidth:6625 / getHorizontalScrollbarHeight:6647
     *  both read the ScrollabilityCache's ScrollBarDrawable and return 0 when
     *  either is absent. We never allocate a ScrollBarDrawable, so 0 is the
     *  AOSP answer for every state this framework can reach -- including after
     *  setScrollbarFadingEnabled, which allocates the cache but not the
     *  drawable (fwprobe VW.scr.vbarwidth.fading_off = 0 on the reference). */
    public int getVerticalScrollbarWidth() { return 0; }
    protected int getHorizontalScrollbarHeight() { return 0; }
    public void setFadingEdgeLength(int length) {}
    public int  getHorizontalFadingEdgeLength() { return 0; }
    public int  getVerticalFadingEdgeLength()   { return 0; }
    /** AOSP getTopFadingEdgeStrength:19153, getBottom:19167, getLeft:19182,
     *  getRight:19196 -- verbatim. Each is defined purely in terms of the
     *  compute*Scroll* trio, so a subclass that overrides those (every
     *  scrolling container does) gets the right fade for free. */
    protected float getLeftFadingEdgeStrength() {
        return computeHorizontalScrollOffset() > 0 ? 1.0f : 0.0f;
    }
    protected float getRightFadingEdgeStrength() {
        return computeHorizontalScrollOffset() + computeHorizontalScrollExtent()
                < computeHorizontalScrollRange() ? 1.0f : 0.0f;
    }
    protected float getTopFadingEdgeStrength() {
        return computeVerticalScrollOffset() > 0 ? 1.0f : 0.0f;
    }
    protected float getBottomFadingEdgeStrength() {
        return computeVerticalScrollOffset() + computeVerticalScrollExtent()
                < computeVerticalScrollRange() ? 1.0f : 0.0f;
    }
    private boolean mKeepScreenOn;
    public void setKeepScreenOn(boolean keepOn) { mKeepScreenOn = keepOn; }
    public boolean getKeepScreenOn() { return mKeepScreenOn; }
    /** AOSP setContextClickable/isContextClickable (View.java:12262) and
     *  setFilterTouchesWhenObscured/getFilterTouchesWhenObscured (:12431).
     *  Both were entirely absent, so every call was a NoSuchMethodError rather
     *  than a wrong answer (fwprobe VW.flag.contextClickable.*,
     *  VW.flag.filterTouchesWhenObscured.*). Neither participates in the
     *  drawable state on AOSP, so neither is added to onCreateDrawableState. */
    private boolean mContextClickable;
    public void setContextClickable(boolean contextClickable) { mContextClickable = contextClickable; }
    public boolean isContextClickable() { return mContextClickable; }
    private boolean mFilterTouchesWhenObscured;
    public void setFilterTouchesWhenObscured(boolean enabled) { mFilterTouchesWhenObscured = enabled; }
    public boolean getFilterTouchesWhenObscured() { return mFilterTouchesWhenObscured; }
    /** AOSP View(Context) seeds mViewFlags with SOUND_EFFECTS_ENABLED |
     *  HAPTIC_FEEDBACK_ENABLED | FOCUSABLE_AUTO (View.java:5307), so both of
     *  these default to TRUE and both setters really take. isSoundEffectsEnabled
     *  did not exist at all and setHapticFeedbackEnabled was a no-op against a
     *  hardcoded `true` getter, so setHapticFeedbackEnabled(false) could not be
     *  observed (fwprobe VW.flag.hapticFeedbackEnabled.false: 0 vs 1). */
    private boolean mSoundEffectsEnabled = true;
    public void setSoundEffectsEnabled(boolean enabled) { mSoundEffectsEnabled = enabled; }
    public boolean isSoundEffectsEnabled() { return mSoundEffectsEnabled; }
    private boolean mHapticFeedbackEnabled = true;
    public void setHapticFeedbackEnabled(boolean enabled) { mHapticFeedbackEnabled = enabled; }
    /** AOSP setContentDescription (View.java:10613-10640). Storing it is the
     *  obvious half; the SIDE EFFECT is the load-bearing half -- a non-empty
     *  description promotes importantForAccessibility from AUTO to YES, and
     *  the promotion is NOT undone when the description is later cleared
     *  (fwprobe VW.id.cd.clear_after_set: mode stays 1). An empty ("") or null
     *  description does not promote. */
    private CharSequence mContentDescription;
    public void setContentDescription(CharSequence cd) {
        if (mContentDescription == null) {
            if (cd == null) return;
        } else if (mContentDescription.equals(cd)) {
            return;
        }
        mContentDescription = cd;
        if (cd != null && cd.length() > 0
                && getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }
    public CharSequence getContentDescription() { return mContentDescription; }
    /** AOSP API-28 accessibility pane title (androidx/Compose set it on panes;
     *  Wikipedia hit setAccessibilityPaneTitle as NoSuchMethodError). Stored only;
     *  no a11y tree consumer. */
    private CharSequence mAccessibilityPaneTitle;
    public void setAccessibilityPaneTitle(CharSequence title) { mAccessibilityPaneTitle = title; }
    public CharSequence getAccessibilityPaneTitle() { return mAccessibilityPaneTitle; }
    /** AOSP nextFocus{Up,Down,Left,Right,Forward}Id (View.java:11023 and
     *  neighbours) -- plain int properties defaulting to NO_ID. The setters
     *  discarded their argument and there were no getters at all, so every
     *  read was a NoSuchMethodError (fwprobe VW.id.nextfocus.*). These are what
     *  a D-pad / keyboard-nav app uses to hand-wire its focus graph, and
     *  FocusFinder reads them before it falls back to geometry. */
    private int mNextFocusUpId      = NO_ID;
    private int mNextFocusDownId    = NO_ID;
    private int mNextFocusLeftId    = NO_ID;
    private int mNextFocusRightId   = NO_ID;
    private int mNextFocusForwardId = NO_ID;
    public void setNextFocusUpId(int id)    { mNextFocusUpId = id; }
    public void setNextFocusDownId(int id)  { mNextFocusDownId = id; }
    public void setNextFocusLeftId(int id)  { mNextFocusLeftId = id; }
    public void setNextFocusRightId(int id) { mNextFocusRightId = id; }
    public void setNextFocusForwardId(int id) { mNextFocusForwardId = id; }
    public int getNextFocusUpId()      { return mNextFocusUpId; }
    public int getNextFocusDownId()    { return mNextFocusDownId; }
    public int getNextFocusLeftId()    { return mNextFocusLeftId; }
    public int getNextFocusRightId()   { return mNextFocusRightId; }
    public int getNextFocusForwardId() { return mNextFocusForwardId; }
    /** AOSP setVerticalScrollbarPosition/getVerticalScrollbarPosition
     *  (View.java:6929). SCROLLBAR_POSITION_DEFAULT/LEFT/RIGHT = 0/1/2; the
     *  setter does not validate. Absent entirely before v1.0.923. */
    private int mVerticalScrollbarPosition = SCROLLBAR_POSITION_DEFAULT;
    public void setVerticalScrollbarPosition(int position) { mVerticalScrollbarPosition = position; }
    public int getVerticalScrollbarPosition() { return mVerticalScrollbarPosition; }
    /** AOSP View.clearFocus() -> clearFocusInternal(null, true, true)
     *  (View.java:7944-7960). The tail we were missing is the REFOCUS step:
     *      if (propagate && (!refocus || !rootViewRequestFocus()))
     *          notifyGlobalFocusCleared(this);
     *  i.e. clearing focus asks the ROOT to take focus again, and only reports
     *  a globally-cleared focus if the root refuses. That is why an attached
     *  screen never ends up with nothing focused after a clearFocus(), and --
     *  the case the probe pins -- why clearFocus() on a DETACHED view leaves it
     *  focused: getRootView() answers `this`, so the view re-grants itself
     *  focus (fwprobe VW.det.focus.clear: isFocused=1 hasFocus=1 on the
     *  reference, 0/0 here).
     *
     *  mInClearFocus guards the one shape AOSP gets for free from its flag
     *  bookkeeping: requestFocus on the root can walk back into clearFocus on
     *  this same view, and without the guard that is unbounded recursion. */
    private boolean mInClearFocus;
    public void clearFocus() {
        if (!mIsFocused) return;
        mIsFocused = false;
        ViewParent p = getParent();
        if (p != null) p.clearChildFocus(this);
        onFocusChanged(false, 0, null);
        // AOSP's clearFocusInternal calls refreshDrawableState() right after
        // onFocusChanged (core/java/android/view/View.java:8011), same pair
        // handleFocusGainInternal and unFocus above need it for: without it
        // a stateful background drawable (e.g. the EditText underline bar)
        // never re-selects its unfocused art once focus leaves.
        refreshDrawableState();
        invalidate();
        // AOSP clearFocus: refocus = sAlwaysAssignFocus || !isInTouchMode(), so in
        // touch mode a modern app's cleared focus STAYS cleared. Gated now that
        // ViewGroup.requestFocus descends: refocusing the root unconditionally
        // would hand focus straight back to the first focusableInTouchMode view
        // (often the very EditText the app just cleared to hide the keyboard).
        if (mInClearFocus || !(macapkAlwaysAssignFocus() || !isInTouchMode())) return;
        mInClearFocus = true;
        try { rootViewRequestFocus(); } catch (Throwable ignored) {} finally { mInClearFocus = false; }
    }
    /** AOSP View.sAlwaysAssignFocus = targetSdkVersion < P (View's constructor,
     *  android14 :5546). An unresolved targetSdk reads as modern. */
    private boolean macapkAlwaysAssignFocus() {
        try {
            android.content.Context c = getContext();
            android.content.pm.ApplicationInfo ai = c == null ? null : c.getApplicationInfo();
            int t = ai == null ? 0 : ai.targetSdkVersion;
            return android.app.Application.macapkTargetSdkKnown(t)
                && t < android.os.Build.VERSION_CODES.P;
        } catch (Throwable t) {
            return false;
        }
    }
    /** AOSP rootViewRequestFocus():7968. */
    boolean rootViewRequestFocus() {
        final View root = getRootView();
        return root != null && root.requestFocus();
    }
    /** AOSP walks to the top of the view hierarchy. Returning `this` made
     *  `getRootView().getHeight()` equal to THIS view's own height, which is
     *  how `PopupWindow.getMaxAvailableHeight` came to clamp every dropdown to
     *  the height of its anchor -- a 48dp overflow button yields ~96px, about
     *  one clipped row. AppCompat routes through that method for real
     *  (`ListPopupWindow$Api24Impl.getMaxAvailableHeight`), so this silently
     *  caps the whole platform AND AppCompat popup family.
     *  Wrong on its own terms regardless of the popup work. */
    public View getRootView() {
        View v = this;
        for (int guard = 0; guard < 256; guard++) {   // depth guard: a cyclic
            ViewParent p = v.getParent();             // parent must not hang
            if (!(p instanceof View)) return v;
            v = (View) p;
        }
        return v;
    }

    /** AOSP-public window-handle accessor. Apps and SDKs (notably
     *  Flappy Bird's AdView setup and Google Mobile Ads' attachment
     *  probe) call this to fetch an IBinder representing the
     *  containing window, then pass it to InputMethodManager /
     *  WindowManager. We don't have a real Binder identity to hand
     *  out, but the AOSP contract allows null (it means "not yet
     *  attached"), and well-behaved SDKs null-check. */
    /** ⚠ THE OLD COMMENT ABOVE CONTAINED THE BUG. It reasoned that "the AOSP
     *  contract allows null ... and well-behaved SDKs null-check". Both halves
     *  are true and the conclusion was still wrong: null does not mean "no token
     *  available", it means NOT YET ATTACHED, so a well-behaved SDK that
     *  null-checks correctly concludes the view is detached and STOPS.
     *
     *  AOSP (frameworks/base/core/java/android/view/View.java):
     *      return mAttachInfo != null ? mAttachInfo.mWindowToken : null;
     *  i.e. non-null for the whole time a view IS attached.
     *
     *  Measured victim: androidx's ViewPager.populate(int) opens with
     *      if (getWindowToken() == null) { return; }
     *  (verified in newpipe's own shipped bytecode: `invokevirtual
     *  View.getWindowToken` then `ifnonnull` past an immediate exit). So on
     *  newpipe the tab strip built, the adapter attached, MainFragment reached
     *  RESUMED -- and the ViewPager held ZERO children forever. No page fragment
     *  was ever created, so NO NETWORK REQUEST WAS EVER ISSUED. The "empty feed"
     *  looked like a networking or extraction failure for several sessions and
     *  was neither; it never got that far.
     *
     *  One token for the whole window, which is what AttachInfo means -- every
     *  view in a window shares mWindowToken, and Mac.apk presents exactly one
     *  window. Gated on macapkAttached (our stand-in for mAttachInfo != null, see
     *  :813) so a detached view still answers null, as AOSP does. */
    private static final android.os.IBinder MACAPK_WINDOW_TOKEN = new android.os.Binder();
    public android.os.IBinder getWindowToken() {
        return macapkAttached ? MACAPK_WINDOW_TOKEN : null;
    }
    /** AOSP returns mAttachInfo.mPanelParentWindowToken, which for a normal
     *  (non-panel) window is the same token. */
    public android.os.IBinder getApplicationWindowToken() {
        return macapkAttached ? MACAPK_WINDOW_TOKEN : null;
    }
    /** AOSP-internal accessor. SmashHit reflectively probes
     *  View.getViewRootImpl() (then null-checks the result). We have no
     *  ViewRootImpl object, so we return null -- the same value real Android
     *  returns for a view that isn't attached to a window's root. Return type
     *  is Object since the internal ViewRootImpl type isn't modelled. */
    public Object getViewRootImpl() { return null; }
    /** REAL attach-state tracking (was hardcoded `true`). The
     *  always-true lie made constructor-time code take attached-only
     *  branches with zero children: MotionLayout.loadLayoutDescription
     *  gates its ConstraintSet readFallback + mCurrentState init on
     *  isAttachedToWindow(), so Calculator's scene derived from an
     *  EMPTY child list and every widget solved to zero (the keypad
     *  transposition). Real Android attaches a view when it joins a
     *  window-attached tree: our root attaches from ViewCanvasDriver,
     *  and ViewGroup.addView dispatches recursively when the parent
     *  is already attached. */
    boolean macapkAttached = false;
    public boolean isAttachedToWindow()                { return macapkAttached; }
    private static boolean warnedAttachThrow = false;
    private static boolean warnedSizeChanged = false;
    /** Class names whose onMeasure has already been reported as throwing.
     *
     *  v1.0.836: this was a single static boolean, so only the FIRST throwing view
     *  in the entire process was ever logged and every subsequent one -- of any
     *  class, in any app -- was silent. That is a real diagnostic hole for exactly
     *  the class of bug it was added for: a measure pass that dies inside one
     *  custom ViewGroup while a different view had already burned the one-shot.
     *  Keyed by class name so the anti-spam intent survives (a throwing view
     *  throws every frame) while a SECOND distinct offender is still reported. */
    private static final java.util.Set<String> warnedMeasureThrowClasses =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** Recursive attach dispatch (AOSP dispatchAttachedToWindow: self
     *  first, then children -- ViewGroup overrides to recurse).
     *  Idempotent. App onAttachedToWindow overrides are guarded so a
     *  framework gap inside one subtree doesn't abort the whole
     *  attach walk; first throw is logged loudly. */
    public void macapkDispatchAttachedToWindow() {
        macapkTraceComposeAttach("ATTACH", macapkAttached);
        if (macapkAttached) return;
        macapkAttached = true;
        // hasWindowFocus() flips false -> true at this moment (it gates on
        // macapkAttached since the fwprobe DRAWSTATE fix), so any drawable
        // state cached during construction is stale now. AOSP recomputes via
        // the window-focus dispatch (View.onWindowFocusChanged ends in
        // refreshDrawableState()); our runtime has no NSWindow focus bridge,
        // so the attach transition IS our focus-gain moment. Before that fix
        // the pre- and post-attach arrays were identical and nothing needed
        // this. Guarded: refreshDrawableState forwards into app-overridable
        // drawableStateChanged, which must not abort the attach walk.
        try { refreshDrawableState(); } catch (Throwable ignored) {}
        // v1.0.575: fold any FLOATING ViewTreeObserver this view accumulated
        // while detached into the tree's ROOT observer, now that the parent
        // chain is reachable. Real Android performs this merge at attach
        // (frameworks/base View.dispatchAttachedToWindow -> the mFloatingTreeObserver
        // merge). Load-bearing for AGDK GameActivity: GameActivity registers its
        // OnGlobalLayoutListener on mSurfaceView.getViewTreeObserver() inside
        // onCreate while the InputEnabledSurfaceView is still DETACHED, so the
        // listener lands in the SurfaceView's private floating observer. Without
        // this merge it never reaches the ROOT observer that ViewCanvasDriver
        // dispatches per frame, so GameActivity.onGlobalLayout never fires,
        // onContentRectChangedNative(handle,0,0,w,h) is never sent, and
        // android_main (RenderDragon) never gets the content rect it needs to size
        // its viewport / build the menu scene -> the game parks after its loading
        // frames. getViewTreeObserver() (see below) does the actual merge when the
        // root is reachable; calling it here triggers it. Generic (any late-attached
        // view whose listeners were registered while detached benefits). Guarded so
        // a throwing observer merge cannot abort the attach walk.
        try { getViewTreeObserver(); } catch (Throwable ignored) {}
        try {
            onAttachedToWindow();
        } catch (Throwable t) {
            // v1.0.437: androidx AbstractComposeView.onAttachedToWindow throws
            // "ViewTreeLifecycleOwner not found" when no owner is reachable yet --
            // its composition is then NEVER created (blank). Conductor (Reddit's
            // welcome) attaches the ComposeView inside a DETACHED container and
            // sets the owner only once that container joins the decor (via its
            // ancestor-change listener), which races our top-down attach dispatch;
            // and because attach is idempotent, the ComposeView never retries on
            // its own. Register it so the next view-tree lifecycle drive (which
            // runs after owners are in place) re-runs onAttachedToWindow ->
            // ensureCompositionCreated (idempotent) and the composition gets built.
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("ViewTreeLifecycleOwner not found")
                || msg.contains("ViewTreeSavedStateRegistryOwner not found")
                || msg.contains("ViewTreeViewModelStoreOwner not found")
                // v1.0.1047: "Cannot locate windowRecomposer" is the SAME class of
                // failure -- the composition could not be created because the
                // owner was not reachable yet -- but it was the one message that
                // fell through to the diagnostic counter WITHOUT queueing a retry.
                // So the case we actually observe in the wild (Thunderbird,
                // Aurora and Signal all hit this exact string) was the only one
                // that never got a second chance.
                || msg.contains("Cannot locate windowRecomposer")) {
                macapkRegisterComposeAttachRetry(this);
            }
            if (!warnedAttachThrow) {
                warnedAttachThrow = true;
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                com.macapk.loader.NativeLog.w("View",
                    "onAttachedToWindow threw on " + getClass().getName()
                    + ": " + t + "\n" + sw);
            }
        }
        if (attachStateChangeListeners != null) {
            for (OnAttachStateChangeListener l :
                    new java.util.ArrayList<>(attachStateChangeListeners)) {
                try { l.onViewAttachedToWindow(this); } catch (Throwable ignored) {}
            }
        }
        // v1.0.410: a late-attached view that carries a ViewTree* owner tag
        // (e.g. a Conductor controller view hosting a ComposeView -- Conductor
        // sets ViewTreeLifecycleOwner on the controller view before adding it)
        // hosts a Compose WindowRecomposer that binds its lifecycle-aware
        // recompose+apply loop to that owner AT ATTACH, with the owner's
        // LifecycleRegistry still at INITIALIZED. The activity-resume /
        // window-focus lifecycle drive already ran, so without a re-drive the
        // registry never reaches CREATED/STARTED and the recomposer never
        // applies the composition (content composes but its LayoutNodes never
        // attach -> blank). Schedule a debounced re-drive. Gated on keyed tags
        // so plain views (RecyclerView items during scroll) don't churn it.
        if (keyedTags != null && !keyedTags.isEmpty()) {
            try { com.macapk.loader.ActivityHost.macapkScheduleViewTreeLifecycleDrive(); }
            catch (Throwable ignored) {}
        }
    }
    // v1.0.437: ComposeViews whose onAttachedToWindow threw because no
    // ViewTreeLifecycleOwner was reachable yet (Conductor sets it late, after the
    // detached container joins the decor). Weak keys (identity, since View doesn't
    // override equals) so detached/GC'd views drop out. macapkRetryComposeAttaches
    // is invoked at the end of each view-tree lifecycle drive, once owners are set.
    private static final java.util.Set<View> sComposeAttachRetry =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<View,Boolean>());
    private static boolean warnedAttachRetryReg = false;
    static void macapkRegisterComposeAttachRetry(View v) {
        if (v == null) return;
        synchronized (sComposeAttachRetry) { sComposeAttachRetry.add(v); }
        if (!warnedAttachRetryReg) {
            warnedAttachRetryReg = true;
            com.macapk.loader.NativeLog.i("View",
                "compose-attach retry REGISTERED on " + v.getClass().getName());
        }
        try { com.macapk.loader.ActivityHost.macapkScheduleViewTreeLifecycleDrive(); }
        catch (Throwable ignored) {}
    }
    /** Re-run onAttachedToWindow on ComposeViews that earlier failed to find a
     *  ViewTreeLifecycleOwner. ensureCompositionCreated is idempotent, so a view
     *  that already composed is a cheap no-op; one that still can't find an owner
     *  re-throws and stays queued for the next drive. Called from
     *  ActivityHost.driveViewTreeLifecycles AFTER owners are driven. */
    public static void macapkRetryComposeAttaches() {
        java.util.List<View> pending;
        synchronized (sComposeAttachRetry) {
            if (sComposeAttachRetry.isEmpty()) return;
            pending = new java.util.ArrayList<>(sComposeAttachRetry);
        }
        com.macapk.loader.NativeLog.i("View",
            "compose-attach retry: trying " + pending.size() + " view(s)");
        for (View v : pending) {
            if (v == null || !v.macapkAttached) {
                synchronized (sComposeAttachRetry) { sComposeAttachRetry.remove(v); }
                continue;
            }
            try {
                v.onAttachedToWindow();
                // No throw -> owner found, composition created (or already was).
                synchronized (sComposeAttachRetry) { sComposeAttachRetry.remove(v); }
                com.macapk.loader.NativeLog.i("View",
                    "compose-attach retry SUCCEEDED on " + v.getClass().getName());
            } catch (Throwable t) {
                // Still no owner; keep queued for the next drive.
            }
        }
    }
    /** v1.0.1643: set on the ROOT of a window that was removed (its activity
     *  was destroyed). AOSP ViewRootImpl.doDie detaches the tree and the
     *  ViewRootImpl itself is gone, so nothing attaches that tree again. Our
     *  canvas pump attaches its root on every frame, so a frame still queued
     *  for a destroyed activity's screen re-attached the whole tree -- measured
     *  on Wikipedia: a LinearProgressIndicator's tick restarted and kept the
     *  destroyed PageActivity reachable from the MessageQueue. Cleared when the
     *  view gets a new window (a new ViewCanvasDriver, an overlay add) or a
     *  parent. */
    private volatile boolean macapkWindowRemoved;
    public boolean macapkIsWindowRemoved() { return macapkWindowRemoved; }
    public void macapkClearWindowRemoved() { macapkWindowRemoved = false; }
    /** AOSP WindowManagerGlobal.removeView -> ViewRootImpl.die: detach the whole
     *  tree and keep it detached. */
    public void macapkRemoveWindow() {
        macapkWindowRemoved = true;
        macapkDispatchDetachedFromWindow();
    }
    /** Recursive detach dispatch; symmetric to attach. */
    public void macapkDispatchDetachedFromWindow() {
        macapkTraceComposeAttach("DETACH", macapkAttached);
        if (!macapkAttached) return;
        macapkAttached = false;
        try { onDetachedFromWindow(); } catch (Throwable ignored) {}
        if (attachStateChangeListeners != null) {
            for (OnAttachStateChangeListener l :
                    new java.util.ArrayList<>(attachStateChangeListeners)) {
                try { l.onViewDetachedFromWindow(this); } catch (Throwable ignored) {}
            }
        }
    }
    /** AOSP isLaidOut():12112 reads PFLAG3_IS_LAID_OUT, which layout() sets and
     *  nothing clears for the life of the view. It answered a hardcoded true,
     *  which is a lie on a view that has never been laid out and is exactly the
     *  question AppCompat's ContentFrameLayout.setDecorPadding asks to decide
     *  whether it may skip a requestLayout (fwprobe VW.det.islaidout.before: 0
     *  on the reference, 1 here; .after agrees at 1 on both). Set in layout()
     *  below, so the "after" answer is unchanged. */
    private boolean mIsLaidOut;
    public boolean isLaidOut()                          { return mIsLaidOut; }
    public boolean isInLayout()                         { return false; }
    public int     getWindowSystemUiVisibility()       { return 0; }
    // AOSP View scroll offset (v1.0.421). mScrollX/mScrollY = how far this
    // view's content is scrolled; the parent's dispatchDraw/drawChild position
    // children at getLeft()-mScrollX/getTop()-mScrollY and touch is offset by
    // the parent's scroll. Was 0/no-op -> ScrollView/HorizontalScrollView/
    // NestedScrollView/RecyclerView programmatic scroll (scrollTo/scrollBy/
    // scrollToPosition/smoothScrollTo) had no visual effect.
    private int mScrollX, mScrollY;
    public int  getScrollX() { return mScrollX; }
    public int  getScrollY() { return mScrollY; }

    // API 1 -- AOSP signature: `public void setScrollContainer(boolean
    // isScrollContainer)`. Adds the View to a system-wide list of
    // resize-on-keyboard-show views. We have no soft keyboard pump,
    // so this is a no-op. RecyclerView calls it during init (so its
    // root is on the resize list); without the method on the base
    // class, RecyclerView's <init> NoSuchMethodErrors and the
    // LayoutInflater falls back to a placeholder View. F-Droid's
    // MainActivity hits this because its activity_main layout has a
    // RecyclerView at the top level.
    private boolean scrollContainer;
    public void setScrollContainer(boolean isScrollContainer) {
        this.scrollContainer = isScrollContainer;
    }
    public boolean isScrollContainer() { return scrollContainer; }
    public void scrollTo(int x, int y) {
        if (x != mScrollX || y != mScrollY) {
            int ox = mScrollX, oy = mScrollY;
            mScrollX = x;
            mScrollY = y;
            onScrollChanged(mScrollX, mScrollY, ox, oy);
            ViewTreeObserver vto = macapkPeekTreeObserver();
            if (vto != null) vto.dispatchOnScrollChanged();
            invalidate();
        }
    }
    public void scrollBy(int dx, int dy) { scrollTo(mScrollX + dx, mScrollY + dy); }

    // Scroll-related no-op surface needed by RecyclerView, NestedScrollView,
    // ViewPager, and many AppCompat / Material scrolling containers. Each
    // method here was added in response to a specific NoSuchMethodError
    // observed while inflating F-Droid's MainActivity layout (RecyclerView
    // <init> reads most of these during construction). macOS doesn't have
    // an equivalent overscroll / nested-scroll behavior so values are
    // sensible defaults (NEVER overscroll, no nested scrolling).
    public static final int OVER_SCROLL_ALWAYS              = 0;
    public static final int OVER_SCROLL_IF_CONTENT_SCROLLS  = 1;
    public static final int OVER_SCROLL_NEVER               = 2;
    private int overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS;
    public int  getOverScrollMode() { return overScrollMode; }
    /** AOSP setOverScrollMode (View.java:26829-26836) REJECTS anything outside
     *  the three constants with an IllegalArgumentException, before storing.
     *  We accepted (and returned) any int, so a caller that passed a garbage
     *  mode got a view in a state real Android cannot produce, and the bug
     *  surfaced far from its cause (fwprobe VW.id.overscroll.{invalid,negative}
     *  read THROW on the reference and 7 / -1 here). */
    public void setOverScrollMode(int m) {
        if (m != OVER_SCROLL_ALWAYS && m != OVER_SCROLL_IF_CONTENT_SCROLLS
                && m != OVER_SCROLL_NEVER) {
            throw new IllegalArgumentException("Invalid overscroll mode " + m);
        }
        overScrollMode = m;
    }

    /** AOSP View.SCROLLBAR_POSITION_* (View.java, API 11). Used by
     *  setVerticalScrollbarPosition. */
    public static final int SCROLLBAR_POSITION_DEFAULT = 0;
    public static final int SCROLLBAR_POSITION_LEFT    = 1;
    public static final int SCROLLBAR_POSITION_RIGHT   = 2;

    public static final int SCROLLBARS_INSIDE_OVERLAY  = 0x0;
    public static final int SCROLLBARS_INSIDE_INSET    = 0x01000000;
    public static final int SCROLLBARS_OUTSIDE_OVERLAY = 0x02000000;
    public static final int SCROLLBARS_OUTSIDE_INSET   = 0x03000000;
    private int scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY;
    public int  getScrollBarStyle() { return scrollBarStyle; }
    public void setScrollBarStyle(int style) { scrollBarStyle = style; }

    public static final int SCROLL_INDICATOR_TOP     = 0x1;
    public static final int SCROLL_INDICATOR_BOTTOM  = 0x2;
    public static final int SCROLL_INDICATOR_LEFT    = 0x4;
    public static final int SCROLL_INDICATOR_RIGHT   = 0x8;
    public static final int SCROLL_INDICATOR_START   = 0x10;
    public static final int SCROLL_INDICATOR_END     = 0x20;
    private int scrollIndicators;
    public int  getScrollIndicators() { return scrollIndicators; }
    public void setScrollIndicators(int indicators) { scrollIndicators = indicators; }
    public void setScrollIndicators(int indicators, int mask) {
        scrollIndicators = (scrollIndicators & ~mask) | (indicators & mask);
    }

    /** AOSP View.canScrollHorizontally / canScrollVertically, verbatim
     *  arithmetic:
     *      final int offset = computeVerticalScrollOffset();
     *      final int range  = computeVerticalScrollRange()
     *                       - computeVerticalScrollExtent();
     *      if (range == 0) return false;
     *      if (direction < 0) return offset > 0; else return offset < range - 1;
     *  These were hardcoded `return false`, so NOTHING in the tree could report
     *  that it was scrollable. The consumer that matters is
     *  SwipeRefreshLayout.canChildScrollUp, which is exactly this call and which
     *  decides whether a downward drag starts a refresh or scrolls the list.
     *  Note `range - 1`, not `range`: writing `range` makes a fully-scrolled list
     *  claim it can still scroll down. */
    public boolean canScrollHorizontally(int direction) {
        final int offset = computeHorizontalScrollOffset();
        final int range = computeHorizontalScrollRange() - computeHorizontalScrollExtent();
        if (range == 0) return false;
        if (direction < 0) {
            return offset > 0;
        } else {
            return offset < range - 1;
        }
    }
    public boolean canScrollVertically(int direction) {
        final int offset = computeVerticalScrollOffset();
        final int range = computeVerticalScrollRange() - computeVerticalScrollExtent();
        if (range == 0) return false;
        if (direction < 0) {
            return offset > 0;
        } else {
            return offset < range - 1;
        }
    }

    // AOSP defaults. Five of these six were already exact; the two OFFSETS
    // returned a hardcoded 0 where AOSP returns the view's own scroll position
    // (javadoc: "The default offset is the scroll offset of this view").
    //
    // That 0 was not academic. androidx's NestedScrollView overrides range and
    // extent but CHAINS the offset to us -- its bytecode is literally
    // `Math.max(0, super.computeVerticalScrollOffset())` -- so every scrolled
    // NestedScrollView in the fleet reported offset 0 forever: its scrollbar
    // thumb never moved, and a SwipeRefreshLayout wrapping it stole the drag even
    // when the content was scrolled away from the top.
    //
    // Note range - extent is 0 for a bare View, here and in AOSP, so this alone
    // cannot make a plain View claim to scroll. Our own ScrollView / ListView /
    // HorizontalScrollView still need their AOSP computeVerticalScrollRange
    // overrides before THEY can; that is a separate, separately-measured change.
    public int  computeHorizontalScrollExtent() { return getWidth(); }
    public int  computeHorizontalScrollOffset() { return mScrollX; }
    public int  computeHorizontalScrollRange()  { return getWidth(); }
    public int  computeVerticalScrollExtent()   { return getHeight(); }
    public int  computeVerticalScrollOffset()   { return mScrollY; }
    public int  computeVerticalScrollRange()    { return getHeight(); }

    /** AOSP View.overScrollBy (android11-release View.java:26733-26783).
     *  REAL body, not a stub: clamps a proposed scroll delta against the
     *  over-scroll range, calls onOverScrolled with the clamped result, and
     *  reports whether either axis got clamped. ScrollView.computeScroll and
     *  HorizontalScrollView both depend on this existing -- HorizontalScrollView
     *  already carries a dormant onOverScrolled override waiting for a caller.
     *  Ported verbatim from AOSP except mOverScrollMode -> this file's
     *  overScrollMode field (no m-prefix here; see setOverScrollMode above).
     *
     *  maxOverScrollX/Y are zeroed when the corresponding axis isn't
     *  scrollable per overScrollMode, so OVER_SCROLL_NEVER (and
     *  OVER_SCROLL_IF_CONTENT_SCROLLS on a non-scrolling axis) clamps hard at
     *  the content edge exactly like AOSP -- no rubber-banding past
     *  scrollRangeX/Y unless the mode says this axis is allowed to. */
    @SuppressWarnings("UnusedParameters")
    protected boolean overScrollBy(int deltaX, int deltaY,
            int scrollX, int scrollY,
            int scrollRangeX, int scrollRangeY,
            int maxOverScrollX, int maxOverScrollY,
            boolean isTouchEvent) {
        final int mode = overScrollMode;
        final boolean canScrollHorizontal =
                computeHorizontalScrollRange() > computeHorizontalScrollExtent();
        final boolean canScrollVertical =
                computeVerticalScrollRange() > computeVerticalScrollExtent();
        final boolean overScrollHorizontal = mode == OVER_SCROLL_ALWAYS
                || (mode == OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollHorizontal);
        final boolean overScrollVertical = mode == OVER_SCROLL_ALWAYS
                || (mode == OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollVertical);

        int newScrollX = scrollX + deltaX;
        if (!overScrollHorizontal) {
            maxOverScrollX = 0;
        }

        int newScrollY = scrollY + deltaY;
        if (!overScrollVertical) {
            maxOverScrollY = 0;
        }

        // Clamp values if at the limits and record
        final int left = -maxOverScrollX;
        final int right = maxOverScrollX + scrollRangeX;
        final int top = -maxOverScrollY;
        final int bottom = maxOverScrollY + scrollRangeY;

        boolean clampedX = false;
        if (newScrollX > right) {
            newScrollX = right;
            clampedX = true;
        } else if (newScrollX < left) {
            newScrollX = left;
            clampedX = true;
        }

        boolean clampedY = false;
        if (newScrollY > bottom) {
            newScrollY = bottom;
            clampedY = true;
        } else if (newScrollY < top) {
            newScrollY = top;
            clampedY = true;
        }

        onOverScrolled(newScrollX, newScrollY, clampedX, clampedY);

        return clampedX || clampedY;
    }

    /** AOSP View.onOverScrolled (android11-release View.java:26788-26798):
     *  intentionally empty base. ScrollView / HorizontalScrollView override
     *  it to move mScrollX/mScrollY to the clamped position; the base class
     *  has no opinion on how a subclass stores its scroll position. */
    protected void onOverScrolled(int scrollX, int scrollY,
            boolean clampedX, boolean clampedY) {
        // Intentionally empty.
    }

    public void setScrollBarFadeDuration(int duration) {}
    public int  getScrollBarFadeDuration() { return 0; }
    public void setScrollBarDefaultDelayBeforeFade(int delay) {}
    public int  getScrollBarDefaultDelayBeforeFade() { return 0; }
    public void setScrollBarSize(int size) {}
    public int  getScrollBarSize() { return 0; }
    /** AOSP View.setVerticalScrollbarThumbDrawable (android11-release
     *  View.java:6792-6795): routes through mScrollCache.scrollBar (a
     *  ScrollBarDrawable) which this file never allocates (see the
     *  mScrollCache/ScrollBarDrawable comment earlier in this file -- the
     *  scrollbar-offset accessors already document why: "we never allocate a
     *  ScrollBarDrawable"). No renderer exists yet to feed, so this stores
     *  the value plainly rather than inventing a parallel mechanism, same
     *  pattern used for forceHasOverlappingRendering above -- ready for a
     *  real scrollbar-drawable consumer once one lands. */
    private android.graphics.drawable.Drawable verticalScrollbarThumbDrawable;
    public void setVerticalScrollbarThumbDrawable(android.graphics.drawable.Drawable drawable) {
        verticalScrollbarThumbDrawable = drawable;
    }

    // NestedScrollingChild / NestedScrollingChild3 surface (API 21+).
    // RecyclerView and CoordinatorLayout both consume this. macOS
    // scroll-handoff doesn't exist; everything is a no-op returning false.
    /** AOSP setNestedScrollingEnabled/isNestedScrollingEnabled
     *  (View.java:26873) -- PFLAG3_NESTED_SCROLLING_ENABLED, default OFF.
     *  Round-tripping it does not by itself start a nested scroll (that is
     *  startNestedScroll's job); it stops a container from being told its own
     *  opt-in did not take (fwprobe VW.flag.nestedScrollingEnabled.true). */
    private boolean mNestedScrollingEnabled;
    public void setNestedScrollingEnabled(boolean enabled) { mNestedScrollingEnabled = enabled; }
    public boolean isNestedScrollingEnabled() { return mNestedScrollingEnabled; }
    public boolean startNestedScroll(int axes) { return false; }
    public boolean startNestedScroll(int axes, int type) { return false; }
    public void stopNestedScroll() {}
    public void stopNestedScroll(int type) {}
    public boolean hasNestedScrollingParent() { return false; }
    public boolean hasNestedScrollingParent(int type) { return false; }
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
                                          int dxUnconsumed, int dyUnconsumed,
                                          int[] offsetInWindow) { return false; }
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
                                          int dxUnconsumed, int dyUnconsumed,
                                          int[] offsetInWindow, int type) { return false; }
    public boolean dispatchNestedPreScroll(int dx, int dy,
                                             int[] consumed, int[] offsetInWindow) { return false; }
    public boolean dispatchNestedPreScroll(int dx, int dy,
                                             int[] consumed, int[] offsetInWindow, int type) { return false; }
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) { return false; }
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) { return false; }

    // Autofill (API 26). RecyclerView reads getImportantForAutofill().
    public static final int IMPORTANT_FOR_AUTOFILL_AUTO          = 0;
    public static final int IMPORTANT_FOR_AUTOFILL_YES           = 1;
    public static final int IMPORTANT_FOR_AUTOFILL_NO            = 2;
    public static final int IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS = 4;
    public static final int IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS  = 8;
    private int importantForAutofill = IMPORTANT_FOR_AUTOFILL_AUTO;
    /** AOSP View.AUTOFILL_TYPE_* (View.java:1216-1259). These are the values
     *  getAutofillType() answers with, and they are COMPILE-TIME constants that
     *  javac inlines into every APK -- so an app never actually reads the field
     *  at runtime, but the field must exist for anything that reflects, and
     *  this is their AOSP home. Declared here (v1.0.940) rather than duplicated
     *  privately in EditText, which is where they had to be worked around. */
    public static final int AUTOFILL_TYPE_NONE = 0;
    public static final int AUTOFILL_TYPE_TEXT = 1;
    public static final int AUTOFILL_TYPE_TOGGLE = 2;
    public static final int AUTOFILL_TYPE_LIST = 3;
    public static final int AUTOFILL_TYPE_DATE = 4;

    /** AOSP View.getAutofillType(): "the type of autofill value this view
     *  requires", AUTOFILL_TYPE_NONE for a view that is not autofillable. The
     *  base View is not, so NONE; TextView overrides it. */
    public int getAutofillType() { return AUTOFILL_TYPE_NONE; }

    public int  getImportantForAutofill() { return importantForAutofill; }
    public void setImportantForAutofill(int mode) { importantForAutofill = mode; }
    public boolean isImportantForAutofill() { return true; }

    // Content-capture (API 30). Similar shape -- AOSP added these for
    // OS-level analytics opt-in/out. macOS has no equivalent; default
    // to AUTO and no-op the setter.
    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_AUTO = 0;
    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_YES  = 1;
    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_NO   = 2;
    private int importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_AUTO;
    public int  getImportantForContentCapture() { return importantForContentCapture; }
    public void setImportantForContentCapture(int mode) { importantForContentCapture = mode; }
    /** AOSP API-29+ `View.getContentCaptureSession()`. Returns the
     *  ContentCaptureSession this view should report to, or null if
     *  ContentCapture isn't enabled. Mac.apk has no ContentCapture
     *  pipeline, so always null. F-Droid's RecyclerView+ViewHolder
     *  bind path calls this reflectively and NPEs at class-link time
     *  if the method is missing. */
    public android.view.contentcapture.ContentCaptureSession getContentCaptureSession() {
        return null;
    }
    public void setContentCaptureSession(android.view.contentcapture.ContentCaptureSession session) {}

    // Accessibility-importance (API 16+). Same shape.
    public static final int IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0;
    public static final int IMPORTANT_FOR_ACCESSIBILITY_YES  = 1;
    public static final int IMPORTANT_FOR_ACCESSIBILITY_NO   = 2;
    public static final int IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4;
    private int importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO;
    public int  getImportantForAccessibility() { return importantForAccessibility; }
    public void setImportantForAccessibility(int mode) { importantForAccessibility = mode; }
    /** AOSP isImportantForAccessibility (View.java:13570-13591), ported whole.
     *  It returned a hardcoded `true`, which is the one answer that is wrong in
     *  every direction: it claimed importance for a view EXPLICITLY marked NO /
     *  NO_HIDE_DESCENDANTS, for a descendant of a NO_HIDE_DESCENDANTS parent,
     *  and for the inert default AUTO view -- the common case, since AUTO only
     *  becomes important once the view is actionable, carries a live region, is
     *  an a11y pane, or has a listener that implies interaction. Every
     *  accessibility consumer (TalkBack-style tree walks, androidx's
     *  ViewCompat.isImportantForAccessibility, our own ActivityHost tree walk)
     *  reads it, so a blanket true makes every decorative view look
     *  interactive. fwprobe VW.id.ifa.* + VW.id.cd.*.ifa.
     *
     *  hasListenersForAccessibility() is AOSP's ListenerInfo check; ours reads
     *  the equivalent discrete listener fields. getAccessibilityNodeProvider()
     *  is not part of this framework's surface, so that disjunct is dropped --
     *  it can only ever ADD importance, and nothing here can return one. */
    public boolean isImportantForAccessibility() {
        final int mode = getImportantForAccessibility();
        if (mode == IMPORTANT_FOR_ACCESSIBILITY_NO
                || mode == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
            return false;
        }
        // Check parent mode to ensure we're not hidden.
        ViewParent p = getParent();
        for (int guard = 0; guard < 256 && p instanceof View; guard++) {
            if (((View) p).getImportantForAccessibility()
                    == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                return false;
            }
            p = ((View) p).getParent();
        }
        return mode == IMPORTANT_FOR_ACCESSIBILITY_YES
                || isActionableForAccessibility()
                || hasListenersForAccessibility()
                || getAccessibilityLiveRegion() != ACCESSIBILITY_LIVE_REGION_NONE
                || isAccessibilityPane();
    }
    /** AOSP isActionableForAccessibility: `clickable || longClickable ||
     *  focusable`. isFocusable() is the FOCUSABLE bit, not FOCUSABLE_AUTO, so a
     *  default view is not actionable. */
    private boolean isActionableForAccessibility() {
        return isClickable() || isLongClickable() || isFocusable();
    }
    /** AOSP hasListenersForAccessibility (android14 :14949), expressed against
     *  our discrete listener fields instead of its ListenerInfo: "mTouchDelegate
     *  != null || info.mOnKeyListener != null || info.mOnTouchListener != null
     *  || info.mOnGenericMotionListener != null || info.mOnHoverListener != null
     *  || info.mOnDragListener != null". The drag listener is a real field now
     *  (setOnDragListener stores it), so it counts as AOSP's does. The hover
     *  listener is still a no-op stub with no field to read; it can only ADD
     *  importance, so omitting it is conservative in the same direction as the
     *  dropped node-provider term. */
    private boolean hasListenersForAccessibility() {
        return mTouchDelegate != null || onKeyListener != null || onTouchListener != null
                || onGenericMotionListener != null || mOnDragListener != null;
    }
    /** AOSP isAccessibilityPane: `mAccessibilityPaneTitle != null`. */
    private boolean isAccessibilityPane() { return mAccessibilityPaneTitle != null; }
    /** AOSP `View.AccessibilityDelegate` (API 14+, frameworks/base
     *  View.java:32839-33086). RecyclerView, androidx's
     *  AccessibilityDelegateCompat adapter and Compose's a11y bridge all
     *  install one of these.
     *
     *  The parameter types are load-bearing. Java method identity is
     *  (name, ERASED parameter types), so the previous Object-typed
     *  declarations were not overridden by a subclass compiled against the
     *  real android.jar -- the app's AccessibilityEvent/AccessibilityNodeInfo/
     *  Bundle-typed methods became unrelated OVERLOADS and our dispatch never
     *  reached them. No exception, no log: the delegate simply never fired.
     *
     *  Each default body forwards to the host's matching ...Internal method,
     *  exactly like AOSP, so `new AccessibilityDelegate(){}` (no overrides) is
     *  behaviourally identical to having no delegate at all. */
    public static class AccessibilityDelegate {
        public boolean dispatchPopulateAccessibilityEvent(View host,
                android.view.accessibility.AccessibilityEvent event) {
            return host.dispatchPopulateAccessibilityEventInternal(event);
        }
        public void onInitializeAccessibilityEvent(View host,
                android.view.accessibility.AccessibilityEvent event) {
            host.onInitializeAccessibilityEventInternal(event);
        }
        public void onInitializeAccessibilityNodeInfo(View host,
                android.view.accessibility.AccessibilityNodeInfo info) {
            host.onInitializeAccessibilityNodeInfoInternal(info);
        }
        public void onPopulateAccessibilityEvent(View host,
                android.view.accessibility.AccessibilityEvent event) {
            host.onPopulateAccessibilityEventInternal(event);
        }
        // AOSP forwards to host.onRequestSendAccessibilityEventInternal(child,
        // event), which lives on ViewGroup and returns true. ViewGroup is not
        // ours to extend here, so mirror that method's AOSP return directly.
        public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                android.view.accessibility.AccessibilityEvent event) {
            return true;
        }
        public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
            return host.performAccessibilityActionInternal(action, args);
        }
        public void sendAccessibilityEvent(View host, int eventType) {
            host.sendAccessibilityEventInternal(eventType);
        }
        public void sendAccessibilityEventUnchecked(View host,
                android.view.accessibility.AccessibilityEvent event) {
            host.sendAccessibilityEventUncheckedInternal(event);
        }
        public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
            return null;
        }
        public android.view.accessibility.AccessibilityNodeInfo createAccessibilityNodeInfo(View host) {
            return host.createAccessibilityNodeInfoInternal();
        }
        public void addExtraDataToAccessibilityNodeInfo(View host,
                android.view.accessibility.AccessibilityNodeInfo info,
                String extraDataKey, android.os.Bundle arguments) {
            host.addExtraDataToAccessibilityNodeInfo(info, extraDataKey, arguments);
        }
    }
    private AccessibilityDelegate accessibilityDelegate;
    public void setAccessibilityDelegate(AccessibilityDelegate delegate) {
        this.accessibilityDelegate = delegate;
    }
    public AccessibilityDelegate getAccessibilityDelegate() { return accessibilityDelegate; }
    /** AOSP ACCESSIBILITY_LIVE_REGION_{NONE,POLITE,ASSERTIVE} = 0/1/2 with a
     *  plain stored mode (View.java setAccessibilityLiveRegion). The value is
     *  not merely informational: isImportantForAccessibility() below reads it,
     *  so a no-op setter made a live region invisible to the a11y importance
     *  rule as well as to its own getter (fwprobe VW.id.livereg.*). */
    public static final int ACCESSIBILITY_LIVE_REGION_NONE      = 0;
    public static final int ACCESSIBILITY_LIVE_REGION_POLITE    = 1;
    public static final int ACCESSIBILITY_LIVE_REGION_ASSERTIVE = 2;
    private int mAccessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE;
    public void setAccessibilityLiveRegion(int mode) { mAccessibilityLiveRegion = mode; }
    public int  getAccessibilityLiveRegion() { return mAccessibilityLiveRegion; }
    public void announceForAccessibility(CharSequence text) {}

    // ---- AOSP accessibility hook pairs (View.java:8951-11805) ----
    // Every public hook first offers the installed AccessibilityDelegate the
    // chance to handle it, then falls back to the ...Internal body -- the exact
    // AOSP shape. Before this the delegate field had only a setter and a
    // getter; nothing in the tree ever read it, so an installed delegate was
    // silently inert.
    //
    // The ...Internal bodies are deliberately minimal: Mac.apk bridges no
    // accessibility service to NSAccessibility, and AccessibilityManager
    // .isEnabled() is false, which is precisely the branch AOSP's
    // sendAccessibilityEventInternal early-returns on when no a11y service is
    // running. The node/event POPULATION paths do real work, because app and
    // androidx overrides chain through them (super.onInitializeAccessibilityNodeInfo).
    public void sendAccessibilityEvent(int eventType) {
        if (accessibilityDelegate != null) {
            accessibilityDelegate.sendAccessibilityEvent(this, eventType);
        } else {
            sendAccessibilityEventInternal(eventType);
        }
    }
    public void sendAccessibilityEventInternal(int eventType) {
        // AOSP: `if (AccessibilityManager.getInstance(mContext).isEnabled())
        // sendAccessibilityEventUnchecked(...)`. Our manager is always disabled
        // (no screen reader bridged), so the event is never built -- same as a
        // real device with no accessibility service enabled.
    }
    public void sendAccessibilityEventUnchecked(android.view.accessibility.AccessibilityEvent event) {
        if (accessibilityDelegate != null) {
            accessibilityDelegate.sendAccessibilityEventUnchecked(this, event);
        } else {
            sendAccessibilityEventUncheckedInternal(event);
        }
    }
    public void sendAccessibilityEventUncheckedInternal(
            android.view.accessibility.AccessibilityEvent event) {
        if (event == null || !isShown()) return;
        onInitializeAccessibilityEvent(event);
        dispatchPopulateAccessibilityEvent(event);
        // AOSP then hands the event to AccessibilityManager; ours is disabled,
        // so the event is dropped here exactly as it would be on a device with
        // no a11y service listening.
    }
    public boolean dispatchPopulateAccessibilityEvent(
            android.view.accessibility.AccessibilityEvent event) {
        if (accessibilityDelegate != null) {
            return accessibilityDelegate.dispatchPopulateAccessibilityEvent(this, event);
        }
        return dispatchPopulateAccessibilityEventInternal(event);
    }
    public boolean dispatchPopulateAccessibilityEventInternal(
            android.view.accessibility.AccessibilityEvent event) {
        onPopulateAccessibilityEvent(event);
        return false;
    }
    public void onPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        if (accessibilityDelegate != null) {
            accessibilityDelegate.onPopulateAccessibilityEvent(this, event);
        } else {
            onPopulateAccessibilityEventInternal(event);
        }
    }
    public void onPopulateAccessibilityEventInternal(
            android.view.accessibility.AccessibilityEvent event) {}
    public void onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        if (accessibilityDelegate != null) {
            accessibilityDelegate.onInitializeAccessibilityEvent(this, event);
        } else {
            onInitializeAccessibilityEventInternal(event);
        }
    }
    public void onInitializeAccessibilityEventInternal(
            android.view.accessibility.AccessibilityEvent event) {
        if (event == null) return;
        event.setSource(this);
        event.setClassName(getAccessibilityClassName());
        event.setEnabled(isEnabled());
        event.setContentDescription(getContentDescription());
        try {
            if (context != null) event.setPackageName(context.getPackageName());
        } catch (Throwable ignored) {}
    }
    public void onInitializeAccessibilityNodeInfo(
            android.view.accessibility.AccessibilityNodeInfo info) {
        if (accessibilityDelegate != null) {
            accessibilityDelegate.onInitializeAccessibilityNodeInfo(this, info);
        } else {
            onInitializeAccessibilityNodeInfoInternal(info);
        }
    }
    public void onInitializeAccessibilityNodeInfoInternal(
            android.view.accessibility.AccessibilityNodeInfo info) {
        if (info == null) return;
        info.setSource(this);
        info.setClassName(getAccessibilityClassName());
        info.setContentDescription(getContentDescription());
        info.setEnabled(isEnabled());
        info.setClickable(isClickable());
        info.setLongClickable(isLongClickable());
        info.setFocusable(isFocusable());
        info.setFocused(isFocused());
        info.setSelected(isSelected());
        Rect bounds = new Rect();
        getBoundsOnScreen(bounds);
        info.setBoundsInScreen(bounds);
        info.setBoundsInParent(new Rect(getLeft(), getTop(), getRight(), getBottom()));
    }
    /** AOSP View.createAccessibilityNodeInfo() (View.java:9341): build a node
     *  for this view, letting the delegate substitute its own. */
    public android.view.accessibility.AccessibilityNodeInfo createAccessibilityNodeInfo() {
        if (accessibilityDelegate != null) {
            return accessibilityDelegate.createAccessibilityNodeInfo(this);
        }
        return createAccessibilityNodeInfoInternal();
    }
    public android.view.accessibility.AccessibilityNodeInfo createAccessibilityNodeInfoInternal() {
        // AOSP first asks getAccessibilityNodeProvider() for the HOST_VIEW_ID
        // node when a virtual hierarchy is present; our AccessibilityNodeProvider
        // has no createAccessibilityNodeInfo(int) yet, and the provider is null
        // unless a delegate supplies one, so the direct path is equivalent today.
        android.view.accessibility.AccessibilityNodeInfo info =
            android.view.accessibility.AccessibilityNodeInfo.obtain(this);
        onInitializeAccessibilityNodeInfo(info);
        return info;
    }
    /** AOSP View.getAccessibilityNodeProvider() (View.java:11803): non-null only
     *  for views that expose a virtual view hierarchy (WebView, a custom canvas
     *  widget), which they do by installing a delegate that returns one. */
    public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() {
        if (accessibilityDelegate != null) {
            return accessibilityDelegate.getAccessibilityNodeProvider(this);
        }
        return null;
    }
    /** AOSP View.addExtraDataToAccessibilityNodeInfo (View.java:11570): the base
     *  body is empty; subclasses (TextView's character-bounds key) override. */
    public void addExtraDataToAccessibilityNodeInfo(
            android.view.accessibility.AccessibilityNodeInfo info,
            String extraDataKey, android.os.Bundle arguments) {}
    /** AOSP View.performAccessibilityAction (View.java:16070). */
    public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (accessibilityDelegate != null) {
            return accessibilityDelegate.performAccessibilityAction(this, action, arguments);
        }
        return performAccessibilityActionInternal(action, arguments);
    }
    // AccessibilityNodeInfo.ACTION_* legacy int ids, verbatim from AOSP
    // frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java.
    // They are declared locally because our AccessibilityNodeInfo stub currently
    // exposes the ids only through the AccessibilityAction objects; the ints on
    // the wire (what performAccessibilityAction is handed) are these values.
    private static final int A11Y_ACTION_FOCUS           = 0x00000001;
    private static final int A11Y_ACTION_CLEAR_FOCUS     = 0x00000002;
    private static final int A11Y_ACTION_SELECT          = 0x00000004;
    private static final int A11Y_ACTION_CLEAR_SELECTION = 0x00000008;
    private static final int A11Y_ACTION_CLICK           = 0x00000010;
    private static final int A11Y_ACTION_LONG_CLICK      = 0x00000020;
    public boolean performAccessibilityActionInternal(int action, android.os.Bundle arguments) {
        if (!isEnabled()) return false;
        switch (action) {
            case A11Y_ACTION_CLICK:
                if (isClickable()) { performClick(); return true; }
                break;
            case A11Y_ACTION_LONG_CLICK:
                if (isLongClickable()) { performLongClick(); return true; }
                break;
            case A11Y_ACTION_FOCUS:
                if (!hasFocus()) { return requestFocus(); }
                break;
            case A11Y_ACTION_CLEAR_FOCUS:
                if (hasFocus()) { clearFocus(); return !hasFocus(); }
                break;
            case A11Y_ACTION_SELECT:
                if (!isSelected()) { setSelected(true); return isSelected(); }
                break;
            case A11Y_ACTION_CLEAR_SELECTION:
                if (isSelected()) { setSelected(false); return !isSelected(); }
                break;
            default:
                break;
        }
        return false;
    }
    /** AOSP View.setLabelFor/getLabelFor (View.java:12171/12182): the id of the
     *  view this one labels, for a11y input/label pairing. AOSP assigns this view
     *  a generated id when it labels something but has none, so the label
     *  relationship survives. */
    private int mLabelForId = NO_ID;
    public int getLabelFor() { return mLabelForId; }
    public void setLabelFor(int id) {
        if (mLabelForId == id) return;
        mLabelForId = id;
        if (mLabelForId != NO_ID && this.id == NO_ID) this.id = generateViewId();
    }
    /** AOSP View.setScreenReaderFocusable/isScreenReaderFocusable
     *  (View.java:14599/14615): treat this view (and its non-focusable subtree)
     *  as a single unit for screen readers. Default false. */
    private boolean mScreenReaderFocusable;
    public boolean isScreenReaderFocusable() { return mScreenReaderFocusable; }
    public void setScreenReaderFocusable(boolean screenReaderFocusable) {
        mScreenReaderFocusable = screenReaderFocusable;
    }
    /** AOSP View.setStateDescription/getStateDescription (View.java:11937):
     *  the spoken STATE of the view ("50 percent", "on"), distinct from the
     *  content description. Setting a non-empty one promotes an AUTO view to
     *  IMPORTANT_FOR_ACCESSIBILITY_YES, per AOSP. */
    private CharSequence mStateDescription;
    public CharSequence getStateDescription() { return mStateDescription; }
    public void setStateDescription(CharSequence stateDescription) {
        if (mStateDescription == null) {
            if (stateDescription == null) return;
        } else if (mStateDescription.equals(stateDescription)) {
            return;
        }
        mStateDescription = stateDescription;
        if (stateDescription != null && stateDescription.length() > 0
                && getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    // Scrollbar awaken (API 5+). Triggered by RecyclerView when content
    // scrolls; we don't draw scroll bars, so always reports "no need to
    // invalidate" (false).
    public boolean awakenScrollBars() { return false; }
    public boolean awakenScrollBars(int startDelay) { return false; }
    public boolean awakenScrollBars(int startDelay, boolean invalidate) { return false; }

    // Offset the layout position by dx/dy. RecyclerView's LayoutManager
    // calls these during scroll to move children without a full layout
    // pass. We mutate the bounds in place.
    // MACAPK_TRACE_OFFSET=<simpleClassName substring>: name WHO moves a view
    // between layout and draw, and by how much. Added while chasing a
    // FloatingActionButton that was laid out correctly at x=664 and then drawn
    // at x=1328 -- +664, exactly its own left -- so its parent's clip discarded
    // everything it painted, ICON INCLUDED. The mover runs inside a pre-draw
    // listener (CoordinatorLayout.onChildViewsChanged), which is app code, so
    // no amount of reading OUR source could name it. Same shape as
    // MACAPK_TRACE_ATTR: when a fix that should work doesn't, instrument the
    // site rather than improving the fix.
    private static final String MACAPK_TRACE_OFFSET =
        System.getenv("MACAPK_TRACE_OFFSET");
    private void macapkTraceOffset(String which, int offset) {
        if (MACAPK_TRACE_OFFSET == null) return;
        String cn = getClass().getSimpleName();
        if (!cn.contains(MACAPK_TRACE_OFFSET)) return;
        System.err.println("[TRACE_OFFSET] " + cn + " " + which + "=" + offset
            + " before l=" + left + " t=" + top + " r=" + right + " b=" + bottom);
        new Throwable("offset caller").printStackTrace();
    }
    public void offsetLeftAndRight(int offset) {
        macapkTraceOffset("dx", offset);
        this.left += offset;
        this.right += offset;
    }
    public void offsetTopAndBottom(int offset) {
        macapkTraceOffset("dy", offset);
        this.top += offset;
        this.bottom += offset;
    }

    // Scroll-changed callback. AOSP fires it when scrollX/scrollY change.
    // RecyclerView depends on the method existing so it can call super
    // in its onScrollChanged override.
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {}

    // Focusable-tree query (API 1+). RecyclerView ItemFocusFinder uses
    // this to figure out if anything inside the row is focusable.
    /** AOSP hasFocusable():8021 -> hasFocusable(allowAutoFocus, false), body at
     *  :8043-8067. It answered a hardcoded false, so a view that had just been
     *  made focusable reported that nothing in it could take focus -- the exact
     *  question RecyclerView / ViewGroup.focusSearch ask before deciding to
     *  skip a subtree (fwprobe VW.det.focus.hasfocusable: 1 vs 0). The
     *  ViewGroup override that walks children is ViewGroup's business; this is
     *  the leaf contract.
     *
     *  allowAutoFocus mirrors AOSP's `!sHasFocusableExcludeAutoFocusable`,
     *  which is `targetSdkVersion < O` (View.java:5896).
     *
     *  ⚠ THIS COMMENT WAS WRONG TWICE, corrected 2026-08-24 -- both halves.
     *  (1) It said "Every app this framework runs targets far above O".
     *      MEASURED FALSE against ../Apks: 5 of 62 target below 26
     *      (jetpackjoyride effective 8, frozenbubble 12, flappybird 15,
     *      hillclimb-1.8.1 16, tobsolitaire 25). Re-measure such a claim with
     *      `debug/compatgate.py --corpus ../Apks`, never assert it.
     *  (2) It named the WRONG branch modern. Excluding AUTO is the LEGACY
     *      (pre-O) behaviour: sHasFocusableExcludeAutoFocusable is TRUE below
     *      O, so AOSP passes allowAutoFocus = !true = false there, and
     *      allowAutoFocus = TRUE from O onward. The literal `false` below is
     *      therefore the PRE-O branch, applied to every app including modern
     *      ones -- an AOSP divergence in its own right, not a compat gate that
     *      is merely missing.
     *
     *  Left as-is deliberately by the legacy-compat lane: correcting it changes
     *  what hasFocusable() answers for MODERN apps (ViewGroup.focusSearch and
     *  RecyclerView both branch on it), which is a sweepable behaviour change
     *  rather than a legacy-only gate. Tracked as MISSING in
     *  debug/compatgate.py (View.sHasFocusableExcludeAutoFocusable) and in
     *  docs/legacy-compat-audit_2026-08-24.md. */
    public boolean hasFocusable() { return hasFocusable(false, false); }
    /** AOSP hasExplicitFocusable():8042 is `hasFocusable(false, true)`; the
     *  two-arg form is package-private there, as here. */
    public boolean hasExplicitFocusable() { return hasFocusable(false, true); }
    boolean hasFocusable(boolean allowAutoFocus, boolean dispatchExplicit) {
        // Invisible, gone, or disabled views are never focusable.
        if (getVisibility() != VISIBLE || !isEnabled()) return false;
        // Only use effective focusable value when allowed.
        return (allowAutoFocus || getFocusable() != FOCUSABLE_AUTO) && isFocusable();
    }

    // API 26+: setDefaultFocusHighlightEnabled. Material widgets and
    // many androidx components call this during construction.
    /** AOSP `boolean mDefaultFocusHighlightEnabled = true;` (View.java:4908),
     *  setter :12829, getter :12844 (which is FINAL on AOSP). It defaulted to
     *  false here and the setter was a no-op, so both the default and any
     *  explicit true read wrong (fwprobe
     *  VW.flag.defaultFocusHighlightEnabled.{default,true}). */
    private boolean mDefaultFocusHighlightEnabled = true;
    public void setDefaultFocusHighlightEnabled(boolean enabled) { mDefaultFocusHighlightEnabled = enabled; }
    public boolean getDefaultFocusHighlightEnabled() { return mDefaultFocusHighlightEnabled; }

    // v1.0.98: more API surface used by Compose / SwipeRefreshLayout
    // / RecyclerView during init. Other isXxx/getXxx defined earlier
    // in the file are not duplicated here.
    /** AOSP View.isInTouchMode (android14): mAttachInfo.mInTouchMode for an
     *  attached view, config_defaultInTouchMode (true on a touch device) for a
     *  detached one. It was a constant true; see ViewRootImpl's TOUCH MODE. */
    public boolean isInTouchMode() {
        return !macapkAttached || ViewRootImpl.macapkInTouchMode();
    }
    /** AOSP View.hasWindowFocus (android11-release View.java:14620-14622):
     *      return mAttachInfo != null && mAttachInfo.mHasWindowFocus;
     *  A DETACHED view has no attach info and therefore no window focus --
     *  which is why a freshly constructed widget's getDrawableState() carries
     *  no state_window_focused on real Android (fwprobe DRAWSTATE golden:
     *  every unattached row lacks it; ours carried it on all 85). Once
     *  attached, Mac.apk's single main window is frontmost-and-key for the
     *  app's lifetime, so macapkAttached alone stands in for the AOSP
     *  conjunction (same stand-in isHardwareAccelerated already uses for
     *  mAttachInfo != null). If a real NSWindow key-status bridge ever lands,
     *  AND it in here rather than replacing the attach gate. */
    public boolean hasWindowFocus() { return macapkAttached; }
    /** AOSP `View.isPaddingRelative()` (android-11.0.0_r48 View.java:24502-24504):
     *      return (mUserPaddingStart != UNDEFINED_PADDING
     *              || mUserPaddingEnd != UNDEFINED_PADDING);
     *  "was this view's padding last set through the RELATIVE API (or the
     *  paddingStart/paddingEnd attributes)". It returned a hardcoded false, so any
     *  widget that branches on it -- AndroidX does, to decide whether to re-apply
     *  padding relatively after an insets update -- took the absolute path.
     *  fwprobe LAY.pad.{relative,relThenRtl,rtlThenRel,absThenRel}: reference
     *  rel=1, ours rel=0. Note the ordering cases: setPadding CLEARS the sentinel
     *  and setPaddingRelative SETS it, so last-call-wins, which is why
     *  relThenAbs (rel=0) and absThenRel (rel=1) disagree. */
    public boolean isPaddingRelative() {
        return (mUserPaddingStart != UNDEFINED_PADDING || mUserPaddingEnd != UNDEFINED_PADDING);
    }
    private boolean fitsSystemWindows = false;
    public boolean getFitsSystemWindows() { return fitsSystemWindows; }
    public void setFitsSystemWindows(boolean fits) { this.fitsSystemWindows = fits; }
    /** AOSP getWindowVisibility():14812 is
     *      `return mAttachInfo != null ? mAttachInfo.mWindowVisibility : GONE;`
     *  so a DETACHED view answers GONE (8), not VISIBLE (fwprobe
     *  VW.det.windowvisibility: 8 vs 0). macapkAttached is this codebase's
     *  mAttachInfo != null stand-in, and this shares the
     *  MACAPK_ISSHOWN_DETACHED=0 bisect gate with isShown() /
     *  getLocationInWindow() because it has the same known weakness: an overlay
     *  root is never attached here, so a popup's children answer GONE until
     *  WindowOverlayStack dispatches attach. */
    public int getWindowVisibility() {
        if (!sIsShownDetachedAosp) return VISIBLE;
        return macapkAttached ? VISIBLE : GONE;
    }
    public void onWindowVisibilityChanged(int visibility) {}
    public void dispatchWindowVisibilityChanged(int visibility) {}
    public void onWindowFocusChanged(boolean hasFocus) {}
    /** AOSP View.dispatchWindowFocusChanged: deliver to this view; ViewGroup
     *  overrides to recurse into children. */
    public void dispatchWindowFocusChanged(boolean hasFocus) { onWindowFocusChanged(hasFocus); }
    // ---- the view's transform matrix (AOSP View.java:16557 / :16571 / :16592) --
    // AOSP delegates all three to the RenderNode, whose matrix hwui composes in
    // RenderProperties::updateMatrix (libs/hwui/RenderProperties.cpp:179-195):
    //
    //     if (!mPrimitiveFields.mPivotExplicitlySet) {
    //         mPrimitiveFields.mPivotX = mPrimitiveFields.mWidth  / 2.0f;
    //         mPrimitiveFields.mPivotY = mPrimitiveFields.mHeight / 2.0f;
    //     }
    //     transform->reset();
    //     if (isZero(getRotationX()) && isZero(getRotationY())) {
    //         transform->setTranslate(getTranslationX(), getTranslationY());
    //         transform->preRotate(getRotation(), getPivotX(), getPivotY());
    //         transform->preScale(getScaleX(), getScaleY(), getPivotX(), getPivotY());
    //     } else { ...SkCamera 3D... }
    //
    // Both accessors returned a FRESH IDENTITY matrix for every view, which is
    // the one answer that is never right for a view that has been translated,
    // scaled or rotated. ViewGroup's hit testing could not use them and composes
    // the same matrix locally from the properties (macapkChildMatrix); that copy
    // pinned the composition ORDER against the golden -- DISP.coord
    // .translateAndScaleHit puts the delivered local x at exactly 30.0, which
    // only translate-after-rotate/scale-about-pivot produces -- so this is
    // deliberately byte-identical to it, and ViewGroup should be routed here.
    //
    // v1.0.939: the rotationX / rotationY branch IS now modelled. It needed an
    // SkCamera 3D projection, which android.graphics.Camera now implements for
    // real (see that file's header -- it was faking 3D with a cos() scale and
    // no perspective term at all). The old note here also said our Matrix is
    // "affine-only", which had stopped being true: Matrix carries all nine
    // values and inverts with perspective.
    //
    // ⚠ PAINT STILL DOES NOT FOLLOW. macapkApplyTransform concats onto a
    // Java2D-backed Canvas, and java.awt.geom.AffineTransform cannot express a
    // perspective row, so a 3D-rotated view is still PAINTED with its 2D part.
    // That asymmetry is deliberate and is the honest state: the ANSWER an app
    // reads from getMatrix() is now Android's, while the pixels are not yet.
    // Hit testing is unaffected either way -- ViewGroup composes its own matrix
    // in macapkChildMatrix and does not route through here -- so nothing that
    // agreed before disagrees now.
    //
    // Cached per view, as AOSP caches in TransformationInfo.mMatrix: the getter
    // hands out the view's own instance and its javadoc says not to modify it.
    private android.graphics.Matrix mMatrix;
    private android.graphics.Matrix mInverseMatrix;
    private android.graphics.Camera mTransformCamera;
    public android.graphics.Matrix getMatrix() {
        if (mMatrix == null) mMatrix = new android.graphics.Matrix();
        final android.graphics.Matrix m = mMatrix;
        m.reset();
        final float px = getPivotX(), py = getPivotY();
        if (rotationX == 0f && rotationY == 0f) {
            m.setTranslate(transX, transY);
            if (rotation != 0f) m.preRotate(rotation, px, py);
            if (scaleX != 1f || scaleY != 1f) m.preScale(scaleX, scaleY, px, py);
            return m;
        }
        // hwui RenderProperties::updateMatrix, 3D branch, mirrored statement for
        // statement -- including that the SCALE goes on the 2D matrix FIRST and
        // the whole 3D transform is postConcat'd over it, and that rotationZ
        // enters the camera NEGATED.
        if (scaleX != 1f || scaleY != 1f) m.preScale(scaleX, scaleY, px, py);
        if (mTransformCamera == null) mTransformCamera = new android.graphics.Camera();
        final android.graphics.Camera cam = mTransformCamera;
        cam.save();
        // setLocation takes INCHES; getCameraDistance() answers pixels, and the
        // negative z puts the eye in front of the plane.
        cam.setLocation(0f, 0f, -getCameraDistance() / macapkDensityDpi());
        cam.rotateX(rotationX);
        cam.rotateY(rotationY);
        cam.rotateZ(-rotation);
        final android.graphics.Matrix m3d = new android.graphics.Matrix();
        cam.getMatrix(m3d);
        cam.restore();
        m3d.preTranslate(-px, -py);
        m3d.postTranslate(px + transX, py + transY);
        m.postConcat(m3d);
        return m;
    }
    /** AOSP View.getInverseMatrix (View.java:16592-16601). A non-invertible
     *  matrix (a zero scale collapses the view to a point or a line) leaves the
     *  output UNTOUCHED on AOSP too, because SkMatrix::invert bails without
     *  writing; we reset first so the caller sees identity rather than a stale
     *  previous inverse. ViewGroup's own hit test additionally treats that case
     *  as "no point can be inside", which is the more useful answer and is why
     *  it does not simply call this. */
    public android.graphics.Matrix getInverseMatrix() {
        if (mInverseMatrix == null) mInverseMatrix = new android.graphics.Matrix();
        final android.graphics.Matrix inv = mInverseMatrix;
        inv.reset();
        getMatrix().invert(inv);
        return inv;
    }
    /** AOSP View.hasIdentityMatrix (View.java:16571-16573). Asked before any
     *  matrix work so the overwhelmingly common untransformed view costs
     *  nothing. */
    public final boolean hasIdentityMatrix() { return getMatrix().isIdentity(); }
    // AOSP View text-DIRECTION contract (android-11.0.0_r48 View.java:2768-2822,
    // setTextDirection:27237, getRawTextDirection:27213, getTextDirection:27288,
    // resolveTextDirection:27299).
    //
    // The constants did not exist here and both accessors were hardcoded 0, which
    // is not even a legal resolved value: TEXT_DIRECTION_INHERIT is a REQUEST, and
    // getTextDirection() answers the RESOLVED direction, never INHERIT. Every
    // caller (TextView's getTextDirectionHeuristic, androidx's
    // TextViewCompat/BidiFormatter wiring) switch()es on the resolved value and
    // fell into its default branch. fwprobe VW.ld.textdir.*: the reference answers
    // 1 (FIRST_STRONG) for every case, ours answered 0 for every case.
    public static final int TEXT_DIRECTION_INHERIT           = 0;
    public static final int TEXT_DIRECTION_FIRST_STRONG      = 1;
    public static final int TEXT_DIRECTION_ANY_RTL           = 2;
    public static final int TEXT_DIRECTION_LTR               = 3;
    public static final int TEXT_DIRECTION_RTL               = 4;
    public static final int TEXT_DIRECTION_LOCALE            = 5;
    public static final int TEXT_DIRECTION_FIRST_STRONG_LTR  = 6;
    public static final int TEXT_DIRECTION_FIRST_STRONG_RTL  = 7;
    /** AOSP TEXT_DIRECTION_RESOLVED_DEFAULT (:2822) = FIRST_STRONG. */
    static final int TEXT_DIRECTION_RESOLVED_DEFAULT = TEXT_DIRECTION_FIRST_STRONG;
    private int mRawTextDirection = TEXT_DIRECTION_INHERIT;
    public int getRawTextDirection() { return mRawTextDirection; }
    public void setTextDirection(int textDirection) {
        if (textDirection == mRawTextDirection) return;
        mRawTextDirection = textDirection;
        requestLayout();
        invalidate();
    }
    /** AOSP getTextDirection():27288 reads the RESOLVED value, and resolution
     *  (resolveTextDirection:27299) is wrapped whole in `if (hasRtlSupport())`
     *  with the else-branch assigning TEXT_DIRECTION_RESOLVED_DEFAULT. So an app
     *  that does not declare android:supportsRtl answers FIRST_STRONG for every
     *  view no matter what it set -- which is what the reference measures, since
     *  the probe manifest omits the attribute. Resolved lazily here rather than
     *  through AOSP's mPrivateFlags2 resolution pass; same answer for every state
     *  observable from outside, and no second bookkeeping pass to keep in sync. */
    public int getTextDirection() {
        if (!hasRtlSupport()) return TEXT_DIRECTION_RESOLVED_DEFAULT;
        int d = mRawTextDirection;
        if (d == TEXT_DIRECTION_INHERIT) {
            // INHERIT takes the PARENT's resolved direction; a root with INHERIT
            // (or a parent whose own resolution is out of range) takes DEFAULT.
            ViewParent p = getParent();
            if (!(p instanceof View)) return TEXT_DIRECTION_RESOLVED_DEFAULT;
            d = ((View) p).getTextDirection();
        }
        switch (d) {
            case TEXT_DIRECTION_FIRST_STRONG:
            case TEXT_DIRECTION_ANY_RTL:
            case TEXT_DIRECTION_LTR:
            case TEXT_DIRECTION_RTL:
            case TEXT_DIRECTION_LOCALE:
            case TEXT_DIRECTION_FIRST_STRONG_LTR:
            case TEXT_DIRECTION_FIRST_STRONG_RTL:
                return d;
            default:
                return TEXT_DIRECTION_RESOLVED_DEFAULT;
        }
    }
    /** AOSP isTextDirectionResolved():27428. Resolution is lazy here, so the
     *  answer is "could we resolve", which for a view whose direction does not
     *  depend on an unresolved parent is always yes. */
    public boolean isTextDirectionResolved() { return true; }
    public java.util.Locale getTextLocale() { return java.util.Locale.getDefault(); }

    // AOSP View text-alignment contract (android13-release View.java:2933-2983,
    // fetched 2026-08-01):
    //   TEXT_ALIGNMENT_INHERIT = 0; GRAVITY = 1; TEXT_START = 2; TEXT_END = 3;
    //   CENTER = 4; VIEW_START = 5; VIEW_END = 6; default = GRAVITY.
    // setTextAlignment stores the raw value then requestLayout+invalidate
    // (View.java:28499-28515). Resolution: INHERIT resolves up the parent
    // chain; a root with INHERIT resolves to the default (GRAVITY).
    // Previously both methods were 0-returning no-ops and the inflater never
    // routed android:textAlignment, so e.g. AntennaPod's welcome
    // <TextView android:layout_width="match_parent"
    //           android:textAlignment="center"/> drew flush-left while its
    // sibling ImageView centered (campaign capture 2026-08-01).
    public static final int TEXT_ALIGNMENT_INHERIT    = 0;
    public static final int TEXT_ALIGNMENT_GRAVITY    = 1;
    public static final int TEXT_ALIGNMENT_TEXT_START = 2;
    public static final int TEXT_ALIGNMENT_TEXT_END   = 3;
    public static final int TEXT_ALIGNMENT_CENTER     = 4;
    public static final int TEXT_ALIGNMENT_VIEW_START = 5;
    public static final int TEXT_ALIGNMENT_VIEW_END   = 6;
    /** AOSP TEXT_ALIGNMENT_RESOLVED_DEFAULT (:2953) = TEXT_ALIGNMENT_GRAVITY. */
    static final int TEXT_ALIGNMENT_RESOLVED_DEFAULT = TEXT_ALIGNMENT_GRAVITY;
    private int macapkRawTextAlignment = TEXT_ALIGNMENT_INHERIT;
    public int getRawTextAlignment() { return macapkRawTextAlignment; }
    /** AOSP getTextAlignment():27543 reads the RESOLVED alignment, and
     *  resolveTextAlignment():27555 wraps the whole switch in
     *  `if (hasRtlSupport())` with the else-branch assigning
     *  TEXT_ALIGNMENT_RESOLVED_DEFAULT. Two corrections over the previous body:
     *    - the RTL-compat gate. An app that does not declare
     *      android:supportsRtl answers GRAVITY for every view, whatever it set;
     *      that is the branch the reference measures (the probe manifest omits
     *      the attribute) and it is also why fwprobe VW.ld.textalign.set.* all
     *      read 1 there while we echoed the raw value back.
     *    - an OUT-OF-RANGE raw value resolves to the DEFAULT, not to itself
     *      (VW.ld.textalign.set.ninetynine).
     *  The INHERIT parent-walk is unchanged in substance, but now asks the
     *  parent for its RESOLVED alignment, as AOSP does, instead of hunting up
     *  the chain for the first non-INHERIT raw value. The Canvas-tier fleet
     *  declares supportsRtl (AntennaPod included), so the textAlignment="center"
     *  routing that motivated the original body still resolves to CENTER. */
    public int getTextAlignment() {
        if (!hasRtlSupport()) return TEXT_ALIGNMENT_RESOLVED_DEFAULT;
        int a = macapkRawTextAlignment;
        if (a == TEXT_ALIGNMENT_INHERIT) {
            ViewParent p = getParent();
            if (!(p instanceof View)) return TEXT_ALIGNMENT_RESOLVED_DEFAULT;
            a = ((View) p).getTextAlignment();
        }
        switch (a) {
            case TEXT_ALIGNMENT_GRAVITY:
            case TEXT_ALIGNMENT_TEXT_START:
            case TEXT_ALIGNMENT_TEXT_END:
            case TEXT_ALIGNMENT_CENTER:
            case TEXT_ALIGNMENT_VIEW_START:
            case TEXT_ALIGNMENT_VIEW_END:
                return a;
            default:
                return TEXT_ALIGNMENT_RESOLVED_DEFAULT;
        }
    }
    /** AOSP isTextAlignmentResolved():27684; see isTextDirectionResolved. */
    public boolean isTextAlignmentResolved() { return true; }
    public void setTextAlignment(int textAlignment) {
        if (textAlignment == macapkRawTextAlignment) return;
        macapkRawTextAlignment = textAlignment;
        requestLayout();
        invalidate();
    }

    // v1.0.98: View.bringToFront / startAnimation / clearAnimation /
    // requestDisallowInterceptTouchEvent — used by SwipeRefreshLayout,
    // RecyclerView, and many androidx widgets during init.
    /** AOSP View.setOnDragListener: ListenerInfo.mOnDragListener, read by
     *  callDragEventHandler. (Was a no-op until MacApkDragSession existed.) */
    public void setOnDragListener(OnDragListener l) { mOnDragListener = l; }
    OnDragListener mOnDragListener;
    /** AOSP ListenerInfo.mOnHoverListener, read by dispatchHoverEvent. Was a
     *  discard. */
    public void setOnHoverListener(OnHoverListener l) { mOnHoverListener = l; }
    public void setOnScrollChangeListener(OnScrollChangeListener l) {}
    public void setOnContextClickListener(OnContextClickListener l) {
        // AOSP stores it in ListenerInfo.mOnContextClickListener and
        // performContextClick() consults it; was a discard here, so the
        // listener could never fire. Storing it is what makes the AOSP
        // performContextClick() added below observable.
        // AOSP View.java:7925: `if (!isContextClickable()) setContextClickable(true);`
        if (!isContextClickable()) setContextClickable(true);
        macapkSetContextClickListener(l);
    }
    public void setOnGenericMotionListener(OnGenericMotionListener listener, Object discriminator) {}
    public void bringToFront() {
        // re-parent to last position so default draw order paints
        // this view on top of siblings.
        ViewParent p = getParent();
        if (p instanceof ViewGroup vg) {
            int idx = vg.indexOfChild(this);
            if (idx >= 0 && idx < vg.getChildCount() - 1) {
                vg.removeView(this);
                vg.addView(this);
            }
        }
    }
    // v1.0.685: getAnimation/setAnimation round-trip through a real field.
    // The animation is not vsync-driven here, but AOSP's contract is that
    // getAnimation returns whatever start/setAnimation last set, and view code
    // (and Transition/LayoutTransition) reads it back to decide whether an
    // animation is in flight; returning null unconditionally broke that check.
    private android.view.animation.Animation mCurrentAnimation;

    /** AOSP View.startAnimation(Animation) (android16-release
     *  core/java/android/view/View.java):
     *
     *      animation.setStartTime(Animation.START_ON_FIRST_FRAME);
     *      setAnimation(animation);
     *      invalidateParentCaches();
     *      invalidate(true);
     *
     *  The invalidate is not decoration: it is what schedules the frame that
     *  first pumps the animation. Without it an app that starts an animation
     *  outside a layout/draw pass gets no frame, so getTransformation is never
     *  called, so onAnimationEnd never fires. */
    public void startAnimation(android.view.animation.Animation animation) {
        if (animation != null) {
            try {
                animation.setStartTime(-1); // Animation.START_ON_FIRST_FRAME
                setAnimation(animation);
            } catch (Throwable ignored) {}
            invalidate();
        } else {
            setAnimation(null);
        }
    }

    /** AOSP View.setAnimation(Animation): stores the animation and RESETS it.
     *  The reset clears mInitialized, which is what makes applyLegacyAnimation
     *  re-run Animation.initialize() against the view's current size -- a
     *  re-used Animation object (the common case: a field on the Activity) would
     *  otherwise keep the pixel deltas it resolved against the OLD size. */
    public void setAnimation(android.view.animation.Animation animation) {
        mCurrentAnimation = animation;
        if (animation != null) {
            try { animation.reset(); } catch (Throwable ignored) {}
        }
    }
    public android.view.animation.Animation getAnimation() { return mCurrentAnimation; }

    // ------------------------------------------------------------------
    // Legacy (tween) view animation: the per-frame pump.
    //
    // Nothing in this tree called Animation.getTransformation until v1.0.1036,
    // so the whole android.view.animation package was inert: a faithful AOSP
    // state machine that no frame ever advanced. The visible half of that is
    // cosmetic (a fade does not fade), but the load-bearing half is a HANG
    // class -- an app that swaps a fragment, re-enables a button or releases a
    // latch in Animation.AnimationListener.onAnimationEnd never gets the
    // callback, because the animation never starts and therefore never ends.
    //
    // COST WHEN NOTHING IS ANIMATING: one field read. Every caller gates on
    // `getAnimation() != null` BEFORE touching anything else -- no
    // Transformation is allocated (ViewGroup.getChildTransformation is lazy and
    // is not reached), no clock is read, no matrix is built. That matters
    // because this sits in the per-child draw loop of every ViewGroup in every
    // app; see ViewGroup.dispatchDraw.
    //
    // MACAPK_LEGACY_ANIM=0 disables the pump entirely (a labelled bisect gate
    // per CLAUDE.md, not a supported mode): with it off, dispatchDraw's
    // per-child block is byte-for-byte what it was before this landed.
    // ------------------------------------------------------------------
    static final boolean MACAPK_LEGACY_ANIM = !"0".equals(System.getenv("MACAPK_LEGACY_ANIM"));
    /** MACAPK_TRACE_LEGACY_ANIM=1: log each legacy (tween) animation's start and
     *  end, so "a view animation never finished" is visible in a run log. */
    static final boolean TRACE_LEGACY_ANIM = "1".equals(System.getenv("MACAPK_TRACE_LEGACY_ANIM"));

    /** AOSP's PFLAG_ANIMATION_STARTED (View.java:2775). Set by onAnimationStart,
     *  cleared by onAnimationEnd, and read by ViewGroup.finishAnimatingView to
     *  guarantee the protected onAnimationEnd() hook fires exactly once. */
    boolean mAnimationStarted;

    /** AOSP View.applyLegacyAnimation(ViewGroup, long, Animation, boolean)
     *  (android16-release core/java/android/view/View.java:24618-24676), called
     *  from View.draw(Canvas, ViewGroup, long):24763.
     *
     *      final boolean initialized = a.isInitialized();
     *      if (!initialized) {
     *          a.initialize(mRight - mLeft, mBottom - mTop, parent.getWidth(), parent.getHeight());
     *          a.initializeInvalidateRegion(0, 0, mRight - mLeft, mBottom - mTop);
     *          if (mAttachInfo != null) a.setListenerHandler(mAttachInfo.mHandler);
     *          onAnimationStart();
     *      }
     *      final Transformation t = parent.getChildTransformation();
     *      boolean more = a.getTransformation(drawingTime, t, 1f);
     *      ... if (more) parent.invalidate(<the animated region>);
     *      return more;
     *
     *  NOT MIRRORED, and why:
     *   - initializeInvalidateRegion / getInvalidateRegion. AOSP uses them to
     *     invalidate only the rectangle the animation actually touches. Our
     *     View.invalidate(int,int,int,int) forwards to the whole-window
     *     invalidate() (View.java:4461), so computing the region would produce a
     *     number nobody reads. We ask the parent for a frame instead, which is
     *     the same request with a coarser rect.
     *   - setListenerHandler. AOSP posts listener callbacks to the view's
     *     handler; Animation.fireAnimation* calls them directly on the thread
     *     that drove the frame, which here IS the UI thread.
     *   - scalingRequired / mApplicationScale. Compatibility-mode scaling for
     *     pre-density apps; we have no such mode, so the parameter is accepted
     *     for signature parity and the scaled branch is unreachable.
     *
     *  ADDED vs AOSP: t.clear() before the fill. AOSP clears the shared
     *  Transformation lazily, at the top of the NEXT child's draw, via
     *  ViewGroup.FLAG_CLEAR_TRANSFORMATION (View.java:24753-24756). Clearing it
     *  immediately before filling it is the same observable answer with one less
     *  cross-frame flag, and it matters: applyTransformation subclasses set only
     *  their OWN component (AlphaAnimation touches alpha, TranslateAnimation
     *  touches the matrix), so a stale value from the previous child would
     *  otherwise leak across siblings. */
    boolean applyLegacyAnimation(ViewGroup parent, long drawingTime,
            android.view.animation.Animation a, boolean scalingRequired) {
        if (!a.isInitialized()) {
            a.initialize(getRight() - getLeft(), getBottom() - getTop(),
                    parent.getWidth(), parent.getHeight());
            if (TRACE_LEGACY_ANIM) {
                com.macapk.loader.NativeLog.i("Mac.apk.LegacyAnim", "start " + a.getClass().getSimpleName()
                    + " dur=" + a.getDuration() + " on " + getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(this)) + " t=" + drawingTime);
            }
            onAnimationStart();
        }
        final android.view.animation.Transformation t = parent.getChildTransformation();
        t.clear();
        final boolean more = a.getTransformation(drawingTime, t, 1f);
        if (more) {
            // AOSP: parent.mPrivateFlags |= PFLAG_DRAW_ANIMATION;
            //       parent.invalidate(left, top, right, bottom);
            parent.invalidate();
        }
        return more;
    }

    // API 21+: shared-element transition name. Material widgets read
    // it during their <init> chains. v1.0.95.
    private String transitionName;
    public void setTransitionName(String name) { this.transitionName = name; }
    public String getTransitionName() { return transitionName; }

    // API 18+: ViewOverlay. Material BadgeDrawable attaches itself
    // to the host's overlay layer. We back it with a per-View lazy
    // ViewOverlay instance.
    // v1.0.97: overlay creation only (no rendering).
    // v1.0.101: overlay drawables actually render -- View.draw invokes
    //   overlay.macapkDraw after onDraw + dispatchDraw so the badge
    //   sits on TOP of the host view's content (Material's contract).
    private android.view.ViewOverlay overlay;
    public android.view.ViewOverlay getOverlay() {
        if (overlay == null) overlay = new android.view.ViewOverlay(this);
        return overlay;
    }
    /** Returns the overlay only if it was created. Avoids triggering
     *  allocation during the per-frame draw pass. */
    public android.view.ViewOverlay macapkPeekOverlay() { return overlay; }

    // (setActivated / setSelected already defined elsewhere)

    // API 24+: setPointerIcon, getPointerIcon. macOS NSCursor is the
    // analogue, but we don't wire it through; no-op suffices.
    // androidx.core.view.ViewCompat$Api24Impl calls
    // View.setPointerIcon(PointerIcon) via reflectionless direct
    // invokevirtual; the JVM resolves by exact signature so we need
    // the PointerIcon-typed overload, not just Object. v1.0.95.
    public void setPointerIcon(android.view.PointerIcon icon) {}
    public android.view.PointerIcon getPointerIcon() { return null; }
    public android.view.PointerIcon onResolvePointerIcon(android.view.MotionEvent event, int pointerIndex) { return null; }

    // API 26+: setTooltipText / getTooltipText. Called via
    // androidx.appcompat.widget.TooltipCompat$Api26Impl during
    // NavigationBarItemView.setTitle. Hover-tooltip surface is a
    // no-op for us; storing the text lets a getTooltipText() read
    // round-trip cleanly. v1.0.95.
    private CharSequence tooltipText;
    public void setTooltipText(CharSequence text) { this.tooltipText = text; }
    public CharSequence getTooltipText() { return tooltipText; }

    // API 11+: View.mergeDrawableStates is a static helper that ORs
    // additionalState ints into baseState. NavigationBarItemView
    // calls it during onCreateDrawableState. AOSP impl: replace
    // zero entries in baseState with values from additionalState.
    // Refs: frameworks/base View.java:mergeDrawableStates.
    // v1.0.95.
    public static int[] mergeDrawableStates(int[] baseState, int[] additionalState) {
        if (additionalState == null || additionalState.length == 0) return baseState;
        // AOSP semantics: find consecutive zero slots at the tail of
        // baseState and write additionalState's non-zero values
        // there, in order. Callers reserve trailing zero slots when
        // building the base array (e.g. View.STATE_SET_BUFFER_SIZE).
        if (baseState == null) return additionalState;
        int N = baseState.length;
        int i = N - 1;
        while (i >= 0 && baseState[i] == 0) i--;
        // i is now index of last non-zero (or -1 if all zero).
        int writeAt = i + 1;
        for (int j = 0; j < additionalState.length && writeAt < N; j++) {
            if (additionalState[j] != 0) baseState[writeAt++] = additionalState[j];
        }
        return baseState;
    }

    // API 23+: setForegroundTintList / Mode.
    public void setForegroundTintList(android.content.res.ColorStateList tint) {}
    public android.content.res.ColorStateList getForegroundTintList() { return null; }
    public void setForegroundTintMode(android.graphics.PorterDuff.Mode mode) {}
    public android.graphics.PorterDuff.Mode getForegroundTintMode() { return null; }

    // API 21+: setBackgroundTintList / Mode. AOSP applies the tint as
    // a PorterDuff SRC_IN over the background drawable -- for the
    // solid-color backgrounds we paint, the visible result IS the
    // tint color, so draw() prefers the tint when both are present
    // (Calculator's keypad buttons: white shape background tinted to
    // the M3 surface-container tokens).
    protected Integer macapkBackgroundTint;
    // The FULL tint list, AOSP's View.TintInfo.mTintList. macapkBackgroundTint
    // stays the CURRENT-STATE resolved int the flat backgroundColor draw branch
    // consumes; drawableStateChanged re-resolves it so a stateful tint follows
    // checked / pressed / disabled instead of freezing on the default.
    protected android.content.res.ColorStateList macapkBackgroundTintList;
    public void setBackgroundTintList(android.content.res.ColorStateList tint) {
        this.macapkBackgroundTintList = tint;
        // getColorForState(state, default) rather than a bare getDefaultColor():
        // AOSP resolves the tint inside the drawable against the view's CURRENT
        // state, and a view already checked or disabled when the tint arrives has
        // to resolve that row immediately rather than one state change later.
        this.macapkBackgroundTint = tint != null
            ? tint.getColorForState(getDrawableState(), tint.getDefaultColor())
            : null;
        // DIAGNOSTIC (MACAPK_TRACE_BGTINT=1): who sets a background tint, and to
        // what. opencalculator's digit buttons end up with an OPAQUE BLACK tint
        // that overrides their correct #efefef <solid>, and the inflater's only
        // call site null-guards -- so the caller is elsewhere.
        if ("1".equals(ENV_MACAPK_TRACE_BGTINT)) {
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 2; i < Math.min(st.length, 8); i++) sb.append("\n    at ").append(st[i]);
            android.util.Log.i("Mac.apk.BgTint", "setBackgroundTintList("
                + (tint == null ? "null" : "0x" + Integer.toHexString(tint.getDefaultColor()))
                + ") on " + getClass().getName() + sb);
        }
    }
    /** Inflation-time android:backgroundTint, written STRAIGHT INTO the View's
     *  tint fields the way AOSP's View constructor does it:
     *      case R.styleable.View_backgroundTint:
     *          // This will get applied later during setBackground().
     *          mBackgroundTint.mTintList = a.getColorStateList(...);
     *          mBackgroundTint.mHasTintList = true;
     *  (View.java:5858-5866, android11-release). The constructor NEVER
     *  dispatches through the virtual setter, so a subclass override of
     *  setBackgroundTintList cannot intercept an XML-declared tint on a
     *  device -- and it must not intercept ours. The measured victim of the
     *  virtual dispatch: MaterialButton overrides setBackgroundTintList ->
     *  setSupportBackgroundTintList and forwards into its MaterialButtonHelper
     *  without ever reaching these fields (verified in opencalculator's own
     *  R8 bytecode), so under the viewInflaterClass flip every
     *  android:backgroundTint="?button_*_color" on its 10 tinted keypad keys
     *  vanished and the keys painted their untinted ?button_color solid --
     *  keypad #b4d2e4 183,460 px -> 0 (the v1.0.897/v1.0.910 flip blocker).
     *  AppCompatButton does NOT override the setter, which is why the same
     *  keys painted correctly without the flip.
     *  LayoutInflater.applyAttribute("backgroundTint") is the caller; runtime
     *  callers keep the virtual setBackgroundTintList above, matching AOSP. */
    public final void macapkSetInflatedBackgroundTint(
            android.content.res.ColorStateList tint) {
        this.macapkBackgroundTintList = tint;
        this.macapkBackgroundTint = tint != null
            ? tint.getColorForState(getDrawableState(), tint.getDefaultColor())
            : null;
    }
    /** AOSP returns `mBackgroundTint != null ? mBackgroundTint.mTintList : null`.
     *  A hardcoded null here made every AppCompat/Material round-trip that reads
     *  its own tint back conclude there was none. */
    public android.content.res.ColorStateList getBackgroundTintList() {
        return macapkBackgroundTintList;
    }
    public void setBackgroundTintMode(android.graphics.PorterDuff.Mode mode) {}
    public android.graphics.PorterDuff.Mode getBackgroundTintMode() { return null; }

    // (setElevation / getElevation already defined elsewhere)
    // AOSP signature takes android.animation.StateListAnimator -- the APK's
    // invokevirtual carries that exact descriptor, so an (Object) stub would
    // NoSuchMethodError. We don't run the animator; it's stored only so
    // get/set round-trip. (AppBarLayout passes the result of
    // AnimatorInflater.loadStateListAnimator here.)
    private android.animation.StateListAnimator stateListAnimator;
    public void setStateListAnimator(android.animation.StateListAnimator animator) {
        if (this.stateListAnimator == animator) return;
        this.stateListAnimator = animator;
        // M23: drive the animator off this view's drawable state (flag-gated).
        if (animator != null) {
            animator.setTarget(this);
            if (android.os.Looper.MACAPK_REAL_LOOPER) animator.setState(getDrawableState());
        }
    }
    public android.animation.StateListAnimator getStateListAnimator() {
        return stateListAnimator;
    }

    // API 30+: setForceDarkAllowed.
    public void setForceDarkAllowed(boolean allow) {}
    public boolean isForceDarkAllowed() { return false; }

    // Layout-change listener (API 11+). Material widgets, androidx (Kotlin
    // doOnLayout / doOnNextLayout, SearchView, CarouselLayoutManager,
    // BottomAppBar.Behavior, TabLayout), media3 PlayerControlView, CameraX
    // PreviewView and plenty of app code register these; a constant-pool scan
    // of the converted fleet finds callers in 42 of 60 apps. Until
    // MACAPK_LAYOUT_CHANGE_LISTENERS both methods were empty and layout() never
    // dispatched, so every such callback was dead: anuto's HeaderFragment never
    // hid its "Build" button under the tower icons, and a doOnNextLayout action
    // never ran. See layout() for the dispatch and
    // docs/viewgroup-drawchild-order-and-layout-listeners_2026-09-26.md.
    public interface OnLayoutChangeListener {
        void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom);
    }
    /** MACAPK_LAYOUT_CHANGE_LISTENERS (default ON). =0 restores the pre-change
     *  inert add/remove: the list is then never created, so layout() has nothing
     *  to dispatch and runs exactly as before. */
    static final boolean MACAPK_LAYOUT_CHANGE_LISTENERS =
        !"0".equals(System.getenv("MACAPK_LAYOUT_CHANGE_LISTENERS"));
    /** AOSP keeps this in ListenerInfo.mOnLayoutChangeListeners; a plain field
     *  here, null until the first add (most views never get one). */
    private java.util.ArrayList<OnLayoutChangeListener> mOnLayoutChangeListeners;
    /** AOSP android14 View.java:7371-7379 -- lazily-created list, add-if-absent:
     *      if (!li.mOnLayoutChangeListeners.contains(listener)) {
     *          li.mOnLayoutChangeListeners.add(listener);
     *      } */
    public void addOnLayoutChangeListener(OnLayoutChangeListener listener) {
        if (!MACAPK_LAYOUT_CHANGE_LISTENERS) return;
        if (mOnLayoutChangeListeners == null) {
            mOnLayoutChangeListeners = new java.util.ArrayList<>();
        }
        if (!mOnLayoutChangeListeners.contains(listener)) {
            mOnLayoutChangeListeners.add(listener);
        }
    }
    /** AOSP android14 View.java:7386-7392. */
    public void removeOnLayoutChangeListener(OnLayoutChangeListener listener) {
        if (mOnLayoutChangeListeners == null) return;
        mOnLayoutChangeListeners.remove(listener);
    }

    // (OnAttachStateChangeListener is defined further down -- single
    // definition reused.)

    // API 29+ -- saves attributes (and which set was inflated) for later
    // accessibility introspection. macOS has no equivalent introspection
    // pump; the call is a no-op. RecyclerView's <init> invokes it via the
    // 6-arg form with the styled-attr index range.
    // AOSP declares this `protected`, but androidx ViewCompat$Api29Impl
    // (a static inner class in androidx.core.view) calls it directly,
    // not via reflection. ART on real Android doesn't enforce access
    // checks across the ViewCompat -> View boundary in this case (the
    // class is in the boot classloader and ART relaxes some access);
    // HotSpot does. Promote to `public` so androidx's ViewCompat can
    // bind. The method is a no-op either way.
    public void saveAttributeDataForStyleable(android.content.Context context,
                                                int[] styleable,
                                                android.util.AttributeSet attrs,
                                                android.content.res.TypedArray t,
                                                int defStyleAttr,
                                                int defStyleRes) {}

    // -------- v1.0.92 batch: View method surface for androidx widgets --------
    //
    // All 31 methods below were observed missing while running F-Droid's
    // MainActivity (which inflates a RecyclerView + Material widgets). The
    // class-load NSME pattern requires every signature in the bytecode of
    // every loaded class to resolve at link time -- absence of any one
    // method on android.view.View prevents the calling class from loading
    // and forces LayoutInflater into its placeholder fallback. The bodies
    // are stubs because the desktop window has no overscroll / accessibility
    // / clipboard / rotation pump; we just need the signatures present so
    // the caller's bytecode verifies. Each was discovered by disassembling
    // the F-Droid classes.jar with `javap -c` and grepping for
    // `Method android/view/View.<x>`.

    public static int combineMeasuredStates(int curState, int newState) {
        return curState | newState;
    }
    /** AOSP `View.resolveSizeAndState` (aosp-mirror android-11.0.0_r48
     *  core/java/android/view/View.java:25625-25645).
     *
     *  The AT_MOST branch is NOT `min(size, specSize)`. When the view wanted MORE
     *  than the spec allows, AOSP returns the spec size OR-ed with
     *  MEASURED_STATE_TOO_SMALL:
     *      case MeasureSpec.AT_MOST:
     *          if (specSize < size) { result = specSize | MEASURED_STATE_TOO_SMALL; }
     *          else                 { result = size; }
     *  That bit is the ONLY channel by which a parent learns a child was squeezed
     *  (every ViewGroup ORs its children's getMeasuredState() back into its own
     *  answer via combineMeasuredStates, then passes it here as
     *  childMeasuredState). Dropping it made getMeasuredState() a constant 0 for
     *  the whole tree, so no AndroidX container could ever react to "too small".
     *  Measured by fwprobe LAY.resolveSizeAndState.{200,1000}.am{0,100}: reference
     *  0x01000064, ours 0x00000064.
     *
     *  The NUMERIC half is unchanged -- min() and the AOSP branch agree on every
     *  input including negatives (specSize 0 vs size -10 gives -10 both ways),
     *  which is why the m10.* rows already matched.
     *
     *  COUPLED CHANGE: because this now returns a value with a state bit set, and
     *  callers feed that value straight into setMeasuredDimension, getMeasuredWidth
     *  / getMeasuredHeight MUST mask with MEASURED_SIZE_MASK (they do, see the
     *  layout-pass section) -- otherwise a squeezed view reports a measured width
     *  of ~16.7 million. The two changes are atomic; never land one without the
     *  other. */
    public static int resolveSizeAndState(int size, int measureSpec, int childMeasuredState) {
        final int specMode = MeasureSpec.getMode(measureSpec);
        final int specSize = MeasureSpec.getSize(measureSpec);
        final int result;
        switch (specMode) {
            case MeasureSpec.AT_MOST:
                if (specSize < size) {
                    result = specSize | MEASURED_STATE_TOO_SMALL;
                } else {
                    result = size;
                }
                break;
            case MeasureSpec.EXACTLY:
                result = specSize;
                break;
            case MeasureSpec.UNSPECIFIED:
            default:
                result = size;
        }
        return result | (childMeasuredState & MEASURED_STATE_MASK);
    }

    public int  getBaseline()           { return -1; }
    public boolean getClipBounds(android.graphics.Rect outRect) {
        if (outRect != null) outRect.set(0, 0, getWidth(), getHeight());
        return true;
    }
    public android.graphics.Rect getClipBounds() {
        return new android.graphics.Rect(0, 0, getWidth(), getHeight());
    }
    public void setClipBounds(android.graphics.Rect clipBounds) {}
    public void setClipToOutline(boolean clipToOutline) { mClipToOutline = clipToOutline; }
    public boolean getClipToOutline() { return mClipToOutline; }
    private boolean mClipToOutline = false;

    /** v1.0.124: AOSP `View.getDrawingTime()` -- timestamp passed to
     *  draw(canvas, parent, drawingTime). Used by ViewGroup/View draw
     *  paths to track animation timing. We return System.nanoTime / 1M. */
    public long getDrawingTime() { return System.nanoTime() / 1_000_000; }

    public static final int LAYOUT_DIRECTION_LTR        = 0;
    public static final int LAYOUT_DIRECTION_RTL        = 1;
    public static final int LAYOUT_DIRECTION_INHERIT    = 2;
    public static final int LAYOUT_DIRECTION_LOCALE     = 3;
    /** AOSP keeps the RAW attribute (which may be INHERIT or LOCALE) and the
     *  RESOLVED direction (always LTR or RTL) in separate bit fields:
     *  `LAYOUT_DIRECTION_DEFAULT = LAYOUT_DIRECTION_INHERIT` (View.java:3035),
     *  getRawLayoutDirection() reads the attribute, getLayoutDirection() reads
     *  the resolution. We used to keep ONE field defaulting to LTR, so the
     *  INHERIT branch of resolveLayoutDirection could never run for a view that
     *  had not been told INHERIT explicitly -- an RTL Configuration therefore
     *  never reached a single View and every RTL-aware consumer downstream
     *  (MarginLayoutParams.resolveLayoutDirection, Gravity.getAbsoluteGravity)
     *  was fed LTR. Keeping the two apart also means getLayoutDirection() never
     *  hands a caller the raw value 2/3, which those consumers cannot interpret. */
    private int mRawLayoutDirection = LAYOUT_DIRECTION_INHERIT;
    private int mResolvedLayoutDirection = LAYOUT_DIRECTION_LTR;
    /** AOSP getLayoutDirection(): the RESOLVED direction, LTR or RTL only. */
    public int  getLayoutDirection() { return mResolvedLayoutDirection; }
    /** AOSP getRawLayoutDirection(): the attribute as set, incl. INHERIT/LOCALE. */
    public int  getRawLayoutDirection() { return mRawLayoutDirection; }
    /** AOSP isLayoutRtl(). */
    public boolean isLayoutRtl() { return mResolvedLayoutDirection == LAYOUT_DIRECTION_RTL; }
    /** AOSP setLayoutDirection: store the raw value, drop the old resolution,
     *  re-resolve, then requestLayout + invalidate (every RTL-derived property
     *  depends on the direction). */
    public void setLayoutDirection(int direction) {
        if (mRawLayoutDirection == direction) return;
        mRawLayoutDirection = direction;
        resetRtlProperties();
        resolveRtlPropertiesIfNeeded();
        requestLayout();
        invalidate();
    }
    public void onRtlPropertiesChanged(int layoutDirection) {}

    // v1.0.123: AOSP RTL-resolution cascade. Fired during measure() so
    // every child's LayoutParams.resolveLayoutDirection runs BEFORE its
    // onMeasure -- which is when ConstraintLayout's
    // applyConstraintsFromLayoutParams reads the resolved fields
    // (mResolvedLeftToLeft / mResolvedLeftToRight / etc.) to wire the
    // constraint anchors.
    //
    // Without this cascade, locale-aware attrs like
    // `app:layout_constraintStart_toEndOf="@id/icon"` parse into
    // LayoutParams.startToEnd at inflation time but the matching
    // mResolvedLeftToRight stays UNSET; the solver then sees no
    // constraint on the widget's left edge and minimizes it to 0 (per
    // androidx ConstraintLayout 2.2's solverMeasure ->
    // solveLinearSystem -> updateFromSolver chain). The visible
    // symptom is that name + summary TextViews render at x=0 inside
    // each card, overlapping the icon at x=0..96.
    //
    // Per cs.android.com/.../view/View.java the chain is:
    //   measure() -> resolveRtlPropertiesIfNeeded()
    //                  -> resolveLayoutDirection()
    //                  -> resolveLayoutParams()
    //                       -> mLayoutParams.resolveLayoutDirection(getLayoutDirection())
    private boolean rtlPropertiesResolved = false;
    public boolean resolveRtlPropertiesIfNeeded() {
        if (rtlPropertiesResolved) return false;
        rtlPropertiesResolved = true;
        resolveLayoutDirection();
        resolveLayoutParams();
        // AOSP resolveRtlPropertiesIfNeeded also resolves PADDING (and drawables /
        // text direction / text alignment) after the direction, because relative
        // padding cannot be mapped onto left/right until the direction is known.
        // Inert in RTL compatibility mode -- resolvePadding then just re-applies
        // the padding already in place -- but required for the direction to reach
        // paddingStart/paddingEnd once android:supportsRtl is parsed.
        try { resolvePadding(); } catch (Throwable ignored) {}
        // Notify subclasses that wish to react to direction change
        // (TextView gravity flip etc.). Safe to invoke even if
        // direction is unchanged from the default LTR.
        try { onRtlPropertiesChanged(mResolvedLayoutDirection); } catch (Throwable ignored) {}
        return true;
    }
    /** AOSP `View.resolveLayoutDirection()` (View.java:22679-22716). Turns the
     *  raw attribute into the resolved LTR/RTL answer:
     *    INHERIT -> the parent's RESOLVED direction; at the root (no ViewParent,
     *               the position AOSP's ViewRootImpl occupies) the app's
     *               Configuration, which is where a device's RTL locale enters
     *               the view tree;
     *    LOCALE  -> the default Locale's writing direction, matching AOSP's
     *               TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
     *    LTR/RTL -> itself. */
    public boolean resolveLayoutDirection() {
        // AOSP wraps the ENTIRE switch in `if (hasRtlSupport())`
        // (android-11.0.0_r48 View.java:20107-20146):
        //     public boolean resolveLayoutDirection() {
        //         mPrivateFlags2 &= ~PFLAG2_LAYOUT_DIRECTION_RESOLVED_MASK;
        //         if (hasRtlSupport()) { switch (...) { ... } }
        //         mPrivateFlags2 |= PFLAG2_LAYOUT_DIRECTION_RESOLVED;
        //         return true;
        //     }
        // so an app whose manifest does NOT carry android:supportsRtl="true"
        // resolves LTR at every node, FOREVER -- even after an explicit
        // setLayoutDirection(LAYOUT_DIRECTION_RTL), even in an RTL locale. We
        // resolved RTL unconditionally, which is why getLayoutDirection(),
        // isLayoutRtl(), getPaddingStart/End, MarginLayoutParams start/end margins
        // and Gravity.getAbsoluteGravity all mirrored for apps real Android leaves
        // alone.
        //
        // How this was pinned, and it is not a guess: the fwprobe golden captured
        // on the API-30 AVD has EVERY LAY.rtl.<scene>.rtl.* row byte-identical to
        // its .ltr.* twin (linearh / linearv / frame / rel / margin, 20 pairs) on a
        // probe whose manifest omits supportsRtl -- real Android mirrored NOTHING.
        // Our rows differed, and LAY.dir.rootRtl answered dir=1 where the reference
        // answered dir=0.
        //
        // The tempting WRONG diagnosis, ruled out here so nobody re-derives it:
        // "AOSP does not resolve layout direction on a DETACHED view". It does.
        // setLayoutDirection (View.java:11987-12000) calls
        // resolveRtlPropertiesIfNeeded() unconditionally, measure()
        // (View.java:25459) calls it again inside `if (forceLayout || needsLayout)`,
        // and the explicit LAYOUT_DIRECTION_RTL arm of the switch sets
        // PFLAG2_LAYOUT_DIRECTION_RESOLVED_RTL with no attach test -- only the
        // INHERIT arm consults canResolveLayoutDirection(), and that wants a
        // PARENT, which the probe's mid/leaf views have. Attachment is nowhere in
        // the chain. Gating resolution on attachment instead would answer these
        // rows for the wrong reason and break every genuinely RTL app.
        if (!hasRtlSupport()) {
            mResolvedLayoutDirection = LAYOUT_DIRECTION_LTR;
            return true;
        }
        switch (mRawLayoutDirection) {
            case LAYOUT_DIRECTION_RTL:
                mResolvedLayoutDirection = LAYOUT_DIRECTION_RTL;
                break;
            case LAYOUT_DIRECTION_LOCALE:
                mResolvedLayoutDirection = layoutDirectionFromLocale(java.util.Locale.getDefault());
                break;
            case LAYOUT_DIRECTION_INHERIT:
                if (parent instanceof View vp) {
                    mResolvedLayoutDirection = vp.getLayoutDirection();
                } else if (parent != null) {
                    mResolvedLayoutDirection = parent.getLayoutDirection();
                } else {
                    mResolvedLayoutDirection = configuredLayoutDirection();
                }
                break;
            default:
                mResolvedLayoutDirection = LAYOUT_DIRECTION_LTR;
                break;
        }
        return true;
    }
    /** AOSP `View.hasRtlSupport()` (android-11.0.0_r48 View.java:20061-20063):
     *      return mContext.getApplicationInfo().hasRtlSupport();
     *  and ApplicationInfo.hasRtlSupport() is
     *      return (flags & FLAG_SUPPORTS_RTL) == FLAG_SUPPORTS_RTL;
     *  i.e. purely "did the <application> tag say android:supportsRtl=true".
     *
     *  This is NOT "force LTR": an app that DOES declare android:supportsRtl
     *  keeps resolving exactly as before, including an explicit
     *  setLayoutDirection(RTL) on a detached view -- AOSP's own setLayoutDirection
     *  (View.java:11987-12000) calls resolveRtlPropertiesIfNeeded() with no attach
     *  gate at all, and measure() (View.java:25459) calls it again, so resolution
     *  on a detached tree IS AOSP behaviour. supportsRtl is the only thing that
     *  suppresses it, which is why the probe (whose manifest omits the attribute,
     *  debug/fwprobe/AndroidManifest.xml) sees no mirroring on the reference.
     *
     *  The flag itself is supplied by the PackageManager layer -- ApplicationInfo
     *  .FLAG_SUPPORTS_RTL, set from `<application android:supportsRtl>` in
     *  MacApkPackageManager.applicationFlags(). This method is the ONLY place the
     *  view system asks the question, so that stays a single wire. */
    private boolean hasRtlSupport() {
        try {
            android.content.pm.ApplicationInfo ai =
                (context != null) ? context.getApplicationInfo() : null;
            return ai != null
                && (ai.flags & android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
    /** AOSP `View.isRtlCompatibilityMode()` (View.java:20069-20072):
     *      final int targetSdkVersion = getContext().getApplicationInfo().targetSdkVersion;
     *      return targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1 || !hasRtlSupport();
     *  Gates the start/end -> left/right swap in resolvePadding: in compatibility
     *  mode AOSP leaves the absolute padding exactly as it was set. */
    private boolean isRtlCompatibilityMode() {
        int targetSdk = 0;
        try {
            android.content.pm.ApplicationInfo ai =
                (context != null) ? context.getApplicationInfo() : null;
            if (ai != null) targetSdk = ai.targetSdkVersion;
        } catch (Throwable ignored) {}
        return targetSdk < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 || !hasRtlSupport();
    }
    /** The app Configuration's layout direction -- AOSP's root-of-tree source
     *  (ViewRootImpl seeds its direction from the Configuration). Falls back to
     *  LTR when no Context/Resources is reachable. */
    private int configuredLayoutDirection() {
        try {
            if (context != null) {
                android.content.res.Resources res = context.getResources();
                if (res != null && res.getConfiguration() != null) {
                    return res.getConfiguration().getLayoutDirection() == LAYOUT_DIRECTION_RTL
                        ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR;
                }
            }
        } catch (Throwable ignored) {}
        return LAYOUT_DIRECTION_LTR;
    }
    /** AOSP resolves LAYOUT_DIRECTION_LOCALE via
     *  TextUtils.getLayoutDirectionFromLocale (ICU ULocale.isRightToLeft).
     *  android.text.TextUtils has no such method in this tree, so use the same
     *  JDK query android.content.res.Configuration.setLayoutDirection(Locale)
     *  already uses, keeping the two answers consistent. */
    private static int layoutDirectionFromLocale(java.util.Locale loc) {
        try {
            if (loc != null && !java.awt.ComponentOrientation.getOrientation(loc).isLeftToRight()) {
                return LAYOUT_DIRECTION_RTL;
            }
        } catch (Throwable ignored) {}
        return LAYOUT_DIRECTION_LTR;
    }
    /** AOSP `View.resolveLayoutParams()`. Invokes the LayoutParams'
     *  per-direction resolution so margin-start/margin-end /
     *  ConstraintLayout's startToEnd / etc. are converted to absolute
     *  left/right equivalents for the upcoming measure/layout. */
    public void resolveLayoutParams() {
        if (layoutParams != null) {
            try { layoutParams.resolveLayoutDirection(mResolvedLayoutDirection); }
            catch (Throwable ignored) {}
        }
    }
    /** AOSP `View.resetRtlProperties()`. Called when the resolved
     *  state needs to be re-derived (e.g. after setLayoutDirection
     *  or a parent's direction changed). The next measure() will
     *  re-resolve. AOSP's resetResolvedLayoutDirection() also drops the
     *  resolution back to the LTR default until it is recomputed. */
    public void resetRtlProperties() {
        rtlPropertiesResolved = false;
        mResolvedLayoutDirection = LAYOUT_DIRECTION_LTR;
        // AOSP resetRtlProperties() -> resetResolvedPadding(): the padding's
        // start/end -> left/right mapping is derived from the direction, so it is
        // stale the moment the direction is dropped.
        mPaddingResolved = false;
    }
    public boolean isLayoutDirectionResolved() { return rtlPropertiesResolved; }

    /** AOSP `View.getMeasuredState()` (android-11.0.0_r48 View.java:16540-16544):
     *      return (mMeasuredWidth & MEASURED_STATE_MASK)
     *              | ((mMeasuredHeight >> MEASURED_HEIGHT_STATE_SHIFT)
     *                      & (MEASURED_STATE_MASK >> MEASURED_HEIGHT_STATE_SHIFT));
     *  i.e. the width's state bits in place, and the height's state bits shifted
     *  DOWN into bits 16-23 so both fit in one int. This returned a hardcoded 0,
     *  which silently told every parent "no child was ever measured too small". */
    public int  getMeasuredState() {
        return (measuredWidth & MEASURED_STATE_MASK)
                | ((measuredHeight >> MEASURED_HEIGHT_STATE_SHIFT)
                        & (MEASURED_STATE_MASK >> MEASURED_HEIGHT_STATE_SHIFT));
    }
    /** AOSP View.getPaddingStart/getPaddingEnd (View.java:27243/27273): the
     *  START edge is the LEFT padding under LTR and the RIGHT padding under RTL
     *  (and the mirror for END). These used to return left/right unconditionally,
     *  so paddingStart/paddingEnd -- the AndroidX/Material-recommended idiom --
     *  was simply an alias for paddingLeft/paddingRight even on a view whose
     *  layout direction resolved to RTL. */
    public int  getPaddingStart()  {
        // AOSP View.java:24450-24456 forces resolution first, so the answer
        // reflects the current direction even if nothing has measured yet.
        if (!isPaddingResolved()) resolvePadding();
        return isLayoutRtl() ? getPaddingRight() : getPaddingLeft();
    }
    public int  getPaddingEnd()    {
        if (!isPaddingResolved()) resolvePadding();
        return isLayoutRtl() ? getPaddingLeft() : getPaddingRight();
    }

    private float rotationX = 0, rotationY = 0;
    public float getRotationX() { return rotationX; }
    public void  setRotationX(float r) { this.rotationX = r; }
    public float getRotationY() { return rotationY; }
    public void  setRotationY(float r) { this.rotationY = r; }

    private float transitionAlpha = 1f;
    public float getTransitionAlpha() { return transitionAlpha; }
    /** AOSP setTransitionAlpha: stores the multiplier and invalidates the view
     *  (invalidateViewProperty), so a Fade's frames repaint. */
    public void  setTransitionAlpha(float alpha) {
        if (this.transitionAlpha == alpha) return;
        this.transitionAlpha = alpha;
        invalidate();
    }
    public void  setTransitionVisibility(int visibility) {}

    public android.view.WindowId getWindowId() { return null; }
    /** AOSP View.getWindowInsetsController(): the controller of the window this
     *  view is attached to, found by walking up to the ViewRootImpl (and, when
     *  detached, up the parent chain). We have no ViewRootImpl, so the route is
     *  the Context: an Activity's is its Window's controller, and every View in
     *  that Activity's hierarchy therefore gets the SAME object -- which is the
     *  property that matters, because an app commonly sets appearance through
     *  the view and reads visibility through the window (or the reverse), and
     *  two independent controllers would answer one question two ways.
     *
     *  Null when the context is not an Activity, matching AOSP's null for a
     *  view that belongs to no window. */
    public WindowInsetsController getWindowInsetsController() {
        try {
            Context c = context;
            while (c instanceof android.content.ContextWrapper
                   && !(c instanceof android.app.Activity)) {
                Context b = ((android.content.ContextWrapper) c).getBaseContext();
                if (b == null || b == c) break;
                c = b;
            }
            if (c instanceof android.app.Activity) {
                Window w = ((android.app.Activity) c).getWindow();
                if (w != null) return w.getInsetsController();
            }
            if (parent instanceof View) return ((View) parent).getWindowInsetsController();
        } catch (Throwable ignored) {}
        return null;
    }

    public boolean hasOverlappingRendering() { return true; }
    /** AOSP View.forceHasOverlappingRendering/getHasOverlappingRendering
     *  (android11-release View.java:16995-17023): lets a caller override the
     *  alpha-compositing hint hasOverlappingRendering() would otherwise
     *  return. AOSP packs this as two mPrivateFlags3 bits
     *  (PFLAG3_HAS_OVERLAPPING_RENDERING_FORCED +
     *  PFLAG3_OVERLAPPING_RENDERING_FORCED_VALUE); this file has no real
     *  mPrivateFlags3 bitfield, so the pair is mirrored as plain booleans,
     *  same pattern as isDirty()/isAccessibilityHeading() above.
     *
     *  Hoisted here from FrameLayout.java (2026-08-12): AOSP declares the
     *  pair on View itself, and a per-subclass copy cannot intercept every
     *  OTHER subclass's hasOverlappingRendering() override (ImageView's, for
     *  one). The part of the AOSP contract that matters here: forcing does
     *  NOT rewrite hasOverlappingRendering() itself -- that stays the plain,
     *  overridable, force-blind hook every subclass already overrides
     *  directly (ImageView included). It is consulted ONLY through this
     *  separate, final getHasOverlappingRendering(), which is what a real
     *  draw-path consumer is required to call (AOSP's own call site is
     *  RenderNode.setHasOverlappingRendering(getHasOverlappingRendering()),
     *  View.java:21952). Folding the force-check directly into
     *  hasOverlappingRendering() -- which is what the prior FrameLayout-local
     *  copy did, since it had no way to add a second method to View -- would
     *  make forcing silently do nothing on any subclass that overrides
     *  hasOverlappingRendering() without calling super, ImageView included. */
    private boolean overlappingRenderingForced;
    private boolean overlappingRenderingForcedValue;
    public void forceHasOverlappingRendering(boolean hasOverlappingRendering) {
        overlappingRenderingForced = true;
        overlappingRenderingForcedValue = hasOverlappingRendering;
    }
    public final boolean getHasOverlappingRendering() {
        return overlappingRenderingForced
                ? overlappingRenderingForcedValue : hasOverlappingRendering();
    }
    public boolean isFocused() { return mIsFocused; }
    /** AOSP View.findFocus(): the focused view within this view's
     *  hierarchy. Leaf contract: this view if focused, else null
     *  (ViewGroup's real impl additionally consults its mFocused
     *  child chain; with isFocused() fixed false everywhere, null is
     *  the consistent answer). */
    public View findFocus() { return isFocused() ? this : null; }
    /** AOSP View.setRevealOnFocusHint/getRevealOnFocusHint (android11-release
     *  View.java:7806-7828), both `public final`. A signal to ANCESTOR views
     *  (consulted from ViewParent.requestChildFocus) that this view would
     *  like to be scrolled fully into view when it gains focus; AOSP's
     *  default is true (few views opt out, e.g. some scrolling containers).
     *  Stored as mPrivateFlags3 & PFLAG3_NO_REVEAL_ON_FOCUS in AOSP (unset =
     *  reveal, matching the default-true doc); mirrored here as a plain
     *  boolean since this file has no mPrivateFlags3, same pattern as
     *  forceHasOverlappingRendering above. Nothing in this tree's focus path
     *  reads it yet (no ViewParent.requestChildFocus reveal walk), so this is
     *  API-completeness surface: the getter/setter pair exists so a caller
     *  doesn't hit NoSuchMethodError, ready for when that walk lands. */
    private boolean noRevealOnFocus;
    public final void setRevealOnFocusHint(boolean revealOnFocus) {
        noRevealOnFocus = !revealOnFocus;
    }
    public final boolean getRevealOnFocusHint() {
        return !noRevealOnFocus;
    }
    /** v1.0.108: real layout-request tracking. AOSP's
     *  View.isLayoutRequested() reads `mPrivateFlags & FORCE_LAYOUT`.
     *  Many AOSP layout containers (ConstraintLayout in particular)
     *  branch on whether any child has requested layout to decide
     *  whether to rebuild their internal state -- e.g. ConstraintLayout
     *  .onMeasure walks children, and if ANY child's
     *  isLayoutRequested returns true, sets `mDirtyHierarchy = true`
     *  and calls updateHierarchy() to rebuild the ConstraintWidget
     *  graph from the LayoutParams. Without true here, the graph
     *  stays stale (or empty for newly-added children) and the
     *  constraint solver has nothing to solve, so children end up
     *  at equal-divide widths instead of respecting their
     *  constraintLeft/Right/Top/Bottom_toXxxOf="parent" attrs.
     *  Initial value: true (new views need their first layout). */
    boolean layoutRequested = true;
    public boolean isLayoutRequested() { return layoutRequested; }
    /** AOSP View.isDirty() (View.java:17287): "True if this view has changed
     *  since the last time being drawn" -- reads `mPrivateFlags &
     *  PFLAG_DIRTY_MASK`, a bit invalidate() sets and the draw pass clears once
     *  it has consumed the pending damage.
     *  <p>
     *  This framework has no per-view damage tracking: invalidate() does not
     *  set a per-View bit, it calls ViewRootImpl.schedule() to request the next
     *  whole-tree traversal (see ViewRootImpl.scheduleTraversal /
     *  consumeTraversalScheduled, and View.invalidate() above). That flag is
     *  the only "has something changed since we last drew" signal that
     *  actually exists in this architecture, and it has the right lifecycle:
     *  invalidate() sets it, the per-frame traversal clears it right after
     *  drawing. Reading it here gives every view the correct answer to "is a
     *  redraw pending" without inventing a per-view bit that nothing else
     *  would ever set, at the cost of AOSP's finer per-view granularity (a
     *  view with no pending change reports dirty while ANY other view in the
     *  same window is invalidated). */
    public boolean isDirty() { return ViewRootImpl.getInstance().isTraversalScheduled(); }
    public boolean isOpaque() { return false; }

    /** AOSP setAccessibilityHeading/isAccessibilityHeading (View.java:12565) --
     *  PFLAG3_ACCESSIBILITY_HEADING, default off, plain round trip.
     *  (fwprobe VW.flag.accessibilityHeading.true.) */
    private boolean mAccessibilityHeading;
    public void setAccessibilityHeading(boolean isHeading) { mAccessibilityHeading = isHeading; }
    public boolean isAccessibilityHeading() { return mAccessibilityHeading; }
    /** AOSP View.setAnimationMatrix / getAnimationMatrix (View.java:17692-17698
     *  and :17712-17714) both forward to the RenderNode, which STORES the matrix
     *  (RenderNode.java:831-838 returns a fresh copy, or null when none was
     *  set). The setter here discarded its argument and the getter always
     *  answered null, so androidx transitions -- which set an animation matrix
     *  and read it back to compose the next step -- saw their own write vanish.
     *
     *  Copies in and out for the same reason AOSP does: the caller keeps its
     *  Matrix and may keep mutating it, and hwui holds a value, not a reference.
     *
     *  The stored matrix is NOT applied to the draw: AOSP composes it inside the
     *  RenderNode's own transform and our Canvas path has no equivalent hook.
     *  Round-tripping it is still strictly better than losing it -- a library
     *  that reads back what it wrote now gets it -- and the missing composition
     *  is a rendering gap rather than a wrong answer. */
    private android.graphics.Matrix mAnimationMatrix;
    public void setAnimationMatrix(android.graphics.Matrix matrix) {
        mAnimationMatrix = (matrix == null) ? null : new android.graphics.Matrix(matrix);
    }
    public android.graphics.Matrix getAnimationMatrix() {
        return (mAnimationMatrix == null) ? null : new android.graphics.Matrix(mAnimationMatrix);
    }
    public void setHasTransientState(boolean hasTransientState) {}
    public boolean hasTransientState() { return false; }
    public void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        int oldW = this.right - this.left, oldH = this.bottom - this.top;
        this.left = left;   this.top = top;
        this.right = right; this.bottom = bottom;
        int newW = right - left, newH = bottom - top;
        if (newW != oldW || newH != oldH) macapkDispatchSizeChanged(newW, newH, oldW, oldH);
    }

    /** AOSP View.sizeChange -> onSizeChanged: fire the size-changed callback when
     *  a view's width/height actually changes during layout. Custom views build
     *  size-dependent state here (F-Droid's FeatureImage builds its low-poly
     *  triangle pattern in onSizeChanged; without the call its triangles[] stay
     *  null and the app-detail header paints a flat color instead of the pattern).
     *  Generic -- any View/SurfaceView/TextureView onSizeChanged override now runs. */
    protected void macapkDispatchSizeChanged(int newW, int newH, int oldW, int oldH) {
        try { onSizeChanged(newW, newH, oldW, oldH); }
        catch (Throwable t) {
            if (!warnedSizeChanged) {
                warnedSizeChanged = true;
                com.macapk.loader.NativeLog.w("View", "onSizeChanged threw on "
                    + getClass().getSimpleName() + ": " + t);
            }
        }
    }
    public void setLongClickable(boolean longClickable) { this.longClickable = longClickable; }
    public boolean isLongClickable() { return longClickable; }
    /** AOSP View.setOnCreateContextMenuListener stores into ListenerInfo, and
     *  AOSP additionally does `if (!isLongClickable()) setLongClickable(true);`
     *  -- registering the listener is what makes the view eligible for the long
     *  press that opens the menu.
     *
     *  ⚠ This DISCARDED its argument (empty body) until v1.0.940, so an app that
     *  registered a context-menu listener got no menu and no error, and
     *  createContextMenu had nothing to consult. Same family as the
     *  setOnKeyListener bug noted directly below. */
    private OnCreateContextMenuListener mOnCreateContextMenuListener;
    public void setOnCreateContextMenuListener(OnCreateContextMenuListener l) {
        mOnCreateContextMenuListener = l;
        if (!isLongClickable()) setLongClickable(true);
    }
    public OnCreateContextMenuListener macapkOnCreateContextMenuListener() {
        return mOnCreateContextMenuListener;
    }
    /** AOSP View.createContextMenu(ContextMenu): calls onCreateContextMenu and
     *  then the registered listener, so both an override and a listener are
     *  honoured. */
    public void createContextMenu(android.view.ContextMenu menu) {
        onCreateContextMenu(menu);
    }
    /** AOSP View.setOnKeyListener stores into ListenerInfo, and
     *  dispatchKeyEvent gives the listener first crack at every key event
     *  (View.java:16513-16519). This used to discard the listener outright. */
    private OnKeyListener onKeyListener;
    public void setOnKeyListener(OnKeyListener l) { this.onKeyListener = l; }

    public void transformMatrixToLocal(android.graphics.Matrix matrix) {}
    public void transformMatrixToGlobal(android.graphics.Matrix matrix) {}

    public void buildLayer() {}

    // ---- Save / restore instance state (API 1+) ----
    //
    // AOSP frameworks/base View.java:23237-23407. The chain is
    //   saveHierarchyState(container) -> dispatchSaveInstanceState(container)
    //     -> onSaveInstanceState()  [stored in container keyed by mID]
    // and the mirror for restore. ViewGroup overrides the two dispatch methods
    // to also recurse into children. This entire family was ABSENT, so every
    // widget that uses the standard onSaveInstanceState()/BaseSavedState pattern
    // (SwitchCompat, TabLayout, SeekBar, ViewPager's current item, any app's own
    // custom View) both NoSuchMethodError'd on `super.onSaveInstanceState()` and
    // silently lost all per-view state.
    private boolean saveEnabled = true;
    private boolean saveFromParentEnabled = true;
    /** AOSP PFLAG_SAVE_STATE_CALLED: proves the subclass chained to super in its
     *  onSaveInstanceState/onRestoreInstanceState override. AOSP throws when it
     *  did not, because the super call is what preserves the state of every
     *  class above the override in the hierarchy. */
    private boolean saveStateCalled;
    public void setSaveEnabled(boolean enabled) { saveEnabled = enabled; }
    public boolean isSaveEnabled() { return saveEnabled; }
    /** AOSP setSaveFromParentEnabled/isSaveFromParentEnabled (View.java:14518,
     *  14535): whether a PARENT's state-saving traversal descends into this
     *  view. The value used to be discarded outright. */
    public void setSaveFromParentEnabled(boolean enabled) { saveFromParentEnabled = enabled; }
    public boolean isSaveFromParentEnabled() { return saveFromParentEnabled; }

    /** AOSP View.saveHierarchyState (View.java:23237). */
    public void saveHierarchyState(android.util.SparseArray<android.os.Parcelable> container) {
        dispatchSaveInstanceState(container);
    }
    /** AOSP View.dispatchSaveInstanceState (View.java:23252): store this view's
     *  state under its id, unless it has no id or saving is disabled. */
    protected void dispatchSaveInstanceState(
            android.util.SparseArray<android.os.Parcelable> container) {
        if (container == null || id == NO_ID || !saveEnabled) return;
        saveStateCalled = false;
        android.os.Parcelable state = onSaveInstanceState();
        if (!saveStateCalled) {
            throw new IllegalStateException(
                "Derived class did not call super.onSaveInstanceState()");
        }
        if (state != null) container.put(id, state);
    }
    /** AOSP View.onSaveInstanceState (View.java:23289). The base view has no
     *  dynamic state of its own, so it returns the EMPTY_STATE marker that
     *  subclasses chain through their BaseSavedState(superState) constructor. */
    protected android.os.Parcelable onSaveInstanceState() {
        saveStateCalled = true;
        return BaseSavedState.EMPTY_STATE;
    }
    /** AOSP View.restoreHierarchyState (View.java:23325). */
    public void restoreHierarchyState(android.util.SparseArray<android.os.Parcelable> container) {
        dispatchRestoreInstanceState(container);
    }
    /** AOSP View.dispatchRestoreInstanceState (View.java:23341). */
    protected void dispatchRestoreInstanceState(
            android.util.SparseArray<android.os.Parcelable> container) {
        if (container == null || id == NO_ID) return;
        android.os.Parcelable state = container.get(id);
        if (state == null) return;
        saveStateCalled = false;
        onRestoreInstanceState(state);
        if (!saveStateCalled) {
            throw new IllegalStateException(
                "Derived class did not call super.onRestoreInstanceState()");
        }
    }
    /** AOSP View.onRestoreInstanceState (View.java:23370). The type check is
     *  AOSP's own guard against two views sharing an id. */
    protected void onRestoreInstanceState(android.os.Parcelable state) {
        saveStateCalled = true;
        if (state != null && !(state instanceof AbsSavedState)) {
            throw new IllegalArgumentException("Wrong state class, expecting View State but "
                + "received " + state.getClass().toString() + " instead. This usually happens "
                + "when two views of different type have the same id in the same hierarchy. "
                + "This view's id is " + getId() + ". Make sure "
                + "other views do not use the same id.");
        }
    }

    // Window-insets dispatch (API 20+). v1.0.136: stores the listener
    // for real and provides a default onApplyWindowInsets that mirrors
    // AOSP's fitsSystemWindows-aware consumption path. The typed-
    // overload entry point below (line ~1700) routes through the
    // same field via setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener).
    // ViewCompat (androidx.core.view) calls the typed overload on API 21+;
    // the Object overload exists so source code that hands us a
    // null / untyped value compiles.
    public void setOnApplyWindowInsetsListener(Object listener) {
        if (listener == null) { onApplyWindowInsetsListener = null; return; }
        if (listener instanceof OnApplyWindowInsetsListener typed) {
            onApplyWindowInsetsListener = typed;
        }
    }
    /** AOSP-default onApplyWindowInsets: when this View has
     *  `fitsSystemWindows=true`, take the system-bar inset bag and
     *  apply it as padding (this is what AppCompat's DecorView does
     *  for "edge-to-edge" activities). Otherwise return the bag
     *  unchanged so a child / ViewGroup descendant gets a chance to
     *  consume. Material widgets override via setOnApplyWindowInsetsListener
     *  so the listener takes precedence when registered. */
    public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (insets == null) return null;
        if (fitsSystemWindows) {
            android.graphics.Insets sys = insets.getSystemWindowInsets();
            if (sys != null && (sys.left != 0 || sys.top != 0
                                  || sys.right != 0 || sys.bottom != 0)) {
                setPadding(sys.left, sys.top, sys.right, sys.bottom);
                return insets.consumeSystemWindowInsets();
            }
        }
        return insets;
    }
    /** Stash for the current listener. ViewGroup.dispatchApplyWindowInsets
     *  routes through here (via super.dispatchApplyWindowInsets) before
     *  walking children. Cleared on setOnApplyWindowInsetsListener(null). */
    private OnApplyWindowInsetsListener onApplyWindowInsetsListener;

    // Window-attached lifecycle. Many androidx components query
    // `isAttachedToWindow()` to gate background work.
    // (typed overloads are below; the Object overloads were the original
    // stubs and the typed ones supersede them. Removed to avoid duplicate
    // definitions when OnAttachStateChangeListener was promoted to a
    // proper inner interface.)

    public void getDrawingRect(android.graphics.Rect outRect) {
        if (outRect != null) outRect.set(0, 0, getWidth(), getHeight());
    }

    // Click / focus / touch (additional surfaces RecyclerView depends on).
    // AOSP View.performClick(): invokes the registered OnClickListener and
    // returns true iff one was present (it also plays a sound + fires an
    // accessibility event, which we don't model). callOnClick() is the same
    // minus the sound/accessibility side effects.
    public boolean performClick() {
        if ("1".equals(ENV_MACAPK_TRACE_TOUCH)) {
            com.macapk.loader.NativeLog.i("View",
                "performClick on " + getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(this))
                + " id=0x" + Integer.toHexString(getId())
                + " listener=" + (onClickListener == null ? "NULL"
                    : onClickListener.getClass().getName()));
        }
        if (onClickListener != null) {
            onClickListener.onClick(this);
            return true;
        }
        return false;
    }
    public boolean performLongClick() {
        if (onLongClickListener != null) {
            return onLongClickListener.onLongClick(this);
        }
        return false;
    }
    public boolean performLongClick(float x, float y) { return performLongClick(); }
    public boolean callOnClick() {
        if (onClickListener != null) {
            onClickListener.onClick(this);
            return true;
        }
        return false;
    }
    /** AOSP View.dispatchKeyShortcutEvent (android14-release View.java:15608):
     *  {@code return onKeyShortcut(event.getKeyCode(), event);} -- the leaf of
     *  the shortcut chain ViewRootImpl runs for a key the view tree declined
     *  while a modifier was held (ViewRootImpl.java:6972). It answered false,
     *  so TextView.onKeyShortcut (Ctrl+A/X/C/V) was unreachable. */
    public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) {
        return onKeyShortcut(event.getKeyCode(), event);
    }
    /** AOSP View.dispatchGenericMotionEvent (android14-release View.java:15759-15788):
     *  a SOURCE_CLASS_POINTER event goes to dispatchHoverEvent (hover actions)
     *  or dispatchGenericPointerEvent (ViewGroup: the child under the pointer);
     *  ANY OTHER source -- a joystick, which is what a game controller's sticks
     *  and triggers produce -- goes to dispatchGenericFocusedEvent (ViewGroup:
     *  the focused child). Then dispatchGenericMotionEventInternal
     *  (:15790-15805): the registered OnGenericMotionListener, then this view's
     *  own onGenericMotionEvent.
     *
     *  Until 2026-10-02 only the Internal half existed (and before that, a bare
     *  `return false`), and ViewGroup walked EVERY child for every source. That
     *  was unobservable while nothing produced a generic motion event; host game
     *  controllers (com.macapk.loader.GamepadBridge) are the first producer, and
     *  a joystick event must reach the FOCUSED view (libGDX's and GameActivity's
     *  input views request focus for exactly this), not whichever sibling
     *  happens to answer first. */
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        final int source = event.getSource();
        if ((source & android.view.InputDevice.SOURCE_CLASS_POINTER) != 0) {
            final int action = event.getAction();
            if (action == android.view.MotionEvent.ACTION_HOVER_ENTER
                    || action == android.view.MotionEvent.ACTION_HOVER_MOVE
                    || action == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                if (dispatchHoverEvent(event)) return true;
            } else if (dispatchGenericPointerEvent(event)) {
                return true;
            }
        } else if (dispatchGenericFocusedEvent(event)) {
            return true;
        }
        if (onGenericMotionListener != null && isEnabled()
                && onGenericMotionListener.onGenericMotion(this, event)) {
            return true;
        }
        if (onGenericMotionEvent(event)) return true;
        // AOSP View.dispatchGenericMotionEventInternal (android14 View.java:16788-16812):
        // a SECONDARY (or stylus primary) BUTTON_PRESS on a context-clickable view
        // performs the context click and holds the pressed state; its
        // BUTTON_RELEASE ends it and makes the following UP skip the click.
        final int actionButton = event.getActionButton();
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_BUTTON_PRESS:
                if (isContextClickable() && !mInContextButtonPress && !mHasPerformedLongPress
                        && (actionButton == android.view.MotionEvent.BUTTON_STYLUS_PRIMARY
                        || actionButton == android.view.MotionEvent.BUTTON_SECONDARY)) {
                    if (performContextClick(event.getX(), event.getY())) {
                        mInContextButtonPress = true;
                        setPressed(true);
                        removeLongPressCallback();
                        return true;
                    }
                }
                break;
            case android.view.MotionEvent.ACTION_BUTTON_RELEASE:
                if (mInContextButtonPress && (actionButton == android.view.MotionEvent.BUTTON_STYLUS_PRIMARY
                        || actionButton == android.view.MotionEvent.BUTTON_SECONDARY)) {
                    mInContextButtonPress = false;
                    mIgnoreNextUpEvent = true;
                }
                break;
            default:
                break;
        }
        return false;
    }
    /** AOSP View.mInContextButtonPress / mIgnoreNextUpEvent. */
    private boolean mInContextButtonPress;
    private boolean mIgnoreNextUpEvent;

    /** AOSP View.performButtonActionOnTouchDown (android14 View.java:8224), @hide:
     *  a MOUSE secondary-button press shows the context menu and consumes the
     *  DOWN. */
    protected boolean performButtonActionOnTouchDown(MotionEvent event) {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
            showContextMenu(event.getX(), event.getY());
            return true;
        }
        return false;
    }
    public boolean onGenericMotionEvent(android.view.MotionEvent event) { return false; }
    /** AOSP View.cancelLongPress (View.java:18392-18401): a scrolling container
     *  calls this on its children the moment it takes over the gesture, so the
     *  pending long press does not fire mid-scroll. It was an empty stub while
     *  there was no long-press timer to cancel; now there is one.
     *  AOSP also calls removeTapCallback() here -- we do not implement the
     *  PREPRESSED/CheckForTap path (see the note above onTouchEvent). */
    public void cancelLongPress() {
        removeLongPressCallback();
    }
    public boolean hasOnClickListeners() { return onClickListener != null; }
    /** AOSP View.drawableHotspotChanged(float, float): RippleDrawable
     *  positions its ripple via this callback. We don't render ripples,
     *  but ViewGroup.dispatchDrawableHotspotChanged invokes it on
     *  children — keep the base impl present so the override-chain
     *  doesn't NoSuchMethodError. */
    public void drawableHotspotChanged(float x, float y) {}

    // Misc API surface needed for various androidx components
    public void postInvalidate(int left, int top, int right, int bottom) { invalidate(); }
    public void postInvalidateDelayed(long delayMs) { invalidate(); }
    public void postInvalidateOnAnimation() { invalidate(); }
    public void requestFitSystemWindows() {}
    public boolean fitSystemWindows(android.graphics.Rect insets) { return false; }
    public void setOnSystemUiVisibilityChangeListener(Object listener) {}

    public int getLeft()    { return left; }
    public int getTop()     { return top; }
    public int getRight()   { return right; }
    public int getBottom()  { return bottom; }
    // AOSP View.setLeft/Top/Right/Bottom(int) -- the per-edge layout setters that
    // ObjectAnimator / Transition / RippleDrawable hotspot tracking drive (e.g. a
    // Material press ripple animates the view's bounds). Were MISSING ->
    // NoSuchMethodError aborted the press/transition handler so a tap registered
    // but its visual+follow-on never completed. Each invalidates like AOSP. v1.0.442.
    public void setLeft(int v)   { if (v != left)   { left = v;   onSizeChangedIfNeeded(); invalidate(); } }
    public void setTop(int v)    { if (v != top)    { top = v;    onSizeChangedIfNeeded(); invalidate(); } }
    public void setRight(int v)  { if (v != right)  { right = v;  onSizeChangedIfNeeded(); invalidate(); } }
    public void setBottom(int v) { if (v != bottom) { bottom = v; onSizeChangedIfNeeded(); invalidate(); } }
    private int lastSizeW = -1, lastSizeH = -1;
    private void onSizeChangedIfNeeded() {
        int w = right - left, h = bottom - top;
        if (w != lastSizeW || h != lastSizeH) {
            int ow = lastSizeW < 0 ? 0 : lastSizeW, oh = lastSizeH < 0 ? 0 : lastSizeH;
            lastSizeW = w; lastSizeH = h;
            try { onSizeChanged(w, h, ow, oh); } catch (Throwable ignored) {}
        }
    }
    public int getWidth()   { return right - left; }
    public int getHeight()  { return bottom - top; }
    // measuredWidth / measuredHeight + getMeasuredWidth/getMeasuredHeight
    // moved to layout-pass section below (single source of truth).

    /** AOSP android14 View.java:26033-26038 / :26063-26068:
     *      public int getPaddingLeft() {
     *          if (!isPaddingResolved()) {
     *              resolvePadding();
     *          }
     *          return mPaddingLeft;
     *      }
     *  (getPaddingTop/Bottom do not resolve: start/end never touch them.)
     *  Cluster B3 made this observable: the constructor now leaves a
     *  default-style paddingStart/End in mUserPaddingStart/End for
     *  resolvePadding, exactly as AOSP's does, so an unresolved read between
     *  construction and the first measure must resolve first or it answers
     *  the pre-resolution left/right. Before B3 nothing left start/end pending
     *  across a read (setPaddingRelative writes the absolute sides at once),
     *  which is why the plain field read went unnoticed. Rides
     *  MACAPK_VIEW_CTOR_PADDING (=0 is the plain field read). */
    public int getPaddingLeft()   {
        if (MACAPK_VIEW_CTOR_PADDING && !isPaddingResolved()) resolvePadding();
        return paddingLeft;
    }
    public int getPaddingTop()    { return paddingTop; }
    public int getPaddingRight()  {
        if (MACAPK_VIEW_CTOR_PADDING && !isPaddingResolved()) resolvePadding();
        return paddingRight;
    }
    public int getPaddingBottom() { return paddingBottom; }
    public void setPadding(int l, int t, int r, int b) {
        // v1.0.136: AOSP-correct -- setPadding triggers requestLayout
        // when the value changed so the next measure pass picks up
        // the new padding contribution to the view's measured size.
        // We DON'T zero measuredWidth/Height here: in our framework
        // the per-frame layout cascade doesn't drive measure (only
        // RecyclerViews are explicitly re-measured); leaving the old
        // values lets downstream tools (e.g. ViewCanvasDriver's
        // growViewsWithBottomInsetPadding) compute the new measure
        // from the pre-padding baseline. AOSP's setPadding does the
        // same -- it never zeros measuredWidth/Height directly.
        //
        // AOSP (android-11.0.0_r48 View.java:24284-24297) also CLEARS the relative
        // bookkeeping here -- an absolute setPadding is what makes
        // isPaddingRelative() go false again, and records the left/right it was
        // given as the "initial" values resolvePadding falls back to when no
        // start/end was ever supplied.
        mUserPaddingStart = UNDEFINED_PADDING;
        mUserPaddingEnd = UNDEFINED_PADDING;
        mUserPaddingLeftInitial = l;
        mUserPaddingRightInitial = r;
        mPaddingResolved = false;
        internalSetPadding(l, t, r, b);
    }
    /** AOSP `View.internalSetPadding` (View.java:24302+) minus the scrollbar-inset
     *  arithmetic, which needs scrollbar geometry this tree does not model. The
     *  part that matters is shared by setPadding, setPaddingRelative and
     *  resolvePadding: write the absolute padding and requestLayout on change. */
    protected void internalSetPadding(int l, int t, int r, int b) {
        boolean changed = (paddingLeft != l) || (paddingTop != t)
                       || (paddingRight != r) || (paddingBottom != b);
        paddingLeft = l; paddingTop = t; paddingRight = r; paddingBottom = b;
        if (changed) requestLayout();
    }
    /** AOSP `View.resolvePadding()` (android-11.0.0_r48 View.java:20219-20276).
     *  Converts the user's start/end padding into left/right for the direction
     *  that was just resolved. The whole conversion is skipped in RTL
     *  compatibility mode (pre-JB-MR1 target, or no android:supportsRtl), where
     *  AOSP re-applies the absolute padding unchanged -- which is every app in
     *  this tree today, so this is currently a no-op by construction. It is
     *  implemented in full anyway so that the moment ApplicationInfo learns to
     *  parse android:supportsRtl (see hasRtlSupport), relative padding resolves
     *  correctly instead of needing a second fix. */
    public void resolvePadding() {
        if (!isRtlCompatibilityMode()) {
            int userPaddingLeft, userPaddingRight;
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
                userPaddingRight = (mUserPaddingStart != UNDEFINED_PADDING)
                        ? mUserPaddingStart : mUserPaddingRightInitial;
                userPaddingLeft = (mUserPaddingEnd != UNDEFINED_PADDING)
                        ? mUserPaddingEnd : mUserPaddingLeftInitial;
            } else {
                userPaddingLeft = (mUserPaddingStart != UNDEFINED_PADDING)
                        ? mUserPaddingStart : mUserPaddingLeftInitial;
                userPaddingRight = (mUserPaddingEnd != UNDEFINED_PADDING)
                        ? mUserPaddingEnd : mUserPaddingRightInitial;
            }
            internalSetPadding(userPaddingLeft, paddingTop, userPaddingRight, paddingBottom);
        }
        // Two deliberate deviations from the AOSP body, both no-ops here:
        //  - AOSP calls internalSetPadding OUTSIDE this `if`, re-applying its own
        //    mUserPaddingLeft/Right/Bottom. Those are separate fields there; here
        //    the resolved padding IS paddingLeft/paddingRight, so the compat-mode
        //    call would be internalSetPadding(paddingLeft, paddingTop,
        //    paddingRight, paddingBottom) -- literally re-writing what is already
        //    stored. Keeping it inside the branch avoids a per-getPaddingStart()
        //    no-op without changing any answer.
        //  - AOSP also fires onRtlPropertiesChanged() from here.
        //    resolveRtlPropertiesIfNeeded() already fires it once per resolution
        //    in this tree; firing it a second time would be the divergence.
        mPaddingResolved = true;
    }
    /** AOSP `View.isPaddingResolved()` (View.java:20208-20210). */
    boolean isPaddingResolved() { return mPaddingResolved; }
    /** AOSP `View.setPaddingRelative` (android-11.0.0_r48 View.java:24378-24398).
     *  Material's RelativePadding.applyToView calls this to push
     *  WindowInsets-derived padding back onto the view; without it,
     *  BottomNavigationView's OnApplyWindowInsetsListener throws NoSuchMethodError
     *  mid-dispatch and the bar never grows. Generic for any Material widget.
     *
     *  Direction-SENSITIVE AT CALL TIME: AOSP switches on getLayoutDirection()
     *  right here, which is why "set padding then flip direction" and "flip
     *  direction then set padding" are different programs. It must NOT delegate to
     *  setPadding(), because setPadding CLEARS mUserPaddingStart/End -- the very
     *  fields that make isPaddingRelative() true and let resolvePadding re-map the
     *  edges on a later direction change. */
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        mPaddingResolved = false;
        mUserPaddingStart = start;
        mUserPaddingEnd = end;
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
            mUserPaddingLeftInitial = end;
            mUserPaddingRightInitial = start;
            internalSetPadding(end, top, start, bottom);
        } else {
            mUserPaddingLeftInitial = start;
            mUserPaddingRightInitial = end;
            internalSetPadding(start, top, end, bottom);
        }
    }

    public int  getVisibility()         { return visibility; }
    /** AOSP VISIBILITY_MASK (View.java:1060) = 0x0000000C, and setVisibility
     *  (:11718) is nothing but `setFlags(visibility, VISIBILITY_MASK)` -- so it
     *  does NOT validate, it MASKS: setVisibility(7) is INVISIBLE (7 & 0xC ==
     *  4), setVisibility(1) is VISIBLE, setVisibility(-1) is GONE|INVISIBLE ==
     *  12. getVisibility() then reads `mViewFlags & VISIBILITY_MASK`, so an
     *  out-of-range value can never be observed. We stored the raw int, so a
     *  view could sit in a visibility state real Android cannot represent and
     *  every `== VISIBLE` / `== GONE` test in the framework quietly missed
     *  (fwprobe VW.flag.vis.{one,seven,minus1}). */
    static final int VISIBILITY_MASK = 0x0000000C;   // package-private, as on AOSP
    /** Gate MACAPK_HIDE_CLEARS_FOCUS (default ON; =0 keeps focus on a view
     *  that is hidden, the old behaviour). */
    static final boolean MACAPK_HIDE_CLEARS_FOCUS =
        !"0".equals(System.getenv("MACAPK_HIDE_CLEARS_FOCUS"));

    /** AOSP View.setFlags (android14-release View.java:17513-17557) clears the
     *  focus of a view (or of the focused descendant of a ViewGroup) that
     *  becomes hidden:
     *      if ((changed & GONE) != 0) { ...
     *          if (((mViewFlags & VISIBILITY_MASK) == GONE)) {
     *              if (hasFocus()) { clearFocus(); ... clearFocusedInCluster(); } ...
     *      if ((changed & INVISIBLE) != 0) { ...
     *          if (((mViewFlags & VISIBILITY_MASK) == INVISIBLE)) {
     *              // root view becoming invisible shouldn't clear focus
     *              if (getRootView() != this) { if (hasFocus()) { clearFocus(); ... } }
     *  Ours only wrote the field, so a field that was hidden kept the focus --
     *  and with it the keyboard: host keystrokes kept landing in an EditText the
     *  user could no longer see, and a widget that commits its typed value on
     *  focus loss (NumberPicker's input, hidden by hideSoftInput) never did. */
    private void macapkClearFocusIfHidden(int oldV, int newV) {
        if (!MACAPK_HIDE_CLEARS_FOCUS || oldV == newV) return;
        final int changed = oldV ^ newV;
        if ((changed & GONE) != 0 && newV == GONE) {
            if (hasFocus()) clearFocus();
        } else if ((changed & INVISIBLE) != 0 && newV == INVISIBLE) {
            if (getRootView() != this && hasFocus()) clearFocus();
        }
    }

    public void setVisibility(int rawVisibility) {
        final int v = rawVisibility & VISIBILITY_MASK;
        final int macapkOldVisibility = this.visibility;
        // Tier 3 M6: a visibility change alters layout/draw, so request a
        // traversal (AOSP View.setVisibility -> requestLayout + invalidate when
        // GONE toggles, invalidate otherwise). Gated; flag-off is the historical
        // bare field write (the driver re-traverses every frame anyway).
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            if (this.visibility != v) {
                this.visibility = v;
                requestLayout();
                invalidate();
                // AOSP View.setFlags runs the GONE / INVISIBLE clearFocus blocks
                // (View.java android14-release ~17512-17557) BEFORE the
                // `(changed & VISIBILITY_MASK) != 0` block that dispatches
                // onVisibilityChanged (~17566-17581), so a callback already sees
                // hasFocus()==false.
                macapkClearFocusIfHidden(macapkOldVisibility, v);
                // AOSP View.setFlags gates the dispatch on `mAttachInfo != null`
                // (frameworks/base .../View.java ~line 16912: "if (mAttachInfo !=
                // null) { dispatchVisibilityChanged(this, newVisibility); ... }"),
                // i.e. a visibility toggle on a not-yet-attached view (common
                // during construction/inflation) does not fire the callback.
                // macapkAttached is this codebase's mAttachInfo != null stand-in.
                if (macapkAttached) dispatchVisibilityChanged(this, v);
                macapkDragVisibilityChanged(v);
            }
            return;
        }
        final boolean changed = this.visibility != v;
        this.visibility = v;
        macapkClearFocusIfHidden(macapkOldVisibility, v);
        if (changed) macapkDragVisibilityChanged(v);
    }
    /** AOSP View.setFlags -> ViewGroup.onChildVisibilityChanged(this, old, new):
     *  the only consumer here is the drag bookkeeping (a drop zone revealed
     *  mid-drag must be told ACTION_DRAG_STARTED). Inert unless a drag is in
     *  flight (the parent's mCurrentDragStartEvent is null). */
    private void macapkDragVisibilityChanged(int newVisibility) {
        if (newVisibility == VISIBLE && parent instanceof ViewGroup vg) {
            vg.macapkDragChildVisibilityChanged(this, newVisibility);
        }
    }
    /** AOSP `View.isShown()` -- "true if this view and all of its ancestors
     *  are VISIBLE." Per frameworks/base/core/java/android/view/View.java
     *  (aosp-mirror android13-release, ~line 11378):
     *      public boolean isShown() {
     *          View current = this;
     *          do {
     *              if ((current.mViewFlags & VISIBILITY_MASK) != VISIBLE) {
     *                  return false;
     *              }
     *              ViewParent parent = current.mParent;
     *              if (parent == null) {
     *                  return false; // We are not attached to the view root
     *              }
     *              if (!(parent instanceof View)) {
     *                  return true;
     *              }
     *              current = (View) parent;
     *          } while (current != null);
     *          return false;
     *      }
     *  Previously this only checked `this.visibility`, ignoring every
     *  ancestor -- a view nested inside a GONE or INVISIBLE parent (a
     *  collapsed section, a hidden tab page, a closed drawer) silently
     *  reported isShown()==true. That is the same "wrong-but-plausible
     *  measurement/visibility answer" shape as the onMeasure fallback bug:
     *  no exception, just a boolean apps trust to gate autoplay / impression
     *  tracking / click handling / accessibility announcements.
     *
     *  We walk the ancestor chain checking VISIBLE at every level like AOSP,
     *  but deliberately do NOT port the `parent == null -> false` branch
     *  (AOSP's "not attached to the view root" signal). AOSP can use a null
     *  parent for that because the real DecorView's mParent is always the
     *  ViewRootImpl (a non-View ViewParent) once attached, so a null parent
     *  only happens on a genuinely detached view. Mac.apk has no such
     *  ViewParent-implementing window-root object: the main content root
     *  attaches via ViewCanvasDriver.macapkDrawFrame calling
     *  macapkDispatchAttachedToWindow() directly, and EVERY Dialog /
     *  PopupWindow / Spinner-popup / AutoCompleteTextView suggestion list
     *  (com.macapk.loader.WindowOverlayStack, see its addView) manages its
     *  root view standalone -- it never calls macapkDispatchAttachedToWindow
     *  on that root, so macapkAttached stays false for the entire overlay
     *  subtree even while it is genuinely on screen and receiving touches.
     *  Gating on macapkAttached here would make isShown() falsely return
     *  false for every currently-visible dialog/popup, a worse regression
     *  than the bug being fixed. The real fix for that gap is
     *  WindowOverlayStack dispatching attach/detach on its layers (outside
     *  this file's ownership); until then we stop the walk at the top of
     *  the View chain (non-View or null parent) and call that "shown",
     *  matching this method's pre-existing (undocumented) stance on
     *  attachment while fixing the ancestor-visibility gap, which is the
     *  dominant real-world failure mode. */
    public boolean isShown() {
        View current = this;
        do {
            if (current.visibility != VISIBLE) return false;
            ViewParent p = current.parent;
            if (p == null) {
                // AOSP (android-11.0.0_r48 View.java:11090-11106) ends the walk
                // here with `return false; // We are not attached to the view root`.
                // It can afford the bare null test because a real attached tree
                // ALWAYS terminates at the ViewRootImpl, a non-View ViewParent, so
                // a null parent means genuinely detached. Ours has no such object
                // (android.view.ViewRootImpl in this tree is a traversal-schedule
                // singleton, not a ViewParent, and nothing assigns it as a root's
                // parent), so `p == null` alone cannot tell the DETACHED tree the
                // probe builds from the LIVE content root -- both have p == null.
                //
                // macapkAttached is this codebase's mAttachInfo != null stand-in
                // (the same stand-in hasWindowFocus() already uses, for the same
                // reason and traced to the same fwprobe family), so ask it: a root
                // that has been through macapkDispatchAttachedToWindow occupies the
                // position AOSP's ViewRootImpl occupies -> shown; a root that never
                // attached is AOSP's null-parent case -> not shown.
                //
                // KNOWN COST, measured cause not guessed: WindowOverlayStack never
                // dispatches attach to the roots it puts on screen (only
                // ViewCanvasDriver does, and only for the content root), so every
                // Dialog / PopupWindow / Spinner popup answers isShown()==false
                // while genuinely visible. The companion fix is one call in
                // WindowOverlayStack.addView, not a change here. Until it lands,
                // MACAPK_ISSHOWN_DETACHED=0 restores the previous "top of the View
                // chain is always shown" answer for a one-run A/B.
                return !sIsShownDetachedAosp || current.macapkAttached;
            }
            if (!(p instanceof View)) return true;
            current = (View) p;
        } while (current != null);
        return true;
    }
    /** Bisect gate for the isShown()/getLocationInWindow() detached-tree contract
     *  above. DEFAULT ON (AOSP behaviour); set MACAPK_ISSHOWN_DETACHED=0 to get the
     *  pre-fix answers back without a rebuild. Read once into a static so the touch
     *  and draw paths do not pay a getenv. */
    private static final boolean sIsShownDetachedAosp =
        !"0".equals(System.getenv("MACAPK_ISSHOWN_DETACHED"));
    public boolean isEnabled()          { return enabled; }
    public void setEnabled(boolean e)   {
        // Flag-OFF: just set the field (exactly the old behavior). Flag-ON:
        // also refresh drawable state + invalidate so state-dependent
        // drawables/colors repaint.
        boolean changed = enabled != e;
        enabled = e;
        if (changed && android.os.Looper.MACAPK_REAL_LOOPER) { refreshDrawableState(); invalidate(); }
    }
    // v1.0.110: AOSP duplicate-parent-state flag.
    // MaterialCardView's super.<init> reads this to decide whether to
    // route state changes through its foreground RippleDrawable. AOSP:
    //   https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/View.java
    //   (search for isDuplicateParentStateEnabled)
    private boolean duplicateParentState;
    public boolean isDuplicateParentStateEnabled() { return duplicateParentState; }
    public void setDuplicateParentStateEnabled(boolean enabled) { this.duplicateParentState = enabled; }
    /** THE FOCUSABLE TRI-STATE (API 26+). AOSP keeps NOT_FOCUSABLE (0),
     *  FOCUSABLE (1) and FOCUSABLE_AUTO (0x10) in the same FOCUSABLE_MASK
     *  (0x11) region of mViewFlags, and a fresh View(Context) is seeded with
     *  FOCUSABLE_AUTO (View.java:5307). Two consequences the old single boolean
     *  could not express:
     *    - isFocusable() tests only the FOCUSABLE bit, so a default view is NOT
     *      focusable, while getFocusable() answers 16 rather than 0
     *      (getFocusable:12509 -- `(mViewFlags & FOCUSABLE_AUTO) > 0 ?
     *      FOCUSABLE_AUTO : mViewFlags & FOCUSABLE`). getFocusable() did not
     *      exist here at all, so every call was a NoSuchMethodError.
     *    - the two setters CROSS-CLEAR: setFocusable(NOT_FOCUSABLE) also drops
     *      FOCUSABLE_IN_TOUCH_MODE (:11792) and setFocusableInTouchMode(true)
     *      also drops FOCUSABLE_AUTO by forcing FOCUSABLE (:11813).
     *  mFocusableAuto is the AUTO bit; `focusable` stays the FOCUSABLE bit, so
     *  every existing isFocusable() caller is unchanged for every state it
     *  could previously reach. fwprobe VW.flag.focusable.*. */
    public static final int NOT_FOCUSABLE  = 0x00000000;
    public static final int FOCUSABLE      = 0x00000001;
    public static final int FOCUSABLE_AUTO = 0x00000010;
    private boolean mFocusableAuto = true;
    public boolean isFocusable()        { return focusable; }
    public int getFocusable() { return mFocusableAuto ? FOCUSABLE_AUTO : (focusable ? FOCUSABLE : NOT_FOCUSABLE); }
    public void setFocusable(boolean f) { setFocusable(f ? FOCUSABLE : NOT_FOCUSABLE); }
    public void setFocusable(int f) {
        if ((f & (FOCUSABLE_AUTO | FOCUSABLE)) == 0) focusableInTouchMode = false;
        focusable = (f & FOCUSABLE) != 0;
        mFocusableAuto = (f & FOCUSABLE_AUTO) != 0;
    }
    public boolean isFocusableInTouchMode() { return focusableInTouchMode; }
    public void setFocusableInTouchMode(boolean f) {
        focusableInTouchMode = f;
        if (f) { focusable = true; mFocusableAuto = false; }
    }
    public boolean isClickable()        { return clickable; }
    public void setClickable(boolean c) { clickable = c; }
    public float getAlpha()             { return alpha; }
    // Tier 3 M9: invalidate on a real change so a fade schedules an M6
    // traversal and the new alpha actually renders (AOSP setAlpha ->
    // mRenderNode.setAlpha + invalidateViewProperty/invalidate). invalidate()
    // is itself flag-gated (no-op flag-off), so the shipped build is unchanged.
    public void  setAlpha(float a)      { if (alpha != a) { alpha = a; invalidate(); } }

    public ViewParent getParent()       { return parent; }
    public void  setParent(ViewParent p){
        this.parent = p;
        if (p != null) macapkWindowRemoved = false;   // joined a tree: see macapkWindowRemoved
    }
    /** Diagnostic: LayoutParams width/height as a short string (MP=-1, WC=-2). */
    String lpStr() {
        ViewGroup.LayoutParams lp = layoutParams;
        if (lp == null) return "null";
        return lp.width + "x" + lp.height;
    }
    public ViewGroup.LayoutParams getLayoutParams() { return layoutParams; }
    public void setLayoutParams(ViewGroup.LayoutParams lp) {
        // AOSP :18175 opens with
        //     if (params == null) throw new NullPointerException("Layout parameters cannot be null");
        // A silently-stored null turns into an NPE inside the next measure pass
        // instead of at the call that caused it (fwprobe VW.id.lp.null).
        if (lp == null) throw new NullPointerException("Layout parameters cannot be null");
        this.layoutParams = lp;
        // AOSP View.setLayoutParams (android13-release View.java:18972-18982) is
        //   mLayoutParams = params; resolveLayoutParams(); ...; requestLayout();
        // and resolveLayoutParams() (:18989) calls
        //   mLayoutParams.resolveLayoutDirection(getLayoutDirection());
        // Ours stored the LP and never resolved it, which is one half of why
        // ConstraintLayout's Start/End constraints never took effect: CL keeps
        // start/end RAW and only converts them to left/right anchors inside
        // LayoutParams.resolveLayoutDirection. Top/bottom need no conversion,
        // which is exactly the measured fingerprint -- vertical placement
        // pixel-exact, horizontal flush-left (AnkiDroid onboarding; analyst
        // report docs/canvas-ab-analyst-report_2026-08-02.md item 1).
        macapkResolveLayoutParams();
    }
    /** AOSP View.resolveLayoutParams(). ViewGroup overrides it to recurse. */
    void macapkResolveLayoutParams() {
        ViewGroup.LayoutParams lp = this.layoutParams;
        if (lp == null) return;
        try { lp.resolveLayoutDirection(getLayoutDirection()); }
        catch (Throwable ignored) {}
    }

    /** v1.0.76: minimal measure/layout pass. Real Android does a
     *  multi-phase MeasureSpec dance. We approximate enough for
     *  ConstraintLayout / LinearLayout subclasses to position
     *  children. Base View.layout stores bounds + records width/
     *  height as the "measured" values; subclasses (ViewGroup)
     *  override to also lay out children. */
    // DIAGNOSTIC (MACAPK_TRACE_LAYOUT=<comma-separated simpleName
    // substrings>): print the FINAL bounds a parent assigns to every view
    // whose class matches. Added 2026-08-13 chasing opencalculator's 24px
    // (12dp) keypad left-offset (emulator first ink x=99, ours x=123) -- the
    // bug turned out to be in LayoutInflater.applyAttributes' padding-family
    // replay order (see the deferredPadding comment there), not in any of
    // TableLayout/TableRow/LinearLayout/ConstraintLayout's bounds math, which
    // this trace proved correct at every level before the real cause was
    // found one layer down (in the button's PADDING, not its BOUNDS). Kept
    // as a standing tool, same shape as MACAPK_TRACE_ATTR/MACAPK_TRACE_OFFSET:
    // the next "my fix should work but doesn't" layout bug gets the real
    // parent-assigned bounds instead of another guess-and-read cycle.
    private static final String MACAPK_TRACE_LAYOUT = System.getenv("MACAPK_TRACE_LAYOUT");
    private void macapkTraceLayout(int l, int t, int r, int b) {
        if (MACAPK_TRACE_LAYOUT == null) return;
        final String cn = getClass().getSimpleName();
        for (String needle : MACAPK_TRACE_LAYOUT.split(",")) {
            if (!needle.isEmpty() && cn.contains(needle)) {
                System.err.println("[TRACE_LAYOUT] " + cn + " id=" + getId()
                    + " l=" + l + " t=" + t + " r=" + r + " b=" + b
                    + " w=" + (r - l) + " padL=" + getPaddingLeft() + " padR=" + getPaddingRight());
                break;
            }
        }
    }
    public void layout(int l, int t, int r, int b) {
        macapkTraceLayout(l, t, r, b);
        int oldW = this.right - this.left, oldH = this.bottom - this.top;
        // AOSP layout():24409-24412 snapshots the old frame BEFORE setFrame, for the
        // layout-change listeners' old* arguments.
        final int oldL = this.left, oldT = this.top, oldR = this.right, oldB = this.bottom;
        // Our FORCE_LAYOUT analogue, read on entry: AOSP fires the listeners when
        // `changed || (mPrivateFlags & PFLAG_LAYOUT_REQUIRED) != 0`. LAYOUT_REQUIRED
        // is set by a measure() that actually ran onMeasure, which is what a
        // requestLayout()/forceLayout() (FORCE_LAYOUT) produces; we have no
        // measure cache to tell a spec-change re-measure apart, so this is the
        // conservative half: a view that asked for layout, or whose bounds moved.
        final boolean wasLayoutRequested = layoutRequested;
        // The bounds assignment goes through setFrame(), which is where AOSP puts
        // it -- so a subclass that overrides setFrame (the documented way to veto
        // or adjust a layout pass) is consulted instead of being bypassed.
        // setFrame assigns exactly the four fields this line used to assign.
        // Its `changed` result now gates the layout-change listeners (below) as in
        // AOSP; onLayout() is STILL called unconditionally (and with changed=true)
        // rather than gating on `changed` the way AOSP does -- making onLayout
        // conditional would be a real layout change and is NOT part of this edit.
        final boolean changed = setFrame(l, t, r, b);
        int newW = r - l, newH = b - t;
        // Compare the SIZE half only: measuredWidth/Height are AOSP bit masks and
        // a legitimately-zero size that carries MEASURED_STATE_TOO_SMALL is
        // 0x01000000, not 0. Without the mask this "never measured" test silently
        // stops firing for any view a parent squeezed to zero.
        if ((this.measuredWidth & MEASURED_SIZE_MASK) == 0)  this.measuredWidth  = newW;
        if ((this.measuredHeight & MEASURED_SIZE_MASK) == 0) this.measuredHeight = newH;
        onLayout(true, l, t, r, b);
        // AOSP setFrame -> sizeChange: fire onSizeChanged when the size actually
        // changed (custom views build size-dependent state there).
        if (newW != oldW || newH != oldH) macapkDispatchSizeChanged(newW, newH, oldW, oldH);
        // AOSP layout():24434-24444, after onLayout:
        //     ListenerInfo li = mListenerInfo;
        //     if (li != null && li.mOnLayoutChangeListeners != null) {
        //         ArrayList<OnLayoutChangeListener> listenersCopy =
        //                 (ArrayList<OnLayoutChangeListener>)li.mOnLayoutChangeListeners.clone();
        //         ...  listenersCopy.get(i).onLayoutChange(this, l, t, r, b, oldL, oldT, oldR, oldB);
        // inside the `changed || LAYOUT_REQUIRED` block, so an unchanged view that did
        // not ask for layout does NOT fire (our traversal re-lays-out the tree more
        // often than AOSP's, and firing every pass would re-run a listener that
        // itself requests layout every frame). The list is null for nearly every
        // view, and always null under MACAPK_LAYOUT_CHANGE_LISTENERS=0.
        if (mOnLayoutChangeListeners != null && (changed || wasLayoutRequested)) {
            macapkDispatchLayoutChange(l, t, r, b, oldL, oldT, oldR, oldB);
        }
        // v1.0.108: AOSP-correct -- layout completion clears the
        // FORCE_LAYOUT flag so isLayoutRequested returns false until
        // a subsequent requestLayout. ConstraintLayout's measure
        // dirty-check relies on this. AOSP clears it AFTER the listeners (so a
        // listener still reads isLayoutRequested()==true and isLaidOut()==false on
        // a first layout, and a requestLayout() on THIS view from inside its own
        // listener is absorbed: AOSP records it via requestLayoutDuringLayout, but
        // ViewRootImpl.getValidLayoutRequesters (android14 :4336-4338) keeps a
        // first-pass requester only while it still has PFLAG_FORCE_LAYOUT, which
        // this very line's AOSP twin has just cleared).
        layoutRequested = false;
        // AOSP layout() sets PFLAG3_IS_LAID_OUT here and never clears it; see
        // isLaidOut() above.
        mIsLaidOut = true;
    }
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    /** Fire the layout-change listeners over a COPY of the list, as AOSP does:
     *  listeners legally remove themselves from inside the callback (androidx
     *  doOnNextLayout does exactly that), and a live iteration would throw
     *  ConcurrentModificationException.
     *
     *  DEVIATION, deliberate: each call is guarded. AOSP lets a throw escape and
     *  kill the app; here a throw from inside layout() would abort the rest of the
     *  tree's layout pass and leave siblings unpositioned, which is how one gap in
     *  our framework used to blank a whole window. The throw is reported loudly
     *  (GfxTrace.caught: once per site+class+message, with the stack), matching the
     *  ViewTreeObserver / draw-pass resilience contract, so it cannot hide. */
    @SuppressWarnings("unchecked")
    private void macapkDispatchLayoutChange(int l, int t, int r, int b,
                                            int oldL, int oldT, int oldR, int oldB) {
        final java.util.ArrayList<OnLayoutChangeListener> copy =
                (java.util.ArrayList<OnLayoutChangeListener>) mOnLayoutChangeListeners.clone();
        final int n = copy.size();
        for (int i = 0; i < n; ++i) {
            final OnLayoutChangeListener li = copy.get(i);
            try {
                li.onLayoutChange(this, l, t, r, b, oldL, oldT, oldR, oldB);
            } catch (Throwable err) {
                com.macapk.loader.GfxTrace.caught("onLayoutChange:"
                        + (li == null ? "null" : li.getClass().getName()), err);
            }
        }
    }
    /** AOSP's mMeasuredWidth / mMeasuredHeight are BIT MASKS, not plain sizes:
     *  the low 24 bits (MEASURED_SIZE_MASK) are the size and the high 8
     *  (MEASURED_STATE_MASK) carry MEASURED_STATE_TOO_SMALL. resolveSizeAndState
     *  sets that bit, and every AndroidX container passes its result straight to
     *  setMeasuredDimension -- so the raw field can and does carry state bits, and
     *  the accessors have to mask. See resolveSizeAndState's COUPLED CHANGE note. */
    protected int measuredWidth, measuredHeight;
    /** AOSP View.java:16484 `return mMeasuredWidth & MEASURED_SIZE_MASK;` */
    public int getMeasuredWidth()  { return measuredWidth & MEASURED_SIZE_MASK; }
    /** AOSP View.java:16512 `return mMeasuredHeight & MEASURED_SIZE_MASK;` */
    public int getMeasuredHeight() { return measuredHeight & MEASURED_SIZE_MASK; }
    /** API-1 measure protocol. Real Android extracts size + mode from
     *  the MeasureSpec ints and asks the subclass to compute its own
     *  size; the subclass then calls setMeasuredDimension. We give
     *  a tolerant default: take the AT_MOST / EXACTLY size, fall back
     *  to 0 for UNSPECIFIED. Many AOSP onMeasure overrides call
     *  super.measure() expecting setMeasuredDimension to be valid
     *  afterwards. */
    public void measure(int wSpec, int hSpec) {
        // Diagnostic (env-gated): trace which classes the measure
        // recursion reaches, verifying the pass flows through app
        // containers (ConstraintLayout etc.) and with what specs.
        if (ENV_MACAPK_TRACE_MEASURE != null) {
            try {
                Class<?> log = Class.forName("com.macapk.loader.NativeLog");
                log.getMethod("i", String.class, String.class).invoke(null,
                    "Mac.apk.Measure",
                    getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " w=" + MeasureSpec.toString(wSpec)
                    + " h=" + MeasureSpec.toString(hSpec));
            } catch (Throwable ignored) {}
        }
        // v1.0.841: self-heal a STALE `macapkAttached==false` before onMeasure
        // runs. AOSP's real invariant is that a view is ALWAYS attached by the
        // time it is measured -- ViewRootImpl.performTraversals only measures a
        // hierarchy that was already fully dispatch-attached, so real onMeasure
        // overrides rely on it unconditionally. androidx AbstractComposeView is
        // a concrete example: per the androidx-main source, its onMeasure calls
        // ensureCompositionCreated() as an "unconditional" safety net (in case
        // onAttachedToWindow's own call raced an owner), and that path reads
        // View.windowRecomposer, which androidx-main
        // (compose/ui/ui/.../WindowRecomposer.android.kt) guards with
        // `checkPrecondition(isAttachedToWindow) { "Cannot locate
        // windowRecomposer; View $this is not attached to a window" }` -- on
        // real Android this branch is unreachable.
        //
        // Ours can go stale: ViewGroup.addView only recurses
        // macapkDispatchAttachedToWindow into a NEW child when the immediate
        // parent isAttachedToWindow() AT THAT INSTANT (ViewGroup.java addView,
        // matching AOSP's own addViewInner gate) -- so a child added to a
        // container a moment before that container itself attaches (or added
        // through a library's own bookkeeping, e.g. a RecyclerView/ViewPager2
        // page host) never gets the recursive dispatch its parent's LATER
        // attach walk assumes already happened. The leaf's macapkAttached then
        // reads false forever, even though every ancestor is genuinely
        // attached. Measured effect (v1.0.833->840 tree): androidx Compose's
        // "Cannot locate windowRecomposer" IllegalStateException, thrown once
        // and silently re-thrown every subsequent measure pass (warn-once
        // guard below) -- ensureCompositionCreated() never succeeds, so the
        // hosted composition never builds. Confirmed on the SAME class
        // (androidx.compose.ui.platform.AndroidComposeView / its ComposeView
        // host) in TWO apps: Wikipedia's Explore feed (ComposeView inside
        // HomeFragment inside ViewPager2$RecyclerViewImpl) and Reddit's
        // RedditComposeView (both the welcome screen's and MainActivity's
        // navstack host, inside a plain FrameLayout) -- different concrete
        // container chains, same desync shape, so this is a ViewGroup-level
        // gap, not a RecyclerView-specific or per-APK one.
        //
        // Repair it at the one point AOSP guarantees the desync cannot exist:
        // if OUR flag says unattached but the immediate parent is genuinely
        // attached, run the same dispatch addView would have run.
        // macapkDispatchAttachedToWindow() is idempotent (returns immediately
        // if already attached) and recurses into this view's OWN children, so
        // it is a no-op for every already-correct view and, for a desynced
        // one, catches up the whole subtree in the natural top-down measure
        // order (a parent's measure() self-heals the parent before its
        // onMeasure() measures its children, so a multi-generation desync
        // unwinds one level per traversal step).
        if (!macapkAttached) {
            ViewParent p = getParent();
            // ...EXCEPT while that parent is inside its OWN attach dispatch
            // (ViewGroup.macapkPreventDispatchAttach, AOSP
            // FLAG_PREVENT_DISPATCH_ATTACHED_TO_WINDOW): this view was added during
            // it and is deliberately not attached yet -- the parent's children loop
            // attaches it right after the parent's listeners, as on AOSP. A measure()
            // run from inside that window must not attach it early (AOSP has no
            // self-heal at all). The flag is only ever set when
            // MACAPK_PREVENT_DISPATCH_ATTACH is on, so =0 keeps the old behaviour.
            if (p instanceof View pv && pv.isAttachedToWindow()
                    && !(p instanceof ViewGroup pg && pg.macapkPreventDispatchAttach)) {
                macapkDispatchAttachedToWindow();
            }
        }
        // v1.0.124: AOSP-correct -- resolve RTL properties BEFORE onMeasure.
        // CL's LayoutParams.resolveLayoutDirection translates startToEnd
        // -> mResolvedLeftToRight, which CL.applyConstraintsFromLayoutParams
        // reads during the solver setup. Without this call, CL's
        // solver never sees the start/end anchors and leaves widget.X=0,
        // so name + summary TextViews render at x=0 overlapping the
        // card's icon. (Previously this was driver-side only because
        // re-layout cascade -- now safe because RV's natural dispatch
        // is the primary trigger, not our cascade.)
        try { resolveRtlPropertiesIfNeeded(); } catch (Throwable ignored) {}
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        // v1.0.438: RESILIENT measure -- a child whose onMeasure throws must NOT
        // abort the parent's whole measure pass (which leaves the ENTIRE content
        // tree 0x0 -> totally blank). The trigger: androidx AbstractComposeView.
        // onMeasure calls ensureCompositionCreated(), which throws
        // "ViewTreeLifecycleOwner not found" when the owner is not yet reachable
        // (Conductor's detached-container attach races our measure); that
        // exception unwound through every ancestor onMeasure up to the driver,
        // so Reddit's whole IntroductionActivity content (chrome + welcome) read
        // 0x0. Containing it here lets the rest of the tree measure+render, and a
        // LATER measure pass retries the throwing view's ensureCompositionCreated
        // once the owner is reachable (onMeasure is the natural retry point).
        // Mirrors the resilient per-node DRAW guard (ViewGroup.dispatchDraw).
        macapkMeasuredDimensionSet = false;
        try {
            onMeasure(wSpec, hSpec);
        } catch (Throwable t) {
            if (warnedMeasureThrowClasses.add(getClass().getName())) {
                com.macapk.loader.NativeLog.w("View",
                    "onMeasure threw on " + getClass().getName() + ": " + t
                    + " -- measuring as " + w + "x" + h + ", tree continues");
            }
            // A ComposeView that threw because its owner wasn't reachable should
            // retry composition creation on a later pass; queue it.
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("ViewTreeLifecycleOwner not found")
                || msg.contains("ViewTreeSavedStateRegistryOwner not found")
                || msg.contains("ViewTreeViewModelStoreOwner not found")
                // v1.0.1047: "Cannot locate windowRecomposer" is the SAME class of
                // failure -- the composition could not be created because the
                // owner was not reachable yet -- but it was the one message that
                // fell through to the diagnostic counter WITHOUT queueing a retry.
                // So the case we actually observe in the wild (Thunderbird,
                // Aurora and Signal all hit this exact string) was the only one
                // that never got a second chance.
                || msg.contains("Cannot locate windowRecomposer")) {
                macapkRegisterComposeAttachRetry(this);
            }
            // v1.0.843 DIAGNOSTIC (docs/compose-attach-desync_2026-07-31.md): the
            // v1.0.841 self-heal above only walks ONE level (this view's immediate
            // parent). warnedMeasureThrowClasses logs this exact exception exactly
            // ONCE per class for the whole process, so the log alone cannot tell
            // "healed after frame 1" from "never heals, refires every measure pass
            // forever" -- see docs/signal-compose-blank_2026-07-31.md OPEN
            // QUESTIONS #1. Count it for real, and dump the ancestor chain the
            // self-heal just failed to climb.
            if (msg.contains("Cannot locate windowRecomposer")) {
                macapkTraceWindowRecomposerFail(t);
            }
            setMeasuredDimension(w, h);
        }
        // Fallback for an onMeasure that never called setMeasuredDimension.
        //
        // WHY THE `macapkMeasuredDimensionSet` GUARD IS LOAD-BEARING (fixed
        // 2026-07-29): this used to be an UNCONDITIONAL
        //     if (measuredWidth == 0)  measuredWidth  = w;
        //     if (measuredHeight == 0) measuredHeight = h;
        // which promotes a LEGITIMATE zero measurement to the full spec size. An
        // empty wrap_content child measuring itself 0 is AOSP-legal and common,
        // and inflating it to the whole parent then displaces every sibling --
        // measured effect: Sgt Puzzles' `game_view` was pushed to
        // y in [-1644,-8], i.e. entirely off-screen. Because the promotion was
        // unconditional it could hit ANY empty wrap_content child, so this was a
        // fleet-wide layout hazard rather than one app's bug.
        //
        // AOSP's own answer to "onMeasure did not set a dimension" is to THROW
        // IllegalStateException ("#onMeasure() did not set the measured dimension
        // by calling setMeasuredDimension()"), gated on PFLAG_MEASURED_DIMENSION_SET.
        // We keep the spec-size fallback instead of throwing -- an exception here
        // would abort the parent's measure pass, which is the very failure the
        // resilient-measure guard above exists to prevent -- but we now gate it on
        // the same signal AOSP gates its throw on, so a deliberate 0 survives.
        if (!macapkMeasuredDimensionSet) {
            // Mask: see the note in layout(). The raw field is an AOSP bit mask.
            if ((measuredWidth & MEASURED_SIZE_MASK) == 0)  measuredWidth  = w;
            if ((measuredHeight & MEASURED_SIZE_MASK) == 0) measuredHeight = h;
        }
        // Targeted compose-measure trace (MACAPK_TRACE_COMPOSE_FALLBACK=1):
        // log measure of any ComposeView/AndroidComposeView (the renamed
        // AndroidComposeView is androidx.compose.ui.platform.c). Confirms
        // whether the Compose host's onMeasure runs (which sets the Compose
        // root Constraints) vs being sized only via layout().
        if (sComposeMeasureTrace) {
            String cn = getClass().getName();
            // Name-suffix shape only, no app package literals. A previous
            // `startsWith("com.reddit.screen.RedditComposeView")` clause was
            // removed: that class name already ENDS with "ComposeView", so the
            // first clause matched it and the app-specific one could never be
            // the deciding test. If some app ever ships a host whose name does
            // not end in ComposeView, the generic fix is a superclass check
            // against AbstractComposeView, never a package string.
            if (cn.endsWith("ComposeView") || cn.equals("androidx.compose.ui.platform.c")
                || cn.endsWith("ScreenContainerView")) {
                try {
                    Class<?> log = Class.forName("com.macapk.loader.NativeLog");
                    log.getMethod("i", String.class, String.class).invoke(null, "Mac.apk.ComposeMeasure",
                        cn + " measure w=" + MeasureSpec.toString(wSpec) + " h=" + MeasureSpec.toString(hSpec)
                        + " -> " + measuredWidth + "x" + measuredHeight);
                } catch (Throwable ignored) {}
            }
        }
    }
    private static final boolean sComposeMeasureTrace =
        "1".equals(System.getenv("MACAPK_TRACE_COMPOSE_FALLBACK"));
    /** AOSP `View.onMeasure` (aosp-mirror android-11.0.0_r48
     *  core/java/android/view/View.java:25540-25543), verbatim:
     *      setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
     *              getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));
     *
     *  This used to be `setMeasuredDimension(getSize(wSpec), getSize(hSpec))`,
     *  which ignores the MODE. Under EXACTLY and AT_MOST the two agree (AOSP's
     *  getDefaultSize returns specSize for both), so the only behaviour change is
     *  UNSPECIFIED -- where AOSP collapses a bare View to its SUGGESTED MINIMUM
     *  (max of setMinimumWidth and the background's intrinsic minimum) and we were
     *  instead handing it the whole spec size, which under UNSPECIFIED is usually
     *  the viewport. Measured by fwprobe LAY.view.*.un200: reference w=0/40/120/80,
     *  ours 200 in every configuration.
     *
     *  Where UNSPECIFIED actually reaches a bare View: the scroll axis of
     *  ScrollView / HorizontalScrollView, RecyclerView / ListView item measurement,
     *  and any wrap_content child of a container that itself got UNSPECIFIED. In
     *  all of those, "take the whole spec size" makes an empty View swallow the
     *  viewport -- the same class of bug as the unconditional
     *  measuredWidth==0 -> specSize promotion that was removed on 2026-07-29. */
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), wSpec),
                             getDefaultSize(getSuggestedMinimumHeight(), hSpec));
    }
    /** AOSP's PFLAG_MEASURED_DIMENSION_SET, as a plain boolean. `measure()`
     *  clears it before calling onMeasure and checks it after, so it can tell
     *  "this view never set a dimension" (a broken onMeasure) apart from "this
     *  view deliberately measured itself 0" (an empty wrap_content child, which
     *  is AOSP-legal). See the fallback in measure(). */
    private boolean macapkMeasuredDimensionSet;

    protected final void setMeasuredDimension(int w, int h) {
        this.measuredWidth = w;
        this.measuredHeight = h;
        this.macapkMeasuredDimensionSet = true;
    }
    // AOSP View.MeasureSpec (core/java/android/view/View.java:31355,
    // android16-release) is `public static class`, NOT final. Same reasoning as
    // AnimatorSet.Builder: a subclass is a hard resolution failure that
    // -Xverify:none does not mask. Deferred from the v1.0.1040 sweep because the
    // animation lane owned this file; closed here.
    public static class MeasureSpec {
        public static final int UNSPECIFIED = 0;
        public static final int EXACTLY     = 1 << 30;
        public static final int AT_MOST     = 2 << 30;
        public static int makeMeasureSpec(int size, int mode) {
            return (size & 0x3fffffff) | mode;
        }
        public static int getSize(int spec) { return spec & 0x3fffffff; }
        public static int getMode(int spec) { return spec & 0xc0000000; }
        /** AOSP MeasureSpec.toString (View.java:28223-28240), ported verbatim.
         *  Two things were wrong with the old formatter and both matter,
         *  because this string is what every layout bug report in the wild is
         *  pasted from and what a custom onMeasure logs:
         *    - the FORMAT is "MeasureSpec: MODE SIZE", not "MeasureSpec(size, MODE)";
         *    - the fourth mode value (0xc0000000), which makeMeasureSpec never
         *      produces but arithmetic on specs does, falls through to printing
         *      the RAW mode int (-1073741824). The old ternary chain silently
         *      relabelled it AT_MOST, i.e. it reported a corrupt spec as a
         *      valid one (fwprobe VW.st.mstostring.rawc000012c / rawffffffff). */
        public static String toString(int spec) {
            int mode = getMode(spec);
            int size = getSize(spec);
            StringBuilder sb = new StringBuilder("MeasureSpec: ");
            if (mode == UNSPECIFIED)   sb.append("UNSPECIFIED ");
            else if (mode == EXACTLY)  sb.append("EXACTLY ");
            else if (mode == AT_MOST)  sb.append("AT_MOST ");
            else                       sb.append(mode).append(" ");
            sb.append(size);
            return sb.toString();
        }
    }
    /** v1.0.108: AOSP-correct requestLayout. Marks this view + every
     *  ancestor as needing a fresh measure-and-layout pass. Containers
     *  like ConstraintLayout check `child.isLayoutRequested()` during
     *  measure to decide whether to rebuild internal state -- without
     *  proper bubbling, parent containers never know their children
     *  changed and skip the heavy update path. */
    public void requestLayout()  {
        macapkTraceComposeInsert("requestLayout");
        try { if (com.macapk.loader.ComposeViewFallback.SHEET_PROBE_ON
            && com.macapk.loader.ComposeViewFallback.macapkIsComposeHost(this))
            com.macapk.loader.ComposeViewFallback.sAcvReqLayout.incrementAndGet(); } catch (Throwable ignored) {}
        macapkTraceOffset("requestLayout", 0);
        layoutRequested = true;
        ViewParent p = parent;
        if (p instanceof View vp && !vp.layoutRequested) vp.requestLayout();
        // Tier 3 M6: ask ViewRootImpl for a traversal (measure->layout->draw) on
        // the next frame. Real Android's View.requestLayout bubbles to the
        // ViewRootImpl, which calls scheduleTraversals(). Gated so flag-off keeps
        // the historical "driver traverses every frame regardless" behavior.
        // scheduleLayout (NOT schedule): requestLayout needs a MEASURE+LAYOUT pass,
        // not just a redraw -- it sets ViewRootImpl.mLayoutRequested so the driver
        // does the full traversal (vs invalidate()'s draw-only frame). Mirrors
        // AOSP requestLayout -> mLayoutRequested = true; scheduleTraversals().
        if (android.os.Looper.MACAPK_REAL_LOOPER) ViewRootImpl.scheduleLayout();
    }
    /** AOSP View.forceLayout(): marks THIS view as needing layout +
     *  invalidation, WITHOUT bubbling up the parent chain (the key
     *  difference from requestLayout). Unity's
     *  UnityPlayerForActivityOrService constructor calls it directly
     *  on targetSdk>=30 builds -- a path the binary-manifest parser
     *  correctly unlocked by reading the real targetSdk (the apktool
     *  text decode reported 0, hiding this call). ViewGroup overrides
     *  to also forceLayout its children. */
    public void forceLayout() {
        layoutRequested = true;
    }
    // Tier 3 M6: invalidate() schedules a draw traversal on the next frame
    // (AOSP View.invalidate -> ViewRootImpl.invalidate -> scheduleTraversals).
    // Gated: flag-off stays a no-op (the driver redraws every frame anyway).
    public void invalidate()     { macapkTraceComposeInsert("invalidate"); try { if (com.macapk.loader.ComposeViewFallback.SHEET_PROBE_ON && com.macapk.loader.ComposeViewFallback.macapkIsComposeHost(this)) com.macapk.loader.ComposeViewFallback.sAcvInval.incrementAndGet(); } catch (Throwable ignored) {} if (android.os.Looper.MACAPK_REAL_LOOPER) ViewRootImpl.schedule(); macapkInvalidateParent(); }
    /** MACAPK_INVALIDATE_PARENT (default ON, =0 the old schedule-only invalidate):
     *  AOSP View.invalidateInternal (android14 View.java:20733-20780) reports the
     *  damage to the parent, `p.invalidateChild(this, damage)`, whose
     *  hardware-accelerated branch is `onDescendantInvalidated(child, child)` up
     *  to ViewRootImpl. Ours only scheduled a frame, so a ViewGroup that keeps
     *  its own cached drawing of a child never heard that the child changed --
     *  Compose's AndroidViewHolder, whose onDescendantInvalidated /
     *  invalidateChildInParent are what re-record the GraphicsLayer it draws the
     *  View into (invalidateOrDefer -> layoutNode.invalidateLayer). An
     *  AndroidView { WebView } therefore replayed the recording from its FIRST
     *  draw forever (mGradani NIAS login white; Mac.apk-Releases issue 2;
     *  debug/wvtest/compose_shape). Same guards as AOSP: skipInvalidate (not
     *  VISIBLE, no animation), attached, non-empty bounds. Off the UI thread
     *  (our postInvalidate calls invalidate directly) the walk is posted to it,
     *  as AOSP's postInvalidate posts. */
    static final boolean MACAPK_INVALIDATE_PARENT =
            !"0".equals(System.getenv("MACAPK_INVALIDATE_PARENT"));
    private void macapkInvalidateParent() {
        if (!MACAPK_INVALIDATE_PARENT) return;
        final ViewParent p = parent;
        if (p == null || !macapkAttached) return;
        if (getVisibility() != VISIBLE && getAnimation() == null) return;
        if (right <= left || bottom <= top) return;
        if (!android.os.Looper.getMainLooper().isCurrentThread()) {
            if (android.os.Looper.MACAPK_REAL_LOOPER) post(this::macapkInvalidateParent);
            return;
        }
        try { p.invalidateChild(this, null); } catch (Throwable t) {
            com.macapk.loader.GfxTrace.caught("View.invalidate parent", t);
        }
    }
    // v1.0.410 DIAGNOSTIC: log requestLayout/invalidate on an AndroidComposeView host
    // with a short stack, so we can see whether a Compose content INSERT
    // (insertAt->attach->onRequestMeasure->requestLayout, AOSP-guaranteed) ever
    // reaches the host -- the decisive fork for Reddit's blank (empty change-list
    // upstream vs insert-reached-but-measure-flush-dropped). Gated off by default.
    private void macapkTraceComposeInsert(String which) {
        try {
            if (!com.macapk.loader.ComposeViewFallback.MACAPK_TRACE_COMPOSE_INSERT) return;
            if (!com.macapk.loader.ComposeViewFallback.macapkIsComposeHost(this)) return;
            // Only log layout/place-sourced events (the populated-content signal),
            // and capture the host's identity + attach/visibility/size so we can
            // tell WHICH c instance holds the content and whether it is in the
            // visible, drawn hierarchy.
            boolean attached = macapkAttached;
            android.view.ViewParent par = parent;
            // Only log when 0x0 (the collapse signal) to cut noise, and walk the
            // FULL ancestor chain so we can see WHICH view collapses to 0x0.
            int w = getRight()-getLeft(), h = getBottom()-getTop();
            if (w != 0 && h != 0) return;
            StringBuilder sb = new StringBuilder("ACV." + which + " host=@"
                + Integer.toHexString(System.identityHashCode(this))
                + " attached=" + attached + " vis=" + getVisibility()
                + " size=" + w + "x" + h + " lp=" + lpStr() + " ANCESTRY:\n");
            android.view.ViewParent p2 = parent; int depth = 0;
            while (p2 instanceof View av && depth < 12) {
                sb.append("  ").append(av.getClass().getName())
                  .append(" ").append(av.getRight()-av.getLeft()).append("x").append(av.getBottom()-av.getTop())
                  .append(" lp=").append(av.lpStr())
                  .append(" measured=").append(av.measuredWidth).append("x").append(av.measuredHeight)
                  .append(" layoutReq=").append(av.layoutRequested).append('\n');
                p2 = av.getParent(); depth++;
            }
            com.macapk.loader.NativeLog.i("Mac.apk.ComposeInsert", sb.toString());
        } catch (Throwable ignored) {}
    }
    // v1.0.411 DIAGNOSTIC: trace attach/detach transitions on Compose hosts
    // (AbstractComposeView subclasses like RedditComposeView, and the
    // AndroidComposeView). A DETACH on a host whose ViewCompositionStrategy is
    // DisposeOnDetachedFromWindow disposes the composition (clearing its
    // LayoutNode tree); a subsequent ATTACH recreates it. Repeated dispose/
    // recreate -- or a DETACH with no following ATTACH -- explains "content
    // composes (resources load) but the live tree is empty." Gated off by default.
    private void macapkTraceComposeAttach(String which, boolean wasAttached) {
        try {
            if (!com.macapk.loader.ComposeViewFallback.MACAPK_TRACE_COMPOSE_INSERT) return;
            String cn = getClass().getName();
            // See the note in onMeasure's trace: the removed
            // com.reddit.screen.RedditComposeView clause was redundant, because
            // that name already ends with "ComposeView".
            boolean host = cn.endsWith("ComposeView") || cn.equals("androidx.compose.ui.platform.c")
                || cn.endsWith("AbstractComposeView");
            if (!host) return;
            com.macapk.loader.NativeLog.i("Mac.apk.ComposeAttach",
                which + " " + cn + "@" + Integer.toHexString(System.identityHashCode(this))
                + " wasAttached=" + wasAttached + " vis=" + getVisibility()
                + " parent=" + (parent == null ? "null" : parent.getClass().getName()));
        } catch (Throwable ignored) {}
    }
    // v1.0.843 DIAGNOSTIC: exact occurrence count + ancestor-chain dump for the
    // "Cannot locate windowRecomposer" measure-throw counted above. This answers
    // docs/signal-compose-blank_2026-07-31.md OPEN QUESTIONS #1 -- "does the
    // v1.0.841 self-heal catch this after one frame, or does it never catch it" --
    // which the pre-existing warn-once NativeLog.w cannot answer (it prints the
    // SAME "1 occurrence" whether the real count is 1 or 100000).
    //
    // AtomicLong: measure() can in principle run off more than one thread
    // (ViewCanvasDriver's per-frame drive plus WindowOverlayStack.measureAndLayout
    // both call View.measure()), so a plain long could lose increments to a race;
    // this counter needs to be exact, not approximate.
    private static final java.util.concurrent.atomic.AtomicLong
        sWindowRecomposerFailCount = new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean sWindowRecomposerHookRegistered = false;
    private void macapkTraceWindowRecomposerFail(Throwable t) {
        long n = sWindowRecomposerFailCount.incrementAndGet();
        // Register the final-total dump lazily (off the failure path itself, so a
        // healthy run that never hits this pays nothing).
        // ⚠ CORRECTED 2026-09-24: this used to say raise(SIGTERM) runs Java
        // shutdown hooks on this VM, so the FINAL line would print on every
        // harness run. FALSE for the shipped runtime. That reading came from
        // debug/jvm_shutdown_hook_probe/, which booted its VM WITHOUT -Xrs; the
        // runtime boots WITH it (jvm_loader.cpp), and under -Xrs HotSpot refuses
        // TERM (JVM_RegisterSignal, ReduceSignalUsage) and nothing in runtime/src
        // handles it, so SIGTERM kills the process with no hooks. Re-measured
        // with -Xrs: exit -> no hook, DestroyJavaVM -> hook, SIGTERM -> no hook.
        // So this line prints only when the VM is destroyed normally (a clean
        // return from main) or a genuine java.lang.Runtime.exit runs; _exit (window close / Cmd-Q), Runtime.halt (an
        // app's System.exit), SIGTERM and SIGKILL (debug/sweep_one.sh's
        // TERM-then-KILL, scripts/sweep.sh's pkill -9) all skip it. Read the
        // sampled "#N" lines below for the count instead of relying on it.
        if (!sWindowRecomposerHookRegistered) {
            synchronized (View.class) {
                if (!sWindowRecomposerHookRegistered) {
                    sWindowRecomposerHookRegistered = true;
                    try {
                        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                            com.macapk.loader.NativeLog.i("Mac.apk.ComposeAttach",
                                "windowRecomposer-fail FINAL total=" + sWindowRecomposerFailCount.get())));
                    } catch (Throwable ignored) {}
                }
            }
        }
        // Sampled full dump: first 5 occurrences, then every 500th (mirrors the
        // angle_glue.cpp AngleBeginFrame convention -- first few in full, then
        // periodic -- so a run that fails every frame doesn't flood the log). The
        // printed #N is the thing to trust, same as that convention: a `grep -c`
        // over this tag only counts SAMPLED lines, not real occurrences. The
        // shutdown-hook FINAL line above prints ONLY on a clean DestroyJavaVM or
        // a genuine java.lang.System.exit, which the shipped runtime almost never
        // reaches (see the correction above: _exit, Runtime.halt, SIGTERM under
        // -Xrs and SIGKILL all skip it), so do not wait for it. The highest #N
        // sampled here is the count to trust (a lower bound, at most 499 short).
        // These lines are also for seeing WHERE the chain breaks.
        if (n <= 5 || (n % 500) == 0) {
            StringBuilder sb = new StringBuilder("windowRecomposer-fail #" + n + " "
                + getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(this))
                + " macapkAttached=" + macapkAttached
                + " isAttachedToWindow=" + isAttachedToWindow()
                + " vis=" + getVisibility() + " ANCESTRY:\n");
            ViewParent p = getParent();
            int depth = 0;
            while (p != null && depth < 16) {
                if (p instanceof View pv) {
                    sb.append("  [").append(depth).append("] ").append(pv.getClass().getName())
                      .append(" @").append(Integer.toHexString(System.identityHashCode(pv)))
                      .append(" macapkAttached=").append(pv.macapkAttached)
                      .append(" isAttachedToWindow=").append(pv.isAttachedToWindow())
                      .append(" vis=").append(pv.getVisibility())
                      .append('\n');
                } else {
                    // No concrete class in this tree currently implements ViewParent
                    // without also being a View (see the isShown() comment above,
                    // "Mac.apk has no such ViewParent-implementing window-root
                    // object") -- if this branch ever prints, that has changed and
                    // is itself a finding.
                    sb.append("  [").append(depth).append("] NON-VIEW ViewParent: ")
                      .append(p.getClass().getName()).append('\n');
                }
                p = p.getParent();
                depth++;
            }
            if (depth == 0) sb.append("  (getParent() == null -- this view has no parent at all)\n");
            else if (depth >= 16) sb.append("  (chain truncated at 16 -- possible cycle)\n");
            com.macapk.loader.NativeLog.i("Mac.apk.ComposeAttach", sb.toString());
        }
    }
    public void invalidate(Rect dirty) { invalidate(); }
    public void invalidate(int l, int t, int r, int b) { invalidate(); }
    public void postInvalidate() { invalidate(); }

    // Standard View.draw / onDraw entry points. Real Android's
    // ViewRoot calls View.draw which then calls onDraw with the
    // current Canvas. We don't have a full layout/draw pass, but
    // exposing onDraw lets apps that subclass View (e.g. Sgt
    // Puzzles' GameView extends View and overrides onDraw) be
    // driven by the framework. The activity's per-frame driver
    // (see com.macapk.loader.ViewCanvasDriver) calls draw(canvas)
    // each vsync with a Bitmap-backed Canvas, then blits to the
    // Metal layer via NativeCocoa.presentBitmap.
    //
    // Per AOSP frameworks/base/core/java/android/view/View.java
    // (View.draw), the official order is:
    //   1. draw the background
    //   2. save the canvas's layer to prepare for fading
    //   3. draw view's content (onDraw)
    //   4. draw children (dispatchDraw)
    //   5. draw fade effect + restore the layer
    //   6. draw decorations (scrollbars, etc.)
    // We implement (1), (3), (4) -- enough for opaque solid
    // backgrounds and standard widget rendering. Steps (2),(5),(6)
    // are display polish that no test APK has needed yet.
    public void draw(android.graphics.Canvas canvas) {
        if (canvas == null) return;
        // AOSP: when getClipToOutline() is true the view masks its
        // background + content + children to the outline produced by
        // its ViewOutlineProvider (rebuildOutline -> getOutline, then
        // a clip during draw). The calculator keypad buttons set
        // clipToOutline + a round-rect provider with radius=min(w,h)/2
        // to render as CIRCLES; without honoring it every button
        // paints its tint as a full square. Gated on the AOSP outline
        // contract (clipToOutline && provider && non-empty clippable
        // outline) so only opt-in views are affected; the static
        // BACKGROUND/BOUNDS providers leave the outline empty and
        // short-circuit. MACAPK_CLIP_OUTLINE=0 reverts.
        int macapkOutlineSave = -1;
        if (mClipToOutline && outlineProvider != null
                && !"0".equals(ENV_MACAPK_CLIP_OUTLINE)) {
            int ow = right - left, oh = bottom - top;
            if (ow > 0 && oh > 0) {
                try {
                    android.graphics.Outline o = new android.graphics.Outline();
                    outlineProvider.getOutline(this, o);
                    if (!o.isEmpty() && o.canClip()) {
                        android.graphics.Path clip = o.macapkClipPath();
                        if (clip != null) {
                            macapkOutlineSave = canvas.save();
                            canvas.clipPath(clip);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        // v1.0.121: paint Material widget chrome (rounded card,
        // rounded-square FAB, opaque bottom-nav) by class-name
        // detection BEFORE the backgroundColor block. This adds the
        // visible card / FAB / nav chrome that's missing because the
        // APK's MaterialShapeDrawable path doesn't render through
        // our Canvas. macapkPaintMaterialChrome inspects bounds
        // and the View's class name; it's a no-op for non-Material
        // widgets and for views with 0x0 bounds.
        //
        // lane layout, 2026-08-01: SKIPPED when the view has a real
        // background Drawable, which now actually paints (see the
        // backgroundDrawable block below). The synthetic painter is a
        // stand-in for a drawable we refused to draw; running both would
        // paint approximate chrome underneath the real thing. AOSP draws
        // the background drawable and nothing else.
        // v1.0.898: ...EXCEPT for a Material button whose fill the inflater
        // positively identified from the app's OWN declared backgroundTint
        // (macapkButtonFill != 0). The reasoning above assumes "there is a
        // drawable, so the drawable will paint it", and for this widget that is
        // false twice over: AOSP's background here is a MaterialShapeDrawable,
        // which our Canvas cannot render at all, and what we actually hold is
        // usually the VectorDrawable STUB that getDrawable hands back for a
        // reference it could not resolve. Measured on AnkiDroid: both intro
        // buttons reported `bg=VectorDrawable` with macapkButtonFill correctly
        // resolved to #2196f3 from `android:backgroundTint="@color/
        // material_blue_500"`, and painted nothing, 0 px against the emulator's
        // 56,983. So for that one case the synthetic chrome IS the stand-in the
        // comment above describes, and the gate has to let it through.
        // ⚠ TRIED AND REVERTED 2026-08-08: narrowing this to
        // `macapkButtonFill != 0 && macapkBackgroundIsUnresolvedStub()`. The
        // reasoning was sound -- with the viewInflaterClass flip ON,
        // opencalculator's 31 keys become MaterialButtons whose background is a
        // REAL `<ripple>` over a rounded `<shape>`, and painting a stadium pill
        // over them took #b4d2e4 from 183,460 to ZERO, so "is there a drawable"
        // is indeed the wrong question and "will it paint" is the right one.
        // But the STUB MARKER does not identify the cases that need the chrome:
        // AnkiDroid's MaterialButtons report `bg=VectorDrawable` and are NOT
        // marked as unresolved stubs, so narrowing the gate took anki from 91.84
        // to 88.82 and its #2196f3 from 57,757 to 0 on the DEFAULT path.
        // The marker is kept (VectorDrawable.macapkUnresolvedStub) because the
        // question it asks is the right one; what is missing is knowing where
        // anki's VectorDrawable comes from, since it is neither our id==0 stub
        // nor something that paints. Settle THAT before narrowing this again.
        if (backgroundDrawable == null
                || (macapkButtonFill != 0 && macapkBackgroundPaintsNothing())) {
            macapkPaintMaterialChrome(canvas);
        }
        // Tint alone paints nothing (AOSP: tint applies TO a background
        // drawable); it only recolors an existing background.
        if (backgroundColor != 0) {
            // v1.0.76: paint in LOCAL coords. Parent ViewGroup's
            // dispatchDraw has already canvas.translate'd by our (left,
            // top); we paint at (0, 0) to (width, height). Was: used
            // absolute (left, top, right, bottom) which double-translated.
            int w = right - left, h = bottom - top;
            if (w <= 0 || h <= 0) {
                // Fall back to canvas extent so children that never
                // got laid out still paint their backgrounds.
                w = canvas.getWidth();
                h = canvas.getHeight();
            }
            android.graphics.Paint bg = backgroundPaint();
            // backgroundTint wins over the base color (SRC_IN over a
            // solid = the tint; see setBackgroundTintList).
            bg.setColor(macapkBackgroundTint != null
                ? macapkBackgroundTint : backgroundColor);
            bg.setStyle(android.graphics.Paint.Style.FILL);
            canvas.drawRect(0, 0, w, h, bg);
        }
        // v1.0.497: AOSP View.draw step 1 -- paint the background DRAWABLE
        // (not just the backgroundColor int above). This was missing: a view
        // whose background is a Drawable (e.g. an inflated <selector>/<shape>
        // or a programmatic StateListDrawable/GradientDrawable) never had it
        // painted, so a filled button with white text on a green
        // StateListDrawable fill rendered white-on-white = invisible
        // (Duolingo's "GET STARTED" JuicyButton).
        //
        // SCOPED to OUR framework drawables (android.graphics.drawable.*):
        // inflated/programmatic ColorDrawable / GradientDrawable /
        // StateListDrawable / LayerDrawable / InsetDrawable / RippleDrawable
        // all render correctly through our Canvas. App-bundled custom
        // backgrounds (Material's MaterialShapeDrawable -- not even present in
        // our framework -- and the like) are DELIBERATELY excluded: they
        // don't render through our Canvas (the very reason
        // macapkPaintMaterialChrome above exists) and drawing them blind would
        // risk black-box / double-paint regressions on the Material protected
        // set. Generic -- no per-APK assumption; keyed on the drawable's
        // framework package, not the app.
        // lane layout, 2026-08-01: DRAW EVERY BACKGROUND DRAWABLE, not just our
        // own framework classes. AOSP's View.draw step 1 is unconditional
        // (`mBackground.draw(canvas)`); the app-package exclusion above was a
        // v1.0.497 GUESS ("their draw() depends on APIs we don't fully support")
        // that was never measured, and it is why every Material widget rendered
        // chrome-less: material's MaterialShapeDrawable is app-bundled, so its
        // fill/stroke/corner painting was skipped entirely and only the
        // synthetic macapkPaintMaterialChrome approximation ever showed.
        // Measured victim: Google Calculator draws every key as a filled circle
        // on the emulator and as a bare glyph on white for us (emulator A/B,
        // docs/canvas-layout-fidelity_2026-08-01.md PHASE 2). The premise was
        // false: MaterialShapeDrawable paints through Canvas.drawPath /
        // drawRoundRect / drawCircle, all of which this Canvas implements.
        // The try/catch still contains a drawable that throws, and a drawable
        // that paints nothing is now a visible bug we can chase rather than a
        // silently-skipped call.
        if (backgroundDrawable != null) {
            try {
                int bw = right - left, bh = bottom - top;
                if (bw > 0 && bh > 0) {
                    // lane layout, 2026-08-02: the background TINT has to reach
                    // the DRAWABLE. AOSP's setBackgroundTintList ->
                    // applyBackgroundTint() -> `mBackground.setTintList(...)`
                    // (SRC_IN); ours consulted macapkBackgroundTint ONLY in the
                    // flat backgroundColor branch above, so a drawable-backed
                    // view painted untinted. Measured victim: OpenCalculator's
                    // 20 keys all rendered the same #eee gray -- their shape
                    // comes from @drawable/circle (which resolves) and their
                    // per-key colour from `android:backgroundTint="?attr"`
                    // (green AC / blue operators / pink equals on the emulator)
                    // which never landed
                    // (docs/canvas-ab-analyst-report_2026-08-02.md item 2a).
                    if (macapkBackgroundTintList != null) {
                        // Pass the FULL list: the drawable resolves it against its
                        // own state (Drawable.setTintList + getColorForState, in
                        // since v1.0.892). Wrapping the already-flattened int in
                        // valueOf() here erased every non-default row at the very
                        // last moment, so a stateful background tint could never
                        // reach a drawable-backed view no matter what set it.
                        try { backgroundDrawable.setTintList(macapkBackgroundTintList); }
                        catch (Throwable ignored) {}
                    } else if (macapkBackgroundTint != null) {
                        try { backgroundDrawable.setTintList(
                            android.content.res.ColorStateList.valueOf(macapkBackgroundTint)); }
                        catch (Throwable ignored) {}
                    }
                    backgroundDrawable.setBounds(0, 0, bw, bh);
                    backgroundDrawable.draw(canvas);
                }
            } catch (Throwable t) {
                // A guest drawable that cannot paint through our Canvas: fall
                // back to the synthetic chrome so the widget is not invisible,
                // and name it once so the gap is diagnosable instead of silent.
                macapkPaintMaterialChrome(canvas);
                macapkTraceBackgroundDrawFail(t);
            }
        }
        // Tier 3 M18: Material ripple, over the background + under the content.
        if (android.os.Looper.MACAPK_REAL_LOOPER) macapkDrawRipple(canvas);
        onDraw(canvas);
        // Diagnostic (MACAPK_TRACE_COMPOSE): for a Compose host view, log
        // its bounds + child count just before its (Compose-overridden)
        // dispatchDraw runs. Empty bounds or zero children means the
        // composition never produced a layout tree to paint.
        if (TRACE_COMPOSE) {
            String cn = getClass().getName();
            if (cn.endsWith("ComposeView") || cn.endsWith("AndroidComposeView")) {
                com.macapk.loader.NativeLog.i("Mac.apk.ComposeDraw",
                    cn + " bounds=[" + left + "," + top + "-" + right + "," + bottom
                    + "] size=" + (right-left) + "x" + (bottom-top)
                    + " attached=" + isAttachedToWindow() + " children="
                    + (this instanceof android.view.ViewGroup ? ((android.view.ViewGroup)this).getChildCount() : 0));
            }
        }
        // v1.0.406: clip a CardView's children to its rounded outline so
        // image/text content can't bleed over the stylistic rounded corners
        // (AOSP MaterialCardView sets clipToOutline + a MaterialShapeDrawable
        // outline; we synthesize the same clip from the corner radius the card
        // chrome already resolved). Gated MACAPK_CARD_CLIP=0.
        int macapkCardClipSave = macapkBeginCardClip(canvas);
        dispatchDraw(canvas);
        if (macapkCardClipSave >= 0) canvas.restoreToCount(macapkCardClipSave);
        // v1.0.101: paint the overlay layer LAST so badge / snackbar /
        // transient drawables sit on TOP of the view's own content +
        // its children. AOSP View.draw step (5)+(6) "draw the fading
        // edges and the scrollbars" is also where overlay drawing
        // happens. Cheap when no overlay was created (the common case).
        if (overlay != null) {
            try { overlay.macapkDraw(canvas); } catch (Throwable ignored) {}
        }
        // AOSP View.draw step 6 -- "draw decorations (foreground, scrollbars)".
        // It runs AFTER the content + children + overlay (which AOSP documents
        // as part of the content, drawn beneath the foreground) and before the
        // default-focus highlight. Without this call android:foreground /
        // setForeground(Drawable) -- the standard way to hang a
        // ?attr/selectableItemBackground ripple on a clickable container -- was
        // accepted and never painted.
        try { onDrawForeground(canvas); } catch (Throwable ignored) {}
        // Balance the outline clip pushed at the top of draw().
        if (macapkOutlineSave >= 0) {
            try { canvas.restoreToCount(macapkOutlineSave); } catch (Throwable ignored) {}
        }
    }

    /** v1.0.129: AOSP-pattern detection for "is this NavigationBarItem
     *  selected". Material's NavigationBarItemView ships two TextView
     *  labels per item (one for inactive typography, one for active);
     *  the SELECTED state is "second visible, first invisible." Falls
     *  back to "find ANY visible TextView and treat the first as the
     *  selected marker" if the tree shape differs from the strict M3
     *  layout (older Material2 BottomNavigationItemView variants).
     *  Returns false if no TextView children at all -- a never-laid-
     *  out item shouldn't render a pill. Generic across Material2 +
     *  Material3 nav-item class hierarchies (called via the macapk-
     *  PaintMaterialChrome dispatch keyed on class-name endsWith). */
    private static final java.util.Set<String> macapkBgDrawFailed =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void macapkTraceBackgroundDrawFail(Throwable t) {
        try {
            String k = t.getClass().getName() + ":" + String.valueOf(t.getMessage());
            if (!macapkBgDrawFailed.add(k)) return;
            com.macapk.loader.NativeLog.w("Mac.apk.View",
                "background drawable draw() failed, using synthetic chrome: " + k);
        } catch (Throwable ignored) {}
    }

    private static boolean macapkIsFrameworkDrawable(android.graphics.drawable.Drawable d) {
        if (d == null) return false;
        try {
            String pkg = d.getClass().getPackage() != null
                ? d.getClass().getPackage().getName() : "";
            return pkg.startsWith("android.")
                || pkg.startsWith("com.macapk.");
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.0.121: paint Material 3 chrome by widget class-name detection.
     *  Each widget gets the AOSP-spec fill + shape + shadow before
     *  the View's own children paint over it.
     *
     *  Detection rules (all class simple-name matching so we work
     *  whether the widget is androidx.cardview / com.google.android.
     *  material / a subclass thereof):
     *    - "*CardView"            -> 12dp rounded white fill + 4px elevation shadow
     *    - "FloatingActionButton" -> 16dp rounded-square teal fill + 6dp shadow
     *    - "BottomNavigationView" / "NavigationBarView" -> opaque surface fill
     *
     *  Real Material draws these via MaterialShapeDrawable, but that
     *  class lives in the APK and depends on shape primitives we
     *  don't fully implement. Painting them here directly is the
     *  observable equivalent.
     *
     *  Skipped if the view already has a non-zero backgroundColor or
     *  if its bounds are 0x0 (unlaid).
     */
    private static volatile boolean materialChromeOff = false;
    private static java.util.concurrent.ConcurrentHashMap<String, Boolean> macapkChromeTraced;
    static {
        materialChromeOff = "0".equals(System.getenv("MACAPK_MATERIAL_CHROME"));
    }
    /** v1.0.121 single-arg entry: read bounds from the View itself,
     *  skip if 0x0 (unlaid view -- chrome at full canvas would
     *  cover everything). Class-name detection means this is a
     *  no-op for non-Material widgets, generic across Material
     *  versions, and never APK-specific. */
    protected void macapkPaintMaterialChrome(android.graphics.Canvas canvas) {
        if (canvas == null) return;
        int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) return; // unlaid -- don't paint full-canvas chrome
        macapkPaintMaterialChrome(canvas, w, h);
    }

    /** v1.0.406: if this view is a (Material)CardView, save the canvas and clip
     *  to the card's rounded outline (radius from shapeAppearanceMediumComponent,
     *  same as the chrome fill) so its children can't overhang the rounded
     *  corners -- AOSP MaterialCardView does this via clipToOutline + a
     *  MaterialShapeDrawable outline. Returns the save count to restore after
     *  dispatchDraw, or -1 if no clip was applied. Gated MACAPK_CARD_CLIP=0. */
    private int macapkBeginCardClip(android.graphics.Canvas canvas) {
        try {
            if (canvas == null) return -1;
            if ("0".equals(ENV_MACAPK_CARD_CLIP)) return -1;
            if (!getClass().getSimpleName().endsWith("CardView")) return -1;
            int w = right - left, h = bottom - top;
            if (w <= 0 || h <= 0) return -1;
            int density = Math.max(1, Math.round(android.util.DisplayMetrics.macapkDeviceDensity()));
            try {
                if (getResources() != null && getResources().getDisplayMetrics() != null
                    && getResources().getDisplayMetrics().density > 0f)
                    density = (int) getResources().getDisplayMetrics().density;
            } catch (Throwable ignored) {}
            float radius = android.content.res.Resources.macapkThemeCornerDp(
                "shapeAppearanceMediumComponent", 12f) * density;
            if (radius <= 0f) return -1;
            android.graphics.Path cp = new android.graphics.Path();
            cp.addRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius,
                android.graphics.Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(cp);
            return save;
        } catch (Throwable t) {
            return -1;
        }
    }
    protected void macapkPaintMaterialChrome(android.graphics.Canvas canvas, int w, int h) {
        if (materialChromeOff || canvas == null || w <= 0 || h <= 0) return;
        String name;
        try { name = getClass().getSimpleName(); } catch (Throwable t) { return; }
        if (name == null || name.isEmpty()) return;
        // v1.0.121 diagnostic: log when chrome path fires per class so
        // we can see which Material widgets actually reach View.draw
        // and which never paint. One-shot per-class so the log stays
        // small. Env-gated to avoid permanent spam.
        if ("1".equals(ENV_MACAPK_TRACE_CHROME)) {
            if (macapkChromeTraced == null) {
                macapkChromeTraced = new java.util.concurrent.ConcurrentHashMap<>();
            }
            if (macapkChromeTraced.putIfAbsent(name, Boolean.TRUE) == null) {
                com.macapk.loader.NativeLog.i("View",
                    "macapkPaintMaterialChrome class=" + name
                    + " bounds=" + w + "x" + h
                    + " local=(" + left + "," + top + "-" + right + "," + bottom + ")"
                    + " canvasTx=(" + canvas.macapkGetCurrentTx()
                    + "," + canvas.macapkGetCurrentTy() + ")"
                    + " full=" + getClass().getName());
            }
        }

        // --- MaterialCardView / CardView ---
        // AOSP MaterialCardView paints via MaterialShapeDrawable which
        // we don't fully implement. We approximate with a rounded-rect
        // fill + soft shadow + thin outline.
        //
        // v1.0.128: theme-resolved fill + corner radius.
        // v1.0.131: inset the painted card area by 4dp on each side so
        // adjacent cards (RV with no item-decoration gap) get a visible
        // gutter between them. Real Android achieves the gap via
        // RecyclerView.ItemDecoration; without that, F-Droid's cards
        // tile edge-to-edge and look like one continuous block of card
        // color. Insetting the paint creates the visual gap without
        // changing the parent's layout (the card's logical bounds are
        // still w x h; only the painted chrome is smaller).
        //
        // v1.0.131: also paint a 1dp colorOutlineVariant stroke around
        // the card. With clipChildren=ON the multi-pass shadow can't
        // extend outside the card, so the visible card boundary needs
        // to come from a stroke. Material3 Outlined CardView uses this
        // exact pattern (1dp outline at colorOutlineVariant); we apply
        // it to all CardView variants for visible boundaries.
        if (name.endsWith("CardView")) {
            int density = Math.max(1, Math.round(android.util.DisplayMetrics.macapkDeviceDensity()));
            try {
                if (this.getResources() != null
                    && this.getResources().getDisplayMetrics() != null
                    && this.getResources().getDisplayMetrics().density > 0f) {
                    density = (int) this.getResources().getDisplayMetrics().density;
                }
            } catch (Throwable ignored) {}
            float radiusDp = android.content.res.Resources.macapkThemeCornerDp(
                "shapeAppearanceMediumComponent", 12f);
            int radius = Math.round(radiusDp * density);
            // v1.0.406: paint the card shape at its FULL bounds, the AOSP way.
            // A MaterialCardView's MaterialShapeDrawable fills [0,0,w,h]; the
            // gap between adjacent cards comes from layout margins / RV
            // ItemDecoration, NOT a paint inset. We also CLIP the card's
            // children to this same rounded rect (macapkBeginCardClip) so
            // content can't overhang the rounded corner -- and that requires
            // the fill to be at full bounds too (an inset fill would leave a
            // child drawing in the gutter over no card). The old 4dp inset
            // double-counted with real margins.
            float L = 0, T = 0, R = w, B = h;
            // Multi-pass soft shadow (clipped to card bounds by
            // dispatchDraw's per-child clip, so mostly invisible past
            // v1.0.129 -- still drawn for the case where a parent
            // explicitly disables clipChildren).
            int[] alphas = { 0x14, 0x10, 0x0C, 0x08 };  // ~8/6/5/3 %
            int yOff = density;
            android.graphics.Paint shadow = backgroundPaint();
            shadow.setAntiAlias(true);
            shadow.setStyle(android.graphics.Paint.Style.FILL);
            for (int i = alphas.length - 1; i >= 0; --i) {
                int g = (i + 1) * density;
                shadow.setColor((alphas[i] << 24));
                canvas.drawRoundRect(
                    new android.graphics.RectF(L - g, T - g + yOff,
                                                R + g, B + g + yOff),
                    radius + g, radius + g, shadow);
            }
            // Card fill resolves from theme. Material3's CardView (and the
            // .Elevated variant F-Droid's AppDetails header uses) draws its
            // surface at ?attr/colorSurfaceContainerLow -- NOT
            // ContainerHigh. F-Droid: Low=#f2f3fc (the light card), High=
            // #e6e8f0, Highest=#e0e2ea (?detailPanel, the summary/latest
            // panels). Using High made the whole header card ~12 units too
            // gray, so the description (no background) read gray instead of
            // matching the emulator's near-white card. Low is the M3 spec
            // for filled/elevated cards and matches the emulator exactly.
            int fill = backgroundColor;
            if ((fill & 0xFF000000) == 0) {
                fill = android.content.res.Resources.macapkThemeColor(
                    "colorSurfaceContainerLow", 0xFFFFFFFF);
            }
            android.graphics.Paint card = new android.graphics.Paint();
            card.setAntiAlias(true);
            card.setStyle(android.graphics.Paint.Style.FILL);
            card.setColor(fill);
            canvas.drawRoundRect(
                new android.graphics.RectF(L, T, R, B),
                radius, radius, card);
            // 1dp outline at colorOutlineVariant -- visible card border
            // that survives the clipChildren=ON parent clip.
            int outlineColor = android.content.res.Resources.macapkThemeColor(
                "colorOutlineVariant", 0x33000000);
            android.graphics.Paint stroke = new android.graphics.Paint();
            stroke.setAntiAlias(true);
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(density);  // 1dp
            stroke.setColor(outlineColor);
            canvas.drawRoundRect(
                new android.graphics.RectF(L, T, R, B),
                radius, radius, stroke);
            return; // chrome IS the background -- skip drawable fallback
        }

        // --- Material3 FILLED MaterialButton ---
        // MaterialButton's filled background is a MaterialShapeDrawable tinted
        // colorPrimary, which doesn't render through our Canvas (same gap as the
        // CardView/FAB above). LayoutInflater flags the filled variant and stores
        // the fill in macapkButtonFill (text/outlined buttons stay 0 -> untouched).
        // We paint a fully-rounded pill (M3 buttons are stadium-shaped: corner =
        // height/2); the white label is drawn on top by TextView.onDraw (its
        // textColor was set to colorOnPrimary by the inflater).
        if (macapkButtonFill != 0
                && (name.endsWith("MaterialButton") || name.equals("MaterialButton"))) {
            android.graphics.Paint p = new android.graphics.Paint();
            p.setAntiAlias(true);
            p.setStyle(android.graphics.Paint.Style.FILL);
            // The view-level android:backgroundTint outranks the style-derived
            // fill: on a device the tint is SRC_IN over whatever background
            // drawable is installed (MaterialShapeDrawable included), and over
            // an opaque fill the visible result IS the tint -- same reasoning
            // as the flat backgroundColor branch in draw(). The chrome stands
            // in for a background that will not paint, so it takes the same
            // tint the real background would have. macapkBackgroundTint is the
            // CURRENT-STATE resolved int (drawableStateChanged re-resolves a
            // stateful list), so a checked/disabled tint row follows here too.
            p.setColor(macapkBackgroundTint != null
                ? macapkBackgroundTint : macapkButtonFill);
            // AOSP insets the button's BACKGROUND vertically (Material's styles
            // set android:insetTop/insetBottom), so the pill is shorter than the
            // view and its stadium radius follows the INSET height, not the
            // view's. Clamped so a pathological inset cannot invert the rect.
            float inset = Math.max(0, Math.min(macapkButtonInsetPx, (h - 1) / 2));
            float top = inset, bot = h - inset;
            float r = Math.min(w, bot - top) / 2f;   // stadium / pill
            canvas.drawRoundRect(new android.graphics.RectF(0, top, w, bot), r, r, p);
            return; // chrome IS the background
        }

        // --- BottomNavigationView / NavigationBarView / NavigationRailView ---
        // Material's bottom-nav background is an opaque surface-tone
        // rectangle with a subtle top hairline. Without it the labels
        // float over whatever content sits behind the strip.
        //
        // v1.0.128: fill + hairline color route through the theme
        // cascade. `?attr/colorSurfaceContainer` is Material3's
        // surface-tone for bottom-nav (slightly elevated above the
        // activity background); `?attr/colorOutlineVariant` is the
        // divider color. F-Droid declares md_theme_surfaceContainer
        // as #ecedf6 (slightly lavender) and md_theme_outlineVariant
        // as #c1c7d1 (light gray). Generic across any Material3 app.
        if (name.endsWith("BottomNavigationView")
            || name.endsWith("NavigationBarView")
            || name.endsWith("NavigationRailView")) {
            int fill = backgroundColor;
            if ((fill & 0xFF000000) == 0) {
                fill = android.content.res.Resources.macapkThemeColor(
                    "colorSurfaceContainer", 0xFFFFFBFE);
            }
            android.graphics.Paint nav = new android.graphics.Paint();
            nav.setStyle(android.graphics.Paint.Style.FILL);
            nav.setColor(fill);
            canvas.drawRect(0, 0, w, h, nav);
            // Top hairline -- 1dp divider, M3 outline-variant.
            int hairColor = android.content.res.Resources.macapkThemeColor(
                "colorOutlineVariant", 0x1F000000);
            android.graphics.Paint hair = new android.graphics.Paint();
            hair.setStyle(android.graphics.Paint.Style.FILL);
            hair.setColor(hairColor);
            canvas.drawRect(0, 0, w, 2, hair);
            return;
        }

        // --- AppBarLayout / Toolbar ---
        // Same surface-fill so action bar text doesn't bleed into
        // content underneath.
        //
        // v1.0.128: theme-aware fill via `?attr/colorSurface`.
        // A TOOLBAR is not filled (MACAPK_TOOLBAR_CHROME): AOSP's Toolbar has
        // no default background, so one without a background is transparent
        // and shows whatever is behind it -- typically its AppBarLayout, whose
        // style background (?attr/colorPrimary, Material's
        // MaterialShapeDrawable included) is drawn. Filling the Toolbar with
        // colorSurface painted a white band over that colour: PalGate's blue
        // app bar came out white and its white "OK" button vanished into it.
        if (name.endsWith("AppBarLayout")
            || (MACAPK_TOOLBAR_CHROME
                && (name.endsWith("Toolbar") || name.endsWith("MaterialToolbar")))) {
            int fill = backgroundColor;
            if ((fill & 0xFF000000) != 0) return; // already opaque
            // Honor an EXPLICIT background (incl. transparent). F-Droid's
            // CollapsingToolbarLayout hero toolbar is
            // android:background="@android:color/transparent" so the
            // feature graphic underneath shows through; painting the
            // surface-tone fallback there hid the top of the image.
            if (macapkBackgroundExplicitlySet) return;
            fill = android.content.res.Resources.macapkThemeColor(
                "colorSurface", 0xFFFFFBFE);
            android.graphics.Paint ab = new android.graphics.Paint();
            ab.setStyle(android.graphics.Paint.Style.FILL);
            ab.setColor(fill);
            canvas.drawRect(0, 0, w, h, ab);
            return;
        }
    }
    /** Gate: MACAPK_TOOLBAR_CHROME=1 restores the colorSurface fill behind a
     *  Toolbar / MaterialToolbar with no background (see macapkPaintMaterialChrome). */
    static final boolean MACAPK_TOOLBAR_CHROME =
        "1".equals(System.getenv("MACAPK_TOOLBAR_CHROME"));
    protected void onDraw(android.graphics.Canvas canvas)      {}
    protected void dispatchDraw(android.graphics.Canvas canvas) {}

    /** Background paint reused across draw calls so we don't
     *  allocate a Paint per frame (each is a small heap object
     *  but at 60fps * many views the GC pressure adds up). */
    private android.graphics.Paint backgroundPaintInstance;
    private android.graphics.Paint backgroundPaint() {
        if (backgroundPaintInstance == null) {
            backgroundPaintInstance = new android.graphics.Paint();
        }
        return backgroundPaintInstance;
    }
    /** ONE Handler per View for everything this View posts.
     *
     *  ⚠ v1.0.936. post/postDelayed/removeCallbacks each did
     *  `new android.os.Handler()`, and Handler's delay map is PER-INSTANCE by
     *  design (see its own comment: making it static broke
     *  removeCallbacksAndMessages(null) across components). So a runnable
     *  posted through one throwaway Handler could never be cancelled through
     *  the next one -- `removeCallbacks` returned true having removed nothing,
     *  and every delayed post was uncancellable. The getHandler() comment above
     *  documents exactly this bug and fixed it there in isolation; these three
     *  kept the old shape.
     *
     *  Deliberately NOT getHandler(): that one answers null while detached, per
     *  the AOSP contract, and posting from a detached view must still work
     *  (AOSP queues it in the ViewRootImpl RunQueue). */
    private android.os.Handler mPostHandler;
    private android.os.Handler macapkPostHandler() {
        android.os.Handler h = mPostHandler;
        if (h == null) {
            synchronized (this) {
                h = mPostHandler;
                if (h == null) mPostHandler = h = new android.os.Handler();
            }
        }
        return h;
    }
    public boolean post(Runnable r) {
        macapkPostHandler().post(r);
        return true;
    }
    /** AOSP-correct since v1.0.103: route through Handler.postDelayed so
     *  the delay is actually honored (the v1.0.103 Handler rewrite added
     *  a real delay-pool timer; before that, View.postDelayed silently
     *  dropped the delay and posted immediately, which mirrored the
     *  Handler behavior). */
    public boolean postDelayed(Runnable r, long delay) {
        macapkPostHandler().postDelayed(r, delay);
        return true;
    }
    /** AOSP: `public boolean removeCallbacks(Runnable action)`. RecyclerView's
     *  <init> invokes this expecting a boolean return; declaring it `void`
     *  triggers NoSuchMethodError at link time (JVM matches signatures
     *  including return type). Cancels through the SAME Handler that posted,
     *  which is what actually makes it cancel anything. */
    public boolean removeCallbacks(Runnable r) {
        macapkPostHandler().removeCallbacks(r);
        macapkForgetScheduled(r);
        return true;
    }
    /** API-16+: same scheduling guarantees as `post`, but the runnable
     *  runs synchronized with the next animation frame. We don't have
     *  a Choreographer, so we just defer to `post` which schedules
     *  through Handler.post. Used by AppCompat's ContentFrameLayout
     *  to defer fitSystemWindows until after layout. */
    /** AOSP signature: `public void postOnAnimation(Runnable)`. Returns
     *  void, NOT boolean -- AppCompat calls it via direct invokevirtual
     *  expecting the void signature, so a boolean return would NoSuchMethodError. */
    public void postOnAnimation(Runnable r) { post(r); }
    public void postOnAnimationDelayed(Runnable r, long d) { postDelayed(r, d); }

    /** API-21+ View.getOutlineProvider / setOutlineProvider. Material
     *  AppBarLayout sets/checks the outline provider for its elevation
     *  shadow, and libraries compare against the statics by IDENTITY.
     *
     *  AOSP's DEFAULT is ViewOutlineProvider.BACKGROUND, not null
     *  (`ViewOutlineProvider mOutlineProvider = ViewOutlineProvider.BACKGROUND;`),
     *  so `getOutlineProvider() == ViewOutlineProvider.BACKGROUND` is TRUE on a
     *  view nobody has configured -- which is exactly the test a library runs
     *  before deciding whether it is safe to install its own provider. We
     *  answered null, so that test failed and the library either installed
     *  nothing or replaced a provider it should have left alone (fwprobe
     *  VW.out.provider.default: reference null=0 bg=1, ours null=1 bg=0).
     *
     *  The two internal consumers stay gated as they were: draw() only builds a
     *  clip when getClipToOutline() is set (default false) and
     *  macapkShadowRadiusPx() only returns a radius for a ROUND_RECT outline
     *  with radius > 0, which BACKGROUND produces only from a rounded
     *  background drawable -- and ViewGroup only draws the shadow when z > 0. */
    private android.view.ViewOutlineProvider outlineProvider = android.view.ViewOutlineProvider.BACKGROUND;
    public android.view.ViewOutlineProvider getOutlineProvider() {
        return outlineProvider;
    }
    public void setOutlineProvider(android.view.ViewOutlineProvider p) {
        this.outlineProvider = p;
    }
    /** API-19+ View.isInEditMode -- "are we running in Android Studio's
     *  layout editor preview." Always false at runtime. */
    public boolean isInEditMode() {
        // Diagnostic (env-gated): ConstraintLayout.setChildrenConstraints
        // calls this FIRST, so a trace here proves whether the
        // constraint-wiring pass ran for a given CL instance.
        if (ENV_MACAPK_TRACE_EDITMODE != null) {
            try {
                Class<?> log = Class.forName("com.macapk.loader.NativeLog");
                log.getMethod("i", String.class, String.class).invoke(null,
                    "Mac.apk.EditMode",
                    getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " id=0x" + Integer.toHexString(getId()));
            } catch (Throwable ignored) {}
        }
        return false;
    }
    // Tier 3 M12: real translationZ / Z (were no-op stubs). AOSP semantics:
    // setZ(z) == setTranslationZ(z - elevation); getZ() == elevation + translationZ.
    public void setTranslationZ(float z) { if (mTranslationZ != z) { mTranslationZ = sanitizeFloatPropertyValue(z, "translationZ"); invalidate(); } }
    public float getTranslationZ() { return mTranslationZ; }
    public void setZ(float z) { setTranslationZ(z - mElevation); }
    public float getZ() { return mElevation + mTranslationZ; }
    /** Tier 3 M12: corner radius (px) for this view's elevation shadow, OR -1
     *  to mean "cast NO shadow". AOSP casts an elevation shadow from the view's
     *  Outline; a view whose ViewOutlineProvider yields an EMPTY outline (the
     *  default BACKGROUND provider over a view with no background) casts nothing.
     *  So: a non-empty round-rect outline -> rounded shadow (Material card/FAB/
     *  pill); a non-empty rect outline OR a plain background surface -> square
     *  (radius 0) shadow; neither -> -1 (no shadow -- e.g. a bare ImageView
     *  placeholder that has elevation but no content, which must NOT paint a
     *  stray shadow box). Cheap enough for the few elevated views per frame on
     *  the M6 dirty path. */
    public float macapkShadowRadiusPx() {
        // M12 scope: cast an elevation shadow ONLY from a non-empty ROUNDED
        // outline (radius > 0). That is exactly the high-value Material case --
        // cards / FABs / the nav active-indicator pill set clipToOutline + a
        // round-rect (or oval, which Outline.setOval stores as a round rect)
        // provider -- and it deliberately does NOT shadow plain rect/background
        // surfaces, which on a content-less placeholder (e.g. an empty
        // CircleImageView that has elevation + a background but whose circular
        // outline our framework doesn't yet receive) would paint a stray opaque
        // square box. Square-outline + plain-background shadows are a notated
        // follow-up; better to under-shadow than to paint a box.
        if (outlineProvider == null) return -1f;
        try {
            android.graphics.Outline o = new android.graphics.Outline();
            outlineProvider.getOutline(this, o);
            if (!o.isEmpty()
                    && o.mMode == android.graphics.Outline.MODE_ROUND_RECT
                    && o.mRadius > 0f) {
                return o.mRadius;
            }
        } catch (Throwable ignored) {}
        return -1f;
    }

    // Real Android: `public boolean requestFocus()` returns true if
    // focus was granted. We always grant focus (single Cocoa window
    // = always focused). The boolean return matters for callers like
    // Cocos2dxGLSurfaceView.setTextField which dispatches via
    // invokevirtual against the boolean signature -- a void-returning
    // override here triggers NoSuchMethodError at link time.
    // AOSP View focus-grant. requestFocus succeeds only if the view is
    // focusable in the current touch mode (matches AOSP -- a non-focusable
    // view returns false), then becomes the single focused view in its tree:
    // the parent chain's mFocused pointers are updated to point at it and the
    // previously-focused view (a sibling branch) is cleared. The boolean
    // return is load-bearing for invokevirtual callers (e.g. cocos2d-x
    // Cocos2dxGLSurfaceView.setTextField).
    public boolean requestFocus()                  { return requestFocus(FOCUS_DOWN); }
    public boolean requestFocus(int direction)     { return requestFocus(direction, null); }
    public boolean requestFocus(int direction, android.graphics.Rect previouslyFocusedRect) {
        return requestFocusNoSearch(direction, previouslyFocusedRect);
    }
    public boolean requestFocusFromTouch()         { return requestFocus(FOCUS_DOWN); }
    static final boolean sTraceIme = "1".equals(System.getenv("MACAPK_TRACE_IME"));
    boolean requestFocusNoSearch(int direction, android.graphics.Rect previouslyFocusedRect) {
        if (!isFocusableConsideringTouchMode()) {
            if (sTraceIme) {
                System.err.println("[Mac.apk.IME] requestFocus REFUSED " + getClass().getName()
                    + " focusable=" + focusable + " vis=" + visibility
                    + " enabled=" + isEnabled() + " touchMode=" + isInTouchMode()
                    + " focusableInTouchMode=" + focusableInTouchMode);
            }
            return false;
        }
        // AOSP requestFocusNoSearch (android14): "need to not have any parents
        // blocking us". Matters once ViewGroup.requestFocus descends: a
        // FOCUS_BLOCK_DESCENDANTS ancestor (a hidden leanback headers panel, a
        // disabled pane) must keep its subtree out of focus.
        if (hasAncestorThatBlocksDescendantFocus()) return false;
        if (mIsFocused) return true;
        handleFocusGainInternal(direction, previouslyFocusedRect);
        if (sTraceIme) {
            System.err.println("[Mac.apk.IME] requestFocus GRANTED " + getClass().getName()
                + " parent=" + (getParent() == null ? "null" : getParent().getClass().getName()));
        }
        return true;
    }
    /** AOSP View.hasAncestorThatBlocksDescendantFocus (android14): a
     *  FOCUS_BLOCK_DESCENDANTS ancestor, or (for a view that is not focusable
     *  in touch mode) an ancestor whose touchscreenBlocksFocus applies. */
    private boolean hasAncestorThatBlocksDescendantFocus() {
        final boolean inTouchModeFocusable = isFocusableInTouchMode();
        ViewParent ancestor = parent;
        while (ancestor instanceof ViewGroup vg) {
            if (vg.getDescendantFocusability() == ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    || (!inTouchModeFocusable && vg.shouldBlockFocusForTouchscreen())) {
                return true;
            }
            ancestor = vg.getParent();
        }
        return false;
    }
    /** AOSP splits this into canTakeFocus():16018 (visible AND focusable AND
     *  ENABLED) plus the touch-mode test in requestFocusNoSearch:13304. The
     *  ENABLED conjunct was missing here, so setEnabled(false) did not stop a
     *  view taking focus: fwprobe VW.det.focus.request_disabled read
     *  got=0 isFocused=0 on the reference and got=1 isFocused=1 here. A
     *  disabled control that can still hold focus swallows key events that
     *  should have travelled on to the next focusable. */
    private boolean isFocusableConsideringTouchMode() {
        return focusable && visibility == VISIBLE && isEnabled()
            && (!isInTouchMode() || focusableInTouchMode);
    }
    /** MACAPK_TRACE_FOCUS=1: who takes focus, from where (diagnostic). */
    static final boolean sTraceFocus = "1".equals(System.getenv("MACAPK_TRACE_FOCUS"));
    void handleFocusGainInternal(int direction, android.graphics.Rect previouslyFocusedRect) {
        if (mIsFocused) return;
        if (sTraceFocus) {
            StringBuilder sb = new StringBuilder("focus-gain ").append(getClass().getName())
                .append('@').append(Integer.toHexString(System.identityHashCode(this)))
                .append(" dir=").append(direction);
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (int i = 1; i < Math.min(st.length, 14); i++) sb.append("\n      at ").append(st[i]);
            com.macapk.loader.NativeLog.i("Mac.apk.Focus", sb.toString());
        }
        mIsFocused = true;
        ViewParent p = getParent();
        if (p != null) p.requestChildFocus(this, this);   // builds the mFocused chain to root, clears old branch
        onFocusChanged(true, direction, previouslyFocusedRect);
        // AOSP's handleFocusGainInternal ends with refreshDrawableState()
        // (core/java/android/view/View.java:7845), NOT a plain invalidate --
        // it is what pushes the state_focused-inclusive array into
        // Drawable.setState() so a stateful background (a StateListDrawable,
        // or a custom Drawable like the EditText underline bar) actually
        // repaints in its focused art. Without it mIsFocused/isFocused()/
        // hasFocus() all flip correctly (the state machine works), but
        // getDrawableState()'s CACHED array is never marked dirty and
        // drawableStateChanged() never runs, so the background stays on
        // whatever state it last drew regardless of focus. Added alongside
        // the existing invalidate() rather than replacing it, since
        // refreshDrawableState()'s own drawableStateChanged() invalidates
        // only CONDITIONALLY (state actually changed the drawable's
        // appearance) and the prior unconditional repaint is a strict
        // superset, not a behavior this removes.
        refreshDrawableState();
        invalidate();
    }
    /** AOSP View.onFocusChanged: fire the registered listener; subclasses
     *  (AndroidComposeView, EditText, ...) override to drive their own focus
     *  state and then call super. */
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        // AOSP tells the IME which view holds focus from here. Without it,
        // isActive()/isAcceptingText() answered false for every VIEW-BASED editor
        // (Signal's EmojiEditText, AppCompat search boxes, every legacy form) --
        // measured 2026-08-25 against a real API-30 device, which answers
        // isActive=true accepting=true for the same field.
        // Typing still worked throughout, because TextView has a raw-key fallback
        // that bypasses the IME entirely -- which is exactly why "I typed and it
        // worked" was never evidence that this was fine.
        // Self-gating: it binds only for a view whose onCheckIsTextEditor() is
        // true, AOSP's own StartInputFlags.IS_TEXT_EDITOR gate, so a game reading
        // raw keys is untouched.
        try {
            android.view.inputmethod.InputMethodManager
                .macapkOnViewFocusChanged(this, gainFocus);
        } catch (Throwable ignored) {}
        if (onFocusChangeListener != null) {
            try { onFocusChangeListener.onFocusChange(this, gainFocus); } catch (Throwable ignored) {}
        }
    }
    /** AOSP View.unFocus: clear focus as a side effect of another view gaining
     *  it (no parent notification -- the new focus path already rewired the
     *  chain). AOSP's real implementation (View.clearFocusInternal,
     *  core/java/android/view/View.java:8011-8012, which unFocus delegates
     *  to) calls onFocusChanged then refreshDrawableState -- same pair, same
     *  order, same reasoning as handleFocusGainInternal above: without it a
     *  stateful background drawable never learns the state_focused bit was
     *  dropped and stays on its focused art. */
    void unFocus(View focused) {
        if (!mIsFocused) return;
        mIsFocused = false;
        onFocusChanged(false, 0, null);
        refreshDrawableState();
        invalidate();
    }
    public boolean hasFocus() { return mIsFocused; }
    public boolean isPressed() { return pressed; }
    public void setPressed(boolean p) {
        // Flag-OFF: just set the field (old behavior). Flag-ON: refresh
        // drawable state (refreshDrawableState is itself flag-gated).
        boolean changed = pressed != p;
        pressed = p;
        if (changed) {
            refreshDrawableState();
        }
        // ⚠ v1.0.938: dispatchSetPressed is OUTSIDE the `changed` guard. AOSP
        // (View.java:12310-12320) refreshes drawable state only on a change but
        // dispatches UNCONDITIONALLY:
        //     if (changed) { refreshDrawableState(); }
        //     dispatchSetPressed(pressed);
        // and it is specifically setPressed that does this -- setSelected and
        // setActivated genuinely do guard their dispatch, so this is not a
        // uniform pattern to "tidy up".
        //
        // Why it matters: a recycled list row is handed to a new item still
        // holding pressed==true from the last one. setPressed(true) then sees
        // no change and dispatches nothing, so the row's non-clickable children
        // keep the previous item's pressed artwork. Our ViewGroup.dispatchSetPressed
        // was already byte-correct; it was simply never reached in that case.
        dispatchSetPressed(p);
    }

    /** AOSP-compatible background storage. Apps that call
     *  setBackgroundColor want the bound region painted in that
     *  color before children render. This is the minimum for
     *  HomeScreen-style layouts to be visible at all -- without
     *  it every plain View is a 0x0 invisible chunk of memory. */
    protected int backgroundColor;
    protected android.graphics.drawable.Drawable backgroundDrawable;
    // Fill color for a Material3 FILLED MaterialButton (0 = not a filled button).
    // Set by LayoutInflater when it sees the filled-button style; rendered as a
    // pill in macapkPaintMaterialChrome (MaterialButton's MaterialShapeDrawable
    // background doesn't render through our Canvas, same as CardView/FAB chrome).
    public int macapkButtonFill = 0;
    /** True when `backgroundDrawable` is the empty stand-in Resources hands back
     *  for a reference it could not resolve, i.e. nothing will paint. */
    protected boolean macapkBackgroundIsUnresolvedStub() {
        return backgroundDrawable instanceof android.graphics.drawable.VectorDrawable vd
            && vd.macapkUnresolvedStub;
    }
    /** True when the background drawable exists but will paint NOTHING, so a
     *  synthetic stand-in is not covering anything up. See
     *  VectorDrawable.macapkPaintsNothing for why the unresolved-stub flag alone
     *  is not enough. */
    protected boolean macapkBackgroundPaintsNothing() {
        return backgroundDrawable instanceof android.graphics.drawable.VectorDrawable vd
            && vd.macapkPaintsNothing();
    }
    // Vertical inset of that pill, in px. Material's button styles set
    // `android:insetTop`/`insetBottom` (m3_btn_inset = 4dp on Material3,
    // mtrl_btn_inset = 6dp on the MaterialComponents baseline), so the drawn
    // background is SHORTER than the view: a 48dp button carries a 40dp pill.
    // Measured on AnkiDroid, where painting the full view rect overshot the
    // emulator's 56,983 px by ~12,000 and rounded the corners at 48px instead
    // of 40px. Set by LayoutInflater alongside macapkButtonFill; 0 keeps the
    // old full-rect behaviour for any caller that does not set it.
    public int macapkButtonInsetPx = 0;

    // True once android:background (or a programmatic setBackground*) was
    // applied to THIS view. Lets macapkPaintMaterialChrome distinguish
    // "no background declared -> safe to paint the Material surface-tone
    // fallback so toolbar/appbar text stays legible" from "background was
    // EXPLICITLY set (incl. @android:color/transparent) -> honor it, do
    // not override". F-Droid's AppDetails MaterialToolbar declares
    // android:background="@android:color/transparent" so the feature
    // graphic shows through it; without this flag the chrome fallback
    // painted an opaque colorSurface rect over the top of the hero image.
    public boolean macapkBackgroundExplicitlySet = false;

    /** AOSP setBackgroundColor:
     *      if (mBackground instanceof ColorDrawable) {
     *          ((ColorDrawable) mBackground.mutate()).setColor(color);
     *          computeOpaqueFlags(); mBackgroundResource = 0;
     *      } else {
     *          setBackground(new ColorDrawable(color));
     *      }
     *  i.e. getBackground() after setBackgroundColor is a ColorDrawable holding
     *  that colour, and that is a question libraries really ask -- material's
     *  NavigationBarView does
     *      if (getBackground() instanceof ColorDrawable) shape.setFillColor(...)
     *  to seed its MaterialShapeDrawable. We answered null (fwprobe
     *  VW.bg.setcolor: reference color=1 val=0xff334455, ours color=0).
     *
     *  DELIBERATE REPRESENTATION SPLIT, and it is not a shortcut: the flat
     *  `backgroundColor` int stays the drawing state (eight branches of draw()
     *  and the Material chrome fallback read it, and it is a fleet-wide fast
     *  path), while macapkColorBackground is the ColorDrawable VIEW onto the
     *  same value that getBackground() hands out. Both describe one fact, so
     *  they cannot disagree; routing setBackgroundColor through setBackground
     *  instead would move every flat-colour view onto the drawable draw branch,
     *  a rendering change with nothing to do with the wrong answer being fixed
     *  here. The drawable is built lazily, so a view that never has its
     *  background read still allocates nothing. */
    private android.graphics.drawable.ColorDrawable macapkColorBackground;
    /** Bisect gate for the setBackground(null) clear below. DEFAULT ON (AOSP
     *  behaviour); MACAPK_BG_NULL_CLEARS_COLOR=0 gets the pre-v1.0.923 answer
     *  back without a rebuild. Read once into a static so the draw path pays no
     *  getenv. */
    private static final boolean sBgNullClearsColor =
        !"0".equals(System.getenv("MACAPK_BG_NULL_CLEARS_COLOR"));
    public void setBackgroundColor(int color) {
        this.backgroundColor = color;
        this.macapkBackgroundExplicitlySet = true;
        if (macapkColorBackground != null) macapkColorBackground.setColor(color);
        // v1.0.1645: AOSP View.setBackgroundColor (android14 View.java):
        //     if (mBackground instanceof ColorDrawable) {
        //         ((ColorDrawable) mBackground.mutate()).setColor(color); ...
        //     } else {
        //         setBackground(new ColorDrawable(color));
        //     }
        // i.e. a colour REPLACES any other background. We kept an existing
        // drawable and drew it over the flat colour, so an element's
        // `android:background="?colorAttr"` could not undo a default style's
        // drawable: OpenCalculator's display showed Widget.EditText's underline
        // (2026-09-25 fleet A/B). A ColorDrawable is recoloured in place; any
        // other drawable is dropped. Its padding stays applied, as in AOSP (a
        // ColorDrawable reports no padding, so setBackground keeps the old one).
        // MACAPK_SETBG_COLOR_REPLACES=0 restores the additive behaviour.
        if (sSetBgColorReplaces && backgroundDrawable != null) {
            if (backgroundDrawable instanceof android.graphics.drawable.ColorDrawable cd) {
                cd.mutate();
                cd.setColor(color);
            } else {
                backgroundDrawable = null;
            }
            invalidate();
        }
    }
    private static final boolean sSetBgColorReplaces =
        !"0".equals(System.getenv("MACAPK_SETBG_COLOR_REPLACES"));
    /** Read the bound background color (0 if never set). Not on AOSP's
     *  public View API, but the framework's renderer-side helpers
     *  (ViewCanvasDriver) need it to decide whether to paint an
     *  opaque clear when no GL pipeline is below. */
    public int getBackgroundColor() { return backgroundColor; }
    public void setBackgroundDrawable(android.graphics.drawable.Drawable d) {
        // AOSP's SECOND statement, and it is load-bearing (View.java:23662-23667,
        // android11-release, fetched 2026-08-09):
        //     public void setBackgroundDrawable(Drawable background) {
        //         computeOpaqueFlags();
        //         if (background == mBackground) {
        //             return;
        //         }
        // Without it, re-installing the SAME drawable instance re-runs
        // getPadding -> internalSetPadding -> requestLayout. Material's
        // FloatingActionButton does exactly that from its own onMeasure
        // (FloatingActionButton.onMeasure -> FloatingActionButtonImpl.updatePadding
        // -> onPaddingUpdated -> ShadowDelegateImpl.setBackgroundDrawable), so
        // EVERY measure marked the FAB layout-requested again. `CoordinatorLayout.
        // getChildRect` early-returns an EMPTY rect when `child.isLayoutRequested()`,
        // so its pre-draw `offsetChildToAnchor` computed the offset against (0,0)
        // and moved the FAB by its FULL desired position -- measured
        // dx=+664 dy=+1161 on unitconverter, putting a 112px view at x=1328 in an
        // 808px parent, where the parent's clip discarded it ENTIRELY, icon
        // included. That is why the FAB painted its background with the correct
        // colour 445 times a run and not one pixel reached the screen.
        //
        // SCOPED TO NON-NULL on purpose. AOSP has ONE background slot; we split
        // the state across `backgroundColor` and `backgroundDrawable`, and the
        // null path below deliberately clears BOTH (see the comment there). An
        // unconditional identity return would resurrect the bug that fixed.
        if (d != null && d == backgroundDrawable) return;
        if (ENV_MACAPK_TRACE_SETBG != null
                && getClass().getSimpleName().endsWith("Button")) {
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 2; i < Math.min(st.length, 7); i++) sb.append("\n    at ").append(st[i]);
            android.util.Log.i("Mac.apk.SetBg", "setBackgroundDrawable("
                + (d == null ? "null" : d.getClass().getSimpleName()
                   + (d instanceof android.graphics.drawable.VectorDrawable vd
                      ? " stub=" + vd.macapkUnresolvedStub : ""))
                + ") on " + getClass().getName() + sb);
        }
        // AOSP's mBackground is ONE slot: installing null means the view has no
        // background at all. Mac.apk splits that state across two fields (the
        // flat `backgroundColor` fast path and `backgroundDrawable`), and
        // setBackground(null) used to clear only the second -- so a view whose
        // background had been set as a colour kept painting it after an explicit
        // removal, and getBackground() (which now reports the flat colour as a
        // ColorDrawable, as AOSP does) reported a background that was supposed
        // to be gone. Clearing both is what makes the two fields describe one
        // fact. Scoped to the NULL case on purpose: installing a non-null
        // drawable over a flat colour keeps its existing additive behaviour,
        // which is a much larger rendering question than the one being fixed.
        // MACAPK_BG_NULL_CLEARS_COLOR=0 restores the old behaviour for a
        // one-run A/B if this turns out to remove a background something relied
        // on.
        if (d == null && sBgNullClearsColor) {
            this.backgroundColor = 0;
            this.macapkColorBackground = null;
        }
        this.backgroundDrawable = d;
        // v1.0.871: APPLY THE BACKGROUND'S PADDING. AOSP's setBackgroundDrawable
        // ends with, verbatim (core/java/android/view/View.java):
        //     if (background.getPadding(padding)) {
        //         resetResolvedPaddingInternal();
        //         switch (background.getLayoutDirection()) {
        //             case LAYOUT_DIRECTION_RTL: ... internalSetPadding(
        //                     padding.right, padding.top, padding.left, padding.bottom);
        //             case LAYOUT_DIRECTION_LTR: default: ... internalSetPadding(
        //                     padding.left, padding.top, padding.right, padding.bottom);
        //         }
        //         mLeftPaddingDefined = false; mRightPaddingDefined = false;
        //     }
        // That is how a 9-patch / InsetDrawable / shape-with-<padding>
        // background insets its own view's content. Ours never did it, so the
        // dialog decor had to apply it BY HAND (v1.0.866
        // Dialog.macapkApplyDialogBackground) and every other padded background
        // in the framework was silently ignored.
        //
        // ⚠ ORDERING IS THE WHOLE HAZARD, and it is handled at the caller.
        // AOSP's View constructor sets the background FIRST and applies explicit
        // `android:padding*` attrs AFTER, so an app's own padding always wins.
        // Our inflater iterates a HashMap of style items in arbitrary order, so
        // LayoutInflater.applyAttributes now defers every padding* attribute to
        // the very end for exactly this reason. Without that, an element with
        // both android:background and android:padding would get whichever
        // happened to be applied last.
        //
        // Only fires when the drawable REPORTS padding (getPadding returns
        // true), which the base Drawable does not -- so a plain ColorDrawable or
        // a shape with no <padding> cannot disturb anything.
        if (d != null) {
            try {
                android.graphics.Rect pad = new android.graphics.Rect();
                if (d.getPadding(pad)) {
                    // AOSP uses internalSetPadding here, NOT setPadding
                    // (View.java setBackgroundDrawable, the LAYOUT_DIRECTION_LTR
                    // arm). The difference is exactly the relative-padding
                    // bookkeeping: setPadding CLEARS mUserPaddingStart/End,
                    // internalSetPadding leaves them alone. Installing a padded
                    // background therefore must not make isPaddingRelative()
                    // start answering false on a view whose padding was set
                    // through the relative API -- fwprobe
                    // VW.bg.relpad_then_bg.rel reads 1 on the reference and read
                    // 0 here, so an AndroidX widget re-applying its padding
                    // after an insets change took the absolute path and mirrored
                    // wrong under RTL.
                    mUserPaddingLeftInitial = pad.left;
                    mUserPaddingRightInitial = pad.right;
                    internalSetPadding(pad.left, pad.top, pad.right, pad.bottom);
                }
            } catch (Throwable ignored) {}
        }
        // v1.0.841: mirror setBackgroundColor's flag update -- AOSP treats
        // "a background was declared" as ONE concept regardless of whether
        // the XML/programmatic value was a solid color or a Drawable (this
        // very method is what AOSP's own deprecated setBackgroundDrawable
        // and the modern setBackground both funnel through). Without this,
        // a View whose android:background resolves to a real Drawable (via
        // LayoutInflater.applyBackground -> Resources.getDrawable) still
        // read as "no background declared" to consumers of the flag (the
        // Toolbar/AppBarLayout colorSurface chrome fallback), which could
        // paint its own fill on top of a real, possibly-transparent-by-design
        // drawable background.
        this.macapkBackgroundExplicitlySet = true;
        // v1.0.842 (lane STATELIST): AOSP's View.setBackgroundDrawable syncs
        // drawable state on install --
        //   frameworks/base/core/java/android/view/View.java (android13-release):
        //     mBackground = background;
        //     if (background.isStateful()) {
        //         background.setState(getDrawableState());
        //     }
        // -- so a stateful background (StateListDrawable / a GradientDrawable
        // whose <solid> is a ColorStateList) starts out painting the row that
        // matches THIS view's actual state, not undefined. Without this call
        // the only path that ever reaches Drawable.setState is
        // drawableStateChanged() (via refreshDrawableState()), which fires
        // from setPressed/setSelected/setActivated/setHovered and from
        // setEnabled(..) but ONLY "if (changed)" -- so a control that is
        // enabled at construction and never explicitly toggled never gets a
        // first setState() at all. StateListDrawable.current() (see that
        // file) then defaults to its LAST entry forever, which is whatever
        // the <selector> author put last (often the catch-all/disabled-
        // looking row), regardless of the view's real enabled/pressed/
        // selected state. This is the exact minimal pattern already used by
        // CompoundButton.setButtonDrawable, ProgressBar.setProgressDrawable/
        // setIndeterminateDrawable, and AbsSeekBar.setThumb in this tree --
        // this was the one drawable-install site in the whole framework that
        // never got it. getDrawableState() self-gates on
        // android.os.Looper.MACAPK_REAL_LOOPER (returns int[0] when off), so
        // no separate flag check is needed here, matching those call sites.
        if (d != null && d.isStateful()) {
            try { d.setState(getDrawableState()); } catch (Throwable ignored) {}
        }
        // v1.0.936: the CALLBACK. AOSP's setBackgroundDrawable brackets the
        // whole method with it (View.java:23668-23718, android11-release):
        //
        //     if (mBackground != null) {
        //         ...
        //         mBackground.setCallback(null);
        //         unscheduleDrawable(mBackground);
        //     }
        //     ...
        //     // Set callback last, since the view may still be initializing.
        //     background.setCallback(this);
        //
        // Neither half existed here. Consequences, both silent:
        //   - an ANIMATED background (AnimationDrawable, AnimatedVectorDrawable,
        //     a Lottie/ripple drawable) calls scheduleSelf/invalidateSelf, which
        //     go through Drawable.mCallback -- null means the animation never
        //     ticks and never repaints. Our Drawable.Callback methods on View
        //     were correct and simply never invoked for a background.
        //   - the OLD drawable kept pointing at this View forever, so it could
        //     still schedule work and force redraws after being replaced.
        //
        // ⚠ SHARED-CONSTANT-STATE TRAP: our Drawable.getConstantState().
        // newDrawable() returns the SAME instance, so two views given "the same"
        // background resource really do share one Drawable, and whichever set
        // its callback last owns it. AOSP avoids this because newDrawable()
        // genuinely allocates. That is a separate fidelity gap (tracked in the
        // session notes); the ordering below at least matches AOSP so the LAST
        // installer wins rather than an arbitrary one.
        macapkSwapBackgroundCallback(d);
    }

    /** The old drawable is detached (callback cleared, pending animation work
     *  cancelled) before the new one is attached, in AOSP's order. */
    private void macapkSwapBackgroundCallback(android.graphics.drawable.Drawable d) {
        android.graphics.drawable.Drawable old = macapkCallbackOwned;
        if (old != null && old != d) {
            try {
                old.setCallback(null);
                unscheduleDrawable(old);
            } catch (Throwable ignored) {}
        }
        macapkCallbackOwned = d;
        if (d != null) {
            try { d.setCallback(this); } catch (Throwable ignored) {}
        }
    }
    /** The drawable whose callback THIS view currently owns. Tracked separately
     *  from backgroundDrawable because the null path clears two fields. */
    private android.graphics.drawable.Drawable macapkCallbackOwned;
    public void setBackground(android.graphics.drawable.Drawable d) {
        setBackgroundDrawable(d);
    }
    /** AOSP setBackgroundResource(0) is `setBackground(null)` -- it REMOVES the
     *  background. This was a total no-op, so `setBackgroundColor(c);
     *  setBackgroundResource(0)` left the view still backed by c (fwprobe
     *  VW.bg.setresource_zero: reference NULL, ours non-null once getBackground
     *  learned to report the flat colour).
     *
     *  Only the resId == 0 arm is implemented. A NON-zero resId still needs the
     *  resource pipeline to produce a Drawable, which LayoutInflater already
     *  does for the XML path; wiring the programmatic path is a separate change
     *  with its own rendering blast radius and is deliberately left alone here
     *  rather than half-done. */
    /** AOSP View.setBackgroundResource(int): resolve the drawable from this
     *  view's Context and install it as the background.
     *
     *  ⚠ This used to handle ONLY `resId == 0` (clear) and silently ignore every
     *  real resource id, so `view.setBackgroundResource(R.drawable.foo)` -- an
     *  extremely common call -- produced NO background at all, with no error.
     *  Found 2026-08-24 by debug/stubscan.py, which flags an override or body
     *  that discards what the caller asked for. `getContext().getDrawable(resId)`
     *  is the idiom already used by CompoundButton, TabWidget and CalendarView. */
    public void setBackgroundResource(int resId) {
        if (resId == 0) { setBackgroundDrawable(null); return; }
        android.graphics.drawable.Drawable d = null;
        try {
            Context c = getContext();
            if (c != null) d = c.getDrawable(resId);
        } catch (Throwable ignored) {
            // A missing or unresolvable drawable must not take the app down: AOSP
            // throws NotFoundException, but this tree's resource path is more
            // fragile and a background is not worth an abort.
        }
        setBackgroundDrawable(d);
    }
    public android.graphics.drawable.Drawable getBackground() {
        if (backgroundDrawable != null) return backgroundDrawable;
        // See setBackgroundColor: a colour set through the flat fast path is
        // still a background, and AOSP represents it as a ColorDrawable. Built
        // on first ask and then kept in sync by setBackgroundColor.
        if (backgroundColor != 0) {
            if (macapkColorBackground == null) {
                macapkColorBackground =
                    new android.graphics.drawable.ColorDrawable(backgroundColor);
            }
            return macapkColorBackground;
        }
        return null;
    }

    /** View.setForeground / getForeground -- API 23+. AppCompat's
     *  createSubDecor calls setForeground(null) on the old window
     *  content view to clear any existing chrome before installing
     *  its own subDecor. Stub-store so AppCompat finds the method. */
    protected android.graphics.drawable.Drawable foregroundDrawable;
    public void setForeground(android.graphics.drawable.Drawable d) {
        // AOSP View.setForeground (View.java:23944-23951) allocates the
        // ForegroundInfo record LAZILY, and a setForeground(null) on a view
        // that has none returns before allocating. That allocation is what the
        // getter below keys on, so it has to happen at exactly the same moment.
        if (d != null) foregroundInfoCreated = true;
        this.foregroundDrawable = d;
    }
    public android.graphics.drawable.Drawable getForeground() {
        return foregroundDrawable;
    }
    /** AOSP ForegroundInfo.mGravity's initial value (View.java:4665). This is
     *  the gravity of a view that HAS a foreground record; a view with no record
     *  answers START|TOP instead (see getForegroundGravity). */
    private int foregroundGravity = android.view.Gravity.FILL;
    /** Our stand-in for `mForegroundInfo != null`. AOSP models the foreground as
     *  a separately-allocated record and both accessors branch on whether it
     *  exists; we keep the fields inline and track existence with this flag so
     *  the observable answers match. */
    private boolean foregroundInfoCreated = false;
    /** AOSP View.setForegroundGravity (View.java:24026-24043): allocate the
     *  record FIRST, then normalise an unspecified axis to START / TOP and
     *  relayout so the new bounds take effect.
     *
     *  The allocation is deliberately ahead of the equality check, exactly as
     *  AOSP has it: `setForegroundGravity(Gravity.FILL)` on a fresh view stores
     *  no new value (the record already starts at FILL) but DOES create the
     *  record, so the getter switches from START|TOP to FILL. Ordering it the
     *  other way round loses that transition. */
    public void setForegroundGravity(int gravity) {
        foregroundInfoCreated = true;
        if (foregroundGravity == gravity) return;
        if ((gravity & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
            gravity |= android.view.Gravity.START;
        }
        if ((gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK) == 0) {
            gravity |= android.view.Gravity.TOP;
        }
        foregroundGravity = gravity;
        requestLayout();
    }
    /** AOSP View.getForegroundGravity (View.java:24012-24015):
     *
     *      return mForegroundInfo != null ? mForegroundInfo.mGravity
     *              : Gravity.START | Gravity.TOP;
     *
     *  So a view that has never been given a foreground (or a foreground
     *  gravity) answers START|TOP = 0x00800033, NOT the FILL that the record
     *  itself starts at. This returned FILL for every view, which is the one
     *  answer a device never gives before the record exists.
     *
     *  Note the DRAW path (onDrawForeground below) reads the FIELD, not this
     *  getter, and is unaffected: a view with no record has no foreground
     *  drawable to position either. */
    public int getForegroundGravity() {
        return foregroundInfoCreated
            ? foregroundGravity
            : (android.view.Gravity.START | android.view.Gravity.TOP);
    }

    /** AOSP View.onDrawForeground (View.java:27026-27052) -- step 6 of draw():
     *  scroll indicators, scroll bars, then the foreground drawable positioned
     *  by its gravity inside the view's (padding-aware) self bounds.
     *
     *  Mac.apk draws no scroll chrome (setHorizontal/VerticalScrollBarEnabled
     *  and friends are inert by design on a desktop window), so the foreground
     *  pass is the only part with an observable effect here.
     *
     *  The drawable is SCOPED to our own android.graphics.drawable.* classes for
     *  exactly the reason the background-drawable pass above documents: an
     *  app-bundled Drawable (Material's MaterialShapeDrawable and the like) does
     *  not render correctly through our Canvas and painting it blind risks
     *  covering the view in an opaque box. That covers the high-value case --
     *  ?attr/selectableItemBackground resolves to a framework RippleDrawable.
     *  Keyed on the drawable's framework package, never on the app. */
    public void onDrawForeground(android.graphics.Canvas canvas) {
        final android.graphics.drawable.Drawable foreground = foregroundDrawable;
        if (foreground == null || canvas == null) return;
        final int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) return;
        try {
            if (!foreground.getClass().getName().startsWith("android.graphics.drawable.")) return;
            // AOSP mForegroundInfo.mInsidePadding defaults to true, so the self
            // bounds are the whole view; the padding-inset variant is only used
            // when a subclass asks for it.
            final Rect selfBounds = new Rect(0, 0, w, h);
            final int gravity = foregroundGravity;
            final int gx = gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
            final int gy = gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
            if (gx == android.view.Gravity.FILL_HORIZONTAL
                    && gy == android.view.Gravity.FILL_VERTICAL) {
                // The default (and overwhelmingly common) case. Handled directly
                // because android.view.Gravity.apply does not implement the FILL
                // axes and would collapse the rect to the drawable's intrinsic
                // size, which is -1 for a ColorDrawable / RippleDrawable.
                foreground.setBounds(selfBounds);
            } else {
                final Rect out = new Rect();
                android.view.Gravity.apply(gravity,
                    foreground.getIntrinsicWidth(), foreground.getIntrinsicHeight(),
                    selfBounds, out, getLayoutDirection());
                foreground.setBounds(out);
            }
            foreground.draw(canvas);
        } catch (Throwable ignored) {}
    }

    public void setOnTouchListener(OnTouchListener l) {
        this.onTouchListener = l;
        if (l != null && "1".equals(ENV_MACAPK_TRACE_TOUCH)) {
            com.macapk.loader.NativeLog.i("View",
                "setOnTouchListener(" + l.getClass().getName() + ") on "
                + getClass().getName() + " id=" + getId());
        }
    }
    public OnTouchListener getOnTouchListener()       { return onTouchListener; }
    // AOSP View.setOnClickListener: registering a click listener also makes
    // the view clickable so onTouchEvent will consume taps and fire it.
    public void setOnClickListener(OnClickListener l) {
        this.onClickListener = l;
        if (l != null) setClickable(true);
    }
    public void setOnLongClickListener(OnLongClickListener l) {
        this.onLongClickListener = l;
        if (l != null) setLongClickable(true);
    }
    public void setOnFocusChangeListener(OnFocusChangeListener l) { this.onFocusChangeListener = l; }

    // ---- Touch dispatch entry points. The framework / launcher delivers
    // synthetic MotionEvents here when the user clicks the Cocoa window.
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // AOSP (android-11.0.0_r48 View.java:14303-14307) gates the listener on
        // ENABLED as part of the condition itself:
        //     if (li != null && li.mOnTouchListener != null
        //             && (mViewFlags & ENABLED_MASK) == ENABLED
        //             && li.mOnTouchListener.onTouch(this, event)) { result = true; }
        // A DISABLED view therefore never calls its OnTouchListener at all; the
        // event falls straight through to onTouchEvent, which consumes it if the
        // view is clickable but does nothing with it. We called the listener
        // regardless, so a view the app had disabled still ran its touch handler --
        // exactly the "disabled button still fires" shape.
        if (onTouchListener != null && isEnabled()) {
            if ("1".equals(ENV_MACAPK_TRACE_TOUCH)) {
                com.macapk.loader.NativeLog.i("View",
                    "dispatchTouchEvent -> onTouchListener " + onTouchListener.getClass().getName()
                    + " on " + getClass().getName() + " ev=(" + ev.getX() + "," + ev.getY()
                    + ") action=" + ev.getAction());
            }
            if (onTouchListener.onTouch(this, ev)) return true;
        }
        return onTouchEvent(ev);
    }
    /** AOSP `View.canReceivePointerEvents()` (android-11.0.0_r48
     *  core/java/android/view/View.java:14341-14343):
     *      return (mViewFlags & VISIBILITY_MASK) == VISIBLE || getAnimation() != null;
     *
     *  ViewGroup.dispatchTouchEvent consults this per child before hit-testing it,
     *  which is what stops an INVISIBLE or GONE child from eating a touch -- with
     *  ONE deliberate exception: a view that is mid-ANIMATION still receives
     *  pointer events even while not VISIBLE, because a fade-out that has already
     *  flipped the visibility flag must keep working until the animation ends.
     *  Lives here rather than in ViewGroup because it reads View's own flags, and
     *  is the piece ViewGroup's dispatch lane needs from this file. */
    protected boolean canReceivePointerEvents() {
        return getVisibility() == VISIBLE || getAnimation() != null;
    }
    // ---- Tier 3 M18: Material ripple touch feedback ----
    // On a touch-down on a clickable view, an expanding translucent circle
    // (radius 0 -> the farthest corner) animates from the touch point via the
    // M7 Choreographer-driven ValueAnimator, drawn OVER the background and UNDER
    // the content (clipped to the view bounds), fading out as it expands -- the
    // Material "tap" ripple. Additive + gated on MACAPK_REAL_LOOPER (flag-OFF
    // never starts a ripple, so the shipped build is byte-for-byte unchanged) +
    // transient (only during the ~400ms after a tap), so the protected sweep --
    // which never touches anything -- is unaffected. Known limitation: this is a
    // tap ripple (auto-completes); a press-and-hold ripple that holds at max
    // radius until release is a follow-up.
    private android.animation.ValueAnimator rippleAnimator;
    private float rippleX, rippleY, rippleFraction, rippleMaxRadius;
    private int rippleColor;
    private void macapkStartRipple(float x, float y) {
        int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) return;
        rippleX = x; rippleY = y;
        // Max radius = the farthest corner from the hotspot, so the ripple
        // grows to cover the whole view (AOSP RippleDrawable computes the same).
        float r1 = (float) Math.hypot(x, y),     r2 = (float) Math.hypot(w - x, y);
        float r3 = (float) Math.hypot(x, h - y), r4 = (float) Math.hypot(w - x, h - y);
        rippleMaxRadius = Math.max(Math.max(r1, r2), Math.max(r3, r4));
        rippleColor = macapkRippleColor();
        if (rippleAnimator != null) { try { rippleAnimator.cancel(); } catch (Throwable ignored) {} }
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(500);
        a.addUpdateListener(an -> {
            Object v = an.getAnimatedValue();
            rippleFraction = (v instanceof Float fv) ? fv : 0f;
            invalidate();   // schedule an M6 traversal so the expanding ripple redraws
        });
        a.addListener(new android.animation.Animator.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                if (rippleAnimator == a) { rippleAnimator = null; rippleFraction = 0f; invalidate(); }
            }
        });
        rippleAnimator = a;
        if ("1".equals(ENV_MACAPK_TRACE_RIPPLE)) {
            com.macapk.loader.NativeLog.i("View", "ripple start " + getClass().getSimpleName()
                + " at (" + (int) x + "," + (int) y + ") maxR=" + (int) rippleMaxRadius
                + " color=" + Integer.toHexString(rippleColor));
        }
        try { a.start(); } catch (Throwable ignored) {}
    }
    /** Ripple highlight color: a RippleDrawable background's color when present,
     *  else a default ~12% black (colorControlHighlight fallback). */
    private int macapkRippleColor() {
        try {
            if (backgroundDrawable instanceof android.graphics.drawable.RippleDrawable rd) {
                android.content.res.ColorStateList csl = rd.getColor();
                if (csl != null) return csl.getDefaultColor();
            }
        } catch (Throwable ignored) {}
        return 0x29000000;   // ~16% black, colorControlHighlight fallback
    }
    /** Draw the active ripple (over background, under content), clipped to the
     *  view bounds, alpha fading as it expands. No-op when no ripple is active. */
    protected void macapkDrawRipple(android.graphics.Canvas canvas) {
        if (rippleAnimator == null || canvas == null) return;
        int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) return;
        float f = Math.max(0f, Math.min(1f, rippleFraction));
        float radius = f * rippleMaxRadius;
        if (radius <= 0.5f) return;
        int baseA = (rippleColor >>> 24) & 0xff; if (baseA == 0) baseA = 0x29;
        // Hold near-full alpha while the ripple expands, then fade over the last
        // 40% (Material: the highlight is visible during the press, fades on end).
        float af = (f < 0.6f) ? 1f : 1f - (f - 0.6f) / 0.4f;
        int alpha = Math.round(baseA * af);
        if (alpha <= 0) return;
        int save = canvas.save();
        canvas.macapkClipChildRect(0, 0, w, h);     // keep the ripple inside the view
        android.graphics.Paint p = new android.graphics.Paint();
        p.setAntiAlias(true);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor((alpha << 24) | (rippleColor & 0xffffff));
        canvas.drawCircle(rippleX, rippleY, radius, p);
        canvas.restoreToCount(save);
    }

    // ---- Long-press / touch-slop machinery (AOSP View.java, android16-release)
    //
    // Before v1.0.1036+ this class had NONE of it: onTouchEvent implemented only
    // DOWN/UP/CANCEL, so (a) OnLongClickListener never fired from a real touch
    // anywhere in any app -- performLongClick() had exactly one caller, the
    // accessibility action at :2153 -- and (b) there was no touch-slop
    // cancellation, so dragging off a button still fired its click on release.
    //
    // AOSP refs (core/java/android/view/View.java, android16-release):
    //   :18208-18220  DOWN -> setPressed(true, x, y) + checkForLongClick(
    //                 ViewConfiguration.getLongPressTimeout(), x, y, ...)
    //   :18240-18278  MOVE -> if (!pointInView(x, y, touchSlop)) {
    //                     removeTapCallback(); removeLongPressCallback();
    //                     if (pressed) setPressed(false); }
    //   :18150-18158  UP   -> `if (!mHasPerformedLongPress && !mIgnoreNextUpEvent)`
    //                 gates the click, so a fired long press eats the tap
    //   :29863-29876  checkForLongClick
    //   :31487-31510  CheckForLongPress.run
    //   :20521-20524  pointInView
    //
    // NOT ported, deliberately: the PREPRESSED / CheckForTap path
    // (AOSP :18225-18234). It exists so a row inside a scrolling container does
    // not flash pressed while the user is actually starting a scroll, and it
    // needs isInScrollingContainer() + ViewGroup.shouldDelayChildPressedState().
    // Without it a list row shows its pressed art immediately; the slop test
    // below still cancels it as soon as the finger moves, so the behaviour is
    // "slightly eager", not wrong. Also not ported: the CLASSIFICATION_
    // AMBIGUOUS_GESTURE / DEEP_PRESS arms, which need MotionEvent.getClassification
    // (our MotionEvent has no such field and nothing synthesises one).
    private CheckForLongPress mPendingCheckForLongPress;
    private boolean mHasPerformedLongPress;
    private int mMacapkTouchSlop = -1;

    /** AOSP View.pointInView(float,float,float) (View.java:20521-20524) verbatim:
     *  the view's own 0,0..w,h box grown by `slop`, in LOCAL coordinates. The
     *  MotionEvent reaching onTouchEvent is already local (ViewGroup
     *  .dispatchTransformedTouchEvent offset it), so no extra transform here. */
    public boolean pointInView(float localX, float localY, float slop) {
        return localX >= -slop && localY >= -slop
                && localX < (getWidth() + slop)
                && localY < (getHeight() + slop);
    }

    /** ViewConfiguration.getScaledTouchSlop() for this view's Context, memoized.
     *  Cheap-but-not-free: ViewConfiguration.get builds a fresh instance every
     *  call by design (see its comment about orientation flips), and this runs
     *  on every ACTION_MOVE. Slop is a density-derived constant, so caching the
     *  int is safe where caching the ViewConfiguration would not be. */
    private int macapkTouchSlop() {
        if (mMacapkTouchSlop < 0) {
            int slop = 8;
            try {
                Context c = getContext();
                if (c != null) slop = ViewConfiguration.get(c).getScaledTouchSlop();
            } catch (Throwable ignored) {}
            mMacapkTouchSlop = slop;
        }
        return mMacapkTouchSlop;
    }

    /** AOSP View.CheckForLongPress (View.java:31487-31528). The two guards in
     *  run() matter: the press state must not have changed since the timer was
     *  armed (a MOVE-out already un-pressed us), and the view must still be
     *  attached to a parent. AOSP additionally compares mWindowAttachCount; we
     *  have no window-attach counter, and the parent check covers the case it
     *  guards against (the view was detached while the timer was pending). */
    private final class CheckForLongPress implements Runnable {
        private float mX;
        private float mY;
        private boolean mOriginalPressedState;

        @Override public void run() {
            if (mOriginalPressedState == isPressed() && getParent() != null) {
                if (performLongClick(mX, mY)) {
                    mHasPerformedLongPress = true;
                }
            }
        }
        void setAnchor(float x, float y) { mX = x; mY = y; }
        void rememberPressedState() { mOriginalPressedState = isPressed(); }
    }

    /** AOSP View.checkForLongClick (View.java:29863-29876). Gated on
     *  LONG_CLICKABLE, so a view that is merely clickable arms no timer. */
    private void checkForLongClick(long delay, float x, float y) {
        if (isLongClickable()) {
            mHasPerformedLongPress = false;
            if (mPendingCheckForLongPress == null) {
                mPendingCheckForLongPress = new CheckForLongPress();
            }
            mPendingCheckForLongPress.setAnchor(x, y);
            mPendingCheckForLongPress.rememberPressedState();
            postDelayed(mPendingCheckForLongPress, delay);
        }
    }

    /** AOSP View.removeLongPressCallback (View.java:18338-18342). */
    private void removeLongPressCallback() {
        if (mPendingCheckForLongPress != null) removeCallbacks(mPendingCheckForLongPress);
    }

    // AOSP View.onTouchEvent: a clickable (or long-clickable) view consumes
    // touch events and fires its OnClickListener on ACTION_UP. A disabled
    // clickable view still consumes (returns clickable) so the tap doesn't
    // leak to siblings; a non-clickable view returns false so dispatch keeps
    // looking. The MotionEvent reaching here is already in this view's local
    // coordinate space (ViewGroup.dispatchTransformedTouchEvent offset it by
    // the child's position), and ViewGroup captures the down-target so the
    // matching up always lands on the same view -- hence `pressed` is set on
    // DOWN and reliably true on the UP that completes the click.
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        final boolean clickable = isClickable() || isLongClickable();
        if ("1".equals(ENV_MACAPK_TRACE_TOUCH)) {
            com.macapk.loader.NativeLog.i("View",
                "onTouchEvent " + getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(this))
                + " id=0x" + Integer.toHexString(getId())
                + " action=" + action + " clickable=" + clickable);
        }
        if (!isEnabled()) {
            // AOSP View.onTouchEvent, disabled branch (android16-release
            // core/java/android/view/View.java:18104-18106):
            //     if (action == ACTION_UP && (mPrivateFlags & PFLAG_PRESSED) != 0) {
            //         setPressed(false);
            //     }
            // setPressed, not a raw flag write -- see the block comment on the
            // clickable DOWN arm below.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (pressed) setPressed(false);
            }
            return clickable;
        }
        // AOSP View.java:18113-18117: the TouchDelegate gets FIRST refusal, ahead
        // of this view's own clickable handling, and consumes the event outright
        // when it claims it. This is how a 24dp icon button gets a 48dp touch
        // target. We stored the delegate (setTouchDelegate at :7559) and never
        // consulted it, so every expanded hit-rect in every AppCompat toolbar was
        // inert -- taps in the expanded margin hit the parent instead of the icon.
        if (mTouchDelegate != null) {
            if (mTouchDelegate.onTouchEvent(ev)) {
                return true;
            }
        }
        if (clickable) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    // ⚠ v1.0.1036+: this MUST go through setPressed(), not a raw
                    // `pressed = true`. AOSP never writes the flag directly from
                    // onTouchEvent -- android16-release View.java:18221 is
                    // `setPressed(true, x, y);` and :18265 is `setPressed(false);`.
                    //
                    // setPressed (this file, ~:5260) is what calls
                    // refreshDrawableState() and dispatchSetPressed(). All four
                    // touch-path writes in this method used to assign the field,
                    // so refreshDrawableState never ran for a real touch: a
                    // background <selector> with a state_pressed row resolved
                    // against the UN-pressed state for the whole gesture, and
                    // ViewGroup.dispatchSetPressed (correct at ViewGroup.java:1221)
                    // never recursed into children. Every button, list row and chip
                    // in every app was missing its press feedback; the only thing
                    // that still showed one was the separate M18 ripple below,
                    // which runs only under MACAPK_REAL_LOOPER.
                    mHasPerformedLongPress = false;
                    // AOSP View.onTouchEvent DOWN (android14 View.java:18167):
                    // `if (performButtonActionOnTouchDown(event)) break;` -- a
                    // mouse SECONDARY press opens the context menu and never
                    // enters the pressed state, so its UP cannot click. Host
                    // mice carry their real buttons since MACAPK_MOUSE_POINTER.
                    if (performButtonActionOnTouchDown(ev)) break;
                    setPressed(true);
                    // M18: kick off a Material ripple from the touch point.
                    if (android.os.Looper.MACAPK_REAL_LOOPER) macapkStartRipple(ev.getX(), ev.getY());
                    // AOSP View.java:18219-18226: arm the long-press timer on the
                    // same DOWN that shows the pressed state.
                    checkForLongClick(ViewConfiguration.getLongPressTimeout(),
                            ev.getX(), ev.getY());
                    break;
                case MotionEvent.ACTION_UP:
                    if (pressed) {
                        setPressed(false);
                        // AOSP View.onTouchEvent, ACTION_UP, BEFORE performClick
                        // (View.java:17083-17086, android14-release):
                        //
                        //     boolean focusTaken = false;
                        //     if (isFocusable() && isFocusableInTouchMode() && !isFocused()) {
                        //         focusTaken = requestFocus();
                        //     }
                        //
                        // This is how tapping a text field focuses it on a real
                        // device -- generic View behaviour, not something
                        // TextView or the app does. We had none of it, so no
                        // view in any app ever took focus from a touch, and
                        // therefore nothing was ever the key-dispatch target.
                        //
                        // Measured 2026-08-12 (MACAPK_TRACE_IME): across a whole
                        // F-Droid search drive, ZERO requestFocus calls of any
                        // kind, and every keystroke logged
                        // "activeInput=null activeConnection=null focused=null".
                        // The app was not at fault and neither was the harness:
                        // it tapped the field exactly as a user would.
                        //
                        // ⚠ CORRECTED: the sentence that used to stand here --
                        // "AOSP ignores the return value except to decide whether
                        // to show the pressed state, and so do we" -- was WRONG.
                        // AOSP android16-release View.java:18136-18140 captures it
                        //     boolean focusTaken = false;
                        //     if (isFocusable() && isFocusableInTouchMode() && !isFocused()) {
                        //         focusTaken = requestFocus();
                        //     }
                        // and :18151-18158 gates the click on it:
                        //     if (!focusTaken) { ... performClick ... }
                        // On a device the FIRST tap on an unfocused
                        // focusableInTouchMode view (an EditText) TAKES FOCUS AND
                        // DOES NOT CLICK; the second tap clicks. We clicked on the
                        // first tap. Blast radius is small (most clickable views
                        // are not focusable-in-touch-mode) but it is observable on
                        // any app that puts an OnClickListener on an EditText.
                        boolean focusTaken = false;
                        if (isFocusable() && isFocusableInTouchMode() && !isFocused()) {
                            focusTaken = requestFocus();
                        }
                        // AOSP View.java:18150-18158: a long press that already
                        // fired EATS the tap -- `if (!mHasPerformedLongPress
                        // && !mIgnoreNextUpEvent) { removeLongPressCallback();
                        // ... performClick ... }`. Without the gate, holding a
                        // list row would fire onItemLongClick AND onItemClick.
                        removeLongPressCallback();
                        // mIgnoreNextUpEvent: a context click consumed this
                        // button's press (dispatchGenericMotionEvent below).
                        if (!mHasPerformedLongPress && !mIgnoreNextUpEvent && !focusTaken) {
                            performClick();
                        }
                        mHasPerformedLongPress = false;
                    }
                    mIgnoreNextUpEvent = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    // AOSP View.java:18258-18272: "Be lenient about moving
                    // outside of buttons." Once the pointer leaves the view's
                    // box grown by touch slop, the gesture is a scroll or a
                    // drag-off, not a tap: cancel the pending long press and
                    // drop the pressed state, which makes the UP arm's
                    // `if (pressed)` gate fail so no click fires.
                    //
                    // ViewGroup.dispatchTouchEvent deliberately never re-hit-tests
                    // on MOVE (ViewGroup.java:816-819), so the MOVE stream does
                    // reach this same view in its own local coordinates. This
                    // slop test is therefore the ONLY thing that can cancel a
                    // press, which is exactly AOSP's arrangement.
                    if (!pointInView(ev.getX(), ev.getY(), macapkTouchSlop())) {
                        removeLongPressCallback();
                        if (pressed) setPressed(false);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    // AOSP View.java:18264-18272: `if (clickable) setPressed(false);`
                    // then removeTapCallback/removeLongPressCallback and clear
                    // mHasPerformedLongPress.
                    setPressed(false);
                    removeLongPressCallback();
                    mHasPerformedLongPress = false;
                    break;
                default:
                    break;
            }
            return true;
        }
        return false;
    }

    /** AOSP View.dispatchKeyEvent (View.java:16508-16530): the OnKeyListener gets
     *  first crack, then `event.dispatch(this, keyDispatcherState, this)` routes
     *  the event to the callback matching its ACTION -- ACTION_DOWN to onKeyDown,
     *  ACTION_UP to onKeyUp, ACTION_MULTIPLE to onKeyMultiple (falling back to a
     *  synthesized DOWN/UP pair when unhandled), per KeyEvent.dispatch
     *  (KeyEvent.java:3430-3483).
     *
     *  This used to call onKeyDown unconditionally, ignoring getAction()
     *  entirely: every ACTION_UP delivered to a focused leaf view fired
     *  onKeyDown a second time, and onKeyUp was unreachable through normal
     *  dispatch, so any "act on release" handler never ran.
     *
     *  The action switch is inlined here because android.view.KeyEvent does not
     *  expose dispatch(Callback, DispatcherState, Object) yet; the observable
     *  routing is the same. Once KeyEvent.dispatch exists this body should
     *  become the AOSP one-liner so long-press tracking flows through the
     *  DispatcherState as well. */
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev == null) return false;
        if (onKeyListener != null && isEnabled()
                && onKeyListener.onKey(this, ev.getKeyCode(), ev)) {
            return true;
        }
        final int keyCode = ev.getKeyCode();
        switch (ev.getAction()) {
            case KeyEvent.ACTION_DOWN:
            case KeyEvent.ACTION_UP:
                // AOSP View.dispatchKeyEvent: `event.dispatch(this,
                // mAttachInfo != null ? mAttachInfo.mKeyDispatchState : null,
                // this)`, which is where a tracked DOWN starts tracking, a
                // tracked long-press reaches onKeyLongPress, and the UP gets
                // FLAG_TRACKING.
                return ev.dispatch(this, getKeyDispatcherState(), this);
            case KeyEvent.ACTION_MULTIPLE: {
                final int count = ev.getRepeatCount();
                if (onKeyMultiple(keyCode, count, ev)) return true;
                // AOSP: an unhandled ACTION_MULTIPLE for a real key code is
                // re-synthesized as `count` DOWN/UP pairs.
                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    boolean handled = false;
                    for (int i = 0; i < count; i++) {
                        handled |= onKeyDown(keyCode, ev);
                        handled |= onKeyUp(keyCode, ev);
                    }
                    return handled;
                }
                return false;
            }
            default:
                return false;
        }
    }
    /** AOSP View.onKeyDown (android14 :16334-16363): a confirm key (D-pad
     *  centre, Enter, Space, numpad Enter) on an enabled clickable or
     *  long-clickable view presses it at its centre and arms the long-press
     *  timer; a disabled view swallows the key.
     *
     *  ⚠ Both bodies were `return false` (on the belief that AOSP's are), so a
     *  focused Button, card or row never clicked from the keyboard: Enter on a
     *  focused SmartTube video card went nowhere. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (KeyEvent.isConfirmKey(keyCode) && ev != null && ev.hasNoModifiers()) {
            if (!isEnabled()) return true;
            if (ev.getRepeatCount() == 0 && (isClickable() || isLongClickable())) {
                if (isClickable()) setPressed(true);
                mKeyHasPerformedLongPress = false;
                if (isLongClickable()) {
                    if (mKeyLongPress == null) {
                        mKeyLongPress = () -> {
                            if (isPressed() || !isClickable()) {
                                if (performLongClick()) mKeyHasPerformedLongPress = true;
                            }
                        };
                    }
                    removeCallbacks(mKeyLongPress);
                    postDelayed(mKeyLongPress, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            }
        }
        return false;
    }
    /** AOSP View.onKeyUp (android14 :16392-16410): releasing a confirm key on a
     *  pressed clickable view clicks it, unless the long press already fired or
     *  the key was cancelled. */
    @Override public boolean onKeyUp(int keyCode, KeyEvent ev) {
        if (KeyEvent.isConfirmKey(keyCode) && ev != null && ev.hasNoModifiers()) {
            if (!isEnabled()) return true;
            if (isClickable() && isPressed()) {
                setPressed(false);
                if (!mKeyHasPerformedLongPress) {
                    if (mKeyLongPress != null) removeCallbacks(mKeyLongPress);
                    if (!ev.isCanceled()) return performClick();
                }
            }
        }
        return false;
    }
    private Runnable mKeyLongPress;
    private boolean mKeyHasPerformedLongPress;
    /** KeyEvent.Callback. AOSP's View base bodies both return false; subclasses
     *  (and apps that route their own key events) override. */
    @Override public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent ev) { return false; }
    @Override public boolean onKeyLongPress(int keyCode, KeyEvent ev) { return false; }

    /** AOSP View text-input hooks. The base returns "not an editor" / no
     *  connection; editors (EditText) and Compose's AndroidComposeView OVERRIDE
     *  these. They MUST be declared here so a virtual call through a View
     *  reference (InputMethodManager.macapkStartInput -> served view) dispatches
     *  to the override -- without the base declaration the override occupies a
     *  different name+descriptor and the call NoSuchMethodErrors at link time.
     *  This is the missing half of the IME bind path: our IMM now calls
     *  onCreateInputConnection on the focused view to obtain the live
     *  InputConnection (Compose's RecordingInputConnection) and feeds host
     *  keystrokes into it. v1.0.425.
     *  Source: frameworks/base/core/java/android/view/View.java
     *  (onCheckIsTextEditor default false; onCreateInputConnection default null). */
    public boolean onCheckIsTextEditor() { return false; }
    public android.view.inputmethod.InputConnection onCreateInputConnection(
            android.view.inputmethod.EditorInfo outAttrs) { return null; }

    public void onAttachedToWindow() {}
    public void onDetachedFromWindow() {}
    public void onSizeChanged(int w, int h, int oldw, int oldh) {}

    // ---------------------------------------------------------------
    // Drawable-state system (M23, flag-gated on MACAPK_REAL_LOOPER).
    //
    // Real Android tracks a per-view "drawable state" -- the sorted set
    // of active android.R.attr.state_* ids (pressed/enabled/focused/
    // selected/activated/hovered/window_focused) -- and forwards it to
    // every stateful drawable the view owns (background, foreground) and
    // to its StateListAnimator, so StateListDrawable / ColorStateList /
    // StateListAnimator pick the entry matching the current state.
    //
    // Flag-OFF keeps the historical STUB behavior (getDrawableState ->
    // int[0], refresh/changed = no-op), because StateSet.stateSetMatches
    // is ALSO a permissive always-match stub off-flag and several current
    // canary renders rely on that "first entry always wins" behavior; the
    // shipped build therefore stays byte-for-byte unchanged. Flag-ON gives
    // the real state array, which the flag-ON StateSet.stateSetMatches
    // consumes for correct per-state selection.
    //
    // Attr ids are the REAL framework resource ids (extracted from the
    // android-34 android.jar R$attr): the app's compiled <selector>s
    // reference these exact ints, so getDrawableState must emit them.
    // Ordered ascending by id (AOSP's VIEW_STATE_SETS table is sorted;
    // StateSet matching is order-independent but we stay faithful).
    private static final int STATE_FOCUSED        = 16842908; // 0x0101009c
    private static final int STATE_WINDOW_FOCUSED = 16842909; // 0x0101009d
    private static final int STATE_ENABLED        = 16842910; // 0x0101009e
    private static final int STATE_SELECTED       = 16842913; // 0x010100a1
    private static final int STATE_PRESSED        = 16842919; // 0x010100a7
    private static final int STATE_ACTIVATED      = 16843518; // 0x010102fe
    private static final int STATE_ACCELERATED    = 16843547; // 0x0101031b
    private static final int STATE_HOVERED        = 16843623; // 0x01010367

    private boolean selected = false;
    private boolean activated = false;
    private boolean hovered = false;
    private int[] mDrawableState = null;
    private boolean mDrawableStateDirty = true;

    public void refreshDrawableState() {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return;
        mDrawableStateDirty = true;
        drawableStateChanged();
    }

    protected int[] onCreateDrawableState(int extraSpace) {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return new int[0];
        // AOSP: DUPLICATE_PARENT_STATE delegates to the parent's state.
        if (duplicateParentState && getParent() instanceof View) {
            return ((View) getParent()).onCreateDrawableState(extraSpace);
        }
        // Count active states, allocate, fill ascending-by-id (matches
        // AOSP VIEW_STATE_SETS ordering). window_focused mirrors AOSP's
        // hasWindowFocus() which is true while we're rendering.
        // state_accelerated mirrors AOSP View.onCreateDrawableState's
        //     if (mAttachInfo != null && mAttachInfo.mHardwareAccelerationRequested)
        //         viewStateIndex |= StateSet.VIEW_STATE_ACCELERATED;
        // Our stand-in for that exact predicate is isHardwareAccelerated() (==
        // macapkAttached, documented as such at its declaration). Without it a
        // selector row carrying `-state_accelerated` spuriously MATCHED on every
        // attached view, which is a silent wrong-row pick rather than a visible
        // failure.
        int n = 0;
        if (isFocused())        n++;
        if (hasWindowFocus())   n++;
        if (isEnabled())        n++;
        if (selected)           n++;
        if (pressed)            n++;
        if (activated)          n++;
        if (isHardwareAccelerated()) n++;
        if (hovered)            n++;
        int[] s = new int[n + extraSpace];
        int i = 0;
        if (isFocused())        s[i++] = STATE_FOCUSED;
        if (hasWindowFocus())   s[i++] = STATE_WINDOW_FOCUSED;
        if (isEnabled())        s[i++] = STATE_ENABLED;
        if (selected)           s[i++] = STATE_SELECTED;
        if (pressed)            s[i++] = STATE_PRESSED;
        if (activated)          s[i++] = STATE_ACTIVATED;
        if (isHardwareAccelerated()) s[i++] = STATE_ACCELERATED;
        if (hovered)            s[i++] = STATE_HOVERED;
        return s;
    }

    public final int[] getDrawableState() {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return new int[0];
        if (mDrawableState != null && !mDrawableStateDirty) return mDrawableState;
        mDrawableState = onCreateDrawableState(0);
        mDrawableStateDirty = false;
        return mDrawableState;
    }

    /** PROTECTED, as on AOSP (View.java: `protected void drawableStateChanged()`).
     *  It was public here, which is a wider access level than the platform
     *  declares -- an app subclass compiled against the real android.jar
     *  declares the protected form, and a framework whose base method is public
     *  cannot be overridden by it under the JLS rule that an override may not
     *  narrow access. Our own widget subclasses declare it public, which is a
     *  legal WIDENING and stays legal. */
    protected void drawableStateChanged() {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return;
        int[] state = getDrawableState();
        boolean changed = false;
        // Forward to the background DRAWABLE if the view owns a stateful
        // one (e.g. a StateListDrawable). NOTE: the backgroundColor int
        // draw path is unchanged; this only affects views that actually
        // set a Drawable background. setState returns true when the
        // drawable's appearance changed -> we invalidate.
        try {
            if (backgroundDrawable != null && backgroundDrawable.isStateful()) {
                changed |= backgroundDrawable.setState(state);
            }
        } catch (Throwable ignored) {}
        // Per-state background tint for the FLAT backgroundColor branch. AOSP
        // resolves the tint inside the background DRAWABLE on each setState; our
        // flat branch has no drawable, so the resolved int is re-cached here.
        // Non-stateful lists resolve to the same colour every time and are
        // skipped, which keeps them byte-identical to the old flatten.
        if (macapkBackgroundTintList != null && macapkBackgroundTintList.isStateful()) {
            try {
                int next = macapkBackgroundTintList.getColorForState(
                    state, macapkBackgroundTintList.getDefaultColor());
                if (macapkBackgroundTint == null || macapkBackgroundTint != next) {
                    macapkBackgroundTint = next;
                    changed = true;
                }
            } catch (Throwable ignored) {}
        }
        if (stateListAnimator != null) {
            try { stateListAnimator.setState(state); } catch (Throwable ignored) {}
        }
        if (changed) invalidate();
    }

    public void jumpDrawablesToCurrentState() {
        if (android.os.Looper.MACAPK_REAL_LOOPER && stateListAnimator != null) {
            try { stateListAnimator.jumpToCurrentState(); } catch (Throwable ignored) {}
        }
    }

    /** AOSP View.setSystemUiVisibility. This was an EMPTY NO-OP, and that
     *  silently dropped every pre-API-30 edge-to-edge request in the fleet.
     *
     *  We report `Build.VERSION.SDK_INT = 29`, and `Window.setDecorFitsSystemWindows`
     *  -- the call we DO listen for -- is API 30. So AndroidX's
     *  `WindowCompat.setDecorFitsSystemWindows(window, false)` takes its older
     *  implementation, whose ENTIRE signal is:
     *
     *      int flags = SYSTEM_UI_FLAG_LAYOUT_STABLE
     *                | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
     *                | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
     *      int vis = decorView.getSystemUiVisibility();
     *      decorView.setSystemUiVisibility(fits ? vis & ~flags : vis | flags);
     *
     *  Both halves of that were broken here: this method discarded the write and
     *  getSystemUiVisibility() returned a hardcoded 0, so the read-modify-write
     *  could not round-trip either. The app asked to draw behind the system bars,
     *  we never heard it, and StatusBarDriver kept painting an opaque strip over
     *  content the reference device leaves showing.
     *
     *  Measured: 6 of 15 fleet apps have a nav-bar mismatch on this path (anki,
     *  antennapod, deskclock, fdroid, mastodon, newpipe), and their bundled
     *  callers are exactly the edge-to-edge helpers -- androidx.activity's
     *  EdgeToEdgeApi23/26/29 and material's EdgeToEdgeUtils. opencalculator,
     *  which declares the same intent through its THEME instead, already renders
     *  correctly; that asymmetry is what identifies this method as the gap.
     *
     *  LAYOUT_STABLE alone is deliberately NOT treated as an edge-to-edge
     *  request: apps set it by itself just to freeze inset reporting, and taking
     *  it as consent would strip the bars from apps that never asked. */
    public void setSystemUiVisibility(int flags) {
        this.systemUiVisibility = flags;
        // ⚠ HONOURING THIS AS AN EDGE-TO-EDGE REQUEST IS CORRECT AOSP AND
        // MEASURED CATASTROPHIC (v1.0.876): wikipedia 88.29 -> 64.11,
        // mastodon 81.81 -> 74.87, newpipe 51.87 -> 47.64. Default OFF.
        //
        // The MECHANISM is right -- these apps really are asking to draw behind
        // the system bars, and we really were dropping the request. What is
        // missing is the OTHER HALF of the AOSP contract: an app that goes
        // edge-to-edge then re-applies the insets itself, via
        // ViewCompat.setOnApplyWindowInsetsListener / fitsSystemWindows, so its
        // content ends up in the same place with the bars merely painted behind
        // it. We do not dispatch real WindowInsets, so the app never gets that
        // callback -- we stop reserving the strip and NOTHING puts the padding
        // back, and the whole layout slides up under the status bar.
        //
        // So this is a PREREQUISITE ordering problem, not a wrong diagnosis:
        // real WindowInsets dispatch has to land BEFORE the request can be
        // honoured. Both analyst lanes flagged the regression risk ("removing
        // the black strip exposes whatever we draw underneath") and both were
        // right; the risk was simply larger than the reward at this stage.
        //
        // The flag STORAGE above is kept unconditionally -- getSystemUiVisibility
        // must round-trip for AndroidX's read-modify-write regardless, and that
        // part is harmless and correct on its own.
        if ("1".equals(ENV_MACAPK_EDGE_TO_EDGE)) {
            final int edgeToEdge = SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                 | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            try {
                android.view.Window.macapkSetDecorFits((flags & edgeToEdge) == 0);
            } catch (Throwable ignored) {}
        }
        // ---- the HIDE bits, which are a DIFFERENT REQUEST from the block above.
        //
        // v1.0.962 made the ViewCanvasDriver overlay actually reach the screen,
        // and that exposed chrome the framework had been painting into a
        // discarded buffer: an opaque status bar and nav strip sitting on top of
        // immersive games. GD Lite asks not to have them, in its own shipped
        // bytecode -- com.customRobTop.BaseRobTopActivity disassembles to
        //     sipush 5894 ; invokevirtual android/view/View.setSystemUiVisibility
        // and 5894 = 0x1706 carries SYSTEM_UI_FLAG_FULLSCREEN (0x4) and
        // SYSTEM_UI_FLAG_HIDE_NAVIGATION (0x2). We stored the word and consumed
        // neither bit, so we kept painting bars a device would have removed.
        //
        // ⚠ THE GATED BLOCK ABOVE AND THIS ONE ARE NOT THE SAME MECHANISM, and
        // the split is AOSP's, not ours: ViewRootImpl feeds the identical
        // systemUiVisibility word to two methods, adjustLayoutParamsForCompat-
        // ibility for the LAYOUT_* bits (bars stay VISIBLE, app re-applies the
        // insets -- the half we cannot complete, hence the env gate) and
        // controlInsetsForCompatibility for the HIDE bits (bar is REMOVED, so
        // nothing has to put padding back). Full citation at the callee.
        //
        // MEASURED BLAST RADIUS, from the shipped bytecode of the 11 converted
        // apps on this machine rather than from reasoning about what androidx
        // "probably" sends: the HIDE bits are passed ONLY by games and by
        // fullscreen ad-SDK activities (gdlite, mindustry, unciv, subway,
        // crossy; applovin / mbridge / inmobi / vungle / fyber / kidoz / moloco).
        // Every widget-tier app that calls this method -- F-Droid, Calculator,
        // Aurora Store, Vector Pinball -- passes only LAYOUT_* / LIGHT_* /
        // LOW_PROFILE (0x700, 0x500, 0x100, 0x2000, 0x400, 0x10), so for them
        // typesToHide and typesToShow are both 0 and this call returns without
        // touching anything. That is the exact population the v1.0.876
        // regression was measured on.
        try {
            android.view.Window.macapkControlInsetsForCompatibility(
                    flags, getWindowInsetsController());
        } catch (Throwable ignored) {}
    }
    private int systemUiVisibility;

    /** AOSP View.getLocationInWindow(int[] outLocation) writes the
     *  view's screen coordinates into a length-2 int array. Used by
     *  apps that compute touch-hit zones in screen space. Per
     *  frameworks/base/core/java/android/view/View.java this walks
     *  up the parent chain accumulating offsets. However, ViewCanvas-
     *  Driver currently relies on the legacy v1.0.69 behavior of
     *  returning (left, top) only -- walking the parent chain caused
     *  a regression. Sticking with the legacy until we can audit
     *  the renderer for screen-vs-local coord assumptions. */
    public void getLocationInWindow(int[] outLocation) {
        // AOSP :24820 throws rather than returning quietly. A caller who passed
        // a too-short array got silence and then read uninitialised zeros --
        // the bug lands at the read site, far from the mistake (fwprobe
        // VW.det.locationinwindow.shortarray).
        if (outLocation == null || outLocation.length < 2) {
            throw new IllegalArgumentException("outLocation must be an array of two integers");
        }
        outLocation[0] = 0;
        outLocation[1] = 0;
        transformFromViewToWindowSpace(outLocation);
    }
    /** AOSP `View.transformFromViewToWindowSpace` (android-11.0.0_r48
     *  View.java:24829-24877). The part that matters here is its FIRST branch:
     *      if (mAttachInfo == null) {
     *          // When the view is not attached to a window, this method does not
     *          // make sense
     *          inOutLocation[0] = inOutLocation[1] = 0;
     *          return;
     *      }
     *  A detached view has no window, so it has no position in one, and AOSP
     *  answers 0,0 rather than an accumulated tree-local coordinate. We answered
     *  the accumulation unconditionally: fwprobe DISP.hier.t1/t3.*.locInWindow
     *  and .locOnScreen read 0,0 on the reference and 20,40 / 200,40 / 50,60 /
     *  70,90 / 80,100 here, on a tree the probe deliberately never attaches.
     *
     *  Same stand-in and the same known cost as isShown() above (macapkAttached
     *  for mAttachInfo != null; overlay roots are never attached, so a popup's
     *  children answer 0,0 until WindowOverlayStack dispatches attach). Shares the
     *  MACAPK_ISSHOWN_DETACHED=0 bisect gate, because it is the same root cause and
     *  the two want to be A/B-ed together. */
    public void transformFromViewToWindowSpace(int[] inOutLocation) {
        if (inOutLocation == null || inOutLocation.length < 2) return;
        if (sIsShownDetachedAosp && !macapkAttached) {
            inOutLocation[0] = inOutLocation[1] = 0;
            return;
        }
        // AOSP: accumulate UP the parent chain so the result is this view's
        // top-left in WINDOW space, not just within its immediate parent. At
        // each level a view sits at (left,top) in its parent, shifted by the
        // parent's scroll -- matching dispatchDraw's child position
        // (left - parentScrollX). The previous (left,top)-only result
        // mislocated popups / IME cursor anchor / Compose boundsInWindow /
        // Toolbar-overflow anchoring for every view inside a nested tree.
        // v1.0.422.
        //
        // ⚠ v1.0.1036+: the walk now applies each level's TRANSFORM MATRIX, which
        // AOSP does at every level (android16-release View.java,
        // transformFromViewToWindowSpace):
        //     if (!hasIdentityMatrix()) { getMatrix().mapPoints(position); }
        //     position[0] += mLeft; position[1] += mTop;
        //     while (viewParent instanceof View) {
        //         position[0] -= view.mScrollX; position[1] -= view.mScrollY;
        //         if (!view.hasIdentityMatrix()) { view.getMatrix().mapPoints(position); }
        //         position[0] += view.mLeft; position[1] += view.mTop;
        //     }
        // We had no matrix step, under a comment (on getBoundsOnScreen) claiming
        // "no scale/rotation/translation is applied at draw time in our
        // renderer". That comment is STALE: ViewGroup.dispatchDraw applies the
        // child transform (ViewGroup.java:1975-1976, `if (MACAPK_REAL_LOOPER &&
        // c.macapkHasTransform()) c.macapkApplyTransform(canvas)`) and the touch
        // and visible-rect paths consult the same matrix (:1320,1345,2348,2355,
        // 2459). So draw and touch agreed with each other and disagreed with
        // every window-space query -- Compose's boundsInWindow and the IME
        // cursor anchor being the live consumers.
        //
        // hasIdentityMatrix() short-circuits, so a tree with no animated view --
        // the overwhelming majority -- takes exactly the previous integer path
        // and produces byte-identical results.
        float px = inOutLocation[0];
        float py = inOutLocation[1];
        boolean anyTransform = false;
        View v = this;
        while (v != null) {
            if (v.macapkHasAnyTransform() && !v.hasIdentityMatrix()) {
                anyTransform = true;
                float[] pt = new float[] { px, py };
                v.getMatrix().mapPoints(pt);
                px = pt[0]; py = pt[1];
            }
            px += v.getLeft();
            py += v.getTop();
            ViewParent p = v.getParent();
            if (p instanceof View) {
                View pv = (View) p;
                px -= pv.getScrollX();
                py -= pv.getScrollY();
                v = pv;
            } else {
                v = null;
            }
        }
        inOutLocation[0] = anyTransform ? Math.round(px) : (int) px;
        inOutLocation[1] = anyTransform ? Math.round(py) : (int) py;
    }

    /** AOSP View.mapRectFromViewToScreenCoords (android16-release View.java), the
     *  Rect twin of transformFromViewToWindowSpace: map a LOCAL rect up the
     *  parent chain into window space, applying each level's matrix and scroll.
     *  Single-window runtime, so window space IS screen space here (same
     *  identity getLocationOnScreen relies on). */
    private void macapkMapRectFromViewToWindow(android.graphics.RectF rect) {
        if (rect == null) return;
        if (macapkHasAnyTransform() && !hasIdentityMatrix()) getMatrix().mapRect(rect);
        rect.offset(getLeft(), getTop());
        ViewParent parent = getParent();
        while (parent instanceof View) {
            View pv = (View) parent;
            rect.offset(-pv.getScrollX(), -pv.getScrollY());
            if (pv.macapkHasAnyTransform() && !pv.hasIdentityMatrix()) pv.getMatrix().mapRect(rect);
            rect.offset(pv.getLeft(), pv.getTop());
            parent = pv.getParent();
        }
    }
    /** AOSP View.getLocationOnScreen: same as getLocationInWindow
     *  in our single-window environment (no decor offset, no status
     *  bar). Real Android adds the window's screen position. */
    public void getLocationOnScreen(int[] outLocation) {
        getLocationInWindow(outLocation);
    }

    /** AOSP View.getGlobalVisibleRect(Rect, Point): computes the
     *  intersection of this view's screen bounds with all ancestor
     *  clip rects, optionally returning the (l,t) origin via the
     *  optional Point. Compose & menu-positioning code reads through
     *  this; returns true if the visible region is non-empty. */
    public boolean getGlobalVisibleRect(Rect r, android.graphics.Point globalOffset) {
        // AOSP `View.getGlobalVisibleRect` (android-11.0.0_r48 View.java:18021-18032),
        // ported verbatim:
        //     int width = mRight - mLeft;  int height = mBottom - mTop;
        //     if (width > 0 && height > 0) {
        //         r.set(0, 0, width, height);
        //         if (globalOffset != null) globalOffset.set(-mScrollX, -mScrollY);
        //         return mParent == null || mParent.getChildVisibleRect(this, r, globalOffset);
        //     }
        //     return false;
        //
        // The whole point is that the View side does NOT compute screen
        // coordinates. It hands the parent a view-LOCAL rect and each ancestor
        // translates it by (child.left - scrollX, child.top - scrollY) and then
        // CLIPS it to its own bounds -- clipping is the part a location-plus-size
        // computation can never do. Ours built the rect from getLocationOnScreen
        // and never asked any ancestor, so a child sticking out of its parent
        // reported its full unclipped rect: fwprobe DISP.hier.t3.N.globalVisRect
        // reference 70,90,350,360 (N is 300x300 inside a 300x300 M at 50,60, so M
        // clips it), ours 70,90,370,390.
        //
        // ViewGroup.getChildVisibleRect (ViewGroup.java, matrix + scroll +
        // clipChildren + clipToPadding) is the other half and is already in place;
        // ViewParent's default implementation returns true without touching the
        // rect, so a non-ViewGroup parent degrades to the unclipped answer rather
        // than to a wrong one.
        if (r == null) return false;
        int width = right - left;
        int height = bottom - top;
        if (width > 0 && height > 0) {
            r.set(0, 0, width, height);
            if (globalOffset != null) {
                globalOffset.x = -getScrollX();
                globalOffset.y = -getScrollY();
            }
            return parent == null || parent.getChildVisibleRect(this, r, globalOffset);
        }
        return false;
    }
    public boolean getGlobalVisibleRect(Rect r) { return getGlobalVisibleRect(r, null); }

    /** AOSP getLocalVisibleRect (View.java:18038-18045), ported verbatim:
     *      final Point offset = mAttachInfo != null ? mAttachInfo.mPoint : new Point();
     *      if (getGlobalVisibleRect(r, offset)) { r.offset(-offset.x, -offset.y); return true; }
     *      return false;
     *  The offset is not decoration. getGlobalVisibleRect seeds it with
     *  (-mScrollX, -mScrollY) (:18027), so the local rect of a SCROLLED view
     *  starts at the scroll position, not at 0,0 -- which is the whole point of
     *  the method for a scrolling container deciding what part of itself is on
     *  screen. Ours ignored the scroll entirely: fwprobe
     *  VW.det.localvisiblerect.scrolled read 30,40,230,140 on the reference and
     *  0,0,200,100 here after scrollTo(30,40). */
    public boolean getLocalVisibleRect(Rect r) {
        if (r == null) return false;
        final android.graphics.Point offset = new android.graphics.Point();
        if (getGlobalVisibleRect(r, offset)) {
            r.offset(-offset.x, -offset.y);
            return true;
        }
        return false;
    }

    /** AOSP View.getHitRect(Rect) (android16-release View.java):
     *      if (hasIdentityMatrix() || mAttachInfo == null) {
     *          outRect.set(mLeft, mTop, mRight, mBottom);
     *      } else {
     *          tmpRect.set(0, 0, getWidth(), getHeight());
     *          getMatrix().mapRect(tmpRect);
     *          outRect.set((int) tmpRect.left + mLeft, (int) tmpRect.top + mTop,
     *                      (int) tmpRect.right + mLeft, (int) tmpRect.bottom + mTop);
     *      }
     *  We had only the first arm, so a parent hit-testing an animated child
     *  (a scaled FAB, a translating drawer) tested the child's UNTRANSFORMED
     *  rect while dispatchDraw painted the transformed one. */
    public void getHitRect(Rect outRect) {
        if (outRect == null) return;
        if (!macapkHasAnyTransform() || hasIdentityMatrix()) {
            outRect.left = left;
            outRect.top = top;
            outRect.right = right;
            outRect.bottom = bottom;
            return;
        }
        android.graphics.RectF tmp = new android.graphics.RectF(0, 0, getWidth(), getHeight());
        getMatrix().mapRect(tmp);
        outRect.left   = (int) tmp.left   + left;
        outRect.top    = (int) tmp.top    + top;
        outRect.right  = (int) tmp.right  + left;
        outRect.bottom = (int) tmp.bottom + top;
    }

    /** AOSP View.getBoundsOnScreen -> mapRectFromViewToScreenCoords: the
     *  screen-space rect of this view INCLUDING its transformation matrix.
     *
     *  ⚠ The comment that used to stand here -- "we simplify by ignoring
     *  transforms (no scale/rotation/translation is applied at draw time in our
     *  renderer)" -- was stale; see transformFromViewToWindowSpace for the
     *  evidence. Delegating to getLocationOnScreen + width/height was correct
     *  only for an identity matrix, and silently wrong for a scaled or rotated
     *  view: it reported the untransformed SIZE at the transformed ORIGIN. */
    public void getBoundsOnScreen(Rect outRect) {
        if (outRect == null) return;
        if (!macapkHasAnyTransform() || hasIdentityMatrix()) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            outRect.left = loc[0];
            outRect.top = loc[1];
            outRect.right = loc[0] + getWidth();
            outRect.bottom = loc[1] + getHeight();
            return;
        }
        android.graphics.RectF r = new android.graphics.RectF(0, 0, getWidth(), getHeight());
        macapkMapRectFromViewToWindow(r);
        outRect.left   = Math.round(r.left);
        outRect.top    = Math.round(r.top);
        outRect.right  = Math.round(r.right);
        outRect.bottom = Math.round(r.bottom);
    }

    /** AOSP View.setWillNotDraw / willNotDraw: hints to the framework
     *  that this View does not paint anything. ViewGroups default to
     *  willNotDraw=true so dispatchDraw can skip them. AppCompat's
     *  ViewStubCompat sets it explicitly. We store the flag but don't
     *  optimize on it (our Canvas pass paints everything anyway). */
    private boolean willNotDraw;
    public void setWillNotDraw(boolean w)        { this.willNotDraw = w; }
    public boolean willNotDraw()                 { return willNotDraw; }
    /** AOSP setWillNotCacheDrawing/willNotCacheDrawing (View.java:12194) --
     *  the WILL_NOT_CACHE_DRAWING bit, default off. Same reasoning as
     *  drawingCacheEnabled above: we cache nothing either way, but the flag is
     *  read back (fwprobe VW.flag.willNotCacheDrawing.true). */
    private boolean mWillNotCacheDrawing;
    public void setWillNotCacheDrawing(boolean w) { mWillNotCacheDrawing = w; }
    public boolean willNotCacheDrawing()         { return mWillNotCacheDrawing; }

    /** AOSP View.OnApplyWindowInsetsListener -- fires when window
     *  insets (status bar, gesture nav) reach this view via the
     *  dispatchApplyWindowInsets cascade. Material's BottomNavigationView
     *  / CoordinatorLayout / AppBarLayout register one in their
     *  constructors via ViewCompat.setOnApplyWindowInsetsListener; the
     *  listener typically adds `inset.bottom` to the view's paddingBottom
     *  so the visible content sits above the system bar.
     *
     *  v1.0.136: the listener is now actually invoked (was a no-op
     *  stub before). See setOnApplyWindowInsetsListener + dispatchApply-
     *  WindowInsets directly below. */
    public interface OnApplyWindowInsetsListener {
        android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets);
    }
    public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener l) {
        // Routes through the same field as the Object-typed overload
        // (line ~835). ViewCompat (API 21+ path) lands here.
        onApplyWindowInsetsListener = l;
        if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
            try {
                com.macapk.loader.NativeLog.i("View",
                    "setOnApplyWindowInsetsListener on "
                    + getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " listener=" + (l == null ? "null" : l.getClass().getName()));
            } catch (Throwable ignored) {}
        }
    }
    /** AOSP dispatch: invoke the registered listener if any (the
     *  listener overrides our onApplyWindowInsets), else fall through
     *  to onApplyWindowInsets which applies the AOSP-default
     *  fitsSystemWindows -> padding consumption. ViewGroup overrides
     *  this to walk children once the listener/onApplyWindowInsets
     *  is done -- depth-first, stopping at the first consumed bag. */
    public android.view.WindowInsets dispatchApplyWindowInsets(android.view.WindowInsets insets) {
        if (insets == null) return null;
        if ("1".equals(ENV_MACAPK_TRACE_INSETS)) {
            try {
                com.macapk.loader.NativeLog.i("View",
                    "dispatchApplyWindowInsets on "
                    + getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " listener=" + (onApplyWindowInsetsListener != null)
                    + " sys=" + insets.getSystemWindowInsets());
            } catch (Throwable ignored) {}
        }
        if (onApplyWindowInsetsListener != null) {
            try {
                android.view.WindowInsets out = onApplyWindowInsetsListener.onApplyWindowInsets(this, insets);
                return out != null ? out : insets;
            } catch (Throwable t) {
                // Listener errors must not kill the renderer; log
                // once-per-view and fall back to the default path.
                if (!macapkWarnedInsetListener) {
                    macapkWarnedInsetListener = true;
                    try {
                        com.macapk.loader.NativeLog.w("View",
                            "onApplyWindowInsetsListener for "
                            + getClass().getSimpleName() + "@"
                            + Integer.toHexString(System.identityHashCode(this))
                            + " threw " + t);
                    } catch (Throwable ignored) {}
                }
                return onApplyWindowInsets(insets);
            }
        }
        return onApplyWindowInsets(insets);
    }
    /** AOSP: signals that the cached insets are stale and we want a
     *  fresh dispatch from the root. The render driver re-dispatches
     *  on the next frame when this flag is set on any view in the
     *  tree -- mirrors AOSP's `mPrivateFlags3 |= PFLAG3_NEEDS_DISPATCH`
     *  + a posted dispatch from ViewRootImpl. Generic across any APK
     *  that calls requestApplyInsets() on its own widgets. */
    public void requestApplyInsets() {
        macapkInsetsDispatchPending = true;
    }
    public boolean macapkConsumeInsetsDispatchPending() {
        boolean r = macapkInsetsDispatchPending;
        macapkInsetsDispatchPending = false;
        return r;
    }
    private boolean macapkInsetsDispatchPending = true;
    private boolean macapkWarnedInsetListener = false;

    /** addOnAttachStateChangeListener: fires when the view is
     *  attached to / detached from a window. AOSP wires this through
     *  ViewRoot attachment events; we don't have those. The listener
     *  is stored so apps don't NPE on remove, but callbacks are NOT
     *  fired -- apps that depend on this for lifecycle work will
     *  under-react. Flappy Bird's main menu registers one for a
     *  Google Sign-In SDK callback; that path is dead on macOS
     *  anyway. The OnAttachStateChangeListener interface itself is
     *  defined further down in this file (alongside other
     *  per-listener interfaces). */
    private java.util.List<OnAttachStateChangeListener> attachStateChangeListeners;
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) {
        if (l == null) return;
        if (attachStateChangeListeners == null) attachStateChangeListeners = new java.util.ArrayList<>();
        attachStateChangeListeners.add(l);
    }
    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) {
        if (attachStateChangeListeners != null) attachStateChangeListeners.remove(l);
    }
    /** Must return what was SET: AndroidX's pre-30 edge-to-edge helper does a
     *  read-modify-write (`vis | flags` / `vis & ~flags`), so a hardcoded 0 made
     *  the clear-path a no-op and lost any flags the app had set itself. */
    public int  getSystemUiVisibility() { return systemUiVisibility; }
    /** AOSP View.getRootWindowInsets (android-11.0.0_r48 core/java/android/view/
     *  View.java:11521-11526):
     *
     *      public WindowInsets getRootWindowInsets() {
     *          if (mAttachInfo != null) {
     *              return mAttachInfo.mViewRootImpl.getWindowInsets(false);
     *          }
     *          return null;
     *      }
     *
     *  NULL while the view is detached, per its own javadoc ("The insets are
     *  only available if the view is attached"). macapkAttached is this file's
     *  mAttachInfo != null stand-in, the same gate getWindowToken() and
     *  hasWindowFocus() already read.
     *
     *  WHY THIS USED TO RETURN A STUB UNCONDITIONALLY, and what changed so it
     *  no longer has to. axmol's `AxmolEngine.getSafeInsets` does
     *  `getWindow().getDecorView().getRootWindowInsets()` and then
     *  `WindowInsets.getDisplayCutout()` with NO null check in between --
     *  verified against OpenGD 0.1.0-beta's own converted bytecode, where bci 89
     *  is getRootWindowInsets, bci 96 is getDisplayCutout and there is no ifnull
     *  between them -- so a null answer NPEs inside the engine. On a device that
     *  never happens, because the decor is attached from wm.addView onward.
     *  Here it was attached by NOBODY: our ActivityWindow decor is not an
     *  ancestor of the installed content tree, so ViewCanvasDriver (which
     *  attaches the CONTENT root) never reached it, and the decor stayed
     *  detached for the whole life of the process. Activity's content install
     *  now attaches the decor at window-show time, restoring the device
     *  invariant "the decor is attached while the window is showing"; axmol asks
     *  from inside Director::drawScene, long after setContentView, so it still
     *  gets a non-null answer.
     *
     *  A caller that asks BEFORE any content exists now gets the device's
     *  answer, null. That is the whole point: an app that dereferences it there
     *  crashes on a real device too. */
    public WindowInsets getRootWindowInsets() {
        if (!macapkAttached) return null;
        // AOSP returns the ROOT WINDOW's bag -- ViewRootImpl.getWindowInsets()
        // (:2281-2298), the same mLastWindowInsets object dispatchApplyInsets
        // (:2300-2312) hands to the tree. We answered an EMPTY bag, so every app
        // asking the system where the bars are was told "nowhere".
        //
        // This is NOT contradicted by our zeroed dispatch. AOSP's
        // PhoneWindow.sDefaultContentInsetsApplier (:159-167) hands everything
        // below R.id.content `insets.inset(insetsToApply).consumeSystemWindowInsets()`
        // -- a truthful root bag and a zero dispatched bag are the two ENDS of
        // the applier, not a conflict.
        //
        // macapkMetricsInsets() is already the correct producer (it has answered
        // getCurrentWindowMetrics().getWindowInsets() since v1.0.939), so this
        // removes one of three disagreeing answers rather than inventing a
        // fourth. It also handles landscape for free: v1.0.997's navBarPosition
        // puts the strip on the RIGHT edge there.
        //
        // ⚠ The gesture/tappable slots deliberately stay NONE. Builder's
        // setSystemWindowInsets/setStableInsets route through assignCompatInsets
        // (WindowInsets.java:236-241), which fills ONLY the statusBars and
        // navigationBars slots. On a real 3-button device
        // mandatorySystemGestures/tappableElement approximate the nav region --
        // and that is exactly what Material's snackbar and DrawerLayout read.
        // Filling them would DOUBLE-COUNT against a window we already shrank.
        // Do not "complete" the bag.
        WindowInsets wi = android.view.WindowManager.macapkMetricsInsets();
        if (macapkTraceInsets()) macapkTraceRootInsetsCaller();
        return wi;
    }

    /** Falsifier instrument for the change above (docs/root-window-insets-spec_2026-08-21.md).
     *  The claim is that no fleet app converts the root bag's systemBars values
     *  into layout. This names each distinct caller ONCE so that claim can be
     *  checked before the pixels are, which is both cheaper and more direct than
     *  a screenshot. Off unless MACAPK_TRACE_INSETS is set. */
    private static final java.util.Set<String> sRootInsetCallers =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static Boolean sTraceInsets;
    private static boolean macapkTraceInsets() {
        if (sTraceInsets == null) sTraceInsets = System.getenv("MACAPK_TRACE_INSETS") != null;
        return sTraceInsets;
    }
    private static void macapkTraceRootInsetsCaller() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (int i = 1; i < st.length && i < 6; i++) {
                String cn = st[i].getClassName();
                if (cn.equals("android.view.View")) continue;
                if (sRootInsetCallers.add(cn)) {
                    com.macapk.loader.NativeLog.i("Mac.apk.View",
                        "getRootWindowInsets caller: " + cn + "." + st[i].getMethodName());
                }
                break;
            }
        } catch (Throwable ignored) {}
    }
    // No-op on macOS; we don't have a status/nav bar to hide. Games
    // register a listener here to react to the user pulling the nav
    // bar back in. Storing it would only matter if we ever decided to
    // simulate that input.
    public void setOnSystemUiVisibilityChangeListener(
        OnSystemUiVisibilityChangeListener l) {}

    /** AOSP View.setLayerType/getLayerType (View.java:23494/23620). The layer
     *  type is a rendering HINT -- our Canvas composites the same either way --
     *  but the getter must report what was set and the range check is part of
     *  the documented contract. Previously the setter discarded its arguments
     *  and the getter always answered LAYER_TYPE_NONE, so library code that
     *  toggles LAYER_TYPE_HARDWARE around an animation and restores the
     *  previous value read an impossible state. */
    private int mLayerType = LAYER_TYPE_NONE;
    private android.graphics.Paint mLayerPaint;
    public void setLayerType(int layerType, android.graphics.Paint paint) {
        if (layerType < LAYER_TYPE_NONE || layerType > LAYER_TYPE_HARDWARE) {
            throw new IllegalArgumentException("Layer type can only be one of: LAYER_TYPE_NONE, "
                + "LAYER_TYPE_SOFTWARE or LAYER_TYPE_HARDWARE");
        }
        if (mLayerType == layerType) { setLayerPaint(paint); return; }
        mLayerType = layerType;
        mLayerPaint = (mLayerType == LAYER_TYPE_NONE) ? null : paint;
        invalidate();
    }
    public int  getLayerType() { return mLayerType; }
    /** AOSP View.setLayerPaint/getLayerPaint. */
    public void setLayerPaint(android.graphics.Paint paint) {
        if (mLayerType != LAYER_TYPE_NONE) { mLayerPaint = paint; invalidate(); }
    }
    public android.graphics.Paint getLayerPaint() { return mLayerPaint; }

    // AOSP: ONE ViewTreeObserver per view tree, owned by the root, returned by
    // every view in the tree so add*Listener() and the driver's dispatch*()
    // share one list. Was a fresh-instance-per-call stub that dropped 100% of
    // registrations (AndroidComposeView interop callbacks, every AndroidX
    // OnPreDraw one-shot). v1.0.418.
    private ViewTreeObserver mTreeObserver;
    public ViewTreeObserver getViewTreeObserver() {
        View root = this;
        ViewParent p = root.parent;
        while (p instanceof View) { root = (View) p; p = root.parent; }
        if (root == this) {
            if (mTreeObserver == null) mTreeObserver = new ViewTreeObserver();
            return mTreeObserver;
        }
        if (root.mTreeObserver == null) root.mTreeObserver = new ViewTreeObserver();
        // Merge any floating observer this view accumulated while detached into
        // the tree's observer now that the root is reachable (AOSP merge at attach).
        if (mTreeObserver != null && mTreeObserver != root.mTreeObserver) {
            root.mTreeObserver.merge(mTreeObserver);
        }
        mTreeObserver = root.mTreeObserver;
        return root.mTreeObserver;
    }
    /** The root view's own observer if one exists (no allocation), for the
     *  driver to dispatch global-layout / pre-draw / draw at the traversal points. */
    public ViewTreeObserver macapkPeekTreeObserver() { return mTreeObserver; }
    public android.view.Window getWindow() { return null; }

    /** Assigns/returns the underlying native view pointer (set by subclasses). */
    public long getNativeHandle()           { return nativeHandle; }
    protected void setNativeHandle(long h)  { this.nativeHandle = h; }

    // ---- Listener interfaces ----
    public interface OnTouchListener        { boolean onTouch(View v, MotionEvent ev); }
    public interface OnClickListener        { void onClick(View v); }
    public interface OnLongClickListener {
        boolean onLongClick(View v);
        /** AOSP View.OnLongClickListener.onLongClickUseDefaultHapticFeedback(View)
         *  (API 33): may the framework play its own LONG_PRESS haptic before
         *  calling onLongClick? AOSP's default body is `return true`. */
        default boolean onLongClickUseDefaultHapticFeedback(View v) { return true; }
    }
    public interface OnFocusChangeListener  { void onFocusChange(View v, boolean hasFocus); }
    public interface OnKeyListener          { boolean onKey(View v, int keyCode, KeyEvent ev); }
    /** AOSP signature: onDrag(View, DragEvent). This declared onDrag(View,
     *  Object) until 2026-09-29, so an app's real onDrag(View, DragEvent) never
     *  implemented it and could not have been called through it. */
    public interface OnDragListener         { boolean onDrag(View v, DragEvent event); }
    public interface OnHoverListener        { boolean onHover(View v, MotionEvent ev); }
    public interface OnScrollChangeListener { void onScrollChange(View v, int x, int y, int ox, int oy); }
    public interface OnUnhandledKeyEventListener { boolean onUnhandledKeyEvent(View v, KeyEvent ev); }
    public interface OnContextClickListener { boolean onContextClick(View v); }
    // OnApplyWindowInsetsListener defined earlier at line ~990.
    // android.view.View$OnGenericMotionListener -- callback for
    // generic input events (mouse wheel, gamepad axis, joystick).
    // ShatteredPD's input glue references this interface during init;
    // absent, the loader hits NoClassDefFoundError before any input
    // handler is installed and the engine bails. Mac.apk doesn't
    // currently route gamepad/wheel events here -- callers register
    // listeners that never fire -- but the interface MUST exist or
    // class loading fails. Per AOSP the method signature is
    // boolean onGenericMotion(View, MotionEvent).
    public interface OnGenericMotionListener {
        boolean onGenericMotion(View v, MotionEvent event);
    }
    public interface OnAttachStateChangeListener {
        void onViewAttachedToWindow(View v);
        void onViewDetachedFromWindow(View v);
    }
    public interface OnCreateContextMenuListener {
        void onCreateContextMenu(android.view.ContextMenu menu, View v,
                                  android.view.ContextMenu.ContextMenuInfo info);
    }
    public interface OnSystemUiVisibilityChangeListener {
        void onSystemUiVisibilityChange(int visibility);
    }
    /** AOSP declares `public static class BaseSavedState extends AbsSavedState`
     *  (View.java:31881). The superclass is what makes the state CHAIN work:
     *  every widget's SavedState calls `super(superState)` and later reads
     *  getSuperState() to hand the enclosing class's state back to
     *  super.onRestoreInstanceState. Implementing Parcelable directly with a
     *  getSuperState() hardcoded to null broke that chain at the first link --
     *  SwitchCompat/TabLayout/SeekBar and every custom View using the standard
     *  pattern silently dropped all state above their own layer. Extending
     *  AbsSavedState also makes View.BaseSavedState.EMPTY_STATE resolve, which
     *  is the marker AOSP's own onSaveInstanceState returns. */
    public static class BaseSavedState extends AbsSavedState {
        public BaseSavedState(android.os.Parcelable superState) { super(superState); }
        public BaseSavedState(android.os.Parcel source) { super(source); }
        public BaseSavedState(android.os.Parcel source, ClassLoader loader) { super(source, loader); }
        public static final android.os.Parcelable.Creator<BaseSavedState> CREATOR =
            new android.os.Parcelable.Creator<BaseSavedState>() {
                @Override public BaseSavedState createFromParcel(android.os.Parcel p) { return new BaseSavedState(p); }
                @Override public BaseSavedState[] newArray(int size) { return new BaseSavedState[size]; }
            };
    }

    // -------- AOSP-audit batch (v1.0.116) — minimal safe additions --------
    // The broader View audit batch caused layout regressions in
    // F-Droid (cards collapsing to icon-only height). Reduced to
    // pure-leaf no-call-out additions until bisected.

    public static int generateViewId() {
        // aapt-allocated IDs start at 0x7f...; static IDs in
        // [0x00000001, 0x00ffffff] are reserved.
        for (;;) {
            final int result = sNextGeneratedId.get();
            int newValue = result + 1;
            if (newValue > 0x00ffffff) newValue = 1;
            if (sNextGeneratedId.compareAndSet(result, newValue)) return result;
        }
    }
    private static final java.util.concurrent.atomic.AtomicInteger sNextGeneratedId =
        new java.util.concurrent.atomic.AtomicInteger(1);

    /** AOSP `View.getDefaultSize` (android-11.0.0_r48 View.java:25656-25670).
     *  Written as AOSP's switch rather than the equivalent ternary because the
     *  two differ on the FOURTH mode: AOSP's switch has no default arm, so a spec
     *  whose mode bits are 0xc0000000 (a value makeMeasureSpec never emits, but
     *  which LinearLayout's negative-excess arithmetic can produce and which the
     *  fwprobe LAY.ms.decode.c000012c case asks about) keeps the initial
     *  `result = size`. The ternary returned specSize for it. */
    public static int getDefaultSize(int size, int measureSpec) {
        int result = size;
        int specMode = MeasureSpec.getMode(measureSpec);
        int specSize = MeasureSpec.getSize(measureSpec);
        switch (specMode) {
            case MeasureSpec.UNSPECIFIED:
                result = size;
                break;
            case MeasureSpec.AT_MOST:
            case MeasureSpec.EXACTLY:
                result = specSize;
                break;
            default:
                break;
        }
        return result;
    }
    public static int resolveSize(int size, int measureSpec) {
        return resolveSizeAndState(size, measureSpec, 0) & 0x00ffffff;
    }

    public static final int MEASURED_STATE_TOO_SMALL = 0x01000000;
    public static final int MEASURED_HEIGHT_STATE_SHIFT = 16;
    public static final int MEASURED_SIZE_MASK = 0x00ffffff;
    public static final int MEASURED_STATE_MASK = 0xff000000;
    public final int getMeasuredWidthAndState() { return measuredWidth; }
    public final int getMeasuredHeightAndState() { return measuredHeight; }

    public CharSequence getAccessibilityClassName() { return getClass().getName(); }
    // isHovered/setHovered live with the M23 drawable-state fields above.
    public boolean isAccessibilityFocused() { return false; }
    /** AOSP (android14 View.java :15550): `mAttachInfo != null ?
     *  mAttachInfo.mKeyDispatchState : null` -- ONE state per window, shared by
     *  every view in it. Ours keys it on the window's root view (the activity
     *  decor, a Dialog decor, a PopupWindow decor). v1.0.1642: returned null,
     *  which made PopupWindow's decor and Compose's PopupLayout skip their BACK
     *  handling entirely (both return super.dispatchKeyEvent when this is
     *  null), so BACK never closed a popup. */
    public KeyEvent.DispatcherState getKeyDispatcherState() {
        View root = getRootView();
        if (root == null) root = this;
        synchronized (sKeyDispatchStates) {
            KeyEvent.DispatcherState st = sKeyDispatchStates.get(root);
            if (st == null) {
                st = new KeyEvent.DispatcherState();
                sKeyDispatchStates.put(root, st);
            }
            return st;
        }
    }
    private static final java.util.Map<View, KeyEvent.DispatcherState> sKeyDispatchStates =
        new java.util.WeakHashMap<>();
    public OnFocusChangeListener getOnFocusChangeListener() { return onFocusChangeListener; }
    public ViewParent getParentForAccessibility() { return parent; }
    /** AOSP: re-query the outline provider + redraw. Our draw loop
     *  re-runs getOutline every frame, so this just requests a redraw
     *  (lets the keypad roundPercent ViewTransition re-clip as the
     *  animated radius changes). */
    public void invalidateOutline() { invalidate(); }
    public void cancelPendingInputEvents() {}
    public boolean performHapticFeedback(int feedbackConstant) { return false; }
    public boolean performHapticFeedback(int feedbackConstant, int flags) { return false; }
    public void playSoundEffect(int soundConstant) {}
    public boolean requestRectangleOnScreen(Rect rectangle) { return false; }
    public boolean requestRectangleOnScreen(Rect rectangle, boolean immediate) { return false; }
    public void resetPivot() {}
    // AOSP View.getAutofillId() returns android.view.autofill.AutofillId (NOT
    // Object). Jetpack Compose's AndroidComposeView ctor (AndroidAutofill)
    // calls this and binds the result; an Object return type or null makes
    // Compose's setContent NoSuchMethodError / NPE. Lazily assign a stable id.
    private android.view.autofill.AutofillId mAutofillId;
    public android.view.autofill.AutofillId getAutofillId() {
        if (mAutofillId == null) {
            int id = (this.id != NO_ID) ? this.id : generateViewId();
            mAutofillId = new android.view.autofill.AutofillId(id);
        }
        return mAutofillId;
    }
    public void setAutofillId(android.view.autofill.AutofillId id) { mAutofillId = id; }
    public void dispatchProvideAutofillStructure(Object structure, int flags) {}
    public void onProvideAutofillStructure(Object structure, int flags) {}
    public void onProvideAutofillVirtualStructure(Object structure, int flags) {}
    public long getUniqueDrawingId() { return System.identityHashCode(this); }
    public android.window.OnBackInvokedDispatcher findOnBackInvokedDispatcher() { return null; }
    /** AOSP View.setRenderEffect (View.java:23533) takes
     *  android.graphics.RenderEffect and forwards it to the View's owned
     *  RenderNode. The Object-typed parameter here was a SIGNATURE_MISMATCH: an
     *  app's `view.setRenderEffect(RenderEffect.createBlurEffect(...))` carries
     *  the RenderEffect descriptor in its invokevirtual and would not resolve
     *  against an (Object)V method.
     *
     *  Mac.apk's View has no owned RenderNode (transform/elevation are plain
     *  fields), so the effect is stored the same way android.graphics.RenderNode
     *  stores it; the compositing half -- rendering this view's output through
     *  the ConvolveOp blur that Canvas.drawRenderNode already applies to a
     *  recorded layer -- is not wired here yet, so a View-level blur is stored
     *  and readable but not yet visible. */
    private android.graphics.RenderEffect mRenderEffect;
    public void setRenderEffect(android.graphics.RenderEffect renderEffect) {
        if (mRenderEffect == renderEffect) return;
        mRenderEffect = renderEffect;
        invalidate();
    }
    /** The RenderEffect set on this view, for the compositing pass to read.
     *  AOSP has no getter (it lives on the RenderNode); named macapk* so it is
     *  clearly not part of the Android API surface. */
    public android.graphics.RenderEffect macapkRenderEffect() { return mRenderEffect; }
    public void setFrameContentVelocity(float velocity) {}
    // Drawable.Callback (AOSP View): a drawable hosted by this View calls these
    // when it needs a redraw or a scheduled self-animation tick. AOSP's View
    // invalidateDrawable -> invalidate() (so the next frame redraws the drawable's
    // new content -- the heartbeat of Lottie/AnimationDrawable/AVD animations);
    // scheduleDrawable -> postDelayed(what, when - now); unscheduleDrawable ->
    // removeCallbacks(what). Wiring these (not no-ops) is what makes a Lottie/
    // animated drawable actually advance + repaint.
    @Override public void invalidateDrawable(android.graphics.drawable.Drawable d) {
        // Only repaint for a drawable we're actually showing; cheap + matches AOSP
        // (it verifies who == mBackground/the foreground). We don't track which
        // drawable, so invalidate unconditionally -- the per-frame draw redraws it.
        //
        // MUST NOT call the VIRTUAL no-arg invalidate(): AOSP's
        // invalidateDrawable calls the RECT overload (android13-release
        // View.java:23935-23945, "invalidate(dirty.left + scrollX, ...)"),
        // never the no-arg virtual -- and subclasses rely on that. Material's
        // BaseProgressIndicator overrides invalidate() to ALSO call
        // getCurrentDrawable().invalidateSelf(); invalidateSelf routes back
        // here via Drawable.Callback, so dispatching virtually recursed
        // invalidate -> invalidateSelf -> invalidateDrawable -> invalidate
        // until StackOverflowError inside the widget's constructor
        // (measured: AntennaPod LinearProgressIndicator x50, campaign log
        // 2026-08-01 -- every instance became a FrameLayout placeholder).
        macapkInvalidateNonVirtual();
    }
    /** The non-virtual core of invalidate(): schedule a redraw pass. Both
     *  invalidate() and invalidateDrawable() land here; only invalidate()
     *  is subclass-overridable, matching AOSP's dispatch shape. */
    private void macapkInvalidateNonVirtual() {
        if (android.os.Looper.MACAPK_REAL_LOOPER) ViewRootImpl.schedule();
    }
    // Pending drawable animation ticks, keyed by the DRAWABLE that scheduled
    // them. AOSP gets this for free: it posts to the Choreographer with `who`
    // as the callback TOKEN, so `removeCallbacks(CALLBACK_ANIMATION, null, who)`
    // cancels every runnable that drawable has outstanding
    // (View.java:23926-23931). We have no Choreographer token channel, so the
    // association is kept here. IdentityHashMap because Drawable equality is
    // not identity for shared constant states.
    private java.util.IdentityHashMap<android.graphics.drawable.Drawable,
                                      java.util.List<Runnable>> mScheduledByDrawable;

    @Override public void scheduleDrawable(android.graphics.drawable.Drawable who, Runnable what, long when) {
        if (what == null) return;
        long delay = when - android.os.SystemClock.uptimeMillis();
        if (who != null) {
            synchronized (this) {
                if (mScheduledByDrawable == null) mScheduledByDrawable = new java.util.IdentityHashMap<>();
                mScheduledByDrawable.computeIfAbsent(who, k -> new java.util.ArrayList<>()).add(what);
            }
        }
        postDelayed(what, delay > 0 ? delay : 0);
    }
    @Override public void unscheduleDrawable(android.graphics.drawable.Drawable who, Runnable what) {
        if (what != null) removeCallbacks(what);
    }
    /** AOSP's non-Callback overload: cancel EVERYTHING `who` has outstanding.
     *
     *  ⚠ v1.0.936. This was an empty stub, which is why setBackgroundDrawable's
     *  AOSP-mandated `unscheduleDrawable(mBackground)` had nothing to call --
     *  a replaced AnimationDrawable kept ticking against the view that had
     *  already dropped it. Making it real needed two things that only landed
     *  together: a STABLE Handler (so a cancel can reach what a post queued)
     *  and the who -> runnables association above. */
    public void unscheduleDrawable(android.graphics.drawable.Drawable who) {
        if (who == null) return;
        java.util.List<Runnable> pending;
        synchronized (this) {
            pending = (mScheduledByDrawable == null) ? null : mScheduledByDrawable.remove(who);
        }
        if (pending == null) return;
        android.os.Handler h = macapkPostHandler();
        for (Runnable r : pending) {
            try { h.removeCallbacks(r); } catch (Throwable ignored) {}
        }
    }
    /** Drop a runnable from the per-drawable bookkeeping once it is cancelled,
     *  so the map cannot grow without bound on a long-lived animated view. */
    private void macapkForgetScheduled(Runnable r) {
        if (r == null) return;
        synchronized (this) {
            if (mScheduledByDrawable == null) return;
            java.util.Iterator<java.util.Map.Entry<android.graphics.drawable.Drawable,
                    java.util.List<Runnable>>> it = mScheduledByDrawable.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<android.graphics.drawable.Drawable,
                        java.util.List<Runnable>> e = it.next();
                e.getValue().remove(r);
                if (e.getValue().isEmpty()) it.remove();
            }
        }
    }

    // v1.0.435: AOSP View animation Property constants. These were MISSING, which
    // is the ROOT CAUSE of Reddit's blank welcome screen: bluelinelabs Conductor's
    // AnimatorChangeHandler (FadeChangeHandler / HorizontalChangeHandler / etc.)
    // builds ObjectAnimator.ofFloat(view, View.ALPHA, ...) -- referencing the
    // static field View.ALPHA. With the field absent the JVM throws
    // NoSuchFieldError inside getAnimator(), which runs in the change's
    // OnPreDrawListener (swallowed by ViewCanvasDriver's pre-draw try/catch), so
    // the animated change STARTS but its animator is never created -> never
    // COMPLETES -> Reddit's navstack<->Conductor bridge (which commits the entry +
    // sets host.e()=true on onChangeCompleted) never fires -> the navstack model
    // stays EMPTY + inactive -> m.g iterates zero entries -> blank welcome.
    // Verified via debug/composetest MACAPK_CT_MODE=conductor: a FadeChangeHandler
    // push fired CHANGE STARTED but never CHANGE COMPLETED. Mirrors frameworks/base
    // View.ALPHA/TRANSLATION_*/SCALE_*/ROTATION*/X/Y/Z (AOSP defines them via
    // FloatProperty; a plain Property<View,Float> is behaviorally identical for
    // ObjectAnimator.ofFloat). Each delegates to the existing transform getter/setter.
    public static final android.util.Property<View, Float> ALPHA =
        new android.util.Property<View, Float>(Float.class, "alpha") {
            @Override public Float get(View v) { return v.getAlpha(); }
            @Override public void set(View v, Float val) { v.setAlpha(val); }
        };
    public static final android.util.Property<View, Float> TRANSLATION_X =
        new android.util.Property<View, Float>(Float.class, "translationX") {
            @Override public Float get(View v) { return v.getTranslationX(); }
            @Override public void set(View v, Float val) { v.setTranslationX(val); }
        };
    public static final android.util.Property<View, Float> TRANSLATION_Y =
        new android.util.Property<View, Float>(Float.class, "translationY") {
            @Override public Float get(View v) { return v.getTranslationY(); }
            @Override public void set(View v, Float val) { v.setTranslationY(val); }
        };
    public static final android.util.Property<View, Float> TRANSLATION_Z =
        new android.util.Property<View, Float>(Float.class, "translationZ") {
            @Override public Float get(View v) { return v.getTranslationZ(); }
            @Override public void set(View v, Float val) { v.setTranslationZ(val); }
        };
    public static final android.util.Property<View, Float> SCALE_X =
        new android.util.Property<View, Float>(Float.class, "scaleX") {
            @Override public Float get(View v) { return v.getScaleX(); }
            @Override public void set(View v, Float val) { v.setScaleX(val); }
        };
    public static final android.util.Property<View, Float> SCALE_Y =
        new android.util.Property<View, Float>(Float.class, "scaleY") {
            @Override public Float get(View v) { return v.getScaleY(); }
            @Override public void set(View v, Float val) { v.setScaleY(val); }
        };
    public static final android.util.Property<View, Float> ROTATION =
        new android.util.Property<View, Float>(Float.class, "rotation") {
            @Override public Float get(View v) { return v.getRotation(); }
            @Override public void set(View v, Float val) { v.setRotation(val); }
        };
    public static final android.util.Property<View, Float> ROTATION_X =
        new android.util.Property<View, Float>(Float.class, "rotationX") {
            @Override public Float get(View v) { return v.getRotationX(); }
            @Override public void set(View v, Float val) { v.setRotationX(val); }
        };
    public static final android.util.Property<View, Float> ROTATION_Y =
        new android.util.Property<View, Float>(Float.class, "rotationY") {
            @Override public Float get(View v) { return v.getRotationY(); }
            @Override public void set(View v, Float val) { v.setRotationY(val); }
        };
    public static final android.util.Property<View, Float> X =
        new android.util.Property<View, Float>(Float.class, "x") {
            @Override public Float get(View v) { return v.getX(); }
            @Override public void set(View v, Float val) { v.setX(val); }
        };
    public static final android.util.Property<View, Float> Y =
        new android.util.Property<View, Float>(Float.class, "y") {
            @Override public Float get(View v) { return v.getY(); }
            @Override public void set(View v, Float val) { v.setY(val); }
        };
    public static final android.util.Property<View, Float> Z =
        new android.util.Property<View, Float>(Float.class, "z") {
            @Override public Float get(View v) { return v.getZ(); }
            @Override public void set(View v, Float val) { v.setZ(val); }
        };

    // ================= AOSP-conformance batch =================

    /** AOSP View.getWindowVisibleDisplayFrame(Rect) (View.java:17177): the
     *  VISIBLE content area of the window this view lives in -- the window
     *  bounds minus the system-bar and display-cutout insets, normalized to
     *  (0,0). Detached, AOSP falls back to the WindowManager's maximum window
     *  metrics inset the same way; Mac.apk's window IS the display, so the
     *  display metrics are the equivalent bounds.
     *
     *  This is the method behind the classic soft-keyboard probe
     *  (`getWindowVisibleDisplayFrame(r); rootView.getHeight() - r.height()`),
     *  so it has to report the same thing our insets pipeline reports rather
     *  than a guess: with no IME overlay resizing the window, the difference is
     *  zero, which is the truthful "no keyboard is covering the window". */
    public void getWindowVisibleDisplayFrame(Rect outRect) {
        if (outRect == null) return;
        int w = 0, h = 0;
        try {
            // ⚠ CO-REQUISITE of the getRootWindowInsets change above -- these two
            // MUST move together, and the arithmetic here was MEASURED, not
            // predicted. On this host the three candidate bases DISAGREE:
            //
            //   Display.macapkRealHeightPx()          1860   full display
            //   Resources.DisplayMetrics.heightPixels 1764   = 1860 - navDecor(96)
            //   the actual window / surface           1796   = 1860 - 64 of macOS chrome
            //   the visible content (rootView)        1652   = 1796 - status(48) - nav(96)
            //
            // So DisplayMetrics and the window are derived from INCONSISTENT
            // bases (one subtracts the nav strip, the other the menu bar), and
            // no subtraction applied to either lands on the truthful 1652.
            // Seeding from DisplayMetrics and subtracting the now-truthful root
            // insets gave 1716 (probe -64); seeding from the real display gave
            // the same, for a different reason.
            //
            // AOSP's meaning is "the display area not covered by system
            // windows", which for a normal window IS the content area -- and
            // rootView already spans exactly that (laid out statusBarH ..
            // contentBottom). So read it off the root view and do NOT subtract
            // insets again; that is both truthful and self-consistent under
            // fullscreen, where the root legitimately spans the whole window.
            // The metrics path stays only as the fallback for a detached view,
            // where it keeps its historical inset subtraction below.
            View vfRoot = getRootView();
            if (vfRoot != null && vfRoot.getHeight() > 0 && vfRoot.getWidth() > 0) {
                outRect.set(0, 0, vfRoot.getWidth(), vfRoot.getHeight());
                // (No probe log here: measured against outRect it would be 0 by
                // construction and read as a passing check. The real probe is
                // ViewCanvasDriver's INSETS SELF-CHECK, which compares this
                // against the independently-computed root layout.)
                return;
            }
            android.content.res.Resources res = getResources();
            android.util.DisplayMetrics dm = (res != null) ? res.getDisplayMetrics() : null;
            if (dm != null) { w = dm.widthPixels; h = dm.heightPixels; }
        } catch (Throwable ignored) {}
        if (w <= 0 || h <= 0) {
            // No metrics reachable: fall back to the root of this view's tree.
            View root = getRootView();
            if (root != null) { w = root.getWidth(); h = root.getHeight(); }
        }
        outRect.set(0, 0, w, h);
        try {
            android.view.WindowInsets wi = getRootWindowInsets();
            if (wi != null) {
                android.graphics.Insets in = wi.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                if (in != null) {
                    outRect.left   += in.left;
                    outRect.top    += in.top;
                    outRect.right  -= in.right;
                    outRect.bottom -= in.bottom;
                }
            }
        } catch (Throwable ignored) {}
        outRect.offsetTo(0, 0);
        if (macapkTraceInsets() && !sLoggedVisFrame) {
            sLoggedVisFrame = true;
            try {
                View root = getRootView();
                int rh = (root != null) ? root.getHeight() : -1;
                com.macapk.loader.NativeLog.i("Mac.apk.View",
                    "visibleDisplayFrame h=" + outRect.height()
                    + " rootView.h=" + rh
                    + " keyboardProbe=" + (rh - outRect.height())
                    + "  (0 == no keyboard; +96 would be the double-count)");
            } catch (Throwable ignored) {}
        }
    }
    private static boolean sLoggedVisFrame;

    /** AOSP View.computeScroll() (View.java:21594): the per-traversal hook a
     *  view driving a Scroller/OverScroller fling uses to advance mScrollX/
     *  mScrollY and ask for another frame. The base body is empty; ScrollView /
     *  NestedScrollView / RecyclerView override it. Declaring it here is what
     *  lets those overrides resolve at all. */
    public void computeScroll() {}

    // ---- Temporary detach (AOSP View.java:16424-16473) ----
    // A recycling container detaches a child from the tree without the full
    // onDetachedFromWindow teardown; the view is told so it can pause per-view
    // work, and told again when it comes back.
    private boolean mTemporaryDetach;
    public final boolean isTemporarilyDetached() { return mTemporaryDetach; }
    public void dispatchStartTemporaryDetach() {
        mTemporaryDetach = true;
        onStartTemporaryDetach();
    }
    /** AOSP cancels the pending press and arms CANCEL_NEXT_UP_EVENT so the
     *  matching ACTION_UP cannot complete a click on a view that left the tree
     *  mid-gesture. Clearing `pressed` is the same observable effect here,
     *  because onTouchEvent gates performClick on it. */
    public void onStartTemporaryDetach() {
        if (isPressed()) setPressed(false);
    }
    public void dispatchFinishTemporaryDetach() {
        mTemporaryDetach = false;
        onFinishTemporaryDetach();
    }
    public void onFinishTemporaryDetach() {}

    /** AOSP View.dispatchConfigurationChanged/onConfigurationChanged
     *  (View.java:17221/17235): lets a view react to an orientation / locale /
     *  density change without the activity being recreated (the
     *  android:configChanges path). ViewGroup overrides the dispatch half to
     *  recurse into children. */
    public void dispatchConfigurationChanged(android.content.res.Configuration newConfig) {
        onConfigurationChanged(newConfig);
    }
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {}

    // ---- Drag and drop (AOSP View.java:29145-29570) ----

    /** AOSP `View.DragShadowBuilder` (View.java:29145): a plain builder holding
     *  a weak reference to the source view plus the two hooks the system calls
     *  to size and paint the drag shadow. App code and Compose's drag-and-drop
     *  interop construct and subclass it, so the TYPE has to exist or the
     *  enclosing method fails verification with NoClassDefFoundError. */
    public static class DragShadowBuilder {
        private final java.lang.ref.WeakReference<View> mView;
        public DragShadowBuilder(View view) { mView = new java.lang.ref.WeakReference<>(view); }
        public DragShadowBuilder() { mView = new java.lang.ref.WeakReference<>(null); }
        public final View getView() { return mView.get(); }
        /** AOSP default: the shadow is the size of the view, touch point centered. */
        public void onProvideShadowMetrics(android.graphics.Point outShadowSize,
                                            android.graphics.Point outShadowTouchPoint) {
            final View view = mView.get();
            if (view == null || outShadowSize == null || outShadowTouchPoint == null) return;
            outShadowSize.set(view.getWidth(), view.getHeight());
            outShadowTouchPoint.set(outShadowSize.x / 2, outShadowSize.y / 2);
        }
        /** AOSP default: the shadow is the view drawn into the supplied canvas. */
        public void onDrawShadow(android.graphics.Canvas canvas) {
            final View view = mView.get();
            if (view != null && canvas != null) view.draw(canvas);
        }
    }

    /** AOSP View.startDragAndDrop (android14 View.java:27994). Starts an
     *  IN-WINDOW drag session (MacApkDragSession: STARTED, ENTERED/EXITED,
     *  LOCATION, DROP, ENDED through the ViewGroup/View dispatchDragEvent
     *  port, on the UI thread). Returns AOSP's own "the drag did not start"
     *  false when the view is detached, no pointer is down (the touch focus
     *  cannot transfer), another drag is in progress, or (a listed divergence)
     *  the view is in a floating window. The drag never leaves the app's
     *  window: there is no host / Finder drag (DRAG_FLAG_GLOBAL acts local). */
    public final boolean startDragAndDrop(android.content.ClipData data,
            DragShadowBuilder shadowBuilder, Object myLocalState, int flags) {
        // MACAPK_DRAG_AND_DROP (default ON): an in-window drag session, see
        // MacApkDragSession. =0 restores "the drag did not start" (false).
        boolean r = MacApkDragSession.ENABLED
                && MacApkDragSession.start(this, data, shadowBuilder, myLocalState, flags);
        // HARNESS runs only (ViewTextDump.ENABLED = MACAPK_DUMP_TREE_EVERY set): say the
        // app ASKED and what it got, so drive_app's dragprobe can tell "our layer gave a
        // drag the app asked for a silent false" (the pre-port stub, or the =0 arm) from
        // "the app never asked" (Anuto with 0 credits). Normal launches log nothing.
        if (com.macapk.loader.ViewTextDump.ENABLED) {
            com.macapk.loader.NativeLog.i("Mac.apk.Drag", "startDragAndDrop -> " + r + " from "
                    + getClass().getName());
        }
        return r;
    }
    /** Pre-API-24 name for the same call (AOSP delegates one to the other). */
    public final boolean startDrag(android.content.ClipData data,
            DragShadowBuilder shadowBuilder, Object myLocalState, int flags) {
        return startDragAndDrop(data, shadowBuilder, myLocalState, flags);
    }
    /** AOSP View.updateDragShadow (android14 :28191): with an active drag,
     *  clears the drag surface and has the builder redraw it; without one AOSP
     *  logs "No active drag" and does nothing (so do we). */
    public final void updateDragShadow(DragShadowBuilder shadowBuilder) {
        if (!MacApkDragSession.ENABLED) return;
        if (!MacApkDragSession.updateShadow(this, shadowBuilder)) {
            com.macapk.loader.NativeLog.e("View", "No active drag");
        }
    }
    /** AOSP View.cancelDragAndDrop (android14 :28165): cancels the in-flight
     *  drag; every view that got ACTION_DRAG_STARTED receives ACTION_DRAG_ENDED
     *  (result false). Without an active drag AOSP logs "No active drag to
     *  cancel" and does nothing (so do we). */
    public final void cancelDragAndDrop() {
        if (!MacApkDragSession.ENABLED) return;
        if (!MacApkDragSession.cancel(this)) {
            com.macapk.loader.NativeLog.e("View", "No active drag to cancel");
        }
    }

    // ---- Drawable state sets (AOSP View.java:2146-2410) ----
    // The protected int[] constants subclasses pass to mergeDrawableStates()
    // from their onCreateDrawableState() override. AOSP builds them from
    // StateSet.get(bitmask) over a table sorted by attr id; we spell them out
    // from the same real framework attr ids getDrawableState() already emits,
    // in the same ascending-by-id order.
    protected static final int[] EMPTY_STATE_SET = {};
    protected static final int[] WINDOW_FOCUSED_STATE_SET = { STATE_WINDOW_FOCUSED };
    protected static final int[] SELECTED_STATE_SET = { STATE_SELECTED };
    protected static final int[] SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_WINDOW_FOCUSED, STATE_SELECTED };
    protected static final int[] FOCUSED_STATE_SET = { STATE_FOCUSED };
    protected static final int[] FOCUSED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED };
    protected static final int[] FOCUSED_SELECTED_STATE_SET =
        { STATE_FOCUSED, STATE_SELECTED };
    protected static final int[] FOCUSED_SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED, STATE_SELECTED };
    protected static final int[] ENABLED_STATE_SET = { STATE_ENABLED };
    protected static final int[] ENABLED_WINDOW_FOCUSED_STATE_SET =
        { STATE_WINDOW_FOCUSED, STATE_ENABLED };
    protected static final int[] ENABLED_SELECTED_STATE_SET =
        { STATE_ENABLED, STATE_SELECTED };
    protected static final int[] ENABLED_SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_WINDOW_FOCUSED, STATE_ENABLED, STATE_SELECTED };
    protected static final int[] ENABLED_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_ENABLED };
    protected static final int[] ENABLED_FOCUSED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED, STATE_ENABLED };
    protected static final int[] ENABLED_FOCUSED_SELECTED_STATE_SET =
        { STATE_FOCUSED, STATE_ENABLED, STATE_SELECTED };
    protected static final int[] ENABLED_FOCUSED_SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED, STATE_ENABLED, STATE_SELECTED };
    protected static final int[] PRESSED_STATE_SET = { STATE_PRESSED };
    protected static final int[] PRESSED_WINDOW_FOCUSED_STATE_SET =
        { STATE_WINDOW_FOCUSED, STATE_PRESSED };
    protected static final int[] PRESSED_SELECTED_STATE_SET =
        { STATE_SELECTED, STATE_PRESSED };
    protected static final int[] PRESSED_SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_WINDOW_FOCUSED, STATE_SELECTED, STATE_PRESSED };
    protected static final int[] PRESSED_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_PRESSED };
    protected static final int[] PRESSED_FOCUSED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED, STATE_PRESSED };
    protected static final int[] PRESSED_FOCUSED_SELECTED_STATE_SET =
        { STATE_FOCUSED, STATE_SELECTED, STATE_PRESSED };
    protected static final int[] PRESSED_FOCUSED_SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED, STATE_SELECTED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_STATE_SET =
        { STATE_ENABLED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_WINDOW_FOCUSED_STATE_SET =
        { STATE_WINDOW_FOCUSED, STATE_ENABLED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_SELECTED_STATE_SET =
        { STATE_ENABLED, STATE_SELECTED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_WINDOW_FOCUSED, STATE_ENABLED, STATE_SELECTED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_ENABLED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_FOCUSED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED, STATE_ENABLED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_FOCUSED_SELECTED_STATE_SET =
        { STATE_FOCUSED, STATE_ENABLED, STATE_SELECTED, STATE_PRESSED };
    protected static final int[] PRESSED_ENABLED_FOCUSED_SELECTED_WINDOW_FOCUSED_STATE_SET =
        { STATE_FOCUSED, STATE_WINDOW_FOCUSED, STATE_ENABLED, STATE_SELECTED, STATE_PRESSED };

    // ---- Per-view frame-rate vote (AOSP View.java:5835-5843, 34374-34408) ----
    // A View may express a PREFERRED frame rate; AOSP aggregates the votes at
    // the ViewRootImpl/Surface level. The contract is explicitly a preference,
    // not an obligation, and the value does not propagate to children -- so
    // store-and-report is the complete View-level contract.
    public static final float REQUESTED_FRAME_RATE_CATEGORY_DEFAULT = Float.NaN;
    public static final float REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE = -1;
    public static final float REQUESTED_FRAME_RATE_CATEGORY_LOW = -2;
    public static final float REQUESTED_FRAME_RATE_CATEGORY_NORMAL = -3;
    public static final float REQUESTED_FRAME_RATE_CATEGORY_HIGH = -4;
    private float mPreferredFrameRate = REQUESTED_FRAME_RATE_CATEGORY_DEFAULT;
    public void setRequestedFrameRate(float frameRate) { mPreferredFrameRate = frameRate; }
    public float getRequestedFrameRate() { return mPreferredFrameRate; }

    // ---- Outline shadow colors (AOSP View.java:20403/20432, API 28+) ----
    // Material You tints the elevation shadow away from black. AOSP delegates
    // to the owned RenderNode's setSpotShadowColor/setAmbientShadowColor;
    // Mac.apk's View has no RenderNode, so the colors live here for the
    // elevation-shadow painter to read. AOSP's default is opaque black.
    private int mSpotShadowColor = 0xFF000000;
    private int mAmbientShadowColor = 0xFF000000;
    public void setOutlineSpotShadowColor(int color) {
        if (mSpotShadowColor != color) { mSpotShadowColor = color; invalidate(); }
    }
    public int getOutlineSpotShadowColor() { return mSpotShadowColor; }
    public void setOutlineAmbientShadowColor(int color) {
        if (mAmbientShadowColor != color) { mAmbientShadowColor = color; invalidate(); }
    }
    public int getOutlineAmbientShadowColor() { return mAmbientShadowColor; }

    // ==================================================================
    // v1.0.685: the long tail of View leaf methods the audit flagged (76
    // missing/mismatched, up to 26 apps each). These are getter/setter pairs,
    // registration methods and query hooks. Each STORES where AOSP stores and
    // returns AOSP's documented default where AOSP has no state, so every
    // setter round-trips and no query throws. The ones that need real
    // behaviour (scroll, focus traversal, findViewWithTag) route through the
    // existing View machinery rather than faking it.
    //
    // Source: frameworks/base/core/java/android/view/View.java
    // ==================================================================

    // ---- programmatic scroll (route through scrollTo for correct invalidate) --
    public void setScrollX(int value) { scrollTo(value, mScrollY); }
    public void setScrollY(int value) { scrollTo(mScrollX, value); }

    // ---- keyboard navigation clusters (API 26) -------------------------------
    private boolean mIsKeyboardNavigationCluster;
    private boolean mFocusedByDefault;
    private int mNextClusterForwardId = NO_ID;
    public void setKeyboardNavigationCluster(boolean isCluster) { mIsKeyboardNavigationCluster = isCluster; }
    public boolean isKeyboardNavigationCluster() { return mIsKeyboardNavigationCluster; }
    public void setNextClusterForwardId(int id) { mNextClusterForwardId = id; }
    public int getNextClusterForwardId() { return mNextClusterForwardId; }
    public void setFocusedByDefault(boolean isFocusedByDefault) { mFocusedByDefault = isFocusedByDefault; }
    public boolean isFocusedByDefault() { return mFocusedByDefault; }
    // No live focus-cluster graph; AOSP returns null/false/no-op when the
    // current view is not part of a cluster hierarchy, which is our state.
    public View keyboardNavigationClusterSearch(View currentCluster, int direction) { return null; }
    public void addKeyboardNavigationClusters(java.util.Collection<View> views, int direction) { }
    public boolean restoreDefaultFocus() { return requestFocus(); }

    /** AOSP View.findKeyboardNavigationCluster (View.java, package-private):
     *  the top-most cluster this view sits inside, found by walking parents
     *  and answering on the way BACK down, so an enclosing cluster wins over a
     *  nested one. Read by ViewGroup.shouldBlockFocusForTouchscreen. */
    View findKeyboardNavigationCluster() {
        if (parent instanceof View pv) {
            View cluster = pv.findKeyboardNavigationCluster();
            if (cluster != null) return cluster;
            else if (isKeyboardNavigationCluster()) return this;
        }
        return null;
    }

    // ---- root namespace (PFLAG_IS_ROOT_NAMESPACE) ----------------------------
    // AOSP View.setIsRootNamespace / isRootNamespace (View.java:26718-26734 on
    // android14-release) are one bit in mPrivateFlags; ours has no packed
    // private-flag word, so it is a boolean. The ONE framework setter is
    // PhoneWindow.installDecor(): `mDecor.setIsRootNamespace(true)`
    // (PhoneWindow.java:2764), and the consumer that matters is
    // ViewGroup.focusSearch(View, int), which runs FocusFinder over the WHOLE
    // window from the first root-namespace ancestor instead of climbing further.
    // Our Activity/Dialog decors set it where installDecor does. It is NOT wired
    // into findViewById's traversal (AOSP skips a root-namespace CHILD there):
    // our decor is never a child of another View, so that branch has nothing
    // to skip, and a wrong skip would hide views from every findViewById.
    private boolean mIsRootNamespace;
    public void setIsRootNamespace(boolean isRoot) { mIsRootNamespace = isRoot; }
    public boolean isRootNamespace() { return mIsRootNamespace; }

    // ---- user-specified next focus (android:nextFocusForward et al.) ---------
    /** AOSP View.findUserSetNextFocus (View.java, package-private): the view
     *  the app hand-wired as this one's successor in `direction`, or null when
     *  the id is NO_ID. FORWARD/LEFT/RIGHT/UP/DOWN read the stored id and resolve
     *  it inside-out from this view (nearest enclosing match wins, because ids
     *  need not be unique in a tree); BACKWARD has no stored id, so it finds the
     *  view whose FORWARD link resolves to this one, exactly as AOSP does. Read
     *  by FocusFinder.findNextUserSpecifiedFocus before any geometric search. */
    View findUserSetNextFocus(View root, int direction) {
        switch (direction) {
            case FOCUS_LEFT:
                if (mNextFocusLeftId == NO_ID) return null;
                return findViewInsideOutShouldExist(root, this, mNextFocusLeftId);
            case FOCUS_RIGHT:
                if (mNextFocusRightId == NO_ID) return null;
                return findViewInsideOutShouldExist(root, this, mNextFocusRightId);
            case FOCUS_UP:
                if (mNextFocusUpId == NO_ID) return null;
                return findViewInsideOutShouldExist(root, this, mNextFocusUpId);
            case FOCUS_DOWN:
                if (mNextFocusDownId == NO_ID) return null;
                return findViewInsideOutShouldExist(root, this, mNextFocusDownId);
            case FOCUS_FORWARD:
                if (mNextFocusForwardId == NO_ID) return null;
                return findViewInsideOutShouldExist(root, this, mNextFocusForwardId);
            case FOCUS_BACKWARD: {
                if (getId() == NO_ID) return null;
                final View rootView = root;
                final View startView = this;
                return root.findViewByPredicateInsideOut(startView,
                    t -> t.mNextFocusForwardId != NO_ID
                         && findViewInsideOutShouldExist(rootView, t, t.mNextFocusForwardId)
                            == startView);
            }
        }
        return null;
    }
    /** AOSP View.findViewInsideOutShouldExist: an id lookup that starts at
     *  `start` and widens outward; AOSP logs a warning when nothing matches,
     *  which is the app's own wiring error and not ours to hide. */
    private static View findViewInsideOutShouldExist(View root, View start, int id) {
        View result = root.findViewByPredicateInsideOut(start, v -> v.getId() == id);
        if (result == null) {
            android.util.Log.w("View", "couldn't find view with id " + id);
        }
        return result;
    }
    /** AOSP View.findViewByPredicate: the predicate form of findViewById. */
    public final <T extends View> T findViewByPredicate(java.util.function.Predicate<View> predicate) {
        return findViewByPredicateTraversal(predicate, null);
    }
    /** AOSP View.findViewByPredicateInsideOut: search `start`'s subtree, then
     *  its parent's (skipping the subtree already searched), and so on up to
     *  this view, so the NEAREST match to `start` wins. Returns null when the
     *  walk leaves the tree rooted at this view without a match. */
    @SuppressWarnings("unchecked")
    public final <T extends View> T findViewByPredicateInsideOut(View start,
            java.util.function.Predicate<View> predicate) {
        View childToSkip = null;
        for (;;) {
            T view = start.findViewByPredicateTraversal(predicate, childToSkip);
            if (view != null || start == this) return view;
            ViewParent p = start.getParent();
            if (!(p instanceof View pv)) return null;
            childToSkip = start;
            start = pv;
        }
    }
    /** AOSP View.findViewByPredicateTraversal: a leaf matches only itself;
     *  ViewGroup overrides to descend. */
    @SuppressWarnings("unchecked")
    protected <T extends View> T findViewByPredicateTraversal(
            java.util.function.Predicate<View> predicate, View childToSkip) {
        if (predicate.test(this)) return (T) this;
        return null;
    }
    // hasExplicitFocusable now lives beside hasFocusable(), expressed as AOSP
    // expresses it (hasFocusable(false, true)); the old open-coded copy here
    // omitted the ENABLED conjunct that the shared body carries.

    // ---- focusable / touchable collection ------------------------------------
    // AOSP's View (leaf) adds itself when it qualifies; ViewGroup overrides to
    // recurse. This is the leaf contract.
    public void addFocusables(java.util.ArrayList<View> views, int direction) {
        addFocusables(views, direction, isInTouchModeSafe()
                ? FOCUSABLES_TOUCH_MODE : FOCUSABLES_ALL);
    }
    public void addFocusables(java.util.ArrayList<View> views, int direction, int focusableMode) {
        if (views == null) return;
        if (!canTakeFocus()) return;
        // AOSP applies the touch-mode filter ONLY when actually in touch mode:
        // (focusableMode & FOCUSABLES_TOUCH_MODE) && isInTouchMode() &&
        // !isFocusableInTouchMode(). Dropping the isInTouchMode() clause (as an
        // earlier draft did) rejected every ordinary focusable by default.
        if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE
                && isInTouchModeSafe() && !isFocusableInTouchMode()) return;
        views.add(this);
    }
    /** AOSP View.canTakeFocus (android14-release View.java): VISIBLE, FOCUSABLE,
     *  ENABLED, and -- for apps targeting P or later -- laid out to a non-zero
     *  size. `isFocusable()` alone (what this file checked until v1.0.1609)
     *  would offer a GONE or disabled field as a TAB destination, which real
     *  Android never does. `!isLayoutRequested()` stands in for AOSP's
     *  `!isLayoutValid()` (a view awaiting its first layout has no size yet and
     *  must not be excluded for it). */
    private boolean canTakeFocus() {
        return getVisibility() == VISIBLE
                && isFocusable()
                && isEnabled()
                && (sCanFocusZeroSized || isLayoutRequested() || (getWidth() > 0 && getHeight() > 0));
    }
    /** AOSP View.sCanFocusZeroSized = targetSdkVersion < Build.VERSION_CODES.P,
     *  latched with the rest of the compat set in macapkInitViewCompat. Starts
     *  TRUE (the permissive pre-P answer) so an app whose targetSdk has not
     *  resolved yet is never denied a focusable for being unsized. */
    static boolean sCanFocusZeroSized = true;
    public java.util.ArrayList<View> getFocusables(int direction) {
        java.util.ArrayList<View> result = new java.util.ArrayList<>(24);
        addFocusables(result, direction);
        return result;
    }
    public void addTouchables(java.util.ArrayList<View> views) {
        if (views != null && getVisibility() == VISIBLE
                && (isClickableSafe() || isLongClickableSafe())) {
            views.add(this);
        }
    }
    /** AOSP View.getTouchables(): "Find and return all touchable views that are
     *  descendants of this view, possibly including this view if it is
     *  touchable itself" -- the exact companion to getFocusables(), and
     *  implemented the same way, by allocating the list and delegating to
     *  addTouchables (which ViewGroup already overrides to walk children).
     *
     *  addTouchables existed; this did not, so an app calling the public
     *  accessor took a NoSuchMethodError even though the machinery under it
     *  was complete. AOSP's initial capacity is 24, kept for fidelity. */
    /** AOSP View.dispatchTrackballEvent(MotionEvent) (public in current.txt;
     *  ViewGroup overrides it to route to the focused child).
     *
     *  Mac.apk has no trackball input path -- there is no macOS equivalent of
     *  that D-pad-era hardware -- so nothing ever CALLS this, and AOSP's own
     *  "not consumed" answer is false. But the METHOD has to exist: two corpus
     *  apps reference it, and a missing one is a NoSuchMethodError at their call
     *  site rather than an unhandled event.
     *
     *  AOSP's body is `return onTrackballEvent(event);`, so an app that
     *  overrides onTrackballEvent still gets its override consulted if anything
     *  ever does dispatch one. */
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return onTrackballEvent(event);
    }
    /** AOSP View.onTrackballEvent: false when unhandled. */
    public boolean onTrackballEvent(MotionEvent event) { return false; }

    /** AOSP View.onCreateContextMenu(ContextMenu) (protected): "Views should
     *  implement this method to provide their own context menu items". The base
     *  implementation notifies the registered OnCreateContextMenuListener; ours
     *  does the same so a listener an app set is actually consulted. */
    protected void onCreateContextMenu(android.view.ContextMenu menu) {
        OnCreateContextMenuListener l = mOnCreateContextMenuListener;
        if (l != null) l.onCreateContextMenu(menu, this, null);
    }

    public java.util.ArrayList<View> getTouchables() {
        java.util.ArrayList<View> result = new java.util.ArrayList<>(24);
        addTouchables(result);
        return result;
    }
    public void getFocusedRect(android.graphics.Rect r) {
        if (r != null) r.set(0, 0, getWidth(), getHeight());
    }

    public static final int FOCUSABLES_ALL = 0x00000000;
    public static final int FOCUSABLES_TOUCH_MODE = 0x00000001;

    private boolean isInTouchModeSafe() {
        try { return isInTouchMode(); } catch (Throwable t) { return true; }
    }
    private boolean isClickableSafe() {
        try { return isClickable(); } catch (Throwable t) { return false; }
    }
    private boolean isLongClickableSafe() {
        try { return isLongClickable(); } catch (Throwable t) { return false; }
    }

    // ---- unhandled key event listeners (API 28) ------------------------------
    private java.util.ArrayList<OnUnhandledKeyEventListener> mUnhandledKeyListeners;
    public void addOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {
        if (mUnhandledKeyListeners == null) mUnhandledKeyListeners = new java.util.ArrayList<>();
        if (!mUnhandledKeyListeners.contains(listener)) mUnhandledKeyListeners.add(listener);
    }
    public void removeOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {
        if (mUnhandledKeyListeners != null) mUnhandledKeyListeners.remove(listener);
    }
    // OnUnhandledKeyEventListener interface is declared with the other listener
    // interfaces earlier in this file.

    // ---- unified content receive (API 31) ------------------------------------
    private String[] mReceiveContentMimeTypes;
    private OnReceiveContentListener mReceiveContentListener;
    public void setOnReceiveContentListener(String[] mimeTypes, OnReceiveContentListener listener) {
        mReceiveContentMimeTypes = mimeTypes;
        mReceiveContentListener = listener;
    }
    public String[] getReceiveContentMimeTypes() { return mReceiveContentMimeTypes; }
    /** AOSP View.performReceiveContent (android14-release):
     *      if (listener != null) {
     *          final ContentInfo remaining = listener.onReceiveContent(this, payload);
     *          return (remaining == null) ? null : onReceiveContent(remaining);
     *      }
     *      return onReceiveContent(payload);
     *  The view's own onReceiveContent is the fallback for whatever the app's
     *  listener declines -- that is how TextView inserts pasted text. */
    public ContentInfo performReceiveContent(ContentInfo payload) {
        if (mReceiveContentListener != null && payload != null) {
            final ContentInfo remaining = mReceiveContentListener.onReceiveContent(this, payload);
            return (remaining == null) ? null : onReceiveContent(remaining);
        }
        return onReceiveContent(payload);
    }

    // ---- window-insets animation (API 30) ------------------------------------
    private android.view.WindowInsetsAnimation.Callback mWindowInsetsAnimationCallback;
    public void setWindowInsetsAnimationCallback(android.view.WindowInsetsAnimation.Callback callback) {
        mWindowInsetsAnimationCallback = callback;
    }
    public boolean hasWindowInsetsAnimationCallback() { return mWindowInsetsAnimationCallback != null; }
    /** AOSP View.computeSystemWindowInsets (core/java/android/view/View.java):
     *  for a view without OPTIONAL_FITS_SYSTEM_WINDOWS the else-branch CONSUMES --
     *      outLocalInsets.set(in.getSystemWindowInsetsAsRect());
     *      return in.consumeSystemWindowInsets().inset(outLocalInsets);
     *
     *  ⚠ THIS WAS THE APP BAR BUG, and the old comment (kept below) states the
     *  premise that made it wrong: "AOSP with no insets returns the input
     *  unchanged" is true for a ZERO input and FALSE for a non-zero one. We
     *  zeroed outLocalInsets UNCONDITIONALLY.
     *
     *  Why that killed the action bar. androidx's ActionBarOverlayLayout.onMeasure
     *  adds the bar height to mContentInsets only when
     *  `!mOverlayMode && !stable && mDecorFitsSystemWindows` -- and that THIRD
     *  clause is a PROBE, inlined by R8 (which is why three rounds of
     *  disassembly walked past it):
     *      NON_EMPTY = Insets.of(0,1,0,1)          // static, synthetic
     *      decorFitsSystemWindows() {
     *          ViewCompat.computeSystemWindowInsets(probe, NON_EMPTY, mTmpRect);
     *          return !mTmpRect.equals(ZERO_INSETS);
     *      }
     *  (androidx-main ActionBarOverlayLayout.java:146-153, :312-315, :456-465.)
     *  Real Android hands the synthetic (0,1,0,1) back in outLocalInsets, so the
     *  rect is non-empty, decor "fits", and the content view gets
     *  topMargin = barHeight. We answered zero, so AppCompat concluded the window
     *  was edge-to-edge and routed the bar height into the dispatched-insets path
     *  that nothing consumes. The bar measured correctly (808x112) and the content
     *  never moved -- a 99.97%-identical frame with nothing thrown, which is
     *  exactly why the earlier flag-flip experiment was reverted as "inert".
     *
     *  ⚠ NOTE the probe uses that STATIC synthetic object and NEVER our dispatched
     *  insets, so the zero-insets dispatch in ViewCanvasDriver is NOT implicated
     *  and does not need to change. (That was the standing doc's §9 conclusion and
     *  it is refuted.)
     *
     *  Gated on macapkDecorFitsSystemWindows(): an app that opted into
     *  edge-to-edge keeps the empty answer, which is AOSP's own listener==null
     *  path. Every existing dispatch in this tree carries ZERO insets, and
     *  consuming zero is zero, so no other caller changes behaviour. */
    public android.view.WindowInsets computeSystemWindowInsets(
            android.view.WindowInsets in, android.graphics.Rect outLocalInsets) {
        if (in == null) {
            if (outLocalInsets != null) outLocalInsets.set(0, 0, 0, 0);
            return in;
        }
        if (!android.view.Window.macapkDecorFitsSystemWindows()) {
            // App asked to draw behind the bars: AOSP consumes nothing here.
            if (outLocalInsets != null) outLocalInsets.set(0, 0, 0, 0);
            return in;
        }
        if (outLocalInsets != null) {
            outLocalInsets.set(in.getSystemWindowInsetLeft(),
                               in.getSystemWindowInsetTop(),
                               in.getSystemWindowInsetRight(),
                               in.getSystemWindowInsetBottom());
        }
        return in.consumeSystemWindowInsets();
    }

    // ---- gesture-exclusion / keep-clear rects (API 29/33): stored, honoured
    // by no compositor here, but read back by layout code. --------------------
    private java.util.List<android.graphics.Rect> mSystemGestureExclusionRects = java.util.Collections.emptyList();
    private java.util.List<android.graphics.Rect> mPreferKeepClearRects = java.util.Collections.emptyList();
    public void setSystemGestureExclusionRects(java.util.List<android.graphics.Rect> rects) {
        mSystemGestureExclusionRects = rects != null ? rects : java.util.Collections.<android.graphics.Rect>emptyList();
    }
    public java.util.List<android.graphics.Rect> getSystemGestureExclusionRects() { return mSystemGestureExclusionRects; }
    public void setPreferKeepClearRects(java.util.List<android.graphics.Rect> rects) {
        mPreferKeepClearRects = rects != null ? rects : java.util.Collections.<android.graphics.Rect>emptyList();
    }
    public java.util.List<android.graphics.Rect> getPreferKeepClearRects() { return mPreferKeepClearRects; }
    public void setPreferKeepClear(boolean preferKeepClear) { mPreferKeepClear = preferKeepClear; }
    public boolean isPreferKeepClear() { return mPreferKeepClear; }
    private boolean mPreferKeepClear;

    // ---- autofill hints / content capture ------------------------------------
    private String[] mAutofillHints;
    public void setAutofillHints(String... autofillHints) {
        mAutofillHints = (autofillHints != null && autofillHints.length > 0) ? autofillHints : null;
    }
    public String[] getAutofillHints() { return mAutofillHints; }
    private int mContentSensitivity;
    public void setContentSensitivity(int mode) { mContentSensitivity = mode; }
    public int getContentSensitivity() { return mContentSensitivity; }
    public boolean isImportantForContentCapture() { return false; }

    // ---- touch delegate ------------------------------------------------------
    private android.view.TouchDelegate mTouchDelegate;
    public void setTouchDelegate(android.view.TouchDelegate delegate) { mTouchDelegate = delegate; }
    public android.view.TouchDelegate getTouchDelegate() { return mTouchDelegate; }

    // ---- camera distance (z-depth for rotation) ------------------------------
    /** AOSP setCameraDistance / getCameraDistance:
     *      public void setCameraDistance(float distance) {
     *          final float dpi = mResources.getDisplayMetrics().densityDpi;
     *          ...
     *          mRenderNode.setCameraDistance(Math.abs(distance) / dpi);
     *      }
     *      public float getCameraDistance() {
     *          final float dpi = mResources.getDisplayMetrics().densityDpi;
     *          return mRenderNode.getCameraDistance() * dpi;
     *      }
     *  Three separate facts the old pair of one-liners lost:
     *    - the stored value is DENSITY-INDEPENDENT, so the DEFAULT is not zero.
     *      RenderNode's default is 8, and View's own javadoc pins the resulting
     *      answers: "on a medium density display the default distance is 1280.
     *      On a high density display the default distance is 1920" -- i.e.
     *      8 * densityDpi (1280/160, 1920/240). fwprobe VW.tx.def.cameraDistance
     *      reads 2560 on the 320dpi reference and read 0.0 here.
     *    - the setter takes the ABSOLUTE value, so a negative distance reads
     *      back positive (VW.tx.camdist.neg: 1280 vs -1280).
     *    - RenderNode.setCameraDistance rejects a non-finite or negative
     *      distance with an IllegalArgumentException, so NaN THROWS rather
     *      than being stored (VW.tx.camdist.nan).
     *  The value is not consumed by our draw path (we have no 3D camera); it is
     *  a property apps read back, and reading back a different number than the
     *  platform would is what makes a rotation animation compute wrong. */
    private float mCameraDistanceDp = 8f;
    private float macapkDensityDpi() {
        try {
            android.content.res.Resources res = getResources();
            if (res != null && res.getDisplayMetrics() != null
                    && res.getDisplayMetrics().densityDpi > 0) {
                return res.getDisplayMetrics().densityDpi;
            }
        } catch (Throwable ignored) {}
        return android.util.DisplayMetrics.DENSITY_DEFAULT;
    }
    public void setCameraDistance(float distance) {
        final float dpi = macapkDensityDpi();
        final float v = Math.abs(distance) / dpi;
        // RenderNode.setCameraDistance's own guard, hoisted here because we
        // have no RenderNode to raise it.
        if (Float.isNaN(v) || Float.isInfinite(v) || v < 0.0f) {
            throw new IllegalArgumentException(
                "distance must be finite & positive, given=" + Math.abs(distance) / dpi);
        }
        mCameraDistanceDp = v;
    }
    public float getCameraDistance() { return mCameraDistanceDp * macapkDensityDpi(); }

    // ---- drawing cache (deprecated since API 28; AOSP returns null / no-ops) --
    // setDrawingCacheEnabled/isDrawingCacheEnabled already defined earlier.
    /** Three protected constants a View subclass overrides and the framework
     *  reads back. All three were entirely absent, so a subclass that overrode
     *  one had nothing to override and every internal call site would have been
     *  a NoSuchMethodError (fwprobe VW.id.{onsetalpha,solidcolor,padoffsetreq}).
     *  AOSP bodies: onSetAlpha:25864 `return false`, getSolidColor:22682
     *  `return 0`, isPaddingOffsetRequired:21732 `return false`. */
    protected boolean onSetAlpha(int alpha) { return false; }
    public int getSolidColor() { return 0; }
    protected boolean isPaddingOffsetRequired() { return false; }
    protected int getLeftPaddingOffset()   { return 0; }
    protected int getRightPaddingOffset()  { return 0; }
    protected int getTopPaddingOffset()    { return 0; }
    protected int getBottomPaddingOffset() { return 0; }

    public void buildDrawingCache() { }
    public void buildDrawingCache(boolean autoScale) { }
    public void destroyDrawingCache() { }
    public android.graphics.Bitmap getDrawingCache() { return null; }
    public android.graphics.Bitmap getDrawingCache(boolean autoScale) { return null; }

    // ---- misc query hooks ----------------------------------------------------
    // findViewWithTag walks the subtree by tag; the leaf matches only itself
    // and ViewGroup.findViewWithTagTraversal overrides to recurse (that
    // override was missing until 2026-09-25, so a lookup from any group found
    // only the group). AOSP android14-release View.java:26525-26530 and
    // 26636-26641; the generic <T extends View> signatures are AOSP's and erase
    // to the same descriptors the old View-returning ones had. The leaf reads
    // the tag FIELD (AOSP's mTag), not getTag(), so a subclass overriding
    // getTag() does not change what the search matches, as on a device.
    public final <T extends View> T findViewWithTag(Object tag) {
        if (tag == null) return null;
        return findViewWithTagTraversal(tag);
    }
    @SuppressWarnings("unchecked")
    protected <T extends View> T findViewWithTagTraversal(Object tag) {
        if (tag != null && tag.equals(this.tag)) return (T) this;
        return null;
    }
    // AOSP declares this protected; CompoundButton and others override it.
    protected boolean verifyDrawable(android.graphics.drawable.Drawable who) {
        return who == getBackground();
    }
    public boolean isShowingLayoutBounds() { return false; }
    /** AOSP View.dispatchHoverEvent (android14 View.java:16829): the
     *  OnHoverListener first (an enabled view only), then onHoverEvent. Both
     *  used to answer false, so no view ever saw a hover (GitHub #25). */
    public boolean dispatchHoverEvent(android.view.MotionEvent event) {
        if (!HOVER_VIEWS) return false;
        if (mOnHoverListener != null && isEnabled() && mOnHoverListener.onHover(this, event)) {
            return true;
        }
        return onHoverEvent(event);
    }
    /** AOSP View.onHoverEvent (android14): a hoverable view (enabled and
     *  clickable / long-clickable / context-clickable), or one already hovered,
     *  takes the hovered state on ENTER and drops it on EXIT, offers the event to
     *  onGenericMotionEvent for compatibility, and consumes it. The accessibility
     *  hover events and the scrollbar wake-up are not modelled. */
    public boolean onHoverEvent(android.view.MotionEvent event) {
        if (!HOVER_VIEWS) return false;
        if (isHoverable() || isHovered()) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER: setHovered(true); break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT: setHovered(false); break;
                default: break;
            }
            if (onGenericMotionListener != null && isEnabled()
                    && onGenericMotionListener.onGenericMotion(this, event)) {
                return true;
            }
            onGenericMotionEvent(event);
            return true;
        }
        return false;
    }
    /** AOSP View.isHoverable (android14 View.java:17859). */
    private boolean isHoverable() {
        if (!isEnabled()) return false;
        return isClickable() || isLongClickable() || isContextClickable();
    }
    /** AOSP @hide hasHoveredChild / pointInHoveredChild: a plain View has no
     *  children; ViewGroup answers from its hover targets. */
    protected boolean hasHoveredChild() { return false; }
    protected boolean pointInHoveredChild(android.view.MotionEvent event) { return false; }
    /** MACAPK_HOVER_EVENTS (default ON; =0 = the pre-change views, which never
     *  answered a hover). Read by ViewGroup's hover routing and TouchDispatcher. */
    public static final boolean HOVER_VIEWS = !"0".equals(System.getenv("MACAPK_HOVER_EVENTS"));
    private OnHoverListener mOnHoverListener;

    // ---- content-translation (API 31): no on-device translator ---------------
    public void setViewTranslationCallback(android.view.translation.ViewTranslationCallback callback) { }
    public void clearViewTranslationCallback() { }

    /** AOSP View.onDragEvent (API 11): the default declines the drag. */
    public boolean onDragEvent(DragEvent event) { return false; }

    // AOSP mPrivateFlags2 PFLAG2_DRAG_CAN_ACCEPT / PFLAG2_DRAG_HOVERED.
    boolean mMacapkDragCanAccept, mMacapkDragHovered;
    /** AOSP View.canAcceptDrag (:28391). */
    boolean canAcceptDrag() { return mMacapkDragCanAccept; }

    /** AOSP View.dispatchDragEvent (android14 :28335-28342), verbatim modulo
     *  the root: getViewRootImpl().setDragFocus is the session's. */
    public boolean dispatchDragEvent(DragEvent event) {
        event.mEventHandlerWasCalled = true;
        if (event.mAction == DragEvent.ACTION_DRAG_LOCATION
                || event.mAction == DragEvent.ACTION_DROP) {
            MacApkDragSession.setDragFocus(this, event);
        }
        return callDragEventHandler(event);
    }

    /** AOSP View.callDragEventHandler (:28346-28389), minus the accessibility
     *  events: the listener (when enabled) first, onDragEvent otherwise, then the
     *  per-action drag-state bits. */
    final boolean callDragEventHandler(DragEvent event) {
        final boolean result;
        if (mOnDragListener != null && isEnabled() && mOnDragListener.onDrag(this, event)) {
            result = true;
        } else {
            result = onDragEvent(event);
        }
        switch (event.mAction) {
            case DragEvent.ACTION_DRAG_ENTERED:
                mMacapkDragHovered = true;
                refreshDrawableState();
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                mMacapkDragHovered = false;
                refreshDrawableState();
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                mMacapkDragCanAccept = false;
                mMacapkDragHovered = false;
                refreshDrawableState();
                break;
            default:
                break;
        }
        return result;
    }

    /** AOSP View.dispatchDragEnterExitInPreN (:28315): pre-N cascaded ENTER/EXIT. */
    boolean dispatchDragEnterExitInPreN(DragEvent event) {
        return callDragEventHandler(event);
    }

    /** AOSP View.inflate (API 1): inflate a layout as a child of root. */
    public static View inflate(android.content.Context context, int resource, android.view.ViewGroup root) {
        return android.view.LayoutInflater.from(context).inflate(resource, root);
    }

    public android.view.ActionMode startActionMode(android.view.ActionMode.Callback callback, int type) { return null; }
    /** AOSP View.startActionMode(Callback) (android11-release
     *  View.java:7630-7632): starts an action mode with the default type.
     *  Delegates to the 2-arg overload exactly as AOSP does. */
    public android.view.ActionMode startActionMode(android.view.ActionMode.Callback callback) {
        return startActionMode(callback, android.view.ActionMode.TYPE_PRIMARY);
    }
    public void dispatchProvideAutofillStructure(android.view.ViewStructure structure, int flags) { }
    /** AOSP View.onProvideStructure(ViewStructure) (android11-release
     *  View.java:8707-8709): entry point Activity.onProvideAssistData's
     *  ViewRootImpl walk calls per view. Real AOSP delegates to a 3-arg
     *  overload that fills in class name, bounds, text, etc. This tree has no
     *  assist dispatcher to drive that walk (no ViewRootImpl getAssist* path)
     *  and ViewStructure itself is a documented sink that accepts and drops
     *  every setter (see ViewStructure.java's own header comment), so a
     *  fuller body has no consumer to reach. This exists so a subclass that
     *  overrides onProvideStructure and calls super doesn't hit
     *  NoSuchMethodError -- same empty-body pattern as the autofill siblings
     *  immediately below, which this sits next to on purpose. */
    public void onProvideStructure(android.view.ViewStructure structure) { }

    public void autofill(android.view.autofill.AutofillValue value) { }
    public boolean dispatchKeyEventPreIme(android.view.KeyEvent event) { return false; }
    protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) { return false; }
    public void findViewsWithText(java.util.ArrayList<View> outViews, CharSequence searched, int flags) { }
    /** AOSP View.focusSearch (View.java:11239-11245):
     *
     *      public View focusSearch(@FocusRealDirection int direction) {
     *          if (mParent != null) { return mParent.focusSearch(this, direction); }
     *          else { return null; }
     *      }
     *
     *  ⚠ This was `return null` -- an unconditional stub, and the ONE missing
     *  link in an otherwise complete focus-navigation chain. `ViewGroup.findFocus`,
     *  `ViewGroup.focusSearch(View,int)`, `ViewGroup.addFocusables` and
     *  `FocusFinder`'s FORWARD/BACKWARD document-order traversal were all here
     *  and correct; nothing could reach them, because every caller enters through
     *  this one-argument overload. Measured on Instagram's login form: Tab moved
     *  focus nowhere, so a user could not leave the username field by keyboard. */
    public View focusSearch(int direction) {
        return parent != null ? parent.focusSearch(this, direction) : null;
    }
    public java.util.Map<Integer, Integer> getAttributeSourceResourceMap() { return java.util.Collections.emptyMap(); }
    public boolean isHapticFeedbackEnabled() { return mHapticFeedbackEnabled; }
    /** AOSP View.onAnimationEnd (View.java:28794-28796) is @CallSuper and its
     *  entire body is `mPrivateFlags &= ~PFLAG_ANIMATION_STARTED`. */
    protected void onAnimationEnd() { mAnimationStarted = false; }
    /** AOSP View.onAnimationStart (View.java:28782) is @CallSuper and its entire
     *  body is `mPrivateFlags |= PFLAG_ANIMATION_STARTED`. */
    protected void onAnimationStart() { mAnimationStarted = true; }
    public boolean onKeyPreIme(int keyCode, android.view.KeyEvent event) { return false; }
    public void onProvideAutofillStructure(android.view.ViewStructure structure, int flags) { }
    public void onProvideAutofillVirtualStructure(android.view.ViewStructure structure, int flags) { }
    public void onScreenStateChanged(int screenState) { }
    public void onVisibilityAggregated(boolean isVisible) { }
    public void onWindowSystemUiVisibilityChanged(int visible) { }
    public void postInvalidateDelayed(long delayMilliseconds, int left, int top, int right, int bottom) { postInvalidateDelayed(delayMilliseconds); }
    public void postInvalidateOnAnimation(int left, int top, int right, int bottom) { postInvalidateOnAnimation(); }
    public void releasePointerCapture() { }
    public void requestPointerCapture() { }
    public void setAccessibilityTraversalBefore(int beforeId) {
        // Was a discard; now records the id so the AOSP getter added below
        // (getAccessibilityTraversalBefore) can answer it. Nothing else in
        // this tree reads it -- we bridge no accessibility service.
        macapkSetAccessibilityTraversalBeforeId(beforeId);
    }
    public void setDrawingCacheBackgroundColor(int color) {
        macapkSetDrawingCacheBackgroundColor(color);
    }
    public void setDrawingCacheQuality(int quality) {
        macapkSetDrawingCacheQuality(quality);
    }

    // ======================================================================
    //  AOSP API-36 surface completion (Mac.apk API-gap lane, android.view).
    //  Every member below is a name AOSP's core/api/current.txt declares on
    //  android.view.View and this file did not have. Bodies mirror
    //  frameworks/base/core/java/android/view/View.java (android16-release);
    //  where AOSP's body reads state Mac.apk has no analogue for (AttachInfo,
    //  RenderNode, IWindowSession) the comment says which branch of AOSP's own
    //  code we are therefore taking, so the answer is one a real device also
    //  gives -- never an invented one.
    // ======================================================================

    // ---- scroll capture (API 31) ---------------------------------------------
    /** AOSP View.SCROLL_CAPTURE_HINT_* (View.java:1603-1633). */
    public static final int SCROLL_CAPTURE_HINT_AUTO = 0;
    public static final int SCROLL_CAPTURE_HINT_EXCLUDE = 0x1;
    public static final int SCROLL_CAPTURE_HINT_INCLUDE = 0x2;
    public static final int SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS = 0x4;

    private int mScrollCaptureHint = SCROLL_CAPTURE_HINT_AUTO;
    private ScrollCaptureCallback mScrollCaptureCallback;

    /** AOSP View.setScrollCaptureHint / getScrollCaptureHint: stored in
     *  PFLAG4_SCROLL_CAPTURE_HINT_MASK there, a plain field here. */
    public void setScrollCaptureHint(int hint) { mScrollCaptureHint = hint; }
    public int getScrollCaptureHint() { return mScrollCaptureHint; }

    /** AOSP View.setScrollCaptureCallback:33216
     *  `getListenerInfo().mScrollCaptureCallback = callback;` */
    public final void setScrollCaptureCallback(ScrollCaptureCallback callback) {
        mScrollCaptureCallback = callback;
    }

    /** AOSP View.dispatchScrollCaptureSearch:33244 -- delegates straight to
     *  onScrollCaptureSearch. ViewGroup overrides to recurse. */
    public void dispatchScrollCaptureSearch(android.graphics.Rect localVisibleRect,
            android.graphics.Point windowOffset,
            java.util.function.Consumer<ScrollCaptureTarget> targets) {
        onScrollCaptureSearch(localVisibleRect, windowOffset, targets);
    }

    /** AOSP View.onScrollCaptureSearch:33262. The one branch we cannot take is
     *  AOSP's `createScrollCaptureCallbackInternal(...)` fallback, which
     *  synthesises a callback for a recognised scrolling container out of
     *  ViewRootImpl-side state; without it a view that registered no explicit
     *  callback simply contributes no target, which is exactly what AOSP does
     *  for a non-scrolling view. */
    public void onScrollCaptureSearch(android.graphics.Rect localVisibleRect,
            android.graphics.Point windowOffset,
            java.util.function.Consumer<ScrollCaptureTarget> targets) {
        if ((getScrollCaptureHint() & SCROLL_CAPTURE_HINT_EXCLUDE) != 0) return;
        android.graphics.Rect clip = getClipBounds();
        if (clip != null && !localVisibleRect.intersect(clip)) return;
        ScrollCaptureCallback callback = mScrollCaptureCallback;
        if (callback != null) {
            targets.accept(new ScrollCaptureTarget(this,
                    new android.graphics.Rect(localVisibleRect),
                    new android.graphics.Point(windowOffset.x, windowOffset.y), callback));
        }
    }

    // ---- pointer capture (API 26) --------------------------------------------
    /** AOSP View.OnCapturedPointerListener (View.java, nested interface). */
    public interface OnCapturedPointerListener {
        boolean onCapturedPointer(View view, MotionEvent event);
    }
    private OnCapturedPointerListener mOnCapturedPointerListener;
    public void setOnCapturedPointerListener(OnCapturedPointerListener l) {
        mOnCapturedPointerListener = l;
    }
    /** AOSP View.hasPointerCapture() asks mAttachInfo.mHasWindowFocus &&
     *  ViewRootImpl's captured-pointer state. Mac.apk never grants pointer
     *  capture (requestPointerCapture() is a no-op here, matching a device that
     *  declines), so the honest answer is false -- the same answer a real device
     *  gives before capture is granted. */
    public boolean hasPointerCapture() { return false; }
    /** AOSP View.dispatchCapturedPointerEvent:16716. */
    public boolean dispatchCapturedPointerEvent(MotionEvent event) {
        if (!hasPointerCapture()) return false;
        if (mOnCapturedPointerListener != null
                && mOnCapturedPointerListener.onCapturedPointer(this, event)) {
            return true;
        }
        return onCapturedPointerEvent(event);
    }
    /** AOSP View.onCapturedPointerEvent:31107 `return false`. */
    public boolean onCapturedPointerEvent(MotionEvent event) { return false; }
    /** AOSP View.dispatchPointerCaptureChanged:31096. */
    public void dispatchPointerCaptureChanged(boolean hasCapture) {
        onPointerCaptureChange(hasCapture);
    }
    /** AOSP View.onPointerCaptureChange:31090, empty @CallSuper hook. */
    public void onPointerCaptureChange(boolean hasCapture) { }

    // ---- generic-motion / hover / display-hint dispatch ------------------------
    /** AOSP View.dispatchGenericFocusedEvent:16918 `return false` (ViewGroup
     *  overrides to route to the focused child). */
    protected boolean dispatchGenericFocusedEvent(MotionEvent event) { return false; }
    /** AOSP View.dispatchGenericPointerEvent `return false`. */
    protected boolean dispatchGenericPointerEvent(MotionEvent event) { return false; }
    /** AOSP View.onHoverChanged:17960, empty hook. */
    public void onHoverChanged(boolean hovered) { }
    /** AOSP View.dispatchDisplayHint:17040 -> onDisplayHint. */
    public void dispatchDisplayHint(int hint) { onDisplayHint(hint); }
    /** AOSP View.onDisplayHint, empty protected hook. */
    protected void onDisplayHint(int hint) { }
    /** AOSP View.dispatchDrawableHotspotChanged:26150 is EMPTY on View (only
     *  ViewGroup forwards to children); the view's own hotspot already moved via
     *  drawableHotspotChanged. */
    public void dispatchDrawableHotspotChanged(float x, float y) { }
    /** AOSP View.dispatchUnhandledMove:14910 `return false`. */
    public boolean dispatchUnhandledMove(View focused, int direction) { return false; }
    /** AOSP View.onFilterTouchEventForSecurity:16674. The FILTER_TOUCHES_WHEN_
     *  OBSCURED branch is the one that matters to an app; Mac.apk never sets
     *  MotionEvent.FLAG_WINDOW_IS_OBSCURED (nothing can overlay our window), so
     *  the guard is evaluated and passes, exactly as on an unobscured device. */
    public boolean onFilterTouchEventForSecurity(MotionEvent event) {
        if (getFilterTouchesWhenObscured()
                && (event.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0) {
            return false;
        }
        return true;
    }
    /** AOSP View.onKeyShortcut:17472 `return false`. */
    public boolean onKeyShortcut(int keyCode, KeyEvent event) { return false; }
    /** AOSP View.checkInputConnectionProxy:17566 `return false`. */
    public boolean checkInputConnectionProxy(View view) { return false; }
    /** AOSP View.addChildrenForAccessibility:15771 is empty on View; ViewGroup
     *  overrides it to add its important-for-a11y children. */
    public void addChildrenForAccessibility(java.util.ArrayList<View> outChildren) { }
    /** AOSP View.dispatchNestedPrePerformAccessibilityAction:16059. */
    public boolean dispatchNestedPrePerformAccessibilityAction(int action,
            android.os.Bundle arguments) {
        for (ViewParent p = getParent(); p != null; p = p.getParent()) {
            if (p.onNestedPrePerformAccessibilityAction(this, action, arguments)) return true;
        }
        return false;
    }
    /** AOSP View.onCancelPendingInputEvents:23256 -- cancels the pending click
     *  and long-press callbacks. We route through the public cancelLongPress()
     *  that already exists here. */
    public void onCancelPendingInputEvents() {
        cancelLongPress();
    }
    /** AOSP View.requestUnbufferedDispatch(MotionEvent):18439. Sets
     *  AttachInfo.mUnbufferedDispatchRequested, which ViewRootImpl reads to stop
     *  batching input for the rest of the gesture. Mac.apk delivers Cocoa mouse
     *  events one-for-one already (cocoa_bridge.mm posts each NSEvent straight
     *  into TouchDispatcher; there is no batching stage to disable), so the
     *  request is already satisfied and there is nothing to record. */
    public final void requestUnbufferedDispatch(MotionEvent event) { }
    /** AOSP View.requestUnbufferedDispatch(int):18463, the source-class form. */
    public final void requestUnbufferedDispatch(int source) { }

    // ---- window-insets animation callback dispatch (API 30) -------------------
    // AOSP View.dispatchWindowInsetsAnimation*: each one hands the event to the
    // view's own WindowInsetsAnimation.Callback if it registered one. These are
    // driven by InsetsAnimationThreadControlRunner on a device; Mac.apk runs no
    // insets animation, so nothing in this tree calls them -- they exist so an
    // app (or a library that subclasses View) can override and super-call.
    public void dispatchWindowInsetsAnimationPrepare(WindowInsetsAnimation animation) {
        if (mWindowInsetsAnimationCallback != null) {
            mWindowInsetsAnimationCallback.onPrepare(animation);
        }
    }
    public WindowInsetsAnimation.Bounds dispatchWindowInsetsAnimationStart(
            WindowInsetsAnimation animation, WindowInsetsAnimation.Bounds bounds) {
        if (mWindowInsetsAnimationCallback != null) {
            return mWindowInsetsAnimationCallback.onStart(animation, bounds);
        }
        return bounds;
    }
    public WindowInsets dispatchWindowInsetsAnimationProgress(WindowInsets insets,
            java.util.List<WindowInsetsAnimation> runningAnimations) {
        if (mWindowInsetsAnimationCallback != null) {
            return mWindowInsetsAnimationCallback.onProgress(insets, runningAnimations);
        }
        return insets;
    }
    public void dispatchWindowInsetsAnimationEnd(WindowInsetsAnimation animation) {
        if (mWindowInsetsAnimationCallback != null) {
            mWindowInsetsAnimationCallback.onEnd(animation);
        }
    }
    /** AOSP View.dispatchSystemUiVisibilityChanged (deprecated at API 30, and a
     *  no-op there once the flags moved to WindowInsetsController). */
    @Deprecated
    public void dispatchSystemUiVisibilityChanged(int visibility) { }
    /** AOSP View.dispatchWindowSystemUiVisiblityChanged (AOSP's own spelling --
     *  "Visiblity" -- is part of the published API and must be reproduced).
     *  Deprecated at API 30; forwards to the hook this file already has. */
    @Deprecated
    public void dispatchWindowSystemUiVisiblityChanged(int visible) {
        onWindowSystemUiVisibilityChanged(visible);
    }

    // ---- transparent region --------------------------------------------------
    /** AOSP View.gatherTransparentRegion:28827. The region walk exists so
     *  SurfaceView can punch a hole through the window's transparent region;
     *  Mac.apk composites SurfaceView content through the same ANGLE/Metal layer
     *  rather than a separate SurfaceFlinger layer, so there is no window
     *  transparent region for a caller to collect. AOSP's own guard is
     *  `if (region != null && attachInfo != null)` and it returns true either
     *  way -- with no AttachInfo we take that same skip branch. */
    public boolean gatherTransparentRegion(android.graphics.Region region) { return true; }

    // ---- display hash (API 31) -----------------------------------------------
    /** AOSP View.generateDisplayHash:34148. Its FIRST guard is
     *  `IWindowSession session = getWindowSession(); if (session == null) {
     *  callback.onDisplayHashError(DISPLAY_HASH_ERROR_MISSING_WINDOW); return; }`.
     *  Mac.apk has no IWindowSession at all, so that is the branch a device
     *  without one takes and the branch we take -- reported through the caller's
     *  Executor, as AOSP reports every result. Consistent with
     *  DisplayHashManager.getSupportedHashAlgorithms() returning an empty set:
     *  we do not advertise a capability we cannot honour. */
    public void generateDisplayHash(String hashAlgorithm, android.graphics.Rect bounds,
            java.util.concurrent.Executor executor,
            android.view.displayhash.DisplayHashResultCallback callback) {
        executor.execute(() -> callback.onDisplayHashError(
                android.view.displayhash.DisplayHashResultCallback
                        .DISPLAY_HASH_ERROR_MISSING_WINDOW));
    }

    // ---- accessibility traversal order ---------------------------------------
    private int mAccessibilityTraversalBeforeId = NO_ID;
    private int mAccessibilityTraversalAfterId = NO_ID;
    /** AOSP View.setAccessibilityTraversalAfter / getAccessibilityTraversalAfter
     *  (and the Before pair immediately above it in AOSP): a plain id round-trip.
     *  Nothing in Mac.apk consumes the order (we bridge no a11y service), but an
     *  app that writes then reads its own traversal order must see it. */
    public void setAccessibilityTraversalAfter(int afterId) {
        mAccessibilityTraversalAfterId = afterId;
    }
    public int getAccessibilityTraversalAfter() { return mAccessibilityTraversalAfterId; }
    public int getAccessibilityTraversalBefore() { return mAccessibilityTraversalBeforeId; }
    /** @hide -- lets the pre-existing setAccessibilityTraversalBefore(int) record
     *  what it was given so the getter above can answer. */
    final void macapkSetAccessibilityTraversalBeforeId(int id) {
        mAccessibilityTraversalBeforeId = id;
    }

    // ---- accessibility data sensitivity (API 34) -----------------------------
    /** AOSP View.ACCESSIBILITY_DATA_SENSITIVE_* (View.java:3334-3348). */
    public static final int ACCESSIBILITY_DATA_SENSITIVE_AUTO = 0x00000000;
    public static final int ACCESSIBILITY_DATA_SENSITIVE_YES = 0x00000001;
    public static final int ACCESSIBILITY_DATA_SENSITIVE_NO = 0x00000002;
    private int mExplicitAccessibilityDataSensitive = ACCESSIBILITY_DATA_SENSITIVE_AUTO;
    public void setAccessibilityDataSensitive(int accessibilityDataSensitive) {
        mExplicitAccessibilityDataSensitive = accessibilityDataSensitive;
    }
    /** AOSP View.isAccessibilityDataSensitive:15840 + calculateAccessibility
     *  DataSensitive:15855, inlined: explicit value wins, else
     *  filterTouchesWhenObscured implies YES, else inherit from a View parent,
     *  else NO. */
    public boolean isAccessibilityDataSensitive() {
        if (mExplicitAccessibilityDataSensitive != ACCESSIBILITY_DATA_SENSITIVE_AUTO) {
            return mExplicitAccessibilityDataSensitive == ACCESSIBILITY_DATA_SENSITIVE_YES;
        }
        if (getFilterTouchesWhenObscured()) return true;
        ViewParent p = getParent();
        return (p instanceof View) && ((View) p).isAccessibilityDataSensitive();
    }

    // ---- content sensitivity (API 35) ----------------------------------------
    /** AOSP View.CONTENT_SENSITIVITY_* (View.java:2013-2033). */
    public static final int CONTENT_SENSITIVITY_AUTO = 0x0;
    public static final int CONTENT_SENSITIVITY_SENSITIVE = 0x1;
    public static final int CONTENT_SENSITIVITY_NOT_SENSITIVE = 0x2;
    /** AOSP View.isContentSensitive:10628. The AUTO branch consults
     *  SensitiveAutofillHintsHelper, whose whole job is to recognise the
     *  password-ish autofill hints; we reproduce that check directly against the
     *  same AUTOFILL_HINT_* names rather than guess. */
    public final boolean isContentSensitive() {
        final int sensitivity = getContentSensitivity();
        if (sensitivity == CONTENT_SENSITIVITY_SENSITIVE) return true;
        if (sensitivity == CONTENT_SENSITIVITY_NOT_SENSITIVE) return false;
        final String[] hints = getAutofillHints();
        if (hints == null) return false;
        for (String h : hints) {
            if (h == null) continue;
            if ("password".equals(h)
                    || "creditCardNumber".equals(h)
                    || "creditCardSecurityCode".equals(h)
                    || "creditCardExpirationDate".equals(h)
                    || "creditCardExpirationDay".equals(h)
                    || "creditCardExpirationMonth".equals(h)
                    || "creditCardExpirationYear".equals(h)) {
                return true;
            }
        }
        return false;
    }

    // ---- credential manager integration (API 35) -----------------------------
    private boolean mIsCredential;
    private android.credentials.GetCredentialRequest mPendingCredentialRequest;
    private android.os.OutcomeReceiver<android.credentials.GetCredentialResponse,
            android.credentials.GetCredentialException> mPendingCredentialCallback;
    /** AOSP View.setIsCredential:33827 / isCredential:33844 (a PFLAG4 bit). */
    public void setIsCredential(boolean isCredential) { mIsCredential = isCredential; }
    public boolean isCredential() { return mIsCredential; }
    /** AOSP View.setPendingCredentialRequest / getPendingCredentialRequest /
     *  getPendingCredentialCallback / clearPendingCredentialRequest: a plain
     *  request+callback round-trip on the view. Mac.apk bridges no credential
     *  provider, so the request is never fulfilled -- which is the same
     *  observable outcome as a device with no credential provider installed. */
    public void setPendingCredentialRequest(android.credentials.GetCredentialRequest request,
            android.os.OutcomeReceiver<android.credentials.GetCredentialResponse,
                    android.credentials.GetCredentialException> callback) {
        mPendingCredentialRequest = request;
        mPendingCredentialCallback = callback;
    }
    public final android.credentials.GetCredentialRequest getPendingCredentialRequest() {
        return mPendingCredentialRequest;
    }
    public final android.os.OutcomeReceiver<android.credentials.GetCredentialResponse,
            android.credentials.GetCredentialException> getPendingCredentialCallback() {
        return mPendingCredentialCallback;
    }
    public void clearPendingCredentialRequest() {
        mPendingCredentialRequest = null;
        mPendingCredentialCallback = null;
    }

    // ---- stylus handwriting (API 33/35) --------------------------------------
    // AOSP stores the enable bit in PFLAG4_AUTO_HANDWRITING_ENABLED (default ON,
    // set in the View constructor) and the four bounds offsets in plain fields
    // (View.setHandwritingBoundsOffsets:13208). Mac.apk has no stylus source and
    // no handwriting IME, so the values only round-trip -- the same as on a
    // device with no stylus, where the offsets are honoured by nobody either.
    private boolean mAutoHandwritingEnabled = true;
    private float mHandwritingBoundsOffsetLeft;
    private float mHandwritingBoundsOffsetTop;
    private float mHandwritingBoundsOffsetRight;
    private float mHandwritingBoundsOffsetBottom;
    private boolean mIsHandwritingDelegate;
    private int mHandwritingDelegateFlags;
    private Runnable mHandwritingDelegatorCallback;
    private String mAllowedHandwritingDelegatePackageName;
    private String mAllowedHandwritingDelegatorPackageName;

    public void setAutoHandwritingEnabled(boolean enabled) { mAutoHandwritingEnabled = enabled; }
    public boolean isAutoHandwritingEnabled() { return mAutoHandwritingEnabled; }
    public void setHandwritingBoundsOffsets(float offsetLeft, float offsetTop,
            float offsetRight, float offsetBottom) {
        mHandwritingBoundsOffsetLeft = offsetLeft;
        mHandwritingBoundsOffsetTop = offsetTop;
        mHandwritingBoundsOffsetRight = offsetRight;
        mHandwritingBoundsOffsetBottom = offsetBottom;
    }
    public float getHandwritingBoundsOffsetLeft() { return mHandwritingBoundsOffsetLeft; }
    public float getHandwritingBoundsOffsetTop() { return mHandwritingBoundsOffsetTop; }
    public float getHandwritingBoundsOffsetRight() { return mHandwritingBoundsOffsetRight; }
    public float getHandwritingBoundsOffsetBottom() { return mHandwritingBoundsOffsetBottom; }
    public void setIsHandwritingDelegate(boolean isHandwritingDelegate) {
        mIsHandwritingDelegate = isHandwritingDelegate;
    }
    public boolean isHandwritingDelegate() { return mIsHandwritingDelegate; }
    public void setHandwritingDelegateFlags(int flags) { mHandwritingDelegateFlags = flags; }
    public int getHandwritingDelegateFlags() { return mHandwritingDelegateFlags; }
    public void setHandwritingDelegatorCallback(Runnable callback) {
        mHandwritingDelegatorCallback = callback;
    }
    public Runnable getHandwritingDelegatorCallback() { return mHandwritingDelegatorCallback; }
    public void setAllowedHandwritingDelegatePackage(String allowedPackageName) {
        mAllowedHandwritingDelegatePackageName = allowedPackageName;
    }
    public String getAllowedHandwritingDelegatePackageName() {
        return mAllowedHandwritingDelegatePackageName;
    }
    public void setAllowedHandwritingDelegatorPackage(String allowedPackageName) {
        mAllowedHandwritingDelegatorPackageName = allowedPackageName;
    }
    public String getAllowedHandwritingDelegatorPackageName() {
        return mAllowedHandwritingDelegatorPackageName;
    }

    // ---- tint blend modes (API 29) -------------------------------------------
    // AOSP keeps ONE tint mode per drawable slot: setBackgroundTintMode(
    // PorterDuff.Mode) converts to a BlendMode and writes the same
    // TintInfo.mBlendMode this pair reads. Mac.apk's PorterDuff pair predates
    // this and is a documented no-op/null (see setBackgroundTintMode above); the
    // two are deliberately NOT wired together here, because making the
    // PorterDuff getter start answering would change behaviour every Canvas-tier
    // app already depends on. See the lane report.
    private android.graphics.BlendMode mBackgroundTintBlendMode;
    private android.graphics.BlendMode mForegroundTintBlendMode;
    public void setBackgroundTintBlendMode(android.graphics.BlendMode blendMode) {
        mBackgroundTintBlendMode = blendMode;
    }
    public android.graphics.BlendMode getBackgroundTintBlendMode() {
        return mBackgroundTintBlendMode;
    }
    public void setForegroundTintBlendMode(android.graphics.BlendMode blendMode) {
        mForegroundTintBlendMode = blendMode;
    }
    public android.graphics.BlendMode getForegroundTintBlendMode() {
        return mForegroundTintBlendMode;
    }

    // ---- scrollbar drawables (API 29) ----------------------------------------
    // Companions to the setVerticalScrollbarThumbDrawable already in this file:
    // stored, and read back, with no ScrollBarDrawable to feed (see that
    // method's comment for why).
    private android.graphics.drawable.Drawable mVerticalScrollbarTrackDrawable;
    private android.graphics.drawable.Drawable mHorizontalScrollbarThumbDrawable;
    private android.graphics.drawable.Drawable mHorizontalScrollbarTrackDrawable;
    public void setVerticalScrollbarTrackDrawable(android.graphics.drawable.Drawable d) {
        mVerticalScrollbarTrackDrawable = d;
    }
    public android.graphics.drawable.Drawable getVerticalScrollbarTrackDrawable() {
        return mVerticalScrollbarTrackDrawable;
    }
    public android.graphics.drawable.Drawable getVerticalScrollbarThumbDrawable() {
        return verticalScrollbarThumbDrawable;
    }
    public void setHorizontalScrollbarThumbDrawable(android.graphics.drawable.Drawable d) {
        mHorizontalScrollbarThumbDrawable = d;
    }
    public android.graphics.drawable.Drawable getHorizontalScrollbarThumbDrawable() {
        return mHorizontalScrollbarThumbDrawable;
    }
    public void setHorizontalScrollbarTrackDrawable(android.graphics.drawable.Drawable d) {
        mHorizontalScrollbarTrackDrawable = d;
    }
    public android.graphics.drawable.Drawable getHorizontalScrollbarTrackDrawable() {
        return mHorizontalScrollbarTrackDrawable;
    }
    /** AOSP View.onDrawScrollBars(Canvas) is `protected final` and draws
     *  mScrollCache's ScrollBarDrawable. This file never allocates a
     *  ScrollBarDrawable (see setVerticalScrollbarThumbDrawable's comment), so
     *  there is nothing to draw -- the same as AOSP's own early-out when
     *  mScrollCache is null. It is `final` in AOSP, so it exists here purely so
     *  a subclass's super-call resolves. */
    protected final void onDrawScrollBars(android.graphics.Canvas canvas) { }

    // ---- structure / assist / content capture --------------------------------
    /** AOSP View.dispatchProvideStructure:11254 -> the assist form, which is
     *  `onProvideStructure(structure); onProvideVirtualStructure(structure);`
     *  when assist is not blocked. */
    public void dispatchProvideStructure(ViewStructure structure) {
        onProvideStructure(structure);
        onProvideVirtualStructure(structure);
    }
    /** AOSP View.onProvideVirtualStructure:9808 -> onProvideVirtualStructureCompat,
     *  which only does anything when the view supplies an
     *  AccessibilityNodeProvider. A view without one contributes nothing, which
     *  is the branch every view in this tree takes. */
    public void onProvideVirtualStructure(ViewStructure structure) { }
    /** AOSP View.onProvideContentCaptureStructure:9686 -> the shared structure
     *  filler with VIEW_STRUCTURE_FOR_CONTENT_CAPTURE. ViewStructure is a
     *  documented sink in this tree (see ViewStructure.java's header), so the
     *  fill has no consumer; this exists so a subclass can override + super-call. */
    public void onProvideContentCaptureStructure(ViewStructure structure, int flags) {
        onProvideStructure(structure);
    }
    /** AOSP View.getAutofillValue():`return null` on the base class -- only
     *  TextView / CompoundButton / DatePicker etc. override it. */
    public android.view.autofill.AutofillValue getAutofillValue() { return null; }
    /** AOSP View.isVisibleToUserForAutofill:11664. The autofill-compatibility
     *  branch only runs when the Context has compat mode on (a per-package
     *  system setting); with it off AOSP returns true unconditionally, which is
     *  the branch every app here takes. */
    public boolean isVisibleToUserForAutofill(int virtualId) { return true; }

    // ---- content translation (API 31) ----------------------------------------
    private android.view.translation.ViewTranslationResponse mViewTranslationResponse;
    /** AOSP View.dispatchCreateViewTranslationRequest: a view that is not
     *  important for content capture, or has no text, contributes no request.
     *  ViewGroup overrides to recurse. */
    public void dispatchCreateViewTranslationRequest(
            java.util.Map<android.view.autofill.AutofillId, long[]> viewIds,
            int[] supportedFormats,
            android.view.translation.TranslationCapability capability,
            java.util.List<android.view.translation.ViewTranslationRequest> requests) {
        if (viewIds != null && viewIds.containsKey(getAutofillId()) && requests != null) {
            onCreateViewTranslationRequest(supportedFormats, requests::add);
        }
    }
    /** AOSP View.onCreateViewTranslationRequest:33948 is empty on View; TextView
     *  overrides it to contribute its text. */
    public void onCreateViewTranslationRequest(int[] supportedFormats,
            java.util.function.Consumer<android.view.translation.ViewTranslationRequest>
                    requestsCollector) { }
    /** AOSP View.onCreateVirtualViewTranslationRequests: empty on View; only a
     *  view with virtual children (WebView) overrides it. */
    public void onCreateVirtualViewTranslationRequests(long[] virtualIds,
            int[] supportedFormats,
            java.util.function.Consumer<android.view.translation.ViewTranslationRequest>
                    requestsCollector) { }
    /** AOSP View.onViewTranslationResponse:34033 `mViewTranslationResponse = response`. */
    public void onViewTranslationResponse(
            android.view.translation.ViewTranslationResponse response) {
        mViewTranslationResponse = response;
    }
    public android.view.translation.ViewTranslationResponse getViewTranslationResponse() {
        return mViewTranslationResponse;
    }
    /** AOSP View.onVirtualViewTranslationResponses: empty on View. */
    public void onVirtualViewTranslationResponses(
            android.util.LongSparseArray<android.view.translation.ViewTranslationResponse>
                    response) { }

    // ---- content receive (API 31) --------------------------------------------
    /** AOSP View.onReceiveContent:9995 `return payload` -- the base class
     *  consumes nothing and hands the whole payload back. TextView overrides. */
    public ContentInfo onReceiveContent(ContentInfo payload) { return payload; }

    // ---- context click / context menu ----------------------------------------
    private OnContextClickListener mOnContextClickListener;
    /** AOSP View.performContextClick():8204 -- sends the a11y event, then offers
     *  the click to the registered listener and haptics it when consumed. */
    public boolean performContextClick() {
        sendAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED);
        boolean handled = false;
        if (mOnContextClickListener != null) {
            handled = mOnContextClickListener.onContextClick(this);
        }
        if (handled) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        }
        return handled;
    }
    /** AOSP View.performContextClick(float,float):8194 delegates to the no-arg form. */
    public boolean performContextClick(float x, float y) { return performContextClick(); }
    /** AOSP View.showContextMenu():8243 `return getParent().showContextMenuForChild(this)`.
     *  AOSP NPEs on a detached view; we return false instead, since a detached
     *  view genuinely has no menu host and an NPE here would be strictly worse
     *  than the "menu not shown" the caller must already handle. */
    public boolean showContextMenu() {
        ViewParent p = getParent();
        return p != null && p.showContextMenuForChild(this);
    }
    /** AOSP View.showContextMenu(float,float):8258, same detached-view note. */
    public boolean showContextMenu(float x, float y) {
        ViewParent p = getParent();
        return p != null && p.showContextMenuForChild(this, x, y);
    }
    /** AOSP View.getContextMenuInfo() `return null` -- only AdapterView and
     *  friends attach a ContextMenuInfo. */
    protected ContextMenu.ContextMenuInfo getContextMenuInfo() { return null; }
    /** @hide -- lets the pre-existing setOnContextClickListener record its
     *  listener so performContextClick() above can reach it. */
    final void macapkSetContextClickListener(OnContextClickListener l) {
        mOnContextClickListener = l;
    }

    // ---- misc round-trips ----------------------------------------------------
    /** AOSP View.hasOnLongClickListeners():7905. */
    public boolean hasOnLongClickListeners() { return onLongClickListener != null; }
    private boolean mAllowClickWhenDisabled;
    /** AOSP View.setAllowClickWhenDisabled:14300 (a PFLAG4 bit). Mac.apk's
     *  onTouchEvent already declines a disabled view, matching the default
     *  (false); the bit is recorded so an app can read its own opt-in back. */
    public void setAllowClickWhenDisabled(boolean clickableWhenDisabled) {
        mAllowClickWhenDisabled = clickableWhenDisabled;
    }
    /** @hide -- read by Mac.apk's own touch path if it ever honours the opt-in. */
    public final boolean macapkAllowClickWhenDisabled() { return mAllowClickWhenDisabled; }
    /** Harness only (com.macapk.loader.ViewTextDump `tch=`/`drg=`): AOSP keeps
     *  these in ListenerInfo with no public getter. A touch listener marks a
     *  view that handles raw MotionEvents (a drag source candidate: Anuto's
     *  TowerView starts its drag from onTouch), a drag listener marks a DROP
     *  TARGET (View.dispatchDragEvent offers it every DragEvent first). */
    public final boolean macapkHasOnTouchListener() { return onTouchListener != null; }
    public final boolean macapkHasOnDragListener() { return mOnDragListener != null; }
    /** AOSP View.isPivotSet() asks RenderNode.isPivotExplicitlySet(). This file
     *  encodes "not explicitly set" as a NaN pivot (see getPivotX/getPivotY,
     *  which fall back to the view's centre exactly when the field is NaN), so
     *  the same question is answered from the same state. */
    public boolean isPivotSet() { return !Float.isNaN(pivotX) || !Float.isNaN(pivotY); }
    /** AOSP View.getFrameContentVelocity():34400 returns 0 unless the
     *  view-velocity API is enabled on the AttachInfo. It is not here. */
    public float getFrameContentVelocity() { return 0f; }
    /** AOSP View.getLocationInSurface(int[]) is getLocationInWindow(location)
     *  plus the ViewRootImpl's surfaceInsets. Mac.apk's window has no surface
     *  insets (no drop-shadow surface expansion), so the two coincide -- the same
     *  as on a device whose window has zero surface insets. */
    public void getLocationInSurface(int[] location) { getLocationInWindow(location); }
    /** AOSP View.getRootSurfaceControl() returns mAttachInfo.getRootSurfaceControl(),
     *  and null for a view that is not attached. Mac.apk composites through
     *  ANGLE/Metal rather than SurfaceControl, so there is no root SurfaceControl
     *  to hand out and null -- AOSP's own "no window" answer -- is the honest one.
     *  Deliberately NOT a fabricated AttachedSurfaceControl: a caller that got one
     *  would queue transactions that never apply. */
    public AttachedSurfaceControl getRootSurfaceControl() { return null; }
    /** AOSP View.getWindowAttachCount() returns mWindowAttachCount, which
     *  ViewRootImpl bumps on each attach. Mac.apk attaches a view to its window
     *  once and never re-attaches it, so the count is 1 once attached and 0
     *  before -- the value a device reports for the same lifecycle. */
    protected int getWindowAttachCount() { return isAttachedToWindow() ? 1 : 0; }
    /** AOSP View.getAttributeResolutionStack(int): returns `new int[0]` unless
     *  sDebugViewAttributes was on when the view was inflated. It never is here
     *  (that flag is a developer-options toggle read from Settings.Global). */
    public int[] getAttributeResolutionStack(int attribute) { return new int[0]; }
    /** AOSP View.getExplicitStyle(): the style resource passed to the
     *  constructor, and Resources.ID_NULL when sDebugViewAttributes is off --
     *  which is the case here, same as getAttributeResolutionStack above. */
    public int getExplicitStyle() { return 0; }
    /** AOSP View.getSourceLayoutResId(): the layout this view was inflated from,
     *  recorded by LayoutInflater only when sDebugViewAttributes is on;
     *  Resources.ID_NULL otherwise. */
    public int getSourceLayoutResId() { return 0; }
    private CharSequence mSupplementalDescription;
    /** AOSP View.setSupplementalDescription / getSupplementalDescription
     *  (API 36): extra a11y text announced after the content description. */
    public void setSupplementalDescription(CharSequence supplementalDescription) {
        mSupplementalDescription = supplementalDescription;
    }
    public CharSequence getSupplementalDescription() { return mSupplementalDescription; }
    private int mDrawingCacheBackgroundColor;
    private int mDrawingCacheQuality;
    /** AOSP View.getDrawingCacheBackgroundColor / getDrawingCacheQuality: both
     *  deprecated at API 28 and both plain round-trips even on AOSP, since the
     *  drawing cache itself is gone. */
    public int getDrawingCacheBackgroundColor() { return mDrawingCacheBackgroundColor; }
    public int getDrawingCacheQuality() { return mDrawingCacheQuality; }
    /** @hide -- fed by the pre-existing setDrawingCache* no-ops. */
    final void macapkSetDrawingCacheBackgroundColor(int c) { mDrawingCacheBackgroundColor = c; }
    final void macapkSetDrawingCacheQuality(int q) { mDrawingCacheQuality = q; }

    // ---- direction/alignment resolvability (AOSP View.canResolve*) -----------
    /** AOSP View.canResolveLayoutDirection():22762 -- an explicit LTR/RTL is
     *  always resolvable; INHERIT is resolvable only if a parent can resolve. */
    public boolean canResolveLayoutDirection() {
        if (getRawLayoutDirection() != LAYOUT_DIRECTION_INHERIT) return true;
        ViewParent p = getParent();
        return p != null && p.canResolveLayoutDirection();
    }
    /** AOSP View.canResolveTextDirection():30567, same shape. */
    public boolean canResolveTextDirection() {
        if (getRawTextDirection() != TEXT_DIRECTION_INHERIT) return true;
        ViewParent p = getParent();
        return p != null && p.canResolveTextDirection();
    }
    /** AOSP View.canResolveTextAlignment():30823, same shape. */
    public boolean canResolveTextAlignment() {
        if (getRawTextAlignment() != TEXT_ALIGNMENT_INHERIT) return true;
        ViewParent p = getParent();
        return p != null && p.canResolveTextAlignment();
    }

    /** AOSP View.setFrame(int, int, int, int): assign this view's bounds inside
     *  its parent and report whether they moved. AOSP marks it protected (and
     *  @UnsupportedAppUsage, so it is not in core/api/current.txt), but real
     *  widget subclasses -- in AOSP itself and in this tree's android.widget --
     *  override it, and an undeclared base method means those overrides are
     *  never reached. AOSP additionally invalidates, updates the RenderNode and
     *  fires sizeChange from here; in this tree layout() still owns the
     *  size-changed dispatch (macapkDispatchSizeChanged), so this does the
     *  assignment only and does not double-fire it. */
    protected boolean setFrame(int left, int top, int right, int bottom) {
        final boolean changed = this.left != left || this.top != top
                || this.right != right || this.bottom != bottom;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        return changed;
    }

    /** AOSP View.debug(int depth): the protected hierarchy dump ViewDebug
     *  drives, which ViewGroup overrides to recurse into its children. Nothing
     *  in this tree consumes the output (there is no view server -- see
     *  ViewDebug.dumpCapturedView), so the leaf body is empty; it exists so a
     *  subclass override, and its super-call, resolve. */
    protected void debug(int depth) { }
}
