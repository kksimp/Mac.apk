// android.view.LayoutInflater -- inflates layout XML into View
// hierarchies.
//
// Until v1.0.69 this was an abstract stub. Bouncy, F-Droid, Sgt
// Puzzles, ShatteredPD (settings activity), and practically every
// non-game Android app depend on setContentView(R.layout.X) followed
// by findViewById(R.id.Y) returning the inflated child. Without
// inflation, every layout-XML-using activity hits NullPointerException
// on the first findViewById call.
//
// This impl is intentionally minimal but FUNCTIONAL:
//   - parse the XML with DocumentBuilder (simple, no per-tag pull-
//     parser state machine to manage)
//   - for each element, instantiate the View class by name (custom
//     fully-qualified, or android.widget.* / android.view.*)
//   - apply a small subset of attributes that actually drive
//     observable behavior (id, layout_width/height, visibility,
//     background, src, text, textColor, textSize, onClick, etc.)
//   - addView to the current parent and recurse over children
//
// Layout (measure/positioning) is NOT implemented -- Views still get
// their default 0x0 bounds, then the per-frame ViewCanvasDriver
// pumps draw() against the canvas extent. This is enough for
// findViewById to return non-null, which is what unblocks
// onCreate. Visual layout polish (centering, weights, etc.) comes
// in a later pass.
package android.view;

import android.content.Context;
import com.macapk.loader.NativeLog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class LayoutInflater {
    private static final String TAG = "Mac.apk.LayoutInflater";

    private final Context context;

    /** The Context that the CURRENT element (and its descendants) is being
     *  inflated with. Equal to `context` except inside a subtree whose element
     *  carried `android:theme`, where it is a ContextThemeWrapper -- AOSP's
     *  createViewFromTag reassigns its local `context` exactly this way and the
     *  wrapper then propagates to children through the recursion.
     *
     *  Only ever reassigned under MACAPK_VIEW_THEME (default OFF), so with the
     *  flag off this is `context` for the whole inflation and every read below
     *  is byte-identical to the field it replaced. */
    private Context inflateContext;

    /** Set by setFactory / setFactory2 -- the activity hook for custom
     *  view inflation. AppCompat-style apps install one to map raw
     *  android.widget names to androidx.appcompat overrides. */
    private Factory  factory;
    private Factory2 factory2;
    /** AOSP's LayoutInflater.mPrivateFactory -- consulted AFTER the public
     *  factory/factory2 and BEFORE default class instantiation. Real Android
     *  installs the Activity here (Activity is a Factory2), which is how
     *  androidx FragmentActivity gets to turn a <fragment>/FragmentContainerView
     *  tag into a real Fragment (Activity.onCreateView -> FragmentController ->
     *  the FragmentManager's factory). Tier 3 M0 installs our
     *  FragmentInflaterFactory here (see from(), gated on MACAPK_REAL_LOOPER). */
    private Factory2 privateFactory;

    /** AOSP LayoutInflater.mFactorySet (android14 :110): true once setFactory /
     *  setFactory2 has been called on THIS instance. Deliberately NOT copied by
     *  the clone constructor or cloneInContext (AOSP :273-280 copies mFactory /
     *  mFactory2 / mPrivateFactory and the filter, never this flag) -- that is
     *  what lets a cloned inflater take exactly one factory of its own on top of
     *  the ones it inherited, which is how androidx Fragment layers its
     *  FragmentLayoutInflaterFactory over AppCompat's. Named as AOSP names it
     *  (@UnsupportedAppUsage) so reflection that resets it finds it. Only read
     *  under MACAPK_FACTORY_MERGE. */
    private boolean mFactorySet;

    /** BISECT GATES (2026-09-26, default ON; lane C, cluster C of
     *  docs/widget-fidelity-cluster_2026-09-25.md; gate debug/factorymerge_gate.sh).
     *
     *  MACAPK_FACTORY_MERGE=0 -- setFactory / setFactory2 / setPrivateFactory
     *  REPLACE again (the pre-change code, byte for byte: no FactoryMerger, no
     *  mFactorySet, a null factory accepted). With it ON they merge the AOSP way,
     *  the NEW factory consulted first and the inherited one second.
     *  What the replace cost, measured: androidx Fragment.onGetLayoutInflater
     *  clones the activity's inflater and calls setFactory2(childFragmentManager
     *  .mLayoutInflaterFactory) on the clone, so replacing threw AppCompat's
     *  delegate away inside EVERY fragment; <Button> then fell through to the
     *  APPCOMPAT_REWRITE table (AppCompatButton) and a Material theme's
     *  viewInflaterClass (MaterialComponentsViewInflater -> MaterialButton) never
     *  ran. Aegis fragment_welcome_slide btnImport: AppCompatButton ->
     *  MaterialButton.
     *
     *  MACAPK_FACTORY_SET_ONCE=0 -- a second setFactory / setFactory2 on the SAME
     *  instance merges again (the new one in front) instead of throwing
     *  IllegalStateException the way AOSP does (android14 :353 / :372). Only
     *  meaningful with MERGE on. The throw is kept ON by measurement: over the
     *  62-APK corpus (debug/factorymerge_verify/factory_census.py) every one of
     *  the 85 call sites that is not a LayoutInflaterCompat-style wrapper either
     *  reads getFactory()/getFactory2() first and sets only on null (43:
     *  AppCompatDelegateImpl.installViewFactory, Fossify
     *  installFontInflaterFactory, Duolingo's inflater), sets on a fresh
     *  cloneInContext() (39: androidx Fragment, directly or through
     *  FragmentHostCallback.onGetLayoutInflater), sets in a constructor on an
     *  inflater it just built (1: AsyncLayoutInflater), or was read by hand (2:
     *  Aurora's R8-renamed Fragment.onGetLayoutInflater, whose host override
     *  clones; Duolingo's LayoutInflater subclass, whose setFactory2 override
     *  calls super once per branch). None sets twice on one instance, so the
     *  throw can only fire on a path that also throws on a device. This gate is
     *  the lever if a live run proves otherwise. */
    static final boolean MACAPK_FACTORY_MERGE =
        !"0".equals(System.getenv("MACAPK_FACTORY_MERGE"));
    static final boolean MACAPK_FACTORY_SET_ONCE =
        !"0".equals(System.getenv("MACAPK_FACTORY_SET_ONCE"));
    /** BISECT GATE (2026-09-26, default ON; gate debug/factorymerge_gate.sh):
     *  cloneInContext goes through the copy constructor, as AOSP's
     *  PhoneLayoutInflater does, so the clone keeps the Filter as well as the
     *  factories. =0 restores the pre-change hand copy of the three factories
     *  (no Filter). See cloneInContext. */
    static final boolean MACAPK_INFLATER_CLONE_FILTER =
        !"0".equals(System.getenv("MACAPK_INFLATER_CLONE_FILTER"));

    /** BISECT GATE (2026-09-26, default ON; dialog lane, gate
     *  debug/dialogtheme_gate.sh 'inflater' arm,
     *  docs/dialog-window-theme_2026-09-26.md). AOSP android14
     *  LayoutInflater.java:286-293 asks the Context:
     *
     *      LayoutInflater LayoutInflater =
     *              (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
     *      if (LayoutInflater == null) {
     *          throw new AssertionError("LayoutInflater not found.");
     *      }
     *      return LayoutInflater;
     *
     *  and ContextThemeWrapper.java:181-189 answers that service with
     *  `LayoutInflater.from(getBaseContext()).cloneInContext(this)`, cached in
     *  mInflater: a wrapper's inflater is its base's inflater cloned in,
     *  FACTORIES KEPT. Ours never asks the Context; it keys a fresh,
     *  factory-less inflater per Context in macapkPerContext. A wrapper that
     *  models the AOSP answer (ContextThemeWrapper.macapkOwnInflater(); today
     *  only android.app.Dialog's dialog context) now answers for itself.
     *  MEASURED (2026-09-26): once Dialog wraps its context in ?android:dialogTheme,
     *  LayoutInflater.from(dialog.getContext()) had no Factory2, so Unit
     *  Converter's own AppCompatDialog, pre-show setContentView(int)
     *  (AppCompatDelegateImpl inflates with LayoutInflater.from(mContext)),
     *  consulted the activity's Factory2 0 times instead of 3: no AppCompat /
     *  Material tag substitution for dialog content inflated before show().
     *  =0 restores the per-Context cache for every Context. */
    static final boolean MACAPK_INFLATER_FROM_OWN =
        !"0".equals(System.getenv("MACAPK_INFLATER_FROM_OWN"));

    /** BISECT GATE (2026-09-26, default ON; lane platlayouts, gate
     *  debug/platlayout_gate.sh, docs/platform-layouts-axml_2026-09-26.md).
     *
     *  A FRAMEWORK layout id (0x01......: android.R.layout.simple_list_item_1,
     *  simple_spinner_item, select_dialog_*_material, preference_material ...)
     *  is read the AOSP way, `res.getLayout(resource)` (LayoutInflater.inflate
     *  (int, ViewGroup, boolean), aosp main :460), which Resources now answers
     *  with a com.macapk.loader.axml parser over the COMPILED entry of
     *  framework-res.apk; the parser's document becomes the DOM this inflater
     *  walks. The same holds for `<include layout="@android:layout/x">`.
     *  If getLayout serves a framework id through the numeric DOM parser
     *  instead (the axml path could not open the file), the id stays a miss
     *  and gets the placeholder, as before, rather than a tree whose ids the
     *  inflater could only hash.
     *
     *  Also under this gate: the getLayout FALLBACK for an APP layout that has
     *  no file (macapkDocumentFromLayout, the Instagram path) renders its
     *  parser through the same bridge (macapkDocumentFromParser) rather than
     *  its own copy, which set a res-auto attribute without its "app:" prefix.
     *
     *  Before: openRawResource served the compiled AXML bytes, the text
     *  DocumentBuilder failed "Content is not allowed in prolog" and the catch
     *  returned an EMPTY FrameLayout -- bobball's spinners were blank (1,662
     *  stack traces in one run) and ArrayAdapter set its text on a detached
     *  TextView. =0 restores all of that exactly. */
    static final boolean MACAPK_PLATFORM_LAYOUTS =
        !"0".equals(System.getenv("MACAPK_PLATFORM_LAYOUTS"));
    /** BISECT GATE (2026-09-26, default ON; same lane and gate script).
     *  inflate(XmlPullParser, ViewGroup, boolean) inflates the parser's
     *  document the way AOSP's does (aosp main :509-609: advanceToRootNode,
     *  then the root and its subtree). It used to be a stub that returned
     *  `root` without reading the parser at all, so an app that inflates
     *  res.getLayout(id) / getXml(id) itself got its own container (or null)
     *  back. Like AOSP it returns ROOT when attaching (root != null &&
     *  attachToRoot) and the inflated view otherwise. =0 restores the stub. */
    static final boolean MACAPK_INFLATE_PARSER =
        !"0".equals(System.getenv("MACAPK_INFLATE_PARSER"));

    /** AOSP LayoutInflater.FactoryMerger, verbatim in behaviour (android14
     *  :219-248): f1/f12 is the factory being set NOW and is asked first; f2/f22
     *  is the one the inflater already had. The 3-arg onCreateView calls only
     *  the Factory halves; the 4-arg one prefers each side's Factory2 and falls
     *  back to its Factory when that side has none. */
    private static class FactoryMerger implements Factory2 {
        private final Factory mF1, mF2;
        private final Factory2 mF12, mF22;

        FactoryMerger(Factory f1, Factory2 f12, Factory f2, Factory2 f22) {
            mF1 = f1;
            mF2 = f2;
            mF12 = f12;
            mF22 = f22;
        }

        public View onCreateView(String name, Context context, android.util.AttributeSet attrs) {
            View v = mF1.onCreateView(name, context, attrs);
            if (v != null) return v;
            return mF2.onCreateView(name, context, attrs);
        }

        public View onCreateView(View parent, String name, Context context,
                android.util.AttributeSet attrs) {
            View v = mF12 != null ? mF12.onCreateView(parent, name, context, attrs)
                    : mF1.onCreateView(name, context, attrs);
            if (v != null) return v;
            return mF22 != null ? mF22.onCreateView(parent, name, context, attrs)
                    : mF2.onCreateView(name, context, attrs);
        }
    }

    protected LayoutInflater(Context c) { this.context = c; this.inflateContext = c; }

    /** AOSP LayoutInflater(LayoutInflater original, Context newContext), the
     *  protected copy constructor cloneInContext() is built on: same factories,
     *  new Context. Declared here so a LayoutInflater subclass (AppCompat ships
     *  one) can chain to it, which is the only reason it is public API.
     *  mFactorySet is NOT copied (AOSP android14 :273-280), so the new instance
     *  may take one factory of its own, merged in front of the inherited ones. */
    protected LayoutInflater(LayoutInflater original, Context newContext) {
        this.context = newContext;
        this.inflateContext = newContext;
        if (original != null) {
            this.factory = original.factory;
            this.factory2 = original.factory2;
            this.privateFactory = original.privateFactory;
            this.mFilter = original.mFilter;
        }
    }

    /** AOSP LayoutInflater.Filter: vetoes a class by name before the inflater
     *  instantiates it. AOSP consults it in createView() and throws
     *  InflateException("Class not allowed to be inflated") when it returns
     *  false. CORRECTED 2026-09-26: this used to say that every creation path
     *  here calls macapkFilterAllows. None does (grep: its only occurrence is
     *  its own definition), so the Filter is stored, returned by getFilter()
     *  and carried by cloneInContext, but never CONSULTED: a vetoed class still
     *  inflates. Enforcing it is a separate, unlanded change. */
    public interface Filter {
        boolean onLoadClass(Class clazz);
    }
    private Filter mFilter;
    /** AOSP LayoutInflater.setFilter(Filter) also clears the per-inflater
     *  allow/deny cache; this inflater keeps no such cache, so the assignment
     *  is the whole of it. */
    public void setFilter(Filter filter) { mFilter = filter; }
    public Filter getFilter() { return mFilter; }
    /** @hide -- AOSP's `verifyClassLoader`/filter gate, factored out so every
     *  view-creation path in this file can apply it identically. */
    protected final boolean macapkFilterAllows(Class<?> clazz) {
        return mFilter == null || clazz == null || mFilter.onLoadClass(clazz);
    }

    /** AOSP-compatible single-arg factory. Returns a real inflater
     *  instead of the previous null stub. The Context is required for
     *  Resources lookup + View constructor invocation. */
    /** ONE inflater per Context, per AOSP. `LAYOUT_INFLATER_SERVICE` is
     *  registered with a `CachedServiceFetcher`, which stores the instance in
     *  `ContextImpl.mServiceCache` -- so `LayoutInflater.from(ctx)` returns the
     *  SAME object every time for a given Context.
     *
     *  That identity is load-bearing, not an optimisation: AppCompat's
     *  `AppCompatDelegateImpl.installViewFactory()` installs its Factory2 on
     *  `LayoutInflater.from(mContext)`, and `setContentView` later inflates
     *  through what it expects to be that same instance. The factory is what
     *  maps `<Button>` -> MaterialButton, `<EditText>` -> AppCompatEditText,
     *  `<RadioButton>` -> AppCompatRadioButton (which supplies the radio
     *  circles), per the theme's `viewInflaterClass`.
     *
     *  We returned a NEW inflater on every call, so the factory was installed
     *  on a throwaway and every real inflation ran factory-less -- which is why
     *  apps rendered our plain widgets with no Material chrome at all
     *  (OpenSudoku's buttons bare text, UnitConverter's EditText with no
     *  underline and RadioButtons with no circles, NewPipe's dialog buttons
     *  unstyled). Keyed weakly so a finished Activity's inflater is collectable.
     *  See docs/canvas-ab-analyst-report-b_2026-08-02.md item 1. */
    private static final java.util.Map<Context, LayoutInflater> macapkPerContext =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** v1.0.1643: the "keyed weakly" above never freed anything -- the VALUE (the
     *  inflater) references its key (inflater.context), so a WeakHashMap entry
     *  keeps its own key alive (debug/hprof_retainers.sh: every destroyed
     *  Wikipedia PageActivity was held by `static macapkPerContext -> WeakHashMap
     *  -> LayoutInflater.context`). A destroyed activity's entries (and those of
     *  wrappers around it) are dropped explicitly; AOSP caches the inflater IN
     *  the context (ContextThemeWrapper.mInflater), so it dies with it. */
    public static void macapkForgetContext(Context c) {
        if (c == null) return;
        synchronized (macapkPerContext) {
            java.util.Iterator<Context> it = macapkPerContext.keySet().iterator();
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

    public static LayoutInflater from(Context context) {
        // AOSP NEVER hands back null here: LayoutInflater.from() either returns
        // the context's inflater or throws AssertionError("LayoutInflater not
        // found."), and Window.getLayoutInflater() / Activity.getLayoutInflater()
        // are both declared @NonNull on top of it. Returning null was our own
        // deviation, and it does not stay local: the caller stores the inflater
        // in a field or local and dereferences it much later, so the failure
        // surfaces as an unattributable NPE deep inside app code.
        //
        // MEASURED on Instagram 445: its Activity.onCreate died with
        //   NullPointerException: Cannot invoke
        //   "android.view.LayoutInflater.getContext()" because "<local13>" is null
        // which aborted onCreate, left the FragmentManager unattached ("has not
        // been attached to a host" from onResume) and produced a window that
        // stayed black. Falling back to the process's own Application context
        // keeps the AOSP contract (non-null) and costs nothing when the caller
        // passed a real context, which is every ordinary call.
        if (context == null) {
            Context fallback = null;
            try {
                android.app.Activity top = com.macapk.loader.ActivityHost.macapkTopActivity();
                fallback = (top != null) ? top : android.app.Application.macapk_get();
            } catch (Throwable ignored) { /* fall through to the null return */ }
            if (fallback == null) return null;   // nothing exists yet: pre-Boot
            NativeLog.w(TAG, "from(null Context) -- AOSP never returns null here;"
                    + " using the " + (fallback instanceof android.app.Activity
                        ? "current Activity" : "Application") + " context instead");
            context = fallback;
        }
        if (MACAPK_INFLATER_FROM_OWN && context instanceof ContextThemeWrapper) {
            LayoutInflater own = ((ContextThemeWrapper) context).macapkOwnInflater();
            if (own != null) return own;
        }
        LayoutInflater cached = macapkPerContext.get(context);
        if (cached != null) return cached;
        LayoutInflater li = null;
        // AOSP ContextThemeWrapper.getSystemService(LAYOUT_INFLATER_SERVICE)
        // (android14-release ContextThemeWrapper.java:181-189) builds the wrapper's
        // inflater as `LayoutInflater.from(getBaseContext()).cloneInContext(this)`,
        // so `new ContextThemeWrapper(activity, style)` inflates through the
        // ACTIVITY's Factory2 (AppCompat's, MDC's) rebound to the wrapper's theme.
        // A bare `new LayoutInflater(wrapper)` had no factory at all: inflating a
        // plain Button/TextView instead of MaterialButton/AppCompatTextView.
        // Activity itself is excluded: AppCompat installs its Factory2 on
        // from(activity) after the fact, so a fresh instance is the right start
        // (and the base's inflater is not where that factory lives). Gated with
        // ContextThemeWrapper.getSystemService (MACAPK_CTW_INFLATER_SERVICE=0 =
        // the old fresh factory-less instance).
        if (ContextThemeWrapper.MACAPK_CTW_INFLATER_SERVICE
                && context instanceof ContextThemeWrapper
                && !(context instanceof android.app.Activity)) {
            Context base = ((ContextThemeWrapper) context).getBaseContext();
            if (base != null && base != context) {
                li = from(base).cloneInContext(context);
            }
        }
        if (li == null) li = new LayoutInflater(context);
        // Tier 3 M0 (MACAPK_REAL_LOOPER): install the fragment-from-layout
        // private factory so a <fragment>/FragmentContainerView in ANY inflated
        // layout (setContentView, fragment onCreateView, adapter binders) becomes
        // a real Fragment on the app's FragmentManager -- the AOSP private-factory
        // mechanism. Stateless singleton (resolves the host FM from the current
        // Activity at call time), so no per-context caching is required and it is
        // a no-op for every non-fragment tag. Flag-OFF: byte-for-byte unchanged.
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            li.privateFactory = com.macapk.loader.FragmentInflaterFactory.INSTANCE;
        }
        macapkPerContext.put(context, li);
        return li;
    }

    public Context getContext() { return context; }
    /** The clone carries the factories but starts with mFactorySet false (a new
     *  instance), exactly as AOSP's copy constructor leaves it, so the caller may
     *  set one factory of its own and have it MERGED in front of these.
     *
     *  AOSP's cloneInContext is abstract; PhoneLayoutInflater's is
     *  `return new PhoneLayoutInflater(this, newContext)`, i.e. the copy
     *  constructor (android14 LayoutInflater.java:273-280), which also carries
     *  the Filter (`setFilter(original.mFilter)`, :279). Ours built a fresh
     *  instance and copied the three factories by hand, so a fragment or ViewStub
     *  clone dropped the parent's Filter (getFilter() == null on the clone).
     *  MACAPK_INFLATER_CLONE_FILTER=0 restores that hand copy. */
    public LayoutInflater cloneInContext(Context newContext) {
        if (MACAPK_INFLATER_CLONE_FILTER) return new LayoutInflater(this, newContext);
        LayoutInflater clone = new LayoutInflater(newContext);
        clone.factory        = this.factory;
        clone.factory2       = this.factory2;
        clone.privateFactory = this.privateFactory;
        return clone;
    }
    public Factory  getFactory()  { return factory; }
    public Factory2 getFactory2() { return factory2; }

    /** AOSP android14 LayoutInflater.setFactory (:352-365):
     *      if (mFactorySet) throw new IllegalStateException("A factory has already been set on this LayoutInflater");
     *      if (factory == null) throw new NullPointerException("Given factory can not be null");
     *      mFactorySet = true;
     *      if (mFactory == null) { mFactory = factory; }
     *      else { mFactory = new FactoryMerger(factory, null, mFactory, mFactory2); }
     *  mFactory2 is left alone, so on a clone that inherited a Factory2 the new
     *  plain Factory is never consulted by tryCreateView (which asks mFactory2
     *  first). That is AOSP's behaviour too, and it is mirrored rather than fixed.
     *  MACAPK_FACTORY_MERGE=0 restores the pre-change replace. */
    public void setFactory(Factory f) {
        if (!MACAPK_FACTORY_MERGE) { this.factory = f; return; }
        if (mFactorySet && MACAPK_FACTORY_SET_ONCE) {
            throw new IllegalStateException("A factory has already been set on this LayoutInflater");
        }
        if (f == null) {
            throw new NullPointerException("Given factory can not be null");
        }
        mFactorySet = true;
        if (factory == null) {
            factory = f;
        } else {
            factory = new FactoryMerger(f, null, factory, factory2);
        }
    }

    /** AOSP android14 LayoutInflater.setFactory2 (:371-384):
     *      if (mFactorySet) throw new IllegalStateException(...);
     *      if (factory == null) throw new NullPointerException(...);
     *      mFactorySet = true;
     *      if (mFactory == null) { mFactory = mFactory2 = factory; }
     *      else { mFactory = mFactory2 = new FactoryMerger(factory, factory, mFactory, mFactory2); }
     *  so a Fragment's factory set on a clone of the activity's inflater is asked
     *  FIRST and AppCompat's delegate still answers every tag it returns null for.
     *  MACAPK_FACTORY_MERGE=0 restores the pre-change replace (factory2 = f, and
     *  factory = f only when it was null). */
    public void setFactory2(Factory2 f) {
        if (!MACAPK_FACTORY_MERGE) {
            this.factory2 = f;
            if (this.factory == null) this.factory = f;
            return;
        }
        if (mFactorySet && MACAPK_FACTORY_SET_ONCE) {
            throw new IllegalStateException("A factory has already been set on this LayoutInflater");
        }
        if (f == null) {
            throw new NullPointerException("Given factory can not be null");
        }
        mFactorySet = true;
        if (factory == null) {
            factory = factory2 = f;
        } else {
            factory = factory2 = new FactoryMerger(f, f, factory, factory2);
        }
    }
    public Factory2 getPrivateFactory() { return privateFactory; }

    /** AOSP android14 LayoutInflater.setPrivateFactory (:390-396) merges too, with
     *  no set-once rule and no null check:
     *      if (mPrivateFactory == null) { mPrivateFactory = factory; }
     *      else { mPrivateFactory = new FactoryMerger(factory, factory, mPrivateFactory, mPrivateFactory); }
     *  from() installs our FragmentInflaterFactory by a field write, not through
     *  here, so a later framework/app call layers in front of it instead of
     *  evicting it. Rides MACAPK_FACTORY_MERGE (=0: replace).
     *
     *  ONE deliberate deviation, in timing only. setPrivateFactory(null) over an
     *  existing private factory builds FactoryMerger(null, null, prev, prev) on a
     *  device, whose 4-arg onCreateView dereferences mF1 (null) the first time it
     *  is consulted, i.e. at the next tag the public factories decline (most
     *  layout roots), so AOSP throws NullPointerException at the next inflate.
     *  Our tryFactoryCreate catches Throwable per tag, so the same merger here
     *  would NOT crash: it would log once per tag and silently stop consulting
     *  the previous private factory (our FragmentInflaterFactory), which breaks
     *  <fragment> / FragmentContainerView with no visible cause. So the NPE is
     *  thrown HERE, at the call that built the broken merger, with the same
     *  message the public setters use. With no previous private factory AOSP
     *  simply stores the null, and so do we. The API is @hide; the corpus census
     *  found no caller. */
    public void setPrivateFactory(Factory2 f) {
        if (!MACAPK_FACTORY_MERGE) { this.privateFactory = f; return; }
        if (f == null && privateFactory != null) {
            throw new NullPointerException("Given factory can not be null");
        }
        if (privateFactory == null) {
            privateFactory = f;
        } else {
            privateFactory = new FactoryMerger(f, f, privateFactory, privateFactory);
        }
    }

    public View inflate(int resource, ViewGroup root) {
        return inflate(resource, root, root != null);
    }

    /** The android: attribute namespace, as every compiled layout spells it. */
    private static final String MACAPK_ANDROID_NS =
            "http://schemas.android.com/apk/res/android";

    /**
     * Read a layout through AOSP's {@code Resources.getLayout(int)} and render the
     * resulting pull-parser into the DOM this inflater consumes.
     *
     * Only used when {@code openRawResource} found no file for the id -- see the
     * call site for why that happens (an app that serves its layouts from its own
     * packed store via a getLayout override). Returns null on any failure so the
     * caller falls back to its empty-FrameLayout placeholder rather than throwing
     * out of a widget constructor.
     */
    private static Document macapkDocumentFromLayout(
            android.content.res.Resources r, int id) {
        org.xmlpull.v1.XmlPullParser p;
        try {
            p = r.getLayout(id);
        } catch (Throwable t) {
            return null;   // the id genuinely has no layout behind it
        }
        if (p == null) return null;
        try {
            // One parser-to-DOM bridge for every pull-parser source (this, the
            // platform layouts, inflate(XmlPullParser)): macapkDocumentFromParser
            // from the parser's CURRENT event, as this loop used to walk it. It
            // also gives a non-android namespaced attribute its prefix ("app:"
            // for res-auto) as a text-parsed layout has it; the old copy set it
            // unprefixed. MACAPK_PLATFORM_LAYOUTS=0 runs the old copy
            // (macapkDocumentFromLayoutLegacy), unchanged.
            Document doc = MACAPK_PLATFORM_LAYOUTS
                ? macapkDocumentFromParser(p, false) : macapkDocumentFromLayoutLegacy(p);
            if (doc == null || doc.getDocumentElement() == null) return null;
            NativeLog.i(TAG, "inflate(0x" + Integer.toHexString(id)
                    + "): served by Resources.getLayout() (the app packs this layout"
                    + " itself; openRawResource has no file for it)");
            return doc;
        } catch (Throwable t) {
            NativeLog.w(TAG, "inflate(0x" + Integer.toHexString(id)
                    + "): getLayout() parser could not be rendered to a document: " + t);
            return null;
        } finally {
            if (p instanceof android.content.res.XmlResourceParser) {
                try { ((android.content.res.XmlResourceParser) p).close(); }
                catch (Throwable ignore) {}
            }
        }
    }

    /** macapkDocumentFromLayout's own bridge before 2026-09-26, verbatim,
     *  kept only for MACAPK_PLATFORM_LAYOUTS=0 (the exact pre-change path). */
    private static Document macapkDocumentFromLayoutLegacy(org.xmlpull.v1.XmlPullParser p)
            throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().newDocument();
        org.w3c.dom.Node cur = doc;
        for (int ev = p.getEventType();
             ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
             ev = p.next()) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                Element e = doc.createElement(p.getName());
                for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
                    String ns   = p.getAttributeNamespace(i);
                    String name = p.getAttributeName(i);
                    String val  = p.getAttributeValue(i);
                    if (name == null) continue;
                    try {
                        if (ns != null && !ns.isEmpty()) {
                            // Keep the prefix the DOM path expects; inflateElement
                            // reads android:* by namespace, and an unprefixed
                            // qualified name would drop it into the default ns.
                            String qn = MACAPK_ANDROID_NS.equals(ns)
                                    ? "android:" + name : name;
                            e.setAttributeNS(ns, qn, val == null ? "" : val);
                        } else {
                            e.setAttribute(name, val == null ? "" : val);
                        }
                    } catch (Throwable ignore) { /* skip one bad attribute */ }
                }
                cur.appendChild(e);
                cur = e;
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                org.w3c.dom.Node parent = cur.getParentNode();
                if (parent != null) cur = parent;
            } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT) {
                String txt = p.getText();
                if (txt != null && cur != doc) cur.appendChild(doc.createTextNode(txt));
            }
        }
        return doc;
    }

    /** Framework layout ids already announced once in the log (the path is
     *  taken per ListView row, so a per-call INFO line would be noise). */
    private static final java.util.Set<Integer> sMacapkPlatformLogged =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One namespace-aware DocumentBuilder per thread: building the factory
     *  and builder per call was the largest single cost of a styled TextView
     *  construction (DomXmlResourceParser measured it at 22%), and this path
     *  runs once per adapter row. DocumentBuilder is not thread-safe. */
    private static final ThreadLocal<DocumentBuilder> sMacapkDomBuilder =
        ThreadLocal.withInitial(() -> {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                return f.newDocumentBuilder();
            } catch (javax.xml.parsers.ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
        });

    /** The DOM of framework layout `id`, read through AOSP's
     *  `res.getLayout(id)` (see MACAPK_PLATFORM_LAYOUTS), or null when the id
     *  has no layout behind it. */
    private static Document macapkPlatformDocument(android.content.res.Resources r, int id) {
        org.xmlpull.v1.XmlPullParser p;
        try {
            p = r.getLayout(id);
        } catch (Throwable t) {
            return null;   // NotFoundException: no file behind the id
        }
        if (p == null) return null;
        try {
            // Resources.getLayout falls back to getXml's DOM parser when the
            // axml path cannot open the file. That DOM spells every reference
            // numerically ("@0x01020014"), which parseResourceId can only HASH
            // (android.R.id.text1 came out #0x3ce2c0ae), so a tree built from it
            // is worse than the pre-change placeholder: treat it as a miss. Our
            // XmlBlock presents an app's own compiled bytes through the same
            // class and the same numeric spelling, so the test is the class.
            if (p instanceof com.macapk.loader.DomXmlResourceParser) {
                NativeLog.w(TAG, "inflate(0x" + Integer.toHexString(id)
                    + "): getLayout served the numeric DOM parser, not the platform"
                    + " parser -- treated as no platform layout");
                return null;
            }
            Document doc = macapkDocumentFromParser(p, false);
            if (doc != null && sMacapkPlatformLogged.add(id)) {
                NativeLog.i(TAG, "inflate(0x" + Integer.toHexString(id)
                    + "): platform layout via Resources.getLayout (" + p.getClass().getSimpleName() + ")");
            }
            return doc;
        } catch (Throwable t) {
            NativeLog.w(TAG, "inflate(0x" + Integer.toHexString(id)
                + "): platform layout could not be read: " + t);
            return null;
        } finally {
            if (p instanceof android.content.res.XmlResourceParser) {
                try { ((android.content.res.XmlResourceParser) p).close(); }
                catch (Throwable ignore) {}
            }
        }
    }

    /** The framework layout id an `<include layout="...">` names, or 0 when it
     *  names an app layout (the caller's existing path). "@android:layout/x"
     *  is the decoded spelling of a compiled 0x0109.... reference;
     *  parseResourceId would hash it, because it looks names up in the APP's
     *  package. */
    private int macapkPlatformLayoutId(String ref) {
        if (ref == null) return 0;
        ref = ref.trim();
        if (ref.startsWith("@0x01") && ref.length() == 11) {
            try { return (int) Long.parseLong(ref.substring(3), 16); }
            catch (NumberFormatException ignored) { return 0; }
        }
        if (!ref.startsWith("@android:layout/") && !ref.startsWith("@*android:layout/")) return 0;
        String name = ref.substring(ref.indexOf('/') + 1);
        try {
            if (context == null || context.getResources() == null) return 0;
            int id = context.getResources().getIdentifier(name, "layout", "android");
            return (id >>> 24) == 0x01 ? id : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Render a pull parser's element into the DOM this inflater walks: the
     *  first START_TAG (reached by next() FIRST when `advanceFirst`, which is
     *  AOSP's advanceToRootNode, else from the current event) and its whole
     *  subtree, leaving the parser on that element's END_TAG as rInflate does.
     *  Attribute values are taken from getAttributeValue(i), which for our
     *  compiled-XML parser is the decoded-source spelling this inflater reads
     *  (com.macapk.loader.axml.AxmlParser's header says why). Returns null
     *  when the parser holds no start tag. */
    private static Document macapkDocumentFromParser(org.xmlpull.v1.XmlPullParser p,
                                                     boolean advanceFirst) throws Exception {
        final int START_TAG = org.xmlpull.v1.XmlPullParser.START_TAG;
        final int END_TAG = org.xmlpull.v1.XmlPullParser.END_TAG;
        final int END_DOCUMENT = org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
        int type = advanceFirst ? p.next() : p.getEventType();
        while (type != START_TAG && type != END_DOCUMENT) type = p.next();
        if (type != START_TAG) return null;
        DocumentBuilder db = sMacapkDomBuilder.get();
        db.reset();
        Document doc = db.newDocument();
        org.w3c.dom.Node cur = macapkElementFromParser(doc, p);
        doc.appendChild(cur);
        int depth = 1;
        while (depth > 0) {
            type = p.next();
            if (type == START_TAG) {
                Element e = macapkElementFromParser(doc, p);
                cur.appendChild(e);
                cur = e;
                depth++;
            } else if (type == END_TAG) {
                depth--;
                if (depth > 0) cur = cur.getParentNode();
            } else if (type == org.xmlpull.v1.XmlPullParser.TEXT) {
                String txt = p.getText();
                if (txt != null) cur.appendChild(doc.createTextNode(txt));
            } else if (type == END_DOCUMENT) {
                break;
            }
        }
        return doc;
    }

    /** One START_TAG as a DOM element: android: attributes under the
     *  "android:" prefix every lookup in this file uses, other namespaced ones
     *  under the source's prefix when the parser knows it ("app" for res-auto
     *  otherwise), un-namespaced ones bare. */
    private static Element macapkElementFromParser(Document doc, org.xmlpull.v1.XmlPullParser p) {
        Element e = doc.createElement(p.getName());
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            try {
                String ns   = p.getAttributeNamespace(i);
                String name = p.getAttributeName(i);
                String val  = p.getAttributeValue(i);
                if (name == null || name.isEmpty()) continue;
                if (val == null) val = "";
                if (ns == null || ns.isEmpty()) {
                    e.setAttribute(name, val);
                    continue;
                }
                String qn;
                if (MACAPK_ANDROID_NS.equals(ns)) {
                    qn = "android:" + name;
                } else {
                    String pfx = null;
                    try { pfx = p.getAttributePrefix(i); } catch (Throwable noPrefix) { /* compiled XML */ }
                    if (pfx == null || pfx.isEmpty()) {
                        pfx = "http://schemas.android.com/apk/res-auto".equals(ns) ? "app" : null;
                    }
                    qn = pfx != null ? pfx + ":" + name : name;
                }
                e.setAttributeNS(ns, qn, val);
            } catch (Throwable ignore) { /* skip one bad attribute, as the DOM path does */ }
        }
        return e;
    }

    public View inflate(int resource, ViewGroup root, boolean attachToRoot) {
        InputStream xmlStream = null;
        try {
            android.content.res.Resources r = (context != null) ? context.getResources() : null;
            if (r == null) {
                NativeLog.w(TAG, "inflate(0x" + Integer.toHexString(resource)
                                  + "): no Resources -- returning empty FrameLayout");
                return new android.widget.FrameLayout(context);
            }
            Document doc = null;
            if (MACAPK_PLATFORM_LAYOUTS && (resource >>> 24) == 0x01) {
                // A framework layout: AOSP's res.getLayout(resource), see the gate.
                doc = macapkPlatformDocument(r, resource);
                if (doc == null) {
                    NativeLog.w(TAG, "inflate(0x" + Integer.toHexString(resource)
                                      + "): no platform layout -- returning empty FrameLayout");
                    return new android.widget.FrameLayout(context);
                }
                return macapkInflateDocument(doc, root, attachToRoot, resource);
            }
            try {
                xmlStream = r.openRawResource(resource);
            } catch (android.content.res.Resources.NotFoundException nf) {
                xmlStream = null;   // -> the getLayout() path below
            }
            if (xmlStream != null) {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                doc = db.parse(xmlStream);
                macapkDropAttrIdRecords(doc.getDocumentElement());
            } else {
                // AOSP's inflate(int,...) reads the layout through
                // `res.getLayout(resource)`, NOT openRawResource. That matters for
                // an app that OVERRIDES getLayout to serve layouts it stores its
                // own way: Instagram 445 packs 5,947 of its 6,017 layouts into a
                // blob and rewrites each table entry from "res/xx.xml" to a private
                // token ("L|47C1C0|8B8|9047"), then decodes it in its Resources
                // subclass's getLayout() (which is also why it reflects
                // android.content.res.XmlBlock to turn the decoded bytes into a
                // parser). openRawResource legitimately MISSES such an id -- there
                // is no file behind it -- and setContentView then died with
                // Resources$NotFoundException, so InstagramMainActivity finished
                // itself and the app exited.
                //
                // We try openRawResource FIRST and fall back here, rather than
                // taking AOSP's order, deliberately: getLayout() resolves through
                // ResourceIndex.openXml, a DIFFERENT path from openRawResource, so
                // making it primary would re-route every layout in the fleet on a
                // change whose purpose is to reach layouts openRawResource cannot.
                // The fallback costs nothing when the stream exists and is generic
                // -- any app that overrides getLayout gets honoured.
                doc = macapkDocumentFromLayout(r, resource);
            }
            if (doc == null) {
                NativeLog.w(TAG, "inflate(0x" + Integer.toHexString(resource)
                                  + "): no XML found -- returning empty FrameLayout");
                return new android.widget.FrameLayout(context);
            }
            return macapkInflateDocument(doc, root, attachToRoot, resource);
        } catch (Throwable t) {
            macapkReportInflateFailure("inflate(0x" + Integer.toHexString(resource)
                              + ") failed: " + t, resource, t);
            return new android.widget.FrameLayout(context);
        } finally {
            if (xmlStream != null) try { xmlStream.close(); } catch (Throwable ignored) {}
        }
    }

    /** BinaryXmlParser's attr-id records (MACAPK_ATTRID_NS, written beside an
     *  attribute whose name the resource table spells differently) exist for
     *  DomXmlResourceParser.getAttributeNameResource. This inflater's own loops
     *  match attributes by LOCAL name in any namespace, and a record's local
     *  name is the attribute it describes while its value is an attr id, so it
     *  would be applied over the real value. They are dropped from every layout
     *  this class parses; the alias records stay, since TypedArray looks the
     *  attribute up by its table name. */
    private static void macapkDropAttrIdRecords(Element e) {
        if (e == null) return;
        NamedNodeMap attrs = e.getAttributes();
        java.util.List<org.w3c.dom.Attr> drop = null;
        for (int i = 0; attrs != null && i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (com.macapk.loader.BinaryXmlParser.MACAPK_ATTRID_NS.equals(a.getNamespaceURI())) {
                if (drop == null) drop = new java.util.ArrayList<>();
                drop.add((org.w3c.dom.Attr) a);
            }
        }
        if (drop != null) for (org.w3c.dom.Attr a : drop) e.removeAttributeNode(a);
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element ce) macapkDropAttrIdRecords(ce);
        }
    }

    /** Everything inflate(int, ...) did once it held the layout's DOM, moved
     *  here unchanged (2026-09-26, lane platlayouts) so the platform-layout
     *  branch and inflate(XmlPullParser, ...) share it. Throws whatever the
     *  body throws; each caller keeps its own catch. */
    private View macapkInflateDocument(Document doc, ViewGroup root, boolean attachToRoot,
                                       int resource) {
        Element rootElem = doc.getDocumentElement();
        if (rootElem == null) {
            return new android.widget.FrameLayout(context);
        }
        View inflated = inflateElement(rootElem, root);
        if (inflated == null) inflated = new android.widget.FrameLayout(context);
        // MACAPK_TRACE_LAYOUTREQ=1: dump each inflated view's
        // isLayoutRequested() + childCount at the END of inflate.
        //
        // WHY THIS EXISTS. androidx ConstraintLayout registers its children
        // with the constraint solver in exactly ONE place, and it is doubly
        // guarded (verified against the app's own bytecode + androidx main):
        //
        //     protected boolean mDirtyHierarchy = true;
        //     // in onMeasure:
        //     if (mDirtyHierarchy) {
        //         mDirtyHierarchy = false;                 // cleared UNCONDITIONALLY
        //         if (updateHierarchy()) { mLayoutWidget.updateHierarchy(); }
        //     }
        //     private boolean updateHierarchy() {
        //         boolean recompute = false;
        //         for (int i = 0; i < getChildCount(); i++) {
        //             if (getChildAt(i).isLayoutRequested()) { recompute = true; break; }
        //         }
        //         if (recompute) { setChildrenConstraints(); }   // <- the ONLY registration
        //         return recompute;
        //     }
        //
        // So if NO child reports isLayoutRequested() on the single pass where
        // mDirtyHierarchy is still true, the solver's widget list stays
        // EMPTY FOREVER: ConstraintLayout then measures itself correctly and
        // never calls child.measure() on anything, and every child sits at
        // 0x0. That is exactly the Wikipedia symptom (v1.0.836): CL measured
        // 210x at w=808 EXACTLY h=1622 EXACTLY, three children with correct
        // LayoutParams and constraint anchors, all measuring 0x0, no throw.
        // Since View.layout() clears the flag (AOSP-correct -- see
        // View.layout, `mPrivateFlags &= ~PFLAG_FORCE_LAYOUT`), ANY layout
        // pass that reaches the subtree before its first measure poisons the
        // ConstraintLayout permanently.
        //
        // This trace answers the decisive question -- were the flags already
        // gone at the END of inflate (poisoned during inflate) or still set
        // (poisoned later, by the driver's traversal order)? Free unless the
        // env var is set.
        if ("1".equals(System.getenv("MACAPK_TRACE_LAYOUTREQ"))) {
            StringBuilder sb = new StringBuilder("inflate(0x")
                .append(Integer.toHexString(resource)).append(") layoutReq: ");
            dumpLayoutRequested(inflated, sb, 0);
            NativeLog.i(TAG, sb.toString());
        }
        // v1.0.98 debug: log IDs of inflated subtree if env DIAG_INFLATE_IDS set
        if ("1".equals(System.getenv("MACAPK_DIAG_INFLATE_IDS"))) {
            StringBuilder ids = new StringBuilder("inflate(0x")
                .append(Integer.toHexString(resource)).append(") IDs: ");
            collectIds(inflated, ids);
            NativeLog.i(TAG, ids.toString());
        }
        // v1.0.95: when the top-level tag was <merge>, inflateElement
        // returns `root` (it has already added the merge's children
        // directly into root). Calling root.addView(root) here would
        // create a self-cycle in the View tree, making findViewById
        // (and any other recursive walker like ViewCanvasDriver's
        // treeHasSurfaceView) recurse forever -> StackOverflowError.
        // AOSP's LayoutInflater.inflate special-cases <merge> the
        // same way: when the top tag is merge, rInflate is called
        // on root and NO outer addView happens (see frameworks/base
        // LayoutInflater.java:526).
        if (root != null && attachToRoot
            && inflated != root
            && inflated.getParent() == null) {
            root.addView(inflated);
        }
        return inflated;
    }

    /** Walk the DOM tree starting at `elem`, instantiating each tag
     *  as a View and recursing over children. `parent` may be null
     *  (root inflation); subsequent recursive calls always have the
     *  immediate parent ViewGroup so addView wiring stays correct. */
    private View inflateElement(Element elem, ViewGroup parent) {
        // AOSP LayoutInflater.createViewFromTag does this BEFORE constructing
        // anything (aosp-mirror/platform_frameworks_base main,
        //  core/java/android/view/LayoutInflater.java):
        //
        //     // Apply a theme wrapper, if allowed and one is specified.
        //     if (!ignoreThemeAttr) {
        //         final TypedArray ta = context.obtainStyledAttributes(attrs, ATTRS_THEME);
        //         final int themeResId = ta.getResourceId(0, 0);
        //         if (themeResId != 0) {
        //             context = new ContextThemeWrapper(context, themeResId);
        //         }
        //         ta.recycle();
        //     }
        //     ... with ATTRS_THEME = { com.android.internal.R.attr.theme }
        //
        // and because the reassigned local is what gets handed to the View
        // constructor AND to the child recursion, the overlay covers the whole
        // subtree. We had no equivalent at all: `android:theme` was read only
        // for the MANIFEST (application/activity themes), never per-View, so
        // every ?attr/ under such a subtree resolved against the activity theme.
        // Measured on Mastodon 2.13.1: fragment_splash.xml puts
        // android:theme="@style/Theme.Mastodon.Dark.SplashFragment" on its root.
        //
        // Gated on MACAPK_VIEW_THEME (default OFF) -- see ContextThemeWrapper.
        // Flag off: straight through to the original body, no attribute read.
        if (!ContextThemeWrapper.MACAPK_VIEW_THEME) return inflateElementInner(elem, parent);
        int themeResId = parseThemeStyleId(elem.getAttributeNS(
            "http://schemas.android.com/apk/res/android", "theme"));
        if (themeResId == 0) return inflateElementInner(elem, parent);
        Context saved = inflateContext;
        inflateContext = new ContextThemeWrapper(saved, themeResId);
        NativeLog.i(TAG, "android:theme 0x" + Integer.toHexString(themeResId)
            + " -> ContextThemeWrapper for <" + elem.getTagName() + "> subtree");
        try {
            return inflateElementInner(elem, parent);
        } finally {
            inflateContext = saved;
        }
    }

    /** Resolve an `android:theme` attribute value to a style resource id, or 0.
     *  Deliberately NOT parseResourceId: that one HASHES an unresolvable name
     *  into a synthetic id so findViewById can still match, which is right for
     *  ids and wrong here -- a hashed id names no style, so applyStyle would
     *  silently apply nothing while we logged that we had applied a theme.
     *  0 means "no overlay", which is the honest answer. */
    private int parseThemeStyleId(String raw) {
        if (raw == null) return 0;
        raw = raw.trim();
        if (raw.length() < 2 || raw.charAt(0) != '@') return 0;
        String body = raw.substring(1);
        // Binary-manifest / AXML form: "@0x7f110007".
        if (body.startsWith("0x") || body.startsWith("0X")) {
            try { return (int) Long.parseLong(body.substring(2), 16); }
            catch (NumberFormatException ignored) { return 0; }
        }
        String type = "style";
        int slash = body.indexOf('/');
        if (slash > 0) { type = body.substring(0, slash); body = body.substring(slash + 1); }
        // "@android:style/..." -- a framework theme we have no style table for.
        if (type.startsWith("android:") || body.startsWith("android:")) return 0;
        int colon = body.indexOf(':');
        if (colon >= 0) body = body.substring(colon + 1);   // strip "pkg:"
        try {
            if (context != null && context.getResources() != null) {
                return context.getResources().getIdentifier(
                    body, type, context.getPackageName());
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private View inflateElementInner(Element elem, ViewGroup parent) {
        String name = elem.getTagName();
        // AOSP LayoutInflater.createViewFromTag opens with exactly this:
        //     if (name.equals("view")) {
        //         name = attrs.getAttributeValue(null, "class");
        //     }
        // (aosp-mirror/platform_frameworks_base main,
        //  core/java/android/view/LayoutInflater.java). The lowercase `<view>`
        //  tag means "instantiate the class named by the (un-namespaced) class
        //  attribute" -- the idiom you use when the class name would be an
        //  awkward XML tag, and the form aapt2 emits for some tooling.
        //
        // We did not handle it, so `<view class="..."/>` became an "unknown
        // tag" FrameLayout placeholder. Measured on Anuto TD 34 (2026-07-29):
        // activity_game.xml is
        //     <view android:id="@id/view_tower_defense" android:layout_width="match_parent"
        //           android:layout_height="match_parent" android:layout_below="@id/fragment_header"
        //           class="ch.logixisland.anuto.view.game.GameView" />
        // so the entire game surface was a FrameLayout and
        // `(GameView) findViewById(R.id.view_tower_defense)` threw
        // ClassCastException out of GameActivity.onCreate. Generic: any app
        // using the `<view class=...>` form.
        if ("view".equals(name)) {
            String cls = elem.getAttribute("class");
            if (cls != null && !cls.isEmpty()) {
                name = cls;
            }
        }
        if ("merge".equals(name)) {
            // <merge>: process children directly into the parent (no
            // intermediate View created). v1.0.74 fix: the recursive
            // inflateElement returns the child View but doesn't add
            // it to the parent -- that's normally the CALLER's job
            // (the outer inflateElement does addView for regular
            // children). For merge, we ARE the caller, so we must
            // addView ourselves. Without this, included merge content
            // (e.g. AppCompat's abc_screen_content_include →
            // ContentFrameLayout) never gets attached to the inflated
            // tree, and findViewById can't find it.
            for (Node n = elem.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element child) {
                    View childView = inflateElement(child, parent);
                    if (childView != null && childView != parent
                        && childView.getParent() == null && parent != null) {
                        parent.addView(childView);
                        NativeLog.i(TAG, "merge: addView " + childView.getClass().getSimpleName()
                          + " id=0x" + Integer.toHexString(childView.getId()) + " to " + parent.getClass().getSimpleName()
                          + "(now " + parent.getChildCount() + " children)");
                    }
                }
            }
            return parent;
        }
        if ("include".equals(name)) {
            // <include layout="@layout/foo" /> -- resolve the layout
            // reference, open the resource, parse it, and inflate its
            // top-level element into the parent. AppCompat's
            // abc_screen_simple.xml uses this to pull in
            // ContentFrameLayout via abc_screen_content_include.
            // Without resolving the include, the ContentFrameLayout
            // never appears in the inflated tree and AppCompat's
            // findViewById(R.id.action_bar_activity_content) returns
            // null → setAttachListener NPE.
            String layoutRef = elem.getAttribute("layout");
            NativeLog.i(TAG, "include: layoutRef='" + layoutRef + "' parent=" + (parent == null ? "null" : parent.getClass().getSimpleName()));
            if (layoutRef == null || layoutRef.isEmpty()) {
                // No layout attribute -- fall through to child walk
                // (same as merge).
                for (Node n = elem.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element child) inflateElement(child, parent);
                }
                return parent;
            }
            int layoutId = parseResourceId(layoutRef);
            if (layoutId == 0) {
                NativeLog.w(TAG, "include: can't resolve layout " + layoutRef);
                return parent;
            }
            try {
                android.content.res.Resources r = (context != null) ? context.getResources() : null;
                if (r == null) return parent;
                Document doc;
                // MACAPK_PLATFORM_LAYOUTS: `layout="@android:layout/x"` is a
                // framework layout, read through res.getLayout like the root
                // path (AOSP parseInclude -> getContext().getResources()
                // .getLayout(layout)). parseResourceId would hash the name.
                final int fwLayout = MACAPK_PLATFORM_LAYOUTS ? macapkPlatformLayoutId(layoutRef) : 0;
                if (fwLayout != 0) {
                    doc = macapkPlatformDocument(r, fwLayout);
                    if (doc == null) {
                        NativeLog.w(TAG, "include: no platform layout for " + layoutRef);
                        return parent;
                    }
                } else {
                    java.io.InputStream s = r.openRawResource(layoutId);
                    if (s == null) {
                        NativeLog.w(TAG, "include: no resource for " + layoutRef);
                        return parent;
                    }
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    dbf.setNamespaceAware(true);
                    DocumentBuilder db = dbf.newDocumentBuilder();
                    try { doc = db.parse(s); } finally { try { s.close(); } catch (Throwable ignored) {} }
                    macapkDropAttrIdRecords(doc.getDocumentElement());
                }
                Element rootElem = doc.getDocumentElement();
                if (rootElem != null) {
                    View inflated = inflateElement(rootElem, parent);
                    // v1.0.109: AOSP behavior -- the <include> tag's own
                    // attributes (android:id, android:visibility,
                    // android:layout_*) OVERRIDE whatever the included
                    // layout's root element declared. ConstraintLayout
                    // children commonly use <include android:id="@id/foo"
                    // layout="@layout/bar" ...> to refer to the
                    // *include site*'s id in cross-view constraints
                    // (e.g. F-Droid's app_card_horizontal.xml has
                    //   <include android:id="@id/new_tag" layout="@layout/app_status_new" />
                    //   ...
                    //   <TextView ...
                    //     app:layout_constraintEnd_toStartOf="@id/new_tag" />
                    // and the constraint target view IS the included
                    // root, but identified by the <include>'s own id).
                    // Without this override, the included root has the
                    // *layout file*'s root-element id (often unset),
                    // and the cross-view constraint reference fails to
                    // resolve -- the solver bails on that anchor and
                    // every dependent child collapses to zero width.
                    if (inflated != null && inflated != parent) {
                        applyIncludeOverrides(inflated, elem, parent);
                    }
                    return inflated;
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "include: parsing " + layoutRef + " threw " + t);
            }
            return parent;
        }
        // Tier 3 M0 (MACAPK_REAL_LOOPER): consult the inflater factories in the
        // AOSP order (factory2 -> factory -> privateFactory) BEFORE default class
        // instantiation, mirroring LayoutInflater.tryCreateView. This is what
        // lets a <fragment>/FragmentContainerView tag become a real Fragment
        // (our FragmentInflaterFactory is the private factory). For non-fragment
        // tags the factories return null and we fall through unchanged. Flag-OFF:
        // factories are never consulted, so behavior is byte-for-byte the same.
        View view = null;
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            view = tryFactoryCreate(parent, name, elem);
        }
        lastCtorFailClass = null;
        lastCtorFailCause = null;
        if (view == null) view = createViewByName(name, elem);
        // AOSP hands every inflated ViewStub the inflater it should use LATER,
        // at the moment it creates it -- createViewFromTag, verbatim:
        //
        //     if (view instanceof ViewStub) {
        //         // Use the same context when inflating ViewStub later.
        //         final ViewStub viewStub = (ViewStub) view;
        //         viewStub.setLayoutInflater(cloneInContext((Context) args[0]));
        //     }
        //
        // (aosp-mirror/platform_frameworks_base, core/java/android/view/
        //  LayoutInflater.java). We never did, so every ViewStub we inflated
        // carried a NULL inflater. ViewStub.inflate() itself survives that (it
        // falls back to LayoutInflater.from(getContext()), as AOSP's does), which
        // is why this went unnoticed -- but ViewStub.getLayoutInflater() is public
        // and an app is entitled to the non-null AOSP hands it.
        //
        // MEASURED on Instagram 445: its async view-pool (X.0cH.A07, reached from
        // getView()) takes the stub's inflater and calls getContext() on it, so a
        // null aborted InstagramMainActivity.onCreate with
        //   NullPointerException: Cannot invoke
        //   "android.view.LayoutInflater.getContext()" because "<local13>" is null
        // which left the FragmentManager unattached ("has not been attached to a
        // host") and the window black. Generic: any app that reads a stub's
        // inflater, which is exactly what a view-recycling pool does.
        if (view instanceof ViewStub) {
            try { ((ViewStub) view).setLayoutInflater(cloneInContext(context)); }
            catch (Throwable ignored) { /* never block an inflate on this */ }
        }
        boolean wasPlaceholder = false;
        if (view == null) {
            // Two very different failures used to share one bland [W] line, and
            // that cost real diagnostic hops on three separate apps in one
            // session (Calculator's MaterialToolbar, OpenCalculator's
            // SlidingUpPanelLayout, Anuto's GameView): each surfaced only as a
            // distant `ClassCastException: FrameLayout cannot be cast to X` in
            // the app's own onCreate/onStart, several hundred log lines away
            // from the actual cause.
            //
            // AOSP does not substitute anything -- LayoutInflater
            // .createViewFromTag wraps BOTH ClassNotFoundException and any other
            // constructor Exception in an InflateException and THROWS
            // (aosp-mirror/platform_frameworks_base main,
            //  core/java/android/view/LayoutInflater.java). We keep the
            // placeholder deliberately: it is load-bearing resilience for an
            // incomplete framework, because a ViewGroup placeholder still lets
            // the unknown tag's XML children inflate and stay reachable via
            // findViewById (F-Droid's CoordinatorLayout -> SwipeRefreshLayout ->
            // RecyclerView case). Propagating instead would blank whole
            // activities that currently render most of their content.
            //
            // What we CAN do is stop the substitution from being quiet, and
            // separate the two cases:
            //   * class not shipped   -> [W]; AOSP would throw
            //     InflateException(ClassNotFoundException) here too, so the app
            //     is asking for something we genuinely do not have.
            //   * class LOADED, ctor THREW -> [E]; this is always a gap on OUR
            //     side, and the placeholder GUARANTEES a later ClassCastException
            //     on any `(X) findViewById(...)`. Name the cause inline so the
            //     cast failure is one grep from its origin.
            // The un-prefixed tag form (`<ProgressBar>`) resolves to
            // android.widget.ProgressBar, so match the simple name too.
            if (lastCtorFailClass != null
                    && (lastCtorFailClass.equals(name)
                        || lastCtorFailClass.endsWith("." + name))) {
                NativeLog.e(TAG, "inflate: '" + name + "' LOADED but its constructor threw ("
                    + lastCtorFailCause + ") -- substituting a FrameLayout placeholder."
                    + " Any (" + name.substring(name.lastIndexOf('.') + 1)
                    + ") findViewById(...) in the app WILL now throw ClassCastException;"
                    + " AOSP would have thrown InflateException here. Fix the framework gap"
                    + " named in the cause above.");
            } else {
                NativeLog.w(TAG, "inflate: unknown tag '" + name + "' -- using FrameLayout as placeholder");
            }
            // v1.0.98: use FrameLayout as the placeholder, not bare View.
            // A placeholder must be a ViewGroup so the XML children of
            // the unknown tag still get inflated + added to the tree --
            // otherwise findViewById on the placeholder can't reach
            // descendants (e.g. F-Droid's main_tab_latest.xml has a
            // CoordinatorLayout root -> SwipeRefreshLayout child ->
            // ComposeView, TextView empty_state, RecyclerView app_list.
            // If CoordinatorLayout falls back to a bare View, none of
            // those descendants exist in the tree, findViewById returns
            // null, the binder NPEs).
            // The placeholder stands for a view AOSP would have built with the
            // per-view context (the parent's plus this element's android:theme),
            // and with MACAPK_INFLATE_PARENT_CONTEXT its children are built with
            // ITS context, so it must carry that context or the subtree loses the
            // element's theme overlay (measured: the design_layout_snackbar_include
            // children fell from ThemeOverlay.AppCompat.Dark to the light theme).
            view = new android.widget.FrameLayout(
                (MACAPK_INFLATE_PARENT_CONTEXT && inflateContext != null) ? inflateContext : context);
            wasPlaceholder = true;
        }
        // v1.0.93: when we substitute a placeholder View for a class
        // we couldn't instantiate, the placeholder has zero intrinsic
        // size -- which breaks RelativeLayout's wrap_content height
        // anchoring (BottomNavigationView at the bottom of activity_main
        // would collapse to zero height, causing layout_above siblings
        // to also collapse). For Material widgets with well-known
        // intrinsic heights, set a minimum size on the placeholder so
        // it occupies a sensible band. The values mirror Material 3
        // spec defaults at xhdpi (160dp / 24px-per-dp factor).
        //
        // v1.0.95: apply this hint to REAL Material widget instances
        // too. Once BottomNavigationView etc. fully instantiates, its
        // real onMeasure would compute an intrinsic height -- but our
        // framework doesn't run a proper measure pass yet, so the
        // widget reports measuredHeight=0 to RelativeLayout, which
        // collapses it to zero. Setting setMinimumHeight on the real
        // widget gives RelativeLayout something to work with; once
        // measure passes are wired, the real onMeasure can override.
        // The visible-tint background is only painted for placeholders
        // (so a real BottomNavigationView's theme background shows
        // through).
        applyKnownWidgetIntrinsicSize(view, name, wasPlaceholder);
        applyAttributes(view, elem);
        // v1.0.93: build LayoutParams whose CLASS matches the parent.
        // RelativeLayout children need RelativeLayout.LayoutParams so
        // addRule(ALIGN_PARENT_BOTTOM) etc. resolve. LinearLayout
        // children need LinearLayout.LayoutParams so layout_weight
        // applies. FrameLayout children need FrameLayout.LayoutParams
        // for layout_gravity. Parsing layout_* attrs into the params
        // here means RelativeLayout.onLayout has the rules it needs
        // to position children correctly (alignParentBottom +
        // layout_above + etc.).
        //
        // The lp class is determined by the IMMEDIATE PARENT (the one
        // calling inflateElement for this child) -- captured via the
        // `parent` arg. addView(child, lp) attaches with the layout
        // params; without an lp, addView leaves the child with default
        // wrap-content and no rules.
        if (parent != null) {
            ViewGroup.LayoutParams lp = buildLayoutParams(parent, elem);
            if (lp != null) {
                view.setLayoutParams(lp);
                // v1.0.131: requestLayout AFTER LP is set. For
                // ConstraintLayout children specifically, the LP's
                // constraint fields were just populated by our
                // reflective writes in applyConstraintLayoutAttribute,
                // but the parent's `mDirtyHierarchy` flag won't fire
                // its setChildrenConstraints rebuild unless at least
                // one child has `isLayoutRequested() == true`. Calling
                // requestLayout here both marks the child AND bubbles
                // up to set the parent's mDirtyHierarchy. Generic for
                // any LayoutParams subclass -- inflated views that
                // never set this flag get a stale layout pass after
                // we write their LP fields.
                try { view.requestLayout(); } catch (Throwable ignored) {}
                // v1.0.131: ALSO trigger ConstraintLayout's RTL
                // resolver via reflection. Per the androidx source
                // (ConstraintLayout.java::resolveLayoutDirection,
                // lines 3775-3923), this is the method that POPULATES
                // mResolvedLeftToLeft/Right/RightToLeft/Right from
                // startToXxx/endToXxx fields. AOSP's View tree-layout
                // pass invokes this automatically; our framework
                // doesn't drive that path, so we invoke it here
                // manually. setAccessible-walks all the LP class's
                // public methods looking for one taking a single int
                // parameter -- ConstraintLayout$LayoutParams has the
                // matching signature, vanilla LP does not (silent
                // no-op for non-CL parents).
                if (isConstraintLayoutLayoutParams(lp)) {
                    try {
                        java.lang.reflect.Method m = lp.getClass().getMethod(
                            "resolveLayoutDirection", int.class);
                        // View.LAYOUT_DIRECTION_LTR == 0
                        m.invoke(lp, 0);
                    } catch (Throwable ignored) {}
                }
            }
        }
        // <requestFocus/> (AOSP TAG_REQUEST_FOCUS): a CHILD ELEMENT, not an
        // attribute, that asks the enclosing view to take initial focus.
        // Scanned unconditionally -- unlike a real child view it can appear
        // inside a LEAF widget (unitconverter's header_value_from.xml puts
        // one directly inside an <EditText>), so it must not be gated behind
        // `instanceof ViewGroup` the way real children are below.
        boolean pendingRequestFocus = false;
        for (Node n = elem.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child && "requestFocus".equals(child.getTagName())) {
                pendingRequestFocus = true;
            }
        }
        // Recurse over children if this view is a ViewGroup. Children
        // are added in document order so dispatchDraw paints them in
        // the same order Android does.
        if (view instanceof ViewGroup vg) {
          // AOSP rInflateChildren (android14-release LayoutInflater.java
          // :1094-1097) builds every child with the PARENT VIEW's context:
          //     rInflate(parser, parent, parent.getContext(), attrs, finishInflate);
          // and rInflate hands that context to createViewFromTag (:1135), which
          // layers the child's own android:theme on top (:1003-1010). A parent
          // whose constructor wrapped its context (Material's
          // MaterialThemeOverlay.wrap in TextInputLayout, MaterialToolbar,
          // BottomNavigationView, ...) therefore themes its whole subtree. We
          // built every child with the inflate context, so the overlay stopped
          // at the parent: anki's dialog_deck_description TextInputEditText
          // read the activity's editTextStyle (Widget.AppCompat.EditText, 18sp)
          // instead of the TextInputLayout overlay's
          // Widget.Material3.TextInputEditText.OutlinedBox (16sp, 0.03125).
          // The element's android:theme wrap in inflateElement then wraps THIS
          // context, as AOSP's does. MACAPK_INFLATE_PARENT_CONTEXT=0 restores the
          // inflate-context-for-everything path.
          final Context macapkSavedCtx = inflateContext;
          if (MACAPK_INFLATE_PARENT_CONTEXT) {
              Context pc = null;
              try { pc = vg.getContext(); } catch (Throwable ignored) {}
              if (pc != null) inflateContext = pc;
          }
          try {
            for (Node n = elem.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element child) {
                    // Consumed by the scan above, not instantiated as a real
                    // view -- AOSP's rInflate special-cases it the same way
                    // (an `if (TAG_REQUEST_FOCUS.equals(name))` branch ahead
                    // of the ordinary createViewFromTag branch: aosp-mirror/
                    // platform_frameworks_base android13-release, core/java/
                    // android/view/LayoutInflater.java:1114). Without this
                    // guard the "unknown tag" placeholder path below would
                    // substitute a spurious FrameLayout child for it.
                    if ("requestFocus".equals(child.getTagName())) continue;
                    View childView = inflateElement(child, vg);
                    if (childView != null && childView != vg
                                            && childView.getParent() == null) {
                        vg.addView(childView);
                    }
                }
            }
          } finally {
              inflateContext = macapkSavedCtx;
          }
        }
        // AOSP LayoutInflater.rInflate walks every child of the enclosing
        // element, sets `pendingRequestFocus = true` when it sees
        // <requestFocus/>, and once ALL children are processed:
        //     if (pendingRequestFocus) parent.restoreDefaultFocus();
        // (aosp-mirror/platform_frameworks_base android13-release, core/
        // java/android/view/LayoutInflater.java:1114-1133). Placed here,
        // after the child recursion above and before onFinishInflate below,
        // to match that order. View.restoreDefaultFocus() -- unless a
        // ViewGroup override finds a focusedByDefault descendant -- is
        // exactly `requestFocus(FOCUS_DOWN)` (core/java/android/view/
        // View.java), which is what the tag called DIRECTLY in the original
        // implementation this project's docs describe (aosp-mirror/
        // platform_frameworks_base android-5.0.0_r1, LayoutInflater.java:821
        // `parseRequestFocus` -> `view.requestFocus()`). Calling
        // requestFocus() directly rather than reimplementing
        // restoreDefaultFocus's ViewGroup descendant/cluster search: every
        // corpus instance of this tag (unitconverter's is the only one) sits
        // inside a leaf EditText, for which the two are identical, and the
        // broader descendant search is a separate, unbuilt feature.
        if (pendingRequestFocus) {
            try { view.requestFocus(); } catch (Throwable ignored) {}
        }
        // AOSP: the inflater calls onFinishInflate() once the view + all its
        // children are inflated, so custom ViewGroups can grab child refs via
        // findViewById. Resilient (a throwing onFinishInflate blanks only that
        // view's post-inflate setup, not the whole inflate pass), mirroring the
        // resilient measure/draw guards.
        try { view.onFinishInflate(); }
        catch (Throwable t) {
            NativeLog.w(TAG, "onFinishInflate threw on "
                + view.getClass().getName() + ": " + t);
        }
        return view;
    }

    /** v1.0.93: build a LayoutParams sized for the parent ViewGroup
     *  type, then parse this child's `layout_*` attributes onto it.
     *  Returns null if the parent doesn't define a LayoutParams or
     *  the child has no layout-relevant attrs (in which case the
     *  child uses parent.generateDefaultLayoutParams in addView). */
    /** v1.0.109: apply the <include> tag's own attributes to the
     *  inflated child, overriding whatever the included layout's
     *  root element declared. AOSP semantic: android:id,
     *  android:visibility, and android:layout_* attrs on the include
     *  tag win over the included root's. Without this, cross-view
     *  ConstraintLayout constraints that reference the include
     *  site's id can't resolve (the inflated view has the included
     *  file's root-element id, not the include site's). */
    static final boolean INCLUDE_SITE_STYLE_LP =
        !"0".equals(System.getenv("MACAPK_INCLUDE_SITE_STYLE_LP"));
    private void applyIncludeOverrides(View inflated, Element includeElem, ViewGroup parent) {
        try {
            org.w3c.dom.NamedNodeMap attrs = includeElem.getAttributes();
            // v1.0.115: regenerate LayoutParams from the INCLUDE SITE's
            // attrs (not the included root element's). AOSP semantics:
            // the <include> tag's `app:layout_constraint*`, `layout_*`,
            // and `android:layout_*` attributes belong to the include
            // SITE, and the parent ViewGroup's generateLayoutParams
            // reads them. Previously we kept the LP that was built from
            // the included layout's root element -- which has NO
            // constraint attrs -- so cross-view anchors like
            //   <include id=new_tag layout=app_status_new
            //            app:layout_constraintEnd_toEndOf="parent"
            //            app:layout_constraintTop_toBottomOf="@id/icon" />
            // resulted in new_tag.LayoutParams with NO constraints set.
            // ConstraintLayout then placed new_tag at (0,0), and any
            // sibling whose `endToStart=@new_tag` anchor depended on
            // new_tag's right edge collapsed to W=0 (the F-Droid name +
            // summary TextView empty-bounds bug).
            //
            // The fix: regenerate the LP using the include site's
            // DomBackedAttributeSet so the parent ConstraintLayout's
            // generateLayoutParams reads the constraint attrs from
            // include's own XML and wires them into the new LP.
            //
            // `parent` is passed explicitly because at this point the
            // inflated view has NOT been added to its parent yet
            // (`addView` happens at the caller's inflateElement loop
            // in line ~312), so `inflated.getParent()` returns null.
            ViewGroup.LayoutParams lp = inflated.getLayoutParams();
            // lane layout, 2026-08-01: regenerate from the include SITE only when
            // the site supplies BOTH layout_width AND layout_height. That is
            // AOSP's exact fallback contract -- parseInclude (android13-release
            // LayoutInflater.java:1109-1123):
            //     // We try to load the layout params set in the <include /> tag.
            //     // If the parent can't generate layout params (ex. missing
            //     // width/height), we fallback to the layout params set in the
            //     // included XML file.
            // and generateLayoutParams(siteAttrs) "can't" precisely when
            // width/height are absent (ViewGroup.LayoutParams.setBaseAttributes
            // throws "You must supply a layout_width attribute"). OUR LayoutParams
            // parsers are forgiving (missing dimensions default instead of
            // throwing), so the unconditional regeneration silently REPLACED the
            // included root's LP with a default-valued site LP. Measured victim:
            // NewPipe's <include layout="@layout/drawer_layout"/> carries no
            // layout_* at all while the included root (a NavigationView) declares
            // android:layout_gravity="start" -- the regenerated LP lost the
            // gravity, androidx DrawerLayout no longer recognized the child as a
            // drawer (isDrawerView reads lp.gravity), laid it out as a full-width
            // CONTENT view over everything, and openDrawer(START) threw "No
            // drawer view found with gravity LEFT" (campaign log + capture,
            // 2026-08-01). The F-Droid constraint-include case that motivated
            // regeneration (v1.0.115) is preserved: every such include site
            // declares width+height (verified in its decoded layouts), so it
            // still takes the regeneration path.
            boolean siteHasW = false, siteHasH = false;
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                String n = a.getLocalName();
                if (n == null) n = a.getNodeName();
                if ("layout_width".equals(n))  siteHasW = true;
                else if ("layout_height".equals(n)) siteHasH = true;
            }
            // The site's width/height may come from its style= rather than
            // attributes: AOSP's generateLayoutParams(attrs) reads through
            // obtainStyledAttributes, which honours the AttributeSet's style.
            // Measured: AdGuard 4.14's fragment_home has
            //   <include android:id="@id/stub" style="@style/LayoutFullWidth"
            //            app:layout_constraintBottom_toBottomOf="parent" .../>
            // and, read as "no size", kept the included root's constraint-less
            // LP: the stub sat at the top, the card barrier followed it there,
            // and the cards covered the main protection switch (issue #5,
            // 2026-10-19). MACAPK_INCLUDE_SITE_STYLE_LP=0 restores attrs-only.
            java.util.Map<String, String> siteStyle = java.util.Collections.emptyMap();
            if (INCLUDE_SITE_STYLE_LP && parent != null && !(siteHasW && siteHasH)) {
                siteStyle = new DomBackedAttributeSet(includeElem, macapkLpContext(parent)).macapkStyleItems();
                if (siteStyle.containsKey("layout_width"))  siteHasW = true;
                if (siteStyle.containsKey("layout_height")) siteHasH = true;
            }
            if (parent != null && siteHasW && siteHasH) {
                try {
                    android.util.AttributeSet siteAttrs = new DomBackedAttributeSet(includeElem, macapkLpContext(parent));
                    ViewGroup.LayoutParams regenerated = parent.generateLayoutParams(siteAttrs);
                    if (regenerated != null) {
                        lp = regenerated;
                    }
                } catch (Throwable t) {
                    NativeLog.w(TAG, "applyIncludeOverrides: parent.generateLayoutParams threw " + t);
                }
            }
            java.util.Map<String, String> incMargins = new java.util.HashMap<>();
            // Style-supplied layout_* first, the site's own attributes then
            // overwrite (the same precedence the element path applies).
            if (lp != null) {
                for (java.util.Map.Entry<String, String> en : siteStyle.entrySet()) {
                    String name = en.getKey();
                    if (!name.startsWith("layout_")) continue;
                    if (LAYOUT_AOSP_1643 && MARGIN_ATTRS.contains(name)) incMargins.put(name, en.getValue());
                    else applyLayoutAttribute(lp, name, en.getValue());
                }
            }
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                String name = a.getLocalName();
                if (name == null) name = a.getNodeName();
                String val = a.getNodeValue();
                if (name == null || val == null) continue;
                if ("id".equals(name)) {
                    int id = parseResourceId(val);
                    if (id != 0) inflated.setId(id);
                } else if ("visibility".equals(name)) {
                    if ("gone".equals(val)) inflated.setVisibility(View.GONE);
                    else if ("invisible".equals(val)) inflated.setVisibility(View.INVISIBLE);
                    else if ("visible".equals(val)) inflated.setVisibility(View.VISIBLE);
                } else if (name.startsWith("layout_") && lp != null) {
                    if (LAYOUT_AOSP_1643 && MARGIN_ATTRS.contains(name)) incMargins.put(name, val);
                    else applyLayoutAttribute(lp, name, val);
                }
            }
            if (lp != null) applyMargins(lp, incMargins);
            if (lp != null) inflated.setLayoutParams(lp);
        } catch (Throwable t) {
            NativeLog.w(TAG, "applyIncludeOverrides failed: " + t);
        }
    }

    /** v1.0.107 diagnostic (kept for ConstraintLayout debugging in
     *  future sessions): dump ConstraintLayout$LayoutParams's
     *  constraint fields to confirm our DomBackedAttributeSet + the
     *  fixed TypedArray.getIndexCount/getIndex actually populates
     *  them. Verified populated as of v1.0.107 ImageView icon dump:
     *    leftToLeft=0 rightToRight=0 topToTop=0 startToStart=0
     *  which matches the XML constraintLeft_toLeftOf/Right_toRightOf
     *  /Top_toTopOf=parent. Constraints land in LP correctly. */
    private void dumpConstraintLpFields(Object lp) {
        try {
            StringBuilder sb = new StringBuilder("ConstraintLP[");
            Class<?> c = lp.getClass();
            String[] fields = {
                "leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
                "topToTop", "topToBottom", "bottomToTop", "bottomToBottom",
                "startToStart", "startToEnd", "endToStart", "endToEnd",
                "width", "height",
                "horizontalChainStyle", "verticalChainStyle"
            };
            for (String name : fields) {
                try {
                    java.lang.reflect.Field f = c.getField(name);
                    sb.append(" ").append(name).append("=").append(f.get(lp));
                } catch (Throwable ignored) {}
            }
            sb.append("]");
            NativeLog.i(TAG, sb.toString());
        } catch (Throwable ignored) {}
    }

    /** The Context whose theme resolves a `style="?attr"` for a child's
     *  LayoutParams: the PARENT's, because AOSP's generateLayoutParams(attrs) is
     *  `new LayoutParams(getContext(), attrs)` on the parent (ViewGroup.java
     *  android14-release). Falls back to the current inflate context. */
    private Context macapkLpContext(ViewGroup parent) {
        Context c = null;
        try { if (parent != null) c = parent.getContext(); } catch (Throwable ignored) {}
        if (c == null) c = inflateContext != null ? inflateContext : context;
        return c;
    }

    private ViewGroup.LayoutParams buildLayoutParams(ViewGroup parent, Element elem) {
        if (parent == null) return null;
        // Diagnostic: log the parent class + child tag so we can verify
        // RelativeLayout children actually pick up RelativeLayout.LayoutParams.
        NativeLog.i(TAG, "buildLP: parent=" + parent.getClass().getSimpleName()
                       + " child=<" + elem.getNodeName() + ">");
        // Pick the LayoutParams class per parent type. Order matters --
        // more-derived first (RelativeLayout extends ViewGroup, but
        // RelativeLayout.LayoutParams isn't a FrameLayout.LayoutParams,
        // so check RelativeLayout before FrameLayout etc.).
        // v1.0.396: prefer the parent's OWN generateLayoutParams when it yields
        // a MEANINGFUL subclass. AppBarLayout extends LinearLayout and
        // CollapsingToolbarLayout extends FrameLayout, so the instanceof
        // fast-paths below would hand their children a LinearLayout/FrameLayout
        // .LayoutParams -- but AppBarLayout.onMeasure / CollapsingToolbarLayout
        // .onLayout cast the child LP to their OWN LayoutParams subclass,
        // throwing ClassCastException that aborted the WHOLE AppDetailsActivity
        // measure+layout (blank detail screen). generateLayoutParams returns the
        // correct subclass. We gate on "meaningful" (not the base ViewGroup
        // .LayoutParams / MarginLayoutParams) so our own LinearLayout/
        // RelativeLayout -- which don't override generateLayoutParams and would
        // return the base -- still fall through to the weight/rule-bearing
        // hardcoded LP below. (CoordinatorLayout / ConstraintLayout also return
        // their meaningful subclass here, which is strictly better than the
        // old `else` branch that ran the same call later.)
        ViewGroup.LayoutParams lp = null;
        try {
            ViewGroup.LayoutParams gen = parent.generateLayoutParams(
                new DomBackedAttributeSet(elem, macapkLpContext(parent)));
            if (gen != null
                && gen.getClass() != ViewGroup.LayoutParams.class
                && gen.getClass() != ViewGroup.MarginLayoutParams.class) {
                lp = gen;
            }
            // v1.0.495 DIAGNOSTIC (MACAPK_TRACE_GUIDE): for a ConstraintLayout
            // Guideline child, dump the generated LP's int/float fields so we
            // can see whether androidx's w3.e ctor actually STORED
            // guideBegin/guideEnd/guidePercent (proves "LP loses the value" vs
            // "solver ignores a correct LP"). Guideline elements are named
            // androidx...Guideline or R8'd; gate on the dump env to stay free.
            if (gen != null && System.getenv("MACAPK_TRACE_GUIDE") != null
                    && gen.getClass() != ViewGroup.LayoutParams.class
                    && gen.getClass() != ViewGroup.MarginLayoutParams.class) {
                StringBuilder sb = new StringBuilder("buildLP-CLchild tag="
                    + elem.getNodeName() + " lp="
                    + gen.getClass().getName() + " fields{");
                for (java.lang.reflect.Field f : gen.getClass().getDeclaredFields()) {
                    try {
                        Class<?> ft = f.getType();
                        if (ft == int.class || ft == float.class) {
                            f.setAccessible(true);
                            Object v = f.get(gen);
                            double d = ((Number) v).doubleValue();
                            if (d != 0 && d != -1) sb.append(f.getName())
                                .append('=').append(v).append(' ');
                        }
                    } catch (Throwable ignore) {}
                }
                sb.append('}');
                NativeLog.i(TAG, sb.toString());
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "buildLP: " + parent.getClass().getSimpleName()
                + ".generateLayoutParams(pre) threw " + t);
        }
        if (lp != null) {
            // Parent-specific subclass already built (AppBarLayout /
            // CollapsingToolbarLayout / CoordinatorLayout / ConstraintLayout
            // .LayoutParams etc.) -- keep it; the attr-walk below fills values.
        } else if (parent instanceof android.widget.RelativeLayout) {
            lp = new android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (parent instanceof android.widget.LinearLayout) {
            lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (parent instanceof android.widget.FrameLayout) {
            lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            // v1.0.98: when parent is an unknown ViewGroup (e.g.
            // CoordinatorLayout, ConstraintLayout from the APK) try
            // to construct the parent's own LayoutParams via its
            // generateLayoutParams(AttributeSet) or via reflection
            // on the inner LayoutParams class. Many ViewGroups in
            // androidx require their own LayoutParams subclass --
            // generic MarginLayoutParams fails the addView cast.
            //
            // v1.0.107: pass a real DomBackedAttributeSet to
            // generateLayoutParams so the parent's own LayoutParams
            // parser sees the XML's namespace-prefixed attrs
            // (`app:layout_constraintLeft_toLeftOf`,
            //  `app:layout_constraintTop_toTopOf`, etc.). Without it,
            // ConstraintLayout's LayoutParams parse no constraints
            // and children get laid out by ConstraintLayout's
            // "no-constraint-fallback" (which divides the parent
            // width equally among siblings). Hit by every
            // ConstraintLayout-based card / row layout (F-Droid
            // app cards, list item rows, etc.).
            ViewGroup.LayoutParams generated = null;
            android.util.AttributeSet attrSet = new DomBackedAttributeSet(elem, macapkLpContext(parent));
            try {
                generated = parent.generateLayoutParams(attrSet);
            } catch (Throwable t) {
                NativeLog.w(TAG, "buildLP: " + parent.getClass().getSimpleName()
                    + ".generateLayoutParams(attrs) threw " + t);
            }
            if (generated != null) {
                lp = generated;
            } else {
                try {
                    // Try the parent's inner LayoutParams(int, int) ctor
                    Class<?> lpc = Class.forName(parent.getClass().getName() + "$LayoutParams",
                        true, parent.getClass().getClassLoader());
                    java.lang.reflect.Constructor<?> ctor = lpc.getConstructor(int.class, int.class);
                    ctor.setAccessible(true);
                    lp = (ViewGroup.LayoutParams) ctor.newInstance(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                } catch (Throwable ignored) {
                    // Default MarginLayoutParams covers `layout_margin*`
                    // for generic ViewGroups; specific subclass attrs
                    // are dropped.
                    lp = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            }
        }
        // Style-supplied layout_* items FIRST (element attrs then
        // overwrite -- AOSP precedence). Calculator's keypad buttons
        // declare layout_width/height=0dp ONLY in KeypadButtonStyle;
        // without this they default wrap_content and the constraint
        // solver gets the wrong dimension behaviour.
        java.util.Map<String, String> sitems =
            new DomBackedAttributeSet(elem, macapkLpContext(parent)).macapkStyleItems();
        // Margins are collected (style items, then the element's own
        // attributes overwriting the same name) and resolved ONCE, in AOSP's
        // order -- see applyMargins. Applying them one attribute at a time let
        // a later layout_marginTop overwrite a layout_margin that AOSP says
        // wins, and dropped layout_marginHorizontal/Vertical entirely.
        java.util.Map<String, String> margins = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> en : sitems.entrySet()) {
            if (en.getKey().startsWith("layout_")) {
                if (LAYOUT_AOSP_1643 && MARGIN_ATTRS.contains(en.getKey())) margins.put(en.getKey(), en.getValue());
                else applyLayoutAttribute(lp, en.getKey(), en.getValue());
            }
        }
        // Walk attributes and apply layout-relevant ones.
        NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); ++i) {
            Node a = attrs.item(i);
            String name = a.getLocalName();
            if (name == null) name = a.getNodeName();
            String val = a.getNodeValue();
            if (LAYOUT_AOSP_1643 && name != null && val != null && MARGIN_ATTRS.contains(name)) margins.put(name, val);
            else applyLayoutAttribute(lp, name, val);
        }
        applyMargins(lp, margins);
        return lp;
    }

    /** BISECT GATE (v1.0.1643, default ON; remove once the fleet A/B is read).
     *  MACAPK_LAYOUT_AOSP_1643=0 restores the pre-1643 layout inflation in one
     *  switch: margins applied attribute by attribute (no marginHorizontal /
     *  Vertical), the padding shorthand replayed FIRST (per-side wins),
     *  fillViewport ignored, TableLayout writing each cell's lp.width, and
     *  VectorDrawable `@dimen` floats left at their defaults. Each of those is
     *  an AOSP-precedence fix that re-lays-out many apps, so the fleet is
     *  measured OFF vs ON (debug/recap_ab.sh) before and after landing. */
    static final boolean LAYOUT_AOSP_1643 =
        !"0".equals(System.getenv("MACAPK_LAYOUT_AOSP_1643"));

    private static final java.util.Set<String> MARGIN_ATTRS = new java.util.HashSet<>(
        java.util.Arrays.asList("layout_margin", "layout_marginHorizontal",
            "layout_marginVertical", "layout_marginLeft", "layout_marginTop",
            "layout_marginRight", "layout_marginBottom", "layout_marginStart",
            "layout_marginEnd"));

    /** AOSP ViewGroup.MarginLayoutParams(Context, AttributeSet) (android14
     *  ViewGroup.java:8452-8521) plus its LTR resolveLayoutDirection:
     *  layout_margin >= 0 sets all four sides and NOTHING else is read;
     *  otherwise layout_marginHorizontal >= 0 beats marginLeft/Right and
     *  layout_marginVertical >= 0 beats marginTop/Bottom, and a defined
     *  marginStart/End then overrides left/right. v1.0.1643: Horizontal /
     *  Vertical were never handled -- OpenCalculator's keypad style sets
     *  them (5dp/7dp), so its buttons filled their cells as touching pills
     *  where a device draws spaced circles. An undefined side leaves the
     *  LayoutParams' existing value alone. */
    private void applyMargins(ViewGroup.LayoutParams lp, java.util.Map<String, String> m) {
        if (!(lp instanceof ViewGroup.MarginLayoutParams mlp) || m.isEmpty()) return;
        Integer all = marginPx(m, "layout_margin");
        if (all != null && all >= 0) {
            mlp.setMargins(all, all, all, all);
            return;
        }
        Integer h = marginPx(m, "layout_marginHorizontal");
        Integer v = marginPx(m, "layout_marginVertical");
        boolean leftUndef = false, rightUndef = false;   // AOSP LEFT/RIGHT_MARGIN_UNDEFINED_MASK
        if (h != null && h >= 0) {
            mlp.leftMargin = h;
            mlp.rightMargin = h;
        } else {
            Integer l = marginPx(m, "layout_marginLeft");
            Integer r = marginPx(m, "layout_marginRight");
            leftUndef = (l == null);
            rightUndef = (r == null);
            if (l != null) mlp.leftMargin = l;
            if (r != null) mlp.rightMargin = r;
        }
        if (v != null && v >= 0) {
            mlp.topMargin = v;
            mlp.bottomMargin = v;
        } else {
            Integer t = marginPx(m, "layout_marginTop");
            Integer b = marginPx(m, "layout_marginBottom");
            if (t != null) mlp.topMargin = t;
            if (b != null) mlp.bottomMargin = b;
        }
        // AOSP doResolveMargins (android14 ViewGroup.java:8764-8797), LTR.
        Integer st = marginPx(m, "layout_marginStart");
        Integer en = marginPx(m, "layout_marginEnd");
        if (st == null && en == null) return;            // !isMarginRelative()
        if (macapkRtlCompatibilityMode()) {
            // targetSdk < 17 or no supportsRtl: start/end only fill a side with
            // no left/right value (marginHorizontal counts as a value).
            if (leftUndef && st != null) mlp.leftMargin = st;
            if (rightUndef && en != null) mlp.rightMargin = en;
        } else {
            // A defined relative margin wins; the side without one resolves to
            // DEFAULT_MARGIN_RESOLVED (0), even over marginRight/Horizontal.
            mlp.leftMargin = (st != null) ? st : 0;
            mlp.rightMargin = (en != null) ? en : 0;
        }
    }

    /** View.isRtlCompatibilityMode, from the inflating context: targetSdk <
     *  JELLY_BEAN_MR1 or the app does not declare supportsRtl. */
    private boolean macapkRtlCompatibilityMode() {
        try {
            android.content.pm.ApplicationInfo ai =
                (context != null) ? context.getApplicationInfo() : null;
            if (ai == null) return true;
            return ai.targetSdkVersion < 17
                || (ai.flags & android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL) == 0;
        } catch (Throwable t) {
            return true;
        }
    }

    private Integer marginPx(java.util.Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) return null;
        try { return parseDimensionPxInt(v); } catch (Throwable t) { return null; }
    }

    private void applyLayoutAttribute(ViewGroup.LayoutParams lp,
                                        String attr, String value) {
        if (lp == null || attr == null || value == null) return;
        try {
            switch (attr) {
                case "layout_width":  lp.width  = parseDimensionOrEnum(value); return;
                case "layout_height": lp.height = parseDimensionOrEnum(value); return;
            }
            if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
                switch (attr) {
                    case "layout_margin": {
                        Integer v = parseDimensionPxInt(value);
                        if (v != null) mlp.setMargins(v, v, v, v);
                        return;
                    }
                    case "layout_marginLeft":
                    case "layout_marginStart": {
                        Integer v = parseDimensionPxInt(value);
                        if (v != null) mlp.leftMargin = v;
                        return;
                    }
                    case "layout_marginTop": {
                        Integer v = parseDimensionPxInt(value);
                        if (v != null) mlp.topMargin = v;
                        return;
                    }
                    case "layout_marginRight":
                    case "layout_marginEnd": {
                        Integer v = parseDimensionPxInt(value);
                        if (v != null) mlp.rightMargin = v;
                        return;
                    }
                    case "layout_marginBottom": {
                        Integer v = parseDimensionPxInt(value);
                        if (v != null) mlp.bottomMargin = v;
                        return;
                    }
                }
            }
            if (lp instanceof android.widget.RelativeLayout.LayoutParams rlp) {
                applyRelativeLayoutAttribute(rlp, attr, value);
                return;
            }
            // v1.0.131: ConstraintLayout's LayoutParams has 13 public
            // int fields (leftToLeft, leftToRight, ..., startToEnd,
            // endToEnd, baselineToBaseline) plus a bunch of float bias
            // / chain-style fields. The APK's androidx.constraintlayout
            // .widget.ConstraintLayout$LayoutParams.<init>(set) READS
            // these via obtainStyledAttributes(R.styleable.ConstraintLayout_Layout)
            // -- which in our framework returns mostly-empty values
            // because the styleable table's clinit was rewritten by
            // ClassFileRewriter (the broken-clinit repair empties the
            // resource-id mapping).
            //
            // Symptom: list-item card layouts (`app_card_list_item.xml`
            // and similar with `app:layout_constraintStart_toEndOf=
            // @id/icon`) had every constraint field at UNSET=-1, so the
            // CL solver fell back to "everything at 0,0" -- text
            // overlapped the icon, card height collapsed to the icon
            // height. Big-tile cards happened to work because their
            // constraint patterns coincide with the few attrs that DID
            // resolve.
            //
            // Fix: walk the layout_constraint* attrs ourselves at LP-
            // build time and reflectively assign the matching LP int
            // field. Generic for any APK using ConstraintLayout --
            // the field names + UNSET sentinel are stable AOSP-public
            // surface. The reflection cost is paid once per child at
            // inflate (not per measure pass), so it's negligible.
            // "layout_constrain" covers both the anchor/chain attrs
            // (layout_constraint*) AND layout_constrainedWidth/Height (note
            // the 'ed' -- it does NOT start with "layout_constraint").
            if (attr.startsWith("layout_constrain")
                    && isConstraintLayoutLayoutParams(lp)) {
                applyConstraintLayoutAttribute(lp, attr, value);
                return;
            }
            // v1.0.101: layout_gravity. Used by FrameLayout, CoordinatorLayout
            // (for FAB / SnackBar / dialog positioning), LinearLayout (per-
            // child alignment within the major axis), DrawerLayout (left/right
            // drawer side). Stored on the universal LayoutParams.macapkGravity
            // field so a custom-ViewGroup-bundled-by-the-APK that uses
            // MarginLayoutParams still ferries the bit through. The post-
            // layout pass in ViewCanvasDriver reads it after onLayout and
            // re-positions children accordingly. Also written to
            // FrameLayout.LayoutParams.gravity when the parent IS a real
            // FrameLayout so AOSP-shaped code that reads `lp.gravity` sees
            // the right value too.
            if ("layout_gravity".equals(attr)) {
                int g = android.view.Gravity.parse(value);
                lp.macapkGravity = g;
                if (lp instanceof android.widget.FrameLayout.LayoutParams flp) {
                    flp.gravity = g;
                } else if (lp instanceof android.widget.LinearLayout.LayoutParams llp) {
                    // Tier 3 M5: LinearLayout.layoutVertical/Horizontal reads
                    // lp.gravity (per-child cross-axis alignment) and falls back
                    // to the container's gravity when it is < 0. Mirror what we
                    // already do for FrameLayout so the faithful onLayout sees it.
                    llp.gravity = g;
                }
                return;
            }
            // Tier 3 M5: android:layout_weight -> LinearLayout.LayoutParams.weight.
            // Without this the faithful LinearLayout.measureVertical/Horizontal
            // weight-distribution pass sees weight=0 for every child and the
            // 0dp+weight "fill the remaining space, split by weight" idiom (the
            // dominant LinearLayout layout pattern) collapses to wrap_content.
            if ("layout_weight".equals(attr)) {
                if (lp instanceof android.widget.LinearLayout.LayoutParams llp) {
                    try { llp.weight = Float.parseFloat(value.trim()); }
                    catch (NumberFormatException nfe) {
                        // A reference ("@integer/x", resolved per configuration:
                        // WhatsApp's home pane is @integer = 1, 40 on sw600dp).
                        // parseFloat threw on it, so the weight stayed 0 and the
                        // whole home screen measured 0px wide.
                        Float ref = macapkResolveNumberRef(value);
                        if (ref != null) llp.weight = ref;
                    }
                }
                return;
            }
        } catch (Throwable ignored) {}
    }

    /** The number a resource reference ("@integer/x", "@dimen/x", apktool's
     *  "@integer/APKTOOL_RENAMED_0x7f0c003d") stands for, or null. */
    private Float macapkResolveNumberRef(String v) {
        if (v == null) return null;
        v = v.trim();
        if (!v.startsWith("@")) return null;
        try {
            android.content.res.Resources r = (context != null) ? context.getResources() : null;
            if (r == null) return null;
            int id = 0;
            int k = v.indexOf("APKTOOL_RENAMED_0x");
            if (k >= 0) {
                id = (int) Long.parseLong(v.substring(k + 18), 16);
            } else {
                String body = v.substring(1);
                int slash = body.indexOf('/');
                if (slash > 0) {
                    String type = body.substring(0, slash), name = body.substring(slash + 1);
                    String pkg = context.getPackageName();
                    if (type.startsWith("android:")) { type = type.substring(8); pkg = "android"; }
                    id = r.getIdentifier(name, type, pkg);
                }
            }
            if (id == 0) return null;
            try { return (float) r.getInteger(id); } catch (Throwable ignored) {}
            android.util.TypedValue tv = new android.util.TypedValue();
            r.getValue(id, tv, true);
            if (tv.type == android.util.TypedValue.TYPE_FLOAT) return tv.getFloat();
            if (tv.type >= android.util.TypedValue.TYPE_FIRST_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_INT) return (float) tv.data;
            if (tv.string != null) return Float.parseFloat(tv.string.toString().trim());
        } catch (Throwable ignored) {}
        return null;
    }

    /** Apply a RelativeLayout-specific `layout_*` attribute. The
     *  AOSP rule index constants (RelativeLayout.ALIGN_PARENT_BOTTOM
     *  etc.) are stable; using them keeps the rule mapping uniform
     *  with whatever addRule() the app code might call directly. */
    private void applyRelativeLayoutAttribute(android.widget.RelativeLayout.LayoutParams rlp,
                                                String attr, String value) {
        // Diagnostic: confirm we're seeing layout_* attrs.
        if (attr != null && attr.startsWith("layout_align")
                          || (attr != null && attr.startsWith("layout_above"))
                          || (attr != null && attr.startsWith("layout_below"))) {
            NativeLog.i(TAG, "rlpAttr: " + attr + "=" + value);
        }
        // Parent-anchor rules: "true"/"false" values.
        boolean isTrue = "true".equals(value);
        switch (attr) {
            case "layout_alignParentTop":
                if (isTrue) rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
                return;
            case "layout_alignParentBottom":
                if (isTrue) rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
                return;
            case "layout_alignParentLeft":
            case "layout_alignParentStart":
                if (isTrue) rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
                return;
            case "layout_alignParentRight":
            case "layout_alignParentEnd":
                if (isTrue) rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
                return;
            case "layout_centerInParent":
                if (isTrue) rlp.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
                return;
            case "layout_centerHorizontal":
                if (isTrue) rlp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
                return;
            case "layout_centerVertical":
                if (isTrue) rlp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
                return;
        }
        // Sibling-relative rules: value is an `@id/foo` reference.
        int subject = parseResourceId(value);
        if (subject == 0) return;
        switch (attr) {
            case "layout_above":
                rlp.addRule(android.widget.RelativeLayout.ABOVE, subject); return;
            case "layout_below":
                rlp.addRule(android.widget.RelativeLayout.BELOW, subject); return;
            case "layout_toLeftOf":
            case "layout_toStartOf":
                rlp.addRule(android.widget.RelativeLayout.LEFT_OF, subject); return;
            case "layout_toRightOf":
            case "layout_toEndOf":
                rlp.addRule(android.widget.RelativeLayout.RIGHT_OF, subject); return;
            case "layout_alignTop":
                rlp.addRule(android.widget.RelativeLayout.ALIGN_TOP, subject); return;
            case "layout_alignBottom":
                rlp.addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, subject); return;
            case "layout_alignLeft":
            case "layout_alignStart":
                rlp.addRule(android.widget.RelativeLayout.ALIGN_LEFT, subject); return;
            case "layout_alignRight":
            case "layout_alignEnd":
                rlp.addRule(android.widget.RelativeLayout.ALIGN_RIGHT, subject); return;
            case "layout_alignBaseline":
                rlp.addRule(android.widget.RelativeLayout.ALIGN_BASELINE, subject); return;
        }
    }

    /** "match_parent" -> -1; "wrap_content" -> -2; "fill_parent" -> -1
     *  (legacy alias for match_parent); a dimension like "16dp" or
     *  "@dimen/foo" -> pixel int; numeric "120" -> 120. */
    private int parseDimensionOrEnum(String s) {
        if (s == null) return ViewGroup.LayoutParams.WRAP_CONTENT;
        if ("match_parent".equals(s) || "fill_parent".equals(s)) {
            return ViewGroup.LayoutParams.MATCH_PARENT;  // -1
        }
        if ("wrap_content".equals(s)) {
            return ViewGroup.LayoutParams.WRAP_CONTENT;  // -2
        }
        Float dim = parseDimensionPx(s);
        if (dim != null) return macapkPixelSize(dim);
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }
    private Integer parseDimensionPxInt(String s) {
        Float f = parseDimensionPx(s);
        return f == null ? null : macapkPixelSize(f);
    }
    /** AOSP TypedValue.complexToDimensionPixelSize: round to nearest, but a
     *  non-zero size never collapses to 0 -- it becomes 1px (or -1). The
     *  getLayoutDimension / getDimensionPixelSize readers behind layout_width,
     *  padding and margins all go through it. Truncating instead turned a
     *  0.3dp divider into 0 (MATCH_CONSTRAINT in a ConstraintLayout), which
     *  stretched it over the screen and squeezed the list below it to nothing. */
    private static int macapkPixelSize(float f) {
        final int res = (int) (f >= 0 ? f + 0.5f : f - 0.5f);
        if (res != 0) return res;
        if (f == 0f) return 0;
        return f > 0 ? 1 : -1;
    }

    /** BISECT GATE (2026-09-26, default ON; docs/fleet-newpipe-vlc-residual_2026-09-26.md).
     *  applyConstraintLayoutAttribute stores layout_constraintGuide_percent as the
     *  float it is. =0 restores the pre-change dimension write, which truncated
     *  every fractional percent Guideline to 0. */
    static final boolean MACAPK_CL_GUIDE_PERCENT_FLOAT =
        !"0".equals(System.getenv("MACAPK_CL_GUIDE_PERCENT_FLOAT"));

    /** BISECT GATE (2026-09-26, default ON; lane platlayouts, gate
     *  debug/platlayout_gate.sh). `@android:dimen/x` in a dimension attribute
     *  resolves through the platform table (parseDimensionPx). =0 restores the
     *  pre-change answer, null, which dropped the attribute. */
    static final boolean MACAPK_INFLATE_FW_DIMEN =
        !"0".equals(System.getenv("MACAPK_INFLATE_FW_DIMEN"));

    /** The literal ("20.0dip") platform dimension `name` holds, or null when the
     *  platform table has no such dimen. Resources.getValue(id, v, true) chases
     *  the reference chain (ResourcesImpl.getValue -> AssetManager.getResourceValue
     *  with resolveRefs), and TypedValue.coerceToString spells the complex value
     *  with the units parseDimensionPx already converts. */
    private String macapkPlatformDimenLiteral(String name) {
        try {
            android.content.res.Resources r = (context != null) ? context.getResources() : null;
            if (r == null || name.isEmpty()) return null;
            int id = r.getIdentifier(name, "dimen", "android");
            if ((id >>> 24) != 0x01) return null;
            android.util.TypedValue tv = new android.util.TypedValue();
            r.getValue(id, tv, true);
            if (tv.type != android.util.TypedValue.TYPE_DIMENSION) return null;
            return android.util.TypedValue.coerceToString(tv.type, tv.data);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolve a @dimen NAME to its raw declared string ("2.0dp") via
     *  the ResourceIndex dimension table. Reflective so this framework
     *  class stays decoupled from com.macapk.loader. */
    private String macapkDimenRaw(String name) {
        try {
            Object ri = android.content.res.Resources.macapkGetResourceIndex();
            if (ri == null) return null;
            Object v = ri.getClass().getMethod("getDimenRaw", String.class)
                .invoke(ri, name);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    /** Resolve a THEME attribute name (e.g. "collapsingToolbarLayoutLargeSize",
     *  "actionBarSize") to its raw declared value through the active application
     *  theme cascade -- ResourceIndex.resolveAttrInTheme(applicationTheme, attr).
     *  Returns the raw value (often a `@dimen/...` reference the caller resolves
     *  next), or null when the theme doesn't define the attr. Reflective so this
     *  framework class stays decoupled from com.macapk.loader. */
    /** Map an app:barrierDirection enum value (name or int) to the Barrier
     *  type int. Values per the ConstraintLayout attrs enum. */
    private int parseBarrierDirection(String v) {
        if (v == null) return -1;
        switch (v) {
            case "left":   return 0;
            case "right":  return 1;
            case "top":    return 2;
            case "bottom": return 3;
            case "start":  return 5;
            case "end":    return 6;
            default:
                try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return -1; }
        }
    }

    /** Map an android:scaleType attribute value to the ImageView.ScaleType enum. */
    private android.widget.ImageView.ScaleType parseScaleType(String v) {
        if (v == null) return null;
        switch (v) {
            case "matrix":       return android.widget.ImageView.ScaleType.MATRIX;
            case "fitXY":        return android.widget.ImageView.ScaleType.FIT_XY;
            case "fitStart":     return android.widget.ImageView.ScaleType.FIT_START;
            case "fitCenter":    return android.widget.ImageView.ScaleType.FIT_CENTER;
            case "fitEnd":       return android.widget.ImageView.ScaleType.FIT_END;
            case "center":       return android.widget.ImageView.ScaleType.CENTER;
            case "centerCrop":   return android.widget.ImageView.ScaleType.CENTER_CROP;
            case "centerInside": return android.widget.ImageView.ScaleType.CENTER_INSIDE;
            default:             return null;
        }
    }

    private String macapkThemeAttrRaw(String attrName) {
        try {
            Object ri = android.content.res.Resources.macapkGetResourceIndex();
            if (ri == null) return null;
            Object theme = ri.getClass().getMethod("getApplicationThemeName").invoke(ri);
            if (theme == null) return null;
            Object v = ri.getClass().getMethod("resolveAttrInTheme", String.class, String.class)
                .invoke(ri, theme.toString(), attrName);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    /** Resolve an attr through a SPECIFIC style's parent cascade (not the
     *  app theme). Used by android:textAppearance, where the style ref
     *  (e.g. @style/DetailsAppTitleStyle) is the cascade root. Returns the
     *  raw declared value or null. */
    private String macapkStyleAttrRaw(String styleName, String attrName) {
        try {
            Object ri = android.content.res.Resources.macapkGetResourceIndex();
            if (ri == null || styleName == null) return null;
            Object v = ri.getClass().getMethod("resolveAttrInTheme", String.class, String.class)
                .invoke(ri, styleName, attrName);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    /** BISECT GATES (2026-09-26, default ON; lane B-inflater, cluster B of
     *  docs/widget-fidelity-cluster_2026-09-25.md). Each restores one pre-change
     *  inflater behaviour on its own, so a fleet A/B can name which half moved a
     *  row. The offline gate debug/inflaterattr_gate.sh turns each off on its own
     *  and requires exactly that gate's rows to go back to the pre-change answers
     *  (its positive control).
     *
     *  MACAPK_INFLATE_STYLE_ATTR=0 -- `style=` accepts only `@style/X` again
     *  (macapkStyleItems returned an EMPTY map for `style="?attr"`,
     *  `style="@android:style/X"` and an id-ref style, so every View/TextView
     *  field those styles carry was dropped by the inflater's own tier-2 copy).
     *  MACAPK_INFLATE_TA_ATTR=0 -- `android:textAppearance="?attr"` is passed
     *  through as if it were a style NAME again (so it resolves to nothing and
     *  the appearance is dropped), a `style=` item's textAppearance again
     *  outranks the element's own android:textAppearance, and the fontFamily
     *  path goes back to the old answers: `@android:string/font_family_*` reads
     *  as no family, and an appearance with no resolvable family resets the
     *  view's face to DEFAULT (see macapkResolveFontFamilyValue and
     *  applyTextAppearance). That hardening rides on this gate because B2 is what
     *  made the path fleet-wide; without it B2 alone erased the constructor's
     *  face on 59 VLC views. */
    static final boolean MACAPK_INFLATE_STYLE_ATTR =
        !"0".equals(System.getenv("MACAPK_INFLATE_STYLE_ATTR"));
    static final boolean MACAPK_INFLATE_TA_ATTR =
        !"0".equals(System.getenv("MACAPK_INFLATE_TA_ATTR"));
    /** Children are built with the parent view's context, and the factories and
     *  an unknown-class placeholder get the same per-view context (AOSP
     *  rInflateChildren / createViewFromTag); =0 restores the inflate context
     *  for every view. */
    static final boolean MACAPK_INFLATE_PARENT_CONTEXT =
        !"0".equals(System.getenv("MACAPK_INFLATE_PARENT_CONTEXT"));
    /** MACAPK_TRACE_INFLATE_STYLE=1: one line per `style=` / textAppearance
     *  reference the inflater resolves (the raw ref, what it resolved to, and how
     *  many items the flattened chain carried). The live falsifier for cluster B
     *  reads this, because dialog windows are not in the tree dump. */
    private static final boolean MACAPK_TRACE_INFLATE_STYLE =
        System.getenv("MACAPK_TRACE_INFLATE_STYLE") != null;

    /** The Theme of a Context, or null when there is none (or it throws). */
    private static android.content.res.Resources.Theme macapkThemeOf(Context c) {
        if (c == null) return null;
        try { return c.getTheme(); } catch (Throwable t) { return null; }
    }

    /** The attribute NAME a `?` reference names, in ResourceIndex's key
     *  convention: "?attr/X" and "?X" -> "X" (the app namespace),
     *  "?android:attr/X" and "?android:X" -> "android:X". A numeric
     *  "?0x7f040042" (a compiled layout parsed without a name table) is named
     *  through the resource table, and a framework "?0x0101xxxx" through the
     *  frozen public attr table. Null when the reference cannot be named. */
    private static String macapkAttrRefName(String ref) {
        if (ref == null || ref.length() < 2 || ref.charAt(0) != '?') return null;
        String body = ref.substring(1).trim();
        if (body.startsWith("attr/")) return body.substring(5);
        if (body.startsWith("android:attr/")) return "android:" + body.substring(13);
        if (body.startsWith("0x") || body.startsWith("0X")) {
            try {
                int id = (int) Long.parseLong(body.substring(2), 16);
                if ((id >>> 24) == 0x01) {
                    String fw = com.macapk.loader.FrameworkAttrIds.name(id);
                    return (fw == null || fw.isEmpty()) ? null : "android:" + fw;
                }
                Object ri = android.content.res.Resources.macapkGetResourceIndex();
                if (ri == null) return null;
                Object tn = ri.getClass().getMethod("macapkTypeNameOf", int.class).invoke(ri, id);
                if (tn instanceof String[] a && a.length >= 2 && "attr".equals(a[0])) return a[1];
            } catch (Throwable ignored) {}
            return null;
        }
        return body.isEmpty() ? null : body;
    }

    /** Follow a `?attr` reference through `theme` until it lands on something
     *  that is not an attribute reference; null when a hop is unanswered.
     *
     *  AOSP resolves the `style` attribute of an XML tag exactly this way:
     *  AttributeResolution.cpp (android14-release) GetXmlStyleBag :90-115,
     *      if (value.dataType == Res_value::TYPE_ATTRIBUTE) {
     *        // Resolve the attribute with out theme.
     *        if (... result = theme->GetAttribute(value.data)) {
     *          return theme->GetAssetManager()->ResolveBag(*result);
     *  and the theme is the one obtainStyledAttributes runs against, i.e. the
     *  VIEW's context theme (the `android:theme` overlay when the element or an
     *  ancestor carries one; the same two-anchor rule as v1.0.897). Each hop is
     *  Theme.resolveAttrRaw, which walks the theme's applied-style cascade and
     *  then the application theme -- the same per-hop lookup as
     *  Resources.Theme.macapkFollowThemeAttrRef, the TypedArray tier-2 path that
     *  already resolved `style="?x"` (ATTRSTYLE, v1.0.968). The two are NOT
     *  identical, and the differences are deliberate here: (1) the hop cap is
     *  Theme::GetAttribute's kMaxIterations (20, AssetManager2.cpp
     *  android14-release :1503-1523), where that loop stops at 4; (2) a
     *  `?android:attr/X` keeps its namespace here ("android:X"), where that loop
     *  strips it to the app attr "X". The binary layout parser spells platform
     *  refs `?android:X`, which both keep namespaced, so they agree on every ref
     *  the corpus actually contains; a chain longer than four hops, or a
     *  hand-written `?android:attr/X`, is where they would part. With no Theme
     *  the application theme answers. */
    private static String macapkChaseAttrRef(String ref, android.content.res.Resources.Theme theme) {
        if (ref == null) return null;
        String cur = ref.trim();
        for (int hop = 0; hop < 20 && cur.startsWith("?"); hop++) {
            String attr = macapkAttrRefName(cur);
            if (attr == null) return null;
            String raw = null;
            try {
                if (theme != null) {
                    raw = theme.resolveAttrRaw(attr);
                } else {
                    Object ri = android.content.res.Resources.macapkGetResourceIndex();
                    Object app = ri == null ? null
                        : ri.getClass().getMethod("getApplicationThemeName").invoke(ri);
                    if (app != null) {
                        Object v = ri.getClass().getMethod("resolveAttrInTheme",
                            String.class, String.class).invoke(ri, app.toString(), attr);
                        raw = v == null ? null : v.toString();
                    }
                }
            } catch (Throwable ignored) {}
            if (raw == null) return null;
            raw = raw.trim();
            if (raw.isEmpty() || raw.equals(cur)) return null;   // self-reference: absent
            cur = raw;
        }
        return cur.startsWith("?") ? null : cur;
    }

    /** Resolve a STYLE reference -- the value of `style=` or of
     *  `android:textAppearance` -- to a name ResourceIndex.getStyleByName and
     *  resolveAttrInTheme both accept, or null for "no style".
     *
     *    ?attr / ?android:attr   chased through `theme` (macapkChaseAttrRef)
     *    @style/X                "X", the app table
     *    @android:style/X        kept NAMESPACED. Both ResourceIndex consumers
     *                            normalise the prefix for the platform table, and
     *                            the app table cannot hold a key spelled that way,
     *                            so an app that happens to declare its own style
     *                            with a platform style's name (bare "X") can never
     *                            capture a platform reference. The bare form that
     *                            styleNameForIdRefPublic returns loses that.
     *    @0x7f.. / @0x0101..     named through the resource table; a platform id
     *                            is namespaced the same way.
     *    @null / @empty          no style.
     *
     *  AOSP accepts a style attribute only as TYPE_REFERENCE or TYPE_ATTRIBUTE
     *  (GetXmlStyleBag, cited above), which is exactly this set. */
    private String macapkStyleNameOfRef(String ref, android.content.res.Resources.Theme theme) {
        if (ref == null) return null;
        String cur = ref.trim();
        if (cur.startsWith("?")) cur = macapkChaseAttrRef(cur, theme);
        if (cur == null || cur.length() < 2 || cur.charAt(0) != '@') return null;
        if (cur.equals("@null") || cur.equals("@empty")) return null;
        if (cur.startsWith("@style/")) return cur.substring(7);
        if (cur.startsWith("@android:style/")) return cur;
        if (cur.startsWith("@+")) return null;
        String body = cur.substring(1);
        int id;
        try {
            id = (body.startsWith("0x") || body.startsWith("0X"))
                ? (int) Long.parseLong(body.substring(2), 16)
                : (int) Long.parseLong(body, 10);
        } catch (NumberFormatException e) {
            return null;   // "@dimen/x" and friends: not a style
        }
        try {
            Object ri = android.content.res.Resources.macapkGetResourceIndex();
            if (ri == null) return null;
            Object n = ri.getClass().getMethod("styleNameForIdRefPublic", String.class)
                .invoke(ri, "@0x" + Integer.toHexString(id));
            if (n == null) return null;
            String name = n.toString();
            return ((id >>> 24) == 0x01) ? "@android:style/" + name : name;
        } catch (Throwable ignored) { return null; }
    }

    /** Resolve a style id-ref ("@7f13012e" / "@0x7f13012e") to its style
     *  NAME via the resource table. android:textAppearance from a
     *  binary-parsed layout arrives as an id ref; named layouts (apktool
     *  text XML) arrive as "@style/Name". Returns the bare style name.
     *
     *  Under MACAPK_INFLATE_TA_ATTR (default ON) the reference goes through
     *  macapkStyleNameOfRef with the VIEW's theme instead. The old body below
     *  returned a `?attr` reference UNCHANGED ("already a bare name"), so
     *  applyTextAppearance asked resolveAttrInTheme for the style
     *  "?android:textAppearanceMedium", got null for every attribute, and every
     *  `android:textAppearance="?..."` in the corpus was dropped. MEASURED
     *  (offline, the apps' own layouts through this inflater): sgtpuzzles'
     *  chooser headers (`?android:textAppearanceMedium`, 18sp) drew at 28px, not
     *  36px; bobball's statistics labels (`?android:textAppearanceLarge`, 22sp)
     *  at 28px, not 44px. AOSP reads the attribute with getResourceId on a
     *  TypedArray, which resolves the `?` through the theme (TextView.java
     *  android14-release :1196-1206, `theme.obtainStyledAttributes(attrs,
     *  TextViewAppearance, ...)` then `theme.obtainStyledAttributes(ap,
     *  TextAppearance)`). */
    private String styleNameFromRef(String ref, android.content.res.Resources.Theme theme) {
        if (MACAPK_INFLATE_TA_ATTR) {
            String n = macapkStyleNameOfRef(ref, theme);
            if (MACAPK_TRACE_INFLATE_STYLE) {
                NativeLog.i(TAG, "textAppearance " + ref + " -> " + n);
            }
            return n;
        }
        return styleNameFromRef(ref);
    }
    private String styleNameFromRef(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        if (ref.startsWith("@style/")) return ref.substring(7);
        if (ref.startsWith("@")) {
            try {
                Object ri = android.content.res.Resources.macapkGetResourceIndex();
                if (ri != null) {
                    Object n = ri.getClass().getMethod("styleNameForIdRefPublic", String.class)
                        .invoke(ri, ref);
                    if (n != null) return n.toString();
                }
            } catch (Throwable ignored) {}
            return null;
        }
        return ref; // already a bare name
    }

    /** Apply android:textAppearance to a TextView: resolve the referenced
     *  style's textSize / textColor / textStyle through its parent cascade
     *  and set them. AOSP applies the textAppearance as the BASE, then
     *  direct XML attrs override -- so applyAttributes calls this BEFORE
     *  the per-attribute loop. F-Droid's AppDetails title
     *  (DetailsAppTitleStyle -> 18sp, parent Material3 TitleLarge), author
     *  / summary / description (AppCompat Body1 = 16sp) all size through
     *  here; without it every TextView rendered at the default ~14sp, so
     *  the whole card read too small + too compressed vs the emulator. */
    private void applyTextAppearance(View view, String styleRef) {
        applyTextAppearance(view, styleRef, true);
    }

    /** BISECT GATE (2026-09-26, default ON). A TextView whose element or style
     *  declares android:textColor keeps its constructor's colour over the
     *  textAppearance's (see the call in applyAttributes). =0 restores the
     *  unconditional appearance colour. */
    static final boolean MACAPK_TA_DECLARED_TEXTCOLOR =
        !"0".equals(System.getenv("MACAPK_TA_DECLARED_TEXTCOLOR"));

    private void applyTextAppearance(View view, String styleRef, boolean applyColor) {
        if (!(view instanceof android.widget.TextView)) return;
        final android.content.res.Resources.Theme viewTheme = macapkThemeOf(view.getContext());
        String styleName = styleNameFromRef(styleRef, viewTheme);
        if (styleName == null) return;
        android.widget.TextView tv = (android.widget.TextView) view;
        String sz = macapkStyleAttrRaw(styleName, "textSize");
        if (sz != null) {
            Float px = parseDimensionPx(sz);
            // parseDimensionPx already converted sp/dp -> px. setTextSize(float)
            // is the 1-ARG overload == setTextSize(COMPLEX_UNIT_SP, x), which
            // would re-apply the sp->px scale -> DOUBLE-sized text. Apply as PX.
            // (Bug: Calculator's DisplayLarge result rendered 228px == 57sp*2*2
            // instead of 57sp*2=114px; every style-textAppearance text was 2x.)
            // Raw, as AOSP's constructor applies a textAppearance size
            // (setRawTextSize), never through the overridable setTextSize.
            if (px != null && px > 0) {
                String v = sz.trim();
                int unit = v.endsWith("sp") ? android.util.TypedValue.COMPLEX_UNIT_SP
                    : (v.endsWith("dp") || v.endsWith("dip")) ? android.util.TypedValue.COMPLEX_UNIT_DIP
                    : android.util.TypedValue.COMPLEX_UNIT_PX;
                tv.macapkSetRawTextSize(px, unit);
            }
        }
        String col = macapkStyleAttrRaw(styleName, "textColor");
        if (applyColor && col != null) {
            // Under MACAPK_INFLATE_TA_ATTR the colour resolves against the VIEW's
            // theme, as AOSP's theme.obtainStyledAttributes(ap, TextAppearance)
            // does. It matters now that `?android:textAppearance*` lands: every
            // platform TextAppearance carries `textColor=?android:textColorPrimary`
            // (or Secondary), and under an `android:theme` overlay (a dark toolbar
            // on a light activity) the application theme gives the WRONG polarity.
            // The theme-aware overload walks the view theme's cascade first and
            // then makes the SAME static call (see its doc comment), so it can
            // only answer where the static path answered nothing or answered for
            // a theme the view is not actually using.
            Integer c = MACAPK_INFLATE_TA_ATTR ? macapkResolveColorValue(col, viewTheme)
                                               : macapkResolveColorValue(col);
            if (c != null) tv.setTextColor(c);
        }
        // v1.0.935: fontFamily travels with the appearance. Material 3's
        // typescale styles (TextAppearance.Material3.LabelLarge and friends)
        // carry android:fontFamily rather than setting it on each view, so a
        // textAppearance that resolved size + colour but not family still
        // rendered every label at weight 400.
        String fam = macapkStyleAttrRaw(styleName, "fontFamily");
        android.graphics.Typeface fres = fam == null ? null : macapkResolveFontResource(fam);
        String fname = (fam == null || fres != null) ? null : macapkResolveFontFamilyValue(fam);
        if (fres != null) {
            tv.setMacapkFontResource(fres);
        } else if (fname != null || (fam != null && !MACAPK_INFLATE_TA_ATTR)) {
            tv.setMacapkFontFamily(fname);
        } else if (MACAPK_INFLATE_TA_ATTR) {
            // The appearance names NO family we can resolve (none in its chain,
            // or one our resolver cannot read). AOSP never lets that ERASE a
            // family: its TextView constructor reads the appearance first and
            // then lets the view's own attrs, style, default style and theme
            // replace mFontFamily (TextView.java android14-release :1196-1213,
            // then readTextAppearance(..., styleArray=true)), and a subclass
            // constructor such as AppCompatTextHelper runs after both. We apply
            // the appearance AFTER the whole constructor chain, so the face the
            // view already carries is the best stand-in for "the family from
            // elsewhere": keep it as the BASE, and let the appearance's
            // textStyle / textFontWeight below apply on top of it. The old path
            // called setMacapkFontFamily(null) for an unresolvable family, and
            // setMacapkTextStyle re-derived from a null family for an absent one
            // (the platform root `TextAppearance` carries textStyle=normal, so
            // every platform chain reached it); both replaced the constructor's
            // face with DEFAULT. A view with no face yet (a framework TextView,
            // whose constructor reads nothing) is unaffected: there is nothing to
            // keep, and the style applies to DEFAULT exactly as before.
            // MACAPK_INFLATE_TA_ATTR=0 restores the old path.
            android.graphics.Typeface cur = tv.getTypeface();
            if (cur != null) tv.setMacapkFontResource(cur);
            if (MACAPK_TRACE_INFLATE_STYLE && fam != null) {
                NativeLog.i(TAG, "textAppearance " + styleName + " fontFamily " + fam
                    + " unresolved; kept the constructor's face " + cur);
            }
        }
        String fw = macapkStyleAttrRaw(styleName, "textFontWeight");
        if (fw != null) {
            try { tv.setMacapkFontWeight(Integer.parseInt(fw.trim())); }
            catch (NumberFormatException ignored) {}
        }
        String style = macapkStyleAttrRaw(styleName, "textStyle");
        if (style != null) tv.setMacapkTextStyle(parseTextStyle(style));
    }

    /** android:textStyle name/flags -> Typeface style bits. */
    private int parseTextStyle(String v) {
        if (v == null) return 0;
        int s = 0;
        if (v.contains("bold")) s |= android.graphics.Typeface.BOLD;
        if (v.contains("italic")) s |= android.graphics.Typeface.ITALIC;
        return s;
    }

    /** v1.0.93: when LayoutInflater substitutes a placeholder View for
     *  an un-instantiable widget (BottomNavigationView, MaterialToolbar,
     *  AppBarLayout, etc.), give the placeholder a sensible minimum
     *  size so wrap_content-anchored layouts don't collapse around it.
     *  RelativeLayout's wrap_content -> bottom-anchored case (F-Droid's
     *  BottomNavigationView with `android:layout_alignParentBottom="true"`
     *  + `android:layout_height="wrap_content"`) reads the placeholder's
     *  measured height when computing the top edge. Without a min size,
     *  measure -> 0 -> the placeholder fills the whole parent
     *  vertically, and any `layout_above` sibling collapses to height 0.
     *
     *  Sizes track Material 3 component spec at xhdpi (2x density):
     *    BottomNavigationView: 56dp = 112px
     *    Toolbar / ActionBar / AppBarLayout: 56dp = 112px
     *    TabLayout: 48dp = 96px
     *    LinearProgressIndicator / ProgressBar (horizontal): 4dp = 8px
     *    Other Material widgets: 48dp = 96px (single-line button height)
     *  Non-Material placeholders get no override (default View min size). */
    private void applyKnownWidgetIntrinsicSize(View placeholder, String tagName) {
        applyKnownWidgetIntrinsicSize(placeholder, tagName, true);
    }
    private void applyKnownWidgetIntrinsicSize(View placeholder, String tagName, boolean tintAllowed) {
        if (placeholder == null || tagName == null) return;
        // Match by simple class name (the last dot-segment).
        String simple = tagName;
        int dot = tagName.lastIndexOf('.');
        if (dot >= 0) simple = tagName.substring(dot + 1);
        int minH = 0;
        switch (simple) {
            case "BottomNavigationView":
            case "NavigationBarView":
            case "MaterialToolbar":
            case "Toolbar":
            case "ActionBar":
            case "ActionBarContainer":
            case "AppBarLayout":
            case "CollapsingToolbarLayout":
                minH = 112;
                break;
            case "TabLayout":
                minH = 96;
                break;
            case "LinearProgressIndicator":
            case "ProgressBar":
                // Some apps use vertical progress; default to thin
                // horizontal-orientation height.
                minH = 8;
                break;
            case "CircularProgressIndicator":
                minH = 96;  // 48dp wheel
                break;
            case "BadgeDrawable":
                minH = 32;  // 16dp badge
                break;
            default:
                // Other Material widget PLACEHOLDERS get a single-line
                // height so they occupy SOME space, but not so much
                // that they push everything off-screen.
                //
                // lane layout, 2026-08-02: gated on `!tintAllowed == false`,
                // i.e. PLACEHOLDERS ONLY. This blanket 48dp floor applied to
                // REAL widgets too, and it catches every
                // `com.google.android.material.*` / `androidx.appcompat.widget.*`
                // tag -- including MaterialTextView, which AOSP measures to its
                // TEXT (no default minHeight at all). Measured: DeskClock's
                // single-line 18/16sp header MaterialTextViews occupied ~97-100px
                // instead of ~46px, so its title->slogan->version gaps read
                // 71/69/57px against the emulator's 19/16/28px, while the
                // multi-line features TextView (already taller than the floor)
                // was unaffected -- the exact fingerprint of a min-height floor.
                // The NAMED rows above keep their floors: they exist so a
                // RelativeLayout anchoring off an un-instantiable widget does
                // not collapse, and tonight's View-ctor minHeight read now
                // supplies the real value for widgets that DO instantiate.
                // See docs/canvas-ab-analyst-report_2026-08-02.md item 3.
                if (!tintAllowed) break;   // real widget: never floor it
                if (tagName.startsWith("com.google.android.material.")
                  || tagName.startsWith("androidx.appcompat.widget.")) {
                    minH = 96;
                }
                break;
        }
        if (minH > 0) {
            // lane layout, 2026-08-01: NEVER LOWER an existing minimum. This
            // hint runs AFTER instantiation (inflateElement line ~513), and a
            // REAL widget's constructor may have just set the correct minimum
            // from its own default style -- measured: BottomNavigationView's
            // ctor chain sets minHeight 160px (M3 80dp, via the View defStyle
            // read + the dimen ref-chain chase), and this v1.0.95-era hint
            // then stomped it back to 112px (M2 56dp), pinning the bar at the
            // wrong height no matter what upstream resolved. The hint's
            // purpose (placeholders + widgets whose init produced nothing)
            // only needs the floor when NOTHING is set yet.
            if (placeholder.getMinimumHeight() < minH) {
                placeholder.setMinimumHeight(minH);
            }
        }
        // v1.0.93: tint the placeholder so its presence on screen is
        // observable. Without a background, every Material widget
        // placeholder is a zero-pixel region of opaque-white canvas;
        // the layout structure is invisible to the user even though
        // the activity reached steady state. A muted gray (#E0E0E0
        // ~= Material outline color) is unobtrusive but distinguishes
        // "the widget would be here on real Android" from the clear
        // background.
        //
        // v1.0.95: only paint the visible-tint background when this
        // is actually a placeholder (the widget failed to instantiate
        // and we want the user to see "something would be here"). A
        // real BottomNavigationView etc. has its own
        // theme-backgroundDrawable; painting an opaque gray over it
        // would hide the genuine Material chrome.
        if (minH > 0 && tintAllowed) {
            // Bottom-nav-shaped placeholders get a slightly darker tint
            // so they stand out as the navigation strip.
            int tint = 0xFFE0E0E0;
            String l = simple == null ? "" : simple;
            if ("BottomNavigationView".equals(l)
                || "NavigationBarView".equals(l)
                || "AppBarLayout".equals(l)
                || "Toolbar".equals(l)
                || "MaterialToolbar".equals(l)) {
                tint = 0xFFEEEEEE;
            }
            placeholder.setBackgroundColor(tint);
        }
    }

    /** Instantiate a View by tag name. Order matches AOSP
     *  LayoutInflater.createViewFromTag:
     *    1. If name contains '.', it's fully qualified (custom view)
     *    2. Otherwise try android.widget.<Name>, android.view.<Name>,
     *       android.webkit.<Name> -- the standard prefixes AOSP uses
     *  Every view has a (Context) ctor; we don't pass AttributeSet
     *  yet since our attrs are read from DOM, not AttributeSet. */
    /** v1.0.98 diag: walk tree and append every view's id to sb. */
    private static void collectIds(View v, StringBuilder sb) {
        if (v == null) return;
        int id = v.getId();
        sb.append(v.getClass().getSimpleName()).append("#0x")
          .append(Integer.toHexString(id)).append(" ");
        if (v instanceof android.view.ViewGroup vg) {
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) collectIds(vg.getChildAt(i), sb);
        }
    }

    /** MACAPK_TRACE_LAYOUTREQ diag: `Class[id](req=true/false,kids=N)` per node.
     *  See the call site in inflate() for why isLayoutRequested() at inflate-end
     *  decides whether a ConstraintLayout will ever register its children with
     *  the constraint solver. Depth-capped so a deep tree stays one log line. */
    private static void dumpLayoutRequested(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 4) return;
        sb.append(v.getClass().getSimpleName());
        int id = v.getId();
        if (id != 0 && id != -1) sb.append("#0x").append(Integer.toHexString(id));
        sb.append("(req=").append(v.isLayoutRequested());
        if (v instanceof android.view.ViewGroup vg) {
            sb.append(",kids=").append(vg.getChildCount()).append(") [");
            for (int i = 0; i < vg.getChildCount() && i < 8; i++) {
                dumpLayoutRequested(vg.getChildAt(i), sb, depth + 1);
                sb.append(' ');
            }
            sb.append(']');
        } else {
            sb.append(')');
        }
    }

    /** Tier 3 M0: consult the inflater factories for this tag, in AOSP order
     *  (factory2 -> factory -> privateFactory), returning the first non-null
     *  View. The AttributeSet is the DOM-backed one so a factory can read
     *  android:name / android:id / android:tag directly (our FragmentInflaterFactory
     *  relies on this). Returns null if no factory claims the tag. */
    private View tryFactoryCreate(ViewGroup parent, String name, Element elem) {
        if (factory2 == null && factory == null && privateFactory == null) return null;
        android.util.AttributeSet attrs = new DomBackedAttributeSet(elem);
        // AOSP createViewFromTag passes the factories the SAME per-view context
        // it would construct with (android14 :1017 tryCreateView(parent, name,
        // context, attrs), after the :1003-1010 android:theme wrap), i.e. the
        // parent's context plus the element's theme. We passed the inflater's.
        final Context fctx = (MACAPK_INFLATE_PARENT_CONTEXT && inflateContext != null)
            ? inflateContext : context;
        View v = null;
        try {
            if (factory2 != null) v = factory2.onCreateView(parent, name, fctx, attrs);
            else if (factory != null) v = factory.onCreateView(name, fctx, attrs);
        } catch (Throwable t) {
            NativeLog.w(TAG, "factory.onCreateView('" + name + "') threw " + t);
        }
        if (v == null && privateFactory != null) {
            try {
                v = privateFactory.onCreateView(parent, name, fctx, attrs);
            } catch (Throwable t) {
                NativeLog.w(TAG, "privateFactory.onCreateView('" + name + "') threw " + t);
            }
        }
        return v;
    }

    // ---- AOSP's AppCompatViewInflater rewrite -------------------------------
    // Real Android does NOT hand an AppCompat app a plain android.widget.ImageView
    // for a bare <ImageView> tag. AppCompatDelegateImpl installs
    // AppCompatViewInflater as the LayoutInflater factory, and it REWRITES a fixed
    // set of bare tags to their androidx.appcompat.widget.AppCompat* subclasses
    // before instantiating. We never did, so any app that inflates a bare tag and
    // then casts the result -- which AppCompat code does constantly, because on a
    // real device the cast always holds -- died in onCreate:
    //
    //   ClassCastException: android.widget.ImageView cannot be cast to
    //   androidx.appcompat.widget.AppCompatImageView
    //
    // MEASURED on Fossify Gallery, where that throw lands inside a Kotlin
    // viewBinding() lazy delegate -- normally the FIRST thing onCreate touches --
    // so ActivityHost caught it and every later line of onCreate, including the
    // media scan, never ran. It is not a Fossify quirk; it is every AppCompat app.
    // The `app:srcCompat` alias landed earlier today was a SYMPTOM of this same
    // missing rewrite.
    //
    // AOSP's list, from androidx AppCompatViewInflater.createView's switch.
    // ORDER IS NOT SIGNIFICANT; completeness is.
    private static final String[][] APPCOMPAT_REWRITE = {
        {"TextView",                 "AppCompatTextView"},
        {"ImageView",                "AppCompatImageView"},
        {"Button",                   "AppCompatButton"},
        {"EditText",                 "AppCompatEditText"},
        {"Spinner",                  "AppCompatSpinner"},
        {"ImageButton",              "AppCompatImageButton"},
        {"CheckBox",                 "AppCompatCheckBox"},
        {"RadioButton",              "AppCompatRadioButton"},
        {"CheckedTextView",          "AppCompatCheckedTextView"},
        {"AutoCompleteTextView",     "AppCompatAutoCompleteTextView"},
        {"MultiAutoCompleteTextView","AppCompatMultiAutoCompleteTextView"},
        {"RatingBar",                "AppCompatRatingBar"},
        {"SeekBar",                  "AppCompatSeekBar"},
        {"ToggleButton",             "AppCompatToggleButton"},
    };

    /** Resolved AppCompat class per bare tag name, or NEGATIVE (a sentinel) when
     *  this app does not bundle one. Cached because inflation is hot and a failed
     *  Class.forName fills in a stack trace every time. */
    private static final java.util.Map<String, Class<?>> sAppCompatCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final Class<?> APPCOMPAT_ABSENT = APPCOMPAT_ABSENT_MARKER.class;
    private static final class APPCOMPAT_ABSENT_MARKER {}

    /** The AppCompat subclass this app bundles for a bare tag, or null.
     *  Deliberately looks the class up in the APP's classloader: these classes
     *  ship in the APK, never in our framework. If the app does not bundle
     *  AppCompat -- or R8 renamed the class beyond recognition -- we return null
     *  and inflation proceeds exactly as before, so this cannot regress a
     *  non-AppCompat app. Both androidx and the legacy support-v7 package are
     *  tried, since older APKs still use the latter. */
    private Class<?> appCompatClassFor(String bareTag) {
        if (context == null) return null;
        Class<?> hit = sAppCompatCache.get(bareTag);
        if (hit != null) return hit == APPCOMPAT_ABSENT ? null : hit;
        String simple = null;
        for (String[] row : APPCOMPAT_REWRITE) {
            if (row[0].equals(bareTag)) { simple = row[1]; break; }
        }
        if (simple == null) { sAppCompatCache.put(bareTag, APPCOMPAT_ABSENT); return null; }
        ClassLoader cl = context.getClassLoader();
        if (cl == null) return null;
        String[] pkgs = { "androidx.appcompat.widget.", "android.support.v7.widget." };
        for (String pkg : pkgs) {
            try {
                Class<?> c = cl.loadClass(pkg + simple);
                sAppCompatCache.put(bareTag, c);
                NativeLog.i(TAG, "AppCompat rewrite: <" + bareTag + "> -> " + c.getName());
                return c;
            } catch (Throwable ignored) {}
        }
        sAppCompatCache.put(bareTag, APPCOMPAT_ABSENT);
        return null;
    }

    private View createViewByName(String name, Element elem) {
        if (name == null || name.isEmpty()) return null;
        // Tags like <View>, <ViewGroup>, <RelativeLayout$LayoutParams>
        // need to map to the right Java class.
        String fq = name;
        if (!fq.contains(".")) {
            // AppCompat rewrite FIRST, exactly as AOSP's factory does -- see the
            // block comment above. Falls straight through when the app bundles no
            // AppCompat, so a non-AppCompat app is byte-identical to before.
            Class<?> ac = appCompatClassFor(name);
            if (ac != null) {
                View v = instantiateView(ac, elem);
                if (v != null) return v;
                // Instantiation failed (odd ctor shape); fall back to the
                // framework class rather than losing the view entirely.
                NativeLog.w(TAG, "AppCompat " + ac.getName()
                    + " would not instantiate; falling back to android.widget." + name);
            }
            // Try common AOSP prefixes; the first one to load wins.
            String[] prefixes = {
                "android.widget.", "android.view.", "android.webkit."
            };
            for (String p : prefixes) {
                try {
                    Class<?> cls = Class.forName(p + name);
                    return instantiateView(cls, elem);
                } catch (ClassNotFoundException ignored) {}
            }
            return null;
        }
        try {
            Class<?> cls = Class.forName(fq);
            return instantiateView(cls, elem);
        } catch (ClassNotFoundException t) {
            // Try the AppClassLoader (custom view in the APK)
            try {
                Class<?> cls = context.getClassLoader().loadClass(fq);
                return instantiateView(cls, elem);
            } catch (Throwable t2) {
                NativeLog.w(TAG, "createViewByName('" + fq + "') failed: " + t2);
                return null;
            }
        }
    }

    /** Resolve ONE constructor signature and invoke it, mirroring AOSP's
     *  `constructor.setAccessible(true)` in LayoutInflater.createView. Returns
     *  null when the class has no such constructor (so the caller tries the next
     *  shorter signature); any OTHER failure propagates, because a constructor
     *  that exists and threw is a real error the caller must report rather than
     *  silently downgrade to a shorter signature. */
    private View tryCtor(Class<?> cls, Class<?>[] sig, Object[] args) throws Exception {
        Constructor<?> c;
        try {
            c = cls.getConstructor(sig);
        } catch (NoSuchMethodException notPublic) {
            try {
                c = cls.getDeclaredConstructor(sig);
            } catch (NoSuchMethodException none) {
                return null;
            }
        }
        c.setAccessible(true);
        return (View) c.newInstance(args);
    }

    private View instantiateView(Class<?> cls, Element elem) {
        if (cls == null || !View.class.isAssignableFrom(cls)) return null;
        try {
            // v1.0.93: prefer the (Context, AttributeSet) constructor
            // for Material widgets and other classes that read styled
            // attrs at <init>. Used to prefer (Context) only, but that
            // skipped the AttributeSet path and Material widgets that
            // ONLY have the 2-arg/3-arg ctors silently fell through.
            // The DomBackedAttributeSet now resolves @-references via
            // the existing parseResourceId machinery, so BottomNav can
            // find its `app:menu` attribute, ProgressBar can read
            // `android:indeterminate`, etc.
            //
            // Order: prefer the 3-arg ctor (Context, AttributeSet, int)
            // when it exists -- Material widgets use the int defStyleAttr
            // to apply theme defaults. Fall through to 2-arg if no 3-arg.
            // Fall through to 1-arg (Context) as a last resort for
            // older / simple views that don't have an AttributeSet ctor.
            android.util.AttributeSet attrs = (elem != null)
                ? new DomBackedAttributeSet(elem)
                : new EmptyAttributeSet();
            // setAccessible(true) is REQUIRED and was missing (found 2026-07-29,
            // measured as PianoLi's blocker at drawn=0.05%). AOSP does it
            // unconditionally in LayoutInflater.createView:
            //     constructor = clazz.getConstructor(mConstructorSignature);
            //     constructor.setAccessible(true);
            // Without it, a custom View whose CLASS is package-private or Kotlin
            // `internal` throws IllegalAccessException even though its
            // constructor is public -- reflection checks the declaring class's
            // accessibility too. We then fell into the catch below, substituted a
            // FrameLayout placeholder, and the app's own cast to its view type
            // threw, blanking the screen. It affects every app with a non-public
            // custom View, so the exposure is fleet-wide, not one app's quirk.
            //
            // The getDeclaredConstructor fallback is a deliberate superset of
            // AOSP: getConstructor sees PUBLIC constructors only, so a
            // package-private ctor makes AOSP throw. Our failure mode is worse
            // than AOSP's (a silent placeholder rather than a propagated
            // exception), and being more permissive on a reflective lookup cannot
            // make a correct app misbehave -- it can only turn a blank screen
            // into a rendered one.
            // inflateContext == context unless we are inside an
            // android:theme subtree (MACAPK_VIEW_THEME, default OFF). Handing
            // the wrapper to the constructor is the whole point of the AOSP
            // step: AppCompat/Material widgets read ?attr/ out of
            // getContext().getTheme() during <init>.
            final Context vctx = inflateContext != null ? inflateContext : context;
            // lane layout, 2026-08-01: the 2-ARG ctor comes FIRST -- it is
            // AOSP's one-and-only inflation signature (LayoutInflater
            // mConstructorSignature = {Context, AttributeSet}). The previous
            // order preferred the 3-arg ctor with a LITERAL defStyleAttr=0,
            // which neuters every widget's default-style resolution: material
            // BottomNavigationView's own 2-arg ctor supplies
            // this(ctx, attrs, R.attr.bottomNavigationStyle) -> the theme's
            // Widget.Material3.BottomNavigationView (80dp bar, 12/16dp item
            // paddings), while our 3-arg(0) call made it fall back to its
            // defStyleRes Widget.Design.BottomNavigationView -- the M2 style
            // (56dp, no item paddings). Measured: the bar drew 63dp tall with
            // icons hugging the top edge vs the emulator's exact 80dp
            // (docs/canvas-layout-fidelity_2026-08-01.md, bottom-bar section).
            // Same mechanism M2-flavored every appcompat/material widget with
            // a styleattr-supplying 2-arg ctor (Toolbar's titleTextAppearance
            // and content insets included). The 3-arg(0) call stays only as a
            // FALLBACK for classes with no 2-arg ctor, matching what a
            // (Context, AttributeSet) super-chain would do anyway.
            View v = tryCtor(cls, new Class<?>[] { Context.class,
                                                   android.util.AttributeSet.class },
                             new Object[] { vctx, attrs });
            if (v != null) return v;
            v = tryCtor(cls, new Class<?>[] { Context.class,
                                              android.util.AttributeSet.class,
                                              int.class },
                        new Object[] { vctx, attrs, 0 });
            if (v != null) return v;
            return tryCtor(cls, new Class<?>[] { Context.class },
                           new Object[] { vctx });
        } catch (Throwable t) {
            // Surface InvocationTargetException's wrapped cause -- the
            // raw "InvocationTargetException" diagnostic on its own
            // hides the real reason the constructor threw (typically
            // a custom view reading something we don't expose yet:
            // Theme.obtainStyledAttributes results, missing String[]
            // resource, etc.). The wrapped cause is the load-bearing
            // signal a future Reader needs to see.
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException)
                                && t.getCause() != null
                              ? t.getCause() : t;
            NativeLog.w(TAG, "instantiateView(" + cls.getName() + ") failed: "
                              + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace();
            // Remember it so the placeholder message below can say WHY, and can
            // distinguish "class we don't ship" from "class loaded fine but its
            // constructor hit a framework gap". See the placeholder site.
            lastCtorFailClass = cls.getName();
            lastCtorFailCause = cause.getClass().getName() + ": " + cause.getMessage();
            return null;
        }
    }

    /** The most recent class whose CONSTRUCTOR threw inside instantiateView, and
     *  the cause. Used only to make the FrameLayout-placeholder log line
     *  self-explaining -- see the placeholder site in inflateElement. */
    private String lastCtorFailClass;
    private String lastCtorFailCause;

    /** v1.0.93: DOM-backed AttributeSet that pulls values directly
     *  from the inflater's DOM Element. Material widgets read their
     *  app:menu / app:itemTextColor / etc. through this interface
     *  during construction; if they get the empty-stub defaults they
     *  throw (BottomNavigationView calls Resources.getXml(0) on a
     *  null menu attr -> NotFoundException). Each getter parses the
     *  matching attribute by name (with or without namespace prefix)
     *  and resolves @<type>/<name> references via ResourceIndex.
     *
     *  Older code paths that instantiate views WITHOUT a DOM element
     *  (e.g. programmatic widget creation in app code) get the
     *  EmptyAttributeSet stub. */
    private final class DomBackedAttributeSet implements android.util.AttributeSet {
        private final Element elem;
        /** The Context whose THEME resolves a `style="?attr"` on this element:
         *  the view's own context (the inflate context, which carries the
         *  element's `android:theme` overlay) for everything the view reads, and
         *  the PARENT's context for its LayoutParams -- AOSP builds those as
         *  `new LayoutParams(getContext(), attrs)` inside the parent's
         *  generateLayoutParams, so the parent's theme is the one that answers.
         *  Captured at construction because a ?-style is resolved lazily and the
         *  inflate context moves on as the recursion continues. */
        private final Context styleContext;
        DomBackedAttributeSet(Element e) {
            this(e, inflateContext != null ? inflateContext : context);
        }
        DomBackedAttributeSet(Element e, Context styleContext) {
            this.elem = e;
            this.styleContext = styleContext;
        }

        /** Merged item map of the element's `style="@style/X"` cascade
         *  (root-first merge so the leaf style wins), with "android:"
         *  prefixes stripped to local names. Real Android merges style
         *  items into every obtainStyledAttributes lookup with XML
         *  attrs taking precedence; without this, views styled
         *  ENTIRELY via style= (Calculator's keypad buttons declare
         *  background/tint/layout_width ONLY in KeypadButtonStyle)
         *  inflate bare and mis-sized. Lazily resolved once.
         *
         *  2026-09-26 (MACAPK_INFLATE_STYLE_ATTR, default ON): the reference is
         *  resolved by macapkStyleNameOfRef against styleContext's theme, so
         *  `style="?attr"`, `style="@android:style/X"` and an id-ref style are
         *  flattened too. Before, anything but a literal `@style/X` returned an
         *  EMPTY map. The TypedArray tier-2 path already honoured `?attr`
         *  (Theme.macapkStyleNameFromSet), but this map is what the inflater
         *  applies View/TextView fields from -- our framework widget
         *  constructors read almost nothing themselves -- so the tier was dead
         *  where it mattered. MEASURED (offline, the apps' own layouts through
         *  this inflater): organicmaps' download title (`style="?fontHeadline6"`
         *  -> TextAppearance.MdcTypographyStyles.Headline6, 20sp) drew at 28px
         *  not 40px; unitconverter's AlertDialog buttons
         *  (`style="?buttonBar*ButtonStyle"`) fell to tier 3's raised
         *  `buttonStyle` face (InsetDrawable, 88dp min width) instead of the
         *  borderless button-bar style; gcalc's `?materialIconButtonStyle`
         *  toolbar buttons lost their 10dp padding. Every decoded app ships
         *  dozens of these (gcalc 40, anki 99, vlc 164, omaps 55, fdroid 79).
         *
         *  The chain walk below already crossed into the platform table
         *  (getStyleByName normalises `@android:style/`, and platform parents are
         *  spelled that way), and the merge already treats an app style's
         *  `android:X` and a platform style's bare `X` as the same attribute (both
         *  strip to `X`, root-first, so the leaf wins) -- the AOSP rule, since a
         *  platform style can only hold 0x0101xxxx attributes. */
        private java.util.Map<String, String> styleItemsCache;
        java.util.Map<String, String> macapkStyleItems() {
            if (styleItemsCache != null) return styleItemsCache;
            styleItemsCache = new java.util.HashMap<>();
            try {
                if (elem == null) return styleItemsCache;
                String ref = elem.getAttribute("style");
                String name;
                if (MACAPK_INFLATE_STYLE_ATTR) {
                    if (ref == null || ref.isEmpty()) return styleItemsCache;
                    name = macapkStyleNameOfRef(ref, macapkThemeOf(styleContext));
                    if (name == null) {
                        if (MACAPK_TRACE_INFLATE_STYLE) {
                            NativeLog.i(TAG, "style " + ref + " on <" + elem.getTagName()
                                + "> -> UNRESOLVED (no items)");
                        }
                        return styleItemsCache;
                    }
                } else {
                    if (ref == null || !ref.startsWith("@style/")) {
                        return styleItemsCache;
                    }
                    name = ref.substring(7);
                }
                Object ri = android.content.res.Resources
                    .macapkGetResourceIndex();
                if (ri == null) return styleItemsCache;
                java.lang.reflect.Method byName = ri.getClass()
                    .getMethod("getStyleByName", String.class);
                java.util.ArrayList<Object> chain = new java.util.ArrayList<>();
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                while (name != null && seen.add(name)) {
                    Object def = byName.invoke(ri, name);
                    if (def == null) break;
                    chain.add(def);
                    Object parent = def.getClass().getField("parent").get(def);
                    name = parent == null ? null : parent.toString();
                    if (name != null && name.startsWith("@style/")) {
                        name = name.substring(7);
                    }
                }
                for (int i = chain.size() - 1; i >= 0; i--) {
                    Object def = chain.get(i);
                    Object items = def.getClass().getField("items").get(def);
                    if (items instanceof java.util.Map<?, ?> mp) {
                        for (java.util.Map.Entry<?, ?> en : mp.entrySet()) {
                            String k = String.valueOf(en.getKey());
                            if (k.startsWith("android:")) k = k.substring(8);
                            styleItemsCache.put(k,
                                String.valueOf(en.getValue()));
                        }
                    }
                }
                if (MACAPK_TRACE_INFLATE_STYLE && !ref.startsWith("@style/")) {
                    NativeLog.i(TAG, "style " + ref + " on <" + elem.getTagName() + "> -> "
                        + chain.size() + " style(s), " + styleItemsCache.size() + " item(s)");
                }
            } catch (Throwable ignored) {}
            return styleItemsCache;
        }

        /** Lookup an attribute by local name, ignoring namespace prefix.
         *  Tries the qualified `ns:name` form first when ns is non-null,
         *  then falls back to local-name match, then to the element's
         *  style= cascade (AOSP precedence: XML attr > style item). */
        private String lookup(String namespace, String name) {
            if (elem == null || name == null) return null;
            // Try fully-qualified namespace match first.
            if (namespace != null && !namespace.isEmpty()) {
                String v = elem.getAttributeNS(namespace, name);
                if (v != null && !v.isEmpty()) return v;
            }
            // v1.0.918: the two REAL namespaces are matched EXACTLY by
            // prefix. This used to match a bare local name across every
            // namespace, on the reasoning that "we don't have a strict
            // separation in our DOM". On a device a styleable slot is
            // filled by attr RESOURCE ID, so the namespaces can never
            // cross: androidx RecyclerView's res-auto `fastScrollEnabled`
            // is 0x7f040289 and AbsListView's android:fastScrollEnabled is
            // 0x0101..., and an android-namespaced attribute therefore can
            // NEVER satisfy an app-package styleable of the same local
            // name. anki's deck picker declares the android: one; the
            // any-namespace match fed it to androidx, which then demanded
            // four fast-scroll drawables nobody declares, threw, and took
            // DeckPicker.onCreate down before its IntroductionActivity
            // redirect -- costing 166k pixels of the intro screen (recap
            // 91.84 -> 49.70 at v1.0.916). The wrongness was INERT until
            // v1.0.913 made getBoolean actually read its value source.
            // A null/empty namespace keeps the legacy any-namespace match,
            // which is what direct app callers of AttributeSet expect.
            final boolean nsAndroid =
                "http://schemas.android.com/apk/res/android".equals(namespace);
            final boolean nsResAuto =
                "http://schemas.android.com/apk/res-auto".equals(namespace);
            org.w3c.dom.NamedNodeMap attrs = elem.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                String qn = a.getNodeName();
                String localName = a.getLocalName();
                if (localName == null) localName = qn;
                // Strip optional namespace prefix from nodeName
                int colon = localName.indexOf(':');
                if (colon >= 0) localName = localName.substring(colon + 1);
                if (!name.equals(localName)) continue;
                boolean androidPrefixed = qn != null && qn.startsWith("android:");
                if (nsAndroid && !androidPrefixed) continue;
                if (nsResAuto && androidPrefixed) continue;
                return a.getNodeValue();
            }
            // The style cascade's keys are prefix-stripped by construction,
            // so a same-local-name collision INSIDE a style is still
            // possible here. Same class as the bug above, much rarer.
            return macapkStyleItems().get(name);
        }

        @Override public int    getAttributeCount() {
            return elem == null ? 0 : elem.getAttributes().getLength();
        }
        @Override public String getAttributeName(int i) {
            if (elem == null) return null;
            Node n = elem.getAttributes().item(i);
            if (n == null) return null;
            String s = n.getLocalName();
            return s != null ? s : n.getNodeName();
        }
        @Override public String getAttributeValue(int i) {
            if (elem == null) return null;
            Node n = elem.getAttributes().item(i);
            return n == null ? null : n.getNodeValue();
        }
        @Override public String getAttributeValue(String ns, String name) {
            return lookup(ns, name);
        }
        @Override public String getPositionDescription() {
            return elem == null ? "" : "<" + elem.getNodeName() + ">";
        }
        @Override public int    getAttributeNameResource(int i) { return 0; }
        @Override public int    getAttributeListValue(String ns, String attr, String[] options, int def) {
            String v = lookup(ns, attr);
            if (v == null || options == null) return def;
            for (int i = 0; i < options.length; i++) if (v.equals(options[i])) return i;
            return def;
        }
        @Override public boolean getAttributeBooleanValue(String ns, String attr, boolean def) {
            String v = lookup(ns, attr);
            if (v == null) return def;
            return "true".equals(v);
        }
        @Override public int    getAttributeResourceValue(String ns, String attr, int def) {
            String v = lookup(ns, attr);
            if (v == null) return def;
            int id = parseResourceId(v);
            return id != 0 ? id : def;
        }
        @Override public int    getAttributeIntValue(String ns, String attr, int def) {
            String v = lookup(ns, attr);
            if (v == null) return def;
            try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { return def; }
        }
        @Override public int    getAttributeUnsignedIntValue(String ns, String attr, int def) {
            return getAttributeIntValue(ns, attr, def);
        }
        @Override public float  getAttributeFloatValue(String ns, String attr, float def) {
            String v = lookup(ns, attr);
            if (v == null) return def;
            try { return Float.parseFloat(v); } catch (NumberFormatException ignored) { return def; }
        }
        @Override public int    getAttributeListValue(int i, String[] options, int def) {
            return def;
        }
        @Override public boolean getAttributeBooleanValue(int i, boolean def) {
            String v = getAttributeValue(i);
            return v == null ? def : "true".equals(v);
        }
        @Override public int    getAttributeResourceValue(int i, int def) {
            String v = getAttributeValue(i);
            if (v == null) return def;
            int id = parseResourceId(v);
            return id != 0 ? id : def;
        }
        @Override public int    getAttributeIntValue(int i, int def) {
            String v = getAttributeValue(i);
            try { return v != null ? Integer.parseInt(v) : def; }
            catch (NumberFormatException ignored) { return def; }
        }
        @Override public int    getAttributeUnsignedIntValue(int i, int def) {
            return getAttributeIntValue(i, def);
        }
        @Override public float  getAttributeFloatValue(int i, float def) {
            String v = getAttributeValue(i);
            try { return v != null ? Float.parseFloat(v) : def; }
            catch (NumberFormatException ignored) { return def; }
        }
        @Override public String getIdAttribute() {
            return lookup(null, "id");
        }
        @Override public String getClassAttribute() {
            return lookup(null, "class");
        }
        @Override public int    getIdAttributeResourceValue(int def) {
            String v = lookup(null, "id");
            if (v == null) return def;
            int id = parseResourceId(v);
            return id != 0 ? id : def;
        }
        @Override public int    getStyleAttribute() {
            String v = lookup(null, "style");
            if (v == null) return 0;
            return parseResourceId(v);
        }
    }

    /** Stub AttributeSet so custom views that REQUIRE the two-arg
     *  ctor can still be instantiated. Returns empty defaults; the
     *  attribute-application pass below uses DOM-side parsing, not
     *  this stub. */
    private static final class EmptyAttributeSet implements android.util.AttributeSet {
        @Override public int    getAttributeCount() { return 0; }
        @Override public String getAttributeName(int i) { return null; }
        @Override public String getAttributeValue(int i) { return null; }
        @Override public String getAttributeValue(String ns, String name) { return null; }
        @Override public String getPositionDescription() { return ""; }
        @Override public int    getAttributeNameResource(int i) { return 0; }
        @Override public int    getAttributeListValue(String ns, String attr, String[] options, int def) { return def; }
        @Override public boolean getAttributeBooleanValue(String ns, String attr, boolean def) { return def; }
        @Override public int    getAttributeResourceValue(String ns, String attr, int def) { return def; }
        @Override public int    getAttributeIntValue(String ns, String attr, int def) { return def; }
        @Override public int    getAttributeUnsignedIntValue(String ns, String attr, int def) { return def; }
        @Override public float  getAttributeFloatValue(String ns, String attr, float def) { return def; }
        @Override public int    getAttributeListValue(int i, String[] options, int def) { return def; }
        @Override public boolean getAttributeBooleanValue(int i, boolean def) { return def; }
        @Override public int    getAttributeResourceValue(int i, int def) { return def; }
        @Override public int    getAttributeIntValue(int i, int def) { return def; }
        @Override public int    getAttributeUnsignedIntValue(int i, int def) { return def; }
        @Override public float  getAttributeFloatValue(int i, float def) { return def; }
        @Override public String getIdAttribute() { return null; }
        @Override public String getClassAttribute() { return null; }
        @Override public int    getIdAttributeResourceValue(int def) { return def; }
        @Override public int    getStyleAttribute() { return 0; }
    }

    /** BISECT GATE (cluster B3, 2026-09-26, default ON):
     *  MACAPK_CTOR_PADDING_SKIP=0 replays tier-1/2 padding after construction
     *  and keeps the MaterialButton stand-in padding exactly as before. */
    static final boolean MACAPK_CTOR_PADDING_SKIP =
        !"0".equals(System.getenv("MACAPK_CTOR_PADDING_SKIP"));

    /** True when the post-construction padding replay would re-apply only what
     *  the view's OWN constructor already resolved from the same cascade
     *  (View.macapkCtorPaddingPx, cluster B3), and nothing after construction
     *  has moved the padding since (a background this pass installed resets it
     *  to the drawable's own, AOSP setBackgroundDrawable, and then the replay
     *  is still needed). AOSP applies padding once, in the constructor
     *  (android14 View.java:5642-5643 and the post-loop :6245-6310), and a
     *  subclass may build on it -- MaterialButtonHelper writes back padding +
     *  insets -- so re-applying the same tier-1/2 value can only undo that
     *  (gcalc history button: 28 -> 20 with the replay). Any key the
     *  constructor did not read, or read as a different value, keeps the
     *  replay, so a view the constructor under-served is never worse off. */
    private boolean macapkCtorPaddingAlreadyApplied(View view,
            java.util.Map<String, String> dp, int[] padAfterCtor) {
        if (padAfterCtor == null || dp.isEmpty()) return false;
        for (java.util.Map.Entry<String, String> e : dp.entrySet()) {
            Integer px = parseDimensionPxIntSafe(e.getValue());
            if (px == null || view.macapkCtorPaddingPx(e.getKey()) != px) return false;
        }
        return view.getPaddingLeft() == padAfterCtor[0] && view.getPaddingTop() == padAfterCtor[1]
            && view.getPaddingRight() == padAfterCtor[2] && view.getPaddingBottom() == padAfterCtor[3];
    }

    /** AOSP View constructor padding resolution (see the replay site). An
     *  undefined side keeps the view's current padding (constructor /
     *  background), as AOSP's `topPadding >= 0 ? topPadding : mPaddingTop`
     *  does. */
    private void macapkApplyResolvedPadding(View view, java.util.Map<String, String> dp) {
        if (dp.isEmpty()) return;
        String l = null, t = null, r = null, b = null;
        String all = dp.get("padding");
        if (nonNegPx(all)) {
            l = t = r = b = all;
        } else {
            String h = dp.get("paddingHorizontal"), v = dp.get("paddingVertical");
            if (nonNegPx(h)) { l = r = h; }
            else { l = dp.get("paddingLeft"); r = dp.get("paddingRight"); }
            if (nonNegPx(v)) { t = b = v; }
            else { t = dp.get("paddingTop"); b = dp.get("paddingBottom"); }
        }
        String st = dp.get("paddingStart"), en = dp.get("paddingEnd");
        boolean stDef = st != null && parseDimensionPxIntSafe(st) != null;
        boolean enDef = en != null && parseDimensionPxIntSafe(en) != null;
        if (macapkRtlCompatibilityMode()) {
            // View ctor :6272-6287: start/end only where left/right was not
            // defined (padding, paddingHorizontal and paddingLeft/Right all
            // count as defined).
            if (stDef && l == null) l = st;
            if (enDef && r == null) r = en;
        } else {
            // resolvePadding (:21705), LTR: a defined start/end overrides.
            if (stDef) l = st;
            if (enDef) r = en;
        }
        if (l != null) applyAttribute(view, "paddingLeft", l);
        if (t != null) applyAttribute(view, "paddingTop", t);
        if (r != null) applyAttribute(view, "paddingRight", r);
        if (b != null) applyAttribute(view, "paddingBottom", b);
    }

    private boolean nonNegPx(String v) {
        Integer px = v != null ? parseDimensionPxIntSafe(v) : null;
        return px != null && px >= 0;
    }

    private Integer parseDimensionPxIntSafe(String v) {
        try { return parseDimensionPxInt(v); } catch (Throwable t) { return null; }
    }

    /** Apply the subset of attributes the framework actually reads
     *  in onDraw / lifecycle. android:id is the load-bearing one
     *  (findViewById depends on it). Everything else is best-effort
     *  via reflection so we don't have to enumerate setters here. */
    private void applyAttributes(View view, Element elem) {
        // Cluster B3: the padding the view's constructor chain left, before
        // anything below can move it (see macapkCtorPaddingAlreadyApplied).
        final int[] macapkPadAfterCtor = (MACAPK_CTOR_PADDING_SKIP && view.macapkCtorPaddingMask != 0)
            ? new int[]{ view.getPaddingLeft(), view.getPaddingTop(),
                         view.getPaddingRight(), view.getPaddingBottom() }
            : null;
        // Style items FIRST (then element attrs overwrite) -- the AOSP
        // precedence for obtainStyledAttributes is XML attr > style
        // item, and applying in this order reproduces it for the
        // setter-based application below. Views styled entirely via
        // style= (Calculator's keypad buttons carry background /
        // tint / scaleType only in KeypadButtonStyle) get their look
        // from this pass. layout_* items are consumed by buildLP /
        // the parent's generateLayoutParams instead.
        java.util.Map<String, String> sitems =
            new DomBackedAttributeSet(elem).macapkStyleItems();
        // TIER 3 -- the WIDGET DEFAULT STYLE, applied UNDER the style= items
        // (which the loop below overwrites them with) and under the element's
        // own attrs. v1.0.869.
        //
        // AOSP gives every framework widget its default style through its own
        // constructor: `Button(Context, AttributeSet)` chains to
        // `this(context, attrs, com.android.internal.R.attr.buttonStyle)`, and
        // obtainStyledAttributes then resolves that attr against the theme. Our
        // framework widgets are simplified reimplementations whose constructors
        // do no attribute resolution at all -- the inflater does it -- so tier 3
        // simply did not exist for them, and a plain `<Button>` got NO default
        // chrome whatsoever.
        //
        // MEASURED victim: bobball's six menu buttons render as bare text with
        // no face and no height against an emulator drawing raised buttons. Its
        // theme inherits `buttonStyle` from `@android:style/
        // Theme.NoTitleBar.Fullscreen` rather than restating it, so the v1.0.858
        // `?android:<attr>` DELEGATION fix could never reach it -- that fix
        // handles a theme that says `<item name="buttonStyle">?android:
        // buttonStyle</item>`; bobball's says nothing at all.
        //
        // Scoped to Button for now, and asks only for the attrs the framework
        // button styles actually carry, because the framework style table
        // answers per-attr rather than returning a whole item map.
        applyWidgetDefaultStyle(view, sitems, elem);
        // textAppearance FIRST (the BASE: AOSP applies the named text-
        // appearance style, then direct attrs override). Pull it from the
        // style= items or a direct android:textAppearance, apply, then let
        // the loops below override with any explicit textSize/textColor.
        {
            // WHICH textAppearance: AOSP reads it with obtainStyledAttributes(attrs,
            // TextViewAppearance, defStyleAttr, defStyleRes) (TextView.java
            // android14-release :1196-1202), whose precedence is XML attribute >
            // XML style > default style > theme (AttributeResolution.cpp ApplyStyle
            // :259-300). So the element's OWN android:textAppearance outranks the
            // one its `style=` carries. The pre-change order asked the style first,
            // which inverted that for any element declaring both. Under
            // MACAPK_INFLATE_TA_ATTR=0 the old order stands.
            org.w3c.dom.Attr taAttr = elem.getAttributeNodeNS(
                "http://schemas.android.com/apk/res/android", "textAppearance");
            String own = taAttr != null ? taAttr.getValue() : null;
            String ta;
            if (MACAPK_INFLATE_TA_ATTR) {
                ta = (own != null && !own.isEmpty()) ? own : sitems.get("textAppearance");
            } else {
                ta = sitems.get("textAppearance");
                if (ta == null) ta = own;
            }
            if (ta != null) {
                // BISECT GATE MACAPK_TA_DECLARED_TEXTCOLOR (2026-09-26, default ON;
                // docs/fleet-newpipe-vlc-residual_2026-09-26.md). AOSP TextView reads
                // the textAppearance FIRST and then lets the TextView styleable's own
                // android:textColor (element, style= or default style) override it
                // (android14 TextView.java: readTextAppearance, then the attribute
                // loop's `case com.android.internal.R.styleable.TextView_textColor:
                // textColor = a.getColorStateList(attr)`). Our constructor already
                // did exactly that. Re-applying the appearance's colour here, as a
                // FLAT int after construction, overwrote the constructor's stateful
                // list whenever the declared colour could not be flattened back for
                // the per-attribute loop below (every `?attr`-row selector): VLC's
                // SKIP (style Widget.MaterialComponents.Button.TextButton, textColor
                // mtrl_text_btn_text_color_selector, enabled = ?colorPrimary orange)
                // read the Button appearance's #ffffffff. So when the element or its
                // style declares textColor, the appearance's colour is not applied.
                // =0 restores the unconditional re-apply.
                boolean declaredColor = MACAPK_TA_DECLARED_TEXTCOLOR
                    && view instanceof android.widget.TextView
                    && (sitems.get("textColor") != null
                        || elem.getAttributeNodeNS("http://schemas.android.com/apk/res/android",
                                                   "textColor") != null);
                applyTextAppearance(view, ta, !declaredColor);
            }
        }
        // v1.0.871: PADDING GOES LAST. AOSP's View constructor sets the
        // background first and applies explicit `android:padding*` afterwards,
        // so an app's own padding always beats the padding a background drawable
        // carries (see View.setBackgroundDrawable, which now applies that
        // padding). Our two loops below iterate a HashMap in ARBITRARY order and
        // then the element's attrs in document order, so without deferring these
        // an element declaring both `android:background` and `android:padding`
        // would get whichever happened to run last -- non-deterministic between
        // runs for the style-item loop. Collected here and replayed at the end.
        java.util.LinkedHashMap<String, String> deferredPadding =
            new java.util.LinkedHashMap<>();
        // v1.0.895: lineHeight defers for the SAME reason padding does, but with
        // an AOSP citation rather than only determinism. AOSP reads
        // lineSpacingExtra/lineSpacingMultiplier inside its attribute loop and
        // applies lineHeight only AFTER it closes (TextView.java:2014-2018), so
        // lineHeight wins over the multiplier -- setLineHeightPx resets mult to
        // 1f. Our loops run in document/HashMap order, so without deferring, a
        // layout declaring BOTH (wikipedia's onboarding TextView declares
        // lineSpacingMultiplier="1.22" AND style="@style/P" with lineHeight=24sp)
        // would get whichever happened to come last, inverting AOSP's precedence
        // non-deterministically.
        java.util.LinkedHashMap<String, String> deferredLineHeight =
            new java.util.LinkedHashMap<>();
        // An element's own android:background="@null" / "@empty" is AOSP's
        // TYPE_NULL record, and the XML attribute outranks every style tier, so
        // View's constructor installs NO background at all. Ours applied the
        // style items first and then treated the element's @null as a no-op,
        // so a style= or widget-default background stayed. Measured: Plague
        // Inc.'s hud.xml news_button (a <Button> with background=@null laid
        // over the news ticker as a tap target) painted the dark Material
        // button face #5a595b over the whole news bar. Skip the style tier's
        // background instead of clearing afterwards, so a background a view's
        // own constructor installed in code survives, as on a device.
        // MACAPK_INFLATE_ELEMENT_NULL_BG=0 restores the old order.
        final boolean elemNullBg = MACAPK_INFLATE_ELEMENT_NULL_BG
            && macapkIsNullBackground(macapkElementAttr(elem, "background"));
        for (java.util.Map.Entry<String, String> en : sitems.entrySet()) {
            String name = en.getKey();
            if (name.startsWith("layout_") || "style".equals(name)
                    || "id".equals(name)) continue;
            if (elemNullBg && "background".equals(name)) continue;
            if (name.startsWith("padding")) { deferredPadding.put(name, en.getValue()); continue; }
            if ("lineHeight".equals(name)) { deferredLineHeight.put(name, en.getValue()); continue; }
            applyAttribute(view, name, en.getValue());
        }
        NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); ++i) {
            Node a = attrs.item(i);
            String localName = a.getLocalName();
            if (localName == null) localName = a.getNodeName();
            String value = a.getNodeValue();
            // An element's own padding outranks a style item's, so it overwrites
            // the deferred entry rather than being dropped by it.
            if (localName.startsWith("padding")) { deferredPadding.put(localName, value); continue; }
            // An element's own lineHeight outranks a style item's, same rule as
            // padding above.
            if ("lineHeight".equals(localName)) { deferredLineHeight.put(localName, value); continue; }
            applyAttribute(view, localName, value);
        }
        // 2026-08-13 (opencalculator structural-offset lane): replay
        // deferredPadding in a FIXED order, not its own (arbitrary) iteration
        // order. deferredPadding is a LinkedHashMap, but
        // it was FILLED by iterating `sitems` -- a plain java.util.HashMap
        // (macapkStyleItems(), line ~1750) -- so "the order entries were
        // inserted" is really "whatever bucket order HashMap<String,String>
        // happened to hash padding/paddingLeft/paddingRight/... into", which
        // is deterministic per JVM but has nothing to do with AOSP precedence.
        //
        // AOSP's real View(Context, AttributeSet, int, int) constructor reads
        // these from a TypedArray via `for (i = 0; i < N; i++) { attr =
        // a.getIndex(i); switch (attr) {...} }`, where getIndex(i) walks
        // R.styleable.View's attrs in a FIXED index order (confirmed by
        // fetching aosp-mirror/platform_frameworks_base View.java :6060-6110):
        // View_padding, then paddingHorizontal, paddingVertical, paddingLeft,
        // paddingTop, paddingRight, paddingBottom, paddingStart, paddingEnd.
        // View_padding sets mUserPaddingLeftInitial/RightInitial as a
        // TENTATIVE value for all 4 sides; the specific slots that come after
        // it in that fixed order each conditionally overwrite ONE side. So on
        // real Android, a per-side padding* ALWAYS beats a same-view "padding"
        // shorthand, no matter which style in the chain declared which -- the
        // order is a property of the attribute table, not of style specificity.
        //
        // Our old code replayed deferredPadding in HashMap-bucket order, so
        // "padding" could land anywhere relative to "paddingLeft"/"Right", and
        // since `case "padding":` unconditionally sets all 4 sides while
        // `case "paddingLeft"/"paddingRight":` each read-modify-write ONE side,
        // whichever one happened to hash-iterate LAST won -- differently per
        // attribute, not consistently. MEASURED on opencalculator's keypad
        // (docs/canvas-structure-head_2026-08-13.md, MACAPK_TRACE_LAYOUT
        // instrumentation on v1.0.969): every keypad MaterialButton laid out
        // with paddingLeft=48px paddingRight=0px, though CalculatorButton
        // style sets `android:padding=0dp` (meant to zero out the inherited
        // Material padding) and its ancestor Widget.Material3.Button sets
        // `paddingLeft=paddingRight=24dp` (both 48px at this density) --
        // symmetric at the SOURCE, asymmetric after the old replay. The
        // measured (48, 0) result is exactly what a
        // [..., paddingRight, padding, paddingLeft] hash-bucket replay order
        // produces (paddingRight applies then gets wiped back to 0 by the
        // shorthand, paddingLeft applies last and sticks) -- one concrete
        // ordering consistent with the observed numbers, not independently
        // instrumented bucket-by-bucket, but sufficient to explain the bug
        // and to predict the fix. Real MaterialButton has no such override at
        // all (insetLeft/Right=0dp, unambiguous), so this button's TEXT (glyph)
        // is centered via Button's default CENTER gravity within
        // [paddingLeft, width-paddingRight] -- symmetric 48/48 on AOSP,
        // lopsided 48/0 on Mac.apk -- landing every digit glyph ~24px right of
        // where AOSP draws it. The button's own BOUNDS (and therefore its
        // background/circle drawable, which View.draw() sizes to the full
        // view rect regardless of padding) were never wrong; only the
        // content inset was, which is why eyes-on the render looked correct
        // and only the structural (edge-recall) signal caught it.
        //
        // Fixed order below matches the AOSP table above; among entries not
        // named "padding"/paddingHorizontal/paddingVertical this doesn't
        // matter (each of Left/Top/Right/Bottom/Start/End touches exactly one
        // side and none of them clobber the other three), so only the
        // shorthand needs to be pulled to the front.
        // ⚠ CORRECTED v1.0.1643: the reading above is WRONG about the outcome.
        // The index-order loop only COLLECTS values; the constructor then
        // RESOLVES them after the loop (android14 View.java:6250-6270, quoted):
        //   "Valid paddingHorizontal/paddingVertical beats leftPadding,
        //    rightPadding, topPadding, bottomPadding, and padding set by
        //    background.  Valid padding beats everything."
        // and resolvePadding (:21705) then lets a DEFINED paddingStart/End
        // override left/right (LTR), even over `padding`. So a style's
        // `android:padding=0dp` beats its parent style's paddingLeft/Right=24dp,
        // the opposite of what the replay below used to do: OpenCalculator's
        // keypad buttons kept pad=48,12,48,12 and its backspace icon sat 24px
        // right of the emulator's. paddingHorizontal/Vertical also had no case
        // at all and were dropped. deferredPadding holds the MERGED family
        // (style items, then the element's own attributes overwriting the same
        // name), which is exactly what a TypedArray presents to that code.
        if (LAYOUT_AOSP_1643) {
            if (!macapkCtorPaddingAlreadyApplied(view, deferredPadding, macapkPadAfterCtor))
                macapkApplyResolvedPadding(view, deferredPadding);
        } else {
            for (String first : new String[]{"padding", "paddingHorizontal", "paddingVertical"}) {
                String v = deferredPadding.remove(first);
                if (v != null) applyAttribute(view, first, v);
            }
            for (java.util.Map.Entry<String, String> en : deferredPadding.entrySet()) {
                applyAttribute(view, en.getKey(), en.getValue());
            }
        }
        // Last, so it beats any lineSpacingMultiplier applied above -- AOSP
        // TextView.java:2014-2018.
        for (java.util.Map.Entry<String, String> en : deferredLineHeight.entrySet()) {
            applyAttribute(view, en.getKey(), en.getValue());
        }
        if (view instanceof android.widget.TextView tvSl) {
            macapkReplaySingleLine(tvSl, elem, sitems);
        }
        applyMaterialButtonStyle(view, sitems, elem);
    }

    /** BISECT GATE shared by name with android.widget.TextView and
     *  android.text.Layout (MACAPK_TEXT_LINES_AOSP=0 restores the pre-gate
     *  text line behaviour); the inflater half is the singleLine replay, the
     *  style-sourced END default and the XML-marquee hook. */
    static final boolean MACAPK_TEXT_LINES_AOSP =
        !"0".equals(System.getenv("MACAPK_TEXT_LINES_AOSP"));

    /** AOSP's TextView constructor reads singleLine and ellipsize through the
     *  TypedArray -- style= and defStyle values included -- and acts on them
     *  AFTER its attribute loop (android14-release TextView.java:1776-1786):
     *      applySingleLine(singleLine, singleLine, singleLine, false);
     *      if (singleLine && getKeyListener() == null && ellipsize == ELLIPSIZE_NOT_SET)
     *          ellipsize = ELLIPSIZE_END;
     *  Two consequences our two attribute loops lose. (1) singleLine always
     *  beats maxLines/lines, since applySingleLine's setLines(1) runs last;
     *  ours ran in HashMap/document order, so `maxLines="2"` applied after
     *  singleLine left the view wrapping. (2) A singleLine that arrives from a
     *  STYLE gets the END default too; TextView's constructor block only sees
     *  the element's own AttributeSet, so such a label was CLIPPED where a
     *  device ellipsizes it. The element's value beats the style's, as in the
     *  loops above. The key-listener conjunct stands in as EditText /
     *  inputType, the two things that give a TextView one at that point. */
    private static void macapkReplaySingleLine(android.widget.TextView tv, Element elem,
                                               java.util.Map<String, String> sitems) {
        if (!MACAPK_TEXT_LINES_AOSP) return;
        try {
            String sl = null;
            org.w3c.dom.Attr a = (elem == null) ? null : elem.getAttributeNodeNS(
                "http://schemas.android.com/apk/res/android", "singleLine");
            if (a != null) sl = a.getValue();
            else if (sitems != null) sl = sitems.get("singleLine");
            if (sl == null) return;
            sl = sl.trim();
            if (!("true".equals(sl) || "1".equals(sl))) return;
            tv.setSingleLine(true);
            if (!macapkDeclares(elem, sitems, "ellipsize")
                    && !(tv instanceof android.widget.EditText)
                    && tv.getKeyListener() == null && tv.getInputType() == 0
                    && tv.getEllipsize() == null) {
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
        } catch (Throwable ignored) {
            // An attribute we cannot parse must never break inflation.
        }
    }

    /** A Material3 FILLED MaterialButton needs a colorPrimary pill background +
     *  colorOnPrimary text + a real button height -- none of which our framework
     *  derives from its style (the fill is a MaterialShapeDrawable, the colors are
     *  ColorStateList selectors we don't resolve). Detect the filled variant by
     *  its backgroundTint selector (text/outlined buttons use different ones, so
     *  they're left alone) and apply the look: macapkButtonFill drives the pill in
     *  View.macapkPaintMaterialChrome; the text goes white + centered + ~40dp tall.
     *  Generic for any app's filled MaterialButton, keyed on the widget + the
     *  Material3 selector name -- no per-APK data. */
    /** Fill `sitems` with the theme's WIDGET DEFAULT STYLE for this view class,
     *  without overwriting anything the app's own `style=` already set.
     *
     *  See the call site for why this tier was missing entirely. The framework
     *  style table answers PER-ATTR (ResourceIndex.frameworkStyleAttr ->
     *  macapkFrameworkButtonGeom) rather than handing back an item map, so this
     *  asks for the specific attrs those styles carry.
     *
     *  Deliberately Button-only: it is the one widget with a measured victim and
     *  with a framework style we can actually answer for. Widening it to
     *  textViewStyle / checkboxStyle / etc. means teaching the framework style
     *  table about those styles first, and each family has DIFFERENT values
     *  (see macapkFrameworkButtonGeom's citation block) -- so that is a separate,
     *  separately-measured change, not a loop over more names here. */
    private void applyWidgetDefaultStyle(View view, java.util.Map<String, String> sitems,
                                         Element elem) {
        try {
            if (view == null || sitems == null) return;
            // `Button` (ours) and `AppCompatButton` (the app's). NOT
            // MaterialButton, which brings a real Material background of its own.
            // AppCompatButton is included because it demonstrably does NOT get a
            // default through its own constructor here: bobball's six render
            // 744x33 with no face at all. It reads its attrs through our
            // simplified TypedArray, which never delivered the framework style's
            // `background`.
            final String cn = view.getClass().getSimpleName();
            final boolean trace = "1".equals(System.getenv("MACAPK_TRACE_DEFSTYLE_APPLY"));
            // v1.0.906: driven off the AOSP widget-class -> defStyleAttr map
            // instead of a hardcoded `if Button`. Every framework widget gets its
            // default style through its own constructor -- RadioButton(Context,
            // AttributeSet) chains to `this(context, attrs,
            // com.android.internal.R.attr.radioButtonStyle)`, CheckBox to
            // checkboxStyle, Button to buttonStyle -- and our widgets' constructors
            // do no attribute resolution at all, so this is where that tier lives.
            //
            // Material* subclasses stay excluded, as MaterialButton already was:
            // they carry their own background/tint machinery and
            // applyMaterialButtonStyle handles them separately.
            String defStyleAttr = macapkDefStyleAttrFor(cn);
            // R8 renames an app's EditText subclass (OpenCalculator's display is
            // `n.x extends android.widget.EditText`, simple name "x"), so the
            // simple-name table above misses it and Widget.EditText's
            // `gravity=center_vertical` (core/res styles.xml:553-567) never
            // arrives. AOSP does not key on the name: EditText(Context,
            // AttributeSet) chains to R.attr.editTextStyle for every subclass
            // that does not pass its own defStyleAttr (AppCompatEditText and
            // Material's TextInputEditText pass editTextStyle as well).
            // AutoCompleteTextView is excluded: it passes
            // autoCompleteTextViewStyle, which this table does not serve.
            if (defStyleAttr == null && view instanceof android.widget.EditText
                    && !(view instanceof android.widget.AutoCompleteTextView)) {
                defStyleAttr = macapkDefStyleAttrFor("EditText");
            }
            if (defStyleAttr == null) {
                if (trace) android.util.Log.i("Mac.apk.DefStyle", "skip class=" + cn);
                return;
            }
            Object ri = android.content.res.Resources.macapkGetResourceIndex();
            if (ri == null) return;
            java.lang.reflect.Method mResolve = ri.getClass()
                .getMethod("resolveAttrInTheme", String.class, String.class);
            Object appTheme = ri.getClass()
                .getMethod("getApplicationThemeName").invoke(ri);
            if (!(appTheme instanceof String)) return;
            Object sr = mResolve.invoke(ri, appTheme, defStyleAttr);
            if (trace) android.util.Log.i("Mac.apk.DefStyle",
                "cn=" + cn + " appTheme=" + appTheme + " " + defStyleAttr + "=" + sr);
            if (!(sr instanceof String)) return;
            String styleName = ((String) sr).trim();
            // ⚠ v1.0.871 BUG FIX. This used to `return` unless the name began
            // `@android:style/`, on the reasoning that an app pointing the attr
            // at its OWN style already gets that style through the normal path.
            // That reasoning is WRONG for the common AppCompat shape, where the
            // app's style is a thin chain that TERMINATES in the framework one:
            //   unitconverter styles.xml:670  radioButtonStyle
            //        -> @style/Widget.AppCompat.CompoundButton.RadioButton   (app)
            //   :1571 -> @style/Base.Widget.AppCompat.CompoundButton.RadioButton (app)
            //   :985  -> @android:style/Widget.Material.CompoundButton.RadioButton
            // The first hop is an APP name, so the gate rejected it and the
            // platform default never arrived -- which is why its radio buttons
            // draw no circle at all.
            //
            // Resolving against whatever name comes back is both simpler and
            // more AOSP-faithful: resolveAttrInThemeImpl already walks an app
            // style's parent chain and falls through to frameworkStyleAttr at
            // the end, so an app style that defines the attribute itself wins on
            // its own merits, and one that merely inherits reaches the platform
            // value exactly as it does on a device. Nothing here needs to know
            // which namespace the chain STARTED in.
            // v1.0.906: CHASE a `?attr` delegation instead of giving up on it. An
            // AppCompat theme routinely points a widget's defStyleAttr straight at
            // the platform one -- unitconverter's AppTheme declares
            // `radioButtonStyle = ?android:radioButtonStyle` -- and returning here
            // meant the platform style was never reached, so its indicator never
            // arrived and every radio and checkbox drew no circle. Bounded, same
            // shape as the chase in macapkInstallButtonDrawable below.
            for (int hop = 0; hop < 4 && styleName.startsWith("?"); hop++) {
                String a = styleName.substring(1);
                if (a.startsWith("android:attr/")) a = a.substring(13);
                else if (a.startsWith("attr/"))    a = a.substring(5);
                Object nx = mResolve.invoke(ri, appTheme, a);
                if (!(nx instanceof String)) return;
                String next = ((String) nx).trim();
                if (next.equals(styleName)) return;   // self-reference
                styleName = next;
            }
            if (styleName.startsWith("@style/"))                styleName = styleName.substring(7);
            else if (styleName.startsWith("@android:style/"))   styleName = styleName.substring(15);
            else if (styleName.startsWith("?"))                 return;  // still unresolved
            // PER-FAMILY attr lists, because the families genuinely differ and
            // asking for the union would serve a value AOSP does not have.
            //   buttonStyle -- geometry + face; macapkFrameworkButtonGeom carries
            //     the cited per-family numbers.
            //   radioButtonStyle / checkboxStyle -- the INDICATOR ONLY. AOSP's
            //     Widget.CompoundButton.* set NO minHeight/minWidth/padding at all
            //     and their Material variants add only a `background` that is a
            //     framework ripple we do not ship. That is the v1.0.863 refutation
            //     and it still stands; `button` is the one attr in the family that
            //     ends in painted pixels.
            final boolean compound = "radioButtonStyle".equals(defStyleAttr)
                                  || "checkboxStyle".equals(defStyleAttr);
            //   editTextStyle -- the BACKGROUND (the Material underline, whose
            //     reported padding is what actually moves layout) plus textSize.
            //     `textSize` is asked for ONLY here because it is the one attr in
            //     this family that our framework style table answers: AOSP's
            //     Widget.EditText carries `textAppearance =
            //     ?attr/textAppearanceMediumInverse` -> TextAppearance.Material
            //     .Medium -> text_size_medium_material = 18sp, which
            //     ResourceIndex.macapkFrameworkStyleDef flattens to a literal
            //     `textSize` the same way its TextAppearance.Material.* rows do.
            //     A bare TextView defaults to 14sp, so without it every field in
            //     every app renders 4sp small. `textColor` (?attr/editTextColor)
            //     rides along in the shared list but is NOT served by the
            //     framework table, deliberately -- see that table's note on why
            //     injecting colour here is a precedence hazard.
            final boolean editText = "editTextStyle".equals(defStyleAttr);
            // "focusable" / "clickable": Widget.Button and Widget.CompoundButton
            // declare both true (core/res styles.xml), and it is the STYLE that
            // makes a platform button focusable on a device. Without them a
            // Button could hold focus only if the app set it, so D-pad / Tab
            // focus skipped every plain button.
            for (String attr : (compound
                    ? new String[]{"button", "focusable", "clickable"}
                    : editText
                    ? new String[]{"background", "textSize", "minHeight",
                                   "minWidth", "gravity", "textColor"}
                    : new String[]{"background", "minHeight", "minWidth",
                                   "gravity", "textColor", "textAllCaps",
                                   "focusable", "clickable"})) {
                // `containsKey` is CORRECT here, and a v1.0.871 experiment that
                // said otherwise was reverted. The reasoning that looked right:
                // Material's `Widget.MaterialComponents.Button` declares
                // `<item name="android:background">@empty</item>`, that sentinel
                // flattens into sitems for any descendant style, and we then skip
                // the platform default -- which is why opensudoku's buttons get no
                // face while bobball's do.
                // Why it is wrong anyway: `@empty` is AAPT's EXPLICIT-no-value,
                // and Material declares it precisely to CLEAR the framework face
                // before MaterialButton installs its own. AOSP's precedence has
                // style= beating defStyleAttr, so the correct outcome is NO
                // background -- not the platform default we would substitute.
                // Treating it as absent inverts that.
                // Measured: it did not help opensudoku (70.10 either way) and
                // regressed nothing it fixed. opensudoku's real fix is the
                // `viewInflaterClass` tier (theme -> MaterialComponentsViewInflater
                // -> MaterialButton), not this.
                if (sitems.containsKey(attr)) continue;   // app's style wins
                Object v = mResolve.invoke(ri, styleName, attr);
                if (trace) android.util.Log.i("Mac.apk.DefStyle",
                    "  " + styleName + "." + attr + " = " + v);
                if (v instanceof String && !((String) v).isEmpty()) {
                    sitems.put(attr, (String) v);
                }
            }
            if (compound) macapkInstallButtonDrawable(view, sitems, ri, mResolve, appTheme, trace, elem);
        } catch (Throwable ignored) {}
    }

    /** AOSP widget-class to defStyleAttr, from each widget's own two-arg
     *  constructor (RadioButton chains to `this(context, attrs,
     *  com.android.internal.R.attr.radioButtonStyle)`). Keyed on SIMPLE class name
     *  so the app's AppCompat subclass -- which the inflater's rewrite table
     *  substitutes for the bare tag -- matches too. Returns null for anything not
     *  listed, which is the previous behaviour for every widget except these. */
    private static String macapkDefStyleAttrFor(String simpleName) {
        if (simpleName == null) return null;
        switch (simpleName) {
            case "Button":
            case "AppCompatButton":      return "buttonStyle";
            case "RadioButton":
            case "AppCompatRadioButton": return "radioButtonStyle";
            case "CheckBox":
            case "AppCompatCheckBox":    return "checkboxStyle";
            // ATTRSTYLE lane, 2026-08-13. AOSP EditText(Context, AttributeSet)
            // chains to `this(context, attrs, com.android.internal.R.attr
            // .editTextStyle)`, same as the three above. Without this row tier 3
            // never ran for a field, so `Widget.AppCompat.EditText`'s
            //     android:background = ?editTextBackground
            // (Base.Widget.AppCompat.EditText) never reached the view -- no
            // underline AND, the part that moves layout, none of the 42px of
            // padding the real edit_text_material reports (10dp+7dp of <inset>
            // plus the 9-patch's own 4dp bottom). Its 18sp from
            // @android:style/Widget.Material.EditText did not arrive either.
            //
            // MEASURED victim: unitconverter's two conversion fields sit in ONE
            // TableRow (res/layout/header_conversion.xml), so the row is short by
            // 42px of padding + 9.4px of line box = 51.4px predicted, and
            // everything below it rides up. Measured on the emulator/us pair:
            // the next feature below the fields lands at y=415 there and y=351
            // here, which after the 12px status-bar inset skew is 52px.
            case "EditText":
            case "AppCompatEditText":
                // Gated with the rest of the lane (Resources.MACAPK_ATTRSTYLE,
                // `MACAPK_ATTRSTYLE=0`) so the fleet A/B for a change that touches
                // EVERY EditText in every app comes from ONE binary rather than
                // from two builds taken ~15 minutes apart.
                return android.content.res.Resources.MACAPK_ATTRSTYLE
                    ? "editTextStyle" : null;
            default:                     return null;
        }
    }

    /** Install the CompoundButton indicator resolved by the tier-3 pass.
     *
     *  This does NOT route through applyAttribute like the other rows, because
     *  the value that arrives is `?attr/listChoiceIndicatorSingle` -- a theme
     *  delegation that has to be chased, and whose terminus is only HALF of a
     *  rendering. AOSP's CompoundButton constructor does the same two steps (read
     *  R.styleable.CompoundButton_button, setButtonDrawable); our widgets'
     *  constructors do no attribute resolution, so it happens here.
     *
     *  THE PAIRING IS ATOMIC AND THAT IS THE SAFETY PROPERTY. The drawable we can
     *  reach is AppCompat's `abc_btn_radio_material`, a selector over PNGs that are
     *  pure black MASKS. AppCompat always pairs them with
     *  `@color/abc_tint_btn_checkable` (?colorControlNormal / ?colorControlActivated).
     *  Installing the mask without the tint would paint a black blob AND grow every
     *  row by the drawable's 32dp intrinsic height, which is strictly worse than
     *  drawing nothing. So: no tint, no install.
     *
     *  The six-argument form is the one the offline gates reflect on (and the one
     *  the pinned pre-change control still has); it is this method with no layout
     *  element, i.e. nothing is known about an android:button on the tag. */
    private void macapkInstallButtonDrawable(View view,
                                             java.util.Map<String, String> sitems,
                                             Object ri,
                                             java.lang.reflect.Method mResolve,
                                             Object appTheme,
                                             boolean trace) {
        macapkInstallButtonDrawable(view, sitems, ri, mResolve, appTheme, trace, null);
    }

    /** @param elem the layout element the view was inflated from, or null. Read
     *              only to learn whether the TAG declares its own android:button
     *              (see the platform branch below). */
    private void macapkInstallButtonDrawable(View view,
                                             java.util.Map<String, String> sitems,
                                             Object ri,
                                             java.lang.reflect.Method mResolve,
                                             Object appTheme,
                                             boolean trace,
                                             Element elem) {
        try {
            if (!(view instanceof android.widget.CompoundButton cb)) return;
            String ref = sitems.get("button");
            if (ref == null) return;
            ref = ref.trim();
            // Chase a `?attr/` delegation against the app theme, exactly as AOSP
            // resolves a bag value of type ATTRIBUTE. Bounded, like the chase in
            // Resources.resolveDefStyleName.
            for (int hop = 0; hop < 4 && ref.startsWith("?"); hop++) {
                String a = ref.substring(1);
                if (a.startsWith("android:attr/")) a = a.substring(13);
                else if (a.startsWith("attr/"))    a = a.substring(5);
                Object nx = mResolve.invoke(ri, appTheme, a);
                if (!(nx instanceof String) || ((String) nx).trim().equals(ref)) return;
                ref = ((String) nx).trim();
            }
            android.content.res.Resources r = view.getResources();
            if (r == null) return;
            if (!ref.startsWith("@drawable/")) {
                // A PLATFORM indicator (`@android:drawable/X`) used to fall out
                // right here, before the synthesized fallback below could run.
                // See macapkInstallFrameworkButtonDrawable for the regression
                // and its gate; with the gate off this is the old early return.
                if (MACAPK_BUTTON_FW_REF && ref.startsWith("@android:drawable/")) {
                    // THE TAG'S OWN android:button OUTRANKS THIS DEFAULT. `ref`
                    // came from the defStyleAttr (tier 3); AOSP reads the
                    // indicator with obtainStyledAttributes(attrs,
                    // R.styleable.CompoundButton, defStyleAttr, defStyleRes),
                    // whose precedence is XML attribute > style= > defStyle >
                    // theme ("Any attribute values in the given AttributeSet"
                    // first, Resources.java android14-release :1648-1662). So a
                    // tag that says android:button="@null" is a TYPE_NULL value,
                    // TypedArray.getDrawableForDensity returns null for it
                    // (:999-1020), and CompoundButton.java:111-114 installs
                    // NOTHING. NewPipe's list_radio_icon_item and Thunderbird's
                    // and Signal's checkable rows draw their own indicator (or
                    // none) that way.
                    // Our framework CompoundButton never applies an inflated
                    // tag's android:button itself (applyAttribute has no
                    // "button" case), so without this check the stand-in would
                    // be painted over the app's deliberate "no indicator".
                    // Any declared value stops it, not only @null: a declared
                    // drawable is the app's, and inventing ours in its place is
                    // further from the device than leaving what the view has.
                    // An AppCompat helper that already installed art from it is
                    // kept either way by the getButtonDrawable guard.
                    // EXACT attribute, not macapkDeclares: that matches by
                    // prefix and would also fire on android:buttonTint.
                    org.w3c.dom.Attr own = elem == null ? null : elem.getAttributeNodeNS(
                        "http://schemas.android.com/apk/res/android", "button");
                    if (own != null) {
                        if (trace) android.util.Log.i("Mac.apk.DefStyle",
                            "  button=" + ref + " skipped: the tag declares android:button="
                            + own.getValue());
                        return;
                    }
                    macapkInstallFrameworkButtonDrawable(cb, r, ref.substring(18), trace);
                }
                return;
            }
            int did = r.getIdentifier(ref.substring(10), "drawable", null);
            int cid = did == 0 ? 0
                    : r.getIdentifier("abc_tint_btn_checkable", "color", null);
            if (did == 0 || cid == 0) {
                // NON-APPCOMPAT FALLBACK (2026-08-28). The style table answers
                // ?attr/listChoiceIndicatorMultiple|Single with the APP'S bundled
                // AppCompat twin (@drawable/abc_btn_check|radio_material), because
                // the real framework asset is an animated-vector we do not ship.
                // ~30 corpus APKs bundle it; the rest resolve to id 0 here and,
                // until now, got NO indicator at all -- the control rendered as
                // bare text. Same for an app that bundles the mask but not
                // AppCompat's @color/abc_tint_btn_checkable, since the mask is
                // pure black art that MUST be tinted (see that table's warning).
                //
                // MEASURED victim: Vector Pinball's "Unlimited Balls" CheckBox
                // measured 186x38 -- its text exactly, no room for a box -- while
                // the emulator draws a checkbox. Found via the fleet emudiff INK
                // IMBALANCE column, localised with MACAPK_TRACE_DEFSTYLE_APPLY=1.
                //
                // Fall back to the SYNTHESIZED framework indicator, whose geometry
                // and tint are decoded from the AOSP vectors (see
                // Resources.MacapkCompoundButtonDrawable). It colours itself from
                // the theme, so it needs no AppCompat tint.
                //
                // ⚠ ORDERING IS DELIBERATE: the app's real asset still WINS
                // whenever both it and the tint resolve, so an AppCompat app
                // renders byte-identically to before this change.
                String synth = (view instanceof android.widget.RadioButton)
                        ? "btn_radio_material" : "btn_check_material";
                android.graphics.drawable.Drawable fb =
                        r.macapkFrameworkDrawableByName(synth);
                if (fb == null) return;
                if (trace) android.util.Log.i("Mac.apk.DefStyle",
                    "  button=" + ref + " unresolved (did=" + did + " tintId=" + cid
                    + ") -> synthesized " + synth);
                cb.setButtonDrawable(fb);
                return;
            }
            android.content.res.Resources.Theme th = null;
            try {
                android.content.Context vc = view.getContext();
                if (vc != null) th = vc.getTheme();
            } catch (Throwable ignored) {}
            android.content.res.ColorStateList tint = r.getColorStateList(cid, th);
            if (tint == null) return;
            android.graphics.drawable.Drawable d = r.getDrawable(did, th);
            if (d == null) return;
            if (trace) android.util.Log.i("Mac.apk.DefStyle",
                "  button=" + ref + " tint=" + tint.getDefaultColor() + " -> " + d);
            cb.setButtonTintList(tint);
            cb.setButtonDrawable(d);
        } catch (Throwable ignored) {}
    }

    /** Wave 5 (2026-09-26), docs/w5-inflater-fixes_2026-09-26.md F3. Default ON;
     *  `MACAPK_BUTTON_FW_REF=0` restores the early return in
     *  macapkInstallButtonDrawable for an `@android:drawable/` indicator, so a
     *  plain framework-themed CheckBox is again drawn with no box (the regressed
     *  state), from one binary. */
    static final boolean MACAPK_BUTTON_FW_REF =
        !"0".equals(System.getenv("MACAPK_BUTTON_FW_REF"));

    /** The indicator for a CompoundButton whose `button` chases to a PLATFORM
     *  drawable, `@android:drawable/<name>`.
     *
     *  REGRESSION this closes. 29587d84 (2026-08-28) gave Vector Pinball's
     *  "Unlimited Balls" CheckBox a box (186x38 -> 250x64) through the
     *  synthesized fallback in macapkInstallButtonDrawable, reached when the
     *  chase ended on an app `@drawable/` that did not resolve (AppCompat's
     *  twin name, which the style table served then). The chase now ends on
     *  the platform's own spelling, because the theme tables answer with the
     *  real framework value (the twin swap was retired at v1.0.922, see
     *  ResourceIndex.macapkFwStyleAttr; the exact commit that made this the
     *  default answer is not bisected -- the window is 08-28 09:15 to 08-31
     *  09:24, and MACAPK_FW_VIEW=0 alone does not bring the twin back):
     *      Widget.CompoundButton.CheckBox  button = ?attr/listChoiceIndicatorMultiple
     *          (android14-release core/res/res/values/styles.xml:390-392)
     *      Theme.Material  listChoiceIndicatorMultiple = @drawable/btn_check_material_anim
     *          (themes_material.xml:137-138 and :510-511)
     *  and the old guard returned on anything not spelled `@drawable/`, so the
     *  checkbox measured 186x38 again (fleet tablet A/B, and offline on both
     *  the 05:07 jars and the working tree).
     *
     *  AOSP reads the indicator as `a.getDrawable(CompoundButton_button)` and
     *  installs whatever the platform ships (CompoundButton.java:111-114,
     *  android14-release). So:
     *   - The Material listChoiceIndicator assets, `btn_{check,radio}_material_anim`,
     *     get the SYNTHESIZED indicator (Resources.MacapkCompoundButtonDrawable),
     *     the stand-in that fallback already used. The real asset is an
     *     animated-selector over vectors tinted @color/control_checkable_material
     *     (btn_check_material_anim.xml:16-33, btn_checkbox_unchecked_mtrl.xml:22),
     *     and our platform-table load of it loses the CHECKED tint. Measured
     *     offline under bouncy's Theme.VPTheme, 64x64 face, checked state:
     *     table asset dominant #ffffffff (colorControlNormal), synthesized
     *     #ff80cbc4 (colorControlActivated), which is the row AOSP selects.
     *   - Any other platform name (Holo's btn_check_holo_dark, the classic
     *     btn_check, a star's btn_star) loads the PLATFORM ASSET itself through
     *     macapkFrameworkDrawableByName, which is AOSP's getDrawable. Those are
     *     PNG selectors that need no tint.
     *   - If that yields nothing, the synthesized indicator for the widget's
     *     kind, for the same reason the app-namespace fallback uses it: a
     *     CompoundButton with no indicator draws as bare text, which is further
     *     from the device than a stand-in box.
     *
     *  NEVER REPLACES A DRAWABLE THE VIEW ALREADY HAS. On a device the framework
     *  CompoundButton constructor installs the platform indicator FIRST and a
     *  subclass constructor (AppCompatCheckBox's AppCompatCompoundButtonHelper,
     *  an app's own subclass) overrides it afterwards, so the subclass art wins.
     *  Our framework constructor installs nothing for an inflated view, and this
     *  runs AFTER construction, so an indicator already present came from a
     *  subclass and must be kept. The pre-existing app-namespace paths above are
     *  left exactly as they were.
     *
     *  NEVER RUNS FOR A TAG THAT DECLARES ITS OWN android:button (the caller
     *  checks): the XML attribute outranks the defStyle this default comes from,
     *  so `android:button="@null"` means no indicator on a device. Without that
     *  check the stand-in was painted on NewPipe's list_radio_icon_item (measured
     *  offline), and would be on Signal's eight @null AppCompatCheckBoxes whenever
     *  AppCompat's helper leaves the button empty (fix-pass review, 2026-09-26). */
    private static void macapkInstallFrameworkButtonDrawable(android.widget.CompoundButton cb,
                                                            android.content.res.Resources r,
                                                            String name, boolean trace) {
        try {
            if (cb.getButtonDrawable() != null || name == null || name.isEmpty()) return;
            final boolean radio = cb instanceof android.widget.RadioButton;
            String standIn = "btn_check_material_anim".equals(name) ? "btn_check_material"
                           : "btn_radio_material_anim".equals(name) ? "btn_radio_material"
                           : null;
            android.graphics.drawable.Drawable d = null;
            if (standIn == null) {
                android.content.res.Resources.Theme th = null;
                try {
                    android.content.Context vc = cb.getContext();
                    if (vc != null) th = vc.getTheme();
                } catch (Throwable ignored) {}
                d = r.macapkFrameworkDrawableByName(name, th);
                if (d == null) standIn = radio ? "btn_radio_material" : "btn_check_material";
            }
            if (d == null) d = r.macapkFrameworkDrawableByName(standIn);
            if (d == null) return;
            if (trace) android.util.Log.i("Mac.apk.DefStyle",
                "  button=@android:drawable/" + name + " -> "
                + (standIn != null ? "synthesized " + standIn : "platform asset " + d));
            cb.setButtonDrawable(d);
        } catch (Throwable ignored) {}
    }

    private void applyMaterialButtonStyle(View view, java.util.Map<String, String> sitems,
                                          Element elem) {
        try {
            if (view == null || sitems == null) return;
            String cn = view.getClass().getSimpleName();
            if (!cn.endsWith("MaterialButton")) return;
            // AOSP attribute PRECEDENCE, from Resources.Theme.obtainStyledAttributes(
            // AttributeSet, int[], int, int): "there are four inputs ... 1. Any
            // attribute values in the given AttributeSet. 2. The style resource
            // specified in the AttributeSet (named "style"). 3. The default style
            // specified by defStyleAttr and defStyleRes. 4. The base values in this
            // theme. ... Each of these inputs is considered in-order, with the
            // first listed taking precedence over the following ones."
            // MaterialButton enters that call with defStyleAttr =
            // R.attr.materialButtonStyle and pushes the winning tint into its
            // MaterialShapeDrawable through its setSupportBackgroundTintList
            // override, which is why a tag's own backgroundTint really does beat
            // the style's.
            //
            // The old code read tier 2 ONLY (sitems) and recognised ONE selector by
            // name, so a tag carrying its own tint and no style= matched nothing.
            // Measured victim: AnkiDroid's fragment_introduction.xml declares
            // android:backgroundTint="@color/material_blue_500" (#2196f3) on both
            // intro buttons and got no fill at all, 0 px against the emulator's
            // 56,983. Its hardcoded colorPrimary was wrong for that app anyway
            // (Base.Theme.Light binds colorPrimary to material_light_blue_500,
            // #03a9f4), which is the tell that the name match was standing in for a
            // mechanism rather than implementing one.
            //
            // We cannot read the tint back off the View: MaterialButton OVERRIDES
            // setBackgroundTintList and forwards to its own helper without calling
            // super, so View.macapkBackgroundTint is never populated for this widget
            // and the declared value has to come from the DOM element.
            // Resolve through the VIEW'S OWN theme, not the theme-less overload.
            // A Material button's tint is routinely a `?attr` -- opensudoku's
            // AppTheme.TextButton declares `backgroundTint = ?colorSecondaryContainer`
            // -- and the 1-arg resolver cannot chase one, so it returned null and the
            // pill never painted. Same two-anchor lesson as v1.0.897: resolve against
            // the theme the CALLER is using. Falls back to the theme-less form when
            // the view has no themed Context yet.
            android.content.res.Resources.Theme vth = null;
            try {
                android.content.Context vc = view.getContext();
                if (vc != null) vth = vc.getTheme();
            } catch (Throwable ignored) {}
            String rawTint = macapkElementAttr(elem, "backgroundTint");
            if (rawTint == null) rawTint = sitems.get("backgroundTint");
            if (System.getenv("MACAPK_TRACE_MTLBTN") != null) {
                android.util.Log.i("Mac.apk.MtlBtn", "cn=" + cn
                    + " elemTint=" + macapkElementAttr(elem, "backgroundTint")
                    + " styleTint=" + sitems.get("backgroundTint")
                    + " raw=" + rawTint
                    + " resolved=" + (rawTint == null ? null : macapkResolveColorValue(rawTint, vth))
                    + " overlay=" + macapkMaterialOverlayColor(elem, "colorContainer"));
            }
            if (rawTint == null) return;   // no tint declared anywhere: not ours to paint
            Integer fill = macapkResolveColorValue(rawTint, vth);
            if (fill == null) {
                // The declared value is a res/color STATE SELECTOR, which the colour
                // expression table cannot flatten (that is the ColorStateList work,
                // deliberately not duplicated here). Ask the style chain the same
                // question the selector asks: every M3 button background selector's
                // stateless default row is ?colorContainer, and the widget style's
                // materialThemeOverlay rebinds that token per variant, which is how
                // one selector serves filled / tonal / elevated / outlined / text.
                fill = macapkMaterialOverlayColor(elem, "colorContainer");
            }
            // Alpha 0 is how a text or outlined button says "no container": its
            // overlay binds colorContainer to @android:color/transparent. Skipping
            // on the resolved VALUE is what lets the selector name disappear from
            // this method entirely.
            if (fill == null || (fill >>> 24) == 0) return;
            view.macapkButtonFill = fill;
            // ⚠ TRIED AND REVERTED 2026-08-08: also pushing `fill` into
            // view.macapkBackgroundTint{,List} directly, to tint a Material button
            // that HAS a real background drawable (MaterialButton overrides
            // setBackgroundTintList and swallows it, so the ordinary attribute
            // path cannot reach one). It restored opencalculator's #b4d2e4 to
            // 183,460 under the flip and then dropped that app to 67.45 anyway,
            // so something else it touches is worse than the thing it fixed and
            // the mechanism is not yet understood. Not shipped on a guess.
            int density = Math.max(1, Math.round(android.util.DisplayMetrics.macapkDeviceDensity()));
            // The pill is INSET vertically from the view: Material's button styles
            // set android:insetTop/insetBottom (m3_btn_inset 4dp on Material3,
            // mtrl_btn_inset 6dp on the MaterialComponents baseline), so a 48dp
            // button draws a 40dp background. Read whatever the app declared, in
            // the same precedence order as the tint, and fall back to the M3
            // default rather than 0 -- every style in the family sets one, so 0 is
            // never the right guess, and painting the full rect overshot the
            // emulator by ~12,000 px on AnkiDroid.
            try {
                android.util.DisplayMetrics dm = view.getResources() != null
                    ? view.getResources().getDisplayMetrics() : null;
                if (dm != null && dm.density > 0f) density = Math.max(1, (int) dm.density);
            } catch (Throwable ignored) {}
            {
                String rawInset = macapkElementAttr(elem, "insetTop");
                if (rawInset == null) rawInset = sitems.get("insetTop");
                Float insetPx = (rawInset == null) ? null : parseDimensionPx(rawInset);
                view.macapkButtonInsetPx = (insetPx != null)
                    ? Math.round(insetPx) : 4 * density;
            }
            if (view instanceof android.widget.TextView tv) {
                // The label is the FOREGROUND token, not a hardcoded white.
                // Widget.Material3.Button sets android:textColor to
                // @color/m3_button_foreground_color_selector, whose stateless row is
                // ?colorOnContainer, which the same overlay binds to ?colorOnPrimary.
                // Substitute it only when nothing we could actually resolve declared
                // a colour, so an explicit android:textColor keeps its tier-1
                // precedence. A style item we could NOT resolve is the very selector
                // we are standing in for, so it does not count as declared.
                String rawText = macapkElementAttr(elem, "textColor");
                if (rawText == null) rawText = sitems.get("textColor");
                if (rawText == null || macapkResolveColorValue(rawText, vth) == null) {
                    Integer on = macapkMaterialOverlayColor(elem, "colorOnContainer");
                    if (on == null || (on >>> 24) == 0) {
                        on = android.content.res.Resources.macapkThemeColor(
                            "colorOnPrimary", 0xFFFFFFFF);
                    }
                    tv.setTextColor(on);
                }
                // Geometry, cited, and applied only where the app declared nothing,
                // because these are tier-3 defaults and tiers 1 and 2 outrank them.
                //   gravity + minHeight 48dp: AOSP core/res/res/values/
                //     styles_material.xml Widget.Material.Button, which every
                //     Material button inherits through Widget.Material3.Button ->
                //     Widget.MaterialComponents.Button -> Widget.AppCompat.Button ->
                //     Base.Widget.AppCompat.Button -> @android:style/Widget.Material.Button.
                //   padding 24dp horizontal, 6dp vertical: Widget.Material3.Button's
                //     m3_btn_padding_left/right = 24dp and m3_btn_padding_top/bottom
                //     = 6dp. This applied 8dp, which is a MaterialComponents-era
                //     number, not an M3 one.
                if (!macapkDeclares(elem, sitems, "gravity")) {
                    tv.setGravity(android.view.Gravity.CENTER);
                }
                if (!macapkDeclares(elem, sitems, "minHeight")) {
                    tv.setMinHeight(48 * density);
                }
                // Cluster B3: not when the constructor already resolved the
                // default-style padding this stands in for (and MaterialButton
                // added its insets to it): anki get_started 48,20,48,20 on a
                // device, this wrote 48,12,48,12 over it.
                if (!macapkDeclares(elem, sitems, "padding")
                        && !(MACAPK_CTOR_PADDING_SKIP && view.macapkCtorPaddingMask != 0)) {
                    int hp = 24 * density, vp = 6 * density;
                    tv.setPadding(hp, vp, hp, vp);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** One element attribute by LOCAL name, ignoring the namespace prefix.
     *  backgroundTint arrives as `android:backgroundTint` on a plain tag and as
     *  `app:backgroundTint` on a Material one, and MaterialButton reads them
     *  through the same styleable, so the prefix must not decide whether we see
     *  the value. Same local-name rule the attribute loops already use. */
    private static String macapkElementAttr(Element elem, String name) {
        if (elem == null || name == null) return null;
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs == null) return null;
        for (int i = 0; i < attrs.getLength(); ++i) {
            Node a = attrs.item(i);
            String ln = a.getLocalName();
            if (ln == null) {
                ln = a.getNodeName();
                int c = ln.indexOf(':');
                if (c >= 0) ln = ln.substring(c + 1);
            }
            if (name.equals(ln)) {
                String v = a.getNodeValue();
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** True when the element or its style= cascade declares an attribute whose
     *  local name starts with `prefix`. Guards the tier-3 Material defaults so
     *  they never overwrite something the app stated: AOSP considers the tag
     *  first, then the style, then the default style, and we are standing in for
     *  the LAST of those. `padding` is a PREFIX rather than an exact name because
     *  padding / paddingTop / paddingHorizontal / paddingStart all claim the same
     *  box; `gravity` as a prefix still excludes `layout_gravity`, which does not
     *  start with it. */
    private static boolean macapkDeclares(Element elem, java.util.Map<String, String> sitems,
                                          String prefix) {
        if (sitems != null) {
            for (String k : sitems.keySet()) {
                if (k != null && k.startsWith(prefix)) return true;
            }
        }
        if (elem == null) return false;
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs == null) return false;
        for (int i = 0; i < attrs.getLength(); ++i) {
            Node a = attrs.item(i);
            String ln = a.getLocalName();
            if (ln == null) {
                ln = a.getNodeName();
                int c = ln.indexOf(':');
                if (c >= 0) ln = ln.substring(c + 1);
            }
            if (ln != null && ln.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The Material CONTAINER (or ON-CONTAINER) colour for this button, derived
     *  from its effective style instead of from a selector name.
     *
     *  Every M3 button style points backgroundTint at a res/color selector whose
     *  stateless row is `?colorContainer`, and android:textColor at one whose
     *  stateless row is `?colorOnContainer`. Those two tokens are rebound per
     *  variant by the style's materialThemeOverlay, which is how a single pair of
     *  selectors serves the filled, tonal, elevated, outlined and text buttons:
     *    ThemeOverlay.Material3.Button            colorContainer = ?colorPrimary
     *    ThemeOverlay.Material3.Button.TonalButton               = ?colorSecondaryContainer
     *    ThemeOverlay.Material3.Button.TextButton                = @android:color/transparent
     *  So reading the token answers "is this filled, and with what" for every
     *  variant at once, and the transparent binding is what makes text and
     *  outlined buttons skip themselves without ever being named.
     *
     *  materialThemeOverlay / colorContainer / colorOnContainer are Material
     *  CONTRACT names, identical in every app that ships the library. Nothing here
     *  knows a selector name, a colour name or a package name. */
    private Integer macapkMaterialOverlayColor(Element elem, String targetAttr) {
        try {
            Object ri = android.content.res.Resources.macapkGetResourceIndex();
            if (ri == null) return null;
            java.lang.reflect.Method mResolve = ri.getClass()
                .getMethod("resolveAttrInTheme", String.class, String.class);
            Object appTheme = ri.getClass().getMethod("getApplicationThemeName").invoke(ri);
            if (!(appTheme instanceof String)) return null;
            String theme = (String) appTheme;
            // Tier 2 then tier 3: the tag's own style=, else the theme's
            // materialButtonStyle, which is the defStyleAttr MaterialButton passes.
            String styleRef = macapkElementAttr(elem, "style");
            if (styleRef != null && styleRef.startsWith("?")) {
                String attr = styleRef.substring(1);
                int sl = attr.indexOf('/');
                if (sl >= 0) attr = attr.substring(sl + 1);   // ?attr/x -> x
                Object v = mResolve.invoke(ri, theme, attr);
                styleRef = (v instanceof String) ? (String) v : null;
            }
            if (styleRef == null) {
                Object v = mResolve.invoke(ri, theme, "materialButtonStyle");
                styleRef = (v instanceof String) ? (String) v : null;
            }
            String styleName = macapkStyleRefName(styleRef);
            if (styleName == null) return null;
            String raw = null;
            Object ov = mResolve.invoke(ri, styleName, "materialThemeOverlay");
            String overlay = macapkStyleRefName(ov instanceof String ? (String) ov : null);
            if (overlay != null) {
                Object t = mResolve.invoke(ri, overlay, targetAttr);
                if (t instanceof String) raw = (String) t;
            }
            if (raw == null) {
                // No overlay, or it does not carry the token: the Material baseline
                // pattern puts the colour straight on the widget style.
                Object t = mResolve.invoke(ri, styleName, targetAttr);
                if (t instanceof String) raw = (String) t;
            }
            if (raw == null) return null;
            return macapkResolveColorValue(raw);
        } catch (Throwable ignored) {}
        return null;
    }

    /** "@style/X" / "@android:style/X" -> "X"; a still-unresolved `?attr` ref, or
     *  any other resource type, -> null. */
    private static String macapkStyleRefName(String ref) {
        if (ref == null) return null;
        String s = ref.trim();
        if (s.isEmpty() || s.startsWith("?")) return null;
        if (s.startsWith("@style/")) return s.substring(7);
        if (s.startsWith("@android:style/")) return s.substring(15);
        if (s.startsWith("@")) return null;
        return s;
    }

    private void applyAttribute(View view, String attr, String value) {
        if (attr == null || value == null) return;
        try {
            switch (attr) {
                case "id":
                    // android:id="@id/foo" or "@+id/foo". The value is
                    // the resource alias; Resources resolves it to an
                    // int id. We register the View's id so
                    // findViewById can find it.
                    int id = parseResourceId(value);
                    if (id != 0) view.setId(id);
                    return;
                case "tag":
                    // android:tag was DROPPED: no case here, so it fell to
                    // `default: return` and no inflated view ever carried an
                    // XML-declared tag. AOSP's View constructor reads it as
                    //   case com.android.internal.R.styleable.View_tag:
                    //       mTag = a.getText(attr);
                    // (android14 core/java/android/view/View.java:5803-5805).
                    // Once ViewGroup.findViewWithTagTraversal descended to
                    // children (v1.0.1645), this was the remaining reason
                    // findViewWithTag returned null for any tag authored in
                    // layout XML; only setTag() tags could be found.
                    // getText resolves a @string/ reference to the string's
                    // value rather than keeping the raw "@string/x", which is
                    // what resolveString does for android:text next door.
                    // setTag writes the same field (our mTag) the leaf
                    // traversal compares against.
                    // AOSP reads android:tag in the View CONSTRUCTOR, i.e. before a
                    // subclass ctor body runs, so a tag the view set on itself wins.
                    // We apply attributes after construction, so never overwrite a
                    // tag the view already carries.
                    if (view.getTag() == null) view.setTag(resolveString(value));
                    return;
                case "background":
                    applyBackground(view, value);
                    return;
                case "fitsSystemWindows":
                    // AOSP: marks the view as wanting to apply the system-
                    // window insets. F-Droid's AppBarLayout + FeatureImage use
                    // it for the edge-to-edge hero (ViewCanvasDriver's appbar-
                    // grow fixup keys on it). Apply generically.
                    view.setFitsSystemWindows("true".equals(value));
                    return;
                case "duplicateParentState":
                    // AOSP View ctor: `case View_duplicateParentState: if
                    // (a.getBoolean(attr, false)) viewFlagValues |=
                    // DUPLICATE_PARENT_STATE;` -- only a literal true sets it.
                    // The View field and the onCreateDrawableState parent
                    // delegation branch already existed (View.java:2410-2412,
                    // :4296); this attribute was simply never parsed, so no
                    // inflated view ever mirrored its parent's checked / pressed
                    // / selected state. Material's own
                    // design_bottom_navigation_item.xml puts it on the icon, the
                    // labels group and BOTH labels, and it is the only way a nav
                    // label learns its item view is CHECKED.
                    if ("true".equals(value)) view.setDuplicateParentStateEnabled(true);
                    return;
                case "backgroundTint": {
                    // API 21+ android:backgroundTint -- recolors the
                    // background (SRC_IN). Resolves theme refs
                    // (?colorSurfaceContainerHighest) through the
                    // cascade; the M3 button look depends on it.
                    //
                    // v1.0.920: write the View's tint FIELDS directly instead
                    // of calling the virtual setter. AOSP's View constructor
                    // stores an XML-declared tint straight into
                    // mBackgroundTint.mTintList ("This will get applied later
                    // during setBackground()", View.java:5858-5866,
                    // android11-release) and never dispatches virtually, so a
                    // subclass override never sees an inflation-time tint on a
                    // device. Ours did dispatch, and MaterialButton's override
                    // (setBackgroundTintList -> setSupportBackgroundTintList
                    // -> MaterialButtonHelper, verified in opencalculator's
                    // own R8 bytecode) swallowed the tint without touching our
                    // fields -- which is the whole viewInflaterClass-flip
                    // regression: the 10 tinted keypad keys lost their
                    // per-key ?button_*_color and painted the untinted
                    // ?button_color solid, #b4d2e4 183,460 px -> 0.
                    // AppCompatButton does not override the setter, which is
                    // why the same layout painted correctly WITHOUT the flip.
                    Integer tc = macapkResolveColorValue(value);
                    if (tc != null) {
                        view.macapkSetInflatedBackgroundTint(
                            android.content.res.ColorStateList.valueOf(tc));
                    }
                    return;
                }
                // v1.0.841: android:tint / app:tint on an ImageView -- the local
                // name is identical for both namespace forms (this loop already
                // strips the prefix, same as backgroundTint above), so one case
                // handles both. MEASURED gap (docs/maps-calculator_2026-07-31.md):
                // Organic Maps' download icon (ic_download.xml) is authored with
                // `android:fillColor="#fff"` -- WHITE -- and relies entirely on
                // `app:tint="?android:colorAccent"` to become visible against its
                // white background; without this case the tint was silently
                // dropped and the icon rendered white-on-white (present in the
                // tree, correctly bounded, invisible). This is the standard
                // Android icon-asset convention (author in white, recolor via
                // tint at display time), so the same gap applies to any app's
                // any icon following it, not just this one.
                // ImageView.setImageTintList(...) + applyImageTint() already
                // exist and are exercised elsewhere in this tree (this method's
                // own comment cites CompoundButton.applyButtonTint / ProgressBar
                // as prior art) -- only the inflater-side wiring was missing.
                case "tint":
                    if (view instanceof android.widget.ImageView iv2) {
                        Integer tintColor = macapkResolveColorValue(value);
                        if (tintColor != null) {
                            iv2.setImageTintList(
                                android.content.res.ColorStateList.valueOf(tintColor));
                        }
                    }
                    return;
                case "src":
                // `app:srcCompat` is AppCompat's namespaced twin of
                // `android:src`. On real Android AppCompat never lets the
                // framework inflater see a bare `<ImageView>`/`<ImageButton>`
                // tag: AppCompatViewInflater rewrites it to
                // AppCompatImageView/AppCompatImageButton, whose
                // AppCompatImageHelper.loadFromAttributes does
                //     final int id = a.getResourceId(
                //             R.styleable.AppCompatImageView_srcCompat, -1);
                //     if (id != -1) { Drawable d = AppCompatResources
                //             .getDrawable(mView.getContext(), id);
                //         if (d != null) mView.setImageDrawable(d); }
                // (androidx-main, appcompat/.../AppCompatImageHelper.java).
                // We ARE the inflater and we instantiate our own
                // android.widget.ImageView, which knows nothing about
                // srcCompat -- so the attribute was silently DROPPED and the
                // ImageView stayed drawable=null.
                //
                // Measured on Open Sudoku 20260625 (2026-07-29): its title
                // screen is `<ImageView layout_height="0dp" layout_weight="1"
                // app:srcCompat="@mipmap/banner"/>` plus a button column. The
                // layout was fully correct (banner ImageView laid out
                // 694x1501, 91% of the content area) but had drawable=null, so
                // the only thing that painted was the three button labels:
                // drawn=7223 of 1,471,712. Same attribute is on OpenCalculator's
                // and Google Calculator's ImageButtons. Treating it as an alias
                // of android:src is exactly what AppCompat does.
                case "srcCompat":
                    if (view instanceof android.widget.ImageView iv) {
                        applyImageSrc(iv, value);
                    }
                    return;
                case "barrierDirection": {
                    // androidx ConstraintLayout Barrier. Its constructor reads
                    // constraint_referenced_ids (a String -- works) but the
                    // barrierDirection ENUM falls through our obtainStyledAttributes
                    // (which returns 0=LEFT for unmapped enums), so a `bottom`
                    // barrier became a LEFT barrier -> resolved to a garbage x and
                    // never pushed dependent views (the F-Droid AppDetails install
                    // button overlapped the icon). Apply it via Barrier.setType.
                    // Generic: any Barrier in any app.
                    int dir = parseBarrierDirection(value);
                    if (dir >= 0) {
                        try { view.getClass().getMethod("setType", int.class).invoke(view, dir); }
                        catch (Throwable ignored) {}
                    }
                    return;
                }
                case "scaleType":
                    // AOSP ImageView scaleType. F-Droid's feature-graphic header
                    // is centerCrop (the app icon/graphic fills + crops the hero
                    // region); screenshots are fitXY (the item box is already
                    // aspect-sized via adjustViewBounds, so fitXY == no distortion).
                    if (view instanceof android.widget.ImageView ivst) {
                        android.widget.ImageView.ScaleType st = parseScaleType(value);
                        if (st != null) ivst.setScaleType(st);
                    }
                    return;
                case "adjustViewBounds":
                    // AOSP ImageView: resize the view to preserve the drawable's
                    // aspect ratio. F-Droid's screenshot items
                    // (app_details2_screenshot_item.xml) are height=match_parent +
                    // width=wrap_content + adjustViewBounds=true, so the width is
                    // derived from each screenshot's aspect at the fixed row height.
                    if (view instanceof android.widget.ImageView ivab) {
                        ivab.setAdjustViewBounds("true".equals(value));
                    }
                    return;
                case "minWidth": {
                    Integer mw = parseDimensionPxInt(value);
                    if (mw != null) view.setMinimumWidth(mw);
                    return;
                }
                case "minHeight": {
                    Integer mh = parseDimensionPxInt(value);
                    if (mh != null) view.setMinimumHeight(mh);
                    return;
                }
                case "maxWidth":
                    if (view instanceof android.widget.ImageView ivmw) {
                        Integer v = parseDimensionPxInt(value);
                        if (v != null) ivmw.setMaxWidth(v);
                    }
                    return;
                case "maxHeight":
                    if (view instanceof android.widget.ImageView ivmh) {
                        Integer v = parseDimensionPxInt(value);
                        if (v != null) ivmh.setMaxHeight(v);
                    }
                    return;
                case "text":
                    if (view instanceof android.widget.TextView tv) {
                        tv.setText(resolveString(value));
                    }
                    return;
                case "hint":
                    // android:hint was DROPPED entirely -- no case here and no
                    // reflective fallback, so it fell to `default: return`.
                    // Combined with TextView.onDraw's empty-text early return,
                    // that meant every placeholder in every search box, login
                    // field and form in the fleet rendered blank. AOSP reads it
                    // as R.styleable.TextView_hint in the TextView constructor
                    // and calls setHint (core/java/android/widget/TextView.java,
                    // setHintInternal :7681-7697); TextView.onDraw then selects
                    // mHintLayout whenever mText is empty (:7013-7016).
                    if (view instanceof android.widget.TextView tvhint) {
                        tvhint.setHint(resolveString(value));
                    }
                    return;
                case "textColorHint":
                    // The colour half of the same attribute pair. AOSP maps
                    // R.styleable.TextView_textColorHint to setHintTextColor
                    // (TextView.java:5857-5875), and getCurrentHintTextColor
                    // (:5897-5900) falls back to the TEXT colour when it was
                    // never supplied -- which would paint a placeholder at full
                    // strength, indistinguishable from typed text.
                    // A `@color/` value that is a state SELECTOR rather than a
                    // flat colour resolves to null here (same limitation the
                    // android:textColor case above has) and leaves the theme's
                    // textColorHint in place, which is the honest fallback.
                    {
                        if (view instanceof android.widget.TextView tvh) {
                            Integer hc = macapkResolveColorValue(value);
                            if (hc != null) tvh.setHintTextColor(hc.intValue());
                        }
                    }
                    return;
                // ---- The rest of the TextView attribute family the inflater
                // never wired. Every one of these has a working SETTER in
                // TextView (and, where relevant, a working measure/draw path);
                // only the XML route was missing, so the declared value was
                // dropped at `default: return` and the widget silently ran on
                // its default. Same shape as the android:hint gap above.
                case "maxLength": {
                    // AOSP TextView ctor (:1471-1473, :1886-1892):
                    //     maxlength = a.getInt(attr, -1);
                    //     ... if (maxlength >= 0)
                    //             setFilters(new InputFilter[] {
                    //                 new InputFilter.LengthFilter(maxlength) });
                    //         else setFilters(NO_FILTERS);
                    // TextView.setFilters already stores and applies the array
                    // (v1.0.930 note at its declaration), so an unwired
                    // android:maxLength meant a field declared "max 4 digits"
                    // accepted unbounded input.
                    if (view instanceof android.widget.TextView tvml) {
                        try {
                            int n = Integer.parseInt(value.trim());
                            tvml.setFilters(n >= 0
                                ? new android.text.InputFilter[] {
                                      new android.text.InputFilter.LengthFilter(n) }
                                : new android.text.InputFilter[0]);
                        } catch (NumberFormatException ignored) {}
                    }
                    return;
                }
                case "digits": {
                    // AOSP TextView ctor (:1309-1311, :1709-1711):
                    //     digits = a.getText(attr);
                    //     ... mEditor.mKeyListener =
                    //             DigitsKeyListener.getInstance(digits.toString());
                    // i.e. the attribute names the ACCEPTED character set. Our
                    // DigitsKeyListener.getInstance(String) builds exactly that.
                    if (view instanceof android.widget.TextView tvdg) {
                        String d = resolveString(value);
                        if (d != null && !d.isEmpty()) {
                            tvdg.setKeyListener(
                                android.text.method.DigitsKeyListener.getInstance(d));
                        }
                    }
                    return;
                }
                case "letterSpacing": {
                    // AOSP reads it with getFloat, NOT getDimension: the value
                    // is in EM units (TextView_letterSpacing ->
                    // setLetterSpacing(attributes.mLetterSpacing), :4509-4511),
                    // so parseDimensionPx would be wrong here. TextView backs
                    // the accessor with the Paint and Paint already implements
                    // the EM-scaled advance (see TextView.setLetterSpacing's
                    // header), so only this wire was missing for an
                    // android:letterSpacing declared directly on a view rather
                    // than through a textAppearance.
                    if (view instanceof android.widget.TextView tvls) {
                        try { tvls.setLetterSpacing(Float.parseFloat(value.trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                    return;
                }
                case "shadowColor": case "shadowDx":
                case "shadowDy":    case "shadowRadius": {
                    // AOSP collects all four into a TextAppearanceAttributes and
                    // applies them together (:4406-4415, :4492-4494):
                    //     if (attributes.mShadowColor != 0)
                    //         setShadowLayer(mShadowRadius, mShadowDx, mShadowDy,
                    //                        mShadowColor);
                    // The four arrive here one at a time in document order, so
                    // each one READS the other three back off the view and
                    // re-applies the merged tuple -- order-independent. dx / dy /
                    // radius are getFloat in AOSP (raw floats, not dimensions).
                    // Applying with colour 0 is harmless and matches AOSP's
                    // gate: TextView.onDraw skips the shadow pass unless the
                    // colour is non-zero.
                    if (view instanceof android.widget.TextView tvsh) {
                        float r = tvsh.getShadowRadius(), dx = tvsh.getShadowDx(),
                              dy = tvsh.getShadowDy();
                        int col = tvsh.getShadowColor();
                        if ("shadowColor".equals(attr)) {
                            Integer sc = macapkResolveColorValue(value);
                            if (sc == null) return;
                            col = sc.intValue();
                        } else {
                            float f;
                            try { f = Float.parseFloat(value.trim()); }
                            catch (NumberFormatException e) { return; }
                            if ("shadowDx".equals(attr)) dx = f;
                            else if ("shadowDy".equals(attr)) dy = f;
                            else r = f;
                        }
                        tvsh.setShadowLayer(r, dx, dy, col);
                    }
                    return;
                }
                case "drawablePadding": {
                    // AOSP TextView ctor (:1374-1376): a DIMENSION, fed to
                    // setCompoundDrawablePadding. It is load-bearing for layout,
                    // not just looks: getCompoundPaddingLeft/Right add it to the
                    // content box, so an unwired value made an icon+label row
                    // measure narrower than the device does.
                    if (view instanceof android.widget.TextView tvdp) {
                        Integer px = parseDimensionPxInt(value);
                        if (px != null) tvdp.setCompoundDrawablePadding(px.intValue());
                    }
                    return;
                }
                case "textColor":
                    if (view instanceof android.widget.TextView tv) {
                        // Full resolver (hex, @color/, @android:color/,
                        // ?attr) -- parseColor only handled hex, so
                        // android:textColor="@android:color/darker_gray"
                        // (F-Droid's text_last_update) fell through to the
                        // default black instead of grey.
                        Integer c = macapkResolveColorValue(value);
                        if (c != null) tv.setTextColor(c);
                    }
                    return;
                case "textSize":
                    if (view instanceof android.widget.TextView tv) {
                        Float sz = parseDimensionPx(value);
                        // parseDimensionPx already gave px; setTextSize(float) is
                        // the SP overload and would double-convert. Apply as PX.
                        // AOSP sets the XML size inside the constructor through the
                        // private setRawTextSize, never through the overridable
                        // public setTextSize; see TextView.macapkSetRawTextSize.
                        if (sz != null) {
                            String v = value.trim();
                            int unit = v.endsWith("sp") ? android.util.TypedValue.COMPLEX_UNIT_SP
                                : (v.endsWith("dp") || v.endsWith("dip")) ? android.util.TypedValue.COMPLEX_UNIT_DIP
                                : android.util.TypedValue.COMPLEX_UNIT_PX;
                            tv.macapkSetRawTextSize(sz, unit);
                        }
                    }
                    return;
                case "textStyle":
                    // android:textStyle="bold|italic". F-Droid's summary is
                    // bold; without this it rendered regular weight.
                    if (view instanceof android.widget.TextView tvst) {
                        tvst.setMacapkTextStyle(parseTextStyle(value));
                    }
                    return;
                case "fontFamily":
                    // android:fontFamily="sans-serif-medium" (or an @string/
                    // indirection to one -- Material 3 names its typefaces that
                    // way, e.g. m3_ref_typeface_plain_medium). Was dropped on
                    // the floor entirely, so every Material label and app-bar
                    // title rendered Regular where Android renders Medium.
                    // TextView holds the NAME and resolves it together with
                    // textStyle, because the family is looked up at NORMAL and
                    // the style applied on top (AOSP setTypefaceFromAttrs).
                    if (view instanceof android.widget.TextView tvff) {
                        // A FONT RESOURCE takes precedence over a family name,
                        // matching AOSP's setTypefaceFromAttrs precedence.
                        android.graphics.Typeface fres = macapkResolveFontResource(value);
                        if (fres != null) {
                            tvff.setMacapkFontResource(fres);
                        } else {
                            // A value our resolver cannot read (gcalc's
                            // `?themeFontFamily*`) must not ERASE the face the
                            // appearance or the constructor already gave the view:
                            // AOSP never reads a family attribute as "reset to
                            // DEFAULT". Same rule, and the same gate, as
                            // applyTextAppearance's family branch.
                            String fname = macapkResolveFontFamilyValue(value);
                            if (fname != null || !MACAPK_INFLATE_TA_ATTR) tvff.setMacapkFontFamily(fname);
                        }
                    }
                    return;
                case "textFontWeight":
                    // android:textFontWeight="500" (API 28). Takes the
                    // createAbsolute path -- the requested weight verbatim, no
                    // +300 and no synthetic bold -- so it is NOT interchangeable
                    // with textStyle. See TextView.macapkApplyTypefaceFromAttrs.
                    if (view instanceof android.widget.TextView tvfw) {
                        try { tvfw.setMacapkFontWeight(Integer.parseInt(value.trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                    return;
                case "textAppearance":
                    // Consumed in applyAttributes' pre-pass (applied as the
                    // base before direct attrs). No-op here so it doesn't
                    // fall to the default/unknown branch.
                    return;
                case "elevation":
                case "translationZ": {
                    // Tier 3 M12: android:elevation / android:translationZ -> real
                    // Z so the view casts an outline-shaped shadow (drawn by
                    // ViewGroup.dispatchDraw under MACAPK_REAL_LOOPER). parse-
                    // DimensionPx resolves dp / @dimen to px. Inert flag-off
                    // (nothing reads getElevation/getZ there).
                    Float zp = parseDimensionPx(value);
                    if (zp != null) {
                        if ("elevation".equals(attr)) view.setElevation(zp);
                        else view.setTranslationZ(zp);
                    }
                    return;
                }
                case "visibility":
                    int vis = "gone".equals(value) ? View.GONE
                          :  "invisible".equals(value) ? View.INVISIBLE
                          :  View.VISIBLE;
                    view.setVisibility(vis);
                    return;
                case "onClick":
                    wireOnClickHandler(view, value);
                    return;
                case "animateLayoutChanges":
                    // AOSP ViewGroup(Context, AttributeSet, int, int)
                    // (ViewGroup.java:774-778, android14-release):
                    //     case R.styleable.ViewGroup_animateLayoutChanges:
                    //         boolean animateLayoutChanges = a.getBoolean(attr, false);
                    //         if (animateLayoutChanges) {
                    //             setLayoutTransition(new LayoutTransition());
                    //         }
                    // The attribute's whole job is to INSTALL a default
                    // LayoutTransition, which is then readable through
                    // getLayoutTransition(). We ignored it, so a group that
                    // declared it read back null and NPE'd on the standard
                    // `getLayoutTransition().setDuration(...)` idiom (Signal's
                    // ConversationListItem.onFinishInflate -- 2026-08-27 fleet
                    // census). There is no setAnimateLayoutChanges() for the
                    // reflective boolean path below to find; AOSP has no such
                    // setter, so this needs its own case.
                    //
                    // We still do not ANIMATE anything -- installing the object is
                    // the observable half that app code reads back, and it is the
                    // half that was missing.
                    if (view instanceof ViewGroup vgALC && "true".equals(value)) {
                        vgALC.setLayoutTransition(new android.animation.LayoutTransition());
                    }
                    return;
                case "fillViewport":
                    // AOSP ScrollView / HorizontalScrollView constructors:
                    // `setFillViewport(a.getBoolean(R.styleable.*_fillViewport,
                    // false))` (HorizontalScrollView.java:211, android14). Ours
                    // never read it, so the onMeasure fillViewport branch was
                    // unreachable from XML: OpenCalculator's result row measured
                    // UNSPECIFIED and drew its preview at the LEFT edge instead
                    // of right-aligned. v1.0.1643. (androidx NestedScrollView
                    // reads it from its own TypedArray; setting it again here is
                    // the same value.)
                    if (LAYOUT_AOSP_1643) setBooleanReflect(view, attr, "true".equals(value));
                    return;
                case "descendantFocusability":
                    // AOSP ViewGroup's constructor: setDescendantFocusability(
                    // DESCENDANT_FOCUSABILITY_FLAGS[a.getInt(attr, 0)]), the enum
                    // beforeDescendants=0 / afterDescendants=1 / blocksDescendants=2
                    // (attrs.xml). Never read, so every XML group stayed
                    // BEFORE_DESCENDANTS: leanback's BrowseFrameLayout (focusable,
                    // afterDescendants in lb_browse_fragment.xml) then kept focus
                    // ITSELF instead of handing it to its rows, and a list row
                    // that blocks descendants let its buttons steal focus.
                    if (view instanceof ViewGroup vgDF && value != null) {
                        int df;
                        switch (value.trim()) {
                            case "0": case "beforeDescendants": df = ViewGroup.FOCUS_BEFORE_DESCENDANTS; break;
                            case "1": case "afterDescendants": df = ViewGroup.FOCUS_AFTER_DESCENDANTS; break;
                            case "2": case "blocksDescendants": df = ViewGroup.FOCUS_BLOCK_DESCENDANTS; break;
                            default: df = -1; break;
                        }
                        if (df != -1) vgDF.setDescendantFocusability(df);
                    }
                    return;
                case "focusable":
                case "focusableInTouchMode":
                case "clickable":
                case "longClickable":
                    // boolean View setters -- pull via reflection
                    setBooleanReflect(view, attr, "true".equals(value));
                    return;
                case "orientation":
                    // android:orientation="vertical|horizontal" -- store on
                    // LinearLayout (and subclasses) for our LinearLayout.onMeasure
                    // + onLayout to consult.
                    if (view instanceof android.widget.LinearLayout ll) {
                        int o = "vertical".equals(value)
                            ? android.widget.LinearLayout.VERTICAL
                            : android.widget.LinearLayout.HORIZONTAL;
                        ll.setOrientation(o);
                    }
                    return;
                case "gravity":
                    // android:gravity -- container's content gravity.
                    if (view instanceof android.widget.LinearLayout ll) {
                        ll.setGravity(parseGravityInt(value));
                    } else if (view instanceof android.widget.TextView tv) {
                        // TextView.setGravity now stores + honors gravity in
                        // onDraw (v1.0.282), so android:gravity="center" etc.
                        // positions the text correctly.
                        tv.setGravity(parseGravityInt(value));
                    }
                    return;
                // lane layout, 2026-08-02: maxLines / singleLine / ellipsize
                // were DROPPED by the inflater although TextView implements all
                // three (setMaxLines, setEllipsize, and both the measure and the
                // draw paths honour them). Measured: F-Droid's card app-name
                // declares `android:maxLines="1" android:ellipsize="end"` and
                // WRAPPED to two lines for us while the emulator ellipsized
                // ("Multi Launcher · Home Scr…").
                // See docs/canvas-ab-analyst-report_2026-08-02.md item 5.
                case "textAllCaps": {
                    if (view instanceof android.widget.TextView tv)
                        tv.setAllCaps("true".equals(value) || "1".equals(value));
                    return;
                }
                case "maxLines": {
                    if (view instanceof android.widget.TextView tv) {
                        try { tv.setMaxLines(Integer.parseInt(value)); }
                        catch (NumberFormatException ignored) {}
                    }
                    return;
                }
                // v1.0.895: line spacing was NEVER APPLIED AT INFLATION.
                // TextView.setLineSpacing / setLineHeightPx have existed and been
                // correct since v1.0.4xx and NOTHING CALLED THEM -- there was no
                // case here for any of the three attributes, and
                // applyTextAppearance pulled only textSize/textColor/textStyle.
                // So every TextView laid out at the raw font height (mult=1,
                // add=0) whatever its layout or style declared.
                // MEASURED on wikipedia, whose `<style name="P">` sets
                // `lineHeight = 24sp` (x density 2.0 = 48px, the emulator's exact
                // measured pitch) while ours was 38px -- the bare Roboto font
                // height. It accumulates: -22.5px by y445, -41 by y778, -109.5 by
                // y1341, -157 by y1576. Glyph widths match to within 4px, so the
                // font size and wrapping were already right; only the pitch was
                // wrong. 34 corpus apps declare one of these three (wikipedia 162
                // sites, duolingo 128, calculator 61, anki 31, opensudoku 30,
                // deskclock 30, antennapod 16, fdroid 15, opencalculator 15) --
                // the recurring ~30 is Material's own M3 typography, i.e. this is
                // essentially every AndroidX app.
                //
                // ⚠ MEASURED INERT ON wikipedia, AND THE REASON IS NOT YET KNOWN.
                // wikipedia's body pitch stays 38px against the emulator's 48
                // (link-blue `#3366cc` stays at y540-600 instead of moving to
                // y600-660), so the attribute is not reaching that TextView.
                // An env-gated trace on these cases logged ZERO hits over 50s and
                // 110s runs -- BUT THAT IS INCONCLUSIVE, NOT PROOF: I could not
                // confirm from the log that the run ever reached the onboarding
                // screen (nothing logs view class names, so grepping for
                // `AppTextView` proves nothing either). An earlier version of
                // this comment claimed the inflater "skips app subclasses"; that
                // was OVERCLAIMED and is retracted -- `applyAttributes(view,
                // elem)` is called unconditionally at the single call site.
                // TO SETTLE IT: trace with a screen marker in the same run, or
                // trace a plain `<TextView>` app (deskclock/antennapod both
                // declare lineHeight in styles) where the screen is reached at
                // launch. Do NOT re-derive the missing-case theory -- the cases
                // below are correct and present; the open question is delivery.
                case "lineSpacingExtra": {
                    if (view instanceof android.widget.TextView tv) {
                        Float px = parseDimensionPx(value);
                        if (px != null) tv.setLineSpacing(px, tv.getLineSpacingMultiplier());
                    }
                    return;
                }
                case "lineSpacingMultiplier": {
                    if (view instanceof android.widget.TextView tv) {
                        try { tv.setLineSpacing(tv.getLineSpacingExtra(),
                                                Float.parseFloat(value.trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                    return;
                }
                case "lineHeight": {
                    // AOSP reads lineSpacingExtra at TextView.java:1493 and
                    // lineSpacingMultiplier at :1497 inside its attribute loop,
                    // but stashes lineHeight (:1632-1638) into a local and
                    // applies it only AFTER the loop closes, at :2014-2018 --
                    // so lineHeight deliberately WINS over the multiplier
                    // (setLineHeightPx resets mult to 1f). Our two attribute
                    // loops run in document/HashMap order, so this case is fed
                    // from the deferred replay in applyAttributes to reproduce
                    // that ordering; see the deferredLineHeight map there.
                    if (view instanceof android.widget.TextView tv) {
                        // setLineHeight(int) is the PX-taking public entry
                        // (setLineHeightPx is private); parseDimensionPx has
                        // already converted sp/dp through the display metrics.
                        Float px = parseDimensionPx(value);
                        if (px != null && px > 0f) tv.setLineHeight(Math.round(px));
                    }
                    return;
                }
                case "lines": {
                    if (view instanceof android.widget.TextView tv) {
                        try { tv.setLines(Integer.parseInt(value)); }
                        catch (NumberFormatException ignored) {}
                    }
                    return;
                }
                case "minLines": {
                    if (view instanceof android.widget.TextView tv) {
                        try { tv.setMinLines(Integer.parseInt(value)); }
                        catch (NumberFormatException ignored) {}
                    }
                    return;
                }
                case "singleLine": {
                    if (view instanceof android.widget.TextView tv
                            && ("true".equals(value) || "1".equals(value))) {
                        tv.setSingleLine(true);
                    }
                    return;
                }
                case "ellipsize": {
                    if (view instanceof android.widget.TextView tv) {
                        android.text.TextUtils.TruncateAt e = null;
                        switch (value) {
                            case "start":    e = android.text.TextUtils.TruncateAt.START;    break;
                            case "middle":   e = android.text.TextUtils.TruncateAt.MIDDLE;   break;
                            case "end":      e = android.text.TextUtils.TruncateAt.END;      break;
                            case "marquee":  e = android.text.TextUtils.TruncateAt.MARQUEE;  break;
                            default: break;   // "none" / unknown -> leave unset
                        }
                        // An XML (element OR style) marquee is the constructor's
                        // ELLIPSIZE_MARQUEE case, which also sets the fade mode
                        // that turns it into END_SMALL on a phone (AOSP
                        // TextView.java:1797-1806); a programmatic
                        // setEllipsize(MARQUEE) does not. See
                        // TextView.macapkEffectiveEllipsize.
                        if (e == android.text.TextUtils.TruncateAt.MARQUEE && MACAPK_TEXT_LINES_AOSP)
                            tv.macapkSetXmlMarquee();
                        if (e != null) tv.setEllipsize(e);
                    }
                    return;
                }
                // lane layout, 2026-08-02: the VIEW TRANSFORM attributes were
                // dropped too, although View stores them and draw() applies
                // `c.scale(scaleX, scaleY)` / rotation. AOSP reads them in the
                // View constructor (R.styleable.View_scaleX ...). Measured:
                // AntennaPod flips one shared arrow asset with
                // `android:scaleX="-1.0" android:scaleY="-1.0"`, so our arrow
                // pointed the wrong way. Same family, same one-line shape.
                case "scaleX": case "scaleY":
                case "rotation": case "rotationX": case "rotationY":
                case "translationX": case "translationY":
                case "transformPivotX": case "transformPivotY":
                case "alpha": {
                    float f;
                    try { f = Float.parseFloat(value.endsWith("dp") || value.endsWith("px")
                            ? value.substring(0, value.length() - 2) : value); }
                    catch (NumberFormatException e) { return; }
                    switch (attr) {
                        case "scaleX":          view.setScaleX(f); break;
                        case "scaleY":          view.setScaleY(f); break;
                        case "rotation":        view.setRotation(f); break;
                        case "rotationX":       view.setRotationX(f); break;
                        case "rotationY":       view.setRotationY(f); break;
                        case "translationX":    view.setTranslationX(f); break;
                        case "translationY":    view.setTranslationY(f); break;
                        case "transformPivotX": view.setPivotX(f); break;
                        case "transformPivotY": view.setPivotY(f); break;
                        case "alpha":           view.setAlpha(f); break;
                        default: break;
                    }
                    return;
                }
                case "textAlignment": {
                    // android:textAlignment -- an aapt2 ENUM attr; apktool
                    // hands us the token string. AOSP token -> constant table:
                    // View.java android13-release :2933-2983 (inherit=0,
                    // gravity=1, textStart=2, textEnd=3, center=4,
                    // viewStart=5, viewEnd=6). Previously DROPPED entirely
                    // (no case + no reflective fallback), which combined with
                    // View.setTextAlignment being a no-op meant
                    // textAlignment="center" drew flush-left (measured:
                    // AntennaPod welcome screen, campaign capture 2026-08-01).
                    int ta;
                    switch (value) {
                        case "gravity":   ta = android.view.View.TEXT_ALIGNMENT_GRAVITY;    break;
                        case "textStart": ta = android.view.View.TEXT_ALIGNMENT_TEXT_START; break;
                        case "textEnd":   ta = android.view.View.TEXT_ALIGNMENT_TEXT_END;   break;
                        case "center":    ta = android.view.View.TEXT_ALIGNMENT_CENTER;     break;
                        case "viewStart": ta = android.view.View.TEXT_ALIGNMENT_VIEW_START; break;
                        case "viewEnd":   ta = android.view.View.TEXT_ALIGNMENT_VIEW_END;   break;
                        case "inherit":   ta = android.view.View.TEXT_ALIGNMENT_INHERIT;    break;
                        default:
                            // aapt2 may have resolved it to the raw int already.
                            try { ta = Integer.parseInt(value); }
                            catch (NumberFormatException e) { return; }
                    }
                    view.setTextAlignment(ta);
                    return;
                }
                // v1.0.132: android:padding* parsing. Without this,
                // ConstraintLayout's wrap_content computation produces
                // height = max(child.bottom) WITHOUT adding the
                // declared padding -- F-Droid's `app_card_list_item.xml`
                // has `android:padding="8.0dp"` on the inner CL, but
                // our LayoutInflater ignored it and the card collapsed
                // to icon-height (48dp = 96px) instead of icon-height
                // + 16dp padding (= 112px). Generic for every APK
                // that uses XML padding on a wrap_content container.
                case "padding": {
                    Integer px = parseDimensionPxInt(value);
                    if (px != null) view.setPadding(px, px, px, px);
                    return;
                }
                case "paddingLeft": {
                    Integer px = parseDimensionPxInt(value);
                    if (px != null) view.setPadding(px,
                        view.getPaddingTop(), view.getPaddingRight(),
                        view.getPaddingBottom());
                    return;
                }
                case "paddingTop": {
                    Integer px = parseDimensionPxInt(value);
                    if (px != null) view.setPadding(view.getPaddingLeft(),
                        px, view.getPaddingRight(),
                        view.getPaddingBottom());
                    return;
                }
                case "paddingRight": {
                    Integer px = parseDimensionPxInt(value);
                    if (px != null) view.setPadding(view.getPaddingLeft(),
                        view.getPaddingTop(), px,
                        view.getPaddingBottom());
                    return;
                }
                case "paddingBottom": {
                    Integer px = parseDimensionPxInt(value);
                    if (px != null) view.setPadding(view.getPaddingLeft(),
                        view.getPaddingTop(), view.getPaddingRight(),
                        px);
                    return;
                }
                case "paddingStart": {
                    // LTR-only mapping (RTL not yet supported here).
                    Integer px = parseDimensionPxInt(value);
                    if (px != null) view.setPadding(px,
                        view.getPaddingTop(), view.getPaddingRight(),
                        view.getPaddingBottom());
                    return;
                }
                case "paddingEnd": {
                    Integer px = parseDimensionPxInt(value);
                    if (px != null) view.setPadding(view.getPaddingLeft(),
                        view.getPaddingTop(), px,
                        view.getPaddingBottom());
                    return;
                }
                default:
                    return;  // ignore everything else
            }
        } catch (Throwable t) {
            // Swallow per-attribute errors so a single bad attr
            // doesn't bring down the whole inflation.
        }
    }

    /** Parse "@id/foo" / "@+id/foo" / "@android:id/..." / "@layout/foo"
     *  / "@string/foo" etc. Returns the resolved resource id or 0 if
     *  it can't be resolved. The "type" component is extracted from
     *  the reference so we look up in the right Resources namespace
     *  (was always "id" before -- include layout lookups failed). */
    /** Parse android:gravity / layout_gravity strings like
     *  "start|center_vertical" into the OR'd Gravity int. */
    static int parseGravityInt(String s) {
        if (s == null || s.isEmpty()) return android.view.Gravity.NO_GRAVITY;
        int g = 0;
        for (String tok : s.split("\\|")) {
            tok = tok.trim();
            switch (tok) {
                case "top": g |= android.view.Gravity.TOP; break;
                case "bottom": g |= android.view.Gravity.BOTTOM; break;
                case "left": g |= android.view.Gravity.LEFT; break;
                case "right": g |= android.view.Gravity.RIGHT; break;
                case "start": g |= android.view.Gravity.START; break;
                case "end": g |= android.view.Gravity.END; break;
                case "center": g |= android.view.Gravity.CENTER; break;
                case "center_vertical": g |= android.view.Gravity.CENTER_VERTICAL; break;
                case "center_horizontal": g |= android.view.Gravity.CENTER_HORIZONTAL; break;
                case "fill": g |= android.view.Gravity.FILL; break;
                case "fill_vertical": g |= android.view.Gravity.FILL_VERTICAL; break;
                case "fill_horizontal": g |= android.view.Gravity.FILL_HORIZONTAL; break;
                case "clip_vertical": g |= android.view.Gravity.CLIP_VERTICAL; break;
                case "clip_horizontal": g |= android.view.Gravity.CLIP_HORIZONTAL; break;
                default: break;
            }
        }
        return g;
    }


    /** The FROZEN framework resource ids for `@android:id/<name>`.
     *
     *  WHY THIS EXISTS (v1.0.867). parseResourceId resolved EVERY reference
     *  against the APP's resource table and, on a miss, fell back to
     *  `body.hashCode()` so that two references to the same name at least agreed
     *  with each other. For an app-defined `@+id/foo` that is fine. For
     *  `@android:id/button1` it is fatal: the app's table has no such name, so
     *  the inflated View got `setId("button1".hashCode())`, while the library's
     *  COMPILED code calls `findViewById(android.R.id.button1)` = 0x01020019.
     *  A hash can never equal a frozen constant, so the lookup returned null.
     *
     *  MEASURED victim (unitconverter 5.7.3): androidx's
     *  `AlertController.setupButtons` does
     *  `buttonPanel.findViewById(android.R.id.button1).setOnClickListener(...)`
     *  and NPE'd on the null, THROWING OUT OF `AlertDialog.onCreate` -- so the
     *  Help dialog was shown, measured and painted with its window background
     *  but was completely EMPTY. Every AppCompat/Material AlertDialog in every
     *  app hits this same path, which is why it is worth a real table.
     *
     *  Values are the frozen public ids, generated verbatim from AOSP
     *  core/res/res/values/public-final.xml (fetched 2026-08-03) -- that file's
     *  own header states these ids are permanently frozen, which is exactly what
     *  makes hardcoding them correct rather than a guess. Same provenance as the
     *  frozen framework STYLE ids in ResourceIndex (v1.0.853).
     *
     *  Returns 0 when the name is not a public framework id; the caller then
     *  keeps its previous behaviour. */
    static int macapkFrameworkId(String name) {
        if (name == null) return 0;
        switch (name) {
            case "background": return 0x01020000;
            case "checkbox": return 0x01020001;
            case "content": return 0x01020002;
            case "edit": return 0x01020003;
            case "empty": return 0x01020004;
            case "hint": return 0x01020005;
            case "icon": return 0x01020006;
            case "icon1": return 0x01020007;
            case "icon2": return 0x01020008;
            case "input": return 0x01020009;
            case "list": return 0x0102000a;
            case "message": return 0x0102000b;
            case "primary": return 0x0102000c;
            case "progress": return 0x0102000d;
            case "selectedIcon": return 0x0102000e;
            case "secondaryProgress": return 0x0102000f;
            case "summary": return 0x01020010;
            case "tabcontent": return 0x01020011;
            case "tabhost": return 0x01020012;
            case "tabs": return 0x01020013;
            case "text1": return 0x01020014;
            case "text2": return 0x01020015;
            case "title": return 0x01020016;
            case "toggle": return 0x01020017;
            case "widget_frame": return 0x01020018;
            case "button1": return 0x01020019;
            case "button2": return 0x0102001a;
            case "button3": return 0x0102001b;
            case "extractArea": return 0x0102001c;
            case "candidatesArea": return 0x0102001d;
            case "inputArea": return 0x0102001e;
            case "selectAll": return 0x0102001f;
            case "cut": return 0x01020020;
            case "copy": return 0x01020021;
            case "paste": return 0x01020022;
            case "copyUrl": return 0x01020023;
            case "switchInputMethod": return 0x01020024;
            case "inputExtractEditText": return 0x01020025;
            case "keyboardView": return 0x01020026;
            case "closeButton": return 0x01020027;
            case "startSelectingText": return 0x01020028;
            case "stopSelectingText": return 0x01020029;
            case "addToDictionary": return 0x0102002a;
            case "custom": return 0x0102002b;
            case "home": return 0x0102002c;
            case "selectTextMode": return 0x0102002d;
            case "mask": return 0x0102002e;
            case "statusBarBackground": return 0x0102002f;
            case "navigationBarBackground": return 0x01020030;
            case "pasteAsPlainText": return 0x01020031;
            case "undo": return 0x01020032;
            case "redo": return 0x01020033;
            case "replaceText": return 0x01020034;
            case "shareText": return 0x01020035;
            case "accessibilityActionShowOnScreen": return 0x01020036;
            case "accessibilityActionScrollToPosition": return 0x01020037;
            case "accessibilityActionScrollUp": return 0x01020038;
            case "accessibilityActionScrollLeft": return 0x01020039;
            case "accessibilityActionScrollDown": return 0x0102003a;
            case "accessibilityActionScrollRight": return 0x0102003b;
            case "accessibilityActionContextClick": return 0x0102003c;
            case "accessibilityActionSetProgress": return 0x0102003d;
            case "icon_frame": return 0x0102003e;
            case "list_container": return 0x0102003f;
            case "switch_widget": return 0x01020040;
            case "textAssist": return 0x01020041;
            case "accessibilityActionMoveWindow": return 0x01020042;
            case "autofill": return 0x01020043;
            case "accessibilityActionShowTooltip": return 0x01020044;
            case "accessibilityActionHideTooltip": return 0x01020045;
            case "accessibilityActionPageUp": return 0x01020046;
            case "accessibilityActionPageDown": return 0x01020047;
            case "accessibilityActionPageLeft": return 0x01020048;
            case "accessibilityActionPageRight": return 0x01020049;
            case "accessibilityActionPressAndHold": return 0x0102004a;
            case "accessibilitySystemActionBack": return 0x0102004b;
            case "accessibilitySystemActionHome": return 0x0102004c;
            case "accessibilitySystemActionRecents": return 0x0102004d;
            case "accessibilitySystemActionNotifications": return 0x0102004e;
            case "accessibilitySystemActionQuickSettings": return 0x0102004f;
            case "accessibilitySystemActionPowerDialog": return 0x01020050;
            case "accessibilitySystemActionToggleSplitScreen": return 0x01020051;
            case "accessibilitySystemActionLockScreen": return 0x01020052;
            case "accessibilitySystemActionTakeScreenshot": return 0x01020053;
            case "accessibilityActionImeEnter": return 0x01020054;
            case "accessibilityActionDragStart": return 0x01020055;
            case "accessibilityActionDragDrop": return 0x01020056;
            case "accessibilityActionDragCancel": return 0x01020057;
            case "accessibilityActionShowTextSuggestions": return 0x01020058;
            case "inputExtractAction": return 0x01020059;
            case "inputExtractAccessories": return 0x0102005a;
            case "bold": return 0x0102005b;
            case "italic": return 0x0102005c;
            case "underline": return 0x0102005d;
            case "accessibilityActionScrollInDirection": return 0x0102005e;
            default: return 0;
        }
    }

    private int parseResourceId(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (s.startsWith("@")) {
            String body = s.substring(1);
            String type = "id";
            boolean frameworkNs = false;
            if (body.startsWith("+id/")) {
                body = body.substring(4);
            } else if (body.startsWith("android:")) {
                // android: namespace prefix
                frameworkNs = true;
                body = body.substring(8);
                // Type prefix follows the namespace
                int slash = body.indexOf('/');
                if (slash > 0) {
                    type = body.substring(0, slash);
                    body = body.substring(slash + 1);
                }
            } else {
                int slash = body.indexOf('/');
                if (slash > 0) {
                    type = body.substring(0, slash);
                    body = body.substring(slash + 1);
                }
            }
            // Try numeric (rare but legal): "@id/12345"
            try { return Integer.parseInt(body); } catch (NumberFormatException ignored) {}
            // FRAMEWORK ids first, and they must never reach the hash fallback
            // below -- see macapkFrameworkId. `@android:id/button1` has to come
            // out as the frozen 0x01020019 that a library's compiled
            // findViewById(android.R.id.button1) will look for; a hash of the
            // name cannot ever match it.
            if (frameworkNs && "id".equals(type)) {
                int fw = macapkFrameworkId(body);
                if (fw != 0) return fw;
            }
            // Look up the name via Resources, using the extracted type
            try {
                if (context != null && context.getResources() != null) {
                    int id = context.getResources().getIdentifier(body, type,
                              context.getPackageName());
                    if (id != 0) return id;
                }
            } catch (Throwable ignored) {}
            // Fall back: hash the name so each unique name maps to a
            // stable id within this session. findViewById uses int
            // equality so two refs to the same alias will hash to the
            // same id. Not stable across sessions but works for
            // single-session use.
            return body.hashCode();
        }
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        return 0;
    }

    /** android:fontFamily's value as a system family NAME, or null when it does
     *  not name one.
     *
     *  Three shapes reach here:
     *    - a literal, "sans-serif-medium" -- the direct case;
     *    - "@string/m3_ref_typeface_plain_medium" -- how Material 3 names its
     *      typefaces, and the dominant shape in the corpus (~600 refs against
     *      ~450 literals), so NOT resolving it would leave most of the fix
     *      inert;
     *    - "@font/something" -- a FONT RESOURCE, i.e. a font file or an XML
     *      font family shipped by the app. That is a different mechanism from
     *      the system family table and is deliberately NOT mapped: passing the
     *      raw string through would be a name no fonts.xml family answers to,
     *      and inventing a mapping would put us ahead of what the app asked for.
     *      Returning null here yields Typeface.DEFAULT, which is what the app
     *      already got before this change.
     *
     *  An unresolvable @string/ falls back to resolveString's bare-name
     *  behaviour, which then simply misses the table -- again DEFAULT.
     *
     *  A FOURTH shape, "@android:string/font_family_button_material", is how
     *  every PLATFORM TextAppearance.Material* names its family (styles_material
     *  .xml), and resolveString answers only the app's "@string/". Under
     *  MACAPK_INFLATE_TA_ATTR (default ON) it is read through the platform
     *  table, as AOSP's readTextAppearance does with `attributes.mFontFamily =
     *  appearance.getString(attr)` (TextView.java android14-release :4279-4290;
     *  getString follows the reference into any package). The values are
     *  donottranslate_material.xml (android14-release :24-30): button and title
     *  "sans-serif-medium", body/subhead "sans-serif". MEASURED victim: once
     *  `?`-textAppearance resolved (B2), nearly every one landed on a platform
     *  Material appearance, this returned null for all seven such families, and
     *  VLC's own classes lost the typeface AppCompatTextHelper had set on 59
     *  views, four of them sans-serif-medium 500 (ActionMenuItemView, whose AOSP
     *  answer via ?actionMenuTextAppearance -> TextAppearance.Material.Button is
     *  exactly that 500). */
    private String macapkResolveFontFamilyValue(String v) {
        if (v == null || v.isEmpty()) return null;
        if (v.startsWith("@font/") || v.startsWith("@android:font/")) return null;
        String s = (MACAPK_INFLATE_TA_ATTR && v.startsWith("@android:string/"))
            ? macapkPlatformString(v.substring("@android:string/".length()))
            : resolveString(v);
        if (s == null || s.isEmpty() || s.charAt(0) == '@' || s.charAt(0) == '?') return null;
        return s;
    }

    /** The value of the PLATFORM string `name` (`@android:string/<name>`), or
     *  null when the platform table does not hold it. Resources.getString answers
     *  "" rather than throwing for an id of 0, so the id is checked first. */
    private String macapkPlatformString(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            if (context == null || context.getResources() == null) return null;
            android.content.res.Resources r = context.getResources();
            int id = r.getIdentifier(name, "string", "android");
            if (id == 0) return null;
            String s = r.getString(id);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** `android:fontFamily="@font/brand"` -> the app's own Typeface, or null
     *  when the value does not name a font resource.
     *
     *  This is the OTHER half of android:fontFamily. Resources.getFont already
     *  knew how to load both a raw font file and a `<font-family>` descriptor;
     *  what was missing was anyone CALLING it from the inflater, so an app that
     *  shipped a branded font in res/font/ rendered in Roboto with no error. */
    private android.graphics.Typeface macapkResolveFontResource(String v) {
        if (v == null || !v.startsWith("@") || v.startsWith("@android:")) return null;
        if (!v.startsWith("@font/")) return null;
        try {
            if (context == null || context.getResources() == null) return null;
            int id = parseResourceId(v);
            if (id == 0) return null;
            return context.getResources().getFont(id);
        } catch (Throwable ignored) {
            // NotFoundException is the AOSP signal for "not a font resource";
            // the caller then treats the value as a family name.
            return null;
        }
    }

    private String resolveString(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.startsWith("@string/")) {
            try {
                int id = parseResourceId(s);
                if (id != 0 && context != null && context.getResources() != null) {
                    String resolved = context.getResources().getString(id);
                    if (resolved != null) return resolved;
                }
            } catch (Throwable ignored) {}
            return s.substring("@string/".length());  // best-effort literal
        }
        return s;
    }

    private Integer parseColor(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.startsWith("#")) {
                String hex = s.substring(1);
                long v = Long.parseLong(hex, 16);
                if (hex.length() == 6) v |= 0xFF000000L;
                else if (hex.length() == 3) {
                    int r = (int)((v >> 8) & 0xF), g = (int)((v >> 4) & 0xF), b = (int)(v & 0xF);
                    v = 0xFF000000L | (r << 20) | (r << 16) | (g << 12) | (g << 8) | (b << 4) | b;
                }
                return (int) v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Parse "16dp", "12sp", "24.0px", "8" into px-space float. Mirrors
     *  AOSP TypedValue.applyDimension: dp/dip and sp multiply by the
     *  display density. v1.0.114: previously this dropped the unit
     *  entirely and returned the raw numeric, so `layout_width="96.0dp"`
     *  became 96px instead of 192px (xhdpi density=2.0) -- icons and
     *  paddings throughout the F-Droid layout rendered at one-half the
     *  expected size, with cascading effects on ConstraintLayout's
     *  chain resolution. AOSP source:
     *    https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/util/TypedValue.java
     *    (search for applyDimension) */
    private Float parseDimensionPx(String s) {
        if (s == null || s.isEmpty()) return null;
        // Resolve @dimen/X references through the dimension table FIRST.
        // Without this, Float.parseFloat("@dimen/...") threw and the
        // value silently became null -> 0: every layout_margin /
        // layout_width / layout_height / padding that uses a @dimen
        // reference (nearly all real layouts) lost its value. The most
        // visible victim: the calculator keypad buttons specify their
        // gaps as @dimen/horizontal_button_margin etc., so they
        // rendered edge-to-edge (touching circles -> a blob) instead of
        // separated. Generic across every APK.
        // Resolve reference chains to a literal dimension:
        //   ?themeAttr -> (theme cascade) -> @dimen/... -> (maybe) @dimen/... -> "152dp"
        // (a) ?attr / ?attr/name -- a THEME dimension attribute (e.g.
        //     `?collapsingToolbarLayoutLargeSize` for F-Droid's AppDetails
        //     CollapsingToolbarLayout height, or `?actionBarSize`). Without this
        //     the value parsed to null -> the layout dimension fell back to
        //     wrap_content, collapsing the app-detail feature-graphic header to
        //     the toolbar height. (b) a @dimen that itself points at another
        //     @dimen (Material's m3_appbar_size_large -> m3_comp_top_app_bar_
        //     large_container_height); the old code returned null on the first
        //     indirection. Loop with a guard so a cyclic/unknown ref bails to
        //     null rather than spinning.
        for (int guard = 0; guard < 8 && !s.isEmpty()
                && (s.charAt(0) == '@' || s.charAt(0) == '?'); guard++) {
            if (s.charAt(0) == '?') {
                String attr = s.substring(1);
                if (attr.startsWith("attr/")) attr = attr.substring(5);
                // A PRIVATE platform attr, as a compiled framework layout spells
                // it (simple_spinner_dropdown_item's layout_height =
                // ?android:attr/dropdownListPreferredItemHeight, id 0x0112....,
                // table type ^attr-private). A theme item is keyed by the attr's
                // NAME, which is unique across attr and ^attr-private in one
                // package, so the type prefix is dropped for the theme walk.
                if (MACAPK_PLATFORM_LAYOUTS && attr.startsWith("android:^attr-private/")) {
                    attr = "android:" + attr.substring("android:^attr-private/".length());
                }
                String raw = macapkThemeAttrRaw(attr);
                if (raw == null || raw.isEmpty()) return null;
                s = raw;
                continue;
            }
            // s.charAt(0) == '@'
            String body = s.substring(1);
            if (body.startsWith("dimen/")) {
                String raw = macapkDimenRaw(body.substring(6));
                if (raw == null || raw.isEmpty()) return null;
                s = raw;
            } else if (MACAPK_INFLATE_FW_DIMEN && body.startsWith("android:dimen/")) {
                // A PLATFORM dimension, the spelling of every 0x0105.... reference
                // in a compiled framework layout (select_dialog_singlechoice_material's
                // paddingStart = @android:dimen/select_dialog_padding_start_material)
                // and legal in an app layout too. macapkDimenRaw reads only the
                // APP's table, so this used to return null and the attribute was
                // dropped. Read through Resources.getValue, as AOSP's TypedArray
                // does for any package, and continue with the literal it holds.
                String raw = macapkPlatformDimenLiteral(body.substring("android:dimen/".length()));
                if (raw == null || raw.isEmpty()) return null;
                s = raw;
            } else {
                // @android:dimen/* and other refs: unknown here.
                return null;
            }
        }
        try {
            String numStr = s;
            String unit = "";
            int unitStart = -1;
            for (int i = 0; i < s.length(); ++i) {
                char c = s.charAt(i);
                if (Character.isLetter(c)) { unitStart = i; break; }
            }
            if (unitStart > 0) {
                numStr = s.substring(0, unitStart);
                unit = s.substring(unitStart).toLowerCase(java.util.Locale.ROOT);
            }
            float v = Float.parseFloat(numStr);
            // Density-aware conversion. We use the global Resources
            // density (DisplayMetrics.density = 2.0f for xhdpi by
            // default; the launcher's surface-size selector can change
            // it via setDensityForLauncherSurface).
            float density = android.util.DisplayMetrics.macapkDeviceDensity();
            try {
                android.content.res.Resources r =
                    (context != null) ? context.getResources() : null;
                if (r != null) {
                    android.util.DisplayMetrics dm = r.getDisplayMetrics();
                    if (dm != null && dm.density > 0f) density = dm.density;
                }
            } catch (Throwable ignored) {}
            switch (unit) {
                case "dp": case "dip":
                    return v * density;
                case "sp":
                    return v * density;       // approx: scaledDensity ~ density
                case "px": case "":
                    return v;
                case "pt":
                    return v * density * (1f / 72f) * 160f;
                case "in":
                    return v * density * 160f;
                case "mm":
                    return v * density * 160f / 25.4f;
                default:
                    return v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void applyBackground(View view, String value) {
        if (value == null) return;
        // Cluster A3 (widget-fidelity, 2026-09-26): `@null` / `@empty` install
        // NOTHING (AOSP: TYPE_NULL, so View's constructor never reaches
        // setBackground), but the background WAS declared, so the stand-in
        // Toolbar / AppBarLayout chrome must keep off it. Covers views whose
        // constructor never ran View's styled read of android:background.
        // The `?attr` spelling is handled after the theme chase below.
        if (macapkIsNullBackground(value)) {
            view.macapkBackgroundExplicitlySet = true;
            return;
        }
        // docs/attr-theme-threading_2026-07-31.md explicitly scoped this call
        // OUT ("LayoutInflater.macapkResolveColorValue... still calls the
        // static Resources.macapkThemeColor and was not converted... tied to
        // NewPipe context... not the bug this task described") -- closing it
        // here. CONFIRMED VICTIM (2026-07-31, NewPipe bring-up):
        // res/layout/toolbar_layout.xml's Toolbar has
        // `android:background="?attr/colorPrimary"` (a DIRECT attribute
        // value, not a `@drawable/X` file reference -- so THEME2's
        // getDrawable(id, theme) fix below never runs for it). Under
        // NewPipe's `OpeningTheme`, `colorPrimary` is deliberately
        // `@android:color/transparent`; the app calls
        // `setTheme(R.style.DarkTheme)` in onCreate specifically so its real
        // toolbar colour takes over, but the static
        // Resources.macapkThemeColor path is hardcoded to
        // ResourceIndex.getApplicationThemeName(), which never updates past
        // the manifest theme -- so the toolbar (and anything else using this
        // same `?attr/X`-as-a-flat-colour idiom) stayed on the transparent
        // splash value regardless of setTheme(). Same fix shape as THEME2's:
        // thread `view.getContext().getTheme()` through so a Theme with a
        // real appliedStyles cascade (this Activity's, once
        // ContextThemeWrapper.setTheme()/getTheme() actually track it -- see
        // that class's own fix) wins over the static fallback, and a null/
        // empty-cascade Theme degrades to EXACTLY the prior static answer
        // (Resources.Theme.resolveAttrColor's own fallback IS
        // resolveApplicationThemeColor -- see its doc comment).
        android.content.res.Resources.Theme bgTheme = null;
        try {
            android.content.Context vc = view.getContext();
            if (vc != null) bgTheme = vc.getTheme();
        } catch (Throwable ignored) {}
        // A `@color/X` whose X is a res/color FILE is a stateful drawable, not a
        // flat colour; see macapkColorFileDrawable. Must precede the colour fast
        // path below, which flattens the selector to its default row.
        android.graphics.drawable.Drawable cslBg = macapkColorFileDrawable(view, value, bgTheme);
        if (cslBg != null) {
            view.setBackground(cslBg);
            return;
        }
        Integer c = macapkResolveColorValue(value, bgTheme);
        if (c != null) {
            view.setBackgroundColor(c);
            return;
        }
        // A `?attr/X` value whose DECLARED type is a drawable reference, not
        // a colour, falls through the flat-colour path above with c==null
        // (macapkResolveColorValue's `?` branch only ever asks a COLOUR
        // resolver, which has nothing to hand back for a `@drawable/Y`
        // value) and previously stopped here -- the `@drawable/` check right
        // below only matches when `value` ITSELF is already a literal
        // `@drawable/...` string, never when it is a `?attr/X` that
        // RESOLVES to one. CONFIRMED VICTIM (2026-07-31, NewPipe bring-up):
        // res/layout/drawer_layout.xml's NavigationView has
        // `android:background="?android:attr/windowBackground"`, and
        // NewPipe's OpeningTheme (the splash theme) declares
        // `windowBackground` as `@drawable/splash_background` (a red-field-
        // plus-logo layer-list) where DarkTheme declares the SAME attr as
        // `@color/dark_background_color` (a plain colour, which the branch
        // above already handles once setTheme()/getTheme() correctly
        // surfaces DarkTheme's cascade -- see ContextThemeWrapper's own
        // fix). So even with every other fix in this session landed, the
        // drawer kept the splash theme's drawable background specifically,
        // because nothing ever asked "what does ?windowBackground resolve
        // to when the answer isn't a colour". Resolve the RAW value via the
        // Theme.resolveAttrRaw sibling (see Resources.java) and, if IT names
        // a `@drawable/Y`, treat this call exactly as if `value` had been
        // `@drawable/Y` to begin with -- reuses the block below verbatim,
        // no new drawable-resolution logic.
        // ATTRSTYLE lane, 2026-08-13: this was ONE hop and APP drawables only.
        // Both limits bit at once on the Material EditText underline, whose chain
        // is a two-hop delegation ending in a FRAMEWORK asset:
        //     Widget.AppCompat.EditText  android:background = ?editTextBackground
        //       Base.V22.Theme.AppCompat editTextBackground = ?android:editTextBackground
        //         themes_material.xml:93                    = @drawable/edit_text_material
        // One hop stopped at `?android:editTextBackground`, and even with the
        // second hop the `@drawable/`-only test would have thrown the answer away.
        // resolveAttrRaw deliberately does NOT chase (its other callers -- the
        // window-flag probes -- want the raw declared token), so the chase belongs
        // at the consumer.
        //
        // STRICTLY A SUPERSET of the old behaviour: a one-hop chain that resolved
        // to `@drawable/X` before takes hop 0 and matches the same test; anything
        // that resolved to a colour or a literal still leaves `value` untouched
        // and falls through to the paths below exactly as before. Bounded and
        // self-reference-guarded, same shape as the sibling chases in
        // applyWidgetDefaultStyle and macapkInstallButtonDrawable.
        if (value.startsWith("?") && bgTheme != null) {
            String cur = value;
            for (int hop = 0; hop < 4 && cur.startsWith("?"); hop++) {
                String attr = cur.substring(1);
                if (attr.startsWith("android:attr/")) attr = "android:" + attr.substring(13);
                else if (attr.startsWith("attr/")) attr = attr.substring(5);
                String raw = bgTheme.resolveAttrRaw(attr);
                if (raw == null) break;
                raw = raw.trim();
                if (raw.isEmpty() || raw.equals(cur)) break;
                cur = raw;
            }
            // `@android:drawable/` goes to the synthesizer a few lines below;
            // `@drawable/` to the app's own table. Both are handled there.
            if (cur.startsWith("@drawable/") || cur.startsWith("@android:drawable/")) {
                value = cur;
            }
            // Cluster A3: a `?attr` whose theme leaf is @null / @empty is the
            // same TYPE_NULL declaration as the literal (see the top of this
            // method).
            if (macapkIsNullBackground(cur)) {
                view.macapkBackgroundExplicitlySet = true;
                return;
            }
        }
        // `@drawable/X` backgrounds. v1.0.841: route through the REAL
        // Resources.getDrawable(id) inflater instead of a hand-rolled
        // re-parse that only recognized a bare `<shape>` root and
        // silently dropped everything else (comment used to say
        // "Complex drawables (layer/ripple/selector) still skip -- the
        // drawable render path is a separate piece").
        //
        // MEASURED root cause of OpenCalculator's 22 invisible keypad
        // buttons (2026-07-31, docs/maps-calculator_2026-07-31.md): its
        // `@style/CalculatorButton` sets `android:background="@drawable/
        // circle"`, and circle.xml's root tag is `<ripple>` (a `<shape>`
        // nested one `<item>` down) -- so `!"shape".equals(root.getTagName())`
        // was true and this method returned WITHOUT calling setBackgroundColor
        // OR setBackground at all. Bounds and label text still laid out and
        // drew fine (independent of background), which is exactly "real
        // bounds and real label text drawn over nothing". This was NOT a
        // `?attr`-resolution bug -- a hermetic probe against OpenCalculator's
        // real resources proved `?button_color` resolves correctly
        // (Resources.macapkThemeColor -> ResourceIndex.resolveApplicationThemeColor
        // -> 0xffefefef) -- the drawable was simply never reaching the View.
        //
        // Resources.getDrawable(id) already handles shape/ripple/layer-list/
        // selector/inset/adaptive-icon/vector (Resources.java's
        // macapkInflateXmlDrawable chain) and resolves `?attr`/`@color`/`#hex`
        // through the same theme cascade this class's own macapkResolveColorValue
        // does for the plain-color fast path above. View.draw() (v1.0.497,
        // View.java ~2644) already knows how to PAINT the result -- it's scoped
        // to `android.graphics.drawable.*` (our own framework classes: exactly
        // what getDrawable ever returns), so an app-bundled custom Drawable
        // class can never reach the canvas via this path. setBackground marks
        // macapkBackgroundExplicitlySet so chrome fallbacks (e.g. Toolbar's
        // colorSurface fill) still know a real background -- even a drawable
        // one -- was declared, matching what setBackgroundColor already does.
        // v1.0.870: `@android:drawable/...` was NOT accepted here -- the guard
        // matched only the app-namespaced `@drawable/`, so a FRAMEWORK drawable
        // reference fell straight out of this method and no background was set
        // at all.
        //
        // That was the last broken link in the v1.0.869 widget-default-style
        // chain. The tier itself works end to end -- traced on bobball:
        //     cn=AppCompatButton appTheme=Theme.NoTitleBar
        //          buttonStyle=@android:style/Widget.Button
        //     Widget.Button.background = @android:drawable/btn_default
        // -- so the style map really did receive the right value, and it died
        // one line later on a prefix test. anuto only worked because its
        // buttons pick up geometry/gravity, which are not routed through here.
        //
        // Framework drawables cannot resolve to an id (the app's table has no
        // such name and parseResourceId would fall back to a name HASH), so
        // they go to the synthesizer instead -- see
        // Resources.macapkFrameworkDrawable.
        if (value.startsWith("@android:drawable/")) {
            try {
                android.content.res.Resources r = view.getResources();
                if (r == null) return;
                android.graphics.drawable.Drawable fw =
                    r.macapkFrameworkDrawableByName(value.substring(18));
                if (fw != null) { view.setBackground(fw); return; }
            } catch (Throwable ignored) {}
            return;
        }
        // ANY app-namespaced reference, not just `@drawable/`.
        //
        // AOSP does not gate on the resource TYPE here. `android:background` is
        // read with TypedArray.getDrawable -> Resources.loadDrawable(value, id,
        // density, theme), which dispatches on the referenced FILE (its root tag
        // for XML, its bytes for a bitmap) and never asks which `res/<type>/`
        // directory the file happens to live in. A drawable XML filed under a
        // different type therefore works on a device.
        //
        // MEASURED VICTIM (2026-08-28): Vector Pinball's menu panel is
        //     <LinearLayout android:id="@id/buttonPanel"
        //                   android:background="@layout/rounded_border" ...>
        // and res/layout/rounded_border.xml is an ordinary
        // `<selector><item><shape>` -- solid #b448, 20dp corners, a 2dp #3bbdfa
        // stroke. That is exactly the translucent blue panel with the cyan border
        // the emulator draws and we did not: our guard matched only `@drawable/`,
        // so the reference fell straight out of this method and the whole menu's
        // text floated on black. Found by the fleet emudiff INK IMBALANCE column,
        // which ranked bouncy top with 10 emulator-only bands.
        //
        // SCOPED to the types a drawable XML actually lives in. AOSP's own
        // behaviour is broader -- loadDrawable does not look at the type at all --
        // but `@layout/` is the case that was measured and needed, and `@mipmap/`
        // is a bitmap type by definition (the sibling ImageView `src` gate already
        // accepts it). Widening to EVERY type is not obviously wrong; it is simply
        // not yet justified by a measurement, so it is left alone.
        //
        // ⚠ A NOTE ON WHY THIS IS NARROW, because the first attempt was narrowed
        // for a REASON THAT TURNED OUT TO BE FALSE and the false version must not
        // be re-derived. That attempt accepted every app-namespaced reference, the
        // next fleet A/B showed F-Droid 84.42% -> 79.39%, and imgdiff pointed at a
        // 200x200 region that had gone from page background to solid black:
        //     R1 EXTRA_IN_B px[104,360-303,559] area=36480 A=#f9f9ff B=#000000
        // I concluded our getDrawable was answering non-drawable ids with a black
        // drawable, and narrowed the guard to stop it. **That conclusion was
        // wrong.** Narrowing did NOT restore the score (79.39 -> 79.42, then three
        // more runs all at exactly 79.42), and cropping the band showed the real
        // story: the emulator's capture lists "Redcini A" and "Chompsss - Calorie
        // Tracker" while ours lists "Zodl: Zcash Wallet" and "Vault Explorer".
        // F-Droid had refreshed its repo index over the network, so the two sides
        // were showing DIFFERENT APPS -- and the "black square" was Zodl's icon,
        // which is genuinely black, rendered correctly. Nothing was broken.
        // (docs/ + the auto-memory already record fdroid as a repo-index drifter;
        // the drift is worth ~5 points here.)
        //
        // The narrow scope is kept because it is what the measurement supports,
        // NOT because the broad one was shown harmful. It was not.
        if (value.startsWith("@drawable/") || value.startsWith("@layout/")
                || value.startsWith("@mipmap/")) {
            try {
                int id = parseResourceId(value);
                if (id == 0) return;
                android.content.res.Resources r = view.getResources();
                if (r == null) return;
                // docs/attr-theme-threading_2026-07-31.md: thread the VIEW's own
                // Theme (view.getContext().getTheme()), not the static
                // application theme, into the drawable inflater -- so a
                // `<solid android:color="?attr/X"/>` inside the referenced
                // drawable resolves against whatever Theme this View was
                // actually constructed with. instantiateView() above already
                // constructs the View with `vctx` (a per-element
                // ContextThemeWrapper when the element's subtree carries
                // android:theme under MACAPK_VIEW_THEME), so view.getContext()
                // is that wrapper when one applies and the ambient Context
                // otherwise -- getDrawable(id, null) (today's behavior) when
                // there is no Context or Theme to offer.
                //
                // Confirmed victim: Mastodon's splash CTAs sit under a root
                // View with android:theme="@style/Theme.Mastodon.Dark
                // .SplashFragment" and read `?colorM3Primary`/`?colorM3Outline`
                // from bg_button_m3_filled.xml / bg_button_m3_outlined_white
                // .xml -- attrs the APPLICATION theme never defines, so the
                // filled pill painted nothing and the outline painted a
                // full-bleed white bar (GradientDrawable.hasFill stays false
                // on an unresolved ?attr color -- see GradientDrawable.java).
                android.content.res.Resources.Theme theme = null;
                try {
                    android.content.Context vc = view.getContext();
                    if (vc != null) theme = vc.getTheme();
                } catch (Throwable ignored) {}
                android.graphics.drawable.Drawable d = r.getDrawable(id, theme);
                if (d != null) view.setBackground(d);
            } catch (Throwable ignored) {}
        }
    }

    /** Wave 5 (2026-09-26), docs/w5-inflater-fixes_2026-09-26.md F6. Default ON;
     *  `MACAPK_COLOR_FILE_DRAWABLE=0` restores the flattening, where a
     *  `@color/` selector background became setBackgroundColor(default row).
     *  The same name gates the matching Resources.getDrawable and
     *  ColorStateListDrawable.isStateful changes (drawables lane), so one env
     *  var A/Bs the whole chain. */
    static final boolean MACAPK_COLOR_FILE_DRAWABLE =
        !"0".equals(System.getenv("MACAPK_COLOR_FILE_DRAWABLE"));

    /** `android:background="@color/X"` where X is a res/color FILE (a
     *  ColorStateList `<selector>`), as the stateful ColorStateListDrawable a
     *  device builds; null for every other value, which then takes the paths
     *  it always took.
     *
     *  AOSP decides by the resolved value's TYPE and the file's type name
     *  (android14-release ResourcesImpl.java):
     *      :883-889  if (file.endsWith(".xml")) {
     *                    final String typeName = getResourceTypeName(id);
     *                    if (typeName != null && typeName.equals("color")) {
     *                        dr = loadColorOrXmlDrawable(wrapper, value, id, density, file);
     *      :922-926  ColorStateList csl = loadColorStateList(wrapper, value, id, null);
     *                return new ColorStateListDrawable(csl);
     *  while a values `<color>` is TYPE_INT_COLOR_* and stays a ColorDrawable,
     *  which the colour fast path in applyBackground already gives it.
     *
     *  Ours sent both through macapkResolveColorValue -> getColorByName ->
     *  colorFromSelectorFile, which returns the selector's DEFAULT row, and
     *  called setBackgroundColor with it. MEASURED victim: SGT Puzzles'
     *  res/layout-v29/list_item.xml puts
     *      <LinearLayout android:id="@id/currentGameHighlight"
     *                    android:background="@color/chooser_item_background">
     *  on each chooser tile, and chooser_item_background.xml is
     *      <selector><item android:color="@color/chooser_current_background"
     *                      android:state_activated="true"/>
     *                <item android:color="@android:color/transparent"/></selector>
     *  (chooser_current_background = #99114411). GameChooser.onResume calls
     *  setActivated(true) on the current game's tile; the emulator tints that
     *  tile and we painted it transparent, because the default row was all
     *  that survived.
     *
     *  The FILE test is AOSP's own: getValue(id, tv, true) reports TYPE_STRING
     *  with the compiled path ("res/color/chooser_item_background.xml") for a
     *  file row and TYPE_INT_COLOR_ARGB8 for a values colour (both measured
     *  offline on SGT). getIdentifier, not parseResourceId: the latter hashes
     *  an unknown name into a synthetic id.
     *
     *  NOT covered, deliberately (no measured victim): a `?attr` that resolves
     *  to a colour file (still flattened by the fast path), and AOSP's
     *  loadColorOrXmlDrawable second arm, a DRAWABLE xml filed under res/color. */
    private static android.graphics.drawable.Drawable macapkColorFileDrawable(
            View view, String value, android.content.res.Resources.Theme theme) {
        if (!MACAPK_COLOR_FILE_DRAWABLE || value == null || !value.startsWith("@color/")) return null;
        try {
            android.content.res.Resources r = view.getResources();
            if (r == null) return null;
            int id = r.getIdentifier(value.substring(7).trim(), "color", null);
            if (id == 0) return null;
            android.util.TypedValue tv = new android.util.TypedValue();
            r.getValue(id, tv, true);
            if (tv.type != android.util.TypedValue.TYPE_STRING || tv.string == null
                    || !tv.string.toString().endsWith(".xml")) {
                return null;
            }
            android.content.res.ColorStateList csl = r.getColorStateList(id, theme);
            if (csl == null) return null;
            return new android.graphics.drawable.ColorStateListDrawable(csl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Cluster A3: aapt2's two TYPE_NULL spellings (ResourceUtils.cpp
     *  TryParseNullOrEmpty :345-364, android14-release). Gated with A2
     *  (MACAPK_TYPEDARRAY_NULL_HASVALUE=0 -> false): with A2 off the View
     *  constructor still installs the VectorDrawable stub, which sets the flag
     *  itself, so the gate-off path stays byte-for-byte the pre-A2 one. */
    private static final boolean MACAPK_A3_NULL_BG_DECLARED =
        !"0".equals(System.getenv("MACAPK_TYPEDARRAY_NULL_HASVALUE"));
    /** Plague Inc. news bar: an element's own background=@null keeps the
     *  style tier's background off (see the style-items loop). =0 restores the
     *  pre-change order, where the style background stayed. */
    private static final boolean MACAPK_INFLATE_ELEMENT_NULL_BG =
        !"0".equals(System.getenv("MACAPK_INFLATE_ELEMENT_NULL_BG"));
    private static boolean macapkIsNullBackground(String v) {
        if (v == null || !MACAPK_A3_NULL_BG_DECLARED) return false;
        String t = v.trim();
        return t.equals("@null") || t.equals("@empty");
    }

    /** Resolve a color VALUE string: hex literal, `@color/X`,
     *  `@android:color/white|black|transparent`, or a `?attr` theme
     *  reference (through the application-theme cascade). Returns
     *  null when unresolvable. Static-only (no Theme) -- the other
     *  callers of this overload (text colour / tint from a named style)
     *  have no confirmed victim needing the Theme threaded yet, matching
     *  docs/attr-theme-threading_2026-07-31.md's own scoping discipline
     *  ("no confirmed victim needs it threaded today" -- same standard
     *  applied here, not a blanket policy against threading them). */
    private Integer macapkResolveColorValue(String v) {
        return macapkResolveColorValue(v, null);
    }
    /** Theme-aware overload -- see applyBackground's call site and its doc
     *  comment for the confirmed victim (NewPipe's toolbar_layout.xml). A
     *  null `theme` behaves identically to the static-only overload above;
     *  Resources.Theme.resolveAttrColor's own fallback (see Resources.java,
     *  docs/attr-theme-threading_2026-07-31.md) is
     *  ResourceIndex.resolveApplicationThemeColor -- the SAME method the
     *  static path below calls -- so a Theme with an empty appliedStyles
     *  cascade (MACAPK_VIEW_THEME off and no explicit setTheme() call on
     *  this Context) resolves to EXACTLY the same answer as before. */
    private Integer macapkResolveColorValue(String v, android.content.res.Resources.Theme theme) {
        if (v == null || v.isEmpty()) return null;
        Integer c = parseColor(v);
        if (c != null) return c;
        if (v.startsWith("@color/")) {
            try {
                Object ri = android.content.res.Resources.macapkGetResourceIndex();
                if (ri != null) {
                    Object col = ri.getClass()
                        .getMethod("getColorByName", String.class)
                        .invoke(ri, v.substring(7));
                    if (col instanceof Integer i) return i;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        if (v.startsWith("@android:color/")) {
            // v1.0.871: the 11-entry switch that USED to live here is GONE.
            // v1.0.870 made it fall THROUGH to the real table when it missed,
            // which fixed the immediate bug but left two copies of the framework
            // palette in two files with nothing keeping them in sync -- and this
            // copy was the ONLY place that knew the holo_* colours, so every
            // other consumer got null for them. Both halves now live in
            // ResourceIndex.resolveColorRef, which is the single source of truth
            // this class already delegates every other colour form to.
            //
            // This is the third instance of the shadow-table shape in three days
            // (v868 the theme table vs its resolver, v870 this switch, v871 the
            // holo rows it was hiding). Deleting the shadow is the fix; making it
            // fall through was only a mitigation.
            Integer fw = android.content.res.Resources.macapkResolveColorExpr(v);
            if (fw != null) return fw;
            return null;
        }
        if (v.startsWith("?")) {
            String attr = v.substring(1);
            if (attr.startsWith("android:attr/")) attr = "android:" + attr.substring(13);
            else if (attr.startsWith("attr/")) attr = attr.substring(5);
            // Theme-first: theme.resolveAttrColor walks THIS Theme's own
            // appliedStyles cascade before falling back to the identical
            // static resolveApplicationThemeColor call the no-theme path
            // uses -- so passing a theme can only ADD a resolution, never
            // remove one already working.
            int rc = (theme != null)
                ? theme.resolveAttrColor(attr, 1 /* sentinel */)
                : android.content.res.Resources.macapkThemeColor(attr, 1 /* sentinel */);
            return rc == 1 ? null : rc;
        }
        return null;
    }

    private void applyImageSrc(android.widget.ImageView iv, String value) {
        if (value == null) return;
        // v1.0.871: FRAMEWORK drawables, the exact bug v1.0.870 fixed in
        // applyBackground -- this sibling still rejected the `@android:` namespace,
        // so `android:src="@android:drawable/..."` fell out of the method and no
        // image was set. 18 of the 62 corpus APKs carry one (F-Droid, AnkiDroid,
        // NewPipe, AntennaPod, Signal, VLC, Telegram, Duolingo); the dominant name
        // is `ic_menu_close_clear_cancel`. Most are `visibility="gone"` until an
        // event fires, so expect little on a static capture -- it is fixed because
        // the asymmetry between two sibling methods is itself the bug.
        if (value.startsWith("@android:drawable/")) {
            try {
                android.content.res.Resources r = (iv != null) ? iv.getResources() : null;
                if (r != null) {
                    android.graphics.drawable.Drawable fw =
                        r.macapkFrameworkDrawableByName(value.substring(18));
                    if (fw != null) iv.setImageDrawable(fw);
                }
            } catch (Throwable ignored) {}
            return;
        }
        if (value.startsWith("@drawable/") || value.startsWith("@mipmap/")) {
            int id = parseResourceId(value);
            if (id != 0) iv.setImageResource(id);
        }
    }

    /** android:onClick="doStart" wires the named handler from the
     *  Activity. AOSP looks up by reflection on the Activity context;
     *  we mirror that. */
    private void wireOnClickHandler(View view, final String handlerName) {
        if (handlerName == null || handlerName.isEmpty() || context == null) return;
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Method m = context.getClass().getMethod(handlerName, View.class);
                    m.invoke(context, v);
                } catch (Throwable t) {
                    // AOSP's DeclaredOnClickListener rethrows as
                    // IllegalStateException("Could not execute method for
                    // android:onClick", e) (View.java:6468-6475) -- the app
                    // crashes. We keep the app alive, but the wrapper alone
                    // ("InvocationTargetException") says nothing: log the
                    // app's REAL exception and where it was thrown. v1.0.1643:
                    // this hid OpenCalculator's getSelectionStart()==-1 crash
                    // in every operator/backspace/point button.
                    Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                            && t.getCause() != null) ? t.getCause() : t;
                    NativeLog.w(TAG, "onClick handler '" + handlerName + "' threw "
                        + cause + macapkTopFrames(cause, 4));
                }
            }
        });
    }

    private static String macapkTopFrames(Throwable t, int n) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < n; i++) sb.append("\n    at ").append(st[i]);
        return sb.toString();
    }

    private void setBooleanReflect(View view, String attrName, boolean value) {
        String setterName = "set"
            + Character.toUpperCase(attrName.charAt(0))
            + attrName.substring(1);
        try {
            Method m = view.getClass().getMethod(setterName, boolean.class);
            m.invoke(view, value);
        } catch (Throwable ignored) {}
    }

    public interface Factory  { View onCreateView(String name, Context context, android.util.AttributeSet attrs); }
    public interface Factory2 extends Factory { View onCreateView(View parent, String name, Context context, android.util.AttributeSet attrs); }

    // ------------------------------------------------------------------
    // v1.0.131: ConstraintLayout LayoutParams reflective parsing.
    //
    // Deep-research finding (androidx ConstraintLayout source, branch
    // androidx-main, file ConstraintLayout.java lines 1354-1426): on
    // API >= 17, `applyConstraintsFromLayoutParams` reads HORIZONTAL
    // anchors EXCLUSIVELY from package-private shadow fields
    //   mResolvedLeftToLeft, mResolvedLeftToRight,
    //   mResolvedRightToLeft, mResolvedRightToRight,
    //   mResolvedHorizontalBias
    // The public `startToStart`/`startToEnd`/`endToStart`/`endToEnd`
    // and the legacy `leftToLeft`/`leftToRight`/`rightToLeft`/
    // `rightToRight` fields are only inputs to `resolveLayoutDirection
    // (int)` -- the framework method that POPULATES the mResolved*
    // shadow fields. Our framework doesn't drive the AOSP RTL
    // resolution pass, so `resolveLayoutDirection` never runs, and the
    // mResolved* fields stay at UNSET=-1 regardless of what we wrote
    // to the public start/end/left/right fields.
    //
    // Vertical anchors (`topToTop`/`topToBottom`/`bottomToTop`/
    // `bottomToBottom`/`baselineToBaseline`) and `verticalBias` are
    // read DIRECTLY from the public fields -- no shadow layer.
    //
    // Second gating: ConstraintLayout's `setChildrenConstraints`
    // rebuild only runs when `mDirtyHierarchy` is true AND at least
    // one child has `isLayoutRequested()`. Both flags are set by
    // calling `child.requestLayout()` on each child after we write the
    // LP fields.
    //
    // Fix below: for each XML constraint attr, set the public field
    // AND the matching mResolved* shadow when applicable, then call
    // child.requestLayout() after the attr walk completes.
    private static final java.util.Map<String, String> CL_INT_FIELD_FOR_ATTR;
    private static final java.util.Map<String, String> CL_FLOAT_FIELD_FOR_ATTR;
    private static final java.util.Map<String, String> CL_DIM_FIELD_FOR_ATTR;
    static {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        // Reference targets (int = sibling-id or PARENT_ID=0).
        // For horizontal anchors we set MULTIPLE fields per attr:
        //   - the public field (leftToLeft, startToStart, etc.)
        //   - the package-private shadow (mResolvedLeftToLeft, etc.)
        //     that ConstraintLayout's solver actually reads on API >= 17.
        // The shadows are listed with a `,` separator so
        // applyConstraintLayoutAttribute writes both.
        m.put("layout_constraintLeft_toLeftOf",     "leftToLeft,mResolvedLeftToLeft");
        m.put("layout_constraintLeft_toRightOf",    "leftToRight,mResolvedLeftToRight");
        m.put("layout_constraintRight_toLeftOf",    "rightToLeft,mResolvedRightToLeft");
        m.put("layout_constraintRight_toRightOf",   "rightToRight,mResolvedRightToRight");
        m.put("layout_constraintTop_toTopOf",       "topToTop");
        m.put("layout_constraintTop_toBottomOf",    "topToBottom");
        m.put("layout_constraintBottom_toTopOf",    "bottomToTop");
        m.put("layout_constraintBottom_toBottomOf", "bottomToBottom");
        m.put("layout_constraintBaseline_toBaselineOf", "baselineToBaseline");
        // Start/end fields ALSO populate the same mResolved* shadows
        // (LTR mapping: Start->Left, End->Right). Because we don't run
        // the AOSP RTL pass, we treat start==left and end==right
        // unconditionally. This is correct for every locale we
        // currently ship; RTL apps would need a real layout-direction
        // resolution pass added separately.
        m.put("layout_constraintStart_toStartOf",   "startToStart,mResolvedLeftToLeft");
        m.put("layout_constraintStart_toEndOf",     "startToEnd,mResolvedLeftToRight");
        m.put("layout_constraintEnd_toStartOf",     "endToStart,mResolvedRightToLeft");
        m.put("layout_constraintEnd_toEndOf",       "endToEnd,mResolvedRightToRight");
        CL_INT_FIELD_FOR_ATTR = java.util.Collections.unmodifiableMap(m);

        java.util.HashMap<String, String> f = new java.util.HashMap<>();
        f.put("layout_constraintHorizontal_bias",   "horizontalBias");
        f.put("layout_constraintVertical_bias",     "verticalBias");
        f.put("layout_constraintDimensionRatio",    "dimensionRatio");
        CL_FLOAT_FIELD_FOR_ATTR = java.util.Collections.unmodifiableMap(f);

        java.util.HashMap<String, String> d = new java.util.HashMap<>();
        d.put("layout_constraintWidth_default",     "matchConstraintDefaultWidth");
        d.put("layout_constraintHeight_default",    "matchConstraintDefaultHeight");
        d.put("layout_constraintWidth_min",         "matchConstraintMinWidth");
        d.put("layout_constraintHeight_min",        "matchConstraintMinHeight");
        d.put("layout_constraintWidth_max",         "matchConstraintMaxWidth");
        d.put("layout_constraintHeight_max",        "matchConstraintMaxHeight");
        d.put("layout_constraintGuide_begin",       "guideBegin");
        d.put("layout_constraintGuide_end",         "guideEnd");
        d.put("layout_constraintGuide_percent",     "guidePercent");
        CL_DIM_FIELD_FOR_ATTR = java.util.Collections.unmodifiableMap(d);
    }

    /** True when the given LayoutParams instance is one of the
     *  androidx ConstraintLayout / ConstraintHelper variants. Walk
     *  the class hierarchy looking for the canonical class name --
     *  cheap (~3 lookups) and handles subclasses (Guideline etc.). */
    private static boolean isConstraintLayoutLayoutParams(ViewGroup.LayoutParams lp) {
        if (lp == null) return false;
        Class<?> c = lp.getClass();
        while (c != null) {
            String n = c.getName();
            if (n.endsWith("ConstraintLayout$LayoutParams")
                || n.endsWith("ConstraintHelper$LayoutParams")) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** Reflectively set a ConstraintLayout.LayoutParams field per the
     *  attribute name. Three flavours of field:
     *   - int (reference target): value is "@id/foo" or "parent" or
     *     a numeric id -> look up via parseResourceId / PARENT_ID=0.
     *   - float (bias / ratio): value is "0.5" or a string like "16:9".
     *     For ratios we set the string field if available.
     *   - dimension/percent: value is "16dp" / "0.7" / etc.
     *
     *  The reflection sets the field DIRECTLY rather than calling a
     *  setter -- ConstraintLayout's LayoutParams fields are
     *  documented-public and accessed by name in AOSP itself. */
    private void applyConstraintLayoutAttribute(ViewGroup.LayoutParams lp,
                                                  String attr, String value) {
        if (value == null) return;
        // Chain style (enum) + constrained-width/height (bool). Without
        // these a `packed` chain defaulted to `spread`, so F-Droid's
        // text_last_update/text_size packed chain (the "Updated N days ago"
        // + size row, start-aligned to the title with bias 0) resolved to a
        // negative x and drew off the left edge.
        if ("layout_constraintHorizontal_chainStyle".equals(attr)) {
            setIntField(lp, "horizontalChainStyle", parseChainStyle(value)); return;
        }
        if ("layout_constraintVertical_chainStyle".equals(attr)) {
            setIntField(lp, "verticalChainStyle", parseChainStyle(value)); return;
        }
        // NOTE: layout_constrainedWidth/Height are deliberately NOT applied.
        // Our solver+measurer integration collapses a constrainedWidth
        // wrap_content chain element to 0 (it measures 0 at solve time),
        // whereas leaving it unset lets the element keep its wrap size and
        // the packed chain positions it correctly. Honoring the attr here
        // made F-Droid's "Updated N days ago" line collapse to 0x0; skipping
        // it + applying chainStyle=packed puts it at the title's start.
        if ("layout_constrainedWidth".equals(attr)
                || "layout_constrainedHeight".equals(attr)) {
            return;
        }
        String fieldSpec = CL_INT_FIELD_FOR_ATTR.get(attr);
        if (fieldSpec != null) {
            int targetId;
            if ("parent".equals(value)) {
                targetId = 0;  // PARENT_ID
            } else {
                targetId = parseResourceId(value);
                if (targetId == 0) {
                    return;
                }
            }
            // fieldSpec may be a single name or comma-separated list
            // ("startToStart,mResolvedLeftToLeft") -- we set every
            // listed field so both the public + package-private shadow
            // get the same target ID.
            boolean traceOn = "1".equals(System.getenv("MACAPK_TRACE_CL_PARAMS"));
            for (String fieldName : fieldSpec.split(",")) {
                boolean ok = setIntField(lp, fieldName, targetId);
                if (traceOn) {
                    com.macapk.loader.NativeLog.i(TAG,
                        "CL." + fieldName + " <- " + targetId
                        + " (attr=" + attr + ", val=" + value
                        + ", set=" + ok + ")");
                }
            }
            return;
        }
        String floatField = CL_FLOAT_FIELD_FOR_ATTR.get(attr);
        if (floatField != null) {
            try {
                if ("dimensionRatio".equals(floatField)) {
                    // dimensionRatio is a STRING field on the LP.
                    setStringField(lp, floatField, value);
                } else {
                    float f = Float.parseFloat(value.trim());
                    setFloatField(lp, floatField, f);
                }
            } catch (Throwable ignored) {}
            return;
        }
        // BISECT GATE MACAPK_CL_GUIDE_PERCENT_FLOAT (2026-09-26, default ON;
        // docs/fleet-newpipe-vlc-residual_2026-09-26.md). layout_constraintGuide_percent
        // is a FLOAT attribute: androidx ConstraintLayout.LayoutParams(Context,
        // AttributeSet) reads it with TypedArray.getFloat into the float field
        // guidePercent. It sits in the dimension map below, whose writer parses
        // "0.66" as 0.66px, truncates it to the int 0 and stores it with
        // Field.setInt -- a legal int-to-float widening, so the percent the
        // constructor had already read correctly is overwritten with 0.0 (1.0
        // survives, every fraction collapses). The Guideline then solves at 0% and
        // everything constrained to it collapses: VLC's tablet onboarding drew its
        // icon at x=0 and 0-wide titles. AOSP's inflater never re-walks layout
        // attributes after generateLayoutParams, so the constructor's value is the
        // answer: a literal is re-stored as the same float, and a reference
        // (@dimen / @fraction / ?attr), which the constructor already chased, is
        // left alone. =0 restores the dimension write exactly.
        if (MACAPK_CL_GUIDE_PERCENT_FLOAT && "layout_constraintGuide_percent".equals(attr)) {
            try {
                setFloatField(lp, "guidePercent", Float.parseFloat(value.trim()));
            } catch (NumberFormatException refLeftToTheCtor) {
                // Not a literal: keep what LayoutParams(Context, AttributeSet) read.
            }
            return;
        }
        String dimField = CL_DIM_FIELD_FOR_ATTR.get(attr);
        if (dimField != null) {
            // Most of these are int fields holding a pixel dimension.
            Integer px = parseDimensionPxInt(value);
            if (px != null) {
                setIntField(lp, dimField, px);
            }
            return;
        }
        // Unknown layout_constraint* attr -- log once per attr so the
        // next session can see what's still on the table.
        // (No-op; cheap to skip silently.)
    }

    /** Reflectively set a field. Walks the class hierarchy via
     *  getDeclaredField (not just getField) so package-private and
     *  superclass-declared fields are reachable. Calls
     *  setAccessible(true) for non-public fields. ConstraintLayout's
     *  `mResolvedLeftToLeft` etc. are package-private; without this
     *  walk + setAccessible, our reflection silently fails. */
    private static java.lang.reflect.Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                try { f.setAccessible(true); } catch (Throwable ignored) {}
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
    private static boolean setIntField(Object target, String name, int v) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            if (f == null) return false;
            f.setInt(target, v);
            return true;
        } catch (Throwable t) { return false; }
    }
    private static void setBooleanField(Object target, String name, boolean v) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            if (f != null) f.setBoolean(target, v);
        } catch (Throwable ignored) {}
    }
    /** ConstraintLayout chain style enum -> int (CHAIN_SPREAD=0,
     *  CHAIN_SPREAD_INSIDE=1, CHAIN_PACKED=2). Accepts the name or a
     *  numeric value. */
    private static int parseChainStyle(String v) {
        if (v == null) return 0;
        switch (v.trim()) {
            case "spread":        return 0;
            case "spread_inside": return 1;
            case "packed":        return 2;
            default:
                try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; }
        }
    }
    private static void setFloatField(Object target, String name, float v) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            if (f != null) f.setFloat(target, v);
        } catch (Throwable ignored) {}
    }
    private static void setStringField(Object target, String name, String v) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            if (f != null) f.set(target, v);
        } catch (Throwable ignored) {}
    }

    public android.view.View createView(android.content.Context viewContext, String name, String prefix, android.util.AttributeSet attrs) throws ClassNotFoundException {
        return createView(name, prefix, attrs);
    }
    public android.view.View createView(String name, String prefix, android.util.AttributeSet attrs) throws ClassNotFoundException {
        return null;
    }
    public android.view.View inflate(org.xmlpull.v1.XmlPullParser parser, android.view.ViewGroup root) {
        return inflate(parser, root, root != null);
    }
    /** AOSP inflate(XmlPullParser, ViewGroup, boolean) (aosp main :509-609):
     *  advanceToRootNode (next() FIRST, then to the first START_TAG, else
     *  InflateException "No start tag found!"), then the root element and its
     *  subtree, consuming the parser through the root's END_TAG. Here the
     *  element becomes the DOM inflate(int) already walks
     *  (macapkDocumentFromParser), and the rest is inflate(int)'s own body,
     *  failures included: a view that cannot be built degrades to the empty
     *  FrameLayout inflate(int) returns, rather than AOSP's InflateException.
     *
     *  The RETURN VALUE is AOSP's, not inflate(int)'s: `View result = root`
     *  (:517), replaced by the inflated view only when
     *  `root == null || !attachToRoot` (:584-586). So attaching returns the
     *  ROOT, and a caller that goes on using the result as its container (or
     *  casts it to root's type) gets root. And a `<merge>` root with no root
     *  to merge into, or attachToRoot=false, is AOSP's InflateException
     *  (:534-538), thrown before anything is built.
     *
     *  MACAPK_INFLATE_PARSER=0 restores the old stub, which returned `root`
     *  without reading the parser. */
    public android.view.View inflate(org.xmlpull.v1.XmlPullParser parser, android.view.ViewGroup root, boolean attachToRoot) {
        if (!MACAPK_INFLATE_PARSER) return root;
        if (parser == null) throw new NullPointerException("parser");
        Document doc;
        try {
            doc = macapkDocumentFromParser(parser, true);
        } catch (Throwable t) {
            throw new InflateException(t.getMessage(), t);
        }
        if (doc == null) {
            throw new InflateException(parser.getPositionDescription() + ": No start tag found!");
        }
        final boolean attach = root != null && attachToRoot;
        if (!attach && "merge".equals(doc.getDocumentElement().getTagName())) {
            throw new InflateException("<merge /> can be used only with a valid "
                    + "ViewGroup root and attachToRoot=true");
        }
        int id = 0;
        if (parser instanceof com.macapk.loader.axml.AxmlParser ap) id = ap.getSourceResId();
        try {
            View v = macapkInflateDocument(doc, root, attachToRoot, id);
            return attach ? root : v;
        } catch (Throwable t) {
            macapkReportInflateFailure("inflate(" + parser.getPositionDescription()
                              + ") failed: " + t, id, t);
            return attach ? root : new android.widget.FrameLayout(context);
        }
    }

    /** Wave 5 (2026-09-26), docs/w5-inflater-fixes_2026-09-26.md E3. Default ON;
     *  `MACAPK_INFLATE_FAIL_ONCE=0` restores a full NativeLog.e line plus
     *  printStackTrace() for EVERY swallowed inflate failure. */
    static final boolean MACAPK_INFLATE_FAIL_ONCE =
        !"0".equals(System.getenv("MACAPK_INFLATE_FAIL_ONCE"));

    /** Failures seen per (layout id, exception class). Bounded by the app's
     *  layout count times the distinct exception classes it can raise. */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicInteger> macapkInflateFailCounts =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The report for an inflate failure the two catches above SWALLOW.
     *
     *  There is no AOSP behaviour to mirror here: AOSP propagates
     *  InflateException, and the swallow-and-substitute is ours (it keeps the
     *  rest of the app alive). The logging is therefore ours to shape, and it
     *  followed the anti-spam idiom nowhere: every failure wrote a NativeLog.e
     *  line AND a full printStackTrace. MEASURED on BobBall (fleet tablet A/B,
     *  2026-09-25): one layout failing on every traversal produced 1,630
     *  identical traces in ~8 s; 72,542 of the 79,524 run.log lines were stack
     *  frames and the log grew ~700 KB/s. An installed app's live log is
     *  deliberately unbounded within a run (launcher/AppStubMain.swift :88-90),
     *  so a screen parked on such a failure fills the disk at ~2.5 GB/hour.
     *  That trigger (a platform layout served as binary XML to the text DOM
     *  parser) is fixed elsewhere; this mechanism stays for the next one.
     *
     *  Same shape as View.measure's warnedMeasureThrowClasses: the FIRST
     *  failure of each (layout id, exception class) prints exactly what it
     *  printed before, so no diagnosis is lost; repeats print one short
     *  counted line at the 2nd, 10th, 100th, 1000th, ... occurrence, which
     *  keeps a runaway loop visible and its growth logarithmic. A different
     *  layout, or a different exception on the same layout, is a new key and
     *  gets its own full trace. `resId` is 0 for a parser that carries no
     *  source id; those share one key per exception class. */
    private static void macapkReportInflateFailure(String msg, int resId, Throwable t) {
        if (!MACAPK_INFLATE_FAIL_ONCE) {
            NativeLog.e(TAG, msg);
            t.printStackTrace();
            return;
        }
        String key = Integer.toHexString(resId) + '|' + t.getClass().getName();
        int n = macapkInflateFailCounts
            .computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger())
            .incrementAndGet();
        if (n == 1) {
            NativeLog.e(TAG, msg);
            t.printStackTrace();
            return;
        }
        if (n == 2 || macapkIsPowerOfTen(n)) {
            NativeLog.e(TAG, msg + " [repeat #" + n + " for layout 0x"
                + Integer.toHexString(resId) + "; stack printed on the first; next report at #"
                + (n == 2 ? 10 : n * 10L) + "]");
        }
    }

    private static boolean macapkIsPowerOfTen(int n) {
        if (n < 10) return false;
        while (n % 10 == 0) n /= 10;
        return n == 1;
    }
    public android.view.View onCreateView(android.content.Context viewContext, android.view.View parent, String name, android.util.AttributeSet attrs) throws ClassNotFoundException {
        return onCreateView(parent, name, attrs);
    }
    protected android.view.View onCreateView(android.view.View parent, String name, android.util.AttributeSet attrs) throws ClassNotFoundException {
        return onCreateView(name, attrs);
    }
    protected android.view.View onCreateView(String name, android.util.AttributeSet attrs) throws ClassNotFoundException {
        return null;
    }
}

