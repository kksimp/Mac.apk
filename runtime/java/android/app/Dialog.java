// android.app.Dialog -- Per AOSP source at
// https://cs.android.com/.../app/Dialog.java. Apps create Dialog
// instances for showing modal content. Mac.apk impl is minimal --
// the dialog is created but never actually displayed (no system
// dialog host). All listener callbacks fire correctly because the
// lifecycle methods (show/dismiss/cancel) just toggle internal
// state without rendering chrome.
package android.app;
import android.content.Context;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.View;

// 2026-08-13 (type-identity audit, docs/type-identity-audit_2026-08-12.md):
// AOSP's real declaration is `public class Dialog implements
// DialogInterface, Window.Callback, KeyEvent.Callback,
// OnCreateContextMenuListener, Window.OnWindowDismissedCallback`. Adding
// the two gaps ranked highest by blast radius here (34 and 32 of the 62
// corpus apps respectively); OnCreateContextMenuListener and
// Window.OnWindowDismissedCallback are separate, unsized gaps left alone.
//
// KeyEvent.Callback (4 methods) is free: onKeyDown/onKeyUp/onKeyLongPress/
// onKeyMultiple below already carry descriptors that match
// android.view.KeyEvent.Callback exactly (checked with javap against the
// built jar, not a source grep -- see the audit doc for why a grep can
// mismatch a sibling nested class's method of the same name).
//
// Window.Callback is also free, for a reason specific to THIS tree: only
// 1 of its 25 methods (dispatchKeyEvent) is abstract in our
// android/view/Window.java -- the other 24 are `default`, a deliberate
// prior divergence from real AOSP (which keeps ~23 of them abstract; see
// Window.java's own Callback-interface comment). Checked method-by-method
// against the built jar: 18 of the 25 already exist on Dialog below with
// matching descriptors (dispatchKeyEvent, dispatchKeyShortcutEvent,
// dispatchTouchEvent, dispatchGenericMotionEvent, both
// onWindowStartingActionMode overloads, onCreatePanelMenu, onPreparePanel,
// onMenuOpened, onMenuItemSelected, onWindowAttributesChanged,
// onWindowFocusChanged, onAttachedToWindow, onDetachedFromWindow,
// onPanelClosed, onCreatePanelView, onActionModeStarted,
// onActionModeFinished). The other 7 (the no-arg and SearchEvent
// onSearchRequested overloads, onProvideKeyboardShortcuts,
// onPointerCaptureChanged, dispatchPopulateAccessibilityEvent,
// dispatchTrackballEvent, onContentChanged) fall through to the
// interface's own defaults -- false/empty/no-op answers already vetted
// against AOSP's "nobody handled it" behavior when this interface was
// written, not fabricated here. So this declaration adds zero new method
// bodies to Dialog; every method reachable through the interface resolves
// to a real body, either Dialog's own or the interface's.
//
// v1.0.1642: the Dialog IS now installed as its own Window's callback
// (`w.setCallback(this)` in initWindow, as AOSP's constructor does --
// android14 Dialog.java:208-210). Keys reach a dialog the way DecorView
// delivers them, through getWindow().getCallback(), and AppCompat's
// WindowCallbackWrapper (AppCompatDialog / ComponentDialog / BottomSheetDialog
// / DialogFragment) wraps THIS instead of Window's private no-op Callback --
// with the no-op every key sent to a dialog vanished and BACK never
// cancelled it.
public class Dialog implements DialogInterface, android.content.DialogInterface.OnCancelListener,
        android.view.Window.Callback, KeyEvent.Callback,
        // AOSP also declares View.OnCreateContextMenuListener
        // (frameworks/base/core/java/android/app/Dialog.java, android16-release):
        // registerForContextMenu(view) installs THIS dialog as the view's
        // create-context-menu listener, so the type identity is load-bearing --
        // without it the registration below could not even compile, and an app
        // doing `instanceof` against the platform type answers false.
        View.OnCreateContextMenuListener {
    protected final Context mContext;
    private CharSequence mTitle;
    private boolean mShowing;
    private boolean mCancelable = true;
    private boolean mCanceledOnTouchOutside = true;
    private android.view.Window mWindow;
    private DialogInterface.OnCancelListener mCancelListener;
    private DialogInterface.OnDismissListener mDismissListener;
    private DialogInterface.OnShowListener mShowListener;
    private DialogInterface.OnKeyListener mKeyListener;
    private Activity mOwnerActivity;
    // AOSP media/java/android/media/AudioManager.java (android13-release) :810
    // USE_DEFAULT_STREAM_TYPE = Integer.MIN_VALUE. Not referenced symbolically:
    // android.media.AudioManager does not define this constant in our tree
    // either (a separate gap, not this file's to fix).
    private int mVolumeControlStream = Integer.MIN_VALUE;

    /** MACAPK_DIALOG_THEME_WRAPPER (default ON; =0 restores every pre-change
     *  constructor body verbatim, for bisecting). The dialog's Context carries
     *  its DIALOG theme, the AOSP way. android14 core/java/android/app/Dialog.java
     *  :193-204, verbatim:
     *
     *      Dialog(@UiContext @NonNull Context context, @StyleRes int themeResId,
     *              boolean createContextThemeWrapper) {
     *          if (createContextThemeWrapper) {
     *              if (themeResId == Resources.ID_NULL) {
     *                  final TypedValue outValue = new TypedValue();
     *                  context.getTheme().resolveAttribute(R.attr.dialogTheme, outValue, true);
     *                  themeResId = outValue.resourceId;
     *              }
     *              mContext = new ContextThemeWrapper(context, themeResId);
     *          } else {
     *              mContext = context;
     *          }
     *
     *  and :170/:189 route Dialog(Context) and Dialog(Context, int) through it
     *  with createContextThemeWrapper=true. We IGNORED themeResId and stored the
     *  caller's Context, so the window read the ACTIVITY theme. Every consumer
     *  of that theme was wrong for a dialog: the PhoneWindow.generateLayout
     *  window attributes (windowIsFloating, backgroundDimEnabled,
     *  backgroundDimAmount -- see macapkGenerateLayout below), and everything
     *  AppCompat / Material build from getContext(): androidx's
     *  AppCompatDialog(Context, int) and ComponentDialog hand their resolved
     *  theme UP to this constructor and rely on the wrapper (their
     *  AlertController and delegate are built from getContext(), not from the
     *  context they were given). BottomSheetDialog passes its
     *  bottomSheetDialogTheme the same way.
     *
     *  The protected constructors are this(context) plus field writes on AOSP
     *  (:228-239), so they wrap too. (The deprecated Message form also stores
     *  its cancel Message; that has its own gate, MACAPK_DIALOG_CANCEL_MESSAGE,
     *  so this one bisects the theme alone.) */
    static final boolean MACAPK_DIALOG_THEME_WRAPPER =
        !"0".equals(System.getenv("MACAPK_DIALOG_THEME_WRAPPER"));

    /** MACAPK_DIALOG_DIM_FROM_THEME (default ON; =0 restores the hardcoded
     *  FLAG_DIM_BEHIND + dimAmount 0.6 seeded in initWindow, for bisecting).
     *  The window's dim comes from the dialog WINDOW theme the way
     *  PhoneWindow.generateLayout reads it (see macapkGenerateLayout). MEASURED
     *  (tablet A/B, 2026-09-26): Fossify Gallery's all-files dialog dims the
     *  page by ~0.34 on the emulator (its Material3 dialog overlay sets
     *  android:backgroundDimAmount 0.32) and by ~0.62 on us, because 0.6 was a
     *  constant here. */
    static final boolean MACAPK_DIALOG_DIM_FROM_THEME =
        !"0".equals(System.getenv("MACAPK_DIALOG_DIM_FROM_THEME"));

    /** The dim is read from the dialog context's theme, which is only the
     *  DIALOG theme when the wrapper above is on: with it off a platform
     *  Dialog's context is the activity, whose theme does not float, and
     *  generateLayout's rule would then take its scrim away. So the theme read
     *  needs BOTH gates, and MACAPK_DIALOG_THEME_WRAPPER=0 alone restores the
     *  whole pre-change behaviour (unwrapped context, FLAG_DIM_BEHIND at 0.6),
     *  while MACAPK_DIALOG_DIM_FROM_THEME=0 isolates the dim. */
    static final boolean MACAPK_DIM_FROM_THEME_ACTIVE =
        MACAPK_DIALOG_DIM_FROM_THEME && MACAPK_DIALOG_THEME_WRAPPER;

    /** MACAPK_DIALOG_FLOATING_FROM_THEME (default ON; =0 keeps every dialog
     *  window at the WRAP_CONTENT seeded in initWindow, the pre-change
     *  behaviour). PhoneWindow.generateLayout only wraps a FLOATING window
     *  (`if (mIsFloating) setLayout(WRAP_CONTENT, WRAP_CONTENT)`); any other
     *  window keeps WindowManager.LayoutParams' MATCH_PARENT default. So a
     *  Dialog built on a theme with windowIsFloating=false is full screen on a
     *  device. MEASURED (2026-10-26): ejoysdk's login BrowserDialogFragment
     *  (theme WebView.Dialog.FullScreen, windowIsFloating=false) got a 20x20px
     *  window here and its WebView never showed. Needs the wrapper for the same
     *  reason as the dim: only then is the context's theme the dialog's. */
    static final boolean MACAPK_DIALOG_FLOATING_FROM_THEME =
        !"0".equals(System.getenv("MACAPK_DIALOG_FLOATING_FROM_THEME"));
    static final boolean MACAPK_FLOATING_FROM_THEME_ACTIVE =
        MACAPK_DIALOG_FLOATING_FROM_THEME && MACAPK_DIALOG_THEME_WRAPPER;

    /** MACAPK_DIALOG_WINDOW_ELEVATION (default ON; =0 leaves the dialog decor
     *  at elevation 0 and Window.setElevation a no-op, the pre-change
     *  behaviour). The dialog window takes its theme's windowElevation onto its
     *  decor, as PhoneWindow.generateLayout does (DialogWindow.macapkLoadElevation),
     *  and WindowOverlayStack casts that root's shadow the way the window's
     *  renderer does on a device. MEASURED (tablet A/B, 2026-09-26): Fossify's
     *  Material3 dialog shows a shadow on the emulator, (138,138,140) straight
     *  below the panel rising to the (171,171,171) scrim over about 16px; ours
     *  was flat scrim to the edge, because nothing in runtime/java read
     *  windowElevation (docs/fleet-widget-apps-mastodon-fossify-pianoli
     *  _2026-09-26.md, F4). Needs MACAPK_DIALOG_THEME_WRAPPER: the value is read
     *  from the DIALOG theme, and with the wrapper off the context is the
     *  activity's. */
    static final boolean MACAPK_DIALOG_WINDOW_ELEVATION =
        !"0".equals(System.getenv("MACAPK_DIALOG_WINDOW_ELEVATION"));
    static final boolean MACAPK_WINDOW_ELEVATION_ACTIVE =
        MACAPK_DIALOG_WINDOW_ELEVATION && MACAPK_DIALOG_THEME_WRAPPER;

    /** MACAPK_DIALOG_MINWIDTH_FROM_THEME (default ON; =0 applies the size-class
     *  min width to every dialog, the pre-change rule). The min width applies
     *  only when the dialog theme binds windowMinWidthMajor/Minor for the
     *  orientation (DialogWindow.macapkThemeBindsMinWidth). Needs
     *  MACAPK_DIALOG_THEME_WRAPPER for the same reason as the elevation, and
     *  MACAPK_DIALOG_WIDTH_AOSP, which owns the min-width measure. */
    static final boolean MACAPK_DIALOG_MINWIDTH_FROM_THEME =
        !"0".equals(System.getenv("MACAPK_DIALOG_MINWIDTH_FROM_THEME"));
    static final boolean MACAPK_MINWIDTH_FROM_THEME_ACTIVE =
        MACAPK_DIALOG_MINWIDTH_FROM_THEME && MACAPK_DIALOG_THEME_WRAPPER;

    /** android.R.attr ids (public-final.xml; frozen, so safe as literals -- this
     *  tree ships no android.R class, see com.macapk.loader.FrameworkAttrIds). */
    static final int ATTR_DIALOG_THEME            = 0x01010308;  // dialogTheme
    static final int ATTR_ALERT_DIALOG_THEME      = 0x01010309;  // alertDialogTheme
    static final int ATTR_TIME_PICKER_DIALOG_THEME = 0x0101049e; // timePickerDialogTheme
    static final int ATTR_DATE_PICKER_DIALOG_THEME = 0x010104ac; // datePickerDialogTheme

    public Dialog(Context c) { this(c, 0, true); }
    public Dialog(Context c, int themeResId) { this(c, themeResId, true); }
    /** AOSP's package-private three-argument constructor (android14 :193).
     *  AlertDialog.Builder.create() uses it with createContextThemeWrapper=false
     *  because the Builder's Context is already wrapped. */
    Dialog(Context context, int themeResId, boolean createContextThemeWrapper) {
        // AOSP android14 Dialog.java:139, a field every constructor runs first:
        //     private final Handler mHandler = new Handler();
        // so a Dialog cannot be built on a thread without a Looper -- on a
        // device, notably the GLSurfaceView GLThread, where it throws
        // "Can't create handler inside thread ... that has not called
        // Looper.prepare()". We keep no such Handler, but make the same demand
        // (MACAPK_GL_THREAD_NO_LOOPER; only the renderer thread is held to it,
        // see Handler.macapkImplicitLooper). docs/gl-thread-has-no-looper_2026-10-01.md
        android.os.Handler.macapkRequireLooperOnGlThread();
        this.mContext = (MACAPK_DIALOG_THEME_WRAPPER && createContextThemeWrapper)
                ? macapkThemedContext(context, themeResId, ATTR_DIALOG_THEME)
                : context;
        initWindow();
    }
    protected Dialog(Context context, boolean cancelable, OnCancelListener cancelListener) {
        this(context);
        this.mCancelable = cancelable; this.mCancelListener = cancelListener;
    }
    @Deprecated
    protected Dialog(Context context, boolean cancelable, android.os.Message cancelCallback) {
        this(context);
        this.mCancelable = cancelable;
        // Its own gate, not MACAPK_DIALOG_THEME_WRAPPER: storing the cancel
        // Message is not theme behaviour, and an A/B of the wrapper must not
        // also switch cancel-Message delivery on and off.
        if (MACAPK_DIALOG_CANCEL_MESSAGE) this.mCancelMessage = cancelCallback;
    }

    /** MACAPK_DIALOG_CANCEL_MESSAGE (default ON; =0 restores the pre-change
     *  drop, for bisecting). AOSP android14 Dialog.java:228-231, the deprecated
     *  protected constructor, verbatim:
     *
     *      protected Dialog(@NonNull Context context, boolean cancelable,
     *              @Nullable Message cancelCallback) {
     *          this(context);
     *          mCancelable = cancelable;
     *          mCancelMessage = cancelCallback;
     *      }
     *
     *  Ours stored cancelable and dropped the Message, so cancel() never told
     *  the caller. */
    static final boolean MACAPK_DIALOG_CANCEL_MESSAGE =
        !"0".equals(System.getenv("MACAPK_DIALOG_CANCEL_MESSAGE"));

    /** The dialog Context: `new ContextThemeWrapper(context, themeResId)` after
     *  resolving themeResId 0 through `themeAttr` (AOSP: ?android:dialogTheme
     *  for Dialog, ?android:alertDialogTheme for AlertDialog, the picker
     *  themes for the pickers). AOSP reads outValue.resourceId whether or not
     *  resolveAttribute answered, so an unresolved attribute wraps with 0,
     *  which our ContextThemeWrapper treats as "no overlay" (it forwards to the
     *  base theme). A null Context stays null: AOSP would NPE on it, and
     *  GoogleApiAvailability here constructs `new Dialog((Context) null)`,
     *  which must keep working exactly as before. */
    static Context macapkThemedContext(Context context, int themeResId, int themeAttr) {
        if (context == null) return null;
        if (themeResId == 0) themeResId = macapkResolveThemeAttr(context, themeAttr);
        return new MacapkDialogContext(context, themeResId);
    }

    /** `context.getTheme().resolveAttribute(attr, outValue, true);
     *   return outValue.resourceId;` -- the idiom every AOSP dialog class uses. */
    static int macapkResolveThemeAttr(Context context, int attr) {
        try {
            android.util.TypedValue out = new android.util.TypedValue();
            android.content.res.Resources.Theme th = context.getTheme();
            if (th != null) th.resolveAttribute(attr, out, true);
            return out.resourceId;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The ContextThemeWrapper a dialog runs in. It IS an
     *  android.view.ContextThemeWrapper (apps test for that), and adds only the
     *  one AOSP behaviour our ContextThemeWrapper does not have yet:
     *  android14 ContextThemeWrapper.getSystemService,
     *
     *      if (LAYOUT_INFLATER_SERVICE.equals(name)) {
     *          if (mInflater == null) {
     *              mInflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
     *          }
     *          return mInflater;
     *      }
     *
     *  i.e. the wrapper's inflater is the BASE context's inflater cloned into
     *  the wrapper, so it inflates in the dialog theme AND keeps the factories
     *  the activity installed (AppCompat's Factory2 turns <Button> into
     *  AppCompatButton). Ours forwards the service to the base, which inflates
     *  in the ACTIVITY theme; and LayoutInflater.from(wrapper) builds a fresh
     *  inflater with no factories. Before this change a platform dialog's
     *  Context WAS the activity, so its inflater was the activity's; keeping
     *  the factories here is what stops the wrapper from regressing that.
     *
     *  LayoutInflater.from(ctx) on AOSP IS ctx.getSystemService(
     *  LAYOUT_INFLATER_SERVICE), so it answers that same clone. Ours never asks
     *  getSystemService: it builds a FRESH, factory-less inflater per Context.
     *  So LayoutInflater.from(dialog.getContext()) lost the activity's factory
     *  once the dialog context stopped being the activity. MEASURED (review,
     *  2026-09-26): Unit Converter's own AppCompatDialog, pre-show
     *  setContentView(int) -- AppCompatDelegateImpl inflates with
     *  LayoutInflater.from(mContext) -- consulted the activity's Factory2 3
     *  times with the wrapper off and 0 times with it on. macapkOwnInflater()
     *  below is the hook LayoutInflater.from uses to hand back THIS clone
     *  (debug/dialogtheme_verify/inflater-from-own.patch, LayoutInflater +
     *  ContextThemeWrapper, not this file's owner's). */
    static final class MacapkDialogContext extends android.view.ContextThemeWrapper {
        private android.view.LayoutInflater mInflater;
        MacapkDialogContext(Context base, int themeResId) { super(base, themeResId); }
        @Override public Object getSystemService(String name) {
            if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) return macapkInflater();
            return super.getSystemService(name);
        }
        /** AOSP's mInflater: LayoutInflater.from(getBaseContext()).cloneInContext(this),
         *  or null when the base has no inflater to clone. Never calls
         *  LayoutInflater.from(this), so LayoutInflater.from can ask it without
         *  recursing. */
        private android.view.LayoutInflater macapkCloneOfBase() {
            if (mInflater == null) {
                Context b = getBaseContext();
                if (b == null) return null;
                android.view.LayoutInflater li = null;
                try { li = android.view.LayoutInflater.from(b); }
                catch (Throwable ignored) {}
                if (li != null) mInflater = li.cloneInContext(this);
            }
            return mInflater;
        }
        /** The inflater LayoutInflater.from(this) must return: AOSP's
         *  getSystemService(LAYOUT_INFLATER_SERVICE) answer. Null falls back to
         *  LayoutInflater's own per-Context cache. No @Override on purpose: the
         *  declaring method on android.view.ContextThemeWrapper arrives with
         *  inflater-from-own.patch, and this class must compile on both sides
         *  of it (a same-signature protected method overrides it once it
         *  exists, JLS 8.4.8.1). */
        protected android.view.LayoutInflater macapkOwnInflater() { return macapkCloneOfBase(); }
        android.view.LayoutInflater macapkInflater() {
            android.view.LayoutInflater own = macapkCloneOfBase();
            return (own != null) ? own : android.view.LayoutInflater.from(this);
        }
    }

    /** The inflater a dialog built on `c` inflates with: the wrapper's AOSP
     *  inflater (see MacapkDialogContext) when `c` is one, else the old
     *  LayoutInflater.from(c). AOSP: PhoneWindow's mLayoutInflater and
     *  AlertController's mInflater are both LayoutInflater.from(dialog context),
     *  which on AOSP IS getSystemService(LAYOUT_INFLATER_SERVICE). */
    static android.view.LayoutInflater macapkInflaterFor(Context c) {
        if (c instanceof MacapkDialogContext) return ((MacapkDialogContext) c).macapkInflater();
        return android.view.LayoutInflater.from(c);
    }

    // AOSP's Dialog(Context, int, boolean) constructor does
    // `mWindow = new PhoneWindow(mContext); w.setWindowManager(...)`, so
    // getWindow() is guaranteed non-null for the Dialog's whole life. App code
    // routinely does getWindow().setBackgroundDrawable(...) / setLayout(...) /
    // getAttributes() / getDecorView() immediately after construction; a null
    // window NPEs there. This mirrors the Activity.getWindow() fix (ActivityWindow
    // + wired WindowManager) that resolved the CandyCrush NPE. AlertDialog /
    // ProgressDialog inherit this since they extend Dialog.
    private void initWindow() {
        DialogWindow w = new DialogWindow(mContext);
        this.mWindow = w;
        w.setCallback(this);   // AOSP Dialog ctor (android14 :208-210)
        // AOSP shapes a Dialog's window (WRAP_CONTENT, centered, floating)
        // through its theme (windowIsFloating) -- this tree has no theme/
        // style resolution system (see Window.isFloating()'s comment), so
        // seed the same shape directly onto the Window's OWN attributes
        // object. That object -- not a fresh one -- is what show() below
        // now hands to WindowManager.addView (matching AOSP's real
        // Dialog.show(): "WindowManager.LayoutParams l = mWindow.
        // getAttributes(); mWindowManager.addView(mDecor, l);"), so it is
        // both our sensible default for a Dialog nobody customizes AND the
        // live object addFlags/setFlags/setLayout/setGravity mutate for a
        // Dialog that DOES customize its window before calling show().
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.width  = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = android.view.Gravity.CENTER;
        lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
        // lane canvas, 2026-08-03: DIM BEHIND. On AOSP a dialog theme carries
        // `backgroundDimEnabled=true` (the default -- themes.xml only turns it
        // OFF for the handful of Panel/Translucent/NoDisplay/Wallpaper themes)
        // and PhoneWindow.generateLayout turns that into
        //   FLAG_DIM_BEHIND with params.dimAmount = ?backgroundDimAmount
        // whose value is 0.6 in BOTH themes.xml:54 and themes_material.xml:53
        // (dark) / :429 (light). We have no windowIsFloating/theme plumbing on
        // the Dialog path, so seed the same result directly -- exactly as the
        // WRAP_CONTENT/CENTER shape above is seeded, and for the same reason.
        //
        // Without it every dialog painted with the activity fully bright behind
        // it. Measured victims: newpipe's two startup dialogs and
        // unitconverter's Help dialog, both of which the emulator shows over a
        // 0.6-black scrim. An app that wants no dim still wins, because
        // clearFlags(FLAG_DIM_BEHIND) mutates this same live attributes object.
        //
        // 2026-09-26: MACAPK_DIALOG_DIM_FROM_THEME moves this to where AOSP does
        // it, PhoneWindow.generateLayout at decor installation, reading the
        // dialog window theme (DialogWindow.macapkGenerateLayout). The AOSP
        // constructor leaves the LayoutParams at their defaults (flags 0,
        // dimAmount 1.0), so nothing is seeded here then. The 0.6 above was
        // right for Material / AppCompat themes and wrong for Material3, whose
        // dialog overlays set backgroundDimAmount 0.32.
        if (!MACAPK_DIM_FROM_THEME_ACTIVE) {
            lp.flags |= android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0.6f;
        }
        try {
            if (mContext instanceof android.content.ContextWrapper || mContext instanceof Activity) {
                Object wm = mContext.getSystemService(Context.WINDOW_SERVICE);
                if (wm instanceof android.view.WindowManager) {
                    w.setWindowManager((android.view.WindowManager) wm, null,
                        mContext.getPackageName());
                }
            }
        } catch (Throwable ignored) {}
    }

    // Concrete android.view.Window for a Dialog. Headless (Mac.apk has no system
    // dialog host), but every method is non-null-safe so app-side window
    // customization does not NPE. Backed by a FrameLayout decor like
    // ActivityWindow, so getWindow().getDecorView() / findViewById work.
    private static final class DialogWindow extends android.view.Window {
        private android.view.View decor;
        private android.widget.FrameLayout content;
        // Set by the app via getWindow().setBackgroundDrawable(...). AOSP lets an
        // app-supplied window background REPLACE the theme's windowBackground, so
        // record it and let it win over the default panel below. A null here
        // means "app never said", NOT "app asked for none" -- an app that really
        // wants no panel passes a ColorDrawable(TRANSPARENT), which is non-null
        // and therefore still wins.
        private android.graphics.drawable.Drawable appBackground;
        private boolean appBackgroundSet;
        DialogWindow(Context c) { this.mContext = c; }

        /** AOSP Window.mForcedWindowFlags / mHaveDimAmount (android14
         *  Window.java:316/:324): setFlags records its MASK as forced (:1284
         *  `mForcedWindowFlags |= mask;`) and setDimAmount records that the
         *  app chose a dim (:1401 `mHaveDimAmount = true;`), so
         *  generateLayout's theme values never override an app's own call.
         *  Our Window.getForcedWindowFlags() answers 0 and tracks neither, so
         *  the dialog window tracks them for macapkGenerateLayout. Only
         *  consulted when the theme read is active (MACAPK_DIM_FROM_THEME_ACTIVE). */
        private int mMacapkForcedFlags;
        private boolean mMacapkHaveDimAmount;
        @Override public void setFlags(int flags, int mask) {
            super.setFlags(flags, mask);
            mMacapkForcedFlags |= mask;
        }
        @Override public void setDimAmount(float amount) {
            super.setDimAmount(amount);
            mMacapkHaveDimAmount = true;
        }
        /** An app's own setLayout before the decor is installed wins over the
         *  theme's non-floating MATCH_PARENT (AOSP never resizes a
         *  non-floating window in generateLayout). */
        private boolean mMacapkAppSetLayout;
        @Override public void setLayout(int width, int height) {
            super.setLayout(width, height);
            mMacapkAppSetLayout = true;
        }

        /** The Window-styleable attributes macapkGenerateLayout reads, in
         *  ascending id order (the order AOSP's styleable arrays use). */
        private static final int A_BACKGROUND_DIM_AMOUNT  = 0x01010032;
        private static final int A_WINDOW_IS_FLOATING     = 0x01010057;
        private static final int A_BACKGROUND_DIM_ENABLED = 0x0101021f;
        private static final int A_WINDOW_MIN_WIDTH_MAJOR = 0x01010356;
        private static final int A_WINDOW_MIN_WIDTH_MINOR = 0x01010357;
        private static final int A_WINDOW_ELEVATION       = 0x01010490;
        private static final int[] MACAPK_WINDOW_STYLE = {
            A_BACKGROUND_DIM_AMOUNT, A_WINDOW_IS_FLOATING, A_BACKGROUND_DIM_ENABLED };

        /** What macapkGenerateLayout read, for the trace and the offline gate
         *  (debug/dialogtheme_gate.sh reads it reflectively): "theme" (the
         *  window theme answered), "unmodelled" (it answered nothing, the legacy
         *  seed was applied), or null (not run: gate off, or no decor yet). */
        String mMacapkDimSource;

        /** The dim half of AOSP PhoneWindow.generateLayout (android14
         *  PhoneWindow.java:2407+), run where AOSP runs it -- once, when the
         *  decor is installed:
         *
         *      TypedArray a = getWindowStyle();   // mContext.obtainStyledAttributes(R.styleable.Window)
         *      mIsFloating = a.getBoolean(R.styleable.Window_windowIsFloating, false);
         *      ...
         *      if (a.getBoolean(R.styleable.Window_backgroundDimEnabled, mIsFloating)) {
         *          if ((getForcedWindowFlags()&WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0) {
         *              params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
         *          }
         *          if (!haveDimAmount()) {
         *              params.dimAmount = a.getFloat(
         *                      android.R.styleable.Window_backgroundDimAmount, 0.5f);
         *          }
         *      }
         *
         *  mContext is the DIALOG context (the wrapper MACAPK_DIALOG_THEME_WRAPPER
         *  builds), so this reads the dialog window theme: 0.32 under a Material3
         *  dialog overlay, 0.6 under Theme.Material / AppCompat / the legacy
         *  Theme.Dialog (all inherit the root Theme's 0.6). An app's
         *  clearFlags(FLAG_DIM_BEHIND) or setDimAmount() before this point is
         *  honoured through the forced/have tracking above; after it, the app's
         *  call simply overwrites, as on AOSP.
         *
         *  UNMODELLED THEME: every platform theme descends from the root Theme,
         *  which defines backgroundDimAmount (themes.xml:54, 0.6). A window style
         *  that answers NEITHER backgroundDimAmount NOR windowIsFloating means
         *  the theme could not be resolved at all (no resource table, a null or
         *  bare Context) -- not a theme that asked for no dim. Then the window
         *  keeps the pre-change seed (FLAG_DIM_BEHIND, 0.6), subject to the same
         *  app-wins rules, rather than silently losing its scrim.
         *
         *  NOT PORTED from generateLayout (listed, deliberately; see the lane
         *  report and docs/dialog-window-theme_2026-09-26.md):
         *  Window.isFloating() itself, windowCloseOnTouchOutside, windowSoftInputMode,
         *  windowAnimationStyle, windowNoTitle, the VALUE of windowMinWidth* and
         *  windowFixed* from the theme (Dialog uses a size-class table for the
         *  value, see MACAPK_DIALOG_WIDTH_AOSP; whether the theme binds one at
         *  all IS read, macapkThemeBindsMinWidth), windowBlurBehind*,
         *  windowBackgroundBlurRadius,
         *  windowTranslucentStatus/Navigation, windowFullscreen, and the
         *  window background from windowBackground (Dialog paints its own
         *  panel, macapkApplyDialogBackground). */
        void macapkGenerateLayout() {
            if (!MACAPK_DIM_FROM_THEME_ACTIVE && !MACAPK_FLOATING_FROM_THEME_ACTIVE) return;
            final android.view.WindowManager.LayoutParams params = getAttributes();
            if (params == null) return;
            boolean modelled = false, floating = false, dimEnabled = false;
            float dimAmount = 0.5f;
            android.content.res.TypedArray a = null;
            try {
                if (mContext != null) a = mContext.obtainStyledAttributes(MACAPK_WINDOW_STYLE);
                if (a != null) {
                    modelled = a.hasValue(0) || a.hasValue(1);
                    floating = a.getBoolean(1, false);
                    dimEnabled = a.getBoolean(2, floating);
                    dimAmount = a.getFloat(0, 0.5f);
                }
            } catch (Throwable ignored) {
                modelled = false;
            } finally {
                if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
            }
            if (MACAPK_FLOATING_FROM_THEME_ACTIVE && modelled && !floating
                    && !mMacapkAppSetLayout) {
                params.width  = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            }
            if (!MACAPK_DIM_FROM_THEME_ACTIVE) return;
            if (!modelled) {
                dimEnabled = true;
                dimAmount = 0.6f;
            }
            mMacapkDimSource = modelled ? "theme" : "unmodelled";
            if (dimEnabled) {
                if ((mMacapkForcedFlags
                        & android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0) {
                    params.flags |= android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                }
                if (!mMacapkHaveDimAmount) params.dimAmount = dimAmount;
            }
            if (System.getenv("MACAPK_TRACE_DIALOG_THEME") != null) {
                try {
                    com.macapk.loader.NativeLog.i("Mac.apk.Dialog", "generateLayout ctx="
                        + (mContext == null ? "null" : mContext.getClass().getName())
                        + " source=" + mMacapkDimSource + " floating=" + floating
                        + " dimEnabled=" + dimEnabled + " dimAmount=" + params.dimAmount
                        + " flags=0x" + Integer.toHexString(params.flags));
                } catch (Throwable ignored) {}
            }
        }
        /** See Dialog.macapkSetThemeWindowBackground. Records nothing as the
         *  app's: an app background set before or after still replaces it. */
        void macapkSetThemeBackground(android.graphics.drawable.Drawable d) {
            if (appBackgroundSet || d == null) return;
            ensureDecor();
            if (decor == null) return;
            decor.setBackground(d);
            android.graphics.Rect pad = new android.graphics.Rect();
            boolean has = false;
            try { has = d.getPadding(pad); } catch (Throwable ignored) {}
            if (!has) pad.set(0, 0, 0, 0);
            try { decor.setPadding(pad.left, pad.top, pad.right, pad.bottom); }
            catch (Throwable ignored) {}
        }
        @Override public void setBackgroundDrawable(android.graphics.drawable.Drawable d) {
            appBackground = d; appBackgroundSet = true;
            if (decor != null) {
                decor.setBackground(d);
                // The decor may already be carrying the 16dp padding our default
                // panel installed below. That padding is OURS, not the app's, so
                // replacing the background must replace it too -- otherwise an app
                // that swaps in its own (often transparent, often padding-less)
                // window background silently keeps a 16dp inset it never asked for.
                android.graphics.Rect pad = new android.graphics.Rect();
                boolean has = false;
                try { has = d != null && d.getPadding(pad); } catch (Throwable ignored) {}
                if (!has) pad.set(0, 0, 0, 0);
                try { decor.setPadding(pad.left, pad.top, pad.right, pad.bottom); }
                catch (Throwable ignored) {}
            }
        }
        private void ensureDecor() {
            if (decor == null) {
                // AOSP PhoneWindow.installDecor: generateDecor, then
                // generateLayout(mDecor) applies the window theme. Once per
                // window, before anything can add the decor to the WindowManager.
                macapkGenerateLayout();
                macapkGenerateBarColors();
                macapkLoadElevation();
                // AOSP DecorView.onMeasure: a floating window measured with an
                // AT_MOST width (i.e. WRAP_CONTENT) is grown to its min width,
                // computed on EVERY measure from the size it is offered, after
                // ViewRootImpl.measureHierarchy has first offered it only
                // config_prefDialogWidth. See the comment above
                // Dialog.MACAPK_DIALOG_WIDTH_AOSP for the numbers, the size
                // classes and the citations.
                android.widget.FrameLayout d = new android.widget.FrameLayout(mContext) {
                    // The measureHierarchy memo (MACAPK_DIALOG_MEASURE_MEMO):
                    // the inputs of the last full pre-pass and the width spec
                    // its final super.onMeasure used. minW and pref are pure
                    // functions of (width spec, box height, density), so those
                    // three are the whole key. (pref is read from the window's
                    // Resources under MACAPK_DIALOG_PREF_WIDTH_SW600, i.e. from
                    // the Configuration; that only moves on a display resize,
                    // which offers the decor a new box and so a new key.)
                    private boolean mMemoValid;
                    private int mMemoWSpec, mMemoHSpec, mMemoAvailH, mMemoFinalWSpec;
                    private float mMemoDensity;

                    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.AT_MOST) {
                            mMemoValid = false;
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                            return;
                        }
                        final int avail = MeasureSpec.getSize(widthMeasureSpec);
                        if (!MACAPK_DIALOG_WIDTH_AOSP) {
                            // v1.0.1647 behaviour, verbatim, for bisecting.
                            final int w = macapkFloatingWidthPx(avail,
                                    MeasureSpec.getSize(heightMeasureSpec));
                            if (w > 0) {
                                widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                                        Math.min(w, avail), MeasureSpec.EXACTLY);
                            }
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                            return;
                        }
                        final float density = macapkDensity(getContext());
                        // The AT_MOST width is always the box (only WRAP_CONTENT
                        // produces it), but the height spec is the box only when
                        // lp.height is WRAP_CONTENT or MATCH_PARENT:
                        // WindowOverlayStack.specFor turns a fixed lp.height into
                        // EXACTLY(lp.height). AOSP reads orientation and size
                        // class from the Configuration, never from the window's
                        // own height, so for a fixed height take the box height
                        // from the Configuration. MEASURED (review, 2026-09-25):
                        // getWindow().setLayout(WRAP_CONTENT, 700) on the 808px
                        // phone box read as a 808x700 LANDSCAPE box and gave 525
                        // where AOSP gives 767; on the 3360px tablet box a short
                        // fixed height dropped xlarge to normal (2184, not 1512).
                        final android.view.WindowManager.LayoutParams wa =
                                DialogWindow.this.getAttributes();
                        final int availH = (wa != null && wa.height >= 0)
                                ? macapkConfigHeightPx(getContext(), density,
                                        MeasureSpec.getSize(heightMeasureSpec))
                                : MeasureSpec.getSize(heightMeasureSpec);
                        // AOSP runs measureHierarchy only on a traversal that has
                        // a layout request (ViewRootImpl.performTraversals, android14
                        // :3064-3087 `if (layoutRequested) { ... measureHierarchy`),
                        // and View.measure skips onMeasure outright for an
                        // unchanged spec without PFLAG_FORCE_LAYOUT (View.java
                        // :27096-27110). Our overlay stack re-measures every
                        // visible window on every present, so without this the
                        // pre-pass would re-measure the content at two or more
                        // alternating widths per FRAME (a Compose root reads each
                        // as new constraints; a TextView rebuilds its Layout at
                        // both). Same inputs and no layout request since the last
                        // layout(): replay only the pass the ladder settled on,
                        // which is what the full ladder's last measure was.
                        if (MACAPK_DIALOG_MEASURE_MEMO && mMemoValid && !isLayoutRequested()
                                && widthMeasureSpec == mMemoWSpec
                                && heightMeasureSpec == mMemoHSpec
                                && availH == mMemoAvailH && density == mMemoDensity) {
                            super.onMeasure(mMemoFinalWSpec, heightMeasureSpec);
                            return;
                        }
                        final int sizeClass = macapkSizeClass(avail, availH, density);
                        // DecorView grows the window only when the theme binds
                        // the min width for this orientation (see
                        // macapkThemeBindsMinWidth); 0 never grows it.
                        final int minW = macapkThemeBindsMinWidth(avail < availH)
                                ? Math.min(avail, macapkMinWidthPx(avail, availH, sizeClass))
                                : 0;
                        final int pref = MACAPK_DIALOG_PREF_WIDTH_SW600
                                ? macapkPrefDialogWidthPx(getResources(), sizeClass,
                                        macapkSmallestWidthDp(avail, availH, density), density)
                                : macapkPrefDialogWidthPx(sizeClass, density);
                        final int finalWSpec = macapkPrePass(widthMeasureSpec,
                                heightMeasureSpec, avail, minW, pref);
                        mMemoValid = true;
                        mMemoWSpec = widthMeasureSpec;
                        mMemoHSpec = heightMeasureSpec;
                        mMemoAvailH = availH;
                        mMemoDensity = density;
                        mMemoFinalWSpec = finalWSpec;
                    }

                    /** ViewRootImpl.measureHierarchy (android14 :2711-2745): a
                     *  WRAP_CONTENT window is first measured at
                     *  config_prefDialogWidth; only if the content reports
                     *  MEASURED_STATE_TOO_SMALL is it retried halfway to the
                     *  available width, and then at the full width. Returns
                     *  the width spec of the last super.onMeasure it made. */
                    private int macapkPrePass(int wSpec, int hSpec, int avail, int minW,
                                              int pref) {
                        if (pref != 0 && avail > pref) {
                            int s = macapkDecorMeasure(MeasureSpec.makeMeasureSpec(pref,
                                    MeasureSpec.AT_MOST), hSpec, minW);
                            if ((getMeasuredWidthAndState() & MEASURED_STATE_TOO_SMALL) == 0) {
                                return s;
                            }
                            final int base = (pref + avail) / 2;
                            s = macapkDecorMeasure(MeasureSpec.makeMeasureSpec(base,
                                    MeasureSpec.AT_MOST), hSpec, minW);
                            if ((getMeasuredWidthAndState() & MEASURED_STATE_TOO_SMALL) == 0) {
                                return s;
                            }
                        }
                        return macapkDecorMeasure(wSpec, hSpec, minW);
                    }

                    /** DecorView.onMeasure (android14 :687-785) for a floating
                     *  window whose windowFixedWidth* is TYPE_NULL (every Material
                     *  and AppCompat dialog theme sets them @null): measure, then
                     *  if the width spec is AT_MOST and the result is narrower
                     *  than the min width, measure again at EXACTLY(min). The
                     *  min is NOT clamped to the spec: DecorView grows past a
                     *  pre-pass width, which is how a 440dp pre-pass still ends
                     *  in a 45%-wide window. Returns the width spec of the last
                     *  super.onMeasure. */
                    private int macapkDecorMeasure(int wSpec, int hSpec, int minW) {
                        super.onMeasure(wSpec, hSpec);
                        if (MeasureSpec.getMode(wSpec) == MeasureSpec.AT_MOST
                                && getMeasuredWidth() < minW) {
                            final int grown = MeasureSpec.makeMeasureSpec(minW,
                                    MeasureSpec.EXACTLY);
                            super.onMeasure(grown, hSpec);
                            return grown;
                        }
                        return wSpec;
                    }
                };
                android.widget.FrameLayout c = new android.widget.FrameLayout(mContext);
                c.setId(0x01020002);  // android.R.id.content
                d.addView(c);
                // A Dialog's window is a PhoneWindow on Android, so its decor gets
                // the same installDecor treatment as an Activity's (see
                // Activity.ActivityWindow.ensureDecor): focus search stops here.
                d.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                d.setIsRootNamespace(true);
                decor = d; content = c;
                if (appBackgroundSet) d.setBackground(appBackground);
                else macapkApplyDialogBackground(d, mContext);
                // generateLayout's "remaining setup ... that only applies to
                // top-level windows": `mDecor.setElevation(mElevation);`
                // (android14 PhoneWindow.java:2736).
                if (MACAPK_WINDOW_ELEVATION_ACTIVE) d.setElevation(mMacapkElevation);
            }
        }

        /** PhoneWindow.mElevation. */
        private float mMacapkElevation;

        /** android14 PhoneWindow.generateLayout (:2641-2642), inside
         *  `if (getContainer() == null)`:
         *
         *      if (mLoadElevation) {
         *          mElevation = a.getDimension(R.styleable.Window_windowElevation, 0);
         *      }
         *
         *  mLoadElevation is false only for a preserved window (an activity
         *  relaunch), never for a dialog, so the theme value is read once, at
         *  decor installation -- and it replaces a setElevation() the app made
         *  before that, exactly as on AOSP. Theme.Material's dialogs bind
         *  @dimen/floating_window_z = 16dp (themes_material.xml:902, :1099,
         *  :1232), Material3's alert overlay 6dp; the root Theme and
         *  Theme.Dialog bind nothing (0: the traditional alert casts no
         *  shadow). */
        private void macapkLoadElevation() {
            if (!MACAPK_WINDOW_ELEVATION_ACTIVE) return;
            float e = 0f;
            android.content.res.TypedArray a = null;
            try {
                if (mContext != null) a = mContext.obtainStyledAttributes(new int[] { A_WINDOW_ELEVATION });
                if (a != null) e = a.getDimension(0, 0f);
            } catch (Throwable ignored) {
                e = 0f;
            } finally {
                if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
            }
            mMacapkElevation = e;
        }

        /** android14 PhoneWindow.setElevation (:1504-1511):
         *
         *      mElevation = elevation;
         *      final WindowManager.LayoutParams attrs = getAttributes();
         *      if (mDecor != null) {
         *          mDecor.setElevation(elevation);
         *          attrs.setSurfaceInsets(mDecor, true, false);
         *      }
         *      dispatchWindowAttributesChanged(attrs);
         *
         *  The surface insets exist so the shadow has pixels to land on; our
         *  overlay raster is the whole surface, so there is nothing to grow.
         *  The dispatch reaches Dialog.onWindowAttributesChanged, which re-lays
         *  the window out and re-rasters it with the new shadow. Base Window's
         *  empty body under MACAPK_DIALOG_WINDOW_ELEVATION=0. */
        @Override public void setElevation(float elevation) {
            if (!MACAPK_WINDOW_ELEVATION_ACTIVE) { super.setElevation(elevation); return; }
            mMacapkElevation = elevation;
            final android.view.WindowManager.LayoutParams attrs = getAttributes();
            if (decor != null) decor.setElevation(elevation);
            try { dispatchWindowAttributesChanged(attrs); } catch (Throwable ignored) {}
        }

        /** PhoneWindow.getElevation: mElevation. (No @Override: this tree's
         *  android.view.Window does not declare getElevation yet; AOSP's base
         *  answers 0.0f. Once it does, this overrides it.) */
        public float getElevation() {
            return MACAPK_WINDOW_ELEVATION_ACTIVE ? mMacapkElevation : 0f;
        }

        /** Whether DecorView.onMeasure would grow this window to its min width
         *  in this orientation: android14 DecorView.onMeasure (:757-770)
         *  applies it only `if (tv.type != TypedValue.TYPE_NULL)`, where tv is
         *  the theme's windowMinWidthMinor (portrait) or windowMinWidthMajor
         *  (landscape), read by PhoneWindow.generateLayout. Material, Holo and
         *  AppCompat dialog themes bind both; the root Theme, Theme.Dialog and
         *  Theme.Dialog.Alert bind neither (0 hits in themes.xml), so a
         *  traditional dialog keeps its config_prefDialogWidth width. We applied
         *  the size-class table to every dialog. True -- the table, as before
         *  -- when the gate is off and when the theme is UNMODELLED (answers
         *  neither backgroundDimAmount nor windowIsFloating, which every real
         *  platform theme defines; see macapkGenerateLayout). Read once: a
         *  window's theme does not change. */
        private int mMacapkMinWidthBinding = -1;   // -1 unread; bit0 major, bit1 minor, bit2 unmodelled
        boolean macapkThemeBindsMinWidth(boolean portrait) {
            if (!MACAPK_MINWIDTH_FROM_THEME_ACTIVE) return true;
            if (mMacapkMinWidthBinding < 0) {
                int bits = 4;
                android.content.res.TypedArray a = null;
                try {
                    if (mContext != null) {
                        a = mContext.obtainStyledAttributes(new int[] {
                            A_BACKGROUND_DIM_AMOUNT, A_WINDOW_IS_FLOATING,
                            A_WINDOW_MIN_WIDTH_MAJOR, A_WINDOW_MIN_WIDTH_MINOR });
                    }
                    if (a != null && (a.hasValue(0) || a.hasValue(1))) {
                        bits = (a.hasValue(2) ? 1 : 0) | (a.hasValue(3) ? 2 : 0);
                    }
                } catch (Throwable ignored) {
                    bits = 4;
                } finally {
                    if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
                }
                mMacapkMinWidthBinding = bits;
            }
            if ((mMacapkMinWidthBinding & 4) != 0) return true;
            return (mMacapkMinWidthBinding & (portrait ? 2 : 1)) != 0;
        }

        /** The AOSP dialog window background, rebuilt in code because we ship no
         *  framework resource table.
         *
         *  `Theme.Material.BaseDialog` (themes_material.xml:912, :1131) sets
         *      <item name="windowBackground">@drawable/dialog_background_material</item>
         *  and that drawable is, verbatim
         *  (core/res/res/drawable/dialog_background_material.xml, fetched
         *  2026-08-03):
         *      <inset android:inset="16dp">
         *        <shape android:shape="rectangle">
         *          <corners android:radius="?attr/dialogCornerRadius" />
         *          <solid android:color="?attr/colorBackground" />
         *        </shape>
         *      </inset>
         *  with `dialogCornerRadius` = @dimen/dialog_corner_radius = 2dp
         *  (dimens.xml:1017, bound at themes_material.xml:192/570) and the dialog
         *  theme rebinding `colorBackground` to `?attr/colorBackgroundFloating`
         *  (:940, :1126) = #ffffff light / #424242 dark.
         *
         *  WHY IT MATTERS: our DialogWindow decor was a bare FrameLayout with no
         *  background at all, so a dialog's text painted straight onto the
         *  activity behind it -- newpipe's two startup dialogs rendered as
         *  overlapping unstyled paragraphs at full screen width. The panel is
         *  what makes a dialog read as a dialog.
         *
         *  The polarity comes from the app's own theme cascade, so a dark-themed
         *  app gets the dark panel without anything here knowing which app it is. */
        private static void macapkApplyDialogBackground(android.view.View d, Context c) {
            android.graphics.drawable.Drawable bg = macapkDialogBackground(c);
            if (bg == null) return;
            // setBackground applies the drawable's own padding as of v1.0.871 --
            // AOSP's setBackgroundDrawable does that, and ours now does too, so
            // the hand-rolled copy that used to live here is gone.
            // InsetDrawable.getPadding returns the child's padding PLUS the inset
            // (InsetDrawable.java:113), so the 16dp dialog inset becomes 16dp of
            // decor padding and the content lands INSIDE the drawn panel.
            //
            // MEASURED without it: newpipe's dialog title and button bar rendered
            // OUTSIDE the white panel -- content flowed into the 16dp inset band
            // that the background deliberately does not paint.
            d.setBackground(bg);
        }

        private static android.graphics.drawable.Drawable macapkDialogBackground(Context c) {
            try {
                // Through the dialog's OWN Context first -- see the long note on
                // AlertDialog.macapkThemeAttrColor. Monotone: Theme.resolveAttrColor
                // falls back to exactly this static call on a cascade miss.
                int fill = 0xFFFFFFFF;
                boolean gotFill = false;
                try {
                    android.content.res.Resources.Theme th =
                            (c != null) ? c.getTheme() : null;
                    if (th != null) {
                        fill = th.resolveAttrColor("colorBackgroundFloating", 0xFFFFFFFF);
                        gotFill = true;
                    }
                } catch (Throwable ignored) {}
                if (!gotFill) {
                    fill = android.content.res.Resources.macapkThemeColor(
                            "colorBackgroundFloating", 0xFFFFFFFF);
                }
                float density = android.util.DisplayMetrics.macapkDeviceDensity();
                try {
                    android.content.res.Resources r = c == null ? null : c.getResources();
                    if (r != null && r.getDisplayMetrics() != null
                            && r.getDisplayMetrics().density > 0) {
                        density = r.getDisplayMetrics().density;
                    }
                } catch (Throwable ignored) {}
                android.graphics.drawable.GradientDrawable shape =
                        new android.graphics.drawable.GradientDrawable();
                shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                shape.setColor(fill);
                shape.setCornerRadius(2f * density);      // dialog_corner_radius 2dp
                int inset = Math.round(16f * density);    // <inset android:inset="16dp">
                return new android.graphics.drawable.InsetDrawable(
                        shape, inset, inset, inset, inset);
            } catch (Throwable t) {
                return null;
            }
        }
        // ---- bar colours: the DIALOG window's own (w5-windecor, F1c/F1d) --------
        // android15 PhoneWindow.generateLayout reads statusBarColor /
        // navigationBarColor for EVERY PhoneWindow, floating or not, from THAT
        // window's theme, and enforces edge-to-edge on it the same way
        // (:2500-2507, :2613-2632); mForcedStatusBarColor and friends are per
        // window. DialogWindow inherited Window's getters, which answer the
        // PROCESS-STATIC forced pair or 0: a dialog on a tSDK<35 app read 0 where a
        // device reads the dialog theme's (sentinel-mapped) black, and any
        // window's setStatusBarColor leaked into every other window's answer.
        // Same shared implementation as ActivityWindow
        // (Activity.macapkGenerateBarColors), same gate: MACAPK_WINDOW_BAR_COLORS=0
        // restores the inherited getters exactly; MACAPK_EDGE_TO_EDGE=0 is the
        // diagnostic sentinel-only state. super keeps writing the statics that
        // StatusBarDriver paints from, so nothing painted moves.
        // docs/fwprobe-answer-parity-remaining_2026-09-26.md F1c.
        private static final boolean MACAPK_WINDOW_BAR_COLORS =
                !"0".equals(System.getenv("MACAPK_WINDOW_BAR_COLORS"));
        private static final boolean MACAPK_EDGE_TO_EDGE =
                !"0".equals(System.getenv("MACAPK_EDGE_TO_EDGE"));
        private int mMacapkStatusBarColor;
        private int mMacapkNavigationBarColor;
        private boolean mMacapkForcedStatusBarColor;
        private boolean mMacapkForcedNavigationBarColor;
        boolean mMacapkEdgeToEdgeEnforced;
        private void macapkGenerateBarColors() {
            if (!MACAPK_WINDOW_BAR_COLORS || mContext == null) return;
            final int[] r = Activity.macapkGenerateBarColors(mContext, mMacapkStatusBarColor,
                    mMacapkNavigationBarColor, mMacapkForcedStatusBarColor,
                    mMacapkForcedNavigationBarColor, MACAPK_EDGE_TO_EDGE);
            mMacapkStatusBarColor = r[0];
            mMacapkNavigationBarColor = r[1];
            mMacapkEdgeToEdgeEnforced = r[2] != 0;
        }
        @Override public int getStatusBarColor() {
            return MACAPK_WINDOW_BAR_COLORS ? mMacapkStatusBarColor : super.getStatusBarColor();
        }
        @Override public int getNavigationBarColor() {
            if (!MACAPK_WINDOW_BAR_COLORS) return super.getNavigationBarColor();
            return mMacapkEdgeToEdgeEnforced ? 0 : mMacapkNavigationBarColor;
        }
        @Override public void setStatusBarColor(int color) {
            super.setStatusBarColor(color);
            if (!MACAPK_WINDOW_BAR_COLORS || mMacapkEdgeToEdgeEnforced) return;
            mMacapkStatusBarColor = color;
            mMacapkForcedStatusBarColor = true;
        }
        @Override public void setNavigationBarColor(int color) {
            super.setNavigationBarColor(color);
            if (!MACAPK_WINDOW_BAR_COLORS) return;
            mMacapkNavigationBarColor = color;
            mMacapkForcedNavigationBarColor = true;
        }

        @Override public android.view.View getDecorView() { ensureDecor(); return decor; }
        /** AOSP PhoneWindow.peekDecorView: the decor if it exists, never forcing
         *  it. v1.0.1642: DialogWindow inherited Window's `return null`, so
         *  superDispatchKeyEvent never reached the dialog's views and
         *  getCurrentFocus() was always null. */
        @Override public android.view.View peekDecorView() { return decor; }
        @Override public void setContentView(int layoutResID) {
            ensureDecor();
            try {
                // AOSP PhoneWindow.setContentView inflates with mLayoutInflater,
                // LayoutInflater.from(the window's context) -- the dialog wrapper's
                // own inflater (see MacapkDialogContext) under the theme wrapper.
                android.view.LayoutInflater inf = MACAPK_DIALOG_THEME_WRAPPER
                        ? macapkInflaterFor(mContext)
                        : android.view.LayoutInflater.from(mContext);
                if (inf != null) { content.removeAllViews(); inf.inflate(layoutResID, content, true); }
            } catch (Throwable t) {
                // NEVER swallow this silently (v1.0.867). A failed inflate here
                // leaves the dialog decor EMPTY, and because the decor is still
                // measured, laid out and painted with its window background, the
                // user sees a correctly-sized, correctly-coloured, completely
                // blank panel -- which looks like a layout bug anywhere else in
                // the framework and gives no clue that an inflate threw.
                // Measured victim: unitconverter's Help dialog, which is an
                // androidx AlertDialog whose AlertController.installContent()
                // calls exactly this method with abc_alert_dialog_material.
                com.macapk.loader.NativeLog.w("Mac.apk.Dialog",
                    "setContentView(0x" + Integer.toHexString(layoutResID)
                    + ") inflate FAILED -> dialog will be blank: " + t);
            }
        }
        @Override public void setContentView(android.view.View v) {
            ensureDecor(); if (v != null) { content.removeAllViews(); content.addView(v); }
        }
        @Override public void setContentView(android.view.View v, android.view.ViewGroup.LayoutParams p) {
            ensureDecor(); if (v != null) { content.removeAllViews(); content.addView(v, p); }
        }
        @Override public void addContentView(android.view.View v, android.view.ViewGroup.LayoutParams p) {
            ensureDecor(); if (v != null) content.addView(v, p);
        }
        @Override public android.view.View findViewById(int id) {
            ensureDecor(); return decor == null ? null : decor.findViewById(id);
        }
        @Override public void setTitle(CharSequence title) {}
    }

    private boolean mCreated;
    /** Log the first few frames of a swallowed dialog-lifecycle throwable. The
     *  message alone is rarely enough -- the useful part is WHICH inflate or
     *  which androidx class blew up -- but a full trace per dialog would drown
     *  the log, so cap it. */
    private static void macapkLogTrace(Throwable t) {
        try {
            StackTraceElement[] st = t.getStackTrace();
            int n = Math.min(st.length, 8);
            for (int i = 0; i < n; i++) {
                com.macapk.loader.NativeLog.w("Mac.apk.Dialog", "    at " + st[i]);
            }
            Throwable c = t.getCause();
            if (c != null) com.macapk.loader.NativeLog.w("Mac.apk.Dialog", "  caused by: " + c);
        } catch (Throwable ignored) {}
    }
    public void show()      {
        // AOSP Dialog.show(): an already-shown dialog (possibly hide()n) only
        // has its decor made VISIBLE again -- it is not re-created or re-added.
        // DialogFragment relies on this: onStop() hides, onStart() shows.
        if (mShowing) {
            if (mDecorAdded != null) mDecorAdded.setVisibility(android.view.View.VISIBLE);
            return;
        }
        mShowing = true;
        // AOSP dispatchOnCreate: onCreate(savedInstanceState) runs exactly once,
        // guarded, BEFORE onStart(). The standard idiom is a Dialog subclass that
        // overrides onCreate() to call setContentView() + wire its buttons/data;
        // skipping onCreate left all that subclass init un-run.
        // These two used to swallow silently. They must NOT (v1.0.867): a
        // Dialog subclass builds ALL of its content in onCreate -- androidx's
        // AlertDialog does its AlertController.installContent() there, and
        // AppCompatDialog its subdecor creation -- so an exception thrown in
        // here produces a dialog that is shown, measured, and painted with its
        // window background but is COMPLETELY EMPTY, with nothing in the log.
        // That is indistinguishable from a layout bug and is exactly how
        // unitconverter's blank Help panel hid. Still caught (a half-built
        // dialog must not take down the activity), but never in silence.
        macapkDispatchOnCreate();
        try { onStart(); }
        catch (Throwable t) {
            com.macapk.loader.NativeLog.w("Mac.apk.Dialog", "onStart THREW: " + t);
            macapkLogTrace(t);
        }
        // v1.0.691: AOSP show() ends with mWindowManager.addView(mDecor, params).
        // Without it the decor tree is built but never composited -- the dialog
        // draws nothing. Now the decor joins the overlay stack that
        // ViewCanvasDriver paints over the Activity content.
        //
        // v<next>: pass mWindow.getAttributes() (per real AOSP Dialog.show(),
        // confirmed against raw.githubusercontent.com aosp-mirror/platform_
        // frameworks_base core/java/android/app/Dialog.java: "WindowManager.
        // LayoutParams l = mWindow.getAttributes(); mWindowManager.addView(
        // mDecor, l);") instead of a hardcoded fresh LayoutParams. A fresh
        // object here silently discarded any addFlags/setFlags/setLayout/
        // setGravity the app called on getWindow() before show() -- paired
        // with Window.setFlags/addFlags/clearFlags being no-ops (fixed in
        // the same change), an app-customized window's flags/size/gravity
        // were always silently reset to plain defaults. NOT the actual lever
        // for the bug this was investigated alongside, though (see below) --
        // kept because it is an independently real AOSP divergence.
        //
        // The actual GD Meltdown touch-swallow bug was a DIFFERENT, deeper
        // gap: WindowOverlayStack's layer bounds were only ever computed by
        // ViewCanvasDriver's Canvas-app draw pass, which a GL-rendered game
        // (cocos2d-x here) never reaches -- so AppLovin's real, non-
        // cancelable Terms/Privacy consent AlertDialog (com.applovin.impl.z0,
        // builder.setCancelable(false)) came up over the game with bounds
        // stuck at (0,0,0,0), and every touch anywhere on screen read as
        // "outside" it and got permanently consumed, blocking the game's own
        // "OK" button underneath. Fixed in WindowOverlayStack.presentForGl +
        // GLSurfaceView.macapkDrawFrame. See
        // docs/gl-overlay-dialog-compositing_2026-07-23.md for the full
        // diagnosis (this file's fix plays a supporting, not primary, role).
        // AOSP show() runs onCreate/onStart and THEN mWindowManager.addView
        // (android14 Dialog.java:341), which builds a ViewRootImpl whose
        // `new ViewRootHandler()` (ViewRootImpl.java:6073) throws on a thread
        // without a Looper -- out of show(), with mShowing (set only AFTER addView
        // there) still false and no show message sent. The catch below swallows
        // addView failures, so the demand is made here, outside it: a Dialog BUILT
        // on the UI thread and show()n from a GLSurfaceView renderer must not read
        // as shown with no window behind it. MACAPK_GL_THREAD_NO_LOOPER;
        // docs/gl-thread-has-no-looper_2026-10-01.md
        try { android.os.Handler.macapkRequireLooperOnGlThread(); }
        catch (RuntimeException e) { mShowing = false; throw e; }
        try {
            android.view.View decor = mWindow != null ? mWindow.getDecorView() : null;
            Object wm = mContext != null ? mContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE) : null;
            if (decor != null && mWindow != null && wm instanceof android.view.WindowManager) {
                android.view.WindowManager.LayoutParams lp = mWindow.getAttributes();
                // lp.width stays WRAP_CONTENT, as on AOSP: the floating width
                // is decided at MEASURE time by the decor itself
                // (DialogWindow.ensureDecor: the config_prefDialogWidth
                // pre-pass, then DecorView's macapkMinWidthPx; under
                // MACAPK_DIALOG_WIDTH_AOSP=0, the old macapkFloatingWidthPx).
                ((android.view.WindowManager) wm).addView(decor, lp);
                // Give the overlay stack a way back to this Dialog, so a tap
                // OUTSIDE the decor can cancel it the way AOSP's
                // Window.shouldCloseOnTouch does. Without the backlink an
                // outside tap has nothing to cancel and is swallowed.
                try { com.macapk.loader.WindowOverlayStack.macapkSetOwner(decor, Dialog.this); }
                catch (Throwable ignored) { }
                mDecorAdded = decor;
                mAddedTo = (android.view.WindowManager) wm;
            }
        } catch (Throwable t) { /* dialog still runs its lifecycle */ }
        // AOSP posts this (Dialog.show -> sendShowMessage -> ListenersHandler
        // SHOW -> onShow, android14 Dialog.java:360, :1483-1484), so a throwing
        // listener is a crash on a device. We run it inline and keep catching --
        // moving it onto a posted message is a separate ordering change -- but
        // never in silence: a listener that dies part-way leaves the dialog
        // half-configured with no other trace. Measured: AppLovin's consent
        // listener (com.applovin.impl.z0) NPE'd on findViewById(alertTitle)
        // before its setLinkTextColor / setMovementMethod / setMinHeight ran,
        // and nothing in the log said so
        // (docs/fleet-games-smashhit-gd-meltdown-jumplite_2026-09-26.md, G1).
        if (mShowListener != null) {
            try { mShowListener.onShow(this); }
            catch (Throwable t) {
                try {
                    com.macapk.loader.NativeLog.w("Mac.apk.Dialog",
                        "OnShowListener THREW -> the app's show-time setup did not finish: " + t);
                } catch (Throwable ignored) {}
                macapkLogTrace(t);
            }
        }
    }

    /** The dialog theme's window background (AOSP PhoneWindow.generateLayout's
     *  mBackgroundDrawable from windowBackground), handed over by a content
     *  installer that has read it -- AlertDialog's traditional tree, whose
     *  Theme.Dialog.Alert binds @color/transparent. Replaces the default panel
     *  Dialog paints, including its padding; an app's own
     *  getWindow().setBackgroundDrawable() still wins, as it does over the
     *  theme on AOSP. */
    void macapkSetThemeWindowBackground(android.graphics.drawable.Drawable d) {
        if (mWindow instanceof DialogWindow) ((DialogWindow) mWindow).macapkSetThemeBackground(d);
    }
    /** AOSP's DecorView.onMeasure grows a FLOATING window to its theme's
     *  `windowMinWidthMinor` (portrait) / `Major` (landscape) whenever the width
     *  spec is AT_MOST, i.e. whenever the window is WRAP_CONTENT. Every alert
     *  dialog theme binds the same dimens: core/res `dialog_min_width_minor` /
     *  `_major` (Theme.Material.Dialog.BaseAlert, themes_material.xml:1198-1200,
     *  and the .MinWidth variants) and AppCompat's identical
     *  `abc_dialog_min_width_minor` / `_major` (Base.Theme.AppCompat.Dialog.Alert,
     *  themes_base.xml:606-608). Those dimens are SIZE-QUALIFIED, and both
     *  libraries carry the same three rows (android14-release core/res
     *  values/dimens.xml:210,240, values-large/dimens.xml:27-28,
     *  values-xlarge/dimens.xml:28-29; androidx appcompat values*, dimens.xml):
     *
     *      screen size class   major (landscape)   minor (portrait)
     *      normal / small            65%                 95%
     *      large                     55%                 80%
     *      xlarge                    45%                 72%
     *
     *  The fraction is of `availableWidth = screenWidthDp * density`, truncated
     *  with (int) (DecorView.java:757-770). Before that, ViewRootImpl
     *  .measureHierarchy offers a WRAP_CONTENT window only config_prefDialogWidth
     *  (values/config.xml:183 320dp; values-large/config.xml:24 440dp), and
     *  DecorView's min then grows it back -- ported in DialogWindow.ensureDecor.
     *
     *  ⚠ THE SIZE CLASS (2026-09-25). This used to be `portrait ? 0.95 : 0.65`,
     *  i.e. the `values` row only. That was right for every phone-shaped window
     *  (docs/refuted-approaches.md row 31 measured 213 declarations of exactly
     *  those numbers) and wrong from the moment v1.0.1647 made the automatic
     *  window Android 16's large-screen window: an unlocked app now opens at the
     *  whole screen, an xlarge configuration. MEASURED on the tablet A/B
     *  (docs/tablet-ab-triage_2026-09-25.md, cluster A): NewPipe's and
     *  OpenSudoku's dialogs were 1059.5dp wide, the emulator's 723.5dp -- 65% and
     *  45% of the 1680dp window, less the 2 x 16dp background inset.
     *
     *  ⚠ WHY A TABLE AND NOT THE THEME ATTRIBUTE. AOSP reads the attribute from
     *  the dialog's window theme. We cannot yet get the right value that way:
     *  ResourceIndex.qualifierScore drops every screen-size-class qualifier
     *  (small/normal/large/xlarge), so `@dimen/abc_dialog_min_width_major`
     *  resolves to the `values` 65% on any window. REMOVAL CONDITION: once
     *  resource selection honours those qualifiers, read windowMinWidthMajor/
     *  Minor (and windowFixedWidth*) from getContext().getTheme() instead --
     *  androidx already hands this Dialog its alert-theme ContextThemeWrapper
     *  (refuted-approaches row 30). Until then the table is the values those
     *  themes resolve to, selected the way the qualifiers would select them.
     *
     *  The size class, the orientation and the base width all come from the box
     *  the overlay stack offers, standing in for Configuration.screenWidthDp/
     *  HeightDp and orientation, for the reason below: our metrics have gone
     *  stale on a resized window before. The one exception is a window with a
     *  FIXED lp.height, whose height spec is that height and not the box; its
     *  box height is taken from the Configuration (macapkConfigHeightPx), which
     *  is where AOSP reads all of it.
     *
     *  Applied only to an AT_MOST (WRAP_CONTENT) width spec, exactly AOSP's gate,
     *  so an app that called getWindow().setLayout(...) -- BottomSheetDialog sets
     *  MATCH_PARENT, full screen dialogs likewise -- keeps its own value.
     *
     *  HISTORY. This used to live only in our own android.app.AlertDialog, which
     *  an AppCompat app never constructs -- androidx.appcompat.app.AlertDialog
     *  extends THIS class, so its window kept the bare WRAP_CONTENT seeded in
     *  initWindow and filled the screen. Measured on unitconverter's Help dialog:
     *  our panel spanned x=32..775 (744px) against the emulator's x=52..754
     *  (703px). The emulator's number decomposes exactly: (int)(0.95*808) = 767
     *  window, minus the 2 x 16dp inset the dialog background's InsetDrawable adds
     *  = 703, at left margin (808-767)/2 + 32 = 52. The pre-2026-09-25 code
     *  ROUNDED and landed on 768.
     *
     *  ⚠ MEASURE TIME, IN THE DECOR, NOT show() TIME (2026-09-22). This used to
     *  write `lp.width = round(fraction * DisplayMetrics.widthPixels)` once, in
     *  show(). AOSP does it inside DecorView.onMeasure, on every measure, against
     *  the CURRENT configuration; a baked pixel width goes stale the moment the
     *  window is resized, and our DisplayMetrics did not follow a live resize.
     *  MEASURED on Among Us: metrics said 3360x1860 while the surface was
     *  808x1796, so the dialog got w=2184 on an 808px surface.
     *  WindowOverlayStack.measureAndLayout then measured it at EXACTLY(2184) but
     *  clamped only the LAYOUT box to 808: the panel background shrank to 744px
     *  while the button bar was laid out for 2120px, ABORT/CONTINUE landed at
     *  x=1864..2128, off screen, and the dialog could not be dismissed.
     *
     *  MACAPK_DIALOG_WIDTH_AOSP=0 restores the v1.0.1647 rule
     *  (macapkFloatingWidthPx, a fixed WIDTH of round(0.95|0.65 * box), no
     *  pre-pass) for bisecting. Default ON. */
    static final boolean MACAPK_DIALOG_WIDTH_AOSP =
        !"0".equals(System.getenv("MACAPK_DIALOG_WIDTH_AOSP"));

    /** The decor's measureHierarchy memo (see DialogWindow.ensureDecor): with
     *  the same specs and inputs and no layout request since the last layout(),
     *  re-measure once at the width the pre-pass settled on instead of re-running
     *  the whole pre-pass. It must not change any result -- only how many times
     *  the content is measured per frame, which was 2 to 6 alternating-width
     *  passes on EVERY present before it. MACAPK_DIALOG_MEASURE_MEMO=0 runs the
     *  full pre-pass on every measure, for bisecting. Default ON. */
    static final boolean MACAPK_DIALOG_MEASURE_MEMO =
        !"0".equals(System.getenv("MACAPK_DIALOG_MEASURE_MEMO"));

    /** The v1.0.1647 rule, kept verbatim behind MACAPK_DIALOG_WIDTH_AOSP=0. */
    static int macapkFloatingWidthPx(int availW, int availH) {
        if (availW <= 0 || availH <= 0) return 0;
        final boolean portrait = availW < availH;
        return Math.round((portrait ? 0.95f : 0.65f) * availW);
    }

    /** Configuration.screenLayout's size bucket for a window of this box, via
     *  our port of AOSP's reduceScreenLayout (Configuration
     *  .macapkDeriveScreenLayout: xlarge needs long >= 960dp and short >= 720dp,
     *  large long >= 640dp and short >= 480dp). The dp rounding is
     *  DisplayConfig's. */
    static int macapkSizeClass(int availW, int availH, float density) {
        if (availW <= 0 || availH <= 0 || density <= 0) {
            return android.content.res.Configuration.SCREENLAYOUT_SIZE_NORMAL;
        }
        android.content.res.Configuration c = new android.content.res.Configuration();
        c.screenWidthDp = (int) (availW / density + 0.5f);
        c.screenHeightDp = (int) (availH / density + 0.5f);
        c.macapkDeriveScreenLayout();
        return c.screenLayout & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK;
    }

    /** DecorView's min width for the table above: (int) of the aapt-compiled
     *  fraction times the available width. A resource qualified `large` is
     *  selected on an xlarge configuration too, so each row covers its class
     *  and up until a more specific row wins. */
    static int macapkMinWidthPx(int availW, int availH, int sizeClass) {
        if (availW <= 0 || availH <= 0) return 0;
        final boolean portrait = availW < availH;
        final int pct;
        if (sizeClass >= android.content.res.Configuration.SCREENLAYOUT_SIZE_XLARGE) {
            pct = portrait ? 72 : 45;
        } else if (sizeClass >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE) {
            pct = portrait ? 80 : 55;
        } else {
            pct = portrait ? 95 : 65;
        }
        return (int) (macapkAaptFraction(pct) * availW);
    }

    /** config_prefDialogWidth in px: 320dp, or 440dp on large and up. The
     *  pre-change rule, kept verbatim for MACAPK_DIALOG_PREF_WIDTH_SW600=0. */
    static int macapkPrefDialogWidthPx(int sizeClass, float density) {
        final int dp = sizeClass >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
                ? 440 : 320;
        return (int) (dp * density);
    }

    /** MACAPK_DIALOG_PREF_WIDTH_SW600 (default ON; =0 restores the two-row
     *  table above). config_prefDialogWidth has THREE rows in android14 core/res:
     *  values/config.xml:183 320dp, values-large/config.xml:24 440dp, and
     *  values-sw600dp/config.xml:30 580dp (no other values-* directory binds
     *  it). The smallest-width qualifier outranks the screen-size one in
     *  resource selection, so any window with smallestScreenWidthDp >= 600
     *  gets 580dp whether or not it is also `large`. We had only the first two,
     *  so every tablet-sized dialog was pre-measured at 440dp. It decides the
     *  final width of a dialog whose theme binds no min width (the traditional
     *  Theme.Dialog.Alert): 580dp = 1160px on the 3360x1636 @2 tablet box, which
     *  is what the emulator shows GD Meltdown's consent dialog at
     *  (docs/fleet-games-smashhit-gd-meltdown-jumplite_2026-09-26.md, G5).
     *
     *  With the gate on, the value is READ from the framework table through
     *  the window's Resources, as ViewRootImpl.measureHierarchy does (android14
     *  :2716-2721, verbatim):
     *
     *      final DisplayMetrics packageMetrics = res.getDisplayMetrics();
     *      res.getValue(com.android.internal.R.dimen.config_prefDialogWidth,
     *              mTmpValue, true);
     *      int baseSize = 0;
     *      if (mTmpValue.type == TypedValue.TYPE_DIMENSION) {
     *          baseSize = (int)mTmpValue.getDimension(packageMetrics);
     *      }
     *
     *  so the row is picked by the same resource selection -- against the same
     *  Configuration.smallestScreenWidthDp and size class -- that picks the
     *  app's own sw600dp / large resources, and a row this code does not know
     *  about is answered too. Measured on our ResourceIndex over framework-res
     *  (2026-09-26): sw404 normal -> 640px, sw480 / sw500 / sw590 large ->
     *  880px, sw600 / sw818 -> 1160px, i.e. all three rows. The three-row table
     *  below is only the fallback for a Resources that cannot answer (no
     *  framework table, a non-dimension value); it keys on the box. Note the
     *  two can differ where the Configuration and the box the overlay stack
     *  offers disagree (a box narrower than the display, a Configuration that
     *  has not followed a resize yet): AOSP reads the Configuration there too. */
    static final boolean MACAPK_DIALOG_PREF_WIDTH_SW600 =
        !"0".equals(System.getenv("MACAPK_DIALOG_PREF_WIDTH_SW600"));

    /** The framework id of config_prefDialogWidth: resolved once, since the
     *  framework table is fixed for the process; -1 = not yet found (a 0
     *  answer is not cached, so a lookup made before the table was installed
     *  is retried). */
    private static volatile int sPrefDialogWidthId = -1;

    /** config_prefDialogWidth in px, resolved through `res` (see above), else
     *  the three-row table. */
    static int macapkPrefDialogWidthPx(android.content.res.Resources res, int sizeClass,
                                       int smallestWidthDp, float density) {
        if (res != null) {
            try {
                int id = sPrefDialogWidthId;
                if (id == -1) {
                    id = res.getIdentifier("config_prefDialogWidth", "dimen", "android");
                    if (id != 0) sPrefDialogWidthId = id;
                }
                if (id != 0) {
                    final android.util.TypedValue tv = new android.util.TypedValue();
                    res.getValue(id, tv, true);
                    if (tv.type == android.util.TypedValue.TYPE_DIMENSION) {
                        return (int) tv.getDimension(res.getDisplayMetrics());
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to the table.
            }
        }
        return macapkPrefDialogWidthPx(sizeClass, smallestWidthDp, density);
    }

    /** config_prefDialogWidth in px from all three rows (see above), keyed on
     *  the box: the fallback when the resource table cannot answer. */
    static int macapkPrefDialogWidthPx(int sizeClass, int smallestWidthDp, float density) {
        final int dp = smallestWidthDp >= 600 ? 580
                : sizeClass >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE ? 440 : 320;
        return (int) (dp * density);
    }

    /** Configuration.smallestScreenWidthDp for a window of this box: the
     *  shorter side in dp, with macapkSizeClass's rounding, so the two
     *  qualifiers are read from the same box. */
    static int macapkSmallestWidthDp(int availW, int availH, float density) {
        if (availW <= 0 || availH <= 0 || density <= 0) return 0;
        return (int) (Math.min(availW, availH) / density + 0.5f);
    }

    /** The float a device reads back for "<pct>%": aapt compiles it through
     *  ResTable::stringToFloat (f = pct * (1.0f/100), then
     *  bits = (uint64_t)(f * (1 << 23) + .5f) into a RADIX_0p23 mantissa), and
     *  TypedValue.complexToFloat decodes mantissa / 2^23. So 45% reads back as
     *  0.45000005 (NewPipe's own values-xlarge prints it as 45.000004%) and 80%
     *  as 0.79999995. It changes the truncated width where the product sits on
     *  an integer: (int)(0.79999995f * 3360) is 2687 where (int)(0.8f * 3360) is
     *  2688 (and 767 against 768 at 960px). For the 45% tablet row it does NOT
     *  matter -- 1512 either way. Valid for 0 < pct < 100, which every row above
     *  is. */
    static float macapkAaptFraction(int pct) {
        final float f = pct * (1.0f / 100);
        final long bits = (long) (f * (1 << 23) + .5f);
        return bits / (float) (1 << 23);
    }

    /** The box height for a window whose lp.height is fixed: the
     *  Configuration's screenHeightDp in px. DisplayConfig.compute derives that
     *  as the display height less BOTH decor bars (v1.0.939), the same box
     *  WindowOverlayStack lays a floating window out in. `fallback` (the height
     *  spec, i.e. the pre-review answer) when there is no usable Configuration. */
    static int macapkConfigHeightPx(Context c, float density, int fallback) {
        try {
            android.content.res.Resources r = c == null ? null : c.getResources();
            android.content.res.Configuration cfg = r == null ? null : r.getConfiguration();
            if (cfg != null && cfg.screenHeightDp > 0 && density > 0) {
                return (int) (cfg.screenHeightDp * density + 0.5f);
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    /** DisplayMetrics.density of the dialog's context, 2.0 (our default
     *  xhdpi) when it cannot be read -- the same fallback the dialog background
     *  uses. */
    static float macapkDensity(Context c) {
        try {
            android.content.res.Resources r = c == null ? null : c.getResources();
            if (r != null && r.getDisplayMetrics() != null
                    && r.getDisplayMetrics().density > 0) {
                return r.getDisplayMetrics().density;
            }
        } catch (Throwable ignored) {}
        return 2.0f;
    }

    private android.view.View mDecorAdded;
    private android.view.WindowManager mAddedTo;
    @Override public void dismiss() {
        if (!mShowing) return;
        mShowing = false;
        // v1.0.691: AOSP dismiss() removes the decor from the WindowManager.
        try {
            if (mAddedTo != null && mDecorAdded != null) {
                mAddedTo.removeView(mDecorAdded);
            }
        } catch (Throwable ignored) {}
        mDecorAdded = null; mAddedTo = null;
        try { onStop(); } catch (Throwable ignored) {}
        if (mDismissListener != null) try { mDismissListener.onDismiss(this); } catch (Throwable ignored) {}
        // AOSP dismissDialog(): `if (mDismissMessage != null)
        // Message.obtain(mDismissMessage).sendToTarget();` -- the Message
        // counterpart of the listener above (see setDismissMessage).
        if (mDismissMessage != null) {
            try { android.os.Message.obtain(mDismissMessage).sendToTarget(); }
            catch (Throwable ignored) {}
        }
    }
    private android.os.Message mDismissMessage;
    @Override public void cancel() {
        if (mCancelListener != null) try { mCancelListener.onCancel(this); } catch (Throwable ignored) {}
        // AOSP Dialog.cancel(): `if (!mCanceled && mCancelMessage != null) {
        // mCanceled = true; Message.obtain(mCancelMessage).sendToTarget(); }`.
        // setCancelMessage is an ALTERNATIVE to setOnCancelListener (AOSP wraps
        // the listener into exactly such a Message), so a caller that used the
        // Message form must still be told.
        if (!mCanceled && mCancelMessage != null) {
            mCanceled = true;
            try { android.os.Message.obtain(mCancelMessage).sendToTarget(); }
            catch (Throwable ignored) {}
        }
        dismiss();
    }
    private boolean mCanceled;
    private android.os.Message mCancelMessage;
    /** AOSP Dialog.hide(): hides the decor but leaves the dialog showing, so a
     *  later show() makes it visible again and dismiss() still removes it.
     *  (Clearing mShowing here made dismiss() return early and leave the
     *  decor in the overlay stack.) */
    public void hide() {
        if (mDecorAdded != null) mDecorAdded.setVisibility(android.view.View.GONE);
    }

    public void setTitle(CharSequence title) { this.mTitle = title; }
    public void setTitle(int resId) {}
    public CharSequence getTitle() { return mTitle; }

    // AOSP Dialog delegates content + view lookup to its Window (mWindow).
    public void setContentView(View v) { if (mWindow != null) mWindow.setContentView(v); }
    public void setContentView(int resId) { if (mWindow != null) mWindow.setContentView(resId); }
    public void setContentView(View view, android.view.ViewGroup.LayoutParams params) {
        if (mWindow != null) mWindow.setContentView(view, params);
    }
    public void addContentView(View view, android.view.ViewGroup.LayoutParams params) {
        if (mWindow != null) mWindow.addContentView(view, params);
    }
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) {
        return mWindow == null ? null : (T) mWindow.findViewById(id);
    }
    public <T extends View> T requireViewById(int id) {
        T v = findViewById(id);
        if (v == null) throw new IllegalArgumentException("ID does not reference a View inside this Dialog");
        return v;
    }

    public boolean isShowing() {
        return mShowing && (mDecorAdded == null
                || mDecorAdded.getVisibility() == android.view.View.VISIBLE);
    }
    public Context getContext() { return mContext; }
    public android.view.Window getWindow() { return mWindow; }

    /** AOSP Window.shouldCloseOnTouch's predicate, exposed so the overlay stack
     *  can ask without reaching into private state. */
    public boolean macapkCancelOnTouchOutside() { return mCancelable && mCanceledOnTouchOutside; }

    public void setCancelable(boolean c) { this.mCancelable = c; }
    public void setCanceledOnTouchOutside(boolean c) { this.mCanceledOnTouchOutside = c; }

    public void setOnCancelListener(DialogInterface.OnCancelListener l) { this.mCancelListener = l; }
    public void setOnDismissListener(DialogInterface.OnDismissListener l) { this.mDismissListener = l; }
    public void setOnShowListener(DialogInterface.OnShowListener l) { this.mShowListener = l; }
    public void setOnKeyListener(DialogInterface.OnKeyListener l) { this.mKeyListener = l; }
    // AOSP core/java/android/app/Dialog.java (android13-release) :1435-1450.
    // Previously a no-op setter + a getter hardcoded to 0 with no backing
    // field, so any app that called setVolumeControlStream(x) then
    // getVolumeControlStream() silently got 0 back instead of x.
    public void setVolumeControlStream(int streamType) { mVolumeControlStream = streamType; }
    public final int getVolumeControlStream() { return mVolumeControlStream; }
    // AOSP :268-284. setOwnerActivity is `final`, stores the Activity, AND
    // pushes the owner's suggested volume-control stream into this dialog
    // (":271 getWindow().setVolumeControlStream(mOwnerActivity.getVolumeControlStream())"
    // -- mirrored here directly on mVolumeControlStream since our Window has no
    // such method to delegate through, same reasoning as the setFeatureDrawable*
    // fix above). Previously both halves were stubs (setter no-op, getter
    // hardcoded null), so Dialog.getOwnerActivity() could never return what
    // Activity.showDialog()-style code had just set.
    public void setOwnerActivity(Activity activity) {
        mOwnerActivity = activity;
        mVolumeControlStream = activity.getVolumeControlStream();
    }
    public final Activity getOwnerActivity() { return mOwnerActivity; }

    public android.os.Bundle onSaveInstanceState() { return new android.os.Bundle(); }
    public void onRestoreInstanceState(android.os.Bundle savedInstanceState) {}
    protected void onCreate(android.os.Bundle savedInstanceState) {}
    protected void onStart() {}
    protected void onStop() {}

    public boolean requestWindowFeature(int featureId) { return true; }
    // AOSP core/java/android/app/Dialog.java (android13-release) :1264-1282:
    // all three of these are `void`, delegating to the matching
    // getWindow().setFeatureDrawableXxx(...). We previously returned boolean,
    // which is a DESCRIPTOR mismatch -- any app compiled against the real SDK
    // emits invokevirtual with a ()V-returning signature, so ours threw
    // NoSuchMethodError instead of linking. Fixed to void; still a no-op stub
    // (no real Window feature-drawable rendering exists here to delegate to,
    // matching every other stub in this class).
    public final void setFeatureDrawableUri(int featureId, android.net.Uri uri) {}
    public final void setFeatureDrawableResource(int featureId, int resId) {}
    public final void setFeatureDrawable(int featureId, android.graphics.drawable.Drawable drawable) {}

    /** AOSP android14 Dialog.dispatchKeyEvent: OnKeyListener, then the
     *  window's view tree, then `event.dispatch(this, decor state, this)`.
     *  v1.0.1642: returned the listener's answer (or false) and never reached
     *  onKeyDown/onKeyUp, so BACK could not cancel a dialog. */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mKeyListener != null) {
            try {
                if (mKeyListener.onKey(this, event.getKeyCode(), event)) return true;
            } catch (Throwable ignored) {}
        }
        if (mWindow != null && mWindow.superDispatchKeyEvent(event)) return true;
        android.view.View decor = mWindow != null ? mWindow.peekDecorView() : null;
        return event.dispatch(this,
                decor != null ? decor.getKeyDispatcherState() : mKeyState, this);
    }
    private final KeyEvent.DispatcherState mKeyState = new KeyEvent.DispatcherState();
    /** AOSP Dialog.dispatchKeyShortcutEvent (android14-release :900-905):
     *  {@code if (mWindow.superDispatchKeyShortcutEvent(event)) return true;
     *  return onKeyShortcut(event.getKeyCode(), event);} -- the same window
     *  route dispatchKeyEvent above takes. Was {@code return false}, so an
     *  EditText in a dialog never saw Ctrl+V. */
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (mWindow != null && mWindow.superDispatchKeyShortcutEvent(event)) return true;
        return onKeyShortcut(event.getKeyCode(), event);
    }
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) { return false; }
    /** AOSP Dialog.dispatchGenericMotionEvent (android14-release :954-959):
     *  the window's view tree, then onGenericMotionEvent. */
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        if (mWindow != null && mWindow.superDispatchGenericMotionEvent(ev)) return true;
        return onGenericMotionEvent(ev);
    }
    /** AOSP android14 Dialog.onKeyDown/onKeyUp: BACK and ESCAPE are tracked on
     *  the down and become onBackPressed (cancel, when cancelable) on a
     *  tracked, un-canceled up. */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            event.startTracking();
            return true;
        }
        return false;
    }
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
                && event.isTracking() && !event.isCanceled()) {
            onBackPressed();
            return true;
        }
        return false;
    }
    public boolean onKeyLongPress(int keyCode, KeyEvent event) { return false; }
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) { return false; }
    public boolean onTouchEvent(android.view.MotionEvent event) { return false; }
    public boolean onTrackballEvent(android.view.MotionEvent event) { return false; }
    public boolean onGenericMotionEvent(android.view.MotionEvent event) { return false; }

    @Override public void onCancel(DialogInterface dialog) {
        if (mCancelListener != null) try { mCancelListener.onCancel(dialog); } catch (Throwable ignored) {}
    }
    public void onWindowFocusChanged(boolean hasFocus) {}
    public void onAttachedToWindow() {}
    public void onDetachedFromWindow() {}
    public void onBackPressed() { if (mCancelable) cancel(); }

    private android.window.OnBackInvokedDispatcher mOnBackInvokedDispatcher;
    public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher() {
        try {
            android.view.Window w = getWindow();
            if (w != null) {
                android.window.OnBackInvokedDispatcher d = w.getOnBackInvokedDispatcher();
                if (d != null) return d;
            }
        } catch (Throwable ignored) { }
        if (mOnBackInvokedDispatcher == null) mOnBackInvokedDispatcher = android.window.OnBackInvokedDispatcher.macapkNew();
        return mOnBackInvokedDispatcher;
    }
    /** AOSP: `return getWindow().getLayoutInflater();` -- PhoneWindow's
     *  LayoutInflater.from(dialog context). Under MACAPK_DIALOG_THEME_WRAPPER
     *  that is the wrapper's own inflater (MacapkDialogContext), which keeps the
     *  activity's factories while inflating in the dialog theme. */
    public android.view.LayoutInflater getLayoutInflater() {
        if (MACAPK_DIALOG_THEME_WRAPPER) return macapkInflaterFor(mContext);
        return android.view.LayoutInflater.from(mContext);
    }
    public android.view.View getCurrentFocus() { return null; }
    public void invalidateOptionsMenu() {}
    public boolean onCreateOptionsMenu(android.view.Menu menu) { return false; }
    public boolean onMenuOpened(int featureId, android.view.Menu menu) { return false; }
    public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) { return false; }
    public boolean onOptionsItemSelected(android.view.MenuItem item) { return false; }
    public void onPanelClosed(int featureId, android.view.Menu menu) {}
    public boolean onPreparePanel(int featureId, View view, android.view.Menu menu) { return true; }
    public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) { return false; }
    public View onCreatePanelView(int featureId) { return null; }
    public android.view.View onCreateView(String name, Context context, android.util.AttributeSet attrs) { return null; }
    public android.view.View onCreateView(View parent, String name, Context context, android.util.AttributeSet attrs) { return null; }
    public void onActionModeStarted(android.view.ActionMode mode) {}
    public void onActionModeFinished(android.view.ActionMode mode) {}
    public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) { return null; }
    public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback, int type) { return null; }
    public android.view.ActionMode startActionMode(android.view.ActionMode.Callback callback) { return null; }
    public android.view.ActionMode startActionMode(android.view.ActionMode.Callback callback, int type) { return null; }

    /** AOSP setDismissMessage(Message): {@code mDismissMessage = msg;} -- "Set a
     *  message to be sent when this dialog is dismissed", the Message-based
     *  alternative to setOnDismissListener. Was a no-op, which silently dropped
     *  the notification; dismiss() now sends it. */
    public void setDismissMessage(android.os.Message msg) { mDismissMessage = msg; }

    /** AOSP setCancelMessage(Message): {@code mCancelMessage = msg;} -- "Set a
     *  message to be sent when this dialog is cancelled." AlertDialog.Builder
     *  and androidx's dialog fragments both go through this form rather than
     *  the listener. cancel() sends it. */
    public void setCancelMessage(android.os.Message msg) { mCancelMessage = msg; }
    /** AOSP Dialog.java:833-837 -- `if (mDecor != null) mWindowManager
     *  .updateViewLayout(mDecor, params);`. This was an empty stub.
     *
     *  ⚠ THE FAILURE IT CAUSED IS SUBTLE, WHICH IS WHY IT SURVIVED. A post-show
     *  `getWindow().setLayout(...)` DID reach WindowOverlayStack -- but only by
     *  ALIASING: `show()` hands addView the LIVE `mWindow.getAttributes()`
     *  object and `WindowOverlayStack.asWlp` stores that same instance, so
     *  mutating it mutates the layer's params in place. What aliasing cannot do
     *  is set `sDirty`. On the GL path `presentForGl` early-returns when
     *  `!sDirty`, so the new bounds were re-measured every frame while the
     *  rasterised bitmap went stale: new geometry, old pixels.
     *  Measured consequence in v1.0.879: an AlertDialog `setLayout(767, ...)`
     *  moved the panel only 743 -> 729 instead of to the intended 703.
     *  BottomSheetDialog resizes through this exact path after show(). */
    public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams params) {
        try {
            if (mDecorAdded != null && mAddedTo != null) {
                mAddedTo.updateViewLayout(mDecorAdded, params);
            }
        } catch (Throwable ignored) {}
    }

    /** Gate MACAPK_DIALOG_CREATE (default ON; =0 restores the old empty
     *  create(), where onCreate first ran at show()). */
    static final boolean MACAPK_DIALOG_CREATE =
        !"0".equals(System.getenv("MACAPK_DIALOG_CREATE"));

    /** AOSP Dialog.create() (android14-release Dialog.java:299-303):
     *      public void create() { if (!mCreated) { dispatchOnCreate(null); } }
     *  "Forces immediate creation of the dialog": onCreate (AlertDialog's
     *  AlertController.installContent) runs NOW, so the app can find and set up
     *  its custom view before show(). Ours was `{ }`. Mastodon's
     *  M3AlertDialogBuilder.create() calls alert.create() and then
     *  alert.findViewById(R.id.picker).setMaxDate(...) on its date-of-birth
     *  dialog; with the empty create() that view did not exist yet, the click
     *  handler died with an NPE and the dialog never opened. */
    public void create() {
        if (!MACAPK_DIALOG_CREATE) return;
        macapkDispatchOnCreate();
    }

    /** AOSP dispatchOnCreate (Dialog.java:434-439): onCreate exactly once. The
     *  flag is set BEFORE the call here, as it always was in show(), so a
     *  re-entrant show()/create() from inside onCreate cannot run it twice. */
    private void macapkDispatchOnCreate() {
        // These used to swallow silently. They must NOT (v1.0.867): a Dialog
        // subclass builds ALL of its content in onCreate -- androidx's
        // AlertDialog does its AlertController.installContent() there, and
        // AppCompatDialog its subdecor creation -- so an exception thrown in
        // here produces a dialog that is shown, measured, and painted with its
        // window background but is COMPLETELY EMPTY, with nothing in the log.
        // Still caught (a half-built dialog must not take down the activity),
        // but never in silence.
        if (!mCreated) {
            mCreated = true;
            try { onCreate(null); }
            catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Mac.apk.Dialog",
                    "onCreate THREW -> dialog will be blank/partial: " + t);
                macapkLogTrace(t);
            }
        }
    }

    // ================================================================
    // AOSP API-36 surface completion (android.app.Dialog).
    // AOSP ref: frameworks/base/core/java/android/app/Dialog.java
    // (android16-release).
    // ================================================================

    /** AOSP openOptionsMenu(): {@code if (mWindow.hasFeature(FEATURE_OPTIONS_PANEL))
     *  mWindow.openPanel(FEATURE_OPTIONS_PANEL, null);}. android.view.Window in
     *  this tree has no panel machinery (openPanel/closePanel/hasFeature for
     *  FEATURE_OPTIONS_PANEL), so no panel opens -- the same as a dialog whose
     *  window never requested the options-panel feature, which is AOSP's own
     *  guarded no-op branch. Reported to the android.view lane. */
    public void openOptionsMenu() { }
    /** AOSP closeOptionsMenu(): the inverse; nothing was opened. */
    public void closeOptionsMenu() { }

    /** AOSP onPrepareOptionsMenu(Menu): {@code return true;} -- note TRUE, not
     *  false: returning false would tell the framework to suppress the menu
     *  entirely. */
    public boolean onPrepareOptionsMenu(android.view.Menu menu) { return true; }
    /** AOSP onOptionsMenuClosed(Menu): empty body. */
    public void onOptionsMenuClosed(android.view.Menu menu) { }

    /** AOSP onContextItemSelected(MenuItem): {@code return false;}. */
    public boolean onContextItemSelected(android.view.MenuItem item) { return false; }
    /** AOSP onContextMenuClosed(Menu): empty body. */
    public void onContextMenuClosed(android.view.Menu menu) { }
    /** AOSP onCreateContextMenu(ContextMenu, View, ContextMenuInfo): empty body
     *  -- the app override point, reached because registerForContextMenu below
     *  installs this Dialog as the view's listener. */
    @Override
    public void onCreateContextMenu(android.view.ContextMenu menu, View v,
            android.view.ContextMenu.ContextMenuInfo menuInfo) { }

    /** AOSP registerForContextMenu(View): {@code
     *  view.setOnCreateContextMenuListener(this);} verbatim -- this really does
     *  wire the dialog up, and android.view.View has the setter. */
    public void registerForContextMenu(View view) {
        if (view != null) view.setOnCreateContextMenuListener(this);
    }
    /** AOSP unregisterForContextMenu(View): {@code
     *  view.setOnCreateContextMenuListener(null);} verbatim. */
    public void unregisterForContextMenu(View view) {
        if (view != null) view.setOnCreateContextMenuListener(null);
    }
    /** AOSP openContextMenu(View): {@code view.showContextMenu();}.
     *  android.view.View in this tree has createContextMenu(ContextMenu) but no
     *  showContextMenu() and there is no ContextMenu implementation to show, so
     *  nothing is displayed -- same situation as Activity.openContextMenu.
     *  Reported to the android.view lane. */
    public void openContextMenu(View view) { }

    /** AOSP takeKeyEvents(boolean): {@code mWindow.takeKeyEvents(get);} -- see
     *  Activity.takeKeyEvents for why this is already in effect here. */
    public void takeKeyEvents(boolean get) { mTakeKeyEvents = get; }
    private boolean mTakeKeyEvents = true;

    /** AOSP getActionBar(): {@code return mActionBar;} -- non-null only when the
     *  dialog's window requested FEATURE_ACTION_BAR. Ours never does, and AOSP
     *  returns null in exactly that case. */
    public ActionBar getActionBar() { return null; }

    /** AOSP getSearchEvent(): {@code return mSearchEvent;} -- non-null only
     *  inside onSearchRequested(SearchEvent). We never synthesise one (see
     *  Activity.getSearchEvent). final, per AOSP. */
    public final android.view.SearchEvent getSearchEvent() { return null; }

    /** AOSP onKeyShortcut(int, KeyEvent): {@code return false;} -- unlike
     *  Activity's, Dialog's AOSP body really is an unconditional false. */
    public boolean onKeyShortcut(int keyCode, KeyEvent event) { return false; }

    /** AOSP setFeatureDrawableAlpha(int, int): {@code
     *  getWindow().setFeatureDrawableAlpha(featureId, alpha);}. See
     *  Activity.setFeatureDrawable for why no feature decoration exists to
     *  alpha-blend here. final, per AOSP. */
    public final void setFeatureDrawableAlpha(int featureId, int alpha) { }
}
